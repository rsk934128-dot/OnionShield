package com.example.browser

data class ParsedDnsQueryPacket(
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val dnsPayload: ByteArray,
    val domainName: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedDnsQueryPacket) return false
        return srcIp.contentEquals(other.srcIp) &&
            dstIp.contentEquals(other.dstIp) &&
            srcPort == other.srcPort &&
            dstPort == other.dstPort &&
            dnsPayload.contentEquals(other.dnsPayload) &&
            domainName == other.domainName
    }

    override fun hashCode(): Int {
        var result = srcIp.contentHashCode()
        result = 31 * result + dstIp.contentHashCode()
        result = 31 * result + srcPort
        result = 31 * result + dstPort
        result = 31 * result + dnsPayload.contentHashCode()
        result = 31 * result + (domainName?.hashCode() ?: 0)
        return result
    }
}

object DnsVpnPacketCodec {

    private const val IPV4_MIN_HEADER_LEN = 20
    private const val UDP_HEADER_LEN = 8
    private const val IP_PROTOCOL_UDP = 17
    private const val DNS_PORT = 53
    private const val DNS_HEADER_LEN = 12

    fun parseIpv4UdpDnsQuery(packet: ByteArray, length: Int): ParsedDnsQueryPacket? {
        if (length < IPV4_MIN_HEADER_LEN + UDP_HEADER_LEN + DNS_HEADER_LEN) return null

        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < IPV4_MIN_HEADER_LEN || length < ihl + UDP_HEADER_LEN + DNS_HEADER_LEN) return null

        val totalLength = readUInt16(packet, 2).coerceAtMost(length)
        if (totalLength < ihl + UDP_HEADER_LEN + DNS_HEADER_LEN) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != IP_PROTOCOL_UDP) return null

        val srcPort = readUInt16(packet, ihl)
        val dstPort = readUInt16(packet, ihl + 2)
        if (dstPort != DNS_PORT) return null

        val udpLength = readUInt16(packet, ihl + 4)
        if (udpLength < UDP_HEADER_LEN + DNS_HEADER_LEN || ihl + udpLength > totalLength) return null

        val dnsStart = ihl + UDP_HEADER_LEN
        val dnsLen = udpLength - UDP_HEADER_LEN
        val dnsPayload = packet.copyOfRange(dnsStart, dnsStart + dnsLen)
        val domainName = extractDnsQuestionDomain(dnsPayload, dnsPayload.size)

        return ParsedDnsQueryPacket(
            srcIp = packet.copyOfRange(12, 16),
            dstIp = packet.copyOfRange(16, 20),
            srcPort = srcPort,
            dstPort = dstPort,
            dnsPayload = dnsPayload,
            domainName = domainName,
        )
    }

    fun buildIpv4UdpDnsResponse(
        query: ParsedDnsQueryPacket,
        dnsResponsePayload: ByteArray,
    ): ByteArray {
        val udpLength = UDP_HEADER_LEN + dnsResponsePayload.size
        val totalLength = IPV4_MIN_HEADER_LEN + udpLength
        val out = ByteArray(totalLength)

        // IPv4 Header (20 bytes)
        out[0] = 0x45.toByte() // Version 4, IHL 5 (20 bytes)
        out[1] = 0x00
        writeUInt16(out, 2, totalLength)
        writeUInt16(out, 4, (query.dnsPayload[0].toInt() and 0xFF shl 8) or (query.dnsPayload[1].toInt() and 0xFF))
        writeUInt16(out, 6, 0x4000) // Don't Fragment
        out[8] = 64.toByte() // TTL
        out[9] = IP_PROTOCOL_UDP.toByte()
        writeUInt16(out, 10, 0) // Checksum placeholder
        System.arraycopy(query.dstIp, 0, out, 12, 4) // Swap source & destination IP
        System.arraycopy(query.srcIp, 0, out, 16, 4)
        val ipChecksum = computeInternetChecksum(out, 0, IPV4_MIN_HEADER_LEN)
        writeUInt16(out, 10, ipChecksum)

        // UDP Header (8 bytes)
        val udpStart = IPV4_MIN_HEADER_LEN
        writeUInt16(out, udpStart, query.dstPort)
        writeUInt16(out, udpStart + 2, query.srcPort)
        writeUInt16(out, udpStart + 4, udpLength)
        writeUInt16(out, udpStart + 6, 0) // UDP checksum placeholder
        System.arraycopy(dnsResponsePayload, 0, out, udpStart + UDP_HEADER_LEN, dnsResponsePayload.size)

        val udpChecksum = computeUdpIpv4Checksum(
            srcIp = query.dstIp,
            dstIp = query.srcIp,
            udpSegment = out,
            udpOffset = udpStart,
            udpLength = udpLength,
        )
        writeUInt16(out, udpStart + 6, udpChecksum)
        return out
    }

    fun extractDnsQuestionDomain(dnsPayload: ByteArray, length: Int): String? {
        if (length <= DNS_HEADER_LEN) return null
        val qdCount = readUInt16(dnsPayload, 4)
        if (qdCount <= 0) return null

        var offset = DNS_HEADER_LEN
        val labels = mutableListOf<String>()
        var guard = 0
        while (offset < length && guard < 128) {
            guard++
            val labelLen = dnsPayload[offset].toInt() and 0xFF
            if (labelLen == 0) break
            // Reject compression pointers in question section bounds
            if ((labelLen and 0xC0) == 0xC0) break
            offset++
            if (offset + labelLen > length) return null
            val label = String(dnsPayload, offset, labelLen, Charsets.US_ASCII)
            labels.add(label)
            offset += labelLen
        }
        return labels.takeIf { it.isNotEmpty() }?.joinToString(".")?.lowercase()
    }

    fun buildErrorDnsResponse(dnsQuery: ByteArray, rcode: Int): ByteArray? {
        if (dnsQuery.size < DNS_HEADER_LEN) return null
        val response = dnsQuery.copyOf()
        // QR=1 (response), preserve RD bit, RA=1, RCODE=rcode (3=NXDOMAIN, 2=SERVFAIL)
        val rdBit = dnsQuery[2].toInt() and 0x01
        response[2] = (0x80 or rdBit).toByte()
        response[3] = (0x80 or (rcode and 0x0F)).toByte()
        // Zero ANCOUNT, NSCOUNT, ARCOUNT so question remains valid
        response[6] = 0
        response[7] = 0
        response[8] = 0
        response[9] = 0
        response[10] = 0
        response[11] = 0
        return response
    }

    fun computeInternetChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    private fun computeUdpIpv4Checksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        udpSegment: ByteArray,
        udpOffset: Int,
        udpLength: Int,
    ): Int {
        var sum = 0L
        // Pseudo-header: srcIp (4B) + dstIp (4B) + 0x0011 (protocol 17) + udpLength (2B)
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += IP_PROTOCOL_UDP.toLong()
        sum += udpLength.toLong()

        var i = udpOffset
        val end = udpOffset + udpLength
        while (i + 1 < end) {
            val word = ((udpSegment[i].toInt() and 0xFF) shl 8) or (udpSegment[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (udpSegment[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        val checksum = sum.inv().toInt() and 0xFFFF
        return if (checksum == 0) 0xFFFF else checksum
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun writeUInt16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }
}

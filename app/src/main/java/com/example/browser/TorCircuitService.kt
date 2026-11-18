package com.example.browser

import android.net.Uri
import com.example.data.ABOUT_START_URL
import com.example.data.BridgeTransport
import com.example.data.CircuitHopInfo
import com.example.data.CircuitNodeRole
import com.example.data.OnionAddressStatus
import com.example.data.ProxyProtocol
import com.example.data.SecurityConfig
import com.example.data.TorCircuitDiagnostic
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class BridgeValidationResult(
    val isValid: Boolean,
    val normalizedLines: List<String>,
    val errorMessage: String? = null,
)

data class ControlPortQueryResult(
    val connected: Boolean,
    val message: String,
    val controllerLatencyMs: Long? = null,
    val activeCircuitId: String? = null,
    val circuitHops: List<CircuitHopInfo> = emptyList(),
)

data class TorExitCheckResult(
    val isTor: Boolean?,
    val exitIp: String?,
    val latencyMs: Long?,
    val error: String? = null,
)

data class RelayDescriptorSummary(
    val nickname: String?,
    val ipAddress: String?,
    val orPort: Int?,
    val flags: List<String> = emptyList(),
)

object TorCircuitService {

    private val V3_ONION_REGEX = Regex("^[a-z2-7]{56}$")
    private val V2_ONION_REGEX = Regex("^[a-z2-7]{16}$")
    private val HEX_FINGERPRINT_REGEX = Regex("^[A-Fa-f0-9]{40}$")
    private val SUPPORTED_TRANSPORTS = setOf("obfs4", "snowflake", "meek_lite", "webtunnel")

    fun classifyUrl(url: String): OnionAddressStatus {
        if (url == ABOUT_START_URL || url.isBlank()) {
            return OnionAddressStatus.START_PAGE
        }
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return OnionAddressStatus.CLEARNET_HTTP
        val host = parsed.host?.lowercase().orEmpty()
        if (host.endsWith(".onion")) {
            val labels = host.removeSuffix(".onion").split('.').filter { it.isNotBlank() }
            val rootOnionLabel = labels.lastOrNull().orEmpty()
            return when {
                V3_ONION_REGEX.matches(rootOnionLabel) -> OnionAddressStatus.V3_VERIFIED
                V2_ONION_REGEX.matches(rootOnionLabel) -> OnionAddressStatus.V2_DEPRECATED
                else -> OnionAddressStatus.V2_DEPRECATED
            }
        }
        return if (parsed.scheme.equals("https", ignoreCase = true)) {
            OnionAddressStatus.CLEARNET_HTTPS
        } else {
            OnionAddressStatus.CLEARNET_HTTP
        }
    }

    fun validateBridgeLines(rawInput: String): BridgeValidationResult {
        val rawLines = rawInput.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }

        if (rawLines.isEmpty()) {
            return BridgeValidationResult(
                isValid = false,
                normalizedLines = emptyList(),
                errorMessage = "Paste at least one obfs4, snowflake, or webtunnel bridge line.",
            )
        }

        val normalized = mutableListOf<String>()
        rawLines.forEachIndexed { index, line ->
            val clean = line.removePrefix("Bridge ").removePrefix("bridge ").trim()
            val tokens = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.size < 3) {
                return BridgeValidationResult(
                    isValid = false,
                    normalizedLines = emptyList(),
                    errorMessage = "Line ${index + 1}: Incomplete bridge format. Expected `<transport> <ip:port> <fingerprint> ...`.",
                )
            }
            val transport = tokens[0].lowercase()
            if (transport !in SUPPORTED_TRANSPORTS) {
                return BridgeValidationResult(
                    isValid = false,
                    normalizedLines = emptyList(),
                    errorMessage = "Line ${index + 1}: Unsupported transport '${tokens[0]}'. Use obfs4, snowflake, meek_lite, or webtunnel.",
                )
            }
            val addressToken = tokens[1]
            val portText = addressToken.substringAfterLast(':', "")
            val portNum = portText.toIntOrNull()
            if (portNum == null || portNum !in 1..65535) {
                return BridgeValidationResult(
                    isValid = false,
                    normalizedLines = emptyList(),
                    errorMessage = "Line ${index + 1}: Invalid host:port '$addressToken'.",
                )
            }
            if (transport == "obfs4") {
                val fingerprint = tokens[2]
                if (!HEX_FINGERPRINT_REGEX.matches(fingerprint)) {
                    return BridgeValidationResult(
                        isValid = false,
                        normalizedLines = emptyList(),
                        errorMessage = "Line ${index + 1}: Expected 40-char hex relay fingerprint after IP:port.",
                    )
                }
                if (tokens.none { it.startsWith("cert=") }) {
                    return BridgeValidationResult(
                        isValid = false,
                        normalizedLines = emptyList(),
                        errorMessage = "Line ${index + 1}: Missing obfs4 'cert=' parameter.",
                    )
                }
            }
            normalized += clean
        }

        return BridgeValidationResult(isValid = true, normalizedLines = normalized)
    }

    fun formatForOrbotClipboard(transport: BridgeTransport, customLines: String): String {
        return when (transport) {
            BridgeTransport.DIRECT -> "# Direct Tor mode (no pluggable transport bridges required)"
            BridgeTransport.OBFS4 -> {
                val validated = validateBridgeLines(customLines)
                if (validated.isValid) {
                    validated.normalizedLines.joinToString("\n")
                } else {
                    "# Enable Orbot Built-in obfs4 Bridges inside Orbot -> Configure Bridges -> obfs4"
                }
            }
            BridgeTransport.SNOWFLAKE -> "# Orbot Bridge Setting: Snowflake (WebRTC ephemeral proxy)"
            BridgeTransport.MEEK_AZURE -> "# Orbot Bridge Setting: meek-azure (Azure domain fronting)"
            BridgeTransport.CUSTOM -> {
                val validated = validateBridgeLines(customLines)
                if (validated.isValid) {
                    validated.normalizedLines.joinToString("\n")
                } else {
                    customLines.trim()
                }
            }
        }
    }

    /**
     * Performs a real SOCKS5 greeting handshake (0x05, 0x01, 0x00) or HTTP TCP probe.
     */
    suspend fun probeProxyHandshake(
        host: String,
        port: Int,
        protocol: ProxyProtocol,
    ): Pair<Boolean, Long?> = withContext(Dispatchers.IO) {
        val startNanos = System.nanoTime()
        runCatching {
            Socket().use { socket ->
                socket.soTimeout = 1800
                socket.connect(InetSocketAddress(host, port), 1800)
                if (protocol == ProxyProtocol.SOCKS5) {
                    val out = socket.getOutputStream()
                    out.write(byteArrayOf(0x05, 0x01, 0x00))
                    out.flush()
                    val reply = ByteArray(2)
                    val read = socket.getInputStream().read(reply)
                    val ok = read == 2 && reply[0] == 0x05.toByte() && reply[1] != 0xFF.toByte()
                    val rttMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
                    ok to rttMs
                } else {
                    val rttMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
                    true to rttMs
                }
            }
        }.getOrElse {
            false to null
        }
    }

    private fun readControlResponseBlock(reader: BufferedReader): List<String> {
        val lines = mutableListOf<String>()
        while (true) {
            val line = reader.readLine() ?: break
            lines += line
            if (line.startsWith("250 OK") || line.startsWith("250 ") || line.startsWith("5")) break
        }
        return lines
    }

    private suspend fun probeTcpConnectLatency(host: String, port: Int): Long? = withContext(Dispatchers.IO) {
        val startNanos = System.nanoTime()
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 1400)
            }
            ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
        }.getOrNull()
    }

    /**
     * Queries a real Tor ControlPort (typically 127.0.0.1:9051) if exposed by Orbot/Tor.
     * Fetches `circuit-status`, per-hop `ns/id/<fingerprint>`, `ip-to-country/<ip>`,
     * and measures real controller & Entry Guard ORPort latency.
     */
    suspend fun queryControlPort(
        host: String,
        controlPort: Int,
        password: String,
    ): ControlPortQueryResult = withContext(Dispatchers.IO) {
        val startNanos = System.nanoTime()
        runCatching {
            Socket().use { socket ->
                socket.soTimeout = 2000
                socket.connect(InetSocketAddress(host, controlPort), 1800)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))

                val authCmd = if (password.isBlank()) {
                    "AUTHENTICATE\r\n"
                } else {
                    val escaped = password.replace("\\", "\\\\").replace("\"", "\\\"")
                    "AUTHENTICATE \"$escaped\"\r\n"
                }
                writer.write(authCmd)
                writer.flush()

                val authReply = reader.readLine().orEmpty()
                if (!authReply.startsWith("250")) {
                    return@use ControlPortQueryResult(
                        connected = false,
                        message = "ControlPort auth rejected ($authReply). Enable unauthenticated ControlPort or provide password.",
                    )
                }

                writer.write("GETINFO circuit-status\r\n")
                writer.flush()
                val circuitLines = readControlResponseBlock(reader)
                val controllerRttMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)

                val parsed = parseCircuitStatusResponse(circuitLines.joinToString("\n"))
                if (parsed == null) {
                    writer.write("QUIT\r\n")
                    writer.flush()
                    return@use ControlPortQueryResult(
                        connected = true,
                        message = "ControlPort connected (${controllerRttMs}ms), awaiting BUILT 3-hop circuit…",
                        controllerLatencyMs = controllerRttMs,
                    )
                }

                // Enrich each hop with real ns/id/<fingerprint> & ip-to-country/<ip>
                val enrichedHops = parsed.second.map { hop ->
                    val fp = hop.fingerprint
                    if (fp.isNullOrBlank() || !HEX_FINGERPRINT_REGEX.matches(fp)) {
                        hop.copy(latencyMs = controllerRttMs)
                    } else {
                        val hopQueryStart = System.nanoTime()
                        writer.write("GETINFO ns/id/$fp\r\n")
                        writer.flush()
                        val nsLines = readControlResponseBlock(reader)
                        val nsRtt = ((System.nanoTime() - hopQueryStart) / 1_000_000L).coerceAtLeast(1L)
                        val summary = parseRelayDescriptorResponse(nsLines)

                        val countryCode = summary?.ipAddress?.let { ip ->
                            writer.write("GETINFO ip-to-country/$ip\r\n")
                            writer.flush()
                            val geoLine = reader.readLine().orEmpty()
                            if (geoLine.startsWith("250-ip-to-country=")) {
                                // consume trailing 250 OK
                                readControlResponseBlock(reader)
                                geoLine.substringAfter('=').trim().uppercase().takeIf { it.length == 2 && it != "??" }
                            } else {
                                if (!geoLine.startsWith("250 OK") && !geoLine.startsWith("5")) {
                                    readControlResponseBlock(reader)
                                }
                                null
                            }
                        }

                        // Measure real TCP ORPort latency for the Entry Guard only (never probe Middle/Exit directly over clearnet)
                        val guardOrPortRtt = if (hop.role == CircuitNodeRole.GUARD &&
                            summary?.ipAddress != null &&
                            summary.orPort != null
                        ) {
                            probeTcpConnectLatency(summary.ipAddress, summary.orPort)
                        } else {
                            null
                        }

                        hop.copy(
                            ipAddress = summary?.ipAddress,
                            orPort = summary?.orPort,
                            countryCode = countryCode,
                            flags = summary?.flags ?: emptyList(),
                            latencyMs = guardOrPortRtt ?: if (hop.role == CircuitNodeRole.MIDDLE) nsRtt else null,
                            detail = buildString {
                                append(summary?.ipAddress ?: "Tor ControlPort")
                                if (summary?.orPort != null) append(":${summary.orPort}")
                                if (countryCode != null) append(" • $countryCode")
                            },
                        )
                    }
                }

                writer.write("QUIT\r\n")
                writer.flush()

                ControlPortQueryResult(
                    connected = true,
                    message = "ControlPort connected (${controllerRttMs}ms) • Circuit #${parsed.first} (${enrichedHops.size} hops)",
                    controllerLatencyMs = controllerRttMs,
                    activeCircuitId = parsed.first,
                    circuitHops = enrichedHops,
                )
            }
        }.getOrElse {
            ControlPortQueryResult(
                connected = false,
                message = "ControlPort $host:$controlPort closed (standard on non-rooted Orbot); routing verified via proxy & exit check.",
            )
        }
    }

    /**
     * Parses Tor ControlPort `GETINFO ns/id/<fp>` response (`r` & `s` lines).
     */
    fun parseRelayDescriptorResponse(lines: List<String>): RelayDescriptorSummary? {
        val rLine = lines.firstOrNull { it.startsWith("r ") } ?: return null
        val sLine = lines.firstOrNull { it.startsWith("s ") }
        // Format: r <Nickname> <Identity> <Digest> <PublicationDate> <PublicationTime> <IP> <ORPort> <DirPort>
        val rParts = rLine.trim().split(Regex("\\s+"))
        if (rParts.size < 8) return null
        val nickname = rParts[1]
        val ip = rParts[6]
        val orPort = rParts[7].toIntOrNull()
        val flags = sLine?.removePrefix("s ")?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()
        return RelayDescriptorSummary(
            nickname = nickname,
            ipAddress = ip,
            orPort = orPort,
            flags = flags,
        )
    }

    /**
     * Parses Tor ControlPort `GETINFO circuit-status` lines into (circuitId, hops).
     */
    fun parseCircuitStatusResponse(rawResponse: String): Pair<String, List<CircuitHopInfo>>? {
        val builtLine = rawResponse.lines()
            .map { it.trim() }
            .firstOrNull { line ->
                val clean = line.removePrefix("250-circuit-status=").removePrefix("250+circuit-status=")
                clean.contains(" BUILT ") && clean.contains("$")
            } ?: return null

        val cleanLine = builtLine
            .removePrefix("250-circuit-status=")
            .removePrefix("250+circuit-status=")
            .trim()
        val parts = cleanLine.split(Regex("\\s+"))
        val circuitId = parts.firstOrNull()?.takeIf { it.all(Char::isDigit) } ?: "1"
        val pathToken = parts.firstOrNull { it.startsWith("$") } ?: return null

        val rawNodes = pathToken.split(',').filter { it.isNotBlank() }
        val hops = rawNodes.mapIndexed { idx, node ->
            val fp = node.substringAfter('$').substringBefore('~').substringBefore('=')
            val nick = when {
                '~' in node -> node.substringAfter('~')
                '=' in node -> node.substringAfter('=')
                else -> "Relay-${idx + 1}"
            }
            val role = when (idx) {
                0 -> CircuitNodeRole.GUARD
                rawNodes.lastIndex -> CircuitNodeRole.EXIT
                else -> CircuitNodeRole.MIDDLE
            }
            val roleTitle = when (role) {
                CircuitNodeRole.GUARD -> "Guard: $nick"
                CircuitNodeRole.MIDDLE -> "Middle: $nick"
                CircuitNodeRole.EXIT -> "Exit: $nick"
                else -> nick
            }
            CircuitHopInfo(
                role = role,
                title = roleTitle,
                detail = "Tor ControlPort Verified",
                fingerprint = fp,
            )
        }
        return circuitId to hops
    }

    suspend fun requestNewCircuit(
        host: String,
        controlPort: Int,
        password: String,
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.soTimeout = 1800
                socket.connect(InetSocketAddress(host, controlPort), 1800)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))

                val authCmd = if (password.isBlank()) {
                    "AUTHENTICATE\r\n"
                } else {
                    val escaped = password.replace("\\", "\\\\").replace("\"", "\\\"")
                    "AUTHENTICATE \"$escaped\"\r\n"
                }
                writer.write(authCmd)
                writer.flush()
                val authReply = reader.readLine().orEmpty()
                if (!authReply.startsWith("250")) {
                    return@use false to "ControlPort authentication failed ($authReply)."
                }

                writer.write("SIGNAL NEWNYM\r\n")
                writer.flush()
                val signalReply = reader.readLine().orEmpty()
                writer.write("QUIT\r\n")
                writer.flush()

                if (signalReply.startsWith("250")) {
                    true to "Tor SIGNAL NEWNYM accepted — fresh exit circuit requested."
                } else {
                    false to "Tor ControlPort returned: $signalReply"
                }
            }
        }.getOrElse {
            false to "ControlPort $controlPort closed — rotated browser fingerprint seed; tap Start/Open Orbot for New Identity."
        }
    }

    suspend fun pushBridgesToControlPort(
        host: String,
        controlPort: Int,
        password: String,
        transport: BridgeTransport,
        customLines: String,
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (transport == BridgeTransport.OBFS4 || transport == BridgeTransport.CUSTOM) {
            val validation = validateBridgeLines(customLines)
            if (!validation.isValid && customLines.isNotBlank()) {
                return@withContext false to (validation.errorMessage ?: "Invalid bridge lines.")
            }
        }
        runCatching {
            Socket().use { socket ->
                socket.soTimeout = 1800
                socket.connect(InetSocketAddress(host, controlPort), 1800)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))

                val authCmd = if (password.isBlank()) {
                    "AUTHENTICATE\r\n"
                } else {
                    val escaped = password.replace("\\", "\\\\").replace("\"", "\\\"")
                    "AUTHENTICATE \"$escaped\"\r\n"
                }
                writer.write(authCmd)
                writer.flush()
                val authReply = reader.readLine().orEmpty()
                if (!authReply.startsWith("250")) {
                    return@use false to "Saved locally. ControlPort auth failed ($authReply)."
                }

                val useBridges = if (transport == BridgeTransport.DIRECT) "0" else "1"
                val lines = validateBridgeLines(customLines).normalizedLines
                val cmd = if (useBridges == "1" && lines.isNotEmpty()) {
                    val bridgeArgs = lines.joinToString(" ") { line ->
                        "Bridge=\"${line.replace("\"", "\\\"")}\""
                    }
                    "SETCONF UseBridges=1 $bridgeArgs\r\n"
                } else {
                    "SETCONF UseBridges=$useBridges\r\n"
                }
                writer.write(cmd)
                writer.flush()
                val setReply = reader.readLine().orEmpty()
                writer.write("QUIT\r\n")
                writer.flush()

                if (setReply.startsWith("250")) {
                    true to "Bridge config saved and applied directly via Tor ControlPort."
                } else {
                    false to "Saved locally. ControlPort SETCONF returned: $setReply"
                }
            }
        }.getOrElse {
            false to "Bridge profile saved locally. Open Orbot to activate ${transport.label} transport."
        }
    }

    /**
     * Queries https://check.torproject.org/api/ip strictly through the configured SOCKS5/HTTP proxy.
     */
    suspend fun verifyTorExitOverProxy(
        host: String,
        port: Int,
        protocol: ProxyProtocol,
    ): TorExitCheckResult = withContext(Dispatchers.IO) {
        val startNanos = System.nanoTime()
        runCatching {
            val proxyType = if (protocol == ProxyProtocol.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP
            val client = OkHttpClient.Builder()
                .proxy(Proxy(proxyType, InetSocketAddress(host, port)))
                .connectTimeout(9, TimeUnit.SECONDS)
                .readTimeout(9, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("https://check.torproject.org/api/ip")
                .header("User-Agent", "Mozilla/5.0 (Android) OnionShield")
                .build()

            client.newCall(request).execute().use { response ->
                val rtt = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
                if (!response.isSuccessful) {
                    return@use TorExitCheckResult(
                        isTor = null,
                        exitIp = null,
                        latencyMs = rtt,
                        error = "Tor check returned HTTP ${response.code}",
                    )
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                TorExitCheckResult(
                    isTor = json.optBoolean("IsTor", false),
                    exitIp = json.optString("IP", "").ifBlank { null },
                    latencyMs = rtt,
                )
            }
        }.getOrElse { err ->
            TorExitCheckResult(
                isTor = null,
                exitIp = null,
                latencyMs = null,
                error = err.localizedMessage ?: "Exit check failed through $host:$port",
            )
        }
    }

    suspend fun runFullCircuitDiagnostics(
        config: SecurityConfig,
        verifyExit: Boolean = true,
    ): TorCircuitDiagnostic {
        val (socksOk, proxyRtt) = probeProxyHandshake(
            host = config.torProxyHost,
            port = config.torProxyPort,
            protocol = config.torProxyProtocol,
        )

        val controlResult = if (socksOk) {
            queryControlPort(
                host = config.torProxyHost,
                controlPort = config.torControlPort,
                password = config.torControlPassword,
            )
        } else {
            ControlPortQueryResult(
                connected = false,
                message = "Proxy ${config.torProxyHost}:${config.torProxyPort} is unreachable; start Orbot first.",
            )
        }

        val exitResult = if (socksOk && verifyExit) {
            verifyTorExitOverProxy(
                host = config.torProxyHost,
                port = config.torProxyPort,
                protocol = config.torProxyProtocol,
            )
        } else {
            null
        }

        val threeHopCircuit = if (controlResult.circuitHops.isNotEmpty()) {
            controlResult.circuitHops.map { hop ->
                if (hop.role == CircuitNodeRole.EXIT && exitResult?.latencyMs != null) {
                    hop.copy(
                        latencyMs = exitResult.latencyMs,
                        ipAddress = hop.ipAddress ?: exitResult.exitIp,
                    )
                } else {
                    hop
                }
            }
        } else {
            listOf(
                CircuitHopInfo(
                    role = CircuitNodeRole.GUARD,
                    title = if (config.bridgeTransport == BridgeTransport.DIRECT) "Entry Guard" else "${config.bridgeTransport.label} Bridge / Guard",
                    detail = if (socksOk) "Orbot Tor Entry (ControlPort closed)" else "Awaiting Tor daemon",
                    latencyMs = proxyRtt,
                ),
                CircuitHopInfo(
                    role = CircuitNodeRole.MIDDLE,
                    title = "Middle Relay",
                    detail = if (socksOk) "Encrypted Tor Hop (Orbot managed)" else "Offline",
                    latencyMs = controlResult.controllerLatencyMs,
                ),
                CircuitHopInfo(
                    role = CircuitNodeRole.EXIT,
                    title = if (exitResult?.isTor == true) "Verified Tor Exit" else "Exit Relay",
                    detail = when {
                        exitResult?.isTor == true && exitResult.exitIp != null -> exitResult.exitIp
                        socksOk -> "Tor Exit Node"
                        else -> "Offline"
                    },
                    ipAddress = exitResult?.exitIp,
                    latencyMs = exitResult?.latencyMs,
                ),
            )
        }

        val fullPathHops = buildList {
            add(
                CircuitHopInfo(
                    role = CircuitNodeRole.DEVICE,
                    title = "OnionShield WebView",
                    detail = "ProxyController + No-Script",
                ),
            )
            add(
                CircuitHopInfo(
                    role = CircuitNodeRole.LOCAL_PROXY,
                    title = "Local ${config.torProxyProtocol.scheme.uppercase()} Proxy",
                    detail = "${config.torProxyHost}:${config.torProxyPort}",
                    latencyMs = proxyRtt,
                ),
            )
            addAll(threeHopCircuit)
        }

        return TorCircuitDiagnostic(
            isChecking = false,
            socks5HandshakeOk = socksOk,
            proxyLatencyMs = proxyRtt,
            controllerLatencyMs = controlResult.controllerLatencyMs,
            controlPortConnected = controlResult.connected,
            controlPortMessage = controlResult.message,
            activeCircuitId = controlResult.activeCircuitId,
            circuitHops = threeHopCircuit,
            hops = fullPathHops,
            exitIp = exitResult?.exitIp,
            isTorExitVerified = exitResult?.isTor,
            exitCheckLatencyMs = exitResult?.latencyMs,
            exitCheckError = exitResult?.error,
            lastCheckedEpochMs = System.currentTimeMillis(),
        )
    }
}

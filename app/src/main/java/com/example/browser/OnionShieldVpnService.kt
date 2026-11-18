package com.example.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.VpnServerProfile
import com.example.data.VpnTunnelStatus
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object VpnTunnelRuntime {
    private val _status = MutableStateFlow<VpnTunnelStatus>(VpnTunnelStatus.Disconnected)
    val status: StateFlow<VpnTunnelStatus> = _status.asStateFlow()

    internal fun setStatus(next: VpnTunnelStatus) {
        _status.value = next
    }

    internal fun recordQuery(
        domain: String?,
        blocked: Boolean,
    ) {
        val current = _status.value
        if (current is VpnTunnelStatus.Connected) {
            _status.value = current.copy(
                queriesTunneled = current.queriesTunneled + 1L,
                blockedQueries = current.blockedQueries + if (blocked) 1L else 0L,
                lastHost = domain?.take(48) ?: current.lastHost,
            )
        }
    }
}

/**
 * Local Android [VpnService] implementing an encrypted DNS-over-HTTPS (DoH) VPN tunnel.
 *
 * Only the virtual DNS gateway route (10.111.222.2/32) is captured on the TUN interface so all
 * browser HTTPS/HTTP traffic routes at full speed while every DNS lookup is encrypted over TLS
 * to the chosen provider (with optional ad/tracker sinkholing) and protected against tunnel loops.
 */
class OnionShieldVpnService : VpnService() {

    private var vpnScope: CoroutineScope? = null
    private var tunnelJob: Job? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private val isTunnelActive = AtomicBoolean(false)
    private var activeProfile: VpnServerProfile = VpnServerProfile.CLOUDFLARE_WARP

    override fun onCreate() {
        super.onCreate()
        ensureVpnNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnectVpn()
                return START_NOT_STICKY
            }
            else -> {
                val requestedProfile = intent?.getStringExtra(EXTRA_VPN_PROFILE)?.let { name ->
                    runCatching { VpnServerProfile.valueOf(name) }.getOrNull()
                } ?: VpnServerProfile.CLOUDFLARE_WARP
                connectVpn(requestedProfile)
                return START_STICKY
            }
        }
    }

    private fun connectVpn(profile: VpnServerProfile) {
        if (VpnService.prepare(this) != null) {
            VpnTunnelRuntime.setStatus(
                VpnTunnelStatus.Error("VPN system permission not granted yet. Tap Connect VPN to authorize."),
            )
            stopSelf()
            return
        }

        if (isTunnelActive.get() && activeProfile == profile && tunInterface != null) {
            promoteForeground(profile)
            return
        }

        activeProfile = profile
        VpnTunnelRuntime.setStatus(VpnTunnelStatus.Connecting(profile))
        promoteForeground(profile)

        closeTunQuietly()
        tunnelJob?.cancel()

        val descriptor = runCatching {
            Builder()
                .setSession("OnionShield VPN (${profile.badge})")
                .setMtu(1500)
                .addAddress(TUN_CLIENT_IP, 30)
                .addDnsServer(TUN_DNS_IP)
                .addRoute(TUN_DNS_IP, 32)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setMetered(false)
                    }
                }
                .establish()
        }.getOrElse { err ->
            Log.e(TAG, "Failed to establish VpnService TUN interface", err)
            VpnTunnelRuntime.setStatus(
                VpnTunnelStatus.Error(err.localizedMessage ?: "Failed to establish VPN interface."),
            )
            stopForegroundAndSelf()
            return
        }

        if (descriptor == null) {
            VpnTunnelRuntime.setStatus(
                VpnTunnelStatus.Error("Android returned a null VPN TUN descriptor."),
            )
            stopForegroundAndSelf()
            return
        }

        tunInterface = descriptor
        isTunnelActive.set(true)
        VpnTunnelRuntime.setStatus(
            VpnTunnelStatus.Connected(
                profile = profile,
                tunnelIp = TUN_CLIENT_IP,
                connectedSinceEpochMs = System.currentTimeMillis(),
            ),
        )

        val scope = vpnScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also { vpnScope = it }
        val dohClient = createProtectedDohClient(profile)

        tunnelJob = scope.launch {
            runTunDnsLoop(
                descriptor = descriptor,
                profile = profile,
                dohClient = dohClient,
                scope = this,
            )
        }
    }

    private fun runTunDnsLoop(
        descriptor: ParcelFileDescriptor,
        profile: VpnServerProfile,
        dohClient: OkHttpClient,
        scope: CoroutineScope,
    ) {
        val inputStream = FileInputStream(descriptor.fileDescriptor)
        val outputStream = FileOutputStream(descriptor.fileDescriptor)
        val writeLock = Any()
        val packetBuffer = ByteArray(32767)

        try {
            while (scope.isActive && isTunnelActive.get()) {
                val bytesRead = inputStream.read(packetBuffer)
                if (bytesRead <= 0) continue

                val query = DnsVpnPacketCodec.parseIpv4UdpDnsQuery(packetBuffer, bytesRead) ?: continue
                val domain = query.domainName

                // Optional DNS-level tracker sinkholing in AdGuard profile
                val shouldSinkhole = profile == VpnServerProfile.ADGUARD_PRIVACY &&
                    domain != null &&
                    TrackerAndUrlEngine.isTrackerRequest("https://$domain")

                scope.launch(Dispatchers.IO) {
                    val dnsResponsePayload = if (shouldSinkhole) {
                        VpnTunnelRuntime.recordQuery(domain = domain, blocked = true)
                        DnsVpnPacketCodec.buildErrorDnsResponse(query.dnsPayload, RCODE_NXDOMAIN)
                    } else {
                        val resolved = resolveDnsOverHttps(dohClient, profile.dohUrl, query.dnsPayload)
                        VpnTunnelRuntime.recordQuery(domain = domain, blocked = false)
                        resolved ?: DnsVpnPacketCodec.buildErrorDnsResponse(query.dnsPayload, RCODE_SERVFAIL)
                    }

                    if (dnsResponsePayload != null && isTunnelActive.get()) {
                        val responsePacket = DnsVpnPacketCodec.buildIpv4UdpDnsResponse(
                            query = query,
                            dnsResponsePayload = dnsResponsePayload,
                        )
                        synchronized(writeLock) {
                            runCatching {
                                outputStream.write(responsePacket)
                                outputStream.flush()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (isTunnelActive.get()) {
                Log.w(TAG, "VPN TUN loop terminated: ${e.localizedMessage}")
            }
        }
    }

    private fun resolveDnsOverHttps(
        client: OkHttpClient,
        dohUrl: String,
        dnsWireQuery: ByteArray,
    ): ByteArray? {
        return runCatching {
            val request = Request.Builder()
                .url(dohUrl)
                .header("Accept", DOH_MEDIA_TYPE)
                .post(dnsWireQuery.toRequestBody(DOH_MEDIA_TYPE.toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.bytes()?.takeIf { it.size >= 12 }
            }
        }.getOrNull()
    }

    private fun createProtectedDohClient(profile: VpnServerProfile): OkHttpClient {
        val protectedFactory = object : SocketFactory() {
            private val delegate = getDefault()

            override fun createSocket(): Socket = delegate.createSocket().also { protect(it) }
            override fun createSocket(host: String?, port: Int): Socket =
                delegate.createSocket(host, port).also { protect(it) }
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                delegate.createSocket(host, port, localHost, localPort).also { protect(it) }
            override fun createSocket(host: InetAddress?, port: Int): Socket =
                delegate.createSocket(host, port).also { protect(it) }
            override fun createSocket(
                address: InetAddress?,
                port: Int,
                localAddress: InetAddress?,
                localPort: Int,
            ): Socket = delegate.createSocket(address, port, localAddress, localPort).also { protect(it) }
        }

        // Pin DoH host to its bootstrap IP so encrypted DNS never creates a TUN loop
        val bootstrapDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val doHHost = runCatching { java.net.URI(profile.dohUrl).host }.getOrNull()
                return if (hostname.equals(doHHost, ignoreCase = true)) {
                    listOf(InetAddress.getByName(profile.bootstrapIp))
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
            }
        }

        return OkHttpClient.Builder()
            .socketFactory(protectedFactory)
            .dns(bootstrapDns)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    private fun disconnectVpn() {
        isTunnelActive.set(false)
        tunnelJob?.cancel()
        tunnelJob = null
        closeTunQuietly()
        VpnTunnelRuntime.setStatus(VpnTunnelStatus.Disconnected)
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeTunQuietly() {
        runCatching { tunInterface?.close() }
        tunInterface = null
    }

    override fun onRevoke() {
        disconnectVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        isTunnelActive.set(false)
        tunnelJob?.cancel()
        closeTunQuietly()
        VpnTunnelRuntime.setStatus(VpnTunnelStatus.Disconnected)
        vpnScope?.cancel()
        vpnScope = null
        super.onDestroy()
    }

    private fun promoteForeground(profile: VpnServerProfile) {
        val notification = buildVpnNotification(profile)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun buildVpnNotification(profile: VpnServerProfile): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            201,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = Intent(this, OnionShieldVpnService::class.java).setAction(ACTION_DISCONNECT)
        val disconnectPending = PendingIntent.getService(
            this,
            202,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_notify)
            .setContentTitle(getString(R.string.vpn_notif_title))
            .setContentText("${profile.displayName} (${profile.badge}) • Encrypted DoH Tunnel")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.vpn_disconnect_action), disconnectPending)
            .build()
    }

    private fun ensureVpnNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.vpn_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.vpn_channel_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        private const val TAG = "OnionShieldVPN"
        private const val CHANNEL_ID = "onion_shield_vpn_tunnel"
        private const val NOTIFICATION_ID = 4110
        private const val TUN_CLIENT_IP = "10.111.222.1"
        private const val TUN_DNS_IP = "10.111.222.2"
        private const val DOH_MEDIA_TYPE = "application/dns-message"
        private const val RCODE_SERVFAIL = 2
        private const val RCODE_NXDOMAIN = 3

        const val ACTION_CONNECT = "com.example.action.VPN_CONNECT"
        const val ACTION_DISCONNECT = "com.example.action.VPN_DISCONNECT"
        const val EXTRA_VPN_PROFILE = "extra_vpn_profile"

        fun startTunnel(context: Context, profile: VpnServerProfile) {
            val intent = Intent(context, OnionShieldVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_VPN_PROFILE, profile.name)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopTunnel(context: Context) {
            val intent = Intent(context, OnionShieldVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            runCatching { context.startService(intent) }
        }
    }
}

package com.example.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.example.data.BrowserMode
import com.example.data.ProxyProtocol
import com.example.data.TorProxyState
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object TorProxyController {

    private val mainExecutor: Executor = Executor { runnable -> runnable.run() }

    suspend fun applyProxyMode(
        mode: BrowserMode,
        host: String,
        port: Int,
        protocol: ProxyProtocol,
    ): TorProxyState {
        val supported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        }.getOrDefault(false)
        if (!supported) {
            return if (mode == BrowserMode.TOR_ONION) {
                TorProxyState.UnsupportedApi("WebView PROXY_OVERRIDE is not supported on this Android WebView version.")
            } else {
                TorProxyState.Inactive
            }
        }

        val controller = runCatching { ProxyController.getInstance() }.getOrElse { err ->
            return if (mode == BrowserMode.TOR_ONION) {
                TorProxyState.UnsupportedApi(err.localizedMessage ?: "WebView ProxyController unavailable.")
            } else {
                TorProxyState.Inactive
            }
        }
        return if (mode == BrowserMode.SURFACE) {
            runCatching {
                suspendCancellableCoroutine { cont ->
                    controller.clearProxyOverride(mainExecutor) {
                        if (cont.isActive) cont.resume(TorProxyState.Inactive)
                    }
                }
            }.getOrDefault(TorProxyState.Inactive)
        } else {
            val endpoint = "${protocol.scheme}://$host:$port"
            val config = ProxyConfig.Builder()
                .addProxyRule(endpoint)
                .build()

            runCatching {
                suspendCancellableCoroutine { cont ->
                    controller.setProxyOverride(config, mainExecutor) {
                        if (cont.isActive) {
                            cont.resume(TorProxyState.Checking)
                        }
                    }
                }
            }.onFailure { err ->
                return TorProxyState.UnsupportedApi(
                    err.localizedMessage ?: "Unable to configure WebView Tor proxy.",
                )
            }
            checkProxyReachability(host, port, protocol)
        }
    }

    suspend fun checkProxyReachability(
        host: String,
        port: Int,
        protocol: ProxyProtocol,
    ): TorProxyState = withContext(Dispatchers.IO) {
        val endpoint = "${protocol.scheme}://$host:$port"
        val startNanos = System.nanoTime()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1800)
            }
            val elapsedMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
            TorProxyState.Connected(endpoint = endpoint, latencyMs = elapsedMs)
        } catch (e: Exception) {
            TorProxyState.Unreachable(
                endpoint = endpoint,
                reason = e.localizedMessage ?: "No local Tor/Orbot daemon listening on $host:$port",
            )
        }
    }

    private const val ORBOT_PACKAGE = "org.torproject.android"
    private const val ACTION_START_TOR = "org.torproject.android.intent.action.START"

    fun isOrbotInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getLaunchIntentForPackage(ORBOT_PACKAGE) != null
        }.getOrDefault(false)
    }

    fun requestOrbotStart(context: Context): Boolean {
        if (!isOrbotInstalled(context)) {
            return launchOrbot(context)
        }
        val startIntent = Intent(ACTION_START_TOR).apply {
            setPackage(ORBOT_PACKAGE)
            putExtra("org.torproject.android.intent.extra.PACKAGE_NAME", context.packageName)
        }
        val broadcastSent = runCatching {
            context.sendBroadcast(startIntent)
            true
        }.getOrDefault(false)
        val launched = launchOrbot(context)
        return broadcastSent || launched
    }

    fun launchOrbot(context: Context): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(ORBOT_PACKAGE)
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        } else {
            val marketIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://guardianproject.info/apps/org.torproject.android/"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(marketIntent) }.isSuccess
        }
    }
}

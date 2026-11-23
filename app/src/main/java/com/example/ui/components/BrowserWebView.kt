package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.example.browser.TrackerAndUrlEngine
import com.example.data.ABOUT_START_URL
import java.util.concurrent.Executor

/**
 * A security-hardened Compose WebView wrapper powered by AndroidX WebKit.
 *
 * Security defaults:
 * - JavaScript is disabled by default (`javaScriptEnabled = false`).
 * - HTTPS is enforced for clearnet URLs and mixed content is blocked (`MIXED_CONTENT_NEVER_ALLOW`).
 * - Traffic can be routed through a Tor SOCKS5 proxy (`127.0.0.1:9050`) via `ProxyController`.
 * - File access, content access, geolocation, WebRTC media prompts, and unsafe URI schemes are blocked.
 */
@Composable
fun BrowserWebView(
    url: String,
    modifier: Modifier = Modifier,
    tabKey: String = "default",
    javaScriptEnabled: Boolean = false,
    enforceHttps: Boolean = true,
    useTorSocks5Proxy: Boolean = true,
    torProxyHost: String = "127.0.0.1",
    torProxyPort: Int = 9050,
    torProxyScheme: String = "socks5",
    blockThirdPartyCookies: Boolean = true,
    enableSafeBrowsing: Boolean = true,
    manageProxyOverride: Boolean = true,
    externalWebViewFactory: ((Context) -> WebView)? = null,
    onProxyConfigured: ((Boolean, String) -> Unit)? = null,
    onUrlUpgraded: ((String) -> Unit)? = null,
) {
    var managedWebView by remember(tabKey) { mutableStateOf<WebView?>(null) }
    var forceSoftwareLayer by remember(tabKey) { mutableStateOf(false) }
    var proxyReady by remember(manageProxyOverride, useTorSocks5Proxy, torProxyScheme, torProxyHost, torProxyPort) {
        mutableStateOf(!manageProxyOverride)
    }

    if (manageProxyOverride) {
        LaunchedEffect(useTorSocks5Proxy, torProxyScheme, torProxyHost, torProxyPort) {
            val endpoint = "$torProxyScheme://$torProxyHost:$torProxyPort"
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                val controller = ProxyController.getInstance()
                val directExecutor = Executor { runnable -> runnable.run() }
                if (useTorSocks5Proxy) {
                    val proxyConfig = ProxyConfig.Builder()
                        .addProxyRule(endpoint)
                        .build()
                    controller.setProxyOverride(proxyConfig, directExecutor) {
                        proxyReady = true
                        onProxyConfigured?.invoke(true, "SOCKS5 proxy active: $endpoint")
                    }
                } else {
                    controller.clearProxyOverride(directExecutor) {
                        proxyReady = true
                        onProxyConfigured?.invoke(true, "Direct Surface routing active")
                    }
                }
            } else {
                proxyReady = !useTorSocks5Proxy
                onProxyConfigured?.invoke(
                    false,
                    "WebView PROXY_OVERRIDE is not supported on this device.",
                )
            }
        }
    }

    if (externalWebViewFactory == null) {
        DisposableEffect(tabKey) {
            onDispose {
                runCatching { CookieManager.getInstance().flush() }
                managedWebView?.let { wv ->
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.stopLoading()
                    wv.clearHistory()
                    wv.destroy()
                }
                managedWebView = null
            }
        }
    }

    val targetUrl = remember(url, enforceHttps) {
        val trimmed = url.trim()
        val lower = trimmed.lowercase()
        when {
            trimmed.isEmpty() || trimmed == ABOUT_START_URL -> ABOUT_START_URL
            lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("data:") -> ABOUT_START_URL
            else -> TrackerAndUrlEngine.applyHttpsUpgradeIfNeeded(trimmed, enforceHttps)
        }
    }

    key(tabKey) {
        AndroidView(
            factory = { context ->
                val webView = externalWebViewFactory?.invoke(context)
                    ?: createStandaloneHardenedWebView(
                        context = context,
                        enforceHttps = enforceHttps,
                        forceSoftwareLayer = forceSoftwareLayer,
                        onGpuRendererEvicted = { forceSoftwareLayer = true },
                        onUrlUpgraded = onUrlUpgraded,
                    )
                (webView.parent as? ViewGroup)?.removeView(webView)
                if (externalWebViewFactory == null && forceSoftwareLayer && webView.layerType != View.LAYER_TYPE_SOFTWARE) {
                    webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }
                webView.onResume()
                if (externalWebViewFactory == null) {
                    applySecurityWebSettings(
                        webView = webView,
                        javaScriptEnabled = javaScriptEnabled,
                        enforceHttps = enforceHttps,
                        blockThirdPartyCookies = blockThirdPartyCookies,
                        enableSafeBrowsing = enableSafeBrowsing,
                    )
                }
                managedWebView = webView
                if (proxyReady && targetUrl != ABOUT_START_URL && webView.url.isNullOrBlank()) {
                    webView.loadUrl(targetUrl)
                }
                webView
            },
            update = { webView ->
                if (externalWebViewFactory == null) {
                    applySecurityWebSettings(
                        webView = webView,
                        javaScriptEnabled = javaScriptEnabled,
                        enforceHttps = enforceHttps,
                        blockThirdPartyCookies = blockThirdPartyCookies,
                        enableSafeBrowsing = enableSafeBrowsing,
                    )
                    if (proxyReady && targetUrl != ABOUT_START_URL && webView.url != targetUrl) {
                        webView.loadUrl(targetUrl)
                    }
                }
            },
            modifier = modifier
                .fillMaxSize()
                .testTag("webview_viewport"),
        )
    }
}

private fun createStandaloneHardenedWebView(
    context: Context,
    enforceHttps: Boolean,
    forceSoftwareLayer: Boolean,
    onGpuRendererEvicted: () -> Unit,
    onUrlUpgraded: ((String) -> Unit)?,
): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        if (forceSoftwareLayer) {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                // Prevent WebRTC / camera / microphone hardware fingerprinting or leaks
                request?.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                callback?.invoke(origin, false, false)
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val nextUrl = request?.url?.toString() ?: return false
                val lower = nextUrl.lowercase()
                if (lower.startsWith("javascript:") ||
                    lower.startsWith("file:") ||
                    lower.startsWith("intent:") ||
                    lower.startsWith("data:")
                ) {
                    return true
                }
                if (enforceHttps &&
                    lower.startsWith("http://") &&
                    !TrackerAndUrlEngine.isOnionUrl(nextUrl) &&
                    !TrackerAndUrlEngine.isLocalhostOrLoopback(nextUrl)
                ) {
                    val upgraded = TrackerAndUrlEngine.applyHttpsUpgradeIfNeeded(nextUrl, true)
                    onUrlUpgraded?.invoke(upgraded)
                    view?.loadUrl(upgraded)
                    return true
                }
                return false
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                if (detail?.didCrash() == false) {
                    onGpuRendererEvicted()
                }
                if (view != null) {
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                }
                return true
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun applySecurityWebSettings(
    webView: WebView,
    javaScriptEnabled: Boolean,
    enforceHttps: Boolean,
    blockThirdPartyCookies: Boolean,
    enableSafeBrowsing: Boolean,
) {
    val settings = webView.settings
    settings.javaScriptEnabled = javaScriptEnabled
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    @Suppress("DEPRECATION")
    settings.allowFileAccessFromFileURLs = false
    @Suppress("DEPRECATION")
    settings.allowUniversalAccessFromFileURLs = false
    settings.setGeolocationEnabled(false)
    settings.databaseEnabled = false
    settings.saveFormData = false
    settings.mixedContentMode = if (enforceHttps) {
        WebSettings.MIXED_CONTENT_NEVER_ALLOW
    } else {
        WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }

    if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
        WebSettingsCompat.setSafeBrowsingEnabled(settings, enableSafeBrowsing)
    }

    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptThirdPartyCookies(webView, !blockThirdPartyCookies)
}

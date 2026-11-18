package com.example.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.example.data.BrowserMode
import com.example.data.PendingDownloadRequest
import com.example.data.SecurityConfig
import java.util.concurrent.ConcurrentHashMap

interface WebViewEventCallbacks {
    fun onPageStarted(tabId: String, url: String)
    fun onPageFinished(tabId: String, url: String, title: String?, canGoBack: Boolean, canGoForward: Boolean)
    fun onProgressChanged(tabId: String, progress: Int)
    fun onTitleReceived(tabId: String, title: String)
    fun onTrackerBlocked(tabId: String)
    fun onScriptBlocked(tabId: String)
    fun onPageError(tabId: String, url: String, errorDescription: String)
    fun onDownloadIntercepted(tabId: String, request: PendingDownloadRequest)
    fun onRendererTerminated(tabId: String)
    fun onOpenNewTab(url: String)
    fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?)
    fun onHideCustomView()
    fun onLinkLongPressed(tabId: String, url: String, title: String?)
}

internal class BackgroundCapableWebView(context: Context) : WebView(context) {
    @Volatile
    var keepMediaAliveInBackground: Boolean = true

    internal fun resolveEffectiveWindowVisibility(requestedVisibility: Int): Int =
        if (keepMediaAliveInBackground && requestedVisibility != View.VISIBLE) {
            View.VISIBLE
        } else {
            requestedVisibility
        }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(resolveEffectiveWindowVisibility(visibility))
    }
}

class WebViewPool(
    context: Context,
    private val callbacks: WebViewEventCallbacks,
) {
    private val appContext = context.applicationContext
    private val pool = mutableMapOf<String, WebView>()
    private val contextWrappers = mutableMapOf<String, MutableContextWrapper>()
    private val tabCurrentUrls = ConcurrentHashMap<String, String>()
    private val playingMediaTabs = ConcurrentHashMap<String, String>()
    private val softwareLayerTabs = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentConfig: SecurityConfig = SecurityConfig()

    fun updateSecurityConfig(config: SecurityConfig) {
        currentConfig = config
        pool.forEach { (tabId, webView) ->
            val seed = (webView.tag as? String) ?: "DEFAULT"
            applySettingsToWebView(tabId, webView, config, seed)
            injectHardeningScript(tabId, webView, tabCurrentUrls[tabId].orEmpty(), seed)
        }
    }

    fun getOrCreateWebView(
        tabId: String,
        seed: String,
        config: SecurityConfig,
        hostContext: Context? = null,
    ): WebView {
        currentConfig = config
        if (hostContext != null) {
            contextWrappers[tabId]?.baseContext = hostContext
        }
        return pool.getOrPut(tabId) {
            val wrapper = MutableContextWrapper(hostContext ?: appContext)
            contextWrappers[tabId] = wrapper
            createHardenedWebView(tabId, seed, config, wrapper)
        }.also { webView ->
            if (softwareLayerTabs.contains(tabId) && webView.layerType != View.LAYER_TYPE_SOFTWARE) {
                webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
            applySettingsToWebView(tabId, webView, config, seed)
        }
    }

    fun loadUrlForTab(tabId: String, seed: String, config: SecurityConfig, url: String) {
        tabCurrentUrls[tabId] = url
        val webView = getOrCreateWebView(tabId, seed, config)
        webView.loadUrl(url)
    }

    fun getWebView(tabId: String): WebView? = pool[tabId]

    fun destroyTabWebView(tabId: String) {
        tabCurrentUrls.remove(tabId)
        playingMediaTabs.remove(tabId)
        syncGlobalMediaRuntime()
        softwareLayerTabs.remove(tabId)
        contextWrappers.remove(tabId)?.baseContext = appContext
        pool.remove(tabId)?.let { wv ->
            runCatching {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.clearHistory()
                wv.destroy()
            }
        }
    }

    fun onAppBackgrounded(keepMediaAlive: Boolean) {
        if (!keepMediaAlive) return
        mainHandler.post {
            pool.values.forEach { wv ->
                runCatching {
                    wv.onResume()
                    wv.resumeTimers()
                    wv.evaluateJavascript("window.__osBgMediaResumeIfNeeded && window.__osBgMediaResumeIfNeeded();", null)
                }
            }
        }
        listOf(150L, 450L, 900L).forEach { delayMs ->
            mainHandler.postDelayed({
                pool.values.forEach { wv ->
                    runCatching {
                        wv.onResume()
                        wv.resumeTimers()
                        wv.evaluateJavascript("window.__osBgMediaResumeIfNeeded && window.__osBgMediaResumeIfNeeded();", null)
                    }
                }
            }, delayMs)
        }
    }

    fun onAppForegrounded() {
        mainHandler.post {
            pool.values.forEach { wv ->
                runCatching {
                    wv.onResume()
                    wv.resumeTimers()
                }
            }
        }
    }

    fun pauseAllMedia() {
        playingMediaTabs.clear()
        LiveSessionRuntime.updateMediaSnapshot(
            isPlaying = false,
            title = LiveSessionRuntime.activeMediaTitle.value,
        )
        mainHandler.post {
            pool.values.forEach { wv ->
                runCatching {
                    wv.evaluateJavascript("window.__osBgMediaPauseAll && window.__osBgMediaPauseAll();", null)
                }
            }
        }
    }

    fun resumeActiveMedia() {
        mainHandler.post {
            pool.values.forEach { wv ->
                runCatching {
                    wv.onResume()
                    wv.resumeTimers()
                    wv.evaluateJavascript("window.__osBgMediaForcePlay && window.__osBgMediaForcePlay();", null)
                }
            }
        }
    }

    fun seekActiveMedia(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        mainHandler.post {
            pool.values.forEach { wv ->
                runCatching {
                    wv.evaluateJavascript("window.__osBgMediaSeekTo && window.__osBgMediaSeekTo($clamped);", null)
                }
            }
        }
    }

    fun skipNextMedia() {
        mainHandler.post {
            pool.values.forEach { wv ->
                runCatching {
                    wv.evaluateJavascript("window.__osBgMediaNext && window.__osBgMediaNext();", null)
                }
            }
        }
    }

    fun skipPrevMedia() {
        mainHandler.post {
            pool.values.forEach { wv ->
                runCatching {
                    wv.evaluateJavascript("window.__osBgMediaPrev && window.__osBgMediaPrev();", null)
                }
            }
        }
    }

    fun extractPageTextSnippet(tabId: String, onResult: (String) -> Unit) {
        val wv = pool[tabId]
        if (wv == null) {
            onResult("")
            return
        }
        mainHandler.post {
            runCatching {
                wv.evaluateJavascript(
                    "(function(){ try { return (document.body && document.body.innerText ? document.body.innerText : '').replace(/\\s+/g,' ').trim().slice(0, 4000); } catch(e) { return ''; } })();",
                ) { raw ->
                    val decoded = raw
                        ?.removeSurrounding("\"")
                        ?.replace("\\n", " ")
                        ?.replace("\\\"", "\"")
                        ?.replace("\\\\", "\\")
                        .orEmpty()
                    onResult(decoded)
                }
            }.onFailure {
                onResult("")
            }
        }
    }

    private fun syncGlobalMediaRuntime() {
        val activeEntry = playingMediaTabs.values.firstOrNull()
        if (activeEntry != null) {
            LiveSessionRuntime.setMediaPlaybackState(true, activeEntry)
        } else {
            LiveSessionRuntime.setMediaPlaybackState(false)
        }
    }

    private fun injectHardeningScript(
        tabId: String,
        webView: WebView?,
        url: String,
        seed: String,
    ) {
        if (webView == null || isJsDisabledForTab(tabId, currentConfig, url)) return
        val siteAllowed = TrackerAndUrlEngine.isSiteWhitelisted(url, currentConfig.adBlockWhitelistHosts)
        val script = PrivacyScripts.buildHardeningScript(
            seed = (webView.tag as? String) ?: seed,
            enableWebRtcGuard = currentConfig.webRtcLeakProtectionEnabled,
            enableFingerprintRandomization = currentConfig.fingerprintRandomizationEnabled,
            mode = currentConfig.browserMode,
            enableCosmeticAdBlock = currentConfig.trackerBlockerEnabled && currentConfig.cosmeticAdBlockEnabled && !siteAllowed,
            enablePopupGuard = currentConfig.popupBlockerEnabled && !siteAllowed,
            enableBackgroundMediaPlayback = currentConfig.backgroundMediaPlaybackEnabled,
        )
        if (script.isNotEmpty()) {
            webView.evaluateJavascript(script, null)
        }
    }

    fun shredAllSessions() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
        pool.values.forEach { wv ->
            runCatching {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.clearCache(true)
                wv.clearFormData()
                wv.clearHistory()
                wv.destroy()
            }
        }
        pool.clear()
        playingMediaTabs.clear()
        syncGlobalMediaRuntime()
        contextWrappers.values.forEach { it.baseContext = appContext }
        contextWrappers.clear()
        tabCurrentUrls.clear()
        softwareLayerTabs.clear()
    }

    private fun isJsDisabledForTab(tabId: String, config: SecurityConfig, urlOverride: String? = null): Boolean {
        if (!config.noScriptEnabled) return false
        val host = TrackerAndUrlEngine.normalizeHost(urlOverride ?: tabCurrentUrls[tabId])
        if (host != null && config.trustedJsHosts.contains(host)) {
            return false
        }
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createHardenedWebView(
        tabId: String,
        seed: String,
        config: SecurityConfig,
        wrapperContext: MutableContextWrapper,
    ): WebView {
        val webView = BackgroundCapableWebView(wrapperContext).apply {
            tag = seed
            keepMediaAliveInBackground = config.backgroundMediaPlaybackEnabled
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            if (softwareLayerTabs.contains(tabId)) {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onMediaMetadataUpdated(
                        title: String?,
                        artist: String?,
                        durationMs: Double,
                        positionMs: Double,
                        isPlaying: Boolean,
                    ) {
                        val cleanTitle = title?.trim().orEmpty().ifBlank { "Media Audio" }
                        val cleanArtist = artist?.trim().orEmpty().ifBlank { "OnionShield Media" }
                        val dur = if (durationMs.isFinite() && durationMs > 0.0) durationMs.toLong() else 0L
                        val pos = if (positionMs.isFinite() && positionMs >= 0.0) positionMs.toLong() else 0L
                        val url = tabCurrentUrls[tabId].orEmpty()

                        if (isPlaying) {
                            playingMediaTabs[tabId] = cleanTitle
                        } else {
                            playingMediaTabs.remove(tabId)
                        }
                        val anyPlaying = playingMediaTabs.isNotEmpty() || isPlaying
                        LiveSessionRuntime.updateMediaSnapshot(
                            isPlaying = anyPlaying,
                            title = playingMediaTabs.values.firstOrNull() ?: cleanTitle,
                            artist = cleanArtist,
                            positionMs = pos,
                            durationMs = dur,
                            url = url,
                        )
                        if (anyPlaying &&
                            currentConfig.backgroundMediaPlaybackEnabled &&
                            !LiveSessionRuntime.isForegroundServiceRunning.value
                        ) {
                            mainHandler.post {
                                runCatching { LiveMonitorForegroundService.start(appContext) }
                            }
                        }
                    }

                    @JavascriptInterface
                    fun onMediaPlaying(title: String?) {
                        onMediaMetadataUpdated(title, "OnionShield Media", 0.0, 0.0, true)
                    }

                    @JavascriptInterface
                    fun onMediaStopped() {
                        playingMediaTabs.remove(tabId)
                        val remainingTitle = playingMediaTabs.values.firstOrNull()
                        if (remainingTitle != null) {
                            LiveSessionRuntime.updateMediaSnapshot(
                                isPlaying = true,
                                title = remainingTitle,
                            )
                        } else {
                            LiveSessionRuntime.updateMediaSnapshot(
                                isPlaying = false,
                                title = LiveSessionRuntime.activeMediaTitle.value,
                            )
                        }
                    }
                },
                "OnionShieldMediaBridge",
            )
        }

        applySettingsToWebView(tabId, webView, config, seed)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                callbacks.onProgressChanged(tabId, newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    callbacks.onTitleReceived(tabId, title)
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                val pageUrl = tabCurrentUrls[tabId]
                val adBlockActiveForSite = currentConfig.trackerBlockerEnabled &&
                    !TrackerAndUrlEngine.isSiteWhitelisted(pageUrl, currentConfig.adBlockWhitelistHosts)
                val popupShieldActive = currentConfig.popupBlockerEnabled &&
                    !TrackerAndUrlEngine.isSiteWhitelisted(pageUrl, currentConfig.adBlockWhitelistHosts)

                val hitResult = view?.hitTestResult
                val hitUrl = hitResult?.extra
                if (!hitUrl.isNullOrBlank() &&
                    (hitUrl.startsWith("http://", ignoreCase = true) ||
                        hitUrl.startsWith("https://", ignoreCase = true))
                ) {
                    val isAdPopup = (popupShieldActive && (!isUserGesture || TrackerAndUrlEngine.isPopupAdUrl(hitUrl, currentConfig.customBlockedDomains))) ||
                        (adBlockActiveForSite && TrackerAndUrlEngine.isAdOrTrackerRequest(hitUrl, pageUrl, currentConfig.customBlockedDomains, currentConfig.adBlockWhitelistHosts))
                    if (isAdPopup) {
                        callbacks.onTrackerBlocked(tabId)
                        return false
                    }
                    callbacks.onOpenNewTab(hitUrl)
                    return false
                }

                if (popupShieldActive && !isUserGesture) {
                    callbacks.onTrackerBlocked(tabId)
                    return false
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val tempWebView = WebView(wrapperContext).apply {
                    var handled = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            childView: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val popupUrl = request?.url?.toString()
                            if (!handled && !popupUrl.isNullOrBlank() &&
                                (popupUrl.startsWith("http://", ignoreCase = true) ||
                                    popupUrl.startsWith("https://", ignoreCase = true))
                            ) {
                                handled = true
                                val isAdTarget = (popupShieldActive && TrackerAndUrlEngine.isPopupAdUrl(popupUrl, currentConfig.customBlockedDomains)) ||
                                    (adBlockActiveForSite && TrackerAndUrlEngine.isAdOrTrackerRequest(popupUrl, pageUrl, currentConfig.customBlockedDomains, currentConfig.adBlockWhitelistHosts))
                                if (isAdTarget) {
                                    callbacks.onTrackerBlocked(tabId)
                                } else {
                                    callbacks.onOpenNewTab(popupUrl)
                                }
                            }
                            runCatching { childView?.destroy() }
                            return true
                        }

                        override fun onPageStarted(
                            childView: WebView?,
                            url: String?,
                            favicon: Bitmap?,
                        ) {
                            if (!handled && !url.isNullOrBlank() &&
                                (url.startsWith("http://", ignoreCase = true) ||
                                    url.startsWith("https://", ignoreCase = true))
                            ) {
                                handled = true
                                val isAdTarget = (popupShieldActive && TrackerAndUrlEngine.isPopupAdUrl(url, currentConfig.customBlockedDomains)) ||
                                    (adBlockActiveForSite && TrackerAndUrlEngine.isAdOrTrackerRequest(url, pageUrl, currentConfig.customBlockedDomains, currentConfig.adBlockWhitelistHosts))
                                if (isAdTarget) {
                                    callbacks.onTrackerBlocked(tabId)
                                } else {
                                    callbacks.onOpenNewTab(url)
                                }
                            }
                            runCatching {
                                childView?.stopLoading()
                                childView?.destroy()
                            }
                        }
                    }
                    postDelayed({
                        if (!handled) {
                            runCatching { destroy() }
                        }
                    }, 4000L)
                }
                transport.webView = tempWebView
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                callbacks.onShowCustomView(view, callback)
            }

            override fun onHideCustomView() {
                callbacks.onHideCustomView()
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val resources = request.resources ?: emptyArray()
                val protectedMediaOnly = resources.isNotEmpty() &&
                    resources.all { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID } &&
                    currentConfig.browserMode == BrowserMode.SURFACE
                if (protectedMediaOnly) {
                    request.grant(resources)
                } else if (currentConfig.webRtcLeakProtectionEnabled) {
                    request.deny()
                } else {
                    super.onPermissionRequest(request)
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                callback?.invoke(origin, false, false)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val targetUrl = request?.url?.toString() ?: return false
                val lower = targetUrl.lowercase()

                if (lower.startsWith("intent:")) {
                    val fallbackUrl = TrackerAndUrlEngine.extractIntentFallbackUrl(targetUrl)
                    if (fallbackUrl != null) {
                        val upgraded = TrackerAndUrlEngine.applyHttpsUpgradeIfNeeded(
                            fallbackUrl,
                            currentConfig.httpsEverywhereEnabled,
                        )
                        tabCurrentUrls[tabId] = upgraded
                        if (view != null) {
                            syncHostJsSetting(tabId, view, currentConfig, upgraded)
                            view.loadUrl(upgraded)
                        }
                    }
                    return true
                }

                if (lower.startsWith("mailto:") || lower.startsWith("tel:")) {
                    if (currentConfig.browserMode == BrowserMode.SURFACE) {
                        runCatching {
                            val extIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            appContext.startActivity(extIntent)
                        }
                    }
                    return true
                }

                if (lower.startsWith("market:") || lower.startsWith("javascript:") || lower.startsWith("file:")) {
                    return true
                }

                if (lower.startsWith("http://") &&
                    currentConfig.httpsEverywhereEnabled &&
                    !TrackerAndUrlEngine.isOnionUrl(targetUrl) &&
                    !TrackerAndUrlEngine.isLocalhostOrLoopback(targetUrl)
                ) {
                    val upgraded = TrackerAndUrlEngine.applyHttpsUpgradeIfNeeded(targetUrl, true)
                    tabCurrentUrls[tabId] = upgraded
                    if (view != null) {
                        syncHostJsSetting(tabId, view, currentConfig, upgraded)
                        view.loadUrl(upgraded)
                    }
                    return true
                }

                if (!request?.isForMainFrame.let { it == true } &&
                    currentConfig.popupBlockerEnabled &&
                    !TrackerAndUrlEngine.isSiteWhitelisted(tabCurrentUrls[tabId], currentConfig.adBlockWhitelistHosts) &&
                    TrackerAndUrlEngine.isPopupAdUrl(targetUrl, currentConfig.customBlockedDomains)
                ) {
                    callbacks.onTrackerBlocked(tabId)
                    return true
                }

                if (lower.startsWith("http://") || lower.startsWith("https://")) {
                    if (request?.isForMainFrame == true &&
                        currentConfig.popupBlockerEnabled &&
                        !TrackerAndUrlEngine.isSiteWhitelisted(tabCurrentUrls[tabId], currentConfig.adBlockWhitelistHosts) &&
                        tabCurrentUrls[tabId] != targetUrl &&
                        TrackerAndUrlEngine.isPopupAdUrl(targetUrl, currentConfig.customBlockedDomains)
                    ) {
                        callbacks.onTrackerBlocked(tabId)
                        return true
                    }
                    tabCurrentUrls[tabId] = targetUrl
                    if (view != null) {
                        syncHostJsSetting(tabId, view, currentConfig, targetUrl)
                    }
                    return false
                }

                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                val currentPage = tabCurrentUrls[tabId]
                if (request.isForMainFrame) {
                    tabCurrentUrls[tabId] = reqUrl
                }

                val isMainCustomBlocked = request.isForMainFrame &&
                    currentConfig.customBlockedDomains.isNotEmpty() &&
                    TrackerAndUrlEngine.isAdOrTrackerRequest(
                        url = reqUrl,
                        pageUrl = null,
                        customBlockedDomains = currentConfig.customBlockedDomains,
                        whitelistHosts = emptySet(),
                    )
                if (isMainCustomBlocked ||
                    (!request.isForMainFrame &&
                        currentConfig.trackerBlockerEnabled &&
                        TrackerAndUrlEngine.isAdOrTrackerRequest(
                            url = reqUrl,
                            pageUrl = currentPage,
                            customBlockedDomains = currentConfig.customBlockedDomains,
                            whitelistHosts = currentConfig.adBlockWhitelistHosts,
                        ))
                ) {
                    callbacks.onTrackerBlocked(tabId)
                    return TrackerAndUrlEngine.emptyBlockedResponseForUrl(reqUrl)
                }

                if (isJsDisabledForTab(tabId, currentConfig)) {
                    val path = request.url?.path?.lowercase().orEmpty()
                    if (path.endsWith(".js") || path.endsWith(".mjs")) {
                        callbacks.onScriptBlocked(tabId)
                        return TrackerAndUrlEngine.emptyBlockedResponseForUrl(reqUrl)
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                val safeUrl = url.orEmpty()
                if (safeUrl.isNotBlank()) {
                    tabCurrentUrls[tabId] = safeUrl
                    injectHardeningScript(tabId, view, safeUrl, seed)
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val safeUrl = url.orEmpty()
                if (safeUrl.isNotBlank()) {
                    tabCurrentUrls[tabId] = safeUrl
                    if (view != null) {
                        syncHostJsSetting(tabId, view, currentConfig, safeUrl)
                    }
                }
                callbacks.onPageStarted(tabId, safeUrl)
                injectHardeningScript(tabId, view, safeUrl, seed)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val safeUrl = url.orEmpty()
                if (safeUrl.isNotBlank()) {
                    tabCurrentUrls[tabId] = safeUrl
                }
                injectHardeningScript(tabId, view, safeUrl, seed)
                callbacks.onPageFinished(
                    tabId = tabId,
                    url = safeUrl,
                    title = view?.title,
                    canGoBack = view?.canGoBack() == true,
                    canGoForward = view?.canGoForward() == true,
                )
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    val urlStr = request.url?.toString().orEmpty()
                    val desc = error?.description?.toString() ?: "Connection failed"
                    callbacks.onPageError(tabId, urlStr, desc)
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                // System/GPU renderer eviction or crash (e.g. missing rendernode): fall back to software layer on retry
                softwareLayerTabs.add(tabId)
                pool.remove(tabId)
                tabCurrentUrls.remove(tabId)
                if (view != null) {
                    runCatching {
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.destroy()
                    }
                }
                callbacks.onRendererTerminated(tabId)
                return true
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            callbacks.onDownloadIntercepted(
                tabId,
                PendingDownloadRequest(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType ?: "application/octet-stream",
                    contentLength = contentLength,
                    fileName = guessedName,
                    isOnionSource = TrackerAndUrlEngine.isOnionUrl(url) ||
                        currentConfig.browserMode == BrowserMode.TOR_ONION,
                ),
            )
        }

        webView.setOnLongClickListener { view ->
            val wv = view as? WebView ?: return@setOnLongClickListener false
            val hit = wv.hitTestResult
            if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            ) {
                val fallbackUrl = hit.extra
                val handler = Handler(Looper.getMainLooper()) { msg ->
                    val hrefUrl = msg.data.getString("url").orEmpty().ifBlank { fallbackUrl.orEmpty() }
                    val hrefTitle = msg.data.getString("title")
                    if (hrefUrl.startsWith("http://", ignoreCase = true) ||
                        hrefUrl.startsWith("https://", ignoreCase = true)
                    ) {
                        callbacks.onLinkLongPressed(tabId, hrefUrl, hrefTitle)
                    }
                    true
                }
                wv.requestFocusNodeHref(handler.obtainMessage())
                true
            } else {
                false
            }
        }

        return webView
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun syncHostJsSetting(
        tabId: String,
        webView: WebView,
        config: SecurityConfig,
        urlOverride: String? = null,
    ) {
        val jsEnabled = !isJsDisabledForTab(tabId, config, urlOverride)
        val settings = webView.settings
        if (settings.javaScriptEnabled != jsEnabled) {
            settings.javaScriptEnabled = jsEnabled
        }
        if (settings.javaScriptCanOpenWindowsAutomatically != jsEnabled) {
            settings.javaScriptCanOpenWindowsAutomatically = jsEnabled
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun applySettingsToWebView(
        tabId: String,
        webView: WebView,
        config: SecurityConfig,
        seed: String,
        urlOverride: String? = null,
    ) {
        (webView as? BackgroundCapableWebView)?.keepMediaAliveInBackground =
            config.backgroundMediaPlaybackEnabled
        val jsDisabled = isJsDisabledForTab(tabId, config, urlOverride)
        val settings = webView.settings
        if (settings.javaScriptEnabled != !jsDisabled) {
            settings.javaScriptEnabled = !jsDisabled
        }
        settings.domStorageEnabled = config.browserMode == BrowserMode.SURFACE
        settings.databaseEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.setGeolocationEnabled(false)
        settings.saveFormData = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = !jsDisabled
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mixedContentMode = if (config.httpsEverywhereEnabled) {
            WebSettings.MIXED_CONTENT_NEVER_ALLOW
        } else {
            WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        val targetUa = if (config.fingerprintRandomizationEnabled) {
            PrivacyScripts.profileForSeed(seed, config.browserMode).userAgent
        } else {
            null
        }
        if (settings.userAgentString != targetUa) {
            settings.userAgentString = targetUa
        }

        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
            }
        }

        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, config.forceDarkWebTheme)
            }
        }

        runCatching {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(config.browserMode == BrowserMode.SURFACE)
            cookieManager.setAcceptThirdPartyCookies(webView, !config.blockThirdPartyCookies)
        }
    }
}

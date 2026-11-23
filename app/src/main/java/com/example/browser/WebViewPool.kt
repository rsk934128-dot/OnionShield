package com.example.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
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
import org.json.JSONObject
import java.util.UUID
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
    fun onAuthenticatedSiteDetected(host: String) {}
    fun onOpenPopupWebView(popupWebView: WebView) {}
    fun onClosePopupWebView(popupWebView: WebView) {}
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

    override fun dispatchWindowVisibilityChanged(visibility: Int) {
        super.dispatchWindowVisibilityChanged(resolveEffectiveWindowVisibility(visibility))
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
    private val injectedScriptKeysForTab = ConcurrentHashMap<String, String>()
    private val playingMediaTabs = ConcurrentHashMap<String, String>()
    private val pendingBlobDownloads = ConcurrentHashMap<String, (SavedDownloadResult) -> Unit>()
    private val softwareLayerTabs = mutableSetOf<String>()
    private val authenticatedHosts = ConcurrentHashMap.newKeySet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentConfig: SecurityConfig = SecurityConfig()

    fun updateAuthenticatedHosts(hosts: Set<String>) {
        authenticatedHosts.clear()
        hosts.map { it.trim().lowercase().removePrefix("www.") }
            .filter { it.isNotBlank() }
            .forEach { authenticatedHosts.add(it) }
    }

    fun flushCookies() {
        runCatching { CookieManager.getInstance().flush() }
    }

    fun updateSecurityConfig(config: SecurityConfig) {
        currentConfig = config
        injectedScriptKeysForTab.clear()
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
        injectedScriptKeysForTab.remove(tabId)
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
        if (currentConfig.browserMode == BrowserMode.SURFACE) {
            flushCookies()
        }
        mainHandler.post {
            pool.values.forEach { wv ->
                runCatching {
                    wv.evaluateJavascript("window.__osSetAppBackground && window.__osSetAppBackground(true);", null)
                    if (keepMediaAlive) {
                        wv.onResume()
                        wv.resumeTimers()
                        wv.evaluateJavascript("window.__osBgMediaResumeIfNeeded && window.__osBgMediaResumeIfNeeded();", null)
                    }
                }
            }
        }
        if (!keepMediaAlive) return
        listOf(150L, 450L, 1000L, 2200L).forEach { delayMs ->
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
                    wv.evaluateJavascript("window.__osSetAppBackground && window.__osSetAppBackground(false);", null)
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

    fun captureScreenAndTradeControls(
        tabId: String,
        maxDimensionPx: Int = 1024,
        onResult: (Bitmap?, List<DetectedTradeButton>) -> Unit,
    ) {
        val wv = pool[tabId]
        if (wv == null) {
            onResult(null, emptyList())
            return
        }
        mainHandler.post {
            val viewW = wv.width
            val viewH = wv.height
            if (viewW <= 0 || viewH <= 0) {
                onResult(null, emptyList())
                return@post
            }
            val scale = (maxDimensionPx.toFloat() / maxOf(viewW, viewH).toFloat()).coerceAtMost(1f)
            val targetW = (viewW * scale).toInt().coerceAtLeast(1)
            val targetH = (viewH * scale).toInt().coerceAtLeast(1)

            fun finishWithFallbackSnapshot(detectedButtons: List<DetectedTradeButton>) {
                runCatching {
                    wv.evaluateJavascript(TradePilotHelper.CANVAS_CHART_CAPTURE_SCRIPT) { rawCharts ->
                        val charts = TradePilotHelper.parseCanvasSnapshotsJson(rawCharts)
                        onResult(
                            renderWebViewCanvasSnapshot(wv, targetW, targetH, scale, charts),
                            detectedButtons,
                        )
                    }
                }.onFailure {
                    onResult(
                        renderWebViewCanvasSnapshot(wv, targetW, targetH, scale, emptyList()),
                        detectedButtons,
                    )
                }
            }

            fun attemptPixelCopy(
                detectedButtons: List<DetectedTradeButton>,
                allowRetry: Boolean,
            ) {
                val window = findHostWindow(wv.context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && window != null && wv.isAttachedToWindow) {
                    val decorW = window.decorView.width
                    val decorH = window.decorView.height
                    val location = IntArray(2)
                    wv.getLocationInWindow(location)
                    val left = location[0].coerceAtLeast(0)
                    val top = location[1].coerceAtLeast(0)
                    val right = (location[0] + wv.width).coerceAtMost(if (decorW > 0) decorW else location[0] + wv.width)
                    val bottom = (location[1] + wv.height).coerceAtMost(if (decorH > 0) decorH else location[1] + wv.height)
                    val clampedW = (right - left)
                    val clampedH = (bottom - top)
                    if (clampedW <= 16 || clampedH <= 16) {
                        finishWithFallbackSnapshot(detectedButtons)
                        return
                    }
                    val srcRect = Rect(left, top, right, bottom)
                    val copyScale = (maxDimensionPx.toFloat() / maxOf(clampedW, clampedH).toFloat()).coerceAtMost(1f)
                    val copyW = (clampedW * copyScale).toInt().coerceAtLeast(1)
                    val copyH = (clampedH * copyScale).toInt().coerceAtLeast(1)
                    val destBitmap = Bitmap.createBitmap(copyW, copyH, Bitmap.Config.ARGB_8888)
                    val pixelCopyAttempted = runCatching {
                        PixelCopy.request(
                            window,
                            srcRect,
                            destBitmap,
                            { copyResult ->
                                if (copyResult == PixelCopy.SUCCESS) {
                                    val telemetry = TradePilotHelper.analyzeBitmapChart(destBitmap)
                                    if (!telemetry.hasChartDetail && allowRetry) {
                                        runCatching { destBitmap.recycle() }
                                        mainHandler.postDelayed(
                                            { attemptPixelCopy(detectedButtons, allowRetry = false) },
                                            140L,
                                        )
                                    } else {
                                        onResult(destBitmap, detectedButtons)
                                    }
                                } else {
                                    runCatching { destBitmap.recycle() }
                                    if (allowRetry) {
                                        mainHandler.postDelayed(
                                            { attemptPixelCopy(detectedButtons, allowRetry = false) },
                                            120L,
                                        )
                                    } else {
                                        finishWithFallbackSnapshot(detectedButtons)
                                    }
                                }
                            },
                            mainHandler,
                        )
                    }.isSuccess
                    if (!pixelCopyAttempted) {
                        runCatching { destBitmap.recycle() }
                        finishWithFallbackSnapshot(detectedButtons)
                    }
                } else {
                    finishWithFallbackSnapshot(detectedButtons)
                }
            }

            runCatching {
                wv.evaluateJavascript(TradePilotHelper.TRADE_DOM_SCAN_SCRIPT) { rawJson ->
                    val buttons = TradePilotHelper.parseDetectedButtonsJson(rawJson)
                    attemptPixelCopy(buttons, allowRetry = true)
                }
            }.onFailure {
                attemptPixelCopy(emptyList(), allowRetry = true)
            }
        }
    }

    fun dispatchWebViewClick(
        tabId: String,
        normX: Float,
        normY: Float,
        onDispatched: (Boolean) -> Unit,
    ) {
        val wv = pool[tabId]
        if (wv == null) {
            onDispatched(false)
            return
        }
        mainHandler.post {
            val viewW = wv.width
            val viewH = wv.height
            if (viewW <= 0 || viewH <= 0 || !wv.isAttachedToWindow) {
                onDispatched(false)
                return@post
            }
            val px = normX.coerceIn(0.02f, 0.98f) * viewW.toFloat()
            val py = normY.coerceIn(0.05f, 0.98f) * viewH.toFloat()
            val downTime = SystemClock.uptimeMillis()
            val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, px, py, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            wv.dispatchTouchEvent(downEvent)
            downEvent.recycle()

            mainHandler.postDelayed({
                val upTime = SystemClock.uptimeMillis()
                val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, px, py, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                val handled = runCatching { wv.dispatchTouchEvent(upEvent) }.getOrDefault(false)
                upEvent.recycle()
                onDispatched(handled || wv.isAttachedToWindow)
            }, 50L)
        }
    }

    private fun renderWebViewCanvasSnapshot(
        wv: WebView,
        targetW: Int,
        targetH: Int,
        scale: Float,
        canvasCharts: List<CanvasChartSnapshot> = emptyList(),
    ): Bitmap? = runCatching {
        val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
        wv.draw(canvas)
        canvas.restore()

        // Composite any extracted HTML5 <canvas> snapshots on top in case software draw omitted GPU canvas
        canvasCharts.forEach { chart ->
            val dataUrl = chart.dataUrl ?: return@forEach
            val base64Part = dataUrl.substringAfter("base64,", "")
            if (base64Part.isBlank()) return@forEach
            runCatching {
                val bytes = Base64.decode(base64Part, Base64.DEFAULT)
                val chartBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (chartBmp != null) {
                    val dst = RectF(
                        chart.leftRatio * targetW,
                        chart.topRatio * targetH,
                        chart.rightRatio * targetW,
                        chart.bottomRatio * targetH,
                    )
                    canvas.drawBitmap(chartBmp, null, dst, null)
                    chartBmp.recycle()
                }
            }
        }
        bitmap
    }.getOrNull()

    private fun findHostWindow(context: Context?): Window? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) {
                return current.window
            }
            current = current.baseContext
        }
        return null
    }

    private fun syncGlobalMediaRuntime() {
        val activeEntry = playingMediaTabs.values.firstOrNull()
        if (activeEntry != null) {
            LiveSessionRuntime.setMediaPlaybackState(true, activeEntry)
        } else {
            LiveSessionRuntime.setMediaPlaybackState(false)
        }
    }

    private fun recordAuthenticatedHostFromUrl(url: String?) {
        if (currentConfig.browserMode != BrowserMode.SURFACE) return
        val host = TrackerAndUrlEngine.normalizeHost(url)?.removePrefix("www.") ?: return
        if (host.isBlank() || host == "start" || host.endsWith(".onion")) return
        if (authenticatedHosts.add(host)) {
            callbacks.onAuthenticatedSiteDetected(host)
        }
    }

    private fun buildHardeningScriptKey(
        tabId: String,
        url: String,
        seed: String,
    ): String {
        val host = TrackerAndUrlEngine.normalizeHost(url).orEmpty()
        val siteAllowed = TrackerAndUrlEngine.isSiteWhitelisted(url, currentConfig.adBlockWhitelistHosts)
        val isAuthFlow = currentConfig.browserMode == BrowserMode.SURFACE && isAuthFlowUrl(url)
        val jsDisabled = isJsDisabledForTab(tabId, currentConfig, url)
        return listOf(
            host,
            seed,
            currentConfig.browserMode.name,
            jsDisabled,
            currentConfig.webRtcLeakProtectionEnabled,
            currentConfig.fingerprintRandomizationEnabled && !isAuthFlow,
            currentConfig.trackerBlockerEnabled && currentConfig.cosmeticAdBlockEnabled && !siteAllowed && !isAuthFlow,
            currentConfig.popupBlockerEnabled && !siteAllowed && !isAuthFlow,
            currentConfig.backgroundMediaPlaybackEnabled,
        ).joinToString("|")
    }

    private fun injectHardeningScript(
        tabId: String,
        webView: WebView?,
        url: String,
        seed: String,
        skipIfSameDocumentConfig: Boolean = false,
    ) {
        if (webView == null || isJsDisabledForTab(tabId, currentConfig, url)) return
        val resolvedSeed = (webView.tag as? String) ?: seed
        val scriptKey = buildHardeningScriptKey(tabId, url, resolvedSeed)
        if (skipIfSameDocumentConfig && injectedScriptKeysForTab[tabId] == scriptKey) {
            return
        }
        val siteAllowed = TrackerAndUrlEngine.isSiteWhitelisted(url, currentConfig.adBlockWhitelistHosts)
        val isAuthFlow = currentConfig.browserMode == BrowserMode.SURFACE && isAuthFlowUrl(url)
        val hardeningScript = PrivacyScripts.buildHardeningScript(
            seed = resolvedSeed,
            enableWebRtcGuard = currentConfig.webRtcLeakProtectionEnabled,
            enableFingerprintRandomization = currentConfig.fingerprintRandomizationEnabled && !isAuthFlow,
            mode = currentConfig.browserMode,
            enableCosmeticAdBlock = currentConfig.trackerBlockerEnabled && currentConfig.cosmeticAdBlockEnabled && !siteAllowed && !isAuthFlow,
            enablePopupGuard = currentConfig.popupBlockerEnabled && !siteAllowed && !isAuthFlow,
            enableBackgroundMediaPlayback = currentConfig.backgroundMediaPlaybackEnabled,
        )
        val blobHelperScript = PrivacyScripts.buildBlobDownloadHelperScript()
        injectedScriptKeysForTab[tabId] = scriptKey
        val combinedScript = buildString {
            if (hardeningScript.isNotEmpty()) {
                append(hardeningScript)
                append('\n')
            }
            append(blobHelperScript)
        }
        webView.evaluateJavascript(combinedScript, null)
    }

    fun fetchBlobDownload(
        tabId: String,
        blobUrl: String,
        fileName: String,
        mimeType: String,
        onResult: (SavedDownloadResult) -> Unit,
    ) {
        val wv = pool[tabId]
        if (wv == null) {
            onResult(
                SavedDownloadResult(
                    success = false,
                    savedFileName = fileName,
                    errorMessage = "Source browser tab was closed. Re-open the site and try downloading again.",
                ),
            )
            return
        }
        val requestId = UUID.randomUUID().toString()
        pendingBlobDownloads[requestId] = onResult
        val quotedId = JSONObject.quote(requestId)
        val quotedUrl = JSONObject.quote(blobUrl)
        val quotedName = JSONObject.quote(fileName)
        val quotedMime = JSONObject.quote(mimeType)
        mainHandler.post {
            runCatching {
                wv.evaluateJavascript(
                    PrivacyScripts.buildBlobDownloadHelperScript() +
                        "\nwindow.__osFetchBlobForDownload && window.__osFetchBlobForDownload($quotedId, $quotedUrl, $quotedName, $quotedMime);",
                    null,
                )
            }.onFailure { err ->
                pendingBlobDownloads.remove(requestId)
                onResult(
                    SavedDownloadResult(
                        success = false,
                        savedFileName = fileName,
                        errorMessage = err.message ?: "Unable to read browser blob download",
                    ),
                )
            }
        }
    }

    fun releaseAllViewsPreservingSession() {
        if (currentConfig.browserMode == BrowserMode.SURFACE) {
            flushCookies()
        }
        pool.values.forEach { wv ->
            runCatching {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.destroy()
            }
        }
        pool.clear()
        injectedScriptKeysForTab.clear()
        playingMediaTabs.clear()
        syncGlobalMediaRuntime()
        contextWrappers.values.forEach { it.baseContext = appContext }
        contextWrappers.clear()
        tabCurrentUrls.clear()
        softwareLayerTabs.clear()
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
        injectedScriptKeysForTab.clear()
        playingMediaTabs.clear()
        syncGlobalMediaRuntime()
        contextWrappers.values.forEach { it.baseContext = appContext }
        contextWrappers.clear()
        tabCurrentUrls.clear()
        softwareLayerTabs.clear()
        authenticatedHosts.clear()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
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
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onBlobDownloadCompleted(
                        requestId: String?,
                        dataUrl: String?,
                        preferredName: String?,
                        preferredMime: String?,
                        errorMessage: String?,
                    ) {
                        val callback = requestId?.let { pendingBlobDownloads.remove(it) } ?: return
                        val safeFallbackName = DownloadFileHelper.resolveDownloadFileName(
                            rawGuessedName = preferredName,
                            url = "",
                            contentDisposition = null,
                            mimeType = preferredMime,
                        )
                        if (!errorMessage.isNullOrBlank() || dataUrl.isNullOrBlank()) {
                            callback(
                                SavedDownloadResult(
                                    success = false,
                                    savedFileName = safeFallbackName,
                                    errorMessage = errorMessage?.ifBlank { null } ?: "Blob download unavailable",
                                ),
                            )
                            return
                        }
                        val decoded = DownloadFileHelper.decodeDataUri(dataUrl)
                        if (decoded == null) {
                            callback(
                                SavedDownloadResult(
                                    success = false,
                                    savedFileName = safeFallbackName,
                                    errorMessage = "Could not decode downloaded blob data",
                                ),
                            )
                            return
                        }
                        val resolvedMime = decoded.mimeType.ifBlank {
                            preferredMime.orEmpty().ifBlank { "application/octet-stream" }
                        }
                        val resolvedName = DownloadFileHelper.resolveDownloadFileName(
                            rawGuessedName = preferredName,
                            url = "",
                            contentDisposition = null,
                            mimeType = resolvedMime,
                        )
                        val saved = decoded.bytes.inputStream().use { stream ->
                            DownloadFileHelper.saveStreamToDownloads(
                                context = appContext,
                                fileName = resolvedName,
                                mimeType = resolvedMime,
                                inputStream = stream,
                            )
                        }
                        callback(saved)
                    }
                },
                "OnionShieldDownloadBridge",
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

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                val activeUrl = view?.url ?: tabCurrentUrls[tabId]
                WebsiteLogoCache.putCapturedIcon(
                    context = appContext,
                    url = activeUrl,
                    icon = icon,
                    persistToDisk = currentConfig.browserMode == BrowserMode.SURFACE && !TrackerAndUrlEngine.isOnionUrl(activeUrl),
                )
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
                val targetUa = if (currentConfig.fingerprintRandomizationEnabled) {
                    PrivacyScripts.profileForSeed(seed, currentConfig.browserMode).userAgent
                } else {
                    null
                }
                val popupWebView = WebView(wrapperContext).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    if (softwareLayerTabs.contains(tabId)) {
                        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                    }
                    settings.javaScriptEnabled = !isJsDisabledForTab(tabId, currentConfig, pageUrl)
                    settings.domStorageEnabled = currentConfig.browserMode == BrowserMode.SURFACE
                    settings.setSupportMultipleWindows(true)
                    settings.javaScriptCanOpenWindowsAutomatically = settings.javaScriptEnabled
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    if (targetUa != null) {
                        settings.userAgentString = targetUa
                    }
                    runCatching {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(currentConfig.browserMode == BrowserMode.SURFACE)
                        cm.setAcceptThirdPartyCookies(this, currentConfig.browserMode == BrowserMode.SURFACE)
                    }
                    var presented = false
                    webChromeClient = object : WebChromeClient() {
                        override fun onCloseWindow(window: WebView?) {
                            if (currentConfig.browserMode == BrowserMode.SURFACE) {
                                flushCookies()
                            }
                            callbacks.onClosePopupWebView(window ?: this@apply)
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onRenderProcessGone(
                            childView: WebView?,
                            detail: RenderProcessGoneDetail?,
                        ): Boolean {
                            softwareLayerTabs.add(tabId)
                            callbacks.onClosePopupWebView(childView ?: this@apply)
                            return true
                        }

                        override fun shouldOverrideUrlLoading(
                            childView: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val popupUrl = request?.url?.toString() ?: return false
                            val lowerPopup = popupUrl.lowercase()
                            if (lowerPopup.startsWith("javascript:") || lowerPopup.startsWith("file:")) {
                                return true
                            }
                            val isAdTarget = (popupShieldActive && TrackerAndUrlEngine.isPopupAdUrl(popupUrl, currentConfig.customBlockedDomains)) ||
                                (adBlockActiveForSite && TrackerAndUrlEngine.isAdOrTrackerRequest(popupUrl, pageUrl, currentConfig.customBlockedDomains, currentConfig.adBlockWhitelistHosts))
                            if (isAdTarget) {
                                callbacks.onTrackerBlocked(tabId)
                                callbacks.onClosePopupWebView(childView ?: this@apply)
                                return true
                            }
                            if ((lowerPopup.startsWith("http://") || lowerPopup.startsWith("https://")) && !presented) {
                                presented = true
                                recordAuthenticatedHostFromUrl(pageUrl)
                                recordAuthenticatedHostFromUrl(popupUrl)
                                callbacks.onOpenPopupWebView(this@apply)
                            }
                            return false
                        }

                        override fun shouldInterceptRequest(
                            childView: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            if (!request.isForMainFrame &&
                                currentConfig.trackerBlockerEnabled &&
                                TrackerAndUrlEngine.isAdOrTrackerRequest(
                                    url = reqUrl,
                                    pageUrl = pageUrl,
                                    customBlockedDomains = currentConfig.customBlockedDomains,
                                    whitelistHosts = currentConfig.adBlockWhitelistHosts,
                                )
                            ) {
                                return TrackerAndUrlEngine.emptyBlockedResponseForUrl(reqUrl)
                            }
                            return super.shouldInterceptRequest(childView, request)
                        }

                        override fun onPageStarted(
                            childView: WebView?,
                            url: String?,
                            favicon: Bitmap?,
                        ) {
                            val cleanUrl = url.orEmpty()
                            val isAdTarget = cleanUrl.isNotBlank() && (
                                (popupShieldActive && TrackerAndUrlEngine.isPopupAdUrl(cleanUrl, currentConfig.customBlockedDomains)) ||
                                    (adBlockActiveForSite && TrackerAndUrlEngine.isAdOrTrackerRequest(cleanUrl, pageUrl, currentConfig.customBlockedDomains, currentConfig.adBlockWhitelistHosts))
                                )
                            if (isAdTarget) {
                                callbacks.onTrackerBlocked(tabId)
                                callbacks.onClosePopupWebView(childView ?: this@apply)
                                return
                            }
                            if (!presented && (cleanUrl.startsWith("http://", true) || cleanUrl.startsWith("https://", true))) {
                                presented = true
                                recordAuthenticatedHostFromUrl(pageUrl)
                                recordAuthenticatedHostFromUrl(cleanUrl)
                                callbacks.onOpenPopupWebView(this@apply)
                            }
                        }

                        override fun onPageFinished(childView: WebView?, url: String?) {
                            if (currentConfig.browserMode == BrowserMode.SURFACE) {
                                flushCookies()
                            }
                        }
                    }
                }
                transport.webView = popupWebView
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
                    injectHardeningScript(
                        tabId = tabId,
                        webView = view,
                        url = safeUrl,
                        seed = seed,
                        skipIfSameDocumentConfig = !isReload,
                    )
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val safeUrl = url.orEmpty()
                injectedScriptKeysForTab.remove(tabId)
                if (favicon != null && safeUrl.isNotBlank()) {
                    WebsiteLogoCache.putCapturedIcon(
                        context = appContext,
                        url = safeUrl,
                        icon = favicon,
                        persistToDisk = currentConfig.browserMode == BrowserMode.SURFACE && !TrackerAndUrlEngine.isOnionUrl(safeUrl),
                    )
                }
                if (safeUrl.isNotBlank()) {
                    if (currentConfig.browserMode == BrowserMode.SURFACE && isAuthFlowUrl(safeUrl)) {
                        recordAuthenticatedHostFromUrl(tabCurrentUrls[tabId])
                        recordAuthenticatedHostFromUrl(safeUrl)
                    }
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
                    if (currentConfig.browserMode == BrowserMode.SURFACE && isAuthFlowUrl(safeUrl)) {
                        recordAuthenticatedHostFromUrl(safeUrl)
                    }
                }
                injectHardeningScript(tabId, view, safeUrl, seed)
                if (currentConfig.browserMode == BrowserMode.SURFACE) {
                    flushCookies()
                }
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
                injectedScriptKeysForTab.remove(tabId)
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
            val safeUrl = url.orEmpty()
            val refererUrl = tabCurrentUrls[tabId].orEmpty()
            val baseMime = mimeType?.substringBefore(';')?.trim().orEmpty().ifBlank { "application/octet-stream" }
            val isOnionOrigin = TrackerAndUrlEngine.isOnionUrl(safeUrl) ||
                TrackerAndUrlEngine.isOnionUrl(refererUrl) ||
                currentConfig.browserMode == BrowserMode.TOR_ONION
            val rawGuessed = URLUtil.guessFileName(safeUrl, contentDisposition, baseMime)

            if (safeUrl.startsWith("blob:", ignoreCase = true)) {
                val peekJs = "(function(){ return window.__osPeekBlobHint ? window.__osPeekBlobHint(${JSONObject.quote(safeUrl)}) : '{}'; })();"
                webView.evaluateJavascript(peekJs) { rawJson ->
                    val parsedJson = runCatching {
                        val unwrapped = rawJson?.trim().orEmpty()
                        val jsonStr = if (unwrapped.startsWith("\"") && unwrapped.endsWith("\"")) {
                            org.json.JSONTokener(unwrapped).nextValue()?.toString().orEmpty()
                        } else {
                            unwrapped
                        }
                        if (jsonStr.isBlank() || jsonStr == "null") JSONObject() else JSONObject(jsonStr)
                    }.getOrDefault(JSONObject())
                    val hintedName = parsedJson.optString("fileName", "").trim().ifBlank { null }
                    val hintedMime = parsedJson.optString("mimeType", "").trim().ifBlank { baseMime }
                    val hintedSize = parsedJson.optLong("size", -1L).takeIf { it > 0L } ?: contentLength
                    val resolvedName = DownloadFileHelper.resolveDownloadFileName(
                        rawGuessedName = hintedName ?: rawGuessed,
                        url = safeUrl,
                        contentDisposition = contentDisposition,
                        mimeType = hintedMime,
                    )
                    callbacks.onDownloadIntercepted(
                        tabId,
                        PendingDownloadRequest(
                            url = safeUrl,
                            userAgent = userAgent.orEmpty(),
                            contentDisposition = contentDisposition.orEmpty(),
                            mimeType = hintedMime,
                            contentLength = hintedSize,
                            fileName = resolvedName,
                            isOnionSource = isOnionOrigin,
                            sourceTabId = tabId,
                            refererUrl = refererUrl,
                        ),
                    )
                }
            } else {
                val resolvedName = DownloadFileHelper.resolveDownloadFileName(
                    rawGuessedName = rawGuessed,
                    url = safeUrl,
                    contentDisposition = contentDisposition,
                    mimeType = baseMime,
                )
                callbacks.onDownloadIntercepted(
                    tabId,
                    PendingDownloadRequest(
                        url = safeUrl,
                        userAgent = userAgent.orEmpty(),
                        contentDisposition = contentDisposition.orEmpty(),
                        mimeType = baseMime,
                        contentLength = contentLength,
                        fileName = resolvedName,
                        isOnionSource = isOnionOrigin,
                        sourceTabId = tabId,
                        refererUrl = refererUrl,
                    ),
                )
            }
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
        val effectiveUrl = urlOverride ?: tabCurrentUrls[tabId]
        val jsEnabled = !isJsDisabledForTab(tabId, config, effectiveUrl)
        val settings = webView.settings
        if (settings.javaScriptEnabled != jsEnabled) {
            settings.javaScriptEnabled = jsEnabled
        }
        if (settings.javaScriptCanOpenWindowsAutomatically != jsEnabled) {
            settings.javaScriptCanOpenWindowsAutomatically = jsEnabled
        }
        runCatching {
            val allowThirdParty = !config.blockThirdPartyCookies ||
                (config.browserMode == BrowserMode.SURFACE && isAccountSessionHost(effectiveUrl, authenticatedHosts))
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, allowThirdParty)
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
            val effectiveUrl = urlOverride ?: tabCurrentUrls[tabId]
            val allowThirdParty = !config.blockThirdPartyCookies ||
                (config.browserMode == BrowserMode.SURFACE && isAccountSessionHost(effectiveUrl, authenticatedHosts))
            cookieManager.setAcceptThirdPartyCookies(webView, allowThirdParty)
        }
    }

    companion object {
        private val GOOGLE_DOMAIN_REGEX = Regex("^([a-z0-9-]+\\.)*google\\.[a-z.]{2,}$")
        private val AUTH_PATH_MARKERS = listOf(
            "/login",
            "/log-in",
            "/signin",
            "/sign-in",
            "/oauth",
            "/oauth2",
            "/sso",
            "/auth/",
            "/authenticate",
            "/servicelogin",
            "/accountchooser",
            "/checkin",
        )
        private val ACCOUNT_SESSION_BASE_HOSTS = setOf(
            "youtube.com",
            "youtu.be",
            "gmail.com",
            "googleapis.com",
            "gstatic.com",
            "googleusercontent.com",
            "facebook.com",
            "fb.com",
            "messenger.com",
            "instagram.com",
            "github.com",
            "githubusercontent.com",
            "microsoft.com",
            "live.com",
            "office.com",
            "outlook.com",
            "microsoftonline.com",
            "x.com",
            "twitter.com",
            "discord.com",
            "reddit.com",
            "linkedin.com",
            "paypal.com",
            "amazon.com",
            "netflix.com",
            "spotify.com",
            "yahoo.com",
            "apple.com",
            "icloud.com",
            "notion.so",
            "figma.com",
            "canva.com",
            "dropbox.com",
            "auth0.com",
            "okta.com",
        )

        private fun matchesBaseHost(host: String, base: String): Boolean =
            host == base || host.endsWith(".$base")

        internal fun isAuthFlowUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val host = TrackerAndUrlEngine.normalizeHost(url) ?: return false
            if (host == "accounts.google.com" ||
                host.startsWith("accounts.google.") ||
                host.endsWith(".accounts.google.com") ||
                host == "accounts.youtube.com" ||
                host == "gsi.google.com" ||
                host == "myaccount.google.com" ||
                host == "oauth2.googleapis.com" ||
                host == "apis.google.com" ||
                host == "login.microsoftonline.com" ||
                host == "login.live.com" ||
                host == "account.live.com" ||
                host == "appleid.apple.com" ||
                host == "idmsa.apple.com" ||
                matchesBaseHost(host, "auth0.com") ||
                matchesBaseHost(host, "okta.com") ||
                matchesBaseHost(host, "oktapreview.com") ||
                host.endsWith(".amazoncognito.com") ||
                host.startsWith("accounts.") ||
                host.startsWith("login.") ||
                host.startsWith("signin.") ||
                host.startsWith("oauth.") ||
                host.startsWith("sso.")
            ) {
                return true
            }
            val path = runCatching { Uri.parse(url).encodedPath.orEmpty().lowercase() }.getOrDefault("")
            return AUTH_PATH_MARKERS.any { marker -> path.contains(marker) }
        }

        internal fun isAccountSessionHost(
            url: String?,
            authenticatedHosts: Set<String> = emptySet(),
        ): Boolean {
            if (isAuthFlowUrl(url)) return true
            val host = TrackerAndUrlEngine.normalizeHost(url)?.removePrefix("www.") ?: return false
            if (GOOGLE_DOMAIN_REGEX.matches(host)) return true
            if (ACCOUNT_SESSION_BASE_HOSTS.any { base -> matchesBaseHost(host, base) }) return true
            return authenticatedHosts.any { saved -> matchesBaseHost(host, saved) }
        }
    }
}

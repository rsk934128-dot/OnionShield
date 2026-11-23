package com.example

import com.example.browser.DetectedTradeButton
import com.example.browser.DownloadFileHelper
import com.example.browser.PrivacyScripts
import com.example.browser.TradeDecision
import com.example.browser.TradePilotHelper
import com.example.browser.TradeSignalDecision
import com.example.browser.WebsiteLogoCache
import com.example.data.DownloadEntity
import com.example.data.repository.GeminiShieldRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun extractNormalizedHost_stripsWwwAndIgnoresInternalSchemes() {
        assertEquals("youtube.com", WebsiteLogoCache.extractNormalizedHost("https://www.YouTube.com/watch?v=123"))
        assertEquals("m.youtube.com", WebsiteLogoCache.extractNormalizedHost("https://m.youtube.com/feed"))
        assertNull(WebsiteLogoCache.extractNormalizedHost("about:start"))
        assertNull(WebsiteLogoCache.extractNormalizedHost("javascript:void(0)"))
        assertNull(WebsiteLogoCache.extractNormalizedHost(""))
    }

    @Test
    fun extractRootHost_handlesSubdomainsAndCountryTlds() {
        assertEquals("wikipedia.org", WebsiteLogoCache.extractRootHost("en.m.wikipedia.org"))
        assertEquals("youtube.com", WebsiteLogoCache.extractRootHost("m.youtube.com"))
        assertEquals("bbc.co.uk", WebsiteLogoCache.extractRootHost("news.bbc.co.uk"))
        assertEquals("github.com", WebsiteLogoCache.extractRootHost("github.com"))
    }

    @Test
    fun buildFaviconCandidateUrls_blocksOnionLocalhostAndTorModeFetch() {
        val onionUrl = "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/"
        assertTrue(WebsiteLogoCache.isOnionHostOrUrl(onionUrl))
        assertTrue(WebsiteLogoCache.buildFaviconCandidateUrls(onionUrl, allowNetworkFetch = true).isEmpty())
        assertTrue(WebsiteLogoCache.buildFaviconCandidateUrls("https://localhost:8080/app", allowNetworkFetch = true).isEmpty())
        assertTrue(WebsiteLogoCache.buildFaviconCandidateUrls("http://127.0.0.1:9050", allowNetworkFetch = true).isEmpty())
        assertTrue(WebsiteLogoCache.buildFaviconCandidateUrls("https://github.com", allowNetworkFetch = false).isEmpty())
    }

    @Test
    fun buildFaviconCandidateUrls_buildsHighResAndDirectFallbacksForSurfaceUrls() {
        val candidates = WebsiteLogoCache.buildFaviconCandidateUrls(
            url = "https://m.youtube.com/watch?v=abc",
            allowNetworkFetch = true,
        )
        assertTrue(candidates.any { it.contains("google.com/s2/favicons") && it.contains("sz=128") })
        assertTrue(candidates.any { it.contains("icons.duckduckgo.com/ip3/m.youtube.com.ico") })
        assertTrue(candidates.any { it == "https://m.youtube.com/apple-touch-icon.png" })
        assertTrue(candidates.any { it == "https://m.youtube.com/favicon.ico" })
        assertTrue(candidates.any { it.contains("domain=youtube.com") })
    }

    @Test
    fun parseHtmlIconUrls_prioritizesAppleTouchAndPngAndSkipsOnionOrData() {
        val html = """
            <html><head>
              <link rel="shortcut icon" href="/favicon.ico">
              <link rel="icon" type="image/png" href="/assets/logo-192.png">
              <link rel="apple-touch-icon" href="https://cdn.example.com/apple-icon.png">
              <link rel="icon" href="data:image/png;base64,AAA">
              <link rel="icon" href="http://hidden123456.onion/icon.png">
            </head></html>
        """.trimIndent()
        val parsed = WebsiteLogoCache.parseHtmlIconUrls(html, "https://example.com/home")
        assertEquals(
            listOf(
                "https://cdn.example.com/apple-icon.png",
                "https://example.com/assets/logo-192.png",
                "https://example.com/favicon.ico",
            ),
            parsed,
        )
        assertFalse(parsed.any { it.contains(".onion") || it.startsWith("data:") })
    }

    @Test
    fun buildDiskFileName_isDeterministicPngSha256() {
        val a = WebsiteLogoCache.buildDiskFileName("youtube.com")
        val b = WebsiteLogoCache.buildDiskFileName("youtube.com")
        val c = WebsiteLogoCache.buildDiskFileName("github.com")
        assertEquals(a, b)
        assertTrue(a.endsWith(".png"))
        assertTrue(a != c)
    }

    @Test
    fun privacyScripts_protectYouTubeShortsFromConcurrentResumeAndGuardHooksOnce() {
        val script = PrivacyScripts.buildHardeningScript(
            seed = "SHORTS99",
            enableWebRtcGuard = true,
            enableFingerprintRandomization = true,
            enableCosmeticAdBlock = true,
            enablePopupGuard = true,
            enableBackgroundMediaPlayback = true,
        )
        assertTrue(script.contains("__osWebRtcGuardInstalled"))
        assertTrue(script.contains("__osFingerprintGuardInstalled"))
        assertTrue(script.contains("OnionShield Virtual GPU (SHORTS99)"))
        assertTrue(script.contains("isShortsRoute"))
        assertTrue(script.contains("isElementInViewport"))
        assertTrue(script.contains("visibleMediaArea"))
        assertTrue(script.contains("__osSetAppBackground"))
        assertTrue(script.contains("ytm-reel-player-overlay-renderer"))
        assertTrue(script.contains("if (/^\\/shorts(\\/|$)/i.test(location.pathname || ''))"))

        val blobScript = PrivacyScripts.buildBlobDownloadHelperScript()
        assertTrue(blobScript.contains("__osBlobDownloadHelperInstalled"))
        assertTrue(blobScript.contains("__osPeekBlobHint"))
        assertTrue(blobScript.contains("__osFetchBlobForDownload"))
        assertTrue(blobScript.contains("OnionShieldDownloadBridge"))
    }

    @Test
    fun downloadFileHelper_replacesUuidBlobNamesAndSanitizesDispositions() {
        assertTrue(DownloadFileHelper.isUuidLikeName("760cb5e9-3e14-43c3-a130-fd1234567890"))
        assertTrue(DownloadFileHelper.isUuidLikeName("760cb5e9-3e14-43c3-a130-fd1234567890.bin"))
        assertFalse(DownloadFileHelper.isUuidLikeName("NetPulse-WiFi-v1.0.1-debug.apk"))

        assertEquals(
            "download_export.zip",
            DownloadFileHelper.resolveDownloadFileName(
                rawGuessedName = "760cb5e9-3e14-43c3-a130-fd1234567890.bin",
                url = "blob:https://example.com/760cb5e9-3e14-43c3-a130-fd1234567890",
                contentDisposition = null,
                mimeType = "application/zip",
            ),
        )
        assertEquals(
            "my app build.apk",
            DownloadFileHelper.resolveDownloadFileName(
                rawGuessedName = "download.bin",
                url = "https://github.com/org/repo/releases/download/v1/app.apk",
                contentDisposition = "attachment; filename*=UTF-8''my%20app%20build.apk",
                mimeType = "application/vnd.android.package-archive",
            ),
        )
        assertEquals(
            "report_2026_.pdf",
            DownloadFileHelper.sanitizeFileName("report:2026?.pdf"),
        )
    }

    @Test
    fun downloadFileHelper_formatsSizesAndExplainsFailuresClearly() {
        assertEquals("", DownloadFileHelper.formatByteSize(0L))
        assertEquals("512 B", DownloadFileHelper.formatByteSize(512L))
        assertEquals("250 KB", DownloadFileHelper.formatByteSize(250 * 1024L))
        assertEquals("14.5 MB", DownloadFileHelper.formatByteSize((14.5 * 1024 * 1024).toLong()))

        val uuidFailed = DownloadEntity(
            id = 1L,
            fileName = "760cb5e9-3e14-43c3-a130-fd1234567890",
            url = "blob:https://example.com/760cb5e9-3e14-43c3-a130-fd1234567890",
            mimeType = "application/octet-stream",
            status = "FAILED",
        )
        val blobReason = DownloadFileHelper.describeFailureReason(uuidFailed, null)
        assertTrue(blobReason!!.contains("blob", ignoreCase = true))

        val completed = uuidFailed.copy(status = "COMPLETED", fileName = "app.apk")
        assertNull(DownloadFileHelper.describeFailureReason(completed, null))
    }

    @Test
    fun tradePilotHelper_parsesDomButtonsAndResolvesClickTargetsSafely() {
        val rawEvalJson = "\"[{\\\"action\\\":\\\"SELL\\\",\\\"label\\\":\\\"SELL 86%\\\",\\\"normX\\\":0.28,\\\"normY\\\":0.88,\\\"widthRatio\\\":0.42,\\\"heightRatio\\\":0.09,\\\"enabled\\\":true},{\\\"action\\\":\\\"BUY\\\",\\\"label\\\":\\\"BUY 86%\\\",\\\"normX\\\":0.72,\\\"normY\\\":0.88,\\\"widthRatio\\\":0.42,\\\"heightRatio\\\":0.09,\\\"enabled\\\":true}]\""
        val parsed = TradePilotHelper.parseDetectedButtonsJson(rawEvalJson)
        assertEquals(2, parsed.size)
        assertEquals("SELL", parsed[0].action)
        assertEquals("BUY", parsed[1].action)

        val buyTarget = TradePilotHelper.resolveClickTarget(
            decision = TradeDecision.BUY,
            aiNormX = 0.65f,
            aiNormY = 0.80f,
            domButtons = parsed,
        )
        assertNotNull(buyTarget)
        assertEquals("DOM", buyTarget!!.source)
        assertEquals(0.72f, buyTarget.normX, 0.001f)
        assertEquals(0.88f, buyTarget.normY, 0.001f)

        val canvasFallbackTarget = TradePilotHelper.resolveClickTarget(
            decision = TradeDecision.SELL,
            aiNormX = 0.25f,
            aiNormY = 0.87f,
            domButtons = emptyList(),
        )
        assertNotNull(canvasFallbackTarget)
        assertEquals("VISION", canvasFallbackTarget!!.source)
        assertEquals(0.25f, canvasFallbackTarget.normX, 0.001f)

        assertNull(
            TradePilotHelper.resolveClickTarget(
                decision = TradeDecision.HOLD,
                aiNormX = 0.5f,
                aiNormY = 0.5f,
                domButtons = parsed,
            ),
        )
    }

    @Test
    fun tradePilot_parsesVisionDecisionAndEnforcesConfidenceThreshold() {
        val sampleJson = """
            {
              "decision": "BUY",
              "confidence": 84,
              "assetName": "EUR / USD (OTC)",
              "accountMode": "DEMO",
              "marketBias": "Strong Uptrend",
              "reasoning": "Higher lows breaking above resistance with upward slope.",
              "targetLabel": "BUY 86%",
              "normalizedX": 0.72,
              "normalizedY": 0.89
            }
        """.trimIndent()
        val parsed = GeminiShieldRepository.parseTradeDecisionJson(sampleJson)
        assertEquals(TradeDecision.BUY, parsed.decision)
        assertEquals(84, parsed.confidence)
        assertEquals("EUR / USD (OTC)", parsed.assetName)
        assertEquals("DEMO", parsed.accountMode)
        assertTrue(TradePilotHelper.shouldAutoExecuteClick(parsed, minConfidencePercent = 75))
        assertFalse(TradePilotHelper.shouldAutoExecuteClick(parsed, minConfidencePercent = 90))

        val holdDecision = GeminiShieldRepository.parseTradeDecisionJson(
            """{"decision":"HOLD","confidence":95,"normalizedX":1.4,"normalizedY":-0.2}""",
        )
        assertEquals(TradeDecision.HOLD, holdDecision.decision)
        assertEquals(1.0f, holdDecision.normalizedX, 0.001f)
        assertEquals(0.0f, holdDecision.normalizedY, 0.001f)
        assertFalse(TradePilotHelper.shouldAutoExecuteClick(holdDecision, minConfidencePercent = 50))

        val prompt = GeminiShieldRepository.buildTradeAnalysisPrompt(
            pageUrl = "https://m.expertoption.com",
            domControlsSummary = TradePilotHelper.formatControlsSummary(emptyList()),
        )
        assertTrue(prompt.contains("https://m.expertoption.com"))
        assertTrue(prompt.contains("BUY or SELL"))
        assertNotNull(GeminiShieldRepository.TRADE_VISION_SCHEMA)
    }
}

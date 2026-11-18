package com.example.browser

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceResponse
import com.example.data.ABOUT_START_URL
import com.example.data.BrowserMode
import com.example.data.SearchEngine
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object TrackerAndUrlEngine {

    private val blockedAdAndTrackerDomains = setOf(
        // Major ad networks & exchanges
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "imasdk.googleapis.com",
        "ads.youtube.com",
        "s0.2mdn.net",
        "2mdn.net",
        "fwmrm.net",
        "innovid.com",
        "serving-sys.com",
        "adsafeprotected.com",
        "doubleverify.com",
        "amazon-adsystem.com",
        "adnxs.com",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "casalemedia.com",
        "indexexchange.com",
        "33across.com",
        "sharethrough.com",
        "triplelift.com",
        "smartadserver.com",
        "adform.net",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "mgid.com",
        "revcontent.com",
        "zemanta.com",
        "media.net",
        "sovrn.com",
        "lijit.com",
        "teads.tv",
        "spotxchange.com",
        "advertising.com",
        "ads.yahoo.com",
        "adroll.com",
        "adsrvr.org",
        "moatads.com",
        "bidvertiser.com",
        // Popunder & aggressive mobile ad networks
        "propellerads.com",
        "popads.net",
        "popcash.net",
        "adsterra.com",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.com",
        "hilltopads.net",
        "richpush.co",
        "clickadu.com",
        "admaven.com",
        // Mobile & video ad SDKs
        "admob.com",
        "applovin.com",
        "unityads.unity3d.com",
        "ironsrc.com",
        "vungle.com",
        "inmobi.com",
        "chartboost.com",
        "adcolony.com",
        // Analytics, telemetry & social tracking pixels
        "google-analytics.com",
        "googletagmanager.com",
        "connect.facebook.net",
        "pixel.facebook.com",
        "an.facebook.com",
        "ads-twitter.com",
        "analytics.twitter.com",
        "bat.bing.com",
        "mc.yandex.ru",
        "analytics.tiktok.com",
        "ads.tiktok.com",
        "sc-static.net",
        "ads.snapchat.com",
        "ads.pinterest.com",
        "ads.reddit.com",
        "alb.reddit.com",
        "scorecardresearch.com",
        "quantserve.com",
        "hotjar.com",
        "mixpanel.com",
        "segment.io",
        "segment.com",
        "amplitude.com",
        "appsflyer.com",
        "adjust.com",
        "branch.io",
        "clarity.ms",
        "demdex.net",
        "krxd.net",
        "rlcdn.com",
        "bluekai.com",
        // Crypto-miners & adware scripts
        "coinhive.com",
        "coin-hive.com",
        "jsecoin.com",
        "minero.cc",
    )

    private val blockedHostPathRules = listOf(
        "bing.com" to "/bat",
        "yandex.ru" to "/metrika",
        "tiktok.com" to "/i18n/pixel",
        "facebook.com" to "/tr",
        "snapchat.com" to "/scevent",
        "youtube.com" to "/api/stats/ads",
        "youtube.com" to "/pagead/",
        "youtube.com" to "/ptracking",
        "youtube.com" to "/get_midroll_info",
        "youtube.com" to "/pcs/activeview",
        "youtube.com" to "/youtubei/v1/player/ad_break",
        "youtube-nocookie.com" to "/api/stats/ads",
        "youtube-nocookie.com" to "/pagead/",
        "youtube-nocookie.com" to "/ptracking",
    )

    private val thirdPartyAdPathMarkers = listOf(
        "/pagead/",
        "/adserver/",
        "/popunder",
        "/vast.xml",
        "/vpaid/",
        "/prebid",
        "/ad_banner",
        "/doubleclick/",
        "/adsid/",
    )

    private val popupAdDomains = setOf(
        "propellerads.com",
        "popads.net",
        "popcash.net",
        "adsterra.com",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.com",
        "hilltopads.net",
        "clickadu.com",
        "admaven.com",
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
    )

    private fun hostMatchesSet(host: String, domains: Set<String>): Boolean {
        return domains.any { blocked ->
            host == blocked || host.endsWith(".$blocked")
        }
    }

    fun isSiteWhitelisted(pageUrl: String?, whitelistHosts: Set<String>): Boolean {
        if (whitelistHosts.isEmpty()) return false
        val pageHost = normalizeHost(pageUrl) ?: return false
        return whitelistHosts.any { allowed ->
            pageHost == allowed || pageHost.endsWith(".$allowed")
        }
    }

    fun isTrackerRequest(url: String?): Boolean {
        return isAdOrTrackerRequest(url = url)
    }

    fun isAdOrTrackerRequest(
        url: String?,
        pageUrl: String? = null,
        customBlockedDomains: Set<String> = emptySet(),
        whitelistHosts: Set<String> = emptySet(),
    ): Boolean {
        if (url.isNullOrBlank()) return false
        if (isSiteWhitelisted(pageUrl, whitelistHosts)) return false

        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val rawHost = parsed.host?.lowercase()?.removePrefix("www.") ?: return false
        val path = parsed.path?.lowercase().orEmpty()

        if (customBlockedDomains.isNotEmpty() && hostMatchesSet(rawHost, customBlockedDomains)) {
            return true
        }
        if (hostMatchesSet(rawHost, blockedAdAndTrackerDomains)) {
            return true
        }
        if (blockedHostPathRules.any { (ruleHost, rulePath) ->
                (rawHost == ruleHost || rawHost.endsWith(".$ruleHost")) && path.startsWith(rulePath)
            }
        ) {
            return true
        }

        val query = parsed.encodedQuery?.lowercase().orEmpty()
        if ((rawHost == "googlevideo.com" || rawHost.endsWith(".googlevideo.com")) &&
            path.contains("/videoplayback") &&
            (query.contains("oad=") || query.contains("ad_type=") || query.contains("ctier=l"))
        ) {
            return true
        }

        val pageHost = normalizeHost(pageUrl)
        if (pageHost != null && rawHost != pageHost && !rawHost.endsWith(".$pageHost") && !pageHost.endsWith(".$rawHost")) {
            if (thirdPartyAdPathMarkers.any { marker -> path.contains(marker) }) {
                return true
            }
        }
        return false
    }

    fun isPopupAdUrl(url: String?, customBlockedDomains: Set<String> = emptySet()): Boolean {
        if (url.isNullOrBlank()) return false
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = parsed.host?.lowercase()?.removePrefix("www.") ?: return false
        if (hostMatchesSet(host, popupAdDomains) ||
            (customBlockedDomains.isNotEmpty() && hostMatchesSet(host, customBlockedDomains))
        ) {
            return true
        }
        val lower = url.lowercase()
        return lower.contains("popunder") || lower.contains("clicktrack") || lower.contains("/aclk?")
    }

    fun emptyBlockedResponse(): WebResourceResponse = emptyBlockedResponseForUrl(null)

    fun emptyBlockedResponseForUrl(url: String?): WebResourceResponse {
        val path = runCatching { Uri.parse(url.orEmpty()).path?.lowercase() }.getOrNull().orEmpty()
        val mimeType = when {
            path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") -> "image/png"
            else -> "text/plain"
        }
        return WebResourceResponse(
            mimeType,
            "utf-8",
            204,
            "Blocked by OnionShield",
            mapOf("X-OnionShield-Blocked" to "true"),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    fun isOnionUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()
        return host?.endsWith(".onion") == true || url.contains(".onion", ignoreCase = true)
    }

    fun isLocalhostOrLoopback(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    fun resolveInputToUrl(
        rawInput: String,
        mode: BrowserMode,
        httpsEverywhere: Boolean,
        searchEngine: SearchEngine,
    ): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return ABOUT_START_URL
        if (trimmed.equals(ABOUT_START_URL, ignoreCase = true)) return ABOUT_START_URL

        // Reject unsafe schemes explicitly
        val lower = trimmed.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("data:")) {
            return ABOUT_START_URL
        }

        val hasScheme = lower.startsWith("http://") || lower.startsWith("https://")
        val looksLikeDomain = !trimmed.contains(' ') && (
            trimmed.contains('.') ||
                lower.startsWith("localhost") ||
                lower.startsWith("127.0.0.1")
            )

        val candidate = when {
            hasScheme -> trimmed
            looksLikeDomain -> {
                val isOnion = lower.endsWith(".onion") || lower.contains(".onion/")
                val isLoopback = lower.startsWith("localhost") || lower.startsWith("127.0.0.1")
                if (isOnion || isLoopback) "http://$trimmed" else "https://$trimmed"
            }
            else -> {
                val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.toString())
                val template = if (mode == BrowserMode.TOR_ONION) {
                    searchEngine.onionQueryTemplate
                } else {
                    searchEngine.surfaceQueryTemplate
                }
                return template.replace("%s", encoded)
            }
        }

        return applyHttpsUpgradeIfNeeded(candidate, httpsEverywhere)
    }

    fun applyHttpsUpgradeIfNeeded(url: String, httpsEverywhere: Boolean): String {
        if (!httpsEverywhere) return url
        if (!url.startsWith("http://", ignoreCase = true)) return url
        if (isOnionUrl(url) || isLocalhostOrLoopback(url)) return url
        return "https://" + url.substring(7)
    }

    fun normalizeHost(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return null
        return host.removePrefix("www.").takeIf { it.isNotBlank() }
    }

    fun extractIntentFallbackUrl(intentUri: String): String? {
        if (!intentUri.startsWith("intent:", ignoreCase = true)) return null
        val parsedFallback = runCatching {
            val intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME)
            intent.getStringExtra("browser_fallback_url")
        }.getOrNull()
        val candidate = parsedFallback ?: run {
            val marker = "S.browser_fallback_url="
            val idx = intentUri.indexOf(marker)
            if (idx >= 0) {
                val rawVal = intentUri.substring(idx + marker.length).substringBefore(';')
                runCatching { java.net.URLDecoder.decode(rawVal, "UTF-8") }.getOrNull()
            } else {
                null
            }
        }
        return candidate?.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
    }

    fun extractFirstHttpUrl(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null
        val regex = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
        val match = regex.find(rawText.trim())?.value
        if (!match.isNullOrBlank()) {
            return match.trimEnd('.', ',', ';', ')', ']')
        }
        return null
    }

    fun isApkAsset(url: String?, fileName: String?, mimeType: String?): Boolean {
        if (mimeType.equals("application/vnd.android.package-archive", ignoreCase = true)) {
            return true
        }
        if (!fileName.isNullOrBlank() && fileName.trim().lowercase().endsWith(".apk")) {
            return true
        }
        val path = runCatching { Uri.parse(url.orEmpty()).path?.lowercase() }.getOrNull().orEmpty()
        return path.endsWith(".apk")
    }
}

package com.example.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object WebsiteLogoCache {
    private const val DISK_CACHE_DIR = "site_logos"
    private const val MAX_MEMORY_ENTRIES = 160
    private const val MAX_ICON_BYTES = 512 * 1024
    private const val MAX_HTML_SNIFF_BYTES = 32 * 1024
    private const val MAX_BITMAP_DIM_PX = 192
    private const val NEGATIVE_CACHE_COOLDOWN_MS = 45_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memoryCache = LruCache<String, Bitmap>(MAX_MEMORY_ENTRIES)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()
    private val negativeCacheTimestamps = ConcurrentHashMap<String, Long>()

    private val _cacheVersion = MutableStateFlow(0)
    val cacheVersion: StateFlow<Int> = _cacheVersion.asStateFlow()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun isOnionHostOrUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = extractRawHost(url)
        return host?.endsWith(".onion") == true || url.lowercase(Locale.US).contains(".onion")
    }

    fun extractRawHost(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        if (trimmed.startsWith("about:", ignoreCase = true) ||
            trimmed.startsWith("javascript:", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true) ||
            trimmed.startsWith("file:", ignoreCase = true)
        ) {
            return null
        }
        val normalizedInput = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val host = runCatching { URI(normalizedInput).host?.lowercase(Locale.US) }.getOrNull()
        return host?.takeIf { it.isNotBlank() }
    }

    fun extractNormalizedHost(url: String?): String? {
        val raw = extractRawHost(url) ?: return null
        return raw.removePrefix("www.").takeIf { it.isNotBlank() }
    }

    internal fun extractRootHost(host: String): String {
        val clean = host.trim().lowercase(Locale.US).removePrefix("www.")
        val parts = clean.split('.').filter { it.isNotBlank() }
        if (parts.size <= 2) return clean
        val secondLevelTlds = setOf("co", "com", "org", "net", "gov", "edu", "ac")
        return if (parts.size >= 3 && parts[parts.size - 1].length == 2 && parts[parts.size - 2] in secondLevelTlds) {
            parts.takeLast(3).joinToString(".")
        } else {
            parts.takeLast(2).joinToString(".")
        }
    }

    fun buildCacheKey(url: String?): String? = extractNormalizedHost(url)

    fun buildDiskFileName(cacheKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray(StandardCharsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "$hex.png"
    }

    fun buildFaviconCandidateUrls(url: String, allowNetworkFetch: Boolean): List<String> {
        if (!allowNetworkFetch) return emptyList()
        val trimmed = url.trim()
        if (trimmed.isBlank() || isOnionHostOrUrl(trimmed)) return emptyList()

        val uri = runCatching {
            val normalized = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            URI(normalized)
        }.getOrNull() ?: return emptyList()

        val scheme = uri.scheme?.lowercase(Locale.US) ?: return emptyList()
        if (scheme != "http" && scheme != "https") return emptyList()

        val rawHost = uri.host?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (rawHost == "localhost" || rawHost == "127.0.0.1" || rawHost == "::1" || rawHost.endsWith(".onion")) {
            return emptyList()
        }

        val normalizedHost = rawHost.removePrefix("www.")
        val rootHost = extractRootHost(normalizedHost)
        val origin = "$scheme://$rawHost"
        val tlsOrigin = "https://$rawHost"
        val encodedOrigin = URLEncoder.encode(origin, "UTF-8")
        val encodedHost = URLEncoder.encode(normalizedHost, "UTF-8")

        val candidates = linkedSetOf(
            "https://www.google.com/s2/favicons?domain_url=$encodedOrigin&sz=128",
            "https://www.google.com/s2/favicons?domain=$encodedHost&sz=128",
            "https://icons.duckduckgo.com/ip3/$encodedHost.ico",
            "$tlsOrigin/apple-touch-icon.png",
            "$tlsOrigin/favicon.ico",
        )
        if (rootHost != normalizedHost) {
            val encodedRoot = URLEncoder.encode(rootHost, "UTF-8")
            candidates += "https://www.google.com/s2/favicons?domain=$encodedRoot&sz=128"
            candidates += "https://icons.duckduckgo.com/ip3/$encodedRoot.ico"
        }
        return candidates.toList()
    }

    internal fun parseHtmlIconUrls(htmlHead: String, baseUrl: String): List<String> {
        if (htmlHead.isBlank()) return emptyList()
        val baseUri = runCatching { URI(baseUrl) }.getOrNull() ?: return emptyList()
        val linkTagRegex = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
        val relRegex = Regex("""rel\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val hrefRegex = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        val prioritized = mutableListOf<Pair<Int, String>>()
        linkTagRegex.findAll(htmlHead).forEach { match ->
            val tag = match.value
            val rel = relRegex.find(tag)?.groupValues?.getOrNull(1)?.lowercase(Locale.US).orEmpty()
            if (!rel.contains("icon")) return@forEach
            val href = hrefRegex.find(tag)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (href.isBlank() || href.startsWith("data:", ignoreCase = true)) return@forEach
            val resolved = runCatching { baseUri.resolve(href).toString() }.getOrNull() ?: return@forEach
            if (!resolved.startsWith("http://", ignoreCase = true) && !resolved.startsWith("https://", ignoreCase = true)) {
                return@forEach
            }
            if (isOnionHostOrUrl(resolved)) return@forEach
            val priority = when {
                rel.contains("apple-touch-icon") -> 0
                resolved.endsWith(".png", ignoreCase = true) -> 1
                else -> 2
            }
            prioritized += priority to resolved
        }
        return prioritized.sortedBy { it.first }.map { it.second }.distinct()
    }

    fun getCachedMemory(url: String?): Bitmap? {
        val key = buildCacheKey(url) ?: return null
        return synchronized(memoryCache) { memoryCache.get(key) }
    }

    fun putCapturedIcon(
        context: Context,
        url: String?,
        icon: Bitmap?,
        persistToDisk: Boolean,
    ) {
        if (icon == null || icon.isRecycled || icon.width <= 0 || icon.height <= 0) return
        val key = buildCacheKey(url) ?: return
        val normalized = normalizeBitmapSize(icon)
        synchronized(memoryCache) {
            memoryCache.put(key, normalized)
        }
        negativeCacheTimestamps.remove(key)
        _cacheVersion.value = _cacheVersion.value + 1

        if (persistToDisk && !isOnionHostOrUrl(url)) {
            val appCtx = context.applicationContext
            scope.launch {
                writeDiskCache(appCtx, key, normalized)
            }
        }
    }

    suspend fun getOrFetchBitmap(
        context: Context,
        url: String?,
        allowNetworkFetch: Boolean,
    ): Bitmap? {
        val cleanUrl = url?.trim().orEmpty()
        if (cleanUrl.isBlank()) return null
        val key = buildCacheKey(cleanUrl) ?: return null
        val isOnion = isOnionHostOrUrl(cleanUrl)

        synchronized(memoryCache) {
            memoryCache.get(key)?.takeIf { !it.isRecycled }
        }?.let { return it }

        val appCtx = context.applicationContext
        if (!isOnion) {
            withContext(Dispatchers.IO) {
                readDiskCache(appCtx, key)
            }?.let { diskBmp ->
                synchronized(memoryCache) {
                    memoryCache.put(key, diskBmp)
                }
                return diskBmp
            }
        }

        if (!allowNetworkFetch || isOnion) {
            return null
        }

        val lastFailAt = negativeCacheTimestamps[key]
        if (lastFailAt != null && (System.currentTimeMillis() - lastFailAt) < NEGATIVE_CACHE_COOLDOWN_MS) {
            return null
        }

        val newDeferred = CompletableDeferred<Bitmap?>()
        val existing = inFlight.putIfAbsent(key, newDeferred)
        if (existing != null) {
            return existing.await()
        }

        return try {
            val fetched = withContext(Dispatchers.IO) {
                fetchFaviconFromNetwork(cleanUrl)
            }
            if (fetched != null) {
                val sized = normalizeBitmapSize(fetched)
                synchronized(memoryCache) {
                    memoryCache.put(key, sized)
                }
                negativeCacheTimestamps.remove(key)
                withContext(Dispatchers.IO) {
                    writeDiskCache(appCtx, key, sized)
                }
                newDeferred.complete(sized)
                sized
            } else {
                negativeCacheTimestamps[key] = System.currentTimeMillis()
                newDeferred.complete(null)
                null
            }
        } catch (_: Throwable) {
            negativeCacheTimestamps[key] = System.currentTimeMillis()
            newDeferred.complete(null)
            null
        } finally {
            inFlight.remove(key, newDeferred)
        }
    }

    private fun fetchFaviconFromNetwork(url: String): Bitmap? {
        val candidates = buildFaviconCandidateUrls(url, allowNetworkFetch = true)
        var lowResFallback: Bitmap? = null

        for (candidate in candidates) {
            val bmp = downloadAndDecodeBitmap(candidate) ?: continue
            if (bmp.width >= 32 && bmp.height >= 32) {
                return bmp
            }
            if (lowResFallback == null || bmp.width > lowResFallback.width) {
                lowResFallback = bmp
            }
        }

        val schemeHost = runCatching {
            val normalized = if (url.contains("://")) url else "https://$url"
            val u = URI(normalized)
            "https://${u.host}"
        }.getOrNull()

        if (schemeHost != null && !isOnionHostOrUrl(schemeHost)) {
            val htmlHead = fetchHtmlHeadSnippet(schemeHost)
            if (!htmlHead.isNullOrBlank()) {
                val discovered = parseHtmlIconUrls(htmlHead, schemeHost)
                for (iconUrl in discovered.take(4)) {
                    val bmp = downloadAndDecodeBitmap(iconUrl) ?: continue
                    if (bmp.width >= 24 && bmp.height >= 24) {
                        return bmp
                    }
                    if (lowResFallback == null) {
                        lowResFallback = bmp
                    }
                }
            }
        }

        return lowResFallback
    }

    private fun downloadAndDecodeBitmap(targetUrl: String): Bitmap? {
        if (isOnionHostOrUrl(targetUrl)) return null
        return runCatching {
            val request = Request.Builder()
                .url(targetUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36",
                )
                .header("Accept", "image/avif,image/webp,image/png,image/x-icon,image/*;q=0.8")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val contentType = response.header("Content-Type")?.lowercase(Locale.US).orEmpty()
                if (contentType.contains("text/html") || contentType.contains("svg")) {
                    return@use null
                }
                val body = response.body ?: return@use null
                val bytes = readLimitedBytes(body.byteStream(), MAX_ICON_BYTES) ?: return@use null
                decodeIconBytes(bytes)
            }
        }.getOrNull()
    }

    private fun fetchHtmlHeadSnippet(pageUrl: String): String? {
        return runCatching {
            val request = Request.Builder()
                .url(pageUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36",
                )
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val contentType = response.header("Content-Type")?.lowercase(Locale.US).orEmpty()
                if (contentType.isNotEmpty() && !contentType.contains("html")) return@use null
                val body = response.body ?: return@use null
                val buffer = ByteArray(MAX_HTML_SNIFF_BYTES)
                val stream = body.byteStream()
                var total = 0
                while (total < buffer.size) {
                    val read = stream.read(buffer, total, buffer.size - total)
                    if (read <= 0) break
                    total += read
                }
                if (total <= 0) null else String(buffer, 0, total, StandardCharsets.UTF_8)
            }
        }.getOrNull()
    }

    private fun readLimitedBytes(stream: java.io.InputStream, maxBytes: Int): ByteArray? {
        val output = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            total += n
            if (total > maxBytes) return null
            output.write(buf, 0, n)
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }

    internal fun decodeIconBytes(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val direct = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
        if (direct != null && direct.width > 0 && direct.height > 0) {
            return direct
        }
        // Fallback: extract embedded PNG payload from .ico container if present
        val pngHeader = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        val startIdx = indexOfSequence(bytes, pngHeader)
        if (startIdx >= 0) {
            val iendMarker = byteArrayOf(0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())
            val endIdx = indexOfSequence(bytes, iendMarker, startIdx + pngHeader.size)
            val sliceEnd = if (endIdx >= 0) endIdx + iendMarker.size else bytes.size
            if (sliceEnd > startIdx) {
                val pngSlice = bytes.copyOfRange(startIdx, sliceEnd)
                val extracted = runCatching {
                    BitmapFactory.decodeByteArray(pngSlice, 0, pngSlice.size)
                }.getOrNull()
                if (extracted != null && extracted.width > 0 && extracted.height > 0) {
                    return extracted
                }
            }
        }
        return null
    }

    private fun indexOfSequence(haystack: ByteArray, needle: ByteArray, fromIndex: Int = 0): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        val maxStart = haystack.size - needle.size
        for (i in fromIndex.coerceAtLeast(0)..maxStart) {
            var matched = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
        }
        return -1
    }

    private fun normalizeBitmapSize(source: Bitmap): Bitmap {
        val maxDim = maxOf(source.width, source.height)
        if (maxDim <= MAX_BITMAP_DIM_PX) return source
        val scale = MAX_BITMAP_DIM_PX.toFloat() / maxDim.toFloat()
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    private fun diskCacheFile(context: Context, key: String): File {
        val dir = File(context.cacheDir, DISK_CACHE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, buildDiskFileName(key))
    }

    private fun readDiskCache(context: Context, key: String): Bitmap? {
        return runCatching {
            val file = diskCacheFile(context, key)
            if (!file.exists() || file.length() == 0L) return@runCatching null
            BitmapFactory.decodeFile(file.absolutePath)?.takeIf { it.width > 0 && it.height > 0 }
        }.getOrNull()
    }

    private fun writeDiskCache(context: Context, key: String, bitmap: Bitmap) {
        runCatching {
            val file = diskCacheFile(context, key)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }
}

package com.example.browser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.example.data.DownloadEntity
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

data class DecodedDataUri(
    val mimeType: String,
    val bytes: ByteArray,
)

data class SavedDownloadResult(
    val success: Boolean,
    val savedFileName: String,
    val localUri: String = "",
    val sizeBytes: Long = 0L,
    val errorMessage: String? = null,
)

object DownloadFileHelper {

    private val UUID_LIKE_REGEX = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(\\.[a-z0-9]{1,8})?$",
        RegexOption.IGNORE_CASE,
    )
    private val UNSAFE_FILE_CHARS_REGEX = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+")
    private val DISPOSITION_EXTENDED_REGEX = Regex("filename\\*\\s*=\\s*(?:UTF-8''|utf-8'')([^;\\r\\n]+)")
    private val DISPOSITION_PLAIN_REGEX = Regex("filename\\s*=\\s*\"?([^;\"]+)\"?")

    private val MIME_TO_EXT = mapOf(
        "application/vnd.android.package-archive" to "apk",
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/x-zip-compressed" to "zip",
        "application/gzip" to "gz",
        "application/x-tar" to "tar",
        "application/json" to "json",
        "application/xml" to "xml",
        "application/javascript" to "js",
        "text/plain" to "txt",
        "text/html" to "html",
        "text/css" to "css",
        "text/csv" to "csv",
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/svg+xml" to "svg",
        "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a",
        "audio/ogg" to "ogg",
        "audio/wav" to "wav",
        "video/mp4" to "mp4",
        "video/webm" to "webm",
        "video/x-matroska" to "mkv",
    )

    fun isUuidLikeName(name: String?): Boolean {
        val clean = name?.trim().orEmpty()
        if (clean.isBlank()) return false
        return UUID_LIKE_REGEX.matches(clean)
    }

    fun extensionForMimeType(mimeType: String?): String {
        val normalized = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        if (normalized.isBlank() || normalized == "application/octet-stream") return ""
        return MIME_TO_EXT[normalized]
            ?: runCatching { MimeTypeMap.getSingleton().getExtensionFromMimeType(normalized) }.getOrNull().orEmpty()
    }

    fun extractFileNameFromContentDisposition(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        DISPOSITION_EXTENDED_REGEX.find(contentDisposition)?.groupValues?.getOrNull(1)?.let { encoded ->
            val decoded = runCatching { URLDecoder.decode(encoded.trim(), "UTF-8") }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }
        DISPOSITION_PLAIN_REGEX.find(contentDisposition)?.groupValues?.getOrNull(1)?.let { plain ->
            val trimmed = plain.trim().trim('"', '\'')
            if (trimmed.isNotBlank()) return trimmed
        }
        return null
    }

    fun sanitizeFileName(raw: String): String {
        val cleaned = raw
            .replace(UNSAFE_FILE_CHARS_REGEX, "_")
            .trim()
            .trimStart('.')
            .replace(Regex("\\s+"), " ")
        val bounded = if (cleaned.length > 100) {
            val dotIdx = cleaned.lastIndexOf('.')
            if (dotIdx in 1..cleaned.length - 2 && cleaned.length - dotIdx <= 10) {
                val ext = cleaned.substring(dotIdx)
                cleaned.substring(0, (100 - ext.length).coerceAtLeast(1)) + ext
            } else {
                cleaned.take(100)
            }
        } else {
            cleaned
        }
        return bounded.ifBlank { "download_file" }
    }

    fun resolveDownloadFileName(
        rawGuessedName: String?,
        url: String,
        contentDisposition: String?,
        mimeType: String?,
    ): String {
        val fromDisposition = extractFileNameFromContentDisposition(contentDisposition)
        val candidateRaw = listOfNotNull(
            fromDisposition?.takeIf { it.isNotBlank() },
            rawGuessedName?.takeIf { it.isNotBlank() },
        ).firstOrNull().orEmpty()

        val stripped = candidateRaw.substringAfterLast('/').substringAfterLast('\\').trim()
        val isBlobOrData = url.startsWith("blob:", ignoreCase = true) || url.startsWith("data:", ignoreCase = true)
        val extFromMime = extensionForMimeType(mimeType)
        val isApk = TrackerAndUrlEngine.isApkAsset(url, stripped, mimeType)

        var baseName = sanitizeFileName(
            if (stripped.isBlank() || (isBlobOrData && isUuidLikeName(stripped)) || isUuidLikeName(stripped)) {
                "download_export"
            } else {
                stripped
            },
        )

        val currentExt = baseName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (isApk && currentExt != "apk") {
            baseName = if (currentExt == "bin" || currentExt.isBlank()) {
                baseName.substringBeforeLast('.') + ".apk"
            } else {
                "$baseName.apk"
            }
        } else if (extFromMime.isNotBlank()) {
            val looksGeneric = currentExt.isBlank() ||
                currentExt == "bin" ||
                (isBlobOrData && currentExt.length > 8) ||
                baseName.none { it == '.' }
            if (looksGeneric) {
                val stem = if (currentExt == "bin" && baseName.contains('.')) {
                    baseName.substringBeforeLast('.')
                } else {
                    baseName
                }
                baseName = "$stem.$extFromMime"
            }
        }
        return sanitizeFileName(baseName)
    }

    fun decodeDataUri(dataUri: String): DecodedDataUri? {
        if (!dataUri.startsWith("data:", ignoreCase = true)) return null
        val commaIndex = dataUri.indexOf(',')
        if (commaIndex < 5) return null
        val header = dataUri.substring(5, commaIndex)
        val payload = dataUri.substring(commaIndex + 1)
        val parts = header.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val isBase64 = parts.any { it.equals("base64", ignoreCase = true) }
        val mime = parts.firstOrNull { !it.equals("base64", ignoreCase = true) && it.contains('/') }
            ?: "application/octet-stream"
        val bytes = runCatching {
            if (isBase64) {
                val cleanB64 = payload.replace("\\s".toRegex(), "")
                android.util.Base64.decode(cleanB64, android.util.Base64.DEFAULT)
            } else {
                URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
            }
        }.getOrNull() ?: return null
        return DecodedDataUri(mimeType = mime, bytes = bytes)
    }

    fun formatByteSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> "${(bytes / 1024.0).toInt().coerceAtLeast(1)} KB"
            bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun describeFailureReason(download: DownloadEntity, recordedReason: String?): String? {
        if (download.status != "FAILED" && download.status != "CANCELLED") return null
        if (!recordedReason.isNullOrBlank()) return recordedReason
        if (download.status == "CANCELLED") {
            return "Download cancelled before starting."
        }
        return when {
            download.url.startsWith("blob:", ignoreCase = true) || isUuidLikeName(download.fileName) ->
                "Temporary browser blob link failed or expired. Re-open the source site to export it again."
            download.isOnionSource ->
                "Tor download could not complete cleanly. Check your Tor circuit and tap Retry."
            else ->
                "Download failed due to connection or server restriction. Tap Retry to download directly."
        }
    }

    fun saveStreamToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        inputStream: InputStream,
    ): SavedDownloadResult {
        val safeName = sanitizeFileName(fileName)
        val safeMime = mimeType.substringBefore(';').trim().ifBlank { "application/octet-stream" }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, safeMime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create MediaStore entry")
                var totalBytes = 0L
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = inputStream.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            totalBytes += read
                        }
                        out.flush()
                    } ?: error("Could not open output stream")

                    val finalizeValues = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(uri, finalizeValues, null, null)
                    SavedDownloadResult(
                        success = true,
                        savedFileName = safeName,
                        localUri = uri.toString(),
                        sizeBytes = totalBytes,
                    )
                } catch (e: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
            }.getOrElse { err ->
                SavedDownloadResult(
                    success = false,
                    savedFileName = safeName,
                    errorMessage = err.message ?: "Failed to save file to Downloads",
                )
            }
        } else {
            runCatching {
                val appDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                if (!appDownloads.exists()) appDownloads.mkdirs()
                val targetFile = uniqueTargetFile(appDownloads, safeName)
                var totalBytes = 0L
                targetFile.outputStream().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = inputStream.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        totalBytes += read
                    }
                    out.flush()
                }
                SavedDownloadResult(
                    success = true,
                    savedFileName = targetFile.name,
                    localUri = Uri.fromFile(targetFile).toString(),
                    sizeBytes = totalBytes,
                )
            }.getOrElse { err ->
                SavedDownloadResult(
                    success = false,
                    savedFileName = safeName,
                    errorMessage = err.message ?: "Failed to save file",
                )
            }
        }
    }

    private fun uniqueTargetFile(dir: File, desiredName: String): File {
        var candidate = File(dir, desiredName)
        if (!candidate.exists()) return candidate
        val stem = desiredName.substringBeforeLast('.', desiredName)
        val ext = desiredName.substringAfterLast('.', "")
        var index = 1
        while (candidate.exists() && index < 100) {
            val numbered = if (ext.isNotBlank() && desiredName.contains('.')) {
                "$stem ($index).$ext"
            } else {
                "$stem ($index)"
            }
            candidate = File(dir, numbered)
            index++
        }
        return candidate
    }

    fun downloadHttpDirectToDownloads(
        context: Context,
        url: String,
        preferredFileName: String,
        mimeType: String,
        userAgent: String,
        cookieHeader: String? = null,
        refererUrl: String? = null,
        useTorProxy: Boolean = false,
        torHost: String = "127.0.0.1",
        torPort: Int = 9050,
    ): SavedDownloadResult {
        return runCatching {
            val proxy = if (useTorProxy) {
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(torHost, torPort))
            } else {
                Proxy.NO_PROXY
            }
            var currentUrl = url
            var redirects = 0
            var connection: HttpURLConnection? = null

            while (redirects < 6) {
                val parsedUrl = URL(currentUrl)
                val conn = (parsedUrl.openConnection(proxy) as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 45_000
                    setRequestProperty("User-Agent", userAgent.ifBlank { "Mozilla/5.0 (Linux; Android 14; Mobile)" })
                    if (!cookieHeader.isNullOrBlank()) {
                        setRequestProperty("Cookie", cookieHeader)
                    }
                    if (!refererUrl.isNullOrBlank()) {
                        setRequestProperty("Referer", refererUrl)
                    }
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location").orEmpty()
                    conn.disconnect()
                    if (location.isBlank()) error("Empty redirect location ($code)")
                    currentUrl = URL(parsedUrl, location).toString()
                    redirects++
                    continue
                }
                connection = conn
                break
            }

            val activeConn = connection ?: error("Too many redirects")
            try {
                val code = activeConn.responseCode
                if (code !in 200..299) {
                    error("Server returned HTTP $code")
                }
                val respMime = activeConn.contentType?.substringBefore(';')?.trim()?.ifBlank { null } ?: mimeType
                val disposition = activeConn.getHeaderField("Content-Disposition")
                val resolvedName = resolveDownloadFileName(
                    rawGuessedName = preferredFileName,
                    url = currentUrl,
                    contentDisposition = disposition,
                    mimeType = respMime,
                )
                activeConn.inputStream.use { stream ->
                    saveStreamToDownloads(
                        context = context,
                        fileName = resolvedName,
                        mimeType = respMime,
                        inputStream = stream,
                    )
                }
            } finally {
                activeConn.disconnect()
            }
        }.getOrElse { err ->
            SavedDownloadResult(
                success = false,
                savedFileName = sanitizeFileName(preferredFileName),
                errorMessage = err.message ?: "Direct HTTP download failed",
            )
        }
    }
}

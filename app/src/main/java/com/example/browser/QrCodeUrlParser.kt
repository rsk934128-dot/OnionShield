package com.example.browser

sealed class QrScanOutcome {
    data object Empty : QrScanOutcome()
    data class NavigableUrl(val targetInput: String) : QrScanOutcome()
    data class UnsafeScheme(val scheme: String) : QrScanOutcome()
    data class NonUrlText(val text: String) : QrScanOutcome()
}

object QrCodeUrlParser {

    private val unsafeSchemes = setOf(
        "javascript",
        "file",
        "data",
        "vbscript",
        "content",
        "about",
    )

    private val nonWebStructuredPrefixes = listOf(
        "WIFI:",
        "BEGIN:VCARD",
        "BEGIN:VEVENT",
        "MATMSG:",
        "SMSTO:",
        "mailto:",
        "tel:",
        "sms:",
        "geo:",
        "bitcoin:",
    )

    private val domainCandidateRegex = Regex(
        """^(localhost|127\.0\.0\.1|([a-zA-Z0-9-]+\.)+[a-zA-Z0-9-]{2,})(:\d+)?(/.*)?$""",
    )

    fun parseQrPayload(rawPayload: String?): QrScanOutcome {
        val trimmed = rawPayload?.trim().orEmpty()
        if (trimmed.isEmpty()) return QrScanOutcome.Empty

        // Support MEBKM / bookmark QR format: MEBKM:TITLE:...;URL:https://...;;
        val normalizedCandidate = extractBookmarkQrUrl(trimmed) ?: trimmed
        val lower = normalizedCandidate.lowercase()

        if (lower.startsWith("intent:")) {
            val fallback = TrackerAndUrlEngine.extractIntentFallbackUrl(normalizedCandidate)
            return if (fallback != null) {
                QrScanOutcome.NavigableUrl(fallback)
            } else {
                QrScanOutcome.UnsafeScheme("intent")
            }
        }

        val schemeMatch = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*):""").find(normalizedCandidate)
        if (schemeMatch != null) {
            val scheme = schemeMatch.groupValues[1].lowercase()
            if (scheme == "http" || scheme == "https") {
                val extracted = TrackerAndUrlEngine.extractFirstHttpUrl(normalizedCandidate)
                    ?: normalizedCandidate
                return QrScanOutcome.NavigableUrl(extracted)
            }
            if (scheme in unsafeSchemes) {
                return QrScanOutcome.UnsafeScheme(scheme)
            }
        }

        if (nonWebStructuredPrefixes.any { normalizedCandidate.startsWith(it, ignoreCase = true) }) {
            return QrScanOutcome.NonUrlText(trimmed)
        }

        val embeddedHttp = TrackerAndUrlEngine.extractFirstHttpUrl(normalizedCandidate)
        if (embeddedHttp != null) {
            return QrScanOutcome.NavigableUrl(embeddedHttp)
        }

        if (!normalizedCandidate.contains(' ') && domainCandidateRegex.matches(normalizedCandidate)) {
            return QrScanOutcome.NavigableUrl(normalizedCandidate)
        }

        return QrScanOutcome.NonUrlText(trimmed)
    }

    private fun extractBookmarkQrUrl(raw: String): String? {
        if (raw.startsWith("MEBKM:", ignoreCase = true)) {
            val urlMarker = Regex("""URL:([^;]+)""", RegexOption.IGNORE_CASE).find(raw)
            return urlMarker?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (raw.startsWith("URL:", ignoreCase = true)) {
            return raw.substring(4).trim().takeIf { it.isNotEmpty() }
        }
        return null
    }
}

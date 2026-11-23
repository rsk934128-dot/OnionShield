package com.example.browser

enum class TradeDecision {
    BUY,
    SELL,
    HOLD,
}

data class DetectedTradeButton(
    val action: String, // "BUY" or "SELL"
    val label: String,
    val normX: Float,
    val normY: Float,
    val widthRatio: Float = 0f,
    val heightRatio: Float = 0f,
    val enabled: Boolean = true,
)

data class TradeSignalDecision(
    val decision: TradeDecision,
    val confidence: Int,
    val assetName: String,
    val accountMode: String,
    val marketBias: String,
    val reasoning: String,
    val targetLabel: String,
    val normalizedX: Float,
    val normalizedY: Float,
    val capturedAtMs: Long = System.currentTimeMillis(),
)

data class ResolvedClickTarget(
    val normX: Float,
    val normY: Float,
    val source: String,
    val matchedLabel: String,
)

object TradePilotHelper {

    val TRADE_DOM_SCAN_SCRIPT: String = """
        (function() {
            try {
                var vw = window.innerWidth || document.documentElement.clientWidth || 1;
                var vh = window.innerHeight || document.documentElement.clientHeight || 1;
                var candidates = document.querySelectorAll('button, [role="button"], a, div, span');
                var results = [];
                var seen = [];
                for (var i = 0; i < candidates.length && results.length < 12; i++) {
                    var el = candidates[i];
                    if (!el || !el.getBoundingClientRect) continue;
                    var rawText = (el.innerText || el.textContent || el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim();
                    var cls = (el.className && typeof el.className === 'string') ? el.className : '';
                    var upperText = rawText.toUpperCase();
                    var upperCls = cls.toUpperCase();
                    if (!upperText && !upperCls) continue;
                    if (upperText.length > 40) continue;

                    var isBuyText = /\b(BUY|CALL|UP|HIGHER|LONG)\b/.test(upperText);
                    var isSellText = /\b(SELL|PUT|DOWN|LOWER|SHORT)\b/.test(upperText);
                    var isBuyCls = !isBuyText && !isSellText && /\b(BUY|CALL|UP-BTN)\b/.test(upperCls);
                    var isSellCls = !isBuyText && !isSellText && /\b(SELL|PUT|DOWN-BTN)\b/.test(upperCls);
                    if (!(isBuyText ^ isSellText) && !(isBuyCls ^ isSellCls)) continue;

                    var action = (isBuyText || isBuyCls) ? 'BUY' : 'SELL';
                    var target = el.closest('button, [role="button"], a, [class*="btn"], [class*="button"], [class*="deal"]') || el;
                    var rect = target.getBoundingClientRect();
                    if (!rect || rect.width <= 0 || rect.height <= 0) continue;
                    var wRatio = rect.width / vw;
                    var hRatio = rect.height / vh;
                    if (wRatio < 0.06 || hRatio < 0.025 || wRatio > 0.65 || hRatio > 0.28) continue;

                    var cx = (rect.left + rect.width * 0.5) / vw;
                    var cy = (rect.top + rect.height * 0.5) / vh;
                    if (cx < 0.02 || cx > 0.98 || cy < 0.05 || cy > 0.98) continue;

                    var style = window.getComputedStyle ? window.getComputedStyle(target) : null;
                    if (style && (style.display === 'none' || style.visibility === 'hidden' || parseFloat(style.opacity || '1') < 0.1)) continue;
                    var disabled = !!(target.disabled || target.getAttribute('aria-disabled') === 'true' || /disabled/.test((target.className || '').toLowerCase()));

                    var key = action + ':' + Math.round(cx * 20) + ':' + Math.round(cy * 20);
                    if (seen.indexOf(key) !== -1) continue;
                    seen.push(key);

                    results.push({
                        action: action,
                        label: rawText.slice(0, 32) || action,
                        normX: Number(cx.toFixed(4)),
                        normY: Number(cy.toFixed(4)),
                        widthRatio: Number(wRatio.toFixed(4)),
                        heightRatio: Number(hRatio.toFixed(4)),
                        enabled: !disabled
                    });
                }
                return JSON.stringify(results);
            } catch (e) {
                return '[]';
            }
        })();
    """.trimIndent()

    fun unwrapJsEvalString(rawJsResult: String?): String {
        val trimmed = rawJsResult?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return ""
        return if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2) {
            unescapeJsonString(trimmed.substring(1, trimmed.length - 1))
        } else {
            trimmed
        }
    }

    internal fun unescapeJsonString(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        if (i + 5 < raw.length) {
                            val code = raw.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) {
                                out.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        out.append("\\u")
                    }
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    internal fun parseFlatJsonObject(jsonObjectText: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val s = jsonObjectText.trim()
        var idx = 0
        val n = s.length
        while (idx < n && s[idx] != '{') idx++
        if (idx >= n) return result
        idx++ // skip '{'

        fun skipWsAndComma() {
            while (idx < n && (s[idx].isWhitespace() || s[idx] == ',')) idx++
        }

        fun readQuoted(): String {
            idx++ // skip opening '"'
            val sb = StringBuilder()
            while (idx < n) {
                val ch = s[idx]
                if (ch == '\\' && idx + 1 < n) {
                    sb.append(ch).append(s[idx + 1])
                    idx += 2
                } else if (ch == '"') {
                    idx++
                    break
                } else {
                    sb.append(ch)
                    idx++
                }
            }
            return unescapeJsonString(sb.toString())
        }

        while (idx < n) {
            skipWsAndComma()
            if (idx >= n || s[idx] == '}') break
            if (s[idx] != '"') {
                idx++
                continue
            }
            val key = readQuoted()
            while (idx < n && (s[idx].isWhitespace() || s[idx] == ':')) idx++
            if (idx >= n) break
            val value = if (s[idx] == '"') {
                readQuoted()
            } else {
                val start = idx
                while (idx < n && s[idx] != ',' && s[idx] != '}') idx++
                s.substring(start, idx).trim()
            }
            result[key] = value
        }
        return result
    }

    fun parseDetectedButtonsJson(rawJsResult: String?): List<DetectedTradeButton> {
        val unwrapped = unwrapJsEvalString(rawJsResult).trim()
        if (!unwrapped.startsWith("[")) return emptyList()
        val results = mutableListOf<DetectedTradeButton>()
        var inString = false
        var escaped = false
        var objStart = -1
        var depth = 0
        for (i in unwrapped.indices) {
            val ch = unwrapped[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            if (ch == '{') {
                if (depth == 0) objStart = i
                depth++
            } else if (ch == '}') {
                depth--
                if (depth == 0 && objStart >= 0) {
                    val map = parseFlatJsonObject(unwrapped.substring(objStart, i + 1))
                    val action = map["action"]?.uppercase().orEmpty()
                    val normX = map["normX"]?.toFloatOrNull() ?: -1f
                    val normY = map["normY"]?.toFloatOrNull() ?: -1f
                    if ((action == "BUY" || action == "SELL") &&
                        normX in 0.01f..0.99f &&
                        normY in 0.01f..0.99f
                    ) {
                        results.add(
                            DetectedTradeButton(
                                action = action,
                                label = map["label"].orEmpty().ifBlank { action },
                                normX = normX,
                                normY = normY,
                                widthRatio = (map["widthRatio"]?.toFloatOrNull() ?: 0f).coerceIn(0f, 1f),
                                heightRatio = (map["heightRatio"]?.toFloatOrNull() ?: 0f).coerceIn(0f, 1f),
                                enabled = map["enabled"]?.toBooleanStrictOrNull() ?: true,
                            ),
                        )
                    }
                    objStart = -1
                }
            }
        }
        return results
    }

    fun formatControlsSummary(buttons: List<DetectedTradeButton>): String {
        if (buttons.isEmpty()) {
            return "No HTML Buy/Sell buttons detected via DOM (controls may be rendered inside canvas or Shadow DOM)."
        }
        return buttons.joinToString("\n") { btn ->
            val pctX = (btn.normX * 100).toInt()
            val pctY = (btn.normY * 100).toInt()
            "- ${btn.action} button (\"${btn.label}\") at x=${pctX}%, y=${pctY}%, enabled=${btn.enabled}"
        }
    }

    fun resolveClickTarget(
        decision: TradeDecision,
        aiNormX: Float,
        aiNormY: Float,
        domButtons: List<DetectedTradeButton>,
    ): ResolvedClickTarget? {
        if (decision == TradeDecision.HOLD) return null
        val desiredAction = decision.name
        val domMatch = domButtons
            .filter { it.enabled && it.action.equals(desiredAction, ignoreCase = true) }
            .sortedWith(
                compareByDescending<DetectedTradeButton> { it.normY >= 0.45f }
                    .thenByDescending { it.widthRatio * it.heightRatio },
            )
            .firstOrNull()

        if (domMatch != null) {
            return ResolvedClickTarget(
                normX = domMatch.normX.coerceIn(0.03f, 0.97f),
                normY = domMatch.normY.coerceIn(0.05f, 0.97f),
                source = "DOM",
                matchedLabel = domMatch.label,
            )
        }

        if (aiNormX in 0.02f..0.98f && aiNormY in 0.05f..0.98f) {
            return ResolvedClickTarget(
                normX = aiNormX,
                normY = aiNormY,
                source = "VISION",
                matchedLabel = desiredAction,
            )
        }
        return null
    }

    fun shouldAutoExecuteClick(
        decision: TradeSignalDecision,
        minConfidencePercent: Int,
    ): Boolean = decision.decision != TradeDecision.HOLD &&
        decision.confidence >= minConfidencePercent.coerceIn(1, 100)
}

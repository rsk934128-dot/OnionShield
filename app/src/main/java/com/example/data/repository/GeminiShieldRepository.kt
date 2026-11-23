package com.example.data.repository

import android.graphics.Bitmap
import com.example.browser.TradeDecision
import com.example.browser.TradeSignalDecision
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

enum class ChatRole {
    USER,
    MODEL,
}

data class GeminiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String,
    val promptText: String = text,
    val pageUrl: String? = null,
    val audit: PagePrivacyAudit? = null,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class PageAiContext(
    val url: String,
    val title: String,
    val browserModeLabel: String,
    val trackersBlockedCount: Int,
    val scriptsBlockedCount: Int,
    val pageTextSnippet: String,
)

data class PagePrivacyAudit(
    val riskLevel: String,
    val summary: String,
    val privacySignals: List<String>,
    val recommendedAction: String,
)

open class GeminiShieldRepository {

    private val chatModel by lazy {
        createChatModel(MODEL_NAME)
    }

    private val fallbackChatModel by lazy {
        createChatModel(FALLBACK_MODEL_NAME)
    }

    private val auditModel by lazy {
        createAuditModel(MODEL_NAME)
    }

    private val fallbackAuditModel by lazy {
        createAuditModel(FALLBACK_MODEL_NAME)
    }

    private val tradeVisionModel by lazy {
        createTradeVisionModel(MODEL_NAME)
    }

    private val fallbackTradeVisionModel by lazy {
        createTradeVisionModel(FALLBACK_MODEL_NAME)
    }

    private fun createChatModel(modelName: String) =
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = modelName,
            systemInstruction = content {
                text(
                    "You are OnionShield AI, a concise security, privacy, and research assistant " +
                        "built inside the OnionShield privacy browser. Explain web privacy, Tor " +
                        "networking, trackers, phishing signals, and web page summaries accurately and concisely.",
                )
            },
        )

    private fun createAuditModel(modelName: String) =
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = PAGE_AUDIT_SCHEMA
            },
        )

    private fun createTradeVisionModel(modelName: String) =
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = TRADE_VISION_SCHEMA
            },
            systemInstruction = content {
                text(
                    "You are a decisive short-term technical price-action and UI vision analyst. " +
                        "Inspect the live chart screenshot carefully: evaluate the latest price leg leading " +
                        "to the active price dot/candle, recent swing bounce or rejection, higher-low vs " +
                        "lower-high structure, and slope momentum. Choose BUY when recent momentum or bounce " +
                        "favors upside, or SELL when momentum or rejection favors downside, with a realistic " +
                        "directional confidence (50-95) and exact button coordinates. Return HOLD ONLY when " +
                        "no trading chart or Buy/Sell buttons are visible or price is completely flat.",
                )
            },
        )

    open fun sendChatMessageStream(
        history: List<GeminiChatMessage>,
        userPrompt: String,
        pageContext: PageAiContext?,
    ): Flow<String> = flow {
        val sdkHistory = buildSdkHistory(history)
        val fullPrompt = buildContextualPrompt(userPrompt, pageContext)

        // Try: (1) primary stream (15s), (2) lite stream (18s), (3) lite unary HTTP fallback (18s)
        val plan = listOf(
            Triple(chatModel, true, PRIMARY_ATTEMPT_TIMEOUT_MS),
            Triple(fallbackChatModel, true, FALLBACK_ATTEMPT_TIMEOUT_MS),
            Triple(fallbackChatModel, false, FALLBACK_ATTEMPT_TIMEOUT_MS),
        )
        var lastError: Exception? = null

        for ((attemptIdx, step) in plan.withIndex()) {
            val (candidate, useStream, attemptTimeoutMs) = step
            var emittedAny = false
            try {
                withTimeout(attemptTimeoutMs) {
                    val chat = candidate.startChat(history = sdkHistory)
                    if (useStream) {
                        chat.sendMessageStream(fullPrompt).collect { chunk ->
                            val piece = chunk.text
                            if (!piece.isNullOrEmpty()) {
                                emittedAny = true
                                emit(piece)
                            }
                        }
                    } else {
                        val unary = chat.sendMessage(fullPrompt)
                        val text = unary.text
                        if (!text.isNullOrEmpty()) {
                            emittedAny = true
                            emit(text)
                        }
                    }
                }
                if (emittedAny) {
                    return@flow
                }
                if (attemptIdx < plan.lastIndex) {
                    delay(FALLBACK_DELAY_MS)
                }
            } catch (e: TimeoutCancellationException) {
                // Propagate outer coroutine cancellation, otherwise treat per-attempt hang as fallback signal.
                if (!currentCoroutineContext().isActive) throw e
                if (emittedAny) return@flow
                lastError = e
                if (attemptIdx == plan.lastIndex) {
                    throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (emittedAny) return@flow
                if (!isTransientHighDemandError(e) || attemptIdx == plan.lastIndex) {
                    throw e
                }
                lastError = e
                delay(FALLBACK_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("Gemini returned an empty response.")
    }

    fun streamPrompt(prompt: String, pageContext: PageAiContext?): Flow<String> =
        sendChatMessageStream(history = emptyList(), userPrompt = prompt, pageContext = pageContext)

    open suspend fun runStructuredPrivacyAudit(pageContext: PageAiContext): PagePrivacyAudit {
        val prompt = buildString {
            appendLine("Analyze this web page context for user privacy, phishing/scam signals, and security posture:")
            appendLine("URL: ${pageContext.url}")
            appendLine("Title: ${pageContext.title}")
            appendLine("Browser Mode: ${pageContext.browserModeLabel}")
            appendLine("Trackers Blocked: ${pageContext.trackersBlockedCount}")
            appendLine("Scripts Blocked: ${pageContext.scriptsBlockedCount}")
            if (pageContext.pageTextSnippet.isNotBlank()) {
                appendLine("Visible Content Excerpt:")
                appendLine(pageContext.pageTextSnippet.take(3500))
            }
        }
        val plan = listOf(
            auditModel to PRIMARY_ATTEMPT_TIMEOUT_MS,
            fallbackAuditModel to 25_000L,
        )
        var lastError: Exception? = null

        for ((attemptIdx, step) in plan.withIndex()) {
            val (candidate, attemptTimeoutMs) = step
            try {
                val jsonText = withTimeout(attemptTimeoutMs) {
                    candidate.generateContent(prompt).text
                }
                if (!jsonText.isNullOrBlank()) {
                    return parsePagePrivacyAuditJson(jsonText)
                }
                if (attemptIdx < plan.lastIndex) {
                    delay(FALLBACK_DELAY_MS)
                }
            } catch (e: TimeoutCancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                lastError = e
                if (attemptIdx == plan.lastIndex) {
                    throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isTransientHighDemandError(e) || attemptIdx == plan.lastIndex) {
                    throw e
                }
                lastError = e
                delay(FALLBACK_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("Gemini returned an empty audit response.")
    }

    open suspend fun analyzeScreenForTradeSignal(
        screenshot: Bitmap,
        pageUrl: String,
        domControlsSummary: String,
    ): TradeSignalDecision {
        val promptText = buildTradeAnalysisPrompt(pageUrl = pageUrl, domControlsSummary = domControlsSummary)
        val multimodalInput = content {
            image(screenshot)
            text(promptText)
        }
        val plan = listOf(
            tradeVisionModel to 20_000L,
            fallbackTradeVisionModel to 25_000L,
        )
        var lastError: Exception? = null

        for ((attemptIdx, step) in plan.withIndex()) {
            val (candidate, attemptTimeoutMs) = step
            try {
                val jsonText = withTimeout(attemptTimeoutMs) {
                    candidate.generateContent(multimodalInput).text
                }
                if (!jsonText.isNullOrBlank()) {
                    return parseTradeDecisionJson(jsonText)
                }
                if (attemptIdx < plan.lastIndex) {
                    delay(FALLBACK_DELAY_MS)
                }
            } catch (e: TimeoutCancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                lastError = e
                if (attemptIdx == plan.lastIndex) {
                    throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isTransientHighDemandError(e) || attemptIdx == plan.lastIndex) {
                    throw e
                }
                lastError = e
                delay(FALLBACK_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("Gemini Vision returned an empty chart analysis response.")
    }

    companion object {
        const val MODEL_NAME = "gemini-3.8-flash"
        const val FALLBACK_MODEL_NAME = "gemini-3.5-flash-lite"
        private const val PRIMARY_ATTEMPT_TIMEOUT_MS = 15_000L
        private const val FALLBACK_ATTEMPT_TIMEOUT_MS = 18_000L
        private const val FALLBACK_DELAY_MS = 300L

        fun isTransientHighDemandError(error: Throwable): Boolean {
            if (error is TimeoutCancellationException) return true
            val className = error::class.java.simpleName.lowercase()
            if (className.contains("serviceunavailable") || className.contains("quotaexceeded")) {
                return true
            }
            val combinedMsg = generateSequence(error) { it.cause }
                .mapNotNull { it.message?.lowercase() }
                .joinToString(" ")
            if (combinedMsg.contains("403") ||
                combinedMsg.contains("app check") ||
                combinedMsg.contains("api key") ||
                combinedMsg.contains("permission denied")
            ) {
                return false
            }
            return combinedMsg.contains("high demand") ||
                combinedMsg.contains("spikes in demand") ||
                combinedMsg.contains("overloaded") ||
                combinedMsg.contains("temporarily unavailable") ||
                combinedMsg.contains("service unavailable") ||
                combinedMsg.contains("resource exhausted") ||
                combinedMsg.contains("rate limit") ||
                combinedMsg.contains("too many requests") ||
                combinedMsg.contains("try again later") ||
                combinedMsg.contains("timed out") ||
                combinedMsg.contains("timeout") ||
                combinedMsg.contains("503") ||
                combinedMsg.contains("504") ||
                combinedMsg.contains("502") ||
                combinedMsg.contains("429")
        }

        val PAGE_AUDIT_SCHEMA = Schema.obj(
            properties = mapOf(
                "riskLevel" to Schema.enumeration(
                    values = listOf("Low", "Medium", "High"),
                    description = "Overall privacy or phishing risk level for the page",
                ),
                "summary" to Schema.string(
                    description = "Concise 2-sentence plain-language overview of what this page does",
                ),
                "privacySignals" to Schema.array(
                    items = Schema.string(),
                    description = "Key privacy, tracker, or authenticity observations",
                ),
                "recommendedAction" to Schema.string(
                    description = "Concrete next step or hardening tip for browsing safely",
                ),
            ),
        )

        val TRADE_VISION_SCHEMA = Schema.obj(
            properties = mapOf(
                "decision" to Schema.enumeration(
                    values = listOf("BUY", "SELL", "HOLD"),
                    description = "Trade action after inspecting chart momentum, price action, and button visibility. Use HOLD if unclear or controls are absent.",
                ),
                "confidence" to Schema.integer(
                    description = "Decision confidence from 0 to 100",
                ),
                "assetName" to Schema.string(
                    description = "Visible asset pair or ticker such as EUR / USD (OTC) or Unknown",
                ),
                "accountMode" to Schema.string(
                    description = "Visible account type on screen: DEMO, REAL, or UNKNOWN",
                ),
                "marketBias" to Schema.string(
                    description = "Short market structure label e.g. Strong Uptrend, Resistance Rejection, Pullback Bounce, Sideways",
                ),
                "reasoning" to Schema.string(
                    description = "Concise 1-2 sentence technical analysis of the visible chart and why BUY, SELL, or HOLD was chosen",
                ),
                "targetLabel" to Schema.string(
                    description = "Exact text on the target button to click such as BUY 86%, SELL 86%, CALL, PUT, or NONE",
                ),
                "normalizedX" to Schema.double(
                    description = "Normalized horizontal center coordinate (0.0 left edge to 1.0 right edge) of the target BUY or SELL button, or 0.5 if HOLD",
                ),
                "normalizedY" to Schema.double(
                    description = "Normalized vertical center coordinate (0.0 top edge to 1.0 bottom edge) of the target BUY or SELL button, or 0.5 if HOLD",
                ),
            ),
        )

        /**
         * Normalizes multi-turn conversation history for [com.google.firebase.ai.GenerativeModel.startChat].
         * Ensures only non-blank text turns are kept and removes any trailing un-answered USER turns so
         * `chat.sendMessageStream(nextUserPrompt)` always produces clean alternating turns.
         */
        fun normalizeHistoryTurns(messages: List<GeminiChatMessage>): List<Pair<ChatRole, String>> {
            val turns = messages.mapNotNull { msg ->
                val turnText = if (msg.role == ChatRole.USER) {
                    msg.promptText.ifBlank { msg.text }.trim()
                } else {
                    msg.audit?.let { audit ->
                        "${audit.summary}\nRisk: ${audit.riskLevel}. Action: ${audit.recommendedAction}"
                    } ?: msg.text.trim()
                }
                if (turnText.isBlank()) null else msg.role to turnText
            }.toMutableList()

            while (turns.isNotEmpty() && turns.last().first == ChatRole.USER) {
                turns.removeAt(turns.lastIndex)
            }
            return turns
        }

        fun buildSdkHistory(messages: List<GeminiChatMessage>): List<Content> =
            normalizeHistoryTurns(messages).map { (role, turnText) ->
                val roleString = if (role == ChatRole.USER) "user" else "model"
                content(role = roleString) { text(turnText) }
            }

        fun buildContextualPrompt(userPrompt: String, pageContext: PageAiContext?): String {
            val cleanPrompt = userPrompt.trim()
            if (pageContext == null || pageContext.url.isBlank()) {
                return cleanPrompt
            }
            return buildString {
                appendLine(cleanPrompt)
                appendLine()
                appendLine("--- Active Browser Tab Context ---")
                appendLine("URL: ${pageContext.url}")
                appendLine("Title: ${pageContext.title}")
                appendLine("Mode: ${pageContext.browserModeLabel}")
                appendLine("Blocked Trackers: ${pageContext.trackersBlockedCount}, Blocked Scripts: ${pageContext.scriptsBlockedCount}")
                if (pageContext.pageTextSnippet.isNotBlank()) {
                    appendLine("Page Text Excerpt: ${pageContext.pageTextSnippet.take(3500)}")
                }
            }
        }

        fun parsePagePrivacyAuditJson(rawJson: String): PagePrivacyAudit {
            val json = JSONObject(rawJson)
            val risk = json.optString("riskLevel", "Medium").ifBlank { "Medium" }
            val summary = json.getString("summary")
            val signalsJson = json.optJSONArray("privacySignals")
            val signals = if (signalsJson != null) {
                (0 until signalsJson.length())
                    .mapNotNull { idx -> signalsJson.optString(idx).takeIf { it.isNotBlank() } }
            } else {
                emptyList()
            }
            val recommended = json.getString("recommendedAction")
            return PagePrivacyAudit(
                riskLevel = risk,
                summary = summary,
                privacySignals = signals,
                recommendedAction = recommended,
            )
        }

        fun buildTradeAnalysisPrompt(pageUrl: String, domControlsSummary: String): String = buildString {
            appendLine("Examine this live trading webpage screenshot carefully and decide BUY, SELL, or HOLD.")
            if (pageUrl.isNotBlank()) {
                appendLine("Page URL: $pageUrl")
            }
            appendLine()
            appendLine("Short-term chart inspection steps:")
            appendLine("1. Identify the asset/pair and visible account mode (REAL or DEMO at the top bar) plus the active price line/candles.")
            appendLine("2. Focus on the latest price swing leading right into the live price marker/dot: upward breakout, support V-bounce, or rising slope favors BUY; downward drop, resistance rejection, or falling slope favors SELL.")
            appendLine("3. Short-term 5s-60s charts naturally oscillate — do NOT choose HOLD just because volatility exists. Give your best BUY or SELL call with an honest confidence score (50-95) whenever a chart and BUY/SELL buttons are present.")
            appendLine("4. Output HOLD only if the chart/Buy-Sell buttons are not visible on screen or the price line is completely flat/frozen.")
            appendLine("5. Return exact normalized (0.0 to 1.0) coordinates of the chosen BUY or SELL button center.")
            if (domControlsSummary.isNotBlank()) {
                appendLine()
                appendLine("Detected DOM control hints (viewport-relative):")
                appendLine(domControlsSummary)
            }
        }

        fun parseTradeDecisionJson(rawJson: String): TradeSignalDecision {
            val map = com.example.browser.TradePilotHelper.parseFlatJsonObject(rawJson)
            val rawDecision = map["decision"]?.trim()?.uppercase().orEmpty().ifBlank { "HOLD" }
            val decision = when (rawDecision) {
                "BUY", "CALL", "UP" -> TradeDecision.BUY
                "SELL", "PUT", "DOWN" -> TradeDecision.SELL
                else -> TradeDecision.HOLD
            }
            val confidence = (map["confidence"]?.toDoubleOrNull()?.toInt() ?: 0).coerceIn(0, 100)
            val assetName = map["assetName"]?.ifBlank { null } ?: "Unknown"
            val accountMode = map["accountMode"]?.uppercase()?.ifBlank { null } ?: "UNKNOWN"
            val marketBias = map["marketBias"]?.ifBlank { null } ?: "Analyzing"
            val reasoning = map["reasoning"]?.ifBlank { null } ?: "No clear chart signal detected."
            val targetLabel = map["targetLabel"]?.ifBlank { null } ?: decision.name
            val rawX = map["normalizedX"]?.toFloatOrNull() ?: 0.5f
            val rawY = map["normalizedY"]?.toFloatOrNull() ?: 0.5f
            val normX = if (rawX.isNaN()) 0.5f else rawX.coerceIn(0f, 1f)
            val normY = if (rawY.isNaN()) 0.5f else rawY.coerceIn(0f, 1f)
            return TradeSignalDecision(
                decision = decision,
                confidence = confidence,
                assetName = assetName,
                accountMode = accountMode,
                marketBias = marketBias,
                reasoning = reasoning,
                targetLabel = targetLabel,
                normalizedX = normX,
                normalizedY = normY,
            )
        }
    }
}

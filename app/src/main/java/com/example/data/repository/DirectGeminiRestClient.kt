package com.example.data.repository

import android.graphics.Bitmap
import android.util.Base64
import com.example.browser.TradeSignalDecision
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Direct Gemini Developer API REST client (generativelanguage.googleapis.com/v1beta)
 * used when the user configures a personal Gemini API key or build-time GEMINI_API_KEY.
 * Passes the key strictly in the `x-goog-api-key` HTTP header (never in URL query params).
 */
class DirectGeminiRestClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    fun streamChat(
        apiKey: String,
        history: List<GeminiChatMessage>,
        userPrompt: String,
        pageContext: PageAiContext?,
    ): Flow<String> = flow {
        val fullPrompt = GeminiShieldRepository.buildContextualPrompt(userPrompt, pageContext)
        val payload = buildChatRequestJson(history, fullPrompt).toString()

        val plan = listOf(
            PRIMARY_MODEL to true,
            FALLBACK_MODEL to true,
            FALLBACK_MODEL to false,
        )
        var lastError: Exception? = null

        for ((idx, step) in plan.withIndex()) {
            val (modelName, useStream) = step
            var emittedAny = false
            try {
                if (useStream) {
                    val url = "$BASE_URL/$modelName:streamGenerateContent?alt=sse"
                    val request = buildJsonPostRequest(url, apiKey, payload)
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw buildHttpException(response.code, response.body?.string())
                        }
                        val source = response.body?.source()
                            ?: throw IOException("Empty streaming response body from Gemini.")
                        while (!source.exhausted()) {
                            if (!currentCoroutineContext().isActive) {
                                throw CancellationException("Gemini stream cancelled")
                            }
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val dataJson = line.removePrefix("data:").trim()
                            if (dataJson.isEmpty() || dataJson == "[DONE]") continue
                            val piece = extractTextFromResponseJson(dataJson)
                            if (!piece.isNullOrEmpty()) {
                                emittedAny = true
                                emit(piece)
                            }
                        }
                    }
                } else {
                    val url = "$BASE_URL/$modelName:generateContent"
                    val request = buildJsonPostRequest(url, apiKey, payload)
                    httpClient.newCall(request).execute().use { response ->
                        val bodyText = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            throw buildHttpException(response.code, bodyText)
                        }
                        val text = extractTextFromResponseJson(bodyText)
                        if (!text.isNullOrEmpty()) {
                            emittedAny = true
                            emit(text)
                        }
                    }
                }
                if (emittedAny) return@flow
                if (idx < plan.lastIndex) delay(RETRY_DELAY_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (emittedAny) return@flow
                lastError = e
                val retryable = isRetryableModelOrTransientError(e)
                if (!retryable || idx == plan.lastIndex) {
                    throw e
                }
                delay(RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("Gemini API returned an empty response.")
    }.flowOn(Dispatchers.IO)

    suspend fun runStructuredPrivacyAudit(
        apiKey: String,
        pageContext: PageAiContext,
    ): PagePrivacyAudit = withContext(Dispatchers.IO) {
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
        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    },
                ),
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("responseSchema", buildAuditSchemaJson())
                },
            )
        }.toString()

        val text = executeUnaryWithFallback(apiKey, payload)
        GeminiShieldRepository.parsePagePrivacyAuditJson(stripOptionalJsonFences(text))
    }

    suspend fun analyzeScreenForTradeSignal(
        apiKey: String,
        screenshot: Bitmap,
        pageUrl: String,
        domControlsSummary: String,
        visualTelemetrySummary: String,
    ): TradeSignalDecision = withContext(Dispatchers.IO) {
        val promptText = GeminiShieldRepository.buildTradeAnalysisPrompt(
            pageUrl = pageUrl,
            domControlsSummary = domControlsSummary,
            visualTelemetrySummary = visualTelemetrySummary,
        )
        val imageBase64 = encodeBitmapJpegBase64(screenshot)
        val parts = JSONArray().apply {
            put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", imageBase64)
                    },
                ),
            )
            put(JSONObject().put("text", promptText))
        }
        val payload = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", GeminiShieldRepository.TRADE_SYSTEM_PROMPT)),
                ),
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", parts)
                    },
                ),
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.2)
                    put("responseMimeType", "application/json")
                    put("responseSchema", buildTradeVisionSchemaJson())
                },
            )
        }.toString()

        val text = executeUnaryWithFallback(apiKey, payload)
        GeminiShieldRepository.parseTradeDecisionJson(stripOptionalJsonFences(text))
    }

    private suspend fun executeUnaryWithFallback(apiKey: String, payload: String): String {
        val models = listOf(PRIMARY_MODEL, FALLBACK_MODEL)
        var lastError: Exception? = null
        for ((idx, modelName) in models.withIndex()) {
            try {
                val url = "$BASE_URL/$modelName:generateContent"
                val request = buildJsonPostRequest(url, apiKey, payload)
                httpClient.newCall(request).execute().use { response ->
                    val bodyText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw buildHttpException(response.code, bodyText)
                    }
                    val extracted = extractTextFromResponseJson(bodyText)
                    if (!extracted.isNullOrBlank()) {
                        return extracted
                    }
                }
                if (idx < models.lastIndex) delay(RETRY_DELAY_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (!isRetryableModelOrTransientError(e) || idx == models.lastIndex) {
                    throw e
                }
                delay(RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("Gemini API returned an empty response.")
    }

    private fun buildJsonPostRequest(url: String, apiKey: String, jsonPayload: String): Request =
        Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-goog-api-key", apiKey)
            .post(jsonPayload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val PRIMARY_MODEL = "gemini-2.5-flash"
        const val FALLBACK_MODEL = "gemini-2.5-flash-lite"
        private const val RETRY_DELAY_MS = 300L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(25, TimeUnit.SECONDS)
                .build()

        fun buildChatRequestJson(
            history: List<GeminiChatMessage>,
            fullPrompt: String,
        ): JSONObject {
            val contents = JSONArray()
            GeminiShieldRepository.normalizeHistoryTurns(history).forEach { (role, text) ->
                val roleStr = if (role == ChatRole.USER) "user" else "model"
                contents.put(
                    JSONObject().apply {
                        put("role", roleStr)
                        put("parts", JSONArray().put(JSONObject().put("text", text)))
                    },
                )
            }
            contents.put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", fullPrompt)))
                },
            )
            return JSONObject().apply {
                put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", GeminiShieldRepository.CHAT_SYSTEM_PROMPT)),
                    ),
                )
                put("contents", contents)
            }
        }

        fun extractTextFromResponseJson(rawJson: String): String? {
            if (rawJson.isBlank()) return null
            val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val parts = candidates.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?: return null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val partText = parts.optJSONObject(i)?.optString("text", "").orEmpty()
                if (partText.isNotEmpty()) sb.append(partText)
            }
            return sb.toString().ifEmpty { null }
        }

        fun parseApiErrorMessage(code: Int, rawBody: String?): String {
            val body = rawBody.orEmpty().trim()
            val apiMsg = runCatching {
                JSONObject(body).optJSONObject("error")?.optString("message")?.trim()
            }.getOrNull().orEmpty()
            return when {
                code == 400 && (apiMsg.contains("API key", ignoreCase = true) || apiMsg.contains("API_KEY_INVALID", ignoreCase = true)) ->
                    "Invalid Gemini API Key. Check your key in Settings and try again."
                code == 401 || code == 403 ->
                    "Gemini API Key rejected ($code): ${apiMsg.ifBlank { "check key permissions or quota." }}"
                code == 429 ->
                    "Gemini API rate limit reached (429). Wait a moment and tap Retry."
                apiMsg.isNotBlank() ->
                    "Gemini API error ($code): $apiMsg"
                else ->
                    "Gemini API HTTP $code"
            }
        }

        fun stripOptionalJsonFences(raw: String): String {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("```")) return trimmed
            return trimmed
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        }

        private fun buildHttpException(code: Int, rawBody: String?): IOException =
            IOException(parseApiErrorMessage(code, rawBody))

        private fun isRetryableModelOrTransientError(error: Throwable): Boolean {
            val msg = error.message?.lowercase().orEmpty()
            if (msg.contains("invalid gemini api key") || msg.contains("api_key_invalid")) return false
            return msg.contains("404") ||
                msg.contains("not found") ||
                msg.contains("429") ||
                msg.contains("503") ||
                msg.contains("502") ||
                msg.contains("504") ||
                msg.contains("timed out") ||
                msg.contains("timeout") ||
                msg.contains("overloaded") ||
                msg.contains("high demand")
        }

        private fun encodeBitmapJpegBase64(bitmap: Bitmap): String {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }

        private fun buildAuditSchemaJson(): JSONObject = JSONObject().apply {
            put("type", "OBJECT")
            put(
                "properties",
                JSONObject().apply {
                    put(
                        "riskLevel",
                        JSONObject().apply {
                            put("type", "STRING")
                            put("enum", JSONArray(listOf("Low", "Medium", "High")))
                        },
                    )
                    put("summary", JSONObject().put("type", "STRING"))
                    put(
                        "privacySignals",
                        JSONObject().apply {
                            put("type", "ARRAY")
                            put("items", JSONObject().put("type", "STRING"))
                        },
                    )
                    put("recommendedAction", JSONObject().put("type", "STRING"))
                },
            )
            put("required", JSONArray(listOf("riskLevel", "summary", "privacySignals", "recommendedAction")))
        }

        private fun buildTradeVisionSchemaJson(): JSONObject = JSONObject().apply {
            put("type", "OBJECT")
            put(
                "properties",
                JSONObject().apply {
                    put(
                        "decision",
                        JSONObject().apply {
                            put("type", "STRING")
                            put("enum", JSONArray(listOf("BUY", "SELL", "HOLD")))
                        },
                    )
                    put("confidence", JSONObject().put("type", "INTEGER"))
                    put("assetName", JSONObject().put("type", "STRING"))
                    put("accountMode", JSONObject().put("type", "STRING"))
                    put("marketBias", JSONObject().put("type", "STRING"))
                    put("reasoning", JSONObject().put("type", "STRING"))
                    put("targetLabel", JSONObject().put("type", "STRING"))
                    put("normalizedX", JSONObject().put("type", "NUMBER"))
                    put("normalizedY", JSONObject().put("type", "NUMBER"))
                },
            )
            put(
                "required",
                JSONArray(
                    listOf(
                        "decision",
                        "confidence",
                        "assetName",
                        "accountMode",
                        "marketBias",
                        "reasoning",
                        "targetLabel",
                        "normalizedX",
                        "normalizedY",
                    ),
                ),
            )
        }
    }
}

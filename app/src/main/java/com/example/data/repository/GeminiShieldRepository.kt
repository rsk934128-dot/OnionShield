package com.example.data.repository

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
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

class GeminiShieldRepository {

    private val chatModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = MODEL_NAME,
            systemInstruction = content {
                text(
                    "You are OnionShield AI, a concise security, privacy, and research assistant " +
                        "built inside the OnionShield privacy browser. Explain web privacy, Tor " +
                        "networking, trackers, phishing signals, and web page summaries accurately and concisely.",
                )
            },
        )
    }

    private val auditModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = PAGE_AUDIT_SCHEMA
            },
        )
    }

    fun sendChatMessageStream(
        history: List<GeminiChatMessage>,
        userPrompt: String,
        pageContext: PageAiContext?,
    ): Flow<String> {
        val chat = chatModel.startChat(history = buildSdkHistory(history))
        val fullPrompt = buildContextualPrompt(userPrompt, pageContext)
        return chat.sendMessageStream(fullPrompt).mapNotNull { chunk ->
            chunk.text?.takeIf { it.isNotEmpty() }
        }
    }

    fun streamPrompt(prompt: String, pageContext: PageAiContext?): Flow<String> =
        sendChatMessageStream(history = emptyList(), userPrompt = prompt, pageContext = pageContext)

    suspend fun runStructuredPrivacyAudit(pageContext: PageAiContext): PagePrivacyAudit {
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
        val response = auditModel.generateContent(prompt)
        val jsonText = response.text ?: throw IllegalStateException("Gemini returned an empty audit response.")
        return parsePagePrivacyAuditJson(jsonText)
    }

    companion object {
        const val MODEL_NAME = "gemini-3.8-flash"

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
    }
}

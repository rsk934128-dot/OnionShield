package com.example.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.ChatRole
import com.example.data.repository.GeminiChatMessage
import com.example.data.repository.GeminiShieldRepository
import com.example.data.repository.PageAiContext
import com.example.data.repository.PagePrivacyAudit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed interface GeminiUiState {
    data object Idle : GeminiUiState
    data object Loading : GeminiUiState
    data class Streaming(val text: String) : GeminiUiState
    data class Success(
        val text: String,
        val audit: PagePrivacyAudit? = null,
    ) : GeminiUiState
    data class Error(val message: String) : GeminiUiState
}

class GeminiAssistantViewModel(
    private val repository: GeminiShieldRepository = GeminiShieldRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow<GeminiUiState>(GeminiUiState.Idle)
    val uiState: StateFlow<GeminiUiState> = _uiState.asStateFlow()

    private val _messages = MutableStateFlow<List<GeminiChatMessage>>(emptyList())
    val messages: StateFlow<List<GeminiChatMessage>> = _messages.asStateFlow()

    private val _lastPrompt = MutableStateFlow("")
    val lastPrompt: StateFlow<String> = _lastPrompt.asStateFlow()

    fun sendMessage(
        prompt: String,
        pageContext: PageAiContext?,
        displayLabel: String? = null,
    ) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return
        if (_uiState.value is GeminiUiState.Loading || _uiState.value is GeminiUiState.Streaming) return

        val priorHistory = _messages.value
        val shownUserText = displayLabel?.trim()?.takeIf { it.isNotEmpty() } ?: cleanPrompt
        val promptWithContext = GeminiShieldRepository.buildContextualPrompt(cleanPrompt, pageContext)
        _lastPrompt.value = shownUserText
        _messages.value = priorHistory + GeminiChatMessage(
            role = ChatRole.USER,
            text = shownUserText,
            promptText = promptWithContext,
            pageUrl = pageContext?.url,
        )

        viewModelScope.launch {
            _uiState.value = GeminiUiState.Loading
            val accumulated = StringBuilder()
            try {
                withTimeout(INFERENCE_TIMEOUT_MS) {
                    repository.sendChatMessageStream(
                        history = priorHistory,
                        userPrompt = cleanPrompt,
                        pageContext = pageContext,
                    ).collect { chunkText ->
                        accumulated.append(chunkText)
                        _uiState.value = GeminiUiState.Streaming(accumulated.toString())
                    }
                }
                val finalAnswer = accumulated.toString().trim()
                if (finalAnswer.isNotEmpty()) {
                    _messages.value = _messages.value + GeminiChatMessage(
                        role = ChatRole.MODEL,
                        text = finalAnswer,
                        pageUrl = pageContext?.url,
                    )
                    _uiState.value = GeminiUiState.Success(text = finalAnswer)
                } else {
                    _uiState.value = GeminiUiState.Error("Gemini returned an empty response.")
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Gemini chat streaming request timed out", e)
                _uiState.value = GeminiUiState.Error(
                    "Gemini request timed out after 60 seconds. Please check your connection and try again.",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Gemini chat streaming inference failed", e)
                _uiState.value = GeminiUiState.Error(
                    e.localizedMessage ?: "Failed to reach Gemini via Firebase AI Logic.",
                )
            }
        }
    }

    fun askGemini(prompt: String, pageContext: PageAiContext?) {
        sendMessage(prompt = prompt, pageContext = pageContext)
    }

    fun summarizeCurrentPage(pageContext: PageAiContext) {
        val label = "Summarize: ${pageContext.title.ifBlank { pageContext.url }}"
        sendMessage(
            prompt = "Summarize the key points, purpose, and any privacy considerations of this web page in clear bullet points.",
            pageContext = pageContext,
            displayLabel = label,
        )
    }

    fun auditCurrentPage(pageContext: PageAiContext) {
        if (_uiState.value is GeminiUiState.Loading || _uiState.value is GeminiUiState.Streaming) return
        val label = "Privacy & Safety Audit: ${pageContext.title.ifBlank { pageContext.url }}"
        _lastPrompt.value = label
        _messages.value = _messages.value + GeminiChatMessage(
            role = ChatRole.USER,
            text = label,
            promptText = label,
            pageUrl = pageContext.url,
        )

        viewModelScope.launch {
            _uiState.value = GeminiUiState.Loading
            try {
                val audit = withTimeout(INFERENCE_TIMEOUT_MS) {
                    repository.runStructuredPrivacyAudit(pageContext)
                }
                _messages.value = _messages.value + GeminiChatMessage(
                    role = ChatRole.MODEL,
                    text = audit.summary,
                    pageUrl = pageContext.url,
                    audit = audit,
                )
                _uiState.value = GeminiUiState.Success(
                    text = audit.summary,
                    audit = audit,
                )
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Structured page audit timed out", e)
                _uiState.value = GeminiUiState.Error(
                    "Page audit timed out after 60 seconds. Please try again.",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Structured page audit failed", e)
                _uiState.value = GeminiUiState.Error(
                    e.localizedMessage ?: "Unable to analyze this page with Gemini.",
                )
            }
        }
    }

    fun clearChat() {
        if (_uiState.value is GeminiUiState.Loading || _uiState.value is GeminiUiState.Streaming) return
        _messages.value = emptyList()
        _lastPrompt.value = ""
        _uiState.value = GeminiUiState.Idle
    }

    fun dismissError() {
        if (_uiState.value is GeminiUiState.Error) {
            _uiState.value = GeminiUiState.Idle
        }
    }

    companion object {
        private const val TAG = "GeminiAssistantVM"
        private const val INFERENCE_TIMEOUT_MS = 60_000L
    }
}

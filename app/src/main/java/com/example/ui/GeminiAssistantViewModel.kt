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
    data class Error(
        val message: String,
        val canRetry: Boolean = false,
    ) : GeminiUiState
}

private sealed interface PendingGeminiRequest {
    data class Chat(
        val prompt: String,
        val pageContext: PageAiContext?,
        val displayLabel: String?,
    ) : PendingGeminiRequest

    data class Audit(
        val pageContext: PageAiContext,
    ) : PendingGeminiRequest
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

    private var pendingFailedRequest: PendingGeminiRequest? = null

    fun sendMessage(
        prompt: String,
        pageContext: PageAiContext?,
        displayLabel: String? = null,
    ) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return
        if (_uiState.value is GeminiUiState.Loading || _uiState.value is GeminiUiState.Streaming) return

        val shownUserText = displayLabel?.trim()?.takeIf { it.isNotEmpty() } ?: cleanPrompt
        val promptWithContext = GeminiShieldRepository.buildContextualPrompt(cleanPrompt, pageContext)

        // Reuse any unanswered trailing USER bubble when retrying the same prompt instead of duplicating it.
        val currentList = _messages.value
        val existingPendingMatch = currentList.lastOrNull()?.let { last ->
            last.role == ChatRole.USER && last.text == shownUserText && last.pageUrl == pageContext?.url
        } == true
        val priorHistory = if (existingPendingMatch) currentList.dropLast(1) else currentList
        if (!existingPendingMatch) {
            _messages.value = priorHistory + GeminiChatMessage(
                role = ChatRole.USER,
                text = shownUserText,
                promptText = promptWithContext,
                pageUrl = pageContext?.url,
            )
        }
        _lastPrompt.value = shownUserText
        val failedRequest = PendingGeminiRequest.Chat(
            prompt = cleanPrompt,
            pageContext = pageContext,
            displayLabel = displayLabel,
        )
        pendingFailedRequest = failedRequest

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
                    pendingFailedRequest = null
                    _messages.value = _messages.value + GeminiChatMessage(
                        role = ChatRole.MODEL,
                        text = finalAnswer,
                        pageUrl = pageContext?.url,
                    )
                    _uiState.value = GeminiUiState.Success(text = finalAnswer)
                } else {
                    _uiState.value = GeminiUiState.Error(
                        message = "Gemini returned an empty response. Tap Retry to try again.",
                        canRetry = true,
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Gemini chat streaming request timed out", e)
                _uiState.value = GeminiUiState.Error(
                    message = "Gemini request timed out after 60 seconds. Tap Retry to try again.",
                    canRetry = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Gemini chat streaming inference failed", e)
                _uiState.value = GeminiUiState.Error(
                    message = formatUserFacingError(e, "Failed to reach Gemini via Firebase AI Logic."),
                    canRetry = true,
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
        val currentList = _messages.value
        val existingPendingMatch = currentList.lastOrNull()?.let { last ->
            last.role == ChatRole.USER && last.text == label && last.pageUrl == pageContext.url
        } == true
        if (!existingPendingMatch) {
            _messages.value = currentList + GeminiChatMessage(
                role = ChatRole.USER,
                text = label,
                promptText = label,
                pageUrl = pageContext.url,
            )
        }
        _lastPrompt.value = label
        pendingFailedRequest = PendingGeminiRequest.Audit(pageContext)

        viewModelScope.launch {
            _uiState.value = GeminiUiState.Loading
            try {
                val audit = withTimeout(INFERENCE_TIMEOUT_MS) {
                    repository.runStructuredPrivacyAudit(pageContext)
                }
                pendingFailedRequest = null
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
                    message = "Page audit timed out after 60 seconds. Tap Retry to try again.",
                    canRetry = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Structured page audit failed", e)
                _uiState.value = GeminiUiState.Error(
                    message = formatUserFacingError(e, "Unable to analyze this page with Gemini."),
                    canRetry = true,
                )
            }
        }
    }

    fun retryLastRequest() {
        if (_uiState.value is GeminiUiState.Loading || _uiState.value is GeminiUiState.Streaming) return
        when (val pending = pendingFailedRequest) {
            is PendingGeminiRequest.Chat -> sendMessage(
                prompt = pending.prompt,
                pageContext = pending.pageContext,
                displayLabel = pending.displayLabel,
            )
            is PendingGeminiRequest.Audit -> auditCurrentPage(pending.pageContext)
            null -> {
                val fallbackPrompt = _lastPrompt.value.trim()
                if (fallbackPrompt.isNotEmpty()) {
                    sendMessage(prompt = fallbackPrompt, pageContext = null)
                }
            }
        }
    }

    fun clearChat() {
        if (_uiState.value is GeminiUiState.Loading || _uiState.value is GeminiUiState.Streaming) return
        pendingFailedRequest = null
        _messages.value = emptyList()
        _lastPrompt.value = ""
        _uiState.value = GeminiUiState.Idle
    }

    fun dismissError() {
        if (_uiState.value is GeminiUiState.Error) {
            _uiState.value = GeminiUiState.Idle
        }
    }

    private fun formatUserFacingError(error: Exception, fallback: String): String {
        val raw = error.localizedMessage?.trim().orEmpty().ifBlank { fallback }
        return if (GeminiShieldRepository.isTransientHighDemandError(error)) {
            "Gemini servers are briefly busy right now. Tap Retry to try again."
        } else {
            raw
        }
    }

    companion object {
        private const val TAG = "GeminiAssistantVM"
        private const val INFERENCE_TIMEOUT_MS = 60_000L
    }
}

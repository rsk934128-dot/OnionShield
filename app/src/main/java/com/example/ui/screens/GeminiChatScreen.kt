package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.BrowserMode
import com.example.data.BrowserTab
import com.example.data.SecurityConfig
import com.example.data.repository.PageAiContext
import com.example.ui.GeminiAssistantViewModel
import com.example.ui.GeminiUiState
import com.example.ui.components.ChatMessageRow
import com.example.ui.components.ModelMessageCard
import com.example.ui.theme.LocalShieldColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GeminiChatScreen(
    activeTab: BrowserTab,
    securityConfig: SecurityConfig,
    extractPageSnippet: (tabId: String, onResult: (String) -> Unit) -> Unit,
    onNavigateBack: () -> Unit,
    assistantViewModel: GeminiAssistantViewModel = viewModel(),
) {
    val shieldColors = LocalShieldColors.current
    val uiState by assistantViewModel.uiState.collectAsStateWithLifecycle()
    val messages by assistantViewModel.messages.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    var promptInput by remember { mutableStateOf("") }
    var pageSnippet by remember(activeTab.id, activeTab.url) { mutableStateOf("") }
    var attachPageContext by remember(activeTab.id, activeTab.url) { mutableStateOf(!activeTab.isStartPage) }

    val isBusy = uiState is GeminiUiState.Loading || uiState is GeminiUiState.Streaming

    LaunchedEffect(activeTab.id, activeTab.url) {
        if (!activeTab.isStartPage) {
            extractPageSnippet(activeTab.id) { snippet ->
                pageSnippet = snippet
            }
        } else {
            pageSnippet = ""
        }
    }

    val activePageContext = remember(activeTab, securityConfig.browserMode, pageSnippet) {
        if (activeTab.isStartPage) {
            null
        } else {
            PageAiContext(
                url = activeTab.url,
                title = activeTab.title,
                browserModeLabel = if (securityConfig.browserMode == BrowserMode.TOR_ONION) "Tor .onion" else "Surface",
                trackersBlockedCount = activeTab.blockedTrackersCount,
                scriptsBlockedCount = activeTab.blockedScriptsCount,
                pageTextSnippet = pageSnippet,
            )
        }
    }

    val effectiveChatContext = if (attachPageContext) activePageContext else null

    LaunchedEffect(messages.size, uiState) {
        val totalItems = listState.layoutInfo.totalItemsCount
        if (totalItems > 0 && (messages.isNotEmpty() || isBusy || uiState is GeminiUiState.Error)) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                expandedHeight = 52.dp,
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.gemini_ai_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.gemini_ai_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = shieldColors.surfaceAccent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("gemini_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (messages.isNotEmpty()) {
                        IconButton(
                            onClick = { assistantViewModel.clearChat() },
                            enabled = !isBusy,
                            modifier = Modifier.testTag("gemini_clear_chat_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = stringResource(R.string.gemini_ai_clear_chat),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (activePageContext != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = attachPageContext,
                                onClick = { attachPageContext = !attachPageContext },
                                label = {
                                    Text(
                                        text = if (attachPageContext) {
                                            "Using active page: ${activePageContext.title.ifBlank { activePageContext.url }}"
                                        } else {
                                            "Attach active page context"
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Language,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                modifier = Modifier.testTag("gemini_attach_page_chip"),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = promptInput,
                            onValueChange = { promptInput = it },
                            placeholder = { Text(stringResource(R.string.gemini_ai_prompt_hint)) },
                            maxLines = 3,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (promptInput.isNotBlank() && !isBusy) {
                                        assistantViewModel.sendMessage(promptInput, effectiveChatContext)
                                        promptInput = ""
                                    }
                                },
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("gemini_prompt_input"),
                        )
                        Button(
                            onClick = {
                                if (promptInput.isNotBlank() && !isBusy) {
                                    assistantViewModel.sendMessage(promptInput, effectiveChatContext)
                                    promptInput = ""
                                }
                            },
                            enabled = promptInput.isNotBlank() && !isBusy,
                            modifier = Modifier
                                .height(52.dp)
                                .testTag("gemini_send_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = stringResource(R.string.gemini_ai_send),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Active Page Context & One-Tap Page Summary / Privacy Audit Actions
            item(key = "context_card") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp)
                        .testTag("gemini_context_card"),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, shieldColors.surfaceAccent.copy(alpha = 0.5f)),
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            shieldColors.surfaceAccent,
                                            shieldColors.torAccent,
                                            shieldColors.secureGreen,
                                        ),
                                    ),
                                ),
                        )
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = if (activePageContext == null) {
                                        Icons.Rounded.AutoAwesome
                                    } else {
                                        Icons.Rounded.Language
                                    },
                                    contentDescription = null,
                                    tint = shieldColors.surfaceAccent,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = activePageContext?.title?.ifBlank { activePageContext.url }
                                            ?: stringResource(R.string.gemini_ai_ready_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = activePageContext?.url
                                            ?: stringResource(R.string.gemini_ai_ready_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            if (activePageContext != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = { assistantViewModel.auditCurrentPage(activePageContext) },
                                        enabled = !isBusy,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("gemini_audit_page_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Security,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.gemini_ai_audit_page), maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = { assistantViewModel.summarizeCurrentPage(activePageContext) },
                                        enabled = !isBusy,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("gemini_summarize_page_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Summarize,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.gemini_ai_summarize_page), maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Starter Privacy Question Chips when starting a fresh chat
            if (messages.isEmpty()) {
                item(key = "starter_prompts") {
                    val quickPrompts = listOf(
                        "Explain how Tor .onion circuits protect my IP address",
                        "How can I spot phishing links on mobile?",
                        "What trackers does OnionShield block?",
                        "Compare DNS-over-HTTPS vs standard ISP DNS",
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 640.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            quickPrompts.forEach { sample ->
                                AssistChip(
                                    onClick = {
                                        if (!isBusy) {
                                            assistantViewModel.sendMessage(sample, effectiveChatContext)
                                        }
                                    },
                                    label = { Text(sample, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }

                        if (uiState is GeminiUiState.Idle) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        tint = shieldColors.surfaceAccent,
                                        modifier = Modifier.size(28.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.gemini_ai_empty_hint),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Multi-Turn Chat Message Thread
            items(items = messages, key = { it.id }) { msg ->
                ChatMessageRow(message = msg)
            }

            // Live Loading / Streaming / Error Footer
            when (val state = uiState) {
                GeminiUiState.Loading -> {
                    item(key = "loading_indicator") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 640.dp)
                                .testTag("gemini_loading_card"),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp,
                                )
                                Text(
                                    text = "Thinking with Gemini AI…",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }

                is GeminiUiState.Streaming -> {
                    item(key = "streaming_bubble") {
                        ModelMessageCard(
                            bodyText = state.text,
                            isStreaming = true,
                        )
                    }
                }

                is GeminiUiState.Error -> {
                    item(key = "error_card") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 640.dp)
                                .testTag("gemini_error_card"),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f)),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ErrorOutline,
                                        contentDescription = "Gemini error",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = "Gemini Request Error",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                        Text(
                                            text = state.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(
                                        onClick = { assistantViewModel.dismissError() },
                                        modifier = Modifier.testTag("gemini_dismiss_error_button"),
                                    ) {
                                        Text("Dismiss")
                                    }
                                    if (state.canRetry) {
                                        Spacer(Modifier.width(8.dp))
                                        Button(
                                            onClick = { assistantViewModel.retryLastRequest() },
                                            modifier = Modifier.testTag("gemini_retry_button"),
                                        ) {
                                            Text("Retry")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                GeminiUiState.Idle, is GeminiUiState.Success -> Unit
            }
        }
    }
}

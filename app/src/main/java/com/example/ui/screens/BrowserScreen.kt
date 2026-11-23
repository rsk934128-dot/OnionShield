package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoGraph
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CodeOff
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.browser.GuardAlertLevel
import com.example.browser.LiveMonitorForegroundService
import com.example.browser.TorProxyController
import com.example.browser.TrackerAndUrlEngine
import com.example.data.ABOUT_START_URL
import com.example.data.BrowserMode
import com.example.data.BrowserTab
import com.example.data.TorProxyState
import com.example.data.VpnTunnelStatus
import com.example.ui.BrowserUiState
import com.example.ui.BrowserViewModel
import com.example.ui.components.BrowserWebView
import com.example.ui.components.CircuitDiagnosticSheet
import com.example.ui.components.DisclaimerDialog
import com.example.ui.components.DownloadSandboxDialog
import com.example.ui.components.InstallHomeAppDialog
import com.example.ui.components.LinkActionSheet
import com.example.ui.components.QrScannerModal
import com.example.ui.components.QuickShieldSheet
import com.example.ui.components.ShredConfirmDialog
import com.example.ui.components.StartPageContent
import com.example.ui.components.TabSwitcherSheet
import com.example.ui.components.TradePilotHudCard
import com.example.ui.components.TradeTapIndicatorOverlay
import com.example.ui.components.WebOverlayAiControls
import com.example.ui.components.WebsiteLogo
import com.example.ui.theme.LocalShieldColors

@Composable
fun BrowserScreen(
    uiState: BrowserUiState,
    isBookmarked: Boolean,
    viewModel: BrowserViewModel,
    onOpenSecuritySettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenVault: () -> Unit = {},
    onOpenCloudSync: () -> Unit = {},
    onOpenSessionGuard: () -> Unit = {},
    onOpenAdminPanel: () -> Unit = {},
    onOpenAdminSettings: () -> Unit = {},
    onOpenVpnConnect: () -> Unit = {},
    onOpenGeminiAssistant: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val shieldColors = LocalShieldColors.current

    val pinnedApps by viewModel.pinnedApps.collectAsStateWithLifecycle()
    val omniboxSuggestions by viewModel.omniboxSuggestions.collectAsStateWithLifecycle()
    val sessionGuardStats by viewModel.sessionGuardStats.collectAsStateWithLifecycle()
    val isForegroundServiceRunning by viewModel.isForegroundServiceRunning.collectAsStateWithLifecycle()
    val isMediaPlaybackActive by viewModel.isMediaPlaybackActive.collectAsStateWithLifecycle()
    val vpnTunnelStatus by viewModel.vpnTunnelStatus.collectAsStateWithLifecycle()
    var mediaNotifPromptDismissed by remember { mutableStateOf(false) }
    var hasNotifPerm by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val mediaNotifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasNotifPerm = granted
        if (granted) {
            LiveMonitorForegroundService.start(context)
        } else {
            mediaNotifPromptDismissed = true
        }
    }
    val fullscreenCustomView by viewModel.fullscreenCustomView.collectAsStateWithLifecycle()
    val popupWebView by viewModel.popupWebView.collectAsStateWithLifecycle()

    var showTabSwitcher by remember { mutableStateOf(false) }
    var showQuickShields by remember { mutableStateOf(false) }
    var showCircuitDiagnostics by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showCustomLinkInstallDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }

    val activeTab = uiState.activeTab
    val isTorMode = uiState.securityConfig.browserMode == BrowserMode.TOR_ONION
    val activeHost = remember(activeTab.url) { TrackerAndUrlEngine.normalizeHost(activeTab.url) }
    val isHostJsTrusted = activeHost != null && uiState.securityConfig.trustedJsHosts.contains(activeHost)
    val effectiveNoScript = uiState.securityConfig.noScriptEnabled && !isHostJsTrusted
    val accentColor by animateColorAsState(
        targetValue = if (isTorMode) shieldColors.torAccent else shieldColors.surfaceAccent,
        label = "browserAccent",
    )

    BackHandler(enabled = fullscreenCustomView != null || !activeTab.isStartPage || activeTab.canGoBack) {
        if (fullscreenCustomView != null) {
            viewModel.exitFullscreenVideo()
        } else if (!viewModel.goBack()) {
            (context as? Activity)?.finish()
        }
    }

    LaunchedEffect(uiState.statusBannerMessage) {
        if (uiState.statusBannerMessage != null) {
            kotlinx.coroutines.delay(3200)
            viewModel.dismissStatusBanner()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding(),
            ) {
                // Dual Mode Switcher + Proxy Pill
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 4.dp, top = 0.dp, bottom = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    DualModeSwitcher(
                        currentMode = uiState.securityConfig.browserMode,
                        onSelectMode = { viewModel.switchBrowserMode(it) },
                    )

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    ) {
                        if (isForegroundServiceRunning) {
                            Surface(
                                shape = CircleShape,
                                color = shieldColors.secureGreen.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, shieldColors.secureGreen.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { onOpenSessionGuard() }
                                    .testTag("live_service_indicator_chip"),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(shieldColors.secureGreen),
                                    )
                                    Text(
                                        text = if (isTorMode) "LIVE TOR" else "LIVE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = shieldColors.secureGreen,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }

                        val isVpnConnected = vpnTunnelStatus is VpnTunnelStatus.Connected
                        val vpnChipColor = if (isVpnConnected) shieldColors.secureGreen else shieldColors.surfaceAccent
                        Surface(
                            shape = CircleShape,
                            color = vpnChipColor.copy(alpha = if (isVpnConnected) 0.18f else 0.11f),
                            border = BorderStroke(1.dp, vpnChipColor.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(onClick = onOpenVpnConnect)
                                .testTag("top_vpn_chip"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = if (isVpnConnected) Icons.Rounded.VpnLock else Icons.Rounded.VpnKey,
                                    contentDescription = "Internet VPN",
                                    tint = vpnChipColor,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = if (isVpnConnected) "VPN ON" else "VPN",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = vpnChipColor,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        ProxyStatusChip(
                            mode = uiState.securityConfig.browserMode,
                            proxyState = uiState.torProxyState,
                            noScriptEnabled = effectiveNoScript,
                            onClick = { showQuickShields = true },
                        )

                        IconButton(
                            onClick = { showCircuitDiagnostics = true },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("top_circuit_shield_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = "Tor Circuit & Bridge Diagnostics",
                                tint = when {
                                    uiState.circuitDiagnostic.isTorExitVerified == true ||
                                        uiState.torProxyState is TorProxyState.Connected -> shieldColors.secureGreen
                                    isTorMode && uiState.torProxyState is TorProxyState.Unreachable -> shieldColors.warningAmber
                                    isTorMode -> shieldColors.torAccent
                                    else -> shieldColors.surfaceAccent
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                // Omnibox Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (uiState.isOmniboxFocused) accentColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = when {
                                    activeTab.isOnionAddress || isTorMode -> Icons.Rounded.Security
                                    activeTab.isHttpsSecure -> Icons.Rounded.Lock
                                    else -> Icons.Rounded.WarningAmber
                                },
                                contentDescription = "Connection security",
                                tint = when {
                                    activeTab.isOnionAddress || isTorMode -> shieldColors.torAccent
                                    activeTab.isHttpsSecure -> shieldColors.secureGreen
                                    else -> shieldColors.warningAmber
                                },
                                modifier = Modifier.size(18.dp),
                            )

                            Box(modifier = Modifier.weight(1f)) {
                                if (uiState.omniboxInput.isEmpty()) {
                                    Text(
                                        text = if (isTorMode) "Search or enter .onion / URL…" else "Search or enter URL…",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                BasicTextField(
                                    value = uiState.omniboxInput,
                                    onValueChange = viewModel::onOmniboxChanged,
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.labelMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    cursorBrush = SolidColor(accentColor),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Go,
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onGo = {
                                            focusManager.clearFocus()
                                            viewModel.openUrlOrSearch(uiState.omniboxInput)
                                        },
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { focusState ->
                                            viewModel.onOmniboxFocusChanged(focusState.isFocused)
                                        }
                                        .testTag("omnibox_input"),
                                )
                            }

                            if (uiState.omniboxInput.isNotEmpty() && uiState.isOmniboxFocused) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Clear address",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { viewModel.onOmniboxChanged("") },
                                )
                            } else if (!activeTab.isStartPage) {
                                Icon(
                                    imageVector = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                    contentDescription = "Bookmark page",
                                    tint = if (isBookmarked) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickable { viewModel.toggleBookmarkCurrentPage() }
                                        .testTag("bookmark_toggle_button"),
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = {
                            focusManager.clearFocus()
                            showQrScanner = true
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("omnibox_qr_scan_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.QrCodeScanner,
                            contentDescription = stringResource(R.string.cd_scan_qr),
                            tint = accentColor,
                        )
                    }

                    if (!activeTab.isStartPage) {
                        IconButton(
                            onClick = {
                                viewModel.installLinkToHomeAndMenu(
                                    context = context,
                                    rawTitle = activeTab.title,
                                    rawUrl = activeTab.url,
                                )
                            },
                            modifier = Modifier
                                .size(42.dp)
                                .testTag("omnibox_install_home_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddToHomeScreen,
                                contentDescription = "Install page to Home Screen",
                                tint = accentColor,
                            )
                        }
                        IconButton(
                            onClick = { viewModel.reloadOrStop() },
                            modifier = Modifier
                                .size(42.dp)
                                .testTag("reload_stop_button"),
                        ) {
                            Icon(
                                imageVector = if (activeTab.isLoading) Icons.Rounded.Close else Icons.Rounded.Refresh,
                                contentDescription = if (activeTab.isLoading) "Stop loading" else "Reload page",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                // Loading Progress Bar
                if (activeTab.isLoading) {
                    LinearProgressIndicator(
                        progress = { (activeTab.progress / 100f).coerceIn(0.05f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp),
                        color = accentColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                } else {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                }
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                Column(modifier = Modifier.navigationBarsPadding()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { viewModel.goBack() },
                            enabled = !activeTab.isStartPage || activeTab.canGoBack,
                            modifier = Modifier.testTag("nav_back_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Navigate back",
                            )
                        }

                        IconButton(
                            onClick = { viewModel.goForward() },
                            enabled = activeTab.canGoForward,
                            modifier = Modifier.testTag("nav_forward_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = "Navigate forward",
                            )
                        }

                        IconButton(
                            onClick = { viewModel.goHome() },
                            modifier = Modifier.testTag("nav_home_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Home,
                                contentDescription = "Start page",
                            )
                        }

                        // Multi-Tab Counter Button
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable { showTabSwitcher = true }
                                .testTag("tabs_switcher_button"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(7.dp),
                                color = accentColor.copy(alpha = 0.14f),
                                border = BorderStroke(1.5.dp, accentColor),
                                modifier = Modifier.size(24.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = uiState.tabs.size.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = accentColor,
                                    )
                                }
                            }
                        }

                        // Shred Session Button
                        IconButton(
                            onClick = { viewModel.requestShredSession() },
                            modifier = Modifier.testTag("shred_session_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = "Shred session",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }

                        // Quick Shield Controls
                        IconButton(
                            onClick = { showQuickShields = true },
                            modifier = Modifier.testTag("quick_shield_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = "Security shield controls",
                                tint = accentColor,
                            )
                        }

                        // Overflow Menu
                        Box {
                            IconButton(
                                onClick = { showOverflowMenu = true },
                                modifier = Modifier.testTag("browser_menu_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreVert,
                                    contentDescription = "Browser menu",
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                if (!activeTab.isStartPage) {
                                    DropdownMenuItem(
                                        text = { Text("Install to Home Screen (PWA)") },
                                        leadingIcon = { Icon(Icons.Rounded.AddToHomeScreen, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            viewModel.installLinkToHomeAndMenu(
                                                context = context,
                                                rawTitle = activeTab.title,
                                                rawUrl = activeTab.url,
                                            )
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Pin to Start Page Apps") },
                                        leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            viewModel.pinAppToStartPage(activeTab.title, activeTab.url)
                                        },
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("Install Any Link to Home") },
                                        leadingIcon = { Icon(Icons.Rounded.AddToHomeScreen, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            showCustomLinkInstallDialog = true
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.gemini_ai_menu_item)) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            tint = accentColor,
                                        )
                                    },
                                    modifier = Modifier.testTag("menu_gemini_assistant"),
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenGeminiAssistant()
                                    },
                                )
                                if (!activeTab.isStartPage) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.trade_pilot_menu_item)) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Rounded.AutoGraph,
                                                contentDescription = null,
                                                tint = accentColor,
                                            )
                                        },
                                        modifier = Modifier.testTag("menu_trade_pilot"),
                                        onClick = {
                                            showOverflowMenu = false
                                            viewModel.toggleTradePilotPanel()
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(stringResource(R.string.ai_overlay_toggle_title))
                                                Text(
                                                    text = if (uiState.securityConfig.showAiOverlayButtons) {
                                                        stringResource(R.string.ai_overlay_status_on)
                                                    } else {
                                                        stringResource(R.string.ai_overlay_status_off)
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (uiState.securityConfig.showAiOverlayButtons) {
                                                        shieldColors.secureGreen
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    },
                                                )
                                            }
                                            Switch(
                                                checked = uiState.securityConfig.showAiOverlayButtons,
                                                onCheckedChange = null,
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (uiState.securityConfig.showAiOverlayButtons) {
                                                Icons.Rounded.Visibility
                                            } else {
                                                Icons.Rounded.VisibilityOff
                                            },
                                            contentDescription = null,
                                            tint = if (uiState.securityConfig.showAiOverlayButtons) {
                                                accentColor
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    },
                                    modifier = Modifier.testTag("menu_toggle_ai_overlay"),
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.toggleAiOverlayButtons()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Scan QR Code URL") },
                                    leadingIcon = { Icon(Icons.Rounded.QrCodeScanner, contentDescription = null) },
                                    modifier = Modifier.testTag("menu_scan_qr"),
                                    onClick = {
                                        showOverflowMenu = false
                                        focusManager.clearFocus()
                                        showQrScanner = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (vpnTunnelStatus is VpnTunnelStatus.Connected) {
                                                "Internet VPN & Web Links (ON)"
                                            } else {
                                                "Internet VPN & Web Links"
                                            },
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.VpnKey, contentDescription = null) },
                                    modifier = Modifier.testTag("menu_vpn_connect"),
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenVpnConnect()
                                    },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Bookmarks & History") },
                                    leadingIcon = { Icon(Icons.Rounded.Bookmark, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenLibrary()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Secure Vault (AES-256)") },
                                    leadingIcon = { Icon(Icons.Rounded.EnhancedEncryption, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenVault()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Cloud Sync (Firebase)") },
                                    leadingIcon = { Icon(Icons.Rounded.CloudSync, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenCloudSync()
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (isForegroundServiceRunning) {
                                                "Session Guard & Stats (LIVE)"
                                            } else {
                                                "Session Guard & Stats"
                                            },
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Timer, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenSessionGuard()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Downloads Manager") },
                                    leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenDownloads()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Privacy & Security Settings") },
                                    leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                                    modifier = Modifier.testTag("menu_privacy_settings"),
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenSecuritySettings()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Admin Panel") },
                                    leadingIcon = { Icon(Icons.Rounded.AdminPanelSettings, contentDescription = null) },
                                    modifier = Modifier.testTag("menu_admin_panel"),
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenAdminPanel()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Server Admin Settings") },
                                    leadingIcon = { Icon(Icons.Rounded.Security, contentDescription = null) },
                                    modifier = Modifier.testTag("menu_admin_settings"),
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenAdminSettings()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Legal & Security Notice") },
                                    leadingIcon = { Icon(Icons.Rounded.Gavel, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.openDisclaimer()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (uiState.securityConfig.showAiOverlayButtons &&
                activeTab.isStartPage &&
                fullscreenCustomView == null &&
                !uiState.isOmniboxFocused
            ) {
                FloatingActionButton(
                    onClick = onOpenGeminiAssistant,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.testTag("gemini_chat_fab"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = stringResource(R.string.cd_open_gemini_chat),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (activeTab.isStartPage) {
                StartPageContent(
                    tab = activeTab,
                    config = uiState.securityConfig,
                    torProxyState = uiState.torProxyState,
                    totalTrackersBlocked = uiState.totalTrackersBlocked,
                    totalScriptsBlocked = uiState.totalScriptsBlocked,
                    tabs = uiState.tabs,
                    pinnedApps = pinnedApps,
                    onNavigateUrl = { url -> viewModel.openUrlOrSearch(url) },
                    onInstallHomeApp = { rawTitle, rawUrl ->
                        viewModel.installLinkToHomeAndMenu(
                            context = context,
                            rawTitle = rawTitle,
                            rawUrl = rawUrl,
                        )
                    },
                    onRemovePinnedApp = viewModel::removePinnedApp,
                    onToggleNoScript = viewModel::setNoScript,
                    onToggleHttpsEverywhere = viewModel::setHttpsEverywhere,
                    onToggleWebRtc = viewModel::setWebRtcLeakProtection,
                    onToggleFingerprint = viewModel::setFingerprintRandomization,
                    onToggleAdBlocker = viewModel::setTrackerBlocker,
                    onTogglePopupBlocker = viewModel::setPopupBlocker,
                    onRotateFingerprintSeed = viewModel::regenerateFingerprintSeed,
                    onTestTorSocket = viewModel::testTorProxyConnection,
                    onOpenSecuritySettings = onOpenSecuritySettings,
                    vpnStatus = vpnTunnelStatus,
                    onConnectVpnAfterConsent = viewModel::startVpnTunnelAfterConsent,
                    onDisconnectVpn = viewModel::stopVpnTunnel,
                    onOpenVpnHub = onOpenVpnConnect,
                )
            } else {
                val cfg = uiState.securityConfig
                Column(modifier = Modifier.fillMaxSize()) {
                    if (effectiveNoScript && activeTab.blockedScriptsCount > 0) {
                        Surface(
                            color = shieldColors.warningAmberContainer.copy(alpha = 0.85f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CodeOff,
                                        contentDescription = "Scripts blocked",
                                        tint = shieldColors.warningAmber,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = "No-Script blocked ${activeTab.blockedScriptsCount} script(s) — site may look blank",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                TextButton(
                                    onClick = { viewModel.trustCurrentSiteJavascript() },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("allow_js_site_button"),
                                ) {
                                    Text(
                                        text = "Allow JS",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = shieldColors.warningAmber,
                                    )
                                }
                            }
                        }
                    }

                    if (uiState.securityConfig.sessionGuardEnabled &&
                        sessionGuardStats.overallAlertLevel >= GuardAlertLevel.APPROACHING_LIMIT
                    ) {
                        val isLimitReached = sessionGuardStats.overallAlertLevel == GuardAlertLevel.LIMIT_REACHED
                        Surface(
                            color = if (isLimitReached) {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
                            } else {
                                shieldColors.warningAmberContainer.copy(alpha = 0.85f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Timer,
                                        contentDescription = "Session guard alert",
                                        tint = if (isLimitReached) MaterialTheme.colorScheme.error else shieldColors.warningAmber,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = if (isLimitReached) {
                                            "Session Guard limit reached (${sessionGuardStats.elapsedMinutes}m). Take a break."
                                        } else {
                                            "Session Guard: approaching time/budget limit (${sessionGuardStats.elapsedMinutes}m)."
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                TextButton(
                                    onClick = onOpenSessionGuard,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                ) {
                                    Text("Stats", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    if (isMediaPlaybackActive &&
                        uiState.securityConfig.backgroundMediaPlaybackEnabled &&
                        !hasNotifPerm &&
                        !mediaNotifPromptDismissed &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ) {
                        Surface(
                            color = shieldColors.secureGreenContainer.copy(alpha = 0.85f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayCircle,
                                        contentDescription = "Background media ready",
                                        tint = shieldColors.secureGreen,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = "Allow notification so YouTube & audio keep playing after exiting the app",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                TextButton(
                                    onClick = { mediaNotifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("allow_media_notif_button"),
                                ) {
                                    Text(
                                        text = "Allow",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = shieldColors.secureGreen,
                                    )
                                }
                            }
                        }
                    }

                    // Expandable AI Screen Trade Pilot HUD docked cleanly ABOVE WebView so chart & PixelCopy stay 100% unobstructed
                    AnimatedVisibility(
                        visible = uiState.tradePilot.isPanelExpanded &&
                            fullscreenCustomView == null &&
                            !uiState.isOmniboxFocused,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TradePilotHudCard(
                                tradePilot = uiState.tradePilot,
                                accentColor = accentColor,
                                onScanAndClick = { viewModel.analyzeScreenForTrade(executeClick = true) },
                                onAnalyzeOnly = { viewModel.analyzeScreenForTrade(executeClick = false) },
                                onExecuteLastDecision = viewModel::executeLastTradeDecision,
                                onToggleAutoPilot = viewModel::toggleTradeAutoPilot,
                                onSelectMinConfidence = viewModel::setTradeMinConfidence,
                                onDismiss = viewModel::closeTradePilotPanel,
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        BrowserWebView(
                            url = activeTab.url,
                            tabKey = activeTab.id,
                            javaScriptEnabled = !effectiveNoScript,
                            enforceHttps = cfg.httpsEverywhereEnabled,
                            useTorSocks5Proxy = cfg.browserMode == BrowserMode.TOR_ONION,
                            torProxyHost = cfg.torProxyHost,
                            torProxyPort = cfg.torProxyPort,
                            torProxyScheme = cfg.torProxyProtocol.scheme,
                            blockThirdPartyCookies = cfg.blockThirdPartyCookies,
                            manageProxyOverride = false,
                            externalWebViewFactory = { viewContext ->
                                val wv = viewModel.webViewPool.getOrCreateWebView(
                                    tabId = activeTab.id,
                                    seed = activeTab.fingerprintSeed,
                                    config = cfg,
                                    hostContext = viewContext,
                                )
                                if (wv.url.isNullOrBlank() && !activeTab.isStartPage) {
                                    viewModel.webViewPool.loadUrlForTab(
                                        tabId = activeTab.id,
                                        seed = activeTab.fingerprintSeed,
                                        config = cfg,
                                        url = activeTab.url,
                                    )
                                }
                                wv
                            },
                        )

                        // Visual click target indicator on WebView (hidden during clean capture)
                        if (!uiState.tradePilot.isCapturingScreen) {
                            TradeTapIndicatorOverlay(
                                normX = uiState.tradePilot.lastTapNormX,
                                normY = uiState.tradePilot.lastTapNormY,
                                actionLabel = uiState.tradePilot.lastClickedAction,
                                onClearIndicator = viewModel::clearLastTapIndicator,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        // Compact Top-Right AI Controls (hidden if user turns overlay off, while expanded, or during capture)
                        if (uiState.securityConfig.showAiOverlayButtons &&
                            fullscreenCustomView == null &&
                            !uiState.isOmniboxFocused &&
                            !uiState.tradePilot.isPanelExpanded &&
                            !uiState.tradePilot.isCapturingScreen
                        ) {
                            WebOverlayAiControls(
                                tradePilot = uiState.tradePilot,
                                accentColor = accentColor,
                                onToggleTradePilot = viewModel::toggleTradePilotPanel,
                                onOpenGeminiChat = onOpenGeminiAssistant,
                                onHideOverlay = { viewModel.setAiOverlayButtonsVisible(false) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 8.dp, end = 8.dp),
                            )
                        }

                        // Error / Onion Proxy Recovery Overlay
                        if (activeTab.lastError != null) {
                            WebErrorOverlay(
                                tab = activeTab,
                                torProxyState = uiState.torProxyState,
                                proxyEndpoint = uiState.securityConfig.torEndpoint,
                                onRetry = { viewModel.reloadOrStop() },
                                onSwitchToSurface = { viewModel.switchBrowserMode(BrowserMode.SURFACE) },
                                onOpenOrbot = { TorProxyController.launchOrbot(context) },
                                onOpenProxySettings = onOpenSecuritySettings,
                            )
                        }
                    }
                }
            }

            // Encrypted Omnibox Recent Searches Dropdown
            AnimatedVisibility(
                visible = uiState.isOmniboxFocused && omniboxSuggestions.isNotEmpty(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp)
                        .testTag("omnibox_suggestions_panel"),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = "AES-256 encrypted search history",
                                    tint = shieldColors.secureGreen,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "Encrypted Recent Searches",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = shieldColors.secureGreen,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            TextButton(
                                onClick = viewModel::clearRecentSearches,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("omnibox_clear_history_button"),
                            ) {
                                Text(
                                    text = "Clear",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                        omniboxSuggestions.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        focusManager.clearFocus()
                                        viewModel.openUrlOrSearch(item.query)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                                    .testTag("omnibox_suggestion_item_${item.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                val isUrlSuggestion = item.resolvedUrl.startsWith("http://", ignoreCase = true) ||
                                    item.resolvedUrl.startsWith("https://", ignoreCase = true)
                                if (isUrlSuggestion) {
                                    WebsiteLogo(
                                        url = item.resolvedUrl,
                                        title = item.query,
                                        accentColor = if (item.isOnion) shieldColors.torAccent else accentColor,
                                        size = 28.dp,
                                        cornerRadius = 7.dp,
                                        isOnion = item.isOnion,
                                        allowNetworkFetch = !isTorMode && !item.isOnion,
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (item.isOnion) Icons.Rounded.Security else Icons.Rounded.History,
                                        contentDescription = null,
                                        tint = if (item.isOnion) shieldColors.torAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.query,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (item.resolvedUrl != item.query) {
                                        Text(
                                            text = item.resolvedUrl,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { viewModel.deleteRecentSearch(item.id) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("omnibox_delete_suggestion_${item.id}"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Remove search entry",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            fullscreenCustomView?.let { customVideo ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                ) {
                    AndroidView(
                        factory = { customVideo },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Status Banner Snackbar
            AnimatedVisibility(
                visible = uiState.statusBannerMessage != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 12.dp,
                        end = if (activeTab.isStartPage) 88.dp else 12.dp,
                        bottom = 12.dp,
                    ),
            ) {
                uiState.statusBannerMessage?.let { message ->
                    Snackbar(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        action = {
                            TextButton(onClick = viewModel::dismissStatusBanner) {
                                Text("OK")
                            }
                        },
                    ) {
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    popupWebView?.let { activePopup ->
        Dialog(
            onDismissRequest = viewModel::dismissPopupWebView,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            BackHandler(onBack = viewModel::dismissPopupWebView)
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(10.dp)
                    .testTag("auth_popup_dialog"),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = activePopup.url?.takeIf { it.isNotBlank() } ?: "Account Sign-In",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = viewModel::dismissPopupWebView,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("close_auth_popup_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close sign-in window",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    HorizontalDivider()
                    AndroidView(
                        factory = {
                            (activePopup.parent as? ViewGroup)?.removeView(activePopup)
                            activePopup
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (showTabSwitcher) {
        TabSwitcherSheet(
            tabs = uiState.tabs,
            activeTabId = uiState.activeTabId,
            onSelectTab = viewModel::selectTab,
            onCloseTab = viewModel::closeTab,
            onNewSurfaceTab = { viewModel.createNewTab(BrowserMode.SURFACE) },
            onNewTorTab = { viewModel.createNewTab(BrowserMode.TOR_ONION) },
            onDismiss = { showTabSwitcher = false },
        )
    }

    if (showQuickShields) {
        QuickShieldSheet(
            tab = activeTab,
            config = uiState.securityConfig,
            tabs = uiState.tabs,
            totalTrackersBlocked = uiState.totalTrackersBlocked,
            totalScriptsBlocked = uiState.totalScriptsBlocked,
            isVpnConnected = vpnTunnelStatus is VpnTunnelStatus.Connected,
            isBackgroundServiceRunning = isForegroundServiceRunning,
            onToggleNoScript = viewModel::setNoScript,
            onToggleFingerprint = viewModel::setFingerprintRandomization,
            onToggleHttpsEverywhere = viewModel::setHttpsEverywhere,
            onToggleWebRtc = viewModel::setWebRtcLeakProtection,
            onToggleTrackerBlocker = viewModel::setTrackerBlocker,
            onTogglePopupBlocker = viewModel::setPopupBlocker,
            onToggleSiteAdBlockWhitelist = viewModel::toggleAdBlockWhitelistCurrentSite,
            onToggleThirdPartyCookies = viewModel::setBlockThirdPartyCookies,
            onToggleBackgroundLiveService = viewModel::setBackgroundLiveServiceEnabled,
            onToggleBackgroundMedia = viewModel::setBackgroundMediaPlayback,
            onRotateSeed = viewModel::regenerateFingerprintSeed,
            onOpenCircuitDiagnostics = {
                showQuickShields = false
                showCircuitDiagnostics = true
            },
            onOpenFullSettings = onOpenSecuritySettings,
            onDismiss = { showQuickShields = false },
        )
    }

    if (showCircuitDiagnostics) {
        CircuitDiagnosticSheet(
            tab = activeTab,
            config = uiState.securityConfig,
            proxyState = uiState.torProxyState,
            diagnostic = uiState.circuitDiagnostic,
            onRunDiagnostics = { viewModel.runCircuitDiagnostics(verifyExit = true) },
            onRequestNewCircuit = viewModel::requestNewTorCircuit,
            onSaveBridgeConfig = viewModel::saveBridgeConfiguration,
            onDismiss = { showCircuitDiagnostics = false },
        )
    }

    if (uiState.showDisclaimerDialog) {
        DisclaimerDialog(onAccept = viewModel::acceptDisclaimer)
    }

    uiState.pendingDownload?.let { pending ->
        DownloadSandboxDialog(
            request = pending,
            onConfirm = viewModel::confirmPendingDownload,
            onCancel = viewModel::cancelPendingDownload,
        )
    }

    if (uiState.showShredConfirmDialog) {
        ShredConfirmDialog(
            onConfirm = viewModel::confirmShredSession,
            onDismiss = viewModel::dismissShredConfirm,
        )
    }

    uiState.linkMenuTarget?.let { target ->
        LinkActionSheet(
            target = target,
            isTorMode = isTorMode,
            onInstallToHomeScreenAndMenu = { title, url ->
                viewModel.installLinkToHomeAndMenu(context, title, url)
            },
            onPinToStartMenuOnly = { title, url ->
                viewModel.pinAppToStartPage(title, url)
            },
            onOpenInNewTab = { url ->
                viewModel.openExternalLaunchUrl(url)
            },
            onDownloadUrl = { url ->
                viewModel.requestDirectDownload(url)
            },
            onCopyUrl = { url ->
                clipboard.setText(AnnotatedString(url))
                viewModel.showStatusBanner("Copied link: $url")
            },
            onDismiss = viewModel::dismissLinkActionSheet,
        )
    }

    if (showCustomLinkInstallDialog) {
        InstallHomeAppDialog(
            onDismiss = { showCustomLinkInstallDialog = false },
            onConfirm = { rawTitle, rawUrl ->
                showCustomLinkInstallDialog = false
                viewModel.installLinkToHomeAndMenu(context, rawTitle, rawUrl)
            },
        )
    }

    if (showQrScanner) {
        QrScannerModal(
            isTorMode = isTorMode,
            onOpenScannedInput = { scannedInput ->
                showQrScanner = false
                focusManager.clearFocus()
                viewModel.openUrlOrSearch(scannedInput)
                viewModel.showStatusBanner("Opened QR link: $scannedInput")
            },
            onDismiss = { showQrScanner = false },
        )
    }
}

@Composable
private fun DualModeSwitcher(
    currentMode: BrowserMode,
    onSelectMode: (BrowserMode) -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModePill(
                label = "Surface",
                icon = Icons.Rounded.Language,
                selected = currentMode == BrowserMode.SURFACE,
                accent = shieldColors.surfaceAccent,
                testTag = "mode_surface_button",
                onClick = { onSelectMode(BrowserMode.SURFACE) },
            )
            ModePill(
                label = "Tor .onion",
                icon = Icons.Rounded.Security,
                selected = currentMode == BrowserMode.TOR_ONION,
                accent = shieldColors.torAccent,
                testTag = "mode_tor_button",
                onClick = { onSelectMode(BrowserMode.TOR_ONION) },
            )
        }
    }
}

@Composable
private fun ModePill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = if (selected) accent.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent,
        border = if (selected) BorderStroke(1.dp, accent.copy(alpha = 0.65f)) else null,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProxyStatusChip(
    mode: BrowserMode,
    proxyState: TorProxyState,
    noScriptEnabled: Boolean,
    onClick: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    val (label, color) = when {
        mode == BrowserMode.TOR_ONION && proxyState is TorProxyState.Connected ->
            "TOR OK" to shieldColors.secureGreen
        mode == BrowserMode.TOR_ONION && proxyState is TorProxyState.Unreachable ->
            "NO TOR" to shieldColors.warningAmber
        mode == BrowserMode.TOR_ONION ->
            "TOR" to shieldColors.torAccent
        noScriptEnabled ->
            "NO-JS" to shieldColors.warningAmber
        else ->
            "SHIELD" to shieldColors.surfaceAccent
    }

    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag("proxy_status_chip"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun WebErrorOverlay(
    tab: BrowserTab,
    torProxyState: TorProxyState,
    proxyEndpoint: String,
    onRetry: () -> Unit,
    onSwitchToSurface: () -> Unit,
    onOpenOrbot: () -> Unit,
    onOpenProxySettings: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = "Connection diagnostic",
                        tint = shieldColors.warningAmber,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = if (tab.isOnionAddress || tab.mode == BrowserMode.TOR_ONION) {
                            "Tor / Onion Route Failed"
                        } else {
                            "Page Could Not Load"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                Text(
                    text = tab.lastError ?: "Unknown connection error",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (tab.mode == BrowserMode.TOR_ONION && torProxyState is TorProxyState.Unreachable) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = shieldColors.warningAmberContainer.copy(alpha = 0.45f),
                    ) {
                        Text(
                            text = "No local Tor daemon detected at $proxyEndpoint. Start Orbot (Tor Proxy) or point OnionShield to a running SOCKS5/HTTP Tor node.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 10.dp),
                    ) {
                        Text("Retry")
                    }
                    if (tab.mode == BrowserMode.TOR_ONION) {
                        OutlinedButton(
                            onClick = onOpenOrbot,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 10.dp),
                        ) {
                            Text("Launch Orbot")
                        }
                    }
                }

                if (tab.mode == BrowserMode.TOR_ONION) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = onOpenProxySettings) {
                            Text("Proxy Config")
                        }
                        if (!tab.isOnionAddress) {
                            TextButton(
                                onClick = onSwitchToSurface,
                                colors = ButtonDefaults.textButtonColors(contentColor = shieldColors.surfaceAccent),
                            ) {
                                Text("Use Surface Mode")
                            }
                        }
                    }
                }
            }
        }
    }
}

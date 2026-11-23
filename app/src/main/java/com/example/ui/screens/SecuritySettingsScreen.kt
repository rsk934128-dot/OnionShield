package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CodeOff
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Https
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.browser.TorProxyController
import com.example.data.SecurityConfig
import com.example.ui.BrowserUiState
import com.example.ui.BrowserViewModel
import com.example.ui.components.BackgroundKeepAliveCard
import com.example.ui.components.BatteryOptimizationCard
import com.example.ui.components.CircuitDiagnosticSheet
import com.example.ui.components.CircuitPrivacyLevelCard
import com.example.ui.components.CustomExceptionsAndAdBlockCard
import com.example.ui.components.LegalDisclaimerCard
import com.example.ui.components.SearchEngineCard
import com.example.ui.components.TorProxyRoutingCard
import com.example.ui.theme.LocalShieldColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(
    uiState: BrowserUiState,
    viewModel: BrowserViewModel,
    onOpenAdminPanel: () -> Unit = {},
    onOpenAdminSettings: () -> Unit = {},
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val shieldColors = LocalShieldColors.current
    val config = uiState.securityConfig

    var showCircuitDiagnostics by remember { mutableStateOf(false) }
    val isForegroundServiceRunning by viewModel.isForegroundServiceRunning.collectAsStateWithLifecycle()
    var notificationDeniedNote by remember { mutableStateOf<String?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            notificationDeniedNote = null
            viewModel.setBackgroundLiveServiceEnabled(true)
        } else {
            notificationDeniedNote =
                "Notification permission is required so Android can show the persistent Tor Keep-Alive notification."
        }
    }

    val activeShieldCount = remember(config) { countActivePrivacyFeatures(config) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Privacy & Security Settings")
                        Text(
                            text = "$activeShieldCount / 8 Privacy Shields Active",
                            style = MaterialTheme.typography.labelSmall,
                            color = shieldColors.secureGreen,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("settings_back_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 1. Privacy Protection Summary + Presets Card
            item {
                PrivacyOverviewPresetCard(
                    config = config,
                    activeCount = activeShieldCount,
                    onApplyRecommended = { viewModel.applyPrivacyPreset(maximumPrivacy = false) },
                    onApplyMaximum = { viewModel.applyPrivacyPreset(maximumPrivacy = true) },
                )
            }

            // Admin Panel & Protected Admin Settings Quick Access
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onOpenAdminPanel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("settings_open_admin_panel_button"),
                    ) {
                        Icon(Icons.Rounded.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open Admin Panel (Governance & Telemetry)")
                    }
                    OutlinedButton(
                        onClick = onOpenAdminSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("settings_open_admin_settings_button"),
                    ) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Server Admin Settings ('admin' Claim Required)")
                    }
                }
            }

            // 2. Individual Privacy Feature Toggles Card
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp)
                        .testTag("settings_privacy_features_card"),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.Shield, contentDescription = null, tint = shieldColors.secureGreen)
                            Column {
                                Text("Individual Privacy Controls", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "Toggle script execution, WebRTC IP leaks, HTTPS enforcement, and trackers independently",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        SettingsSwitchItem(
                            title = "Block JavaScript (No-Script)",
                            subtitle = if (config.noScriptEnabled) {
                                val trustedNote = if (config.trustedJsHosts.isNotEmpty()) {
                                    " • ${config.trustedJsHosts.size} trusted host(s) allowed"
                                } else {
                                    ""
                                }
                                "JavaScript execution & external .js resources blocked$trustedNote"
                            } else {
                                "JavaScript currently allowed • Toggle ON to block scripts"
                            },
                            icon = Icons.Rounded.CodeOff,
                            checked = config.noScriptEnabled,
                            switchTestTag = "setting_switch_noscript",
                            onCheckedChange = viewModel::setNoScript,
                        )
                        SettingsSwitchItem(
                            title = "WebRTC Leak Protection",
                            subtitle = "Disable RTCPeerConnection, STUN/ICE candidates & media device enumeration",
                            icon = Icons.Rounded.VisibilityOff,
                            checked = config.webRtcLeakProtectionEnabled,
                            switchTestTag = "setting_switch_webrtc",
                            onCheckedChange = viewModel::setWebRtcLeakProtection,
                        )
                        SettingsSwitchItem(
                            title = "HTTPS-Only Enforcement",
                            subtitle = "Upgrade clearnet HTTP to HTTPS and block insecure mixed content",
                            icon = Icons.Rounded.Https,
                            checked = config.httpsEverywhereEnabled,
                            switchTestTag = "setting_switch_https",
                            onCheckedChange = viewModel::setHttpsEverywhere,
                        )
                        SettingsSwitchItem(
                            title = "Fingerprint Randomization",
                            subtitle = "Inject canvas/WebGL noise and rotate User-Agent (Seed #${uiState.activeTab.fingerprintSeed})",
                            icon = Icons.Rounded.Fingerprint,
                            checked = config.fingerprintRandomizationEnabled,
                            switchTestTag = "setting_switch_fingerprint",
                            onCheckedChange = viewModel::setFingerprintRandomization,
                        )
                        SettingsSwitchItem(
                            title = "Ad & Tracker Blocker",
                            subtitle = "Block ad networks, banners, video ads & tracking pixels",
                            icon = Icons.Rounded.Block,
                            checked = config.trackerBlockerEnabled,
                            switchTestTag = "setting_switch_adblock",
                            onCheckedChange = viewModel::setTrackerBlocker,
                        )
                        SettingsSwitchItem(
                            title = "Popup & Redirect Shield",
                            subtitle = "Prevent automatic popups, popunders & ad redirects",
                            icon = Icons.Rounded.Security,
                            checked = config.popupBlockerEnabled,
                            switchTestTag = "setting_switch_popup",
                            onCheckedChange = viewModel::setPopupBlocker,
                        )
                        SettingsSwitchItem(
                            title = "Cosmetic Ad Hiding (CSS)",
                            subtitle = "Collapse empty ad containers, native ad widgets & iframes",
                            icon = Icons.Rounded.VisibilityOff,
                            checked = config.cosmeticAdBlockEnabled,
                            switchTestTag = "setting_switch_cosmetic",
                            onCheckedChange = viewModel::setCosmeticAdBlock,
                        )
                        SettingsSwitchItem(
                            title = "Block Third-Party Cookies",
                            subtitle = "Prevent cross-site cookie tracking across embedded frames",
                            icon = Icons.Rounded.Lock,
                            checked = config.blockThirdPartyCookies,
                            switchTestTag = "setting_switch_third_party_cookies",
                            onCheckedChange = viewModel::setBlockThirdPartyCookies,
                        )
                    }
                }
            }

            // 3. Browsing History & Display Behavior Card
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp)
                        .testTag("settings_behavior_card"),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("History & Rendering", style = MaterialTheme.typography.titleMedium)
                        SettingsSwitchItem(
                            title = "Dark WebView Content",
                            subtitle = "Apply algorithmic darkening to rendered web pages",
                            icon = Icons.Rounded.DarkMode,
                            checked = config.forceDarkWebTheme,
                            switchTestTag = "setting_switch_dark_webview",
                            onCheckedChange = viewModel::setForceDarkTheme,
                        )
                        SettingsSwitchItem(
                            title = "Save Surface Web History",
                            subtitle = "Off by default. .onion visits are never stored on disk.",
                            icon = Icons.Rounded.History,
                            checked = config.saveSurfaceHistory,
                            switchTestTag = "setting_switch_surface_history",
                            onCheckedChange = viewModel::setSaveSurfaceHistory,
                        )
                        SettingsSwitchItem(
                            title = "Background YouTube & Media Audio",
                            subtitle = "Keep video songs & HTML5 audio playing when you exit or minimize the app",
                            icon = Icons.Rounded.PlayCircle,
                            checked = config.backgroundMediaPlaybackEnabled,
                            switchTestTag = "setting_switch_background_media",
                            onCheckedChange = viewModel::setBackgroundMediaPlayback,
                        )
                        SettingsSwitchItem(
                            title = stringResource(R.string.ai_overlay_settings_title),
                            subtitle = stringResource(R.string.ai_overlay_settings_subtitle),
                            icon = Icons.Rounded.AutoAwesome,
                            checked = config.showAiOverlayButtons,
                            switchTestTag = "setting_switch_ai_overlay",
                            onCheckedChange = viewModel::setAiOverlayButtonsVisible,
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        SettingsSwitchItem(
                            title = "Biometric App Lock",
                            subtitle = "Require fingerprint or face unlock before opening history, bookmarks, or tabs",
                            icon = Icons.Rounded.Fingerprint,
                            checked = config.biometricAppLockEnabled,
                            switchTestTag = "setting_switch_biometric_app_lock",
                            onCheckedChange = viewModel::setBiometricAppLockEnabled,
                        )
                        if (config.biometricAppLockEnabled) {
                            OutlinedButton(
                                onClick = viewModel::lockAppNow,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("setting_lock_app_now_button"),
                            ) {
                                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Lock App Now")
                            }
                        }
                    }
                }
            }

            // 4. Background Browser & Tor Keep-Alive Foreground Service Card
            item {
                BackgroundKeepAliveCard(
                    isForegroundServiceRunning = isForegroundServiceRunning,
                    isEnabledPreference = config.backgroundLiveServiceEnabled,
                    notificationDeniedNote = notificationDeniedNote,
                    onToggle = { enable ->
                        notificationDeniedNote = null
                        if (!enable) {
                            viewModel.setBackgroundLiveServiceEnabled(false)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.setBackgroundLiveServiceEnabled(true)
                        }
                    },
                )
            }

            // 4b. Battery Optimization Exemption Guide for Background Media & Foreground Service
            item {
                BatteryOptimizationCard()
            }

            // 5. Tor Proxy Routing & Circuit Level
            item {
                TorProxyRoutingCard(
                    config = config,
                    torProxyState = uiState.torProxyState,
                    orbotInstalled = TorProxyController.isOrbotInstalled(context),
                    onSaveAndTest = { host, port, protocol ->
                        viewModel.updateTorProxyConfig(host, port, protocol)
                        viewModel.testTorProxyConnection()
                    },
                    onLaunchOrbot = { TorProxyController.requestOrbotStart(context) },
                    onOpenDiagnostics = { showCircuitDiagnostics = true },
                )
            }

            item {
                CircuitPrivacyLevelCard(
                    currentLevel = config.circuitPrivacyLevel,
                    onSelectLevel = viewModel::setCircuitPrivacyLevel,
                )
            }

            // 6. Custom Ad Blocklist, Ad Whitelist & Trusted JavaScript Sites
            item {
                CustomExceptionsAndAdBlockCard(
                    config = config,
                    totalTrackersBlocked = uiState.totalTrackersBlocked,
                    onAddBlockedDomain = viewModel::addCustomBlockedDomain,
                    onRemoveBlockedDomain = viewModel::removeCustomBlockedDomain,
                    onToggleAdWhitelistHost = viewModel::toggleAdBlockWhitelistHost,
                    onRemoveTrustedJsHost = viewModel::removeTrustedJsHost,
                )
            }

            // 7. Search Engine & Legal Notice
            item {
                SearchEngineCard(
                    currentEngine = config.searchEngine,
                    onSelectEngine = viewModel::setSearchEngine,
                )
            }

            item {
                LegalDisclaimerCard(onOpenDisclaimer = viewModel::openDisclaimer)
            }
        }
    }

    if (showCircuitDiagnostics) {
        CircuitDiagnosticSheet(
            tab = uiState.activeTab,
            config = config,
            proxyState = uiState.torProxyState,
            diagnostic = uiState.circuitDiagnostic,
            onRunDiagnostics = { viewModel.runCircuitDiagnostics(verifyExit = true) },
            onRequestNewCircuit = viewModel::requestNewTorCircuit,
            onSaveBridgeConfig = viewModel::saveBridgeConfiguration,
            onDismiss = { showCircuitDiagnostics = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrivacyOverviewPresetCard(
    config: SecurityConfig,
    activeCount: Int,
    onApplyRecommended: () -> Unit,
    onApplyMaximum: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .testTag("settings_privacy_summary_card"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, shieldColors.secureGreen.copy(alpha = 0.55f)),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(shieldColors.secureGreen, shieldColors.torAccent, shieldColors.surfaceAccent),
                        ),
                    ),
            )
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = "Privacy status",
                            tint = shieldColors.secureGreen,
                            modifier = Modifier.size(28.dp),
                        )
                        Column {
                            Text("Privacy Shield Status", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "$activeCount of 8 active • ${config.circuitPrivacyLevel.label} Mode",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusBadgePill(
                        label = if (config.noScriptEnabled) "JS: Blocked" else "JS: Allowed",
                        active = config.noScriptEnabled,
                        activeColor = shieldColors.secureGreen,
                        inactiveColor = shieldColors.warningAmber,
                    )
                    StatusBadgePill(
                        label = if (config.webRtcLeakProtectionEnabled) "WebRTC: Guarded" else "WebRTC: Exposed",
                        active = config.webRtcLeakProtectionEnabled,
                        activeColor = shieldColors.secureGreen,
                        inactiveColor = MaterialTheme.colorScheme.error,
                    )
                    StatusBadgePill(
                        label = if (config.httpsEverywhereEnabled) "HTTPS-Only: ON" else "HTTPS-Only: OFF",
                        active = config.httpsEverywhereEnabled,
                        activeColor = shieldColors.secureGreen,
                        inactiveColor = MaterialTheme.colorScheme.error,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onApplyRecommended,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("settings_reset_defaults_button"),
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reset Defaults", maxLines = 1)
                    }
                    Button(
                        onClick = onApplyMaximum,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("settings_max_privacy_button"),
                    ) {
                        Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Max Privacy", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadgePill(
    label: String,
    active: Boolean,
    activeColor: Color,
    inactiveColor: Color,
) {
    val tint = if (active) activeColor else inactiveColor
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = tint.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.45f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SettingsSwitchItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    switchTestTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(switchTestTag),
        )
    }
}

private fun countActivePrivacyFeatures(config: SecurityConfig): Int {
    return listOf(
        config.noScriptEnabled,
        config.webRtcLeakProtectionEnabled,
        config.httpsEverywhereEnabled,
        config.fingerprintRandomizationEnabled,
        config.trackerBlockerEnabled,
        config.popupBlockerEnabled,
        config.cosmeticAdBlockEnabled,
        config.blockThirdPartyCookies,
    ).count { it }
}

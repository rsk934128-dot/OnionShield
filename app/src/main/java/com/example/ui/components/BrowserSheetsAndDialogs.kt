package com.example.ui.components

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CodeOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Https
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.browser.HomeAppShortcutHelper
import com.example.browser.TrackerAndUrlEngine
import com.example.data.BrowserMode
import com.example.data.BrowserTab
import com.example.data.LinkMenuTarget
import com.example.data.PendingDownloadRequest
import com.example.data.SecurityConfig
import com.example.ui.theme.LocalShieldColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabSwitcherSheet(
    tabs: List<BrowserTab>,
    activeTabId: String,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewSurfaceTab: () -> Unit,
    onNewTorTab: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Open Tabs (${tabs.size})",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            onNewSurfaceTab()
                            onDismiss()
                        },
                        modifier = Modifier.testTag("new_surface_tab_button"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Surface")
                    }
                    Button(
                        onClick = {
                            onNewTorTab()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = shieldColors.torAccent,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                        ),
                        modifier = Modifier.testTag("new_tor_tab_button"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Tor Tab")
                    }
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(tabs, key = { it.id }) { tab ->
                    val isSelected = tab.id == activeTabId
                    val isTor = tab.mode == BrowserMode.TOR_ONION
                    val accent = if (isTor) shieldColors.torAccent else shieldColors.surfaceAccent

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable {
                                onSelectTab(tab.id)
                                onDismiss()
                            }
                            .testTag("tab_card_${tab.id}"),
                        shape = MaterialTheme.shapes.medium,
                        color = if (isSelected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (tab.isStartPage) {
                                Icon(
                                    imageVector = if (isTor) Icons.Rounded.Security else Icons.Rounded.Language,
                                    contentDescription = if (isTor) "Tor tab" else "Surface tab",
                                    tint = accent,
                                    modifier = Modifier.size(22.dp),
                                )
                            } else {
                                WebsiteLogo(
                                    url = tab.url,
                                    title = tab.title,
                                    accentColor = accent,
                                    size = 30.dp,
                                    cornerRadius = 8.dp,
                                    isOnion = tab.isOnionAddress || isTor,
                                    allowNetworkFetch = !isTor && !tab.isOnionAddress,
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = tab.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = accent.copy(alpha = 0.2f),
                                    ) {
                                        Text(
                                            text = if (isTor) "TOR" else "SURFACE",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = accent,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                                Text(
                                    text = if (tab.isStartPage) "Start Page • Seed #${tab.fingerprintSeed}" else tab.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { onCloseTab(tab.id) },
                                modifier = Modifier.testTag("close_tab_${tab.id}"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Close tab",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickShieldSheet(
    tab: BrowserTab,
    config: SecurityConfig,
    tabs: List<BrowserTab> = listOf(tab),
    totalTrackersBlocked: Int = tab.blockedTrackersCount,
    totalScriptsBlocked: Int = tab.blockedScriptsCount,
    isVpnConnected: Boolean = false,
    isBackgroundServiceRunning: Boolean = false,
    onToggleNoScript: (Boolean) -> Unit,
    onToggleFingerprint: (Boolean) -> Unit,
    onToggleHttpsEverywhere: (Boolean) -> Unit,
    onToggleWebRtc: (Boolean) -> Unit,
    onToggleTrackerBlocker: (Boolean) -> Unit,
    onTogglePopupBlocker: (Boolean) -> Unit = {},
    onToggleSiteAdBlockWhitelist: () -> Unit = {},
    onToggleThirdPartyCookies: (Boolean) -> Unit,
    onToggleBackgroundLiveService: (Boolean) -> Unit = {},
    onToggleBackgroundMedia: (Boolean) -> Unit = {},
    onRotateSeed: () -> Unit,
    onOpenCircuitDiagnostics: () -> Unit = {},
    onOpenFullSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    val accent = if (config.browserMode == BrowserMode.TOR_ONION) {
        shieldColors.torAccent
    } else {
        shieldColors.surfaceAccent
    }
    val dashboardMetrics = remember(
        tabs,
        tab,
        config,
        totalTrackersBlocked,
        totalScriptsBlocked,
        isVpnConnected,
    ) {
        PrivacyDashboardCalculator.computeMetrics(
            tabs = tabs,
            activeTab = tab,
            config = config,
            totalTrackersBlocked = totalTrackersBlocked,
            totalScriptsBlocked = totalScriptsBlocked,
            isVpnConnected = isVpnConnected,
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PrivacyDashboardCard(
                metrics = dashboardMetrics,
                accentColor = accent,
                onRotateSeed = onRotateSeed,
                onOpenShieldControls = {
                    onDismiss()
                    onOpenFullSettings()
                },
                compact = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Shield, contentDescription = null, tint = accent)
                    Column {
                        Text(
                            text = "Active Security Shields",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "${tab.blockedTrackersCount} ads/trackers • ${tab.blockedScriptsCount} scripts blocked",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(
                    onClick = {
                        onDismiss()
                        onOpenFullSettings()
                    },
                    modifier = Modifier.testTag("sheet_full_settings_button"),
                ) {
                    Text("All Settings")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            ShieldSwitchRow(
                title = "No-Script Mode",
                subtitle = "Disable JavaScript execution to neutralize exploit payloads",
                icon = Icons.Rounded.CodeOff,
                checked = config.noScriptEnabled,
                accent = accent,
                onCheckedChange = onToggleNoScript,
            )
            ShieldSwitchRow(
                title = "WebRTC Leak Protection",
                subtitle = "Block RTCPeerConnection & media enumeration to prevent IP leaks",
                icon = Icons.Rounded.VisibilityOff,
                checked = config.webRtcLeakProtectionEnabled,
                accent = accent,
                onCheckedChange = onToggleWebRtc,
            )
            ShieldSwitchRow(
                title = "HTTPS Everywhere",
                subtitle = "Upgrade clearnet HTTP traffic to TLS & block mixed content",
                icon = Icons.Rounded.Https,
                checked = config.httpsEverywhereEnabled,
                accent = accent,
                onCheckedChange = onToggleHttpsEverywhere,
            )
            ShieldSwitchRow(
                title = "Fingerprint Randomization",
                subtitle = "Spoof Canvas, WebGL & User-Agent (Seed #${tab.fingerprintSeed})",
                icon = Icons.Rounded.Fingerprint,
                checked = config.fingerprintRandomizationEnabled,
                accent = accent,
                onCheckedChange = onToggleFingerprint,
            )
            ShieldSwitchRow(
                title = "Ad & Tracker Blocker",
                subtitle = "Block ads, banners, tracking pixels & cosmetic ad boxes",
                icon = Icons.Rounded.Block,
                checked = config.trackerBlockerEnabled,
                accent = shieldColors.secureGreen,
                modifier = Modifier.testTag("sheet_ad_blocker_switch"),
                onCheckedChange = onToggleTrackerBlocker,
            )
            ShieldSwitchRow(
                title = "Popup & Redirect Shield",
                subtitle = "Block auto-popups, popunders & ad redirects",
                icon = Icons.Rounded.Shield,
                checked = config.popupBlockerEnabled,
                accent = shieldColors.secureGreen,
                modifier = Modifier.testTag("sheet_popup_blocker_switch"),
                onCheckedChange = onTogglePopupBlocker,
            )
            val currentHost = TrackerAndUrlEngine.normalizeHost(tab.url)
            if (!tab.isStartPage && currentHost != null) {
                val isSiteWhitelisted = TrackerAndUrlEngine.isSiteWhitelisted(tab.url, config.adBlockWhitelistHosts)
                ShieldSwitchRow(
                    title = "Ad Block for $currentHost",
                    subtitle = if (isSiteWhitelisted) "Ads allowed on this site (Paused)" else "Active — blocking ads on this site",
                    icon = Icons.Rounded.Block,
                    checked = !isSiteWhitelisted,
                    accent = shieldColors.secureGreen,
                    modifier = Modifier.testTag("site_adblock_whitelist_switch"),
                    onCheckedChange = { onToggleSiteAdBlockWhitelist() },
                )
            }
            ShieldSwitchRow(
                title = "Block 3rd-Party Cookies",
                subtitle = "Isolate site storage and reject cross-origin tracking cookies",
                icon = Icons.Rounded.Lock,
                checked = config.blockThirdPartyCookies,
                accent = accent,
                onCheckedChange = onToggleThirdPartyCookies,
            )

            val sheetContext = LocalContext.current
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) {
                    onToggleBackgroundLiveService(true)
                }
            }
            ShieldSwitchRow(
                title = "Keep Browser & Tor Live",
                subtitle = if (isBackgroundServiceRunning) {
                    "Foreground Service running with persistent notification"
                } else {
                    "Maintain Tor & session activity after leaving the app"
                },
                icon = Icons.Rounded.Security,
                checked = isBackgroundServiceRunning || config.backgroundLiveServiceEnabled,
                accent = shieldColors.secureGreen,
                modifier = Modifier.testTag("sheet_background_live_switch"),
                onCheckedChange = { wantEnable ->
                    if (!wantEnable) {
                        onToggleBackgroundLiveService(false)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            sheetContext,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onToggleBackgroundLiveService(true)
                    }
                },
            )
            ShieldSwitchRow(
                title = "Background YouTube & Media",
                subtitle = "Keep video songs & audio playing when exiting or minimizing the app",
                icon = Icons.Rounded.PlayCircle,
                checked = config.backgroundMediaPlaybackEnabled,
                accent = shieldColors.secureGreen,
                modifier = Modifier.testTag("sheet_background_media_switch"),
                onCheckedChange = onToggleBackgroundMedia,
            )

            OutlinedButton(
                onClick = onRotateSeed,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rotate_fingerprint_seed_button"),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Rotate Fingerprint Seed (${tab.userAgentLabel})")
            }

            Button(
                onClick = onOpenCircuitDiagnostics,
                colors = ButtonDefaults.buttonColors(containerColor = shieldColors.torAccent),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sheet_circuit_diagnostics_button"),
            ) {
                Icon(Icons.Rounded.Hub, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Tor Circuit & Bridge Diagnostics")
            }
        }
    }
}

@Composable
private fun ShieldSwitchRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = if (checked) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun DisclaimerDialog(
    onAccept: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    AlertDialog(
        onDismissRequest = {},
        icon = {
            Icon(
                imageVector = Icons.Rounded.Gavel,
                contentDescription = "Legal and security disclaimer",
                tint = shieldColors.warningAmber,
                modifier = Modifier.size(32.dp),
            )
        },
        title = {
            Text("Security & Legal Responsibility")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "OnionShield is engineered for lawful privacy, security research, and censorship-resistant browsing.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = shieldColors.warningAmberContainer.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, shieldColors.warningAmber.copy(alpha = 0.4f)),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Important Precautions:",
                            style = MaterialTheme.typography.titleSmall,
                            color = shieldColors.warningAmber,
                        )
                        Text(
                            text = "• Tor / .onion routing requires a local Tor SOCKS5/HTTP daemon (e.g., Orbot on 127.0.0.1:9050).\n" +
                                "• Keep No-Script enabled when visiting untrusted .onion services.\n" +
                                "• Avoid downloading unknown binaries or entering personal credentials.\n" +
                                "• You are solely responsible for complying with applicable local and international laws.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                modifier = Modifier.testTag("accept_disclaimer_button"),
            ) {
                Text("I Understand & Accept")
            }
        },
    )
}

@Composable
fun DownloadSandboxDialog(
    request: PendingDownloadRequest,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    val isApk = TrackerAndUrlEngine.isApkAsset(request.url, request.fileName, request.mimeType)
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                imageVector = when {
                    request.isOnionSource -> Icons.Rounded.WarningAmber
                    isApk -> Icons.Rounded.InstallMobile
                    else -> Icons.Rounded.Download
                },
                contentDescription = "Download security check",
                tint = if (request.isOnionSource) shieldColors.warningAmber else shieldColors.surfaceAccent,
                modifier = Modifier.size(30.dp),
            )
        },
        title = {
            Text(if (isApk) "APK Package Guard" else "Sandboxed Download Guard")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isApk) {
                        "Verify Android APK details before downloading. After it finishes, tap Install APK in Downloads Manager."
                    } else {
                        "Verify file details before saving to device storage:"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = request.fileName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (isApk) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = shieldColors.surfaceAccent.copy(alpha = 0.2f),
                                ) {
                                    Text(
                                        text = "APK",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = shieldColors.surfaceAccent,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            text = buildString {
                                append("Type: ${request.mimeType}")
                                if (request.contentLength > 0L) {
                                    append(" • ")
                                    append(com.example.browser.DownloadFileHelper.formatByteSize(request.contentLength))
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Origin: ${if (request.isOnionSource) "Tor / .onion Network (Caution)" else "Surface Web"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (request.isOnionSource) shieldColors.warningAmber else shieldColors.surfaceAccent,
                        )
                    }
                }
                if (request.isOnionSource) {
                    Text(
                        text = "Tor downloads are routed directly through your local Tor SOCKS5 proxy. Only install APKs or open files from trusted sources.",
                        style = MaterialTheme.typography.bodySmall,
                        color = shieldColors.warningAmber,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirm_download_button"),
            ) {
                Text(if (isApk) "Download APK" else "Download File")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("cancel_download_button"),
            ) {
                Text("Block & Cancel")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkActionSheet(
    target: LinkMenuTarget,
    isTorMode: Boolean = false,
    onInstallToHomeScreenAndMenu: (title: String, url: String) -> Unit,
    onPinToStartMenuOnly: (title: String, url: String) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onDownloadUrl: (String) -> Unit,
    onCopyUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    val isApk = TrackerAndUrlEngine.isApkAsset(target.url, null, null)
    val isOnion = TrackerAndUrlEngine.isOnionUrl(target.url)
    val displayTitle = HomeAppShortcutHelper.deriveDisplayTitle(target.title, target.url)
    val accent = when {
        isOnion -> shieldColors.torAccent
        else -> shieldColors.surfaceAccent
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WebsiteLogo(
                    url = target.url,
                    title = displayTitle,
                    accentColor = accent,
                    size = 38.dp,
                    cornerRadius = 10.dp,
                    isOnion = isOnion,
                    allowNetworkFetch = !isTorMode && !isOnion,
                )
                Text(
                    text = if (target.isSharedFromExternalApp) "Shared Link Ready" else displayTitle,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isApk) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = shieldColors.secureGreen.copy(alpha = 0.18f),
                    ) {
                        Text(
                            text = "APK",
                            style = MaterialTheme.typography.labelSmall,
                            color = shieldColors.secureGreen,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                if (isOnion) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = shieldColors.torAccent.copy(alpha = 0.2f),
                    ) {
                        Text(
                            text = ".ONION",
                            style = MaterialTheme.typography.labelSmall,
                            color = shieldColors.torAccent,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Text(
                text = target.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            )

            LinkActionRow(
                title = "Install to Mobile Home Screen & Menu",
                subtitle = "Create a device Home Screen shortcut & save in Home Apps",
                icon = Icons.Rounded.AddToHomeScreen,
                accent = accent,
                testTag = "link_action_install_home",
                onClick = {
                    onDismiss()
                    onInstallToHomeScreenAndMenu(displayTitle, target.url)
                },
            )

            LinkActionRow(
                title = "Save to Home Apps Mobile Menu",
                subtitle = "Pin to OnionShield's Start Page app grid",
                icon = Icons.Rounded.PushPin,
                accent = accent,
                testTag = "link_action_pin_menu",
                onClick = {
                    onDismiss()
                    onPinToStartMenuOnly(displayTitle, target.url)
                },
            )

            LinkActionRow(
                title = "Open Link in New Tab",
                subtitle = "Launch safely in an isolated tab",
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                accent = MaterialTheme.colorScheme.onSurface,
                testTag = "link_action_open_tab",
                onClick = {
                    onDismiss()
                    onOpenInNewTab(target.url)
                },
            )

            LinkActionRow(
                title = if (isApk) "Download APK for Installation" else "Download Linked File",
                subtitle = if (isApk) {
                    "Download .apk via sandbox guard & open Android installer"
                } else {
                    "Queue link in Sandboxed Downloads Manager"
                },
                icon = if (isApk) Icons.Rounded.InstallMobile else Icons.Rounded.Download,
                accent = if (isApk) shieldColors.secureGreen else MaterialTheme.colorScheme.onSurface,
                testTag = "link_action_download",
                onClick = {
                    onDismiss()
                    onDownloadUrl(target.url)
                },
            )

            LinkActionRow(
                title = "Copy Link Address",
                subtitle = "Copy full URL to device clipboard",
                icon = Icons.Rounded.ContentCopy,
                accent = MaterialTheme.colorScheme.onSurfaceVariant,
                testTag = "link_action_copy",
                onClick = {
                    onDismiss()
                    onCopyUrl(target.url)
                },
            )
        }
    }
}

@Composable
private fun LinkActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun ShredConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.DeleteSweep,
                contentDescription = "Shred session",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(30.dp),
            )
        },
        title = { Text("Shred Active Session?") },
        text = {
            Text(
                text = "This immediately destroys all open tabs, clears cookies, WebStorage, form data, WebView cache, and rotates your browser fingerprint seed.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("confirm_shred_button"),
            ) {
                Text("Shred Now")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

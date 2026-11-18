package com.example.ui.components

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CodeOff
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Https
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.browser.TrackerAndUrlEngine
import com.example.R
import com.example.data.BrowserMode
import com.example.data.BrowserTab
import com.example.data.PinnedAppEntity
import com.example.data.SecurityConfig
import com.example.data.TorProxyState
import com.example.data.VpnServerProfile
import com.example.data.VpnTunnelStatus
import com.example.ui.theme.LocalShieldColors

private data class QuickBookmarkLink(
    val title: String,
    val subtitle: String,
    val url: String,
    val isOnion: Boolean,
)

private val curatedQuickLinks = listOf(
    QuickBookmarkLink(
        title = "DuckDuckGo",
        subtitle = "Private Surface Search",
        url = "https://duckduckgo.com",
        isOnion = false,
    ),
    QuickBookmarkLink(
        title = "DuckDuckGo .onion",
        subtitle = "Tor Hidden Service Search",
        url = "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion",
        isOnion = true,
    ),
    QuickBookmarkLink(
        title = "Cover Your Tracks",
        subtitle = "EFF Browser Fingerprint Test",
        url = "https://coveryourtracks.eff.org",
        isOnion = false,
    ),
    QuickBookmarkLink(
        title = "Tor Check",
        subtitle = "Verify Exit Node & IP Masking",
        url = "https://check.torproject.org",
        isOnion = false,
    ),
    QuickBookmarkLink(
        title = "Wikipedia",
        subtitle = "Encyclopedia (HTTPS Enforced)",
        url = "https://en.wikipedia.org",
        isOnion = false,
    ),
)

@Composable
fun StartPageContent(
    tab: BrowserTab,
    config: SecurityConfig,
    torProxyState: TorProxyState,
    totalTrackersBlocked: Int,
    totalScriptsBlocked: Int,
    pinnedApps: List<PinnedAppEntity> = emptyList(),
    onNavigateUrl: (String) -> Unit,
    onInstallHomeApp: (title: String, url: String) -> Unit = { _, _ -> },
    onRemovePinnedApp: (Long) -> Unit = {},
    onToggleNoScript: (Boolean) -> Unit,
    onToggleHttpsEverywhere: (Boolean) -> Unit,
    onToggleWebRtc: (Boolean) -> Unit,
    onToggleFingerprint: (Boolean) -> Unit,
    onToggleAdBlocker: (Boolean) -> Unit = {},
    onTogglePopupBlocker: (Boolean) -> Unit = {},
    onTestTorSocket: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    vpnStatus: VpnTunnelStatus = VpnTunnelStatus.Disconnected,
    onConnectVpnAfterConsent: (VpnServerProfile) -> Unit = {},
    onDisconnectVpn: () -> Unit = {},
    onOpenVpnHub: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shieldColors = LocalShieldColors.current
    val vpnConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            onConnectVpnAfterConsent(config.vpnProfile)
        }
    }
    val isTor = config.browserMode == BrowserMode.TOR_ONION
    val accentColor by animateColorAsState(
        targetValue = if (isTor) shieldColors.torAccent else shieldColors.surfaceAccent,
        label = "modeAccent",
    )
    var isEditingApps by remember { mutableStateOf(false) }
    var showInstallAppDialog by remember { mutableStateOf(false) }

    val activeShieldCount = listOf(
        config.noScriptEnabled,
        config.fingerprintRandomizationEnabled,
        config.httpsEverywhereEnabled,
        config.webRtcLeakProtectionEnabled,
        config.trackerBlockerEnabled,
    ).count { it }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Hero Banner Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp),
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_privacy_banner_1794756388393),
                        contentDescription = "OnionShield encryption backdrop",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = accentColor.copy(alpha = 0.18f),
                                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(accentColor),
                                    )
                                    Text(
                                        text = if (isTor) "TOR / ONION ROUTING" else "SURFACE WEB HARDENED",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = accentColor,
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "OnionShield",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = if (isTor) {
                                    "Traffic routed via ${config.torEndpoint}"
                                } else {
                                    "HTTPS Everywhere, WebRTC Guard & Tracker Shield active"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                            ) {
                                Text(
                                    text = "SEED #${tab.fingerprintSeed} • ${tab.userAgentLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Internet VPN Quick-Connect & Free Web Links Strip
        item {
            val isVpnActive = vpnStatus is VpnTunnelStatus.Connected || vpnStatus is VpnTunnelStatus.Connecting
            val vpnAccent = if (vpnStatus is VpnTunnelStatus.Connected) shieldColors.secureGreen else shieldColors.surfaceAccent
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .testTag("start_vpn_connect_card"),
                shape = MaterialTheme.shapes.medium,
                color = vpnAccent.copy(alpha = 0.11f),
                border = BorderStroke(1.dp, vpnAccent.copy(alpha = 0.45f)),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = if (vpnStatus is VpnTunnelStatus.Connected) Icons.Rounded.VpnLock else Icons.Rounded.VpnKey,
                        contentDescription = "Internet VPN",
                        tint = vpnAccent,
                        modifier = Modifier.size(24.dp),
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = when (vpnStatus) {
                                is VpnTunnelStatus.Connected -> "VPN Connected (${vpnStatus.profile.badge})"
                                is VpnTunnelStatus.Connecting -> "Connecting Encrypted VPN…"
                                is VpnTunnelStatus.Error -> "VPN Attention Required"
                                VpnTunnelStatus.Disconnected -> "Internet VPN Ready"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = vpnAccent,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = when (vpnStatus) {
                                is VpnTunnelStatus.Connected ->
                                    "Tunnel ${vpnStatus.tunnelIp} • ${vpnStatus.queriesTunneled} encrypted DNS queries"
                                else ->
                                    "Connect encrypted DNS-over-HTTPS VPN & browse web links freely"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isVpnActive) MaterialTheme.colorScheme.error.copy(alpha = 0.2f) else vpnAccent.copy(alpha = 0.22f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (isVpnActive) {
                                        onDisconnectVpn()
                                    } else {
                                        val prepare = runCatching { VpnService.prepare(context) }.getOrNull()
                                        if (prepare != null) {
                                            vpnConsentLauncher.launch(prepare)
                                        } else {
                                            onConnectVpnAfterConsent(config.vpnProfile)
                                        }
                                    }
                                }
                                .testTag("start_vpn_toggle_button"),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .height(48.dp),
                            ) {
                                Text(
                                    text = if (isVpnActive) "Disconnect" else "Connect VPN",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isVpnActive) MaterialTheme.colorScheme.error else vpnAccent,
                                )
                            }
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onOpenVpnHub)
                                .height(48.dp)
                                .padding(horizontal = 8.dp)
                                .testTag("start_open_vpn_hub_button"),
                        ) {
                            Text(
                                text = "Web Links Hub",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // Tor Proxy Status Strip (when in Tor Mode)
        if (isTor) {
            item {
                TorStatusPanel(
                    torProxyState = torProxyState,
                    endpoint = config.torEndpoint,
                    onTestSocket = onTestTorSocket,
                    onConfigureProxy = onOpenSecuritySettings,
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }
        }

        // Telemetry Summary Row
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TelemetryMetricCard(
                    label = "Ads Blocked",
                    value = totalTrackersBlocked.toString(),
                    icon = Icons.Rounded.Block,
                    accent = shieldColors.secureGreen,
                    modifier = Modifier.weight(1f),
                )
                TelemetryMetricCard(
                    label = "Scripts Blocked",
                    value = totalScriptsBlocked.toString(),
                    icon = Icons.Rounded.CodeOff,
                    accent = shieldColors.warningAmber,
                    modifier = Modifier.weight(1f),
                )
                TelemetryMetricCard(
                    label = "Active Shields",
                    value = "$activeShieldCount/5",
                    icon = Icons.Rounded.VerifiedUser,
                    accent = accentColor,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Quick Security Shields Grid
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Privacy & Defense Layers",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "Configure All",
                        style = MaterialTheme.typography.labelLarge,
                        color = accentColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onOpenSecuritySettings)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("start_configure_all_button"),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ShieldToggleTile(
                        title = "No-Script",
                        subtitle = if (config.noScriptEnabled) "JS Blocked" else "JS Allowed",
                        enabled = config.noScriptEnabled,
                        icon = Icons.Rounded.CodeOff,
                        accent = accentColor,
                        testTag = "tile_noscript",
                        onClick = { onToggleNoScript(!config.noScriptEnabled) },
                        modifier = Modifier.weight(1f),
                    )
                    ShieldToggleTile(
                        title = "WebRTC Guard",
                        subtitle = if (config.webRtcLeakProtectionEnabled) "IP Leak Blocked" else "Standard",
                        enabled = config.webRtcLeakProtectionEnabled,
                        icon = Icons.Rounded.VisibilityOff,
                        accent = accentColor,
                        testTag = "tile_webrtc",
                        onClick = { onToggleWebRtc(!config.webRtcLeakProtectionEnabled) },
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ShieldToggleTile(
                        title = "HTTPS Force",
                        subtitle = if (config.httpsEverywhereEnabled) "Upgraded" else "Permissive",
                        enabled = config.httpsEverywhereEnabled,
                        icon = Icons.Rounded.Https,
                        accent = accentColor,
                        testTag = "tile_https",
                        onClick = { onToggleHttpsEverywhere(!config.httpsEverywhereEnabled) },
                        modifier = Modifier.weight(1f),
                    )
                    ShieldToggleTile(
                        title = "Fingerprint",
                        subtitle = if (config.fingerprintRandomizationEnabled) "Randomized" else "Native",
                        enabled = config.fingerprintRandomizationEnabled,
                        icon = Icons.Rounded.Fingerprint,
                        accent = accentColor,
                        testTag = "tile_fingerprint",
                        onClick = { onToggleFingerprint(!config.fingerprintRandomizationEnabled) },
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ShieldToggleTile(
                        title = "Ad Blocker",
                        subtitle = if (config.trackerBlockerEnabled) "Ads & Pixels Blocked" else "Paused",
                        enabled = config.trackerBlockerEnabled,
                        icon = Icons.Rounded.Block,
                        accent = shieldColors.secureGreen,
                        testTag = "tile_adblock",
                        onClick = { onToggleAdBlocker(!config.trackerBlockerEnabled) },
                        modifier = Modifier.weight(1f),
                    )
                    ShieldToggleTile(
                        title = "Popup Shield",
                        subtitle = if (config.popupBlockerEnabled) "Popups Blocked" else "Allowed",
                        enabled = config.popupBlockerEnabled,
                        icon = Icons.Rounded.Shield,
                        accent = shieldColors.secureGreen,
                        testTag = "tile_popup_blocker",
                        onClick = { onTogglePopupBlocker(!config.popupBlockerEnabled) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Home Apps & PWAs Launcher Grid
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AddToHomeScreen,
                            contentDescription = "Home Apps & PWAs",
                            tint = accentColor,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Home Apps & PWAs",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (pinnedApps.isNotEmpty()) {
                            Text(
                                text = if (isEditingApps) "Done" else "Edit",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { isEditingApps = !isEditingApps }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .testTag("home_apps_edit_button"),
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = accentColor.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { showInstallAppDialog = true }
                                .testTag("install_home_app_button"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "Install Home App",
                                    tint = accentColor,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "Install App",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = accentColor,
                                )
                            }
                        }
                    }
                }

                if (pinnedApps.isEmpty()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { showInstallAppDialog = true },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    ) {
                        Text(
                            text = "Tap + Install App to pin any website (YouTube, News, Web Apps) to Home Apps & your phone Home Screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                } else {
                    pinnedApps.chunked(4).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rowItems.forEach { app ->
                                val tileAccent = if (app.isOnion) shieldColors.torAccent else accentColor
                                val badgeLetters = app.title.trim()
                                    .split(" ")
                                    .filter { it.isNotBlank() }
                                    .take(2)
                                    .joinToString("") { it.first().uppercaseChar().toString() }
                                    .ifBlank { "APP" }
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(MaterialTheme.shapes.medium)
                                        .clickable {
                                            if (isEditingApps) {
                                                onRemovePinnedApp(app.id)
                                            } else {
                                                onNavigateUrl(app.url)
                                            }
                                        }
                                        .testTag("home_app_tile_${app.id}"),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isEditingApps) MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                    ),
                                ) {
                                    Box(modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp)) {
                                        if (isEditingApps) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .align(Alignment.TopCenter),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.AddToHomeScreen,
                                                    contentDescription = "Pin ${app.title} to Phone Home",
                                                    tint = tileAccent,
                                                    modifier = Modifier
                                                        .size(18.dp)
                                                        .clickable {
                                                            onInstallHomeApp(app.title, app.url)
                                                        },
                                                )
                                                Icon(
                                                    imageVector = Icons.Rounded.Close,
                                                    contentDescription = "Remove ${app.title}",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = if (isEditingApps) 12.dp else 0.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(tileAccent.copy(alpha = 0.16f)),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = badgeLetters,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    color = tileAccent,
                                                )
                                            }
                                            Text(
                                                text = app.title,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                }
                            }
                            repeat(4 - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // Curated Quick Access Links
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Quick Launch Destinations",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                curatedQuickLinks.forEach { link ->
                    val linkColor = if (link.isOnion) shieldColors.torAccent else shieldColors.surfaceAccent
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onNavigateUrl(link.url) }
                            .testTag("quick_link_${link.title.lowercase().replace(" ", "_")}"),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(linkColor.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (link.isOnion) Icons.Rounded.Security else Icons.Rounded.TravelExplore,
                                    contentDescription = link.title,
                                    tint = linkColor,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = link.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (link.isOnion) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = shieldColors.torAccent.copy(alpha = 0.2f),
                                        ) {
                                            Text(
                                                text = ".ONION",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = shieldColors.torAccent,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = link.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = "Open ${link.title}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showInstallAppDialog) {
        InstallHomeAppDialog(
            onDismiss = { showInstallAppDialog = false },
            onConfirm = { title, url ->
                showInstallAppDialog = false
                onInstallHomeApp(title, url)
            },
        )
    }
}

@Composable
fun InstallHomeAppDialog(
    initialTitle: String = "",
    initialUrl: String = "",
    onDismiss: () -> Unit,
    onConfirm: (title: String, url: String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var appTitle by remember(initialTitle) { mutableStateOf(initialTitle) }
    var appUrl by remember(initialUrl) { mutableStateOf(initialUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install Any Link to Home") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Save any link to OnionShield's Home Apps menu and create a shortcut on your mobile Home Screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = appTitle,
                    onValueChange = { appTitle = it },
                    label = { Text("App Name (optional)") },
                    placeholder = { Text("e.g. YouTube") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("install_app_title_input"),
                )
                OutlinedTextField(
                    value = appUrl,
                    onValueChange = { appUrl = it },
                    label = { Text("Website or Link URL") },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("install_app_url_input"),
                )
                TextButton(
                    onClick = {
                        val clip = clipboard.getText()?.text.orEmpty()
                        val extracted = TrackerAndUrlEngine.extractFirstHttpUrl(clip) ?: clip.trim()
                        if (extracted.isNotBlank()) {
                            appUrl = extracted
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("Paste Link from Clipboard")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(appTitle, appUrl) },
                enabled = appUrl.trim().isNotEmpty(),
                modifier = Modifier.testTag("install_app_confirm_button"),
            ) {
                Text("Install & Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun TorStatusPanel(
    torProxyState: TorProxyState,
    endpoint: String,
    onTestSocket: () -> Unit,
    onConfigureProxy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shieldColors = LocalShieldColors.current
    val (statusText, detailText, statusColor) = when (torProxyState) {
        is TorProxyState.Connected -> Triple(
            "Tor Proxy Connected (${torProxyState.latencyMs} ms)",
            "SOCKS/HTTP socket verified at ${torProxyState.endpoint}",
            shieldColors.secureGreen,
        )
        is TorProxyState.Checking -> Triple(
            "Probing Tor Socket…",
            "Checking $endpoint",
            shieldColors.torAccent,
        )
        is TorProxyState.Unreachable -> Triple(
            "Tor Proxy Unreachable",
            "Start Orbot or a local Tor proxy at $endpoint for .onion routing.",
            shieldColors.warningAmber,
        )
        is TorProxyState.UnsupportedApi -> Triple(
            "WebView Proxy Override Unavailable",
            torProxyState.reason,
            MaterialTheme.colorScheme.error,
        )
        TorProxyState.Inactive -> Triple(
            "Tor Proxy Standby",
            endpoint,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = statusColor.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = statusText,
                tint = statusColor,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleSmall,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.2f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onTestSocket)
                        .testTag("test_tor_socket_button"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Test socket",
                            tint = statusColor,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Test",
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor,
                        )
                    }
                }
                Text(
                    text = "Proxy Setup",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(onClick = onConfigureProxy)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun TelemetryMetricCard(
    label: String,
    value: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ShieldToggleTile(
    title: String,
    subtitle: String,
    enabled: Boolean,
    icon: ImageVector,
    accent: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileColor = if (enabled) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface
    val borderColor = if (enabled) accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = MaterialTheme.shapes.medium,
        color = tileColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (enabled) accent.copy(alpha = 0.22f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

package com.example.ui.screens

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.browser.TrackerAndUrlEngine
import com.example.data.VpnServerProfile
import com.example.data.VpnTunnelStatus
import com.example.ui.theme.LocalShieldColors

private data class FreeWebLinkDestination(
    val title: String,
    val subtitle: String,
    val url: String,
    val badge: String,
)

private val freeOpenWebLinks = listOf(
    FreeWebLinkDestination(
        title = "DuckDuckGo Search",
        subtitle = "Private search engine without tracking",
        url = "https://duckduckgo.com",
        badge = "Search",
    ),
    FreeWebLinkDestination(
        title = "Wikipedia",
        subtitle = "Free global encyclopedia (HTTPS)",
        url = "https://en.wikipedia.org",
        badge = "Knowledge",
    ),
    FreeWebLinkDestination(
        title = "YouTube",
        subtitle = "Watch videos & background audio playback",
        url = "https://m.youtube.com",
        badge = "Video",
    ),
    FreeWebLinkDestination(
        title = "Internet Archive",
        subtitle = "Free library of websites, books & media",
        url = "https://archive.org",
        badge = "Archive",
    ),
    FreeWebLinkDestination(
        title = "GitHub",
        subtitle = "Open-source repositories & code",
        url = "https://github.com",
        badge = "Dev",
    ),
    FreeWebLinkDestination(
        title = "BBC News",
        subtitle = "Global news coverage",
        url = "https://www.bbc.com",
        badge = "News",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnConnectScreen(
    selectedProfile: VpnServerProfile,
    vpnStatus: VpnTunnelStatus,
    onSelectProfile: (VpnServerProfile) -> Unit,
    onConnectAfterConsent: (VpnServerProfile) -> Unit,
    onDisconnect: () -> Unit,
    onOpenWebLink: (String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val shieldColors = LocalShieldColors.current

    var linkInput by remember { mutableStateOf("") }
    var permissionError by remember { mutableStateOf<String?>(null) }

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            permissionError = null
            onConnectAfterConsent(selectedProfile)
        } else {
            permissionError = "VPN connection permission was cancelled by user."
        }
    }

    fun requestConnectVpn() {
        permissionError = null
        val prepareIntent = runCatching { VpnService.prepare(context) }.getOrNull()
        if (prepareIntent != null) {
            vpnConsentLauncher.launch(prepareIntent)
        } else {
            onConnectAfterConsent(selectedProfile)
        }
    }

    val isConnected = vpnStatus is VpnTunnelStatus.Connected
    val isConnecting = vpnStatus is VpnTunnelStatus.Connecting
    val statusColor by animateColorAsState(
        targetValue = when (vpnStatus) {
            is VpnTunnelStatus.Connected -> shieldColors.secureGreen
            is VpnTunnelStatus.Connecting -> shieldColors.surfaceAccent
            is VpnTunnelStatus.Error -> MaterialTheme.colorScheme.error
            VpnTunnelStatus.Disconnected -> shieldColors.warningAmber
        },
        label = "vpnStatusAccent",
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Internet VPN & Web Links")
                        Text(
                            text = if (isConnected) {
                                "Connected • ${selectedProfile.badge}"
                            } else {
                                "Encrypted DoH VPN Tunnel"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("vpn_back_button"),
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
                .navigationBarsPadding()
                .testTag("vpn_connect_screen"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 1. Main VPN Power & Telemetry Card
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.55f)),
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(statusColor, shieldColors.surfaceAccent, shieldColors.torAccent),
                                    ),
                                ),
                        )
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = statusColor.copy(alpha = 0.16f),
                                    border = BorderStroke(1.5.dp, statusColor.copy(alpha = 0.6f)),
                                    modifier = Modifier.size(54.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (isConnected) Icons.Rounded.VpnLock else Icons.Rounded.VpnKey,
                                            contentDescription = "VPN tunnel status",
                                            tint = statusColor,
                                            modifier = Modifier.size(28.dp),
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = when (vpnStatus) {
                                            is VpnTunnelStatus.Connected -> "VPN Tunnel Active"
                                            is VpnTunnelStatus.Connecting -> "Establishing VPN Tunnel…"
                                            is VpnTunnelStatus.Error -> "VPN Tunnel Attention"
                                            VpnTunnelStatus.Disconnected -> "VPN Disconnected"
                                        },
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = statusColor,
                                    )
                                    Text(
                                        text = when (vpnStatus) {
                                            is VpnTunnelStatus.Connected ->
                                                "Encrypted DNS-over-HTTPS via ${vpnStatus.profile.displayName} • Browser links open at full speed."
                                            is VpnTunnelStatus.Error -> vpnStatus.reason
                                            else ->
                                                "Connect to encrypt DNS lookups over TLS and open web links privately inside OnionShield."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            if (vpnStatus is VpnTunnelStatus.Connected) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    VpnStatChip(
                                        label = "Tunnel IP",
                                        value = vpnStatus.tunnelIp,
                                        accent = shieldColors.secureGreen,
                                        modifier = Modifier.weight(1f),
                                    )
                                    VpnStatChip(
                                        label = "Queries",
                                        value = vpnStatus.queriesTunneled.toString(),
                                        accent = shieldColors.surfaceAccent,
                                        modifier = Modifier.weight(1f),
                                    )
                                    VpnStatChip(
                                        label = "Trackers",
                                        value = vpnStatus.blockedQueries.toString(),
                                        accent = shieldColors.torAccent,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (vpnStatus.lastHost.isNotBlank()) {
                                    Text(
                                        text = "Last resolved host: ${vpnStatus.lastHost}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            permissionError?.let { err ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.errorContainer,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.WarningAmber,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = err,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    if (isConnected || isConnecting) {
                                        onDisconnect()
                                    } else {
                                        requestConnectVpn()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isConnected) MaterialTheme.colorScheme.error else shieldColors.secureGreen,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("vpn_power_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PowerSettingsNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (isConnected || isConnecting) "Disconnect VPN" else "Connect Internet VPN",
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }

            // 2. VPN Gateway / Server Profile Selector
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = shieldColors.surfaceAccent)
                            Column {
                                Text("Encrypted Gateway Profile", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "Choose an encrypted DoH VPN resolver",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        VpnServerProfile.entries.chunked(2).forEach { rowProfiles ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                rowProfiles.forEach { profile ->
                                    val selected = selectedProfile == profile
                                    FilterChip(
                                        selected = selected,
                                        onClick = { onSelectProfile(profile) },
                                        label = {
                                            Text(
                                                text = "${profile.displayName} (${profile.badge})",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("vpn_profile_${profile.name}"),
                                    )
                                }
                            }
                        }

                        Text(
                            text = selectedProfile.summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 3. Direct Web Link Launcher ("Use Any Web Link Freely")
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, shieldColors.surfaceAccent.copy(alpha = 0.45f)),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.Public, contentDescription = null, tint = shieldColors.surfaceAccent)
                            Column {
                                Text("Browse Any Web Link Freely", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "Paste or enter any website link to open directly in OnionShield Browser",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        OutlinedTextField(
                            value = linkInput,
                            onValueChange = { linkInput = it },
                            label = { Text("Enter URL or web search") },
                            placeholder = { Text("https://example.org or search query…") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    if (linkInput.isNotBlank()) {
                                        onOpenWebLink(linkInput.trim())
                                    }
                                },
                            ),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val clip = clipboard.getText()?.text.orEmpty()
                                        val extracted = TrackerAndUrlEngine.extractFirstHttpUrl(clip) ?: clip.trim()
                                        if (extracted.isNotBlank()) {
                                            linkInput = extracted
                                        }
                                    },
                                ) {
                                    Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste link")
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("vpn_link_input"),
                        )

                        Button(
                            onClick = {
                                val target = linkInput.trim()
                                if (target.isNotEmpty()) {
                                    onOpenWebLink(target)
                                }
                            },
                            enabled = linkInput.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("vpn_open_link_button"),
                        ) {
                            Icon(Icons.Rounded.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Open Link in Browser")
                        }
                    }
                }
            }

            // 4. Free Open Web Links Directory
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Free Web Quick Links",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )

                    freeOpenWebLinks.forEach { link ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable { onOpenWebLink(link.url) }
                                .testTag("vpn_free_link_${link.title.lowercase().replace(" ", "_")}"),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = shieldColors.surfaceAccent.copy(alpha = 0.14f),
                                    modifier = Modifier.size(38.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Rounded.Lock,
                                            contentDescription = null,
                                            tint = shieldColors.surfaceAccent,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = link.title,
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = shieldColors.secureGreen.copy(alpha = 0.16f),
                                        ) {
                                            Text(
                                                text = link.badge,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = shieldColors.secureGreen,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                            )
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
    }
}

@Composable
private fun VpnStatChip(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

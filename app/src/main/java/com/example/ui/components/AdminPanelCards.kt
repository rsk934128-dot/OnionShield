package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.BrowserMode
import com.example.data.CircuitPrivacyLevel
import com.example.data.SecurityConfig
import com.example.data.TorProxyState
import com.example.ui.theme.LocalShieldColors

@Composable
internal fun AdminHeroScoreCard(
    config: SecurityConfig,
    torProxyState: TorProxyState,
    isForegroundServiceRunning: Boolean,
    isAdminVerified: Boolean,
    onVerifyAdminClick: () -> Unit,
    onLockdownClick: () -> Unit,
    onStandardDefaultsClick: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    val score = remember(config) { computeAdminSecurityScore(config) }
    val scoreColor = when {
        score >= 85 -> shieldColors.secureGreen
        score >= 60 -> shieldColors.warningAmber
        else -> MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .testTag("admin_hero_card"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, scoreColor.copy(alpha = 0.55f)),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(shieldColors.torAccent, shieldColors.surfaceAccent, shieldColors.secureGreen),
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
                            imageVector = Icons.Rounded.AdminPanelSettings,
                            contentDescription = "Admin control center",
                            tint = shieldColors.torAccent,
                            modifier = Modifier.size(30.dp),
                        )
                        Column {
                            Text("System Security Posture", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = when {
                                    config.browserMode == BrowserMode.TOR_ONION && torProxyState is TorProxyState.Connected ->
                                        "Tor Mode • Proxy Connected (${torProxyState.latencyMs}ms)"
                                    config.browserMode == BrowserMode.TOR_ONION ->
                                        "Tor Mode • Waiting for SOCKS5 Proxy"
                                    else -> "Surface Web Mode • Hardened"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = scoreColor.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, scoreColor.copy(alpha = 0.5f)),
                    ) {
                        Text(
                            text = "$score%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = scoreColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }

                LinearProgressIndicator(
                    progress = { score / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = scoreColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AdminStatusTag(
                        label = if (isForegroundServiceRunning) "Keep-Alive: LIVE" else "Keep-Alive: Idle",
                        color = if (isForegroundServiceRunning) shieldColors.secureGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AdminStatusTag(
                        label = if (isAdminVerified) "Admin Verified" else "Device Check Ready",
                        color = if (isAdminVerified) shieldColors.secureGreen else shieldColors.torAccent,
                    )
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = onVerifyAdminClick,
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("admin_verify_biometric_button"),
                    ) {
                        Icon(Icons.Rounded.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isAdminVerified) "Verified" else "Verify")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onStandardDefaultsClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("admin_standard_policy_button"),
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Standard", maxLines = 1)
                    }
                    Button(
                        onClick = onLockdownClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("admin_lockdown_policy_button"),
                    ) {
                        Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Lockdown", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminStatusTag(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun AdminTelemetryGridCard(
    openTabsCount: Int,
    trackersBlocked: Int,
    scriptsBlocked: Int,
    bookmarksCount: Int,
    historyCount: Int,
    downloadsCount: Int,
) {
    val shieldColors = LocalShieldColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .testTag("admin_telemetry_card"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Live Telemetry & Storage Audit", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TelemetryPill("Open Tabs", openTabsCount.toString(), shieldColors.surfaceAccent, Modifier.weight(1f))
                TelemetryPill("Ads Blocked", trackersBlocked.toString(), shieldColors.secureGreen, Modifier.weight(1f))
                TelemetryPill("Scripts Blocked", scriptsBlocked.toString(), shieldColors.warningAmber, Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TelemetryPill("Bookmarks", bookmarksCount.toString(), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                TelemetryPill("History", historyCount.toString(), shieldColors.torAccent, Modifier.weight(1f))
                TelemetryPill("Downloads", downloadsCount.toString(), MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TelemetryPill(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = accent)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
internal fun AdminPolicyRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdminDomainGovernanceCard(
    config: SecurityConfig,
    fingerprintSeed: String,
    onRotateSeed: () -> Unit,
    onSelectCircuitLevel: (CircuitPrivacyLevel) -> Unit,
    onAddBlockedDomain: (String) -> Unit,
    onRemoveBlockedDomain: (String) -> Unit,
    onRemoveWhitelistHost: (String) -> Unit,
    onRemoveTrustedJsHost: (String) -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    var domainInput by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .testTag("admin_governance_card"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Circuit, Identity & Domain Governance", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Fingerprint Seed #$fingerprintSeed", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Rotate canvas, WebGL & User-Agent seed immediately",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = onRotateSeed,
                    modifier = Modifier.height(48.dp).testTag("admin_rotate_seed_button"),
                ) {
                    Icon(Icons.Rounded.Fingerprint, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Rotate")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircuitPrivacyLevel.entries.forEach { level ->
                    FilterChip(
                        selected = config.circuitPrivacyLevel == level,
                        onClick = { onSelectCircuitLevel(level) },
                        label = { Text("${level.label} Circuit") },
                        modifier = Modifier.testTag("admin_circuit_${level.name.lowercase()}"),
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = domainInput,
                    onValueChange = { domainInput = it },
                    label = { Text("Block domain rule") },
                    placeholder = { Text("tracker.example.com") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("admin_block_domain_input"),
                )
                Button(
                    onClick = {
                        onAddBlockedDomain(domainInput)
                        domainInput = ""
                    },
                    modifier = Modifier.height(48.dp).testTag("admin_block_domain_button"),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add blocked domain")
                }
            }

            if (config.customBlockedDomains.isNotEmpty()) {
                Text("Blocked Domains (${config.customBlockedDomains.size})", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    config.customBlockedDomains.sorted().forEach { domain ->
                        FilterChip(
                            selected = true,
                            onClick = { onRemoveBlockedDomain(domain) },
                            label = { Text(domain) },
                            trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove $domain", modifier = Modifier.size(14.dp)) },
                        )
                    }
                }
            }

            if (config.adBlockWhitelistHosts.isNotEmpty()) {
                Text("Ad-Block Exceptions (${config.adBlockWhitelistHosts.size})", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    config.adBlockWhitelistHosts.sorted().forEach { host ->
                        FilterChip(
                            selected = false,
                            onClick = { onRemoveWhitelistHost(host) },
                            label = { Text(host) },
                            trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Re-enable ad block on $host", modifier = Modifier.size(14.dp)) },
                        )
                    }
                }
            }

            if (config.trustedJsHosts.isNotEmpty()) {
                Text("Trusted JavaScript Hosts (${config.trustedJsHosts.size})", style = MaterialTheme.typography.labelMedium, color = shieldColors.warningAmber)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    config.trustedJsHosts.sorted().forEach { host ->
                        FilterChip(
                            selected = true,
                            onClick = { onRemoveTrustedJsHost(host) },
                            label = { Text(host) },
                            trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Revoke JS on $host", modifier = Modifier.size(14.dp)) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdminMaintenanceAndShortcutsCard(
    isVaultUnlocked: Boolean,
    onRequestShredSession: () -> Unit,
    onRequestClearHistory: () -> Unit,
    onRequestClearDownloads: () -> Unit,
    onLockVault: () -> Unit,
    onTestTorProxy: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onOpenAdminSettings: () -> Unit,
    onOpenCloudSync: () -> Unit,
    onOpenSessionGuard: () -> Unit,
    onOpenVault: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .testTag("admin_maintenance_card"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Maintenance & Quick Modules", style = MaterialTheme.typography.titleMedium)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRequestShredSession,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f).height(48.dp).testTag("admin_shred_session_button"),
                ) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Shred Session", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onTestTorProxy,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("admin_test_tor_button"),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Test Tor", maxLines = 1)
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionPillButton(Icons.Rounded.History, "Clear History", onRequestClearHistory, "admin_clear_history_button")
                ActionPillButton(Icons.Rounded.Block, "Clear Downloads", onRequestClearDownloads, "admin_clear_downloads_button")
                if (isVaultUnlocked) {
                    ActionPillButton(Icons.Rounded.Lock, "Lock Vault", onLockVault, "admin_lock_vault_button")
                }
            }

            HorizontalDivider()

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionPillButton(Icons.Rounded.AdminPanelSettings, "Server Config & Logs", onOpenAdminSettings, "admin_nav_server_settings_button")
                ActionPillButton(Icons.Rounded.Settings, "Privacy Settings", onOpenSecuritySettings, "admin_nav_settings_button")
                ActionPillButton(Icons.Rounded.CloudSync, "Cloud Sync", onOpenCloudSync, "admin_nav_cloud_button")
                ActionPillButton(Icons.Rounded.Timer, "Session Guard", onOpenSessionGuard, "admin_nav_guard_button")
                ActionPillButton(Icons.Rounded.EnhancedEncryption, "Secure Vault", onOpenVault, "admin_nav_vault_button")
            }
        }
    }
}

@Composable
private fun ActionPillButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    testTag: String,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(48.dp).testTag(testTag),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}

internal fun computeAdminSecurityScore(config: SecurityConfig): Int {
    var score = 0
    if (config.httpsEverywhereEnabled) score += 15
    if (config.webRtcLeakProtectionEnabled) score += 15
    if (config.trackerBlockerEnabled) score += 15
    if (config.popupBlockerEnabled) score += 10
    if (config.cosmeticAdBlockEnabled) score += 10
    if (config.fingerprintRandomizationEnabled) score += 10
    if (config.blockThirdPartyCookies) score += 10
    if (config.noScriptEnabled) score += 10
    if (!config.saveSurfaceHistory) score += 5
    return score.coerceIn(0, 100)
}

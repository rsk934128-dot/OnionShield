package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.CircuitPrivacyLevel
import com.example.data.ProxyProtocol
import com.example.data.SearchEngine
import com.example.data.SecurityConfig
import com.example.data.TorProxyState
import com.example.ui.theme.LocalShieldColors

@Composable
internal fun BackgroundKeepAliveCard(
    isForegroundServiceRunning: Boolean,
    isEnabledPreference: Boolean,
    notificationDeniedNote: String?,
    onToggle: (Boolean) -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    val activeOrEnabled = isForegroundServiceRunning || isEnabledPreference

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isForegroundServiceRunning) {
                shieldColors.secureGreen.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Background Browser & Tor Keep-Alive",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (isForegroundServiceRunning) {
                            "ACTIVE • Persistent notification keeps OnionShield alive, polls Tor proxy health, and preserves session state when minimized."
                        } else {
                            "Run a persistent Foreground Service notification so the browser and Tor connectivity stay active after leaving the app."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isForegroundServiceRunning) {
                            shieldColors.secureGreen
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Switch(
                    checked = activeOrEnabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("settings_background_live_switch"),
                )
            }
            if (notificationDeniedNote != null) {
                Text(
                    text = notificationDeniedNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
internal fun TorProxyRoutingCard(
    config: SecurityConfig,
    torProxyState: TorProxyState,
    orbotInstalled: Boolean,
    onSaveAndTest: (host: String, port: Int, protocol: ProxyProtocol) -> Unit,
    onLaunchOrbot: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    var hostInput by remember(config.torProxyHost) { mutableStateOf(config.torProxyHost) }
    var portInput by remember(config.torProxyPort) { mutableStateOf(config.torProxyPort.toString()) }
    var selectedProtocol by remember(config.torProxyProtocol) { mutableStateOf(config.torProxyProtocol) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, shieldColors.torAccent.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Router, contentDescription = null, tint = shieldColors.torAccent)
                Column {
                    Text("Tor / Onion Proxy Routing", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Routes WebView traffic via ProxyController when Tor mode is active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProxyProtocol.entries.forEach { protocol ->
                    FilterChip(
                        selected = selectedProtocol == protocol,
                        onClick = {
                            selectedProtocol = protocol
                            portInput = protocol.defaultPort.toString()
                        },
                        label = { Text(protocol.label) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = hostInput,
                    onValueChange = { hostInput = it },
                    label = { Text("Proxy Host") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it.filter(Char::isDigit) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            val statusText = when (torProxyState) {
                is TorProxyState.Connected -> "Connected to ${torProxyState.endpoint} (${torProxyState.latencyMs} ms)"
                is TorProxyState.Checking -> "Checking socket reachability…"
                is TorProxyState.Unreachable -> "Unreachable at ${torProxyState.endpoint}: ${torProxyState.reason}"
                is TorProxyState.UnsupportedApi -> torProxyState.reason
                TorProxyState.Inactive -> "Standby (Surface Web Mode active)"
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = when (torProxyState) {
                    is TorProxyState.Connected -> shieldColors.secureGreen
                    is TorProxyState.Unreachable -> shieldColors.warningAmber
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val port = portInput.toIntOrNull() ?: selectedProtocol.defaultPort
                        onSaveAndTest(hostInput, port, selectedProtocol)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("save_test_proxy_button"),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save & Test")
                }
                OutlinedButton(
                    onClick = onLaunchOrbot,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("launch_orbot_button"),
                ) {
                    Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (orbotInstalled) "Start Orbot" else "Get Orbot")
                }
            }

            OutlinedButton(
                onClick = onOpenDiagnostics,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("settings_circuit_diagnostics_button"),
            ) {
                Icon(Icons.Rounded.Hub, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("V3 .onion Circuit & Bridge Diagnostics (${config.bridgeTransport.label})")
            }
        }
    }
}

@Composable
internal fun CircuitPrivacyLevelCard(
    currentLevel: CircuitPrivacyLevel,
    onSelectLevel: (CircuitPrivacyLevel) -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Security, contentDescription = null, tint = shieldColors.surfaceAccent)
                Column {
                    Text("Tor Circuit Privacy Level", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = currentLevel.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircuitPrivacyLevel.entries.forEach { level ->
                    FilterChip(
                        selected = currentLevel == level,
                        onClick = { onSelectLevel(level) },
                        label = { Text(level.label) },
                        modifier = Modifier.testTag("privacy_level_${level.name.lowercase()}"),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomExceptionsAndAdBlockCard(
    config: SecurityConfig,
    totalTrackersBlocked: Int,
    onAddBlockedDomain: (String) -> Unit,
    onRemoveBlockedDomain: (String) -> Unit,
    onToggleAdWhitelistHost: (String) -> Unit,
    onRemoveTrustedJsHost: (String) -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    var customDomainInput by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, shieldColors.secureGreen.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Block, contentDescription = null, tint = shieldColors.secureGreen)
                Column {
                    Text("Site Exceptions & Custom Blocklist", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Session blocked: $totalTrackersBlocked ads/trackers • 90+ built-in ad networks active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = customDomainInput,
                    onValueChange = { customDomainInput = it },
                    label = { Text("Block custom domain") },
                    placeholder = { Text("e.g. ads.example.com") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("custom_ad_domain_input"),
                )
                Button(
                    onClick = {
                        onAddBlockedDomain(customDomainInput)
                        customDomainInput = ""
                    },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("add_custom_ad_domain_button"),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add blocked domain", modifier = Modifier.size(18.dp))
                }
            }

            if (config.customBlockedDomains.isNotEmpty()) {
                Text("Custom Blocked Domains", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    config.customBlockedDomains.sorted().forEach { domain ->
                        FilterChip(
                            selected = true,
                            onClick = { onRemoveBlockedDomain(domain) },
                            label = { Text(domain) },
                            trailingIcon = {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "Remove $domain",
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                        )
                    }
                }
            }

            if (config.adBlockWhitelistHosts.isNotEmpty()) {
                Text("Allowed Sites (Ad Block Paused)", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    config.adBlockWhitelistHosts.sorted().forEach { host ->
                        FilterChip(
                            selected = false,
                            onClick = { onToggleAdWhitelistHost(host) },
                            label = { Text(host) },
                            trailingIcon = {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "Re-enable ad block on $host",
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                        )
                    }
                }
            }

            if (config.trustedJsHosts.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Rounded.Code,
                        contentDescription = null,
                        tint = shieldColors.warningAmber,
                        modifier = Modifier.size(16.dp),
                    )
                    Text("Trusted JavaScript Sites (No-Script Exceptions)", style = MaterialTheme.typography.labelMedium)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    config.trustedJsHosts.sorted().forEach { host ->
                        FilterChip(
                            selected = true,
                            onClick = { onRemoveTrustedJsHost(host) },
                            label = { Text(host) },
                            modifier = Modifier.testTag("trusted_js_chip_$host"),
                            trailingIcon = {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "Revoke JavaScript trust for $host",
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchEngineCard(
    currentEngine: SearchEngine,
    onSelectEngine: (SearchEngine) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Default Search Engine", style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchEngine.entries.forEach { engine ->
                    FilterChip(
                        selected = currentEngine == engine,
                        onClick = { onSelectEngine(engine) },
                        label = { Text(engine.displayName) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun LegalDisclaimerCard(
    onOpenDisclaimer: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .clickable(onClick = onOpenDisclaimer),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Gavel, contentDescription = null, tint = shieldColors.warningAmber)
            Column(modifier = Modifier.weight(1f)) {
                Text("Legal & Security Disclaimer", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Review operational security rules and Tor usage guidelines",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

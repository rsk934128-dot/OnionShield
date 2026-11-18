package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.browser.TorCircuitService
import com.example.browser.TorProxyController
import com.example.data.BridgeTransport
import com.example.data.BrowserMode
import com.example.data.BrowserTab
import com.example.data.OnionAddressStatus
import com.example.data.SecurityConfig
import com.example.data.TorCircuitDiagnostic
import com.example.data.TorProxyState
import com.example.ui.theme.LocalShieldColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircuitDiagnosticSheet(
    tab: BrowserTab,
    config: SecurityConfig,
    proxyState: TorProxyState,
    diagnostic: TorCircuitDiagnostic,
    onRunDiagnostics: () -> Unit,
    onRequestNewCircuit: () -> Unit,
    onSaveBridgeConfig: (BridgeTransport, String, Int, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val shieldColors = LocalShieldColors.current
    val orbotInstalled = remember { TorProxyController.isOrbotInstalled(context) }

    var selectedTransport by remember(config.bridgeTransport) { mutableStateOf(config.bridgeTransport) }
    var bridgeLinesInput by remember(config.customBridgeLines) { mutableStateOf(config.customBridgeLines) }
    var controlPortInput by remember(config.torControlPort) { mutableStateOf(config.torControlPort.toString()) }
    var controlPasswordInput by remember(config.torControlPassword) { mutableStateOf(config.torControlPassword) }
    var showAdvancedControlPort by remember { mutableStateOf(false) }
    var livePollingEnabled by remember { mutableStateOf(false) }
    var clipboardFeedback by remember { mutableStateOf<String?>(null) }

    val bridgeValidation = remember(selectedTransport, bridgeLinesInput) {
        if ((selectedTransport == BridgeTransport.OBFS4 || selectedTransport == BridgeTransport.CUSTOM) &&
            bridgeLinesInput.isNotBlank()
        ) {
            TorCircuitService.validateBridgeLines(bridgeLinesInput)
        } else {
            null
        }
    }

    val urlClassification = remember(tab.url) { TorCircuitService.classifyUrl(tab.url) }

    LaunchedEffect(Unit) {
        onRunDiagnostics()
    }

    val pollIntervalMs = config.circuitPrivacyLevel.livePollIntervalMs
    LaunchedEffect(livePollingEnabled, pollIntervalMs) {
        while (livePollingEnabled) {
            delay(pollIntervalMs)
            onRunDiagnostics()
        }
    }

    val isConnected = diagnostic.socks5HandshakeOk == true || proxyState is TorProxyState.Connected
    val statusColor = when {
        diagnostic.isChecking || proxyState is TorProxyState.Checking -> shieldColors.torAccent
        isConnected && diagnostic.isTorExitVerified == true -> shieldColors.secureGreen
        isConnected -> shieldColors.torAccent
        config.browserMode == BrowserMode.SURFACE -> shieldColors.surfaceAccent
        else -> shieldColors.warningAmber
    }
    val statusLabel = when {
        diagnostic.isChecking || proxyState is TorProxyState.Checking -> "ESTABLISHING / PROBING"
        isConnected && diagnostic.isTorExitVerified == true -> "CONNECTED • TOR EXIT VERIFIED"
        isConnected -> "PROXY REACHABLE"
        config.browserMode == BrowserMode.SURFACE -> "SURFACE DIRECT (STANDBY)"
        else -> "DISCONNECTED"
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
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = statusColor.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f)),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Hub,
                                contentDescription = "Tor Circuit Diagnostics",
                                tint = statusColor,
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "V3 .onion & Tor Circuit",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (diagnostic.isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = shieldColors.torAccent,
                    )
                }
            }

            // V3 .onion Address & Exit Telemetry Card
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
                                imageVector = when (urlClassification) {
                                    OnionAddressStatus.V3_VERIFIED -> Icons.Rounded.VerifiedUser
                                    OnionAddressStatus.V2_DEPRECATED -> Icons.Rounded.WarningAmber
                                    OnionAddressStatus.CLEARNET_HTTPS -> Icons.Rounded.Lock
                                    else -> Icons.Rounded.Language
                                },
                                contentDescription = urlClassification.label,
                                tint = when (urlClassification) {
                                    OnionAddressStatus.V3_VERIFIED -> shieldColors.torAccent
                                    OnionAddressStatus.V2_DEPRECATED -> shieldColors.warningAmber
                                    OnionAddressStatus.CLEARNET_HTTPS -> shieldColors.secureGreen
                                    else -> shieldColors.surfaceAccent
                                },
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = urlClassification.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        diagnostic.proxyLatencyMs?.let { rtt ->
                            Surface(
                                shape = CircleShape,
                                color = shieldColors.secureGreen.copy(alpha = 0.14f),
                            ) {
                                Text(
                                    text = "Proxy ${rtt}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = shieldColors.secureGreen,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Proxy Endpoint",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = config.torEndpoint,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Verified Tor Exit",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val exitText = when {
                                diagnostic.isTorExitVerified == true && diagnostic.exitIp != null ->
                                    "${diagnostic.exitIp} (${diagnostic.exitCheckLatencyMs ?: 0}ms)"
                                diagnostic.isTorExitVerified == false ->
                                    "Not exiting via Tor"
                                diagnostic.exitCheckError != null ->
                                    "Unverified"
                                else -> "Tap Verify Exit"
                            }
                            Text(
                                text = exitText,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = when (diagnostic.isTorExitVerified) {
                                    true -> shieldColors.secureGreen
                                    false -> shieldColors.warningAmber
                                    null -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }

                    if (diagnostic.exitCheckError != null) {
                        Text(
                            text = diagnostic.exitCheckError,
                            style = MaterialTheme.typography.labelSmall,
                            color = shieldColors.warningAmber,
                        )
                    }
                }
            }

            // Real-time 3-Hop Tor CircuitGraph (Guard -> Middle -> Exit)
            CircuitGraph(
                hops = diagnostic.circuitHops,
                isConnected = isConnected,
                isChecking = diagnostic.isChecking,
                controlPortConnected = diagnostic.controlPortConnected,
                proxyLatencyMs = diagnostic.proxyLatencyMs,
                controllerLatencyMs = diagnostic.controllerLatencyMs,
                exitCheckLatencyMs = diagnostic.exitCheckLatencyMs,
                activeCircuitId = diagnostic.activeCircuitId,
                controlPortMessage = diagnostic.controlPortMessage,
                livePollingEnabled = livePollingEnabled,
                pollIntervalMs = pollIntervalMs,
                onToggleLivePolling = { livePollingEnabled = it },
                onRefresh = onRunDiagnostics,
            )

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRunDiagnostics,
                    enabled = !diagnostic.isChecking,
                    colors = ButtonDefaults.buttonColors(containerColor = shieldColors.torAccent),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("verify_circuit_button"),
                    contentPadding = PaddingValues(vertical = 10.dp),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Verify Exit")
                }

                OutlinedButton(
                    onClick = onRequestNewCircuit,
                    enabled = !diagnostic.isChecking,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("new_circuit_button"),
                    contentPadding = PaddingValues(vertical = 10.dp),
                ) {
                    Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New Circuit")
                }
            }

            OutlinedButton(
                onClick = { TorProxyController.requestOrbotStart(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("start_orbot_intent_button"),
            ) {
                Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (orbotInstalled) "Start / Open Orbot Service" else "Install Orbot (Tor Proxy)")
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            // Pluggable Transport Bridge Selector
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Pluggable Transport & Bridge Selector",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = selectedTransport.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BridgeTransport.entries.forEach { transport ->
                        val selected = selectedTransport == transport
                        FilterChip(
                            selected = selected,
                            onClick = { selectedTransport = transport },
                            label = { Text(transport.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = shieldColors.torAccent.copy(alpha = 0.2f),
                                selectedLabelColor = shieldColors.torAccent,
                            ),
                            modifier = Modifier.testTag("bridge_chip_${transport.name.lowercase()}"),
                        )
                    }
                }

                AnimatedVisibility(
                    visible = selectedTransport == BridgeTransport.OBFS4 || selectedTransport == BridgeTransport.CUSTOM,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = bridgeLinesInput,
                            onValueChange = { bridgeLinesInput = it },
                            label = { Text("obfs4 / webtunnel Bridge Lines") },
                            placeholder = {
                                Text(
                                    "obfs4 <IP:PORT> <40-HEX-FINGERPRINT> cert=... iat-mode=0",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            minLines = 3,
                            maxLines = 5,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("bridge_lines_input"),
                        )

                        if (bridgeValidation != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = if (bridgeValidation.isValid) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                                    contentDescription = null,
                                    tint = if (bridgeValidation.isValid) shieldColors.secureGreen else shieldColors.warningAmber,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = if (bridgeValidation.isValid) {
                                        "${bridgeValidation.normalizedLines.size} bridge line(s) validated"
                                    } else {
                                        bridgeValidation.errorMessage ?: "Invalid bridge syntax"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (bridgeValidation.isValid) shieldColors.secureGreen else shieldColors.warningAmber,
                                )
                            }
                        }
                    }
                }

                // Advanced ControlPort collapsible config
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { showAdvancedControlPort = !showAdvancedControlPort }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SettingsEthernet,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Tor ControlPort (${controlPortInput.ifBlank { "9051" }})",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Icon(
                        imageVector = if (showAdvancedControlPort) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = "Toggle ControlPort settings",
                    )
                }

                AnimatedVisibility(visible = showAdvancedControlPort) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = controlPortInput,
                            onValueChange = { controlPortInput = it.filter(Char::isDigit) },
                            label = { Text("Control Port") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = controlPasswordInput,
                            onValueChange = { controlPasswordInput = it },
                            label = { Text("Auth Password (Optional)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.weight(1.6f),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            val ctrlPort = controlPortInput.toIntOrNull() ?: 9051
                            onSaveBridgeConfig(selectedTransport, bridgeLinesInput, ctrlPort, controlPasswordInput)
                        },
                        enabled = bridgeValidation == null || bridgeValidation.isValid,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_bridge_config_button"),
                    ) {
                        Text("Save Bridge")
                    }

                    OutlinedButton(
                        onClick = {
                            val payload = TorCircuitService.formatForOrbotClipboard(selectedTransport, bridgeLinesInput)
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Tor Bridge Config", payload))
                            clipboardFeedback = "Bridge config copied. Opening Orbot…"
                            TorProxyController.launchOrbot(context)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("copy_orbot_bridge_button"),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy & Orbot")
                    }
                }

                clipboardFeedback?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = shieldColors.secureGreen,
                    )
                }
            }
        }
    }
}

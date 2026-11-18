package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.CircuitHopInfo
import com.example.data.CircuitNodeRole
import com.example.ui.theme.LocalShieldColors
import kotlin.math.roundToInt

/**
 * Displays the three-hop Tor circuit (Guard, Middle, Exit) visually with animated
 * path topology, relay metadata, and real-time latency indicators from Tor Controller.
 */
@Composable
fun CircuitGraph(
    hops: List<CircuitHopInfo>,
    isConnected: Boolean,
    isChecking: Boolean,
    controlPortConnected: Boolean,
    proxyLatencyMs: Long?,
    controllerLatencyMs: Long?,
    exitCheckLatencyMs: Long?,
    activeCircuitId: String?,
    controlPortMessage: String,
    livePollingEnabled: Boolean,
    pollIntervalMs: Long = 5_000L,
    onToggleLivePolling: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shieldColors = LocalShieldColors.current

    val threeHops = remember(hops, isConnected, proxyLatencyMs, controllerLatencyMs, exitCheckLatencyMs) {
        normalizeThreeHops(
            hops = hops,
            isConnected = isConnected,
            proxyLatencyMs = proxyLatencyMs,
            controllerLatencyMs = controllerLatencyMs,
            exitCheckLatencyMs = exitCheckLatencyMs,
        )
    }

    val pulseTransition = rememberInfiniteTransition(label = "torCircuitPulse")
    val pulsePhase by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulsePhase",
    )

    val lineAccent = when {
        isChecking -> shieldColors.torAccent
        isConnected && controlPortConnected -> shieldColors.secureGreen
        isConnected -> shieldColors.torAccent
        else -> shieldColors.warningAmber
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("circuit_graph_card"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, lineAccent.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header Row: Title, Circuit ID, Live Polling Toggle & Refresh
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Router,
                        contentDescription = "Tor three-hop circuit",
                        tint = lineAccent,
                        modifier = Modifier.size(20.dp),
                    )
                    Column {
                        Text(
                            text = activeCircuitId?.let { "Tor 3-Hop Circuit #$it" } ?: "Tor 3-Hop Circuit",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (controlPortConnected) "ControlPort Telemetry Active" else "Guard → Middle → Exit",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = livePollingEnabled,
                        onClick = { onToggleLivePolling(!livePollingEnabled) },
                        label = {
                            val sec = (pollIntervalMs / 1000L).coerceAtLeast(1L)
                            Text(
                                text = if (livePollingEnabled) "Live ${sec}s" else "Live",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = shieldColors.secureGreen.copy(alpha = 0.18f),
                            selectedLabelColor = shieldColors.secureGreen,
                            selectedLeadingIconColor = shieldColors.secureGreen,
                        ),
                        modifier = Modifier.testTag("circuit_live_toggle"),
                    )

                    IconButton(
                        onClick = onRefresh,
                        enabled = !isChecking,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("circuit_graph_refresh"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh circuit latency",
                            tint = lineAccent,
                        )
                    }
                }
            }

            // Custom Canvas 3-Hop Graph (Guard -> Middle -> Exit)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                ) {
                    val leftX = 32.dp.toPx()
                    val rightX = (size.width - 32.dp.toPx()).coerceAtLeast(leftX + 30f)
                    val midX = (leftX + rightX) / 2f
                    val centerY = size.height / 2f
                    val nodeXs = listOf(leftX, midX, rightX)

                    val guardColor = shieldColors.surfaceAccent
                    val middleColor = shieldColors.torAccent
                    val exitColor = if (isConnected) shieldColors.secureGreen else shieldColors.warningAmber
                    val nodeColors = listOf(guardColor, middleColor, exitColor)

                    val dashEffect = if (!isConnected) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null

                    // Segment 1: Guard -> Middle
                    drawLine(
                        brush = Brush.horizontalGradient(listOf(guardColor, middleColor), startX = leftX, endX = midX),
                        start = Offset(leftX, centerY),
                        end = Offset(midX, centerY),
                        strokeWidth = 3.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = dashEffect,
                    )

                    // Segment 2: Middle -> Exit
                    drawLine(
                        brush = Brush.horizontalGradient(listOf(middleColor, exitColor), startX = midX, endX = rightX),
                        start = Offset(midX, centerY),
                        end = Offset(rightX, centerY),
                        strokeWidth = 3.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = dashEffect,
                    )

                    // Animated packet orb across Guard -> Middle -> Exit
                    if (isConnected || isChecking) {
                        val orbX = leftX + (rightX - leftX) * pulsePhase
                        drawCircle(
                            color = lineAccent.copy(alpha = 0.28f),
                            radius = 10.dp.toPx(),
                            center = Offset(orbX, centerY),
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 4.dp.toPx(),
                            center = Offset(orbX, centerY),
                        )
                    }

                    // 3 Hop Nodes (Guard, Middle, Exit)
                    nodeXs.forEachIndexed { idx, x ->
                        val color = nodeColors[idx]
                        drawCircle(
                            color = color.copy(alpha = 0.20f),
                            radius = 18.dp.toPx(),
                            center = Offset(x, centerY),
                        )
                        drawCircle(
                            color = color.copy(alpha = 0.45f),
                            radius = 12.dp.toPx(),
                            center = Offset(x, centerY),
                        )
                        drawCircle(
                            color = color,
                            radius = 7.dp.toPx(),
                            center = Offset(x, centerY),
                        )
                    }
                }

                // Node Labels + Live Latency Pills directly beneath the 3 Canvas Nodes
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    threeHops.forEachIndexed { idx, hop ->
                        val align = when (idx) {
                            0 -> Alignment.Start
                            1 -> Alignment.CenterHorizontally
                            else -> Alignment.End
                        }
                        val roleLabel = when (hop.role) {
                            CircuitNodeRole.GUARD -> "GUARD"
                            CircuitNodeRole.MIDDLE -> "MIDDLE"
                            CircuitNodeRole.EXIT -> "EXIT"
                            else -> "HOP"
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = align,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = roleLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = when (hop.role) {
                                    CircuitNodeRole.GUARD -> shieldColors.surfaceAccent
                                    CircuitNodeRole.MIDDLE -> shieldColors.torAccent
                                    else -> if (isConnected) shieldColors.secureGreen else shieldColors.warningAmber
                                },
                            )
                            LatencyPill(latencyMs = hop.latencyMs)
                        }
                    }
                }
            }

            // Adaptive 3-Hop Detail Cards
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val useHorizontalRow = maxWidth >= 520.dp
                if (useHorizontalRow) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        threeHops.forEach { hop ->
                            CircuitHopDetailCard(
                                hop = hop,
                                isConnected = isConnected,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        threeHops.forEach { hop ->
                            CircuitHopDetailCard(
                                hop = hop,
                                isConnected = isConnected,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // Real-time Controller & Proxy Telemetry Summary Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TelemetryChip(
                    label = "Proxy RTT",
                    value = proxyLatencyMs?.let { "${it} ms" } ?: "--",
                    color = latencyColor(proxyLatencyMs, isConnected),
                    modifier = Modifier.weight(1f),
                )
                TelemetryChip(
                    label = "Controller",
                    value = controllerLatencyMs?.let { "${it} ms" } ?: if (controlPortConnected) "OK" else "Closed",
                    color = if (controlPortConnected) shieldColors.secureGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TelemetryChip(
                    label = "Tor Exit RTT",
                    value = exitCheckLatencyMs?.let { "${it} ms" } ?: "--",
                    color = latencyColor(exitCheckLatencyMs, isConnected),
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = controlPortMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CircuitHopDetailCard(
    hop: CircuitHopInfo,
    isConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    val shieldColors = LocalShieldColors.current
    val accent = when (hop.role) {
        CircuitNodeRole.GUARD -> shieldColors.surfaceAccent
        CircuitNodeRole.MIDDLE -> shieldColors.torAccent
        CircuitNodeRole.EXIT -> if (isConnected) shieldColors.secureGreen else shieldColors.warningAmber
        else -> shieldColors.torAccent
    }
    val roleTag = when (hop.role) {
        CircuitNodeRole.GUARD -> "1 • GUARD"
        CircuitNodeRole.MIDDLE -> "2 • MIDDLE"
        CircuitNodeRole.EXIT -> "3 • EXIT"
        else -> "RELAY"
    }
    val icon = when (hop.role) {
        CircuitNodeRole.GUARD -> Icons.Rounded.Shield
        CircuitNodeRole.MIDDLE -> Icons.Rounded.Security
        CircuitNodeRole.EXIT -> Icons.Rounded.Public
        else -> Icons.Rounded.Router
    }

    Surface(
        modifier = modifier.testTag("circuit_hop_${hop.role.name.lowercase()}"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = roleTag,
                        tint = accent,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = roleTag,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    hop.countryCode?.let { cc ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = accent.copy(alpha = 0.16f),
                        ) {
                            Text(
                                text = cc,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = accent,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                    LatencyPill(latencyMs = hop.latencyMs)
                }
            }

            Text(
                text = hop.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = hop.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = if (hop.ipAddress != null) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (!hop.fingerprint.isNullOrBlank() || hop.flags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    hop.fingerprint?.takeIf { it.isNotBlank() }?.let { fp ->
                        Text(
                            text = "$${fp.take(10)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    hop.flags.take(3).forEach { flag ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = flag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LatencyPill(latencyMs: Long?) {
    val shieldColors = LocalShieldColors.current
    val color = latencyColor(latencyMs, isConnected = latencyMs != null)
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(0.8.dp, color.copy(alpha = 0.45f)),
    ) {
        Text(
            text = latencyMs?.let { "${it}ms" } ?: "--",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = if (latencyMs == null) MaterialTheme.colorScheme.onSurfaceVariant else color,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun TelemetryChip(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun latencyColor(latencyMs: Long?, isConnected: Boolean): Color {
    val shieldColors = LocalShieldColors.current
    if (!isConnected || latencyMs == null) return MaterialTheme.colorScheme.onSurfaceVariant
    return when {
        latencyMs < 200L -> shieldColors.secureGreen
        latencyMs < 600L -> shieldColors.warningAmber
        else -> MaterialTheme.colorScheme.error
    }
}

internal fun normalizeThreeHops(
    hops: List<CircuitHopInfo>,
    isConnected: Boolean,
    proxyLatencyMs: Long?,
    controllerLatencyMs: Long?,
    exitCheckLatencyMs: Long?,
): List<CircuitHopInfo> {
    val guard = hops.firstOrNull { it.role == CircuitNodeRole.GUARD } ?: CircuitHopInfo(
        role = CircuitNodeRole.GUARD,
        title = "Entry Guard",
        detail = if (isConnected) "Tor Guard (Orbot managed)" else "Awaiting Tor circuit",
        latencyMs = proxyLatencyMs,
    )
    val middle = hops.firstOrNull { it.role == CircuitNodeRole.MIDDLE } ?: CircuitHopInfo(
        role = CircuitNodeRole.MIDDLE,
        title = "Middle Relay",
        detail = if (isConnected) "Encrypted Tor Hop" else "Offline",
        latencyMs = controllerLatencyMs,
    )
    val exit = hops.firstOrNull { it.role == CircuitNodeRole.EXIT } ?: CircuitHopInfo(
        role = CircuitNodeRole.EXIT,
        title = "Exit Relay",
        detail = if (isConnected) "Tor Exit Node" else "Offline",
        latencyMs = exitCheckLatencyMs,
    )
    return listOf(guard, middle, exit)
}

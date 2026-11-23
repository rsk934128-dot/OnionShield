package com.example.ui.components

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.BrowserMode
import com.example.data.BrowserTab
import com.example.data.SecurityConfig
import com.example.ui.theme.LocalShieldColors
import java.util.Locale

enum class FingerprintProtectionStatus(val label: String) {
    PROTECTED("PROTECTED"),
    PARTIAL("PARTIAL"),
    EXPOSED("EXPOSED"),
}

data class PrivacyDashboardMetrics(
    val totalTrackersBlocked: Int,
    val totalScriptsBlocked: Int,
    val averageSessionEntropyBits: Double,
    val entropyRatio: Float,
    val entropyTierLabel: String,
    val fingerprintStatus: FingerprintProtectionStatus,
    val activeSeed: String,
    val activeUserAgentLabel: String,
    val activeTabCount: Int,
    val activeProtectionTags: List<String>,
)

object PrivacyDashboardCalculator {
    const val MAX_ENTROPY_BITS = 32.0

    fun computeMetrics(
        tabs: List<BrowserTab>,
        activeTab: BrowserTab,
        config: SecurityConfig,
        totalTrackersBlocked: Int,
        totalScriptsBlocked: Int,
        isVpnConnected: Boolean,
    ): PrivacyDashboardMetrics {
        val effectiveTabs = tabs.ifEmpty { listOf(activeTab) }
        val avgEntropy = effectiveTabs
            .map { tab -> computeTabEntropyBits(tab, config, isVpnConnected) }
            .average()
            .coerceIn(0.0, MAX_ENTROPY_BITS)

        val entropyRatio = (avgEntropy / MAX_ENTROPY_BITS).toFloat().coerceIn(0f, 1f)
        val entropyTier = when {
            avgEntropy >= 23.0 -> "High Entropy"
            avgEntropy >= 14.0 -> "Balanced"
            else -> "Low Entropy"
        }

        val fpStatus = when {
            config.fingerprintRandomizationEnabled &&
                config.webRtcLeakProtectionEnabled &&
                config.trackerBlockerEnabled -> FingerprintProtectionStatus.PROTECTED
            config.fingerprintRandomizationEnabled ||
                config.webRtcLeakProtectionEnabled ||
                config.noScriptEnabled -> FingerprintProtectionStatus.PARTIAL
            else -> FingerprintProtectionStatus.EXPOSED
        }

        val tags = buildList {
            if (config.fingerprintRandomizationEnabled) add("Canvas & WebGL Noise")
            if (config.webRtcLeakProtectionEnabled) add("WebRTC Guard")
            if (config.blockThirdPartyCookies) add("3P Cookie Isolation")
            if (config.noScriptEnabled) add("No-Script")
            if (config.browserMode == BrowserMode.TOR_ONION) add("Tor Circuit")
            if (isVpnConnected) add("Encrypted VPN")
            if (isEmpty()) add("Minimal Shielding")
        }

        return PrivacyDashboardMetrics(
            totalTrackersBlocked = totalTrackersBlocked.coerceAtLeast(0),
            totalScriptsBlocked = totalScriptsBlocked.coerceAtLeast(0),
            averageSessionEntropyBits = avgEntropy,
            entropyRatio = entropyRatio,
            entropyTierLabel = entropyTier,
            fingerprintStatus = fpStatus,
            activeSeed = activeTab.fingerprintSeed.ifBlank { "DEFAULT" },
            activeUserAgentLabel = activeTab.userAgentLabel.ifBlank { "Hardened Mobile" },
            activeTabCount = effectiveTabs.size,
            activeProtectionTags = tags,
        )
    }

    internal fun computeTabEntropyBits(
        tab: BrowserTab,
        config: SecurityConfig,
        isVpnConnected: Boolean,
    ): Double {
        var bits = 3.2 // baseline sandboxed WebView + randomized session UA baseline
        if (config.fingerprintRandomizationEnabled) {
            val cleanSeed = tab.fingerprintSeed.trim()
            val distinctChars = cleanSeed.toSet().size.coerceIn(1, 8)
            bits += 8.0 + (distinctChars * 0.45)
        }
        if (config.webRtcLeakProtectionEnabled) bits += 4.6
        if (config.blockThirdPartyCookies) bits += 3.2
        if (config.trackerBlockerEnabled) bits += 3.0
        if (config.noScriptEnabled) bits += 3.8
        if (config.httpsEverywhereEnabled) bits += 1.4
        if (tab.mode == BrowserMode.TOR_ONION || config.browserMode == BrowserMode.TOR_ONION) {
            bits += 3.2
        } else if (isVpnConnected) {
            bits += 2.0
        }
        return bits.coerceIn(0.0, MAX_ENTROPY_BITS)
    }

    fun formatEntropyBits(bits: Double): String =
        String.format(Locale.US, "%.1f", bits.coerceIn(0.0, MAX_ENTROPY_BITS))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrivacyDashboardCard(
    metrics: PrivacyDashboardMetrics,
    accentColor: Color,
    onRotateSeed: () -> Unit,
    onOpenShieldControls: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val shieldColors = LocalShieldColors.current
    val statusColor = when (metrics.fingerprintStatus) {
        FingerprintProtectionStatus.PROTECTED -> shieldColors.secureGreen
        FingerprintProtectionStatus.PARTIAL -> shieldColors.warningAmber
        FingerprintProtectionStatus.EXPOSED -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .testTag("privacy_dashboard_card"),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (compact) 0.dp else 2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                statusColor.copy(alpha = 0.14f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.88f, size.height * 0.14f),
                            radius = size.width * 0.55f,
                        ),
                        center = Offset(size.width * 0.88f, size.height * 0.14f),
                        radius = size.width * 0.55f,
                    )
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = if (compact) 12.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = statusColor.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f)),
                            modifier = Modifier.size(34.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.VerifiedUser,
                                    contentDescription = stringResource(R.string.cd_privacy_dashboard_icon),
                                    tint = statusColor,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.privacy_dashboard_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${metrics.activeUserAgentLabel} • Seed #${metrics.activeSeed}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = statusColor.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.55f)),
                        modifier = Modifier.testTag("privacy_dashboard_status_badge"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(statusColor),
                            )
                            Text(
                                text = metrics.fingerprintStatus.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = statusColor,
                            )
                        }
                    }
                }

                // Key Security Metrics Row (Trackers Blocked, Avg Session Entropy, Fingerprinting)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DashboardMetricTile(
                        title = stringResource(R.string.privacy_dashboard_trackers_label),
                        value = metrics.totalTrackersBlocked.toString(),
                        subtitle = "${metrics.totalScriptsBlocked} scripts",
                        icon = Icons.Rounded.Block,
                        accent = shieldColors.secureGreen,
                        valueTestTag = "privacy_dashboard_trackers_value",
                        modifier = Modifier.weight(1f),
                    )
                    val formattedEntropy = remember(metrics.averageSessionEntropyBits) {
                        "${PrivacyDashboardCalculator.formatEntropyBits(metrics.averageSessionEntropyBits)} b"
                    }
                    DashboardMetricTile(
                        title = stringResource(R.string.privacy_dashboard_entropy_label),
                        value = formattedEntropy,
                        subtitle = metrics.entropyTierLabel,
                        icon = Icons.Rounded.GraphicEq,
                        accent = accentColor,
                        valueTestTag = "privacy_dashboard_entropy_value",
                        modifier = Modifier.weight(1f),
                    )
                    DashboardMetricTile(
                        title = stringResource(R.string.privacy_dashboard_fingerprint_label),
                        value = when (metrics.fingerprintStatus) {
                            FingerprintProtectionStatus.PROTECTED -> "Active"
                            FingerprintProtectionStatus.PARTIAL -> "Partial"
                            FingerprintProtectionStatus.EXPOSED -> "Off"
                        },
                        subtitle = "${metrics.activeTabCount} tab(s)",
                        icon = Icons.Rounded.Fingerprint,
                        accent = statusColor,
                        valueTestTag = "privacy_dashboard_fingerprint_status",
                        modifier = Modifier.weight(1f),
                    )
                }

                // Session Entropy Bar
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.privacy_dashboard_entropy_gauge_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${PrivacyDashboardCalculator.formatEntropyBits(metrics.averageSessionEntropyBits)} / 32.0 bits",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = accentColor,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(metrics.entropyRatio.coerceAtLeast(0.06f))
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(accentColor, statusColor),
                                    ),
                                ),
                        )
                    }
                }

                // Active Protection Tags
                if (!compact) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        metrics.activeProtectionTags.forEach { tagLabel ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                ),
                            ) {
                                Text(
                                    text = tagLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }

                    // Action Buttons Row (48.dp touch target)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onRotateSeed,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("privacy_dashboard_rotate_seed_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.privacy_dashboard_rotate_seed),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Button(
                            onClick = onOpenShieldControls,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor.copy(alpha = 0.18f),
                                contentColor = accentColor,
                            ),
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("privacy_dashboard_configure_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.privacy_dashboard_manage_shields),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardMetricTile(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    valueTestTag: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.32f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(valueTestTag),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

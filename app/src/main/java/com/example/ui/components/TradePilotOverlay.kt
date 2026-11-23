package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoGraph
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.browser.TradeDecision
import com.example.ui.TradePilotUiState
import com.example.ui.theme.LocalShieldColors
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun WebOverlayAiControls(
    tradePilot: TradePilotUiState,
    accentColor: Color,
    onToggleTradePilot: () -> Unit,
    onOpenGeminiChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shieldColors = LocalShieldColors.current
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(
            width = 1.dp,
            color = if (tradePilot.isAutoPilotRunning) {
                shieldColors.secureGreen.copy(alpha = 0.85f)
            } else {
                accentColor.copy(alpha = 0.55f)
            },
        ),
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .height(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleTradePilot)
                    .padding(horizontal = 10.dp)
                    .testTag("trade_pilot_toggle_pill"),
            ) {
                if (tradePilot.isAutoPilotRunning) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(shieldColors.secureGreen),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.AutoGraph,
                        contentDescription = stringResource(R.string.cd_open_trade_pilot),
                        tint = accentColor,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = when {
                        tradePilot.isAnalyzing -> "Scanning…"
                        tradePilot.isAutoPilotRunning -> "Auto ON"
                        else -> "AI Trade"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (tradePilot.isAutoPilotRunning) shieldColors.secureGreen else MaterialTheme.colorScheme.onSurface,
                )
            }

            IconButton(
                onClick = onOpenGeminiChat,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("gemini_chat_fab"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = stringResource(R.string.cd_open_gemini_chat),
                    tint = accentColor,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
fun TradePilotHudCard(
    tradePilot: TradePilotUiState,
    accentColor: Color,
    onScanAndClick: () -> Unit,
    onAnalyzeOnly: () -> Unit,
    onExecuteLastDecision: () -> Unit,
    onToggleAutoPilot: (Boolean) -> Unit,
    onSelectMinConfidence: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shieldColors = LocalShieldColors.current
    val decision = tradePilot.lastDecision
    val decisionColor = when (decision?.decision) {
        TradeDecision.BUY -> shieldColors.secureGreen
        TradeDecision.SELL -> MaterialTheme.colorScheme.error
        TradeDecision.HOLD -> shieldColors.warningAmber
        null -> accentColor
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 600.dp)
            .testTag("trade_pilot_hud_card"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (tradePilot.isAutoPilotRunning) shieldColors.secureGreen else decisionColor.copy(alpha = 0.6f),
        ),
        tonalElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoGraph,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = if (decision != null) {
                            "${decision.assetName} • ${decision.accountMode}"
                        } else {
                            stringResource(R.string.trade_pilot_title)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (tradePilot.totalExecutionsCount > 0) {
                        Text(
                            text = "(${tradePilot.totalExecutionsCount}x)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = shieldColors.secureGreen,
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(34.dp)
                        .testTag("trade_pilot_close_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cd_close_trade_pilot),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (tradePilot.isAnalyzing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .clip(CircleShape),
                    color = accentColor,
                )
            }

            if (decision != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = decisionColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, decisionColor.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = decisionColor,
                            ) {
                                Text(
                                    text = decision.decision.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.Black,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                            Text(
                                text = "${decision.confidence}% • ${decision.marketBias}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = decision.reasoning,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (!tradePilot.lastError.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = tradePilot.lastError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onScanAndClick,
                    enabled = !tradePilot.isAnalyzing,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .testTag("trade_pilot_scan_click_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.TouchApp,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = stringResource(R.string.trade_pilot_scan_click),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }

                OutlinedButton(
                    onClick = onAnalyzeOnly,
                    enabled = !tradePilot.isAnalyzing,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .height(38.dp)
                        .testTag("trade_pilot_analyze_only_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = stringResource(R.string.trade_pilot_analyze_only),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }

                if (decision != null && decision.decision != TradeDecision.HOLD) {
                    val tapColor = if (decision.decision == TradeDecision.BUY) {
                        shieldColors.secureGreen
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                    Button(
                        onClick = onExecuteLastDecision,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tapColor,
                            contentColor = Color.Black,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(38.dp)
                            .testTag("trade_pilot_manual_tap_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = decision.decision.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Conf:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    listOf(55, 60, 75).forEach { threshold ->
                        val selected = tradePilot.minConfidencePercent == threshold
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectMinConfidence(threshold) },
                            label = {
                                Text(
                                    text = "$threshold%",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accentColor.copy(alpha = 0.22f),
                                selectedLabelColor = accentColor,
                            ),
                            modifier = Modifier.height(26.dp),
                        )
                    }
                }

                if (tradePilot.isAutoPilotRunning) {
                    Button(
                        onClick = { onToggleAutoPilot(false) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("trade_pilot_stop_auto_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.StopCircle,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = "STOP",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.trade_pilot_auto_pilot),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Switch(
                            checked = tradePilot.isAutoPilotRunning,
                            onCheckedChange = onToggleAutoPilot,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = shieldColors.secureGreen,
                            ),
                            modifier = Modifier.testTag("trade_pilot_auto_switch"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TradeTapIndicatorOverlay(
    normX: Float?,
    normY: Float?,
    actionLabel: String?,
    onClearIndicator: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = normX != null && normY != null
    LaunchedEffect(normX, normY, actionLabel) {
        if (visible) {
            delay(1800)
            onClearIndicator()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        if (normX != null && normY != null) {
            val shieldColors = LocalShieldColors.current
            val ringColor = if (actionLabel.equals("BUY", ignoreCase = true)) {
                shieldColors.secureGreen
            } else {
                MaterialTheme.colorScheme.error
            }
            val density = LocalDensity.current
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val widthPx = with(density) { maxWidth.toPx() }
                val heightPx = with(density) { maxHeight.toPx() }
                val badgeHalfPx = with(density) { 22.dp.toPx() }
                val xPx = (normX.coerceIn(0.05f, 0.95f) * widthPx - badgeHalfPx).roundToInt()
                val yPx = (normY.coerceIn(0.05f, 0.95f) * heightPx - badgeHalfPx).roundToInt()

                Box(
                    modifier = Modifier
                        .offset { IntOffset(xPx, yPx) }
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(ringColor.copy(alpha = 0.25f))
                        .border(2.5.dp, ringColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.TouchApp,
                        contentDescription = "AI clicked target",
                        tint = ringColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

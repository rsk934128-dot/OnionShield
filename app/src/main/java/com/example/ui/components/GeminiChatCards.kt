package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.repository.ChatRole
import com.example.data.repository.GeminiChatMessage
import com.example.data.repository.PagePrivacyAudit
import com.example.ui.theme.LocalShieldColors

@Composable
fun ChatMessageRow(message: GeminiChatMessage) {
    val shieldColors = LocalShieldColors.current
    if (message.role == ChatRole.USER) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .testTag("gemini_user_bubble"),
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 6.dp,
                    bottomStart = 18.dp,
                    bottomEnd = 18.dp,
                ),
                color = shieldColors.surfaceAccent.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, shieldColors.surfaceAccent.copy(alpha = 0.45f)),
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    } else if (message.audit != null) {
        GeminiAuditResultCard(audit = message.audit)
    } else {
        ModelMessageCard(
            bodyText = message.text,
            isStreaming = false,
        )
    }
}

@Composable
fun ModelMessageCard(
    bodyText: String,
    isStreaming: Boolean,
) {
    val shieldColors = LocalShieldColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .testTag("gemini_model_bubble"),
        shape = RoundedCornerShape(
            topStart = 6.dp,
            topEnd = 18.dp,
            bottomStart = 18.dp,
            bottomEnd = 18.dp,
        ),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, shieldColors.surfaceAccent.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = shieldColors.surfaceAccent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.gemini_ai_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = shieldColors.surfaceAccent,
                )
            }
            Text(
                text = bodyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (isStreaming) {
                Text(
                    text = "Streaming live response…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun GeminiAuditResultCard(audit: PagePrivacyAudit) {
    val shieldColors = LocalShieldColors.current
    val riskColor = when (audit.riskLevel.lowercase()) {
        "low" -> shieldColors.secureGreen
        "high" -> MaterialTheme.colorScheme.error
        else -> shieldColors.warningAmber
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .testTag("gemini_audit_card"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, riskColor.copy(alpha = 0.6f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Structured Privacy Audit", style = MaterialTheme.typography.titleMedium)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = riskColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, riskColor.copy(alpha = 0.5f)),
                ) {
                    Text(
                        text = "${audit.riskLevel} Risk",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = riskColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Text(audit.summary, style = MaterialTheme.typography.bodyMedium)

            if (audit.privacySignals.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Text("Privacy & Safety Signals", style = MaterialTheme.typography.labelLarge)
                audit.privacySignals.forEach { signal ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = riskColor,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(signal, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
            }

            Surface(
                shape = MaterialTheme.shapes.small,
                color = riskColor.copy(alpha = 0.1f),
            ) {
                Text(
                    text = "Recommended Action: ${audit.recommendedAction}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}

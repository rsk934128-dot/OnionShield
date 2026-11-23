package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.security.GeminiApiKeyStore
import com.example.security.GeminiKeySource
import com.example.ui.theme.LocalShieldColors

@Composable
fun GeminiApiKeySettingsCard(
    activeSource: GeminiKeySource,
    maskedUserKey: String?,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shieldColors = LocalShieldColors.current
    var showDialog by remember { mutableStateOf(false) }

    val statusColor = when (activeSource) {
        GeminiKeySource.USER_CUSTOM -> shieldColors.secureGreen
        GeminiKeySource.BUILD_SECRET -> shieldColors.surfaceAccent
        GeminiKeySource.FIREBASE_AI_LOGIC -> shieldColors.torAccent
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .testTag("settings_gemini_api_key_card"),
        shape = MaterialTheme.shapes.large,
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
                            colors = listOf(shieldColors.surfaceAccent, shieldColors.secureGreen),
                        ),
                    ),
            )
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Key,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(24.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.gemini_key_settings_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.gemini_key_settings_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = statusColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "Active Engine: ${activeSource.label}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                        )
                        if (!maskedUserKey.isNullOrBlank()) {
                            Text(
                                text = "Saved Personal Key: $maskedUserKey (AES-256 encrypted)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        } else if (activeSource == GeminiKeySource.BUILD_SECRET) {
                            Text(
                                text = "Using GEMINI_API_KEY secret embedded at build time.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                text = "Optional: add a key from aistudio.google.com/apikey to run Gemini directly on sideloaded APKs.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { showDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("settings_configure_gemini_key_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (maskedUserKey != null) {
                                stringResource(R.string.gemini_key_update_button)
                            } else {
                                stringResource(R.string.gemini_key_configure_button)
                            },
                            maxLines = 1,
                        )
                    }
                    if (!maskedUserKey.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = onClearKey,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("settings_clear_gemini_key_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = stringResource(R.string.gemini_key_clear_button),
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.gemini_key_clear_button), maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        GeminiApiKeyDialog(
            activeSource = activeSource,
            maskedUserKey = maskedUserKey,
            onSaveKey = {
                onSaveKey(it)
                showDialog = false
            },
            onClearKey = {
                onClearKey()
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
fun GeminiApiKeyDialog(
    activeSource: GeminiKeySource,
    maskedUserKey: String?,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    var apiKeyInput by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }

    val normalizedInput = remember(apiKeyInput) { GeminiApiKeyStore.normalizeApiKey(apiKeyInput) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .testTag("gemini_api_key_dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, shieldColors.surfaceAccent.copy(alpha = 0.55f)),
            tonalElevation = 8.dp,
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    shieldColors.surfaceAccent,
                                    shieldColors.torAccent,
                                    shieldColors.secureGreen,
                                ),
                            ),
                        ),
                )
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Key,
                            contentDescription = null,
                            tint = shieldColors.surfaceAccent,
                            modifier = Modifier.size(24.dp),
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.gemini_key_dialog_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = activeSource.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = shieldColors.secureGreen,
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.gemini_key_dialog_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (!maskedUserKey.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = shieldColors.secureGreen.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, shieldColors.secureGreen.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = shieldColors.secureGreen,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "Saved Key: $maskedUserKey",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text(stringResource(R.string.gemini_key_input_label)) },
                        placeholder = { Text(stringResource(R.string.gemini_key_input_placeholder)) },
                        singleLine = true,
                        visualTransformation = if (showSecret) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        trailingIcon = {
                            IconButton(
                                onClick = { showSecret = !showSecret },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("gemini_api_key_toggle_visibility"),
                            ) {
                                Icon(
                                    imageVector = if (showSecret) {
                                        Icons.Rounded.VisibilityOff
                                    } else {
                                        Icons.Rounded.Visibility
                                    },
                                    contentDescription = stringResource(R.string.cd_toggle_key_visibility),
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gemini_api_key_input"),
                    )

                    Text(
                        text = stringResource(R.string.gemini_key_get_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!maskedUserKey.isNullOrBlank()) {
                            OutlinedButton(
                                onClick = onClearKey,
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("gemini_api_key_clear_button"),
                            ) {
                                Text(stringResource(R.string.gemini_key_clear_button), maxLines = 1)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("gemini_api_key_cancel_button"),
                        ) {
                            Text("Cancel", maxLines = 1)
                        }
                        Button(
                            onClick = {
                                normalizedInput?.let(onSaveKey)
                            },
                            enabled = normalizedInput != null,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("gemini_api_key_save_button"),
                        ) {
                            Text(stringResource(R.string.gemini_key_save_button), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

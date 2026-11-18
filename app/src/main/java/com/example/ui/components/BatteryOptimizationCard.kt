package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.ui.theme.LocalShieldColors

/**
 * Play-policy-compliant helper that inspects current battery optimization exemption status
 * via [PowerManager.isIgnoringBatteryOptimizations] and routes the user to zero-permission
 * Android System Settings screens without declaring `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
 */
object BatteryOptimizationHelper {

    fun isBatteryOptimizationDisabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)
    }

    fun buildAppBatteryDetailsIntent(packageName: String): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildBatteryOptimizationListIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun openAppBatteryDetails(context: Context): Boolean {
        return runCatching {
            context.startActivity(buildAppBatteryDetailsIntent(context.packageName))
            true
        }.getOrDefault(false)
    }

    fun openBatteryOptimizationList(context: Context): Boolean {
        val openedList = runCatching {
            context.startActivity(buildBatteryOptimizationListIntent())
            true
        }.getOrDefault(false)
        return openedList || openAppBatteryDetails(context)
    }
}

@Composable
internal fun BatteryOptimizationCard(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val shieldColors = LocalShieldColors.current

    var isUnrestricted by remember {
        mutableStateOf(BatteryOptimizationHelper.isBatteryOptimizationDisabled(context))
    }
    var launchWarning by remember { mutableStateOf<String?>(null) }

    // Re-check exemption status automatically when the user returns from Android Settings.
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isUnrestricted = BatteryOptimizationHelper.isBatteryOptimizationDisabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val accentColor = if (isUnrestricted) shieldColors.secureGreen else shieldColors.warningAmber
    val unavailableMsg = stringResource(R.string.battery_opt_settings_unavailable)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .testTag("settings_battery_optimization_card"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isUnrestricted) {
                            Icons.Rounded.BatteryChargingFull
                        } else {
                            Icons.Rounded.BatteryAlert
                        },
                        contentDescription = stringResource(R.string.battery_opt_section_title),
                        tint = accentColor,
                        modifier = Modifier.size(22.dp),
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.battery_opt_section_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = accentColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
                        modifier = Modifier.testTag("settings_battery_status_badge"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (isUnrestricted) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                            Text(
                                text = stringResource(
                                    if (isUnrestricted) {
                                        R.string.battery_opt_status_unrestricted
                                    } else {
                                        R.string.battery_opt_status_optimized
                                    },
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = accentColor,
                            )
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.battery_opt_section_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BatteryStepRow(
                        stepNumber = "1",
                        text = stringResource(R.string.battery_opt_step_1),
                        badgeColor = MaterialTheme.colorScheme.primary,
                    )
                    BatteryStepRow(
                        stepNumber = "2",
                        text = stringResource(R.string.battery_opt_step_2),
                        badgeColor = MaterialTheme.colorScheme.primary,
                    )
                    BatteryStepRow(
                        stepNumber = "3",
                        text = stringResource(R.string.battery_opt_step_3),
                        badgeColor = shieldColors.secureGreen,
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )

                    Text(
                        text = stringResource(R.string.battery_opt_oem_tip),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (launchWarning != null) {
                Text(
                    text = launchWarning!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val launched = BatteryOptimizationHelper.openAppBatteryDetails(context)
                        launchWarning = if (launched) null else unavailableMsg
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("settings_battery_app_info_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.battery_opt_open_app_info),
                        maxLines = 1,
                    )
                }

                OutlinedButton(
                    onClick = {
                        val launched = BatteryOptimizationHelper.openBatteryOptimizationList(context)
                        launchWarning = if (launched) null else unavailableMsg
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("settings_battery_optimization_list_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.battery_opt_open_system_list),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun BatteryStepRow(
    stepNumber: String,
    text: String,
    badgeColor: androidx.compose.ui.graphics.Color,
) {
    val cleanText = remember(text, stepNumber) {
        text.removePrefix("$stepNumber. ")
    }
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(badgeColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stepNumber,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = badgeColor,
            )
        }
        Text(
            text = cleanText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

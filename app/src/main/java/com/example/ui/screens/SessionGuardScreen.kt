package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.browser.FairnessVerificationResult
import com.example.browser.GuardAlertLevel
import com.example.browser.MultiplierBucket
import com.example.browser.SessionGuardEngine
import com.example.browser.SessionGuardStats
import com.example.browser.SessionMonitorSyncStatus
import com.example.data.SecurityConfig
import com.example.data.SessionRoundLogEntity
import com.example.ui.theme.LocalShieldColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionGuardScreen(
    config: SecurityConfig,
    stats: SessionGuardStats,
    rounds: List<SessionRoundLogEntity>,
    activeSiteHost: String,
    monitorSyncStatus: SessionMonitorSyncStatus = SessionMonitorSyncStatus.Disabled,
    isBackgroundServiceRunning: Boolean = false,
    onSaveGuardConfig: (enabled: Boolean, timeLimitMinutes: Int, budgetLimit: Double, currencyLabel: String) -> Unit,
    onToggleLiveMonitorSync: (Boolean) -> Unit = {},
    onToggleBackgroundLiveService: (Boolean) -> Unit = {},
    onOpenCloudSync: () -> Unit = {},
    onResetSessionTimer: () -> Unit,
    onLogRound: (siteHost: String, betAmount: Double, crashMultiplier: Double, cashoutMultiplier: Double?, note: String) -> Unit,
    onDeleteRound: (Long) -> Unit,
    onClearRounds: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val shieldColors = LocalShieldColors.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Session Guard & Fair-Play Stats", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Time/budget control • Past-round ledger • SHA-256 verifier",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("session_guard_back")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
        ) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = shieldColors.surfaceAccent,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Budget Guard") },
                    icon = { Icon(Icons.Rounded.Timer, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("tab_budget_guard"),
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Past Stats") },
                    icon = { Icon(Icons.Rounded.Analytics, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("tab_past_stats"),
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Fairness") },
                    icon = { Icon(Icons.Rounded.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("tab_fairness_verifier"),
                )
            }

            when (selectedTab) {
                0 -> BudgetGuardTab(
                    config = config,
                    stats = stats,
                    monitorSyncStatus = monitorSyncStatus,
                    isBackgroundServiceRunning = isBackgroundServiceRunning,
                    onSaveGuardConfig = onSaveGuardConfig,
                    onToggleLiveMonitorSync = onToggleLiveMonitorSync,
                    onToggleBackgroundLiveService = onToggleBackgroundLiveService,
                    onOpenCloudSync = onOpenCloudSync,
                    onResetSessionTimer = onResetSessionTimer,
                )
                1 -> PastRoundStatsTab(
                    currencyLabel = config.sessionCurrencyLabel,
                    stats = stats,
                    rounds = rounds,
                    activeSiteHost = activeSiteHost,
                    onLogRound = onLogRound,
                    onDeleteRound = onDeleteRound,
                    onClearRounds = onClearRounds,
                )
                else -> ProvablyFairVerifierTab()
            }
        }
    }
}

@Composable
private fun BudgetGuardTab(
    config: SecurityConfig,
    stats: SessionGuardStats,
    monitorSyncStatus: SessionMonitorSyncStatus,
    isBackgroundServiceRunning: Boolean,
    onSaveGuardConfig: (enabled: Boolean, timeLimitMinutes: Int, budgetLimit: Double, currencyLabel: String) -> Unit,
    onToggleLiveMonitorSync: (Boolean) -> Unit,
    onToggleBackgroundLiveService: (Boolean) -> Unit,
    onOpenCloudSync: () -> Unit,
    onResetSessionTimer: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    var guardEnabled by remember(config.sessionGuardEnabled) { mutableStateOf(config.sessionGuardEnabled) }
    var timeLimitInput by remember(config.sessionTimeLimitMinutes) { mutableStateOf(config.sessionTimeLimitMinutes.toString()) }
    var budgetLimitInput by remember(config.sessionBudgetLimit) { mutableStateOf("%.0f".format(config.sessionBudgetLimit)) }
    var currencyInput by remember(config.sessionCurrencyLabel) { mutableStateOf(config.sessionCurrencyLabel) }

    val netLoss = max(0.0, -stats.netProfit)
    val timeProgress = if (config.sessionTimeLimitMinutes > 0) {
        (stats.elapsedMinutes.toFloat() / config.sessionTimeLimitMinutes.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val budgetProgress = if (config.sessionBudgetLimit > 0.0) {
        (netLoss / config.sessionBudgetLimit).toFloat().coerceIn(0f, 1f)
    } else 0f

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            val statusColor = when (stats.overallAlertLevel) {
                GuardAlertLevel.LIMIT_REACHED -> MaterialTheme.colorScheme.error
                GuardAlertLevel.APPROACHING_LIMIT -> shieldColors.warningAmber
                GuardAlertLevel.SAFE -> shieldColors.secureGreen
                GuardAlertLevel.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val statusHeadline = when (stats.overallAlertLevel) {
                GuardAlertLevel.LIMIT_REACHED -> "Session Limit Reached — Consider Stopping"
                GuardAlertLevel.APPROACHING_LIMIT -> "Approaching Session Limit (80%+)"
                GuardAlertLevel.SAFE -> "Within Safe Session Limits"
                GuardAlertLevel.OFF -> "Session Guard Paused"
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.55f)),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = if (stats.overallAlertLevel >= GuardAlertLevel.APPROACHING_LIMIT) {
                                Icons.Rounded.WarningAmber
                            } else {
                                Icons.Rounded.Shield
                            },
                            contentDescription = null,
                            tint = statusColor,
                        )
                        Text(
                            text = statusHeadline,
                            style = MaterialTheme.typography.titleSmall,
                            color = statusColor,
                        )
                    }

                    Text(
                        text = "Session Elapsed Time: ${stats.elapsedMinutes} / ${config.sessionTimeLimitMinutes} min",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LinearProgressIndicator(
                        progress = { timeProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = statusColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )

                    Text(
                        text = "Logged Net Loss vs Limit: ${"%.2f".format(netLoss)} / ${"%.2f".format(config.sessionBudgetLimit)} ${config.sessionCurrencyLabel}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LinearProgressIndicator(
                        progress = { budgetProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = statusColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )

                    OutlinedButton(
                        onClick = onResetSessionTimer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reset_session_timer_button"),
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Reset Session Timer")
                    }
                }
            }
        }

        item {
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
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable Session Guard Alerts", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Show banner alert in browser when time or loss limit is reached",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = guardEnabled,
                            onCheckedChange = { guardEnabled = it },
                            modifier = Modifier.testTag("session_guard_switch"),
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = timeLimitInput,
                            onValueChange = { timeLimitInput = it.filter(Char::isDigit).take(3) },
                            label = { Text("Time Limit (min)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = budgetLimitInput,
                            onValueChange = { budgetLimitInput = it },
                            label = { Text("Loss Limit") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = currencyInput,
                            onValueChange = { currencyInput = it.take(6) },
                            label = { Text("Currency") },
                            singleLine = true,
                            modifier = Modifier.width(96.dp),
                        )
                    }

                    Button(
                        onClick = {
                            val mins = timeLimitInput.toIntOrNull()?.coerceIn(5, 600) ?: 30
                            val budget = budgetLimitInput.toDoubleOrNull()?.coerceAtLeast(1.0) ?: 100.0
                            onSaveGuardConfig(guardEnabled, mins, budget, currencyInput)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("save_guard_config_button"),
                    ) {
                        Text("Save Session Limits")
                    }
                }
            }
        }

        item {
            val statusText = when (monitorSyncStatus) {
                is SessionMonitorSyncStatus.SyncingLive -> "Syncing live stats to Firebase Firestore"
                is SessionMonitorSyncStatus.SignInRequired -> "Google Sign-In required in Cloud Sync to stream stats"
                is SessionMonitorSyncStatus.Error -> "Sync error: ${monitorSyncStatus.message}"
                is SessionMonitorSyncStatus.Disabled -> "Paused — enable to stream stats to your signed-in devices"
            }
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
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Live Remote Monitor Sync (Firestore)", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = when (monitorSyncStatus) {
                                    is SessionMonitorSyncStatus.SyncingLive -> shieldColors.secureGreen
                                    is SessionMonitorSyncStatus.Error -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Switch(
                            checked = config.liveCloudMonitorSyncEnabled,
                            onCheckedChange = onToggleLiveMonitorSync,
                            modifier = Modifier.testTag("live_cloud_monitor_switch"),
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenCloudSync,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("open_remote_monitor_button"),
                    ) {
                        Text("Open Remote Monitor & Google Sign-In")
                    }
                }
            }
        }

        item {
            val context = LocalContext.current
            var permissionDeniedMessage by remember { mutableStateOf<String?>(null) }
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) {
                    permissionDeniedMessage = null
                    onToggleBackgroundLiveService(true)
                } else {
                    permissionDeniedMessage = "Notification permission denied — Android requires it to run a persistent live background service."
                }
            }

            val isRunningOrEnabled = isBackgroundServiceRunning || config.backgroundLiveServiceEnabled
            val serviceBadgeColor = if (isBackgroundServiceRunning) {
                shieldColors.secureGreen
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    1.dp,
                    if (isBackgroundServiceRunning) {
                        shieldColors.secureGreen.copy(alpha = 0.6f)
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
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Keep App Live in Background", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (isBackgroundServiceRunning) {
                                    "LIVE • Foreground Service active. Session clock, stats & Firestore sync continue even if you leave the app."
                                } else {
                                    "Uses a persistent Android notification (dataSync service) so monitoring stays alive after leaving the app."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = serviceBadgeColor,
                            )
                        }
                        Switch(
                            checked = isRunningOrEnabled,
                            onCheckedChange = { wantEnable ->
                                permissionDeniedMessage = null
                                if (!wantEnable) {
                                    onToggleBackgroundLiveService(false)
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    onToggleBackgroundLiveService(true)
                                }
                            },
                            modifier = Modifier.testTag("background_live_service_switch"),
                        )
                    }

                    if (permissionDeniedMessage != null) {
                        Text(
                            text = permissionDeniedMessage.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PastRoundStatsTab(
    currencyLabel: String,
    stats: SessionGuardStats,
    rounds: List<SessionRoundLogEntity>,
    activeSiteHost: String,
    onLogRound: (siteHost: String, betAmount: Double, crashMultiplier: Double, cashoutMultiplier: Double?, note: String) -> Unit,
    onDeleteRound: (Long) -> Unit,
    onClearRounds: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    var betInput by remember { mutableStateOf("1.00") }
    var crashInput by remember { mutableStateOf("") }
    var cashoutInput by remember { mutableStateOf("") }
    var noteInput by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }
    val dateFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                shape = MaterialTheme.shapes.medium,
                color = shieldColors.warningAmberContainer.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, shieldColors.warningAmber.copy(alpha = 0.4f)),
            ) {
                Text(
                    text = "Statistical Notice: Every round is generated independently by the server. Logging completed rounds helps track your real spending and distribution, not predict future rounds.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        item {
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
                    Text("Log Completed Round", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = betInput,
                            onValueChange = { betInput = it },
                            label = { Text("Bet ($currencyLabel)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = crashInput,
                            onValueChange = { crashInput = it },
                            label = { Text("Crash x") },
                            placeholder = { Text("e.g. 2.45") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = cashoutInput,
                            onValueChange = { cashoutInput = it },
                            label = { Text("Cashout x") },
                            placeholder = { Text("blank=lost") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it.take(80) },
                        label = { Text("Optional Note") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (formError != null) {
                        Text(
                            text = formError.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = {
                            val bet = betInput.toDoubleOrNull()
                            val crash = crashInput.toDoubleOrNull()
                            val cashout = cashoutInput.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                            if (bet == null || bet < 0.0 || crash == null || crash < 1.0) {
                                formError = "Enter valid Bet (>=0) and Crash Multiplier (>=1.00)."
                            } else if (cashoutInput.isNotBlank() && (cashout == null || cashout < 1.0)) {
                                formError = "Cashout must be >= 1.00 or left blank if lost."
                            } else {
                                formError = null
                                onLogRound(activeSiteHost, bet, crash, cashout, noteInput)
                                crashInput = ""
                                cashoutInput = ""
                                noteInput = ""
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("log_round_button"),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add Completed Round")
                    }
                }
            }
        }

        item {
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
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Recorded Distribution (${stats.totalRounds} rounds)", style = MaterialTheme.typography.titleSmall)
                        if (rounds.isNotEmpty()) {
                            TextButton(onClick = onClearRounds) {
                                Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Clear")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        StatBadge("Net P/L", "${"%+.2f".format(stats.netProfit)} $currencyLabel")
                        StatBadge("Avg Crash", "${"%.2f".format(stats.averageCrashMultiplier)}x")
                        StatBadge("Max Crash", "${"%.2f".format(stats.maxCrashMultiplier)}x")
                        StatBadge("Wins", "${stats.winsCount}/${stats.totalRounds}")
                    }

                    MultiplierBucketBarChart(
                        buckets = stats.buckets,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                    )
                }
            }
        }

        items(rounds, key = { it.id }) { round ->
            val won = round.cashoutMultiplier != null
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Crash ${"%.2f".format(round.crashMultiplier)}x • Bet ${"%.2f".format(round.betAmount)} $currencyLabel",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = buildString {
                                if (won) {
                                    append("Cashout ${"%.2f".format(round.cashoutMultiplier)}x • ")
                                } else {
                                    append("Lost • ")
                                }
                                append("Net ${"%+.2f".format(round.netProfit)} $currencyLabel • ")
                                append(dateFormatter.format(Date(round.createdAt)))
                                if (round.note.isNotBlank()) append(" • ${round.note}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (round.netProfit >= 0) shieldColors.secureGreen else MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { onDeleteRound(round.id) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete round", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBadge(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MultiplierBucketBarChart(
    buckets: List<MultiplierBucket>,
    modifier: Modifier = Modifier,
) {
    val shieldColors = LocalShieldColors.current
    val maxCount = (buckets.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)
    val barColor = shieldColors.surfaceAccent
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (buckets.isEmpty()) return@Canvas
            val spacing = 16.dp.toPx()
            val barWidth = ((size.width - spacing * (buckets.size - 1)) / buckets.size).coerceAtLeast(12f)
            buckets.forEachIndexed { idx, bucket ->
                val x = idx * (barWidth + spacing)
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(x, 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = CornerRadius(10f, 10f),
                )
                val fraction = bucket.count.toFloat() / maxCount.toFloat()
                val h = (size.height * fraction).coerceAtLeast(if (bucket.count > 0) 8f else 0f)
                if (h > 0f) {
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, size.height - h),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(10f, 10f),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            buckets.forEach { bucket ->
                Text(
                    text = "${bucket.label} (${bucket.count})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProvablyFairVerifierTab() {
    val shieldColors = LocalShieldColors.current
    var serverSeed by remember { mutableStateOf("") }
    var clientSeed by remember { mutableStateOf("") }
    var nonce by remember { mutableStateOf("1") }
    var expectedHash by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<FairnessVerificationResult?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, shieldColors.surfaceAccent.copy(alpha = 0.4f)),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Provably Fair Post-Round SHA-256 Verifier", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Online crash games keep the Server Seed secret until the round ends, revealing only its SHA-256 hash before takeoff. After a round finishes, paste the revealed seeds below to verify fairness mathematically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = serverSeed,
                        onValueChange = { serverSeed = it },
                        label = { Text("Revealed Server Seed (post-round)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = clientSeed,
                        onValueChange = { clientSeed = it },
                        label = { Text("Client Seed") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = nonce,
                            onValueChange = { nonce = it },
                            label = { Text("Nonce") },
                            singleLine = true,
                            modifier = Modifier.width(110.dp),
                        )
                        OutlinedTextField(
                            value = expectedHash,
                            onValueChange = { expectedHash = it },
                            label = { Text("Pre-Round SHA-256 Hash (optional)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Button(
                        onClick = {
                            result = SessionGuardEngine.verifyCrashFairness(
                                serverSeed = serverSeed,
                                clientSeed = clientSeed,
                                nonce = nonce,
                                expectedServerSeedHash = expectedHash,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("verify_fairness_button"),
                    ) {
                        Icon(Icons.Rounded.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Verify Completed Round")
                    }

                    result?.let { res ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = res.summary,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when (res.commitmentMatched) {
                                        false -> MaterialTheme.colorScheme.error
                                        true -> shieldColors.secureGreen
                                        null -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                if (res.isValidInput) {
                                    Text(
                                        text = "SHA-256(ServerSeed): ${res.computedServerSeedSha256}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Text(
                                        text = "HMAC-SHA256: ${res.hmacSha256Hex}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.example.data.repository.AdminClaimStatus
import com.example.data.repository.AdminServerConfig
import com.example.data.repository.AdminSettingsRepository
import com.example.data.repository.MaintenanceLogEntry
import com.example.ui.AdminConfigUiState
import com.example.ui.AdminSettingsViewModel
import com.example.ui.MaintenanceLogsUiState
import com.example.ui.screens.resolveDefaultWebClientId
import com.example.ui.theme.LocalShieldColors
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminClaimIdentityBanner(
    claimStatus: AdminClaimStatus.Authorized,
    configUiState: AdminConfigUiState,
    isSaving: Boolean,
) {
    val shieldColors = LocalShieldColors.current
    val syncLabel = when (configUiState) {
        AdminConfigUiState.Loading -> "Syncing…"
        is AdminConfigUiState.Error -> "Sync Error"
        is AdminConfigUiState.Success -> when {
            isSaving || configUiState.hasPendingWrites -> "Pending Cloud Write"
            configUiState.isFromCache -> "Offline Cache"
            else -> "Live Synced"
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .testTag("admin_settings_claim_banner"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, shieldColors.secureGreen.copy(alpha = 0.45f)),
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VerifiedUser,
                        contentDescription = "Admin custom claim verified",
                        tint = shieldColors.secureGreen,
                    )
                    Column {
                        Text("Protected Admin Session", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = claimStatus.user.email ?: claimStatus.user.uid,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                StatusBadgeChip(label = syncLabel, color = shieldColors.torAccent)
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                claimStatus.matchedClaims.forEach { claim ->
                    StatusBadgeChip(label = "Claim: $claim", color = shieldColors.secureGreen)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServerPrivacyOverridesCard(
    configUiState: AdminConfigUiState,
    isSaving: Boolean,
    onToggleMaintenanceMode: (Boolean) -> Unit,
    onToggleStrictTor: (Boolean) -> Unit,
    onToggleRemoteTelemetry: (Boolean) -> Unit,
    onToggleForceAdBlockSync: (Boolean) -> Unit,
    onToggleEmergencyLockdown: (Boolean) -> Unit,
    onSaveBanner: (String) -> Unit,
    onApplyOverridesToLocalBrowser: ((AdminServerConfig) -> Unit)? = null,
) {
    val shieldColors = LocalShieldColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .testTag("admin_settings_config_card"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = "Server privacy overrides",
                    tint = shieldColors.torAccent,
                )
                Column {
                    Text("Server-Side Privacy Overrides", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Remote fleet policies synced to Firestore /admin_config",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            when (configUiState) {
                AdminConfigUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is AdminConfigUiState.Error -> {
                    Text(
                        text = configUiState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("admin_settings_config_error"),
                    )
                }

                is AdminConfigUiState.Success -> {
                    val config = configUiState.config
                    var bannerDraft by remember(config.maintenanceBanner) {
                        mutableStateOf(config.maintenanceBanner)
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StatusBadgeChip(
                            label = if (config.emergencyLockdown) "Lockdown: ACTIVE" else "Lockdown: OFF",
                            color = if (config.emergencyLockdown) MaterialTheme.colorScheme.error else shieldColors.secureGreen,
                        )
                        StatusBadgeChip(
                            label = if (config.strictTorRequired) "Strict Tor: ON" else "Strict Tor: OFF",
                            color = if (config.strictTorRequired) shieldColors.torAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        StatusBadgeChip(
                            label = if (config.forceAdBlockSync) "Ad-Block Sync: FORCED" else "Ad-Block Sync: AUTO",
                            color = if (config.forceAdBlockSync) shieldColors.secureGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    ServerToggleRow(
                        title = "Emergency Privacy Lockdown",
                        subtitle = "Override local client shields to force No-Script, WebRTC guard & HTTPS-Only",
                        checked = config.emergencyLockdown,
                        enabled = !isSaving,
                        testTag = "admin_settings_switch_emergency_lockdown",
                        onCheckedChange = onToggleEmergencyLockdown,
                    )
                    ServerToggleRow(
                        title = "Strict Tor Routing Required",
                        subtitle = "Mandate .onion circuit routing across managed browser profiles",
                        checked = config.strictTorRequired,
                        enabled = !isSaving,
                        testTag = "admin_settings_switch_strict_tor",
                        onCheckedChange = onToggleStrictTor,
                    )
                    ServerToggleRow(
                        title = "Force Global Ad-Block Rules Sync",
                        subtitle = "Push updated tracker & cosmetic filter overrides on launch",
                        checked = config.forceAdBlockSync,
                        enabled = !isSaving,
                        testTag = "admin_settings_switch_adblock_sync",
                        onCheckedChange = onToggleForceAdBlockSync,
                    )
                    ServerToggleRow(
                        title = "Maintenance Mode",
                        subtitle = "Display server notice banner and pause non-essential remote sync",
                        checked = config.maintenanceMode,
                        enabled = !isSaving,
                        testTag = "admin_settings_switch_maintenance",
                        onCheckedChange = onToggleMaintenanceMode,
                    )
                    ServerToggleRow(
                        title = "Remote Telemetry & Health Stream",
                        subtitle = "Collect anonymized circuit & crash diagnostics from endpoints",
                        checked = config.remoteTelemetryEnabled,
                        enabled = !isSaving,
                        testTag = "admin_settings_switch_telemetry",
                        onCheckedChange = onToggleRemoteTelemetry,
                    )

                    if (onApplyOverridesToLocalBrowser != null) {
                        OutlinedButton(
                            onClick = { onApplyOverridesToLocalBrowser(config) },
                            enabled = !isSaving,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("admin_settings_apply_local_overrides_button"),
                        ) {
                            Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Apply Active Overrides to This Browser")
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    OutlinedTextField(
                        value = bannerDraft,
                        onValueChange = { bannerDraft = it.take(AdminSettingsRepository.MAX_BANNER_LENGTH) },
                        label = { Text("Server Maintenance Notice") },
                        placeholder = { Text("Scheduled relay maintenance at 02:00 UTC") },
                        maxLines = 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_settings_banner_input"),
                    )
                    Button(
                        onClick = { onSaveBanner(bannerDraft) },
                        enabled = !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("admin_settings_save_banner_button"),
                    ) {
                        Text("Save Server Notice")
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MaintenanceLogsHeaderCard(
    logsUiState: MaintenanceLogsUiState,
    selectedSeverity: String,
    onSelectSeverity: (String) -> Unit,
    onOpenAddLog: () -> Unit,
) {
    val totalCount = (logsUiState as? MaintenanceLogsUiState.Success)?.entries?.size ?: 0
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Maintenance Logs ($totalCount)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Auditable server override & operations feed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onOpenAddLog,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("admin_settings_add_log_button"),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Log Event")
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val filters = listOf(AdminSettingsViewModel.FILTER_ALL) + MaintenanceLogEntry.VALID_SEVERITIES
                filters.forEach { level ->
                    FilterChip(
                        selected = selectedSeverity == level,
                        onClick = { onSelectSeverity(level) },
                        label = { Text(level) },
                        modifier = Modifier.testTag("admin_settings_filter_${level.lowercase()}"),
                    )
                }
            }
        }
    }
}

@Composable
fun MaintenanceLogCard(
    entry: MaintenanceLogEntry,
    onDelete: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    val severityColor = when (entry.severity) {
        MaintenanceLogEntry.SEVERITY_CRITICAL -> MaterialTheme.colorScheme.error
        MaintenanceLogEntry.SEVERITY_WARN -> shieldColors.warningAmber
        else -> shieldColors.secureGreen
    }
    val timestampMs = entry.createdAt?.toDate()?.time ?: entry.clientTimestampMs
    val formattedTime = remember(timestampMs) {
        if (timestampMs <= 0L) "Pending timestamp"
        else SimpleDateFormat("MMM dd, HH:mm:ss", Locale.US).format(Date(timestampMs))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .testTag("admin_settings_log_item_${entry.id}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, severityColor.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    StatusBadgeChip(label = entry.severity, color = severityColor)
                    StatusBadgeChip(label = entry.category, color = MaterialTheme.colorScheme.primary)
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("admin_settings_delete_log_${entry.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Delete maintenance log",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = entry.action,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )

            if (entry.details.isNotBlank()) {
                Text(
                    text = entry.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = entry.actorEmail.ifBlank { entry.actorUid },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddMaintenanceLogDialog(
    onDismiss: () -> Unit,
    onSubmit: (action: String, category: String, severity: String, details: String) -> Unit,
) {
    var actionText by remember { mutableStateOf("") }
    var detailsText by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(MaintenanceLogEntry.CATEGORY_MAINTENANCE) }
    var selectedSeverity by remember { mutableStateOf(MaintenanceLogEntry.SEVERITY_INFO) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record Maintenance Log") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = actionText,
                    onValueChange = { actionText = it.take(AdminSettingsRepository.MAX_ACTION_LENGTH) },
                    label = { Text("Action summary") },
                    placeholder = { Text("Rotated Tor directory authority keys") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("admin_settings_log_action_input"),
                )
                OutlinedTextField(
                    value = detailsText,
                    onValueChange = { detailsText = it.take(AdminSettingsRepository.MAX_DETAILS_LENGTH) },
                    label = { Text("Operational details") },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("admin_settings_log_details_input"),
                )
                Text("Category", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MaintenanceLogEntry.VALID_CATEGORIES.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat) },
                        )
                    }
                }
                Text("Severity", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MaintenanceLogEntry.VALID_SEVERITIES.forEach { sev ->
                        FilterChip(
                            selected = selectedSeverity == sev,
                            onClick = { selectedSeverity = sev },
                            label = { Text(sev) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(actionText, selectedCategory, selectedSeverity, detailsText) },
                enabled = actionText.isNotBlank(),
                modifier = Modifier.testTag("admin_settings_log_submit_button"),
            ) {
                Text("Record")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun StatusBadgeChip(label: String, color: Color) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminSignInGateScreen(
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shieldColors = LocalShieldColors.current
    var isSigningIn by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Admin Settings") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("admin_settings_gate_back_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp)
                    .testTag("admin_settings_signin_gate"),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AdminPanelSettings,
                        contentDescription = "Protected admin access",
                        tint = shieldColors.torAccent,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = "Custom 'admin' Claim Required",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "Sign in with a Google account carrying the Firebase Auth custom 'admin' claim to manage server-side privacy overrides and view maintenance logs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (authError != null) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                        ) {
                            Text(
                                text = authError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                    Button(
                        onClick = {
                            isSigningIn = true
                            authError = null
                            launchAdminGoogleSignIn(
                                context = context,
                                credentialManager = credentialManager,
                                scope = scope,
                                onSuccess = {
                                    isSigningIn = false
                                    onAuthSuccess()
                                },
                                onError = { msg ->
                                    isSigningIn = false
                                    authError = msg
                                },
                                onCancelled = {
                                    isSigningIn = false
                                },
                            )
                        },
                        enabled = !isSigningIn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("admin_settings_signin_button"),
                    ) {
                        if (isSigningIn) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Sign in with Google")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminClaimCheckingScreen(onNavigateBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Admin Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("admin_settings_checking_claims"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = "Verifying custom 'admin' Firebase claim…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminClaimsDeniedScreen(
    status: AdminClaimStatus.Denied,
    onRefreshClaims: () -> Unit,
    onSignOut: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Admin Settings") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("admin_settings_denied_back_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .testTag("admin_settings_denied_card"),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GppBad,
                        contentDescription = "Admin claim denied",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = "Access Denied: Missing 'admin' Claim",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = status.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(
                                text = status.user.email ?: status.user.uid,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    Button(
                        onClick = onRefreshClaims,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("admin_settings_retry_claims_button"),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Refresh Token Claims")
                    }
                    OutlinedButton(
                        onClick = onSignOut,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("admin_settings_switch_account_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Switch Account")
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findAdminActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findAdminActivity()
    else -> null
}

private fun launchAdminGoogleSignIn(
    context: Context,
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
    onCancelled: () -> Unit,
) {
    val clientId = resolveDefaultWebClientId(context)
    if (clientId == null) {
        onError("Google Sign-In configuration missing: default_web_client_id not found")
        return
    }
    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

    scope.launch {
        try {
            val activity = context.findAdminActivity()
                ?: throw IllegalStateException("Google Sign-In requires an Activity context.")
            val result = credentialManager.getCredential(activity, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                Firebase.auth.signInWithCredential(authCredential).await()
                onSuccess()
            } else {
                onError("Unexpected credential type returned.")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w("AdminAuth", "Google Sign-In cancelled: ${e.message}", e)
            onCancelled()
            onError(
                "Sign-in was cancelled or dismissed by Google Play Services. Make sure a Google account is signed in on this device/emulator and try again.",
            )
        } catch (e: NoCredentialException) {
            Log.w("AdminAuth", "No Google credentials available: ${e.message}", e)
            onError(
                "No Google account is available on this device/emulator. Add a Google account in Settings > Google (or Play Store), then retry.",
            )
        } catch (e: GetCredentialUnsupportedException) {
            Log.w("AdminAuth", "Credential Manager unsupported: ${e.message}", e)
            onError(
                "Google Play Services Credential Manager is unavailable or outdated on this device/emulator.",
            )
        } catch (e: GetCredentialException) {
            Log.e("AdminAuth", "Credential Manager sign-in error: ${e.type}", e)
            onError(e.localizedMessage ?: "Google Sign-In failed (${e.type}).")
        } catch (e: Exception) {
            Log.e("AdminAuth", "Admin Google Sign-In failed", e)
            onError(e.localizedMessage ?: "Google Sign-In failed")
        }
    }
}

fun signOutAdminSession(
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onComplete: () -> Unit,
) {
    scope.launch {
        withContext(NonCancellable) {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w("AdminAuth", "Failed to clear credential state: ${e.message}")
            } finally {
                Firebase.auth.signOut()
                onComplete()
            }
        }
    }
}

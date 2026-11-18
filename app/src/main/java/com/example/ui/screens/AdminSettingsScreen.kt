package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.repository.AdminClaimStatus
import com.example.data.repository.AdminServerConfig
import com.example.data.repository.AdminSettingsRepository
import com.example.data.repository.AuthContextProvider
import com.example.data.repository.FirebaseAuthContextProvider
import com.example.data.repository.FirestoreClientProvider
import com.example.ui.AdminSettingsViewModel
import com.example.ui.MaintenanceLogsUiState
import com.example.ui.components.AddMaintenanceLogDialog
import com.example.ui.components.AdminClaimCheckingScreen
import com.example.ui.components.AdminClaimIdentityBanner
import com.example.ui.components.AdminClaimsDeniedScreen
import com.example.ui.components.AdminSignInGateScreen
import com.example.ui.components.MaintenanceLogCard
import com.example.ui.components.MaintenanceLogsHeaderCard
import com.example.ui.components.ServerPrivacyOverridesCard
import com.example.ui.components.signOutAdminSession
import com.example.ui.theme.LocalShieldColors
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

/**
 * Protected route guard for [AdminSettingsScreen].
 * Verifies that the authenticated user's Firebase Auth ID token carries the custom `'admin'` claim
 * (`claims["admin"] == true`) before mounting or subscribing to server-side privacy overrides or maintenance logs.
 */
@Composable
fun AdminSettingsRoute(
    onNavigateBack: () -> Unit,
    authContextProvider: AuthContextProvider = remember { FirebaseAuthContextProvider() },
    allowTrustedRegistryFallback: Boolean = false,
    onApplyOverridesToLocalBrowser: ((AdminServerConfig) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val adminRepoForGate = remember(context, authContextProvider) {
        AdminSettingsRepository(
            db = FirestoreClientProvider.getInstance(context),
            auth = Firebase.auth,
            authContextProvider = authContextProvider,
        )
    }

    val activeUserId by authContextProvider.observeUserId()
        .collectAsStateWithLifecycle(initialValue = authContextProvider.currentUser?.uid)

    var claimStatus by remember {
        mutableStateOf<AdminClaimStatus>(
            if (activeUserId == null) AdminClaimStatus.SignedOut else AdminClaimStatus.Checking,
        )
    }
    var claimCheckTrigger by remember { mutableIntStateOf(0) }
    var forceTokenRefresh by remember { mutableStateOf(false) }

    LaunchedEffect(activeUserId, claimCheckTrigger) {
        if (activeUserId == null) {
            claimStatus = AdminClaimStatus.SignedOut
            return@LaunchedEffect
        }
        claimStatus = AdminClaimStatus.Checking
        val tokenResult = authContextProvider.checkAdminClaims(forceRefresh = forceTokenRefresh)
        forceTokenRefresh = false
        claimStatus = when (tokenResult) {
            is AdminClaimStatus.Authorized -> tokenResult
            is AdminClaimStatus.Denied -> {
                if (allowTrustedRegistryFallback && (
                        AdminSettingsRepository.isBootstrappedAdmin(tokenResult.user) ||
                            adminRepoForGate.hasTrustedAdminDocument(tokenResult.user.uid)
                        )
                ) {
                    AdminClaimStatus.Authorized(
                        user = tokenResult.user,
                        matchedClaims = setOf("trusted_admin_registry"),
                        viaTrustedRegistry = true,
                    )
                } else {
                    tokenResult
                }
            }
            AdminClaimStatus.Checking -> AdminClaimStatus.Checking
            AdminClaimStatus.SignedOut -> AdminClaimStatus.SignedOut
        }
    }

    when (val status = claimStatus) {
        AdminClaimStatus.SignedOut -> {
            AdminSignInGateScreen(
                credentialManager = credentialManager,
                onAuthSuccess = {
                    forceTokenRefresh = true
                    claimCheckTrigger++
                },
                onNavigateBack = onNavigateBack,
            )
        }

        AdminClaimStatus.Checking -> {
            AdminClaimCheckingScreen(onNavigateBack = onNavigateBack)
        }

        is AdminClaimStatus.Denied -> {
            AdminClaimsDeniedScreen(
                status = status,
                onRefreshClaims = {
                    forceTokenRefresh = true
                    claimCheckTrigger++
                },
                onSignOut = {
                    signOutAdminSession(credentialManager, scope) {
                        claimStatus = AdminClaimStatus.SignedOut
                    }
                },
                onNavigateBack = onNavigateBack,
            )
        }

        is AdminClaimStatus.Authorized -> {
            key(status.user.uid) {
                val viewModel: AdminSettingsViewModel = viewModel(
                    key = "admin_settings_${status.user.uid}",
                    factory = viewModelFactory {
                        initializer {
                            val app = checkNotNull(this[APPLICATION_KEY]) {
                                "APPLICATION_KEY missing from CreationExtras"
                            }
                            AdminSettingsViewModel(
                                repository = AdminSettingsRepository(
                                    db = FirestoreClientProvider.getInstance(app),
                                    auth = Firebase.auth,
                                    authContextProvider = authContextProvider,
                                ),
                                currentUserId = status.user.uid,
                            )
                        }
                    },
                )
                AdminSettingsScreen(
                    claimStatus = status,
                    viewModel = viewModel,
                    onRefreshClaims = {
                        forceTokenRefresh = true
                        claimCheckTrigger++
                    },
                    onSignOut = {
                        signOutAdminSession(credentialManager, scope) {
                            claimStatus = AdminClaimStatus.SignedOut
                        }
                    },
                    onApplyOverridesToLocalBrowser = onApplyOverridesToLocalBrowser,
                    onNavigateBack = onNavigateBack,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminSettingsScreen(
    claimStatus: AdminClaimStatus.Authorized,
    viewModel: AdminSettingsViewModel,
    onRefreshClaims: () -> Unit,
    onSignOut: () -> Unit,
    onApplyOverridesToLocalBrowser: ((AdminServerConfig) -> Unit)? = null,
    onNavigateBack: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    val snackbarHostState = remember { SnackbarHostState() }

    val configUiState by viewModel.configUiState.collectAsStateWithLifecycle()
    val logsUiState by viewModel.logsUiState.collectAsStateWithLifecycle()
    val filteredLogs by viewModel.filteredLogs.collectAsStateWithLifecycle()
    val selectedSeverity by viewModel.selectedSeverityFilter.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    var showAddLogDialog by remember { mutableStateOf(false) }

    LaunchedEffect(statusMessage) {
        val msg = statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Admin Settings")
                        Text(
                            text = "Privacy Overrides & Maintenance Logs",
                            style = MaterialTheme.typography.labelSmall,
                            color = shieldColors.torAccent,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("admin_settings_back_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefreshClaims,
                        modifier = Modifier.testTag("admin_settings_refresh_claims_button"),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh admin token claims")
                    }
                    IconButton(
                        onClick = onSignOut,
                        modifier = Modifier.testTag("admin_settings_sign_out_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = "Sign out")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                AdminClaimIdentityBanner(
                    claimStatus = claimStatus,
                    configUiState = configUiState,
                    isSaving = isSaving,
                )
            }

            item {
                ServerPrivacyOverridesCard(
                    configUiState = configUiState,
                    isSaving = isSaving,
                    onToggleMaintenanceMode = viewModel::toggleMaintenanceMode,
                    onToggleStrictTor = viewModel::toggleStrictTorRequired,
                    onToggleRemoteTelemetry = viewModel::toggleRemoteTelemetry,
                    onToggleForceAdBlockSync = viewModel::toggleForceAdBlockSync,
                    onToggleEmergencyLockdown = viewModel::toggleEmergencyLockdown,
                    onSaveBanner = viewModel::saveMaintenanceBanner,
                    onApplyOverridesToLocalBrowser = onApplyOverridesToLocalBrowser,
                )
            }

            item {
                MaintenanceLogsHeaderCard(
                    logsUiState = logsUiState,
                    selectedSeverity = selectedSeverity,
                    onSelectSeverity = viewModel::setSeverityFilter,
                    onOpenAddLog = { showAddLogDialog = true },
                )
            }

            when (val logsState = logsUiState) {
                MaintenanceLogsUiState.Loading -> {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(28.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }

                is MaintenanceLogsUiState.Error -> {
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 640.dp)
                                .testTag("admin_settings_logs_error"),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Text(
                                    text = logsState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                is MaintenanceLogsUiState.Success -> {
                    if (filteredLogs.isEmpty()) {
                        item {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 640.dp)
                                    .testTag("admin_settings_logs_empty"),
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                            ) {
                                Text(
                                    text = "No maintenance log entries match the selected filter.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(24.dp),
                                )
                            }
                        }
                    } else {
                        items(filteredLogs, key = { it.id }) { entry ->
                            MaintenanceLogCard(
                                entry = entry,
                                onDelete = { viewModel.deleteMaintenanceLog(entry.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddLogDialog) {
        AddMaintenanceLogDialog(
            onDismiss = { showAddLogDialog = false },
            onSubmit = { action, category, severity, details ->
                viewModel.appendMaintenanceLog(action, category, severity, details)
                showAddLogDialog = false
            },
        )
    }
}

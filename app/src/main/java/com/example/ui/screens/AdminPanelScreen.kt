package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.BrowserMode
import com.example.ui.BrowserUiState
import com.example.ui.BrowserViewModel
import com.example.ui.components.AdminDomainGovernanceCard
import com.example.ui.components.AdminHeroScoreCard
import com.example.ui.components.AdminMaintenanceAndShortcutsCard
import com.example.ui.components.AdminPolicyRow
import com.example.ui.components.AdminTelemetryGridCard
import com.example.ui.theme.LocalShieldColors
import kotlinx.coroutines.launch

private enum class PendingAdminAction {
    SHRED_SESSION,
    CLEAR_HISTORY,
    CLEAR_DOWNLOADS,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminPanelScreen(
    uiState: BrowserUiState,
    viewModel: BrowserViewModel,
    bookmarksCount: Int,
    historyCount: Int,
    downloadsCount: Int,
    onOpenSecuritySettings: () -> Unit,
    onOpenAdminSettings: () -> Unit = {},
    onOpenCloudSync: () -> Unit,
    onOpenSessionGuard: () -> Unit,
    onOpenVault: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val shieldColors = LocalShieldColors.current
    val config = uiState.securityConfig
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val isForegroundServiceRunning by viewModel.isForegroundServiceRunning.collectAsStateWithLifecycle()
    var isAdminVerified by remember { mutableStateOf(false) }
    var pendingDestructiveAction by remember { mutableStateOf<PendingAdminAction?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.setBackgroundLiveServiceEnabled(true)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Notification permission is required for Live Keep-Alive.")
            }
        }
    }

    val verifyAdminWithDeviceCredential: () -> Unit = {
        val activity = context as? FragmentActivity
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuth = activity?.let {
            BiometricManager.from(it).canAuthenticate(authenticators)
        } ?: BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE

        if (activity != null && canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        isAdminVerified = true
                        scope.launch { snackbarHostState.showSnackbar("Admin identity verified.") }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        scope.launch { snackbarHostState.showSnackbar("Verification cancelled: $errString") }
                    }
                },
            )
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Verify Admin Access")
                .setSubtitle("Confirm device biometric or screen lock credential")
                .setAllowedAuthenticators(authenticators)
                .build()
            prompt.authenticate(promptInfo)
        } else {
            isAdminVerified = true
            scope.launch {
                snackbarHostState.showSnackbar("Local device admin verification active.")
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Admin Panel")
                        Text(
                            text = "Security Governance • Telemetry • Hygiene",
                            style = MaterialTheme.typography.labelSmall,
                            color = shieldColors.torAccent,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("admin_back_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
                AdminHeroScoreCard(
                    config = config,
                    torProxyState = uiState.torProxyState,
                    isForegroundServiceRunning = isForegroundServiceRunning,
                    isAdminVerified = isAdminVerified,
                    onVerifyAdminClick = verifyAdminWithDeviceCredential,
                    onLockdownClick = {
                        viewModel.applyPrivacyPreset(maximumPrivacy = true)
                        scope.launch { snackbarHostState.showSnackbar("Maximum Lockdown policy applied.") }
                    },
                    onStandardDefaultsClick = {
                        viewModel.applyPrivacyPreset(maximumPrivacy = false)
                        scope.launch { snackbarHostState.showSnackbar("Standard privacy policy restored.") }
                    },
                )
            }

            item {
                AdminTelemetryGridCard(
                    openTabsCount = uiState.tabs.size,
                    trackersBlocked = uiState.totalTrackersBlocked,
                    scriptsBlocked = uiState.totalScriptsBlocked,
                    bookmarksCount = bookmarksCount,
                    historyCount = historyCount,
                    downloadsCount = downloadsCount,
                )
            }

            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp)
                        .testTag("admin_policy_card"),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Master Policy Enforcement", style = MaterialTheme.typography.titleMedium)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        AdminPolicyRow(
                            title = "Force Tor .onion Routing",
                            subtitle = "Route active browser traffic through ${config.torEndpoint}",
                            checked = config.browserMode == BrowserMode.TOR_ONION,
                            testTag = "admin_switch_tor_mode",
                            onCheckedChange = { enable ->
                                viewModel.switchBrowserMode(if (enable) BrowserMode.TOR_ONION else BrowserMode.SURFACE)
                            },
                        )
                        AdminPolicyRow(
                            title = "Block JavaScript (No-Script)",
                            subtitle = "Prevent arbitrary JavaScript execution on untrusted hosts",
                            checked = config.noScriptEnabled,
                            testTag = "admin_switch_noscript",
                            onCheckedChange = viewModel::setNoScript,
                        )
                        AdminPolicyRow(
                            title = "WebRTC Leak Protection",
                            subtitle = "Disable peer-to-peer IP discovery and ICE candidates",
                            checked = config.webRtcLeakProtectionEnabled,
                            testTag = "admin_switch_webrtc",
                            onCheckedChange = viewModel::setWebRtcLeakProtection,
                        )
                        AdminPolicyRow(
                            title = "HTTPS-Only Enforcement",
                            subtitle = "Upgrade clearnet connections and block insecure mixed assets",
                            checked = config.httpsEverywhereEnabled,
                            testTag = "admin_switch_https",
                            onCheckedChange = viewModel::setHttpsEverywhere,
                        )
                        AdminPolicyRow(
                            title = "Ad & Tracker Blocker",
                            subtitle = "Block ads, trackers, popups & cosmetic ad containers",
                            checked = config.trackerBlockerEnabled,
                            testTag = "admin_switch_adblock",
                            onCheckedChange = viewModel::setTrackerBlocker,
                        )
                        AdminPolicyRow(
                            title = "Background Keep-Alive Service",
                            subtitle = "Keep browser session & Tor proxy checks alive via notification",
                            checked = isForegroundServiceRunning || config.backgroundLiveServiceEnabled,
                            testTag = "admin_switch_keep_alive",
                            onCheckedChange = { enable ->
                                if (!enable) {
                                    viewModel.setBackgroundLiveServiceEnabled(false)
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.setBackgroundLiveServiceEnabled(true)
                                }
                            },
                        )
                        AdminPolicyRow(
                            title = "Background YouTube & Media Audio",
                            subtitle = "Prevent playback pause when switching apps or pressing Home",
                            checked = config.backgroundMediaPlaybackEnabled,
                            testTag = "admin_switch_background_media",
                            onCheckedChange = viewModel::setBackgroundMediaPlayback,
                        )
                    }
                }
            }

            item {
                AdminDomainGovernanceCard(
                    config = config,
                    fingerprintSeed = uiState.activeTab.fingerprintSeed,
                    onRotateSeed = {
                        viewModel.regenerateFingerprintSeed()
                        scope.launch { snackbarHostState.showSnackbar("Fingerprint seed rotated.") }
                    },
                    onSelectCircuitLevel = viewModel::setCircuitPrivacyLevel,
                    onAddBlockedDomain = viewModel::addCustomBlockedDomain,
                    onRemoveBlockedDomain = viewModel::removeCustomBlockedDomain,
                    onRemoveWhitelistHost = viewModel::toggleAdBlockWhitelistHost,
                    onRemoveTrustedJsHost = viewModel::removeTrustedJsHost,
                )
            }

            item {
                AdminMaintenanceAndShortcutsCard(
                    isVaultUnlocked = uiState.isVaultUnlocked,
                    onRequestShredSession = { pendingDestructiveAction = PendingAdminAction.SHRED_SESSION },
                    onRequestClearHistory = { pendingDestructiveAction = PendingAdminAction.CLEAR_HISTORY },
                    onRequestClearDownloads = { pendingDestructiveAction = PendingAdminAction.CLEAR_DOWNLOADS },
                    onLockVault = {
                        viewModel.lockVault()
                        scope.launch { snackbarHostState.showSnackbar("AES-256 Vault locked.") }
                    },
                    onTestTorProxy = {
                        viewModel.testTorProxyConnection()
                        scope.launch { snackbarHostState.showSnackbar("Testing Tor proxy ${config.torEndpoint}…") }
                    },
                    onOpenSecuritySettings = onOpenSecuritySettings,
                    onOpenAdminSettings = onOpenAdminSettings,
                    onOpenCloudSync = onOpenCloudSync,
                    onOpenSessionGuard = onOpenSessionGuard,
                    onOpenVault = onOpenVault,
                )
            }
        }
    }

    pendingDestructiveAction?.let { action ->
        val (title, body, confirmLabel) = when (action) {
            PendingAdminAction.SHRED_SESSION -> Triple(
                "Shred Active Session?",
                "This closes all tabs, wipes WebView cookies/cache/storage, resets counters, locks the Vault, and rotates your fingerprint seed.",
                "Shred Everything",
            )
            PendingAdminAction.CLEAR_HISTORY -> Triple(
                "Clear Surface History?",
                "Permanently delete all $historyCount recorded surface web history entries from this device.",
                "Clear History",
            )
            PendingAdminAction.CLEAR_DOWNLOADS -> Triple(
                "Clear Download Log?",
                "Remove all $downloadsCount download records from the OnionShield Downloads Manager.",
                "Clear Log",
            )
        }
        AlertDialog(
            onDismissRequest = { pendingDestructiveAction = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                Button(
                    onClick = {
                        when (action) {
                            PendingAdminAction.SHRED_SESSION -> {
                                viewModel.confirmShredSession()
                                scope.launch { snackbarHostState.showSnackbar("Session shredded cleanly.") }
                            }
                            PendingAdminAction.CLEAR_HISTORY -> {
                                viewModel.clearAllHistory()
                                scope.launch { snackbarHostState.showSnackbar("Surface web history cleared.") }
                            }
                            PendingAdminAction.CLEAR_DOWNLOADS -> {
                                viewModel.clearDownloads()
                                scope.launch { snackbarHostState.showSnackbar("Download log cleared.") }
                            }
                        }
                        pendingDestructiveAction = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(confirmLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDestructiveAction = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

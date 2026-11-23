package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.browser.HomeAppShortcutHelper
import com.example.browser.TrackerAndUrlEngine
import com.example.data.BrowserMode
import com.example.security.configureAppCheck
import com.example.ui.BrowserViewModel
import com.example.ui.Routes
import com.example.ui.screens.AdminPanelScreen
import com.example.ui.screens.AdminSettingsRoute
import com.example.ui.screens.BiometricAppLockScreen
import com.example.ui.screens.BrowserScreen
import com.example.ui.screens.CloudSyncRoute
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.GeminiChatScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.SecuritySettingsScreen
import com.example.ui.screens.SessionGuardScreen
import com.example.ui.screens.VaultScreen
import com.example.ui.screens.VpnConnectScreen
import com.example.ui.theme.OnionShieldTheme
import kotlinx.coroutines.flow.MutableStateFlow

private data class ExternalLinkIntent(
    val url: String,
    val title: String = "",
    val isSharedAction: Boolean = false,
)

class MainActivity : FragmentActivity() {

    private val pendingExternalIntent = MutableStateFlow<ExternalLinkIntent?>(null)
    private lateinit var sharedBrowserViewModel: BrowserViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Configure Firebase App Check before ViewModels, Compose UI, or any Firebase operations.
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        sharedBrowserViewModel = ViewModelProvider(this)[BrowserViewModel::class.java]
        pendingExternalIntent.value = extractExternalIntent(intent)

        setContent {
            val browserViewModel = sharedBrowserViewModel
            val uiState by browserViewModel.uiState.collectAsStateWithLifecycle()
            val isBookmarked by browserViewModel.isCurrentPageBookmarked.collectAsStateWithLifecycle()
            val bookmarks by browserViewModel.bookmarks.collectAsStateWithLifecycle()
            val pinnedApps by browserViewModel.pinnedApps.collectAsStateWithLifecycle()
            val sessionRoundLogs by browserViewModel.sessionRoundLogs.collectAsStateWithLifecycle()
            val sessionGuardStats by browserViewModel.sessionGuardStats.collectAsStateWithLifecycle()
            val monitorSyncStatus by browserViewModel.monitorSyncStatus.collectAsStateWithLifecycle()
            val isForegroundServiceRunning by browserViewModel.isForegroundServiceRunning.collectAsStateWithLifecycle()
            val history by browserViewModel.history.collectAsStateWithLifecycle()
            val downloads by browserViewModel.downloads.collectAsStateWithLifecycle()
            val externalIntent by pendingExternalIntent.collectAsStateWithLifecycle()

            val navController = rememberNavController()
            val isTorMode = uiState.securityConfig.browserMode == BrowserMode.TOR_ONION

            LaunchedEffect(externalIntent, uiState.isAppLocked) {
                val target = externalIntent
                if (target != null && !uiState.isAppLocked) {
                    if (target.isSharedAction) {
                        browserViewModel.showLinkActionSheet(
                            url = target.url,
                            title = target.title,
                            fromExternalShare = true,
                        )
                    } else {
                        browserViewModel.openExternalLaunchUrl(target.url)
                    }
                    pendingExternalIntent.value = null
                }
            }

            OnionShieldTheme(
                isTorMode = isTorMode,
                darkTheme = true,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = Routes.ROUTE_BROWSER,
                    ) {
                    composable(Routes.ROUTE_BROWSER) {
                        BrowserScreen(
                            uiState = uiState,
                            isBookmarked = isBookmarked,
                            viewModel = browserViewModel,
                            onOpenSecuritySettings = {
                                navController.navigate(Routes.ROUTE_SHIELD_SETTINGS)
                            },
                            onOpenLibrary = {
                                navController.navigate(Routes.ROUTE_LIBRARY)
                            },
                            onOpenDownloads = {
                                navController.navigate(Routes.ROUTE_DOWNLOADS)
                            },
                            onOpenVault = {
                                navController.navigate(Routes.ROUTE_VAULT)
                            },
                            onOpenCloudSync = {
                                navController.navigate(Routes.ROUTE_CLOUD_SYNC)
                            },
                            onOpenSessionGuard = {
                                navController.navigate(Routes.ROUTE_SESSION_GUARD)
                            },
                            onOpenAdminPanel = {
                                navController.navigate(Routes.ROUTE_ADMIN_PANEL)
                            },
                            onOpenAdminSettings = {
                                navController.navigate(Routes.ROUTE_ADMIN_SETTINGS)
                            },
                            onOpenVpnConnect = {
                                navController.navigate(Routes.ROUTE_VPN_CONNECT)
                            },
                            onOpenGeminiAssistant = {
                                navController.navigate(Routes.ROUTE_GEMINI_ASSISTANT)
                            },
                        )
                    }

                    composable(Routes.ROUTE_SHIELD_SETTINGS) {
                        SecuritySettingsScreen(
                            uiState = uiState,
                            viewModel = browserViewModel,
                            onOpenAdminPanel = {
                                navController.navigate(Routes.ROUTE_ADMIN_PANEL)
                            },
                            onOpenAdminSettings = {
                                navController.navigate(Routes.ROUTE_ADMIN_SETTINGS)
                            },
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.ROUTE_LIBRARY) {
                        LibraryScreen(
                            bookmarks = bookmarks,
                            history = history,
                            isTorMode = isTorMode,
                            onOpenUrl = { url -> browserViewModel.openUrlOrSearch(url) },
                            onInstallToHomeScreen = { title, url ->
                                browserViewModel.installLinkToHomeAndMenu(this@MainActivity, title, url)
                            },
                            onDeleteBookmark = browserViewModel::removeBookmark,
                            onDeleteHistory = browserViewModel::deleteHistoryEntry,
                            onClearAllHistory = browserViewModel::clearAllHistory,
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.ROUTE_DOWNLOADS) {
                        LaunchedEffect(Unit) {
                            browserViewModel.refreshDownloadStatuses()
                        }
                        DownloadsScreen(
                            downloads = downloads,
                            pendingDownload = uiState.pendingDownload,
                            downloadFailureReasons = uiState.downloadFailureReasons,
                            onConfirmPendingDownload = browserViewModel::confirmPendingDownload,
                            onCancelPendingDownload = browserViewModel::cancelPendingDownload,
                            onRefreshDownloads = browserViewModel::refreshDownloadStatuses,
                            onRetryDownload = browserViewModel::retryDownload,
                            onOpenOrInstallDownload = browserViewModel::openOrInstallDownloadedFile,
                            onOpenSystemDownloads = browserViewModel::openSystemDownloadsFolder,
                            onRequestDirectDownload = browserViewModel::requestDirectDownload,
                            onDeleteDownload = browserViewModel::deleteDownloadRecord,
                            onClearDownloads = browserViewModel::clearDownloads,
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.ROUTE_VAULT) {
                        VaultScreen(
                            uiState = uiState,
                            onUnlockWithBiometricKey = browserViewModel::unlockVaultAfterBiometricAuth,
                            onSaveBiometricWrappedKeyAndUnlock = browserViewModel::saveBiometricWrappedKeyAndUnlock,
                            onLockVault = browserViewModel::lockVault,
                            onAddEntry = browserViewModel::addVaultEntry,
                            onDeleteEntry = browserViewModel::deleteVaultEntry,
                            onWipeVault = browserViewModel::wipeEntireVault,
                            onOpenUrl = { url -> browserViewModel.openUrlOrSearch(url) },
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.ROUTE_CLOUD_SYNC) {
                        CloudSyncRoute(
                            localBookmarks = bookmarks,
                            localPinnedApps = pinnedApps,
                            localHistory = history,
                            onRestoreToHomeScreen = { title, url ->
                                browserViewModel.installLinkToHomeAndMenu(this@MainActivity, title, url)
                            },
                            onOpenUrl = { url -> browserViewModel.openUrlOrSearch(url) },
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.ROUTE_SESSION_GUARD) {
                        SessionGuardScreen(
                            config = uiState.securityConfig,
                            stats = sessionGuardStats,
                            rounds = sessionRoundLogs,
                            activeSiteHost = TrackerAndUrlEngine.normalizeHost(uiState.activeTab.url) ?: "manual",
                            monitorSyncStatus = monitorSyncStatus,
                            isBackgroundServiceRunning = isForegroundServiceRunning,
                            onSaveGuardConfig = browserViewModel::updateSessionGuardConfig,
                            onToggleLiveMonitorSync = browserViewModel::setLiveCloudMonitorSync,
                            onToggleBackgroundLiveService = browserViewModel::setBackgroundLiveServiceEnabled,
                            onOpenCloudSync = { navController.navigate(Routes.ROUTE_CLOUD_SYNC) },
                            onResetSessionTimer = browserViewModel::resetSessionGuardTimer,
                            onLogRound = browserViewModel::logCompletedRound,
                            onDeleteRound = browserViewModel::deleteSessionRoundLog,
                            onClearRounds = browserViewModel::clearSessionRoundLogs,
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.ROUTE_ADMIN_PANEL) {
                        AdminPanelScreen(
                            uiState = uiState,
                            viewModel = browserViewModel,
                            bookmarksCount = bookmarks.size,
                            historyCount = history.size,
                            downloadsCount = downloads.size,
                            onOpenSecuritySettings = {
                                navController.navigate(Routes.ROUTE_SHIELD_SETTINGS)
                            },
                            onOpenAdminSettings = {
                                navController.navigate(Routes.ROUTE_ADMIN_SETTINGS)
                            },
                            onOpenCloudSync = {
                                navController.navigate(Routes.ROUTE_CLOUD_SYNC)
                            },
                            onOpenSessionGuard = {
                                navController.navigate(Routes.ROUTE_SESSION_GUARD)
                            },
                            onOpenVault = {
                                navController.navigate(Routes.ROUTE_VAULT)
                            },
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.ROUTE_ADMIN_SETTINGS) {
                        AdminSettingsRoute(
                            onApplyOverridesToLocalBrowser = { serverConfig ->
                                browserViewModel.applyServerPrivacyOverrides(serverConfig)
                            },
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.ROUTE_VPN_CONNECT) {
                        val vpnStatus by browserViewModel.vpnTunnelStatus.collectAsStateWithLifecycle()
                        VpnConnectScreen(
                            selectedProfile = uiState.securityConfig.vpnProfile,
                            vpnStatus = vpnStatus,
                            onSelectProfile = browserViewModel::selectVpnProfile,
                            onConnectAfterConsent = browserViewModel::startVpnTunnelAfterConsent,
                            onDisconnect = browserViewModel::stopVpnTunnel,
                            onOpenWebLink = { link ->
                                browserViewModel.openUrlOrSearch(link)
                                navController.popBackStack()
                            },
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.ROUTE_GEMINI_ASSISTANT) {
                        GeminiChatScreen(
                            activeTab = uiState.activeTab,
                            securityConfig = uiState.securityConfig,
                            extractPageSnippet = { tabId, callback ->
                                browserViewModel.webViewPool.extractPageTextSnippet(tabId, callback)
                            },
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }
                    }

                    if (uiState.isAppLocked) {
                        BiometricAppLockScreen(
                            isTorMode = isTorMode,
                            isConfigLoaded = uiState.isAppLockConfigLoaded,
                            onUnlocked = browserViewModel::unlockApp,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::sharedBrowserViewModel.isInitialized) {
            sharedBrowserViewModel.onAppForegrounded()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (::sharedBrowserViewModel.isInitialized) {
            sharedBrowserViewModel.onAppBackgrounded()
        }
    }

    override fun onStop() {
        if (::sharedBrowserViewModel.isInitialized) {
            sharedBrowserViewModel.onAppBackgrounded()
            if (!isChangingConfigurations) {
                sharedBrowserViewModel.lockAppIfEnabled()
            }
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val extracted = extractExternalIntent(intent)
        if (extracted != null) {
            pendingExternalIntent.value = extracted
        }
    }

    private fun extractExternalIntent(intent: Intent?): ExternalLinkIntent? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            val sharedUrl = TrackerAndUrlEngine.extractFirstHttpUrl(sharedText) ?: return null
            val sharedTitle = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
            return ExternalLinkIntent(
                url = sharedUrl,
                title = sharedTitle,
                isSharedAction = true,
            )
        }
        val candidate = intent.getStringExtra(HomeAppShortcutHelper.EXTRA_PINNED_APP_URL)
            ?: intent.dataString
            ?: return null
        return candidate.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }?.let { ExternalLinkIntent(url = it, isSharedAction = false) }
    }
}

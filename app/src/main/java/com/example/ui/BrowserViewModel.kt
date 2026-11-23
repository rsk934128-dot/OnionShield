package com.example.ui

import android.Manifest
import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.browser.DetectedTradeButton
import com.example.browser.DownloadFileHelper
import com.example.browser.HomeAppShortcutHelper
import com.example.browser.LiveMonitorForegroundService
import com.example.browser.LiveSessionRuntime
import com.example.browser.OnionShieldVpnService
import com.example.browser.PrivacyScripts
import com.example.browser.ResolvedClickTarget
import com.example.browser.SessionGuardEngine
import com.example.browser.SessionGuardStats
import com.example.browser.SessionMonitorSyncService
import com.example.browser.SessionMonitorSyncStatus
import com.example.browser.TorCircuitService
import com.example.browser.TorProxyController
import com.example.browser.TrackerAndUrlEngine
import com.example.browser.TradeDecision
import com.example.browser.TradePilotHelper
import com.example.browser.TradeSignalDecision
import com.example.browser.VpnTunnelRuntime
import com.example.browser.WebViewEventCallbacks
import com.example.browser.WebViewPool
import com.example.browser.WebsiteLogoCache
import com.example.data.ABOUT_START_URL
import com.example.data.BookmarkEntity
import com.example.data.BridgeTransport
import com.example.data.BrowserLibraryRepository
import com.example.data.BrowserMode
import com.example.data.BrowserTab
import com.example.data.CircuitPrivacyLevel
import com.example.data.DecryptedVaultItem
import com.example.data.DownloadEntity
import com.example.data.HistoryEntity
import com.example.data.LinkMenuTarget
import com.example.data.OnionShieldDatabase
import com.example.data.PendingDownloadRequest
import com.example.data.PersistedMediaState
import com.example.data.PinnedAppEntity
import com.example.data.PrivacyPreferencesRepository
import com.example.data.ProxyProtocol
import com.example.data.SearchEngine
import com.example.data.SecurityConfig
import com.example.data.SessionRoundLogEntity
import com.example.data.TorCircuitDiagnostic
import com.example.data.TorProxyState
import com.example.data.VaultEntryEntity
import com.example.data.VaultEntryType
import com.example.data.VpnServerProfile
import com.example.data.VpnTunnelStatus
import com.example.data.repository.DecryptedSearchHistoryItem
import com.example.data.repository.EncryptedSearchHistoryRepository
import com.example.data.repository.GeminiShieldRepository
import com.example.data.repository.SessionMonitorFirestoreRepository
import com.example.security.VaultCryptoManager
import com.example.ui.screens.AppLockPolicy
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.util.UUID
import javax.crypto.SecretKey
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class TradePilotUiState(
    val isPanelExpanded: Boolean = false,
    val isAnalyzing: Boolean = false,
    val isCapturingScreen: Boolean = false,
    val isAutoPilotRunning: Boolean = false,
    val minConfidencePercent: Int = 60,
    val cooldownSeconds: Int = 8,
    val lastDecision: TradeSignalDecision? = null,
    val lastResolvedTarget: ResolvedClickTarget? = null,
    val lastClickedAction: String? = null,
    val lastClickedAtMs: Long = 0L,
    val lastTapNormX: Float? = null,
    val lastTapNormY: Float? = null,
    val totalExecutionsCount: Int = 0,
    val lastError: String? = null,
)

data class BrowserUiState(
    val tabs: List<BrowserTab> = listOf(BrowserTab()),
    val activeTabId: String = tabs.first().id,
    val omniboxInput: String = "",
    val isOmniboxFocused: Boolean = false,
    val securityConfig: SecurityConfig = SecurityConfig(),
    val torProxyState: TorProxyState = TorProxyState.Inactive,
    val circuitDiagnostic: TorCircuitDiagnostic = TorCircuitDiagnostic(),
    val totalTrackersBlocked: Int = 0,
    val totalScriptsBlocked: Int = 0,
    val pendingDownload: PendingDownloadRequest? = null,
    val downloadFailureReasons: Map<Long, String> = emptyMap(),
    val showDisclaimerDialog: Boolean = false,
    val showShredConfirmDialog: Boolean = false,
    val isVaultUnlocked: Boolean = false,
    val vaultLockedCount: Int = 0,
    val vaultPinConfigured: Boolean = false,
    val wrappedVaultDek: String? = null,
    val decryptedVaultItems: List<DecryptedVaultItem> = emptyList(),
    val linkMenuTarget: LinkMenuTarget? = null,
    val statusBannerMessage: String? = null,
    val isAppLocked: Boolean = true,
    val isAppLockConfigLoaded: Boolean = false,
    val tradePilot: TradePilotUiState = TradePilotUiState(),
) {
    val activeTab: BrowserTab
        get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
}

class BrowserViewModel(application: Application) : AndroidViewModel(application), WebViewEventCallbacks {

    private val prefsRepo = PrivacyPreferencesRepository(application)
    private val localDb = OnionShieldDatabase.getInstance(application)
    private val libraryRepo = BrowserLibraryRepository(localDb)
    private val searchHistoryRepo = EncryptedSearchHistoryRepository(application, localDb.searchHistoryDao())
    private val geminiShieldRepository = GeminiShieldRepository()
    val webViewPool = WebViewPool(application, this)
    private val autoFallbackAttemptedIds = mutableSetOf<Long>()
    private var tradeAutoPilotJob: Job? = null
    private var lastDetectedTradeButtons: List<DetectedTradeButton> = emptyList()

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    val bookmarks: StateFlow<List<BookmarkEntity>> = libraryRepo.bookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history: StateFlow<List<HistoryEntity>> = libraryRepo.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloads: StateFlow<List<DownloadEntity>> = libraryRepo.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pinnedApps: StateFlow<List<PinnedAppEntity>> = libraryRepo.pinnedApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sessionRoundLogs: StateFlow<List<SessionRoundLogEntity>> = libraryRepo.sessionRoundLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentSearchHistory: StateFlow<List<DecryptedSearchHistoryItem>> =
        searchHistoryRepo.observeRecentSearches(limit = 30)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val omniboxSuggestions: StateFlow<List<DecryptedSearchHistoryItem>> = combine(
        recentSearchHistory,
        _uiState.map { it.omniboxInput to it.isOmniboxFocused }.distinctUntilChanged(),
    ) { items, (input, focused) ->
        if (!focused) {
            emptyList()
        } else {
            val activeUrl = _uiState.value.activeTab.url
            val queryFilter = if (input == activeUrl) "" else input
            EncryptedSearchHistoryRepository.filterSuggestions(items, queryFilter, maxResults = 6)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isForegroundServiceRunning: StateFlow<Boolean> = LiveSessionRuntime.isForegroundServiceRunning
    val isMediaPlaybackActive: StateFlow<Boolean> = LiveSessionRuntime.isMediaPlaybackActive
    val activeMediaTitle: StateFlow<String> = LiveSessionRuntime.activeMediaTitle
    val vpnTunnelStatus: StateFlow<VpnTunnelStatus> = VpnTunnelRuntime.status

    val sessionGuardStats: StateFlow<SessionGuardStats> = combine(
        sessionRoundLogs,
        _uiState,
        LiveSessionRuntime.elapsedMinutes,
    ) { rounds, state, elapsed ->
        val cfg = state.securityConfig
        SessionGuardEngine.computeSessionStats(
            rounds = rounds,
            guardEnabled = cfg.sessionGuardEnabled,
            elapsedMinutes = elapsed,
            timeLimitMinutes = cfg.sessionTimeLimitMinutes,
            budgetLimit = cfg.sessionBudgetLimit,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SessionGuardStats())

    private val sessionMonitorSyncService: SessionMonitorSyncService? = runCatching {
        SessionMonitorSyncService(
            repository = SessionMonitorFirestoreRepository(application),
            auth = Firebase.auth,
            scope = viewModelScope,
        )
    }.getOrNull()

    val monitorSyncStatus: StateFlow<SessionMonitorSyncStatus> = combine(
        LiveSessionRuntime.isForegroundServiceRunning,
        LiveSessionRuntime.foregroundSyncStatus,
        sessionMonitorSyncService?.syncStatus ?: MutableStateFlow(SessionMonitorSyncStatus.Disabled),
    ) { serviceRunning, serviceStatus, vmStatus ->
        if (serviceRunning) serviceStatus else vmStatus
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SessionMonitorSyncStatus.Disabled)

    private val _fullscreenCustomView = MutableStateFlow<View?>(null)
    val fullscreenCustomView: StateFlow<View?> = _fullscreenCustomView.asStateFlow()
    private var customVideoCallback: WebChromeClient.CustomViewCallback? = null

    private val _popupWebView = MutableStateFlow<WebView?>(null)
    val popupWebView: StateFlow<WebView?> = _popupWebView.asStateFlow()

    val isCurrentPageBookmarked: StateFlow<Boolean> = combine(_uiState, bookmarks) { state, list ->
        val url = state.activeTab.url
        url != ABOUT_START_URL && list.any { it.url == url }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var lastAppliedProxyKey: String? = null
    private var autoStartedBackgroundService = false
    private var latestEncryptedVaultEntities: List<VaultEntryEntity> = emptyList()
    private var unlockedVaultKey: SecretKey? = null
    @Volatile
    private var persistentSurfaceSeed: String = _uiState.value.activeTab.fingerprintSeed

    init {
        viewModelScope.launch {
            libraryRepo.ensureDefaultHomeAppsSeeded()
        }

        viewModelScope.launch {
            val resolvedSeed = prefsRepo.ensureSurfaceDeviceSeed(persistentSurfaceSeed)
            persistentSurfaceSeed = resolvedSeed
            val resolvedProfile = PrivacyScripts.profileForSeed(resolvedSeed, BrowserMode.SURFACE)
            _uiState.update { state ->
                state.copy(
                    tabs = state.tabs.map { tab ->
                        if (tab.mode == BrowserMode.SURFACE && tab.isStartPage) {
                            tab.copy(
                                fingerprintSeed = resolvedSeed,
                                userAgentLabel = resolvedProfile.label,
                            )
                        } else {
                            tab
                        }
                    },
                )
            }
            prefsRepo.surfaceDeviceSeed.collectLatest { savedSeed ->
                if (!savedSeed.isNullOrBlank()) {
                    persistentSurfaceSeed = savedSeed
                }
            }
        }

        viewModelScope.launch {
            prefsRepo.authenticatedSessionHosts.collectLatest { hosts ->
                webViewPool.updateAuthenticatedHosts(hosts)
            }
        }

        viewModelScope.launch {
            prefsRepo.securityConfig.collectLatest { config ->
                _uiState.update { current ->
                    val nextLocked = AppLockPolicy.resolveConfigUpdate(
                        currentLocked = current.isAppLocked,
                        isConfigLoaded = current.isAppLockConfigLoaded,
                        biometricAppLockEnabled = config.biometricAppLockEnabled,
                    )
                    current.copy(
                        securityConfig = config,
                        showDisclaimerDialog = !config.disclaimerAccepted,
                        isAppLocked = nextLocked,
                        isAppLockConfigLoaded = true,
                    )
                }
                webViewPool.updateSecurityConfig(config)

                val proxyKey = "${config.browserMode}:${config.torProxyProtocol}:${config.torProxyHost}:${config.torProxyPort}"
                if (proxyKey != lastAppliedProxyKey) {
                    val isColdStartSurface = lastAppliedProxyKey == null && config.browserMode == BrowserMode.SURFACE
                    lastAppliedProxyKey = proxyKey
                    if (isColdStartSurface) {
                        _uiState.update { it.copy(torProxyState = TorProxyState.Inactive) }
                    } else {
                        applyProxyForMode(config)
                    }
                }

                if (!autoStartedBackgroundService) {
                    autoStartedBackgroundService = true
                    if (config.backgroundLiveServiceEnabled &&
                        !LiveSessionRuntime.isForegroundServiceRunning.value &&
                        hasNotificationPermission()
                    ) {
                        LiveMonitorForegroundService.start(getApplication())
                    }
                }
            }
        }

        viewModelScope.launch {
            _uiState.map { it.activeTab.url }
                .distinctUntilChanged()
                .collectLatest { url ->
                    LiveSessionRuntime.updateActiveSiteHost(url)
                }
        }

        viewModelScope.launch {
            combine(
                LiveSessionRuntime.isForegroundServiceRunning,
                LiveSessionRuntime.liveTorProxyState,
            ) { running, torState ->
                running to torState
            }.collectLatest { (running, torState) ->
                if (running && _uiState.value.securityConfig.browserMode == BrowserMode.TOR_ONION &&
                    torState !is TorProxyState.Inactive
                ) {
                    _uiState.update { it.copy(torProxyState = torState) }
                }
            }
        }

        viewModelScope.launch {
            libraryRepo.vaultEntries.collectLatest { encrypted ->
                latestEncryptedVaultEntities = encrypted
                _uiState.update { state ->
                    val activeKey = unlockedVaultKey
                    state.copy(
                        vaultLockedCount = encrypted.size,
                        decryptedVaultItems = if (state.isVaultUnlocked && activeKey != null) {
                            decryptVaultList(encrypted, activeKey)
                        } else {
                            emptyList()
                        },
                    )
                }
            }
        }

        viewModelScope.launch {
            prefsRepo.vaultWrappedDek.collectLatest { envelope ->
                _uiState.update { it.copy(wrappedVaultDek = envelope) }
            }
        }

        viewModelScope.launch {
            while (true) {
                LiveSessionRuntime.refreshElapsed()
                delay(15_000L)
            }
        }

        sessionMonitorSyncService?.startObserving(
            siteHostFlow = LiveSessionRuntime.activeSiteHost,
            configFlow = _uiState.map { it.securityConfig }.distinctUntilChanged(),
            statsFlow = sessionGuardStats,
            roundsFlow = sessionRoundLogs,
            isPausedFlow = LiveSessionRuntime.isForegroundServiceRunning,
        )

        viewModelScope.launch {
            LiveSessionRuntime.mediaControlCommands.collect { cmd ->
                when {
                    cmd == LiveMonitorForegroundService.CMD_PLAY_MEDIA -> webViewPool.resumeActiveMedia()
                    cmd == LiveMonitorForegroundService.CMD_PAUSE_MEDIA -> webViewPool.pauseAllMedia()
                    cmd == LiveMonitorForegroundService.CMD_STOP_MEDIA -> {
                        webViewPool.pauseAllMedia()
                        LiveSessionRuntime.clearMediaSession()
                        prefsRepo.clearPersistedMediaState()
                    }
                    cmd.startsWith(LiveMonitorForegroundService.CMD_SEEK_PREFIX) -> {
                        val seekMs = cmd.removePrefix(LiveMonitorForegroundService.CMD_SEEK_PREFIX).toLongOrNull()
                        if (seekMs != null) {
                            webViewPool.seekActiveMedia(seekMs)
                        }
                    }
                    cmd == LiveMonitorForegroundService.CMD_NEXT_TRACK -> webViewPool.skipNextMedia()
                    cmd == LiveMonitorForegroundService.CMD_PREV_TRACK -> webViewPool.skipPrevMedia()
                }
            }
        }

        viewModelScope.launch {
            combine(
                LiveSessionRuntime.hasMediaSession,
                LiveSessionRuntime.activeMediaTitle,
                LiveSessionRuntime.activeMediaArtist,
                LiveSessionRuntime.activeMediaUrl,
                LiveSessionRuntime.activeMediaPositionMs,
            ) { hasSession, title, artist, url, posMs ->
                PersistedMediaState(
                    title = if (hasSession) title else "",
                    artist = artist,
                    url = url,
                    positionMs = posMs,
                    durationMs = LiveSessionRuntime.activeMediaDurationMs.value,
                )
            }
                .distinctUntilChanged { old, new ->
                    old.title == new.title &&
                        old.artist == new.artist &&
                        old.url == new.url &&
                        old.durationMs == new.durationMs &&
                        (old.positionMs / 2_000L) == (new.positionMs / 2_000L)
                }
                .collectLatest { state ->
                    if (state.title.isNotBlank()) {
                        prefsRepo.savePersistedMediaState(state)
                    }
                }
        }
    }

    private suspend fun applyProxyForMode(config: SecurityConfig) {
        if (config.browserMode == BrowserMode.TOR_ONION) {
            _uiState.update { it.copy(torProxyState = TorProxyState.Checking) }
        }
        val result = TorProxyController.applyProxyMode(
            mode = config.browserMode,
            host = config.torProxyHost,
            port = config.torProxyPort,
            protocol = config.torProxyProtocol,
        )
        _uiState.update { it.copy(torProxyState = result) }
    }

    fun switchBrowserMode(targetMode: BrowserMode) {
        viewModelScope.launch {
            prefsRepo.setBrowserMode(targetMode)
            val currentTab = _uiState.value.activeTab
            updateTab(currentTab.id) { it.copy(mode = targetMode, lastError = null) }
            if (targetMode == BrowserMode.TOR_ONION && !_uiState.value.securityConfig.disclaimerAccepted) {
                _uiState.update { it.copy(showDisclaimerDialog = true) }
            }
        }
    }

    fun testTorProxyConnection() {
        viewModelScope.launch {
            val cfg = _uiState.value.securityConfig
            _uiState.update { it.copy(torProxyState = TorProxyState.Checking) }
            val result = TorProxyController.checkProxyReachability(
                host = cfg.torProxyHost,
                port = cfg.torProxyPort,
                protocol = cfg.torProxyProtocol,
            )
            _uiState.update { it.copy(torProxyState = result) }
        }
    }

    fun runCircuitDiagnostics(verifyExit: Boolean = true) {
        viewModelScope.launch {
            val cfg = _uiState.value.securityConfig
            _uiState.update {
                it.copy(
                    torProxyState = if (cfg.browserMode == BrowserMode.TOR_ONION) TorProxyState.Checking else it.torProxyState,
                    circuitDiagnostic = it.circuitDiagnostic.copy(isChecking = true, exitCheckError = null),
                )
            }
            val diag = TorCircuitService.runFullCircuitDiagnostics(
                config = cfg,
                verifyExit = verifyExit,
            )
            val updatedProxyState = when {
                diag.socks5HandshakeOk == true && diag.proxyLatencyMs != null ->
                    TorProxyState.Connected(cfg.torEndpoint, diag.proxyLatencyMs)
                cfg.browserMode == BrowserMode.TOR_ONION ->
                    TorProxyState.Unreachable(
                        endpoint = cfg.torEndpoint,
                        reason = "No Tor SOCKS5/HTTP daemon responding on ${cfg.torEndpoint}",
                    )
                else -> TorProxyState.Inactive
            }
            _uiState.update {
                it.copy(
                    torProxyState = updatedProxyState,
                    circuitDiagnostic = diag,
                )
            }
        }
    }

    fun requestNewTorCircuit() {
        viewModelScope.launch {
            val cfg = _uiState.value.securityConfig
            regenerateFingerprintSeed()
            val (_, message) = TorCircuitService.requestNewCircuit(
                host = cfg.torProxyHost,
                controlPort = cfg.torControlPort,
                password = cfg.torControlPassword,
            )
            _uiState.update { it.copy(statusBannerMessage = message) }
            runCircuitDiagnostics(verifyExit = true)
        }
    }

    fun selectBridgeTransport(transport: BridgeTransport) {
        viewModelScope.launch {
            prefsRepo.setBridgeTransport(transport)
        }
    }

    fun saveBridgeConfiguration(
        transport: BridgeTransport,
        customLines: String,
        controlPort: Int,
        controlPassword: String,
    ) {
        viewModelScope.launch {
            prefsRepo.updateBridgeConfig(transport, customLines, controlPort, controlPassword)
            val cfg = _uiState.value.securityConfig
            val (_, message) = TorCircuitService.pushBridgesToControlPort(
                host = cfg.torProxyHost,
                controlPort = controlPort,
                password = controlPassword,
                transport = transport,
                customLines = customLines,
            )
            _uiState.update { it.copy(statusBannerMessage = message) }
            runCircuitDiagnostics(verifyExit = false)
        }
    }

    fun openUrlOrSearch(rawInput: String, targetTabId: String = _uiState.value.activeTabId) {
        val cfg = _uiState.value.securityConfig
        val resolved = TrackerAndUrlEngine.resolveInputToUrl(
            rawInput = rawInput,
            mode = cfg.browserMode,
            httpsEverywhere = cfg.httpsEverywhereEnabled,
            searchEngine = cfg.searchEngine,
        )
        val isOnion = TrackerAndUrlEngine.isOnionUrl(resolved)

        // Automatically elevate to Tor Onion mode if user enters a .onion domain
        if (isOnion && cfg.browserMode != BrowserMode.TOR_ONION) {
            viewModelScope.launch {
                prefsRepo.setBrowserMode(BrowserMode.TOR_ONION)
            }
        }

        updateTab(targetTabId) { tab ->
            tab.copy(
                url = resolved,
                title = if (resolved == ABOUT_START_URL) "Start Page" else resolved,
                isOnionAddress = isOnion,
                isHttpsSecure = resolved.startsWith("https://", ignoreCase = true) || isOnion,
                lastError = null,
                mode = if (isOnion) BrowserMode.TOR_ONION else cfg.browserMode,
            )
        }
        _uiState.update {
            it.copy(
                omniboxInput = if (resolved == ABOUT_START_URL) "" else resolved,
                isOmniboxFocused = false,
            )
        }
        if (resolved != ABOUT_START_URL) {
            val cleanQuery = rawInput.trim()
            if (cleanQuery.isNotEmpty()) {
                viewModelScope.launch {
                    searchHistoryRepo.recordSearch(
                        rawQuery = cleanQuery,
                        resolvedUrl = resolved,
                        isOnion = isOnion,
                    )
                }
            }
            val tab = _uiState.value.tabs.firstOrNull { it.id == targetTabId } ?: _uiState.value.activeTab
            webViewPool.loadUrlForTab(tab.id, tab.fingerprintSeed, cfg, resolved)
        }
    }

    fun deleteRecentSearch(id: Long) {
        viewModelScope.launch {
            searchHistoryRepo.deleteSearch(id)
        }
    }

    fun clearRecentSearches() {
        viewModelScope.launch {
            searchHistoryRepo.clearAll()
            _uiState.update { it.copy(statusBannerMessage = "Encrypted omnibox search history cleared.") }
        }
    }

    fun openExternalLaunchUrl(rawUrl: String) {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return
        }
        val currentActive = _uiState.value.activeTab
        if (currentActive.isStartPage) {
            openUrlOrSearch(trimmed, currentActive.id)
        } else {
            val targetMode = if (TrackerAndUrlEngine.isOnionUrl(trimmed)) BrowserMode.TOR_ONION else BrowserMode.SURFACE
            createNewTab(mode = targetMode, initialUrl = trimmed)
        }
    }

    fun onOmniboxChanged(text: String) {
        _uiState.update { it.copy(omniboxInput = text) }
    }

    fun onOmniboxFocusChanged(focused: Boolean) {
        _uiState.update { state ->
            val currentUrl = state.activeTab.url
            val nextInput = if (focused) {
                if (currentUrl == ABOUT_START_URL) "" else currentUrl
            } else {
                if (currentUrl == ABOUT_START_URL) "" else currentUrl
            }
            state.copy(isOmniboxFocused = focused, omniboxInput = nextInput)
        }
    }

    fun createNewTab(mode: BrowserMode = _uiState.value.securityConfig.browserMode, initialUrl: String = ABOUT_START_URL) {
        val seed = if (mode == BrowserMode.SURFACE) {
            persistentSurfaceSeed
        } else {
            UUID.randomUUID().toString().take(8).uppercase()
        }
        val profile = PrivacyScripts.profileForSeed(seed, mode)
        val newTab = BrowserTab(
            mode = mode,
            url = initialUrl,
            title = if (initialUrl == ABOUT_START_URL) "Start Page" else initialUrl,
            fingerprintSeed = seed,
            userAgentLabel = profile.label,
        )
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs + newTab,
                activeTabId = newTab.id,
                omniboxInput = if (initialUrl == ABOUT_START_URL) "" else initialUrl,
                isOmniboxFocused = false,
            )
        }
        viewModelScope.launch {
            if (mode != _uiState.value.securityConfig.browserMode) {
                prefsRepo.setBrowserMode(mode)
            }
        }
        if (initialUrl != ABOUT_START_URL) {
            openUrlOrSearch(initialUrl, newTab.id)
        }
    }

    fun selectTab(tabId: String) {
        val target = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return
        _uiState.update {
            it.copy(
                activeTabId = tabId,
                omniboxInput = if (target.isStartPage) "" else target.url,
                isOmniboxFocused = false,
            )
        }
        viewModelScope.launch {
            if (target.mode != _uiState.value.securityConfig.browserMode) {
                prefsRepo.setBrowserMode(target.mode)
            }
        }
    }

    fun closeTab(tabId: String) {
        exitFullscreenVideo()
        webViewPool.destroyTabWebView(tabId)
        _uiState.update { state ->
            val remaining = state.tabs.filterNot { it.id == tabId }
            if (remaining.isEmpty()) {
                val mode = state.securityConfig.browserMode
                val seed = if (mode == BrowserMode.SURFACE) {
                    persistentSurfaceSeed
                } else {
                    UUID.randomUUID().toString().take(8).uppercase()
                }
                val profile = PrivacyScripts.profileForSeed(seed, mode)
                val fresh = BrowserTab(
                    mode = mode,
                    fingerprintSeed = seed,
                    userAgentLabel = profile.label,
                )
                state.copy(
                    tabs = listOf(fresh),
                    activeTabId = fresh.id,
                    omniboxInput = "",
                )
            } else {
                val nextActive = if (state.activeTabId == tabId) remaining.last() else remaining.first { it.id == state.activeTabId }
                state.copy(
                    tabs = remaining,
                    activeTabId = nextActive.id,
                    omniboxInput = if (nextActive.isStartPage) "" else nextActive.url,
                )
            }
        }
    }

    fun goBack(): Boolean {
        val tab = _uiState.value.activeTab
        val wv = webViewPool.getWebView(tab.id)
        return if (wv != null && wv.canGoBack()) {
            wv.goBack()
            true
        } else if (!tab.isStartPage) {
            updateTab(tab.id) {
                it.copy(
                    url = ABOUT_START_URL,
                    title = "Start Page",
                    isLoading = false,
                    canGoBack = false,
                    lastError = null,
                )
            }
            _uiState.update { it.copy(omniboxInput = "") }
            true
        } else {
            false
        }
    }

    fun goForward() {
        val tab = _uiState.value.activeTab
        webViewPool.getWebView(tab.id)?.let { wv ->
            if (wv.canGoForward()) wv.goForward()
        }
    }

    fun reloadOrStop() {
        val tab = _uiState.value.activeTab
        if (tab.isStartPage) return
        val wv = webViewPool.getWebView(tab.id)
        if (tab.isLoading) {
            wv?.stopLoading()
            updateTab(tab.id) { it.copy(isLoading = false, progress = 0) }
        } else {
            updateTab(tab.id) { it.copy(lastError = null) }
            wv?.reload() ?: openUrlOrSearch(tab.url, tab.id)
        }
    }

    fun goHome() {
        exitFullscreenVideo()
        val tab = _uiState.value.activeTab
        updateTab(tab.id) {
            it.copy(
                url = ABOUT_START_URL,
                title = "Start Page",
                isLoading = false,
                progress = 0,
                lastError = null,
            )
        }
        _uiState.update { it.copy(omniboxInput = "") }
    }

    fun requestShredSession() {
        _uiState.update { it.copy(showShredConfirmDialog = true) }
    }

    fun dismissShredConfirm() {
        _uiState.update { it.copy(showShredConfirmDialog = false) }
    }

    fun confirmShredSession() {
        dismissPopupWebView()
        webViewPool.shredAllSessions()
        LiveSessionRuntime.clearMediaSession()
        unlockedVaultKey = null
        val seed = UUID.randomUUID().toString().take(8).uppercase()
        persistentSurfaceSeed = seed
        viewModelScope.launch {
            searchHistoryRepo.clearAll()
            prefsRepo.clearPersistedMediaState()
            prefsRepo.rotateSurfaceDeviceSeed(seed)
            prefsRepo.clearAuthenticatedSessionHosts()
        }
        val mode = _uiState.value.securityConfig.browserMode
        val profile = PrivacyScripts.profileForSeed(seed, mode)
        val cleanTab = BrowserTab(
            mode = mode,
            fingerprintSeed = seed,
            userAgentLabel = profile.label,
        )
        _uiState.update {
            it.copy(
                tabs = listOf(cleanTab),
                activeTabId = cleanTab.id,
                omniboxInput = "",
                totalTrackersBlocked = 0,
                totalScriptsBlocked = 0,
                showShredConfirmDialog = false,
                isVaultUnlocked = false,
                decryptedVaultItems = emptyList(),
                statusBannerMessage = "Session shredded: cookies, cache, storage & fingerprint seed wiped; vault locked.",
            )
        }
    }

    private fun decryptVaultList(entries: List<VaultEntryEntity>, primaryKey: SecretKey): List<DecryptedVaultItem> {
        val legacyKey by lazy { VaultCryptoManager.getOrCreateVaultKey(getApplication()) }
        return entries.mapNotNull { entity ->
            decryptSingleEntity(entity, primaryKey)
                ?: runCatching { decryptSingleEntity(entity, legacyKey) }.getOrNull()
        }
    }

    private fun decryptSingleEntity(entity: VaultEntryEntity, key: SecretKey): DecryptedVaultItem? {
        return runCatching {
            DecryptedVaultItem(
                id = entity.id,
                entryType = runCatching { VaultEntryType.valueOf(entity.entryType) }.getOrDefault(VaultEntryType.NOTE),
                title = VaultCryptoManager.decrypt(entity.titleCiphertext, key),
                secret = VaultCryptoManager.decrypt(entity.secretCiphertext, key),
                url = VaultCryptoManager.decrypt(entity.urlCiphertext, key),
                isOnion = entity.isOnion,
                updatedAt = entity.updatedAt,
            )
        }.getOrNull()
    }

    fun unlockVaultAfterBiometricAuth(authenticatedKey: SecretKey? = null) {
        val sessionKey = authenticatedKey ?: VaultCryptoManager.getOrCreateVaultKey(getApplication())
        unlockedVaultKey = sessionKey
        val decrypted = decryptVaultList(latestEncryptedVaultEntities, sessionKey)
        _uiState.update {
            it.copy(
                isVaultUnlocked = true,
                decryptedVaultItems = decrypted,
                statusBannerMessage = "Secure Vault unlocked via Biometric authentication",
            )
        }
    }

    fun saveBiometricWrappedKeyAndUnlock(dek: SecretKey, wrappedEnvelope: String) {
        viewModelScope.launch {
            prefsRepo.saveVaultWrappedDek(wrappedEnvelope)
        }
        unlockVaultAfterBiometricAuth(dek)
    }

    fun lockVault() {
        unlockedVaultKey = null
        _uiState.update {
            it.copy(
                isVaultUnlocked = false,
                decryptedVaultItems = emptyList(),
            )
        }
    }

    fun addVaultEntry(type: VaultEntryType, title: String, secret: String, url: String) {
        val key = unlockedVaultKey
        if (!_uiState.value.isVaultUnlocked || key == null) return
        val cleanTitle = title.trim().ifBlank { url.trim().ifBlank { type.label } }
        viewModelScope.launch {
            val entity = VaultEntryEntity(
                entryType = type.name,
                titleCiphertext = VaultCryptoManager.encrypt(cleanTitle, key),
                secretCiphertext = VaultCryptoManager.encrypt(secret.trim(), key),
                urlCiphertext = VaultCryptoManager.encrypt(url.trim(), key),
                isOnion = url.contains(".onion", ignoreCase = true),
            )
            libraryRepo.insertVaultEntry(entity)
            _uiState.update { it.copy(statusBannerMessage = "Sealed in AES-256 Vault: $cleanTitle") }
        }
    }

    fun deleteVaultEntry(id: Long) {
        if (!_uiState.value.isVaultUnlocked) return
        viewModelScope.launch { libraryRepo.deleteVaultEntry(id) }
    }

    fun wipeEntireVault() {
        unlockedVaultKey = null
        VaultCryptoManager.resetBiometricKek()
        viewModelScope.launch {
            libraryRepo.clearVault()
            prefsRepo.clearVaultWrappedDek()
            _uiState.update {
                it.copy(
                    isVaultUnlocked = false,
                    decryptedVaultItems = emptyList(),
                    wrappedVaultDek = null,
                    statusBannerMessage = "All encrypted vault entries permanently erased.",
                )
            }
        }
    }

    fun regenerateFingerprintSeed() {
        val seed = UUID.randomUUID().toString().take(8).uppercase()
        val tab = _uiState.value.activeTab
        val profile = PrivacyScripts.profileForSeed(seed, tab.mode)
        if (tab.mode == BrowserMode.SURFACE) {
            persistentSurfaceSeed = seed
            viewModelScope.launch {
                prefsRepo.rotateSurfaceDeviceSeed(seed)
            }
        }
        webViewPool.getWebView(tab.id)?.let { it.tag = seed }
        updateTab(tab.id) {
            it.copy(fingerprintSeed = seed, userAgentLabel = profile.label)
        }
        webViewPool.updateSecurityConfig(_uiState.value.securityConfig)
        _uiState.update {
            it.copy(statusBannerMessage = "Fingerprint rotated: Seed $seed (${profile.label})")
        }
    }

    fun dismissStatusBanner() {
        _uiState.update { it.copy(statusBannerMessage = null) }
    }

    fun showStatusBanner(message: String) {
        _uiState.update { it.copy(statusBannerMessage = message) }
    }

    fun trustCurrentSiteJavascript() {
        val tab = _uiState.value.activeTab
        if (tab.isStartPage) return
        val host = TrackerAndUrlEngine.normalizeHost(tab.url) ?: return
        viewModelScope.launch {
            prefsRepo.addTrustedJsHost(host)
            updateTab(tab.id) { it.copy(blockedScriptsCount = 0) }
            _uiState.update {
                it.copy(statusBannerMessage = "JavaScript enabled for $host")
            }
            webViewPool.getWebView(tab.id)?.reload() ?: openUrlOrSearch(tab.url, tab.id)
        }
    }

    fun pinAppToStartPage(title: String, rawUrl: String) {
        val resolved = TrackerAndUrlEngine.resolveInputToUrl(
            rawInput = rawUrl,
            mode = _uiState.value.securityConfig.browserMode,
            httpsEverywhere = _uiState.value.securityConfig.httpsEverywhereEnabled,
            searchEngine = _uiState.value.securityConfig.searchEngine,
        )
        if (resolved == ABOUT_START_URL) return
        val cleanTitle = HomeAppShortcutHelper.deriveDisplayTitle(title, resolved)
        viewModelScope.launch {
            libraryRepo.pinHomeApp(cleanTitle, resolved)
            _uiState.update { it.copy(statusBannerMessage = "Pinned \"$cleanTitle\" to Home Apps") }
        }
    }

    fun removePinnedApp(id: Long) {
        viewModelScope.launch {
            libraryRepo.removePinnedApp(id)
            _uiState.update { it.copy(statusBannerMessage = "Removed app from Start Page") }
        }
    }

    fun installLinkToHomeAndMenu(context: Context, rawTitle: String, rawUrl: String) {
        val resolved = TrackerAndUrlEngine.resolveInputToUrl(
            rawInput = rawUrl,
            mode = _uiState.value.securityConfig.browserMode,
            httpsEverywhere = _uiState.value.securityConfig.httpsEverywhereEnabled,
            searchEngine = _uiState.value.securityConfig.searchEngine,
        )
        if (resolved == ABOUT_START_URL) return
        val cleanTitle = HomeAppShortcutHelper.deriveDisplayTitle(rawTitle, resolved)
        val isOnion = TrackerAndUrlEngine.isOnionUrl(resolved)
        val allowNetworkLogoFetch = !isOnion && _uiState.value.securityConfig.browserMode == BrowserMode.SURFACE
        viewModelScope.launch {
            libraryRepo.pinHomeApp(cleanTitle, resolved)
            val fetchedLogo = WebsiteLogoCache.getOrFetchBitmap(
                context = context,
                url = resolved,
                allowNetworkFetch = allowNetworkLogoFetch,
            )
            val (_, msg) = HomeAppShortcutHelper.pinWebsiteToHomeScreen(
                context = context,
                title = cleanTitle,
                url = resolved,
                isOnion = isOnion,
                fetchedLogoBitmap = fetchedLogo,
            )
            _uiState.update { it.copy(statusBannerMessage = msg) }
        }
    }

    fun showLinkActionSheet(url: String, title: String = "", fromExternalShare: Boolean = false) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return
        }
        _uiState.update {
            it.copy(
                linkMenuTarget = LinkMenuTarget(
                    url = trimmed,
                    title = title.trim(),
                    isSharedFromExternalApp = fromExternalShare,
                ),
            )
        }
    }

    fun dismissLinkActionSheet() {
        _uiState.update { it.copy(linkMenuTarget = null) }
    }

    fun toggleBookmarkCurrentPage() {
        val tab = _uiState.value.activeTab
        if (tab.isStartPage) return
        viewModelScope.launch {
            if (isCurrentPageBookmarked.value) {
                libraryRepo.removeBookmarkByUrl(tab.url)
                _uiState.update { it.copy(statusBannerMessage = "Removed from bookmarks") }
            } else {
                libraryRepo.addBookmark(tab.title, tab.url)
                _uiState.update { it.copy(statusBannerMessage = "Bookmarked locally") }
            }
        }
    }

    fun removeBookmark(id: Long) {
        viewModelScope.launch { libraryRepo.removeBookmarkById(id) }
    }

    fun deleteHistoryEntry(id: Long) {
        viewModelScope.launch { libraryRepo.deleteHistoryItem(id) }
    }

    fun clearAllHistory() {
        viewModelScope.launch { libraryRepo.clearAllHistory() }
    }

    fun confirmPendingDownload() {
        val pending = _uiState.value.pendingDownload ?: return
        _uiState.update { it.copy(pendingDownload = null) }
        val app = getApplication<Application>()
        val safeName = DownloadFileHelper.resolveDownloadFileName(
            rawGuessedName = pending.fileName,
            url = pending.url,
            contentDisposition = pending.contentDisposition,
            mimeType = pending.mimeType,
        )
        val safeMime = pending.mimeType.substringBefore(';').trim().ifBlank { "application/octet-stream" }

        viewModelScope.launch {
            // 1) Handle blob: URLs via WebView JS bridge (DownloadManager rejects blob: URIs)
            if (pending.url.startsWith("blob:", ignoreCase = true)) {
                val recordId = libraryRepo.recordDownload(
                    DownloadEntity(
                        fileName = safeName,
                        url = pending.url,
                        mimeType = safeMime,
                        status = "DOWNLOADING",
                        systemDownloadId = -1L,
                        isOnionSource = pending.isOnionSource,
                        sizeBytes = pending.contentLength.coerceAtLeast(0L),
                    ),
                )
                val tabId = pending.sourceTabId.ifBlank { _uiState.value.activeTabId }
                webViewPool.fetchBlobDownload(
                    tabId = tabId,
                    blobUrl = pending.url,
                    fileName = safeName,
                    mimeType = safeMime,
                ) { saved ->
                    viewModelScope.launch {
                        if (saved.success) {
                            libraryRepo.updateDownloadDetails(
                                id = recordId,
                                fileName = saved.savedFileName,
                                url = saved.localUri.ifBlank { pending.url },
                                status = "COMPLETED",
                                systemDownloadId = -1L,
                                sizeBytes = saved.sizeBytes,
                            )
                            _uiState.update {
                                it.copy(
                                    downloadFailureReasons = it.downloadFailureReasons - recordId,
                                    statusBannerMessage = "Download completed: ${saved.savedFileName}",
                                )
                            }
                        } else {
                            val err = saved.errorMessage ?: "Could not save blob download"
                            libraryRepo.updateDownloadStatus(recordId, "FAILED")
                            _uiState.update {
                                it.copy(
                                    downloadFailureReasons = it.downloadFailureReasons + (recordId to err),
                                    statusBannerMessage = "Download failed: $err",
                                )
                            }
                        }
                    }
                }
                _uiState.update { it.copy(statusBannerMessage = "Saving $safeName...") }
                return@launch
            }

            // 2) Handle data: URIs directly in Kotlin (DownloadManager rejects data: URIs)
            if (pending.url.startsWith("data:", ignoreCase = true)) {
                val saved = withContext(Dispatchers.IO) {
                    val decoded = DownloadFileHelper.decodeDataUri(pending.url)
                    if (decoded == null) {
                        com.example.browser.SavedDownloadResult(
                            success = false,
                            savedFileName = safeName,
                            errorMessage = "Invalid data URI payload",
                        )
                    } else {
                        val actualMime = decoded.mimeType.ifBlank { safeMime }
                        val resolvedName = DownloadFileHelper.resolveDownloadFileName(
                            rawGuessedName = safeName,
                            url = "",
                            contentDisposition = null,
                            mimeType = actualMime,
                        )
                        decoded.bytes.inputStream().use { stream ->
                            DownloadFileHelper.saveStreamToDownloads(app, resolvedName, actualMime, stream)
                        }
                    }
                }
                val recordId = libraryRepo.recordDownload(
                    DownloadEntity(
                        fileName = saved.savedFileName,
                        url = saved.localUri.ifBlank { pending.url },
                        mimeType = safeMime,
                        status = if (saved.success) "COMPLETED" else "FAILED",
                        systemDownloadId = -1L,
                        isOnionSource = pending.isOnionSource,
                        sizeBytes = saved.sizeBytes,
                    ),
                )
                _uiState.update {
                    it.copy(
                        downloadFailureReasons = if (saved.success) {
                            it.downloadFailureReasons - recordId
                        } else {
                            it.downloadFailureReasons + (recordId to (saved.errorMessage ?: "Data download failed"))
                        },
                        statusBannerMessage = if (saved.success) {
                            "Download completed: ${saved.savedFileName}"
                        } else {
                            "Download failed: ${saved.errorMessage ?: safeName}"
                        },
                    )
                }
                return@launch
            }

            // 3) Tor / .onion downloads route directly through Tor SOCKS5 proxy into MediaStore Downloads
            if (pending.isOnionSource) {
                val recordId = libraryRepo.recordDownload(
                    DownloadEntity(
                        fileName = safeName,
                        url = pending.url,
                        mimeType = safeMime,
                        status = "DOWNLOADING",
                        systemDownloadId = -1L,
                        isOnionSource = true,
                        sizeBytes = pending.contentLength.coerceAtLeast(0L),
                    ),
                )
                _uiState.update { it.copy(statusBannerMessage = "Downloading via Tor: $safeName") }
                executeDirectDownloadForRecord(
                    recordId = recordId,
                    url = pending.url,
                    fileName = safeName,
                    mimeType = safeMime,
                    userAgent = pending.userAgent,
                    refererUrl = pending.refererUrl,
                    useTorProxy = true,
                )
                return@launch
            }

            // 4) Standard Surface http(s) -> DownloadManager with Cookie + Referer + direct fallback
            val cookieHeader = runCatching { CookieManager.getInstance().getCookie(pending.url) }.getOrNull()
            val systemId = runCatching {
                val request = DownloadManager.Request(Uri.parse(pending.url)).apply {
                    setMimeType(safeMime)
                    if (pending.userAgent.isNotBlank()) {
                        addRequestHeader("User-Agent", pending.userAgent)
                    }
                    if (!cookieHeader.isNullOrBlank()) {
                        addRequestHeader("Cookie", cookieHeader)
                    }
                    if (pending.refererUrl.isNotBlank()) {
                        addRequestHeader("Referer", pending.refererUrl)
                    }
                    setTitle(safeName)
                    setDescription("OnionShield Download")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
                }
                val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
            }.getOrDefault(-1L)

            if (systemId >= 0L) {
                libraryRepo.recordDownload(
                    DownloadEntity(
                        fileName = safeName,
                        url = pending.url,
                        mimeType = safeMime,
                        status = "DOWNLOADING",
                        systemDownloadId = systemId,
                        isOnionSource = false,
                        sizeBytes = pending.contentLength.coerceAtLeast(0L),
                    ),
                )
                _uiState.update { it.copy(statusBannerMessage = "Download queued: $safeName") }
            } else {
                val recordId = libraryRepo.recordDownload(
                    DownloadEntity(
                        fileName = safeName,
                        url = pending.url,
                        mimeType = safeMime,
                        status = "DOWNLOADING",
                        systemDownloadId = -1L,
                        isOnionSource = false,
                        sizeBytes = pending.contentLength.coerceAtLeast(0L),
                    ),
                )
                _uiState.update { it.copy(statusBannerMessage = "Downloading $safeName...") }
                executeDirectDownloadForRecord(
                    recordId = recordId,
                    url = pending.url,
                    fileName = safeName,
                    mimeType = safeMime,
                    userAgent = pending.userAgent,
                    refererUrl = pending.refererUrl,
                    useTorProxy = false,
                )
            }
        }
    }

    private suspend fun executeDirectDownloadForRecord(
        recordId: Long,
        url: String,
        fileName: String,
        mimeType: String,
        userAgent: String,
        refererUrl: String,
        useTorProxy: Boolean,
    ) {
        val app = getApplication<Application>()
        val cfg = _uiState.value.securityConfig
        val cookieHeader = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        val result = withContext(Dispatchers.IO) {
            DownloadFileHelper.downloadHttpDirectToDownloads(
                context = app,
                url = url,
                preferredFileName = fileName,
                mimeType = mimeType,
                userAgent = userAgent,
                cookieHeader = cookieHeader,
                refererUrl = refererUrl,
                useTorProxy = useTorProxy,
                torHost = cfg.torProxyHost,
                torPort = cfg.torProxyPort,
            )
        }
        if (result.success) {
            libraryRepo.updateDownloadDetails(
                id = recordId,
                fileName = result.savedFileName,
                url = result.localUri.ifBlank { url },
                status = "COMPLETED",
                systemDownloadId = -1L,
                sizeBytes = result.sizeBytes,
            )
            _uiState.update {
                it.copy(
                    downloadFailureReasons = it.downloadFailureReasons - recordId,
                    statusBannerMessage = "Download completed: ${result.savedFileName}",
                )
            }
        } else {
            val err = result.errorMessage ?: "Direct download failed"
            libraryRepo.updateDownloadDetails(
                id = recordId,
                fileName = result.savedFileName,
                url = url,
                status = "FAILED",
                systemDownloadId = -1L,
                sizeBytes = 0L,
            )
            _uiState.update {
                it.copy(
                    downloadFailureReasons = it.downloadFailureReasons + (recordId to err),
                    statusBannerMessage = "Download failed: $err",
                )
            }
        }
    }

    fun cancelPendingDownload() {
        val pending = _uiState.value.pendingDownload ?: return
        _uiState.update { it.copy(pendingDownload = null) }
        val safeName = DownloadFileHelper.resolveDownloadFileName(
            rawGuessedName = pending.fileName,
            url = pending.url,
            contentDisposition = pending.contentDisposition,
            mimeType = pending.mimeType,
        )
        viewModelScope.launch {
            libraryRepo.recordDownload(
                DownloadEntity(
                    fileName = safeName,
                    url = pending.url,
                    mimeType = pending.mimeType,
                    status = "CANCELLED",
                    isOnionSource = pending.isOnionSource,
                    sizeBytes = pending.contentLength.coerceAtLeast(0L),
                ),
            )
        }
    }

    fun requestDirectDownload(rawUrl: String) {
        val resolved = TrackerAndUrlEngine.resolveInputToUrl(
            rawInput = rawUrl,
            mode = _uiState.value.securityConfig.browserMode,
            httpsEverywhere = _uiState.value.securityConfig.httpsEverywhereEnabled,
            searchEngine = _uiState.value.securityConfig.searchEngine,
        )
        if (resolved == ABOUT_START_URL) return
        val isApk = TrackerAndUrlEngine.isApkAsset(resolved, null, null)
        val guessedMime = if (isApk) "application/vnd.android.package-archive" else "application/octet-stream"
        val guessedName = DownloadFileHelper.resolveDownloadFileName(
            rawGuessedName = URLUtil.guessFileName(resolved, null, guessedMime),
            url = resolved,
            contentDisposition = null,
            mimeType = guessedMime,
        )
        _uiState.update {
            it.copy(
                pendingDownload = PendingDownloadRequest(
                    url = resolved,
                    userAgent = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
                    contentDisposition = "",
                    mimeType = guessedMime,
                    contentLength = -1L,
                    fileName = guessedName,
                    isOnionSource = TrackerAndUrlEngine.isOnionUrl(resolved) ||
                        _uiState.value.securityConfig.browserMode == BrowserMode.TOR_ONION,
                    sourceTabId = it.activeTabId,
                    refererUrl = it.activeTab.url,
                ),
            )
        }
    }

    fun refreshDownloadStatuses() {
        val app = getApplication<Application>()
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val list = downloads.value
        if (list.isEmpty()) return
        viewModelScope.launch {
            list.filter { it.systemDownloadId >= 0L && it.status != "CANCELLED" }.forEach { entry ->
                val cursor = runCatching {
                    dm.query(DownloadManager.Query().setFilterById(entry.systemDownloadId))
                }.getOrNull()
                if (cursor == null) return@forEach
                cursor.use { c ->
                    if (!c.moveToFirst()) {
                        if (entry.status == "DOWNLOADING") {
                            libraryRepo.updateDownloadStatus(entry.id, "FAILED")
                            _uiState.update {
                                it.copy(
                                    downloadFailureReasons = it.downloadFailureReasons +
                                        (entry.id to "Download was cancelled or removed by Android."),
                                )
                            }
                        }
                        return@use
                    }
                    val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val totalSizeCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val reasonCol = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                    if (statusCol >= 0) {
                        val dmStatus = c.getInt(statusCol)
                        val dmSize = if (totalSizeCol >= 0) c.getLong(totalSizeCol).coerceAtLeast(entry.sizeBytes) else entry.sizeBytes
                        val mapped = when (dmStatus) {
                            DownloadManager.STATUS_SUCCESSFUL -> "COMPLETED"
                            DownloadManager.STATUS_FAILED -> "FAILED"
                            else -> "DOWNLOADING"
                        }
                        if (mapped == "FAILED" &&
                            (entry.url.startsWith("http://", ignoreCase = true) || entry.url.startsWith("https://", ignoreCase = true)) &&
                            autoFallbackAttemptedIds.add(entry.id)
                        ) {
                            libraryRepo.updateDownloadStatus(entry.id, "DOWNLOADING")
                            executeDirectDownloadForRecord(
                                recordId = entry.id,
                                url = entry.url,
                                fileName = entry.fileName,
                                mimeType = entry.mimeType,
                                userAgent = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
                                refererUrl = "",
                                useTorProxy = entry.isOnionSource,
                            )
                            return@use
                        }
                        if (mapped == "FAILED" && reasonCol >= 0) {
                            val reasonText = describeDownloadManagerReason(c.getInt(reasonCol))
                            _uiState.update {
                                it.copy(downloadFailureReasons = it.downloadFailureReasons + (entry.id to reasonText))
                            }
                        } else if (mapped == "COMPLETED") {
                            _uiState.update {
                                it.copy(downloadFailureReasons = it.downloadFailureReasons - entry.id)
                            }
                        }
                        if (mapped != entry.status || dmSize != entry.sizeBytes) {
                            libraryRepo.updateDownloadDetails(
                                id = entry.id,
                                fileName = entry.fileName,
                                url = entry.url,
                                status = mapped,
                                systemDownloadId = entry.systemDownloadId,
                                sizeBytes = dmSize,
                            )
                        }
                    }
                }
            }
        }
    }

    fun retryDownload(download: DownloadEntity) {
        _uiState.update {
            it.copy(downloadFailureReasons = it.downloadFailureReasons - download.id)
        }
        viewModelScope.launch {
            if (download.url.startsWith("http://", ignoreCase = true) ||
                download.url.startsWith("https://", ignoreCase = true)
            ) {
                val cleanName = DownloadFileHelper.resolveDownloadFileName(
                    rawGuessedName = download.fileName,
                    url = download.url,
                    contentDisposition = null,
                    mimeType = download.mimeType,
                )
                libraryRepo.updateDownloadDetails(
                    id = download.id,
                    fileName = cleanName,
                    url = download.url,
                    status = "DOWNLOADING",
                    systemDownloadId = -1L,
                    sizeBytes = 0L,
                )
                _uiState.update { it.copy(statusBannerMessage = "Retrying $cleanName...") }
                executeDirectDownloadForRecord(
                    recordId = download.id,
                    url = download.url,
                    fileName = cleanName,
                    mimeType = download.mimeType,
                    userAgent = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
                    refererUrl = "",
                    useTorProxy = download.isOnionSource ||
                        _uiState.value.securityConfig.browserMode == BrowserMode.TOR_ONION,
                )
            } else if (download.url.startsWith("data:", ignoreCase = true)) {
                val app = getApplication<Application>()
                val saved = withContext(Dispatchers.IO) {
                    val decoded = DownloadFileHelper.decodeDataUri(download.url)
                    if (decoded == null) {
                        com.example.browser.SavedDownloadResult(false, download.fileName, errorMessage = "Invalid data URI")
                    } else {
                        decoded.bytes.inputStream().use { stream ->
                            DownloadFileHelper.saveStreamToDownloads(app, download.fileName, decoded.mimeType, stream)
                        }
                    }
                }
                libraryRepo.updateDownloadDetails(
                    id = download.id,
                    fileName = saved.savedFileName,
                    url = saved.localUri.ifBlank { download.url },
                    status = if (saved.success) "COMPLETED" else "FAILED",
                    systemDownloadId = -1L,
                    sizeBytes = saved.sizeBytes,
                )
            } else {
                _uiState.update {
                    it.copy(
                        downloadFailureReasons = it.downloadFailureReasons + (
                            download.id to "Temporary browser blob link expired. Re-open the site in a tab to export again."
                        ),
                        statusBannerMessage = "Temporary blob link expired — open the source website to re-download.",
                    )
                }
            }
        }
    }

    private fun describeDownloadManagerReason(reasonCode: Int): String {
        return when (reasonCode) {
            DownloadManager.ERROR_CANNOT_RESUME -> "Server does not support resuming this download."
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "External Downloads storage is unavailable."
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "A file with the same name already exists in Downloads."
            DownloadManager.ERROR_FILE_ERROR -> "Could not write file to Downloads storage."
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "Network connection dropped during download."
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough free storage space on device."
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Server redirected too many times."
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Server blocked or returned an HTTP error."
            in 400..599 -> "Server returned HTTP $reasonCode."
            else -> "Download did not complete (code $reasonCode)."
        }
    }

    fun openOrInstallDownloadedFile(download: DownloadEntity) {
        val app = getApplication<Application>()
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val isApk = TrackerAndUrlEngine.isApkAsset(download.url, download.fileName, download.mimeType)
        val resolvedMime = if (isApk) {
            "application/vnd.android.package-archive"
        } else {
            download.mimeType.ifBlank { "*/*" }
        }

        val targetUri: Uri? = when {
            download.url.startsWith("content://", ignoreCase = true) -> Uri.parse(download.url)
            download.systemDownloadId >= 0L -> runCatching { dm.getUriForDownloadedFile(download.systemDownloadId) }.getOrNull()
            else -> null
        }

        val opened = if (targetUri != null) {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(targetUri, resolvedMime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(intent)
                true
            }.getOrDefault(false)
        } else {
            false
        }

        if (!opened) {
            openSystemDownloadsFolder()
            _uiState.update {
                it.copy(
                    statusBannerMessage = if (isApk) {
                        "Opening Android Downloads — tap ${download.fileName} to install APK."
                    } else {
                        "Opening Android Downloads folder."
                    },
                )
            }
        }
    }

    fun openSystemDownloadsFolder() {
        val app = getApplication<Application>()
        runCatching {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        }.onFailure {
            _uiState.update {
                it.copy(statusBannerMessage = "Check your device's Files / Downloads app to install downloaded APKs.")
            }
        }
    }

    fun deleteDownloadRecord(id: Long) {
        viewModelScope.launch { libraryRepo.deleteDownload(id) }
        _uiState.update { it.copy(downloadFailureReasons = it.downloadFailureReasons - id) }
    }

    fun clearDownloads() {
        viewModelScope.launch { libraryRepo.clearDownloads() }
        _uiState.update { it.copy(downloadFailureReasons = emptyMap()) }
    }

    // Security preference mutations
    fun setNoScript(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setNoScript(enabled)
        _uiState.value.activeTab.let { tab ->
            if (!tab.isStartPage) webViewPool.getWebView(tab.id)?.reload()
        }
    }

    fun removeTrustedJsHost(host: String) = viewModelScope.launch {
        prefsRepo.removeTrustedJsHost(host)
        _uiState.value.activeTab.let { tab ->
            if (!tab.isStartPage) webViewPool.getWebView(tab.id)?.reload()
        }
        _uiState.update { it.copy(statusBannerMessage = "Removed JavaScript exception for $host") }
    }

    fun applyPrivacyPreset(maximumPrivacy: Boolean) = viewModelScope.launch {
        prefsRepo.applyPrivacyDefaults(maximumPrivacy)
        _uiState.value.activeTab.let { tab ->
            if (!tab.isStartPage) webViewPool.getWebView(tab.id)?.reload()
        }
        _uiState.update {
            it.copy(
                statusBannerMessage = if (maximumPrivacy) {
                    "Maximum Privacy active: JavaScript blocked, WebRTC & HTTPS-Only locked ON."
                } else {
                    "Recommended privacy defaults restored."
                },
            )
        }
    }

    fun applyServerPrivacyOverrides(serverConfig: com.example.data.repository.AdminServerConfig) = viewModelScope.launch {
        if (serverConfig.emergencyLockdown) {
            prefsRepo.applyPrivacyDefaults(maximumPrivacy = true)
            prefsRepo.setTrackerBlocker(true)
            prefsRepo.setCosmeticAdBlock(true)
        } else if (serverConfig.forceAdBlockSync) {
            prefsRepo.setTrackerBlocker(true)
            prefsRepo.setCosmeticAdBlock(true)
        }
        if (serverConfig.strictTorRequired) {
            prefsRepo.setBrowserMode(BrowserMode.TOR_ONION)
        }
        _uiState.value.activeTab.let { tab ->
            if (!tab.isStartPage) webViewPool.getWebView(tab.id)?.reload()
        }
        val activeOverrides = buildList {
            if (serverConfig.emergencyLockdown) add("Emergency Lockdown")
            if (serverConfig.strictTorRequired) add("Strict Tor Routing")
            if (serverConfig.forceAdBlockSync) add("Global Ad-Block Sync")
        }
        val summary = if (activeOverrides.isEmpty()) {
            "No strict server overrides currently active."
        } else {
            "Applied server overrides: ${activeOverrides.joinToString(", ")}"
        }
        _uiState.update { it.copy(statusBannerMessage = summary) }
    }

    fun setFingerprintRandomization(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setFingerprintRandomization(enabled)
    }

    fun setHttpsEverywhere(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setHttpsEverywhere(enabled)
    }

    fun setWebRtcLeakProtection(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setWebRtcLeakProtection(enabled)
        _uiState.value.activeTab.let { tab ->
            if (!tab.isStartPage) webViewPool.getWebView(tab.id)?.reload()
        }
    }

    fun setTrackerBlocker(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setTrackerBlocker(enabled)
        _uiState.value.activeTab.let { tab ->
            if (!tab.isStartPage) webViewPool.getWebView(tab.id)?.reload()
        }
        _uiState.update {
            it.copy(
                statusBannerMessage = if (enabled) {
                    "Ad & Tracker Blocker enabled."
                } else {
                    "Ad & Tracker Blocker paused."
                },
            )
        }
    }

    fun setPopupBlocker(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setPopupBlocker(enabled)
    }

    fun setCosmeticAdBlock(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setCosmeticAdBlock(enabled)
        _uiState.value.activeTab.let { tab ->
            if (!tab.isStartPage) webViewPool.getWebView(tab.id)?.reload()
        }
    }

    fun toggleAdBlockWhitelistCurrentSite() = viewModelScope.launch {
        val tab = _uiState.value.activeTab
        if (tab.isStartPage) return@launch
        val host = TrackerAndUrlEngine.normalizeHost(tab.url) ?: return@launch
        val currentlyWhitelisted = _uiState.value.securityConfig.adBlockWhitelistHosts.contains(host)
        prefsRepo.toggleAdBlockWhitelistHost(host)
        _uiState.update {
            it.copy(
                statusBannerMessage = if (currentlyWhitelisted) {
                    "Ad Blocker re-enabled on $host"
                } else {
                    "Ad Blocker paused on $host"
                },
            )
        }
        webViewPool.getWebView(tab.id)?.reload()
    }

    fun toggleAdBlockWhitelistHost(host: String) = viewModelScope.launch {
        prefsRepo.toggleAdBlockWhitelistHost(host)
    }

    fun addCustomBlockedDomain(domain: String) = viewModelScope.launch {
        val clean = domain.trim().lowercase()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')
            .removePrefix("www.")
        if (clean.isBlank() || !clean.contains('.')) {
            _uiState.update { it.copy(statusBannerMessage = "Enter a valid domain like ads.example.com") }
            return@launch
        }
        prefsRepo.addCustomBlockedDomain(clean)
        _uiState.update { it.copy(statusBannerMessage = "Added $clean to Ad Blocklist") }
    }

    fun removeCustomBlockedDomain(domain: String) = viewModelScope.launch {
        prefsRepo.removeCustomBlockedDomain(domain)
    }

    fun setBlockThirdPartyCookies(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setBlockThirdPartyCookies(enabled)
    }

    fun setSaveSurfaceHistory(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setSaveSurfaceHistory(enabled)
    }

    fun updateTorProxyConfig(host: String, port: Int, protocol: ProxyProtocol) = viewModelScope.launch {
        prefsRepo.updateTorProxy(host, port, protocol)
    }

    fun setSearchEngine(engine: SearchEngine) = viewModelScope.launch {
        prefsRepo.setSearchEngine(engine)
    }

    fun setForceDarkTheme(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setForceDarkTheme(enabled)
    }

    fun setCircuitPrivacyLevel(level: CircuitPrivacyLevel) = viewModelScope.launch {
        prefsRepo.setCircuitPrivacyLevel(level)
        if (level == CircuitPrivacyLevel.STRICT) {
            _uiState.value.activeTab.let { tab ->
                if (!tab.isStartPage) webViewPool.getWebView(tab.id)?.reload()
            }
            _uiState.update {
                it.copy(statusBannerMessage = "Strict Privacy Level: No-Script, WebRTC, HTTPS & 5s circuit checks locked ON.")
            }
        }
    }

    fun acceptDisclaimer() = viewModelScope.launch {
        prefsRepo.acceptDisclaimer()
        _uiState.update { it.copy(showDisclaimerDialog = false) }
    }

    fun openDisclaimer() {
        _uiState.update { it.copy(showDisclaimerDialog = true) }
    }

    fun updateSessionGuardConfig(
        enabled: Boolean,
        timeLimitMinutes: Int,
        budgetLimit: Double,
        currencyLabel: String,
    ) = viewModelScope.launch {
        prefsRepo.updateSessionGuardConfig(
            enabled = enabled,
            timeLimitMinutes = timeLimitMinutes,
            budgetLimit = budgetLimit,
            currencyLabel = currencyLabel,
        )
        _uiState.update {
            it.copy(
                statusBannerMessage = if (enabled) {
                    "Session Guard active (${timeLimitMinutes}m • $budgetLimit $currencyLabel limit)."
                } else {
                    "Session Guard paused."
                },
            )
        }
    }

    fun resetSessionGuardTimer() {
        LiveSessionRuntime.resetTimer()
        _uiState.update { it.copy(statusBannerMessage = "Session timer reset to 0 min.") }
    }

    fun logCompletedRound(
        siteHost: String,
        betAmount: Double,
        crashMultiplier: Double,
        cashoutMultiplier: Double?,
        note: String,
    ) = viewModelScope.launch {
        val cleanBet = betAmount.coerceAtLeast(0.0)
        val cleanCrash = crashMultiplier.coerceAtLeast(1.0)
        val validCashout = cashoutMultiplier?.takeIf { it >= 1.0 && it <= cleanCrash }
        val payout = if (validCashout != null) cleanBet * validCashout else 0.0
        val netProfit = payout - cleanBet
        val cleanHost = siteHost.trim().ifBlank {
            TrackerAndUrlEngine.normalizeHost(_uiState.value.activeTab.url) ?: "manual"
        }

        libraryRepo.insertSessionRoundLog(
            SessionRoundLogEntity(
                siteHost = cleanHost,
                betAmount = cleanBet,
                crashMultiplier = cleanCrash,
                cashoutMultiplier = validCashout,
                netProfit = netProfit,
                note = note.trim(),
            ),
        )
    }

    fun deleteSessionRoundLog(id: Long) = viewModelScope.launch {
        val target = sessionRoundLogs.value.firstOrNull { it.id == id }
        libraryRepo.deleteSessionRoundLog(id)
        if (target != null) {
            sessionMonitorSyncService?.deleteRemoteRoundForLocal(target)
        }
    }

    fun clearSessionRoundLogs() = viewModelScope.launch {
        libraryRepo.clearSessionRoundLogs()
    }

    fun setLiveCloudMonitorSync(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setLiveCloudMonitorSync(enabled)
        _uiState.update {
            it.copy(
                statusBannerMessage = if (enabled) {
                    "Live Remote Monitor Sync enabled (requires Google Sign-In in Cloud Sync)."
                } else {
                    "Live Remote Monitor Sync paused."
                },
            )
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun setBackgroundLiveServiceEnabled(enabled: Boolean) = viewModelScope.launch {
        val app = getApplication<Application>()
        if (enabled) {
            if (!hasNotificationPermission()) {
                prefsRepo.setBackgroundLiveService(false)
                _uiState.update {
                    it.copy(
                        statusBannerMessage = "Notification permission is required to keep the live background service active.",
                    )
                }
                return@launch
            }
            prefsRepo.setBackgroundLiveService(true)
            LiveMonitorForegroundService.start(app)
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Foreground Service active — browser & Tor keep-alive running in background.",
                )
            }
        } else {
            prefsRepo.setBackgroundLiveService(false)
            LiveMonitorForegroundService.stop(app)
            _uiState.update {
                it.copy(statusBannerMessage = "Background live monitor stopped.")
            }
        }
    }

    fun setBackgroundMediaPlayback(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setBackgroundMediaPlayback(enabled)
        _uiState.update {
            it.copy(
                statusBannerMessage = if (enabled) {
                    "Background YouTube & media playback enabled."
                } else {
                    "Background media keep-alive disabled."
                },
            )
        }
    }

    fun onAppBackgrounded() {
        val cfg = _uiState.value.securityConfig
        if (cfg.browserMode == BrowserMode.SURFACE) {
            webViewPool.flushCookies()
        }
        val hasOpenWebTab = _uiState.value.tabs.any { !it.isStartPage }
        val hasMediaHostTab = _uiState.value.tabs.any { tab ->
            val host = TrackerAndUrlEngine.normalizeHost(tab.url).orEmpty()
            host == "youtube.com" ||
                host.endsWith(".youtube.com") ||
                host == "youtu.be" ||
                host == "vimeo.com" ||
                host == "twitch.tv" ||
                host == "soundcloud.com"
        }
        val mediaPlaying = LiveSessionRuntime.isMediaPlaybackActive.value

        if (cfg.backgroundMediaPlaybackEnabled && (mediaPlaying || hasOpenWebTab)) {
            if (!LiveSessionRuntime.isForegroundServiceRunning.value &&
                (mediaPlaying || hasMediaHostTab || cfg.backgroundLiveServiceEnabled)
            ) {
                runCatching { LiveMonitorForegroundService.start(getApplication()) }
            }
            webViewPool.onAppBackgrounded(keepMediaAlive = true)
        } else if (cfg.backgroundLiveServiceEnabled &&
            !LiveSessionRuntime.isForegroundServiceRunning.value &&
            hasNotificationPermission()
        ) {
            runCatching { LiveMonitorForegroundService.start(getApplication()) }
        }
    }

    fun onAppForegrounded() {
        webViewPool.onAppForegrounded()
    }

    fun unlockApp() {
        _uiState.update { it.copy(isAppLocked = false) }
    }

    fun lockAppNow() {
        lockVault()
        _uiState.update { it.copy(isAppLocked = true) }
    }

    fun lockAppIfEnabled() {
        if (AppLockPolicy.shouldLockOnBackground(_uiState.value.securityConfig.biometricAppLockEnabled)) {
            lockVault()
            _uiState.update { it.copy(isAppLocked = true) }
        }
    }

    fun setBiometricAppLockEnabled(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setBiometricAppLockEnabled(enabled)
        _uiState.update {
            it.copy(
                isAppLocked = AppLockPolicy.resolveAfterToggle(it.isAppLocked, enabled),
                statusBannerMessage = if (enabled) {
                    "Biometric App Lock enabled — required when opening or returning to OnionShield."
                } else {
                    "Biometric App Lock disabled."
                },
            )
        }
    }

    fun selectVpnProfile(profile: VpnServerProfile) = viewModelScope.launch {
        prefsRepo.setVpnProfile(profile)
        if (VpnTunnelRuntime.status.value is VpnTunnelStatus.Connected ||
            VpnTunnelRuntime.status.value is VpnTunnelStatus.Connecting
        ) {
            OnionShieldVpnService.startTunnel(getApplication(), profile)
        }
        _uiState.update {
            it.copy(statusBannerMessage = "VPN profile set to ${profile.displayName} (${profile.badge}).")
        }
    }

    fun startVpnTunnelAfterConsent(profile: VpnServerProfile = _uiState.value.securityConfig.vpnProfile) {
        OnionShieldVpnService.startTunnel(getApplication(), profile)
        _uiState.update {
            it.copy(statusBannerMessage = "Encrypted VPN connected via ${profile.displayName}.")
        }
    }

    fun stopVpnTunnel() {
        OnionShieldVpnService.stopTunnel(getApplication())
        _uiState.update {
            it.copy(statusBannerMessage = "VPN tunnel disconnected.")
        }
    }

    // AI Screen Trade Pilot
    fun toggleTradePilotPanel() {
        val next = !_uiState.value.tradePilot.isPanelExpanded
        _uiState.update { state ->
            state.copy(
                tradePilot = state.tradePilot.copy(
                    isPanelExpanded = next,
                    lastError = if (next) null else state.tradePilot.lastError,
                ),
            )
        }
    }

    fun closeTradePilotPanel() {
        _uiState.update { state ->
            state.copy(tradePilot = state.tradePilot.copy(isPanelExpanded = false))
        }
    }

    fun setTradeMinConfidence(percent: Int) {
        val clamped = percent.coerceIn(50, 95)
        _uiState.update { state ->
            state.copy(tradePilot = state.tradePilot.copy(minConfidencePercent = clamped))
        }
    }

    fun setTradeCooldownSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(5, 60)
        _uiState.update { state ->
            state.copy(tradePilot = state.tradePilot.copy(cooldownSeconds = clamped))
        }
    }

    fun clearLastTapIndicator() {
        _uiState.update { state ->
            state.copy(
                tradePilot = state.tradePilot.copy(
                    lastTapNormX = null,
                    lastTapNormY = null,
                ),
            )
        }
    }

    fun toggleTradeAutoPilot(enabled: Boolean) {
        if (!enabled) {
            tradeAutoPilotJob?.cancel()
            tradeAutoPilotJob = null
            _uiState.update { state ->
                state.copy(
                    tradePilot = state.tradePilot.copy(isAutoPilotRunning = false),
                    statusBannerMessage = "AI Auto-Click paused.",
                )
            }
            return
        }
        if (_uiState.value.activeTab.isStartPage) {
            _uiState.update { state ->
                state.copy(
                    tradePilot = state.tradePilot.copy(
                        isAutoPilotRunning = false,
                        lastError = "Open a live trading chart webpage first before starting Auto-Click.",
                    ),
                )
            }
            return
        }
        tradeAutoPilotJob?.cancel()
        _uiState.update { state ->
            state.copy(
                tradePilot = state.tradePilot.copy(
                    isPanelExpanded = true,
                    isAutoPilotRunning = true,
                    lastError = null,
                ),
            )
        }
        tradeAutoPilotJob = viewModelScope.launch {
            try {
                while (isActive && _uiState.value.tradePilot.isAutoPilotRunning) {
                    val activeTab = _uiState.value.activeTab
                    if (activeTab.isStartPage) {
                        _uiState.update { s ->
                            s.copy(
                                tradePilot = s.tradePilot.copy(isAutoPilotRunning = false),
                                statusBannerMessage = "Returned to Start Page — Auto-Click stopped.",
                            )
                        }
                        break
                    }
                    runTradeScreenAnalysisInternal(executeClick = true)
                    val delayMs = _uiState.value.tradePilot.cooldownSeconds.coerceIn(5, 60) * 1000L
                    delay(delayMs)
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _uiState.update { state ->
                    state.copy(tradePilot = state.tradePilot.copy(isAutoPilotRunning = false))
                }
            }
        }
    }

    fun analyzeScreenForTrade(executeClick: Boolean) {
        if (_uiState.value.tradePilot.isAnalyzing) return
        viewModelScope.launch {
            runTradeScreenAnalysisInternal(executeClick = executeClick)
        }
    }

    fun executeLastTradeDecision() {
        val currentState = _uiState.value
        val activeTab = currentState.activeTab
        val decision = currentState.tradePilot.lastDecision ?: return
        if (activeTab.isStartPage || decision.decision == TradeDecision.HOLD) return
        val target = currentState.tradePilot.lastResolvedTarget ?: TradePilotHelper.resolveClickTarget(
            decision = decision.decision,
            aiNormX = decision.normalizedX,
            aiNormY = decision.normalizedY,
            domButtons = lastDetectedTradeButtons,
        )
        if (target == null) {
            _uiState.update { state ->
                state.copy(
                    tradePilot = state.tradePilot.copy(
                        lastError = "Could not locate ${decision.decision.name} button on this screen. Scan again.",
                    ),
                )
            }
            return
        }
        viewModelScope.launch {
            val clicked = dispatchTradeClick(activeTab.id, target.normX, target.normY)
            _uiState.update { state ->
                if (clicked) {
                    state.copy(
                        tradePilot = state.tradePilot.copy(
                            lastClickedAction = decision.decision.name,
                            lastClickedAtMs = System.currentTimeMillis(),
                            lastTapNormX = target.normX,
                            lastTapNormY = target.normY,
                            totalExecutionsCount = state.tradePilot.totalExecutionsCount + 1,
                            lastError = null,
                        ),
                        statusBannerMessage = "Tapped ${decision.decision.name} (${decision.confidence}% confidence)",
                    )
                } else {
                    state.copy(
                        tradePilot = state.tradePilot.copy(
                            lastError = "Unable to dispatch touch tap onto WebView.",
                        ),
                    )
                }
            }
        }
    }

    private suspend fun runTradeScreenAnalysisInternal(executeClick: Boolean) {
        val activeTab = _uiState.value.activeTab
        if (activeTab.isStartPage) {
            _uiState.update { state ->
                state.copy(
                    tradePilot = state.tradePilot.copy(
                        isAnalyzing = false,
                        lastError = "Open a webpage with a chart and Buy/Sell buttons first.",
                    ),
                )
            }
            return
        }

        _uiState.update { state ->
            state.copy(
                tradePilot = state.tradePilot.copy(
                    isPanelExpanded = true,
                    isAnalyzing = true,
                    isCapturingScreen = true,
                    lastError = null,
                ),
            )
        }

        var capturedBitmap: android.graphics.Bitmap? = null
        try {
            // Brief frame delay so any floating overlay inside WebView bounds hides before PixelCopy.
            delay(60L)
            val (bitmap, detectedButtons) = suspendCancellableCoroutine { cont ->
                webViewPool.captureScreenAndTradeControls(activeTab.id) { bmp, buttons ->
                    if (cont.isActive) {
                        cont.resume(bmp to buttons)
                    } else {
                        runCatching { bmp?.recycle() }
                    }
                }
            }
            _uiState.update { state ->
                state.copy(tradePilot = state.tradePilot.copy(isCapturingScreen = false))
            }
            capturedBitmap = bitmap
            lastDetectedTradeButtons = detectedButtons
            if (bitmap == null) {
                _uiState.update { state ->
                    state.copy(
                        tradePilot = state.tradePilot.copy(
                            isAnalyzing = false,
                            lastError = "Could not capture webpage screen yet. Wait for page to render and retry.",
                        ),
                    )
                }
                return
            }

            val controlsSummary = TradePilotHelper.formatControlsSummary(detectedButtons)
            val decision = withContext(Dispatchers.IO) {
                geminiShieldRepository.analyzeScreenForTradeSignal(
                    screenshot = bitmap,
                    pageUrl = activeTab.url,
                    domControlsSummary = controlsSummary,
                )
            }

            val resolvedTarget = TradePilotHelper.resolveClickTarget(
                decision = decision.decision,
                aiNormX = decision.normalizedX,
                aiNormY = decision.normalizedY,
                domButtons = detectedButtons,
            )

            val minConfidence = _uiState.value.tradePilot.minConfidencePercent
            val shouldClickNow = executeClick && TradePilotHelper.shouldAutoExecuteClick(decision, minConfidence)
            var clicked = false
            var clickFailureReason: String? = null

            if (shouldClickNow) {
                if (resolvedTarget != null) {
                    clicked = dispatchTradeClick(activeTab.id, resolvedTarget.normX, resolvedTarget.normY)
                    if (!clicked) {
                        clickFailureReason = "Could not dispatch click on ${decision.decision.name} button."
                    }
                } else {
                    clickFailureReason = "AI chose ${decision.decision.name}, but button position wasn't visible on screen."
                }
            }

            _uiState.update { state ->
                val banner = when {
                    clicked && resolvedTarget != null ->
                        "AI Clicked ${decision.decision.name} (${decision.confidence}% • ${decision.assetName})"
                    executeClick && decision.decision == TradeDecision.HOLD ->
                        "AI chose HOLD (${decision.marketBias}) — waiting for clearer swing"
                    executeClick && decision.decision != TradeDecision.HOLD && decision.confidence < minConfidence ->
                        "Signal ${decision.decision.name} (${decision.confidence}%) below $minConfidence% threshold — skipped"
                    else -> null
                }
                state.copy(
                    tradePilot = state.tradePilot.copy(
                        isAnalyzing = false,
                        isCapturingScreen = false,
                        lastDecision = decision,
                        lastResolvedTarget = resolvedTarget,
                        lastClickedAction = if (clicked) decision.decision.name else state.tradePilot.lastClickedAction,
                        lastClickedAtMs = if (clicked) System.currentTimeMillis() else state.tradePilot.lastClickedAtMs,
                        lastTapNormX = if (clicked && resolvedTarget != null) resolvedTarget.normX else null,
                        lastTapNormY = if (clicked && resolvedTarget != null) resolvedTarget.normY else null,
                        totalExecutionsCount = if (clicked) state.tradePilot.totalExecutionsCount + 1 else state.tradePilot.totalExecutionsCount,
                        lastError = clickFailureReason,
                    ),
                    statusBannerMessage = banner ?: state.statusBannerMessage,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val friendlyMsg = if (GeminiShieldRepository.isTransientHighDemandError(e)) {
                "Gemini Vision is briefly busy right now. Tap Analyze again."
            } else {
                e.localizedMessage?.takeIf { it.isNotBlank() } ?: "Screen analysis failed."
            }
            _uiState.update { state ->
                state.copy(
                    tradePilot = state.tradePilot.copy(
                        isAnalyzing = false,
                        isCapturingScreen = false,
                        lastError = friendlyMsg,
                    ),
                )
            }
        } finally {
            _uiState.update { state ->
                state.copy(tradePilot = state.tradePilot.copy(isCapturingScreen = false))
            }
            runCatching { capturedBitmap?.recycle() }
        }
    }

    private suspend fun dispatchTradeClick(tabId: String, normX: Float, normY: Float): Boolean =
        suspendCancellableCoroutine { cont ->
            webViewPool.dispatchWebViewClick(tabId, normX, normY) { ok ->
                if (cont.isActive) {
                    cont.resume(ok)
                }
            }
        }

    // WebViewEventCallbacks
    override fun onPageStarted(tabId: String, url: String) {
        updateTab(tabId) { tab ->
            tab.copy(
                url = url,
                isLoading = true,
                progress = 10,
                isOnionAddress = TrackerAndUrlEngine.isOnionUrl(url),
                isHttpsSecure = url.startsWith("https://", ignoreCase = true) || TrackerAndUrlEngine.isOnionUrl(url),
                lastError = null,
            )
        }
        if (_uiState.value.activeTabId == tabId && !_uiState.value.isOmniboxFocused) {
            _uiState.update { it.copy(omniboxInput = url) }
        }
    }

    override fun onPageFinished(
        tabId: String,
        url: String,
        title: String?,
        canGoBack: Boolean,
        canGoForward: Boolean,
    ) {
        val cleanTitle = title?.takeIf { it.isNotBlank() } ?: url
        updateTab(tabId) { tab ->
            tab.copy(
                url = url,
                title = cleanTitle,
                isLoading = false,
                progress = 100,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                isOnionAddress = TrackerAndUrlEngine.isOnionUrl(url),
                isHttpsSecure = url.startsWith("https://", ignoreCase = true) || TrackerAndUrlEngine.isOnionUrl(url),
            )
        }
        if (_uiState.value.activeTabId == tabId && !_uiState.value.isOmniboxFocused) {
            _uiState.update { it.copy(omniboxInput = url) }
        }

        val cfg = _uiState.value.securityConfig
        if (cfg.saveSurfaceHistory && cfg.browserMode == BrowserMode.SURFACE && url != ABOUT_START_URL) {
            viewModelScope.launch {
                libraryRepo.recordHistory(cleanTitle, url)
            }
        }
    }

    override fun onProgressChanged(tabId: String, progress: Int) {
        updateTab(tabId) { it.copy(progress = progress, isLoading = progress < 100) }
    }

    override fun onTitleReceived(tabId: String, title: String) {
        updateTab(tabId) { it.copy(title = title) }
    }

    override fun onTrackerBlocked(tabId: String) {
        updateTab(tabId) { it.copy(blockedTrackersCount = it.blockedTrackersCount + 1) }
        _uiState.update { it.copy(totalTrackersBlocked = it.totalTrackersBlocked + 1) }
    }

    override fun onScriptBlocked(tabId: String) {
        updateTab(tabId) { it.copy(blockedScriptsCount = it.blockedScriptsCount + 1) }
        _uiState.update { it.copy(totalScriptsBlocked = it.totalScriptsBlocked + 1) }
    }

    override fun onPageError(tabId: String, url: String, errorDescription: String) {
        updateTab(tabId) {
            it.copy(
                isLoading = false,
                progress = 0,
                lastError = errorDescription,
            )
        }
    }

    override fun onDownloadIntercepted(tabId: String, request: PendingDownloadRequest) {
        _uiState.update { it.copy(pendingDownload = request) }
    }

    override fun onRendererTerminated(tabId: String) {
        updateTab(tabId) {
            it.copy(
                isLoading = false,
                lastError = "WebView renderer process terminated cleanly for isolation. Tap Reload to resume.",
            )
        }
    }

    override fun onOpenNewTab(url: String) {
        val mode = if (TrackerAndUrlEngine.isOnionUrl(url)) {
            BrowserMode.TOR_ONION
        } else {
            _uiState.value.securityConfig.browserMode
        }
        createNewTab(mode = mode, initialUrl = url)
    }

    override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        if (view == null) {
            callback?.onCustomViewHidden()
            return
        }
        customVideoCallback = callback
        _fullscreenCustomView.value = view
    }

    override fun onHideCustomView() {
        customVideoCallback = null
        _fullscreenCustomView.value = null
    }

    override fun onLinkLongPressed(tabId: String, url: String, title: String?) {
        showLinkActionSheet(url = url, title = title.orEmpty(), fromExternalShare = false)
    }

    override fun onAuthenticatedSiteDetected(host: String) {
        viewModelScope.launch {
            prefsRepo.addAuthenticatedSessionHost(host)
        }
    }

    override fun onOpenPopupWebView(popupWebView: WebView) {
        val previous = _popupWebView.value
        if (previous != null && previous !== popupWebView) {
            runCatching {
                (previous.parent as? ViewGroup)?.removeView(previous)
                previous.stopLoading()
                previous.destroy()
            }
        }
        _popupWebView.value = popupWebView
    }

    override fun onClosePopupWebView(popupWebView: WebView) {
        if (_popupWebView.value === popupWebView || _popupWebView.value == null) {
            dismissPopupWebView()
        } else {
            runCatching {
                (popupWebView.parent as? ViewGroup)?.removeView(popupWebView)
                popupWebView.stopLoading()
                popupWebView.destroy()
            }
        }
    }

    fun dismissPopupWebView() {
        if (_uiState.value.securityConfig.browserMode == BrowserMode.SURFACE) {
            webViewPool.flushCookies()
        }
        val active = _popupWebView.value ?: return
        _popupWebView.value = null
        runCatching {
            (active.parent as? ViewGroup)?.removeView(active)
            active.stopLoading()
            active.destroy()
        }
    }

    fun exitFullscreenVideo() {
        customVideoCallback?.onCustomViewHidden()
        customVideoCallback = null
        _fullscreenCustomView.value = null
    }

    private fun updateTab(tabId: String, transform: (BrowserTab) -> BrowserTab) {
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) transform(tab) else tab
                },
            )
        }
    }

    override fun onCleared() {
        tradeAutoPilotJob?.cancel()
        unlockedVaultKey = null
        sessionMonitorSyncService?.stop()
        dismissPopupWebView()
        exitFullscreenVideo()
        webViewPool.releaseAllViewsPreservingSession()
        super.onCleared()
    }
}

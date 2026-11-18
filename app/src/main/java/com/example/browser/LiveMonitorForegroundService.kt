package com.example.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.BrowserMode
import com.example.data.OnionShieldDatabase
import com.example.data.PrivacyPreferencesRepository
import com.example.data.SecurityConfig
import com.example.data.TorProxyState
import com.example.data.repository.SessionMonitorFirestoreRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Process-wide live session state shared between [BrowserViewModel] and
 * [LiveMonitorForegroundService] so timer ticks, active site metadata, Tor proxy
 * health, and background service status survive Activity destruction or leaving the app.
 */
object LiveSessionRuntime {
    private val _sessionStartEpochMs = MutableStateFlow(System.currentTimeMillis())
    val sessionStartEpochMs: StateFlow<Long> = _sessionStartEpochMs.asStateFlow()

    private val _elapsedMinutes = MutableStateFlow(0L)
    val elapsedMinutes: StateFlow<Long> = _elapsedMinutes.asStateFlow()

    private val _activeSiteHost = MutableStateFlow("manual")
    val activeSiteHost: StateFlow<String> = _activeSiteHost.asStateFlow()

    private val _isForegroundServiceRunning = MutableStateFlow(false)
    val isForegroundServiceRunning: StateFlow<Boolean> = _isForegroundServiceRunning.asStateFlow()

    private val _foregroundSyncStatus = MutableStateFlow<SessionMonitorSyncStatus>(SessionMonitorSyncStatus.Disabled)
    val foregroundSyncStatus: StateFlow<SessionMonitorSyncStatus> = _foregroundSyncStatus.asStateFlow()

    private val _liveTorProxyState = MutableStateFlow<TorProxyState>(TorProxyState.Inactive)
    val liveTorProxyState: StateFlow<TorProxyState> = _liveTorProxyState.asStateFlow()

    private val _isMediaPlaybackActive = MutableStateFlow(false)
    val isMediaPlaybackActive: StateFlow<Boolean> = _isMediaPlaybackActive.asStateFlow()

    private val _hasMediaSession = MutableStateFlow(false)
    val hasMediaSession: StateFlow<Boolean> = _hasMediaSession.asStateFlow()

    private val _activeMediaTitle = MutableStateFlow("")
    val activeMediaTitle: StateFlow<String> = _activeMediaTitle.asStateFlow()

    private val _activeMediaArtist = MutableStateFlow("OnionShield Media")
    val activeMediaArtist: StateFlow<String> = _activeMediaArtist.asStateFlow()

    private val _activeMediaPositionMs = MutableStateFlow(0L)
    val activeMediaPositionMs: StateFlow<Long> = _activeMediaPositionMs.asStateFlow()

    private val _activeMediaDurationMs = MutableStateFlow(0L)
    val activeMediaDurationMs: StateFlow<Long> = _activeMediaDurationMs.asStateFlow()

    private val _activeMediaUrl = MutableStateFlow("")
    val activeMediaUrl: StateFlow<String> = _activeMediaUrl.asStateFlow()

    private val _arePowerLocksHeld = MutableStateFlow(false)
    val arePowerLocksHeld: StateFlow<Boolean> = _arePowerLocksHeld.asStateFlow()

    private val _mediaControlCommands = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 16)
    val mediaControlCommands: kotlinx.coroutines.flow.Flow<String> = _mediaControlCommands

    fun refreshElapsed(nowEpochMs: Long = System.currentTimeMillis()): Long {
        val mins = ((nowEpochMs - _sessionStartEpochMs.value) / 60_000L).coerceAtLeast(0L)
        _elapsedMinutes.value = mins
        return mins
    }

    fun resetTimer(nowEpochMs: Long = System.currentTimeMillis()) {
        _sessionStartEpochMs.value = nowEpochMs
        _elapsedMinutes.value = 0L
    }

    fun updateActiveSiteHost(rawUrlOrHost: String) {
        val extracted = if (rawUrlOrHost.contains("://")) {
            TrackerAndUrlEngine.normalizeHost(rawUrlOrHost) ?: "manual"
        } else {
            rawUrlOrHost
        }
        _activeSiteHost.value = SessionMonitorFirestoreRepository.sanitizeSiteHost(extracted)
    }

    fun setMediaPlaybackState(active: Boolean, title: String = "") {
        _isMediaPlaybackActive.value = active
        if (active) {
            _hasMediaSession.value = true
            _activeMediaTitle.value = title.trim().take(100)
        } else {
            _activeMediaTitle.value = ""
            _hasMediaSession.value = false
        }
    }

    fun updateMediaSnapshot(
        isPlaying: Boolean,
        title: String,
        artist: String = "OnionShield Media",
        positionMs: Long = _activeMediaPositionMs.value,
        durationMs: Long = _activeMediaDurationMs.value,
        url: String = _activeMediaUrl.value,
    ) {
        _isMediaPlaybackActive.value = isPlaying
        if (isPlaying || title.isNotBlank()) {
            _hasMediaSession.value = true
        }
        if (title.isNotBlank()) {
            _activeMediaTitle.value = title.trim().take(100)
        }
        if (artist.isNotBlank()) {
            _activeMediaArtist.value = artist.trim().take(80)
        }
        if (positionMs >= 0L) {
            _activeMediaPositionMs.value = positionMs
        }
        if (durationMs >= 0L) {
            _activeMediaDurationMs.value = durationMs
        }
        if (url.isNotBlank()) {
            _activeMediaUrl.value = url
        }
    }

    fun clearMediaSession() {
        _isMediaPlaybackActive.value = false
        _hasMediaSession.value = false
        _activeMediaTitle.value = ""
        _activeMediaPositionMs.value = 0L
        _activeMediaDurationMs.value = 0L
    }

    internal fun emitMediaCommand(command: String) {
        _mediaControlCommands.tryEmit(command)
    }

    internal fun setForegroundRunning(running: Boolean) {
        _isForegroundServiceRunning.value = running
        if (!running) {
            _foregroundSyncStatus.value = SessionMonitorSyncStatus.Disabled
            _liveTorProxyState.value = TorProxyState.Inactive
            _arePowerLocksHeld.value = false
        }
    }

    internal fun setPowerLocksHeld(held: Boolean) {
        _arePowerLocksHeld.value = held
    }

    internal fun updateForegroundSyncStatus(status: SessionMonitorSyncStatus) {
        _foregroundSyncStatus.value = status
    }

    internal fun updateTorProxyState(state: TorProxyState) {
        _liveTorProxyState.value = state
    }
}

/**
 * Play-policy-compliant `dataSync` Foreground Service that keeps the OnionShield
 * browser session alive, maintains & monitors Tor SOCKS5/HTTP proxy & ControlPort
 * connectivity, updates the Session Guard timer, and streams optional Cloud Monitor sync
 * even after the user navigates away from the app.
 */
class LiveMonitorForegroundService : Service() {

    private var serviceScope: CoroutineScope? = null
    private var monitoringJob: Job? = null
    private var tickerJob: Job? = null
    private var torKeepAliveJob: Job? = null
    private var syncStatusForwardJob: Job? = null
    private var syncService: SessionMonitorSyncService? = null

    private var mediaStateJob: Job? = null

    private val wakeLockGuard = Any()
    private var partialWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    internal var mediaSessionHandler: OnionMediaSessionHandler? = null
        private set

    private var latestStats: SessionGuardStats = SessionGuardStats()
    private var latestConfig: SecurityConfig = SecurityConfig()
    private var latestSyncStatus: SessionMonitorSyncStatus = SessionMonitorSyncStatus.Disabled
    private var latestTorState: TorProxyState = TorProxyState.Inactive
    private var latestMediaActive: Boolean = false
    private var latestMediaTitle: String = ""
    private var latestMediaSessionPaused: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        mediaSessionHandler = OnionMediaSessionHandler(
            context = applicationContext,
            onPlayCommand = {
                LiveSessionRuntime.emitMediaCommand(CMD_PLAY_MEDIA)
                LiveSessionRuntime.updateMediaSnapshot(
                    isPlaying = true,
                    title = LiveSessionRuntime.activeMediaTitle.value.ifBlank { latestMediaTitle },
                )
            },
            onPauseCommand = {
                LiveSessionRuntime.emitMediaCommand(CMD_PAUSE_MEDIA)
                LiveSessionRuntime.updateMediaSnapshot(
                    isPlaying = false,
                    title = LiveSessionRuntime.activeMediaTitle.value.ifBlank { latestMediaTitle },
                )
            },
            onStopCommand = {
                LiveSessionRuntime.emitMediaCommand(CMD_STOP_MEDIA)
                LiveSessionRuntime.clearMediaSession()
            },
            onSeekCommand = { posMs ->
                LiveSessionRuntime.emitMediaCommand("$CMD_SEEK_PREFIX$posMs")
            },
            onSkipNextCommand = {
                LiveSessionRuntime.emitMediaCommand(CMD_NEXT_TRACK)
            },
            onSkipPrevCommand = {
                LiveSessionRuntime.emitMediaCommand(CMD_PREV_TRACK)
            },
            onStateChanged = { snap ->
                latestMediaActive = snap.isPlaying
                latestMediaTitle = snap.title
                latestMediaSessionPaused = snap.hasActiveSession && !snap.isPlaying
                updateNotification(
                    buildNotification(
                        stats = latestStats,
                        config = latestConfig,
                        syncStatus = latestSyncStatus,
                        torState = latestTorState,
                        isMediaActive = latestMediaActive,
                        mediaTitle = latestMediaTitle,
                        isMediaPausedSession = latestMediaSessionPaused,
                        mediaSessionToken = mediaSessionHandler?.sessionToken,
                    ),
                )
            },
        ).also { it.ensureSession() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestMediaActive = LiveSessionRuntime.isMediaPlaybackActive.value
        latestMediaTitle = LiveSessionRuntime.activeMediaTitle.value
        latestMediaSessionPaused = !latestMediaActive && LiveSessionRuntime.hasMediaSession.value
        when (intent?.action) {
            ACTION_STOP -> {
                stopAndClearPreference()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_MEDIA -> {
                mediaSessionHandler?.handlePauseFromSession(abandonFocus = false)
                latestMediaActive = false
                latestMediaSessionPaused = true
                promoteToForeground(
                    buildNotification(
                        stats = latestStats,
                        config = latestConfig,
                        syncStatus = latestSyncStatus,
                        torState = latestTorState,
                        isMediaActive = false,
                        mediaTitle = latestMediaTitle,
                        isMediaPausedSession = true,
                        mediaSessionToken = mediaSessionHandler?.sessionToken,
                    ),
                )
                return START_STICKY
            }
            ACTION_RESUME_MEDIA -> {
                acquireWakeAndWifiLocks()
                mediaSessionHandler?.handlePlayFromSession()
                latestMediaActive = true
                latestMediaSessionPaused = false
                promoteToForeground(
                    buildNotification(
                        stats = latestStats,
                        config = latestConfig,
                        syncStatus = latestSyncStatus,
                        torState = latestTorState,
                        isMediaActive = true,
                        mediaTitle = latestMediaTitle,
                        isMediaPausedSession = false,
                        mediaSessionToken = mediaSessionHandler?.sessionToken,
                    ),
                )
                return START_STICKY
            }
            ACTION_NEW_CIRCUIT -> {
                promoteToForeground(
                    buildNotification(
                        stats = latestStats,
                        config = latestConfig,
                        syncStatus = latestSyncStatus,
                        torState = latestTorState,
                        isMediaActive = latestMediaActive,
                        mediaTitle = latestMediaTitle,
                        isMediaPausedSession = latestMediaSessionPaused,
                        mediaSessionToken = mediaSessionHandler?.sessionToken,
                    ),
                )
                LiveSessionRuntime.setForegroundRunning(true)
                acquireWakeAndWifiLocks()
                if (latestMediaActive) {
                    mediaSessionHandler?.updatePlayback(
                        title = latestMediaTitle,
                        artist = LiveSessionRuntime.activeMediaArtist.value,
                        isPlaying = true,
                        positionMs = LiveSessionRuntime.activeMediaPositionMs.value,
                        durationMs = LiveSessionRuntime.activeMediaDurationMs.value,
                    )
                }
                startMonitoringLoopsIfNeeded()
                triggerNewTorCircuit()
                return START_STICKY
            }
            else -> {
                // Immediately promote to foreground within Android 14+ startup deadline
                promoteToForeground(
                    buildNotification(
                        stats = latestStats,
                        config = latestConfig,
                        syncStatus = latestSyncStatus,
                        torState = latestTorState,
                        isMediaActive = latestMediaActive,
                        mediaTitle = latestMediaTitle,
                        isMediaPausedSession = latestMediaSessionPaused,
                        mediaSessionToken = mediaSessionHandler?.sessionToken,
                    ),
                )
                LiveSessionRuntime.setForegroundRunning(true)
                acquireWakeAndWifiLocks()
                if (latestMediaActive) {
                    mediaSessionHandler?.updatePlayback(
                        title = latestMediaTitle,
                        artist = LiveSessionRuntime.activeMediaArtist.value,
                        isPlaying = true,
                        positionMs = LiveSessionRuntime.activeMediaPositionMs.value,
                        durationMs = LiveSessionRuntime.activeMediaDurationMs.value,
                    )
                }
                startMonitoringLoopsIfNeeded()
                return START_STICKY
            }
        }
    }

    private fun startMonitoringLoopsIfNeeded() {
        val scope = serviceScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also {
            serviceScope = it
        }

        if (tickerJob == null) {
            tickerJob = scope.launch {
                while (isActive) {
                    LiveSessionRuntime.refreshElapsed()
                    delay(15_000L)
                }
            }
        }

        if (torKeepAliveJob == null) {
            torKeepAliveJob = scope.launch {
                val prefsRepo = PrivacyPreferencesRepository(applicationContext)
                prefsRepo.securityConfig.collectLatest { cfg ->
                    latestConfig = cfg
                    if (cfg.browserMode == BrowserMode.TOR_ONION) {
                        while (isActive) {
                            val nextTorState = checkTorConnectivityOnce(cfg)
                            if (nextTorState != latestTorState) {
                                latestTorState = nextTorState
                                LiveSessionRuntime.updateTorProxyState(nextTorState)
                            } else {
                                LiveSessionRuntime.updateTorProxyState(nextTorState)
                            }
                            updateNotification(
                                buildNotification(
                                    stats = latestStats,
                                    config = cfg,
                                    syncStatus = latestSyncStatus,
                                    torState = latestTorState,
                                    isMediaActive = latestMediaActive,
                                    mediaTitle = latestMediaTitle,
                                    isMediaPausedSession = latestMediaSessionPaused,
                                    mediaSessionToken = mediaSessionHandler?.sessionToken,
                                ),
                            )
                            delay(cfg.circuitPrivacyLevel.livePollIntervalMs)
                        }
                    } else {
                        latestTorState = TorProxyState.Inactive
                        LiveSessionRuntime.updateTorProxyState(TorProxyState.Inactive)
                        updateNotification(
                            buildNotification(
                                stats = latestStats,
                                config = cfg,
                                syncStatus = latestSyncStatus,
                                torState = latestTorState,
                                isMediaActive = latestMediaActive,
                                mediaTitle = latestMediaTitle,
                                isMediaPausedSession = latestMediaSessionPaused,
                                mediaSessionToken = mediaSessionHandler?.sessionToken,
                            ),
                        )
                    }
                }
            }
        }

        if (monitoringJob != null) return

        val prefsRepo = PrivacyPreferencesRepository(applicationContext)
        val db = OnionShieldDatabase.getInstance(applicationContext)
        val roundDao = db.sessionRoundLogDao()

        val activeSyncService = runCatching {
            SessionMonitorSyncService(
                repository = SessionMonitorFirestoreRepository(applicationContext),
                auth = Firebase.auth,
                scope = scope,
            )
        }.getOrNull().also { syncService = it }

        val statsFlow = combine(
            roundDao.observeAll(),
            prefsRepo.securityConfig,
            LiveSessionRuntime.elapsedMinutes,
        ) { rounds, cfg, elapsed ->
            cfg to SessionGuardEngine.computeSessionStats(
                rounds = rounds,
                guardEnabled = cfg.sessionGuardEnabled,
                elapsedMinutes = elapsed,
                timeLimitMinutes = cfg.sessionTimeLimitMinutes,
                budgetLimit = cfg.sessionBudgetLimit,
            )
        }

        activeSyncService?.startObserving(
            siteHostFlow = LiveSessionRuntime.activeSiteHost,
            configFlow = prefsRepo.securityConfig,
            statsFlow = combine(
                roundDao.observeAll(),
                prefsRepo.securityConfig,
                LiveSessionRuntime.elapsedMinutes,
            ) { rounds, cfg, elapsed ->
                SessionGuardEngine.computeSessionStats(
                    rounds = rounds,
                    guardEnabled = cfg.sessionGuardEnabled,
                    elapsedMinutes = elapsed,
                    timeLimitMinutes = cfg.sessionTimeLimitMinutes,
                    budgetLimit = cfg.sessionBudgetLimit,
                )
            },
            roundsFlow = roundDao.observeAll(),
        )

        if (activeSyncService != null && syncStatusForwardJob == null) {
            syncStatusForwardJob = scope.launch {
                activeSyncService.syncStatus.collectLatest { status ->
                    latestSyncStatus = status
                    LiveSessionRuntime.updateForegroundSyncStatus(status)
                    updateNotification(
                        buildNotification(
                            stats = latestStats,
                            config = latestConfig,
                            syncStatus = status,
                            torState = latestTorState,
                            isMediaActive = latestMediaActive,
                            mediaTitle = latestMediaTitle,
                            isMediaPausedSession = latestMediaSessionPaused,
                            mediaSessionToken = mediaSessionHandler?.sessionToken,
                        ),
                    )
                }
            }
        }

        if (mediaStateJob == null) {
            mediaStateJob = scope.launch {
                combine(
                    LiveSessionRuntime.isMediaPlaybackActive,
                    LiveSessionRuntime.hasMediaSession,
                    LiveSessionRuntime.activeMediaTitle,
                    LiveSessionRuntime.activeMediaArtist,
                    LiveSessionRuntime.activeMediaPositionMs,
                ) { active, hasSession, title, artist, posMs ->
                    MediaPlaybackSnapshot(
                        isPlaying = active,
                        hasActiveSession = hasSession,
                        title = title,
                        artist = artist,
                        positionMs = posMs,
                        durationMs = LiveSessionRuntime.activeMediaDurationMs.value,
                    )
                }.collectLatest { snap ->
                    latestMediaActive = snap.isPlaying
                    latestMediaTitle = snap.title
                    latestMediaSessionPaused = snap.hasActiveSession && !snap.isPlaying
                    if (snap.hasActiveSession) {
                        mediaSessionHandler?.updatePlayback(
                            title = snap.title,
                            artist = snap.artist,
                            isPlaying = snap.isPlaying,
                            positionMs = snap.positionMs,
                            durationMs = snap.durationMs,
                        )
                    }
                    updateNotification(
                        buildNotification(
                            stats = latestStats,
                            config = latestConfig,
                            syncStatus = latestSyncStatus,
                            torState = latestTorState,
                            isMediaActive = snap.isPlaying,
                            mediaTitle = snap.title,
                            isMediaPausedSession = latestMediaSessionPaused,
                            mediaSessionToken = mediaSessionHandler?.sessionToken,
                        ),
                    )
                }
            }
        }

        monitoringJob = scope.launch {
            statsFlow.collectLatest { (cfg, stats) ->
                latestConfig = cfg
                latestStats = stats
                updateNotification(
                    buildNotification(
                        stats = stats,
                        config = cfg,
                        syncStatus = latestSyncStatus,
                        torState = latestTorState,
                        isMediaActive = latestMediaActive,
                        mediaTitle = latestMediaTitle,
                        isMediaPausedSession = latestMediaSessionPaused,
                        mediaSessionToken = mediaSessionHandler?.sessionToken,
                    ),
                )
            }
        }
    }

    private suspend fun checkTorConnectivityOnce(cfg: SecurityConfig): TorProxyState {
        val (handshakeOk, latencyMs) = TorCircuitService.probeProxyHandshake(
            host = cfg.torProxyHost,
            port = cfg.torProxyPort,
            protocol = cfg.torProxyProtocol,
        )
        return if (handshakeOk && latencyMs != null) {
            TorProxyState.Connected(endpoint = cfg.torEndpoint, latencyMs = latencyMs)
        } else {
            TorProxyController.checkProxyReachability(
                host = cfg.torProxyHost,
                port = cfg.torProxyPort,
                protocol = cfg.torProxyProtocol,
            )
        }
    }

    private fun triggerNewTorCircuit() {
        val scope = serviceScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also {
            serviceScope = it
        }
        scope.launch {
            val cfg = latestConfig
            TorCircuitService.requestNewCircuit(
                host = cfg.torProxyHost,
                controlPort = cfg.torControlPort,
                password = cfg.torControlPassword,
            )
            val refreshed = checkTorConnectivityOnce(cfg)
            latestTorState = refreshed
            LiveSessionRuntime.updateTorProxyState(refreshed)
            updateNotification(
                buildNotification(
                    stats = latestStats,
                    config = cfg,
                    syncStatus = latestSyncStatus,
                    torState = refreshed,
                    isMediaActive = latestMediaActive,
                    mediaTitle = latestMediaTitle,
                    isMediaPausedSession = latestMediaSessionPaused,
                    mediaSessionToken = mediaSessionHandler?.sessionToken,
                ),
            )
        }
    }

    private fun stopAndClearPreference() {
        LiveSessionRuntime.setForegroundRunning(false)
        mediaSessionHandler?.release()
        mediaSessionHandler = null
        releaseWakeAndWifiLocks()
        tickerJob?.cancel()
        tickerJob = null
        torKeepAliveJob?.cancel()
        torKeepAliveJob = null
        mediaStateJob?.cancel()
        mediaStateJob = null
        monitoringJob?.cancel()
        monitoringJob = null
        syncStatusForwardJob?.cancel()
        syncStatusForwardJob = null
        syncService?.stop()
        syncService = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

        val appContext = applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                PrivacyPreferencesRepository(appContext).setBackgroundLiveService(false)
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        LiveSessionRuntime.setForegroundRunning(false)
        mediaSessionHandler?.release()
        mediaSessionHandler = null
        releaseWakeAndWifiLocks()
        tickerJob?.cancel()
        torKeepAliveJob?.cancel()
        mediaStateJob?.cancel()
        monitoringJob?.cancel()
        syncStatusForwardJob?.cancel()
        syncService?.stop()
        serviceScope?.cancel()
        serviceScope = null
        super.onDestroy()
    }

    internal fun acquireWakeAndWifiLocks() {
        synchronized(wakeLockGuard) {
            runCatching {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (powerManager != null) {
                    val lock = partialWakeLock ?: powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        PARTIAL_WAKE_LOCK_TAG,
                    ).apply {
                        setReferenceCounted(false)
                    }.also { partialWakeLock = it }
                    if (!lock.isHeld) {
                        lock.acquire()
                    }
                }
            }.onFailure { err ->
                Log.w(TAG, "Unable to acquire PartialWakeLock", err)
            }

            runCatching {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifiManager != null) {
                    val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        @Suppress("DEPRECATION")
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    }
                    val lock = wifiLock ?: wifiManager.createWifiLock(wifiMode, WIFI_LOCK_TAG).apply {
                        setReferenceCounted(false)
                    }.also { wifiLock = it }
                    if (!lock.isHeld) {
                        lock.acquire()
                    }
                }
            }.onFailure { err ->
                Log.w(TAG, "Unable to acquire WifiLock", err)
            }

            LiveSessionRuntime.setPowerLocksHeld(
                (partialWakeLock?.isHeld == true) || (wifiLock?.isHeld == true),
            )
        }
    }

    internal fun releaseWakeAndWifiLocks() {
        synchronized(wakeLockGuard) {
            runCatching {
                if (partialWakeLock?.isHeld == true) {
                    partialWakeLock?.release()
                }
            }.onFailure { err ->
                Log.w(TAG, "Failed releasing PartialWakeLock", err)
            }
            runCatching {
                if (wifiLock?.isHeld == true) {
                    wifiLock?.release()
                }
            }.onFailure { err ->
                Log.w(TAG, "Failed releasing WifiLock", err)
            }
            partialWakeLock = null
            wifiLock = null
            LiveSessionRuntime.setPowerLocksHeld(false)
        }
    }

    internal fun isPartialWakeLockHeld(): Boolean = synchronized(wakeLockGuard) {
        partialWakeLock?.isHeld == true
    }

    internal fun isWifiLockHeld(): Boolean = synchronized(wakeLockGuard) {
        wifiLock?.isHeld == true
    }

    private fun promoteToForeground(notification: Notification) {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun updateNotification(notification: Notification) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.live_monitor_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.live_monitor_channel_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    internal fun buildNotification(
        stats: SessionGuardStats,
        config: SecurityConfig,
        syncStatus: SessionMonitorSyncStatus,
        torState: TorProxyState = TorProxyState.Inactive,
        isMediaActive: Boolean = false,
        mediaTitle: String = "",
        isMediaPausedSession: Boolean = false,
        mediaSessionToken: MediaSession.Token? = null,
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, LiveMonitorForegroundService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val isTorMode = config.browserMode == BrowserMode.TOR_ONION
        val torSummary = if (isTorMode) {
            when (torState) {
                is TorProxyState.Connected -> "Tor Connected (${torState.latencyMs}ms)"
                is TorProxyState.Unreachable -> "Tor Unreachable"
                is TorProxyState.Checking -> "Tor Checking..."
                is TorProxyState.UnsupportedApi -> "Proxy Unsupported"
                TorProxyState.Inactive -> "Tor Standby"
            }
        } else {
            "Surface Shield Active"
        }

        val cloudLabel = when {
            !config.liveCloudMonitorSyncEnabled -> "Local"
            syncStatus is SessionMonitorSyncStatus.SyncingLive -> "Cloud Live"
            syncStatus is SessionMonitorSyncStatus.SignInRequired -> "Sign-In"
            syncStatus is SessionMonitorSyncStatus.Error -> "Cached"
            else -> "Sync Ready"
        }

        val showMediaPresentation = isMediaActive || isMediaPausedSession
        val summaryLine = buildString {
            if (showMediaPresentation) {
                val trackLabel = mediaTitle.ifBlank { "Background Media Audio" }
                append("♪ $trackLabel • ")
            }
            append(torSummary)
            append(" • ${stats.elapsedMinutes}m active")
            if (stats.totalRounds > 0) {
                append(" • ${stats.totalRounds} rnds")
            }
            append(" • $cloudLabel")
        }

        val titleRes = when {
            showMediaPresentation -> R.string.live_monitor_notif_media_title
            isTorMode -> R.string.live_monitor_notif_tor_title
            else -> R.string.live_monitor_notif_title
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(R.drawable.ic_shield_notify)
            .setContentTitle(getString(titleRes))
            .setContentText(summaryLine)
            .setOngoing(isMediaActive || !isMediaPausedSession)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(
                if (showMediaPresentation) Notification.CATEGORY_TRANSPORT else Notification.CATEGORY_SERVICE,
            )
            .setContentIntent(openPendingIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.live_monitor_stop_action),
                    stopPendingIntent,
                ).build(),
            )

        if (isMediaActive) {
            val pauseMediaIntent = Intent(this, LiveMonitorForegroundService::class.java)
                .setAction(ACTION_PAUSE_MEDIA)
            val pauseMediaPendingIntent = PendingIntent.getService(
                this,
                REQUEST_CODE_PAUSE_MEDIA,
                pauseMediaIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.live_monitor_pause_media_action),
                    pauseMediaPendingIntent,
                ).build(),
            )
        } else if (isMediaPausedSession) {
            val resumeMediaIntent = Intent(this, LiveMonitorForegroundService::class.java)
                .setAction(ACTION_RESUME_MEDIA)
            val resumeMediaPendingIntent = PendingIntent.getService(
                this,
                REQUEST_CODE_RESUME_MEDIA,
                resumeMediaIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.live_monitor_resume_media_action),
                    resumeMediaPendingIntent,
                ).build(),
            )
        }

        if (isTorMode) {
            val newCircuitIntent = Intent(this, LiveMonitorForegroundService::class.java)
                .setAction(ACTION_NEW_CIRCUIT)
            val newCircuitPendingIntent = PendingIntent.getService(
                this,
                REQUEST_CODE_NEW_CIRCUIT,
                newCircuitIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.live_monitor_new_circuit_action),
                    newCircuitPendingIntent,
                ).build(),
            )
        }

        if (showMediaPresentation && mediaSessionToken != null) {
            builder.setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(summaryLine))
        }

        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "onion_shield_live_monitor"
        const val NOTIFICATION_ID = 4109
        const val ACTION_START = "com.example.action.START_LIVE_MONITOR"
        const val ACTION_STOP = "com.example.action.STOP_LIVE_MONITOR"
        const val ACTION_NEW_CIRCUIT = "com.example.action.NEW_TOR_CIRCUIT"
        const val ACTION_PAUSE_MEDIA = "com.example.action.PAUSE_MEDIA"
        const val ACTION_RESUME_MEDIA = "com.example.action.RESUME_MEDIA"
        const val CMD_PAUSE_MEDIA = "PAUSE_MEDIA"
        const val CMD_PLAY_MEDIA = "PLAY_MEDIA"
        const val CMD_STOP_MEDIA = "STOP_MEDIA"
        const val CMD_SEEK_PREFIX = "SEEK_MEDIA:"
        const val CMD_NEXT_TRACK = "NEXT_TRACK"
        const val CMD_PREV_TRACK = "PREV_TRACK"
        private const val TAG = "LiveMonitorFGS"
        private const val PARTIAL_WAKE_LOCK_TAG = "OnionShield:MediaStreamWakeLock"
        private const val WIFI_LOCK_TAG = "OnionShield:MediaStreamWifiLock"
        private const val REQUEST_CODE_OPEN = 100
        private const val REQUEST_CODE_STOP = 101
        private const val REQUEST_CODE_NEW_CIRCUIT = 102
        private const val REQUEST_CODE_PAUSE_MEDIA = 103
        private const val REQUEST_CODE_RESUME_MEDIA = 104

        fun start(context: Context) {
            val intent = Intent(context, LiveMonitorForegroundService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveMonitorForegroundService::class.java))
        }
    }
}

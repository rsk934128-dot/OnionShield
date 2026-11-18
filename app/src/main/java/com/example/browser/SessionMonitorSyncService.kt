package com.example.browser

import com.example.data.SecurityConfig
import com.example.data.SessionRoundLogEntity
import com.example.data.repository.AuthContextProvider
import com.example.data.repository.FirebaseAuthContextProvider
import com.example.data.repository.SessionMonitorFirestoreRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

sealed interface SessionMonitorSyncStatus {
    data object Disabled : SessionMonitorSyncStatus
    data object SignInRequired : SessionMonitorSyncStatus
    data class SyncingLive(val lastSyncedEpochMs: Long?) : SessionMonitorSyncStatus
    data class Error(val message: String) : SessionMonitorSyncStatus
}

private data class LiveSyncPayload(
    val userId: String?,
    val siteHost: String,
    val config: SecurityConfig,
    val stats: SessionGuardStats,
    val rounds: List<SessionRoundLogEntity>,
    val isPaused: Boolean = false,
)

/**
 * Real-time monitoring service that streams sanitized session statistics,
 * time/budget guard status, and completed round logs to Firebase Firestore
 * whenever live sync is enabled and the user is authenticated with Google Sign-In.
 */
class SessionMonitorSyncService(
    private val repository: SessionMonitorFirestoreRepository,
    private val auth: FirebaseAuth = Firebase.auth,
    private val scope: CoroutineScope,
    private val authContextProvider: AuthContextProvider = FirebaseAuthContextProvider(auth),
) {
    constructor(
        repository: SessionMonitorFirestoreRepository,
        authContextProvider: AuthContextProvider,
        scope: CoroutineScope,
    ) : this(
        repository = repository,
        auth = Firebase.auth,
        scope = scope,
        authContextProvider = authContextProvider,
    )

    private val _syncStatus = MutableStateFlow<SessionMonitorSyncStatus>(SessionMonitorSyncStatus.Disabled)
    val syncStatus: StateFlow<SessionMonitorSyncStatus> = _syncStatus.asStateFlow()

    private val syncedRoundKeys = mutableSetOf<String>()
    private var monitorJob: Job? = null

    private fun authUserIdFlow(): Flow<String?> = authContextProvider.observeUserId()

    @OptIn(FlowPreview::class)
    fun startObserving(
        siteHostFlow: Flow<String>,
        configFlow: Flow<SecurityConfig>,
        statsFlow: Flow<SessionGuardStats>,
        roundsFlow: Flow<List<SessionRoundLogEntity>>,
        isPausedFlow: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false),
    ) {
        if (monitorJob != null) return

        monitorJob = scope.launch {
            combine(
                authUserIdFlow(),
                siteHostFlow,
                configFlow,
                statsFlow,
                roundsFlow,
                isPausedFlow,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                LiveSyncPayload(
                    userId = values[0] as String?,
                    siteHost = values[1] as String,
                    config = values[2] as SecurityConfig,
                    stats = values[3] as SessionGuardStats,
                    rounds = values[4] as List<SessionRoundLogEntity>,
                    isPaused = values[5] as Boolean,
                )
            }
                .debounce(650L)
                .collectLatest { payload ->
                    if (payload.isPaused) {
                        return@collectLatest
                    }
                    if (!payload.config.liveCloudMonitorSyncEnabled) {
                        _syncStatus.value = SessionMonitorSyncStatus.Disabled
                        return@collectLatest
                    }
                    if (payload.userId == null) {
                        _syncStatus.value = SessionMonitorSyncStatus.SignInRequired
                        return@collectLatest
                    }

                    val publishRes = repository.publishMonitorSnapshot(
                        siteHost = payload.siteHost,
                        config = payload.config,
                        stats = payload.stats,
                        isMonitoringActive = true,
                    )

                    if (publishRes.isFailure) {
                        _syncStatus.value = SessionMonitorSyncStatus.Error(
                            publishRes.exceptionOrNull()?.localizedMessage ?: "Failed to sync monitor state",
                        )
                        return@collectLatest
                    }

                    // Sync recent completed rounds that haven't been pushed this session
                    val recentRounds = payload.rounds.take(50)
                    for (round in recentRounds) {
                        val roundKey = "${payload.userId}:${SessionMonitorFirestoreRepository.deterministicRoundId(round)}"
                        if (!syncedRoundKeys.contains(roundKey)) {
                            val roundRes = repository.upsertRemoteRound(round)
                            if (roundRes.isSuccess) {
                                syncedRoundKeys.add(roundKey)
                            }
                        }
                    }

                    _syncStatus.value = SessionMonitorSyncStatus.SyncingLive(System.currentTimeMillis())
                }
        }
    }

    suspend fun deleteRemoteRoundForLocal(round: SessionRoundLogEntity) {
        if (authContextProvider.currentUser == null) return
        val roundId = SessionMonitorFirestoreRepository.deterministicRoundId(round)
        repository.deleteRemoteRound(roundId)
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }
}

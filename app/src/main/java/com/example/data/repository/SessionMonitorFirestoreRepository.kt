package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.browser.SessionGuardStats
import com.example.data.SecurityConfig
import com.example.data.SessionRoundLogEntity
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.snapshots
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

@IgnoreExtraProperties
data class SessionMonitorState(
    @DocumentId val id: String = "",
    val userId: String = "",
    val siteHost: String = "manual",
    val guardEnabled: Boolean = false,
    val alertLevel: String = "OFF",
    val elapsedMinutes: Long = 0L,
    val timeLimitMinutes: Int = 30,
    val budgetLimit: Double = 100.0,
    val currencyLabel: String = "BDT",
    val totalRounds: Int = 0,
    val winsCount: Int = 0,
    val totalWagered: Double = 0.0,
    val totalReturned: Double = 0.0,
    val netProfit: Double = 0.0,
    val averageCrashMultiplier: Double = 0.0,
    val maxCrashMultiplier: Double = 0.0,
    val isMonitoringActive: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class RemoteSessionRound(
    @DocumentId val id: String = "",
    val userId: String = "",
    val siteHost: String = "manual",
    val betAmount: Double = 0.0,
    val crashMultiplier: Double = 1.0,
    val cashoutMultiplier: Double = 0.0,
    val netProfit: Double = 0.0,
    val note: String = "",
    val clientTimestampMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

class SessionMonitorFirestoreRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
    private val authContextProvider: AuthContextProvider = FirebaseAuthContextProvider(auth),
) {
    constructor(
        db: FirebaseFirestore,
        authContextProvider: AuthContextProvider,
    ) : this(
        db = db,
        auth = Firebase.auth,
        authContextProvider = authContextProvider,
    )

    constructor(context: Context) : this(
        db = FirestoreClientProvider.getInstance(context),
        auth = Firebase.auth,
    )

    private fun requireUserId(): String {
        return authContextProvider.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before syncing session monitor.")
    }

    fun observeActiveMonitor(
        userId: String,
        monitorId: String = DEFAULT_MONITOR_ID,
    ): Flow<SessionMonitorState?> = flow {
        val docPath = "users/$userId/session_monitors/$monitorId"
        emitAll(
            db.collection("users")
                .document(userId)
                .collection("session_monitors")
                .document(monitorId)
                .snapshots()
                .map { snapshot ->
                    if (!snapshot.exists()) null
                    else snapshot.toObject(
                        SessionMonitorState::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && authContextProvider.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, docPath, auth)
                    }
                    throw error
                },
        )
    }

    fun observeRemoteRounds(userId: String): Flow<List<RemoteSessionRound>> = flow {
        val collectionPath = "users/$userId/session_rounds"
        emitAll(
            db.collection("users")
                .document(userId)
                .collection("session_rounds")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        RemoteSessionRound::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                    ).sortedByDescending { it.clientTimestampMs }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && authContextProvider.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, collectionPath, auth)
                    }
                    throw error
                },
        )
    }

    suspend fun publishMonitorSnapshot(
        siteHost: String,
        config: SecurityConfig,
        stats: SessionGuardStats,
        isMonitoringActive: Boolean,
        monitorId: String = DEFAULT_MONITOR_ID,
    ): Result<Unit> {
        val uid = runCatching { requireUserId() }.getOrElse { return Result.failure(it) }
        val docRef = db.collection("users")
            .document(uid)
            .collection("session_monitors")
            .document(monitorId)

        val cleanHost = sanitizeSiteHost(siteHost)
        val cleanCurrency = config.sessionCurrencyLabel.trim().uppercase().take(10).ifBlank { "BDT" }
        val safeWins = stats.winsCount.coerceIn(0, stats.totalRounds.coerceAtLeast(0))
        val updatableFields = mapOf(
            "siteHost" to cleanHost,
            "guardEnabled" to config.sessionGuardEnabled,
            "alertLevel" to stats.overallAlertLevel.name,
            "elapsedMinutes" to stats.elapsedMinutes.coerceIn(0L, 100_000L),
            "timeLimitMinutes" to config.sessionTimeLimitMinutes.coerceIn(1, 1440),
            "budgetLimit" to max(0.0, config.sessionBudgetLimit),
            "currencyLabel" to cleanCurrency,
            "totalRounds" to max(0, stats.totalRounds),
            "winsCount" to safeWins,
            "totalWagered" to max(0.0, stats.totalWagered),
            "totalReturned" to max(0.0, stats.totalReturned),
            "netProfit" to stats.netProfit.coerceIn(-1_000_000_000.0, 1_000_000_000.0),
            "averageCrashMultiplier" to max(0.0, stats.averageCrashMultiplier),
            "maxCrashMultiplier" to max(0.0, stats.maxCrashMultiplier),
            "isMonitoringActive" to isMonitoringActive,
            "updatedAt" to FieldValue.serverTimestamp(),
        )

        return try {
            val existing = withTimeoutOrNull(OFFLINE_CHECK_TIMEOUT_MS) {
                runCatching { docRef.get().await() }.getOrNull()
            }
            if (existing != null && existing.exists()) {
                withTimeoutOrNull(OFFLINE_WRITE_ACK_TIMEOUT_MS) {
                    docRef.update(updatableFields).await()
                }
            } else {
                val createFields = updatableFields + mapOf(
                    "userId" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                )
                withTimeoutOrNull(OFFLINE_WRITE_ACK_TIMEOUT_MS) {
                    docRef.set(createFields).await()
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun upsertRemoteRound(round: SessionRoundLogEntity): Result<String> {
        val uid = runCatching { requireUserId() }.getOrElse { return Result.failure(it) }
        val roundId = deterministicRoundId(round)
        val docRef = db.collection("users")
            .document(uid)
            .collection("session_rounds")
            .document(roundId)

        val updatableFields = mapOf(
            "siteHost" to sanitizeSiteHost(round.siteHost),
            "betAmount" to max(0.0, round.betAmount),
            "crashMultiplier" to max(1.0, round.crashMultiplier),
            "cashoutMultiplier" to max(0.0, round.cashoutMultiplier ?: 0.0),
            "netProfit" to round.netProfit.coerceIn(-1_000_000_000.0, 1_000_000_000.0),
            "note" to round.note.trim().take(120),
            "clientTimestampMs" to max(0L, round.createdAt),
            "updatedAt" to FieldValue.serverTimestamp(),
        )

        return try {
            val existing = withTimeoutOrNull(OFFLINE_CHECK_TIMEOUT_MS) {
                runCatching { docRef.get().await() }.getOrNull()
            }
            if (existing != null && existing.exists()) {
                withTimeoutOrNull(OFFLINE_WRITE_ACK_TIMEOUT_MS) {
                    docRef.update(updatableFields).await()
                }
            } else {
                val createFields = updatableFields + mapOf(
                    "userId" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                )
                withTimeoutOrNull(OFFLINE_WRITE_ACK_TIMEOUT_MS) {
                    docRef.set(createFields).await()
                }
            }
            Result.success(roundId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun deleteRemoteRound(roundId: String): Result<Unit> {
        val uid = runCatching { requireUserId() }.getOrElse { return Result.failure(it) }
        val docRef = db.collection("users")
            .document(uid)
            .collection("session_rounds")
            .document(roundId)

        return try {
            withTimeoutOrNull(OFFLINE_WRITE_ACK_TIMEOUT_MS) {
                docRef.delete().await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path, auth)
            Result.failure(e)
        }
    }

    companion object {
        private const val OFFLINE_CHECK_TIMEOUT_MS = 1200L
        private const val OFFLINE_WRITE_ACK_TIMEOUT_MS = 1800L
        const val DEFAULT_MONITOR_ID = "active_session"

        fun sanitizeSiteHost(rawHost: String): String {
            val clean = rawHost.trim().lowercase().take(120)
            if (clean.isBlank()) return "manual"
            if (clean.contains(".onion")) return "onion_redacted"
            return clean
        }

        fun deterministicRoundId(round: SessionRoundLogEntity): String {
            val raw = "${round.createdAt}|${round.betAmount}|${round.crashMultiplier}|${round.cashoutMultiplier ?: 0.0}|${round.siteHost}"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            val hex = digest.take(14).joinToString("") { "%02x".format(it) }
            return "rnd_$hex"
        }
    }
}

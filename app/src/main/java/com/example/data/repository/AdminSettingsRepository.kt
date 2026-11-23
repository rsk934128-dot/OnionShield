package com.example.data.repository

import android.content.Context
import androidx.annotation.Keep
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
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.snapshots
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Keep
@IgnoreExtraProperties
data class AdminServerConfig(
    @DocumentId val id: String = DEFAULT_CONFIG_ID,
    val maintenanceMode: Boolean = false,
    val strictTorRequired: Boolean = false,
    val remoteTelemetryEnabled: Boolean = true,
    val forceAdBlockSync: Boolean = true,
    val emergencyLockdown: Boolean = false,
    val maintenanceBanner: String = "",
    val updatedByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    companion object {
        const val DEFAULT_CONFIG_ID = "global_settings"
    }
}

@Keep
@IgnoreExtraProperties
data class MaintenanceLogEntry(
    @DocumentId val id: String = "",
    val action: String = "",
    val category: String = CATEGORY_CONFIG,
    val severity: String = SEVERITY_INFO,
    val details: String = "",
    val actorUid: String = "",
    val actorEmail: String = "",
    val clientTimestampMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    companion object {
        const val CATEGORY_CONFIG = "CONFIG"
        const val CATEGORY_SECURITY = "SECURITY"
        const val CATEGORY_TOR_NETWORK = "TOR_NETWORK"
        const val CATEGORY_MAINTENANCE = "MAINTENANCE"
        const val CATEGORY_AUDIT = "AUDIT"

        val VALID_CATEGORIES = listOf(
            CATEGORY_CONFIG,
            CATEGORY_SECURITY,
            CATEGORY_TOR_NETWORK,
            CATEGORY_MAINTENANCE,
            CATEGORY_AUDIT,
        )

        const val SEVERITY_INFO = "INFO"
        const val SEVERITY_WARN = "WARN"
        const val SEVERITY_CRITICAL = "CRITICAL"

        val VALID_SEVERITIES = listOf(
            SEVERITY_INFO,
            SEVERITY_WARN,
            SEVERITY_CRITICAL,
        )
    }
}

data class AdminServerConfigSnapshot(
    val config: AdminServerConfig = AdminServerConfig(),
    val existsRemotely: Boolean = false,
    val isFromCache: Boolean = false,
    val hasPendingWrites: Boolean = false,
)

data class MaintenanceLogsSnapshot(
    val entries: List<MaintenanceLogEntry> = emptyList(),
    val isFromCache: Boolean = false,
    val hasPendingWrites: Boolean = false,
)

interface AdminSettingsDataSource {
    suspend fun hasTrustedAdminDocument(uid: String): Boolean
    fun observeServerConfig(
        configId: String = AdminServerConfig.DEFAULT_CONFIG_ID,
    ): Flow<AdminServerConfigSnapshot>
    fun observeMaintenanceLogs(limitCount: Long = 50L): Flow<MaintenanceLogsSnapshot>
    suspend fun saveServerConfig(
        config: AdminServerConfig,
        auditAction: String? = null,
        auditCategory: String = MaintenanceLogEntry.CATEGORY_CONFIG,
        auditSeverity: String = MaintenanceLogEntry.SEVERITY_INFO,
        auditDetails: String = "",
    ): Result<Unit>
    suspend fun appendMaintenanceLog(
        action: String,
        category: String = MaintenanceLogEntry.CATEGORY_MAINTENANCE,
        severity: String = MaintenanceLogEntry.SEVERITY_INFO,
        details: String = "",
    ): Result<String>
    suspend fun deleteMaintenanceLog(logId: String): Result<Unit>
}

class AdminSettingsRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
    private val authContextProvider: AuthContextProvider = FirebaseAuthContextProvider(auth),
) : AdminSettingsDataSource {
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

    private fun requireAuthenticatedUser(): AuthenticatedUserContext {
        return authContextProvider.currentUser
            ?: throw IllegalStateException("Admin authentication is required before accessing server settings.")
    }

    override suspend fun hasTrustedAdminDocument(uid: String): Boolean = withContext(Dispatchers.IO) {
        if (uid.isBlank()) return@withContext false
        val docRef = db.collection("admins").document(uid)
        val cached = runCatching { docRef.get(Source.CACHE).await() }.getOrNull()
        if (cached?.exists() == true) return@withContext true
        val remote = withTimeoutOrNull(OFFLINE_CHECK_TIMEOUT_MS) {
            runCatching { docRef.get(Source.DEFAULT).await() }.getOrNull()
        }
        remote?.exists() == true
    }

    override fun observeServerConfig(
        configId: String,
    ): Flow<AdminServerConfigSnapshot> = flow {
        val docPath = "admin_config/$configId"
        emitAll(
            db.collection("admin_config")
                .document(configId)
                .snapshots(MetadataChanges.INCLUDE)
                .map { snapshot ->
                    val parsed = if (snapshot.exists()) {
                        snapshot.toObject(
                            AdminServerConfig::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                        ) ?: AdminServerConfig(id = configId)
                    } else {
                        AdminServerConfig(id = configId)
                    }
                    AdminServerConfigSnapshot(
                        config = parsed,
                        existsRemotely = snapshot.exists(),
                        isFromCache = snapshot.metadata.isFromCache,
                        hasPendingWrites = snapshot.metadata.hasPendingWrites(),
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, docPath, auth)
                    }
                    throw error
                },
        )
    }

    override fun observeMaintenanceLogs(limitCount: Long): Flow<MaintenanceLogsSnapshot> = flow {
        val collectionPath = "maintenance_logs"
        emitAll(
            db.collection(collectionPath)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limitCount.coerceIn(1L, 200L))
                .snapshots(MetadataChanges.INCLUDE)
                .map { snapshot ->
                    val items = snapshot.toObjects(
                        MaintenanceLogEntry::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                    ).sortedByDescending { entry ->
                        entry.createdAt?.toDate()?.time ?: entry.clientTimestampMs
                    }
                    MaintenanceLogsSnapshot(
                        entries = items,
                        isFromCache = snapshot.metadata.isFromCache,
                        hasPendingWrites = snapshot.metadata.hasPendingWrites(),
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, collectionPath, auth)
                    }
                    throw error
                },
        )
    }

    override suspend fun saveServerConfig(
        config: AdminServerConfig,
        auditAction: String?,
        auditCategory: String,
        auditSeverity: String,
        auditDetails: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val user = runCatching { requireAuthenticatedUser() }.getOrElse { return@withContext Result.failure(it) }
        val configId = sanitizeDocId(config.id).ifBlank { AdminServerConfig.DEFAULT_CONFIG_ID }
        val docRef = db.collection("admin_config").document(configId)
        val cleanBanner = config.maintenanceBanner.trim().take(MAX_BANNER_LENGTH)

        val updatableFields = mapOf(
            "maintenanceMode" to config.maintenanceMode,
            "strictTorRequired" to config.strictTorRequired,
            "remoteTelemetryEnabled" to config.remoteTelemetryEnabled,
            "forceAdBlockSync" to config.forceAdBlockSync,
            "emergencyLockdown" to config.emergencyLockdown,
            "maintenanceBanner" to cleanBanner,
            "updatedByUid" to user.uid,
            "updatedAt" to FieldValue.serverTimestamp(),
        )

        try {
            val cachedSnap = runCatching { docRef.get(Source.CACHE).await() }.getOrNull()
            val existing = if (cachedSnap != null && cachedSnap.exists()) {
                cachedSnap
            } else {
                withTimeoutOrNull(OFFLINE_CHECK_TIMEOUT_MS) {
                    runCatching { docRef.get(Source.DEFAULT).await() }.getOrNull()
                }
            }

            if (existing != null && existing.exists()) {
                withTimeoutOrNull(OFFLINE_WRITE_ACK_TIMEOUT_MS) {
                    docRef.update(updatableFields).await()
                }
            } else {
                val createFields = updatableFields + mapOf(
                    "createdAt" to FieldValue.serverTimestamp(),
                )
                withTimeoutOrNull(OFFLINE_WRITE_ACK_TIMEOUT_MS) {
                    docRef.set(createFields).await()
                }
            }

            if (!auditAction.isNullOrBlank()) {
                appendMaintenanceLog(
                    action = auditAction,
                    category = auditCategory,
                    severity = auditSeverity,
                    details = auditDetails,
                )
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            Result.failure(e)
        }
    }

    override suspend fun appendMaintenanceLog(
        action: String,
        category: String,
        severity: String,
        details: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        val user = runCatching { requireAuthenticatedUser() }.getOrElse { return@withContext Result.failure(it) }
        val cleanAction = action.trim().take(MAX_ACTION_LENGTH)
        if (cleanAction.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Maintenance log action cannot be blank."))
        }
        val cleanCategory = category.trim().uppercase().takeIf {
            it in MaintenanceLogEntry.VALID_CATEGORIES
        } ?: MaintenanceLogEntry.CATEGORY_MAINTENANCE
        val cleanSeverity = severity.trim().uppercase().takeIf {
            it in MaintenanceLogEntry.VALID_SEVERITIES
        } ?: MaintenanceLogEntry.SEVERITY_INFO
        val cleanDetails = details.trim().take(MAX_DETAILS_LENGTH)
        val cleanActorEmail = user.email.orEmpty().trim().take(160)
        val nowMs = System.currentTimeMillis()
        val logId = "log_${nowMs}_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val docRef = db.collection("maintenance_logs").document(logId)

        val payload = mapOf(
            "action" to cleanAction,
            "category" to cleanCategory,
            "severity" to cleanSeverity,
            "details" to cleanDetails,
            "actorUid" to user.uid,
            "actorEmail" to cleanActorEmail,
            "clientTimestampMs" to nowMs,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )

        try {
            withTimeoutOrNull(OFFLINE_WRITE_ACK_TIMEOUT_MS) {
                docRef.set(payload).await()
            }
            Result.success(logId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path, auth)
            Result.failure(e)
        }
    }

    override suspend fun deleteMaintenanceLog(logId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { requireAuthenticatedUser() }.getOrElse { return@withContext Result.failure(it) }
        val cleanId = sanitizeDocId(logId)
        if (cleanId.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Invalid maintenance log ID."))
        }
        val docRef = db.collection("maintenance_logs").document(cleanId)
        try {
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
        const val MAX_BANNER_LENGTH = 240
        const val MAX_ACTION_LENGTH = 120
        const val MAX_DETAILS_LENGTH = 500
        const val BOOTSTRAPPED_ADMIN_EMAIL = "monikhoku88@gmail.com"

        fun isBootstrappedAdmin(user: AuthenticatedUserContext?): Boolean {
            return user?.isEmailVerified == true &&
                user.email.equals(BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)
        }

        private fun sanitizeDocId(raw: String): String {
            return raw.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "").take(128)
        }
    }
}

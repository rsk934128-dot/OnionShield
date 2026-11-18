package com.example.data.repository

import android.content.Context
import android.util.Log
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
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.Source
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
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(
    exception: Throwable,
    operationType: OperationType,
    path: String?,
    auth: FirebaseAuth = Firebase.auth,
): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val currentUser = runCatching { auth.currentUser }.getOrNull()
    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

@IgnoreExtraProperties
data class SyncedLink(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val url: String = "",
    val kind: String = KIND_BOOKMARK,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    companion object {
        const val KIND_BOOKMARK = "bookmark"
        const val KIND_HOME_APP = "home_app"
        const val KIND_HISTORY = "history"
    }
}

data class SyncedLinksSnapshot(
    val items: List<SyncedLink> = emptyList(),
    val isFromCache: Boolean = false,
    val hasPendingWrites: Boolean = false,
)

class CloudSyncRepository(
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
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeSyncedLinksWithMetadata(userId: String): Flow<SyncedLinksSnapshot> = flow {
        val path = "users/$userId/synced_links"
        emitAll(
            db.collection("users").document(userId).collection("synced_links")
                .snapshots(MetadataChanges.INCLUDE)
                .map { snapshot ->
                    val items = snapshot.toObjects(
                        SyncedLink::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                    ).sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
                    SyncedLinksSnapshot(
                        items = items,
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
                    if (exceptionToLog != null && authContextProvider.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path, auth)
                    }
                    throw error
                },
        )
    }

    fun observeSyncedLinks(userId: String): Flow<List<SyncedLink>> =
        observeSyncedLinksWithMetadata(userId).map { it.items }

    suspend fun getSyncedLink(linkId: String): Result<SyncedLink?> {
        val uid = runCatching { requireUserId() }.getOrElse { return Result.failure(it) }
        val docRef = db.collection("users").document(uid).collection("synced_links").document(linkId)
        return try {
            val defaultSnap = withTimeoutOrNull(OFFLINE_CHECK_TIMEOUT_MS) {
                docRef.get(Source.DEFAULT).await()
            }
            val snap = defaultSnap ?: docRef.get(Source.CACHE).await()
            val item = snap.toObject(SyncedLink::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            Result.success(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun upsertSyncedLink(
        title: String,
        url: String,
        kind: String,
    ): Result<String> {
        val uid = runCatching { requireUserId() }.getOrElse { return Result.failure(it) }
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
            !cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            return Result.failure(IllegalArgumentException("Only http/https clearnet URLs can be cloud synced."))
        }
        val cleanTitle = title.trim().ifBlank { cleanUrl }.take(120)
        val cleanKind = when (kind) {
            SyncedLink.KIND_HOME_APP -> SyncedLink.KIND_HOME_APP
            SyncedLink.KIND_HISTORY -> SyncedLink.KIND_HISTORY
            else -> SyncedLink.KIND_BOOKMARK
        }
        val linkId = deterministicLinkId(cleanUrl, cleanKind)
        val docRef = db.collection("users").document(uid).collection("synced_links").document(linkId)

        return try {
            val cachedSnap = runCatching { docRef.get(Source.CACHE).await() }.getOrNull()
            val existing = if (cachedSnap != null && cachedSnap.exists()) {
                cachedSnap
            } else {
                withTimeoutOrNull(OFFLINE_CHECK_TIMEOUT_MS) {
                    runCatching { docRef.get(Source.DEFAULT).await() }.getOrNull()
                }
            }

            if (existing != null && existing.exists()) {
                val updatePayload = mapOf(
                    "title" to cleanTitle,
                    "url" to cleanUrl.take(1000),
                    "kind" to cleanKind,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                withTimeoutOrNull(OFFLINE_WRITE_ACK_TIMEOUT_MS) {
                    docRef.update(updatePayload).await()
                }
            } else {
                val createPayload = mapOf(
                    "userId" to uid,
                    "title" to cleanTitle,
                    "url" to cleanUrl.take(1000),
                    "kind" to cleanKind,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                withTimeoutOrNull(OFFLINE_WRITE_ACK_TIMEOUT_MS) {
                    docRef.set(createPayload).await()
                }
            }
            Result.success(linkId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun deleteSyncedLink(linkId: String): Result<Unit> {
        val uid = runCatching { requireUserId() }.getOrElse { return Result.failure(it) }
        val docRef = db.collection("users").document(uid).collection("synced_links").document(linkId)
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

        fun deterministicLinkId(url: String, kind: String = SyncedLink.KIND_BOOKMARK): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$kind|${url.trim()}".toByteArray(Charsets.UTF_8))
            val hex = digest.take(16).joinToString("") { "%02x".format(it) }
            return "lnk_$hex"
        }
    }
}

package com.example.base

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.browser.SessionMonitorSyncService
import com.example.data.repository.AdminClaimStatus
import com.example.data.repository.AdminSettingsRepository
import com.example.data.repository.AuthContextProvider
import com.example.data.repository.AuthenticatedUserContext
import com.example.data.repository.CloudSyncRepository
import com.example.data.repository.SessionMonitorFirestoreRepository
import com.example.data.repository.evaluateAdminClaims
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Controllable in-memory [AuthContextProvider] for local Robolectric and JVM unit tests.
 */
class FakeAuthContextProvider(
    initialUser: AuthenticatedUserContext? = null,
    initialClaims: Map<String, Any?> = emptyMap(),
) : AuthContextProvider {

    private val userFlow = MutableStateFlow(initialUser)
    private val claimsFlow = MutableStateFlow(initialClaims)

    override val currentUser: AuthenticatedUserContext?
        get() = userFlow.value

    override fun observeUserId(): Flow<String?> = userFlow.map { it?.uid }

    override suspend fun checkAdminClaims(forceRefresh: Boolean): AdminClaimStatus {
        val user = userFlow.value ?: return AdminClaimStatus.SignedOut
        val matched = evaluateAdminClaims(claimsFlow.value)
        return if (matched.isNotEmpty()) {
            AdminClaimStatus.Authorized(user = user, matchedClaims = matched)
        } else {
            AdminClaimStatus.Denied(
                user = user,
                reason = "Account lacks required admin user claim (admin=true or role=\"admin\").",
            )
        }
    }

    fun signIn(
        uid: String = DEFAULT_TEST_UID,
        email: String? = DEFAULT_TEST_EMAIL,
        isEmailVerified: Boolean = true,
        tenantId: String? = null,
        claims: Map<String, Any?> = emptyMap(),
    ): AuthenticatedUserContext {
        val user = AuthenticatedUserContext(
            uid = uid,
            email = email,
            isEmailVerified = isEmailVerified,
            tenantId = tenantId,
        )
        claimsFlow.value = claims
        userFlow.value = user
        return user
    }

    fun signIn(
        user: AuthenticatedUserContext,
        claims: Map<String, Any?> = emptyMap(),
    ): AuthenticatedUserContext {
        claimsFlow.value = claims
        userFlow.value = user
        return user
    }

    fun setClaims(claims: Map<String, Any?>) {
        claimsFlow.value = claims
    }

    fun signOut() {
        claimsFlow.value = emptyMap()
        userFlow.value = null
    }

    suspend fun <T> withAuthenticatedUser(
        uid: String = DEFAULT_TEST_UID,
        email: String? = DEFAULT_TEST_EMAIL,
        isEmailVerified: Boolean = true,
        claims: Map<String, Any?> = emptyMap(),
        block: suspend (AuthenticatedUserContext) -> T,
    ): T {
        val previousUser = userFlow.value
        val previousClaims = claimsFlow.value
        val active = signIn(
            uid = uid,
            email = email,
            isEmailVerified = isEmailVerified,
            claims = claims,
        )
        return try {
            block(active)
        } finally {
            claimsFlow.value = previousClaims
            userFlow.value = previousUser
        }
    }

    companion object {
        const val DEFAULT_TEST_UID = "test_uid_onionshield_1"
        const val DEFAULT_TEST_EMAIL = "test_user@onionshield.local"
    }
}

/**
 * JUnit [org.junit.rules.TestRule] that initializes an isolated Robolectric FirebaseApp + offline
 * MemoryCache Firestore instance and provides an authenticated user context via [authProvider],
 * allowing repositories that require authentication to pass in local Robolectric/unit tests without
 * starting external Firebase emulators.
 */
class AuthenticatedFirebaseTestRule(
    val defaultUid: String = FakeAuthContextProvider.DEFAULT_TEST_UID,
    val defaultEmail: String? = FakeAuthContextProvider.DEFAULT_TEST_EMAIL,
    val startSignedIn: Boolean = true,
    val disableFirestoreNetwork: Boolean = true,
    val defaultClaims: Map<String, Any?> = emptyMap(),
) : TestWatcher() {

    lateinit var firebaseApp: FirebaseApp
        private set

    lateinit var firestore: FirebaseFirestore
        private set

    lateinit var auth: FirebaseAuth
        private set

    val authProvider: FakeAuthContextProvider = FakeAuthContextProvider(
        initialUser = if (startSignedIn) {
            AuthenticatedUserContext(uid = defaultUid, email = defaultEmail)
        } else {
            null
        },
        initialClaims = if (startSignedIn) defaultClaims else emptyMap(),
    )

    val currentUser: AuthenticatedUserContext?
        get() = authProvider.currentUser

    val currentUid: String
        get() = checkNotNull(authProvider.currentUser?.uid) {
            "No authenticated test user is currently signed in."
        }

    override fun starting(description: Description) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resId = context.resources.getIdentifier("firestore_database_id", "string", context.packageName)
        val databaseId = if (resId != 0) {
            context.getString(resId)
        } else {
            "ai-studio-android-omnisafe-14a07df1-dd17-4347-b219-286220f8ad01"
        }

        val appName = "$TEST_APP_PREFIX-${description.className ?: "default"}"
        firebaseApp = FirebaseApp.getApps(context).firstOrNull { it.name == appName }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("com.aistudio.onionshield.kxrqzm")
                    .setProjectId("demo-robolectric-test")
                    .setApiKey("fake-api-key-for-robolectric-test")
                    .build(),
                appName,
            )

        firestore = FirebaseFirestore.getInstance(firebaseApp, databaseId).also { db ->
            runCatching {
                db.firestoreSettings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                    .build()
            }
            if (disableFirestoreNetwork) {
                db.disableNetwork()
            }
        }
        auth = FirebaseAuth.getInstance(firebaseApp)

        if (startSignedIn) {
            authProvider.signIn(uid = defaultUid, email = defaultEmail, claims = defaultClaims)
        } else {
            authProvider.signOut()
        }
    }

    override fun finished(description: Description) {
        authProvider.signOut()
    }

    fun signIn(
        uid: String = defaultUid,
        email: String? = defaultEmail,
        isEmailVerified: Boolean = true,
        claims: Map<String, Any?> = defaultClaims,
    ): AuthenticatedUserContext = authProvider.signIn(
        uid = uid,
        email = email,
        isEmailVerified = isEmailVerified,
        claims = claims,
    )

    fun signOut() {
        authProvider.signOut()
    }

    suspend fun <T> withAuthenticatedUser(
        uid: String = defaultUid,
        email: String? = defaultEmail,
        isEmailVerified: Boolean = true,
        claims: Map<String, Any?> = defaultClaims,
        block: suspend (AuthenticatedUserContext) -> T,
    ): T = authProvider.withAuthenticatedUser(
        uid = uid,
        email = email,
        isEmailVerified = isEmailVerified,
        claims = claims,
        block = block,
    )

    fun createCloudSyncRepository(): CloudSyncRepository = CloudSyncRepository(
        db = firestore,
        auth = auth,
        authContextProvider = authProvider,
    )

    fun createSessionMonitorRepository(): SessionMonitorFirestoreRepository = SessionMonitorFirestoreRepository(
        db = firestore,
        auth = auth,
        authContextProvider = authProvider,
    )

    fun createAdminSettingsRepository(): AdminSettingsRepository = AdminSettingsRepository(
        db = firestore,
        auth = auth,
        authContextProvider = authProvider,
    )

    fun createSessionMonitorSyncService(scope: CoroutineScope): SessionMonitorSyncService = SessionMonitorSyncService(
        repository = createSessionMonitorRepository(),
        auth = auth,
        scope = scope,
        authContextProvider = authProvider,
    )

    private companion object {
        const val TEST_APP_PREFIX = "robolectric-auth-test"
    }
}

/**
 * Helper factory returning a pre-authenticated [FakeAuthContextProvider] for unit tests.
 */
fun createAuthenticatedAuthProvider(
    uid: String = FakeAuthContextProvider.DEFAULT_TEST_UID,
    email: String? = FakeAuthContextProvider.DEFAULT_TEST_EMAIL,
    isEmailVerified: Boolean = true,
): FakeAuthContextProvider {
    return FakeAuthContextProvider(
        AuthenticatedUserContext(
            uid = uid,
            email = email,
            isEmailVerified = isEmailVerified,
        ),
    )
}

/**
 * Runs [block] under a scoped authenticated [AuthenticatedUserContext] and restores the prior auth state.
 */
suspend fun <T> withAuthenticatedUserContext(
    uid: String = FakeAuthContextProvider.DEFAULT_TEST_UID,
    email: String? = FakeAuthContextProvider.DEFAULT_TEST_EMAIL,
    authProvider: FakeAuthContextProvider = FakeAuthContextProvider(),
    block: suspend (FakeAuthContextProvider, AuthenticatedUserContext) -> T,
): T {
    return authProvider.withAuthenticatedUser(uid = uid, email = email) { user ->
        block(authProvider, user)
    }
}

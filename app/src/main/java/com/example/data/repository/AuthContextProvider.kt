package com.example.data.repository

import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Lightweight snapshot of the currently authenticated user required by Firestore repositories.
 */
data class AuthenticatedUserContext(
    val uid: String,
    val email: String? = null,
    val isEmailVerified: Boolean = true,
    val tenantId: String? = null,
)

/**
 * Result of checking Firebase ID token custom claims for admin privileges.
 */
sealed interface AdminClaimStatus {
    data object SignedOut : AdminClaimStatus
    data object Checking : AdminClaimStatus
    data class Authorized(
        val user: AuthenticatedUserContext,
        val matchedClaims: Set<String> = emptySet(),
        val viaTrustedRegistry: Boolean = false,
    ) : AdminClaimStatus

    data class Denied(
        val user: AuthenticatedUserContext,
        val reason: String,
    ) : AdminClaimStatus
}

fun hasCustomAdminClaim(claims: Map<String, Any?>): Boolean {
    val adminClaim = claims["admin"]
    return adminClaim == true || (adminClaim as? String)?.trim().equals("true", ignoreCase = true)
}

fun evaluateAdminClaims(claims: Map<String, Any?>): Set<String> {
    if (claims.isEmpty()) return emptySet()
    val matched = linkedSetOf<String>()
    if (hasCustomAdminClaim(claims)) {
        matched += "admin=true"
    }
    if (claims["isAdmin"] == true) {
        matched += "isAdmin=true"
    }
    val roleValue = (claims["role"] as? String)?.trim()
    if (roleValue.equals("admin", ignoreCase = true) ||
        roleValue.equals("super_admin", ignoreCase = true)
    ) {
        matched += "role=$roleValue"
    }
    val rolesList = claims["roles"] as? Iterable<*>
    if (rolesList?.any {
            val item = it?.toString()?.trim()
            item.equals("admin", ignoreCase = true) || item.equals("super_admin", ignoreCase = true)
        } == true
    ) {
        matched += "roles[admin]"
    }
    val permissionsList = claims["permissions"] as? Iterable<*>
    if (permissionsList?.any { it?.toString()?.trim().equals("admin_settings", ignoreCase = true) } == true) {
        matched += "permissions[admin_settings]"
    }
    return matched
}

/**
 * Provides the active authenticated user context, auth-state stream, and admin token-claim checks.
 * Defaults to [FirebaseAuthContextProvider] in production and can be backed by a fake
 * provider in local Robolectric/unit tests via `AuthenticatedFirebaseTestRule`.
 */
interface AuthContextProvider {
    val currentUser: AuthenticatedUserContext?
    fun observeUserId(): Flow<String?>
    suspend fun checkAdminClaims(forceRefresh: Boolean = false): AdminClaimStatus
}

class FirebaseAuthContextProvider(
    private val auth: FirebaseAuth = Firebase.auth,
) : AuthContextProvider {

    override val currentUser: AuthenticatedUserContext?
        get() = runCatching { auth.currentUser }.getOrNull()?.toUserContext()

    override fun observeUserId(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(runCatching { firebaseAuth.currentUser?.uid }.getOrNull())
        }
        trySend(runCatching { auth.currentUser?.uid }.getOrNull())
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    override suspend fun checkAdminClaims(forceRefresh: Boolean): AdminClaimStatus {
        val firebaseUser = runCatching { auth.currentUser }.getOrNull()
            ?: return AdminClaimStatus.SignedOut
        val userContext = firebaseUser.toUserContext()
        return try {
            val tokenResult = firebaseUser.getIdToken(forceRefresh).await()
            val matched = evaluateAdminClaims(tokenResult.claims)
            if (matched.isNotEmpty()) {
                AdminClaimStatus.Authorized(
                    user = userContext,
                    matchedClaims = matched,
                    viaTrustedRegistry = false,
                )
            } else {
                AdminClaimStatus.Denied(
                    user = userContext,
                    reason = "Your Firebase Auth profile lacks the custom 'admin' claim (admin: true).",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AdminClaimStatus.Denied(
                user = userContext,
                reason = e.localizedMessage ?: "Unable to verify Firebase ID token custom 'admin' claim.",
            )
        }
    }
}

internal fun FirebaseUser.toUserContext(): AuthenticatedUserContext {
    return AuthenticatedUserContext(
        uid = uid,
        email = email,
        isEmailVerified = isEmailVerified,
        tenantId = tenantId,
    )
}

package com.example.security

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.example.R
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

@Volatile
private var appCheckConfigured: Boolean = false

@Volatile
private var lastConfiguredDebugSecret: String? = null

@Volatile
private var lastInitStatus: String = "unconfigured"

fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
            FirebaseApp.initializeApp(context)
        }

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
            if (appCheckConfigured && (debugToken.isNullOrEmpty() || debugToken == lastConfiguredDebugSecret)) {
                return
            }

            if (!debugToken.isNullOrEmpty()) {
                val persistenceKey = FirebaseApp.getInstance().persistenceKey
                val prefs = context.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE,
                )
                // Must use commit() synchronously so DebugAppCheckProviderFactory reads the secret immediately.
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
                lastConfiguredDebugSecret = debugToken
                Log.d(TAG, "App Check debug secret configured successfully.")
                lastInitStatus = "DebugAppCheck(intentToken=present)"
            } else {
                Log.w(TAG, "No debug token found in Intent extra.")
                lastInitStatus = "DebugAppCheck(intentToken=missing)"
            }

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance(),
            )
            appCheckConfigured = true
            Log.d(TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            if (appCheckConfigured) return
            val siteKey = resolveRecaptchaSiteKey(context)
            if (!siteKey.isNullOrBlank()) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    RecaptchaAppCheckProviderFactory.getInstance(siteKey),
                )
                appCheckConfigured = true
                lastInitStatus = "RecaptchaAppCheck(key=${siteKey.take(8)}…)"
                Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
            } else {
                lastInitStatus = "RecaptchaAppCheck(missing_site_key)"
                Log.e(TAG, "recaptcha_site_key string resource not found or blank in package.")
            }
        }
    } catch (e: Exception) {
        lastInitStatus = "initError(${e.javaClass.simpleName}: ${e.localizedMessage})"
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}

private fun resolveRecaptchaSiteKey(context: Context): String? {
    val resId = context.resources.getIdentifier(RECAPTCHA_SITE_KEY_RES, "string", context.packageName)
    if (resId != 0) {
        val value = runCatching { context.getString(resId) }.getOrNull()
        if (!value.isNullOrBlank()) return value
    }
    return runCatching { context.getString(R.string.recaptcha_site_key) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
}

fun isDefaultFirebaseAppInitialized(context: Context): Boolean =
    runCatching {
        FirebaseApp.getApps(context).any { it.name == FirebaseApp.DEFAULT_APP_NAME }
    }.getOrDefault(false)

fun ensureFirebaseAndAppCheckInitialized(context: Context, intent: Intent? = null): Boolean {
    if (!isDefaultFirebaseAppInitialized(context)) {
        configureAppCheck(context, intent)
    }
    return isDefaultFirebaseAppInitialized(context)
}

fun isAppCheckTokenError(rawMessage: String): Boolean {
    val lower = rawMessage.lowercase()
    return lower.contains("app check") ||
        lower.contains("appcheck") ||
        lower.contains("attestation")
}

suspend fun diagnoseAppCheckFailure(context: Context? = null): String? {
    val app = runCatching {
        val targetCtx = context?.applicationContext
        if (targetCtx != null && FirebaseApp.getApps(targetCtx).isEmpty()) {
            FirebaseApp.initializeApp(targetCtx)
        }
        FirebaseApp.getInstance()
    }.getOrNull() ?: return "FirebaseApp not initialized ($lastInitStatus)"

    val options = app.options
    val projectInfo = "project=${options.projectId ?: "?"}, appId=${options.applicationId.takeLast(12)}, pkg=${app.applicationContext.packageName}, provider=$lastInitStatus"

    val tokenProbe = withTimeoutOrNull(8_000L) {
        suspendCancellableCoroutine<String> { cont ->
            try {
                FirebaseAppCheck.getInstance(app)
                    .getAppCheckToken(true)
                    .addOnSuccessListener { result ->
                        if (cont.isActive) {
                            val tokenLen = result.token.length
                            cont.resume("tokenOk(len=$tokenLen)")
                        }
                    }
                    .addOnFailureListener { exc ->
                        if (cont.isActive) {
                            val detail = generateSequence(exc as Throwable?) { it.cause }
                                .mapNotNull { t -> t.message?.trim()?.takeIf { it.isNotEmpty() } }
                                .distinct()
                                .joinToString(" | ")
                            cont.resume("tokenErr(${exc.javaClass.simpleName}: ${detail.ifBlank { "unknown" }})")
                        }
                    }
            } catch (e: Exception) {
                if (cont.isActive) {
                    cont.resume("probeExc(${e.javaClass.simpleName}: ${e.localizedMessage})")
                }
            }
        }
    } ?: "tokenProbeTimedOut(8s)"

    return "$projectInfo, $tokenProbe"
}

fun formatAppCheckErrorMessage(
    rawMessage: String,
    isDebugBuild: Boolean = BuildConfig.DEBUG,
    diagnosticDetail: String? = null,
): String {
    val base = rawMessage.trim().ifBlank { "Firebase App Check token is invalid." }
    val guidance = if (isDebugBuild) {
        "$base Debug APKs require AI Studio Preview or a registered Firebase App Check debug token. Install the signed Release APK from GitHub 'Release Android APK' for personal phone use."
    } else {
        "$base Ensure Google Play Services & internet are reachable (disable VPN/Tor briefly if blocked) and verify App Check reCAPTCHA Enterprise is linked for this package in Firebase."
    }
    return if (diagnosticDetail.isNullOrBlank()) {
        guidance
    } else {
        "$guidance\n[Diagnostic: $diagnosticDetail]"
    }
}

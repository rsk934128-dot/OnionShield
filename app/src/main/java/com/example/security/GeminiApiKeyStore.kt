package com.example.security

import android.content.Context
import com.example.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GeminiKeySource(val label: String) {
    USER_CUSTOM("Direct Gemini API (Custom Key)"),
    BUILD_SECRET("Direct Gemini API (Build Secret)"),
    FIREBASE_AI_LOGIC("Firebase AI Logic (App Check)"),
}

/**
 * Thread-safe runtime holder and resolver for an optional Gemini Developer API key.
 * Priority:
 *   1) End-user key entered in Settings / Quick Dialog (stored AES-256-GCM encrypted in DataStore)
 *   2) Optional build-time secret injected via BuildConfig.GEMINI_API_KEY (.env / GitHub Secret)
 *   3) None -> uses Firebase AI Logic (App Check)
 */
object GeminiApiKeyStore {

    @Volatile
    private var userCustomKey: String? = null

    private val _activeSource = MutableStateFlow(resolveSource())
    val activeSource: StateFlow<GeminiKeySource> = _activeSource.asStateFlow()

    private val _maskedUserKey = MutableStateFlow<String?>(null)
    val maskedUserKey: StateFlow<String?> = _maskedUserKey.asStateFlow()

    fun setUserApiKey(rawKey: String?) {
        val clean = normalizeApiKey(rawKey)
        userCustomKey = clean
        _maskedUserKey.value = clean?.let { maskApiKey(it) }
        _activeSource.value = resolveSource()
    }

    fun resolveActiveApiKey(): String? {
        userCustomKey?.let { return it }
        return resolveBuildConfigApiKey()
    }

    fun hasActiveApiKey(): Boolean = resolveActiveApiKey() != null

    fun hasUserCustomKey(): Boolean = userCustomKey != null

    fun resolveSource(): GeminiKeySource = when {
        userCustomKey != null -> GeminiKeySource.USER_CUSTOM
        resolveBuildConfigApiKey() != null -> GeminiKeySource.BUILD_SECRET
        else -> GeminiKeySource.FIREBASE_AI_LOGIC
    }

    fun resolveBuildConfigApiKey(): String? {
        val raw = runCatching {
            val field = BuildConfig::class.java.getField("GEMINI_API_KEY")
            field.get(null) as? String
        }.getOrNull()
        return normalizeApiKey(raw)
    }

    fun normalizeApiKey(raw: String?): String? {
        val cleaned = raw?.trim()?.trim('"', '\'') ?: return null
        if (cleaned.isEmpty()) return null
        if (cleaned.equals("null", ignoreCase = true)) return null
        if (cleaned.contains("DEFAULT_VALUE", ignoreCase = true)) return null
        if (cleaned.equals("YOUR_API_KEY", ignoreCase = true)) return null
        return cleaned
    }

    fun maskApiKey(key: String): String {
        val clean = key.trim()
        if (clean.length <= 8) return "••••${clean.takeLast(2)}"
        return "${clean.take(4)}••••••${clean.takeLast(4)}"
    }

    fun encryptForStorage(context: Context, rawKey: String): String {
        val clean = normalizeApiKey(rawKey).orEmpty()
        if (clean.isEmpty()) return ""
        val key = VaultCryptoManager.getOrCreateVaultKey(context)
        return VaultCryptoManager.encrypt(clean, key)
    }

    fun decryptFromStorage(context: Context, encryptedPayload: String?): String? {
        if (encryptedPayload.isNullOrBlank()) return null
        return runCatching {
            val key = VaultCryptoManager.getOrCreateVaultKey(context)
            normalizeApiKey(VaultCryptoManager.decrypt(encryptedPayload, key))
        }.getOrNull()
    }
}

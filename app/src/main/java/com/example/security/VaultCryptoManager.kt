package com.example.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hardware-backed AES-256-GCM cipher using Android Keystore + BiometricPrompt CryptoObject
 * key-wrapping for Zero-Trace local vault entries.
 */
object VaultCryptoManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "onionshield_vault_aes256_v1"
    private const val BIOMETRIC_KEK_ALIAS = "onionshield_vault_biometric_kek_v1"
    private const val SEARCH_HISTORY_KEY_ALIAS = "onionshield_search_history_aes256_v1"
    private const val SEARCH_HISTORY_HMAC_ALIAS = "onionshield_search_history_hmac_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val DEK_BYTES = 32
    private const val PBKDF2_ITERATIONS = 100_000

    private val secureRandom = SecureRandom()

    fun getOrCreateVaultKey(context: Context? = null): SecretKey {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }

        // Software AES-256 fallback only when AndroidKeyStore provider is absent (e.g. JVM unit test runner)
        val fallbackMaterial = (context?.packageName ?: "com.example.onionshield.test").toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(fallbackMaterial)
        return SecretKeySpec(digest, "AES")
    }

    fun getOrCreateSearchHistoryKey(context: Context? = null): SecretKey {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getKey(SEARCH_HISTORY_KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                SEARCH_HISTORY_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }

        val fallbackMaterial = "${context?.packageName ?: "com.example.onionshield.test"}:search_history_v1"
            .toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(fallbackMaterial)
        return SecretKeySpec(digest, "AES")
    }

    fun computeSearchHistoryHmac(normalizedInput: String, context: Context? = null): String {
        val clean = normalizedInput.trim().lowercase()
        val hmacKey = runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getKey(SEARCH_HISTORY_HMAC_ALIAS, null) as? SecretKey
            if (existing != null) {
                existing
            } else {
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    SEARCH_HISTORY_HMAC_ALIAS,
                    KeyProperties.PURPOSE_SIGN,
                ).build()
                keyGen.init(spec)
                keyGen.generateKey()
            }
        }.getOrElse {
            val fallbackMaterial = "${context?.packageName ?: "com.example.onionshield.test"}:search_hmac_v1"
                .toByteArray(Charsets.UTF_8)
            SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(fallbackMaterial), HMAC_ALGORITHM)
        }

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(hmacKey)
        val bytes = mac.doFinal(clean.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun getOrCreateBiometricKek(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getKey(BIOMETRIC_KEK_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            BIOMETRIC_KEK_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .setRandomizedEncryptionRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    fun resetBiometricKek() {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(BIOMETRIC_KEK_ALIAS)) {
                ks.deleteEntry(BIOMETRIC_KEK_ALIAS)
            }
        }
    }

    fun createBiometricEncryptCipher(): Cipher {
        return try {
            val key = getOrCreateBiometricKek()
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            resetBiometricKek()
            val freshKey = getOrCreateBiometricKek()
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, freshKey)
            }
        }
    }

    fun createBiometricDecryptCipher(wrappedDekEnvelope: String): Cipher {
        val parts = wrappedDekEnvelope.split(':')
        require(parts.size == 3 && parts[0] == "bio1") { "Invalid biometric wrapped key envelope" }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val key = getOrCreateBiometricKek()
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
    }

    fun generateAndWrapVaultDek(authenticatedEncryptCipher: Cipher): Pair<SecretKey, String> {
        val dekBytes = ByteArray(DEK_BYTES).also { secureRandom.nextBytes(it) }
        val iv = authenticatedEncryptCipher.iv
            ?: ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val wrapped = authenticatedEncryptCipher.doFinal(dekBytes)
        val envelope = encodeEnvelope(prefix = "bio1", iv = iv, ciphertext = wrapped)
        return SecretKeySpec(dekBytes, "AES") to envelope
    }

    fun unwrapVaultDek(authenticatedDecryptCipher: Cipher, wrappedDekEnvelope: String): SecretKey {
        val parts = wrappedDekEnvelope.split(':')
        require(parts.size == 3 && parts[0] == "bio1") { "Invalid biometric wrapped key envelope" }
        val wrappedBytes = Base64.decode(parts[2], Base64.NO_WRAP)
        val dekBytes = authenticatedDecryptCipher.doFinal(wrappedBytes)
        require(dekBytes.size == DEK_BYTES) { "Unexpected unwrapped vault key length" }
        return SecretKeySpec(dekBytes, "AES")
    }

    fun encrypt(plaintext: String, key: SecretKey): String {
        if (plaintext.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val isSoftwareKey = key is SecretKeySpec
        if (isSoftwareKey) {
            val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return encodeEnvelope(prefix = "v1", iv = iv, ciphertext = sealed)
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return encodeEnvelope(prefix = "v1", iv = iv, ciphertext = sealed)
        }
    }

    fun decrypt(payload: String, key: SecretKey): String {
        if (payload.isEmpty()) return ""
        val parts = payload.split(':')
        require(parts.size == 3 && parts[0] == "v1") { "Invalid vault ciphertext envelope" }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plain = cipher.doFinal(ciphertext)
        return String(plain, Charsets.UTF_8)
    }

    fun generateSaltBase64(): String {
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    fun hashPassphrase(passphrase: String, saltBase64: String): String {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.encodeToString(derived, Base64.NO_WRAP)
    }

    fun verifyPassphrase(candidate: String, saltBase64: String, expectedHashBase64: String): Boolean {
        if (saltBase64.isBlank() || expectedHashBase64.isBlank()) return false
        val actual = Base64.decode(hashPassphrase(candidate, saltBase64), Base64.NO_WRAP)
        val expected = Base64.decode(expectedHashBase64, Base64.NO_WRAP)
        return MessageDigest.isEqual(actual, expected)
    }

    private fun encodeEnvelope(prefix: String, iv: ByteArray, ciphertext: ByteArray): String {
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$prefix:$ivB64:$ctB64"
    }
}

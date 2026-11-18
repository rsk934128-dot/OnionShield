package com.example.browser

import com.example.data.SessionRoundLogEntity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

enum class GuardAlertLevel {
    OFF,
    SAFE,
    APPROACHING_LIMIT,
    LIMIT_REACHED,
}

data class MultiplierBucket(
    val label: String,
    val count: Int,
)

data class SessionGuardStats(
    val totalRounds: Int = 0,
    val winsCount: Int = 0,
    val totalWagered: Double = 0.0,
    val totalReturned: Double = 0.0,
    val netProfit: Double = 0.0,
    val averageCrashMultiplier: Double = 0.0,
    val maxCrashMultiplier: Double = 0.0,
    val buckets: List<MultiplierBucket> = emptyList(),
    val elapsedMinutes: Long = 0L,
    val timeAlertLevel: GuardAlertLevel = GuardAlertLevel.OFF,
    val budgetAlertLevel: GuardAlertLevel = GuardAlertLevel.OFF,
    val overallAlertLevel: GuardAlertLevel = GuardAlertLevel.OFF,
)

data class FairnessVerificationResult(
    val isValidInput: Boolean,
    val computedServerSeedSha256: String = "",
    val commitmentMatched: Boolean? = null,
    val hmacSha256Hex: String = "",
    val derivedMultiplier: Double = 1.0,
    val summary: String,
)

object SessionGuardEngine {

    fun computeSessionStats(
        rounds: List<SessionRoundLogEntity>,
        guardEnabled: Boolean,
        elapsedMinutes: Long,
        timeLimitMinutes: Int,
        budgetLimit: Double,
    ): SessionGuardStats {
        val totalRounds = rounds.size
        var winsCount = 0
        var totalWagered = 0.0
        var totalReturned = 0.0
        var crashSum = 0.0
        var maxCrash = 0.0

        var b1 = 0 // 1.00x - 1.49x
        var b2 = 0 // 1.50x - 2.99x
        var b3 = 0 // 3.00x - 9.99x
        var b4 = 0 // 10.00x+

        for (r in rounds) {
            val bet = max(0.0, r.betAmount)
            val crash = max(1.0, r.crashMultiplier)
            totalWagered += bet
            crashSum += crash
            if (crash > maxCrash) maxCrash = crash

            val cashout = r.cashoutMultiplier
            if (cashout != null && cashout >= 1.0 && cashout <= crash) {
                winsCount++
                totalReturned += bet * cashout
            }

            when {
                crash < 1.5 -> b1++
                crash < 3.0 -> b2++
                crash < 10.0 -> b3++
                else -> b4++
            }
        }

        val netProfit = totalReturned - totalWagered
        val netLoss = max(0.0, -netProfit)
        val avgCrash = if (totalRounds > 0) crashSum / totalRounds else 0.0

        val timeAlert = evaluateLimitAlert(
            guardEnabled = guardEnabled,
            used = elapsedMinutes.toDouble(),
            limit = timeLimitMinutes.toDouble(),
        )
        val budgetAlert = evaluateLimitAlert(
            guardEnabled = guardEnabled,
            used = netLoss,
            limit = budgetLimit,
        )
        val overall = maxOf(timeAlert, budgetAlert)

        return SessionGuardStats(
            totalRounds = totalRounds,
            winsCount = winsCount,
            totalWagered = totalWagered,
            totalReturned = totalReturned,
            netProfit = netProfit,
            averageCrashMultiplier = avgCrash,
            maxCrashMultiplier = maxCrash,
            buckets = listOf(
                MultiplierBucket("1.0–1.49x", b1),
                MultiplierBucket("1.5–2.99x", b2),
                MultiplierBucket("3.0–9.99x", b3),
                MultiplierBucket("10x+", b4),
            ),
            elapsedMinutes = elapsedMinutes,
            timeAlertLevel = timeAlert,
            budgetAlertLevel = budgetAlert,
            overallAlertLevel = overall,
        )
    }

    private fun evaluateLimitAlert(
        guardEnabled: Boolean,
        used: Double,
        limit: Double,
    ): GuardAlertLevel {
        if (!guardEnabled) return GuardAlertLevel.OFF
        if (limit <= 0.0) return GuardAlertLevel.SAFE
        val ratio = used / limit
        return when {
            ratio >= 1.0 -> GuardAlertLevel.LIMIT_REACHED
            ratio >= 0.8 -> GuardAlertLevel.APPROACHING_LIMIT
            else -> GuardAlertLevel.SAFE
        }
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        val bytes = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies a completed Provably Fair crash round AFTER the server reveals its Server Seed.
     * Standard 52-bit HMAC-SHA256 crash formula (educational/post-round audit only).
     */
    fun verifyCrashFairness(
        serverSeed: String,
        clientSeed: String,
        nonce: String,
        expectedServerSeedHash: String = "",
    ): FairnessVerificationResult {
        val cleanServer = serverSeed.trim()
        val cleanClient = clientSeed.trim()
        val cleanNonce = nonce.trim()
        val cleanHash = expectedServerSeedHash.trim().lowercase()

        if (cleanServer.isEmpty() || cleanClient.isEmpty()) {
            return FairnessVerificationResult(
                isValidInput = false,
                summary = "Enter both revealed Server Seed and Client Seed from a finished round to verify.",
            )
        }

        val computedSha256 = sha256Hex(cleanServer)
        val commitmentMatched = if (cleanHash.isNotEmpty()) {
            computedSha256.equals(cleanHash, ignoreCase = true)
        } else {
            null
        }

        val payload = if (cleanNonce.isNotEmpty()) "$cleanClient:$cleanNonce" else cleanClient
        val hmacHex = hmacSha256Hex(cleanServer, payload)

        // Check standard 1-in-33 instant bust condition on raw bytes (first 8 hex chars)
        val rawMod33 = hmacHex.take(8).toLong(16)
        val multiplier = if (rawMod33 % 33L == 0L) {
            1.00
        } else {
            val h = hmacHex.take(13).toLong(16).toDouble()
            val e = 2.0.pow(52.0)
            val raw = floor((100.0 * e - h) / (e - h)) / 100.0
            max(1.00, raw)
        }

        val summary = buildString {
            append("Post-round HMAC multiplier: ${"%.2f".format(multiplier)}x. ")
            when (commitmentMatched) {
                true -> append("SHA-256 seed commitment MATCHED.")
                false -> append("WARNING: SHA-256 seed commitment did NOT match expected hash!")
                null -> append("Add pre-round SHA-256 hash to also confirm seed commitment.")
            }
        }

        return FairnessVerificationResult(
            isValidInput = true,
            computedServerSeedSha256 = computedSha256,
            commitmentMatched = commitmentMatched,
            hmacSha256Hex = hmacHex,
            derivedMultiplier = multiplier,
            summary = summary,
        )
    }
}

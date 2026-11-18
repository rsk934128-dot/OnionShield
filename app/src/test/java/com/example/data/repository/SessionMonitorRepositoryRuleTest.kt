package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.browser.GuardAlertLevel
import com.example.browser.SessionGuardStats
import com.example.data.SecurityConfig
import com.example.data.SessionRoundLogEntity
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMonitorRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun publishAndObserveSessionMonitor_owner_succeedsAndSanitizesOnion() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = SessionMonitorFirestoreRepository(firestore, auth)

        val stats = SessionGuardStats(
            totalRounds = 3,
            winsCount = 2,
            totalWagered = 30.0,
            totalReturned = 45.0,
            netProfit = 15.0,
            averageCrashMultiplier = 3.25,
            maxCrashMultiplier = 5.71,
            elapsedMinutes = 10L,
            overallAlertLevel = GuardAlertLevel.SAFE,
        )
        val cfg = SecurityConfig(
            sessionGuardEnabled = true,
            sessionTimeLimitMinutes = 30,
            sessionBudgetLimit = 100.0,
            sessionCurrencyLabel = "BDT",
            liveCloudMonitorSyncEnabled = true,
        )

        val publishCreate = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.publishMonitorSnapshot(
                siteHost = "secret777example.onion",
                config = cfg,
                stats = stats,
                isMonitoringActive = true,
            )
        }
        assertTrue(publishCreate.isSuccess)

        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeActiveMonitor(uid).first { it != null && it.totalRounds == 3 }
        }
        assertEquals("onion_redacted", observed?.siteHost)
        assertEquals("SAFE", observed?.alertLevel)
        assertEquals(15.0, observed?.netProfit ?: 0.0, 0.001)

        // Update existing snapshot (verifying update rule allows updating mutable metrics without touching userId/createdAt)
        val publishUpdate = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.publishMonitorSnapshot(
                siteHost = "example.org",
                config = cfg,
                stats = stats.copy(
                    elapsedMinutes = 25L,
                    overallAlertLevel = GuardAlertLevel.APPROACHING_LIMIT,
                ),
                isMonitoringActive = true,
            )
        }
        assertTrue(publishUpdate.isSuccess)

        val updatedObserved = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeActiveMonitor(uid).first { it?.alertLevel == "APPROACHING_LIMIT" }
        }
        assertEquals(25L, updatedObserved?.elapsedMinutes)
        assertEquals("example.org", updatedObserved?.siteHost)
    }

    @Test
    fun upsertObserveAndDeleteRemoteRound_owner_succeedsAndBlocksCrossUser() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = SessionMonitorFirestoreRepository(firestore, auth)

        val round = SessionRoundLogEntity(
            siteHost = "example.org",
            betAmount = 10.0,
            crashMultiplier = 5.71,
            cashoutMultiplier = 2.50,
            netProfit = 15.0,
            note = "Verified session",
            createdAt = 1700000123000L,
        )
        val roundId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.upsertRemoteRound(round).getOrThrow()
        }

        val observedRounds = withTimeout(FLOW_TIMEOUT_MS) {
            aliceRepo.observeRemoteRounds(aliceUid).first { list -> list.any { it.id == roundId } }
        }
        assertTrue(observedRounds.any { it.id == roundId && it.crashMultiplier == 5.71 })

        // Cross-user read by Bob must be denied
        signInTestUser(BOB_EMAIL)
        val bobMonitorReadError = runCatching {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users")
                    .document(aliceUid)
                    .collection("session_monitors")
                    .document(SessionMonitorFirestoreRepository.DEFAULT_MONITOR_ID)
                    .get()
                    .await()
            }
        }.exceptionOrNull()
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            bobMonitorReadError?.unwrapFirestoreException()?.code,
        )

        val bobRoundReadError = runCatching {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users")
                    .document(aliceUid)
                    .collection("session_rounds")
                    .document(roundId)
                    .get()
                    .await()
            }
        }.exceptionOrNull()
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            bobRoundReadError?.unwrapFirestoreException()?.code,
        )

        // Switch back to Alice and delete round cleanly
        signInTestUser(ALICE_EMAIL)
        val deleteRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.deleteRemoteRound(roundId)
        }
        assertTrue(deleteRes.isSuccess)
    }

    @Test
    fun observeMonitor_unauthenticatedCaller_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = SessionMonitorFirestoreRepository(firestore, auth)
        val error = runCatching {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeActiveMonitor("alice_uid").first()
            }
        }.exceptionOrNull()
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            error?.unwrapFirestoreException()?.code,
        )
    }

    private companion object {
        const val ALICE_EMAIL = "alice_monitor@test.com"
        const val BOB_EMAIL = "bob_monitor@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}

package com.example

import com.example.base.AuthenticatedFirebaseTestRule
import com.example.base.createAuthenticatedAuthProvider
import com.example.base.withAuthenticatedUserContext
import com.example.browser.GuardAlertLevel
import com.example.browser.SessionGuardStats
import com.example.browser.SessionMonitorSyncStatus
import com.example.data.SecurityConfig
import com.example.data.SessionRoundLogEntity
import com.example.data.repository.AdminClaimStatus
import com.example.data.repository.AdminServerConfig
import com.example.data.repository.MaintenanceLogEntry
import com.example.data.repository.SyncedLink
import com.example.data.repository.evaluateAdminClaims
import com.example.ui.AdminConfigUiState
import com.example.ui.AdminSettingsViewModel
import com.example.ui.MaintenanceLogsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class AuthenticatedRepositoryUnitTest {

    @get:Rule
    val authRule = AuthenticatedFirebaseTestRule(
        defaultUid = "robolectric_uid_alice",
        defaultEmail = "alice@onionshield.test",
    )

    @Test
    fun `cloud sync repository passes authenticated operations under robolectric rule`() = runBlocking {
        val repository = authRule.createCloudSyncRepository()

        val upsertRes = repository.upsertSyncedLink(
            title = "OnionShield Docs",
            url = "https://example.org/docs",
            kind = SyncedLink.KIND_BOOKMARK,
        )
        assertTrue(upsertRes.isSuccess)
        val linkId = upsertRes.getOrThrow()

        val observed = withTimeout(3000L) {
            repository.observeSyncedLinks(authRule.currentUid)
                .first { list -> list.any { it.id == linkId } }
        }
        assertTrue(observed.any { it.id == linkId && it.title == "OnionShield Docs" })

        val loaded = repository.getSyncedLink(linkId)
        assertTrue(loaded.isSuccess)
        assertEquals("OnionShield Docs", loaded.getOrNull()?.title)

        val deleteRes = repository.deleteSyncedLink(linkId)
        assertTrue(deleteRes.isSuccess)
    }

    @Test
    fun `repositories reject operations when signed out and succeed inside withAuthenticatedUser`() = runBlocking {
        val cloudRepo = authRule.createCloudSyncRepository()
        val monitorRepo = authRule.createSessionMonitorRepository()

        authRule.signOut()
        assertNull(authRule.currentUser)

        val unauthBookmark = cloudRepo.upsertSyncedLink(
            title = "Blocked Write",
            url = "https://example.org/private",
            kind = SyncedLink.KIND_BOOKMARK,
        )
        assertTrue(unauthBookmark.isFailure)
        assertTrue(unauthBookmark.exceptionOrNull() is IllegalStateException)

        val unauthMonitor = monitorRepo.publishMonitorSnapshot(
            siteHost = "example.org",
            config = SecurityConfig(liveCloudMonitorSyncEnabled = true),
            stats = SessionGuardStats(),
            isMonitoringActive = true,
        )
        assertTrue(unauthMonitor.isFailure)
        assertTrue(unauthMonitor.exceptionOrNull() is IllegalStateException)

        authRule.withAuthenticatedUser(uid = "robolectric_uid_bob", email = "bob@onionshield.test") { user ->
            assertEquals("robolectric_uid_bob", user.uid)

            val round = SessionRoundLogEntity(
                siteHost = "secret777example.onion",
                betAmount = 25.0,
                crashMultiplier = 3.5,
                cashoutMultiplier = 2.0,
                netProfit = 25.0,
                createdAt = 1700000555000L,
            )
            val roundRes = monitorRepo.upsertRemoteRound(round)
            assertTrue(roundRes.isSuccess)

            val snapshotRes = monitorRepo.publishMonitorSnapshot(
                siteHost = "secret777example.onion",
                config = SecurityConfig(liveCloudMonitorSyncEnabled = true, sessionGuardEnabled = true),
                stats = SessionGuardStats(totalRounds = 1, winsCount = 1, netProfit = 25.0, overallAlertLevel = GuardAlertLevel.SAFE),
                isMonitoringActive = true,
            )
            assertTrue(snapshotRes.isSuccess)

            val deleteRoundRes = monitorRepo.deleteRemoteRound(roundRes.getOrThrow())
            assertTrue(deleteRoundRes.isSuccess)
        }

        // Previous signed-out state restored cleanly after scoped block
        assertNull(authRule.currentUser)
    }

    @Test
    fun `standalone auth helpers and sync service react to authenticated user context`() = runBlocking {
        val helperProvider = createAuthenticatedAuthProvider(uid = "standalone_uid", email = "solo@example.org")
        assertEquals("standalone_uid", helperProvider.currentUser?.uid)

        withAuthenticatedUserContext(uid = "scoped_uid", authProvider = helperProvider) { _, user ->
            assertEquals("scoped_uid", user.uid)
            assertEquals("scoped_uid", helperProvider.currentUser?.uid)
        }
        assertEquals("standalone_uid", helperProvider.currentUser?.uid)

        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val syncService = authRule.createSessionMonitorSyncService(serviceScope)
            val configFlow = MutableStateFlow(SecurityConfig(liveCloudMonitorSyncEnabled = true))
            val hostFlow = MutableStateFlow("example.org")
            val statsFlow = MutableStateFlow(SessionGuardStats(totalRounds = 2, winsCount = 1, netProfit = 10.0))
            val roundsFlow = MutableStateFlow(emptyList<SessionRoundLogEntity>())

            syncService.startObserving(hostFlow, configFlow, statsFlow, roundsFlow)

            val syncingStatus = withTimeout(5000L) {
                syncService.syncStatus.first { it is SessionMonitorSyncStatus.SyncingLive }
            }
            assertTrue(syncingStatus is SessionMonitorSyncStatus.SyncingLive)

            authRule.signOut()
            val signInRequired = withTimeout(5000L) {
                syncService.syncStatus.first { it is SessionMonitorSyncStatus.SignInRequired }
            }
            assertTrue(signInRequired is SessionMonitorSyncStatus.SignInRequired)
            syncService.stop()
        } finally {
            serviceScope.cancel()
        }
    }

    @Test
    fun `admin claims verification distinguishes signed out, non-admin denied, and claim-authorized users`(): Unit = runBlocking {
        assertTrue(evaluateAdminClaims(emptyMap()).isEmpty())
        assertTrue(evaluateAdminClaims(mapOf("admin" to false, "role" to "viewer")).isEmpty())
        assertTrue(com.example.data.repository.hasCustomAdminClaim(mapOf("admin" to true)))
        assertTrue(com.example.data.repository.hasCustomAdminClaim(mapOf("admin" to "true")))
        assertFalse(com.example.data.repository.hasCustomAdminClaim(mapOf("admin" to false)))
        assertTrue(evaluateAdminClaims(mapOf("admin" to true)).contains("admin=true"))
        assertTrue(evaluateAdminClaims(mapOf("admin" to "true")).contains("admin=true"))
        assertTrue(evaluateAdminClaims(mapOf("role" to "ADMIN")).contains("role=ADMIN"))
        assertTrue(evaluateAdminClaims(mapOf("roles" to listOf("editor", "admin"))).contains("roles[admin]"))
        assertTrue(evaluateAdminClaims(mapOf("permissions" to listOf("admin_settings"))).contains("permissions[admin_settings]"))

        authRule.signOut()
        assertEquals(AdminClaimStatus.SignedOut, authRule.authProvider.checkAdminClaims())

        authRule.signIn(uid = "regular_uid", email = "user@onionshield.test", claims = emptyMap())
        val denied = authRule.authProvider.checkAdminClaims()
        assertTrue(denied is AdminClaimStatus.Denied)

        authRule.withAuthenticatedUser(
            uid = "admin_uid_1",
            email = "admin@onionshield.test",
            claims = mapOf("admin" to true, "role" to "admin"),
        ) {
            val authorized = authRule.authProvider.checkAdminClaims()
            assertTrue(authorized is AdminClaimStatus.Authorized)
            val claims = (authorized as AdminClaimStatus.Authorized).matchedClaims
            assertTrue(claims.contains("admin=true"))
            assertTrue(claims.contains("role=admin"))
        }
    }

    @Test
    fun `admin settings repository and viewmodel sync server toggles and maintenance logs`(): Unit = runBlocking {
        val adminRepo = authRule.createAdminSettingsRepository()

        authRule.signOut()
        val unauthSave = adminRepo.saveServerConfig(AdminServerConfig(maintenanceMode = true))
        assertTrue(unauthSave.isFailure)

        authRule.withAuthenticatedUser(
            uid = "admin_ops_uid",
            email = "secops@onionshield.test",
            claims = mapOf("admin" to true),
        ) {
            val saveRes = adminRepo.saveServerConfig(
                config = AdminServerConfig(
                    maintenanceMode = true,
                    strictTorRequired = true,
                    remoteTelemetryEnabled = false,
                    forceAdBlockSync = true,
                    emergencyLockdown = true,
                    maintenanceBanner = "Rotating onion relays at 02:00 UTC.",
                ),
                auditAction = "Enabled Emergency Lockdown",
                auditCategory = MaintenanceLogEntry.CATEGORY_SECURITY,
                auditSeverity = MaintenanceLogEntry.SEVERITY_CRITICAL,
                auditDetails = "Activated strict Tor + lockdown.",
            )
            assertTrue(saveRes.isSuccess)

            val configSnap = withTimeout(3000L) {
                adminRepo.observeServerConfig().first { it.existsRemotely }
            }
            assertTrue(configSnap.config.maintenanceMode)
            assertTrue(configSnap.config.strictTorRequired)
            assertFalse(configSnap.config.remoteTelemetryEnabled)
            assertTrue(configSnap.config.emergencyLockdown)
            assertEquals("Rotating onion relays at 02:00 UTC.", configSnap.config.maintenanceBanner)

            val infoLogRes = adminRepo.appendMaintenanceLog(
                action = "Routine rule sync",
                category = MaintenanceLogEntry.CATEGORY_CONFIG,
                severity = MaintenanceLogEntry.SEVERITY_INFO,
                details = "Filter lists synced cleanly.",
            )
            assertTrue(infoLogRes.isSuccess)
            val infoLogId = infoLogRes.getOrThrow()

            val viewModel = AdminSettingsViewModel(
                repository = adminRepo,
                currentUserId = "admin_ops_uid",
            )
            val loadedLogs = withTimeout(3000L) {
                viewModel.logsUiState.first {
                    it is MaintenanceLogsUiState.Success && it.entries.size >= 2
                }
            } as MaintenanceLogsUiState.Success
            assertEquals(2, loadedLogs.entries.size)

            viewModel.setSeverityFilter(MaintenanceLogEntry.SEVERITY_CRITICAL)
            val criticalOnly = withTimeout(3000L) {
                viewModel.filteredLogs.first { list -> list.size == 1 && list.first().severity == MaintenanceLogEntry.SEVERITY_CRITICAL }
            }
            assertEquals("Enabled Emergency Lockdown", criticalOnly.first().action)

            // Wait for VM config to synchronize then toggle privacy overrides via ViewModel methods
            withTimeout(5000L) {
                viewModel.configUiState.first { it is AdminConfigUiState.Success && it.existsRemotely }
            }
            viewModel.setSeverityFilter(AdminSettingsViewModel.FILTER_ALL)
            viewModel.toggleEmergencyLockdown(false)
            val afterLockdownOff = withTimeout(8000L) {
                viewModel.configUiState.first {
                    it is AdminConfigUiState.Success && !it.config.emergencyLockdown
                }
            } as AdminConfigUiState.Success
            assertFalse(afterLockdownOff.config.emergencyLockdown)

            // Wait for toggleEmergencyLockdown's audit log write to finish before next toggle
            withTimeout(8000L) {
                viewModel.logsUiState.first {
                    it is MaintenanceLogsUiState.Success &&
                        it.entries.any { e -> e.action == "Disabled Emergency Lockdown" }
                }
            }

            viewModel.toggleForceAdBlockSync(false)
            val afterAdBlockOff = withTimeout(8000L) {
                viewModel.configUiState.first {
                    it is AdminConfigUiState.Success && !it.config.forceAdBlockSync
                }
            } as AdminConfigUiState.Success
            assertFalse(afterAdBlockOff.config.forceAdBlockSync)

            val updatedLogs = withTimeout(8000L) {
                viewModel.logsUiState.first {
                    it is MaintenanceLogsUiState.Success &&
                        it.entries.any { e -> e.action == "Disabled Emergency Lockdown" } &&
                        it.entries.any { e -> e.action == "Disabled Global Ad-Block Rules Sync" }
                }
            } as MaintenanceLogsUiState.Success
            assertTrue(updatedLogs.entries.any { it.action == "Disabled Global Ad-Block Rules Sync" })

            val deleteRes = adminRepo.deleteMaintenanceLog(infoLogId)
            assertTrue(deleteRes.isSuccess)
        }
    }
}

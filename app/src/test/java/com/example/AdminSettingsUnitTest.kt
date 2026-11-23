package com.example

import com.example.base.AuthenticatedFirebaseTestRule
import com.example.data.repository.AdminClaimStatus
import com.example.data.repository.AdminServerConfig
import com.example.data.repository.AdminServerConfigSnapshot
import com.example.data.repository.AdminSettingsDataSource
import com.example.data.repository.AdminSettingsRepository
import com.example.data.repository.MaintenanceLogEntry
import com.example.data.repository.MaintenanceLogsSnapshot
import com.example.data.repository.evaluateAdminClaims
import com.example.data.repository.hasCustomAdminClaim
import com.example.ui.AdminConfigUiState
import com.example.ui.AdminSettingsViewModel
import com.example.ui.MaintenanceLogsUiState
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * Deterministic unit test suite for [AdminSettingsRepository], [AdminSettingsViewModel],
 * and Firebase Auth custom `'admin'` claim verification.
 *
 * Uses [StandardTestDispatcher] virtual-time scheduling for ViewModel + recording mock verification,
 * and an isolated [AuthenticatedFirebaseTestRule] for offline Firestore repository contracts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class AdminSettingsUnitTest {

    @get:Rule
    val authRule = AuthenticatedFirebaseTestRule(
        defaultUid = "admin_unit_uid",
        defaultEmail = "ops-admin@onionshield.test",
    )

    data class RecordedConfigSave(
        val config: AdminServerConfig,
        val auditAction: String?,
        val auditCategory: String,
        val auditSeverity: String,
        val auditDetails: String,
    )

    data class RecordedLogAppend(
        val action: String,
        val category: String,
        val severity: String,
        val details: String,
    )

    /**
     * Thread-safe recording mock/fake implementing [AdminSettingsDataSource] with controllable
     * simulated latency, failure injection, and cancellation propagation.
     */
    private class RecordingAdminSettingsMock : AdminSettingsDataSource {
        val configFlow = MutableStateFlow(AdminServerConfigSnapshot())
        val logsFlow = MutableStateFlow(MaintenanceLogsSnapshot())

        val saveConfigCalls = CopyOnWriteArrayList<RecordedConfigSave>()
        val appendLogCalls = CopyOnWriteArrayList<RecordedLogAppend>()
        val deletedLogIds = CopyOnWriteArrayList<String>()
        val trustedDocChecks = CopyOnWriteArrayList<String>()

        var simulatedWriteDelayMs: Long = 0L
        var saveConfigFailure: Throwable? = null
        var appendLogFailure: Throwable? = null
        var deleteLogFailure: Throwable? = null
        var customConfigFlowOverride: Flow<AdminServerConfigSnapshot>? = null
        var customLogsFlowOverride: Flow<MaintenanceLogsSnapshot>? = null

        override suspend fun hasTrustedAdminDocument(uid: String): Boolean {
            trustedDocChecks += uid
            return false
        }

        override fun observeServerConfig(configId: String): Flow<AdminServerConfigSnapshot> {
            return customConfigFlowOverride ?: configFlow
        }

        override fun observeMaintenanceLogs(limitCount: Long): Flow<MaintenanceLogsSnapshot> {
            return customLogsFlowOverride ?: logsFlow
        }

        override suspend fun saveServerConfig(
            config: AdminServerConfig,
            auditAction: String?,
            auditCategory: String,
            auditSeverity: String,
            auditDetails: String,
        ): Result<Unit> {
            saveConfigCalls += RecordedConfigSave(
                config = config,
                auditAction = auditAction,
                auditCategory = auditCategory,
                auditSeverity = auditSeverity,
                auditDetails = auditDetails,
            )
            if (simulatedWriteDelayMs > 0L) {
                delay(simulatedWriteDelayMs)
            }
            saveConfigFailure?.let { err ->
                if (err is CancellationException) throw err
                return Result.failure(err)
            }

            configFlow.value = AdminServerConfigSnapshot(
                config = config,
                existsRemotely = true,
            )
            if (!auditAction.isNullOrBlank()) {
                val entry = MaintenanceLogEntry(
                    id = "log_mock_${logsFlow.value.entries.size + 1}",
                    action = auditAction,
                    category = auditCategory,
                    severity = auditSeverity,
                    details = auditDetails,
                    actorUid = "admin_unit_uid",
                    actorEmail = "ops-admin@onionshield.test",
                    clientTimestampMs = 1_700_000_000_000L + logsFlow.value.entries.size,
                )
                logsFlow.value = MaintenanceLogsSnapshot(
                    entries = listOf(entry) + logsFlow.value.entries,
                )
            }
            return Result.success(Unit)
        }

        override suspend fun appendMaintenanceLog(
            action: String,
            category: String,
            severity: String,
            details: String,
        ): Result<String> {
            appendLogCalls += RecordedLogAppend(action, category, severity, details)
            if (simulatedWriteDelayMs > 0L) {
                delay(simulatedWriteDelayMs)
            }
            appendLogFailure?.let { err ->
                if (err is CancellationException) throw err
                return Result.failure(err)
            }
            val newId = "log_manual_${appendLogCalls.size}"
            val entry = MaintenanceLogEntry(
                id = newId,
                action = action,
                category = category,
                severity = severity,
                details = details,
                actorUid = "admin_unit_uid",
                actorEmail = "ops-admin@onionshield.test",
                clientTimestampMs = 1_700_000_100_000L + appendLogCalls.size,
            )
            logsFlow.value = MaintenanceLogsSnapshot(entries = listOf(entry) + logsFlow.value.entries)
            return Result.success(newId)
        }

        override suspend fun deleteMaintenanceLog(logId: String): Result<Unit> {
            deletedLogIds += logId
            if (simulatedWriteDelayMs > 0L) {
                delay(simulatedWriteDelayMs)
            }
            deleteLogFailure?.let { err ->
                if (err is CancellationException) throw err
                return Result.failure(err)
            }
            logsFlow.value = MaintenanceLogsSnapshot(
                entries = logsFlow.value.entries.filterNot { it.id == logId },
            )
            return Result.success(Unit)
        }
    }

    private fun runWithMainTestDispatcher(
        block: suspend TestScope.() -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher, timeout = 10.seconds) {
                block()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `viewmodel privacy override toggles verify exact mock arguments and audit logs`() = runWithMainTestDispatcher {
        val mockRepo = RecordingAdminSettingsMock()
        val viewModel = AdminSettingsViewModel(
            repository = mockRepo,
            currentUserId = "admin_unit_uid",
        )

        // Start collecting stateFlows so WhileSubscribed(5000L) activates cleanly
        val configCollector = viewModel.configUiState.launchIn(backgroundScope)
        val logsCollector = viewModel.logsUiState.launchIn(backgroundScope)
        val filterCollector = viewModel.filteredLogs.launchIn(backgroundScope)
        advanceUntilIdle()

        // Toggle Emergency Lockdown ON -> verifies config + CRITICAL SECURITY audit log
        viewModel.toggleEmergencyLockdown(true)
        advanceUntilIdle()
        assertEquals(1, mockRepo.saveConfigCalls.size)
        val lockdownCall = mockRepo.saveConfigCalls[0]
        assertTrue(lockdownCall.config.emergencyLockdown)
        assertEquals("Enabled Emergency Lockdown", lockdownCall.auditAction)
        assertEquals(MaintenanceLogEntry.CATEGORY_SECURITY, lockdownCall.auditCategory)
        assertEquals(MaintenanceLogEntry.SEVERITY_CRITICAL, lockdownCall.auditSeverity)
        assertEquals("Emergency Lockdown enabled on server config.", viewModel.statusMessage.value)

        // Toggle Strict Tor ON -> verifies config + TOR_NETWORK audit log
        viewModel.toggleStrictTorRequired(true)
        advanceUntilIdle()
        assertEquals(2, mockRepo.saveConfigCalls.size)
        val torCall = mockRepo.saveConfigCalls[1]
        assertTrue(torCall.config.strictTorRequired)
        assertTrue(torCall.config.emergencyLockdown) // preserved prior state
        assertEquals(MaintenanceLogEntry.CATEGORY_TOR_NETWORK, torCall.auditCategory)
        assertEquals(MaintenanceLogEntry.SEVERITY_INFO, torCall.auditSeverity)

        // Toggle Global Ad-Block Rules Sync OFF
        viewModel.toggleForceAdBlockSync(false)
        advanceUntilIdle()
        assertFalse(mockRepo.saveConfigCalls[2].config.forceAdBlockSync)

        // Toggle Maintenance Mode ON -> verifies WARN/MAINTENANCE audit log
        viewModel.toggleMaintenanceMode(true)
        advanceUntilIdle()
        val maintCall = mockRepo.saveConfigCalls[3]
        assertTrue(maintCall.config.maintenanceMode)
        assertEquals(MaintenanceLogEntry.CATEGORY_MAINTENANCE, maintCall.auditCategory)
        assertEquals(MaintenanceLogEntry.SEVERITY_WARN, maintCall.auditSeverity)

        // Toggle Remote Telemetry OFF
        viewModel.toggleRemoteTelemetry(false)
        advanceUntilIdle()
        assertFalse(mockRepo.saveConfigCalls[4].config.remoteTelemetryEnabled)

        // Save Maintenance Banner with whitespace & length clamping
        val longBanner = "  " + "B".repeat(300) + "  "
        viewModel.saveMaintenanceBanner(longBanner)
        advanceUntilIdle()
        val bannerCall = mockRepo.saveConfigCalls[5]
        assertEquals(AdminSettingsRepository.MAX_BANNER_LENGTH, bannerCall.config.maintenanceBanner.length)
        assertEquals("Updated maintenance banner", bannerCall.auditAction)

        // Verify filteredLogs across CRITICAL and ALL
        viewModel.setSeverityFilter(MaintenanceLogEntry.SEVERITY_CRITICAL)
        advanceUntilIdle()
        assertEquals(1, viewModel.filteredLogs.value.size)
        assertEquals("Enabled Emergency Lockdown", viewModel.filteredLogs.value.first().action)

        viewModel.setSeverityFilter(AdminSettingsViewModel.FILTER_ALL)
        advanceUntilIdle()
        assertEquals(6, viewModel.filteredLogs.value.size)

        configCollector.cancel()
        logsCollector.cancel()
        filterCollector.cancel()
    }

    @Test
    fun `viewmodel coroutine timeout handling and isSaving reset on slow or failing mock calls`() = runWithMainTestDispatcher {
        val mockRepo = RecordingAdminSettingsMock().apply {
            simulatedWriteDelayMs = 2000L
        }
        val viewModel = AdminSettingsViewModel(
            repository = mockRepo,
            currentUserId = "admin_unit_uid",
        )

        val configCollector = viewModel.configUiState.launchIn(backgroundScope)
        advanceUntilIdle()

        // Start a slow server write and verify isSaving stays true before virtual 2000ms completes
        viewModel.toggleEmergencyLockdown(true)
        advanceTimeBy(500L)
        assertTrue(viewModel.isSaving.value)
        assertEquals(1, mockRepo.saveConfigCalls.size)

        // Timeout helper verifies bounded wait handles slow repository calls without blocking thread
        val timedOutResult = withTimeoutOrNull(500L) {
            viewModel.isSaving.first { !it }
        }
        assertNull(timedOutResult)

        // Advance remaining virtual time cleanly to complete the coroutine
        advanceTimeBy(1500L)
        advanceUntilIdle()
        assertFalse(viewModel.isSaving.value)
        assertTrue((viewModel.configUiState.value as AdminConfigUiState.Success).config.emergencyLockdown)

        // Now inject write failure and verify isSaving resets and error status surfaces
        mockRepo.simulatedWriteDelayMs = 0L
        mockRepo.saveConfigFailure = IllegalStateException("Firestore offline quota exceeded")
        viewModel.toggleMaintenanceMode(true)
        advanceUntilIdle()
        assertFalse(viewModel.isSaving.value)
        assertEquals("Firestore offline quota exceeded", viewModel.statusMessage.value)

        // Verify manual append & delete mock invocations and failure handling
        viewModel.appendMaintenanceLog(
            action = "Rotated Tor relays",
            category = MaintenanceLogEntry.CATEGORY_TOR_NETWORK,
            severity = MaintenanceLogEntry.SEVERITY_INFO,
            details = "All guard nodes healthy",
        )
        advanceUntilIdle()
        assertEquals(1, mockRepo.appendLogCalls.size)
        assertEquals("Maintenance log entry recorded.", viewModel.statusMessage.value)

        val createdId = mockRepo.logsFlow.value.entries.first().id
        mockRepo.deleteLogFailure = SecurityException("PERMISSION_DENIED: missing 'admin' claim")
        viewModel.deleteMaintenanceLog(createdId)
        advanceUntilIdle()
        assertEquals(listOf(createdId), mockRepo.deletedLogIds)
        assertEquals("PERMISSION_DENIED: missing 'admin' claim", viewModel.statusMessage.value)

        viewModel.clearStatusMessage()
        assertNull(viewModel.statusMessage.value)

        configCollector.cancel()
    }

    @Test
    fun `viewmodel surfaces firestore errors cleanly and propagates coroutine cancellation`() = runWithMainTestDispatcher {
        val mockRepo = RecordingAdminSettingsMock().apply {
            customConfigFlowOverride = flow {
                throw FirebaseFirestoreException(
                    "PERMISSION_DENIED",
                    FirebaseFirestoreException.Code.PERMISSION_DENIED,
                )
            }
            customLogsFlowOverride = flow {
                throw CancellationException("Upstream scope cancelled")
            }
        }
        val viewModel = AdminSettingsViewModel(
            repository = mockRepo,
            currentUserId = "admin_unit_uid",
        )

        val configCollector = viewModel.configUiState.launchIn(backgroundScope)
        advanceUntilIdle()

        val configState = viewModel.configUiState.value
        assertTrue(configState is AdminConfigUiState.Error)
        assertTrue((configState as AdminConfigUiState.Error).message.contains("PERMISSION_DENIED"))

        // Verify non-Firestore CancellationException is rethrown cleanly inside flow collection
        var logsCollectorError: Throwable? = null
        val logsJob = launch {
            try {
                viewModel.logsUiState.collect {}
            } catch (t: Throwable) {
                logsCollectorError = t
            }
        }
        advanceTimeBy(100L)
        // Standard CancellationException inside flow.catch should cancel/rethrow without mapping to Error UI state
        assertFalse(viewModel.logsUiState.value is MaintenanceLogsUiState.Error)
        logsJob.cancel()
        configCollector.cancel()
        assertNull(logsCollectorError)
    }

    @Test
    fun `custom admin claim verification enforces admin=true and rejects non-admin profiles`() = runBlocking {
        assertTrue(hasCustomAdminClaim(mapOf("admin" to true)))
        assertTrue(hasCustomAdminClaim(mapOf("admin" to "TRUE")))
        assertFalse(hasCustomAdminClaim(mapOf("admin" to false)))
        assertFalse(hasCustomAdminClaim(emptyMap()))
        assertTrue(evaluateAdminClaims(mapOf("admin" to true)).contains("admin=true"))

        authRule.signOut()
        assertEquals(AdminClaimStatus.SignedOut, authRule.authProvider.checkAdminClaims())

        authRule.signIn(uid = "non_admin_uid", email = "user@onionshield.test", claims = mapOf("admin" to false))
        val denied = authRule.authProvider.checkAdminClaims()
        assertTrue(denied is AdminClaimStatus.Denied)

        authRule.withAuthenticatedUser(
            uid = "claim_admin_uid",
            email = "secops@onionshield.test",
            claims = mapOf("admin" to true),
        ) {
            val authorized = authRule.authProvider.checkAdminClaims(forceRefresh = true)
            assertTrue(authorized is AdminClaimStatus.Authorized)
            assertTrue((authorized as AdminClaimStatus.Authorized).matchedClaims.contains("admin=true"))
        }
    }

    @Test
    fun `real admin settings repository handles offline timeouts and viewmodel toggles deterministically`(): Unit = runBlocking {
        val repo = authRule.createAdminSettingsRepository()

        authRule.signOut()
        assertTrue(repo.saveServerConfig(AdminServerConfig(emergencyLockdown = true)).isFailure)
        assertTrue(repo.appendMaintenanceLog("Blocked action").isFailure)
        assertTrue(repo.deleteMaintenanceLog("log_1").isFailure)

        authRule.withAuthenticatedUser(
            uid = "admin_ops_uid",
            email = "secops@onionshield.test",
            claims = mapOf("admin" to true),
        ) {
            // Blank action / invalid ID validation fails immediately without waiting for network timeout
            assertTrue(repo.appendMaintenanceLog("   ").isFailure)
            assertTrue(repo.deleteMaintenanceLog("!!!").isFailure)

            val saveRes = withTimeout(6000L) {
                repo.saveServerConfig(
                    config = AdminServerConfig(
                        emergencyLockdown = true,
                        strictTorRequired = true,
                        forceAdBlockSync = true,
                        maintenanceMode = false,
                        remoteTelemetryEnabled = false,
                        maintenanceBanner = "Strict privacy overrides active.",
                    ),
                    auditAction = "Enabled Privacy Overrides",
                    auditCategory = MaintenanceLogEntry.CATEGORY_SECURITY,
                    auditSeverity = MaintenanceLogEntry.SEVERITY_CRITICAL,
                    auditDetails = "Initial lockdown + strict Tor policy",
                )
            }
            assertTrue(saveRes.isSuccess)

            val viewModel = AdminSettingsViewModel(
                repository = repo,
                currentUserId = "admin_ops_uid",
            )

            val loadedConfig = withTimeout(5000L) {
                viewModel.configUiState.first { it is AdminConfigUiState.Success && it.existsRemotely }
            } as AdminConfigUiState.Success
            assertTrue(loadedConfig.config.emergencyLockdown)
            assertTrue(loadedConfig.config.strictTorRequired)

            viewModel.toggleEmergencyLockdown(false)
            val updatedConfig = withTimeout(6000L) {
                viewModel.configUiState.first {
                    it is AdminConfigUiState.Success && !it.config.emergencyLockdown
                }
            } as AdminConfigUiState.Success
            assertFalse(updatedConfig.config.emergencyLockdown)

            val updatedLogs = withTimeout(6000L) {
                viewModel.logsUiState.first { state ->
                    state is MaintenanceLogsUiState.Success &&
                        state.entries.any { it.action == "Enabled Privacy Overrides" } &&
                        state.entries.any { it.action == "Disabled Emergency Lockdown" }
                }
            } as MaintenanceLogsUiState.Success
            assertTrue(updatedLogs.entries.size >= 2)
        }
    }
}

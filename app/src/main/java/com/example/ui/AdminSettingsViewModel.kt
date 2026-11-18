package com.example.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.AdminServerConfig
import com.example.data.repository.AdminServerConfigSnapshot
import com.example.data.repository.AdminSettingsRepository
import com.example.data.repository.MaintenanceLogEntry
import com.example.data.repository.MaintenanceLogsSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AdminConfigUiState {
    data object Loading : AdminConfigUiState
    data class Success(
        val config: AdminServerConfig,
        val existsRemotely: Boolean = false,
        val isFromCache: Boolean = false,
        val hasPendingWrites: Boolean = false,
    ) : AdminConfigUiState

    data class Error(val message: String) : AdminConfigUiState
}

sealed interface MaintenanceLogsUiState {
    data object Loading : MaintenanceLogsUiState
    data class Success(
        val entries: List<MaintenanceLogEntry>,
        val isFromCache: Boolean = false,
        val hasPendingWrites: Boolean = false,
    ) : MaintenanceLogsUiState

    data class Error(val message: String) : MaintenanceLogsUiState
}

class AdminSettingsViewModel(
    private val repository: AdminSettingsRepository,
    private val currentUserId: String,
) : ViewModel() {

    val configUiState: StateFlow<AdminConfigUiState> = repository.observeServerConfig()
        .map<AdminServerConfigSnapshot, AdminConfigUiState> { snap ->
            AdminConfigUiState.Success(
                config = snap.config,
                existsRemotely = snap.existsRemotely,
                isFromCache = snap.isFromCache,
                hasPendingWrites = snap.hasPendingWrites,
            )
        }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing admin server config for $currentUserId", rootError)
            emit(AdminConfigUiState.Error(rootError.message ?: "Failed to load server configuration."))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = AdminConfigUiState.Loading,
        )

    val logsUiState: StateFlow<MaintenanceLogsUiState> = repository.observeMaintenanceLogs()
        .map<MaintenanceLogsSnapshot, MaintenanceLogsUiState> { snap ->
            MaintenanceLogsUiState.Success(
                entries = snap.entries,
                isFromCache = snap.isFromCache,
                hasPendingWrites = snap.hasPendingWrites,
            )
        }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing maintenance logs for $currentUserId", rootError)
            emit(MaintenanceLogsUiState.Error(rootError.message ?: "Failed to load maintenance logs."))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = MaintenanceLogsUiState.Loading,
        )

    private val _selectedSeverityFilter = MutableStateFlow(FILTER_ALL)
    val selectedSeverityFilter: StateFlow<String> = _selectedSeverityFilter.asStateFlow()

    val filteredLogs: StateFlow<List<MaintenanceLogEntry>> = combine(
        logsUiState,
        selectedSeverityFilter,
    ) { state, filter ->
        val entries = (state as? MaintenanceLogsUiState.Success)?.entries.orEmpty()
        if (filter == FILTER_ALL) entries else entries.filter { it.severity == filter }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList(),
    )

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun setSeverityFilter(severity: String) {
        _selectedSeverityFilter.value = severity
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun toggleMaintenanceMode(enabled: Boolean) {
        updateToggle(
            label = "Maintenance Mode",
            enabled = enabled,
            category = MaintenanceLogEntry.CATEGORY_MAINTENANCE,
            severity = if (enabled) MaintenanceLogEntry.SEVERITY_WARN else MaintenanceLogEntry.SEVERITY_INFO,
            details = if (enabled) "Server maintenance mode activated." else "Server maintenance mode deactivated.",
        ) { it.copy(maintenanceMode = enabled) }
    }

    fun toggleStrictTorRequired(enabled: Boolean) {
        updateToggle(
            label = "Strict Tor Routing",
            enabled = enabled,
            category = MaintenanceLogEntry.CATEGORY_TOR_NETWORK,
            severity = MaintenanceLogEntry.SEVERITY_INFO,
            details = if (enabled) "Global strict .onion enforcement enabled." else "Global strict .onion enforcement relaxed.",
        ) { it.copy(strictTorRequired = enabled) }
    }

    fun toggleRemoteTelemetry(enabled: Boolean) {
        updateToggle(
            label = "Remote Telemetry",
            enabled = enabled,
            category = MaintenanceLogEntry.CATEGORY_CONFIG,
            severity = MaintenanceLogEntry.SEVERITY_INFO,
            details = if (enabled) "Diagnostic telemetry collection enabled." else "Diagnostic telemetry collection paused.",
        ) { it.copy(remoteTelemetryEnabled = enabled) }
    }

    fun toggleForceAdBlockSync(enabled: Boolean) {
        updateToggle(
            label = "Global Ad-Block Rules Sync",
            enabled = enabled,
            category = MaintenanceLogEntry.CATEGORY_SECURITY,
            severity = MaintenanceLogEntry.SEVERITY_INFO,
            details = if (enabled) "Clients instructed to force-sync filter lists." else "Automatic ad-block filter push disabled.",
        ) { it.copy(forceAdBlockSync = enabled) }
    }

    fun toggleEmergencyLockdown(enabled: Boolean) {
        updateToggle(
            label = "Emergency Lockdown",
            enabled = enabled,
            category = MaintenanceLogEntry.CATEGORY_SECURITY,
            severity = if (enabled) MaintenanceLogEntry.SEVERITY_CRITICAL else MaintenanceLogEntry.SEVERITY_WARN,
            details = if (enabled) "Emergency maximum-privacy lockdown engaged." else "Emergency lockdown cleared.",
        ) { it.copy(emergencyLockdown = enabled) }
    }

    fun saveMaintenanceBanner(bannerText: String) {
        val baseConfig = (configUiState.value as? AdminConfigUiState.Success)?.config ?: AdminServerConfig()
        val trimmed = bannerText.trim().take(AdminSettingsRepository.MAX_BANNER_LENGTH)
        viewModelScope.launch {
            _isSaving.value = true
            val result = repository.saveServerConfig(
                config = baseConfig.copy(maintenanceBanner = trimmed),
                auditAction = if (trimmed.isBlank()) "Cleared maintenance banner" else "Updated maintenance banner",
                auditCategory = MaintenanceLogEntry.CATEGORY_MAINTENANCE,
                auditSeverity = MaintenanceLogEntry.SEVERITY_INFO,
                auditDetails = trimmed.ifBlank { "Banner text cleared." },
            )
            _isSaving.value = false
            _statusMessage.value = if (result.isSuccess) {
                "Server maintenance banner updated."
            } else {
                result.exceptionOrNull()?.localizedMessage ?: "Failed to update maintenance banner."
            }
        }
    }

    fun appendMaintenanceLog(
        action: String,
        category: String,
        severity: String,
        details: String,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            val result = repository.appendMaintenanceLog(
                action = action,
                category = category,
                severity = severity,
                details = details,
            )
            _isSaving.value = false
            _statusMessage.value = if (result.isSuccess) {
                "Maintenance log entry recorded."
            } else {
                result.exceptionOrNull()?.localizedMessage ?: "Failed to record maintenance log."
            }
        }
    }

    fun deleteMaintenanceLog(logId: String) {
        viewModelScope.launch {
            _isSaving.value = true
            val result = repository.deleteMaintenanceLog(logId)
            _isSaving.value = false
            _statusMessage.value = if (result.isSuccess) {
                "Maintenance log entry removed."
            } else {
                result.exceptionOrNull()?.localizedMessage ?: "Failed to delete maintenance log."
            }
        }
    }

    private fun updateToggle(
        label: String,
        enabled: Boolean,
        category: String,
        severity: String,
        details: String,
        transform: (AdminServerConfig) -> AdminServerConfig,
    ) {
        val current = (configUiState.value as? AdminConfigUiState.Success)?.config ?: AdminServerConfig()
        viewModelScope.launch {
            _isSaving.value = true
            val result = repository.saveServerConfig(
                config = transform(current),
                auditAction = "${if (enabled) "Enabled" else "Disabled"} $label",
                auditCategory = category,
                auditSeverity = severity,
                auditDetails = details,
            )
            _isSaving.value = false
            _statusMessage.value = if (result.isSuccess) {
                "$label ${if (enabled) "enabled" else "disabled"} on server config."
            } else {
                result.exceptionOrNull()?.localizedMessage ?: "Failed to update $label."
            }
        }
    }

    companion object {
        private const val TAG = "AdminSettingsVM"
        const val FILTER_ALL = "ALL"
    }
}

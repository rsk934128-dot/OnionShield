package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.privacyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "onion_shield_privacy_prefs"
)

class PrivacyPreferencesRepository(private val context: Context) {

    private object Keys {
        val BROWSER_MODE = stringPreferencesKey("browser_mode")
        val NO_SCRIPT = booleanPreferencesKey("no_script_enabled")
        val TRUSTED_JS_HOSTS = stringSetPreferencesKey("trusted_js_hosts")
        val FINGERPRINT_RANDOMIZATION = booleanPreferencesKey("fingerprint_randomization")
        val HTTPS_EVERYWHERE = booleanPreferencesKey("https_everywhere")
        val WEBRTC_LEAK_PROTECTION = booleanPreferencesKey("webrtc_leak_protection")
        val TRACKER_BLOCKER = booleanPreferencesKey("tracker_blocker")
        val POPUP_BLOCKER = booleanPreferencesKey("popup_blocker_enabled")
        val COSMETIC_AD_BLOCK = booleanPreferencesKey("cosmetic_ad_block_enabled")
        val AD_BLOCK_WHITELIST_HOSTS = stringSetPreferencesKey("ad_block_whitelist_hosts")
        val CUSTOM_BLOCKED_DOMAINS = stringSetPreferencesKey("custom_blocked_domains")
        val BLOCK_THIRD_PARTY_COOKIES = booleanPreferencesKey("block_third_party_cookies")
        val SAVE_SURFACE_HISTORY = booleanPreferencesKey("save_surface_history")
        val TOR_PROXY_HOST = stringPreferencesKey("tor_proxy_host")
        val TOR_PROXY_PORT = intPreferencesKey("tor_proxy_port")
        val TOR_PROXY_PROTOCOL = stringPreferencesKey("tor_proxy_protocol")
        val BRIDGE_TRANSPORT = stringPreferencesKey("bridge_transport")
        val CUSTOM_BRIDGE_LINES = stringPreferencesKey("custom_bridge_lines")
        val TOR_CONTROL_PORT = intPreferencesKey("tor_control_port")
        val TOR_CONTROL_PASSWORD = stringPreferencesKey("tor_control_password")
        val CIRCUIT_PRIVACY_LEVEL = stringPreferencesKey("circuit_privacy_level")
        val VAULT_PIN_SALT = stringPreferencesKey("vault_pin_salt")
        val VAULT_PIN_HASH = stringPreferencesKey("vault_pin_hash")
        val VAULT_WRAPPED_DEK = stringPreferencesKey("vault_wrapped_dek")
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
        val FORCE_DARK_THEME = booleanPreferencesKey("force_dark_theme")
        val SESSION_GUARD_ENABLED = booleanPreferencesKey("session_guard_enabled")
        val SESSION_TIME_LIMIT_MIN = intPreferencesKey("session_time_limit_min")
        val SESSION_BUDGET_LIMIT = doublePreferencesKey("session_budget_limit")
        val SESSION_CURRENCY_LABEL = stringPreferencesKey("session_currency_label")
        val LIVE_CLOUD_MONITOR_SYNC = booleanPreferencesKey("live_cloud_monitor_sync")
        val BACKGROUND_LIVE_SERVICE = booleanPreferencesKey("background_live_service")
        val BACKGROUND_MEDIA_PLAYBACK = booleanPreferencesKey("background_media_playback")
        val BIOMETRIC_APP_LOCK = booleanPreferencesKey("biometric_app_lock_enabled")
        val VPN_PROFILE = stringPreferencesKey("vpn_server_profile")
        val LAST_MEDIA_TITLE = stringPreferencesKey("last_media_title")
        val LAST_MEDIA_ARTIST = stringPreferencesKey("last_media_artist")
        val LAST_MEDIA_URL = stringPreferencesKey("last_media_url")
        val LAST_MEDIA_POSITION_MS = longPreferencesKey("last_media_position_ms")
        val LAST_MEDIA_DURATION_MS = longPreferencesKey("last_media_duration_ms")
    }

    val securityConfig: Flow<SecurityConfig> = context.privacyDataStore.data.map { prefs ->
        SecurityConfig(
            browserMode = prefs[Keys.BROWSER_MODE]?.let {
                runCatching { BrowserMode.valueOf(it) }.getOrNull()
            } ?: BrowserMode.SURFACE,
            noScriptEnabled = prefs[Keys.NO_SCRIPT] ?: false,
            trustedJsHosts = prefs[Keys.TRUSTED_JS_HOSTS] ?: emptySet(),
            fingerprintRandomizationEnabled = prefs[Keys.FINGERPRINT_RANDOMIZATION] ?: true,
            httpsEverywhereEnabled = prefs[Keys.HTTPS_EVERYWHERE] ?: true,
            webRtcLeakProtectionEnabled = prefs[Keys.WEBRTC_LEAK_PROTECTION] ?: true,
            trackerBlockerEnabled = prefs[Keys.TRACKER_BLOCKER] ?: true,
            popupBlockerEnabled = prefs[Keys.POPUP_BLOCKER] ?: true,
            cosmeticAdBlockEnabled = prefs[Keys.COSMETIC_AD_BLOCK] ?: true,
            adBlockWhitelistHosts = prefs[Keys.AD_BLOCK_WHITELIST_HOSTS] ?: emptySet(),
            customBlockedDomains = prefs[Keys.CUSTOM_BLOCKED_DOMAINS] ?: emptySet(),
            blockThirdPartyCookies = prefs[Keys.BLOCK_THIRD_PARTY_COOKIES] ?: true,
            saveSurfaceHistory = prefs[Keys.SAVE_SURFACE_HISTORY] ?: false,
            torProxyHost = prefs[Keys.TOR_PROXY_HOST] ?: "127.0.0.1",
            torProxyPort = prefs[Keys.TOR_PROXY_PORT] ?: 9050,
            torProxyProtocol = prefs[Keys.TOR_PROXY_PROTOCOL]?.let {
                runCatching { ProxyProtocol.valueOf(it) }.getOrNull()
            } ?: ProxyProtocol.SOCKS5,
            bridgeTransport = prefs[Keys.BRIDGE_TRANSPORT]?.let {
                runCatching { BridgeTransport.valueOf(it) }.getOrNull()
            } ?: BridgeTransport.DIRECT,
            customBridgeLines = prefs[Keys.CUSTOM_BRIDGE_LINES] ?: "",
            torControlPort = prefs[Keys.TOR_CONTROL_PORT] ?: 9051,
            torControlPassword = prefs[Keys.TOR_CONTROL_PASSWORD] ?: "",
            circuitPrivacyLevel = prefs[Keys.CIRCUIT_PRIVACY_LEVEL]?.let {
                runCatching { CircuitPrivacyLevel.valueOf(it) }.getOrNull()
            } ?: CircuitPrivacyLevel.STANDARD,
            searchEngine = prefs[Keys.SEARCH_ENGINE]?.let {
                runCatching { SearchEngine.valueOf(it) }.getOrNull()
            } ?: SearchEngine.DUCKDUCKGO,
            disclaimerAccepted = prefs[Keys.DISCLAIMER_ACCEPTED] ?: false,
            forceDarkWebTheme = prefs[Keys.FORCE_DARK_THEME] ?: true,
            sessionGuardEnabled = prefs[Keys.SESSION_GUARD_ENABLED] ?: false,
            sessionTimeLimitMinutes = prefs[Keys.SESSION_TIME_LIMIT_MIN] ?: 30,
            sessionBudgetLimit = prefs[Keys.SESSION_BUDGET_LIMIT] ?: 100.0,
            sessionCurrencyLabel = prefs[Keys.SESSION_CURRENCY_LABEL] ?: "BDT",
            liveCloudMonitorSyncEnabled = prefs[Keys.LIVE_CLOUD_MONITOR_SYNC] ?: false,
            backgroundLiveServiceEnabled = prefs[Keys.BACKGROUND_LIVE_SERVICE] ?: false,
            backgroundMediaPlaybackEnabled = prefs[Keys.BACKGROUND_MEDIA_PLAYBACK] ?: true,
            biometricAppLockEnabled = prefs[Keys.BIOMETRIC_APP_LOCK] ?: true,
            vpnProfile = prefs[Keys.VPN_PROFILE]?.let {
                runCatching { VpnServerProfile.valueOf(it) }.getOrNull()
            } ?: VpnServerProfile.CLOUDFLARE_WARP,
        )
    }

    val vaultPinConfigured: Flow<Boolean> = context.privacyDataStore.data.map { prefs ->
        !prefs[Keys.VAULT_PIN_HASH].isNullOrBlank() && !prefs[Keys.VAULT_PIN_SALT].isNullOrBlank()
    }

    val vaultPinCredentials: Flow<Pair<String, String>?> = context.privacyDataStore.data.map { prefs ->
        val salt = prefs[Keys.VAULT_PIN_SALT]
        val hash = prefs[Keys.VAULT_PIN_HASH]
        if (!salt.isNullOrBlank() && !hash.isNullOrBlank()) salt to hash else null
    }

    val vaultWrappedDek: Flow<String?> = context.privacyDataStore.data.map { prefs ->
        prefs[Keys.VAULT_WRAPPED_DEK]?.takeIf { it.isNotBlank() }
    }

    val persistedMediaState: Flow<PersistedMediaState> = context.privacyDataStore.data.map { prefs ->
        PersistedMediaState(
            title = prefs[Keys.LAST_MEDIA_TITLE] ?: "",
            artist = prefs[Keys.LAST_MEDIA_ARTIST] ?: "",
            url = prefs[Keys.LAST_MEDIA_URL] ?: "",
            positionMs = prefs[Keys.LAST_MEDIA_POSITION_MS] ?: 0L,
            durationMs = prefs[Keys.LAST_MEDIA_DURATION_MS] ?: 0L,
        )
    }

    suspend fun setBrowserMode(mode: BrowserMode) {
        context.privacyDataStore.edit { it[Keys.BROWSER_MODE] = mode.name }
    }

    suspend fun setNoScript(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.NO_SCRIPT] = enabled }
    }

    suspend fun addTrustedJsHost(host: String) {
        val clean = host.trim().lowercase()
        if (clean.isBlank()) return
        context.privacyDataStore.edit { prefs ->
            val current = prefs[Keys.TRUSTED_JS_HOSTS] ?: emptySet()
            prefs[Keys.TRUSTED_JS_HOSTS] = current + clean
        }
    }

    suspend fun removeTrustedJsHost(host: String) {
        val clean = host.trim().lowercase()
        if (clean.isBlank()) return
        context.privacyDataStore.edit { prefs ->
            val current = prefs[Keys.TRUSTED_JS_HOSTS] ?: emptySet()
            prefs[Keys.TRUSTED_JS_HOSTS] = current - clean
        }
    }

    suspend fun setFingerprintRandomization(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.FINGERPRINT_RANDOMIZATION] = enabled }
    }

    suspend fun setHttpsEverywhere(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.HTTPS_EVERYWHERE] = enabled }
    }

    suspend fun setWebRtcLeakProtection(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.WEBRTC_LEAK_PROTECTION] = enabled }
    }

    suspend fun setTrackerBlocker(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.TRACKER_BLOCKER] = enabled }
    }

    suspend fun setPopupBlocker(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.POPUP_BLOCKER] = enabled }
    }

    suspend fun setCosmeticAdBlock(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.COSMETIC_AD_BLOCK] = enabled }
    }

    suspend fun toggleAdBlockWhitelistHost(host: String) {
        val clean = host.trim().lowercase().removePrefix("www.")
        if (clean.isBlank()) return
        context.privacyDataStore.edit { prefs ->
            val current = prefs[Keys.AD_BLOCK_WHITELIST_HOSTS] ?: emptySet()
            prefs[Keys.AD_BLOCK_WHITELIST_HOSTS] = if (current.contains(clean)) {
                current - clean
            } else {
                current + clean
            }
        }
    }

    suspend fun addCustomBlockedDomain(domain: String) {
        val clean = domain.trim().lowercase()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')
            .removePrefix("www.")
        if (clean.isBlank() || !clean.contains('.')) return
        context.privacyDataStore.edit { prefs ->
            val current = prefs[Keys.CUSTOM_BLOCKED_DOMAINS] ?: emptySet()
            prefs[Keys.CUSTOM_BLOCKED_DOMAINS] = current + clean
        }
    }

    suspend fun removeCustomBlockedDomain(domain: String) {
        val clean = domain.trim().lowercase()
        context.privacyDataStore.edit { prefs ->
            val current = prefs[Keys.CUSTOM_BLOCKED_DOMAINS] ?: emptySet()
            prefs[Keys.CUSTOM_BLOCKED_DOMAINS] = current - clean
        }
    }

    suspend fun setBlockThirdPartyCookies(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.BLOCK_THIRD_PARTY_COOKIES] = enabled }
    }

    suspend fun setSaveSurfaceHistory(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.SAVE_SURFACE_HISTORY] = enabled }
    }

    suspend fun updateTorProxy(host: String, port: Int, protocol: ProxyProtocol) {
        context.privacyDataStore.edit {
            it[Keys.TOR_PROXY_HOST] = host.trim().ifBlank { "127.0.0.1" }
            it[Keys.TOR_PROXY_PORT] = port.coerceIn(1, 65535)
            it[Keys.TOR_PROXY_PROTOCOL] = protocol.name
        }
    }

    suspend fun setBridgeTransport(transport: BridgeTransport) {
        context.privacyDataStore.edit { it[Keys.BRIDGE_TRANSPORT] = transport.name }
    }

    suspend fun updateBridgeConfig(
        transport: BridgeTransport,
        customLines: String,
        controlPort: Int,
        controlPassword: String,
    ) {
        context.privacyDataStore.edit {
            it[Keys.BRIDGE_TRANSPORT] = transport.name
            it[Keys.CUSTOM_BRIDGE_LINES] = customLines.trim()
            it[Keys.TOR_CONTROL_PORT] = controlPort.coerceIn(1, 65535)
            it[Keys.TOR_CONTROL_PASSWORD] = controlPassword
        }
    }

    suspend fun setCircuitPrivacyLevel(level: CircuitPrivacyLevel) {
        context.privacyDataStore.edit {
            it[Keys.CIRCUIT_PRIVACY_LEVEL] = level.name
            if (level == CircuitPrivacyLevel.STRICT) {
                it[Keys.NO_SCRIPT] = true
                it[Keys.WEBRTC_LEAK_PROTECTION] = true
                it[Keys.HTTPS_EVERYWHERE] = true
                it[Keys.FINGERPRINT_RANDOMIZATION] = true
                it[Keys.TRACKER_BLOCKER] = true
                it[Keys.BLOCK_THIRD_PARTY_COOKIES] = true
                it[Keys.SAVE_SURFACE_HISTORY] = false
            }
        }
    }

    suspend fun applyPrivacyDefaults(maximumPrivacy: Boolean) {
        context.privacyDataStore.edit { prefs ->
            prefs[Keys.NO_SCRIPT] = maximumPrivacy
            prefs[Keys.WEBRTC_LEAK_PROTECTION] = true
            prefs[Keys.HTTPS_EVERYWHERE] = true
            prefs[Keys.FINGERPRINT_RANDOMIZATION] = true
            prefs[Keys.TRACKER_BLOCKER] = true
            prefs[Keys.POPUP_BLOCKER] = true
            prefs[Keys.COSMETIC_AD_BLOCK] = true
            prefs[Keys.BLOCK_THIRD_PARTY_COOKIES] = true
            prefs[Keys.SAVE_SURFACE_HISTORY] = false
            prefs[Keys.CIRCUIT_PRIVACY_LEVEL] = if (maximumPrivacy) {
                CircuitPrivacyLevel.STRICT.name
            } else {
                CircuitPrivacyLevel.STANDARD.name
            }
        }
    }

    suspend fun saveVaultPinCredentials(saltBase64: String, hashBase64: String) {
        context.privacyDataStore.edit {
            it[Keys.VAULT_PIN_SALT] = saltBase64
            it[Keys.VAULT_PIN_HASH] = hashBase64
        }
    }

    suspend fun saveVaultWrappedDek(envelope: String) {
        context.privacyDataStore.edit {
            it[Keys.VAULT_WRAPPED_DEK] = envelope
        }
    }

    suspend fun clearVaultWrappedDek() {
        context.privacyDataStore.edit {
            it.remove(Keys.VAULT_WRAPPED_DEK)
        }
    }

    suspend fun setSearchEngine(engine: SearchEngine) {
        context.privacyDataStore.edit { it[Keys.SEARCH_ENGINE] = engine.name }
    }

    suspend fun acceptDisclaimer() {
        context.privacyDataStore.edit { it[Keys.DISCLAIMER_ACCEPTED] = true }
    }

    suspend fun setForceDarkTheme(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.FORCE_DARK_THEME] = enabled }
    }

    suspend fun updateSessionGuardConfig(
        enabled: Boolean,
        timeLimitMinutes: Int,
        budgetLimit: Double,
        currencyLabel: String,
    ) {
        context.privacyDataStore.edit {
            it[Keys.SESSION_GUARD_ENABLED] = enabled
            it[Keys.SESSION_TIME_LIMIT_MIN] = timeLimitMinutes.coerceIn(5, 600)
            it[Keys.SESSION_BUDGET_LIMIT] = budgetLimit.coerceAtLeast(1.0)
            it[Keys.SESSION_CURRENCY_LABEL] = currencyLabel.trim().uppercase().take(6).ifBlank { "BDT" }
        }
    }

    suspend fun setLiveCloudMonitorSync(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.LIVE_CLOUD_MONITOR_SYNC] = enabled }
    }

    suspend fun setBackgroundLiveService(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.BACKGROUND_LIVE_SERVICE] = enabled }
    }

    suspend fun setBackgroundMediaPlayback(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.BACKGROUND_MEDIA_PLAYBACK] = enabled }
    }

    suspend fun setBiometricAppLockEnabled(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.BIOMETRIC_APP_LOCK] = enabled }
    }

    suspend fun setVpnProfile(profile: VpnServerProfile) {
        context.privacyDataStore.edit { it[Keys.VPN_PROFILE] = profile.name }
    }

    suspend fun savePersistedMediaState(state: PersistedMediaState) {
        context.privacyDataStore.edit {
            it[Keys.LAST_MEDIA_TITLE] = state.title.take(100)
            it[Keys.LAST_MEDIA_ARTIST] = state.artist.take(80)
            it[Keys.LAST_MEDIA_URL] = state.url.take(512)
            it[Keys.LAST_MEDIA_POSITION_MS] = state.positionMs.coerceAtLeast(0L)
            it[Keys.LAST_MEDIA_DURATION_MS] = state.durationMs.coerceAtLeast(0L)
        }
    }

    suspend fun clearPersistedMediaState() {
        context.privacyDataStore.edit {
            it.remove(Keys.LAST_MEDIA_TITLE)
            it.remove(Keys.LAST_MEDIA_ARTIST)
            it.remove(Keys.LAST_MEDIA_URL)
            it.remove(Keys.LAST_MEDIA_POSITION_MS)
            it.remove(Keys.LAST_MEDIA_DURATION_MS)
        }
    }
}

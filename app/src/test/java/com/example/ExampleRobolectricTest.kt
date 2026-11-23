package com.example

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.browser.LiveMonitorForegroundService
import com.example.browser.LiveSessionRuntime
import com.example.browser.PrivacyScripts
import com.example.browser.QrCodeUrlParser
import com.example.browser.QrScanOutcome
import com.example.browser.TorCircuitService
import com.example.browser.TrackerAndUrlEngine
import com.example.data.ABOUT_START_URL
import com.example.data.BrowserMode
import com.example.data.CircuitNodeRole
import com.example.data.CircuitPrivacyLevel
import com.example.data.OnionAddressStatus
import com.example.data.OnionShieldDatabase
import com.example.data.SearchEngine
import com.example.data.repository.EncryptedSearchHistoryRepository
import com.example.security.VaultCryptoManager
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("OnionShield", appName)
    }

    @Test
    fun `https everywhere upgrades clearnet http but preserves onion`() {
        val clearnet = TrackerAndUrlEngine.resolveInputToUrl(
            rawInput = "http://example.org",
            mode = BrowserMode.SURFACE,
            httpsEverywhere = true,
            searchEngine = SearchEngine.DUCKDUCKGO,
        )
        assertEquals("https://example.org", clearnet)

        val onion = TrackerAndUrlEngine.resolveInputToUrl(
            rawInput = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion",
            mode = BrowserMode.TOR_ONION,
            httpsEverywhere = true,
            searchEngine = SearchEngine.DUCKDUCKGO,
        )
        assertTrue(onion.startsWith("http://"))
        assertTrue(TrackerAndUrlEngine.isOnionUrl(onion))
    }

    @Test
    fun `unsafe schemes are rejected to start page`() {
        val jsScheme = TrackerAndUrlEngine.resolveInputToUrl(
            rawInput = "javascript:alert(1)",
            mode = BrowserMode.SURFACE,
            httpsEverywhere = true,
            searchEngine = SearchEngine.DUCKDUCKGO,
        )
        assertEquals(ABOUT_START_URL, jsScheme)
    }

    @Test
    fun `tracker blocker detects analytics and allows normal sites`() {
        assertTrue(TrackerAndUrlEngine.isTrackerRequest("https://www.google-analytics.com/analytics.js"))
        assertTrue(TrackerAndUrlEngine.isTrackerRequest("https://pixel.facebook.com/tr"))
        assertFalse(TrackerAndUrlEngine.isTrackerRequest("https://en.wikipedia.org/wiki/Tor"))
    }

    @Test
    fun `privacy script includes webrtc guard and fingerprint noise`() {
        val script = PrivacyScripts.buildHardeningScript(
            seed = "A1B2C3D4",
            enableWebRtcGuard = true,
            enableFingerprintRandomization = true,
        )
        assertTrue(script.contains("RTCPeerConnection"))
        assertTrue(script.contains("OnionShield Virtual GPU (A1B2C3D4)"))
    }

    @Test
    fun `v3 onion validator distinguishes 56 char v3 from 16 char v2`() {
        assertEquals(
            OnionAddressStatus.V3_VERIFIED,
            TorCircuitService.classifyUrl("http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"),
        )
        assertEquals(
            OnionAddressStatus.V2_DEPRECATED,
            TorCircuitService.classifyUrl("http://3g2upl4pq6kufc4m.onion"),
        )
        assertEquals(
            OnionAddressStatus.CLEARNET_HTTPS,
            TorCircuitService.classifyUrl("https://www.torproject.org"),
        )
    }

    @Test
    fun `obfs4 bridge line validator accepts valid line and rejects malformed lines`() {
        val valid = TorCircuitService.validateBridgeLines(
            "Bridge obfs4 192.0.2.10:443 0123456789ABCDEF0123456789ABCDEF01234567 cert=abcd1234 iat-mode=0",
        )
        assertTrue(valid.isValid)
        assertEquals(1, valid.normalizedLines.size)
        assertTrue(valid.normalizedLines.first().startsWith("obfs4 "))

        val invalid = TorCircuitService.validateBridgeLines("obfs4 192.0.2.10:443 SHORTFP cert=abc")
        assertFalse(invalid.isValid)
    }

    @Test
    fun `control port circuit status parser extracts guard middle exit hops`() {
        val sample = """
            250+circuit-status=
            14 BUILT ${'$'}AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA~AlphaGuard,${'$'}BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB~BetaMid,${'$'}CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC~GammaExit BUILD_FLAGS=NEED_CAPACITY PURPOSE=GENERAL
            .
            250 OK
        """.trimIndent()
        val parsed = TorCircuitService.parseCircuitStatusResponse(sample)
        assertNotNull(parsed)
        assertEquals("14", parsed!!.first)
        assertEquals(3, parsed.second.size)
        assertEquals(CircuitNodeRole.GUARD, parsed.second[0].role)
        assertEquals(CircuitNodeRole.MIDDLE, parsed.second[1].role)
        assertEquals(CircuitNodeRole.EXIT, parsed.second[2].role)
    }

    @Test
    fun `relay descriptor parser extracts ip orPort and flags from ns id response`() {
        val nsLines = listOf(
            "250+ns/id/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "r AlphaGuard qqqqqqqqqqqqqqqqqqqqqqqqqqq rrrrrrrrrrrrrrrrrrrrrrrrrrr 2026-11-15 08:00:00 198.51.100.42 9001 9030",
            "s Fast Guard Running Stable Valid",
            ".",
            "250 OK",
        )
        val summary = TorCircuitService.parseRelayDescriptorResponse(nsLines)
        assertNotNull(summary)
        assertEquals("AlphaGuard", summary!!.nickname)
        assertEquals("198.51.100.42", summary.ipAddress)
        assertEquals(9001, summary.orPort)
        assertTrue(summary.flags.contains("Guard"))
    }

    @Test
    fun `circuit graph normalizer always yields guard middle exit hops with latencies`() {
        val normalized = com.example.ui.components.normalizeThreeHops(
            hops = emptyList(),
            isConnected = true,
            proxyLatencyMs = 14L,
            controllerLatencyMs = 28L,
            exitCheckLatencyMs = 340L,
        )
        assertEquals(3, normalized.size)
        assertEquals(CircuitNodeRole.GUARD, normalized[0].role)
        assertEquals(14L, normalized[0].latencyMs)
        assertEquals(CircuitNodeRole.MIDDLE, normalized[1].role)
        assertEquals(28L, normalized[1].latencyMs)
        assertEquals(CircuitNodeRole.EXIT, normalized[2].role)
        assertEquals(340L, normalized[2].latencyMs)
    }

    @Test
    fun `vault crypto manager encrypts decrypts aes256 gcm and verifies pbkdf2 pin`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = VaultCryptoManager.getOrCreateVaultKey(context)
        val secret = "onion-secret-key-9050"
        val sealed = VaultCryptoManager.encrypt(secret, key)
        assertTrue(sealed.startsWith("v1:"))
        assertFalse(sealed.contains(secret))
        assertEquals(secret, VaultCryptoManager.decrypt(sealed, key))

        val salt = VaultCryptoManager.generateSaltBase64()
        val hash = VaultCryptoManager.hashPassphrase("7391", salt)
        assertTrue(VaultCryptoManager.verifyPassphrase("7391", salt, hash))
        assertFalse(VaultCryptoManager.verifyPassphrase("0000", salt, hash))
    }

    @Test
    fun `circuit privacy level strict uses faster poll interval than standard`() {
        assertTrue(CircuitPrivacyLevel.STRICT.livePollIntervalMs < CircuitPrivacyLevel.STANDARD.livePollIntervalMs)
    }

    @Test
    fun `host normalization and intent fallback url parser work for modern links`() {
        assertEquals("youtube.com", TrackerAndUrlEngine.normalizeHost("https://www.youtube.com/watch?v=abc"))
        assertEquals("github.com", TrackerAndUrlEngine.normalizeHost("https://github.com/rsk934128-dot"))

        val intentUri = "intent://watch?v=3a9pHxp#Intent;scheme=https;package=com.google.android.youtube;S.browser_fallback_url=https%3A%2F%2Fm.youtube.com%2Fwatch%3Fv%3D3a9pHxp;end"
        assertEquals(
            "https://m.youtube.com/watch?v=3a9pHxp",
            TrackerAndUrlEngine.extractIntentFallbackUrl(intentUri),
        )
    }

    @Test
    fun `surface mode user agent profile is mobile compatible and shortcut title falls back cleanly`() {
        val surfaceProfile = PrivacyScripts.profileForSeed("ABCD1234", BrowserMode.SURFACE)
        assertTrue(surfaceProfile.userAgent.contains("Android"))
        assertTrue(surfaceProfile.userAgent.contains("Mobile"))

        assertEquals(
            "Youtube",
            com.example.browser.HomeAppShortcutHelper.deriveDisplayTitle("", "https://m.youtube.com/watch?v=123"),
        )
        assertEquals(
            "YouTube",
            com.example.browser.HomeAppShortcutHelper.deriveDisplayTitle("YouTube - Music Video", "https://m.youtube.com"),
        )
    }

    @Test
    fun `biometric wrapped vault dek encrypts and decrypts vault entries only after unwrap`() {
        val kekBytes = ByteArray(32) { (it + 7).toByte() }
        val kek = javax.crypto.spec.SecretKeySpec(kekBytes, "AES")

        val encCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, kek)
        }
        val (dek, wrappedEnvelope) = VaultCryptoManager.generateAndWrapVaultDek(encCipher)
        assertTrue(wrappedEnvelope.startsWith("bio1:"))

        val sealedSecret = VaultCryptoManager.encrypt("onion-private-token", dek)
        assertTrue(sealedSecret.startsWith("v1:"))

        // Decrypting with wrong key must fail
        val wrongKey = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 1 }, "AES")
        val failedWrongKey = runCatching { VaultCryptoManager.decrypt(sealedSecret, wrongKey) }.isFailure
        assertTrue(failedWrongKey)

        // Unwrapping DEK with authenticated cipher recovers exact Vault key
        val ivB64 = wrappedEnvelope.split(':')[1]
        val iv = android.util.Base64.decode(ivB64, android.util.Base64.NO_WRAP)
        val decCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.DECRYPT_MODE, kek, javax.crypto.spec.GCMParameterSpec(128, iv))
        }
        val unwrappedDek = VaultCryptoManager.unwrapVaultDek(decCipher, wrappedEnvelope)
        assertEquals("onion-private-token", VaultCryptoManager.decrypt(sealedSecret, unwrappedDek))
    }

    @Test
    fun `extractFirstHttpUrl parses shared link from external message text`() {
        val shared = "Check this app release: https://github.com/rsk934128-dot/OmniSafe-Browser/releases/latest, very cool!"
        assertEquals(
            "https://github.com/rsk934128-dot/OmniSafe-Browser/releases/latest",
            TrackerAndUrlEngine.extractFirstHttpUrl(shared),
        )
        assertEquals(null, TrackerAndUrlEngine.extractFirstHttpUrl("no url here"))
    }

    @Test
    fun `isApkAsset detects apk packages by mime filename or url path`() {
        assertTrue(
            TrackerAndUrlEngine.isApkAsset(
                url = "https://github.com/rsk934128-dot/OmniSafe-Browser/releases/download/v1.0/omnisafe.apk?raw=true",
                fileName = null,
                mimeType = null,
            ),
        )
        assertTrue(
            TrackerAndUrlEngine.isApkAsset(
                url = "https://example.org/download?id=99",
                fileName = "app-release.apk",
                mimeType = "application/octet-stream",
            ),
        )
        assertTrue(
            TrackerAndUrlEngine.isApkAsset(
                url = "https://example.org/file",
                fileName = "package.bin",
                mimeType = "application/vnd.android.package-archive",
            ),
        )
        assertFalse(
            TrackerAndUrlEngine.isApkAsset(
                url = "https://example.org/manual.pdf",
                fileName = "manual.pdf",
                mimeType = "application/pdf",
            ),
        )
    }

    @Test
    fun `session guard engine computes net loss alert and bucket distribution accurately`() {
        val rounds = listOf(
            com.example.data.SessionRoundLogEntity(
                siteHost = "example.org",
                betAmount = 50.0,
                crashMultiplier = 1.20,
                cashoutMultiplier = null,
                netProfit = -50.0,
            ),
            com.example.data.SessionRoundLogEntity(
                siteHost = "example.org",
                betAmount = 40.0,
                crashMultiplier = 5.71,
                cashoutMultiplier = 2.0,
                netProfit = 40.0,
            ),
            com.example.data.SessionRoundLogEntity(
                siteHost = "example.org",
                betAmount = 100.0,
                crashMultiplier = 12.50,
                cashoutMultiplier = null,
                netProfit = -100.0,
            ),
        )
        val stats = com.example.browser.SessionGuardEngine.computeSessionStats(
            rounds = rounds,
            guardEnabled = true,
            elapsedMinutes = 15L,
            timeLimitMinutes = 30,
            budgetLimit = 100.0,
        )
        assertEquals(3, stats.totalRounds)
        assertEquals(1, stats.winsCount)
        assertEquals(-110.0, stats.netProfit, 0.001)
        assertEquals(12.50, stats.maxCrashMultiplier, 0.001)
        assertEquals(com.example.browser.GuardAlertLevel.SAFE, stats.timeAlertLevel)
        assertEquals(com.example.browser.GuardAlertLevel.LIMIT_REACHED, stats.budgetAlertLevel)
        assertEquals(com.example.browser.GuardAlertLevel.LIMIT_REACHED, stats.overallAlertLevel)
        assertEquals(1, stats.buckets[0].count) // 1.0–1.49x
        assertEquals(1, stats.buckets[2].count) // 3.0–9.99x
        assertEquals(1, stats.buckets[3].count) // 10x+
    }

    @Test
    fun `provably fair sha256 and hmac verifier checks post-round commitment`() {
        val serverSeed = "demo_revealed_server_seed_2026"
        val expectedHash = com.example.browser.SessionGuardEngine.sha256Hex(serverSeed)
        val validResult = com.example.browser.SessionGuardEngine.verifyCrashFairness(
            serverSeed = serverSeed,
            clientSeed = "client_seed_abc",
            nonce = "42",
            expectedServerSeedHash = expectedHash,
        )
        assertTrue(validResult.isValidInput)
        assertEquals(true, validResult.commitmentMatched)
        assertTrue(validResult.derivedMultiplier >= 1.00)

        val tamperedResult = com.example.browser.SessionGuardEngine.verifyCrashFairness(
            serverSeed = serverSeed,
            clientSeed = "client_seed_abc",
            nonce = "42",
            expectedServerSeedHash = "0000000000000000",
        )
        assertEquals(false, tamperedResult.commitmentMatched)
    }

    @Test
    fun `live session runtime redacts onion hosts and refreshes elapsed timer`() {
        val now = 1_700_000_000_000L
        LiveSessionRuntime.resetTimer(now)
        assertEquals(0L, LiveSessionRuntime.elapsedMinutes.value)

        val elapsed = LiveSessionRuntime.refreshElapsed(now + 12 * 60_000L)
        assertEquals(12L, elapsed)
        assertEquals(12L, LiveSessionRuntime.elapsedMinutes.value)

        LiveSessionRuntime.updateActiveSiteHost("https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/search")
        assertEquals("onion_redacted", LiveSessionRuntime.activeSiteHost.value)

        LiveSessionRuntime.updateActiveSiteHost("https://example.org/app")
        assertEquals("example.org", LiveSessionRuntime.activeSiteHost.value)
    }

    @Test
    fun `foreground live monitor service enters foreground and stops cleanly on ACTION_STOP`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val startIntent = Intent(context, LiveMonitorForegroundService::class.java)
            .setAction(LiveMonitorForegroundService.ACTION_START)
        val controller = Robolectric.buildService(LiveMonitorForegroundService::class.java, startIntent)
            .create()
            .startCommand(0, 1)
        val service = controller.get()

        assertTrue(LiveSessionRuntime.isForegroundServiceRunning.value)
        val shadowService = Shadows.shadowOf(service)
        assertNotNull(shadowService.lastForegroundNotification)
        assertEquals(LiveMonitorForegroundService.NOTIFICATION_ID, shadowService.lastForegroundNotificationId)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = notificationManager.getNotificationChannel(LiveMonitorForegroundService.CHANNEL_ID)
        assertNotNull(channel)

        val stopIntent = Intent(context, LiveMonitorForegroundService::class.java)
            .setAction(LiveMonitorForegroundService.ACTION_STOP)
        service.onStartCommand(stopIntent, 0, 2)

        assertFalse(LiveSessionRuntime.isForegroundServiceRunning.value)
        assertTrue(shadowService.isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun `foreground live monitor notification includes Tor connectivity status and New Circuit action in Tor mode`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val startIntent = Intent(context, LiveMonitorForegroundService::class.java)
            .setAction(LiveMonitorForegroundService.ACTION_START)
        val controller = Robolectric.buildService(LiveMonitorForegroundService::class.java, startIntent)
            .create()
        val service = controller.get()

        val torConfig = com.example.data.SecurityConfig(
            browserMode = BrowserMode.TOR_ONION,
            torProxyHost = "127.0.0.1",
            torProxyPort = 9050,
        )
        val torNotification = service.buildNotification(
            stats = com.example.browser.SessionGuardStats(elapsedMinutes = 7L, totalRounds = 2),
            config = torConfig,
            syncStatus = com.example.browser.SessionMonitorSyncStatus.Disabled,
            torState = com.example.data.TorProxyState.Connected("socks5://127.0.0.1:9050", 14L),
        )
        val shadowNotif = Shadows.shadowOf(torNotification)
        assertEquals(context.getString(R.string.live_monitor_notif_tor_title), shadowNotif.contentTitle.toString())
        assertTrue(shadowNotif.contentText.toString().contains("Tor Connected (14ms)"))
        assertEquals(2, torNotification.actions.size)
        assertEquals(context.getString(R.string.live_monitor_stop_action), torNotification.actions[0].title.toString())
        assertEquals(context.getString(R.string.live_monitor_new_circuit_action), torNotification.actions[1].title.toString())

        val surfaceNotification = service.buildNotification(
            stats = com.example.browser.SessionGuardStats(elapsedMinutes = 3L),
            config = com.example.data.SecurityConfig(browserMode = BrowserMode.SURFACE),
            syncStatus = com.example.browser.SessionMonitorSyncStatus.Disabled,
            torState = com.example.data.TorProxyState.Inactive,
        )
        assertEquals(1, surfaceNotification.actions.size)
        controller.destroy()
    }

    @Test
    fun `ad blocker detects ad networks path rules custom domains and respects site whitelist`() {
        assertTrue(TrackerAndUrlEngine.isAdOrTrackerRequest("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(TrackerAndUrlEngine.isAdOrTrackerRequest("https://propellerads.com/tag.min.js"))
        assertTrue(TrackerAndUrlEngine.isAdOrTrackerRequest("https://mc.yandex.ru/metrika/tag.js"))
        assertTrue(TrackerAndUrlEngine.isAdOrTrackerRequest("https://www.tiktok.com/i18n/pixel/events.js"))
        assertTrue(
            TrackerAndUrlEngine.isAdOrTrackerRequest(
                url = "https://cdn.thirdparty.net/pagead/banner.js",
                pageUrl = "https://news.example.org/article",
            ),
        )
        assertTrue(
            TrackerAndUrlEngine.isAdOrTrackerRequest(
                url = "https://annoying-promo.example.net/banner.png",
                pageUrl = "https://news.example.org/article",
                customBlockedDomains = setOf("annoying-promo.example.net"),
            ),
        )
        assertFalse(
            TrackerAndUrlEngine.isAdOrTrackerRequest(
                url = "https://doubleclick.net/pixel.gif",
                pageUrl = "https://trusted.example.org/home",
                whitelistHosts = setOf("trusted.example.org"),
            ),
        )

        assertTrue(TrackerAndUrlEngine.isPopupAdUrl("https://popads.net/pop.js"))
        assertTrue(TrackerAndUrlEngine.isPopupAdUrl("https://promo.example.com/popunder?id=1"))
        assertFalse(TrackerAndUrlEngine.isPopupAdUrl("https://en.wikipedia.org/wiki/Tor_(network)"))

        assertEquals(
            "application/javascript",
            TrackerAndUrlEngine.emptyBlockedResponseForUrl("https://doubleclick.net/ads.js").mimeType,
        )
    }

    @Test
    fun `privacy scripts generate cosmetic adblock css and popup guard when enabled`() {
        val script = PrivacyScripts.buildHardeningScript(
            seed = "TESTSEED",
            enableWebRtcGuard = false,
            enableFingerprintRandomization = false,
            enableCosmeticAdBlock = true,
            enablePopupGuard = true,
        )
        assertTrue(script.contains("os-cosmetic-adblock"))
        assertTrue(script.contains("adsbygoogle"))
        assertTrue(script.contains("__osPopupGuardInstalled"))
    }

    @Test
    fun `privacy preferences repository toggles javascript webrtc https and applies defaults`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.data.PrivacyPreferencesRepository(context)

        repo.setNoScript(true)
        repo.setWebRtcLeakProtection(false)
        repo.setHttpsEverywhere(false)
        repo.addTrustedJsHost("example.org")

        var current = repo.securityConfig.first()
        assertTrue(current.noScriptEnabled)
        assertFalse(current.webRtcLeakProtectionEnabled)
        assertFalse(current.httpsEverywhereEnabled)
        assertTrue(current.trustedJsHosts.contains("example.org"))

        repo.removeTrustedJsHost("example.org")
        current = repo.securityConfig.first()
        assertFalse(current.trustedJsHosts.contains("example.org"))

        repo.applyPrivacyDefaults(maximumPrivacy = true)
        current = repo.securityConfig.first()
        assertTrue(current.noScriptEnabled)
        assertTrue(current.webRtcLeakProtectionEnabled)
        assertTrue(current.httpsEverywhereEnabled)
        assertEquals(CircuitPrivacyLevel.STRICT, current.circuitPrivacyLevel)

        repo.applyPrivacyDefaults(maximumPrivacy = false)
        current = repo.securityConfig.first()
        assertFalse(current.noScriptEnabled)
        assertTrue(current.webRtcLeakProtectionEnabled)
        assertTrue(current.httpsEverywhereEnabled)
        assertEquals(CircuitPrivacyLevel.STANDARD, current.circuitPrivacyLevel)
    }

    @Test
    fun `resolveDefaultWebClientId handles both configured and unconfigured builds safely`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        val resolved = com.example.ui.screens.resolveDefaultWebClientId(context)
        if (resId != 0) {
            assertEquals(context.getString(resId), resolved)
        } else {
            assertEquals(null, resolved)
        }
    }

    @Test
    fun `admin security score reflects active hardening and lockdown toggles accurately`() {
        val lockdown = com.example.data.SecurityConfig(
            noScriptEnabled = true,
            httpsEverywhereEnabled = true,
            webRtcLeakProtectionEnabled = true,
            trackerBlockerEnabled = true,
            popupBlockerEnabled = true,
            cosmeticAdBlockEnabled = true,
            fingerprintRandomizationEnabled = true,
            blockThirdPartyCookies = true,
            saveSurfaceHistory = false,
        )
        assertEquals(100, com.example.ui.components.computeAdminSecurityScore(lockdown))

        val standard = lockdown.copy(noScriptEnabled = false)
        assertEquals(90, com.example.ui.components.computeAdminSecurityScore(standard))

        val exposed = com.example.data.SecurityConfig(
            noScriptEnabled = false,
            httpsEverywhereEnabled = false,
            webRtcLeakProtectionEnabled = false,
            trackerBlockerEnabled = false,
            popupBlockerEnabled = false,
            cosmeticAdBlockEnabled = false,
            fingerprintRandomizationEnabled = false,
            blockThirdPartyCookies = false,
            saveSurfaceHistory = true,
        )
        assertEquals(0, com.example.ui.components.computeAdminSecurityScore(exposed))
    }

    @Test
    fun `privacy scripts inject background media visibility guard when enabled`() {
        val enabledScript = PrivacyScripts.buildHardeningScript(
            seed = "MEDIA123",
            enableWebRtcGuard = false,
            enableFingerprintRandomization = false,
            enableCosmeticAdBlock = false,
            enablePopupGuard = false,
            enableBackgroundMediaPlayback = true,
        )
        assertTrue(enabledScript.contains("__osBgMediaGuardInstalled"))
        assertTrue(enabledScript.contains("visibilityState"))
        assertTrue(enabledScript.contains("visibilitychange"))
        assertTrue(enabledScript.contains("__osBgMediaResumeIfNeeded"))
        assertTrue(enabledScript.contains("__osBgMediaPauseAll"))

        val disabledScript = PrivacyScripts.buildHardeningScript(
            seed = "MEDIA123",
            enableWebRtcGuard = false,
            enableFingerprintRandomization = false,
            enableCosmeticAdBlock = false,
            enablePopupGuard = false,
            enableBackgroundMediaPlayback = false,
        )
        assertEquals("", disabledScript)
    }

    @Test
    fun `foreground service notification renders media playback state and persists background media preference`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = Robolectric.buildService(
            LiveMonitorForegroundService::class.java,
            Intent(context, LiveMonitorForegroundService::class.java).setAction(LiveMonitorForegroundService.ACTION_START),
        ).create()
        val service = controller.get()

        val mediaNotif = service.buildNotification(
            stats = com.example.browser.SessionGuardStats(elapsedMinutes = 4L),
            config = com.example.data.SecurityConfig(browserMode = BrowserMode.SURFACE),
            syncStatus = com.example.browser.SessionMonitorSyncStatus.Disabled,
            torState = com.example.data.TorProxyState.Inactive,
            isMediaActive = true,
            mediaTitle = "Lo-Fi Study Mix - YouTube",
        )
        val shadowMediaNotif = Shadows.shadowOf(mediaNotif)
        assertEquals(context.getString(R.string.live_monitor_notif_media_title), shadowMediaNotif.contentTitle.toString())
        assertTrue(shadowMediaNotif.contentText.toString().contains("Lo-Fi Study Mix - YouTube"))
        assertEquals(2, mediaNotif.actions.size)
        assertEquals(context.getString(R.string.live_monitor_pause_media_action), mediaNotif.actions[1].title.toString())

        val repo = com.example.data.PrivacyPreferencesRepository(context)
        assertTrue(repo.securityConfig.first().backgroundMediaPlaybackEnabled)
        repo.setBackgroundMediaPlayback(false)
        assertFalse(repo.securityConfig.first().backgroundMediaPlaybackEnabled)
        repo.setBackgroundMediaPlayback(true)
        assertTrue(repo.securityConfig.first().backgroundMediaPlaybackEnabled)
        controller.destroy()
    }

    @Test
    fun `foreground service acquires and releases PartialWakeLock and WifiLock cleanly`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val startIntent = Intent(context, LiveMonitorForegroundService::class.java)
            .setAction(LiveMonitorForegroundService.ACTION_START)
        val controller = Robolectric.buildService(LiveMonitorForegroundService::class.java, startIntent)
            .create()
            .startCommand(0, 0)
        val service = controller.get()

        assertTrue(service.isPartialWakeLockHeld())
        assertTrue(service.isWifiLockHeld())
        assertTrue(LiveSessionRuntime.isForegroundServiceRunning.value)
        assertTrue(LiveSessionRuntime.arePowerLocksHeld.value)

        // Repeated start / circuit refresh remains idempotent without reference-count leak
        service.onStartCommand(
            Intent(context, LiveMonitorForegroundService::class.java)
                .setAction(LiveMonitorForegroundService.ACTION_NEW_CIRCUIT),
            0,
            1,
        )
        assertTrue(service.isPartialWakeLockHeld())
        assertTrue(service.isWifiLockHeld())

        // Stopping via ACTION_STOP releases both locks cleanly
        service.onStartCommand(
            Intent(context, LiveMonitorForegroundService::class.java)
                .setAction(LiveMonitorForegroundService.ACTION_STOP),
            0,
            2,
        )
        assertFalse(service.isPartialWakeLockHeld())
        assertFalse(service.isWifiLockHeld())
        assertFalse(LiveSessionRuntime.arePowerLocksHeld.value)

        // Re-acquiring then destroying the service also releases both locks
        service.onStartCommand(startIntent, 0, 3)
        assertTrue(service.isPartialWakeLockHeld())
        assertTrue(service.isWifiLockHeld())

        controller.destroy()
        assertFalse(service.isPartialWakeLockHeld())
        assertFalse(service.isWifiLockHeld())
        assertFalse(LiveSessionRuntime.arePowerLocksHeld.value)
    }

    @Test
    fun `encrypted search history stores ciphertext in Room, deduplicates via HMAC, and decrypts for omnibox`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, OnionShieldDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.searchHistoryDao()
            val repo = EncryptedSearchHistoryRepository(context = context, searchHistoryDao = dao)

            val query1 = "privacy browser test"
            val url1 = "https://duckduckgo.com/?q=privacy+browser+test"
            repo.recordSearch(rawQuery = query1, resolvedUrl = url1, isOnion = false, timestampMs = 1000L)

            val query2 = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"
            val url2 = "http://$query2"
            repo.recordSearch(rawQuery = query2, resolvedUrl = url2, isOnion = true, timestampMs = 2000L)

            // Verify raw Room entity stores ONLY ciphertext & HMAC hash (zero plaintext leakage)
            val rawRows = dao.observeRecent(10).first()
            assertEquals(2, rawRows.size)
            rawRows.forEach { entity ->
                assertTrue(entity.queryCiphertext.startsWith("v1:"))
                assertTrue(entity.resolvedUrlCiphertext.startsWith("v1:"))
                assertFalse(entity.queryCiphertext.contains("privacy"))
                assertFalse(entity.resolvedUrlCiphertext.contains("duckduckgo"))
                assertFalse(entity.queryHash.contains("privacy"))
                assertEquals(64, entity.queryHash.length)
            }

            // Re-searching same query deduplicates via HMAC and bumps timestamp
            repo.recordSearch(rawQuery = "  PRIVACY browser test  ", resolvedUrl = url1, isOnion = false, timestampMs = 5000L)
            val decrypted = repo.observeRecentSearches(10).first()
            assertEquals(2, decrypted.size)
            assertEquals("PRIVACY browser test", decrypted.first().query)
            assertEquals(5000L, decrypted.first().lastSearchedAt)
            assertEquals(url1, decrypted.first().resolvedUrl)

            // Suggestion filter matches partial query cleanly
            val onionMatches = EncryptedSearchHistoryRepository.filterSuggestions(decrypted, "duckduckgogg")
            assertEquals(1, onionMatches.size)
            assertTrue(onionMatches.first().isOnion)

            // Delete single & clearAll
            repo.deleteSearch(onionMatches.first().id)
            assertEquals(1, repo.observeRecentSearches(10).first().size)
            repo.clearAll()
            assertEquals(0, repo.observeRecentSearches(10).first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `qr payload parser extracts safe urls and blocks dangerous schemes`() {
        assertEquals(
            QrScanOutcome.NavigableUrl("https://eff.org/privacy"),
            QrCodeUrlParser.parseQrPayload("https://eff.org/privacy"),
        )
        assertEquals(
            QrScanOutcome.NavigableUrl("http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"),
            QrCodeUrlParser.parseQrPayload("http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"),
        )
        assertEquals(
            QrScanOutcome.NavigableUrl("duckduckgo.com/?q=tor"),
            QrCodeUrlParser.parseQrPayload("duckduckgo.com/?q=tor"),
        )
        assertEquals(
            QrScanOutcome.NavigableUrl("https://wikipedia.org"),
            QrCodeUrlParser.parseQrPayload("MEBKM:TITLE:Wiki;URL:https://wikipedia.org;;"),
        )
        assertEquals(
            QrScanOutcome.UnsafeScheme("javascript"),
            QrCodeUrlParser.parseQrPayload("javascript:alert(1)"),
        )
        assertEquals(
            QrScanOutcome.UnsafeScheme("file"),
            QrCodeUrlParser.parseQrPayload("file:///etc/passwd"),
        )
        assertEquals(
            QrScanOutcome.UnsafeScheme("data"),
            QrCodeUrlParser.parseQrPayload("data:text/html,<script>alert(1)</script>"),
        )
        assertEquals(
            QrScanOutcome.NonUrlText("WIFI:S:HomeNet;T:WPA;P:secret;;"),
            QrCodeUrlParser.parseQrPayload("WIFI:S:HomeNet;T:WPA;P:secret;;"),
        )
        assertEquals(
            QrScanOutcome.Empty,
            QrCodeUrlParser.parseQrPayload("   "),
        )
    }

    @Test
    fun `biometric app lock defaults enabled, persists toggle, and locks on background when enabled`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.data.PrivacyPreferencesRepository(context)
        assertTrue(repo.securityConfig.first().biometricAppLockEnabled)

        // BrowserUiState starts locked before initial config & biometric verification
        val initialUiState = com.example.ui.BrowserUiState()
        assertTrue(initialUiState.isAppLocked)
        assertFalse(initialUiState.isAppLockConfigLoaded)

        // Initial config emission keeps app locked when biometricAppLockEnabled is true
        assertTrue(
            com.example.ui.screens.AppLockPolicy.resolveConfigUpdate(
                currentLocked = true,
                isConfigLoaded = false,
                biometricAppLockEnabled = true,
            ),
        )
        // Backgrounding re-locks while enabled
        assertTrue(com.example.ui.screens.AppLockPolicy.shouldLockOnBackground(true))

        // Toggling preference to false persists and keeps app unlocked across background transitions
        repo.setBiometricAppLockEnabled(false)
        assertFalse(repo.securityConfig.first().biometricAppLockEnabled)
        assertFalse(
            com.example.ui.screens.AppLockPolicy.resolveConfigUpdate(
                currentLocked = true,
                isConfigLoaded = false,
                biometricAppLockEnabled = false,
            ),
        )
        assertFalse(com.example.ui.screens.AppLockPolicy.resolveAfterToggle(currentLocked = true, enabled = false))
        assertFalse(com.example.ui.screens.AppLockPolicy.shouldLockOnBackground(false))

        // Restore default enabled state
        repo.setBiometricAppLockEnabled(true)
        assertTrue(repo.securityConfig.first().biometricAppLockEnabled)
    }

    @Test
    fun `dns vpn packet codec parses ipv4 udp query, extracts domain, builds valid checksummed response, and builds nxdomain`() {
        // Minimal DNS query for "example.org" (QTYPE=A, QCLASS=IN)
        val dnsQuery = byteArrayOf(
            0x12, 0x34, // Transaction ID
            0x01, 0x00, // Flags: standard query, RD=1
            0x00, 0x01, // QDCOUNT=1
            0x00, 0x00, // ANCOUNT=0
            0x00, 0x00, // NSCOUNT=0
            0x00, 0x00, // ARCOUNT=0
            // Question: 7 "example" 3 "org" 0
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'o'.code.toByte(), 'r'.code.toByte(), 'g'.code.toByte(),
            0,
            0x00, 0x01, // QTYPE A
            0x00, 0x01, // QCLASS IN
        )

        val udpLen = 8 + dnsQuery.size
        val ipTotalLen = 20 + udpLen
        val packet = ByteArray(ipTotalLen)
        packet[0] = 0x45.toByte() // IPv4, IHL=5
        packet[2] = (ipTotalLen ushr 8).toByte()
        packet[3] = (ipTotalLen and 0xFF).toByte()
        packet[8] = 64.toByte()
        packet[9] = 17.toByte() // UDP
        // Src IP: 10.111.222.1
        packet[12] = 10; packet[13] = 111; packet[14] = 222.toByte(); packet[15] = 1
        // Dst IP: 10.111.222.2
        packet[16] = 10; packet[17] = 111; packet[18] = 222.toByte(); packet[19] = 2
        // UDP SrcPort=53535, DstPort=53, Length=udpLen
        packet[20] = (53535 ushr 8).toByte(); packet[21] = (53535 and 0xFF).toByte()
        packet[22] = 0; packet[23] = 53
        packet[24] = (udpLen ushr 8).toByte(); packet[25] = (udpLen and 0xFF).toByte()
        System.arraycopy(dnsQuery, 0, packet, 28, dnsQuery.size)

        val parsed = com.example.browser.DnsVpnPacketCodec.parseIpv4UdpDnsQuery(packet, packet.size)
        assertNotNull(parsed)
        assertEquals("example.org", parsed!!.domainName)
        assertEquals(53535, parsed.srcPort)
        assertEquals(53, parsed.dstPort)

        // Build NXDOMAIN response and verify DNS flags
        val nxdomain = com.example.browser.DnsVpnPacketCodec.buildErrorDnsResponse(parsed.dnsPayload, 3)
        assertNotNull(nxdomain)
        assertEquals(0x81.toByte(), nxdomain!![2]) // QR=1, RD preserved
        assertEquals(0x83.toByte(), nxdomain[3]) // RA=1, RCODE=3 (NXDOMAIN)

        // Wrap into IPv4/UDP response and verify swapped headers + valid IPv4 header checksum
        val responsePacket = com.example.browser.DnsVpnPacketCodec.buildIpv4UdpDnsResponse(parsed, nxdomain)
        assertEquals(ipTotalLen, responsePacket.size)
        // Src IP should now be 10.111.222.2, Dst IP should now be 10.111.222.1
        assertEquals(2.toByte(), responsePacket[15])
        assertEquals(1.toByte(), responsePacket[19])
        // UDP SrcPort=53, DstPort=53535
        assertEquals(53, ((responsePacket[20].toInt() and 0xFF) shl 8) or (responsePacket[21].toInt() and 0xFF))
        assertEquals(53535, ((responsePacket[22].toInt() and 0xFF) shl 8) or (responsePacket[23].toInt() and 0xFF))
        // One's-complement checksum over complete valid IPv4 header must equal 0
        assertEquals(0, com.example.browser.DnsVpnPacketCodec.computeInternetChecksum(responsePacket, 0, 20))
    }

    @Test
    fun `vpn server profile defaults to cloudflare warp and persists selection in datastore`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.data.PrivacyPreferencesRepository(context)
        assertEquals(com.example.data.VpnServerProfile.CLOUDFLARE_WARP, repo.securityConfig.first().vpnProfile)

        repo.setVpnProfile(com.example.data.VpnServerProfile.QUAD9_SECURE)
        assertEquals(com.example.data.VpnServerProfile.QUAD9_SECURE, repo.securityConfig.first().vpnProfile)

        repo.setVpnProfile(com.example.data.VpnServerProfile.ADGUARD_PRIVACY)
        assertEquals(com.example.data.VpnServerProfile.ADGUARD_PRIVACY, repo.securityConfig.first().vpnProfile)

        repo.setVpnProfile(com.example.data.VpnServerProfile.CLOUDFLARE_WARP)
        assertEquals(com.example.data.VpnServerProfile.CLOUDFLARE_WARP, repo.securityConfig.first().vpnProfile)
    }

    @Test
    fun `youtube ad blocker blocks first-party ad endpoints and ad video streams while preserving real video playback`() {
        val pageUrl = "https://m.youtube.com/watch?v=dQw4w9WgXcQ"
        assertTrue(TrackerAndUrlEngine.isAdOrTrackerRequest("https://m.youtube.com/api/stats/ads?ns=yt", pageUrl))
        assertTrue(TrackerAndUrlEngine.isAdOrTrackerRequest("https://www.youtube.com/pagead/viewthroughconversion/123/", pageUrl))
        assertTrue(TrackerAndUrlEngine.isAdOrTrackerRequest("https://m.youtube.com/ptracking?video_id=abc", pageUrl))
        assertTrue(TrackerAndUrlEngine.isAdOrTrackerRequest("https://m.youtube.com/get_midroll_info?v=abc", pageUrl))
        assertTrue(TrackerAndUrlEngine.isAdOrTrackerRequest("https://www.youtube.com/youtubei/v1/player/ad_break", pageUrl))
        assertTrue(TrackerAndUrlEngine.isAdOrTrackerRequest("https://rr2---sn-test.googlevideo.com/videoplayback?expire=999&oad=1&ctier=l", pageUrl))
        assertTrue(TrackerAndUrlEngine.isAdOrTrackerRequest("https://googleads.g.doubleclick.net/pagead/id", pageUrl))

        // Real YouTube watch page, player API, and normal video streams remain unblocked
        assertFalse(TrackerAndUrlEngine.isAdOrTrackerRequest("https://m.youtube.com/watch?v=dQw4w9WgXcQ", pageUrl))
        assertFalse(TrackerAndUrlEngine.isAdOrTrackerRequest("https://m.youtube.com/youtubei/v1/player", pageUrl))
        assertFalse(TrackerAndUrlEngine.isAdOrTrackerRequest("https://rr2---sn-test.googlevideo.com/videoplayback?expire=999&ctier=A&mime=audio/mp4", pageUrl))
    }

    @Test
    fun `privacy scripts include youtube ad stripper and background media visibility sync`() {
        val script = PrivacyScripts.buildHardeningScript(
            seed = "YTGUARD1",
            enableWebRtcGuard = false,
            enableFingerprintRandomization = false,
            enableCosmeticAdBlock = true,
            enablePopupGuard = true,
            enableBackgroundMediaPlayback = true,
        )
        assertTrue(script.contains("__osYtAdBlockerInstalled"))
        assertTrue(script.contains("ytm-promoted-video-renderer"))
        assertTrue(script.contains("adPlacements"))
        assertTrue(script.contains("skipYouTubeAdTick"))
        assertTrue(script.contains("syncPlayingMediaNow"))
    }

    @Test
    fun `background capable webview keeps window visibility visible when background media playback is enabled`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val webView = com.example.browser.BackgroundCapableWebView(context)
        assertTrue(webView.keepMediaAliveInBackground)

        assertEquals(android.view.View.VISIBLE, webView.resolveEffectiveWindowVisibility(android.view.View.GONE))
        assertEquals(android.view.View.VISIBLE, webView.resolveEffectiveWindowVisibility(android.view.View.INVISIBLE))

        webView.keepMediaAliveInBackground = false
        assertEquals(android.view.View.GONE, webView.resolveEffectiveWindowVisibility(android.view.View.GONE))
        assertEquals(android.view.View.INVISIBLE, webView.resolveEffectiveWindowVisibility(android.view.View.INVISIBLE))
    }

    @Test
    fun `onion media session handler manages playback state, audio focus resume, and stop transitions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var playCalls = 0
        var pauseCalls = 0
        var stopCalls = 0
        var lastSeekMs = -1L

        val handler = com.example.browser.OnionMediaSessionHandler(
            context = context,
            onPlayCommand = { playCalls++ },
            onPauseCommand = { pauseCalls++ },
            onStopCommand = { stopCalls++ },
            onSeekCommand = { lastSeekMs = it },
            onSkipNextCommand = {},
            onSkipPrevCommand = {},
        )
        try {
            assertNotNull(handler.sessionToken)
            handler.updatePlayback(
                title = "Midnight Rain - Music Video",
                artist = "YouTube Music",
                isPlaying = true,
                positionMs = 12_000L,
                durationMs = 240_000L,
            )
            assertTrue(handler.snapshot.value.isPlaying)
            assertTrue(handler.snapshot.value.hasActiveSession)
            assertEquals("Midnight Rain - Music Video", handler.snapshot.value.title)
            assertEquals("YouTube Music", handler.snapshot.value.artist)
            assertEquals(12_000L, handler.snapshot.value.positionMs)
            assertEquals(240_000L, handler.snapshot.value.durationMs)

            // Transient audio focus loss pauses cleanly and resumes when focus returns
            handler.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            assertFalse(handler.snapshot.value.isPlaying)
            assertTrue(handler.snapshot.value.hasActiveSession)
            assertEquals(1, pauseCalls)

            handler.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)
            assertTrue(handler.snapshot.value.isPlaying)
            assertEquals(1, playCalls)

            // Stop deactivates session cleanly
            handler.handleStopFromSession()
            assertFalse(handler.snapshot.value.isPlaying)
            assertFalse(handler.snapshot.value.hasActiveSession)
            assertEquals(1, stopCalls)
        } finally {
            handler.release()
        }
    }

    @Test
    fun `foreground service handles pause resume actions with MediaStyle notification and persists media state`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LiveSessionRuntime.updateMediaSnapshot(
            isPlaying = true,
            title = "Acoustic Chill Stream",
            artist = "Live Channel",
            positionMs = 30_000L,
            durationMs = 180_000L,
            url = "https://m.youtube.com/watch?v=test123",
        )

        val controller = Robolectric.buildService(
            LiveMonitorForegroundService::class.java,
            Intent(context, LiveMonitorForegroundService::class.java).setAction(LiveMonitorForegroundService.ACTION_START),
        ).create().startCommand(0, 0)
        val service = controller.get()

        try {
            val token = service.mediaSessionHandler?.sessionToken
            assertNotNull(token)
            assertTrue(service.isPartialWakeLockHeld())
            assertTrue(service.isWifiLockHeld())

            // Pause via notification action switches button to Resume Media and embeds MediaSession token
            service.onStartCommand(
                Intent(context, LiveMonitorForegroundService::class.java)
                    .setAction(LiveMonitorForegroundService.ACTION_PAUSE_MEDIA),
                0,
                1,
            )
            val pausedNotif = service.buildNotification(
                stats = com.example.browser.SessionGuardStats(elapsedMinutes = 5L),
                config = com.example.data.SecurityConfig(browserMode = BrowserMode.SURFACE),
                syncStatus = com.example.browser.SessionMonitorSyncStatus.Disabled,
                torState = com.example.data.TorProxyState.Inactive,
                isMediaActive = false,
                mediaTitle = "Acoustic Chill Stream",
                isMediaPausedSession = true,
                mediaSessionToken = token,
            )
            assertEquals(2, pausedNotif.actions.size)
            assertEquals(
                context.getString(R.string.live_monitor_resume_media_action),
                pausedNotif.actions[1].title.toString(),
            )
            assertTrue(pausedNotif.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION))

            // DataStore persisted media state saves and clears cleanly
            val repo = com.example.data.PrivacyPreferencesRepository(context)
            repo.savePersistedMediaState(
                com.example.data.PersistedMediaState(
                    title = "Acoustic Chill Stream",
                    artist = "Live Channel",
                    url = "https://m.youtube.com/watch?v=test123",
                    positionMs = 45_000L,
                    durationMs = 180_000L,
                ),
            )
            val restored = repo.persistedMediaState.first()
            assertEquals("Acoustic Chill Stream", restored.title)
            assertEquals("Live Channel", restored.artist)
            assertEquals(45_000L, restored.positionMs)

            repo.clearPersistedMediaState()
            assertEquals("", repo.persistedMediaState.first().title)

            // Verify PrivacyScripts includes remote MediaSession control hooks
            val script = PrivacyScripts.buildHardeningScript(
                seed = "MSESS1",
                enableWebRtcGuard = false,
                enableFingerprintRandomization = false,
                enableBackgroundMediaPlayback = true,
            )
            assertTrue(script.contains("__osBgMediaForcePlay"))
            assertTrue(script.contains("__osBgMediaSeekTo"))
            assertTrue(script.contains("__osBgMediaNext"))
            assertTrue(script.contains("__osBgMediaPrev"))
            assertTrue(script.contains("onMediaMetadataUpdated"))
        } finally {
            LiveSessionRuntime.clearMediaSession()
            controller.destroy()
        }
    }

    @Test
    fun `battery optimization helper builds zero-permission settings intents and reads power manager status`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appDetailsIntent = com.example.ui.components.BatteryOptimizationHelper
            .buildAppBatteryDetailsIntent(context.packageName)
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appDetailsIntent.action)
        assertEquals("package:${context.packageName}", appDetailsIntent.dataString)
        assertTrue((appDetailsIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)

        val listIntent = com.example.ui.components.BatteryOptimizationHelper
            .buildBatteryOptimizationListIntent()
        assertEquals(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, listIntent.action)
        assertTrue((listIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val shadowPowerManager = Shadows.shadowOf(powerManager)
        assertFalse(com.example.ui.components.BatteryOptimizationHelper.isBatteryOptimizationDisabled(context))

        shadowPowerManager.setIgnoringBatteryOptimizations(context.packageName, true)
        assertTrue(com.example.ui.components.BatteryOptimizationHelper.isBatteryOptimizationDisabled(context))
    }

    @Test
    fun `gemini repository builds contextual prompts parses structured audit json and configures app check token`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("gemini-3.8-flash", com.example.data.repository.GeminiShieldRepository.MODEL_NAME)

        val ctx = com.example.data.repository.PageAiContext(
            url = "https://example.org/privacy",
            title = "Example Privacy",
            browserModeLabel = "Surface",
            trackersBlockedCount = 4,
            scriptsBlockedCount = 1,
            pageTextSnippet = "We collect zero analytics cookies.",
        )
        val builtPrompt = com.example.data.repository.GeminiShieldRepository.buildContextualPrompt("Summarize this", ctx)
        assertTrue(builtPrompt.contains("Summarize this"))
        assertTrue(builtPrompt.contains("https://example.org/privacy"))
        assertTrue(builtPrompt.contains("Blocked Trackers: 4"))
        assertEquals("Just a question", com.example.data.repository.GeminiShieldRepository.buildContextualPrompt("  Just a question  ", null))

        val sampleJson = """
            {
              "riskLevel": "Low",
              "summary": "This page describes minimal cookie collection.",
              "privacySignals": ["HTTPS verified", "4 trackers blocked by OnionShield"],
              "recommendedAction": "Keep standard HTTPS-Only protection enabled."
            }
        """.trimIndent()
        val parsed = com.example.data.repository.GeminiShieldRepository.parsePagePrivacyAuditJson(sampleJson)
        assertEquals("Low", parsed.riskLevel)
        assertEquals("This page describes minimal cookie collection.", parsed.summary)
        assertEquals(2, parsed.privacySignals.size)
        assertEquals("Keep standard HTTPS-Only protection enabled.", parsed.recommendedAction)

        if (com.example.BuildConfig.DEBUG) {
            if (com.google.firebase.FirebaseApp.getApps(context)
                    .none { it.name == com.google.firebase.FirebaseApp.DEFAULT_APP_NAME }
            ) {
                com.google.firebase.FirebaseApp.initializeApp(context)
                    ?: com.google.firebase.FirebaseApp.initializeApp(
                        context,
                        com.google.firebase.FirebaseOptions.Builder()
                            .setApplicationId("com.aistudio.onionshield.kxrqzm")
                            .setProjectId("demo-robolectric-test")
                            .setApiKey("fake-api-key-for-robolectric-test")
                            .build(),
                    )
            }
            val testToken = "test-debug-token-12345"
            val launchIntent = Intent().putExtra("FIREBASE_APPCHECK_DEBUG_TOKEN", testToken)
            com.example.security.configureAppCheck(context, launchIntent)
            val persistenceKey = com.google.firebase.FirebaseApp.getInstance().persistenceKey
            val prefs = context.getSharedPreferences(
                "com.google.firebase.appcheck.debug.store.$persistenceKey",
                Context.MODE_PRIVATE,
            )
            assertEquals(testToken, prefs.getString("com.google.firebase.appcheck.debug.DEBUG_SECRET", null))
        }
    }

    @Test
    fun `gemini multi-turn chat normalizes history turns and viewModel manages chat state cleanly`() {
        val audit = com.example.data.repository.PagePrivacyAudit(
            riskLevel = "Low",
            summary = "Minimal tracking detected.",
            privacySignals = listOf("HTTPS active"),
            recommendedAction = "Keep Tor or tracker blocking enabled.",
        )
        val rawHistory = listOf(
            com.example.data.repository.GeminiChatMessage(
                role = com.example.data.repository.ChatRole.USER,
                text = "What trackers were blocked?",
                promptText = "What trackers were blocked?\n--- Active Browser Tab Context ---\nURL: https://example.org",
            ),
            com.example.data.repository.GeminiChatMessage(
                role = com.example.data.repository.ChatRole.MODEL,
                text = "Three analytics scripts were blocked.",
            ),
            com.example.data.repository.GeminiChatMessage(
                role = com.example.data.repository.ChatRole.USER,
                text = "Audit this page",
            ),
            com.example.data.repository.GeminiChatMessage(
                role = com.example.data.repository.ChatRole.MODEL,
                text = "Minimal tracking detected.",
                audit = audit,
            ),
            // Dangling unanswered user turn (e.g. after a previous network error) should be trimmed
            com.example.data.repository.GeminiChatMessage(
                role = com.example.data.repository.ChatRole.USER,
                text = "Timed out question",
            ),
            // Blank message should also be ignored
            com.example.data.repository.GeminiChatMessage(
                role = com.example.data.repository.ChatRole.MODEL,
                text = "   ",
            ),
        )

        val normalized = com.example.data.repository.GeminiShieldRepository.normalizeHistoryTurns(rawHistory)
        assertEquals(4, normalized.size)
        assertEquals(com.example.data.repository.ChatRole.USER, normalized[0].first)
        assertTrue(normalized[0].second.contains("https://example.org"))
        assertEquals(com.example.data.repository.ChatRole.MODEL, normalized[1].first)
        assertEquals("Three analytics scripts were blocked.", normalized[1].second)
        assertEquals(com.example.data.repository.ChatRole.MODEL, normalized[3].first)
        assertTrue(normalized[3].second.contains("Risk: Low"))

        val sdkHistory = com.example.data.repository.GeminiShieldRepository.buildSdkHistory(rawHistory)
        assertEquals(4, sdkHistory.size)
        assertEquals("user", sdkHistory[0].role)
        assertEquals("model", sdkHistory[3].role)

        val vm = com.example.ui.GeminiAssistantViewModel()
        assertEquals(com.example.ui.GeminiUiState.Idle, vm.uiState.value)
        assertTrue(vm.messages.value.isEmpty())
        vm.sendMessage("   ", null)
        assertEquals(com.example.ui.GeminiUiState.Idle, vm.uiState.value)
        assertTrue(vm.messages.value.isEmpty())
        vm.clearChat()
        assertEquals(com.example.ui.GeminiUiState.Idle, vm.uiState.value)
    }

    @Test
    fun `youtube playback fix preserves ctier=l streams, avoids audio focus self-steal, and hardens autoplay transitions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pageUrl = "https://m.youtube.com/watch?v=song123"

        // Legitimate YouTube low-tier / preload audio streams must NOT be blocked
        assertFalse(TrackerAndUrlEngine.isAdOrTrackerRequest("https://rr3---sn-test.googlevideo.com/videoplayback?expire=999&ctier=l&mime=audio/mp4", pageUrl))
        assertFalse(TrackerAndUrlEngine.isAdOrTrackerRequest("https://rr3---sn-test.googlevideo.com/videoplayback?expire=999&preload=1&mime=audio/webm", pageUrl))
        // Explicit ad streams remain blocked
        assertTrue(TrackerAndUrlEngine.isAdOrTrackerRequest("https://rr3---sn-test.googlevideo.com/videoplayback?expire=999&oad=1&ctier=l", pageUrl))
        assertTrue(TrackerAndUrlEngine.isAdOrTrackerRequest("https://rr3---sn-test.googlevideo.com/videoplayback?expire=999&ad_type=video", pageUrl))

        // OnionMediaSessionHandler must NOT steal AudioManager focus away from in-process WebView by default
        val handler = com.example.browser.OnionMediaSessionHandler(
            context = context,
            onPlayCommand = {},
            onPauseCommand = {},
            onStopCommand = {},
            onSeekCommand = {},
            onSkipNextCommand = {},
            onSkipPrevCommand = {},
        )
        try {
            handler.updatePlayback(
                title = "Autoplayed Song",
                artist = "YouTube Channel",
                isPlaying = true,
                positionMs = 1_500L,
                durationMs = 210_000L,
            )
            assertTrue(handler.snapshot.value.isPlaying)
            assertEquals(0, handler.platformAudioFocusRequests)
        } finally {
            handler.release()
        }

        // PrivacyScripts includes hasFocus override, Continue Watching auto-dismissal, and SPA autoplay transition guard
        val script = PrivacyScripts.buildHardeningScript(
            seed = "YTMUSIC1",
            enableWebRtcGuard = false,
            enableFingerprintRandomization = false,
            enableCosmeticAdBlock = true,
            enablePopupGuard = true,
            enableBackgroundMediaPlayback = true,
        )
        assertTrue(script.contains("Document.prototype.hasFocus"))
        assertTrue(script.contains("dismissContinueWatchingDialogs"))
        assertTrue(script.contains("isExplicitPlayPauseControlTarget"))
        assertTrue(script.contains("tryAutoResumeElement"))
        assertTrue(script.contains("__osAutoplayTransitionUntilMs"))
    }

    @Test
    fun `gemini high demand errors trigger friendly retry state and retryLastRequest avoids duplicate bubbles`() = runBlocking {
        assertEquals("gemini-3.5-flash-lite", com.example.data.repository.GeminiShieldRepository.FALLBACK_MODEL_NAME)
        assertTrue(
            com.example.data.repository.GeminiShieldRepository.isTransientHighDemandError(
                RuntimeException("This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later."),
            ),
        )
        assertTrue(
            com.example.data.repository.GeminiShieldRepository.isTransientHighDemandError(
                RuntimeException("HTTP 503 service unavailable"),
            ),
        )
        assertFalse(
            com.example.data.repository.GeminiShieldRepository.isTransientHighDemandError(
                RuntimeException("HTTP 403 App Check token invalid"),
            ),
        )

        var attempt = 0
        val fakeRepo = object : com.example.data.repository.GeminiShieldRepository() {
            override fun sendChatMessageStream(
                history: List<com.example.data.repository.GeminiChatMessage>,
                userPrompt: String,
                pageContext: com.example.data.repository.PageAiContext?,
            ): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
                attempt++
                if (attempt == 1) {
                    throw RuntimeException("This model is currently experiencing high demand.")
                }
                emit("Tor routes traffic through three encrypted relays.")
            }
        }

        val vm = com.example.ui.GeminiAssistantViewModel(repository = fakeRepo)
        vm.sendMessage("Explain how Tor .onion circuits protect my IP address", null)
        // Allow viewModelScope coroutine on Main dispatcher to process
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val firstErr = vm.uiState.value as? com.example.ui.GeminiUiState.Error
        assertNotNull(firstErr)
        assertTrue(firstErr!!.canRetry)
        assertEquals(1, vm.messages.value.size)

        vm.retryLastRequest()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(vm.uiState.value is com.example.ui.GeminiUiState.Success)
        // Must reuse the existing user turn rather than duplicating it
        assertEquals(2, vm.messages.value.size)
        assertEquals(com.example.data.repository.ChatRole.USER, vm.messages.value[0].role)
        assertEquals(com.example.data.repository.ChatRole.MODEL, vm.messages.value[1].role)
        assertTrue(vm.messages.value[1].text.contains("three encrypted relays"))
    }

    @Test
    fun `persistent surface seed authenticated hosts and webview session preservation work for google and any site`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.data.PrivacyPreferencesRepository(context)

        val initialSeed = repo.ensureSurfaceDeviceSeed("SEED1111")
        assertEquals("SEED1111", initialSeed)
        // Subsequent app starts reuse the same Surface seed until explicitly rotated
        assertEquals("SEED1111", repo.ensureSurfaceDeviceSeed("SEED9999"))

        repo.addAuthenticatedSessionHost("www.customshop.example")
        assertTrue(repo.authenticatedSessionHosts.first().contains("customshop.example"))

        // Auth flow URL detection covers Google, major SSO providers, and arbitrary /login or /oauth endpoints
        assertTrue(com.example.browser.WebViewPool.isAuthFlowUrl("https://accounts.google.com/v3/signin"))
        assertTrue(com.example.browser.WebViewPool.isAuthFlowUrl("https://www.example.com/oauth2/authorize"))
        assertTrue(com.example.browser.WebViewPool.isAuthFlowUrl("https://portal.mybank.org/login"))
        assertFalse(com.example.browser.WebViewPool.isAuthFlowUrl("https://example.org/news/article-1"))

        // Cross-domain account session hosts work for Google, YouTube, other major sites, and any user-logged-in domain
        val recordedHosts = repo.authenticatedSessionHosts.first()
        assertTrue(com.example.browser.WebViewPool.isAccountSessionHost("https://m.youtube.com/watch?v=abc", recordedHosts))
        assertTrue(com.example.browser.WebViewPool.isAccountSessionHost("https://www.google.com/search?q=ai", recordedHosts))
        assertTrue(com.example.browser.WebViewPool.isAccountSessionHost("https://github.com/settings", recordedHosts))
        assertTrue(com.example.browser.WebViewPool.isAccountSessionHost("https://sub.customshop.example/account", recordedHosts))
        assertFalse(com.example.browser.WebViewPool.isAccountSessionHost("https://untrusted-news.example/home", recordedHosts))

        // Explicit shred rotates seed and clears authenticated hosts
        repo.rotateSurfaceDeviceSeed("SEED2222")
        repo.clearAuthenticatedSessionHosts()
        assertEquals("SEED2222", repo.surfaceDeviceSeed.first())
        assertTrue(repo.authenticatedSessionHosts.first().isEmpty())
    }
}

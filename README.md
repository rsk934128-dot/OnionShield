<div align="center">

<img src="app/src/main/res/drawable/ic_onionshield_logo_1794756388628.jpg" alt="OnionShield Logo" width="128" />

# OnionShield

**Hardened Dual-Mode Android Privacy Browser — Surface Web & Tor / `.onion` Routing**

Part of the **[OmniSafe-Browser](https://github.com/rsk934128-dot/OmniSafe-Browser)** privacy browser ecosystem.

[![Repository](https://img.shields.io/badge/GitHub-OmniSafe--Browser-00E5D1?style=for-the-badge&logo=github&logoColor=0B0F19)](https://github.com/rsk934128-dot/OmniSafe-Browser)
[![Platform](https://img.shields.io/badge/Platform-Android%20(SDK%2024--36)-00E5D1?style=for-the-badge&logo=android&logoColor=0B0F19)](https://developer.android.com)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-B66CFF?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Language](https://img.shields.io/badge/Kotlin-2.2-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![WebKit](https://img.shields.io/badge/Engine-AndroidX%20WebKit-22C55E?style=for-the-badge)](https://developer.android.com/jetpack/androidx/releases/webkit)

<br />

<img src="app/src/main/res/drawable/img_privacy_banner_1794756388393.jpg" alt="OnionShield Privacy Hero Banner" width="100%" />

</div>

---

## বাংলা পরিচিতি (Bengali Overview)

**OnionShield** হলো Kotlin এবং Jetpack Compose দিয়ে তৈরি একটি আধুনিক, নিরাপত্তা-কেন্দ্রিক অ্যান্ড্রয়েড ওয়েব ব্রাউজার। এটি একইসাথে **Surface Web** এবং **Tor / `.onion` (Dark Web)** প্রক্সি রাউটিং সাপোর্ট করে। ব্যবহারকারীর প্রাইভেসি নিশ্চিত করতে এতে **No-Script Mode (ডিফল্টভাবে JavaScript বন্ধ)**, **HTTPS Everywhere**, **WebRTC Leak Protection**, **Fingerprint Randomization**, **Tracker Blocker**, **Sandboxed Downloads Manager** এবং এক-ট্যাপে **Shred Session** ফিচার যুক্ত করা হয়েছে।

---

## Dual-Mode Interface Showcase

<div align="center">
  <img src="docs/images/ui_showcase.jpg" alt="OnionShield Dual-Mode Surface and Tor Interface Showcase" width="100%" />
</div>

| Mode | Visual Identity | Network Behavior |
| :--- | :--- | :--- |
| **Surface Web** | Electric Cyan (`#00E5D1`) | Direct clearnet routing with automatic HTTP → HTTPS upgrades, tracker blocking, and optional local history. |
| **Tor `.onion`** | Amethyst Violet (`#B66CFF`) | Routes WebView traffic through `ProxyController` (`socks5://127.0.0.1:9050` or HTTP `8118` via Orbot) with zero history persistence. |

---

## Multi-Layer Security Architecture

<div align="center">
  <img src="docs/images/security_architecture.jpg" alt="OnionShield Defense Layers Architecture" width="100%" />
</div>

### Core Security Shields
- **No-Script Mode (Default On):** Disables JavaScript execution (`javaScriptEnabled = false`) and intercepts `.js`/`.mjs` network requests out of the box to prevent script-based exploits.
- **Tor SOCKS5 Proxy Routing:** Uses `androidx.webkit.ProxyController` (`WebViewFeature.PROXY_OVERRIDE`) to route traffic through `socks5://127.0.0.1:9050` (or custom SOCKS5/HTTP endpoints) with live TCP socket reachability diagnostics and Orbot detection.
- **V3 `.onion` Bridge & Circuit Diagnostics:** Dedicated top-bar Shield diagnostic sheet featuring an animated `CircuitGraph` route visualizer, real SOCKS5 handshake & `check.torproject.org/api/ip` Exit IP verification, optional Tor ControlPort (`9051`) `GETINFO circuit-status` & `SIGNAL NEWNYM` new-circuit rotation, 56-character V3 `.onion` address verification, Orbot `START` intent control, and pluggable transport (`obfs4`, `Snowflake`, `meek-azure`, `Custom`) bridge validation.
- **HTTPS Everywhere:** Automatically upgrades clearnet `http://` URLs to `https://` while preserving `.onion` hidden services and local loopback addresses, and enforces `MIXED_CONTENT_NEVER_ALLOW`.
- **WebRTC Leak Protection:** Injects runtime guards neutralizing `RTCPeerConnection` and `navigator.mediaDevices` while denying camera, microphone, and geolocation prompts in `WebChromeClient`.
- **Fingerprint Randomization:** Rotates User-Agent profiles and injects per-tab seeded Canvas, WebGL, `hardwareConcurrency`, and `deviceMemory` noise with one-tap seed rotation.
- **Tracker & 3rd-Party Cookie Blocker:** Intercepts known analytics/advertising domains inside `shouldInterceptRequest` and isolates cross-origin cookies.
- **Sandboxed Download Guard:** Intercepts file downloads with a security confirmation dialog highlighting `.onion` vs Surface origins before dispatching to Android `DownloadManager`.
- **One-Tap Shred Session:** Instantly destroys all open tabs, wipes cookies, `WebStorage`, cache, and form data, and generates a fresh fingerprint seed.

---

## Reusable `BrowserWebView` Composable

OnionShield includes a standalone, security-hardened `BrowserWebView` composable built on AndroidX `WebKit`:

```kotlin
BrowserWebView(
    url = "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion",
    javaScriptEnabled = false, // Disabled by default
    enforceHttps = true,       // Upgrades clearnet HTTP -> HTTPS & blocks mixed content
    useTorSocks5Proxy = true,  // Routes via ProxyController
    torProxyHost = "127.0.0.1",
    torProxyPort = 9050,
)
```

---

## Tech Stack

- **Language & Concurrency:** Kotlin, Coroutines, `StateFlow`
- **UI & Design System:** Jetpack Compose, Material Design 3 (Obsidian Cipher theme), bundled `Space Grotesk`, `Inter`, and `JetBrains Mono` fonts
- **Browser Engine:** Android `WebView` + `androidx.webkit` (`ProxyController`, `WebSettingsCompat`, Safe Browsing, algorithmic darkening)
- **Persistence (100% On-Device):**
  - **Room + KSP:** Local Bookmarks, optional Surface History, and Sandboxed Download logs
  - **Jetpack DataStore:** Privacy shields, proxy configuration, and search engine preferences
- **Testing:** JUnit 4 + Robolectric (`:app:testDebugUnitTest`)

---

## Project Structure

```text
app/src/main/java/com/example/
├── MainActivity.kt                      # Edge-to-edge host & Navigation Compose
├── browser/
│   ├── TorProxyController.kt            # AndroidX ProxyController & TCP socket probe
│   ├── TrackerAndUrlEngine.kt           # HTTPS upgrade, .onion detection & tracker filter
│   ├── PrivacyScripts.kt                # WebRTC guard & fingerprint noise injection
│   └── WebViewPool.kt                   # Hardened multi-tab WebView pool & session shredder
├── data/
│   ├── BrowserModels.kt                 # Domain models, proxy states & SecurityConfig
│   ├── OnionShieldDatabase.kt           # Room DB & BrowserLibraryRepository
│   └── PrivacyPreferencesRepository.kt  # DataStore preferences
└── ui/
    ├── BrowserViewModel.kt              # MVVM state coordinator
    ├── components/
    │   ├── BrowserWebView.kt            # Reusable security-hardened WebView composable
    │   ├── StartPageContent.kt          # Telemetry dashboard & quick-toggle shield grid
    │   └── BrowserSheetsAndDialogs.kt   # Tab switcher, Quick Shield, Download & Shred dialogs
    └── screens/
        ├── BrowserScreen.kt             # Dual-mode chrome, omnibox & viewport
        ├── SecuritySettingsScreen.kt    # Tor SOCKS5/HTTP proxy setup & shield settings
        └── LibraryAndDownloadsScreens.kt # Bookmarks, local history & downloads manager
```

---

## Getting Started

1. **Build & Run:**
   ```bash
   gradle assembleDebug
   ```
2. **Run Unit & Robolectric Tests:**
   ```bash
   gradle :app:testDebugUnitTest
   ```
3. **Using Tor / `.onion` Mode:**
   - Install and start **Orbot** (or any local Tor SOCKS5/HTTP daemon) on your Android device.
   - Switch OnionShield to **Tor .onion** mode in the top bar (defaults to `socks5://127.0.0.1:9050`).
   - Tap **Test** on the Start Page or open **Security & Proxy Setup** to verify socket connectivity.

---

## GitHub APK Release (CI/CD)

This repository includes automated GitHub Actions workflows in `.github/workflows/`:

- **Automatic Release (`release-apk.yml`):**
  - Push a version tag (e.g. `git tag v1.0.0 && git push origin v1.0.0`), **or**
  - Open **GitHub → Actions → Release Android APK → Run workflow** and enter a version tag (e.g. `v1.0.0`).
  - GitHub will run all unit tests, build the signed `OnionShield-<tag>-release.apk` + `.sha256` checksum, and attach them directly to **GitHub Releases**.
- **Continuous CI (`android-ci.yml`):** Every push or pull request to `main`/`master` runs tests and uploads a downloadable Debug APK artifact.
- **Production Signing Secrets (Optional):** Add `RELEASE_KEYSTORE_BASE64`, `STORE_PASSWORD`, and `KEY_PASSWORD` (key alias `upload`) under **Settings → Secrets and variables → Actions** for consistent update signing.

---

## Vercel Hosting (Web & PWA Companion + QR APK Portal)

This repository also includes `vercel.json` and an installable PWA inside `web/`:
1. Import [`rsk934128-dot/OmniSafe-Browser`](https://github.com/rsk934128-dot/OmniSafe-Browser) in **Vercel** (no custom build command needed — `vercel.json` automatically serves `web/` with strict privacy headers).
2. The deployed Vercel web app provides private search launching, real-time browser WebRTC/fingerprint auditing, a tracking-parameter URL stripper, and automatic APK release fetching from `rsk934128-dot/OmniSafe-Browser` with a client-side scannable **QR code** for mobile installation.
3. For native `.onion` SOCKS5 Tor routing (`127.0.0.1:9050`) and No-Script WebView browsing, scan the QR code or download the Android APK built via GitHub Releases.

---

## OmniSafe-Browser Ecosystem & Roadmap Alignment

| Capability | Desktop (`OmniSafe-Browser` / Electron) | Android (`OnionShield` / Kotlin + WebKit) |
| :--- | :--- | :--- |
| **Dual-Stack Connectivity** | Direct Clearnet / Tor Circuit middleware | Surface Web / `ProxyController` SOCKS5 (`127.0.0.1:9050`) |
| **Script-Shield** | Renderer JS isolation | `javaScriptEnabled = false` by default + `.js`/`.mjs` blocker |
| **Anti-Fingerprinting** | Canvas & renderer noise | Seeded Canvas, WebGL, CPU & UA profile randomization |
| **V3 `.onion` Support** | Embedded Tor binary | Orbot / local Tor daemon + TCP socket health check |
| **Zero-Trace Storage** | Encrypted local vault (Roadmap) | Room local-only storage + one-tap **Shred Session** |

---

## Security & Legal Disclaimer

OnionShield is designed for privacy-conscious browsing, security research, and censorship resistance. Users are solely responsible for complying with applicable local and international laws when accessing Surface Web or `.onion` services. Keep **No-Script Mode** enabled when visiting untrusted sites and verify files before downloading.

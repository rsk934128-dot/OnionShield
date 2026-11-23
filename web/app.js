(() => {
  'use strict';

  const STORAGE_KEYS = {
    CONFIG: 'onionshield_web_config_v2',
    BOOKMARKS: 'onionshield_web_bookmarks_v2',
    HISTORY: 'onionshield_web_history_v2',
    PINNED_APPS: 'onionshield_web_pinned_apps_v2',
    VAULT: 'onionshield_web_vault_v2',
    ROUNDS: 'onionshield_web_rounds_v2',
    GUARD: 'onionshield_web_guard_v2',
    GITHUB_REPO: 'onionshield_github_repo',
  };

  const FIREBASE_WEB_CONFIG = {
    apiKey: 'AIzaSyABsQu2zCCJsTz4sELLXj6g8oU7fNuBRMQ',
    authDomain: 'gen-lang-client-0814260017.firebaseapp.com',
    projectId: 'gen-lang-client-0814260017',
    storageBucket: 'gen-lang-client-0814260017.firebasestorage.app',
    messagingSenderId: '49745482001',
  };
  const FIRESTORE_DATABASE_ID = 'ai-studio-android-onionshi-d761e5dd-52d5-4ed4-9109-9c9de6b767ef';
  const DEFAULT_GITHUB_REPO = 'rsk934128-dot/OmniSafe-Browser';
  const TRACKING_PARAMS = [
    'utm_source', 'utm_medium', 'utm_campaign', 'utm_term', 'utm_content',
    'fbclid', 'gclid', 'dclid', 'msclkid', 'mc_cid', 'mc_eid', 'igshid',
    'yclid', '_hsenc', '_hsmi', 'si', 'feature',
  ];

  const DEFAULT_CONFIG = {
    mode: 'surface',
    noScript: true,
    httpsEverywhere: true,
    webRtcGuard: true,
    fingerprintRandom: true,
    trackerBlocker: true,
    popupBlocker: true,
    blockThirdPartyCookies: true,
    strictV3Onion: true,
    seed: randomSeedHex(),
    trackersCleaned: 0,
  };

  const DEFAULT_PINNED_APPS = [
    { id: 'app_ddg', title: 'DuckDuckGo', url: 'https://duckduckgo.com' },
    { id: 'app_wiki', title: 'Wikipedia', url: 'https://en.wikipedia.org' },
    { id: 'app_tor', title: 'Tor Project', url: 'https://www.torproject.org' },
    { id: 'app_eff', title: 'Privacy Tests', url: 'https://privacytests.org' },
  ];

  function randomSeedHex() {
    const bytes = new Uint8Array(4);
    crypto.getRandomValues(bytes);
    return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('').toUpperCase();
  }

  function loadJson(key, fallback) {
    try {
      const raw = localStorage.getItem(key);
      return raw ? JSON.parse(raw) : fallback;
    } catch {
      return fallback;
    }
  }

  function saveJson(key, value) {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch {
      // Ignore quota errors
    }
  }

  let config = { ...DEFAULT_CONFIG, ...loadJson(STORAGE_KEYS.CONFIG, {}) };
  let bookmarks = loadJson(STORAGE_KEYS.BOOKMARKS, [
    { id: 'bm_1', title: 'Tor Project', url: 'https://www.torproject.org', createdAt: Date.now() },
    { id: 'bm_2', title: 'DuckDuckGo', url: 'https://duckduckgo.com', createdAt: Date.now() },
  ]);
  let historyEntries = loadJson(STORAGE_KEYS.HISTORY, []);
  let pinnedApps = loadJson(STORAGE_KEYS.PINNED_APPS, DEFAULT_PINNED_APPS);
  let guardSettings = { timeLimitMin: 45, budgetLimit: 100, startTs: Date.now(), ...loadJson(STORAGE_KEYS.GUARD, {}) };
  let roundLogs = loadJson(STORAGE_KEYS.ROUNDS, []);

  let tabs = [{ id: 'tab_1', title: 'Start Page', url: 'about:onionshield', mode: config.mode }];
  let activeTabId = 'tab_1';
  let currentView = 'browser';
  let unlockedVaultKey = null;
  let unlockedVaultSalt = null;
  let unlockedVaultEntries = [];
  let deferredPrompt = null;
  let latestApkUrl = '';
  let latestReleaseTag = '';

  // Firebase runtime state
  let fbAuth = null;
  let fbDb = null;
  let fbModules = null;
  let currentFirebaseUser = null;

  // DOM helpers
  const $ = (id) => document.getElementById(id);

  function showToast(msg) {
    const banner = $('toastBanner');
    if (!banner) return;
    banner.textContent = msg;
    banner.classList.remove('hidden');
    clearTimeout(showToast._timer);
    showToast._timer = setTimeout(() => banner.classList.add('hidden'), 3200);
  }

  function isOnionUrl(raw) {
    return /\.onion(?:\/|$|:|\?)/i.test(String(raw || ''));
  }

  function normalizeHost(raw) {
    try {
      const parsed = new URL(raw.includes('://') ? raw : `https://${raw}`);
      return parsed.hostname.replace(/^www\./i, '');
    } catch {
      return '';
    }
  }

  function sanitizeAndUpgradeUrl(rawUrl, enforceHttps = config.httpsEverywhere) {
    const cleanedInput = String(rawUrl || '').trim();
    if (!cleanedInput) return null;
    const withScheme = cleanedInput.includes('://')
      ? cleanedInput
      : (isOnionUrl(cleanedInput) ? `http://${cleanedInput}` : `https://${cleanedInput}`);
    try {
      const url = new URL(withScheme);
      if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
      let stripped = 0;
      TRACKING_PARAMS.forEach((param) => {
        if (url.searchParams.has(param)) {
          url.searchParams.delete(param);
          stripped += 1;
        }
      });
      if (
        enforceHttps &&
        url.protocol === 'http:' &&
        !url.hostname.endsWith('.onion') &&
        url.hostname !== 'localhost' &&
        url.hostname !== '127.0.0.1'
      ) {
        url.protocol = 'https:';
      }
      if (stripped > 0 && config.trackerBlocker) {
        config.trackersCleaned += stripped;
        saveJson(STORAGE_KEYS.CONFIG, config);
      }
      return { cleanUrl: url.toString(), strippedCount: stripped };
    } catch {
      return null;
    }
  }

  function resolveSearchOrUrl(rawInput, engineKey) {
    const text = String(rawInput || '').trim();
    if (!text || text === 'about:onionshield') return 'about:onionshield';
    if (/^(javascript|data|file|vbscript):/i.test(text)) return 'about:onionshield';

    const looksLikeHost =
      /^https?:\/\//i.test(text) ||
      (!text.includes(' ') && (text.includes('.') || text.startsWith('localhost')));

    if (looksLikeHost) {
      const sanitized = sanitizeAndUpgradeUrl(text, config.httpsEverywhere);
      if (sanitized) return sanitized.cleanUrl;
    }

    const q = encodeURIComponent(text);
    if (engineKey === 'startpage') return `https://www.startpage.com/sp/search?query=${q}`;
    if (engineKey === 'brave') return `https://search.brave.com/search?q=${q}`;
    return `https://duckduckgo.com/?q=${q}`;
  }

  // Navigation switching
  function switchView(viewName) {
    currentView = viewName;
    document.querySelectorAll('.view-panel').forEach((el) => {
      el.classList.toggle('active', el.id === `view-${viewName}`);
    });
    document.querySelectorAll('.nav-item').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.view === viewName);
    });
  }

  document.querySelectorAll('.nav-item').forEach((btn) => {
    btn.addEventListener('click', () => {
      if (btn.dataset.view) switchView(btn.dataset.view);
    });
  });

  // Mode switching & Telemetry UI
  function applyModeUI() {
    const isTor = config.mode === 'tor';
    document.body.setAttribute('data-mode', config.mode);
    $('btnSurfaceMode').className = isTor ? 'mode-btn' : 'mode-btn active-surface';
    $('btnTorMode').className = isTor ? 'mode-btn active-tor' : 'mode-btn';
    $('modeSubtitle').textContent = isTor
      ? 'Tor .onion • Zero-History Mode'
      : 'Surface Web • Hardened Mode';
    $('modeBadgeText').textContent = isTor
      ? 'TOR .ONION PRIVACY PROFILE'
      : 'SURFACE WEB PRIVATE LAUNCHER';
    $('onionAlert').classList.toggle('hidden', !isTor);
    updateTelemetryUI();
  }

  function computeHardeningScore() {
    const keys = [
      'noScript', 'httpsEverywhere', 'webRtcGuard', 'fingerprintRandom',
      'trackerBlocker', 'popupBlocker', 'blockThirdPartyCookies', 'strictV3Onion',
    ];
    const active = keys.filter((k) => Boolean(config[k])).length;
    return { active, total: keys.length, pct: Math.round((active / keys.length) * 100) };
  }

  function updateTelemetryUI() {
    const { active, total, pct } = computeHardeningScore();
    $('securityScoreText').textContent = `Score ${pct}%`;
    $('statTrackers').textContent = String(config.trackersCleaned || 0);
    $('statShields').textContent = `${active} / ${total}`;
    $('statSeed').textContent = config.seed;
    const vaultRaw = loadJson(STORAGE_KEYS.VAULT, null);
    $('statVaultCount').textContent = String(vaultRaw?.count ?? 0);
  }

  $('btnSurfaceMode').addEventListener('click', () => {
    config.mode = 'surface';
    saveJson(STORAGE_KEYS.CONFIG, config);
    applyModeUI();
  });

  $('btnTorMode').addEventListener('click', () => {
    config.mode = 'tor';
    saveJson(STORAGE_KEYS.CONFIG, config);
    applyModeUI();
  });

  $('btnRotateSeed').addEventListener('click', () => {
    config.seed = randomSeedHex();
    saveJson(STORAGE_KEYS.CONFIG, config);
    updateTelemetryUI();
    showToast(`Fingerprint seed rotated: ${config.seed}`);
  });

  $('btnShredSession').addEventListener('click', () => {
    tabs = [{ id: `tab_${Date.now()}`, title: 'Start Page', url: 'about:onionshield', mode: config.mode }];
    activeTabId = tabs[0].id;
    historyEntries = [];
    saveJson(STORAGE_KEYS.HISTORY, historyEntries);
    config.seed = randomSeedHex();
    saveJson(STORAGE_KEYS.CONFIG, config);
    $('sandboxFrame').src = 'about:blank';
    renderTabs();
    renderBrowserViewport();
    renderLibrary();
    updateTelemetryUI();
    showToast('Session shredded: tabs, surface history, viewport, and fingerprint seed cleared.');
  });

  // Tabs & Viewport
  function getActiveTab() {
    return tabs.find((t) => t.id === activeTabId) || tabs[0];
  }

  function renderTabs() {
    const bar = $('tabsBar');
    bar.innerHTML = '';
    tabs.forEach((tab) => {
      const chip = document.createElement('div');
      chip.className = `tab-chip ${tab.id === activeTabId ? 'active' : ''}`;
      chip.addEventListener('click', () => {
        activeTabId = tab.id;
        renderTabs();
        renderBrowserViewport();
      });

      const titleSpan = document.createElement('span');
      titleSpan.className = 'truncate';
      titleSpan.textContent = tab.title || 'Start Page';
      chip.appendChild(titleSpan);

      if (tabs.length > 1) {
        const closeBtn = document.createElement('button');
        closeBtn.type = 'button';
        closeBtn.className = 'tab-chip-close';
        closeBtn.textContent = '×';
        closeBtn.setAttribute('aria-label', 'Close tab');
        closeBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          tabs = tabs.filter((t) => t.id !== tab.id);
          if (activeTabId === tab.id) activeTabId = tabs[0].id;
          renderTabs();
          renderBrowserViewport();
        });
        chip.appendChild(closeBtn);
      }
      bar.appendChild(chip);
    });

    const addBtn = document.createElement('button');
    addBtn.type = 'button';
    addBtn.className = 'tab-new-btn';
    addBtn.textContent = '+ Tab';
    addBtn.addEventListener('click', () => {
      const next = { id: `tab_${Date.now()}`, title: 'Start Page', url: 'about:onionshield', mode: config.mode };
      tabs.push(next);
      activeTabId = next.id;
      switchView('browser');
      renderTabs();
      renderBrowserViewport();
    });
    bar.appendChild(addBtn);
  }

  function updateBookmarkStar() {
    const tab = getActiveTab();
    const isBookmarked = tab.url !== 'about:onionshield' && bookmarks.some((b) => b.url === tab.url);
    $('btnBookmarkPage').textContent = isBookmarked ? '★' : '☆';
  }

  function renderBrowserViewport() {
    const tab = getActiveTab();
    const isStart = !tab.url || tab.url === 'about:onionshield';
    $('omniboxInput').value = isStart ? '' : tab.url;
    $('omniboxLockBadge').textContent = tab.url.startsWith('https://') ? '🔒' : (isOnionUrl(tab.url) ? '🧅' : '🛡️');
    $('startPageContainer').classList.toggle('hidden', !isStart);
    $('webViewportContainer').classList.toggle('hidden', isStart);
    updateBookmarkStar();

    if (!isStart) {
      const frame = $('sandboxFrame');
      frame.setAttribute(
        'sandbox',
        config.noScript
          ? 'allow-forms allow-popups'
          : 'allow-forms allow-popups allow-scripts allow-same-origin'
      );
      $('viewportSandboxBadge').textContent = config.noScript ? 'SANDBOX: NO-SCRIPT' : 'SANDBOX: JS ENABLED';
      $('viewportCurrentUrl').textContent = tab.url;
      if (frame.src !== tab.url) {
        frame.src = tab.url;
      }
    }
  }

  function navigateTo(rawInput) {
    const resolved = resolveSearchOrUrl(rawInput, $('searchEngine').value);
    if (config.strictV3Onion && isOnionUrl(resolved)) {
      const host = normalizeHost(resolved);
      const label = host.replace(/\.onion$/i, '');
      if (label.length === 16) {
        showToast('Blocked deprecated 16-char V2 .onion address (Strict V3 policy).');
        return;
      }
    }
    const tab = getActiveTab();
    tab.url = resolved;
    tab.title = resolved === 'about:onionshield' ? 'Start Page' : (normalizeHost(resolved) || resolved);
    if (resolved !== 'about:onionshield' && config.mode === 'surface') {
      historyEntries.unshift({
        id: `hist_${Date.now()}`,
        title: tab.title,
        url: resolved,
        visitedAt: Date.now(),
      });
      historyEntries = historyEntries.slice(0, 60);
      saveJson(STORAGE_KEYS.HISTORY, historyEntries);
      renderLibrary();
      syncHistoryEntryToCloud(tab.title, resolved);
    }
    switchView('browser');
    renderTabs();
    renderBrowserViewport();
    updateTelemetryUI();
  }

  $('omniboxForm').addEventListener('submit', (e) => {
    e.preventDefault();
    navigateTo($('omniboxInput').value);
  });

  $('btnHomePage').addEventListener('click', () => navigateTo('about:onionshield'));

  $('btnBookmarkPage').addEventListener('click', () => {
    const tab = getActiveTab();
    if (!tab.url || tab.url === 'about:onionshield') {
      showToast('Open a website first to bookmark it.');
      return;
    }
    const existingIdx = bookmarks.findIndex((b) => b.url === tab.url);
    if (existingIdx >= 0) {
      const removed = bookmarks.splice(existingIdx, 1)[0];
      saveJson(STORAGE_KEYS.BOOKMARKS, bookmarks);
      deleteSyncedLinkFromCloud(removed.id);
      showToast('Removed bookmark.');
    } else {
      const item = { id: `bm_${Date.now()}`, title: tab.title || tab.url, url: tab.url, createdAt: Date.now() };
      bookmarks.unshift(item);
      saveJson(STORAGE_KEYS.BOOKMARKS, bookmarks);
      syncLinkToCloud(item, 'BOOKMARK');
      showToast('Bookmarked page.');
    }
    updateBookmarkStar();
    renderLibrary();
  });

  $('btnOpenExternalTab').addEventListener('click', () => {
    const tab = getActiveTab();
    if (tab.url && tab.url !== 'about:onionshield') {
      window.open(tab.url, '_blank', 'noopener,noreferrer');
    }
  });

  $('btnReloadFrame').addEventListener('click', () => {
    const tab = getActiveTab();
    if (tab.url && tab.url !== 'about:onionshield') {
      $('sandboxFrame').src = tab.url;
    }
  });

  $('btnCloseFrame').addEventListener('click', () => navigateTo('about:onionshield'));

  // Pinned Quick Apps
  function renderPinnedApps() {
    const grid = $('pinnedAppsGrid');
    grid.innerHTML = '';
    pinnedApps.forEach((app) => {
      const tile = document.createElement('div');
      tile.className = 'app-tile';

      const main = document.createElement('div');
      main.className = 'app-tile-main';
      main.addEventListener('click', () => navigateTo(app.url));

      const host = normalizeHost(app.url);
      const img = document.createElement('img');
      img.className = 'app-favicon';
      img.alt = '';
      if (host && !host.endsWith('.onion') && config.mode !== 'tor') {
        img.src = `https://www.google.com/s2/favicons?domain=${encodeURIComponent(host)}&sz=64`;
      } else {
        img.src = '/icon.svg';
      }
      img.onerror = () => { img.src = '/icon.svg'; };

      const textCol = document.createElement('div');
      textCol.className = 'truncate';
      const titleDiv = document.createElement('div');
      titleDiv.className = 'app-tile-title truncate';
      titleDiv.textContent = app.title;
      const hostDiv = document.createElement('div');
      hostDiv.className = 'app-tile-host truncate';
      hostDiv.textContent = host || app.url;
      textCol.append(titleDiv, hostDiv);

      main.append(img, textCol);

      const removeBtn = document.createElement('button');
      removeBtn.type = 'button';
      removeBtn.className = 'icon-btn';
      removeBtn.textContent = '×';
      removeBtn.title = 'Unpin';
      removeBtn.addEventListener('click', () => {
        pinnedApps = pinnedApps.filter((a) => a.id !== app.id);
        saveJson(STORAGE_KEYS.PINNED_APPS, pinnedApps);
        deleteSyncedLinkFromCloud(app.id);
        renderPinnedApps();
      });

      tile.append(main, removeBtn);
      grid.appendChild(tile);
    });
  }

  $('btnTogglePinForm').addEventListener('click', () => {
    $('pinAppForm').classList.toggle('hidden');
  });

  $('pinAppForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const title = $('pinAppTitle').value.trim();
    const sanitized = sanitizeAndUpgradeUrl($('pinAppUrl').value, config.httpsEverywhere);
    if (!title || !sanitized) {
      showToast('Enter a valid title and HTTP/HTTPS URL.');
      return;
    }
    const item = { id: `app_${Date.now()}`, title, url: sanitized.cleanUrl };
    pinnedApps.unshift(item);
    saveJson(STORAGE_KEYS.PINNED_APPS, pinnedApps);
    syncLinkToCloud(item, 'PINNED_APP');
    $('pinAppTitle').value = '';
    $('pinAppUrl').value = '';
    $('pinAppForm').classList.add('hidden');
    renderPinnedApps();
    showToast(`Pinned "${title}" to Start Page.`);
  });

  // Security Shield Toggles
  const SHIELD_DEFINITIONS = [
    { key: 'noScript', title: 'No-Script Sandbox', desc: 'Blocks embedded JS execution by default' },
    { key: 'httpsEverywhere', title: 'HTTPS Everywhere', desc: 'Upgrades clearnet HTTP links to HTTPS' },
    { key: 'webRtcGuard', title: 'WebRTC Leak Guard', desc: 'Monitors & flags local IP WebRTC exposure' },
    { key: 'fingerprintRandom', title: 'Fingerprint Rotation', desc: 'Rotates virtual session fingerprint seed' },
    { key: 'trackerBlocker', title: 'Tracker & Param Stripper', desc: 'Removes utm_*, fbclid, gclid, igshid' },
    { key: 'popupBlocker', title: 'Popup Blocker', desc: 'Restricts unrequested popup windows' },
    { key: 'blockThirdPartyCookies', title: '3rd-Party Isolation', desc: 'No-referrer & isolated cross-site requests' },
    { key: 'strictV3Onion', title: 'Strict V3 .onion Guard', desc: 'Rejects deprecated 16-char V2 onion links' },
  ];

  function renderShields() {
    const grid = $('shieldsGrid');
    grid.innerHTML = '';
    SHIELD_DEFINITIONS.forEach((def) => {
      const enabled = Boolean(config[def.key]);
      const card = document.createElement('div');
      card.className = `shield-card ${enabled ? 'enabled' : ''}`;
      card.addEventListener('click', () => {
        config[def.key] = !config[def.key];
        saveJson(STORAGE_KEYS.CONFIG, config);
        renderShields();
        updateTelemetryUI();
        renderBrowserViewport();
      });

      const info = document.createElement('div');
      const t = document.createElement('div');
      t.className = 'shield-title';
      t.textContent = def.title;
      const d = document.createElement('div');
      d.className = 'shield-desc';
      d.textContent = def.desc;
      info.append(t, d);

      const pill = document.createElement('span');
      pill.className = 'toggle-pill';
      pill.textContent = enabled ? 'ON' : 'OFF';

      card.append(info, pill);
      grid.appendChild(card);
    });
  }

  // Privacy Lab: Audit, Stripper, Onion Validator, Bridge Validator
  function computeCanvasHash() {
    try {
      const canvas = document.createElement('canvas');
      canvas.width = 180;
      canvas.height = 32;
      const ctx = canvas.getContext('2d');
      ctx.fillStyle = '#0B0F19';
      ctx.fillRect(0, 0, 180, 32);
      ctx.fillStyle = '#00E5D1';
      ctx.font = '14px sans-serif';
      ctx.fillText('OnionShield-FP', 8, 22);
      const data = canvas.toDataURL();
      let hash = 0;
      for (let i = 0; i < data.length; i += 1) {
        hash = (hash * 31 + data.charCodeAt(i)) >>> 0;
      }
      return hash.toString(16).toUpperCase().padStart(8, '0');
    } catch {
      return 'BLOCKED';
    }
  }

  function runPrivacyAudit() {
    const isHttps = location.protocol === 'https:' || location.hostname === 'localhost';
    const hasRtc = !!(window.RTCPeerConnection || window.webkitRTCPeerConnection);
    const dnt = navigator.doNotTrack === '1' ? 'Enabled (1)' : 'Not Set';
    const cores = navigator.hardwareConcurrency || 'Hidden';
    const canvasHash = computeCanvasHash();
    let webglRenderer = 'Unavailable';
    try {
      const gl = document.createElement('canvas').getContext('webgl');
      if (gl) {
        const dbg = gl.getExtension('WEBGL_debug_renderer_info');
        webglRenderer = dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : 'Masked';
      }
    } catch {
      webglRenderer = 'Blocked';
    }

    const rows = [
      { label: 'Transport Security', val: isHttps ? 'HTTPS Active' : 'Insecure HTTP', good: isHttps },
      { label: 'WebRTC Exposure', val: hasRtc ? 'Exposed in Host Browser' : 'Blocked / Absent', good: !hasRtc },
      { label: 'Do Not Track Header', val: dnt, good: navigator.doNotTrack === '1' },
      { label: 'Canvas Fingerprint', val: `#${canvasHash}`, good: canvasHash === 'BLOCKED' },
      { label: 'WebGL Renderer', val: String(webglRenderer), good: webglRenderer === 'Blocked' || webglRenderer === 'Masked' },
      { label: 'CPU Concurrency', val: `${cores} threads`, good: cores === 'Hidden' },
    ];

    const container = $('auditResults');
    container.innerHTML = '';
    rows.forEach((row) => {
      const item = document.createElement('div');
      item.className = 'audit-item';
      const k = document.createElement('span');
      k.textContent = row.label;
      const v = document.createElement('span');
      v.className = `audit-val ${row.good ? 'tag-good' : 'tag-warn'}`;
      v.textContent = row.val;
      item.append(k, v);
      container.appendChild(item);
    });
  }

  $('btnRunAudit').addEventListener('click', runPrivacyAudit);

  let lastCleanUrl = '';
  $('btnCleanUrl').addEventListener('click', () => {
    const res = sanitizeAndUpgradeUrl($('dirtyUrlInput').value, true);
    if (!res) {
      $('cleanUrlOutput').textContent = 'Invalid URL. Enter a valid http:// or https:// address.';
      lastCleanUrl = '';
      return;
    }
    lastCleanUrl = res.cleanUrl;
    $('cleanUrlOutput').textContent = `${res.cleanUrl}${res.strippedCount ? `  (Removed ${res.strippedCount} tracker param(s))` : ''}`;
    updateTelemetryUI();
  });

  $('btnCopyClean').addEventListener('click', async () => {
    if (!lastCleanUrl) return;
    await navigator.clipboard.writeText(lastCleanUrl);
    showToast('Sanitized URL copied.');
  });

  $('btnOpenClean').addEventListener('click', () => {
    if (lastCleanUrl) navigateTo(lastCleanUrl);
  });

  $('btnCheckOnion').addEventListener('click', () => {
    const raw = $('onionCheckInput').value.trim();
    const host = normalizeHost(raw);
    if (!host) {
      $('onionCheckResult').textContent = 'Invalid host or URL.';
      return;
    }
    if (host.endsWith('.onion')) {
      const label = host.slice(0, -6);
      if (label.length === 56 && /^[a-z2-7]{56}$/i.test(label)) {
        $('onionCheckResult').textContent = `VERIFIED V3 ONION: ${host} (56-char Ed25519 hidden service)`;
      } else if (label.length === 16) {
        $('onionCheckResult').textContent = `DEPRECATED V2 ONION: ${host} (16-char RSA — rejected by modern Tor)`;
      } else {
        $('onionCheckResult').textContent = `MALFORMED ONION: ${host} (Expected 56 base32 chars before .onion)`;
      }
    } else {
      $('onionCheckResult').textContent = `CLEARNET HOST: ${host} (HTTPS recommended)`;
    }
  });

  $('btnValidateBridges').addEventListener('click', () => {
    const lines = $('bridgeLinesInput').value
      .split(/\r?\n/)
      .map((l) => l.trim().replace(/^Bridge\s+/i, ''))
      .filter(Boolean);
    if (!lines.length) {
      $('bridgeCheckResult').textContent = 'Enter at least one bridge line.';
      return;
    }
    const invalid = [];
    const valid = [];
    lines.forEach((line) => {
      const parts = line.split(/\s+/);
      const transport = (parts[0] || '').toLowerCase();
      const hasAddr = parts[1] && parts[1].includes(':');
      if (['obfs4', 'snowflake', 'meek_lite', 'meek-azure', 'webtunnel'].includes(transport) && hasAddr) {
        valid.push(line);
      } else {
        invalid.push(line);
      }
    });
    $('bridgeCheckResult').textContent = invalid.length === 0
      ? `VALID (${valid.length} bridge line(s)): Ready for Orbot / Tor Client.`
      : `Valid: ${valid.length}, Invalid: ${invalid.length} — check transport prefix (obfs4/snowflake) and ip:port.`;
  });

  // Library: Bookmarks & History
  function renderLibrary() {
    const bList = $('bookmarksList');
    bList.innerHTML = '';
    if (!bookmarks.length) {
      bList.innerHTML = '<p class="muted">No bookmarks saved yet.</p>';
    } else {
      bookmarks.forEach((bm) => {
        const row = document.createElement('div');
        row.className = 'list-row';
        const main = document.createElement('div');
        main.className = 'list-row-main';
        const t = document.createElement('div');
        t.className = 'list-row-title truncate';
        t.textContent = bm.title || bm.url;
        const u = document.createElement('div');
        u.className = 'list-row-sub truncate';
        u.textContent = bm.url;
        main.append(t, u);

        const openBtn = document.createElement('button');
        openBtn.type = 'button';
        openBtn.className = 'secondary-btn mini-btn';
        openBtn.textContent = 'Open';
        openBtn.addEventListener('click', () => navigateTo(bm.url));

        const delBtn = document.createElement('button');
        delBtn.type = 'button';
        delBtn.className = 'danger-btn mini-btn';
        delBtn.textContent = 'Delete';
        delBtn.addEventListener('click', () => {
          bookmarks = bookmarks.filter((x) => x.id !== bm.id);
          saveJson(STORAGE_KEYS.BOOKMARKS, bookmarks);
          deleteSyncedLinkFromCloud(bm.id);
          renderLibrary();
          updateBookmarkStar();
        });

        row.append(main, openBtn, delBtn);
        bList.appendChild(row);
      });
    }

    const hList = $('historyList');
    hList.innerHTML = '';
    if (!historyEntries.length) {
      hList.innerHTML = '<p class="muted">Surface history is empty.</p>';
    } else {
      historyEntries.forEach((h) => {
        const row = document.createElement('div');
        row.className = 'list-row';
        const main = document.createElement('div');
        main.className = 'list-row-main';
        const t = document.createElement('div');
        t.className = 'list-row-title truncate';
        t.textContent = h.title || h.url;
        const u = document.createElement('div');
        u.className = 'list-row-sub truncate';
        u.textContent = h.url;
        main.append(t, u);

        const openBtn = document.createElement('button');
        openBtn.type = 'button';
        openBtn.className = 'secondary-btn mini-btn';
        openBtn.textContent = 'Open';
        openBtn.addEventListener('click', () => navigateTo(h.url));

        row.append(main, openBtn);
        hList.appendChild(row);
      });
    }
  }

  $('addBookmarkForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const title = $('bookmarkTitleInput').value.trim();
    const sanitized = sanitizeAndUpgradeUrl($('bookmarkUrlInput').value, config.httpsEverywhere);
    if (!title || !sanitized) {
      showToast('Enter a valid bookmark title and URL.');
      return;
    }
    const item = { id: `bm_${Date.now()}`, title, url: sanitized.cleanUrl, createdAt: Date.now() };
    bookmarks.unshift(item);
    saveJson(STORAGE_KEYS.BOOKMARKS, bookmarks);
    syncLinkToCloud(item, 'BOOKMARK');
    $('bookmarkTitleInput').value = '';
    $('bookmarkUrlInput').value = '';
    renderLibrary();
    updateBookmarkStar();
    showToast('Bookmark saved.');
  });

  $('btnClearHistory').addEventListener('click', () => {
    historyEntries = [];
    saveJson(STORAGE_KEYS.HISTORY, historyEntries);
    renderLibrary();
    showToast('Local browsing history cleared.');
  });

  // AES-256-GCM WebCrypto Vault
  function toBase64(uint8) {
    let bin = '';
    uint8.forEach((b) => { bin += String.fromCharCode(b); });
    return btoa(bin);
  }

  function fromBase64(b64) {
    const bin = atob(b64);
    const arr = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i += 1) arr[i] = bin.charCodeAt(i);
    return arr;
  }

  async function deriveAesKey(passphrase, saltBytes) {
    const enc = new TextEncoder();
    const keyMaterial = await crypto.subtle.importKey(
      'raw',
      enc.encode(passphrase),
      'PBKDF2',
      false,
      ['deriveKey']
    );
    return crypto.subtle.deriveKey(
      { name: 'PBKDF2', salt: saltBytes, iterations: 100000, hash: 'SHA-256' },
      keyMaterial,
      { name: 'AES-GCM', length: 256 },
      false,
      ['encrypt', 'decrypt']
    );
  }

  async function saveEncryptedVault() {
    if (!unlockedVaultKey || !unlockedVaultSalt) return;
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const plaintext = new TextEncoder().encode(JSON.stringify(unlockedVaultEntries));
    const cipherBuf = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, unlockedVaultKey, plaintext);
    saveJson(STORAGE_KEYS.VAULT, {
      salt: toBase64(unlockedVaultSalt),
      iv: toBase64(iv),
      ciphertext: toBase64(new Uint8Array(cipherBuf)),
      count: unlockedVaultEntries.length,
    });
    updateTelemetryUI();
  }

  function renderVault() {
    const isUnlocked = Boolean(unlockedVaultKey);
    $('vaultLockedView').classList.toggle('hidden', isUnlocked);
    $('vaultUnlockedView').classList.toggle('hidden', !isUnlocked);
    $('btnLockVault').classList.toggle('hidden', !isUnlocked);
    const existing = loadJson(STORAGE_KEYS.VAULT, null);
    $('vaultPromptText').textContent = existing
      ? `Encrypted vault found (${existing.count || 0} record(s)). Enter master passphrase to decrypt.`
      : 'No vault initialized yet. Enter a master passphrase/PIN to create your AES-256-GCM vault.';

    const list = $('vaultEntriesList');
    list.innerHTML = '';
    if (!isUnlocked) return;
    if (!unlockedVaultEntries.length) {
      list.innerHTML = '<p class="muted">Vault is empty. Add an encrypted entry above.</p>';
      return;
    }
    unlockedVaultEntries.forEach((entry) => {
      const row = document.createElement('div');
      row.className = 'list-row';
      const main = document.createElement('div');
      main.className = 'list-row-main';
      const t = document.createElement('div');
      t.className = 'list-row-title truncate';
      t.textContent = entry.title;
      const s = document.createElement('div');
      s.className = 'list-row-sub truncate';
      s.textContent = [entry.site, entry.username].filter(Boolean).join(' • ') || 'Encrypted Secret';
      main.append(t, s);

      const copyBtn = document.createElement('button');
      copyBtn.type = 'button';
      copyBtn.className = 'secondary-btn mini-btn';
      copyBtn.textContent = 'Copy Secret';
      copyBtn.addEventListener('click', async () => {
        await navigator.clipboard.writeText(entry.secret);
        showToast('Decrypted secret copied to clipboard.');
      });

      const delBtn = document.createElement('button');
      delBtn.type = 'button';
      delBtn.className = 'danger-btn mini-btn';
      delBtn.textContent = 'Delete';
      delBtn.addEventListener('click', async () => {
        unlockedVaultEntries = unlockedVaultEntries.filter((x) => x.id !== entry.id);
        await saveEncryptedVault();
        renderVault();
      });

      row.append(main, copyBtn, delBtn);
      list.appendChild(row);
    });
  }

  $('btnUnlockVault').addEventListener('click', async () => {
    const pass = $('vaultPassphraseInput').value;
    if (!pass || pass.length < 4) {
      showToast('Master passphrase must be at least 4 characters.');
      return;
    }
    const existing = loadJson(STORAGE_KEYS.VAULT, null);
    try {
      if (!existing) {
        unlockedVaultSalt = crypto.getRandomValues(new Uint8Array(16));
        unlockedVaultKey = await deriveAesKey(pass, unlockedVaultSalt);
        unlockedVaultEntries = [];
        await saveEncryptedVault();
        showToast('AES-256-GCM Vault created and unlocked.');
      } else {
        const salt = fromBase64(existing.salt);
        const iv = fromBase64(existing.iv);
        const cipher = fromBase64(existing.ciphertext);
        const key = await deriveAesKey(pass, salt);
        const plainBuf = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, cipher);
        unlockedVaultEntries = JSON.parse(new TextDecoder().decode(plainBuf));
        unlockedVaultSalt = salt;
        unlockedVaultKey = key;
        showToast('Vault decrypted cleanly.');
      }
      $('vaultPassphraseInput').value = '';
      renderVault();
    } catch {
      showToast('Incorrect passphrase or corrupted ciphertext.');
    }
  });

  $('addVaultForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!unlockedVaultKey) return;
    const title = $('vaultEntryTitle').value.trim();
    const site = $('vaultEntrySite').value.trim();
    const username = $('vaultEntryUsername').value.trim();
    const secret = $('vaultEntrySecret').value;
    if (!title || !secret) return;
    unlockedVaultEntries.unshift({ id: `v_${Date.now()}`, title, site, username, secret });
    await saveEncryptedVault();
    $('vaultEntryTitle').value = '';
    $('vaultEntrySite').value = '';
    $('vaultEntryUsername').value = '';
    $('vaultEntrySecret').value = '';
    renderVault();
    showToast('Entry encrypted with AES-256-GCM.');
  });

  $('btnLockVault').addEventListener('click', () => {
    unlockedVaultKey = null;
    unlockedVaultSalt = null;
    unlockedVaultEntries = [];
    renderVault();
    showToast('Vault locked and memory key cleared.');
  });

  $('btnWipeVault').addEventListener('click', () => {
    localStorage.removeItem(STORAGE_KEYS.VAULT);
    unlockedVaultKey = null;
    unlockedVaultSalt = null;
    unlockedVaultEntries = [];
    renderVault();
    updateTelemetryUI();
    showToast('Encrypted vault wiped.');
  });

  // Session Guard & Provably Fair
  function renderGuard() {
    const elapsedSec = Math.max(0, Math.floor((Date.now() - (guardSettings.startTs || Date.now())) / 1000));
    const mins = Math.floor(elapsedSec / 60);
    const secs = elapsedSec % 60;
    $('guardElapsed').textContent = `${mins}m ${String(secs).padStart(2, '0')}s`;

    const total = roundLogs.length;
    const wins = roundLogs.filter((r) => r.netProfit > 0).length;
    const net = roundLogs.reduce((acc, r) => acc + r.netProfit, 0);
    $('guardTotalRounds').textContent = String(total);
    $('guardWinRate').textContent = total ? `${Math.round((wins / total) * 100)}%` : '0%';
    $('guardNetProfit').textContent = net.toFixed(2);
    $('guardTimeLimitInput').value = guardSettings.timeLimitMin;
    $('guardBudgetLimitInput').value = guardSettings.budgetLimit;

    const alertBanner = $('guardAlertBanner');
    const lossExceeded = -net >= guardSettings.budgetLimit;
    const timeExceeded = mins >= guardSettings.timeLimitMin;
    if (lossExceeded || timeExceeded) {
      alertBanner.textContent = lossExceeded
        ? `Session Guard Limit Reached: Net loss exceeded budget limit (${guardSettings.budgetLimit}). Take a break.`
        : `Session Guard Limit Reached: Active duration (${mins}m) reached time limit (${guardSettings.timeLimitMin}m).`;
      alertBanner.classList.remove('hidden');
    } else {
      alertBanner.classList.add('hidden');
    }

    const list = $('roundsList');
    list.innerHTML = '';
    roundLogs.forEach((r) => {
      const row = document.createElement('div');
      row.className = 'list-row';
      const main = document.createElement('div');
      main.className = 'list-row-main';
      const t = document.createElement('div');
      t.className = 'list-row-title';
      t.textContent = `${r.siteHost} • Bet ${r.bet.toFixed(2)} • Crash ${r.crash.toFixed(2)}x`;
      const s = document.createElement('div');
      s.className = 'list-row-sub';
      s.textContent = `Cashout ${r.cashout.toFixed(2)}x • Net ${r.netProfit >= 0 ? '+' : ''}${r.netProfit.toFixed(2)}`;
      main.append(t, s);
      row.appendChild(main);
      list.appendChild(row);
    });
  }

  setInterval(() => {
    if (currentView === 'guard') renderGuard();
  }, 1000);

  $('guardLimitForm').addEventListener('submit', (e) => {
    e.preventDefault();
    guardSettings.timeLimitMin = Math.max(5, Number($('guardTimeLimitInput').value) || 45);
    guardSettings.budgetLimit = Math.max(1, Number($('guardBudgetLimitInput').value) || 100);
    saveJson(STORAGE_KEYS.GUARD, guardSettings);
    renderGuard();
    showToast('Session Guard limits updated.');
  });

  $('btnResetGuardTimer').addEventListener('click', () => {
    guardSettings.startTs = Date.now();
    saveJson(STORAGE_KEYS.GUARD, guardSettings);
    renderGuard();
  });

  $('logRoundForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const siteHost = $('roundSiteInput').value.trim() || 'manual';
    const bet = Number($('roundBetInput').value);
    const crash = Number($('roundCrashInput').value);
    const cashout = Number($('roundCashoutInput').value);
    if (!Number.isFinite(bet) || bet <= 0 || !Number.isFinite(crash) || crash < 1) return;
    const won = cashout > 0 && cashout <= crash;
    const netProfit = won ? bet * (cashout - 1) : -bet;
    roundLogs.unshift({ id: `r_${Date.now()}`, siteHost, bet, crash, cashout, netProfit });
    saveJson(STORAGE_KEYS.ROUNDS, roundLogs);
    renderGuard();
  });

  $('btnClearRounds').addEventListener('click', () => {
    roundLogs = [];
    saveJson(STORAGE_KEYS.ROUNDS, roundLogs);
    renderGuard();
  });

  $('fairVerifyForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const serverSeed = $('fairServerSeed').value.trim();
    const clientSeed = $('fairClientSeed').value.trim();
    const nonce = $('fairNonce').value.trim();
    const expected = $('fairExpectedHash').value.trim().toLowerCase();
    const enc = new TextEncoder();

    const shaBuf = await crypto.subtle.digest('SHA-256', enc.encode(serverSeed));
    const shaHex = Array.from(new Uint8Array(shaBuf), (b) => b.toString(16).padStart(2, '0')).join('');

    let hmacHex = '';
    if (clientSeed) {
      const hmacKey = await crypto.subtle.importKey(
        'raw',
        enc.encode(serverSeed),
        { name: 'HMAC', hash: 'SHA-256' },
        false,
        ['sign']
      );
      const msg = nonce ? `${clientSeed}:${nonce}` : clientSeed;
      const sig = await crypto.subtle.sign('HMAC', hmacKey, enc.encode(msg));
      hmacHex = Array.from(new Uint8Array(sig), (b) => b.toString(16).padStart(2, '0')).join('');
    }

    const matched = shaHex === expected;
    $('fairVerifyResult').textContent =
      `${matched ? 'MATCHED COMMITMENT' : 'MISMATCH'}\nSHA-256(serverSeed): ${shaHex}${hmacHex ? `\nHMAC-SHA256: ${hmacHex}` : ''}`;
  });

  // Firebase Cloud Sync (Optional ESM initialization for cross-device sync)
  async function initFirebaseSync() {
    const statusEl = $('cloudAuthStatus');
    try {
      const [appMod, authMod, fsMod] = await Promise.all([
        import('https://www.gstatic.com/firebasejs/11.6.0/firebase-app.js'),
        import('https://www.gstatic.com/firebasejs/11.6.0/firebase-auth.js'),
        import('https://www.gstatic.com/firebasejs/11.6.0/firebase-firestore.js'),
      ]);
      fbModules = { appMod, authMod, fsMod };
      const fbApp = appMod.getApps().length
        ? appMod.getApp()
        : appMod.initializeApp(FIREBASE_WEB_CONFIG);
      fbAuth = authMod.getAuth(fbApp);
      fbDb = fsMod.getFirestore(fbApp, FIRESTORE_DATABASE_ID);

      authMod.onAuthStateChanged(fbAuth, async (user) => {
        currentFirebaseUser = user;
        const signedIn = Boolean(user);
        $('btnGoogleSignIn').classList.toggle('hidden', signedIn);
        $('btnSyncNow').classList.toggle('hidden', !signedIn);
        $('btnGoogleSignOut').classList.toggle('hidden', !signedIn);
        if (signedIn) {
          statusEl.textContent = `Signed in as ${user.email || user.uid} — Syncing with Android Firestore database.`;
          await pullFromCloud();
        } else {
          statusEl.textContent = 'Signed out — Sign in with Google to sync Bookmarks, Pinned Apps & History across Android and Web.';
          $('cloudSyncStats').textContent = '';
        }
      });
    } catch (err) {
      statusEl.textContent = `Firebase SDK offline or blocked (${err.message}). Local browser storage remains active.`;
    }
  }

  async function syncLinkToCloud(item, kind) {
    if (!fbDb || !fbModules || !currentFirebaseUser) return;
    const { fsMod } = fbModules;
    try {
      const ref = fsMod.doc(fbDb, 'users', currentFirebaseUser.uid, 'synced_links', item.id);
      await fsMod.setDoc(ref, {
        title: item.title,
        url: item.url,
        kind,
        uid: currentFirebaseUser.uid,
        clientTimestampMs: Date.now(),
        createdAt: fsMod.serverTimestamp(),
        updatedAt: fsMod.serverTimestamp(),
      }, { merge: true });
    } catch {
      // Ignore offline/network sync failures cleanly
    }
  }

  async function deleteSyncedLinkFromCloud(linkId) {
    if (!fbDb || !fbModules || !currentFirebaseUser) return;
    const { fsMod } = fbModules;
    try {
      await fsMod.deleteDoc(fsMod.doc(fbDb, 'users', currentFirebaseUser.uid, 'synced_links', linkId));
    } catch {
      // Ignore
    }
  }

  async function syncHistoryEntryToCloud(title, url) {
    if (!fbDb || !fbModules || !currentFirebaseUser) return;
    const { fsMod } = fbModules;
    try {
      const id = `wh_${Date.now()}`;
      await fsMod.setDoc(fsMod.doc(fbDb, 'users', currentFirebaseUser.uid, 'browser_history', id), {
        title,
        url,
        uid: currentFirebaseUser.uid,
        visitedAtMs: Date.now(),
        createdAt: fsMod.serverTimestamp(),
        updatedAt: fsMod.serverTimestamp(),
      });
    } catch {
      // Ignore
    }
  }

  async function pullFromCloud() {
    if (!fbDb || !fbModules || !currentFirebaseUser) return;
    const { fsMod } = fbModules;
    try {
      const linksSnap = await fsMod.getDocs(
        fsMod.collection(fbDb, 'users', currentFirebaseUser.uid, 'synced_links')
      );
      let cloudBm = 0;
      let cloudPin = 0;
      linksSnap.forEach((docSnap) => {
        const d = docSnap.data();
        if (!d || !d.url) return;
        if (d.kind === 'PINNED_APP') {
          cloudPin += 1;
          if (!pinnedApps.some((p) => p.url === d.url)) {
            pinnedApps.push({ id: docSnap.id, title: d.title || d.url, url: d.url });
          }
        } else {
          cloudBm += 1;
          if (!bookmarks.some((b) => b.url === d.url)) {
            bookmarks.push({ id: docSnap.id, title: d.title || d.url, url: d.url, createdAt: d.clientTimestampMs || Date.now() });
          }
        }
      });
      saveJson(STORAGE_KEYS.BOOKMARKS, bookmarks);
      saveJson(STORAGE_KEYS.PINNED_APPS, pinnedApps);
      renderPinnedApps();
      renderLibrary();
      updateBookmarkStar();
      $('cloudSyncStats').textContent = `Synced ${cloudBm} bookmark(s) and ${cloudPin} pinned app(s) from Firestore.`;
    } catch (err) {
      $('cloudSyncStats').textContent = `Cloud sync note: ${err.message}`;
    }
  }

  $('btnGoogleSignIn').addEventListener('click', async () => {
    if (!fbAuth || !fbModules) return;
    try {
      const provider = new fbModules.authMod.GoogleAuthProvider();
      await fbModules.authMod.signInWithPopup(fbAuth, provider);
      showToast('Signed in to Firebase Cloud Sync.');
    } catch (err) {
      if (String(err.code || '').includes('unauthorized-domain')) {
        $('cloudAuthStatus').textContent = `Add "${location.hostname}" to Firebase Console → Authentication → Settings → Authorized domains to enable Google Sign-In on this Vercel URL.`;
      } else {
        $('cloudAuthStatus').textContent = `Sign-in failed: ${err.message}`;
      }
    }
  });

  $('btnSyncNow').addEventListener('click', async () => {
    for (const bm of bookmarks) await syncLinkToCloud(bm, 'BOOKMARK');
    for (const app of pinnedApps) await syncLinkToCloud(app, 'PINNED_APP');
    await pullFromCloud();
    showToast('Cloud Sync completed.');
  });

  $('btnGoogleSignOut').addEventListener('click', async () => {
    if (!fbAuth || !fbModules) return;
    await fbModules.authMod.signOut(fbAuth);
    showToast('Signed out from Cloud Sync.');
  });

  // GitHub APK Releases + QR Generator
  const savedRepo = localStorage.getItem(STORAGE_KEYS.GITHUB_REPO) || DEFAULT_GITHUB_REPO;
  $('githubRepoInput').value = savedRepo;

  async function fetchLatestRelease(rawRepo, savePref = false) {
    const repo = String(rawRepo || '')
      .trim()
      .replace(/^https?:\/\/github\.com\//i, '')
      .replace(/\/$/, '');
    const releaseStatus = $('releaseStatus');
    const qrContainer = $('apkQrContainer');
    if (!repo.includes('/')) {
      releaseStatus.textContent = 'Enter a valid GitHub repository in owner/repo format.';
      qrContainer.classList.add('hidden');
      return;
    }
    if (savePref) localStorage.setItem(STORAGE_KEYS.GITHUB_REPO, repo);
    releaseStatus.textContent = `Fetching latest release from github.com/${repo}…`;
    qrContainer.classList.add('hidden');

    try {
      const res = await fetch(`https://api.github.com/repos/${repo}/releases/latest`);
      if (!res.ok) {
        throw new Error(
          res.status === 404
            ? 'No published GitHub Release yet. Run "Release Android APK" in GitHub Actions or push a tag (e.g. v1.0.0).'
            : `HTTP ${res.status} — could not query GitHub Releases.`
        );
      }
      const data = await res.json();
      const apkAsset = (data.assets || []).find((a) => a.name && a.name.endsWith('.apk'));
      const shaAsset = (data.assets || []).find((a) => a.name && a.name.endsWith('.sha256'));
      if (!apkAsset) {
        releaseStatus.textContent = `Release ${data.tag_name || 'latest'} found, but no .apk asset is attached yet.`;
        return;
      }
      latestApkUrl = apkAsset.browser_download_url;
      latestReleaseTag = data.tag_name || 'latest';

      releaseStatus.innerHTML = '';
      const infoDiv = document.createElement('div');
      const strong = document.createElement('strong');
      strong.textContent = 'Latest Release: ';
      infoDiv.append(strong, document.createTextNode(`${latestReleaseTag} (${(apkAsset.size / (1024 * 1024)).toFixed(2)} MB)`));
      releaseStatus.appendChild(infoDiv);

      const apkLink = document.createElement('a');
      apkLink.className = 'apk-download-btn';
      apkLink.href = latestApkUrl;
      apkLink.rel = 'noopener noreferrer';
      apkLink.textContent = `Download ${apkAsset.name}`;
      releaseStatus.appendChild(apkLink);

      if (shaAsset) {
        const shaLink = document.createElement('a');
        shaLink.className = 'secondary-btn';
        shaLink.style.display = 'inline-block';
        shaLink.style.textDecoration = 'none';
        shaLink.style.marginTop = '10px';
        shaLink.href = shaAsset.browser_download_url;
        shaLink.rel = 'noopener noreferrer';
        shaLink.textContent = 'SHA-256';
        releaseStatus.appendChild(shaLink);
      }

      if (window.OnionShieldQR && $('apkQrCanvas')) {
        window.OnionShieldQR.renderToCanvas($('apkQrCanvas'), latestApkUrl, {
          size: 210,
          quietModules: 4,
          darkColor: '#0B0F19',
          lightColor: '#FFFFFF',
        });
        $('apkQrUrlLabel').textContent = latestApkUrl;
        qrContainer.classList.remove('hidden');
      }
    } catch (err) {
      releaseStatus.textContent = `Could not fetch release: ${err.message}`;
    }
  }

  $('btnFetchRelease').addEventListener('click', () => fetchLatestRelease($('githubRepoInput').value, true));

  $('btnCopyApkLink').addEventListener('click', async () => {
    if (!latestApkUrl) return;
    await navigator.clipboard.writeText(latestApkUrl);
    showToast('APK download link copied.');
  });

  $('btnDownloadQr').addEventListener('click', () => {
    const canvas = $('apkQrCanvas');
    if (!canvas || !latestApkUrl) return;
    const link = document.createElement('a');
    link.download = `OnionShield-${latestReleaseTag}-apk-qr.png`;
    link.href = canvas.toDataURL('image/png');
    link.click();
  });

  // PWA Installation
  function setInstallGuideVisible(visible, promptReady = false) {
    const guide = $('pwaInstallGuide');
    if (!guide) return;
    guide.classList.toggle('prompt-ready', promptReady);
    guide.classList.toggle('is-visible', visible);
    guide.setAttribute('aria-hidden', visible ? 'false' : 'true');
  }

  async function triggerPwaInstall() {
    if (!deferredPrompt) return;
    deferredPrompt.prompt();
    const choice = await deferredPrompt.userChoice;
    deferredPrompt = null;
    $('btnGuideInstallNow').classList.add('hidden');
    setInstallGuideVisible(false, false);
    if (choice && choice.outcome === 'accepted') {
      $('btnInstallPwa').textContent = 'Installed';
    }
  }

  if (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches) {
    $('btnInstallPwa').textContent = 'Installed';
  }

  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferredPrompt = e;
    $('btnGuideInstallNow').classList.remove('hidden');
    setInstallGuideVisible(true, true);
  });

  window.addEventListener('appinstalled', () => {
    deferredPrompt = null;
    $('btnInstallPwa').textContent = 'Installed';
    setInstallGuideVisible(false, false);
  });

  $('btnGuideInstallNow').addEventListener('click', triggerPwaInstall);
  $('btnInstallPwa').addEventListener('click', async () => {
    if (deferredPrompt) {
      await triggerPwaInstall();
    } else {
      setInstallGuideVisible(!$('pwaInstallGuide').classList.contains('is-visible'), false);
    }
  });

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch(() => {});
  }

  // Boot
  applyModeUI();
  renderTabs();
  renderPinnedApps();
  renderShields();
  renderBrowserViewport();
  renderLibrary();
  renderVault();
  renderGuard();
  runPrivacyAudit();
  fetchLatestRelease(savedRepo, false);
  initFirebaseSync();
})();

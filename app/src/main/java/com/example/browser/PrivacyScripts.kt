package com.example.browser

import com.example.data.BrowserMode

object PrivacyScripts {

    data class SpoofedUserAgentProfile(
        val label: String,
        val userAgent: String,
        val platform: String,
    )

    private val surfaceMobileProfiles = listOf(
        SpoofedUserAgentProfile(
            label = "Chrome Android Mobile",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            platform = "Linux armv8l",
        ),
        SpoofedUserAgentProfile(
            label = "Chrome Mobile (Standard)",
            userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            platform = "Linux armv8l",
        ),
    )

    private val userAgentProfiles = listOf(
        SpoofedUserAgentProfile(
            label = "Linux Desktop Firefox",
            userAgent = "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0",
            platform = "Linux x86_64",
        ),
        SpoofedUserAgentProfile(
            label = "Android Hardened Mobile",
            userAgent = "Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0",
            platform = "Linux armv8l",
        ),
        SpoofedUserAgentProfile(
            label = "Windows Standard",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0",
            platform = "Win32",
        ),
        SpoofedUserAgentProfile(
            label = "macOS Safari-Neutral",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
            platform = "MacIntel",
        ),
    )

    fun profileForSeed(seed: String): SpoofedUserAgentProfile {
        return profileForSeed(seed, BrowserMode.SURFACE)
    }

    fun profileForSeed(seed: String, mode: BrowserMode): SpoofedUserAgentProfile {
        val pool = if (mode == BrowserMode.SURFACE) surfaceMobileProfiles else userAgentProfiles
        val index = (seed.hashCode() and 0x7FFFFFFF) % pool.size
        return pool[index]
    }

    fun buildHardeningScript(
        seed: String,
        enableWebRtcGuard: Boolean,
        enableFingerprintRandomization: Boolean,
        mode: BrowserMode = BrowserMode.SURFACE,
        enableCosmeticAdBlock: Boolean = false,
        enablePopupGuard: Boolean = false,
        enableBackgroundMediaPlayback: Boolean = true,
    ): String {
        if (!enableWebRtcGuard &&
            !enableFingerprintRandomization &&
            !enableCosmeticAdBlock &&
            !enablePopupGuard &&
            !enableBackgroundMediaPlayback
        ) {
            return ""
        }
        val profile = profileForSeed(seed, mode)
        val numericNoise = ((seed.hashCode() and 0xFF) % 5) + 1
        val cores = listOf(2, 4, 8)[(seed.hashCode() and 0x7FFFFFFF) % 3]
        val memoryGb = listOf(4, 8)[(seed.hashCode() and 0x7FFFFFFF) % 2]

        return buildString {
            append("(function() { 'use strict'; ")
            if (enableWebRtcGuard) {
                append(
                    """
                    try {
                      if (!window.__osWebRtcGuardInstalled) {
                        window.__osWebRtcGuardInstalled = true;
                        const blockedRtc = function() {
                          throw new DOMException('WebRTC disabled by OnionShield Privacy Guard', 'SecurityError');
                        };
                        Object.defineProperty(window, 'RTCPeerConnection', { value: blockedRtc, configurable: false });
                        Object.defineProperty(window, 'webkitRTCPeerConnection', { value: blockedRtc, configurable: false });
                        Object.defineProperty(window, 'mozRTCPeerConnection', { value: blockedRtc, configurable: false });
                        Object.defineProperty(window, 'RTCDataChannel', { value: undefined, configurable: false });
                        if (navigator.mediaDevices) {
                          navigator.mediaDevices.getUserMedia = function() {
                            return Promise.reject(new DOMException('Media capture blocked by OnionShield', 'NotAllowedError'));
                          };
                          navigator.mediaDevices.enumerateDevices = function() {
                            return Promise.resolve([]);
                          };
                        }
                        navigator.getUserMedia = undefined;
                      }
                    } catch (e) {}
                    """.trimIndent()
                )
            }
            if (enableFingerprintRandomization) {
                append(
                    """
                    try {
                      if (!window.__osFingerprintGuardInstalled) {
                        window.__osFingerprintGuardInstalled = true;
                        Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => $cores });
                        Object.defineProperty(navigator, 'deviceMemory', { get: () => $memoryGb });
                        Object.defineProperty(navigator, 'platform', { get: () => '${profile.platform}' });
                        Object.defineProperty(navigator, 'doNotTrack', { get: () => '1' });

                        const origToDataURL = HTMLCanvasElement.prototype.toDataURL;
                        HTMLCanvasElement.prototype.toDataURL = function() {
                          try {
                            const ctx = this.getContext('2d');
                            if (ctx && this.width > 0 && this.height > 0) {
                              const img = ctx.getImageData(0, 0, 1, 1);
                              img.data[0] = (img.data[0] + $numericNoise) % 256;
                              ctx.putImageData(img, 0, 0);
                            }
                          } catch (_) {}
                          return origToDataURL.apply(this, arguments);
                        };

                        const origGetParam = WebGLRenderingContext.prototype.getParameter;
                        WebGLRenderingContext.prototype.getParameter = function(param) {
                          if (param === 37445) return 'Generic Privacy Renderer';
                          if (param === 37446) return 'OnionShield Virtual GPU ($seed)';
                          return origGetParam.apply(this, arguments);
                        };
                      }
                    } catch (e) {}
                    """.trimIndent()
                )
            }
            if (enableCosmeticAdBlock) {
                append(
                    """
                    try {
                      if (!document.getElementById('os-cosmetic-adblock')) {
                        const style = document.createElement('style');
                        style.id = 'os-cosmetic-adblock';
                        style.textContent = `
                          ins.adsbygoogle,
                          .adsbygoogle,
                          [id^="google_ads_iframe"],
                          [id^="div-gpt-ad"],
                          [data-ad-slot],
                          [data-ad-client],
                          .ad-banner,
                          .ad-container,
                          .ad-wrapper,
                          .sponsored-ad,
                          #taboola-below-article-thumbnails,
                          .taboola-container,
                          .OUTBRAIN,
                          [data-widget-host="outbrain"],
                          ytm-promoted-sparkles-web-renderer,
                          ytm-promoted-video-renderer,
                          ytd-ad-slot-renderer,
                          ad-slot-renderer,
                          ytd-promoted-sparkles-web-renderer,
                          ytd-display-ad-renderer,
                          ytd-companion-slot-renderer,
                          ytm-companion-slot-renderer,
                          ytd-in-feed-ad-layout-renderer,
                          ytd-banner-promo-renderer,
                          ytm-statement-banner-renderer,
                          ytm-mealbar-promo-renderer,
                          #player-ads,
                          #masthead-ad,
                          .ytp-ad-module,
                          .ytp-ad-overlay-container,
                          .ytp-ad-image-overlay,
                          .ytp-ad-text-overlay,
                          .ytp-ad-player-overlay,
                          .ytp-ad-player-overlay-layout,
                          .ytp-ad-action-interstitial,
                          [target-id="engagement-panel-ads"],
                          iframe[src*="doubleclick.net"],
                          iframe[src*="googlesyndication.com"],
                          iframe[src*="adnxs.com"],
                          iframe[src*="propellerads"],
                          iframe[src*="exoclick"] {
                            display: none !important;
                            visibility: hidden !important;
                            height: 0 !important;
                            min-height: 0 !important;
                          }
                        `;
                        (document.head || document.documentElement).appendChild(style);
                      }

                      const isYtHost = /(^|\.)(youtube\.com|youtube-nocookie\.com)$/i.test(location.hostname || '');
                      if (isYtHost && !window.__osYtAdBlockerInstalled) {
                        window.__osYtAdBlockerInstalled = true;

                        const stripYtAdsObj = function(obj) {
                          if (!obj || typeof obj !== 'object') return obj;
                          try {
                            if ('adPlacements' in obj) obj.adPlacements = [];
                            if ('playerAds' in obj) obj.playerAds = [];
                            if ('adSlots' in obj) obj.adSlots = [];
                            if ('adBreakHeartbeatParams' in obj) delete obj.adBreakHeartbeatParams;
                          } catch (_) {}
                          return obj;
                        };
                        stripYtAdsObj(window.ytInitialPlayerResponse);

                        if (typeof window.fetch === 'function') {
                          const origFetch = window.fetch;
                          window.fetch = function(input, init) {
                            const reqUrl = typeof input === 'string' ? input : (input && input.url ? input.url : '');
                            if (/\/(pagead|api\/stats\/ads|ptracking|get_midroll_info|pcs\/activeview|player\/ad_break)/i.test(reqUrl)) {
                              return Promise.resolve(new Response('', { status: 204, statusText: 'Blocked by OnionShield' }));
                            }
                            return origFetch.apply(this, arguments).then(function(resp) {
                              if (!/\/youtubei\/v1\/(player|next)/i.test(reqUrl)) return resp;
                              try {
                                const cloned = resp.clone();
                                return cloned.text().then(function(txt) {
                                  if (!txt || (!txt.includes('adPlacements') && !txt.includes('playerAds') && !txt.includes('adSlots'))) {
                                    return resp;
                                  }
                                  const data = stripYtAdsObj(JSON.parse(txt));
                                  return new Response(JSON.stringify(data), {
                                    status: resp.status,
                                    statusText: resp.statusText,
                                    headers: resp.headers
                                  });
                                }).catch(function() { return resp; });
                              } catch (_) {
                                return resp;
                              }
                            });
                          };
                        }

                        const skipYouTubeAdTick = function() {
                          try {
                            if (/^\/shorts(\/|$)/i.test(location.pathname || '')) {
                              return;
                            }
                            const skipBtns = document.querySelectorAll(
                              '.ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-ad-skip-button-container button, button[id^="skip-button"]'
                            );
                            for (let i = 0; i < skipBtns.length; i++) {
                              if (skipBtns[i] && typeof skipBtns[i].click === 'function') {
                                window.__osInAdSkipUntilMs = Date.now() + 4500;
                                window.__osAutoplayTransitionUntilMs = Date.now() + 10000;
                                skipBtns[i].click();
                              }
                            }
                            const player = document.querySelector('#movie_player, .html5-video-player');
                            const isAdShowing = player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'));
                            const video = document.querySelector('video.html5-main-video, #movie_player video, video');
                            if (video) {
                              if (isAdShowing) {
                                if (!video.__osSavedMuteStateRecorded) {
                                  video.__osSavedMuteStateRecorded = true;
                                  video.__osSavedMuted = video.muted;
                                }
                                window.__osInAdSkipUntilMs = Date.now() + 4500;
                                window.__osAutoplayTransitionUntilMs = Date.now() + 10000;
                                video.muted = true;
                                video.playbackRate = 16;
                                if (isFinite(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.15) {
                                  video.currentTime = video.duration;
                                }
                              } else if (video.__osSavedMuteStateRecorded) {
                                video.muted = !!video.__osSavedMuted;
                                video.playbackRate = 1;
                                video.__osSavedMuteStateRecorded = false;
                                if (window.__osBgMediaEnabled && !window.__osNativePaused && !window.__osUserExplicitlyPaused && video.paused && !video.ended) {
                                  try {
                                    video.__osUserWantsPlay = true;
                                    const p = video.play();
                                    if (p && p.catch) p.catch(function() {});
                                  } catch (_) {}
                                }
                              }
                            }
                          } catch (_) {}
                        };
                        setInterval(skipYouTubeAdTick, 350);
                        skipYouTubeAdTick();
                      }
                    } catch (e) {}
                    """.trimIndent()
                )
            }
            if (enablePopupGuard) {
                append(
                    """
                    try {
                      if (!window.__osPopupGuardInstalled) {
                        window.__osPopupGuardInstalled = true;
                        const origOpen = window.open;
                        const adHostRe = /(doubleclick\.net|googlesyndication|googleadservices|popads\.net|popcash\.net|propellerads|adsterra|exoclick|juicyads|trafficjunky|clickadu|admaven|hilltopads)/i;
                        window.open = function(targetUrl) {
                          if (!targetUrl || String(targetUrl) === 'about:blank' || adHostRe.test(String(targetUrl))) {
                            return null;
                          }
                          return origOpen.apply(this, arguments);
                        };
                      }
                    } catch (e) {}
                    """.trimIndent()
                )
            }
            if (enableBackgroundMediaPlayback) {
                append(
                    """
                    try {
                      window.__osBgMediaEnabled = true;
                      if (!window.__osBgMediaGuardInstalled) {
                        window.__osBgMediaGuardInstalled = true;
                        window.__osLastPauseControlTapMs = 0;
                        window.__osUserExplicitlyPaused = false;
                        window.__osNativePaused = false;
                        window.__osAutoplayActive = false;
                        window.__osAutoplayTransitionUntilMs = 0;
                        window.__osInAdSkipUntilMs = 0;
                        window.__osTouchDragMoved = false;
                        window.__osTouchStartX = 0;
                        window.__osTouchStartY = 0;
                        window.__osAppInBackground = !!window.__osAppInBackground;
                        window.__osSetAppBackground = function(inBg) {
                          window.__osAppInBackground = !!inBg;
                        };

                        const forceVisibleProps = function(target) {
                          if (!target) return;
                          try {
                            Object.defineProperty(target, 'hidden', { get: function() { return false; }, configurable: true });
                          } catch (_) {}
                          try {
                            Object.defineProperty(target, 'webkitHidden', { get: function() { return false; }, configurable: true });
                          } catch (_) {}
                          try {
                            Object.defineProperty(target, 'mozHidden', { get: function() { return false; }, configurable: true });
                          } catch (_) {}
                          try {
                            Object.defineProperty(target, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                          } catch (_) {}
                          try {
                            Object.defineProperty(target, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });
                          } catch (_) {}
                        };
                        forceVisibleProps(Document.prototype);
                        forceVisibleProps(document);
                        try {
                          const origHasFocus = Document.prototype.hasFocus;
                          Document.prototype.hasFocus = function() {
                            if (window.__osBgMediaEnabled) return true;
                            return origHasFocus ? origHasFocus.apply(this, arguments) : true;
                          };
                        } catch (_) {}

                        const blockedVisibilityEvents = {
                          visibilitychange: true,
                          webkitvisibilitychange: true,
                          mozvisibilitychange: true,
                          msvisibilitychange: true,
                          pagehide: true,
                          freeze: true,
                          blur: true,
                          focusout: true
                        };
                        const origAddEventListener = EventTarget.prototype.addEventListener;
                        EventTarget.prototype.addEventListener = function(type, listener, options) {
                          if (window.__osBgMediaEnabled &&
                              typeof type === 'string' &&
                              blockedVisibilityEvents[type.toLowerCase()] &&
                              (this === document || this === window || (this && this.nodeType === 9))) {
                            return;
                          }
                          return origAddEventListener.apply(this, arguments);
                        };
                        try {
                          Object.defineProperty(document, 'onvisibilitychange', { get: function() { return null; }, set: function() {}, configurable: true });
                          Object.defineProperty(window, 'onpagehide', { get: function() { return null; }, set: function() {}, configurable: true });
                          Object.defineProperty(window, 'onblur', { get: function() { return null; }, set: function() {}, configurable: true });
                        } catch (_) {}

                        const isShortsRoute = function() {
                          try {
                            const h = (location.hostname || '').toLowerCase();
                            const p = (location.pathname || '').toLowerCase();
                            return /(^|\.)(youtube\.com|youtube-nocookie\.com)$/i.test(h) && /^\/shorts(\/|$)/.test(p);
                          } catch (_) {
                            return false;
                          }
                        };
                        const visibleMediaArea = function(el) {
                          if (!el || !el.getBoundingClientRect) return 0;
                          try {
                            const r = el.getBoundingClientRect();
                            if (r.width <= 0 || r.height <= 0) return 0;
                            const vw = window.innerWidth || (document.documentElement && document.documentElement.clientWidth) || 1080;
                            const vh = window.innerHeight || (document.documentElement && document.documentElement.clientHeight) || 1920;
                            const ix = Math.max(0, Math.min(r.right, vw) - Math.max(r.left, 0));
                            const iy = Math.max(0, Math.min(r.bottom, vh) - Math.max(r.top, 0));
                            return ix * iy;
                          } catch (_) {
                            return 0;
                          }
                        };
                        const isElementInViewport = function(el) {
                          if (!el || !el.getBoundingClientRect) return true;
                          return visibleMediaArea(el) > 256;
                        };
                        const findPrimaryMedia = function() {
                          const list = document.querySelectorAll('video, audio');
                          let bestPlayingVisible = null;
                          let bestPlayingArea = -1;
                          let firstPlaying = null;
                          let bestPausedVisible = null;
                          let bestPausedArea = -1;
                          let fallback = null;
                          for (let i = 0; i < list.length; i++) {
                            const m = list[i];
                            const area = visibleMediaArea(m);
                            const isPlaying = !m.paused && !m.ended;
                            if (isPlaying) {
                              if (!firstPlaying) firstPlaying = m;
                              if (area > bestPlayingArea) {
                                bestPlayingArea = area;
                                bestPlayingVisible = m;
                              }
                            } else if (m.currentSrc || m.src || m.readyState > 0) {
                              if (!fallback) fallback = m;
                              if (area > bestPausedArea) {
                                bestPausedArea = area;
                                bestPausedVisible = m;
                              }
                            }
                          }
                          if (bestPlayingVisible && bestPlayingArea > 0) return bestPlayingVisible;
                          if (firstPlaying) return firstPlaying;
                          if (bestPausedVisible && bestPausedArea > 0) return bestPausedVisible;
                          return fallback || list[0] || null;
                        };
                        const hasAnyActiveMedia = function() {
                          const list = document.querySelectorAll('video, audio');
                          for (let i = 0; i < list.length; i++) {
                            const m = list[i];
                            if (!m.paused && !m.ended) return true;
                          }
                          return false;
                        };
                        const isExplicitPlayPauseControlTarget = function(target) {
                          if (!target || !target.closest) return false;
                          if (target.closest('a[href], ytm-rich-item-renderer, ytm-compact-video-renderer, ytd-compact-video-renderer, .ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern')) {
                            return false;
                          }
                          if (target.closest('.ytp-play-button, .icon-player-pause, .player-control-play-pause-icon, .ytm-custom-control, button[aria-label*="Pause" i], button[aria-label*="Play" i], [data-title-no-tooltip*="Pause" i]')) {
                            return true;
                          }
                          const directMedia = target.tagName === 'VIDEO' || target.tagName === 'AUDIO' || !!target.closest('.html5-video-container, #player-control-overlay, #shorts-player, ytm-shorts-player-controls, ytd-reel-player-overlay-renderer, ytm-reel-player-overlay-renderer, ytd-reel-video-renderer, ytm-reel-video-renderer, .reel-video-in-sequence');
                          if (!directMedia) return false;
                          const m = findPrimaryMedia();
                          const minTapMs = isShortsRoute() ? 150 : 650;
                          return !!(m && !m.paused && !m.ended && (Date.now() - (m.__osStartedPlayingAtMs || 0) > minTapMs));
                        };

                        origAddEventListener.call(document, 'touchstart', function(ev) {
                          window.__osTouchDragMoved = false;
                          const t = ev && ev.touches && ev.touches[0];
                          if (t) {
                            window.__osTouchStartX = t.clientX;
                            window.__osTouchStartY = t.clientY;
                          }
                        }, true);
                        origAddEventListener.call(document, 'touchmove', function(ev) {
                          const t = ev && ev.touches && ev.touches[0];
                          if (t) {
                            const dx = Math.abs(t.clientX - (window.__osTouchStartX || 0));
                            const dy = Math.abs(t.clientY - (window.__osTouchStartY || 0));
                            if (dx > 12 || dy > 12) window.__osTouchDragMoved = true;
                          }
                        }, true);

                        const markControlInteraction = function(ev) {
                          if (!ev || ev.isTrusted === false) return;
                          if (ev.type === 'keydown') {
                            const tag = ev.target && ev.target.tagName ? ev.target.tagName : '';
                            if (tag === 'INPUT' || tag === 'TEXTAREA' || (ev.target && ev.target.isContentEditable)) return;
                            if (ev.key === 'k' || ev.key === 'K' || ev.key === ' ' || ev.code === 'MediaPlayPause' || ev.code === 'MediaPause') {
                              window.__osLastPauseControlTapMs = Date.now();
                            }
                            return;
                          }
                          if (window.__osTouchDragMoved) return;
                          if (isExplicitPlayPauseControlTarget(ev.target)) {
                            window.__osLastPauseControlTapMs = Date.now();
                          }
                        };
                        ['pointerdown', 'click', 'keydown'].forEach(function(evtName) {
                          origAddEventListener.call(document, evtName, markControlInteraction, true);
                        });

                        const dismissContinueWatchingDialogs = function() {
                          try {
                            const dialogs = document.querySelectorAll(
                              'ytm-confirm-dialog-renderer, yt-confirm-dialog-renderer, tp-yt-paper-dialog'
                            );
                            for (let i = 0; i < dialogs.length; i++) {
                              const dlg = dialogs[i];
                              if (!dlg) continue;
                              const style = window.getComputedStyle ? window.getComputedStyle(dlg) : null;
                              if (style && (style.display === 'none' || style.visibility === 'hidden')) continue;
                              const txt = (dlg.textContent || '').toLowerCase();
                              if (txt.includes('continue watching') || txt.includes('video paused') || txt.includes('still watching')) {
                                const confirmBtn = dlg.querySelector(
                                  '#confirm-button button, button[aria-label*="Yes" i], button[aria-label*="Continue" i], yt-button-renderer#confirm-button, .yt-spec-button-shape-next--filled, button'
                                );
                                if (confirmBtn && typeof confirmBtn.click === 'function') {
                                  confirmBtn.click();
                                }
                                window.__osUserExplicitlyPaused = false;
                                window.__osAutoplayTransitionUntilMs = Date.now() + 6000;
                              }
                            }
                          } catch (_) {}
                        };

                        const extractMediaArtist = function() {
                          try {
                            const el = document.querySelector(
                              'ytm-slim-owner-renderer .slim-owner-icon-and-title, ytd-channel-name a, #owner #channel-name a, [itemprop="author"]'
                            );
                            const txt = el && el.textContent ? el.textContent.trim() : '';
                            if (txt) return txt.slice(0, 80);
                          } catch (_) {}
                          return location.hostname || 'OnionShield Media';
                        };
                        const reportMediaState = function(isPlayingOverride) {
                          try {
                            const m = findPrimaryMedia();
                            const isPlaying = typeof isPlayingOverride === 'boolean'
                              ? isPlayingOverride
                              : (m ? (!m.paused && !m.ended) : hasAnyActiveMedia());
                            const title = (document.title || location.hostname || 'Media').trim();
                            const artist = extractMediaArtist();
                            const durationMs = (m && isFinite(m.duration) && m.duration > 0) ? Math.round(m.duration * 1000) : 0;
                            const positionMs = (m && isFinite(m.currentTime) && m.currentTime >= 0) ? Math.round(m.currentTime * 1000) : 0;
                            if (window.OnionShieldMediaBridge && window.OnionShieldMediaBridge.onMediaMetadataUpdated) {
                              window.OnionShieldMediaBridge.onMediaMetadataUpdated(title, artist, durationMs, positionMs, !!isPlaying);
                            } else if (isPlaying && window.OnionShieldMediaBridge && window.OnionShieldMediaBridge.onMediaPlaying) {
                              window.OnionShieldMediaBridge.onMediaPlaying(title);
                            } else if (!isPlaying && window.OnionShieldMediaBridge && window.OnionShieldMediaBridge.onMediaStopped) {
                              window.OnionShieldMediaBridge.onMediaStopped();
                            }
                          } catch (_) {}
                        };
                        const notifyPlaying = function() {
                          reportMediaState(true);
                        };
                        const notifyPausedOrStopped = function() {
                          reportMediaState(hasAnyActiveMedia());
                        };

                        const tryAutoResumeElement = function(el) {
                          if (!window.__osBgMediaEnabled || !el || window.__osNativePaused || window.__osUserExplicitlyPaused) return;
                          if (!el.paused || el.ended) return;
                          if (isShortsRoute()) {
                            if (!window.__osAppInBackground) return;
                            const primary = findPrimaryMedia();
                            if (el !== primary || !isElementInViewport(el)) return;
                          } else if (el.tagName === 'VIDEO' && !isElementInViewport(el) && !el.__osUserWantsPlay) {
                            return;
                          }
                          const inAutoplayTransition = window.__osAutoplayActive && (Date.now() < (window.__osAutoplayTransitionUntilMs || 0));
                          if (!el.__osUserWantsPlay && !inAutoplayTransition) return;
                          try {
                            el.__osUserWantsPlay = true;
                            const p = el.play();
                            if (p && p.catch) p.catch(function() {});
                          } catch (_) {}
                        };

                        const onPlayOrPlaying = function(ev) {
                          const el = ev && ev.target;
                          if (el && (el.tagName === 'VIDEO' || el.tagName === 'AUDIO')) {
                            if (isShortsRoute()) {
                              const allMedia = document.querySelectorAll('video');
                              for (let i = 0; i < allMedia.length; i++) {
                                if (allMedia[i] !== el) {
                                  allMedia[i].__osUserWantsPlay = false;
                                }
                              }
                            }
                            el.__osUserWantsPlay = true;
                            el.__osStartedPlayingAtMs = Date.now();
                            window.__osUserExplicitlyPaused = false;
                            window.__osNativePaused = false;
                            window.__osAutoplayActive = true;
                            window.__osAutoplayTransitionUntilMs = Date.now() + 10000;
                            notifyPlaying();
                          }
                        };
                        origAddEventListener.call(document, 'play', onPlayOrPlaying, true);
                        origAddEventListener.call(document, 'playing', onPlayOrPlaying, true);

                        ['loadedmetadata', 'loadeddata', 'canplay', 'durationchange'].forEach(function(evtName) {
                          origAddEventListener.call(document, evtName, function(ev) {
                            const el = ev && ev.target;
                            if (!el || (el.tagName !== 'VIDEO' && el.tagName !== 'AUDIO')) return;
                            if (!el.paused && !el.ended) {
                              notifyPlaying();
                            } else if (!isShortsRoute() || window.__osAppInBackground) {
                              tryAutoResumeElement(el);
                            }
                          }, true);
                        });

                        origAddEventListener.call(document, 'seeked', function(ev) {
                          const el = ev && ev.target;
                          if (el && (el.tagName === 'VIDEO' || el.tagName === 'AUDIO')) {
                            reportMediaState(!el.paused && !el.ended);
                          }
                        }, true);

                        origAddEventListener.call(document, 'ended', function(ev) {
                          const el = ev && ev.target;
                          if (el && (el.tagName === 'VIDEO' || el.tagName === 'AUDIO')) {
                            const wasAutoplayActive = !!window.__osAutoplayActive && !window.__osUserExplicitlyPaused && !window.__osNativePaused;
                            if (wasAutoplayActive) {
                              window.__osAutoplayTransitionUntilMs = Date.now() + 10000;
                            }
                            setTimeout(function() {
                              if (hasAnyActiveMedia()) {
                                notifyPlaying();
                                return;
                              }
                              if (Date.now() < (window.__osInAdSkipUntilMs || 0)) {
                                tryAutoResumeElement(el);
                                return;
                              }
                              if (el.ended) {
                                el.__osUserWantsPlay = false;
                                notifyPausedOrStopped();
                              }
                            }, 1800);
                          }
                        }, true);

                        origAddEventListener.call(document, 'pause', function(ev) {
                          const el = ev && ev.target;
                          if (!el || (el.tagName !== 'VIDEO' && el.tagName !== 'AUDIO')) return;
                          const msSinceControlTap = Date.now() - (window.__osLastPauseControlTapMs || 0);
                          const inAdSkip = Date.now() < (window.__osInAdSkipUntilMs || 0);
                          const userClickedPauseControl = (msSinceControlTap <= 850) && !inAdSkip;

                          if (!window.__osBgMediaEnabled || window.__osNativePaused || userClickedPauseControl) {
                            el.__osUserWantsPlay = false;
                            window.__osUserExplicitlyPaused = !el.ended;
                            window.__osAutoplayActive = false;
                            notifyPausedOrStopped();
                            return;
                          }
                          if (el.ended && !inAdSkip) {
                            return;
                          }
                          if (isShortsRoute() && !window.__osAppInBackground) {
                            el.__osUserWantsPlay = false;
                            if (!hasAnyActiveMedia()) {
                              notifyPausedOrStopped();
                            }
                            return;
                          }
                          if (el.__osUserWantsPlay || window.__osAutoplayActive || inAdSkip) {
                            el.__osUserWantsPlay = true;
                            window.__osAutoplayTransitionUntilMs = Math.max(window.__osAutoplayTransitionUntilMs || 0, Date.now() + 5000);
                            dismissContinueWatchingDialogs();
                            [60, 200, 500, 1100, 2200].forEach(function(delayMs) {
                              setTimeout(function() {
                                dismissContinueWatchingDialogs();
                                tryAutoResumeElement(el);
                              }, delayMs);
                            });
                          }
                        }, true);

                        const syncPlayingMediaNow = function() {
                          dismissContinueWatchingDialogs();
                          const shortsForeground = isShortsRoute() && !window.__osAppInBackground;
                          const list = document.querySelectorAll('video, audio');
                          let anyPlaying = false;
                          for (let i = 0; i < list.length; i++) {
                            const m = list[i];
                            if (!m.paused && !m.ended) {
                              m.__osUserWantsPlay = true;
                              window.__osUserExplicitlyPaused = false;
                              window.__osAutoplayActive = true;
                              anyPlaying = true;
                            } else if (!shortsForeground &&
                                window.__osBgMediaEnabled &&
                                !window.__osUserExplicitlyPaused &&
                                !window.__osNativePaused &&
                                (Date.now() < (window.__osAutoplayTransitionUntilMs || 0) || Date.now() < (window.__osInAdSkipUntilMs || 0))) {
                              tryAutoResumeElement(m);
                            }
                          }
                          if (anyPlaying) {
                            notifyPlaying();
                          }
                        };
                        syncPlayingMediaNow();
                        setInterval(syncPlayingMediaNow, 1500);

                        window.__osBgMediaResumeIfNeeded = function() {
                          if (!window.__osBgMediaEnabled || window.__osUserExplicitlyPaused || window.__osNativePaused) return;
                          dismissContinueWatchingDialogs();
                          if (isShortsRoute()) {
                            const primary = findPrimaryMedia();
                            if (primary) tryAutoResumeElement(primary);
                            return;
                          }
                          const list = document.querySelectorAll('video, audio');
                          for (let i = 0; i < list.length; i++) {
                            tryAutoResumeElement(list[i]);
                          }
                        };

                        window.__osBgMediaForcePlay = function() {
                          window.__osNativePaused = false;
                          window.__osUserExplicitlyPaused = false;
                          window.__osAutoplayActive = true;
                          window.__osAutoplayTransitionUntilMs = Date.now() + 10000;
                          const m = findPrimaryMedia();
                          if (m && !m.ended) {
                            m.__osUserWantsPlay = true;
                            try {
                              const p = m.play();
                              if (p && p.catch) p.catch(function() {});
                            } catch (_) {}
                            reportMediaState(true);
                          }
                        };

                        window.__osBgMediaSeekTo = function(posMs) {
                          const m = findPrimaryMedia();
                          if (m && isFinite(posMs) && posMs >= 0) {
                            try {
                              m.currentTime = posMs / 1000.0;
                              reportMediaState(!m.paused && !m.ended);
                            } catch (_) {}
                          }
                        };

                        window.__osBgMediaNext = function() {
                          try {
                            window.__osUserExplicitlyPaused = false;
                            window.__osNativePaused = false;
                            window.__osAutoplayActive = true;
                            window.__osAutoplayTransitionUntilMs = Date.now() + 10000;
                            const btn = document.querySelector('.ytp-next-button, button[aria-label*="Next"], a.ytp-next-button');
                            if (btn && typeof btn.click === 'function') {
                              btn.click();
                              return;
                            }
                            const m = findPrimaryMedia();
                            if (m && isFinite(m.duration)) {
                              m.currentTime = Math.min(m.duration, m.currentTime + 15);
                              reportMediaState(!m.paused && !m.ended);
                            }
                          } catch (_) {}
                        };

                        window.__osBgMediaPrev = function() {
                          try {
                            window.__osUserExplicitlyPaused = false;
                            window.__osNativePaused = false;
                            window.__osAutoplayActive = true;
                            window.__osAutoplayTransitionUntilMs = Date.now() + 10000;
                            const m = findPrimaryMedia();
                            if (m && m.currentTime > 5) {
                              m.currentTime = 0;
                              reportMediaState(!m.paused && !m.ended);
                              return;
                            }
                            const btn = document.querySelector('.ytp-prev-button, button[aria-label*="Previous"], a.ytp-prev-button');
                            if (btn && typeof btn.click === 'function') {
                              btn.click();
                              return;
                            }
                            if (m) {
                              m.currentTime = 0;
                              reportMediaState(!m.paused && !m.ended);
                            }
                          } catch (_) {}
                        };

                        window.__osBgMediaPauseAll = function() {
                          window.__osNativePaused = true;
                          window.__osUserExplicitlyPaused = true;
                          window.__osAutoplayActive = false;
                          window.__osAutoplayTransitionUntilMs = 0;
                          const list = document.querySelectorAll('video, audio');
                          for (let i = 0; i < list.length; i++) {
                            const el = list[i];
                            el.__osUserWantsPlay = false;
                            try { el.pause(); } catch (_) {}
                          }
                          reportMediaState(false);
                          setTimeout(function() { window.__osNativePaused = false; }, 250);
                        };
                      }
                    } catch (e) {}
                    """.trimIndent()
                )
            } else {
                append(" try { window.__osBgMediaEnabled = false; } catch (e) {} ")
            }
            append(" })();")
        }
    }

    fun buildBlobDownloadHelperScript(): String {
        return """
            (function() {
              'use strict';
              try {
                if (window.__osBlobDownloadHelperInstalled) return;
                window.__osBlobDownloadHelperInstalled = true;
                window.__osBlobRegistry = window.__osBlobRegistry || {};
                window.__osBlobOrder = window.__osBlobOrder || [];
                window.__osDownloadHints = window.__osDownloadHints || {};
                window.__osLastDownloadHint = null;

                const rememberBlob = function(url, obj) {
                  if (!url || typeof url !== 'string' || !url.startsWith('blob:')) return;
                  if (!obj || typeof obj.size !== 'number') return;
                  try {
                    window.__osBlobRegistry[url] = {
                      blob: obj,
                      type: obj.type || '',
                      size: obj.size || 0,
                      ts: Date.now()
                    };
                    window.__osBlobOrder.push(url);
                    while (window.__osBlobOrder.length > 20) {
                      const oldUrl = window.__osBlobOrder.shift();
                      if (oldUrl && oldUrl !== url) {
                        delete window.__osBlobRegistry[oldUrl];
                      }
                    }
                  } catch (_) {}
                };

                if (window.URL && typeof window.URL.createObjectURL === 'function') {
                  const origCreateObjectURL = window.URL.createObjectURL.bind(window.URL);
                  window.URL.createObjectURL = function(obj) {
                    const outUrl = origCreateObjectURL(obj);
                    rememberBlob(outUrl, obj);
                    return outUrl;
                  };
                }

                if (window.URL && typeof window.URL.revokeObjectURL === 'function') {
                  const origRevokeObjectURL = window.URL.revokeObjectURL.bind(window.URL);
                  window.URL.revokeObjectURL = function(url) {
                    if (url && window.__osBlobRegistry && window.__osBlobRegistry[url]) {
                      setTimeout(function() {
                        try {
                          if (window.__osBlobRegistry) delete window.__osBlobRegistry[url];
                        } catch (_) {}
                        try { origRevokeObjectURL(url); } catch (_) {}
                      }, 90000);
                      return;
                    }
                    return origRevokeObjectURL(url);
                  };
                }

                const recordAnchorHint = function(anchor) {
                  if (!anchor || anchor.tagName !== 'A') return;
                  try {
                    const href = anchor.href || anchor.getAttribute('href') || '';
                    const dlName = (anchor.getAttribute('download') || anchor.download || '').trim();
                    if (!href && !dlName) return;
                    const reg = href && window.__osBlobRegistry ? window.__osBlobRegistry[href] : null;
                    const hint = {
                      href: href,
                      fileName: dlName,
                      mimeType: reg ? (reg.type || '') : '',
                      size: reg ? (reg.size || 0) : 0,
                      ts: Date.now()
                    };
                    if (href) window.__osDownloadHints[href] = hint;
                    window.__osLastDownloadHint = hint;
                  } catch (_) {}
                };

                document.addEventListener('click', function(ev) {
                  try {
                    const target = ev && ev.target;
                    const anchor = target && target.closest ? target.closest('a[download], a[href^="blob:"], a[href^="data:"]') : null;
                    if (anchor) recordAnchorHint(anchor);
                  } catch (_) {}
                }, true);

                if (window.HTMLAnchorElement && HTMLAnchorElement.prototype && typeof HTMLAnchorElement.prototype.click === 'function') {
                  const origAnchorClick = HTMLAnchorElement.prototype.click;
                  HTMLAnchorElement.prototype.click = function() {
                    recordAnchorHint(this);
                    return origAnchorClick.apply(this, arguments);
                  };
                }

                window.__osPeekBlobHint = function(targetUrl) {
                  try {
                    const direct = targetUrl && window.__osDownloadHints ? window.__osDownloadHints[targetUrl] : null;
                    const reg = targetUrl && window.__osBlobRegistry ? window.__osBlobRegistry[targetUrl] : null;
                    const recent = window.__osLastDownloadHint && (Date.now() - (window.__osLastDownloadHint.ts || 0) < 10000)
                      ? window.__osLastDownloadHint
                      : null;
                    const fileName = (direct && direct.fileName) || (recent && recent.fileName) || '';
                    const mimeType = (reg && reg.type) || (direct && direct.mimeType) || (recent && recent.mimeType) || '';
                    const size = (reg && reg.size) || (direct && direct.size) || (recent && recent.size) || 0;
                    return JSON.stringify({ fileName: fileName, mimeType: mimeType, size: size });
                  } catch (_) {
                    return '{}';
                  }
                };

                window.__osFetchBlobForDownload = function(requestId, blobUrl, preferredName, preferredMime) {
                  try {
                    const sendResult = function(dataUrl, mime, errMsg) {
                      try {
                        if (window.OnionShieldDownloadBridge && window.OnionShieldDownloadBridge.onBlobDownloadCompleted) {
                          window.OnionShieldDownloadBridge.onBlobDownloadCompleted(
                            String(requestId || ''),
                            String(dataUrl || ''),
                            String(preferredName || ''),
                            String(mime || preferredMime || ''),
                            String(errMsg || '')
                          );
                        }
                      } catch (_) {}
                    };

                    const readBlob = function(blobObj) {
                      if (!blobObj) {
                        sendResult('', preferredMime || '', 'Blob data is no longer available');
                        return;
                      }
                      const resolvedMime = (blobObj.type && blobObj.type.trim()) ? blobObj.type : (preferredMime || 'application/octet-stream');
                      const reader = new FileReader();
                      reader.onloadend = function() {
                        sendResult(typeof reader.result === 'string' ? reader.result : '', resolvedMime, '');
                      };
                      reader.onerror = function() {
                        sendResult('', resolvedMime, 'Failed to read browser blob stream');
                      };
                      reader.readAsDataURL(blobObj);
                    };

                    const cached = blobUrl && window.__osBlobRegistry ? window.__osBlobRegistry[blobUrl] : null;
                    if (cached && cached.blob) {
                      readBlob(cached.blob);
                      return;
                    }
                    fetch(blobUrl).then(function(resp) {
                      if (!resp.ok) throw new Error('HTTP ' + resp.status);
                      return resp.blob();
                    }).then(readBlob).catch(function(err) {
                      sendResult('', preferredMime || '', (err && err.message) ? err.message : 'Unable to fetch blob download');
                    });
                  } catch (err) {
                    try {
                      if (window.OnionShieldDownloadBridge && window.OnionShieldDownloadBridge.onBlobDownloadCompleted) {
                        window.OnionShieldDownloadBridge.onBlobDownloadCompleted(
                          String(requestId || ''),
                          '',
                          String(preferredName || ''),
                          String(preferredMime || ''),
                          (err && err.message) ? err.message : 'Blob export error'
                        );
                      }
                    } catch (_) {}
                  }
                };
              } catch (_) {}
            })();
        """.trimIndent()
    }
}

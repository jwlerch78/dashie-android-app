/**
 * Kiosk Shell — main entry point.
 * Top-level page for the Dashie kiosk. Hosts the sidebar as native DOM
 * and HA in an iframe, matching the standard Dashie architecture.
 *
 * Kotlin communicates via evaluateJavascript calling global functions:
 * - dashieSetHaUrl(url)    — load HA in the content iframe
 * - dashieSetLockState(locked) — update sidebar lock state
 */

// NOTE: DashMenuController removed (2026-03-11) — native Kotlin sidebar replaces JS sidebar.
import { initOverlayBridge } from './kiosk-overlay-bridge.js';
import { getBrand } from './brand.js';
import { OnboardingController } from './onboarding/onboarding-controller.js';
import { renderSwipeTip, renderControlCenterTip } from './onboarding/onboarding-renderer.js';
import { PowerManagementEngine } from './power-management-engine.js';
import { applyThemeClass, syncHaIframeTheme } from './utils/theme-utils.js';
import * as haOfflineOverlay from './ha-offline-overlay.js';
import { getVideoFeedConfig, VIDEO_FEED_STORAGE_KEY } from '../../js/utils/video-feed-config.js';
import { initKioskSessionBridge, armKioskSyncTripwire } from './kiosk-settings-sync.js';

let onboarding = null;

/**
 * Forward music-player CustomEvents to the HA iframe via postMessage.
 * Kotlin dispatches these events on the shell page via evaluateJavascript;
 * the HA iframe has a parent bridge handler that re-dispatches them locally.
 */
function setupMusicEventForwarding() {
  const events = [
    'music-player-play', 'music-player-pause', 'music-player-play-pause',
    'music-player-next', 'music-player-previous', 'music-player-stop',
    'music-player-toggle-minimize', 'music-player-voice-duck', 'music-player-voice-unduck',
    'music-player-switch-entity', 'music-player-volume-set', 'music-player-play-media'
  ];
  const haIframe = document.getElementById('ha-content');
  for (const name of events) {
    window.addEventListener(name, (e) => {
      haIframe?.contentWindow?.postMessage({
        source: 'dashie-parent',
        type: 'dispatch-event',
        event: name,
        detail: e.detail || null
      }, '*');
    });
  }
  console.log('[KioskShell] Music event forwarding set up');
}

/**
 * Apply viewport-based scaling to the dash bar elements.
 * Uses the same 1368×768 reference resolution as standard Dashie's ViewportDetector
 * so the sidebar scales identically across both apps.
 *
 * Without this, the kiosk shell (which uses useWideViewPort=true) renders CSS px
 * at 1:1 with physical pixels, making the sidebar look too small on high-res screens
 * and too large on small screens.
 */
function applyShellViewportScale() {
  const REF_W = 1368;
  const REF_H = 768;
  const scaleX = window.innerWidth / REF_W;
  const scaleY = window.innerHeight / REF_H;
  let scale = Math.min(scaleX, scaleY);

  // Scale up for readability — 1.2 minimum ensures sidebar is usable on small screens
  if (scale < 1.2) scale = 1.2;
  if (scale > 2.5) scale = 2.5;

  console.log(`[KioskShell] Viewport scale: ${scale.toFixed(2)} (${window.innerWidth}×${window.innerHeight} vs ${REF_W}×${REF_H})`);

  const physicalShort = Math.min(window.innerWidth, window.innerHeight);

  // Apply zoom to sidebar and popout elements via CSS variable
  document.documentElement.style.setProperty('--shell-scale', scale);

  // Tiered sidebar boost based on physical short dimension (CSS px)
  // Kiosk uses useWideViewPort=true so innerWidth/Height = physical pixels
  // Device reference: Echo Show 394, Fire 601, Samsung 800, Mio 15"/32" 1080
  let sidebarBoost;
  if (physicalShort >= 1000) sidebarBoost = 1.5;       // Mio 15", Mio 32"
  else if (physicalShort >= 750) sidebarBoost = 1.3;   // Samsung SM-X200
  else sidebarBoost = 1.15;                            // Fire tablet, Echo Show, ONN
  document.documentElement.style.setProperty('--sidebar-boost', sidebarBoost);

  // Popout spacing factor: compress menu items on smaller screens
  const popoutSpacingFactor = sidebarBoost >= 1.3 ? 1 : 0.7;
  document.documentElement.style.setProperty('--popout-spacing-factor', popoutSpacingFactor);

  // User-configurable menu size multiplier (stored in localStorage)
  try {
    const menuSize = localStorage.getItem('dashie-menu-size');
    if (menuSize) {
      document.documentElement.style.setProperty('--menu-size', parseFloat(menuSize) || 1);
    }
  } catch (e) { /* localStorage not available */ }

  console.log(`[KioskShell] Sidebar boost: ${sidebarBoost}, popout spacing: ${popoutSpacingFactor} (short dim: ${physicalShort}px)`);
}

function init() {
  console.log('[KioskShell] Initializing...');

  // Hide sidebar by default before Kotlin calls setSidebarConfig.
  // Prevents brief flash of sidebar on initial load.
  document.body.classList.add('sidebar-hidden');

  // Apply viewport scaling before rendering sidebar
  applyShellViewportScale();

  // Apply dashboard zoom from localStorage or native SharedPreferences
  // localStorage is set by web Settings UI; SharedPreferences is set by HA plugin (textScaling API)
  try {
    let zoomPercent = null;
    const storedZoom = localStorage.getItem('dashie-dashboard-zoom');
    if (storedZoom) {
      zoomPercent = parseInt(storedZoom, 10);
    } else if (typeof DashieNative !== 'undefined' && DashieNative.getDashboardZoom) {
      // Fall back to native SharedPreferences (set by HA plugin textScaling API)
      const nativeZoom = DashieNative.getDashboardZoom();
      if (nativeZoom && nativeZoom !== 100) {
        zoomPercent = nativeZoom;
        // Sync to localStorage so both stores agree
        try { localStorage.setItem('dashie-dashboard-zoom', String(nativeZoom)); } catch (e) {}
      }
    }
    if (zoomPercent) {
      const scale = Math.max(10, Math.min(300, zoomPercent)) / 100;
      document.documentElement.style.setProperty('--dashboard-zoom', scale);
      console.log(`[KioskShell] Dashboard zoom: ${scale}`);
    }
  } catch (e) { /* localStorage unavailable */ }

  // Clear any legacy whole-page zoom styles (zoom is now CSS-variable-based)
  // NOTE: Do NOT call DashieNative.setDashboardZoom(100) here — that resets
  // SharedPreferences and wipes zoom settings from the HA plugin.
  document.documentElement.style.zoom = '';
  document.documentElement.style.transform = '';
  document.documentElement.style.transformOrigin = '';

  // Apply initial theme class based on system dark mode state
  try {
    if (typeof DashieNative !== 'undefined' && DashieNative.isSystemDarkMode) {
      applyThemeClass(DashieNative.isSystemDarkMode());
    }
  } catch (e) { /* bridge unavailable — stays on default dark */ }

  // NOTE: JS sidebar removed (2026-03-11) — native Kotlin sidebar handles all sidebar UI.
  // Legacy camera visibility refresh kept as no-op for Kotlin compatibility.
  window.dashieRefreshCameraVisibility = () => {};

  // Initialize overlay bridge (headless overlay communication)
  initOverlayBridge();

  // Kiosk Real Login, Phase 2 — host the REAL settings stack.
  //
  // Installs window.dashieKioskSetSession and picks up any session native latched into
  // window.__dashieKioskSession before this module ran. If the kiosk is anonymous (never
  // provisioned, or household sharing is off) this is INERT: it logs why and returns, and
  // the shell boots exactly as it always has, fully offline.
  //
  // Nothing below this line depends on it — a settings-sync failure must never cost the
  // user their Home Assistant display.
  initKioskSessionBridge();
  armKioskSyncTripwire();

  // Start power management engine immediately.
  // The engine handles evalInHaIframe not being ready yet (returns false, retries next poll).
  // A delayed start creates a window where the timer can be frozen during sleep/recreation.
  PowerManagementEngine.start();
  // Expose for settings page to call onManualToggle
  window.__dashiePowerEngine = PowerManagementEngine;

  // Forward music-player-* events from Kotlin (evaluateJavascript → window.dispatchEvent)
  // to the HA iframe via postMessage, where the music player subscription script listens.
  setupMusicEventForwarding();

  // Check if first-launch onboarding is needed
  const needsOnboarding = typeof DashieNative !== 'undefined'
    && DashieNative.isSetupComplete
    && !DashieNative.isSetupComplete();

  if (needsOnboarding) {
    console.log('[KioskShell] Setup not complete — launching onboarding');
    onboarding = new OnboardingController();
    onboarding.initialize();
  }

  // Signal to Kotlin that shell is ready
  // (Kotlin will load HA iframe only if isSetupComplete; onboarding triggers it later)
  if (typeof DashieNative !== 'undefined' && DashieNative.onShellReady) {
    DashieNative.onShellReady();
  }

  console.log('[KioskShell] Ready');
}

// --- Kotlin-callable global functions ---

// Track the HA iframe's current URL via postMessage (cross-origin safe).
// The parent bridge script injected into HA hooks pushState/replaceState/popstate
// and sends { type: 'ha-url-changed', url } messages here.
let _haCurrentUrl = '';
window.addEventListener('message', (e) => {
  if (!e.data) return;

  if (e.data.type === 'ha-url-changed' && e.data.url) {
    _haCurrentUrl = e.data.url;
    // Persist to Kotlin so crash/OOM recovery can restore the correct page
    try {
      if (typeof DashieNative !== 'undefined' && DashieNative.onHaUrlChanged) {
        DashieNative.onHaUrlChanged(e.data.url);
      }
    } catch (ex) { /* bridge not ready */ }
  }

  // Relay ingress_session cookies from HA iframe to CookieManager via Kotlin.
  // Newer WebView/Chromium partitions cookies set by JS in cross-origin iframes,
  // so the ingress_session cookie set by HA's frontend doesn't survive navigation.
  // CookieManager.setCookie() operates at the app level (not partitioned).
  if (e.data.type === 'ingress-cookie' && e.data.cookie && e.data.origin) {
    try {
      if (typeof DashieNative !== 'undefined' && DashieNative.setIngressCookie) {
        DashieNative.setIngressCookie(e.data.origin, e.data.cookie);
        console.log('[KioskShell] Relayed ingress cookie to CookieManager');
      }
    } catch (ex) { /* bridge not ready */ }
  }

  // Relay HA token updates from the iframe so native HalitePreferences stays
  // in sync with HA's rotated refresh token. Without this, native callers
  // (FrigatePlaybackController, sensor publisher, etc.) keep using a stale
  // refresh token and silently 401 against HA's auth endpoint. Mirrors the
  // dashieapp.com handler in core-initializer.js so HA tokens flow through
  // dashieDevice.syncHaTokens in both kiosk and full modes.
  if (e.data.source === 'dashie-ha' && e.data.type === 'ha-tokens' && e.data.tokens) {
    try {
      if (typeof window.dashieDevice !== 'undefined' && window.dashieDevice.syncHaTokens) {
        window.dashieDevice.syncHaTokens(e.data.tokens);
        console.log('[KioskShell] Synced HA tokens to native');
      }
    } catch (ex) { /* bridge not ready */ }
  }
});

/** Get the current HA iframe URL (includes path/hash for page restoration). */
window.dashieGetHaUrl = function() {
  return _haCurrentUrl || '';
};

/**
 * Inject scripts into HA iframe via same-origin contentDocument access.
 * Called after iframe.onload when _dashieInjectionScripts is set by Kotlin.
 * Injects: parent bridge, WS proxy, kiosk CSS (in that order).
 */
function injectScriptsViaContentDocument(iframe) {
  const scripts = window._dashieInjectionScripts;
  if (!scripts || !iframe.contentDocument) return false;

  try {
    const doc = iframe.contentDocument;
    const head = doc.head || doc.documentElement;
    if (!head) return false;

    // Block HA theme persistence to prevent cross-device theme sync.
    // syncHaIframeTheme() dispatches a 'settheme' CustomEvent which HA handles
    // by (1) applying theme CSS and (2) persisting to frontend/set_user_data via WS.
    // We want (1) but not (2). The WS message format is:
    //   { "type": "frontend/set_user_data", "key": "theme", "value": { "dark": true, ... } }
    // This intercepts the WS send and drops theme persistence messages so the theme
    // applies locally but doesn't propagate to other devices sharing the same HA user.
    const themeBlocker = doc.createElement('script');
    themeBlocker.textContent = `(function() {
      var origSend = WebSocket.prototype.send;
      WebSocket.prototype.send = function(data) {
        try {
          if (typeof data === 'string' && data.indexOf('set_user_data') !== -1) {
            var msg = JSON.parse(data);
            if (msg.type === 'frontend/set_user_data' && msg.key === 'theme') {
              console.log('[Dashie] Blocked HA theme persistence');
              return;
            }
          }
        } catch (e) {}
        return origSend.call(this, data);
      };
    })();`;
    head.appendChild(themeBlocker);

    // Inject parent bridge (for postMessage communication)
    if (scripts.parentBridge) {
      const el = doc.createElement('script');
      el.textContent = scripts.parentBridge;
      head.appendChild(el);
    }

    // Inject WS proxy (if enabled)
    if (scripts.wsProxy) {
      const el = doc.createElement('script');
      el.textContent = scripts.wsProxy;
      head.appendChild(el);
    }

    // Inject kiosk CSS injection script
    if (scripts.kioskCss) {
      const el = doc.createElement('script');
      el.textContent = scripts.kioskCss;
      head.appendChild(el);
    }

    console.log('[KioskShell] Same-origin injection complete');
    return true;
  } catch (e) {
    console.warn('[KioskShell] Same-origin injection failed:', e.message);
    return false;
  }
}

/** Load HA dashboard into the content iframe. Called by Kotlin after shell loads. */
let _haLoadTimeout = null;
// The URL Kotlin last asked us to load — the canonical target for a forced
// reload (dashieReloadHaIframe), independent of what the iframe self-reports.
let _haLastRequestedUrl = '';
// Guards the about:blank reload dance so a native-driven reload (coordinator,
// screen-off) and the JS offline-overlay reload can't collide.
let _haReloadInFlight = false;
let _haReloadFailsafe = null;   // clears a wedged in-place reload (see dashieReloadHaIframe)
window.dashieSetHaUrl = function(url) {
  const iframe = document.getElementById('ha-content');
  if (!iframe) return;

  _haLastRequestedUrl = url;
  console.log('[KioskShell] Loading HA:', url);

  // Clear any previous load timeout
  if (_haLoadTimeout) clearTimeout(_haLoadTimeout);

  // Same-origin mode: hide iframe during load to prevent FOUC
  // (HA sidebar/tabs briefly visible before kiosk CSS is injected)
  const hasSameOriginScripts = window._dashieInjectionScripts != null;
  if (hasSameOriginScripts) {
    iframe.style.visibility = 'hidden';
  }

  // Detect when HA iframe finishes loading and notify Kotlin
  iframe.onload = function() {
    _haReloadInFlight = false; // a load completed — release the reload guard
    if (_haReloadFailsafe) { clearTimeout(_haReloadFailsafe); _haReloadFailsafe = null; }
    // Chrome's "webpage not available" error page also fires onload.
    // Detect it and show offline overlay instead of treating as success.
    let isErrorPage = false;
    try {
      const doc = iframe.contentDocument;
      if (!doc) {
        // contentDocument is null for cross-origin frames. The shell is loaded at
        // HA's origin, so a successful HA load is always same-origin and accessible.
        // A null doc means the iframe has a Chrome error page (different origin).
        isErrorPage = true;
      } else {
        const bodyText = (doc.body?.innerText || '').toLowerCase();
        // Chrome error pages and HTTP error pages during HA restart
        if (bodyText.includes('webpage') || bodyText.includes('err_') ||
            bodyText.includes('not available') || bodyText.includes('can\u2019t reach') ||
            bodyText.includes('404') || bodyText.includes('not found') ||
            bodyText.includes('502') || bodyText.includes('bad gateway') ||
            bodyText.includes('503') || bodyText.includes('service unavailable')) {
          isErrorPage = true;
        }
        // HA pages always have <home-assistant> element — if missing, likely error
        if (!isErrorPage && !doc.querySelector('home-assistant') && !doc.querySelector('ha-authorize')) {
          const len = (doc.body?.innerHTML || '').length;
          // Short pages without HA elements are error pages (HA frontend is >100KB)
          if (len < 5000) {
            isErrorPage = true;
          }
        }
      }
    } catch (e) {
      // Cross-origin access error — likely a Chrome error page
      isErrorPage = true;
    }

    if (isErrorPage) {
      if (_haLoadTimeout) { clearTimeout(_haLoadTimeout); _haLoadTimeout = null; }
      console.log('[KioskShell] HA iframe loaded error page — showing offline overlay');
      iframe.style.visibility = 'visible';
      // Report up to the native DashboardHealthCoordinator, which owns recovery
      // while the screen is off (JS timers — incl. the overlay poll below — freeze
      // then). Feature-detected so it's a no-op on older APKs.
      if (typeof DashieNative !== 'undefined' && DashieNative.onHaIframeError) {
        DashieNative.onHaIframeError(url);
      }
      haOfflineOverlay.show(url, () => {
        // Awake recovery: the poll detected HA is back. Reload through the single
        // guarded path so it can't collide with a native-driven reload.
        console.log('[KioskShell] HA reconnected — reloading iframe');
        window.dashieReloadHaIframe();
      });
      return;
    }

    if (_haLoadTimeout) { clearTimeout(_haLoadTimeout); _haLoadTimeout = null; }
    haOfflineOverlay.hide();
    console.log('[KioskShell] HA iframe loaded');

    // Report healthy so the native coordinator clears its error state and stops
    // any recovery loop. Feature-detected for older APKs.
    if (typeof DashieNative !== 'undefined' && DashieNative.onHaIframeHealthy) {
      DashieNative.onHaIframeHealthy();
    }

    // Same-origin: inject kiosk CSS and scripts via contentDocument
    if (hasSameOriginScripts) {
      injectScriptsViaContentDocument(iframe);
      // Show iframe after a brief delay for CSS to take effect
      setTimeout(() => { iframe.style.visibility = 'visible'; }, 150);
    }

    if (typeof DashieNative !== 'undefined' && DashieNative.onHaIframeLoaded) {
      DashieNative.onHaIframeLoaded();
    }
    // Sync HA theme, fetch pipeline name, extract tokens, and sync feeds once web components initialize
    setTimeout(() => {
      const isDark = document.documentElement.classList.contains('theme-dark');
      syncHaIframeTheme(isDark);
      fetchPreferredPipelineName();
      extractHaTokens();
      syncVideoFeedsFromHa();
    }, 2000);
  };

  // Start a 10s timeout — if iframe hasn't loaded by then, show offline overlay
  _haLoadTimeout = setTimeout(() => {
    _haLoadTimeout = null;
    _haReloadInFlight = false; // load never completed — release the reload guard
    if (_haReloadFailsafe) { clearTimeout(_haReloadFailsafe); _haReloadFailsafe = null; }
    console.log('[KioskShell] HA iframe load timeout — showing offline overlay');
    iframe.style.visibility = 'visible'; // Ensure iframe is visible on timeout
    // A load timeout is an error condition — report up so native can recover
    // during screen-off. Feature-detected for older APKs.
    if (typeof DashieNative !== 'undefined' && DashieNative.onHaIframeError) {
      DashieNative.onHaIframeError(url);
    }
    haOfflineOverlay.show(url, () => {
      // Called when polling detects HA is back online — reload via the single
      // guarded path (shared with the native coordinator).
      console.log('[KioskShell] HA reconnected — reloading iframe');
      window.dashieReloadHaIframe();
    });
  }, 10000);

  iframe.src = url;
};

/**
 * Force-reload the HA iframe.
 *
 * Called by the native DashboardHealthCoordinator to drive recovery while the
 * screen is off — when WebView JS timers (including the offline-overlay poll) are
 * frozen and can't recover an overnight 404. Also used by the awake offline-overlay
 * reconnect callbacks, so there is exactly ONE reload path.
 *
 * CRITICAL — auth preservation: we reload the HA page IN PLACE via
 * contentWindow.location.reload(). That keeps the iframe's localStorage (the HA
 * `hassTokens`) intact, so HA stays authenticated and the existing iframe.onload
 * (error detection + native health report + reload-guard clear) still fires.
 *
 * The previous about:blank approach navigated to a fresh context, which — under
 * modern Android WebView storage partitioning — DROPS the iframe's localStorage.
 * HA then came back with `hassTokens` empty and landed on its login page (which the
 * error detector mis-reads as "healthy" because it has an <ha-authorize> element).
 * i.e. the "recovery" silently logged the user out. See dashieLoadHaWithTokens.
 *
 * about:blank is kept ONLY as a fallback for when the content window is
 * unreachable (a cross-origin Chrome error page, where in-place reload throws).
 */
window.dashieReloadHaIframe = function() {
  if (_haReloadInFlight) {
    console.log('[KioskShell] dashieReloadHaIframe ignored — reload already in flight');
    return;
  }
  const iframe = document.getElementById('ha-content');
  const url = _haLastRequestedUrl;
  if (!iframe || !url) {
    console.warn('[KioskShell] dashieReloadHaIframe: no iframe or URL yet');
    return;
  }
  _haReloadInFlight = true;

  // FAILSAFE: the in-place reload below relies on iframe.onload to clear the guard,
  // but when HA is unreachable the reload can HANG (onload never fires) — e.g. a real
  // outage / screen-asleep. Without this, the guard stays wedged true and EVERY later
  // coordinator reload is dropped at the `_haReloadInFlight` check ("ignored — reload
  // already in flight"), so recovery never happens until a full process restart. (This
  // is the field bug: 814 logged reloads, zero effective.) Arm a timeout that releases
  // the guard and re-reports the error so the native coordinator can escalate.
  if (_haReloadFailsafe) clearTimeout(_haReloadFailsafe);
  _haReloadFailsafe = setTimeout(() => {
    _haReloadFailsafe = null;
    if (_haReloadInFlight) {
      _haReloadInFlight = false;
      console.warn('[KioskShell] dashieReloadHaIframe failsafe — reload did not complete in 12s, releasing guard');
      if (typeof DashieNative !== 'undefined' && DashieNative.onHaIframeError) {
        DashieNative.onHaIframeError(url);
      }
    }
  }, 12000);

  // Preferred path: in-place reload preserves localStorage (HA auth survives).
  try {
    if (iframe.contentWindow && iframe.contentWindow.location) {
      console.log('[KioskShell] dashieReloadHaIframe → in-place reload (auth preserved)');
      iframe.contentWindow.location.reload();
      return;
    }
  } catch (e) {
    // Cross-origin error page — content window not accessible. Fall through.
    console.log('[KioskShell] dashieReloadHaIframe: content window unreachable, about:blank fallback');
  }

  // Fallback: cross-origin error page (HA host unreachable). about:blank drops
  // tokens, so HA may require re-auth here — acceptable for a hard-down instance.
  console.log('[KioskShell] dashieReloadHaIframe → about:blank fallback');
  iframe.onload = null;
  iframe.src = 'about:blank';
  setTimeout(() => { dashieSetHaUrl(url); }, 100);
};

/**
 * Load a custom (non-HA) URL into the custom-content iframe.
 * Hides the HA iframe and shows the custom one instead.
 * HA stays loaded in the background for backend services (voice, entities, etc.)
 * Called by Kotlin when connection.useCustomUrl is enabled.
 */
window.dashieSetCustomUrl = function(url) {
  const customIframe = document.getElementById('custom-content');
  const haIframe = document.getElementById('ha-content');
  if (!customIframe) return;

  console.log('[KioskShell] Loading custom URL:', url);

  // Hide HA iframe, show custom iframe
  if (haIframe) haIframe.style.display = 'none';
  customIframe.style.display = 'block';

  customIframe.onload = function() {
    console.log('[KioskShell] Custom URL iframe loaded');
  };

  customIframe.src = url;
};

/**
 * Switch back from custom URL to HA dashboard display.
 * Called when useCustomUrl is toggled off (e.g., from settings change).
 */
window.dashieShowHaContent = function() {
  const customIframe = document.getElementById('custom-content');
  const haIframe = document.getElementById('ha-content');

  if (customIframe) {
    customIframe.style.display = 'none';
    customIframe.src = '';
  }
  if (haIframe) haIframe.style.display = '';
  console.log('[KioskShell] Switched back to HA content');
};

/**
 * Load HA with pre-injected tokens (post-onboarding flow).
 * Instead of navigating the main WebView away from the shell page to inject
 * tokens (which breaks due to storage partitioning on modern Android WebView),
 * we load HA in the iframe, inject tokens, then reload.
 *
 * Same-origin mode: uses direct iframe.contentWindow.localStorage access.
 * Cross-origin fallback: uses postMessage bridge (injected by KioskCssInjector).
 */
window.dashieLoadHaWithTokens = function(url, tokenJson) {
  const iframe = document.getElementById('ha-content');
  if (!iframe) return;

  console.log('[KioskShell] Loading HA with token injection:', url);

  // Step 1: Load HA in iframe (will show auth page since no tokens yet)
  iframe.onload = function() {
    console.log('[KioskShell] HA iframe loaded (auth page) — injecting tokens');

    // Step 2: Inject tokens into HA's localStorage
    let injected = false;

    // Same-origin: direct localStorage access (no bridge needed)
    try {
      if (iframe.contentWindow && iframe.contentWindow.localStorage) {
        iframe.contentWindow.localStorage.setItem('hassTokens', tokenJson);
        console.log('[KioskShell] Tokens injected via direct localStorage access');
        injected = true;
      }
    } catch (e) {
      console.warn('[KioskShell] Direct localStorage failed:', e.message);
    }

    // Cross-origin fallback: postMessage bridge
    if (!injected) {
      const escapedJson = tokenJson.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
      iframe.contentWindow.postMessage({
        source: 'dashie-parent',
        type: 'eval',
        script: "try { localStorage.setItem('hassTokens', '" + escapedJson + "'); console.log('Dashie: Tokens injected into HA localStorage'); } catch(e) { console.error('Dashie: Token injection failed:', e); }"
      }, '*');
    }

    // Step 3: Reload the iframe so HA picks up the tokens
    setTimeout(() => {
      console.log('[KioskShell] Reloading HA iframe after token injection');
      iframe.onload = function() {
        console.log('[KioskShell] HA iframe loaded with tokens');

        // Same-origin: inject kiosk scripts after token reload too
        if (window._dashieInjectionScripts) {
          injectScriptsViaContentDocument(iframe);
        }

        if (typeof DashieNative !== 'undefined' && DashieNative.onHaIframeLoaded) {
          DashieNative.onHaIframeLoaded();
        }
        setTimeout(() => {
          const isDark = document.documentElement.classList.contains('theme-dark');
          syncHaIframeTheme(isDark);
          fetchPreferredPipelineName();
        }, 2000);
      };
      iframe.src = url;
    }, 500); // Brief delay for localStorage write to settle
  };

  iframe.src = url;
};

/**
 * Execute a script in the HA iframe context.
 * Same-origin: injects via contentDocument (direct, no bridge needed).
 * Cross-origin fallback: uses postMessage to parent bridge handler.
 *
 * Returns 'same-origin' if direct injection worked, 'cross-origin' if
 * fell back to postMessage (which may silently fail if the iframe page
 * has no listener), or 'no-iframe' if the iframe element wasn't found.
 */
window.evalInHaIframe = function(script) {
  const iframe = document.getElementById('ha-content');
  if (!iframe) return 'no-iframe';

  // Same-origin: direct script injection via contentDocument
  try {
    if (iframe.contentDocument) {
      const el = iframe.contentDocument.createElement('script');
      el.textContent = script;
      (iframe.contentDocument.head || iframe.contentDocument.documentElement).appendChild(el);
      return 'same-origin';
    }
  } catch (e) {
    // SecurityError = cross-origin, fall through to postMessage
  }

  // Cross-origin fallback: postMessage
  if (iframe.contentWindow) {
    iframe.contentWindow.postMessage({
      source: 'dashie-parent',
      type: 'eval',
      script: script
    }, '*');
  }
  return 'cross-origin';
};

/**
 * Fetch the preferred Assist pipeline name from HA and cache it in localStorage.
 * Uses hass.callWS inside the HA iframe, which posts the result back via postMessage.
 */
function fetchPreferredPipelineName() {
  if (typeof window.evalInHaIframe !== 'function') return;
  window.evalInHaIframe(`
    try {
      var ha = document.querySelector('home-assistant');
      if (ha && ha.hass && ha.hass.callWS) {
        ha.hass.callWS({type: 'assist_pipeline/pipeline/list'}).then(function(result) {
          var pref = result.pipelines.find(function(p) { return p.id === result.preferred_pipeline; });
          if (pref && pref.name) {
            window.parent.postMessage({source: 'dashie-ha', type: 'preferred-pipeline-name', name: pref.name}, '*');
          }
        });
      }
    } catch(e) {}
  `);
}

// Listen for pipeline name response from HA iframe
window.addEventListener('message', function(e) {
  if (e.data?.source === 'dashie-ha' && e.data?.type === 'preferred-pipeline-name') {
    try {
      localStorage.setItem('dashie-preferred-pipeline-name', e.data.name);
      console.log('[KioskShell] Cached preferred pipeline name:', e.data.name);
    } catch(err) { /* localStorage unavailable */ }
  }
});

/**
 * Extract HA auth tokens from the HA iframe's localStorage and sync to native.
 * Same-origin: direct localStorage access.
 * Cross-origin: uses evalInHaIframe + postMessage roundtrip.
 */
function extractHaTokens() {
  const iframe = document.getElementById('ha-content');

  // Same-origin: direct localStorage access (much simpler)
  try {
    const win = iframe?.contentWindow;
    if (win?.localStorage) {
      let tokens = win.localStorage.getItem('hassTokens');

      // Checkbox-independent persistence: if HA authenticated but didn't persist
      // hassTokens (user left "Keep me logged in" unchecked → HA keeps the token
      // in memory only, in home-assistant.hass.auth.data), copy it back into
      // localStorage. `hassTokens` is literally JSON.stringify(hass.auth.data),
      // so this is exactly what HA's saveTokens() would write if the box were
      // checked. Required for a kiosk display to survive iframe reloads — without
      // it, every reload (recovery, screen-wake, restart) lands on the login page
      // over the proxy, where hassTokens for this origin was never persisted.
      if (!tokens) {
        const authData = win.document?.querySelector('home-assistant')?.hass?.auth?.data;
        if (authData?.access_token && authData?.refresh_token) {
          tokens = JSON.stringify(authData);
          win.localStorage.setItem('hassTokens', tokens);
          console.log('[KioskShell] Persisted in-memory HA token to localStorage (checkbox-independent)');
        }
      }

      if (tokens && window.dashieDevice?.syncHaTokens) {
        window.dashieDevice.syncHaTokens(tokens);
        console.log('[KioskShell] HA tokens synced to native (same-origin)');
        return;
      }
    }
  } catch (e) {
    // SecurityError = cross-origin, fall through
  }

  // Cross-origin fallback: evalInHaIframe + postMessage. Same checkbox-independent
  // persistence runs inside the iframe context before relaying tokens up.
  if (typeof window.evalInHaIframe !== 'function') return;
  window.evalInHaIframe(`
    try {
      var tokens = localStorage.getItem('hassTokens');
      if (!tokens) {
        var d = document.querySelector('home-assistant')?.hass?.auth?.data;
        if (d && d.access_token && d.refresh_token) {
          tokens = JSON.stringify(d);
          localStorage.setItem('hassTokens', tokens);
        }
      }
      if (tokens) {
        window.parent.postMessage({source: 'dashie-ha', type: 'ha-tokens', tokens: tokens}, '*');
      }
    } catch(e) {}
  `);
}

// Listen for token response from HA iframe and sync to native SharedPreferences
window.addEventListener('message', function(e) {
  if (e.data?.source === 'dashie-ha' && e.data?.type === 'ha-tokens' && e.data.tokens) {
    try {
      if (window.dashieDevice?.syncHaTokens) {
        window.dashieDevice.syncHaTokens(e.data.tokens);
        console.log('[KioskShell] HA tokens synced to native');
      }
    } catch(err) { /* bridge unavailable */ }
  }
});

// Refresh tokens every 20 minutes (HA tokens expire after 30 minutes)
setInterval(extractHaTokens, 20 * 60 * 1000);

/**
 * Sync video feed definitions from HA registry → localStorage + SharedPreferences.
 * Ensures the camera popout menu and control center see HA-managed feeds
 * without requiring the user to open Settings → Video Feeds first.
 */
function syncVideoFeedsFromHa() {
  if (typeof window.evalInHaIframe !== 'function') return;
  // Get device ID for subscription lookup
  var deviceId = 'unknown';
  try {
    if (typeof DashieNative !== 'undefined' && DashieNative.getDeviceId) {
      deviceId = DashieNative.getDeviceId();
    }
  } catch(e) {}
  window.evalInHaIframe(`
    try {
      var hass = document.querySelector('home-assistant')?.hass;
      if (hass && hass.callApi) {
        Promise.all([
          hass.callApi('GET', 'dashie/feeds'),
          hass.callApi('GET', 'dashie/feeds/subscriptions/${deviceId}')
        ]).then(function(results) {
          window.parent.postMessage({source: 'dashie-ha', type: 'video-feeds-sync', data: JSON.stringify({
            feeds: (results[0] || {}).feeds || {},
            subscription: results[1] || {}
          })}, '*');
        }).catch(function() {});
      }
    } catch(e) {}
  `);
}

// Listen for feed sync response — HA feeds are source of truth, replace local duplicates
window.addEventListener('message', function(e) {
  if (e.data?.source === 'dashie-ha' && e.data?.type === 'video-feeds-sync') {
    try {
      var result = JSON.parse(e.data.data);
      var haFeeds = result.feeds || {};
      var feedModes = (result.subscription || {}).feed_modes || {};
      var feedIds = Object.keys(haFeeds);
      if (feedIds.length === 0) return;

      // Read native-first: Kotlin owns the feed config on Android and localStorage is
      // only a mirror. Merging against a stale/empty mirror would both miss existing
      // rules and re-add feeds Kotlin already has.
      var config = getVideoFeedConfig();

      // Skip sync if video feeds feature is disabled
      if (!config.enabled) {
        console.log('[KioskShell] Feed sync skipped — video feeds disabled');
        return;
      }

      // Build sets of HA feed IDs and camera entities for dedup
      var haFeedIdSet = new Set(feedIds);
      var haCameraEntities = new Set();
      for (var i = 0; i < feedIds.length; i++) {
        var cam = haFeeds[feedIds[i]].camera_entity_id;
        if (cam) haCameraEntities.add(cam);
      }

      // Remove local rules that duplicate an HA feed (by ID or camera entity)
      var before = config.rules.length;
      config.rules = (config.rules || []).filter(function(r) {
        if (haFeedIdSet.has(r.id)) return false;
        if (r.cameraEntityId && haCameraEntities.has(r.cameraEntityId)) return false;
        return true;
      });
      var removed = before - config.rules.length;

      // Add all HA feeds as local rules, respecting subscription modes
      var existingIds = new Set(config.rules.map(function(r) { return r.id; }));
      var added = 0;
      for (var j = 0; j < feedIds.length; j++) {
        var feedId = feedIds[j];
        if (existingIds.has(feedId)) continue;
        var feed = haFeeds[feedId];
        var trigger = (feed.triggers || [])[0];
        var mode = feedModes[feedId] || feed.default_mode || 'subscribed';
        config.rules.push({
          id: feedId,
          name: feed.label || feedId,
          cameraEntityId: feed.camera_entity_id || '',
          cameraName: feed.label || '',
          triggerEntityId: trigger ? trigger.entity_id : '',
          triggerState: trigger ? (trigger.state || 'on') : 'on',
          autoDismissSeconds: feed.auto_dismiss_seconds != null ? feed.auto_dismiss_seconds : 30,
          continueWhileActive: feed.continue_while_active != null ? feed.continue_while_active : true,
          streamSourceType: feed.stream_source_type || 'entity',
          streamSourceUrl: feed.stream_source_url || '',
          playSoundOnTrigger: !!feed.alert_sound,
          triggerSound: feed.alert_sound || 'notify_bell_tap',
          enabled: mode !== 'ignored',
        });
        added++;
      }

      if (added > 0 || removed > 0) {
        // Persist subscription modes so settings page can read them
        if (Object.keys(feedModes).length > 0) config.feedModes = feedModes;
        localStorage.setItem(VIDEO_FEED_STORAGE_KEY, JSON.stringify(config));
        if (typeof DashieNative !== 'undefined' && DashieNative.saveVideoFeedConfig) {
          DashieNative.saveVideoFeedConfig(JSON.stringify(config));
        }
        console.log('[KioskShell] Feed sync: added=' + added + ', deduped=' + removed + ', total=' + config.rules.length);
      }
    } catch(err) {
      console.warn('[KioskShell] Feed sync error:', err);
    }
  }
});

/**
 * Dynamically show/hide the sidebar strip and resize the HA iframe.
 * Called by Kotlin via evaluateJavascript when the settings toggle changes.
 * Uses CSS class + visibility:hidden to avoid destroying backdrop-filter
 * compositing layers (display:none breaks blur on Android WebView).
 */
window.dashieToggleSidebar = function(visible) {
  console.log('[KioskShell] Toggle sidebar:', visible);
  document.body.classList.toggle('sidebar-hidden', !visible);
};

// Sidebar control functions — delegate to native Kotlin sidebar via DashieNative bridge.
// dashieRevealSidebar / dashieStopSidebarAutoHide are called by the onboarding tip flow
// to show the sidebar alongside quick tip cards.
window.dashieSetSidebarConfig = function(config) {};
window.dashieRevealSidebar = function() {
  try { if (typeof DashieNative !== 'undefined') DashieNative.revealNativeSidebar(); } catch(e) {}
};
window.dashieStopSidebarAutoHide = function() {
  try { if (typeof DashieNative !== 'undefined') DashieNative.stopSidebarAutoHide(); } catch(e) {}
};
window.dashieDismissSidebar = function() {};
window.dashieSetLockState = function(locked) {};
window.dashieRefreshMusicVisibility = function() {};

/**
 * Show post-onboarding guided tips. Called by Kotlin after onboarding completes
 * and the shell page has reloaded (token injection navigates away and back).
 */
window.dashieShowOnboardingTips = function() {
  console.log('[KioskShell] Showing onboarding tips');
  // Clear stale onboarding reference — controller already destroyed by _complete()
  onboarding = null;

  // 🔴 ORPHAN SWEEP. Every call to this function builds a FRESH closure with its
  // own `tipOverlay`, so a card appended by a previous call is invisible to this
  // one's removeTip() — it would sit in the DOM with no reference able to remove
  // it. Sweep before we add anything, so a second invocation can never leave a
  // stuck card behind. Observed on a Fire tablet (2026-08-18): the "Setting up
  // Dashie" card could not be dismissed by either of its buttons — a
  // programmatic .click() left the node AND its `visible` class untouched,
  // proving removeTip()'s `if (tipOverlay)` guard was false while the card was
  // on screen.
  document.querySelectorAll('.onboarding-tip-overlay').forEach(el => {
    console.log('[KioskShell] Removing orphaned tip overlay from a previous invocation');
    el.remove();
  });

  let tipOverlay = null;
  let tipFocusIndex = 0;

  // Tell Kotlin to forward ALL d-pad keys during tips (tips happen after onboarding
  // completes, so the Kotlin !isSetupComplete check no longer fires)
  window.overlayHasKeyboardFocus = true;
  try { DashieNative.setOverlayKeyboardFocus(true); } catch (e) { /* bridge unavailable */ }

  // Clear any stale d-pad focus highlights on sidebar buttons
  document.querySelectorAll('.dm-dpad-focus').forEach(el => el.classList.remove('dm-dpad-focus'));

  /** Get focusable buttons inside the current tip card. */
  function getTipButtons() {
    if (!tipOverlay) return [];
    return Array.from(tipOverlay.querySelectorAll('button'));
  }

  /** Update d-pad highlight on tip card buttons. */
  function updateTipHighlight() {
    const buttons = getTipButtons();
    buttons.forEach((btn, i) => {
      btn.classList.toggle('onboarding-dpad-focus', i === tipFocusIndex);
    });
  }

  /** D-pad handler for tip cards — registered as window.dashieOnboardingDpad. */
  function handleTipDpad(keyCode) {
    const buttons = getTipButtons();
    if (!buttons.length) return;
    switch (keyCode) {
      case 21: // LEFT
      case 19: // UP
        if (tipFocusIndex > 0) { tipFocusIndex--; updateTipHighlight(); }
        break;
      case 22: // RIGHT
      case 20: // DOWN
        if (tipFocusIndex < buttons.length - 1) { tipFocusIndex++; updateTipHighlight(); }
        break;
      case 23: // CENTER
      case 66: // ENTER
        buttons[tipFocusIndex]?.click();
        break;
      case 4:  // BACK — dismiss tip (click first/dismiss button)
        buttons[0]?.click();
        break;
    }
  }

  /**
   * Dismiss a tip card.
   *
   * @param {HTMLElement} [node] the overlay to remove. Callers that OWN a
   *   specific card (its own buttons) pass it explicitly — see below.
   *
   * 🔴 Do NOT go back to gating solely on the closure's `tipOverlay`. That
   * variable is mutable and is nulled by several paths (this function itself,
   * and `dashieOnSidebarDismissed`), so a click handler could run when it was
   * already null and silently remove NOTHING — leaving the card on screen with
   * no way to dismiss it, which is exactly the bug this shape fixes. A card's
   * own button must always be able to remove that card, whatever the shared
   * state happens to be.
   */
  function removeTip(node) {
    const toRemove = node || tipOverlay;
    if (toRemove) {
      // Only clear the shared slot if we are removing what it points at —
      // otherwise an explicit removal would null out an unrelated live card.
      if (toRemove === tipOverlay) tipOverlay = null;
      toRemove.classList.remove('visible');
      setTimeout(() => toRemove.remove(), 300);
    }
    // Keep dashieOnboardingDpad as a blocking handler between tips
    // so d-pad input doesn't leak to sidebar/HA during transitions.
    // Set a no-op handler that consumes all keys.
    window.dashieOnboardingDpad = () => {};
  }

  /** Final cleanup — remove blocking handler and release keyboard focus. */
  function finishTips() {
    delete window.dashieOnboardingDpad;
    delete window.dashieOnSidebarDismissed;
    // Only release keyboard focus if settings/dash bar don't have control
    if (!window._shellSettingsDpad && !window.dashieDashBarDpad) {
      window.overlayHasKeyboardFocus = false;
      try { DashieNative.setOverlayKeyboardFocus(false); } catch (e) { /* bridge unavailable */ }
    }
  }

  function dismissSidebarAndMenu() {
    const highlighted = document.querySelector('.onboarding-highlight');
    if (highlighted) highlighted.classList.remove('onboarding-highlight');
    try { if (typeof DashieNative !== 'undefined') DashieNative.dismissNativeSidebar(); } catch(e) {}
    finishTips();
  }

  /** Install d-pad handler for tip cards and auto-highlight the primary button. */
  function activateTipDpad() {
    tipFocusIndex = 0;
    window.dashieOnboardingDpad = handleTipDpad;
    // Auto-highlight after DOM settles (slight delay for low-end devices)
    setTimeout(() => {
      const buttons = getTipButtons();
      // Focus the primary button (last one, typically the action button)
      tipFocusIndex = buttons.length > 1 ? buttons.length - 1 : 0;
      updateTipHighlight();
    }, 100);
  }

  function showControlCenterTip() {
    // Reveal sidebar + open hamburger popout (no backdrop so tip card is visible).
    try { if (typeof DashieNative !== 'undefined') DashieNative.openHamburgerPopout(); } catch(e) {}

    // Wait for the popout layout to complete, then get the Control Center item's screen bounds
    // and position the tip card to the RIGHT of the popout with the arrow pointing at it.
    setTimeout(() => {
      let bounds = {};
      try {
        if (typeof DashieNative !== 'undefined') {
          bounds = JSON.parse(DashieNative.getControlCenterItemBounds());
        }
      } catch(e) {}

      // `el` is referenced inside its own handlers — declared with `let` and
      // assigned below so both closures capture the binding. Each handler
      // removes THE CARD IT BELONGS TO rather than whatever the shared
      // `tipOverlay` currently points at, so a card's own button always works.
      let el;
      el = renderControlCenterTip({
        onGoToControlCenter: () => {
          removeTip(el);
          dismissSidebarAndMenu();
          setTimeout(() => {
            if (typeof DashieNative !== 'undefined' && DashieNative.openControlCenter) {
              DashieNative.openControlCenter();
            } else {
              // window.openSettingsToPage died with the kiosk settings bundle (2026-03);
              // on-device DashieNative always exists, so this branch is browser-dev only.
              console.warn('[KioskShell] DROP: openControlCenter unavailable (no DashieNative)');
            }
          }, 300);
        },
        onStart: () => {
          removeTip(el);
          dismissSidebarAndMenu();
        }
      });

      tipOverlay = el;
      document.body.appendChild(el);

      // Position tip card to the right of the popout, arrow pointing at Control Center item.
      // Native getLocationOnScreen() returns physical pixels; convert to CSS pixels via dpr.
      const card = el.querySelector('.onboarding-tip-card');
      if (card && bounds.popoutRight > 0 && bounds.controlCenterY > 0) {
        // Kotlin getControlCenterItemBounds returns physical pixels — use the
        // ACTUAL physical/CSS ratio for conversion. window.devicePixelRatio
        // is unreliable on some Fire TV devices (D.78 — reported 4 when real
        // ratio was 2) which previously made the tip card land 2× too far
        // right. Prefer Android's display density via the bridge when the
        // APK has the new getDisplayDensity method; fall back to dpr.
        let dpr;
        try {
          const d = window.DashieNative?.getDisplayDensity?.();
          dpr = (typeof d === 'number' && d > 0) ? d : (window.devicePixelRatio || 1);
        } catch (_) {
          dpr = window.devicePixelRatio || 1;
        }
        const cardWidth = card.offsetWidth;
        const cardHeight = card.offsetHeight;
        const margin = 16;
        let cardLeft = bounds.popoutRight / dpr + 64;
        console.log('[KioskShell] Tip card layout debug', {
          rawBounds: bounds,
          dpr,
          innerWidth: window.innerWidth,
          innerHeight: window.innerHeight,
          cardWidth,
          cardHeight,
          cardLeftRaw: cardLeft
        });

        // D.64 v1 — clamp right edge so the card never overflows the
        // viewport. Mirrors the cardTop clamp below. Safety net for
        // any device whose popoutRight+cardWidth pushes off-screen.
        if (cardLeft + cardWidth > window.innerWidth - margin) {
          cardLeft = window.innerWidth - margin - cardWidth;
        }
        if (cardLeft < margin) cardLeft = margin;

        let cardTop = bounds.controlCenterY / dpr - cardHeight / 2;
        if (cardTop + cardHeight > window.innerHeight - margin) cardTop = window.innerHeight - margin - cardHeight;
        if (cardTop < margin) cardTop = margin;

        card.style.left = cardLeft + 'px';
        card.style.top = cardTop + 'px';
        card.style.transform = 'none';

        // Arrow points at the Control Center item
        const arrow = card.querySelector('.onboarding-tip-arrow--left');
        if (arrow) {
          arrow.style.top = (bounds.controlCenterY / dpr - cardTop) + 'px';
          arrow.style.transform = 'translateY(-50%)';
        }
      }

      requestAnimationFrame(() => el.classList.add('visible'));
      activateTipDpad();
    }, 300);
  }

  // Listen for native sidebar dismissal — if the user taps outside the tip card,
  // the native sidebar/popout may close first (consuming the touch). Auto-dismiss
  // the current tip so the user doesn't get stuck with an orphaned tip card.
  window.dashieOnSidebarDismissed = function() {
    if (tipOverlay) {
      console.log('[KioskShell] Sidebar dismissed during tip — auto-dismissing tip');
      removeTip();
      finishTips();
    }
  };

  // Step 1: Reveal sidebar and show swipe tip (stop auto-hide so sidebar stays during tip)
  if (window.dashieRevealSidebar) window.dashieRevealSidebar();
  if (window.dashieStopSidebarAutoHide) window.dashieStopSidebarAutoHide();

  const swipeTipEl = renderSwipeTip({
    onGotIt: () => {
      // Explicit node, same reason as the Control Center card: this button
      // dismisses THIS card regardless of the shared slot's current value.
      removeTip(swipeTipEl);
      showControlCenterTip();
    }
  });
  tipOverlay = swipeTipEl;
  document.body.appendChild(swipeTipEl);
  requestAnimationFrame(() => swipeTipEl.classList.add('visible'));
  activateTipDpad();
};

/**
 * Handle back button press. Called by Kotlin's onBackPressedDispatcher.
 * Routes back to the appropriate handler: onboarding, settings overlay, or dash bar.
 */
window.dashieHandleBack = function() {
  console.log('[KioskShell] Back pressed');

  // 1. Onboarding active — navigate back within onboarding
  // Check overlay too: after onboarding completes, destroy() nulls .overlay
  // but the outer `onboarding` variable may still hold the dead instance.
  if (onboarding && onboarding.overlay) {
    console.log('[KioskShell] Back → onboarding');
    onboarding.handleBack();
    return;
  }

  // 1.5. CC overlay visible — route back to CC overlay d-pad handler
  if (window._ccOverlayDpad) {
    console.log('[KioskShell] Back → CC overlay');
    window._ccOverlayDpad(4); // KEYCODE_BACK
    return;
  }

  // 2. Settings open on shell page — route back to settings input handler
  if (window._shellSettingsDpad) {
    console.log('[KioskShell] Back → shell settings');
    window._shellSettingsDpad(4); // KEYCODE_BACK
    return;
  }

  // 3. Overlay iframe has focus — forward back to overlay
  if (window._overlayWantsKeys) {
    console.log('[KioskShell] Back → overlay iframe');
    const overlayIframe = document.getElementById('dashie-overlay');
    if (overlayIframe?.contentWindow) {
      overlayIframe.contentWindow.postMessage({
        source: 'dashie-parent', type: 'remote-input', keyCode: 4 // KEYCODE_BACK
      }, '*');
      return;
    }
  }

  // 4. Fallback: reveal the native Kotlin sidebar. If we got here, Kotlin's
  //    dispatchKeyEvent already chose NOT to focus the sidebar itself (e.g.
  //    overlayHasKeyboardFocus stuck true after a tip flow). Without this
  //    fallback BACK would dead-end and the user couldn't reach the sidebar
  //    or control center.
  console.log('[KioskShell] Back → reveal native sidebar (fallback)');
  try {
    if (typeof DashieNative !== 'undefined' && DashieNative.revealNativeSidebar) {
      DashieNative.revealNativeSidebar();
    }
  } catch(e) { console.warn('[KioskShell] revealNativeSidebar failed:', e); }
};

/**
 * Color scheme change callback. Called by MainDarkModeHandler when system
 * dark/light mode changes. Applies theme class to kiosk overlay UI.
 */
window.onColorSchemeChanged = function(isDark) {
  console.log('[KioskShell] Color scheme changed, isDark:', isDark);
  applyThemeClass(isDark);
  syncHaIframeTheme(isDark);
};

// Initialize on DOM ready
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}

// ── Brand ──
// The document title is set HERE rather than in kiosk-shell.html: the HTML is static and
// shared by both editions, so a literal there is a Dashie string shipped to every Chickadee
// device. Same reason the onboarding strings moved into the brand table.
try { document.title = getBrand().name; } catch (e) { /* pre-DOM or no bridge; harmless */ }

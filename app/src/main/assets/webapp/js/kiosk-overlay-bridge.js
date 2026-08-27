/**
 * Kiosk Overlay Bridge
 * Sets up communication between the shell page and the headless overlay iframe.
 * Replaces the proxy code that was previously injected by OverlayInjector.kt.
 *
 * Handles:
 * - Overlay ready detection
 * - Screensaver proxy (window.screensaver)
 * - Drawer proxy (window.dashieOverlay)
 * - Keyboard/d-pad input routing
 * - Timer event forwarding (JS → Kotlin via DashieNative)
 * - Click forwarding for touch passthrough
 */

const READY_TIMEOUT = 15000;
const MAX_RETRIES = 2;

let iframe = null;
let isReady = false;
let retryCount = 0;
let readyTimeout = null;

/**
 * Initialize the overlay bridge.
 * Call after DOM is ready — finds the overlay iframe and starts listening.
 */
export function initOverlayBridge() {
  iframe = document.getElementById('dashie-overlay');
  if (!iframe) {
    console.warn('[KioskShell] Overlay iframe not found');
    return;
  }

  // Set up message listener
  window.addEventListener('message', handleOverlayMessage);

  // Set up global keyboard router (available immediately)
  window.overlayHasKeyboardFocus = false;
  window.handleRemoteInput = handleRemoteInput;
  window.forwardKeyToOverlay = forwardKeyToOverlay;

  // Start ready timeout
  startReadyTimeout();

  console.log('[KioskShell] Overlay bridge initialized');
}

function startReadyTimeout() {
  if (readyTimeout) clearTimeout(readyTimeout);
  readyTimeout = setTimeout(() => {
    if (!isReady && retryCount < MAX_RETRIES) {
      retryCount++;
      console.warn(`[KioskShell] No overlay ready signal after ${READY_TIMEOUT}ms — retrying (${retryCount}/${MAX_RETRIES})`);
      iframe.src = iframe.src.split('?')[0] + '?retry=' + retryCount;
      startReadyTimeout();
    } else if (!isReady) {
      console.error(`[KioskShell] Overlay failed to load after ${MAX_RETRIES} retries`);
    }
  }, READY_TIMEOUT);
}

function handleOverlayMessage(event) {
  if (!iframe || event.source !== iframe.contentWindow) return;
  const data = event.data;
  if (!data || data.source !== 'dashie-overlay') return;

  switch (data.type) {
    case 'ready':
      onOverlayReady();
      break;
    case 'pointer-events':
      iframe.style.pointerEvents = data.enable ? 'auto' : 'none';
      if (data.enable) iframe.focus();
      break;
    case 'close-sidebar':
      if (typeof DashieNative !== 'undefined' && DashieNative.closeSidebar) {
        DashieNative.closeSidebar();
      }
      break;
    case 'keyboard-focus':
      // Track explicit overlay focus separately from the dash bar piggyback flag
      window._overlayWantsKeys = data.capture;
      // Don't let overlay iframe clear the Kotlin-side keyboard focus flag
      // when shell-page settings has d-pad control — settings manages its own
      // activation/deactivation via activateSettingsDpad()/deactivateSettingsDpad()
      if (window._shellSettingsDpad) break;
      // Keep overlayHasKeyboardFocus true if dash bar is also active
      // (Kotlin needs it to forward ALL d-pad keys to JS)
      window.overlayHasKeyboardFocus = data.capture || !!window.dashieDashBarDpad;
      if (typeof DashieNative !== 'undefined' && DashieNative.setOverlayKeyboardFocus) {
        DashieNative.setOverlayKeyboardFocus(data.capture || !!window.dashieDashBarDpad);
      }
      break;
    case 'forward-click':
      if (typeof DashieNative !== 'undefined' && DashieNative.injectTouch) {
        DashieNative.injectTouch(data.x, data.y);
      }
      break;
    case 'timer-created':
      if (typeof DashieNative !== 'undefined' && DashieNative.onTimerCreated) {
        DashieNative.onTimerCreated(JSON.stringify(data.timer));
      }
      break;
    case 'timer-updated':
      if (typeof DashieNative !== 'undefined' && DashieNative.onTimerUpdated) {
        DashieNative.onTimerUpdated(JSON.stringify(data.timer));
      }
      break;
    case 'timer-completed':
      if (typeof DashieNative !== 'undefined' && DashieNative.onTimerCompleted) {
        DashieNative.onTimerCompleted(JSON.stringify(data.timer));
      }
      break;
    case 'timer-cancelled':
      if (typeof DashieNative !== 'undefined' && DashieNative.onTimerCancelled) {
        DashieNative.onTimerCancelled(data.timerId);
      }
      break;
  }
}

function onOverlayReady() {
  console.log('[KioskShell] Overlay ready');
  isReady = true;
  window.dashieOverlayReady = true;
  if (readyTimeout) { clearTimeout(readyTimeout); readyTimeout = null; }

  // Install proxies now that overlay is ready
  setupScreensaverProxy();
  setupDrawerProxy();
}

function sendToOverlay(method, args) {
  if (!iframe?.contentWindow) return;
  iframe.contentWindow.postMessage({
    source: 'dashie-parent',
    type: 'screensaver-call',
    method,
    args: args || [],
  }, '*');
}

function setupScreensaverProxy() {
  window.screensaver = {
    activate: () => sendToOverlay('activate'),
    deactivate: () => sendToOverlay('deactivate'),
    showPhoto: (url, date, location) => sendToOverlay('showPhoto', [url, date, location]),
    updateMetadata: (date, location, forUrl) => sendToOverlay('updateMetadata', [date, location, forUrl]),
    configure: (mode, showClock, showMetadata, screensaverUrl) => sendToOverlay('configure', [mode, showClock, showMetadata, screensaverUrl]),
    setPhotos: (urls) => sendToOverlay('setPhotos', [urls]),
    setAuthToken: (token) => sendToOverlay('setAuthToken', [token]),
    showThumbnail: (url) => sendToOverlay('showThumbnail', [url]),
    hideThumbnail: () => sendToOverlay('hideThumbnail'),
    openGallery: (index) => sendToOverlay('openGallery', [index]),
  };
}

function setupDrawerProxy() {
  window.dashieOverlay = {
    openDrawer: () => {
      iframe.style.pointerEvents = 'auto';
      iframe.contentWindow?.postMessage({ source: 'dashie-parent', type: 'open-drawer' }, '*');
    },
    closeDrawer: () => {
      iframe.contentWindow?.postMessage({ source: 'dashie-parent', type: 'close-drawer' }, '*');
    },
    forwardKey: (keyCode) => {
      iframe.contentWindow?.postMessage({ source: 'dashie-parent', type: 'remote-input', keyCode }, '*');
    },
  };
}

const KEY_NAMES = { 4:'BACK', 19:'UP', 20:'DOWN', 21:'LEFT', 22:'RIGHT', 23:'CENTER', 66:'ENTER' };

function handleRemoteInput(keyCode) {
  const keyName = KEY_NAMES[keyCode] || keyCode;

  // During onboarding, route d-pad to onboarding handler
  if (window.dashieOnboardingDpad) {
    console.log(`[DpadRoute] ${keyName} → onboarding`);
    window.dashieOnboardingDpad(keyCode);
    return;
  }

  // CC overlay d-pad (pre-rendered Control Center, highest priority after onboarding)
  if (window._ccOverlayDpad) {
    console.log(`[DpadRoute] ${keyName} → ccOverlay`);
    window._ccOverlayDpad(keyCode);
    return;
  }

  // Shell-page settings d-pad (settings runs in the shell page, not the iframe)
  if (window._shellSettingsDpad) {
    console.log(`[DpadRoute] ${keyName} → shellSettings`);
    window._shellSettingsDpad(keyCode);
    return;
  }

  // Overlay explicitly captured keyboard focus (e.g. future iframe-based modals)
  if (window._overlayWantsKeys && iframe?.contentWindow) {
    console.log(`[DpadRoute] ${keyName} → overlayIframe`);
    iframe.contentWindow.postMessage({ source: 'dashie-parent', type: 'remote-input', keyCode }, '*');
    return;
  }

  // Dash bar d-pad navigation (when sidebar is visible and has focus)
  if (window.dashieDashBarDpad) {
    console.log(`[DpadRoute] ${keyName} → dashBar`);
    window.dashieDashBarDpad(keyCode);
    return;
  }

  if (window.overlayHasKeyboardFocus) {
    console.log(`[DpadRoute] ${keyName} → overlayFocus`);
    iframe?.contentWindow?.postMessage({ source: 'dashie-parent', type: 'remote-input', keyCode }, '*');
    return;
  }

  // BACK or LEFT with no active handler → reveal dash bar
  if ((keyCode === 4 || keyCode === 21) && window.dashieHandleBack) {
    console.log(`[DpadRoute] ${keyName} → handleBack`);
    window.dashieHandleBack();
    return;
  }

  console.log(`[DpadRoute] ${keyName} → unhandled (passthrough to WebView)`);
}

function forwardKeyToOverlay(keyCode) {
  iframe?.contentWindow?.postMessage({ source: 'dashie-parent', type: 'remote-input', keyCode }, '*');
}

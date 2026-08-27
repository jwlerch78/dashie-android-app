/**
 * Pointer Layer Manager
 *
 * Centralized management of iframe pointer-events for the overlay.
 *
 * Problem: The overlay iframe has pointer-events: none by default so HA
 * receives touches. Multiple features (screensaver, gallery, thumbnail)
 * need to temporarily enable pointer-events.
 * When each feature independently toggles pointer-events, they race and
 * override each other, leaving the iframe in the wrong state.
 *
 * Solution: A stack-based layer system. Each feature registers/unregisters
 * its layer. If ANY layer is active, pointer-events are enabled. Only when
 * all layers are inactive do pointer-events return to none.
 *
 * Layer Types:
 * - "blocking" layers (settings, screensaver) capture ALL clicks
 * - "pass-through" layers (performance-overlay, timers) only capture clicks
 *   on their specific elements - clicks elsewhere forward to HA dashboard
 */

const LOG_TAG = '[PointerLayerManager]';
const DIAG_TAG = '[PLM:DIAG]';

/** @type {Set<string>} Currently active layers */
const activeLayers = new Set();

/** @type {Set<string>} Layers that block all clicks (vs pass-through) */
const blockingLayers = new Set(['screensaver', 'gallery', 'thumbnail']);

/** @type {boolean} Current pointer-events state sent to parent */
let currentState = false;

/** @type {boolean} Whether click-through handler is installed */
let clickThroughInstalled = false;

function diagLog(msg, data) {
  const timestamp = new Date().toISOString().substring(11, 23);
  console.log(`${DIAG_TAG} [${timestamp}] ${msg}`, data || '');
}

/**
 * Notify parent window to set iframe pointer-events.
 * @param {boolean} enable
 */
function notifyParent(enable) {
  if (enable === currentState) {
    diagLog('notifyParent: no change needed', { currentState, enable });
    return; // No change needed
  }

  const oldState = currentState;
  currentState = enable;

  console.log(LOG_TAG, enable ? 'ENABLE' : 'DISABLE', 'pointer-events. Active layers:', [...activeLayers].join(', ') || '(none)');
  diagLog('Pointer-events state change', {
    from: oldState ? 'auto' : 'none',
    to: enable ? 'auto' : 'none',
    activeLayers: [...activeLayers]
  });

  // Manage click-through handler based on state
  if (enable) {
    // Install click-through handler so clicks on empty space reach HA
    installClickThroughHandler();
  } else {
    // Remove handler when pointer-events are disabled
    removeClickThroughHandler();
  }

  if (window.parent && window.parent !== window) {
    window.parent.postMessage({
      source: 'dashie-overlay',
      type: 'pointer-events',
      enable,
      layers: [...activeLayers]  // Include layers for diagnostic purposes
    }, '*');
  }
}

/**
 * Register a layer as needing pointer-events.
 * @param {string} layerName - Unique layer identifier (e.g., 'screensaver', 'gallery', 'thumbnail', 'settings')
 */
export function addLayer(layerName) {
  const wasEmpty = activeLayers.size === 0;
  activeLayers.add(layerName);
  diagLog('addLayer', {
    layer: layerName,
    wasEmpty,
    activeLayers: [...activeLayers]
  });
  notifyParent(true);
}

/**
 * Unregister a layer - it no longer needs pointer-events.
 * @param {string} layerName - The layer to remove
 */
export function removeLayer(layerName) {
  const hadLayer = activeLayers.has(layerName);
  activeLayers.delete(layerName);
  const isEmpty = activeLayers.size === 0;
  diagLog('removeLayer', {
    layer: layerName,
    hadLayer,
    isEmpty,
    remainingLayers: [...activeLayers]
  });
  if (isEmpty) {
    notifyParent(false);
  }
}

/**
 * Check if any layer is currently active.
 * @returns {boolean}
 */
export function hasActiveLayers() {
  return activeLayers.size > 0;
}

/**
 * Get list of active layers (for debugging).
 * @returns {string[]}
 */
export function getActiveLayers() {
  return [...activeLayers];
}

/**
 * Check if any blocking layer is active.
 * When blocking layers are active, all clicks are captured.
 * When only pass-through layers are active, clicks on empty space forward to HA.
 */
function hasBlockingLayer() {
  for (const layer of activeLayers) {
    if (blockingLayers.has(layer)) {
      return true;
    }
  }
  return false;
}

/**
 * Selectors for interactive elements that should capture clicks.
 * Clicks on these elements (or their children) are NOT forwarded to HA.
 */
const INTERACTIVE_SELECTORS = [
  '.timer-card',                // Timer cards (expanded)
  '.timer-mini',                // Timer cards (minimized)
  '.screensaver',               // Screensaver
  '.alarm-overlay',             // Alarm overlay
  '.screensaver-thumbnail',     // Photo thumbnail
  '.screensaver-gallery',       // Photo gallery
].join(', ');

/**
 * Handle click/touch events and forward to HA if not on interactive element.
 * Uses a flag to prevent double-handling from touchend + click on mobile.
 */
let lastForwardedTime = 0;

function handleClickThrough(e) {
  // If a blocking layer is active, don't forward - it captures all clicks
  if (hasBlockingLayer()) {
    return;
  }

  // Check if click target is inside an interactive element
  const target = e.target;
  if (target.closest(INTERACTIVE_SELECTORS)) {
    // Click is on an interactive element - let it handle normally
    return;
  }

  // Debounce: prevent double-forwarding from touchend + click sequence
  const now = Date.now();
  if (now - lastForwardedTime < 300) {
    return;
  }
  lastForwardedTime = now;

  // Get coordinates (touch or mouse)
  // Use screenX/screenY for accurate cross-frame coordinate mapping
  let screenX, screenY, clientX, clientY;
  if (e.changedTouches && e.changedTouches.length > 0) {
    screenX = e.changedTouches[0].screenX;
    screenY = e.changedTouches[0].screenY;
    clientX = e.changedTouches[0].clientX;
    clientY = e.changedTouches[0].clientY;
  } else {
    screenX = e.screenX;
    screenY = e.screenY;
    clientX = e.clientX;
    clientY = e.clientY;
  }

  // Click is on empty overlay space - forward to HA dashboard
  diagLog('Forwarding click to HA', { screenX, screenY, clientX, clientY, target: target.tagName, type: e.type });

  if (window.parent && window.parent !== window) {
    window.parent.postMessage({
      source: 'dashie-overlay',
      type: 'forward-click',
      // Send both coordinate systems so parent can choose
      screenX,
      screenY,
      x: clientX,
      y: clientY
    }, '*');
  }

  // Note: We do NOT call preventDefault() or stopPropagation() here
  // because it can interfere with future touch events on interactive elements.
  // The click is forwarded to HA, and the overlay ignores it naturally since
  // the target is not interactive.
}

/**
 * Install click-through handler on document.
 * Called when pointer-events are enabled with only pass-through layers.
 */
function installClickThroughHandler() {
  if (clickThroughInstalled) return;

  document.addEventListener('click', handleClickThrough, true);
  document.addEventListener('touchend', handleClickThrough, true);
  clickThroughInstalled = true;
  diagLog('Click-through handler installed');
}

/**
 * Remove click-through handler from document.
 * Called when pointer-events are disabled.
 */
function removeClickThroughHandler() {
  if (!clickThroughInstalled) return;

  document.removeEventListener('click', handleClickThrough, true);
  document.removeEventListener('touchend', handleClickThrough, true);
  clickThroughInstalled = false;
  diagLog('Click-through handler removed');
}

/**
 * Kiosk Services — Headless Service Layer
 *
 * This runs inside an iframe injected by Android (display:none - no visual rendering).
 * All visual UI (timer cards, alarm overlay, settings modal) is handled by native Kotlin.
 *
 * Features:
 * - Timer service (localStorage-based, state forwarded to Kotlin)
 * - Voice command processing via AI service
 * - Sleep timer scheduling
 *
 * Architecture:
 * ┌─────────────────────────────────────────┐
 * │ Android WebView                          │
 * │ ├── HA Dashboard (loaded directly)       │
 * │ ├── [Kotlin native overlays]             │ ← Timer cards, alarm, settings, screensaver
 * │ └── [Headless iframe: this JS service]   │ ← Timer service, voice/AI, sleep scheduling
 * └─────────────────────────────────────────┘
 */

import { TimerService, BrowserStorage } from '@dashieapp/timer-service';
import { normalizeWordNumbers } from '@dashieapp/core-utils';
import { classifyTimerIntent, classifyMediaIntent, classifyVolumeIntent, parseDuration } from '@dashieapp/intent-classifier';
// Shared with full mode (pure module, no app imports — bundles cleanly): answers
// "what time / date / day is it" on-device instead of a billable brain round-trip.
import { answerTimeQuery } from '../../js/core/voice/time-fast-path.js';
import { DASHIE_CONFIG, isLLMEnabled } from './config.js';
// Own module, NOT brand.js: importing it from there pulled the whole BRANDS table (Dashie's
// legal URLs included) into this bundle. See ha-api-prefix.js for why it is not a brand fact.
import { getHaApiPrefix } from './ha-api-prefix.js';
import kioskSleepTimerService from './services/sleep-timer-service.js';  // Sleep/Wake timer scheduler

// NOTE: Visual rendering code removed - overlay now runs headless (display:none)
// Timer UI is rendered by native Kotlin layer
// Settings modal will be handled by native Kotlin layer

// ==================== Performance Timing ====================
// Log how long module imports took (time from HTML script to here)
const __importsComplete = performance.now();
if (window.__overlayPerfStart) {
  const importTime = __importsComplete - window.__overlayPerfStart;
  console.log(`[Overlay Perf] ⏱️ Module imports complete: ${importTime.toFixed(0)}ms`);
  window.__overlayPerfMarks.importsComplete = __importsComplete;
  window.__overlayPerfMarks.importDuration = importTime;
}

// ==================== Configuration ====================
// NOTE: Fullscreen management removed - overlay runs headless (display:none)
// Visual rendering is handled by native Kotlin layer

const config = {
  debug: false
};

// ==================== DOM Elements ====================
// NOTE: DOM element references removed - overlay runs headless (display:none)
// Visual rendering is handled by native Kotlin layer

// ==================== State ====================

let timerService = null;


// Voice command tracking (for refresh safety)
let voiceCommandInProgress = false;

// Active alarm ID (for tracking, visual handled by Kotlin)
let activeAlarmId = null;

// ==================== Memory Diagnostics ====================

/**
 * Get a snapshot of current memory usage for diagnostics.
 * This helps identify memory leaks over long runtimes.
 */
function getMemorySnapshot() {
  const snapshot = {
    timestamp: new Date().toISOString(),
    // JS Heap (Chrome/WebView only)
    jsHeapMB: null,
    jsHeapLimitMB: null,
    jsHeapPercent: null,
    // Timer count from service
    timerCount: timerService?.getAllTimers()?.length || 0,
    // Active state
    alarmActive: !!activeAlarmId
  };

  // Get JS heap info if available (Chrome/WebView)
  if (performance.memory) {
    snapshot.jsHeapMB = (performance.memory.usedJSHeapSize / 1024 / 1024).toFixed(1);
    snapshot.jsHeapLimitMB = (performance.memory.jsHeapSizeLimit / 1024 / 1024).toFixed(1);
    snapshot.jsHeapPercent = ((performance.memory.usedJSHeapSize / performance.memory.jsHeapSizeLimit) * 100).toFixed(1);
  }

  return snapshot;
}

/**
 * Log memory snapshot to console (will be captured by DiagnosticBuffer via Android)
 */
function logMemorySnapshot(reason = 'periodic') {
  const snapshot = getMemorySnapshot();
  const heapInfo = snapshot.jsHeapMB
    ? `heap=${snapshot.jsHeapMB}MB/${snapshot.jsHeapLimitMB}MB (${snapshot.jsHeapPercent}%)`
    : 'heap=N/A';

  console.log(`[MEMORY-JS] ${reason}: ${heapInfo} timers=${snapshot.timerCount}`);

  return snapshot;
}

// Periodic memory logging (every 30 minutes)
let memoryLogInterval = null;
function startMemoryLogging() {
  if (memoryLogInterval) return;

  // Log initial snapshot
  logMemorySnapshot('init');

  // Log every 30 minutes
  memoryLogInterval = setInterval(() => {
    logMemorySnapshot('periodic');
  }, 30 * 60 * 1000);

  console.log('[KioskServices] Memory logging started (30 min interval)');
}

/**
 * Check if it's safe to refresh the overlay iframe.
 * Returns true if no user interaction is in progress.
 */
function canSafelyRefresh() {
  const reasons = [];

  if (voiceCommandInProgress) {
    reasons.push('voice_command');
  }
  if (activeAlarmId) {
    reasons.push('alarm_active');
  }
  // Note: settings modal check removed - settings now handled by Kotlin

  const canRefresh = reasons.length === 0;

  if (!canRefresh) {
    console.log(`[KioskServices] canSafelyRefresh: false (${reasons.join(', ')})`);
  }

  return {
    canRefresh,
    reasons,
    snapshot: getMemorySnapshot()
  };
}

// Expose memory diagnostics globally
window.dashieOverlay = {
  getMemorySnapshot,
  logMemorySnapshot,
  canSafelyRefresh
};

// ==================== HA voice entities (window.dashieHaVoiceContext) ====================
// The native brain turn (Kotlin RealtimeHaEntitiesBridge) calls this to attach the voice-
// controllable entity set to provided_context.ha_entities — so a device command that fell through
// the fast path reaches the LLM with something to act on. In KIOSK mode the document IS the HA
// origin, so we fetch the integration's ENRICHED list same-origin — it already carries
// {entity_id, domain, friendly_name, state, area, aliases}, so we need no ha-service/entityCache
// (contract: .reference/build-plans/20260717_HA_ENTITY_EXPOSURE_CONTRACT.md). Default source =
// exposed (the kiosk has no settingsStore). ANY failure → empty, so the brain proceeds gracefully.
async function buildHaVoiceContext() {
  try {
    // Same-origin as HA → read the frontend's stored bearer token (HA API auth, not cookies).
    let token = null;
    try { token = JSON.parse(localStorage.getItem('hassTokens') || 'null')?.access_token || null; } catch (_) { /* no token */ }
    const auth = { headers: token ? { Authorization: `Bearer ${token}` } : {}, credentials: 'include' };
    const fetchEntities = async (path) => {
      const res = await fetch(path, auth);
      if (!res.ok) { console.warn('[KioskServices] dashieHaVoiceContext:', path, res.status); return null; }
      const data = await res.json();
      return Array.isArray(data?.entities) ? data.entities : [];
    };
    // Honor the account's entity source (dashboard | assist), matching the webapp's getVoiceEntityIds.
    // The kiosk has no settingsStore, so read it from the native bridge (synced from the account
    // voice-config); absent / old APK → 'assist' (exposed), the safe historical default.
    let source = 'assist';
    try { source = window.DashieNative?.getVoiceEntitySource?.() || 'assist'; } catch (_) { /* default */ }
    // 🔴 The prefix is EDITION-specific (#63) and this bundle is shared by both editions, so it
    // must be asked, never hardcoded: a Chickadee box serves /api/chickadee/… and 404s
    // /api/dashie/…, which lands here as an empty entity list — the brain then gets no HA
    // context and every device command falls through with nothing to act on, visible only as a
    // console.warn in a headless iframe nobody is watching.
    const api = getHaApiPrefix();
    let entities = null;
    if (source === 'dashboard') {
      const deviceId = localStorage.getItem('dashie-device-id') || '';
      entities = await fetchEntities(api + '/dashboard_entities' + (deviceId ? ('?device_id=' + encodeURIComponent(deviceId)) : ''));
    }
    // Fallback floor (mirrors the webapp): 'dashboard' empty/unknown → exposed; 'assist' → exposed.
    if (!entities || entities.length === 0) entities = await fetchEntities(api + '/exposed_entities');
    entities = entities || [];
    console.log(`[KioskServices] 🏠 ha voice context (${source}): ${entities.length} entit${entities.length === 1 ? 'y' : 'ies'}`);
    return { ha_entities: entities, device_area: null };
  } catch (e) {
    console.warn('[KioskServices] dashieHaVoiceContext failed:', e?.message || e);
    return { ha_entities: [], device_area: null, error: String(e?.message || e) };
  }
}
window.dashieHaVoiceContext = { build: buildHaVoiceContext };

// ==================== Main Controller ====================

// ==================== Diagnostic Logging ====================
// NOTE: Visual diagnostic logging removed - overlay runs headless (display:none)

function diagLog(msg, data) {
  const timestamp = new Date().toISOString().substring(11, 23);
  console.log(`[Overlay:DIAG] [${timestamp}] ${msg}`, data || '');
}

class KioskServicesController {
  async initialize() {
    console.log('[KioskServices] Initializing (headless mode)...');
    diagLog('initialize() started');

    // Performance timing helper
    const perfMark = (name) => {
      const now = performance.now();
      const fromStart = window.__overlayPerfStart ? (now - window.__overlayPerfStart).toFixed(0) : '?';
      console.log(`[Overlay Perf] ⏱️ ${name}: ${fromStart}ms from start`);
      if (window.__overlayPerfMarks) window.__overlayPerfMarks[name] = now;
    };

    perfMark('initialize-start');

    // NOTE: Visual initialization removed - overlay runs headless (display:none)
    // Timer UI, settings modal, and themes are handled by native Kotlin layer

    // Initialize timer service with localStorage (same as webapp)
    timerService = new TimerService({
      storage: new BrowserStorage(),
      maxTimers: 3,
      deviceId: this.getDeviceId()
    });

    // Expose timer service to window for Kotlin bridge access
    window.dashieTimerService = timerService;
    console.log('[Dashie] ⏱️ Timer service exposed to window', window.dashieTimerService);
    perfMark('timer-service');

    // Subscribe to timer events (forwards to Kotlin)
    this.setupTimerEventListeners();
    this.setupEventListeners();
    perfMark('event-listeners');

    // Forward existing timers to Kotlin
    this.forwardExistingTimers();
    perfMark('forward-timers');

    // Initialize sleep timer service (schedule-based and inactivity-based sleep)
    this.initializeSleepTimerService();
    perfMark('sleep-timer');


    // Setup Android remote input handler (for D-pad navigation)
    this.setupAndroidRemoteInput();
    perfMark('android-remote');

    // Notify parent window (Android WebView) that overlay is ready
    this.notifyParentReady();
    perfMark('ready-sent');

    // Log final summary
    if (window.__overlayPerfStart) {
      const totalTime = performance.now() - window.__overlayPerfStart;
      console.log(`[Overlay Perf] ✅ TOTAL TIME: ${totalTime.toFixed(0)}ms`);
      console.log('[Overlay Perf] Breakdown:', window.__overlayPerfMarks);
    }

    // Start memory diagnostics logging
    startMemoryLogging();

    console.log('[KioskServices] Initialized (headless mode - timer/voice services only)');
    console.log('[KioskServices] LLM Config:', {
      enabled: isLLMEnabled(),
      provider: DASHIE_CONFIG.llm.provider,
      model: DASHIE_CONFIG.llm.localModel
    });
  }

  /**
   * Notify the parent window that the overlay is ready
   */
  notifyParentReady() {
    diagLog('notifyParentReady() called (headless mode)');

    // Post message to parent (Android injects JS that listens for this)
    if (window.parent !== window) {
      diagLog('Sending ready signal to parent window');
      window.parent.postMessage({
        source: 'dashie-overlay',
        type: 'ready'
      }, '*');
    } else {
      diagLog('WARNING: window.parent === window (not in iframe?)');
    }

    // Also expose directly to Android bridge
    if (window.DashieBridge?.onOverlayReady) {
      diagLog('Calling DashieBridge.onOverlayReady()');
      window.DashieBridge.onOverlayReady();
    }
  }

  // NOTE: Theme initialization removed - overlay runs headless (display:none)
  // Theme/styling is handled by native Kotlin layer

  /**
   * Get a unique device ID for this device
   */
  getDeviceId() {
    // Check Android bridge first
    if (window.DashieBridge?.getDeviceId) {
      return window.DashieBridge.getDeviceId();
    }

    // Use stored ID or generate one
    let deviceId = localStorage.getItem('dashie-device-id');
    if (!deviceId) {
      deviceId = `device-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
      localStorage.setItem('dashie-device-id', deviceId);
    }
    return deviceId;
  }

  // NOTE: Viewport scaling removed - overlay runs headless (display:none)
  // Visual scaling is handled by native Kotlin layer

  setupTimerEventListeners() {
    timerService.on('TIMER_CREATED', ({ timer }) => {
      console.log('[KioskServices] ⏱️ TIMER_CREATED event received:', timer.id);
      console.log('[KioskServices] ⏱️ Timer data:', JSON.stringify(timer));

      // Call DashieNative directly if available (stand-alone mode)
      if (typeof DashieNative !== 'undefined' && DashieNative.onTimerCreated) {
        console.log('[KioskServices] ⏱️ Calling DashieNative.onTimerCreated directly');
        try {
          DashieNative.onTimerCreated(JSON.stringify(timer));
          console.log('[KioskServices] ⏱️ DashieNative.onTimerCreated called successfully');
        } catch (e) {
          console.error('[KioskServices] ❌ Error calling DashieNative.onTimerCreated:', e);
        }
      } else {
        console.log('[KioskServices] ⚠️ DashieNative not available, using postMessage fallback');
      }

      // Forward to parent window (Kotlin overlay) - addon mode fallback
      if (window.parent !== window) {
        console.log('[KioskServices] ⏱️ Sending timer-created postMessage to parent');
        window.parent.postMessage({
          source: 'dashie-overlay',
          type: 'timer-created',
          timer: timer
        }, '*');
        console.log('[KioskServices] ⏱️ postMessage sent successfully');
      }

      // HTML rendering disabled - using native Kotlin overlay instead
      // this.addTimerCard(timer);
    });

    timerService.on('TIMER_UPDATED', ({ timer, action }) => {
      console.log('[KioskServices] Timer updated:', timer.id, action);

      // Call DashieNative directly if available (stand-alone mode)
      if (typeof DashieNative !== 'undefined' && DashieNative.onTimerUpdated) {
        try {
          DashieNative.onTimerUpdated(JSON.stringify(timer));
        } catch (e) {
          console.error('[KioskServices] ❌ Error calling DashieNative.onTimerUpdated:', e);
        }
      }

      // Forward to parent window (Kotlin overlay) - addon mode fallback
      if (window.parent !== window) {
        window.parent.postMessage({
          source: 'dashie-overlay',
          type: 'timer-updated',
          timer: timer
        }, '*');
      }

      // HTML rendering disabled - using native Kotlin overlay instead
      // this.updateTimerCard(timer, action);
    });

    timerService.on('TIMER_COMPLETED', ({ timer }) => {
      console.log('[KioskServices] Timer completed:', timer.id);

      // Call DashieNative directly if available (stand-alone mode)
      if (typeof DashieNative !== 'undefined' && DashieNative.onTimerCompleted) {
        try {
          DashieNative.onTimerCompleted(JSON.stringify(timer));
        } catch (e) {
          console.error('[KioskServices] ❌ Error calling DashieNative.onTimerCompleted:', e);
        }
      }

      // Forward to parent window (Kotlin overlay) - addon mode fallback
      if (window.parent !== window) {
        window.parent.postMessage({
          source: 'dashie-overlay',
          type: 'timer-completed',
          timer: timer
        }, '*');
      }

      // HTML rendering disabled - using native Kotlin overlay instead
      // this.handleTimerCompleted(timer);
    });

    timerService.on('TIMER_CANCELLED', ({ timer }) => {
      console.log('[KioskServices] Timer cancelled:', timer.id);

      // Call DashieNative directly if available (stand-alone mode)
      if (typeof DashieNative !== 'undefined' && DashieNative.onTimerCancelled) {
        try {
          DashieNative.onTimerCancelled(timer.id);
        } catch (e) {
          console.error('[KioskServices] ❌ Error calling DashieNative.onTimerCancelled:', e);
        }
      }

      // Forward to parent window (Kotlin overlay) - addon mode fallback
      if (window.parent !== window) {
        window.parent.postMessage({
          source: 'dashie-overlay',
          type: 'timer-cancelled',
          timerId: timer.id
        }, '*');
      }

      // HTML rendering disabled - using native Kotlin overlay instead
      // this.removeTimerCard(timer.id);
    });

    timerService.on('TIMERS_TICK', ({ timers }) => {
      // Forward tick updates to Kotlin so timers count down
      if (typeof DashieNative !== 'undefined' && DashieNative.onTimerUpdated) {
        timers.forEach(timer => {
          try {
            DashieNative.onTimerUpdated(JSON.stringify(timer));
          } catch (e) {
            console.error('[KioskServices] ❌ Error calling DashieNative.onTimerUpdated in tick:', e);
          }
        });
      }

      // HTML rendering disabled - using native Kotlin overlay instead
      // this.updateTimerDisplays(timers);
    });
  }

  setupEventListeners() {
    // NOTE: Alarm overlay DOM listeners removed - overlay runs headless (display:none)
    // Alarm UI is handled by native Kotlin layer

    // Listen for messages from parent window (Android WebView)
    window.addEventListener('message', (event) => {
      this.handleParentMessage(event);
    });
  }

  /**
   * Setup Android remote input handler.
   * Android WebView calls window.handleRemoteInput(keyCode) for D-pad/remote keys.
   * This dispatches native keyboard events so existing handlers can process them.
   * NOTE: Settings modal is now handled by native Kotlin layer.
   */
  setupAndroidRemoteInput() {
    // Android keycode to keyboard event mapping
    const androidKeyMap = {
      // D-pad navigation (Android KeyEvent values)
      19: { key: 'ArrowUp', code: 'ArrowUp' },      // KEYCODE_DPAD_UP
      20: { key: 'ArrowDown', code: 'ArrowDown' },  // KEYCODE_DPAD_DOWN
      21: { key: 'ArrowLeft', code: 'ArrowLeft' },  // KEYCODE_DPAD_LEFT
      22: { key: 'ArrowRight', code: 'ArrowRight' }, // KEYCODE_DPAD_RIGHT
      23: { key: 'Enter', code: 'Enter' },          // KEYCODE_DPAD_CENTER
      66: { key: 'Enter', code: 'Enter' },          // KEYCODE_ENTER

      // Back/Escape
      4: { key: 'Escape', code: 'Escape' },         // KEYCODE_BACK
      111: { key: 'Escape', code: 'Escape' },       // KEYCODE_ESCAPE

      // Menu
      82: { key: 'ContextMenu', code: 'ContextMenu' }, // KEYCODE_MENU
    };

    window.handleRemoteInput = (keyCode) => {
      console.log('[KioskServices] Android remote input:', keyCode);

      const mapping = androidKeyMap[keyCode];
      if (!mapping) {
        console.log('[KioskServices] Unmapped Android keycode:', keyCode);
        return;
      }

      // Create and dispatch a synthetic keyboard event
      const event = new KeyboardEvent('keydown', {
        key: mapping.key,
        code: mapping.code,
        bubbles: true,
        cancelable: true
      });

      // Dispatch the event
      const handled = !document.dispatchEvent(event);

      console.log('[KioskServices] Key dispatched:', mapping.key, 'handled:', handled);

      // For BACK/ESCAPE: let Android handle it (Kotlin sidebar/settings)
      // NOTE: Settings modal now handled by native Kotlin layer
      if ((keyCode === 4 || keyCode === 111) && !handled) {
        console.log('[KioskServices] Back not handled - letting Android handle (Kotlin)');
      }
    };

    console.log('[KioskServices] Android remote input handler initialized');
  }

  /**
   * Handle messages from parent window (Android WebView)
   */
  handleParentMessage(event) {
    const data = event.data;
    if (!data || typeof data !== 'object') return;

    // Handle remote input forwarded from parent (Android key events)
    if (data.source === 'dashie-parent' && data.type === 'remote-input') {
      console.log('[KioskServices] 🎮 Received remote-input via postMessage:', data.keyCode);
      if (window.handleRemoteInput) {
        console.log('[KioskServices] 🎮 Calling handleRemoteInput with keyCode:', data.keyCode);
        window.handleRemoteInput(data.keyCode);
      } else {
        console.warn('[KioskServices] 🎮 handleRemoteInput not defined!');
      }
      return;
    }

    // Handle timer control commands from parent (Kotlin timer card buttons + voice pipeline)
    if (data.source === 'dashie-parent' && data.type === 'timer-command') {
      const { command, timerId, timerSlot, timerName } = data;
      console.log(`[KioskServices] ⏱️ Timer command received: ${command} for ${timerId || timerName || (timerSlot ? 'slot ' + timerSlot : '(any)')}`);

      if (!timerService) {
        console.error('[KioskServices] ⏱️ Timer service not available');
        return;
      }

      // Find target timer by: exact ID > slot number > name match > first matching state
      const findTimer = (stateFilter) => {
        if (timerId) return timerService.getAllTimers().find(t => t.id === timerId);
        const timers = timerService.getAllTimers();
        if (timerSlot) return timers.find(t => t.slot === timerSlot);
        if (timerName) {
          const lower = timerName.toLowerCase();
          return timers.find(t =>
            (t.description && t.description.toLowerCase().includes(lower)) ||
            t.label.toLowerCase().includes(lower)
          );
        }
        if (stateFilter) return timers.find(t => t.state === stateFilter);
        return timers[0];
      };

      try {
        switch (command) {
          case 'create': {
            // Voice-initiated timer creation from HA pipeline (Kotlin)
            const seconds = data.durationSeconds || 0;
            const description = data.description || undefined;
            if (seconds > 0) {
              const result = timerService.createTimer(seconds, description);
              console.log(`[KioskServices] ⏱️ Timer created via voice: ${seconds}s`, result);
            }
            break;
          }
          case 'minimize': {
            const t = findTimer(); if (t) timerService.minimizeTimer(t.id);
            break;
          }
          case 'expand': {
            const t = findTimer(); if (t) timerService.expandTimer(t.id);
            break;
          }
          case 'pause': {
            const t = findTimer('running');
            if (t) timerService.pauseTimer(t.id);
            break;
          }
          case 'resume': {
            const t = findTimer('paused');
            if (t) timerService.resumeTimer(t.id);
            break;
          }
          case 'cancel': {
            const t = findTimer();
            if (t) timerService.cancelTimer(t.id);
            break;
          }
          case 'add_time': {
            const addSeconds = data.addSeconds || 0;
            if (addSeconds > 0) {
              const t = findTimer();
              if (t) {
                timerService.addTime(t.id, addSeconds);
                console.log(`[KioskServices] ⏱️ Added ${addSeconds}s to timer ${t.id}`);
              }
            }
            break;
          }
          case 'subtract_time': {
            const subtractSeconds = data.subtractSeconds || 0;
            if (subtractSeconds > 0) {
              const t = findTimer();
              if (t) {
                timerService.addTime(t.id, -subtractSeconds);
                console.log(`[KioskServices] ⏱️ Subtracted ${subtractSeconds}s from timer ${t.id}`);
              }
            }
            break;
          }
          default:
            console.warn(`[KioskServices] ⏱️ Unknown timer command: ${command}`);
        }
      } catch (error) {
        console.error(`[KioskServices] ⏱️ Error executing timer command ${command}:`, error);
      }
      return;
    }

    // Handle screensaver proxy calls from parent (no longer used - native Kotlin screensaver)
    if (data.source === 'dashie-parent' && data.type === 'screensaver-call') {
      const { method } = data;
      console.log('[KioskServices] Screensaver call (ignored - using native Kotlin):', method);
      return;
    }

    // Handle dashie proxy calls from parent
    if (data.source === 'dashie-parent' && data.type === 'dashie-call') {
      const { method, args, callbackId } = data;
      console.log('[KioskServices] Dashie call:', method, args);

      if (method === 'processVoiceCommand') {
        this.processVoiceCommand(args[0]).then(result => {
          // Send response via postMessage (caught by parent's voice response listener)
          // The listener in VoiceOverlayBridge.injectVoiceResponseListener() forwards to DashieNative
          event.source?.postMessage({
            source: 'dashie-overlay',
            type: 'voice-response',
            requestId: callbackId,
            result
          }, '*');
        }).catch(error => {
          console.error('[KioskServices] Voice command error:', error);
          // Send error via postMessage
          event.source?.postMessage({
            source: 'dashie-overlay',
            type: 'voice-error',
            requestId: callbackId,
            error: error.message || 'Unknown error'
          }, '*');
        });
      } else if (method === 'buildHaVoiceContext') {
        // Native RealtimeHaEntitiesBridge relays here (it evaluates the MAIN frame, but
        // window.dashieHaVoiceContext lives in THIS overlay iframe). Run it and post the result
        // back on a distinct type so it doesn't collide with the voice-response listener.
        buildHaVoiceContext().then(result => {
          event.source?.postMessage({
            source: 'dashie-overlay', type: 'ha-voice-context-response', requestId: callbackId, result
          }, '*');
        }).catch(error => {
          event.source?.postMessage({
            source: 'dashie-overlay', type: 'ha-voice-context-response', requestId: callbackId,
            result: { ha_entities: [], device_area: null, error: error?.message || String(error) }
          }, '*');
        });
      } else if (method === 'createTimer') {
        this.createTimer(args[0], args[1]);
      } else if (method === 'cancelTimer') {
        this.cancelTimer(args[0]);
      } else if (method === 'getTimers') {
        // Can't easily return sync, but the proxy returns []
      } else if (method === 'openSettings') {
        // NOTE: Settings modal now handled by native Kotlin layer
        console.log('[KioskServices] openSettings call - forwarding to Kotlin');
        if (window.DashieNative?.openSettings) {
          window.DashieNative.openSettings(args[0] || '');
        }
      } else if (method === 'closeSettings') {
        // NOTE: Settings modal now handled by native Kotlin layer
        console.log('[KioskServices] closeSettings call - forwarding to Kotlin');
        if (window.DashieNative?.closeSettings) {
          window.DashieNative.closeSettings();
        }
      } else if (method === 'onNativeScreensaverDeactivated') {
        // Forward to sleep timer service for resleep handling
        console.log('[KioskServices] Native screensaver deactivated - notifying sleep timer');
        kioskSleepTimerService.onNativeScreensaverDeactivated();
      } else {
        // A parent/Kotlin→overlay RPC with no dispatch branch: the caller's callbackId
        // promise just times out with no clue why. Be loud (contract #35 gap a lives here).
        console.warn('[KioskServices] DROP: unhandled dashie-call method (no dispatch):', method, { callbackId });
      }
      return;
    }

    // Legacy message format (backwards compatibility)
    console.log('[KioskServices] Message from parent:', data.type);

    switch (data.type) {
      case 'create-timer':
        this.createTimer(data.durationSeconds, data.description);
        break;

      case 'cancel-timer':
        this.cancelTimer(data.timerId);
        break;

      case 'process-voice':
        this.processVoiceCommand(data.transcript).then(result => {
          // Send response back to parent
          event.source?.postMessage({
            source: 'dashie-overlay',
            type: 'voice-response',
            requestId: data.requestId,
            result
          }, '*');
        });
        break;

      default:
        console.warn('[KioskServices] DROP: unhandled parent message type:', data.type);
    }
  }

  // ==================== Timer Forwarding ====================
  // NOTE: HTML rendering removed - overlay runs headless (display:none)
  // Timer UI is rendered by native Kotlin layer

  /**
   * Forward existing timers to Kotlin on startup
   */
  forwardExistingTimers() {
    const timers = timerService.getAllTimers();
    console.log('[KioskServices] Forwarding', timers.length, 'existing timers to Kotlin');

    for (const timer of timers) {
      // Call DashieNative directly if available
      if (typeof DashieNative !== 'undefined' && DashieNative.onTimerCreated) {
        try {
          DashieNative.onTimerCreated(JSON.stringify(timer));
        } catch (e) {
          console.error('[KioskServices] Error forwarding timer to DashieNative:', e);
        }
      }

      // Also forward via postMessage (fallback)
      if (window.parent !== window) {
        window.parent.postMessage({
          source: 'dashie-overlay',
          type: 'timer-created',
          timer: timer
        }, '*');
      }
    }
  }

  // ==================== Timer Actions (local via TimerService) ====================

  handlePauseResume(timerId) {
    const timer = timerService.getTimer(timerId);
    if (!timer) return;

    if (timer.state === 'running') {
      timerService.pauseTimer(timerId);
    } else if (timer.state === 'paused') {
      timerService.resumeTimer(timerId);
    }
  }

  handleCancel(timerId) {
    if (activeAlarmId === timerId) {
      this.dismissAlarm();
    }
    timerService.cancelTimer(timerId);
  }

  handleMinimize(timerId) {
    timerService.minimizeTimer(timerId);
  }

  handleExpand(timerId) {
    timerService.expandTimer(timerId);
  }

  // ==================== Alarm Handling ====================
  // NOTE: Alarm UI removed - overlay runs headless (display:none)
  // Alarm visual is handled by native Kotlin layer
  // We still track alarm ID for dismissAlarm logic

  /**
   * Called when Kotlin dismisses an alarm (user tapped dismiss in Kotlin UI)
   */
  dismissAlarm() {
    if (!activeAlarmId) return;

    console.log('[KioskServices] Dismissing alarm:', activeAlarmId);

    const timerId = activeAlarmId;
    activeAlarmId = null;

    // Cancel the completed timer
    timerService.cancelTimer(timerId);
  }

  /**
   * Track alarm ID when timer completes (for dismissAlarm logic)
   */
  setActiveAlarm(timerId) {
    activeAlarmId = timerId;
    console.log('[KioskServices] Active alarm set:', timerId);
  }

  // ==================== Public API ====================

  createTimer(durationSeconds, description = null) {
    return timerService.createTimer(durationSeconds, description);
  }

  cancelTimer(timerId) {
    if (!timerId) {
      const timers = timerService.getAllTimers();
      if (timers.length > 0) {
        return timerService.cancelTimer(timers[0].id);
      }
      return false;
    }
    return timerService.cancelTimer(timerId);
  }

  getTimers() {
    return timerService.getAllTimers();
  }

  getTimerService() {
    return timerService;
  }

  // ==================== Voice Processing ====================

  /**
   * Initialize the sleep timer service for schedule-based and inactivity-based sleep.
   * Handles automatic screen off based on configured sleep settings.
   */
  initializeSleepTimerService() {
    try {
      kioskSleepTimerService.initialize();
      console.log('[KioskServices] Sleep Timer Service initialized');
    } catch (error) {
      console.error('[KioskServices] Failed to initialize Sleep Timer Service:', error);
    }
  }


  async processVoiceCommand(transcript) {
    console.log('[KioskServices] Processing voice command:', transcript);
    voiceCommandInProgress = true;

    try {
      // On-device time/date/day answer FIRST (full-mode parity — voice-command-router
      // runs the same fast-path before any classifier). Timezone comes from the WebView's
      // OS zone; if that latched to UTC (the cold-start gotcha full mode dodges via the
      // zip-derived zone) fall through to the brain rather than speak a wrong time.
      const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
      const timeAnswer = (tz && tz !== 'UTC') ? answerTimeQuery(transcript, tz) : null;
      if (timeAnswer) {
        console.log('[KioskServices] Handled locally as time/date query');
        return { success: true, voice: timeAnswer, text: timeAnswer, action: null, localHandler: 'time' };
      }

      // First, check for local timer commands (fast path - no LLM needed)
      const timerResult = this.handleLocalTimerCommand(transcript);
      if (timerResult) {
        console.log('[KioskServices] Handled locally as timer command');
        return {
          success: true,
          voice: timerResult.voice,
          text: timerResult.text || timerResult.voice,
          action: timerResult.action || null,
          localHandler: 'timer'
        };
      }

      // Check for local music commands (fast path - no LLM needed)
      const musicResult = this.handleLocalMusicCommand(transcript);
      if (musicResult) {
        console.log('[KioskServices] Handled locally as music command');
        return {
          success: true,
          voice: musicResult.voice,
          text: musicResult.text || musicResult.voice,
          action: musicResult.action || null,
          localHandler: 'music'
        };
      }

      // Check for local volume commands (fast path - no LLM needed)
      const volumeResult = this.handleLocalVolumeCommand(transcript);
      if (volumeResult) {
        console.log('[KioskServices] Handled locally as volume command');
        return {
          success: true,
          voice: volumeResult.voice,
          text: volumeResult.text || volumeResult.voice,
          action: volumeResult.action || null,
          localHandler: 'volume'
        };
      }

      // ── AI lane → native handler ───────────────────────────────────────────
      // Not a local command. Hand off to native Kotlin, which decides by voice
      // control method: in cloud mode it calls the integration's
      // <ApiPaths.HA>/voice/converse (HA token) → brain → speaks the result;
      // otherwise it speaks a graceful "needs cloud" note. The legacy
      // local AI path below is retired (kiosk no longer orchestrates — WS5).
      console.log('[KioskServices] AI lane → native handler');
      return { success: true, ai_lane: true };

    } catch (error) {
      console.error('[KioskServices] Voice command error:', error);
      return {
        success: false,
        error: error.message,
        voice: "I'm sorry, I had trouble processing that. Please try again."
      };
    } finally {
      voiceCommandInProgress = false;
    }
  }

  handleLocalTimerCommand(transcript) {
    // Use shared normalizeWordNumbers to convert "five" -> "5", "twenty five" -> "25", etc.
    const normalized = normalizeWordNumbers(transcript).toLowerCase().trim();

    // Use shared intent classifier for timer pattern matching
    const result = classifyTimerIntent(normalized, { alarmPlaying: !!activeAlarmId });
    if (!result) return null;

    return this._executeTimerIntent(result);
  }

  /**
   * Execute a classified timer intent via the timer service.
   * Maps shared classifier results to timerService calls.
   */
  _executeTimerIntent(result) {
    const { command, params } = result.action;

    switch (command) {
      case 'start_timer': {
        const hours = params.duration_hours || 0;
        const minutes = params.duration_minutes || 0;
        const seconds = params.duration_seconds || 0;
        const totalSeconds = hours * 3600 + minutes * 60 + seconds;
        if (totalSeconds <= 0) return null;

        console.log('[KioskServices] ⏱️ Creating timer:', totalSeconds, 'seconds');
        const createResult = timerService.createTimer(totalSeconds, params.description);
        if (createResult.success) {
          const durationStr = this.formatDuration(totalSeconds);
          const desc = params.description ? ` for ${params.description}` : '';
          return {
            voice: `I've set a ${durationStr} timer${desc}.`,
            action: { category: 'timer', command: 'create', parameters: { seconds: totalSeconds, description: params.description } }
          };
        }
        return { voice: createResult.error || "I couldn't create that timer." };
      }

      case 'pause_timer': {
        const running = timerService.getAllTimers().filter(t => t.state === 'running');
        if (running.length > 0) {
          timerService.pauseTimer(running[0].id);
          return { voice: 'Timer paused.' };
        }
        return { voice: "There's no running timer to pause." };
      }

      case 'resume_timer': {
        const paused = timerService.getAllTimers().filter(t => t.state === 'paused');
        if (paused.length > 0) {
          timerService.resumeTimer(paused[0].id);
          return { voice: 'Timer resumed.' };
        }
        return { voice: "There's no paused timer to resume." };
      }

      case 'cancel_timer': {
        const timers = timerService.getAllTimers();
        if (timers.length > 0) {
          timerService.cancelTimer(timers[0].id);
          return { voice: 'Timer cancelled.' };
        }
        return { voice: "There's no timer to cancel." };
      }

      case 'query_time': {
        const timers = timerService.getAllTimers();
        if (timers.length > 0) {
          const remaining = this.formatDuration(timers[0].remainingSeconds);
          return { voice: `${remaining} remaining on your timer.` };
        }
        return { voice: "You don't have any active timers." };
      }

      case 'add_time': {
        const addSeconds = (params.hours || 0) * 3600 + (params.minutes || 0) * 60 + (params.seconds || 0);
        const timers = timerService.getAllTimers();
        if (timers.length > 0 && addSeconds > 0) {
          timerService.addTime(timers[0].id, addSeconds);
          return { voice: `Added ${this.formatDuration(addSeconds)} to your timer.` };
        }
        return { voice: "There's no timer to add time to." };
      }

      case 'subtract_time': {
        const subSeconds = (params.hours || 0) * 3600 + (params.minutes || 0) * 60 + (params.seconds || 0);
        const timers = timerService.getAllTimers();
        if (timers.length > 0 && subSeconds > 0) {
          timerService.subtractTime(timers[0].id, subSeconds);
          return { voice: `Removed ${this.formatDuration(subSeconds)} from your timer.` };
        }
        return { voice: "There's no timer to remove time from." };
      }

      case 'stop_alarm': {
        if (activeAlarmId) {
          this.dismissAlarm();
          return { voice: 'Alarm dismissed.' };
        }
        return null;
      }

      default:
        return null;
    }
  }

  /**
   * Handle local music commands without LLM processing.
   * Uses shared intent classifier for pattern matching.
   * Supports flexible matching (short phrases like "next") when music is playing.
   */
  handleLocalMusicCommand(transcript) {
    // Strip terminal punctuation — STT emits "Play songs by Chris Stapleton." and the
    // trailing period otherwise rides into the captured query ("chris stapleton." → a
    // doubled-period voice line and a dirtier MA search).
    const lower = transcript.toLowerCase().trim().replace(/[.!?]+$/, '');

    // Check if DashieNative is available
    const hasKotlinBridge = typeof DashieNative !== 'undefined' && DashieNative.sendMusicCommand;
    if (!hasKotlinBridge) return null;

    // Check if music is currently playing (for flexible matching)
    const musicPlaying = typeof DashieNative !== 'undefined' &&
                         DashieNative.isMusicPlaying &&
                         DashieNative.isMusicPlaying();

    // Use shared classifier for media intent matching
    const result = classifyMediaIntent(lower, { musicPlaying });
    if (!result) return null;

    return this._executeMediaIntent(result, lower);
  }

  /**
   * Execute a classified media intent via Kotlin bridge.
   */
  _executeMediaIntent(result, transcript) {
    const { command, params } = result.action;

    switch (command) {
      case 'pause':
        DashieNative.sendMusicCommand('pause', '{}');
        return { voice: 'Music paused.', action: { category: 'music', command: 'pause' } };

      case 'play':
        DashieNative.sendMusicCommand('play', '{}');
        return { voice: 'Resuming music.', action: { category: 'music', command: 'play' } };

      case 'next_track':
        DashieNative.sendMusicCommand('next', '{}');
        return { voice: 'Skipping to next track.', action: { category: 'music', command: 'next' } };

      case 'previous_track':
        DashieNative.sendMusicCommand('previous', '{}');
        return { voice: 'Going back to previous track.', action: { category: 'music', command: 'previous' } };

      case 'play_search': {
        const query = params.query || '';
        // Check for "by artist" pattern in transcript for richer search. "play" is optional so
        // the implicit forms ("songs by chris stapleton", no verb) get the artist treatment too.
        const byArtistMatch = transcript.match(/(?:play\s+)?(?:me\s+)?(?:some\s+)?(?:music\s+by|songs?\s+by|something\s+by|tracks?\s+by)\s+(.+)/i);
        const songByArtistMatch = !byArtistMatch && transcript.match(/play\s+(.+?)\s+by\s+(.+)/i);

        if (byArtistMatch) {
          const artist = byArtistMatch[1].trim();
          // mediaType tells the native resolver this is an ARTIST request. Without it,
          // "songs by the beatles" arrives as the bare name "the beatles", the "by" that
          // signals artist intent is already stripped, and the search falls to tracks-first —
          // playing one track with radio continuation instead of the artist (fixed 2026-07-18).
          // The Kotlin classifier has always sent this; only the JS side was missing it.
          DashieNative.sendMusicCommand('play_media', JSON.stringify({ mediaId: artist, mediaType: 'artist' }));
          return { voice: `Playing music by ${artist}.`, action: { category: 'music', command: 'play_search', parameters: { query: artist, artist } } };
        } else if (songByArtistMatch) {
          const song = songByArtistMatch[1].trim();
          const artist = songByArtistMatch[2].trim();
          DashieNative.sendMusicCommand('play_media', JSON.stringify({ mediaId: song, artist }));
          return { voice: `Playing ${song} by ${artist}.`, action: { category: 'music', command: 'play_search', parameters: { query: song, artist } } };
        } else {
          DashieNative.sendMusicCommand('play_media', JSON.stringify({ mediaId: query }));
          return { voice: `Playing ${query}.`, action: { category: 'music', command: 'play_search', parameters: { query } } };
        }
      }

      case 'volume_up':
      case 'volume_down':
        // Music volume commands handled by volume handler, not here
        return null;

      default:
        return null;
    }
  }

  /**
   * Handle local volume commands without LLM processing.
   * Uses shared intent classifier, routes to Android system volume.
   */
  handleLocalVolumeCommand(transcript) {
    const lower = transcript.toLowerCase().trim();

    const hasNativeBridge = typeof DashieNative !== 'undefined';
    if (!hasNativeBridge) return null;

    const result = classifyVolumeIntent(lower);
    if (!result) return null;

    const { command, params } = result.action;

    switch (command) {
      case 'volume_up': {
        const newLevel = DashieNative.volumeUp ? DashieNative.volumeUp(1) : null;
        const levelStr = newLevel != null ? ` Volume is now ${newLevel}.` : '';
        return { voice: `Turning it up.${levelStr}`, action: { category: 'volume', command: 'volume_up' } };
      }

      case 'volume_down': {
        const newLevel = DashieNative.volumeDown ? DashieNative.volumeDown(1) : null;
        const levelStr = newLevel != null ? ` Volume is now ${newLevel}.` : '';
        return { voice: `Turning it down.${levelStr}`, action: { category: 'volume', command: 'volume_down' } };
      }

      case 'volume_set': {
        const level = params.level;
        if (DashieNative.setVolume) DashieNative.setVolume(level);
        return { voice: `Volume set to ${level}.`, action: { category: 'volume', command: 'volume_set', parameters: { level } } };
      }

      default:
        return null;
    }
  }

  // NOTE: Performance overlay removed - handled by native Kotlin layer

  // ==================== Utilities ====================

  formatDuration(seconds) {
    if (!seconds) return '';
    if (seconds >= 3600) {
      const hours = Math.floor(seconds / 3600);
      const mins = Math.floor((seconds % 3600) / 60);
      return mins > 0 ? `${hours}h ${mins}m` : `${hours}h`;
    }
    if (seconds >= 60) {
      const mins = Math.floor(seconds / 60);
      const secs = seconds % 60;
      return secs > 0 ? `${mins}m ${secs}s` : `${mins}m`;
    }
    return `${seconds}s`;
  }
}

// ==================== Initialize ====================

const controller = new KioskServicesController();

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => controller.initialize());
} else {
  controller.initialize();
}

// Expose for debugging and Android bridge access
window.dashieLiteController = controller;

/**
 * Get the current Home Assistant dashboard URL.
 * Returns the full URL from the main WebView (not the overlay iframe).
 * @returns {string} Full URL (e.g., "http://homeassistant.local:8123/lovelace/living-room")
 *                   or just the path if full URL unavailable
 */
function getCurrentDashboardUrl() {
  // First, try the Android bridge - this gets the actual WebView URL
  try {
    if (window.DashieBridge?.getCurrentUrl) {
      const url = window.DashieBridge.getCurrentUrl();
      if (url) return url;
    }
  } catch (e) {
    console.warn('[Dashie] Failed to get URL from DashieBridge:', e);
  }

  // Fallback: try to access parent window location (cross-origin may block this)
  try {
    if (window.parent !== window && window.parent.location) {
      return window.parent.location.href;
    }
  } catch (e) {
    // Cross-origin - can't access parent
  }

  return '';
}

// Expose voice processing and services for Android bridge to call directly
window.dashie = {
  processVoiceCommand: (transcript) => controller.processVoiceCommand(transcript),
  getTimers: () => controller.getTimers(),
  createTimer: (seconds, description) => controller.createTimer(seconds, description),
  cancelTimer: (id) => controller.cancelTimer(id),
  isReady: () => true,
  // Settings now handled by native Kotlin layer
  openSettings: (page) => {
    console.log('[Overlay] openSettings - forwarding to Kotlin');
    if (window.DashieNative?.openSettings) {
      window.DashieNative.openSettings(page || '');
    }
  },
  closeSettings: () => {
    console.log('[Overlay] closeSettings - forwarding to Kotlin');
    if (window.DashieNative?.closeSettings) {
      window.DashieNative.closeSettings();
    }
  },
  // Alarm handling (called by Kotlin when alarm is dismissed)
  dismissAlarm: () => controller.dismissAlarm(),
  setActiveAlarm: (timerId) => controller.setActiveAlarm(timerId),
  // Memory management APIs (for Android to call)
  canSafelyRefresh: () => canSafelyRefresh(),
  getMemorySnapshot: () => getMemorySnapshot(),
  logMemorySnapshot: (reason) => logMemorySnapshot(reason),
  // HA Dashboard URL API
  getCurrentDashboardUrl: () => getCurrentDashboardUrl(),
};

console.log('[KioskServices] Module loaded');

// kiosk-overlay/js/kiosk-settings-sync.js
//
// Brings the REAL settings stack up inside the kiosk shell (Kiosk Real Login, Phase 2).
//
// ## What this is for
//
// A kiosk is now a logged-in device (Phase 1) that happens to display Home Assistant.
// This module is what makes that fact USEFUL: it hosts the ordinary settings machinery —
// settingsStore, SETTINGS_KEY_MAP, the native listener, device registration, the realtime
// channels — so a Console settings change lands on the tablet the same way it lands on
// every other device, instead of through the 5-hop hand-mirror that leaked ~10 keys.
//
// This is HOSTING, not reimplementation. Every guardrail the settings system already has
// (lint:settings, dirty-only uploads, the echo guards, cache-first convergence) applies to
// kiosks for free, and every FUTURE setting works on a kiosk with zero extra wiring.
// If you find yourself adding settings logic *here*, you are rebuilding the mirror.
//
// ## The credential flows native → JS (the exact reverse of the full app)
//
// On a dashboard device, `SupabaseTokenExtractor` scrapes the JWT OUT of the WebView.
// On a kiosk, native OWNS the credential — `KioskSessionProvisioner` mints it and
// `KioskJwtRefresher` renews it over OkHttp, because an appliance must not depend on its
// browser to stay signed in. So the session is pushed IN, via `dashieKioskSetSession`.
//
// Two properties that are not optional:
//
//   1. It is a LATCH, not a call. Native writes `window.__dashieKioskSession` *and* calls
//      the function if it exists. Whichever runs first, the other picks it up. A push that
//      arrives before this module has parsed is not lost, and a module that starts before
//      any push simply reads the latch.
//
//   2. It handles BOTH directions. `dashieKioskSetSession(null)` tears the stack down.
//      Clearing the credential in Kotlin does NOT stop a JS EdgeClient that still holds the
//      token in memory: it would keep reading calendars and spending credits until the page
//      happened to reload. That is §10 bug 4 — a revoked kiosk with full account access —
//      reintroduced one layer up. A revocation that only signs out half the device is worse
//      than one that signs out none, because it looks like it worked.
//
//      (Same reason the ON direction is wired: §10 bug 8. The add-on fires its push on BOTH
//      sharing-toggle directions, and wiring only the off half left the tablet signed out
//      after sharing came back on. An event that fires for both will happily let you
//      implement one.)
//
// ## Dual mode
//
// A never-provisioned kiosk, or one whose household turned sharing off, must still boot
// completely — timers, voice, HA, the lot. It just runs INERT: no cloud, no store writes.
// Inert is a state we log with a REASON, never a silent nothing (§10: "if you can't
// distinguish 'correctly did nothing' from 'silently did nothing', you haven't tested it").

import { createLogger } from '../../js/utils/logger.js';

const logger = createLogger('KioskSettingsSync');

const JWT_STORAGE_KEY = 'dashie-supabase-jwt';

/** Bring-up is single-flight: page-load, provision and refresh pushes can all race. */
let _startPromise = null;
let _running = false;
let _teardown = [];

/** Set once the stack is fully up. The tripwire below asserts on this. */
let _reachedSynced = false;

// ─────────────────────────────────────────────────────────────────────────────
// The native → JS session latch
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Called by Kotlin (KioskSessionInjector) on every shell page load, after provisioning,
 * after a native JWT refresh, and — with null — when the server disowns the session.
 *
 * @param {object|string|null} session - {jwt, expiry, userId, userEmail} or null to sign out
 */
export function setKioskSession(session) {
  let parsed = session;
  if (typeof session === 'string') {
    try {
      parsed = session ? JSON.parse(session) : null;
    } catch (e) {
      logger.error('Session push was not valid JSON — ignoring', { error: e.message });
      return;
    }
  }

  if (!parsed || !parsed.jwt) {
    // Signed out: device removed in the Console (D5), household sharing swept (D6), or the
    // account was deleted. Native has already cleared its own copy.
    if (_running || localStorage.getItem(JWT_STORAGE_KEY)) {
      logger.warn('Session revoked by native — tearing down the settings stack');
      stopKioskSettingsSync();
    } else {
      logger.info('Kiosk is anonymous (no session) — settings sync stays INERT');
    }
    return;
  }

  // Persist it where EdgeClient already looks. This is the whole handoff: EdgeClient's
  // _loadJWTFromStorage() reads exactly this key and shape, so it works UNMODIFIED.
  try {
    localStorage.setItem(JWT_STORAGE_KEY, JSON.stringify({
      jwt: parsed.jwt,
      expiry: parsed.expiry,
      userId: parsed.userId,
      userEmail: parsed.userEmail || '',
      savedAt: Date.now()
    }));
  } catch (e) {
    logger.error('Could not persist the session to localStorage', { error: e.message });
    return;
  }

  if (_running) {
    // A refresh of a session we're already running on. EdgeClient holds the token in
    // memory, so hand it the new one rather than restarting the world.
    if (window.edgeClient?.setJWT) {
      try {
        // setJWT derives expiry/userId/email from the token payload itself.
        window.edgeClient.setJWT(parsed.jwt);
        logger.info('Session refreshed by native — EdgeClient updated in place');
      } catch (e) {
        logger.warn('In-place JWT update failed', { error: e.message });
      }
    }
    return;
  }

  logger.info('Session received from native — starting the settings stack', {
    userId: parsed.userId?.substring(0, 8) + '…'
  });
  startKioskSettingsSync();
}

/**
 * Install the latch. Idempotent. Called at shell boot BEFORE anything else, so a native
 * push that lands mid-parse still finds a home.
 */
export function initKioskSessionBridge() {
  window.dashieKioskSetSession = setKioskSession;

  // Native may have already pushed (onPageFinished can beat module evaluation). Read the
  // latch it left behind. Without this, the push is silently dropped and the kiosk sits
  // inert holding a perfectly good session — invisible, because nothing threw.
  const latched = window.__dashieKioskSession;
  if (latched) {
    logger.debug('Found a session latched by native before this module ran');
    setKioskSession(latched);
    return;
  }

  logger.info('No session latched — kiosk is anonymous for now (sync INERT until provisioned)');
}

// ─────────────────────────────────────────────────────────────────────────────
// Bring-up — the core-initializer recipe, minus the dashboard
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Start the settings stack. Safe to call repeatedly — single-flight and idempotent.
 * Never throws: a kiosk that fails to sync must still show Home Assistant.
 */
export async function startKioskSettingsSync() {
  if (_startPromise) return _startPromise;
  _startPromise = _start().catch((e) => {
    logger.error('Settings stack failed to start — kiosk continues WITHOUT cloud sync', e);
    _startPromise = null;
  });
  return _startPromise;
}

async function _start() {
  const stored = localStorage.getItem(JWT_STORAGE_KEY);
  if (!stored) {
    logger.info('INERT: no session in localStorage — nothing to sync (this is normal for an ' +
                'anonymous kiosk; it will start the moment native provisions one)');
    _startPromise = null;
    return;
  }

  _running = true;

  // ── 1. EdgeClient ──────────────────────────────────────────────────────────
  // It reads the JWT out of localStorage itself (the blob native just wrote).
  //
  // `externallyManaged` turns OFF its proactive refresh timer: on a kiosk, KioskJwtRefresher
  // owns renewal. Two independent refreshers would each mint their own 72h token and diverge —
  // and worse, only the NATIVE one knows what to do with a `device_revoked` reply (sign the
  // device out). Leaving the JS timer on would give us a second, dumber refresher racing the
  // smart one.
  const { EdgeClient } = await import('../../js/data/auth/edge-client.js');
  const edgeClient = new EdgeClient(null, { externallyManaged: true });

  if (!edgeClient.isReady) {
    logger.error('INERT: EdgeClient rejected the stored session (expired?) — no cloud sync. ' +
                 'Native should re-provision on its next HA-token cycle.');
    _running = false;
    _startPromise = null;
    return;
  }

  // ⚠️ THIS GLOBAL IS LOAD-BEARING, and it fails SILENTLY.
  // device-settings-writer.canWriteSettingsToCloud() reads `window.edgeClient`. Without it,
  // every saveDeviceSetting() skips the Supabase write and logs a bland
  // "Kiosk/unauthenticated — skipping Supabase write". Nothing throws. The device looks
  // synced and writes nothing, forever.
  window.edgeClient = edgeClient;
  window.jwtAuth = edgeClient;   // legacy alias, same object

  logger.info('EdgeClient ready', { userId: edgeClient.jwtUserId?.substring(0, 8) + '…' });

  // ── 2. settings-service + the Supabase realtime client ─────────────────────
  // setEdgeClient() force-touches the `supabase` proxy, which in the kiosk bundle is the
  // bundled realtime facade — that's what publishes window.supabase.
  const settingsService = (await import('../../js/data/services/settings-service.js')).default;
  settingsService.setEdgeClient(edgeClient);

  // ── 3. settingsStore + globals + localStorage hydrate ──────────────────────
  // The SAME function the dashboard runs (settings-core-init.js). Not a copy of it.
  const { initializeSettingsCore } = await import('../../js/modules/Settings/settings-core-init.js');
  await initializeSettingsCore({ cacheFirst: true });

  // ── 4. Account-settings realtime (user_settings fan-out) ───────────────────
  // Console changes an account key → this channel → settings-store._applyRemoteSettings.
  try {
    const { getSettingsSync } = await import('../../js/data/sync/settings-sync.js');
    const sync = getSettingsSync();
    sync.configure(window.supabase, edgeClient.jwtUserId);
    await sync.connect();
    _teardown.push(() => sync.disconnect());
  } catch (e) {
    logger.warn('Account-settings realtime failed to connect (device settings still sync)', e);
  }

  // ── 5. Kotlin → JS native settings listener ────────────────────────────────
  // The kiosk's native settings UI writes SharedPreferences; SettingsSyncNotifier dispatches
  // `native-settings-changed`; this listener reads the category back and persists it to the
  // account. Without it, a change made ON the tablet stays on the tablet.
  // Must come AFTER window.settingsStore exists — it calls beginApply() on every event.
  try {
    const { initializeNativeSettingsListener } =
      await import('../../js/modules/Dashboard/native-settings-listener.js');
    initializeNativeSettingsListener();
  } catch (e) {
    logger.warn('Native settings listener failed to initialize', e);
  }

  // ── 6. Device registration → apply → upload → realtime → heartbeat ─────────
  await _registerAndSync();

  _reachedSynced = true;
  logger.success('✅ Kiosk settings stack LIVE — this device now syncs like any other');
}

/**
 * The device half of the recipe (core-initializer.js:1416-1516, minus the dashboard bits).
 */
async function _registerAndSync() {
  const {
    registerDevice, applyDeviceSettings, getCurrentDeviceSettings,
    stripDeviceEditsFromCloudApply, syncCurrentSettingsToDatabase, startDeviceHeartbeat
  } = await import('../../js/core/initialization/device-registration.js');

  // Flush writes that failed last session BEFORE the cloud apply, so the healed row is
  // what gets applied.
  try {
    const { flushDeviceSettingsRetryQueue } =
      await import('../../js/data/settings/device-settings-writer.js');
    await flushDeviceSettingsRetryQueue();
  } catch (e) {
    logger.debug('Retry-queue flush failed (non-fatal)', e);
  }

  const device = await registerDevice();

  if (!device) {
    // ⚠️ registerDevice() returns null WITHOUT throwing when
    // shouldRegisterDevice(deviceType) is false — i.e. if the platform detector doesn't
    // classify this kiosk as a tablet or TV. That would ALSO silently disable
    // initializeDeviceSettingsSync() below (same gate), killing cloud→device sync with no
    // error anywhere. Exactly the §10 bug 3 shape (list_devices' tv_only default silently
    // matching zero kiosks). Say so LOUDLY — logger.error is logcat-visible.
    logger.error(
      'TRIPWIRE: registerDevice() returned null — the kiosk has a valid session but NO ' +
      'user_devices row, so device settings will NOT sync in either direction. Either (a) ' +
      'the server REFUSED registration because this kiosk was revoked (household sharing ' +
      'turned off / device removed in the Console) — expected, and the session ends at the ' +
      'next liveness check; see the DROP line just above — or (b) the platform detector did ' +
      'not classify this device as tablet/TV (check DashieNative.getDeviceType()), or the ' +
      'register_device call failed.'
    );
    return;
  }

  logger.info('Device registered', { name: device.device_name, id: device.device_id?.substring(0, 8) + '…' });

  // Snapshot BEFORE the cloud apply pushes cloud values into Kotlin — otherwise a native
  // change that missed its sync broadcast gets reverted by the apply and the reverted value
  // re-uploaded, permanently erasing it.
  let preApplySnapshot = null;
  try {
    preApplySnapshot = getCurrentDeviceSettings();
  } catch (e) {
    logger.warn('Pre-apply snapshot failed — falling back to post-apply read', e);
  }

  if (device.settings) {
    const cloudToApply = preApplySnapshot
      ? stripDeviceEditsFromCloudApply(device.settings, preApplySnapshot)
      : device.settings;
    await applyDeviceSettings(cloudToApply);
    logger.info('Applied device settings from the cloud');
  }

  await syncCurrentSettingsToDatabase(
    device.device_id,
    preApplySnapshot ? { snapshot: preApplySnapshot, cloudBaseline: device.settings || null } : null
  );

  // Device-settings realtime (console/mobile → this kiosk).
  try {
    const { initializeDeviceSettingsSync } =
      await import('../../js/core/initialization/device-settings-sync.js');
    const stop = initializeDeviceSettingsSync();
    if (stop) _teardown.push(stop);
    else logger.error('TRIPWIRE: initializeDeviceSettingsSync() returned null — cloud→device ' +
                      'settings changes will NOT reach this kiosk');
  } catch (e) {
    logger.warn('Device settings realtime failed', e);
  }

  try {
    startDeviceHeartbeat(device.device_id);
  } catch (e) {
    logger.debug('Heartbeat failed to start', e);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Teardown (revocation)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Tear the stack down and drop the credential. Called when native reports the server has
 * disowned this session.
 *
 * Dropping the localStorage blob is the point: EdgeClient rehydrates from it on the next
 * page load, so leaving it behind would silently resurrect a revoked session.
 */
export function stopKioskSettingsSync() {
  for (const fn of _teardown) {
    try { fn(); } catch (e) { logger.debug('Teardown step failed', e); }
  }
  _teardown = [];

  try {
    window.edgeClient?.clearJWT?.();
  } catch (e) {
    logger.debug('EdgeClient clearJWT failed', e);
  }
  try {
    localStorage.removeItem(JWT_STORAGE_KEY);
  } catch (e) {
    logger.warn('Could not clear the stored session', e);
  }

  window.edgeClient = null;
  window.jwtAuth = null;

  _running = false;
  _reachedSynced = false;
  _startPromise = null;

  logger.warn('Kiosk settings stack STOPPED — the device is anonymous again');
}

// ─────────────────────────────────────────────────────────────────────────────
// Tripwire
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Every failure this system produces is SILENT (§10: nothing threw, nothing logged, and the
 * code read correctly). So assert on positive state: if we hold a session but never reached
 * "synced", say so in a way that shows up in logcat and in the settings-soak grep.
 *
 * Copied in spirit from SettingsSync's own TRIPWIRE, which is the only reason the 1.0.15
 * missing-consumer regression was ever found.
 */
export function armKioskSyncTripwire(delayMs = 60000) {
  setTimeout(() => {
    const hasSession = !!localStorage.getItem(JWT_STORAGE_KEY);
    if (hasSession && !_reachedSynced) {
      logger.error(
        'KioskSync TRIPWIRE: this kiosk holds a valid account session but the settings stack ' +
        'never came up. Settings will NOT sync in either direction and NOTHING ELSE will tell ' +
        'you that. Check the log above for the step that bailed.'
      );
    }
  }, delayMs);
}

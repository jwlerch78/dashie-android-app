/**
 * CHICKADEE shell stub for `kiosk-settings-sync.js` — John's ruling, 2026-08-02:
 * *"no shell bridge in chickadee."* The account/session bridge does not exist in this edition.
 *
 * ## Why a BUILD-TIME stub and not a runtime `if`
 *
 * The goal is a property of the **artifact**, not of the running code: zero account, session or
 * Supabase strings in an account-free product's bundle. A runtime gate cannot deliver that — the
 * PROD Supabase project id would still be grep-able in the shipped file.
 *
 * And that distinction is not theoretical here. Every heavy import in the real module is a
 * DYNAMIC `await import(...)` (`edge-client`, `settings-service`, `settings-sync`,
 * `device-registration`, …), so at RUNTIME the cloud stack was **already inert** without a
 * session — nothing executed. They were inlined at BUNDLE time. The fix therefore belongs in the
 * bundler, which is exactly what this file is.
 *
 * Replacing the whole module is safe because the module is 100% account-side: all five of its
 * exports exist to serve a cloud session, and there is no local-persistence half entangled with
 * them (measured before proposing the structure). If that ever stops being true, this stub is
 * where it will show up as a missing export rather than as silent misbehaviour.
 *
 * @see kiosk-overlay/js/kiosk-settings-sync.js — the real module, built into the Dashie shell.
 */

const TAG = '[KioskSyncStub]';

/**
 * 🔴 Installed, not omitted — and that is the whole point of this function.
 *
 * Native Kotlin pushes a session by calling `window.dashieKioskSetSession`. If Chickadee simply
 * did not define it, a push would land in an `undefined` call inside `evaluateJavascript`, where
 * the exception is **swallowed by the WebView** — invisible on both sides. Standing rule 2: a
 * dispatch that falls through must say so.
 *
 * Reaching this means an account-free build was handed an account session, i.e. something
 * upstream still believes this edition has accounts. That is worth a loud line, not a shrug.
 */
export function initKioskSessionBridge() {
  window.dashieKioskSetSession = () => {
    console.warn(TAG, 'DROP: [unexpected] native pushed an account session into the Chickadee ' +
      'shell. This edition has no account, no session bridge and no cloud settings sync, so the ' +
      'session is discarded. Something upstream still thinks this build has accounts.');
  };
  console.info(TAG, 'account/session bridge is absent by design (Chickadee edition)');
}

/**
 * No-op. The real tripwire asserts *"we hold a session but never synced"* — a question that
 * cannot arise where sessions do not exist.
 *
 * Silent on purpose, unlike [initKioskSessionBridge]: this fires on every shell boot, and a
 * marker at that rate trains the reader to skim the channel that carries the one above. Same
 * grading criterion the Kotlin seams use.
 */
export function armKioskSyncTripwire() { /* no session can exist, so nothing to assert */ }

/**
 * Unused by the shell today, exported to keep this stub's surface equal to the real module's.
 *
 * If a future consumer imports one of these, it gets a defined no-op rather than a build error —
 * but the DROP above is what makes the account-shaped call visible. Kept deliberately: a stub
 * whose surface drifts from its twin fails at build time in the edition nobody is looking at.
 */
export function setKioskSession() {
  console.warn(TAG, 'DROP: [unexpected] setKioskSession() called in the Chickadee shell.');
}
export async function startKioskSettingsSync() {
  console.warn(TAG, 'DROP: [unexpected] startKioskSettingsSync() called in the Chickadee shell.');
}
export function stopKioskSettingsSync() { /* nothing was ever started */ }

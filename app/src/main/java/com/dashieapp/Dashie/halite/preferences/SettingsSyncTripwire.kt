package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.os.Handler
import android.util.Log
import android.widget.Toast

/**
 * Debug-build runtime drift guard for [SettingsSyncNotifier]
 * (SETTINGS_ROBUSTNESS_PLAN Phase 3, "runtime tripwire").
 *
 * Invariant it enforces: every CLASSIFIED pref change (a key in
 * PREF_KEY_TO_CATEGORY, observed while not suppressed) must be followed by a
 * dispatch of its category within [TRIP_WINDOW_MS]. The notifier debounces at
 * ~400 ms, so a healthy change always dispatches well inside the window; a
 * miss means the notifier's pipeline regressed (listener dropped, debounce
 * runnable cancelled and never re-armed, dispatch callback throwing, etc.) —
 * the exact failure class that would silently re-open the one-sided-write bug
 * the notifier exists to kill.
 *
 * Suppression-aware by construction: the notifier only calls
 * [onClassifiedChange] AFTER its applyDepth check, so cloud→Kotlin applies
 * never arm the tripwire.
 *
 * On trip: Log.e (greppable tag for the test rig) + a toast so it's visible
 * during dogfooding without watching logcat. Instantiated ONLY on debuggable
 * builds (see SettingsSyncNotifier) — zero release overhead.
 */
class SettingsSyncTripwire(
    private val context: Context,
    /** The notifier's main-looper handler — all callbacks arrive on main. */
    private val handler: Handler
) {
    companion object {
        private const val TAG = "SettingsSyncTripwire"
        private const val TRIP_WINDOW_MS = 5_000L
    }

    /** category → armed-at timestamp + the key that armed it (for the report).
     *  Main-thread-confined (SharedPreferences listeners and the notifier's
     *  debounce both run on the main looper). */
    private val armed = mutableMapOf<String, Pair<Long, String>>()

    fun onClassifiedChange(key: String, category: String) {
        // Re-arming an already-armed category keeps the ORIGINAL deadline —
        // a steady write stream must not indefinitely postpone detection.
        if (armed.containsKey(category)) return
        armed[category] = System.currentTimeMillis() to key
        handler.postDelayed({ check(category) }, TRIP_WINDOW_MS)
    }

    fun onDispatched(category: String) {
        armed.remove(category)
    }

    private fun check(category: String) {
        val (armedAt, key) = armed[category] ?: return  // dispatched in time
        val ageMs = System.currentTimeMillis() - armedAt
        // A stale timer (from an arm that already dispatched) can fire while a
        // NEWER arm is pending — only trip once THIS entry's own window elapsed;
        // the newer arm's own timer covers it otherwise.
        if (ageMs < TRIP_WINDOW_MS - 50) return
        armed.remove(category)
        val msg = "Settings sync TRIPWIRE: pref '$key' (category '$category') changed " +
            "${ageMs}ms ago but no category dispatch fired — the auto-sync pipeline " +
            "is broken for this key (revert-on-reboot risk)."
        Log.e(TAG, msg)
        // Logcat rotates away in hours on a busy device; a trip during a soak
        // MUST survive until someone pulls logs. PersistentLog is the durable
        // channel test-rig/settings-soak-check.sh greps.
        try {
            com.dashieapp.Dashie.halite.diagnostics.PersistentLog.error(TAG, msg)
        } catch (_: Exception) { /* best-effort */ }
        try {
            Toast.makeText(context, "⚠️ $msg", Toast.LENGTH_LONG).show()
        } catch (_: Exception) { /* toast is best-effort (no UI context on some surfaces) */ }
    }
}

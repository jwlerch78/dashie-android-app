package com.dashieapp.Dashie.halite

import android.app.Activity
import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.halite.preferences.LockPreferences
import com.dashieapp.Dashie.halite.sidebar.dialogs.LockDialogs

/**
 * Kiosk-lock gate for settings destinations (Control Center overlay,
 * SettingsActivity, and the JS settings drawer funnels).
 *
 * `lockSettings` used to be enforced only by hiding sidebar rows — every
 * non-sidebar entry point (out-of-credits prompt, calendar reauth pill,
 * music sign-in, JS bridge openSettings/openControlCenter, CC cards) walked
 * straight into full settings on a locked kiosk. This gate guards the
 * DESTINATIONS instead of the callers, so new callers stay covered.
 *
 * Policy:
 * - `lockSettings == false` → pass through (zero behavior change unlocked).
 * - `unlockMechanism == "anyone"` (or PIN mode with no PIN set) → pass
 *   through. Anyone can unlock via the lock row anyway — blocking here would
 *   be security theater.
 * - PIN mode with a PIN set → prompt via the existing [LockDialogs] PIN
 *   entry (attempt limit + lockout + forgot-PIN recovery already live
 *   there). Success opens a short in-memory grace window so PIN-once
 *   doesn't double-prompt (CC → card tap → SettingsActivity).
 * - PIN success does NOT unlock — the lock stays armed.
 *
 * Grace is in-memory only (app restart clears it for free) and is also
 * cleared on screensaver start (ScreenDimmer.dimScreen) and on explicit
 * lock/unlock changes (LockDialogs.applyLock / performUnlock).
 */
object LockGate {

    private const val TAG = "LockGate"
    private const val GRACE_WINDOW_MS = 3 * 60_000L

    @Volatile
    private var graceUntilMs = 0L

    /**
     * True if settings surfaces are open-able right now without a PIN prompt
     * (unlocked, "anyone" mechanism, no PIN set, or inside the grace window).
     * Read-only — does not grant or extend grace.
     */
    fun isAllowedNow(context: Context): Boolean {
        val lock = LockPreferences(context)
        if (!lock.lockSettings) return true
        if (!lock.isPinRequired) return true
        return System.currentTimeMillis() < graceUntilMs
    }

    /**
     * Run [onAllowed] if settings surfaces are open-able right now, else show
     * the PIN dialog first (reusing LockDialogs) and run [onAllowed] only on
     * success; [onDenied] runs on cancel/failure/lockout. Call on UI thread.
     *
     * Pass the caller's existing [lockDialogs] instance when it has one so
     * the attempt/lockout counters are shared; null makes the gate construct
     * a fresh instance for this prompt (per-instance counters — acceptable).
     */
    fun requirePin(
        activity: Activity,
        lockDialogs: LockDialogs? = null,
        onDenied: () -> Unit = {},
        onAllowed: () -> Unit
    ) {
        if (isAllowedNow(activity)) {
            onAllowed()
            return
        }
        val dialogs = lockDialogs ?: LockDialogs(activity, HalitePreferences(activity))
        dialogs.showPinEntryDialog(
            title = "Settings Locked",
            subtitle = "Enter PIN to open settings",
            showForgotPin = true
        ) { success ->
            if (success) {
                graceUntilMs = System.currentTimeMillis() + GRACE_WINDOW_MS
                Log.i(TAG, "PIN accepted — settings grace window open (${GRACE_WINDOW_MS / 1000}s)")
                onAllowed()
            } else {
                Log.i(TAG, "PIN cancelled/denied — settings stay closed")
                onDenied()
            }
        }
    }

    /** Drop the grace window (screensaver start, lock/unlock changes). */
    fun clearGrace() {
        graceUntilMs = 0L
    }
}

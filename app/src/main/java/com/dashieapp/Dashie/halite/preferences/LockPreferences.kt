package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * Lock and kiosk mode preferences for Dashie Kiosk.
 *
 * Manages:
 * - Lock to app (screen pinning mode)
 * - Kiosk locked state (legacy)
 * - Lock app exit mode
 * - Lock settings mode
 * - PIN protection settings
 * - Unlock mechanism and interface settings
 *
 * Uses the same SharedPreferences file as HalitePreferences for backward compatibility.
 */
class LockPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_lite_prefs"

        // Legacy keys
        private const val KEY_HIDE_NAV_BARS = "hide_nav_bars"  // Used for lockToApp
        private const val KEY_KIOSK_LOCKED = "kiosk_locked"

        // Lock modes (new unified lock system)
        private const val KEY_LOCK_APP_EXIT = "lock_app_exit"
        private const val KEY_LOCK_SETTINGS = "lock_settings"
        private const val KEY_UNLOCK_MECHANISM = "unlock_mechanism"
        private const val KEY_UNLOCK_INTERFACE = "unlock_interface"
        private const val KEY_LOCK_PIN = "lock_pin"
        private const val KEY_LOCK_PIN_LENGTH = "lock_pin_length"
        private const val KEY_LOCK_RECOVERY_EMAIL = "lock_recovery_email"
        private const val KEY_PIN_APP_WHEN_LOCKED = "pin_app_when_locked"

        // Unlock mechanism options
        const val UNLOCK_MECHANISM_ANYONE = "anyone"
        const val UNLOCK_MECHANISM_PIN = "pin"

        // Unlock interface options
        const val UNLOCK_INTERFACE_VISIBLE = "visible"
        const val UNLOCK_INTERFACE_HIDDEN = "hidden"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ========== Lock To App (Screen Pinning) ==========

    /**
     * Lock to app (screen pinning + immersive mode)
     * When true, enables screen pinning to block notification shade and hides nav/status bars
     * Uses KEY_HIDE_NAV_BARS for backwards compatibility with existing installs
     * Default: false (user must opt-in)
     */
    var lockToApp: Boolean
        get() = prefs.getBoolean(KEY_HIDE_NAV_BARS, false)
        set(value) { prefs.edit().putBoolean(KEY_HIDE_NAV_BARS, value).commit() }

    // Legacy alias for backwards compatibility
    @Deprecated("Use lockToApp instead", ReplaceWith("lockToApp"))
    var hideNavBars: Boolean
        get() = lockToApp
        set(value) { lockToApp = value }

    // ========== Pin App When Locked ==========

    /**
     * Whether to use Android screen pinning (startLockTask) when Lock to App is enabled.
     * When true (default), uses startLockTask/stopLockTask which fully blocks the notification shade
     * but causes a popup notification when unpinning (e.g., for external app screensavers).
     * When false, relies solely on immersive mode fast re-hide (same strategy as Fire tablets).
     * Hidden on Fire tablets since they always skip screen pinning.
     * Default: true (preserves existing behavior)
     */
    var pinAppWhenLocked: Boolean
        get() = prefs.getBoolean(KEY_PIN_APP_WHEN_LOCKED, true)
        set(value) { prefs.edit().putBoolean(KEY_PIN_APP_WHEN_LOCKED, value).commit() }

    // ========== Legacy Kiosk Lock ==========

    /**
     * Kiosk locked state (controlled via Fully Kiosk API lockKiosk/unlockKiosk commands)
     * When true, sidebar hides Exit, Clear Cache, and Reload buttons to prevent
     * users from escaping the kiosk experience.
     * Default: false (unlocked - full access to all controls)
     * @deprecated Use lockSettings instead. Maintained for API compatibility.
     */
    @Deprecated("Use lockSettings instead", ReplaceWith("lockSettings"))
    var kioskLocked: Boolean
        get() = prefs.getBoolean(KEY_KIOSK_LOCKED, false)
        set(value) { prefs.edit().putBoolean(KEY_KIOSK_LOCKED, value).commit() }

    // ========== Lock Modes (Unified Lock System) ==========

    /**
     * Lock app exit mode - prevents back button/home from exiting the app
     * This is the new replacement for lockToApp, providing more granular control.
     * Default: false (app can be exited normally)
     */
    var lockAppExit: Boolean
        get() {
            val stored = prefs.contains(KEY_LOCK_APP_EXIT)
            if (!stored) {
                // Migrate from old lockToApp setting
                val oldLockToApp = prefs.getBoolean(KEY_HIDE_NAV_BARS, false)
                if (oldLockToApp) {
                    prefs.edit().putBoolean(KEY_LOCK_APP_EXIT, true).commit()
                    return true
                }
            }
            return prefs.getBoolean(KEY_LOCK_APP_EXIT, false)
        }
        set(value) { prefs.edit().putBoolean(KEY_LOCK_APP_EXIT, value).commit() }

    /**
     * Lock settings mode - hides all settings except volume slider and lock row
     * This is the new replacement for kioskLocked, with added PIN protection options.
     * Default: false (all settings visible)
     */
    var lockSettings: Boolean
        get() {
            val stored = prefs.contains(KEY_LOCK_SETTINGS)
            if (!stored) {
                // Migrate from old kioskLocked setting
                val oldKioskLocked = prefs.getBoolean(KEY_KIOSK_LOCKED, false)
                if (oldKioskLocked) {
                    prefs.edit().putBoolean(KEY_LOCK_SETTINGS, true).commit()
                    return true
                }
            }
            return prefs.getBoolean(KEY_LOCK_SETTINGS, false)
        }
        set(value) { prefs.edit().putBoolean(KEY_LOCK_SETTINGS, value).commit() }

    /**
     * Computed property: true if any lock mode is active
     */
    val isLocked: Boolean
        get() = lockAppExit || lockSettings

    /**
     * Get a human-readable description of what's locked
     * Returns null if nothing is locked, otherwise returns "(App Exit)", "(Settings)", or "(App Exit, Settings)"
     */
    val lockDescription: String?
        get() {
            val locked = mutableListOf<String>()
            if (lockAppExit) locked.add("App Exit")
            if (lockSettings) locked.add("Settings")
            return if (locked.isEmpty()) null else "(${locked.joinToString(", ")})"
        }

    // ========== Unlock Settings ==========

    /**
     * Unlock mechanism: "anyone" or "pin"
     * - anyone: Anyone can unlock by tapping the lock row
     * - pin: PIN required to unlock
     * Default: "anyone" (no PIN required)
     */
    var unlockMechanism: String
        get() = prefs.getString(KEY_UNLOCK_MECHANISM, UNLOCK_MECHANISM_ANYONE) ?: UNLOCK_MECHANISM_ANYONE
        set(value) { prefs.edit().putString(KEY_UNLOCK_MECHANISM, value).commit() }

    /**
     * Unlock interface: "visible" or "hidden"
     * - visible: Lock row is clickable to initiate unlock
     * - hidden: 4-second long press on logo required to initiate unlock
     * Default: "visible"
     */
    var unlockInterface: String
        get() = prefs.getString(KEY_UNLOCK_INTERFACE, UNLOCK_INTERFACE_VISIBLE) ?: UNLOCK_INTERFACE_VISIBLE
        set(value) { prefs.edit().putString(KEY_UNLOCK_INTERFACE, value).commit() }

    /**
     * Lock PIN - stored in plain text (simple dashboard access, not high security)
     * Empty string means no PIN is set
     */
    var lockPin: String
        get() = prefs.getString(KEY_LOCK_PIN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LOCK_PIN, value).commit() }

    /**
     * Lock PIN length: 4 or 6
     * Only relevant when PIN is set
     * Default: 4
     */
    var lockPinLength: Int
        get() = prefs.getInt(KEY_LOCK_PIN_LENGTH, 4)
        set(value) { prefs.edit().putInt(KEY_LOCK_PIN_LENGTH, value.coerceIn(4, 6)).commit() }

    /**
     * Recovery email for forgotten PIN
     * If set, user can request PIN to be sent to this email
     */
    var lockRecoveryEmail: String
        get() = prefs.getString(KEY_LOCK_RECOVERY_EMAIL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LOCK_RECOVERY_EMAIL, value).commit() }

    /**
     * Check if a PIN is set
     */
    val hasPinSet: Boolean
        get() = lockPin.isNotEmpty()

    /**
     * Check if PIN is required for unlock
     */
    val isPinRequired: Boolean
        get() = unlockMechanism == UNLOCK_MECHANISM_PIN && hasPinSet

    /**
     * Clear all lock settings (for testing or reset)
     */
    fun clearLockSettings() {
        prefs.edit()
            .remove(KEY_LOCK_APP_EXIT)
            .remove(KEY_LOCK_SETTINGS)
            .remove(KEY_UNLOCK_MECHANISM)
            .remove(KEY_UNLOCK_INTERFACE)
            .remove(KEY_LOCK_PIN)
            .remove(KEY_LOCK_PIN_LENGTH)
            .remove(KEY_LOCK_RECOVERY_EMAIL)
            .commit()
    }
}

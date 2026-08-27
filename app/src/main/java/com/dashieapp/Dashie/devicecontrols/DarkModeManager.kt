package com.dashieapp.Dashie.devicecontrols

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONObject

/**
 * Manages system-wide dark mode control.
 *
 * Primary source of truth: SharedPreferences ("dashie_dark_mode_prefs").
 * Also writes to Settings.Secure.ui_night_mode for system-level persistence,
 * and calls AppCompatDelegate.setDefaultNightMode() to force the app's DayNight
 * theme to match — this ensures Settings, dialogs, and all Activities follow
 * the user's choice regardless of whether the OS actually applies the system
 * dark mode change (which fails on some devices like Mio rk3576).
 *
 * Requires WRITE_SECURE_SETTINGS permission, which must be granted via ADB:
 * adb shell pm grant com.dashieapp.Dashie.staging android.permission.WRITE_SECURE_SETTINGS
 */
class DarkModeManager(private val activity: Activity) {

    companion object {
        private const val TAG = "DarkModeManager"

        // Settings.Secure key for UI night mode
        private const val UI_NIGHT_MODE = "ui_night_mode"

        // SharedPreferences for dark mode tracking
        private const val PREFS_NAME = "dashie_dark_mode_prefs"
        private const val KEY_IS_DARK = "is_dark_mode"
        private const val KEY_HAS_USER_SET = "has_user_set"

        // Values for ui_night_mode
        const val MODE_NIGHT_AUTO = 0
        const val MODE_NIGHT_NO = 1   // Light mode
        const val MODE_NIGHT_YES = 2  // Dark mode

        /**
         * Apply stored dark mode preference on app startup.
         * Call from MainActivity.onCreate() before setContentView().
         *
         * Only updates resources.configuration (for ComponentActivity like MainActivity).
         * Does NOT call AppCompatDelegate.setDefaultNightMode() — that triggers a
         * system config change cascade that races with forceResourcesNightMode() and
         * reverts the theme on ComponentActivity. AppCompatActivity subclasses
         * (like SettingsActivity) should call applyStoredPreferenceAppCompat() instead.
         */
        fun applyStoredPreference(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_HAS_USER_SET, false)) {
                val isDark = prefs.getBoolean(KEY_IS_DARK, false)
                forceResourcesNightMode(context, isDark)
                Log.i(TAG, "🌓 Restored dark mode preference: isDark=$isDark")
            } else {
                // First launch: force LIGHT to override any system-default dark theme.
                forceResourcesNightMode(context, false)
                Log.i(TAG, "🌓 First launch — forcing LIGHT mode")
            }
        }

        /**
         * Apply stored dark mode preference for AppCompatActivity subclasses.
         * Call before super.onCreate() in SettingsActivity etc.
         * Uses AppCompatDelegate which properly integrates with DayNight themes.
         */
        fun applyStoredPreferenceAppCompat(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_HAS_USER_SET, false)) {
                val isDark = prefs.getBoolean(KEY_IS_DARK, false)
                AppCompatDelegate.setDefaultNightMode(
                    if (isDark) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                )
                Log.i(TAG, "🌓 Restored dark mode preference (AppCompat): isDark=$isDark")
            } else {
                // First launch: explicit LIGHT default for new users.
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                Log.i(TAG, "🌓 First launch (AppCompat) — forcing LIGHT mode")
            }
        }

        /**
         * Check if the user has a stored dark mode preference and return it.
         * Returns null if the user has never toggled dark mode.
         */
        fun getStoredPreference(context: Context): Boolean? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return if (prefs.getBoolean(KEY_HAS_USER_SET, false)) {
                prefs.getBoolean(KEY_IS_DARK, false)
            } else null
        }

        /**
         * Force the activity's resources.configuration to use the specified night mode.
         * Required for ComponentActivity (non-AppCompat) where AppCompatDelegate has no effect.
         */
        @Suppress("DEPRECATION")
        fun forceResourcesNightMode(context: Context, isDark: Boolean) {
            val beforeNight = context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val config = android.content.res.Configuration(context.resources.configuration)
            config.uiMode = (config.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (isDark) android.content.res.Configuration.UI_MODE_NIGHT_YES
                 else android.content.res.Configuration.UI_MODE_NIGHT_NO)
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            val afterNight = context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            Log.w(TAG, "🌓 forceResourcesNightMode(isDark=$isDark): before=$beforeNight after=$afterNight")
        }
    }

    private val contentResolver = activity.contentResolver
    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Check if dark mode is currently enabled.
     * Reads from SharedPreferences (source of truth), falling back to
     * Settings.Secure and then system configuration.
     */
    fun isDarkMode(): Boolean {
        // SharedPreferences is the source of truth if the user has ever toggled
        if (prefs.getBoolean(KEY_HAS_USER_SET, false)) {
            val isDark = prefs.getBoolean(KEY_IS_DARK, false)
            Log.d(TAG, "🌓 isDarkMode(): from SharedPrefs -> $isDark")
            return isDark
        }
        // First launch: explicit Dashie default is LIGHT, regardless of the
        // device's system theme. Avoids the chrome shipping in dark on
        // tablets that ship with a default-dark system theme.
        Log.d(TAG, "🌓 isDarkMode(): first launch -> default LIGHT")
        return false
    }

    /**
     * Set dark mode.
     * 1. Saves to SharedPreferences (source of truth)
     * 2. Forces resources.configuration uiMode (immediate effect on ComponentActivity)
     * 3. Best-effort writes to Settings.Secure and UiModeManager for system-level change
     *
     * Does NOT call AppCompatDelegate.setDefaultNightMode() — that triggers a system
     * config change cascade that races with forceResourcesNightMode() on ComponentActivity.
     * SettingsActivity (AppCompatActivity) picks up the preference via
     * applyStoredPreferenceAppCompat() in its own onCreate().
     *
     * @param enabled true for dark mode, false for light mode
     * @return true if successful
     */
    fun setDarkMode(enabled: Boolean): Boolean {
        Log.i(TAG, "🌓 setDarkMode($enabled)")

        // 1. Save to SharedPreferences (always works, source of truth)
        prefs.edit()
            .putBoolean(KEY_IS_DARK, enabled)
            .putBoolean(KEY_HAS_USER_SET, true)
            .apply()

        // 2. Force resources configuration (immediate effect for ComponentActivity)
        forceResourcesNightMode(activity, enabled)
        Log.i(TAG, "🌓 Dark mode set to ${if (enabled) "dark" else "light"}")

        // NOTE: We intentionally do NOT write to Settings.Secure or UiModeManager.
        // On devices where system dark mode works (e.g. Samsung), those writes trigger
        // asynchronous system configuration changes that race with forceResourcesNightMode(),
        // causing intermittent wrong-theme rendering in native views (Control Center, etc.).
        // SharedPreferences + forceResourcesNightMode is sufficient — we re-apply on every
        // activity start, config change, and CC overlay show/poll.

        return true
    }

    /**
     * Toggle dark mode
     * @return the new dark mode state (true = dark, false = light)
     */
    fun toggleDarkMode(): Boolean {
        val currentlyDark = isDarkMode()
        val newState = !currentlyDark
        setDarkMode(newState)
        Log.i(TAG, "🌓 toggleDarkMode(): $currentlyDark -> $newState")
        return newState
    }

    /**
     * Check if we have permission to write secure settings.
     * Dark mode toggle now works via AppCompatDelegate even without this permission,
     * but WRITE_SECURE_SETTINGS is still needed for system-level dark mode.
     */
    fun canWriteSecureSettings(): Boolean {
        return try {
            val currentMode = Settings.Secure.getInt(contentResolver, UI_NIGHT_MODE, MODE_NIGHT_AUTO)
            Settings.Secure.putInt(contentResolver, UI_NIGHT_MODE, currentMode)
        } catch (e: SecurityException) {
            Log.w(TAG, "🌓 canWriteSecureSettings(): false - ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "🌓 canWriteSecureSettings() error: ${e.message}")
            false
        }
    }

    /**
     * Get dark mode info as JSON string
     */
    fun getDarkModeInfo(): String {
        return try {
            val mode = Settings.Secure.getInt(contentResolver, UI_NIGHT_MODE, MODE_NIGHT_AUTO)
            val isDark = isDarkMode()
            val canWrite = canWriteSecureSettings()

            JSONObject(mapOf(
                "isDark" to isDark,
                "mode" to mode,
                "modeString" to when (mode) {
                    MODE_NIGHT_NO -> "light"
                    MODE_NIGHT_YES -> "dark"
                    else -> "auto"
                },
                "canWriteSecureSettings" to canWrite
            )).toString()
        } catch (e: Exception) {
            Log.e(TAG, "🌓 getDarkModeInfo() error: ${e.message}")
            "{\"isDark\": false, \"mode\": 0, \"modeString\": \"unknown\", \"canWriteSecureSettings\": false}"
        }
    }
}

package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * Sleep/Wake timer preferences for Dashie Kiosk.
 *
 * Manages:
 * - Sleep timer enabled state
 * - Sleep method (schedule vs inactivity)
 * - Sleep/wake times for scheduled mode
 * - Re-sleep and inactivity timeouts
 * - Motion wake during sleep
 * - Clock display during sleep
 *
 * Uses the same SharedPreferences file as HalitePreferences for backward compatibility.
 */
class SleepPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_lite_prefs"

        // Sleep/Wake Timer settings
        private const val KEY_SLEEP_ENABLED = "sleep_enabled"
        private const val KEY_SLEEP_METHOD = "sleep_method"
        private const val KEY_SLEEP_TIME = "sleep_time"
        private const val KEY_WAKE_TIME = "wake_time"
        private const val KEY_RESLEEP_TIMEOUT = "resleep_timeout"
        private const val KEY_INACTIVITY_TIMEOUT = "inactivity_timeout"
        private const val KEY_MOTION_WAKE_FOR_SLEEP = "motion_wake_for_sleep"
        private const val KEY_SLEEP_SHOW_CLOCK = "sleep_show_clock"
        private const val KEY_REDUCE_BRIGHTNESS_ON_SLEEP = "reduce_brightness_on_sleep"

        // Sleep method options
        const val SLEEP_METHOD_SCHEDULE = "schedule"
        const val SLEEP_METHOD_INACTIVITY = "inactivity"

        // App startup/lifecycle
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_LAUNCH_ON_WAKE = "launch_on_wake"
        private const val KEY_WAS_IN_SCREEN_OFF_MODE = "was_in_screen_off_mode"
        private const val KEY_HARDWARE_SCREEN_OFF = "hardware_screen_off"
        private const val KEY_MOTION_WAKE_CAMERA_ACTIVE = "motion_wake_camera_active"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ========== Sleep Timer Settings ==========

    /**
     * Sleep timer enabled (master toggle).
     * When enabled, the device will sleep/wake based on configured method (schedule or inactivity).
     * Default: false
     */
    var sleepEnabled: Boolean
        get() = prefs.getBoolean(KEY_SLEEP_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_SLEEP_ENABLED, value).commit() }

    /**
     * Sleep method: "schedule" or "inactivity".
     * - schedule: Sleep/wake at configured times
     * - inactivity: Sleep after period of no user interaction
     * Default: "schedule"
     */
    var sleepMethod: String
        get() {
            val raw = prefs.getString(KEY_SLEEP_METHOD, SLEEP_METHOD_SCHEDULE) ?: SLEEP_METHOD_SCHEDULE
            // Normalize "scheduled" → "schedule" (legacy webapp value)
            return if (raw == "scheduled") SLEEP_METHOD_SCHEDULE else raw
        }
        set(value) { prefs.edit().putString(KEY_SLEEP_METHOD, value).commit() }

    /**
     * Sleep time in 24-hour format (e.g., "22:00" for 10:00 PM).
     * Used when sleepMethod == "schedule".
     * Default: "22:00"
     */
    var sleepTime: String
        get() = prefs.getString(KEY_SLEEP_TIME, "22:00") ?: "22:00"
        set(value) { prefs.edit().putString(KEY_SLEEP_TIME, value).commit() }

    /**
     * Wake time in 24-hour format (e.g., "07:00" for 7:00 AM).
     * Used when sleepMethod == "schedule".
     * Default: "07:00"
     */
    var wakeTime: String
        get() = prefs.getString(KEY_WAKE_TIME, "07:00") ?: "07:00"
        set(value) { prefs.edit().putString(KEY_WAKE_TIME, value).commit() }

    /**
     * Re-sleep timeout in minutes.
     * When user wakes during scheduled sleep hours, re-sleep after this many minutes
     * of no interaction. 0 = "Immediate" in the settings UI (a short grace applies so
     * the wake isn't instantly reverted — see SleepWakeScheduler).
     * Default: 5 minutes
     */
    var resleepTimeout: Int
        get() = prefs.getInt(KEY_RESLEEP_TIMEOUT, 5)
        set(value) { prefs.edit().putInt(KEY_RESLEEP_TIMEOUT, value.coerceIn(0, 60)).commit() }

    /**
     * Inactivity timeout in seconds.
     * When sleepMethod == "inactivity", sleep after this many seconds of no user interaction.
     * Default: 1800 seconds (30 minutes)
     */
    var inactivityTimeout: Int
        get() = prefs.getInt(KEY_INACTIVITY_TIMEOUT, 1800)
        set(value) { prefs.edit().putInt(KEY_INACTIVITY_TIMEOUT, value.coerceIn(30, 28800)).commit() }

    /**
     * Motion wake enabled during sleep mode.
     * When true, motion detection can wake the screen from sleep.
     * When false, only touch or scheduled wake time will wake from sleep.
     * Default: false (must be explicitly enabled to avoid unnecessary camera usage)
     */
    var motionWakeForSleep: Boolean
        get() = prefs.getBoolean(KEY_MOTION_WAKE_FOR_SLEEP, false)
        set(value) { prefs.edit().putBoolean(KEY_MOTION_WAKE_FOR_SLEEP, value).commit() }

    /**
     * Show clock overlay during sleep mode.
     * Separate from screensaver's showClock setting - user may want different behavior.
     * Default: false
     */
    var sleepShowClock: Boolean
        get() = prefs.getBoolean(KEY_SLEEP_SHOW_CLOCK, false)
        set(value) { prefs.edit().putBoolean(KEY_SLEEP_SHOW_CLOCK, value).commit() }

    /**
     * Reduce hardware brightness while asleep (black overlay sleep mode).
     * Separate from screensaver's reduceBrightnessOnBlack so users can dim
     * differently for sleep vs. screensaver.
     * Default: true
     */
    var reduceBrightnessOnSleep: Boolean
        get() = prefs.getBoolean(KEY_REDUCE_BRIGHTNESS_ON_SLEEP, true)
        set(value) { prefs.edit().putBoolean(KEY_REDUCE_BRIGHTNESS_ON_SLEEP, value).commit() }

    /**
     * Get a human-readable description of the current sleep configuration.
     * Returns "None" if disabled, or a description like "9:30pm / 6:30am" or "30 min inactive".
     *
     * @param use24HourClock Whether to display times in 24-hour format
     */
    fun getSleepDescription(use24HourClock: Boolean = false): String {
        if (!sleepEnabled) return "None"

        return when (sleepMethod) {
            SLEEP_METHOD_SCHEDULE -> {
                val sleepFormatted = formatTimeForDisplay(sleepTime, use24HourClock)
                val wakeFormatted = formatTimeForDisplay(wakeTime, use24HourClock)
                "$sleepFormatted / $wakeFormatted"
            }
            SLEEP_METHOD_INACTIVITY -> {
                val secs = inactivityTimeout
                when {
                    secs < 60 -> "${secs}s inactive"
                    secs < 3600 -> {
                        val mins = secs / 60
                        "${mins}m inactive"
                    }
                    else -> {
                        val hours = secs / 3600
                        val remainMins = (secs % 3600) / 60
                        if (remainMins > 0) "${hours}h ${remainMins}m inactive"
                        else "${hours}h inactive"
                    }
                }
            }
            else -> "None"
        }
    }

    /**
     * Format a 24-hour time string for display.
     * E.g., "22:00" -> "10:00pm" (12-hour) or "22:00" (24-hour)
     */
    private fun formatTimeForDisplay(time24: String, use24HourClock: Boolean): String {
        return try {
            val parts = time24.split(":")
            if (parts.size != 2) return time24

            val hour = parts[0].toIntOrNull() ?: return time24
            val minute = parts[1].toIntOrNull() ?: return time24

            if (use24HourClock) {
                // 24-hour format: 22:00
                "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
            } else {
                // 12-hour format: 10:00pm
                val amPm = if (hour >= 12) "pm" else "am"
                val hour12 = when {
                    hour == 0 -> 12
                    hour > 12 -> hour - 12
                    else -> hour
                }

                if (minute == 0) {
                    "$hour12$amPm"
                } else {
                    "$hour12:${minute.toString().padStart(2, '0')}$amPm"
                }
            }
        } catch (e: Exception) {
            time24
        }
    }

    // ========== App Startup/Lifecycle ==========

    /** Start app on device boot. Default: false */
    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, false)
        set(value) { prefs.edit().putBoolean(KEY_START_ON_BOOT, value).commit() }

    /** Keep screen on while app is running. Default: true */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) { prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).commit() }

    /** Launch app when device wakes from sleep (USER_PRESENT intent). Default: false */
    var launchOnWake: Boolean
        get() = prefs.getBoolean(KEY_LAUNCH_ON_WAKE, false)
        set(value) { prefs.edit().putBoolean(KEY_LAUNCH_ON_WAKE, value).commit() }

    /** Track if app was in Screen Off mode (hardware screen off via lockNow). */
    var wasInScreenOffMode: Boolean
        get() = prefs.getBoolean(KEY_WAS_IN_SCREEN_OFF_MODE, false)
        set(value) { prefs.edit().putBoolean(KEY_WAS_IN_SCREEN_OFF_MODE, value).commit() }

    /** Hardware screen off - uses Device Admin to actually turn off display hardware. Default: false (black overlay) */
    var hardwareScreenOff: Boolean
        get() = prefs.getBoolean(KEY_HARDWARE_SCREEN_OFF, false)
        set(value) { prefs.edit().putBoolean(KEY_HARDWARE_SCREEN_OFF, value).commit() }

    /**
     * Persisted snapshot of whether motion-wake is using the camera (CAMERA or
     * FACE detection mode). Written by DashieServiceManager.setMotionWakeCameraActive()
     * and read by DashieApiService.onStartCommand() so the FGS camera type
     * (and the PARTIAL_WAKE_LOCK) can be restored on START_STICKY restart after
     * the system reaps the service.
     *
     * Without this restore, OEM camera policies (Samsung, Mio kiosk ROM, newer
     * Fire OS) revoke camera access ~5s after the activity backgrounds, killing
     * face/motion wake until the user restarts the app — the "5 times in a row
     * fine, 6th not" symptom.
     */
    var motionWakeCameraActive: Boolean
        get() = prefs.getBoolean(KEY_MOTION_WAKE_CAMERA_ACTIVE, false)
        set(value) { prefs.edit().putBoolean(KEY_MOTION_WAKE_CAMERA_ACTIVE, value).commit() }
}

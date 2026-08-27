package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * Alert sound preferences for Dashie Kiosk.
 *
 * Manages the volume level for notification chimes and timer alarms.
 * Uses the same SharedPreferences file as HalitePreferences.
 */
class AlertPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_lite_prefs"
        private const val KEY_ALERT_VOLUME = "alert_volume"
        private const val DEFAULT_ALERT_VOLUME = 0.40f
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Alert volume as 0.0..1.0 float (default 40%) */
    var alertVolume: Float
        get() = prefs.getFloat(KEY_ALERT_VOLUME, DEFAULT_ALERT_VOLUME)
        set(value) { prefs.edit().putFloat(KEY_ALERT_VOLUME, value.coerceIn(0f, 1f)).commit() }
}

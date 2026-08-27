package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * Location tracking and travel time preferences.
 *
 * Manages:
 * - Location tracking on/off and map display
 * - Travel time estimation config
 * - Early arrival defaults by event type
 * - Traffic model selection
 *
 * NOTE (2026-04-23): These SharedPrefs are currently DEAD WRITES from the
 * Kotlin runtime's perspective. The Kotlin Settings schema writes to them
 * (via the schema value provider) and reads them back to render the UI,
 * but no other Kotlin code consumes these values. The actual location
 * tracking / travel-time / notification behavior is driven by the JS
 * layer reading from settingsStore + user_devices.locations.
 *
 * Planned: these fields are queued for migration from device-level to
 * account-level in the Kotlin-aware account-tier work. Once that lands,
 * the Kotlin schema UI will read the current values from JS via a bridge
 * and this class can be deleted.
 */
class LocationsPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_locations_prefs"

        private const val KEY_TRACKING_ENABLED = "tracking_enabled"
        private const val KEY_SHOW_RADIUS_CIRCLES = "show_radius_circles"
        private const val KEY_TRAVEL_TIME_ENABLED = "travel_time_enabled"
        private const val KEY_EARLY_ARRIVAL_GAMES = "early_arrival_games"
        private const val KEY_EARLY_ARRIVAL_PRACTICES = "early_arrival_practices"
        private const val KEY_EARLY_ARRIVAL_OTHER = "early_arrival_other"
        private const val KEY_TRAFFIC_MODEL = "traffic_model"
        private const val KEY_NOTIFICATION_SOUNDS = "notification_sounds"

        const val DEFAULT_EARLY_ARRIVAL_MINUTES = 15
        const val DEFAULT_TRAFFIC_MODEL = "best_guess"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Boolean fields ──────────────────────────────────────────────────

    // Default flipped to false — Locations is opt-in. Was true, which
    // caused new accounts to see Locations card "ON" in Control Center
    // before the user had configured anything.
    var trackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_TRACKING_ENABLED, value).commit() }

    var showRadiusCircles: Boolean
        get() = prefs.getBoolean(KEY_SHOW_RADIUS_CIRCLES, true)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_RADIUS_CIRCLES, value).commit() }

    var travelTimeEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRAVEL_TIME_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_TRAVEL_TIME_ENABLED, value).commit() }

    var notificationSounds: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_SOUNDS, true)
        set(value) { prefs.edit().putBoolean(KEY_NOTIFICATION_SOUNDS, value).commit() }

    // ── Int fields ──────────────────────────────────────────────────────

    var earlyArrivalGames: Int
        get() = prefs.getInt(KEY_EARLY_ARRIVAL_GAMES, DEFAULT_EARLY_ARRIVAL_MINUTES)
        set(value) { prefs.edit().putInt(KEY_EARLY_ARRIVAL_GAMES, value).commit() }

    var earlyArrivalPractices: Int
        get() = prefs.getInt(KEY_EARLY_ARRIVAL_PRACTICES, DEFAULT_EARLY_ARRIVAL_MINUTES)
        set(value) { prefs.edit().putInt(KEY_EARLY_ARRIVAL_PRACTICES, value).commit() }

    var earlyArrivalOther: Int
        get() = prefs.getInt(KEY_EARLY_ARRIVAL_OTHER, 0)
        set(value) { prefs.edit().putInt(KEY_EARLY_ARRIVAL_OTHER, value).commit() }

    // ── String fields ───────────────────────────────────────────────────

    var trafficModel: String
        get() = prefs.getString(KEY_TRAFFIC_MODEL, DEFAULT_TRAFFIC_MODEL) ?: DEFAULT_TRAFFIC_MODEL
        set(value) { prefs.edit().putString(KEY_TRAFFIC_MODEL, value).commit() }

    // ── Display helpers ─────────────────────────────────────────────────

    fun earlyArrivalDisplay(minutes: Int): String {
        return if (minutes == 0) "None" else "$minutes min"
    }

    fun trafficModelDisplay(): String {
        return when (trafficModel) {
            "best_guess" -> "Best Guess"
            "optimistic" -> "Optimistic"
            "pessimistic" -> "Pessimistic"
            else -> trafficModel
        }
    }
}

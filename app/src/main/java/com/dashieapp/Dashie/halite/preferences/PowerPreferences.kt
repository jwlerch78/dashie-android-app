package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * Typed wrapper around power management SharedPreferences.
 *
 * Uses individual keys in `dashie_power_config` (same file as before for backward
 * compatibility). On first access, migrates any legacy `config_json` blob to
 * individual keys, then deletes the blob.
 *
 * The [PowerManagementWatchdog] and [ControlCenterStateProvider] read from
 * this same class, so all writes are immediately visible.
 */
class PowerPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_power_config"

        // Legacy key (JSON blob) — migrated on first access
        private const val KEY_LEGACY_CONFIG_JSON = "config_json"
        private const val KEY_MIGRATED = "migrated_to_keys"

        // Individual keys
        private const val KEY_ENABLED = "power_enabled"
        private const val KEY_ENTITY_ID = "power_entity_id"
        private const val KEY_PREFER_NIGHT = "power_prefer_night"
        private const val KEY_MIN_THRESHOLD = "power_min_threshold"
        private const val KEY_MAX_THRESHOLD = "power_max_threshold"
        private const val KEY_EMERGENCY_THRESHOLD = "power_emergency_threshold"
        private const val KEY_NIGHT_START = "power_night_start"
        private const val KEY_NIGHT_END = "power_night_end"

        // Defaults (must match PowerManagementWatchdog defaults)
        const val DEFAULT_MIN_THRESHOLD = 20
        const val DEFAULT_MAX_THRESHOLD = 80
        const val DEFAULT_EMERGENCY_THRESHOLD = 10
        const val DEFAULT_NIGHT_START = "22:00"
        const val DEFAULT_NIGHT_END = "06:00"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateFromJsonBlob()
    }

    /**
     * One-time migration from legacy `config_json` blob to individual keys.
     */
    private fun migrateFromJsonBlob() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val raw = prefs.getString(KEY_LEGACY_CONFIG_JSON, null) ?: run {
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            return
        }
        try {
            val obj = org.json.JSONObject(raw)
            prefs.edit()
                .putBoolean(KEY_ENABLED, obj.optBoolean("enabled", false))
                .putString(KEY_ENTITY_ID, obj.optString("entityId", ""))
                .putBoolean(KEY_PREFER_NIGHT, obj.optBoolean("preferNight", false))
                .putInt(KEY_MIN_THRESHOLD, obj.optInt("minThreshold", DEFAULT_MIN_THRESHOLD))
                .putInt(KEY_MAX_THRESHOLD, obj.optInt("maxThreshold", DEFAULT_MAX_THRESHOLD))
                .putInt(KEY_EMERGENCY_THRESHOLD, obj.optInt("emergencyThreshold", DEFAULT_EMERGENCY_THRESHOLD))
                .putString(KEY_NIGHT_START, obj.optString("nightStart", DEFAULT_NIGHT_START))
                .putString(KEY_NIGHT_END, obj.optString("nightEnd", DEFAULT_NIGHT_END))
                .remove(KEY_LEGACY_CONFIG_JSON)
                .putBoolean(KEY_MIGRATED, true)
                .commit()
        } catch (_: Exception) {
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        }
    }

    // ── Boolean fields ──────────────────────────────────────────────────

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).commit() }

    var preferNight: Boolean
        get() = prefs.getBoolean(KEY_PREFER_NIGHT, false)
        set(value) { prefs.edit().putBoolean(KEY_PREFER_NIGHT, value).commit() }

    // ── String fields ───────────────────────────────────────────────────

    var entityId: String
        get() = prefs.getString(KEY_ENTITY_ID, "") ?: ""
        set(value) { prefs.edit().putString(KEY_ENTITY_ID, value).commit() }

    var nightStart: String
        get() = prefs.getString(KEY_NIGHT_START, DEFAULT_NIGHT_START) ?: DEFAULT_NIGHT_START
        set(value) { prefs.edit().putString(KEY_NIGHT_START, value).commit() }

    var nightEnd: String
        get() = prefs.getString(KEY_NIGHT_END, DEFAULT_NIGHT_END) ?: DEFAULT_NIGHT_END
        set(value) { prefs.edit().putString(KEY_NIGHT_END, value).commit() }

    // ── Int fields ──────────────────────────────────────────────────────

    var minThreshold: Int
        get() = prefs.getInt(KEY_MIN_THRESHOLD, DEFAULT_MIN_THRESHOLD)
        set(value) { prefs.edit().putInt(KEY_MIN_THRESHOLD, value).commit() }

    var maxThreshold: Int
        get() = prefs.getInt(KEY_MAX_THRESHOLD, DEFAULT_MAX_THRESHOLD)
        set(value) { prefs.edit().putInt(KEY_MAX_THRESHOLD, value).commit() }

    var emergencyThreshold: Int
        get() = prefs.getInt(KEY_EMERGENCY_THRESHOLD, DEFAULT_EMERGENCY_THRESHOLD)
        set(value) { prefs.edit().putInt(KEY_EMERGENCY_THRESHOLD, value).commit() }

    // ── Bulk import from JS JSON ────────────────────────────────────────

    /**
     * Import all fields from a JS-originated JSON string.
     * Used by the `syncPowerConfig` bridge method.
     */
    fun importFromJson(json: String) {
        val obj = org.json.JSONObject(json)
        prefs.edit()
            .putBoolean(KEY_ENABLED, obj.optBoolean("enabled", false))
            .putString(KEY_ENTITY_ID, obj.optString("entityId", ""))
            .putBoolean(KEY_PREFER_NIGHT, obj.optBoolean("preferNight", false))
            .putInt(KEY_MIN_THRESHOLD, obj.optInt("minThreshold", DEFAULT_MIN_THRESHOLD))
            .putInt(KEY_MAX_THRESHOLD, obj.optInt("maxThreshold", DEFAULT_MAX_THRESHOLD))
            .putInt(KEY_EMERGENCY_THRESHOLD, obj.optInt("emergencyThreshold", DEFAULT_EMERGENCY_THRESHOLD))
            .putString(KEY_NIGHT_START, obj.optString("nightStart", DEFAULT_NIGHT_START))
            .putString(KEY_NIGHT_END, obj.optString("nightEnd", DEFAULT_NIGHT_END))
            .commit()
    }

    /**
     * Export all fields as a JSON string for the JS bridge.
     */
    fun exportToJson(): String {
        return org.json.JSONObject().apply {
            put("enabled", enabled)
            put("entityId", entityId)
            put("preferNight", preferNight)
            put("minThreshold", minThreshold)
            put("maxThreshold", maxThreshold)
            put("emergencyThreshold", emergencyThreshold)
            put("nightStart", nightStart)
            put("nightEnd", nightEnd)
        }.toString()
    }

    // ── Display helpers ─────────────────────────────────────────────────

    /** Friendly name derived from entity_id: "switch.my_plug" → "My Plug" */
    fun entityDisplayName(): String {
        val id = entityId
        if (id.isEmpty()) return "None"
        return id.removePrefix("switch.")
            .replace("_", " ")
            .replaceFirstChar { it.uppercaseChar() }
            .replace(Regex("(?<=\\s)\\w")) { it.value.uppercase() }
    }

    /** Format time string "HH:mm" → "10:00 PM" */
    fun formatTime(time: String): String {
        val parts = time.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return time
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val period = if (h < 12) "AM" else "PM"
        val displayH = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return "$displayH:${m.toString().padStart(2, '0')} $period"
    }
}

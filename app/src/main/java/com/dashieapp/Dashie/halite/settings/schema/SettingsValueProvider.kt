package com.dashieapp.Dashie.halite.settings.schema

import com.dashieapp.Dashie.halite.HalitePreferences

/**
 * Abstraction over preferences for the schema renderer.
 * Maps dot-notation setting keys ("display.darkMode") to actual preference values.
 */
interface SettingsValueProvider {
    fun getBoolean(key: String): Boolean
    fun getString(key: String): String?
    fun getInt(key: String): Int
    fun setBoolean(key: String, value: Boolean)
    fun setString(key: String, value: String)
    fun setInt(key: String, value: Int)
}

/**
 * Concrete implementation backed by [HalitePreferences].
 *
 * Keys use dot-notation: "display.darkMode", "voice.enabled", "sleep.scheduleEnabled", etc.
 * Each key is mapped to the corresponding property on the appropriate sub-preferences object.
 *
 * This is populated incrementally as pages are migrated to schema.
 */
class HaliteSettingsValueProvider(
    private val prefs: HalitePreferences
) : SettingsValueProvider {

    // ── Boolean mappings ────────────────────────────────────────────────

    private val booleanGetters = mutableMapOf<String, () -> Boolean>()
    private val booleanSetters = mutableMapOf<String, (Boolean) -> Unit>()

    // ── String mappings ─────────────────────────────────────────────────

    private val stringGetters = mutableMapOf<String, () -> String?>()
    private val stringSetters = mutableMapOf<String, (String) -> Unit>()

    // ── Int mappings ────────────────────────────────────────────────────

    private val intGetters = mutableMapOf<String, () -> Int>()
    private val intSetters = mutableMapOf<String, (Int) -> Unit>()

    /**
     * Register a boolean setting key.
     */
    fun registerBoolean(key: String, getter: () -> Boolean, setter: (Boolean) -> Unit) {
        booleanGetters[key] = getter
        booleanSetters[key] = setter
    }

    /**
     * Register a string setting key.
     */
    fun registerString(key: String, getter: () -> String?, setter: (String) -> Unit) {
        stringGetters[key] = getter
        stringSetters[key] = setter
    }

    /**
     * Register a READ-ONLY string key — resolvable by schema Conditions / display, but never
     * written through this provider (no setter, so it isn't flagged as settable-without-schema).
     * Use for derived/console-owned keys a condition needs to read (e.g. voice.agentMode).
     */
    fun registerReadOnlyString(key: String, getter: () -> String?) {
        stringGetters[key] = getter
    }

    /**
     * Register an int setting key.
     */
    fun registerInt(key: String, getter: () -> Int, setter: (Int) -> Unit) {
        intGetters[key] = getter
        intSetters[key] = setter
    }

    override fun getBoolean(key: String): Boolean {
        return booleanGetters[key]?.invoke() ?: false
    }

    override fun getString(key: String): String? {
        return stringGetters[key]?.invoke()
    }

    override fun getInt(key: String): Int {
        return intGetters[key]?.invoke() ?: 0
    }

    override fun setBoolean(key: String, value: Boolean) {
        booleanSetters[key]?.invoke(value)
            ?: throw IllegalArgumentException("Unknown boolean key: $key")
    }

    override fun setString(key: String, value: String) {
        stringSetters[key]?.invoke(value)
            ?: throw IllegalArgumentException("Unknown string key: $key")
    }

    override fun setInt(key: String, value: Int) {
        intSetters[key]?.invoke(value)
            ?: throw IllegalArgumentException("Unknown int key: $key")
    }
}

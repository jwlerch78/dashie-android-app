package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * MQTT broker and publishing preferences for Dashie.
 *
 * Manages:
 * - Broker connection (host, port, credentials)
 * - Topic configuration (base topic)
 * - Publishing settings (interval, change-based, HA Discovery)
 * - Per-sensor enable toggles
 *
 * Uses the same SharedPreferences file as HalitePreferences for consistency.
 */
class MqttPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_lite_prefs"

        // Master toggle
        private const val KEY_ENABLED = "mqtt_enabled"

        // Broker connection
        private const val KEY_BROKER_HOST = "mqtt_broker_host"
        private const val KEY_BROKER_PORT = "mqtt_broker_port"
        private const val KEY_USERNAME = "mqtt_username"
        private const val KEY_PASSWORD = "mqtt_password"
        private const val KEY_USE_TLS = "mqtt_use_tls"

        // Topics
        private const val KEY_BASE_TOPIC = "mqtt_base_topic"

        // Publishing
        private const val KEY_PUBLISH_INTERVAL_SEC = "mqtt_publish_interval_sec"
        private const val KEY_PUBLISH_ON_CHANGE = "mqtt_publish_on_change"
        private const val KEY_HA_DISCOVERY = "mqtt_ha_discovery"

        // Sensor toggles
        private const val KEY_SENSOR_BATTERY = "mqtt_sensor_battery"
        private const val KEY_SENSOR_SCREEN = "mqtt_sensor_screen"
        private const val KEY_SENSOR_MOTION = "mqtt_sensor_motion"
        private const val KEY_SENSOR_FACE = "mqtt_sensor_face"
        private const val KEY_SENSOR_LIGHT = "mqtt_sensor_light"
        private const val KEY_SENSOR_AUDIO = "mqtt_sensor_audio"
        private const val KEY_SENSOR_DEVICE_INFO = "mqtt_sensor_device_info"

        // Runtime status (written by DashieMqttService, read by settings UI)
        private const val KEY_LAST_STATUS = "mqtt_last_status"
        private const val KEY_LAST_ERROR = "mqtt_last_error"

        // Defaults
        const val DEFAULT_PORT = 1883
        const val DEFAULT_TLS_PORT = 8883
        const val DEFAULT_PUBLISH_INTERVAL_SEC = 30

        // Status constants
        const val STATUS_DISCONNECTED = "disconnected"
        const val STATUS_CONNECTING = "connecting"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_AUTH_ERROR = "auth_error"
        const val STATUS_ERROR = "error"

        /**
         * Convert a raw Paho exception message into a user-facing string.
         * Generic `MqttException` text (the broker is down or unreachable) is
         * surfaced as "MQTT not available" rather than the raw class name.
         */
        fun friendlyError(raw: String): String {
            if (raw.isEmpty()) return "Connection failed"
            return when {
                raw.contains("not authorized", ignoreCase = true) ||
                raw.contains("bad user", ignoreCase = true) ->
                    "Authentication failed — check username/password"
                raw.contains("Connection refused", ignoreCase = true) ->
                    "Connection refused — check broker host/port"
                raw.contains("UnknownHost", ignoreCase = true) ->
                    "Host unreachable — check broker address"
                raw.contains("Timed out", ignoreCase = true) ||
                raw.contains("timeout", ignoreCase = true) ->
                    "Connection timed out — check network"
                raw.contains("TLS", ignoreCase = true) ||
                raw.contains("SSL", ignoreCase = true) ->
                    "TLS error — check SSL settings"
                raw.contains("MqttException", ignoreCase = true) ||
                raw.contains("Unable to connect", ignoreCase = true) ->
                    "MQTT not available"
                else -> raw
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ========== Master Toggle ==========

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    // ========== Broker Connection ==========

    var brokerHost: String
        get() = prefs.getString(KEY_BROKER_HOST, "") ?: ""
        set(value) { prefs.edit().putString(KEY_BROKER_HOST, value.trim()).apply() }

    var brokerPort: Int
        get() = prefs.getInt(KEY_BROKER_PORT, DEFAULT_PORT)
        set(value) { prefs.edit().putInt(KEY_BROKER_PORT, value.coerceIn(1, 65535)).apply() }

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) { prefs.edit().putString(KEY_USERNAME, value).apply() }

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PASSWORD, value).apply() }

    var useTls: Boolean
        get() = prefs.getBoolean(KEY_USE_TLS, false)
        set(value) { prefs.edit().putBoolean(KEY_USE_TLS, value).apply() }

    /** Whether broker credentials are configured enough to attempt connection */
    val hasConnectionConfig: Boolean
        get() = brokerHost.isNotEmpty()

    // ========== Topics ==========

    var baseTopic: String
        get() = prefs.getString(KEY_BASE_TOPIC, "") ?: ""
        set(value) { prefs.edit().putString(KEY_BASE_TOPIC, value.trim().trimEnd('/')).apply() }

    /**
     * Resolve the base topic, substituting {deviceName} placeholder.
     * Falls back to "dashie/{deviceModel}" if no topic configured.
     */
    fun resolvedBaseTopic(deviceName: String): String {
        val topic = baseTopic.ifEmpty { "dashie/{deviceName}" }
        val safeName = deviceName.lowercase()
            .replace(" ", "-")
            .replace(Regex("[^a-z0-9_-]"), "")
        return topic.replace("{deviceName}", safeName)
    }

    // ========== Publishing ==========

    var publishIntervalSec: Int
        get() = prefs.getInt(KEY_PUBLISH_INTERVAL_SEC, DEFAULT_PUBLISH_INTERVAL_SEC)
        set(value) { prefs.edit().putInt(KEY_PUBLISH_INTERVAL_SEC, value.coerceIn(10, 300)).apply() }

    var publishOnChange: Boolean
        get() = prefs.getBoolean(KEY_PUBLISH_ON_CHANGE, true)
        set(value) { prefs.edit().putBoolean(KEY_PUBLISH_ON_CHANGE, value).apply() }

    var haDiscoveryEnabled: Boolean
        get() = prefs.getBoolean(KEY_HA_DISCOVERY, true)
        set(value) { prefs.edit().putBoolean(KEY_HA_DISCOVERY, value).apply() }

    // ========== Sensor Toggles ==========

    var sensorBattery: Boolean
        get() = prefs.getBoolean(KEY_SENSOR_BATTERY, true)
        set(value) { prefs.edit().putBoolean(KEY_SENSOR_BATTERY, value).apply() }

    var sensorScreen: Boolean
        get() = prefs.getBoolean(KEY_SENSOR_SCREEN, true)
        set(value) { prefs.edit().putBoolean(KEY_SENSOR_SCREEN, value).apply() }

    var sensorMotion: Boolean
        get() = prefs.getBoolean(KEY_SENSOR_MOTION, true)
        set(value) { prefs.edit().putBoolean(KEY_SENSOR_MOTION, value).apply() }

    var sensorFace: Boolean
        get() = prefs.getBoolean(KEY_SENSOR_FACE, true)
        set(value) { prefs.edit().putBoolean(KEY_SENSOR_FACE, value).apply() }

    var sensorLight: Boolean
        get() = prefs.getBoolean(KEY_SENSOR_LIGHT, true)
        set(value) { prefs.edit().putBoolean(KEY_SENSOR_LIGHT, value).apply() }

    var sensorAudio: Boolean
        get() = prefs.getBoolean(KEY_SENSOR_AUDIO, true)
        set(value) { prefs.edit().putBoolean(KEY_SENSOR_AUDIO, value).apply() }

    var sensorDeviceInfo: Boolean
        get() = prefs.getBoolean(KEY_SENSOR_DEVICE_INFO, true)
        set(value) { prefs.edit().putBoolean(KEY_SENSOR_DEVICE_INFO, value).apply() }

    // ========== Runtime Status (written by MQTT service, read by settings UI) ==========

    var lastStatus: String
        get() = prefs.getString(KEY_LAST_STATUS, STATUS_DISCONNECTED) ?: STATUS_DISCONNECTED
        set(value) { prefs.edit().putString(KEY_LAST_STATUS, value).apply() }

    var lastError: String
        get() = prefs.getString(KEY_LAST_ERROR, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LAST_ERROR, value).apply() }

    /** Human-readable status for display */
    val statusDisplayText: String
        get() = when (lastStatus) {
            STATUS_CONNECTED -> "Connected"
            STATUS_CONNECTING -> "Connecting..."
            STATUS_AUTH_ERROR -> "Update authentication"
            STATUS_ERROR -> friendlyError(lastError)
            else -> if (!enabled) "Disabled" else if (!hasConnectionConfig) "Not configured" else "Disconnected"
        }

    /** Whether current status is an error state */
    val isError: Boolean
        get() = lastStatus == STATUS_ERROR || lastStatus == STATUS_AUTH_ERROR
}

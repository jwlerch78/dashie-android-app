package com.dashieapp.Dashie.halite.mqtt

import android.content.Context
import android.os.Build
import android.util.Log
import com.dashieapp.Dashie.halite.preferences.MqttPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Publishes Home Assistant MQTT Discovery config messages.
 *
 * When enabled, publishes retained messages to `homeassistant/...` topics
 * that cause HA's MQTT integration to auto-create entities for this tablet.
 *
 * All entities are grouped under a single HA device using the same `device`
 * block, so they appear together in Settings → Devices.
 *
 * See: https://www.home-assistant.io/integrations/mqtt/#mqtt-discovery
 */
class MqttDiscoveryPublisher(
    private val context: Context,
    private val mqttService: DashieMqttService,
    private val prefs: MqttPreferences
) {
    companion object {
        private const val TAG = "MqttDiscovery"
        private const val DISCOVERY_PREFIX = "homeassistant"
    }

    private val deviceId: String by lazy {
        com.dashieapp.Dashie.util.StableDeviceId.read(context).ifEmpty { "unknown" }
    }

    private val nodeId: String by lazy { "dashie_$deviceId" }

    /**
     * Publish all discovery configs as retained messages.
     * Call once after MQTT connects (and again if discovery is re-enabled).
     */
    fun publishDiscovery() {
        if (!prefs.haDiscoveryEnabled || !mqttService.isConnected) return

        Log.i(TAG, "Publishing HA MQTT Discovery for $nodeId")

        // Sensors
        // NB: binary sensor value_templates lowercase the rendered Python bool —
        // HA's Jinja renders booleans as "True"/"False" (capitalized) but
        // payload_on/off below are lowercase "true"/"false". Without `| lower`
        // the string comparison fails and HA never updates the state.
        if (prefs.sensorBattery) {
            publishSensor("battery", "Battery", "%", "battery",
                "{{ value_json.value }}", "sensor/battery", "diagnostic")
            publishBinarySensor("charging", "Charging", "battery_charging",
                "{{ value_json.charging | lower }}", "sensor/battery", "diagnostic")
        }
        if (prefs.sensorScreen) {
            publishBinarySensor("screen", "Screen", null,
                "{{ value_json.screenOn | lower }}", "sensor/screen")
            publishNumber("brightness", "Brightness", 0, 255, 1,
                "{{ value_json.brightness }}", "sensor/screen", "brightness")
        }
        if (prefs.sensorMotion) {
            publishBinarySensor("motion", "Motion", "motion",
                "{{ value_json.value | lower }}", "sensor/motion")
        }
        if (prefs.sensorFace) {
            publishBinarySensor("face", "Face Detected", "occupancy",
                "{{ value_json.value | lower }}", "sensor/face")
        }
        if (prefs.sensorLight) {
            publishSensor("light", "Ambient Light", "lx", "illuminance",
                "{{ value_json.value }}", "sensor/light", "diagnostic")
        }
        if (prefs.sensorAudio) {
            publishNumber("volume", "Volume", 0, 100, 1,
                "{{ value_json.volume }}", "sensor/audio", "volume")
        }
        if (prefs.sensorDeviceInfo) {
            publishSensor("ram_usage", "RAM Usage", "%", null,
                "{{ value_json.ramUsedPercent }}", "sensor/memory", "diagnostic")
        }

        // Controls (always published — commands work regardless of sensor toggles)
        publishSwitch("screen_switch", "Screen", "sensor/screen", "screenOn")
        publishButton("reload", "Reload")
        publishButton("relaunch", "Relaunch")
        publishButton("clear_cache", "Clear Cache")

        Log.i(TAG, "Discovery published for $nodeId")
    }

    /**
     * Remove all discovery configs (publish empty retained messages).
     * Call when MQTT or discovery is disabled.
     */
    fun removeDiscovery() {
        if (!mqttService.isConnected) return
        Log.i(TAG, "Removing HA MQTT Discovery for $nodeId")

        val entities = listOf(
            "sensor/battery", "binary_sensor/charging", "binary_sensor/screen",
            "number/brightness", "binary_sensor/motion", "binary_sensor/face",
            "sensor/light", "number/volume", "sensor/ram_usage",
            "switch/screen_switch", "button/reload", "button/relaunch",
            "button/clear_cache"
        )
        for (entity in entities) {
            val topic = "$DISCOVERY_PREFIX/$entity/${nodeId}/config"
            mqttService.publishRetained(topic, "")  // Empty retained = remove
        }
    }

    // ── Entity Publishers ────────────────────────────────────────────

    private fun publishSensor(
        objectId: String,
        name: String,
        unit: String?,
        deviceClass: String?,
        valueTemplate: String,
        stateSuffix: String,
        entityCategory: String? = null
    ) {
        val config = baseConfig(objectId, name).apply {
            put("state_topic", "${mqttService.baseTopic}/$stateSuffix")
            put("value_template", valueTemplate)
            if (unit != null) put("unit_of_measurement", unit)
            if (deviceClass != null) put("device_class", deviceClass)
            if (entityCategory != null) put("entity_category", entityCategory)
        }
        val topic = "$DISCOVERY_PREFIX/sensor/${nodeId}_$objectId/config"
        mqttService.publishRetained(topic, config.toString())
    }

    private fun publishBinarySensor(
        objectId: String,
        name: String,
        deviceClass: String?,
        valueTemplate: String,
        stateSuffix: String,
        entityCategory: String? = null
    ) {
        val config = baseConfig(objectId, name).apply {
            put("state_topic", "${mqttService.baseTopic}/$stateSuffix")
            put("value_template", valueTemplate)
            put("payload_on", "true")
            put("payload_off", "false")
            if (deviceClass != null) put("device_class", deviceClass)
            if (entityCategory != null) put("entity_category", entityCategory)
        }
        val topic = "$DISCOVERY_PREFIX/binary_sensor/${nodeId}_$objectId/config"
        mqttService.publishRetained(topic, config.toString())
    }

    private fun publishNumber(
        objectId: String,
        name: String,
        min: Int,
        max: Int,
        step: Int,
        valueTemplate: String,
        stateSuffix: String,
        commandField: String
    ) {
        val config = baseConfig(objectId, name).apply {
            put("state_topic", "${mqttService.baseTopic}/$stateSuffix")
            put("value_template", valueTemplate)
            put("command_topic", "${mqttService.baseTopic}/command")
            put("command_template", "{\"$commandField\": {{ value | int }}}")
            put("min", min)
            put("max", max)
            put("step", step)
        }
        val topic = "$DISCOVERY_PREFIX/number/${nodeId}_$objectId/config"
        mqttService.publishRetained(topic, config.toString())
    }

    private fun publishSwitch(
        objectId: String,
        name: String,
        stateSuffix: String,
        stateField: String
    ) {
        val config = baseConfig(objectId, name).apply {
            put("state_topic", "${mqttService.baseTopic}/$stateSuffix")
            // `| lower` because Jinja renders booleans as "True"/"False" but
            // state_on/off below are lowercase. Same gotcha as the binary
            // sensors above — without lowercasing, the switch state never
            // updates in HA.
            put("value_template", "{{ value_json.$stateField | lower }}")
            put("state_on", "true")
            put("state_off", "false")
            put("command_topic", "${mqttService.baseTopic}/command")
            put("payload_on", "{\"wake\": true}")
            put("payload_off", "{\"wake\": false}")
        }
        val topic = "$DISCOVERY_PREFIX/switch/${nodeId}_$objectId/config"
        mqttService.publishRetained(topic, config.toString())
    }

    private fun publishButton(objectId: String, name: String) {
        val config = baseConfig(objectId, name).apply {
            put("command_topic", "${mqttService.baseTopic}/command")
            put("payload_press", "{\"$objectId\": true}")
        }
        val topic = "$DISCOVERY_PREFIX/button/${nodeId}_$objectId/config"
        mqttService.publishRetained(topic, config.toString())
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun baseConfig(objectId: String, name: String): JSONObject {
        val deviceName = getDeviceName()
        return JSONObject().apply {
            put("name", name)
            put("unique_id", "${nodeId}_$objectId")
            put("default_entity_id", "${nodeId}_$objectId")
            put("availability_topic", "${mqttService.baseTopic}/connection")
            put("payload_available", "online")
            put("payload_not_available", "offline")
            put("device", JSONObject().apply {
                put("identifiers", JSONArray().put(nodeId))
                put("name", deviceName)
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("sw_version", getAppVersion())
            })
        }
    }

    private fun getDeviceName(): String {
        val connPrefs = com.dashieapp.Dashie.halite.preferences.ConnectionPreferences(context)
        val name = connPrefs.deviceFriendlyName
        return name.ifEmpty { "${Build.MANUFACTURER} ${Build.MODEL}" }
    }

    private fun getAppVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }
}

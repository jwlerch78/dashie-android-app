package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SchemaPickerOption
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection
import com.dashieapp.Dashie.halite.settings.schema.SubScreenSchema

/**
 * Schema definition for the MQTT settings detail page.
 *
 * Consolidated layout:
 * - Connection → single nav row to broker sub-page (shows host:port)
 * - Publishing settings inline
 * - Sensors → single nav row to sensors sub-page (shows "7 active, 1 inactive")
 * - Topics inline
 * - Test Connection action
 */
object MqttPageSchema {

    /** Show motion sensor toggle only when motion detection is enabled */
    private val motionVisible = Condition.IsTrue("mqtt.sensorMotion.visible")

    /** Show face sensor toggle only when face detection is enabled */
    private val faceVisible = Condition.IsTrue("mqtt.sensorFace.visible")

    private val mqttEnabled = Condition.IsTrue("mqtt.enabled")

    fun create() = SettingsPageSchema(
        id = "mqtt",
        title = "MQTT Settings",
        sections = listOf(
            enableSection(),
            connectionSection(),
            publishingSection(),
            sensorsSection(),
            topicsSection()
        ),
        subScreens = mapOf(
            "mqtt-broker" to brokerSubScreen(),
            "mqtt-sensors" to sensorsSubScreen()
        )
    )

    // ── Main page sections ───────────────────────────────────────────

    private fun enableSection() = SettingsSection(
        items = listOf(
            SchemaItem.Toggle(
                id = "mqtt_enabled",
                label = "Enable MQTT",
                settingKey = "mqtt.enabled",
                onChanged = "mqttEnabledChanged"
            )
        )
    )

    private fun connectionSection() = SettingsSection(
        header = "Connection",
        visibleWhen = mqttEnabled,
        items = listOf(
            SchemaItem.Navigation(
                id = "mqtt_broker_nav",
                label = "MQTT Connection",
                navigateTo = "mqtt-broker",
                displayValueKey = "mqtt.connectionStatus"
            )
        )
    )

    private fun publishingSection() = SettingsSection(
        header = "Publishing",
        visibleWhen = mqttEnabled,
        items = listOf(
            SchemaItem.Picker(
                id = "mqtt_publish_interval",
                label = "Publish Interval",
                settingKey = "mqtt.publishIntervalSec",
                options = listOf(
                    SchemaPickerOption("10", "10 seconds"),
                    SchemaPickerOption("30", "30 seconds"),
                    SchemaPickerOption("60", "1 minute"),
                    SchemaPickerOption("120", "2 minutes"),
                    SchemaPickerOption("300", "5 minutes")
                )
            ),
            SchemaItem.Toggle(
                id = "mqtt_publish_on_change",
                label = "Publish on Change",
                sublabel = "Immediately publish when sensor values change",
                settingKey = "mqtt.publishOnChange"
            ),
            SchemaItem.Toggle(
                id = "mqtt_ha_discovery",
                label = "HA MQTT Discovery",
                sublabel = "Auto-register sensors and controls in Home Assistant",
                settingKey = "mqtt.haDiscovery",
                onChanged = "mqttDiscoveryChanged"
            )
        )
    )

    private fun sensorsSection() = SettingsSection(
        header = "Sensors",
        visibleWhen = mqttEnabled,
        items = listOf(
            SchemaItem.Navigation(
                id = "mqtt_sensors_nav",
                label = "Sensors to Publish",
                navigateTo = "mqtt-sensors",
                displayValueKey = "mqtt.sensorsSummary"
            )
        )
    )

    private fun topicsSection() = SettingsSection(
        header = "Topics",
        visibleWhen = mqttEnabled,
        footer = "Use {deviceName} as a placeholder for the device name.",
        items = listOf(
            SchemaItem.TextInput(
                id = "mqtt_base_topic",
                label = "Base Topic",
                settingKey = "mqtt.baseTopic",
                placeholder = "dashie/{deviceName}",
                onChanged = "mqttBaseTopicChanged"
            ),
            SchemaItem.Info(
                id = "mqtt_topic_preview",
                label = "Resolved Topic",
                valueKey = "mqtt.resolvedTopic"
            )
        )
    )

    // ── Sub-screens ──────────────────────────────────────────────────

    private fun brokerSubScreen() = SubScreenSchema(
        title = "MQTT Connection",
        parent = "mqtt",
        sections = listOf(
            SettingsSection(
                items = listOf(
                    SchemaItem.TextInput(
                        id = "mqtt_broker_host",
                        label = "Broker Host",
                        settingKey = "mqtt.brokerHost",
                        placeholder = "e.g. 192.168.1.100",

                    ),
                    SchemaItem.TextInput(
                        id = "mqtt_broker_port",
                        label = "Broker Port",
                        settingKey = "mqtt.brokerPort",
                        placeholder = "1883",
                        inputType = "number",

                    ),
                    SchemaItem.TextInput(
                        id = "mqtt_username",
                        label = "Username",
                        settingKey = "mqtt.username",
                        placeholder = "Optional",

                    ),
                    SchemaItem.TextInput(
                        id = "mqtt_password",
                        label = "Password",
                        settingKey = "mqtt.password",
                        placeholder = "Optional",
                        inputType = "password",

                    ),
                    SchemaItem.Toggle(
                        id = "mqtt_use_tls",
                        label = "Use TLS/SSL",
                        settingKey = "mqtt.useTls",

                    ),
                    SchemaItem.Info(
                        id = "mqtt_connection_status",
                        label = "Connection Status",
                        valueKey = "mqtt.connectionStatus",
                        valueColorKey = "mqtt.connectionStatusColor"
                    ),
                    SchemaItem.Action(
                        id = "mqtt_test_connection",
                        label = "Test Connection",
                        action = "mqttTestConnection"
                    )
                )
            )
        )
    )

    private fun sensorsSubScreen() = SubScreenSchema(
        title = "Sensors to Publish",
        parent = "mqtt",
        sections = listOf(
            SettingsSection(
                items = listOf(
                    SchemaItem.Toggle(
                        id = "mqtt_sensor_battery",
                        label = "Battery",
                        settingKey = "mqtt.sensorBattery",
                        onChanged = "mqttSensorsChanged"
                    ),
                    SchemaItem.Toggle(
                        id = "mqtt_sensor_screen",
                        label = "Screen State",
                        settingKey = "mqtt.sensorScreen",
                        onChanged = "mqttSensorsChanged"
                    ),
                    SchemaItem.Toggle(
                        id = "mqtt_sensor_motion",
                        label = "Motion Detection",
                        settingKey = "mqtt.sensorMotion",
                        visibleWhen = motionVisible,
                        onChanged = "mqttSensorsChanged"
                    ),
                    SchemaItem.Toggle(
                        id = "mqtt_sensor_face",
                        label = "Face Detection",
                        settingKey = "mqtt.sensorFace",
                        visibleWhen = faceVisible,
                        onChanged = "mqttSensorsChanged"
                    ),
                    SchemaItem.Toggle(
                        id = "mqtt_sensor_light",
                        label = "Ambient Light",
                        settingKey = "mqtt.sensorLight",
                        onChanged = "mqttSensorsChanged"
                    ),
                    SchemaItem.Toggle(
                        id = "mqtt_sensor_audio",
                        label = "Audio State",
                        settingKey = "mqtt.sensorAudio",
                        onChanged = "mqttSensorsChanged"
                    ),
                    SchemaItem.Toggle(
                        id = "mqtt_sensor_device_info",
                        label = "Device Info",
                        sublabel = "RAM, storage, network",
                        settingKey = "mqtt.sensorDeviceInfo",
                        onChanged = "mqttSensorsChanged"
                    )
                )
            )
        )
    )
}

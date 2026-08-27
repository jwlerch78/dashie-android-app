package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SchemaPickerOption
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection
import com.dashieapp.Dashie.halite.settings.schema.SubScreenSchema

/**
 * Schema definition for the Battery Charging settings page.
 *
 * Controls a Home Assistant smart switch to maintain battery level
 * within configurable thresholds (hysteresis charging).
 *
 * Sections:
 * 1. Enable — master toggle
 * 2. Switch Entity — entity picker, switch status toggle
 * 3. Charging — min/max threshold sliders
 * 4. Night Charging — prefer night toggle, time pickers, emergency threshold
 *    (debug builds only)
 */
object BatteryChargingPageSchema {

    private val enabled = Condition.IsTrue("power.enabled")
    private val hasEntity = Condition.And(listOf(
        Condition.IsTrue("power.enabled"),
        Condition.IsTrue("power.hasEntity")
    ))
    // Night charging hidden — feature not ready for release
    private val nightVisible = Condition.Never
    private val nightExpanded = Condition.Never

    fun create() = SettingsPageSchema(
        id = "battery_charging",
        title = "Battery Charging",
        sections = listOf(
            enableSection(),
            entitySection(),
            chargingSection(),
            nightChargingSection()
        ),
        subScreens = mapOf(
            "night-start-time" to nightStartSubScreen(),
            "night-end-time" to nightEndSubScreen()
        )
    )

    // ── Section 1: Enable ───────────────────────────────────────────────

    private fun enableSection() = SettingsSection(
        items = listOf(
            SchemaItem.Toggle(
                id = "power_enabled",
                label = "Enable Battery Charging Control",
                settingKey = "power.enabled",
                onChanged = "notifyPowerConfigChanged"
            )
        )
    )

    // ── Section 2: Switch Entity ────────────────────────────────────────

    private fun entitySection() = SettingsSection(
        header = "Switch Entity",
        visibleWhen = enabled,
        items = listOf(
            SchemaItem.Navigation(
                id = "power_entity",
                label = "Smart Switch",
                navigateTo = "ext:power_entity_picker",
                displayValueKey = "power.entityDisplay"
            ),
            SchemaItem.Toggle(
                id = "power_switch_status",
                label = "Switch Status",
                settingKey = "power.switchState",
                visibleWhen = hasEntity,
                onChanged = "notifyPowerSwitchToggled"
            ),
            SchemaItem.Info(
                id = "power_battery_status",
                label = "Battery",
                valueKey = "power.batteryDisplay",
                visibleWhen = hasEntity
            ),
            SchemaItem.Info(
                id = "power_status",
                label = "Status",
                valueKey = "power.statusDisplay",
                valueColorKey = "power.statusIsWarning"
            )
        )
    )

    // ── Section 3: Charging Thresholds ──────────────────────────────────

    private fun chargingSection() = SettingsSection(
        header = "Charging",
        visibleWhen = enabled,
        items = listOf(
            SchemaItem.Slider(
                id = "power_min_threshold",
                label = "Min Threshold",
                settingKey = "power.minThreshold",
                min = 0,
                max = 100,
                step = 1,
                unit = "%",
                onChanged = "notifyPowerConfigChanged"
            ),
            SchemaItem.Slider(
                id = "power_max_threshold",
                label = "Max Threshold",
                settingKey = "power.maxThreshold",
                min = 0,
                max = 100,
                step = 1,
                unit = "%",
                onChanged = "notifyPowerConfigChanged"
            )
        )
    )

    // ── Section 4: Night Charging (debug builds only) ───────────────────

    private fun nightChargingSection() = SettingsSection(
        visibleWhen = nightVisible,
        items = listOf(
            SchemaItem.Toggle(
                id = "power_prefer_night",
                label = "Prefer Night Charging",
                settingKey = "power.preferNight",
                onChanged = "notifyPowerConfigChanged"
            ),
            SchemaItem.Navigation(
                id = "power_night_start",
                label = "Start Time",
                navigateTo = "night-start-time",
                displayValueKey = "power.nightStartDisplay",
                visibleWhen = nightExpanded
            ),
            SchemaItem.Navigation(
                id = "power_night_end",
                label = "End Time",
                navigateTo = "night-end-time",
                displayValueKey = "power.nightEndDisplay",
                visibleWhen = nightExpanded
            ),
            SchemaItem.Slider(
                id = "power_emergency_threshold",
                label = "Emergency Threshold",
                settingKey = "power.emergencyThreshold",
                min = 0,
                max = 100,
                step = 1,
                unit = "%",
                onChanged = "notifyPowerConfigChanged",
                visibleWhen = nightExpanded
            )
        ),
        footer = "Charge during the day if battery drops below the emergency threshold."
    )

    // ── Sub-screens: Time Pickers ───────────────────────────────────────

    private fun timeOptions(): List<SchemaPickerOption> {
        val options = mutableListOf<SchemaPickerOption>()
        for (h in 0 until 24) {
            for (m in listOf(0, 30)) {
                val value = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
                val period = if (h < 12) "AM" else "PM"
                val displayH = when {
                    h == 0 -> 12
                    h > 12 -> h - 12
                    else -> h
                }
                val label = "$displayH:${m.toString().padStart(2, '0')} $period"
                options.add(SchemaPickerOption(value, label))
            }
        }
        return options
    }

    private fun nightStartSubScreen() = SubScreenSchema(
        title = "Night Start Time",
        parent = "battery_charging",
        sections = listOf(
            SettingsSection(
                footer = "Charging is deferred until this time when Prefer Night Charging is enabled.",
                items = listOf(
                    SchemaItem.Picker(
                        id = "night_start_picker",
                        label = "Start Time",
                        settingKey = "power.nightStart",
                        options = timeOptions(),
                        onChanged = "notifyPowerConfigChanged"
                    )
                )
            )
        )
    )

    private fun nightEndSubScreen() = SubScreenSchema(
        title = "Night End Time",
        parent = "battery_charging",
        sections = listOf(
            SettingsSection(
                footer = "Night charging window ends at this time.",
                items = listOf(
                    SchemaItem.Picker(
                        id = "night_end_picker",
                        label = "End Time",
                        settingKey = "power.nightEnd",
                        options = timeOptions(),
                        onChanged = "notifyPowerConfigChanged"
                    )
                )
            )
        )
    )
}

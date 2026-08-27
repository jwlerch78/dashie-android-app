package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SchemaPickerOption
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection
import com.dashieapp.Dashie.halite.settings.schema.SubScreenSchema

/**
 * Schema definition for the Sleep / Wake settings page.
 *
 * Matches the JS settings-sleep-page.js layout:
 * 1. General — Sleep Mode picker (Off / Schedule / Timeout)
 * 2. Schedule — Sleep Time, Wake Time, Re-sleep Delay (visible when mode=schedule)
 * 3. Inactivity — Sleep After timeout (visible when mode=inactivity)
 * 4. Options — Screen-Off Behavior, Show Clock, Reduce Brightness, Motion Wake
 *    (visible when sleep is enabled)
 *
 * The "sleep mode" is a composite of sleepEnabled + sleepMethod:
 * - "off" → sleepEnabled=false
 * - "schedule" → sleepEnabled=true, sleepMethod=schedule
 * - "inactivity" → sleepEnabled=true, sleepMethod=inactivity
 * Handled by the `sleep.sleepMode` computed key in SettingsSchemaWiring.
 */
object SleepPageSchema {

    // ── Conditions ──────────────────────────────────────────────────────

    private val isEnabled = Condition.NotEquals("sleep.sleepMode", "off")

    private val scheduleVisible = Condition.Equals("sleep.sleepMode", "schedule")

    private val inactivityVisible = Condition.Equals("sleep.sleepMode", "inactivity")

    /** Screen-off behavior gate for items that only make sense with a black overlay. */
    private val notPowerOff = Condition.NotEquals("display.screenOffBehavior", "power_off")

    /** Advanced tablet-control visibility — see AdvancedPageSchema for the full rationale. */
    private val advancedVisible = Condition.Or(listOf(
        Condition.IsTrue("home_assistant.enabled"),
        Condition.IsTrue("advanced.tabletControlsEnabled")
    ))

    /** Inverse of advancedVisible — non-HA, non-advanced-opt-in users. */
    private val nonAdvanced = Condition.And(listOf(
        Condition.IsFalse("home_assistant.enabled"),
        Condition.IsFalse("advanced.tabletControlsEnabled")
    ))

    /** TV devices don't expose brightness controls, so hide brightness items. */
    private val notTv = Condition.IsFalse("device.isTv")

    fun create() = SettingsPageSchema(
        id = "sleep",
        title = "Sleep / Wake",
        sections = listOf(
            generalSection(),
            scheduleSection(),
            inactivitySection(),
            optionsSection()
        ),
        subScreens = emptyMap()
    )

    // ── Section 1: General ──────────────────────────────────────────────
    //
    // Advanced users get the full Off / Schedule / Timeout picker.
    // Non-advanced users get a simple on/off toggle that always uses
    // scheduled sleep (Timeout is hidden — it's an advanced feature).

    private fun generalSection() = SettingsSection(
        items = listOf(
            SchemaItem.Picker(
                id = "sleep_mode",
                label = "Sleep Mode",
                settingKey = "sleep.sleepMode",
                options = listOf(
                    SchemaPickerOption("off", "Off"),
                    SchemaPickerOption("schedule", "Schedule"),
                    SchemaPickerOption("inactivity", "Timeout")
                ),
                onChanged = "notifySleepConfigChanged",
                visibleWhen = advancedVisible
            ),
            SchemaItem.Toggle(
                id = "sleep_schedule_enabled",
                label = "Sleep Mode",
                sublabel = "Sleep on a daily schedule",
                settingKey = "sleep.scheduleEnabled",
                onChanged = "notifySleepConfigChanged",
                visibleWhen = nonAdvanced
            )
        )
    )

    // ── Section 2: Schedule ─────────────────────────────────────────────

    // Schedule section is visible whenever the user is in schedule mode OR
    // when they are a non-advanced user (where the section stays visible but
    // grays out while sleep is off, since their toggle controls schedule
    // directly). For advanced users in Timeout / Off mode it stays hidden.
    private fun scheduleSection() = SettingsSection(
        header = "Schedule",
        visibleWhen = Condition.Or(listOf(nonAdvanced, scheduleVisible)),
        items = listOf(
            SchemaItem.Picker(
                id = "sleep_time_picker",
                label = "Sleep Time",
                settingKey = "sleep.sleepTime",
                options = timeOptions(),
                onChanged = "notifySleepConfigChanged",
                enabledWhen = isEnabled
            ),
            SchemaItem.Picker(
                id = "wake_time_picker",
                label = "Wake Time",
                settingKey = "sleep.wakeTime",
                options = timeOptions(),
                onChanged = "notifySleepConfigChanged",
                enabledWhen = isEnabled
            ),
            SchemaItem.Picker(
                id = "resleep_delay_picker",
                label = "Re-sleep Delay",
                sublabel = "Time for screen to go back to sleep after user wakes",
                settingKey = "sleep.resleepTimeout",
                options = listOf(
                    SchemaPickerOption("0", "Immediate"),
                    SchemaPickerOption("1", "1 min"),
                    SchemaPickerOption("2", "2 min"),
                    SchemaPickerOption("5", "5 min"),
                    SchemaPickerOption("10", "10 min"),
                    SchemaPickerOption("15", "15 min"),
                    SchemaPickerOption("30", "30 min"),
                    SchemaPickerOption("60", "1 hour")
                ),
                onChanged = "notifySleepConfigChanged",
                enabledWhen = isEnabled
            )
        )
    )

    // ── Section 3: Inactivity ───────────────────────────────────────────

    private fun inactivitySection() = SettingsSection(
        header = "Inactivity",
        visibleWhen = inactivityVisible,
        items = listOf(
            SchemaItem.Picker(
                id = "inactivity_timeout_picker",
                label = "Sleep After",
                settingKey = "sleep.inactivityTimeout",
                options = listOf(
                    SchemaPickerOption("30", "30 sec"),
                    SchemaPickerOption("60", "1 min"),
                    SchemaPickerOption("120", "2 min"),
                    SchemaPickerOption("180", "3 min"),
                    SchemaPickerOption("300", "5 min"),
                    SchemaPickerOption("600", "10 min"),
                    SchemaPickerOption("900", "15 min"),
                    SchemaPickerOption("1800", "30 min"),
                    SchemaPickerOption("3600", "1 hour"),
                    SchemaPickerOption("7200", "2 hours"),
                    SchemaPickerOption("14400", "4 hours"),
                    SchemaPickerOption("28800", "8 hours")
                ),
                onChanged = "notifySleepConfigChanged",
                visibleWhen = inactivityVisible
            )
        )
    )

    // ── Section 4: Options ──────────────────────────────────────────────

    // Options section follows the same shape as Schedule: visible for
    // non-advanced users regardless of sleep state (grayed when off), and
    // for advanced users only when sleep is enabled.
    private fun optionsSection() = SettingsSection(
        header = "Options",
        visibleWhen = Condition.Or(listOf(nonAdvanced, isEnabled)),
        items = listOf(
            SchemaItem.Picker(
                id = "screen_off_behavior_picker",
                label = "Screen-Off Behavior",
                settingKey = "display.screenOffBehavior",
                options = listOf(
                    SchemaPickerOption("black_overlay", "Black Overlay"),
                    SchemaPickerOption("power_off", "Power Off Screen")
                ),
                onChanged = "notifyScreenOffChanged",
                visibleWhen = advancedVisible,
                enabledWhen = isEnabled
            ),
            SchemaItem.Toggle(
                id = "sleep_show_clock",
                label = "Show Clock During Sleep",
                settingKey = "sleep.sleepShowClock",
                onChanged = "notifySleepConfigChanged",
                visibleWhen = notPowerOff,
                enabledWhen = isEnabled
            ),
            SchemaItem.Toggle(
                id = "sleep_reduce_brightness",
                label = "Reduce Brightness While Asleep",
                settingKey = "sleep.reduceBrightnessOnSleep",
                onChanged = "notifySleepConfigChanged",
                visibleWhen = Condition.And(listOf(notPowerOff, notTv)),
                enabledWhen = isEnabled
            ),
            SchemaItem.Toggle(
                id = "sleep_motion_wake",
                label = "Motion Wake",
                settingKey = "sleep.motionWakeForSleep",
                onChanged = "notifySleepConfigChanged",
                visibleWhen = advancedVisible,
                enabledWhen = isEnabled
            )
        )
    )

    // ── Helpers ────────────────────────────────────────────────────────

    /** Generate 48 half-hour time options (12:00 AM through 11:30 PM). */
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
}

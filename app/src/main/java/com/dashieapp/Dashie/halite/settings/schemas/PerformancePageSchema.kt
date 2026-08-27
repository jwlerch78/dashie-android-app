package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SchemaPickerOption
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection
import com.dashieapp.Dashie.halite.settings.schema.SubScreenSchema

/**
 * Schema definition for the Performance & Diagnostics settings page.
 *
 * Sections:
 * 1. Diagnostics — Performance overlay toggle, send diagnostics/crash report
 * 2. Dashboard Refresh — Periodic refresh toggle, interval picker, daily schedule
 * 3. Memory Pressure Refresh — Threshold-based refresh, RTSP restart, idle/critical thresholds
 * 4. WebSocket Connection — Auto-reload stale, battery optimization
 */
object PerformancePageSchema {

    private val periodicEnabled = Condition.IsTrue("performance.stealthRefreshEnabled")
    private val thresholdEnabled = Condition.IsTrue("performance.emergencyRecoveryEnabled")

    /** Advanced tablet-control visibility — see AdvancedPageSchema for the full rationale. */
    private val advancedVisible = Condition.Or(listOf(
        Condition.IsTrue("home_assistant.enabled"),
        Condition.IsTrue("advanced.tabletControlsEnabled")
    ))

    fun create() = SettingsPageSchema(
        id = "performance",
        title = "Performance",
        sections = listOf(
            diagnosticsSection(),
            dashboardRefreshSection(),
            memoryPressureSection(),
            websocketSection()
        ),
        subScreens = mapOf(
            "perf-refresh-interval" to refreshIntervalSubScreen(),
            "perf-daily-refresh" to dailyRefreshSubScreen(),
            "perf-auto-reload" to autoReloadSubScreen(),
            "perf-idle-threshold" to idleThresholdSubScreen(),
            "perf-critical-threshold" to criticalThresholdSubScreen()
        )
    )

    // ── Section 1: Diagnostics ────────────────────────────────────────

    private fun diagnosticsSection() = SettingsSection(
        header = "Diagnostics",
        items = listOf(
            SchemaItem.Toggle(
                id = "performance_overlay",
                label = "Performance Overlay",
                settingKey = "performance.overlayEnabled",
                onChanged = "performanceToggleOverlay",
                visibleWhen = advancedVisible
            ),
            SchemaItem.Action(
                id = "send_diagnostics",
                label = "Send Diagnostics",
                action = "performanceSendDiagnostics"
            ),
            SchemaItem.Action(
                id = "send_crash_report",
                label = "Send Crash Report",
                action = "performanceSendCrashReport",
                visibleWhen = Condition.IsTrue("performance.hasCrashReport")
            )
        )
    )

    // ── Section 2: Dashboard Refresh ──────────────────────────────────

    private fun dashboardRefreshSection() = SettingsSection(
        header = "Dashboard Refresh",
        items = listOf(
            SchemaItem.Toggle(
                id = "periodic_refresh",
                label = "Periodic Refresh",
                settingKey = "performance.stealthRefreshEnabled",
                defaultValue = true,
                onChanged = "notifyStealthRefreshChanged"
            ),
            SchemaItem.Navigation(
                id = "daily_refresh",
                label = "Daily Scheduled",
                navigateTo = "perf-daily-refresh",
                displayValueKey = "performance.dailyRefreshDisplay",
                visibleWhen = periodicEnabled
            ),
            SchemaItem.Navigation(
                id = "refresh_interval",
                label = "During Screensaver/Sleep",
                navigateTo = "perf-refresh-interval",
                displayValueKey = "performance.refreshIntervalDisplay",
                directPicker = true,
                visibleWhen = periodicEnabled
            )
        )
    )

    // ── Section 3: Memory Pressure Refresh ────────────────────────────

    private fun memoryPressureSection() = SettingsSection(
        header = "Memory Pressure Refresh",
        visibleWhen = advancedVisible,
        items = listOf(
            SchemaItem.Toggle(
                id = "threshold_refresh",
                label = "Threshold-based Refresh",
                settingKey = "performance.emergencyRecoveryEnabled",
                onChanged = "performanceRefreshThresholds"
            ),
            SchemaItem.Toggle(
                id = "restart_rtsp_on_memory",
                label = "Restart RTSP on Memory Pressure",
                settingKey = "performance.restartRtspOnMemory",
                visibleWhen = thresholdEnabled
            ),
            SchemaItem.Navigation(
                id = "idle_threshold",
                label = "Idle Refresh Threshold",
                navigateTo = "perf-idle-threshold",
                displayValueKey = "performance.idleThresholdDisplay",
                visibleWhen = thresholdEnabled
            ),
            SchemaItem.Navigation(
                id = "critical_threshold",
                label = "Critical Refresh Threshold",
                navigateTo = "perf-critical-threshold",
                displayValueKey = "performance.criticalThresholdDisplay",
                visibleWhen = thresholdEnabled
            )
        )
    )

    // ── Section 4: WebSocket Connection ───────────────────────────────

    private fun websocketSection() = SettingsSection(
        header = "WebSocket Connection",
        visibleWhen = advancedVisible,
        items = listOf(
            SchemaItem.Navigation(
                id = "auto_reload",
                label = "Auto-Reload Stale",
                navigateTo = "perf-auto-reload",
                displayValueKey = "performance.autoReloadDisplay",
                directPicker = true
            ),
            SchemaItem.Navigation(
                id = "battery_optimization",
                label = "Battery Optimization",
                navigateTo = "ext:battery_optimization",
                displayValueKey = "performance.batteryOptDisplay"
            ),
            SchemaItem.Toggle(
                id = "wifi_lock",
                label = "WiFi Lock",
                settingKey = "performance.wifiLockEnabled",
                defaultValue = true,
                onChanged = "performanceWifiLockChanged"
            )
        )
    )

    // ── Sub-screens ───────────────────────────────────────────────────

    private fun refreshIntervalSubScreen() = SubScreenSchema(
        title = "Refresh Interval",
        parent = "performance",
        sections = listOf(
            SettingsSection(
                items = listOf(
                    SchemaItem.Picker(
                        id = "refresh_interval_picker",
                        label = "During Screensaver/Sleep",
                        settingKey = "performance.stealthRefreshInterval",
                        options = listOf(
                            SchemaPickerOption("0", "Disabled"),
                            SchemaPickerOption("15", "15 minutes"),
                            SchemaPickerOption("30", "30 minutes"),
                            SchemaPickerOption("60", "1 hour"),
                            SchemaPickerOption("120", "2 hours"),
                            SchemaPickerOption("180", "3 hours"),
                            SchemaPickerOption("240", "4 hours"),
                            SchemaPickerOption("360", "6 hours"),
                            SchemaPickerOption("480", "8 hours")
                        ),
                        onChanged = "notifyStealthRefreshChanged"
                    )
                )
            )
        )
    )

    private fun dailyRefreshSubScreen() = SubScreenSchema(
        title = "Daily Scheduled Refresh",
        parent = "performance",
        sections = listOf(
            SettingsSection(
                header = "Morning",
                items = (1..12).map { hour ->
                    SchemaItem.Toggle(
                        id = "daily_hour_$hour",
                        label = formatHourLabel(hour),
                        settingKey = "performance.dailyRefreshHour$hour"
                    )
                }
            ),
            SettingsSection(
                header = "Afternoon / Evening",
                items = (13..24).map { hour ->
                    SchemaItem.Toggle(
                        id = "daily_hour_$hour",
                        label = formatHourLabel(hour),
                        settingKey = "performance.dailyRefreshHour$hour"
                    )
                }
            )
        )
    )

    private fun autoReloadSubScreen() = SubScreenSchema(
        title = "Auto-Reload Stale",
        parent = "performance",
        sections = listOf(
            SettingsSection(
                items = listOf(
                    SchemaItem.Picker(
                        id = "auto_reload_picker",
                        label = "Reload After Disconnect",
                        settingKey = "performance.autoReloadMinutes",
                        options = listOf(
                            SchemaPickerOption("0", "Disabled"),
                            SchemaPickerOption("1", "1 minute"),
                            SchemaPickerOption("2", "2 minutes"),
                            SchemaPickerOption("3", "3 minutes"),
                            SchemaPickerOption("5", "5 minutes"),
                            SchemaPickerOption("10", "10 minutes"),
                            SchemaPickerOption("15", "15 minutes"),
                            SchemaPickerOption("30", "30 minutes")
                        )
                    )
                )
            )
        )
    )

    private fun idleThresholdSubScreen() = SubScreenSchema(
        title = "Idle Refresh Threshold",
        parent = "performance",
        sections = listOf(
            SettingsSection(
                header = "RAM Threshold",
                items = listOf(
                    SchemaItem.Picker(
                        id = "idle_ram_picker",
                        label = "RAM Usage %",
                        settingKey = "performance.idleRamPercent",
                        options = thresholdOptions(82),
                        onChanged = "performanceRefreshThresholds"
                    )
                )
            ),
            SettingsSection(
                header = "Heap Threshold",
                items = listOf(
                    SchemaItem.Picker(
                        id = "idle_heap_picker",
                        label = "Heap Usage %",
                        settingKey = "performance.idleHeapPercent",
                        options = thresholdOptions(80),
                        onChanged = "performanceRefreshThresholds"
                    )
                )
            )
        )
    )

    private fun criticalThresholdSubScreen() = SubScreenSchema(
        title = "Critical Refresh Threshold",
        parent = "performance",
        sections = listOf(
            SettingsSection(
                header = "RAM Threshold",
                items = listOf(
                    SchemaItem.Picker(
                        id = "critical_ram_picker",
                        label = "RAM Usage %",
                        settingKey = "performance.criticalRamPercent",
                        options = thresholdOptions(90),
                        onChanged = "performanceRefreshThresholds"
                    )
                )
            ),
            SettingsSection(
                header = "Heap Threshold",
                items = listOf(
                    SchemaItem.Picker(
                        id = "critical_heap_picker",
                        label = "Heap Usage %",
                        settingKey = "performance.criticalHeapPercent",
                        options = thresholdOptions(88),
                        onChanged = "performanceRefreshThresholds"
                    )
                )
            )
        )
    )

    // ── Helpers ───────────────────────────────────────────────────────

    private fun formatHourLabel(hour: Int): String {
        val h = if (hour == 24) 0 else hour
        val amPm = if (h >= 12) "PM" else "AM"
        val h12 = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
        return "$h12:00 $amPm"
    }

    /**
     * Build threshold percentage options with Disabled + increments from 50-98%.
     * The [defaultPercent] is labeled "(Default)" for clarity.
     */
    private fun thresholdOptions(defaultPercent: Int): List<SchemaPickerOption> {
        val options = mutableListOf(SchemaPickerOption("0", "Disabled"))
        for (pct in listOf(50, 55, 60, 65, 70, 75, 80, 82, 85, 88, 90, 92, 95, 98)) {
            val label = if (pct == defaultPercent) "$pct% (Default)" else "$pct%"
            options.add(SchemaPickerOption(pct.toString(), label))
        }
        return options
    }
}

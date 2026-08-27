package com.dashieapp.Dashie.halite.settings.schema.wiring

import android.content.Context
import com.dashieapp.Dashie.halite.preferences.LockPreferences
import com.dashieapp.Dashie.halite.preferences.MqttPreferences
import com.dashieapp.Dashie.halite.preferences.PerformancePreferences
import com.dashieapp.Dashie.halite.preferences.SleepPreferences
import com.dashieapp.Dashie.halite.settings.schema.HaliteSettingsValueProvider

/**
 * Wiring for MQTT, Advanced, and Performance settings sections.
 * Extracted from SettingsSchemaWiring to reduce file size.
 */
object MqttSettingsWiring {

    // ── Performance ─────────────────────────────────────────────────────

    fun registerPerformancePreferences(vp: HaliteSettingsValueProvider, perfPrefs: PerformancePreferences, context: Context) {
        // ── Advanced page keys (existing) ──
        vp.registerBoolean("performance.autoReloadOnCrash",
            getter = { perfPrefs.autoReloadOnCrash },
            setter = { perfPrefs.autoReloadOnCrash = it }
        )
        vp.registerBoolean("performance.useExactAlarms",
            getter = { perfPrefs.useExactAlarms },
            setter = { perfPrefs.useExactAlarms = it }
        )

        // ── Performance page keys ──

        // Diagnostics
        vp.registerBoolean("performance.overlayEnabled",
            getter = { perfPrefs.performanceOverlayEnabled },
            setter = { perfPrefs.performanceOverlayEnabled = it }
        )
        vp.registerBoolean("performance.hasCrashReport",
            getter = {
                val crashHandler = com.dashieapp.Dashie.halite.diagnostics.CrashHandler
                crashHandler.getPendingCrashCount(context) > 0 &&
                    crashHandler.getAllPendingCrashReports(context).firstOrNull()?.let {
                        System.currentTimeMillis() - it.timestamp <= 24 * 60 * 60 * 1000L
                    } == true
            },
            setter = { } // read-only
        )

        // Dashboard Refresh
        vp.registerBoolean("performance.stealthRefreshEnabled",
            getter = { perfPrefs.stealthRefreshEnabled },
            setter = { perfPrefs.stealthRefreshEnabled = it }
        )
        vp.registerString("performance.stealthRefreshInterval",
            getter = { perfPrefs.stealthRefreshIntervalMinutes.toString() },
            setter = { perfPrefs.stealthRefreshIntervalMinutes = it.toIntOrNull() ?: 60 }
        )
        vp.registerString("performance.refreshIntervalDisplay",
            getter = { formatIntervalLabel(perfPrefs.stealthRefreshIntervalMinutes) },
            setter = { } // read-only
        )
        vp.registerString("performance.dailyRefreshDisplay",
            getter = { formatDailyRefreshLabel(perfPrefs.dailyRefreshHours) },
            setter = { } // read-only
        )

        // Daily refresh hour toggles (1-24)
        for (hour in 1..24) {
            vp.registerBoolean("performance.dailyRefreshHour$hour",
                getter = { hour in perfPrefs.dailyRefreshHours },
                setter = { enabled ->
                    val current = perfPrefs.dailyRefreshHours.toMutableSet()
                    if (enabled) current.add(hour) else current.remove(hour)
                    perfPrefs.dailyRefreshHours = current
                }
            )
        }

        // Memory Pressure Refresh
        vp.registerBoolean("performance.emergencyRecoveryEnabled",
            getter = { perfPrefs.emergencyRecoveryEnabled },
            setter = { perfPrefs.emergencyRecoveryEnabled = it }
        )
        vp.registerBoolean("performance.restartRtspOnMemory",
            getter = { perfPrefs.restartRtspOnMemoryPressure },
            setter = { perfPrefs.restartRtspOnMemoryPressure = it }
        )
        vp.registerString("performance.idleRamPercent",
            getter = { perfPrefs.idleRamPercent.toString() },
            setter = { perfPrefs.idleRamPercent = it.toIntOrNull() ?: 82 }
        )
        vp.registerString("performance.idleHeapPercent",
            getter = { perfPrefs.idleHeapPercent.toString() },
            setter = { perfPrefs.idleHeapPercent = it.toIntOrNull() ?: 80 }
        )
        vp.registerString("performance.criticalRamPercent",
            getter = { perfPrefs.criticalRamPercent.toString() },
            setter = { perfPrefs.criticalRamPercent = it.toIntOrNull() ?: 90 }
        )
        vp.registerString("performance.criticalHeapPercent",
            getter = { perfPrefs.criticalHeapPercent.toString() },
            setter = { perfPrefs.criticalHeapPercent = it.toIntOrNull() ?: 88 }
        )
        vp.registerString("performance.idleThresholdDisplay",
            getter = { formatThresholdLabel(perfPrefs.idleRamPercent, perfPrefs.idleHeapPercent) },
            setter = { } // read-only
        )
        vp.registerString("performance.criticalThresholdDisplay",
            getter = { formatThresholdLabel(perfPrefs.criticalRamPercent, perfPrefs.criticalHeapPercent) },
            setter = { } // read-only
        )

        // WebSocket Connection
        vp.registerString("performance.autoReloadMinutes",
            getter = { perfPrefs.autoReloadStaleMinutes.toString() },
            setter = { perfPrefs.autoReloadStaleMinutes = it.toIntOrNull() ?: 0 }
        )
        vp.registerString("performance.autoReloadDisplay",
            getter = { formatAutoReloadLabel(perfPrefs.autoReloadStaleMinutes) },
            setter = { } // read-only
        )
        vp.registerString("performance.batteryOptDisplay",
            getter = {
                try {
                    if (com.dashieapp.Dashie.halite.BatteryOptimizationHelper.isExempt(context)) "Unrestricted" else "Optimized"
                } catch (_: Exception) { "Unknown" }
            },
            setter = { } // read-only
        )
        vp.registerBoolean("performance.wifiLockEnabled",
            getter = { perfPrefs.wifiLockEnabled },
            setter = { perfPrefs.wifiLockEnabled = it }
        )
    }

    // ── Performance display helpers ──────────────────────────────────────

    private fun formatIntervalLabel(minutes: Int): String = when (minutes) {
        0 -> "Disabled"
        60 -> "1 hour"
        120 -> "2 hours"
        180 -> "3 hours"
        240 -> "4 hours"
        360 -> "6 hours"
        480 -> "8 hours"
        else -> "$minutes min"
    }

    private fun formatDailyRefreshLabel(hours: Set<Int>): String {
        if (hours.isEmpty()) return "Disabled"
        if (hours.size == 1) return formatHourLabel(hours.first())
        if (hours.size > 4) return "${hours.size} times"
        return hours.sorted().joinToString(", ") { formatHourCompact(it) }
    }

    private fun formatHourLabel(hour: Int): String {
        val h = if (hour == 24) 0 else hour
        val amPm = if (h >= 12) "PM" else "AM"
        val h12 = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
        return "$h12:00 $amPm"
    }

    private fun formatHourCompact(hour: Int): String {
        val h = if (hour == 24) 0 else hour
        val amPm = if (h >= 12) "p" else "a"
        val h12 = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
        return "$h12$amPm"
    }

    private fun formatThresholdLabel(ramPercent: Int, heapPercent: Int): String {
        val ramEnabled = ramPercent > 0
        val heapEnabled = heapPercent > 0
        return when {
            ramEnabled && heapEnabled -> "$ramPercent% RAM / $heapPercent% Heap"
            ramEnabled -> "$ramPercent% RAM"
            heapEnabled -> "$heapPercent% Heap"
            else -> "Disabled"
        }
    }

    private fun formatAutoReloadLabel(minutes: Int): String = when (minutes) {
        0 -> "Disabled"
        1 -> "1 minute"
        else -> "$minutes minutes"
    }

    // ── Advanced ─────────────────────────────────────────────────────────

    fun registerAdvancedPreferences(
        vp: HaliteSettingsValueProvider,
        sleepPrefs: SleepPreferences,
        perfPrefs: PerformancePreferences,
        lockPrefs: LockPreferences,
        context: Context
    ) {
        // Device name (editable, synced from HA or user-set)
        val connPrefs = com.dashieapp.Dashie.halite.HalitePreferences(context).connection
        vp.registerString("advanced.deviceFriendlyName",
            getter = {
                val name = connPrefs.deviceFriendlyName
                if (name.isEmpty()) "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" else name
            },
            setter = { connPrefs.deviceFriendlyName = it.trim() }
        )

        // Boot / startup
        vp.registerBoolean("advanced.startOnBoot",
            getter = { sleepPrefs.startOnBoot },
            setter = { sleepPrefs.startOnBoot = it }
        )
        // Gates Dashie's own automatic update checks (not the explicit
        // "Check for Updates" action, and not store auto-update).
        val generalPrefs = com.dashieapp.Dashie.halite.preferences.GeneralPreferences(context)
        vp.registerBoolean("advanced.autoUpdateCheck",
            getter = { generalPrefs.autoUpdateCheckEnabled },
            setter = { generalPrefs.autoUpdateCheckEnabled = it }
        )
        vp.registerBoolean("advanced.useExactAlarms",
            getter = { perfPrefs.useExactAlarms },
            setter = { perfPrefs.useExactAlarms = it }
        )
        vp.registerBoolean("advanced.autoReloadOnCrash",
            getter = { perfPrefs.autoReloadOnCrash },
            setter = { perfPrefs.autoReloadOnCrash = it }
        )

        // Screen locking
        vp.registerBoolean("advanced.pinAppWhenLocked",
            getter = { lockPrefs.pinAppWhenLocked },
            setter = { lockPrefs.pinAppWhenLocked = it }
        )

        // Computed flags (read-only, for conditions)
        vp.registerBoolean("advanced.isFireTablet",
            getter = { com.dashieapp.Dashie.util.DeviceInfoHelper.isFireTablet() },
            setter = { }
        )
        vp.registerBoolean("advanced.isDeviceOwner",
            getter = {
                val dpm = context.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE)
                    as? android.app.admin.DevicePolicyManager
                dpm?.isDeviceOwnerApp(context.packageName) ?: false
            },
            setter = { }
        )

        // Anonymous data sharing
        vp.registerBoolean("advanced.dashboardTelemetryEnabled",
            getter = { perfPrefs.dashboardTelemetryEnabled },
            setter = {
                perfPrefs.dashboardTelemetryEnabled = it
                // Runtime readers gate on enabled && consent; the native toggle is the
                // ongoing consent surface, so grant/revoke consent alongside enable
                // (mirrors JsBridgePerformanceDelegate.setDashboardTelemetryEnabled).
                // FIRST consent is granted by the overlay-JS onboarding "Data Collection"
                // step via JsBridgeHaliteDelegate; SidebarSettingsHelper's dialog copy was
                // deleted 2026-08-05 as a never-wired duplicate of it.
                perfPrefs.dashboardTelemetryConsent = it
            }
        )

        // Detection visibility flags for Advanced page
        // Motion calibration shows when wake mode uses camera/face/rtsp OR HA sensor motion is on
        val screensaverPrefs = com.dashieapp.Dashie.halite.HalitePreferences(context).screensaver
        val camPrefs = com.dashieapp.Dashie.halite.preferences.CameraPreferences(context)
        vp.registerBoolean("advanced.motionDetectionActive",
            getter = {
                val wakeMode = screensaverPrefs.motionWakeMode
                wakeMode == "camera" || wakeMode == "face" || wakeMode == "rtsp" ||
                    camPrefs.haSensorMotionEnabled
            },
            setter = { }
        )
        vp.registerBoolean("advanced.faceDetectionActive",
            getter = {
                screensaverPrefs.motionWakeMode == "face" || camPrefs.haSensorFaceEnabled
            },
            setter = { }
        )

        // MQTT status display for Advanced page nav row
        // Shows nothing if disabled, "MQTT error" in context if error, "MQTT connected" if ok
        val mqttPrefs = MqttPreferences(context)
        vp.registerString("advanced.mqttStatusDisplay",
            getter = {
                if (!mqttPrefs.enabled) ""
                else mqttPrefs.statusDisplayText
            },
            setter = { }
        )
    }

    // ── MQTT ─────────────────────────────────────────────────────────────

    fun registerMqttPreferences(
        vp: HaliteSettingsValueProvider,
        mqtt: MqttPreferences,
        context: Context
    ) {
        // Master toggle (shown on Advanced page)
        vp.registerBoolean("mqtt.enabled",
            getter = { mqtt.enabled },
            setter = { mqtt.enabled = it }
        )

        // Broker connection
        vp.registerString("mqtt.brokerHost",
            getter = { mqtt.brokerHost },
            setter = { mqtt.brokerHost = it }
        )
        vp.registerString("mqtt.brokerPort",
            getter = { mqtt.brokerPort.toString() },
            setter = { mqtt.brokerPort = it.toIntOrNull() ?: MqttPreferences.DEFAULT_PORT }
        )
        vp.registerString("mqtt.username",
            getter = { mqtt.username },
            setter = { mqtt.username = it }
        )
        vp.registerString("mqtt.password",
            getter = { mqtt.password },
            setter = { mqtt.password = it }
        )
        vp.registerBoolean("mqtt.useTls",
            getter = { mqtt.useTls },
            setter = { mqtt.useTls = it }
        )

        // Connection status (read-only display, reads persisted runtime state)
        vp.registerString("mqtt.connectionStatus",
            getter = { mqtt.statusDisplayText },
            setter = { }
        )
        // Connection status color (green=connected, red=error, gray=off)
        vp.registerInt("mqtt.connectionStatusColor",
            getter = {
                when {
                    !mqtt.enabled -> android.graphics.Color.GRAY
                    mqtt.isError -> android.graphics.Color.parseColor("#F44336") // Red
                    mqtt.lastStatus == MqttPreferences.STATUS_CONNECTED ->
                        android.graphics.Color.parseColor("#4CAF50") // Green
                    else -> android.graphics.Color.GRAY
                }
            },
            setter = { }
        )
        // Colored status text for MQTT Connection sublabel on MQTT detail page
        vp.registerString("mqtt.connectionStatusColored",
            getter = { mqtt.statusDisplayText },
            setter = { }
        )

        // Topics
        vp.registerString("mqtt.baseTopic",
            getter = { mqtt.baseTopic.ifEmpty { "dashie/{deviceName}" } },
            setter = { mqtt.baseTopic = it }
        )
        vp.registerString("mqtt.resolvedTopic",
            getter = {
                val connPrefs = com.dashieapp.Dashie.halite.preferences.ConnectionPreferences(context)
                val name = connPrefs.deviceFriendlyName.ifEmpty {
                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                }
                mqtt.resolvedBaseTopic(name)
            },
            setter = { }
        )

        // Publishing
        vp.registerString("mqtt.publishIntervalSec",
            getter = { mqtt.publishIntervalSec.toString() },
            setter = { mqtt.publishIntervalSec = it.toIntOrNull() ?: MqttPreferences.DEFAULT_PUBLISH_INTERVAL_SEC }
        )
        vp.registerBoolean("mqtt.publishOnChange",
            getter = { mqtt.publishOnChange },
            setter = { mqtt.publishOnChange = it }
        )
        vp.registerBoolean("mqtt.haDiscovery",
            getter = { mqtt.haDiscoveryEnabled },
            setter = { mqtt.haDiscoveryEnabled = it }
        )

        // Sensor toggles
        vp.registerBoolean("mqtt.sensorBattery",
            getter = { mqtt.sensorBattery },
            setter = { mqtt.sensorBattery = it }
        )
        vp.registerBoolean("mqtt.sensorScreen",
            getter = { mqtt.sensorScreen },
            setter = { mqtt.sensorScreen = it }
        )
        vp.registerBoolean("mqtt.sensorMotion",
            getter = { mqtt.sensorMotion },
            setter = { mqtt.sensorMotion = it }
        )
        vp.registerBoolean("mqtt.sensorFace",
            getter = { mqtt.sensorFace },
            setter = { mqtt.sensorFace = it }
        )
        vp.registerBoolean("mqtt.sensorLight",
            getter = { mqtt.sensorLight },
            setter = { mqtt.sensorLight = it }
        )
        vp.registerBoolean("mqtt.sensorAudio",
            getter = { mqtt.sensorAudio },
            setter = { mqtt.sensorAudio = it }
        )
        vp.registerBoolean("mqtt.sensorDeviceInfo",
            getter = { mqtt.sensorDeviceInfo },
            setter = { mqtt.sensorDeviceInfo = it }
        )

        // Visibility flags for motion/face sensor toggles
        // (only show if the corresponding detection mode is enabled)
        val cameraPrefs = com.dashieapp.Dashie.halite.preferences.CameraPreferences(context)
        vp.registerBoolean("mqtt.sensorMotion.visible",
            getter = { cameraPrefs.haSensorMotionEnabled || cameraPrefs.cameraMotionEnabled },
            setter = { }
        )
        vp.registerBoolean("mqtt.sensorFace.visible",
            getter = { cameraPrefs.haSensorFaceEnabled },
            setter = { }
        )

        // Broker display value for main MQTT page (shows "host:port" or "Not configured")
        vp.registerString("mqtt.brokerDisplayValue",
            getter = {
                if (mqtt.brokerHost.isEmpty()) "Not configured"
                else "${mqtt.brokerHost}:${mqtt.brokerPort}"
            },
            setter = { }
        )

        // Sensors summary for main MQTT page (e.g., "5 active, 2 inactive")
        vp.registerString("mqtt.sensorsSummary",
            getter = {
                val sensors = listOf(
                    mqtt.sensorBattery, mqtt.sensorScreen, mqtt.sensorMotion,
                    mqtt.sensorFace, mqtt.sensorLight, mqtt.sensorAudio,
                    mqtt.sensorDeviceInfo
                )
                val active = sensors.count { it }
                val inactive = sensors.size - active
                if (inactive == 0) "$active active"
                else "$active active, $inactive inactive"
            },
            setter = { }
        )

        // Pre-fill broker host from HA URL if broker host is empty
        val connPrefs = com.dashieapp.Dashie.halite.preferences.ConnectionPreferences(context)
        if (mqtt.brokerHost.isEmpty() && connPrefs.haBaseUrl.isNotEmpty()) {
            try {
                val haHost = java.net.URI(connPrefs.haBaseUrl).host
                if (!haHost.isNullOrEmpty()) {
                    mqtt.brokerHost = haHost
                }
            } catch (_: Exception) { }
        }
    }
}

package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection

/**
 * Schema definition for the Advanced settings page.
 *
 * 1. System — Performance & Diagnostics, Clear Cache, Open Android Settings, Check for Updates
 * 2. Startup & Recovery — Start on Boot, Use Exact Times, Auto Reload
 * 3. Detection & MQTT — Motion/face calibration, MQTT settings nav
 * 4. Screen Locking — Pin App When Locked, Relinquish Device Owner
 * 5. Advanced Tablet Controls — opt-in toggle (only shown when HA is off)
 * 6. Home Assistant — Enable / disable HA mode
 * 7. Anonymous Data Sharing — Share Performance Data
 */
object AdvancedPageSchema {

    /** Hide Screen Locking on Fire tablets */
    private val notFireTablet = Condition.IsFalse("advanced.isFireTablet")

    /** Show Relinquish Device Owner only when device owner */
    private val isDeviceOwner = Condition.And(listOf(
        notFireTablet,
        Condition.IsTrue("advanced.isDeviceOwner")
    ))

    /** Show motion calibration when motion detection toggle is on */
    private val motionEnabled = Condition.IsTrue("camera.haSensorMotionEnabled")

    /** Show face calibration when face detection toggle is on */
    private val faceEnabled = Condition.IsTrue("camera.haSensorFaceEnabled")

    /**
     * Advanced tablet-control sections are visible when EITHER Home Assistant
     * is enabled (HA users always see them) OR the user has opted in via the
     * Advanced Tablet Controls toggle. Mirror this in other schemas that gate
     * tablet-control surfaces (Display, Sleep, Preferences > Startup, etc.).
     */
    private val advancedVisible = Condition.Or(listOf(
        Condition.IsTrue("home_assistant.enabled"),
        Condition.IsTrue("advanced.tabletControlsEnabled")
    ))

    /** Opt-in toggle is only shown to non-HA users (HA users get advanced for free). */
    private val haDisabled = Condition.IsFalse("home_assistant.enabled")

    fun create() = SettingsPageSchema(
        id = "advanced",
        title = "Advanced",
        sections = listOf(
            systemSection(),
            startupRecoverySection(),
            detectionMqttSection(),
            screenLockingSection(),
            cameraCompatibilitySection(),
            conversationModeSection(),
            tabletControlsSection(),
            homeAssistantSection(),
            dataSharingSection()
        )
    )

    // ── Section: Conversation Mode (experimental) ────────────────────
    // Device-local toggles (VoicePreferences.realtimeAecEnabled / cascadeAecEnabled).
    // Default on; let us A/B the software echo cancellers per device — one per voice
    // path (Live vs cascade dialog/single, WS-A.2). Only meaningful when voice is in
    // use, so hide the whole section unless voice is enabled for this device/account.
    private fun conversationModeSection() = SettingsSection(
        header = "Conversation Mode",
        visibleWhen = Condition.IsTrue("voice.enabled"),
        items = listOf(
            SchemaItem.Toggle(
                id = "realtime_aec",
                label = "Echo Cancellation",
                sublabel = "Stops the assistant from hearing its own voice during a conversation. " +
                    "Turn off only to compare. Applies to this device.",
                settingKey = "voice.realtimeAec"
            ),
            SchemaItem.Toggle(
                id = "cascade_aec",
                label = "Echo Cancellation (Dialog)",
                sublabel = "Stops the assistant from hearing its own reply in dialog and " +
                    "single-question voice. Turn off only to compare. Applies to this device.",
                settingKey = "voice.cascadeAec"
            )
        )
    )

    // ── Section 1: Advanced Tablet Controls (opt-in for non-HA users) ─

    private fun tabletControlsSection() = SettingsSection(
        header = "Advanced Device Controls",
        visibleWhen = haDisabled,
        items = listOf(
            SchemaItem.Toggle(
                id = "tablet_controls_enabled",
                label = "Enable Advanced Controls",
                sublabel = "Show screen locking, wake modes, and other advanced controls",
                settingKey = "advanced.tabletControlsEnabled",
                // Rides the Kotlin-owned HA blob — persist it to user_devices
                // now instead of lagging until next boot (audit §2B).
                onChanged = "notifyHaConfigChanged"
            )
        )
    )

    // ── Section 1: System ─────────────────────────────────────────────

    /**
     * The toggle stops Dashie's own checking on every flavor — that single
     * meaning is what makes it shippable. But on a Play build the STORE can
     * still update the app on its own schedule, and a label reading
     * "Automatic Update Checks: off" beside an app that just updated itself
     * would look like a broken promise. So the Play copy names the limit
     * instead of leaving the user to discover it.
     */
    private fun autoUpdateSublabel(): String? = when (BuildConfig.UPDATE_BACKEND) {
        "play" -> "Dashie won't check on its own. Google Play may still update the app automatically."
        else -> null
    }

    private fun systemSection() = SettingsSection(
        header = "System",
        items = listOf(
            SchemaItem.Navigation(
                id = "device_name",
                label = "Device Name",
                displayValueKey = "advanced.deviceFriendlyName",
                navigateTo = "ext:edit_device_name"
            ),
            SchemaItem.Navigation(
                id = "performance_diagnostics",
                label = "Performance & Diagnostics",
                navigateTo = "ext:performance"
            ),
            SchemaItem.Action(
                id = "clear_cache",
                label = "Clear Cache",
                action = "advancedClearCache"
            ),
            SchemaItem.Action(
                id = "go_android_home",
                label = "Open System Launcher",
                action = "advancedGoAndroidHome",
                // TVs typically launch this app via Leanback / TV launcher
                // and don't expose a meaningful "home screen" to drop to.
                visibleWhen = Condition.IsFalse("device.isTv")
            ),
            SchemaItem.Action(
                id = "open_android_settings",
                label = "Open Device Settings",
                action = "advancedOpenAndroidSettings"
            ),
            SchemaItem.Action(
                id = "change_wifi",
                label = "Change WiFi Network",
                action = "advancedChangeWifi"
            ),
            SchemaItem.Action(
                id = "check_for_update",
                label = "Check for Updates",
                action = "advancedCheckForUpdate"
            ),
            // Gates Dashie's OWN automatic checks (startup, daily refresh, the
            // 6-hourly self-poll) — never the action above, which is explicit
            // user intent. On Play builds the store can still auto-update the
            // app; the subtitle says so rather than letting the label overclaim.
            SchemaItem.Toggle(
                id = "auto_update_check",
                label = "Automatic Update Checks",
                settingKey = "advanced.autoUpdateCheck",
                // Matches GeneralPreferences.autoUpdateCheckEnabled — an existing
                // install must see exactly today's behaviour.
                defaultValue = true,
                sublabel = autoUpdateSublabel()
            )
        )
    )

    // ── Section 2: Startup & Recovery ─────────────────────────────────

    private fun startupRecoverySection() = SettingsSection(
        header = "Startup & Recovery",
        visibleWhen = advancedVisible,
        items = listOf(
            SchemaItem.Toggle(
                id = "start_on_boot",
                label = "Start on Boot",
                settingKey = "advanced.startOnBoot"
            ),
            SchemaItem.Toggle(
                id = "keep_screen_on",
                label = "Keep Screen On",
                settingKey = "sleep.keepScreenOn"
            ),
            SchemaItem.Toggle(
                id = "use_exact_alarms",
                label = "Use Exact Times",
                settingKey = "advanced.useExactAlarms"
            ),
            SchemaItem.Toggle(
                id = "auto_reload_on_crash",
                label = "Auto Reload on Forced Close",
                settingKey = "advanced.autoReloadOnCrash"
            )
        )
    )

    // ── Section 3: Detection & MQTT ──────────────────────────────────

    private fun detectionMqttSection() = SettingsSection(
        header = "Detection & MQTT",
        // HA-only: motion/face detection publishes via HA sensor entities,
        // and MQTT discovery requires an HA broker. Not useful without HA.
        visibleWhen = Condition.IsTrue("home_assistant.enabled"),
        items = listOf(
            SchemaItem.Toggle(
                id = "motion_detection_enabled",
                label = "Motion Detection",
                settingKey = "camera.haSensorMotionEnabled",
                onChanged = "notifyHaSensorConfigChanged"
            ),
            SchemaItem.Navigation(
                id = "calibrate_motion",
                label = "Calibrate Motion Detection",
                navigateTo = "ext:calibrate_motion",
                displayValueKey = "display.motionThresholdDisplay",
                visibleWhen = motionEnabled
            ),
            SchemaItem.Toggle(
                id = "face_detection_enabled",
                label = "Face Detection",
                settingKey = "camera.haSensorFaceEnabled",
                onChanged = "notifyHaSensorConfigChanged"
            ),
            SchemaItem.Navigation(
                id = "calibrate_face",
                label = "Calibrate Face Detection",
                navigateTo = "ext:calibrate_face",
                displayValueKey = "display.faceDistanceDisplay",
                visibleWhen = faceEnabled
            ),
            SchemaItem.Navigation(
                id = "mqtt_settings",
                label = "MQTT Settings",
                navigateTo = "ext:mqtt",
                displayValueKey = "advanced.mqttStatusDisplay"
            )
        )
    )

    // ── Section 4: Screen Locking ─────────────────────────────────────

    private fun screenLockingSection() = SettingsSection(
        header = "Screen Locking",
        visibleWhen = Condition.And(listOf(notFireTablet, advancedVisible)),
        items = listOf(
            SchemaItem.Toggle(
                id = "pin_app_when_locked",
                label = "Pin App When Locked",
                settingKey = "advanced.pinAppWhenLocked"
            ),
            SchemaItem.Action(
                id = "relinquish_device_owner",
                label = "Relinquish Device Owner",
                action = "advancedRelinquishDeviceOwner",
                visibleWhen = isDeviceOwner
            )
        )
    )

    // ── Section: Camera Compatibility ───────────────────────────────

    private fun cameraCompatibilitySection() = SettingsSection(
        header = "Camera Compatibility",
        visibleWhen = advancedVisible,
        items = listOf(
            SchemaItem.Toggle(
                id = "gpu_hardware_layer",
                label = "GPU Hardware Mode",
                sublabel = "Try this if camera card streams go blank a few seconds after loading. Requires app restart.",
                settingKey = "display.gpuHardwareLayerEnabled",
                onChanged = "notifyGpuHardwareLayerChanged"
            )
        )
    )

    // ── Section 5: Anonymous Data Sharing ───────────────────────────

    private fun dataSharingSection() = SettingsSection(
        header = "Anonymous Data Sharing",
        items = listOf(
            SchemaItem.Toggle(
                id = "telemetry",
                label = "Share Performance Data",
                sublabel = "Share dashboard stats to help optimize performance",
                settingKey = "advanced.dashboardTelemetryEnabled"
            )
        )
    )

    // ── Section 6: Home Assistant ───────────────────────────────────
    // Entry point that controls the home_assistant.enabled per-device flag.
    // Gating elsewhere (Display layout/screensaver, Sleep, Startup,
    // Detection & MQTT, Screen Locking, Performance overlay, etc.) keys off
    // this flag via Condition.IsTrue("home_assistant.enabled"). Disable
    // confirmation lives in SettingsCallbackWiring.registerInterceptors.
    //
    // Only shown when the user is signed in to a Dashie cloud account AND
    // not in `ha_only` mode. Without an account (or in ha_only, where the
    // user opted out of paid features), HA is the only thing the dashboard
    // does — there's no dashboard to fall back to if HA is disabled, so the
    // toggle is hidden to prevent the user from putting the device into a
    // useless state. Mirrors the Display page Dashboard-section gate
    // (D.56 follow-up).

    private fun homeAssistantSection() = SettingsSection(
        header = "Home Assistant",
        visibleWhen = Condition.And(listOf(
            Condition.IsTrue("account.isLinked"),
            Condition.IsFalse("subscription.isHaOnly")
        )),
        items = listOf(
            SchemaItem.Toggle(
                id = "ha_enabled",
                label = "Enable Home Assistant",
                sublabel = "Show Home Assistant pages and advanced tablet controls",
                settingKey = "home_assistant.enabled"
            )
        )
    )
}

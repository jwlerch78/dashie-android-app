package com.dashieapp.Dashie.halite.settings.schema.wiring

import android.content.Context
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.AlertPreferences
import com.dashieapp.Dashie.halite.preferences.CameraPreferences
import com.dashieapp.Dashie.halite.preferences.PowerPreferences
import com.dashieapp.Dashie.halite.preferences.VideoFeedPreferences
import com.dashieapp.Dashie.halite.settings.SettingsActivity
import com.dashieapp.Dashie.halite.settings.schema.HaliteSettingsValueProvider

/**
 * Wiring for Camera, Video Feed, and Power settings.
 * Extracted from SettingsSchemaWiring to reduce file size.
 */
object CameraSettingsWiring {

    fun registerCameraPreferences(vp: HaliteSettingsValueProvider, cam: CameraPreferences, context: Context) {
        // Booleans
        vp.registerBoolean("camera.enabled",
            getter = { cam.rtspEnabled },
            setter = { cam.rtspEnabled = it }
        )
        vp.registerBoolean("camera.softwareEncoding",
            getter = { cam.rtspForceSoftwareEncoding },
            setter = { cam.rtspForceSoftwareEncoding = it }
        )
        vp.registerBoolean("camera.portraitStream",
            getter = { cam.rtspPortraitStream },
            setter = { cam.rtspPortraitStream = it }
        )
        vp.registerBoolean("camera.rotateStream180",
            getter = { cam.rtspRotateStream180 },
            setter = { cam.rtspRotateStream180 = it }
        )
        vp.registerBoolean("camera.disableMirrorCorrection",
            getter = { cam.rtspDisableMirrorCorrection },
            setter = { cam.rtspDisableMirrorCorrection = it }
        )
        vp.registerBoolean("camera.haSensorEnabled",
            getter = { cam.haSensorEnabled },
            setter = { cam.haSensorEnabled = it }
        )
        vp.registerBoolean("camera.haSensorMotionEnabled",
            getter = { cam.haSensorMotionEnabled },
            setter = { cam.haSensorMotionEnabled = it }
        )
        vp.registerBoolean("camera.haSensorFaceEnabled",
            getter = { cam.haSensorFaceEnabled },
            setter = { cam.haSensorFaceEnabled = it }
        )

        // Strings — resolution stored as string, FPS stored as int exposed as string for picker
        vp.registerString("camera.resolution",
            getter = { cam.rtspResolution },
            setter = { cam.rtspResolution = it }
        )
        vp.registerString("camera.fps",
            getter = { cam.rtspFps.toString() },
            setter = { cam.rtspFps = it.toIntOrNull() ?: CameraPreferences.DEFAULT_RTSP_FPS }
        )

        // Custom resolution as strings for TextInput
        vp.registerString("camera.customWidth",
            getter = { cam.rtspCustomWidth.toString() },
            setter = { cam.rtspCustomWidth = it.toIntOrNull() ?: 1280 }
        )
        vp.registerString("camera.customHeight",
            getter = { cam.rtspCustomHeight.toString() },
            setter = { cam.rtspCustomHeight = it.toIntOrNull() ?: 720 }
        )

        // Computed display values (read-only)
        vp.registerString("camera.resolutionDisplay",
            getter = { cam.rtspResolutionDisplay },
            setter = { }
        )
        vp.registerString("camera.fpsDisplay",
            getter = { "${cam.rtspFps} fps" },
            setter = { }
        )
        vp.registerString("camera.statusDisplay",
            getter = {
                if (!cam.rtspEnabled) return@registerString "Stopped"
                val hasFailed = SettingsActivity.hasRtspFailed?.invoke() ?: false
                if (hasFailed) {
                    val reason = SettingsActivity.getRtspFailureReason?.invoke()
                    "Error: ${reason ?: "Unknown"}"
                } else {
                    val isRunning = SettingsActivity.isRtspRunning?.invoke() ?: false
                    if (isRunning) "Streaming" else "Connecting…"
                }
            },
            setter = { }
        )
        vp.registerString("camera.streamUrlDisplay",
            getter = {
                val ipAddress = com.dashieapp.Dashie.util.DeviceInfoHelper.getIpAddressCached(context)
                "rtsp://$ipAddress:${cam.rtspPort}"
            },
            setter = { }
        )

        // Motion threshold and face distance from ScreensaverPreferences (read-only display)
        val screensaverPrefs = HalitePreferences(context).screensaver
        vp.registerString("camera.motionThresholdDisplay",
            getter = { "${screensaverPrefs.cameraWakeThresholdDouble}%" },
            setter = { }
        )
        vp.registerString("camera.faceDistanceDisplay",
            getter = {
                com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences.faceDistanceLabel(
                    screensaverPrefs.faceWakeDistance
                )
            },
            setter = { }
        )
    }

    fun registerPowerPreferences(vp: HaliteSettingsValueProvider, power: PowerPreferences, context: Context) {
        // Booleans
        vp.registerBoolean("power.enabled",
            getter = { power.enabled },
            setter = { power.enabled = it }
        )
        vp.registerBoolean("power.preferNight",
            getter = { power.preferNight },
            setter = { power.preferNight = it }
        )

        // Computed boolean flags (read-only, for conditions)
        vp.registerBoolean("power.hasEntity",
            getter = { power.entityId.isNotEmpty() },
            setter = { }
        )
        vp.registerBoolean("power.isDebugBuild",
            getter = { com.dashieapp.Dashie.BuildConfig.DEBUG },
            setter = { }
        )

        // Switch state — read from HA via REST API is too slow for UI rendering,
        // so we track it in a mutable holder updated by the toggle callback.
        vp.registerBoolean("power.switchState",
            getter = { SettingsActivity.powerSwitchState },
            setter = { } // handled by toggle callback
        )

        // Ints
        vp.registerInt("power.minThreshold",
            getter = { power.minThreshold },
            setter = { power.minThreshold = it }
        )
        vp.registerInt("power.maxThreshold",
            getter = { power.maxThreshold },
            setter = { power.maxThreshold = it }
        )
        vp.registerInt("power.emergencyThreshold",
            getter = { power.emergencyThreshold },
            setter = { power.emergencyThreshold = it }
        )

        // Strings
        vp.registerString("power.entityId",
            getter = { power.entityId },
            setter = { power.entityId = it }
        )
        vp.registerString("power.nightStart",
            getter = { power.nightStart },
            setter = { power.nightStart = it }
        )
        vp.registerString("power.nightEnd",
            getter = { power.nightEnd },
            setter = { power.nightEnd = it }
        )

        // Computed display values (read-only)
        vp.registerString("power.entityDisplay",
            getter = { power.entityDisplayName() },
            setter = { }
        )
        vp.registerString("power.nightStartDisplay",
            getter = { power.formatTime(power.nightStart) },
            setter = { }
        )
        vp.registerString("power.nightEndDisplay",
            getter = { power.formatTime(power.nightEnd) },
            setter = { }
        )
        vp.registerString("power.batteryDisplay",
            getter = {
                val level = SettingsActivity.currentBatteryLevel
                val charging = SettingsActivity.currentBatteryCharging
                if (level == null) "..."
                else {
                    val status = if (charging == true) "Charging" else "Discharging"
                    "$level% ($status)"
                }
            },
            setter = { }
        )

        // Status display — computed from enabled + entity + reachability
        vp.registerString("power.statusDisplay",
            getter = {
                when {
                    !power.enabled -> "Disabled"
                    power.entityId.isEmpty() -> "No switch configured"
                    SettingsActivity.powerSwitchReachable == false -> "Switch unreachable"
                    SettingsActivity.powerSwitchReachable == null -> "Checking…"
                    else -> "Active"
                }
            },
            setter = { }
        )
        vp.registerBoolean("power.statusIsWarning",
            getter = {
                power.enabled && (
                    power.entityId.isEmpty() ||
                    SettingsActivity.powerSwitchReachable == false
                )
            },
            setter = { }
        )
    }

    // ── Video Feeds ──────────────────────────────────────────────────

    fun registerVideoFeedPreferences(vp: HaliteSettingsValueProvider, vf: VideoFeedPreferences, alertPrefs: AlertPreferences) {
        // Booleans
        vp.registerBoolean("videoFeed.enabled",
            getter = { vf.enabled },
            setter = { vf.enabled = it }
        )
        vp.registerBoolean("videoFeed.continueWhileActive",
            getter = { vf.getContinueWhileActive() },
            setter = { vf.setContinueWhileActive(it) }
        )
        // NOTE: videoFeed.alertsEnabled (the global "Enable Alerts" master) was
        // removed from the UI — a feed's sound is turned on per-feed via
        // playSoundOnTrigger, so the global master was redundant. The pref +
        // JS-blob passthrough remain for back-compat but there's no settings
        // control (and thus no value provider) for it anymore.

        // Alert volume as int 1-10 (stored as 0.0-1.0 float)
        vp.registerInt("videoFeed.alertVolume",
            getter = { (alertPrefs.alertVolume * 10).toInt().coerceIn(1, 10) },
            setter = { alertPrefs.alertVolume = (it.coerceIn(1, 10) / 10f) }
        )

        // Alert display value (read-only) — the sub-screen is now just the volume
        // slider (the global "Enable Alerts" master was removed; sound is per-feed),
        // so summarize with the current alert volume.
        vp.registerString("videoFeed.alertsDisplay",
            getter = { "${(alertPrefs.alertVolume * 100).toInt()}%" },
            setter = { }
        )

        // Strings (stored as string for picker compatibility)
        vp.registerString("videoFeed.feedLocation",
            getter = { vf.getFeedLocation() },
            setter = { vf.setFeedLocation(it) }
        )
        vp.registerString("videoFeed.feedSize",
            getter = { vf.getFeedSize() },
            setter = { vf.setFeedSize(it) }
        )
        vp.registerString("videoFeed.autoDismissSeconds",
            getter = { vf.getAutoDismissSeconds().toString() },
            setter = { vf.setAutoDismissSeconds(it.toIntOrNull() ?: 30) }
        )
        vp.registerString("videoFeed.cooldownSeconds",
            getter = { vf.getCooldownSeconds().toString() },
            setter = { vf.setCooldownSeconds(it.toIntOrNull() ?: 0) }
        )

        // Dynamic per-feed mode display values (read-only)
        // These are resolved dynamically by ID prefix matching in the value provider.
        // Each feed gets a "videoFeed.feedMode.<feedId>" key registered on-demand.
        val modeLabels = mapOf(
            "subscribed" to "On-demand",
            "trigger" to "Trigger",
            "trigger_alert" to "Trigger + Alert",
            "ignored" to "Ignored"
        )
        for (rule in vf.getRules()) {
            val feedId = rule.optString("id", rule.optString("ruleId", ""))
            if (feedId.isEmpty()) continue
            vp.registerString("videoFeed.feedMode.$feedId",
                getter = { modeLabels[vf.getFeedMode(feedId)] ?: "On-demand" },
                setter = { }
            )
        }
    }
}

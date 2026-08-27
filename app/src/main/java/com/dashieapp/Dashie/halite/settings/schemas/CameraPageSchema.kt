package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SchemaPickerOption
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection

/**
 * Schema definition for the Camera Streaming settings page.
 *
 * Matches the JS settings-camera-page.js layout:
 * 1. Camera Streaming — enable toggle, status, stream URL, resolution, FPS,
 *    software encoding, mirror correction
 * 2. Home Assistant Triggers — HA sensor toggle, motion detection, face detection
 */
object CameraPageSchema {

    // Reusable conditions
    private val streamingEnabled = Condition.IsTrue("camera.enabled")

    fun create() = SettingsPageSchema(
        id = "camera",
        title = "Camera Streaming",
        sections = listOf(
            cameraStreamingSection()
        )
    )

    // ── Section 1: Camera Streaming ─────────────────────────────────────

    private fun cameraStreamingSection() = SettingsSection(
        header = "Camera Streaming",
        items = listOf(
            SchemaItem.Toggle(
                id = "camera_enabled",
                label = "Camera Streaming",
                settingKey = "camera.enabled",
                onChanged = "notifyCameraEnabledChanged"
            ),
            SchemaItem.Info(
                id = "camera_status",
                label = "Status",
                valueKey = "camera.statusDisplay",
                visibleWhen = streamingEnabled
            ),
            SchemaItem.Info(
                id = "camera_stream_url",
                label = "Stream URL",
                valueKey = "camera.streamUrlDisplay",
                visibleWhen = streamingEnabled
            ),
            SchemaItem.Picker(
                id = "camera_resolution",
                label = "Resolution",
                settingKey = "camera.resolution",
                options = listOf(
                    SchemaPickerOption("480p", "480p", "640×480 — Low CPU usage"),
                    SchemaPickerOption("720p", "720p", "1280×720 — Standard HD"),
                    SchemaPickerOption("1080p", "1080p", "1920×1080 — Full HD"),
                    SchemaPickerOption("custom", "Custom", "Set custom dimensions")
                ),
                onChanged = "notifyCameraResolutionChanged",
                visibleWhen = streamingEnabled
            ),
            SchemaItem.Picker(
                id = "camera_fps",
                label = "Frame Rate",
                settingKey = "camera.fps",
                options = listOf(
                    SchemaPickerOption("10", "10 fps", "Lowest CPU usage"),
                    SchemaPickerOption("15", "15 fps", "Good balance (default)"),
                    SchemaPickerOption("24", "24 fps", "Smooth video"),
                    SchemaPickerOption("30", "30 fps", "Highest quality")
                ),
                onChanged = "notifyCameraFpsChanged",
                visibleWhen = streamingEnabled
            ),
            SchemaItem.Toggle(
                id = "camera_software_encoding",
                label = "Software Encoding",
                sublabel = "Use CPU encoding (fixes corrupted video on some devices)",
                settingKey = "camera.softwareEncoding",
                onChanged = "notifyCameraSoftwareEncodingChanged",
                visibleWhen = streamingEnabled
            ),
            SchemaItem.Toggle(
                id = "camera_portrait_stream",
                label = "Portrait Stream",
                sublabel = "Use portrait dimensions (swaps width/height)",
                settingKey = "camera.portraitStream",
                onChanged = "notifyCameraPortraitStreamChanged",
                visibleWhen = streamingEnabled
            ),
            SchemaItem.Toggle(
                id = "camera_rotate_stream_180",
                label = "Rotate Stream 180°",
                sublabel = "Correct flipped orientation",
                settingKey = "camera.rotateStream180",
                onChanged = "notifyCameraRotateStream180Changed",
                visibleWhen = streamingEnabled
            ),
            SchemaItem.Toggle(
                id = "camera_mirror_correction",
                label = "Disable Mirror Correction",
                sublabel = "Disable auto-flip for front camera",
                settingKey = "camera.disableMirrorCorrection",
                onChanged = "notifyCameraMirrorCorrectionChanged",
                visibleWhen = streamingEnabled
            ),
            SchemaItem.Action(
                id = "camera_preview_stream",
                label = "Preview Stream",
                action = "showStreamPreview",
                visibleWhen = streamingEnabled
            )
        )
    )

}

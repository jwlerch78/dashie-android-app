package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection

/**
 * Schema definition for the Wake Mode settings page.
 *
 * Uses Navigation items with dynamic `checked` state (read from preferences each render)
 * so the 4 mode options display inline as checkmark items — no sub-menu needed.
 *
 * Below the mode options, conditional sections show calibration controls:
 * - Camera mode: threshold info + "Calibrate Motion Detection" button
 * - Face mode: distance info + "Calibrate Face Detection" button
 */
object WakeModePageSchema {

    private val isCameraMode = Condition.Equals("display.motionWakeMode", "camera")
    private val isFaceMode = Condition.Equals("display.motionWakeMode", "face")

    private data class ModeOption(
        val id: String,
        val value: String,
        val label: String,
        val sublabel: String
    )

    private val modeOptions = listOf(
        ModeOption("wake_disabled", "disabled", "Touch Only", "Screen only wakes on touch"),
        ModeOption("wake_camera", "camera", "Motion (Camera)", "Uses front camera to detect movement"),
        ModeOption("wake_face", "face", "Face Detection (Camera)", "Wakes when a face is detected nearby"),
        ModeOption("wake_brightness", "brightness", "Brightness Sensor", "Uses ambient light sensor to detect motion")
    )

    /**
     * Create the schema. Called fresh each render so `currentMode` reflects live preferences.
     * @param currentMode The current motionWakeMode value from preferences
     */
    fun create(currentMode: String) = SettingsPageSchema(
        id = "wake_mode",
        title = "Wake Mode",
        sections = listOf(
            modeSelectionSection(currentMode),
            motionCalibrationSection(),
            faceCalibrationSection()
        )
    )

    private fun modeSelectionSection(currentMode: String) = SettingsSection(
        items = modeOptions.map { option ->
            SchemaItem.Navigation(
                id = option.id,
                label = option.label,
                sublabel = option.sublabel,
                navigateTo = "action:set_wake_mode:${option.value}",
                checked = option.value == currentMode
            )
        }
    )

    private fun motionCalibrationSection() = SettingsSection(
        header = "Motion Detection",
        visibleWhen = isCameraMode,
        items = listOf(
            SchemaItem.Info(
                id = "motion_threshold_display",
                label = "Threshold",
                valueKey = "display.motionThresholdDisplay"
            ),
            SchemaItem.Action(
                id = "calibrate_motion",
                label = "Calibrate Motion Detection",
                action = "openMotionCalibration"
            )
        )
    )

    private fun faceCalibrationSection() = SettingsSection(
        header = "Face Detection",
        visibleWhen = isFaceMode,
        items = listOf(
            SchemaItem.Info(
                id = "face_distance_display",
                label = "Distance",
                valueKey = "display.faceDistanceDisplay"
            ),
            SchemaItem.Action(
                id = "calibrate_face",
                label = "Calibrate Face Detection",
                action = "openFaceCalibration"
            )
        )
    )
}

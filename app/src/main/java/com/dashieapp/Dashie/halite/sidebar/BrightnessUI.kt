package com.dashieapp.Dashie.halite.sidebar

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.ImageView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.LightSensorBrightnessController
import com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper
import com.dashieapp.Dashie.edition.brandName

/**
 * Handles all brightness-related UI including:
 * - Brightness slider visibility and state
 * - Permission UI (when WRITE_SETTINGS not granted)
 * - Auto-brightness settings dialog
 * - Dark mode permission dialog
 */
class BrightnessUI(
    private val activity: Activity,
    private val halitePrefs: HalitePreferences
) {
    companion object {
        private const val TAG = "BrightnessUI"
    }

    /**
     * Update brightness slider visibility based on auto-brightness mode.
     * The slider is always visible but changes behavior:
     * - Auto ON: Slider is read-only and shows current brightness (light orange color)
     * - Auto OFF: Slider is interactive for manual adjustment (orange color)
     */
    fun updateBrightnessSliderVisibility(
        autoBrightnessEnabled: Boolean,
        rangeLayout: LinearLayout?,  // No longer used, kept for compatibility
        manualLayout: LinearLayout?,
        seekBarBrightness: SeekBar? = null,
        controlsRow: LinearLayout? = null,
        settingsButton: View? = null,  // Can be ImageView or LinearLayout
        autoValueDisplay: TextView? = null,
        currentBrightness: Int = 50,
        autoSettingsRow: LinearLayout? = null  // New inline settings button row
    ) {
        // Range layout is now in a modal, always hidden in sidebar
        rangeLayout?.visibility = View.GONE
        // Manual layout is always visible
        manualLayout?.visibility = View.VISIBLE

        // Old settings button (gear icon) - no longer used, always hidden
        if (settingsButton is ImageView) {
            settingsButton.visibility = View.GONE
        }

        if (autoBrightnessEnabled) {
            // Auto mode: read-only slider with light orange color
            seekBarBrightness?.isEnabled = false
            seekBarBrightness?.progressTintList = android.content.res.ColorStateList.valueOf(
                activity.getColor(R.color.dashie_orange_light)
            )
            seekBarBrightness?.thumbTintList = android.content.res.ColorStateList.valueOf(
                activity.getColor(R.color.dashie_orange_light)
            )
            // Hide +/- buttons, show Settings button row in auto mode
            controlsRow?.visibility = View.GONE
            autoSettingsRow?.visibility = View.VISIBLE
            // Show brightness % to the right of slider
            autoValueDisplay?.visibility = View.VISIBLE
            autoValueDisplay?.text = "$currentBrightness%"
        } else {
            // Manual mode: interactive slider with orange color
            seekBarBrightness?.isEnabled = true
            seekBarBrightness?.progressTintList = android.content.res.ColorStateList.valueOf(
                activity.getColor(R.color.dashie_orange)
            )
            seekBarBrightness?.thumbTintList = android.content.res.ColorStateList.valueOf(
                activity.getColor(R.color.dashie_orange)
            )
            // Show +/- buttons, hide Settings button row in manual mode
            controlsRow?.visibility = View.VISIBLE
            autoSettingsRow?.visibility = View.GONE
            // Hide auto value display in manual mode (shown in +/- row instead)
            autoValueDisplay?.visibility = View.GONE
        }
    }

    /**
     * Update brightness UI based on permission state.
     * When permission not granted:
     * - Show "Enable Control" instead of Auto toggle
     * - Show slider in read-only mode with disabled color
     * - Hide +/- buttons
     */
    fun updateBrightnessPermissionUI(
        canWrite: Boolean,
        autoLabel: TextView?,
        autoSwitch: Switch?,
        enableControl: TextView?,
        manualLayout: LinearLayout?,
        rangeLayout: LinearLayout?,
        seekBarBrightness: SeekBar?,
        controlsRow: LinearLayout?,
        darkLightToggle: View?  // Now the dark/light mode toggle button
    ) {
        if (canWrite) {
            // Permission granted - show Auto toggle and enable slider
            autoLabel?.visibility = View.VISIBLE
            autoSwitch?.visibility = View.VISIBLE
            enableControl?.visibility = View.GONE

            // Enable slider controls
            seekBarBrightness?.isEnabled = true
            seekBarBrightness?.progressTintList = android.content.res.ColorStateList.valueOf(
                activity.getColor(R.color.dashie_orange)
            )
            seekBarBrightness?.thumbTintList = android.content.res.ColorStateList.valueOf(
                activity.getColor(R.color.dashie_orange)
            )
            controlsRow?.visibility = View.VISIBLE
            darkLightToggle?.alpha = 1.0f
        } else {
            // Permission not granted - show Enable Control and read-only slider
            // But hide Enable Control on TV devices (no touchscreen) since they can't grant the permission
            val hasTouchscreen = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
            autoLabel?.visibility = View.GONE
            autoSwitch?.visibility = View.GONE
            enableControl?.visibility = if (hasTouchscreen) View.VISIBLE else View.GONE

            // Show slider in read-only mode with disabled color
            manualLayout?.visibility = View.VISIBLE
            rangeLayout?.visibility = View.GONE

            // Disable slider and use lighter orange color (like disabled toggles)
            seekBarBrightness?.isEnabled = false
            seekBarBrightness?.progressTintList = android.content.res.ColorStateList.valueOf(
                activity.getColor(R.color.dashie_orange_light)
            )
            seekBarBrightness?.thumbTintList = android.content.res.ColorStateList.valueOf(
                activity.getColor(R.color.dashie_orange_light)
            )
            // Hide +/- buttons when read-only
            controlsRow?.visibility = View.GONE
            darkLightToggle?.alpha = 0.5f
        }
    }

    /**
     * Show brightness permission dialog
     */
    fun showBrightnessPermissionDialog(onEnableNow: () -> Unit) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Brightness Control"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            "To control brightness, ${activity.brandName()} must have \"Allow modify system settings\" permissions."

        dialogView.findViewById<Button>(R.id.buttonNegative).text = "Cancel"
        dialogView.findViewById<Button>(R.id.buttonPositive).text = "Enable Now"

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
            dialog.dismiss()
            onEnableNow()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        DialogHelper.setDefaultFocusOnCancel(dialogView)
    }

    /**
     * Show dark mode permission dialog explaining how to grant WRITE_SECURE_SETTINGS via ADB
     */
    fun showDarkModePermissionDialog() {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Dark Mode Control"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            "Dark mode control requires special permission that must be granted via ADB.\n\n" +
            "Run this command via ADB:\n" +
            "adb shell pm grant ${activity.packageName} android.permission.WRITE_SECURE_SETTINGS"

        dialogView.findViewById<Button>(R.id.buttonNegative).visibility = View.GONE
        dialogView.findViewById<Button>(R.id.buttonPositive).text = "OK"

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        DialogHelper.setDefaultFocusOnConfirm(dialogView)
    }

    /**
     * Show auto brightness settings dialog with min/max sliders and live preview
     */
    fun showAutoBrightnessSettingsDialog(
        callbacks: SettingsCallbacks,
        lightSensorController: LightSensorBrightnessController? = null
    ) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_auto_brightness_settings, null)

        val seekBarMin = dialogView.findViewById<SeekBar>(R.id.seekBarBrightnessMin)
        val seekBarMax = dialogView.findViewById<SeekBar>(R.id.seekBarBrightnessMax)
        val textMinValue = dialogView.findViewById<TextView>(R.id.textBrightnessMinValue)
        val textMaxValue = dialogView.findViewById<TextView>(R.id.textBrightnessMaxValue)

        // Response curve radio group
        val radioGroupCurve = dialogView.findViewById<RadioGroup>(R.id.radioGroupResponseCurve)
        val radioLinear = dialogView.findViewById<RadioButton>(R.id.radioLinear)
        val radioAggressive = dialogView.findViewById<RadioButton>(R.id.radioAggressive)
        val radioGentle = dialogView.findViewById<RadioButton>(R.id.radioGentle)

        // Live sensor display elements
        val textLuxValue = dialogView.findViewById<TextView>(R.id.textLuxValue)
        val textBrightnessOutput = dialogView.findViewById<TextView>(R.id.textBrightnessOutput)
        val brightnessBarFill = dialogView.findViewById<View>(R.id.brightnessBarFill)
        val liveSensorBox = dialogView.findViewById<LinearLayout>(R.id.liveSensorBox)

        // Store original values for cancel/revert
        val originalMin = halitePrefs.display.autoBrightnessMin
        val originalMax = halitePrefs.display.autoBrightnessMax
        val originalCurve = halitePrefs.display.autoBrightnessCurve

        // Initialize with current values
        var currentMin = originalMin
        var currentMax = originalMax
        var currentCurve = originalCurve
        seekBarMin.progress = currentMin
        seekBarMax.progress = currentMax
        textMinValue.text = "$currentMin%"
        textMaxValue.text = "$currentMax%"

        // Initialize response curve selection
        when (currentCurve) {
            HalitePreferences.BRIGHTNESS_CURVE_AGGRESSIVE -> radioAggressive.isChecked = true
            HalitePreferences.BRIGHTNESS_CURVE_GENTLE -> radioGentle.isChecked = true
            else -> radioLinear.isChecked = true
        }

        // Handler for periodic lux updates
        val handler = Handler(Looper.getMainLooper())
        var isDialogActive = true

        // Function to calculate preview brightness (same logic as LightSensorBrightnessController)
        fun calculatePreviewBrightness(lux: Float, min: Int, max: Int, curve: String): Int {
            val luxDark = 10f
            val luxBright = 1000f
            val clampedLux = lux.coerceIn(0f, luxBright)

            val luxRatio = when {
                clampedLux <= luxDark -> 0f
                clampedLux >= luxBright -> 1f
                else -> (clampedLux - luxDark) / (luxBright - luxDark)
            }

            val adjustedRatio = when (curve) {
                HalitePreferences.BRIGHTNESS_CURVE_AGGRESSIVE -> kotlin.math.sqrt(luxRatio.toDouble()).toFloat()
                HalitePreferences.BRIGHTNESS_CURVE_GENTLE -> luxRatio * luxRatio
                else -> luxRatio
            }

            val brightness = min + (adjustedRatio * (max - min))
            return brightness.toInt().coerceIn(min, max)
        }

        // Function to update live sensor display
        fun updateLiveSensorDisplay() {
            val currentLux = lightSensorController?.getCurrentLux() ?: 0f
            val previewBrightness = calculatePreviewBrightness(currentLux, currentMin, currentMax, currentCurve)

            textLuxValue?.text = "${currentLux.toInt()} lux"
            textBrightnessOutput?.text = "$previewBrightness%"

            // Update brightness bar fill width
            brightnessBarFill?.let { fill ->
                val parent = fill.parent as? FrameLayout
                parent?.let { container ->
                    val params = fill.layoutParams
                    params.width = (container.width * previewBrightness / 100)
                    fill.layoutParams = params
                }
            }
        }

        // Schedule periodic updates
        val updateRunnable = object : Runnable {
            override fun run() {
                if (isDialogActive) {
                    updateLiveSensorDisplay()
                    handler.postDelayed(this, 500) // Update every 500ms
                }
            }
        }

        // Show/hide sensor box based on sensor availability
        if (lightSensorController?.isAvailable() == true) {
            liveSensorBox?.visibility = View.VISIBLE
            handler.post(updateRunnable)
        } else {
            liveSensorBox?.visibility = View.GONE
        }

        // Listen for curve changes - update preview immediately
        radioGroupCurve.setOnCheckedChangeListener { _, checkedId ->
            currentCurve = when (checkedId) {
                R.id.radioAggressive -> HalitePreferences.BRIGHTNESS_CURVE_AGGRESSIVE
                R.id.radioGentle -> HalitePreferences.BRIGHTNESS_CURVE_GENTLE
                else -> HalitePreferences.BRIGHTNESS_CURVE_LINEAR
            }
            updateLiveSensorDisplay()
        }

        // Min slider listener - update preview immediately
        seekBarMin.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Ensure min doesn't exceed max
                    currentMin = progress.coerceAtMost(currentMax)
                    if (currentMin != progress) {
                        seekBar?.progress = currentMin
                    }
                    textMinValue.text = "$currentMin%"
                    updateLiveSensorDisplay()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Max slider listener - update preview immediately
        seekBarMax.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Ensure max doesn't go below min
                    currentMax = progress.coerceAtLeast(currentMin)
                    if (currentMax != progress) {
                        seekBar?.progress = currentMax
                    }
                    textMaxValue.text = "$currentMax%"
                    updateLiveSensorDisplay()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .setOnDismissListener {
                isDialogActive = false
                handler.removeCallbacksAndMessages(null)
                callbacks.onDialogDismissed()
            }
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonCancel).setOnClickListener {
            // Revert to original values (don't save)
            callbacks.onAutoBrightnessChanged(true, originalMin, originalMax, originalCurve)
            Log.i(TAG, "🔆 Auto brightness cancelled - reverted to: $originalMin% - $originalMax%, curve=$originalCurve")
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.buttonSave).setOnClickListener {
            // Save the new values
            halitePrefs.display.autoBrightnessMin = currentMin
            halitePrefs.display.autoBrightnessMax = currentMax
            halitePrefs.display.autoBrightnessCurve = currentCurve
            callbacks.onAutoBrightnessChanged(true, currentMin, currentMax, currentCurve)
            Log.i(TAG, "🔆 Auto brightness saved: $currentMin% - $currentMax%, curve=$currentCurve")
            dialog.dismiss()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        callbacks.onDialogShown()

        // Initial update after dialog is shown (to get correct container width)
        handler.postDelayed({ updateLiveSensorDisplay() }, 100)
    }

    // ============================================
    // External Update Methods
    // Called when brightness changes from outside the sidebar (API, auto-brightness sensor)
    // ============================================

    /**
     * Update the auto-brightness display value.
     * Called from LightSensorBrightnessController when brightness changes.
     */
    @Suppress("UNUSED_PARAMETER")
    fun updateAutoBrightnessDisplay(brightnessPercent: Int) {
        // No-op since 2026-07-19: the rows this refreshed lived in the legacy quick-settings
        // sidebar panel (sidebar_content.xml, deleted). The live brightness UI is the strip's
        // BrightnessPopout, which reads state itself. Kept because
        // LightSensorBrightnessController still calls through the helper.
    }

    /**
     * Update the auto-brightness switch state when changed via API.
     * Called from MainActivity when setAutoBrightnessCallback is invoked.
     */
    @android.annotation.SuppressLint("UseSwitchCompatOrMaterialCode")
    @Suppress("UNUSED_PARAMETER")
    fun updateAutoBrightnessSwitch(enabled: Boolean) {
        // No-op since 2026-07-19: refreshed the legacy quick-settings panel's switch/slider
        // rows (sidebar_content.xml, deleted). The live surface is the strip's
        // BrightnessPopout. Kept because the MainActivity API callback still calls through.
    }
    /**
     * No-op since 2026-07-19: refreshed the legacy quick-settings panel's slider/value rows
     * (sidebar_content.xml, deleted). The strip's BrightnessPopout reads state itself.
     * Kept because BrightnessManager.onBrightnessChanged still calls through the helper.
     */
    @Suppress("UNUSED_PARAMETER")
    fun updateBrightnessFromExternal(brightnessLevel: Int) { /* legacy panel rows deleted */ }
}

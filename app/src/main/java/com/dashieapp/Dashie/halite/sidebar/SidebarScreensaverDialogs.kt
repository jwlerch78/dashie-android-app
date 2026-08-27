package com.dashieapp.Dashie.halite.sidebar

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaTokenExtractor
import com.dashieapp.Dashie.halite.screensaver.HaMediaPhotoSource
import com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences
import com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper
import com.dashieapp.Dashie.halite.sidebar.dialogs.StyledConfirmDialog
import com.dashieapp.Dashie.util.TimeFormatter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.dashieapp.Dashie.edition.brandName

/**
 * Handles all screensaver-related dialog UIs including:
 * - Timeout picker (preset and custom)
 * - Screensaver settings (timeout + mode + photo source)
 * - HA Media folder picker
 * - App picker for app screensaver mode
 *
 * Extracted from DialogPickers to reduce file size and improve separation of concerns.
 */
class SidebarScreensaverDialogs(
    private val activity: Activity,
    private val halitePrefs: HalitePreferences,
    private val webViewProvider: (() -> WebView?)? = null
) {
    private val webView: WebView? get() = webViewProvider?.invoke()

    // UI coroutines tied to the host activity's lifecycle (all real hosts are
    // ComponentActivity/AppCompatActivity). Fallback scope for non-lifecycle
    // hosts matches the old fire-and-forget behavior.
    private val uiScope: CoroutineScope =
        (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope
            ?: CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "SidebarScreensaverDialogs"
    }

    // Callbacks for actions that need external handling
    var settingsCallbacks: SettingsCallbacks? = null

    /**
     * Show dual picker for screensaver settings (timeout + mode + photo source)
     */
    fun showScreensaverPicker(textView: TextView?, onSettingsChanged: (timeout: Int, mode: String) -> Unit) {
        val screensaverPrefs = ScreensaverPreferences(activity)

        val timeoutOptions = listOf(
            "Off" to 0,
            "10 sec" to 10,  // For testing
            "30 sec" to 30,
            "1 min" to 60,
            "2 min" to 120,
            "5 min" to 300,
            "10 min" to 600,
            "30 min" to 1800,
            "Custom" to -1  // Special value for custom input
        )

        val modeOptions = listOf(
            "Dim" to "dim",
            "Black Overlay" to "black",
            "Screen Off" to "off",
            "URL" to "url",
            "Photos" to "photos",
            "App" to "app"
        )

        val transitionTimeOptions = listOf(
            "5 sec" to 5,
            "15 sec" to 15,
            "30 sec" to 30,
            "1 min" to 60,
            "5 min" to 300
        )

        val currentTimeout = halitePrefs.screensaver.screensaverTimeout
        val currentMode = halitePrefs.screensaver.screensaverMode
        val currentTransitionTime = screensaverPrefs.slideshowInterval

        // Check if current timeout is a custom value (not in the preset list)
        val isCurrentCustom = timeoutOptions.none { it.second == currentTimeout } && currentTimeout > 0

        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_screensaver_picker, null)

        // Get custom input elements
        val customInputContainer = dialogView.findViewById<LinearLayout>(R.id.customInputContainer)
        val editCustomTimeout = dialogView.findViewById<android.widget.EditText>(R.id.editCustomTimeout)

        // Get Keep Screen On elements
        val rowKeepScreenOn = dialogView.findViewById<LinearLayout>(R.id.rowKeepScreenOn)
        val switchKeepScreenOn = dialogView.findViewById<android.widget.Switch>(R.id.switchKeepScreenOn)
        val textKeepScreenOnHint = dialogView.findViewById<TextView>(R.id.textKeepScreenOnHint)

        // Get photo source elements (clickable rows)
        val photoSourceContainer = dialogView.findViewById<LinearLayout>(R.id.photoSourceContainer)
        val rowPhotoSource = dialogView.findViewById<LinearLayout>(R.id.rowPhotoSource)
        val textPhotoSourceValue = dialogView.findViewById<TextView>(R.id.textPhotoSourceValue)

        Log.d(TAG, "Photo source row initialized: rowPhotoSource=${rowPhotoSource != null}, container=${photoSourceContainer != null}")
        val rowPhotoDuration = dialogView.findViewById<LinearLayout>(R.id.rowPhotoDuration)
        val textPhotoDurationValue = dialogView.findViewById<TextView>(R.id.textPhotoDurationValue)
        val textPhotoSourceInfo = dialogView.findViewById<TextView>(R.id.textPhotoSourceInfo)

        // Get mode dropdown elements
        val rowMode = dialogView.findViewById<LinearLayout>(R.id.rowMode)
        val textModeValue = dialogView.findViewById<TextView>(R.id.textModeValue)
        val rowShowClock = dialogView.findViewById<LinearLayout>(R.id.rowShowClock)
        val rowShowMusicPlayer = dialogView.findViewById<LinearLayout>(R.id.rowShowMusicPlayer)

        // Get dim brightness options elements
        val dimBrightnessContainer = dialogView.findViewById<LinearLayout>(R.id.dimBrightnessContainer)
        val seekBarDimBrightness = dialogView.findViewById<android.widget.SeekBar>(R.id.seekBarDimBrightness)
        val textDimBrightnessValue = dialogView.findViewById<TextView>(R.id.textDimBrightnessValue)

        // Get URL options elements
        val urlOptionsContainer = dialogView.findViewById<LinearLayout>(R.id.urlOptionsContainer)
        val editScreensaverUrl = dialogView.findViewById<android.widget.EditText>(R.id.editScreensaverUrl)

        // Get App options elements
        val appOptionsContainer = dialogView.findViewById<LinearLayout>(R.id.appOptionsContainer)
        val rowSelectApp = dialogView.findViewById<LinearLayout>(R.id.rowSelectApp)
        val textSelectedAppValue = dialogView.findViewById<TextView>(R.id.textSelectedAppValue)

        // Track selected values (can be changed by sub-dialogs)
        var selectedDuration = currentTransitionTime
        var selectedMode = currentMode
        var selectedAppPackage = screensaverPrefs.launchAppPackage
        var selectedAppLabel = screensaverPrefs.launchAppLabel

        // Set initial URL value
        editScreensaverUrl.setText(screensaverPrefs.screensaverUrl)

        // Set initial dim brightness value
        val currentDimBrightness = screensaverPrefs.dimBrightness
        seekBarDimBrightness.progress = currentDimBrightness
        textDimBrightnessValue.text = "$currentDimBrightness%"

        // Track selected dim brightness (can be changed by seekbar)
        var selectedDimBrightness = currentDimBrightness

        // Dim brightness SeekBar listener
        seekBarDimBrightness.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    selectedDimBrightness = progress.coerceIn(1, 75)
                    textDimBrightnessValue.text = "$selectedDimBrightness%"
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // Set initial app value
        textSelectedAppValue.text = if (selectedAppLabel.isNotEmpty()) selectedAppLabel else if (selectedAppPackage.isNotEmpty()) selectedAppPackage else "Not configured"

        // Populate timeout radio buttons
        val timeoutGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.radioGroupTimeout)
        val radioButtonIds = mutableListOf<Int>()

        timeoutOptions.forEachIndexed { index, (label, value) ->
            val radioButton = activity.layoutInflater.inflate(
                R.layout.dialog_picker_item, timeoutGroup, false
            ) as android.widget.RadioButton
            radioButton.id = View.generateViewId()
            radioButton.text = label
            // Select "Custom" if current value is custom, otherwise match preset
            radioButton.isChecked = if (value == -1) isCurrentCustom else value == currentTimeout
            radioButtonIds.add(radioButton.id)
            timeoutGroup.addView(radioButton)
        }

        // Show custom input if current value is custom
        if (isCurrentCustom) {
            customInputContainer.visibility = View.VISIBLE
            editCustomTimeout.setText(currentTimeout.toString())
        }

        // Set initial Keep Screen On value and visibility
        switchKeepScreenOn.isChecked = halitePrefs.sleep.keepScreenOn
        // Only show Keep Screen On toggle when timeout is "Off" (0)
        val showKeepScreenOn = currentTimeout == 0
        rowKeepScreenOn.visibility = if (showKeepScreenOn) View.VISIBLE else View.GONE
        textKeepScreenOnHint.visibility = if (showKeepScreenOn) View.VISIBLE else View.GONE

        // Listen for radio button changes to show/hide custom input AND Keep Screen On toggle
        timeoutGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedIndex = radioButtonIds.indexOf(checkedId)
            val selectedValue = if (selectedIndex >= 0) timeoutOptions[selectedIndex].second else 0
            customInputContainer.visibility = if (selectedValue == -1) View.VISIBLE else View.GONE

            // Only show Keep Screen On when timeout is "Off" (0)
            val showKeepScreenOn = selectedValue == 0
            rowKeepScreenOn.visibility = if (showKeepScreenOn) View.VISIBLE else View.GONE
            textKeepScreenOnHint.visibility = if (showKeepScreenOn) View.VISIBLE else View.GONE
        }

        // Toggle row click handler for D-pad support
        rowKeepScreenOn.setOnClickListener {
            switchKeepScreenOn.isChecked = !switchKeepScreenOn.isChecked
        }

        // Weather overlay UI refs (declared early for use in updateModeVisibility)
        val rowWeatherOverlay = dialogView.findViewById<LinearLayout>(R.id.rowWeatherOverlay)
        val switchWeatherOverlay = dialogView.findViewById<android.widget.Switch>(R.id.switchWeatherOverlay)
        val rowWeatherEntity = dialogView.findViewById<LinearLayout>(R.id.rowWeatherEntity)
        val textWeatherEntityValue = dialogView.findViewById<TextView>(R.id.textWeatherEntityValue)

        // Helper function to format mode display text
        fun formatMode(mode: String): String = when (mode) {
            "dim" -> "Dim"
            "black" -> "Black Overlay"
            "off" -> "Screen Off"
            "url" -> "URL"
            "photos" -> "Photos"
            "app" -> "App"
            else -> "Dim"
        }

        // Helper to update UI based on selected mode
        fun updateModeVisibility() {
            // Show dim brightness options only for dim mode
            dimBrightnessContainer.visibility = if (selectedMode == "dim") View.VISIBLE else View.GONE

            // Show photo options only for photos mode
            photoSourceContainer.visibility = if (selectedMode == "photos") View.VISIBLE else View.GONE

            // Show URL options only for URL mode
            urlOptionsContainer.visibility = if (selectedMode == "url") View.VISIBLE else View.GONE

            // Show App options only for app mode
            appOptionsContainer.visibility = if (selectedMode == "app") View.VISIBLE else View.GONE

            // Show clock, music player, and weather overlay toggles for dim, black, and photos modes
            val showDisplayToggles = selectedMode == "dim" || selectedMode == "black" || selectedMode == "photos"
            rowShowClock.visibility = if (showDisplayToggles) View.VISIBLE else View.GONE
            rowShowMusicPlayer.visibility = if (showDisplayToggles) View.VISIBLE else View.GONE
            rowWeatherOverlay.visibility = if (showDisplayToggles) View.VISIBLE else View.GONE
            rowWeatherEntity.visibility = if (showDisplayToggles && switchWeatherOverlay.isChecked) View.VISIBLE else View.GONE
        }

        // Set initial mode display
        textModeValue.text = formatMode(selectedMode)
        updateModeVisibility()

        // Mode row click handler - show mode picker sub-dialog
        rowMode.setOnClickListener {
            showModePickerSubDialog(selectedMode) { newMode ->
                selectedMode = newMode
                textModeValue.text = formatMode(selectedMode)
                updateModeVisibility()
            }
        }

        // Helper functions for display formatting
        fun formatDuration(seconds: Int): String = when (seconds) {
            5 -> "5 sec"
            15 -> "15 sec"
            30 -> "30 sec"
            60 -> "1 min"
            300 -> "5 min"
            else -> "$seconds sec"
        }

        // Helper to update photo source display based on current source type
        fun updatePhotoSourceDisplay() {
            when (screensaverPrefs.photoSourceType) {
                ScreensaverPreferences.PHOTO_SOURCE_HA_MEDIA -> {
                    val folder = screensaverPrefs.haMediaFolder
                    val folderDisplay = when (folder) {
                        "." -> "(root)"
                        "*" -> "All"
                        else -> folder
                    }
                    textPhotoSourceValue.text = "HA Media: $folderDisplay"
                }
                ScreensaverPreferences.PHOTO_SOURCE_UNSPLASH -> {
                    val query = screensaverPrefs.unsplashQuery.ifEmpty { "nature" }
                    textPhotoSourceValue.text = "Unsplash: $query"
                }
                ScreensaverPreferences.PHOTO_SOURCE_LOCAL -> {
                    textPhotoSourceValue.text = "Local Folder"
                }
                else -> {
                    textPhotoSourceValue.text = "HA Media"
                }
            }
            textPhotoSourceInfo.visibility = View.GONE
        }

        // Get references to new toggle switches
        val switchShowMetadata = dialogView.findViewById<android.widget.Switch>(R.id.switchShowMetadata)
        val switchShowClock = dialogView.findViewById<android.widget.Switch>(R.id.switchShowClock)
        val switchShowMusicPlayer = dialogView.findViewById<android.widget.Switch>(R.id.switchShowMusicPlayer)

        // Hide the 24-hour clock toggle row (moved to Preferences section in settings modal)
        val rowUse24HourClock = dialogView.findViewById<LinearLayout>(R.id.rowUse24HourClock)
        rowUse24HourClock?.visibility = View.GONE

        // Set initial values for toggles
        switchShowMetadata.isChecked = screensaverPrefs.showMetadata
        switchShowClock.isChecked = screensaverPrefs.showClock
        switchShowMusicPlayer.isChecked = halitePrefs.connection.musicPlayerShowWithScreensaver

        // Set initial display values
        textPhotoDurationValue.text = formatDuration(selectedDuration)

        // Update photo source display if photos mode is initially selected
        if (selectedMode == "photos") {
            updatePhotoSourceDisplay()
        }

        // Photo source row click handler - show source type picker
        rowPhotoSource.setOnClickListener {
            Log.d(TAG, "Photo source row clicked - showing source type picker")
            showPhotoSourceTypePicker(screensaverPrefs) {
                updatePhotoSourceDisplay()
            }
        }

        // Duration row click handler - show duration picker sub-dialog
        rowPhotoDuration.setOnClickListener {
            showDurationPickerSubDialog(selectedDuration) { newDuration ->
                selectedDuration = newDuration
                textPhotoDurationValue.text = formatDuration(selectedDuration)
            }
        }

        // App selection row click handler - show installed apps picker
        rowSelectApp.setOnClickListener {
            showAppPicker { packageName, label ->
                selectedAppPackage = packageName
                selectedAppLabel = label
                textSelectedAppValue.text = label.ifEmpty { packageName }
            }
        }

        // Toggle row click handlers for D-pad support
        rowShowClock.setOnClickListener {
            switchShowClock.isChecked = !switchShowClock.isChecked
        }
        rowShowMusicPlayer.setOnClickListener {
            switchShowMusicPlayer.isChecked = !switchShowMusicPlayer.isChecked
        }

        // Weather overlay toggle setup
        switchWeatherOverlay.isChecked = screensaverPrefs.weatherOverlayEnabled
        textWeatherEntityValue.text = screensaverPrefs.weatherEntityId
        rowWeatherEntity.visibility = if (screensaverPrefs.weatherOverlayEnabled) View.VISIBLE else View.GONE

        switchWeatherOverlay.setOnCheckedChangeListener { _, isChecked ->
            rowWeatherEntity.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        rowWeatherOverlay.setOnClickListener {
            switchWeatherOverlay.isChecked = !switchWeatherOverlay.isChecked
        }
        rowWeatherEntity.setOnClickListener {
            showWeatherEntityDialog(screensaverPrefs) { newEntity ->
                textWeatherEntityValue.text = newEntity
            }
        }

        // Note: 24-hour clock toggle moved to Preferences section in settings modal

        val rowShowMetadata = dialogView.findViewById<LinearLayout>(R.id.rowShowMetadata)
        rowShowMetadata.setOnClickListener {
            switchShowMetadata.isChecked = !switchShowMetadata.isChecked
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.buttonSave).setOnClickListener {
            // Get selected timeout
            val selectedTimeoutIndex = (0 until timeoutGroup.childCount).indexOfFirst {
                (timeoutGroup.getChildAt(it) as? android.widget.RadioButton)?.isChecked == true
            }
            val presetValue = if (selectedTimeoutIndex >= 0) timeoutOptions[selectedTimeoutIndex].second else currentTimeout

            // If "Custom" is selected, parse the input value
            val selectedTimeout = if (presetValue == -1) {
                val customText = editCustomTimeout.text.toString()
                customText.toIntOrNull()?.coerceIn(1, 3600) ?: currentTimeout
            } else {
                presetValue
            }

            // selectedMode is already tracked from the mode dropdown clicks

            // Save photo settings if photos mode
            if (selectedMode == "photos") {
                // Photo source type is already saved when user picks it from the sub-dialog
                // Just default to HA Media if nothing was explicitly selected
                if (screensaverPrefs.photoSourceType == ScreensaverPreferences.PHOTO_SOURCE_NONE) {
                    screensaverPrefs.photoSourceType = ScreensaverPreferences.PHOTO_SOURCE_HA_MEDIA
                }
                screensaverPrefs.slideshowInterval = selectedDuration
                screensaverPrefs.showMetadata = switchShowMetadata.isChecked
                Log.i(TAG, "🔧 Photo settings: source=${screensaverPrefs.photoSourceType}, duration=$selectedDuration, showMetadata=${switchShowMetadata.isChecked}")
            }

            // Save dim brightness if dim mode
            if (selectedMode == "dim") {
                screensaverPrefs.dimBrightness = selectedDimBrightness
                Log.i(TAG, "🔧 Dim screensaver: brightness=$selectedDimBrightness%")
            }

            // Save URL if URL mode
            if (selectedMode == "url") {
                val urlText = editScreensaverUrl.text.toString().trim()
                // Use default URL if empty
                val urlToSave = if (urlText.isEmpty()) {
                    ScreensaverPreferences.defaultScreensaverUrl(activity)
                } else {
                    urlText
                }
                screensaverPrefs.screensaverUrl = urlToSave
                Log.i(TAG, "🔧 URL screensaver: url=$urlToSave")
            }

            // Save app settings if app mode
            if (selectedMode == "app") {
                screensaverPrefs.launchAppPackage = selectedAppPackage
                screensaverPrefs.launchAppLabel = selectedAppLabel
                Log.i(TAG, "🔧 App screensaver: package=$selectedAppPackage, label=$selectedAppLabel")
            }

            // Save clock settings (applies to all modes)
            // Note: 24-hour clock is now in HalitePreferences (universal preference)
            screensaverPrefs.showClock = switchShowClock.isChecked

            // Save weather overlay settings
            screensaverPrefs.weatherOverlayEnabled = switchWeatherOverlay.isChecked
            Log.i(TAG, "🔧 Weather overlay: enabled=${switchWeatherOverlay.isChecked}, entity=${screensaverPrefs.weatherEntityId}")

            // Save music player screensaver setting (same pref as Music Assistant > Show with Screensaver)
            val showMusicPlayer = switchShowMusicPlayer.isChecked
            halitePrefs.connection.musicPlayerShowWithScreensaver = showMusicPlayer
            settingsCallbacks?.onMusicPlayerShowWithScreensaverChanged(showMusicPlayer)

            // Save preferences
            halitePrefs.screensaver.screensaverTimeout = selectedTimeout
            halitePrefs.screensaver.screensaverMode = selectedMode

            // Handle Keep Screen On logic:
            // - If timeout is "Off" (0), use the user's toggle value
            // - If timeout is enabled (not 0), automatically enable Keep Screen On (required for screensaver)
            if (selectedTimeout == 0) {
                // Timeout is off - respect user's toggle choice
                halitePrefs.sleep.keepScreenOn = switchKeepScreenOn.isChecked
                Log.i(TAG, "🔧 Keep screen on = ${switchKeepScreenOn.isChecked} (user choice)")
            } else {
                // Timeout is enabled - automatically enable Keep Screen On
                halitePrefs.sleep.keepScreenOn = true
                Log.i(TAG, "🔧 Keep screen on = true (auto-enabled for screensaver)")
            }

            // Update MainActivity's keep screen on flag
            settingsCallbacks?.onKeepScreenOnChanged(halitePrefs.sleep.keepScreenOn)

            // Update display
            textView?.text = SidebarFormatters.formatScreensaverDisplay(selectedTimeout, selectedMode)

            // Notify callback
            onSettingsChanged(selectedTimeout, selectedMode)
            Log.i(TAG, "🔧 Screensaver: timeout=$selectedTimeout, mode=$selectedMode")

            dialog.dismiss()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
    }

    /**
     * Show mode picker sub-dialog for screensaver mode selection.
     */
    private fun showModePickerSubDialog(currentMode: String, onModeSelected: (String) -> Unit) {
        val modes = arrayOf("Dim", "Black Overlay", "Screen Off", "URL", "Photos", "App")
        val modeValues = arrayOf("dim", "black", "off", "url", "photos", "app")
        val currentIndex = modeValues.indexOf(currentMode).coerceAtLeast(0)

        val modeDialogView = activity.layoutInflater.inflate(R.layout.dialog_picker, null)
        modeDialogView.findViewById<TextView>(R.id.dialogTitle).text = "Screensaver Mode"

        val modeOptionsGroup = modeDialogView.findViewById<android.widget.RadioGroup>(R.id.optionsGroup)
        val modeRadioIds = mutableListOf<Int>()

        modes.forEachIndexed { index, label ->
            val radioButton = activity.layoutInflater.inflate(R.layout.dialog_picker_item, modeOptionsGroup, false) as android.widget.RadioButton
            radioButton.id = View.generateViewId()
            radioButton.text = label
            radioButton.isChecked = index == currentIndex
            modeRadioIds.add(radioButton.id)
            modeOptionsGroup.addView(radioButton)
        }

        val modeSubDialog = AlertDialog.Builder(activity)
            .setView(modeDialogView)
            .setCancelable(true)
            .create()

        modeSubDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        modeDialogView.findViewById<Button>(R.id.buttonCancel).setOnClickListener {
            modeSubDialog.dismiss()
        }

        modeDialogView.findViewById<Button>(R.id.buttonSave).setOnClickListener {
            val selectedIdx = modeRadioIds.indexOf(modeOptionsGroup.checkedRadioButtonId)
            if (selectedIdx >= 0) {
                val selectedMode = modeValues[selectedIdx]
                onModeSelected(selectedMode)
                modeSubDialog.dismiss()

                // If user selected "Screen Off", check if Device Admin is enabled
                if (selectedMode == "off") {
                    checkDeviceAdminForScreenOff()
                }
            }
        }

        modeSubDialog.show()
        DialogHelper.applyImmersiveModeToDialog(modeSubDialog)
        // Don't set default focus - let radio group handle it
        modeDialogView.post {
            modeOptionsGroup.requestFocus()
        }
    }

    /**
     * Show duration picker sub-dialog for photo slideshow duration.
     */
    private fun showDurationPickerSubDialog(currentDuration: Int, onDurationSelected: (Int) -> Unit) {
        val durations = arrayOf("5 sec", "15 sec", "30 sec", "1 min", "5 min")
        val durationValues = arrayOf(5, 15, 30, 60, 300)
        val currentIndex = durationValues.indexOf(currentDuration).coerceAtLeast(0)

        val durationDialogView = activity.layoutInflater.inflate(R.layout.dialog_picker, null)
        durationDialogView.findViewById<TextView>(R.id.dialogTitle).text = "Photo Duration"

        val durationOptionsGroup = durationDialogView.findViewById<android.widget.RadioGroup>(R.id.optionsGroup)
        val durationRadioIds = mutableListOf<Int>()

        durations.forEachIndexed { index, label ->
            val radioButton = activity.layoutInflater.inflate(R.layout.dialog_picker_item, durationOptionsGroup, false) as android.widget.RadioButton
            radioButton.id = View.generateViewId()
            radioButton.text = label
            radioButton.isChecked = index == currentIndex
            durationRadioIds.add(radioButton.id)
            durationOptionsGroup.addView(radioButton)
        }

        val durationSubDialog = AlertDialog.Builder(activity)
            .setView(durationDialogView)
            .setCancelable(true)
            .create()

        durationSubDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        durationDialogView.findViewById<Button>(R.id.buttonCancel).setOnClickListener {
            durationSubDialog.dismiss()
        }

        durationDialogView.findViewById<Button>(R.id.buttonSave).setOnClickListener {
            val selectedIdx = durationRadioIds.indexOf(durationOptionsGroup.checkedRadioButtonId)
            if (selectedIdx >= 0) {
                onDurationSelected(durationValues[selectedIdx])
                durationSubDialog.dismiss()
            }
        }

        durationSubDialog.show()
        DialogHelper.applyImmersiveModeToDialog(durationSubDialog)
        // Don't set default focus - let radio group handle it
        durationDialogView.post {
            durationOptionsGroup.requestFocus()
        }
    }

    /**
     * Check if Device Admin is enabled for Screen Off mode.
     * Shows info about touch-to-wake not working, and prompts for Device Admin if not enabled.
     */
    private fun checkDeviceAdminForScreenOff() {
        if (com.dashieapp.Dashie.halite.DeviceAdminHelper.isActive(activity)) {
            // Device Admin already enabled - just show info about touch-to-wake
            StyledConfirmDialog.show(
                activity,
                title = "Screen Off Mode",
                message = "When the screen is off, touch-to-wake may not work.\n\n" +
                    "You can wake the screen using:\n" +
                    "• Camera motion detection (if enabled)\n" +
                    "• Power button (press to unlock)\n" +
                    "• Home Assistant automation"
            )
            return
        }

        // Device Admin not enabled - show explanation and offer to enable
        StyledConfirmDialog.show(
            activity,
            title = "Enable Screen Off",
            message = "Screen Off mode requires Device Admin permission — without it, the screen shows a black overlay instead of actually turning off.\n\n" +
                "Enable Device Admin now?",
            positiveText = "Enable",
            negativeText = "Later",
            onPositive = {
                activity.startActivity(
                    com.dashieapp.Dashie.halite.DeviceAdminHelper.buildActivationIntent(
                        activity,
                        com.dashieapp.Dashie.halite.DeviceAdminHelper.screenOffExplanation(activity)
                    )
                )
            }
        )
    }

    /**
     * Show photo source type picker (HA Media, Unsplash, Local Folder).
     */
    private fun showPhotoSourceTypePicker(
        screensaverPrefs: ScreensaverPreferences,
        onSourceSelected: () -> Unit
    ) {
        val options = arrayOf(
            "Home Assistant Media",
            "Unsplash",
            "Local Folder"
        )
        val values = arrayOf(
            ScreensaverPreferences.PHOTO_SOURCE_HA_MEDIA,
            ScreensaverPreferences.PHOTO_SOURCE_UNSPLASH,
            ScreensaverPreferences.PHOTO_SOURCE_LOCAL
        )

        val currentIdx = values.indexOf(screensaverPrefs.photoSourceType).coerceAtLeast(0)

        AlertDialog.Builder(activity)
            .setTitle("Photo Source")
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                val selectedType = values[which]
                dialog.dismiss()

                when (selectedType) {
                    ScreensaverPreferences.PHOTO_SOURCE_UNSPLASH -> {
                        showUnsplashQueryDialog(screensaverPrefs, onSourceSelected)
                    }
                    ScreensaverPreferences.PHOTO_SOURCE_HA_MEDIA -> {
                        checkHaMediaApiAndShowPicker(screensaverPrefs) {
                            screensaverPrefs.photoSourceType = ScreensaverPreferences.PHOTO_SOURCE_HA_MEDIA
                            onSourceSelected()
                        }
                    }
                    else -> {
                        screensaverPrefs.photoSourceType = selectedType
                        onSourceSelected()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show Unsplash query keywords dialog.
     */
    private fun showUnsplashQueryDialog(
        screensaverPrefs: ScreensaverPreferences,
        onSourceSelected: () -> Unit
    ) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val label = TextView(activity).apply {
            text = "Unsplash Search Keywords"
            setPadding(0, 0, 0, 8)
        }
        layout.addView(label)

        val input = EditText(activity).apply {
            setText(screensaverPrefs.unsplashQuery)
            hint = "nature, landscape, mountains..."
            isSingleLine = true
        }
        layout.addView(input)

        AlertDialog.Builder(activity)
            .setTitle("Unsplash Photo Source")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                screensaverPrefs.unsplashQuery = input.text.toString().trim()
                screensaverPrefs.photoSourceType = ScreensaverPreferences.PHOTO_SOURCE_UNSPLASH
                Log.i(TAG, "Unsplash configured: query=${screensaverPrefs.unsplashQuery.ifEmpty { "nature" }}")
                onSourceSelected()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show weather entity ID input dialog.
     */
    private fun showWeatherEntityDialog(
        screensaverPrefs: ScreensaverPreferences,
        onEntitySet: (String) -> Unit
    ) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val label = TextView(activity).apply {
            text = "Home Assistant weather entity ID"
            setPadding(0, 0, 0, 8)
        }
        layout.addView(label)

        val input = EditText(activity).apply {
            setText(screensaverPrefs.weatherEntityId)
            hint = "weather.forecast_home"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }
        layout.addView(input)

        AlertDialog.Builder(activity)
            .setTitle("Weather Entity")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val entity = input.text.toString().trim().ifEmpty {
                    ScreensaverPreferences.DEFAULT_WEATHER_ENTITY_ID
                }
                screensaverPrefs.weatherEntityId = entity
                Log.i(TAG, "Weather entity set: $entity")
                onEntitySet(entity)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Check if HA Media API is accessible before showing the folder picker.
     * Shows an error dialog if the Dashie integration is not installed in HA.
     */
    private fun checkHaMediaApiAndShowPicker(
        screensaverPrefs: ScreensaverPreferences,
        onSourceSelected: () -> Unit
    ) {
        val baseUrl = halitePrefs.connection.haBaseUrl.ifEmpty {
            halitePrefs.connection.haUrl.substringBefore("?").trimEnd('/')
        }
        if (baseUrl.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("Configuration Error")
                .setMessage("Home Assistant URL not configured. Please open the dashboard first.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        // Show loading while checking API
        val loadingDialog = AlertDialog.Builder(activity)
            .setTitle("Checking...")
            .setMessage("Verifying ${activity.brandName()} integration in Home Assistant...")
            .setCancelable(true)
            .create()
        loadingDialog.show()

        // Use HaTokenExtractor to ensure we have a fresh token from WebView
        HaTokenExtractor.ensureToken(webView, halitePrefs) { hasToken ->
            if (!hasToken) {
                loadingDialog.dismiss()
                AlertDialog.Builder(activity)
                    .setTitle("Not Logged In")
                    .setMessage("Please open the Home Assistant dashboard first to log in, then try again.")
                    .setPositiveButton("OK", null)
                    .show()
                return@ensureToken
            }

            // Try to fetch folders to verify API is accessible
            fetchFoldersWithRetry(screensaverPrefs, baseUrl, loadingDialog, onSourceSelected)
        }
    }

    /**
     * Fetch folders from HA Media API, with automatic token refresh on 401.
     * This mirrors how voice commands handle expired tokens.
     */
    private fun fetchFoldersWithRetry(
        screensaverPrefs: ScreensaverPreferences,
        baseUrl: String,
        loadingDialog: AlertDialog,
        onSourceSelected: () -> Unit,
        isRetry: Boolean = false
    ) {
        uiScope.launch {
            val haSource = HaMediaPhotoSource(activity, screensaverPrefs)
            haSource.setCredentials(halitePrefs.connection.haAccessToken, baseUrl)

            val result = haSource.fetchFoldersWithError()

            // If we got a 401 and haven't retried yet, try refreshing the token
            if (result.error == HaMediaPhotoSource.FolderFetchError.UNAUTHORIZED && !isRetry) {
                Log.d(TAG, "Got 401 on folder fetch, attempting token refresh...")
                HaTokenExtractor.refreshTokenWithResult(halitePrefs, webView) { refreshResult ->
                    if (refreshResult.success) {
                        Log.i(TAG, "Token refresh successful, retrying folder fetch")
                        // Retry with the new token
                        fetchFoldersWithRetry(screensaverPrefs, baseUrl, loadingDialog, onSourceSelected, isRetry = true)
                    } else {
                        // Refresh failed - try extracting fresh token from WebView as fallback
                        Log.w(TAG, "Token refresh failed, trying WebView extraction...")
                        HaTokenExtractor.extractAndCache(webView, halitePrefs) { extracted ->
                            if (extracted) {
                                Log.i(TAG, "WebView token extraction successful, retrying folder fetch")
                                fetchFoldersWithRetry(screensaverPrefs, baseUrl, loadingDialog, onSourceSelected, isRetry = true)
                            } else {
                                Log.w(TAG, "WebView token extraction also failed")
                                loadingDialog.dismiss()
                                AlertDialog.Builder(activity)
                                    .setTitle("Authentication Error")
                                    .setMessage("Could not authenticate with Home Assistant.\n\nPlease reopen the dashboard to refresh your session.")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }
                }
                return@launch
            }

            loadingDialog.dismiss()

            if (result.folders.isEmpty()) {
                // Show error based on the specific failure reason
                val (title, message) = when (result.error) {
                    HaMediaPhotoSource.FolderFetchError.API_NOT_FOUND -> Pair(
                        // 🔴 BRAND-TRIAGED — DELIBERATELY NOT SUBSTITUTED (A, 2026-08-05, pass 2),
                        // and this is the OPPOSITE call from the rest of the sweep. "Dashie" here
                        // is not the product speaking about itself: it is the literal name of the
                        // HA custom component the user must go and find. Verified against the
                        // artifact, not assumed — dashie-ha-integration/custom_components/dashie/
                        // manifest.json declares domain "dashie", name "Dashie", and this dialog
                        // fires on a 404 from GET /api/dashie/media. There is one integration, not
                        // one per edition, so "install the Chickadee custom component" would send
                        // a user to search HACS for something that does not exist — a leak turned
                        // into a FALSE INSTRUCTION, which is strictly worse. Same class as the
                        // filesystem paths BrandStrings.kt warns off: functional, not prose.
                        // Reworded instead so the name reads as a thing to search for rather than
                        // as this app's own name, and the title is brand-neutral.
                        // ➡️ The real fix is a product decision (does the HA edition ship its own
                        // integration name/alias?) — routed to O, not invented here.
                        "Home Assistant Integration Required",
                        "The \"Dashie\" integration is not installed in Home Assistant.\n\n" +
                        "Install it via HACS — search for \"Dashie\" — or add the custom " +
                        "component manually to enable HA Media photos."
                    )
                    HaMediaPhotoSource.FolderFetchError.UNAUTHORIZED -> Pair(
                        "Authentication Error",
                        "Could not authenticate with Home Assistant.\n\n" +
                        "Try reopening the dashboard to refresh your session."
                    )
                    HaMediaPhotoSource.FolderFetchError.NETWORK_ERROR -> Pair(
                        "Network Error",
                        "Could not connect to Home Assistant.\n\n" +
                        "Check your network connection and that Home Assistant is running."
                    )
                    HaMediaPhotoSource.FolderFetchError.NO_FOLDERS -> Pair(
                        "No Photos Found",
                        "No folders with photos were found in Home Assistant Media.\n\n" +
                        "Add photos to your Media folder (HA sidebar > Media) and try again."
                    )
                    HaMediaPhotoSource.FolderFetchError.MISSING_CREDENTIALS -> Pair(
                        "Configuration Error",
                        "Missing Home Assistant credentials.\n\n" +
                        "Open the dashboard first to establish connection, then try again."
                    )
                    else -> Pair(
                        "HA Media Not Available",
                        "Could not access Home Assistant Media.\n\n" +
                        "Make sure:\n" +
                        "1. The Dashie integration is installed in HA\n" +
                        "2. Your Media folder has photos (sidebar > Media)\n\n" +
                        "Error: ${result.error?.name ?: "Unknown"}" +
                        (result.httpCode?.let { " (HTTP $it)" } ?: "")
                    )
                }
                AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                // API is working - show folder picker
                showHaMediaFolderList(screensaverPrefs, result.folders, screensaverPrefs.haMediaFolder, onSourceSelected)
            }
        }
    }

    /**
     * Show the folder list picker after fetching from HA API.
     */
    private fun showHaMediaFolderList(
        screensaverPrefs: ScreensaverPreferences,
        folders: List<HaMediaPhotoSource.HaMediaFolder>,
        currentFolder: String,
        onFolderChanged: () -> Unit
    ) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_picker, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Select Photo Folder"

        // Get the RadioGroup and repurpose it as a container for folder rows
        val optionsGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.optionsGroup)
        // Remove the RadioGroup and replace with a LinearLayout for custom folder rows
        val parent = optionsGroup.parent as? android.widget.ScrollView
        parent?.removeView(optionsGroup)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 8)
        }
        parent?.addView(container)

        // Track currently selected folder (starts with saved value)
        var selectedFolder = currentFolder

        // Helper to update visual selection state on all rows
        fun updateRowSelection() {
            folders.forEachIndexed { index, folder ->
                val row = container.getChildAt(index) as? LinearLayout ?: return@forEachIndexed
                val isSelected = folder.path == selectedFolder

                // Update folder name style (first child)
                val nameView = row.getChildAt(0) as? TextView
                nameView?.setTypeface(nameView.typeface,
                    if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

                // Update checkmark visibility (third child, if present)
                val childCount = row.childCount
                if (childCount >= 3) {
                    row.getChildAt(2).visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
                }
            }
        }

        // Helper to create a clickable folder row
        fun createFolderRow(folder: HaMediaPhotoSource.HaMediaFolder): LinearLayout {
            val isSelected = folder.path == currentFolder
            val displayName = when (folder.name) {
                "*" -> "All (cycle through everything)"
                "." -> "(root)"
                else -> folder.name
            }

            return LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(48, 20, 48, 20)
                isClickable = true
                isFocusable = true
                background = activity.getDrawable(android.R.drawable.list_selector_background)
                gravity = android.view.Gravity.CENTER_VERTICAL

                // Folder name
                addView(TextView(activity).apply {
                    text = displayName
                    textSize = 16f
                    setTextColor(activity.getColor(R.color.text_primary))
                    if (isSelected) {
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                // Photo count badge
                addView(TextView(activity).apply {
                    text = "${folder.photoCount} photos"
                    textSize = 12f
                    setTextColor(activity.getColor(R.color.text_secondary))
                    setPadding(16, 0, 0, 0)
                })

                // Checkmark (always present, visibility toggled)
                addView(TextView(activity).apply {
                    text = "✓"
                    textSize = 16f
                    setTextColor(activity.getColor(R.color.dashie_orange))
                    setPadding(16, 0, 0, 0)
                    visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
                })
            }
        }

        // Add folder rows
        folders.forEach { folder ->
            container.addView(createFolderRow(folder))
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        // Add dim scrim behind the dialog
        dialog.window?.apply {
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes?.apply { dimAmount = 0.5f }
        }

        dialogView.findViewById<Button>(R.id.buttonCancel).setOnClickListener {
            dialog.dismiss()
        }

        // Save button — commits the selection
        dialogView.findViewById<Button>(R.id.buttonSave).setOnClickListener {
            screensaverPrefs.haMediaFolder = selectedFolder
            Log.i(TAG, "🔧 HA Media folder set to: $selectedFolder")
            onFolderChanged()
            dialog.dismiss()

            // Pre-cache photos in the background to speed up screensaver start
            preCacheHaMediaPhotos(screensaverPrefs)
        }

        // Row clicks update selection (visual only, no save yet)
        folders.forEachIndexed { index, folder ->
            container.getChildAt(index).setOnClickListener {
                selectedFolder = folder.path
                updateRowSelection()
            }
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        DialogHelper.setDefaultFocusOnCancel(dialogView)
    }

    /**
     * Pre-cache photos from HA Media in the background.
     * This speeds up screensaver start by downloading photos ahead of time.
     */
    private fun preCacheHaMediaPhotos(screensaverPrefs: ScreensaverPreferences) {
        if (!halitePrefs.connection.hasHaAccessToken) {
            Log.d(TAG, "Skipping pre-cache: no HA token")
            return
        }

        val baseUrl = halitePrefs.connection.haBaseUrl.ifEmpty {
            halitePrefs.connection.haUrl.substringBefore("?").trimEnd('/')
        }
        if (baseUrl.isEmpty()) {
            Log.d(TAG, "Skipping pre-cache: no HA base URL")
            return
        }

        Log.i(TAG, "🖼️ Pre-caching HA Media photos in background...")

        uiScope.launch {
            val haSource = HaMediaPhotoSource(activity, screensaverPrefs)
            haSource.setCredentials(halitePrefs.connection.haAccessToken, baseUrl)

            val result = haSource.sync()
            if (result.success) {
                Log.i(TAG, "🖼️ Pre-cached ${result.photosNew} photos (${result.photosFound} total)")
            } else {
                Log.w(TAG, "🖼️ Pre-cache failed: ${result.error}")
            }
        }
    }

    /**
     * Public API for showing the HA Media folder picker from the JS bridge.
     * Fetches folders from HA API and shows a native folder picker dialog.
     * @param onFolderSelected Called with the selected folder path after user picks one
     */
    fun showHaMediaFolderPickerFromBridge(onFolderSelected: (String) -> Unit) {
        val screensaverPrefs = halitePrefs.screensaver
        checkHaMediaApiAndShowPicker(screensaverPrefs) {
            onFolderSelected(screensaverPrefs.haMediaFolder)
        }
    }

    /**
     * Show app picker dialog for selecting an app to launch as screensaver.
     * Lists all installed apps with launch intents (launchable apps).
     */
    fun showAppPicker(onAppSelected: (packageName: String, label: String) -> Unit) {
        // Show loading dialog while fetching apps
        val loadingDialog = AlertDialog.Builder(activity)
            .setTitle("Loading Apps...")
            .setMessage("Getting installed apps...")
            .setCancelable(true)
            .create()
        loadingDialog.show()

        // Fetch installed apps in background thread
        Thread {
            val pm = activity.packageManager

            // Collect apps from multiple categories to catch all launchable apps
            val appMap = mutableMapOf<String, String>() // packageName -> label

            // 1. Standard launcher apps (phone/tablet)
            val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            pm.queryIntentActivities(launcherIntent, 0).forEach { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName
                val label = resolveInfo.loadLabel(pm).toString()
                appMap[packageName] = label
            }

            // 2. Leanback launcher apps (Android TV)
            val leanbackIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            }
            pm.queryIntentActivities(leanbackIntent, 0).forEach { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName
                val label = resolveInfo.loadLabel(pm).toString()
                if (!appMap.containsKey(packageName)) {
                    appMap[packageName] = label
                }
            }

            // 3. Query ACTION_MAIN without category (catches more apps)
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
            pm.queryIntentActivities(mainIntent, 0).forEach { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName
                val label = resolveInfo.loadLabel(pm).toString()
                if (!appMap.containsKey(packageName)) {
                    appMap[packageName] = label
                }
            }

            // 4. All installed applications (most comprehensive)
            pm.getInstalledApplications(0).forEach { appInfo ->
                val packageName = appInfo.packageName
                if (!appMap.containsKey(packageName)) {
                    // Check if it has any launchable activity
                    val launchIntent = pm.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        val label = pm.getApplicationLabel(appInfo).toString()
                        appMap[packageName] = label
                    }
                }
            }

            Log.i(TAG, "📱 App picker found ${appMap.size} launchable apps")

            val apps = appMap.entries
                .filter { it.key != activity.packageName } // Exclude Dashie itself
                .filter { !it.key.startsWith("com.amazon.") } // Exclude Amazon system apps
                .filter { !it.key.startsWith("com.android.") } // Exclude Android system apps
                .map { Pair(it.value, it.key) } // (label, packageName)
                .sortedBy { it.first.lowercase() }

            // Update UI on main thread
            activity.runOnUiThread {
                loadingDialog.dismiss()

                if (apps.isEmpty()) {
                    Toast.makeText(activity, "No apps found", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                // Create scrollable list
                val container = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 16, 0, 16)
                }

                apps.forEach { (label, packageName) ->
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(48, 20, 48, 20)
                        isClickable = true
                        isFocusable = true
                        background = activity.getDrawable(android.R.drawable.list_selector_background)

                        addView(TextView(activity).apply {
                            text = label
                            textSize = 15f
                            setTextColor(activity.getColor(R.color.text_primary))
                        })

                        addView(TextView(activity).apply {
                            text = packageName
                            textSize = 12f
                            setTextColor(activity.getColor(R.color.text_secondary))
                        })
                    }
                    container.addView(row)
                }

                val scrollView = android.widget.ScrollView(activity).apply {
                    addView(container)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        600  // Fixed height for scrollable area
                    )
                }

                val dialog = AlertDialog.Builder(activity)
                    .setTitle("Select App")
                    .setView(scrollView)
                    .setNegativeButton("Cancel", null)
                    .create()

                // Wire up click handlers
                apps.forEachIndexed { index, (label, packageName) ->
                    container.getChildAt(index).setOnClickListener {
                        onAppSelected(packageName, label)
                        dialog.dismiss()
                    }
                }

                dialog.show()
                DialogHelper.applyImmersiveModeToDialog(dialog)
            }
        }.start()
    }
}

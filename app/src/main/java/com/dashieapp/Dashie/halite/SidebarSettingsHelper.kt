package com.dashieapp.Dashie.halite

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.SeekBar
import android.widget.Toast
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.sidebar.BrightnessUI
import com.dashieapp.Dashie.halite.sidebar.DialogPickers
import com.dashieapp.Dashie.halite.sidebar.SettingsCallbacks
import com.dashieapp.Dashie.halite.sidebar.SidebarFormatters
import com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper
import com.dashieapp.Dashie.halite.sidebar.dialogs.MotionCameraDialogs
import com.dashieapp.Dashie.halite.sidebar.dialogs.ScreensaverSettingsDialogs
import com.dashieapp.Dashie.halite.sidebar.dialogs.VoiceApiDialogs
import com.dashieapp.Dashie.wakeword.models.WakeWordModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.dashieapp.Dashie.wakeword.models.WakeWordModelManager

/**
 * Shared helper class for sidebar settings functionality.
 * Used by both HaliteSidebarController and HaUrlSetupActivity
 * to avoid code duplication.
 */
class SidebarSettingsHelper(
    private val activity: Activity,
    private val halitePrefs: HalitePreferences,
    private val webViewProvider: (() -> android.webkit.WebView?)? = null
) {
    // Re-evaluated per use: survives WebView memory-recovery recreation. A raw
    // WebView here (the old shape) pinned the construction-time instance, so
    // every dialog path went dead against the destroyed WebView after the
    // nightly recreation.
    private val webView: android.webkit.WebView? get() = webViewProvider?.invoke()

    // UI coroutines tied to the host activity's lifecycle (all real hosts are
    // ComponentActivity/AppCompatActivity). Fallback scope for non-lifecycle
    // hosts matches the old fire-and-forget behavior.
    private val uiScope: CoroutineScope =
        (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope
            ?: CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "SidebarSettingsHelper"

        /**
         * Apply immersive mode flags to a dialog window to prevent nav bar from showing.
         * Delegates to DialogHelper - kept here for backwards compatibility with existing callers.
         */
        fun applyImmersiveModeToDialog(dialog: AlertDialog) = DialogHelper.applyImmersiveModeToDialog(dialog)
    }

    // Wake word model manager (lazy-initialized)
    val wakeWordModelManager by lazy { WakeWordModelManager(activity) }

    // Brightness UI handler
    private val brightnessUI = BrightnessUI(activity, halitePrefs)

    // Dialog pickers handler (lazy to allow callback setup)
    // Note: DialogPickers is kept for large functions (showScreensaverPicker, showMotionWakeModePicker)
    private val dialogPickers by lazy {
        DialogPickers(activity, halitePrefs, webViewProvider).apply {
            onRestartRequired = { msg -> this@SidebarSettingsHelper.onRestartRequired?.invoke(msg) }
        }
    }

    // Screensaver settings dialogs (timeout picker, lock navigation)
    private val screensaverSettingsDialogs by lazy {
        ScreensaverSettingsDialogs(activity, halitePrefs, webViewProvider).apply {
            onRestartRequired = { msg -> this@SidebarSettingsHelper.onRestartRequired?.invoke(msg) }
        }
    }

    // Voice API dialogs (API settings, response handling, voice pipeline)
    private val voiceApiDialogs by lazy {
        VoiceApiDialogs(activity, halitePrefs, webViewProvider).apply {
            onRestartRequired = { msg -> this@SidebarSettingsHelper.onRestartRequired?.invoke(msg) }
        }
    }

    // Motion camera dialogs (camera permission, device admin)
    private val motionCameraDialogs by lazy {
        MotionCameraDialogs(activity, halitePrefs)
    }

    // The edition seam's paywall surfaces (credits, subscription, cloud activation).
    //
    // The four VoiceUiCallbacks that used to be attached here went with the voice licence on
    // 2026-08-02: they existed to keep the sidebar in sync after a licence flow, and there are
    // no licence flows any more. The callback FIELDS below stay — they are still driven by the
    // ordinary voice toggle.
    private val paywall by lazy {
        com.dashieapp.Dashie.edition.EditionSeams.paywall(activity)
    }

    // Callbacks
    var onVoiceEnabledChanged: ((Boolean) -> Unit)? = null
    var onShowResponsesChanged: ((Boolean) -> Unit)? = null
    var onReadResponsesAloudChanged: ((Boolean) -> Unit)? = null
    var onRestartRequired: ((String) -> Unit)? = null
    var onRestartApp: (() -> Unit)? = null  // Full app restart (for wake word model changes)
    var getSystemDarkMode: (() -> Boolean)? = null  // Callback to get current system dark mode state
    var lightSensorProvider: (() -> LightSensorBrightnessController?)? = null  // Provider for light sensor controller

    // The two no-op callbacks that lived here (updateShowResponsesEnabled,
    // updateAudioSectionVisibility) existed only so VoiceLicenseUI's callback wiring had a
    // target — their real bodies were deleted with sidebar_content.xml in 2026-07. The licence
    // went on 2026-08-02, taking the last caller with it, so they are gone rather than left as
    // unreachable no-ops.

    // ============================================
    // Brightness Methods (delegated to BrightnessUI)
    // ============================================

    /**
     * Update brightness slider visibility based on auto-brightness mode.
     * Delegates to BrightnessUI.
     */
    internal fun updateBrightnessSliderVisibility(
        autoBrightnessEnabled: Boolean,
        rangeLayout: LinearLayout?,
        manualLayout: LinearLayout?,
        seekBarBrightness: SeekBar? = null,
        controlsRow: LinearLayout? = null,
        settingsButton: View? = null,
        autoValueDisplay: TextView? = null,
        currentBrightness: Int = 50,
        autoSettingsRow: LinearLayout? = null
    ) = brightnessUI.updateBrightnessSliderVisibility(
        autoBrightnessEnabled, rangeLayout, manualLayout, seekBarBrightness,
        controlsRow, settingsButton, autoValueDisplay, currentBrightness, autoSettingsRow
    )

    /**
     * Update brightness UI based on permission state.
     * Delegates to BrightnessUI.
     */
    internal fun updateBrightnessPermissionUI(
        canWrite: Boolean,
        autoLabel: TextView?,
        autoSwitch: Switch?,
        enableControl: TextView?,
        manualLayout: LinearLayout?,
        rangeLayout: LinearLayout?,
        seekBarBrightness: SeekBar?,
        controlsRow: LinearLayout?,
        darkLightToggle: View?
    ) = brightnessUI.updateBrightnessPermissionUI(
        canWrite, autoLabel, autoSwitch, enableControl, manualLayout,
        rangeLayout, seekBarBrightness, controlsRow, darkLightToggle
    )

    /**
     * Show brightness permission dialog.
     * Delegates to BrightnessUI.
     */
    internal fun showBrightnessPermissionDialog(onEnableNow: () -> Unit) =
        brightnessUI.showBrightnessPermissionDialog(onEnableNow)

    /**
     * Show dark mode permission dialog.
     * Delegates to BrightnessUI.
     */
    internal fun showDarkModePermissionDialog() = brightnessUI.showDarkModePermissionDialog()

    /**
     * Show auto brightness settings dialog with live sensor preview.
     * Delegates to BrightnessUI.
     */
    internal fun showAutoBrightnessSettingsDialog(callbacks: SettingsCallbacks) =
        brightnessUI.showAutoBrightnessSettingsDialog(callbacks, lightSensorProvider?.invoke())

    /**
     * Update auto-brightness display value from sensor.
     * Delegates to BrightnessUI.
     */
    fun updateAutoBrightnessDisplay(brightnessPercent: Int) =
        brightnessUI.updateAutoBrightnessDisplay(brightnessPercent)

    /**
     * Update auto-brightness switch state from API.
     * Delegates to BrightnessUI.
     */
    fun updateAutoBrightnessSwitch(enabled: Boolean) =
        brightnessUI.updateAutoBrightnessSwitch(enabled)

    /**
     * Update brightness slider from external change.
     * Delegates to BrightnessUI.
     */
    fun updateBrightnessFromExternal(brightnessLevel: Int) =
        brightnessUI.updateBrightnessFromExternal(brightnessLevel)

    // ============================================
    // Voice License Dialogs
    // ============================================

    /**
     * Show the voice activation dialog (first time enabling voice).
     * Delegates to VoiceLicenseUI.
     */

    /**
     * Show purchase dialog with QR code.
     * Delegates to VoiceLicenseUI.
     */

    // ============================================
    // Sample Collection / Dashboard Telemetry consent
    // ============================================
    // showSampleCollectionConsentDialog + showDashboardTelemetryConsentDialog were
    // DELETED 2026-08-05 (Chickadee brand-prose removal). Both were uncalled: the LIVE
    // consent surface is the overlay-JS onboarding "Data Collection" step
    // (kiosk-overlay/js/onboarding/onboarding-renderer.js renderDataCollection +
    // renderDataCollectionInfo), which discloses BOTH data streams and whose Continue
    // sets prefs.voice.sampleCollectionConsent + prefs.performance.dashboardTelemetryConsent
    // via JsBridgeHaliteDelegate. The in-Settings toggles are the ongoing opt-out (see
    // MqttSettingsWiring advanced.dashboardTelemetryEnabled). These dialogs were a
    // never-wired duplicate — reachability here means Kotlin AND the bundled overlay JS.

    // ============================================
    // Wake Word Model Methods
    // ============================================

    /**
     * No-op: the legacy panel's wake-word row was deleted with sidebar_content.xml
     * (2026-07-19). Kept because the wake-word selection modal flows call it after a
     * model change; the live display is the native Voice & AI settings page.
     */
    fun updateWakeWordModelDisplay() { /* legacy panel row deleted */ }

    /**
     * Update pipeline mode display in sidebar.
     */
    /**
     * Show wake word selection modal with available models.
     */
    private fun showWakeWordSelectionModal() {
        uiScope.launch {
            try {
                // Get current model info
                val activeModel = wakeWordModelManager.getActiveModel()
                val currentVersion = activeModel.version

                // Check for available test version from manifest
                val testVersion = wakeWordModelManager.getAvailableTestVersion()

                // Build list of options
                val options = mutableListOf<WakeWordOption>()

                // Option 1: Bundled model (always available)
                val bundledVersion = WakeWordModel.BUNDLED.version
                val isBundledActive = activeModel.isBundled
                options.add(WakeWordOption(
                    label = "${WakeWordModel.NAME} v$bundledVersion",
                    subtitle = "Built-in (stable)",
                    version = bundledVersion,
                    isActive = isBundledActive,
                    isBundled = true,
                    needsDownload = false
                ))

                // Option 2: Test version from manifest (if available)
                if (testVersion != null) {
                    val isTestActive = currentVersion == testVersion.replace("T", "")
                    options.add(WakeWordOption(
                        label = "${WakeWordModel.NAME} v${testVersion.replace("T", "")}",
                        subtitle = "Beta (testing)",
                        version = testVersion,
                        isActive = isTestActive,
                        isBundled = false,
                        needsDownload = !isTestActive
                    ))
                }

                // Option 3: Currently downloaded model (if different from bundled and test)
                if (!activeModel.isBundled && currentVersion != testVersion?.replace("T", "")) {
                    options.add(0, WakeWordOption(
                        label = "${WakeWordModel.NAME} v$currentVersion",
                        subtitle = "Downloaded",
                        version = currentVersion,
                        isActive = true,
                        isBundled = false,
                        needsDownload = false
                    ))
                }

                // Show selection dialog
                showWakeWordOptionsDialog(options)

            } catch (e: Exception) {
                Log.e("SidebarSettings", "Error showing wake word selection", e)
                Toast.makeText(activity, "Error loading wake word options", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Data class for wake word option in selection modal.
     */
    private data class WakeWordOption(
        val label: String,
        val subtitle: String,
        val version: String,
        val isActive: Boolean,
        val isBundled: Boolean,
        val needsDownload: Boolean
    )

    /**
     * Show the wake word options dialog with orange radio buttons.
     */
    private fun showWakeWordOptionsDialog(options: List<WakeWordOption>) {
        // Find the currently active option index
        val activeIndex = options.indexOfFirst { it.isActive }.takeIf { it >= 0 } ?: 0

        // Create ScrollView with RadioGroup
        val scrollView = android.widget.ScrollView(activity).apply {
            setPadding(48, 48, 48, 48)
        }

        val radioGroup = android.widget.RadioGroup(activity).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }

        val radioButtonIds = mutableListOf<Int>()

        // Create radio buttons with orange tint
        options.forEachIndexed { index, option ->
            val download = if (option.needsDownload) " (download)" else ""
            val displayText = "${option.label}$download\n${option.subtitle}"

            val radioButton = android.widget.RadioButton(activity).apply {
                id = android.view.View.generateViewId()
                text = displayText
                textSize = 14f
                setTextColor(activity.getColor(R.color.text_primary))
                buttonTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.dashie_orange))
                isChecked = index == activeIndex
                setPadding(0, 16, 0, 16)
            }
            radioButtonIds.add(radioButton.id)
            radioGroup.addView(radioButton)
        }

        scrollView.addView(radioGroup)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Select Wake Word Model")
            .setView(scrollView)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Select") { _, _ ->
                val selectedIndex = radioButtonIds.indexOf(radioGroup.checkedRadioButtonId)
                if (selectedIndex >= 0) {
                    handleWakeWordSelection(options[selectedIndex])
                }
            }
            .create()

        dialog.show()
        applyImmersiveModeToDialog(dialog)
    }

    /**
     * Handle wake word model selection.
     */
    private fun handleWakeWordSelection(option: WakeWordOption) {
        when {
            option.isActive -> {
                // Already active - do nothing
                Toast.makeText(activity, "Already using ${option.label}", Toast.LENGTH_SHORT).show()
            }
            option.isBundled -> {
                // Switch to bundled model
                wakeWordModelManager.resetToBundled()
                updateWakeWordModelDisplay()
                showRestartPrompt(option.label)
            }
            option.needsDownload -> {
                // Download the model
                downloadWakeWordModel(option.version, option.label)
            }
            else -> {
                // Already downloaded, just need restart
                showRestartPrompt(option.label)
            }
        }
    }

    /**
     * Show restart prompt after wake word model change.
     */
    private fun showRestartPrompt(modelLabel: String) {
        RestartPromptHelper.show(
            activity = activity,
            message = "Switched to $modelLabel.\n\nThe new wake word model will take effect after restarting the app.\n\nRestart now?",
            layoutRes = R.layout.dialog_simple_message
        ) {
            restartApp()
        }
    }

    /**
     * Restart the app using the registered callback (goes through MainActivity's performRestartApp).
     */
    private fun restartApp() {
        onRestartApp?.invoke() ?: run {
            // Fallback if callback not set: just close the activity
            Log.w(TAG, "onRestartApp callback not set, falling back to activity finish")
            activity.finish()
        }
    }

    /**
     * Download a wake word model version.
     */
    private fun downloadWakeWordModel(version: String, label: String) {
        uiScope.launch {
            try {
                Toast.makeText(activity, "Downloading $label...", Toast.LENGTH_SHORT).show()

                val downloadedVersion = wakeWordModelManager.downloadTestModel()

                if (downloadedVersion != null) {
                    updateWakeWordModelDisplay()
                    showRestartPrompt(label)
                } else {
                    Toast.makeText(activity, "Download failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("SidebarSettings", "Error downloading wake word model", e)
                Toast.makeText(activity, "Download error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ============================================
    // Dialog Pickers (delegated to DialogPickers)
    // ============================================

    fun formatScreensaverDisplay(timeout: Int, mode: String): String =
        SidebarFormatters.formatScreensaverDisplay(timeout, mode)

    /**
     * Show dual picker for screensaver settings (timeout + mode).
     * Delegates to DialogPickers.
     */
    fun showScreensaverPicker(textView: TextView?, onSettingsChanged: (timeout: Int, mode: String) -> Unit) {
        dialogPickers.settingsCallbacks = settingsCallbacks
        dialogPickers.showScreensaverPicker(textView, onSettingsChanged)
    }

    /**
     * Show API settings dialog (enable, port, password).
     * Delegates to VoiceApiDialogs.
     */
    fun showApiSettingsDialog() {
        voiceApiDialogs.settingsCallbacks = settingsCallbacks
        voiceApiDialogs.showApiSettingsDialog()
    }

    // ============================================
    // Consolidated Settings Setup
    // ============================================

    // SettingsCallbacks interface moved to sidebar/SettingsCallbacks.kt

    internal var settingsCallbacks: SettingsCallbacks? = null

    // SettingsWiring and its forwarders were DELETED 2026-07-19: the legacy quick-settings
    // sidebar panel (sidebar_content.xml / sidebar_overlay.xml) lost its inflation site in
    // the sidebar redesign, so the whole wiring layer was authored-but-unreached dead code.
    // The live surfaces are the strip popouts + the native SettingsActivity; this helper's
    // remaining job is the dialogs/pickers NativeDialogHost drives.

    /**
     * Show confirmation dialog when enabling Lock Nav Bar.
     * Delegates to ScreensaverSettingsDialogs.
     */
    internal fun showLockNavigationConfirmationDialog(onConfirm: () -> Unit) =
        screensaverSettingsDialogs.showLockNavigationConfirmationDialog(onConfirm)

    // ============================================
    // Formatting Helpers
    // ============================================
    // Note: Most formatting functions moved to sidebar/SidebarFormatters.kt
    // These methods delegate to SidebarFormatters for backwards compatibility

    fun formatTimeout(seconds: Int): String = SidebarFormatters.formatTimeout(seconds)

    fun formatApiPassword(password: String): String = SidebarFormatters.formatApiPassword(password)

    fun shortenUrl(url: String): String = SidebarFormatters.shortenUrl(url)

    fun formatMotionWakeMode(mode: String): String =
        SidebarFormatters.formatMotionWakeMode(mode, halitePrefs.screensaver.cameraWakeThresholdTenths, halitePrefs.screensaver.faceWakeDistance)

    fun formatResponseHandling(mode: String): String = SidebarFormatters.formatResponseHandling(mode)

    /**
     * Show motion wake mode picker dialog.
     * Delegates to DialogPickers.
     */
    fun showMotionWakeModePicker(textView: TextView?, onModeSet: (String) -> Unit) {
        dialogPickers.settingsCallbacks = settingsCallbacks
        dialogPickers.showMotionWakeModePicker(textView, onModeSet)
    }

    /**
     * Show calibration-only dialog for motion/face wake mode (no mode radio buttons).
     * Delegates to DialogPickers.
     */
    fun showMotionWakeCalibrationDialog(mode: String, onDismiss: () -> Unit) {
        dialogPickers.settingsCallbacks = settingsCallbacks
        dialogPickers.showMotionWakeCalibrationDialog(mode, onDismiss)
    }

    /**
     * Show voice pipeline picker dialog.
     * Fetches available pipelines from Home Assistant and allows user to select one.
     * Delegates to VoiceApiDialogs.
     */
    fun showVoicePipelinePicker(textView: TextView?, onPipelineSet: (id: String, name: String) -> Unit) =
        voiceApiDialogs.showVoicePipelinePicker(textView, onPipelineSet)

    // ========== HA Media Folder Picker (from JS bridge) ==========

    /**
     * Show HA Media folder picker from the JS bridge.
     * Delegates to sidebar DialogPickers → ScreensaverDialogs.
     */
    fun showHaMediaFolderPicker(onFolderSelected: (String) -> Unit) =
        dialogPickers.showHaMediaFolderPicker(onFolderSelected)

    // ========== Photo Source (Screensaver) ==========

    private val screensaverDialogs: com.dashieapp.Dashie.halite.screensaver.ScreensaverDialogs by lazy {
        com.dashieapp.Dashie.halite.screensaver.ScreensaverDialogs(activity, halitePrefs.screensaver) {
            // Callback when settings change
        }
    }

    /**
     * Show photo source configuration dialog.
     */
    fun showPhotoSourceDialog(onSettingsChanged: () -> Unit) {
        val dialogs = com.dashieapp.Dashie.halite.screensaver.ScreensaverDialogs(
            activity,
            halitePrefs.screensaver,
            onSettingsChanged
        )
        dialogs.showPhotoSourceDialog()
    }

    /**
     * Get display text for current photo source setting.
     */
    fun getPhotoSourceDisplayText(): String {
        val dialogs = com.dashieapp.Dashie.halite.screensaver.ScreensaverDialogs(
            activity,
            halitePrefs.screensaver
        ) {}
        return dialogs.getPhotoSourceDisplayText()
    }
}

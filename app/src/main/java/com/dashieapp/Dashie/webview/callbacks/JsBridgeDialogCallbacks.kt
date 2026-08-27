package com.dashieapp.Dashie.webview.callbacks

import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaliteScreenController
import com.dashieapp.Dashie.halite.NativeDialogHost
import com.dashieapp.Dashie.webview.DashieJSBridge

class JsBridgeDialogCallbacks(
    private val dialogHostProvider: () -> NativeDialogHost?,
    private val halitePrefsProvider: () -> HalitePreferences?,
    private val screenControllerProvider: () -> HaliteScreenController?,
    private val dismissSystemOverlaysProvider: () -> Unit = {},
    private val runOnUiThread: (Runnable) -> Unit,
    /** Item 23: invoked when user picks "Close Sign-in" — navigates the
     *  WebView back to determineInitialUrl (HA shell when configured). */
    private val onCloseSignInProvider: () -> Unit = {}
) : DashieJSBridge.DialogCallbacks {

    override fun dismissSystemOverlays() {
        runOnUiThread { dismissSystemOverlaysProvider() }
    }

    override fun openAutoBrightnessSettings() {
        runOnUiThread { dialogHostProvider()?.showAutoBrightnessSettingsFromBridge() }
    }
    override fun showLockDialog() {
        runOnUiThread { dialogHostProvider()?.showLockDialogFromBridge() }
    }
    override fun showPinRecoveryDialog() {
        runOnUiThread { dialogHostProvider()?.showPinRecoveryDialogFromBridge() }
    }
    override fun showExitConfirmation() {
        runOnUiThread { dialogHostProvider()?.showExitConfirmationFromBridge() }
    }
    override fun showSignInCloseConfirmation() {
        runOnUiThread {
            dialogHostProvider()?.showSignInCloseFromBridge {
                onCloseSignInProvider()
            }
        }
    }
    override fun onToggleVoiceEnabled() {
        dialogHostProvider()?.handleVoiceToggleFromDrawer()
    }
    override fun onScreensaverSettingsChanged() {
        val timeout = halitePrefsProvider()?.screensaver?.screensaverTimeout ?: 60
        val mode = halitePrefsProvider()?.screensaver?.screensaverMode ?: "dim"
        Log.i("DashieJSBridge", "🖥️ onScreensaverSettingsChanged() callback - timeout=$timeout, mode=$mode")
        screenControllerProvider()?.updateScreensaverSettings(timeout, mode)
    }
    override fun onOpenMotionWakeSettings() {
        dialogHostProvider()?.showMotionWakePickerFromBridge()
    }
    override fun onShowHaMediaFolderPicker() {
        // Dead JS lane: no JS anywhere defines window._onHaMediaFolderSelected (the callback the
        // old bridge variant fed), so opening the picker from here could only drop the selection.
        // Native settings own the picker now (DialogPickers.showHaMediaFolderPicker with a Kotlin
        // callback). Kept as a loud no-op for old cached webapp JS that might still call it.
        Log.w("DashieJSBridge", "DROP: showHaMediaFolderPicker JS bridge is retired — " +
            "no JS consumer for the selection; use native settings (Screensaver → HA media folder)")
    }
    override fun onOpenAutoBrightnessSettings() {
        runOnUiThread { dialogHostProvider()?.showAutoBrightnessSettingsFromBridge() }
    }
    override fun onOpenSystemDetails() {
        dialogHostProvider()?.showSystemDetailsFromBridge()
    }
    override fun onShowPinDialog() {
        dialogHostProvider()?.showPinDialogFromBridge()
    }
    override fun onShowRecoveryEmailDialog() {
        dialogHostProvider()?.showRecoveryEmailDialogFromBridge()
    }
    override fun onShowRestartRequired(message: String) {
        dialogHostProvider()?.showRestartPromptFromBridge(message)
    }
    override fun onShowDiagnosticsDialog() {
        dialogHostProvider()?.showDiagnosticsDialogFromBridge()
    }
    override fun onSendDiagnosticsHeadless() {
        dialogHostProvider()?.sendDiagnosticsHeadlessFromBridge()
    }
    override fun onSendPendingCrashReportHeadless() {
        dialogHostProvider()?.sendPendingCrashReportHeadlessFromBridge()
    }
    override fun onShowHaPipelinePicker(callback: (id: String, name: String) -> Unit) {
        dialogHostProvider()?.showVoicePipelinePickerFromBridge(callback)
    }
}

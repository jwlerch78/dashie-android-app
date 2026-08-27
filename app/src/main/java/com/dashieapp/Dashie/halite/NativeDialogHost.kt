package com.dashieapp.Dashie.halite

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.sidebar.SettingsCallbacks
import com.dashieapp.Dashie.halite.sidebar.SidebarFormatters
import com.dashieapp.Dashie.halite.sidebar.SidebarWelcomeDialogs
import com.dashieapp.Dashie.halite.sidebar.dialogs.DiagnosticsDialogs
import com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper
import com.dashieapp.Dashie.halite.sidebar.dialogs.LockDialogs
import com.dashieapp.Dashie.halite.sidebar.dialogs.PerformanceDialogs
import com.dashieapp.Dashie.halite.SidebarSettingsHelper.Companion.applyImmersiveModeToDialog

/**
 * Hosts native dialogs that are launched from the JS bridge.
 *
 * Extracted from HaliteSidebarController when the legacy native sidebar
 * was removed. All dialog methods that were called via DashieJSBridge
 * callbacks now live here.
 *
 * Does NOT manage any sidebar views or animations — only standalone
 * AlertDialog instances and their supporting helper classes.
 */
class NativeDialogHost(
    private val activity: Activity,
    private val halitePrefs: HalitePreferences,
    private val webViewProvider: () -> WebView,
    private val screenDimmerProvider: () -> ScreenDimmer?
) {
    private val webView: WebView
        get() = webViewProvider()

    private val screenDimmer: ScreenDimmer?
        get() = screenDimmerProvider()

    private val sidebarHelper = SidebarSettingsHelper(activity, halitePrefs, webViewProvider)

    /**
     * "May voice run?" via the edition seam rather than a VoiceLicenseManager.
     *
     * Lazy, and holding the seam instance rather than re-creating it per call, because that is
     * what the code it replaces did — `SidebarSettingsHelper` held one eager manager for the
     * lifetime of the host. The answer itself is not cached: `isVoiceAllowed()` reads live
     * state on every call, so a licence flow completing mid-session is still seen.
     */
    private val voiceEntitlement by lazy {
        com.dashieapp.Dashie.edition.EditionSeams.voice(activity, halitePrefs)
    }
    // Public: shared with LockGate call sites (Control Center, JS-bridge
    // settings funnels) so the PIN attempt/lockout counters stay in one place.
    val lockDialogs = LockDialogs(activity, halitePrefs)
    private val performanceDialogs = PerformanceDialogs(activity, halitePrefs)
    private val diagnosticsDialogs = DiagnosticsDialogs(activity, halitePrefs)
    private val welcomeDialogs = SidebarWelcomeDialogs(activity, halitePrefs)

    // Motion wake diagnostics provider (set externally from HaliteComponentRegistry)
    var motionWakeDiagnosticsProvider: (() -> String)? = null
        set(value) {
            field = value
            diagnosticsDialogs.motionWakeDiagnosticsProvider = value
            performanceDialogs.motionWakeDiagnosticsProvider = value
        }

    // Screensaver panel state provider (set externally from HaliteComponentRegistry)
    var screensaverPanelDiagnosticsProvider: (() -> String)? = null
        set(value) {
            field = value
            diagnosticsDialogs.screensaverPanelDiagnosticsProvider = value
        }

    // Lock status providers for diagnostics (set externally from HaliteComponentRegistry)
    var wifiLockHeldProvider: (() -> Boolean)? = null
        set(value) { field = value; performanceDialogs.wifiLockHeldProvider = value }
    var cpuWakeLockHeldProvider: (() -> Boolean)? = null
        set(value) { field = value; performanceDialogs.cpuWakeLockHeldProvider = value }

    // Applies the Performance → WiFi Lock toggle live (set externally from HaliteComponentRegistry)
    var onWifiLockChanged: ((Boolean) -> Unit)? = null
        set(value) { field = value; performanceDialogs.onWifiLockChanged = value }

    // Gesture handler for edge swipes (left-edge swipe to open JS dash menu)
    private val gestureHandler = SidebarGestureHandler(activity, object : SidebarGestureCallback {
        override fun onSwipeToOpen() {
            handleEdgeSwipe()
        }
        override fun onLongPressRight() {
            // No-op: tab navigation was tied to the old native sidebar
        }
    })

    companion object {
        private const val TAG = "NativeDialogHost"
    }

    // Callbacks
    var onExitApp: (() -> Unit)? = null
    var onLogout: (() -> Unit)? = null
    var onRestartApp: (() -> Unit)? = null
    var onVoiceEnabledChanged: ((Boolean) -> Unit)? = null
    var onScreensaverSettingsChanged: ((Int, String) -> Unit)? = null
    var onMotionWakeModeChanged: ((String) -> Unit)? = null
    var onAutoBrightnessChanged: ((Boolean, Int, Int, String) -> Unit)? = null
    var onDialogShown: (() -> Unit)? = null
    var onDialogDismissed: (() -> Unit)? = null
    var onLockToAppChanged: ((Boolean) -> Unit)? = null

    /**
     * Set the SettingsCallbacks implementation for dialogs that need camera preview,
     * motion score, face detection, and RTSP debugging support.
     */
    fun setSettingsCallbacks(callbacks: SettingsCallbacks) {
        sidebarHelper.settingsCallbacks = callbacks
    }

    init {
        // Wire up sidebar helper's restart app callback
        sidebarHelper.onRestartApp = {
            onRestartApp?.invoke()
        }

        // Wire up lock dialogs callbacks
        lockDialogs.onLockStateChanged = { isLocked ->
            Log.i(TAG, "🔒 onLockStateChanged: isLocked=$isLocked")
            // Sync lockToApp with lockAppExit
            if (halitePrefs.lock.lockAppExit != halitePrefs.lock.lockToApp) {
                halitePrefs.lock.lockToApp = halitePrefs.lock.lockAppExit
                onLockToAppChanged?.invoke(halitePrefs.lock.lockAppExit)
            }
            // Notify JS of lock state change
            webView.post {
                webView.evaluateJavascript(
                    // WEBAPP-EXEMPT: dashieSetLockState — kiosk-overlay shell API
                    "if(window.dashieSetLockState) { dashieSetLockState($isLocked); 'OK' } else { 'MISSING' }",
                    null
                )
            }
            // Refresh the JS drawer lock state
            notifyDrawerLockStateChanged()
        }
        lockDialogs.onLockSettingsChanged = {
            notifyDrawerLockStateChanged()
        }
    }

    // ============================================
    // Touch Event Handling (edge swipe)
    // ============================================

    /**
     * Handle touch events for edge swipe detection.
     * Resets screen dimmer timer on touch.
     */
    fun handleTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN && screenDimmer?.isDimmed() != true) {
            screenDimmer?.resetTimer()
        }
        return gestureHandler.handleTouchEvent(event)
    }

    /** Callback to reveal the native sidebar (set by MainActivity). */
    var onRevealNativeSidebar: (() -> Unit)? = null

    /**
     * Handle left-edge swipe — reveals native sidebar or falls back to JS dash menu.
     */
    private fun handleEdgeSwipe() {
        // Route to native sidebar if available
        val nativeReveal = onRevealNativeSidebar
        if (nativeReveal != null) {
            nativeReveal()
            return
        }

        // Fallback: JS sidebar
        // When locked, reveal sidebar via JS
        if (halitePrefs.lock.isLocked) {
            webView.evaluateJavascript(
                // WEBAPP-EXEMPT: dashieRevealSidebar — kiosk-overlay JS sidebar; full mode uses the native sidebar
                "if(window.dashieRevealSidebar) { dashieRevealSidebar(); 'OK' } else if(window.dashieSetLockState) { dashieSetLockState(true); 'LOCK' } else { 'MISSING' }",
                null
            )
            return
        }

        // When dash bar is enabled in kiosk mode, reveal it via JS
        if (halitePrefs.display.dashMenuEnabled && (!halitePrefs.account.isLinked || halitePrefs.account.forceKioskMode)) {
            if (!halitePrefs.display.dashMenuPinned) {
                webView.evaluateJavascript("if(window.dashieRevealSidebar) dashieRevealSidebar();", null)
            }
            return
        }

        // Fallback: open JS dash menu directly
        webView.evaluateJavascript("if(window.dashieRevealSidebar) dashieRevealSidebar();", null)
    }

    // ============================================
    // Bridge Dialog Methods
    // ============================================

    fun showLockDialogFromBridge() {
        Log.i(TAG, "🔒 showLockDialogFromBridge: isLocked=${halitePrefs.lock.isLocked}, " +
                "lockAppExit=${halitePrefs.lock.lockAppExit}, lockSettings=${halitePrefs.lock.lockSettings}")
        if (halitePrefs.lock.isLocked) {
            lockDialogs.initiateUnlock {
                notifyDrawerLockStateChanged()
            }
        } else {
            lockDialogs.showLockSettingsDialog()
        }
    }

    fun showPinRecoveryDialogFromBridge() {
        if (lockDialogs.hasPinSet()) {
            lockDialogs.showPinRecoveryDialog {
                notifyDrawerLockStateChanged()
            }
        }
    }

    fun showExitConfirmationFromBridge() {
        showExitConfirmation()
    }

    /**
     * Item 23: 3-button dialog shown when the user clicks X on the
     * Dashie sign-in screen while HA is configured. Cancel dismisses;
     * "Close Sign-in" returns to the HA shell URL (the user's HA
     * dashboard); "Exit App" exits like the normal exit flow.
     */
    fun showSignInCloseFromBridge(onCloseSignIn: () -> Unit) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_exit_app, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Close Sign-in"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            activity.getString(R.string.exit_or_return_body, activity.getString(R.string.brand_name))

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.6f)
        }

        // Reuse the 3-button layout from dialog_exit_app, relabel:
        // buttonCancel = Cancel, buttonExit (middle) = Close Sign-in,
        // buttonLogout (right primary-orange) = Exit App.
        val cancelBtn = dialogView.findViewById<android.widget.Button>(R.id.buttonCancel)
        val closeBtn = dialogView.findViewById<android.widget.Button>(R.id.buttonExit)
        val exitBtn = dialogView.findViewById<android.widget.Button>(R.id.buttonLogout)
        cancelBtn.text = "Cancel"
        closeBtn.text = "Close Sign-in"
        exitBtn.text = "Exit App"

        cancelBtn.setOnClickListener { dialog.dismiss() }
        closeBtn.setOnClickListener {
            dialog.dismiss()
            onCloseSignIn()
        }
        exitBtn.setOnClickListener {
            dialog.dismiss()
            onExitApp?.invoke()
        }

        dialog.show()
        applyImmersiveModeToDialog(dialog)
        // D-pad nav: focus Cancel by default; wire next-focus IDs.
        cancelBtn.isFocusable = true
        closeBtn.isFocusable = true
        exitBtn.isFocusable = true
        cancelBtn.nextFocusRightId = R.id.buttonExit
        closeBtn.nextFocusLeftId = R.id.buttonCancel
        closeBtn.nextFocusRightId = R.id.buttonLogout
        exitBtn.nextFocusLeftId = R.id.buttonExit
        cancelBtn.post { cancelBtn.requestFocus() }
    }


    fun showMotionWakePickerFromBridge() {
        sidebarHelper.showMotionWakeModePicker(null) { mode ->
            onMotionWakeModeChanged?.invoke(mode)
            val description = SidebarFormatters.formatMotionWakeMode(
                mode, halitePrefs.screensaver.cameraWakeThresholdTenths, halitePrefs.screensaver.faceWakeDistance
            )
            webView.evaluateJavascript(
                // BUNDLE-EXEMPT: onMotionWakeChanged — cosmetic label refresh in the JS settings page; kiosk control center/settings are native
                "if(window.onMotionWakeChanged) window.onMotionWakeChanged('$description')",
                null
            )
        }
    }

    /**
     * Show calibration-only dialog for motion/face wake mode (no mode radio buttons).
     * Called from SettingsActivity via broadcast → MainBroadcastManager.
     */
    fun showWakeCalibrationDialog(mode: String, onDismiss: () -> Unit) {
        sidebarHelper.showMotionWakeCalibrationDialog(mode, onDismiss)
    }

    fun showAutoBrightnessSettingsFromBridge() {
        sidebarHelper.showAutoBrightnessSettingsDialog(object : SettingsCallbacks {
            override fun onAutoBrightnessChanged(enabled: Boolean, min: Int, max: Int, curve: String) {
                onAutoBrightnessChanged?.invoke(enabled, min, max, curve)
            }
        })
    }

    fun showRestartPromptFromBridge(message: String) {
        showRestartPrompt(message)
    }

    fun showPinDialogFromBridge() {
        lockDialogs.startPinSetupFlow {}
    }

    fun showRecoveryEmailDialogFromBridge() {
        lockDialogs.startPinSetupFlow {}
    }

    fun showSystemDetailsFromBridge() {
        performanceDialogs.showSystemDetailsDialog()
    }

    fun showDiagnosticsDialogFromBridge() {
        diagnosticsDialogs.showSendDiagnosticsDialog()
    }

    /** Remote-triggered diagnostic upload (no UI). Console publishes
     *  {cmd:'send_diagnostics'} on the per-device realtime channel; the
     *  webapp listener calls into this. */
    fun sendDiagnosticsHeadlessFromBridge() {
        diagnosticsDialogs.sendDiagnosticsHeadless()
    }

    /** Remote-triggered pending crash upload. Same pattern as
     *  sendDiagnosticsHeadlessFromBridge. No-op when there's no pending
     *  crash report. */
    fun sendPendingCrashReportHeadlessFromBridge() {
        diagnosticsDialogs.sendPendingCrashReportHeadless()
    }

    fun showVoicePipelinePickerFromBridge(callback: (id: String, name: String) -> Unit) {
        sidebarHelper.showVoicePipelinePicker(null) { id, name ->
            callback(id, name)
        }
    }

    // ============================================
    // Voice Methods
    // ============================================

    fun handleVoiceToggleFromDrawer() {
        Log.i(TAG, "handleVoiceToggleFromDrawer() called, voiceEnabled=${halitePrefs.voice.voiceEnabled}")
        if (halitePrefs.voice.voiceEnabled) {
            halitePrefs.voice.voiceEnabled = false
            onVoiceEnabledChanged?.invoke(false)
            notifyDrawerVoiceState(false)
        } else if (voiceEntitlement.isVoiceAllowed()) {
            halitePrefs.voice.voiceEnabled = true
            onVoiceEnabledChanged?.invoke(true)
            notifyDrawerVoiceState(true)
        }
        // No else: voice is free since 2026-08-02, so isVoiceAllowed() is unconditionally true
        // and the old "offer them a licence" branch is unreachable. The CHECK is kept rather
        // than inlined — see VoiceEntitlement's note on why the question outlives its answer.
    }

    // ============================================
    // Welcome / Setup
    // ============================================

    fun showWelcomeToast(confirmedLogin: Boolean = false) {
        welcomeDialogs.showWelcomeToast(confirmedLogin)
    }

    fun continuePermissionSetupIfNeeded() {
        welcomeDialogs.continueSetupIfNeeded()
    }

    // ============================================
    // Diagnostics
    // ============================================

    fun setViewportDiagnostics(diagnostics: String) {
        diagnosticsDialogs.lastViewportDiagnostics = diagnostics
    }

    fun onDeviceAdminResult(granted: Boolean) {
        performanceDialogs.onDeviceAdminResult(granted)
    }

    // ============================================
    // Lock State Notifications
    // ============================================

    /**
     * Handle kiosk lock state changes from API.
     * Notifies JS of the new lock state.
     */
    fun updateKioskLockState(isLocked: Boolean) {
        halitePrefs.lock.lockAppExit = isLocked
        halitePrefs.lock.lockSettings = isLocked
        // Sync lockToApp for screen pinning
        if (halitePrefs.lock.lockToApp != isLocked) {
            halitePrefs.lock.lockToApp = isLocked
            onLockToAppChanged?.invoke(isLocked)
        }
        // Notify JS
        webView.post {
            webView.evaluateJavascript(
                "if(window.dashieSetLockState) { dashieSetLockState($isLocked); 'OK' } else { 'MISSING' }",
                null
            )
        }
        notifyDrawerLockStateChanged()
    }

    // ============================================
    // Private Helpers
    // ============================================

    private fun showRestartPrompt(message: String) {
        RestartPromptHelper.show(
            activity = activity,
            message = "$message\n\nWould you like to restart now?"
        ) {
            Log.i(TAG, "User requested restart")
            val intent = activity.intent
            activity.finish()
            activity.startActivity(intent)
        }
    }

    private fun showExitConfirmation() {
        if (halitePrefs.account.isLinked) {
            showExitWithLogoutDialog()
        } else {
            showSimpleExitDialog()
        }
    }

    private fun showSimpleExitDialog() {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Exit App"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            activity.getString(R.string.exit_confirm_body, activity.getString(R.string.brand_name))

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<Button>(R.id.buttonPositive).text = "Exit"
        dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
            dialog.dismiss()
            onExitApp?.invoke()
        }

        dialog.show()
        applyImmersiveModeToDialog(dialog)
        DialogHelper.setDefaultFocusOnCancel(dialogView)
    }

    private fun showExitWithLogoutDialog() {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_exit_app, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Exit"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            "Signed in as ${halitePrefs.account.email}"

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Cancel (left)
        dialogView.findViewById<android.widget.Button>(R.id.buttonCancel).setOnClickListener {
            dialog.dismiss()
        }
        // Logout (center)
        dialogView.findViewById<android.widget.Button>(R.id.buttonExit).setOnClickListener {
            Log.w(TAG, "🔐🔐🔐 NativeDialogHost LOGOUT clicked, onLogout=${onLogout != null}")
            dialog.dismiss()
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            onLogout?.invoke()
            Log.w(TAG, "🔐🔐🔐 NativeDialogHost LOGOUT — onLogout invoked")
        }
        // Exit App (right, orange)
        dialogView.findViewById<android.widget.Button>(R.id.buttonLogout).setOnClickListener {
            Log.w(TAG, "🔐🔐🔐 NativeDialogHost EXIT APP clicked")
            dialog.dismiss()
            onExitApp?.invoke()
        }

        dialog.show()
        applyImmersiveModeToDialog(dialog)
    }

    private fun notifyDrawerVoiceState(enabled: Boolean) {
        webView.post {
            webView.evaluateJavascript("""
                (function() {
                    var msg = {source: 'dashie-parent', type: 'voice-state-changed', enabled: $enabled};
                    var iframe = document.getElementById('dashie-overlay');
                    if (iframe && iframe.contentWindow) {
                        iframe.contentWindow.postMessage(msg, '*');
                    }
                    window.postMessage(msg, '*');
                })()
            """.trimIndent(), null)
        }
    }

    private fun notifyDrawerLockStateChanged() {
        webView.post {
            webView.evaluateJavascript("""
                (function() {
                    var iframe = document.getElementById('dashie-overlay');
                    if (iframe && iframe.contentWindow) {
                        iframe.contentWindow.postMessage({source: 'dashie-parent', type: 'refresh-lock-state'}, '*');
                    }
                })()
            """.trimIndent(), null)
        }
    }
}

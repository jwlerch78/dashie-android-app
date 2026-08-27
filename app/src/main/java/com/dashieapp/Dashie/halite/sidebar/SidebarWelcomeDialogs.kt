package com.dashieapp.Dashie.halite.sidebar

import android.app.Activity
import android.app.AlertDialog
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.DashieDeviceAdminReceiver
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.SidebarSettingsHelper.Companion.applyImmersiveModeToDialog
import com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper
import com.dashieapp.Dashie.util.DeviceInfoHelper
import com.dashieapp.Dashie.edition.brandName

/**
 * Handles welcome/onboarding dialogs shown on first use:
 * - Permission setup dialog (replaces beta welcome)
 * - Swipe gesture tip popup (first use)
 * - Swipe gesture toast hint (subsequent launches)
 *
 * Extracted from HaliteSidebarController.
 */
class SidebarWelcomeDialogs(
    private val activity: Activity,
    private val halitePrefs: HalitePreferences
) {
    companion object {
        private const val TAG = "SidebarWelcome"
    }

    // State: only show welcome once per session
    private var hasShownWelcomeToast = false

    /**
     * Show welcome flow (only once per session).
     * First launch: permission setup dialog (if needed), then swipe tip.
     * Subsequent launches: swipe tip toast only.
     *
     * @param confirmedLogin true when called from onHaLoginCompleted (permission setup
     *   only triggers on confirmed login to avoid showing before auth completes)
     */
    fun showWelcomeToast(confirmedLogin: Boolean = false) {
        Log.i(TAG, "showWelcomeToast called - hasShownWelcomeToast=$hasShownWelcomeToast, confirmedLogin=$confirmedLogin")
        if (hasShownWelcomeToast) {
            Log.i(TAG, "Skipping - already shown this session")
            return
        }
        // D.80 — suppress the kiosk-style welcome flow (permission setup
        // dialog + swipe tip + CC tip) for Dashie-cloud users. These
        // tips are designed for the first-launch kiosk path where the
        // user is new to the app entirely. A user with an existing
        // Dashie account who later enables HA / switches to kiosk mode
        // is not a new user; the prompts feel out of place. Mark the
        // welcome shown so the rest of the flow short-circuits cleanly
        // (callers downstream check `betaWelcomeShown`).
        if (halitePrefs.account.isLinked) {
            Log.i(TAG, "Skipping - Dashie account linked (kiosk welcome flow is for fresh kiosk users only)")
            hasShownWelcomeToast = true
            halitePrefs.display.betaWelcomeShown = true
            return
        }

        if (!halitePrefs.display.betaWelcomeShown) {
            if (!confirmedLogin) {
                // First launch but login not confirmed — wait for onHaLoginCompleted
                Log.i(TAG, "First launch: deferring permission setup until login confirmed")
                return
            }
            hasShownWelcomeToast = true

            val needsOverlay = needsOverlayPermission()
            val needsExactAlarm = needsExactAlarmPermission()
            val needsDeviceAdmin = needsDeviceAdminPermission()
            val needsBrightness = needsBrightnessPermission()

            Log.i(TAG, "First launch setup: overlay=$needsOverlay, exactAlarm=$needsExactAlarm, deviceAdmin=$needsDeviceAdmin, brightness=$needsBrightness")

            if (!needsOverlay && !needsExactAlarm && !needsDeviceAdmin && !needsBrightness) {
                // All permissions already granted or not needed — skip dialog
                halitePrefs.display.betaWelcomeShown = true
                showSwipeTip()
            } else {
                showPermissionSetupDialog()
            }
        } else {
            hasShownWelcomeToast = true
            showSwipeTip()
        }
    }

    /**
     * Continue the permission setup flow after returning from system settings.
     * Called from MainLifecycleHandler.onResume().
     */
    fun continueSetupIfNeeded() {
        val step = halitePrefs.display.setupPendingStep
        if (step == 0) return

        Log.i(TAG, "continueSetupIfNeeded: step=$step")
        halitePrefs.display.setupPendingStep = 0

        when (step) {
            1 -> {
                // Returned from device admin — advance to next
                advanceToNextPermission(afterDeviceAdmin = true)
            }
            2 -> {
                // Returned from exact alarm — advance to next
                advanceToNextPermission(afterDeviceAdmin = true, afterExactAlarm = true)
            }
            3 -> {
                // Returned from brightness settings — advance to next
                advanceToNextPermission(afterDeviceAdmin = true, afterExactAlarm = true, afterBrightness = true)
            }
            4 -> {
                // Returned from overlay (auto relaunch) — done
                finishSetup()
            }
        }
    }

    // ========== Permission Checks ==========

    private fun needsOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity)
    }

    private fun needsExactAlarmPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        return alarmManager?.canScheduleExactAlarms() != true
    }

    private fun needsDeviceAdminPermission(): Boolean {
        // Skip on TV devices - screen off via device admin is not useful on TV/streaming sticks
        if (DeviceInfoHelper.isFireTV() || DeviceInfoHelper.isTVDevice(activity)) return false
        return !com.dashieapp.Dashie.halite.DeviceAdminHelper.isActive(activity)
    }

    private fun needsBrightnessPermission(): Boolean {
        // Skip on TV devices - brightness control is not useful on TV/streaming sticks
        if (DeviceInfoHelper.isFireTV() || DeviceInfoHelper.isTVDevice(activity)) return false
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(activity)
    }

    // ========== Setup Dialog ==========

    private fun showPermissionSetupDialog() {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Recommended Permissions"
        val messageBuilder = StringBuilder("${activity.brandName()} works best with a few optional Android permissions:\n\n")
        if (needsDeviceAdminPermission()) {
            messageBuilder.append("\u2022 Device Screen Off \u2014 Providing device admin privileges allows you to turn the screen fully off\n\n")
        }
        if (needsExactAlarmPermission()) {
            messageBuilder.append("\u2022 Exact Times \u2014 Allow Dashie to set sleep timers and perform other functions at specific times\n\n")
        }
        if (needsBrightnessPermission()) {
            messageBuilder.append("\u2022 Brightness Control \u2014 Enable modification of system settings for Dashie to control the brightness\n\n")
        }
        if (needsOverlayPermission()) {
            messageBuilder.append("\u2022 Auto Relaunch \u2014 Enable automatic restart after an unintended shutdown\n\n")
        }
        messageBuilder.append("Would you like to set these up now? You can always change them later in Settings.")
        dialogView.findViewById<TextView>(R.id.dialogMessage).text = messageBuilder.toString()

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonPositive).text = "Set Up"
        dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
            dialog.dismiss()
            startPermissionFlow()
        }

        dialogView.findViewById<Button>(R.id.buttonNegative).text = "Skip"
        dialogView.findViewById<Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
            finishSetup()
        }

        dialog.show()
        applyImmersiveModeToDialog(dialog)

        // Ensure both buttons are focusable for d-pad navigation (TV/non-touchscreen devices)
        val positiveBtn = dialogView.findViewById<Button>(R.id.buttonPositive)
        val negativeBtn = dialogView.findViewById<Button>(R.id.buttonNegative)
        DialogHelper.applyBorderButtonFocusHighlight(negativeBtn, dialogStyle = true)
        positiveBtn.isFocusable = true
        negativeBtn.isFocusable = true
        // Link buttons for horizontal d-pad navigation
        positiveBtn.nextFocusLeftId = R.id.buttonNegative
        negativeBtn.nextFocusRightId = R.id.buttonPositive
        // Post to ensure layout is ready before requesting focus
        positiveBtn.post { positiveBtn.requestFocus() }
        Log.i(TAG, "Showing permission setup dialog")
    }

    // ========== Sequential Permission Flow ==========

    private fun startPermissionFlow() {
        advanceToNextPermission(afterDeviceAdmin = false, afterExactAlarm = false, afterBrightness = false)
    }

    /**
     * Advance to the next needed permission in sequence:
     * device admin → exact alarm → brightness → overlay.
     */
    private fun advanceToNextPermission(
        afterDeviceAdmin: Boolean = false,
        afterExactAlarm: Boolean = false,
        afterBrightness: Boolean = false
    ) {
        // Step 1: Device admin (screen off)
        if (!afterDeviceAdmin && needsDeviceAdminPermission()) {
            openDeviceAdminSettings()
            return
        }

        // Step 2: Exact alarm
        if (!afterExactAlarm && needsExactAlarmPermission()) {
            openExactAlarmSettings()
            return
        }

        // Step 3: Brightness control
        if (!afterBrightness && needsBrightnessPermission()) {
            openBrightnessSettings()
            return
        }

        // Step 4: Overlay (auto relaunch)
        if (needsOverlayPermission()) {
            openOverlaySettings()
            return
        }

        // All done
        finishSetup()
    }

    private fun openBrightnessSettings() {
        halitePrefs.display.setupPendingStep = 3
        try {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open write settings: ${e.message}")
            halitePrefs.display.setupPendingStep = 0
            advanceToNextPermission(afterDeviceAdmin = true, afterExactAlarm = true, afterBrightness = true)
        }
    }

    private fun openOverlaySettings() {
        halitePrefs.display.setupPendingStep = 4
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open overlay settings: ${e.message}")
            halitePrefs.display.setupPendingStep = 0
            finishSetup()
        }
    }

    private fun openExactAlarmSettings() {
        halitePrefs.display.setupPendingStep = 2
        try {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open exact alarm settings: ${e.message}")
            halitePrefs.display.setupPendingStep = 0
            advanceToNextPermission(afterDeviceAdmin = true, afterExactAlarm = true)
        }
    }

    private fun openDeviceAdminSettings() {
        halitePrefs.display.setupPendingStep = 1
        try {
            activity.startActivity(
                com.dashieapp.Dashie.halite.DeviceAdminHelper.buildActivationIntent(
                    activity,
                    com.dashieapp.Dashie.halite.DeviceAdminHelper.screenOffExplanation(activity)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not open device admin settings: ${e.message}")
            halitePrefs.display.setupPendingStep = 0
            advanceToNextPermission(afterDeviceAdmin = true)
        }
    }

    private fun finishSetup() {
        halitePrefs.display.betaWelcomeShown = true
        Log.i(TAG, "Permission setup complete")
        showSwipeTip()
    }

    // ========== Swipe Tip ==========

    /**
     * Show the swipe tip popup dialog (only on first use), then toast.
     */
    private fun showSwipeTip() {
        if (halitePrefs.display.swipeTipShown) {
            Log.i(TAG, "Swipe tip already shown - skipping")
            return
        }

        Log.i(TAG, "Showing swipe tip popup dialog (first time)")

        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_swipe_gesture, null)

        // Both variants are set here now. The touch copy used to be a hardcoded literal in the
        // layout and the TV copy a hardcoded literal here — so only one of the two was even
        // visible to the brand lint (it scans res/, not the dex). Setting both from resources
        // keeps them together and makes both scannable.
        val isTvDevice = DeviceInfoHelper.isFireTV() || DeviceInfoHelper.isTVDevice(activity)
        val brand = activity.getString(R.string.brand_name)
        dialogView.findViewById<TextView>(R.id.swipeDescription)?.text = activity.getString(
            if (isTvDevice) R.string.swipe_gesture_body_tv else R.string.swipe_gesture_body,
            brand,
        )

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .setOnDismissListener {
                halitePrefs.display.swipeTipShown = true
            }
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val gotItBtn = dialogView.findViewById<Button>(R.id.gotItButton)
        gotItBtn.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        applyImmersiveModeToDialog(dialog)
        // Ensure button is focusable for d-pad and request focus after layout
        gotItBtn.isFocusable = true
        gotItBtn.post { gotItBtn.requestFocus() }
    }

}

package com.dashieapp.Dashie.halite.sidebar.dialogs

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.DashieDeviceAdminReceiver
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.sidebar.SettingsCallbacks

/**
 * Dialogs for motion wake and camera-related settings including:
 * - Camera permission explanation
 * - Device admin for screen off mode
 *
 * Note: The large showMotionWakeModePicker function remains in DialogPickers.kt
 * due to its complexity (camera preview, motion graph, etc.) and will be moved
 * in a future refactoring.
 */
class MotionCameraDialogs(
    private val activity: Activity,
    private val halitePrefs: HalitePreferences
) {
    companion object {
        private const val TAG = "MotionCameraDialogs"
    }

    // Callbacks for actions that need external handling
    var settingsCallbacks: SettingsCallbacks? = null

    /**
     * Show camera permission explanation dialog
     */
    fun showCameraPermissionExplanation(onContinue: () -> Unit) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Camera Permission"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            "Camera motion detection uses your device's front camera to detect movement and wake the screen.\n\n" +
            "The camera feed is processed locally and is never stored or transmitted."

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.buttonPositive).text = "Enable"
        dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
            dialog.dismiss()
            onContinue()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        DialogHelper.setDefaultFocusOnCancel(dialogView)
    }

    /**
     * Check if Device Admin is enabled for Screen Off mode.
     * Shows info about touch-to-wake not working, and prompts for Device Admin if not enabled.
     */
    fun checkDeviceAdminForScreenOff() {
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
}

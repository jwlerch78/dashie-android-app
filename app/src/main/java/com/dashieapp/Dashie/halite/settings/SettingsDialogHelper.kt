package com.dashieapp.Dashie.halite.settings

import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.dashieapp.Dashie.MainActivity
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment
import com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper
import com.dashieapp.Dashie.edition.brandName

/**
 * Open battery optimization settings for the app.
 */
fun SettingsActivity.openBatteryOptimization() {
    try {
        if (com.dashieapp.Dashie.halite.BatteryOptimizationHelper.isExempt(this)) {
            startActivity(com.dashieapp.Dashie.halite.BatteryOptimizationHelper.buildSettingsListIntent())
        } else {
            startActivity(com.dashieapp.Dashie.halite.BatteryOptimizationHelper.buildRequestExemptionIntent(this))
        }
    } catch (e: Exception) {
        Toast.makeText(this, "Could not open battery settings", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Show a styled confirmation dialog using dialog_confirm.xml layout.
 * Matches the exit app confirmation dialog style.
 */
fun SettingsActivity.showStyledConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "OK",
    cancelLabel: String = "Cancel",
    isDestructive: Boolean = false,
    onConfirm: () -> Unit
) {
    val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)

    dialogView.findViewById<TextView>(R.id.dialogTitle).text = title
    dialogView.findViewById<TextView>(R.id.dialogMessage).text = message

    val dialog = AlertDialog.Builder(this)
        .setView(dialogView)
        .setCancelable(true)
        .create()

    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes = attributes?.apply { dimAmount = 0.5f }
    }

    dialogView.findViewById<Button>(R.id.buttonNegative).apply {
        text = cancelLabel
        setOnClickListener { dialog.dismiss() }
    }

    dialogView.findViewById<Button>(R.id.buttonPositive).apply {
        text = confirmLabel
        setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
    }

    dialog.show()
    DialogHelper.applyImmersiveModeToDialog(dialog)
}

fun SettingsActivity.showClearCacheConfirmation() {
    val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)

    dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Clear Cache"
    dialogView.findViewById<TextView>(R.id.dialogMessage).text =
        "Clear all cached data? The app will restart."

    val dialog = AlertDialog.Builder(this)
        .setView(dialogView)
        .setCancelable(true)
        .create()

    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes = attributes?.apply { dimAmount = 0.5f }
    }

    dialogView.findViewById<Button>(R.id.buttonNegative).apply {
        text = "Cancel"
        setOnClickListener { dialog.dismiss() }
    }

    dialogView.findViewById<Button>(R.id.buttonPositive).apply {
        text = "Clear Cache"
        setOnClickListener {
            dialog.dismiss()
            // Clear WebView cache and restart. clearCache(true) is async and races
            // the relaunch's WebView load, so flag the next WebView config to use
            // LOAD_NO_CACHE — that guarantees fresh JS even if the eviction hasn't
            // finished. (Same-process restart, so the static flag survives.)
            com.dashieapp.Dashie.MainWebViewSetup.forceFreshLoadOnce = true
            SettingsActivity.webViewRef?.get()?.clearCache(true)
            val intent = Intent(
                this@showClearCacheConfirmation,
                MainActivity::class.java
            )
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    dialog.show()
    DialogHelper.applyImmersiveModeToDialog(dialog)
    DialogHelper.setDefaultFocusOnCancel(dialogView)
}

/**
 * Show a confirmation dialog for relinquishing device owner status.
 */
fun SettingsActivity.showRelinquishDeviceOwnerConfirmation() {
    val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)

    dialogView.findViewById<TextView>(R.id.dialogTitle).text =
        "Relinquish Device Owner"
    dialogView.findViewById<TextView>(R.id.dialogMessage).text =
        "This will remove device owner privileges. Screen pinning and hardware screen-off will no longer work. This cannot be undone."

    val dialog = AlertDialog.Builder(this)
        .setView(dialogView)
        .setCancelable(true)
        .create()

    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes = attributes?.apply { dimAmount = 0.5f }
    }

    dialogView.findViewById<Button>(R.id.buttonNegative).apply {
        text = "Cancel"
        setOnClickListener { dialog.dismiss() }
    }

    dialogView.findViewById<Button>(R.id.buttonPositive).apply {
        text = "Relinquish"
        setOnClickListener {
            dialog.dismiss()
            try {
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as? DevicePolicyManager
                dpm?.clearDeviceOwnerApp(packageName)
                // Refresh the fragment to hide the button
                val fragment = supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer)
                if (fragment is SchemaSettingsFragment) {
                    fragment.refresh()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@showRelinquishDeviceOwnerConfirmation,
                    "Failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    dialog.show()
    DialogHelper.applyImmersiveModeToDialog(dialog)
    DialogHelper.setDefaultFocusOnCancel(dialogView)
}

/**
 * Show a dialog to edit the device friendly name.
 * On save, persists to ConnectionPreferences and restarts Sendspin.
 */
fun SettingsActivity.showEditDeviceNameDialog() {
    val dialogView = layoutInflater.inflate(R.layout.dialog_input, null)
    val connPrefs = HalitePreferences(this).connection
    val currentName = connPrefs.deviceFriendlyName.ifEmpty {
        "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Device Name"
    val input = dialogView.findViewById<EditText>(R.id.dialogInput)
    input.setText(currentName)
    input.setSelection(currentName.length)
    input.hint = "e.g. Mio 15\" ${brandName()}"

    val dialog = AlertDialog.Builder(this)
        .setView(dialogView)
        .setCancelable(true)
        .create()

    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes = attributes?.apply { dimAmount = 0.5f }
    }

    dialogView.findViewById<Button>(R.id.buttonNegative).apply {
        text = "Cancel"
        setOnClickListener { dialog.dismiss() }
    }

    dialogView.findViewById<Button>(R.id.buttonPositive).apply {
        text = "Save"
        setOnClickListener {
            val newName = input.text.toString().trim()
            connPrefs.deviceFriendlyName = newName
            // #8/M5 (build-plan 20260722_DEVICE_NAME_CONVERGENCE_PLAN.md): a native rename
            // is now a Console-equivalent rename — push it to the cloud SSOT
            // (user_devices.device_name) so the Console/family/HA reconciliation see it.
            // deviceFriendlyName (above) stays the immediate local value MQTT/music read.
            if (newName.isNotBlank()) {
                sendBroadcast(
                    Intent("com.dashieapp.Dashie.ACTION_DEVICE_NAME_CHANGED").apply {
                        setPackage(packageName)
                        putExtra("name", newName)
                    }
                )
            }
            dialog.dismiss()
            // Refresh the schema page to show the updated name
            val fragment = supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer)
            if (fragment is SchemaSettingsFragment) {
                fragment.refresh()
            }
            // Restart Sendspin so it re-registers with the new name
            sendBroadcast(
                Intent("com.dashieapp.Dashie.ACTION_RESTART_SENDSPIN").apply {
                    setPackage(packageName)
                }
            )
        }
    }

    dialog.show()
    DialogHelper.applyImmersiveModeToDialog(dialog)
    // Show keyboard for the input
    input.requestFocus()
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
}

/**
 * Open the wake calibration dialog (camera or face mode).
 * @param mode "camera" or "face" — determines which calibration UI to show
 */
fun SettingsActivity.openWakeCalibrationDialog(mode: String) {
    finish()
    sendBroadcast(
        Intent("com.dashieapp.Dashie.ACTION_OPEN_WAKE_CALIBRATION").apply {
            setPackage(packageName)
            putExtra("mode", mode)
        }
    )
}

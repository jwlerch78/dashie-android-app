package com.dashieapp.Dashie.halite.diagnostics

import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper
import com.dashieapp.Dashie.edition.brandName

/**
 * Surfaces a one-time warning dialog when a known Fire TV idle-management activity
 * (e.g. com.amazon.stillwatching.activity / "Still Watching") was detected as the
 * focus-stealer during the prior pause window.
 *
 * Pattern matches the device-permissions dialog in MainPermissionDelegate: the existing
 * `R.layout.dialog_confirm` layout is reused and a "Don't show again" CheckBox is added
 * programmatically before the buttons row. The user's checkbox state is persisted to
 * `HalitePreferences.display.firetvIdleWarningDismissed`; once set, this dialog never
 * shows again unless the user clears app data.
 */
object FireTvIdleKillDialog {
    private const val TAG = "FireTvIdleKillDialog"

    private val KILLER_LABELS = mapOf(
        "com.amazon.stillwatching.activity" to "\"Still Watching\""
    )

    /**
     * Show the dialog if the user hasn't permanently dismissed it. Safe to call from
     * the main thread; no-op if [killerPackage] is null or dismissal is set.
     *
     * @param killerPackage The package name returned by
     *   [CrashDiagnosticsCollector.logFocusStealersForPriorPause].
     */
    fun showIfApplicable(
        activity: ComponentActivity,
        halitePrefs: HalitePreferences,
        killerPackage: String
    ) {
        if (halitePrefs.display.firetvIdleWarningDismissed) {
            Log.d(TAG, "Suppressed (user previously checked 'Don't show again'): $killerPackage")
            return
        }

        val killerLabel = KILLER_LABELS[killerPackage] ?: killerPackage
        Log.i(TAG, "Showing Fire TV idle-kill warning for $killerPackage")

        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Fire TV closed ${activity.brandName()}"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text = buildString {
            append("Fire TV's $killerLabel feature paused ${activity.brandName()} while you were away.\n\n")
            append("To prevent this, on your Fire TV go to:\n")
            append("Settings → Preferences → Data Usage Monitoring → Still Watching → Off")
        }

        // Add "Don't show again" checkbox between message and buttons (mirrors the
        // device-permissions dialog in MainPermissionDelegate at the same location).
        val rootLayout = dialogView as android.widget.LinearLayout
        val density = activity.resources.displayMetrics.density
        val dontShowCheckbox = android.widget.CheckBox(activity).apply {
            text = "Don't show again"
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 14f
            gravity = android.view.Gravity.CENTER_VERTICAL
            minHeight = (36 * density).toInt()
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFFFF9500.toInt())
        }
        val checkboxParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * density).toInt() }
        rootLayout.addView(dontShowCheckbox, rootLayout.childCount - 1, checkboxParams)

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .setOnCancelListener {
                if (dontShowCheckbox.isChecked) {
                    halitePrefs.display.firetvIdleWarningDismissed = true
                }
            }
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val positiveBtn = dialogView.findViewById<Button>(R.id.buttonPositive)
        val negativeBtn = dialogView.findViewById<Button>(R.id.buttonNegative)

        positiveBtn.text = "Open Settings"
        positiveBtn.setOnClickListener {
            if (dontShowCheckbox.isChecked) {
                halitePrefs.display.firetvIdleWarningDismissed = true
            }
            dialog.dismiss()
            try {
                // Best effort: Fire TV doesn't expose a deep-link to the Still Watching toggle,
                // so we open the top of system Settings and rely on the dialog's printed path.
                activity.startActivity(
                    Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open Settings: ${e.message}")
            }
        }

        negativeBtn.text = "Got it"
        negativeBtn.setOnClickListener {
            if (dontShowCheckbox.isChecked) {
                halitePrefs.display.firetvIdleWarningDismissed = true
            }
            dialog.dismiss()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        DialogHelper.applyBorderButtonFocusHighlight(negativeBtn, dialogStyle = true)
        positiveBtn.isFocusable = true
        negativeBtn.isFocusable = true
        positiveBtn.nextFocusLeftId = R.id.buttonNegative
        negativeBtn.nextFocusRightId = R.id.buttonPositive
        positiveBtn.post { positiveBtn.requestFocus() }
    }
}

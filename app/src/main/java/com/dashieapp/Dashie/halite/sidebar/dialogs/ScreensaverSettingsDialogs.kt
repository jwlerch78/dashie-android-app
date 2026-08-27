package com.dashieapp.Dashie.halite.sidebar.dialogs

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaTokenExtractor
import com.dashieapp.Dashie.halite.screensaver.HaMediaPhotoSource
import com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences
import com.dashieapp.Dashie.halite.sidebar.SettingsCallbacks
import com.dashieapp.Dashie.halite.sidebar.SidebarFormatters
import com.dashieapp.Dashie.util.TimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.dashieapp.Dashie.edition.brandName

/**
 * Dialogs for screensaver settings including:
 * - Timeout picker
 * - Screensaver mode picker (with photo source selection)
 * - HA Media folder picker
 */
class ScreensaverSettingsDialogs(
    private val activity: Activity,
    private val halitePrefs: HalitePreferences,
    private val webViewProvider: (() -> WebView?)? = null
) {
    private val webView: WebView? get() = webViewProvider?.invoke()

    companion object {
        private const val TAG = "ScreensaverDialogs"
    }

    // Callbacks for actions that need external handling
    var onRestartRequired: ((String) -> Unit)? = null
    var settingsCallbacks: SettingsCallbacks? = null

    /**
     * Show lock navigation confirmation dialog
     */
    fun showLockNavigationConfirmationDialog(onConfirm: () -> Unit) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Lock Nav Bar?"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            "This prevents the navigation bar from opening while ${activity.brandName()} is running.\n\n" +
            "To unlock, go to Settings > Lock Nav Bar and disable."

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.buttonPositive).text = "Lock"
        dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        DialogHelper.setDefaultFocusOnCancel(dialogView)
    }
}

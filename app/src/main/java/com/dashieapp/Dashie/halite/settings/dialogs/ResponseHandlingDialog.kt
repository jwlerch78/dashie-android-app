package com.dashieapp.Dashie.halite.settings.dialogs

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.HalitePreferences

/**
 * Dialog for selecting response handling mode.
 * Shows how voice assistant responses should be delivered (read, display, both, or none).
 */
class ResponseHandlingDialog(
    private val activity: Activity,
    private val halitePrefs: HalitePreferences
) {
    companion object {
        private const val TAG = "ResponseHandlingDialog"
    }

    /**
     * Show the response handling dialog.
     * @param onSettingsChanged Callback when settings are saved
     */
    fun show(onSettingsChanged: () -> Unit) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_response_handling, null)

        // Get UI references
        val radioGroup = dialogView.findViewById<RadioGroup>(R.id.radioGroupResponseHandling)
        val radioReadAndDisplay = dialogView.findViewById<RadioButton>(R.id.radioReadAndDisplay)
        val radioReadOnly = dialogView.findViewById<RadioButton>(R.id.radioReadOnly)
        val radioDisplayOnly = dialogView.findViewById<RadioButton>(R.id.radioDisplayOnly)
        val radioNone = dialogView.findViewById<RadioButton>(R.id.radioNone)
        val buttonCancel = dialogView.findViewById<Button>(R.id.buttonCancel)
        val buttonSave = dialogView.findViewById<Button>(R.id.buttonSave)

        // Set current selection
        when (halitePrefs.voice.responseHandling) {
            HalitePreferences.RESPONSE_HANDLING_READ_AND_DISPLAY -> radioReadAndDisplay.isChecked = true
            HalitePreferences.RESPONSE_HANDLING_READ_ONLY -> radioReadOnly.isChecked = true
            HalitePreferences.RESPONSE_HANDLING_DISPLAY_ONLY -> radioDisplayOnly.isChecked = true
            HalitePreferences.RESPONSE_HANDLING_NONE -> radioNone.isChecked = true
            else -> radioReadAndDisplay.isChecked = true // Default
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Cancel button
        buttonCancel.setOnClickListener {
            dialog.dismiss()
        }

        // Save button
        buttonSave.setOnClickListener {
            val selectedMode = when (radioGroup.checkedRadioButtonId) {
                R.id.radioReadAndDisplay -> HalitePreferences.RESPONSE_HANDLING_READ_AND_DISPLAY
                R.id.radioReadOnly -> HalitePreferences.RESPONSE_HANDLING_READ_ONLY
                R.id.radioDisplayOnly -> HalitePreferences.RESPONSE_HANDLING_DISPLAY_ONLY
                R.id.radioNone -> HalitePreferences.RESPONSE_HANDLING_NONE
                else -> HalitePreferences.RESPONSE_HANDLING_READ_AND_DISPLAY
            }

            halitePrefs.voice.responseHandling = selectedMode
            Log.i(TAG, "🔧 Response handling = $selectedMode")

            // Notify voice controller of the change via MainActivity
            if (activity is com.dashieapp.Dashie.MainActivity) {
                val mainActivity = activity as com.dashieapp.Dashie.MainActivity
                mainActivity.updateResponseHandling(selectedMode)
            }

            dialog.dismiss()
            onSettingsChanged()
        }

        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
    }
}

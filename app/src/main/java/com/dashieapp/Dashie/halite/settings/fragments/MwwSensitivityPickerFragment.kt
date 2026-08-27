package com.dashieapp.Dashie.halite.settings.fragments

import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.settings.items.SettingsItem
import com.dashieapp.Dashie.wakeword.models.WakeWordModel
import com.dashieapp.Dashie.wakeword.models.WakeWordModelManager
import com.dashieapp.Dashie.wakeword.models.WakeWordSensitivity

/**
 * Picker fragment for wake word detection sensitivity (Low / Medium / High).
 */
class MwwSensitivityPickerFragment : BaseSettingsFragment() {

    companion object {
        private const val TAG = "Sensitivity"
    }

    override val title: String = "Sensitivity"

    private val halitePrefs by lazy { HalitePreferences(requireContext()) }

    override fun getItems(): List<SettingsItem> {
        val currentValue = halitePrefs.voice.mwwSensitivity
        val currentSensitivity = WakeWordSensitivity.fromString(currentValue)
        val activeModel = WakeWordModelManager(requireContext()).getActiveModel()

        val items = mutableListOf<SettingsItem>()

        for (sensitivity in WakeWordSensitivity.entries) {
            items.add(SettingsItem.Checkmark(
                id = "sensitivity_${sensitivity.name}",
                label = sensitivity.displayName,
                isChecked = currentSensitivity == sensitivity
            ))
        }

        return items
    }

    override fun handleItemClick(item: SettingsItem) {
        if (item !is SettingsItem.Checkmark) return
        val value = item.id.removePrefix("sensitivity_")
        val sensitivity = WakeWordSensitivity.fromString(value)

        halitePrefs.voice.mwwSensitivity = sensitivity.name
        Log.i(TAG, "Sensitivity set to: ${sensitivity.displayName}")
        refreshItems()
    }
}

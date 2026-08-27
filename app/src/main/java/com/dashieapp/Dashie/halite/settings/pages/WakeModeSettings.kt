package com.dashieapp.Dashie.halite.settings.pages

import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.SettingsActivity

/**
 * Create the Wake Mode fragment with inline checkmark mode selection.
 * Schema is dynamic — `checked` state is computed from live preferences each render.
 * Navigation clicks on mode items are intercepted to set the preference and refresh.
 */
internal fun SettingsActivity.createWakeModeFragment(): com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment {
    val prefs = com.dashieapp.Dashie.halite.HalitePreferences(this)
    return com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = {
            val currentMode = prefs.screensaver.motionWakeMode
            com.dashieapp.Dashie.halite.settings.schemas.WakeModePageSchema.create(currentMode)
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            if (target.startsWith("action:set_wake_mode:")) {
                val newMode = target.removePrefix("action:set_wake_mode:")
                prefs.screensaver.motionWakeMode = newMode
                sendBroadcast(
                    android.content.Intent("com.dashieapp.Dashie.ACTION_REFRESH_MOTION_WAKE").apply {
                        setPackage(packageName)
                    }
                )
                val currentFragment = supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer)
                if (currentFragment is com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment) {
                    currentFragment.refresh()
                }
                true
            } else {
                false
            }
        }
    )
}

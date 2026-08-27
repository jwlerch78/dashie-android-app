package com.dashieapp.Dashie.halite.settings.pages

import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.SettingsActivity

internal fun SettingsActivity.createScreensaverFragment(): com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment {
    val activity = this
    return com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = {
            val uiMode = activity.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_TYPE_MASK
            val isTv = uiMode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
            val halitePrefs = com.dashieapp.Dashie.halite.HalitePreferences(activity)
            val isHaEnabled = halitePrefs.connection.haEnabled
            val isAdvancedEnabled = isHaEnabled || halitePrefs.connection.advancedTabletControlsEnabled
            com.dashieapp.Dashie.halite.settings.schemas.ScreensaverPageSchema.create(
                isTv = isTv,
                isAdvancedEnabled = isAdvancedEnabled
            )
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            val halitePrefs = com.dashieapp.Dashie.halite.HalitePreferences(this@createScreensaverFragment)
            val ssPrefs = halitePrefs.screensaver
            val dialogs = com.dashieapp.Dashie.halite.sidebar.SidebarScreensaverDialogs(this@createScreensaverFragment, halitePrefs)
            when (target) {
                "ext:app_picker" -> {
                    dialogs.showAppPicker { packageName, label ->
                        ssPrefs.launchAppPackage = packageName
                        ssPrefs.launchAppLabel = label
                        val currentFragment = supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer)
                        if (currentFragment is com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment) {
                            currentFragment.refresh()
                        }
                    }
                    true
                }
                "ext:ha_media_folder_picker" -> {
                    dialogs.showHaMediaFolderPickerFromBridge { _ ->
                        val currentFragment = supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer)
                        if (currentFragment is com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment) {
                            currentFragment.refresh()
                        }
                    }
                    true
                }
                "ext:open_photos_page" -> {
                    showFragment(createPhotosFragment(), "photos")
                    true
                }
                else -> false
            }
        }
    )
}

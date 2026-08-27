package com.dashieapp.Dashie.halite.settings.pages

import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.SettingsActivity
import com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring

/**
 * Create the Photos schema fragment.
 * Handles ext:ha_media_folder_picker navigation for HA media folder selection.
 */
internal fun SettingsActivity.createPhotosFragment(): com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment {
    val subPrefs = com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences(this)
    val isLoggedIn = subPrefs.subscriptionStatus.isNotEmpty() &&
        subPrefs.subscriptionStatus !in listOf("", "trial_expired", "canceled")
    // HA Media + Immich photo sources require an HA connection — gate
    // both on the per-device haEnabled flag so they don't appear when
    // HA isn't configured (avoids the user picking a dead-end source).
    // Note: previously used account.isLinked which tracks Dashie auth,
    // not HA — that's why HA Media + Immich kept appearing for users
    // logged into Dashie without HA.
    val isHaEnabled = com.dashieapp.Dashie.halite.HalitePreferences(this).connection.haEnabled
    // Background validity check: if the stored Immich token no longer works,
    // the status row will flip to "Sign-in expired" on the next refresh.
    SettingsSchemaWiring.pingImmichToken(this, com.dashieapp.Dashie.halite.HalitePreferences(this))
    return com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = { com.dashieapp.Dashie.halite.settings.schemas.PhotosPageSchema.create(isLoggedIn, isHaEnabled) },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            when (target) {
                "ext:ha_media_folder_picker" -> {
                    val halitePrefs = com.dashieapp.Dashie.halite.HalitePreferences(this@createPhotosFragment)
                    val dialogs = com.dashieapp.Dashie.halite.sidebar.SidebarScreensaverDialogs(this@createPhotosFragment, halitePrefs)
                    dialogs.showHaMediaFolderPickerFromBridge { _ ->
                        val currentFragment = supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer)
                        if (currentFragment is com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment) {
                            currentFragment.refresh()
                        }
                    }
                    true
                }
                "ext:immich_login" -> {
                    val halitePrefs = com.dashieapp.Dashie.halite.HalitePreferences(this@createPhotosFragment)
                    val refresh = {
                        val currentFragment = supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer)
                        if (currentFragment is com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment) {
                            currentFragment.refresh()
                        }
                    }
                    if (halitePrefs.screensaver.hasImmichConfig) {
                        // Already configured — show the 3-button d-pad-compatible
                        // confirm dialog (same look as Family-Edit "Save Changes?").
                        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_3, null)
                        dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle).text = "Immich Account"
                        dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage).text =
                            "Signed in to ${halitePrefs.screensaver.immichServerUrl}"

                        val dialog = android.app.AlertDialog.Builder(this@createPhotosFragment)
                            .setView(dialogView)
                            .setCancelable(true)
                            .create()
                        dialog.window?.apply {
                            setBackgroundDrawableResource(android.R.color.transparent)
                            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                            attributes = attributes?.apply { dimAmount = 0.5f }
                        }

                        dialogView.findViewById<android.widget.Button>(R.id.buttonNegative).apply {
                            text = "Cancel"
                            setOnClickListener { dialog.dismiss() }
                        }
                        dialogView.findViewById<android.widget.Button>(R.id.buttonNeutral).apply {
                            text = "Sign out"
                            setOnClickListener {
                                dialog.dismiss()
                                SettingsSchemaWiring.signOutImmich(this@createPhotosFragment, halitePrefs) {
                                    refresh()
                                }
                            }
                        }
                        dialogView.findViewById<android.widget.Button>(R.id.buttonPositive).apply {
                            text = "Re-authenticate"
                            setOnClickListener {
                                dialog.dismiss()
                                SettingsSchemaWiring.signOutImmich(this@createPhotosFragment, halitePrefs) {
                                    SettingsSchemaWiring.launchImmichLogin(this@createPhotosFragment, halitePrefs) {
                                        refresh()
                                    }
                                }
                            }
                        }

                        dialog.show()
                        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
                        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.setDefaultFocusOnCancel(dialogView)
                    } else {
                        SettingsSchemaWiring.launchImmichLogin(this@createPhotosFragment, halitePrefs) {
                            refresh()
                        }
                    }
                    true
                }
                "ext:immich_album_picker" -> {
                    val halitePrefs = com.dashieapp.Dashie.halite.HalitePreferences(this@createPhotosFragment)
                    SettingsSchemaWiring.showImmichAlbumPicker(this@createPhotosFragment, halitePrefs) {
                        val currentFragment = supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer)
                        if (currentFragment is com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment) {
                            currentFragment.refresh()
                        }
                        sendBroadcast(
                            android.content.Intent("com.dashieapp.Dashie.ACTION_PHOTO_SOURCE_CHANGED").apply {
                                setPackage(packageName)
                            }
                        )
                    }
                    true
                }
                else -> false
            }
        }
    )
}

/**
 * Poll RTSP status every 500ms for up to 10s after enabling,
 * refreshing the fragment to update the status display.
 */
internal fun SettingsActivity.pollCameraStatus() {
    stopCameraStatusPoll()
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    cameraStatusPollHandler = handler
    var attempts = 0
    val maxAttempts = 20

    val runnable = object : Runnable {
        override fun run() {
            attempts++
            val fragment = supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer)
            if (fragment is com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment) {
                fragment.refresh()
            }

            val isRunning = SettingsActivity.isRtspRunning?.invoke() ?: false
            val hasFailed = SettingsActivity.hasRtspFailed?.invoke() ?: false
            val enabled = schemaContext.valueProvider.getBoolean("camera.enabled")

            if (!enabled || isRunning || hasFailed || attempts >= maxAttempts) {
                stopCameraStatusPoll()
            } else {
                handler.postDelayed(this, 500)
            }
        }
    }
    cameraStatusPollRunnable = runnable
    handler.postDelayed(runnable, 500)
}

internal fun SettingsActivity.stopCameraStatusPoll() {
    cameraStatusPollRunnable?.let { cameraStatusPollHandler?.removeCallbacks(it) }
    cameraStatusPollHandler = null
    cameraStatusPollRunnable = null
}

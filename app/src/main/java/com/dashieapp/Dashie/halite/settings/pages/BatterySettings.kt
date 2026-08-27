package com.dashieapp.Dashie.halite.settings.pages

import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.SettingsActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal fun SettingsActivity.createBatteryChargingFragment(): com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment {
    val powerPrefs = com.dashieapp.Dashie.halite.preferences.PowerPreferences(this)
    val halitePrefs = com.dashieapp.Dashie.halite.HalitePreferences(this)

    // Load initial battery status
    loadBatteryStatus()
    loadSwitchState(powerPrefs, halitePrefs)

    return com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = com.dashieapp.Dashie.halite.settings.schemas.BatteryChargingPageSchema::create,
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            when (target) {
                "ext:power_entity_picker" -> {
                    val fragment = com.dashieapp.Dashie.halite.settings.fragments.PowerEntityPickerFragment.create(
                        powerPrefs = powerPrefs,
                        halitePrefs = halitePrefs,
                        onDismiss = {
                            // Refresh parent to update entity display
                            val current = supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer)
                            if (current is com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment) {
                                current.refresh()
                            }
                        }
                    )
                    showFragment(fragment, "power_entity_picker")
                    true
                }
                else -> false
            }
        }
    )
}

/** Read battery level from Android BatteryManager intent. */
internal fun SettingsActivity.loadBatteryStatus() {
    try {
        val intent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1

        SettingsActivity.currentBatteryLevel = if (level >= 0 && scale > 0) (level * 100) / scale else null
        SettingsActivity.currentBatteryCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
    } catch (_: Exception) {
        SettingsActivity.currentBatteryLevel = null
        SettingsActivity.currentBatteryCharging = null
    }
}

/** Query the HA REST API for the switch entity's current state. */
internal fun SettingsActivity.loadSwitchState(
    powerPrefs: com.dashieapp.Dashie.halite.preferences.PowerPreferences,
    halitePrefs: com.dashieapp.Dashie.halite.HalitePreferences
) {
    val entityId = powerPrefs.entityId
    if (entityId.isEmpty()) {
        SettingsActivity.powerSwitchState = false
        return
    }

    Thread {
        try {
            val (baseUrl, token) = com.dashieapp.Dashie.halite.HaTokenExtractor.getValidCredentialsSync(halitePrefs) ?: return@Thread

            val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url("$baseUrl/api/states/$entityId")
                .addHeader("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use
                    val state = org.json.JSONObject(body).optString("state", "unknown")
                    SettingsActivity.powerSwitchState = state == "on"
                    SettingsActivity.powerSwitchReachable = true
                } else {
                    SettingsActivity.powerSwitchReachable = false
                }
                runOnUiThread {
                    val fragment = supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer)
                    if (fragment is com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment) {
                        fragment.refresh()
                    }
                }
            }
        } catch (_: Exception) {
            SettingsActivity.powerSwitchReachable = false
            runOnUiThread {
                val fragment = supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer)
                if (fragment is com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment) {
                    fragment.refresh()
                }
            }
        }
    }.start()
}

/**
 * Toggle the HA smart switch on/off via REST API.
 * Called from the toggle callback when the user flips the switch status toggle.
 *
 * If the toggle would move outside the configured min/max range, shows a
 * confirmation dialog first. The watchdog is notified with the appropriate
 * override target (100% or 5%) so it respects the user's intent.
 */
fun SettingsActivity.togglePowerSwitch(powerPrefs: com.dashieapp.Dashie.halite.preferences.PowerPreferences) {
    val entityId = powerPrefs.entityId
    if (entityId.isEmpty()) return

    val batteryLevel = SettingsActivity.currentBatteryLevel ?: -1
    val wantOn = !SettingsActivity.powerSwitchState  // the state we'd toggle TO

    // Check if this toggle is outside the configured range
    if (wantOn && batteryLevel > powerPrefs.maxThreshold) {
        // Turning ON above max — will charge to 100%
        showPowerOverrideConfirmation(
            "This will override the battery management system. " +
            "The current battery level is already above the max threshold " +
            "(${powerPrefs.maxThreshold}%) and it will charge to 100% and remain there.",
            onConfirm = { executePowerToggle(powerPrefs, turnOn = true, overrideTarget = 100) },
            onCancel = { revertSwitchToggle() }
        )
    } else if (!wantOn && batteryLevel < powerPrefs.minThreshold) {
        // Turning OFF below min — will discharge to 5%
        showPowerOverrideConfirmation(
            "This will override the battery management system. " +
            "The current battery level is below the min threshold " +
            "(${powerPrefs.minThreshold}%) and will continue to discharge down to 5%.",
            onConfirm = { executePowerToggle(powerPrefs, turnOn = false, overrideTarget = 5) },
            onCancel = { revertSwitchToggle() }
        )
    } else {
        // Normal toggle within range
        executePowerToggle(powerPrefs, turnOn = wantOn, overrideTarget = null)
    }
}

internal fun SettingsActivity.showPowerOverrideConfirmation(
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    android.app.AlertDialog.Builder(this)
        .setTitle("Override Battery Management")
        .setMessage(message)
        .setPositiveButton("Override") { _, _ -> onConfirm() }
        .setNegativeButton("Cancel") { _, _ -> onCancel() }
        .setCancelable(false)
        .show()
}

/** Revert the switch toggle UI when the user cancels the confirmation. */
internal fun SettingsActivity.revertSwitchToggle() {
    val fragment = supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer)
    if (fragment is com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment) {
        fragment.refresh()
    }
}

/**
 * Execute the actual power switch toggle.
 * @param overrideTarget If non-null, tells the watchdog to hold the override until
 *   battery reaches this level (100 for charge-to-full, 5 for discharge-to-5%).
 */
internal fun SettingsActivity.executePowerToggle(
    powerPrefs: com.dashieapp.Dashie.halite.preferences.PowerPreferences,
    turnOn: Boolean,
    overrideTarget: Int?
) {
    val entityId = powerPrefs.entityId
    val halitePrefs = com.dashieapp.Dashie.halite.HalitePreferences(this)

    // Update state
    SettingsActivity.powerSwitchState = turnOn
    SettingsActivity.currentBatteryCharging = turnOn
    SettingsActivity.onPowerManualToggle?.invoke(turnOn, overrideTarget)

    // Refresh UI
    val fragment = supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer)
    if (fragment is com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment) {
        fragment.refresh()
    }

    Thread {
        try {
            val (baseUrl, token) = com.dashieapp.Dashie.halite.HaTokenExtractor.getValidCredentialsSync(halitePrefs) ?: return@Thread

            val action = if (turnOn) "turn_on" else "turn_off"
            val url = "$baseUrl/api/services/switch/$action"
            val body = org.json.JSONObject().put("entity_id", entityId).toString()

            val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { /* fire and forget */ }

            // Verify state after a delay
            Thread.sleep(1500)
            loadSwitchState(powerPrefs, halitePrefs)
            loadBatteryStatus()
            runOnUiThread {
                val frag = supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer)
                if (frag is com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment) {
                    frag.refresh()
                }
            }
        } catch (_: Exception) {}
    }.start()
}

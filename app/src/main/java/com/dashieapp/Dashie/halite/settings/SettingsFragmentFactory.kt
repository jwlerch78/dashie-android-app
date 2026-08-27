package com.dashieapp.Dashie.halite.settings

import com.dashieapp.Dashie.halite.settings.pages.createScreensaverFragment
import com.dashieapp.Dashie.halite.settings.pages.createWakeModeFragment
import com.dashieapp.Dashie.halite.settings.pages.pollCameraStatus
import com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment

/**
 * Simple fragment factory methods extracted from SettingsActivity.
 *
 * Each function is an extension on SettingsActivity so that members like
 * `schemaContext`, `showFragment()`, `webViewRef`, etc. remain accessible
 * via `this` without any parameter plumbing.
 */

// ── Camera ──────────────────────────────────────────────────────────

/**
 * Create the Camera Streaming schema fragment.
 * Polls RTSP status after enabling to show Connecting → Streaming transition.
 */
fun SettingsActivity.createCameraFragment(): SchemaSettingsFragment {
    return SchemaSettingsFragment.create(
        schemaProvider = com.dashieapp.Dashie.halite.settings.schemas.CameraPageSchema::create,
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        toggleCallback = { settingKey, _ ->
            if (settingKey == "camera.enabled") {
                // Poll for RTSP status changes after toggle
                pollCameraStatus()
            }
        }
    )
}

// ── MQTT ────────────────────────────────────────────────────────────

/**
 * Create the MQTT settings schema fragment.
 */
fun SettingsActivity.createMqttFragment(): SchemaSettingsFragment {
    return SchemaSettingsFragment.create(
        schemaProvider = com.dashieapp.Dashie.halite.settings.schemas.MqttPageSchema::create,
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry
    )
}

// ── Performance ─────────────────────────────────────────────────────

/**
 * Create the Performance & Diagnostics schema fragment.
 */
fun SettingsActivity.createPerformanceFragment(): SchemaSettingsFragment {
    return SchemaSettingsFragment.create(
        schemaProvider = com.dashieapp.Dashie.halite.settings.schemas.PerformancePageSchema::create,
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            when (target) {
                "ext:battery_optimization" -> {
                    openBatteryOptimization()
                    true
                }
                else -> false
            }
        }
    )
}

// ── Display ─────────────────────────────────────────────────────────

fun SettingsActivity.createDisplayFragment(): SchemaSettingsFragment {
    // D.51 — Kiosk option requires HA configured; Canvas (Dev) only in
    // the dev build flavor. Read both at fragment-create time so the
    // schema reflects current state.
    val isHaEnabled = com.dashieapp.Dashie.halite.HalitePreferences(this).connection.haEnabled
    val isDevFlavor = com.dashieapp.Dashie.BuildConfig.FLAVOR == "dev"
    val uiMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
    val isTv = uiMode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    return SchemaSettingsFragment.create(
        schemaProvider = {
            com.dashieapp.Dashie.halite.settings.schemas.DisplayPageSchema.create(isHaEnabled, isDevFlavor, isTv)
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            when (target) {
                "ext:sleep_page" -> {
                    showFragment(createSleepFragment(), "sleep")
                    true
                }
                "ext:screensaver_page" -> {
                    showFragment(createScreensaverFragment(), "screensaver")
                    true
                }
                "ext:wake_mode_page" -> {
                    showFragment(createWakeModeFragment(), "wake_mode")
                    true
                }
                else -> false
            }
        }
    )
}

// ── Advanced ────────────────────────────────────────────────────────

/**
 * Create the Advanced schema fragment with Performance & Diagnostics navigation.
 */
fun SettingsActivity.createAdvancedFragment(): SchemaSettingsFragment {
    return SchemaSettingsFragment.create(
        schemaProvider = com.dashieapp.Dashie.halite.settings.schemas.AdvancedPageSchema::create,
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            when (target) {
                "ext:performance" -> {
                    showFragment(createPerformanceFragment(), "performance")
                    true
                }
                "ext:edit_device_name" -> {
                    showEditDeviceNameDialog()
                    true
                }
                "ext:mqtt" -> {
                    showFragment(createMqttFragment(), "mqtt")
                    true
                }
                "ext:calibrate_motion" -> {
                    openWakeCalibrationDialog("camera")
                    true
                }
                "ext:calibrate_face" -> {
                    openWakeCalibrationDialog("face")
                    true
                }
                else -> false
            }
        }
    )
}

// ── Locations ───────────────────────────────────────────────────────

fun SettingsActivity.createLocationsFragment(): SchemaSettingsFragment {
    return SchemaSettingsFragment.create(
        schemaProvider = com.dashieapp.Dashie.halite.settings.schemas.LocationsPageSchema::create,
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry
    )
}

// ── Chores & Rewards ────────────────────────────────────────────────

fun SettingsActivity.createChoresRewardsFragment(): SchemaSettingsFragment {
    return SchemaSettingsFragment.create(
        schemaProvider = com.dashieapp.Dashie.halite.settings.schemas.ChoresRewardsPageSchema::create,
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            if (target == "ext:chores_participants_picker") {
                showChoresParticipantsPicker()
                true
            } else false
        }
    )
}

fun SettingsActivity.showChoresParticipantsPicker() {
    val choresPrefs = com.dashieapp.Dashie.halite.preferences.ChoresRewardsPreferences(this)
    val currentParticipants = choresPrefs.getParticipants() // null = all

    // Load family members via JS bridge, then show picker
    val wv = SettingsActivity.webViewRef?.get() ?: return
    wv.post {
        wv.evaluateJavascript("""
            (async () => {
                try {
                    const svc = window.familyService;
                    if (!svc) throw new Error('Family service not initialized');
                    const members = await svc.listMembers({ forceRefresh: false });
                    window.DashieNative.onChoresParticipantsLoaded(JSON.stringify(members));
                } catch (e) {
                    console.error('[ChoresParticipants] Failed to load members:', e.message);
                }
            })()
        """.trimIndent(), null)
    }

    // Wire one-shot callback via JS bridge
    val delegate = SettingsActivity.jsBridgeRef?.settingsDataDelegate ?: return
    delegate.onChoresParticipantsLoaded = { members ->
        delegate.onChoresParticipantsLoaded = null
        runOnUiThread {
            showChoresParticipantsFragment(members, currentParticipants, choresPrefs)
        }
    }
}

fun SettingsActivity.showChoresParticipantsFragment(
    members: List<com.dashieapp.Dashie.halite.settings.data.FamilyMember>,
    currentParticipants: List<String>?,
    choresPrefs: com.dashieapp.Dashie.halite.preferences.ChoresRewardsPreferences
) {
    // null = all members. Convert to explicit set of all IDs for toggling.
    val participantIds = (currentParticipants ?: members.map { it.id }).toMutableSet()

    val fragment = SchemaSettingsFragment.create(
        schemaProvider = {
            com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema(
                id = "chores_participants",
                title = "Chore Participants",
                sections = listOf(
                    com.dashieapp.Dashie.halite.settings.schema.SettingsSection(
                        footer = "Select which family members participate in chores. Uncheck all to include everyone.",
                        items = members.map { member ->
                            val isSelected = participantIds.contains(member.id)
                            com.dashieapp.Dashie.halite.settings.schema.SchemaItem.Navigation(
                                id = "participant_${member.id}",
                                label = member.shortName,
                                colorDot = member.assignedColor,
                                checked = isSelected,
                                navigateTo = "ext:toggle_participant:${member.id}"
                            )
                        }
                    )
                )
            )
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            if (target.startsWith("ext:toggle_participant:")) {
                val memberId = target.removePrefix("ext:toggle_participant:")
                if (participantIds.contains(memberId)) {
                    participantIds.remove(memberId)
                } else {
                    participantIds.add(memberId)
                }
                // If all selected or none selected, save as "all" (null)
                if (participantIds.size == members.size || participantIds.isEmpty()) {
                    choresPrefs.setParticipants(null)
                    participantIds.clear()
                    participantIds.addAll(members.map { it.id })
                } else {
                    choresPrefs.setParticipants(participantIds.toList())
                }
                // Notify the webapp so settingsStore.chores.participants stays in sync
                // (mirrors the onChanged="notifyChoresRewardsConfigChanged" used by schema toggles)
                schemaContext.callbackRegistry.invoke("notifyChoresRewardsConfigChanged")
                supportFragmentManager.fragments.filterIsInstance<
                    SchemaSettingsFragment>().lastOrNull()?.refresh()
                true
            } else false
        }
    )
    showFragment(fragment, "chores_participants")
}

// ── Sleep ────────────────────────────────────────────────────────────

/**
 * Create the Sleep / Wake schema fragment.
 * No custom navigation needed — all items use standard schema pickers/toggles.
 */
fun SettingsActivity.createSleepFragment(): SchemaSettingsFragment {
    return SchemaSettingsFragment.create(
        schemaProvider = com.dashieapp.Dashie.halite.settings.schemas.SleepPageSchema::create,
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry
    )
}

package com.dashieapp.Dashie.halite.settings.schema

import android.content.Context
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.AiPreferences
import com.dashieapp.Dashie.halite.preferences.AccountPreferences
import com.dashieapp.Dashie.halite.preferences.AlertPreferences
import com.dashieapp.Dashie.halite.preferences.CameraPreferences
import com.dashieapp.Dashie.halite.preferences.VideoFeedPreferences
import com.dashieapp.Dashie.halite.preferences.GeneralPreferences
import com.dashieapp.Dashie.halite.preferences.LockPreferences
import com.dashieapp.Dashie.halite.preferences.MqttPreferences
import com.dashieapp.Dashie.halite.preferences.PerformancePreferences
import com.dashieapp.Dashie.halite.preferences.PowerPreferences
import com.dashieapp.Dashie.halite.preferences.SleepPreferences
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.halite.settings.SettingsProfiler
import com.dashieapp.Dashie.halite.settings.schema.wiring.*

/**
 * Coordinator that wires schema setting keys to SharedPreferences properties
 * and registers side-effect callbacks.
 *
 * Delegates to domain-specific wiring modules in the `wiring/` package:
 * - [DisplaySettingsWiring] — General, Display, Sleep, Screensaver, Photos, Display Page
 * - [VoiceAiSettingsWiring] — Voice, AI
 * - [CameraSettingsWiring] — Camera, Power, Video Feeds
 * - [ConnectionSettingsWiring] — Connection (HA), Music, Account, Subscription, Locations, Chores, Calendar, Family
 * - [MqttSettingsWiring] — Performance, Advanced, MQTT
 * - [SettingsCallbackWiring] — Callbacks and Interceptors
 * - [SettingsDialogWiring] — Music Token, Immich Login, Immich Album, MA Login dialogs
 *
 * Call [create] once per SettingsActivity lifecycle.
 */
object SettingsSchemaWiring {

    data class SchemaContext(
        val valueProvider: HaliteSettingsValueProvider,
        val callbackRegistry: SettingsCallbackRegistry
    )

    fun create(context: Context): SchemaContext {
        val prefs = SettingsProfiler.measure("Wiring.HalitePreferences()") { HalitePreferences(context) }
        val perfPrefs = SettingsProfiler.measure("Wiring.PerformancePreferences()") { PerformancePreferences(context) }
        val voicePrefs = VoicePreferences(context)
        val aiPrefs = AiPreferences(context)
        val cameraPrefs = CameraPreferences(context)
        val powerPrefs = PowerPreferences(context)
        val videoFeedPrefs = VideoFeedPreferences(context)
        val sleepPrefs = SleepPreferences(context)
        val lockPrefs = LockPreferences(context)
        val mqttPrefs = MqttPreferences(context)
        val generalPrefs = GeneralPreferences(context)
        val accountPrefs = AccountPreferences(context)
        val locationsPrefs = com.dashieapp.Dashie.halite.preferences.LocationsPreferences(context)
        val choresPrefs = com.dashieapp.Dashie.halite.preferences.ChoresRewardsPreferences(context)
        val valueProvider = HaliteSettingsValueProvider(prefs)
        val callbackRegistry = SettingsCallbackRegistry()

        SettingsProfiler.measure("Wiring.registerAll") {
            // Device info (read-only keys for visibleWhen gates)
            DisplaySettingsWiring.registerDeviceInfo(valueProvider, context)

            // Display domain
            DisplaySettingsWiring.registerGeneralPreferences(valueProvider, generalPrefs)
            DisplaySettingsWiring.registerDisplayPreferences(valueProvider, prefs, context)
            DisplaySettingsWiring.registerSleepPreferences(valueProvider, prefs)
            DisplaySettingsWiring.registerScreensaverPreferences(valueProvider, prefs.screensaver)
            DisplaySettingsWiring.registerPhotoPreferences(valueProvider, prefs.screensaver)
            DisplaySettingsWiring.registerDisplayPagePreferences(valueProvider, prefs, context)

            // Performance, Advanced, MQTT
            MqttSettingsWiring.registerPerformancePreferences(valueProvider, perfPrefs, context)
            MqttSettingsWiring.registerAdvancedPreferences(valueProvider, sleepPrefs, perfPrefs, lockPrefs, context)
            MqttSettingsWiring.registerMqttPreferences(valueProvider, mqttPrefs, context)

            // Voice & AI
            VoiceAiSettingsWiring.registerVoicePreferences(valueProvider, voicePrefs, context)
            VoiceAiSettingsWiring.registerAiPreferences(valueProvider, aiPrefs, voicePrefs)

            // Camera, Power, Video Feeds
            CameraSettingsWiring.registerCameraPreferences(valueProvider, cameraPrefs, context)
            CameraSettingsWiring.registerPowerPreferences(valueProvider, powerPrefs, context)
            CameraSettingsWiring.registerVideoFeedPreferences(valueProvider, videoFeedPrefs, prefs.alert)

            // Connection, Music, Account, Subscription, Locations, Chores, Calendar, Family
            ConnectionSettingsWiring.registerConnectionPreferences(valueProvider, prefs.connection, perfPrefs)
            ConnectionSettingsWiring.registerMusicPreferences(valueProvider, prefs.connection)
            ConnectionSettingsWiring.registerAccountPreferences(valueProvider, accountPrefs, context)
            ConnectionSettingsWiring.registerSubscriptionDisplayKeys(valueProvider, context)
            ConnectionSettingsWiring.registerLocationsPreferences(valueProvider, locationsPrefs)
            ConnectionSettingsWiring.registerChoresRewardsPreferences(valueProvider, choresPrefs)
            ConnectionSettingsWiring.registerCalendarPreferences(valueProvider, context)
            ConnectionSettingsWiring.registerFamilyDisplayKeys(valueProvider, generalPrefs)
        }
        SettingsProfiler.measure("Wiring.registerCallbacks") {
            SettingsCallbackWiring.registerCallbacks(callbackRegistry, context, prefs, voicePrefs, cameraPrefs, powerPrefs, videoFeedPrefs)
        }
        SettingsProfiler.measure("Wiring.registerInterceptors") {
            SettingsCallbackWiring.registerInterceptors(callbackRegistry, context, prefs)
        }

        return SchemaContext(valueProvider, callbackRegistry)
    }

    // ── Forwarding methods for external callers ─────────────────────────
    // These delegate to the sub-modules so existing call sites don't need to change.

    /**
     * Re-register per-feed mode display keys after a feed is added/edited/deleted.
     */
    fun refreshVideoFeedModeKeys(context: Context, vp: HaliteSettingsValueProvider) {
        val vf = VideoFeedPreferences(context)
        CameraSettingsWiring.registerVideoFeedPreferences(vp, vf, AlertPreferences(context))
    }

    // Dialog forwarding
    fun launchImmichLogin(activity: com.dashieapp.Dashie.halite.settings.SettingsActivity, prefs: HalitePreferences, onComplete: () -> Unit) =
        SettingsDialogWiring.launchImmichLogin(activity, prefs, onComplete)

    fun showImmichAlbumPicker(activity: com.dashieapp.Dashie.halite.settings.SettingsActivity, prefs: HalitePreferences, onComplete: () -> Unit) =
        SettingsDialogWiring.showImmichAlbumPicker(activity, prefs, onComplete)

    fun signOutImmich(activity: com.dashieapp.Dashie.halite.settings.SettingsActivity, prefs: HalitePreferences, onComplete: () -> Unit) =
        SettingsDialogWiring.signOutImmich(activity, prefs, onComplete)

    fun pingImmichToken(activity: com.dashieapp.Dashie.halite.settings.SettingsActivity, prefs: HalitePreferences) =
        SettingsDialogWiring.pingImmichToken(activity, prefs)

    fun checkPendingMusicEnable(prefs: HalitePreferences, activity: com.dashieapp.Dashie.halite.settings.SettingsActivity? = null) =
        SettingsCallbackWiring.checkPendingMusicEnable(prefs, activity)

    fun checkPendingImmichLogin(prefs: HalitePreferences, activity: com.dashieapp.Dashie.halite.settings.SettingsActivity? = null) =
        SettingsDialogWiring.checkPendingImmichLogin(prefs, activity)

    fun saveCentralMaToken(prefs: HalitePreferences) =
        SettingsDialogWiring.saveCentralMaToken(prefs)

    fun fetchCentralMaToken(prefs: HalitePreferences) =
        SettingsDialogWiring.fetchCentralMaToken(prefs)

    // FamilyEditState forwarding (referenced by FamilyPageSchema and SettingsActivity)
    val FamilyEditState get() = ConnectionSettingsWiring.FamilyEditState
}

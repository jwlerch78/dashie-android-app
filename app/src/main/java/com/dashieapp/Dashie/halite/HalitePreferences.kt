package com.dashieapp.Dashie.halite

import android.content.Context
import android.content.SharedPreferences
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.preferences.AccountPreferences
import com.dashieapp.Dashie.halite.preferences.AlertPreferences
import com.dashieapp.Dashie.halite.preferences.CameraPreferences
import com.dashieapp.Dashie.halite.preferences.ChoresRewardsPreferences
import com.dashieapp.Dashie.halite.preferences.ConnectionPreferences
import com.dashieapp.Dashie.halite.preferences.DisplayPreferences
import com.dashieapp.Dashie.halite.preferences.GeneralPreferences
import com.dashieapp.Dashie.halite.preferences.LocationsPreferences
import com.dashieapp.Dashie.halite.preferences.LockPreferences
import com.dashieapp.Dashie.halite.preferences.MqttPreferences
import com.dashieapp.Dashie.halite.preferences.PerformancePreferences
import com.dashieapp.Dashie.halite.preferences.SleepPreferences
import com.dashieapp.Dashie.halite.preferences.VideoFeedPreferences
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences

/**
 * Preferences manager for Dashie Lite (halite flavor)
 * Stores user's Home Assistant URL and other kiosk settings
 *
 * Note: Screensaver preferences have been extracted to [ScreensaverPreferences].
 * Note: Performance preferences have been extracted to [PerformancePreferences].
 * This class delegates to these for backward compatibility.
 */
class HalitePreferences(context: Context) {

    /**
     * Screensaver-specific preferences (extracted for modularity)
     * Access directly for new code: halitePreferences.screensaver.photoSourceType
     */
    val screensaver: ScreensaverPreferences = ScreensaverPreferences(context)

    /**
     * Performance-specific preferences (extracted for modularity)
     * Access directly for new code: halitePreferences.performance.memoryRecoveryEnabled
     */
    val performance: PerformancePreferences = PerformancePreferences(context)

    /**
     * Voice-specific preferences (extracted for modularity)
     * Access directly for new code: halitePreferences.voice.voiceEnabled
     */
    val voice: VoicePreferences = VoicePreferences(context)

    /**
     * Display-specific preferences (extracted for modularity)
     * Access directly for new code: halitePreferences.display.dashboardZoom
     */
    val display: DisplayPreferences = DisplayPreferences(context)

    /**
     * Connection-specific preferences (extracted for modularity)
     * Access directly for new code: halitePreferences.connection.haUrl
     */
    val connection: ConnectionPreferences = ConnectionPreferences(context)

    /**
     * Camera/RTSP-specific preferences (extracted for modularity)
     * Access directly for new code: halitePreferences.camera.rtspEnabled
     */
    val camera: CameraPreferences = CameraPreferences(context)

    /**
     * Video Feed PiP overlay preferences (extracted for modularity)
     * Access directly for new code: halitePreferences.videoFeed.enabled
     */
    val videoFeed: VideoFeedPreferences = VideoFeedPreferences(context)

    /**
     * Lock/kiosk mode preferences (extracted for modularity)
     * Access directly for new code: halitePreferences.lock.lockToApp
     */
    val lock: LockPreferences = LockPreferences(context)

    /**
     * Sleep/wake timer preferences (extracted for modularity)
     * Access directly for new code: halitePreferences.sleep.sleepEnabled
     */
    val sleep: SleepPreferences = SleepPreferences(context)

    /**
     * General user preferences (extracted for modularity)
     * Access directly for new code: halitePreferences.general.zipCode
     */
    val general: GeneralPreferences = GeneralPreferences(context)

    /**
     * Account preferences (Dashie account linking)
     * Access directly for new code: halitePreferences.account.isLinked
     */
    val account: AccountPreferences = AccountPreferences(context)

    /**
     * Does a HOUSEHOLD-scoped answer govern this device — i.e. does this session BORROW?
     *
     * One definition, several consumers: the capability lease (whether the loop runs at all) and
     * the brain route (whether the add-on's `brain_route` is obeyed). Both ask the identical
     * borrows-vs-owns question, so they must not answer it separately — a second copy of this
     * predicate is precisely how the two would drift into disagreeing about the same device.
     *
     * See `LeaseGovernance` for the rule, the truth table, and the bug that produced it (a
     * signed-in device denied voice/AI and told to enable household sharing it does not use).
     */
    val householdAnswersGovern: Boolean
        get() = com.dashieapp.Dashie.halite.voice.lease.LeaseGovernance.governs(
            isLinked = account.isLinked,
            kioskProvisionedSession = account.kioskProvisionedSession)

    /**
     * Alert sound preferences (chime/alarm volume)
     * Access directly for new code: halitePreferences.alert.alertVolume
     */
    val alert: AlertPreferences = AlertPreferences(context)

    /**
     * Location tracking and travel time preferences
     * Access directly for new code: halitePreferences.locations.trackingEnabled
     */
    val locations: LocationsPreferences = LocationsPreferences(context)

    /**
     * Chores & Rewards feature preferences
     * Access directly for new code: halitePreferences.choresRewards.choresEnabled
     */
    val choresRewards: ChoresRewardsPreferences = ChoresRewardsPreferences(context)

    /**
     * MQTT broker and publishing preferences
     * Access directly for new code: halitePreferences.mqtt.enabled
     */
    val mqtt: MqttPreferences = MqttPreferences(context)

    companion object {
        private const val PREFS_NAME = "dashie_lite_prefs"

        // Public constants referenced by external consumers
        // (Unlock)
        const val UNLOCK_MECHANISM_ANYONE = "anyone"
        const val UNLOCK_MECHANISM_PIN = "pin"
        const val UNLOCK_INTERFACE_VISIBLE = "visible"

        // (Brightness curves)
        const val BRIGHTNESS_CURVE_LINEAR = "linear"
        const val BRIGHTNESS_CURVE_AGGRESSIVE = "aggressive"
        const val BRIGHTNESS_CURVE_GENTLE = "gentle"

        // (Dashboard zoom)
        const val DEFAULT_DASHBOARD_ZOOM = 100
        const val MIN_DASHBOARD_ZOOM = 10
        const val MAX_DASHBOARD_ZOOM = 300
        const val DEFAULT_RETURN_HOME_TIMEOUT = 0

        // (Response handling)
        const val RESPONSE_HANDLING_READ_AND_DISPLAY = "read_and_display"
        const val RESPONSE_HANDLING_READ_ONLY = "read_only"
        const val RESPONSE_HANDLING_DISPLAY_ONLY = "display_only"
        const val RESPONSE_HANDLING_NONE = "none"

        // (Voice pipeline modes)
        const val VOICE_PIPELINE_MODE_HA = "ha"
        const val VOICE_PIPELINE_MODE_AI = "ai"

        // (RTSP resolution presets)
        const val RTSP_RESOLUTION_480P = "480p"
        const val RTSP_RESOLUTION_720P = "720p"
        const val RTSP_RESOLUTION_1080P = "1080p"
        const val RTSP_RESOLUTION_CUSTOM = "custom"

        // (Emergency threshold)
        const val THRESHOLD_MODE_CUSTOM = "custom"

        // (Addon modes)
        const val ADDON_MODE_NONE = "none"
        const val ADDON_MODE_HOMEASSISTANT = "homeassistant"
        const val ADDON_MODE_DEV = "dev"

        // (AI/Voice)
        const val AI_MODEL_OLLAMA = "ollama"
        const val WEB_SEARCH_DISABLED = "disabled"
        const val WEB_SEARCH_SEARXNG = "searxng"

        // (Performance overlay positions)
        const val OVERLAY_POSITION_TOP_LEFT = "top-left"
        const val OVERLAY_POSITION_TOP_RIGHT = "top-right"
        const val OVERLAY_POSITION_BOTTOM_LEFT = "bottom-left"
        const val OVERLAY_POSITION_BOTTOM_RIGHT = "bottom-right"

        // (Default HA URL)
        const val DEFAULT_HA_URL = "http://192.168.1.x:8123"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ========== Sleep/Wake Timer Settings ==========
    // Delegated to SleepPreferences for modularity

    /**
     * Get a human-readable description of the current sleep configuration.
     * @deprecated Access via sleep.getSleepDescription(use24HourClock) for new code
     */
    fun getSleepDescription(): String {
        return sleep.getSleepDescription(display.use24HourClock)
    }

    // ========== STT Provider Settings ==========
    // Delegated to VoicePreferences for modularity

    // ========== Lock Modes (Unified Lock System) ==========
    // Delegated to LockPreferences for modularity

    // ========== HA Login Credentials (Fire Tablet) ==========
    // Delegated to ConnectionPreferences for modularity

    // ========== Voice License & Trial ==========
    // Delegated to VoicePreferences for modularity

    // ========== Sample Collection Preferences ==========
    // Delegated to VoicePreferences for modularity

    // ========== Dashboard Telemetry Preferences ==========

    // ========== Interaction Priority Mode (Experimental) ==========

    // ========== Performance/Connection Stability Settings ==========
    // Delegated to PerformancePreferences for modularity

    // ========== Stealth WebView Refresh ==========
    // Delegated to PerformancePreferences for modularity

    // ========== Emergency Recovery Settings ==========
    // Delegated to PerformancePreferences for modularity

    // ========== Dual Threshold System (RAM % OR PSS) ==========
    // Delegated to PerformancePreferences for modularity

    // ========== Enhanced Logging ==========
    // Delegated to PerformancePreferences for modularity

    // ========== OOM Kill Detection ==========
    // Delegated to PerformancePreferences for modularity

    // ========== Diagnostics Mode Settings ==========
    // Delegated to PerformancePreferences for modularity

    // ========== URL Builder Preferences ==========
    // Delegated to ConnectionPreferences for modularity

    /**
     * Reset all preferences to defaults
     */
    fun reset() {
        prefs.edit().clear().commit()
    }

    // ========== Music Player ==========
    // Delegated to ConnectionPreferences for modularity

    // ========== RTSP Camera Streaming ==========
    // Delegated to CameraPreferences for modularity

    // ========== HA Access Token (for background services) ==========
    // Delegated to ConnectionPreferences for modularity

    // ========== Timer Overlay Positions ==========

    // ========== Preference Change Listeners ==========

    /**
     * Register a preference change listener.
     * Use this to react to preference changes in real-time.
     */
    fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /**
     * Unregister a preference change listener.
     * Should be called in onDestroy() to avoid memory leaks.
     */
    fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}

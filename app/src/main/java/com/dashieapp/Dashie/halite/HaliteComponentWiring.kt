package com.dashieapp.Dashie.halite

import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import com.dashieapp.Dashie.halite.wiring.*

/**
 * Coordinator for all inter-component callback wiring.
 *
 * Delegates to domain-specific wiring modules in the `wiring/` package:
 * - [DialogHostWiring] — Exit, logout, restart, voice enable, motion wake
 * - [VoiceComponentWiring] — Voice commands, feed matching, timer bridge
 * - [MusicComponentWiring] — Music player, Sendspin, MA API, speaker groups
 * - [MediaComponentWiring] — Dashie API, HA connection, telemetry, alerts, video feeds
 * - [SensorComponentWiring] — HA sensors, MQTT, native widgets
 */
object HaliteComponentWiring {
    private const val TAG = "HaliteWiring"

    // ── Public API (called from outside) ────────────────────────────

    /**
     * Wire all inter-component callbacks.
     * Called after all components are created.
     */
    fun wireAll(
        registry: HaliteComponentRegistry,
        activity: ComponentActivity,
        webViewProvider: () -> WebView
    ) {
        Log.i(TAG, "🔌 Wiring component callbacks...")

        DialogHostWiring.wireDialogHostCallbacks(registry, webViewProvider)
        // Before VoiceComponentWiring: its onLifecycle rewiring routes through the mirror,
        // so the mirror's providers must exist first. start() arms the kiosk-side reconcile
        // (no-op on a dashboard device — reminder-sync.js owns the mirror there).
        com.dashieapp.Dashie.halite.schedule.ScheduleCloudMirror.wire(
            activity.applicationContext, registry.prefs, registry.scheduledActionManager)
        com.dashieapp.Dashie.halite.schedule.ScheduleCloudMirror.start()
        // Family push on timer/reminder fire (reuses the location-alert APNs path).
        com.dashieapp.Dashie.halite.notify.FamilyAlertNotifier.wire(
            activity.applicationContext, registry.prefs)
        // Native family-settings reader/writer for the per-member reminder-push toggle.
        com.dashieapp.Dashie.halite.notify.NotificationPrefsClient.wire(registry.prefs)
        VoiceComponentWiring.wireVoiceCallbacks(registry, webViewProvider)
        MediaComponentWiring.wireDashieServiceCallbacks(registry, activity)
        MediaComponentWiring.wireHaConnectionCallbacks(registry)
        MediaComponentWiring.wireTelemetryCallbacks(registry)
        MusicComponentWiring.wireMusicPlayerCallbacks(registry)
        MediaComponentWiring.wireAlertSoundCallbacks(registry)
        MediaComponentWiring.wireVideoFeedCallbacks(registry, webViewProvider)
        SensorComponentWiring.wireHaSensorCallbacks(registry)
        SensorComponentWiring.wireMqttCallbacks(registry)
        SensorComponentWiring.wireNativeWidgetCallbacks(registry)
        WidgetOverlayWiring.wireWidgetOverlayCallbacks(registry)
        PhotosBridgeWiring.wirePhotosBridgeCallbacks(registry)
        CalendarReauthWiring.wire(registry)

        Log.i(TAG, "🔌 Component wiring complete")
    }

    // ── Forwarding methods for external callers ─────────────────────

    fun onSidebarVolumeChanged(volumeScale0to10: Int) =
        MusicComponentWiring.onSidebarVolumeChanged(volumeScale0to10)

    fun stopSendspin() =
        MusicComponentWiring.stopSendspin()

    /**
     * Stop the previous generation's wiring-owned loops before a WebView
     * memory-recovery recreation re-runs wireAll() (which re-creates them).
     */
    fun teardownForRecreation() =
        MusicComponentWiring.teardownForRecreation()

    fun wireMusicPlayerCallbacks(registry: HaliteComponentRegistry) =
        MusicComponentWiring.wireMusicPlayerCallbacks(registry)

    fun showMaLoginIfExpired(registry: HaliteComponentRegistry): Boolean =
        MusicComponentWiring.showMaLoginIfExpired(registry)

    fun resolveImageForRecentlyPlayed(obj: org.json.JSONObject, maApiUrl: String): String? =
        MusicComponentWiring.resolveImageForRecentlyPlayed(obj, maApiUrl)

    fun applyHaSensorConfig(registry: HaliteComponentRegistry) =
        SensorComponentWiring.applyHaSensorConfig(registry)

    fun wireMqttCallbacks(registry: HaliteComponentRegistry) =
        SensorComponentWiring.wireMqttCallbacks(registry)
}

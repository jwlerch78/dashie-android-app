package com.dashieapp.Dashie.halite

import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.view.Window
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import com.dashieapp.Dashie.devicecontrols.DeviceControlsCoordinator
import com.dashieapp.Dashie.api.DashieServiceManager
import com.dashieapp.Dashie.halite.mdns.DashieMdnsService
import com.dashieapp.Dashie.halite.mqtt.DashieMqttService
import com.dashieapp.Dashie.halite.mqtt.MqttSensorPublisher
import com.dashieapp.Dashie.halite.voice.HaliteVoiceController
import com.dashieapp.Dashie.ui.ImmersiveModeController
import com.dashieapp.Dashie.voice.VoiceAssistantCoordinator
import com.dashieapp.Dashie.halite.registry.*
import com.dashieapp.Dashie.webview.DashieJSBridge

/**
 * Central registry for all Halite (Dashie Lite) components.
 *
 * This class owns and manages the lifecycle of all Halite-specific components,
 * providing a single point of access and reducing MainActivity's responsibilities.
 *
 * Benefits:
 * - Single source of truth for component instances
 * - Centralized lifecycle management (init, resume, pause, destroy)
 * - Components can reference each other through the registry
 * - Easier to test and reason about dependencies
 */
class HaliteComponentRegistry(
    private val activity: ComponentActivity,
    val prefs: HalitePreferences,
    internal val webViewProvider: () -> WebView,
    private val deviceControls: DeviceControlsCoordinator,
    private val immersiveMode: ImmersiveModeController,
    private val window: Window
) {
    // Get the current WebView (may be recreated after memory pressure)
    private val webView: WebView
        get() = webViewProvider()

    /** Activity reference for dialogs opened from component wiring. */
    internal val activityRef: ComponentActivity get() = activity

    /** WebView reference for dialogs opened from component wiring. */
    internal val webViewRef: WebView get() = webView

    /** Device controls reference for registry extension functions. */
    internal val deviceControlsRef: DeviceControlsCoordinator get() = deviceControls

    /**
     * Session-scoped current music player entity.
     * Set during transfers/switches, NOT persisted across restarts.
     * Falls back to the persisted default entity when empty.
     */
    var currentMusicEntityId: String = ""
        internal set

    /** Resolve the effective music entity: session override, then persisted default. */
    val effectiveMusicEntityId: String
        get() = currentMusicEntityId.takeIf { it.isNotEmpty() }
            ?: prefs.connection.musicPlayerDefaultEntityId.takeIf { it.isNotEmpty() }
            ?: prefs.connection.musicPlayerEntityId

    companion object {
        private const val TAG = "HaliteRegistry"
    }

    // ============================================================
    // Core Components (created immediately)
    // ============================================================

    var screenController: HaliteScreenController? = null
        internal set

    var dialogHost: NativeDialogHost? = null
        internal set

    var voiceController: HaliteVoiceController? = null
        internal set

    var dashieServiceManager: DashieServiceManager? = null
        internal set

    var ttsManager: TtsManager? = null
        internal set

    var lightSensorController: LightSensorBrightnessController? = null
        internal set

    var wifiLockManager: WifiLockManager? = null
        internal set

    var networkMonitor: NetworkMonitor? = null
        internal set

    var mdnsService: DashieMdnsService? = null
        internal set

    var timerOverlayManager: com.dashieapp.Dashie.halite.timer.TimerOverlayManager? = null
        internal set

    var scheduledActionManager: com.dashieapp.Dashie.halite.schedule.ScheduledActionManager? = null
        internal set

    var voiceEnrollmentController: com.dashieapp.Dashie.halite.voice.speakerid.enrollment.VoiceEnrollmentController? = null
        internal set

    var conditionAlertManager: com.dashieapp.Dashie.halite.schedule.condition.ConditionAlertManager? = null
        internal set

    var performanceOverlayManager: com.dashieapp.Dashie.halite.diagnostics.PerformanceOverlayManager? = null
        internal set

    var musicPlayerManager: com.dashieapp.Dashie.halite.music.MusicPlayerOverlayManager? = null
        internal set

    var alertSoundService: com.dashieapp.Dashie.halite.audio.AlertSoundService? = null
        internal set

    var videoFeedManager: com.dashieapp.Dashie.halite.videofeed.VideoFeedOverlayManager? = null
        internal set

    var screensaverPanelCoordinator: com.dashieapp.Dashie.halite.screensaver.ScreensaverPanelCoordinator? = null
        internal set

    var haSensorPublisher: HaSensorPublisher? = null
        internal set

    var mqttService: DashieMqttService? = null
        internal set

    var mqttSensorPublisher: MqttSensorPublisher? = null
        internal set

    // HA MQTT Discovery publisher — set by SensorComponentWiring.wireMqttCallbacks
    // so runtime setting changes (sensor toggles, discovery on/off) can refresh
    // discovery in place instead of only on the next CONNECTED transition.
    var mqttDiscoveryPublisher: com.dashieapp.Dashie.halite.mqtt.MqttDiscoveryPublisher? = null
        internal set

    var bottomInsetManager: WebViewBottomInsetManager? = null
        internal set

    /**
     * Reaches the dashboard recovery ladder (reload iframe → reload page →
     * recreate WebView) from wiring that is not the JS bridge.
     *
     * A provider rather than the instance: the coordinator is rebuilt whenever the
     * WebView is recreated, so holding the object here would go stale exactly when
     * recovery matters most (see the stale-WebView-ref rule). Set by
     * MainWebViewBootstrap and re-set by MainWebViewRecreation, alongside the
     * `jsBridge.dashboardHealthCoordinatorProvider` that already existed.
     *
     * Read at fault time (minutes into a no-HA streak), never at wiring time, so
     * there is no ordering dependency on when the registry is built.
     */
    var dashboardHealthCoordinatorProvider:
        (() -> com.dashieapp.Dashie.halite.DashboardHealthCoordinator?)? = null

    // ============================================================
    // Preference Change Listener
    // ============================================================

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "api_enabled" -> {
                handleApiEnabledChanged()
            }
        }
    }

    /**
     * Sync-by-default notifier (SETTINGS_ROBUSTNESS_PLAN Phase 2). Auto-dispatches
     * the webapp native-settings-changed event for any write to a synced pref key,
     * from any surface. Dispatch goes straight to the current WebView (survives
     * recreation via webViewProvider). Exposed for the JS bridge's begin/endSettingsApply.
     */
    val settingsSyncNotifier = com.dashieapp.Dashie.halite.preferences.SettingsSyncNotifier(
        context = activity,
        dispatch = { category ->
            try {
                webViewProvider().evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('native-settings-changed', { detail: { category: '$category' } }));",
                    null
                )
            } catch (e: Exception) {
                Log.d(TAG, "WebView not ready for auto-sync dispatch: $category")
            }
        }
    )

    // ============================================================
    // External References (set by MainActivity)
    // ============================================================

    /** Standard Dashie voice assistant (non-Halite) */
    var voiceAssistant: VoiceAssistantCoordinator? = null

    /** HA auth manager for token refresh */
    var authManager: HaliteAuthManager? = null

    /** Native photo widget controller for layout system */
    var photoWidgetController: com.dashieapp.Dashie.halite.widgets.PhotoWidgetController? = null

    /** Native weather widget controllers — one per forecast mode.
     *  Both controllers can coexist when a slot rotates between them. */
    var weatherDailyWidgetController: com.dashieapp.Dashie.halite.widgets.WeatherWidgetController? = null
    var weatherHourlyWidgetController: com.dashieapp.Dashie.halite.widgets.WeatherWidgetController? = null

    /** Rotator pill controllers, keyed by slot id. One per rotating slot;
     *  created lazily when JS calls setRotatorBarBounds. Each owns the
     *  always-visible media-controls pill (bottom-right) and the touch-fade
     *  settings pill (bottom-left). */
    val rotatorBarControllers = mutableMapOf<String, com.dashieapp.Dashie.halite.widgets.RotatorPillsController>()

    // Hint + bar menu overlays for rotator now go through widgetHintOverlay
    // / widgetMenuOverlay below (shared across all widgets).

    /** Small badge in the bottom-right corner of a paused rotator slot. */
    val rotatorPauseIndicator: com.dashieapp.Dashie.halite.widgets.RotatorPauseIndicator by lazy {
        com.dashieapp.Dashie.halite.widgets.RotatorPauseIndicator(
            activity = activity,
            rootContainer = activity.findViewById(com.dashieapp.Dashie.R.id.widgetContainer)
        )
    }

    // Rotator settings now goes through widgetSettingsOverlay below
    // (shared with photos and any future widget that has a settings page).

    /** Calendar/agenda re-auth overlay manager — renders an amber pill
     *  ("Sign in required") in the bottom-left of any calendar or agenda
     *  widget when one or more connected calendar accounts has had its
     *  refresh token revoked. Click → AlertDialog with Fix / Dismiss. */
    val calendarReauthOverlayManager: com.dashieapp.Dashie.halite.widgets.CalendarReauthOverlayManager by lazy {
        com.dashieapp.Dashie.halite.widgets.CalendarReauthOverlayManager(
            activity = activity,
            rootContainer = activity.findViewById(com.dashieapp.Dashie.R.id.widgetContainer),
            visibilityGate = nativeWidgetVisibilityGate,
            webViewProvider = { webViewProvider() },
            jsBridgeProvider = { jsBridge }
        )
    }

    // ============================================================
    // Generic widget overlay components (go-forward pattern — shared
    // across all widgets that have a TV menu). Only one menu/settings/hint
    // is open at a time, so a single instance of each handles every
    // widget. Each show() call carries the widgetId + jsCoordinator name
    // for round-tripping touch events back to the right JS coordinator.
    // ============================================================

    val widgetHintOverlay: com.dashieapp.Dashie.halite.widgets.NativeWidgetHintOverlay by lazy {
        com.dashieapp.Dashie.halite.widgets.NativeWidgetHintOverlay(
            activity = activity,
            rootContainer = activity.findViewById(com.dashieapp.Dashie.R.id.widgetContainer),
            visibilityGate = nativeWidgetVisibilityGate
        )
    }

    val widgetMenuOverlay: com.dashieapp.Dashie.halite.widgets.WidgetMenuOverlay by lazy {
        com.dashieapp.Dashie.halite.widgets.WidgetMenuOverlay(
            activity = activity,
            rootContainer = activity.findViewById(com.dashieapp.Dashie.R.id.widgetContainer),
            webViewProvider = { activity.findViewById<android.webkit.WebView>(com.dashieapp.Dashie.R.id.dashboardWebView) },
            visibilityGate = nativeWidgetVisibilityGate
        )
    }

    val widgetSettingsOverlay: com.dashieapp.Dashie.halite.widgets.WidgetSettingsOverlay by lazy {
        com.dashieapp.Dashie.halite.widgets.WidgetSettingsOverlay(
            activity = activity,
            rootContainer = activity.findViewById(com.dashieapp.Dashie.R.id.widgetContainer),
            webViewProvider = { activity.findViewById<android.webkit.WebView>(com.dashieapp.Dashie.R.id.dashboardWebView) },
            visibilityGate = nativeWidgetVisibilityGate
        )
    }

    /** Native focus ring manager — paints JS-controlled focus ring above
     *  the WebView so it isn't occluded by Kotlin overlays. */
    val focusRingManager: com.dashieapp.Dashie.halite.widgets.FocusRingManager by lazy {
        com.dashieapp.Dashie.halite.widgets.FocusRingManager(activity, nativeWidgetVisibilityGate)
    }

    /** Tracks which native widget (if any) is currently d-pad-activated.
     *  When non-null, MainInputHandler routes d-pad keys through the manager
     *  instead of forwarding to JS. */
    val nativeWidgetFocusManager: com.dashieapp.Dashie.halite.widgets.NativeWidgetFocusManager by lazy {
        com.dashieapp.Dashie.halite.widgets.NativeWidgetFocusManager(activity) {
            activity.findViewById<android.webkit.WebView>(com.dashieapp.Dashie.R.id.dashboardWebView)
        }
    }

    /** Visibility gate — keeps native widgets hidden until JS dashboard is visible */
    val nativeWidgetVisibilityGate = com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate()

    /** Dashboard telemetry bridge */
    var telemetryBridge: DashboardTelemetryBridge? = null

    /** HA connection monitor */
    var haConnectionMonitor: HaConnectionMonitor? = null

    /** JS Bridge for webview communication */
    var jsBridge: DashieJSBridge? = null
        set(value) {
            field = value
            // Wire music player callbacks now that jsBridge is available
            if (value != null && musicPlayerManager != null) {
                Log.i(TAG, "🎵 jsBridge set, wiring music player callbacks")
                HaliteComponentWiring.wireMusicPlayerCallbacks(this)
            }
        }

    // ============================================================
    // Permission Launchers (must be set before initialize)
    // ============================================================

    var haliteVoicePermissionLauncher: ActivityResultLauncher<String>? = null

    // ============================================================
    // Callbacks for MainActivity actions
    // ============================================================

    /** Called when lock-to-app should start */
    var onStartLockToApp: (() -> Unit)? = null

    /** Called when lock-to-app should stop */
    var onStopLockToApp: (() -> Unit)? = null

    /** Called to apply keep-screen-on setting */
    var onApplyKeepScreenOn: ((Boolean) -> Unit)? = null

    /** Called when API enabled state changes */
    var onApiEnabledChanged: ((Boolean) -> Unit)? = null

    /** Called to check and request permissions for API features */
    var onApiPermissionsCheck: (() -> Unit)? = null

    /** Called to exit the app */
    var onExitApp: (() -> Unit)? = null

    /** Called to logout from Dashie account and return to kiosk mode */
    var onLogout: (() -> Unit)? = null

    /** Called to restart the app */
    var onRestartApp: (() -> Unit)? = null

    /** Called to reboot the device (requires root/system app) */
    var onRebootDevice: (() -> Unit)? = null

    /** Called when RTSP enabled changes (needs permission check) */
    var onRtspEnabledChanged: ((Boolean) -> Unit)? = null

    /** Called when RTSP motion detection changes */
    var onRtspMotionDetectionChanged: ((Boolean) -> Unit)? = null

    /** Called to restart RTSP server if needed */
    var onRestartRtspServerIfNeeded: (() -> Unit)? = null

    /** Called when music player enabled state changes */
    var onMusicPlayerEnabledChanged: ((Boolean) -> Unit)? = null

    /** Called to apply dashboard zoom */
    var onApplyDashboardZoom: ((Int) -> Unit)? = null

    /** Called when VoiceOverlayBridge is ready (for wiring to DashieJSBridge) */
    var onVoiceOverlayBridgeReady: ((com.dashieapp.Dashie.halite.voice.VoiceOverlayBridge) -> Unit)? = null

    /** Provider for camera permission status */
    var hasCameraPermission: (() -> Boolean)? = null

    /** Provider for requesting camera permission */
    var requestCameraPermission: ((onResult: (Boolean) -> Unit) -> Unit)? = null

    /** Provider for microphone permission status */
    var hasMicrophonePermission: (() -> Boolean)? = null

    // ============================================================
    // Initialization State
    // ============================================================

    private var initialized = false

    // ============================================================
    // Permission Launchers (must be set before screen controller init)
    // ============================================================

    var cameraPermissionLauncher: ActivityResultLauncher<String>? = null
    var storagePermissionLauncher: ActivityResultLauncher<String>? = null
    var folderPickerLauncher: ActivityResultLauncher<Uri?>? = null

    /**
     * Initialize the screen controller early (before deferred components).
     * Called after HA login to set up screensaver, motion wake, etc.
     *
     * IMPORTANT: Permission launchers must be set before calling this method.
     */
    fun initializeScreenController() {
        if (screenController != null) return

        val cameraLauncher = cameraPermissionLauncher
        val storageLauncher = storagePermissionLauncher
        val folderLauncher = folderPickerLauncher

        if (cameraLauncher == null || storageLauncher == null || folderLauncher == null) {
            Log.e(TAG, "❌ Cannot initialize screen controller - permission launchers not set")
            return
        }

        Log.i(TAG, "🔧 Initializing screen controller")
        screenController = HaliteScreenController(
            activity = activity,
            halitePrefs = prefs,
            lifecycleOwner = activity,
            cameraPermissionLauncher = cameraLauncher,
            storagePermissionLauncher = storageLauncher,
            folderPickerLauncher = folderLauncher
        )
    }

    /**
     * Initialize all deferred Halite components.
     * Called after HA login completes and screen controller is ready.
     */
    fun initializeDeferredComponents() {
        if (initialized) {
            Log.d(TAG, "⚡ Components already initialized, skipping")
            return
        }
        initialized = true

        val startTime = System.currentTimeMillis()
        Log.i(TAG, "⚡ Starting deferred component initialization...")

        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry:dialog-host:start")
        initializeDialogHost()
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry:fully-kiosk:start")
        initializeDashieApi()
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry:mdns:start")
        initializeMdnsService()
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry:voice-controller:start")
        initializeVoiceController()
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry:voice-controller:end")
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry:light-sensor:start")
        initializeLightSensor()
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry:alert-sound:start")
        initializeAlertSoundService()
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry:timer-overlay:start")
        initializeTimerOverlay()
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry:scheduled-actions:start")
        initializeScheduledActions()
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry:perf-overlay:start")
        initializePerformanceOverlay()
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry:music-player:start")
        initializeMusicPlayer()
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry:video-feed:start")
        initializeVideoFeed()
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry:photo-widget:start")
        initializePhotoWidget()
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry:weather-widget:start")
        initializeWeatherWidget()
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry:screensaver-panel:start")
        initializeScreensaverPanelCoordinator()
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry:ha-sensor-pub:start")
        initializeHaSensorPublisher()
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry:mqtt:start")
        initializeMqttService()
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry:components-done")

        // Wire up all inter-component callbacks
        HaliteComponentWiring.wireAll(this, activity, webViewProvider)

        // Register preference change listener for dynamic mDNS start/stop
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        Log.d(TAG, "📡 Preference change listener registered")

        // Sync-by-default: auto-dispatch native-settings-changed for synced pref writes
        settingsSyncNotifier.start()

        Log.i(TAG, "⚡ Total deferred initialization completed in ${System.currentTimeMillis() - startTime}ms")
    }

    // Init methods moved to registry/CoreComponentInit.kt:
    // initializeDialogHost, initializeDashieApi, initializeMdnsService,
    // handleApiEnabledChanged, initializeVoiceController, initializeLightSensor,
    // initializeAlertSoundService, initializeTimerOverlay, initializePerformanceOverlay,
    // initializeScreensaverPanelCoordinator, initializeHaSensorPublisher,
    // initializeWifiLock, initializeNetworkMonitor

    // Init methods moved to registry/MediaComponentInit.kt:
    // initializeMusicPlayer, initializeVideoFeed, initializePhotoWidget, initializeWeatherWidget

    private fun initializeMqttService() {
        if (!prefs.mqtt.enabled) {
            Log.d(TAG, "MQTT disabled, skipping init")
            return
        }
        startMqttService()
    }

    /**
     * Start the MQTT service and sensor publisher.
     * Called at init time and also from MainBroadcastManager when user enables MQTT at runtime.
     */
    fun startMqttService() {
        // Clean up existing service if any
        mqttSensorPublisher?.destroy()
        mqttService?.destroy()

        val service = DashieMqttService(activity, prefs.mqtt)
        mqttService = service

        // Create sensor publisher (wired to state providers in HaliteComponentWiring)
        mqttSensorPublisher = MqttSensorPublisher(activity, service, prefs.mqtt)

        // Wire callbacks before connecting (so onConnected triggers start/discovery)
        HaliteComponentWiring.wireMqttCallbacks(this)

        service.connect()
        Log.i(TAG, "⚡ MQTT service started")
    }

    /**
     * Stop and clean up the MQTT service.
     * Called from MainBroadcastManager when user disables MQTT at runtime.
     */
    fun stopMqttService() {
        mqttSensorPublisher?.destroy()
        mqttSensorPublisher = null
        mqttService?.destroy()
        mqttService = null
        mqttDiscoveryPublisher = null
        Log.i(TAG, "MQTT service stopped")
    }

    /**
     * Refresh HA MQTT discovery in place: remove the retained configs (so a
     * just-disabled sensor or the whole discovery-off case clears from HA) then
     * republish the currently-enabled set. Both calls no-op when not connected /
     * publisher absent, and publishDiscovery itself early-returns when discovery
     * is disabled — so this correctly handles discovery-on, discovery-off, and
     * individual sensor toggles without a full reconnect.
     */
    fun refreshMqttDiscovery() {
        val publisher = mqttDiscoveryPublisher ?: return
        publisher.removeDiscovery()
        publisher.publishDiscovery()
        Log.i(TAG, "MQTT discovery refreshed")
    }

    // ============================================================
    // Lifecycle Methods
    // ============================================================

    fun onResume() {
        // Resume voice if enabled
        if (prefs.voice.voiceEnabled) {
            voiceController?.checkPermissionAndInit()
        }

        // Rebind the voice indicator's views to the live content view. checkPermissionAndInit
        // early-returns when voice is already initialized and never re-runs setupVoiceIndicator,
        // so after an Activity recreate (sleep/wake) the retained controller can be left pointing
        // at a stale view tree and render nothing. attach() is idempotent (no-op when already
        // bound to this exact tree), so this is cheap on a normal resume.
        voiceController?.reattachVoiceIndicator(
            activityRef.findViewById(android.R.id.content),
            nativeWidgetVisibilityGate
        )

        // Legacy sidebar refresh removed — settings UI is in JS now
    }

    fun onPause() {
        // Components handle their own pause via lifecycle observers
    }

    /**
     * Dismiss all visible Kotlin-layer overlays (music player, video feeds, perf overlay).
     * Used when navigating to auth/login where overlays would block d-pad input.
     * Timers are intentionally NOT dismissed — they represent persistent running state.
     */
    fun dismissAllOverlays() {
        Log.i(TAG, "🧹 Dismissing all overlays")
        musicPlayerManager?.userHidePlayer()
        videoFeedManager?.dismissAll()
        performanceOverlayManager?.hide()
    }

    fun onDestroy() {
        Log.i(TAG, "🧹 Destroying Halite components")

        // Unregister preference change listener
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        Log.d(TAG, "📡 Preference change listener unregistered")
        settingsSyncNotifier.stop()

        voiceController?.shutdown()
        screenController?.destroy()
        dashieServiceManager?.stopRtspServer()
        mdnsService?.stop()
        lightSensorController?.stop()
        wifiLockManager?.release()
        networkMonitor?.stopMonitoring()
        ttsManager?.shutdown()
        timerOverlayManager?.destroy()
        musicPlayerManager?.destroy()
        // Stop Sendspin audio service when app closes
        com.dashieapp.Dashie.halite.music.sendspin.SendspinPlayerService.stop(activity)
        videoFeedManager?.destroy()
        alertSoundService?.destroy()
        haSensorPublisher?.destroy()
        mqttSensorPublisher?.destroy()
        mqttService?.destroy()
    }

    // Utility Methods — moved to registry/RtspDebugUtils.kt
    // (checkVoiceOnResume, startRtspIfEnabled, getRtspStreamUrl, isRtspServerRunning,
    //  getRtspClientCount, isRtspV2Running, wireRtspFaceFrameCallback, clearRtspFaceFrameCallback,
    //  startRtspForDebugging, stopRtspForDebugging, getCameraPreviewFrame, getCurrentMotionScore,
    //  enableMotionGraphMode, disableMotionGraphMode, setFaceTestCallback, updateFaceDistance,
    //  getFeatureState)

    // Music Player Methods — moved to registry/MediaComponentInit.kt
    // (showMockMusicPlayer, hideMusicPlayer, isMusicPlayerVisible,
    //  loadFamilyMembersForMusicProfile, showMusicPlayer)
}

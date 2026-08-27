package com.dashieapp.Dashie.halite.registry

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.FrameLayout
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.api.DashieServiceManager
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.HaliteComponentWiring
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaliteScreenController
import com.dashieapp.Dashie.halite.HaSensorPublisher
import com.dashieapp.Dashie.halite.LightSensorBrightnessController
import com.dashieapp.Dashie.halite.NativeDialogHost
import com.dashieapp.Dashie.halite.NetworkMonitor
import com.dashieapp.Dashie.halite.TtsManager
import com.dashieapp.Dashie.halite.WifiLockManager
import com.dashieapp.Dashie.halite.mdns.DashieMdnsService
import com.dashieapp.Dashie.halite.voice.HaliteVoiceController

private const val TAG = "HaliteRegistry"

internal fun HaliteComponentRegistry.initializeScreenController() {
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
        activity = activityRef,
        halitePrefs = prefs,
        lifecycleOwner = activityRef,
        cameraPermissionLauncher = cameraLauncher,
        storagePermissionLauncher = storageLauncher,
        folderPickerLauncher = folderLauncher
    )
}

internal fun HaliteComponentRegistry.initializeDialogHost() {
    val startTime = System.currentTimeMillis()

    dialogHost = NativeDialogHost(
        activity = activityRef,
        halitePrefs = prefs,
        webViewProvider = { webViewRef },
        screenDimmerProvider = { screenController?.screenDimmer }
    )
    dialogHost?.motionWakeDiagnosticsProvider = { screenController?.getMotionWakeDiagnostics() ?: "Motion Wake: Not initialized" }
    dialogHost?.screensaverPanelDiagnosticsProvider = { screensaverPanelCoordinator?.describePanelState() ?: "Screensaver panel: not initialized" }
    dialogHost?.wifiLockHeldProvider = { wifiLockManager?.isHeld == true }
    dialogHost?.cpuWakeLockHeldProvider = { wifiLockManager?.isCpuLockHeld == true }
    dialogHost?.onWifiLockChanged = { enabled -> wifiLockManager?.applyWifiLockPreference(enabled) }

    Log.i(TAG, "⚡ DialogHost initialized in ${System.currentTimeMillis() - startTime}ms")
}

internal fun HaliteComponentRegistry.initializeDashieApi() {
    val apiStartTime = System.currentTimeMillis()

    dashieServiceManager = DashieServiceManager(
        context = activityRef,
        webViewProvider = { webViewRef },
        deviceControls = deviceControlsRef,
        halitePrefs = prefs
    )

    // (Re)wire RTSP face detection whenever the RTSP camera becomes active — covers EVERY
    // RTSP-start path (app launch, toggle, restart, WiFi reconnect), not just the handful of
    // call sites that previously called wireRtspFaceFrameCallback(). Delay so the camera is
    // open and HW-face support is known before we pick the HW vs ML Kit path. Clear on stop.
    dashieServiceManager?.onRtspCameraActiveChanged = { active ->
        Handler(Looper.getMainLooper()).postDelayed({
            if (active && prefs.screensaver.motionWakeMode == "face") {
                Log.i(TAG, "RTSP camera active — wiring face detection (mode=face)")
                wireRtspFaceFrameCallback()
            } else if (!active) {
                clearRtspFaceFrameCallback()
            }
        }, 1500)
    }

    // Initialize TTS for API use
    ttsManager = TtsManager(activityRef) { tts ->
        dashieServiceManager?.setTts(tts)
    }
    ttsManager?.initialize()

    // Start the service if any consumer needs it (API enabled, RTSP, motion-wake camera)
    dashieServiceManager?.startServiceIfNeeded()

    Log.i(TAG, "⚡ Fully Kiosk API initialized in ${System.currentTimeMillis() - apiStartTime}ms")
}

internal fun HaliteComponentRegistry.initializeMdnsService() {
    if (prefs.connection.apiEnabled) {
        mdnsService = DashieMdnsService(activityRef, prefs)
        mdnsService?.start()
        Log.i(TAG, "⚡ mDNS service started")
    }
}

internal fun HaliteComponentRegistry.handleApiEnabledChanged() {
    Log.i(TAG, "API enabled changed to: ${prefs.connection.apiEnabled}")

    if (prefs.connection.apiEnabled) {
        dashieServiceManager?.startServiceIfNeeded()
        Log.i(TAG, "⚡ Fully Kiosk API service starting (API enabled)")

        // Wait for API service to initialize before starting mDNS
        // This ensures HA can fetch device info when it receives discovery
        if (mdnsService == null) {
            Handler(Looper.getMainLooper()).postDelayed({
                mdnsService = DashieMdnsService(activityRef, prefs)
                mdnsService?.start()
                Log.i(TAG, "⚡ mDNS service started (API enabled)")
            }, 1000)
        }
    } else {
        dashieServiceManager?.stopService()
        Log.i(TAG, "🛑 Fully Kiosk API service stopped (API disabled)")

        mdnsService?.stop()
        mdnsService = null
        Log.i(TAG, "🛑 mDNS service stopped (API disabled)")
    }
}

internal fun HaliteComponentRegistry.initializeVoiceController() {
    val voiceStartTime = System.currentTimeMillis()

    // Clear STT-model working files orphaned by an install that was KILLED — the installer's
    // cleanup is a `finally`, and a kill does not unwind (T cont.47: ~106 MB stranded in
    // app-private storage, forever, for a user who never retries).
    //
    // ⚠️ It lives HERE, in boot init, rather than beside the providers it belongs to — and that
    // is the whole point. I first put it in SttProviderFactory's sherpa-registration block, which
    // reads like the natural home and is WRONG: that block does not run at boot on an HA-mode
    // device. Verified on the Fire — fabricated the exact orphan pair, restarted, and the sweep
    // logged nothing, because provider creation is lazy. Authored-but-unreached, caught only by
    // driving it. This path runs on every boot on every device.
    //
    // Cheap where there is nothing to do: it returns immediately unless `files/stt_models` exists.
    com.dashieapp.Dashie.halite.voice.stt.SttModelInstaller.sweepOrphans(activityRef)

    voiceController = HaliteVoiceController(
        context = activityRef,
        webViewProvider = { webViewRef },
        halitePrefs = prefs,
        screenDimmerProvider = { screenController?.screenDimmer },
        ttsProvider = { ttsManager?.getTts() },
    )

    haliteVoicePermissionLauncher?.let {
        voiceController?.setPermissionLauncher(it)
    }

    voiceController?.setupVoiceIndicator(
        activityRef.findViewById(android.R.id.content),
        nativeWidgetVisibilityGate
    )

    // Forward VoiceOverlayBridge ready callback to MainActivity
    voiceController?.onVoiceOverlayBridgeReady = { bridge ->
        onVoiceOverlayBridgeReady?.invoke(bridge)
    }

    Log.i(TAG, "⚡ Voice controller initialized in ${System.currentTimeMillis() - voiceStartTime}ms")
}

internal fun HaliteComponentRegistry.initializeLightSensor() {
    lightSensorController = LightSensorBrightnessController(
        activityRef,
        deviceControlsRef.brightnessManager
    ) { brightnessPercent ->
        // Auto-brightness display was on the legacy sidebar — no-op now
    }
    lightSensorController?.start()

    // Wire to screen controller so auto-brightness pauses during dim/sleep
    screenController?.lightSensorController = lightSensorController

    if (prefs.display.autoBrightnessEnabled) {
        lightSensorController?.setEnabled(true)
        lightSensorController?.setMinMax(
            prefs.display.autoBrightnessMin ?: 10,
            prefs.display.autoBrightnessMax ?: 100
        )
        lightSensorController?.setCurve(
            prefs.display.autoBrightnessCurve ?: HalitePreferences.BRIGHTNESS_CURVE_LINEAR
        )
    }

    Log.i(TAG, "⚡ Light sensor controller initialized")
}

internal fun HaliteComponentRegistry.initializeAlertSoundService() {
    val startTime = System.currentTimeMillis()
    alertSoundService = com.dashieapp.Dashie.halite.audio.AlertSoundService(activityRef)
    alertSoundService?.alertVolume = prefs.alert.alertVolume
    Log.i(TAG, "⚡ Alert sound service initialized in ${System.currentTimeMillis() - startTime}ms")
}

internal fun HaliteComponentRegistry.initializeTimerOverlay() {
    val timerStartTime = System.currentTimeMillis()

    // Get overlay container from activity layout (no SYSTEM_ALERT_WINDOW permission needed!)
    val overlayContainer = activityRef.findViewById<FrameLayout>(R.id.overlayContainer)
    if (overlayContainer == null) {
        Log.e(TAG, "❌ Could not find overlayContainer in activity layout!")
        return
    }

    timerOverlayManager = com.dashieapp.Dashie.halite.timer.TimerOverlayManager(
        context = activityRef,
        overlayContainer = overlayContainer,
        prefs = prefs,
        jsBridgeProvider = { jsBridge },
        alertSoundService = alertSoundService!!,
        visibilityGate = nativeWidgetVisibilityGate
    )

    Log.i(TAG, "⚡ Timer overlay manager initialized in ${System.currentTimeMillis() - timerStartTime}ms")
}

internal fun HaliteComponentRegistry.initializeScheduledActions() {
    val startTime = System.currentTimeMillis()
    scheduledActionManager = com.dashieapp.Dashie.halite.schedule.ScheduledActionManager(
        context = activityRef,
        alertSoundProvider = { alertSoundService },
        ttsProvider = { ttsManager },
        // android.R.id.content (top-level) + the view's 300dp elevation so the fire-time
        // alert composits ABOVE the screensaver (screenDimOverlay), not under it.
        overlayContainerProvider = { activityRef.findViewById<android.view.ViewGroup>(android.R.id.content) }
    )
    // Re-arm any reminders persisted from a previous run.
    scheduledActionManager?.rescheduleAll()
    Log.i(TAG, "⚡ Scheduled actions initialized in ${System.currentTimeMillis() - startTime}ms")

    // Condition alerts ("tell me when the hot tub is at 102") — device-local
    // adaptive poller against HA REST states; fires the standard reminder
    // NOTIFY via ScheduledActionManager.fireNow. Re-arms persisted alerts.
    conditionAlertManager =
        com.dashieapp.Dashie.halite.schedule.condition.ConditionAlertManager(
            context = activityRef,
            credentialsProvider = {
                com.dashieapp.Dashie.halite.HaTokenExtractor.getValidCredentialsSync(prefs)
            },
            fireNotify = { action -> scheduledActionManager?.fireNow(action) }
        )
    conditionAlertManager?.init()

    // Voice-enrollment wizard ("Hey Dashie — learn my voice"). Dev-gated:
    // the controller no-ops when BuildConfig.SPEAKER_ID_ENABLED is false or
    // the model asset is absent, so unconditional construction is safe on
    // all flavors. Same overlay host as reminder alerts.
    voiceEnrollmentController =
        com.dashieapp.Dashie.halite.voice.speakerid.enrollment.VoiceEnrollmentController(
            context = activityRef,
            store = com.dashieapp.Dashie.halite.voice.speakerid.VoiceprintStore(activityRef),
            overlayContainer = {
                activityRef.findViewById<android.view.ViewGroup>(android.R.id.content)
            },
            audioBuffer = { voiceController?.getSharedAudioBuffer() }
        )
}

internal fun HaliteComponentRegistry.initializePerformanceOverlay() {
    val startTime = System.currentTimeMillis()

    // Get overlay container from activity layout (no SYSTEM_ALERT_WINDOW permission needed!)
    val overlayContainer = activityRef.findViewById<FrameLayout>(R.id.overlayContainer)
    if (overlayContainer == null) {
        Log.e(TAG, "❌ Could not find overlayContainer in activity layout!")
        return
    }

    performanceOverlayManager = com.dashieapp.Dashie.halite.diagnostics.PerformanceOverlayManager(
        context = activityRef,
        overlayContainer = overlayContainer,
        prefs = prefs
    )

    // Restore overlay if it was previously enabled
    performanceOverlayManager?.initializeFromPrefs()

    Log.i(TAG, "⚡ Performance overlay manager initialized in ${System.currentTimeMillis() - startTime}ms")
}

internal fun HaliteComponentRegistry.initializeScreensaverPanelCoordinator() {
    val panel = activityRef.findViewById<FrameLayout>(R.id.screensaverOverlayPanel)
    if (panel == null) {
        Log.e(TAG, "Could not find screensaverOverlayPanel!")
        return
    }

    screensaverPanelCoordinator = com.dashieapp.Dashie.halite.screensaver.ScreensaverPanelCoordinator(
        context = activityRef,
        panel = panel
    )

    // Wire resize callback to ScreenDimmer so photos resize (not crop) when panel shows
    screensaverPanelCoordinator?.onResizeScreensaver = { active, feedAreaWidth ->
        screenController?.screenDimmer?.setVideoFeedMode(active, feedAreaWidth)
    }
    // Narrow-portrait screensaver: forward bottom-inset so the slideshow shrinks
    // photos to fit above the music/camera card band at the bottom of the screen.
    screensaverPanelCoordinator?.onScreensaverBottomInset = { bottomPanelHeightPx ->
        screenController?.screenDimmer?.setBottomPanelInset(bottomPanelHeightPx)
    }
    // Narrow-portrait photo-alone: use fit-with-bars instead of crop.
    screensaverPanelCoordinator?.onScreensaverPhotoAlone = { alone ->
        screenController?.screenDimmer?.setScreensaverPhotoAlone(alone)
    }

    // Register participants
    videoFeedManager?.let { vfm ->
        vfm.coordinator = screensaverPanelCoordinator
        screensaverPanelCoordinator?.registerParticipant(vfm)
    }

    musicPlayerManager?.let { mpm ->
        mpm.coordinator = screensaverPanelCoordinator
        mpm.showWithScreensaver = prefs.connection.musicPlayerShowWithScreensaver
        screensaverPanelCoordinator?.registerParticipant(mpm)
    }

    timerOverlayManager?.let { tom ->
        tom.coordinator = screensaverPanelCoordinator
        screensaverPanelCoordinator?.registerParticipant(tom)
    }

    Log.i(TAG, "⚡ Screensaver panel coordinator initialized")
}

internal fun HaliteComponentRegistry.initializeHaSensorPublisher() {
    haSensorPublisher = HaSensorPublisher().also {
        val haBaseUrl = prefs.connection.haBaseUrl
        // Hardware-tied stable ID — same value the HA integration stores
        // as device_id after the 1.4.7 migration.
        val deviceId = com.dashieapp.Dashie.util.StableDeviceId.read(activityRef)
        if (haBaseUrl.isNotEmpty() && deviceId.isNotEmpty()) {
            it.configurePush(haBaseUrl, deviceId)
            it.pushCheckIn()
        }
    }
    Log.i(TAG, "HA sensor publisher initialized")
}

internal fun HaliteComponentRegistry.initializeMqttService() {
    if (!prefs.mqtt.enabled) {
        Log.d(TAG, "MQTT disabled, skipping init")
        return
    }
    startMqttService()
}

internal fun HaliteComponentRegistry.initializeWifiLock() {
    wifiLockManager = WifiLockManager(activityRef)
    // CPU keep-alive is always held; the WiFi radio lock honors Performance → WiFi Lock
    // (holding the radio out of power-save wedges some budget WiFi chipsets over hours)
    wifiLockManager?.applyWifiLockPreference(prefs.performance.wifiLockEnabled)
}

internal fun HaliteComponentRegistry.initializeNetworkMonitor() {
    networkMonitor = NetworkMonitor(activityRef)

    // Wire up callbacks to restart RTSP when network reconnects
    networkMonitor?.onNetworkAvailable = {
        Log.i(TAG, "Network reconnected - checking if RTSP needs restart")

        // Only restart RTSP if it's supposed to be running
        if (prefs.camera.rtspEnabled && dashieServiceManager?.isRtspServerRunning() == true) {
            Log.i(TAG, "📹 Restarting RTSP server after WiFi reconnection")
            com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer.info("RTSP", "Restarting after WiFi reconnect")

            // Stop and restart to rebind socket
            dashieServiceManager?.stopRtspServer()
            Handler(Looper.getMainLooper()).postDelayed({
                dashieServiceManager?.startRtspServer()
                // Re-wire face frame callback after restart
                wireRtspFaceFrameCallback()
                // Re-apply HA sensor config so motion/face callbacks are wired to the new RTSP instance
                HaliteComponentWiring.applyHaSensorConfig(this)
            }, 1500)  // Use same delay as camera release
        }
    }

    networkMonitor?.onNetworkLost = {
        Log.w(TAG, "Network lost - RTSP socket will be closed by OS")
        com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer.warn("NETWORK", "WiFi lost - RTSP socket closed")
    }

    // Start monitoring
    networkMonitor?.startMonitoring()
}

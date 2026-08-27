package com.dashieapp.Dashie.halite.wiring

import android.util.Log
import com.dashieapp.Dashie.halite.Camera2MotionVideoSource
import com.dashieapp.Dashie.halite.FaceWakeDetector
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.mqtt.DashieMqttService
import com.dashieapp.Dashie.halite.mqtt.MqttCommandHandler
import com.dashieapp.Dashie.halite.mqtt.MqttDiscoveryPublisher
import com.dashieapp.Dashie.halite.preferences.MqttPreferences
import com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences

object SensorComponentWiring {
    private const val TAG = "HaliteWiring"

    /** FaceWakeDetector instance dedicated to HA sensor publishing (separate from wake detector) */
    private var haSensorFaceDetector: FaceWakeDetector? = null

    fun wireHaSensorCallbacks(registry: HaliteComponentRegistry) {
        val publisher = registry.haSensorPublisher ?: run {
            Log.d(TAG, "HA sensor publisher not ready, skipping wiring")
            return
        }
        val jsBridge = registry.jsBridge ?: run {
            Log.d(TAG, "JS bridge not ready for HA sensor, skipping wiring")
            return
        }

        Log.d(TAG, "Wiring HA sensor callbacks")

        // When settings change, reconfigure publisher
        jsBridge.settingsDelegate.onHaSensorConfigChanged = {
            applyHaSensorConfig(registry)
        }

        // Wire detection state callbacks so DeviceInfoHandler can read publisher state
        registry.dashieServiceManager?.isMotionDetectedCallback = {
            publisher.motionDetected
        }
        registry.dashieServiceManager?.isFaceDetectedCallback = {
            publisher.faceDetected
        }
        // Toggle state — read directly from preferences so the integration /
        // MQTT consumer can render binary_sensor as unavailable when off.
        // Both flags also require the master haSensorEnabled to be on.
        registry.dashieServiceManager?.isMotionDetectionEnabledCallback = {
            registry.prefs.camera.haSensorEnabled && registry.prefs.camera.haSensorMotionEnabled
        }
        registry.dashieServiceManager?.isFaceDetectionEnabledCallback = {
            registry.prefs.camera.haSensorEnabled && registry.prefs.camera.haSensorFaceEnabled
        }

        // Apply initial config if already enabled
        applyHaSensorConfig(registry)

        Log.i(TAG, "HA sensor callbacks wired")
    }

    fun applyHaSensorConfig(registry: HaliteComponentRegistry) {
        val publisher = registry.haSensorPublisher ?: return
        val prefs = registry.prefs

        val enabled = prefs.camera.haSensorEnabled
        val motionEnabled = prefs.camera.haSensorMotionEnabled
        val faceEnabled = prefs.camera.haSensorFaceEnabled
        val rtspRunning = registry.dashieServiceManager?.isRtspV2Running() ?: false

        if (!enabled) {
            // Stop publisher and clear all callbacks
            publisher.stop()
            registry.dashieServiceManager?.onHaSensorMotionDetected = null
            registry.dashieServiceManager?.setHaSensorFaceFrameCallback(null)
            registry.dashieServiceManager?.setHaSensorHwFaceResultCallback(null)
            registry.screenController?.onExtraMotionDetected = null
            haSensorFaceDetector?.destroy()
            haSensorFaceDetector = null
            Log.i(TAG, "HA sensor publisher stopped (enabled=false)")
            return
        }

        // Start publisher with current config
        publisher.start(motionEnabled, faceEnabled)

        // Wire motion callback: use RTSP source when streaming, standalone camera otherwise
        if (motionEnabled) {
            if (rtspRunning) {
                // RTSP owns the camera; motion fires from RTSP frame analysis
                registry.dashieServiceManager?.onHaSensorMotionDetected = {
                    publisher.onMotionDetected()

                }
                registry.screenController?.onExtraMotionDetected = null
            } else {
                // RTSP not running; hook into MotionWakeManager's camera detection
                registry.dashieServiceManager?.onHaSensorMotionDetected = null
                registry.screenController?.onExtraMotionDetected = {
                    publisher.onMotionDetected()

                }
                Log.i(TAG, "HA sensor: motion wired to MotionWakeManager (RTSP not running)")
            }
        } else {
            registry.dashieServiceManager?.onHaSensorMotionDetected = null
            registry.screenController?.onExtraMotionDetected = null
        }

        // Wire face detection
        if (faceEnabled) {
            val faceDistPct = com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences
                .faceDistanceToPercent(prefs.screensaver.faceWakeDistance)

            // Prefer Camera2 hardware face detection (zero CPU cost)
            if (registry.dashieServiceManager?.isHwFaceDetectSupported() == true) {
                Log.i(TAG, "HA sensor: using HW face detection (Camera2 ISP)")
                registry.dashieServiceManager?.setHwFaceMinSizePercent(faceDistPct)
                registry.dashieServiceManager?.setHaSensorHwFaceResultCallback { result ->
                    val detected = result == Camera2MotionVideoSource.FACE_RESULT_DETECTED
                    publisher.onFaceResult(detected)

                }
            } else {
                Log.i(TAG, "HA sensor: using ML Kit face detection (no HW support)")
            }

            // Always wire ML Kit as fallback. When HW is active, Camera2MotionVideoSource
            // skips YUV→Bitmap conversion (zero cost). If HW is detected as broken at
            // runtime, the flag flips and ML Kit kicks in automatically.
            if (haSensorFaceDetector == null) {
                haSensorFaceDetector = FaceWakeDetector(
                    onFaceDetected = { /* not used — we use onFaceResult callback instead */ }
                ).apply {
                    minFaceSizePercent = faceDistPct
                    enableTestMode()  // Always analyze frames (no motion trigger required)
                }
            } else {
                haSensorFaceDetector?.minFaceSizePercent = faceDistPct
            }
            haSensorFaceDetector?.onFaceResult = { result ->
                val detected = result == FaceWakeDetector.FACE_RESULT_DETECTED
                publisher.onFaceResult(detected)
            }
            registry.dashieServiceManager?.setHaSensorFaceFrameCallback { bitmap, rotation ->
                haSensorFaceDetector?.analyzeFrame(bitmap, rotation)
            }
        } else {
            registry.dashieServiceManager?.setHaSensorFaceFrameCallback(null)
            registry.dashieServiceManager?.setHaSensorHwFaceResultCallback(null)
            haSensorFaceDetector?.destroy()
            haSensorFaceDetector = null
        }

        val motionSource = if (rtspRunning) "rtsp" else "camera-wake"
        Log.i(TAG, "HA sensor publisher configured: motion=$motionEnabled ($motionSource), face=$faceEnabled")
    }

    fun wireMqttCallbacks(registry: HaliteComponentRegistry) {
        val mqttService = registry.mqttService ?: run {
            Log.d(TAG, "MQTT service not initialized, skipping wiring")
            return
        }
        val sensorPublisher = registry.mqttSensorPublisher ?: return
        val dashieService = registry.dashieServiceManager

        // Wire state providers so sensor publisher can read current values
        val screenController = registry.screenController
        val activity = registry.activityRef
        val audioMgr = activity.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
        val haSensorPublisher = registry.haSensorPublisher

        // Read debounced motion/face state from HaSensorPublisher (authoritative source)
        sensorPublisher.motionDetectedProvider = { haSensorPublisher?.motionDetected ?: false }
        sensorPublisher.faceDetectedProvider = { haSensorPublisher?.faceDetected ?: false }
        // Toggle state — same source as the integration polling path.
        sensorPublisher.motionDetectionEnabledProvider = {
            registry.prefs.camera.haSensorEnabled && registry.prefs.camera.haSensorMotionEnabled
        }
        sensorPublisher.faceDetectionEnabledProvider = {
            registry.prefs.camera.haSensorEnabled && registry.prefs.camera.haSensorFaceEnabled
        }

        sensorPublisher.screenOnProvider = {
            !(screenController?.isScreenOff() ?: false)
        }
        sensorPublisher.brightnessProvider = {
            try {
                val lp = activity.window.attributes
                if (lp.screenBrightness >= 0) (lp.screenBrightness * 255).toInt()
                else android.provider.Settings.System.getInt(
                    activity.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS, 128
                )
            } catch (_: Exception) { 128 }
        }
        sensorPublisher.ambientLightProvider = { 0 } // Light sensor value updated via callback if available
        sensorPublisher.volumeProvider = {
            audioMgr?.let {
                val vol = it.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                val max = it.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                if (max > 0) (vol * 100 / max) else 0
            } ?: 0
        }
        sensorPublisher.isMutedProvider = {
            audioMgr?.isStreamMute(android.media.AudioManager.STREAM_MUSIC) ?: false
        }
        sensorPublisher.currentUrlProvider = {
            registry.webViewRef.url ?: ""
        }
        sensorPublisher.isInScreensaverProvider = {
            screenController?.isExternalAppActive() ?: false
        }
        sensorPublisher.appMemoryMbProvider = {
            try {
                val am = activity.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                val pids = intArrayOf(android.os.Process.myPid())
                val memInfo = am?.getProcessMemoryInfo(pids)
                (memInfo?.firstOrNull()?.totalPss ?: 0) / 1024
            } catch (_: Exception) { 0 }
        }

        // Wire command handler for incoming MQTT commands
        val commandHandler = com.dashieapp.Dashie.halite.mqtt.MqttCommandHandler().apply {
            onWakeScreen = { registry.screenController?.screenOn() }
            onSleepScreen = { registry.screenController?.screenOff() }
            onSetBrightness = { level ->
                registry.screenController?.let {
                    // Brightness is set via the screen controller's underlying window
                    registry.activityRef.runOnUiThread {
                        val lp = registry.activityRef.window.attributes
                        lp.screenBrightness = level / 255f
                        registry.activityRef.window.attributes = lp
                    }
                }
            }
            onSetVolume = { level ->
                dashieService?.setDeviceVolume(level)
            }
            onLoadUrl = { url ->
                com.dashieapp.Dashie.halite.diagnostics.PersistentLog.info(
                    "REFRESH", "WebView loadUrl: MQTT command url=${url.take(80)}"
                )
                registry.activityRef.runOnUiThread {
                    registry.webViewRef.loadUrl(url)
                }
            }
            onReload = {
                com.dashieapp.Dashie.halite.diagnostics.PersistentLog.info(
                    "REFRESH", "WebView reload: MQTT command"
                )
                registry.activityRef.runOnUiThread {
                    registry.webViewRef.reload()
                }
            }
            onRelaunch = {
                val startUrl = registry.prefs.connection.haUrl
                if (startUrl.isNotEmpty()) {
                    com.dashieapp.Dashie.halite.diagnostics.PersistentLog.info(
                        "REFRESH", "WebView loadUrl: MQTT relaunch"
                    )
                    registry.activityRef.runOnUiThread {
                        registry.webViewRef.loadUrl(startUrl)
                    }
                }
            }
            onSpeak = { text ->
                dashieService?.audioManager?.speakText(text)
            }
            onPlayAudio = { url ->
                dashieService?.audioManager?.playSound(url)
            }
            onClearCache = {
                registry.activityRef.runOnUiThread {
                    registry.webViewRef.clearCache(true)
                }
            }
            onStartCamera = { dashieService?.startRtspServer() }
            onStopCamera = { dashieService?.stopRtspServer() }
        }

        mqttService.onMessageReceived = { topic, payload ->
            val commandTopic = "${mqttService.baseTopic}/command"
            if (topic == commandTopic) {
                commandHandler.handleMessage(topic, payload)
            }
        }

        // Create discovery publisher (stored on the registry so runtime setting
        // changes — sensor toggles, discovery on/off — can refresh it in place
        // via registry.refreshMqttDiscovery() without a full reconnect).
        val discoveryPublisher = com.dashieapp.Dashie.halite.mqtt.MqttDiscoveryPublisher(
            registry.activityRef, mqttService,
            com.dashieapp.Dashie.halite.preferences.MqttPreferences(registry.activityRef)
        )
        registry.mqttDiscoveryPublisher = discoveryPublisher

        // Track whether we've shown an error toast (only show once per connect attempt)
        var errorToastShown = false

        // Start publishing and discovery once connected; show toast for feedback
        mqttService.onConnectionStateChanged = { state ->
            when (state) {
                com.dashieapp.Dashie.halite.mqtt.DashieMqttService.ConnectionState.CONNECTED -> {
                    discoveryPublisher.publishDiscovery()
                    sensorPublisher.start()
                    errorToastShown = false
                }
                com.dashieapp.Dashie.halite.mqtt.DashieMqttService.ConnectionState.ERROR -> {
                    sensorPublisher.stop()
                    if (!errorToastShown) {
                        errorToastShown = true
                        val mqttPrefs = registry.prefs.mqtt
                        val displayMsg = mqttPrefs.statusDisplayText
                        activity.runOnUiThread {
                            android.widget.Toast.makeText(
                                activity, "MQTT: $displayMsg",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                com.dashieapp.Dashie.halite.mqtt.DashieMqttService.ConnectionState.DISCONNECTED -> {
                    sensorPublisher.stop()
                }
                else -> {}
            }
        }

        // If already connected (reconnect scenario), start immediately
        if (mqttService.isConnected) {
            discoveryPublisher.publishDiscovery()
            sensorPublisher.start()
        }

        // Wire MQTT state callbacks for DeviceInfoHandler
        val mqttPrefs = registry.prefs.mqtt
        dashieService?.isMqttEnabledCallback = { mqttPrefs.enabled }
        dashieService?.getMqttBaseTopicCallback = { mqttService.baseTopic }

        Log.i(TAG, "MQTT callbacks wired")
    }

    fun wireNativeWidgetCallbacks(registry: HaliteComponentRegistry) {
        val jsBridge = registry.jsBridge ?: run {
            Log.d(TAG, "JS bridge not ready for native widgets, skipping wiring")
            return
        }

        Log.d(TAG, "Wiring native widget callbacks")

        // When JS layout canvas reports a Kotlin widget slot
        jsBridge.layoutDelegate.onKotlinWidgetBounds = { type, x, y, w, h, gridCols, gridRows ->
            when (type) {
                "photos" -> {
                    val controller = registry.photoWidgetController
                    if (controller != null) {
                        controller.setWidgetBounds(x, y, w, h, gridCols, gridRows)
                    } else {
                        Log.w(TAG, "PhotoWidgetController not initialized yet")
                    }
                }
                "weather_daily" -> {
                    val controller = registry.weatherDailyWidgetController
                    if (controller != null) {
                        controller.setWidgetBounds(x, y, w, h, gridCols, gridRows)
                    } else {
                        Log.w(TAG, "weatherDailyWidgetController not initialized yet")
                    }
                }
                "weather_hourly" -> {
                    val controller = registry.weatherHourlyWidgetController
                    if (controller != null) {
                        controller.setWidgetBounds(x, y, w, h, gridCols, gridRows)
                    } else {
                        Log.w(TAG, "weatherHourlyWidgetController not initialized yet")
                    }
                }
            }
        }

        // When JS removes a Kotlin widget slot
        jsBridge.layoutDelegate.onKotlinWidgetRemoved = { type ->
            when (type) {
                "photos" -> registry.photoWidgetController?.removeWidget()
                "weather_daily" -> registry.weatherDailyWidgetController?.removeWidget()
                "weather_hourly" -> registry.weatherHourlyWidgetController?.removeWidget()
            }
        }

        // When JS hides/shows native widgets (e.g. during edit mode or rotation)
        jsBridge.layoutDelegate.onKotlinWidgetHide = { type ->
            Log.i(TAG, "onKotlinWidgetHide($type) — daily=${registry.weatherDailyWidgetController != null} hourly=${registry.weatherHourlyWidgetController != null} photos=${registry.photoWidgetController != null}")
            when (type) {
                "photos" -> registry.photoWidgetController?.setVisible(false)
                "weather_daily" -> registry.weatherDailyWidgetController?.setVisible(false)
                "weather_hourly" -> registry.weatherHourlyWidgetController?.setVisible(false)
            }
        }
        jsBridge.layoutDelegate.onKotlinWidgetShow = { type ->
            Log.i(TAG, "onKotlinWidgetShow($type) — daily=${registry.weatherDailyWidgetController != null} hourly=${registry.weatherHourlyWidgetController != null} photos=${registry.photoWidgetController != null}")
            when (type) {
                "photos" -> registry.photoWidgetController?.setVisible(true)
                "weather_daily" -> registry.weatherDailyWidgetController?.setVisible(true)
                "weather_hourly" -> registry.weatherHourlyWidgetController?.setVisible(true)
            }
        }

        // Legacy JS-driven reset is now a no-op for visibility — hide-on-
        // reload is anchored to WebView main-frame onPageStarted in
        // MainActivity. Kept wired for compatibility with other reset
        // subscribers if any exist later.
        jsBridge.layoutDelegate.onResetNativeWidgetGateCallback = {
            registry.nativeWidgetVisibilityGate.reset()
        }

        // UI mode — JS reports "off" / "kiosk" / "full" so the gate can
        // show / hide all native surfaces in one decision.
        jsBridge.authStateDelegate.onUiModeChanged = { modeString ->
            val mode = com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate
                .parseMode(modeString)
            registry.nativeWidgetVisibilityGate.setUiMode(mode)
        }

        // Auth-flow modal suppression (OAuth QR, etc.) — orthogonal to mode.
        jsBridge.authStateDelegate.onAuthFlowStarted = {
            registry.nativeWidgetVisibilityGate.setSuppressedForFullscreen(true)
        }
        jsBridge.authStateDelegate.onAuthFlowEnded = {
            registry.nativeWidgetVisibilityGate.setSuppressedForFullscreen(false)
        }
        // JS modal backdrop → Kotlin widget dim. Ref-counted inside the
        // gate, so stacked modals don't undim too early. JS modals (e.g.
        // DashieModal) call setNativeWidgetsDimmed(true) on show and
        // setNativeWidgetsDimmed(false) on hide so photos / rotator
        // widgets above the WebView read as "behind the backdrop."
        jsBridge.authStateDelegate.onSetNativeWidgetsDimmed = { dimmed ->
            registry.nativeWidgetVisibilityGate.setDimmed(dimmed)
        }
        // dismissContextualOverlays is wired in commit 2 once individual
        // overlays implement self-dismissal hooks.

        // Rotator control bar — one controller per rotating slot, lazily
        // created on first setRotatorBarBounds.
        jsBridge.layoutDelegate.onRotatorBarBounds = { slotId, x, y, w, h ->
            // Re-fetch widgetContainer each call — earlier capture-once
            // approach risked stale reference if WebView/activity recreated.
            val widgetContainer = registry.activityRef.findViewById<android.widget.FrameLayout>(com.dashieapp.Dashie.R.id.widgetContainer)
            if (widgetContainer != null) {
                val isNew = !registry.rotatorBarControllers.containsKey(slotId)
                val ctrl = registry.rotatorBarControllers.getOrPut(slotId) {
                    Log.i(TAG, "Creating RotatorPillsController for slot=$slotId")
                    com.dashieapp.Dashie.halite.widgets.RotatorPillsController(
                        context = registry.activityRef,
                        rootContainer = widgetContainer,
                        webViewProvider = { registry.activityRef.findViewById<android.webkit.WebView>(com.dashieapp.Dashie.R.id.dashboardWebView) },
                        visibilityGate = registry.nativeWidgetVisibilityGate,
                        slotId = slotId
                    )
                }
                Log.d(TAG, "Rotator bar setBounds: slot=$slotId x=$x y=$y w=$w h=$h (new=$isNew)")
                ctrl.setBounds(x, y, w, h)
            } else {
                Log.w(TAG, "widgetContainer not found — cannot create rotator bar for $slotId")
            }
        }
        jsBridge.layoutDelegate.onRotatorBarRemoved = { slotId ->
            registry.rotatorBarControllers.remove(slotId)?.remove()
        }
        jsBridge.layoutDelegate.onRotatorBarTouchSignal = { slotId ->
            registry.rotatorBarControllers[slotId]?.signalTouch()
        }
        jsBridge.layoutDelegate.onRotatorBarPausedChanged = { slotId, paused ->
            registry.rotatorBarControllers[slotId]?.setPaused(paused)
        }
        jsBridge.layoutDelegate.onRotatorViews = { slotId, viewsJson, activeType ->
            // Parse [{type, label}, ...] into the controller's pair list.
            val views = try {
                val arr = org.json.JSONArray(viewsJson)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Pair(o.optString("type", ""), o.optString("label", ""))
                }.filter { it.first.isNotEmpty() }
            } catch (e: Exception) {
                Log.w(TAG, "onRotatorViews: bad json: ${e.message}")
                emptyList()
            }
            registry.rotatorBarControllers[slotId]?.setViews(views, activeType)
        }
        jsBridge.layoutDelegate.onRotatorActiveView = { slotId, activeType ->
            registry.rotatorBarControllers[slotId]?.setActiveView(activeType)
        }
        jsBridge.layoutDelegate.onRotatorFrozenView = { slotId, frozenType ->
            registry.rotatorBarControllers[slotId]?.setFrozenView(frozenType)
        }
        // Hint / menu / settings overlays now go through the generic widget
        // overlay bridge — see WidgetOverlayWiring.

        // Atomic Kotlin widget swap: show one + hide another in the same
        // mainHandler.post so there's no inter-frame gap where neither
        // is visible (avoids family_cards iframe flashing through during
        // weather→weather rotations).
        jsBridge.layoutDelegate.onSwapKotlinWidgets = { showType, hideType ->
            registry.activityRef.runOnUiThread {
                fun controllerFor(type: String): com.dashieapp.Dashie.halite.widgets.WeatherWidgetController? = when (type) {
                    "weather_daily" -> registry.weatherDailyWidgetController
                    "weather_hourly" -> registry.weatherHourlyWidgetController
                    else -> null
                }
                controllerFor(showType)?.setVisible(true)
                controllerFor(hideType)?.setVisible(false)
            }
        }
        jsBridge.layoutDelegate.onRotatorPauseIndicatorShow = { _, x, y, w, h ->
            registry.rotatorPauseIndicator.show(x, y, w, h)
        }
        jsBridge.layoutDelegate.onRotatorPauseIndicatorHide = { _ ->
            registry.rotatorPauseIndicator.hide()
        }

        Log.i(TAG, "Native widget callbacks wired")
    }
}

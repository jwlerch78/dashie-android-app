package com.dashieapp.Dashie.halite.wiring

import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.registry.*
import com.dashieapp.Dashie.halite.HaTokenExtractor
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer

/**
 * Wiring for the Dashie API, HA connection, telemetry, alert sound, and video feed components.
 *
 * Extracted from HaliteComponentWiring to reduce file size.
 */
object MediaComponentWiring {
    private const val TAG = "HaliteWiring"

    /**
     * Video-feed config receiver. Held here (with its host activity) so re-runs of
     * wireVideoFeedCallbacks() — every nightly WebView memory-recovery recreation —
     * don't register a duplicate.
     */
    private var configChangedReceiver: android.content.BroadcastReceiver? = null
    private var configReceiverHost: android.app.Activity? = null

    /** Quote a JS string for embedding inside evaluateJavascript as a string literal. */
    private fun quoteJs(js: String): String {
        val escaped = js.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
        return "'$escaped'"
    }

    /**
     * Wire Dashie API callbacks for screen control.
     */
    fun wireDashieServiceCallbacks(registry: HaliteComponentRegistry, activity: ComponentActivity) {
        val dashieService = registry.dashieServiceManager ?: return
        val screen = registry.screenController

        // Screen on/off callbacks
        // Uses HaliteScreenController methods which work in both native and HTML/addon modes
        dashieService.onScreenOnRequested = {
            DiagnosticBuffer.info("SCREEN", "screenOn API called")
            activity.runOnUiThread {
                if (screen != null) {
                    screen.screenOn()
                    DiagnosticBuffer.info("SCREEN", "screenOn() executed")
                } else {
                    DiagnosticBuffer.error("SCREEN", "screenOn FAILED - screenController is NULL")
                }
            }
        }
        dashieService.onScreenOffRequested = { method ->
            DiagnosticBuffer.info("SCREEN", "screenOff API called (method=$method)")
            activity.runOnUiThread {
                if (screen != null) {
                    screen.screenOff(method)
                    DiagnosticBuffer.info("SCREEN", "screenOff(method=$method) executed")
                } else {
                    DiagnosticBuffer.error("SCREEN", "screenOff FAILED - screenController is NULL")
                }
            }
        }
        dashieService.isScreenOffCallback = {
            screen?.isScreenOff() ?: false
        }

        // App restart callback
        dashieService.onRestartAppRequested = {
            registry.onRestartApp?.invoke()
        }

        // Device reboot callback
        dashieService.onRebootDeviceRequested = {
            registry.onRebootDevice?.invoke()
        }

        // Screensaver callbacks
        dashieService.onStartScreensaver = {
            DiagnosticBuffer.info("SCREEN", "startScreensaver API called")
            activity.runOnUiThread {
                val dimmer = screen?.screenDimmer
                if (dimmer != null) {
                    dimmer.dimNow()
                    DiagnosticBuffer.info("SCREEN", "dimNow() executed, isDimmed=${dimmer.isDimmed()}")
                } else {
                    DiagnosticBuffer.error("SCREEN", "startScreensaver FAILED - screenDimmer is NULL (screen=${if (screen != null) "OK" else "NULL"})")
                }
            }
        }
        dashieService.onStopScreensaver = {
            DiagnosticBuffer.info("SCREEN", "stopScreensaver API called")
            activity.runOnUiThread {
                val dimmer = screen?.screenDimmer
                if (dimmer != null) {
                    dimmer.resetTimer()
                    DiagnosticBuffer.info("SCREEN", "resetTimer() executed")
                } else {
                    DiagnosticBuffer.error("SCREEN", "stopScreensaver FAILED - screenDimmer is NULL (screen=${if (screen != null) "OK" else "NULL"})")
                }
            }
        }
        dashieService.onScreensaverModeChanged = { mode ->
            DiagnosticBuffer.info("SCREEN", "screensaverMode API called: mode=$mode")
            activity.runOnUiThread {
                val dimmer = screen?.screenDimmer
                if (dimmer != null) {
                    dimmer.setMode(mode)
                    DiagnosticBuffer.info("SCREEN", "setMode($mode) executed")
                } else {
                    DiagnosticBuffer.error("SCREEN", "setMode FAILED - screenDimmer is NULL")
                }
            }
        }
        dashieService.isInScreensaverCallback = {
            screen?.screenDimmer?.isDimmed() ?: false
        }

        // Motion trigger callback
        dashieService.onTriggerMotion = {
            activity.runOnUiThread {
                screen?.screenDimmer?.resetTimer()
                screen?.resetReturnHomeTimer()
                screen?.resetSleepInactivityTimer()
            }
        }

        // WebView refresh callback (API-triggered memory release)
        dashieService.onRefreshWebView = { resultCallback ->
            (activity as? com.dashieapp.Dashie.MainActivity)?.triggerApiWebViewRefresh(resultCallback)
                ?: resultCallback(false)
        }

        // RTSP motion callback
        dashieService.onRtspMotionDetected = {
            activity.runOnUiThread {
                handleRtspMotionDetected(registry)
            }
        }

        // Kiosk lock callback
        dashieService.onKioskLockChanged = { isLocked ->
            activity.runOnUiThread {
                registry.dialogHost?.updateKioskLockState(isLocked)
                Log.i(TAG, "Kiosk lock state changed via API: locked=$isLocked")
            }
        }

        // PIN changed callback
        dashieService.onPinChanged = {
            activity.runOnUiThread {
                Log.i(TAG, "PIN changed via API")
            }
        }

        // Light sensor callback
        dashieService.getAmbientLightCallback = {
            registry.lightSensorController?.getCurrentLuxInt() ?: 0
        }

        // Auto-brightness callback
        dashieService.setAutoBrightnessCallback = { enabled, min, max, curve ->
            registry.lightSensorController?.setEnabled(enabled)
            registry.lightSensorController?.setMinMax(min, max)
            registry.lightSensorController?.setCurve(curve)
            Log.i(TAG, "Auto-brightness changed via API: enabled=$enabled, min=$min, max=$max, curve=$curve")
        }

        // Settings changed callback
        dashieService.onSettingsChangedViaApi = {
            activity.runOnUiThread {
                Log.i(TAG, "Settings changed via API")
            }
        }

        // Timer control callbacks - forward to JS TimerService via JsBridge
        dashieService.onCreateTimer = { seconds, description ->
            val jsBridge = registry.jsBridge
            if (jsBridge != null) {
                activity.runOnUiThread {
                    jsBridge.createTimer(seconds, description)
                }
                true
            } else {
                Log.w(TAG, "Cannot create timer - jsBridge is null")
                false
            }
        }
        dashieService.onCancelTimer = { timerId ->
            val jsBridge = registry.jsBridge
            if (jsBridge != null && timerId != null) {
                activity.runOnUiThread {
                    jsBridge.cancelTimer(timerId)
                }
                true
            } else {
                Log.w(TAG, "Cannot cancel timer - jsBridge=${jsBridge != null}, timerId=$timerId")
                false
            }
        }

        // Voice command injection - forward to VoiceOverlayBridge for testing
        dashieService.onProcessVoiceCommand = { transcript, callback ->
            // 🧪 "@pipeline <text>" routes through the FULL voice pipeline (classifier +
            // AI-lane branch — single/dialog/live, exactly like a spoken turn), enabling
            // headless voice regression over adb/HTTP. Response is async (TTS etc.), so
            // the HTTP reply is just an ack — verify via logcat (VoicePipeline tag).
            // The default (no prefix) path below stays: local-lane classify with a
            // synchronous response object.
            val overlayBridge = registry.voiceController?.getVoiceOverlayBridge()
            if (transcript.startsWith("@pipeline ")) {
                val text = transcript.removePrefix("@pipeline ").trim()
                val ok = text.isNotEmpty() && registry.voiceController?.injectTranscript(text) == true
                callback(ok, if (ok) "injected (full pipeline — watch logcat VoicePipeline)" else "voice pipeline not ready")
            } else if (overlayBridge != null) {
                activity.runOnUiThread {
                    overlayBridge.processVoiceCommand(transcript, object : com.dashieapp.Dashie.halite.voice.VoiceOverlayBridge.VoiceResponseCallback {
                        override fun onResponse(response: com.dashieapp.Dashie.halite.voice.VoiceOverlayBridge.VoiceResponse) {
                            callback(response.success, response.voice ?: response.text)
                        }
                        override fun onError(error: String) {
                            callback(false, error)
                        }
                        override fun onTimeout() {
                            callback(false, "Request timed out")
                        }
                    })
                }
            } else {
                Log.w(TAG, "Cannot process voice command - VoiceOverlayBridge not available")
                callback(false, "Voice not initialized or not in AI Routing mode")
            }
        }

        // STT bench (tools/stt-bench) — BOTH hook-ups live in SttBenchWiring, which is the
        // file's own stated design: this one holds the hook-up, not the logic. Moved there
        // 2026-08-25 when adding the record endpoint pushed this file past its 800-line budget
        // — the budget was right and the split it forced is the one SttBenchWiring's KDoc
        // already asked for.
        SttBenchWiring.install(
            dashieService, activity,
            { registry.voiceController?.sttManagerForBench() },
            { registry.voiceController?.sharedBufferForBench() },
        )

        // HA-Assist turn bench (validation matrix R5/R6) — dispatch in HaAssistBenchWiring.
        // Off the main thread for the same reason as sttBench, and more so: this one paces the
        // clip at 1x AND then waits out the whole turn (connect + STT + intent + TTS).
        dashieService.onRunHaAssistBench = { file, timeoutMs, allowBusy, reply ->
            Thread {
                reply(HaAssistBenchWiring.run(
                    { registry.voiceController?.haVoiceServiceForBench() },
                    { registry.voiceController?.sharedBufferForBench() },
                    { registry.voiceController?.audioCaptureForBench() },
                    file, timeoutMs, allowBusy))
            }.start()
        }
    }

    /**
     * Handle RTSP motion detection event.
     */
    private fun handleRtspMotionDetected(registry: HaliteComponentRegistry) {
        val motionWakeMode = registry.prefs.screensaver.motionWakeMode ?: "disabled"
        if (motionWakeMode != "camera" && motionWakeMode != "face") {
            Log.v(TAG, "RTSP motion detected but ignored (mode: $motionWakeMode)")
            return
        }

        val screen = registry.screenController

        // Face mode: route through face detector instead of direct wake
        if (motionWakeMode == "face") {
            Log.d(TAG, "RTSP motion detected - routing to face detector")
            screen?.onRtspMotionForFace()
            return
        }

        // Camera mode: direct wake. Entry to WAKE PATH 2/4 (RTSP motion) — the wake decision
        // itself lives in HaliteScreenController.onExternalMotionWake() so all four paths share
        // the display-off + grace logic. See .reference/CAMERA_WAKE_PATHS.md
        // Check if we're in external app mode
        if (screen?.isExternalAppActive() == true) {
            Log.i(TAG, "RTSP motion detected during external app - bringing Dashie back")
            screen.triggerReturnFromExternalApp()
        } else {
            // Wake via the shared decision: display-off detection (incl. power-button off)
            // + sleep gate + power-button grace. Replaces the old isScreenOff-only check
            // that never woke from a system/power-button screen-off.
            screen?.onExternalMotionWake()
        }
    }

    /**
     * Wire HA connection monitor callbacks.
     */
    fun wireHaConnectionCallbacks(registry: HaliteComponentRegistry) {
        val monitor = registry.haConnectionMonitor
        val dashieService = registry.dashieServiceManager

        if (monitor != null && dashieService != null) {
            monitor.onPauseRtsp = {
                dashieService.pauseRtspServer()
            }
            monitor.onResumeRtsp = {
                dashieService.resumeRtspServer()
            }
        }

        // WebSocket keepalive during screen sleep + reconnect nudge on wake.
        // This works independently of HaConnectionMonitor — it only needs
        // screenController and webView. Uses evalInHaIframe to reach the HA
        // frontend inside the kiosk shell iframe, and falls back to direct
        // document.querySelector for full mode (no iframe).
        wireWebSocketKeepAlive(registry, monitor)
    }

    /**
     * Wire WebSocket keepalive during screen sleep and reconnect nudge on wake.
     *
     * Android throttles WebView JS timers when the display is off, preventing
     * HA's frontend from sending WebSocket pings (30s interval). The server
     * drops the connection, causing stale data on wake.
     *
     * Fix: a 20s evaluateJavascript keepalive that calls connection.ping()
     * inside the HA iframe (kiosk mode) or directly (full mode).
     */
    private fun wireWebSocketKeepAlive(
        registry: HaliteComponentRegistry,
        monitor: com.dashieapp.Dashie.halite.HaConnectionMonitor?
    ) {
        val keepAliveHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var keepAliveRunnable: Runnable? = null

        var keepAliveCount = 0
        var keepAliveDropCount = 0

        // Stop the keepalive after this many consecutive "no-ha" results. When the
        // WebView has no reachable HA context at all (no iframe / no home-assistant
        // element), every ping is a no-op drop — there's no socket to keep alive.
        // Without this guard the loop pings (and WARN-logs) every 20s for the whole
        // screen-sleep window, flooding the rotating PersistentLog and evicting more
        // useful diagnostics. 10 × 20s ≈ 3.3 min tolerates a transient mid-reload gap.
        val maxConsecutiveNoHa = 10
        var keepAliveConsecutiveNoHa = 0

        // JS that pings the WebSocket with a real HA protocol message.
        // Uses hass.connection.socket.send() to send {type:"ping"} which forces
        // actual TCP traffic — the HA server responds with {type:"pong"}.
        // This prevents the server from closing the connection due to inactivity.
        // In kiosk mode, runs inside the HA iframe via evalInHaIframe.
        val pingJs = """
            (function() {
                try {
                    var ha = document.querySelector('home-assistant');
                    if (!ha || !ha.hass || !ha.hass.connection) {
                        window.parent.postMessage({source:'dashie-ws-keepalive', status:'no-ha'}, '*');
                        return;
                    }
                    var conn = ha.hass.connection;
                    var sock = conn.socket;
                    if (!sock || sock.readyState !== 1) {
                        window.parent.postMessage({source:'dashie-ws-keepalive', status:'state:' + (sock ? sock.readyState : 'no-socket')}, '*');
                        return;
                    }
                    // Send a real HA ping message over the wire.
                    // MUST use ++commandId so the id participates in HA's
                    // monotonically-increasing sequence. Using a separate id
                    // (e.g. commandId + 10000) causes "identifier values have
                    // to increase" errors when HA frontend resumes sending with
                    // its lower commandId counter.
                    // Property is "commandId" (no underscore) per home-assistant-js-websocket.
                    var id = ++conn.commandId;
                    sock.send(JSON.stringify({id: id, type:'ping'}));
                    window.parent.postMessage({source:'dashie-ws-keepalive', status:'alive'}, '*');
                } catch(e) {
                    window.parent.postMessage({source:'dashie-ws-keepalive', status:'err:' + e.message}, '*');
                }
            })();
        """.trimIndent()

        // JS that nudges reconnect on wake. Checks staleness via _dashieWsHealth
        // (tracked by websocket-monitor.js) and falls back to location.reload()
        // for long-dead connections.
        val nudgeJs = """
            (function() {
                try {
                    var ha = document.querySelector('home-assistant');
                    if (!ha || !ha.hass || !ha.hass.connection) {
                        window.parent.postMessage({source:'dashie-ws-keepalive', status:'wake:no-ha-reload'}, '*');
                        location.reload();
                        return;
                    }
                    var conn = ha.hass.connection;
                    var state = conn.socket ? conn.socket.readyState : -1;
                    var health = window._dashieWsHealth;
                    if (state === 1) {
                        var staleMs = health ? (Date.now() - health.lastMessageTime) : 0;
                        if (staleMs > 30000) {
                            if (typeof conn.reconnect === 'function') {
                                conn.reconnect(true);
                                window.parent.postMessage({source:'dashie-ws-keepalive', status:'wake:stale-reconnect:' + Math.round(staleMs/1000) + 's'}, '*');
                            } else {
                                location.reload();
                                window.parent.postMessage({source:'dashie-ws-keepalive', status:'wake:stale-reload:' + Math.round(staleMs/1000) + 's'}, '*');
                            }
                        } else {
                            window.parent.postMessage({source:'dashie-ws-keepalive', status:'wake:connected:fresh'}, '*');
                        }
                        return;
                    }
                    if (typeof conn.reconnect === 'function') {
                        conn.reconnect(true);
                        window.parent.postMessage({source:'dashie-ws-keepalive', status:'wake:reconnecting'}, '*');
                    } else {
                        var deadMs = health && health.lastDisconnectTime ? (Date.now() - health.lastDisconnectTime) : 99999;
                        if (deadMs > 10000) {
                            window.parent.postMessage({source:'dashie-ws-keepalive', status:'wake:dead-reload:' + Math.round(deadMs/1000) + 's'}, '*');
                            location.reload();
                        } else if (conn.socket) {
                            conn.socket.close();
                            window.parent.postMessage({source:'dashie-ws-keepalive', status:'wake:force-closed'}, '*');
                        }
                    }
                } catch(e) {
                    window.parent.postMessage({source:'dashie-ws-keepalive', status:'wake:err:' + e.message}, '*');
                }
            })();
        """.trimIndent()

        // Evaluate JS in the HA context: tries evalInHaIframe (kiosk mode) first,
        // falls back to direct evaluation (full mode where HA is the top-level page).
        fun evalInHaContext(webView: WebView, js: String, callback: ((String) -> Unit)? = null) {
            webView.evaluateJavascript(
                "if(typeof evalInHaIframe==='function'){evalInHaIframe(${quoteJs(js)});'sent-iframe'}" +
                "else if(document.querySelector('home-assistant')){eval(${quoteJs(js)});'sent-direct'}" +
                "else{'no-ha'}"
            ) { result ->
                callback?.invoke(result?.trim('"') ?: "null")
            }
        }

        fun installKeepAliveListener(webView: WebView) {
            webView.evaluateJavascript("""
                if (!window._dashieWsKeepAliveListener) {
                    window._dashieWsKeepAliveListener = true;
                    window.addEventListener('message', function(e) {
                        if (e.data && e.data.source === 'dashie-ws-keepalive' && e.data.status) {
                            console.log('[Dashie] WS keepalive: ' + e.data.status);
                        }
                    });
                }
            """.trimIndent(), null)
        }

        registry.screenController?.onScreenSleepChanged = { isSleeping ->
            monitor?.setScreenSleeping(isSleeping)

            val webView = registry.webViewRef

            if (isSleeping) {
                // Cancel any in-flight runnable before re-arming. onScreenSleepChanged
                // can fire isSleeping=true again without an intervening wake (e.g. after
                // a WebView recreation re-wires this callback). Without this, each event
                // posts another runnable that shares — and races — keepAliveCount, so the
                // ping/drop counters balloon (31k+ over one night) from stacked loops.
                keepAliveRunnable?.let { keepAliveHandler.removeCallbacks(it) }
                keepAliveCount = 0
                keepAliveDropCount = 0
                keepAliveConsecutiveNoHa = 0
                installKeepAliveListener(webView)
                keepAliveRunnable = object : Runnable {
                    override fun run() {
                        keepAliveCount++
                        evalInHaContext(webView, pingJs) { status ->
                            if (status.contains("no-ha") || status == "null") {
                                keepAliveDropCount++
                                keepAliveConsecutiveNoHa++
                                // Rate-limit: log the first drop of a streak and then every
                                // 15th — not every ping — so a no-HA device can't fill the log.
                                if (keepAliveConsecutiveNoHa == 1 || keepAliveDropCount % 15 == 0) {
                                    Log.w(TAG, "🔄 WS keepalive #$keepAliveCount: $status (drop #$keepAliveDropCount)")
                                    com.dashieapp.Dashie.halite.diagnostics.PersistentLog.warn("WS_KEEPALIVE", "Ping #$keepAliveCount: $status (drop #$keepAliveDropCount)")
                                }
                                // Sustained no-HA is NOT "nothing to keep alive" — see
                                // reportShellUnreachable. Stop the ping loop (it can do
                                // nothing more) and hand the fault to the recovery ladder.
                                if (keepAliveConsecutiveNoHa >= maxConsecutiveNoHa) {
                                    keepAliveRunnable?.let { keepAliveHandler.removeCallbacks(it) }
                                    keepAliveRunnable = null
                                    Log.w(TAG, "🔄 DROP: shell unreachable after $keepAliveConsecutiveNoHa consecutive pings — escalating")
                                    com.dashieapp.Dashie.halite.diagnostics.PersistentLog.warn(
                                        "WS_KEEPALIVE",
                                        "DROP: shell unreachable after $keepAliveConsecutiveNoHa consecutive pings ($keepAliveDropCount drops) — escalating to recovery"
                                    )
                                    ShellHealthEscalation.reportShellUnreachable(registry, "keepalive-x$keepAliveConsecutiveNoHa")
                                }
                            } else {
                                keepAliveConsecutiveNoHa = 0
                                if (keepAliveCount % 15 == 0) {
                                    Log.i(TAG, "🔄 WS keepalive #$keepAliveCount: $status (drops=$keepAliveDropCount)")
                                    com.dashieapp.Dashie.halite.diagnostics.PersistentLog.info("WS_KEEPALIVE", "Ping #$keepAliveCount $status (drops=$keepAliveDropCount)")
                                }
                            }
                        }
                        keepAliveHandler.postDelayed(this, 20_000)
                    }
                }
                keepAliveHandler.postDelayed(keepAliveRunnable!!, 20_000)
                Log.i(TAG, "🔄 Screen sleep: started WebSocket keepalive (20s interval)")
                com.dashieapp.Dashie.halite.diagnostics.PersistentLog.info("WS_KEEPALIVE", "Started (screen sleep)")
            } else {
                keepAliveRunnable?.let { keepAliveHandler.removeCallbacks(it) }
                keepAliveRunnable = null
                Log.i(TAG, "🔄 Screen wake: keepalive stopped after $keepAliveCount pings ($keepAliveDropCount drops)")
                com.dashieapp.Dashie.halite.diagnostics.PersistentLog.info("WS_KEEPALIVE", "Stopped: $keepAliveCount pings, $keepAliveDropCount drops")

                // Immediate nudge — no delay, evaluateJavascript queues on WebView thread
                evalInHaContext(webView, nudgeJs) { status ->
                    Log.i(TAG, "🔄 Screen wake nudge: $status")
                    com.dashieapp.Dashie.halite.diagnostics.PersistentLog.info("WS_KEEPALIVE", "Wake nudge: $status")

                    // If first nudge couldn't reach HA, retry after 2s (hardware wake may be slow)
                    if (status.contains("no-ha") || status == "null") {
                        webView.postDelayed({
                            evalInHaContext(webView, nudgeJs) { retry ->
                                Log.i(TAG, "🔄 Screen wake nudge (retry): $retry")
                                com.dashieapp.Dashie.halite.diagnostics.PersistentLog.info("WS_KEEPALIVE", "Wake nudge retry: $retry")
                                // Second consecutive miss with the user standing in front of
                                // the device: the hardware-wake-is-slow excuse is spent, and
                                // this is the exact point Scott's report stopped dead
                                // ("Wake nudge: no-ha" / "Wake nudge retry: no-ha", then
                                // nothing). Report it so the state is correct and the next
                                // sleep transition acts on it.
                                if (retry.contains("no-ha") || retry == "null") {
                                    Log.w(TAG, "🔄 DROP: shell unreachable on wake after nudge+retry — escalating")
                                    com.dashieapp.Dashie.halite.diagnostics.PersistentLog.warn(
                                        "WS_KEEPALIVE",
                                        "DROP: shell unreachable on wake (nudge+retry both no-ha) — escalating to recovery"
                                    )
                                    ShellHealthEscalation.reportShellUnreachable(registry, "wake-nudge-retry")
                                }
                            }
                        }, 2000)
                    }
                }
            }
        }
    }

    /**
     * Wire telemetry bridge callbacks.
     */
    fun wireTelemetryCallbacks(registry: HaliteComponentRegistry) {
        registry.telemetryBridge?.telemetryDelegate?.featureStateProvider = {
            registry.getFeatureState()
        }
    }

    /**
     * Wire AlertSoundService ducking callbacks.
     * Connects: alert duck/unduck → AudioPlaybackManager, JS volume changes → service.
     */
    fun wireAlertSoundCallbacks(registry: HaliteComponentRegistry) {
        val alertSound = registry.alertSoundService ?: run {
            Log.d(TAG, "Alert sound service not ready, skipping wiring")
            return
        }
        val dashieService = registry.dashieServiceManager ?: run {
            Log.d(TAG, "Dashie service manager not ready for alert sound, skipping wiring")
            return
        }
        val jsBridge = registry.jsBridge

        // Duck/unduck ExoPlayer when alert sounds play
        alertSound.onDuckForAlert = { dashieService.duckAudio() }
        alertSound.onUnduckForAlert = { dashieService.unduckAudio() }

        // JS bridge → alert volume changes
        jsBridge?.settingsDelegate?.onAlertVolumeChanged = { volume ->
            alertSound.alertVolume = volume
            registry.prefs.alert.alertVolume = volume
        }

        Log.i(TAG, "🔔 Alert sound callbacks wired")
    }

    /**
     * Wire Video Feed PiP callbacks.
     * Connects: JS trigger events → overlay manager, config changes → trigger re-injection.
     */
    fun wireVideoFeedCallbacks(
        registry: HaliteComponentRegistry,
        webViewProvider: () -> WebView
    ) {
        val videoFeedManager = registry.videoFeedManager ?: run {
            Log.d(TAG, "Video feed manager not ready, skipping wiring")
            return
        }
        val jsBridge = registry.jsBridge ?: run {
            Log.d(TAG, "JS bridge not ready for video feed, skipping wiring")
            return
        }

        Log.d(TAG, "Wiring video feed callbacks")

        // Seed the per-device global timing values from prefs so they're correct
        // from boot — the config callbacks below only fire on a subsequent change.
        run {
            val vfPrefsInit = com.dashieapp.Dashie.halite.preferences.VideoFeedPreferences(registry.activityRef)
            videoFeedManager.autoDismissSeconds = vfPrefsInit.getAutoDismissSeconds()
            videoFeedManager.continueWhileActive = vfPrefsInit.getContinueWhileActive()
        }

        // Config changed from JS settings → update cooldown, dismiss feeds if disabled
        // Trigger entity list updates are picked up by HA integration on next deviceInfo poll
        jsBridge.settingsDelegate.onVideoFeedConfigChanged = { configJson ->
            Log.i(TAG, "Video feed config changed")
            try {
                val config = org.json.JSONObject(configJson)
                val enabled = config.optBoolean("enabled", false)
                val cooldown = config.optInt("cooldownSeconds", 0)
                val resolution = config.optInt("streamResolution", 640)
                val fps = config.optInt("streamFps", 15)
                // Display settings are stored as top-level prefs, not in JSON blob
                val vfPrefs = com.dashieapp.Dashie.halite.preferences.VideoFeedPreferences(registry.activityRef)
                val location = vfPrefs.getFeedLocation()
                val layout = vfPrefs.getFeedLayout()
                val size = vfPrefs.getFeedSize()
                videoFeedManager.cooldownSeconds = cooldown
                videoFeedManager.streamResolution = resolution
                videoFeedManager.streamFps = fps
                videoFeedManager.feedLocation = location
                videoFeedManager.feedLayout = layout
                videoFeedManager.feedSize = size
                // Per-device globals (apply to all feeds) — read from the config blob.
                videoFeedManager.autoDismissSeconds = config.optInt("autoDismissSeconds", 30)
                videoFeedManager.continueWhileActive = config.optBoolean("continueWhileActive", true)
                registry.screensaverPanelCoordinator?.feedSize = size
                Log.i(TAG, "Video feed config updated: cooldown=${cooldown}s, resolution=${resolution}, fps=${fps}, location=$location, layout=$layout, size=$size")
                // Dismiss all active feeds so they pick up new camera/settings on next trigger
                videoFeedManager.dismissAll()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process video feed config change", e)
            }
        }

        // Listen for config changes from native Kotlin settings (SettingsActivity broadcasts).
        // Registered once per host activity: WebView recreation re-runs wireVideoFeedCallbacks
        // (via wireAll) but must not stack another receiver; an Activity recreate gets a fresh
        // registration after unregistering from the old, now-destroyed host.
        if (configChangedReceiver == null || configReceiverHost !== registry.activityRef) {
            configChangedReceiver?.let { old ->
                runCatching { configReceiverHost?.unregisterReceiver(old) }
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    Log.i(TAG, "Video feed config changed (broadcast)")
                    val vfPrefs = com.dashieapp.Dashie.halite.preferences.VideoFeedPreferences(registry.activityRef)
                    videoFeedManager.feedSize = vfPrefs.getFeedSize()
                    videoFeedManager.feedLocation = vfPrefs.getFeedLocation()
                    videoFeedManager.feedLayout = vfPrefs.getFeedLayout()
                    videoFeedManager.cooldownSeconds = vfPrefs.getCooldownSeconds()
                    videoFeedManager.streamResolution = vfPrefs.getStreamResolution()
                    videoFeedManager.streamFps = vfPrefs.getStreamFps()
                    // Auto-dismiss + display-while-active are per-device globals that
                    // apply to ALL feeds (the per-rule copies were never editable).
                    videoFeedManager.autoDismissSeconds = vfPrefs.getAutoDismissSeconds()
                    videoFeedManager.continueWhileActive = vfPrefs.getContinueWhileActive()
                    registry.screensaverPanelCoordinator?.feedSize = vfPrefs.getFeedSize()
                    // Alert volume is a per-device global; push it into the live
                    // AlertSoundService so a slider change applies immediately (the
                    // service caches it as a field, set only at init otherwise).
                    registry.alertSoundService?.alertVolume = registry.prefs.alert.alertVolume
                    // Video feeds turned off in native Settings — dismiss any visible
                    // feeds now (mirrors the JS config path's dismissAll()).
                    if (!vfPrefs.enabled) videoFeedManager.dismissAll()
                    Log.i(TAG, "Video feed config refreshed from prefs: size=${vfPrefs.getFeedSize()}, location=${vfPrefs.getFeedLocation()}, autoDismiss=${vfPrefs.getAutoDismissSeconds()}, continueWhileActive=${vfPrefs.getContinueWhileActive()}, enabled=${vfPrefs.enabled}")
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registry.activityRef.registerReceiver(
                    receiver,
                    android.content.IntentFilter("com.dashieapp.Dashie.ACTION_VIDEO_FEED_CONFIG_CHANGED"),
                    android.content.Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                registry.activityRef.registerReceiver(
                    receiver,
                    android.content.IntentFilter("com.dashieapp.Dashie.ACTION_VIDEO_FEED_CONFIG_CHANGED")
                )
            }
            configChangedReceiver = receiver
            configReceiverHost = registry.activityRef
        }

        // Token refresh callback — called from stream thread on 401
        videoFeedManager.onTokenRefreshNeeded = {
            Log.i(TAG, "Video feed stream got 401 — refreshing token")
            val halitePrefs = registry.prefs
            HaTokenExtractor.refreshTokenSync(halitePrefs)
            halitePrefs.connection.haAccessToken.isNotEmpty() && !halitePrefs.connection.isHaTokenExpired
        }

        // Listen for trigger events pushed by HA integration via NanoHTTPD
        // Legacy: GET /?cmd=videoFeedTrigger&entityId=xxx&state=on
        // Centralized: includes feedId, mode, cameraEntityId, etc.
        registry.dashieServiceManager?.onVideoFeedTriggerCallback = { params ->
            val entityId = params["entityId"] ?: ""
            val newState = params["state"] ?: ""
            val feedId = params["feedId"]
            Log.i(TAG, "Video feed trigger (from HA): entity=$entityId, state=$newState, feedId=$feedId")

            // Master enabled gate — when the user has turned video feeds off
            // in Settings, drop the trigger entirely. Without this, configured
            // rules continue firing because the trigger entity subscriptions
            // remain active and showFeed() doesn't check the master toggle.
            if (registry.prefs.videoFeed.enabled.not()) {
                Log.i(TAG, "Skipping video feed trigger — master toggle is OFF")
            } else {

            // Always track current state so continueWhileActive can check it on dismiss
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                videoFeedManager.updateTriggerState(entityId, newState)
            }

            // HA integration pushes EVERY state transition (on and off) so the device
            // can update its cached trigger state for continue-while-active checks.
            // But only state=on should actually fire a feed — a state=off push is just
            // a "trigger cleared" notification, not a reason to re-show the feed.
            // Without this gate, clearing the trigger re-displays the feed (user-visible
            // bug: face_detected goes on→off and Samsung pops up on the "off" transition).
            if (newState != "on") {
                Log.d(TAG, "Skipping showFeed for $entityId state=$newState (not a rising-edge trigger)")
            } else {
                val data: com.dashieapp.Dashie.halite.videofeed.VideoFeedData? = if (feedId != null) {
                    // Centralized feed registry path — build VideoFeedData from HA params
                    val mode = params["mode"] ?: "trigger"
                    // Skip on-demand and ignored feeds — only trigger/trigger_alert should auto-show
                    if (mode == "subscribed" || mode == "ignored") {
                        Log.d(TAG, "Skipping trigger for $feedId — mode=$mode (on-demand/ignored)")
                        null
                    } else com.dashieapp.Dashie.halite.videofeed.VideoFeedData(
                        ruleId = feedId,
                        cameraEntityId = params["cameraEntityId"] ?: "",
                        cameraName = params["feedLabel"] ?: "",
                        triggerEntityId = entityId,
                        autoDismissSeconds = params["autoDismissSeconds"]?.toIntOrNull() ?: 30,
                        continueWhileActive = params["continueWhileActive"] == "true",
                        streamSourceType = params["streamSourceType"] ?: "entity",
                        streamSourceUrl = params["streamSourceUrl"] ?: "",
                        playSoundOnTrigger = mode == "trigger_alert",
                        triggerSound = params["alertSound"]?.takeIf { it.isNotBlank() } ?: "extra_wood_knock",
                        rtspUrl = params["rtspUrl"] ?: ""
                    )
                } else {
                    // Legacy path — look up rule from local preferences
                    val rule = registry.prefs.videoFeed.findRuleByTrigger(entityId, newState)
                    rule?.let {
                        val ruleMode = it.optString("subscriptionMode", "")
                        if (ruleMode == "subscribed" || ruleMode == "ignored") {
                            Log.d(TAG, "Skipping legacy trigger for $entityId — mode=$ruleMode")
                            null
                        } else {
                            com.dashieapp.Dashie.halite.videofeed.VideoFeedData.fromJson(it)
                        }
                    }
                }

                if (data != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        videoFeedManager.showFeed(data)
                    }
                } else {
                    Log.d(TAG, "No matching video feed rule for $entityId=$newState")
                }
            }
            } // close enabled-gate else
        }

        // Preview chime from settings UI
        jsBridge.settingsDelegate.onPreviewVideoFeedChime = { soundName ->
            videoFeedManager.previewChime(soundName)
        }

        Log.i(TAG, "Video feed callbacks wired")
    }
}

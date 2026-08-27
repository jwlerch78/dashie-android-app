package com.dashieapp.Dashie.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import com.dashieapp.Dashie.devicecontrols.DeviceControlsCoordinator
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.audio.SharedAudioBuffer

/**
 * Coordinator for the Dashie API Service and sub-managers.
 *
 * Handles:
 * - Service lifecycle (start/stop/restart, binding)
 * - URL management (current URL tracking, JS eval)
 * - Callback wiring between API service and app components
 * - App utilities (launcher, toast, cache/storage clear)
 *
 * Delegates domain-specific work to sub-managers:
 * - [RtspStreamingManager] - RTSP camera streaming, motion detection
 * - [AudioPlaybackManager] - ExoPlayer, TTS, audio ducking
 * - [SettingsPreferenceManager] - Display/screensaver settings, kiosk lock/PIN
 */
class DashieServiceManager(
    private val context: Context,
    private val webViewProvider: () -> WebView,
    private val deviceControls: DeviceControlsCoordinator,
    private val halitePrefs: HalitePreferences
) {
    // Get the current WebView (may be recreated after memory pressure)
    private val webView: WebView
        get() = webViewProvider()

    companion object {
        private const val TAG = "DashieServiceMgr"
    }

    private var apiService: DashieApiService? = null
    /** Whether the foreground service is currently bound. Exposed for diagnostics. */
    var isBound = false
        private set

    /** Whether the API server failed to bind its port. Returns false if service not bound. */
    val isApiBindFailed: Boolean
        get() = apiService?.serverBindFailed ?: false

    /** The port the API server is configured to use. */
    val apiServerPort: Int
        get() = apiService?.serverPort ?: DashieApiServer.DEFAULT_PORT

    private var currentUrl: String = halitePrefs.connection.haUrl

    private val handler = Handler(Looper.getMainLooper())

    // Timestamp of last user-initiated (local) volume change.
    // Used by setVolumeCallback to suppress stale remote volume pushes.
    @Volatile
    private var lastLocalVolChangeAt = 0L

    // Screensaver state callback (delegated to MainActivity for actual state)
    // This replaces the local isInScreensaverMode variable to get actual ScreenDimmer state
    var isInScreensaverCallback: (() -> Boolean)? = null

    // ==================== Camera FGS consumers ====================
    // The API service's FGS type includes `camera` if ANY consumer needs it.
    // Today: RTSP streaming and motion-wake-camera mode. Each consumer reports
    // its state independently; refreshCameraFgsType() ORs them and applies.
    // Without the union, a stop in one consumer would clear the camera bit even
    // though the other still needed it.
    private var rtspCameraActive = false
    // Initialize from the persisted pref so the first onServiceConnected →
    // refreshCameraFgsType() doesn't clobber the service's pref-restored camera FGS
    // type back to false before the live motion-wake state has propagated. The service
    // restores this same pref in onStartCommand; the client must agree or the union
    // reads a stale false on first bind and the OS revokes the camera after lockNow().
    private var motionWakeCameraActive = halitePrefs.sleep.motionWakeCameraActive

    // ==================== Sub-managers ====================
    val rtspManager = RtspStreamingManager(context, halitePrefs).apply {
        // Promote/demote the API foreground service's camera FGS type as RTSP
        // streaming starts/stops. Reads apiService lazily — a no-op if the service
        // isn't bound yet (onServiceConnected re-syncs the type in that case).
        onCameraActiveChanged = { active ->
            rtspCameraActive = active
            refreshCameraFgsType()
            onRtspCameraActiveChanged?.invoke(active)
        }
    }

    /**
     * Fired when the RTSP camera becomes active/inactive (any path: app launch, toggle,
     * restart, WiFi reconnect). The Activity wires this to (re)wire the RTSP face-detection
     * callback so face wake survives RTSP (re)starts regardless of order-of-operations —
     * the scattered per-callsite wiring missed the plain app-launch case.
     */
    var onRtspCameraActiveChanged: ((Boolean) -> Unit)? = null
    val audioManager = AudioPlaybackManager(context)
    val screenshotManager = ScreenshotCaptureManager(context, webViewProvider)
    val settingsManager = SettingsPreferenceManager(webViewProvider, halitePrefs).apply {
        // Wire RTSP toggle to rtspManager
        onRtspToggle = { enabled ->
            if (enabled) rtspManager.startRtspServer()
            else rtspManager.stopRtspServer()
        }
    }

    // Callbacks from MainActivity for service actions
    var onScreenOnRequested: (() -> Unit)? = null
    var onScreenOffRequested: ((String?) -> Unit)? = null  // method: "overlay", "hardware", or null
    var onRestartAppRequested: (() -> Unit)? = null
    var onRebootDeviceRequested: (() -> Unit)? = null

    // Screen state callback (returns true if screen is in "off" state)
    var isScreenOffCallback: (() -> Boolean)? = null

    // Screensaver callbacks (delegated to MainActivity for screen control)
    var onStartScreensaver: (() -> Unit)? = null
    var onStopScreensaver: (() -> Unit)? = null

    // Light sensor callback (gets current lux from LightSensorBrightnessController)
    var getAmbientLightCallback: (() -> Int)? = null

    // Motion callback (resets sleep timer in MainActivity)
    var onTriggerMotion: (() -> Unit)? = null

    // WebView refresh callback (triggers memory release via about:blank navigation)
    var onRefreshWebView: ((callback: (Boolean) -> Unit) -> Unit)? = null

    // Timer callbacks (forwarded to DashboardTelemetryBridge via MainActivity)
    var onCreateTimer: ((Int, String?) -> Boolean)? = null
    var onCancelTimer: ((String?) -> Boolean)? = null

    // Voice command injection callback (for testing - bypasses wake word + STT)
    var onProcessVoiceCommand: ((String, (Boolean, String?) -> Unit) -> Unit)? = null

    // STT benchmark (dev harness — tools/stt-bench): file, providerName, timeoutMs, reply
    var onRunSttBench: ((String, String, Long, (org.json.JSONObject) -> Unit) -> Unit)? = null
    // STT bench RECORD (dev harness): clip name, durationMs, reply
    var onRecordSttBench: ((String, Long, (org.json.JSONObject) -> Unit) -> Unit)? = null
    // HA-Assist turn bench (validation matrix R5/R6) — clip, timeoutMs, reply.
    var onRunHaAssistBench: ((String, Long, Boolean, (org.json.JSONObject) -> Unit) -> Unit)? = null

    // ==================== Facade methods for RtspStreamingManager ====================
    fun setSharedAudioBuffer(buffer: SharedAudioBuffer?) = rtspManager.setSharedAudioBuffer(buffer)
    fun startRtspServer(forceStart: Boolean = false): Boolean = rtspManager.startRtspServer(forceStart)
    fun stopRtspServer() = rtspManager.stopRtspServer()
    fun isRtspServerRunning(): Boolean = rtspManager.isRtspServerRunning()
    fun isRtspV2Running(): Boolean = rtspManager.isRtspV2Running()
    fun isRtspInCameraRecovery(): Boolean = rtspManager.isRtspInCameraRecovery()
    fun getRtspStreamUrl(): String = rtspManager.getRtspStreamUrl()
    fun getRtspClientCount(): Int = rtspManager.getRtspClientCount()
    fun getRtspWatchdogStatus(): String = rtspManager.getRtspWatchdogStatus()
    fun hasRtspFailed(): Boolean = rtspManager.hasRtspFailed()
    fun getRtspFailureReason(): String? = rtspManager.getRtspFailureReason()
    fun setRtspMotionDetection(enabled: Boolean) = rtspManager.setRtspMotionDetection(enabled)
    fun isRtspMotionDetectionEnabled(): Boolean = rtspManager.isRtspMotionDetectionEnabled()
    fun rearmRtspMotionDetection() = rtspManager.rearmRtspMotionDetection()
    fun setRtspMotionThreshold(threshold: Double) = rtspManager.setRtspMotionThreshold(threshold)
    fun setFaceFrameCallback(callback: ((android.graphics.Bitmap, Int) -> Unit)?) = rtspManager.setFaceFrameCallback(callback)
    fun setHaSensorFaceFrameCallback(callback: ((android.graphics.Bitmap, Int) -> Unit)?) = rtspManager.setHaSensorFaceFrameCallback(callback)
    fun pauseRtspServer() = rtspManager.pauseRtspServer()
    fun resumeRtspServer() = rtspManager.resumeRtspServer()
    fun setRtspResolution(width: Int, height: Int) = rtspManager.setRtspResolution(width, height)
    fun setRtspFps(fps: Int) = rtspManager.setRtspFps(fps)
    fun setRtspBitrate(bitrate: Int) = rtspManager.setRtspBitrate(bitrate)
    fun getRtspConfig(): RtspConfig = rtspManager.getRtspConfig()
    fun getCameraPreviewFrame(): ByteArray? = rtspManager.getCameraPreviewFrame()
    fun getCurrentRtspMotionScore(): Double = rtspManager.getCurrentRtspMotionScore()
    fun isHwFaceDetectSupported(): Boolean = rtspManager.isHwFaceDetectSupported()
    fun setHwFaceResultCallback(callback: ((Int) -> Unit)?) = rtspManager.setHwFaceResultCallback(callback)
    fun setHwFaceDetectedCallback(callback: (() -> Unit)?) = rtspManager.setHwFaceDetectedCallback(callback)
    fun setHaSensorHwFaceResultCallback(callback: ((Int) -> Unit)?) = rtspManager.setHaSensorHwFaceResultCallback(callback)
    fun setHwFaceMinSizePercent(percent: Float) = rtspManager.setHwFaceMinSizePercent(percent)

    var onRtspMotionDetected: (() -> Unit)?
        get() = rtspManager.onRtspMotionDetected
        set(value) { rtspManager.onRtspMotionDetected = value }

    var onHaSensorMotionDetected: (() -> Unit)?
        get() = rtspManager.onHaSensorMotionDetected
        set(value) { rtspManager.onHaSensorMotionDetected = value }

    // HA sensor detection state callbacks (read by DeviceInfoHandler)
    var isMotionDetectedCallback: (() -> Boolean)? = null
    var isFaceDetectedCallback: (() -> Boolean)? = null
    // Whether the user has enabled the corresponding detection toggle. Reported
    // alongside the detection state so HA's binary_sensor can render unavailable
    // when off (= "we're not scanning") rather than collapsing to "active+clear".
    var isMotionDetectionEnabledCallback: (() -> Boolean)? = null
    var isFaceDetectionEnabledCallback: (() -> Boolean)? = null

    // MQTT state callbacks (read by DeviceInfoHandler)
    var isMqttEnabledCallback: (() -> Boolean)? = null
    var getMqttBaseTopicCallback: (() -> String)? = null

    // Video feed trigger callback (pushed by HA integration via NanoHTTPD)
    var onVideoFeedTriggerCallback: ((Map<String, String>) -> Unit)? = null

    // ==================== Facade methods for AudioPlaybackManager ====================
    fun duckAudio() = audioManager.duckAudio()
    fun unduckAudio() = audioManager.unduckAudio()
    fun isMusicPlaying(): Boolean = audioManager.isMusicPlaying()
    fun isMusicPaused(): Boolean = audioManager.isMusicPaused()
    fun pauseMusic() = audioManager.pauseMusic()
    fun resumeMusic() = audioManager.resumeMusic()
    fun stopMusic() = audioManager.stopMusic()
    fun setTts(textToSpeech: TextToSpeech) = audioManager.setTts(textToSpeech)
    fun getDeviceVolume(): Int = deviceControls.getVolume() * 10  // 0-10 → 0-100
    fun setDeviceVolume(level: Int) {
        lastLocalVolChangeAt = System.currentTimeMillis()
        deviceControls.setVolume((level * 10 / 100).coerceIn(0, 10))
    }

    // ==================== Facade properties for SettingsPreferenceManager ====================
    var onScreensaverModeChanged: ((String) -> Unit)?
        get() = settingsManager.onScreensaverModeChanged
        set(value) { settingsManager.onScreensaverModeChanged = value }
    var setAutoBrightnessCallback: ((enabled: Boolean, min: Int, max: Int, curve: String) -> Unit)?
        get() = settingsManager.setAutoBrightnessCallback
        set(value) { settingsManager.setAutoBrightnessCallback = value }
    var onSettingsChangedViaApi: (() -> Unit)?
        get() = settingsManager.onSettingsChangedViaApi
        set(value) { settingsManager.onSettingsChangedViaApi = value }
    var onKioskLockChanged: ((Boolean) -> Unit)?
        get() = settingsManager.onKioskLockChanged
        set(value) { settingsManager.onKioskLockChanged = value }
    var onPinChanged: (() -> Unit)?
        get() = settingsManager.onPinChanged
        set(value) { settingsManager.onPinChanged = value }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? DashieApiService.LocalBinder
            apiService = localBinder?.getService()
            isBound = true
            Log.i(TAG, "Bound to DashieApiService")

            // Setup callbacks
            setupServiceCallbacks()

            // Re-sync the foreground service type from the union of camera
            // consumers (RTSP + motion-wake-camera). onStartCommand started the
            // service as SPECIAL_USE only; if either consumer was already active
            // before the (re)bind, the camera bit needs to be added now so the
            // camera survives backgrounding.
            if (rtspManager.isRtspServerRunning()) rtspCameraActive = true
            // motionWakeCameraActive is updated via setMotionWakeCameraActive()
            // — HaliteScreenController fires the callback during setup() and
            // refreshMotionWake(), which may happen before OR after this bind.
            // If after, that path will trigger another refresh; this call covers
            // the before case.
            refreshCameraFgsType()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            apiService = null
            isBound = false
            Log.i(TAG, "Disconnected from DashieApiService")
        }
    }

    /**
     * Start the DashieApiService if any consumer needs it.
     *
     * Consumers today: REST API (apiEnabled pref), RTSP streaming
     * (rtspCameraActive), motion-wake camera mode (motionWakeCameraActive).
     * The service is primarily a foreground-service-type holder — its
     * camera|microphone|specialUse types are how the camera/mic survive
     * Activity backgrounding (lockNow). Even if the user has API disabled,
     * RTSP and motion-wake-camera still need the FGS to function on strict
     * OEMs. The REST HTTP server inside the service is independently gated
     * on apiEnabled (see DashieApiService.onStartCommand).
     */
    fun startServiceIfNeeded() {
        val needed = halitePrefs.connection.apiEnabled || rtspCameraActive || motionWakeCameraActive
        if (!needed) {
            Log.i(TAG, "No consumer needs the API service — not starting")
            return
        }

        if (isBound) {
            Log.d(TAG, "Service already started + bound — skip")
            return
        }

        val reason = buildString {
            if (halitePrefs.connection.apiEnabled) append("api ")
            if (rtspCameraActive) append("rtsp ")
            if (motionWakeCameraActive) append("motionWake ")
        }.trim()
        Log.i(TAG, "Starting Fully Kiosk API service (needed by: $reason)")

        // NOTE: CameraCaptureManager removed from startup initialization.
        // It was holding the camera open permanently via CameraX bindToLifecycle,
        // causing camerahalserver to burn 33-60% CPU even when no camera features
        // were in use. getCamshot now only works when RTSP is actively streaming.

        val intent = Intent(context, DashieApiService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        // Bind to get callbacks
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Stop the API service
     */
    fun stopService() {
        Log.i(TAG, "Stopping Fully Kiosk API service")

        if (isBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding service: ${e.message}")
            }
            isBound = false
        }

        context.stopService(Intent(context, DashieApiService::class.java))
        apiService = null

        // Release audio resources (TTS + ExoPlayer)
        audioManager.release()

        // Clean up RTSP camera servers
        rtspManager.stopRtspServer()
    }

    /**
     * Restart the service (e.g., after password change)
     */
    fun restartService() {
        Log.i(TAG, "Restarting Fully Kiosk API service")
        stopService()
        startServiceIfNeeded()
    }

    /**
     * Called by HaliteScreenController when motion-wake's camera usage changes
     * (mode set to camera/face, or back to disabled/brightness/sensor fallback).
     *
     * Same FGS-promotion need as RTSP — without the `camera` foreground-service
     * type, OEM camera policies (Samsung, Mio kiosk ROM, newer Fire OS) revoke
     * camera access ~5s after the app backgrounds via lockNow(), killing
     * motion-wake. Wired in MainHaliteSetup.kt.
     */
    fun setMotionWakeCameraActive(active: Boolean) {
        if (motionWakeCameraActive == active) return
        motionWakeCameraActive = active
        // Persist so DashieApiService.onStartCommand() can restore the FGS
        // camera type after a START_STICKY restart (system kill / OOM / doze
        // reap), where DashieServiceManager may not be alive to re-promote.
        halitePrefs.sleep.motionWakeCameraActive = active
        Log.i(TAG, "Motion wake camera consumer = $active — refreshing FGS type")
        PersistentLog.info("FGS", "motionWake camera consumer=$active — serviceBound=${apiService != null}")
        if (active) {
            // Motion wake needs the FGS even if API is disabled. startServiceIfNeeded
            // is a no-op if service is already bound. On first bind, onServiceConnected
            // calls refreshCameraFgsType() which applies the camera bit.
            startServiceIfNeeded()
        }
        refreshCameraFgsType()
    }

    /**
     * Apply the union of all camera-FGS consumers (RTSP + motion-wake) to the
     * bound API service. If the service isn't bound yet, this is a no-op —
     * onServiceConnected will call refreshCameraFgsType() once bound. If the
     * service never binds (apiEnabled=false), motion-wake's camera will not
     * survive screen-off on strict OEMs — future improvement could force-start
     * the service for this case.
     */
    private fun refreshCameraFgsType() {
        val include = rtspCameraActive || motionWakeCameraActive
        val svc = apiService
        if (svc == null) {
            Log.d(TAG, "refreshCameraFgsType: includeCamera=$include but apiService not bound yet")
            PersistentLog.warn(
                "FGS",
                "refreshCameraFgsType: include=$include but service NOT bound — deferred to onServiceConnected (rtsp=$rtspCameraActive motionWake=$motionWakeCameraActive)"
            )
            return
        }
        PersistentLog.info(
            "FGS",
            "refreshCameraFgsType: applying includeCamera=$include (rtsp=$rtspCameraActive motionWake=$motionWakeCameraActive)"
        )
        svc.applyForegroundServiceType(includeCamera = include)
    }

    /**
     * Update the current URL (called when WebView navigates)
     */
    fun setCurrentUrl(url: String) {
        currentUrl = url
    }

    /**
     * Get the current URL by evaluating JavaScript in the WebView.
     * This correctly handles SPA navigation where the URL changes via History API.
     * Falls back to cached currentUrl if JS evaluation fails or times out.
     */
    private fun getCurrentUrlFromWebView(): String {
        return try {
            var url = ""
            val latch = java.util.concurrent.CountDownLatch(1)

            webView.post {
                webView.evaluateJavascript("(typeof dashieGetHaUrl==='function'?dashieGetHaUrl():window.location.href)") { result ->
                    // Result comes back as a JSON string (quoted), so strip quotes
                    url = result?.trim('"') ?: ""
                    if (url.isEmpty() || url == "null") {
                        url = currentUrl // Fallback to cached URL
                    }
                    latch.countDown()
                }
            }

            // Wait up to 200ms for result
            val completed = latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)

            // If timeout occurred or url is empty, fall back to cached URL
            // This prevents returning empty string when JS eval is slow (during screensaver, memory pressure, etc.)
            if (!completed || url.isEmpty()) {
                Log.d(TAG, "getCurrentUrlFromWebView: timeout or empty, using cached URL: $currentUrl")
                return currentUrl
            }

            // Filter out data: URLs, return cached URL instead
            if (url.startsWith("data:") || url.startsWith("about:")) {
                currentUrl
            } else {
                // Update cache with successful JS eval result to keep it in sync
                // This fixes the case where doUpdateVisitedHistory wasn't called
                // (e.g., after memory reload restoration)
                if (url != currentUrl) {
                    Log.d(TAG, "getCurrentUrlFromWebView: updating cached URL from '$currentUrl' to '$url'")
                    currentUrl = url
                }
                url
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get current URL from WebView: ${e.message}")
            currentUrl // Fallback to cached URL
        }
    }

    /**
     * Setup all callbacks between the service and MainActivity components
     */
    private fun setupServiceCallbacks() {
        val service = apiService ?: return

        // Screen control
        service.onScreenOnRequested = { onScreenOnRequested?.invoke() }
        service.onScreenOffRequested = { method -> onScreenOffRequested?.invoke(method) }

        // App control
        service.onRestartAppRequested = { onRestartAppRequested?.invoke() }
        service.onRebootDeviceRequested = { onRebootDeviceRequested?.invoke() }
        service.onToForegroundRequested = {
            // Already handled by service bringing activity to foreground
        }

        // URL loading
        service.onLoadUrlRequested = { url ->
            (context as? android.app.Activity)?.runOnUiThread {
                val isDashieMode = halitePrefs.account.showsDashboard
                if (isDashieMode) {
                    // Widgets mode — load URL directly in the top-level WebView
                    Log.i(TAG, "🔗 loadUrl (widgets mode): loading $url")
                    webView.loadUrl(url)
                } else {
                    // Kiosk mode — HA is inside an iframe, navigate via evalInHaIframe
                    val escapedUrl = url.replace("'", "\\'").replace("\\", "\\\\")
                    val isHaPath = url.startsWith("/")
                    if (isHaPath) {
                        Log.i(TAG, "🔗 loadUrl: SPA navigate HA iframe to $url")
                        webView.evaluateJavascript(
                            "if (typeof window.evalInHaIframe === 'function') { " +
                                "window.evalInHaIframe(\"history.pushState(null,'','$escapedUrl');window.dispatchEvent(new Event('location-changed'));\"); " +
                            "} else { console.warn('[Dashie] evalInHaIframe not available for loadUrl'); }",
                            null
                        )
                    } else {
                        Log.i(TAG, "🔗 loadUrl: full navigate HA iframe to $url")
                        webView.evaluateJavascript(
                            "if (typeof window.evalInHaIframe === 'function') { " +
                                "window.evalInHaIframe(\"window.location.href='$escapedUrl'\"); " +
                            "} else { console.warn('[Dashie] evalInHaIframe not available for loadUrl'); }",
                            null
                        )
                    }
                }
            }
        }

        service.onLoadStartUrlRequested = {
            (context as? android.app.Activity)?.runOnUiThread {
                val isDashieMode = halitePrefs.account.showsDashboard
                if (isDashieMode) {
                    // Widgets mode — reload dashieapp.com
                    val dashieUrl = halitePrefs.account.dashieUrl
                    Log.i(TAG, "🔄 loadStartUrl (widgets mode): reloading $dashieUrl")
                    webView.loadUrl(dashieUrl)
                } else {
                    // Kiosk mode — navigate HA iframe back to the start dashboard
                    // AND force a real page reload, so "Reload Dashboard" actually
                    // reloads even when the user is already on the start page
                    // (history.pushState alone is a no-op against the same URL).
                    // If the iframe is cross-origin (user navigated to a camera
                    // stream URL etc.), evalInHaIframe returns 'cross-origin' and
                    // we fall back to reloading the entire kiosk shell.
                    val dashboard = halitePrefs.connection.dashboardName
                    val startPath = if (dashboard.isNotEmpty()) "/$dashboard" else "/lovelace"
                    Log.i(TAG, "🔄 loadStartUrl: attempting SPA navigate + iframe reload to $startPath")
                    val escapedPath = startPath.replace("'", "\\'").replace("\\", "\\\\")
                    webView.evaluateJavascript(
                        "(function() { " +
                            "try { " +
                                "if (typeof window.evalInHaIframe === 'function') { " +
                                    "var result = window.evalInHaIframe(\"history.pushState(null,'','$escapedPath');window.dispatchEvent(new Event('location-changed'));\"); " +
                                    "if (result === 'same-origin') { " +
                                        "var f = document.getElementById('ha-content'); " +
                                        "if (f && f.contentWindow) { try { f.contentWindow.location.reload(); } catch(_) {} } " +
                                    "} " +
                                    "return result || 'ok'; " +
                                "} else { return 'no_func'; } " +
                            "} catch(e) { return 'error:' + e.message; } " +
                        "})()"
                    ) { result ->
                        val cleanResult = result?.trim('"') ?: ""
                        if (cleanResult != "same-origin") {
                            Log.w(TAG, "🔄 loadStartUrl: evalInHaIframe returned '$cleanResult', reloading kiosk shell")
                            val shellUrl = halitePrefs.connection.getShellPageUrl()
                            webView.loadUrl(shellUrl)
                        }
                    }
                }
            }
        }

        // Volume control (maps 0-100 to our 0-10 scale)
        // Skip no-op changes and suppress stale remote values for 10s after a local change.
        // The feedback loop: device reports vol → MA poll reads it → HA/MA pushes setAudioVolume
        // with a stale value → device changes → repeat. Breaking the loop by ignoring redundant
        // sets and giving local changes time to propagate through the poll cycle.
        service.setVolumeCallback = { level, stream ->
            val currentDeviceVol = deviceControls.getVolume() * 10  // 0-10 → 0-100
            val now = System.currentTimeMillis()
            val isRedundant = Math.abs(level - currentDeviceVol) <= 5  // within rounding tolerance
            val localAge = now - lastLocalVolChangeAt
            val isLocalRecent = localAge < 10_000
            if (isRedundant) {
                Log.d(TAG, "🔊 setVolume SKIP (redundant): remote=$level device=$currentDeviceVol")
            } else if (isLocalRecent) {
                Log.w(TAG, "🔊 setVolume SUPPRESSED: remote=$level device=$currentDeviceVol localAge=${localAge/1000}s")
            } else {
                val ourLevel = (level * 10 / 100).coerceIn(0, 10)
                Log.i(TAG, "🔊 setVolume APPLIED: remote=$level → device=$ourLevel (was $currentDeviceVol)")
                deviceControls.setVolume(ourLevel)
            }
        }

        service.getVolumeCallback = { stream ->
            // Convert our 0-10 to 0-100
            val ourLevel = deviceControls.getVolume()
            ourLevel * 10
        }

        // Brightness control (0-255 from API, 0-10 internally)
        service.setBrightnessCallback = { level ->
            // Convert 0-255 to 0-10 for BrightnessManager
            val ourLevel = (level * 10 / 255).coerceIn(0, 10)
            deviceControls.setBrightness(ourLevel) // BrightnessManager expects 0-10
        }

        service.getBrightnessCallback = {
            // Convert our 0-10 to 0-255 for API
            val ourLevel = deviceControls.getBrightness() // Returns 0-10
            ourLevel * 255 / 10
        }

        // Ambient light sensor
        service.getAmbientLightCallback = {
            getAmbientLightCallback?.invoke() ?: 0
        }

        // Screen state - returns false if our app has screen "off" (black overlay)
        // OR if the physical display is actually off
        service.isScreenOnCallback = {
            val isOurScreenOff = isScreenOffCallback?.invoke() ?: false
            if (isOurScreenOff) {
                false
            } else {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                powerManager?.isInteractive ?: true
            }
        }

        // URLs - use evaluateJavascript for real-time URL (catches SPA navigation)
        service.getCurrentUrlCallback = { getCurrentUrlFromWebView() }
        service.getStartUrlCallback = { halitePrefs.connection.haUrl }

        // TTS + audio playback callbacks (delegated to AudioPlaybackManager)
        audioManager.registerCallbacks(service)

        // Screensaver control
        service.startScreensaverCallback = {
            onStartScreensaver?.invoke()
        }
        service.stopScreensaverCallback = {
            onStopScreensaver?.invoke()
        }
        // Use callback to get actual screensaver state from ScreenDimmer
        // This correctly reports state when external app mode is active
        service.isInScreensaverCallback = { isInScreensaverCallback?.invoke() ?: false }

        // App launcher
        service.startApplicationCallback = { packageName -> startApplication(packageName) }

        // Overlay message (Toast)
        service.showOverlayMessageCallback = { text, duration -> showOverlayMessage(text, duration) }

        // Motion trigger
        service.triggerMotionCallback = { onTriggerMotion?.invoke() }

        // WebView control
        service.clearCacheCallback = { clearCache() }
        service.clearWebstorageCallback = { clearWebstorage() }
        service.refreshWebViewCallback = { callback ->
            val handler = onRefreshWebView
            if (handler != null) {
                handler.invoke(callback)
            } else {
                callback(false)
            }
        }

        // Memory diagnostics (PSS from MemoryMonitor)
        service.getAppMemoryMbCallback = {
            com.dashieapp.Dashie.halite.diagnostics.MemoryMonitor.getTotalPssMb()
        }

        // RTSP streaming + camera capture callbacks (delegated to RtspStreamingManager)
        rtspManager.registerCallbacks(service)

        // Screenshot capture — draws the live dashboard WebView to a watermarked JPEG
        // (delegated to ScreenshotCaptureManager; recreation-safe + foreground-independent).
        screenshotManager.registerCallbacks(service)

        // Settings, display, lock/PIN, screensaver callbacks (delegated to SettingsPreferenceManager)
        settingsManager.registerCallbacks(service)

        // Dark mode callbacks
        service.isDarkModeCallback = {
            deviceControls.isSystemDarkMode()
        }
        service.setDarkModeCallback = { enabled ->
            val success = deviceControls.setSystemDarkMode(enabled)
            if (success) {
                // Notify kiosk shell JS so it can update CSS theme classes + HA iframe theme
                Handler(Looper.getMainLooper()).post {
                    webView.evaluateJavascript(
                        "if (window.onColorSchemeChanged) window.onColorSchemeChanged($enabled);",
                        null
                    )
                }
            }
            success
        }
        service.canControlDarkModeCallback = {
            true  // Dark mode works via AppCompatDelegate + SharedPreferences on all devices
        }

        // Device name - from Halite preferences (set by web app via JS bridge)
        service.getDeviceNameCallback = { halitePrefs.connection.deviceName }

        // HA sensor detection state - read from publisher
        service.isMotionDetectedCallback = { isMotionDetectedCallback?.invoke() ?: false }
        service.isFaceDetectedCallback = { isFaceDetectedCallback?.invoke() ?: false }
        service.isMotionDetectionEnabledCallback = { isMotionDetectionEnabledCallback?.invoke() ?: false }
        service.isFaceDetectionEnabledCallback = { isFaceDetectionEnabledCallback?.invoke() ?: false }

        // MQTT state - read from preferences/service
        service.isMqttEnabledCallback = { isMqttEnabledCallback?.invoke() ?: false }
        service.getMqttBaseTopicCallback = { getMqttBaseTopicCallback?.invoke() ?: "" }

        // Video feed triggers - pushed by HA integration
        service.onVideoFeedTriggerCallback = { params ->
            onVideoFeedTriggerCallback?.invoke(params)
        }
        service.getVideoFeedTriggerEntitiesCallback = {
            if (halitePrefs.videoFeed.enabled) {
                halitePrefs.videoFeed.getEnabledRules()
                    .filter {
                        // Only report triggers for trigger/trigger_alert modes
                        val mode = it.optString("subscriptionMode", "")
                        mode.isEmpty() || mode == "trigger" || mode == "trigger_alert"
                    }
                    .mapNotNull { it.optString("triggerEntityId").takeIf { id -> id.isNotBlank() } }
                    .distinct()
            } else emptyList()
        }

        // Timer control - forward to DashboardTelemetryBridge via activity
        service.createTimerCallback = { seconds, description ->
            onCreateTimer?.invoke(seconds, description) ?: false
        }
        service.cancelTimerCallback = { timerId ->
            onCancelTimer?.invoke(timerId) ?: false
        }

        // Voice command injection - forward to voice controller via activity
        service.processVoiceCommandCallback = { transcript, callback ->
            val handler = onProcessVoiceCommand
            if (handler != null) {
                handler.invoke(transcript, callback)
            } else {
                Log.w(TAG, "No voice command handler registered")
                callback(false, "Voice not initialized")
            }
        }

        // STT benchmark - forward to voice controller via activity
        service.sttBenchCallback = { file, provider, timeoutMs, callback ->
            val handler = onRunSttBench
            if (handler != null) {
                handler.invoke(file, provider, timeoutMs, callback)
            } else {
                Log.w(TAG, "DROP: no STT bench handler registered")
                callback(org.json.JSONObject().apply { put("error", "Voice not initialized") })
            }
        }

        // STT bench RECORD — same forwarding shape as the bench above
        service.sttBenchRecordCallback = { name, ms, callback ->
            val handler = onRecordSttBench
            if (handler != null) {
                handler.invoke(name, ms, callback)
            } else {
                Log.w(TAG, "DROP: no STT bench record handler registered")
                callback(org.json.JSONObject().apply { put("error", "Voice not initialized") })
            }
        }

        // HA-Assist turn benchmark — forward to voice controller via activity
        service.haAssistBenchCallback = { file, timeoutMs, allowBusy, callback ->
            val handler = onRunHaAssistBench
            if (handler != null) {
                handler.invoke(file, timeoutMs, allowBusy, callback)
            } else {
                Log.w(TAG, "DROP: no HA-Assist bench handler registered")
                callback(org.json.JSONObject().apply { put("error", "Voice not initialized") })
            }
        }

        Log.i(TAG, "Service callbacks configured")
    }

    // ============================================
    // Implementation methods for new API commands
    // ============================================

    /**
     * Launch an application by package name
     */
    private fun startApplication(packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Started application: $packageName")
                true
            } else {
                Log.w(TAG, "No launch intent for package: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start application $packageName: ${e.message}", e)
            false
        }
    }

    /**
     * Show a toast/overlay message
     */
    private fun showOverlayMessage(text: String, durationMs: Int) {
        handler.post {
            // Use Toast - duration is either short (2s) or long (3.5s)
            val toastDuration = if (durationMs > 2500) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            Toast.makeText(context, text, toastDuration).show()
            Log.d(TAG, "Showing overlay message: $text")
        }
    }

    /**
     * Clear WebView cache and HA login credentials
     */
    private fun clearCache() {
        handler.post {
            try {
                webView.clearCache(true)
                // Also clear HA login credentials since they're tied to the session
                halitePrefs.connection.clearCredentials()
                Log.i(TAG, "WebView cache and HA credentials cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear cache: ${e.message}", e)
            }
        }
    }

    /**
     * Clear WebView local storage and HA login credentials
     */
    private fun clearWebstorage() {
        handler.post {
            try {
                WebStorage.getInstance().deleteAllData()
                // Also clear HA login credentials since they're tied to the session
                halitePrefs.connection.clearCredentials()
                Log.i(TAG, "WebView storage and HA credentials cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear web storage: ${e.message}", e)
            }
        }
    }

    /**
     * Check if the service is currently running
     */
    fun isServiceRunning(): Boolean = apiService?.isServerRunning() == true

}

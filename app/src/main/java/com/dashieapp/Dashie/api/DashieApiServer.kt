package com.dashieapp.Dashie.api

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.api.handlers.AudioApiHandler
import com.dashieapp.Dashie.api.handlers.DeviceInfoHandler
import com.dashieapp.Dashie.api.handlers.HaAssistBenchHandler
import com.dashieapp.Dashie.api.handlers.RtspApiHandler
import com.dashieapp.Dashie.api.handlers.ScreenNavApiHandler
import com.dashieapp.Dashie.api.handlers.SettingsApiHandler
import com.dashieapp.Dashie.api.handlers.SttBenchHandler
import com.dashieapp.Dashie.api.handlers.VoiceConfigHandler
import com.dashieapp.Dashie.api.handlers.VoiceEnginesHandler
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Dashie REST API Server (Fully Kiosk Browser-compatible).
 *
 * NanoHTTPD server implementing the REST API that Home Assistant's Fully Kiosk integration
 * uses to control and monitor Android kiosk devices. Runs on port 2323 with password auth.
 *
 * Request routing delegates to domain-specific handlers:
 * - [DeviceInfoHandler] — deviceInfo, listSettings, device.xml
 * - [ScreenNavApiHandler] — screen on/off, navigation, app/WebView, camera
 * - [AudioApiHandler] — volume, TTS, sound playback
 * - [SettingsApiHandler] — settings, dark mode, screensaver, kiosk lock/PIN
 * - [RtspApiHandler] — RTSP camera streaming
 *
 * Timer, voice command, and debug handlers are kept here (~110 lines).
 */
class DashieApiServer(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") password: String,  // Deprecated: use callbacks.getApiPassword() instead
    private val callbacks: DashieApiCallbacks,
    port: Int  // Port to run the API server on (default 2323, configurable by user)
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "DashieAPI"
        const val DEFAULT_PORT = 2323  // Default Fully Kiosk Browser port
    }

    // Store port for reference
    val port: Int = port

    // Domain handlers
    private val deviceInfoHandler = DeviceInfoHandler(context, callbacks, port)
    private val audioApiHandler = AudioApiHandler(callbacks)
    private val screenNavApiHandler = ScreenNavApiHandler(callbacks)
    private val settingsApiHandler = SettingsApiHandler(callbacks)
    private val rtspApiHandler = RtspApiHandler(callbacks)
    private val sttBenchHandler = SttBenchHandler(context, callbacks)
    private val haAssistBenchHandler = HaAssistBenchHandler(context, callbacks)
    private val voiceEnginesHandler = VoiceEnginesHandler(context)
    private val voiceConfigHandler = VoiceConfigHandler(context)

    override fun start() {
        super.start()
        Log.i(TAG, "Dashie API server started on port $port")
    }

    override fun stop() {
        super.stop()
        Log.i(TAG, "Dashie API server stopped")
    }

    override fun serve(session: IHTTPSession): Response {
        // Observe-only: record the caller's source IP for the planned :2323
        // IP-allowlist design (measures real HA network configs; blocks nothing).
        ApiAccessObserver.observe(context, session.remoteIpAddress)

        // Handle UPnP device description XML endpoint (for SSDP discovery)
        if (session.uri == "/device.xml") {
            return deviceInfoHandler.handleDeviceXml()
        }

        val params = session.parms ?: emptyMap()
        val cmd = params["cmd"] ?: return errorResponse("Missing 'cmd' parameter")
        val providedPassword = params["password"] ?: ""

        // Authenticate - read password dynamically so changes take effect immediately
        val currentPassword = callbacks.getApiPassword()
        if (currentPassword.isNotEmpty() && providedPassword != currentPassword) {
            Log.w(TAG, "API authentication failed for cmd=$cmd")
            return errorResponse("Invalid password", Response.Status.UNAUTHORIZED)
        }

        Log.d(TAG, "API request: cmd=$cmd")

        return try {
            when (cmd) {
                // Device info
                "deviceInfo" -> deviceInfoHandler.handleDeviceInfo(params)
                "listSettings" -> deviceInfoHandler.handleListSettings()

                // Screen & navigation
                "screenOn" -> screenNavApiHandler.handleScreenOn()
                "screenOff" -> screenNavApiHandler.handleScreenOff(params)
                "startScreensaver" -> screenNavApiHandler.handleStartScreensaver()
                "stopScreensaver" -> screenNavApiHandler.handleStopScreensaver()
                "loadUrl" -> screenNavApiHandler.handleLoadUrl(params)
                "loadStartUrl" -> screenNavApiHandler.handleLoadStartUrl()
                "restartApp" -> screenNavApiHandler.handleRestartApp()
                "rebootDevice" -> screenNavApiHandler.handleRebootDevice()
                "toForeground" -> screenNavApiHandler.handleToForeground()
                "startApplication" -> screenNavApiHandler.handleStartApplication(params)
                "setOverlayMessage" -> screenNavApiHandler.handleSetOverlayMessage(params)
                "triggerMotion" -> screenNavApiHandler.handleTriggerMotion()
                "clearCache" -> screenNavApiHandler.handleClearCache()
                "clearWebstorage" -> screenNavApiHandler.handleClearWebstorage()
                "refreshWebView" -> screenNavApiHandler.handleRefreshWebView()
                "getCamshot" -> screenNavApiHandler.handleGetCamshot()
                "getScreenshot" -> screenNavApiHandler.handleGetScreenshot()

                // Audio
                "setAudioVolume" -> audioApiHandler.handleSetAudioVolume(params)
                "textToSpeech" -> audioApiHandler.handleTextToSpeech(params)
                "stopTextToSpeech" -> audioApiHandler.handleStopTextToSpeech()
                "playSound" -> audioApiHandler.handlePlaySound(params)
                "stopSound" -> audioApiHandler.handleStopSound()
                "pauseSound" -> audioApiHandler.handlePauseSound()
                "resumeSound" -> audioApiHandler.handleResumeSound()
                "seekSound" -> audioApiHandler.handleSeekSound(params)
                "muteAudio" -> audioApiHandler.handleMuteAudio()
                "unmuteAudio" -> audioApiHandler.handleUnmuteAudio()
                "setMediaInfo" -> audioApiHandler.handleSetMediaInfo(params)
                "setPlayerId" -> audioApiHandler.handleSetPlayerId(params)

                // Settings, dark mode, screensaver config, kiosk lock/PIN
                "setStringSetting" -> settingsApiHandler.handleSetStringSetting(params)
                "setBooleanSetting" -> settingsApiHandler.handleSetBooleanSetting(params)
                "setDarkMode" -> settingsApiHandler.handleSetDarkMode(params)
                "getDarkMode" -> settingsApiHandler.handleGetDarkMode()
                "setScreenOffMethod" -> settingsApiHandler.handleSetScreenOffMethod(params)
                "getScreenOffMethod" -> settingsApiHandler.handleGetScreenOffMethod()
                "setScreensaverMode" -> settingsApiHandler.handleSetScreensaverMode(params)
                "setPhotoSource" -> settingsApiHandler.handleSetPhotoSource(params)
                "setHaMediaFolder" -> settingsApiHandler.handleSetHaMediaFolder(params)
                "lockKiosk" -> settingsApiHandler.handleLockKiosk()
                "unlockKiosk" -> settingsApiHandler.handleUnlockKiosk(params)
                "setPin" -> settingsApiHandler.handleSetPin(params)
                "clearPin" -> settingsApiHandler.handleClearPin()
                "getPinStatus" -> settingsApiHandler.handleGetPinStatus()

                // RTSP camera
                "startRtspStream" -> rtspApiHandler.handleStartRtspStream()
                "stopRtspStream" -> rtspApiHandler.handleStopRtspStream()
                "getRtspStatus" -> rtspApiHandler.handleGetRtspStatus()
                "setRtspResolution" -> rtspApiHandler.handleSetRtspResolution(params)
                "setRtspFps" -> rtspApiHandler.handleSetRtspFps(params)
                "setRtspBitrate" -> rtspApiHandler.handleSetRtspBitrate(params)
                "getRtspConfig" -> rtspApiHandler.handleGetRtspConfig()

                // Video feed triggers (pushed by HA integration)
                "videoFeedTrigger" -> handleVideoFeedTrigger(params)

                // Voice-config push (HA integration, on household-sharing toggle / voice
                // settings change) — anon kiosk re-fetches + hard-applies the account pipeline
                "refreshVoiceConfig" -> handleRefreshVoiceConfig()

                // Timer, voice, debug (kept in server)
                "createTimer" -> handleCreateTimer(params)
                "cancelTimer" -> handleCancelTimer(params)
                "voiceCommand" -> handleVoiceCommand(params)
                "sttBench" -> sttBenchHandler.handle(params)
                "sttBenchRecord" -> sttBenchHandler.handleRecord(params)
                "haAssistBench" -> haAssistBenchHandler.handle(params)
                // Pure READ of local-engine detection (Phase D of the device-validation
                // plan). Sets nothing — see VoiceEnginesHandler on why a setter here
                // would reopen the one-sided-write class the settings audit closed.
                "voiceEngines" -> voiceEnginesHandler.handleVoiceEngines()
                // Resolved voice/AI config with per-key SOURCE (account-pushed vs device-local
                // vs add-on-reported) — the release-build window into otherwise-unreadable
                // prefs. Pure read; see VoiceConfigHandler.
                "getVoiceConfig" -> voiceConfigHandler.handleGetVoiceConfig()
                // Runtime provenance: which JS bundle / voice pipeline / brain route / mode this
                // device is ACTUALLY running (postmortem 20260718 P0 — the incident-D collapser).
                "provenance" -> newFixedLengthResponse(
                    Response.Status.OK, "application/json",
                    com.dashieapp.Dashie.halite.diagnostics.ProvenanceReporter.report(context).toString(2)
                )
                "testMwwWav" -> handleTestMwwWav(params)
                "triggerTestCrash" -> handleTriggerTestCrash(params)
                "triggerRendererCrash" -> handleTriggerRendererCrash()
                "getDiagnosticsLog" -> handleGetDiagnosticsLog()
                else -> errorResponse("Unknown command: $cmd")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling command $cmd: ${e.message}", e)
            errorResponse("Error: ${e.message}")
        }
    }

    // ============================================
    // Video feed trigger (pushed by HA integration)
    // ============================================

    /**
     * GET /?cmd=videoFeedTrigger&entityId=binary_sensor.xxx&state=on
     *     [&feedId=...&feedLabel=...&cameraEntityId=...&mode=...&autoDismissSeconds=...
     *      &continueWhileActive=...&alertSound=...&streamSourceType=...&streamSourceUrl=...]
     *
     * Called by the HA integration when a monitored trigger entity changes state.
     * Legacy calls send only entityId+state. Centralized feed registry calls include
     * extended fields so the tablet can display the feed without local rule lookup.
     */
    private fun handleVideoFeedTrigger(params: Map<String, String>): Response {
        val entityId = params["entityId"] ?: return errorResponse("Missing 'entityId' parameter")
        val state = params["state"] ?: return errorResponse("Missing 'state' parameter")
        Log.i(TAG, "API: videoFeedTrigger entityId=$entityId state=$state feedId=${params["feedId"]}")
        callbacks.onVideoFeedTrigger(params)
        return successResponse("Trigger received: $entityId=$state")
    }

    /**
     * GET /?cmd=refreshVoiceConfig
     *
     * Pushed by the HA integration when household Dashie Cloud sharing is toggled
     * (or the account's voice settings change). The anon kiosk re-probes
     * /api/dashie/voice/status and hard-applies the account's voice pipeline, so it
     * reflects the household setup with no voice command, settings visit, or reboot.
     */
    private fun handleRefreshVoiceConfig(): Response {
        Log.i(TAG, "API: refreshVoiceConfig — re-fetching + applying account voice config")
        callbacks.onRefreshVoiceConfig()
        return successResponse("Voice config refresh triggered")
    }

    // ============================================
    // Timer commands
    // ============================================

    /**
     * GET /?cmd=createTimer&seconds=300&description=cooking
     *
     * Create a countdown timer in the overlay.
     * @param seconds Required - duration in seconds
     * @param description Optional - timer label
     */
    private fun handleCreateTimer(params: Map<String, String>): Response {
        val seconds = params["seconds"]?.toIntOrNull()
            ?: return errorResponse("Missing or invalid 'seconds' parameter")
        if (seconds <= 0 || seconds > 86400) {
            return errorResponse("'seconds' must be between 1 and 86400")
        }
        val description = params["description"]
        Log.i(TAG, "API: createTimer requested: ${seconds}s, description=$description")
        val success = callbacks.createTimer(seconds, description)
        return if (success) {
            successResponse("Timer created: ${seconds}s" + if (description != null) " ($description)" else "")
        } else {
            errorResponse("Failed to create timer")
        }
    }

    /**
     * GET /?cmd=cancelTimer&timerId=abc123
     *
     * Cancel a timer. If timerId is omitted, cancels the first active timer.
     * @param timerId Optional - specific timer to cancel
     */
    private fun handleCancelTimer(params: Map<String, String>): Response {
        val timerId = params["timerId"]
        Log.i(TAG, "API: cancelTimer requested: timerId=$timerId")
        val success = callbacks.cancelTimer(timerId)
        return if (success) {
            successResponse("Timer cancelled" + if (timerId != null) ": $timerId" else "")
        } else {
            errorResponse("Failed to cancel timer")
        }
    }

    /**
     * GET /?cmd=voiceCommand&text=[transcript]
     *
     * Inject a voice command transcript directly into the voice pipeline.
     * Bypasses wake word detection and STT - useful for testing NLP and actions.
     *
     * Example: ?cmd=voiceCommand&text=set%205%20minute%20timer&password=xxx
     *
     * @param text Required - the voice command transcript to process
     * @return JSON with success status and voice response (if any)
     */
    private fun handleVoiceCommand(params: Map<String, String>): Response {
        val transcript = params["text"] ?: return errorResponse("Missing 'text' parameter")

        Log.i(TAG, "API: voiceCommand requested: \"$transcript\"")

        // Use a latch to wait for async voice processing
        val latch = java.util.concurrent.CountDownLatch(1)
        var resultSuccess = false
        var resultResponse: String? = null

        callbacks.processVoiceCommand(transcript) { success, response ->
            resultSuccess = success
            resultResponse = response
            latch.countDown()
        }

        // Wait up to 30 seconds for voice processing (LLM can be slow)
        val completed = latch.await(30, java.util.concurrent.TimeUnit.SECONDS)

        return if (!completed) {
            Log.w(TAG, "Voice command timed out after 30s")
            errorResponse("Voice command timed out")
        } else {
            Log.i(TAG, "Voice command result: success=$resultSuccess, response=$resultResponse")
            val json = JSONObject().apply {
                put("status", if (resultSuccess) "OK" else "ERROR")
                put("transcript", transcript)
                put("success", resultSuccess)
                if (resultResponse != null) {
                    put("response", resultResponse)
                }
            }
            newFixedLengthResponse(
                if (resultSuccess) Response.Status.OK else Response.Status.INTERNAL_ERROR,
                "application/json",
                json.toString(2)
            )
        }
    }

    // ============================================
    // Debug / Testing
    // ============================================

    /**
     * GET /?cmd=triggerTestCrash&delay=[ms]
     *
     * Triggers an uncaught exception on the main thread after a delay (default 1000ms).
     * This tests the full crash reporting pipeline:
     * 1. CrashHandler captures the exception and writes crash report to disk
     * 2. App terminates
     * 3. On next launch, MainCrashReportHandler detects the report and shows Send dialog
     *
     * MWW parity harness: run a WAV through the exact detector pipeline (native
     * frontend + TFLite classifier) and return frame probabilities as JSON.
     * Lets us diff armv7 vs arm64 feature extraction over HTTP on devices
     * without adb (Echo Show class). Path must be readable by the app
     * (e.g. filesDir or /sdcard/Download with storage permission).
     */
    private fun handleTestMwwWav(params: Map<String, String>): Response {
        val path = params["path"] ?: return errorResponse("Missing 'path' parameter")
        return try {
            val summary = com.dashieapp.Dashie.wakeword.microwakeword.MicroWakeWordWavTest
                .processWavFile(context, path)
            newFixedLengthResponse(Response.Status.OK, "application/json", summary.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "testMwwWav failed: ${e.message}", e)
            errorResponse("testMwwWav failed: ${e.message}")
        }
    }

    /**
     * Only available in debug builds.
     */
    private fun handleTriggerTestCrash(params: Map<String, String>): Response {
        if (!BuildConfig.DEBUG) {
            return errorResponse("Test crash only available in debug builds")
        }

        val delayMs = params["delay"]?.toLongOrNull() ?: 1000L

        Log.w(TAG, "TEST CRASH: Scheduling uncaught exception on main thread in ${delayMs}ms")

        Handler(Looper.getMainLooper()).postDelayed({
            throw RuntimeException(
                "TEST CRASH triggered via API (triggerTestCrash command). " +
                "This is intentional for testing crash reporting."
            )
        }, delayMs)

        return successResponse("Test crash scheduled in ${delayMs}ms. App will terminate.")
    }

    /**
     * GET /?cmd=triggerRendererCrash
     *
     * Triggers a WebView renderer crash by loading chrome://crash.
     * This tests the onRenderProcessGone() handler which catches crashes
     * in the WebView's separate renderer process (not caught by Java exception handler).
     *
     * Only available in debug builds.
     */
    private fun handleTriggerRendererCrash(): Response {
        if (!BuildConfig.DEBUG) {
            return errorResponse("Renderer crash test only available in debug builds")
        }

        Log.w(TAG, "TEST RENDERER CRASH: Loading chrome://crash to crash WebView renderer")

        Handler(Looper.getMainLooper()).post {
            callbacks.onLoadUrl("chrome://crash")
        }

        return successResponse("Renderer crash triggered. WebView will crash and onRenderProcessGone() should fire.")
    }

    // ============================================
    // Diagnostics
    // ============================================

    /**
     * GET /?cmd=getDiagnosticsLog
     *
     * Returns the in-memory DiagnosticBuffer (~last ~30 min on a busy
     * device) followed by the on-disk PersistentLog (rotating 3MB total,
     * survives crashes/restarts) as plain text. Used by the HA
     * integration's Diagnostics download for offline tablets that can't
     * upload to Supabase directly.
     */
    private fun handleGetDiagnosticsLog(): Response {
        return try {
            val sb = StringBuilder()
            sb.appendLine("=== Dashie App ===")
            sb.appendLine("Version: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
            sb.appendLine()
            sb.appendLine("=== DiagnosticBuffer (in-memory, recent ~30 min) ===")
            sb.appendLine()
            sb.appendLine(DiagnosticBuffer.exportAsText())
            sb.appendLine()
            sb.appendLine()
            sb.appendLine("=== PersistentLog (on-disk, rotating ~3MB total) ===")
            sb.appendLine()
            sb.append(PersistentLog.exportLogs())
            newFixedLengthResponse(Response.Status.OK, "text/plain", sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "getDiagnosticsLog failed: ${e.message}", e)
            errorResponse("Failed to read logs: ${e.message}")
        }
    }

    // ============================================
    // Response helpers
    // ============================================

    private fun successResponse(message: String): Response {
        val json = JSONObject().apply {
            put("status", "OK")
            put("message", message)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun errorResponse(message: String, status: Response.Status = Response.Status.BAD_REQUEST): Response {
        val json = JSONObject().apply {
            put("status", "ERROR")
            put("message", message)
        }
        return newFixedLengthResponse(status, "application/json", json.toString())
    }
}

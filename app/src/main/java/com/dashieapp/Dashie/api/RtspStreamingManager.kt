package com.dashieapp.Dashie.api

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.util.DeviceInfoHelper
import com.dashieapp.Dashie.halite.RtspCameraServer
import com.dashieapp.Dashie.halite.RtspCameraServer2
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.audio.SharedAudioBuffer

/**
 * Manages RTSP camera streaming for the Dashie API.
 *
 * Handles:
 * - RTSP server lifecycle (v2 Camera2 with dual surfaces, v1 fallback)
 * - Motion detection (pixel-based v2, FPS-based v1)
 * - Stream configuration (resolution, FPS, bitrate)
 * - Camera capture (JPEG frames from stream)
 * - Shared audio buffer for mic sharing with wake word detection
 *
 * Extracted from DashieServiceManager to isolate RTSP concerns.
 */
class RtspStreamingManager(
    private val context: Context,
    private val halitePrefs: HalitePreferences
) {
    companion object {
        private const val TAG = "DashieSvcMgr"
    }

    // RTSP camera server for streaming (v2 with dual-surface motion detection)
    private var rtspCameraServer2: RtspCameraServer2? = null

    // Legacy RTSP server (v1 with FPS-based motion detection - kept as fallback)
    private var rtspCameraServer: RtspCameraServer? = null

    // RTSP failure state (preserved after server is released)
    private var rtspHasFailed = false
    private var rtspFailureReason: String? = null

    // Use v2 server (Camera2 with dual surfaces for true motion detection)
    private val useRtspV2 = true

    // Shared audio buffer for RTSP to share microphone with wake word detection
    private var sharedAudioBuffer: SharedAudioBuffer? = null

    // Stored face frame callback - applied to RTSP server when it starts
    // This allows setting the callback before the RTSP server is created
    private var pendingFaceFrameCallback: ((android.graphics.Bitmap, Int) -> Unit)? = null

    // HA sensor face frame callback (parallel to wake detector)
    private var pendingHaSensorFaceFrameCallback: ((android.graphics.Bitmap, Int) -> Unit)? = null

    // RTSP motion callback (notifies MotionWakeManager when motion detected in stream)
    var onRtspMotionDetected: (() -> Unit)? = null

    // HA sensor motion callback (parallel to wake motion callback)
    var onHaSensorMotionDetected: (() -> Unit)? = null

    // Fired when RTSP camera streaming starts (true) or stops (false).
    // DashieServiceManager wires this to DashieApiService.applyForegroundServiceType()
    // so the foreground service carries the camera FGS type only while the camera is
    // actually in use — keeping Samsung from revoking camera access while backgrounded.
    var onCameraActiveChanged: ((Boolean) -> Unit)? = null

    // ============================================
    // Shared Audio Buffer
    // ============================================

    /**
     * Set the SharedAudioBuffer for RTSP to share microphone with wake word detection.
     * This avoids audio HAL conflicts on devices with limited audio support (like Google TV).
     *
     * If RTSP server is already running with a dedicated mic, it will be restarted to use
     * the shared buffer instead.
     *
     * @param buffer The SharedAudioBuffer from VoiceAssistantCoordinator
     */
    fun setSharedAudioBuffer(buffer: SharedAudioBuffer?) {
        sharedAudioBuffer = buffer
        Log.i(TAG, "🔊 setSharedAudioBuffer: id=${if (buffer != null) System.identityHashCode(buffer) else "null"}")
        // Note: Caller is responsible for restarting RTSP if needed
        // The onVoiceInitialized callback in MainActivity handles this
    }

    // ============================================
    // RTSP Camera Streaming
    // ============================================

    /**
     * Initialize and start the RTSP camera server.
     * Called when RTSP streaming is enabled in settings.
     *
     * Uses v2 server (Camera2 with dual surfaces) for true pixel-based motion detection,
     * or falls back to v1 server (FPS-based motion detection) if v2 fails.
     *
     * @param forceStart If true, bypasses the preference check (used for debugging preview)
     */
    fun startRtspServer(forceStart: Boolean = false): Boolean {
        Log.i(TAG, "startRtspServer() called (useRtspV2=$useRtspV2, forceStart=$forceStart)")

        // Skip RTSP on TV devices in full Dashie mode — TV sticks (ONN, Fire TV Stick)
        // don't have enough resources to run RTSP camera + full dashboard simultaneously.
        // Tablets handle both fine so this only applies to TV-class devices.
        if (!forceStart && halitePrefs.account.showsDashboard) {
            val isTv = DeviceInfoHelper.isFireTV() || DeviceInfoHelper.isTVDevice(context)
            if (isTv) {
                Log.i(TAG, "RTSP skipped — TV device in full Dashie mode")
                return false
            }
        }

        // Don't start if RTSP is disabled in preferences (unless force-starting for debugging)
        if (!forceStart && !halitePrefs.camera.rtspEnabled) {
            Log.i(TAG, "RTSP not enabled in preferences - skipping start")
            return false
        }

        // Camera streaming is being started — promote the API foreground service to
        // carry the camera FGS type BEFORE the camera opens, so Samsung's camera
        // policy won't revoke access while the app is backgrounded.
        onCameraActiveChanged?.invoke(true)

        // Check if already running
        if (useRtspV2) {
            if (rtspCameraServer2 != null) {
                val running = rtspCameraServer2?.isRunning() == true
                if (running) {
                    Log.i(TAG, "RTSP v2 server already running - skipping start")
                    return true
                } else {
                    // Server exists but not running - release it and start fresh
                    Log.w(TAG, "RTSP v2 server exists but not running - releasing and restarting")
                    rtspCameraServer2?.release()
                    rtspCameraServer2 = null
                }
            }
        } else {
            if (rtspCameraServer != null) {
                val running = rtspCameraServer?.isRunning() == true
                if (running) {
                    Log.i(TAG, "RTSP v1 server already running - skipping start")
                    return true
                } else {
                    // Server exists but not running - release it and start fresh
                    Log.w(TAG, "RTSP v1 server exists but not running - releasing and restarting")
                    rtspCameraServer?.release()
                    rtspCameraServer = null
                }
            }
        }

        val started = if (useRtspV2) {
            startRtspServerV2() || startRtspServerV1()  // Fallback to v1 if v2 fails
        } else {
            startRtspServerV1()
        }
        if (!started) {
            Log.w(TAG, "RTSP server failed to start — reverting foreground service type")
            onCameraActiveChanged?.invoke(false)
        }
        return started
    }

    /**
     * Start RTSP server v2 (Camera2 with dual surfaces for true motion detection)
     */
    private fun startRtspServerV2(): Boolean {
        // Clear failure state at the start of a new attempt
        rtspHasFailed = false
        rtspFailureReason = null

        try {
            Log.i(TAG, "Starting RTSP v2 server (Camera2 + dual surfaces)...")
            rtspCameraServer2 = RtspCameraServer2(context, halitePrefs)

            // Set shared audio buffer if available (for sharing mic with wake word detection)
            // This avoids audio HAL conflicts on devices like Google TV
            // NOTE: Must be set BEFORE initialize() to take effect
            if (sharedAudioBuffer != null) {
                Log.i(TAG, "RTSP v2: Setting shared audio buffer (will use 16kHz from wake word mic)")
                rtspCameraServer2?.setSharedAudioBuffer(sharedAudioBuffer)
            } else {
                Log.i(TAG, "RTSP v2: No shared audio buffer available (will use dedicated 44.1kHz mic)")
            }

            // Initialize - this creates camera2Source
            rtspCameraServer2?.initialize()

            // Check if initialization failed
            if (rtspCameraServer2?.hasFailed() == true) {
                val reason = rtspCameraServer2?.getFailureReason()
                Log.e(TAG, "RTSP v2 server initialization failed: $reason")
                // Save failure state before releasing
                rtspHasFailed = true
                rtspFailureReason = reason
                rtspCameraServer2?.release()
                rtspCameraServer2 = null
                return false
            }

            // Setup motion detection callback AFTER initialize() so camera2Source exists
            rtspCameraServer2?.setMotionCallback { motionDetected ->
                if (motionDetected) {
                    Log.d(TAG, "RTSP v2 motion detected - notifying callback")
                    onRtspMotionDetected?.invoke()
                    onHaSensorMotionDetected?.invoke()
                }
            }

            // Start the server
            val success = rtspCameraServer2?.startServer() == true
            if (success) {
                Log.i(TAG, "RTSP v2 server started: ${rtspCameraServer2?.getStreamUrl()}")

                // Enable motion detection
                rtspCameraServer2?.enableMotionDetection()
                Log.i(TAG, "RTSP v2 motion detection enabled (pixel-based)")

                // Apply pending face frame callback (set before RTSP started, e.g. settings test)
                pendingFaceFrameCallback?.let { callback ->
                    rtspCameraServer2?.setFaceFrameCallback(callback)
                    Log.i(TAG, "RTSP v2: Applied pending face frame callback")
                }

                // Apply pending HA sensor face frame callback
                pendingHaSensorFaceFrameCallback?.let { callback ->
                    rtspCameraServer2?.setHaSensorFaceFrameCallback(callback)
                    Log.i(TAG, "RTSP v2: Applied pending HA sensor face frame callback")
                }
            } else {
                val reason = rtspCameraServer2?.getFailureReason()
                Log.e(TAG, "Failed to start RTSP v2 server: $reason")
                // Save failure state before releasing
                rtspHasFailed = true
                rtspFailureReason = reason
                rtspCameraServer2?.release()
                rtspCameraServer2 = null
            }
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Error starting RTSP v2 server: ${e.message}", e)
            // Save failure state before releasing
            rtspHasFailed = true
            rtspFailureReason = e.message
            rtspCameraServer2?.release()
            rtspCameraServer2 = null
            return false
        }
    }

    /**
     * Start RTSP server v1 (FPS-based motion detection - fallback)
     */
    private fun startRtspServerV1(): Boolean {
        try {
            Log.i(TAG, "Starting RTSP v1 server (FPS-based motion detection)...")
            rtspCameraServer = RtspCameraServer(context, halitePrefs)

            // Setup motion detection callback
            rtspCameraServer?.setMotionCallback { motionDetected ->
                if (motionDetected) {
                    Log.d(TAG, "RTSP v1 motion detected - notifying callback")
                    onRtspMotionDetected?.invoke()
                }
            }

            // Initialize in headless mode (no preview surface needed)
            rtspCameraServer?.initializeHeadless()

            // Check if initialization failed
            if (rtspCameraServer?.hasFailed() == true) {
                val reason = rtspCameraServer?.getFailureReason()
                Log.e(TAG, "RTSP v1 server initialization failed: $reason")
                // Save failure state (v1 is fallback, so this is the final failure)
                rtspHasFailed = true
                rtspFailureReason = reason
                rtspCameraServer?.release()
                rtspCameraServer = null
                return false
            }

            // Start the server
            val success = rtspCameraServer?.startServer() == true
            if (success) {
                Log.i(TAG, "RTSP v1 server started: ${rtspCameraServer?.getStreamUrl()}")
                // Clear any failure state from v2 since v1 succeeded
                rtspHasFailed = false
                rtspFailureReason = null

                // Motion detection is always enabled when streaming
                rtspCameraServer?.enableMotionDetection()
                Log.i(TAG, "RTSP v1 motion detection enabled (FPS-based)")
            } else {
                val reason = rtspCameraServer?.getFailureReason()
                Log.e(TAG, "Failed to start RTSP v1 server: $reason")
                // Save failure state before releasing
                rtspHasFailed = true
                rtspFailureReason = reason
                rtspCameraServer?.release()
                rtspCameraServer = null
            }
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Error starting RTSP v1 server: ${e.message}", e)
            // Save failure state before releasing
            rtspHasFailed = true
            rtspFailureReason = e.message
            rtspCameraServer?.release()
            rtspCameraServer = null
            return false
        }
    }

    /**
     * Stop the RTSP camera server (both v1 and v2).
     * This completely stops the camera - use pauseRtspServer() to keep camera for motion detection.
     * Note: Failure state is NOT cleared here - it's only cleared when starting a new attempt.
     */
    fun stopRtspServer() {
        rtspCameraServer2?.let { server ->
            Log.i(TAG, "Stopping RTSP v2 server")
            server.release()
        }
        rtspCameraServer2 = null

        rtspCameraServer?.let { server ->
            Log.i(TAG, "Stopping RTSP v1 server")
            server.release()
        }
        rtspCameraServer = null

        // Camera released — drop the camera FGS type so the service is back to
        // SPECIAL_USE only (no unnecessary camera/microphone foreground type).
        onCameraActiveChanged?.invoke(false)
    }

    /**
     * Check if RTSP server is running.
     */
    fun isRtspServerRunning(): Boolean =
        rtspCameraServer2?.isRunning() == true || rtspCameraServer?.isRunning() == true

    /**
     * Check if RTSP v2 is running (supports camera preview frames).
     * Returns false if v1 is running instead (no preview support).
     */
    fun isRtspV2Running(): Boolean = rtspCameraServer2?.isRunning() == true

    /**
     * Check if the RTSP camera is currently in a failure/recovery state.
     * Memory recovery should skip RTSP restarts when this is true to avoid
     * making Samsung "Camera disabled by policy" worse with rapid open/close cycles.
     */
    fun isRtspInCameraRecovery(): Boolean = rtspCameraServer2?.isInCameraRecovery() == true

    /**
     * Get the RTSP socket watchdog status (for diagnostics).
     */
    fun getRtspWatchdogStatus(): String =
        rtspCameraServer2?.getWatchdogStatus() ?: "N/A"

    /**
     * Get the RTSP stream URL.
     */
    fun getRtspStreamUrl(): String =
        rtspCameraServer2?.getStreamUrl() ?: rtspCameraServer?.getStreamUrl() ?: ""

    /**
     * Get the number of connected RTSP clients.
     */
    fun getRtspClientCount(): Int =
        rtspCameraServer2?.getClientCount() ?: rtspCameraServer?.getClientCount() ?: 0

    /**
     * Check if RTSP server has failed to start.
     * Uses stored failure state since server objects are released on failure.
     */
    fun hasRtspFailed(): Boolean = rtspHasFailed

    /**
     * Get the RTSP failure reason (if failed).
     * Uses stored failure state since server objects are released on failure.
     */
    fun getRtspFailureReason(): String? = rtspFailureReason

    /**
     * Enable/disable motion detection on the RTSP stream.
     */
    fun setRtspMotionDetection(enabled: Boolean) {
        if (enabled) {
            rtspCameraServer2?.enableMotionDetection()
            rtspCameraServer?.enableMotionDetection()
        } else {
            rtspCameraServer2?.disableMotionDetection()
            rtspCameraServer?.disableMotionDetection()
        }
    }

    /**
     * Check if RTSP motion detection is enabled.
     */
    fun isRtspMotionDetectionEnabled(): Boolean =
        rtspCameraServer2?.isMotionDetectionEnabled() == true ||
        rtspCameraServer?.isMotionDetectionEnabled() == true

    /**
     * Re-arm RTSP motion detection (reset delay timer).
     * Call this when the screen dims to give the camera time to stabilize
     * before detecting motion (avoids false positives from screen dimming).
     */
    fun rearmRtspMotionDetection() {
        rtspCameraServer2?.rearmMotionDetection()
        // Note: v1 server doesn't need this - it uses FPS-based detection
    }

    /**
     * Update RTSP motion detection threshold.
     * Call this when the threshold is changed in settings so the running
     * RTSP server picks up the new value immediately.
     *
     * @param threshold Threshold percentage (0.5 - 20.0)
     */
    fun setRtspMotionThreshold(threshold: Double) {
        Log.i(TAG, "Setting RTSP motion threshold to $threshold%")
        rtspCameraServer2?.setMotionThreshold(threshold)
        rtspCameraServer?.setMotionThreshold(threshold)
    }

    /**
     * Set face detection frame callback on the RTSP camera source.
     * Frames are converted from YUV to Bitmap and forwarded to the callback.
     * Callback is stored so it can be applied when the RTSP server starts later.
     */
    fun setFaceFrameCallback(callback: ((android.graphics.Bitmap, Int) -> Unit)?) {
        pendingFaceFrameCallback = callback
        rtspCameraServer2?.setFaceFrameCallback(callback)
    }

    /**
     * Set HA sensor face detection frame callback on the RTSP camera source.
     * Parallel to setFaceFrameCallback — used by HaSensorPublisher to get face frames
     * without interfering with the wake detector's face frame callback.
     */
    fun setHaSensorFaceFrameCallback(callback: ((android.graphics.Bitmap, Int) -> Unit)?) {
        pendingHaSensorFaceFrameCallback = callback
        rtspCameraServer2?.setHaSensorFaceFrameCallback(callback)
    }

    // ============================================
    // Hardware (Camera2 ISP) Face Detection
    // ============================================

    /** Check if the camera supports hardware face detection */
    fun isHwFaceDetectSupported(): Boolean = rtspCameraServer2?.isHwFaceDetectSupported() ?: false

    /** Set callback for HW face detection results (3-state: NONE/DETECTED/TOO_FAR) */
    fun setHwFaceResultCallback(callback: ((Int) -> Unit)?) {
        rtspCameraServer2?.setHwFaceResultCallback(callback)
    }

    /** Set callback for HW face detected wake trigger */
    fun setHwFaceDetectedCallback(callback: (() -> Unit)?) {
        rtspCameraServer2?.setHwFaceDetectedCallback(callback)
    }

    /** Set HA sensor callback for HW face detection results */
    fun setHaSensorHwFaceResultCallback(callback: ((Int) -> Unit)?) {
        rtspCameraServer2?.setHaSensorHwFaceResultCallback(callback)
    }

    /** Set minimum face size for HW face detection distance filter */
    fun setHwFaceMinSizePercent(percent: Float) {
        rtspCameraServer2?.setHwFaceMinSizePercent(percent)
    }

    /**
     * Pause the RTSP server (stop streaming but keep camera ready for motion detection).
     * Used when HA goes offline to prevent OOM from buffered frames with no consumer.
     */
    fun pauseRtspServer() {
        if (rtspCameraServer2?.isRunning() == true) {
            Log.i(TAG, "Pausing RTSP server (HA offline) - camera stays on for motion detection")
            rtspCameraServer2?.pauseServer()
            DiagnosticBuffer.info("RTSP", "Server paused (HA offline)")
        }
        if (rtspCameraServer?.isRunning() == true) {
            Log.i(TAG, "Pausing RTSP v1 server (HA offline) - camera stays on for motion detection")
            rtspCameraServer?.pauseServer()
        }
    }

    /**
     * Resume the RTSP server after HA comes back online.
     * Tries to resume existing paused server first, falls back to full start if needed.
     */
    fun resumeRtspServer() {
        if (!halitePrefs.camera.rtspEnabled) {
            Log.d(TAG, "RTSP not enabled, skipping resume")
            return
        }

        // Try to resume paused server first (faster, keeps existing state)
        var resumed = false
        if (rtspCameraServer2?.isRunning() == true) {
            resumed = rtspCameraServer2?.resumeServer() == true
            if (resumed) {
                Log.i(TAG, "Resumed RTSP server (was paused)")
                DiagnosticBuffer.info("RTSP", "Server resumed")
            }
        }
        if (!resumed && rtspCameraServer?.isRunning() == true) {
            resumed = rtspCameraServer?.resumeServer() == true
            if (resumed) {
                Log.i(TAG, "Resumed RTSP v1 server (was paused)")
            }
        }

        // If couldn't resume (server was fully stopped), do full start
        if (!resumed && rtspCameraServer2?.isRunning() != true && rtspCameraServer?.isRunning() != true) {
            Log.i(TAG, "Starting RTSP server (was stopped)")
            DiagnosticBuffer.info("RTSP", "Server starting (was stopped)")
            startRtspServer()
        }
    }

    // ============================================
    // RTSP Configuration API
    // ============================================

    /**
     * Set RTSP resolution. Updates preferences - requires stream restart to take effect.
     * This uses a custom resolution rather than the preset (480p/720p/1080p).
     */
    fun setRtspResolution(width: Int, height: Int) {
        // Map to closest standard resolution for the preference, or store custom
        val resolution = when {
            width <= 854 && height <= 480 -> HalitePreferences.RTSP_RESOLUTION_480P
            width <= 1280 && height <= 720 -> HalitePreferences.RTSP_RESOLUTION_720P
            else -> HalitePreferences.RTSP_RESOLUTION_1080P
        }
        halitePrefs.camera.rtspResolution = resolution
        Log.i(TAG, "RTSP resolution set to $resolution (requested: ${width}x${height})")
    }

    /**
     * Set RTSP FPS. Updates preferences - requires stream restart to take effect.
     */
    fun setRtspFps(fps: Int) {
        halitePrefs.camera.rtspFps = fps.coerceIn(10, 30)
        Log.i(TAG, "RTSP FPS set to ${halitePrefs.camera.rtspFps}")
    }

    /**
     * Set RTSP bitrate. Updates preferences - requires stream restart to take effect.
     */
    fun setRtspBitrate(bitrate: Int) {
        halitePrefs.camera.rtspBitrate = bitrate.coerceIn(500_000, 10_000_000)
        Log.i(TAG, "RTSP bitrate set to ${halitePrefs.camera.rtspBitrate} bps")
    }

    /**
     * Get current RTSP configuration.
     */
    fun getRtspConfig(): RtspConfig {
        return RtspConfig(
            width = halitePrefs.camera.rtspResolutionWidth,
            height = halitePrefs.camera.rtspResolutionHeight,
            fps = halitePrefs.camera.rtspFps,
            bitrate = halitePrefs.camera.rtspBitrate,
            port = halitePrefs.camera.rtspPort,
            softwareEncoding = halitePrefs.camera.rtspForceSoftwareEncoding
        )
    }

    // ============================================
    // Camera Capture
    // ============================================

    /**
     * Capture an image from the camera.
     * Only works when RTSP streaming is active (grabs latest frame from stream).
     * Returns null if RTSP is not running or no frame is available.
     */
    fun captureImage(callback: (ByteArray?) -> Unit) {
        if (!isRtspServerRunning()) {
            Log.w(TAG, "getCamshot: Camera not available (RTSP not running)")
            callback(null)
            return
        }

        val frame = rtspCameraServer2?.getLatestJpegFrame()
        if (frame != null) {
            Log.d(TAG, "getCamshot: Using RTSP stream frame (${frame.size} bytes)")
            callback(frame)
        } else {
            Log.d(TAG, "getCamshot: RTSP running but no frame available yet")
            callback(null)
        }
    }

    /**
     * Get the latest camera preview frame (for motion wake settings preview).
     * Returns JPEG frame from RTSP stream if available, null otherwise.
     * This is synchronous and non-blocking - returns immediately.
     */
    fun getCameraPreviewFrame(): ByteArray? {
        return rtspCameraServer2?.getLatestJpegFrame()
    }

    /**
     * Get current motion score from RTSP camera for graph visualization.
     * Returns 0.0 if RTSP is not active or no score available.
     */
    fun getCurrentRtspMotionScore(): Double {
        return rtspCameraServer2?.getCurrentMotionScore() ?: 0.0
    }

    // ============================================
    // Service Callback Registration
    // ============================================

    /**
     * Register RTSP-related callbacks on the API service.
     */
    fun registerCallbacks(service: DashieApiService) {
        // RTSP streaming callbacks
        service.startRtspStreamCallback = { startRtspServer() }
        service.stopRtspStreamCallback = { stopRtspServer() }
        service.isRtspStreamRunningCallback = { isRtspServerRunning() }
        service.getRtspStreamUrlCallback = { getRtspStreamUrl() }
        service.getRtspClientCountCallback = { getRtspClientCount() }
        service.hasRtspFailedCallback = { hasRtspFailed() }
        service.getRtspFailureReasonCallback = { getRtspFailureReason() }

        // RTSP configuration callbacks
        service.setRtspResolutionCallback = { width, height -> setRtspResolution(width, height) }
        service.setRtspFpsCallback = { fps -> setRtspFps(fps) }
        service.setRtspBitrateCallback = { bitrate -> setRtspBitrate(bitrate) }
        service.getRtspConfigCallback = { getRtspConfig() }

        // Camera capture
        service.getCamshotCallback = { callback -> captureImage(callback) }
    }
}

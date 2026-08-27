package com.dashieapp.Dashie.halite

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.util.FpsListener
import com.pedro.rtspserver.RtspServerCamera1
import com.pedro.common.ConnectChecker
import kotlin.math.abs

/**
 * RTSP Camera Server for Dashie Lite.
 *
 * Provides real-time camera streaming via RTSP protocol with integrated motion detection.
 * Uses RootEncoder's hardware-accelerated H.264 encoding for efficient streaming.
 *
 * Motion detection uses the SurfaceTexture's OnFrameAvailableListener to count frames
 * and trigger wake detection at ~0.5s intervals.
 *
 * Features:
 * - H.264 hardware encoding (low CPU usage)
 * - Multi-client support
 * - Motion detection from SurfaceTexture frame notifications
 * - Configurable resolution, FPS, and bitrate
 * - Optional authentication
 *
 * Stream URL: rtsp://[device-ip]:[port]/
 */
class RtspCameraServer(
    private val context: Context,
    private val prefs: HalitePreferences
) : ConnectChecker {

    companion object {
        private const val TAG = "RtspCameraServer"

        // Motion detection constants
        // At 15fps, checking every 8 frames = ~0.5s interval
        private const val MOTION_CHECK_FRAME_INTERVAL = 8
        private const val MOTION_DEBOUNCE_MS = 2000L
        private const val ARM_DELAY_MS = 2000L
        private const val SAMPLE_SIZE = 16  // Small sample for quick comparison
        private const val CHANGE_THRESHOLD = 10  // Minimum change to count as motion
    }

    // RTSP Server instance
    private var rtspServer: RtspServerCamera1? = null

    // Dummy surface texture for headless streaming
    private var dummySurfaceTexture: SurfaceTexture? = null

    // Server state
    private var isServerRunning = false
    private var isStreaming = false
    private var clientCount = 0

    // Motion detection state
    private var motionCallback: ((Boolean) -> Unit)? = null
    private var lastTimestamp = 0L
    private var lastMotionTime = 0L
    private var armTime = 0L
    private var motionEnabled = false
    private var motionThreshold = 5.0

    // FPS-based motion detection
    private var lastFps = -1
    private var fpsHistory = mutableListOf<Int>()
    private val FPS_HISTORY_SIZE = 5

    // Handler for motion detection on background thread
    private var motionHandler: Handler? = null
    private var motionHandlerThread: HandlerThread? = null

    // Error state
    private var hasFailed = false
    private var failureReason: String? = null

    /**
     * Initialize the RTSP server with a preview surface.
     */
    fun initialize(surfaceView: SurfaceView) {
        if (rtspServer != null) {
            Log.w(TAG, "Server already initialized")
            return
        }

        try {
            Log.w(TAG, "Surface-based init requested, using headless mode instead")
            initializeHeadless()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize RTSP server: ${e.message}", e)
            hasFailed = true
            failureReason = e.message
        }
    }

    /**
     * Initialize the RTSP server in headless mode.
     * Uses Camera1 API with a dummy SurfaceTexture for reliable streaming.
     */
    fun initializeHeadless() {
        if (rtspServer != null) {
            Log.w(TAG, "Server already initialized")
            return
        }

        try {
            // Create a dummy SurfaceTexture for Camera1 to output to
            dummySurfaceTexture = SurfaceTexture(0)
            dummySurfaceTexture?.setDefaultBufferSize(
                prefs.camera.rtspResolutionWidth,
                prefs.camera.rtspResolutionHeight
            )

            // Create the RTSP server
            rtspServer = RtspServerCamera1(context, this, prefs.camera.rtspPort)

            // Set FPS listener for motion detection - this gets called every second with current FPS
            // We use this as a heartbeat to trigger motion analysis
            rtspServer?.setFpsListener(object : FpsListener.Callback {
                override fun onFps(fps: Int) {
                    this@RtspCameraServer.onFpsUpdate(fps)
                }
            })

            // Set motion threshold from preferences
            motionThreshold = prefs.screensaver.cameraWakeThresholdDouble

            // Start motion handler thread
            motionHandlerThread = HandlerThread("RtspMotionDetection").apply { start() }
            motionHandler = Handler(motionHandlerThread!!.looper)

            Log.i(TAG, "RTSP server initialized in headless mode on port ${prefs.camera.rtspPort} (Camera1 API with motion detection)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize RTSP server (headless): ${e.message}", e)
            hasFailed = true
            failureReason = e.message
            dummySurfaceTexture?.release()
            dummySurfaceTexture = null
        }
    }

    /**
     * Called by RootEncoder with the current FPS approximately every second.
     * We use FPS variation to detect motion since pixel data isn't accessible.
     * Motion causes exposure adjustments which affect frame rate.
     *
     * Note: FPS-based detection is a secondary method. The light sensor provides
     * primary motion detection when RTSP is enabled, as FPS variation may not
     * be sensitive enough for subtle motion.
     */
    private fun onFpsUpdate(fps: Int) {
        if (!motionEnabled || !isServerRunning) return

        val now = System.currentTimeMillis()

        // Skip if still in arming delay
        if (now - armTime < ARM_DELAY_MS) return

        // Track FPS history for variance analysis
        fpsHistory.add(fps)
        if (fpsHistory.size > FPS_HISTORY_SIZE) {
            fpsHistory.removeAt(0)
        }

        // Need enough history to analyze
        if (fpsHistory.size < 3) return

        // Analyze FPS variance - motion causes FPS fluctuation
        val avgFps = fpsHistory.average()
        val variance = fpsHistory.map { abs(it - avgFps) }.average()
        val targetFps = prefs.camera.rtspFps.toDouble()
        val fpsDeviation = abs(avgFps - targetFps)

        // Motion detection criteria:
        // 1. High variance in FPS (motion causes fluctuation)
        // 2. FPS significantly below target (exposure changes from motion)
        val varianceThreshold = 1.5 + (motionThreshold * 0.2)  // ~1.5-5.5 based on threshold
        val deviationThreshold = 2.0 + (motionThreshold * 0.3)  // ~2.0-8.0 based on threshold

        if (variance > varianceThreshold || fpsDeviation > deviationThreshold) {
            // Check debounce
            if (now - lastMotionTime > MOTION_DEBOUNCE_MS) {
                lastMotionTime = now
                Log.i(TAG, "FPS-based motion detected: variance=%.1f, deviation=%.1f".format(variance, fpsDeviation))

                // Trigger callback on main thread
                Handler(Looper.getMainLooper()).post {
                    motionCallback?.invoke(true)
                }
            }
        }
    }

    /**
     * Start the RTSP server.
     */
    fun startServer(): Boolean {
        val server = rtspServer
        if (server == null) {
            Log.e(TAG, "Cannot start: server not initialized")
            return false
        }

        if (isServerRunning) {
            Log.w(TAG, "Server already running")
            return true
        }

        if (hasFailed) {
            Log.w(TAG, "Server previously failed: $failureReason")
            return false
        }

        val width = prefs.camera.rtspResolutionWidth
        val height = prefs.camera.rtspResolutionHeight
        val fps = prefs.camera.rtspFps
        val bitrate = prefs.camera.rtspBitrate

        // Front camera needs 180° rotation for correct orientation
        // Camera1 API doesn't have rotation metadata, so we must physically rotate pixels
        val rotation = 180

        Log.i(TAG, "Starting RTSP server: ${width}x${height}@${fps}fps, ${bitrate/1_000_000}Mbps, rotation=${rotation}°")

        try {
            if (!server.prepareVideo(width, height, fps, bitrate, rotation)) {
                Log.e(TAG, "Failed to prepare video encoder")
                return false
            }

            try {
                if (!server.prepareAudio()) {
                    Log.w(TAG, "Failed to prepare audio encoder - continuing without audio")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to prepare audio (exception: ${e.message}) - continuing without audio")
            }

            Log.d(TAG, "Starting camera preview for encoding (front camera, Camera1 API)...")
            server.startPreview(CameraHelper.Facing.FRONT)

            Thread.sleep(300)

            Log.d(TAG, "Starting RTSP stream...")
            server.startStream("")

            Thread.sleep(200)
            isServerRunning = true
            isStreaming = true

            Log.i(TAG, "RTSP server started successfully")
            Log.i(TAG, "Stream URL: rtsp://${getLocalIpAddress()}:${prefs.camera.rtspPort}/")

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTSP server: ${e.message}", e)
            hasFailed = true
            failureReason = e.message
            return false
        }
    }

    /**
     * Pause the RTSP server - stops streaming but keeps camera running for motion detection.
     * Use this when HA goes offline to prevent OOM from buffered frames.
     */
    fun pauseServer() {
        val server = rtspServer ?: return

        if (!isServerRunning) {
            Log.d(TAG, "Server not running, nothing to pause")
            return
        }

        try {
            // Only stop the stream, NOT the preview - keep camera running for motion detection
            server.stopStream()
            isStreaming = false
            clientCount = 0
            Log.i(TAG, "RTSP server paused (camera still running for motion detection)")
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing RTSP server: ${e.message}", e)
        }
    }

    /**
     * Resume the RTSP server after pause - restarts streaming.
     */
    fun resumeServer(): Boolean {
        val server = rtspServer ?: return false

        if (!isServerRunning) {
            Log.d(TAG, "Server not running, cannot resume - need full start")
            return false
        }

        if (isStreaming) {
            Log.d(TAG, "Server already streaming")
            return true
        }

        return try {
            server.startStream("")
            isStreaming = true
            Log.i(TAG, "RTSP server resumed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming RTSP server: ${e.message}", e)
            false
        }
    }

    /**
     * Stop the RTSP server completely (including camera preview).
     */
    fun stopServer() {
        val server = rtspServer ?: return

        if (!isServerRunning) {
            Log.d(TAG, "Server not running")
            return
        }

        try {
            try {
                server.stopPreview()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping preview: ${e.message}")
            }

            server.stopStream()
            isServerRunning = false
            isStreaming = false
            clientCount = 0
            Log.i(TAG, "RTSP server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping RTSP server: ${e.message}", e)
        }
    }

    /**
     * Release all resources.
     */
    fun release() {
        stopServer()

        // Stop motion handler thread
        motionHandlerThread?.quitSafely()
        motionHandlerThread = null
        motionHandler = null

        dummySurfaceTexture?.setOnFrameAvailableListener(null)
        dummySurfaceTexture?.release()
        dummySurfaceTexture = null

        rtspServer = null
        motionCallback = null
        lastTimestamp = 0L
        Log.i(TAG, "RTSP server released")
    }

    fun isRunning(): Boolean = isServerRunning

    fun isActivelyStreaming(): Boolean = isStreaming && clientCount > 0

    fun getClientCount(): Int = clientCount

    fun getStreamUrl(): String {
        val ip = getLocalIpAddress()
        val port = prefs.camera.rtspPort
        return if (prefs.camera.rtspAuthEnabled && prefs.camera.hasRtspCredentials) {
            "rtsp://${prefs.camera.rtspAuthUser}:****@$ip:$port/"
        } else {
            "rtsp://$ip:$port/"
        }
    }

    fun hasFailed(): Boolean = hasFailed

    fun getFailureReason(): String? = failureReason

    fun resetFailedState() {
        hasFailed = false
        failureReason = null
        Log.i(TAG, "Failed state reset - can retry")
    }

    // ========== Motion Detection ==========

    fun setMotionCallback(callback: (Boolean) -> Unit) {
        motionCallback = callback
    }

    fun enableMotionDetection() {
        motionEnabled = true
        armTime = System.currentTimeMillis()
        lastTimestamp = 0L
        lastFps = -1
        fpsHistory.clear()
        Log.d(TAG, "Motion detection enabled (armed, ${ARM_DELAY_MS}ms delay, threshold: ${motionThreshold}%)")
    }

    fun disableMotionDetection() {
        motionEnabled = false
        lastTimestamp = 0L
        lastFps = -1
        fpsHistory.clear()
        Log.d(TAG, "Motion detection disabled")
    }

    fun isMotionDetectionEnabled(): Boolean = motionEnabled

    fun setMotionThreshold(threshold: Double) {
        motionThreshold = threshold.coerceIn(0.5, 20.0)
        Log.d(TAG, "Motion threshold set to ${motionThreshold}%")
    }

    // ========== ConnectChecker Implementation ==========

    override fun onConnectionStarted(url: String) {
        clientCount++
        Log.i(TAG, "Client connected. Total clients: $clientCount, URL: $url")
    }

    override fun onConnectionSuccess() {
        Log.i(TAG, "Connection successful")
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "Connection failed: $reason")
    }

    override fun onDisconnect() {
        clientCount = (clientCount - 1).coerceAtLeast(0)
        Log.i(TAG, "Client disconnected. Total clients: $clientCount")

        if (clientCount == 0 && prefs.camera.stopStreamWhenNoClients && isStreaming) {
            Log.i(TAG, "No clients connected - stopping stream to save resources")
        }
    }

    override fun onAuthError() {
        Log.e(TAG, "Authentication error")
    }

    override fun onAuthSuccess() {
        Log.i(TAG, "Authentication successful")
    }

    override fun onNewBitrate(bitrate: Long) {
        Log.d(TAG, "New bitrate: ${bitrate / 1000} kbps")
    }

    // ========== Utility Methods ==========

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr.hostAddress?.contains(".") == true && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address: ${e.message}")
        }
        return "0.0.0.0"
    }

    fun switchCamera(useFrontCamera: Boolean) {
        val server = rtspServer ?: return

        try {
            server.switchCamera()
            Log.i(TAG, "Switched to ${if (useFrontCamera) "front" else "back"} camera")
        } catch (e: Exception) {
            Log.e(TAG, "Error switching camera: ${e.message}")
        }
    }

    fun isFrontCamera(): Boolean {
        return rtspServer?.cameraFacing == CameraHelper.Facing.FRONT
    }
}

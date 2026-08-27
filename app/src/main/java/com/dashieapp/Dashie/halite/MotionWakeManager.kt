package com.dashieapp.Dashie.halite

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import androidx.lifecycle.LifecycleOwner

/**
 * Manages motion/proximity detection to wake the screen when someone approaches.
 *
 * Detection methods (in order of preference):
 * 1. RTSP stream motion detection (if RTSP streaming is active with motion enabled)
 *    - Motion is detected from the RTSP stream frames in RtspCameraServer
 *    - This mode is set externally when RTSP motion detection is active
 * 2. Camera-based visual motion detection (most reliable, like Fully Kiosk)
 * 3. Proximity sensor (if available)
 * 4. Light sensor (fallback)
 *
 * When motion is detected and the screen is dimmed, it triggers a wake callback.
 *
 * Note: When RTSP motion detection is active, this manager delegates to the RTSP
 * stream's motion detection instead of running its own camera capture. This prevents
 * camera resource conflicts.
 */
class MotionWakeManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner? = null,
    private val useCameraDetection: Boolean = true,
    private val useFaceDetection: Boolean = false,
    private val faceDistancePercent: Float = 0.15f,
    private val onMotionDetected: () -> Unit,
    // Presence keep-alive (face mode only): invoked while a face stays in view so the
    // caller can reset the inactivity timer and keep the screen awake. Distinct from
    // onMotionDetected (the wake trigger) — this fires for any detected face, including
    // a still one below the wake distance. Null in camera/motion mode.
    private val onFaceKeepAlive: (() -> Unit)? = null
) : SensorEventListener {

    companion object {
        private const val TAG = "MotionWakeManager"
        // Debounce time to prevent rapid wake/dim cycles
        private const val WAKE_DEBOUNCE_MS = 1000L
    }

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var proximitySensor: Sensor? = null
    private var lightSensor: Sensor? = null
    private var cameraMotionDetector: CameraMotionDetector? = null
    private var faceWakeDetector: FaceWakeDetector? = null

    private var isEnabled = false
    private var isScreenDimmed = false
    private var lastWakeTime = 0L

    // Proximity sensor state
    private var lastProximityValue = -1f
    private var proximityMaxRange = 5f

    // Light sensor state (fallback if no proximity sensor)
    private var baseLightLevel = -1f
    private var lightChangeThreshold = 50f // lux change to trigger wake

    // Detection mode
    private var detectionMode: DetectionMode = DetectionMode.NONE

    enum class DetectionMode {
        RTSP,       // Motion detection via RTSP stream (handled externally)
        CAMERA,     // Visual motion detection (preferred)
        FACE,       // Camera motion + ML Kit face detection (two-stage)
        PROXIMITY,  // Proximity sensor
        LIGHT,      // Light sensor (fallback)
        NONE        // No detection available
    }

    // Flag to indicate RTSP is handling motion detection externally
    private var rtspMotionEnabled = false

    init {
        // Log all available sensors for debugging
        logAvailableSensors()

        // Determine detection mode
        determineDetectionMode()
    }

    private fun determineDetectionMode() {
        // Try camera first if enabled and lifecycle owner available
        if (useCameraDetection && lifecycleOwner != null) {
            try {
                // In face mode, motion triggers face search instead of direct wake
                val motionCallback: () -> Unit = if (useFaceDetection) {
                    { onMotionTriggeredForFace() }
                } else {
                    { triggerWake("camera motion") }
                }

                cameraMotionDetector = CameraMotionDetector(
                    context,
                    lifecycleOwner,
                    onMotionDetected = motionCallback,
                    onCameraError = { error -> handleCameraError(error) }
                )
                // Check if camera is actually available before committing
                if (cameraMotionDetector?.hasCamera() == true) {
                    if (useFaceDetection) {
                        faceWakeDetector = FaceWakeDetector(
                            onFaceDetected = { triggerWake("face confirmed") }
                        ).apply {
                            minFaceSizePercent = faceDistancePercent
                            onFacePresent = onFaceKeepAlive
                        }
                        cameraMotionDetector?.setFaceWakeDetector(faceWakeDetector)
                        detectionMode = DetectionMode.FACE
                        Log.i(TAG, "✓ Using FACE detection (camera motion → ML Kit face → wake)")
                    } else {
                        detectionMode = DetectionMode.CAMERA
                        Log.i(TAG, "✓ Using CAMERA for motion detection (like Fully Kiosk)")
                    }
                    return
                } else {
                    Log.w(TAG, "Camera motion detection not available - no camera on device")
                    cameraMotionDetector = null
                    faceWakeDetector?.destroy()
                    faceWakeDetector = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Camera motion detection not available: ${e.message}")
            }
        }

        // Fallback to sensor-based detection
        fallbackToSensorDetection()
    }

    /**
     * Called when motion is detected in face mode.
     * Notifies the face detector which starts/extends face search with cooldown.
     */
    private fun onMotionTriggeredForFace() {
        val detector = faceWakeDetector ?: return
        detector.onMotionDetected()
    }

    /**
     * Handle camera errors by attempting recovery, then falling back to sensor detection.
     */
    private fun handleCameraError(error: String) {
        Log.e(TAG, "Camera error: $error")

        // CameraMotionDetector has its own internal recovery (watchdog + auto-retry).
        // Only fall back to sensors if it has truly given up (max recovery attempts exhausted).
        if (cameraMotionDetector?.hasFailed() == true) {
            Log.e(TAG, "Camera has permanently failed - falling back to sensor detection")
            PersistentLog.warn(
                "MOTION",
                "Camera detection permanently failed — falling back to sensors. Wake-on-face/camera will no longer work."
            )
            cameraMotionDetector?.destroy()
            cameraMotionDetector = null
            faceWakeDetector?.destroy()
            faceWakeDetector = null
            fallbackToSensorDetection()

            // If we were running, restart with sensor detection
            if (isEnabled) {
                isEnabled = false
                start()
            }
        } else {
            Log.i(TAG, "Camera error is transient - CameraMotionDetector will attempt recovery")
        }
    }

    /**
     * Set up sensor-based detection as fallback
     */
    private fun fallbackToSensorDetection() {
        // Try proximity sensor
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (proximitySensor != null) {
            proximityMaxRange = proximitySensor!!.maximumRange
            detectionMode = DetectionMode.PROXIMITY
            Log.i(TAG, "✓ Using PROXIMITY sensor: ${proximitySensor!!.name} (max range: $proximityMaxRange)")
            return
        }
        Log.w(TAG, "✗ No proximity sensor available")

        // Fallback to light sensor
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor != null) {
            detectionMode = DetectionMode.LIGHT
            Log.i(TAG, "✓ Using LIGHT sensor as fallback: ${lightSensor!!.name}")
            return
        }

        Log.w(TAG, "✗ No light sensor available either - motion wake disabled")
        detectionMode = DetectionMode.NONE
    }

    private fun logAvailableSensors() {
        val allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        Log.i(TAG, "=== Available Sensors (${allSensors.size} total) ===")

        // Log proximity and light sensors specifically
        val proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        Log.i(TAG, "Proximity: ${proximity?.name ?: "NOT AVAILABLE"}")
        Log.i(TAG, "Light: ${light?.name ?: "NOT AVAILABLE"}")

        // Log all sensor types for debugging
        allSensors.forEach { sensor ->
            Log.d(TAG, "  - ${sensor.name} (type=${sensor.type}, vendor=${sensor.vendor})")
        }
        Log.i(TAG, "==========================================")
    }

    /**
     * Start listening for motion/proximity events
     */
    fun start() {
        if (isEnabled) return

        when (detectionMode) {
            DetectionMode.RTSP -> {
                // RTSP mode - motion detection is handled externally by RtspCameraServer
                // Nothing to start here, just mark as enabled
                isEnabled = true
                Log.i(TAG, "Motion wake started (using RTSP stream - handled externally)")
                PersistentLog.info("MOTION", "Motion wake started — mode=RTSP (external)")
            }
            DetectionMode.CAMERA, DetectionMode.FACE -> {
                cameraMotionDetector?.start()
                // Arm immediately so detection runs whenever motion wake is active — not
                // only during the screensaver (which is the only thing that used to arm it,
                // via onScreenDimmed). This is the always-on camera: presence keeps the
                // screen awake while it's on, and wakes it while it's off — including a
                // power-button-off, which never triggers our onScreenDimmed arm path. Without
                // this, a refresh/mode-change leaves the detector disarmed until the next
                // screensaver cycle, so a power-button-off wouldn't wake (intermittently).
                cameraMotionDetector?.enable()
                if (detectionMode == DetectionMode.FACE) {
                    faceWakeDetector?.onMotionDetected()
                }
                isEnabled = true
                val modeLabel = if (detectionMode == DetectionMode.FACE) "face" else "camera"
                Log.i(TAG, "Motion wake started (using $modeLabel, armed)")
                PersistentLog.info("MOTION", "Motion wake started — mode=$modeLabel (armed)")
            }
            DetectionMode.PROXIMITY, DetectionMode.LIGHT -> {
                val sensor = proximitySensor ?: lightSensor
                if (sensor != null) {
                    sensorManager.registerListener(
                        this,
                        sensor,
                        SensorManager.SENSOR_DELAY_NORMAL
                    )
                    isEnabled = true
                    Log.i(TAG, "Motion wake started (using ${sensor.name})")
                    PersistentLog.info("MOTION", "Motion wake started — mode=${detectionMode.name.lowercase()} sensor=${sensor.name}")
                }
            }
            DetectionMode.NONE -> {
                Log.w(TAG, "Cannot start - no detection methods available")
                PersistentLog.warn("MOTION", "Motion wake cannot start — no detection methods available")
            }
        }
    }

    /**
     * Stop listening for motion/proximity events
     */
    fun stop() {
        if (!isEnabled) return

        isEnabled = false

        when (detectionMode) {
            DetectionMode.RTSP -> {
                // RTSP mode - nothing to stop here, handled externally
                Log.d(TAG, "Motion wake stopped (RTSP mode)")
            }
            DetectionMode.CAMERA, DetectionMode.FACE -> {
                cameraMotionDetector?.stop()
                faceWakeDetector?.stopSearching()
            }
            DetectionMode.PROXIMITY, DetectionMode.LIGHT -> {
                sensorManager.unregisterListener(this)
            }
            DetectionMode.NONE -> {}
        }

        Log.i(TAG, "Motion wake stopped")
    }

    /**
     * Notify the manager that the screen has been dimmed
     * This enables motion detection to wake the screen
     */
    fun onScreenDimmed() {
        isScreenDimmed = true

        when (detectionMode) {
            DetectionMode.CAMERA, DetectionMode.FACE -> {
                cameraMotionDetector?.enable()
                // In face mode, immediately start a face search so someone standing
                // still looking at the screen when it dims gets detected right away
                // (without needing to move first to trigger motion detection)
                if (detectionMode == DetectionMode.FACE) {
                    faceWakeDetector?.onMotionDetected()
                    Log.i(TAG, "Face mode: auto-triggering face search on screen dim")
                }
            }
            DetectionMode.LIGHT -> {
                // Reset light baseline when screen dims
                baseLightLevel = -1f
            }
            else -> {}
        }

        Log.d(TAG, "Screen dimmed - motion wake armed (mode: $detectionMode)")
    }

    /**
     * Notify the manager that the screen has been woken
     * This disables motion detection until screen dims again
     */
    fun onScreenWoken() {
        isScreenDimmed = false

        if (detectionMode == DetectionMode.CAMERA || detectionMode == DetectionMode.FACE) {
            // Keep the camera detector ARMED while the screen is on (always-on camera) so
            // the active mode's own signal holds the screen awake by resetting the inactivity
            // timer: motion in CAMERA mode, a present face in FACE mode (via onFacePresent).
            // The wake callback resetTimer()s instead of waking when the screen is already on,
            // so this never causes spurious wakes — it just prevents the screensaver from
            // starting while the user is present, without requiring a screen touch.
            cameraMotionDetector?.enable()
            if (detectionMode == DetectionMode.FACE) {
                // (Re)start a face search so a still face is continuously confirmed and keeps
                // resetting the timer; presence reposts the cooldown so it stays alive.
                faceWakeDetector?.onMotionDetected()
            }
        }

        Log.d(TAG, "Screen woken - detection kept armed for presence keep-alive (mode: $detectionMode)")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isEnabled || !isScreenDimmed) return

        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> handleProximityEvent(event)
            Sensor.TYPE_LIGHT -> handleLightEvent(event)
        }
    }

    private fun handleProximityEvent(event: SensorEvent) {
        val distance = event.values[0]

        // Proximity sensor typically returns:
        // - 0 (or small value) when something is near
        // - maxRange when nothing is near
        val isNear = distance < proximityMaxRange

        // Detect transition from far to near (something approaching)
        if (isNear && (lastProximityValue < 0 || lastProximityValue >= proximityMaxRange)) {
            triggerWake("proximity")
        }

        lastProximityValue = distance
    }

    private fun handleLightEvent(event: SensorEvent) {
        val lightLevel = event.values[0]

        // First reading - establish baseline
        if (baseLightLevel < 0) {
            baseLightLevel = lightLevel
            return
        }

        // Detect significant light change (someone walking by or approaching)
        val lightChange = kotlin.math.abs(lightLevel - baseLightLevel)
        if (lightChange > lightChangeThreshold) {
            triggerWake("light change ($lightChange lux)")
            // Reset baseline after wake
            baseLightLevel = lightLevel
        }
    }

    private fun triggerWake(reason: String) {
        val now = System.currentTimeMillis()

        // Debounce to prevent rapid triggers
        if (now - lastWakeTime < WAKE_DEBOUNCE_MS) {
            return
        }

        lastWakeTime = now
        Log.i(TAG, "Motion detected ($reason) - waking screen")
        onMotionDetected()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for our use case
    }

    /**
     * Check if motion wake is available on this device
     */
    fun isAvailable(): Boolean = detectionMode != DetectionMode.NONE || rtspMotionEnabled

    /**
     * Get the current detection mode
     */
    fun getDetectionMode(): DetectionMode = detectionMode

    /**
     * Set the camera motion detection threshold (1-20)
     * Only has effect when using camera detection mode
     * Higher value = less sensitive (more motion required to trigger)
     */
    fun setCameraThreshold(value: Int) {
        cameraMotionDetector?.setThreshold(value)
    }

    /**
     * Set the camera motion detection threshold with decimal support (0.5 - 20.0)
     * Only has effect when using camera detection mode
     */
    fun setCameraThreshold(value: Double) {
        cameraMotionDetector?.setThreshold(value)
    }

    /**
     * Enable RTSP motion detection mode.
     *
     * When enabled, this manager stops camera detection to release the camera for RTSP
     * and falls back to sensor-based detection (proximity or light sensor).
     *
     * Note: RootEncoder's Camera1 API doesn't expose frames for analysis, so we can't
     * do motion detection from the RTSP stream. Instead, we use sensor-based fallback.
     *
     * This prevents camera resource conflicts between the local CameraMotionDetector
     * and the RtspCameraServer.
     */
    fun enableRtspMotionMode() {
        if (rtspMotionEnabled) return

        rtspMotionEnabled = true
        val previousMode = detectionMode
        val wasEnabled = isEnabled

        // Stop and destroy camera detection to release camera for RTSP
        if (detectionMode == DetectionMode.CAMERA || detectionMode == DetectionMode.FACE) {
            cameraMotionDetector?.stop()
            cameraMotionDetector?.destroy()
            cameraMotionDetector = null
            faceWakeDetector?.destroy()
            faceWakeDetector = null
        }

        // When RTSP is active, it handles motion detection via Camera2MotionVideoSrc.
        // We disable MotionWakeManager entirely - no sensor fallback needed.
        // This prevents false triggers from light sensor when screen turns off.
        stop()
        detectionMode = DetectionMode.NONE
        Log.i(TAG, "RTSP motion mode enabled - MotionWakeManager disabled (RTSP handles motion)")
    }

    /**
     * Disable RTSP motion detection mode.
     *
     * When disabled, this manager will re-evaluate available detection methods
     * and may restart camera-based detection if available.
     */
    fun disableRtspMotionMode() {
        if (!rtspMotionEnabled) return

        rtspMotionEnabled = false
        Log.i(TAG, "RTSP motion mode disabled - waiting for RTSP server restart before re-enabling")

        // IMPORTANT: Delay before re-determining detection mode
        // The RTSP server needs time to fully restart and reclaim the camera.
        // On slower devices (Samsung tablet, ONN stick), jumping in too quickly
        // causes camera resource conflicts between RTSP and MotionWakeManager.
        // This delay is longer than the RTSP restart delay (1.5s) to ensure it completes first.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Log.i(TAG, "Re-determining detection mode after RTSP handoff delay")

            // Re-determine the best detection mode (recreates CameraMotionDetector)
            determineDetectionMode()

            // Always restart - enableRtspMotionMode() called stop() which set isEnabled=false,
            // so we must call start() to bind CameraX and resume frame delivery.
            start()

            // If screen is already dimmed, arm detection immediately
            if (isScreenDimmed) {
                cameraMotionDetector?.enable()
                Log.d(TAG, "Screen is dimmed - arming motion detection immediately after RTSP handoff")
            }
        }, 2000)  // 2s delay - longer than RTSP restart to ensure it completes first
    }

    /**
     * Check if RTSP motion mode is active
     */
    fun isRtspMotionMode(): Boolean = rtspMotionEnabled

    /**
     * Get the current motion score from camera detection for graph visualization.
     * Returns 0.0 if camera detection is not active or no score available yet.
     */
    fun getCurrentMotionScore(): Double {
        val score = if (detectionMode == DetectionMode.CAMERA || detectionMode == DetectionMode.FACE) {
            val cameraScore = cameraMotionDetector?.getCurrentMotionScore() ?: 0.0
            Log.d(TAG, "getCurrentMotionScore: mode=$detectionMode, cameraScore=$cameraScore, detector=${if (cameraMotionDetector != null) "exists" else "null"}")
            cameraScore
        } else {
            Log.d(TAG, "getCurrentMotionScore: mode=$detectionMode (not CAMERA/FACE)")
            0.0
        }
        Log.d(TAG, "📊 getCurrentMotionScore: mode=$detectionMode, score=$score, detector=${cameraMotionDetector != null}")
        return score
    }

    /**
     * Enable graph mode - camera scores motion for visualization without triggering wake events.
     * This allows the motion graph to work even when the screen isn't dimmed.
     */
    fun enableGraphMode() {
        if (detectionMode == DetectionMode.CAMERA || detectionMode == DetectionMode.FACE) {
            cameraMotionDetector?.enableGraphMode()
        }
    }

    /**
     * Disable graph mode
     */
    fun disableGraphMode() {
        if (detectionMode == DetectionMode.CAMERA || detectionMode == DetectionMode.FACE) {
            cameraMotionDetector?.disableGraphMode()
        }
    }

    // Track if we created a temporary face detector for settings dialog testing
    private var tempFaceDetector = false

    /**
     * Set face detection test callback for settings dialog indicator.
     * When non-null, enables face test mode (frames analyzed regardless of search state).
     * When null, disables test mode and clears callback.
     *
     * If no FaceWakeDetector exists (user is in camera mode previewing face mode),
     * creates one temporarily and attaches it to the CameraMotionDetector.
     */
    fun setFaceTestCallback(callback: ((Int) -> Unit)?) {
        if (callback != null) {
            var detector = faceWakeDetector
            // Create temporary face detector if none exists but camera is available
            if (detector == null && cameraMotionDetector != null) {
                detector = FaceWakeDetector(onFaceDetected = { /* no-op in test mode */ }).apply {
                    minFaceSizePercent = faceDistancePercent
                }
                faceWakeDetector = detector
                cameraMotionDetector?.setFaceWakeDetector(detector)
                tempFaceDetector = true
                Log.d(TAG, "Created temporary FaceWakeDetector for settings dialog test (minFace=${faceDistancePercent})")
            }
            detector?.onFaceResult = callback
            detector?.enableTestMode()
        } else {
            faceWakeDetector?.disableTestMode()
            faceWakeDetector?.onFaceResult = null
            // Destroy temporary face detector
            if (tempFaceDetector) {
                faceWakeDetector?.destroy()
                faceWakeDetector = null
                cameraMotionDetector?.setFaceWakeDetector(null)
                tempFaceDetector = false
                Log.d(TAG, "Destroyed temporary FaceWakeDetector")
            }
        }
    }

    /**
     * Update face distance filter on the live face detector.
     * Used by settings dialog for real-time preview when user changes distance.
     */
    fun updateFaceDistance(percent: Float) {
        faceWakeDetector?.minFaceSizePercent = percent
        Log.d(TAG, "Updated face distance to ${(percent * 100).toInt()}%")
    }

    /**
     * Get diagnostic info for troubleshooting motion wake issues.
     */
    fun getDiagnostics(): String {
        val sb = StringBuilder()
        sb.appendLine("Detection Mode: $detectionMode")
        sb.appendLine("Enabled: ${if (isEnabled) "Yes" else "No"}")
        sb.appendLine("Screen Dimmed: ${if (isScreenDimmed) "Yes" else "No"}")
        sb.appendLine("RTSP Motion Mode: ${if (rtspMotionEnabled) "Yes" else "No"}")
        val cameraDiag = cameraMotionDetector?.getDiagnostics()
        if (cameraDiag != null) {
            sb.appendLine("Camera Detector:")
            sb.append(cameraDiag)
        }
        val faceDiag = faceWakeDetector != null
        if (faceDiag) {
            sb.appendLine("  Face Detector: Active")
        }
        return sb.toString().trimEnd()
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        stop()
        cameraMotionDetector?.destroy()
        cameraMotionDetector = null
        faceWakeDetector?.destroy()
        faceWakeDetector = null
        rtspMotionEnabled = false
        Log.i(TAG, "MotionWakeManager destroyed")
    }
}

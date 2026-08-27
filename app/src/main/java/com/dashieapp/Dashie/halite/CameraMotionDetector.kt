package com.dashieapp.Dashie.halite

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Detects motion using the front camera for waking the screen.
 *
 * Uses frame differencing to detect significant changes between frames,
 * indicating someone has walked by or approached the device.
 *
 * Similar to Fully Kiosk's "Visual Motion Detection" feature.
 */
class CameraMotionDetector(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onMotionDetected: () -> Unit,
    private val onCameraError: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "CameraMotionDetector"

        // Motion detection sensitivity (0-100, higher = more sensitive)
        private const val DEFAULT_SENSITIVITY = 50

        // Default motion threshold (as percentage of pixels that must change)
        // Lower value = more sensitive
        // Range: 1-20, default 5
        private const val DEFAULT_MOTION_THRESHOLD_PERCENT = 5.0

        // Minimum brightness change per pixel to count as "changed"
        // Increased from 10 to 25 to reduce false positives from camera sensor noise
        private const val PIXEL_CHANGE_THRESHOLD = 25

        // Debounce to prevent rapid triggers. Tighter than the RTSP source's
        // 2000ms on purpose — motion-wake is user-facing and time-critical
        // (tuned in bec985e0 for ~1.25s faster screen-on vs Fully Kiosk).
        // Camera2MotionVideoSource keeps looser values for stream monitoring.
        private const val MOTION_DEBOUNCE_MS = 1000L

        // Delay after arming before motion can trigger (allows camera to stabilize)
        // Camera auto-exposure stabilizes in ~200-500ms on most devices
        private const val ARM_DELAY_MS = 750L

        // Frame analysis rate (don't need every frame)
        private const val ANALYSIS_INTERVAL_MS = 250L

        // Downscale factor for analysis (reduces CPU usage)
        private const val DOWNSCALE_FACTOR = 8

        // Max errors before auto-disabling
        private const val MAX_ERRORS = 5

        // Time window for counting errors (reset if no errors for this long)
        private const val ERROR_WINDOW_MS = 30000L  // 30 seconds

        // Frame validation timeout - if no frames received within this time, camera is broken
        // Built-in cameras: ~100-200ms, USB cameras: 3-5 seconds, 6s for safety margin
        private const val FRAME_VALIDATION_TIMEOUT_MS = 6000L  // 6 seconds (for USB cameras)

        // Watchdog interval - checks camera is still delivering frames
        private const val WATCHDOG_INTERVAL_MS = 30000L  // 30 seconds

        // Auto-recovery delay after camera failure
        private const val RECOVERY_DELAY_MS = 60000L  // 60 seconds

        // Max recovery attempts before truly giving up
        private const val MAX_RECOVERY_ATTEMPTS = 5

        // Headless Preview surface resolution. Doesn't need to match analysis;
        // YUV_420_888 is universally supported by Camera2 producers.
        private const val PREVIEW_WIDTH = 320
        private const val PREVIEW_HEIGHT = 240
    }

    /**
     * Custom LifecycleOwner that stays permanently in RESUMED state.
     * CameraX binds to this instead of Activity/ProcessLifecycleOwner, so the camera
     * keeps running even when lockNow() pauses/stops the Activity. We manage cleanup
     * explicitly in destroy(). This is how apps like Fully Kiosk keep camera detection
     * alive when the screen is hardware-off.
     */
    private class AlwaysOnLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        init { registry.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle = registry
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    private val alwaysOnLifecycle = AlwaysOnLifecycleOwner()

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var camera: Camera? = null
    // Headless Preview use case — bound alongside ImageAnalysis so the Camera HAL
    // treats the session as a real streaming session rather than a transient
    // analytical one. Some OEMs (Pixel/Android 16) starve ImageAnalysis-only
    // capture sessions after a few seconds of activity-backgrounded operation;
    // adding an active Preview output surface keeps the HAL pipeline alive.
    // Surface is provided by an ImageReader whose images we immediately discard.
    private var preview: Preview? = null
    private var previewImageReader: ImageReader? = null
    private var availabilityCallback: CameraManager.AvailabilityCallback? = null
    private var isRunning = false
    private var isEnabled = false
    private var isGraphMode = false  // Enable scoring for graph without triggering wake
    private var hasFailed = false
    private var errorCount = 0
    private var firstErrorTime = 0L
    private var hasReceivedFrame = false
    private var frameValidationHandler: android.os.Handler? = null
    private var frameValidationRunnable: Runnable? = null

    // Watchdog: tracks frame delivery to detect silent camera death
    private var watchdogHandler: android.os.Handler? = null
    private var watchdogRunnable: Runnable? = null
    @Volatile private var lastFrameTimestamp = 0L

    // Recovery: auto-retry after transient failures
    private var recoveryHandler: android.os.Handler? = null
    private var recoveryRunnable: Runnable? = null
    private var recoveryAttempts = 0

    // Previous frame data for comparison
    private var previousFrameData: ByteArray? = null
    private var lastAnalysisTime = 0L
    private var lastMotionTime = 0L
    private var armTime = 0L  // When motion detection was armed

    // Sensitivity (0-100)
    private var sensitivity = DEFAULT_SENSITIVITY

    // Motion threshold percentage (1-20, higher = less sensitive)
    private var motionThresholdPercent = DEFAULT_MOTION_THRESHOLD_PERCENT

    // Last calculated motion score (for graph visualization)
    @Volatile
    private var lastMotionScore = 0.0

    // Optional face wake detector (set when in face mode)
    private var faceWakeDetector: FaceWakeDetector? = null

    // Rotation for ML Kit face detection (computed once when camera binds)
    // Landscape-natural tablets (Fire tablets) need +90° compensation vs CameraX default
    private var faceDetectionRotation = 0

    /**
     * Get the most recent motion score for graph visualization.
     * Returns 0.0 if no motion has been calculated yet.
     */
    fun getCurrentMotionScore(): Double = lastMotionScore

    /**
     * Attach a FaceWakeDetector to receive camera frames for face analysis.
     * When attached and searching, each analyzed frame is also forwarded
     * to the face detector as a Bitmap.
     */
    fun setFaceWakeDetector(detector: FaceWakeDetector?) {
        faceWakeDetector = detector
        Log.d(TAG, "FaceWakeDetector ${if (detector != null) "attached" else "detached"}")
    }

    /**
     * Start the camera for motion detection
     */
    fun start() {
        if (isRunning) return
        if (hasFailed) {
            Log.w(TAG, "Camera previously failed, scheduling recovery attempt")
            scheduleRecovery()
            return
        }

        // Check if device has a camera before trying to start
        if (!hasCamera()) {
            Log.w(TAG, "No camera available on this device")
            hasFailed = true
            onCameraError?.invoke("No camera available")
            return
        }

        Log.i(TAG, "Starting camera motion detection (sensitivity: $sensitivity)")

        cameraExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraForAnalysis()
                isRunning = true
                hasReceivedFrame = false
                startFrameValidation()
                startWatchdog()
                Log.i(TAG, "Camera motion detection started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera: ${e.message}")
                handleCameraError("Failed to start: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Stop the camera
     */
    fun stop() {
        if (!isRunning) return

        Log.i(TAG, "Stopping camera motion detection")

        stopFrameValidation()
        stopWatchdog()
        unregisterAvailabilityCallback()

        // Stop observing camera state to prevent error callbacks after stop
        camera = null

        // Unbind all use cases first
        cameraProvider?.unbindAll()

        // Shutdown the camera provider to fully release camera resources
        // This prevents CameraX from auto-reopening the camera
        try {
            cameraProvider?.let { provider ->
                // Unbind everything to release resources
                provider.unbindAll()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during camera provider cleanup: ${e.message}")
        }

        releasePreviewSurface()

        cameraProvider = null
        cameraExecutor?.shutdown()
        cameraExecutor = null
        previousFrameData = null
        isRunning = false

        Log.i(TAG, "Camera motion detection stopped - camera released")
    }

    /**
     * Start frame validation timeout - if no frames received within timeout, camera is broken
     */
    private fun startFrameValidation() {
        frameValidationHandler = android.os.Handler(android.os.Looper.getMainLooper())
        frameValidationRunnable = Runnable {
            if (!hasReceivedFrame && isRunning) {
                Log.e(TAG, "No frames received within ${FRAME_VALIDATION_TIMEOUT_MS}ms - camera is broken, disabling immediately")
                // No frames = definitely broken, disable immediately without waiting for more errors
                hasFailed = true
                stop()
                onCameraError?.invoke("No frames received - camera broken")
            }
        }
        frameValidationHandler?.postDelayed(frameValidationRunnable!!, FRAME_VALIDATION_TIMEOUT_MS)
    }

    /**
     * Stop frame validation timeout
     */
    private fun stopFrameValidation() {
        frameValidationRunnable?.let { frameValidationHandler?.removeCallbacks(it) }
        frameValidationHandler = null
        frameValidationRunnable = null
    }

    /**
     * Start frame delivery watchdog - checks that CameraX is still delivering frames.
     * If no frames are received for WATCHDOG_INTERVAL_MS, attempts to restart the camera.
     */
    private fun startWatchdog() {
        stopWatchdog()
        lastFrameTimestamp = System.currentTimeMillis()
        watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var frameCountAtLastTick = frameCount  // For heartbeat delta calculation
        var heartbeatTickCounter = 0
        watchdogRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return

                val elapsed = System.currentTimeMillis() - lastFrameTimestamp
                if (elapsed > WATCHDOG_INTERVAL_MS) {
                    Log.w(TAG, "⚠️ WATCHDOG: No frames received for ${elapsed/1000}s - camera may be dead, attempting restart")
                    PersistentLog.warn(
                        "MOTION",
                        "Watchdog: no camera frames for ${elapsed/1000}s — attempting restart (likely OEM-killed capture)"
                    )
                    attemptRestart("watchdog timeout")
                } else {
                    Log.d(TAG, "🐕 Watchdog: camera alive (last frame ${elapsed/1000}s ago, enabled=$isEnabled, graphMode=$isGraphMode, frameCount=$frameCount)")
                    // Frame heartbeat: every 2 ticks (~60s) emit a positive signal so the
                    // log timeline shows when frames ARE arriving — turns the
                    // "0 watchdog timeouts" case into a "yes, N frames received" positive.
                    heartbeatTickCounter++
                    if (heartbeatTickCounter >= 2) {
                        val delta = frameCount - frameCountAtLastTick
                        PersistentLog.info(
                            "CAMERA",
                            "Frame heartbeat: $delta frames in last 60s (total=$frameCount, enabled=$isEnabled)"
                        )
                        frameCountAtLastTick = frameCount
                        heartbeatTickCounter = 0
                    }
                }
                watchdogHandler?.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
        watchdogHandler?.postDelayed(watchdogRunnable!!, WATCHDOG_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        watchdogRunnable?.let { watchdogHandler?.removeCallbacks(it) }
        watchdogHandler = null
        watchdogRunnable = null
    }

    /**
     * Attempt to restart the camera after a failure.
     * Unbinds and rebinds CameraX without destroying the detector.
     */
    private fun attemptRestart(reason: String) {
        Log.i(TAG, "Attempting camera restart (reason: $reason)")

        // Unbind current camera
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding during restart: ${e.message}")
        }
        camera = null
        previousFrameData = null
        hasReceivedFrame = false

        // Rebind
        try {
            bindCameraForAnalysis()
            startFrameValidation()
            Log.i(TAG, "Camera restart successful")
        } catch (e: Exception) {
            Log.e(TAG, "Camera restart failed: ${e.message}")
            handleCameraError("Restart failed: ${e.message}")
        }
    }

    /**
     * Schedule auto-recovery after a camera failure.
     * Resets the failed state and attempts to restart after a delay.
     */
    private fun scheduleRecovery() {
        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
            Log.e(TAG, "Max recovery attempts ($MAX_RECOVERY_ATTEMPTS) reached - giving up")
            return
        }

        cancelRecovery()
        recoveryHandler = android.os.Handler(android.os.Looper.getMainLooper())
        recoveryRunnable = Runnable {
            recoveryAttempts++
            Log.i(TAG, "Auto-recovery attempt $recoveryAttempts/$MAX_RECOVERY_ATTEMPTS")
            hasFailed = false
            errorCount = 0
            firstErrorTime = 0L
            hasReceivedFrame = false
            start()
        }
        val delay = RECOVERY_DELAY_MS * recoveryAttempts  // Exponential backoff
        Log.i(TAG, "Scheduling recovery attempt ${recoveryAttempts + 1} in ${delay/1000}s")
        recoveryHandler?.postDelayed(recoveryRunnable!!, delay)
    }

    private fun cancelRecovery() {
        recoveryRunnable?.let { recoveryHandler?.removeCallbacks(it) }
        recoveryHandler = null
        recoveryRunnable = null
    }

    /**
     * Enable motion detection (arm it)
     */
    fun enable() {
        isEnabled = true
        armTime = System.currentTimeMillis()  // Record when we armed
        previousFrameData = null // Reset baseline when enabling
        Log.d(TAG, "Motion detection enabled (armed, ${ARM_DELAY_MS}ms delay)")
    }

    /**
     * Disable motion detection (disarm it)
     */
    fun disable() {
        isEnabled = false
        Log.d(TAG, "Motion detection disabled (disarmed)")
    }

    /**
     * Enable graph mode - camera scores motion for visualization but doesn't trigger wake events
     */
    fun enableGraphMode() {
        isGraphMode = true
        previousFrameData = null // Reset baseline
        Log.d(TAG, "Graph mode enabled - scoring without wake triggers")
    }

    /**
     * Disable graph mode
     */
    fun disableGraphMode() {
        isGraphMode = false
        Log.d(TAG, "Graph mode disabled")
    }

    /**
     * Set sensitivity (0-100)
     */
    fun setSensitivity(value: Int) {
        sensitivity = value.coerceIn(0, 100)
        Log.d(TAG, "Sensitivity set to $sensitivity")
    }

    /**
     * Set motion threshold percentage (1-20)
     * Higher value = less sensitive (more motion required to trigger)
     */
    fun setThreshold(value: Int) {
        motionThresholdPercent = value.coerceIn(1, 20).toDouble()
        Log.i(TAG, "Motion threshold set to $motionThresholdPercent%")
    }

    /**
     * Set motion threshold percentage with decimal support (0.2 - 20.0)
     * Higher value = less sensitive (more motion required to trigger)
     */
    fun setThreshold(value: Double) {
        motionThresholdPercent = value.coerceIn(0.2, 20.0)
        Log.i(TAG, "Motion threshold set to $motionThresholdPercent%")
    }

    /**
     * Whether to bind a headless Preview use case alongside ImageAnalysis.
     *
     * Pixel/Android 16 starves ImageAnalysis-only capture sessions after a few seconds of
     * activity-backgrounded operation, so an active Preview output keeps the HAL pipeline
     * alive there (added in 340aba4d). On other OEMs (e.g. Samsung/Unisoc) the extra
     * Preview surface competes for ISP buffers and starves the *analysis* stream instead —
     * which intermittently broke motion wake on the Samsung after 340aba4d. So default to
     * the long-reliable ImageAnalysis-only path and only add Preview on the device class
     * known to need it (Google Pixel on Android 14+). The service-owned Camera2 capture
     * refactor will eventually replace this whole CameraX path.
     */
    private fun shouldBindPreviewUseCase(): Boolean {
        return Build.MANUFACTURER.equals("Google", ignoreCase = true) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }

    private fun bindCameraForAnalysis() {
        val cameraProvider = cameraProvider ?: return
        val executor = cameraExecutor ?: return

        // Image analysis use case
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(executor) { imageProxy ->
            try {
                lastFrameTimestamp = System.currentTimeMillis()
                analyzeFrame(imageProxy)

                // Forward frame to face detector when searching or in graph mode (for test indicator)
                faceWakeDetector?.let { detector ->
                    val shouldForward = detector.isSearching() || isGraphMode
                    if (shouldForward) {
                        try {
                            val bitmap = imageProxy.toBitmap()
                            detector.analyzeFrame(bitmap, faceDetectionRotation)
                        } catch (t: Throwable) {
                            // CRITICAL: Catch Throwable (not just Exception) to handle OutOfMemoryError.
                            // OOM from toBitmap() would otherwise kill the executor thread and permanently
                            // stop CameraX frame delivery.
                            Log.e(TAG, "Failed to forward frame to face detector: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                }
            } catch (t: Throwable) {
                // Safety net: any error in frame processing must not kill the executor thread
                Log.e(TAG, "Error in frame analyzer: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                // CRITICAL: Always close imageProxy. If not closed, CameraX stops delivering frames.
                imageProxy.close()
            }
        }

        // Headless Preview use case — only bound where it's actually needed (see
        // shouldBindPreviewUseCase). On Pixel/Android 16 it keeps the HAL pipeline alive;
        // on other OEMs it starves the analysis stream, so they stay ImageAnalysis-only.
        var previewUseCase: Preview? = null
        if (shouldBindPreviewUseCase()) {
            // Build a draining ImageReader and feed its Surface to Preview's SurfaceProvider.
            // The ImageReader's OnImageAvailableListener immediately closes each image so
            // the buffer queue keeps cycling and the HAL keeps producing preview frames.
            val previewReader = ImageReader.newInstance(
                PREVIEW_WIDTH, PREVIEW_HEIGHT, ImageFormat.YUV_420_888, 2
            )
            previewReader.setOnImageAvailableListener({ reader ->
                try {
                    reader.acquireLatestImage()?.close()
                } catch (e: Exception) {
                    // Buffer queue is best-effort; ignore drain errors
                }
            }, Handler(Looper.getMainLooper()))
            previewImageReader = previewReader

            previewUseCase = Preview.Builder().build().apply {
                setSurfaceProvider { request ->
                    request.provideSurface(
                        previewReader.surface,
                        ContextCompat.getMainExecutor(context)
                    ) { _ ->
                        // CameraX has released the surface — ImageReader gets released in destroy()
                    }
                }
            }
            preview = previewUseCase
        }

        // Unbind any existing use cases
        cameraProvider.unbindAll()

        // Try front camera first (most relevant for motion/face detection facing the user),
        // then fall back to any available camera
        val cameraSelectors = listOf(
            "front" to CameraSelector.DEFAULT_FRONT_CAMERA,
            "back" to CameraSelector.DEFAULT_BACK_CAMERA
        )

        for ((name, selector) in cameraSelectors) {
            try {
                // Use AlwaysOnLifecycleOwner so CameraX stays bound even when lockNow()
                // pauses/stops the Activity (hardware screen off). Activity and even
                // ProcessLifecycleOwner get stopped by lockNow() on some devices (Fire tablets),
                // killing camera frame delivery. AlwaysOnLifecycleOwner stays RESUMED permanently.
                // Bind Preview alongside ImageAnalysis only where needed (Pixel/Android 16);
                // ImageAnalysis-only elsewhere — see shouldBindPreviewUseCase().
                camera = if (previewUseCase != null) {
                    cameraProvider.bindToLifecycle(
                        alwaysOnLifecycle,
                        selector,
                        imageAnalysis,
                        previewUseCase
                    )
                } else {
                    cameraProvider.bindToLifecycle(
                        alwaysOnLifecycle,
                        selector,
                        imageAnalysis
                    )
                }

                // Observe camera state for errors
                observeCameraState()

                // Register AvailabilityCallback to detect platform-side camera revocation
                // (independent of CameraX — surfaces evictions even when CameraX swallows them).
                registerAvailabilityCallback()

                // Compute face detection rotation (with landscape-natural compensation)
                computeFaceDetectionRotation()

                val useCaseLabel = if (previewUseCase != null) "Preview+ImageAnalysis" else "ImageAnalysis"
                PersistentLog.info(
                    "CAMERA",
                    "Motion-detection camera bound — $name, useCases=$useCaseLabel"
                )
                logCameraPolicyState("post-bind")
                Log.i(TAG, "Successfully bound $name camera for motion detection")
                return  // Success! Exit the function
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bind $name camera: ${e.message}")
                PersistentLog.warn(
                    "CAMERA",
                    "bindToLifecycle($name) failed: ${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }

        // If we get here, no camera worked
        Log.e(TAG, "No camera available for motion detection")
        handleCameraError("No camera available")
    }

    /**
     * Snapshot the camera-policy state to PersistentLog.
     *
     * Called at every bind attempt and on every CameraX cameraState.error or
     * AvailabilityCallback.UNAVAILABLE event. Tells us *which* policy layer is
     * gating the camera when something fails:
     *   - DevicePolicyManager.getCameraDisabled(null) — true when ANY Device
     *     Admin (including third-party MDM / family management apps) has called
     *     setCameraDisabled(true). Passing null checks ALL admins, not just ours.
     *   - DevicePolicyManager.activeAdmins — full list of registered Device
     *     Admin components, so we can name the third-party app if it's not Dashie.
     *
     * Note: SensorPrivacyManager.isSensorPrivacyEnabled() (the "Camera access"
     * Quick Settings toggle state) requires OBSERVE_SENSOR_PRIVACY, a system-
     * signature permission unavailable to regular apps. Inference path:
     * if cameraState.error == ERROR_CAMERA_DISABLED AND dpmCameraDisabled=false
     * AND activeAdmins contains only Dashie → it's the sensor-privacy toggle.
     */
    private fun logCameraPolicyState(trigger: String) {
        val tags = mutableListOf<String>()
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val cameraDisabled = dpm?.getCameraDisabled(null)  // null = check ALL admins
            tags.add("dpmCameraDisabled(any-admin)=$cameraDisabled")
            val admins = dpm?.activeAdmins?.joinToString(",") { it.flattenToShortString() }
                ?: "(none)"
            tags.add("activeAdmins=[$admins]")
        } catch (e: Exception) {
            tags.add("dpm=err(${e.javaClass.simpleName})")
        }
        PersistentLog.info("CAMERA", "Policy state ($trigger): ${tags.joinToString("  ")}")
    }

    /**
     * Register a CameraManager.AvailabilityCallback so we get an explicit signal
     * when the platform marks any camera unavailable — independent of whatever
     * CameraX surfaces (or doesn't) via cameraState. This is the diagnostic that
     * tells us whether "no frames" is a platform-side eviction or a CameraX-side
     * silent wedge. Mirrors to PersistentLog so Peter's next diagnostics submission
     * captures the timeline.
     */
    private fun registerAvailabilityCallback() {
        if (availabilityCallback != null) return  // Already registered
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val cb = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                PersistentLog.info("CAMERA", "AvailabilityCallback: camera $cameraId AVAILABLE")
            }
            override fun onCameraUnavailable(cameraId: String) {
                PersistentLog.warn("CAMERA", "AvailabilityCallback: camera $cameraId UNAVAILABLE")
                logCameraPolicyState("availability=UNAVAILABLE id=$cameraId")
            }
        }
        try {
            cm.registerAvailabilityCallback(cb, Handler(Looper.getMainLooper()))
            availabilityCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register AvailabilityCallback: ${e.message}")
        }
    }

    private fun unregisterAvailabilityCallback() {
        val cb = availabilityCallback ?: return
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            cm?.unregisterAvailabilityCallback(cb)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister AvailabilityCallback: ${e.message}")
        }
        availabilityCallback = null
    }

    private fun releasePreviewSurface() {
        try { previewImageReader?.close() } catch (e: Exception) {
            Log.w(TAG, "Failed to close preview ImageReader: ${e.message}")
        }
        previewImageReader = null
        preview = null
    }

    /**
     * Observe camera state and handle errors
     */
    private fun observeCameraState() {
        camera?.cameraInfo?.cameraState?.observe(alwaysOnLifecycle) { state ->
            when (state.type) {
                CameraState.Type.OPEN -> {
                    Log.d(TAG, "Camera opened successfully")
                    // Don't reset error count on success - we want to track total errors
                }
                CameraState.Type.OPENING -> {
                    Log.d(TAG, "Camera opening...")
                }
                CameraState.Type.CLOSED -> {
                    Log.d(TAG, "Camera closed")
                    // Closed without an error attached is suspicious during operation
                    // (CameraX should keep it open under AlwaysOnLifecycleOwner). Log it
                    // so we see if Pixel/Android 16 is silently flipping us to CLOSED.
                    if (state.error == null && isRunning) {
                        PersistentLog.warn("CAMERA", "CameraState=CLOSED with no error (unexpected while running)")
                    }
                }
                CameraState.Type.CLOSING -> {
                    Log.d(TAG, "Camera closing...")
                }
                CameraState.Type.PENDING_OPEN -> {
                    Log.d(TAG, "Camera pending open...")
                    if (isRunning) {
                        PersistentLog.info("CAMERA", "CameraState=PENDING_OPEN (waiting for camera to become available)")
                    }
                }
            }

            // Handle camera errors
            state.error?.let { error ->
                val errorCode = error.code
                val errorMessage = when (errorCode) {
                    CameraState.ERROR_CAMERA_IN_USE -> "Camera in use by another app"
                    CameraState.ERROR_CAMERA_FATAL_ERROR -> "Fatal camera error"
                    CameraState.ERROR_CAMERA_DISABLED -> "Camera disabled"
                    CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "Do not disturb mode"
                    CameraState.ERROR_MAX_CAMERAS_IN_USE -> "Max cameras in use"
                    CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> "Recoverable error"
                    CameraState.ERROR_STREAM_CONFIG -> "Stream config error"
                    else -> "Unknown error (code: $errorCode)"
                }
                Log.e(TAG, "Camera error: $errorMessage")
                PersistentLog.warn(
                    "CAMERA",
                    "CameraX cameraState.error: $errorMessage (code=$errorCode, type=${state.type})"
                )
                logCameraPolicyState("cameraState.error code=$errorCode")
                handleCameraError(errorMessage)
            }
        }
    }

    /**
     * Compute the rotation needed for ML Kit face detection.
     * Standard Android/CameraX rotation formulas assume portrait-natural devices.
     * Landscape-natural tablets (Fire tablets) need +90° compensation.
     */
    private fun computeFaceDetectionRotation() {
        val sensorOrientation = camera?.cameraInfo?.sensorRotationDegrees ?: return
        val display = (context as? android.app.Activity)?.windowManager?.defaultDisplay
        val displayRotation = display?.rotation ?: Surface.ROTATION_0
        val deviceDegrees = when (displayRotation) {
            Surface.ROTATION_0 -> 0; Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
        }
        val metrics = android.util.DisplayMetrics()
        display?.getRealMetrics(metrics)
        val isLandscapeNatural = metrics.widthPixels > metrics.heightPixels && displayRotation == Surface.ROTATION_0
        val landscapeCompensation = if (isLandscapeNatural) 90 else 0
        faceDetectionRotation = (sensorOrientation + deviceDegrees + landscapeCompensation) % 360
        Log.i(TAG, "Face detection rotation: $faceDetectionRotation° (sensor=$sensorOrientation°, device=$deviceDegrees°, landscapeNatural=$isLandscapeNatural)")
    }

    /**
     * Handle camera errors - track errors and disable if too many occur in time window
     */
    private fun handleCameraError(message: String) {
        val now = System.currentTimeMillis()

        // Reset error count if window has passed
        if (now - firstErrorTime > ERROR_WINDOW_MS) {
            errorCount = 0
            firstErrorTime = now
        }

        errorCount++

        // Set first error time if this is the first error
        if (errorCount == 1) {
            firstErrorTime = now
        }

        Log.e(TAG, "Camera error #$errorCount in window: $message")

        // Disable after too many errors in the time window
        if (errorCount >= MAX_ERRORS) {
            Log.e(TAG, "Too many camera errors ($errorCount in ${(now - firstErrorTime)/1000}s), disabling motion detection")
            hasFailed = true
            stop()
            onCameraError?.invoke(message)
        }
    }

    private var frameCount = 0

    private fun analyzeFrame(imageProxy: ImageProxy) {
        frameCount++

        // Mark that we received at least one frame (validates camera is working)
        if (!hasReceivedFrame) {
            hasReceivedFrame = true
            stopFrameValidation()
            Log.d(TAG, "First frame received - camera is working")
        }

        // Skip if not enabled (unless graph mode is active for visualization)
        if (!isEnabled && !isGraphMode) return

        // Rate limit analysis
        val now = System.currentTimeMillis()
        if (now - lastAnalysisTime < ANALYSIS_INTERVAL_MS) {
            return
        }
        lastAnalysisTime = now

        // Skip if still in arming delay (prevents false triggers right after screen dims)
        if (now - armTime < ARM_DELAY_MS) {
            return
        }

        // Debug: Log every 120th analyzed frame (once per minute at 500ms interval)
        if (frameCount % 120 == 0) {
            Log.d(TAG, "Analyzing frame #$frameCount (enabled=$isEnabled)")
        }

        // Get luminance (Y plane) data
        val yBuffer = imageProxy.planes[0].buffer
        val width = imageProxy.width
        val height = imageProxy.height

        // Downsample for efficiency
        val sampledData = sampleFrame(yBuffer, width, height)

        // Compare with previous frame
        val previousData = previousFrameData
        if (previousData != null && previousData.size == sampledData.size) {
            val motionScore = calculateMotionScore(previousData, sampledData)

            // Store for graph visualization
            lastMotionScore = motionScore

            // Adjust threshold based on sensitivity
            // Higher sensitivity = lower threshold
            val threshold = motionThresholdPercent * (100 - sensitivity) / 50.0

            // Debug: Log motion score every 12th frame (~every 3 seconds)
            if (frameCount % 12 == 0) {
                // Frame fingerprint: sum of first 100 bytes to verify data is changing
                val fingerprint = sampledData.take(100).sumOf { (it.toInt() and 0xFF) }
                Log.d(TAG, "📊 Motion score: %.2f%% (threshold: %.2f%%, fp=$fingerprint, size=${width}x${height})".format(motionScore, threshold))
            }

            if (motionScore > threshold) {
                // Only trigger wake events if detector is armed (not just graph mode)
                if (isEnabled) {
                    // Check debounce
                    if (now - lastMotionTime > MOTION_DEBOUNCE_MS) {
                        lastMotionTime = now
                        Log.i(TAG, "Motion detected! Score: %.2f%% (threshold: %.2f%%)".format(motionScore, threshold))
                        PersistentLog.info(
                            "MOTION",
                            "Motion detected — score=%.2f%% threshold=%.2f%%".format(motionScore, threshold)
                        )

                        // Trigger callback on main thread
                        ContextCompat.getMainExecutor(context).execute {
                            onMotionDetected()
                        }
                    }
                } else if (isGraphMode) {
                    // Graph mode - just log, don't trigger wake
                    if (frameCount % 12 == 0) {
                        Log.d(TAG, "📊 Motion in graph mode: %.2f%% (no wake trigger)".format(motionScore))
                    }
                }
            }
        }

        // Store current frame for next comparison
        previousFrameData = sampledData
    }

    /**
     * Downsample frame data for efficient comparison
     */
    private fun sampleFrame(buffer: ByteBuffer, width: Int, height: Int): ByteArray {
        val sampledWidth = width / DOWNSCALE_FACTOR
        val sampledHeight = height / DOWNSCALE_FACTOR
        val sampledData = ByteArray(sampledWidth * sampledHeight)

        // Rewind buffer to start reading from the beginning
        buffer.rewind()

        for (y in 0 until sampledHeight) {
            for (x in 0 until sampledWidth) {
                val srcX = x * DOWNSCALE_FACTOR
                val srcY = y * DOWNSCALE_FACTOR
                val srcIndex = srcY * width + srcX

                if (srcIndex < buffer.limit()) {
                    sampledData[y * sampledWidth + x] = buffer.get(srcIndex)
                }
            }
        }

        return sampledData
    }

    /**
     * Calculate percentage of pixels that changed significantly
     */
    private fun calculateMotionScore(prev: ByteArray, curr: ByteArray): Double {
        var changedPixels = 0

        for (i in prev.indices) {
            val prevVal = prev[i].toInt() and 0xFF
            val currVal = curr[i].toInt() and 0xFF
            val diff = abs(prevVal - currVal)
            if (diff > PIXEL_CHANGE_THRESHOLD) {
                changedPixels++
            }
        }

        return (changedPixels.toDouble() / prev.size) * 100.0
    }

    /**
     * Check if device has a camera (quick synchronous check)
     */
    fun hasCamera(): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    /**
     * Check if camera permission is granted
     */
    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
               PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if camera can be used (has permission, has camera, and hasn't failed)
     */
    fun canUseCamera(): Boolean {
        return hasCamera() && hasCameraPermission() && !hasFailed
    }

    /**
     * Check if camera is available and not in failed state
     */
    fun isAvailable(): Boolean {
        if (hasFailed) return false
        return hasCamera()
    }

    /**
     * Check if camera has failed and should be disabled
     */
    fun hasFailed(): Boolean = hasFailed

    /**
     * Reset failed state to allow retry
     */
    fun resetFailedState() {
        hasFailed = false
        errorCount = 0
        firstErrorTime = 0L
        hasReceivedFrame = false
        Log.i(TAG, "Camera failed state reset - can retry")
    }

    /**
     * Get diagnostic info about camera state for troubleshooting.
     */
    fun getDiagnostics(): String {
        val sb = StringBuilder()
        sb.appendLine("  Camera Running: ${if (isRunning) "Yes" else "No"}")
        sb.appendLine("  Camera Failed: ${if (hasFailed) "Yes" else "No"}")
        sb.appendLine("  Frames Received: ${if (hasReceivedFrame) "Yes" else "No"}")
        sb.appendLine("  Frame Count: $frameCount")
        sb.appendLine("  Enabled (armed): ${if (isEnabled) "Yes" else "No"}")
        sb.appendLine("  Graph Mode: ${if (isGraphMode) "Yes" else "No"}")
        val lastFrame = lastFrameTimestamp
        if (lastFrame > 0) {
            val elapsed = (System.currentTimeMillis() - lastFrame) / 1000
            sb.appendLine("  Last Frame: ${elapsed}s ago")
        } else {
            sb.appendLine("  Last Frame: Never")
        }
        sb.appendLine("  Last Motion Score: ${"%.2f".format(lastMotionScore)}%")
        sb.appendLine("  Threshold: ${"%.1f".format(motionThresholdPercent)}%")
        sb.appendLine("  Sensitivity: $sensitivity")
        sb.appendLine("  Error Count: $errorCount")
        sb.appendLine("  Recovery Attempts: $recoveryAttempts/$MAX_RECOVERY_ATTEMPTS")
        sb.appendLine("  Lifecycle: AlwaysOn (survives lockNow)")
        return sb.toString().trimEnd()
    }

    /**
     * Clean up resources - fully releases the camera for other use
     */
    fun destroy() {
        Log.i(TAG, "Destroying CameraMotionDetector - releasing all camera resources")

        // Mark as not running first to stop any callbacks
        isRunning = false
        isEnabled = false

        stopFrameValidation()
        stopWatchdog()
        cancelRecovery()
        unregisterAvailabilityCallback()

        // Clear the camera reference to stop observer callbacks
        camera = null

        // Unbind all camera use cases
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding camera: ${e.message}")
        }

        releasePreviewSurface()

        // Clear the provider reference
        cameraProvider = null

        // Destroy custom lifecycle owner so CameraX fully cleans up
        alwaysOnLifecycle.destroy()

        // Shutdown executor
        cameraExecutor?.shutdown()
        cameraExecutor = null

        // Clear frame data
        previousFrameData = null

        Log.i(TAG, "CameraMotionDetector destroyed - camera fully released")
    }
}

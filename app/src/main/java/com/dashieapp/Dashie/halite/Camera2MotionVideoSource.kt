package com.dashieapp.Dashie.halite

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.core.content.ContextCompat
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.video.VideoSource
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Custom Camera2 VideoSource that provides frames to both:
 * 1. RootEncoder's SurfaceTexture for RTSP streaming
 * 2. An ImageReader for motion detection
 *
 * This allows simultaneous RTSP streaming AND motion detection from the same camera,
 * similar to how audio can be sent to multiple destinations.
 *
 * The Camera2 API supports multiple output surfaces in a single capture session,
 * which is guaranteed on all Android devices API 21+.
 */
class Camera2MotionVideoSource(
    private val context: Context
) : VideoSource() {

    companion object {
        private const val TAG = "Camera2MotionVideoSrc"

        // Motion detection at low resolution for efficiency
        private const val MOTION_WIDTH = 320
        private const val MOTION_HEIGHT = 240

        // Motion detection settings. Deliberately tuned differently from
        // CameraMotionDetector (motion-wake): this source feeds continuous RTSP
        // monitoring, which tolerates a slower re-trigger (2s debounce) and a
        // longer settle (1s arm) — see commits 1d893203 / 2682b239. Motion-wake
        // is user-facing and time-critical, so it runs tighter values (1s/750ms,
        // commit bec985e0). Don't "unify" these without re-testing both features.
        private const val PIXEL_CHANGE_THRESHOLD = 25
        private const val MOTION_DEBOUNCE_MS = 2000L
        private const val ARM_DELAY_MS = 1000L
        private const val ANALYSIS_INTERVAL_MS = 250L
        private const val DOWNSCALE_FACTOR = 4  // Downsample 320x240 to 80x60 for comparison

        // Watchdog settings - detect camera stalls (frames stop arriving)
        private const val WATCHDOG_INTERVAL_MS = 15_000L  // Check every 15 seconds
        private const val FRAME_TIMEOUT_MS = 30_000L     // Trigger restart if no frames for 30 seconds

        /**
         * Pre-flight camera check - validates camera can produce frames BEFORE starting RTSP.
         * This is a standalone check that opens the camera, waits for a frame, then closes it.
         * Use this to detect "phantom" cameras (HAL present but no physical camera).
         *
         * @param context Application context
         * @param useFrontCamera Whether to test front or back camera
         * @param timeoutMs Maximum time to wait for first frame
         * @return true if camera produced a frame, false otherwise
         */
        @JvmStatic
        fun preflightCameraCheck(context: Context, useFrontCamera: Boolean = true, timeoutMs: Long = 2000L): Boolean {
            Log.i(TAG, "🔍 Pre-flight camera check starting (timeout=${timeoutMs}ms)...")

            var cameraManager: CameraManager? = null
            var cameraDevice: CameraDevice? = null
            var captureSession: CameraCaptureSession? = null
            var imageReader: ImageReader? = null
            var handlerThread: HandlerThread? = null
            var handler: Handler? = null

            // Use AtomicBoolean for thread-safe access from callbacks
            val frameReceived = java.util.concurrent.atomic.AtomicBoolean(false)
            val errorOccurred = java.util.concurrent.atomic.AtomicBoolean(false)
            var errorMessage: String? = null

            try {
                cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

                // Find camera
                val targetFacing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT
                else CameraCharacteristics.LENS_FACING_BACK

                var cameraId: String? = null
                for (id in cameraManager.cameraIdList) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (facing == targetFacing) {
                        cameraId = id
                        break
                    }
                }

                if (cameraId == null) {
                    cameraId = cameraManager.cameraIdList.firstOrNull()
                }

                if (cameraId == null) {
                    Log.e(TAG, "Pre-flight: No camera found")
                    return false
                }

                Log.d(TAG, "Pre-flight: Using camera $cameraId")

                // Check permission
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Pre-flight: Camera permission not granted")
                    return false
                }

                // Create handler thread
                handlerThread = HandlerThread("CameraPreflightCheck").apply { start() }
                handler = Handler(handlerThread.looper)

                // Create ImageReader to receive frames
                imageReader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2)
                imageReader.setOnImageAvailableListener({ reader ->
                    try {
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            frameReceived.set(true)
                            Log.d(TAG, "Pre-flight: Frame received!")
                            image.close()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Pre-flight: Error acquiring image: ${e.message}")
                    }
                }, handler)

                // Open camera synchronously using a latch
                val openLatch = java.util.concurrent.CountDownLatch(1)

                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        Log.d(TAG, "Pre-flight: Camera opened")
                        openLatch.countDown()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        Log.w(TAG, "Pre-flight: Camera disconnected")
                        errorOccurred.set(true)
                        errorMessage = "Camera disconnected"
                        camera.close()
                        openLatch.countDown()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        val msg = when (error) {
                            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "Camera in use"
                            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Max cameras in use"
                            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Camera disabled"
                            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "Camera device error"
                            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "Camera service error"
                            else -> "Unknown error ($error)"
                        }
                        Log.e(TAG, "Pre-flight: Camera error: $msg")
                        errorOccurred.set(true)
                        errorMessage = msg
                        camera.close()
                        openLatch.countDown()
                    }
                }, handler)

                // Wait for camera to open
                if (!openLatch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    Log.e(TAG, "Pre-flight: Camera open timeout")
                    return false
                }

                if (errorOccurred.get() || cameraDevice == null) {
                    Log.e(TAG, "Pre-flight: Camera open failed: $errorMessage")
                    return false
                }

                // Create capture session
                val sessionLatch = java.util.concurrent.CountDownLatch(1)
                val surfaces = listOf(imageReader.surface)

                cameraDevice?.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        Log.d(TAG, "Pre-flight: Session configured")

                        // Start capture
                        try {
                            val requestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            requestBuilder?.addTarget(imageReader!!.surface)
                            requestBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            session.setRepeatingRequest(requestBuilder!!.build(), null, handler)
                            Log.d(TAG, "Pre-flight: Capture started")
                        } catch (e: Exception) {
                            Log.e(TAG, "Pre-flight: Failed to start capture: ${e.message}")
                            errorOccurred.set(true)
                            errorMessage = e.message
                        }
                        sessionLatch.countDown()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Pre-flight: Session configure failed")
                        errorOccurred.set(true)
                        errorMessage = "Session configure failed"
                        sessionLatch.countDown()
                    }
                }, handler)

                // Wait for session
                if (!sessionLatch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    Log.e(TAG, "Pre-flight: Session timeout")
                    return false
                }

                if (errorOccurred.get()) {
                    Log.e(TAG, "Pre-flight: Session failed: $errorMessage")
                    return false
                }

                // Wait for frame
                val startTime = System.currentTimeMillis()
                while (!frameReceived.get() && !errorOccurred.get() && System.currentTimeMillis() - startTime < timeoutMs) {
                    Thread.sleep(50)
                }

                val result = frameReceived.get()
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "🔍 Pre-flight camera check: ${if (result) "✅ PASS" else "❌ FAIL"} (${elapsed}ms)")
                return result

            } catch (e: Exception) {
                Log.e(TAG, "Pre-flight: Exception: ${e.message}", e)
                return false
            } finally {
                // Cleanup
                try { captureSession?.stopRepeating() } catch (e: Exception) { }
                try { captureSession?.close() } catch (e: Exception) { }
                try { cameraDevice?.close() } catch (e: Exception) { }
                try { imageReader?.close() } catch (e: Exception) { }
                handlerThread?.quitSafely()
            }
        }

        // Face result states (same values as FaceWakeDetector)
        const val FACE_RESULT_NONE = 0
        const val FACE_RESULT_DETECTED = 1
        const val FACE_RESULT_TOO_FAR = 2

        // Rate limit ML Kit face frame forwarding (YUV→Bitmap is expensive on weak devices)
        private const val FACE_FRAME_INTERVAL_MS = 600L

        // Only forward face frames when motion was detected within this window.
        // Matches FaceWakeDetector's cooldown (5s) + buffer for first-frame timing.
        private const val FACE_MOTION_WINDOW_MS = 6000L

        // How many capture results to check before declaring HW face broken
        private const val HW_FACE_VALIDATION_FRAMES = 10
    }

    // Camera2 components
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureRequest: CaptureRequest? = null

    // Handler for camera operations
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // Motion detection ImageReader
    private var motionImageReader: ImageReader? = null

    // Surfaces for capture session
    private var encoderSurface: Surface? = null  // From RootEncoder's SurfaceTexture

    // State
    private var running = false
    private var cameraId: String? = null
    private var useFrontCamera = true
    private var sensorOrientation = 0  // Camera sensor orientation in degrees
    private var isExternalCamera = false  // USB/UVC camera (LENS_FACING_EXTERNAL)

    // Motion detection state
    private var motionCallback: ((Boolean) -> Unit)? = null

    // Camera disconnect callback - called when camera disconnects or errors out
    private var cameraDisconnectCallback: ((String) -> Unit)? = null
    private var motionEnabled = false
    private var armTime = 0L
    private var lastMotionTime = 0L
    private var lastAnalysisTime = 0L
    private var previousFrameData: ByteArray? = null
    private var motionThresholdPercent = 5.0
    private var frameCount = 0
    // Phase-0 diagnostics: periodic PersistentLog heartbeat so RTSP camera liveness
    // is observable across a hardware screen-off (mirrors the wake camera's heartbeat).
    private var lastHeartbeatLogTime = 0L
    private var framesAtLastHeartbeat = 0

    // Latest captured JPEG for getCamshot API (on-demand capture)
    @Volatile
    private var latestJpegFrame: ByteArray? = null
    @Volatile
    private var jpegCaptureRequested = false  // Flag to request JPEG capture on next frame

    // Last calculated motion score (for graph visualization)
    @Volatile
    private var lastMotionScore = 0.0

    // Face detection frame callback (provides Bitmap + rotation for FaceWakeDetector)
    // Only used as ML Kit fallback when HW face detection is not available.
    private var faceFrameCallback: ((android.graphics.Bitmap, Int) -> Unit)? = null
    private var lastFaceFrameTime = 0L

    // Second face frame callback for HA sensor publishing (parallel to wake detector)
    private var haSensorFaceFrameCallback: ((android.graphics.Bitmap, Int) -> Unit)? = null
    private var lastHaSensorFaceFrameTime = 0L

    // Rotation for ML Kit face detection (computed once when camera opens)
    // Standard Android rotation formulas assume portrait-natural devices.
    // Landscape-natural tablets (Fire tablets, etc.) need +90° compensation.
    private var faceDetectionRotation = 0

    // ── Hardware (Camera2 ISP) face detection ──
    // Runs on the ISP at zero CPU cost. Available on most devices (SIMPLE or FULL mode).
    // Replaces ML Kit face detection when supported.
    private var hwFaceDetectSupported = false
    private var activeArrayWidth = 0  // SENSOR_INFO_ACTIVE_ARRAY_SIZE width for distance calc
    @Volatile var hwFaceMinSizePercent: Float = 0.15f  // Same default as FaceWakeDetector
    @Volatile private var hwFaceResultCallback: ((Int) -> Unit)? = null  // 3-state: NONE/DETECTED/TOO_FAR
    @Volatile private var hwFaceDetectedCallback: (() -> Unit)? = null   // Wake trigger
    @Volatile private var haSensorHwFaceResultCallback: ((Int) -> Unit)? = null  // HA sensor face results
    private var lastHwFaceResultTime = 0L
    // Broken HAL detection: some devices (e.g. onn stick) report HW face support
    // but return null/empty face data every frame. Detect via STATISTICS_FACE_DETECT_MODE.
    private var hwFaceValidationCount = 0
    private var hwFaceValidated = false

    // Flag to track when first frame is received (camera fully initialized)
    @Volatile
    private var firstFrameReceived = false

    // Watchdog: Track last frame time to detect camera stalls
    @Volatile
    private var lastFrameTime = 0L
    private var watchdogHandler: Handler? = null
    private var watchdogRunnable: Runnable? = null

    /**
     * Get the most recent motion score for graph visualization.
     * Returns 0.0 if motion detection is not active or no score available yet.
     */
    fun getCurrentMotionScore(): Double = lastMotionScore

    // ========== VideoSource Implementation ==========

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean {
        Log.i(TAG, "Creating Camera2MotionVideoSource: ${width}x${height}@${fps}fps, rotation=$rotation")

        this.width = width
        this.height = height
        this.fps = fps
        this.rotation = rotation

        try {
            cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            // Find appropriate camera
            cameraId = findCameraId(useFrontCamera)
            if (cameraId == null) {
                Log.e(TAG, "No suitable camera found")
                return false
            }

            // Initialize JPEG rotation based on sensor orientation
            // Assume portrait (0°) device orientation for initial value
            jpegRotation = if (useFrontCamera) {
                (sensorOrientation + 180) % 360
            } else {
                sensorOrientation % 360
            }
            Log.i(TAG, "Initial JPEG rotation: $jpegRotation° (sensor=$sensorOrientation°, ${if (useFrontCamera) "front" else "back"} camera)")

            // Compute face detection rotation for ML Kit InputImage.
            // Standard Android formula: front = (sensor + device) % 360, back = (sensor - device + 360) % 360
            // But landscape-natural devices (Fire tablets) need +90° compensation because
            // Surface.ROTATION_0 = landscape, not portrait.
            val display = (context as? android.app.Activity)?.windowManager?.defaultDisplay
            val displayRotation = display?.rotation ?: Surface.ROTATION_0
            val deviceDegrees = when (displayRotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val metrics = android.util.DisplayMetrics()
            display?.getRealMetrics(metrics)
            val isLandscapeNatural = metrics.widthPixels > metrics.heightPixels && displayRotation == Surface.ROTATION_0
            // External (USB) cameras output frames in their native orientation with sensorOrientation=0,
            // so they don't need landscape compensation — the image is already correctly oriented.
            val landscapeCompensation = if (isLandscapeNatural && !isExternalCamera) 90 else 0
            faceDetectionRotation = if (useFrontCamera) {
                (sensorOrientation + deviceDegrees + landscapeCompensation) % 360
            } else {
                (sensorOrientation - deviceDegrees + landscapeCompensation + 360) % 360
            }
            Log.i(TAG, "Face detection rotation: $faceDetectionRotation° (sensor=$sensorOrientation°, device=$deviceDegrees°, landscapeNatural=$isLandscapeNatural, external=$isExternalCamera)")

            // Start camera handler thread
            cameraThread = HandlerThread("Camera2MotionSource").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)

            // Initialize orientation listener to update JPEG rotation automatically
            orientationListener = object : OrientationEventListener(context) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) return

                    // Map device orientation to rotation angles
                    val deviceRotation = when {
                        orientation >= 315 || orientation < 45 -> 0   // Portrait
                        orientation >= 45 && orientation < 135 -> 270  // Landscape (rotated left)
                        orientation >= 135 && orientation < 225 -> 180 // Upside down
                        orientation >= 225 && orientation < 315 -> 90  // Landscape (rotated right)
                        else -> 0
                    }

                    // Combine sensor orientation with device rotation
                    // For front camera, add 180 to compensate for sensor being mirrored
                    val newRotation = if (useFrontCamera) {
                        (sensorOrientation + deviceRotation + 180) % 360
                    } else {
                        (sensorOrientation + deviceRotation) % 360
                    }

                    // Update if changed
                    if (newRotation != jpegRotation) {
                        jpegRotation = newRotation
                        Log.d(TAG, "Device rotated - JPEG rotation updated to $jpegRotation° (sensor=$sensorOrientation° + device=$deviceRotation° + ${if (useFrontCamera) "180° front-cam" else "0°"})")
                    }
                }
            }

            // Enable the orientation listener
            if (orientationListener?.canDetectOrientation() == true) {
                orientationListener?.enable()
                Log.d(TAG, "Orientation listener enabled")
            } else {
                Log.w(TAG, "Cannot detect orientation changes")
            }

            // Create ImageReader for motion detection
            motionImageReader = ImageReader.newInstance(
                MOTION_WIDTH,
                MOTION_HEIGHT,
                ImageFormat.YUV_420_888,
                2  // Max images in buffer
            )

            motionImageReader?.setOnImageAvailableListener({ reader ->
                processMotionFrame(reader)
            }, cameraHandler)

            created = true
            Log.i(TAG, "Camera2MotionVideoSource created successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Camera2MotionVideoSource: ${e.message}", e)
            return false
        }
    }

    override fun start(surfaceTexture: SurfaceTexture) {
        Log.i(TAG, "Starting Camera2MotionVideoSource with encoder surface")

        if (!created) {
            Log.e(TAG, "Cannot start - not created")
            return
        }

        if (running) {
            Log.w(TAG, "Already running")
            return
        }

        // Decide the camera buffer size. For a PORTRAIT encoder frame we deliberately capture a
        // NATIVE landscape buffer: sensors have no portrait output config, and asking for one
        // makes the HAL anamorphically squeeze the landscape frame into it — the stream then
        // fills the frame but faces are narrowed and squares come out tall (verified on SM-X200,
        // where the hoop backboard measured taller than wide). The GL layer orients the landscape
        // buffer into the portrait frame instead — see setStreamIsPortrait in RtspCameraServer2.
        // Landscape encoder frames are captured at the encoder size unchanged.
        val portrait = height > width
        val captureSize = if (portrait) {
            chooseLandscapeCaptureSize(
                targetLongEdge = maxOf(width, height),
                // Ideal capture aspect = inverse of the portrait encoder aspect (e.g. 1920/1080).
                targetAspect = maxOf(width, height).toFloat() / minOf(width, height)
            )
        } else null
        val bufferWidth = captureSize?.width ?: width
        val bufferHeight = captureSize?.height ?: height

        // Store the surface texture and create encoder surface
        this.surfaceTexture = surfaceTexture
        surfaceTexture.setDefaultBufferSize(bufferWidth, bufferHeight)
        encoderSurface = Surface(surfaceTexture)

        // Open camera
        openCamera()
    }

    override fun stop() {
        Log.i(TAG, "Stopping Camera2MotionVideoSource")

        running = false
        firstFrameReceived = false  // Reset for next start
        stopFrameWatchdog()  // Stop the watchdog timer

        // Close capture session first (stops the repeating request)
        val session = captureSession
        captureSession = null
        try {
            session?.stopRepeating()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping repeating request: ${e.message}")
        }
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing capture session: ${e.message}")
        }

        // Close camera device.
        // Note: Camera2's close() is asynchronous — the HAL may still hold resources after
        // this returns. restartServer() must sleep long enough for the HAL to fully release
        // before opening a new camera, otherwise CameraService evicts the old client and
        // triggers onDisconnected on the NEW camera, causing an infinite restart loop.
        val camera = cameraDevice
        cameraDevice = null
        try {
            camera?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera device: ${e.message}")
        }

        // Release encoder surface
        encoderSurface?.release()
        encoderSurface = null

        previousFrameData = null
        Log.i(TAG, "Camera2MotionVideoSource stopped")
    }

    override fun release() {
        Log.i(TAG, "Releasing Camera2MotionVideoSource")

        stopFrameWatchdog()  // Ensure watchdog is stopped
        stop()

        // Close ImageReader
        motionImageReader?.close()
        motionImageReader = null

        // Stop handler thread
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null

        // Disable orientation listener
        orientationListener?.disable()
        orientationListener = null

        cameraManager = null
        created = false

        Log.i(TAG, "Camera2MotionVideoSource released")
    }

    override fun isRunning(): Boolean = running

    // ========== Camera2 Implementation ==========

    private fun findCameraId(useFront: Boolean): String? {
        val manager = cameraManager ?: return null
        val targetFacing = if (useFront) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK

        for (id in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == targetFacing) {
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                queryHwFaceCapabilities(characteristics)
                Log.i(TAG, "Found ${if (useFront) "front" else "back"} camera: $id, sensorOrientation=$sensorOrientation")
                return id
            }
        }

        // Fallback to first available camera
        return manager.cameraIdList.firstOrNull()?.also { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            queryHwFaceCapabilities(characteristics)
            Log.w(TAG, "Using first available camera: $id, sensorOrientation=$sensorOrientation")
        }
    }

    /**
     * Query HW face detection capabilities from camera characteristics.
     * Called during findCameraId() when camera is selected.
     */
    private fun queryHwFaceCapabilities(characteristics: CameraCharacteristics) {
        val faceDetectModes: IntArray? = characteristics.get(
            CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES
        )
        hwFaceDetectSupported = faceDetectModes?.any { mode ->
            mode == CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE ||
            mode == CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL
        } ?: false

        // USB/UVC cameras (LENS_FACING_EXTERNAL) can't do ISP face detection even if the
        // HAL reports SIMPLE mode. The camera framework advertises the capability generically
        // but the UVC driver just streams raw frames with no ISP processing.
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        isExternalCamera = facing == CameraCharacteristics.LENS_FACING_EXTERNAL
        if (isExternalCamera && hwFaceDetectSupported) {
            Log.i(TAG, "External (USB) camera detected — disabling HW face detection (UVC has no ISP)")
            hwFaceDetectSupported = false
        }

        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        activeArrayWidth = activeArray?.width() ?: 0

        val maxFaces = characteristics.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) ?: 0
        Log.i(TAG, "HW face detection: supported=$hwFaceDetectSupported, maxFaces=$maxFaces, activeArrayWidth=$activeArrayWidth, facing=$facing")
    }

    /**
     * Get the sensor orientation of the current camera.
     * This can be used to set the encoder rotation.
     * Note: Call querySensorOrientation() first if camera hasn't been created yet.
     */
    fun getSensorOrientation(): Int = sensorOrientation

    /**
     * Query the sensor orientation without fully initializing the camera.
     * Use this to get the rotation value before calling create().
     */
    fun querySensorOrientation(): Int {
        if (sensorOrientation != 0) return sensorOrientation  // Already queried

        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val targetFacing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT
            else CameraCharacteristics.LENS_FACING_BACK

            for (id in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == targetFacing) {
                    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    Log.i(TAG, "Queried sensor orientation for ${if (useFrontCamera) "front" else "back"} camera: $sensorOrientation")
                    return sensorOrientation
                }
            }

            // Fallback to first available camera
            manager.cameraIdList.firstOrNull()?.let { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                Log.i(TAG, "Queried sensor orientation for fallback camera $id: $sensorOrientation")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query sensor orientation: ${e.message}")
        }

        return sensorOrientation
    }

    /**
     * Choose a NATIVE landscape output size for the SurfaceTexture when portrait streaming.
     *
     * Sensors have NO portrait output configuration (verified on SM-X200: all 21 advertised
     * sizes are landscape or square). Requesting a portrait buffer asks for something the
     * sensor cannot produce, and the HAL responds by anamorphically SQUEEZING the full
     * landscape frame into it — the stream then fills the frame but every face is narrowed and
     * every square is tall. So we always request a real landscape size the sensor supports and
     * let the GL layer orient it (see setStreamIsPortrait in [RtspCameraServer2]).
     *
     * Ranking puts ASPECT first: a buffer whose aspect isn't a native sensor aspect is exactly
     * what gets squeezed/padded. Squares are excluded for the same reason — `>=` would admit
     * 1920x1920, which outranked 1920x1080 on long edge alone and reintroduced the bug. Only
     * after an aspect match do we tie-break on long edge, so the streamed resolution still
     * tracks the user's chosen resolution.
     *
     * @param targetLongEdge the portrait encoder's long edge (e.g. 1920)
     * @param targetAspect the ideal capture aspect — the INVERSE of the encoder aspect
     *   (e.g. a 1080x1920 encoder → 1.778, i.e. 16:9).
     */
    private fun chooseLandscapeCaptureSize(targetLongEdge: Int, targetAspect: Float): Size? {
        val id = cameraId ?: return null
        val map = try {
            cameraManager?.getCameraCharacteristics(id)
                ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read stream config map: ${e.message}")
            null
        } ?: return null

        // Strictly landscape — `>=` would admit squares, which the HAL squeezes just the same.
        val landscape = map.getOutputSizes(SurfaceTexture::class.java)
            ?.filter { it.width > it.height }
            .orEmpty()
        if (landscape.isEmpty()) {
            Log.w(TAG, "No landscape SurfaceTexture output sizes available")
            return null
        }

        // Aspect first (bucketed to 2dp so all 16:9 candidates tie), then long edge.
        val best = landscape.minWithOrNull(
            compareBy(
                { size: Size -> (abs(size.width.toFloat() / size.height - targetAspect) * 100f).roundToInt() },
                { size: Size -> abs(size.width - targetLongEdge) }
            )
        )
        Log.i(TAG, "Portrait capture: using native landscape ${best?.width}x${best?.height}")
        return best
    }

    private fun openCamera() {
        val manager = cameraManager ?: return
        val id = cameraId ?: return
        val handler = cameraHandler ?: return

        // Check camera permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted")
            return
        }

        try {
            Log.i(TAG, "Opening camera: $id")
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.i(TAG, "Camera opened successfully")
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    // Check if this disconnect is for the CURRENT camera device.
                    // During restartServer(), the old CameraDevice's onDisconnected fires
                    // asynchronously after a new camera has already been opened. Ignoring
                    // stale disconnects prevents an infinite restart loop.
                    if (camera !== cameraDevice) {
                        Log.w(TAG, "Camera disconnected (stale device, ignoring) - closing old device")
                        camera.close()
                        return
                    }
                    Log.w(TAG, "Camera disconnected - will notify for restart")
                    camera.close()
                    cameraDevice = null
                    running = false
                    // Notify listener so RTSP server can restart
                    cameraDisconnectCallback?.invoke("Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    val errorMsg = when (error) {
                        ERROR_CAMERA_IN_USE -> "Camera in use"
                        ERROR_MAX_CAMERAS_IN_USE -> "Max cameras in use"
                        ERROR_CAMERA_DISABLED -> "Camera disabled"
                        ERROR_CAMERA_DEVICE -> "Camera device error"
                        ERROR_CAMERA_SERVICE -> "Camera service error"
                        else -> "Unknown error ($error)"
                    }
                    // Ignore errors from stale camera devices (same as onDisconnected)
                    if (camera !== cameraDevice) {
                        Log.w(TAG, "Camera error on stale device (ignoring): $errorMsg")
                        camera.close()
                        return
                    }
                    Log.e(TAG, "Camera error: $errorMsg - will notify for restart")
                    camera.close()
                    cameraDevice = null
                    running = false
                    // Notify listener so RTSP server can restart
                    cameraDisconnectCallback?.invoke("Camera error: $errorMsg")
                }
            }, handler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera: ${e.message}", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied: ${e.message}", e)
        }
    }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val encoderSurf = encoderSurface ?: return
        val motionReader = motionImageReader ?: return
        val handler = cameraHandler ?: return

        try {
            // Create list of output surfaces - BOTH encoder AND motion detection
            val surfaces = listOf(
                encoderSurf,                  // For RTSP encoding
                motionReader.surface          // For motion detection
            )

            Log.i(TAG, "Creating capture session with ${surfaces.size} output surfaces (encoder + motion)")

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.i(TAG, "Capture session configured with dual output surfaces")
                    captureSession = session
                    startCapture()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Failed to configure capture session")
                    running = false
                }
            }, handler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create capture session: ${e.message}", e)
        }
    }

    private fun startCapture() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val encoderSurf = encoderSurface ?: return
        val motionReader = motionImageReader ?: return
        val handler = cameraHandler ?: return

        try {
            // Create capture request with BOTH surfaces as targets
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

            // Add both surfaces to the request
            requestBuilder.addTarget(encoderSurf)
            requestBuilder.addTarget(motionReader.surface)

            // Configure for best streaming performance
            requestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)

            // Enable hardware face detection if supported (zero CPU cost — runs on ISP)
            if (hwFaceDetectSupported) {
                requestBuilder.set(
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                    CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
                )
                Log.i(TAG, "HW face detection enabled in capture request (SIMPLE mode)")
            }

            captureRequest = requestBuilder.build()

            // Use CaptureCallback to read HW face results, or null if not supported
            val captureCallback = if (hwFaceDetectSupported) {
                object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: android.hardware.camera2.CameraCaptureSession,
                        request: CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult
                    ) {
                        processHwFaceResult(result)
                    }
                }
            } else null

            // Start repeating capture
            session.setRepeatingRequest(captureRequest!!, captureCallback, handler)

            running = true
            Log.i(TAG, "Capture started - frames going to both encoder and motion detector")

            // Start frame watchdog to detect camera stalls
            startFrameWatchdog()
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to start capture: ${e.message}", e)
            running = false
        }
    }

    /**
     * Set callback for face detection frame forwarding.
     * When set, YUV frames are converted to Bitmap and forwarded for ML Kit face detection.
     */
    fun setFaceFrameCallback(callback: ((android.graphics.Bitmap, Int) -> Unit)?) {
        faceFrameCallback = callback
        Log.i(TAG, "Face frame callback ${if (callback != null) "set" else "cleared"}")
    }

    /** Second face frame callback for HA sensor publishing (parallel to wake detector) */
    fun setHaSensorFaceFrameCallback(callback: ((android.graphics.Bitmap, Int) -> Unit)?) {
        haSensorFaceFrameCallback = callback
        Log.i(TAG, "HA sensor face frame callback ${if (callback != null) "set" else "cleared"}")
    }

    // ── Hardware face detection API ──

    /** Whether Camera2 hardware face detection is available on this device */
    fun isHwFaceDetectSupported(): Boolean = hwFaceDetectSupported

    /** Set callback for HW face detection 3-state results (wake detector path) */
    fun setHwFaceResultCallback(callback: ((Int) -> Unit)?) {
        hwFaceResultCallback = callback
        Log.i(TAG, "HW face result callback ${if (callback != null) "set" else "cleared"}")
    }

    /** Set callback triggered when a qualifying face is detected (wake trigger) */
    fun setHwFaceDetectedCallback(callback: (() -> Unit)?) {
        hwFaceDetectedCallback = callback
    }

    /** Set callback for HA sensor face results (parallel to wake path) */
    fun setHaSensorHwFaceResultCallback(callback: ((Int) -> Unit)?) {
        haSensorHwFaceResultCallback = callback
        Log.i(TAG, "HA sensor HW face result callback ${if (callback != null) "set" else "cleared"}")
    }

    /**
     * Process hardware face detection results from CaptureResult.
     * Called on the camera handler thread for every captured frame.
     * Rate-limited to ~3 results/sec to match FaceWakeDetector behavior.
     */
    private fun processHwFaceResult(result: android.hardware.camera2.TotalCaptureResult) {
        val now = System.currentTimeMillis()
        if (now - lastHwFaceResultTime < FACE_FRAME_INTERVAL_MS) return
        lastHwFaceResultTime = now

        // Runtime validation: confirm the HAL actually enables face detection mode.
        // Safety net for edge cases not caught by LENS_FACING_EXTERNAL check.
        if (!hwFaceValidated) {
            hwFaceValidationCount++
            val actualMode = result.get(android.hardware.camera2.CaptureResult.STATISTICS_FACE_DETECT_MODE)
            if (actualMode != null && actualMode != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF) {
                hwFaceValidated = true
                Log.i(TAG, "HW face validated: mode=$actualMode after $hwFaceValidationCount frames")
            } else if (hwFaceValidationCount >= HW_FACE_VALIDATION_FRAMES) {
                Log.w(TAG, "HW face not active: mode=$actualMode after $hwFaceValidationCount frames. Falling back to ML Kit.")
                hwFaceDetectSupported = false
                return
            }
        }

        val resultCb = hwFaceResultCallback
        val wakeCb = hwFaceDetectedCallback
        val haSensorCb = haSensorHwFaceResultCallback
        if (resultCb == null && wakeCb == null && haSensorCb == null) return

        val faces = result.get(android.hardware.camera2.CaptureResult.STATISTICS_FACES)
        if (faces == null || faces.isEmpty() || activeArrayWidth <= 0) {
            Log.d(TAG, "HW face DIAG: 0 faces (ISP=${if (faces == null) "null" else "empty"}, activeArrayWidth=$activeArrayWidth)")
            resultCb?.invoke(FACE_RESULT_NONE)
            haSensorCb?.invoke(FACE_RESULT_NONE)
            return
        }

        // Distance filter: face bounding box is in active array coordinates
        val maxFaceWidthPct = faces.maxOf { it.bounds.width().toFloat() / activeArrayWidth }
        val qualifyingFace = maxFaceWidthPct >= hwFaceMinSizePercent
        // Debug-level: useful for diagnosing "face wake won't trigger / too far" reports
        // (shows detected face size vs the configured distance threshold).
        Log.d(TAG, "HW face: ${faces.size} face(s), max=${"%.1f".format(maxFaceWidthPct * 100)}% vs min=${"%.1f".format(hwFaceMinSizePercent * 100)}% -> ${if (qualifyingFace) "WAKE" else "too far"}")

        val faceResult = if (qualifyingFace) FACE_RESULT_DETECTED else FACE_RESULT_TOO_FAR
        resultCb?.invoke(faceResult)
        haSensorCb?.invoke(faceResult)

        if (qualifyingFace) {
            wakeCb?.invoke()
        }
    }

    /**
     * Convert YUV_420_888 image to proper NV21 byte array.
     * Handles both pixelStride=1 (planar) and pixelStride=2 (semi-planar/interleaved) formats.
     * Different devices use different formats - this handles both correctly.
     */
    private fun yuv420ToNv21(image: android.media.Image): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        val nv21 = ByteArray(width * height * 3 / 2)

        // Copy Y plane (handle rowStride padding)
        val yRowStride = yPlane.rowStride
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, width * height)
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, row * width, width)
            }
        }

        // Copy UV planes into NV21 interleaved format (VUVUVU...)
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val uvPixelStride = vPlane.pixelStride
        var offset = width * height

        if (uvPixelStride == 2) {
            // Semi-planar: V buffer already contains interleaved VU pairs
            val vRowStride = vPlane.rowStride
            for (row in 0 until chromaHeight) {
                vBuffer.position(row * vRowStride)
                // Last row may be 1 byte shorter (buffer ends after last V, no trailing U)
                val bytesToRead = minOf(chromaWidth * 2, vBuffer.remaining())
                vBuffer.get(nv21, offset, bytesToRead)
                offset += chromaWidth * 2
            }
        } else {
            // Planar (pixelStride=1): V and U are separate, need to interleave
            val vRowStride = vPlane.rowStride
            val uRowStride = uPlane.rowStride
            for (row in 0 until chromaHeight) {
                for (col in 0 until chromaWidth) {
                    nv21[offset++] = vBuffer.get(row * vRowStride + col)
                    nv21[offset++] = uBuffer.get(row * uRowStride + col)
                }
            }
        }

        return nv21
    }

    /**
     * Convert YUV_420_888 image to Bitmap for face detection.
     * Uses YUV→JPEG→Bitmap pipeline (fast enough for 320x240).
     */
    private fun yuvToBitmap(image: android.media.Image): android.graphics.Bitmap? {
        try {
            val nv21 = yuv420ToNv21(image)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
            return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        } catch (e: Exception) {
            Log.w(TAG, "yuvToBitmap failed: ${e.message}")
            return null
        }
    }

    // ========== Motion Detection ==========

    private fun processMotionFrame(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage() ?: return
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring image: ${e.message}")
            return
        }

        frameCount++
        lastFrameTime = System.currentTimeMillis()  // Update watchdog timestamp

        // Phase-0: emit a persistent heartbeat ~every 60s so a submitted diagnostic
        // shows whether the RTSP camera keeps producing frames through screen-off.
        if (lastHeartbeatLogTime == 0L) lastHeartbeatLogTime = lastFrameTime
        if (lastFrameTime - lastHeartbeatLogTime >= 60_000L) {
            val delta = frameCount - framesAtLastHeartbeat
            PersistentLog.info("CAMERA", "RTSP camera heartbeat: $delta frames in last 60s (total=$frameCount)")
            lastHeartbeatLogTime = lastFrameTime
            framesAtLastHeartbeat = frameCount
        }

        // Mark that camera is fully initialized and producing frames
        if (!firstFrameReceived) {
            firstFrameReceived = true
            val yPlane = image.planes[0]
            val vPlane = image.planes[2]
            val format = if (vPlane.pixelStride == 2) "semi-planar" else "planar"
            val msg = "Motion detection started: ${image.width}x${image.height}, Y rowStride=${yPlane.rowStride}, UV pixelStride=${vPlane.pixelStride} rowStride=${vPlane.rowStride} ($format)"
            Log.i(TAG, msg)
            DiagnosticBuffer.info("CAMERA", msg)
            PersistentLog.info("CAMERA", msg)
        }

        try {
            val now = System.currentTimeMillis()

            // Capture JPEG only when requested (on-demand for getCamshot API)
            if (jpegCaptureRequested) {
                captureJpegFromYuv(image)
                jpegCaptureRequested = false
            }

            // Forward frame to ML Kit face detector only if HW face detection is NOT handling it.
            // When HW is active, face results come from CaptureResult (processHwFaceResult),
            // so we skip the expensive YUV→Bitmap conversion entirely.
            // Only forward when motion was detected recently to save CPU (YUV→Bitmap is expensive).
            val motionRecent = lastMotionTime > 0 && (now - lastMotionTime < FACE_MOTION_WINDOW_MS)
            if (!hwFaceDetectSupported && motionRecent) {
                faceFrameCallback?.let { callback ->
                    if (now - lastFaceFrameTime >= FACE_FRAME_INTERVAL_MS) {
                        lastFaceFrameTime = now
                        try {
                            val bitmap = yuvToBitmap(image)
                            if (bitmap != null) {
                                Log.d(TAG, "Forwarding face frame: ${bitmap.width}x${bitmap.height}, rot=$faceDetectionRotation")
                                callback(bitmap, faceDetectionRotation)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to forward face frame: ${e.message}")
                        }
                    }
                }

                haSensorFaceFrameCallback?.let { callback ->
                    if (now - lastHaSensorFaceFrameTime >= FACE_FRAME_INTERVAL_MS) {
                        lastHaSensorFaceFrameTime = now
                        try {
                            val bitmap = yuvToBitmap(image)
                            if (bitmap != null) callback(bitmap, faceDetectionRotation)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to forward HA sensor face frame: ${e.message}")
                        }
                    }
                }
            }

            // Skip motion detection if not enabled
            if (!motionEnabled) {
                image.close()
                return
            }

            // Skip if still in arming delay
            if (now - armTime < ARM_DELAY_MS) {
                image.close()
                return
            }

            // Rate limit analysis
            if (now - lastAnalysisTime < ANALYSIS_INTERVAL_MS) {
                image.close()
                return
            }
            lastAnalysisTime = now

            // Get Y plane (luminance) for motion detection
            val yBuffer = image.planes[0].buffer
            val sampledData = sampleFrame(yBuffer, image.width, image.height)

            // Compare with previous frame
            val previousData = previousFrameData
            if (previousData != null && previousData.size == sampledData.size) {
                val motionScore = calculateMotionScore(previousData, sampledData)

                // Store for graph visualization
                lastMotionScore = motionScore

                // Log periodically for debugging
                if (frameCount % 120 == 0) {
                    Log.d(TAG, "Motion score: %.2f%% (threshold: %.2f%%)".format(motionScore, motionThresholdPercent))
                }

                if (motionScore > motionThresholdPercent) {
                    // Check debounce
                    if (now - lastMotionTime > MOTION_DEBOUNCE_MS) {
                        lastMotionTime = now
                        val hasCallback = motionCallback != null
                        Log.i(TAG, "Motion detected! Score: %.2f%% (threshold: %.2f%%) hasCallback=$hasCallback".format(motionScore, motionThresholdPercent))

                        // Trigger callback on main thread
                        if (hasCallback) {
                            Handler(android.os.Looper.getMainLooper()).post {
                                Log.d(TAG, "Invoking motion callback on main thread")
                                motionCallback?.invoke(true)
                            }
                        }
                    }
                }
            }

            // Store current frame for next comparison
            previousFrameData = sampledData

        } finally {
            image.close()
        }
    }

    private fun sampleFrame(buffer: java.nio.ByteBuffer, width: Int, height: Int): ByteArray {
        val sampledWidth = width / DOWNSCALE_FACTOR
        val sampledHeight = height / DOWNSCALE_FACTOR
        val sampledData = ByteArray(sampledWidth * sampledHeight)

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

    private fun calculateMotionScore(prev: ByteArray, curr: ByteArray): Double {
        var changedPixels = 0
        var totalBrightnessDiff = 0L  // Track global brightness change (auto-exposure detection)

        for (i in prev.indices) {
            val prevVal = prev[i].toInt() and 0xFF
            val currVal = curr[i].toInt() and 0xFF
            val diff = currVal - prevVal  // Signed diff for global brightness tracking
            totalBrightnessDiff += diff
            if (abs(diff) > PIXEL_CHANGE_THRESHOLD) {
                changedPixels++
            }
        }

        // Calculate average brightness change per pixel
        val avgBrightnessChange = abs(totalBrightnessDiff.toDouble() / prev.size)

        // If there's a significant global brightness shift (>10 levels), it's likely auto-exposure
        // In this case, check if most "changed" pixels are just following the global shift
        if (avgBrightnessChange > 10.0 && changedPixels > prev.size * 0.15) {
            // Recount changed pixels, but compensate for global brightness shift
            val compensatedThreshold = PIXEL_CHANGE_THRESHOLD + avgBrightnessChange.toInt()
            var compensatedChangedPixels = 0
            for (i in prev.indices) {
                val prevVal = prev[i].toInt() and 0xFF
                val currVal = curr[i].toInt() and 0xFF
                val diff = abs(currVal - prevVal)
                if (diff > compensatedThreshold) {
                    compensatedChangedPixels++
                }
            }
            Log.d(TAG, "Auto-exposure detected (avgShift=%.1f), compensating: %d -> %d changed pixels"
                .format(avgBrightnessChange, changedPixels, compensatedChangedPixels))
            changedPixels = compensatedChangedPixels
        }

        return (changedPixels.toDouble() / prev.size) * 100.0
    }

    // ========== Public Motion Detection API ==========

    /**
     * Set callback for motion detection events
     */
    fun setMotionCallback(callback: (Boolean) -> Unit) {
        motionCallback = callback
        Log.i(TAG, "Motion callback set (non-null)")
    }

    /**
     * Set callback for camera disconnect/error events.
     * Called when the camera disconnects unexpectedly (e.g., USB camera unplugged or error).
     * The RTSP server should use this to trigger a restart.
     */
    fun setCameraDisconnectCallback(callback: (String) -> Unit) {
        cameraDisconnectCallback = callback
        Log.i(TAG, "Camera disconnect callback set")
    }

    /**
     * Enable motion detection (arm it)
     */
    fun enableMotionDetection() {
        motionEnabled = true
        armTime = System.currentTimeMillis()
        previousFrameData = null
        Log.d(TAG, "Motion detection enabled (armed, ${ARM_DELAY_MS}ms delay, threshold: ${motionThresholdPercent}%)")
    }

    /**
     * Disable motion detection
     */
    fun disableMotionDetection() {
        motionEnabled = false
        previousFrameData = null
        Log.d(TAG, "Motion detection disabled")
    }

    /**
     * Re-arm motion detection (reset the arm delay timer).
     * Call this when the screen dims to give the camera time to stabilize
     * before starting to detect motion.
     */
    fun rearmMotionDetection() {
        if (motionEnabled) {
            armTime = System.currentTimeMillis()
            previousFrameData = null
            Log.d(TAG, "Motion detection re-armed (${ARM_DELAY_MS}ms delay)")
        }
    }

    /**
     * Check if motion detection is enabled
     */
    fun isMotionDetectionEnabled(): Boolean = motionEnabled

    /**
     * Set motion threshold (higher = less sensitive)
     */
    fun setMotionThreshold(threshold: Double) {
        motionThresholdPercent = threshold.coerceIn(0.5, 20.0)
        Log.d(TAG, "Motion threshold set to ${motionThresholdPercent}%")
    }

    // ========== Frame Watchdog (detect camera stalls) ==========

    /**
     * Start the frame watchdog timer.
     * Periodically checks if frames are still arriving; triggers disconnect if stalled.
     */
    private fun startFrameWatchdog() {
        stopFrameWatchdog()  // Clean up any existing watchdog

        lastFrameTime = System.currentTimeMillis()  // Initialize timestamp

        watchdogHandler = Handler(android.os.Looper.getMainLooper())
        watchdogRunnable = object : Runnable {
            override fun run() {
                if (!running) {
                    Log.d(TAG, "Watchdog: Camera not running, stopping watchdog")
                    return
                }

                val now = System.currentTimeMillis()
                val timeSinceLastFrame = now - lastFrameTime

                if (timeSinceLastFrame > FRAME_TIMEOUT_MS) {
                    // No frames received for too long - camera is stalled
                    Log.e(TAG, "⚠️ Watchdog: No frames received for ${timeSinceLastFrame}ms - camera stalled!")
                    running = false  // Mark as not running to prevent further watchdog triggers

                    // Trigger disconnect callback to initiate restart
                    cameraDisconnectCallback?.invoke("Camera stalled - no frames for ${timeSinceLastFrame / 1000}s")
                } else {
                    // Camera is healthy, schedule next check
                    if (frameCount % 100 == 0) {  // Log occasionally for debugging
                        Log.d(TAG, "Watchdog: Camera healthy, last frame ${timeSinceLastFrame}ms ago")
                    }
                    watchdogHandler?.postDelayed(this, WATCHDOG_INTERVAL_MS)
                }
            }
        }

        // Start the watchdog after initial delay (give camera time to initialize)
        watchdogHandler?.postDelayed(watchdogRunnable!!, WATCHDOG_INTERVAL_MS)
        Log.i(TAG, "Frame watchdog started (interval=${WATCHDOG_INTERVAL_MS}ms, timeout=${FRAME_TIMEOUT_MS}ms)")
    }

    /**
     * Stop the frame watchdog timer.
     */
    private fun stopFrameWatchdog() {
        watchdogRunnable?.let { watchdogHandler?.removeCallbacks(it) }
        watchdogHandler = null
        watchdogRunnable = null
    }

    // ========== JPEG Frame Capture for getCamshot API ==========

    // Rotation angle for JPEG output (combines sensor orientation + device orientation)
    private var jpegRotation = 0  // Will be calculated based on sensor + device orientation

    // Orientation listener to update JPEG rotation when device rotates
    private var orientationListener: OrientationEventListener? = null

    /**
     * Set the rotation angle for JPEG capture.
     * @param degrees Rotation in degrees (0, 90, 180, 270)
     */
    fun setJpegRotation(degrees: Int) {
        jpegRotation = degrees
        Log.d(TAG, "JPEG rotation set to $degrees degrees")
    }

    /**
     * Convert YUV image to JPEG and store for getCamshot API.
     * Applies rotation based on camera/device orientation.
     */
    private fun captureJpegFromYuv(image: android.media.Image) {
        try {
            val nv21 = yuv420ToNv21(image)

            // Convert to JPEG using YuvImage
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val tempStream = ByteArrayOutputStream()

            // Compress to JPEG with 85% quality
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 85, tempStream)

            // Apply rotation and/or horizontal flip if needed
            if (jpegRotation != 0 || useFrontCamera) {
                val jpegBytes = tempStream.toByteArray()
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                if (bitmap != null) {
                    val matrix = Matrix().apply {
                        if (jpegRotation != 0) {
                            postRotate(jpegRotation.toFloat())
                        }
                        // Apply horizontal flip for front camera to match RTSP stream
                        // (un-mirror the selfie view for monitoring use case)
                        if (useFrontCamera) {
                            postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                        }
                    }
                    val transformedBitmap = android.graphics.Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                    )
                    val transformedStream = ByteArrayOutputStream()
                    transformedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, transformedStream)
                    latestJpegFrame = transformedStream.toByteArray()

                    // Clean up bitmaps
                    if (transformedBitmap != bitmap) {
                        transformedBitmap.recycle()
                    }
                    bitmap.recycle()
                } else {
                    // Fallback if decode fails
                    latestJpegFrame = tempStream.toByteArray()
                }
            } else {
                latestJpegFrame = tempStream.toByteArray()
            }

            Log.d(TAG, "Captured JPEG frame: ${latestJpegFrame?.size} bytes (${image.width}x${image.height}, rot=$jpegRotation)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to capture JPEG: ${e.message}")
        }
    }

    /**
     * Get a fresh JPEG frame for getCamshot API.
     * This triggers an on-demand capture and waits for it (up to 500ms).
     * Returns null if camera is not running or capture times out.
     */
    fun getLatestJpegFrame(): ByteArray? {
        if (!running) {
            Log.w(TAG, "getCamshot: Camera not running")
            return null
        }

        // Wait for camera to be fully initialized (first frame received)
        // Camera initialization can take 1-2 seconds after session starts
        if (!firstFrameReceived) {
            Log.d(TAG, "getCamshot: Waiting for camera initialization...")
            val initStartTime = System.currentTimeMillis()
            val initTimeout = 2000L  // 2 second timeout for camera init
            while (!firstFrameReceived && running && System.currentTimeMillis() - initStartTime < initTimeout) {
                Thread.sleep(100)
            }
            if (!firstFrameReceived) {
                Log.w(TAG, "getCamshot: Camera initialization timed out after ${initTimeout}ms")
                return null
            }
            Log.d(TAG, "getCamshot: Camera initialized in ${System.currentTimeMillis() - initStartTime}ms")
        }

        // Request a fresh capture
        jpegCaptureRequested = true

        // Wait for capture to complete (up to 500ms, checking every 50ms)
        // At 10fps, a new frame should arrive within 100ms
        val startTime = System.currentTimeMillis()
        val timeout = 500L
        while (jpegCaptureRequested && System.currentTimeMillis() - startTime < timeout) {
            Thread.sleep(50)
        }

        if (jpegCaptureRequested) {
            Log.w(TAG, "getCamshot: Capture timed out after ${timeout}ms")
            jpegCaptureRequested = false
            return null
        }

        Log.d(TAG, "getCamshot: Captured ${latestJpegFrame?.size} bytes in ${System.currentTimeMillis() - startTime}ms")
        return latestJpegFrame
    }

    /**
     * Check if camera is running and can provide a camshot.
     */
    fun hasJpegFrame(): Boolean = running

    /**
     * Check if camera is fully initialized (has received first frame).
     * Useful for UI to know when preview/graph will start showing data.
     */
    fun isFullyInitialized(): Boolean = running && firstFrameReceived

    // ========== Camera Validation ==========

    /**
     * Validate that the camera can actually produce frames.
     * This is a quick check to detect "phantom" cameras (HAL present but no physical camera).
     *
     * @param timeoutMs Maximum time to wait for first frame (default 3 seconds)
     * @return true if camera produced a frame within timeout, false otherwise
     */
    fun validateCamera(timeoutMs: Long = 3000L): Boolean {
        if (!created) {
            Log.w(TAG, "validateCamera: Not created")
            return false
        }

        // If already running and has received frames, camera is valid
        if (running && firstFrameReceived) {
            Log.d(TAG, "validateCamera: Already validated (running and received frames)")
            return true
        }

        // If running but no frames yet, wait for them
        if (running) {
            val startTime = System.currentTimeMillis()
            while (!firstFrameReceived && System.currentTimeMillis() - startTime < timeoutMs) {
                Thread.sleep(100)
            }
            val valid = firstFrameReceived
            Log.d(TAG, "validateCamera: Result=$valid (waited ${System.currentTimeMillis() - startTime}ms)")
            return valid
        }

        // Not running - can't validate without starting
        Log.w(TAG, "validateCamera: Not running - call after start()")
        return false
    }

    // ========== Camera Control ==========

    /**
     * Switch between front and back camera
     */
    fun switchCamera() {
        useFrontCamera = !useFrontCamera
        if (running) {
            stop()
            cameraId = findCameraId(useFrontCamera)
            if (surfaceTexture != null) {
                start(surfaceTexture!!)
            }
        } else {
            cameraId = findCameraId(useFrontCamera)
        }
        Log.i(TAG, "Switched to ${if (useFrontCamera) "front" else "back"} camera")
    }

    /**
     * Check if using front camera
     */
    fun isFrontCamera(): Boolean = useFrontCamera
}

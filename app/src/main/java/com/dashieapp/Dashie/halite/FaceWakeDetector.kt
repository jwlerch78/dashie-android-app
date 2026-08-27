package com.dashieapp.Dashie.halite

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two-stage face wake detector using Google ML Kit.
 *
 * Design: Face detection is active while motion is happening, and continues
 * for [COOLDOWN_AFTER_MOTION_MS] after the last motion event. This handles:
 * - Continuous motion scenes (face search stays active the whole time)
 * - Someone standing still after approaching (5s grace period to detect face)
 * - Brief motion from pets/shadows (face search auto-stops when motion stops)
 *
 * Each motion event from CameraMotionDetector calls [onMotionDetected], which
 * starts face search (if not already active) and resets the cooldown timer.
 * When the cooldown expires with no new motion, face search stops.
 *
 * The ML Kit FaceDetector is lazily initialized on first use to avoid loading
 * the ~7MB model until face mode is actually activated.
 *
 * Thread safety: analyzeFrame() is called from the camera executor thread,
 * while onMotionDetected()/stop are called from the main thread.
 */
class FaceWakeDetector(
    private val onFaceDetected: () -> Unit
) {
    companion object {
        private const val TAG = "FaceWakeDetector"
        private const val COOLDOWN_AFTER_MOTION_MS = 5000L
        private const val MIN_FACE_SIZE = 0.1f  // 10% of frame width
        private const val ANALYSIS_INTERVAL_MS = 600L  // ~1.5 analyses per second (lighter on weak devices)

        // Face result states for onFaceResult callback
        const val FACE_RESULT_NONE = 0       // No face detected in frame
        const val FACE_RESULT_DETECTED = 1   // Face detected and meets distance filter
        const val FACE_RESULT_TOO_FAR = 2    // Face detected but too far away
    }

    @Volatile private var faceDetector: FaceDetector? = null
    private val isSearchingFlag = AtomicBoolean(false)
    private val isProcessingFrame = AtomicBoolean(false)
    private var lastAnalysisTime = 0L
    @Volatile private var isDestroyed = false
    @Volatile private var hasFailed = false
    @Volatile private var isTestMode = false  // When true, analyze all frames regardless of searching state

    // Adaptive throttling: backs off when ML Kit is slow (weak device protection)
    @Volatile private var adaptiveIntervalMs = ANALYSIS_INTERVAL_MS
    private var recentInferenceTimes = LongArray(5)  // Rolling window
    private var inferenceTimeIndex = 0
    private var inferenceTimeSamples = 0

    /**
     * Minimum face size as a fraction of frame width (0.0 - 1.0).
     * Faces smaller than this are ignored (treated as "too far away").
     * Default: 0.15 (15%) = "near" distance (~1.5m).
     */
    @Volatile var minFaceSizePercent: Float = 0.15f

    // Cooldown timer: stops face search N seconds after last motion event
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cooldownRunnable = Runnable {
        Log.i(TAG, "Cooldown expired - stopping face search (no motion for ${COOLDOWN_AFTER_MOTION_MS}ms)")
        stopSearching()
    }

    /**
     * Optional callback for UI: notified on each face detection result.
     * Called on the ML Kit callback thread (not main thread).
     * Parameter: FACE_RESULT_NONE, FACE_RESULT_DETECTED, or FACE_RESULT_TOO_FAR.
     * Volatile: written on main thread (setFaceTestCallback), read on camera thread (analyzeFrame).
     */
    @Volatile var onFaceResult: ((Int) -> Unit)? = null

    /**
     * Optional presence keep-alive callback. Fired on EVERY detected face (even one
     * below the wake distance) while searching — presence, not proximity. Used to
     * reset the inactivity timer so the screensaver doesn't start while a person is
     * in view, including a perfectly still (motionless) face. Called on the ML Kit
     * callback thread; the receiver must marshal to the main thread if needed.
     */
    @Volatile var onFacePresent: (() -> Unit)? = null

    /**
     * Called when motion is detected. Starts face search if not already active,
     * and resets the cooldown timer so search continues while motion is happening.
     */
    fun onMotionDetected() {
        if (isDestroyed || hasFailed) return

        // Reset cooldown timer (search continues while motion is active)
        mainHandler.removeCallbacks(cooldownRunnable)
        mainHandler.postDelayed(cooldownRunnable, COOLDOWN_AFTER_MOTION_MS)

        // Start searching if not already
        if (isSearchingFlag.getAndSet(true)) return  // Already searching
        ensureDetector()
        Log.i(TAG, "Face search started (motion detected, cooldown ${COOLDOWN_AFTER_MOTION_MS}ms after last motion)")
    }

    /**
     * Stop searching for a face and cancel the cooldown timer.
     */
    fun stopSearching() {
        mainHandler.removeCallbacks(cooldownRunnable)
        if (!isSearchingFlag.getAndSet(false)) return
        Log.d(TAG, "Face search stopped")
    }

    /**
     * Check if currently searching for a face.
     */
    fun isSearching(): Boolean = isSearchingFlag.get()

    /**
     * Enable test mode for settings dialog preview.
     * In test mode, all frames are analyzed for faces regardless of searching state,
     * and results are reported via [onFaceResult] but don't trigger [onFaceDetected].
     */
    fun enableTestMode() {
        isTestMode = true
        ensureDetector()
        Log.d(TAG, "Test mode enabled - analyzing all frames for face indicator")
    }

    /**
     * Disable test mode.
     */
    fun disableTestMode() {
        isTestMode = false
        adaptiveIntervalMs = ANALYSIS_INTERVAL_MS
        inferenceTimeSamples = 0
        Log.d(TAG, "Test mode disabled")
    }

    /**
     * Analyze a camera frame for faces.
     *
     * Called from CameraMotionDetector's frame analysis pipeline on the camera
     * executor thread. The bitmap is owned by this method and will be recycled
     * after ML Kit processing completes (or immediately if skipped).
     *
     * @param bitmap Camera frame as Bitmap (from ImageProxy.toBitmap())
     * @param rotationDegrees Image rotation from camera sensor
     */
    fun analyzeFrame(bitmap: Bitmap, rotationDegrees: Int) {
        val searching = isSearchingFlag.get()
        if (isDestroyed || (!searching && !isTestMode)) {
            bitmap.recycle()
            return
        }
        Log.d(TAG, "analyzeFrame: searching=$searching, testMode=$isTestMode, processing=${isProcessingFrame.get()}")

        // Rate limit using adaptive interval (backs off on slow devices)
        val now = System.currentTimeMillis()
        if (now - lastAnalysisTime < adaptiveIntervalMs) {
            bitmap.recycle()
            return
        }
        lastAnalysisTime = now

        // Don't queue multiple analyses - skip if previous one still processing
        if (!isProcessingFrame.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }

        val detector = faceDetector
        if (detector == null) {
            isProcessingFrame.set(false)
            bitmap.recycle()
            return
        }

        // Clamp rotation to valid InputImage values (0, 90, 180, 270)
        val validRotation = when (rotationDegrees) {
            0, 90, 180, 270 -> rotationDegrees
            else -> 0
        }

        // Let ML Kit handle rotation internally via InputImage
        val inputImage = InputImage.fromBitmap(bitmap, validRotation)

        val bitmapWidth = bitmap.width

        val startMs = System.currentTimeMillis()
        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                isProcessingFrame.set(false)
                val elapsed = System.currentTimeMillis() - startMs
                bitmap.recycle()

                // Adaptive throttling: adjust interval based on actual inference time
                updateAdaptiveInterval(elapsed)

                // Apply distance filter: face must be large enough (close enough) to trigger
                val qualifyingFaces = faces.filter { face ->
                    face.boundingBox.width().toFloat() / bitmapWidth >= minFaceSizePercent
                }
                val faceCloseEnough = qualifyingFaces.isNotEmpty()

                // Diagnostic: log all face results with timing
                Log.d(TAG, "ML Kit: ${faces.size} faces (${qualifyingFaces.size} qualifying) in ${elapsed}ms, rot=$validRotation, bitmapW=$bitmapWidth, interval=${adaptiveIntervalMs}ms" +
                    if (faces.isNotEmpty()) " bounds=${faces.map { "${it.boundingBox.width()}x${it.boundingBox.height()}" }}" else "")

                // Report 3-state result: none / detected / too far
                val result = when {
                    faces.isEmpty() -> FACE_RESULT_NONE
                    faceCloseEnough -> FACE_RESULT_DETECTED
                    else -> FACE_RESULT_TOO_FAR
                }
                onFaceResult?.invoke(result)

                // Presence keep-alive: any detected face (even below the wake distance)
                // means a person is in view. Repost the cooldown so the search stays
                // alive on a still, motionless face — without this, a single detection
                // would stopSearching() and a motionless face would let the screen dim,
                // then re-wake on the next motion (the dim/re-wake flicker). The callback
                // resets the dimmer timer (the receiver gates on screen-on).
                if (faces.isNotEmpty() && isSearchingFlag.get()) {
                    mainHandler.removeCallbacks(cooldownRunnable)
                    mainHandler.postDelayed(cooldownRunnable, COOLDOWN_AFTER_MOTION_MS)
                    onFacePresent?.invoke()
                }

                if (faceCloseEnough && isSearchingFlag.get()) {
                    // No stopSearching() here — keep confirming so a still qualifying
                    // face keeps holding/waking the screen rather than firing once. The
                    // cooldown above stops the search when no face/motion remains.
                    onFaceDetected()
                }
            }
            .addOnFailureListener { e ->
                isProcessingFrame.set(false)
                bitmap.recycle()
                Log.w(TAG, "Face detection failed: ${e.message}", e)
            }
    }

    /**
     * Adjust analysis interval based on rolling average of ML Kit inference time.
     * On fast devices (< 200ms): stays at base interval (600ms).
     * On slow devices (> 500ms): interval = 2x avg inference time, capped at 3s.
     * This prevents weak devices from being overwhelmed by continuous ML Kit work.
     */
    private fun updateAdaptiveInterval(elapsedMs: Long) {
        recentInferenceTimes[inferenceTimeIndex] = elapsedMs
        inferenceTimeIndex = (inferenceTimeIndex + 1) % recentInferenceTimes.size
        if (inferenceTimeSamples < recentInferenceTimes.size) inferenceTimeSamples++

        val avg = recentInferenceTimes.take(inferenceTimeSamples).average().toLong()
        val newInterval = when {
            avg < 200 -> ANALYSIS_INTERVAL_MS  // Fast device, use base interval
            avg < 500 -> maxOf(ANALYSIS_INTERVAL_MS, avg * 2)  // Moderate, give breathing room
            else -> minOf(avg * 2, 3000L)  // Slow device, back off hard (cap 3s)
        }
        if (newInterval != adaptiveIntervalMs) {
            Log.i(TAG, "Adaptive throttle: avg inference=${avg}ms → interval=${newInterval}ms")
            adaptiveIntervalMs = newInterval
        }
    }

    /**
     * Lazily create the ML Kit FaceDetector.
     * Uses PERFORMANCE_MODE_FAST for lighter CPU load.
     */
    private fun ensureDetector() {
        if (faceDetector != null) return

        try {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setMinFaceSize(MIN_FACE_SIZE)
                .build()

            faceDetector = FaceDetection.getClient(options)
            Log.i(TAG, "ML Kit FaceDetector initialized (FAST mode, minFaceSize=$MIN_FACE_SIZE)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ML Kit FaceDetector: ${e.message}", e)
            hasFailed = true
        }
    }

    /**
     * Release ML Kit resources.
     */
    fun destroy() {
        isDestroyed = true
        isTestMode = false
        stopSearching()
        mainHandler.removeCallbacksAndMessages(null)
        faceDetector?.close()
        faceDetector = null
        onFaceResult = null
        onFacePresent = null
        Log.i(TAG, "FaceWakeDetector destroyed")
    }
}

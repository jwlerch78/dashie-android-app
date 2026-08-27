package com.dashieapp.Dashie.halite.screensaver

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Simplified motion detector for use in DreamService.
 *
 * Uses CameraX frame differencing to detect movement and wake the screensaver.
 * This version creates its own lifecycle for CameraX binding since DreamService
 * doesn't implement LifecycleOwner.
 *
 * Similar to the main app's CameraMotionDetector but optimized for screensaver use.
 */
class MotionDetector(
    private val context: Context,
    private val sensitivityTenths: Int = 50,  // 2-200, default 50 = 5.0%
    private val onMotionDetected: () -> Unit
) : LifecycleOwner {

    companion object {
        private const val TAG = "MotionDetector"

        // Minimum brightness change per pixel to count as "changed"
        private const val PIXEL_CHANGE_THRESHOLD = 25

        // Debounce to prevent rapid triggers
        private const val MOTION_DEBOUNCE_MS = 2000L

        // Delay after starting before motion can trigger (allows camera to stabilize)
        private const val STABILIZATION_DELAY_MS = 2000L

        // Frame analysis rate (don't need every frame)
        private const val ANALYSIS_INTERVAL_MS = 250L

        // Downscale factor for analysis (reduces CPU usage)
        private const val DOWNSCALE_FACTOR = 8
    }

    // Lifecycle for CameraX
    private val lifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var isRunning = false

    // Previous frame data for comparison
    private var previousFrameData: ByteArray? = null
    private var lastAnalysisTime = 0L
    private var lastMotionTime = 0L
    private var startTime = 0L

    // Motion threshold as percentage (derived from sensitivityTenths)
    private val motionThresholdPercent: Double
        get() = sensitivityTenths / 10.0  // e.g., 50 -> 5.0%

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Start motion detection.
     */
    fun start() {
        if (isRunning) return

        // Check if device has a camera
        if (!hasCamera()) {
            Log.w(TAG, "No camera available on this device")
            return
        }

        Log.i(TAG, "Starting motion detection (threshold: ${motionThresholdPercent}%)")

        // Start lifecycle
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        cameraExecutor = Executors.newSingleThreadExecutor()
        startTime = System.currentTimeMillis()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraForAnalysis()
                isRunning = true
                Log.i(TAG, "Motion detection started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera for motion detection", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Stop motion detection.
     */
    fun stop() {
        if (!isRunning) return

        Log.i(TAG, "Stopping motion detection")

        isRunning = false
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED

        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding camera", e)
        }

        cameraExecutor?.shutdown()
        cameraExecutor = null
        cameraProvider = null
        previousFrameData = null
    }

    /**
     * Bind camera for image analysis only (no preview).
     */
    private fun bindCameraForAnalysis() {
        val cameraProvider = cameraProvider ?: return
        val executor = cameraExecutor ?: return

        // Try front camera first, fall back to back camera
        val cameraSelector = try {
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Front camera not available, trying back camera")
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
        }

        // Image analysis use case
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(executor) { imageProxy ->
            analyzeFrame(imageProxy)
            imageProxy.close()
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
            Log.i(TAG, "Camera bound for motion analysis")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera", e)
        }
    }

    /**
     * Analyze a frame for motion.
     */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (!isRunning) return

        val now = System.currentTimeMillis()

        // Rate limit analysis
        if (now - lastAnalysisTime < ANALYSIS_INTERVAL_MS) {
            return
        }
        lastAnalysisTime = now

        // Skip if still in stabilization period
        if (now - startTime < STABILIZATION_DELAY_MS) {
            return
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

            if (motionScore > motionThresholdPercent) {
                // Check debounce
                if (now - lastMotionTime > MOTION_DEBOUNCE_MS) {
                    lastMotionTime = now
                    Log.i(TAG, "Motion detected! Score: %.2f%% (threshold: %.2f%%)".format(
                        motionScore, motionThresholdPercent
                    ))

                    // Trigger callback on main thread
                    mainHandler.post {
                        onMotionDetected()
                    }
                }
            }
        }

        // Store current frame for next comparison
        previousFrameData = sampledData
    }

    /**
     * Downsample frame data for efficient comparison.
     */
    private fun sampleFrame(buffer: ByteBuffer, width: Int, height: Int): ByteArray {
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

    /**
     * Calculate percentage of pixels that changed significantly.
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
     * Check if device has any camera.
     */
    private fun hasCamera(): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    /**
     * Check if motion detector is running.
     */
    fun isRunning(): Boolean = isRunning
}

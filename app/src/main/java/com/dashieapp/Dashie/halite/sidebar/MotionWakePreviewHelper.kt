package com.dashieapp.Dashie.halite.sidebar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Helper class to manage camera preview and motion detection for the Motion Wake settings dialog.
 * Shows a live camera preview with real-time motion score updates and a motion graph.
 */
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class MotionWakePreviewHelper(
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "MotionWakePreview"
        private const val PEAK_WINDOW_MS = 500L  // Show peak from last 0.5 seconds
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var previewView: PreviewView? = null
    private var textMotionScore: TextView? = null
    private var textCameraUnavailable: TextView? = null
    private var cameraPreviewContainer: LinearLayout? = null
    private var motionGraphView: MotionGraphView? = null

    // Motion detection state
    private var lastBitmap: Bitmap? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    // Smoothing: store recent scores with timestamps
    private val recentScores = mutableListOf<Pair<Long, Double>>()
    private var currentThresholdTenths: Int = 50  // Default 5.0%

    /**
     * Initialize the preview helper with view references.
     * Call this when the dialog is created.
     */
    fun init(
        previewView: PreviewView,
        textMotionScore: TextView,
        textCameraUnavailable: TextView,
        cameraPreviewContainer: LinearLayout,
        motionGraphView: MotionGraphView? = null
    ) {
        this.previewView = previewView
        this.textMotionScore = textMotionScore
        this.textCameraUnavailable = textCameraUnavailable
        this.cameraPreviewContainer = cameraPreviewContainer
        this.motionGraphView = motionGraphView
    }

    /**
     * Set the current threshold for the graph display.
     */
    fun setThreshold(thresholdTenths: Int) {
        currentThresholdTenths = thresholdTenths
        motionGraphView?.setThreshold(thresholdTenths)
    }

    /**
     * Check if camera permission is granted.
     */
    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if device has a camera.
     */
    fun hasCamera(): Boolean {
        return activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    /**
     * Start the camera preview and motion detection.
     * Call this when camera mode is selected in the dialog.
     */
    fun startPreview() {
        if (isRunning) return

        // Check permission and camera availability
        if (!hasCameraPermission()) {
            showUnavailableState("Camera permission denied")
            return
        }

        if (!hasCamera()) {
            showUnavailableState("No camera available")
            return
        }

        Log.i(TAG, "Starting motion wake preview")
        isRunning = true
        recentScores.clear()

        // Show preview container
        cameraPreviewContainer?.visibility = View.VISIBLE
        textCameraUnavailable?.visibility = View.GONE

        // Initialize graph with current threshold
        motionGraphView?.setThreshold(currentThresholdTenths)
        motionGraphView?.clear()

        // Create camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Get camera provider
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraPreview()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider: ${e.message}")
                showUnavailableState("Camera error")
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    /**
     * Stop the camera preview and cleanup resources.
     * Call this when the dialog is dismissed or camera mode is deselected.
     */
    fun stopPreview() {
        if (!isRunning) return

        Log.i(TAG, "Stopping motion wake preview")
        isRunning = false

        // Unbind camera
        cameraProvider?.unbindAll()
        cameraProvider = null

        // Shutdown executor
        cameraExecutor?.shutdown()
        cameraExecutor = null

        // Clear last bitmap
        lastBitmap?.recycle()
        lastBitmap = null

        // Clear scores
        recentScores.clear()

        // Hide preview container
        cameraPreviewContainer?.visibility = View.GONE
    }

    /**
     * Bind camera preview and image analysis use cases.
     */
    private fun bindCameraPreview() {
        val cameraProvider = cameraProvider ?: return
        val previewView = previewView ?: return

        try {
            // Unbind any existing use cases
            cameraProvider.unbindAll()

            // Select front camera (more useful for motion detection on a dashboard)
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            // Create preview use case
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Create image analysis use case for motion detection
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor!!) { imageProxy ->
                        analyzeFrame(imageProxy)
                    }
                }

            // Bind use cases to lifecycle
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )

            Log.i(TAG, "Camera preview bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera: ${e.message}")
            // Try back camera as fallback
            tryBackCamera()
        }
    }

    /**
     * Try binding to back camera if front camera fails.
     */
    private fun tryBackCamera() {
        val cameraProvider = cameraProvider ?: return
        val previewView = previewView ?: return

        try {
            cameraProvider.unbindAll()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor!!) { imageProxy ->
                        analyzeFrame(imageProxy)
                    }
                }

            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )

            Log.i(TAG, "Back camera bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind back camera: ${e.message}")
            // Try external/USB camera as final fallback
            tryExternalCamera()
        }
    }

    /**
     * Try binding to any available camera (including USB/external cameras).
     * USB cameras don't have FRONT/BACK facing designations, so we enumerate
     * available cameras using Camera2 API and try each one.
     */
    private fun tryExternalCamera() {
        val cameraProvider = cameraProvider ?: return
        val previewView = previewView ?: return

        try {
            // Use Camera2 API to enumerate all available cameras
            val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList

            Log.i(TAG, "Found ${cameraIds.size} cameras via Camera2 API: ${cameraIds.toList()}")

            // Try each camera ID until one works
            for (cameraId in cameraIds) {
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    val facingStr = when (facing) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                        else -> "UNKNOWN ($facing)"
                    }
                    Log.i(TAG, "Trying camera $cameraId (facing: $facingStr)")

                    cameraProvider.unbindAll()

                    // Create a camera selector that matches this specific camera ID
                    val cameraSelector = CameraSelector.Builder()
                        .addCameraFilter { cameras ->
                            cameras.filter { cameraInfo ->
                                // CameraX camera info has an identifier that matches Camera2 camera ID
                                val cameraIdFromInfo = androidx.camera.camera2.interop.Camera2CameraInfo
                                    .from(cameraInfo).cameraId
                                cameraIdFromInfo == cameraId
                            }
                        }
                        .build()

                    val preview = Preview.Builder()
                        .build()
                        .also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor!!) { imageProxy ->
                                analyzeFrame(imageProxy)
                            }
                        }

                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )

                    Log.i(TAG, "Successfully bound camera $cameraId ($facingStr)")
                    return  // Success! Exit the loop
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to bind camera $cameraId: ${e.message}")
                    // Continue to try next camera
                }
            }

            // If we get here, none of the cameras worked
            Log.e(TAG, "Failed to bind any of the ${cameraIds.size} available cameras")
            showUnavailableState("Camera unavailable")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate cameras: ${e.message}")
            showUnavailableState("Camera unavailable")
        }
    }

    /**
     * Analyze a camera frame for motion detection.
     */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (!isRunning) {
            imageProxy.close()
            return
        }

        try {
            // Convert image to bitmap for motion analysis
            val bitmap = imageProxyToBitmap(imageProxy)

            if (bitmap != null) {
                // Calculate motion score by comparing to previous frame
                val motionScore = calculateMotionScore(bitmap)
                val now = System.currentTimeMillis()

                // Add to recent scores for smoothing
                synchronized(recentScores) {
                    recentScores.add(Pair(now, motionScore))
                    // Remove scores older than peak window
                    val cutoff = now - PEAK_WINDOW_MS
                    recentScores.removeAll { it.first < cutoff }
                }

                // Update UI on main thread
                handler.post {
                    // Add point to graph (raw score)
                    motionGraphView?.addDataPoint(motionScore)

                    // Display peak score from last 0.5 seconds
                    val peakScore = synchronized(recentScores) {
                        recentScores.maxOfOrNull { it.second } ?: 0.0
                    }
                    updateMotionScoreUI(peakScore)
                }

                // Keep current bitmap for next comparison
                lastBitmap?.recycle()
                lastBitmap = bitmap
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame analysis error: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Convert ImageProxy to Bitmap for analysis.
     * Uses downsampled resolution for performance.
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        try {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            // Create bitmap from RGBA data
            val width = imageProxy.width
            val height = imageProxy.height

            // Downsample for performance (use every 4th pixel)
            val sampleSize = 4
            val sampledWidth = width / sampleSize
            val sampledHeight = height / sampleSize

            val pixels = IntArray(sampledWidth * sampledHeight)
            val rowStride = imageProxy.planes[0].rowStride

            for (y in 0 until sampledHeight) {
                for (x in 0 until sampledWidth) {
                    val srcY = y * sampleSize
                    val srcX = x * sampleSize
                    val idx = srcY * rowStride + srcX * 4  // RGBA = 4 bytes

                    if (idx + 3 < bytes.size) {
                        val r = bytes[idx].toInt() and 0xFF
                        val g = bytes[idx + 1].toInt() and 0xFF
                        val b = bytes[idx + 2].toInt() and 0xFF
                        pixels[y * sampledWidth + x] = Color.rgb(r, g, b)
                    }
                }
            }

            return Bitmap.createBitmap(pixels, sampledWidth, sampledHeight, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to convert image: ${e.message}")
            return null
        }
    }

    /**
     * Calculate motion score by comparing current frame to previous frame.
     * Returns percentage of pixels that changed significantly (0.0 to 100.0).
     */
    private fun calculateMotionScore(currentBitmap: Bitmap): Double {
        val previousBitmap = lastBitmap ?: return 0.0

        // Ensure same dimensions
        if (currentBitmap.width != previousBitmap.width ||
            currentBitmap.height != previousBitmap.height) {
            return 0.0
        }

        val width = currentBitmap.width
        val height = currentBitmap.height
        val totalPixels = width * height

        // Sample pixels for performance (check every 2nd pixel)
        var changedPixels = 0
        val threshold = 30  // Pixel difference threshold

        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                val currentPixel = currentBitmap.getPixel(x, y)
                val previousPixel = previousBitmap.getPixel(x, y)

                val rDiff = abs(Color.red(currentPixel) - Color.red(previousPixel))
                val gDiff = abs(Color.green(currentPixel) - Color.green(previousPixel))
                val bDiff = abs(Color.blue(currentPixel) - Color.blue(previousPixel))

                val avgDiff = (rDiff + gDiff + bDiff) / 3
                if (avgDiff > threshold) {
                    changedPixels++
                }
            }
        }

        // Calculate percentage (adjust for sampling - we checked 1/4 of pixels)
        val sampledPixels = totalPixels / 4
        return (changedPixels.toDouble() / sampledPixels) * 100.0
    }

    /**
     * Update the motion score UI display.
     * Shows the peak score from the last 0.5 seconds.
     */
    private fun updateMotionScoreUI(peakScore: Double) {
        textMotionScore?.text = String.format("%.1f%%", peakScore)

        // Color based on relationship to threshold
        val thresholdPercent = currentThresholdTenths / 10.0
        val color = when {
            peakScore >= thresholdPercent -> Color.parseColor("#4CAF50")  // Green - would trigger
            peakScore >= thresholdPercent * 0.5 -> Color.parseColor("#FF9800")   // Orange - moderate
            else -> Color.parseColor("#757575")  // Gray - low motion
        }
        textMotionScore?.setTextColor(color)
    }

    /**
     * Show the unavailable state with a message.
     */
    private fun showUnavailableState(message: String) {
        cameraPreviewContainer?.visibility = View.VISIBLE
        textCameraUnavailable?.text = message
        textCameraUnavailable?.visibility = View.VISIBLE
        textMotionScore?.text = "N/A"
    }

    /**
     * Cleanup all resources. Call when the dialog is destroyed.
     */
    fun cleanup() {
        stopPreview()
        previewView = null
        textMotionScore = null
        textCameraUnavailable = null
        cameraPreviewContainer = null
        motionGraphView = null
    }
}

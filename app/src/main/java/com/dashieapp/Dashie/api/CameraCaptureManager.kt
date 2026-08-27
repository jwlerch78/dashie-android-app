package com.dashieapp.Dashie.api

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages camera capture for the getCamshot API command.
 *
 * Uses CameraX ImageCapture to take JPEG snapshots on demand.
 * This is separate from the motion detection camera to allow independent control.
 */
class CameraCaptureManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "CameraCaptureManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService? = null
    private var isInitialized = false

    // Pending capture callback
    private var pendingCaptureCallback: ((ByteArray?) -> Unit)? = null

    /**
     * Initialize the camera for capturing.
     * Call this during app startup.
     */
    fun initialize() {
        if (isInitialized) return

        Log.i(TAG, "Initializing camera capture")
        cameraExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraForCapture()
                isInitialized = true
                Log.i(TAG, "Camera capture initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize camera capture: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraForCapture() {
        val provider = cameraProvider ?: return

        // Build ImageCapture use case
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        // Try front camera first (typical for kiosk/tablet facing user)
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

        try {
            // Note: If motion detection is also using the camera, this might conflict
            // For now, we don't unbindAll() to avoid stopping motion detection
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageCapture
            )
            Log.i(TAG, "Camera bound for capture")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera for capture: ${e.message}")
        }
    }

    /**
     * Capture a JPEG image and return the bytes via callback.
     * Returns null if capture fails.
     */
    fun captureImage(callback: (ByteArray?) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            Log.e(TAG, "ImageCapture not initialized")
            callback(null)
            return
        }

        val executor = cameraExecutor
        if (executor == null) {
            Log.e(TAG, "Camera executor not available")
            callback(null)
            return
        }

        Log.d(TAG, "Taking picture...")

        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)

                    Log.i(TAG, "Image captured: ${bytes.size} bytes")

                    // Return on main thread
                    ContextCompat.getMainExecutor(context).execute {
                        callback(bytes)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing captured image: ${e.message}")
                    ContextCompat.getMainExecutor(context).execute {
                        callback(null)
                    }
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Image capture failed: ${exception.message}")
                ContextCompat.getMainExecutor(context).execute {
                    callback(null)
                }
            }
        })
    }

    /**
     * Check if camera capture is available
     */
    fun isAvailable(): Boolean = isInitialized && imageCapture != null

    /**
     * Clean up resources
     */
    fun destroy() {
        Log.i(TAG, "Destroying camera capture manager")
        try {
            cameraProvider?.unbind(imageCapture)
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding camera: ${e.message}")
        }
        cameraExecutor?.shutdown()
        cameraExecutor = null
        imageCapture = null
        isInitialized = false
    }
}

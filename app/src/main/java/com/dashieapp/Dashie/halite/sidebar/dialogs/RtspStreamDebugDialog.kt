package com.dashieapp.Dashie.halite.sidebar.dialogs

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.diagnostics.MediaCodecCapabilities

/**
 * RTSP Stream Debug dialog.
 * Shows a live camera preview (via RTSP snapshot polling) alongside stream
 * configuration, encoder capabilities, camera info, compatibility checks,
 * and device details. Helps users self-diagnose RTSP streaming issues.
 *
 * Uses the same snapshot approach as the motion detection preview in
 * DialogPickers — polls getCameraPreviewFrame() for JPEG bytes from the
 * RTSP server, so it never steals the camera from the active stream.
 */
class RtspStreamDebugDialog(
    private val activity: Activity,
    private val halitePrefs: HalitePreferences
) {
    companion object {
        private const val TAG = "RtspStreamDebug"
        private const val PREVIEW_INTERVAL_MS = 1000L
    }

    // Callbacks to query live server state
    var isStreamRunning: (() -> Boolean)? = null
    var getStreamUrl: (() -> String)? = null
    var getClientCount: (() -> Int)? = null
    var getCameraPreviewFrame: (() -> ByteArray?)? = null

    // Preview polling state
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var mainHandler = Handler(Looper.getMainLooper())
    private var isPolling = false

    /**
     * Show the RTSP stream debug dialog.
     */
    fun show() {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_rtsp_debug, null)

        val imagePreview = dialogView.findViewById<ImageView>(R.id.imagePreview)
        val textCameraUnavailable = dialogView.findViewById<TextView>(R.id.textCameraUnavailable)
        val textStreamStatus = dialogView.findViewById<TextView>(R.id.textStreamStatus)
        val textEncoderInfo = dialogView.findViewById<TextView>(R.id.textEncoderInfo)
        val textCameraInfo = dialogView.findViewById<TextView>(R.id.textCameraInfo)
        val textCompatibilityInfo = dialogView.findViewById<TextView>(R.id.textCompatibilityInfo)
        val textDeviceInfo = dialogView.findViewById<TextView>(R.id.textDeviceInfo)
        val buttonCopy = dialogView.findViewById<Button>(R.id.buttonCopy)
        val buttonClose = dialogView.findViewById<Button>(R.id.buttonClose)

        // Populate all info sections
        textStreamStatus.text = buildStreamStatus()
        textEncoderInfo.text = buildEncoderInfo()
        textCameraInfo.text = buildCameraInfo()
        textCompatibilityInfo.text = buildCompatibilityInfo()
        textDeviceInfo.text = buildDeviceInfo()

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialog.setOnDismissListener {
            stopPreviewPolling()
        }

        // Copy button — copies all sections as text
        buttonCopy.setOnClickListener {
            val fullText = buildFullReport(
                textStreamStatus.text.toString(),
                textEncoderInfo.text.toString(),
                textCameraInfo.text.toString(),
                textCompatibilityInfo.text.toString(),
                textDeviceInfo.text.toString()
            )
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("RTSP Debug Info", fullText))
            Toast.makeText(activity, "Debug info copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        buttonClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)

        // Start snapshot polling for live preview
        val streaming = isStreamRunning?.invoke() ?: false
        if (streaming && getCameraPreviewFrame != null) {
            startPreviewPolling(imagePreview, textCameraUnavailable)
        } else if (!streaming) {
            textCameraUnavailable.text = "Stream not active"
            textCameraUnavailable.visibility = View.VISIBLE
        } else {
            textCameraUnavailable.text = "Preview not available"
            textCameraUnavailable.visibility = View.VISIBLE
        }
    }

    // ========== RTSP Snapshot Preview ==========

    private fun startPreviewPolling(imagePreview: ImageView, unavailableText: TextView) {
        isPolling = true
        backgroundThread = HandlerThread("RtspDebugPreview").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        fun pollFrame() {
            if (!isPolling) return

            backgroundHandler?.post {
                try {
                    val frame = getCameraPreviewFrame?.invoke()
                    if (frame != null) {
                        val bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.size)
                        // Rotate 180° to match RTSP stream orientation (front camera)
                        val matrix = Matrix().apply { postRotate(180f) }
                        val rotated = android.graphics.Bitmap.createBitmap(
                            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                        )
                        bitmap.recycle()

                        mainHandler.post {
                            imagePreview.setImageBitmap(rotated)
                            imagePreview.visibility = View.VISIBLE
                            unavailableText.visibility = View.GONE
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Preview frame error: ${e.message}")
                }

                // Schedule next poll
                if (isPolling) {
                    mainHandler.postDelayed(::pollFrame, PREVIEW_INTERVAL_MS)
                }
            }
        }

        pollFrame()
    }

    private fun stopPreviewPolling() {
        isPolling = false
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }

    // ========== Info Builders ==========

    private fun buildStreamStatus(): String {
        val running = isStreamRunning?.invoke() ?: false
        val url = getStreamUrl?.invoke() ?: "N/A"
        val clients = getClientCount?.invoke() ?: 0

        return buildString {
            appendLine("Status:     ${if (running) "Streaming" else "Not streaming"}")
            appendLine("URL:        $url")
            appendLine("Clients:    $clients")
            appendLine("Enabled:    ${halitePrefs.camera.rtspEnabled}")
            appendLine("Resolution: ${halitePrefs.camera.rtspResolutionDisplay} (${halitePrefs.camera.rtspResolutionWidth}x${halitePrefs.camera.rtspResolutionHeight})")
            appendLine("Frame rate: ${halitePrefs.camera.rtspFps} fps")
            appendLine("Bitrate:    ${halitePrefs.camera.rtspBitrate / 1_000_000}Mbps")
            appendLine("SW encode:  ${halitePrefs.camera.rtspForceSoftwareEncoding}")
            append("Mirror off: ${halitePrefs.camera.rtspDisableMirrorCorrection}")
        }
    }

    private fun buildEncoderInfo(): String {
        val caps = MediaCodecCapabilities.getH264EncoderCapabilities()
            ?: return "Could not query encoder capabilities"

        return buildString {
            appendLine("Name:       ${caps.encoderName}")
            appendLine("Hardware:   ${if (caps.isHardwareAccelerated) "Yes" else "No (software)"}")
            appendLine("Profiles:   ${caps.supportedProfiles.joinToString(", ")}")
            appendLine("Resolution: ${caps.widthRange.first}-${caps.widthRange.last} x ${caps.heightRange.first}-${caps.heightRange.last}")
            appendLine("Alignment:  ${caps.widthAlignment}x${caps.heightAlignment}")
            appendLine("FPS range:  ${caps.supportedFrameRates.first}-${caps.supportedFrameRates.last}")
            appendLine("Bitrate:    ${caps.bitrateRange.first / 1000}k - ${caps.bitrateRange.last / 1_000_000}M")
            append("Surface in: ${if (caps.supportsSurfaceInput) "Yes" else "No"}")
        }
    }

    private fun buildCameraInfo(): String {
        val caps = MediaCodecCapabilities.getCameraCapabilities(activity, useFrontCamera = true)
            ?: return "Could not query camera capabilities"

        return buildString {
            appendLine("Camera ID:    ${caps.cameraId} (${caps.facing})")
            appendLine("Orientation:  ${caps.sensorOrientation}°")
            appendLine("Ratios:       ${caps.nativeAspectRatios.joinToString(", ")}")
            appendLine("Sizes (top 6):")
            caps.supportedSizes.take(6).forEach { size ->
                appendLine("  ${size.width}x${size.height}")
            }
            append("Total sizes:  ${caps.supportedSizes.size}")
        }
    }

    private fun buildCompatibilityInfo(): String {
        val width = halitePrefs.camera.rtspResolutionWidth
        val height = halitePrefs.camera.rtspResolutionHeight
        val fps = halitePrefs.camera.rtspFps
        val bitrate = halitePrefs.camera.rtspBitrate

        val diag = MediaCodecCapabilities.diagnoseVideoConfig(
            activity, width, height, fps, bitrate, useFrontCamera = true
        )

        if (diag.isLikelyOk && diag.warnings.isEmpty()) {
            return "No issues detected\nConfiguration looks good for this device."
        }

        return buildString {
            if (diag.issues.isNotEmpty()) {
                appendLine("Issues:")
                diag.issues.forEach { appendLine("  ! $it") }
            }
            if (diag.warnings.isNotEmpty()) {
                appendLine("Warnings:")
                diag.warnings.forEach { appendLine("  ~ $it") }
            }
            if (diag.recommendations.isNotEmpty()) {
                appendLine("Suggestions:")
                diag.recommendations.forEach { appendLine("  > $it") }
            }
        }.trimEnd()
    }

    private fun buildDeviceInfo(): String {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)

        return buildString {
            appendLine("Model:      ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android:    ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Board:      ${Build.BOARD}")
            append("Heap:       ${usedMb}MB / ${maxMb}MB")
        }
    }

    // ========== Report ==========

    private fun buildFullReport(
        streamStatus: String,
        encoderInfo: String,
        cameraInfo: String,
        compatibilityInfo: String,
        deviceInfo: String
    ): String {
        return buildString {
            appendLine("=== RTSP Stream Debug ===")
            appendLine()
            appendLine("--- Stream Status ---")
            appendLine(streamStatus)
            appendLine()
            appendLine("--- Encoder ---")
            appendLine(encoderInfo)
            appendLine()
            appendLine("--- Camera ---")
            appendLine(cameraInfo)
            appendLine()
            appendLine("--- Compatibility ---")
            appendLine(compatibilityInfo)
            appendLine()
            appendLine("--- Device ---")
            appendLine(deviceInfo)
        }
    }
}

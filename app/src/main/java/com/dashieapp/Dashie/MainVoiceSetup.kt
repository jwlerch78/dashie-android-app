package com.dashieapp.Dashie

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.core.content.ContextCompat
import com.dashieapp.Dashie.util.DiagnosticToastController
import com.dashieapp.Dashie.voice.VoiceAssistantCoordinator
import com.dashieapp.Dashie.voice.VoiceAssistantSetup
import com.dashieapp.Dashie.voice.wakeword.WakeWordSampleRecorder

/**
 * Handles voice assistant initialization and wake word sample recording for MainActivity.
 * Extracted from MainActivity to reduce its size and improve maintainability.
 *
 * Responsibilities:
 * - Initialize VoiceAssistantCoordinator with callbacks
 * - Initialize WakeWordSampleRecorder
 * - Handle wake word sample recording requests
 * - Provide WebView notification callbacks
 */
class MainVoiceSetup(
    private val context: Context,
    private val webView: () -> WebView,
    private val webViewBridge: MainWebViewBridge,
    private val callbacks: Callbacks
) {
    companion object {
        private const val TAG = "MainVoiceSetup"
    }

    interface Callbacks {
        fun getDashieServiceManager(): com.dashieapp.Dashie.api.DashieServiceManager?
    }

    // Voice Assistant Coordinator - Final refactored architecture
    private var voiceAssistant: VoiceAssistantCoordinator? = null

    // Wake Word Sample Recorder
    private var wakeWordSampleRecorder: WakeWordSampleRecorder? = null

    /**
     * Get the voice assistant coordinator.
     */
    fun getVoiceAssistant(): VoiceAssistantCoordinator? = voiceAssistant

    /**
     * Initialize the voice assistant coordinator with all callbacks.
     * Creates a new VoiceAssistantCoordinator and sets up WebView notification callbacks.
     */
    fun initializeVoiceAssistant() {
        val coordinator = VoiceAssistantCoordinator(context)
        voiceAssistant = coordinator

        // Use helper to setup all callbacks
        VoiceAssistantSetup.setupCallbacks(coordinator, object : VoiceAssistantSetup.WebViewNotifier {
            override fun notifyWebView(event: String, data: String) {
                webViewBridge.notifyWebView(event, data)
                // Also notify test interface for speech results
                if (event == "speechResult") {
                    (context as? android.app.Activity)?.runOnUiThread {
                        webView().evaluateJavascript(
                            // BUNDLE-EXEMPT: onDashieSpeechResult — JS voice lane callback; kiosk voice is the native pipeline
                            "if (window.onDashieSpeechResult) window.onDashieSpeechResult('${data.replace("'", "\\'")}');",
                            null
                        )
                    }
                }
            }
            override fun sendAudioToWebView(audioData: ByteArray) = webViewBridge.sendAudioToWebView(audioData)
            override fun sendAudioLevelToWebView(amplitude: Float) = webViewBridge.sendAudioLevelToWebView(amplitude)
            override fun sendHeartbeatToWebView(confidence: Float, volume: Float) = webViewBridge.sendHeartbeatToWebView(confidence, volume)
            override fun sendLiveSampleToWebView(wavData: ByteArray, metadata: String) = webViewBridge.sendLiveSampleToWebView(wavData, metadata)
        })

        // Share audio buffer with RTSP server (if available)
        // This allows RTSP to share the microphone with wake word detection,
        // avoiding audio HAL conflicts on devices like Google TV
        val sharedBuffer = coordinator.getSharedAudioBuffer()
        val dashieServiceManager = callbacks.getDashieServiceManager()
        Log.i(TAG, "SharedAudioBuffer check: buffer=${if (sharedBuffer != null) "exists" else "null"}, dashieServiceManager=${if (dashieServiceManager != null) "exists" else "null"}")
        if (sharedBuffer != null) {
            dashieServiceManager?.setSharedAudioBuffer(sharedBuffer)
            Log.i(TAG, "✓ SharedAudioBuffer passed to RTSP server for audio sharing")
        } else {
            Log.w(TAG, "⚠️ SharedAudioBuffer not available yet - RTSP will use dedicated mic")
        }
    }

    /**
     * Initialize the wake word sample recorder with callbacks.
     * Called lazily when sample recording is first requested.
     */
    private fun initializeWakeWordSampleRecorder() {
        if (wakeWordSampleRecorder == null) {
            wakeWordSampleRecorder = WakeWordSampleRecorder(context)

            // Setup callbacks
            wakeWordSampleRecorder?.onRecordingProgress = { secondsRemaining ->
                // Show toast with countdown (only if diagnostic toasts enabled)
                DiagnosticToastController.showToast("Recording... ${secondsRemaining}s")
            }

            wakeWordSampleRecorder?.onRecordingComplete = { success, wavData, metadata, error ->
                (context as? android.app.Activity)?.runOnUiThread {
                    // Notify web - send WAV data and metadata directly
                    if (success && wavData != null && metadata != null) {
                        // Convert WAV data to base64 for JavaScript
                        val base64Audio = android.util.Base64.encodeToString(wavData, android.util.Base64.NO_WRAP)

                        // Escape metadata JSON for JavaScript
                        val escapedMetadata = metadata.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

                        webView().evaluateJavascript(
                            // BUNDLE-EXEMPT: onWakeWordSampleRecorded — wake-word sample collection UI is a webapp developer page
                            "if (window.onWakeWordSampleRecorded) window.onWakeWordSampleRecorded('$base64Audio', '$escapedMetadata');",
                            null
                        )
                    } else {
                        val escapedError = (error ?: "Unknown error").replace("'", "\\'")
                        // BUNDLE-EXEMPT: onWakeWordSampleError — wake-word sample collection is a webapp dev-console tool (paired with onWakeWordSampleRecorded)
                        webView().evaluateJavascript(
                            "if (window.onWakeWordSampleError) window.onWakeWordSampleError('$escapedError');",
                            null
                        )
                    }
                }
            }
        }
    }

    /**
     * Handle wake word sample recording request.
     * Called from JavaScript via DashieNative.recordWakeWordSample().
     */
    fun handleRecordWakeWordSample() {
        Log.d(TAG, "handleRecordWakeWordSample() called")
        (context as? android.app.Activity)?.runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Microphone permission required")
                    return@runOnUiThread
                }
            }
            initializeWakeWordSampleRecorder()
            Handler(Looper.getMainLooper()).postDelayed({
                wakeWordSampleRecorder?.recordSample()
            }, 1000)
        }
    }

    /**
     * Shutdown the voice assistant and release resources.
     * Should be called from Activity.onDestroy().
     */
    fun shutdown() {
        voiceAssistant?.shutdown()
        voiceAssistant = null
    }

    /**
     * Get the wake word memory status for diagnostics.
     */
    fun getWakeWordMemoryStatus(): String? {
        return voiceAssistant?.getWakeWordMemoryStatus()
    }
}

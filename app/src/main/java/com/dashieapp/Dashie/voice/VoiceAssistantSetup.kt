package com.dashieapp.Dashie.voice

import android.content.Context
import android.util.Log
import android.webkit.WebView

/**
 * Helper class for setting up VoiceAssistantCoordinator callbacks.
 * Extracts the verbose callback setup from MainActivity.
 */
object VoiceAssistantSetup {

    private const val TAG = "VoiceAssistantSetup"

    /**
     * Callbacks for WebView notification
     */
    interface WebViewNotifier {
        fun notifyWebView(event: String, data: String)
        fun sendAudioToWebView(audioData: ByteArray)
        fun sendAudioLevelToWebView(amplitude: Float)
        fun sendHeartbeatToWebView(confidence: Float, volume: Float)
        fun sendLiveSampleToWebView(wavData: ByteArray, metadata: String)
    }

    /**
     * Initialize all VoiceAssistantCoordinator callbacks.
     * Call this after creating the coordinator.
     *
     * @param coordinator The VoiceAssistantCoordinator to configure
     * @param notifier Interface for WebView notifications
     */
    fun setupCallbacks(
        coordinator: VoiceAssistantCoordinator,
        notifier: WebViewNotifier
    ) {
        // Initialize TTS
        coordinator.initializeTTS()
        coordinator.onTTSReady = {
            Log.d(TAG, "Voice assistant TTS ready")
        }
        coordinator.onTTSError = { error ->
            Log.e(TAG, "Voice assistant TTS error: $error")
        }

        // Initialize Speech Recognition
        coordinator.initializeSpeechRecognition()

        // Initialize Deepgram connection proactively (prevents missing first utterance)
        coordinator.initializeDeepgram()
        coordinator.onListeningStarted = {
            Log.d(TAG, "Started listening")
            notifier.notifyWebView("listeningStarted", "")
        }
        coordinator.onListeningEnded = {
            Log.d(TAG, "Stopped listening")
            notifier.notifyWebView("listeningEnded", "")
        }
        coordinator.onSpeechResult = { text ->
            Log.d(TAG, "Speech result: $text")
            notifier.notifyWebView("speechResult", text)
        }
        coordinator.onSpeechError = { error ->
            Log.e(TAG, "Speech error: $error")
            notifier.notifyWebView("speechError", error)
        }
        coordinator.onPartialResult = { text ->
            Log.d(TAG, "Partial result: $text")
            notifier.notifyWebView("partialResult", text)
        }

        // Initialize Wake Word Detection (initialize only — no auto-start, see below)
        Log.d(TAG, "🔍 Initializing legacy wake word (no auto-start)")
        coordinator.initializeWakeWord()
        coordinator.onWakeWordDetected = { confidence ->
            Log.d(TAG, "Wake word detected! Confidence: ${"%.1f".format(confidence * 100)}%")
            notifier.notifyWebView("wakeWordDetected", confidence.toString())
        }
        coordinator.onWakeWordError = { error ->
            Log.e(TAG, "Wake word error: $error")
            notifier.notifyWebView("wakeWordError", error)
        }

        // NO auto-start. The auto-start that lived here ("to test inference" — a diagnostics
        // leftover) held a second AudioRecord + ran this legacy wake engine for the process
        // lifetime on every firetv-flavor launch and after any onboarding mic grant, contending
        // with the Halite pipeline's shared capture (voice master plan WS-H.0). Detection still
        // starts on demand via DashieNative.startWakeWordDetection() (settings test harness).

        // Cloud STT Audio Capture
        coordinator.onAudioDataCaptured = { audioData ->
            Log.d(TAG, "Audio data captured: ${audioData.size} bytes")
            notifier.sendAudioToWebView(audioData)
        }

        // Audio Level Visualization (for mic test)
        coordinator.onAudioLevel = { amplitude ->
            notifier.sendAudioLevelToWebView(amplitude)
        }

        // Heartbeat callback (2x per second with max confidence/volume)
        coordinator.onHeartbeat = { confidence, volume ->
            notifier.sendHeartbeatToWebView(confidence, volume)
        }

        // Live sample collection callback (beta feature for training data)
        coordinator.onLiveSampleCollected = { wavData, metadata ->
            notifier.sendLiveSampleToWebView(wavData, metadata)
        }
    }
}

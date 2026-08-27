package com.dashieapp.Dashie.voice.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * SpeechRecognitionManager
 * Handles Android's built-in speech recognition (local/cloud)
 *
 * Extracted from VoiceAssistantManager for better separation of concerns
 */
class SpeechRecognitionManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val TAG = "SpeechRecognitionMgr"
    private val handler = Handler(Looper.getMainLooper())

    // Callbacks for speech recognition events
    var onListeningStarted: (() -> Unit)? = null
    var onListeningEnded: (() -> Unit)? = null
    var onSpeechResult: ((String) -> Unit)? = null
    var onSpeechError: ((String) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onAudioLevelChanged: ((Float) -> Unit)? = null

    /**
     * Initialize Speech Recognition
     * @return true if initialization successful, false otherwise
     */
    fun initialize(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            // ⚠️ NOT an error, and NOT user-facing. This runs at every app start on every device,
            // and it is false by design on Fire OS / de-Googled builds — which ship no
            // RecognitionService but do have working microphones served by other providers.
            // Firing onSpeechError here pushed "Speech recognition not available" to the WebView
            // at boot on devices whose voice works (measured: Echo Show 5, Fire HD 8).
            //
            // The caller gets `false` and decides. Anything that genuinely NEEDS this recognizer
            // — i.e. startListening() below — still reports a real failure at the point of use,
            // which is where a user-facing message belongs.
            Log.i(TAG, "SpeechRecognizer unavailable on this device — no RecognitionService " +
                    "registered. Callers should fall back to the STT provider chain.")
            return false
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
                isListening = true
                handler.post { onListeningStarted?.invoke() }
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Beginning of speech detected")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Audio level changed
                Log.v(TAG, "Audio level: $rmsdB dB")
                onAudioLevelChanged?.invoke(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Audio buffer received (not used currently)
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech")
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                val errorMessage = getErrorMessage(error)
                Log.e(TAG, "Speech recognition error: $errorMessage")
                handler.post {
                    onSpeechError?.invoke(errorMessage)
                    onListeningEnded?.invoke()
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val bestMatch = matches[0]
                    Log.d(TAG, "Speech result: $bestMatch")
                    handler.post {
                        onSpeechResult?.invoke(bestMatch)
                        onListeningEnded?.invoke()
                    }
                } else {
                    handler.post { onListeningEnded?.invoke() }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val partial = matches[0]
                    Log.d(TAG, "Partial result: $partial")
                    handler.post { onPartialResult?.invoke(partial) }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // Custom events (not used currently)
            }
        })

        Log.d(TAG, "Speech recognition initialized")
        return true
    }

    /**
     * Start listening for speech input
     * @param partialResults Enable partial results (default: true)
     * @param maxResults Maximum number of results to return (default: 1)
     */
    fun startListening(partialResults: Boolean = true, maxResults: Int = 1) {
        if (speechRecognizer == null) {
            Log.w(TAG, "Speech recognizer not initialized")
            onSpeechError?.invoke("Speech recognizer not initialized")
            return
        }

        if (isListening) {
            Log.w(TAG, "Already listening")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults)
        }

        Log.d(TAG, "Starting speech recognition...")
        speechRecognizer?.startListening(intent)
    }

    /**
     * Stop listening for speech input
     * Processes any speech captured so far
     */
    fun stopListening() {
        if (isListening) {
            Log.d(TAG, "Stopping speech recognition")
            speechRecognizer?.stopListening()
            isListening = false
        }
    }

    /**
     * Cancel speech recognition
     * Discards any speech captured
     */
    fun cancelListening() {
        if (isListening) {
            Log.d(TAG, "Canceling speech recognition")
            speechRecognizer?.cancel()
            isListening = false
        }
    }

    /**
     * Check if currently listening
     * @return true if listening, false otherwise
     */
    fun isCurrentlyListening(): Boolean {
        return isListening
    }

    /**
     * Check if speech recognition is available on this device
     * @return true if available, false otherwise
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    /**
     * Get human-readable error message for error code
     * @param error Error code from SpeechRecognizer
     * @return Human-readable error message
     */
    private fun getErrorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown error: $error"
        }
    }

    /**
     * Clean up speech recognition resources
     * Call this when done using speech recognition
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down speech recognition")
        speechRecognizer?.destroy()
        speechRecognizer = null
        isListening = false
    }
}

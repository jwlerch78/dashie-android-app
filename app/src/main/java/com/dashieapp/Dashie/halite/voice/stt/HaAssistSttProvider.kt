package com.dashieapp.Dashie.halite.voice.stt

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.halite.voice.HaAssistClient
import com.dashieapp.Dashie.halite.voice.VoiceActivityDetector

/**
 * Home Assistant Assist STT Provider
 *
 * Uses Home Assistant's Assist pipeline for speech-to-text.
 * The Assist pipeline typically runs local Whisper for STT.
 *
 * This provider extracts just the STT result from the Assist pipeline,
 * allowing the overlay's NLP to handle intent processing.
 *
 * Flow:
 * 1. Connect to HA WebSocket with auth token
 * 2. Start Assist pipeline (STT-only mode)
 * 3. Stream audio to HA
 * 4. Receive STT result
 * 5. Return transcript (not the intent)
 */
class HaAssistSttProvider : SttProvider {
    companion object {
        private const val TAG = "HaAssistStt"
    }

    override val providerId = "ha_assist"
    override val displayName = "Home Assistant (Whisper)"

    // Configuration
    private var haUrl: String = ""
    private var haToken: String = ""
    private var haTokenProvider: (() -> String)? = null
    private var pipelineId: String? = null

    // The HA token rotates ~every 30 min — resolve it when the per-session
    // HaAssistClient is created, never from the init-time snapshot ("Invalid
    // access token" on every HA STT call ~30 min after app start, 2026-07-13).
    // updateToken() remains as a manual override; the live provider wins.
    private fun currentToken(): String =
        haTokenProvider?.invoke()?.takeIf { it.isNotEmpty() } ?: haToken

    // Components
    private var assistClient: HaAssistClient? = null
    private var vad: VoiceActivityDetector? = null
    private val handler = Handler(Looper.getMainLooper())

    // Session state
    private var listener: SttListener? = null
    private var isSessionActive = false
    private var hasReceivedResult = false

    // Audio streaming
    private var isStreaming = false

    // Watchdog for timeout
    private var processingWatchdogRunnable: Runnable? = null
    private val PROCESSING_TIMEOUT_MS = 10000L

    override fun isAvailable(): Boolean {
        return haUrl.isNotEmpty() && currentToken().isNotEmpty()
    }

    override suspend fun initialize(config: SttConfig): Boolean {
        haUrl = config.haUrl ?: ""
        haToken = config.haToken ?: ""
        haTokenProvider = config.haTokenProvider
        pipelineId = config.haPipelineId

        // Initialize VAD for end-of-speech detection
        vad = VoiceActivityDetector().apply {
            setSilenceTimeout(config.endOfSpeechTimeoutMs)
            setMinSpeechDuration(300L)
            onEndOfSpeech = {
                handleEndOfSpeech()
            }
        }

        Log.i(TAG, "Initialized with HA URL: $haUrl, pipeline: ${pipelineId ?: "default"}")
        return isAvailable()
    }

    override fun startSession(listener: SttListener) {
        if (!isAvailable()) {
            listener.onError("HA not configured", isRecoverable = false)
            return
        }

        if (isSessionActive) {
            Log.w(TAG, "Session already active, cancelling previous")
            cancelSession()
        }

        this.listener = listener
        isSessionActive = true
        hasReceivedResult = false
        isStreaming = false

        Log.i(TAG, "Starting STT session")

        // Reset VAD
        vad?.reset()

        // Create new Assist client — with the CURRENT token (rotates ~30 min).
        assistClient = HaAssistClient(haUrl, currentToken()).apply {
            onAuthSuccess = {
                Log.d(TAG, "HA auth successful, starting STT pipeline")
                // Start pipeline in STT-only mode (endAtStt = true to not process intent)
                // Note: We use endAtIntent=true to get faster response - we only need the transcript
                startAudioPipeline(endAtIntent = true, pipelineId = this@HaAssistSttProvider.pipelineId)
            }

            onAuthFailed = { error ->
                Log.e(TAG, "HA auth failed: $error")
                notifyError("Authentication failed: $error", isRecoverable = true)
            }

            onPipelineStarted = { handlerId ->
                Log.d(TAG, "Pipeline started, handler=$handlerId")
                isStreaming = true
                handler.post {
                    listener.onSessionStarted()
                }
            }

            onSttStart = {
                Log.d(TAG, "STT started")
            }

            onSttEnd = { text ->
                Log.d(TAG, "STT result: $text")
                cancelProcessingWatchdog()

                if (text.isNotEmpty()) {
                    hasReceivedResult = true
                    handler.post {
                        this@HaAssistSttProvider.listener?.onFinalResult(text)
                        endSession()
                    }
                }
            }

            onSttNoText = {
                Log.d(TAG, "STT: No speech detected")
                cancelProcessingWatchdog()
                handler.post {
                    this@HaAssistSttProvider.listener?.onNoSpeechDetected()
                    endSession()
                }
            }

            // We don't need intent result for STT-only mode, but handle it in case
            onIntentEnd = { intent, _, _ ->
                Log.d(TAG, "Intent received (ignored): $intent")
                // If we haven't received STT result yet, use the intent as a trigger to end
                if (!hasReceivedResult) {
                    cancelProcessingWatchdog()
                    endSession()
                }
            }

            onRunEnd = {
                Log.d(TAG, "Pipeline run ended")
                cancelProcessingWatchdog()
                if (isSessionActive && !hasReceivedResult) {
                    handler.post {
                        this@HaAssistSttProvider.listener?.onNoSpeechDetected()
                        endSession()
                    }
                }
            }

            onError = { error ->
                Log.e(TAG, "Pipeline error: $error")
                notifyError(error, isRecoverable = true)
            }

            onDisconnected = { reason ->
                Log.d(TAG, "Disconnected: $reason")
                if (isSessionActive) {
                    notifyError("Disconnected: $reason", isRecoverable = true)
                }
            }
        }

        // Connect to HA
        assistClient?.connect()
    }

    override fun streamAudio(audioData: ByteArray) {
        if (!isSessionActive || !isStreaming) {
            return
        }

        // Process through VAD for end-of-speech detection
        vad?.processAudio(audioData)

        // Stream to HA
        assistClient?.streamAudio(audioData)
    }

    override fun endAudioStream() {
        if (!isSessionActive || !isStreaming) {
            return
        }

        Log.d(TAG, "End of audio stream signaled")
        isStreaming = false

        // Signal end to HA
        assistClient?.endAudioStream()

        // Start watchdog in case HA doesn't respond
        startProcessingWatchdog()
    }

    override fun cancelSession() {
        if (!isSessionActive) {
            return
        }

        Log.d(TAG, "Cancelling session")
        endSession()
    }

    override fun release() {
        cancelSession()
        vad = null
    }

    private fun handleEndOfSpeech() {
        if (!isSessionActive) {
            return
        }

        Log.d(TAG, "VAD detected end of speech")
        endAudioStream()
    }

    private fun endSession() {
        isSessionActive = false
        isStreaming = false

        cancelProcessingWatchdog()

        // Disconnect from HA
        assistClient?.disconnect()
        assistClient = null

        handler.post {
            listener?.onSessionEnded()
            listener = null
        }
    }

    private fun notifyError(error: String, isRecoverable: Boolean) {
        cancelProcessingWatchdog()
        handler.post {
            listener?.onError(error, isRecoverable)
            endSession()
        }
    }

    private fun startProcessingWatchdog() {
        cancelProcessingWatchdog()

        processingWatchdogRunnable = Runnable {
            if (isSessionActive && !hasReceivedResult) {
                Log.w(TAG, "Processing watchdog timeout")
                handler.post {
                    listener?.onNoSpeechDetected()
                    endSession()
                }
            }
        }

        handler.postDelayed(processingWatchdogRunnable!!, PROCESSING_TIMEOUT_MS)
        Log.d(TAG, "Started processing watchdog (${PROCESSING_TIMEOUT_MS}ms)")
    }

    private fun cancelProcessingWatchdog() {
        processingWatchdogRunnable?.let {
            handler.removeCallbacks(it)
            processingWatchdogRunnable = null
        }
    }

    /**
     * Update the HA auth token (e.g., after refresh)
     */
    fun updateToken(token: String) {
        haToken = token
    }

    /**
     * Update the HA URL
     */
    fun updateHaUrl(url: String) {
        haUrl = url
    }

    /**
     * Set the voice pipeline ID to use
     */
    fun setPipelineId(id: String?) {
        pipelineId = id
    }
}

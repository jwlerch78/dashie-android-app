package com.dashieapp.Dashie.voice.stt

import android.util.Log
import java.util.concurrent.Executors
import com.dashieapp.Dashie.audio.DeepgramStreamingSTT

/**
 * StreamingSTTManager
 * Handles Deepgram WebSocket streaming for real-time speech-to-text
 *
 * Extracted from VoiceAssistantManager for better separation of concerns
 *
 * Features:
 * - Persistent WebSocket connection pooling (eliminates connection latency)
 * - Real-time transcription with partial results
 * - Deepgram VAD events (SpeechStarted, UtteranceEnd)
 * - Automatic reconnection on connection loss
 * - KeepAlive for maintaining idle connections
 */
class StreamingSTTManager(
    private val edgeFunctionUrl: String,
    private val supabaseAnonKey: String
) {
    private val TAG = "StreamingSTTManager"
    private val executor = Executors.newSingleThreadExecutor()

    private var deepgramStreaming: DeepgramStreamingSTT? = null
    private var isConnecting = false
    private var utteranceEndMs = 1000 // Default: 1.0 seconds (snappier for short commands)
    // FUTURE: Dynamic adjustment based on speech duration (1s for <2s speech, 1.5s for longer)

    // Callbacks for streaming events
    var onConnected: (() -> Unit)? = null
    var onPartialTranscript: ((String) -> Unit)? = null
    var onFinalTranscript: ((String) -> Unit)? = null
    var onSpeechStarted: (() -> Unit)? = null
    var onUtteranceEnd: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onClosed: (() -> Unit)? = null

    /**
     * Set the utterance end silence threshold
     * @param silenceMs Milliseconds of silence before utterance is considered ended (default: 1000ms)
     */
    fun setUtteranceEndMs(silenceMs: Int) {
        utteranceEndMs = silenceMs
        Log.d(TAG, "Utterance end threshold set to ${silenceMs}ms")
    }

    /**
     * Get current utterance end threshold
     * @return Milliseconds of silence threshold
     */
    fun getUtteranceEndMs(): Int {
        return utteranceEndMs
    }

    /**
     * Ensure Deepgram connection is ready
     * Creates connection if needed, or reuses existing persistent connection
     * @param onReady Callback when connection is established and ready
     */
    fun ensureConnection(onReady: () -> Unit) {
        Log.d(TAG, "Ensuring Deepgram connection is ready...")

        // If already connected, invoke callback immediately
        if (deepgramStreaming?.isConnected() == true) {
            Log.d(TAG, "Already connected to Deepgram")
            onReady()
            return
        }

        // If currently connecting, queue the callback
        if (isConnecting) {
            Log.d(TAG, "Connection in progress, queueing callback...")
            // Store callback to be invoked when connection completes
            deepgramStreaming?.onConnected = {
                Log.i(TAG, "Persistent Deepgram connection established!")
                isConnecting = false
                onReady()
            }
            return
        }

        // Need to establish new connection
        isConnecting = true
        Log.i(TAG, "Establishing new Deepgram connection...")

        executor.execute {
            try {
                // Create Deepgram client if not exists
                if (deepgramStreaming == null) {
                    deepgramStreaming = DeepgramStreamingSTT(
                        edgeFunctionUrl = edgeFunctionUrl,
                        supabaseAnonKey = supabaseAnonKey,
                        utteranceEndMs = utteranceEndMs
                    )
                }

                // Set up callbacks
                deepgramStreaming?.onConnected = {
                    Log.i(TAG, "Persistent Deepgram connection established!")
                    isConnecting = false
                    onReady()
                    onConnected?.invoke()
                }

                deepgramStreaming?.onClosed = {
                    Log.w(TAG, "Deepgram connection closed")
                    onClosed?.invoke()
                }

                deepgramStreaming?.onError = { error ->
                    Log.e(TAG, "Deepgram connection error: $error")
                    isConnecting = false
                    onError?.invoke(error)
                }

                // Connect (async)
                deepgramStreaming?.connect()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to establish Deepgram connection: ${e.message}", e)
                isConnecting = false
                onError?.invoke(e.message ?: "Connection failed")
            }
        }
    }

    /**
     * Send audio chunk to Deepgram
     * @param audioData PCM audio samples (16-bit signed)
     * @param length Number of samples to send
     */
    fun sendAudio(audioData: ShortArray, length: Int) {
        deepgramStreaming?.sendAudio(audioData, length)
    }

    /**
     * Finalize current recording session
     * Signals end of audio stream but keeps connection alive for reuse
     */
    fun finalize() {
        deepgramStreaming?.finalize()
    }

    /**
     * Send KeepAlive to maintain idle connection
     * Call periodically when connection is idle to prevent timeout
     */
    fun sendKeepAlive() {
        deepgramStreaming?.sendKeepAlive()
    }

    /**
     * Check if currently connected to Deepgram
     * @return true if connected, false otherwise
     */
    fun isConnected(): Boolean {
        return deepgramStreaming?.isConnected() == true
    }

    /**
     * Set up callbacks for a new recording session
     * Call this before starting a new recording to handle transcripts
     *
     * @param onPartial Callback for partial transcripts (real-time feedback)
     * @param onFinal Callback for final transcripts (complete utterances)
     * @param onSpeechStart Callback when speech is detected
     * @param onUtteranceComplete Callback when utterance ends (silence detected)
     */
    fun setupSessionCallbacks(
        onPartial: ((String) -> Unit)? = null,
        onFinal: ((String) -> Unit)? = null,
        onSpeechStart: (() -> Unit)? = null,
        onUtteranceComplete: (() -> Unit)? = null
    ) {
        deepgramStreaming?.onPartialTranscript = { transcript ->
            Log.v(TAG, "Partial: $transcript")
            onPartial?.invoke(transcript)
            onPartialTranscript?.invoke(transcript)
        }

        deepgramStreaming?.onFinalTranscript = { transcript ->
            Log.i(TAG, "Final: $transcript")
            onFinal?.invoke(transcript)
            onFinalTranscript?.invoke(transcript)
        }

        deepgramStreaming?.onSpeechStarted = {
            Log.d(TAG, "Speech started")
            onSpeechStart?.invoke()
            onSpeechStarted?.invoke()
        }

        deepgramStreaming?.onUtteranceEnd = {
            Log.i(TAG, "Utterance ended (Deepgram VAD)")
            onUtteranceComplete?.invoke()
            onUtteranceEnd?.invoke()
        }
    }

    /**
     * Close the Deepgram connection
     * Use this to fully disconnect (not recommended - persistent connection is faster)
     */
    fun close() {
        Log.i(TAG, "Closing Deepgram connection")
        deepgramStreaming?.close()
        deepgramStreaming = null
    }

    /**
     * Disconnect immediately (for cleanup)
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting from Deepgram")
        deepgramStreaming?.disconnect()
        deepgramStreaming = null
        isConnecting = false
    }

    /**
     * Clean up resources
     * Call this when completely done with streaming STT
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down streaming STT")
        disconnect()
        executor.shutdown()
    }
}

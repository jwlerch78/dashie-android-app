package com.dashieapp.Dashie.audio

import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Deepgram Streaming STT Client (via Secure Edge Function Proxy)
 *
 * Connects to Supabase Edge Function which proxies to Deepgram WebSocket.
 * This keeps the Deepgram API key secure on the server.
 *
 * Features:
 * - Real-time transcription during speech
 * - Partial results (word-by-word feedback)
 * - VAD events from Deepgram (speech_started, utterance_end)
 * - Automatic end-of-speech detection
 *
 * Usage:
 *   val client = DeepgramStreamingSTT(
 *       edgeFunctionUrl = "wss://your-project.supabase.co/functions/v1/deepgram-stt-stream",
 *       supabaseAnonKey = "YOUR_SUPABASE_ANON_KEY"
 *   )
 *   client.onPartialTranscript = { text -> Log.d(TAG, "Partial: $text") }
 *   client.onFinalTranscript = { text -> Log.d(TAG, "Final: $text") }
 *   client.connect()
 *
 *   // Stream audio chunks (320-sample frames at 16kHz)
 *   client.sendAudio(audioChunk)
 *
 *   // When done
 *   client.close()
 */
class DeepgramStreamingSTT(
    private val edgeFunctionUrl: String,
    private val supabaseAnonKey: String,
    private val utteranceEndMs: Int = 1500  // Default 1.5 seconds silence threshold
) {
    companion object {
        private const val TAG = "DeepgramStreaming"
        private const val VERSION = "1.1.0-CONFIGURABLE-SILENCE"
    }

    // OkHttp WebSocket client
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)  // No timeout for long-lived persistent connections
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)  // Disable ping - Edge Function proxy handles keep-alive
        .build()

    private var webSocket: WebSocket? = null
    @Volatile
    private var isConnected = false

    // Audio buffer for queuing chunks during reconnection
    private val audioBuffer = mutableListOf<ByteArray>()
    private val bufferLock = Any()
    private var isReconnecting = false

    // Callbacks
    var onPartialTranscript: ((String) -> Unit)? = null
    var onFinalTranscript: ((String) -> Unit)? = null
    var onSpeechFinal: ((String) -> Unit)? = null  // Called when speech_final:true (endpointing detected silence)
    var onError: ((String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onClosed: (() -> Unit)? = null
    var onSpeechStarted: (() -> Unit)? = null
    var onUtteranceEnd: (() -> Unit)? = null

    /**
     * Connect to Edge Function WebSocket proxy
     */
    fun connect() {
        if (isConnected) {
            Log.w(TAG, "Already connected")
            return
        }

        // Clean up existing WebSocket if present (handles reconnection case)
        if (webSocket != null) {
            Log.d(TAG, "Cleaning up previous WebSocket before reconnecting...")
            webSocket?.cancel()
            webSocket = null
        }

        Log.i(TAG, "🔧 DeepgramStreamingSTT VERSION: $VERSION")
        Log.i(TAG, "Connecting to Edge Function WebSocket proxy: $edgeFunctionUrl")
        Log.i(TAG, "Silence threshold (utterance_end_ms): ${utteranceEndMs}ms")

        val request = Request.Builder()
            .url(edgeFunctionUrl)
            .addHeader("Authorization", "Bearer $supabaseAnonKey")
            .addHeader("apikey", supabaseAnonKey)
            .addHeader("X-Utterance-End-Ms", utteranceEndMs.toString())  // Pass to edge function
            .build()

        webSocket = client.newWebSocket(request, WebSocketListener())
    }

    /**
     * Send audio chunk to Deepgram (via Edge Function proxy)
     * @param audioData Raw PCM samples (16-bit signed)
     * @param length Number of samples to send
     */
    private var audioChunksSent = 0  // Track number of chunks sent

    fun sendAudio(audioData: ShortArray, length: Int) {
        // Convert ShortArray to ByteArray (16-bit little-endian)
        val byteArray = ByteArray(length * 2)
        for (i in 0 until length) {
            val sample = audioData[i]
            byteArray[i * 2] = (sample.toInt() and 0xFF).toByte()
            byteArray[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        // If not connected, buffer the audio chunk and trigger reconnection
        if (!isConnected) {
            synchronized(bufferLock) {
                audioBuffer.add(byteArray)

                // Log first buffered chunk
                if (audioBuffer.size == 1) {
                    Log.w(TAG, "⏸️ Not connected - buffering audio chunks...")
                }

                // Trigger reconnection if not already in progress
                if (!isReconnecting) {
                    isReconnecting = true
                    Log.i(TAG, "🔄 Triggering reconnection while buffering audio...")
                    connect()
                }
            }
            return
        }

        // Send as binary WebSocket frame
        val success = webSocket?.send(ByteString.of(*byteArray)) ?: false
        audioChunksSent++

        // Log every 50 chunks (every ~1 second at 16kHz)
        if (audioChunksSent % 50 == 0) {
            Log.d(TAG, "📤 Sent $audioChunksSent audio chunks (${byteArray.size} bytes each)")
        }

        if (!success) {
            Log.e(TAG, "❌ Failed to send audio chunk!")
        }
    }

    /**
     * Send Finalize message to force Deepgram to process remaining audio
     * and return final transcript. Keeps connection open for reuse.
     *
     * Use this instead of close() when you want to keep the connection alive
     * for subsequent recordings (eliminates connection latency!)
     */
    fun finalize() {
        if (!isConnected) {
            Log.w(TAG, "Not connected, cannot finalize")
            return
        }

        Log.i(TAG, "🔄 Sending Finalize message after $audioChunksSent audio chunks (keeping connection alive for reuse)")
        val finalizeMessage = """{"type":"Finalize"}"""
        webSocket?.send(finalizeMessage)
        audioChunksSent = 0  // Reset for next recording

        // Clear any remaining buffered audio (shouldn't happen, but be safe)
        synchronized(bufferLock) {
            if (audioBuffer.isNotEmpty()) {
                Log.w(TAG, "⚠️ Clearing ${audioBuffer.size} unbuffered chunks on finalize")
                audioBuffer.clear()
            }
        }
    }

    /**
     * Send KeepAlive message to maintain WebSocket connection during idle periods.
     * This prevents the connection from timing out and avoids processing costs
     * (unlike sending empty audio frames).
     *
     * Deepgram will acknowledge this message without processing it as audio.
     */
    fun sendKeepAlive() {
        if (!isConnected) {
            Log.v(TAG, "Not connected, cannot send keep-alive")
            return
        }

        val keepAliveMessage = """{"type":"KeepAlive"}"""
        webSocket?.send(keepAliveMessage)
    }

    /**
     * Check if currently connected
     */
    fun isConnected(): Boolean {
        return isConnected
    }

    /**
     * Signal end of audio stream and close connection
     */
    fun close() {
        Log.i(TAG, "Closing Deepgram streaming connection")

        // Send CloseStream message to finalize and close
        val closeMessage = """{"type":"CloseStream"}"""
        webSocket?.send(closeMessage)

        // Then close the WebSocket
        webSocket?.close(1000, "Recording complete")
        isConnected = false

        // Clean up OkHttp connection pool to prevent stale connections on next use
        // This fixes the issue where subsequent connections take 13+ seconds
        cleanupConnectionPool()
    }

    /**
     * Forcefully disconnect (for cleanup)
     */
    fun disconnect() {
        webSocket?.cancel()
        isConnected = false

        // Also cleanup connection pool on disconnect
        cleanupConnectionPool()
    }

    /**
     * Clean up OkHttp connection pool
     * This prevents connection pooling issues when creating new instances
     */
    private fun cleanupConnectionPool() {
        try {
            // Evict all idle connections from the pool
            client.connectionPool.evictAll()

            // Cancel any pending calls
            client.dispatcher.cancelAll()

            Log.d(TAG, "Connection pool cleaned up")
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up connection pool: ${e.message}")
        }
    }

    /**
     * WebSocket event listener
     */
    private inner class WebSocketListener : okhttp3.WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "✓ WebSocket connected to Edge Function proxy")
            // Don't set isConnected yet - wait for 'ready' message from Edge Function
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "📨 Received message: ${text.take(200)}${if (text.length > 200) "..." else ""}")
            try {
                val json = JSONObject(text)
                val type = json.optString("type", "")

                when (type) {
                    "ready" -> {
                        // Edge Function confirms Deepgram connection is ready
                        Log.i(TAG, "✓ Deepgram streaming ready")
                        isConnected = true
                        isReconnecting = false

                        // Flush buffered audio chunks
                        synchronized(bufferLock) {
                            if (audioBuffer.isNotEmpty()) {
                                Log.i(TAG, "📤 Flushing ${audioBuffer.size} buffered audio chunks...")
                                for (chunk in audioBuffer) {
                                    webSocket?.send(ByteString.of(*chunk))
                                    audioChunksSent++
                                }
                                Log.i(TAG, "✅ Flushed all ${audioBuffer.size} buffered chunks - no audio loss!")
                                audioBuffer.clear()
                            }
                        }

                        onConnected?.invoke()
                    }

                    "error" -> {
                        val message = json.optString("message", "Unknown error")
                        Log.e(TAG, "🚨 Edge Function error: $message")
                        onError?.invoke(message)
                    }

                    "SpeechStarted" -> {
                        Log.d(TAG, "🎤 Speech started")
                        onSpeechStarted?.invoke()
                    }

                    "UtteranceEnd" -> {
                        Log.d(TAG, "🔚 Utterance ended")
                        onUtteranceEnd?.invoke()
                    }

                    "session_closed" -> {
                        // Deepgram session was closed (e.g., after Finalize) - need to wait for new 'ready'
                        Log.i(TAG, "📴 Deepgram session closed - will wait for reconnection before sending audio")
                        isConnected = false
                        // Note: Don't call onClosed - the WebSocket to Edge Function is still open
                        // Next audio send will trigger reconnection
                    }

                    else -> {
                        // Check for Deepgram transcript results
                        if (json.has("channel")) {
                            parseTranscriptResult(json)
                        } else {
                            Log.w(TAG, "⚠️ Received unknown message type: $type, full message: ${text.take(300)}")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error parsing message: ${e.message}", e)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Binary messages not expected from Deepgram
            Log.d(TAG, "Received binary message: ${bytes.size} bytes")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closing: $code - $reason")
            isConnected = false
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "✓ WebSocket closed: $code - $reason")
            isConnected = false
            onClosed?.invoke()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket error: ${t.message}", t)
            isConnected = false
            onError?.invoke(t.message ?: "Unknown WebSocket error")
        }

        /**
         * Parse Deepgram transcript result
         */
        private fun parseTranscriptResult(json: JSONObject) {
            try {
                val channel = json.getJSONObject("channel")
                val alternatives = channel.getJSONArray("alternatives")

                if (alternatives.length() > 0) {
                    val alternative = alternatives.getJSONObject(0)
                    val transcript = alternative.getString("transcript")
                    val confidence = alternative.optDouble("confidence", 0.0)
                    val isFinal = json.optBoolean("is_final", false)
                    val speechFinal = json.optBoolean("speech_final", false)

                    if (transcript.isNotEmpty()) {
                        if (isFinal) {
                            Log.i(TAG, "📝 Final transcript: \"$transcript\" (confidence: ${String.format("%.2f", confidence)}, speech_final: $speechFinal)")
                            onFinalTranscript?.invoke(transcript)
                        } else {
                            Log.v(TAG, "📝 Partial transcript: \"$transcript\"")
                            onPartialTranscript?.invoke(transcript)
                        }
                    }

                    // speech_final indicates endpointing detected silence (400ms) - user done speaking
                    if (speechFinal) {
                        Log.i(TAG, "🔚 Speech final (endpointing) - user stopped speaking")
                        onSpeechFinal?.invoke(transcript)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing transcript: ${e.message}", e)
            }
        }
    }
}

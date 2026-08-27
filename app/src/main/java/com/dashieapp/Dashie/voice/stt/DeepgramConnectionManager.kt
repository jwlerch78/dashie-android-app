package com.dashieapp.Dashie.voice.stt

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import com.dashieapp.Dashie.audio.DeepgramStreamingSTT

/**
 * Manages persistent Deepgram WebSocket connection for streaming STT
 * Handles connection lifecycle, keep-alive pings, and auto-reconnection
 */
class DeepgramConnectionManager {
    private val TAG = "DeepgramConnectionMgr"
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var deepgramStreaming: DeepgramStreamingSTT? = null
    private var isConnecting = false
    private var keepAliveRunnable: Runnable? = null

    // Configuration. Edge-function URL + anon key are derived from the
    // flavor-aware BuildConfig so the STT proxy hits the matching Supabase
    // project (staging build → staging, prod build → prod). SUPABASE_URL is
    // empty for the firetv flavor, which doesn't use voice.
    private val EDGE_FUNCTION_URL =
        com.dashieapp.Dashie.BuildConfig.SUPABASE_URL.takeIf { it.isNotBlank() }
            ?.let { "wss://" + it.removePrefix("https://") + "/functions/v1/deepgram-stt-stream" }
            ?: ""
    private val SUPABASE_ANON_KEY = com.dashieapp.Dashie.BuildConfig.SUPABASE_ANON_KEY
    private var utteranceEndMs = 1000  // Default: 1.0 seconds (snappier for short commands)
    // FUTURE: Dynamic adjustment based on speech duration (1s for <2s speech, 1.5s for longer)

    // Callbacks
    var onConnectionReady: (() -> Unit)? = null
    var onConnectionClosed: (() -> Unit)? = null
    var onConnectionError: ((String) -> Unit)? = null

    /**
     * Check if Deepgram connection is currently active
     */
    fun isConnected(): Boolean {
        return deepgramStreaming?.isConnected() == true
    }

    /**
     * Check if connection is currently being established
     */
    fun isConnecting(): Boolean {
        return isConnecting
    }

    /**
     * Get the underlying DeepgramStreamingSTT instance
     * (For setting up per-recording callbacks)
     */
    fun getStreamingSTT(): DeepgramStreamingSTT? {
        return deepgramStreaming
    }

    /**
     * Set Deepgram silence threshold (utterance_end_ms)
     * @param silenceMs Milliseconds of silence before utterance ends (default: 1000ms)
     *
     * Use cases:
     * - Short commands: 800-1000ms (default, snappier)
     * - Conversations/dialogue: 2000-3000ms (longer pauses for thinking)
     *
     * Note: Requires reconnecting to Deepgram to take effect
     */
    fun setSilenceThreshold(silenceMs: Int) {
        utteranceEndMs = silenceMs
        Log.i(TAG, "Silence threshold updated to ${silenceMs}ms")

        // If Deepgram is currently connected, reconnect with new threshold
        if (isConnected()) {
            Log.i(TAG, "Reconnecting Deepgram with new silence threshold...")
            disconnect()
            // Reconnect will happen automatically on next use with new threshold
        }
    }

    /**
     * Get current silence threshold
     */
    fun getSilenceThreshold(): Int {
        return utteranceEndMs
    }

    /**
     * Initialize persistent Deepgram connection proactively
     * Call this during app startup to ensure the first voice command is fast
     */
    fun initialize(onReady: (() -> Unit)? = null) {
        Log.i(TAG, "🚀 Proactively initializing Deepgram connection for instant first command")
        ensureConnection(onReady)
    }

    /**
     * Ensure Deepgram connection is ready
     * If already connected, calls onReady immediately
     * If connecting, waits for connection to complete
     * If not connected, starts new connection
     */
    fun ensureConnection(onReady: (() -> Unit)? = null) {
        val instanceId = deepgramStreaming?.hashCode()
        Log.d(TAG, "🔍 ensureConnection called - instance=$instanceId, isConnected=${isConnected()}, isConnecting=$isConnecting")

        // If already connected, call onReady immediately
        if (isConnected()) {
            Log.d(TAG, "✓ Deepgram already connected (instance=$instanceId), ready to stream")
            onReady?.invoke()
            return
        }

        // If currently connecting, queue the callback
        if (isConnecting) {
            Log.d(TAG, "⏳ Deepgram connection in progress, waiting...")
            // Poll for connection (with timeout)
            executor.execute {
                var waitTime = 0
                while (isConnecting && waitTime < 5000) {
                    Thread.sleep(100)
                    waitTime += 100
                }
                if (isConnected()) {
                    Log.d(TAG, "✓ Connection ready after wait, invoking callback")
                    handler.post { onReady?.invoke() }
                } else {
                    Log.e(TAG, "Deepgram connection timeout")
                    handler.post { onConnectionError?.invoke("Connection timeout") }
                }
            }
            return
        }

        // Start new connection
        connect(onReady)
    }

    /**
     * Start new Deepgram connection
     */
    private fun connect(onReady: (() -> Unit)? = null) {
        isConnecting = true
        Log.i(TAG, "🔌 Initializing persistent Deepgram connection...")

        executor.execute {
            try {
                // Create Deepgram client if not exists
                if (deepgramStreaming == null) {
                    deepgramStreaming = DeepgramStreamingSTT(
                        edgeFunctionUrl = EDGE_FUNCTION_URL,
                        supabaseAnonKey = SUPABASE_ANON_KEY,
                        utteranceEndMs = utteranceEndMs  // Pass configurable silence threshold
                    )
                }

                // Set up callbacks for THIS connection attempt
                // (callbacks are overwritten each time to ensure the correct onReady is called)
                deepgramStreaming?.onConnected = {
                    Log.i(TAG, "✅ Persistent Deepgram connection established!")
                    isConnecting = false
                    // startKeepAlive()  // TEMPORARILY DISABLED - Testing if this interferes with audio streaming
                    handler.post {
                        onReady?.invoke()
                        onConnectionReady?.invoke()
                    }
                }

                deepgramStreaming?.onClosed = {
                    Log.w(TAG, "⚠️ Deepgram connection closed - will auto-reconnect on next use")
                    stopKeepAlive()  // Stop keep-alive pings
                    deepgramStreaming = null
                    isConnecting = false

                    handler.post {
                        onConnectionClosed?.invoke()
                    }

                    // Auto-reconnect after a short delay to keep connection ready
                    handler.postDelayed({
                        Log.i(TAG, "🔄 Auto-reconnecting Deepgram to maintain persistent connection")
                        connect()
                    }, 2000) // Wait 2 seconds before reconnecting
                }

                deepgramStreaming?.onError = { error ->
                    Log.e(TAG, "Deepgram connection error: $error")
                    isConnecting = false
                    handler.post {
                        onConnectionError?.invoke(error)
                    }
                }

                // Connect (async) - will reconnect if connection was lost
                deepgramStreaming?.connect()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Deepgram: ${e.message}", e)
                isConnecting = false
                deepgramStreaming = null
                handler.post {
                    onConnectionError?.invoke("Initialization failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Disconnect from Deepgram (does not auto-reconnect)
     */
    fun disconnect() {
        stopKeepAlive()
        deepgramStreaming?.close()
        deepgramStreaming = null
        isConnecting = false
        Log.i(TAG, "Deepgram connection closed")
    }

    /**
     * Start keep-alive pings to prevent idle connection from closing
     * Sends a KeepAlive message every 30 seconds (text message, not audio - no processing cost!)
     */
    private fun startKeepAlive() {
        stopKeepAlive()  // Clear any existing keep-alive

        keepAliveRunnable = object : Runnable {
            override fun run() {
                if (isConnected()) {
                    Log.v(TAG, "📡 Sending keep-alive ping to Deepgram")
                    try {
                        deepgramStreaming?.sendKeepAlive()  // Send KeepAlive JSON message (no processing cost)
                    } catch (e: Exception) {
                        Log.w(TAG, "Keep-alive ping failed: ${e.message}")
                    }
                    handler.postDelayed(this, 30000)  // Ping every 30 seconds
                } else {
                    Log.d(TAG, "Keep-alive stopped (connection closed)")
                }
            }
        }

        handler.postDelayed(keepAliveRunnable!!, 30000)  // Start first ping in 30s
        Log.d(TAG, "Keep-alive started (30s interval)")
    }

    /**
     * Stop keep-alive pings
     */
    private fun stopKeepAlive() {
        keepAliveRunnable?.let {
            handler.removeCallbacks(it)
            keepAliveRunnable = null
            Log.d(TAG, "Keep-alive stopped")
        }
    }

    /**
     * Clean up resources
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down Deepgram connection manager")
        stopKeepAlive()
        deepgramStreaming?.close()
        deepgramStreaming = null
        isConnecting = false
        executor.shutdown()
    }
}

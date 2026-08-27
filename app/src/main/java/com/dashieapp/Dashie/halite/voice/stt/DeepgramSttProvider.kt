package com.dashieapp.Dashie.halite.voice.stt

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * Deepgram Cloud STT Provider (via Edge Function Proxy)
 *
 * Connects to Supabase Edge Function which proxies to Deepgram WebSocket.
 * This keeps the Deepgram API key secure on the server - devices only need
 * the Supabase anon key, not the actual Deepgram API key.
 *
 * Features:
 * - Real-time streaming transcription
 * - Interim results for responsive UI
 * - End-of-speech detection via utterance_end
 * - Smart formatting and punctuation
 * - API key security (key never leaves server)
 *
 * Architecture:
 *   Android App (WebSocket) → Edge Function (WebSocket) → Deepgram (WebSocket)
 *
 * Docs: https://developers.deepgram.com/docs/getting-started-with-live-streaming-audio
 */
class DeepgramSttProvider : SttProvider {
    companion object {
        private const val TAG = "DeepgramStt"
        // Edge function proxy — Deepgram API key stays secure on the server.
        // URL + anon key derived from the flavor-aware BuildConfig so the
        // proxy hits the matching Supabase project (staging vs prod).
        // SUPABASE_URL is empty for the firetv flavor, which doesn't use voice.
        private val EDGE_FUNCTION_URL =
            com.dashieapp.Dashie.BuildConfig.SUPABASE_URL.takeIf { it.isNotBlank() }
                ?.let { "wss://" + it.removePrefix("https://") + "/functions/v1/deepgram-stt-stream" }
                ?: ""
        private val SUPABASE_ANON_KEY = com.dashieapp.Dashie.BuildConfig.SUPABASE_ANON_KEY
    }

    override val providerId = "deepgram"
    override val displayName = "Deepgram (Cloud)"

    /**
     * Resolves the WS Bearer at connect time (set by [com.dashieapp.Dashie.halite.voice.VoicePipelineCoordinator]):
     * a logged-in device's account JWT, or an anonymous kiosk's minted STT token
     * ([SttCredentialProvider]). Null / unset → the legacy [SUPABASE_ANON_KEY] fallback (which only
     * streams while the proxy's `stt_auth_enforce` flag is OFF). Build plan §3.2 (WS2).
     */
    var credentialProvider: (() -> String?)? = null

    // Configuration
    private var sampleRate: Int = 16000
    private var enableInterimResults: Boolean = true
    private var utteranceEndMs: Int = 1000  // Silence threshold before utterance ends
    // Deepgram 'endpointing' (speech_final silence threshold), sent per session. 400ms = snappy
    // for commands; the cascade Dialog loop raises it (~1000ms via SttProviderManager.setEndpointingMs)
    // so a mid-thought pause in a back-and-forth doesn't finalize the utterance early.
    var endpointingMs: Int = 400

    // OkHttp client for WebSocket
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS) // No timeout for streaming
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // WebSocket connection
    private var webSocket: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())

    // Session state
    private var listener: SttListener? = null
    private var isSessionActive = false
    private var hasReceivedFinalResult = false

    /**
     * Set once [endAudioStream] has sent `{"type":"Finalize"}`.
     *
     * Deepgram answers a Finalize with `is_final:true` but **`speech_final:false`** —
     * `speech_final` means "the VAD saw end-of-speech", and a Finalize-flush is not
     * that. It marks the flush with `from_finalize:true` instead. Completing only on
     * `speech_final` therefore ignored Deepgram's actual answer and let the 1200 ms
     * [FINAL_RESULT_TIMEOUT_MS] watchdog synthesise the SAME text a second later.
     *
     * Measured 2026-08-05 on the Samsung: the transcript arrived ~170 ms after
     * endAudioStream, the turn completed at 1201 ms, and 5 of 8 turns took that
     * path — i.e. the watchdog was the primary route, not a safety net.
     */
    private var finalizeSent = false
    private var lastInterimText = ""

    // Accumulated transcript (for multi-utterance)
    private var accumulatedTranscript = StringBuilder()

    // Silence detection timeout
    private var silenceWatchdogRunnable: Runnable? = null
    private val SILENCE_TIMEOUT_MS = 2000L  // End session after 2s of silence after speech
    private val MAX_RECONNECTS = 1           // FB20: at most one silent early-connect reconnect
    private val MAX_RECONNECT_BUFFER = 400   // ~40s of 100ms chunks — bound the reconnect buffer

    override fun isAvailable(): Boolean {
        // Always available - uses edge function proxy with server-side API key
        return true
    }

    override suspend fun initialize(config: SttConfig): Boolean {
        sampleRate = config.sampleRate
        enableInterimResults = config.enableInterimResults
        utteranceEndMs = config.endOfSpeechTimeoutMs.toInt()

        Log.i(TAG, "Initialized - using edge function proxy (API key secure on server)")
        return isAvailable()
    }

    // Track if we've received the "ready" message from edge function
    private var isEdgeFunctionReady = false

    // FB20: swallow a transient early-connect drop ("WebSocket error: null" right after connect,
    // edge cold start) by reconnecting silently ONCE — while buffering the mic audio and flushing
    // it on reconnect, so nothing is lost and the user doesn't see a connecting/error flash.
    private var reconnectAttempts = 0
    private var isReconnecting = false
    private val reconnectBuffer = mutableListOf<ByteArray>()

    override fun startSession(listener: SttListener) {
        if (isSessionActive) {
            Log.w(TAG, "Session already active, cancelling previous")
            cancelSession()
        }

        this.listener = listener
        isSessionActive = true
        isEdgeFunctionReady = false
        hasReceivedFinalResult = false
        finalizeSent = false
        lastInterimText = ""
        accumulatedTranscript.clear()
        reconnectAttempts = 0
        isReconnecting = false
        synchronized(reconnectBuffer) { reconnectBuffer.clear() }

        Log.i(TAG, "Starting STT session via edge function proxy")
        connect()
    }

    /** Open (or reopen) the edge-proxy WebSocket. Shared by the initial connect and the FB20
     *  silent reconnect. */
    private fun connect() {
        isEdgeFunctionReady = false
        // Auth: the account JWT (logged-in) or a minted STT token (anonymous kiosk), resolved at
        // connect time; falls back to the anon key when no credential is available yet (legacy —
        // only accepted while the proxy's stt_auth_enforce flag is OFF). apikey stays the anon key
        // (the Supabase platform gateway header). Build plan §3.2 (WS2).
        val bearer = credentialProvider?.invoke() ?: SUPABASE_ANON_KEY
        val request = Request.Builder()
            .url(EDGE_FUNCTION_URL)
            .addHeader("Authorization", "Bearer $bearer")
            .addHeader("apikey", SUPABASE_ANON_KEY)
            .addHeader("X-Utterance-End-Ms", utteranceEndMs.toString())
            .addHeader("X-Endpointing-Ms", endpointingMs.toString())
            .build()
        webSocket = okHttpClient.newWebSocket(request, DeepgramWebSocketListener())
    }

    override fun streamAudio(audioData: ByteArray) {
        if (!isSessionActive) {
            return
        }

        // Reset silence watchdog when we send audio
        resetSilenceWatchdog()

        // FB20: while reconnecting, buffer audio (bounded) to flush on the new socket's open —
        // don't send to the dead socket (it would be lost).
        if (isReconnecting) {
            synchronized(reconnectBuffer) {
                if (reconnectBuffer.size < MAX_RECONNECT_BUFFER) reconnectBuffer.add(audioData)
            }
            return
        }

        // Send audio as binary
        webSocket?.send(audioData.toByteString())
    }

    override fun endAudioStream() {
        if (!isSessionActive) {
            return
        }

        Log.d(TAG, "End of audio stream signaled")

        // Force a final NOW: the proxy handles {"type":"Finalize"} by closing the
        // Deepgram session, which flushes the pending final transcript. (Sending
        // empty bytes did NOT finalize, so end-of-speech fell through to the ~3s
        // watchdog — the "VAD waits 3s" bug.)
        finalizeSent = true
        webSocket?.send("{\"type\":\"Finalize\"}")

        // Backup watchdog if the final still doesn't arrive.
        startFinalResultWatchdog()
    }

    override fun cancelSession() {
        if (!isSessionActive) {
            return
        }

        Log.d(TAG, "Cancelling session")
        endSession(notifyListener = false)
    }

    override fun release() {
        cancelSession()
        okHttpClient.dispatcher.executorService.shutdown()
    }

    // Note: Edge function handles all Deepgram configuration (model, language, etc.)
    // No need to build URL params - just send audio and receive transcripts

    private fun endSession(notifyListener: Boolean = true) {
        isSessionActive = false
        cancelSilenceWatchdog()
        cancelFinalResultWatchdog()

        // Close WebSocket
        webSocket?.close(1000, "Session ended")
        webSocket = null

        if (notifyListener) {
            handler.post {
                listener?.onSessionEnded()
                listener = null
            }
        } else {
            listener = null
        }
    }

    private fun resetSilenceWatchdog() {
        // Only start watchdog after we've received some speech
        if (lastInterimText.isEmpty()) {
            return
        }

        cancelSilenceWatchdog()

        silenceWatchdogRunnable = Runnable {
            if (isSessionActive && !hasReceivedFinalResult && buildFinalTranscript().isNotEmpty()) {
                Log.d(TAG, "Silence watchdog triggered - finalizing accumulated + interim")
                handleFinalResult(buildFinalTranscript())
            }
        }

        handler.postDelayed(silenceWatchdogRunnable!!, SILENCE_TIMEOUT_MS)
    }

    private fun cancelSilenceWatchdog() {
        silenceWatchdogRunnable?.let {
            handler.removeCallbacks(it)
            silenceWatchdogRunnable = null
        }
    }

    private var finalResultWatchdogRunnable: Runnable? = null
    private val FINAL_RESULT_TIMEOUT_MS = 1200L

    private fun startFinalResultWatchdog() {
        cancelFinalResultWatchdog()

        finalResultWatchdogRunnable = Runnable {
            if (isSessionActive && !hasReceivedFinalResult) {
                Log.w(TAG, "Final result watchdog triggered")
                val text = buildFinalTranscript()
                if (text.isNotEmpty()) {
                    handleFinalResult(text)
                } else {
                    handler.post {
                        listener?.onNoSpeechDetected()
                        endSession()
                    }
                }
            }
        }

        handler.postDelayed(finalResultWatchdogRunnable!!, FINAL_RESULT_TIMEOUT_MS)
    }

    private fun cancelFinalResultWatchdog() {
        finalResultWatchdogRunnable?.let {
            handler.removeCallbacks(it)
            finalResultWatchdogRunnable = null
        }
    }

    /**
     * The complete utterance = every finalized segment (accumulatedTranscript) PLUS any pending
     * interim that Deepgram hasn't finalized yet (lastInterimText, cleared on each is_final so it
     * only ever holds words BEYOND the last final). Finalizing off accumulatedTranscript alone
     * dropped that tail — in dialog mode endpointing(1000ms) ties utterance_end_ms(1000ms), so
     * UtteranceEnd routinely fired before speech_final, taking the finals and losing the last
     * interim words (~50% tail-drop). Combine them so no finalize path loses the tail.
     */
    private fun buildFinalTranscript(): String {
        val sb = StringBuilder(accumulatedTranscript)
        if (lastInterimText.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append(lastInterimText)
        }
        return sb.toString().trim()
    }

    private fun handleFinalResult(text: String) {
        if (hasReceivedFinalResult) {
            return
        }

        hasReceivedFinalResult = true
        cancelSilenceWatchdog()
        cancelFinalResultWatchdog()

        handler.post {
            listener?.onFinalResult(text)
            endSession()
        }
    }

    /**
     * WebSocket listener for edge function proxy
     */
    private inner class DeepgramWebSocketListener : WebSocketListener() {
        // Socket-identity guard: sessions cycle back-to-back (the cascade Dialog re-listens
        // every turn), and `isSessionActive` is already true again for the NEXT session when a
        // late onFailure/onClosed from the PREVIOUS socket arrives — which killed the new
        // session or promoted the previous turn's transcript as its final result. Every
        // callback bails unless it belongs to the CURRENT socket. (Master plan WS-A.3.)
        private fun isStale(webSocket: WebSocket): Boolean {
            val stale = webSocket !== this@DeepgramSttProvider.webSocket
            if (stale) Log.d(TAG, "Ignoring event from stale WebSocket")
            return stale
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (isStale(webSocket)) return
            Log.i(TAG, "WebSocket connected to edge function proxy")
            // FB20: after a silent reconnect, flush the audio buffered during the gap so nothing
            // is lost, then resume live streaming. (The edge queues it until Deepgram is ready.)
            if (isReconnecting) {
                synchronized(reconnectBuffer) {
                    if (reconnectBuffer.isNotEmpty()) {
                        Log.i(TAG, "FB20: flushing ${reconnectBuffer.size} buffered audio chunk(s) on reconnect")
                        reconnectBuffer.forEach { try { webSocket.send(it.toByteString()) } catch (_: Exception) {} }
                        reconnectBuffer.clear()
                    }
                }
                isReconnecting = false
            }
            // Don't call onSessionStarted yet - wait for 'ready' message from edge function
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (isStale(webSocket)) return
            try {
                parseDeepgramResponse(text)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing Deepgram response: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (isStale(webSocket)) return
            Log.w(TAG, "WebSocket error: ${t.message}")
            if (!isSessionActive) return
            // FB20: swallow a transient early-connect drop (before 'ready', before any transcript)
            // by reconnecting silently ONCE. Audio keeps buffering (streamAudio) while
            // isReconnecting, and flushes on the new socket's onOpen — nothing lost, no
            // connecting/error flash. A later or repeated failure surfaces the error normally.
            if (!isEdgeFunctionReady && !hasReceivedFinalResult && reconnectAttempts < MAX_RECONNECTS) {
                reconnectAttempts++
                isReconnecting = true
                Log.i(TAG, "FB20: transient early WS failure — silent reconnect #$reconnectAttempts")
                handler.post { if (isSessionActive) connect() }
                return
            }
            handler.post {
                listener?.onError("Connection error: ${t.message}", isRecoverable = true)
                endSession()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (isStale(webSocket)) return
            Log.d(TAG, "WebSocket closed: $code - $reason")
            if (isSessionActive && !hasReceivedFinalResult) {
                // Connection closed before we got a result
                val text = buildFinalTranscript()
                if (text.isNotEmpty()) {
                    handleFinalResult(text)
                } else {
                    handler.post {
                        listener?.onNoSpeechDetected()
                        endSession()
                    }
                }
            }
        }
    }

    /**
     * Parse response from edge function proxy
     *
     * Edge function messages:
     * - { "type": "ready" } - Deepgram connection established
     * - { "type": "error", "message": "..." } - Error from edge function
     * - { "type": "SpeechStarted" } - VAD detected speech
     * - { "type": "UtteranceEnd" } - Utterance ended
     * - { "channel": {...} } - Deepgram transcript results
     */
    private fun parseDeepgramResponse(json: String) {
        val response = JSONObject(json)
        val type = response.optString("type", "")

        when (type) {
            "ready" -> {
                // Edge function confirms Deepgram connection is ready
                Log.i(TAG, "Edge function ready - Deepgram streaming active")
                isEdgeFunctionReady = true
                handler.post {
                    listener?.onSessionStarted()
                }
            }

            "error" -> {
                val message = response.optString("message", "Unknown error")
                val code = response.optString("code", "")
                Log.e(TAG, "Edge function error: $message (code=$code)")
                // A credit reject (deepgram-stt-stream gate) is TERMINAL — mark it
                // non-recoverable so SttProviderManager does NOT fall back to HA-Assist
                // (a silent degrade whose auth failure also masks the real cause, FB21),
                // and pass the code straight through so the coordinator shows the clean
                // out-of-credits prompt instead of a generic red error.
                val terminal = code == "insufficient_credits"
                handler.post {
                    listener?.onError(
                        if (terminal) "insufficient_credits" else "Deepgram error: $message",
                        isRecoverable = !terminal
                    )
                    endSession()
                }
            }

            "SpeechStarted" -> {
                Log.d(TAG, "Speech started")
            }

            "UtteranceEnd" -> {
                // Combine finals + pending interim — do NOT take accumulatedTranscript alone
                // (that dropped the tail when UtteranceEnd raced ahead of speech_final).
                val combined = buildFinalTranscript()
                Log.d(TAG, "Utterance end — final='$combined' (accum='${accumulatedTranscript.toString().trim()}' + interim='$lastInterimText')")
                if (combined.isNotEmpty()) {
                    handleFinalResult(combined)
                }
            }

            "Metadata" -> {
                val requestId = response.optString("request_id", "")
                Log.d(TAG, "Session metadata: requestId=$requestId")
            }

            else -> {
                // Check for Deepgram transcript results (has "channel" field)
                if (response.has("channel")) {
                    parseTranscriptResult(response)
                } else if (type.isNotEmpty()) {
                    Log.d(TAG, "Unknown message type: $type")
                }
            }
        }
    }

    /**
     * Parse Deepgram transcript result
     */
    private fun parseTranscriptResult(response: JSONObject) {
        val channel = response.optJSONObject("channel") ?: return
        val alternatives = channel.optJSONArray("alternatives") ?: return

        if (alternatives.length() == 0) return

        val firstAlt = alternatives.getJSONObject(0)
        val transcript = firstAlt.optString("transcript", "")

        if (transcript.isEmpty()) return

        val isFinal = response.optBoolean("is_final", false)
        val speechFinal = response.optBoolean("speech_final", false)
        // Deepgram's explicit marker for "this is the Finalize flush you asked for".
        val fromFinalize = response.optBoolean("from_finalize", false)

        Log.d(TAG, "Transcript: '$transcript' (final=$isFinal, speechFinal=$speechFinal" +
            (if (fromFinalize) ", fromFinalize=true" else "") + ")")

        if (isFinal) {
            // Accumulate final results (Deepgram sends multiple finals for long speech)
            if (accumulatedTranscript.isNotEmpty()) {
                accumulatedTranscript.append(" ")
            }
            accumulatedTranscript.append(transcript)
            // This segment is now finalized — the interim that was building it is redundant.
            // Clear it so buildFinalTranscript() only ever appends a GENUINELY-pending tail
            // (words spoken after this final), never a duplicate of what we just accumulated.
            lastInterimText = ""

            // End of utterance on ANY of three signals, not just speech_final:
            //   speechFinal  — Deepgram's VAD saw end-of-speech (the natural case)
            //   fromFinalize — this IS the flush answering our Finalize
            //   finalizeSent — we asked to finalize and a final came back; some
            //                  responses omit from_finalize, and waiting for a
            //                  speech_final that will never arrive costs the full
            //                  1200 ms watchdog for a transcript already in hand.
            if (speechFinal || fromFinalize || finalizeSent) {
                handleFinalResult(buildFinalTranscript())
            }
        } else {
            // Interim result
            lastInterimText = transcript
            handler.post {
                listener?.onInterimResult(transcript)
            }
        }
    }
}

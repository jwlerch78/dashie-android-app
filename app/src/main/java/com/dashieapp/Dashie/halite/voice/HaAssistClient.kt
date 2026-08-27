package com.dashieapp.Dashie.halite.voice

import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Home Assistant Assist Pipeline WebSocket Client
 *
 * Handles WebSocket communication with HA's Assist pipeline for voice control.
 * Supports both audio streaming (STT) and text input modes.
 *
 * Protocol flow:
 * 1. Connect to ws://ha-url/api/websocket
 * 2. Authenticate with access token
 * 3. Start assist_pipeline/run with audio input
 * 4. Stream audio chunks as binary frames
 * 5. Receive STT results, intent results, and TTS output
 * 6. Signal end of audio with empty chunk
 */
class HaAssistClient(
    private val haUrl: String,
    private val accessToken: String
) {
    companion object {
        private const val TAG = "HaAssistClient"
        private const val CONNECT_TIMEOUT_MS = 10000L
        private const val READ_TIMEOUT_MS = 30000L
        private const val WRITE_TIMEOUT_MS = 10000L
    }

    // WebSocket state
    private var webSocket: WebSocket? = null
    private val messageId = AtomicInteger(1)
    private var isAuthenticated = false
    private var sttBinaryHandlerId: Int? = null

    // OkHttp client — accepts self-signed certs for LAN HA URLs.
    private val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    // Callbacks
    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((reason: String) -> Unit)? = null
    var onAuthSuccess: (() -> Unit)? = null
    var onAuthFailed: ((error: String) -> Unit)? = null
    var onPipelineStarted: ((handlerId: Int) -> Unit)? = null
    var onSttStart: (() -> Unit)? = null
    var onSttEnd: ((text: String) -> Unit)? = null
    var onIntentEnd: ((intent: String, response: String, responseType: String) -> Unit)? = null
    var onTtsEnd: ((url: String, mimeType: String) -> Unit)? = null
    var onRunEnd: (() -> Unit)? = null
    var onError: ((error: String) -> Unit)? = null
    var onSttNoText: (() -> Unit)? = null  // Called when STT couldn't recognize speech (friendly handling)
    var onPipelinesReceived: ((List<Pipeline>) -> Unit)? = null  // Called when pipeline list is received

    /**
     * Data class representing an Assist pipeline from Home Assistant
     */
    data class Pipeline(
        val id: String,
        val name: String,
        val sttEngine: String?,
        val ttsEngine: String?,
        val conversationEngine: String?,
        val language: String?,
        val isPreferred: Boolean = false
    )

    /**
     * Connect to Home Assistant WebSocket API
     */
    fun connect() {
        // Extract base URL (scheme + host + port) from full HA URL
        // haUrl might be like "http://192.168.1.50:8123/lovelace-dashboard?params"
        // We need just "ws://192.168.1.50:8123/api/websocket"
        val baseUrl = extractBaseUrl(haUrl)
        val wsUrl = baseUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://") + "/api/websocket"

        Log.d(TAG, "Connecting to: $wsUrl (from haUrl: $haUrl)")

        // 🔴 This call KILLED THE APP. `Request.Builder.url()` throws IllegalArgumentException on
        // a scheme-less origin, and `connect()` runs on the MAIN thread inside
        // `initializeVoicePipeline` — so a stored "192.168.1.5:8123" produced a boot crash LOOP,
        // ~10 s per cycle, on a device that was otherwise fine (T, 2026-08-02).
        //
        // `ConnectionPreferences.haUrl` now repairs a missing scheme at the value, which is the
        // real fix. This guard stays anyway, and the distinction matters: that one prevents the
        // known cause, this one bounds the BLAST RADIUS of any future malformed origin. A voice
        // client cannot be allowed to take the whole app down on boot for a bad string —
        // degrade loudly, never crash. Same shape as HaEventSubscriber's, third instance of it.
        val request = runCatching { Request.Builder().url(wsUrl).build() }
            .onFailure {
                Log.e(TAG, "DROP: HA Assist cannot connect — malformed origin '$wsUrl' " +
                    "(from haUrl '$haUrl'): ${it.javaClass.simpleName} ${it.message}. " +
                    "Voice via HA Assist is unavailable until the HA URL is fixed.", it)
                onError?.invoke("Invalid Home Assistant URL")
            }
            .getOrNull() ?: return

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                onConnected?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleTextMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary messages not expected from HA
                Log.w(TAG, "Received unexpected binary message: ${bytes.size} bytes")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code - $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                isAuthenticated = false
                sttBinaryHandlerId = null
                onDisconnected?.invoke(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                isAuthenticated = false
                sttBinaryHandlerId = null
                onError?.invoke("Connection failed: ${t.message}")
                onDisconnected?.invoke(t.message ?: "Unknown error")
            }
        })
    }

    /**
     * Disconnect from WebSocket
     */
    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        isAuthenticated = false
        sttBinaryHandlerId = null
    }

    /**
     * Check if connected and authenticated
     */
    fun isReady(): Boolean = webSocket != null && isAuthenticated

    /**
     * Start Assist pipeline with audio input
     * Call this after authentication succeeds
     *
     * @param endAtIntent If true, end pipeline at intent stage (skip TTS).
     *                    Useful when using device-local TTS instead of HA TTS.
     * @param pipelineId Optional pipeline ID to use. If null or empty, uses HA's preferred pipeline.
     */
    fun startAudioPipeline(endAtIntent: Boolean = false, pipelineId: String? = null) {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot start pipeline - not authenticated")
            onError?.invoke("Not authenticated")
            return
        }

        val endStage = if (endAtIntent) "intent" else "tts"
        val id = messageId.getAndIncrement()
        val message = JSONObject().apply {
            put("id", id)
            put("type", "assist_pipeline/run")
            put("start_stage", "stt")
            put("end_stage", endStage)
            put("input", JSONObject().apply {
                put("sample_rate", 16000)
            })
            // Only include pipeline parameter if a specific pipeline is selected
            if (!pipelineId.isNullOrEmpty()) {
                put("pipeline", pipelineId)
            }
        }

        Log.d(TAG, "Starting audio pipeline (id=$id, endStage=$endStage, pipeline=${pipelineId ?: "default"})")
        sendMessage(message.toString())
    }

    /**
     * Request list of available Assist pipelines from Home Assistant.
     * Results will be delivered via onPipelinesReceived callback.
     */
    fun requestPipelineList() {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot request pipelines - not authenticated")
            onError?.invoke("Not authenticated")
            return
        }

        val id = messageId.getAndIncrement()
        val message = JSONObject().apply {
            put("id", id)
            put("type", "assist_pipeline/pipeline/list")
        }

        Log.d(TAG, "Requesting pipeline list (id=$id)")
        sendMessage(message.toString())
    }

    /**
     * Start Assist pipeline with text input — HA runs intent (+ optionally TTS) on a transcript
     * Dashie already has. This is the local-STT path in HA Voice Assist mode: Dashie transcribes
     * on-device (sherpa / engine-direct Whisper / own-box) and HA still owns intent, execution and
     * the response, so the pipeline's `stt_engine` is never consulted.
     *
     * Mirrors [startAudioPipeline]'s parameters deliberately — the two only differ in start_stage.
     * Before 2026-07-29 this hardcoded `end_stage=tts` and ignored the selected pipeline, which
     * would have overridden a local-TTS choice and used HA's *preferred* pipeline regardless.
     *
     * @param endAtIntent If true, end at the intent stage (skip HA TTS) — device-local TTS speaks.
     * @param pipelineId Optional pipeline ID. If null or empty, uses HA's preferred pipeline.
     */
    fun startTextPipeline(text: String, endAtIntent: Boolean = false, pipelineId: String? = null) {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot start pipeline - not authenticated")
            onError?.invoke("Not authenticated")
            return
        }

        val endStage = if (endAtIntent) "intent" else "tts"
        val id = messageId.getAndIncrement()
        val message = JSONObject().apply {
            put("id", id)
            put("type", "assist_pipeline/run")
            put("start_stage", "intent")
            put("end_stage", endStage)
            put("input", JSONObject().apply {
                put("text", text)
            })
            if (!pipelineId.isNullOrEmpty()) put("pipeline", pipelineId)
        }

        Log.d(TAG, "Starting text pipeline (id=$id, endStage=$endStage, " +
            "pipeline=${pipelineId ?: "default"}): $text")
        sendMessage(message.toString())
    }

    /**
     * Synthesize [text] via the pipeline's TTS engine ONLY — start AND end at
     * the tts stage, no intent/conversation. Used by the cloud-brain path
     * (VoicePipelineCoordinator): the AI response is already generated by
     * Dashie's brain, and we only want HA to voice it (e.g. local Piper /
     * Azure) instead of device TTS or ElevenLabs. Emits `onTtsEnd(url, mime)`.
     *
     * @param pipelineId optional pipeline whose tts_engine to use (null = HA's preferred)
     */
    fun startTtsPipeline(text: String, pipelineId: String? = null) {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot start TTS pipeline - not authenticated")
            onError?.invoke("Not authenticated")
            return
        }

        val id = messageId.getAndIncrement()
        val message = JSONObject().apply {
            put("id", id)
            put("type", "assist_pipeline/run")
            put("start_stage", "tts")
            put("end_stage", "tts")
            put("input", JSONObject().apply {
                put("text", text)
            })
            if (!pipelineId.isNullOrEmpty()) put("pipeline", pipelineId)
        }

        Log.d(TAG, "Starting TTS-only pipeline (id=$id, pipeline=${pipelineId ?: "default"}): ${text.take(50)}")
        sendMessage(message.toString())
    }

    /**
     * Stream audio chunk to HA
     * Must call startAudioPipeline() first and wait for onPipelineStarted callback
     *
     * @param audioData Raw 16-bit PCM audio at 16kHz mono
     */
    fun streamAudio(audioData: ByteArray) {
        val handlerId = sttBinaryHandlerId
        if (handlerId == null) {
            Log.w(TAG, "Cannot stream audio - pipeline not started")
            return
        }

        // Binary message format: [handler_id (1 byte)][audio_data...]
        val binaryMessage = ByteArray(1 + audioData.size)
        binaryMessage[0] = handlerId.toByte()
        System.arraycopy(audioData, 0, binaryMessage, 1, audioData.size)

        webSocket?.send(binaryMessage.toByteString())
    }

    /**
     * Signal end of audio stream
     * Call this when user stops speaking
     */
    fun endAudioStream() {
        val handlerId = sttBinaryHandlerId
        if (handlerId == null) {
            Log.w(TAG, "Cannot end audio stream - pipeline not started")
            return
        }

        Log.d(TAG, "Ending audio stream (handler=$handlerId)")

        // Send empty audio chunk to signal end
        val endMessage = ByteArray(1)
        endMessage[0] = handlerId.toByte()
        webSocket?.send(endMessage.toByteString())

        // Clear handler ID - pipeline is done after this
        sttBinaryHandlerId = null
    }

    /**
     * Handle incoming text messages from HA
     */
    private fun handleTextMessage(text: String) {
        try {
            Log.d(TAG, "Received message: ${text.take(200)}...")
            val msg = JSONObject(text)
            val type = msg.optString("type")

            when (type) {
                "auth_required" -> {
                    Log.d(TAG, "Auth required, sending token")
                    sendAuthMessage()
                }

                "auth_ok" -> {
                    Log.d(TAG, "Auth successful")
                    isAuthenticated = true
                    onAuthSuccess?.invoke()
                }

                "auth_invalid" -> {
                    val message = msg.optString("message", "Authentication failed")
                    Log.e(TAG, "Auth failed: $message")
                    isAuthenticated = false
                    onAuthFailed?.invoke(message)
                }

                "result" -> {
                    handleResultMessage(msg)
                }

                "event" -> {
                    handleEventMessage(msg)
                }

                else -> {
                    Log.d(TAG, "Unhandled message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}", e)
            onError?.invoke("Parse error: ${e.message}")
        }
    }

    /**
     * Handle result messages (response to our requests)
     */
    private fun handleResultMessage(msg: JSONObject) {
        val id = msg.optInt("id")
        val success = msg.optBoolean("success", false)
        Log.d(TAG, "Result message: id=$id, success=$success")

        if (!success) {
            val error = msg.optJSONObject("error")
            val errorMessage = error?.optString("message") ?: "Unknown error"
            Log.e(TAG, "Request $id failed: $errorMessage")
            onError?.invoke(errorMessage)
            return
        }

        val result = msg.optJSONObject("result")
        Log.d(TAG, "Result content: $result")
        if (result != null) {
            // Check for pipeline list response
            val pipelines = result.optJSONArray("pipelines")
            if (pipelines != null) {
                val preferredPipelineId = result.optString("preferred_pipeline", "")
                val pipelineList = mutableListOf<Pipeline>()

                for (i in 0 until pipelines.length()) {
                    val pipelineJson = pipelines.optJSONObject(i) ?: continue
                    val pipelineId = pipelineJson.optString("id", "")
                    if (pipelineId.isEmpty()) continue

                    pipelineList.add(Pipeline(
                        id = pipelineId,
                        name = pipelineJson.optString("name", "Unnamed"),
                        sttEngine = pipelineJson.optString("stt_engine").takeIf { it.isNotEmpty() },
                        ttsEngine = pipelineJson.optString("tts_engine").takeIf { it.isNotEmpty() },
                        conversationEngine = pipelineJson.optString("conversation_engine").takeIf { it.isNotEmpty() },
                        language = pipelineJson.optString("language").takeIf { it.isNotEmpty() },
                        isPreferred = (pipelineId == preferredPipelineId)
                    ))
                }

                Log.d(TAG, "Received ${pipelineList.size} pipelines (preferred: $preferredPipelineId)")
                onPipelinesReceived?.invoke(pipelineList)
                return
            }

            // Check for runner_data which contains the binary handler ID
            val runnerData = result.optJSONObject("runner_data")
            Log.d(TAG, "Runner data: $runnerData")
            if (runnerData != null) {
                val handlerId = runnerData.optInt("stt_binary_handler_id", -1)
                Log.d(TAG, "Handler ID from runner_data: $handlerId")
                if (handlerId >= 0) {
                    Log.d(TAG, "Pipeline started, handler_id=$handlerId")
                    sttBinaryHandlerId = handlerId
                    onPipelineStarted?.invoke(handlerId)
                }
            }
        }
    }

    /**
     * Handle event messages (pipeline events)
     */
    private fun handleEventMessage(msg: JSONObject) {
        val event = msg.optJSONObject("event") ?: return
        val eventType = event.optString("type")
        val data = event.optJSONObject("data")

        Log.d(TAG, "Pipeline event: $eventType")

        when (eventType) {
            "run-start" -> {
                // Extract stt_binary_handler_id from run-start event
                val runnerData = data?.optJSONObject("runner_data")
                Log.d(TAG, "run-start runner_data: $runnerData")
                if (runnerData != null) {
                    val handlerId = runnerData.optInt("stt_binary_handler_id", -1)
                    Log.d(TAG, "Handler ID from run-start: $handlerId")
                    if (handlerId >= 0) {
                        Log.d(TAG, "Pipeline started, handler_id=$handlerId")
                        sttBinaryHandlerId = handlerId
                        onPipelineStarted?.invoke(handlerId)
                    }
                }
            }

            "stt-start" -> {
                onSttStart?.invoke()
            }

            "stt-end" -> {
                val sttOutput = data?.optJSONObject("stt_output")
                val text = sttOutput?.optString("text") ?: ""
                Log.d(TAG, "STT result: $text")
                onSttEnd?.invoke(text)
            }

            "intent-end" -> {
                val intentOutput = data?.optJSONObject("intent_output")
                val intent = intentOutput?.optJSONObject("intent")?.optString("name") ?: ""

                // Extract speech text from response.speech.plain.speech
                // HA response structure: { response: { speech: { plain: { speech: "text" } } } }
                val responseObj = intentOutput?.optJSONObject("response")
                val speechObj = responseObj?.optJSONObject("speech")
                val plainObj = speechObj?.optJSONObject("plain")
                val speechText = plainObj?.optString("speech") ?: ""
                // response_type distinguishes an executed command ("action_done")
                // from an informational answer ("query_answer") or "error". BUT HA
                // reports action_done for some informational intents too (e.g.
                // "what time is it" → speech "4:54 PM", type action_done) — the real
                // discriminator is whether the action touched any targets: device
                // commands list affected entities in data.success/failed; pure
                // answers act on none. Demote target-less action_done to
                // query_answer so the confirmation tone can't swallow the answer.
                var responseType = responseObj?.optString("response_type") ?: ""
                if (responseType == "action_done") {
                    val dataObj = responseObj?.optJSONObject("data")
                    val acted = (dataObj?.optJSONArray("success")?.length() ?: 0) +
                        (dataObj?.optJSONArray("failed")?.length() ?: 0) > 0
                    if (!acted) responseType = "query_answer"
                }

                Log.d(TAG, "Intent: $intent, Speech text: $speechText, type: $responseType")
                Log.d(TAG, "Full response object: $responseObj")
                onIntentEnd?.invoke(intent, speechText, responseType)
            }

            "tts-end" -> {
                val ttsOutput = data?.optJSONObject("tts_output")
                val url = ttsOutput?.optString("url") ?: ""
                val mimeType = ttsOutput?.optString("mime_type") ?: "audio/mpeg"
                Log.d(TAG, "TTS output: $url ($mimeType)")
                onTtsEnd?.invoke(url, mimeType)
            }

            "run-end" -> {
                Log.d(TAG, "Pipeline run ended")
                sttBinaryHandlerId = null
                onRunEnd?.invoke()
            }

            "error" -> {
                val errorCode = data?.optString("code") ?: "unknown"
                val errorMessage = data?.optString("message") ?: "Pipeline error"
                Log.d(TAG, "Pipeline event error: $errorCode - $errorMessage")

                // Handle STT no-text-recognized as a friendly "didn't understand" rather than error
                if (errorCode == "stt-no-text-recognized") {
                    Log.d(TAG, "STT couldn't recognize speech - triggering friendly handler")
                    onSttNoText?.invoke()
                } else {
                    Log.e(TAG, "Pipeline error: $errorCode - $errorMessage")
                    onError?.invoke("$errorCode: $errorMessage")
                }
            }
        }
    }

    /**
     * Send authentication message
     */
    private fun sendAuthMessage() {
        val authMsg = JSONObject().apply {
            put("type", "auth")
            put("access_token", accessToken)
        }
        sendMessage(authMsg.toString())
    }

    /**
     * Send a text message through WebSocket
     */
    private fun sendMessage(message: String) {
        val sent = webSocket?.send(message) ?: false
        if (!sent) {
            Log.e(TAG, "Failed to send message")
            onError?.invoke("Failed to send message")
        }
    }

    /**
     * Extract base URL (scheme + host + port) from a full URL
     * Examples:
     *   "http://192.168.1.50:8123/lovelace?params" -> "http://192.168.1.50:8123"
     *   "https://ha.example.com/dashboard" -> "https://ha.example.com"
     */
    private fun extractBaseUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val port = if (uri.port != -1) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}$port"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse URL: $url", e)
            // Fallback: try to extract manually
            val schemeEnd = url.indexOf("://")
            if (schemeEnd == -1) return url

            val pathStart = url.indexOf('/', schemeEnd + 3)
            val queryStart = url.indexOf('?', schemeEnd + 3)

            val endIndex = when {
                pathStart != -1 && queryStart != -1 -> minOf(pathStart, queryStart)
                pathStart != -1 -> pathStart
                queryStart != -1 -> queryStart
                else -> url.length
            }

            url.substring(0, endIndex)
        }
    }
}

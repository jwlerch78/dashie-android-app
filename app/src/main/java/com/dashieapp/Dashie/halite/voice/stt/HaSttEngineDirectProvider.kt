package com.dashieapp.Dashie.halite.voice.stt

import android.util.Log
import com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HA engine-direct STT (the `ha_engine` transport) — POSTs the captured utterance
 * straight to a Home Assistant STT *engine* (faster-whisper, or any other), with
 * NO Assist pipeline. The lighter cousin of [HaAssistSttProvider] (which streams
 * over WebSocket through a pipeline).
 *
 * Contract (build plan 20260708 §4.2):
 *   POST {haOrigin}/api/stt/{engineId}
 *   header X-Speech-Content: format=wav; codec=pcm; sample_rate=16000; bit_rate=16; channel=1; language=en
 *   body: raw 16-bit mono PCM @16 kHz  → { "text": "...", "result": "success" }
 *
 * ⚠️ The exact header/body shape is the one part §4.3 could not verify without a
 * live mic capture — confirm on-device. We send RAW PCM (codec=pcm) rather than a
 * WAV container, matching HA's streaming STT contract.
 */
class HaSttEngineDirectProvider : BufferedPostSttProvider() {
    companion object {
        private const val TAG = "HaSttEngineDirect"
        private val PCM = "application/octet-stream".toMediaType()
    }

    override val providerId = "ha_engine"
    override val displayName = "Home Assistant (engine-direct)"

    private var haOrigin = ""
    private var haToken = ""
    private var haTokenProvider: (() -> String)? = null
    private var engineId = ""

    // The HA token rotates ~every 30 min — resolve it at REQUEST time via the live
    // provider, never the init-time snapshot ("Invalid access token" ~30 min after
    // every app start, 2026-07-13). Snapshot kept only as a last-resort fallback.
    private fun currentToken(): String =
        haTokenProvider?.invoke()?.takeIf { it.isNotEmpty() } ?: haToken

    private val http = LocalHostsTrustingHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun isAvailable(): Boolean =
        haOrigin.isNotEmpty() && currentToken().isNotEmpty() && engineId.isNotEmpty()

    override fun onInitialize(config: SttConfig): Boolean {
        haOrigin = (config.haUrl ?: "").trimEnd('/')
        haToken = config.haToken ?: ""
        haTokenProvider = config.haTokenProvider
        engineId = config.haSttEngineId ?: ""
        Log.i(TAG, "Initialized (engine=${engineId.ifBlank { "none" }}, origin=${haOrigin.ifBlank { "none" }})")
        return isAvailable()
    }

    override fun transcribe(pcm: ByteArray, onText: (String?) -> Unit, onError: (String) -> Unit) {
        val req = Request.Builder()
            .url("$haOrigin/api/stt/$engineId")
            .addHeader("Authorization", "Bearer ${currentToken()}")
            .addHeader("X-Speech-Content", "format=wav; codec=pcm; sample_rate=$SAMPLE_RATE; bit_rate=16; channel=1; language=en")
            .post(pcm.toRequestBody(PCM))
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("HA STT request failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                val body = try { response.body?.string() } catch (_: Exception) { null }
                val code = response.code
                runCatching { response.close() }
                if (code !in 200..299 || body.isNullOrBlank()) { onError("HA STT HTTP $code"); return }
                val text = try {
                    val o = JSONObject(body)
                    if (o.optString("result", "success") == "success") o.optString("text") else ""
                } catch (e: Exception) { "" }
                onText(text)
            }
        })
    }
}

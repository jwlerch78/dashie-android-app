package com.dashieapp.Dashie.halite.voice.stt

import android.util.Log
import com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Own-box Whisper STT (the `local_stt_url` transport) — POSTs the captured
 * utterance to a user-hosted OpenAI-compatible transcription server on their LAN
 * (faster-whisper / speaches / whisper.cpp server). Mirrors how [LocalTtsClient]
 * points straight at the user's box: no cloud, no HA hop.
 *
 * Contract (OpenAI Audio API):
 *   POST {baseUrl}/v1/audio/transcriptions   (multipart/form-data)
 *   fields: file=<audio.wav>, model=<id>   → { "text": "..." }
 *
 * The captured PCM is wrapped as a WAV (server expects a real audio file).
 * LAN-trusting HTTP client so a self-signed https box still works.
 */
class LocalWhisperSttProvider : BufferedPostSttProvider() {
    companion object {
        private const val TAG = "LocalWhisperStt"
        // OpenAI-compatible servers require a model field; "whisper-1" is the
        // conventional default and is accepted/ignored by faster-whisper servers.
        private const val DEFAULT_MODEL = "whisper-1"
        private val WAV = "audio/wav".toMediaType()
    }

    override val providerId = "local_stt_url"
    override val displayName = "Local Whisper (your box)"

    private var baseUrl = ""

    private val http = LocalHostsTrustingHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun isAvailable(): Boolean = baseUrl.isNotEmpty()

    override fun onInitialize(config: SttConfig): Boolean {
        baseUrl = (config.localSttUrl ?: "").trim().trimEnd('/')
        Log.i(TAG, "Initialized (url=${baseUrl.ifBlank { "none" }})")
        return isAvailable()
    }

    override fun transcribe(pcm: ByteArray, onText: (String?) -> Unit, onError: (String) -> Unit) {
        val wav = pcmToWav(pcm)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", DEFAULT_MODEL)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody(WAV))
            .build()
        val req = Request.Builder()
            .url("$baseUrl/v1/audio/transcriptions")
            .post(body)
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("Local Whisper request failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                val respBody = try { response.body?.string() } catch (_: Exception) { null }
                val code = response.code
                runCatching { response.close() }
                if (code !in 200..299 || respBody.isNullOrBlank()) { onError("Local Whisper HTTP $code"); return }
                val text = try { JSONObject(respBody).optString("text") } catch (e: Exception) { "" }
                onText(text)
            }
        })
    }
}

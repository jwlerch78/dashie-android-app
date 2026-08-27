package com.dashieapp.Dashie.halite.voice

import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Pre-warms the HA-local voice engines so the FIRST real turn doesn't pay their
 * cold start (the "long lag the first time a voice is read" with Piper,
 * 2026-07-12).
 *
 * What "cold" means per engine:
 *   • Piper (TTS): the add-on process is resident, but each VOICE MODEL (.onnx)
 *     loads lazily on its first synthesis and then stays in memory for the life
 *     of the add-on. So the lag is paid once per add-on lifetime per voice —
 *     it does NOT idle out, but it comes back after an HA/add-on restart or a
 *     voice change.
 *   • faster-whisper (STT): the model loads when the add-on starts; the first
 *     transcription still pays one-time compute warmup. Same story: no idle
 *     unload, resets on add-on restart.
 *
 * So warming once per (engine, voice) per app session is right, re-warmed when
 * the selection changes (reinitialize() re-runs init) — [warmIfConfigured] is
 * idempotent per key. Both warms are tiny (a one-word synth with cache:false,
 * half a second of silent PCM) and entirely LAN-local.
 *
 * ⚠️ The Piper warm must NOT hit HA's TTS cache — a disk-cached URL is served
 * without running Piper at all, which would "warm" nothing. `cache: false`
 * forces a real synthesis and persists nothing.
 *
 * Request shapes mirror the real clients (tts.HaTtsEngineDirectClient §4.1,
 * stt.HaSttEngineDirectProvider §4.2) — keep them in sync.
 */
class LocalEngineWarmer(private val prefs: HalitePreferences) {
    companion object {
        private const val TAG = "LocalEngineWarmer"
        private val JSON = "application/json".toMediaType()
        private val PCM = "application/octet-stream".toMediaType()
        private const val WARM_TEXT = "Ready."
        private const val SILENCE_MS = 500   // 0.5 s of 16 kHz 16-bit mono silence
    }

    private val http = LocalHostsTrustingHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)   // a cold Piper model load can take several seconds
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var warmedTts = ""   // "engineId|voice" last warmed — idempotence per selection
    private var warmedStt = ""
    private var warmedTtsAt = 0L
    private var warmedSttAt = 0L
    // Re-warm after this long even for the same selection: an HA/add-on restart drops
    // Piper's loaded model, and we can't observe that — a cheap periodic re-warm (at
    // most one tiny synth per interval, and only when a wake actually happens) heals it.
    private val rewarmAfterMs = 10 * 60_000L

    /** Fire-and-forget: warm whichever HA-local engines the current prefs select.
     *  Safe to call repeatedly (init, each wake, reinitialize) — each engine warms
     *  once per selection per [rewarmAfterMs] window. Called at wake time this also
     *  overlaps any reload with the STT + brain phase, so TTS is hot by reply time. */
    fun warmIfConfigured() {
        val haOrigin = prefs.connection.haBaseUrl.ifEmpty { prefs.connection.getHaOrigin() ?: "" }
            .trim().trimEnd('/')
        val token = prefs.connection.haAccessToken
        if (haOrigin.isEmpty() || token.isBlank()) return

        val now = System.currentTimeMillis()
        if (prefs.voice.ttsProvider == VoicePreferences.TTS_HA_ENGINE) {
            val engine = prefs.voice.haTtsEngineId
            val voice = prefs.voice.haTtsVoiceId
            val key = "$engine|$voice"
            if (engine.isNotBlank() && (key != warmedTts || now - warmedTtsAt > rewarmAfterMs)) {
                warmedTts = key; warmedTtsAt = now
                warmTts(haOrigin, token, engine, voice)
            }
        }
        if (prefs.voice.sttProvider == VoicePreferences.STT_HA_ENGINE) {
            val engine = prefs.voice.haSttEngineId
            if (engine.isNotBlank() && (engine != warmedStt || now - warmedSttAt > rewarmAfterMs)) {
                warmedStt = engine; warmedSttAt = now
                warmStt(haOrigin, token, engine)
            }
        }
    }

    /** One real (uncached) synthesis loads the Piper voice model into memory. */
    private fun warmTts(haOrigin: String, token: String, engineId: String, voice: String) {
        val language = voice.substringBefore('-').ifBlank { "en_US" }
        val body = JSONObject().apply {
            put("engine_id", engineId)
            put("message", WARM_TEXT)
            put("language", language)
            put("cache", false)   // MUST bypass HA's TTS cache or Piper never runs
            if (voice.isNotBlank()) put("options", JSONObject().put("voice", voice))
        }.toString()
        val req = Request.Builder()
            .url("$haOrigin/api/tts_get_url")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        val t0 = System.currentTimeMillis()
        Log.i(TAG, "🔥 Warming HA TTS engine $engineId (voice=${voice.ifBlank { "default" }})")
        http.newCall(req).enqueue(loggingCallback("TTS $engineId", t0))
    }

    /** Half a second of silence through the STT engine covers any first-request warmup. */
    private fun warmStt(haOrigin: String, token: String, engineId: String) {
        val silence = ByteArray(16_000 * 2 * SILENCE_MS / 1000)   // 16 kHz, 16-bit mono zeros
        val req = Request.Builder()
            .url("$haOrigin/api/stt/$engineId")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("X-Speech-Content",
                "format=wav; codec=pcm; sample_rate=16000; bit_rate=16; channel=1; language=en")
            .post(silence.toRequestBody(PCM))
            .build()
        val t0 = System.currentTimeMillis()
        Log.i(TAG, "🔥 Warming HA STT engine $engineId")
        http.newCall(req).enqueue(loggingCallback("STT $engineId", t0))
    }

    private fun loggingCallback(label: String, t0: Long) = object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.w(TAG, "🔥 Warm $label failed (harmless): ${e.message}")
        }
        override fun onResponse(call: Call, response: okhttp3.Response) {
            val ms = System.currentTimeMillis() - t0
            runCatching { response.close() }
            Log.i(TAG, "🔥 Warm $label done in ${ms}ms (HTTP ${response.code})")
        }
    }
}

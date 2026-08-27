package com.dashieapp.Dashie.halite.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HA engine-direct TTS (the `ha_engine` transport) — hits a Home Assistant TTS
 * *engine* (Piper, or any other) directly, with NO Assist pipeline. Lighter cousin
 * of [com.dashieapp.Dashie.halite.voice.HaTtsSynthesizer] (TTS-only Assist pipeline
 * over WebSocket): here we POST the text to HA's REST synthesis endpoint, DOWNLOAD
 * the resulting audio, and play it.
 *
 * Contract (validated against a real HA, build plan 20260708 §4.1):
 *   POST {haBaseUrl}/api/tts_get_url  { engine_id, message, language, options:{ voice } }
 *   → { url, path }   (url = absolute http://<ha>/api/tts_proxy/<hash>.mp3)
 *
 * ⚠️ We DON'T hand the URL to a streaming MediaPlayer. HA serves the proxy audio
 * `transfer-encoding: chunked` (no Content-Length) → Android MediaPlayer reports
 * duration=-1 and, behind a Samsung Knox network filter, took ~10s to fetch and
 * produced no sound (device-verified 2026-07-10). Instead we fetch the bytes with
 * OkHttp (73ms on the same box) and play from a temp file with USAGE_MEDIA — the
 * same robust path [LocalTtsClient] uses for its MP3 branch. The proxy URL is
 * signed (plays with any/no auth) but we still send the bearer for parity.
 *
 * Engine + voice + HA creds are passed per-utterance, so one lazily-constructed
 * instance serves any HA engine. LAN-trusting HTTP client so a self-signed https HA works.
 */
class HaTtsEngineDirectClient(
    private val context: Context,
) {
    companion object {
        private const val TAG = "HaTtsEngineDirect"
        private const val DEFAULT_LANGUAGE = "en_US"
        private val JSON = "application/json".toMediaType()
    }

    private val http = LocalHostsTrustingHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private var call: Call? = null
    private var player: MediaPlayer? = null
    private var clipPlayer: AecTeedClipPlayer? = null
    private var generation = 0

    /** WS-F.0b: cascade AEC render-reference hookup. When set and the AEC wants PCM, the
     *  downloaded audio is decoded and played through the teed clip player; MediaPlayer
     *  stays as the fallback. Null → legacy MediaPlayer only. */
    var aecControllerProvider: (() -> com.dashieapp.Dashie.halite.voice.aec.CascadeAecController?)? = null

    /**
     * Synthesize [text] on HA engine [engineId] with [voice] and play it. onStart/onDone
     * fire on the main thread; onDone fires EXACTLY once (completion / error / superseded).
     */
    fun speak(
        text: String,
        haBaseUrl: String,
        haToken: String,
        engineId: String,
        voice: String,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val fired = AtomicBoolean(false)
        val done: () -> Unit = { if (fired.compareAndSet(false, true)) main.post { onDone() } }
        val fail: (String) -> Unit = { e -> main.post { onError(e) }; done() }

        val base = haBaseUrl.trim().trimEnd('/')
        if (base.isEmpty() || engineId.isBlank()) { fail("HA engine-direct TTS not configured"); return }
        if (text.isBlank()) { done(); return }

        val gen = ++generation
        stopPlaybackOnly()

        // Piper voice ids look like en_US-amy-medium → locale is the part before the
        // first '-'. Blank voice → engine default + a safe default language.
        val language = voice.substringBefore('-').ifBlank { DEFAULT_LANGUAGE }
        val body = JSONObject().apply {
            put("engine_id", engineId)
            put("message", text)
            put("language", language)
            if (voice.isNotBlank()) put("options", JSONObject().put("voice", voice))
        }.toString()

        val req = Request.Builder()
            .url("$base/api/tts_get_url")
            .addHeader("Authorization", "Bearer $haToken")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()

        Log.i(TAG, "HA engine-direct TTS → $engineId (voice=${voice.ifBlank { "default" }}, lang=$language): '${text.take(50)}'")
        call = http.newCall(req).also { c ->
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "tts_get_url failed: ${e.message}")
                    fail("HA TTS request failed: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    val bodyStr = try { response.body?.string() } catch (_: Exception) { null }
                    val code = response.code
                    runCatching { response.close() }
                    if (code !in 200..299 || bodyStr.isNullOrBlank()) { fail("HA TTS HTTP $code"); return }
                    val pathOrUrl = try {
                        val o = JSONObject(bodyStr); o.optString("url").ifBlank { o.optString("path") }
                    } catch (e: Exception) { "" }
                    if (pathOrUrl.isBlank()) { fail("HA TTS returned no url"); return }
                    if (gen != generation) { done(); return }
                    val audioUrl = if (pathOrUrl.startsWith("http")) pathOrUrl else base + pathOrUrl
                    fetchAndPlay(gen, audioUrl, haToken, onStart, done, fail)
                }
            })
        }
    }

    /** GET the proxy audio with OkHttp (fast + chunked-safe, unlike MediaPlayer's HTTP)
     *  then play the bytes from a temp file. */
    private fun fetchAndPlay(gen: Int, audioUrl: String, token: String, onStart: () -> Unit, done: () -> Unit, fail: (String) -> Unit) {
        val req = Request.Builder().url(audioUrl).addHeader("Authorization", "Bearer $token").get().build()
        call = http.newCall(req).also { c ->
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { fail("HA TTS audio fetch failed: ${e.message}") }
                override fun onResponse(call: Call, response: Response) {
                    val bytes = try { response.body?.bytes() } catch (_: Exception) { null }
                    val code = response.code
                    runCatching { response.close() }
                    if (code !in 200..299 || bytes == null || bytes.isEmpty()) { fail("HA TTS audio HTTP $code"); return }
                    if (gen != generation) { done(); return }
                    main.post { playBytes(gen, bytes, onStart, done, fail) }
                }
            })
        }
    }

    private fun playBytes(gen: Int, audio: ByteArray, onStart: () -> Unit, done: () -> Unit, fail: (String) -> Unit) {
        if (gen != generation) { done(); return }
        val tmp = try {
            File.createTempFile("dashie_ha_tts_", ".mp3", context.cacheDir).apply { writeBytes(audio) }
        } catch (e: Exception) { fail("HA TTS buffer failed: ${e.message}"); return }

        // AEC-teed route (WS-F.0b): decode → PcmTtsPlayer with render tee. Decode failure
        // falls back to the MediaPlayer path below (echo-blind, never silent).
        if (aecControllerProvider?.invoke()?.wantsPcm() == true) {
            val clip = AecTeedClipPlayer(aecControllerProvider)
            clipPlayer = clip
            clip.play(
                tmp, deleteFileWhenDone = true,
                onStart = { if (gen == generation) onStart() },
                onComplete = { if (clipPlayer === clip) clipPlayer = null; done() },
                onUnplayable = {
                    if (clipPlayer === clip) clipPlayer = null
                    if (gen != generation) { tmp.delete(); done(); return@play }
                    Log.w(TAG, "AEC-path decode failed — falling back to MediaPlayer")
                    playViaMediaPlayer(gen, tmp, onStart, done, fail)
                }
            )
            return
        }
        playViaMediaPlayer(gen, tmp, onStart, done, fail)
    }

    private fun playViaMediaPlayer(gen: Int, tmp: File, onStart: () -> Unit, done: () -> Unit, fail: (String) -> Unit) {
        if (gen != generation) { tmp.delete(); done(); return }
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)                 // media stream — audible; matches LocalTtsClient
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(tmp.absolutePath)
            mp.setOnPreparedListener { p ->
                if (gen != generation) { runCatching { p.release() }; tmp.delete(); done(); return@setOnPreparedListener }
                onStart(); p.start()
            }
            mp.setOnCompletionListener { p -> runCatching { p.release() }; tmp.delete(); if (player === p) player = null; done() }
            mp.setOnErrorListener { p, what, extra ->
                Log.e(TAG, "MediaPlayer error $what/$extra"); runCatching { p.release() }; tmp.delete()
                if (player === p) player = null; fail("HA TTS playback error $what"); true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) { Log.e(TAG, "MediaPlayer setup failed: ${e.message}"); tmp.delete(); fail("HA TTS player error: ${e.message}") }
    }

    /** Stop any in-flight request + playback (barge-in / supersede). */
    fun stop() {
        generation++
        call?.cancel(); call = null
        stopPlaybackOnly()
    }

    private fun stopPlaybackOnly() {
        clipPlayer?.stop(); clipPlayer = null
        player?.let { p -> runCatching { if (p.isPlaying) p.stop() }; runCatching { p.release() } }
        player = null
    }

    fun release() { stop() }
}

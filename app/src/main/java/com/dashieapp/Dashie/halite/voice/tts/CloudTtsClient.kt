package com.dashieapp.Dashie.halite.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vendor config for a Supabase-proxied cloud TTS edge function. The device only ever
 * holds the Supabase anon key; the vendor API key stays server-side. [defaultVoiceId] is
 * used when the caller passes no voice.
 */
data class TtsVendor(
    val name: String,           // logging/label only; the edge fn records its own provider
    val path: String,           // edge-fn path, e.g. "/functions/v1/elevenlabs-tts"
    val defaultVoiceId: String,
    val modelId: String,
) {
    companion object {
        // Dashie personality voices: Bella (ElevenLabs) / Ashley (Inworld). See Phase-2
        // (brain returns provider+voice per personality) for the account-driven version.
        val ELEVENLABS = TtsVendor(
            name = "elevenlabs",
            path = "/functions/v1/elevenlabs-tts",
            defaultVoiceId = "EXAVITQu4vr4xnSDxMaL",   // Bella
            modelId = "eleven_flash_v2_5",             // Flash v2.5 for latency
        )
        val INWORLD = TtsVendor(
            name = "inworld",
            path = "/functions/v1/inworld-tts",
            defaultVoiceId = "Ashley",                 // Inworld voices are name-based
            modelId = "inworld-tts-2",
        )
    }
}

/**
 * Native cloud TTS via a Supabase edge function, vendor-parameterized ([vendor]).
 * ElevenLabs and Inworld are thin subclasses that differ only in endpoint, default voice,
 * and model — all playback/streaming/billing/AEC/generation logic is shared here.
 *
 * Mirrors [com.dashieapp.Dashie.halite.voice.stt.DeepgramSttProvider]'s edge-proxy +
 * anon-key pattern. Used by the NATIVE voice pipeline for AI-lane responses (kiosk single/
 * `handleBrainConverse` + cascade Dialog) so a reply is spoken with a natural cloud voice
 * and a real playback-complete signal can drive the Dialog follow-up re-arm.
 *
 * Contract:
 *   POST {SUPABASE_URL}{vendor.path}
 *   headers: apikey + Authorization: Bearer (account JWT when available, else anon key)
 *   body:    { text, voice_id, model_id, record_usage, [session_id], output_format, stream }
 *   → 200 audio/pcm (streamed raw PCM), or audio/mpeg (buffered MP3) from an old edge fn.
 *
 * Streaming: always requests pcm_24000 + stream so playback starts as bytes arrive
 * (~250 ms) and can be teed to AEC3 as the echo reference ([PcmTtsPlayer.playStream]). The
 * branch is taken on the RESPONSE Content-Type, so an edge fn that predates streaming
 * silently falls back to MP3+MediaPlayer. Completion, 402 and generation semantics are
 * identical on both branches.
 */
open class CloudTtsClient(
    private val context: Context,
    private val vendor: TtsVendor,
) {
    companion object {
        // Cascade AEC render format: 24 kHz PCM16 mono — the render rate AEC3 is proven at
        // in Live mode (RealtimeAudioIo OUT_RATE).
        private const val PCM_FORMAT = "pcm_24000"
        private const val PCM_RATE = 24000
        private val ANON_KEY = com.dashieapp.Dashie.BuildConfig.SUPABASE_ANON_KEY
        private val JSON = "application/json".toMediaType()

        // ONE OkHttp client shared across vendor instances → one connection pool to the
        // functions host, reused across turns. Combined with warmUp() this lets the reply's
        // TTS call reuse a warm TLS connection instead of a cold ~100–200 ms handshake.
        private val sharedHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    private val tag = "CloudTts:${vendor.name}"
    private val endpoint: String =
        com.dashieapp.Dashie.BuildConfig.SUPABASE_URL.takeIf { it.isNotBlank() }
            ?.let { it.trimEnd('/') + vendor.path } ?: ""

    private val http get() = sharedHttp
    private val main = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var pcmPlayer: PcmTtsPlayer? = null
    private var call: Call? = null
    // Bumped on every speak()/stop(); an in-flight request or prepared player whose captured
    // generation no longer matches has been superseded and must not start playing.
    private var generation = 0

    @Volatile
    var isSpeaking = false
        private set

    /** Account JWT for the Authorization Bearer when available (CR2: identifies the caller so
     *  the edge fn's pre-spend gate can 402). Null/absent → anon key (legacy passthrough). */
    var credentialProvider: (() -> String?)? = null

    /** Fired on a 402 insufficient_credits from the edge fn (CR2) — the caller shows the
     *  prompt-to-choose. onDone still fires exactly once afterward. */
    var onCreditDenied: (() -> Unit)? = null

    /** Cascade AEC (WS-A.2). A provider (not a direct ref) because the controller is
     *  created at voice init, which may run after this client's lazy construction. */
    var aecControllerProvider: (() -> com.dashieapp.Dashie.halite.voice.aec.CascadeAecController?)? = null

    /**
     * Pre-establish the connection to the functions host (and warm the edge fn) so the
     * turn's speak() reuses a warm TLS connection instead of a cold handshake. Fire from
     * wake-word-accept — it overlaps the STT+brain window, so the reply's first audio lands
     * ~100–200 ms sooner. Fire-and-forget; failures are ignored.
     */
    fun warmUp() {
        if (endpoint.isEmpty()) return
        try {
            val req = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", ANON_KEY)
                .method("OPTIONS", null)   // CORS preflight — cheap, opens the connection
                .build()
            sharedHttp.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
        } catch (_: Exception) { /* warmup is best-effort */ }
    }

    /**
     * Fetch `text` from the edge function and play it. All callbacks fire on the main
     * thread. [onDone] fires EXACTLY once — on natural completion, error, empty/failed
     * response, or being superseded — so the caller's flow (e.g. cascade re-arm) always
     * proceeds. [voiceId] blank → [vendor]'s default voice.
     */
    fun speak(text: String, voiceId: String?, sessionId: String? = null, onStart: (() -> Unit)? = null, onDone: () -> Unit) {
        val fired = AtomicBoolean(false)
        val done: () -> Unit = { if (fired.compareAndSet(false, true)) main.post { onDone() } }

        if (endpoint.isEmpty()) {
            Log.w(tag, "No SUPABASE_URL for this flavor — cannot speak")
            done(); return
        }
        if (text.isBlank()) { done(); return }

        val gen = ++generation
        stopPlaybackOnly()   // cut any current playback; the new generation owns the mic now
        isSpeaking = true

        val body = JSONObject().apply {
            put("text", text)
            put("voice_id", voiceId?.takeIf { it.isNotBlank() } ?: vendor.defaultVoiceId)
            put("model_id", vendor.modelId)
            // Server-side metering: the edge fn writes token_usage + the credit debit itself
            // when the Bearer identifies the account. Ignored (no-op) for the anon key.
            put("record_usage", true)
            // Group this TTS usage row with the AI turn(s) it belongs to (console history).
            sessionId?.takeIf { it.isNotBlank() }?.let { put("session_id", it) }
            // Always request raw PCM + streaming so playback starts as bytes arrive (~250 ms)
            // and can be teed to AEC3 as the echo reference. An edge fn that predates
            // output_format/stream returns MP3 — handled by the Content-Type branch below.
            put("output_format", PCM_FORMAT)
            put("stream", true)
        }.toString()

        // Identify the account when we can (CR2 pre-spend gate); anon key otherwise.
        val bearer = credentialProvider?.invoke()?.takeIf { it.isNotBlank() } ?: ANON_KEY
        // Anon TTS is UNBILLED server-side — surface which identity each call carries so a
        // wiring regression (2026-07-12: device spoke anon while the brain leg had the JWT)
        // is visible in logcat instead of silently eating COGS.
        Log.i(tag, "speak: bearer=${if (bearer === ANON_KEY) "ANON (unbilled)" else "account JWT (${bearer.length})"} chars=${text.length}")
        val req = Request.Builder()
            .url(endpoint)
            .addHeader("apikey", ANON_KEY)
            .addHeader("Authorization", "Bearer $bearer")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()

        call = http.newCall(req).also { c ->
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(tag, "TTS request failed: ${e.message}")
                    markIdle(gen); done()
                }

                override fun onResponse(call: Call, response: Response) {
                    // NOTE: no response.use{} — the PCM branch streams the body during
                    // playback, so it must NOT be closed here. Each branch closes the
                    // response when it's done (streamPcm on complete/fail; the MP3 branch
                    // after buffering; error/supersede paths inline).
                    if (response.code == 402) {
                        Log.w(tag, "TTS 402 insufficient_credits")
                        runCatching { response.close() }
                        onCreditDenied?.invoke()
                        markIdle(gen); done(); return
                    }
                    if (!response.isSuccessful) {
                        Log.e(tag, "TTS HTTP ${response.code}")
                        runCatching { response.close() }
                        markIdle(gen); done(); return
                    }
                    if (gen != generation) { runCatching { response.close() }; done(); return }

                    // Branch on what the server actually returned, not what we asked for —
                    // an old edge fn answers our PCM request with MP3 (buffered).
                    val isPcm = response.header("Content-Type")?.startsWith("audio/pcm") == true
                    if (isPcm) {
                        main.post { streamPcm(gen, response, onStart, done) }
                    } else {
                        // MP3 fallback: buffer fully (MediaPlayer needs a complete file), then close.
                        val bytes = try { response.body?.bytes() } catch (_: Exception) { null }
                        runCatching { response.close() }
                        if (gen != generation) { done(); return }
                        if (bytes == null || bytes.isEmpty()) {
                            Log.e(tag, "TTS empty MP3 body")
                            markIdle(gen); done(); return
                        }
                        main.post { playBytes(gen, bytes, onStart, done) }
                    }
                }
            })
        }
    }

    private fun playBytes(gen: Int, mp3: ByteArray, onStart: (() -> Unit)?, done: () -> Unit) {
        if (gen != generation) { done(); return }
        val tmp: File
        try {
            tmp = File.createTempFile("dashie_tts_", ".mp3", context.cacheDir)
            tmp.writeBytes(mp3)
        } catch (e: Exception) {
            Log.e(tag, "Failed to buffer MP3: ${e.message}")
            markIdle(gen); done(); return
        }
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    // USAGE_MEDIA (→ STREAM_MUSIC), NOT USAGE_ASSISTANT — STREAM_ASSISTANT is a
                    // separate stream the media-volume rocker doesn't control and defaults to 0
                    // on some devices (silent). Matches PcmTtsPlayer + the AEC config table.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(tmp.absolutePath)
            mp.setOnPreparedListener { p ->
                if (gen != generation) { runCatching { p.release() }; tmp.delete(); done(); return@setOnPreparedListener }
                onStart?.invoke()
                p.start()
            }
            mp.setOnCompletionListener { p ->
                runCatching { p.release() }; tmp.delete()
                if (player === p) player = null
                markIdle(gen); done()
            }
            mp.setOnErrorListener { p, what, extra ->
                Log.e(tag, "MediaPlayer error what=$what extra=$extra")
                runCatching { p.release() }; tmp.delete()
                if (player === p) player = null
                markIdle(gen); done(); true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(tag, "MediaPlayer setup failed: ${e.message}")
            tmp.delete(); markIdle(gen); done()
        }
    }

    /** Stream a raw pcm_24000 response via [PcmTtsPlayer], playing it progressively as
     *  bytes arrive and teeing head-paced frames to the cascade AEC as the echo reference.
     *  Mirrors [playBytes]' generation + done contract: onComplete fires at REAL playback
     *  end (head drains the last frame). Owns [resp]: closed on complete/failure (and via
     *  the byteStream on stop()/call.cancel()). */
    private fun streamPcm(gen: Int, resp: Response, onStart: (() -> Unit)?, done: () -> Unit) {
        if (gen != generation) { runCatching { resp.close() }; done(); return }
        val stream = resp.body?.byteStream()
        if (stream == null) {
            Log.e(tag, "PCM response has no body")
            runCatching { resp.close() }; markIdle(gen); done(); return
        }
        val aecC = aecControllerProvider?.invoke()
        val engaged = aecC?.onTtsSessionStart() == true
        val p = PcmTtsPlayer(PCM_RATE, if (engaged) aecC!!::onTtsPcmPlayed else null)
        val started = p.playStream(
            stream,
            onStart = { main.post { if (gen == generation) onStart?.invoke() } },
            onComplete = {
                main.post {
                    runCatching { resp.close() }
                    if (pcmPlayer === p) pcmPlayer = null
                    if (engaged) aecC?.onTtsSessionEnd()
                    markIdle(gen); done()
                }
            }
        )
        if (!started) {
            Log.e(tag, "PCM playback failed to start")
            runCatching { resp.close() }
            if (engaged) aecC?.onTtsSessionEnd()
            markIdle(gen); done(); return
        }
        pcmPlayer = p
    }

    /** Stop any in-flight request + playback (e.g. a new wake word interrupts the reply). */
    fun stop() {
        generation++
        call?.cancel(); call = null
        stopPlaybackOnly()
        isSpeaking = false
    }

    private fun stopPlaybackOnly() {
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        player = null
        pcmPlayer?.let { p ->
            runCatching { p.stop() }
            // The cut playback session is over — start the AEC tail-hold now.
            runCatching { aecControllerProvider?.invoke()?.onTtsSessionEnd() }
        }
        pcmPlayer = null
    }

    private fun markIdle(gen: Int) {
        if (gen == generation) isSpeaking = false
    }
}

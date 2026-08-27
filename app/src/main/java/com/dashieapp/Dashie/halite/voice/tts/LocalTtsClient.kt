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
 * Native TTS via a user-hosted OpenAI-compatible server on their LAN — the `local_url`
 * ("Local TTS (your box)") transport. Any engine behind the OpenAI audio API works: Kokoro
 * (kokoro-fastapi), Piper (the Mac openai_shim), speaches, … The user's "own box" runs the
 * server; Dashie points straight at it (no cloud, no HA-Assist hop), matching how the local
 * LLM already works (direct OpenAI-compatible).
 *
 * Contract (kokoro-fastapi / any OpenAI-compatible TTS, incl. the Mac piper shim):
 *   POST {baseUrl}/v1/audio/speech
 *   body: { model:"kokoro", input, voice, response_format:"wav", stream:true, speed }
 *   → WAV (PCM16 mono); the sample rate is read from the RIFF header, so engines with
 *     different native rates all play correctly (Kokoro 24 kHz, Piper 16/22.05 kHz).
 *     Servers ignore unknown model ids or accept "kokoro"; the voice id selects the engine
 *     voice. Fallbacks: a raw-PCM body (no RIFF magic) plays at the legacy 24 kHz, and a
 *     server that returns MP3 is handled by the Content-Type branch (MediaPlayer).
 *
 * Reuses [PcmTtsPlayer] for progressive playback + the cascade-AEC tee, and mirrors
 * [CloudTtsClient]'s generation-guard + fire-once done contract. LAN-trusting HTTP client
 * (like BrainConverseClient) so a local https box with a self-signed cert still works.
 */
class LocalTtsClient(private val context: Context) {
    companion object {
        private const val TAG = "LocalTts"
        // Wire-compat: kokoro-fastapi validates this id; every other supported server
        // (piper shim, speaches with alias) ignores or accepts it. Do NOT change casually.
        private const val MODEL_ID = "kokoro"
        private const val PCM_RATE = 24000   // legacy raw-PCM fallback rate (16-bit LE mono)
        private const val MAX_WAV_CHUNK = 1 shl 20   // sanity cap on pre-data RIFF chunks
        // `internal`, not private: the Voice & AI settings row surfaces this as the effective
        // value when voice.localTtsVoiceId is unset, so the row shows what will ACTUALLY speak.
        // One source of truth — don't copy the literal into the settings wiring.
        internal const val DEFAULT_VOICE = "af_heart"
        private val JSON = "application/json".toMediaType()
    }

    private val http = LocalHostsTrustingHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var pcmPlayer: PcmTtsPlayer? = null
    private var call: Call? = null
    private var generation = 0

    @Volatile
    var isSpeaking = false
        private set

    /** Cascade AEC (WS-A.2): PCM playback teed to AEC3 as the echo reference. */
    var aecControllerProvider: (() -> com.dashieapp.Dashie.halite.voice.aec.CascadeAecController?)? = null

    /**
     * Synthesize [text] at [baseUrl] with [voice] and play it. All callbacks on the main thread.
     * [onDone] fires EXACTLY once (completion / error / superseded). Blank baseUrl → onDone now.
     */
    fun speak(text: String, baseUrl: String?, voice: String?, onStart: (() -> Unit)? = null, onDone: () -> Unit) {
        val fired = AtomicBoolean(false)
        val done: () -> Unit = { if (fired.compareAndSet(false, true)) main.post { onDone() } }

        val url = baseUrl?.trim()?.trimEnd('/')
        if (url.isNullOrEmpty()) { Log.w(TAG, "No local TTS URL configured"); done(); return }
        if (text.isBlank()) { done(); return }

        val gen = ++generation
        stopPlaybackOnly()
        isSpeaking = true

        val body = JSONObject().apply {
            put("model", MODEL_ID)
            put("input", text)
            put("voice", voice?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE)
            put("response_format", "wav")   // rate self-described by the RIFF header → progressive play + AEC tee
            put("stream", true)
            put("speed", 1.0)
        }.toString()

        val req = Request.Builder()
            .url("$url/v1/audio/speech")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()

        Log.i(TAG, "local TTS → $url (voice=${voice ?: DEFAULT_VOICE})")
        call = http.newCall(req).also { c ->
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "local TTS request failed: ${e.message}")
                    markIdle(gen); done()
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        Log.e(TAG, "local TTS HTTP ${response.code}")
                        runCatching { response.close() }; markIdle(gen); done(); return
                    }
                    if (gen != generation) { runCatching { response.close() }; done(); return }
                    // We requested WAV; only a server that ignored it returns audio/mpeg → MP3 path.
                    val isMp3 = response.header("Content-Type")?.startsWith("audio/mpeg") == true
                    if (isMp3) {
                        val bytes = try { response.body?.bytes() } catch (_: Exception) { null }
                        runCatching { response.close() }
                        if (gen != generation) { done(); return }
                        if (bytes == null || bytes.isEmpty()) { markIdle(gen); done(); return }
                        main.post { playBytes(gen, bytes, onStart, done) }
                    } else {
                        // Sniff the RIFF header HERE (OkHttp thread — blocking reads are fine)
                        // so the engine's real sample rate drives playback; a non-WAV body is
                        // pushed back and treated as legacy raw PCM @24k.
                        val raw = response.body?.byteStream()
                        if (raw == null) { runCatching { response.close() }; markIdle(gen); done(); return }
                        val (stream, wavRate) = try { sniffWav(raw) } catch (e: Exception) {
                            Log.e(TAG, "WAV header read failed: ${e.message}")
                            runCatching { response.close() }; markIdle(gen); done(); return
                        }
                        main.post { streamPcm(gen, response, stream, wavRate ?: PCM_RATE, onStart, done) }
                    }
                }
            })
        }
    }

    private fun streamPcm(gen: Int, resp: Response, stream: java.io.InputStream, rate: Int, onStart: (() -> Unit)?, done: () -> Unit) {
        if (gen != generation) { runCatching { resp.close() }; done(); return }
        val aecC = aecControllerProvider?.invoke()
        // Declare the ACTUAL playback rate to the AEC — Piper voices are 16 k / 22.05 k, Kokoro
        // 24 k. AEC3 frames the echo reference by this rate; getting it wrong misaligns every
        // frame and cancellation silently dies (the TTS tail then bleeds into STT).
        val engaged = aecC?.onTtsSessionStart(rate) == true
        val p = PcmTtsPlayer(rate, if (engaged) aecC!!::onTtsPcmPlayed else null)
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
            runCatching { resp.close() }
            if (engaged) aecC?.onTtsSessionEnd()
            markIdle(gen); done(); return
        }
        pcmPlayer = p
    }

    private fun playBytes(gen: Int, mp3: ByteArray, onStart: (() -> Unit)?, done: () -> Unit) {
        if (gen != generation) { done(); return }
        val tmp = try { File.createTempFile("dashie_localtts_", ".mp3", context.cacheDir).apply { writeBytes(mp3) } }
                  catch (e: Exception) { Log.e(TAG, "buffer MP3 failed: ${e.message}"); markIdle(gen); done(); return }
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)   // media-volume stream (see PcmTtsPlayer)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(tmp.absolutePath)
            mp.setOnPreparedListener { p ->
                if (gen != generation) { runCatching { p.release() }; tmp.delete(); done(); return@setOnPreparedListener }
                onStart?.invoke(); p.start()
            }
            mp.setOnCompletionListener { p -> runCatching { p.release() }; tmp.delete(); if (player === p) player = null; markIdle(gen); done() }
            mp.setOnErrorListener { p, what, extra ->
                Log.e(TAG, "MediaPlayer error $what/$extra"); runCatching { p.release() }; tmp.delete()
                if (player === p) player = null; markIdle(gen); done(); true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer setup failed: ${e.message}"); tmp.delete(); markIdle(gen); done()
        }
    }

    /** Sniff a RIFF/WAVE header off [raw]: returns the stream positioned at the PCM data
     *  plus the fmt-chunk sample rate, or (pushed-back stream, null) when the body isn't
     *  WAV — legacy raw-PCM servers. Blocking reads — call OFF the main thread. Streaming
     *  servers write a placeholder data-chunk size, so it is deliberately ignored. */
    private fun sniffWav(raw: java.io.InputStream): Pair<java.io.InputStream, Int?> {
        val stream = java.io.PushbackInputStream(raw, 12)
        val head = ByteArray(12)
        val got = readFully(stream, head)
        val isWav = got == 12 &&
            head.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            head.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())
        if (!isWav) {
            if (got > 0) stream.unread(head, 0, got)
            return stream to null
        }
        var rate: Int? = null
        val hdr = ByteArray(8)
        while (true) {
            if (readFully(stream, hdr) < 8) return stream to rate
            val id = String(hdr, 0, 4, Charsets.US_ASCII)
            if (id == "data") return stream to rate   // PCM starts here; size field ignored
            val size = le32(hdr, 4)
            if (size < 0 || size > MAX_WAV_CHUNK) { Log.w(TAG, "suspicious WAV chunk '$id' size=$size"); return stream to rate }
            val body = ByteArray(size + (size and 1))   // RIFF chunks are word-aligned
            if (readFully(stream, body) < body.size) return stream to rate
            if (id == "fmt ") {
                val channels = le16(body, 2)
                val bits = le16(body, 14)
                rate = le32(body, 4)
                if (channels != 1 || bits != 16) {
                    Log.w(TAG, "WAV is not PCM16 mono (ch=$channels bits=$bits rate=$rate) — playback may be degraded")
                }
            }
        }
    }

    /** Read exactly [buf].size bytes unless EOF; returns the count actually read. */
    private fun readFully(s: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = s.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /** Stop any in-flight request + playback (barge-in / supersede). */
    fun stop() {
        generation++
        call?.cancel(); call = null
        stopPlaybackOnly()
        isSpeaking = false
    }

    private fun stopPlaybackOnly() {
        player?.let { p -> runCatching { if (p.isPlaying) p.stop() }; runCatching { p.release() } }
        player = null
        pcmPlayer?.let { p ->
            runCatching { p.stop() }
            runCatching { aecControllerProvider?.invoke()?.onTtsSessionEnd() }
        }
        pcmPlayer = null
    }

    private fun markIdle(gen: Int) { if (gen == generation) isSpeaking = false }
}

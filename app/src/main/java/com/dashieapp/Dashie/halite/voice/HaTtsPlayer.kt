package com.dashieapp.Dashie.halite.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.dashieapp.Dashie.halite.voice.tts.AecTeedClipPlayer
import com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * TTS Audio Player for Home Assistant Assist Pipeline
 *
 * Plays TTS audio responses from HA's Assist pipeline.
 * Handles streaming audio from HA's TTS proxy URL.
 *
 * WS-F.0b: when [aecControllerProvider] is set and the cascade AEC wants PCM, the proxy
 * audio is fetched with OkHttp, decoded, and played via the AEC-teed clip player so the
 * canceller gets a render reference (MediaPlayer exposes no PCM — this path was echo-
 * blind). Any failure falls back to the legacy MediaPlayer route below.
 */
class HaTtsPlayer(private val context: Context) {
    companion object {
        private const val TAG = "HaTtsPlayer"
        private val http by lazy {
            LocalHostsTrustingHttpClient.builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var isPrepared = false
    private var clipPlayer: AecTeedClipPlayer? = null
    private var fetchCall: Call? = null
    private var generation = 0

    /** Cascade AEC render-reference hookup (WS-F.0b). Null → legacy MediaPlayer only. */
    var aecControllerProvider: (() -> com.dashieapp.Dashie.halite.voice.aec.CascadeAecController?)? = null

    // Callbacks
    var onPlaybackStarted: (() -> Unit)? = null
    var onPlaybackCompleted: (() -> Unit)? = null
    var onPlaybackError: ((error: String) -> Unit)? = null

    /**
     * Play TTS audio from HA
     *
     * @param haBaseUrl Base URL of Home Assistant (e.g., http://192.168.1.100:8123)
     * @param ttsPath TTS proxy path (e.g., /api/tts_proxy/abc123.mp3)
     * @param authToken HA access token for authentication
     */
    fun play(haBaseUrl: String, ttsPath: String, authToken: String) {
        // Build full URL
        val url = if (ttsPath.startsWith("http")) {
            ttsPath
        } else {
            haBaseUrl.trimEnd('/') + ttsPath
        }

        Log.d(TAG, "Playing TTS from: $url")

        // Release any existing player
        release()

        // AEC-teed route: fetch → decode → PcmTtsPlayer with render tee. Fetching with
        // OkHttp is also the chunked-transfer-safe route (HA's proxy sends no
        // Content-Length; see HaTtsEngineDirectClient's device-verified note).
        if (aecControllerProvider?.invoke()?.wantsPcm() == true) {
            fetchDecodePlay(++generation, url, authToken)
            return
        }

        playViaMediaPlayer(url, authToken)
    }

    /** Fetch the proxy audio and play it through the AEC-teed clip player. Any failure
     *  falls back to [playViaMediaPlayer] (today's echo-blind behavior, never silence). */
    private fun fetchDecodePlay(gen: Int, url: String, authToken: String) {
        val req = Request.Builder().url(url).addHeader("Authorization", "Bearer $authToken").get().build()
        fetchCall = http.newCall(req).also { c ->
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (gen != generation) return
                    Log.w(TAG, "AEC-path fetch failed (${e.message}) — falling back to MediaPlayer")
                    playViaMediaPlayer(url, authToken)
                }

                override fun onResponse(call: Call, response: Response) {
                    val bytes = try { response.body?.bytes() } catch (_: Exception) { null }
                    val code = response.code
                    runCatching { response.close() }
                    if (gen != generation) return
                    if (code !in 200..299 || bytes == null || bytes.isEmpty()) {
                        Log.w(TAG, "AEC-path fetch HTTP $code — falling back to MediaPlayer")
                        playViaMediaPlayer(url, authToken)
                        return
                    }
                    val tmp = try {
                        File.createTempFile("dashie_ha_tts_", ".audio", context.cacheDir).apply { writeBytes(bytes) }
                    } catch (e: Exception) {
                        Log.w(TAG, "AEC-path buffer failed (${e.message}) — falling back to MediaPlayer")
                        playViaMediaPlayer(url, authToken)
                        return
                    }
                    val clip = AecTeedClipPlayer(aecControllerProvider)
                    clipPlayer = clip
                    clip.play(
                        tmp, deleteFileWhenDone = true,
                        onStart = { if (gen == generation) onPlaybackStarted?.invoke() },
                        onComplete = {
                            if (clipPlayer === clip) clipPlayer = null
                            if (gen == generation) onPlaybackCompleted?.invoke()
                        },
                        onUnplayable = {
                            if (clipPlayer === clip) clipPlayer = null
                            runCatching { tmp.delete() }
                            if (gen != generation) return@play
                            Log.w(TAG, "AEC-path decode failed — falling back to MediaPlayer")
                            playViaMediaPlayer(url, authToken)
                        }
                    )
                }
            })
        }
    }

    private fun playViaMediaPlayer(url: String, authToken: String) {
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .build()
                )

                // Set data source with auth header
                setDataSource(context, android.net.Uri.parse(url), mapOf("Authorization" to "Bearer $authToken"))

                setOnPreparedListener { mp ->
                    isPrepared = true
                    Log.d(TAG, "TTS prepared, duration=${mp.duration}ms")
                    mp.start()
                    onPlaybackStarted?.invoke()
                }

                setOnCompletionListener {
                    Log.d(TAG, "TTS playback completed")
                    onPlaybackCompleted?.invoke()
                    release()
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "TTS playback error: what=$what, extra=$extra")
                    onPlaybackError?.invoke("Playback error: $what")
                    release()
                    true
                }

                // Prepare asynchronously
                prepareAsync()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to set data source: ${e.message}", e)
            onPlaybackError?.invoke("Failed to load audio: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "MediaPlayer in illegal state: ${e.message}", e)
            onPlaybackError?.invoke("Player error: ${e.message}")
        }
    }

    /**
     * Stop current playback
     */
    fun stop() {
        if (isPrepared) {
            try {
                mediaPlayer?.stop()
                Log.d(TAG, "TTS playback stopped")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not stop player: ${e.message}")
            }
        }
        release()
    }

    /**
     * Check if currently playing
     */
    fun isPlaying(): Boolean {
        if (clipPlayer != null) return true
        return try {
            mediaPlayer?.isPlaying ?: false
        } catch (e: IllegalStateException) {
            false
        }
    }

    /**
     * Get current playback position
     *
     * @return Current position in milliseconds, or 0 if not playing
     */
    fun getCurrentPosition(): Int {
        return try {
            if (isPrepared) mediaPlayer?.currentPosition ?: 0 else 0
        } catch (e: IllegalStateException) {
            0
        }
    }

    /**
     * Get total duration
     *
     * @return Duration in milliseconds, or 0 if not prepared
     */
    fun getDuration(): Int {
        return try {
            if (isPrepared) mediaPlayer?.duration ?: 0 else 0
        } catch (e: IllegalStateException) {
            0
        }
    }

    /**
     * Release media player resources
     */
    fun release() {
        generation++                       // invalidate in-flight fetch/decode callbacks
        fetchCall?.cancel(); fetchCall = null
        clipPlayer?.stop(); clipPlayer = null
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing player: ${e.message}")
        }
        mediaPlayer = null
        isPrepared = false
    }

    /**
     * Set playback volume
     *
     * @param volume Volume level (0.0 to 1.0)
     */
    fun setVolume(volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        try {
            mediaPlayer?.setVolume(clampedVolume, clampedVolume)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not set volume: ${e.message}")
        }
    }
}

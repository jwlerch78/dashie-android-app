package com.dashieapp.Dashie.halite.voice.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.dashieapp.Dashie.halite.voice.aec.CascadeAecController
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AEC-teed route for the Android system TTS engine (WS-F.0b): `synthesizeToFile()` → WAV
 * → [AecTeedClipPlayer], so the cascade AEC gets a render reference. `tts.speak()` plays
 * inside the engine and exposes no PCM — that path is echo-blind, which is exactly the
 * self-hearing trap the $0 degradation fallback would hand an out-of-credits user.
 *
 * Costs streaming (full synthesis before first audio), but the engine is local and fast —
 * the trade the master plan's F.0b prescribes. Every failure path falls back to the
 * caller's legacy `tts.speak()` route (echo-blind, never silent).
 */
class DeviceTtsPcmSpeaker(
    private val context: Context,
    private val aecControllerProvider: () -> CascadeAecController?,
) {
    companion object { private const val TAG = "DeviceTtsPcm" }

    private var clip: AecTeedClipPlayer? = null

    /**
     * Try the teed route. Returns false SYNCHRONOUSLY when it isn't available (AEC off or
     * init-failed, temp file or synthesizeToFile refused) — the caller then runs its legacy
     * path and this instance has touched nothing. After true: [onDone] fires at spoken
     * completion, or [fallback] on an async synth/decode failure (caller speaks legacy) —
     * on the main thread. [stop] fires neither (same contract as PcmTtsPlayer: the
     * pipeline's barge-in flow owns state after a stop).
     *
     * Note: sets the engine's UtteranceProgressListener; callers that use the engine
     * directly re-set their own listener per-utterance already (HaliteVoiceController does).
     *
     * [onStart] fires at REAL first audio (clip playback begins), not at synthesis request —
     * this route synthesises the whole utterance first, so the two are far apart and only the
     * former is a meaningful time-to-first-audio.
     */
    fun trySpeak(
        tts: TextToSpeech,
        text: String,
        onDone: () -> Unit,
        fallback: () -> Unit,
        onStart: (() -> Unit)? = null,
    ): Boolean {
        if (aecControllerProvider()?.wantsPcm() != true) return false
        val out = try {
            File.createTempFile("dashie_tts_", ".wav", context.cacheDir)
        } catch (e: Exception) {
            Log.w(TAG, "temp file failed: ${e.message}"); return false
        }
        val utteranceId = "dashie_pcm_${System.currentTimeMillis()}"
        val fired = AtomicBoolean(false)
        val once: (() -> Unit) -> Unit = { f -> if (fired.compareAndSet(false, true)) f() }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}

            override fun onDone(id: String?) {
                if (id != utteranceId) return
                val c = AecTeedClipPlayer { aecControllerProvider() }
                clip = c
                c.play(
                    out, deleteFileWhenDone = true,
                    onStart = { Log.i(TAG, "🔊 Device TTS speaking via AEC-teed PCM"); onStart?.invoke() },
                    onComplete = { if (clip === c) clip = null; once(onDone) },
                    onUnplayable = {
                        if (clip === c) clip = null
                        runCatching { out.delete() }
                        Log.w(TAG, "WAV decode failed — legacy tts.speak fallback")
                        once(fallback)
                    }
                )
            }

            @Deprecated("Deprecated in API 21")
            override fun onError(id: String?) {
                if (id == utteranceId) { runCatching { out.delete() }; once(fallback) }
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId) {
                    Log.w(TAG, "synthesizeToFile error $errorCode — legacy tts.speak fallback")
                    runCatching { out.delete() }
                    once(fallback)
                }
            }
        })

        val result = tts.synthesizeToFile(text, Bundle(), out, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "synthesizeToFile refused ($result)")
            runCatching { out.delete() }
            return false
        }
        return true
    }

    /** Cut in-flight PCM playback (barge-in). The engine itself is stopped by the caller's
     *  existing `tts.stop()` — this covers the playback the engine no longer owns. */
    fun stop() {
        clip?.stop()
        clip = null
    }
}

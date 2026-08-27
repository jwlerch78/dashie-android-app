package com.dashieapp.Dashie.halite.voice

import android.content.Context
import android.util.Log

/**
 * HA TTS voice for the CLOUD-BRAIN path (VoicePipelineCoordinator).
 *
 * On the cloud-AI lane the response text is produced by Dashie's brain, not
 * HA's conversation agent — so there's no Assist pipeline TTS stage to ride
 * (unlike HaVoiceService's HA-Assist path). This class fills that gap: given
 * arbitrary text, it runs HA's Assist pipeline **TTS-only** (start=end=tts)
 * with the pipeline's configured `tts_engine` (e.g. local Piper, Azure) and
 * plays the resulting `tts_output.url` via [HaTtsPlayer]. That's what lets a
 * powerful+cheap cloud brain speak with a free+fast local HA voice.
 *
 * Extracted into its own file deliberately — VoicePipelineCoordinator and
 * HaliteVoiceController are both at/over the file-size budget (FB26).
 *
 * Lifecycle: construct once per HA voice session, [connect] eagerly so the
 * WebSocket + auth are warm before the first utterance, [release] on teardown.
 */
class HaTtsSynthesizer(
    private val context: Context,
    private val haUrl: String,        // HTTP HA url (HaAssistClient derives the ws url)
    private val haBaseUrl: String,    // HTTP origin for HaTtsPlayer to resolve relative tts paths
    // LIVE token read — the HA token rotates ~every 30 min; a constructor snapshot
    // fails any (re)connect after the first rotation ("Invalid access token",
    // 2026-07-13). Resolved at connect/play time.
    private val haToken: () -> String,
    private val pipelineId: String?,
    // WS-F.0b: cascade AEC render-reference hookup for the proxy-audio playback.
    private val aecControllerProvider: (() -> com.dashieapp.Dashie.halite.voice.aec.CascadeAecController?)? = null,
) {
    companion object { private const val TAG = "HaTtsSynthesizer" }

    private val player = HaTtsPlayer(context).apply {
        aecControllerProvider = this@HaTtsSynthesizer.aecControllerProvider
    }
    private var client: HaAssistClient? = null
    private var authed = false

    // The active utterance's callbacks — one at a time (voice is serial).
    private var pendingText: String? = null
    private var curOnStart: (() -> Unit)? = null
    private var curOnDone: (() -> Unit)? = null
    private var curOnError: ((String) -> Unit)? = null

    /** Warm the connection so the first speak() doesn't pay WS+auth latency. */
    fun connect() {
        if (client != null) return
        client = HaAssistClient(haUrl, haToken()).apply {
            onAuthSuccess = {
                authed = true
                Log.i(TAG, "Authenticated — HA TTS ready")
                // Flush a queued utterance that arrived before auth completed.
                pendingText?.let { t -> pendingText = null; runTts(t) }
            }
            onAuthFailed = { err ->
                authed = false
                Log.w(TAG, "Auth failed: $err")
                curOnError?.invoke("HA auth failed: $err")
                clearCurrent()
            }
            onDisconnected = { authed = false }
            onTtsEnd = { url, mime ->
                if (url.isBlank()) {
                    Log.w(TAG, "tts-end with empty url ($mime)")
                    curOnError?.invoke("HA returned no TTS audio")
                    clearCurrent()
                } else {
                    playUrl(url)
                }
            }
            onError = { err ->
                Log.w(TAG, "Pipeline error: $err")
                curOnError?.invoke(err)
                clearCurrent()
            }
            connect()
        }
    }

    /**
     * Speak [text] with the HA pipeline voice. onDone/onError fire exactly once.
     * If a previous utterance is still playing it is stopped first (barge-in).
     */
    fun speak(text: String, onStart: () -> Unit, onDone: () -> Unit, onError: (String) -> Unit) {
        player.stop()
        curOnStart = onStart
        curOnDone = onDone
        curOnError = onError
        if (client == null) connect()
        if (authed) runTts(text) else pendingText = text   // run on onAuthSuccess
    }

    private fun runTts(text: String) {
        Log.i(TAG, "Synthesizing via HA (pipeline=${pipelineId ?: "default"}): '${text.take(50)}'")
        client?.startTtsPipeline(text, pipelineId)
    }

    private fun playUrl(url: String) {
        player.onPlaybackStarted = { curOnStart?.invoke() }
        player.onPlaybackCompleted = { val d = curOnDone; clearCurrent(); d?.invoke() }
        player.onPlaybackError = { e -> val er = curOnError; clearCurrent(); er?.invoke(e) }
        player.play(haBaseUrl, url, haToken())
    }

    private fun clearCurrent() {
        curOnStart = null; curOnDone = null; curOnError = null; pendingText = null
    }

    fun stop() { player.stop(); clearCurrent() }

    fun release() {
        player.release()
        client?.disconnect()
        client = null
        authed = false
        clearCurrent()
    }
}

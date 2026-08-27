package com.dashieapp.Dashie.halite.voice.tts

import android.os.Handler
import android.os.Looper
import com.dashieapp.Dashie.halite.voice.aec.CascadeAecController
import java.io.File
import kotlin.concurrent.thread

/**
 * One-shot "decode → AEC session → PcmTtsPlayer" clip player — the shared render-reference
 * seam for TTS paths that only ever have a compressed clip in hand (HA tts_proxy audio,
 * HA engine-direct MP3, system-TTS WAV). Closes WS-F.0b for those paths.
 *
 * Decodes off-thread via [TtsAudioDecoder], engages [CascadeAecController.onTtsSessionStart]
 * at the DECODED sample rate (AEC3 slices reference frames by rate — a mismatch silently
 * kills cancellation, see the controller's KDoc), tees head-paced frames through
 * [PcmTtsPlayer], and ends the session at real playback end or [stop].
 *
 * Callback contract (all posted to main, mirroring the MediaPlayer semantics callers have):
 *  • [onUnplayable] fires INSTEAD of onStart/onComplete when the clip can't be decoded or
 *    the track can't start — the file is left in place so the caller can fall back to its
 *    legacy MediaPlayer route. A codec oddity degrades to today's (echo-blind) behavior,
 *    never to silence.
 *  • on success, the file is deleted after playback when [deleteFileWhenDone].
 *  • [stop] mirrors PcmTtsPlayer: cuts playback, ends the AEC session, no callbacks after.
 */
class AecTeedClipPlayer(
    private val aecControllerProvider: (() -> CascadeAecController?)?,
) {
    @Volatile private var stopped = false
    @Volatile private var player: PcmTtsPlayer? = null
    @Volatile private var engagedAec: CascadeAecController? = null
    @Volatile private var ownedFile: File? = null   // deleted on complete OR stop (never on unplayable)
    private val main = Handler(Looper.getMainLooper())

    fun play(
        file: File,
        deleteFileWhenDone: Boolean,
        onStart: () -> Unit,
        onComplete: () -> Unit,
        onUnplayable: () -> Unit,
    ) {
        if (deleteFileWhenDone) ownedFile = file
        thread(name = "tts-clip-decode") {
            val decoded = TtsAudioDecoder.decodeToMonoPcm16(file)
            if (stopped) { if (deleteFileWhenDone) { ownedFile = null; runCatching { file.delete() } }; return@thread }
            if (decoded == null || decoded.pcm.isEmpty()) {
                ownedFile = null   // caller's fallback still needs the file
                main.post { if (!stopped) onUnplayable() }
                return@thread
            }

            val aecC = aecControllerProvider?.invoke()
            val engaged = aecC?.onTtsSessionStart(decoded.sampleRate) == true
            if (engaged) engagedAec = aecC
            val p = PcmTtsPlayer(decoded.sampleRate, if (engaged) aecC!!::onTtsPcmPlayed else null)
            player = p
            val started = p.play(
                decoded.pcm,
                onStart = { main.post { if (!stopped) onStart() } },
                onComplete = {
                    if (engaged) { engagedAec = null; aecC?.onTtsSessionEnd() }
                    if (deleteFileWhenDone) { ownedFile = null; runCatching { file.delete() } }
                    main.post { if (!stopped) onComplete() }
                }
            )
            if (!started) {
                if (engaged) { engagedAec = null; aecC?.onTtsSessionEnd() }
                player = null
                main.post { if (!stopped) onUnplayable() }
            }
        }
    }

    /** Cut playback (barge-in / supersede). No callbacks fire after this. */
    fun stop() {
        stopped = true
        runCatching { player?.stop() }
        player = null
        engagedAec?.onTtsSessionEnd()
        engagedAec = null
        ownedFile?.let { f -> ownedFile = null; runCatching { f.delete() } }
    }
}

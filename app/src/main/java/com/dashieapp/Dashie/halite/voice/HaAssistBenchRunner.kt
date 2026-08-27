package com.dashieapp.Dashie.halite.voice

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.audio.AudioCaptureService
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import org.json.JSONObject

/**
 * Drives a whole **HA Voice Assist** turn headlessly from a staged WAV — the instrument the
 * validation matrix's R5/R6 rows need and did not have.
 *
 * ── WHY AUDIO, AND NOT TEXT ───────────────────────────────────────────────────────────
 * The obvious cheap version is a text-injection entry, and it would be worthless here. The
 * existing `@pipeline` text entry ([HaliteVoiceController.injectTranscript]) delegates
 * unconditionally to [VoicePipelineCoordinator] — the CASCADE — while HA-Assist mode
 * (`useOverlayNlp=false`) runs on [HaVoiceService] and never touches the coordinator. So an
 * `ha_assist` row driven that way silently exercised the wrong pipeline and passed, because
 * `logNativeTurn` is emitted only from the cascade's `runBrainTurn`. (2026-07-30, defect D15.)
 *
 * Worse, R6's claim — *local STT in HA-Assist mode must never fall back to cloud STT* — is a
 * claim about **which engine receives audio**. Any text entry makes it unfalsifiable, which is
 * the same trap that made the previous two instruments useless (`stt=null/null` on a text turn;
 * `sttBench` pinning `preferredType` and bypassing the priority chain outright). Feeding real
 * PCM through the real buffer is the only version that can fail.
 *
 * ── HOW IT WORKS ──────────────────────────────────────────────────────────────────────
 * Both consumers read the shared ring buffer from `wakeWordBufferPosition`:
 * `startAudioStreaming` (HA does STT) and `HaLocalSttStage.transcribe` (we do). So the runner
 * marks a position, fires [HaVoiceService.onWakeWordDetected] at it, and paces the clip in
 * afterwards exactly as [SttBenchRunner] does — 1x real time, so a streaming stage gets the
 * same head start a live speaker gives it and a buffered one the same standing wait.
 *
 * Three details that would each quietly corrupt the result:
 *  • **Lead silence.** Both consumers skip `SttProviderFactory.wakeTailSkipSamples` past the
 *    wake position to drop the "…dashie" tail — but they pass DIFFERENT provider keys, so the
 *    skip is 4480 samples on the buffered path and 1600 on the local one. We write the larger
 *    of the two as silence before the clip, so whichever path runs lands on silence rather than
 *    eating the first word.
 *  • **The microphone.** Live capture writes into the SAME buffer. Left running, the room mixes
 *    into the clip and the transcript becomes untrustworthy in a way that still looks like a
 *    pass. Capture is stopped for the duration and restored after.
 *  • **Trailing silence.** Silero endpoints on trailing silence; a clip that ends at the last
 *    word never closes the segment and the turn hangs to its timeout. We append quiet.
 */
object HaAssistBenchRunner {
    private const val TAG = "HaAssistBench"
    private const val SAMPLE_RATE = 16000
    private const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000     // 16-bit mono
    private const val CHUNK_MS = 20L

    /** The largest `wakeTailSkipSamples` any consumer applies (STT_VA_DEFAULT → buffered). */
    private const val LEAD_SILENCE_SAMPLES = 4480

    /** Enough quiet after the clip for silero (500 ms) and the energy VAD (1200 ms) to close. */
    private const val TRAIL_SILENCE_MS = 1800L

    private const val POLL_MS = 100L

    /**
     * The shared ring buffer is created at `durationSeconds = 5.0f`
     * ([HaliteVoiceController] line ~674), i.e. it holds FIVE SECONDS. It is a streaming
     * buffer — the consumer drains it as we write, so a longer clip is not automatically
     * fatal — but the consumer does not start draining until `extractAuthTokenAndConnect`
     * has opened a socket to HA. Anything written during that connect delay is racing the
     * wrap, and a clip that loses its head comes back as a partial transcript with no error:
     * indistinguishable from an STT quality problem, and exactly the kind of thing that sent
     * this validation effort chasing the wrong layer twice already. Warn loudly above a
     * conservative fraction rather than discovering it as a mystery WER.
     */
    private const val SAFE_CLIP_MS = 3000L

    /**
     * Run one clip through the live HA-Assist path. Blocking — call off the main thread.
     *
     * Reports what HAPPENED, not a verdict: the assertions that matter for R5/R6 are the
     * `HaLocalStt: Final transcript (<provider>)` marker and the server-side billing check, and
     * both are made by the harness. Returning a verdict here would just be a second place for
     * the truth to drift.
     */
    fun run(
        service: HaVoiceService,
        buffer: SharedAudioBuffer,
        capture: AudioCaptureService?,
        pcm: ByteArray,
        timeoutMs: Long,
        allowBusy: Boolean = false,
    ): JSONObject {
        val json = JSONObject()
        val audioMs = (pcm.size / BYTES_PER_MS).toLong()
        json.put("audioMs", audioMs)

        val startState = service.getState()
        json.put("startState", startState.toString())
        if (startState != HaVoiceService.VoiceState.IDLE && !allowBusy) {
            // Refuse rather than fire into a busy pipeline. ⚠️ The original reason — "a wake in
            // any non-IDLE state is dropped, so we would inject audio nobody reads" — became a
            // FOSSIL at vc189, which made PROCESSING/SPEAKING interruptible. It is still the
            // right default (a bench firing into a busy pipeline usually means a leaked turn),
            // but it is no longer a statement about what the service can do.
            //
            // `allowBusy=1` is what makes mechanism (c) — a wake honoured mid-SPEAKING —
            // headlessly regression-testable. Without it, the only instrument for the barge
            // path is a person speaking in a room, which is how three defects in this area
            // reached a human before a test.
            Log.w(TAG, "DROP: pipeline not IDLE (state=$startState) — refusing to bench " +
                "(pass allowBusy=1 to bench a barge into a live turn)")
            return json.put("error", "DROP: pipeline busy (state=$startState)")
        }
        if (startState != HaVoiceService.VoiceState.IDLE) {
            Log.i(TAG, "allowBusy=1 — benching INTO a live turn (state=$startState); " +
                "this is the barge-in path, not the ordinary one")
        }

        if (audioMs > SAFE_CLIP_MS) {
            Log.w(TAG, "DROP: clip is ${audioMs}ms — the shared buffer holds ~5000ms and the " +
                "consumer only starts draining after the HA socket opens, so the HEAD of this " +
                "clip may be overwritten before it is read. A partial transcript here is a " +
                "BUFFER artifact, not an STT result. Use a clip under ${SAFE_CLIP_MS}ms.")
            json.put("bufferRisk", "clip ${audioMs}ms > ${SAFE_CLIP_MS}ms safe window")
        }

        val wasCapturing = capture?.isCapturing() ?: false
        if (wasCapturing) {
            Log.i(TAG, "stopping mic capture for the duration (room audio would mix into the clip)")
            capture?.stop()
            Thread.sleep(200)   // let the capture loop drain before we own the buffer
        }

        try {
            val mark = buffer.markPosition()
            json.put("wakePosition", mark)

            // Silence lead FIRST, so the consumer's tail-skip lands here and not on speech.
            buffer.writeShorts(ShortArray(LEAD_SILENCE_SAMPLES), LEAD_SILENCE_SAMPLES)

            // Fire the wake on the main thread: extractAuthTokenAndConnect touches the WebView.
            val main = Handler(Looper.getMainLooper())
            main.post { service.onWakeWordDetected(1.0f, mark) }

            // Pace the clip at 1x, exactly as AudioCaptureService feeds it live.
            val chunkSamples = (CHUNK_MS * SAMPLE_RATE / 1000).toInt()
            val startNs = System.nanoTime()
            var offset = 0
            var sentMs = 0L
            while (offset < pcm.size) {
                val end = minOf(offset + chunkSamples * 2, pcm.size)
                val n = (end - offset) / 2
                val shorts = ShortArray(n)
                for (i in 0 until n) {
                    val lo = pcm[offset + 2 * i].toInt() and 0xff
                    val hi = pcm[offset + 2 * i + 1].toInt()
                    shorts[i] = ((hi shl 8) or lo).toShort()
                }
                buffer.writeShorts(shorts, n)
                offset = end
                sentMs += CHUNK_MS
                val behind = sentMs - (System.nanoTime() - startNs) / 1_000_000
                if (behind > 0) Thread.sleep(behind)
            }

            // Trailing quiet so the endpointer actually closes the segment.
            var trailed = 0L
            while (trailed < TRAIL_SILENCE_MS) {
                buffer.writeShorts(ShortArray(chunkSamples), chunkSamples)
                trailed += CHUNK_MS
                Thread.sleep(CHUNK_MS)
            }

            // Wait for the turn to run its course. "Left IDLE then returned" is the completion
            // signal; a turn that never leaves IDLE means the wake was dropped, which is a
            // distinct failure from one that ran and produced nothing.
            val deadline = System.currentTimeMillis() + timeoutMs
            var everLeftIdle = false
            var lastState = service.getState()
            val states = StringBuilder(lastState.name)
            while (System.currentTimeMillis() < deadline) {
                val s = service.getState()
                if (s != lastState) { states.append(" → ").append(s.name); lastState = s }
                if (s != HaVoiceService.VoiceState.IDLE) everLeftIdle = true
                else if (everLeftIdle) break
                Thread.sleep(POLL_MS)
            }

            json.put("stateTrace", states.toString())
            json.put("reachedPipeline", everLeftIdle)
            json.put("completed", everLeftIdle && service.getState() == HaVoiceService.VoiceState.IDLE)
            if (!everLeftIdle) {
                Log.w(TAG, "DROP: wake fired but the pipeline never left IDLE — wake dropped?")
                json.put("error", "DROP: pipeline never left IDLE (wake dropped — check isEnabled/micMuted)")
            }
            Log.i(TAG, "bench done: audio=${audioMs}ms states=$states")
        } catch (e: Throwable) {
            Log.w(TAG, "DROP: HA-Assist bench failed", e)
            json.put("error", "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            if (wasCapturing) {
                Log.i(TAG, "restoring mic capture")
                capture?.start()
            }
        }
        return json
    }
}

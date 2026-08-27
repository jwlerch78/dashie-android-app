package com.dashieapp.Dashie.halite.voice.aec

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.dashieapp.Dashie.audio.MicCaptureProcessor
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.voice.realtime.RealtimeAec3

/**
 * AEC3 echo cancellation for the CASCADE voice path (WS-A.2) — the plan-of-record
 * self-hearing fix for dialog/single mode. Reuses [RealtimeAec3] (the canceller proven
 * on-device in Live mode) at the Live path's exact 16 kHz-capture / 24 kHz-render split:
 *
 *  • Capture side: plugs into [com.dashieapp.Dashie.audio.AudioCaptureService] as a
 *    [MicCaptureProcessor] — the ONE seam every mic sample crosses — so wake word AND
 *    STT both read echo-cancelled audio from the shared buffer.
 *  • Render side: ElevenLabsTtsClient's PCM playback (PcmTtsPlayer) tees the frames the
 *    speaker has actually emitted (head-paced) into [onTtsPcmPlayed] as the reference.
 *
 * Engagement is windowed: capture frames only pass through AEC3 while a cloud-TTS
 * session is playing (+ [TAIL_HOLD_MS] for the room's reverb tail), so the always-on
 * wake-word path pays zero AEC CPU when Dashie isn't speaking. The AEC3 instance is
 * kept across turns of a dialog (filter state persists → fast re-convergence).
 *
 * Strictly non-fatal, mirroring RealtimeAudioIo: pref off, native-lib load failure, or
 * any processing throw → raw pass-through (today's behavior). No AECM fallback here —
 * AECM needs MODE_IN_COMMUNICATION + platform-AEC routing, which would disturb the
 * always-on wake-word capture; AEC3-or-nothing keeps the cascade path untouched on the
 * rare device where the .so can't load.
 *
 * WS-F.0b (2026-07-19): HA proxy / HA engine-direct / Android system TTS are now ALSO
 * teed via [AecTeedClipPlayer] (decode → PcmTtsPlayer at the decoded rate). Still out of
 * scope: music playback echo is not cancelled.
 *
 * Threading: [process] runs on the capture thread, [onTtsPcmPlayed] on the player's
 * pacer thread — RealtimeAec3's read-write lock makes that safe (render and capture
 * process concurrently; release waits for in-flight calls).
 */
class CascadeAecController(private val context: Context) : MicCaptureProcessor {

    companion object {
        private const val TAG = "CascadeAec"
        const val CAPTURE_RATE = 16000
        /** ElevenLabs pcm_24000 — matches Live's render rate so AEC3 runs in the exact
         *  configuration already validated on-device (incl. the weak Echo Show). */
        const val RENDER_RATE = 24000
        // Keep cancelling briefly after playback ends: reverb tail + mic frames already
        // in flight when the playback head reached the end.
        private const val TAIL_HOLD_MS = 750L
        // WS-A.2 acceptance evidence: dump raw + cleaned mic (engaged windows only) to
        // getExternalFilesDir/cascade_aec_{raw,clean}.pcm (16k mono PCM16).
        // OFF (2026-07-13): the AEC now engages for every TURN, not just while Dashie speaks,
        // so these append ~32 KB/s per file on ordinary use — unbounded, no rotation. Flip to
        // true only for a debugging session, and delete the files afterwards.
        private const val DEBUG_DUMP = false
    }

    private val lock = Any()   // guards init/release; steady-state reads are volatile
    @Volatile private var aec: RealtimeAec3? = null
    @Volatile private var initFailed = false
    @Volatile private var sessionActive = false
    @Volatile private var tailUntil = 0L
    @Volatile private var turnActive = false
    /** Render rate the live AEC3 was built with. A TTS session at a different rate rebuilds it
     *  (see [ensureAec]) — the reference stream's rate is not something AEC3 can be told later. */
    @Volatile private var activeRenderRate = RENDER_RATE

    private var rawDump: java.io.OutputStream? = null
    private var cleanDump: java.io.OutputStream? = null

    /** Device-local kill switch (Advanced → Conversation Mode → "Echo Cancellation
     *  (Dialog)"). Read per session so a toggle applies without an app restart. */
    private fun prefEnabled() = VoicePreferences(context).cascadeAecEnabled

    /** Should the TTS client request PCM audio (the AEC-capable playback path)?
     *  False once AEC3 init has failed — then MP3+MediaPlayer, exactly today's path. */
    fun wantsPcm(): Boolean = prefEnabled() && !initFailed

    /**
     * A TTS PCM playback session is starting. Lazily builds AEC3 on first use.
     *
     * [renderRate] MUST be the rate the player will actually emit — AEC3 slices the reference
     * into 10 ms frames using it (240 samples @ 24 kHz), so a mismatch misaligns every frame
     * and cancellation silently stops working. That regressed when LocalTtsClient started
     * honouring each engine's native rate (Piper is 16 k / 22.05 k, Kokoro 24 k): the
     * reference was still declared 24 k and the user heard the TTS tail bleed into STT
     * ("cloudy" → "body", John 2026-07-13). Cloud TTS stays 24 k, hence the default.
     *
     * @return true if the AEC engaged (caller should tee playback via [onTtsPcmPlayed]).
     */
    fun onTtsSessionStart(renderRate: Int = RENDER_RATE): Boolean {
        if (!ensureAec(renderRate)) {
            // 🔴 Loud, because the ONLY existing AEC line is "cascade AEC3 ready", which is emitted
            // ONCE at CONSTRUCTION. Nothing said whether the canceller was ENGAGED for a given
            // playback window — so a log containing "AEC3 ready" was read as evidence that AEC was
            // running while EI heard the echo anyway, when it only ever proved the
            // canceller had been built at some earlier point. An instrument that cannot
            // distinguish "built" from "engaged" cannot support the conclusion drawn from it.
            Log.w(TAG, "AEC-WINDOW: NOT engaged for this TTS session (renderRate=$renderRate, " +
                    "prefEnabled=${prefEnabled()}, initFailed=$initFailed) — capture passes RAW, " +
                    "so wake-word and STT hear the playback echo unattenuated")
            return false
        }
        sessionActive = true
        Log.i(TAG, "AEC-WINDOW: engaged for TTS session (render=$renderRate capture=$CAPTURE_RATE)")
        return true
    }

    /** Lazily build AEC3 at [renderRate]. Returns false when the pref is off or init has
     *  failed (→ raw pass-through, today's behavior). Shared by the TTS session and the turn
     *  window. A rate CHANGE (the user switched to a voice with a different native rate)
     *  rebuilds the canceller — AEC3's render rate is fixed at construction. */
    private fun ensureAec(renderRate: Int = activeRenderRate): Boolean {
        if (!prefEnabled() || initFailed) return false
        synchronized(lock) {
            if (aec != null && renderRate != activeRenderRate) {
                Log.i(TAG, "render rate ${activeRenderRate} → $renderRate — rebuilding AEC3")
                runCatching { aec?.release() }
                aec = null
            }
            if (aec == null) {
                val a = RealtimeAec3(captureRate = CAPTURE_RATE, renderRate = renderRate)
                if (a.init()) {
                    a.setStreamDelayMs(50)   // reference is head-paced; residual is acoustic + mic buffering
                    aec = a
                    activeRenderRate = renderRate
                    if (DEBUG_DUMP) openDumps()
                    Log.i(TAG, "cascade AEC3 ready (capture=$CAPTURE_RATE render=$renderRate)")
                } else {
                    initFailed = true
                    Log.w(TAG, "AEC3 init failed — cascade AEC off (raw pass-through)")
                    return false
                }
            }
        }
        return true
    }

    /**
     * A voice TURN is starting (wake word fired / conversation follow-up) — engage the capture
     * path for its whole duration, not just while Dashie is speaking, so STT reads noise-
     * suppressed audio and any residual echo keeps being cancelled through the listen window.
     *
     * ⚠️ Honest scope (measured on John's Samsung, 2026-07-13 — 31 s of dumped mic):
     * this does NOT fix missed utterances, and it was originally added believing it did. The
     * room floor there is -76 dBFS (0.00016 RMS), a full 42 dB BELOW the VAD's 0.02 speech
     * threshold; zero silent frames read as speech. AEC+NS drops the floor another ~12 dB,
     * which is irrelevant at that margin. The dropped-sentence bug was the pipeline's
     * no-transcript watchdog guillotining a long utterance (fixed separately). Keep this for
     * what it actually buys — a cleaner stream into STT, which matters in a NOISY room, not
     * this one — and don't let it accrete more justification than that.
     *
     * Deliberately NOT always-on: [process] is the ONE seam every mic sample crosses, so
     * always-on would also feed noise-suppressed audio to the wake-word models, which were
     * tuned on the raw stream (and whose recall is already fragile). Wake detection is
     * stopped for the duration of a turn, so this window touches STT/VAD only.
     */
    fun onTurnStart() {
        if (!ensureAec()) return
        turnActive = true
    }

    /** The turn ended (transcript handled / cancelled / timed out). Keeps the same reverb
     *  tail as a TTS session so in-flight frames still get cancelled. */
    fun onTurnEnd() {
        if (!turnActive) return
        turnActive = false
        tailUntil = SystemClock.uptimeMillis() + TAIL_HOLD_MS
    }

    /** Head-paced playback PCM (24 kHz mono PCM16) — what the speaker just emitted.
     *  Called from PcmTtsPlayer's pacer thread. */
    fun onTtsPcmPlayed(pcm: ByteArray) {
        if (!sessionActive) return
        try { aec?.processReverse(pcm) } catch (t: Throwable) { Log.w(TAG, "processReverse failed", t) }
    }

    /** Playback finished or was stopped (barge-in / supersede). Cancellation holds for
     *  [TAIL_HOLD_MS] more, then capture passes raw again. */
    fun onTtsSessionEnd() {
        if (!sessionActive) return
        sessionActive = false
        tailUntil = SystemClock.uptimeMillis() + TAIL_HOLD_MS
    }

    private fun engagedNow(): Boolean =
        turnActive || sessionActive || SystemClock.uptimeMillis() < tailUntil

    // ── MicCaptureProcessor (capture thread) ─────────────────────────────
    override fun process(samples: ShortArray, count: Int): ShortArray? {
        val a = aec ?: return null
        if (!engagedNow()) return null
        return try {
            val raw = shortsToBytes(samples, count)
            try { rawDump?.write(raw) } catch (_: Exception) {}
            val clean = a.processCapture(raw) ?: return null
            try { cleanDump?.write(clean) } catch (_: Exception) {}
            bytesToShorts(clean)
        } catch (t: Throwable) {
            Log.w(TAG, "processCapture failed — raw pass-through", t)
            null
        }
    }

    fun release() {
        synchronized(lock) {
            sessionActive = false; tailUntil = 0L
            try { aec?.release() } catch (_: Throwable) {}
            aec = null
            closeDumps()
        }
    }

    // ── debug dumps ──────────────────────────────────────────────────────
    private fun openDumps() {
        try {
            val dir = context.getExternalFilesDir(null)
            rawDump = java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.File(dir, "cascade_aec_raw.pcm")))
            cleanDump = java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.File(dir, "cascade_aec_clean.pcm")))
            Log.i(TAG, "AEC debug dump → $dir/cascade_aec_{raw,clean}.pcm (16k mono PCM16)")
        } catch (e: Exception) { Log.w(TAG, "dump open failed", e) }
    }

    private fun closeDumps() {
        try { rawDump?.flush(); rawDump?.close() } catch (_: Exception) {}
        try { cleanDump?.flush(); cleanDump?.close() } catch (_: Exception) {}
        rawDump = null; cleanDump = null
    }

    // ── PCM16 little-endian helpers ──────────────────────────────────────
    private fun shortsToBytes(s: ShortArray, count: Int): ByteArray {
        val out = ByteArray(count * 2)
        var j = 0
        for (i in 0 until count) {
            val iv = s[i].toInt()
            out[j] = (iv and 0xFF).toByte()
            out[j + 1] = ((iv shr 8) and 0xFF).toByte()
            j += 2
        }
        return out
    }

    private fun bytesToShorts(b: ByteArray): ShortArray {
        val out = ShortArray(b.size / 2)
        var j = 0
        for (i in out.indices) {
            out[i] = ((b[j].toInt() and 0xFF) or (b[j + 1].toInt() shl 8)).toShort()
            j += 2
        }
        return out
    }
}

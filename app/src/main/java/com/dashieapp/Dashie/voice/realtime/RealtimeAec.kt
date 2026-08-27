package com.dashieapp.Dashie.voice.realtime

import android.util.Log
import ru.theeasiestway.libaecm.AEC

/**
 * Software acoustic echo canceller for conversation mode — build plan
 * 20260627_REALTIME_VOICE_AEC, Phase 0 spike.
 *
 * Wraps WebRTC's fixed-point AECM (vendored [ru.theeasiestway.libaecm.AEC], ~105 KB
 * native, arm64-v8a + armeabi-v7a). AECM is the mobile/low-RAM echo canceller the plan
 * recommends for Echo Show / weak-AEC hardware.
 *
 * Both streams are reduced to 16 kHz mono PCM16 in 10 ms (160-sample) frames:
 *  • [processReverse] — far-end *reference* (the model's 24 kHz playback), 3:2-decimated
 *    to 16 kHz, buffered, handed to AECM as the echo it should subtract.
 *  • [processCapture] — near-end mic (already 16 kHz); returns the echo-cancelled bytes.
 *
 * AECM wants the far-end buffered AHEAD of the matching near-end with a coarse
 * render↔capture [delayMs] so it knows how far back in its far-end ring to look.
 * [processReverse] runs on the playback/WS thread and [processCapture] on the mic
 * thread, so the native instance is guarded by [lock]; the per-stream carry/resampler
 * state is single-threaded (each touched by exactly one thread) and lock-free.
 *
 * Throwaway spike quality: one AECM instance, a 3:2 decimator (not a windowed
 * resampler), and a fixed delay. It must NEVER throw into the audio path — callers fall
 * back to raw pass-through on a null/failed result.
 */
class RealtimeAec : SoftwareAec {

    companion object {
        private const val TAG = "RealtimeAec"
        private const val FRAME = 160            // samples = 10 ms @ 16 kHz (AECM supports 80 or 160)
        private const val DEFAULT_DELAY_MS = 120 // coarse AudioTrack buffer + output latency; tune in Phase 1
    }

    private val lock = Any()
    private var aec: AEC? = null

    /** Coarse render↔capture delay handed to AECM each frame. Per-device tuning is Phase 1. */
    @Volatile var delayMs: Int = DEFAULT_DELAY_MS

    /**
     * AECM aggressiveness. MILD < MEDIUM < HIGH < AGGRESSIVE < MOST_AGGRESSIVE. More aggressive
     * = more echo removed but more near-end (your voice) eaten during double-talk, so barge-in
     * needs a louder voice. Spike default MILD: on Samsung, echo stayed cancelled down through
     * MEDIUM while barge-in still needed a raised voice, so go to the least-gating setting and
     * lean on the platform AEC to keep echo down. Bump toward MEDIUM/HIGH if echo leaks. The
     * lever to tune alongside [delayMs].
     */
    var aggressiveMode: AEC.AggressiveMode = AEC.AggressiveMode.MILD

    // near-end (mic) 16 kHz framing remainder — touched only by the capture thread.
    private var nearCarry = ShortArray(0)
    // far-end (reference) 16 kHz framing remainder — touched only by the playback thread.
    private var farCarry = ShortArray(0)
    // 3:2 decimator input remainder (≤2 samples @24k) — touched only by the playback thread.
    private var rsRemainder = ShortArray(0)

    /** Create the AECM instance. Returns false (and stays pass-through) if the native
     *  lib is unavailable on this ABI — never blocks the dialog on AEC. */
    fun init(): Boolean = try {
        aec = AEC(AEC.SamplingFrequency.FS_16000Hz, aggressiveMode)
        Log.i(TAG, "AECM ready (16k mono, mode=${aggressiveMode.mode}, delay=${delayMs}ms)")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "AECM init failed; pass-through", t); aec = null; false
    }

    /** Feed far-end playback PCM (24 kHz mono PCM16, little-endian). Decimates → 16 kHz,
     *  buffers into 10 ms frames, hands each to AECM as the echo reference. */
    override fun processReverse(pcm24k: ByteArray) {
        val a = aec ?: return
        // 24 kHz → 16 kHz, 3:2 decimation. For each group of 3 input samples emit 2:
        //   out0 = in0,  out1 = (in1 + in2) / 2  (linear midpoint). Carry <3 leftover samples.
        val src = concat(rsRemainder, bytesToShorts(pcm24k))
        val groups = src.size / 3
        val out = ShortArray(groups * 2)
        var si = 0; var oi = 0
        repeat(groups) {
            out[oi] = src[si]
            out[oi + 1] = ((src[si + 1].toInt() + src[si + 2].toInt()) / 2).toShort()
            si += 3; oi += 2
        }
        rsRemainder = src.copyOfRange(groups * 3, src.size)

        // Buffer the 16 kHz reference into 160-sample frames for AECM.
        val buf = concat(farCarry, out)
        var off = 0
        while (off + FRAME <= buf.size) {
            val frame = buf.copyOfRange(off, off + FRAME)
            synchronized(lock) { a.farendBuffer(frame, FRAME) }
            off += FRAME
        }
        farCarry = buf.copyOfRange(off, buf.size)
    }

    /** Run near-end mic PCM (16 kHz mono PCM16, little-endian) through AECM. Returns the
     *  echo-cancelled bytes (same rate/length as the framed input), or null if AEC is off.
     *  May return an empty array if the input held only a sub-frame remainder. */
    override fun processCapture(pcm16k: ByteArray): ByteArray? {
        val a = aec ?: return null
        val buf = concat(nearCarry, bytesToShorts(pcm16k))
        val frames = buf.size / FRAME
        if (frames == 0) { nearCarry = buf; return ByteArray(0) }
        val cleaned = ShortArray(frames * FRAME)
        var off = 0
        repeat(frames) {
            val frame = buf.copyOfRange(off, off + FRAME)
            val outFrame = synchronized(lock) { a.echoCancellation(frame, FRAME, delayMs) }
            if (outFrame != null && outFrame.size == FRAME) {
                System.arraycopy(outFrame, 0, cleaned, off, FRAME)
            } else {
                System.arraycopy(frame, 0, cleaned, off, FRAME)  // AECM hiccup → pass the raw frame
            }
            off += FRAME
        }
        nearCarry = buf.copyOfRange(off, buf.size)
        return shortsToBytes(cleaned)
    }

    override fun release() {
        try { synchronized(lock) { aec?.close() } } catch (_: Throwable) {}
        aec = null
        nearCarry = ShortArray(0); farCarry = ShortArray(0); rsRemainder = ShortArray(0)
    }

    // ── PCM16 little-endian helpers ──────────────────────────────────────
    private fun bytesToShorts(b: ByteArray): ShortArray {
        val out = ShortArray(b.size / 2)
        var j = 0
        for (i in out.indices) {
            out[i] = ((b[j].toInt() and 0xFF) or (b[j + 1].toInt() shl 8)).toShort()
            j += 2
        }
        return out
    }

    private fun shortsToBytes(s: ShortArray): ByteArray {
        val out = ByteArray(s.size * 2)
        var j = 0
        for (v in s) {
            val iv = v.toInt()
            out[j] = (iv and 0xFF).toByte()
            out[j + 1] = ((iv shr 8) and 0xFF).toByte()
            j += 2
        }
        return out
    }

    private fun concat(a: ShortArray, b: ShortArray): ShortArray {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = ShortArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        return out
    }
}

package com.dashieapp.Dashie.voice.realtime

import android.util.Log
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * AEC3 software echo canceller for conversation mode — build plan
 * 20260627_REALTIME_VOICE_AEC, Phase 1. Primary canceller (AECM [RealtimeAec] is the
 * fallback).
 *
 * Wraps WebRTC's full-band AudioProcessing (AEC3) via the vendored `libdashie_aec3.so`
 * (~0.9 MB/ABI, arm64-v8a + armeabi-v7a). Unlike AECM, AEC3 does proper *linear* echo
 * subtraction and preserves near-end speech during double-talk — that's the talk-over
 * barge-in win. It also accepts render and capture at their native rates, so the 24 kHz
 * playback reference and 16 kHz mic are fed as-is — no resampler.
 *
 * AEC3 wants 10 ms frames: render = renderRate/100 samples (240 @24k), capture =
 * captureRate/100 (160 @16k). Carry buffers hold sub-frame remainders. [processReverse]
 * runs on the playback/WS thread and [processCapture] on the mic thread, so native access
 * is guarded by [lock]; the per-stream carry buffers are single-threaded and lock-free.
 *
 * Never throws into the audio path: a failed native create leaves [isReady] false and the
 * caller falls back (AECM or raw pass-through).
 */
class RealtimeAec3(
    private val captureRate: Int = 16000,
    private val renderRate: Int = 24000,
    private val noiseSuppression: Boolean = true,
) : SoftwareAec {

    companion object {
        private const val TAG = "RealtimeAec3"

        @Volatile private var libLoaded = false
        private fun ensureLib(): Boolean {
            if (libLoaded) return true
            return try {
                System.loadLibrary("dashie_aec3"); libLoaded = true; true
            } catch (t: Throwable) {
                Log.w(TAG, "libdashie_aec3 load failed (ABI unsupported?)", t); false
            }
        }
    }

    // Render (processReverse) and capture (processCapture) run on SEPARATE threads so AEC3's
    // two streams use two cores in parallel — the single-thread serial path saturated the weak
    // Echo Show. WebRTC's APM supports concurrent render/capture calls, so both take the READ
    // lock (they don't block each other); only release() takes the WRITE lock (waits for any
    // in-flight processing before freeing the native instance).
    private val rw = ReentrantReadWriteLock()
    @Volatile private var handle: Long = 0L
    private val captureFrame = captureRate / 100   // 160 @16k
    private val renderFrame = renderRate / 100     // 240 @24k

    private var nearCarry = ShortArray(0)   // capture thread only
    private var farCarry = ShortArray(0)    // playback thread only

    val isReady: Boolean get() = handle != 0L

    /** Build the APM. Returns false (pass-through) if the native lib/instance is unavailable. */
    fun init(): Boolean {
        if (!ensureLib()) return false
        return try {
            handle = nativeCreate(captureRate, renderRate, noiseSuppression)
            if (handle != 0L) {
                Log.i(TAG, "AEC3 ready (capture=$captureRate, render=$renderRate, ns=$noiseSuppression)")
                true
            } else {
                Log.w(TAG, "AEC3 nativeCreate returned 0"); false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AEC3 init failed", t); handle = 0L; false
        }
    }

    /** AEC3 stats for double-talk discrimination:
     *  [0] residual_echo_likelihood (0..1) — envelope-correlation echo prob (dead on device,
     *      see 20260703_BARGEIN_ESCALATION_HANDOFF; kept for comparison logging)
     *  [1] ERLE_dB, [2] ERL_dB
     *  [3] dominant_nearend (0/1) — AEC3 DominantNearendDetector state (ratio-based)
     *  [4] nearend_enr — echo/nearend low-freq ratio (LOW ⇒ near-end dominant ⇒ barge-in)
     *  [5] nearend_snr — nearend/noise low-freq ratio (HIGH ⇒ real near-end)
     *  Any element -1 if unavailable; null if AEC is inactive. Indices [3..5] are the
     *  ratio-based signal that replaces [0] for talk-over barge-in. */
    fun stats(): DoubleArray? {
        val h = handle; if (h == 0L) return null
        return try {
            val rl = rw.readLock(); rl.lock()
            try { if (handle != 0L) nativeStats(handle) else null } finally { rl.unlock() }
        } catch (_: Throwable) { null }
    }

    /** Coarse render↔capture delay hint. AEC3 also runs its own delay estimator. */
    fun setStreamDelayMs(ms: Int) {
        val rl = rw.readLock(); rl.lock()
        try { val h = handle; if (h != 0L) nativeSetStreamDelay(h, ms) } catch (_: Throwable) {} finally { rl.unlock() }
    }

    override fun processReverse(pcm: ByteArray) {
        if (handle == 0L) return
        val buf = concat(farCarry, bytesToShorts(pcm))
        val frame = ShortArray(renderFrame)
        var off = 0
        val rl = rw.readLock(); rl.lock()
        try {
            while (off + renderFrame <= buf.size) {
                System.arraycopy(buf, off, frame, 0, renderFrame)
                val h = handle; if (h != 0L) nativeProcessReverse(h, frame)
                off += renderFrame
            }
        } finally { rl.unlock() }
        farCarry = buf.copyOfRange(off, buf.size)
    }

    override fun processCapture(pcm: ByteArray): ByteArray? {
        if (handle == 0L) return null
        val buf = concat(nearCarry, bytesToShorts(pcm))
        val frames = buf.size / captureFrame
        if (frames == 0) { nearCarry = buf; return ByteArray(0) }
        val cleaned = ShortArray(frames * captureFrame)
        val inF = ShortArray(captureFrame)
        val outF = ShortArray(captureFrame)
        var off = 0
        val rl = rw.readLock(); rl.lock()
        try {
            repeat(frames) {
                System.arraycopy(buf, off, inF, 0, captureFrame)
                val h = handle
                val ok = if (h != 0L) nativeProcessCapture(h, inF, outF) else -1
                if (ok == 0) System.arraycopy(outF, 0, cleaned, off, captureFrame)
                else System.arraycopy(inF, 0, cleaned, off, captureFrame)   // APM hiccup → raw frame
                off += captureFrame
            }
        } finally { rl.unlock() }
        nearCarry = buf.copyOfRange(off, buf.size)
        return shortsToBytes(cleaned)
    }

    override fun release() {
        val wl = rw.writeLock(); wl.lock()
        try {
            val h = handle; handle = 0L
            if (h != 0L) try { nativeDestroy(h) } catch (_: Throwable) {}
        } finally { wl.unlock() }
        nearCarry = ShortArray(0); farCarry = ShortArray(0)
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

    // ── native (libdashie_aec3.so) ───────────────────────────────────────
    private external fun nativeCreate(captureRate: Int, renderRate: Int, ns: Boolean): Long
    private external fun nativeStats(handle: Long): DoubleArray?
    private external fun nativeSetStreamDelay(handle: Long, delayMs: Int)
    private external fun nativeProcessReverse(handle: Long, frame: ShortArray)
    private external fun nativeProcessCapture(handle: Long, inFrame: ShortArray, outFrame: ShortArray): Int
    private external fun nativeDestroy(handle: Long)
}

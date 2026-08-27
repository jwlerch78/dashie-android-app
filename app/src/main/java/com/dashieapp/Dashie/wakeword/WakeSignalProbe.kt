package com.dashieapp.Dashie.wakeword

import android.content.Context
import android.os.SystemClock
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.util.StableDeviceId
import kotlin.math.log10

/**
 * One structured line per wake-word trigger, carrying the candidate signals a multi-device
 * arbiter might rank on. MEASUREMENT ONLY — nothing reads these lines at runtime.
 *
 * The open question this exists to answer: when two devices hear the same "hey dashie",
 * can we tell which one the speaker was closest to? We do not yet know which signal
 * discriminates, so this deliberately records several candidates rather than betting on one.
 *
 * NOT confidence. AudioPreprocessor.normalizeVolume() peak-normalizes before inference, so
 * the score is proximity-blind by construction — and it isn't even comparable between devices,
 * since MEDIUM reports avg(EI, MWW) while an EI-only device reports a raw EI score against a
 * different threshold. Both legs are recorded separately here precisely because the AND-gate's
 * avg() destroys the information we'd want.
 *
 * Collected via PersistentLog (survives reboot, pullable over the :2323 `getDiagnosticsLog`
 * command and the device_cmd remote-diagnostics path — no ADB needed). PersistentLog writes
 * the file asynchronously and mirrors to logcat, so this costs the detection path nothing.
 */
object WakeSignalProbe {

    /** Bump when the line format changes; analyze-sweep.mjs pins on it. */
    const val VERSION = 1

    private const val TAG = "WakeProbe"

    @Volatile private var cachedDeviceId: String? = null

    /**
     * @param engine     "ei" | "mww" | "dual" — which decision produced this line.
     * @param trigger    true if this line represents a wake the device would ACT on (an EI-only
     *                   fire or an accepted AND-gate). False = a leg fire that still needs its
     *                   partner to agree; useful for margin analysis, not a response.
     * @param rms        Wake-window rms, RAW (pre-normalization), normalized float scale.
     * @param peak       Wake-window maxAbs, same scale.
     * @param noiseFloor Ambient floor from [NoiseFloorTracker], or null if not yet settled.
     */
    fun emit(
        context: Context,
        engine: String,
        mode: String,
        trigger: Boolean,
        confidence: Float,
        eiConfidence: Float?,
        mwwConfidence: Float?,
        rms: Float,
        peak: Float,
        noiseFloor: Float?,
        floorSamples: Int,
        bufferPosition: Long,
    ) {
        try {
            val deviceId = cachedDeviceId ?: StableDeviceId.read(context).also { cachedDeviceId = it }

            // 20*log10 — these are amplitude ratios, not power.
            val snrDb: Float? =
                if (noiseFloor != null && noiseFloor > 0f && rms > 0f) 20f * log10(rms / noiseFloor)
                else null

            // Wall clock is what lets us cluster ACROSS devices; the monotonic clock lets analysis
            // separate genuine detection skew from clock skew between devices. Both, always.
            val line = buildString(220) {
                append("WAKEPROBE v").append(VERSION)
                append(" ts=").append(System.currentTimeMillis())
                append(" mono=").append(SystemClock.elapsedRealtime())
                append(" dev=").append(deviceId)
                append(" engine=").append(engine)
                append(" mode=").append(mode)
                append(" trig=").append(if (trigger) 1 else 0)
                append(" conf=").append(f(confidence))
                append(" ei=").append(eiConfidence?.let { f(it) } ?: "-")
                append(" mww=").append(mwwConfidence?.let { f(it) } ?: "-")
                append(" rms=").append(f6(rms))
                append(" peak=").append(f6(peak))
                append(" floor=").append(noiseFloor?.let { f6(it) } ?: "-")
                append(" snr=").append(snrDb?.let { f(it) } ?: "-")
                append(" fn=").append(floorSamples)
                append(" pos=").append(bufferPosition)
            }

            PersistentLog.info(TAG, line)
        } catch (e: Exception) {
            // A probe must never be able to break a wake word.
            PersistentLog.warn(TAG, "probe failed: ${e.message}")
        }
    }

    private fun f(v: Float) = String.format("%.3f", v)
    private fun f6(v: Float) = String.format("%.6f", v)
}

package com.dashieapp.Dashie.wakeword

/**
 * Rolling ambient-noise floor, used by [WakeSignalProbe] to turn a raw wake-word level
 * into an SNR (multi-device wake arbitration, Phase A measurement).
 *
 * WHY THIS EXISTS: the wake-word confidence score cannot tell us how close the speaker was.
 * AudioPreprocessor.normalizeVolume() peak-normalizes every window before inference —
 * deliberately, to boost distant audio — so loudness is divided out before the model sees it.
 * The only proximity-bearing numbers are the raw pre-normalization rms/maxAbs computed in
 * EdgeImpulseDetector.processAudioChunkInternal(). Raw level alone isn't comparable across
 * devices (mic sensitivity differs wildly between the Samsung, the Mio 15" and an Echo Show),
 * so we divide by each device's OWN ambient floor. That cancels most of the fixed gain
 * difference and leaves something roughly comparable fleet-wide.
 *
 * WHY A LOW PERCENTILE, NOT A MEAN: the room contains speech, music and Dashie's own TTS.
 * A mean tracks those bursts and would collapse the SNR of the very utterance we're measuring.
 * A low percentile over a long window reads through them to the quiet floor underneath.
 *
 * Nothing here is on the audio hot path: [record] is O(1), and [floor] — which sorts — is only
 * called when a wake word actually fires.
 */
class NoiseFloorTracker(
    /** ~30s at EdgeImpulseDetector's ~100ms poll cadence. */
    private val windowSamples: Int = 300,
    /**
     * The wake word must not raise its own floor. EI reads a 1s window, so a detection's audio
     * is already in the ring by the time it fires; exclude the most recent samples outright.
     */
    private val excludeRecentMs: Long = 1_500,
    /** Below this, the floor is a guess — report null rather than a fabricated SNR. */
    private val minSamples: Int = 50,
    /** 10th percentile: robust to speech/TTS bursts without chasing a single quiet frame. */
    private val percentile: Float = 0.10f,
    /**
     * Detection stops during every turn and restarts after, so the ring survives gaps. Age
     * samples out by wall-clock rather than resetting on start(): the floor is a property of
     * the ROOM and stays valid across a turn, but an hour-old sample describes a different room.
     */
    private val maxAgeMs: Long = 120_000,
) {
    private val rmsRing = FloatArray(windowSamples)
    private val atRing = LongArray(windowSamples)
    private var head = 0
    private var count = 0

    /** Record one observed window rms. Call from the detection loop; O(1), allocation-free. */
    @Synchronized
    fun record(rms: Float, atMs: Long) {
        rmsRing[head] = rms
        atRing[head] = atMs
        head = (head + 1) % windowSamples
        if (count < windowSamples) count++
    }

    /**
     * The ambient floor as of [nowMs], ignoring the last [excludeRecentMs].
     * Returns null when there isn't enough settled history to be honest about it.
     */
    @Synchronized
    fun floor(nowMs: Long): Float? {
        val eligible = collectEligible(nowMs) ?: return null
        eligible.sort()
        val rank = ((eligible.size - 1) * percentile).toInt().coerceIn(0, eligible.size - 1)
        return eligible[rank]
    }

    /** Number of settled samples backing [floor] — lets analysis discount a thin floor. */
    @Synchronized
    fun settledCount(nowMs: Long): Int = collectEligible(nowMs)?.size ?: 0

    /** Samples old enough to have settled but young enough to still describe this room. */
    private fun collectEligible(nowMs: Long): FloatArray? {
        if (count == 0) return null
        val newest = nowMs - excludeRecentMs
        val oldest = nowMs - maxAgeMs

        var n = 0
        val scratch = FloatArray(count)
        for (i in 0 until count) {
            val idx = (head - 1 - i + windowSamples * 2) % windowSamples
            val at = atRing[idx]
            if (at in oldest..newest) scratch[n++] = rmsRing[idx]
        }
        return if (n < minSamples) null else scratch.copyOf(n)
    }

    @Synchronized
    fun reset() {
        head = 0
        count = 0
    }
}

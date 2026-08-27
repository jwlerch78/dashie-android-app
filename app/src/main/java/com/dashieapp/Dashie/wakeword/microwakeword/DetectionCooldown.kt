package com.dashieapp.Dashie.wakeword.microwakeword

/**
 * The debounce that stops ONE utterance being reported thirty times — and the rollback that stops
 * a DISCARDED detection from spending it.
 *
 * ## Why this is its own class, and pure
 *
 * Same reasoning as [MicroWakeWordRearmPolicy] and
 * [com.dashieapp.Dashie.wakeword.BackfillCorroboration], and for the same earned reason: this is
 * logic that was **wrong on a device and unassertable off one**. The stamp lived as three fields
 * inside [MicroWakeWordDetector], reachable only with a mic, a native classifier and a live ring
 * buffer — so the defect below shipped, was measured by a human speaking at a loudspeaker, and
 * could not be pinned by a unit test at any point in between.
 *
 * Taking the clock as a parameter rather than reading it is what makes the whole thing assertable.
 *
 * ## The defect this class exists to prevent
 *
 * [MicroWakeWordDetector] must stamp the cooldown BEFORE the gate has judged the detection — the
 * stamp is what keeps one wake word from re-firing every 30 ms while it is still audible. But a
 * BACK-FILLED detection then goes to `BackfillGate`, which may DISCARD it (EI declines to
 * corroborate, or the audio has rolled out of the ring). Nothing gave the stamp back.
 *
 * 🔴 **Measured, 5/5:** the discarded back-filled fire was followed ~600 ms later
 * by EI firing LIVE on the same utterance, and the gate logged *"no MWW corroboration arrived
 * within 500ms"* — because microWakeWord was debounced by a detection that had been thrown away.
 * **One wake word, heard by both engines, gated by neither.** The user says "Hey Dashie" and
 * nothing happens.
 *
 * ⚠️ **The rollback is bounded to once per run.** Unbounded, a re-arm that keeps producing
 * uncorroborated back-fills would hand the cooldown back indefinitely and re-detect in a loop —
 * trading a lost wake for a busy one, which is a worse trade than the bug.
 */
internal class DetectionCooldown(private val cooldownMs: Long) {

    /** Wall-clock of the detection currently holding the cooldown. 0 = never fired. */
    var lastDetectionTime = 0L
        private set

    /** The stamp displaced by [tryStamp], so [rollback] can restore it. */
    private var previousDetectionTime = 0L

    private var rollbackUsed = false

    /** Call at every `start()`. The run boundary is what bounds the rollback. */
    fun onRunStarted() {
        rollbackUsed = false
    }

    fun isDebounced(now: Long): Boolean = now - lastDetectionTime < cooldownMs

    /** Milliseconds left on the debounce, or 0 when it has expired. */
    fun remainingMs(now: Long): Long = (cooldownMs - (now - lastDetectionTime)).coerceAtLeast(0L)

    /**
     * Take the cooldown for a detection at [now].
     *
     * @return true when the caller may report the detection; false when it is debounced.
     */
    fun tryStamp(now: Long): Boolean {
        if (isDebounced(now)) return false
        previousDetectionTime = lastDetectionTime
        lastDetectionTime = now
        return true
    }

    /**
     * Give the cooldown back after the gate DISCARDED the detection that took it.
     *
     * ⚠️ **Only ever for a DISCARDED detection.** Rolling back after a TRIGGER would let one
     * utterance wake the device twice, which is the thing the cooldown exists to prevent.
     *
     * @return true if the stamp was restored; false if this run has already spent its one
     *         rollback (see the class KDoc for why that bound exists).
     */
    fun rollback(): Boolean {
        if (rollbackUsed) return false
        rollbackUsed = true
        lastDetectionTime = previousDetectionTime
        return true
    }
}

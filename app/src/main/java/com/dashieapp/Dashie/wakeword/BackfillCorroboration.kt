package com.dashieapp.Dashie.wakeword

/**
 * The decision half of "should a BACK-FILLED microWakeWord detection wake the device?"
 *
 * ## Why this is its own file, and pure
 *
 * The re-arm race has now produced a wrong answer twice, each time from timing rather than from
 * thresholds — first with MWW unable to see the audio (0/3), then with MWW able to
 * see it and EI unable (0/5, the same defect with the legs swapped). Both were
 * discovered on a device, by speaking at a loudspeaker, because the logic lived inside a detector
 * that needs a mic, a native classifier and a live ring buffer to run at all.
 *
 * Separating the DECISION from the I/O makes the part that keeps being wrong assertable without
 * any of that. [decide] takes two scores and a clock and returns what should happen;
 * `DualEngineDetector` does the pulling, the logging and the firing.
 */
object BackfillCorroboration {

    sealed interface Decision {
        /** Both engines agree on the same audio — wake, at the averaged confidence. */
        data class Trigger(val avgConfidence: Float) : Decision

        /** Another path already triggered on this utterance. Not a lost wake. */
        data class Debounced(val sinceLastTriggerMs: Long) : Decision

        /** EI could not be asked — the audio has rolled out of the ring. */
        object AudioGone : Decision

        /** EI was asked and disagreed. The gate working, not a defect. */
        data class NotCorroborated(val eiConfidence: Float, val eiThreshold: Float) : Decision
    }

    /**
     * @param mwwConfidence the back-filled MWW score.
     * @param eiConfidence EI's score for the SAME window, or null when that audio is gone.
     * @param eiThreshold the gate's EI bar for the active sensitivity rung.
     * @param sinceLastTriggerMs wall-clock since the last trigger from any path.
     * @param triggerCooldownMs the shared cooldown.
     *
     * ⚠️ **The cooldown is checked FIRST, before the score comparison, and that ordering matters.**
     * A back-filled detection can arrive milliseconds after a live gate has already fired on the same
     * utterance — re-triggering there would be a duplicate wake, which is user-visible in a way a
     * missed one is not. Checking scores first and the clock second would let a high-confidence
     * pair jump the guard.
     */
    fun decide(
        mwwConfidence: Float,
        eiConfidence: Float?,
        eiThreshold: Float,
        sinceLastTriggerMs: Long,
        triggerCooldownMs: Long,
    ): Decision {
        if (sinceLastTriggerMs < triggerCooldownMs) return Decision.Debounced(sinceLastTriggerMs)
        if (eiConfidence == null) return Decision.AudioGone
        if (eiConfidence < eiThreshold) {
            return Decision.NotCorroborated(eiConfidence, eiThreshold)
        }
        return Decision.Trigger((eiConfidence + mwwConfidence) / 2f)
    }
}

package com.dashieapp.Dashie.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors for the decision that closes the re-arm race's second half.
 *
 * ## Why this is worth unit-testing at all
 *
 * This exact question has been answered wrongly twice on a device, and both times the discovery
 * cost a scripted rig, a loudspeaker and a Samsung: first MWW could not see the audio (T s44
 * cont.5, 0/3), then MWW could and EI could not (T s44 cont.14, 0/5 — the same defect with the
 * legs swapped). Neither failure was about thresholds; both were about *which audio each engine
 * was judged on*. Once that is a pure function of two scores and a clock, it can be pinned here
 * and the device pass only has to confirm the plumbing.
 */
class BackfillCorroborationTest {

    private val cooldownMs = 2_000L
    private val eiBar = 0.35f   // MEDIUM's EI threshold

    private fun decide(
        mww: Float = 0.90f,
        ei: Float? = 0.70f,
        sinceLastTrigger: Long = 10_000L,
    ) = BackfillCorroboration.decide(mww, ei, eiBar, sinceLastTrigger, cooldownMs)

    @Test fun `both engines agreeing on the same audio triggers, at the average`() {
        val d = decide(mww = 0.90f, ei = 0.70f)
        assertTrue(d is BackfillCorroboration.Decision.Trigger)
        assertEquals(0.80f, (d as BackfillCorroboration.Decision.Trigger).avgConfidence, 1e-6f)
    }

    @Test fun `EI disagreeing on the same audio does NOT trigger`() {
        // The path that keeps a back-filled MWW fire from becoming an un-corroborated trigger.
        // Without it, the re-arm window would be an MWW-only gate at a false-accept rate nobody
        // has measured.
        val d = decide(mww = 1.0f, ei = 0.34f)
        assertTrue(d is BackfillCorroboration.Decision.NotCorroborated)
        assertEquals(0.34f, (d as BackfillCorroboration.Decision.NotCorroborated).eiConfidence, 1e-6f)
    }

    @Test fun `EI exactly AT the bar corroborates`() {
        // Boundary, stated explicitly because ">=" vs ">" here decides real wakes and the live
        // gate uses >=. The two paths must not disagree about what "clears the bar" means.
        assertTrue(decide(ei = eiBar) is BackfillCorroboration.Decision.Trigger)
    }

    @Test fun `audio that has rolled out of the ring is reported, not silently ignored`() {
        // Distinct from NotCorroborated on purpose: "EI said no" and "EI could not be asked" are
        // different facts, and only the second one means the reach is too wide for the buffer.
        assertTrue(decide(ei = null) is BackfillCorroboration.Decision.AudioGone)
    }

    @Test fun `the cooldown is checked BEFORE the scores`() {
        // 🎯 The ordering vector. A back-filled detection can arrive milliseconds after the live
        // gate already fired on the same utterance; a duplicate wake is user-visible in a way a
        // missed one is not. If scores were checked first, a confident pair would jump the guard —
        // so this asserts a perfect pair inside the cooldown still debounces.
        val d = decide(mww = 1.0f, ei = 1.0f, sinceLastTrigger = 100L)
        assertTrue("a perfect pair inside the cooldown must still debounce",
            d is BackfillCorroboration.Decision.Debounced)
    }

    @Test fun `just past the cooldown, the same pair triggers`() {
        // Control for the vector above: proves it is debouncing on the CLOCK and not because the
        // scores were somehow rejected.
        assertTrue(decide(mww = 1.0f, ei = 1.0f, sinceLastTrigger = cooldownMs) is
            BackfillCorroboration.Decision.Trigger)
    }

    @Test fun `a stale-audio case inside the cooldown debounces rather than reporting AudioGone`() {
        // Both conditions true at once. The cooldown wins, and it should: nothing was lost, so
        // reporting a discarded wake would put a false defect signal in the log.
        assertTrue(decide(ei = null, sinceLastTrigger = 0L) is
            BackfillCorroboration.Decision.Debounced)
    }
}

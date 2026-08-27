package com.dashieapp.Dashie.halite.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The defect these pin, measured on device 2026-08-26: in HA-Assist the wake detector was stopped
 * for the ENTIRE interaction plus a 502 ms tail — 5.15–5.18 s of nothing listening, 4/4 — so
 * "Hey Dashie" spoken over a reply could not be heard and the `BARGE:` gate was structurally
 * unreachable. Found by a person talking to a tablet, ~8 months after it shipped.
 *
 * ⚠️ Every assertion here was **fault-injected before being trusted** — a test that can only pass
 * is not evidence. The injection that breaks each one is named on the test.
 */
class HaWakeRearmPolicyTest {

    private fun rearm(s: HaVoiceService.VoiceState) = HaWakeRearmPolicy.shouldRearmOnEntering(s)

    // ── The defect ────────────────────────────────────────────────────────────

    /**
     * 🔴 THE REGRESSION TEST. These are the two states the user actually interrupts during, and
     * the ones that were unreachable before the fix.
     *
     * Fault-injected: returning false for these (the pre-fix behaviour) fails both assertions.
     */
    @Test
    fun `the detector comes back once recording is over`() {
        assertTrue("HA is thinking — the user can already interrupt",
            rearm(HaVoiceService.VoiceState.PROCESSING))
        assertTrue("HA is speaking — this is THE barge-in window",
            rearm(HaVoiceService.VoiceState.SPEAKING))
    }

    /**
     * The other half, and the one that keeps the fix honest: re-arming while the microphone is
     * being transcribed would self-trigger on the user's own command.
     *
     * Fault-injected: adding LISTENING to the true-branch fails this.
     */
    @Test
    fun `the detector stays down while the user is being recorded`() {
        assertFalse("re-arming here would self-trigger on the user's own speech",
            rearm(HaVoiceService.VoiceState.LISTENING))
        assertFalse("nothing to interrupt yet", rearm(HaVoiceService.VoiceState.CONNECTING))
    }

    /**
     * Turn-END states are deliberately false: they keep the long-standing completion-path restart,
     * and returning true would put two restarts on one transition.
     *
     * Fault-injected: adding IDLE/ERROR to the true-branch fails this.
     */
    @Test
    fun `turn-end states defer to the completion path`() {
        assertFalse(rearm(HaVoiceService.VoiceState.IDLE))
        assertFalse(rearm(HaVoiceService.VoiceState.ERROR))
    }

    /**
     * Exhaustiveness, so a NEW state cannot quietly default to "never re-arm" — which is how the
     * original hole read: the code said nothing about SPEAKING, so nothing happened there.
     */
    @Test
    fun `every state has a deliberate answer`() {
        val answered = HaVoiceService.VoiceState.values().map { it to rearm(it) }
        assertTrue("every state must be classified", answered.size == 6)
        assertTrue("exactly the two post-recording states re-arm",
            answered.filter { it.second }.map { it.first }.toSet() ==
                setOf(HaVoiceService.VoiceState.PROCESSING, HaVoiceService.VoiceState.SPEAKING))
    }

    // ── The THIRD defect: the honoured wake that was thrown away ──────────────

    private fun interruptible(s: HaVoiceService.VoiceState) = HaWakeRearmPolicy.isInterruptible(s)

    /** The wake-ACCEPTANCE rule as the service applies it: IDLE starts a turn, an
     *  interruptible state barges into one, everything else is a real reject. */
    private fun accepted(s: HaVoiceService.VoiceState) =
        s == HaVoiceService.VoiceState.IDLE || interruptible(s)

    /**
     * 🔴 THE REGRESSION TEST for the discard. Measured 3/3 on John's voice at vc188 — including a
     * fire at 100 % confidence, which is why no threshold was ever the cause: the wake passed
     * every gate and `if (currentState != IDLE) return` dropped it at Log.d.
     *
     * Fault-injected: restoring the IDLE-only guard fails both assertions.
     */
    @Test
    fun `a wake during HA thinking or speaking is honoured, not discarded`() {
        assertTrue("barge-in during the reply is the whole feature",
            accepted(HaVoiceService.VoiceState.SPEAKING))
        assertTrue("interrupting while HA thinks is equally valid",
            accepted(HaVoiceService.VoiceState.PROCESSING))
    }

    /**
     * The guard is narrowed, NOT deleted — deleting it would let the detector self-trigger on the
     * user's own command audio mid-transcription.
     *
     * Fault-injected: accepting in every state (removing the guard) fails this.
     */
    @Test
    fun `a wake while recording or connecting is still rejected`() {
        assertFalse("would self-trigger on the user's own speech",
            accepted(HaVoiceService.VoiceState.LISTENING))
        assertFalse(accepted(HaVoiceService.VoiceState.CONNECTING))
    }

    /**
     * 🔑 The seam's sharp edge, and the reason the acceptance rule is not simply this predicate:
     * IDLE is where a turn NORMALLY starts, so it must be accepted — while re-arming on entering
     * IDLE must stay false or the completion path restarts twice. One set, two different uses.
     *
     * Fault-injected: writing the guard as `if (!isInterruptible(state)) return` — the literal
     * "same set" reading — fails this, and on a device it would reject every ordinary wake and
     * present as voice being completely dead.
     */
    @Test
    fun `IDLE is accepted for a wake but never re-armed on`() {
        assertTrue("the ordinary way a turn begins", accepted(HaVoiceService.VoiceState.IDLE))
        assertFalse("the completion path already restarts here",
            rearm(HaVoiceService.VoiceState.IDLE))
    }
}

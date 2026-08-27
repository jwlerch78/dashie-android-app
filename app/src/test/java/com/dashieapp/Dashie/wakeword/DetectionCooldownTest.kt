package com.dashieapp.Dashie.wakeword

import com.dashieapp.Dashie.wakeword.microwakeword.DetectionCooldown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The defect these pin is T s44 cont.20, measured 5/5 on a device: a back-filled detection took
 * the cooldown, the gate DISCARDED it, nothing gave the cooldown back — so when EI fired LIVE on
 * the same utterance ~600 ms later, microWakeWord was debounced and could not corroborate. One
 * wake word, heard by both engines, gated by neither.
 *
 * ⚠️ Every assertion below was **fault-injected before being trusted** — the s175 lesson that a
 * test which can only pass is not evidence. The injections are named per test.
 */
class DetectionCooldownTest {

    private val cooldownMs = 1500L

    private fun armed() = DetectionCooldown(cooldownMs).apply { onRunStarted() }

    // ── The defect ────────────────────────────────────────────────────────────

    /**
     * 🔴 THE REGRESSION TEST. The exact device timeline: back-filled detection at t, discarded,
     * EI fires live 600 ms later. MWW must be able to fire again to corroborate it.
     *
     * Fault-injected: with `rollback()` reduced to `return false` (the pre-fix behaviour), the
     * final assertion fails — 600 ms is inside the 1500 ms debounce.
     */
    @Test
    fun `a discarded back-filled detection does not blind the live path`() {
        val c = armed()
        assertTrue("the back-filled detection takes the cooldown", c.tryStamp(10_000))
        assertTrue("…and holds it", c.isDebounced(10_600))

        c.rollback() // the gate discarded it

        assertFalse("600ms later MWW must be free to fire", c.isDebounced(10_600))
        assertTrue("…and can actually corroborate EI's live fire", c.tryStamp(10_600))
    }

    /**
     * The other half, and the one that keeps the fix honest: a detection that TRIGGERED must keep
     * the cooldown, or one utterance wakes the device twice.
     *
     * Fault-injected: calling `rollback()` here (i.e. wiring the callback to every decision branch
     * rather than only the discard branches) makes this fail.
     */
    @Test
    fun `a TRIGGERED detection keeps the cooldown`() {
        val c = armed()
        assertTrue(c.tryStamp(10_000))
        // no rollback — the gate fired
        assertFalse("the same utterance must not re-report", c.tryStamp(10_600))
        assertTrue(c.isDebounced(11_400))
    }

    // ── The bound ─────────────────────────────────────────────────────────────

    /**
     * One rollback per run. Unbounded, a re-arm producing uncorroborated back-fills hands the
     * cooldown back forever and re-detects in a loop — a worse trade than the bug.
     *
     * Fault-injected: removing the `rollbackUsed` guard makes the second assertion fail.
     */
    @Test
    fun `rollback is spent after one use per run`() {
        val c = armed()
        c.tryStamp(10_000)
        assertTrue("first discard gives the cooldown back", c.rollback())
        c.tryStamp(10_600)
        assertFalse("second discard in the same run does NOT", c.rollback())
        assertTrue("…so the stamp stands and the loop cannot open", c.isDebounced(10_900))
    }

    /** A re-arm resets the bound — the next turn gets its own rollback. */
    @Test
    fun `a new run restores the rollback allowance`() {
        val c = armed()
        c.tryStamp(10_000)
        assertTrue(c.rollback())
        assertFalse(c.rollback())

        c.onRunStarted()
        c.tryStamp(20_000)
        assertTrue("the next re-arm gets its own rollback", c.rollback())
    }

    // ── Ordinary debounce behaviour must not move ─────────────────────────────

    @Test
    fun `the ordinary debounce is unchanged`() {
        val c = armed()
        assertTrue("first fire is always allowed", c.tryStamp(10_000))
        assertFalse("mid-cooldown is suppressed", c.tryStamp(11_000))
        assertFalse("the boundary-1 ms is still suppressed", c.tryStamp(11_499))
        assertTrue("exactly one cooldown later is allowed", c.tryStamp(11_500))
    }

    @Test
    fun `remainingMs reports the debounce and floors at zero`() {
        val c = armed()
        c.tryStamp(10_000)
        assertEquals(1500L, c.remainingMs(10_000))
        assertEquals(900L, c.remainingMs(10_600))
        assertEquals("never negative — the settle line prints this", 0L, c.remainingMs(99_999))
    }

    /**
     * A rollback before anything ever fired must not invent a future stamp. `previousDetectionTime`
     * starts at 0, so the detector lands back in the never-fired state rather than a debounced one.
     */
    @Test
    fun `rollback on a never-fired cooldown leaves it free`() {
        val c = armed()
        assertTrue(c.rollback())
        assertFalse(c.isDebounced(10_000))
        assertTrue(c.tryStamp(10_000))
    }
}

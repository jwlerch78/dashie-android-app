package com.dashieapp.Dashie.halite.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wake-prefix strip (T s41 cont.2, third defect). The name was MANDATORY in the regex, so a
 * capture that clipped it let the greeting through to the brain — and, because [isEndIntent]
 * matches exactly, made a real end-intent unrecognisable so the dialog stayed open.
 */
class WakePrefixStripTest {

    private fun strip(s: String) = CascadeDialogSupport.stripWakePrefix(s)

    @Test
    fun `the exact payload John saw is cleaned`() {
        assertEquals(
            "tell me a fun fact about Jupiter.",
            strip("- Hey, tell me a fun fact about Jupiter.")
        )
    }

    @Test
    fun `full wake word still strips - every spelling the decoder produces`() {
        listOf(
            "hey dashie, turn on the lights",
            "Hey Dashi turn on the lights",
            "okay dashy, turn on the lights",
            "ok dashee turn on the lights",
            "dashie turn on the lights",
        ).forEach { assertEquals("from '$it'", "turn on the lights", strip(it)) }
    }

    @Test
    fun `greeting-only form strips when the name was clipped`() {
        assertEquals("turn on the lights", strip("Hey, turn on the lights"))
        assertEquals("turn on the lights", strip("okay. turn on the lights"))
        assertEquals("thanks", strip("hey thanks"))
    }

    @Test
    fun `leading junk tokens are dropped`() {
        assertEquals("turn on the lights", strip("- turn on the lights"))
        assertEquals("turn on the lights", strip("♪ turn on the lights"))
        assertEquals("turn on the lights", strip("  —  turn on the lights  "))
    }

    @Test
    fun `the end-intent that used to be MISSED is now recognised`() {
        // This is the actual bug: a clipped-name end phrase kept the dialog open.
        assertTrue("hey thanks", CascadeDialogSupport.isEndIntent("hey thanks"))
        assertTrue("- Hey, thanks", CascadeDialogSupport.isEndIntent("- Hey, thanks"))
        assertTrue("baseline still works", CascadeDialogSupport.isEndIntent("thanks"))
    }

    @Test
    fun `content that merely STARTS with a greeting word is not eaten`() {
        // No trailing separator => it is a word, not a clipped wake form.
        assertEquals("okay google what time is it", strip("okay google what time is it"))
        // A bare greeting as the WHOLE utterance must survive, or it becomes empty and
        // cascadeDialogTurn would close the dialog on it.
        assertEquals("ok", strip("ok"))
        assertEquals("okay", strip("okay"))
    }

    @Test
    fun `a greeting inside the utterance is untouched - leading only`() {
        assertEquals("tell hey dashie to stop", strip("tell hey dashie to stop"))
    }

    @Test
    fun `stripping does not run twice - a real ok after the wake word survives`() {
        // Full wake word matched, so the greeting-only pass must NOT also fire.
        assertEquals("ok fine", strip("hey dashie, ok fine"))
    }

    @Test
    fun `a normal command is unchanged`() {
        listOf(
            "turn on the kitchen lights",
            "what's the weather tomorrow",
            "never mind",
        ).forEach { assertEquals(it, strip(it)) }
        assertFalse(CascadeDialogSupport.isEndIntent("what's the weather tomorrow"))
    }
}

package com.dashieapp.Dashie.halite.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two response rules that only existed as inline comments: an empty HA response becomes
 * "I didn't understand that", and a "sorry" response is shown but never spoken (grating read aloud,
 * especially after a false wake).
 */
class HaAssistResponsePlannerTest {

    @Test
    fun `a normal response is spoken by the device`() {
        val a = HaAssistResponsePlanner.plan("Turned on the lights", isCommand = true, deviceSpeaks = true)
        val speak = a as HaAssistResponsePlanner.Action.SpeakOnDevice
        assertEquals("Turned on the lights", speak.text)
        assertTrue("action_done must survive — a tone may replace the speech", speak.isCommand)
    }

    @Test
    fun `a sorry response is displayed, never spoken`() {
        listOf("Sorry, I couldn't understand that", "sorry", "I'm SORRY about that").forEach {
            val a = HaAssistResponsePlanner.plan(it, isCommand = false, deviceSpeaks = true)
            assertTrue("'$it' must not be spoken", a is HaAssistResponsePlanner.Action.DisplayOnly)
            assertEquals(it, (a as HaAssistResponsePlanner.Action.DisplayOnly).text)
        }
    }

    @Test
    fun `an empty response becomes the not-understood message`() {
        val a = HaAssistResponsePlanner.plan("", isCommand = false, deviceSpeaks = true)
        assertEquals(
            HaAssistResponsePlanner.NOT_UNDERSTOOD,
            (a as HaAssistResponsePlanner.Action.DisplayOnly).text,
        )
    }

    @Test
    fun `HA TTS shows the text and waits for audio`() {
        val a = HaAssistResponsePlanner.plan("It is 72 degrees", isCommand = false, deviceSpeaks = false)
        assertEquals("It is 72 degrees", (a as HaAssistResponsePlanner.Action.AwaitHaAudio).text)
    }

    @Test
    fun `HA TTS with an empty response still shows the fallback`() {
        val a = HaAssistResponsePlanner.plan("", isCommand = false, deviceSpeaks = false)
        assertEquals(
            HaAssistResponsePlanner.NOT_UNDERSTOOD,
            (a as HaAssistResponsePlanner.Action.AwaitHaAudio).text,
        )
    }

    @Test
    fun `the sorry rule does not apply when HA is synthesizing`() {
        // HA already generated the audio; suppressing it here would strand the turn waiting for a
        // tts-end we deliberately ignored.
        val a = HaAssistResponsePlanner.plan("Sorry, no", isCommand = false, deviceSpeaks = false)
        assertTrue(a is HaAssistResponsePlanner.Action.AwaitHaAudio)
    }
}

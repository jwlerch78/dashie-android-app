package com.dashieapp.Dashie.halite.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The transcript ladder shared by HA Voice Assist's two STT sources (HA's own `stt-end` and the
 * on-device stage). Precedence is the load-bearing part: weather before local commands before HA,
 * because the first two must PREVENT HA from also acting on the turn.
 *
 * Robolectric only for a real org.json.JSONObject (the weather outcome carries one) — same reason
 * as WeatherInterceptTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HaAssistTranscriptGateTest {

    private fun classify(
        raw: String,
        weatherWired: Boolean = true,
        intercept: (String) -> String? = { null },
    ) = HaAssistTranscriptGate.classify(raw, weatherWired, intercept)

    @Test
    fun `the wake word is stripped before anything else sees the text`() {
        val d = classify("hey dashie turn on the string lights")
        assertEquals("turn on the string lights", d.text)
        assertTrue(d.outcome is HaAssistTranscriptGate.Outcome.ForwardToHa)
    }

    @Test
    fun `the interceptor sees the stripped text, not the raw text`() {
        var seen: String? = null
        classify("hey dashie set a 5 minute timer", intercept = { seen = it; "Timer set" })
        assertEquals("set a 5 minute timer", seen)
    }

    @Test
    fun `weather wins over the local interceptor and over HA`() {
        val d = classify("what's the weather tomorrow", intercept = { "should not be consulted" })
        assertTrue("HA Assist cannot answer weather — it must never reach HA",
            d.outcome is HaAssistTranscriptGate.Outcome.Weather)
    }

    @Test
    fun `weather falls through to HA when native fulfillment is not wired`() {
        val d = classify("what's the weather tomorrow", weatherWired = false)
        assertTrue("a null callback must not silently drop the turn",
            d.outcome is HaAssistTranscriptGate.Outcome.ForwardToHa)
    }

    @Test
    fun `a claimed local command carries its spoken response`() {
        val d = classify("pause the music", intercept = { "Paused" })
        val outcome = d.outcome as HaAssistTranscriptGate.Outcome.LocalCommand
        assertEquals("Paused", outcome.response)
    }

    @Test
    fun `a silently handled local command is still an intercept`() {
        // Blank response = handled, nothing to speak. Must NOT be confused with "not claimed",
        // or HA would double-execute the command.
        val d = classify("volume up", intercept = { "" })
        assertTrue(d.outcome is HaAssistTranscriptGate.Outcome.LocalCommand)
        assertEquals("", (d.outcome as HaAssistTranscriptGate.Outcome.LocalCommand).response)
    }

    @Test
    fun `an unclaimed transcript goes to HA`() {
        val d = classify("turn off the kitchen lights", intercept = { null })
        assertTrue(d.outcome is HaAssistTranscriptGate.Outcome.ForwardToHa)
    }
}

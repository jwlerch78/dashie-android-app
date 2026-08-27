package com.dashieapp.Dashie.halite.voice

import com.dashieapp.Dashie.halite.voice.stt.hasSpeechBandEnergy
import com.dashieapp.Dashie.halite.voice.stt.isHallucinationSignature
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * The output half of the silence-hallucination gate (T s41 cont.2). The silero input gate needs
 * the native engine and a Context, so it is device-verified rather than unit-tested; these two
 * are pure and pinned here.
 *
 * The literal strings are the ones the Mio actually produced with nobody speaking.
 */
class SilenceHallucinationGateTest {

    // ── signature belt ───────────────────────────────────────────────────────────

    @Test
    fun `the two shapes observed on the Mio are caught`() {
        assertTrue("symbol-only", isHallucinationSignature("♪♪ ♪♪"))
        assertTrue(
            "4x looped sentence",
            isHallucinationSignature(
                "I'm going to go to the hospital. I'm going to go to the hospital. " +
                    "I'm going to go to the hospital. I'm going to go to the hospital."
            )
        )
    }

    @Test
    fun `other non-speech shapes are caught`() {
        assertTrue(isHallucinationSignature("..."))
        assertTrue(isHallucinationSignature("[ Music ]".replace(Regex("[A-Za-z]"), "")))
        assertTrue(isHallucinationSignature("♫"))
        assertTrue(isHallucinationSignature("- - -"))
    }

    @Test
    fun `real commands are NOT discarded`() {
        listOf(
            "turn on the kitchen lights",
            "what's the weather tomorrow",
            "add soccer practice to the calendar on friday at four",
            "play the beatles",
            "no",                                   // one word, still real
            "yes yes yes",                          // repetition WITHIN one sentence is not the shape
            "tell me a fun fact about Jupiter.",
        ).forEach { assertFalse("must survive: '$it'", isHallucinationSignature(it)) }
    }

    @Test
    fun `a sentence repeated twice is not enough - only three or more`() {
        // Deliberate: "okay. okay." is a plausible human utterance; 3x is the decoder looping.
        assertFalse(isHallucinationSignature("Okay. Okay."))
        assertTrue(isHallucinationSignature("Okay. Okay. Okay."))
    }

    @Test
    fun `empty stays empty - the caller handles it, this must not claim it`() {
        assertFalse(isHallucinationSignature(""))
        assertFalse(isHallucinationSignature("   "))
    }

    // ── energy fallback (only used when silero isn't bundled) ────────────────────

    @Test
    fun `digital silence and realistic DC offset are rejected`() {
        assertFalse("pure silence", hasSpeechBandEnergy(FloatArray(16000)))
        // A constant offset has RMS but ZERO speech-band content — what a bare RMS check
        // would wave through. Realistic offset magnitude; see the coarseness test below.
        assertFalse("DC offset", hasSpeechBandEnergy(FloatArray(16000) { 0.05f }))
    }

    @Test
    fun `low-frequency room rumble is rejected but a quieter speech-band tone passes`() {
        val sr = 16000.0
        // 60 Hz rumble at a realistic room level.
        val rumble = FloatArray(16000) { (0.05 * sin(2.0 * Math.PI * 60.0 * it / sr)).toFloat() }
        assertFalse("60Hz rumble", hasSpeechBandEnergy(rumble))
        // 500 Hz at the SAME amplitude passes — frequency, not loudness, is what decides.
        val voiceBand = FloatArray(16000) { (0.05 * sin(2.0 * Math.PI * 500.0 * it / sr)).toFloat() }
        assertTrue("500Hz tone", hasSpeechBandEnergy(voiceBand))
    }

    @Test
    fun `the fallback is COARSE - very loud low-frequency noise still passes, by design`() {
        // Pinning the limitation rather than pretending it isn't there: one-pole pre-emphasis
        // gives only ~26 dB of rejection at 60 Hz, so rumble at 6x a realistic level gets
        // through. This is why silero is the real gate and this runs only when it isn't
        // bundled. If this test ever starts FAILING, the fallback got better — update it.
        val sr = 16000.0
        val loudRumble = FloatArray(16000) { (0.3 * sin(2.0 * Math.PI * 60.0 * it / sr)).toFloat() }
        assertTrue("known limit", hasSpeechBandEnergy(loudRumble))
    }

    @Test
    fun `a buffer too short to filter is rejected rather than assumed`() {
        assertFalse(hasSpeechBandEnergy(FloatArray(0)))
        assertFalse(hasSpeechBandEnergy(FloatArray(1) { 0.9f }))
    }
}

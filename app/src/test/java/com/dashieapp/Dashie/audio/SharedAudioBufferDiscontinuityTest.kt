package com.dashieapp.Dashie.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [SharedAudioBuffer.markDiscontinuity] against the ghost-wake defect device-observed on
 * 2026-08-24 (John's HA-Assist pass, 3 ghosts in 7 turns at a constant 0.53 s offset).
 *
 * The shape being reproduced: a mic handoff stops capture, so the write position FREEZES while
 * wall-clock time passes. On restart, the audio at position `N-1` is adjacent to `N` in the ring
 * but seconds older in reality — and its newest content is the wake word that started the turn.
 * Every consumer that reads "the last X ms of history" then sees speech that is not there.
 */
class SharedAudioBufferDiscontinuityTest {

    private fun loudSpeech(n: Int) = FloatArray(n) { 0.7f }

    /** What a wake detector does: read the most recent window and look at it. */
    private fun energyOfRecentWindow(buf: SharedAudioBuffer, windowSize: Int): Float {
        val w = buf.readWindow(windowSize)
        return w.maxOf { kotlin.math.abs(it) }
    }

    @Test
    fun `without the fix, history across a capture gap still reads as recent speech`() {
        val buf = SharedAudioBuffer(sampleRate = 16000, durationSeconds = 5.0f)
        buf.writeShorts(ShortArray(16000) { 22000 }, 16000)   // 1s of loud speech: "Hey Dashie"
        // ── capture stops here for a mic handoff; NOTHING is written for seconds ──
        // ── capture restarts and writes only a little fresh audio ──
        buf.write(FloatArray(1600))                            // 100ms of fresh silence

        // A detector asking for the last second sees the pre-gap speech as if it were current.
        assertTrue("this is the defect: stale speech presents as recent history",
            energyOfRecentWindow(buf, 16000) > 0.5f)
    }

    @Test
    fun `markDiscontinuity clears the stale history so the same read is silent`() {
        val buf = SharedAudioBuffer(sampleRate = 16000, durationSeconds = 5.0f)
        buf.writeShorts(ShortArray(16000) { 22000 }, 16000)
        buf.markDiscontinuity()                                // ← capture restart
        buf.write(FloatArray(1600))

        assertEquals("a post-handoff re-arm must prime on silence, i.e. behave like a cold start",
            0f, energyOfRecentWindow(buf, 16000), 1e-6f)
    }

    @Test
    fun `markDiscontinuity does NOT rewind the position - held cursors stay valid`() {
        val buf = SharedAudioBuffer(sampleRate = 16000, durationSeconds = 5.0f)
        buf.write(loudSpeech(8000))
        val positionHeldByAConsumer = buf.getCurrentPosition()

        buf.markDiscontinuity()

        assertEquals("rewinding here is what reset() does, and it would invalidate every " +
            "wakeWordBufferPosition and STT read cursor held across the handoff",
            positionHeldByAConsumer, buf.getCurrentPosition())
        buf.write(loudSpeech(1600))
        assertTrue("positions must stay monotonic",
            buf.getCurrentPosition() > positionHeldByAConsumer)
    }

    @Test
    fun `reset by contrast DOES rewind - the distinction the two KDocs turn on`() {
        val buf = SharedAudioBuffer(sampleRate = 16000, durationSeconds = 5.0f)
        buf.write(loudSpeech(8000))
        assertNotEquals(0L, buf.getCurrentPosition())
        buf.reset()
        assertEquals("reset is the mid-turn-unsafe one; markDiscontinuity exists because this " +
            "is not what a capture restart wants", 0L, buf.getCurrentPosition())
    }

    @Test
    fun `audio written after the discontinuity is readable normally`() {
        val buf = SharedAudioBuffer(sampleRate = 16000, durationSeconds = 5.0f)
        buf.writeShorts(ShortArray(16000) { 22000 }, 16000)
        buf.markDiscontinuity()
        val from = buf.getCurrentPosition()
        buf.write(loudSpeech(1600))

        val fresh = buf.readFrom(from, 1600)
        assertEquals(1600, fresh.size)
        assertTrue("clearing history must not deafen the buffer to NEW audio",
            fresh.all { kotlin.math.abs(it - 0.7f) < 1e-6f })
    }
}

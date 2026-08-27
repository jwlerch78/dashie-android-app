package com.dashieapp.Dashie.wakeword

import com.dashieapp.Dashie.wakeword.edgeimpulse.EdgeImpulseConfig
import com.dashieapp.Dashie.wakeword.microwakeword.MicroWakeWordRearmPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [MicroWakeWordRearmPolicy.startCursor] — the arithmetic behind the 2026-08-23 re-arm-race fix.
 *
 * ## Why these vectors and not "does the fix work"
 *
 * The defect (T s44 cont.5: 0/3 wake after a failed turn vs 5/5 from idle, identical clip) was
 * invisible to every existing test because the losing line was a single unconditional
 * `readCursor = getCurrentPosition()`. There is nothing to assert about that line in isolation;
 * it only misbehaves in relation to audio that arrived while the detector was stopped. So these
 * vectors pin the RELATION — how far back a re-arm may reach, and the three separate ceilings on
 * it — rather than a value.
 *
 * Each bound below is a distinct way to reintroduce a defect, which is why each gets its own case
 * instead of one happy-path assertion:
 *  - drop the `cursorAtStop` floor → one utterance can fire twice
 *  - drop the back-fill floor → an arbitrarily long reach back into the turn itself
 *  - drop the ring floor → `SharedAudioBuffer.readFrom` silently re-bases the request while the
 *    caller still advances by the returned length, so the cursor desyncs instead of failing
 *  - drop the cold-start branch → app boot scores whatever was in the room before voice was armed
 */
class MicroWakeWordStartCursorTest {

    private val sampleRate = 16_000L
    private fun seconds(s: Double) = (sampleRate * s).toLong()

    // The SHIPPED values, read from production rather than restated — a test that carries its
    // own copy of the constant stops testing the constant.
    private val backFill = MicroWakeWordRearmPolicy.REARM_BACKFILL_SAMPLES
    private val detectReach = MicroWakeWordRearmPolicy.REARM_DETECT_REACH_SAMPLES
    private val priming = MicroWakeWordRearmPolicy.REARM_PRIMING_SAMPLES

    /** SharedAudioBuffer is constructed at durationSeconds = 5.0f (HaliteVoiceController). */
    private val ring = seconds(5.0)

    private fun cursor(
        currentPosition: Long,
        cursorAtStop: Long,
        hasRunBefore: Boolean = true,
    ) = MicroWakeWordRearmPolicy.startCursor(
        currentPosition = currentPosition,
        cursorAtStop = cursorAtStop,
        hasRunBefore = hasRunBefore,
        backFillSamples = backFill,
        ringCapacity = ring,
    )

    @Test
    fun `cold start reads the live head and ignores any stale stop cursor`() {
        val head = seconds(600.0)
        assertEquals(
            "app boot must not reach back into audio captured before voice was armed",
            head,
            cursor(currentPosition = head, cursorAtStop = seconds(1.0), hasRunBefore = false),
        )
    }

    @Test
    fun `re-arm after a long turn is bounded by the back-fill, not by the whole gap`() {
        val head = seconds(600.0)
        val stoppedAt = head - seconds(30.0)   // a 30 s conversation
        assertEquals(
            "a long turn must back-fill the bounded reach, not the whole 30 s gap",
            head - backFill,
            cursor(currentPosition = head, cursorAtStop = stoppedAt),
        )
    }

    @Test
    fun `re-arm after a short turn never re-reads audio already scored`() {
        // The 100 ms credits-error re-arm path: stop and start almost touch, so a naive
        // "head minus 2 s" would re-score the ORIGINAL wake word and could fire twice on it.
        val head = seconds(600.0)
        val stoppedAt = head - seconds(0.1)
        assertEquals(
            "the stop cursor is a hard floor — scored audio is never re-scored",
            stoppedAt,
            cursor(currentPosition = head, cursorAtStop = stoppedAt),
        )
    }

    @Test
    fun `a gap longer than the ring clamps to the oldest sample still in the ring`() {
        // Deliberately set the back-fill wider than the ring to prove the ring floor is a real,
        // independent bound rather than something the 2 s constant happens to satisfy today.
        val head = seconds(600.0)
        val result = MicroWakeWordRearmPolicy.startCursor(
            currentPosition = head,
            cursorAtStop = seconds(10.0),      // long overwritten
            hasRunBefore = true,
            backFillSamples = seconds(9.0),    // wider than the 5 s ring
            ringCapacity = ring,
        )
        assertEquals(
            "never hand the buffer a position it has already overwritten",
            head - ring,
            result,
        )
    }

    @Test
    fun `never returns a negative position early in the buffer's life`() {
        // First seconds after capture starts: head is smaller than the back-fill reach.
        val head = seconds(0.4)
        val result = cursor(currentPosition = head, cursorAtStop = 0L)
        assertTrue("cursor must not go negative, got $result", result >= 0L)
        assertEquals(0L, result)
    }

    @Test
    fun `never returns a position ahead of the write head`() {
        // stop() records the cursor a beat before the loop's final read, and a redundant stop()
        // can record it again later — so cursorAtStop can legitimately sit at or past the head.
        val head = seconds(600.0)
        val result = cursor(currentPosition = head, cursorAtStop = head + seconds(1.0))
        assertEquals("a stop cursor past the head must clamp to the head", head, result)
    }

    @Test
    fun `the defect case - a wake completing just before re-arm is now inside the read range`() {
        // John's sequence: wake -> garble -> turn FAILS -> he says "Hey Dashie" again while the
        // failure notice is still up. handleNoSpeech re-arms AT the IDLE transition, by which
        // point the second wake word is already complete in the buffer.
        val head = seconds(600.0)
        val wakeWordStarted = head - seconds(0.9)          // "Hey Dashie" ~0.9 s, ending at head
        val stoppedAt = head - seconds(7.0)                // detection off since the turn began

        val newCursor = cursor(currentPosition = head, cursorAtStop = stoppedAt)

        assertTrue(
            "the re-armed cursor must sit BEFORE the wake word so it can be scored " +
                "(cursor=$newCursor wakeStart=$wakeWordStarted)",
            newCursor < wakeWordStarted,
        )
        // Negative control: the pre-fix behaviour — an unconditional live head — sits AFTER the
        // whole wake word, which is exactly why MWW could never corroborate EI's 100% fire.
        assertTrue(
            "the pre-fix cursor must fail this same check, or the test proves nothing",
            head > wakeWordStarted,
        )
    }

    @Test
    fun `the read back-fill is exactly the detection reach plus the priming lead-in`() {
        // Read reach and DETECTION reach are deliberately different: everything before
        // detectFromPosition is consumed to prime the streaming model but cannot fire. If these
        // two ever collapse into one number, either the priming disappears (MWW blind to the
        // first 150 ms EI can see) or the priming becomes reportable (MWW seeing 300 ms EI
        // cannot) — the two failures this whole fix is bracketing.
        val head = seconds(600.0)
        val readReach = head - cursor(currentPosition = head, cursorAtStop = 0L)
        assertEquals(detectReach + priming, readReach)
        assertTrue("the priming lead-in must be non-zero", priming > 0L)
    }

    /**
     * 🔴 **THIS ASSERTION HAS NOW FLIPPED TWICE IN ONE DAY, and the history is the point.**
     *
     * v1 required the reach to EQUAL `EdgeImpulseConfig.WINDOW_SIZE_SAMPLES` — capping MWW at what
     * EI could see live. T s44 cont.14 measured 0/5: EI does not reach back at all, so matching
     * only chose which engine was blind.
     *
     * v2 required the reach to EXCEED that window — reach further, and have the gate PULL an EI
     * score for the audio. Measured 0/3, and the diagnostic said why: MWW was firing at 84–99% on
     * the first inference it was allowed to, every run, at an identical offset, while EI put the
     * real wake **near the live head**. A `reset()` artifact, not a wake — and it burned MWW's
     * 1.5 s cooldown so the real wake could not be corroborated when it arrived.
     *
     * ✅ v3, asserted below: **the reach is deliberately SHORT and the PRIMING is long.** The
     * re-arm defect was never that the wake is far in the past; it is that MWW was DEAF when the
     * wake arrived. So the back-fill's job is to make it arrive **warm**, and a wide honoured reach
     * only exposes more unsettled frames as ghosts.
     */
    @Test fun `a re-arm primes for a long time and honours detections over a short one`() {
        val inferenceSamples = 480L   // 3 frames x 10 ms @ 16 kHz = one inference
        val primingInferences = priming / inferenceSamples

        assertTrue(
            "the priming lead-in ($primingInferences inferences) must cover the AGC/noise-reduction " +
                "settle — under ~33 (1 s) and microWakeWord fires reset() ghosts, which burn its " +
                "cooldown and block the real wake. See T s44 cont.14 and the 84-99% constant-offset " +
                "fires that produced this bound.",
            primingInferences >= 33,
        )
        assertTrue(
            "the honoured reach ($detectReach) must stay SHORT — it exists for a wake completing " +
                "just before the re-arm instant, not to mine history. Reaching further only " +
                "exposes unsettled frames.",
            detectReach <= seconds(0.5),
        )
        assertTrue(
            "priming must dominate the back-fill; if the reach ever grows past it, the ghost " +
                "window is back",
            priming > detectReach,
        )
        assertTrue("the whole back-fill must stay inside the 5 s ring", backFill < ring)
    }

    // ── Capture discontinuity (2026-08-24, John's HA-Assist device pass) ──────
    // A mic handoff stops capture, so audio before the restart is either the PREVIOUS turn's
    // speech or the silence markDiscontinuity wrote over it. Neither may be scored as recent.

    @Test
    fun `the read cursor never reaches back past a capture restart`() {
        val now = seconds(100.0)
        val restartedAt = now - seconds(0.5)          // handoff ended 500ms ago
        val result = MicroWakeWordRearmPolicy.startCursor(
            currentPosition = now,
            cursorAtStop = 0L,                         // deliberately permissive
            hasRunBefore = true,
            backFillSamples = backFill,                // would otherwise reach back 1.8s
            ringCapacity = ring,
            discontinuityPosition = restartedAt,
        )
        assertEquals("reading before the restart re-scores the previous turn's wake word — " +
            "the ghost John saw 3 times in 7 turns", restartedAt, result)
    }

    @Test
    fun `an OLD discontinuity does not change the ordinary re-arm at all`() {
        val now = seconds(100.0)
        val longAgo = seconds(1.0)
        assertEquals("every ordinary turn must behave exactly as before",
            cursor(now, cursorAtStop = 0L),
            MicroWakeWordRearmPolicy.startCursor(
                currentPosition = now, cursorAtStop = 0L, hasRunBefore = true,
                backFillSamples = backFill, ringCapacity = ring,
                discontinuityPosition = longAgo))
    }

    @Test
    fun `nothing is honoured until a full priming settle has passed since the restart`() {
        val now = seconds(100.0)
        val restartedAt = now - seconds(0.5)
        val honourFrom = MicroWakeWordRearmPolicy.earliestHonouredPosition(now, restartedAt)

        assertTrue("with only 500ms of live audio since the restart, the reset artifact still " +
            "sits inside the honoured reach — this is why clamping the cursor ALONE would have " +
            "made it worse, not better",
            honourFrom > now)
        assertEquals(restartedAt + priming, honourFrom)
    }

    @Test
    fun `once the settle has passed, honouring returns to the normal reach`() {
        val now = seconds(100.0)
        val restartedAt = now - priming - seconds(1.0)   // settled a second ago
        assertEquals("a settled run must not be permanently handicapped",
            now - detectReach,
            MicroWakeWordRearmPolicy.earliestHonouredPosition(now, restartedAt))
    }

    @Test
    fun `the exact device case - MWW scored 100 percent on the silence and must be ignored`() {
        // Measured: capture restarted, re-arm ~510ms later, MWW hit 100% on all-zero input
        // (rms=0, EI 0%) and BURNED the 1.5s cooldown. The artifact position is what must fall
        // outside the honoured region.
        val now = seconds(50.0)
        val restartedAt = now - seconds(0.51)
        val artifactAt = now - seconds(0.28)             // "@ 280ms before the live head"
        assertTrue("the silence artifact must land in the priming lead-in, where it is dropped " +
            "instead of burning the debounce a real wake needs",
            artifactAt < MicroWakeWordRearmPolicy.earliestHonouredPosition(now, restartedAt))
    }
}

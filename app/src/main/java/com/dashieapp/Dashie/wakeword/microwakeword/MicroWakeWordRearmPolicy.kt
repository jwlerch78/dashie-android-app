package com.dashieapp.Dashie.wakeword.microwakeword

/**
 * What a RE-ARMING microWakeWord run reads, and how much of it it may act on.
 *
 * Extracted from [MicroWakeWordDetector] 2026-08-23 when that file passed its size budget — and
 * this is the right seam rather than an arbitrary one: **it is the policy that has been wrong
 * twice**, both times found on a device (then cont.14 with the legs swapped). It is
 * also the only part of the detector with no dependency on a mic, a native classifier or a live
 * ring buffer, so putting it here is what lets `MicroWakeWordStartCursorTest` assert it at all.
 *
 * The detector keeps the audio loop; this keeps the arithmetic and the reasoning behind it.
 */
object MicroWakeWordRearmPolicy {

    /**
     * Audio consumed on a re-arm purely to PRIME the streaming model — **1.5 s, i.e. almost all of
     * the back-fill.** Never acted on.
     *
     * ## 🔬 This is 1.5 s and not 0.3 s because the device said so, three runs running
     *
     * With a 0.3 s lead-in, microWakeWord fired at **84–99% at the FIRST inference it was allowed
     * to**, every run, at an identical offset. That constancy is the signature of a `reset()`
     * artifact, not of speech: `microFrontend.reset()` clears the noise-reduction and PCAN
     * estimates, and the first frames after it score garbage-high. The diagnostic that settled it:
     * ```
     * DROP: back-filled MWW 96% not corroborated — EI scored 1% below its 35% bar.
     *       COMPARED WINDOW: 2680ms..1680ms before the live head
     * DualEngine: EI fired: 50%          ← 800 ms LATER, on live audio
     * ```
     * EI put the real wake **near the live head**; MWW's "detection" sat 1.7–2.7 s behind it. Two
     * different sounds — and only one of them was a wake word.
     *
     * 🔴 **And the ghost was not merely wasted: it BURNED MWW's 1.5 s cooldown**, so when the real
     * wake arrived 800 ms later MWW could not fire on it, and the gate logged *"EI fired … no MWW
     * corroboration (MWW warm)"*. That is the whole post-fix failure signature, and I introduced it
     * by setting the re-arm settle to zero.
     *
     * ## What that means for the 2026-03-15 guard I removed
     *
     * Its second reason — *"lets the MicroFrontend's AGC/noise reduction settle"* — was **right**,
     * and my n=1 argument against it (a 94% score 240 ms after reset) was reading a **ghost** as
     * evidence of sensitivity. The guard's mistake was never that it existed; it was that it made
     * the user wait ~3 s of LIVE audio for it. Paying it out of back-filled history costs the user
     * nothing and keeps the protection.
     */
    const val REARM_PRIMING_SAMPLES = 24_000L   // 1.5 s @ 16 kHz ≈ 50 inferences of settle

    /**
     * How far back a re-arm honours detections: **0.3 s.**
     *
     * The re-arm defect is not that the wake is far in the past — EI's own live fire places it
     * spanning the re-arm instant. It is that microWakeWord was DEAF when it arrived (a 3 s
     * count-based warmup on a cold restart). So the job of the back-fill is to arrive **warm**, not
     * to reach far; a wide honoured reach only exposes more unsettled frames as ghosts.
     *
     * ⚠️ This was 1.7 s for one build, on the reasoning that a wider reach recovers more wakes. It
     * recovered none and cost a cooldown-burning false fire on every re-arm — see
     * [REARM_PRIMING_SAMPLES]. 0.3 s covers the boundary case (a wake completing just before the
     * re-arm instant) without reaching into the unsettled region.
     */
    const val REARM_DETECT_REACH_SAMPLES = 4800L   // 0.3 s @ 16 kHz

    /** Total history READ on a re-arm: the honoured reach, plus the lead-in that primes it. */
    internal const val REARM_BACKFILL_SAMPLES =
        REARM_DETECT_REACH_SAMPLES + REARM_PRIMING_SAMPLES

    /**
     * Where a starting detection run should begin reading the SharedAudioBuffer.
     *
     * Pure so the clamps can be pinned without a device, a native classifier, or audio — the
     * defect this arithmetic fixes is one nobody could see in a unit test before, and the
     * three bounds below are each a separate way to get it wrong:
     *
     *  - **cold start** (`hasRunBefore = false`) → the live head. Reaching back at app boot
     *    would score whatever happened to be in the room before voice was armed, for no
     *    user-visible benefit — nobody is waiting on a wake at that moment.
     *  - **`cursorAtStop` floor** → never re-read audio this detector already scored. Without
     *    it a short turn (e.g. the 100 ms credits-error re-arm) would re-score the ORIGINAL
     *    wake word and could fire a second time on one utterance.
     *  - **back-fill floor** → the bounded reach, so a long turn back-fills 2 s rather than
     *    the whole gap.
     *  - **ring floor** → never a position the buffer has already overwritten. `readFrom`
     *    silently re-bases such a request to the oldest available sample while the caller
     *    still advances its cursor by the returned length, so an out-of-ring cursor would
     *    desync rather than fail.
     */
    internal fun startCursor(
        currentPosition: Long,
        cursorAtStop: Long,
        hasRunBefore: Boolean,
        backFillSamples: Long,
        ringCapacity: Long,
        /** Oldest sample belonging to the current continuous capture run — see the 4th bound. */
        discontinuityPosition: Long = 0L,
    ): Long {
        if (!hasRunBefore) return currentPosition
        val backFillFloor = currentPosition - backFillSamples
        val ringFloor = currentPosition - ringCapacity
        return maxOf(cursorAtStop, backFillFloor, ringFloor, discontinuityPosition)
            .coerceIn(0L, currentPosition)
    }

    /**
     * The earliest position at which a detection may be ACTED ON — normally the honoured reach,
     * but never sooner than a full priming lead-in after a capture discontinuity.
     *
     * ## 🔴 Why the second bound exists (device-observed 2026-08-24, John's HA-Assist pass)
     *
     * A mic handoff stops capture, so `SharedAudioBuffer.markDiscontinuity` zeroes the stale
     * history that would otherwise be re-scored as recent. That killed the ghost — EI went from
     * 99% to **0%** on the same window, `rms=0` — but it left microWakeWord scoring **100% on the
     * silence**, twice in six turns, which is the [REARM_PRIMING_SAMPLES] reset artifact with an
     * all-zero input instead of stale speech.
     *
     * 🔴 **Harmless-looking and not harmless: it BURNS the 1.5 s cooldown.** That is the exact
     * mechanism this class was rewritten for once already (see [REARM_PRIMING_SAMPLES]) — a ghost
     * consuming the debounce so a REAL wake moments later cannot be corroborated. A user saying
     * "Hey Dashie" within ~1.5 s of a turn ending could be dropped.
     *
     * ⚠️ **And clamping [startCursor] alone would make it WORSE, which is why this is a second
     * bound rather than a one-line fix.** With the cursor floored at the discontinuity, a re-arm
     * ~500 ms after a restart has less than [REARM_PRIMING_SAMPLES] of audio available — so the
     * unsettled frames would land INSIDE the honoured reach instead of in the lead-in that exists
     * to absorb them. The reach must move too.
     *
     * When the discontinuity is old (any ordinary turn — capture has been running for minutes)
     * `discontinuityPosition + priming` is far in the past and this returns the usual reach
     * unchanged, so nothing about normal operation moves.
     */
    internal fun earliestHonouredPosition(
        currentPosition: Long,
        discontinuityPosition: Long,
        reachSamples: Long = REARM_DETECT_REACH_SAMPLES,
        primingSamples: Long = REARM_PRIMING_SAMPLES,
    ): Long = maxOf(currentPosition - reachSamples, discontinuityPosition + primingSamples)
}

package com.dashieapp.Dashie.wakeword

import android.util.Log

/**
 * Measures how much of a wake word microWakeWord can hear THROUGH Dashie's own TTS playback.
 *
 * Extracted from `DualEngineDetector` 2026-08-23 when that file passed its size budget: this is
 * instrumentation, not gating. It decides nothing — it reports a number the gate's barge-in
 * constant is argued from, which is exactly the kind of thing that should be separable from the
 * decision it informs.
 *
 * ## What it is for (2026-08-23: "We should also confirm that MWW can't work during
 * playback")
 *
 * The echo-deaf claim is the load-bearing premise of the whole EI-only barge-in branch — it is
 * why corroboration is dropped exactly where a false fire is most costly — and it had been
 * DOCUMENTED but never MEASURED. It came from one observation ("EI fired 95%/80%, MWW silent"),
 * and MWW being silent is consistent with two very different worlds: it hears nothing through the
 * echo, or it hears something and never crosses its own threshold. Those imply opposite fixes.
 *
 * So rather than a one-off probe run, the measurement rides normal use: track MWW's PEAK score
 * across each playback window and report it when the window ends — and quote it inside the
 * BARGE / BARGE-SUPPRESSED lines, so the answer sits next to the decision it would inform.
 *
 * ## ✅ It answered its question, and it stays anyway
 *
 * Re-run at ≥70% device volume: MWW read 0–2% in 4 of 5 windows, and 23% — over
 * threshold — in the fifth. So the answer is NEITHER of the two worlds above: echo penetration
 * VARIES. See `DualEngineDetector.TTS_BARGE_IN_THRESHOLD` for why that closes the "restore the
 * gate during playback" question in the NEGATIVE rather than opening it. The peak keeps being
 * reported because the variance IS the finding, and a second device or room could move it.
 */
internal class TtsWindowEchoProbe(
    private val isTtsSpeaking: () -> Boolean,
    private val mwwThreshold: () -> Float,
) {
    private val TAG = "DualEngine"

    @Volatile var mwwPeak = 0f
        private set
    @Volatile private var eiPeak = 0f
    @Volatile private var active = false
    @Volatile private var startedAt = 0L

    /** A restart mid-playback must not carry a previous window's peak into the next line. */
    fun reset() {
        active = false
        mwwPeak = 0f
        eiPeak = 0f
    }

    /**
     * Track MWW's score across one TTS playback window and report it when the window closes.
     *
     * Called from MWW's heartbeat, which ticks continuously in both states — that is what makes
     * the window's own edges observable without any hook into the TTS player. The line is emitted
     * ONCE per window, not per tick.
     *
     * 🔴 Reports the peak even when it is ZERO, and says so explicitly. A window that produced no
     * MWW signal at all is the RESULT, not the absence of one — logging only non-zero peaks would
     * make "echo-deaf, as documented" and "the instrument never ran" identical in the log, which
     * is the failure this whole entry is about.
     */
    fun observe(mwwConf: Float, eiHeartbeat: Float) {
        val speaking = isTtsSpeaking()
        if (speaking) {
            if (!active) {
                active = true
                mwwPeak = 0f
                eiPeak = 0f
                startedAt = System.currentTimeMillis()
            }
            if (mwwConf > mwwPeak) mwwPeak = mwwConf
            if (eiHeartbeat > eiPeak) eiPeak = eiHeartbeat
        } else if (active) {
            active = false
            val ms = System.currentTimeMillis() - startedAt
            Log.i(TAG, "ECHO-DEAF: TTS window closed after ${ms}ms — MWW peak " +
                    "${String.format("%.0f", mwwPeak * 100)}% " +
                    "(threshold ${String.format("%.0f", mwwThreshold() * 100)}%), EI peak " +
                    "${String.format("%.0f", eiPeak * 100)}%. " +
                    "MWW peak at/near 0 confirms echo-deafness; a peak approaching its threshold " +
                    "means the AND gate could be restored during playback.")
        }
    }
}

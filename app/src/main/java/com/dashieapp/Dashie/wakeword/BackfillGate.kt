package com.dashieapp.Dashie.wakeword

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import com.dashieapp.Dashie.wakeword.edgeimpulse.EdgeImpulseConfig
import com.dashieapp.Dashie.wakeword.edgeimpulse.EdgeImpulseDetector

/**
 * The RE-ARM path of the dual-engine gate: what happens when microWakeWord fires on audio that
 * predates the re-arm, and EdgeImpulse therefore never classified it live.
 *
 * ## Why it is a separate path at all
 *
 * The ordinary gate waits for two LIVE fires to land within 500 ms of each other. That cannot work
 * here: EI reads only the live head and only moves forward, so a wake finishing more than a second
 * before a re-arm is structurally invisible to it (measured on device — MWW
 * 100%, orphaned; EI 68% ~900 ms later, orphaned in turn). So this path **pulls** EI's opinion of
 * the one window MWW fired on, and judges both engines on the identical audio.
 *
 * ## Why it is its own file
 *
 * Extracted from [DualEngineDetector] 2026-08-23 on the size budget — and it is the right seam
 * twice over: it is a genuinely different gating rule, and it is the rule that has now been wrong
 * three times running, so it belongs next to its own decision function ([BackfillCorroboration])
 * rather than buried in the live gate.
 *
 * Holds no state of its own — everything arrives as an accessor, so it cannot drift out of sync
 * with the gate's shared cooldown.
 */
internal class BackfillGate(
    private val context: Context,
    private val ei: () -> EdgeImpulseDetector?,
    private val buffer: () -> SharedAudioBuffer?,
    private val eiThreshold: () -> Float,
    private val modeName: () -> String,
    private val lastTriggerTime: () -> Long,
    private val stampTrigger: (Long) -> Unit,
    private val triggerCooldownMs: Long,
    private val clearDetectionState: () -> Unit,
    private val onTrigger: (Float, Long) -> Unit,
    /**
     * Hand microWakeWord's cooldown back when this gate DISCARDS the detection that consumed it.
     *
     * Passed in rather than reached for: this class holds no state and knows no detector, and the
     * one thing it must not do is decide *when* a rollback is safe. It reports "discarded"; the
     * detector owns what that costs. Default no-op keeps the existing tests constructing.
     */
    private val onDiscarded: () -> Unit = {},
) {
    private val TAG = "DualEngine"

    private companion object {
        /**
         * The 100 ms pre-roll both detectors rewind `bufferPosition` by before handing off to STT.
         * Added back when scoring the window that ENDS at a detection, so the retro-score sees the
         * wake word rather than the wake word shifted 100 ms earlier.
         */
        const val PRE_ROLL_SAMPLES = 1600L
    }

    /**
     * Decide and act on a back-filled microWakeWord detection.
     *
     * ⚠️ Scoped to back-filled detections only. The ordinary live gate is untouched, so the
     * measured operating point behind `SensitivityMode` (MEDIUM: 0.065 FA/hr over the 61.7 h
     * suite) still describes it. Widening this to every MWW fire would move that number in a
     * direction nobody has measured.
     *
     * Called holding the detector's monitor.
     */
    fun corroborate(mwwConf: Float, bufferPosition: Long) {
        val now = System.currentTimeMillis()
        val scoreAt = bufferPosition + PRE_ROLL_SAMPLES
        val liveHead = buffer()?.getCurrentPosition() ?: 0L
        val eiConf = ei()?.scoreWindowEndingAt(scoreAt)

        val decision = BackfillCorroboration.decide(
            mwwConfidence = mwwConf,
            eiConfidence = eiConf,
            eiThreshold = eiThreshold(),
            sinceLastTriggerMs = now - lastTriggerTime(),
            triggerCooldownMs = triggerCooldownMs,
        )

        when (decision) {
            is BackfillCorroboration.Decision.Debounced ->
                Log.d(TAG, "DEBOUNCE: back-filled MWW ${pct(mwwConf)} suppressed by the shared " +
                        "trigger cooldown (${decision.sinceLastTriggerMs}ms)")

            is BackfillCorroboration.Decision.AudioGone -> {
                Log.w(TAG, "DROP: wake DISCARDED — back-filled MWW ${pct(mwwConf)} could not be " +
                        "corroborated: that audio has rolled out of the ring, so EI cannot be " +
                        "asked about it")
                onDiscarded()
            }

            is BackfillCorroboration.Decision.NotCorroborated -> {
                // 🔬 The offsets are still worth printing — but 🔴 READ THE CLOSING SENTENCE, which
                // was rewritten 2026-08-25 because the one it replaces could not fail.
                //
                // It used to end: "If MWW's fire sits inside that span the engines really disagree;
                // if not, this is a position bug." That test is VACUOUS. `scoreAt` is
                // `bufferPosition + PRE_ROLL_SAMPLES`, and `bufferPosition` is MWW's own cursor
                // MINUS the identical pre-roll — so this span is DEFINED as ending at MWW's fire.
                // The fire is on the closing edge in every possible run, and the criterion returns
                // "the engines really disagree" whatever is true.
                //
                // It was read and acted on exactly once, by T, which is how it was
                // caught: T applied it faithfully and reported the only answer it can give.
                // ⚠️ A diagnostic that cannot come out the other way is not evidence. If a future
                // change makes these offsets independent of MWW's cursor, the old sentence becomes
                // meaningful again — until then, do not reinstate it.
                val endMs = (liveHead - scoreAt) / 16
                val startMs = endMs + EdgeImpulseConfig.WINDOW_SIZE_SAMPLES / 16
                Log.i(TAG, "DROP: back-filled MWW ${pct(mwwConf)} not corroborated — EI scored " +
                        "${pct(decision.eiConfidence)} below its ${pct(decision.eiThreshold)} bar. " +
                        "COMPARED WINDOW: ${startMs}ms..${endMs}ms before the live head. " +
                        "MWW's fire is the span's CLOSING EDGE by construction, so their agreeing " +
                        "proves nothing — compare EI's own live fire position instead.")
                onDiscarded()
            }

            is BackfillCorroboration.Decision.Trigger -> {
                stampTrigger(now)
                Log.i(TAG, "========================================")
                Log.i(TAG, "AND-GATE TRIGGERED! (back-filled) EI=${pct(eiConf ?: 0f)} " +
                        "MWW=${pct(mwwConf)} avg=${pct(decision.avgConfidence)} — corroborated on " +
                        "the SAME audio, not on two live fires")
                Log.i(TAG, "========================================")
                ei()?.let { d ->
                    WakeSignalProbe.emit(
                        context = context,
                        engine = "dual",
                        mode = "${modeName()}-backfill",
                        trigger = true,
                        confidence = decision.avgConfidence,
                        eiConfidence = eiConf,
                        mwwConfidence = mwwConf,
                        rms = d.lastDetectionRms,
                        peak = d.lastDetectionPeak,
                        noiseFloor = d.noiseFloorTracker.floor(now),
                        floorSamples = d.noiseFloorTracker.settledCount(now),
                        bufferPosition = bufferPosition,
                    )
                }
                clearDetectionState()
                onTrigger(decision.avgConfidence, bufferPosition)
            }
        }
    }

    private fun pct(v: Float) = "${String.format("%.0f", v * 100)}%"
}

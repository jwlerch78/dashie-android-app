package com.dashieapp.Dashie.wakeword

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import com.dashieapp.Dashie.microfrontend.MicroFrontend
import com.dashieapp.Dashie.wakeword.edgeimpulse.EdgeImpulseConfig
import com.dashieapp.Dashie.wakeword.edgeimpulse.EdgeImpulseDetector
import com.dashieapp.Dashie.wakeword.microwakeword.MicroWakeWordConfig
import com.dashieapp.Dashie.wakeword.microwakeword.MicroWakeWordDetector
import java.io.File

/**
 * Dual-engine AND-gate wake word detector.
 *
 * Runs both Edge Impulse and microWakeWord simultaneously on the same
 * SharedAudioBuffer. Detection only triggers when BOTH engines agree
 * within a time window, eliminating false positives.
 *
 * The gate is an AND at MEDIUM/LOW; HIGH adds OR escape branches so either engine
 * can trigger alone. See SensitivityMode for the measured ladder and its evidence.
 *
 * Sample collection uses OR-gate: captures when EITHER engine exceeds
 * its collection threshold, gathering training data from both.
 */
class DualEngineDetector(
    private val context: Context,
    private val mwwModelFile: File? = null,
    private val mwwAssetModelPath: String? = null,
    // Dual-engine models with their OWN EI leg (chickadee) pass its bundled
    // asset; null keeps the hey_dashie default inside TFLiteClassifier.
    private val eiAssetPath: String? = null,
    // Per-model sensitivity ladder (WakeWordModel.dualThresholds). Empty map →
    // the legacy hey_dashie SensitivityMode values below.
    private val dualThresholds: Map<com.dashieapp.Dashie.wakeword.models.WakeWordSensitivity,
            com.dashieapp.Dashie.wakeword.models.DualGateThresholds> = emptyMap(),
    // Per-model EI↔MWW agreement window (WakeWordModel.agreementWindowMs).
    // Chickadee's MWW leg peaks ~0.6-0.8s after the EI leg on real voices —
    // at 500ms the gate vetoed 18/27 real field utterances; 800ms passes 19/27
    // for +1 joint FA in 62h of stream audio (field_dual_gate_v01.json /
    // dual-grid-v01, 2026-07-28). hey_dashie stays at 500 (no w0.8 FA data).
    private val agreementWindowMs: Long = 500L
) : WakeWordDetectorInterface {

    private val TAG = "DualEngine"

    private companion object {
        const val NO_ESCAPE = com.dashieapp.Dashie.wakeword.models.DualGateThresholds.NO_ESCAPE
        // EI's own DEFAULT_DETECTION_THRESHOLD, and the only EI-alone operating point
        // with a measured FA rate (0.714/hr, 61.7 h suite — high_rung.py family A).
        const val EI_ONLY_FALLBACK_THRESHOLD = 0.80f

        /**
         * The bar an EI fire must clear to barge in DURING Dashie's own TTS playback.
         *
         * ## Why a separate, higher bar exists here at all
         *
         * Outside playback an EI fire is corroborated by MWW (the AND gate). Inside it, MWW is
         * echo-deaf, so the TTS branch below fires on EI ALONE — and until 2026-08-23 it did so at
         * the ORDINARY threshold, i.e. with the corroboration removed and nothing put in its
         * place. Field result (, ordinary household use): Dashie cut its own sentence off
         * 2.4 s in, on an EI fire of **54%**, and again at **46%**. The same 54% is safe WITH
         * corroboration and unsafe without it.
         *
         * ## 0.70 is a product decision, and the cost is known and accepted
         *
         * John, 2026-08-23: *"up the single engine requirement for barge-in to 70%."*
         *
         * ⚠️ **A GENUINE wake was observed at EI 54%** in the same evidence set. So this bar does
         * not separate true from false — it trades *some real barge-ins during playback* for the
         * cut-offs. That trade was made knowingly, not derived; do not "correct" it toward recall
         * without a product decision.
         *
         * 📌 **The number is provisional on n=5 and is meant to be revisited on evidence.** That
         * is why a fire in the 46–70% band still emits `BARGE-SUPPRESSED:` below rather than
         * vanishing — a silent suppression would blind exactly the measurement that should replace
         * this constant. Grep both markers to rebuild the full distribution.
         *
         * ## ✅ The "is this bar transitional?" question is now CLOSED — it is not (2026-08-23)
         *
         * This bar existed because MWW is echo-deaf during playback, and that premise had been
         * documented but never measured. It has now been measured, at ≥70 % device volume so the
         * under-powered confound is discharged (3.5× the earlier playback level):
         * **MWW read 0–2 % in 4 of 5 playback windows — but one rig-verified window reached 23 %,
         * over its 20 % threshold.**
         *
         * 🎯 **That middle result is the ruling, and it is the opposite of what a cleaner answer
         * would have given.** Echo-deafness is **real but not total**, so MWW's echo penetration
         * has real variance — meaning a restored AND gate during playback would work occasionally
         * and fail unpredictably. **An unreliable gate is arguably worse than a consistent
         * EI-only bar**, because a user cannot learn its behaviour. So the EI-only branch stays,
         * and it stays *for a measured reason* rather than for the undocumented assumption it
         * started as. (Honest limits: n=5, one device, one speaker, one room, synthetic stimulus.)
         */
        const val TTS_BARGE_IN_THRESHOLD = 0.70f
    }

    override val engineName: String = "DualEngine (EI+MWW)"

    // Sub-detectors
    private var eiDetector: EdgeImpulseDetector? = null
    private var mwwDetector: MicroWakeWordDetector? = null

    /**
     * Sensitivity mode determines which engines run and at what thresholds.
     *
     * Re-tuned 2026-07-31 against the first full (EI × MWW) false-accept sweep over
     * the 61.7 h adversarial suite. Until then the gate's FA had only ever been
     * measured at EI@0.80, so nobody knew what loosening EI actually cost — and the
     * answer is: almost nothing. HIGH was re-derived 2026-08-01 (below). Recall is
     * field positives (P-holdout n=81 / P-field-all n=1352), FA is distinct fires
     * over the same 61.7 h:
     *
     * - HIGH:   MEDIUM ∨ EI@0.80 ∨ MWW@0.20   93.8% / 96.7% at 1.800 FA/hr
     * - MEDIUM: EI 4.0 @ 0.35 AND MWW @ 0.20     84.0% / 92.8% at 0.065 FA/hr
     * - LOW:    EI 4.0 @ 0.80 AND MWW @ 0.30     72.8% / 86.8% at 0.000 FA/hr
     *
     * MEDIUM was EI@0.80 ∧ MWW@0.30 (now LOW) and LOW was EI@0.90 ∧ MWW@0.50. The new
     * MEDIUM buys +11.2 points of holdout recall and +6.0 on the wider field set for
     * roughly one false wake per 15 hours of adversarial audio. LOW is unchanged in
     * behaviour — it is exactly the old MEDIUM — so anyone on a FP-plagued device who
     * drops a step lands on the gate that shipped before, at a measured zero FA.
     *
     * HIGH (re-derived 2026-08-01, phoneme-ctc/high_rung.py, high-rung-frontier.json).
     * It was `EI only @0.60`: 82.7% / 94.7% at 2.984 FA/hr — a rate nothing had ever
     * measured, because gate_frontier.py sweeps `EI ∧ MWW`, `pCTC ∧ MWW` and `MWW`
     * alone but had no EI-alone family. That rung was incoherent three ways:
     *
     *   1. It scored BELOW MEDIUM on P-holdout (82.7% vs 84.0%), so stepping UP the
     *      ladder was a recall DOWNGRADE. An EI-only rung cannot be a superset of an
     *      `EI ∧ MWW` rung unless its threshold is <= MEDIUM's, and 0.60 > 0.35.
     *   2. Device-level it was 8 better / 5 worse than MEDIUM across 34 device models
     *      (sign test p = 0.58) — indistinguishable from MEDIUM, while making five
     *      devices WORSE.
     *   3. Worst, it regressed the exact case it exists for. On SM-X110 (the
     *      Asian-accented household where the gate underperforms) MEDIUM reaches
     *      36.4% and old HIGH reached 18.2%: dropping the MWW leg cost more than
     *      loosening EI gained, because on that device EI is the leg that fails.
     *
     * The fix is structural, not a better constant. HIGH is now MEDIUM's gate plus
     * two OR escape branches — either engine, alone, at its own high-confidence bar —
     * so it is a superset of MEDIUM by construction and the ladder is monotone in
     * recall AND false accepts for the first time. Against the rung it replaces it is
     * better on every axis at once: +11.1 P-holdout, +2.0 P-field-all, and 40% FEWER
     * false accepts (1.800 vs 2.984/hr). Device-level: 12 better / 0 worse (p < 0.001),
     * and SM-X110 goes 36.4% -> 72.7%, which is exactly MWW-alone's ceiling on that
     * device — i.e. the MWW@0.20 branch hands back precisely what the AND gate was
     * discarding there.
     *
     * Why 0.80/0.20 and not tighter: the frontier's knee. MWW's positive scores are
     * bimodal, so an MWW escape anywhere in 0.50-0.95 buys nothing over MEDIUM
     * (flat 85.2% / 93.4%); it only pays below 0.30. Going the other way,
     * EI@0.70 ∨ MWW@0.70 costs 1.9 FA/hr for 88.9% / 96.3% — strictly worse than
     * this rung on both sets at more FA.
     *
     * 1.8 FA/hr is a lot in absolute terms — roughly one false wake per 33 minutes of
     * adversarial TV/meeting/VoIP audio. That is the price of an escape hatch and it
     * is opt-in; MEDIUM remains the default at 0.065. It is also 40% cheaper than what
     * this replaces, so there is no axis on which the old rung was preferable.
     *
     * Caveat on the holdout figures: P-holdout is 81 clips from 7 devices in a single
     * household, so its recall numbers are the least robust here. P-field-all (1352
     * clips, 34 device models) is the one to trust, and it agrees on the direction;
     * the device-level sign tests above are on P-field-all, never clip-level.
     *
     * NOT transferable to Chickadee: it has its own dualThresholds map from its own
     * benchmark, and no FA sweep has ever been run against it. Its escape branches
     * stay at NO_ESCAPE until that exists.
     *
     * Evidence: wake-word-training/benchmark-results/phoneme-ctc-phase0/
     *   COMBINED_GRID_FINDING.md ("all three gates at matched false accepts"),
     *   gate-frontier-dashie-emp.json, high-rung-frontier.json. Reproduce with
     *   phoneme-ctc/gate_frontier.py and phoneme-ctc/high_rung.py.
     */
    enum class SensitivityMode(
        val eiThreshold: Float,
        val mwwThreshold: Float,
        val eiOnly: Boolean,
        val eiEscape: Float = NO_ESCAPE,
        val mwwEscape: Float = NO_ESCAPE
    ) {
        // MEDIUM's gate, plus either engine alone at its own bar.
        HIGH(0.35f, 0.20f, false, eiEscape = 0.80f, mwwEscape = 0.20f),
        MEDIUM(0.35f, 0.20f, false),  // Dual AND-gate (EI 0.35 ∧ MWW 0.20) — default
        LOW(0.80f, 0.30f, false)      // Dual AND-gate — the pre-2026-07-31 MEDIUM
    }

    var sensitivityMode: SensitivityMode = SensitivityMode.MEDIUM

    // Effective thresholds: per-model ladder when provided (chickadee), else
    // the legacy SensitivityMode enum values (hey_dashie).
    private val modelThresholds
        get() = dualThresholds[com.dashieapp.Dashie.wakeword.models.WakeWordSensitivity
            .fromString(sensitivityMode.name)]
    private val eiThreshold get() = modelThresholds?.eiThreshold ?: sensitivityMode.eiThreshold
    private val mwwThreshold get() = modelThresholds?.mwwGate ?: sensitivityMode.mwwThreshold
    private val eiOnlyMode get() = forcedEiOnly || (modelThresholds?.eiOnly ?: sensitivityMode.eiOnly)

    // OR escape branches (see SensitivityMode). A model with its own ladder that has
    // never had an FA sweep — chickadee — inherits NO_ESCAPE and keeps a pure AND gate.
    private val eiEscape get() = modelThresholds?.eiEscape ?: sensitivityMode.eiEscape
    private val mwwEscape get() = modelThresholds?.mwwEscape ?: sensitivityMode.mwwEscape

    // Set when MicroFrontend is unavailable, so there is no MWW leg to gate with.
    // Distinct from a mode that CHOSE eiOnly: before 2026-08-01 this was expressed by
    // reassigning sensitivityMode = HIGH, which silently worked only because HIGH
    // happened to be EI-only. HIGH now needs its MWW leg, so the fallback must carry
    // its own flag or it would demand the very engine that is missing.
    private var forcedEiOnly = false

    // Time window for agreement (both must detect within this period) — per-model,
    // from the constructor (WakeWordModel.agreementWindowMs)
    private val AGREEMENT_WINDOW_MS = agreementWindowMs

    // Tracking detections from each engine
    private var eiConfidence: Float? = null
    private var eiBufferPosition: Long? = null
    private var eiDetectionTime: Long? = null

    private var mwwConfidence: Float? = null
    private var mwwBufferPosition: Long? = null
    private var mwwDetectionTime: Long? = null

    // EI heartbeat confidence for dual-mode meter (min of both)
    @Volatile private var lastEiHeartbeat = 0f

    /** TTS-window echo measurement — see [TtsWindowEchoProbe]. */
    private val echoProbe = TtsWindowEchoProbe(
        isTtsSpeaking = { isTtsSpeaking?.invoke() == true },
        mwwThreshold = { mwwThreshold },
    )

    // Cooldown after ANY trigger — the AND gate and both escape branches share it.
    // HIGH has three independent paths to onWakeWordDetected; each sub-detector holds
    // only its OWN 1.5 s cooldown, so without a shared guard two paths firing on the
    // same audio would both trigger. Measured cost of the coupling is small (the
    // 61.7 h sweep puts HIGH at 1.800 FA/hr shared vs 1.881 split) but the duplicate
    // wake is user-visible, so the guard is shared.
    private var lastTriggerTime = 0L
    private val TRIGGER_COOLDOWN_MS = 2000L

    // Which trigger the gate/escape DEBOUNCE lines have already been emitted for.
    private var lastGateDebounceLoggedFor = -1L

    /** The re-arm path — see [BackfillGate]. Accessors, so it shares this gate's cooldown. */
    private val backfillGate = BackfillGate(
        context = context,
        ei = { eiDetector },
        buffer = { sharedBuffer },
        eiThreshold = { eiThreshold },
        modeName = { sensitivityMode.name },
        lastTriggerTime = { lastTriggerTime },
        stampTrigger = { lastTriggerTime = it },
        triggerCooldownMs = TRIGGER_COOLDOWN_MS,
        clearDetectionState = { clearDetectionState() },
        onTrigger = { conf, pos -> onWakeWordDetected?.invoke(conf, pos) },
        // A discarded back-fill must not blind MWW for the next 1.5 s — that is how one wake word
        // heard by BOTH engines ends up gated by neither (5/5). Provider, not a
        // captured reference: the detector is rebuilt on re-init.
        onDiscarded = { mwwDetector?.rollbackDetectionStamp() },
    )

    // State
    private var isInitialized = false
    private var sharedBuffer: SharedAudioBuffer? = null

    // Sample collection (shared between both engines)
    var sampleCollector: LiveSampleCollector? = null

    // Cascade barge-in (2026-07-27): while Dashie's TTS is playing, MWW can't hear
    // the wake word through the speaker echo and vetoes every interrupt (field:
    // EI fired 95%/80% on chickadee barge-in attempts, MWW silent). When this
    // provider reports TTS playback, an EI fire triggers DIRECTLY (EI-only) —
    // exposure is bounded to the few seconds of playback; EI@0.80 measured
    // ≤1.5 FA/hr worst-tier. Injected as a provider, never a state snapshot.
    var isTtsSpeaking: (() -> Boolean)? = null

    // Callbacks
    override var onWakeWordDetected: ((confidence: Float, bufferPosition: Long) -> Unit)? = null
    override var onHeartbeat: ((confidence: Float, volume: Float) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    override fun setSharedBuffer(buffer: SharedAudioBuffer) {
        this.sharedBuffer = buffer
    }

    override fun initialize(): Boolean {
        try {
            Log.i(TAG, "========================================")
            Log.i(TAG, "Initializing dual-engine detector" +
                    (if (dualThresholds.isEmpty()) " (Hey Dashie)" else " (per-model ladder)"))
            Log.i(TAG, "Sensitivity: ${sensitivityMode.name} " +
                    "(${describeGate()})")
            Log.i(TAG, "Agreement window: ${AGREEMENT_WINDOW_MS}ms")
            Log.i(TAG, "========================================")

            // Set EI threshold
            EdgeImpulseConfig.DETECTION_THRESHOLD = minOf(eiThreshold, eiEscape)

            // Create Edge Impulse detector (always needed)
            eiDetector = EdgeImpulseDetector(context, eiAssetPath = eiAssetPath).apply {
                sampleCollector = this@DualEngineDetector.sampleCollector
            }

            // Create MWW detector (needed for medium/low, and for sample collection)
            if (MicroFrontend.isAvailable()) {
                // The sub-detector only emits above its cutoff, so the cutoff must
                // be the LOWEST score either path cares about — otherwise an escape
                // branch set below the gate threshold would silently never fire.
                MicroWakeWordConfig.probabilityCutoff =
                    if (eiOnlyMode) 0.50f else minOf(mwwThreshold, mwwEscape)
                mwwDetector = MicroWakeWordDetector(
                    context = context,
                    modelFile = mwwModelFile,
                    assetModelPath = mwwAssetModelPath
                ).apply {
                    sampleCollector = this@DualEngineDetector.sampleCollector
                    // Capture stays EI-focused: the EI leg collects at its 0.50 floor
                    // (targeting the 0.80 operating point) so we harvest EI's behavior for
                    // the 4.0.1 retrain. Let MWW veto triggers, not steer collection.
                    collectSamples = false
                }
            } else if (!eiOnlyMode) {
                // DROP: no MWW leg exists, so neither the AND gate nor HIGH's MWW
                // escape branch can run. Fall back to EI alone at 0.80 — EI's own
                // default operating point and the only EI-alone threshold with a
                // measured false-accept rate (0.714/hr over the 61.7 h suite,
                // 76.5% / 91.5%). Loud, because the user's chosen rung is not what
                // they are getting.
                Log.w(TAG, "DROP: MicroFrontend unavailable — no MWW leg. " +
                        "${sensitivityMode.name} degraded to EI-only@$EI_ONLY_FALLBACK_THRESHOLD " +
                        "(measured 0.714 FA/hr, 76.5%/91.5%)")
                forcedEiOnly = true
                EdgeImpulseConfig.DETECTION_THRESHOLD = EI_ONLY_FALLBACK_THRESHOLD
            }

            // Wire both to shared buffer
            eiDetector!!.setSharedBuffer(sharedBuffer!!)
            mwwDetector?.setSharedBuffer(sharedBuffer!!)

            // Wake-signal probe: in AND-gate mode the GATE decides whether this device acts, so
            // the legs emit diagnostic (trig=0) lines and checkAndGate() emits the trigger line.
            // In EI-only mode the EI leg IS the trigger and keeps its authority.
            eiDetector!!.isTriggerAuthority = eiOnlyMode
            eiDetector!!.probeMode = sensitivityMode.name
            mwwDetector?.isTriggerAuthority = false
            mwwDetector?.probeMode = sensitivityMode.name

            // Wire EI detection callback
            eiDetector!!.onWakeWordDetected = { confidence, bufferPosition ->
                Log.i(TAG, "EI fired: ${String.format("%.0f", confidence * 100)}%")
                if (eiOnlyMode) {
                    // No MWW leg to gate with (MicroFrontend missing, or a model
                    // whose ladder declares eiOnly): EI IS the trigger. Its own
                    // 1.5 s cooldown is the only one in play; stamp the shared clock
                    // so nothing else double-fires behind it.
                    synchronized(this) { lastTriggerTime = System.currentTimeMillis() }
                    onWakeWordDetected?.invoke(confidence, bufferPosition)
                } else if (isTtsSpeaking?.invoke() == true) {
                    // TTS playback window: MWW is echo-deaf, so EI fire IS the barge-in — but it
                    // must clear TTS_BARGE_IN_THRESHOLD, because this is the one path with no
                    // corroboration behind it. See that constant for the evidence and the trade.
                    if (confidence < TTS_BARGE_IN_THRESHOLD) {
                        // Loud on purpose. This branch is where the distribution that should
                        // REPLACE the constant comes from, so a suppressed fire must be as
                        // grep-able as a fired one — `BARGE-SUPPRESSED:` alongside `BARGE:`.
                        // Deliberately NOT stamping lastTriggerTime: nothing was triggered, and
                        // stamping would suppress a legitimate wake arriving behind it.
                        Log.i(TAG, "BARGE-SUPPRESSED: ${String.format("%.0f", confidence * 100)}% " +
                                "below the ${String.format("%.0f", TTS_BARGE_IN_THRESHOLD * 100)}% " +
                                "TTS-window bar (MWW peak this window " +
                                "${String.format("%.0f", echoProbe.mwwPeak * 100)}%) — not barging in")
                        // NOT falling through to checkAndGate() on purpose — and as of
                        // 2026-08-23 that is a MEASURED choice, not a placeholder. The gate
                        // would be the right home for these if MWW could hear through the echo
                        // RELIABLY; measured at ≥70% device volume and found
                        // 0–2% in 4 of 5 windows and 23% in the fifth. Variance, not deafness —
                        // so a fall-through would corroborate sometimes and veto sometimes, on
                        // the same utterance at the same volume. See TTS_BARGE_IN_THRESHOLD.
                    } else {
                        Log.i(TAG, "BARGE: EI-only trigger during TTS playback " +
                                "(${String.format("%.0f", confidence * 100)}%, MWW peak this " +
                                "window ${String.format("%.0f", echoProbe.mwwPeak * 100)}%)")
                        // Deliberately NOT gated on the shared cooldown — barge-in must
                        // stay responsive — but it stamps the clock so the AND gate and
                        // the escape branches don't fire a second wake behind it.
                        synchronized(this) { lastTriggerTime = System.currentTimeMillis() }
                        onWakeWordDetected?.invoke(confidence, bufferPosition)
                    }
                } else if (confidence >= eiEscape) {
                    // OR escape branch: EI alone is confident enough that MWW's
                    // corroboration is not required. See SensitivityMode.
                    synchronized(this) {
                        fireEscape("EI", confidence, bufferPosition,
                                   eiConf = confidence, mwwConf = null)
                    }
                } else {
                    synchronized(this) {
                        eiConfidence = confidence
                        eiBufferPosition = bufferPosition
                        eiDetectionTime = System.currentTimeMillis()
                        checkAndGate()
                    }
                }
            }

            // Wire MWW detection callback (for AND-gate + sample collection)
            mwwDetector?.onWakeWordDetected = { confidence, bufferPosition ->
                Log.i(TAG, "MWW fired: ${String.format("%.0f", confidence * 100)}%")
                // Read INSIDE the callback, on MWW's own thread, immediately after it was set.
                val fromBackfill = mwwDetector?.lastDetectionFromBackfill == true
                if (!eiOnlyMode && fromBackfill && confidence < mwwEscape) {
                    synchronized(this) { backfillGate.corroborate(confidence, bufferPosition) }
                } else if (!eiOnlyMode) {
                    synchronized(this) {
                        if (confidence >= mwwEscape) {
                            // OR escape branch. This is the one an EI-side hatch
                            // structurally cannot provide: where EI is the failing
                            // leg (SM-X110), no EI threshold recovers the utterance.
                            fireEscape("MWW", confidence, bufferPosition,
                                       eiConf = null, mwwConf = confidence)
                        } else {
                            mwwConfidence = confidence
                            mwwBufferPosition = bufferPosition
                            mwwDetectionTime = System.currentTimeMillis()
                            checkAndGate()
                        }
                    }
                }
            }

            // Forward heartbeats — in dual mode show min(EI, MWW), in EI-only show EI
            eiDetector!!.onHeartbeat = { eiConf, volume ->
                if (eiOnlyMode) {
                    onHeartbeat?.invoke(eiConf, volume)
                }
                // Store EI heartbeat confidence for dual-mode meter
                lastEiHeartbeat = eiConf
                // Swept from BOTH legs so an unpaired detection is still reported when the other
                // engine is absent or wedged — the case where the marker matters most.
                sweepUnpaired()
            }
            mwwDetector?.onHeartbeat = { mwwConf, volume ->
                echoProbe.observe(mwwConf, lastEiHeartbeat)
                sweepUnpaired()
                if (!eiOnlyMode) {
                    // Dual mode: report min of both engines
                    val minConf = minOf(lastEiHeartbeat, mwwConf)
                    onHeartbeat?.invoke(minConf, volume)
                }
            }

            // Initialize
            val eiOk = eiDetector!!.initialize()
            val mwwOk = mwwDetector?.initialize() ?: true

            if (!eiOk || !mwwOk) {
                Log.e(TAG, "Failed to initialize: EI=$eiOk, MWW=$mwwOk")
                onError?.invoke("Detector init failed: EI=$eiOk, MWW=$mwwOk")
                return false
            }

            isInitialized = true
            Log.i(TAG, "Dual engine detector initialized successfully")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize dual engine detector", e)
            onError?.invoke("Dual engine init error: ${e.message}")
            return false
        }
    }

    override fun start() {
        if (!isInitialized) {
            Log.e(TAG, "Cannot start — not initialized")
            return
        }

        Log.i(TAG, "========================================")
        Log.i(TAG, "Starting detection — ${sensitivityMode.name} sensitivity " +
                "(${describeGate()})")
        Log.i(TAG, "========================================")

        // Clear tracking state
        clearDetectionState()
        lastEiHeartbeat = 0f
        // A restart mid-playback must not carry a previous window's peak into the next line.
        echoProbe.reset()

        eiDetector?.start()
        mwwDetector?.start()  // Always start MWW for sample collection even in EI-only mode
    }

    override fun stop() {
        eiDetector?.stop()
        mwwDetector?.stop()
    }

    override fun isDetecting(): Boolean {
        return (eiDetector?.isDetecting() == true) || (mwwDetector?.isDetecting() == true)
    }

    override fun setThreshold(threshold: Float) {
        // Threshold is controlled by sensitivity mode, not directly settable
        Log.d(TAG, "setThreshold ignored — sensitivity mode controls thresholds")
    }

    override fun getThreshold(): Float = mwwThreshold

    override fun getMemoryStatus(): String {
        val eiStatus = eiDetector?.getMemoryStatus() ?: "null"
        val mwwStatus = mwwDetector?.getMemoryStatus() ?: "null"
        return "engine=dual ei=[$eiStatus] mww=[$mwwStatus]"
    }

    override fun close() {
        stop()
        eiDetector?.close()
        mwwDetector?.close()
        eiDetector = null
        mwwDetector = null
        isInitialized = false
        Log.d(TAG, "Dual engine detector closed")
    }

    /**
     * AND-gate: triggers only when both engines have detected within the agreement window.
     * Must be called while holding synchronized(this).
     */
    /** The gate this detector is actually running, for logs. */
    private fun describeGate(): String {
        if (eiOnlyMode) return "EI@${EdgeImpulseConfig.DETECTION_THRESHOLD} only"
        val sb = StringBuilder("EI@$eiThreshold AND MWW@$mwwThreshold")
        if (eiEscape < NO_ESCAPE) sb.append(" OR EI@$eiEscape")
        if (mwwEscape < NO_ESCAPE) sb.append(" OR MWW@$mwwEscape")
        return sb.toString()
    }

    private fun fireEscape(
        leg: String,
        confidence: Float,
        bufferPosition: Long,
        eiConf: Float?,
        mwwConf: Float?
    ) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < TRIGGER_COOLDOWN_MS) {
            // `DEBOUNCE:`, not `DROP:` — same-utterance suppression (another path already
            // triggered), matching the vocabulary split introduced with the re-arm fix.
            if (lastGateDebounceLoggedFor != lastTriggerTime) {
                lastGateDebounceLoggedFor = lastTriggerTime
                Log.d(TAG, "DEBOUNCE: $leg escape suppressed by the shared trigger cooldown for " +
                        "${TRIGGER_COOLDOWN_MS}ms — same utterance " +
                        "(first re-cross at ${now - lastTriggerTime}ms)")
            }
            return
        }
        lastTriggerTime = now

        Log.i(TAG, "========================================")
        Log.i(TAG, "ESCAPE TRIGGERED! $leg alone at " +
                "${String.format("%.0f", confidence * 100)}% " +
                "(${sensitivityMode.name}: no ${if (leg == "EI") "MWW" else "EI"} " +
                "corroboration required)")
        Log.i(TAG, "========================================")

        eiDetector?.let { ei ->
            WakeSignalProbe.emit(
                context = context,
                engine = "dual",
                mode = "${sensitivityMode.name}-escape-$leg",
                trigger = true,
                confidence = confidence,
                eiConfidence = eiConf,
                mwwConfidence = mwwConf,
                rms = ei.lastDetectionRms,
                peak = ei.lastDetectionPeak,
                noiseFloor = ei.noiseFloorTracker.floor(now),
                floorSamples = ei.noiseFloorTracker.settledCount(now),
                bufferPosition = bufferPosition,
            )
        }

        clearDetectionState()
        onWakeWordDetected?.invoke(confidence, bufferPosition)
    }

    private fun checkAndGate() {
        val now = System.currentTimeMillis()

        // Cooldown check — shared with the escape branches. `DEBOUNCE:`, not `DROP:`: this is one
        // utterance already triggered through another path, not a wake being lost. Keeping the
        // two vocabularies apart is what lets a `DROP:` grep stay a defect signal.
        if (now - lastTriggerTime < TRIGGER_COOLDOWN_MS) {
            // Once per cooldown episode — see the per-detector DEBOUNCE lines for why.
            if (lastGateDebounceLoggedFor != lastTriggerTime) {
                lastGateDebounceLoggedFor = lastTriggerTime
                Log.d(TAG, "DEBOUNCE: gate suppressed by the shared trigger cooldown for " +
                        "${TRIGGER_COOLDOWN_MS}ms — same utterance " +
                        "(first re-cross at ${now - lastTriggerTime}ms)")
            }
            return
        }

        // A half-filled gate is the NORMAL state for 20–180 ms while the slower leg catches up,
        // so nothing is logged here — the discard is only real once the partner can no longer
        // arrive, which sweepUnpaired() below reports exactly once.
        val eiTime = eiDetectionTime ?: return
        val mwwTime = mwwDetectionTime ?: return
        val eiConf = eiConfidence ?: return
        val mwwConf = mwwConfidence ?: return

        // Both must have detected within the agreement window
        if (kotlin.math.abs(eiTime - mwwTime) > AGREEMENT_WINDOW_MS) {
            // Expire stale detections — loudly, because a detection expiring here is a wake word
            // that BOTH engines heard and the gate still threw away, which is the most surprising
            // discard in this class and used to leave no trace at all.
            if (now - eiTime > AGREEMENT_WINDOW_MS) {
                Log.w(TAG, "DROP: EI ${String.format("%.0f", eiConf * 100)}% expired unpaired — " +
                        "MWW's ${String.format("%.0f", mwwConf * 100)}% landed " +
                        "${kotlin.math.abs(eiTime - mwwTime)}ms away, outside the " +
                        "${AGREEMENT_WINDOW_MS}ms agreement window")
                eiConfidence = null; eiDetectionTime = null
            }
            if (now - mwwTime > AGREEMENT_WINDOW_MS) {
                Log.w(TAG, "DROP: MWW ${String.format("%.0f", mwwConf * 100)}% expired unpaired — " +
                        "EI's ${String.format("%.0f", eiConf * 100)}% landed " +
                        "${kotlin.math.abs(eiTime - mwwTime)}ms away, outside the " +
                        "${AGREEMENT_WINDOW_MS}ms agreement window")
                mwwConfidence = null; mwwDetectionTime = null
            }
            return
        }

        // AND-gate triggered!
        lastTriggerTime = now
        val avgConfidence = (eiConf + mwwConf) / 2f
        // Use the earlier buffer position (captures more audio context)
        val bufferPosition = minOf(eiBufferPosition ?: Long.MAX_VALUE, mwwBufferPosition ?: Long.MAX_VALUE)

        Log.i(TAG, "========================================")
        Log.i(TAG, "AND-GATE TRIGGERED! EI=${String.format("%.0f", eiConf * 100)}% " +
                "MWW=${String.format("%.0f", mwwConf * 100)}% " +
                "avg=${String.format("%.0f", avgConfidence * 100)}%")
        Log.i(TAG, "========================================")

        // The trigger line for this device. Reuses the EI leg's audio stats: both legs read the
        // same SharedAudioBuffer, and EI's 1s window is the wake window. Both per-engine
        // confidences are recorded separately — avgConfidence above discards exactly the
        // signal a cross-device arbiter might need.
        eiDetector?.let { ei ->
            WakeSignalProbe.emit(
                context = context,
                engine = "dual",
                mode = sensitivityMode.name,
                trigger = true,
                confidence = avgConfidence,
                eiConfidence = eiConf,
                mwwConfidence = mwwConf,
                rms = ei.lastDetectionRms,
                peak = ei.lastDetectionPeak,
                noiseFloor = ei.noiseFloorTracker.floor(now),
                floorSamples = ei.noiseFloorTracker.settledCount(now),
                bufferPosition = bufferPosition,
            )
        }

        clearDetectionState()
        onWakeWordDetected?.invoke(avgConfidence, bufferPosition)
    }

    /**
     * Report — exactly once, and loudly — a detection that will never find its partner.
     *
     * 🔴 This is the marker the re-arm race had no trace of. Until 2026-08-23 an EI fire with no
     * MWW behind it simply sat in `eiDetectionTime` until something else overwrote it: no DROP, no
     * "wake ignored", nothing. T's controlled repro (0/3 after a failed turn vs 5/5 from idle)
     * had to be assembled by noticing which log line was ABSENT, which is the definition of a
     * silent drop under standing rule 2.
     *
     * Deliberately driven off the heartbeats rather than a scheduled task: both legs already tick
     * ~2×/s on their own threads, so this needs no new thread and no lifecycle, and a marker that
     * is up to 500 ms late is still a marker. It reports the OTHER leg's settle state inline, so
     * the line explains itself instead of requiring a two-tag timestamp correlation.
     */
    private fun sweepUnpaired() {
        val now = System.currentTimeMillis()
        synchronized(this) {
            val eiTime = eiDetectionTime
            val mwwTime = mwwDetectionTime
            if (eiTime != null && mwwTime == null && now - eiTime > AGREEMENT_WINDOW_MS) {
                Log.w(TAG, "DROP: wake DISCARDED — EI fired " +
                        "${String.format("%.0f", (eiConfidence ?: 0f) * 100)}% and no MWW " +
                        "corroboration arrived within ${AGREEMENT_WINDOW_MS}ms " +
                        "(MWW ${mwwDetector?.settleState ?: "absent"}; gate ${describeGate()})")
                eiConfidence = null; eiBufferPosition = null; eiDetectionTime = null
            }
            if (mwwTime != null && eiTime == null && now - mwwTime > AGREEMENT_WINDOW_MS) {
                Log.w(TAG, "DROP: wake DISCARDED — MWW fired " +
                        "${String.format("%.0f", (mwwConfidence ?: 0f) * 100)}% and no EI " +
                        "corroboration arrived within ${AGREEMENT_WINDOW_MS}ms " +
                        "(gate ${describeGate()})")
                mwwConfidence = null; mwwBufferPosition = null; mwwDetectionTime = null
            }
        }
    }

    private fun clearDetectionState() {
        eiConfidence = null
        eiBufferPosition = null
        eiDetectionTime = null
        mwwConfidence = null
        mwwBufferPosition = null
        mwwDetectionTime = null
    }
}

package com.dashieapp.Dashie.wakeword.microwakeword

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import com.dashieapp.Dashie.microfrontend.MicroFrontend
import com.dashieapp.Dashie.wakeword.WakeWordDetectorInterface
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * microWakeWord wake word detector.
 *
 * Uses the TFLite Micro Frontend (native C via JNI) for feature extraction
 * and a streaming MixConv TFLite model for detection.
 *
 * Key differences from EdgeImpulseDetector:
 * - True streaming model: processes 30ms of new audio per inference (not full 1-sec window)
 * - Built-in noise reduction + PCAN AGC in the feature extractor
 * - Sliding window probability averaging (must sustain high confidence across multiple frames)
 * - Much smaller model (~37KB tensor arena vs 611KB)
 * - Requires native C library (64-bit only — ARM32 falls back to Edge Impulse)
 *
 * Detection algorithm:
 * 1. Read 160 samples (10ms) from SharedAudioBuffer
 * 2. Feed to MicroFrontend → 40 float features
 * 3. Every 3 frames (30ms), run TFLite inference → single probability
 * 4. Maintain sliding window of last N probabilities
 * 5. If average probability > cutoff, trigger detection
 */
class MicroWakeWordDetector(
    private val context: Context,
    private val modelFile: File? = null,
    private val assetModelPath: String? = null
) : WakeWordDetectorInterface {

    private val TAG = "MicroWakeWordDetector"

    override val engineName: String = "microWakeWord"

    // Native feature extractor
    private var microFrontend: MicroFrontend? = null

    // TFLite streaming classifier
    private var classifier: MicroWakeWordClassifier? = null

    // Shared audio buffer
    private var sharedBuffer: SharedAudioBuffer? = null

    // Processing state
    private val executor = Executors.newSingleThreadExecutor()
    private val isRunning = AtomicBoolean(false)
    private var isInitialized = false

    // Feature frame accumulator (collect 3 frames before inference)
    private val featureFrames = mutableListOf<FloatArray>()

    // Sliding window of recent probabilities for averaging
    private val probabilityWindow = ArrayDeque<Float>()

    // Sequential read cursor (tracks position in SharedAudioBuffer)
    private var readCursor: Long = 0

    // ── Re-arm back-fill (2026-08-23) ────────────────────────────────────────────────────────
    // Where the cursor was when detection last stopped, and whether this instance has ever run.
    // Together these let start() distinguish a RE-ARM (mid-conversation, a user is standing
    // there) from a true COLD START (app boot), which need different history and settle rules.
    // See start() for the reasoning.
    private var cursorAtStop: Long = 0
    private var hasRunBefore = false

    /** True for the current run when start() back-filled history (i.e. this was a re-arm). */
    private var warmStart = false

    /**
     * Whether the detection currently being delivered scored audio that predates the re-arm.
     *
     * Read by [com.dashieapp.Dashie.wakeword.DualEngineDetector] INSIDE the callback, on this same
     * thread, immediately after it is set — so it needs no synchronisation and cannot be observed
     * stale. It exists because a back-filled detection is the one case where the AND gate cannot
     * wait for a live EI fire: EI never reaches that far back, so the gate must PULL a score from
     * it instead. See `EdgeImpulseDetector.scoreWindowEndingAt`.
     */
    @Volatile var lastDetectionFromBackfill: Boolean = false
        private set

    // Warmup: ignore detections for the first N inferences after start(), so the MicroFrontend's
    // AGC / noise-reduction estimates (reset in start()) have settled before a score is trusted.
    private var inferenceCount = 0

    /**
     * Inferences of settle required at a TRUE COLD START — app boot / voice first enabled.
     *
     * ⚠️ **This is ~3 SECONDS of audio, not the ~1 second it has claimed since 2026-03-15.** One
     * inference consumes `STRIDE_FRAMES` (3) frames of `FEATURE_STEP_SIZE_MS` (10 ms) each, so
     * 100 × 3 × 10 ms = **3000 ms** — the original comment's own arithmetic, with a conclusion
     * that contradicted it. Measured on device 2026-08-23 (Samsung, `mww: peak=` lines are emitted
     * every 17 inferences and land 496–504 ms apart ⇒ 29.4 ms per inference ⇒ 100 ≈ 2.94 s).
     *
     * 📌 Deliberately left at 100 rather than retuned down to the 1 s that was intended: a deaf
     * window at app boot is invisible to users, and silently changing cold-start behaviour is a
     * separate decision from fixing the re-arm defect. Flagged, not fixed.
     */
    private val COLD_START_MIN_INFERENCES = 100

    /**
     * Inferences of settle required on a RE-ARM: **none.** The only floor left is the structural
     * one every score already needs — a full [MicroWakeWordConfig.slidingWindowSize] window
     * (5 inferences, 150 ms), enforced at the top of `checkDetection`.
     *
     * ## This was 33 for one build, and the device said no
     *
     * The first cut of this fix kept a ~1 s allowance on the theory that the back-fill would pay
     * for it out of history rather than out of the user's patience. **Measured on the Samsung
     * 2026-08-23, that theory is wrong, and the log said so in one line:**
     * ```
     * DROP: wake DISCARDED by the settle window — avg=94% at inference 8/33 (re-arm)
     * ```
     * The back-fill did its job — MWW scored the wake at **94%**, on audio the old cursor could
     * never have seen — and the allowance threw it away anyway. The reason is structural: how much
     * audio precedes the wake word *inside* the back-fill depends on when the user happened to
     * speak relative to the re-arm, which nothing here controls. A wake spoken ~1.7 s before the
     * re-arm leaves only ~0.25 s of pre-roll. **Any non-zero allowance is therefore a lottery on
     * the user's timing**, and widening the back-fill only moves the boundary.
     *
     * ## Why removing it on this path is safe
     *
     * The 2026-03-15 guard (`7ce180bf`) gave two reasons, and neither survives here:
     *  1. *"prevents ghost triggers from residual audio in the buffer"* — a single-engine concern.
     *     `DualEngineDetector`'s AND gate landed 12 days LATER (`898cf7f3`), and a ghost now needs
     *     BOTH engines to agree on the same audio within 500 ms. The `cursorAtStop` floor covers
     *     it a second time: the cursor never reaches audio this detector already scored.
     *  2. *"lets the MicroFrontend's AGC/noise reduction settle"* — the 94% above IS a measurement
     *     of that cost, and it is a data point AGAINST it mattering for recall: a completely
     *     unsettled frontend, 8 inferences (240 ms) from `reset()`, scored a real wake word far
     *     above this gate's 20 % bar. On the false-accept side, an unsettled mis-score still has
     *     to be corroborated by EI to trigger anything.
     *
     * ⚠️ n = 1 for that AGC observation. If a warm-start ghost ever shows up in the field it will
     * be visible — the gate logs every unpaired fire — and the fix is a small allowance here, not
     * a return to the 3 s one. [COLD_START_MIN_INFERENCES] is deliberately untouched.
     */
    private val WARM_START_MIN_INFERENCES = 0

    /**
     * Buffer position from which detections are honoured on the current run, or `Long.MIN_VALUE`
     * on a cold start (where the count-based [COLD_START_MIN_INFERENCES] governs instead).
     */
    private var detectFromPosition = Long.MIN_VALUE

    /**
     * The live write head at the moment of the last [start]. A detection whose cursor sits BEFORE
     * this scored audio that predates the re-arm — which is what [lastDetectionFromBackfill]
     * reports to the gate, and the only case that needs a pulled EI score rather than a live one.
     */
    private var backfillEndsAt = Long.MAX_VALUE

    // Detection cooldown — the stamp, the rollback and the run bound all live in one pure,
    // clock-injected place so they can be asserted without a mic. See DetectionCooldown.
    private val cooldown = DetectionCooldown(MicroWakeWordConfig.COOLDOWN_MS)

    // Which detection the DEBOUNCE line has already been emitted for — one line per cooldown
    // episode rather than one per suppressed inference. See checkDetection().
    private var lastDebounceLoggedFor = -1L

    // Whether this run has already reported a settle-window / priming discard (one line each
    // per start(), because a single wake stays above the cutoff for several consecutive
    // inferences and one discarded wake would otherwise emit a paragraph).
    private var settleDropLogged = false
    private var primingDropLogged = false

    // Heartbeat tracking
    private var heartbeatChunkCounter = 0
    private var maxConfidenceSinceHeartbeat = 0f
    private var maxVolumeSinceHeartbeat = 0f
    private var chunkCount = 0

    // Probability reporting (debug)
    private var probReportCounter = 0
    private var maxProbSinceReport = 0f

    // Live sample collection for training data
    var sampleCollector: com.dashieapp.Dashie.wakeword.LiveSampleCollector? = null
    // When false (set by DualEngineDetector), this engine does NOT drive collection —
    // capture stays EI-focused so the daily budget harvests EI's operating point.
    var collectSamples: Boolean = true

    // ── Wake-signal probe (multi-device arbitration, Phase A measurement) ──────────────
    // Sized for this engine's 10ms chunk cadence: ~30s of history, min ~5s settled.
    val noiseFloorTracker = com.dashieapp.Dashie.wakeword.NoiseFloorTracker(
        windowSamples = 3000,
        minSamples = 500,
    )

    /** False when this is only a LEG of a DualEngineDetector AND-gate — the gate emits instead. */
    var isTriggerAuthority: Boolean = true

    /** Sensitivity label for the probe line; DualEngineDetector overwrites it with its mode. */
    var probeMode: String = "MWW_ONLY"

    // Audio stats of the 1s window that fired, exposed for DualEngineDetector's gate line.
    @Volatile var lastDetectionRms: Float = 0f
        private set
    @Volatile var lastDetectionPeak: Float = 0f
        private set

    // Callbacks
    override var onWakeWordDetected: ((confidence: Float, bufferPosition: Long) -> Unit)? = null
    override var onHeartbeat: ((confidence: Float, volume: Float) -> Unit)? = null

    companion object {
        /**
         * Static listener for live confidence meter (used by settings UI).
         * Called on the detection thread with each inference result (~every 30ms).
         * Set to null when not needed to avoid overhead.
         */
        var onLiveConfidence: ((probability: Float, avgProbability: Float) -> Unit)? = null
    }
    override var onError: ((String) -> Unit)? = null

    override fun setSharedBuffer(buffer: SharedAudioBuffer) {
        this.sharedBuffer = buffer
        Log.d(TAG, "SharedAudioBuffer attached")
    }

    override fun initialize(): Boolean {
        try {
            Log.i(TAG, "========================================")
            Log.i(TAG, "Initializing microWakeWord detector...")
            Log.i(TAG, "Probability cutoff: ${MicroWakeWordConfig.probabilityCutoff}")
            Log.i(TAG, "Sliding window size: ${MicroWakeWordConfig.slidingWindowSize}")
            Log.i(TAG, "========================================")

            // Check native library availability
            if (!MicroFrontend.isAvailable()) {
                Log.e(TAG, "MicroFrontend native library not available on this device")
                onError?.invoke("microWakeWord native library unavailable on this device")
                return false
            }

            // Create native feature extractor
            microFrontend = MicroFrontend(
                stepSizeMs = MicroWakeWordConfig.FEATURE_STEP_SIZE_MS,
                sampleRate = MicroWakeWordConfig.SAMPLE_RATE
            )

            // Create and initialize TFLite classifier
            classifier = MicroWakeWordClassifier(
                context = context,
                modelFile = modelFile,
                assetModelPath = assetModelPath
            )

            if (!classifier!!.initialize()) {
                Log.e(TAG, "Failed to initialize classifier")
                onError?.invoke("Classifier initialization failed")
                return false
            }

            isInitialized = true
            Log.i(TAG, "microWakeWord detector initialized successfully")
            Log.i(TAG, "========================================")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize microWakeWord detector", e)
            onError?.invoke("Initialization failed: ${e.message}")
            return false
        }
    }

    override fun start() {
        if (isRunning.get()) {
            Log.w(TAG, "Already running")
            return
        }

        if (!isInitialized) {
            Log.e(TAG, "Cannot start - not initialized")
            onError?.invoke("Detector not initialized")
            return
        }

        if (sharedBuffer == null) {
            Log.e(TAG, "Cannot start - no SharedAudioBuffer attached")
            onError?.invoke("No audio buffer attached")
            return
        }

        Log.d(TAG, "========================================")
        Log.d(TAG, "Starting microWakeWord detection")
        Log.d(TAG, "========================================")

        // Reset state
        chunkCount = 0
        heartbeatChunkCounter = 0
        maxConfidenceSinceHeartbeat = 0f
        maxVolumeSinceHeartbeat = 0f
        probReportCounter = 0
        maxProbSinceReport = 0f
        inferenceCount = 0
        settleDropLogged = false
        primingDropLogged = false
        featureFrames.clear()
        probabilityWindow.clear()

        // ── Where this run starts reading ────────────────────────────────────────────────────
        //
        // 🔴 THE RE-ARM RACE LIVED ON THIS LINE. It used to be an unconditional
        // `readCursor = getCurrentPosition()` — "skip any buffered audio" — which means a wake
        // word already COMPLETE in the buffer at the moment of re-arm is permanently behind the
        // cursor and can never be scored. EI has no such cursor (it classifies the most recent 1 s
        // window on every poll), so it fires ~7 ms after the re-arm on exactly that audio and then
        // waits for a partner that structurally cannot arrive. The AND gate never triggers and a
        // 100% detection is discarded. Measured, with a control (, Samsung):
        // **0/3 woke after a failed turn vs 5/5 from idle on the identical clip**; MWW fired in
        // every accept and in none of the drops; EI at 71% was dropped while EI at 44% woke, so it
        // is not a threshold effect.
        //
        // ⚠️ The user-visible shape is worse than "a wake in the ~300 ms after a turn": the
        // re-arm is only cold on the paths that DON'T restart the detector early. A successful
        // turn re-arms at `Final STT result` (VoicePipelineCoordinator, "resume wake word
        // immediately so user can interrupt"), seconds before the answer finishes — so by `->
        // IDLE` this detector is long warm, and every later start() logs "Already running". A
        // FAILED turn ("I didn't hear that") has no such early restart: `handleNoSpeech` re-arms
        // at the IDLE transition itself, cold. That is precisely the moment a user retries.
        //
        // The fix, in one line: on a re-arm, read the history this detector MISSED instead of
        // discarding it — bounded, and never re-reading anything already scored before the stop.
        val currentPos = sharedBuffer!!.getCurrentPosition()
        // Audio before the last capture restart is NOT part of this run — it is either stale
        // speech or the silence markDiscontinuity wrote over it. Both floors below derive from it.
        val discontinuityPos = sharedBuffer!!.discontinuityPosition
        readCursor = MicroWakeWordRearmPolicy.startCursor(
            currentPosition = currentPos,
            cursorAtStop = cursorAtStop,
            hasRunBefore = hasRunBefore,
            backFillSamples = MicroWakeWordRearmPolicy.REARM_BACKFILL_SAMPLES,
            ringCapacity = sharedBuffer!!.capacity.toLong(),
            discontinuityPosition = discontinuityPos,
        )
        warmStart = hasRunBefore
        detectFromPosition =
            if (warmStart) MicroWakeWordRearmPolicy.earliestHonouredPosition(currentPos, discontinuityPos)
            else Long.MIN_VALUE
        backfillEndsAt = if (warmStart) currentPos else Long.MAX_VALUE
        cooldown.onRunStarted()
        if (warmStart) {
            // Name the binding constraint: after a mic handoff the reach is priming-bound, not
            // reach-bound, and a reader comparing the two numbers should not have to infer that.
            val primingBound = detectFromPosition > currentPos - MicroWakeWordRearmPolicy.REARM_DETECT_REACH_SAMPLES
            val backFilled = (currentPos - readCursor) / 16
            Log.i(TAG, "Re-arm: back-filling ${backFilled}ms of missed audio, " +
                if (primingBound)
                    "NOTHING honoured yet — PRIMING-BOUND: capture restarted " +
                        "${(currentPos - discontinuityPos) / 16}ms ago, so no detection is acted " +
                        "on until ${(detectFromPosition - currentPos) / 16}ms more live audio has settled"
                else
                    "detections honoured over the last ${(currentPos - detectFromPosition) / 16}ms " +
                        "(matched to EI's classification window)")
        }

        // Reset streaming model state
        classifier?.reset()
        microFrontend?.reset()

        isRunning.set(true)

        executor.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            runDetectionLoop()
        }
    }

    override fun stop() {
        // Recorded before the running check so a redundant stop() can't lose it. May trail the
        // loop's own cursor by at most one 160-sample chunk (the loop notices isRunning on its
        // next pass), which only ever means the back-fill re-reads ≤10 ms — harmless.
        cursorAtStop = readCursor
        hasRunBefore = true

        if (!isRunning.get()) {
            return
        }

        Log.d(TAG, "========================================")
        Log.d(TAG, "Stopping microWakeWord detection (chunk #$chunkCount)")
        Log.d(TAG, "========================================")
        isRunning.set(false)
    }

    override fun isDetecting(): Boolean = isRunning.get()

    override fun setThreshold(threshold: Float) {
        val clamped = threshold.coerceIn(0.0f, 1.0f)
        MicroWakeWordConfig.probabilityCutoff = clamped
        Log.d(TAG, "Probability cutoff set to: ${"%.2f".format(clamped)}")
    }

    override fun getThreshold(): Float = MicroWakeWordConfig.probabilityCutoff

    override fun getMemoryStatus(): String {
        val state = when {
            !isInitialized -> "not_init"
            isRunning.get() -> "running"
            else -> "stopped"
        }
        return "engine=mww state=$state chunks=$chunkCount"
    }

    override fun close() {
        stop()
        microFrontend?.close()
        microFrontend = null
        classifier?.close()
        classifier = null
        sharedBuffer = null
        isInitialized = false
        Log.d(TAG, "microWakeWord detector closed")
    }

    /**
     * Detection loop - reads 10ms chunks from SharedAudioBuffer.
     * Runs on a dedicated high-priority thread.
     */
    private fun runDetectionLoop() {
        Log.d(TAG, "Detection loop started - polling every ${MicroWakeWordConfig.POLL_INTERVAL_MS}ms")

        // Pre-allocate conversion buffer
        val shortBuffer = ShortArray(MicroWakeWordConfig.SAMPLES_PER_CHUNK)

        while (isRunning.get()) {
            try {
                chunkCount++

                // Wait for enough new samples (sequential read, no gaps or overlaps)
                val samplesNeeded = MicroWakeWordConfig.SAMPLES_PER_CHUNK
                val available = sharedBuffer!!.samplesAvailableFrom(readCursor)
                if (available < samplesNeeded) {
                    // Not enough new audio yet — wait and retry
                    Thread.sleep(1)
                    continue
                }

                // Read exactly 160 new samples sequentially
                val floatSamples = sharedBuffer!!.readFrom(readCursor, samplesNeeded)
                readCursor += floatSamples.size

                // Track volume for heartbeat
                val maxAbs = floatSamples.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
                if (maxAbs > maxVolumeSinceHeartbeat) {
                    maxVolumeSinceHeartbeat = maxAbs
                }

                // Feed the ambient floor for the wake-signal probe. RMS is window-length
                // invariant for stationary noise, so a floor built from 10ms chunks is
                // comparable to the 1s wake window measured at fire time below.
                if (floatSamples.isNotEmpty()) {
                    noiseFloorTracker.record(
                        kotlin.math.sqrt(floatSamples.map { it * it }.average().toFloat()),
                        System.currentTimeMillis(),
                    )
                }

                // Convert float [-1, 1] to int16 for MicroFrontend
                for (i in floatSamples.indices) {
                    shortBuffer[i] = (floatSamples[i] * 32767f)
                        .toInt()
                        .coerceIn(-32768, 32767)
                        .toShort()
                }

                // Extract features via native MicroFrontend
                val frames = microFrontend!!.processSamples(shortBuffer)

                // Accumulate feature frames
                for (frame in frames) {
                    featureFrames.add(frame)

                    // Run inference every 3 frames (30ms)
                    if (featureFrames.size >= MicroWakeWordConfig.STRIDE_FRAMES) {
                        inferenceCount++
                        val probability = classifier!!.classify(featureFrames)
                        featureFrames.clear()

                        // Add to sliding window
                        probabilityWindow.addLast(probability)
                        while (probabilityWindow.size > MicroWakeWordConfig.slidingWindowSize) {
                            probabilityWindow.removeFirst()
                        }

                        // Track max confidence for heartbeat
                        if (probability > maxConfidenceSinceHeartbeat) {
                            maxConfidenceSinceHeartbeat = probability
                        }

                        // Live confidence for settings meter
                        onLiveConfidence?.invoke(probability, getAverageProbability())

                        // Track max probability per reporting window (every 500ms)
                        if (probability > maxProbSinceReport) {
                            maxProbSinceReport = probability
                        }
                        probReportCounter++
                        // Report max probability every ~500ms (17 inferences at 30ms each)
                        if (probReportCounter >= 17) {
                            if (maxProbSinceReport >= 0.01f) {
                                Log.i(TAG, "mww: peak=${"%.0f".format(maxProbSinceReport * 100)}% " +
                                        "avg=${"%.0f".format(getAverageProbability() * 100)}% " +
                                        "vol=${"%.3f".format(maxVolumeSinceHeartbeat)}")
                            }
                            probReportCounter = 0
                            maxProbSinceReport = 0f
                        }

                        // Log individual high probabilities
                        if (probability >= 0.30f) {
                            Log.i(TAG, "mww: ${"%.0f".format(probability * 100)}% " +
                                    "(avg=${"%.0f".format(getAverageProbability() * 100)}% " +
                                    "window=${probabilityWindow.map { "%.0f".format(it * 100) }})")
                        }

                        // Collect samples for training (uses sliding window avg as score).
                        // Skipped when collectSamples=false (dual mode → EI drives capture).
                        val avgProb = getAverageProbability()
                        if (collectSamples && avgProb >= 0.35f) {
                            sampleCollector?.maybeCollectSample(
                                score = avgProb,
                                bufferPosition = sharedBuffer!!.getCurrentPosition(),
                                maxAbs = maxAbs,
                                rms = kotlin.math.sqrt(
                                    floatSamples.map { it * it }.average().toFloat()
                                ),
                                detectionType = "mww"
                            )
                        }

                        // Check for detection
                        checkDetection()
                    }
                }

                // Send heartbeat
                heartbeatChunkCounter++
                if (heartbeatChunkCounter >= MicroWakeWordConfig.HEARTBEAT_INTERVAL_CHUNKS) {
                    onHeartbeat?.invoke(maxConfidenceSinceHeartbeat, maxVolumeSinceHeartbeat)
                    heartbeatChunkCounter = 0
                    maxConfidenceSinceHeartbeat = 0f
                    maxVolumeSinceHeartbeat = 0f
                }

                // Log every 60 seconds to confirm loop is running
                if (chunkCount % 6000 == 0) {
                    Log.i(TAG, "Detection loop alive: iteration=$chunkCount")
                }

                // No sleep here — pacing handled by waiting for new samples at top of loop

            } catch (e: InterruptedException) {
                Log.d(TAG, "Detection loop interrupted")
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in detection loop", e)
                onError?.invoke("Detection error: ${e.message}")
            }
        }

        Log.d(TAG, "Detection loop ended")
    }

    /**
     * Check if the sliding window average exceeds the cutoff.
     */
    private fun checkDetection() {
        if (probabilityWindow.size < MicroWakeWordConfig.slidingWindowSize) {
            return  // Not enough samples yet
        }

        // Settle window: ignore detections until the MicroFrontend's AGC / noise-reduction
        // estimates (reset in start()) have converged. On a re-arm the back-fill has already paid
        // most of this out of history, so the bar is lower AND the audio that satisfies it is
        // audio the user did not have to wait through. See the two constants.
        //
        // 🔴 The commit that added this guard (7ce180bf, 2026-03-15) gave TWO reasons: AGC settle,
        // and "prevents ghost triggers from residual audio in the buffer". The second reason is
        // now covered twice over — once structurally (the cursor never reaches back past
        // cursorAtStop, so residual audio cannot be re-scored) and once by the AND gate in
        // DualEngineDetector, which did not exist when this landed (898cf7f3 is 12 days LATER).
        // A ghost now needs BOTH engines to agree on the same audio.
        // Re-arm only: the priming lead-in is consumed but never acted on, so the streaming model
        // and the AGC have context before any score is trusted. Everything AFTER the lead-in is
        // honoured — including audio EI never classified live, because the gate pulls a score from
        // EI at the detection's position rather than waiting for one. See
        // MicroWakeWordRearmPolicy.REARM_DETECT_REACH_SAMPLES for why that bound is no longer EI's window.
        if (readCursor < detectFromPosition) {
            if (!primingDropLogged &&
                probabilityWindow.size >= MicroWakeWordConfig.slidingWindowSize &&
                getAverageProbability() >= MicroWakeWordConfig.probabilityCutoff
            ) {
                primingDropLogged = true
                // ⚠️ A SCORE dropped, not necessarily a wake. Observed on device 2026-08-23: the
                // head of a wake word scored 95% here and the TAIL of the same utterance fired
                // the gate 10 ms later, because the utterance straddles the lead-in boundary. So
                // this line must not claim the wake was lost.
                //
                // 🔴 REWRITTEN 2026-08-25 — the previous version was wrong TWICE, and
                // both are the same fault as the COMPARED WINDOW criterion next door: it told the
                // reader what to conclude, from data it did not print.
                //  1. It printed only the AVERAGE, so the one test that actually discriminates —
                //     a reset artifact is SATURATED on its first inference ([100,100,100,100,100])
                //     where a real wake RAMPS ([23,82,99,100,100]) — could not be applied to the
                //     log at all. The window is printed now.
                //  2. It said "if no AND-GATE follows within ~1s, this was the miss." T measured
                //     21 of 22 of these firing at an ordinary end-of-turn re-arm with NOTHING
                //     spoken, so that sentence misread almost every occurrence as a lost wake.
                //     This line is USUALLY the priming lead-in doing its job.
                //
                // ⚠️ And the window is evidence, not a verdict: T's 21/22 spread a smooth 36–100%,
                // so "saturated ⇒ artifact" sorts the clear cases and leaves a middle. If you need
                // certainty, the discriminator is whether a person actually spoke, not this line.
                Log.w(TAG, "DROP: score not acted on — avg " +
                        "${"%.0f".format(getAverageProbability() * 100)}% " +
                        "(window=${probabilityWindow.map { "%.0f".format(it * 100) }}) landed " +
                        "${(detectFromPosition - readCursor) / 16}ms into the re-arm priming " +
                        "lead-in, i.e. older than EI's window. Expected at an ordinary re-arm: a " +
                        "window saturated from its first inference is the microFrontend reset " +
                        "artifact being absorbed, which is this bound working. A RAMPING window " +
                        "with a wake actually spoken is the case worth chasing.")
            }
            return
        }

        if (inferenceCount < minInferencesBeforeDetection()) {
            // Standing rule 2: this used to be a fully silent discard, and it is the second half
            // of the re-arm race — a real wake scored above the cutoff and thrown away with no
            // trace. Only logged when a detection WOULD have fired, so it stays rare rather than
            // firing on every muted inference.
            // Once per run: a single wake stays above the cutoff for several consecutive
            // inferences, and on the build that carried a 33-inference re-arm allowance this
            // emitted 8 lines for one discarded wake. The FIRST line carries the peak and the
            // count, which is the whole diagnosis.
            if (!settleDropLogged &&
                probabilityWindow.size >= MicroWakeWordConfig.slidingWindowSize &&
                getAverageProbability() >= MicroWakeWordConfig.probabilityCutoff
            ) {
                settleDropLogged = true
                Log.w(TAG, "DROP: wake DISCARDED by the settle window — " +
                        "avg=${"%.0f".format(getAverageProbability() * 100)}% " +
                        "at inference $inferenceCount/${minInferencesBeforeDetection()} " +
                        "(${if (warmStart) "re-arm" else "cold start"})")
            }
            return
        }

        val avgProbability = getAverageProbability()

        if (avgProbability >= MicroWakeWordConfig.probabilityCutoff) {
            val currentTime = System.currentTimeMillis()

            if (cooldown.isDebounced(currentTime)) {
                // `DEBOUNCE:`, not `DROP:` — same reasoning as EdgeImpulseDetector's: this is one
                // utterance reported once, not a decision discarded. `DROP:` is reserved for
                // wakes that are actually lost, so that grep stays a defect signal.
                //
                // ONCE PER COOLDOWN EPISODE, keyed on the detection being debounced against.
                // Measured on device 2026-08-23: without this guard a single wake word produced
                // ~30 lines, several per millisecond, because this runs per inference and the
                // loop bursts. A marker nobody can read is not an improvement on silence.
                if (lastDebounceLoggedFor != cooldown.lastDetectionTime) {
                    lastDebounceLoggedFor = cooldown.lastDetectionTime
                    Log.d(TAG, "DEBOUNCE: further detections suppressed by cooldown for " +
                            "${MicroWakeWordConfig.COOLDOWN_MS}ms — same utterance " +
                            "(first re-cross at ${currentTime - cooldown.lastDetectionTime}ms, " +
                            "avg=${"%.0f".format(avgProbability * 100)}%)")
                }
            }

            if (cooldown.tryStamp(currentTime)) {

                // Mark buffer position for STT handoff
                // 🔴 WHICH POSITION THIS DETECTION IS ABOUT. `markPosition()` is the LIVE write
                // head, which is correct only while this detector is reading live audio. During a
                // re-arm back-fill the loop is scoring audio up to 1.7 s in the past, so the live
                // head names a moment that has nothing to do with the wake word just detected.
                //
                // Measured consequence, and it is why this is not cosmetic (device, 2026-08-23):
                // the gate's retro-score asked EI about the window ending at the live head —
                // silence — and got **0–1% against MWW's 97–100%** on three consecutive runs. The
                // corroboration could not have succeeded at any threshold. The same wrong position
                // is handed to STT, so a back-filled wake would also have started recording from
                // the wrong point.
                //
                // `readCursor` is the end of the audio actually scored, in both modes. It is used
                // only for back-filled detections so the live path keeps its existing behaviour
                // byte for byte.
                val currentPosition =
                    if (warmStart && readCursor < backfillEndsAt) readCursor
                    else sharedBuffer!!.markPosition()
                val preRollSamples = 1600L  // 100ms pre-roll (same as EI)
                val bufferPosition = (currentPosition - preRollSamples).coerceAtLeast(0)

                // The cursor offset is what makes this line comparable with the gate's
                // COMPARED WINDOW line — the two together answer "did EI look where MWW fired?"
                // without anyone having to reconstruct buffer arithmetic from two tags.
                val liveHeadNow = sharedBuffer!!.getCurrentPosition()
                Log.i(TAG, "WAKE WORD DETECTED! avg=${"%.0f".format(avgProbability * 100)}% " +
                        "(window=${probabilityWindow.map { "%.0f".format(it * 100) }}) " +
                        "@ ${(liveHeadNow - currentPosition) / 16}ms before the live head" +
                        (if (warmStart && readCursor < backfillEndsAt) " [BACK-FILLED]" else ""))

                // Clear window to prevent re-trigger
                probabilityWindow.clear()

                // Measure the wake window over the same 1s span EI uses, so the two engines'
                // probe lines are directly comparable. Only on fire — never in the loop.
                val wakeWindow = sharedBuffer!!.readWindow(16000)
                lastDetectionPeak = wakeWindow.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
                lastDetectionRms =
                    if (wakeWindow.isEmpty()) 0f
                    else kotlin.math.sqrt(wakeWindow.map { it * it }.average().toFloat())

                com.dashieapp.Dashie.wakeword.WakeSignalProbe.emit(
                    context = context,
                    engine = "mww",
                    mode = probeMode,
                    trigger = isTriggerAuthority,
                    confidence = avgProbability,
                    eiConfidence = null,
                    mwwConfidence = avgProbability,
                    rms = lastDetectionRms,
                    peak = lastDetectionPeak,
                    noiseFloor = noiseFloorTracker.floor(currentTime),
                    floorSamples = noiseFloorTracker.settledCount(currentTime),
                    bufferPosition = bufferPosition,
                )

                // Set before the callback and read inside it — see the field's KDoc.
                lastDetectionFromBackfill = warmStart && readCursor < backfillEndsAt
                onWakeWordDetected?.invoke(avgProbability, bufferPosition)
            }
        }
    }

    private fun getAverageProbability(): Float {
        if (probabilityWindow.isEmpty()) return 0f
        return probabilityWindow.sum() / probabilityWindow.size
    }

    private fun minInferencesBeforeDetection(): Int =
        if (warmStart) WARM_START_MIN_INFERENCES else COLD_START_MIN_INFERENCES

    /**
     * Human-readable settle state, for the gate's DROP line.
     *
     * Exists so that an unpaired EI fire says WHY there was no corroboration in the same log line,
     * rather than leaving a future reader to correlate two tags by timestamp — which is exactly
     * the correlation this defect went undiagnosed behind.
     */
    val settleState: String
        get() {
            // 🔴 COOLDOWN FIRST, and this ordering is the point. Until 2026-08-25 this property
            // reported only the settle counter, so a debounced detector announced itself as
            // "warm" — and the gate's unpaired-EI DROP line printed that verbatim. A tester
            // read it exactly as written (MWW was listening and disagreed) when the truth was
            // that MWW had fired moments earlier and was suppressed. "Warm" and "cannot fire"
            // are different facts and the line was reporting the wrong one.
            val now = System.currentTimeMillis()
            if (cooldown.isDebounced(now)) {
                return "DEBOUNCED ${cooldown.remainingMs(now)}ms left"
            }
            return if (inferenceCount >= minInferencesBeforeDetection()) "warm"
            else "SETTLING $inferenceCount/${minInferencesBeforeDetection()}" +
                    (if (warmStart) " (re-arm)" else " (cold start)")
        }

    /**
     * Give the cooldown back after the gate DISCARDED the detection that consumed it.
     *
     * ⚠️ **Only ever for a DISCARDED detection** — after a trigger it would let one utterance wake
     * the device twice. The defect this prevents, the measurement behind it and the once-per-run
     * bound are all in [DetectionCooldown]'s KDoc; this method only adds the logging.
     */
    fun rollbackDetectionStamp() {
        if (!cooldown.rollback()) {
            Log.d(TAG, "DEBOUNCE: cooldown rollback already used this run — stamp kept")
            return
        }
        // Re-arm the DEBOUNCE dedupe too: it keys on the stamp, and a stale key would swallow
        // the next genuine cooldown episode's one line.
        lastDebounceLoggedFor = -1L
        Log.i(TAG, "Cooldown RETURNED — the detection that consumed it was discarded by the " +
                "gate, so it must not blind the live path for the next " +
                "${MicroWakeWordConfig.COOLDOWN_MS}ms")
    }
}

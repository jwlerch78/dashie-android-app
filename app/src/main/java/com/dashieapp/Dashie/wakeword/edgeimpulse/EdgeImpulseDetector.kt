package com.dashieapp.Dashie.wakeword.edgeimpulse

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import com.dashieapp.Dashie.wakeword.LiveSampleCollector
import com.dashieapp.Dashie.wakeword.NoiseFloorTracker
import com.dashieapp.Dashie.wakeword.WakeSignalProbe
import com.dashieapp.Dashie.wakeword.WakeWordDetectorInterface
import com.dashieapp.Dashie.wakeword.models.WakeWordModel
import com.dashieapp.Dashie.wakeword.models.WakeWordModelManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Edge Impulse Wake Word Detector
 *
 * VERSION 3.0: Pure Kotlin implementation - no native SDK required!
 * Uses MfeFeatureExtractor (Kotlin) + TFLiteClassifier for detection.
 * Verified to match native SDK output (99.5%+ exact match on MFE features).
 *
 * Benefits of v3.0:
 * - Works on ARM32 (Fire TV) - no native library dependency
 * - ~50MB smaller APK (removed native SDK C++ code)
 * - Easier to debug and maintain
 * - No JNI complexity
 *
 * VERSION 2.13: Fixed preprocessing - removed preemphasis since native SDK handles it.
 * VERSION 2.12: Switched from Kotlin MFE reimplementation to native C++ SDK.
 *
 * UPDATED: Now reads from SharedAudioBuffer instead of managing its own AudioRecord.
 * This enables seamless handoff to STT without audio gaps.
 */
class EdgeImpulseDetector(
    private val context: Context,
    // Non-null for dual-engine models with their OWN EI leg (chickadee): the
    // bundled EI asset to load. Also disables the downloaded-model override,
    // which is a hey_dashie artifact.
    private val eiAssetPath: String? = null
) : WakeWordDetectorInterface {

    private val TAG = "EdgeImpulseDetector"

    override val engineName: String = "EdgeImpulse"

    // Pure Kotlin implementation: MFE feature extraction + TFLite inference
    private val mfeExtractor = MfeFeatureExtractor()
    private var tfliteClassifier: TFLiteClassifier? = null

    /**
     * Serialises every use of [mfeExtractor] + [tfliteClassifier].
     *
     * 🔴 **Neither is thread-safe, and both are now reached from TWO threads.** `MfeFeatureExtractor`
     * reuses a `frameDouble` scratch array across calls; `TFLiteClassifier` reuses its input/output
     * `ByteBuffer`s and wraps a TFLite `Interpreter`, which is explicitly not safe for concurrent
     * invocation. That was fine while the detection loop was the only caller — [scoreWindowEndingAt]
     * broke the assumption by running on microWakeWord's detection thread, concurrently with this
     * detector's own loop.
     *
     * ⚠️ **This is the strongest candidate for the 0–8% retro-scores measured on 2026-08-23** —
     * EI scoring near zero on audio microWakeWord scored 95–100%, four runs running. A raced
     * scratch buffer does not fail loudly; it returns a plausible-looking number computed from
     * half-overwritten features, which is exactly the shape of the observation.
     *
     * A lock rather than a second extractor+interpreter pair: the loop runs ~10×/s and an
     * inference is tens of milliseconds, so contention is negligible, whereas a second TFLite
     * interpreter would cost another ~611 KB arena on devices already tight on RAM. The retro-score
     * may block for at most one inference, far inside the gate's 500 ms agreement window.
     */
    private val scoringLock = Any()

    // Wake word model manager for downloadable models
    private val modelManager = WakeWordModelManager(context)

    // Audio preprocessor for normalization
    private val preprocessor = AudioPreprocessor()

    // Detection cooldown (prevent multiple triggers)
    private var lastDetectionTime = 0L
    private val cooldownMs = 1500L  // 1.5 seconds

    // Which detection the DEBOUNCE line has already been emitted for (see below).
    private var lastDebounceLoggedFor = -1L

    // Shared audio buffer (set via setSharedBuffer)
    private var sharedBuffer: SharedAudioBuffer? = null

    // Processing state
    private val executor = Executors.newSingleThreadExecutor()
    private val isRunning = AtomicBoolean(false)
    private var chunkCount = 0

    // Native library initialization state
    private var isNativeInitialized = false

    // Heartbeat tracking (2x per second = every 5 chunks at 10 chunks/sec)
    private val HEARTBEAT_INTERVAL_CHUNKS = 5  // Send heartbeat every 5 chunks (500ms)
    private var heartbeatChunkCounter = 0
    private var maxConfidenceSinceHeartbeat = 0f
    private var maxVolumeSinceHeartbeat = 0f


    // Callbacks
    override var onWakeWordDetected: ((confidence: Float, bufferPosition: Long) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onHeartbeat: ((confidence: Float, volume: Float) -> Unit)? = null

    // Live sample collection for training data (beta feature)
    var sampleCollector: LiveSampleCollector? = null

    // ── Wake-signal probe (multi-device arbitration, Phase A measurement) ──────────────
    // Ambient floor for SNR. Fed the raw pre-normalization rms every poll (~100ms); read only
    // when a wake fires. See NoiseFloorTracker for why confidence can't serve this purpose.
    val noiseFloorTracker = NoiseFloorTracker()

    /**
     * False when this detector is only a LEG of a DualEngineDetector AND-gate — the gate, not
     * this instance, decides whether the device acts, so it emits the trigger line instead.
     */
    var isTriggerAuthority: Boolean = true

    /** Sensitivity label for the probe line; DualEngineDetector overwrites it with its mode. */
    var probeMode: String = "EI_ONLY"

    // Audio stats of the window that fired, exposed so DualEngineDetector can emit the gate
    // line without recomputing them (both legs read the same SharedAudioBuffer anyway).
    @Volatile var lastDetectionRms: Float = 0f
        private set
    @Volatile var lastDetectionPeak: Float = 0f
        private set

    /**
     * Set the shared audio buffer to read from
     * Must be called before start()
     */
    override fun setSharedBuffer(buffer: SharedAudioBuffer) {
        this.sharedBuffer = buffer
        Log.d(TAG, "SharedAudioBuffer attached")
    }

    /**
     * Initialize Kotlin MFE + TFLite classifier
     */
    override fun initialize(): Boolean {
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "Initializing Edge Impulse detector (Kotlin v3.0)...")
            Log.d(TAG, "Threshold: ${EdgeImpulseConfig.DETECTION_THRESHOLD}")
            Log.d(TAG, "========================================")

            // Get model file from manager (null = use bundled). When this leg has
            // its own EI asset (chickadee), the downloaded override never applies —
            // downloads are hey_dashie models.
            val activeModel = modelManager.getActiveModel()
            val modelFile = if (eiAssetPath == null) modelManager.getModelFile() else null

            Log.d(TAG, "Active model: ${activeModel.wakeWordName} v${activeModel.version}")
            if (modelFile != null) {
                Log.d(TAG, "Model source: downloaded (${modelFile.absolutePath})")
            } else {
                Log.d(TAG, "Model source: bundled assets")
            }

            // Initialize TFLite classifier with the active model
            tfliteClassifier = if (eiAssetPath != null) {
                TFLiteClassifier(context, modelFile, bundledAssetPath = eiAssetPath)
            } else {
                TFLiteClassifier(context, modelFile)
            }

            if (!tfliteClassifier!!.isReady()) {
                Log.e(TAG, "TFLite classifier failed to initialize!")
                onError?.invoke("TFLite classifier not available")
                return false
            }

            // Log configuration
            Log.d(TAG, "Kotlin MFE + TFLite classifier initialized")
            Log.d(TAG, "Expected samples: ${EdgeImpulseConfig.WINDOW_SIZE_SAMPLES}")
            Log.d(TAG, "Expected sample rate: ${EdgeImpulseConfig.SAMPLE_RATE} Hz")
            Log.d(TAG, "MFE output size: ${EdgeImpulseConfig.NN_INPUT_SIZE}")
            Log.d(TAG, "Labels: ${EdgeImpulseConfig.LABELS.joinToString(", ")}")

            isNativeInitialized = true  // Keeping variable name for compatibility

            // Update sample collector with current model info
            sampleCollector?.let {
                it.modelId = activeModel.modelId
                it.modelVersion = activeModel.version
                Log.d(TAG, "Sample collector updated with model: ${activeModel.modelId} v${activeModel.version}")
            }

            Log.d(TAG, "✓ Edge Impulse Kotlin detector initialized successfully")
            Log.d(TAG, "========================================")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Edge Impulse detector", e)
            onError?.invoke("Initialization failed: ${e.message}")
            return false
        }
    }

    /**
     * Get the wake word model manager for UI access
     */
    fun getModelManager(): WakeWordModelManager = modelManager

    /**
     * Reload the model (call after switching active model)
     */
    fun reloadModel(): Boolean {
        Log.d(TAG, "Reloading wake word model...")

        // Close existing classifier
        tfliteClassifier?.close()
        tfliteClassifier = null
        isNativeInitialized = false

        // Re-initialize with new active model
        return initialize()
    }

    /**
     * Start wake word detection
     * Reads from SharedAudioBuffer every 100ms
     */
    override fun start() {
        if (isRunning.get()) {
            Log.w(TAG, "Already running")
            return
        }

        if (!isNativeInitialized) {
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
        Log.d(TAG, "▶️ STARTING wake word detection (reading from SharedAudioBuffer)")
        Log.d(TAG, "========================================")

        chunkCount = 0
        heartbeatChunkCounter = 0
        maxConfidenceSinceHeartbeat = 0f
        maxVolumeSinceHeartbeat = 0f
        isRunning.set(true)

        executor.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            runDetectionLoop()
        }
    }

    /**
     * Stop wake word detection
     * Note: Does NOT stop AudioCaptureService - that continues running
     */
    override fun stop() {
        if (!isRunning.get()) {
            return
        }

        Log.d(TAG, "========================================")
        Log.d(TAG, "⏹️ STOPPING wake word detection (was at chunk #$chunkCount)")
        Log.d(TAG, "========================================")
        isRunning.set(false)
    }

    /**
     * Check if detection is running
     */
    override fun isDetecting(): Boolean {
        return isRunning.get()
    }

    /**
     * Detection loop - reads from SharedAudioBuffer every 100ms
     */
    private fun runDetectionLoop() {
        Log.d(TAG, "✓ Detection loop started - polling SharedAudioBuffer every 100ms")

        val pollIntervalMs = 100L  // Match original 100ms chunk processing
        var loopCount = 0

        while (isRunning.get()) {
            try {
                loopCount++
                val loopStartTime = System.currentTimeMillis()

                // Log every 600 iterations (60 seconds) to confirm loop is running
                if (loopCount % 600 == 0) {
                    val bufferPos = sharedBuffer?.getCurrentPosition() ?: 0
                    Log.i(TAG, "🔄 Detection loop alive: iteration=$loopCount, bufferPos=$bufferPos")
                }

                // Read 1-second window from shared buffer
                val audioWindow = sharedBuffer!!.readWindow(EdgeImpulseConfig.WINDOW_SIZE_SAMPLES)

                // Process this window
                processAudioChunkInternal(audioWindow)

                val processingTimeMs = System.currentTimeMillis() - loopStartTime
                // Log slow iterations (> 500ms) to diagnose performance issues
                if (processingTimeMs > 500 || loopCount <= 10) {
                    Log.w(TAG, "⏱️ Chunk #$loopCount took ${processingTimeMs}ms (target: <100ms)")
                }

                // Sleep until next poll
                Thread.sleep(pollIntervalMs)

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
     * Process audio chunk using Kotlin MFE + TFLite
     */
    private fun processAudioChunkInternal(audioSamples: FloatArray) {
        chunkCount++

        try {
            // Log audio statistics for debugging
            val maxAbs = audioSamples.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
            val rms = kotlin.math.sqrt(audioSamples.map { it * it }.average().toFloat())

            // Feed the ambient floor BEFORE preprocessing — normalizeVolume() below destroys the
            // level information, which is the only thing that carries proximity. O(1).
            noiseFloorTracker.record(rms, System.currentTimeMillis())

            // Step 1: Preprocess audio (normalize length, volume)
            // Note: MfeFeatureExtractor handles preemphasis internally
            val preprocessed = preprocessor.preprocess(audioSamples)

            // Steps 2+3 hold scoringLock — see its KDoc. Both the extractor and the interpreter
            // carry reusable scratch state, and scoreWindowEndingAt() reaches them from another
            // thread.
            val mfeStartTime = System.nanoTime()
            var mfeTimeMs = 0L
            var inferenceTimeMs = 0L
            val result = synchronized(scoringLock) {
                val features = mfeExtractor.extract(preprocessed)
                mfeTimeMs = (System.nanoTime() - mfeStartTime) / 1_000_000
                val inferenceStartTime = System.nanoTime()
                val r = tfliteClassifier?.classify(features)
                inferenceTimeMs = (System.nanoTime() - inferenceStartTime) / 1_000_000
                r
            }
            val totalMs = mfeTimeMs + inferenceTimeMs

            if (result == null) {
                Log.e(TAG, "TFLite classification failed for chunk #$chunkCount")
                return
            }

            // Get scores from result
            val heyDashieScore = result.wakeWordScore
            val unknownScore = result.scores["unknown"] ?: 0f

            // Log when hey_dashie score is above 50% (only log high-confidence detections)
            if (heyDashieScore >= 0.50) {
                Log.d(TAG, "hey_dashie: ${"%.0f".format(heyDashieScore * 100)}%")
            }

            // Determine detection type early so sample collector can use it
            val wouldTriggerPrimary = heyDashieScore >= EdgeImpulseConfig.DETECTION_THRESHOLD
            val currentDetectionType = if (wouldTriggerPrimary) "primary" else "none"

            // Live sample collection for training (beta feature)
            // Collector handles all filtering (threshold, cooldown, sampling rate)
            // Heavy work runs on background thread to keep detection loop fast
            //
            // This gate is DERIVED from the collector's own floor, not a second constant.
            // It used to be a hardcoded 0.35f sitting next to the collector's fixed 0.50f;
            // the pair silently disagreed, and when detection was dropped to 0.35 the higher
            // of the two won and every false wake in 0.35..0.50 went uncollected. One source
            // of truth now — see LiveSampleCollector.effectiveFloor.
            val collectionFloor = sampleCollector?.effectiveFloor ?: LiveSampleCollector.ABSOLUTE_FLOOR
            if (heyDashieScore >= collectionFloor) {
                if (sampleCollector == null) {
                    Log.w(TAG, "📦 Sample collection SKIPPED: sampleCollector is NULL (score=${"%.2f".format(heyDashieScore)})")
                } else {
                    Log.d(TAG, "📦 Sample collection: calling maybeCollectSample (score=${"%.2f".format(heyDashieScore)}, enabled=${sampleCollector?.enabled})")
                    sampleCollector?.maybeCollectSample(
                        score = heyDashieScore,
                        bufferPosition = sharedBuffer!!.getCurrentPosition(),
                        maxAbs = maxAbs,
                        rms = rms,
                        detectionType = currentDetectionType
                    )
                }
            }

            // Track max values for heartbeat
            if (heyDashieScore > maxConfidenceSinceHeartbeat) {
                maxConfidenceSinceHeartbeat = heyDashieScore
            }
            if (maxAbs > maxVolumeSinceHeartbeat) {
                maxVolumeSinceHeartbeat = maxAbs
            }

            // Send heartbeat every 5 chunks (500ms = 2x per second)
            heartbeatChunkCounter++
            if (heartbeatChunkCounter >= HEARTBEAT_INTERVAL_CHUNKS) {
                onHeartbeat?.invoke(maxConfidenceSinceHeartbeat, maxVolumeSinceHeartbeat)
                // Reset for next heartbeat window
                heartbeatChunkCounter = 0
                maxConfidenceSinceHeartbeat = 0f
                maxVolumeSinceHeartbeat = 0f
            }

            // Step 3: Check for detection.
            // Single operating point: the wake word fires when one window clears
            // DETECTION_THRESHOLD (sensitivity-ladder controlled). EI 4.0 is a single
            // well-trained engine, so there is no sub-threshold rescue path.
            if (wouldTriggerPrimary) {
                val currentTime = System.currentTimeMillis()

                // Standing rule 2: no silent filter. Marked `DEBOUNCE:`, NOT `DROP:`, and the
                // distinction is load-bearing — this detector re-classifies overlapping 1 s
                // windows every 100 ms, so ONE wake word legitimately scores above threshold
                // several times in a row. Calling that a drop would put 5–8 `DROP:` lines beside
                // every healthy wake and destroy the signal that a discarded-wake marker carries
                // (`rearm-race.sh` reads exactly that grep to tell "silently lost" from "lost but
                // reported"). `DROP:` means a decision was thrown away; `DEBOUNCE:` means one
                // decision was reported once.
                // Once per cooldown EPISODE, keyed on the detection being debounced against —
                // one healthy wake spans 5–8 overlapping windows, and a line for each is noise.
                if (currentTime - lastDetectionTime < cooldownMs &&
                    lastDebounceLoggedFor != lastDetectionTime
                ) {
                    lastDebounceLoggedFor = lastDetectionTime
                    Log.d(TAG, "DEBOUNCE: further windows suppressed by cooldown for " +
                            "${cooldownMs}ms — same utterance (first re-cross at " +
                            "${currentTime - lastDetectionTime}ms, " +
                            "${"%.0f".format(heyDashieScore * 100)}%)")
                }

                // Check cooldown
                if (currentTime - lastDetectionTime >= cooldownMs) {
                    lastDetectionTime = currentTime

                    // Mark buffer position for STT to start reading from
                    // Back up by ~100ms (1600 samples at 16kHz) to catch the start of
                    // commands spoken immediately after the wake word.
                    // Kept small to avoid capturing "Dashie" itself in the transcript.
                    val currentPosition = sharedBuffer!!.markPosition()
                    val preRollSamples = 1600L  // 100ms pre-roll
                    val bufferPosition = (currentPosition - preRollSamples).coerceAtLeast(0)

                    Log.i(TAG, "🎯 WAKE WORD DETECTED (${"%.0f".format(heyDashieScore * 100)}%)")

                    // Publish this window's raw level so a DualEngineDetector gate line can carry
                    // the same audio stats without recomputing them.
                    lastDetectionRms = rms
                    lastDetectionPeak = maxAbs

                    WakeSignalProbe.emit(
                        context = context,
                        engine = "ei",
                        mode = probeMode,
                        trigger = isTriggerAuthority,
                        confidence = heyDashieScore,
                        eiConfidence = heyDashieScore,
                        mwwConfidence = null,
                        rms = rms,
                        peak = maxAbs,
                        noiseFloor = noiseFloorTracker.floor(currentTime),
                        floorSamples = noiseFloorTracker.settledCount(currentTime),
                        bufferPosition = bufferPosition,
                    )

                    // Pass buffer position along with confidence for STT to use
                    onWakeWordDetected?.invoke(heyDashieScore, bufferPosition)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio chunk #$chunkCount", e)
            onError?.invoke("Processing error: ${e.message}")
        }
    }

    /**
     * Process audio chunk (public API for external use)
     *
     * @param audioSamples Raw audio samples (16000 samples at 16kHz)
     * @return Confidence score (0.0-1.0) if detected, null otherwise
     */
    /**
     * Score the 1-second window ENDING at [endPosition] and return the wake-word confidence, or
     * null when that audio is no longer in the ring.
     *
     * ## Why this exists — the re-arm race's second half
     *
     * This detector only ever reads the LIVE head (`readWindow`), and only ever moves forward. So
     * its reach into the past is exactly one window, from wherever "now" is. A wake word that
     * finished more than a second before a re-arm is therefore **structurally invisible to it** —
     * no threshold, ordering or timing change can recover it.
     *
     * That is what made the 2026-08-23 back-fill fix INVERT the race instead of closing it
     * (measured 5/5): `MicroWakeWordDetector` gained the ability to score back-filled audio, fired
     * at 100%, and then had nothing to pair with, because EI could not reach the same audio. Same
     * dropped wake as before, opposite leg. The measured signature was unambiguous — MWW 100% at
     * re-arm, orphaned; EI 68% ~900 ms later, orphaned in turn.
     *
     * 🎯 **A pull, not a scan.** The alternative was to give this detector its own back-fill cursor
     * and let its loop walk the missed span. That costs ~13 inferences per re-arm and re-opens a
     * timing race — MWW burst-processes its back-fill in ~15 ms, so a walking EI can easily land
     * outside the 500 ms agreement window (T measured exactly that, at ~900 ms). Scoring ONE
     * window, at the position MWW already flagged, is a single inference and cannot drift: both
     * engines are then judged on **the identical audio** rather than on two detections that have
     * to arrive close enough together in wall-clock time.
     *
     * ⚠️ **Deliberately side-effect-free.** No cooldown stamp, no heartbeat, no sample collection,
     * no `lastDetection*` publish, no callback. It answers a question and changes nothing — so it
     * cannot double-fire, cannot disturb the live loop running on another thread, and cannot
     * quietly alter the measured operating point of the ordinary gate.
     */
    fun scoreWindowEndingAt(endPosition: Long): Float? {
        val buffer = sharedBuffer ?: return null
        if (!isNativeInitialized) return null
        val window = EdgeImpulseConfig.WINDOW_SIZE_SAMPLES
        val start = endPosition - window
        if (start < 0) return null
        // The ring re-bases an overwritten request rather than failing, and MfeFeatureExtractor
        // requires EXACTLY one window — so verify the span is still resident instead of scoring
        // whatever comes back and reporting it as if it were the audio that was asked for.
        if (!buffer.isPositionValid(start) || !buffer.isPositionValid(endPosition)) return null
        val samples = buffer.readFrom(start, window)
        if (samples.size != window) return null
        return try {
            synchronized(scoringLock) {
                val features = mfeExtractor.extract(preprocessor.preprocess(samples))
                tfliteClassifier?.classify(features)?.wakeWordScore
            }
        } catch (e: Exception) {
            Log.w(TAG, "DROP: retro-score at $endPosition failed (${e.message}) — no corroboration")
            null
        }
    }

    fun processAudioChunk(audioSamples: FloatArray): Float? {
        processAudioChunkInternal(audioSamples)
        return null  // Detection handled via callback
    }

    /**
     * Set detection threshold
     * @param threshold Confidence threshold (0.0-1.0)
     */
    override fun setThreshold(threshold: Float) {
        val clampedThreshold = threshold.coerceIn(0.0f, 1.0f)
        EdgeImpulseConfig.DETECTION_THRESHOLD = clampedThreshold
        Log.d(TAG, "Detection threshold set to: ${"%.2f".format(clampedThreshold)} (${(clampedThreshold * 100).toInt()}%)")
    }

    /**
     * Get current detection threshold
     * @return Current threshold (0.0-1.0)
     */
    override fun getThreshold(): Float {
        return EdgeImpulseConfig.DETECTION_THRESHOLD
    }

    /**
     * Clean up resources
     */
    override fun close() {
        stop()
        tfliteClassifier?.close()
        tfliteClassifier = null
        isNativeInitialized = false
        sharedBuffer = null
        Log.d(TAG, "Edge Impulse detector closed (Kotlin MFE+TFLite)")
    }

    /**
     * Get a status string for memory diagnostics.
     * Includes running state and chunk count.
     */
    override fun getMemoryStatus(): String {
        val state = when {
            !isNativeInitialized -> "not_init"
            isRunning.get() -> "running"
            else -> "stopped"
        }
        return "state=$state chunks=$chunkCount"
    }
}

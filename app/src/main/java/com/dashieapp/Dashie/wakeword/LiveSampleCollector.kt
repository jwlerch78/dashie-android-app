package com.dashieapp.Dashie.wakeword

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import com.dashieapp.Dashie.wakeword.edgeimpulse.EdgeImpulseConfig
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * LiveSampleCollector
 *
 * Collects audio samples during normal wake word detection for model training.
 * This is a temporary beta feature for collecting training data.
 *
 * PERFORMANCE: Heavy work (WAV creation, metadata, callback) runs on a
 * background executor to avoid impacting detection loop timing.
 *
 * Collection Strategy:
 * - Collects samples when confidence score >= [effectiveFloor], which DERIVES from
 *   the runtime detection threshold rather than sitting beside it (see below).
 * - Near-miss (floor..DETECTION_THRESHOLD): 100% collection rate
 * - Triggered low (DETECTION_THRESHOLD..0.74): 100% collection rate
 * - Triggered high (0.75+): 100% collection rate (beta)
 *
 * WHY THE FLOOR IS DERIVED (2026-08-17). [collectionThreshold] used to be a fixed
 * 0.50 constant while [EdgeImpulseConfig.DETECTION_THRESHOLD] is runtime-configurable.
 * Dropping detection to 0.35 on a dev device therefore fired the wake word on scores
 * the collector would never upload — every false wake in 0.35..0.50 woke the device and
 * left no evidence. It showed up in the data as a hard clip: the minimum confidence in
 * the entire August corpus was exactly 0.500 on every device. Lowering sensitivity must
 * never blind the collector to the band it just opened, so the floor now tracks the
 * threshold down. At the default 0.80 the floor still resolves to 0.50 — unchanged for
 * the fleet, which matters because the server daily cap is shared.
 *
 * Safeguards:
 * - Minimum 10 seconds between collections
 * - Maximum 50 samples per session
 * - Heavy work offloaded to background thread
 */
class LiveSampleCollector(
    private val sharedBuffer: SharedAudioBuffer,
    // Optional — used to persist the per-device lifetime ambient-capture count so the
    // "trickle" quota survives reboots. Without it, the ambient cap degrades to per-process.
    private val context: android.content.Context? = null
) {
    private val TAG = "LiveSampleCollector"

    // Configuration
    var enabled: Boolean = false

    /**
     * Upper bound on the collection floor. The floor is never HIGHER than this, and
     * [effectiveFloor] pulls it lower when detection sensitivity is raised. Kept as a
     * settable var so a future remote-config can tighten the budget without touching
     * the derivation.
     */
    var collectionThreshold: Float = 0.50f

    /**
     * The floor actually applied, recomputed on every read because
     * [EdgeImpulseConfig.DETECTION_THRESHOLD] is a runtime-mutable var.
     *
     *   detection 0.80 -> 0.50 (capped by collectionThreshold — the fleet default, unchanged)
     *   detection 0.55 -> 0.45
     *   detection 0.35 -> 0.25 (clamped by ABSOLUTE_FLOOR)
     *
     * This is the ONE source of truth for the floor: EdgeImpulseDetector gates its call
     * into [maybeCollectSample] on this same value, so the two can't drift apart.
     */
    val effectiveFloor: Float
        get() = minOf(
            collectionThreshold,
            EdgeImpulseConfig.DETECTION_THRESHOLD - FLOOR_MARGIN
        ).coerceAtLeast(ABSOLUTE_FLOOR)

    var minIntervalMs: Long = 5_000  // 5 seconds between samples - catch frustrated repeat attempts
    // Kiosk devices run one "session" for days, so a small session cap silently
    // ends collection; the server's daily rate limit is the real ceiling.
    var maxSamplesPerSession: Int = 500

    // Model info (set by EdgeImpulseDetector when model is loaded)
    var modelId: String = "unknown"
    var modelVersion: String = "unknown"

    // Audio configuration
    private val sampleRate = 16000
    private val preRollSamples = 8000   // 0.5 seconds (unused, kept for reference)
    private val windowSamples = 48000   // 3.0 seconds total - enough for slower speakers

    // Tail capture (July 2026 fix). EI 4.0 fires mid-phrase — on "hey dash-" — so snapshotting
    // the buffer at the trigger instant clips the "-ie" tail off every field positive, which
    // poisons them as training data. Fix: keep the 3s window but slide it forward 1s in time —
    // it now ENDS 1s AFTER the trigger point (2s pre-roll + 1s post-trigger), and the read is
    // deferred so those trailing samples have been written. The audio buffer keeps filling after
    // a trigger (AudioCaptureService is a continuous single writer that STT also reads from), so
    // the extra second is reliably present ~1s later.
    private val tailCaptureSamples = 16000    // 1.0s of audio captured AFTER the trigger point
    private val tailCaptureDelayMs = 1200L    // must exceed tailCaptureSamples/16kHz (1000ms)

    // Sampling rates by score tier
    // TODO: Set to 0.25f for production to avoid flooding with similar samples
    private val highConfidenceSamplingRate = 1.0f  // 100% during beta testing

    // Ambient "trickle" collection (July 2026). EI 4.0 rests at 0.50–0.75 on a dead-silent
    // room (~-74 dBFS), which floods the near-miss budget with silence. Those clips ARE useful
    // silence negatives for 4.0.1, but we only want a diverse HANDFUL per device — diversity
    // comes from many rooms each donating a few, not one room donating hundreds. So: classify a
    // capture as ambient by energy, then cap it per-device-lifetime and space captures out.
    private val ambientRmsFloor = 0.0056f              // ~-45 dBFS (float RMS): below = ambient
    private val ambientLifetimeCap = 12                // per-device lifetime ambient captures
    private val ambientMinIntervalMs = 20 * 60 * 1000L // 20 min between ambient captures
    private var lastAmbientCollectionTime: Long = 0
    private var ambientCountCache: Int = -1            // -1 = not yet loaded from prefs

    private val collectorPrefs by lazy {
        context?.getSharedPreferences("wake_word_collector", android.content.Context.MODE_PRIVATE)
    }

    // State
    private var lastCollectionTime: Long = 0
    private var sessionSampleCount: Int = 0

    // Peak tracking - wait for score to peak before collecting
    // This ensures we collect the BEST sample, not an early weak one
    private var peakScore: Float = 0f
    private var peakBufferPosition: Long = 0
    private var peakMaxAbs: Float = 0f
    private var peakRms: Float = 0f
    private var peakDetectionType: String = "primary"
    private var isTracking: Boolean = false
    private var consecutiveDeclines: Int = 0
    private val DECLINES_TO_CONFIRM_PEAK = 2  // Collect after 2 consecutive declining scores

    companion object {
        /** How far below the detection threshold the collection floor sits. */
        const val FLOOR_MARGIN = 0.10f

        /** The floor never drops below this, however low sensitivity is set. */
        const val ABSOLUTE_FLOOR = 0.25f
    }

    // Background processing (keeps detection loop fast)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Callbacks
    var onSampleCollected: ((wavData: ByteArray, metadata: String) -> Unit)? = null

    /**
     * Called from EdgeImpulseDetector for every chunk with score >= threshold.
     *
     * FAST PATH: Only buffer read happens here (~0.5ms).
     * Heavy work (WAV creation) runs on background executor.
     *
     * @param score The wake word confidence score (0.0-1.0)
     * @param bufferPosition Current position in the SharedAudioBuffer
     * @param maxAbs Peak audio amplitude
     * @param rms RMS energy level
     */
    fun maybeCollectSample(
        score: Float,
        bufferPosition: Long,
        maxAbs: Float,
        rms: Float,
        detectionType: String = "primary"
    ) {
        if (!enabled) {
            return
        }

        // Check cooldown and session limits first
        if (!shouldStartTracking(score)) {
            // If we were tracking and score dropped, check if we should collect the peak
            if (isTracking && score < peakScore) {
                consecutiveDeclines++
                if (consecutiveDeclines >= DECLINES_TO_CONFIRM_PEAK) {
                    Log.d(TAG, "📦 Peak confirmed after $consecutiveDeclines declines, collecting at score=${"%.2f".format(peakScore)}")
                    collectPeakSample()
                }
            }
            return
        }

        // Score is above threshold and we're not in cooldown
        if (!isTracking) {
            // Start tracking a new potential sample
            isTracking = true
            consecutiveDeclines = 0
            peakScore = score
            peakBufferPosition = bufferPosition
            peakMaxAbs = maxAbs
            peakRms = rms
            peakDetectionType = detectionType
            Log.d(TAG, "📦 Started tracking peak at score=${"%.2f".format(score)} [$detectionType]")

            // If this is a triggered detection, collect immediately
            // because the detection loop will stop and we won't see declining scores
            if (score >= EdgeImpulseConfig.DETECTION_THRESHOLD || detectionType == "mww") {
                Log.d(TAG, "📦 Detection triggered [$detectionType] - collecting immediately")
                collectPeakSample()
            }
        } else if (score > peakScore) {
            // New peak - update tracking
            consecutiveDeclines = 0
            peakScore = score
            peakBufferPosition = bufferPosition
            peakMaxAbs = maxAbs
            peakRms = rms
            peakDetectionType = detectionType
            Log.d(TAG, "📦 New peak: score=${"%.2f".format(score)} [$detectionType]")

            // If this is a triggered detection, collect immediately
            if (score >= EdgeImpulseConfig.DETECTION_THRESHOLD || detectionType == "mww") {
                Log.d(TAG, "📦 Detection triggered [$detectionType] - collecting immediately")
                collectPeakSample()
            }
        } else {
            // Score declining from peak
            consecutiveDeclines++
            Log.d(TAG, "📦 Score declining ($consecutiveDeclines): ${"%.2f".format(score)} < peak ${"%.2f".format(peakScore)}")
            if (consecutiveDeclines >= DECLINES_TO_CONFIRM_PEAK) {
                Log.d(TAG, "📦 Peak confirmed, collecting sample")
                collectPeakSample()
            }
        }
    }

    /**
     * Collect the sample at the tracked peak position
     */
    private fun collectPeakSample() {
        if (!isTracking) return

        val score = peakScore
        val bufferPosition = peakBufferPosition
        val maxAbs = peakMaxAbs
        val rms = peakRms
        val detectionType = peakDetectionType

        // Reset tracking state
        isTracking = false
        consecutiveDeclines = 0
        peakScore = 0f
        peakDetectionType = "primary"

        // Apply sampling rate for high-confidence samples
        if (!shouldCollectPeak(score)) {
            return
        }

        // Calculate collection tier for metadata
        val collectionTier = when {
            score >= 0.75f -> "triggered_high"
            score >= EdgeImpulseConfig.DETECTION_THRESHOLD -> "triggered_low"
            else -> "near_miss"
        }

        // Ambient "trickle" gate: dead-silent/ambient clips (below the speech energy floor)
        // are throttled to a small, spaced-out per-device lifetime quota so a quiet room can't
        // flood the budget. Real speech (rms above the floor) is never throttled here.
        val isAmbient = rms < ambientRmsFloor
        if (isAmbient) {
            if (ambientLifetimeCount() >= ambientLifetimeCap) {
                Log.d(TAG, "🤫 Ambient skipped — lifetime quota ($ambientLifetimeCap) reached (rms=${"%.4f".format(rms)})")
                return
            }
            val now = System.currentTimeMillis()
            if (lastAmbientCollectionTime != 0L && now - lastAmbientCollectionTime < ambientMinIntervalMs) {
                val minsLeft = (ambientMinIntervalMs - (now - lastAmbientCollectionTime)) / 60000
                Log.d(TAG, "🤫 Ambient skipped — spacing (${minsLeft}min left, rms=${"%.4f".format(rms)})")
                return
            }
        }

        // Commit to collection NOW (synchronously) so a duplicate trigger during the
        // tail-capture delay can't double-collect this utterance.
        lastCollectionTime = System.currentTimeMillis()
        sessionSampleCount++
        val sampleNumber = sessionSampleCount

        if (isAmbient) {
            lastAmbientCollectionTime = lastCollectionTime
            val newCount = incrementAmbientLifetimeCount()
            Log.i(TAG, "🤫 Ambient capture kept ($newCount/$ambientLifetimeCap lifetime, rms=${"%.4f".format(rms)})")
        }

        // Capture values for background thread
        val wasTriggered = score >= EdgeImpulseConfig.DETECTION_THRESHOLD
        val threshold = EdgeImpulseConfig.DETECTION_THRESHOLD
        val timestamp = System.currentTimeMillis()

        // Slide the 3s window forward: it ENDS tailCaptureSamples after the trigger point so the
        // "…ie" tail of "hey dashie" is included (the model can fire on "hey dash-"). The read is
        // deferred by tailCaptureDelayMs so those trailing samples are already in the ring buffer.
        val windowStartPos = (bufferPosition + tailCaptureSamples - windowSamples).coerceAtLeast(0)

        Log.i(TAG, "📦 Queuing sample #$sampleNumber | peak_score=${"%.2f".format(score)} | tier=$collectionTier | triggered=$wasTriggered | type=$detectionType (tail-delayed ${tailCaptureDelayMs}ms)")

        mainHandler.postDelayed({
            // Positional read on main thread (~0.5ms). readFrom returns fewer than windowSamples
            // only near session start; keep the real audio at the front and zero-pad the tail so
            // WAV length stays fixed at 3s.
            val raw = sharedBuffer.readFrom(windowStartPos, windowSamples)
            val audioSamples = if (raw.size >= windowSamples) raw else FloatArray(windowSamples).also {
                System.arraycopy(raw, 0, it, 0, raw.size)
            }

            // HEAVY WORK: Run on background thread to keep detection loop fast
            executor.execute {
                try {
                    // Convert FloatArray to ShortArray for WAV creation
                    val shortSamples = floatsToShorts(audioSamples)

                    // Convert to WAV (~1ms)
                    val wavData = createWavFile(shortSamples, shortSamples.size)

                    // Create metadata (~0.1ms)
                    val metadata = createMetadata(
                        timestamp = timestamp,
                        score = score,
                        wasTriggered = wasTriggered,
                        detectionThreshold = threshold,
                        detectionType = detectionType,
                        maxAbs = maxAbs,
                        rms = rms,
                        sampleNumber = sampleNumber,
                        collectionTier = collectionTier,
                        sampleCount = shortSamples.size,
                        fileSize = wavData.size
                    )

                    Log.i(TAG, "✅ Sample #$sampleNumber ready (${wavData.size} bytes)")

                    // Callback on main thread
                    mainHandler.post {
                        onSampleCollected?.invoke(wavData, metadata)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process sample #$sampleNumber", e)
                }
            }
        }, tailCaptureDelayMs)
    }

    /** Per-device lifetime count of ambient/silence captures (persisted across reboots). */
    private fun ambientLifetimeCount(): Int {
        if (ambientCountCache < 0) {
            ambientCountCache = collectorPrefs?.getInt("ambient_lifetime_count", 0) ?: 0
        }
        return ambientCountCache
    }

    private fun incrementAmbientLifetimeCount(): Int {
        val next = ambientLifetimeCount() + 1
        ambientCountCache = next
        collectorPrefs?.edit()?.putInt("ambient_lifetime_count", next)?.apply()
        return next
    }

    /**
     * Check if we should start/continue tracking a potential sample.
     * Different from shouldCollect - we don't apply sampling rate here since we want to track all peaks.
     */
    private fun shouldStartTracking(score: Float): Boolean {
        // Check session limit
        if (sessionSampleCount >= maxSamplesPerSession) {
            if (!isTracking) {
                Log.d(TAG, "Session limit reached ($maxSamplesPerSession), not starting new tracking")
            }
            return false
        }

        // Check cooldown (only if not already tracking)
        if (!isTracking) {
            val now = System.currentTimeMillis()
            if (now - lastCollectionTime < minIntervalMs) {
                return false
            }
        }

        // Check threshold
        if (score < effectiveFloor) {
            return false
        }

        return true
    }

    /**
     * Apply sampling rate - called when we're about to collect a peak sample.
     * High-confidence samples (0.75+) are sampled at 25% since we have plenty of these.
     */
    private fun shouldCollectPeak(score: Float): Boolean {
        if (score >= 0.75f) {
            if (Math.random() > highConfidenceSamplingRate) {
                Log.d(TAG, "High-confidence sample skipped (sampling at ${(highConfidenceSamplingRate * 100).toInt()}%)")
                return false
            }
        }
        return true
    }

    /**
     * Convert FloatArray [-1, 1] to ShortArray for WAV encoding.
     */
    private fun floatsToShorts(floats: FloatArray): ShortArray {
        return ShortArray(floats.size) { i ->
            (floats[i] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /**
     * Create WAV file with proper headers.
     * Reuses pattern from WakeWordSampleRecorder.
     */
    private fun createWavFile(audioData: ShortArray, sampleCount: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val dataSize = sampleCount * (bitsPerSample / 8)
        val headerSize = 44
        val fileSize = headerSize + dataSize - 8

        val buffer = ByteBuffer.allocate(headerSize + dataSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(fileSize)
        buffer.put("WAVE".toByteArray())

        // fmt chunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)  // fmt chunk size
        buffer.putShort(1)  // PCM format
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * channels * bitsPerSample / 8)  // byte rate
        buffer.putShort((channels * bitsPerSample / 8).toShort())  // block align
        buffer.putShort(bitsPerSample.toShort())

        // data chunk
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)

        // Audio data
        for (i in 0 until sampleCount) {
            buffer.putShort(audioData[i])
        }

        return buffer.array()
    }

    /**
     * Create metadata JSON for sample.
     */
    private fun createMetadata(
        timestamp: Long,
        score: Float,
        wasTriggered: Boolean,
        detectionThreshold: Float,
        detectionType: String,
        maxAbs: Float,
        rms: Float,
        sampleNumber: Int,
        collectionTier: String,
        sampleCount: Int,
        fileSize: Int
    ): String {
        val metadata = JSONObject()

        // Device ID - required by edge function
        // Use Android ID as a stable device identifier
        val deviceId = "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}_${android.os.Build.SERIAL}".replace(" ", "_")
        metadata.put("device_id", deviceId)

        // Core info
        metadata.put("timestamp", timestamp)
        metadata.put("duration_ms", (windowSamples * 1000) / sampleRate)
        metadata.put("sample_count", sampleCount)
        metadata.put("sample_rate", sampleRate)
        metadata.put("channels", 1)
        metadata.put("bits_per_sample", 16)
        metadata.put("file_size_bytes", fileSize)

        // Detection info
        metadata.put("confidence_score", score.toDouble())
        metadata.put("detection_threshold", detectionThreshold.toDouble())
        metadata.put("was_triggered", wasTriggered)
        metadata.put("detection_type", detectionType)  // "primary" (>= threshold), "mww", or "none"
        metadata.put("collection_tier", collectionTier)
        metadata.put("session_sample_number", sampleNumber)

        // Audio characteristics
        metadata.put("max_amplitude", maxAbs.toDouble())
        metadata.put("rms_energy", rms.toDouble())

        // Device info (detailed)
        val deviceInfo = JSONObject()
        deviceInfo.put("manufacturer", android.os.Build.MANUFACTURER)
        deviceInfo.put("model", android.os.Build.MODEL)
        deviceInfo.put("android_api", android.os.Build.VERSION.SDK_INT)
        deviceInfo.put("android_version", android.os.Build.VERSION.RELEASE)
        metadata.put("device", deviceInfo)

        // Recording context
        val recordingInfo = JSONObject()
        recordingInfo.put("source", "live_detection")
        recordingInfo.put("purpose", "wake_word_training")
        recordingInfo.put("wake_word", "hey_dashie")
        metadata.put("recording", recordingInfo)

        // Model info - track which model triggered this sample
        val modelInfo = JSONObject()
        modelInfo.put("id", modelId)
        modelInfo.put("version", modelVersion)
        metadata.put("model", modelInfo)

        return metadata.toString()
    }

    /**
     * Reset session counters. Call when app goes to background or closes.
     */
    fun resetSession() {
        Log.d(TAG, "Session reset (was at $sessionSampleCount samples)")
        sessionSampleCount = 0
        lastCollectionTime = 0
        // Reset tracking state
        isTracking = false
        consecutiveDeclines = 0
        peakScore = 0f
    }

    /**
     * Get current session stats for debugging.
     */
    fun getSessionStats(): String {
        return "samples=$sessionSampleCount/$maxSamplesPerSession, enabled=$enabled, " +
            "floor=${"%.2f".format(effectiveFloor)} (cap=$collectionThreshold, " +
            "detect=${EdgeImpulseConfig.DETECTION_THRESHOLD})"
    }

    /**
     * Clean up executor. Call when shutting down.
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down LiveSampleCollector")
        executor.shutdown()
    }
}

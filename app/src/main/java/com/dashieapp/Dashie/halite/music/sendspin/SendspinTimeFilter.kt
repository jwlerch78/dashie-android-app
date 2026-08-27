package com.dashieapp.Dashie.halite.music.sendspin

import android.util.Log
import com.dashieapp.Dashie.halite.music.sendspin.Platform
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Kalman filter for time synchronization between client and server.
 *
 * Implements a 2D Kalman filter tracking:
 * - offset: Time difference between client and server clocks (microseconds)
 * - drift: Rate of clock divergence (microseconds per microsecond)
 *
 * ## Algorithm
 * Uses NTP-style measurements to estimate clock offset:
 * ```
 * offset = ((T2 - T1) + (T3 - T4)) / 2
 * ```
 * where:
 * - T1 = client_transmitted
 * - T2 = server_received
 * - T3 = server_transmitted
 * - T4 = client_received
 *
 * The Kalman filter smooths these measurements and tracks drift over time.
 *
 * ## Time Conversion
 * - Server to Client: T_client = T_server - offset
 * - Client to Server: T_server = T_client + offset
 *
 * Note: Drift is tracked but not used in time conversion (matches Python reference).
 * The sync correction feedback loop handles clock rate differences naturally through
 * sample insert/drop operations.
 *
 * Reference: aiosendspin time_sync.py
 */
class SendspinTimeFilter {

    companion object {
        // ============================================================================
        // KALMAN FILTER CONSTANTS - Detailed Documentation
        // ============================================================================
        //
        // This Kalman filter tracks two state variables:
        //   1. offset: Time difference between client and server clocks (microseconds)
        //   2. drift: Rate of clock divergence (microseconds per microsecond, i.e., dimensionless)
        //
        // The filter uses a constant-velocity model where offset evolves according to:
        //   offset(t+dt) = offset(t) + drift * dt + process_noise
        //
        // References:
        // - NTP clock discipline algorithm (RFC 5905)
        // - Kalman filter theory: Welch & Bishop, "An Introduction to the Kalman Filter"
        // - aiosendspin Python reference implementation (time_sync.py)
        // ============================================================================

        // ----------------------------------------------------------------------------
        // BASE_PROCESS_NOISE_OFFSET: Base variance for adaptive process noise (us^2/s)
        // ----------------------------------------------------------------------------
        // This is the baseline Q_offset value. The actual process noise is dynamically
        // scaled based on observed innovation variance vs expected variance.
        //
        // On stable WiFi: adaptive Q stays near BASE (smooth tracking)
        // On jittery cellular: adaptive Q rises to BASE * ADAPTIVE_Q_MAX (faster adaptation)
        // During handoff: Q spikes to handle step changes, then settles back
        // ----------------------------------------------------------------------------
        private const val BASE_PROCESS_NOISE_OFFSET = 100.0

        // Adaptive process noise bounds (multipliers on BASE_PROCESS_NOISE_OFFSET)
        private const val ADAPTIVE_Q_MIN = 0.5    // Minimum: half of base (very stable network)
        private const val ADAPTIVE_Q_MAX = 5.0    // Maximum: 5x base (high-jitter/handoff)
        private const val INNOVATION_WINDOW_SIZE = 20  // ~5 seconds at 4Hz burst rate

        // ----------------------------------------------------------------------------
        // PROCESS_NOISE_DRIFT: Variance added to drift estimate per second ((us/us)^2/s)
        // ----------------------------------------------------------------------------
        // Physical meaning: Models how much we expect the clock frequency ratio to
        // change over time. Quartz oscillators have stable short-term frequency but
        // can drift due to temperature changes, aging, and voltage variations.
        //
        // Value rationale: Set to 0.0 to match the web player. Phone crystal
        // oscillators have extremely stable short-term frequency, so adding drift
        // noise just makes the estimate noisy on high-jitter networks. The offset
        // process noise alone provides sufficient adaptation. With zero drift noise,
        // the drift estimate converges faster and stays stable.
        //
        // If too high: Drift estimate becomes noisy, oscillating wildly and causing
        //              the offset prediction to overshoot/undershoot. This is
        //              especially problematic on cellular networks with high RTT
        //              variance.
        // ----------------------------------------------------------------------------
        private const val PROCESS_NOISE_DRIFT = 0.0

        // ----------------------------------------------------------------------------
        // MIN_MEASUREMENTS: Minimum measurements before isReady becomes true
        // ----------------------------------------------------------------------------
        // Physical meaning: Number of round-trip time measurements needed before
        // the filter has enough information for reliable time conversion.
        //
        // Value rationale: 2 measurements provide:
        //   - 1st measurement: Initializes offset
        //   - 2nd measurement: First drift estimate and basic convergence
        //
        // ARCHITECTURAL NOTE (2025-02): With the sync error decoupling fix,
        // Kalman filter learning no longer triggers correction noise. This means:
        //   - We can safely start playback earlier (2 measurements instead of 4)
        //   - The filter continues refining in the background
        //   - Corrections only happen due to actual DAC clock drift
        //
        // Previously (4 measurements), we needed the filter to converge before
        // playback because filter adjustments caused sync error noise that
        // triggered unnecessary sample insert/drop corrections. Now the sync
        // error is calculated entirely in client time, independent of Kalman.
        //
        // At ~500ms burst interval, this reduces startup by ~1 second.
        // If too low (1): Only offset, no drift estimate at all.
        // If too high: Longer delay before audio starts.
        // ----------------------------------------------------------------------------
        private const val MIN_MEASUREMENTS = 2

        // ----------------------------------------------------------------------------
        // MIN_MEASUREMENTS_FOR_CONVERGENCE: Measurements for high-confidence sync
        // ----------------------------------------------------------------------------
        // Physical meaning: Number of measurements needed before we consider the
        // filter "converged" to a stable, high-quality estimate.
        //
        // Value rationale: 5 measurements provide enough statistical samples for
        // the Kalman gain to stabilize. After ~5 iterations, the covariance matrix
        // typically settles to its steady-state value. This matches behavior
        // observed in the Python reference implementation.
        //
        // If too low: isConverged triggers early, possibly before the filter has
        //             settled. May cause premature "sync locked" indications.
        // If too high: Takes too long to report convergence. UI may show "syncing"
        //              status longer than necessary.
        // ----------------------------------------------------------------------------
        private const val MIN_MEASUREMENTS_FOR_CONVERGENCE = 5

        // ----------------------------------------------------------------------------
        // MAX_ERROR_FOR_CONVERGENCE_US: Maximum acceptable uncertainty for convergence
        // ----------------------------------------------------------------------------
        // Physical meaning: The standard deviation threshold (in microseconds) below
        // which we consider the sync estimate reliable. This is derived from the
        // covariance matrix diagonal element P[0,0].
        //
        // Value rationale: 10,000us (10ms) is chosen because:
        //   - Human perception: Audio sync errors <20ms are generally imperceptible
        //   - Sample accuracy: At 48kHz, 10ms = 480 samples - well within correction range
        //   - Network reality: WiFi jitter is often 5-20ms, so <10ms uncertainty
        //     indicates the filter has successfully averaged out the noise.
        //
        // If too low: Convergence may never be reached on high-jitter networks.
        //             Filter perpetually reports "not converged".
        // If too high: Reports convergence even when sync quality is poor.
        //              Playback may have audible glitches despite "converged" status.
        //
        // The threshold is adaptive: on high-jitter networks (stabilityScore >> 1.0)
        // it widens linearly toward MAX_ERROR_FOR_CONVERGENCE_HIGH_JITTER_US.
        // This lets the filter report convergence on cellular where the steady-state
        // error is higher but still perfectly usable for audio sync.
        // ----------------------------------------------------------------------------
        private const val MAX_ERROR_FOR_CONVERGENCE_US = 10_000L
        private const val MAX_ERROR_FOR_CONVERGENCE_HIGH_JITTER_US = 20_000L

        // ----------------------------------------------------------------------------
        // FORGETTING_THRESHOLD: Absolute residual threshold for step change detection
        // ----------------------------------------------------------------------------
        // Physical meaning: When |residual| > FORGETTING_THRESHOLD * maxError, we
        // suspect a step change in offset (e.g., network route change) and gently
        // inflate covariance to adapt faster. Uses absolute threshold (matching the
        // web player) rather than normalized threshold.
        //
        // Value rationale: 0.75 means the residual must exceed 75% of the
        // measurement uncertainty (sqrt(variance) ~ RTT/2) to trigger forgetting.
        // This naturally scales with network conditions:
        //   - WiFi (RTT ~20ms): threshold ~ 7.5ms - rarely triggered by jitter
        //   - Cell (RTT ~100ms): threshold ~ 37ms - only genuine step changes
        //
        // Unlike the previous normalized approach, this doesn't become more
        // sensitive as the filter converges (which caused the instability loop).
        //
        // If too low: Normal jitter triggers forgetting, preventing convergence.
        // If too high: Genuine step changes are ignored, slow to reconverge.
        // ----------------------------------------------------------------------------
        private const val FORGETTING_THRESHOLD = 0.75

        // ----------------------------------------------------------------------------
        // MAX_DRIFT: Maximum allowed drift magnitude (dimensionless, us/us)
        // ----------------------------------------------------------------------------
        // Physical meaning: Hard limit on clock frequency difference. +/-5e-4 means
        // +/-500 parts per million (ppm), or +/-0.05% frequency difference.
        //
        // Value rationale: 500 ppm is generous for real hardware:
        //   - Typical quartz oscillators: 20-100 ppm accuracy
        //   - Phone crystals: Often 10-50 ppm
        //   - Temperature effects: Can add 10-20 ppm variation
        // The limit prevents the drift term from growing unbounded due to
        // measurement noise, which would cause offset predictions to diverge.
        // At 500 ppm, over 1 hour the drift would only contribute 1.8 seconds
        // of offset change - well within correctable range.
        //
        // If too low: May clip legitimate drift, causing persistent sync error
        //             if client and server clocks genuinely differ by more.
        // If too high: Noisy drift estimates can cause wild offset predictions.
        //              Filter may oscillate around true value.
        // ----------------------------------------------------------------------------
        private const val MAX_DRIFT = 5e-4

        private const val TAG = "SendspinTimeFilter"

        // ----------------------------------------------------------------------------
        // Convergence-Aware Warmup: Ends based on filter state, not fixed count
        // ----------------------------------------------------------------------------
        // MIN_WARMUP: Absolute minimum before forgetting can activate (prevents
        //   premature activation while Kalman gain is still large)
        // MAX_WARMUP: Absolute maximum before we force-enable forgetting (prevents
        //   indefinite warmup on very noisy networks)
        // WARMUP_CONVERGENCE_THRESHOLD_US: If error drops below this, warmup ends
        //   early (filter has converged, safe to enable adaptive mechanisms)
        //
        // Behavior:
        //   WiFi: Warmup ends at ~20 measurements (~5s) when error < threshold
        //   Cellular: Warmup may extend to 100 measurements (~25s) for convergence
        // ----------------------------------------------------------------------------
        private const val MIN_WARMUP = 20
        private const val MAX_WARMUP = 100
        private const val WARMUP_CONVERGENCE_THRESHOLD_US = 15_000L  // 15ms

        // ----------------------------------------------------------------------------
        // Outlier Pre-Rejection: Protects filter from cellular spikes
        // ----------------------------------------------------------------------------
        // Measurements that deviate too far from recent history are rejected before
        // reaching the Kalman filter. Uses median + IQR for robust statistics.
        //
        // OUTLIER_WINDOW_SIZE: Number of recent accepted offsets to track
        // OUTLIER_IQR_MULTIPLIER: How many IQRs from median to allow
        // MIN_OUTLIER_MEASUREMENTS: Accept everything during early warmup
        // ----------------------------------------------------------------------------
        private const val OUTLIER_WINDOW_SIZE = 10
        private const val OUTLIER_IQR_MULTIPLIER = 3.0
        private const val MIN_OUTLIER_MEASUREMENTS = 5

        // ----------------------------------------------------------------------------
        // FORGETTING_FACTOR: Covariance inflation factor for step changes (lambda)
        // ----------------------------------------------------------------------------
        // Physical meaning: When a step change is detected (residual exceeds
        // FORGETTING_THRESHOLD * maxError), covariance is multiplied by lambda^2 to
        // allow faster adaptation. Unlike the previous approach, this is NOT
        // applied continuously - only when the absolute threshold is exceeded.
        //
        // Value rationale: lambda = 1.001 gives gentle inflation (~0.2% per trigger):
        //   - Matches the web player's approach (1.001^2 on threshold exceed)
        //   - Gentle enough that repeated triggers don't cause explosion
        //   - Combined with the absolute threshold, provides smooth reconvergence
        //
        // The key difference from the old approach: previously lambda^2 was applied
        // EVERY update (continuous inflation), fighting against the filter's
        // natural convergence. Now it's only applied when genuinely needed.
        //
        // If too low (1.0): After a step change, filter adapts too slowly.
        // If too high (1.01+): Each trigger inflates too much, risking instability.
        // ----------------------------------------------------------------------------
        private const val FORGETTING_FACTOR = 1.001
    }

    // Lock for protecting filter state mutations (addMeasurement, reset, freeze, thaw).
    // Hot-path readers (serverToClient, clientToServer) use @Volatile fields instead of
    // locking to avoid blocking the audio thread.
    private val lock = Any()

    // State vector: [offset, drift]
    // @Volatile: read by audio thread (serverToClient), written under lock by IO thread
    @Volatile private var offset: Double = 0.0
    private var drift: Double = 0.0

    // Covariance matrix (2x2)
    private var p00: Double = Double.MAX_VALUE  // offset variance
    private var p01: Double = 0.0               // offset-drift covariance
    private var p10: Double = 0.0               // drift-offset covariance
    private var p11: Double = 1e-6              // drift variance

    // Timing state
    private var lastUpdateTime: Long = 0
    private var measurementCount: Int = 0

    // Adaptive process noise: tracks innovation (residual) magnitudes
    private val innovationWindow = DoubleArray(INNOVATION_WINDOW_SIZE)
    private var innovationWindowIndex = 0
    private var innovationWindowCount = 0
    private var adaptiveProcessNoise = BASE_PROCESS_NOISE_OFFSET

    // Outlier pre-rejection: tracks recent accepted offset measurements
    private val recentOffsets = DoubleArray(OUTLIER_WINDOW_SIZE)
    private var recentOffsetsIndex = 0
    private var recentOffsetsCount = 0
    private var rejectedCount = 0  // Consecutive rejections (for forced acceptance)

    // Baseline time for relative calculations - prevents drift accumulation over long periods
    // Set when first measurement is received, used as reference point for time conversions
    private var baselineClientTime: Long = 0

    // Static delay offset for speaker synchronization (GroupSync calibration)
    // Positive = delay playback (plays later), Negative = advance (plays earlier)
    // @Volatile: read by audio thread (serverToClient), written from UI/main thread
    @Volatile private var staticDelayMicros: Long = 0

    // Convergence tracking
    private var convergenceTimeMs: Long = 0L       // Time to reach isConverged
    private var firstMeasurementTimeMs: Long = 0L  // Timestamp of first measurement
    private var hasLoggedConvergence: Boolean = false

    // Stability tracking (innovation variance ratio)
    private var stabilityScore: Double = 1.0  // Should be ~1.0 for well-tuned filter

    // Frozen state for reconnection - preserves sync across network drops
    @Volatile private var frozenState: FrozenState? = null

    private data class FrozenState(
        val offset: Double,
        val drift: Double,
        val p00: Double,
        val p01: Double,
        val p10: Double,
        val p11: Double,
        val measurementCount: Int,
        val baselineClientTime: Long,
        val recentOffsets: DoubleArray,
        val recentOffsetsIndex: Int,
        val recentOffsetsCount: Int
    )

    /**
     * Whether enough measurements have been collected for reliable time conversion.
     * This is the minimum threshold - playback can start, but may need corrections.
     */
    val isReady: Boolean
        get() = measurementCount >= MIN_MEASUREMENTS && p00.isFinite()

    /**
     * Whether the filter has converged to a high-quality sync.
     * This is stricter than isReady - requires more measurements and lower uncertainty.
     * When true, sync corrections should be minimal.
     *
     * The convergence threshold adapts to network conditions using [stabilityScore]:
     * - On low-jitter networks (stabilityScore ~1.0): threshold stays at 10ms
     * - On high-jitter networks (stabilityScore -> 5.0): threshold widens to 20ms
     * This prevents cellular networks from being stuck in "not converged" indefinitely
     * when the error has stabilised at a level that is still fine for audio sync.
     */
    val isConverged: Boolean
        get() {
            if (measurementCount < MIN_MEASUREMENTS_FOR_CONVERGENCE || !p00.isFinite()) return false
            // Scale threshold from 10ms to 20ms as stabilityScore rises from 1.0 to ADAPTIVE_Q_MAX
            val jitterFactor = ((stabilityScore - 1.0) / (ADAPTIVE_Q_MAX - 1.0)).coerceIn(0.0, 1.0)
            val threshold = MAX_ERROR_FOR_CONVERGENCE_US +
                (jitterFactor * (MAX_ERROR_FOR_CONVERGENCE_HIGH_JITTER_US - MAX_ERROR_FOR_CONVERGENCE_US)).toLong()
            return errorMicros < threshold
        }

    /**
     * Current estimated offset in microseconds.
     */
    val offsetMicros: Long
        get() = offset.toLong()

    /**
     * Estimated error (standard deviation) in microseconds.
     */
    val errorMicros: Long
        get() = if (p00.isFinite() && p00 >= 0) sqrt(p00).toLong() else Long.MAX_VALUE

    /**
     * Number of measurements collected so far.
     */
    val measurementCountValue: Int
        get() = measurementCount

    /**
     * Current drift in parts per million (ppm).
     * Positive = server clock running faster than client.
     */
    val driftPpm: Double
        get() = drift * 1_000_000.0

    /**
     * Time of last measurement update in microseconds (client time).
     */
    val lastUpdateTimeUs: Long
        get() = lastUpdateTime

    /**
     * Static delay in milliseconds for speaker synchronization.
     * Positive = delay playback (plays later), Negative = advance (plays earlier).
     * Used by GroupSync calibration tool.
     */
    var staticDelayMs: Double
        get() = staticDelayMicros / 1000.0
        set(value) {
            staticDelayMicros = (value * 1000).toLong()
        }

    /**
     * Time to reach convergence in milliseconds.
     * 0 if not yet converged.
     */
    val convergenceTimeMillis: Long
        get() = convergenceTimeMs

    /**
     * Stability score (innovation variance ratio).
     * Should be ~1.0 for a well-tuned filter.
     * < 1 = filter is over-responsive, > 1 = filter is too sluggish.
     */
    val stability: Double
        get() = stabilityScore

    /**
     * Reset the filter to initial state.
     * Thread-safe: synchronized to prevent concurrent mutation.
     */
    fun reset() = synchronized(lock) {
        offset = 0.0
        drift = 0.0
        p00 = Double.MAX_VALUE
        p01 = 0.0
        p10 = 0.0
        p11 = 1e-6
        lastUpdateTime = 0
        measurementCount = 0
        baselineClientTime = 0
        // Reset adaptive mechanisms
        innovationWindowIndex = 0
        innovationWindowCount = 0
        adaptiveProcessNoise = BASE_PROCESS_NOISE_OFFSET
        recentOffsetsIndex = 0
        recentOffsetsCount = 0
        rejectedCount = 0
        // Reset convergence tracking
        convergenceTimeMs = 0
        firstMeasurementTimeMs = 0
        hasLoggedConvergence = false
        stabilityScore = 1.0
    }

    /**
     * Whether the filter has frozen state that can be restored.
     */
    val isFrozen: Boolean
        get() = frozenState != null

    /**
     * Freeze the current filter state for reconnection.
     * Preserves the converged time sync across network drops so playback
     * can continue from buffer without losing synchronization.
     *
     * Call this when connection is lost but reconnection will be attempted.
     * Thread-safe: synchronized to capture a consistent snapshot.
     */
    fun freeze() {
        synchronized(lock) {
            if (!isReady) return

            frozenState = FrozenState(
                offset = offset,
                drift = drift,
                p00 = p00,
                p01 = p01,
                p10 = p10,
                p11 = p11,
                measurementCount = measurementCount,
                baselineClientTime = baselineClientTime,
                recentOffsets = recentOffsets.copyOf(),
                recentOffsetsIndex = recentOffsetsIndex,
                recentOffsetsCount = recentOffsetsCount
            )
        }
    }

    /**
     * Restore frozen state after reconnection.
     * Increases covariance to allow faster adaptation to potentially
     * changed network conditions while preserving the general sync estimate.
     *
     * Call this after successful reconnection, before resuming time sync.
     * Thread-safe: synchronized to prevent concurrent mutation.
     */
    fun thaw() {
        synchronized(lock) {
            val frozen = frozenState ?: return

            offset = frozen.offset
            drift = frozen.drift

            // Increase covariance by 100x to allow rapid re-convergence after
            // reconnection. The old offset is a reasonable starting point but may
            // be stale if the server restarted or network conditions changed
            // significantly. Large covariance lets the filter quickly adopt new
            // measurements while still benefiting from the prior estimate.
            p00 = frozen.p00 * 100.0
            p01 = frozen.p01 * 10.0
            p10 = frozen.p10 * 10.0
            p11 = frozen.p11 * 100.0

            // Reset measurement count to MIN_MEASUREMENTS so that:
            // 1. isReady remains true (playback continues from buffer)
            // 2. isConverged returns false (forces aggressive burst sync)
            // 3. addMeasurement() takes the Kalman update path (not init path)
            // This ensures TimeSyncManager uses fast burst parameters until
            // the filter actually reconverges with fresh measurements.
            measurementCount = MIN_MEASUREMENTS
            baselineClientTime = frozen.baselineClientTime

            // Restore outlier rejection state (offsets are still valid reference)
            frozen.recentOffsets.copyInto(recentOffsets)
            recentOffsetsIndex = frozen.recentOffsetsIndex
            recentOffsetsCount = frozen.recentOffsetsCount
            rejectedCount = 0

            // Reset innovation window (network conditions may have changed)
            innovationWindowIndex = 0
            innovationWindowCount = 0
            adaptiveProcessNoise = BASE_PROCESS_NOISE_OFFSET

            // Reset convergence tracking so it gets re-logged after re-sync
            hasLoggedConvergence = false
            convergenceTimeMs = 0
            firstMeasurementTimeMs = 0

            frozenState = null
        }
    }

    /**
     * Discard frozen state and perform full reset.
     * Call this when reconnection fails and we need to start fresh.
     * Thread-safe: synchronized to prevent concurrent mutation.
     */
    fun resetAndDiscard() = synchronized(lock) {
        frozenState = null
        // Inline reset logic here since reset() also acquires the lock (reentrant on JVM,
        // but we inline to be explicit and avoid platform-dependent reentrant behavior)
        offset = 0.0
        drift = 0.0
        p00 = Double.MAX_VALUE
        p01 = 0.0
        p10 = 0.0
        p11 = 1e-6
        lastUpdateTime = 0
        measurementCount = 0
        baselineClientTime = 0
        innovationWindowIndex = 0
        innovationWindowCount = 0
        adaptiveProcessNoise = BASE_PROCESS_NOISE_OFFSET
        recentOffsetsIndex = 0
        recentOffsetsCount = 0
        rejectedCount = 0
        convergenceTimeMs = 0
        firstMeasurementTimeMs = 0
        hasLoggedConvergence = false
        stabilityScore = 1.0
    }

    /**
     * Add a new time measurement to the filter.
     *
     * Includes outlier pre-rejection: measurements that deviate significantly from
     * recent history are rejected before reaching the Kalman filter, protecting
     * against cellular congestion spikes and handoff transients.
     *
     * Thread-safe: synchronized to prevent concurrent mutation of filter state.
     *
     * @param measurementOffset The measured offset in microseconds
     * @param maxError The maximum error (uncertainty) in microseconds
     * @param clientTimeMicros The client timestamp when measurement was taken
     * @param rtt Optional round-trip time in microseconds (ignored, kept for API compatibility)
     * @return true if measurement was accepted, false if rejected as outlier
     */
    fun addMeasurement(
        measurementOffset: Long,
        maxError: Long,
        clientTimeMicros: Long,
        rtt: Long = 0L
    ): Boolean = synchronized(lock) {
        val measurement = measurementOffset.toDouble()
        val variance = (maxError.toDouble() * maxError).coerceAtLeast(1.0)

        // Track first measurement time for convergence timing
        if (measurementCount == 0) {
            firstMeasurementTimeMs = Platform.currentTimeMillis()
        }

        when (measurementCount) {
            0 -> {
                // First measurement - initialize offset directly
                offset = measurement
                p00 = variance
                lastUpdateTime = clientTimeMicros
                baselineClientTime = clientTimeMicros  // Set baseline for relative calculations
                measurementCount = 1
                recordAcceptedOffset(measurement)
            }
            1 -> {
                // Second measurement - initialize drift covariance but NOT drift value
                // Drift = 0 initially; the Kalman filter will learn it over time.
                // DO NOT calculate drift from (measurement - offset) / dt because:
                // - Measurement noise of ~3ms with dt of ~1s gives drift error of ~3000ppm
                // - Real crystal drift is only 10-50ppm, so noise dominates completely
                // - Starting at 0 and learning is much better than starting wildly wrong
                val dt = (clientTimeMicros - lastUpdateTime).toDouble()
                if (dt > 0) {
                    // Initialize drift covariance based on how much drift could have changed
                    // Use a conservative estimate: 100ppm (0.0001) uncertainty
                    p11 = 0.0001 * 0.0001  // (100ppm)^2 variance
                }
                // Keep drift at 0 (initialized in reset())
                offset = measurement
                p00 = variance
                lastUpdateTime = clientTimeMicros
                measurementCount = 2
                recordAcceptedOffset(measurement)
            }
            else -> {
                // Outlier pre-rejection: check before feeding to Kalman filter
                if (!shouldAcceptMeasurement(measurement, maxError.toDouble())) {
                    rejectedCount++
                    return false
                }
                rejectedCount = 0

                // Steady state - full Kalman update
                kalmanUpdate(measurement, variance, clientTimeMicros)
                recordAcceptedOffset(measurement)

                // Check and log convergence
                checkConvergence()
            }
        }
        return true
    }

    /**
     * Check for convergence and log milestone.
     */
    private fun checkConvergence() {
        if (!hasLoggedConvergence && isConverged) {
            hasLoggedConvergence = true
            convergenceTimeMs = Platform.currentTimeMillis() - firstMeasurementTimeMs
            Log.i(TAG, "Kalman locked: time=${convergenceTimeMs}ms, " +
                    "offset=${offset.toLong()}us (+/-$errorMicros), drift=${String.format("%.2f", driftPpm)}ppm")
        }
    }

    /**
     * Determine if a measurement should be accepted or rejected as an outlier.
     *
     * Uses robust statistics (median + IQR) to detect measurements that are
     * far from the recent accepted history. This protects the Kalman filter
     * from being pulled by cellular congestion spikes (200ms+ outliers).
     *
     * Force-accepts after 3 consecutive rejections to handle genuine step changes
     * (e.g., network route change where ALL measurements shift).
     */
    private fun shouldAcceptMeasurement(measurement: Double, maxError: Double): Boolean {
        // Accept during early warmup - not enough history for outlier detection
        if (recentOffsetsCount < MIN_OUTLIER_MEASUREMENTS) return true

        // Force-accept after consecutive rejections (genuine step change)
        if (rejectedCount >= 3) return true

        val count = minOf(recentOffsetsCount, OUTLIER_WINDOW_SIZE)
        val sorted = DoubleArray(count)
        for (i in 0 until count) {
            sorted[i] = recentOffsets[(recentOffsetsIndex - count + i + OUTLIER_WINDOW_SIZE) % OUTLIER_WINDOW_SIZE]
        }
        sorted.sort()

        val median = if (count % 2 == 0) {
            (sorted[count / 2 - 1] + sorted[count / 2]) / 2.0
        } else {
            sorted[count / 2]
        }

        val q1 = sorted[count / 4]
        val q3 = sorted[(count * 3) / 4]
        val iqr = q3 - q1

        // Threshold: at least RTT-sized window (maxError), or IQR-based
        val threshold = maxOf(OUTLIER_IQR_MULTIPLIER * iqr, maxError)

        return abs(measurement - median) <= threshold
    }

    /**
     * Record an accepted offset measurement in the recent history window.
     */
    private fun recordAcceptedOffset(measurement: Double) {
        recentOffsets[recentOffsetsIndex] = measurement
        recentOffsetsIndex = (recentOffsetsIndex + 1) % OUTLIER_WINDOW_SIZE
        if (recentOffsetsCount < OUTLIER_WINDOW_SIZE) recentOffsetsCount++
    }

    /**
     * Perform 2D Kalman filter prediction and update (offset + drift).
     *
     * Optimizations over the base implementation:
     * - Adaptive process noise: Q_offset scales with observed innovation variance,
     *   automatically responding to network conditions (stable WiFi vs jittery cellular)
     * - Convergence-aware warmup: Forgetting activates when filter converges OR after
     *   MAX_WARMUP, not at a fixed measurement count
     * - Absolute residual threshold for forgetting (prevents instability feedback loop)
     */
    private fun kalmanUpdate(measurement: Double, variance: Double, clientTimeMicros: Long) {
        val dt = (clientTimeMicros - lastUpdateTime).toDouble()
        if (dt <= 0) return

        // === Prediction Step ===
        // State prediction: offset_predicted = offset + drift * dt
        val offsetPredicted = offset + drift * dt

        // Covariance prediction: P = F * P * F^T + Q
        // Uses adaptive process noise instead of fixed constant
        val p00New = p00 + 2 * p01 * dt + p11 * dt * dt + adaptiveProcessNoise * dt
        val p01New = p01 + p11 * dt
        val p10New = p10 + p11 * dt
        val p11New = p11 + PROCESS_NOISE_DRIFT * dt

        // === Innovation (Residual) ===
        val innovation = measurement - offsetPredicted

        // === Track Innovation for Adaptive Process Noise ===
        // Use p00New (predicted covariance) so the normalization denominator is
        // S = P_predicted[0,0] + R, matching standard Kalman innovation covariance.
        recordInnovation(innovation * innovation, p00New, variance)

        // === Adaptive Forgetting (convergence-aware warmup) ===
        if (isWarmupComplete()) {
            val absResidual = abs(innovation)
            val maxError = sqrt(variance)
            if (absResidual > FORGETTING_THRESHOLD * maxError) {
                val lambdaSquared = FORGETTING_FACTOR * FORGETTING_FACTOR
                p00 = p00New * lambdaSquared
                p01 = p01New * lambdaSquared
                p10 = p10New * lambdaSquared
                p11 = p11New * lambdaSquared
            } else {
                p00 = p00New
                p01 = p01New
                p10 = p10New
                p11 = p11New
            }
        } else {
            // During warmup: no forgetting, let the filter converge undisturbed
            p00 = p00New
            p01 = p01New
            p10 = p10New
            p11 = p11New
        }

        // === Update Step ===
        val s = p00 + variance // Innovation covariance
        if (s <= 0) return

        val k0 = p00 / s // Kalman gain for offset
        val k1 = p10 / s // Kalman gain for drift

        // State update
        offset = offsetPredicted + k0 * innovation
        var newDrift = drift + k1 * innovation

        // Bound drift to physically realistic values (+/-500 ppm)
        newDrift = newDrift.coerceIn(-MAX_DRIFT, MAX_DRIFT)
        drift = newDrift

        // Covariance update: P = (I - K * H) * P
        val p00Updated = (1 - k0) * p00
        val p01Updated = (1 - k0) * p01
        val p10Updated = p10 - k1 * p00
        val p11Updated = p11 - k1 * p01

        p00 = p00Updated
        p01 = p01Updated
        p10 = p10Updated
        p11 = p11Updated

        lastUpdateTime = clientTimeMicros
        measurementCount++

        // Log when filter becomes ready for the first time
        if (measurementCount == MIN_MEASUREMENTS) {
            Log.i(TAG, "Time sync ready: offset=${offset.toLong()}us, error=${errorMicros}us, " +
                    "drift=${String.format("%.3f", driftPpm)}ppm (after $measurementCount measurements)")
        }

        // === Update Adaptive Process Noise ===
        updateAdaptiveProcessNoise()
    }

    /**
     * Convergence-aware warmup check.
     *
     * Warmup is complete when EITHER:
     * - measurementCount >= MIN_WARMUP AND filter error < convergence threshold
     * - measurementCount >= MAX_WARMUP (force-end regardless of convergence)
     *
     * This means WiFi converges and exits warmup quickly (~5s), while cellular
     * stays in warmup longer until it actually converges or hits the max.
     */
    private fun isWarmupComplete(): Boolean {
        if (measurementCount >= MAX_WARMUP) return true
        if (measurementCount < MIN_WARMUP) return false
        return errorMicros < WARMUP_CONVERGENCE_THRESHOLD_US
    }

    /**
     * Record a squared innovation and its associated measurement variance.
     * Used to compute the innovation variance ratio for adaptive process noise.
     *
     * @param innovationSquared The squared innovation (z - H*x_predicted)^2
     * @param predictedP00 The predicted offset covariance P_predicted[0,0] (BEFORE the update step)
     * @param measurementVariance The measurement noise variance R
     */
    private fun recordInnovation(innovationSquared: Double, predictedP00: Double, measurementVariance: Double) {
        // Store the normalized innovation: innovation^2 / S where S = P_predicted[0,0] + R
        // This ratio should be ~1.0 if the filter model matches reality
        val expectedVariance = predictedP00 + measurementVariance
        val normalizedInnovation = if (expectedVariance > 0) {
            innovationSquared / expectedVariance
        } else {
            1.0
        }
        innovationWindow[innovationWindowIndex] = normalizedInnovation
        innovationWindowIndex = (innovationWindowIndex + 1) % INNOVATION_WINDOW_SIZE
        if (innovationWindowCount < INNOVATION_WINDOW_SIZE) innovationWindowCount++
    }

    /**
     * Update the adaptive process noise based on observed innovation variance.
     *
     * If innovations are consistently larger than expected (ratio > 1), the filter's
     * process noise model is too optimistic - increase Q to track faster.
     * If innovations are smaller than expected (ratio < 1), the filter is over-responsive
     * - decrease Q for smoother tracking.
     *
     * This naturally handles:
     * - Stable WiFi: ratio ~1.0, Q stays near BASE
     * - Jittery cellular: ratio > 1, Q increases for faster adaptation
     * - Handoff events: ratio spikes, Q increases temporarily
     */
    private fun updateAdaptiveProcessNoise() {
        if (innovationWindowCount < 5) return  // Need minimum data

        // Compute mean of normalized innovations (should be ~1.0 for well-tuned filter)
        val count = minOf(innovationWindowCount, INNOVATION_WINDOW_SIZE)
        var sum = 0.0
        for (i in 0 until count) {
            sum += innovationWindow[i]
        }
        val meanRatio = sum / count

        // Update stability score (visible in Stats for Nerds)
        stabilityScore = meanRatio

        // Scale process noise by the ratio, clamped to bounds
        val scaleFactor = meanRatio.coerceIn(ADAPTIVE_Q_MIN, ADAPTIVE_Q_MAX)
        adaptiveProcessNoise = BASE_PROCESS_NOISE_OFFSET * scaleFactor
    }

    /**
     * Convert server time to client time.
     * Includes static delay offset for speaker synchronization.
     *
     * NOTE: Drift is intentionally NOT used in time conversion (matching Python reference).
     * The sync correction feedback loop handles clock rate differences naturally through
     * sample insert/drop. Using drift here caused sync error oscillation because noisy
     * drift estimates fed back into the sync error calculation, creating instability.
     *
     * Drift is still tracked and displayed in Stats for Nerds for debugging purposes.
     *
     * @param serverTimeMicros Server timestamp in microseconds
     * @return Equivalent client timestamp in microseconds
     */
    fun serverToClient(serverTimeMicros: Long): Long {
        // Simple offset-only conversion (matches Python sendspin-cli)
        // Drift is NOT applied here - the sync correction loop handles rate differences
        val baseResult = serverTimeMicros - offset.toLong()

        // Apply static delay: positive delay = play later = higher client time
        return baseResult + staticDelayMicros
    }

    /**
     * Convert client time to server time.
     *
     * NOTE: Drift is intentionally NOT used (see serverToClient comments).
     *
     * @param clientTimeMicros Client timestamp in microseconds
     * @return Equivalent server timestamp in microseconds
     */
    fun clientToServer(clientTimeMicros: Long): Long {
        // Simple offset-only conversion (matches Python sendspin-cli)
        return clientTimeMicros + offset.toLong() - staticDelayMicros
    }
}

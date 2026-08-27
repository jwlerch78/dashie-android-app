package com.dashieapp.Dashie.halite.music.sendspin

import kotlin.math.sqrt

/**
 * 2D Kalman filter for sync error smoothing.
 *
 * Tracks both sync error offset AND drift to handle systematic timing differences
 * between the CPU clock and audio hardware clock. This matches the approach used
 * in the Python reference implementation.
 *
 * State vector: [offset, drift]
 * - offset: Current sync error estimate (microseconds)
 * - drift: Rate of change of sync error (microseconds per microsecond)
 *
 * The drift term is critical for handling cases where:
 * - CPU monotonic clock runs slightly faster/slower than audio DAC clock
 * - Network timing has systematic bias
 *
 * @param processNoiseOffset Process noise for offset state (μs² per second)
 * @param processNoiseDrift Process noise for drift state ((μs/μs)² per second)
 * @param measurementNoiseUs Expected measurement noise in microseconds
 */
class SyncErrorFilter(
    private val processNoiseOffset: Double = 100.0,
    private val processNoiseDrift: Double = 1e-6,
    private val measurementNoiseUs: Long = 5_000L
) {
    companion object {
        // Minimum measurements before filter is ready
        private const val MIN_MEASUREMENTS = 2

        // Forgetting threshold - if normalized residual exceeds this, increase uncertainty
        private const val FORGETTING_THRESHOLD = 0.75

        // Maximum allowed drift (+/-500 ppm = +/-5e-4)
        // Realistic clock drift is ~20-100 ppm
        private const val MAX_DRIFT = 5e-4
    }

    // State vector: [offset, drift]
    private var offset: Double = 0.0
    private var drift: Double = 0.0

    // Covariance matrix (2x2)
    private var p00: Double = Double.MAX_VALUE  // offset variance
    private var p01: Double = 0.0               // offset-drift covariance
    private var p10: Double = 0.0               // drift-offset covariance
    private var p11: Double = 1e-6              // drift variance

    // Timing state
    private var lastUpdateTimeUs: Long = 0
    private var measurementCount: Int = 0

    /**
     * Whether enough measurements have been collected for reliable estimation.
     */
    val isReady: Boolean
        get() = measurementCount >= MIN_MEASUREMENTS && p00.isFinite()

    /**
     * Current estimated sync error offset in microseconds.
     */
    val offsetMicros: Long
        get() = offset.toLong()

    /**
     * Current estimated drift (microseconds per microsecond).
     */
    val driftValue: Double
        get() = drift

    /**
     * Estimated uncertainty (standard deviation) in microseconds.
     */
    val errorMicros: Long
        get() = if (p00.isFinite() && p00 >= 0) sqrt(p00).toLong() else Long.MAX_VALUE

    /**
     * Reset the filter to initial state.
     */
    fun reset() {
        offset = 0.0
        drift = 0.0
        p00 = Double.MAX_VALUE
        p01 = 0.0
        p10 = 0.0
        p11 = 1e-6
        lastUpdateTimeUs = 0
        measurementCount = 0
    }

    /**
     * Add a new sync error measurement.
     *
     * @param measurement The measured sync error in microseconds
     * @param timeUs Current time in microseconds (for process noise scaling)
     */
    fun update(measurement: Long, timeUs: Long) {
        val measDouble = measurement.toDouble()
        val measurementVariance = (measurementNoiseUs.toDouble() * measurementNoiseUs).coerceAtLeast(1.0)

        when (measurementCount) {
            0 -> {
                // First measurement - initialize offset directly
                offset = measDouble
                p00 = measurementVariance
                lastUpdateTimeUs = timeUs
                measurementCount = 1
            }
            1 -> {
                // Second measurement - estimate initial drift
                val dt = (timeUs - lastUpdateTimeUs).toDouble()
                if (dt > 0) {
                    drift = (measDouble - offset) / dt
                    p11 = measurementVariance / (dt * dt)
                }
                offset = measDouble
                p00 = measurementVariance
                lastUpdateTimeUs = timeUs
                measurementCount = 2
            }
            else -> {
                // Steady state - full Kalman update
                kalmanUpdate(measDouble, measurementVariance, timeUs)
            }
        }
    }

    /**
     * Perform Kalman filter prediction and update.
     */
    private fun kalmanUpdate(measurement: Double, variance: Double, timeUs: Long) {
        val dt = (timeUs - lastUpdateTimeUs).toDouble()
        if (dt <= 0) return

        val dtSeconds = dt / 1_000_000.0

        // === Prediction Step ===
        // State prediction: offset_predicted = offset + drift * dt
        val offsetPredicted = offset + drift * dt
        // Drift prediction: drift stays the same (random walk model)

        // Covariance prediction: P = F * P * F^T + Q
        // F = [1, dt; 0, 1] (state transition matrix)
        // Q = [q_offset * dt, 0; 0, q_drift * dt] (process noise)
        val p00New = p00 + 2 * p01 * dt + p11 * dt * dt + processNoiseOffset * dtSeconds * 1_000_000.0
        val p01New = p01 + p11 * dt
        val p10New = p10 + p11 * dt
        val p11New = p11 + processNoiseDrift * dtSeconds

        // === Innovation (Residual) ===
        val innovation = measurement - offsetPredicted

        // Check for outlier - apply adaptive forgetting if needed
        val innovationVariance = p00New + variance
        val normalizedResidual = if (innovationVariance > 0) {
            (innovation * innovation) / innovationVariance
        } else {
            0.0
        }

        // If residual is too large, this might be a step change - inflate covariance
        // uniformly to adapt faster while preserving the correlation structure.
        // Inflating only diagonal elements (p00, p11) would break the covariance matrix
        // by artificially weakening the offset-drift coupling, making drift adaptation
        // slower exactly when fast adaptation is most needed during step changes.
        if (normalizedResidual > FORGETTING_THRESHOLD * FORGETTING_THRESHOLD) {
            // Increase all covariance elements by the same factor to preserve
            // the correlation structure (p01/sqrt(p00*p11) stays the same)
            p00 = p00New * 10
            p01 = p01New * 10
            p10 = p10New * 10
            p11 = p11New * 10
        } else {
            p00 = p00New
            p01 = p01New
            p10 = p10New
            p11 = p11New
        }

        // === Update Step ===
        // Kalman gain: K = P * H^T * (H * P * H^T + R)^-1
        // H = [1, 0] (measurement matrix - we only measure offset)
        val s = p00 + variance // Innovation covariance
        if (s <= 0) return

        val k0 = p00 / s // Kalman gain for offset
        val k1 = p10 / s // Kalman gain for drift

        // State update
        offset = offsetPredicted + k0 * innovation
        var newDrift = drift + k1 * innovation

        // Bound drift to physically realistic values (+/-500 ppm)
        newDrift = newDrift.coerceIn(-MAX_DRIFT, MAX_DRIFT)

        // NOTE: No multiplicative drift decay. The previous 1% per-update decay
        // systematically underestimated genuine DAC clock drift by fighting against
        // Kalman convergence. The process noise model (processNoiseDrift) already
        // handles uncertainty about drift changes, and MAX_DRIFT clamping prevents
        // unbounded growth.

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

        lastUpdateTimeUs = timeUs
        measurementCount++
    }

    /**
     * Get predicted sync error at a future time.
     *
     * @param futureTimeUs The future time in microseconds
     * @return Predicted sync error at that time
     */
    fun predictAt(futureTimeUs: Long): Long {
        val dt = (futureTimeUs - lastUpdateTimeUs).toDouble()
        return (offset + drift * dt).toLong()
    }
}

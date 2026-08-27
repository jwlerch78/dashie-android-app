package com.dashieapp.Dashie.wakeword.edgeimpulse

import kotlin.math.abs

/**
 * Audio Preprocessing for Edge Impulse Model
 *
 * For Native SDK (v2.12+):
 * - Input: Audio from SharedAudioBuffer (normalized to [-1, 1])
 * - Output: Audio scaled to int16 range (~-32768 to +32767)
 * - The SDK's preemphasis class with rescale=true divides by 32768 internally
 * - Native SDK handles all MFE preprocessing internally (normalization, preemphasis, filterbank)
 *
 * For Kotlin MFE (legacy):
 * - Length normalization, volume normalization, and preemphasis
 */
class AudioPreprocessor {

    /**
     * Preprocess audio samples for native Edge Impulse SDK
     *
     * IMPORTANT: The native SDK expects RAW int16 PCM values as floats (range ~-32768 to +32767).
     * The SDK's preemphasis class with rescale=true performs normalization internally by
     * dividing by 32768. Since SharedAudioBuffer stores normalized [-1, 1] audio, we must
     * scale it back to int16 range before passing to the native SDK.
     *
     * Processing steps:
     *   1. Length normalization (ensure 16000 samples)
     *   2. Scale from [-1, 1] to int16 range (multiply by 32768)
     *
     * The SDK handles:
     *   - Normalization (divides by 32768 when rescale=true)
     *   - Preemphasis filtering
     *   - MFE feature extraction
     *
     * @param samples Audio samples from SharedAudioBuffer, normalized to [-1, 1]
     * @return Samples scaled to int16 range for native SDK
     */
    fun preprocess(samples: FloatArray): FloatArray {
        // Step 1: Ensure exactly 16000 samples (zero-pad or trim)
        val lengthNormalized = normalizeLength(samples)

        // Step 2: Normalize volume to [-1, 1] range (peak normalization)
        // This boosts quiet/distant audio to full scale, matching training data normalization
        // and improving detection at distance. Was removed during SDK migration but is needed.
        val volumeNormalized = normalizeVolume(lengthNormalized)

        // Step 3: Scale from [-1, 1] to int16 range
        // SharedAudioBuffer normalizes audio to [-1, 1], but the Edge Impulse SDK
        // expects raw int16 values. The SDK will divide by 32768 internally.
        // See edge-impulse-sdk/dsp/speechpy/processing.hpp lines 123-128
        return scaleToInt16Range(volumeNormalized)
    }

    /**
     * Scale audio from [-1, 1] to int16 range (~-32768 to +32767)
     * Required because SharedAudioBuffer stores normalized values but
     * Edge Impulse SDK expects raw int16 values.
     */
    private fun scaleToInt16Range(samples: FloatArray): FloatArray {
        return FloatArray(samples.size) { i -> samples[i] * 32768.0f }
    }

    /**
     * Ensure exactly 16000 samples (zero-pad short audio, trim long audio)
     */
    private fun normalizeLength(samples: FloatArray): FloatArray {
        return when {
            samples.size < EdgeImpulseConfig.WINDOW_SIZE_SAMPLES -> {
                // Zero-pad short audio
                val padded = FloatArray(EdgeImpulseConfig.WINDOW_SIZE_SAMPLES)
                samples.copyInto(padded, 0, 0, samples.size)
                padded
            }
            samples.size > EdgeImpulseConfig.WINDOW_SIZE_SAMPLES -> {
                // Trim long audio
                samples.copyOf(EdgeImpulseConfig.WINDOW_SIZE_SAMPLES)
            }
            else -> samples
        }
    }

    /**
     * Normalize audio volume to [-1, 1] range
     * This matches the training data normalization.
     *
     * Floor at 0.01 prevents near-silence from being amplified to look like speech.
     * Without this, silence (maxAbs=0.001) gets amplified 1000x, producing MFE features
     * indistinguishable from real audio. MUST match Python MFEExtractor._preprocess_audio().
     */
    private fun normalizeVolume(samples: FloatArray): FloatArray {
        val maxAbsValue = maxOf(samples.maxOfOrNull { abs(it) } ?: 1.0f, 0.01f)
        return FloatArray(samples.size) { i -> samples[i] / maxAbsValue }
    }

    /**
     * Apply preemphasis filter (Version 4 requirement)
     *
     * Formula: y[n] = x[n] - 0.98 * x[n-1]
     *
     * This emphasizes high frequencies and is REQUIRED by the model
     */
    private fun applyPreemphasis(samples: FloatArray): FloatArray {
        val result = FloatArray(samples.size)
        result[0] = samples[0]  // First sample unchanged

        for (i in 1 until samples.size) {
            result[i] = samples[i] - EdgeImpulseConfig.PREEMPHASIS_COEFF * samples[i - 1]
        }

        return result
    }
}

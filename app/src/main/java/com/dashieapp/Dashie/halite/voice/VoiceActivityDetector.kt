package com.dashieapp.Dashie.halite.voice

import android.util.Log
import kotlin.math.sqrt

/**
 * Voice Activity Detector (VAD)
 *
 * Simple energy-based silence detection for determining when the user
 * has stopped speaking. Uses RMS (root mean square) of audio samples.
 *
 * This is a lightweight alternative to WebRTC VAD, optimized for
 * detecting end-of-speech after wake word activation.
 */
class VoiceActivityDetector {
    companion object {
        private const val TAG = "VoiceActivityDetector"

        // RMS threshold for silence detection (normalized to 0-1 range)
        private const val DEFAULT_SILENCE_THRESHOLD = 0.02f

        // Time of silence needed to trigger end-of-speech (milliseconds)
        private const val DEFAULT_SILENCE_TIMEOUT_MS = 1500L

        // Minimum speech duration before checking for silence (milliseconds)
        // Prevents triggering immediately if user pauses after wake word
        private const val MIN_SPEECH_DURATION_MS = 500L
    }

    // Configuration
    private var silenceThreshold: Float = DEFAULT_SILENCE_THRESHOLD
    private var silenceTimeoutMs: Long = DEFAULT_SILENCE_TIMEOUT_MS
    private var minSpeechDurationMs: Long = MIN_SPEECH_DURATION_MS

    // State tracking
    private var speechStartTime: Long = 0L
    private var silenceStartTime: Long? = null
    private var hasSpeechStarted = false
    private var peakRms: Float = 0f

    // Callback when end-of-speech detected
    var onEndOfSpeech: (() -> Unit)? = null

    /** Fired once per utterance, the moment speech is first detected. The pipeline uses it to
     *  disarm its "the user never said anything" timeout: once someone IS talking, that
     *  premise is dead, and the only bound that should apply is the max-recording cap.
     *  (Before this, a >6 s sentence tripped the silent-window timeout mid-word and the
     *  captured audio was thrown away — John, 2026-07-13.) */
    var onSpeechStarted: (() -> Unit)? = null

    /** True once this utterance has had real speech in it — i.e. the window isn't silent. */
    @Volatile var hasSpeech: Boolean = false
        private set

    /**
     * Reset detector state for a new utterance
     */
    fun reset() {
        speechStartTime = System.currentTimeMillis()
        silenceStartTime = null
        hasSpeechStarted = false
        hasSpeech = false   // per-utterance: a stale `true` would make the next silent window flush
        peakRms = 0f
        Log.d(TAG, "Reset - starting new utterance detection")
    }

    /**
     * Process audio chunk and check for end-of-speech
     *
     * @param pcmData 16-bit PCM audio samples
     * @return true if end-of-speech detected
     */
    fun processAudio(pcmData: ByteArray): Boolean {
        val rms = calculateRms(pcmData)
        val currentTime = System.currentTimeMillis()

        // Track peak RMS for adaptive threshold
        if (rms > peakRms) {
            peakRms = rms
        }

        // Check if this is speech or silence
        val isSpeech = rms > silenceThreshold

        if (isSpeech) {
            // Speech detected
            if (!hasSpeechStarted) {
                hasSpeechStarted = true
                hasSpeech = true
                Log.d(TAG, "Speech started (RMS=${"%.4f".format(rms)})")
                onSpeechStarted?.invoke()
            }
            silenceStartTime = null
        } else {
            // Silence detected
            if (hasSpeechStarted) {
                // Only start silence timer after we've had some speech
                val speechDuration = currentTime - speechStartTime
                if (speechDuration >= minSpeechDurationMs) {
                    if (silenceStartTime == null) {
                        silenceStartTime = currentTime
                        Log.d(TAG, "Silence started after ${speechDuration}ms of speech (RMS=${"%.4f".format(rms)})")
                    } else {
                        val silenceDuration = currentTime - silenceStartTime!!
                        if (silenceDuration >= silenceTimeoutMs) {
                            Log.d(TAG, "End of speech detected after ${silenceDuration}ms silence")
                            onEndOfSpeech?.invoke()
                            return true
                        }
                    }
                }
            }
        }

        return false
    }

    /**
     * Process audio from FloatArray (normalized -1.0 to 1.0)
     *
     * @param samples Normalized float audio samples
     * @return true if end-of-speech detected
     */
    fun processAudioFloat(samples: FloatArray): Boolean {
        val rms = calculateRmsFloat(samples)
        val currentTime = System.currentTimeMillis()

        // Track peak RMS
        if (rms > peakRms) {
            peakRms = rms
        }

        val isSpeech = rms > silenceThreshold

        if (isSpeech) {
            if (!hasSpeechStarted) {
                hasSpeechStarted = true
                hasSpeech = true
                Log.d(TAG, "Speech started (RMS=${"%.4f".format(rms)})")
                onSpeechStarted?.invoke()
            }
            silenceStartTime = null
        } else {
            if (hasSpeechStarted) {
                val speechDuration = currentTime - speechStartTime
                if (speechDuration >= minSpeechDurationMs) {
                    if (silenceStartTime == null) {
                        silenceStartTime = currentTime
                        Log.d(TAG, "Silence started (RMS=${"%.4f".format(rms)})")
                    } else {
                        val silenceDuration = currentTime - silenceStartTime!!
                        if (silenceDuration >= silenceTimeoutMs) {
                            Log.d(TAG, "End of speech detected after ${silenceDuration}ms silence")
                            onEndOfSpeech?.invoke()
                            return true
                        }
                    }
                }
            }
        }

        return false
    }

    /**
     * Calculate RMS (Root Mean Square) from 16-bit PCM data
     *
     * @param pcmData ByteArray of 16-bit little-endian PCM samples
     * @return RMS value normalized to 0.0-1.0 range
     */
    private fun calculateRms(pcmData: ByteArray): Float {
        if (pcmData.size < 2) return 0f

        var sumSquares = 0.0
        val sampleCount = pcmData.size / 2

        for (i in 0 until sampleCount) {
            // Convert 2 bytes to 16-bit signed sample (little-endian)
            val low = pcmData[i * 2].toInt() and 0xFF
            val high = pcmData[i * 2 + 1].toInt()
            val sample = (high shl 8) or low

            // Normalize to -1.0 to 1.0 range
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
        }

        return sqrt(sumSquares / sampleCount).toFloat()
    }

    /**
     * Calculate RMS from normalized float samples
     *
     * @param samples FloatArray of normalized audio samples (-1.0 to 1.0)
     * @return RMS value
     */
    private fun calculateRmsFloat(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f

        var sumSquares = 0.0
        for (sample in samples) {
            sumSquares += sample * sample
        }

        return sqrt(sumSquares / samples.size).toFloat()
    }

    /**
     * Set silence detection threshold
     *
     * @param threshold RMS threshold (0.0-1.0), lower = more sensitive
     */
    fun setSilenceThreshold(threshold: Float) {
        silenceThreshold = threshold.coerceIn(0.001f, 0.5f)
        Log.d(TAG, "Silence threshold set to $silenceThreshold")
    }

    /**
     * Set silence timeout duration
     *
     * @param timeoutMs Milliseconds of silence before triggering end-of-speech
     */
    fun setSilenceTimeout(timeoutMs: Long) {
        silenceTimeoutMs = timeoutMs.coerceIn(500L, 5000L)
        Log.d(TAG, "Silence timeout set to ${silenceTimeoutMs}ms")
    }

    /**
     * Set minimum speech duration
     *
     * @param durationMs Minimum milliseconds of speech before checking for silence
     */
    fun setMinSpeechDuration(durationMs: Long) {
        minSpeechDurationMs = durationMs.coerceIn(0L, 2000L)
        Log.d(TAG, "Min speech duration set to ${minSpeechDurationMs}ms")
    }

    /**
     * Get time since speech started (for UI feedback)
     *
     * @return Milliseconds since speech started, or 0 if not started
     */
    fun getSpeechDuration(): Long {
        return if (hasSpeechStarted) {
            System.currentTimeMillis() - speechStartTime
        } else {
            0L
        }
    }

    /**
     * Get current silence duration (for UI feedback)
     *
     * @return Milliseconds of current silence, or 0 if not in silence
     */
    fun getSilenceDuration(): Long {
        val start = silenceStartTime ?: return 0L
        return System.currentTimeMillis() - start
    }

    /**
     * Check if currently in speech mode
     */
    fun hasSpeechStarted(): Boolean = hasSpeechStarted
}

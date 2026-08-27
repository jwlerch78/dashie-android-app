package com.dashieapp.Dashie.audio

import android.util.Log
import com.konovalov.vad.webrtc.Vad
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate

/**
 * WebRTC VAD Processor for Real-Time Silence Detection
 *
 * Purpose:
 * 1. Detect speech vs non-speech in real-time (<1ms processing)
 * 2. Track sustained silence duration
 * 3. Trigger recording stop after threshold (e.g., 1 second silence)
 *
 * Performance: <1ms per 20ms frame (fast enough for inline processing)
 *
 * Version: 1.0.0-WEBRTC-VAD
 */
class WebRTCVADProcessor {
    companion object {
        private const val TAG = "WebRTCVAD"
        private const val VERSION = "1.0.0-WEBRTC-VAD"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE_MS = 20  // 20ms frames (320 samples at 16kHz)
    }

    private var vad: VadWebRTC? = null
    private var consecutiveSilenceFrames = 0
    private var consecutiveSpeechFrames = 0
    private var hasDetectedSpeech = false  // Track if we've ever detected speech in this session
    private var totalSpeechFrames = 0  // Total speech frames across entire session (not consecutive)
    private var isInitialized = false

    /**
     * Initialize WebRTC VAD
     * @param aggressiveness 0-3 where:
     *   0 = NORMAL (least aggressive, catches all speech)
     *   1 = LOW_BITRATE (moderate filtering)
     *   2 = AGGRESSIVE (more filtering, recommended)
     *   3 = VERY_AGGRESSIVE (maximum filtering, only clear speech)
     * @return true if successful
     */
    fun initialize(aggressiveness: Int = 2): Boolean {
        Log.e(TAG, "🔧 WebRTCVADProcessor VERSION: $VERSION")

        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return true
        }

        try {
            val mode = when (aggressiveness) {
                0 -> Mode.NORMAL
                1 -> Mode.LOW_BITRATE
                2 -> Mode.AGGRESSIVE
                3 -> Mode.VERY_AGGRESSIVE
                else -> Mode.AGGRESSIVE
            }

            // Build VAD with continuous detection enabled
            // silenceDurationMs and speechDurationMs control the library's internal state tracking
            vad = Vad.builder()
                .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(FrameSize.FRAME_SIZE_320)  // 20ms at 16kHz
                .setMode(mode)
                .setSilenceDurationMs(0)  // No internal filtering - we'll track duration ourselves
                .setSpeechDurationMs(0)   // No internal filtering - we'll track duration ourselves
                .build()

            Log.i(TAG, "✓ WebRTC VAD initialized (aggressiveness: $aggressiveness, mode: $mode)")

            isInitialized = true
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebRTC VAD: ${e.message}", e)
            return false
        }
    }

    /**
     * Start a new detection session
     */
    fun startSession() {
        consecutiveSilenceFrames = 0
        consecutiveSpeechFrames = 0
        hasDetectedSpeech = false
        totalSpeechFrames = 0
        Log.d(TAG, "✓ VAD session started")
    }

    /**
     * Process audio frame (20ms chunk at 16kHz = 320 samples)
     *
     * @param audioData Raw audio samples (ShortArray, must be 320 samples for 20ms at 16kHz)
     * @return VADResult with speech detection and silence duration
     */
    fun processFrame(audioData: ShortArray): VADResult {
        if (vad == null) {
            Log.w(TAG, "VAD not initialized")
            return VADResult()
        }

        try {
            // Expected frame size: 320 samples for 20ms at 16kHz
            val expectedSize = (SAMPLE_RATE * FRAME_SIZE_MS) / 1000
            if (audioData.size != expectedSize) {
                Log.w(TAG, "Invalid frame size: ${audioData.size} (expected $expectedSize)")
                return VADResult()
            }

            // Process with WebRTC VAD (<1ms)
            val isSpeech = vad!!.isSpeech(audioData)

            // Update counters
            if (isSpeech) {
                consecutiveSpeechFrames++
                consecutiveSilenceFrames = 0  // Reset silence counter
                totalSpeechFrames++  // Accumulate total speech
                hasDetectedSpeech = true  // Mark that we've detected speech at least once
            } else {
                consecutiveSilenceFrames++
                consecutiveSpeechFrames = 0  // Reset speech counter
            }

            // Calculate durations
            val silenceDuration = (consecutiveSilenceFrames * FRAME_SIZE_MS) / 1000.0f
            val totalSpeechDuration = (totalSpeechFrames * FRAME_SIZE_MS) / 1000.0f

            return VADResult(
                isSpeech = isSpeech,
                silenceDuration = silenceDuration,
                speechDuration = (consecutiveSpeechFrames * FRAME_SIZE_MS) / 1000.0f,
                totalSpeechDuration = totalSpeechDuration,  // Total speech across entire session
                hasDetectedSpeech = hasDetectedSpeech
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame: ${e.message}", e)
            return VADResult()
        }
    }

    /**
     * Get current silence duration
     */
    fun getSilenceDuration(): Float {
        return (consecutiveSilenceFrames * FRAME_SIZE_MS) / 1000.0f
    }

    /**
     * End session and cleanup
     */
    fun endSession() {
        consecutiveSilenceFrames = 0
        consecutiveSpeechFrames = 0
        Log.d(TAG, "VAD session ended")
    }

    /**
     * Shutdown and cleanup
     */
    fun shutdown() {
        endSession()
        // VadWebRTC doesn't require explicit cleanup/close
        vad = null
        isInitialized = false
        Log.d(TAG, "WebRTC VAD shutdown")
    }

    /**
     * Result data class
     */
    data class VADResult(
        val isSpeech: Boolean = false,
        val silenceDuration: Float = 0f,       // Seconds of continuous silence
        val speechDuration: Float = 0f,        // Seconds of consecutive speech (resets on silence)
        val totalSpeechDuration: Float = 0f,   // Total speech across entire session
        val hasDetectedSpeech: Boolean = false // True if we've detected speech at any point in this session
    )
}

package com.dashieapp.Dashie.voice.callbacks

/**
 * VoiceCallbacks
 * Interface definitions for all voice assistant callbacks
 *
 * Organizes callbacks by functional area:
 * - TTS (Text-to-Speech)
 * - STT (Speech-to-Text)
 * - Wake Word Detection
 * - Audio Monitoring
 *
 * Extracted from VoiceAssistantManager for better organization and type safety
 */

/**
 * Text-to-Speech callbacks
 */
interface TTSCallbacks {
    /**
     * Called when TTS engine is ready
     */
    fun onReady()

    /**
     * Called when TTS starts speaking
     */
    fun onSpeakingStarted()

    /**
     * Called when TTS finishes speaking
     */
    fun onSpeakingCompleted()

    /**
     * Called when TTS encounters an error
     * @param error Error message
     */
    fun onError(error: String)
}

/**
 * Speech-to-Text callbacks
 */
interface STTCallbacks {
    /**
     * Called when STT starts listening
     */
    fun onListeningStarted()

    /**
     * Called when STT stops listening
     */
    fun onListeningEnded()

    /**
     * Called when STT produces a final result
     * @param text Recognized text
     */
    fun onResult(text: String)

    /**
     * Called when STT produces a partial result
     * @param text Partial recognized text
     */
    fun onPartialResult(text: String)

    /**
     * Called when STT encounters an error
     * @param error Error message
     */
    fun onError(error: String)
}

/**
 * Wake Word Detection callbacks
 */
interface WakeWordCallbacks {
    /**
     * Called when wake word is detected
     */
    fun onDetected()

    /**
     * Called when wake word detection encounters an error
     * @param error Error message
     */
    fun onError(error: String)
}

/**
 * Audio Monitoring callbacks
 */
interface AudioCallbacks {
    /**
     * Called with raw audio data captured during STT
     * @param data Raw audio bytes (PCM 16-bit, 16kHz mono)
     */
    fun onAudioDataCaptured(data: ByteArray)

    /**
     * Called with real-time audio level for visualization
     * @param level Audio level (0.0 to 1.0)
     */
    fun onAudioLevel(level: Float)

    /**
     * Called periodically (~5 seconds) with audio statistics
     * @param maxVolume Maximum volume level in period
     * @param maxConfidence Maximum wake word confidence in period
     */
    fun onHeartbeat(maxVolume: Float, maxConfidence: Float)
}

/**
 * Adapter class for TTS callbacks
 * Allows selective implementation of callbacks
 */
open class TTSCallbacksAdapter : TTSCallbacks {
    override fun onReady() {}
    override fun onSpeakingStarted() {}
    override fun onSpeakingCompleted() {}
    override fun onError(error: String) {}
}

/**
 * Adapter class for STT callbacks
 * Allows selective implementation of callbacks
 */
open class STTCallbacksAdapter : STTCallbacks {
    override fun onListeningStarted() {}
    override fun onListeningEnded() {}
    override fun onResult(text: String) {}
    override fun onPartialResult(text: String) {}
    override fun onError(error: String) {}
}

/**
 * Adapter class for Wake Word callbacks
 * Allows selective implementation of callbacks
 */
open class WakeWordCallbacksAdapter : WakeWordCallbacks {
    override fun onDetected() {}
    override fun onError(error: String) {}
}

/**
 * Adapter class for Audio callbacks
 * Allows selective implementation of callbacks
 */
open class AudioCallbacksAdapter : AudioCallbacks {
    override fun onAudioDataCaptured(data: ByteArray) {}
    override fun onAudioLevel(level: Float) {}
    override fun onHeartbeat(maxVolume: Float, maxConfidence: Float) {}
}

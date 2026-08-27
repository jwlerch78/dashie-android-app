package com.dashieapp.Dashie.wakeword

import com.dashieapp.Dashie.audio.SharedAudioBuffer

/**
 * Common interface for wake word detectors.
 *
 * Both EdgeImpulseDetector and MicroWakeWordDetector implement this interface,
 * allowing the voice pipeline to swap engines at runtime based on the selected
 * model version suffix ("E" = Edge Impulse, "M" = microWakeWord).
 *
 * The rest of the pipeline (SharedAudioBuffer, VoicePipelineCoordinator,
 * VoiceAssistantCoordinator, STT handoff) is unchanged regardless of engine.
 */
interface WakeWordDetectorInterface {

    // ========== Lifecycle ==========

    /** Initialize the detector (load model, prepare resources). */
    fun initialize(): Boolean

    /** Start wake word detection (reads from SharedAudioBuffer). */
    fun start()

    /** Stop wake word detection. Does NOT stop AudioCaptureService. */
    fun stop()

    /** Release all resources. */
    fun close()

    /** Check if detection is currently running. */
    fun isDetecting(): Boolean

    // ========== Configuration ==========

    /** Attach the shared audio buffer to read from. Must be called before start(). */
    fun setSharedBuffer(buffer: SharedAudioBuffer)

    /** Set detection threshold (0.0-1.0). */
    fun setThreshold(threshold: Float)

    /** Get current detection threshold. */
    fun getThreshold(): Float

    // ========== Callbacks ==========

    /** Called when wake word is detected with confidence and buffer position for STT handoff. */
    var onWakeWordDetected: ((confidence: Float, bufferPosition: Long) -> Unit)?

    /** Called periodically with max confidence and volume since last heartbeat. */
    var onHeartbeat: ((confidence: Float, volume: Float) -> Unit)?

    /** Called on error. */
    var onError: ((String) -> Unit)?

    // ========== Diagnostics ==========

    /** Get status string for memory diagnostics. */
    fun getMemoryStatus(): String

    /** Engine identifier for logging and UI (e.g., "EdgeImpulse", "microWakeWord"). */
    val engineName: String
}

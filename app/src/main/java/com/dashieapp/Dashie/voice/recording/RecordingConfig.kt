package com.dashieapp.Dashie.voice.recording

/**
 * RecordingConfig
 * Configuration for audio recording behavior
 *
 * Extracted from VoiceAssistantManager for better separation of concerns
 */
data class RecordingConfig(
    /**
     * Recording mode (batch, streaming, or manual)
     */
    val mode: RecordingMode,

    /**
     * Maximum recording duration in seconds
     * Recording stops after this duration even if speech is ongoing
     */
    val maxDurationSeconds: Int,

    /**
     * Silence threshold in seconds
     * Recording stops early if this much silence is detected after speech
     * - Batch mode: Uses WebRTC VAD
     * - Streaming mode: Uses Deepgram cloud VAD
     */
    val silenceThresholdSeconds: Float,

    /**
     * Whether to automatically restart wake word detection after recording completes
     * - true: Wake word detection restarts after 500ms (default for auto-triggered recordings)
     * - false: Wake word detection does NOT restart (manual recordings for testing)
     */
    val autoRestartWakeWord: Boolean = true,

    /**
     * Whether to use streaming STT mode
     * Only applies to MANUAL mode - routes to batch or streaming
     */
    val useStreamingSTT: Boolean = true
) {
    companion object {
        /**
         * Default configuration for auto-triggered recordings (after wake word)
         */
        val DEFAULT_AUTO = RecordingConfig(
            mode = RecordingMode.STREAMING,
            maxDurationSeconds = 5,
            silenceThresholdSeconds = 1.0f,
            autoRestartWakeWord = true,
            useStreamingSTT = true
        )

        /**
         * Default configuration for manual recordings (testing)
         */
        val DEFAULT_MANUAL = RecordingConfig(
            mode = RecordingMode.MANUAL,
            maxDurationSeconds = 10,
            silenceThresholdSeconds = 1.0f,
            autoRestartWakeWord = false,
            useStreamingSTT = true
        )
    }
}

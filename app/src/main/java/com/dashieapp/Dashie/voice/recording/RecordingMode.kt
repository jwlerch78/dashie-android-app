package com.dashieapp.Dashie.voice.recording

/**
 * RecordingMode
 * Defines the different audio recording modes for speech-to-text
 *
 * Extracted from VoiceAssistantManager for better type safety
 */
enum class RecordingMode {
    /**
     * BATCH mode
     * - Records entire audio chunk
     * - Sends complete recording to Deepgram API after completion
     * - Uses WebRTC VAD for silence detection
     * - Downsamples 16kHz → 8kHz on-the-fly
     * - Best for: Shorter utterances, lower bandwidth
     */
    BATCH,

    /**
     * STREAMING mode
     * - Real-time transcription via WebSocket
     * - Streams audio to Deepgram as it's recorded
     * - Uses Deepgram's cloud VAD for silence detection
     * - Keeps connection alive between recordings
     * - Best for: Longer utterances, real-time feedback
     */
    STREAMING,

    /**
     * MANUAL mode
     * - Manual recording without wake word trigger
     * - Does NOT automatically restart wake word after completion
     * - Routes to either batch or streaming based on configuration
     * - Used for: Testing, explicit user-initiated recording
     */
    MANUAL
}

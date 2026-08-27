package com.dashieapp.Dashie.voice.wakeword

/**
 * WakeWordConfig
 * Configuration for wake word detection behavior
 *
 * Extracted from VoiceAssistantManager for better separation of concerns
 */
data class WakeWordConfig(
    /**
     * Whether to automatically start recording after wake word detection
     * Default: true
     */
    val autoRecord: Boolean = true,

    /**
     * Duration in seconds for auto-recording after wake word
     * Default: 5 seconds
     *
     * Note: Recording may stop early if silence is detected (WebRTC VAD or Deepgram utterance_end_ms)
     */
    val duration: Int = 5
) {
    /**
     * Convert to JSON string for JavaScript bridge
     */
    fun toJson(): String {
        return """{"autoRecord":$autoRecord,"duration":$duration}"""
    }

    companion object {
        /**
         * Default configuration
         */
        val DEFAULT = WakeWordConfig()

        /**
         * Parse from JSON string (for JavaScript bridge)
         */
        fun fromJson(json: String): WakeWordConfig {
            // Simple JSON parsing (avoids adding JSON library dependency)
            val autoRecord = json.contains("\"autoRecord\":true")
            val durationMatch = Regex("\"duration\":(\\d+)").find(json)
            val duration = durationMatch?.groupValues?.get(1)?.toIntOrNull() ?: 5

            return WakeWordConfig(autoRecord, duration)
        }
    }
}

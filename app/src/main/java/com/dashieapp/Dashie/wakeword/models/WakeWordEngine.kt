package com.dashieapp.Dashie.wakeword.models

/**
 * Wake word detection engine type, determined by model version suffix.
 *
 * - "E" or "S" (legacy) suffix → Edge Impulse (pure Kotlin MFE + TFLite)
 * - "M" suffix → microWakeWord (native C frontend + streaming TFLite)
 *
 * Example: "v3.22E" = Edge Impulse, "v1.0M" = microWakeWord
 */
enum class WakeWordEngine(val displayName: String) {
    EDGE_IMPULSE("Edge Impulse"),
    MICRO_WAKE_WORD("microWakeWord");

    companion object {
        /**
         * Determine engine from model version suffix.
         * Default to Edge Impulse for unrecognized suffixes.
         */
        fun fromVersionSuffix(version: String): WakeWordEngine {
            val trimmed = version.trim()
            return when {
                trimmed.endsWith("M", ignoreCase = true) -> MICRO_WAKE_WORD
                else -> EDGE_IMPULSE  // "E", "S", or no suffix
            }
        }
    }
}

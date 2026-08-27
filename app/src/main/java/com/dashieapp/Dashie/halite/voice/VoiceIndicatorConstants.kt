package com.dashieapp.Dashie.halite.voice

/**
 * Shared timing constants and helpers for the voice indicator UI, used across the
 * decomposed indicator classes ([VoiceIndicatorController], [VoiceIndicatorViews],
 * [VoiceIndicatorAnimator], [VoiceResultCardView], [VoiceSidebarPanelView]).
 *
 * Extracted from the former 990-line VoiceIndicatorController so every piece reads
 * the same numbers instead of re-declaring its own (and silently drifting).
 */
internal object VoiceIndicatorConstants {
    const val ANIMATION_DURATION_MS = 250L
    const val LINE_PULSE_DURATION_MS = 2500L
    const val DOT_ANIMATION_DURATION_MS = 1400L
    const val SPEAKING_DISPLAY_DURATION_MS = 5000L

    // Post-speech sidebar/card dwell window (see readingBufferMs)
    const val POST_SPEECH_MIN_MS = 7_000L
    const val POST_SPEECH_MAX_MS = 24_000L

    /**
     * Post-speech dwell time, scaled by on-screen text length (~18 chars/sec
     * comfortable re-reading pace) on top of a base buffer, clamped to a sane
     * range so trivial replies don't linger forever and long lists stay readable.
     */
    fun readingBufferMs(charCount: Int): Long {
        val base = 6000L
        val perChar = (charCount / 18.0 * 1000).toLong()
        return (base + perChar).coerceIn(POST_SPEECH_MIN_MS, POST_SPEECH_MAX_MS)
    }
}

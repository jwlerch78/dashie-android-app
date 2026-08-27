package com.dashieapp.Dashie.halite.voice

/**
 * What to DO with the response HA returned for a turn: speak it on the device, show it without
 * speaking, or wait for HA's own TTS audio.
 *
 * Extracted from HaVoiceService's `onIntentEnd` so the rules are pinned by tests instead of living
 * as nested ifs. Two of them are non-obvious and were previously undocumented anywhere but a
 * comment: an empty response becomes "I didn't understand that", and a response containing "sorry"
 * is DISPLAYED but never spoken (HA's not-understood replies are grating read aloud, especially
 * after a false wake).
 */
object HaAssistResponsePlanner {

    /** Displayed when HA returns nothing at all. */
    const val NOT_UNDERSTOOD = "I didn't understand that"

    sealed interface Action {
        /** Device TTS speaks [text]. [isCommand] marks an action ack (a tone may replace speech). */
        data class SpeakOnDevice(val text: String, val isCommand: Boolean) : Action

        /** Show [text], speak nothing, then complete. */
        data class DisplayOnly(val text: String) : Action

        /** Show [text] and wait for HA's `tts-end` audio to play. */
        data class AwaitHaAudio(val text: String) : Action
    }

    /**
     * @param response HA's spoken response text (may be empty).
     * @param isCommand HA reported `action_done` — a device command ran, not an informational reply.
     * @param deviceSpeaks the device owns TTS this turn (user choice, or the pipeline has no
     *   tts_engine — see HaAssistStagePlanner).
     */
    fun plan(response: String, isCommand: Boolean, deviceSpeaks: Boolean): Action {
        if (!deviceSpeaks) {
            // HA is synthesizing; show the text now and let the audio arrive.
            return Action.AwaitHaAudio(response.ifEmpty { NOT_UNDERSTOOD })
        }
        if (response.isEmpty()) return Action.DisplayOnly(NOT_UNDERSTOOD)
        // "Sorry, I couldn't understand…" and friends: show, don't say.
        if (response.contains("sorry", ignoreCase = true)) return Action.DisplayOnly(response)
        return Action.SpeakOnDevice(response, isCommand)
    }
}

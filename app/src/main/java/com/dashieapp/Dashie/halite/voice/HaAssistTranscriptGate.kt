package com.dashieapp.Dashie.halite.voice

import org.json.JSONObject

/**
 * What HA Voice Assist mode does with a transcript, once it has one.
 *
 * Extracted from HaVoiceService's `onSttEnd` lambda when local STT arrived (2026-07-29): the
 * transcript can now come from HA's own `stt-end` event OR from an on-device provider, and both
 * must take the SAME decisions — wake-word strip, native weather fulfillment, local
 * timer/music/volume interception. Two copies of that ladder would drift on the first new intercept
 * (the seam rule), so it lives here and both callers execute the outcome.
 *
 * Pure and dependency-free (its two helpers are too), so every branch is unit-testable.
 */
object HaAssistTranscriptGate {

    sealed interface Outcome {
        /** Answer natively via WeatherVoiceTool — HA Assist can't do weather ("no weather is
         *  exposed"). HA must NOT also process this turn. */
        data class Weather(val args: JSONObject) : Outcome

        /** A local command (timer / music / volume) handled on-device. [response] may be blank,
         *  meaning "handled silently, just complete". HA must NOT also process this turn. */
        data class LocalCommand(val response: String) : Outcome

        /** Nothing local claimed it — HA owns intent, execution and the response. */
        object ForwardToHa : Outcome
    }

    /** [text] is the wake-stripped transcript to display and act on; [outcome] is what to do. */
    data class Decision(val text: String, val outcome: Outcome)

    /**
     * @param rawText the transcript as produced by STT, wake word possibly included.
     * @param weatherWired whether native weather fulfillment is available (null callback ⇒ weather
     *   falls through to HA rather than silently dropping).
     * @param intercept the local-command interceptor: returns a spoken response when it claims the
     *   transcript, null to pass it on.
     */
    fun classify(
        rawText: String,
        weatherWired: Boolean,
        intercept: (String) -> String?,
    ): Decision {
        // Strip a leading wake word ("hey dashie what's the weather" → "what's the weather") so the
        // on-screen transcript and the local classifier match the cascade path. Reuses the shared
        // WAKE_PREFIX regex — one seam, no hand-mirror. (Punch #7)
        val text = CascadeDialogSupport.stripWakePrefix(rawText)

        val wxArgs = if (weatherWired) WeatherIntercept.classify(text) else null
        if (wxArgs != null) return Decision(text, Outcome.Weather(wxArgs))

        val local = intercept(text)
        if (local != null) return Decision(text, Outcome.LocalCommand(local))

        return Decision(text, Outcome.ForwardToHa)
    }
}

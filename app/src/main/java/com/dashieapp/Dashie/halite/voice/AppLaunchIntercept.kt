package com.dashieapp.Dashie.halite.voice

/**
 * Native "open <app>" pre-intercept for the cloud voice path.
 *
 * Extracted from VoicePipelineCoordinator (a god-file at its size budget) so the
 * app-launch hook lives outside it. Classifies the transcript with
 * [OpenAppClassifier]; on a match it launches on-device and speaks an ack via the
 * supplied callbacks, fully handling the turn so the brain is skipped. Non-app
 * phrasings return false and fall through to the cloud brain (which carries the
 * open_app tool as the fuzzy-phrasing backstop).
 */
object AppLaunchIntercept {

    /**
     * @param speak      the coordinator's onSpeakResponse (text, voiceId, provider, sessionId, onDone)
     * @param onSpeaking transition UI to the speaking state before the ack
     * @param onComplete end the voice turn
     * @param launch     perform the actual app launch (query, knownPackage, knownLabel)
     * @return true if the turn was handled here (caller must return), false to fall through
     */
    fun tryHandle(
        transcript: String,
        speak: ((String, String?, String?, String?, () -> Unit) -> Unit)?,
        onSpeaking: () -> Unit,
        onComplete: () -> Unit,
        launch: (query: String, knownPackage: String, knownLabel: String) -> Unit,
    ): Boolean {
        val intent = OpenAppClassifier.classify(
            transcript.lowercase().trim().trimEnd('.', '?', '!', ',')
        ) ?: return false

        val p = intent.params
        launch(
            p["query"] as? String ?: "",
            p["knownPackage"] as? String ?: "",
            p["knownLabel"] as? String ?: "",
        )

        val ack = intent.ttsResponse
        if (speak != null && ack.isNotBlank()) {
            onSpeaking()
            speak(ack, null, null, null) { onComplete() }
        } else {
            onComplete()
        }
        return true
    }
}

package com.dashieapp.Dashie.halite.voice.tts

import android.content.Context
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.halite.voice.aec.CascadeAecController

/**
 * Routes native cloud-TTS to the selected vendor (ElevenLabs / Inworld) and owns the
 * cross-cutting concerns for both: shared CR2 credential + credit-denied + AEC wiring,
 * connection warm-up, and barge-in stop. Extracted from HaliteVoiceController so the
 * vendor logic lives in one small place (and to keep the controller off the file-size
 * budget). Vendor selection is the internal `voice.cloudTtsVendor` toggle today; Phase 2
 * swaps [selectVendor] for a personality→(provider,voice) mapping resolved by the brain.
 */
class CloudTtsRouter(
    context: Context,
    private val prefs: HalitePreferences,
    // NB: distinct-from-the-client-property names ON PURPOSE. CloudTtsClient has
    // `credentialProvider`/`onCreditDenied`/`aecControllerProvider` properties;
    // inside the `.wired()` extensions below the receiver's members shadow an
    // outer param of the same name, so `it.credentialProvider = credentialProvider`
    // silently self-assigned null (account JWT never reached native TTS → every
    // cascade-Dialog utterance billed anon/unmetered). Keep the `Fn` suffix.
    private val credentialProviderFn: () -> String?,
    private val onCreditDeniedFn: () -> Unit,
    private val aecControllerProviderFn: () -> CascadeAecController?,
) {
    private fun ElevenLabsTtsClient.wired() = also {
        it.credentialProvider = credentialProviderFn
        it.onCreditDenied = onCreditDeniedFn
        it.aecControllerProvider = aecControllerProviderFn
    }
    private fun InworldTtsClient.wired() = also {
        it.credentialProvider = credentialProviderFn
        it.onCreditDenied = onCreditDeniedFn
        it.aecControllerProvider = aecControllerProviderFn
    }

    private val elevenLabs by lazy { ElevenLabsTtsClient(context).wired() }
    private val inworld by lazy { InworldTtsClient(context).wired() }

    /** Use Inworld this turn? Normally the brain's voice_provider decides (personality-driven).
     *  The cloudTtsVendor pref is a DEV-ONLY force override; default "auto" follows the brain,
     *  with Inworld as the hard fallback when the brain names no provider (e.g. a follow-up). */
    private fun useInworld(brainVoiceProvider: String?): Boolean = when (prefs.voice.cloudTtsVendor) {
        VoicePreferences.CLOUD_TTS_INWORLD -> true          // dev override: force Inworld
        VoicePreferences.CLOUD_TTS_ELEVENLABS -> false      // dev override: force ElevenLabs
        else -> brainVoiceProvider?.lowercase() != "elevenlabs"  // auto: follow brain; default → Inworld
    }

    /**
     * Speak [text] via the vendor that owns the personality's voice. [brainVoiceProvider]
     * (from the brain) selects the vendor; absent → the cloudTtsVendor default. [brainVoiceId]
     * is that vendor's voice (Inworld 'Ashley' / an ElevenLabs id); absent → vendor default.
     */
    fun speak(text: String, brainVoiceId: String?, brainVoiceProvider: String?, sessionId: String?, onStart: () -> Unit, onDone: () -> Unit) {
        val client = if (useInworld(brainVoiceProvider)) inworld else elevenLabs
        val voiceId = brainVoiceId?.takeIf { it.isNotBlank() }
                      ?: if (client === inworld) null else prefs.voice.voiceId
        client.speak(text, voiceId, sessionId, onStart = { onStart() }, onDone = onDone)
    }

    /** Stable engine id for the vendor that will speak — voice_turn_timing.tts_engine. */
    fun engineId(brainVoiceProvider: String?): String =
        if (useInworld(brainVoiceProvider)) "inworld" else "elevenlabs"

    /** Which vendor + voice will speak (for logging). */
    fun describe(brainVoiceId: String?, brainVoiceProvider: String?): String =
        if (useInworld(brainVoiceProvider)) "Inworld (${brainVoiceId?.takeIf { it.isNotBlank() } ?: "Ashley"})"
        else "ElevenLabs (voice=${(brainVoiceId?.takeIf { it.isNotBlank() } ?: prefs.voice.voiceId).ifBlank { "default" }})"

    /** Pre-warm the likely vendor's connection + edge fn (voice init + each wake). The turn's
     *  actual vendor may differ per personality, but the pooled connection to the same functions
     *  host is reused regardless. Warms Inworld (the default) unless a dev override forces EL. */
    fun warmUp() {
        (if (prefs.voice.cloudTtsVendor == VoicePreferences.CLOUD_TTS_ELEVENLABS) elevenLabs else inworld).warmUp()
    }

    /** Cut any in-flight reply on barge-in/cancel — stops both (the idle one is a no-op). */
    fun stop() { elevenLabs.stop(); inworld.stop() }
}

package com.dashieapp.Dashie.halite.voice

import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.halite.voice.stt.SttProviderManager.ProviderType

/**
 * WS-D.1 — what voice can still do when the account is out of credits.
 *
 * Pure and stateless (same contract as [SttProviderFactory] and ReArmPolicy): it takes
 * primitives, returns a plan, and touches no pipeline state and no SharedPreferences. That
 * last part is the point — the $0 fallback **overrides the EFFECTIVE engines at runtime and
 * writes nothing** (2026-07-14). Nothing to undo, nothing to un-break when credits
 * return, and no PIN wall for a wall-mounted kiosk that can't reach Settings anyway.
 *
 * What it replaced: a pre-STT gate that returned early whenever `!spendable && usingCloudStt`,
 * so at $0 the tablet did *nothing at all* — not even a free local HA device command, because
 * the pipeline never got a transcript to classify. "Turn off the lights" never depended on
 * credits; it only looked that way.
 *
 * The three components degrade INDEPENDENTLY:
 *  - **STT** — the irreducible one. HA Assist → HA engine-direct Whisper → own-box Whisper.
 *    If none is configured there is genuinely no free transcript and [anyFreeStt] is false;
 *    the honest block + prompt is then the correct outcome. It cannot be made bulletproof,
 *    especially if local was never set up — so say so rather than pretending.
 *    (Android SpeechRecognizer is not yet a real provider — see SttProviderFactory's TODO.
 *    Wiring it is tracked separately and will widen this ladder to Google-services tablets.)
 *  - **Brain** — HA conversation agent when HA is configured; otherwise the device-control
 *    lane still works (local intent matching + the HA fast path, never billed).
 *  - **TTS** — free local engine. NativeResponseTts already falls back to the device engine on
 *    every non-cloud branch, so this only has to steer away from the cloud one.
 */
object FreeVoicePlan {

    /**
     * @param sttPriority        provider order to use for THIS turn (per-session override).
     * @param ttsProvider        TTS to use for this turn (never [VoicePreferences.TTS_DASHIE_CLOUD]).
     * @param useBrain           may we call a brain at all? False ⇒ device-control lane only.
     * @param useHaConversation  route brain-ish turns to the HA conversation agent.
     * @param anyFreeStt         is a free transcript possible? False ⇒ caller must block + prompt.
     * @param announcement       one-line spoken/shown reason. Null when nothing is degraded.
     */
    data class Plan(
        val sttPriority: List<ProviderType>,
        val ttsProvider: String,
        val useBrain: Boolean,
        val useHaConversation: Boolean,
        val anyFreeStt: Boolean,
        val announcement: String?,
    ) {
        /** Nothing free to fall back to — the caller should block and show the CR2 prompt. */
        val isDeadEnd: Boolean get() = !anyFreeStt
    }

    /** Spoken/shown when we auto-switch. Never silent: a tablet that quietly gets worse reads
     *  as broken, and the user never learns credits are the fix. */
    const val ANNOUNCE_LOCAL = "Out of credits — switching to local voice."
    const val ANNOUNCE_DEVICE_ONLY =
        "Out of credits — I can still control your home, but I can't answer questions."

    /**
     * Resolve what's still possible at $0.
     *
     * @param haConfigured    HA origin + token present (HA Assist STT / conversation agent).
     * @param haSttEngineId   engine id for HA engine-direct Whisper ("" = not configured).
     * @param localSttUrl     own-box Whisper base url ("" = not configured).
     * @param localTtsUrl     own-box TTS base url ("" = not configured).
     * @param haTtsEngineId   HA engine-direct TTS id ("" = not configured).
     * @param androidRecognizerAvailable  is Android's SpeechRecognizer usable here? (False on
     *        Fire OS / Lineage — no Google services.)
     */
    fun resolve(
        haConfigured: Boolean,
        haSttEngineId: String,
        localSttUrl: String,
        localTtsUrl: String,
        haTtsEngineId: String,
        androidRecognizerAvailable: Boolean = false,
    ): Plan {
        // ── STT ladder: prefer the user's own box, then HA. Each entry is only included when
        // its own runtime config exists, so the priority list never leads with a provider whose
        // isAvailable() will immediately fail.
        val stt = buildList {
            if (localSttUrl.isNotBlank()) add(ProviderType.LOCAL_WHISPER)
            if (haConfigured && haSttEngineId.isNotBlank()) add(ProviderType.HA_ENGINE)
            if (haConfigured) add(ProviderType.HA_ASSIST)
            // Last rung: Android's built-in recognizer. Free to us, needs no setup, and covers
            // the plug-and-play tablet with no HA and no Whisper box — but it's LAST because it
            // hands the mic away for the session (no AEC, no barge-in). Gated on the CALLER's
            // availability check rather than added unconditionally, so [anyFreeStt] keeps
            // meaning "a free transcript is genuinely possible on THIS device" — on Fire OS /
            // Lineage (no Google services) it stays false and the honest block still applies.
            if (androidRecognizerAvailable) add(ProviderType.ANDROID_NATIVE)
        }
        // DEEPGRAM is deliberately absent from every branch — it's the billed one.

        // ── TTS: any configured DIRECT free engine, else the device engine (always present).
        // Deliberately NOT VA_DEFAULT (the HA pipeline's own TTS): that depends on the HA Assist
        // pipeline having a TTS engine configured, and when it doesn't the run hard-fails with
        // "the pipeline does not support text-to-speech" and drops the whole turn (device 2026-07-22).
        // A direct HA Piper engine (haTtsEngineId) or the device's own TTS always work, so the free
        // fallback speaks reliably regardless of the HA pipeline's TTS config.
        val tts = when {
            localTtsUrl.isNotBlank() -> VoicePreferences.TTS_LOCAL_URL
            haConfigured && haTtsEngineId.isNotBlank() -> VoicePreferences.TTS_HA_ENGINE
            else -> VoicePreferences.TTS_ANDROID_VOICE
        }

        // ── Brain: only HA's conversation agent is free. Without it we keep the device-control
        // lane (local intent matching + HA fast path) and answer nothing — which is still far
        // better than the old behavior of doing nothing at all.
        val useHaConversation = haConfigured

        return Plan(
            sttPriority = stt,
            ttsProvider = tts,
            useBrain = useHaConversation,
            useHaConversation = useHaConversation,
            anyFreeStt = stt.isNotEmpty(),
            announcement = when {
                stt.isEmpty() -> null                       // dead end — caller shows the prompt
                useHaConversation -> ANNOUNCE_LOCAL
                else -> ANNOUNCE_DEVICE_ONLY
            },
        )
    }

    /** Convenience binding for the live prefs (same overload shape as SttProviderFactory). */
    fun resolve(
        voice: VoicePreferences,
        haOrigin: String?,
        haToken: String,
        androidRecognizerAvailable: Boolean = false,
    ): Plan = resolve(
        haConfigured = !haOrigin.isNullOrBlank() && haToken.isNotBlank(),
        haSttEngineId = voice.haSttEngineId,
        localSttUrl = voice.localSttUrl,
        localTtsUrl = voice.localTtsUrl,
        haTtsEngineId = voice.haTtsEngineId,
        androidRecognizerAvailable = androidRecognizerAvailable,
    )
}

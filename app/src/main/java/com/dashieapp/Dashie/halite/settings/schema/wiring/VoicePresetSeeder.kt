package com.dashieapp.Dashie.halite.settings.schema.wiring

import android.util.Log
import com.dashieapp.Dashie.halite.preferences.AiPreferences
import com.dashieapp.Dashie.halite.preferences.VoicePreferences

/**
 * Seeds the granular pipeline keys when the user picks an Open Brain preset
 * (Cloud / Hybrid / Local / HA Voice Assist) — the Kotlin mirror of the
 * console's VoiceAiPage.selectPreset/_seedProvider (Open Brain plan §6/§7,
 * decisions John 2026-07-11/12):
 *
 *  - controlMethod stays the runtime key, derived from the preset, so every
 *    existing controlMethod consumer keeps working.
 *  - Hybrid: voice goes LOCAL — TTS flips to detected Piper (an engine id is
 *    known) else Android voice; STT flips to Whisper when known, else STAYS
 *    on Deepgram (Android STT would be a quality downgrade nobody asked for).
 *  - Local: the brain is the user's own model; voice engines local.
 *  - HA Assist: the Assist pipeline owns STT/agent/TTS (still customizable).
 *  - A valid, already-local provider choice is never overridden.
 *  - Live is Cloud-only: leaving Cloud drops agentMode back to 'single'.
 *  - First Piper selection defaults the voice to amy (low).
 *
 * Kept out of SettingsCallbackWiring (already 1,600+ lines) per the
 * MainActivity-growth rules.
 */
object VoicePresetSeeder {

    private const val TAG = "VoicePresetSeeder"

    /**
     * With no account, force the stored preset onto the only one that is offered.
     *
     * 🔴 THE DEFECT THIS CLOSES (on device, 2026-08-24). The no-account lock
     * ([VoiceAiOptions.isLockedToHaAssist]) removes Cloud/Hybrid/Local from the picker but does
     * NOT touch the stored value. A device whose preset is any of those then shows a page with
     * **one option, unchecked, and no way to select it** — the row that would fix it is the row
     * that is already the only row. John caught it from the missing checkmark.
     *
     * ⚠️ [revertOffCloud] already lands `ha_assist` on the de-auth paths, so a device that SIGNS
     * OUT is fine. The uncovered case is a device that **upgrades into this build** already on
     * cloud/hybrid/local and never passes through a de-auth transition — nothing would ever
     * correct it.
     *
     * Writes the preset AND runs [applyPreset] for its consequences, so `controlMethod` and
     * `ai.model` land with it. Writing only the one key is what produced the incoherent
     * `preset=local` + `controlMethod=voice_assistant` pair observed on the device.
     *
     * 🔴 **Both calls are required and the first version had only one.** [applyPreset] applies a
     * preset's CONSEQUENCES; it never writes `pipelinePreset` itself, because on the normal path
     * the picker's `settingKey` binding has already stored the value before `onChanged` fires.
     * Called without that binding, `applyPreset` alone left the preset untouched — and this
     * function's own WARN said it had coerced. Device-caught: the log claimed success while the
     * pref still read `local`. ([revertOffCloud] sets the key on its own line for the same reason.)
     *
     * @return true if anything changed, so the caller can reinit / re-render.
     */
    fun enforceNoAccountPresetFloor(voice: VoicePreferences, ai: AiPreferences): Boolean {
        val stored = voice.pipelinePreset
        if (stored == "ha_assist") return false
        voice.pipelinePreset = "ha_assist"      // the VALUE — applyPreset does not write it
        applyPreset(voice, ai, "ha_assist")     // the CONSEQUENCES — controlMethod, ai.model, agent
        android.util.Log.w("VoicePresetSeeder",
            "DROP: stored preset '$stored' is not offered without an account — coerced to " +
                "ha_assist (now '${voice.pipelinePreset}') so the picker and the pipeline agree. " +
                "(A preset the page cannot show is a preset the user cannot fix.)")
        return true
    }

    fun applyPreset(voice: VoicePreferences, ai: AiPreferences, preset: String) {
        // Live is Cloud-only — any other preset returns the agent to cascade.
        if (preset != "cloud" && voice.conversationEngineMode == "live") {
            // Loud: this DROPS a Live engine selection. The stored conversationModel is left
            // alone on purpose (the user may switch back to Cloud), which means the console will
            // still display a Live model beside a cascade engine until they do — see the
            // coherence block below for why that pair must never be silent.
            Log.w(TAG, "DROP: preset=$preset is not Cloud — demoting agentMode live -> single " +
                "(conversationModel='${voice.conversationModel}' kept but inert)")
            voice.agentMode = "single"
        }
        when (preset) {
            "cloud" -> {
                voice.voiceControlMethod = VoicePreferences.VOICE_METHOD_DASHIE_CLOUD
                if (voice.ttsProvider != VoicePreferences.TTS_DASHIE_CLOUD) {
                    voice.ttsProvider = VoicePreferences.TTS_DASHIE_CLOUD
                }
                if (voice.sttProvider != VoicePreferences.STT_DASHIE_CLOUD) {
                    voice.sttProvider = VoicePreferences.STT_DASHIE_CLOUD
                }
                if (ai.aiModel == "local" || ai.aiModel == VoicePreferences.AI_MODEL_HOME_ASSISTANT) {
                    setModel(voice, ai, DEFAULT_CLOUD_MODEL)
                }
            }
            "hybrid" -> {
                voice.voiceControlMethod = VoicePreferences.VOICE_METHOD_DASHIE_CLOUD
                // Hybrid = cloud AI + on-device voice: default to the BUNDLED on-device engines
                // (sherpa STT / built-in device TTS), not HA Whisper/Piper (2026-07-28). The
                // provider chain falls back to HA/cloud where the engine isn't bundled.
                if (!isLocalTts(voice.ttsProvider)) {
                    voice.ttsProvider = VoicePreferences.TTS_ANDROID_VOICE
                }
                if (!isLocalStt(voice.sttProvider)) {
                    // base, not tiny — tiny is retired from the offering (2026-08-20).
                    voice.sttProvider = VoicePreferences.STT_SHERPA_MOONSHINE_BASE
                }
                if (ai.aiModel == "local" || ai.aiModel == VoicePreferences.AI_MODEL_HOME_ASSISTANT) {
                    setModel(voice, ai, DEFAULT_CLOUD_MODEL)
                }
            }
            "local" -> {
                voice.voiceControlMethod = VoicePreferences.VOICE_METHOD_DASHIE_CLOUD
                setModel(voice, ai, "local")
                if (!isLocalTts(voice.ttsProvider)) {
                    voice.ttsProvider = if (voice.haTtsEngineId.isNotEmpty())
                        VoicePreferences.TTS_HA_ENGINE else VoicePreferences.TTS_ANDROID_VOICE
                    seedPiperVoiceIfMissing(voice)
                }
                if (!isLocalStt(voice.sttProvider)) {
                    // Whisper via HA when known; else own-box URL row (user
                    // fills the URL — Android STT isn't shippable yet).
                    voice.sttProvider = if (voice.haSttEngineId.isNotEmpty())
                        VoicePreferences.STT_HA_ENGINE else VoicePreferences.STT_LOCAL_URL
                }
            }
            "ha_assist" -> {
                voice.voiceControlMethod = VoicePreferences.VOICE_METHOD_VOICE_ASSISTANT
                if (!isLocalTts(voice.ttsProvider)) {
                    voice.ttsProvider = VoicePreferences.TTS_VA_DEFAULT
                }
                if (!isLocalStt(voice.sttProvider)) {
                    voice.sttProvider = VoicePreferences.STT_VA_DEFAULT
                }
                // HA owns the conversation agent (mirrors the legacy
                // controlMethod switch, incl. the N6 dual-write).
                setModel(voice, ai, VoicePreferences.AI_MODEL_HOME_ASSISTANT)
                ai.personalityId = "home_assistant"
            }
        }
        // Cloud & Hybrid ship the conversational defaults ON (console/JS parity —
        // applyPresetSeeding, voice-ai-options.js): Conversation Dialog + Retrieve
        // Pictures. Local & HA Assist drop Retrieve Pictures (it's cloud web-image
        // search, billed to credits). Was missing natively — a first-time cloud
        // setup left both off on the tablet.
        if (preset == "cloud" || preset == "hybrid") {
            // 🔴 ENGINE COHERENCE (product decision 2026-08-21). This branch used to be a blind
            // `if (engineMode != "live") agentMode = "dialog"`, which DERIVED NOTHING from the
            // selected model — so a household whose account carried a Gemini Live
            // `conversationModel` got `agentMode='dialog'` stamped over it on the HA→Cloud flip.
            // agentMode is the SOLE engine selector (VoiceSessionAccess.effectiveAgentMode →
            // VoicePipelineCoordinator: live→Gemini Live, dialog/single→cascade), so the tablet
            // ran the cascade on ai.model=claude while the console displayed the Live model the
            // user had picked. Measured on a live household: 10/10 turns model=claude-sonnet.
            //
            // A non-blank conversationModel IS the Live selection (see VoicePreferences
            // .conversationModeEnabled), and it is the MORE SPECIFIC signal — a user who wants a
            // cascade engine has no Live model selected. So derive from it rather than defaulting
            // over it. Never strip a Live selection on a preset re-apply.
            if (preset == "cloud" && voice.conversationModeEnabled) {
                if (voice.agentMode != "live") {
                    // Not a DROP — this is the repair. Loud anyway: it is the exact disagreement
                    // that hid this bug, and it should be visible when it is corrected.
                    Log.w(TAG, "engine coherence: agentMode='${voice.agentMode}' disagreed with " +
                        "Live conversationModel='${voice.conversationModel}' — deriving agentMode=live")
                }
                voice.agentMode = "live"
            } else if (voice.conversationEngineMode != "live") {
                voice.agentMode = "dialog"
            }
            ai.retrievePicturesEnabled = true
        } else {
            ai.retrievePicturesEnabled = false
        }
    }

    /**
     * De-authorized from Dashie cloud — sign-out, household sharing revoked, or the
     * device removed in the console. Drop the cloud-share flag and revert any cloud
     * STT/TTS/AI/control-method to the non-cloud (HA Voice Assist) default so the
     * tablet stops running — and the Voice & AI page stops showing — the cloud
     * pipeline. Mirror of VoiceAiSettings' `!cloudAvailable` fallback, but callable
     * from the de-auth paths (KioskJwtRefresher.endSession, ACTION_DASHIE_SIGN_OUT)
     * that fire without the settings page open. Context-free; the caller reinits the
     * running voice controller. Field report 2026-07-22: revoked kiosk "kept cloud
     * access going". These paths are HA kiosks, so HA Voice Assist is the right floor.
     */
    fun revertOffCloud(voice: VoicePreferences) {
        voice.cloudShareAvailable = false
        if (voice.sttProvider == VoicePreferences.STT_DASHIE_CLOUD) {
            voice.sttProvider = VoicePreferences.STT_VA_DEFAULT
        }
        if (voice.ttsProvider == VoicePreferences.TTS_DASHIE_CLOUD) {
            voice.ttsProvider = VoicePreferences.TTS_ANDROID_VOICE
        }
        if (voice.voiceControlMethod == VoicePreferences.VOICE_METHOD_DASHIE_CLOUD) {
            voice.voiceControlMethod = VoicePreferences.VOICE_METHOD_VOICE_ASSISTANT
        }
        if (voice.aiModel != VoicePreferences.AI_MODEL_HOME_ASSISTANT) {
            voice.aiModel = VoicePreferences.AI_MODEL_HOME_ASSISTANT
        }
        // Live is Cloud-only — a revoked device can't run it.
        if (voice.conversationEngineMode == "live") voice.agentMode = "single"
        voice.pipelinePreset = "ha_assist"
    }

    private const val DEFAULT_CLOUD_MODEL = "gemini-2.5-flash"

    /** N6 dual-write: the synced key reads AiPreferences.aiModel while the
     *  voice pipeline reads VoicePreferences.aiModel — keep them agreeing. */
    private fun setModel(voice: VoicePreferences, ai: AiPreferences, model: String) {
        ai.aiModel = model
        voice.aiModel = model
    }

    private fun isLocalTts(p: String) = p == VoicePreferences.TTS_ANDROID_VOICE ||
        p == VoicePreferences.TTS_HA_ENGINE || p == VoicePreferences.TTS_LOCAL_URL ||
        p == VoicePreferences.TTS_VA_DEFAULT

    private fun isLocalStt(p: String) = p == VoicePreferences.STT_HA_ENGINE ||
        p == VoicePreferences.STT_LOCAL_URL || p == VoicePreferences.STT_VA_DEFAULT ||
        p == VoicePreferences.STT_ANDROID_VOICE ||
        p == VoicePreferences.STT_SHERPA_MOONSHINE_TINY ||
        p == VoicePreferences.STT_SHERPA_MOONSHINE_BASE

    /** amy (low) default on first Piper selection (2026-07-12). */
    private fun seedPiperVoiceIfMissing(voice: VoicePreferences) {
        if (voice.ttsProvider == VoicePreferences.TTS_HA_ENGINE && voice.haTtsVoiceId.isEmpty()) {
            voice.haTtsVoiceId = "en_US-amy-low"
        }
    }
}

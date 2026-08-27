package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * Voice-related preferences for Dashie Kiosk.
 *
 * Manages:
 * - Voice enabled state and wake word settings
 * - Response handling (show/read responses)
 * - Voice pipeline configuration (HA Assist vs AI routing)
 * - STT provider settings (Deepgram)
 * - AI model and web search backend selection
 * - Voice license and trial management
 * - Sample collection consent and settings
 * - Microphone mute state
 *
 * Uses the same SharedPreferences file as HalitePreferences for backward compatibility.
 */
class VoicePreferences(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_lite_prefs"

        // Voice enabled
        private const val KEY_VOICE_ENABLED = "voice_enabled"

        // Microphone state
        private const val KEY_MIC_MUTED = "mic_muted"

        // Response handling
        private const val KEY_SHOW_RESPONSES = "show_responses"
        private const val KEY_READ_RESPONSES_ALOUD = "read_responses_aloud"
        private const val KEY_RESPONSE_HANDLING = "response_handling"

        // Response handling mode options
        const val RESPONSE_HANDLING_READ_AND_DISPLAY = "read_and_display"  // Both TTS and visual
        const val RESPONSE_HANDLING_READ_ONLY = "read_only"                // TTS only, no card
        const val RESPONSE_HANDLING_DISPLAY_ONLY = "display_only"          // Visual card only
        const val RESPONSE_HANDLING_NONE = "none"                          // Silent, no feedback

        // Confirmation tone: played for command acknowledgements instead of a
        // spoken response (when a speaking Response Format is selected). Reuses
        // the video-feed chime tones loaded by AlertSoundService. The type value
        // "disabled" turns the feature off (the default).
        private const val KEY_CONFIRMATION_TONE_TYPE = "confirmation_tone_type"
        private const val KEY_CONFIRMATION_TONE_VOLUME = "confirmation_tone_volume"
        const val CONFIRMATION_TONE_DISABLED = "disabled"
        // Default = the simple beep (matches the long-standing cloud-mode beep, now
        // volume-adjustable). "disabled" means "speak the response" instead of a tone.
        const val DEFAULT_CONFIRMATION_TONE = "notify_simple_beep"
        const val DEFAULT_CONFIRMATION_TONE_VOLUME = 0.40f

        // Display format options (how the visual response is shown)
        private const val KEY_DISPLAY_FORMAT = "voice_display_format"
        const val DISPLAY_FORMAT_NOTIFICATION = "notification"  // Mini card, auto-dismiss
        const val DISPLAY_FORMAT_SIDEBAR = "sidebar"            // Right panel, stays until dismissed
        const val DISPLAY_FORMAT_FULLSCREEN = "fullscreen"      // Full overlay, large font

        // Voice pipeline selection (for HA Assist)
        private const val KEY_VOICE_PIPELINE_ID = "voice_pipeline_id"
        private const val KEY_VOICE_PIPELINE_NAME = "voice_pipeline_name"
        private const val KEY_PREFERRED_PIPELINE_CACHED_NAME = "preferred_pipeline_cached_name"
        const val VOICE_PIPELINE_DEFAULT = ""  // Empty string = use HA's preferred/default pipeline

        // Voice pipeline mode (derived from provider selections for backward compat)
        private const val KEY_VOICE_PIPELINE_MODE = "voice_pipeline_mode"
        const val VOICE_PIPELINE_MODE_HA = "ha"
        const val VOICE_PIPELINE_MODE_AI = "ai"

        // Voice control method (top-level choice)
        private const val KEY_VOICE_CONTROL_METHOD = "voice_control_method"
        const val VOICE_METHOD_VOICE_ASSISTANT = "voice_assistant"  // HA voice pipeline (customizable)
        const val VOICE_METHOD_DASHIE_CLOUD = "dashie_cloud"       // Dashie cloud services (subscription)

        // Customize voice pipeline toggle
        private const val KEY_CUSTOMIZE_PIPELINE = "customize_voice_pipeline"

        // Cached result of the integration's /api/dashie/voice/status probe —
        // whether the add-on account holder has enabled household Dashie Cloud
        // sharing. Lets an anonymous kiosk offer "Dashie Cloud" (§16.5 OR)
        // without the tablet being logged in. Refreshed in the background.
        private const val KEY_CLOUD_SHARE_AVAILABLE = "cloud_share_available"
        private const val KEY_CLOUD_SHARE_CHECKED_AT = "cloud_share_checked_at"
        private const val KEY_KIOSK_AGENT_MODE = "kiosk_agent_mode"
        private const val KEY_KIOSK_RETRIEVE_PICTURES = "kiosk_retrieve_pictures"
        // Which hub serves the household gateway — from the status probe's `hub`
        // field. Two editions, ONE brand (T2b/T2h, 2026-07-30): the PUBLISHED
        // edition ("Dashie for Home Assistant", the open add-on) emits a hub id;
        // the FULL edition's integration has no such field at all, so "" = full.
        // Brands the "manage in ..." strings — see CreditUrls.managementSurface.
        //
        // The pref KEY is deliberately unchanged: renaming it would orphan the
        // cached value on every existing install.
        private const val KEY_HUB_BRAND = "hub_brand"

        /** Dashie's published HA edition — `dashie_voice` integration v0.7.0+
         *  (shipped in dev add-on 0.9.6). JS_KOTLIN_CONTRACTS #59. */
        const val HUB_DASHIE_VOICE = "dashie_voice"

        /** CHICKADEE's voice integration — the independent free/BYOK HA brand
         *  (`chickadee-voice-integration`, generated; emits this from
         *  `chickadee_voice/voice_view.py`). A distinct PRODUCT from
         *  [HUB_DASHIE_VOICE], not a rename of it. JS_KOTLIN_CONTRACTS #59. */
        const val HUB_CHICKADEE_VOICE = "chickadee_voice"

        /** ⚠️ NOT CHICKADEE — despite the string. This is the pre-0.7.0 name for
         *  [HUB_DASHIE_VOICE], still emitted by the PROD add-on (`dashie-ha/` 0.8.6 —
         *  prod is a promotion, not a build, so it lags the dev channel by design).
         *
         *  It was called `HUB_CHICKADEE_LEGACY` until 2026-08-01. That name became
         *  actively dangerous the moment a real `chickadee_voice` existed: it reads as
         *  "Chickadee's id" and is not. Renamed rather than deleted — the value is still
         *  on the wire.
         *
         *  Must keep matching until prod has been promoted past the rename AND the APKs
         *  in the field have had an adoption window; dropping it earlier makes every
         *  un-promoted household look like the full edition. */
        const val HUB_DASHIE_VOICE_PRE_0_7 = "chickadee"

        /** One-shot latch so an unrecognised hub warns once per process, not per render. */
        @Volatile private var warnedUnknownHub = false

        /**
         * Does this `hub` value mean the PUBLISHED edition serves the gateway?
         *
         * FAILS TOWARD PUBLISHED for any unrecognised non-empty value. Only the
         * published integration emits `hub` at all (the full one omits it), so a
         * value we don't know is far more likely to be a *newer published* id than
         * a full-edition device — which is exactly what happened in 2026-07: the
         * integration renamed `chickadee` → `dashie_voice`, Kotlin wasn't updated,
         * and the old `== HUB_CHICKADEE` test fell to false SILENTLY on every
         * dev-add-on household. Loud + restrictive-toward-the-known-emitter beats
         * silent (CLAUDE.md "No silent drops").
         */
        fun isPublishedHubValue(hub: String): Boolean {
            if (hub.isEmpty()) return false                 // full edition — field absent
            if (hub == HUB_DASHIE_VOICE) return true
            if (hub == HUB_CHICKADEE_VOICE) return true
            if (hub == HUB_DASHIE_VOICE_PRE_0_7) return true
            if (!warnedUnknownHub) {
                warnedUnknownHub = true
                android.util.Log.w("VoicePreferences",
                    "DROP: unrecognised gateway hub '$hub' — expected " +
                    "'$HUB_DASHIE_VOICE', '$HUB_CHICKADEE_VOICE', or pre-0.7 " +
                    "'$HUB_DASHIE_VOICE_PRE_0_7'. Treating it as the PUBLISHED edition (only " +
                    "those integrations emit `hub` at all). Add the new id here — AND compare " +
                    "it above, a constant nothing tests is decoration — plus a " +
                    "JS_KOTLIN_CONTRACTS #59 row; do not rely on this fallback.")
            }
            return true
        }

        // Provider selection (used when customizing)
        private const val KEY_STT_PROVIDER = "stt_provider"
        private const val KEY_TTS_PROVIDER = "tts_provider"
        private const val KEY_CLOUD_TTS_VENDOR = "cloud_tts_vendor"
        private const val KEY_LOCAL_TTS_URL = "local_tts_url"
        // Console-owned Local Engines registry (display-only url → name lookup). SYNC_EXEMPT:
        // the device never writes it, so it needs no PREF_KEY_TO_CATEGORY row.
        private const val KEY_LOCAL_ENGINES = "local_engines"
        private const val KEY_LOCAL_TTS_VOICE = "local_tts_voice"
        // Own-box OpenAI-compatible Whisper URL (local_stt_url transport).
        private const val KEY_LOCAL_STT_URL = "local_stt_url"
        // HA engine-direct (ha_engine transport): which detected HA engine to hit
        // + the chosen voice. Engine name lives ONLY here, never in the provider id.
        private const val KEY_HA_TTS_ENGINE = "ha_tts_engine"
        private const val KEY_HA_TTS_VOICE = "ha_tts_voice"
        private const val KEY_HA_STT_ENGINE = "ha_stt_engine"
        // Open Brain preset (cloud|hybrid|local|ha_assist) — the console's top-level
        // Voice & AI selector; controlMethod stays derived/synced alongside it.
        private const val KEY_PIPELINE_PRESET = "pipeline_preset"
        // Which HA entities voice can control (dashboard | assist) — account setting, mirrored to a
        // shared-account kiosk so it honors the same pick the webapp does (20260717).
        private const val KEY_ENTITY_SOURCE = "entity_source"
        // "My own AI" (BYO model, WS-I): OpenAI-compatible endpoint + model name.
        // The API KEY itself never reaches the device (add-on/console only) —
        // only a boolean "a key is set" for the settings UI indicator.
        private const val KEY_LOCAL_LLM_URL = "local_llm_url"
        private const val KEY_LOCAL_LLM_MODEL = "local_llm_model"
        private const val KEY_LOCAL_LLM_KEY_SET = "local_llm_key_set"

        // Realtime conversation mode (Gemini Live) — empty = off
        private const val KEY_CONVERSATION_MODEL = "conversation_model"
        // Gemini Live prebuilt voice (voiceName) — empty = engine default (Aoede)
        private const val KEY_CONVERSATION_VOICE = "conversation_voice"
        // BYOK-for-Live: run Gemini Live on the household's OWN AI-Studio key (AI free to
        // Dashie). Account-level toggle; device brokers an ephemeral token from the box.
        private const val KEY_LIVE_BYOK = "live_byok"
        // When true, every wake enters conversation mode instead of the cascade
        private const val KEY_CONVERSATION_ALWAYS = "conversation_always"
        private const val KEY_ALWAYS_OPEN_DIALOG = "always_open_dialog"
        // Conversation Agent Mode (canonical): "live" | "dialog" | "single". Account-level,
        // pushed via setVoiceSettings. Selects Engine A (Live) vs the cascade Dialog loop.
        private const val KEY_AGENT_MODE = "agent_mode"
        // Device-local: run software echo cancellation during a live dialog so Dashie
        // doesn't hear its own voice. Default on; per-device so we can A/B it easily.
        private const val KEY_REALTIME_AEC = "realtime_aec"
        // Device-local: AEC3 on the CASCADE path (dialog/single cloud TTS) — WS-A.2.
        private const val KEY_CASCADE_AEC = "cascade_aec"

        // STT provider options
        const val STT_ANDROID_VOICE = "android_voice"
        // Bundled sherpa-onnx on-device STT (contract #16 ids — mirror in
        // voice-ai-value-ids.js STT_PROVIDER_IDS; dev flavors only for now)
        const val STT_SHERPA_MOONSHINE_TINY = "sherpa_moonshine_tiny"
        const val STT_SHERPA_MOONSHINE_BASE = "sherpa_moonshine_base"
        const val STT_VA_DEFAULT = "va_default"       // Use HA voice pipeline's STT
        const val STT_DASHIE_CLOUD = "dashie_cloud"
        const val STT_HA_ENGINE = "ha_engine"         // HA engine-direct (engine in haSttEngineId), no Assist pipeline
        const val STT_LOCAL_URL = "local_stt_url"     // Own-box OpenAI-compatible Whisper

        // TTS provider options
        const val TTS_ANDROID_VOICE = "android_voice"
        const val TTS_VA_DEFAULT = "va_default"       // Use HA voice pipeline's TTS
        const val TTS_DASHIE_CLOUD = "dashie_cloud"
        const val TTS_LOCAL_URL = "local_url"         // Direct OpenAI-compatible box (Kokoro)
        const val TTS_HA_ENGINE = "ha_engine"         // HA engine-direct (engine in haTtsEngineId), no Assist pipeline

        // Cloud TTS vendor (under dashie_cloud). Default "auto" = follow the brain's
        // voice_provider (personality-driven); "inworld"/"elevenlabs" are a DEV-ONLY force
        // override for A/B testing. Not user-facing; set via setVoiceSettings({cloudTtsVendor}).
        const val CLOUD_TTS_AUTO = "auto"
        const val CLOUD_TTS_ELEVENLABS = "elevenlabs"
        const val CLOUD_TTS_INWORLD = "inworld"

        // AI model "Home Assistant" option (passes through to HA conversation agent)
        const val AI_MODEL_HOME_ASSISTANT = "home_assistant"

        // STT Provider settings (legacy)
        private const val KEY_DEEPGRAM_API_KEY = "deepgram_api_key"
        private const val KEY_PREFER_DEEPGRAM_STT = "prefer_deepgram_stt"
        private const val KEY_USE_OVERLAY_NLP = "use_overlay_nlp"

        // AI model selection
        private const val KEY_AI_MODEL = "ai_model"
        const val AI_MODEL_OLLAMA = "ollama"
        // "My own AI" sentinel (BYO local/self-hosted model — Ollama/Hermes/OpenAI-compatible).
        // The canonical value the picker + engine-resolution write (ai.model='local'); free, so
        // NOT billable. (AI_MODEL_OLLAMA is a legacy alias kept for the same exclusion.)
        const val AI_MODEL_LOCAL = "local"

        // On-prem brain routing (build plan §13.16/§13.17). DEVICE-LOCAL toggle (NOT account-
        // synced): when true, the AI lane routes to the add-on's on-prem brain (local model on
        // the HA box) via options.route="local" instead of the cloud edge fn. Kept device-local
        // so testing one kiosk never flips the cloud path on other surfaces; the proper account-
        // level "My Local LLM" selector is the Wave-2 §16.4 UI work.
        // Flip for testing:  adb shell ... (SharedPreferences "use_local_brain" = true)
        private const val KEY_USE_LOCAL_BRAIN = "use_local_brain"
        // Add-on-reported brain route ("local"|"cloud"|""), cached from the
        // /api/dashie/voice/status probe (Open Brain §5). See [brainRoute].
        internal const val KEY_BRAIN_ROUTE = "brain_route_cached"
        // When the brain_route probe last succeeded (epoch ms) — TTL gate for the
        // wake-time re-probe (refreshBrainRouteIfStale) so adding a key takes effect
        // without an app restart. See [brainRouteCheckedAtMs].
        internal const val KEY_BRAIN_ROUTE_CHECKED_AT = "brain_route_checked_at"
        const val BRAIN_ROUTE_LOCAL = "local"
        const val BRAIN_ROUTE_CLOUD = "cloud"

        // Web search backend
        private const val KEY_WEB_SEARCH_BACKEND = "web_search_backend"
        const val WEB_SEARCH_DISABLED = "disabled"
        const val WEB_SEARCH_SEARXNG = "searxng"

        // Voice license & trial
        private const val KEY_VOICE_TRIAL_START = "voice_trial_start"
        private const val KEY_VOICE_TRIAL_EXPIRY = "voice_trial_expiry"
        private const val KEY_VOICE_LICENSE_STATUS = "voice_license_status"
        private const val KEY_VOICE_LICENSE_EMAIL = "voice_license_email"
        private const val KEY_VOICE_LICENSE_LAST_CHECK = "voice_license_last_check"
        private const val KEY_VOICE_LICENSE_DERIVED = "voice_license_derived"
        private const val KEY_VOICE_DEVICE_ID = "voice_device_id"

        // Voice & personality selection
        private const val KEY_VOICE_KEY = "voice_key"
        private const val KEY_VOICE_ID = "voice_id"
        const val DEFAULT_VOICE_KEY = "BELLA"

        // Sample collection (wake word training data)
        private const val KEY_SAMPLE_COLLECTION_ENABLED = "sample_collection_enabled"
        private const val KEY_SAMPLE_COLLECTION_CONSENT = "sample_collection_consent"

        // microWakeWord sensitivity (probability cutoff override)
        private const val KEY_MWW_SENSITIVITY = "mww_sensitivity"
        const val MWW_SENSITIVITY_DEFAULT = "default"  // Use model's built-in cutoff
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ========== Voice Enabled ==========

    /**
     * Voice commands enabled ("Hey Dashie" wake word)
     */
    var voiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_VOICE_ENABLED, value).commit() }

    // ========== Microphone State ==========

    /**
     * Microphone muted state
     * When true, voice commands won't capture audio
     * Default: false (microphone active)
     */
    var micMuted: Boolean
        get() = prefs.getBoolean(KEY_MIC_MUTED, false)
        set(value) { prefs.edit().putBoolean(KEY_MIC_MUTED, value).commit() }

    // ========== microWakeWord Sensitivity ==========

    /**
     * microWakeWord sensitivity level (probability cutoff override).
     * Stored as a string: "default", or a float value like "0.85".
     * "default" means use each model's built-in probabilityCutoff.
     */
    var mwwSensitivity: String
        get() = prefs.getString(KEY_MWW_SENSITIVITY, MWW_SENSITIVITY_DEFAULT) ?: MWW_SENSITIVITY_DEFAULT
        set(value) { prefs.edit().putString(KEY_MWW_SENSITIVITY, value).commit() }

    /**
     * Get the effective MWW probability cutoff.
     * Returns null if "default" (caller should use model's built-in value).
     */
    fun getMwwCutoffOverride(): Float? {
        val value = mwwSensitivity
        if (value == MWW_SENSITIVITY_DEFAULT) return null
        return value.toFloatOrNull()
    }

    // ========== Response Handling ==========

    /**
     * Show voice responses on screen (result card after voice command)
     * Default: true (show responses)
     * @deprecated Use responseHandling instead
     */
    var showResponses: Boolean
        get() = responseHandling == RESPONSE_HANDLING_READ_AND_DISPLAY || responseHandling == RESPONSE_HANDLING_DISPLAY_ONLY
        set(value) {
            // Update via responseHandling to maintain consistency
            val currentRead = readResponsesAloud
            responseHandling = when {
                value && currentRead -> RESPONSE_HANDLING_READ_AND_DISPLAY
                value && !currentRead -> RESPONSE_HANDLING_DISPLAY_ONLY
                !value && currentRead -> RESPONSE_HANDLING_READ_ONLY
                else -> RESPONSE_HANDLING_NONE
            }
        }

    /**
     * Read voice responses aloud (text-to-speech)
     * Default: true (read responses aloud)
     * @deprecated Use responseHandling instead
     */
    var readResponsesAloud: Boolean
        get() = responseHandling == RESPONSE_HANDLING_READ_AND_DISPLAY || responseHandling == RESPONSE_HANDLING_READ_ONLY
        set(value) {
            // Update via responseHandling to maintain consistency
            val currentShow = showResponses
            responseHandling = when {
                value && currentShow -> RESPONSE_HANDLING_READ_AND_DISPLAY
                value && !currentShow -> RESPONSE_HANDLING_READ_ONLY
                !value && currentShow -> RESPONSE_HANDLING_DISPLAY_ONLY
                else -> RESPONSE_HANDLING_NONE
            }
        }

    /**
     * Response handling mode: controls how voice responses are presented
     * Options: "read_and_display", "read_only", "display_only", "none"
     * Migrates from old showResponses + readResponsesAloud settings on first read
     */
    var responseHandling: String
        get() {
            val storedMode = prefs.getString(KEY_RESPONSE_HANDLING, null)
            if (storedMode != null) {
                // Heal a value orphaned by the short-lived "Confirmation Tone"
                // Response Format option (now a separate Confirmation Sound
                // setting). Map it back to the equivalent speaking+card mode.
                if (storedMode == "tone_and_display") {
                    prefs.edit().putString(KEY_RESPONSE_HANDLING, RESPONSE_HANDLING_READ_AND_DISPLAY).commit()
                    return RESPONSE_HANDLING_READ_AND_DISPLAY
                }
                return storedMode
            }

            // Migrate from old boolean settings
            val oldShow = prefs.getBoolean(KEY_SHOW_RESPONSES, true)
            val oldRead = prefs.getBoolean(KEY_READ_RESPONSES_ALOUD, true)
            val migratedMode = when {
                oldShow && oldRead -> RESPONSE_HANDLING_READ_AND_DISPLAY
                oldShow && !oldRead -> RESPONSE_HANDLING_DISPLAY_ONLY
                !oldShow && oldRead -> RESPONSE_HANDLING_READ_ONLY
                else -> RESPONSE_HANDLING_NONE
            }
            // Store migrated value
            prefs.edit().putString(KEY_RESPONSE_HANDLING, migratedMode).commit()
            return migratedMode
        }
        set(value) { prefs.edit().putString(KEY_RESPONSE_HANDLING, value).commit() }

    /**
     * Display format: how the visual response is shown on screen.
     * Options: "notification" (mini card), "sidebar" (right panel), "fullscreen" (full overlay)
     * Only applies when response handling includes display (read_and_display or display_only).
     *
     * Default follows the voice PIPELINE, until the user explicitly picks one
     * (2026-07-13 — supersedes the D.27 account-linked default):
     *  - HA Voice Assist → "notification": a mini auto-dismissing card, so
     *    Dashie stays unobtrusive over the HA dashboard the pipeline belongs to.
     *  - Dashie-driven pipelines (Cloud / Hybrid / Local) → "fullscreen": the
     *    response IS the interaction, so it gets the screen.
     * Keyed off the preset, with controlMethod as the fallback for devices that
     * predate presets (its own default is voice_assistant → notification, the
     * pre-change behavior for an unconfigured device). A share-account kiosk
     * mirrors the household preset (KioskAccountVoiceApplier), so it inherits
     * this default too.
     *
     * Voice Assist ALWAYS uses the notification card (punch #6, John 2026-07-21):
     * it lives over the HA dashboard and must stay unobtrusive, so its notification
     * format is not overridable by the stored global value. `displayFormat` is a
     * single global pref, so a fullscreen chosen on a Dashie-driven preset (or synced
     * down from the account) used to leak into Voice Assist and take the whole screen.
     * The stored value now applies only to the Dashie-driven pipelines (Cloud/Hybrid/
     * Local), where the response IS the interaction and gets the screen. Once the user
     * sets a value it's stored and wins for those pipelines.
     * Mirrors the conditional-default pattern in ScreensaverPreferences.weatherMode.
     */
    var displayFormat: String
        get() {
            val haAssist = if (pipelinePreset.isNotEmpty()) pipelinePreset == "ha_assist"
                           else voiceControlMethod == VOICE_METHOD_VOICE_ASSISTANT
            // Voice Assist is pinned to notification — the stored global (possibly
            // fullscreen) only governs Dashie-driven pipelines.
            if (haAssist) return DISPLAY_FORMAT_NOTIFICATION
            val stored = prefs.getString(KEY_DISPLAY_FORMAT, null)
            return stored ?: DISPLAY_FORMAT_FULLSCREEN
        }
        set(value) { prefs.edit().putString(KEY_DISPLAY_FORMAT, value).commit() }

    /**
     * Confirmation sound for command acknowledgements. "disabled" turns it off;
     * otherwise an AlertSoundService chime name (e.g. "notify_soft_double").
     */
    var confirmationToneType: String
        get() = prefs.getString(KEY_CONFIRMATION_TONE_TYPE, DEFAULT_CONFIRMATION_TONE) ?: DEFAULT_CONFIRMATION_TONE
        set(value) { prefs.edit().putString(KEY_CONFIRMATION_TONE_TYPE, value).commit() }

    /** Confirmation tone playback volume, 0.0..1.0. */
    var confirmationToneVolume: Float
        get() = prefs.getFloat(KEY_CONFIRMATION_TONE_VOLUME, DEFAULT_CONFIRMATION_TONE_VOLUME)
        set(value) { prefs.edit().putFloat(KEY_CONFIRMATION_TONE_VOLUME, value).commit() }

    /** True when a confirmation tone is selected (i.e. not "disabled"). */
    val confirmationToneEnabled: Boolean
        get() = confirmationToneType != CONFIRMATION_TONE_DISABLED

    /**
     * Use Home Assistant TTS (e.g. Piper) instead of device native TTS.
     * When true, HA Assist pipeline runs TTS and returns audio.
     * When false (default), device native TextToSpeech speaks the response.
     */
    // ========== Voice Pipeline Configuration ==========

    /**
     * Voice pipeline ID for Home Assistant Assist.
     * Empty string = use HA's preferred/default pipeline.
     * Otherwise, specifies the pipeline_id to use.
     */
    var voicePipelineId: String
        get() = prefs.getString(KEY_VOICE_PIPELINE_ID, VOICE_PIPELINE_DEFAULT) ?: VOICE_PIPELINE_DEFAULT
        set(value) { prefs.edit().putString(KEY_VOICE_PIPELINE_ID, value).commit() }

    /**
     * Voice pipeline display name (cached for UI display).
     * This is stored alongside the ID so we don't need to query HA just to show the name.
     */
    var voicePipelineName: String
        get() = prefs.getString(KEY_VOICE_PIPELINE_NAME, "") ?: ""
        set(value) { prefs.edit().putString(KEY_VOICE_PIPELINE_NAME, value).commit() }

    /**
     * Cached name of HA's preferred/default pipeline.
     * Updated when pipelines are fetched from HA.
     * Used to show the actual pipeline name when "Preferred" is selected.
     */
    var preferredPipelineCachedName: String
        get() = prefs.getString(KEY_PREFERRED_PIPELINE_CACHED_NAME, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PREFERRED_PIPELINE_CACHED_NAME, value).commit() }

    /**
     * Check if a specific pipeline is selected (vs using HA default)
     */
    val hasCustomPipeline: Boolean
        get() = voicePipelineId.isNotEmpty()

    /**
     * Get display text for current pipeline selection.
     * Returns the pipeline name if set, or the cached preferred pipeline name,
     * or "Preferred" as fallback.
     */
    val voicePipelineDisplayName: String
        get() = when {
            voicePipelineName.isNotEmpty() -> voicePipelineName
            preferredPipelineCachedName.isNotEmpty() -> preferredPipelineCachedName
            else -> "Preferred"
        }

    /**
     * Voice pipeline mode: "ha" (Home Assistant) or "ai" (AI Routing).
     * Derived from voiceControlMethod and custom overrides for backward compatibility.
     * Returns "ai" if Dashie Cloud is selected, or if a cloud AI model is customized.
     */
    var voicePipelineMode: String
        get() {
            if (voiceControlMethod == VOICE_METHOD_DASHIE_CLOUD) return VOICE_PIPELINE_MODE_AI
            if (customizePipeline && aiModel != AI_MODEL_HOME_ASSISTANT) return VOICE_PIPELINE_MODE_AI
            return VOICE_PIPELINE_MODE_HA
        }
        set(value) { prefs.edit().putString(KEY_VOICE_PIPELINE_MODE, value).commit() }

    /**
     * FB27: does the effective voice pipeline hit Dashie's paid cloud (STT, AI, OR TTS) —
     * i.e. is it BILLABLE against credits? Credit UI (card red/"No credits", the low badge, the
     * low-credit pill) only applies when this is true.
     *
     * NOT just STT: the user can mix components (e.g. local Piper STT + a cloud Gemini AI is
     * still billable; a fully-local Dashie Intelligence config is not). So:
     *   - voice disabled → false.
     *   - Dashie Cloud method → all cloud → true.
     *   - Otherwise → true if ANY component is a Dashie-cloud choice (STT = dashie_cloud,
     *     TTS = dashie_cloud, or AI = a cloud model, not HA/Ollama-local).
     *
     * Judged from the CONCRETE engine keys, NOT gated on `customizePipeline`: a non-customized
     * config can still carry a cloud STT (a preset that seeded dashie_cloud), and that bills.
     * The old `!customizePipeline → false` early-return read the Fire's `stt=dashie_cloud` +
     * `customize=false` config as non-billable, so no low-credit/$0 UI ever showed (2026-07-21).
     */
    val isBillableVoice: Boolean
        get() {
            if (!voiceEnabled) return false
            if (voiceControlMethod == VOICE_METHOD_DASHIE_CLOUD) return true
            val sttCloud = sttProvider == STT_DASHIE_CLOUD
            val ttsCloud = ttsProvider == TTS_DASHIE_CLOUD
            val aiCloud = aiModel != AI_MODEL_HOME_ASSISTANT && aiModel != AI_MODEL_OLLAMA &&
                aiModel != AI_MODEL_LOCAL
            return sttCloud || ttsCloud || aiCloud
        }

    // ========== Voice Control Method ==========

    /**
     * Top-level voice control method selection.
     * Options: "voice_assistant" (HA pipeline, customizable), "dashie_cloud" (subscription)
     * Default: "voice_assistant"
     *
     * Kept as a plain stored value on purpose: it stays the authoritative input to
     * the pipeline decision (voicePipelineMode/useOverlayNlp) AND the channel the
     * VoiceAiSettings capability guards use to force a fallback when the device
     * can't honor the preset (no HA → dashie_cloud, no cloud → voice_assistant).
     * Consistency with the canonical pipelinePreset is guaranteed at WRITE time by
     * the pipelinePreset setter (see below), which seeds this on every preset write
     * so a stale value can't survive — the 2026-07-22 churn fix.
     */
    var voiceControlMethod: String
        get() = prefs.getString(KEY_VOICE_CONTROL_METHOD, VOICE_METHOD_VOICE_ASSISTANT) ?: VOICE_METHOD_VOICE_ASSISTANT
        set(value) { prefs.edit().putString(KEY_VOICE_CONTROL_METHOD, value).commit() }

    /**
     * Whether the user has enabled custom pipeline component overrides.
     * When false, STT/AI/TTS use defaults for the selected control method.
     * When true, user can override individual components.
     */
    var customizePipeline: Boolean
        get() = prefs.getBoolean(KEY_CUSTOMIZE_PIPELINE, false)
        set(value) { prefs.edit().putBoolean(KEY_CUSTOMIZE_PIPELINE, value).commit() }

    /**
     * Cached "is household Dashie Cloud sharing available?" from the integration
     * status probe (anonymous-kiosk path). Drives the §16.5 OR alongside the
     * logged-in/alpha gate. Updated in the background by DashieCloudCapabilityClient.
     */
    var cloudShareAvailable: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_SHARE_AVAILABLE, false)
        set(value) { prefs.edit().putBoolean(KEY_CLOUD_SHARE_AVAILABLE, value).commit() }

    /** Epoch millis of the last successful capability probe (0 = never). */
    var cloudShareCheckedAtMs: Long
        get() = prefs.getLong(KEY_CLOUD_SHARE_CHECKED_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_CLOUD_SHARE_CHECKED_AT, value).commit() }

    /**
     * Cached hub identity from the status probe's `hub` field: a published-edition id
     * ([HUB_DASHIE_VOICE] or [HUB_CHICKADEE_VOICE]; or [HUB_DASHIE_VOICE_PRE_0_7] from an un-promoted prod
     * add-on) when the open "Dashie for Home Assistant" integration serves the gateway;
     * "" when the full-edition integration does (its view has no such field). Brands the
     * shared-device "manage in ..." strings (Account page Disconnect row, add-credits
     * flow). Written on every successful probe, route-only included — signed-in kiosks
     * only ever probe route-only. Runtime-state cache, SYNC_EXEMPT.
     */
    var hubBrand: String
        get() = prefs.getString(KEY_HUB_BRAND, "") ?: ""
        set(value) { prefs.edit().putString(KEY_HUB_BRAND, value).commit() }

    /** True when the household gateway is served by the PUBLISHED edition's integration
     *  (the Kotlin sibling of the console's `FeatureGate.isPublishedBuild`, on the hub
     *  axis rather than the build axis). See [isPublishedHubValue] for the fallthrough. */
    val isPublishedHub: Boolean
        get() = isPublishedHubValue(hubBrand)

    /**
     * Cached household agentMode ("live" | "dialog" | "single") from the integration
     * status probe — the anonymous-kiosk sibling of the account-pushed [agentMode]
     * (which only reaches logged-in devices via setVoiceSettings). Set from the
     * console's Voice & AI page, carried add-on → integration `/status` → probe
     * (DashieCloudCapabilityClient). Empty = probe hasn't reported one → the
     * effective-mode resolver falls back to the kiosk default ('live',
     * 2026-07-09 Live-on-kiosk decision). Runtime-state cache, SYNC_EXEMPT.
     */
    var kioskAgentMode: String
        get() = prefs.getString(KEY_KIOSK_AGENT_MODE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_KIOSK_AGENT_MODE, value).commit() }

    /**
     * Cached household "retrieve pictures in AI responses" from the integration status
     * probe — the anonymous-kiosk sibling of AiPreferences.retrievePicturesEnabled
     * (account-pushed, logged-in devices only). Only written when the probe reports it
     * (older integrations omit the field). Default false = the relay omits image_search,
     * the pre-probe behavior. Runtime-state cache, SYNC_EXEMPT.
     */
    var kioskRetrievePictures: Boolean
        get() = prefs.getBoolean(KEY_KIOSK_RETRIEVE_PICTURES, false)
        set(value) { prefs.edit().putBoolean(KEY_KIOSK_RETRIEVE_PICTURES, value).commit() }

    // ========== Provider Selection (Unified Model) ==========

    /**
     * Speech-to-Text provider.
     * Options: "android_voice", "ha_whisper", "dashie_cloud"
     * Default depends on subscription status (set by settings page).
     */
    var sttProvider: String
        get() = prefs.getString(KEY_STT_PROVIDER, STT_VA_DEFAULT) ?: STT_VA_DEFAULT
        set(value) { prefs.edit().putString(KEY_STT_PROVIDER, value).commit() }

    /**
     * Realtime "conversation mode" Live model (account-level, from the console
     * Voice & AI Step-1 selector → user_settings.voice.conversationModel, pushed
     * to native via setVoiceSettings). Empty string = off (default). When set,
     * the spoken "conversation mode" trigger opens a Gemini Live session through
     * the conversation-relay. See 20260625_REALTIME_VOICE_CONVERSATION_MODE.md.
     */
    var conversationModel: String
        get() = prefs.getString(KEY_CONVERSATION_MODEL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_CONVERSATION_MODEL, value).commit() }

    /** Conversation mode is available iff a Live model is selected. */
    val conversationModeEnabled: Boolean
        get() = conversationModel.isNotBlank()

    // `liveByok` removed 2026-08-01. It gated BYOK-for-Live on a stored boolean whose console
    // toggle was NEVER BUILT — no UI writer existed anywhere, so the feature was reachable only
    // by hand-editing the DB. The device now always attempts the mint and lets the box answer
    // (503 `no_gemini_key` → Dashie-key path), which is both simpler and the only source that
    // actually knows. See VoicePipelineCoordinator.resolveLiveToken.
    //
    // KEY_LIVE_BYOK is retained below, unread: stored values on existing installs are harmless
    // and deleting the key buys nothing, while re-using the name later would silently inherit them.

    /**
     * The Gemini prebuilt voice a Live session speaks in (account-level, from the
     * console Voice & AI "Live voice" picker → user_settings.voice.liveVoiceName,
     * pushed to native via setVoiceSettings). Empty = the engine default
     * (RealtimeConfig.DEFAULT_VOICE). Read into the session setup by RealtimeConfig.
     */
    var conversationVoice: String
        get() = prefs.getString(KEY_CONVERSATION_VOICE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_CONVERSATION_VOICE, value).commit() }

    /**
     * When true, conversation mode is the DEFAULT — every wake opens a realtime
     * session instead of the cascade (no spoken trigger needed). When false,
     * conversation mode is on-demand via the trigger phrases. Account-level, from
     * user_settings.voice.conversationAlways. Only meaningful when a Live model
     * is selected ([conversationModeEnabled]).
     */
    var conversationAlways: Boolean
        get() = prefs.getBoolean(KEY_CONVERSATION_ALWAYS, false)
        set(value) { prefs.edit().putBoolean(KEY_CONVERSATION_ALWAYS, value).commit() }

    /**
     * DLG-6 "Keep dialog open": after ANY interaction (incl. a device command) in
     * conversation mode, auto-re-arm the mic — as if the wake word fired — so the user can
     * chain "dim the lights" → "lock the doors" without saying "Hey Dashie" again. NOT a
     * held-open live socket: it's an auto-wake trigger, so the next utterance still routes
     * normally (command / dialog / live). Account-level (user_settings.voice.alwaysOpenDialog),
     * pushed via setVoiceSettings; only meaningful when conversation mode is enabled.
     */
    var alwaysOpenDialog: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_OPEN_DIALOG, false)
        set(value) { prefs.edit().putBoolean(KEY_ALWAYS_OPEN_DIALOG, value).commit() }

    /**
     * Conversation Agent Mode (canonical): "live" | "dialog" | "single". Empty = unset
     * (older accounts) → derive from the old conversationModel/conversationAlways pair.
     * Account-level, pushed via setVoiceSettings.
     */
    var agentMode: String
        get() = prefs.getString(KEY_AGENT_MODE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_AGENT_MODE, value).commit() }

    /**
     * Effective conversation engine, honoring [agentMode] with back-compat derivation
     * from the old keys (mirrors JS voice-command-router._conversationMode):
     *   "live"   → Engine A (Gemini Live S2S)
     *   "dialog" → cascade Dialog loop (Engine B in VoicePipelineCoordinator)
     *   "single" → one-shot cascade (today's default)
     */
    val conversationEngineMode: String
        get() {
            val m = agentMode
            if (m.isNotBlank()) return m
            return if (conversationAlways) (if (conversationModeEnabled) "live" else "dialog") else "single"
        }

    /**
     * Software echo cancellation for live conversation mode (build plan
     * 20260627_REALTIME_VOICE_AEC). Device-local + default ON so we can flip it per
     * device to A/B the AEC. Read by the realtime audio path when that lands; harmless
     * until then.
     */
    var realtimeAecEnabled: Boolean
        get() = prefs.getBoolean(KEY_REALTIME_AEC, true)
        set(value) { prefs.edit().putBoolean(KEY_REALTIME_AEC, value).commit() }

    /**
     * Software echo cancellation for the CASCADE voice path (dialog/single cloud TTS) —
     * WS-A.2 self-hearing fix. Device-local + default ON so it can be killed per device
     * to A/B (mirrors [realtimeAecEnabled] for the Live path). Read by
     * CascadeAecController at each TTS session start, so toggling applies immediately.
     */
    var cascadeAecEnabled: Boolean
        get() = prefs.getBoolean(KEY_CASCADE_AEC, true)
        set(value) { prefs.edit().putBoolean(KEY_CASCADE_AEC, value).commit() }

    /**
     * Cloud TTS vendor for the `dashie_cloud` path. Default "auto" → the brain's
     * voice_provider decides (personality-driven: default personality → Inworld, others →
     * their voice's vendor). "inworld"/"elevenlabs" are a DEV-ONLY force override for A/B
     * testing (device-local, not surfaced in any UI; set via setVoiceSettings).
     */
    var cloudTtsVendor: String
        get() = prefs.getString(KEY_CLOUD_TTS_VENDOR, CLOUD_TTS_AUTO) ?: CLOUD_TTS_AUTO
        set(value) { prefs.edit().putString(KEY_CLOUD_TTS_VENDOR, value).commit() }

    /** Base URL of the user's OpenAI-compatible TTS box (Kokoro), e.g. http://192.168.1.50:8880.
     *  Account setting pushed console→native via setVoiceSettings; used when ttsProvider=local_url. */
    var localTtsUrl: String
        get() = prefs.getString(KEY_LOCAL_TTS_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LOCAL_TTS_URL, value).commit() }

    /** Voice id on the local TTS box (e.g. Kokoro "af_bella"). Blank → the client default. */
    var localTtsVoiceId: String
        get() = prefs.getString(KEY_LOCAL_TTS_VOICE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LOCAL_TTS_VOICE, value).commit() }

    /** Base URL of the user's OpenAI-compatible Whisper box, e.g. http://192.168.1.50:8000.
     *  Account setting pushed console→native via setVoiceSettings; used when sttProvider=local_stt_url. */
    var localSttUrl: String
        get() = prefs.getString(KEY_LOCAL_STT_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LOCAL_STT_URL, value).commit() }

    /** Open Brain preset (cloud|hybrid|local|ha_assist) — console's top-level
     *  selector, pushed via setVoiceSettings. Empty = derive from controlMethod. */
    var pipelinePreset: String
        get() = prefs.getString(KEY_PIPELINE_PRESET, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_PIPELINE_PRESET, value).commit()
            // Seed the derived controlMethod from the SSOT preset on EVERY preset
            // write (2026-07-22 churn fix). controlMethod is a derived runtime key,
            // but it's stored — and a write path that set the preset WITHOUT
            // updating controlMethod (notably the cloud→device setVoiceSettings
            // push, which applied a possibly-stale blob controlMethod at line ~540
            // then set the preset here) left the row inconsistent: a leftover
            // controlMethod=dashie_cloud with pipelinePreset=ha_assist made
            // voicePipelineMode derive `ai` (cascade), overriding the preset and
            // flip-flopping the device HA-Assist ↔ cascade every boot. Seeding
            // here — same mapping as the JS SSOT (voice-ai-options.js
            // applyPresetSeeding) and VoicePresetSeeder — keeps controlMethod
            // consistent for the native-UI, cloud-push, and any future preset
            // writer, so the stale-override can't recur. The VoiceAiSettings
            // capability guards still write controlMethod directly AFTER a preset
            // write, so a device that can't honor the preset (no HA / no cloud)
            // still falls back. Empty preset = legacy row → leave controlMethod
            // as-is (the getter's stored value stays authoritative).
            if (value.isNotEmpty()) {
                voiceControlMethod = if (value == "ha_assist") VOICE_METHOD_VOICE_ASSISTANT
                                     else VOICE_METHOD_DASHIE_CLOUD
            }
        }

    /** Voice-controllable HA entity source (dashboard | assist). Account setting, mirrored to a
     *  shared-account kiosk via the account voice-config → KioskAccountVoiceApplier, and exposed to
     *  the kiosk overlay's buildHaVoiceContext via DashieNative.getVoiceEntitySource(). Empty →
     *  the kiosk defaults to 'assist' (exposed list). Matches the webapp's voice.entitySource. */
    var entitySource: String
        get() = prefs.getString(KEY_ENTITY_SOURCE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_ENTITY_SOURCE, value).commit() }

    /** "My own AI" endpoint (Ollama / Hermes / OpenAI-compatible). Empty = unset. */
    var localLlmUrl: String
        get() = prefs.getString(KEY_LOCAL_LLM_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LOCAL_LLM_URL, value).commit() }

    /** Model name at the "My own AI" endpoint, e.g. "qwen3:8b". */
    var localLlmModel: String
        get() = prefs.getString(KEY_LOCAL_LLM_MODEL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LOCAL_LLM_MODEL, value).commit() }

    /**
     * The household's saved own-box engines — the console's **Local Engines** registry, pushed
     * console→native via `setVoiceSettings`. Raw JSON array:
     * `[{id, name, kind:"tts"|"stt"|"llm", url, model?}]`.
     *
     * **DISPLAY ONLY.** This is not a second source of truth and the device never writes it.
     * The runtime still reads the same resolved flat keys it always has ([localTtsUrl],
     * [localSttUrl], [localLlmUrl], [localLlmModel]) — the console writes those when the user
     * selects an engine, and that resolution (`EnginesStore.resolveToSettings`) lives in exactly
     * ONE place. We deliberately do NOT re-derive the flat keys here: doing so would be a
     * hand-mirrored cross-boundary mapping, i.e. a new drift surface, which is the class of bug
     * the 2026-07-13 settings audit was cleaning up. Kotlin only does a url → name LOOKUP so the
     * tablet can say "Piper on the Mac" instead of "http://192.168.1.50:8881"; a lookup miss is
     * harmless (we fall back to showing the URL).
     */
    var localEngines: String
        get() = prefs.getString(KEY_LOCAL_ENGINES, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LOCAL_ENGINES, value).commit() }

    /** One saved engine from the console registry. */
    data class LocalEngine(
        val id: String,
        val name: String,
        val kind: String,   // "tts" | "stt" | "llm"
        val url: String,
        val model: String = ""
    )

    /** Parse [localEngines]; never throws — a malformed blob yields an empty list. */
    fun localEnginesList(): List<LocalEngine> {
        val raw = localEngines
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url", "")
                if (url.isEmpty()) return@mapNotNull null
                LocalEngine(
                    id = o.optString("id", ""),
                    name = o.optString("name", ""),
                    kind = o.optString("kind", ""),
                    url = url,
                    model = o.optString("model", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * The friendly name of the saved engine currently selected for [kind], or "" if none matches.
     *
     * Matches on URL (plus model for `llm`, since two Ollama presets can share one box) — the
     * same identity the console's `matchSelected()` uses. This is a DISPLAY lookup, not a
     * mapping: if it misses, the UI just shows the raw URL, so a stale registry degrades to the
     * old behavior rather than misconfiguring anything.
     */
    fun selectedEngineName(kind: String): String = selectedEngine(kind)?.name.orEmpty()

    /**
     * The saved engine the current flat settings point at, or null.
     *
     * Same matching rule as [selectedEngineName] (which now delegates here) — the native picker
     * needs the engine's IDENTITY, not just its name, to mark the selected row.
     */
    fun selectedEngine(kind: String): LocalEngine? {
        val url = when (kind) {
            "tts" -> localTtsUrl
            "stt" -> localSttUrl
            "llm" -> localLlmUrl
            else -> ""
        }
        if (url.isBlank()) return null
        val model = if (kind == "llm") localLlmModel else ""
        return localEnginesList().firstOrNull {
            it.kind == kind &&
                it.url.trimEnd('/') == url.trimEnd('/') &&
                (kind != "llm" || model.isBlank() || it.model == model)
        }
    }

    /** Whether an API key exists for the "My own AI" endpoint — INDICATOR ONLY.
     *  The key itself never syncs to the device; manage it in the Dashie Console. */
    var localLlmKeySet: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_LLM_KEY_SET, false)
        set(value) { prefs.edit().putBoolean(KEY_LOCAL_LLM_KEY_SET, value).commit() }

    /** Which HA TTS engine to hit engine-direct, e.g. "tts.piper". Used when ttsProvider=ha_engine.
     *  The engine name lives ONLY here — the provider id is the transport (ha_engine). */
    var haTtsEngineId: String
        get() = prefs.getString(KEY_HA_TTS_ENGINE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_HA_TTS_ENGINE, value).commit() }

    /** Chosen voice on the HA TTS engine, e.g. "en_US-amy-medium". Blank → engine default. */
    var haTtsVoiceId: String
        get() = prefs.getString(KEY_HA_TTS_VOICE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_HA_TTS_VOICE, value).commit() }

    /** Which HA STT engine to hit engine-direct, e.g. "stt.faster_whisper". Used when sttProvider=ha_engine. */
    var haSttEngineId: String
        get() = prefs.getString(KEY_HA_STT_ENGINE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_HA_STT_ENGINE, value).commit() }

    /**
     * Text-to-Speech provider.
     * Options: "android_voice", "ha_piper", "dashie_cloud"
     * Default: "android_voice" (always available on all devices)
     */
    var ttsProvider: String
        get() = prefs.getString(KEY_TTS_PROVIDER, TTS_ANDROID_VOICE) ?: TTS_ANDROID_VOICE
        set(value) { prefs.edit().putString(KEY_TTS_PROVIDER, value).commit() }

    // ========== AI Model & Web Search ==========

    /**
     * AI model selection.
     * "home_assistant" = pass through to HA conversation agent (hassil).
     * Any other value = cloud LLM model ID (e.g., "gemini-2.5-flash").
     */
    var aiModel: String
        get() = prefs.getString(KEY_AI_MODEL, AI_MODEL_HOME_ASSISTANT) ?: AI_MODEL_HOME_ASSISTANT
        set(value) {
            val old = prefs.getString(KEY_AI_MODEL, AI_MODEL_HOME_ASSISTANT) ?: AI_MODEL_HOME_ASSISTANT
            prefs.edit().putString(KEY_AI_MODEL, value).commit()
            // Keep the brain-route cache honest across model changes — see AiModelChangeGuard.
            AiModelChangeGuard.noteAiModelChanged(prefs, old, value)
        }

    /**
     * Route the AI lane to the add-on's ON-PREM brain (local model on the HA box) instead of the
     * cloud edge fn — build plan §13.16/§13.17. Device-local toggle (not account-synced); default
     * false. When true, [BrainConverseClient] adds options.route="local" so the integration gateway
     * forwards to the add-on. Proper account-level selection is the Wave-2 §16.4 UI.
     */
    var useLocalBrain: Boolean
        get() = prefs.getBoolean(KEY_USE_LOCAL_BRAIN, false)
        set(value) { prefs.edit().putBoolean(KEY_USE_LOCAL_BRAIN, value).commit() }

    /**
     * Where the account's BRAIN runs, as reported by the add-on through the integration's
     * `/api/dashie/voice/status` probe (Open Brain §5 / WS-C): "local" = send cascade turns
     * through the HA gateway to the add-on brain (own model / Hermes / a BYO provider key
     * stored on the box), "cloud" = the metered edge fn, "" = no report yet (old
     * integration/add-on) → direct cloud. Cached by DashieCloudCapabilityClient because the
     * synchronous converse path can't probe per turn. The add-on owns the decision — the
     * device never re-derives it (no model→provider mirror in Kotlin). But the cache must not
     * outlive the model selection it was probed under: an ai_model change zeroes the TTL stamp
     * so the next wake re-probes immediately (see [AiModelChangeGuard], 2026-08-21).
     */
    var brainRoute: String
        get() = prefs.getString(KEY_BRAIN_ROUTE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_BRAIN_ROUTE, value).commit() }

    /**
     * Effective on-prem-brain decision: the manual dev toggle OR the add-on-reported route —
     * **but the reported route only counts for sessions the household actually governs.**
     *
     * ## Why this takes a parameter instead of being a property (2026-08-04)
     *
     * John, on a fully signed-in Mio: `BrainRoute: route=local`, so a device holding its own
     * account and its own entitlement was routed to the HA box's brain, denying it the cloud path
     * that account pays for. `brain_route` is a **household** answer — B measured that
     * `resolveBrainRoute` sees only the box's config and literally cannot see a signed-in device's
     * entitlement — so it is the wrong authority for a session a human signed into here.
     *
     * 🔴 **B's decisive measurement:** `getAccountVoiceConfig()` replays a **cached** config when
     * Supabase is unreachable or signed out, so the `brain_route` a device receives can be a stale
     * household preference carrying **no entitlement meaning at all**. Obeying that on an
     * own-account session is indefensible, which is what ruled out making the add-on caller-aware.
     *
     * ⚠️ It is a **required parameter, not a defaulted one, and the property is gone**. The
     * regression to prevent is someone quietly going back to obeying the wire value; with no
     * ungoverned form in existence, a caller cannot do that by accident — it will not compile.
     *
     * [useLocalBrain] is deliberately NOT gated: that is the device-local dev toggle, an explicit
     * choice made on this device, so it outranks both the household answer and this predicate.
     *
     * @param householdRouteGoverns pass
     *   `LeaseGovernance.governs(account.isLinked, account.kioskProvisionedSession)` — the SAME
     *   borrows-vs-owns predicate the capability lease uses. One rule, two consumers.
     */
    fun effectiveUseLocalBrain(householdRouteGoverns: Boolean): Boolean =
        useLocalBrain || (householdRouteGoverns && brainRoute == BRAIN_ROUTE_LOCAL)

    /** When the brain_route probe last succeeded (epoch ms). TTL gate for wake-time
     *  re-probes so a newly-added API key routes on the NEXT turn, no app restart. */
    var brainRouteCheckedAtMs: Long
        get() = prefs.getLong(KEY_BRAIN_ROUTE_CHECKED_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_BRAIN_ROUTE_CHECKED_AT, value).commit() }

    /** Apply an add-on-reported brain route (from the /voice/status probe OR the keystone
     *  `X-Dashie-Brain-Route` converse-response header — the latter re-caches the authoritative
     *  route every turn so a stale cache can't strand the device). Only a valid ("local"/"cloud")
     *  route is written; garbage/empty is ignored (never downgrade the cache). Stamps
     *  [brainRouteCheckedAtMs] on any valid apply; returns true iff the cached route changed. */
    fun applyBrainRoute(route: String): Boolean {
        if (route != BRAIN_ROUTE_LOCAL && route != BRAIN_ROUTE_CLOUD) return false
        val changed = brainRoute != route
        if (changed) brainRoute = route
        brainRouteCheckedAtMs = System.currentTimeMillis()
        return changed
    }

    /**
     * Web search backend (only used when voicePipelineMode = "ai")
     */
    var webSearchBackend: String
        get() = prefs.getString(KEY_WEB_SEARCH_BACKEND, WEB_SEARCH_DISABLED) ?: WEB_SEARCH_DISABLED
        set(value) { prefs.edit().putString(KEY_WEB_SEARCH_BACKEND, value).commit() }

    // ========== STT Provider Settings ==========

    /**
     * @deprecated No longer needed - Deepgram now uses edge function proxy with server-side API key.
     * Kept for backwards compatibility but not used.
     */
    @Deprecated("Deepgram API key no longer needed - uses edge function proxy")
    var deepgramApiKey: String
        get() = prefs.getString(KEY_DEEPGRAM_API_KEY, "") ?: ""
        set(value) { prefs.edit().putString(KEY_DEEPGRAM_API_KEY, value).commit() }

    /**
     * Prefer Deepgram STT over HA Assist.
     * When true, uses Deepgram cloud STT (faster, more accurate) as primary.
     * HA Assist is used as fallback if Deepgram fails.
     * Default: false (use HA Assist as primary)
     */
    var preferDeepgramStt: Boolean
        get() = prefs.getBoolean(KEY_PREFER_DEEPGRAM_STT, false)
        set(value) { prefs.edit().putBoolean(KEY_PREFER_DEEPGRAM_STT, value).commit() }

    /**
     * Use overlay NLP instead of HA Assist intent processing.
     * When true, STT result is sent to the overlay's processVoiceCommand for NLP.
     * This enables timer commands, LLM queries, and custom HA control through the overlay.
     * Default: false (use HA Assist for STT + intent)
     */
    var useOverlayNlp: Boolean
        get() = prefs.getBoolean(KEY_USE_OVERLAY_NLP, false) || voicePipelineMode == VOICE_PIPELINE_MODE_AI
        set(value) { prefs.edit().putBoolean(KEY_USE_OVERLAY_NLP, value).commit() }

    /**
     * Check if Deepgram STT is available.
     * Always true now - uses edge function proxy with server-side API key.
     */
    val hasDeepgramStt: Boolean
        get() = true  // Always available via edge function proxy

    // ========== Voice License & Trial ==========

    /**
     * Timestamp (epoch ms) when voice trial started
     * 0 = trial not started yet
     */
    var voiceTrialStart: Long
        get() = prefs.getLong(KEY_VOICE_TRIAL_START, 0L)
        set(value) { prefs.edit().putLong(KEY_VOICE_TRIAL_START, value).commit() }

    /**
     * Timestamp (epoch ms) when voice trial expires
     * Set by server - used instead of calculating from start + duration
     * 0 = not set (fall back to calculated expiry)
     */
    var voiceTrialExpiry: Long
        get() = prefs.getLong(KEY_VOICE_TRIAL_EXPIRY, 0L)
        set(value) { prefs.edit().putLong(KEY_VOICE_TRIAL_EXPIRY, value).commit() }

    /**
     * Voice license status: "none", "trial", "active", "expired"
     * - none: Never enabled voice
     * - trial: Within 30-day trial period (extended for beta)
     * - active: Paid and activated
     * - expired: Trial expired, not purchased
     */
    var voiceLicenseStatus: String
        get() = prefs.getString(KEY_VOICE_LICENSE_STATUS, "none") ?: "none"
        set(value) { prefs.edit().putString(KEY_VOICE_LICENSE_STATUS, value).commit() }

    /** True if this device's license was adopted from a household seed (derived).
     *  A derived device must NOT re-publish itself as the household seed. */
    var voiceLicenseDerived: Boolean
        get() = prefs.getBoolean(KEY_VOICE_LICENSE_DERIVED, false)
        set(value) { prefs.edit().putBoolean(KEY_VOICE_LICENSE_DERIVED, value).commit() }

    /**
     * Email address used for voice license purchase
     */
    var voiceLicenseEmail: String
        get() = prefs.getString(KEY_VOICE_LICENSE_EMAIL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_VOICE_LICENSE_EMAIL, value).commit() }

    /**
     * Last timestamp when license was validated online
     * Used for offline caching (valid for 7 days)
     */
    var voiceLicenseLastCheck: Long
        get() = prefs.getLong(KEY_VOICE_LICENSE_LAST_CHECK, 0L)
        set(value) { prefs.edit().putLong(KEY_VOICE_LICENSE_LAST_CHECK, value).commit() }

    /**
     * Stable device identifier for license validation
     * Generated once and persisted
     */
    var voiceDeviceId: String
        get() = prefs.getString(KEY_VOICE_DEVICE_ID, "") ?: ""
        set(value) { prefs.edit().putString(KEY_VOICE_DEVICE_ID, value).commit() }

    // ========== Voice & Personality Selection ==========

    /**
     * TTS voice key (e.g. "BELLA", "BAD_SANTA")
     * Used for personality-based voice lookup
     */
    var voiceKey: String
        get() = prefs.getString(KEY_VOICE_KEY, DEFAULT_VOICE_KEY) ?: DEFAULT_VOICE_KEY
        set(value) { prefs.edit().putString(KEY_VOICE_KEY, value).commit() }

    /**
     * TTS voice provider ID (ElevenLabs voice ID)
     * Used for actual TTS API calls
     */
    var voiceId: String
        get() = prefs.getString(KEY_VOICE_ID, "") ?: ""
        set(value) { prefs.edit().putString(KEY_VOICE_ID, value).commit() }

    // ========== Sample Collection Preferences ==========

    /**
     * Sample collection enabled (wake word training data contribution)
     * Default: false (user must opt-in via consent dialog)
     */
    var sampleCollectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_SAMPLE_COLLECTION_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_SAMPLE_COLLECTION_ENABLED, value).commit() }

    /**
     * User has given consent for sample collection
     * Must be true before collection can be enabled
     */
    var sampleCollectionConsent: Boolean
        get() = prefs.getBoolean(KEY_SAMPLE_COLLECTION_CONSENT, false)
        set(value) { prefs.edit().putBoolean(KEY_SAMPLE_COLLECTION_CONSENT, value).commit() }
}

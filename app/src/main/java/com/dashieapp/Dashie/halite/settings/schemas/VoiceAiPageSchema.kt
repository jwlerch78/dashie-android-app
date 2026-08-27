package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection
import com.dashieapp.Dashie.halite.settings.schema.SubScreenSchema

/**
 * Schema definition for the unified Voice & AI settings page.
 *
 * Layout:
 * 1. Voice — enable, wake word
 * 2. Voice Handling — control method (Voice Assistant / Dashie Cloud)
 *    - Voice Assistant: pipeline selector + customize toggle + STT/AI/TTS
 *    - Dashie Cloud: personality + customize toggle + STT/AI/TTS
 * 3. AI Tools & Settings — only when cloud AI model selected
 * 4. Wake Word Collection
 */
object VoiceAiPageSchema {

    // ── Reusable conditions ─────────────────────────────────────────────

    private val voiceEnabled = Condition.IsTrue("voice.enabled")

    private val isVoiceAssistant = Condition.And(listOf(
        voiceEnabled,
        Condition.Equals("voice.controlMethod", "voice_assistant")
    ))
    private val isDashieCloud = Condition.And(listOf(
        voiceEnabled,
        Condition.Equals("voice.controlMethod", "dashie_cloud")
    ))
    private val isCustomizing = Condition.And(listOf(
        voiceEnabled,
        Condition.IsTrue("voice.customizePipeline")
    ))
    // The AI Model picker only applies to Dashie Cloud voice — picking which
    // cloud LLM answers. In Voice Assistant (HA pipeline) mode the AI step is
    // always HA's conversation agent, so the picker would be a single-real-
    // option control. Gating on dashie_cloud also means beta (non-alpha)
    // accounts — which can't select dashie_cloud — never see it, so they
    // never see a stray cloud-model default like Claude (D.31).
    private val aiModelVisible = Condition.And(listOf(isCustomizing, isDashieCloud))
    // AI Tools & Settings should only show when the user is on the Dashie
    // Cloud pipeline AND their AI model isn't HA's local LLM. In Voice
    // Assistant mode (HA's pipeline) these settings don't apply — the HA
    // pipeline has its own configuration. The previous check only excluded
    // the "home_assistant" model and missed the controlMethod axis.
    private val cloudAiSelected = Condition.And(listOf(
        voiceEnabled,
        Condition.Equals("voice.controlMethod", "dashie_cloud"),
        Condition.NotEquals("ai.model", "home_assistant")
    ))
    // DLG-6 "Keep dialog open" only applies in conversation mode (dialog or live) —
    // it re-arms the mic after each turn/command. Gated to a set agentMode so it hides
    // in one-shot (single) mode.
    private val conversationEnabled = Condition.And(listOf(
        cloudAiSelected,
        Condition.Or(listOf(
            Condition.Equals("voice.agentMode", "dialog"),
            Condition.Equals("voice.agentMode", "live")
        ))
    ))
    // Live (S2S) owns STT+TTS — hide those pickers while a Live model is the
    // agent (mirrors the console; the Dialog toggle hides too).
    private val notLive = Condition.NotEquals("voice.agentMode", "live")
    // Own-box / engine-direct config rows (URLs are reasonable to set on-device;
    // API keys are NOT — they live in the Dashie Console only).
    private val ttsIsLocalUrl = Condition.Equals("voice.ttsProvider", "local_url")
    private val ttsIsHaEngine = Condition.Equals("voice.ttsProvider", "ha_engine")
    private val sttIsLocalUrl = Condition.Equals("voice.sttProvider", "local_stt_url")
    // Display format is relevant when response includes visual display
    private val hasDisplayResponse = Condition.And(listOf(
        voiceEnabled,
        Condition.Or(listOf(
            Condition.Equals("voice.responseHandling", "read_and_display"),
            Condition.Equals("voice.responseHandling", "display_only")
        ))
    ))
    // Tone volume only applies once an actual tone (not "Disabled") is chosen
    private val toneSelected = Condition.NotEquals("voice.confirmationToneType", "disabled")

    fun create(
        hasAccount: Boolean = false,
        cloudAvailable: Boolean = false,
        haEnabled: Boolean = true,
        // Voice response feedback (thumbs up/down) is alpha-gated (feature_access
        // 'voice_feedback'); the per-user prompt toggle only appears for that cohort.
        feedbackPromptAvailable: Boolean = false,
        // Effective pipeline preset (cloud|hybrid|local|ha_assist) — computed by
        // the schemaProvider from prefs each rebuild; shapes the option lists
        // (Open Brain §7 Kotlin mirror). Empty = legacy caller → unfiltered.
        preset: String = "",
        // Chores are alpha-rollout — hide "Always Use AI for Chores" for
        // accounts without access (mirrors the console FeatureGate).
        choresAvailable: Boolean = true,
        // Gemini cascade models search via Google grounding → the Web Search
        // Source picker reads "Google" instead of Tavily (console parity).
        isGeminiModel: Boolean = false,
        // Saved engines from the console's Local Engines registry (voice.localEngines,
        // pushed to prefs). Each becomes a named picker row; empty (never synced) falls
        // back to the generic "your box" option.
        engines: List<VoicePreferences.LocalEngine> = emptyList(),
        // WS-D.1: whether Android's SpeechRecognizer can run here (Google services present).
        // Gates the "Android Voice" STT row selectable-vs-grayed.
        androidSttAvailable: Boolean = false,
        // Whether this APK bundles the sherpa-onnx on-device STT engine (dev flavors).
        // Gates the "On-Device (fast/accurate)" STT rows selectable-vs-hidden.
        sherpaAvailable: Boolean = false,
        /** Per-model install state; see VoiceAiOptions.sttOptions. */
        sherpaModels: Map<String, Int?> = emptyMap(),
        // Brand-split T4: this device shows Home Assistant, not the Dashie dashboard
        // (HaEditionGate.isHaMode). Dashie for HA is free — no account, no
        // subscription — so the voice LICENSE (a paywall: one-time payment gating the
        // voice feature) must not be reachable from any Settings path here.
        haMode: Boolean = false,
        /**
         * Does a metered cloud service exist in this EDITION — as opposed to "can this account
         * spend right now", which is [cloudAvailable]? `false` omits the Dashie-Cloud rows
         * outright rather than showing them disabled with an add-credits explainer.
         *
         * ⚠️ Deliberately **last**, with a default. This function is called positionally, and
         * a parameter inserted mid-list silently re-binds every argument after it wherever the
         * types happen to line up. My first attempt did insert it mid-list; the compiler caught
         * it only because `String` and `Boolean` collided — that was luck, not design.
         */
        offersMeteredCloud: Boolean = true,
    ) = SettingsPageSchema(
        id = "voice_ai",
        title = "Voice & AI",
        sections = listOfNotNull(
            voiceSection(),
            // The key-based voice license only applies to non-subscribed
            // accounts. A subscribed Dashie Cloud account (hasAccount =
            // trialing / active / complimentary) is entitled to voice via the
            // subscription, so the license status + "Activate Voice License"
            // rows are hidden entirely (D.26).
            //
            // Brand-split T4: also hidden in HA mode, and that branch matters far more
            // than D.26 — a no-account kiosk is exactly `hasAccount = false`, so this
            // section (and its purchase QR) was SHOWING on the default free-HA device.
            // Voice-licence section removed 2026-08-02: voice is free, so there is no
            // licence to show a status for or to activate.
            voiceHandlingSection(cloudAvailable, haEnabled, offersMeteredCloud, hasAccount),
            voiceAssistantSection(),
            dashieCloudSection(),
            customizePipelineSection(hasAccount, cloudAvailable, haEnabled, preset, engines, androidSttAvailable, sherpaAvailable, offersMeteredCloud, sherpaModels),
            aiToolsSection(choresAvailable, isGeminiModel),
            wakeWordCollectionSection(feedbackPromptAvailable)
        ),
        subScreens = mapOf(
            "confirmation_sound" to confirmationSoundSubScreen()
        )
    )

    // ── Section 1: Voice ─────────────────────────────────────────────────

    private fun voiceSection() = SettingsSection(
        header = "Voice",
        items = listOf(
            SchemaItem.Toggle(
                id = "voice_enabled",
                label = "Enable Voice",
                settingKey = "voice.enabled",
                onChanged = "notifyVoiceEnabledChanged"
            ),
            SchemaItem.Navigation(
                id = "wake_word",
                label = "Wake Word",
                navigateTo = "ext:wake_word",
                displayValueKey = "voice.wakeWordDisplay",
                visibleWhen = voiceEnabled
            )
        )
    )

    // ── License section ──────────────────────────────────────────────────

    // ── Section 2: Setup (pipeline preset picker) ─────────────────────────
    // Replaces the controlMethod picker (Open Brain §6/§7): the preset drives
    // voice.controlMethod + provider seeding via VoicePresetSeeder; all the
    // existing controlMethod-based conditions keep working because the seeder
    // keeps controlMethod in sync.

    private fun voiceHandlingSection(
        cloudAvailable: Boolean,
        haEnabled: Boolean,
        offersMeteredCloud: Boolean,
        hasAccount: Boolean,
    ): SettingsSection {
        // Release-week hold — the SAME predicate the option list uses, never a second copy.
        // See VoiceAiOptions.isLockedToHaAssist for what it gates and when to delete it.
        val locked = VoiceAiOptions.isLockedToHaAssist(hasAccount)
        return SettingsSection(
        header = "Setup",
        visibleWhen = voiceEnabled,
        items = listOf(
            // The picker sub-screen groups its options under Dashie (a
            // "Details & Setup" entry + Cloud/Hybrid/Local) and HA Voice (Voice
            // Assistant) via sectionHeaders — see VoiceAiOptions.presetOptions.
            // The page row itself is unchanged. Stored value is untouched
            // (voice.pipelinePreset: cloud|hybrid|local|ha_assist).
            SchemaItem.Picker(
                id = "pipeline_preset",
                label = "Voice & AI Setup",
                settingKey = "voice.pipelinePreset",
                options = VoiceAiOptions.presetOptions(cloudAvailable, haEnabled, offersMeteredCloud, hasAccount),
                sectionHeaders = if (locked) emptyList() else VoiceAiOptions.presetSectionHeaders(haEnabled),
                // Under the "Dashie" header: the explainer, then a stand-alone
                // orange "Dashie Details & Setup" block (opens DashieVoiceInfoDialog
                // via onVoiceAiLearnMore), then the Cloud/Hybrid/Local options.
                //
                // 🔴 ALL OF IT IS SUPPRESSED WITH NO ACCOUNT. The explainer sells the Dashie
                // built-in stack and the orange button is the entry point to setting it up — via
                // an add-on that HAS NOT SHIPPED. Removing the Cloud/Hybrid/Local rows while
                // leaving the banner that advertises them would be the worst of both: the pitch
                // stays, the product is gone. The section headers go too — with one option they
                // would label a group of one.
                leadingDescription = if (locked) null else
                    "Open source plug-and-play voice & AI for the Home Assistant ecosystem.",
                leadingActionId = if (locked) null else "dashie_details_setup",
                leadingActionLabel = if (locked) null else "Details & Setup",
                leadingActionCallback = if (locked) null else "onVoiceAiLearnMore",
                onChanged = "notifyPipelinePresetChanged"
            )
        )
        )
    }

    // ── Voice Assistant section (pipeline selector) ──────────────────────

    private fun voiceAssistantSection() = SettingsSection(
        visibleWhen = isVoiceAssistant,
        items = listOf(
            SchemaItem.Navigation(
                id = "voice_pipeline",
                label = "Voice Pipeline",
                navigateTo = "ext:ha_pipeline",
                displayValueKey = "voice.haPipelineDisplay"
            )
        )
    )

    // ── Dashie Cloud section (personality) ───────────────────────────────

    private fun dashieCloudSection() = SettingsSection(
        visibleWhen = isDashieCloud,
        items = listOf(
            SchemaItem.Navigation(
                id = "personality",
                label = "Personality",
                navigateTo = "ext:personality",
                displayValueKey = "ai.personalityDisplay"
            )
        )
    )

    // ── Customize Voice Pipeline (both methods) ──────────────────────────

    private fun customizePipelineSection(
        hasAccount: Boolean,
        cloudAvailable: Boolean,
        haEnabled: Boolean,
        preset: String,
        engines: List<VoicePreferences.LocalEngine>,
        androidSttAvailable: Boolean,
        sherpaAvailable: Boolean,
        /** See [create]'s parameter of the same name — edition capability, not account state. */
        offersMeteredCloud: Boolean,
        /** Per-model install state; see [VoiceAiOptions.sttOptions]. */
        sherpaModels: Map<String, Int?>,
    ) = SettingsSection(
        header = "Voice Pipeline",
        visibleWhen = voiceEnabled,
        items = listOf(
            SchemaItem.Toggle(
                id = "customize_pipeline",
                label = "Customize Voice Pipeline",
                settingKey = "voice.customizePipeline",
                onChanged = "notifyCustomizePipelineChanged"
            ),
            // One row per stage, mirroring the console: the row NAMES the selected engine
            // ("qwen3:30b…", not "My own AI") and badges whether it's LOCAL or CLOUD. The
            // separate "AI Engine" Info row that used to carry the name is gone — with
            // displayValueKey the picker says it itself, and two rows for one fact was noise.
            SchemaItem.Picker(
                id = "ai_model",
                label = "AI Model",
                // Virtual key — resolves an engine token into ai.model + url + model. The
                // persisted keys are unchanged; see VoiceAiSettingsWiring.
                settingKey = "voice.aiModelSelection",
                options = VoiceAiOptions.aiModelOptions(cloudAvailable, preset, engines),
                sectionHeaders = VoiceAiOptions.aiModelSectionHeaders(preset),
                displayValueKey = "voice.aiModelDisplay",
                badgeKey = "voice.aiModelBadge",
                visibleWhen = aiModelVisible,
                onChanged = "notifyAiModelChanged"
            ),
            // GAP-2: Live (S2S) voice — the Gemini prebuilt voice that speaks in Live mode. Shown
            // only when a Live model is the agent (agentMode=live, which owns STT/TTS). Account-scoped
            // → NO "Default" row (it IS the account setting). Ids gated by lint:voice-options.
            SchemaItem.Picker(
                id = "live_voice",
                label = "Live Voice",
                settingKey = "voice.liveVoiceName",
                options = VoiceAiOptions.LIVE_VOICES,
                displayValueKey = "voice.liveVoiceDisplay",
                // isCustomizing (voice enabled + customize-pipeline ON) + Live mode — a pipeline
                // detail, so it hides with the rest when Customize Voice Pipeline is toggled off.
                visibleWhen = Condition.And(listOf(isCustomizing, Condition.Equals("voice.agentMode", "live"))),
                onChanged = "notifyLiveVoiceChanged"
            ),
            // NB: no "API Key" row — keys are Console-owned, so the row was read-only and could
            // only ever say "Set"/"Not set" with nothing to act on. The console omits it too.
            // The engine itself is still chosen on the console's Local Engines page (which can
            // PROBE it: list models, verify it answers); the tablet only reports the selection.
            // STT comes FIRST (it's first in the pipeline — John 2026-07-28). Cascade STT
            // (non-Live): filtered by the active preset (Cloud→cloud-only, etc).
            SchemaItem.Picker(
                id = "stt_provider",
                label = "Speech-to-Text",
                settingKey = "voice.sttSelection",
                options = VoiceAiOptions.filterByPreset(preset, VoiceAiOptions.sttOptions(hasAccount, cloudAvailable, haEnabled, engines, androidSttAvailable, sherpaAvailable, sherpaModels), offersMeteredCloud),
                displayValueKey = "voice.sttProviderDisplay",
                badgeKey = "voice.sttProviderBadge",
                visibleWhen = Condition.And(listOf(isCustomizing, notLive)),
                onChanged = "notifySttProviderChanged"
            ),
            // Live STT (console parity): Live runs on the Cloud preset, which would filter STT to
            // cloud-only — but Live still transcribes the FIRST request to decide local-vs-Live
            // routing, so it must offer LOCAL engines too. Use the "hybrid" set (cloud + local + HA
            // Whisper, minus the HA-pipeline default). Same key/handler; TTS rows are hidden in Live
            // so this renders directly below "Live Voice".
            SchemaItem.Picker(
                id = "stt_provider_live",
                label = "Speech-to-Text",
                settingKey = "voice.sttSelection",
                options = VoiceAiOptions.filterByPreset("hybrid", VoiceAiOptions.sttOptions(hasAccount, cloudAvailable, haEnabled, engines, androidSttAvailable, sherpaAvailable, sherpaModels), offersMeteredCloud),
                displayValueKey = "voice.sttProviderDisplay",
                badgeKey = "voice.sttProviderBadge",
                visibleWhen = Condition.And(listOf(isCustomizing, Condition.Equals("voice.agentMode", "live"))),
                onChanged = "notifySttProviderChanged"
            ),
            // Live-only note explaining why STT is present (mirrors the console's _renderLiveSttNote).
            SchemaItem.Info(
                id = "live_stt_note",
                label = "The first request is transcribed on this device to decide whether to handle it locally or send it to Live.",
                visibleWhen = Condition.And(listOf(isCustomizing, Condition.Equals("voice.agentMode", "live")))
            ),
            SchemaItem.Picker(
                id = "tts_provider",
                label = "Text-to-Speech",
                settingKey = "voice.ttsSelection",
                options = VoiceAiOptions.filterByPreset(preset, VoiceAiOptions.ttsOptions(hasAccount, cloudAvailable, haEnabled, engines), offersMeteredCloud),
                displayValueKey = "voice.ttsProviderDisplay",
                badgeKey = "voice.ttsProviderBadge",
                visibleWhen = Condition.And(listOf(isCustomizing, notLive)),
                onChanged = "notifyTtsProviderChanged"
            ),
            // NB: there is deliberately NO "TTS Engine" row here. The Text-to-Speech picker above
            // already says which box is selected, and a second row repeating it (plus a fourth
            // "manage in the Dashie Console") was pure noise — the tablet mirrors the console's
            // one-row-per-stage layout (2026-07-16).
            // The VOICE, however, belongs to the tablet's own Voice & AI card — one box can speak
            // many voices, and until now there was NO field for it at all: picking "Local TTS"
            // left the device on the native default `af_heart`, a KOKORO voice that a Piper box
            // simply does not have (handover doc §4). Mirrors the HA Piper voice row.
            // GAP-2 Piece A (Kokoro): the local TTS voice is now a PICKER of the box's probed voices
            // (KokoroVoicePickerFragment → GET /v1/audio/voices), not free-text. "Custom…" fallback inside.
            SchemaItem.Navigation(
                id = "local_tts_voice",
                label = "Voice",
                navigateTo = "ext:kokoro_voice",
                displayValueKey = "voice.localTtsVoiceDisplay",
                visibleWhen = Condition.And(listOf(isCustomizing, notLive, ttsIsLocalUrl))
            ),
            // GAP-2 Piece A: the Piper voice is now a PICKER of the engine's probed voices
            // (PiperVoicePickerFragment → HA WS tts/engine/voices), not free-text. A "Custom…" row
            // inside keeps the manual-entry fallback. Display still decoded via VoiceLabelDecoder.
            SchemaItem.Navigation(
                id = "ha_tts_voice",
                label = "Voice",
                navigateTo = "ext:piper_voice",
                displayValueKey = "voice.haTtsVoiceDisplay",
                visibleWhen = Condition.And(listOf(isCustomizing, notLive, ttsIsHaEngine))
            ),
            // NB: no "STT Engine" row either — same reason as TTS above. The Speech-to-Text
            // picker already names the selected box.
            SchemaItem.Picker(
                id = "response_format",
                label = "Response Format",
                settingKey = "voice.responseHandling",
                options = VoiceAiOptions.responseFormatOptions,
                onChanged = "notifyResponseHandlingChanged"
            ),
            SchemaItem.Picker(
                id = "display_format",
                label = "Display Format",
                settingKey = "voice.displayFormat",
                options = VoiceAiOptions.displayFormatOptions,
                visibleWhen = hasDisplayResponse,
                onChanged = "notifyDisplayFormatChanged"
            ),
            SchemaItem.Navigation(
                id = "confirmation_sound",
                label = "Confirmation Sound",
                sublabel = "Play a sound instead of a voice response for commands",
                navigateTo = "confirmation_sound",
                displayValueKey = "voice.confirmationSoundDisplay",
                visibleWhen = voiceEnabled
            )
        )
    )

    // ── Sub-screen: Confirmation Sound ───────────────────────────────────
    // "Disabled", or pick a tone + set its volume. When a tone is selected,
    // command acknowledgements play it instead of speaking (in a speaking
    // Response Format mode).
    private fun confirmationSoundSubScreen() = SubScreenSchema(
        title = "Confirmation Sound",
        parent = "voice_ai",
        sections = listOf(
            SettingsSection(
                items = listOf(
                    SchemaItem.Picker(
                        id = "confirmation_tone_type",
                        label = "Sound",
                        settingKey = "voice.confirmationToneType",
                        options = VoiceAiOptions.confirmationToneOptions,
                        onChanged = "notifyConfirmationToneChanged"
                    ),
                    SchemaItem.Slider(
                        id = "confirmation_tone_volume",
                        label = "Volume",
                        settingKey = "voice.confirmationToneVolume",
                        min = 1,
                        max = 10,
                        step = 1,
                        visibleWhen = toneSelected
                    )
                )
            )
        )
    )

    // ── AI Tools & Settings (cloud AI model selected) ────────────────────

    private fun aiToolsSection(choresAvailable: Boolean, isGeminiModel: Boolean): SettingsSection {
        val webSearchOptions = VoiceAiOptions.webSearchSourceOptions(isGeminiModel)
        return SettingsSection(
        header = "AI Tools & Settings",
        visibleWhen = cloudAiSelected,
        items = listOfNotNull(
            // Conversation dialog (console parity, 2026-07-12): ON = agentMode
            // 'dialog' (mic re-arms after replies), OFF = 'single'. Hidden while
            // a Live model is the agent — dialog is built into Live.
            SchemaItem.Toggle(
                id = "dialog_mode",
                label = "Conversation Dialog",
                sublabel = "Keep the mic open after a reply so you can keep talking — no wake word needed",
                settingKey = "voice.dialogModeEnabled",
                visibleWhen = notLive,
                onChanged = "notifyDialogModeChanged"
            ),
            // Hidden since 2026-07-18 behind VoiceFeatureFlags.KEEP_DIALOG_OPEN_ENABLED — the
            // feature works but may not be wanted; kept (not deleted) in case it's requested.
            // takeIf drops it from this listOfNotNull, so re-enabling is one flag flip. The flag
            // ALSO neutralizes the behavior (see VoicePipelineCoordinator.keepDialogOpenPref) —
            // hiding the toggle alone would strand accounts the console had seeded ON.
            SchemaItem.Toggle(
                id = "always_open_dialog",
                label = "Open Dialog After Commands",
                sublabel = "Keep listening after every command — not just questions",
                settingKey = "voice.alwaysOpenDialog",
                // Console parity: only while the Dialog toggle is ON (hidden in
                // Live/single — Live's dialog behavior is built into the model).
                visibleWhen = Condition.And(listOf(
                    cloudAiSelected, Condition.Equals("voice.agentMode", "dialog"))),
                onChanged = "notifyAlwaysOpenDialogChanged"
            ).takeIf { com.dashieapp.Dashie.halite.voice.VoiceFeatureFlags.KEEP_DIALOG_OPEN_ENABLED },
            SchemaItem.Picker(
                id = "web_search_source",
                label = "Web Search Source",
                settingKey = "ai.webSearchSource",
                options = webSearchOptions,
                onChanged = "notifyWebSearchChanged"
            ),
            SchemaItem.Toggle(
                id = "retrieve_pictures",
                label = "Retrieve Pictures",
                // No price here. Rates are hot-editable server-side (charge_rates) and the console
                // renders them dynamically — a number baked into the APK goes stale the moment a
                // rate changes and can only be corrected by shipping a build. Prices live in the
                // Console (2026-07-13).
                sublabel = "Allow the AI to show pictures with its responses — uses web image search",
                settingKey = "ai.retrievePicturesEnabled",
                onChanged = "notifyRetrievePicturesChanged"
            ),
            // Conversation memory hidden (2026-07-12) — not in use yet; re-add
            // the ai.conversationContextEnabled / ai.conversationTimeout rows
            // when it ships.
            if (choresAvailable) SchemaItem.Toggle(
                id = "always_use_ai",
                label = "Always Use AI for Chores",
                sublabel = "Disable fast path (uses more tokens)",
                settingKey = "ai.alwaysUseAI",
                onChanged = "notifyAlwaysUseAiChanged"
            ) else null
        )
        )
    }

    // ── Wake Word Collection ─────────────────────────────────────────────

    private fun wakeWordCollectionSection(feedbackPromptAvailable: Boolean = false) = SettingsSection(
        header = "Wake Word Collection",
        visibleWhen = voiceEnabled,
        items = listOf(
            SchemaItem.Toggle(
                id = "sample_collection",
                label = "Wake Word Training",
                sublabel = "Share audio clips to improve detection accuracy",
                settingKey = "voice.sampleCollectionEnabled"
            )
        ) + (
            // Voice response feedback prompt — alpha cohort only. Account-scoped
            // (ai.promptForFeedback); "No, don't ask again" on the overlay flips it.
            if (feedbackPromptAvailable)
                listOf(
                    SchemaItem.Toggle(
                        id = "prompt_for_feedback",
                        label = "Prompt for feedback on responses",
                        sublabel = "Show thumbs up/down after voice responses",
                        settingKey = "ai.promptForFeedback",
                        defaultValue = true,
                        onChanged = "notifyPromptForFeedbackChanged"
                    )
                )
            else emptyList()
        ) + (
            // Dev-only realtime conversation (Gemini Live) audio test — staging/local
            // flavors only. See 20260625_REALTIME_VOICE_CONVERSATION_MODE.md.
            if (com.dashieapp.Dashie.BuildConfig.FLAVOR == "staging" ||
                com.dashieapp.Dashie.BuildConfig.FLAVOR == "local")
                listOf(
                    SchemaItem.Action(
                        id = "rt_audio_test",
                        label = "RT Audio Test (dev)",
                        action = "openRtAudioTest"
                    ),
                    SchemaItem.Action(
                        id = "continuous_capture",
                        label = "Continuous Audio Capture (dev)",
                        action = "openContinuousCapture"
                    )
                )
            else emptyList()
        )
    )
}

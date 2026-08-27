package com.dashieapp.Dashie.halite.settings.schema.wiring

import android.content.Context
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.AiPreferences
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.halite.settings.schema.HaliteSettingsValueProvider
import com.dashieapp.Dashie.halite.settings.schemas.EngineResolver
import com.dashieapp.Dashie.halite.settings.schemas.VoiceAiOptions
import com.dashieapp.Dashie.halite.voice.tts.LocalTtsClient
import com.dashieapp.Dashie.wakeword.models.WakeWordModelManager

object VoiceAiSettingsWiring {

    // ── Voice ─────────────────────────────────────────────────────────

    fun registerVoicePreferences(vp: HaliteSettingsValueProvider, voice: VoicePreferences, context: Context) {
        // Booleans
        vp.registerBoolean("voice.enabled",
            getter = { voice.voiceEnabled },
            setter = { voice.voiceEnabled = it }
        )
        vp.registerBoolean("voice.sampleCollectionEnabled",
            getter = { voice.sampleCollectionEnabled },
            setter = { voice.sampleCollectionEnabled = it }
        )
        vp.registerBoolean("voice.realtimeAec",
            getter = { voice.realtimeAecEnabled },
            setter = { voice.realtimeAecEnabled = it }
        )
        vp.registerBoolean("voice.cascadeAec",
            getter = { voice.cascadeAecEnabled },
            setter = { voice.cascadeAecEnabled = it }
        )
        // DLG-6: account-scoped (ACCOUNT_VOICE_KEYS) — the native toggle writes locally +
        // round-trips to user_settings.voice via notifyAlwaysOpenDialogChanged.
        vp.registerBoolean("voice.alwaysOpenDialog",
            getter = { voice.alwaysOpenDialog },
            setter = { voice.alwaysOpenDialog = it }
        )

        // Voice control method and customize toggle
        vp.registerString("voice.controlMethod",
            getter = { voice.voiceControlMethod },
            setter = { voice.voiceControlMethod = it }
        )
        // Open Brain preset (Phase 3 Kotlin mirror). Getter returns the
        // EFFECTIVE preset — deriving one from the granular keys for accounts
        // that predate presets (mirrors the console's _activePreset; persisted
        // on the user's first preset pick). Setter stores + the
        // notifyPipelinePresetChanged callback seeds the granular keys.
        vp.registerString("voice.pipelinePreset",
            getter = {
                val stored = voice.pipelinePreset
                if (stored.isNotEmpty()) stored
                else {
                    val ai = AiPreferences(context)
                    val localVoice = voice.ttsProvider != VoicePreferences.TTS_DASHIE_CLOUD &&
                        voice.sttProvider != VoicePreferences.STT_DASHIE_CLOUD
                    when {
                        voice.voiceControlMethod == VoicePreferences.VOICE_METHOD_VOICE_ASSISTANT -> "ha_assist"
                        ai.aiModel == "local" && localVoice -> "local"
                        localVoice -> "hybrid"
                        else -> "cloud"
                    }
                }
            },
            setter = { voice.pipelinePreset = it }
        )
        // "My own AI" endpoint config (URL/model settable on-device; the API
        // key is Console-only — display an indicator, never the key).
        vp.registerString("voice.localLlmUrl",
            getter = { voice.localLlmUrl },
            setter = { voice.localLlmUrl = it }
        )
        vp.registerString("voice.localLlmModel",
            getter = { voice.localLlmModel },
            setter = { voice.localLlmModel = it }
        )
        // NB: no voice.localLlmKeyDisplay — the API Key row is gone. It was read-only (keys are
        // Console-owned), so it could only ever report "Set"/"Not set" with no way to act on it.
        // The console doesn't show it either. (2026-07-16)

        // ── Local Engines: show the engine's NAME, not its IP (2026-07-13) ──
        //
        // The console's Local Engines page owns the registry and resolves a selection into the
        // flat keys the runtime reads (localTtsUrl / localSttUrl / localLlmUrl+Model). We do NOT
        // re-derive those here — that mapping lives in exactly one place, so there's no
        // cross-boundary contract to drift. These are display-only lookups: url → engine name,
        // falling back to the URL when the registry has no match (a stale registry then simply
        // degrades to the old behavior rather than showing an empty row).
        fun engineDisplay(kind: String, url: String): String {
            val name = voice.selectedEngineName(kind)
            if (name.isNotBlank()) return name
            // Registry miss (stale push / engine deleted) → degrade to the URL rather than an
            // empty row, so the device still tells the truth about what it's pointed at.
            return if (url.isBlank()) "Not set" else url
        }
        // NB: localTts/localStt EngineDisplay are gone — their rows were removed (the stage picker
        // already names the box; a second row repeating it was noise). Only the LLM row survives,
        // because the MODEL is a real second axis on one box.
        vp.registerReadOnlyString("voice.localLlmEngineDisplay") {
            val name = voice.selectedEngineName("llm")
            val model = voice.localLlmModel
            // The model is the thing you switch between on ONE box, so show it alongside — but only
            // when it ISN'T already the engine's name, which would render it twice.
            val base = engineDisplay("llm", voice.localLlmUrl)
            if (model.isNotBlank() && !name.equals(model, ignoreCase = true)) "$base · $model" else base
        }

        // ── Console parity: name the ENGINE on the stage row, and badge its cost ──────────
        //
        // The console shows one row per stage reading "Kokoro on GPU · LOCAL". The tablet used to
        // show the stage CATEGORY ("Local TTS (your box)") because a picker's text is pinned to its
        // option's static label — so these keys feed Picker.displayValueKey/badgeKey instead.
        //
        // Each display key returns "" unless that stage is own-box: blank falls back to the option
        // label, so cloud/HA/Android rows keep rendering exactly as before and only the case the
        // registry actually knows about (a selected local engine) gets overridden.
        vp.registerReadOnlyString("voice.aiModelDisplay") {
            if (AiPreferences(context).aiModel == "local") {
                val name = voice.selectedEngineName("llm")
                val model = voice.localLlmModel
                val base = engineDisplay("llm", voice.localLlmUrl)
                if (model.isNotBlank() && !name.equals(model, ignoreCase = true)) "$base · $model" else base
            } else ""
        }
        // "home_assistant" is deliberately unbadged: the agent behind HA may itself be a cloud LLM,
        // so neither tag would be honest. An unrecognized value renders no badge (ValueBadge.from).
        vp.registerReadOnlyString("voice.aiModelBadge") {
            when (AiPreferences(context).aiModel) {
                "local" -> "local"
                "home_assistant" -> ""
                else -> "cloud"
            }
        }
        vp.registerReadOnlyString("voice.ttsProviderDisplay") {
            if (voice.ttsProvider == VoicePreferences.TTS_LOCAL_URL)
                engineDisplay("tts", voice.localTtsUrl)
            else ""
        }
        vp.registerReadOnlyString("voice.ttsProviderBadge") {
            if (voice.ttsProvider == VoicePreferences.TTS_DASHIE_CLOUD) "cloud" else "local"
        }
        vp.registerReadOnlyString("voice.sttProviderDisplay") {
            if (voice.sttProvider == VoicePreferences.STT_LOCAL_URL)
                engineDisplay("stt", voice.localSttUrl)
            else ""
        }
        vp.registerReadOnlyString("voice.sttProviderBadge") {
            if (voice.sttProvider == VoicePreferences.STT_DASHIE_CLOUD) "cloud" else "local"
        }
        // Own-box engine URLs + Piper voice — console-configured, editable here
        // too ("setting IP addresses is reasonable", 2026-07-12).
        vp.registerString("voice.localTtsUrl",
            getter = { voice.localTtsUrl },
            setter = { voice.localTtsUrl = it }
        )
        vp.registerString("voice.localSttUrl",
            getter = { voice.localSttUrl },
            setter = { voice.localSttUrl = it }
        )
        vp.registerString("voice.haTtsVoiceId",
            getter = { voice.haTtsVoiceId },
            setter = { voice.haTtsVoiceId = it }
        )
        // GAP-2: Live (S2S) voice — voice.liveVoiceName ↔ VoicePreferences.conversationVoice.
        // Proper Gemini voice names, so the display is the value itself; blank → engine default
        // (Aoede). cloud→native already handled in JsBridgeVoiceDelegate.setAiVoiceSettings.
        vp.registerString("voice.liveVoiceName",
            getter = { voice.conversationVoice },
            setter = { voice.conversationVoice = it }
        )
        vp.registerReadOnlyString("voice.liveVoiceDisplay") {
            voice.conversationVoice.ifBlank { "Aoede" }
        }
        // Own-box TTS voice. This was NEVER registered (only its HA sibling above was), so the
        // "Voice" row could neither READ nor WRITE it — it rendered blank and silently did nothing
        // while the device fell back to LocalTtsClient.DEFAULT_VOICE. Surface the effective value
        // rather than "", so the row tells the truth about what will actually speak.
        vp.registerString("voice.localTtsVoiceId",
            getter = { voice.localTtsVoiceId.ifBlank { LocalTtsClient.DEFAULT_VOICE } },
            setter = { voice.localTtsVoiceId = it }
        )
        // Punch #3: friendly-decoded DISPLAY for the two Voice rows so they read "Amy (fast)"
        // instead of the raw id (the edit dialog still takes the real voice id). Console parity
        // via VoiceLabelDecoder (JS_KOTLIN_CONTRACTS #44). Blank → the row falls back to the raw.
        vp.registerReadOnlyString("voice.haTtsVoiceDisplay") {
            com.dashieapp.Dashie.halite.voice.VoiceLabelDecoder.piperVoiceLabel(null, voice.haTtsVoiceId)
        }
        vp.registerReadOnlyString("voice.localTtsVoiceDisplay") {
            com.dashieapp.Dashie.halite.voice.VoiceLabelDecoder.localVoiceLabel(
                voice.localTtsVoiceId.ifBlank { LocalTtsClient.DEFAULT_VOICE }
            )
        }
        // Conversation-dialog toggle (console parity): ON = agentMode 'dialog',
        // OFF = 'single'. Live is set by picking a Live model, never here.
        vp.registerBoolean("voice.dialogModeEnabled",
            getter = { voice.conversationEngineMode == "dialog" },
            setter = { voice.agentMode = if (it) "dialog" else "single" }
        )
        // DLG-6: expose the EFFECTIVE agent mode (live|dialog|single) so schema Conditions can
        // gate on conversation mode. Returns conversationEngineMode (derives from the old
        // conversationModel/Always pair when agentMode is unset), never empty. READ-ONLY —
        // agentMode is console-owned on native, so no setter (avoids the settable-no-schema flag).
        vp.registerReadOnlyString("voice.agentMode") { voice.conversationEngineMode }
        vp.registerBoolean("voice.customizePipeline",
            getter = { voice.customizePipeline },
            setter = { voice.customizePipeline = it }
        )

        // Provider selections
        vp.registerString("voice.sttProvider",
            getter = { voice.sttProvider },
            setter = { voice.sttProvider = it }
        )
        vp.registerString("voice.ttsProvider",
            getter = { voice.ttsProvider },
            setter = { voice.ttsProvider = it }
        )

        // ── Virtual selection keys: one picker row per SAVED engine ──────────────────────
        //
        // The stage pickers bind to these instead of the raw provider key, because N local
        // engines all resolve to the SAME provider value ("local_url") — as raw-key options
        // they'd be indistinguishable, and the picker matches the selected row by unique
        // value. So the token is per-engine ("engine:<id>") and NOTHING here is persisted
        // under that name: the setter resolves it into exactly the flat keys the console
        // writes, via the GENERATED EngineResolver (contract #29). Persisted shape is
        // unchanged, so old APKs and the console read it identically.
        //
        // getter: flat keys -> the token naming them.  setter: token -> flat keys.
        fun selectionGetter(kind: String, currentProvider: String): String {
            val spec = EngineResolver.spec(kind) ?: return currentProvider
            // Cloud / HA / Android: the provider id IS the option value, unchanged.
            if (currentProvider != spec.providerValue) return currentProvider
            // Own-box: name the engine when we can identify it. If we can't (registry never
            // synced, or the engine was deleted while still selected) fall back to the generic
            // provider id, which ttsOptions/sttOptions keep offering in exactly that case —
            // so the row still renders instead of showing an unmatched blank.
            val engine = voice.selectedEngine(kind) ?: return currentProvider
            return VoiceAiOptions.engineOptionValue(engine.id)
        }
        fun applySelection(kind: String, token: String, providerKey: String) {
            val engineId = VoiceAiOptions.engineIdOf(token)
            if (engineId == null) {
                // A plain provider id (cloud / HA / generic local). Route through the REGISTERED
                // key, never straight at the pref: ai.model's setter also owns the Live-model
                // branch (conversationModel + agentMode), so writing AiPreferences directly here
                // silently dropped it — picking a Live model wrote a Live id into ai.model, and
                // leaving Live never reset agentMode to "single".
                vp.setString(providerKey, token)
                return
            }
            val engine = voice.localEnginesList().firstOrNull { it.id == engineId && it.kind == kind }
            // Unknown id (stale menu vs a registry that just changed): do NOTHING rather than
            // write half a pipeline. The row simply stays where it was.
                ?: return
            EngineResolver.resolveToSettings(kind, engine.url, engine.model).forEach { (k, v) ->
                vp.setString(k, v)
            }
        }
        // Both directions go through the REGISTERED provider keys, so any logic those setters
        // own (ai.model's Live branch above) still runs — a virtual key must be a lens over the
        // real key, never a second way to write it.
        vp.registerString("voice.ttsSelection",
            getter = { selectionGetter("tts", vp.getString("voice.ttsProvider") ?: "") },
            setter = { applySelection("tts", it, "voice.ttsProvider") }
        )
        vp.registerString("voice.sttSelection",
            getter = { selectionGetter("stt", vp.getString("voice.sttProvider") ?: "") },
            setter = { applySelection("stt", it, "voice.sttProvider") }
        )
        vp.registerString("voice.aiModelSelection",
            getter = { selectionGetter("llm", vp.getString("ai.model") ?: "") },
            setter = { applySelection("llm", it, "ai.model") }
        )

        // Strings (pipelineMode is now derived, but keep setter for legacy)
        vp.registerString("voice.pipelineMode",
            getter = { voice.voicePipelineMode },
            setter = { voice.voicePipelineMode = it }
        )
        vp.registerString("voice.responseHandling",
            getter = { voice.responseHandling },
            setter = { voice.responseHandling = it }
        )
        vp.registerString("voice.displayFormat",
            getter = { voice.displayFormat },
            setter = { voice.displayFormat = it }
        )
        vp.registerString("voice.confirmationToneType",
            getter = { voice.confirmationToneType },
            setter = { voice.confirmationToneType = it }
        )
        // Slider exposes 1–10; stored as 0.0–1.0 (mirrors videoFeed.alertVolume)
        vp.registerInt("voice.confirmationToneVolume",
            getter = { (voice.confirmationToneVolume * 10).toInt().coerceIn(1, 10) },
            setter = { voice.confirmationToneVolume = (it.coerceIn(1, 10) / 10f) }
        )
        // Computed summary row on the Voice & AI page, e.g. "Soft Double (vol 4)"
        // or "Disabled" (read-only).
        vp.registerString("voice.confirmationSoundDisplay",
            getter = {
                if (!voice.confirmationToneEnabled) {
                    "Disabled"
                } else {
                    val label = com.dashieapp.Dashie.halite.settings.schemas.VoiceAiOptions
                        .confirmationToneOptions.firstOrNull { it.value == voice.confirmationToneType }
                        ?.label ?: voice.confirmationToneType
                    val vol = (voice.confirmationToneVolume * 10).toInt().coerceIn(1, 10)
                    "$label (vol $vol)"
                }
            },
            setter = { }
        )
        vp.registerString("voice.voiceKey",
            getter = { voice.voiceKey },
            setter = { voice.voiceKey = it }
        )
        vp.registerString("voice.voiceId",
            getter = { voice.voiceId },
            setter = { voice.voiceId = it }
        )

        // Computed display values (read-only)
        vp.registerString("voice.pipelineModeDisplay",
            getter = {
                when (voice.voicePipelineMode) {
                    VoicePreferences.VOICE_PIPELINE_MODE_AI -> "Dashie"
                    else -> "HA Assist"
                }
            },
            setter = { }
        )
        vp.registerString("voice.responseHandlingDisplay",
            getter = {
                when (voice.responseHandling) {
                    VoicePreferences.RESPONSE_HANDLING_READ_AND_DISPLAY -> "Read & Display"
                    VoicePreferences.RESPONSE_HANDLING_READ_ONLY -> "Read Only"
                    VoicePreferences.RESPONSE_HANDLING_DISPLAY_ONLY -> "Display Only"
                    VoicePreferences.RESPONSE_HANDLING_NONE -> "Silent"
                    else -> voice.responseHandling
                }
            },
            setter = { }
        )
        vp.registerString("voice.wakeWordDisplay",
            getter = {
                val name = try {
                    WakeWordModelManager(context).getActiveModel().wakeWordName
                } catch (_: Exception) {
                    "Hey Dashie"
                }
                // GAP-2: when inheriting the account default (no per-device override), the active
                // model IS the account default, so show "Default (<name>)" to match the picker.
                val inheriting = com.dashieapp.Dashie.halite.preferences.AiPreferences(context).wakeWordInheriting
                if (inheriting) "$name (Default)" else name
            },
            setter = { }
        )
        vp.registerString("voice.haPipelineDisplay",
            getter = {
                val name = voice.voicePipelineName
                if (name.isNotEmpty()) name
                else {
                    val preferred = voice.preferredPipelineCachedName
                    preferred.ifEmpty { "Default" }
                }
            },
            setter = { }
        )
        vp.registerString("voice.voiceDisplay",
            getter = {
                // For now show the voice key; Supabase voice name lookup comes later
                voice.voiceKey.ifEmpty { "Bella" }
            },
            setter = { }
        )

        // Voice licence rows — registered ONLY in an edition that has a licence concept.
        //
        // The three licence value-providers that lived here (voice.licenseStatus,
        // voice.licenseDisplay, voice.hasActiveLicense) went with the voice licence on
        // 2026-08-02. Their only consumer was VoiceAiPageSchema's licence section, removed in
        // the same change — so this is a deletion, not a stubbing: no provider is left
        // answering a question about a licence that no longer exists.
    }

    // ── AI ────────────────────────────────────────────────────────────

    fun registerAiPreferences(vp: HaliteSettingsValueProvider, ai: AiPreferences, voice: VoicePreferences? = null) {
        // Booleans
        vp.registerBoolean("ai.webSearchEnabled",
            getter = { ai.webSearchEnabled },
            setter = { ai.webSearchEnabled = it }
        )
        vp.registerBoolean("ai.retrievePicturesEnabled",
            getter = { ai.retrievePicturesEnabled },
            setter = { ai.retrievePicturesEnabled = it }
        )
        vp.registerBoolean("ai.promptForFeedback",
            getter = { ai.promptForFeedback },
            setter = { ai.promptForFeedback = it }
        )
        vp.registerBoolean("ai.conversationContextEnabled",
            getter = { ai.conversationContextEnabled },
            setter = { ai.conversationContextEnabled = it }
        )
        vp.registerBoolean("ai.alwaysUseAI",
            getter = { ai.alwaysUseAI },
            setter = { ai.alwaysUseAI = it }
        )
        // Web Search Source picker (console parity): derived key over
        // ai.webSearchEnabled — 'none' = off; the provider itself is
        // model-derived (Gemini → Google grounding, else Dashie/Tavily),
        // not a stored setting.
        vp.registerString("ai.webSearchSource",
            getter = {
                if (!ai.webSearchEnabled) "none"
                else if (ai.aiModel.startsWith("gemini-")) "google" else "dashie"
            },
            setter = { ai.webSearchEnabled = it != "none" }
        )

        // Strings
        // The model picker mixes Live S2S models (Cloud preset, top group) with
        // cascade models — console parity (Open Brain §7). Picking a Live model
        // sets agentMode='live' + conversationModel and leaves ai.model alone;
        // picking a cascade model restores 'single' when leaving Live.
        vp.registerString("ai.model",
            getter = {
                val v = voice
                if (v != null && v.conversationEngineMode == "live" && v.conversationModel.isNotEmpty()) v.conversationModel
                else ai.aiModel
            },
            setter = { picked ->
                val v = voice
                if (v != null && com.dashieapp.Dashie.halite.settings.schemas.VoiceAiOptions.isLiveModel(picked)) {
                    v.conversationModel = picked
                    v.agentMode = "live"
                } else {
                    if (v != null && v.conversationEngineMode == "live") v.agentMode = "single"
                    ai.aiModel = picked
                }
            }
        )
        vp.registerString("ai.personalityId",
            getter = { ai.personalityId },
            setter = { ai.personalityId = it }
        )

        // Int stored as string for picker compatibility
        vp.registerString("ai.conversationTimeout",
            getter = { ai.conversationTimeout.toString() },
            setter = { ai.conversationTimeout = it.toIntOrNull() ?: 30 }
        )

        // Computed display values (read-only)
        vp.registerString("ai.modelDisplay",
            getter = { getAiModelDisplayName(ai.aiModel) },
            setter = { }
        )
        vp.registerString("ai.conversationTimeoutDisplay",
            getter = {
                when (ai.conversationTimeout) {
                    5 -> "5 minutes"
                    30 -> "30 minutes"
                    60 -> "1 hour"
                    360 -> "6 hours"
                    0 -> "Never"
                    else -> "${ai.conversationTimeout} minutes"
                }
            },
            setter = { }
        )
        vp.registerString("ai.personalityDisplay",
            getter = {
                // Use cached display name set by PersonalityPickerFragment / JS push
                val name = ai.personalityDisplayName.ifEmpty {
                    // Fallback for legacy values before display name was cached
                    when (ai.personalityId) {
                        "home_assistant" -> "Home Assistant"
                        "dashie" -> "Dashie"
                        else -> ai.personalityId.replaceFirstChar { it.uppercase() }
                    }
                }
                // GAP-2: when the device inherits the account default (no per-device override),
                // show "Default (<name>)" so the summary matches the picker's checked "Default (X)" row.
                if (ai.personalityInheriting) "$name (Default)" else name
            },
            setter = { }
        )
    }

    /** Map AI model ID to display name. Current models come from the shared
     *  catalog (no drift); the fallback covers legacy ids users may still have
     *  stored from before a model update. */
    private fun getAiModelDisplayName(modelId: String): String {
        if (modelId == "home_assistant") return "Home Assistant"
        com.dashieapp.Dashie.halite.settings.schemas.AiModelCatalog.MODELS
            .firstOrNull { it.id == modelId }?.let { return it.label }
        return when {
            modelId.contains("sonnet-4-5") -> "Claude Sonnet 4.5"
            modelId.contains("sonnet-4") -> "Claude Sonnet 4.0"
            modelId.contains("opus-4") -> "Claude Opus 4.0"
            modelId == "gpt-4o-mini" -> "GPT-4o Mini"
            modelId == "gpt-4o" -> "GPT-4o"
            modelId == "gpt-4-turbo" -> "GPT-4 Turbo"
            modelId == "gpt-3.5-turbo" -> "GPT-3.5 Turbo"
            modelId.contains("gemini-2.5-flash-lite") -> "Gemini 2.5 Flash Lite"
            modelId.contains("gemini-2.5-flash") -> "Gemini 2.5 Flash"
            modelId.contains("gemini-2.0-flash") -> "Gemini 2.0 Flash"
            modelId.contains("nova-lite") -> "Amazon Nova Lite"
            else -> modelId
        }
    }
}

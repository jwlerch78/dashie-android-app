package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.halite.settings.schema.SchemaPickerOption

/**
 * Picker option lists for the Voice & AI settings page.
 * Extracted from VoiceAiPageSchema to keep the schema file focused on layout.
 */
object VoiceAiOptions {

    // ── Pipeline preset (Open Brain §6 — mirrors the console picker) ────
    // Values match voice.pipelinePreset: cloud | hybrid | local | ha_assist.
    // Cloud/Hybrid need the Dashie cloud path (credits or BYO key — gated by
    // cloudAvailable here); Local & HA Assist are always available (HA Assist
    // only when an HA is configured).

    /**
     * Release-week hold: with **no account**, HA Voice Assist is the only voice setup offered and
     * the Dashie built-in pitch is suppressed. See [presetOptions] for what is removed and why each
     * option is unreachable rather than merely degraded.
     *
     * ## Why this keys off the ACCOUNT and not the build flavor
     *
     * The first cut gated on `BuildConfig.FLAVOR == "prod"` so staging/local could keep the full
     * picker while the team worked. That made **prod the only configuration nobody tests** — the
     * shape that produces "worked on staging, broke in prod".
     *
     * The account condition removes the divergence at no cost, because **every option this hides
     * requires an account to function**: Cloud and Hybrid need credits or a BYO key, and Local
     * needs a local-engine URL that can only be set in the console. Anyone who can meaningfully
     * work on them is signed in — and voice on staging is alpha/beta gated, so testers hold an
     * account regardless (2026-08-24). Signing in restores the full picker on any build, so
     * one code path serves both audiences and staging genuinely exercises what prod does.
     *
     * ⚠️ A server-side feature flag — the usual answer — is **impossible here**: this repo's flag
     * mechanism (`feature_access`, tier × rollout) is ACCOUNT-scoped, and the entire audience being
     * gated has no account. There is no channel to reach them. Recorded so the next person does not
     * spend the afternoon rediscovering it.
     *
     * 📌 ONE definition, consumed by both `VoiceAiOptions.presetOptions` (which options exist) and
     * `VoiceAiPageSchema.voiceHandlingSection` (whether the explainer + "Details & Setup" render).
     * Those must agree — a page that hides the options but keeps the banner advertising them, or
     * vice versa, is the drift this repo has paid for repeatedly. Do not inline the condition.
     *
     * 📌 REMOVE once Cloud/Hybrid/Local are genuinely reachable without a console (or are dropped
     * on their own merits). Still here after the add-on ships ⇒ it is stale and is hiding working
     * features from users who have paid for them.
     */
    fun isLockedToHaAssist(hasAccount: Boolean): Boolean = !hasAccount

    fun presetOptions(
        cloudAvailable: Boolean,
        haEnabled: Boolean,
        /** Does a metered cloud service exist in this EDITION? Distinct from [cloudAvailable],
         *  which asks whether this ACCOUNT can currently spend. False → Cloud and Hybrid are
         *  omitted outright rather than shown disabled with an add-credits explainer, because
         *  in an edition with no metered service they describe nothing. No default: the
         *  compiler should name every caller. */
        offersMeteredCloud: Boolean,
        /**
         * Is an account bound? **No account ⇒ HA Voice Assist is the ONLY preset offered**
         * (2026-08-24: *"we just need to currently fix the Voice & AI Setup as HA Voice
         * Assist and not allow an update through the Voice & AI Setup page"*).
         *
         * 🔴 Why the other three are removed rather than disabled — each is unreachable, not
         * merely degraded, and that was device-observed on the real shipping state:
         *  - **Cloud / Hybrid** were already `enabled = false` here, but the picker renders a
         *    disabled preset **identically to a selectable one** — no greying, no affordance. A
         *    user cannot tell they are dead, taps, and gets a dialog pointing at an add-on that
         *    **has not been released**.
         *  - **Local** was worse: unguarded, always selectable, and it seeds
         *    `voiceControlMethod = DASHIE_CLOUD` + `aiModel = "local"` — the Dashie cascade aimed
         *    at a local LLM URL. It does NOT fall back to HA's conversation agent. And the URL
         *    **cannot be set from the device at all**: "URLs/engine ids are configured in the
         *    console" (see [sttOptions]), which this audience does not have. A menu item nobody
         *    who can see it is able to make work.
         *
         * Defaults true so every existing caller keeps the full picker; the no-account page
         * passes false.
         */
        hasAccount: Boolean = true,
    ): List<SchemaPickerOption> {
        // Release-week hold on an unfinished area, not the final design. Keyed on the ACCOUNT so
        // prod and staging run one code path — see [isLockedToHaAssist] for why, and for when to
        // delete it.
        val lockedToHaAssist = isLockedToHaAssist(hasAccount)
        // No account: one real option, and it is already the active one — so there is nothing to
        // tap, nothing to revert, and no dead end.
        //
        // ⚠️ Deliberately NOT conditioned on [haEnabled]. There is no such user as no-account +
        // no-HA: without HA (and outside alpha/beta) there is no access to voice at all, so this
        // page is unreachable for them (2026-08-24). An earlier draft gated this on
        // `haEnabled` "defensively" — which would have fallen through to the FULL list, handing a
        // no-account user exactly the three dead options this branch exists to remove. A guard
        // for an impossible state that fails toward the bug is worse than no guard.
        if (lockedToHaAssist) {
            return listOf(SchemaPickerOption("ha_assist", "HA Voice Assist",
                "Hand voice off to Home Assistant's Assist pipeline, configured in HA."))
        }
        // When cloud isn't available (no account / no credits), Cloud & Hybrid
        // stay in the list but route a tap to onCloudPresetBlocked instead of
        // silently no-op'ing — the callback shows the cloud-activation dialog
        // (create account / add credits / set up console). enabled=false keeps
        // them un-selectable; onDisabledTap makes the row a tappable explainer.
        val cloudBlockedTap = if (cloudAvailable) null else "onCloudPresetBlocked"
        // The Voice & AI explainer + "Details & Setup" entry are rendered by the
        // picker's leadingDescription/leadingAction (VoiceAiPageSchema), NOT as
        // options here — so these stay the real, selectable presets.
        val options = mutableListOf<SchemaPickerOption>()
        if (offersMeteredCloud) {
            options.add(SchemaPickerOption("cloud", "Cloud",
                "Anonymized cloud AI & Voices (Requires credits and/or API key)",
                enabled = cloudAvailable, onDisabledTap = cloudBlockedTap))
            options.add(SchemaPickerOption("hybrid", "Hybrid",
                "Mix cloud and local models (requires credits and/or API key)",
                enabled = cloudAvailable, onDisabledTap = cloudBlockedTap))
        }
        options.add(SchemaPickerOption("local", "Local",
            "All local, all the time (set up engines in the Dashie add-on)"))
        if (haEnabled) {
            options.add(SchemaPickerOption("ha_assist", "HA Voice Assist",
                "Hand voice off to Home Assistant's Assist pipeline, configured in HA."))
        }
        return options
    }

    /** Preset filter mirroring the console rules: Local → local rows only;
     *  Cloud → cloud rows only; Hybrid → everything minus va_default;
     *  HA Assist → local rows only (mix the Assist pipeline with e.g. the
     *  Android voice — no metered cloud rows). Locality by option value. */
    private val CLOUD_VALUES = setOf("dashie_cloud")
    fun filterByPreset(
        preset: String,
        options: List<SchemaPickerOption>,
        offersMeteredCloud: Boolean,
    ): List<SchemaPickerOption> {
        // Edition gate first: with no metered service, a Dashie-Cloud row must never appear
        // whatever the preset says — including the "cloud" preset, which would otherwise
        // filter DOWN TO exactly the rows that should not exist.
        if (!offersMeteredCloud) return options.filter { it.value !in CLOUD_VALUES }
        return when (preset) {
            "local", "ha_assist" -> options.filter { it.value !in CLOUD_VALUES }
            "cloud" -> options.filter { it.value in CLOUD_VALUES }
            "hybrid" -> options.filter { it.value != "va_default" }
            else -> options
        }
    }

    /** Groups the preset picker under two headers — "Dashie" (Cloud / Hybrid /
     *  Local; the explainer + "Details & Setup" block are the picker's lead-in,
     *  not options) and "HA Voice" (Voice Assistant). Indices track presetOptions'
     *  layout: cloud=0, hybrid=1, local=2, ha_assist=3. HA Voice header only when
     *  ha_assist is present.
     *
     *  The header names the PROVIDER of the pipeline, which is the contrast that
     *  matters here (Dashie's own presets vs handing off to HA's Assist) — so it
     *  is the brand, not an edition name. Was "Chickadee" until T2h. */
    fun presetSectionHeaders(haEnabled: Boolean): List<Pair<String, Int>> {
        // BRAND-TRIAGED (A, 2026-08-05): brand-NEUTRAL, not brand-resolved — Context-free schema
        // object, same call as pass 1's AdvancedPageSchema/VoiceAiPageSchema. "Built-in" keeps the
        // contrast the header exists for (this app's own pipeline vs handing off to HA Assist).
        val headers = mutableListOf("Built-in" to 0)
        if (haEnabled) headers.add("HA Voice" to 3)
        return headers
    }

    // ── Voice Control Method ────────────────────────────────────────────
    // The `voiceControlMethodOptions` picker was DELETED 2026-08-05 (Chickadee
    // brand-prose removal): superseded by presetOptions above, uncalled in
    // Kotlin, and its rows spelled "Dashie Cloud"/"Requires Dashie subscription"
    // into the published tree. `voice.controlMethod` itself is UNCHANGED — it is
    // still the runtime key, still derived from the preset by VoicePresetSeeder,
    // and its value vocabulary now lives (and is lint-gated, contract #16) on
    // VoicePreferences.VOICE_METHOD_* rather than here.

    // ── STT Provider Options ────────────────────────────────────────────
    // ha_engine (Whisper engine-direct) + local_stt_url (own-box Whisper) were
    // previously bridge-settable only — exposed here so console-configured
    // modes are reachable from the device too (Phase 3). URLs/engine ids are
    // configured in the console; the rows describe where the value comes from.

    fun sttOptions(
        hasAccount: Boolean,
        cloudAvailable: Boolean = false,
        haEnabled: Boolean = true,
        engines: List<VoicePreferences.LocalEngine> = emptyList(),
        // WS-D.1: Android's built-in SpeechRecognizer is a real provider now — selectable
        // where it can actually run (Google services present). Grayed on Fire OS / Lineage,
        // where SpeechRecognizer.isRecognitionAvailable() is false, rather than offering a
        // pick that silently falls back to the priority chain.
        androidSttAvailable: Boolean = false,
        // Bundled sherpa-onnx on-device STT (the Amazon/de-Googled reach). Selectable only
        // when the APK actually bundles the engine (dev flavors); grayed otherwise, same
        // stance as androidSttAvailable.
        sherpaAvailable: Boolean = false,
        /**
         * On-device model state, keyed by the FROZEN provider id: `null` value ⇒ ready to use
         * (bundled or already downloaded); an Int ⇒ that many MB must be downloaded first. A key
         * that is absent ⇒ unknown to the registry, so the row keeps its historic wording.
         */
        sherpaModels: Map<String, Int?> = emptyMap()
    ): List<SchemaPickerOption> {
        // Order mirrors the console: cloud → On-Device family (Open Source / System) → HA →
        // "+ your box" at the bottom.
        val options = mutableListOf(
            // Vendor-neutral, matching the console (which abstracted these names in
            // e04d588): the user buys "Dashie Cloud", not a named third party — and naming
            // the vendor makes the label a lie the day we switch providers.
            SchemaPickerOption("dashie_cloud", "Cloud STT",
                if (hasAccount) "Streaming, premium accuracy"
                else "Requires Dashie subscription",
                enabled = cloudAvailable)
        )
        // On-Device family. Open Source = bundled sherpa-onnx (offline, no chime, works on
        // Amazon/de-Googled); System = the OS SpeechRecognizer (Google-services only, chime).
        if (sherpaAvailable) {
            // ⚠️ IDs ARE FROZEN by CONTRACT #16 (`lint:voice-options` cross-checks the STT id set
            // against js/data/settings/voice-ai-value-ids.js). Only the DESCRIPTION varies with
            // install state — the row says the same thing it always did, plus what it will cost.
            // That row explicitly allows this: "labels differ per surface, ids gated".
            //
            // 🔴 Why the description can no longer say "Bundled": since 2026-08-04 the engine
            // (`sherpaAvailable`, the .so) ships in flavors that carry NO models. Claiming
            // "Bundled offline STT" there would describe a model that is not present — the
            // option reading as working when selecting it cannot work. The size is stated
            // BEFORE the user commits, which is the whole point of the on-demand design.
            //
            // 🔴 A row whose model is not installed is NOT SELECTABLE — it routes the tap to the
            // download instead (`onSttModelDownloadRequested` → SttModelDownloadPrompt), and the
            // selection is written only once the install succeeds. Letting it be picked first
            // would point the STT lane at a model that is not on the device: present-but-broken,
            // reached from the settings side. The option works, or it offers to become real.
            // 🔴 moonshine-TINY IS RETIRED FROM THE OFFERING (2026-08-20: "Yes - B").
            // S's 641-clip file-fed measurement cleared base (0.3% empties, no short-utterance
            // failure mode); T's device work showed tiny hallucinating words out of ANY noise,
            // including an 80 ms click the brain then answered. One on-device model, and it is
            // the one that works. The id stays recognised for migration (see
            // SttModelRegistry.RETIRED_FAMILY_IDS) — retired means "not offered", not "unknown".
            //
            // 🔴 NAMING (2026-08-20: "I like Open Source and System"). Two rulings, in order:
            // first "remove 'accurate' from the naming convention" — "(Accurate)"/"(Fast)" were a
            // CONTRAST between two models, and with one model "Accurate" is a bare promise. Then
            // the replacement qualifiers.
            //
            // 📌 THE DISTINGUISHER IS PROVENANCE, NOT NATIVENESS. Both rows run on the device, so
            // "(Native)" was answering a question nobody asked and implying the wrong contrast.
            // The HA audience is deciding **whose code is listening** — ours (open source, in the
            // app) or the OS vendor's. Hence "(Open Source)" vs ~~"(System)"~~ "(Built-in)".
            //
            // 🔴 "(System)" → "(Built-in)" 2026-08-24 (*"System seems a bit broad in this
            // context"*). That was right, and a device survey had just measured WHY: "System" named a **slot**,
            // not an engine, and the slot resolves differently per device — on a Samsung SM-X200
            // the only registered `RecognitionService` is Google's (`com.google.android.tts`,
            // SODA); on a Fire there are ZERO and `voice_recognition_service` is Amazon Alexa.
            // "(Built-in)" names the MECHANISM — *the device's built-in recogniser* — which is
            // true on every OEM.
            // ⚠️ **"(Native)" was requested first and rejected AGAIN, for the reason directly
            // above:** both rows are native, so it implies a contrast that does not exist. If it
            // is proposed a third time, this paragraph is the answer.
            // ⚠️ The console mirrors this label (`voice-ai-options.js`, edited in the SOURCE tree
            // `dashie-ha-console/dashie-ha/frontend/console/` — never in `dashie-ha-dev/**` or in
            // the vendored `dashie-console` copy, both of which are regenerated destructively).
            //
            // ⚠️ "Google" stays OUT of the label and IN the sublabel: in the label it reads as an
            // accusation, in the sublabel as disclosure. And these are provenance words, not
            // quality words — the no-quality-words ruling above still holds, so do not reintroduce
            // "accurate"/"fast"/"best" here.
            options.add(SchemaPickerOption("sherpa_moonshine_base", "On-Device (Open Source)",
                sherpaDesc(sherpaModels, "sherpa_moonshine_base",
                    "Open-source Moonshine model, runs inside Dashie. Fully offline, works on any device."),
                enabled = sherpaReady(sherpaModels, "sherpa_moonshine_base"),
                onDisabledTap = sherpaTap(sherpaModels, "sherpa_moonshine_base"),
                actionLabel = sherpaAction(sherpaModels, "sherpa_moonshine_base")))
        }
        options.add(SchemaPickerOption("android_voice", "On-Device (Built-in)",
            // "usually Google's" rather than "Google's": on Fire OS / de-Googled builds it is not,
            // and isAvailable() is false there — the hedge is accurate, not evasive.
            if (androidSttAvailable)
                "Your device's built-in speech recognizer (usually Google's). " +
                "No download; availability and quality vary by device."
            else "Not available on this device",
            enabled = androidSttAvailable))
        if (haEnabled) {
            options.add(SchemaPickerOption("ha_engine", "Whisper (Home Assistant)",
                "Your HA Whisper engine, direct — no Assist pipeline"))
            options.add(SchemaPickerOption("va_default", "Voice Assistant Default",
                "Uses your HA voice pipeline's STT"))
        }
        // "+ your box" at the bottom: named engines when the registry has any; else the generic row.
        val local = engineOptions(engines, "stt")
        if (local.isEmpty()) {
            options.add(SchemaPickerOption("local_stt_url", "Local Whisper (your box)",
                "OpenAI-compatible Whisper server — add one in the Dashie Console"))
        } else {
            options.addAll(local)
        }
        return options
    }

    // ── AI Model Options ────────────────────────────────────────────────

    // Live S2S models — shown at the TOP of the model picker in the Cloud

    /**
     * Describe an on-device STT row: ready to use, or what it will cost to get there.
     *
     * ⚠️ "Download required" is stated as a SIZE, not a nag. The user is choosing an engine; the
     * cost belongs in the choice. Nothing here prompts a user on SpeechRecognizer or a cloud
     * engine — they simply never read this line.
     */
    /**
     * @param trait the model's own sentence(s); the INSTALL STATE clause is appended here so the
     *   three states share one shape and the size stays DATA-DRIVEN (from
     *   `SttModelRegistry.Family.approxMb`, the measured wire size). Never hardcode a number in
     *   the trait — that is how the dialog came to state tiny's 30 MB for base's 111 MB download.
     */
    private fun sherpaDesc(models: Map<String, Int?>, id: String, trait: String): String {
        if (!models.containsKey(id)) return trait
        val mb = models[id] ?: return "$trait Installed on this device."
        return "$trait ~$mb MB download."
    }

    /**
     * Is this on-device model ready to be SELECTED — i.e. installed (bundled or downloaded)?
     *
     * ⚠️ An id the registry does not know keeps its historic behaviour (selectable), matching
     * [sherpaDesc]'s absent-key branch. Only a key present with a size means "not here yet".
     */
    private fun sherpaReady(models: Map<String, Int?>, id: String): Boolean =
        !models.containsKey(id) || models[id] == null

    /** The download route for a not-yet-installed row; null when the row is selectable. */
    private fun sherpaTap(models: Map<String, Int?>, id: String): String? =
        if (sherpaReady(models, id)) null else "onSttModelDownloadRequested"

    /**
     * The explicit affordance on a not-installed row (2026-08-18): the row grays like any
     * unavailable option, and the lit "Download" button is what says the state is fixable — the
     * verb, not just a description of the cost.
     */
    private fun sherpaAction(models: Map<String, Int?>, id: String): String? =
        if (sherpaReady(models, id)) null else "Download"

    // preset (mirrors the console: Live is fully cloud + credits; selecting
    // one sets agentMode=live and hides STT/TTS). Ids match
    // voice.conversationModel values.
    val LIVE_MODELS = listOf(
        SchemaPickerOption("gemini-3.1-flash-live-preview", "Gemini 3.1 Live (faster)",
            "Realtime conversation — speech, language & search in one model"),
        // Dated alias — the floating '-latest' was retired by Google (1008 silent-close,
        // 2026-07-20). Must be a member of CONVERSATION_MODEL_IDS (asserted by lint:voice-options).
        SchemaPickerOption("gemini-2.5-flash-native-audio-preview-12-2025", "Gemini 2.5 Live (more capable)",
            "Realtime conversation — more capable, slightly slower")
    )
    fun isLiveModel(id: String) = LIVE_MODELS.any { it.value == id }

    // Live (S2S) prebuilt voices — voice.liveVoiceName values (Gemini prebuilt voices). Account-
    // scoped → no "Default" row (it IS the account setting; propagates account-wide). The ids MUST
    // match CONVERSATION_VOICE_IDS (js/data/settings/voice-ai-value-ids.js) — asserted by
    // lint:voice-options (JS ↔ Console ↔ Kotlin). The id list is kept flat (one string per voice) so
    // the lint parses it cleanly; the character sublabels live in a separate map below (labels/descs
    // may differ from the canon — only ids are gated). Descriptions mirror the console picker.
    // Voice character (console parity — console/js/pages/voice-ai.js CONVERSATION_VOICES). Prose,
    // not a gated contract (lint gates ids only) — shown as the picker row's sublabel. Declared
    // BEFORE LIVE_VOICES since that list reads it during object init.
    private val LIVE_VOICE_DESCRIPTIONS = mapOf(
        "Aoede" to "Breezy", "Zephyr" to "Bright", "Puck" to "Upbeat", "Charon" to "Informative",
        "Kore" to "Firm", "Fenrir" to "Excitable", "Leda" to "Youthful", "Orus" to "Firm",
        "Callirrhoe" to "Easy-going", "Autonoe" to "Bright", "Enceladus" to "Breathy",
        "Iapetus" to "Clear", "Umbriel" to "Easy-going", "Algieba" to "Smooth", "Despina" to "Smooth",
        "Erinome" to "Clear", "Algenib" to "Gravelly", "Rasalgethi" to "Informative",
        "Laomedeia" to "Upbeat", "Achernar" to "Soft", "Alnilam" to "Firm", "Schedar" to "Even",
        "Gacrux" to "Mature", "Pulcherrima" to "Forward", "Achird" to "Friendly",
        "Zubenelgenubi" to "Casual", "Vindemiatrix" to "Gentle", "Sadachbia" to "Lively",
        "Sadaltager" to "Knowledgeable", "Sulafat" to "Warm"
    )

    val LIVE_VOICES = listOf(
        "Aoede", "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus",
        "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba",
        "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
        "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi",
        "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"
    ).map { SchemaPickerOption(it, it, LIVE_VOICE_DESCRIPTIONS[it]) }

    /** "My own AI" row (BYO model, WS-I): local Ollama / self-hosted Hermes /
     *  any OpenAI-compatible endpoint. Selecting it stores ai.model='local';
     *  URL + model name are configured below the picker (the API key, if any,
     *  is managed in the Dashie Console — never entered on-device). */
    val LOCAL_MODEL_OPTION = SchemaPickerOption("local", "My own AI (local / self-hosted)",
        "Ollama, Hermes, or any OpenAI-compatible endpoint — free")

    fun aiModelOptions(
        cloudAvailable: Boolean = false,
        preset: String = "",
        engines: List<VoicePreferences.LocalEngine> = emptyList()
    ): List<SchemaPickerOption> {
        return aiModelOptionsImpl(cloudAvailable, preset, engines)
    }

    /** The own-AI rows: one per saved LLM engine, else the generic "My own AI" row. */
    private fun localAiOptions(engines: List<VoicePreferences.LocalEngine>): List<SchemaPickerOption> =
        engineOptions(engines, "llm").ifEmpty { listOf(LOCAL_MODEL_OPTION) }

    // Built from the shared model catalog (AiModelCatalog.kt is GENERATED from
    // js/ai/ai-models-catalog.js — run scripts/gen-android-ai-models.mjs to
    // update). Preset shaping mirrors the console (§6): Cloud prepends the
    // Live group; Local shows only the own-AI row; the "home_assistant"
    // conversation-agent sentinel stays for the legacy/HA path.
    private fun aiModelOptionsImpl(
        hasAccount: Boolean,
        preset: String,
        engines: List<VoicePreferences.LocalEngine> = emptyList()
    ): List<SchemaPickerOption> {
        // Local preset offers Home Assistant as a first-class AI (punch #2b, John 2026-07-21):
        // when HA is the selected model it's the de-facto AI — the "AI Tools & Settings" section
        // auto-hides via the schema's cloudAiSelected predicate (ai.model != home_assistant). HA
        // leads, then the own-AI row(s). Kotlin-forward — the web/Console don't offer HA-in-Local
        // (recorded divergence, JS_KOTLIN_CONTRACTS #43).
        if (preset == "local") return listOf(
            SchemaPickerOption("home_assistant", "Home Assistant", "Uses your HA conversation agent")
        ) + localAiOptions(engines)
        val options = mutableListOf<SchemaPickerOption>()
        if (preset == "cloud") options.addAll(LIVE_MODELS.map {
            SchemaPickerOption(it.value, it.label, it.sublabel, enabled = hasAccount)
        })
        options.add(SchemaPickerOption("home_assistant", "Home Assistant",
            "Uses your HA conversation agent"))
        AiModelCatalog.MODELS.forEach { m ->
            options.add(SchemaPickerOption(m.id, m.label, m.description, enabled = hasAccount))
        }
        // Hybrid means local VOICE — the brain may still be your own box, and the console offers
        // the own-AI row under Hybrid, so the tablet must too. Omitting it meant a Hybrid user on
        // ai.model='local' matched NO option, and the picker fell back to rendering the raw stored
        // value ("local") instead of a label (John's tablet, 2026-07-16). Appended last so the
        // positional header math in aiModelSectionHeaders stays valid.
        if (preset == "hybrid" || preset.isEmpty()) options.addAll(localAiOptions(engines))
        return options
    }

    // Section headers: label → index of the first option in that group. Index
    // math accounts for the preset-shaped prefix (Live group in Cloud).
    fun aiModelSectionHeaders(preset: String = ""): List<Pair<String, Int>> {
        if (preset == "local") return emptyList()
        val headers = mutableListOf<Pair<String, Int>>()
        var offset = 0
        if (preset == "cloud") {
            headers.add("Live · realtime conversation" to 0)
            offset = LIVE_MODELS.size
        }
        headers.add("Home Assistant" to offset)
        var lastProvider: String? = null
        AiModelCatalog.MODELS.forEachIndexed { i, m ->
            if (m.provider != lastProvider) {
                headers.add(m.providerLabel to (i + offset + 1)) // +1 for the sentinel
                lastProvider = m.provider
            }
        }
        // Matches the LOCAL_MODEL_OPTION appended last in aiModelOptionsImpl — same predicate,
        // or the header lands on a row that isn't there.
        if (preset == "hybrid" || preset.isEmpty()) {
            headers.add("Your own AI" to (offset + 1 + AiModelCatalog.MODELS.size))
        }
        return headers
    }

    // ── Local engine options (the console's Local Engines registry) ──────
    //
    // A picker row per SAVED engine, so the tablet offers "Kokoro on GPU" rather than the
    // generic "Local TTS (your box)". These option values are NOT persisted: they're
    // selection tokens that the value-provider's virtual key (voice.ttsSelection /
    // sttSelection / aiModelSelection) resolves into the flat keys via the GENERATED
    // EngineResolver. That's what keeps contract #16 (lint:voice-options) honest — the
    // persisted vocabulary is unchanged, and per-user engine ids could never be a static
    // vocabulary anyway.
    //
    // Built with NAMED arguments deliberately: check-voice-options.mjs scrapes
    // `SchemaPickerOption("literal"` first-args out of this file, and a positional
    // template string would be misread as a persisted id.

    const val ENGINE_OPTION_PREFIX = "engine:"

    fun engineOptionValue(id: String) = ENGINE_OPTION_PREFIX + id

    /** The engine id inside an option token, or null if this isn't an engine token. */
    fun engineIdOf(optionValue: String): String? =
        if (optionValue.startsWith(ENGINE_OPTION_PREFIX))
            optionValue.removePrefix(ENGINE_OPTION_PREFIX).ifBlank { null }
        else null

    private fun engineOptions(
        engines: List<VoicePreferences.LocalEngine>,
        kind: String
    ): List<SchemaPickerOption> =
        engines.filter { it.kind == kind && it.name.isNotBlank() }.map { e ->
            SchemaPickerOption(
                value = engineOptionValue(e.id),
                // The URL is the useful second line: it's how you tell two boxes apart. For an
                // AI engine the model matters more, unless the engine is already named for it.
                label = e.name,
                sublabel = if (kind == "llm" && e.model.isNotBlank() &&
                        !e.name.equals(e.model, ignoreCase = true)) e.model else e.url
            )
        }

    // ── TTS Provider Options ────────────────────────────────────────────
    // ha_engine (Piper engine-direct) + local_url (own-box Kokoro) exposed on
    // device (Phase 3) — previously bridge-settable only.

    fun ttsOptions(
        hasAccount: Boolean,
        cloudAvailable: Boolean = false,
        haEnabled: Boolean = true,
        engines: List<VoicePreferences.LocalEngine> = emptyList()
    ): List<SchemaPickerOption> {
        // Order mirrors STT: cloud → On-Device (built-in device voice) → HA → "+ your box".
        val options = mutableListOf(
            SchemaPickerOption("dashie_cloud", "Cloud TTS",
                if (hasAccount) "The default Dashie voice is the most economical; personality voices are premium"
                else "Requires Dashie subscription",
                enabled = cloudAvailable)
        )
        // ⚠️ "(System)" tracks the STT row deliberately: SAME id, same device capability, same
        // provenance question, same settings page. Leaving this "(Native)" would make one id read
        // two ways one section apart — the defect class John's naming ruling was closing. His
        // ruling named the STT picker; if he wants TTS left alone, this row is the one to revert.
        options.add(SchemaPickerOption("android_voice", "On-Device (Built-in)",
            "Your device's built-in text-to-speech (usually Google's)"))
        if (haEnabled) {
            options.add(SchemaPickerOption("ha_engine", "Piper (Home Assistant)",
                "Your HA Piper voice, direct — no Assist pipeline"))
            options.add(SchemaPickerOption("va_default", "Voice Assistant Default",
                "Uses your HA voice pipeline's TTS"))
        }
        // "+ your box" at the bottom: named engines when the registry has any; else the generic row.
        val local = engineOptions(engines, "tts")
        if (local.isEmpty()) {
            options.add(SchemaPickerOption("local_url", "Local TTS (your box)",
                "Kokoro / OpenAI-compatible TTS — add one in the Dashie Console"))
        } else {
            options.addAll(local)
        }
        return options
    }

    // ── Web Search Source (console parity, 2026-07-12) ──────────────────
    // Replaces the Enable-Web-Search toggle: "None" = search off. Gemini
    // cascade models search via native Google grounding → the source reads
    // "Google"; everything else uses Dashie's managed Tavily. The picker
    // writes the derived ai.webSearchSource key (value-provider maps it to
    // ai.webSearchEnabled — provider itself is model-derived, not stored).

    fun webSearchSourceOptions(isGeminiModel: Boolean) = listOf(
        if (isGeminiModel)
            SchemaPickerOption("google", "Google",
                "Gemini searches Google directly and grounds its answer")
        else
            // ⚠️ VALUE id "dashie" is a stored wire value — NOT renamed, only the label.
            SchemaPickerOption("dashie", "Managed (Tavily)",
                "Managed web search — no setup"),
        SchemaPickerOption("none", "None",
            "Web search off — answers without searching the web")
    )

    // ── Personality Options ─────────────────────────────────────────────
    // `personalityOptions()` was DELETED 2026-08-05 (Chickadee brand-prose
    // removal): uncalled in Kotlin, and a one-row hardcoded list of the "Dashie"
    // personality. The live personality list is account-loaded over the bridge
    // (JsBridgeSettingsDataDelegate's personality callbacks) — this static row
    // never fed a picker. The persisted `personalityId` vocabulary is untouched.

    // ── Display Format Options ───────────────────────────────────────────

    val displayFormatOptions = listOf(
        SchemaPickerOption("notification", "Notification",
            "Mini card, auto-dismiss after 5 seconds"),
        SchemaPickerOption("sidebar", "Sidebar",
            "Right panel, stays until dismissed"),
        SchemaPickerOption("fullscreen", "Full Screen",
            "Full overlay, large text (best for small screens)")
    )

    // ── Response Format Options ─────────────────────────────────────────

    val responseFormatOptions = listOf(
        SchemaPickerOption("read_and_display", "Read & Display",
            "Speak and show response card"),
        SchemaPickerOption("read_only", "Read Only",
            "Speak response, no card"),
        SchemaPickerOption("display_only", "Display Only",
            "Show card, no speech"),
        SchemaPickerOption("none", "Silent",
            "No response feedback")
    )

    // ── Confirmation Tone Options ───────────────────────────────────────
    // The sound played for commands instead of a spoken response. "Disabled"
    // turns the feature off; the rest mirror the video-feed alert tones.

    val confirmationToneOptions = listOf(
        SchemaPickerOption("disabled", "Disabled", "Speak command responses (no tone)"),
        SchemaPickerOption("notify_simple_beep", "Simple Beep", ""),
        SchemaPickerOption("notify_soft_double", "Soft Double", ""),
        SchemaPickerOption("notify_bell_tap", "Bell Tap", ""),
        SchemaPickerOption("notify_chord_wash", "Chord Wash", ""),
        SchemaPickerOption("notify_pulse_alert", "Pulse Alert", ""),
        SchemaPickerOption("notify_tri_fall", "Tri Fall", ""),
        SchemaPickerOption("notify_tri_rise", "Tri Rise", ""),
        SchemaPickerOption("extra_bubble", "Bubble", ""),
        SchemaPickerOption("extra_celesta", "Celesta", ""),
        SchemaPickerOption("extra_deep_bell", "Deep Bell", ""),
        SchemaPickerOption("extra_duo_chirp", "Duo Chirp", ""),
        SchemaPickerOption("extra_wood_knock", "Wood Knock", ""),
        SchemaPickerOption("extra_xylophone_pair", "Xylophone Pair", "")
    )
}

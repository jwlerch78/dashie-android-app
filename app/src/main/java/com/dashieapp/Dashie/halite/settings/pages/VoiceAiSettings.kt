package com.dashieapp.Dashie.halite.settings.pages

import com.dashieapp.Dashie.halite.settings.SettingsActivity

internal fun SettingsActivity.createVoiceAiFragment(): com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment {
    val prefs = com.dashieapp.Dashie.halite.HalitePreferences(this)
    val subPrefs = com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences(this)
    val featurePrefs = com.dashieapp.Dashie.halite.preferences.FeatureVisibilityPreferences(this)
    // Allowlist subscription statuses that entitle the user to Dashie cloud
    // Voice/AI features. Default-deny — any new/unknown status is treated as
    // "no cloud access" until explicitly added here. Mirrors the existing
    // SubscriptionPreferences.hasActiveAccess helper.
    val isLoggedIn = subPrefs.subscriptionStatus in listOf(
        com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences.STATUS_TRIALING,
        com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences.STATUS_ACTIVE,
        com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences.STATUS_COMPLIMENTARY
    )
    // Cloud Voice/AI is a BETA feature (moved alpha→beta in the 2026-07-03 access-tier
    // restructure). Without beta+ access the Dashie Cloud handler + cloud-side STT/TTS/AI
    // options are disabled in pickers (and any pre-existing cloud selection is reverted to
    // the HA equivalent below).
    val isBeta = featurePrefs.hasBetaAccess()
    // §16.5 OR: an own beta+ account, OR the add-on holds the account and the
    // household-sharing toggle is on (cached from the integration status probe;
    // refreshed in the background after the fragment is built).
    val conn = prefs.connection
    val hasAccount = conn.hasSupabaseJwt && !conn.isSupabaseJwtExpired
    // Cloud availability is decided by CloudActivationState — the SINGLE source of
    // truth shared with the blocked-tap handler (onCloudPresetBlocked), so the row
    // gate and the tap popup can never disagree (a funded account never grays +
    // pops the useless "you're all set" dialog). Cloud is usable via a beta
    // subscription, household sharing, OR a valid account + credits / BYO key.
    fun cloudNowAvailable(): Boolean =
        com.dashieapp.Dashie.edition.EditionSeams.credits(this@createVoiceAiFragment).isCloudUsable()
    val cloudAvailable = cloudNowAvailable()
    // HA is "configured" only when the toggle is on AND a real URL is set —
    // haEnabled alone defaults true on a fresh (non-HA) device.
    val haConfigured = conn.haEnabled && conn.buildFullUrl().let {
        it.isNotEmpty() &&
            it != com.dashieapp.Dashie.halite.preferences.ConnectionPreferences.DEFAULT_HA_URL
    }
    // No HA → the Voice Assistant (HA pipeline) control method is unusable;
    // lock the stored method to Dashie Cloud so the (single-option) picker
    // and the voice pipeline agree.
    if (!haConfigured &&
        prefs.voice.voiceControlMethod != com.dashieapp.Dashie.halite.preferences.VoicePreferences.VOICE_METHOD_DASHIE_CLOUD) {
        prefs.voice.voiceControlMethod = com.dashieapp.Dashie.halite.preferences.VoicePreferences.VOICE_METHOD_DASHIE_CLOUD
    }
    // No account ⇒ the picker offers only HA Voice Assist, so a stored cloud/hybrid/local preset
    // would render as one unchecked row the user cannot select. Correct the STATE rather than the
    // display — see VoicePresetSeeder.enforceNoAccountPresetFloor. Runs before the schema is built
    // so the page renders the corrected value in the same pass.
    if (com.dashieapp.Dashie.halite.settings.schemas.VoiceAiOptions.isLockedToHaAssist(hasAccount)) {
        com.dashieapp.Dashie.halite.settings.schema.wiring.VoicePresetSeeder
            .enforceNoAccountPresetFloor(prefs.voice,
                com.dashieapp.Dashie.halite.preferences.AiPreferences(this@createVoiceAiFragment))
    }
    if (!cloudAvailable) {
        val voice = prefs.voice
        // Only fall back to the HA pipeline if HA is actually configured —
        // otherwise leave the method on Dashie Cloud (set just above).
        if (voice.voiceControlMethod == com.dashieapp.Dashie.halite.preferences.VoicePreferences.VOICE_METHOD_DASHIE_CLOUD && haConfigured) {
            voice.voiceControlMethod = com.dashieapp.Dashie.halite.preferences.VoicePreferences.VOICE_METHOD_VOICE_ASSISTANT
        }
        if (voice.sttProvider == com.dashieapp.Dashie.halite.preferences.VoicePreferences.STT_DASHIE_CLOUD) {
            voice.sttProvider = com.dashieapp.Dashie.halite.preferences.VoicePreferences.STT_VA_DEFAULT
        }
        if (voice.ttsProvider == com.dashieapp.Dashie.halite.preferences.VoicePreferences.TTS_DASHIE_CLOUD) {
            voice.ttsProvider = com.dashieapp.Dashie.halite.preferences.VoicePreferences.TTS_ANDROID_VOICE
        }
        if (voice.aiModel != com.dashieapp.Dashie.halite.preferences.VoicePreferences.AI_MODEL_HOME_ASSISTANT) {
            voice.aiModel = com.dashieapp.Dashie.halite.preferences.VoicePreferences.AI_MODEL_HOME_ASSISTANT
        }
    }
    val fragment = com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = {
            // Re-read the cached sharing flag on each (re)build so a background
            // probe result below takes effect via refresh().
            val cloudAvail = cloudNowAvailable()
            // Voice response feedback (thumbs up/down) is alpha-gated, mirroring the
            // web canAccess('voice_feedback') check.
            val feedbackPromptAvailable = isLoggedIn && isBeta
            // Effective preset — stored value, else derived from the granular
            // keys (mirrors the console; recomputed each rebuild so the seeded
            // option filtering follows a preset change live).
            val voice = prefs.voice
            val preset = voice.pipelinePreset.ifEmpty {
                val aiPrefs = com.dashieapp.Dashie.halite.preferences.AiPreferences(this)
                val localVoice = voice.ttsProvider != com.dashieapp.Dashie.halite.preferences.VoicePreferences.TTS_DASHIE_CLOUD &&
                    voice.sttProvider != com.dashieapp.Dashie.halite.preferences.VoicePreferences.STT_DASHIE_CLOUD
                when {
                    voice.voiceControlMethod == com.dashieapp.Dashie.halite.preferences.VoicePreferences.VOICE_METHOD_VOICE_ASSISTANT -> "ha_assist"
                    aiPrefs.aiModel == "local" && localVoice -> "local"
                    localVoice -> "hybrid"
                    else -> "cloud"
                }
            }
            // Chores are alpha-rollout (console FeatureGate parity).
            val choresAvailable = featurePrefs.accessLevel ==
                com.dashieapp.Dashie.halite.preferences.FeatureVisibilityPreferences.ACCESS_LEVEL_ALPHA
            // Gemini grounding → the Web Search Source picker reads "Google".
            val isGeminiModel = com.dashieapp.Dashie.halite.preferences.AiPreferences(this)
                .aiModel.startsWith("gemini-")
            // Re-read the registry on each rebuild so a console push (new/renamed/deleted
            // engine) shows up without restarting the app.
            val engines = voice.localEnginesList()
            val androidSttAvailable = android.speech.SpeechRecognizer.isRecognitionAvailable(this)
            // On-device sherpa engine bundled in this APK? (dev flavors only) — gates the
            // "On-Device (fast/accurate)" STT rows.
            val sherpaAvailable = com.dashieapp.Dashie.halite.voice.stt.SherpaEngineLoader.engineAvailable()
            // 🔴 The engine shipping no longer implies a MODEL is present (on-demand download,
            // 2026-08-04). `sherpaAvailable` above is the .so only; without this map every
            // shipping flavor would offer "On-Device" rows describing a model that is not there.
            // null ⇒ usable now (bundled or downloaded); Int ⇒ MB to download first.
            val sherpaModels = com.dashieapp.Dashie.halite.voice.stt.SttModelRegistry.FAMILIES
                .associate { family ->
                    com.dashieapp.Dashie.halite.voice.stt.SttModelRegistry.providerValue(family) to
                        if (com.dashieapp.Dashie.halite.voice.stt.SherpaEngineLoader
                                .modelUsable(this, family.id)) null else family.approxMb
                }
            // Brand-split T4: HA mode drops the voice-license (paywall) section.
            val haMode = com.dashieapp.Dashie.halite.HaEditionGate.isHaMode(this)
            com.dashieapp.Dashie.halite.settings.schemas.VoiceAiPageSchema.create(
                isLoggedIn, cloudAvail, haConfigured, feedbackPromptAvailable, preset, choresAvailable,
                isGeminiModel, engines, androidSttAvailable, sherpaAvailable,
                sherpaModels = sherpaModels,
                haMode = haMode,
                // Edition capability: with no metered service the Dashie-Cloud rows are
                // omitted outright, not shown disabled with an add-credits explainer.
                offersMeteredCloud = com.dashieapp.Dashie.edition.EditionSeams
                    .credits(this).hasMeteredService)
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            when (target) {
                "ext:wake_word" -> {
                    showFragment(
                        com.dashieapp.Dashie.halite.settings.fragments.WakeWordPickerFragment(),
                        "wake_word"
                    )
                    true
                }
                "ext:ha_pipeline" -> {
                    showFragment(
                        com.dashieapp.Dashie.halite.settings.fragments.PipelinePickerFragment(),
                        "ha_pipeline"
                    )
                    true
                }
                "ext:personality" -> {
                    showFragment(
                        com.dashieapp.Dashie.halite.settings.fragments.PersonalityPickerFragment(),
                        "personality_picker"
                    )
                    true
                }
                "ext:piper_voice" -> {
                    showFragment(
                        com.dashieapp.Dashie.halite.settings.fragments.PiperVoicePickerFragment(),
                        "piper_voice_picker"
                    )
                    true
                }
                "ext:kokoro_voice" -> {
                    showFragment(
                        com.dashieapp.Dashie.halite.settings.fragments.KokoroVoicePickerFragment(),
                        "kokoro_voice_picker"
                    )
                    true
                }
                "ext:voice_select" -> {
                    android.widget.Toast.makeText(
                        this@createVoiceAiFragment,
                        "Voice is set by personality selection",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    true
                }
                else -> false
            }
        }
    )

    // Anonymous-kiosk path: ask the integration whether the add-on permits
    // household Dashie Cloud, and re-render if it changed — so "Dashie Cloud"
    // un-grays without re-entering settings. Logged-in alpha users already
    // qualify via the left side of the OR, so skip the probe for them.
    if (haConfigured && !(isLoggedIn && isBeta)) {
        com.dashieapp.Dashie.halite.voice.DashieCloudCapabilityClient().refresh(
            // getHaOrigin() — clean scheme://host:port; buildFullUrl()/haUrl carry
            // the dashboard suffix (e.g. /ha-dashie) which breaks the API path.
            haUrl = conn.getHaOrigin() ?: "",
            haToken = conn.haAccessToken,
            voicePrefs = prefs.voice,
            // ANON KIOSK: pass a Context so this probe also HARD-APPLIES the account's
            // voice config (kiosk voice-config mirror), not just cache availability.
            // Without it, a kiosk configured AFTER household sharing was already turned
            // on never applied anything — no toggle-push had reached it — so it sat on
            // its own default (HA Voice Assist) while the account said Cloud.
            //
            // Gate on the REAL account credential (a per-device Supabase JWT), NOT
            // `isLoggedIn` (subscription status). A Kiosk-Real-Login kiosk holds a
            // `device_type=ha_kiosk` JWT but never populates subscriptionStatus, so
            // `isLoggedIn` was false and this passed a Context — the anon mirror applier
            // then hard-applied the add-on's 30s-stale `/voice-config` pipeline and
            // clobbered a just-made STT/TTS pick ~5s later (KioskAccountVoiceApplier).
            // A device with a real account gets its voice config via the account push,
            // so skip the mirror here exactly as VoiceSessionAccess.refreshKioskCapability
            // already does (both now gate on hasSupabaseJwt).
            context = if (!(conn.hasSupabaseJwt && !conn.isSupabaseJwtExpired))
                this@createVoiceAiFragment.applicationContext else null,
        ) { runOnUiThread { fragment.refresh() } }
    }

    // Funded account whose balance isn't cached yet on first open: cloudNowAvailable()
    // returns false → Cloud/Hybrid gray. Read the balance and re-render once it lands
    // so the presets un-gray without leaving Settings. One-shot — the listener removes
    // itself on the first credit-state change.
    if (hasAccount && !cloudAvailable) {
        com.dashieapp.Dashie.halite.voice.CreditStateHolder.addOnChanged("voiceai-cloud-gate") {
            com.dashieapp.Dashie.halite.voice.CreditStateHolder.removeOnChanged("voiceai-cloud-gate")
            runOnUiThread { fragment.refresh() }
        }
        com.dashieapp.Dashie.edition.EditionSeams.credits(this@createVoiceAiFragment).refreshBalance()
    }

    return fragment
}

/** @deprecated Use createVoiceAiFragment() — unified Voice & AI page */
internal fun SettingsActivity.createVoiceAssistantFragment() = createVoiceAiFragment()

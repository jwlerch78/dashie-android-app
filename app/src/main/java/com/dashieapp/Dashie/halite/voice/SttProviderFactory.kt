package com.dashieapp.Dashie.halite.voice

import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.halite.voice.stt.*
import com.dashieapp.Dashie.halite.voice.stt.SttProviderManager.ProviderType

/**
 * Builds the STT stack for [VoicePipelineCoordinator]: constructs and registers every provider,
 * and resolves which one leads.
 *
 * Extracted because this is *construction*, not orchestration — it reads preferences and returns
 * wired objects, touching no pipeline state. That makes the priority decision (previously a 65-line
 * `when` buried in `initialize()`) independently unit-testable.
 *
 * Provider-specific tuning constants stay with their provider config here; the comments explaining
 * WHY each value is what it is came along with them (they are hard-won: the Deepgram
 * `utterance_end_ms >= 1000` rule, the live HA-token lambdas, the buffered-provider timeouts).
 */
object SttProviderFactory {

    private const val TAG = "VoicePipeline"

    // End-of-speech silence for the bundled on-device sherpa providers (buffered → the
    // provider's OWN VAD decides when to decode). 1200ms matches the coordinator's tested
    // backup-VAD value (900→1200, John 2026-07-06: 900 cut off beat-long mid-sentence pauses),
    // vs the 1500ms the HA/own-box POST providers use — those are network-latency-dominated so
    // the extra patience is invisible; sherpa decodes locally in ~0.3s, so the silence wait IS
    // the perceived latency. John 2026-07-28: 1500 felt "a little sluggish vs SpeechRecognizer".
    private const val SHERPA_ENDPOINT_SILENCE_MS = 1200L

    /** The constructed STT stack. [credentialProvider] is returned so the coordinator can keep it
     *  alive — it holds the prewarmed session token the first utterance needs. */
    data class Providers(
        val manager: SttProviderManager,
        val credentialProvider: SttCredentialProvider,
    )

    /** The resolved lead/fallback order plus the flags the coordinator reports on. */
    data class Priority(
        val order: List<ProviderType>,
        val usingCloudStt: Boolean,
        val description: String,
        /**
         * A cloud STT selection was IGNORED because the mode is no-cloud (HA Voice Assist).
         * Kept as data rather than logged here so this decision stays a pure function (the
         * priority tests run on plain JUnit, where android.util.Log throws). The caller owns
         * the `DROP:` WARN — see VoicePipelineCoordinator / HaVoiceService.
         */
        val coercedFromCloud: Boolean = false,
    )

    /**
     * Construct all four STT providers and register them with a fresh manager.
     *
     * NOTE the ordering contract with [resolvePriority]: callers must run
     * `VoiceSessionAccess.refreshKioskCapability` BETWEEN these two calls. That probe can
     * hard-apply the account's voice config (anon-kiosk mirror), so resolving priority before it
     * would pick the pre-sync provider.
     */
    suspend fun createProviders(
        halitePrefs: HalitePreferences,
        endpointId: String,
        /** Context + mic handoff for the Android SpeechRecognizer provider. Both null (the
         *  default) simply omits that provider — every other one is unaffected. */
        context: android.content.Context? = null,
        micHandoff: AndroidSpeechRecognizerProvider.MicHandoff? = null,
    ): Providers {
        val manager = SttProviderManager()

        // HA Assist (pipeline STT).
        val haAssistProvider = HaAssistSttProvider()
        haAssistProvider.initialize(SttConfig(
            haUrl = halitePrefs.connection.haUrl,
            haToken = halitePrefs.connection.haAccessToken,
            // LIVE read — the HA token rotates ~30 min; an init snapshot dies after one rotation.
            haTokenProvider = { halitePrefs.connection.haAccessToken },
            haPipelineId = halitePrefs.voice.voicePipelineId.takeIf { it.isNotEmpty() },
            endOfSpeechTimeoutMs = 1500
        ))
        manager.registerProvider(ProviderType.HA_ASSIST, haAssistProvider)

        // Deepgram via the edge-function proxy — no API key on device (it stays on the server).
        val deepgramProvider = DeepgramSttProvider()
        deepgramProvider.initialize(SttConfig(
            // utterance_end_ms — Deepgram REJECTS anything < 1000 with HTTP 400 (Bad
            // Request), which silently dropped us to the slow HA-Assist fallback (the
            // "VAD takes 3s" bug). Keep >= 1000. End-of-speech snappiness comes from
            // endpointing=400 (edge fn) + the coordinator VAD (900ms) + Finalize, NOT
            // from this backup signal.
            endOfSpeechTimeoutMs = 1000
        ))
        // STT credential: logged-in → account JWT; anonymous kiosk → minted /session token
        // (prewarmed so the first utterance has a valid Bearer). Falls back to the anon key
        // when no credential is available yet. Build plan §3.2 (WS2).
        val sttConn = halitePrefs.connection
        val credentialProvider = SttCredentialProvider(
            endpointId = endpointId,   // stable per-device id — NOT Build.MODEL
            haOrigin = { sttConn.getHaOrigin() },
            haToken = { sttConn.haAccessToken },
            accountBearer = { if (sttConn.hasSupabaseJwt && !sttConn.isSupabaseJwtExpired) sttConn.supabaseJwt else null },
        ).also { it.prewarm() }
        deepgramProvider.credentialProvider = credentialProvider::currentBearer
        manager.registerProvider(ProviderType.DEEPGRAM, deepgramProvider)
        Log.i(TAG, "Deepgram STT provider configured (via edge function proxy)")

        // Local-voice STT (build plan 20260708 §4.2): engine-direct HA Whisper +
        // own-box OpenAI-compatible Whisper. Both capture via VAD then POST once.
        val haEngineStt = HaSttEngineDirectProvider()
        haEngineStt.initialize(SttConfig(
            haUrl = halitePrefs.connection.getHaOrigin(),
            haToken = halitePrefs.connection.haAccessToken,
            haTokenProvider = { halitePrefs.connection.haAccessToken },   // live (see HA_ASSIST above)
            haSttEngineId = halitePrefs.voice.haSttEngineId.takeIf { it.isNotEmpty() },
            endOfSpeechTimeoutMs = 1500
        ))
        manager.registerProvider(ProviderType.HA_ENGINE, haEngineStt)

        val localWhisperStt = LocalWhisperSttProvider()
        localWhisperStt.initialize(SttConfig(
            localSttUrl = halitePrefs.voice.localSttUrl.takeIf { it.isNotEmpty() },
            endOfSpeechTimeoutMs = 1500
        ))
        manager.registerProvider(ProviderType.LOCAL_WHISPER, localWhisperStt)

        // Android SpeechRecognizer — the free STT rung for Google-services devices with no HA
        // and no own-box Whisper (WS-D.1). Registered only when a mic handoff is supplied,
        // because it captures its OWN audio and would otherwise fight the shared capture for
        // the microphone. isAvailable() reports false on Fire OS / Lineage, which is what keeps
        // the $0 ladder's "genuinely nothing free" case honest.
        if (micHandoff != null && context != null) {
            val androidStt = AndroidSpeechRecognizerProvider(context, micHandoff)
            androidStt.initialize(SttConfig(endOfSpeechTimeoutMs = 1500))
            manager.registerProvider(ProviderType.ANDROID_NATIVE, androidStt)
            Log.i(TAG, "Android SpeechRecognizer provider registered (available=${androidStt.isAvailable()})")
        }

        // Bundled sherpa-onnx on-device STT (build-plan 20260728_SHERPA_STT_INTEGRATION_PLAN).
        // Registered only when the dev APK actually bundles the engine — on any other build
        // isAvailable() is false and the priority chain falls through, same stance as
        // ANDROID_NATIVE on Fire OS. Consumes shared-capture PCM (no mic handoff needed).
        if (context != null && SherpaEngineLoader.engineAvailable()) {
            val tiny = SherpaMoonshineSttProvider(context,
                SherpaEngineLoader.MODEL_MOONSHINE_TINY,
                VoicePreferences.STT_SHERPA_MOONSHINE_TINY, "On-Device (Moonshine Tiny)")
            tiny.initialize(SttConfig(endOfSpeechTimeoutMs = SHERPA_ENDPOINT_SILENCE_MS))
            manager.registerProvider(ProviderType.SHERPA_MOONSHINE_TINY, tiny)
            val base = SherpaMoonshineSttProvider(context,
                SherpaEngineLoader.MODEL_MOONSHINE_BASE,
                VoicePreferences.STT_SHERPA_MOONSHINE_BASE, "On-Device (Moonshine Base)")
            base.initialize(SttConfig(endOfSpeechTimeoutMs = SHERPA_ENDPOINT_SILENCE_MS))
            manager.registerProvider(ProviderType.SHERPA_MOONSHINE_BASE, base)
            Log.i(TAG, "sherpa STT providers registered (tiny=${tiny.isAvailable()} base=${base.isAvailable()})")
        }

        return Providers(manager, credentialProvider)
    }

    // ── Wake-word tail skip ───────────────────────────────────────────────────
    // Samples to skip PAST the wake-word detection point before feeding STT, so the tail of
    // "Hey Dashie" doesn't bleed into the command. Shared (not mirrored) because both the cascade
    // and HA Voice Assist's local STT stage feed the same providers from the same buffer.
    //
    // ⚠️ THE NUMBERS BELOW ARE NOT THE NET SKIP. The caller starts from `bufferPosition`, which
    // both detectors have ALREADY rewound by a 100 ms pre-roll (MicroWakeWordDetector:437,
    // EdgeImpulseDetector:414). So the audio actually discarded PAST the detection instant is:
    //     streaming   (1600)  →  −100 + 100 =    0 ms   (the wake tail after detection is KEPT)
    //     whole-clip  (4480)  →  −100 + 280 =  180 ms
    // Quote 0 / 180, not 100 / 280, when reasoning about what a command loses.

    /**
     * ~100ms @ 16kHz — for STREAMING sockets only (Deepgram). A streaming decoder emits partials
     * as audio arrives and revises them, so a fragment of the wake tail is corrected away rather
     * than committed.
     *
     * Net 0 ms past detection, so this engine keeps any wake tail that follows the detection
     * instant and relies on `CascadeDialogSupport.stripWakePrefix` to remove it as TEXT. That is
     * why it does not clip command heads.
     */
    private const val TAIL_SKIP_SAMPLES = 1600L

    /**
     * ~280ms @ 16kHz for providers whose DECODER SEES THE WHOLE CLIP AT ONCE. Such a decoder has
     * no chance to revise: an early wake detection leaks the "…shie" tail into the transcript
     * ("she what time…", 2026-07-13).
     *
     * ⚠️ **What the 180 ms net cut costs, stated as narrowly as the evidence allows**
     * (first measured, then retracted and re-scoped, on a Samsung tablet):
     *  - Against **zero-gap SYNTHETIC speech** (`say -r 175`, wake running straight into the
     *    command with a gap no human produces) the cut takes the head of the first word:
     *    *"Hey Dashie what time is it"* → **"Time is it."** (2/3).
     *  - Against **human continuous speech measured on the same device** it does **not**:
     *    John, speaking with no pause after the wake word, KEEPS his first word (*"Turn on the
     *    string lights."* decodes complete). n = **1 speaker**, Samsung only; the Fire has had
     *    no human no-pause A/B.
     *
     * 🔻 **An earlier revision of this KDoc said the "users pause after the wake word" premise was
     * FALSIFIED. That is WITHDRAWN** — the finding it rested on was retracted as a rig artifact
     * (the ruling, 2026-08-23: *"i think that was a rig artifact… i think we tuned it right —
     * at least for the samsung"*). The premise is not established either; what is measured is the
     * pair above. Do not re-promote the synthetic result to a statement about users.
     *
     * ⚠️ **This is still one trade with two sides, and the sides were measured on different
     * stimuli.** 2026-07-13 widened the skip on measured wake-tail leakage. Anything that narrows
     * it must re-measure the OTHER side, because the leak this prevents is not merely the literal
     * "…shie" text (that IS reachable by `stripWakePrefix`) but the HALLUCINATION class a
     * Whisper-family decoder produces from wake audio, which matches no text pattern at all
     * (*"So, here's the key."*).
     *
     * 📌 **Ship posture 2026-08-23 (John): keep it.** Do not tune this number without both sides
     * on a rig — and with a stimulus whose wake→command transition a human actually produces.
     */
    private const val TAIL_SKIP_SAMPLES_BUFFERED = 4480L

    /**
     * Provider values whose decoder receives the complete utterance in one shot.
     *
     * 🔴 THE CRITERION IS THE DECODER, NOT THE TRANSPORT — and getting that backwards is what
     * this set exists to stop repeating (, Fire, 2026-08-20). `sherpa_moonshine_*`
     * used to fall through to the streaming skip because it is "on-device", and the pinning test
     * justified it as *"endpoints itself"*. It does endpoint itself — with silero — but
     * endpointing decides where speech ENDS and says nothing about audio at the START. sherpa is
     * a [com.dashieapp.Dashie.halite.voice.stt.BufferedPostSttProvider]: `transcribe()` takes the
     * whole PCM buffer and decodes it offline, exactly like the Whisper engines two lines up. With
     * only 100ms skipped, the retained wake tail reached a Whisper-family decoder, which rendered
     * it as fluent English — one turn opened `"So, here's the key."`, a sentence nobody said.
     * That class is unreachable by `CascadeDialogSupport.WAKE_PREFIX`, which matches wake-word
     * TEXT; an arbitrary hallucination matches nothing and passes straight through.
     *
     * `va_default` is here on the same criterion even though OUR transport is a socket: HA's own
     * Whisper buffers the clip, so the decoder that can be fooled is still a whole-clip one.
     *
     * ⚠️ `android_voice` is deliberately absent, and it is inert for a provider that actually
     * runs — `AndroidSpeechRecognizerProvider` captures from its own mic, so no shared-buffer
     * position is applied to it. That fact now lives on [SttProvider.ownsMic] rather than in this
     * comment; it was a hand-mirror and the two shared-buffer consumers never read it.
     *
     * 🔴 **BUT `android_voice` REACHING THIS FUNCTION AT ALL WAS THE BUG (2026-08-24).** The
     * callers passed the user's PREFERENCE, not the provider that runs. In HA Voice Assist mode
     * `ANDROID_NATIVE` was never registered (`HaLocalSttStage` passed `micHandoff = null`), so the
     * chain fell to `HA_ENGINE` — HA's `faster_whisper`, a whole-clip decoder — while the skip was
     * computed from `android_voice` and came back **1600 (net 0 ms)**. Every turn in that
     * configuration fed a Whisper-family decoder the retained wake tail: precisely the
     * hallucination class documented above. Resolve the PROVIDER first and use
     * [wakeTailSkipSamples] (ProviderType) — never the raw preference.
     *
     * 📌 This is a hand-mirror of a property the type hierarchy already states, kept because
     * [wakeTailSkipSamples] takes primitives so it stays unit-testable without Android. Add a
     * `BufferedPostSttProvider` subclass and its provider value belongs here — `SttProviderPriorityTest`
     * pins the criterion, in those words, so the next reader classifies by decoder and not by
     * where the engine runs.
     */
    private val WHOLE_CLIP_DECODERS: Set<String> = setOf(
        VoicePreferences.STT_HA_ENGINE,          // HaSttEngineDirectProvider  : BufferedPostSttProvider
        VoicePreferences.STT_LOCAL_URL,          // LocalWhisperSttProvider    : BufferedPostSttProvider
        VoicePreferences.STT_VA_DEFAULT,         // HA's pipeline buffers the clip on its side
        VoicePreferences.STT_SHERPA_MOONSHINE_BASE,  // SherpaMoonshineSttProvider : BufferedPostSttProvider
        VoicePreferences.STT_SHERPA_MOONSHINE_TINY,  // ditto (retired from the offering, still stored)
    )

    /** Tail skip for [sttProvider]. Pinned by SttProviderPriorityTest.
     *
     *  ⚠️ Takes a SETTING VALUE, so it answers "what would this *preference* need?". Callers with
     *  a live provider stack must use the [ProviderType] overload instead — see the 🔴 note in
     *  [WHOLE_CLIP_DECODERS] for the defect that distinction exists to prevent. */
    fun wakeTailSkipSamples(sttProvider: String): Long =
        if (sttProvider in WHOLE_CLIP_DECODERS) TAIL_SKIP_SAMPLES_BUFFERED else TAIL_SKIP_SAMPLES

    /**
     * Tail skip for the provider that will ACTUALLY RUN — resolve the chain first
     * (`SttProviderManager.getPrimaryProviderType()`), then ask this.
     *
     * A null [type] (no provider available at all) yields the streaming default, matching the
     * unknown-value branch above: the turn is about to fail on "no STT provider available", and
     * a skip is not what decides that.
     */
    fun wakeTailSkipSamples(type: ProviderType?): Long =
        wakeTailSkipSamples(VALUE_BY_TYPE[type] ?: "")

    /** Test seam for the completeness gate on [VALUE_BY_TYPE] — vocabulary, not a decision. */
    internal fun settingValueOf(type: ProviderType): String? = VALUE_BY_TYPE[type]

    /**
     * Which STT actually leads, honoring "customize pipeline" (explicit choice) or deriving the
     * default from the voice control method.
     *
     * Takes primitives rather than [VoicePreferences] so the decision is unit-testable without
     * Android (VoicePreferences reads SharedPreferences); the overloads below bind the prefs.
     */
    fun resolveEffectiveStt(
        customizePipeline: Boolean,
        sttProvider: String,
        voiceControlMethod: String,
    ): String =
        if (customizePipeline) sttProvider else {
            when (voiceControlMethod) {
                VoicePreferences.VOICE_METHOD_DASHIE_CLOUD -> VoicePreferences.STT_DASHIE_CLOUD
                else -> VoicePreferences.STT_VA_DEFAULT  // Voice Assistant uses HA pipeline
            }
        }

    fun resolveEffectiveStt(voiceSettings: VoicePreferences): String = resolveEffectiveStt(
        voiceSettings.customizePipeline, voiceSettings.sttProvider, voiceSettings.voiceControlMethod)

    fun resolvePriority(voiceSettings: VoicePreferences): Priority = resolvePriority(
        voiceSettings.customizePipeline, migrateRetiredStt(voiceSettings), voiceSettings.voiceControlMethod)

    /**
     * Rewrite a stored selection that names a RETIRED model (moonshine-tiny, 2026-08-20) to its
     * replacement, and PERSIST it so the settings picker agrees with what the pipeline runs —
     * a picker showing nothing selected is how a retirement turns into a support ticket.
     *
     * Placed on the resolve path on purpose: it is the one point every caller passes through, and
     * it is idempotent (after the first write the read no longer matches). This cannot demote a
     * device that only has the old model — the replacement's Priority chain keeps the retired
     * provider as a fallback rung; see STT_SHERPA_MOONSHINE_BASE above.
     */
    private fun migrateRetiredStt(voiceSettings: VoicePreferences): String {
        val current = voiceSettings.sttProvider
        val migrated = SttModelRegistry.migrateRetiredProviderValue(current) ?: return current
        Log.i(TAG, "STT selection '$current' names a RETIRED model — migrating to '$migrated'")
        voiceSettings.sttProvider = migrated
        return migrated
    }

    /** Lead + fallback chain for the resolved STT. */
    fun resolvePriority(
        customizePipeline: Boolean,
        sttProvider: String,
        voiceControlMethod: String,
    ): Priority {
        val effectiveStt = resolveEffectiveStt(customizePipeline, sttProvider, voiceControlMethod)
        // HA Voice Assist is a NO-CLOUD mode: the settings UI already hides every cloud option
        // under the ha_assist preset (VoiceAiOptions.filterByPreset drops CLOUD_VALUES), so a
        // chain that falls back to Deepgram would bill credits for a config the user was never
        // offered. Fall back to LOCAL rungs instead (2026-07-29). voiceControlMethod is a
        // sufficient discriminator: ha_assist is the only preset that seeds VOICE_ASSISTANT —
        // cloud/hybrid/local all seed DASHIE_CLOUD (VoicePresetSeeder).
        if (voiceControlMethod == VoicePreferences.VOICE_METHOD_VOICE_ASSISTANT) {
            return haVoiceAssistPriority(effectiveStt)
        }
        val cloudFallbacks = listOf(ProviderType.HA_ASSIST, ProviderType.DEEPGRAM)
        return when (effectiveStt) {
            VoicePreferences.STT_DASHIE_CLOUD -> Priority(
                chainFrom(ProviderType.DEEPGRAM, listOf(ProviderType.HA_ASSIST)),
                usingCloudStt = true,
                description = "Deepgram/Cloud (primary)${bridgeNote(ProviderType.DEEPGRAM)}, HA Assist (fallback)",
            )
            VoicePreferences.STT_VA_DEFAULT -> Priority(
                chainFrom(ProviderType.HA_ASSIST, listOf(ProviderType.DEEPGRAM)),
                usingCloudStt = false,
                description = "HA Assist/VA Default (primary)${bridgeNote(ProviderType.HA_ASSIST)}, Deepgram (fallback)",
            )
            // Engine-direct HA Whisper (no Assist pipeline). Fall back to the HA
            // pipeline, then cloud, if the engine isn't configured/reachable.
            VoicePreferences.STT_HA_ENGINE -> Priority(
                chainFrom(ProviderType.HA_ENGINE, cloudFallbacks),
                usingCloudStt = false,
                description = "HA engine-direct (primary)${bridgeNote(ProviderType.HA_ENGINE)}, HA Assist + Deepgram (fallback)",
            )
            // Own-box OpenAI-compatible Whisper. Fall back to cloud, then HA.
            VoicePreferences.STT_LOCAL_URL -> Priority(
                chainFrom(ProviderType.LOCAL_WHISPER,
                    listOf(ProviderType.DEEPGRAM, ProviderType.HA_ASSIST)),
                usingCloudStt = false,
                description = "Local Whisper (primary)${bridgeNote(ProviderType.LOCAL_WHISPER)}, Deepgram + HA Assist (fallback)",
            )
            // Android's built-in SpeechRecognizer (real provider since 2026-07-20). Falls back
            // to HA Assist then cloud where it isn't available (Fire OS / Lineage have no
            // Google services, so isAvailable() is false there and the chain does the work).
            VoicePreferences.STT_ANDROID_VOICE -> Priority(
                chainFrom(ProviderType.ANDROID_NATIVE, cloudFallbacks),
                usingCloudStt = false,
                description = "Android SpeechRecognizer (primary)${bridgeNote(ProviderType.ANDROID_NATIVE)}, HA Assist + Deepgram (fallback)",
            )
            // Bundled sherpa-onnx on-device STT. Fall back to HA then cloud when the APK
            // doesn't bundle the engine (e.g. console-selected on a prod build) — the
            // unavailability logs its own DROP-shaped warn via the provider.
            VoicePreferences.STT_SHERPA_MOONSHINE_TINY -> Priority(
                chainFrom(ProviderType.SHERPA_MOONSHINE_TINY, cloudFallbacks),
                usingCloudStt = false,
                description = "On-Device Moonshine Tiny (primary)${bridgeNote(ProviderType.SHERPA_MOONSHINE_TINY)}, HA Assist + Deepgram (fallback)",
            )
            // The retirement bridge (retired tiny, today) is injected by [chainFrom] — see
            // RETIREMENT_BRIDGES for why it is derived rather than written here.
            VoicePreferences.STT_SHERPA_MOONSHINE_BASE -> Priority(
                chainFrom(ProviderType.SHERPA_MOONSHINE_BASE, cloudFallbacks),
                usingCloudStt = false,
                description = "On-Device Moonshine Base (primary)${bridgeNote(ProviderType.SHERPA_MOONSHINE_BASE)}, HA Assist + Deepgram (fallback)",
            )
            else -> Priority(
                chainFrom(ProviderType.HA_ASSIST, listOf(ProviderType.DEEPGRAM)),
                usingCloudStt = false,
                description = "HA Assist (default)${bridgeNote(ProviderType.HA_ASSIST)}, Deepgram (fallback)",
            )
        }
    }

    /**
     * Lead + fallback chain in HA Voice Assist mode — same leads, but the chain NEVER reaches a
     * cloud provider.
     *
     * Fallback order is HA_ENGINE **before** HA_ASSIST deliberately: engine-direct Whisper
     * (`POST /api/stt/{engine}`) bypasses the Assist pipeline entirely, so it still transcribes
     * when the selected/preferred pipeline has no `stt_engine` — the exact failure that motivated
     * this ("the pipeline does not support speech-to-text", 2026-07-29). HA_ENGINE self-skips when
     * `haSttEngineId` is unset (HaSttEngineDirectProvider.isAvailable), so it is safe in every
     * chain.
     */
    private fun haVoiceAssistPriority(effectiveStt: String): Priority {
        // va_default, a STALE dashie_cloud (e.g. synced from an older console pick or a preset
        // switch), and anything unknown all lead with the HA pipeline's own STT.
        val lead = NON_CLOUD_LEAD_BY_STT[effectiveStt] ?: ProviderType.HA_ASSIST
        val order = chainFrom(lead, LOCAL_FALLBACKS)
        return Priority(
            order = order,
            usingCloudStt = false,
            description = order.joinToString(" → ") { it.name } + " (no cloud: HA Voice Assist)",
            coercedFromCloud = effectiveStt == VoicePreferences.STT_DASHIE_CLOUD,
        )
    }

    /** The no-cloud fallback rungs, in order. See [haVoiceAssistPriority] for why HA_ENGINE leads. */
    private val LOCAL_FALLBACKS = listOf(ProviderType.HA_ENGINE, ProviderType.HA_ASSIST)

    // ── The ONE chain composition ─────────────────────────────────────────────
    // 🔴 Both modes compose their chain HERE. The retirement bridge was originally written by
    // hand into the cascade `when` branch only, and haVoiceAssistPriority — a second, separate
    // composition one level deeper inside the same resolvePriority — never got it. A device with
    // tiny installed and base absent, in HA Voice Assist mode (likely the MAJORITY config on the
    // "Dashie for HA" lane), migrated correctly and then logged
    // `DROP: no local STT rung is available` with a working model sitting unused.
    // The unit test asserted the first list, so it passed while production was wrong.
    // Written-twice is the defect; one composition fed by one mapping is the fix.

    /**
     * STT setting value → the provider that leads for it, for the NO-CLOUD (HA Voice Assist) mode.
     *
     * ⚠️ `dashie_cloud` is deliberately ABSENT: in this mode a stale cloud selection is coerced to
     * the HA pipeline rather than billing credits for a config the user was never offered (see
     * [haVoiceAssistPriority]). Adding it here would silently re-open that hole.
     */
    private val NON_CLOUD_LEAD_BY_STT: Map<String, ProviderType> = mapOf(
        VoicePreferences.STT_SHERPA_MOONSHINE_TINY to ProviderType.SHERPA_MOONSHINE_TINY,
        VoicePreferences.STT_SHERPA_MOONSHINE_BASE to ProviderType.SHERPA_MOONSHINE_BASE,
        VoicePreferences.STT_HA_ENGINE to ProviderType.HA_ENGINE,
        VoicePreferences.STT_LOCAL_URL to ProviderType.LOCAL_WHISPER,
        VoicePreferences.STT_ANDROID_VOICE to ProviderType.ANDROID_NATIVE,
    )

    /**
     * [ProviderType] → its setting value. **Derived** from [NON_CLOUD_LEAD_BY_STT] for every local
     * rung, so the two cannot drift; the two cloud entries are stated here because that map omits
     * them ON PURPOSE (adding `dashie_cloud` to it would re-open the no-cloud hole its own KDoc
     * warns about) — this map has no such role, it is pure vocabulary.
     *
     * ⚠️ Declared AFTER [NON_CLOUD_LEAD_BY_STT] because an `object`'s property initializers run in
     * declaration order: read earlier, the derivation silently yields an EMPTY map and every
     * resolved provider then takes the streaming skip — the exact defect this map exists to fix.
     * [RETIREMENT_BRIDGES] below sits after it for the same reason.
     *
     * `SttProviderPriorityTest` asserts EVERY [ProviderType] has an entry, so adding a provider
     * type without a value fails loudly instead of silently taking the streaming skip.
     */
    private val VALUE_BY_TYPE: Map<ProviderType, String> =
        NON_CLOUD_LEAD_BY_STT.entries.associate { (value, type) -> type to value } +
            mapOf(
                ProviderType.DEEPGRAM to VoicePreferences.STT_DASHIE_CLOUD,
                ProviderType.HA_ASSIST to VoicePreferences.STT_VA_DEFAULT,
            )

    /**
     * Lead → the RETIRED providers it replaced, which ride directly behind it in every chain.
     *
     * Why bridges exist at all: migrating a tiny-selected device to base is only safe if base is
     * actually INSTALLED. On a device that has tiny downloaded and base not, a bare
     * `[BASE, …]` chain silently demotes working on-device STT to HA/cloud — the dead-selection
     * anti-pattern. With the retired model right behind its replacement, that
     * device keeps transcribing locally on the model it already has until the replacement lands,
     * then the replacement wins on its own because it is listed first. A retired model stays
     * REGISTERED as a provider (just never OFFERED), so the id still resolves.
     *
     * 📌 DERIVED from [SttModelRegistry] rather than written out, so retiring the next family
     * bridges both chains with no edit here — the mapping cannot be updated in one place and
     * missed in the other, because there is only one place.
     */
    private val RETIREMENT_BRIDGES: Map<ProviderType, List<ProviderType>> =
        SttModelRegistry.byId(SttModelRegistry.REPLACEMENT_FOR_RETIRED)
            ?.let { NON_CLOUD_LEAD_BY_STT[SttModelRegistry.providerValue(it)] }
            ?.let { replacementLead ->
                val bridges = SttModelRegistry.RETIRED_FAMILY_IDS
                    .mapNotNull { SttModelRegistry.byId(it) }
                    .mapNotNull { NON_CLOUD_LEAD_BY_STT[SttModelRegistry.providerValue(it)] }
                if (bridges.isEmpty()) emptyMap() else mapOf(replacementLead to bridges)
            }
            ?: emptyMap()

    /** [lead], its retirement bridges, then [fallbacks]. The one composition both modes use. */
    private fun chainFrom(lead: ProviderType, fallbacks: List<ProviderType>): List<ProviderType> =
        (listOf(lead) + RETIREMENT_BRIDGES[lead].orEmpty() + fallbacks).distinct()

    /** Names the bridges in a [Priority.description] so a log line can never claim a chain it
     *  doesn't have — the description is derived from the same mapping as the chain. */
    private fun bridgeNote(lead: ProviderType): String =
        RETIREMENT_BRIDGES[lead].orEmpty()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { it.name }
            ?.let { ", retired $it if still installed" }
            ?: ""
}

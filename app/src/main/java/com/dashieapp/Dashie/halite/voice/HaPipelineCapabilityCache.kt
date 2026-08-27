package com.dashieapp.Dashie.halite.voice

/**
 * What the selected (or HA-preferred) Assist pipeline can actually DO, and therefore which
 * pipeline stages Dashie should ask HA to run.
 *
 * Motivation (2026-07-29): a Fire tablet configured for HA Voice Assist + on-device STT failed
 * EVERY turn with HA's `validation-error: the pipeline does not support speech-to-text`. Dashie
 * sent no `pipeline` field, so HA used its *preferred* pipeline — which happened to be the stock
 * "Home Assistant" one, with `stt_engine: null` and `tts_engine: null`. Nothing checked first, so
 * the only symptom was a raw HA string on screen once per wake word. HA already reports every
 * pipeline's engines in `assist_pipeline/pipeline/list`; this caches that answer and turns it into
 * a decision.
 *
 * Deliberately free of Android and of HaAssistClient's socket: it is FED the list (from
 * `onPipelinesReceived`) rather than fetching it, which keeps both halves unit-testable and lets
 * the caller piggyback the request on the per-turn connection it already opens.
 */
class HaPipelineCapabilityCache(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** How long a fetched list stays trustworthy. Pipelines change rarely (a human editing HA
     *  settings), and a stale-by-minutes answer only costs one avoidable failed turn. */
    private val ttlMs: Long = 10 * 60 * 1000L,
) {

    /** The subset of [HaAssistClient.Pipeline] that decides stage routing. */
    data class Caps(
        val id: String,
        val name: String,
        val hasStt: Boolean,
        val hasTts: Boolean,
    )

    private var byId: Map<String, Caps> = emptyMap()
    private var preferred: Caps? = null
    private var fetchedAtMs: Long = 0L

    /** Feed the result of `assist_pipeline/pipeline/list`. */
    fun update(pipelines: List<HaAssistClient.Pipeline>) {
        val caps = pipelines.map {
            Caps(
                id = it.id,
                name = it.name,
                hasStt = it.sttEngine != null,
                hasTts = it.ttsEngine != null,
            )
        }
        byId = caps.associateBy { it.id }
        preferred = pipelines.firstOrNull { it.isPreferred }?.let { byId[it.id] }
        fetchedAtMs = nowMs()
    }

    /** True when [capsFor] can answer without a refetch. */
    fun isFresh(): Boolean = fetchedAtMs != 0L && (nowMs() - fetchedAtMs) < ttlMs

    /** Drop the cache — e.g. after HA reports a pipeline error, in case the config changed. */
    fun invalidate() {
        byId = emptyMap()
        preferred = null
        fetchedAtMs = 0L
    }

    /**
     * Capabilities of [pipelineId], or of HA's *preferred* pipeline when it is null/empty (which
     * is exactly what Dashie sends when the user leaves the picker on "Preferred").
     *
     * Returns null when unknown — a cold cache, or an id HA no longer has. Callers must treat null
     * as "don't block": an unknown pipeline is not a broken one, and refusing a turn on missing
     * cache data would be worse than the failure this class exists to prevent.
     */
    fun capsFor(pipelineId: String?): Caps? =
        if (pipelineId.isNullOrEmpty()) preferred else byId[pipelineId]
}

/**
 * Turns [HaPipelineCapabilityCache.Caps] + the device's own STT/TTS ability into the stage plan
 * for one HA Voice Assist turn. Pure, so every branch is unit-testable.
 */
object HaAssistStagePlanner {

    /**
     * @param startAtIntent Dashie transcribes locally and sends HA text (`start_stage=intent`);
     *   when false HA does STT itself from streamed audio (`start_stage=stt`).
     * @param endAtIntent HA stops before TTS and the device speaks the response.
     * @param error Non-null means don't start a turn at all — the config cannot work, and this
     *   string is user-facing and actionable.
     * @param coercedToLocalStt The pipeline can't do STT so the local stage was substituted —
     *   the caller logs the `DROP:` warn.
     * @param coercedToLocalTts Same for the TTS stage.
     */
    data class Plan(
        val startAtIntent: Boolean,
        val endAtIntent: Boolean,
        val error: String? = null,
        val coercedToLocalStt: Boolean = false,
        val coercedToLocalTts: Boolean = false,
    )

    /**
     * @param caps capabilities of the pipeline about to be used, or null if unknown.
     * @param sttIsLocalByChoice the user's effective STT is NOT the HA pipeline's own
     *   (`SttProviderFactory.resolveEffectiveStt(...) != STT_VA_DEFAULT`).
     * @param localSttAvailable at least one non-HA-Assist STT provider is usable on this device.
     * @param prefersLocalTts the user's TTS choice is a device/engine-direct one, not `va_default`.
     */
    fun plan(
        caps: HaPipelineCapabilityCache.Caps?,
        sttIsLocalByChoice: Boolean,
        localSttAvailable: Boolean,
        prefersLocalTts: Boolean,
    ): Plan {
        // The TTS half is independent of how STT resolves: a pipeline with no tts_engine
        // hard-fails the same way ("does not support text-to-speech" — see LocalVoiceSwitch's
        // 2026-07-22 note), so fall back to the device voice rather than dropping the turn.
        val pipelineCantTts = caps != null && !caps.hasTts
        val endAtIntent = prefersLocalTts || pipelineCantTts

        // 1. The user asked for local STT and we can do it → HA starts at intent. This is the
        //    fix for "stt_provider=sherpa_moonshine_tiny was silently ignored".
        if (sttIsLocalByChoice && localSttAvailable) {
            return Plan(
                startAtIntent = true,
                endAtIntent = endAtIntent,
                coercedToLocalTts = pipelineCantTts && !prefersLocalTts,
            )
        }

        // 2. HA's pipeline is meant to do STT. If we know it can't, substitute the local stage
        //    when possible; otherwise refuse with something the user can act on.
        if (caps != null && !caps.hasStt) {
            if (localSttAvailable) {
                return Plan(
                    startAtIntent = true,
                    endAtIntent = endAtIntent,
                    coercedToLocalStt = true,
                    coercedToLocalTts = pipelineCantTts && !prefersLocalTts,
                )
            }
            return Plan(
                startAtIntent = false,
                endAtIntent = endAtIntent,
                error = "The \"${caps.name}\" pipeline has no speech-to-text. " +
                    "Pick a different pipeline in Settings → Voice & AI → Voice Pipeline.",
            )
        }

        // 3. Unknown (cold cache) or a pipeline that can hear: stream audio, as before.
        return Plan(
            startAtIntent = false,
            endAtIntent = endAtIntent,
            coercedToLocalTts = pipelineCantTts && !prefersLocalTts,
        )
    }
}

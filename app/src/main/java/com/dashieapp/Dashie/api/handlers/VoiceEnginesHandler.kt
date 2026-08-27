package com.dashieapp.Dashie.api.handlers

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.halite.voice.stt.SherpaEngineLoader
import com.dashieapp.Dashie.halite.voice.stt.SttModelRegistry
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Voice-engine DETECTION probe — what this device believes is available, separately from
 * whether a turn works.
 *
 *   GET /?cmd=voiceEngines
 *
 * ## Why this exists
 * Phase D of `.reference/build-plans/20260729_DEVICE_VALIDATION_PLAN.md`. When a local-engine
 * turn fails, the failure is ambiguous between "the engine was never detected" and "it was
 * detected but broke". Those have completely different fixes, and the sherpa picker rows are
 * *gated on* `SherpaEngineLoader.engineAvailable()`, so non-detection is a real failure mode
 * rather than a hypothetical. Nothing on the device could answer the question before this:
 * `listSettings` returns 23 Fully-Kiosk-compat keys and none of them are voice.
 *
 * ## Deliberately a pure READ
 * This reports state; it sets nothing. That matters for where it lives: the rest of
 * `setStringSetting` is a Fully Kiosk compatibility surface, and voice config is
 * account-authoritative (SettingsSyncNotifier classifies `stt_provider`/`tts_provider`/
 * `pipeline_preset` as "account-level — user_settings.voice.*"). A local HTTP *setter* for
 * those would be a fifth writer into a synced lane — the one-sided-write class the
 * 2026-07-04 settings audit spent a phase closing, and which named this very API as one of
 * the ~23 offenders. A reader has none of that hazard.
 *
 * ## What "available" means per row — and what it does NOT mean
 * - `sherpa.engine`  — `libsherpa-onnx-jni.so` loads. Flavor-gated: absent on non-bundled builds.
 * - `sherpa.models`  — the model's asset dir is bundled (a cheap `list()` probe).
 * - `ha.*Engine`     — the engine id the device has CACHED from the integration. A non-empty
 *                      value means "configured", NOT "reachable right now".
 * - `yourBox.*Url`   — the configured URL. Again configured ≠ reachable; this endpoint does
 *                      no network I/O, deliberately, so it can't hang a sweep or make its
 *                      answer depend on transient Wi-Fi.
 *
 * Keeping reachability out is the point: a probe that sometimes blocks for 10s is a probe
 * people stop running. Reachability belongs in the turn itself, where a failure is real.
 */
class VoiceEnginesHandler(private val context: Context) {

    companion object { private const val TAG = "VoiceEngines" }

    fun handleVoiceEngines(): NanoHTTPD.Response {
        Log.i(TAG, "API: voiceEngines — reporting local engine detection")
        val prefs = VoicePreferences(context)

        val sherpaEngine = try { SherpaEngineLoader.engineAvailable() } catch (e: Throwable) {
            Log.w(TAG, "sherpa engineAvailable() threw: ${e.message}"); false
        }
        // ENUMERATE what is bundled rather than probing a hardcoded id list. The first version
        // asked about moonshine-tiny/base only and would have silently omitted anything else —
        // it already missed `zipf20m`, which shipped in the APK at the time. (zipf20m was
        // deleted 2026-08-04 as dead payload, so today the two lists would agree — which is
        // exactly why the rule is worth keeping written down rather than inferred from the
        // current asset set.) A detection probe whose answer depends on the author remembering
        // to update a list is stale by construction.
        //
        // Enumeration lives on the LOADER (bundledModels), not here: spelling "models/stt" in
        // this file duplicated a private constant, so changing ASSET_ROOT would have left the
        // probe reporting every model absent while the models were fine.
        val models = JSONObject()
        for (id in SherpaEngineLoader.bundledModels(context)) models.put(id, true)
        // 🔴 DOWNLOADED models are invisible to the enumeration above — it lists APK ASSETS, and a
        // downloaded family lives in filesDir. Caught on the Fire 2026-08-04: immediately after an
        // install this probe still answered `moonshine-tiny: false` while the loader, the provider
        // and the pipeline all had it (`sherpa STT providers registered (tiny=true base=false)`).
        //
        // That is the THIRD instance of one bug in this block — see the two notes above and below,
        // both "the probe re-derived what the loader already knows". So it now asks the loader's
        // own gate, [SherpaEngineLoader.modelUsable]: the same predicate the STT lane gates on,
        // which answers bundled-OR-installed. A detection probe that disagrees with the loader is
        // worse than no probe — it makes a working feature look broken.
        //
        // Registry families are answered explicitly (true or false) so a missing model reads as
        // `false` rather than merely being absent from the object.
        for (family in SttModelRegistry.FAMILIES) {
            models.put(family.id, SherpaEngineLoader.modelUsable(context, family.id))
        }

        val payload = JSONObject().apply {
            put("sherpa", JSONObject().apply {
                put("engine", sherpaEngine)
                put("models", models)
                // The silero VAD asset drives sherpa's endpointing; absence silently falls back
                // to energy VAD, which is a behaviour difference the sweep should be able to see
                // rather than infer from a latency number.
                //
                // Calls the loader's OWN guard (sileroVadAvailable) rather than re-deriving it.
                // The first version used modelAvailable("silero_vad"), which lists a directory
                // — silero is a FILE directly under models/stt — so it reported false on both
                // tablets where the VAD is actually present. Caught before it was reported as
                // a finding; sharing the function is what stops it recurring.
                put("sileroVad", try { SherpaEngineLoader.sileroVadAvailable(context) } catch (e: Throwable) { false })
            })
            put("ha", JSONObject().apply {
                put("sttEngineId", prefs.haSttEngineId)
                put("ttsEngineId", prefs.haTtsEngineId)
                put("ttsVoiceId", prefs.haTtsVoiceId)
                put("configured", prefs.haSttEngineId.isNotEmpty() || prefs.haTtsEngineId.isNotEmpty())
            })
            put("yourBox", JSONObject().apply {
                put("sttUrl", prefs.localSttUrl)
                put("ttsUrl", prefs.localTtsUrl)
                put("ttsVoiceId", prefs.localTtsVoiceId)
                put("configured", prefs.localSttUrl.isNotEmpty() || prefs.localTtsUrl.isNotEmpty())
            })
            // The active selection, so a sweep can correlate detection against what is actually
            // selected without a second call into the prefs file.
            put("active", JSONObject().apply {
                put("pipelinePreset", prefs.pipelinePreset)
                put("sttProvider", prefs.sttProvider)
                put("ttsProvider", prefs.ttsProvider)
            })
            put("note", "configured != reachable; this endpoint performs no network I/O")
        }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, "application/json", payload.toString(2)
        )
    }
}

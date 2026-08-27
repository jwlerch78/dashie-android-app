package com.dashieapp.Dashie.api.handlers

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.SettingsSyncNotifier
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resolved voice/AI config with per-key SOURCE — the release-build diagnostic window.
 *
 *   GET /?cmd=getVoiceConfig
 *
 * ## Why this exists (rig ask, 2026-08-21)
 * On a release artifact the raw SharedPreferences are unreadable (not debuggable → no `run-as`,
 * no root, and none of the other `?cmd=` getters exposes a voice key), so telling apart
 * "the account pushed this", "someone set it on the device", and "the add-on reported it"
 * took a log grep + a DB read + an HTTP probe — three times in one diagnostic session.
 * This is the one read that answers it: every key of the resolved voice/AI block, each with
 * the pref key it came from and WHERE that key gets its value.
 *
 * ## Deliberately a pure READ — see [VoiceEnginesHandler]'s note; the same one-sided-write
 * hazard applies here, and this endpoint does no network I/O (values are whatever the device
 * has NOW, including possibly-stale probe caches — staleness is the thing being diagnosed,
 * so the `checkedAgoMs` age is part of the payload rather than hidden by a refresh).
 *
 * ## How SOURCE stays honest (seam note)
 * The coarse split is NOT hand-maintained here: every row's declared source class is
 * cross-checked at runtime against [SettingsSyncNotifier]'s own structures
 * (`PREF_KEY_TO_CATEGORY` = device-synced; `SYNC_EXEMPT` = everything else), and any
 * disagreement is emitted in a `sourceCrossCheck` array in the payload itself plus a
 * grep-able WARN — so if a key is ever reclassified in the notifier, this endpoint says so
 * out loud instead of drifting. The finer labels within "exempt" (account-pushed vs
 * device-local vs add-on-reported) mirror the notifier's per-key reason annotations, which
 * are comments there; the cross-check bounds how wrong they can silently be.
 */
class VoiceConfigHandler(private val context: Context) {

    companion object { private const val TAG = "VoiceConfig" }

    /** Source classes. The string before " (" must be one of: device-synced, account-pushed,
     *  device-local, add-on-reported, derived — the cross-check keys off it. */
    private data class Row(val name: String, val prefKey: String?, val source: String, val value: Any?)

    fun handleGetVoiceConfig(): NanoHTTPD.Response {
        Log.i(TAG, "API: getVoiceConfig — reporting resolved voice/AI config with per-key source")
        val prefs = HalitePreferences(context)
        val v = prefs.voice
        val ai = com.dashieapp.Dashie.halite.preferences.AiPreferences(context)
        val now = System.currentTimeMillis()

        val accountRoundTrip = "account-pushed (user_settings round-trip via ACTION_AI_VOICE_SETTINGS_CHANGED)"
        val consolePush = "account-pushed (console→native setVoiceSettings; never syncs up from the device)"
        val probeCache = "add-on-reported (probe cache from /api/dashie/voice/status; box-local, never synced)"
        val deviceLocal = "device-local (never leaves the device)"

        val rows = listOf(
            Row("voiceEnabled", "voice_enabled", "device-synced", v.voiceEnabled),
            Row("pipelinePreset", "pipeline_preset", accountRoundTrip, v.pipelinePreset),
            Row("controlMethod", "voice_control_method", accountRoundTrip, v.voiceControlMethod),
            Row("sttProvider", "stt_provider", accountRoundTrip, v.sttProvider),
            Row("ttsProvider", "tts_provider", accountRoundTrip, v.ttsProvider),
            Row("aiModel", "ai_model", accountRoundTrip, v.aiModel),
            Row("personalityId", "ai_personality_id", "device-synced", ai.personalityId),
            Row("voicePipelineMode", "voice_pipeline_mode", deviceLocal, v.voicePipelineMode),
            Row("agentModeRaw", "agent_mode", "$deviceLocal — seeded by VoicePresetSeeder/console", v.agentMode),
            Row("agentMode", null, "derived (conversationEngineMode from agent_mode/legacy keys)", v.conversationEngineMode),
            Row("conversationModel", "conversation_model", "$deviceLocal — seeded by VoicePresetSeeder/console", v.conversationModel),
            Row("conversationAlways", "conversation_always", deviceLocal, v.conversationAlways),
            Row("useLocalBrain", "use_local_brain", "$deviceLocal (dev toggle; outranks the reported route)", v.useLocalBrain),
            Row("brainRoute", "brain_route_cached", probeCache, v.brainRoute),
            Row("brainRouteCheckedAgoMs", "brain_route_checked_at", probeCache,
                if (v.brainRouteCheckedAtMs == 0L) "never (or invalidated)" else now - v.brainRouteCheckedAtMs),
            Row("localSttUrl", "local_stt_url", consolePush, v.localSttUrl),
            Row("localTtsUrl", "local_tts_url", consolePush, v.localTtsUrl),
            Row("localLlmUrl", "local_llm_url", consolePush, v.localLlmUrl),
            Row("localLlmModel", "local_llm_model", consolePush, v.localLlmModel),
            Row("haSttEngine", "ha_stt_engine", consolePush, v.haSttEngineId),
            Row("haTtsEngine", "ha_tts_engine", consolePush, v.haTtsEngineId),
            Row("haTtsVoice", "ha_tts_voice", consolePush, v.haTtsVoiceId),
        )

        val resolved = JSONObject()
        val source = JSONObject()
        val disagreements = JSONArray()
        for (r in rows) {
            resolved.put(r.name, r.value ?: JSONObject.NULL)
            source.put(r.name, (r.prefKey?.let { "[$it] " } ?: "") + r.source)
            crossCheck(r)?.let {
                disagreements.put(it)
                Log.w(TAG, "WARN: getVoiceConfig source cross-check disagreement — $it")
            }
        }

        val body = JSONObject().apply {
            put("resolved", resolved)
            put("source", source)
            put("sourceCrossCheck", if (disagreements.length() == 0) "ok" else disagreements)
            put("note", "Pure read: values are the device's CURRENT state, including possibly-" +
                "stale probe caches — that staleness is what checkedAgoMs is for.")
        }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, "application/json", body.toString(2))
    }

    /** Null when the declared source class agrees with SettingsSyncNotifier; else a message. */
    private fun crossCheck(r: Row): String? {
        val key = r.prefKey ?: return null // derived rows have no pref key to check
        val coarse = r.source.substringBefore(" ").substringBefore("(")
        val inTable = SettingsSyncNotifier.PREF_KEY_TO_CATEGORY.containsKey(key)
        val inExempt = key in SettingsSyncNotifier.SYNC_EXEMPT
        return when {
            coarse == "device-synced" && !inTable ->
                "$key: declared device-synced but absent from PREF_KEY_TO_CATEGORY"
            coarse != "device-synced" && inTable ->
                "$key: declared '$coarse' but PREF_KEY_TO_CATEGORY classifies it device-synced " +
                    "(category '${SettingsSyncNotifier.PREF_KEY_TO_CATEGORY[key]}')"
            coarse != "device-synced" && !inExempt ->
                "$key: declared '$coarse' but not in SYNC_EXEMPT either — unclassified key"
            else -> null
        }
    }
}

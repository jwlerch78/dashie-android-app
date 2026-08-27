package com.dashieapp.Dashie.halite.voice

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.halite.preferences.AiPreferences
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.wakeword.models.WakeWordModel
import com.dashieapp.Dashie.wakeword.models.WakeWordModelManager
import org.json.JSONObject
import kotlin.reflect.KMutableProperty0

/**
 * Applies a share-account anon kiosk's effective voice config from the account,
 * carried on the `/api/dashie/voice/status` probe (kiosk voice-config mirror,
 * Phase 1 — 2026-07-13). The household's Voice & AI setup (STT/TTS providers +
 * HA engine ids + voice + model + personality + wake word) is mirrored onto the
 * kiosk's native prefs so it behaves like the account chose, instead of using
 * its own local defaults.
 *
 * Phase 1 = HARD-APPLY: the account value always wins. Phase 2 will add local
 * per-key overrides (a device value that beats the account default) — the
 * two-slot resolution model from WS-G §13.2. Until then a kiosk mirrors the
 * account exactly.
 *
 * Only ever runs on the ANON kiosk path (a logged-in device gets its config via
 * the normal account push). `apply` returns true when anything changed, so the
 * caller can reinitialize the running voice pipeline to pick it up.
 */
object KioskAccountVoiceApplier {

    private const val TAG = "KioskAccountVoice"

    private val VALID_AGENT_MODES = setOf("live", "dialog", "single")

    fun apply(context: Context, voicePrefs: VoicePreferences, json: JSONObject): Boolean {
        var changed = false
        // The add-on reports whose account it holds; carry it into the overwrite DROP so a
        // diagnosis starts from the real owner rather than from whatever email happens to be in
        // this device's prefs (see [sourceLabel]).
        sourceLabel = json.optString("account_email", "").takeIf { it.isNotEmpty() }
            ?.let { "the household account $it" } ?: "the household account (email not reported)"

        // Voice pipeline block (providers + HA engine ids + voice + control method).
        json.optJSONObject("pipeline")?.let { p ->
            changed = str(p, "sttProvider", voicePrefs::sttProvider) || changed
            changed = str(p, "ttsProvider", voicePrefs::ttsProvider) || changed
            changed = str(p, "haSttEngineId", voicePrefs::haSttEngineId) || changed
            changed = str(p, "haTtsEngineId", voicePrefs::haTtsEngineId) || changed
            changed = str(p, "haTtsVoiceId", voicePrefs::haTtsVoiceId) || changed
            changed = str(p, "controlMethod", voicePrefs::voiceControlMethod) || changed
            changed = str(p, "pipelinePreset", voicePrefs::pipelinePreset) || changed
            // Which HA entities voice can control (dashboard | assist) — so a shared-account kiosk
            // honors the account's pick. DashieNative.getVoiceEntitySource() exposes it to the kiosk
            // overlay's buildHaVoiceContext (webapp/kiosk divergence fix, 20260717).
            changed = str(p, "entitySource", voicePrefs::entitySource) || changed
            // Own-box STT/TTS endpoints. These MUST travel with the providers above: a
            // Local-preset household serves sttProvider=local_stt_url / ttsProvider=local_url,
            // and without the URLs the kiosk got dead STT (null url at pipeline init) and a
            // silent fallback to device TTS. Audit 2026-07-13, #1.
            changed = str(p, "localSttUrl", voicePrefs::localSttUrl) || changed
            changed = str(p, "localTtsUrl", voicePrefs::localTtsUrl) || changed
            changed = str(p, "localTtsVoiceId", voicePrefs::localTtsVoiceId) || changed
            // Live (realtime) model — without it a kiosk's Live turns always used
            // RealtimeConfig.DEFAULT_MODEL instead of the household's pick, and the on-demand
            // "conversation mode" trigger could never arm (conversationModeEnabled reads this).
            changed = str(p, "conversationModel", voicePrefs::conversationModel) || changed
            // MIRROR-EXEMPT: searchSource — served + forwarded, deliberately NOT applied. The
            // console's vocabulary ({dashie|google|searxng|none}) is NOT Android's
            // webSearchBackend ({disabled|searxng|…}); writing it verbatim would store a garbage
            // value in a pref that has no search-gating consumer anyway (the brain decides search
            // server-side from account.webSearchEnabled). See KIOSK_VOICE_MIRROR_DISCONNECTS §3a.
            // Either define a mapping or stop serving it — do NOT "just apply" it.
            // (`lint:kiosk-mirror` check B reads this MIRROR-EXEMPT annotation.)
            if (p.has("conversationAlways")) {
                val b = p.optBoolean("conversationAlways", voicePrefs.conversationAlways)
                if (voicePrefs.conversationAlways != b) { voicePrefs.conversationAlways = b; changed = true }
            }
            if (p.has("customizePipeline")) {
                val b = p.optBoolean("customizePipeline", voicePrefs.customizePipeline)
                if (voicePrefs.customizePipeline != b) { voicePrefs.customizePipeline = b; changed = true }
            }
            // DLG-6 "keep dialog open" (account voice.alwaysOpenDialog) — served inside the
            // pipeline block so no integration change was needed. Absent (older add-on) →
            // leave the kiosk's current value alone.
            if (p.has("alwaysOpenDialog")) {
                val b = p.optBoolean("alwaysOpenDialog", voicePrefs.alwaysOpenDialog)
                if (voicePrefs.alwaysOpenDialog != b) { voicePrefs.alwaysOpenDialog = b; changed = true }
            }
        }

        // Conversation agent mode (live|dialog|single). The probe ALSO caches this in
        // `kioskAgentMode`, but that cache loses to a locally-set `agentMode` in
        // VoiceSessionAccess.effectiveAgentMode — and the kiosk's own Voice & AI page
        // (plus VoicePresetSeeder) writes `agentMode`, so a stale local 'single' silently
        // beat a household 'dialog' (2026-07-13: dialog was off on the Fire tablet while
        // the account said dialog, and the settings toggle rendered off — it reads
        // conversationEngineMode, i.e. this key). Hard-apply it like every other mirrored
        // field: the account wins, and the settings UI now shows the truth.
        val agentMode = json.optString("agent_mode", "")
        if (agentMode in VALID_AGENT_MODES && voicePrefs.agentMode != agentMode) {
            voicePrefs.agentMode = agentMode
            changed = true
        }

        // AI model — the kiosk's brain model mirrors the account (N6 dual-write:
        // VoicePreferences.aiModel drives the pipeline, AiPreferences.aiModel is
        // the synced key both must agree).
        val model = json.optString("model", "")
        if (model.isNotEmpty() && voicePrefs.aiModel != model) {
            voicePrefs.aiModel = model
            AiPreferences(context).aiModel = model
            changed = true
        }

        // Retrieve pictures. The probe already caches this in VoicePreferences.kioskRetrievePictures
        // (what the relay actually reads on an anon kiosk — VoiceSessionAccess.effectiveRetrievePictures),
        // but the kiosk's own Voice & AI TOGGLE is bound to AiPreferences.retrievePicturesEnabled,
        // which nothing mirrored. So the account said ON, pictures WORKED, and the settings page
        // showed the toggle OFF — and flipping it there did nothing. Same UI-vs-runtime split that
        // hid the conversation-dialog bug. Hard-apply it so the page shows the truth.
        if (json.has("retrieve_pictures")) {
            val ai = AiPreferences(context)
            val b = json.optBoolean("retrieve_pictures", ai.retrievePicturesEnabled)
            if (ai.retrievePicturesEnabled != b) { ai.retrievePicturesEnabled = b; changed = true }
        }

        // Personality (WS-G default). Empty → leave the current one alone.
        val personality = json.optString("default_personality_id", "")
        if (personality.isNotEmpty()) {
            val ai = AiPreferences(context)
            if (ai.personalityId != personality) { ai.personalityId = personality; changed = true }
            // We just applied the ACCOUNT default — record the inherit state so the
            // picker/summary show "<name> (Default)" checked instantly instead of
            // waiting on the (kiosk-slow) bridge fetch. (2026-07-27: applier set the
            // value but never the flag → Default row unchecked, "forever loading".)
            if (!ai.personalityInheriting) { ai.personalityInheriting = true; changed = true }
        }

        // Voice key (WS-G default `ai.defaultVoiceKey`) — the cloud-TTS voice the
        // account chose. Empty → leave the current one alone (a voice-flexible
        // personality then uses its preferred voice, or Piper's haTtsVoiceId when
        // on local TTS). Closes the §3b applier omission (2026-07-13 handoff).
        val voiceKey = json.optString("default_voice_key", "")
        if (voiceKey.isNotEmpty()) {
            if (voicePrefs.voiceKey != voiceKey) { voicePrefs.voiceKey = voiceKey; changed = true }
            val ai = AiPreferences(context)
            if (ai.voiceKeyDefaultKey != voiceKey) { ai.voiceKeyDefaultKey = voiceKey; changed = true }
            if (!ai.voiceKeyInheriting) { ai.voiceKeyInheriting = true; changed = true }
        }

        // Wake word (WS-G default) — model-gap rule: apply only when this APK
        // bundles the model, else keep the current word (no silent degradation).
        // Takes effect on the next voice restart (WakeWordModelManager v1 scope).
        val wake = json.optString("default_wake_word", "")
        if (wake.isNotEmpty()) {
            val ai = AiPreferences(context)
            // Record the account default id regardless (drives "<name> (Default)" rows).
            if (ai.wakeWordDefaultId != wake) { ai.wakeWordDefaultId = wake; changed = true }
            if (WakeWordModel.availableModelIds().contains(wake)) {
                try {
                    val mgr = WakeWordModelManager(context)
                    if (mgr.getActiveModel().modelId != wake) { mgr.selectModelById(wake); changed = true }
                    // Following the account default (inherit flag = picker checkmark).
                    if (!ai.wakeWordInheriting) { ai.wakeWordInheriting = true; changed = true }
                } catch (e: Exception) {
                    Log.w(TAG, "wake word apply failed: ${e.message}")
                }
            }
        }

        if (changed) Log.i(TAG, "Applied account voice config to kiosk (stt=${voicePrefs.sttProvider}, tts=${voicePrefs.ttsProvider}, model=${voicePrefs.aiModel}, agent=${voicePrefs.agentMode}, keepOpen=${voicePrefs.alwaysOpenDialog})")
        return changed
    }

    /** Set a string pref from `json[key]` when present+non-empty and different. */
    /**
     * Who the incoming config belongs to, for the DROP line below. Set at the top of [apply];
     * this object is a singleton but `apply` is only ever called from the probe callback, one
     * refresh at a time.
     *
     * 📌 Naming the account is the point, not decoration. On 2026-08-24 a signed-OUT device was
     * enforcing a config that a thread attributed to the last account seen in DEVICE prefs
     * (`floridalerches`). It was actually the ADD-ON's account (`jwlerch`) actively re-serving it.
     * A diagnosis started from the wrong owner and the proposed fix — clear the device's settings
     * — could never have worked against a source that re-sends them.
     */
    private var sourceLabel: String = "the household account"

    private fun str(json: JSONObject, key: String, prop: KMutableProperty0<String>): Boolean {
        val v = json.optString(key, "")
        val current = prop.get()
        if (v.isEmpty() || current == v) return false
        // 🔴 Standing rule 2. This is a HARD-APPLY: whatever the user set on this device is about
        // to lose, silently, to an account they may not even be signed into. Until Phase 2 adds
        // local per-key overrides (see the class KDoc) that is the designed behaviour — but it must
        // not also be an INVISIBLE one. Every "my setting won't stick" report should be one grep.
        //
        // Fires only when the values actually DIFFER, so a device that already matches the
        // household stays quiet; a divergence means either the user changed it here or the account
        // changed it there, and the line says which value is being discarded either way.
        Log.w(TAG, "DROP: overwriting device setting '$key' — was '$current', now '$v' " +
            "(hard-applied from $sourceLabel). If the user set this on the device, their choice " +
            "is being discarded; local overrides are Phase 2.")
        prop.set(v)
        return true
    }
}

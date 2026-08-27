package com.dashieapp.Dashie.halite.voice

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.halite.preferences.AiPreferences
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import org.json.JSONObject

/**
 * Native application of the brain's `set_personality` action — the 1x implementation for BOTH
 * Android modes (VOICE_SINGLE_PATH Batch 3 item 11; closes postmortem 20260718 §7 / incident D:
 * on a kiosk device the switch was spoken, forwarded to a JS lane the device never loads, and
 * silently dropped).
 *
 * The brain's personalities second pass enriches the action DETERMINISTICALLY with the
 * template's voice fields (`voice_mode`, `voice_key`, `greeting_fallback` — orchestrator.ts),
 * so no client-side template lookup is needed. Persistence is prefs-only:
 *   • [AiPreferences.personalityId] (+ display name) — SettingsSyncNotifier already mirrors
 *     `ai_personality_id`/`voice_key` (aiVoice category) to the cloud, so the webapp/console
 *     learn the switch for free.
 *   • voice follows personality: `fixed` → pin [VoicePreferences.voiceKey]; `preferred` →
 *     reset to the default voice (mirrors the JS lane clearing its device pin — otherwise the
 *     device keeps speaking as e.g. Santa forever). An OLD brain without the enrichment fields
 *     applies the id/name only and leaves the voice untouched.
 *
 * Applied BEFORE the turn's confirmation line reaches TTS, so "Switching to Princess now."
 * already speaks in the NEW voice. The JS PersonalityActionHandler stays registered as the
 * web/desktop residue (contract #35 kiosk policy → KIOSK_NATIVE).
 */
object PersonalityActionApplier {

    private const val TAG = "VoicePipeline"

    /** True = the action was a personality switch and was applied natively (do NOT forward to
     *  the JS lane — forwarding too would double-apply on full mode). */
    fun tryApply(context: Context, action: JSONObject): Boolean {
        if (action.optString("category") != "personality") return false
        if (action.optString("command") != "set_personality") {
            Log.w(TAG, "DROP: unknown personality command '${action.optString("command")}' — not applied")
            return true
        }
        val params = action.optJSONObject("parameters") ?: JSONObject()
        val key = params.optString("key").trim()
        if (key.isEmpty()) {
            Log.w(TAG, "DROP: set_personality with no key — not applied")
            return true
        }
        val name = params.optString("name").ifEmpty { key }
        val voiceMode = params.optString("voice_mode")
        val voiceKey = params.optString("voice_key")

        val ai = AiPreferences(context)
        ai.personalityId = key
        ai.personalityDisplayName = name

        val voice = VoicePreferences(context)
        when {
            voiceMode == "fixed" && voiceKey.isNotEmpty() -> voice.voiceKey = voiceKey
            // preferred → CLEAR the pin to the '' inherit sentinel (mirrors the JS lane
            // removing its device pin; WS-G: '' resolves device → account default →
            // personality preferred server-side). Writing DEFAULT_VOICE_KEY here was a
            // HARD pin that stomped the user's own chosen voice (found 2026-07-19).
            voiceMode == "preferred" -> voice.voiceKey = ""
            // old brain / no enrichment → leave the voice alone
        }
        val voiceLogged = when {
            voiceMode == "preferred" -> "(cleared→default)"
            voiceKey.isNotEmpty() -> voiceKey
            else -> "(unchanged)"
        }
        Log.i(TAG, "🎭 set_personality applied natively: key=$key name=$name mode=${voiceMode.ifEmpty { "(unenriched)" }} voice=$voiceLogged")
        return true
    }
}

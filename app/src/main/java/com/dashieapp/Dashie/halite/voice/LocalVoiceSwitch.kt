package com.dashieapp.Dashie.halite.voice

import android.content.Context
import android.content.Intent
import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.AiPreferences
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.halite.settings.schema.wiring.VoicePresetSeeder

/**
 * Punch #5 — PERSIST the free/local voice config when the user picks "Use local voice" at $0,
 * replacing the old runtime-only override (DegradedVoiceMode). Unlike the runtime override, this
 * WRITES a real preset + granular engine keys and rounds them up to the account, so the console
 * and Settings reflect the switch — and there is NO auto-restore on credit return: the user stays
 * on local voice until they switch back in Settings (2026-07-21). The temporary "Not now" /
 * "Don't show again" chooser paths still use the runtime DegradedVoiceMode override.
 *
 * The free preset it applies:
 *  - HA configured → "ha_assist" (HA STT/TTS + HA conversation agent — all free; the common kiosk
 *    case, since both test kiosks follow the HA add-on's account).
 *  - else a local STT/TTS box is set → "local" (the user's own Whisper/Piper + own brain).
 *  - else null — this device has no free voice to switch to; the caller stays honest (points the
 *    user at Settings) instead of pretending it switched.
 *
 * The write reuses the exact machinery a manual preset pick uses: [VoicePresetSeeder.applyPreset]
 * seeds the granular keys, then the ACTION_AI_VOICE_SETTINGS_CHANGED broadcast rounds the change
 * up to user_settings.voice.* (the changed key uploads unconditionally; siblings diff → no
 * clobber). The caller reinitializes the pipeline so the new engines take effect immediately.
 */
object LocalVoiceSwitch {
    private const val TAG = "LocalVoiceSwitch"

    /** Persist the appropriate free preset. Returns the applied preset id, or null when this
     *  device has no free voice to switch to. Safe to call from the UI thread. */
    fun persist(context: Context): String? {
        val prefs = HalitePreferences(context)
        val voice = prefs.voice
        val preset = resolveFreePreset(voice, prefs) ?: run {
            Log.i(TAG, "💳 no free voice on this device — not persisting")
            return null
        }
        val ai = AiPreferences(context)
        voice.pipelinePreset = preset
        VoicePresetSeeder.applyPreset(voice, ai, preset)
        // The ha_assist preset seeds ttsProvider=va_default (the HA Assist pipeline's own TTS),
        // but that hard-fails when the HA pipeline has no TTS engine ("the pipeline does not
        // support text-to-speech" → the turn drops). A local-voice FALLBACK must speak reliably,
        // so swap va_default for a direct HA Piper engine (if configured) else the device's own
        // Android TTS — matching FreeVoicePlan's reliable-TTS choice (2026-07-22).
        if (voice.ttsProvider == VoicePreferences.TTS_VA_DEFAULT) {
            voice.ttsProvider = if (voice.haTtsEngineId.isNotEmpty())
                VoicePreferences.TTS_HA_ENGINE else VoicePreferences.TTS_ANDROID_VOICE
        }
        // Round-trip to the account — the same broadcast the preset picker fires (see
        // SettingsCallbackWiring.notifyPipelinePresetChanged), so the console + other devices pick
        // it up. changedKey uploads unconditionally; siblings diff, so nothing gets clobbered.
        context.sendBroadcast(
            Intent("com.dashieapp.Dashie.ACTION_AI_VOICE_SETTINGS_CHANGED").apply {
                setPackage(context.packageName)
                putExtra("changedKey", "voice.pipelinePreset")
            }
        )
        Log.i(TAG, "💳 persisted local voice → preset=$preset (no auto-restore; switch back in Settings)")
        return preset
    }

    private fun resolveFreePreset(voice: VoicePreferences, prefs: HalitePreferences): String? {
        if (!prefs.connection.getHaOrigin().isNullOrEmpty()) return "ha_assist"
        if (voice.localSttUrl.isNotEmpty() || voice.localTtsUrl.isNotEmpty()) return "local"
        // No HA and no local box → honest dead-end (the caller shows "no local voice set up" +
        // Settings). Deliberately NOT an Android-native persist: a Google-services tablet CAN do
        // Android STT+TTS, but only in the cascade path, and there's no clean non-billable,
        // no-brain persisted state for it today (Android STT is cascade-only; the only non-billable
        // cascade aiModel is `ollama`, which on a box-less device makes questions hit a dead local
        // brain). The $0 TEMP fallback still covers Android voice at runtime via FreeVoicePlan +
        // the degraded-question route; a persisted device-only mode is a deliberate future add
        // (2026-07-22). Pinned by LocalVoiceSwitchTest.
        return null
    }
}

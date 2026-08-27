package com.dashieapp.Dashie.halite.voice.stt

import android.content.Context
import android.content.Intent
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.halite.preferences.VoicePreferences

/**
 * Apply the STT selection the user reached for, the moment its model finishes installing —
 * WITHOUT needing any activity to still be alive.
 *
 * ## The bug this replaces (2026-08-18, live on the Fire)
 *
 * Selection used to be applied only by the settings screen's `onInstalled` callback, which is
 * skipped when the activity is gone — and with extraction running ~7× longer than the download,
 * the screen is almost always gone by then. The user downloaded a model *because they tapped it*,
 * and then had to come back and tap it again.
 *
 * ## What "select" means — one definition, shared
 *
 * The pref write plus the three broadcasts below are exactly what
 * `SettingsCallbackWiring` does for a hand-picked STT row (its `notifySttProviderChanged`
 * plumbing), so an auto-applied selection is indistinguishable from a hand-picked one:
 *  - `ACTION_VOICE_SETTINGS_CHANGED` — voice service picks up the change
 *  - `ACTION_VOICE_PIPELINE_REINIT` — running pipeline switches now, not at next reload
 *  - `ACTION_AI_VOICE_SETTINGS_CHANGED` (`changedKey=voice.sttProvider`) — round-trips the
 *    account-scoped change to `user_settings.voice.*` so the console sees it
 *
 * All three are plain context broadcasts and the pref is process-wide, which is what makes this
 * callable from the install worker thread with no UI anywhere.
 */
object SttAutoSelect {

    fun apply(context: Context, providerValue: String) {
        val app = context.applicationContext
        VoicePreferences(app).sttProvider = providerValue
        PersistentLog.info("STT", "auto-selected '$providerValue' after install")
        for (action in listOf(
            "com.dashieapp.Dashie.ACTION_VOICE_SETTINGS_CHANGED",
            "com.dashieapp.Dashie.ACTION_VOICE_PIPELINE_REINIT",
        )) {
            app.sendBroadcast(Intent(action).apply { setPackage(app.packageName) })
        }
        app.sendBroadcast(
            Intent("com.dashieapp.Dashie.ACTION_AI_VOICE_SETTINGS_CHANGED").apply {
                setPackage(app.packageName)
                putExtra("changedKey", "voice.sttProvider")
            }
        )
    }
}

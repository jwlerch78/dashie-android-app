package com.dashieapp.Dashie.halite.voice

import android.content.Context

/**
 * Promo/screenshot mode for the voice conversation transcript: turns build in chat
 * order (newest at the BOTTOM) and prior turns are NOT demoted (no dim/shrink/gray),
 * so a finished conversation screenshots as a top-to-bottom, full-contrast exchange.
 *
 * Local-only rig flag — lives in its own prefs file, deliberately NOT a synced
 * setting (invisible to SettingsSyncNotifier / SETTINGS_KEY_MAP / lint:settings).
 * Set/cleared per tablet over adb via DebugInjectHandler (DEBUG builds):
 *   adb shell am broadcast -a com.dashieapp.Dashie.TEST_INJECT --es event promo_mode --es value true
 *   adb shell am broadcast -a com.dashieapp.Dashie.TEST_INJECT --es event promo_mode --es value false
 */
object VoicePromoMode {
    private const val PREFS = "dashie_debug"
    private const val KEY = "voice_promo_mode"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}

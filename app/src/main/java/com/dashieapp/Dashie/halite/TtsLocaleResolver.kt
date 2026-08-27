package com.dashieapp.Dashie.halite

import android.speech.tts.TextToSpeech
import android.util.Log
import com.dashieapp.Dashie.halite.preferences.GeneralPreferences
import java.util.Locale

/**
 * Resolves the app [GeneralPreferences.language] setting to a [Locale] the given
 * TextToSpeech engine can actually speak.
 *
 * The local Android TTS engine does NOT infer language from text (unlike cloud
 * multilingual TTS) — the locale must be set explicitly, and the requested
 * language's voice data may not be installed on the device. So we resolve
 * through a fallback chain and only return a locale the engine reports as
 * available:
 *
 *   configured tag → language-only → device locale → English (US)
 *
 * Shared by [TtsManager] (at init) and the per-response re-apply in
 * HaliteVoiceController, so a changed Language setting takes effect on the next
 * spoken response without restarting TTS.
 */
object TtsLocaleResolver {
    private const val TAG = "TtsLocaleResolver"

    private fun isUsable(tts: TextToSpeech, locale: Locale): Boolean {
        return try {
            when (tts.isLanguageAvailable(locale)) {
                TextToSpeech.LANG_AVAILABLE,
                TextToSpeech.LANG_COUNTRY_AVAILABLE,
                TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> true
                else -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "isLanguageAvailable threw for $locale: ${e.message}")
            false
        }
    }

    /** Resolve [languageTag] (a BCP-47 tag or [GeneralPreferences.LANG_SYSTEM]) to a speakable locale. */
    fun resolve(tts: TextToSpeech, languageTag: String): Locale {
        val candidates = mutableListOf<Locale>()

        if (languageTag.isNotBlank() && languageTag != GeneralPreferences.LANG_SYSTEM) {
            val tagged = Locale.forLanguageTag(languageTag)
            if (tagged.language.isNotEmpty()) {
                candidates.add(tagged)                       // e.g. es-ES
                candidates.add(Locale(tagged.language))      // language-only, e.g. es
            }
        }

        val device = Locale.getDefault()
        candidates.add(device)                               // device locale
        if (device.language.isNotEmpty()) candidates.add(Locale(device.language))
        candidates.add(Locale.US)                            // final fallback

        val chosen = candidates.firstOrNull { isUsable(tts, it) } ?: Locale.US
        Log.i(TAG, "Resolved language '$languageTag' -> $chosen")
        return chosen
    }

    /**
     * Resolve and apply the language to the engine. Returns the locale applied,
     * or null if the engine rejected even the fallback (caller may then try
     * another engine).
     */
    fun apply(tts: TextToSpeech, languageTag: String): Locale? {
        val locale = resolve(tts, languageTag)
        val result = tts.setLanguage(locale)
        return if (result == TextToSpeech.LANG_NOT_SUPPORTED ||
            result == TextToSpeech.LANG_MISSING_DATA) {
            Log.w(TAG, "setLanguage($locale) rejected: $result")
            null
        } else {
            locale
        }
    }
}

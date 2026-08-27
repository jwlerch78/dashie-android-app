package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * AI-related preferences for Dashie.
 *
 * Manages settings that were previously only in JS localStorage (SettingsStore):
 * - AI enabled state, model selection
 * - Web search, picture retrieval
 * - Conversation memory settings
 * - Personality selection
 * - "Always use AI for chores" flag
 *
 * Uses the same SharedPreferences file as HalitePreferences for consistency.
 * Values are synced to JS via native-settings-listener.js.
 */
class AiPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_lite_prefs"

        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_WEB_SEARCH_ENABLED = "ai_web_search_enabled"
        private const val KEY_RETRIEVE_PICTURES_ENABLED = "ai_retrieve_pictures_enabled"
        private const val KEY_PROMPT_FOR_FEEDBACK = "ai_prompt_for_feedback"
        private const val KEY_CONVERSATION_CONTEXT_ENABLED = "ai_conversation_context_enabled"
        private const val KEY_CONVERSATION_TIMEOUT = "ai_conversation_timeout"
        private const val KEY_PERSONALITY_ID = "ai_personality_id"
        private const val KEY_PERSONALITY_DISPLAY_NAME = "ai_personality_display_name"
        private const val KEY_PERSONALITY_INHERITING = "ai_personality_inheriting"
        private const val KEY_WAKE_WORD_INHERITING = "ai_wake_word_inheriting"
        private const val KEY_WAKE_WORD_DEFAULT_ID = "ai_wake_word_default_id"
        private const val KEY_VOICE_KEY_INHERITING = "ai_voice_key_inheriting"
        private const val KEY_VOICE_KEY_DEFAULT_KEY = "ai_voice_key_default_key"
        private const val KEY_ALWAYS_USE_AI = "voice_always_use_ai"

        const val DEFAULT_AI_MODEL = "claude-sonnet-4-6"
        const val DEFAULT_PERSONALITY_ID = "dashie"
        const val DEFAULT_CONVERSATION_TIMEOUT = 30
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** AI assistant enabled (default: true) */
    /** AI model ID (e.g. "claude-sonnet-4-5-20250929") */
    var aiModel: String
        get() = prefs.getString(KEY_AI_MODEL, DEFAULT_AI_MODEL) ?: DEFAULT_AI_MODEL
        set(value) {
            val old = prefs.getString(KEY_AI_MODEL, DEFAULT_AI_MODEL) ?: DEFAULT_AI_MODEL
            prefs.edit().putString(KEY_AI_MODEL, value).commit()
            // Same underlying key ("ai_model" in dashie_lite_prefs) as VoicePreferences.aiModel —
            // both writers must keep the brain-route cache honest. See AiModelChangeGuard.
            AiModelChangeGuard.noteAiModelChanged(prefs, old, value)
        }

    /** Web search enabled for AI responses (default: true) */
    var webSearchEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEB_SEARCH_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_WEB_SEARCH_ENABLED, value).commit() }

    /** Retrieve pictures in AI responses (default: false) */
    var retrievePicturesEnabled: Boolean
        get() = prefs.getBoolean(KEY_RETRIEVE_PICTURES_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_RETRIEVE_PICTURES_ENABLED, value).commit() }

    /** Prompt for thumbs up/down feedback after voice responses (default: true; the
     *  feature itself is alpha-gated via feature_access 'voice_feedback'). Account-scoped. */
    var promptForFeedback: Boolean
        get() = prefs.getBoolean(KEY_PROMPT_FOR_FEEDBACK, true)
        set(value) { prefs.edit().putBoolean(KEY_PROMPT_FOR_FEEDBACK, value).commit() }

    /** Conversation memory enabled (default: false) */
    var conversationContextEnabled: Boolean
        get() = prefs.getBoolean(KEY_CONVERSATION_CONTEXT_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_CONVERSATION_CONTEXT_ENABLED, value).commit() }

    /** Conversation memory timeout in minutes (0 = never expire, default: 30) */
    var conversationTimeout: Int
        get() = prefs.getInt(KEY_CONVERSATION_TIMEOUT, DEFAULT_CONVERSATION_TIMEOUT)
        set(value) { prefs.edit().putInt(KEY_CONVERSATION_TIMEOUT, value).commit() }

    /** Active personality ID (default: "dashie") */
    var personalityId: String
        get() = prefs.getString(KEY_PERSONALITY_ID, DEFAULT_PERSONALITY_ID) ?: DEFAULT_PERSONALITY_ID
        set(value) { prefs.edit().putString(KEY_PERSONALITY_ID, value).commit() }

    /** Cached display name for the active personality (avoids hardcoded mapping) */
    var personalityDisplayName: String
        get() = prefs.getString(KEY_PERSONALITY_DISPLAY_NAME, "Dashie") ?: "Dashie"
        set(value) { prefs.edit().putString(KEY_PERSONALITY_DISPLAY_NAME, value).commit() }

    /** GAP-2: true when the device is INHERITING the account default personality (no per-device
     *  override), so the Voice & AI summary can render "Default (<name>)". Kotlin can't compute
     *  this (it only holds the effective value); JS pushes it via setAiVoiceSettings. */
    var personalityInheriting: Boolean
        get() = prefs.getBoolean(KEY_PERSONALITY_INHERITING, false)
        set(value) { prefs.edit().putBoolean(KEY_PERSONALITY_INHERITING, value).commit() }

    /** GAP-2: true when the device is INHERITING the account default WAKE WORD (no per-device
     *  override), so the Voice & AI summary / picker can render "Default (<name>)". JS pushes it. */
    var wakeWordInheriting: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD_INHERITING, false)
        set(value) { prefs.edit().putBoolean(KEY_WAKE_WORD_INHERITING, value).commit() }

    /** GAP-2: the account default wake-word MODEL ID (e.g. "hey_dashie"), pushed by JS. The picker
     *  maps it to a friendly name for the "Default (X)" row even while the device overrides. */
    var wakeWordDefaultId: String
        get() = prefs.getString(KEY_WAKE_WORD_DEFAULT_ID, "") ?: ""
        set(value) { prefs.edit().putString(KEY_WAKE_WORD_DEFAULT_ID, value).commit() }

    /** GAP-2 Piece B: true when the device is INHERITING the account default CLOUD VOICE
     *  (ai.defaultVoiceKey — no per-device override). Seeds the personality picker's Voice
     *  line instantly; JS pushes it via setAiVoiceSettings. */
    var voiceKeyInheriting: Boolean
        get() = prefs.getBoolean(KEY_VOICE_KEY_INHERITING, false)
        set(value) { prefs.edit().putBoolean(KEY_VOICE_KEY_INHERITING, value).commit() }

    /** GAP-2 Piece B: the account default cloud voice KEY (e.g. "AMY"), pushed by JS. The
     *  picker prettifies it for the "Default (X)" seed render; the async bridge fetch
     *  (voiceKeyDefaultStateForNative) replaces it with the exact DB name. */
    var voiceKeyDefaultKey: String
        get() = prefs.getString(KEY_VOICE_KEY_DEFAULT_KEY, "") ?: ""
        set(value) { prefs.edit().putString(KEY_VOICE_KEY_DEFAULT_KEY, value).commit() }

    /** Always route chore completions through AI (bypasses fast path, default: false) */
    var alwaysUseAI: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_USE_AI, false)
        set(value) { prefs.edit().putBoolean(KEY_ALWAYS_USE_AI, value).commit() }
}

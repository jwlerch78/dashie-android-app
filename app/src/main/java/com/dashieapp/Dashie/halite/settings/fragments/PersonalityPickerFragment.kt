package com.dashieapp.Dashie.halite.settings.fragments

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.AiPreferences
import com.dashieapp.Dashie.halite.settings.SettingsActivity
import com.dashieapp.Dashie.halite.settings.data.PersonalityItem
import com.dashieapp.Dashie.halite.settings.items.SettingsItem

/**
 * Picker fragment for selecting AI personalities.
 *
 * Shows built-in templates immediately from a hardcoded fallback, then
 * refreshes with full data (including custom personalities) once the JS
 * bridge responds. This eliminates the "Loading..." delay.
 *
 * Two sections:
 * - Built-in Personalities (templates from database)
 * - Custom Personalities (user-created, with Edit and Create actions)
 */
class PersonalityPickerFragment : BaseSettingsFragment() {

    companion object {
        private const val TAG = "PersonalityPicker"
        // GAP-2: the "Default (X)" row — follow the account default personality.
        private const val DEFAULT_ITEM_ID = "__default_personality__"
        // GAP-2 Piece B: the Voice section — the cloud voice has no standalone native picker
        // (it rides the personality), so its "Default (X)" affordance lives here.
        private const val VOICE_DEFAULT_ITEM_ID = "__default_voice__"
        // Voice section visibility — see the Voice section comment in getItems().
        private const val SHOW_VOICE_SECTION = false
        private const val VOICE_CURRENT_ITEM_ID = "__current_voice__"

        /** 'AMY' → 'Amy', 'DRILL_SERGEANT' → 'Drill Sergeant' — display fallback until the
         *  bridge fetch delivers the exact DB name (voiceKeyDefaultStateForNative). */
        private fun prettyVoiceKey(key: String): String =
            key.split('_').filter { it.isNotEmpty() }
                .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.titlecase() } }
        // Instant-open cache: last good { personalities, defaultState } payload, persisted so a
        // re-open renders fully populated from disk before the async bridge (~2s) re-confirms.
        private const val CACHE_PREFS = "personality_picker_cache"
        private const val CACHE_KEY = "payload_json"

        /** Hardcoded fallback shown instantly while network loads */
        private val FALLBACK_PERSONALITIES = listOf(
            PersonalityItem("dashie", "dashie", "Standard", "Friendly family assistant", "preferred", "RACHEL", "Rachel", false),
            PersonalityItem("santa", "santa", "Santa", "Jolly holiday helper", "fixed", "SANTA", "Santa", false),
            PersonalityItem("bad_santa", "bad_santa", "Bad Santa", "Grumpy but helpful holiday assistant", "fixed", "SANTA", "Santa", false),
            PersonalityItem("pirate", "pirate", "Pirate Captain", "Eccentric swashbuckler in the spirit of Captain Jack Sparrow", "fixed", "PIRATE", "Pirate", false),
            PersonalityItem("drill_sergeant", "drill_sergeant", "Drill Sergeant", "Motivational coach with military discipline", "fixed", "DRILL_SERGEANT", "Drill Sergeant", false),
            PersonalityItem("surfer_dude", "surfer_dude", "Surfer Dude", "Laid-back beach vibes and chill attitude", "fixed", "JERRY", "Jerry", false),
            PersonalityItem("butler", "butler", "Butler", "Overworked, bitter servant who thinks you're lazy", "fixed", "BUTLER", "Butler", false),
            PersonalityItem("cowboy", "cowboy", "Cowboy", "Tough, rugged Texan with old-fashioned grit", "fixed", "COWBOY", "Cowboy", false),
            PersonalityItem("princess", "princess", "Princess", "Gracious, kind-hearted royal with a sparkle of pluck", "fixed", "HANA", "Hana", false),
            PersonalityItem("wizard", "wizard", "Wizard", "Wise, twinkly-eyed old wizard full of gentle mischief", "fixed", "DUMBLEDORE", "Dumbledore", false)
        )
    }

    override val title = "Personality"

    private var builtInPersonalities: List<PersonalityItem> = FALLBACK_PERSONALITIES
    private var customPersonalities: List<PersonalityItem> = emptyList()
    private var networkLoaded = false
    private var renderedFromCache = false
    private var loadError: String? = null
    // GAP-2: the account default personality id (from ai.defaultPersonalityId, via JS) + whether
    // this device is currently inheriting it (no device override). Drives the "Default (X)" row.
    private var accountDefaultId: String = ""
    private var isInheriting: Boolean = false
    // GAP-2 Piece B: cloud-voice inherit state for the Voice section. Seeded from native prefs
    // (instant, kept fresh by applyEffectiveAiVoice), re-confirmed with exact DB names by the
    // bridge fetch's voiceState. Hidden behind the personality's fixed voice when locked.
    private var voiceInheriting: Boolean = false
    private var voiceDefaultName: String = ""
    private var voiceEffectiveName: String = ""

    private lateinit var aiPrefs: AiPreferences
    private lateinit var halitePrefs: HalitePreferences

    override fun onResume() {
        super.onResume()
        aiPrefs = AiPreferences(requireContext())
        halitePrefs = HalitePreferences(requireContext())
        // Instant render: last cached list (or the built-in fallback) for the ROWS, then overlay
        // the FRESH inherit state from native prefs for the "Default (X)" row. applyEffectiveAiVoice
        // keeps ai_personality_inheriting + display name in lockstep with the Voice & AI summary, so
        // the picker's Default row matches the parent page IMMEDIATELY — instead of showing the stale
        // cache for ~3-4s while the bridge's DB list fetch (which carries the JS defaultState) lands.
        // When inheriting, the effective id IS the account default id, so accountDefaultId resolves
        // its name from the list. The bridge still runs and re-confirms both list + defaultState.
        readPickerCache()?.let { applyPersonalitiesPayload(it, cache = false) }
        isInheriting = aiPrefs.personalityInheriting
        if (isInheriting) accountDefaultId = aiPrefs.personalityId
        // Voice line seed: fresh native prefs beat the cached names (a remote default change
        // updates the prefs via applyEffectiveAiVoice before the picker re-opens).
        voiceInheriting = aiPrefs.voiceKeyInheriting
        prettyVoiceKey(aiPrefs.voiceKeyDefaultKey).takeIf { it.isNotEmpty() }?.let { voiceDefaultName = it }
        prettyVoiceKey(halitePrefs.voice.voiceKey).takeIf { it.isNotEmpty() }?.let { voiceEffectiveName = it }
        renderedFromCache = true
        refreshItems()
        loadPersonalities()
    }

    override fun onPause() {
        super.onPause()
        val delegate = SettingsActivity.jsBridgeRef?.settingsDataDelegate
        delegate?.onPersonalitiesLoaded = null
        delegate?.onPersonalitiesError = null
    }

    override fun getItems(): List<SettingsItem> {
        val currentId = aiPrefs.personalityId
        val items = mutableListOf<SettingsItem>()

        // ── Built-in section ──
        items.add(SettingsItem.SectionHeader(id = "section_builtin", title = "Personalities"))

        // GAP-2 "Default" row — follow the account default. Appends the account default's name in
        // parens ONCE it resolves; picking a concrete personality below overrides it per-device.
        // Checked when the device is inheriting (no override); concrete rows un-checked in that case.
        // Before the async bridge lands (~2s) accountDefaultId is empty → show a bare "Default"
        // (NOT a "(Dashie)" fallback, which was wrong AND flashed) and append "(X)" when known.
        val resolvedDefaultName = (builtInPersonalities + customPersonalities)
            .find { it.key == accountDefaultId || it.id == accountDefaultId }?.name
        val defaultLabel = if (resolvedDefaultName != null) "$resolvedDefaultName (Default)" else "Default"
        items.add(
            SettingsItem.Checkmark(
                id = DEFAULT_ITEM_ID,
                label = defaultLabel,
                isChecked = isInheriting,
                sublabel = "Follow the account default"
            )
        )

        for (p in builtInPersonalities) {
            items.add(
                SettingsItem.Checkmark(
                    id = p.key,
                    label = p.name,
                    // Gate concrete checkmarks on networkLoaded: the inherit state only arrives
                    // with the async bridge (~2s). Before it lands, currentId is the native
                    // EFFECTIVE value (= the resolved account default when inheriting), so an
                    // ungated check would briefly show the wrong concrete row selected before the
                    // "Default (X)" row takes the check. No check until we know the real state.
                    isChecked = networkLoaded && !isInheriting && (p.key == currentId || p.id == currentId),
                    sublabel = p.subtitle
                )
            )
        }

        // ── Custom section ──
        items.add(SettingsItem.SectionHeader(id = "section_custom", title = "Custom Personalities"))

        if (customPersonalities.isEmpty()) {
            if (!networkLoaded) {
                items.add(SettingsItem.Info(id = "loading_custom", text = "Loading..."))
            } else {
                items.add(SettingsItem.Info(
                    id = "no_custom",
                    text = "No custom personalities yet"
                ))
            }
        } else {
            for (p in customPersonalities) {
                items.add(
                    SettingsItem.Checkmark(
                        id = p.id,
                        label = p.name,
                        isChecked = networkLoaded && !isInheriting && (p.key == currentId || p.id == currentId),
                        sublabel = p.subtitle
                    )
                )
            }
        }

        // ── Voice section (GAP-2 Piece B) ──
        // HIDDEN (2026-07-27): the voice line on the personality picker read as
        // clutter/confusion (showed the EFFECTIVE voice, e.g. "Butler", under an
        // unrelated personality). Kept behind a flag in case a dedicated voice
        // surface wants it back.
        if (SHOW_VOICE_SECTION) {
        val activePersonality = (builtInPersonalities + customPersonalities)
            .find { it.key == currentId || it.id == currentId }
        items.add(SettingsItem.SectionHeader(id = "section_voice", title = "Voice"))
        if (activePersonality?.isVoiceFixed == true) {
            items.add(SettingsItem.Info(
                id = VOICE_CURRENT_ITEM_ID,
                text = "${voiceEffectiveName.ifEmpty { "Voice" }} — set by ${activePersonality.name}"
            ))
        } else {
            val voiceDefaultLabel =
                if (voiceDefaultName.isNotEmpty()) "$voiceDefaultName (Default)" else "Default"
            items.add(SettingsItem.Checkmark(
                id = VOICE_DEFAULT_ITEM_ID,
                label = voiceDefaultLabel,
                isChecked = voiceInheriting,
                sublabel = "Follow the account default voice"
            ))
            if (!voiceInheriting && voiceEffectiveName.isNotEmpty()) {
                items.add(SettingsItem.Checkmark(
                    id = VOICE_CURRENT_ITEM_ID,
                    label = voiceEffectiveName,
                    isChecked = true,
                    sublabel = "This device's voice"
                ))
            }
        }
        }

        // Show error if network failed (non-blocking — fallback data is still shown)
        if (loadError != null && !networkLoaded) {
            items.add(SettingsItem.Info(id = "error", text = loadError ?: ""))
        }

        return items
    }

    override fun handleItemClick(item: SettingsItem) {
        if (item.id == DEFAULT_ITEM_ID) { selectDefault(); return }
        if (item.id == VOICE_DEFAULT_ITEM_ID) { selectVoiceDefault(); return }
        if (item.id == VOICE_CURRENT_ITEM_ID) return  // informational — already the active voice
        val personality = (builtInPersonalities + customPersonalities)
            .find { it.key == item.id || it.id == item.id }
            ?: return
        isInheriting = false   // a concrete pick is a per-device override
        selectPersonality(personality)
    }

    /** GAP-2: "Default" picked → drop the device override so this device follows the account
     *  default personality. clearPersonalityOverride() removes the mirror, re-resolves + pushes the
     *  effective value back to native, and uploads the cleared override. */
    private fun selectDefault() {
        Log.d(TAG, "Selecting Default personality (follow account default)")
        isInheriting = true
        // Set the native inherit flag + account-default name NOW so the Voice & AI summary shows
        // "Default (<name>)" immediately, before the async clearPersonalityOverride round-trips.
        aiPrefs.personalityInheriting = true
        (builtInPersonalities + customPersonalities)
            .find { it.key == accountDefaultId || it.id == accountDefaultId }
            ?.let { aiPrefs.personalityDisplayName = it.name }
        val wv = SettingsActivity.webViewRef?.get()
        wv?.post {
            wv.evaluateJavascript(
                """
                (async () => {
                    try {
                        // WINDOW GLOBAL, not import('/js/…') — evaluateJavascript's base URL is
                        // about:blank so a dynamic import can't resolve the specifier. Exposed by
                        // ai-voice-resolution.js at boot.
                        if (typeof window.clearPersonalityOverride === 'function') {
                            await window.clearPersonalityOverride();
                        } else {
                            console.error('DROP: window.clearPersonalityOverride missing');
                        }
                    } catch (e) { console.error('clearPersonalityOverride failed', e); }
                })()
                """.trimIndent(), null
            )
        }
        refreshItems()
        refreshParentFragment()
    }

    /** GAP-2 Piece B: "Default" picked on the Voice line → drop the device VOICE override so this
     *  device follows ai.defaultVoiceKey. clearVoiceKeyOverride() removes the mirror, re-resolves +
     *  pushes the effective voice back to native, and uploads the {voiceKey:''} inherit sentinel. */
    private fun selectVoiceDefault() {
        Log.d(TAG, "Selecting Default voice (follow account default)")
        voiceInheriting = true
        // Set the native inherit flag NOW so a re-open seeds correctly before the async round-trip.
        aiPrefs.voiceKeyInheriting = true
        val wv = SettingsActivity.webViewRef?.get()
        wv?.post {
            wv.evaluateJavascript(
                """
                (async () => {
                    try {
                        // WINDOW GLOBAL, not import('/js/…') — evaluateJavascript's base URL is
                        // about:blank so a dynamic import can't resolve the specifier. Exposed by
                        // ai-voice-resolution.js at boot.
                        if (typeof window.clearVoiceKeyOverride === 'function') {
                            await window.clearVoiceKeyOverride();
                        } else {
                            console.error('DROP: window.clearVoiceKeyOverride missing');
                        }
                    } catch (e) { console.error('clearVoiceKeyOverride failed', e); }
                })()
                """.trimIndent(), null
            )
        }
        refreshItems()
        refreshParentFragment()
    }

    // ── Selection ────────────────────────────────────────────────────

    private fun selectPersonality(personality: PersonalityItem) {
        Log.d(TAG, "Selecting personality: ${personality.key} (voice: ${personality.voiceKey})")

        // A concrete pick is a per-device override → not inheriting; summary shows the plain name.
        aiPrefs.personalityInheriting = false
        aiPrefs.personalityId = if (personality.isCustom) personality.id else personality.key
        aiPrefs.personalityDisplayName = personality.name

        if (personality.isVoiceFixed && personality.voiceKey.isNotEmpty()) {
            halitePrefs.voice.voiceKey = personality.voiceKey
            // GAP-2 Piece B: a fixed-voice pick pins the device voice override (syncPersonalityToJs
            // writes the mirror) — keep the Voice line's inherit state honest immediately.
            aiPrefs.voiceKeyInheriting = false
            voiceInheriting = false
            voiceEffectiveName = personality.voiceName.ifEmpty { prettyVoiceKey(personality.voiceKey) }
        } else if (personality.voiceMode == "preferred") {
            // Preferred-voice pick CLEARS the device voice override (syncPersonalityToJs removes
            // the mirror) → the device inherits the account voice again.
            aiPrefs.voiceKeyInheriting = true
            voiceInheriting = true
        }

        syncPersonalityToJs(personality)

        // Also fire ACTION_AI_VOICE_SETTINGS_CHANGED so MainBroadcastManager
        // persists {personalityId, voiceKey} to user_devices.aiVoice via the
        // vetted saveDeviceSettings path. Supplements the inline evaluateJavascript
        // sync in syncPersonalityToJs, which has historically been unreliable
        // (dynamic imports, race conditions, silent network failures).
        requireContext().sendBroadcast(
            android.content.Intent("com.dashieapp.Dashie.ACTION_AI_VOICE_SETTINGS_CHANGED").apply {
                setPackage(requireContext().packageName)
            }
        )

        refreshItems()
        refreshParentFragment()
    }

    // ── JS Sync ──────────────────────────────────────────────────────

    private fun syncPersonalityToJs(personality: PersonalityItem) {
        val wv = SettingsActivity.webViewRef?.get() ?: return
        val escapedId = (if (personality.isCustom) personality.id else personality.key)
            .replace("'", "\\'")
        val escapedVoiceKey = personality.voiceKey.replace("'", "\\'")
        wv.post {
            wv.evaluateJavascript("""
                (async () => {
                    try {
                        if (!window.settingsStore) return;
                        window.settingsStore.set('ai.personality_id', '$escapedId');
                        localStorage.setItem('dashie-device-personality-id', '$escapedId');

                        // Resolve and set fixed voice if applicable
                        if ('${personality.voiceMode}' === 'fixed' && '$escapedVoiceKey') {
                            try {
                                const { getVoicesService } = await import('/js/data/services/voices-service.js');
                                const voice = await getVoicesService().getVoice('$escapedVoiceKey');
                                if (voice) {
                                    window.settingsStore.set('interface.voiceKey', voice.key);
                                    window.settingsStore.set('interface.voiceId', voice.id);
                                    // Update device-local voice so it survives refresh
                                    // (VoiceService checks this localStorage key FIRST)
                                    localStorage.setItem('dashie-device-voice-key', voice.key);
                                }
                            } catch (_) {
                                window.settingsStore.set('interface.voiceKey', '$escapedVoiceKey');
                                localStorage.setItem('dashie-device-voice-key', '$escapedVoiceKey');
                            }
                        } else if ('${personality.voiceMode}' === 'preferred') {
                            // Preferred-voice personalities (e.g. Dashie) don't force a voice,
                            // so clear the device-local override to let the account voice through
                            localStorage.removeItem('dashie-device-voice-key');
                        }

                        // Emit personality-changed so VoiceService applies the fixed voice
                        const svc = window.personalityService;
                        if (svc) {
                            try {
                                const personality = await svc.getPersonality('$escapedId');
                                if (personality && window.AppComms) {
                                    window.AppComms.emit('personality-changed', { personality });
                                }
                            } catch (_) {}
                        }

                        // Trigger device settings sync via AppComms
                        if (window.AppComms) {
                            window.AppComms.publish(window.AppComms.events?.SETTINGS_CHANGED || 'settings-changed', {
                                ai: { personality_id: '$escapedId' }
                            });
                        }

                        // Also directly upload aiVoice to Supabase to ensure persistence
                        // (the AppComms path has guards that may skip the upload)
                        try {
                            const { getCurrentDeviceSettings } = await import('/js/core/initialization/device-registration.js');
                            const { getDeviceSettingsService } = await import('/js/data/services/device-settings-service.js');
                            const { getDeviceId } = await import('/js/utils/device-id.js');
                            // Canonical device id (native androidId / web UUID). The old
                            // localStorage['dashie-device-id'] read was null on every native
                            // device (that key is only written on the web path), so this upload
                            // silently no-op'd — the broadcast path masked it for concrete picks.
                            const deviceId = getDeviceId();
                            if (deviceId) {
                                const settings = getCurrentDeviceSettings();
                                if (settings.aiVoice) {
                                    await getDeviceSettingsService().updateDeviceSettings(
                                        deviceId, 'aiVoice', settings.aiVoice, false
                                    );
                                    console.log('[PersonalityPicker] aiVoice synced to Supabase');
                                }
                            } else {
                                console.warn('DROP: [PersonalityPicker] aiVoice upload skipped — no device id');
                            }
                        } catch (syncErr) {
                            console.warn('[PersonalityPicker] Failed to sync aiVoice to Supabase:', syncErr);
                        }
                    } catch (e) {
                        console.error('Failed to sync personality to JS:', e);
                    }
                })()
            """.trimIndent(), null)
        }
    }

    // ── Loading ──────────────────────────────────────────────────────

    private fun loadPersonalities() {
        // Keep the cached render "loaded" so its checkmarks don't flicker off while refreshing.
        networkLoaded = renderedFromCache
        loadError = null
        // Don't show loading spinner — cached/fallback data is already displayed
        refreshItems()

        val delegate = SettingsActivity.jsBridgeRef?.settingsDataDelegate
        if (delegate == null) {
            loadError = "Settings bridge not available"
            refreshItems()
            return
        }

        delegate.onPersonalitiesLoaded = { json ->
            Handler(Looper.getMainLooper()).post { applyPersonalitiesPayload(json, cache = true) }
        }

        delegate.onPersonalitiesError = { message ->
            Handler(Looper.getMainLooper()).post {
                networkLoaded = true  // Stop showing "Loading..." in custom section
                loadError = message
                refreshItems()
            }
        }

        val wv = SettingsActivity.webViewRef?.get()
        if (wv == null) {
            loadError = "WebView not available"
            refreshItems()
            return
        }

        wv.post {
            wv.evaluateJavascript("""
                (async () => {
                    try {
                        const svc = window.personalityService;
                        if (!svc) throw new Error('Personality service not initialized');
                        const data = await svc.getPersonalitiesForNative();
                        // GAP-2: bundle the account-default + inherit state for the "Default (X)" row.
                        // Call the WINDOW GLOBAL — never import('/js/…') here. Kotlin's
                        // evaluateJavascript runs with base URL about:blank, so a dynamic import
                        // fails to resolve the module specifier (CORS-cross-origin script).
                        // ai-voice-resolution.js exposes this on window at boot.
                        let defaultState = { defaultId: '', inheriting: false };
                        try {
                            if (typeof window.personalityDefaultStateForNative === 'function') {
                                defaultState = window.personalityDefaultStateForNative();
                            } else {
                                defaultState = { defaultId: '', inheriting: false, __err: 'window.personalityDefaultStateForNative missing' };
                            }
                        } catch (e) { defaultState = { defaultId: '', inheriting: false, __err: String((e && e.message) || e) }; }
                        // GAP-2 Piece B: cloud-voice inherit state for the Voice section. Same
                        // window-global rule; async (resolves exact DB names via voices-service).
                        // null on an old webapp → the native-pref seed stands.
                        let voiceState = null;
                        try {
                            if (typeof window.voiceKeyDefaultStateForNative === 'function') {
                                voiceState = await window.voiceKeyDefaultStateForNative();
                            }
                        } catch (e) { voiceState = null; }
                        window.DashieNative.onPersonalitiesLoaded(
                            JSON.stringify({ personalities: data, defaultState, voiceState }));
                    } catch (e) {
                        window.DashieNative.onPersonalitiesError(e.message || 'Failed to load personalities');
                    }
                })()
            """.trimIndent(), null)
        }
    }

    /**
     * Parse a { personalities, defaultState } payload into the fragment state + refresh.
     * Shared by the bridge callback and the instant cache-seed (onResume). Empty/partial
     * payloads don't wipe an existing (cached) render. cache=true persists it for next open.
     */
    private fun applyPersonalitiesPayload(json: String, cache: Boolean) {
        val obj = try { org.json.JSONObject(json) } catch (_: Exception) { null } ?: return
        val personalitiesJson = obj.optJSONArray("personalities")?.toString() ?: json
        val parsed = PersonalityItem.fromJsonArray(personalitiesJson)
        if (parsed.isNotEmpty()) {
            builtInPersonalities = parsed.filter { !it.isCustom }.ifEmpty { FALLBACK_PERSONALITIES }
            customPersonalities = parsed.filter { it.isCustom }
        }
        val defState = obj.optJSONObject("defaultState")
        accountDefaultId = defState?.optString("defaultId") ?: accountDefaultId
        isInheriting = defState?.optBoolean("inheriting", false) ?: isInheriting
        // GAP-2 Piece B: cloud-voice inherit state (absent on an old webapp — seeds stand).
        obj.optJSONObject("voiceState")?.let { vs ->
            voiceInheriting = vs.optBoolean("inheriting", voiceInheriting)
            vs.optString("defaultName").takeIf { it.isNotEmpty() }?.let { voiceDefaultName = it }
            vs.optString("effectiveName").takeIf { it.isNotEmpty() }?.let { voiceEffectiveName = it }
        }
        networkLoaded = true
        loadError = null
        if (cache) writePickerCache(json)
        refreshItems()
    }

    private fun readPickerCache(): String? =
        context?.getSharedPreferences(CACHE_PREFS, android.content.Context.MODE_PRIVATE)
            ?.getString(CACHE_KEY, null)

    private fun writePickerCache(json: String) {
        context?.getSharedPreferences(CACHE_PREFS, android.content.Context.MODE_PRIVATE)
            ?.edit()?.putString(CACHE_KEY, json)?.apply()
    }

    private fun refreshParentFragment() {
        val activity = activity as? SettingsActivity ?: return
        activity.supportFragmentManager.fragments
            .filterIsInstance<com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>()
            .forEach { it.refresh() }
    }
}

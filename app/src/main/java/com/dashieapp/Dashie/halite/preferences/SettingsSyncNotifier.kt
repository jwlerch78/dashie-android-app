package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Sync-by-default: fire the webapp `native-settings-changed` event for any
 * write to a cloud-synced SharedPreferences key, from ANY surface (schema
 * pages, sidebar dialogs, the Fully-Kiosk HTTP API, future code) — without
 * that surface needing to know about sync.
 *
 * WHY (SETTINGS_ROBUSTNESS_PLAN Phase 2): the settings system defaulted to
 * *unsynced* — a Kotlin setting reached the cloud only if someone wired an
 * explicit onChanged → callback → broadcast, three links each individually
 * forgettable. That produced ~23 one-sided-write bugs (SETTINGS_AUDIT
 * 2026-07-04): the sidebar screensaver dialog, photo sub-dialogs, the whole
 * Fully-Kiosk API, screenOffBehavior's no-op callback, etc. This inverts the
 * default: a synced pref write auto-dispatches its category, and the webapp's
 * native-settings-listener reads the category back and persists it.
 *
 * HOW: one OnSharedPreferenceChangeListener per synced prefs file. A changed
 * key is mapped (PREF_KEY_TO_CATEGORY) to a webapp category and dispatched
 * (debounced per category) via the injected `dispatch` callback — the same
 * `window.dispatchEvent(new CustomEvent('native-settings-changed', …))` the
 * existing explicit broadcasts use.
 *
 * ADDITIVE + FALLBACK-SAFE: the existing explicit broadcasts stay in place
 * during the bake. A key absent from the table keeps its current behavior
 * (its own wiring, if any), so a table gap can't REGRESS a working setting —
 * it only means the notifier isn't yet the one carrying that key. The table
 * therefore prioritizes the keys whose current wiring is BROKEN (the census
 * one-sided writes); local-only keys are simply absent (no sync, correct).
 *
 * SUPPRESSION: while the webapp is applying cloud→Kotlin settings it brackets
 * the native-setter loop with DashieNative.beginSettingsApply()/endSettingsApply()
 * (see device-settings-writer.js pushDeviceSettingsToNative). Those increment
 * `applyDepth`; the notifier no-ops while depth > 0 so a cloud apply doesn't
 * echo back out. Kotlin-internal boot-restore paths use [suppress].
 *
 * Owned by HaliteComponentRegistry (process lifetime). SharedPreferences holds
 * listeners weakly, so we keep strong refs in [listeners].
 */
class SettingsSyncNotifier(
    private val context: Context,
    /** Fires the webapp event for one category. Provided by the registry so the
     *  notifier stays WebView-agnostic (MainBroadcastManager owns the dispatch). */
    private val dispatch: (category: String) -> Unit
) {
    companion object {
        private const val TAG = "SettingsSyncNotifier"
        private const val DEBOUNCE_MS = 400L

        // Synced SharedPreferences files. Local-only files (feature_visibility,
        // subscription) are intentionally NOT watched.
        // internal (not private): the Robolectric schema-walker asserts against
        // these tables, and Phase 3 checks F′/G lint-diff them cross-repo.
        internal val SYNCED_PREF_FILES = listOf(
            "dashie_lite_prefs",     // display/sleep/screensaver/photos/voice/aiVoice
            "dashie_locations_prefs",
            "dashie_chores_prefs",
            "dashie_calendar_prefs", // calendar_enabled/scroll_time/start_week_on (no
                                     // CalendarPreferences class; keys written by
                                     // ConnectionSettingsWiring + setCalendarSettings)
            "wake_word_prefs"        // selected_model_id → aiVoice (census N2: the wake-word
                                     // picker writes here; current/pending_version are
                                     // runtime state, deliberately unclassified)
            // NOTE: dashie_theme_prefs (theme_family) + darkMode (applied via a
            // callback, not a pref) are NOT watched — theme already syncs through
            // its dedicated path and was never a one-sided bug. dashie_power_config
            // (HA power blob) rides the home_assistant_config broadcast. Add here
            // only with a matching PREF_KEY_TO_CATEGORY entry.
        )

        /**
         * Raw SharedPreferences key → webapp category. The category getter on
         * the JS side (getSleepSettings/getScreensaverSettings/…) re-reads the
         * whole category, so per-key precision isn't required — only that the
         * key maps to the category whose readback covers it.
         *
         * Keep in sync with the preference classes' KEY_* constants. A synced
         * key added to a *Preferences class without an entry here will not
         * auto-sync (it falls back to its explicit wiring, if any) — Phase 3
         * check G will diff this table against the manifest to catch gaps.
         */
        internal val PREF_KEY_TO_CATEGORY: Map<String, String> = buildMap {
            // ── sleep (SleepPreferences) ──
            for (k in listOf(
                "sleep_enabled", "sleep_method", "sleep_time", "wake_time",
                "resleep_timeout", "inactivity_timeout", "motion_wake_for_sleep",
                "sleep_show_clock", "reduce_brightness_on_sleep"
            )) put(k, "sleep")
            // screen-off behavior is a display setting stored as a Sleep pref
            // (getScreenOffBehavior reads hardware_screen_off); dispatch display.
            put("hardware_screen_off", "display")

            // ── screensaver (ScreensaverPreferences) ──
            for (k in listOf(
                "screensaver_timeout", "screensaver_mode", "show_clock", "show_date",
                "dim_brightness", "screensaver_url", "launch_app_package",
                "launch_app_label", "screensaver_ha_page_path", "reduce_brightness_on_black"
            )) put(k, "screensaver")

            // ── photos (ScreensaverPreferences — photo_* keys) ──
            for (k in listOf(
                "photo_source_type", "ha_media_folder", "unsplash_query",
                "unsplash_artist_hyperlinks", "slideshow_interval",
                "slideshow_transition", "slideshow_shuffle", "photo_fit", "show_metadata"
            )) put(k, "photos")

            // ── display device keys (DisplayPreferences + ScreensaverPreferences) ──
            for (k in listOf(
                "widget_zoom", "dashboard_zoom", "widget_font_size", "display_size",
                "sidebar_icon_size", "layout_mode", "orientation_lock",
                "auto_brightness_enabled"
            )) put(k, "display")
            put("motion_wake_mode", "display")       // ScreensaverPreferences → display.motionWakeMode
            put("weather_overlay_enabled", "display")
            put("weather_entity_id", "display")

            // ── voice device keys (VoicePreferences) ──
            for (k in listOf(
                "voice_enabled", "response_handling", "voice_display_format",
                "confirmation_tone_type", "confirmation_tone_volume"
            )) put(k, "voice")

            // ── aiVoice device keys (VoicePreferences / AiPreferences) ──
            put("voice_key", "aiVoice")
            put("voice_id", "aiVoice")
            put("ai_personality_id", "aiVoice")
            put("selected_model_id", "aiVoice")  // wake word (wake_word_prefs, N2)

            // ── developer ──
            put("sample_collection_enabled", "developer")

            // ── calendar (dashie_calendar_prefs — raw keys, no domain class) ──
            // Was wrong twice pre-Phase-3 (caught by checks F′(a)/G4 2026-07-05):
            // the file wasn't in SYNCED_PREF_FILES, and the scroll key was listed
            // as "calendar_scroll_time" while the real pref key is "scroll_time".
            // hours_to_show = vertical density preset ('auto'|'6'…'24'), per-device
            // like scroll_time — classify to calendar so a native picker change
            // auto-dispatches calendar → cloud.
            for (k in listOf("calendar_enabled", "scroll_time", "start_week_on", "hours_to_show"))
                put(k, "calendar")
            // NEEDS-DEVICE-VERIFICATION (calendar.writeAccess native UI, 2026-07-15) — built while owner away from devices
            // writeAccess (who may CUD calendar entries) is ACCOUNT-level like
            // start_week_on; classify its raw pref key to the calendar category so
            // a native picker change auto-dispatches calendar → cloud.
            put("write_access", "calendar")

            // ── locations (LocationsPreferences) ──
            // notification_sounds is device-level (user_devices.locations); the
            // other seven are ACCOUNT-level (user_settings.locations.*) — the
            // 'locations' readback handler splits them (handleLocationsChanged
            // saves account keys via settingsStore.save, notificationSounds via
            // saveDeviceSettings), so one category dispatch covers both scopes.
            for (k in listOf(
                "notification_sounds", "tracking_enabled", "show_radius_circles",
                "travel_time_enabled", "early_arrival_games", "early_arrival_practices",
                "early_arrival_other", "traffic_model"
            )) put(k, "locations")

            // ── choresRewards (ChoresRewardsPreferences) ──
            for (k in listOf(
                "chores_enabled", "rewards_enabled", "anyone_enabled",
                "participants_json", "upcoming_days"
            )) put(k, "choresRewards")
        }

        /**
         * Explicitly-NOT-auto-synced keys from the synced domain classes
         * (SleepPreferences, DisplayPreferences, VoicePreferences, AiPreferences,
         * ScreensaverPreferences, LocationsPreferences, ChoresRewardsPreferences).
         *
         * Phase 3 check F′(b) requires PREF_KEY_TO_CATEGORY ∪ SYNC_EXEMPT to cover
         * every KEY_* constant in those companion objects, so a new key added to a
         * synced domain class MUST be classified — either mapped to a category
         * (auto-sync) or listed here with its reason. Reasons used below:
         *   account-level — synced through user_settings (family-walker / account
         *                   readback handlers), not the device notifier
         *   local-only    — device-local by design, never leaves the device
         *   runtime-state — transient machine state, not a user setting
         *   legacy        — migration-only key, no live writer
         */
        internal val SYNC_EXEMPT: Set<String> = buildSet {
            // ── GeneralPreferences ──
            // Only this one key is triaged; the class as a whole is still an
            // AUDIT_PENDING debt marker in check-settings-wiring-harness2.mjs,
            // so listing it here is a claim about THIS key and nothing else.
            // Local-only for the same reason as start_on_boot below: it describes
            // one device's maintenance behaviour, not a household preference —
            // a tablet on a metered connection should be able to stop checking
            // without switching the whole family off. Promoting it later is
            // additive (a SETTINGS_KEY_MAP row + removal from this set).
            add("auto_update_check_enabled")
            // ── SleepPreferences ──
            addAll(listOf(
                "start_on_boot", "keep_screen_on", "launch_on_wake",   // local-only
                "was_in_screen_off_mode", "motion_wake_camera_active"  // runtime-state
            ))
            // ── DisplayPreferences ──
            addAll(listOf(
                "use_24_hour_clock", "date_format", "temperature_unit", // account-level (family.*)
                "show_ui_tips",                                          // account-level (user_settings)
                "auto_brightness_min", "auto_brightness_max", "auto_brightness_curve", // local-only (curve tuning)
                "beta_welcome_shown", "swipe_tip_shown", "setup_pending_step",         // local-only (one-shot flags)
                "timer_positions", "low_bandwidth_mode", "gpu_hardware_layer_enabled", // local-only
                "dash_menu_enabled", "dash_menu_pinned",                               // local-only
                "permission_prompt_declined", "firetv_idle_warning_dismissed"          // local-only (one-shot flags)
            ))
            // ── VoicePreferences ──
            addAll(listOf(
                "voice_control_method",                                 // account-level; derived runtime key seeded by pipeline_preset (VoicePresetSeeder)
                "customize_voice_pipeline", "stt_provider", "tts_provider", // account-level (user_settings.voice.*) — round-trip to
                                                                         // user_settings.voice via ACTION_AI_VOICE_SETTINGS_CHANGED (like always_open_dialog), not this notifier
                "ai_model",                                             // account-level — round-trips via notifyAiModelChanged / ACTION_AI_VOICE_SETTINGS_CHANGED
                // Local Engines registry — console-owned, pushed console→native for a DISPLAY-only
                // url→name lookup. The device never writes it, so there is nothing to sync BACK.
                "local_engines",                                        // account-level (console-owned, read-only on device)
                "mic_muted", "show_responses", "read_responses_aloud",  // local-only
                "use_overlay_nlp", "use_local_brain",                    // local-only (pipeline plumbing)
                "voice_pipeline_id", "voice_pipeline_name", "voice_pipeline_mode", // local-only (HA pipeline)
                "preferred_pipeline_cached_name",                        // runtime-state (cache)
                "cloud_share_available", "cloud_share_checked_at",       // runtime-state (cache)
                "kiosk_agent_mode", "kiosk_retrieve_pictures",           // runtime-state (probe cache — household agentMode/pictures for anon kiosks)
                "hub_brand",                                             // runtime-state (probe cache — which hub serves the gateway, brands "manage in ..." strings)
                "brain_route_cached", "brain_route_checked_at",          // runtime-state (probe cache + TTL stamp — add-on-reported brain route, Open Brain §5; box-local by design, never synced)
                "conversation_model", "conversation_always", "agent_mode",                 // local-only (dev/experimental)
                "always_open_dialog",                                    // account-level (ACCOUNT_VOICE_KEYS) — round-trips to
                                                                         // user_settings.voice via ACTION_AI_VOICE_SETTINGS_CHANGED, not this notifier
                "entity_source",                                         // account-level (user_settings.voice.entitySource) — mirrored
                                                                         // DOWN to a shared-account kiosk read-only; never syncs up from the device

                "realtime_aec", "cascade_aec",                           // local-only (per-device AEC A/B switches)
                "cloud_tts_vendor",                                      // local-only (internal TTS vendor A/B toggle)
                "local_tts_url", "local_tts_voice", "conversation_voice", "live_byok", // account-pushed console→native (setVoiceSettings), not native-originated
                "local_stt_url", "ha_tts_engine", "ha_tts_voice", "ha_stt_engine", // account-pushed console→native (setVoiceSettings), not native-originated
                "pipeline_preset",                                       // account-level (user_settings.voice.pipelinePreset) — round-trips via ACTION_AI_VOICE_SETTINGS_CHANGED like always_open_dialog
                "local_llm_url", "local_llm_model", "local_llm_key_set", // account-pushed console→native (setVoiceSettings); key_set is a derived boolean — the key itself NEVER syncs
                "deepgram_api_key", "prefer_deepgram_stt", "web_search_backend",           // local-only (dev)
                "voice_trial_start", "voice_trial_expiry", "voice_license_status",         // local-only (licensing —
                "voice_license_email", "voice_license_last_check", "voice_license_derived",// server-pinned, never
                "voice_device_id",                                                          // cloud-synced via settings)
                "sample_collection_consent", "mww_sensitivity",          // local-only
                "device_friendly_name"                                   // #8/M5: a native rename pushes the SSOT
                                                                         // (user_devices.device_name) via ACTION_DEVICE_NAME_CHANGED → window.updateDeviceName,
                                                                         // NOT this notifier; setDeviceName mirrors it back. See 20260722_DEVICE_NAME_CONVERGENCE_PLAN.md
            ))
            // ── AiPreferences ──
            addAll(listOf(
                "ai_web_search_enabled", "ai_retrieve_pictures_enabled", // local-only (dev toggles)
                "ai_prompt_for_feedback", "ai_conversation_context_enabled",           // local-only
                "ai_conversation_timeout", "voice_always_use_ai",                      // local-only
                "ai_personality_display_name",                                         // runtime-state (cache of id)
                "ai_personality_inheriting",                                           // runtime-state (GAP-2 "Default (X)" summary hint)
                "ai_wake_word_inheriting", "ai_wake_word_default_id",                   // runtime-state (GAP-2 wake-word "Default (X)")
                "ai_voice_key_inheriting", "ai_voice_key_default_key"                    // runtime-state (GAP-2 Piece B cloud-voice "Default (X)")
            ))
            // ── ScreensaverPreferences ──
            addAll(listOf(
                "motion_wake_enabled", "camera_motion_enabled",          // legacy (migration keys)
                "camera_wake_threshold", "face_wake_distance",           // local-only (hardware tuning)
                "local_photo_folder", "local_photo_folder_uri",          // local-only (device storage paths)
                "immich_server_url", "immich_access_token", "immich_selected_albums", // local-only (credentials)
                "google_photos_album_id", "google_photos_refresh_token", // local-only (credentials)
                "show_preview_on_wake", "clock_position", "clock_size", "clock_font_size", // local-only
                "weather_mode", "forecast_card_size"                     // local-only
            ))
            // ── PerformancePreferences (shares dashie_lite_prefs) — ALL 47 device-local ──
            // Perf/recovery config + runtime memory diagnostics. NONE sync to cloud and
            // reset-on-reinstall is intended (2026-07-23): a Fire TV and a tablet want
            // different memory thresholds, the rest is transient machine state / one-shot
            // flags, and several flip alongside a device permission that must be re-granted on
            // a fresh install anyway. These were escaping check F′b (which doesn't scan
            // PerformancePreferences) so every diagnostic write (~every 30s) hit the loud DROP
            // marker; classified here as local-only + now statically enforced by
            // lint:no-silent-notifier-drop. See SETTINGS_AUDIT_2026-07-21 §5.
            addAll(listOf(
                // runtime-state / diagnostics + one-shot & migration flags
                "app_start_time", "app_was_running", "battery_optimization_prompted",
                "crash_restore_url", "custom_refresh_test_time", "renderer_recovery_pending",
                "migration_ran_stealth_default_2_24_15", "last_app_version",
                "last_ha_iframe_url", "last_webview_url", "last_app_memory_breakdown",
                "last_memory_reading_mb", "last_ram_percent", "last_system_meminfo",
                "hourly_pss_trend", "hourly_ram_trend", "hourly_heap_trend"
            ))
            addAll(listOf(
                // local-only device config (per-device hardware tuning + recovery/refresh)
                "wifi_lock_enabled", "websocket_ping_enabled", "smart_reconnect_enabled",
                "auto_reload_stale_minutes", "auto_reload_on_crash", "memory_recovery_enabled",
                "proactive_refresh_hours", "local_ha_url", "interaction_priority_enabled",
                "return_home_timeout", "restart_rtsp_on_memory_pressure",
                "idle_ram_percent", "idle_heap_percent", "critical_ram_percent", "critical_heap_percent",
                "emergency_recovery_enabled", "emergency_threshold_mode", "emergency_threshold_mb",
                "stealth_refresh_enabled", "stealth_refresh_interval_minutes",
                "daily_refresh_hour", "daily_refresh_hours", "use_exact_alarms",
                "dashboard_telemetry_enabled", "dashboard_telemetry_consent",
                "diagnostics_mode_enabled", "enhanced_logging_enabled", "log_webview_api_requests",
                "performance_overlay_enabled", "performance_overlay_position"
            ))
            // ── TelemetryDelegate's dashboard snapshot (also dashie_lite_prefs) — local-only ──
            //
            // A description of the HA dashboard currently on screen (node/card/entity counts, the
            // card breakdown, JS heap), re-persisted on every telemetry cycle so a CRASH REPORT can
            // say what the device was rendering when it died — CrashReportSections reads them back.
            // Device-local by construction: it describes ONE device's screen at ONE moment, so
            // there is no account-level value to keep in step, and syncing it would push a churning
            // blob to the cloud every cycle for a reader that only exists on-device.
            //
            // ⚠️ Classified as a FAMILY, not just the one key T saw fire. T cont.47 reported the
            // DROP on `snapshot_time`, but all twelve are written in a single edit() in
            // TelemetryDelegate, so every cycle was firing twelve markers and fixing the reported
            // one would have left eleven — the same fix-the-symptom shape the PerformancePreferences
            // block above was created to end.
            //
            // 📌 Why the static check did not catch these: F′(b) scans KEY_* constants in the synced
            // domain classes, and these are raw string literals in a delegate that happens to share
            // dashie_lite_prefs. Same blind spot as the PerformancePreferences keys above.
            addAll(listOf(
                "snapshot_time", "snapshot_dom_nodes", "snapshot_camera_count",
                "snapshot_total_cards", "snapshot_custom_card_count", "snapshot_entity_count",
                "snapshot_view_count", "snapshot_cards_json", "snapshot_custom_cards_json",
                "snapshot_media_json", "snapshot_js_heap_mb", "snapshot_js_heap_limit_mb"
            ))
            // ── ConnectionPreferences: the live HA credential ──
            //
            // 🔴 EXEMPT BECAUSE SYNCING IT WOULD BE A SECURITY BUG, not because it is unimportant.
            // These are the device's own rotating Home Assistant session credential. Syncing them
            // would propagate a LIVE SECRET account-wide, to every device on the household —
            // including devices that are not entitled to that HA box.
            //
            // ⚠️ THE HAZARD IS THE NOISE ITSELF (T, lease watch cont.25). The HA token rotates on
            // its own ~26–30 minute cycle, so while these were unclassified the loud
            // "neither in PREF_KEY_TO_CATEGORY nor SYNC_EXEMPT" DROP fired on that cycle, on every
            // device, forever. That marker asks the reader to "classify it or add it to
            // SYNC_EXEMPT" — and classifying is the obvious-looking way to make a recurring log
            // line stop. **Do not.** Categorising these is precisely the change this comment
            // exists to prevent; the noise was an invitation to introduce the bug.
            //
            // Device-scoped by construction: each device authorises against HA separately, so
            // there is no account-level value here to keep in step. Cf. the credential keys
            // already exempt under ScreensaverPreferences (immich_access_token,
            // google_photos_refresh_token) — same reasoning, different provider.
            addAll(listOf(
                "ha_access_token", "ha_token_expiry",   // local-only (rotating live credential)
                // ⚠️ `ha_refresh_token` was MISSING from the block written to cover exactly this
                // family — the longest-lived secret of the three, left out of its own exemption.
                // Same incompleteness the snapshot_* block above records one level up.
                "ha_refresh_token",                     // local-only (rotating live credential)
                // Native HA login on Fire tablets stores the user's HA account password in the
                // clear here. Whatever else that deserves, it must never become account-wide
                // state — see the security paragraph above, which covers these identically.
                "ha_username", "ha_password"            // local-only (HA account credentials)
            ))
            // ── ConnectionPreferences / VideoFeedPreferences: the HA CONFIG BLOB ──
            //
            // These DO reach the cloud — as part of `user_devices.settings.home_assistant`, via
            // `dispatchNativeSettingsChanged("home_assistant_config")` → JS `handleHaConfigChanged`
            // → `getHomeAssistantSettings()` (the whole core/api/camera/videoFeed/power/alert blob)
            // → `saveDeviceSettings`. So "syncs nowhere" was never true of them; they sync by a
            // DIFFERENT mechanism, and listing them here says which. Precedent: `pipeline_preset`
            // and `device_friendly_name` above are exempt on exactly this grounds.
            //
            // ⚠️ WHAT THIS EXEMPTION DOES NOT PROMISE. The blob dispatch is hand-wired PER WRITER
            // (ACTION_HA_CONFIG_CHANGED, ACTION_HA_DISPLAY_CHANGED, ACTION_VIDEO_FEED_CONFIG_CHANGED,
            // ACTION_API_CHANGED, ACTION_CAMERA_*…). The settings-UI paths dispatch; a writer that
            // does not — an onboarding or native-login path setting `ha_url`/`keep_logged_in`
            // directly — reaches the cloud only at the next boot snapshot. Mapping these to the
            // category instead would make the notifier cover every writer, which is the sync-by-
            // default design; it is deliberately NOT done here because it turns on new uploads and
            // wants its own unit. Recorded so the next reader inherits the gap, not a false
            // all-clear.
            addAll(listOf(
                "ha_url", "ha_base_url", "dashboard_name",       // blob: core
                "use_custom_url", "custom_url", "keep_logged_in",// blob: core
                "video_feed_enabled", "video_feed_config_json"   // blob: videoFeed
            ))
            // ── ConnectionPreferences: pending-login handoff — runtime-state ──
            // One-shot flags that survive activity recreation on low-RAM devices so onboarding can
            // resume where it left off. They describe a moment in ONE device's setup flow; there is
            // no account-level value to keep in step, and syncing them would replay another
            // device's half-finished login.
            addAll(listOf(
                "pending_ha_setup", "pending_ha_login_success",
                "pending_ha_login_url", "pending_ha_login_request"
            ))
            // ── TelemetryDelegate's Lovelace freshness stamp — runtime-state ──
            // Written on EVERY telemetry cycle (persistLovelaceConfig), purely so a crash report
            // can say how fresh the cached dashboard config was. Local by construction, and while
            // unclassified it fired the loud DROP on that same cycle — the recurring-noise hazard
            // the credential block above warns about, and the reason a family gets classified
            // rather than the one key someone happened to see.
            add("lovelace_config_time")
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Debug-only runtime drift guard (SETTINGS_ROBUSTNESS_PLAN Phase 3): every
    // classified (non-suppressed) pref change must be followed by its category
    // dispatch within TRIP_WINDOW_MS, or the tripwire logs an error + toast.
    // Catches notifier regressions during normal dogfooding. Null on release.
    private val tripwire: SettingsSyncTripwire? =
        if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            SettingsSyncTripwire(context, mainHandler) else null

    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val prefs = mutableListOf<SharedPreferences>()
    private val pendingCategories = mutableSetOf<String>()

    // Cloud→Kotlin apply in progress: while > 0, a pref change is an echo of the
    // apply and must not dispatch back out. Incremented by begin/endSettingsApply
    // (JS bridge) and [suppress] (Kotlin boot-restore). Written from the JavaBridge
    // thread and main; @Volatile is sufficient (monotonic guard, not a CAS).
    @Volatile private var applyDepth: Int = 0

    fun start() {
        for (name in SYNCED_PREF_FILES) {
            val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key != null) onKeyChanged(key)
            }
            sp.registerOnSharedPreferenceChangeListener(listener)
            prefs += sp
            listeners += listener
        }
        Log.i(TAG, "Started — watching ${prefs.size} synced prefs file(s), ${PREF_KEY_TO_CATEGORY.size} mapped keys")
    }

    fun stop() {
        prefs.forEachIndexed { i, sp ->
            listeners.getOrNull(i)?.let { sp.unregisterOnSharedPreferenceChangeListener(it) }
        }
        prefs.clear()
        listeners.clear()
        mainHandler.removeCallbacksAndMessages(null)
    }

    /** Run [block] with the notifier suppressed — Kotlin boot-restore / migration
     *  paths that write synced prefs but must not dispatch. */
    fun <T> suppress(block: () -> T): T {
        beginApply()
        try {
            return block()
        } finally {
            endApply()
        }
    }

    // Called from the JS bridge (beginSettingsApply/endSettingsApply) around
    // pushDeviceSettingsToNative, and by [suppress].
    //
    // CRITICAL: SharedPreferences change listeners ALWAYS run on the main thread.
    // A commit/apply from an off-main caller (the JavaBridge thread, where JS
    // setters run) POSTS its listener notification to the main looper — so it
    // arrives AFTER this call chain returns. A synchronous decrement would drop
    // applyDepth to 0 before those notifications run, and every echo would leak.
    // So begin increments synchronously (visible to any same-thread synchronous
    // notification and, via @Volatile, to the later main-thread ones), and end
    // POSTS the decrement to the main looper — enqueued after the apply's own
    // notifications (FIFO), so they observe applyDepth > 0 and suppress, then the
    // decrement runs. Handles both off-main JS applies and on-main Kotlin restore.
    fun beginApply() { applyDepth++ }
    fun endApply() {
        mainHandler.post { if (applyDepth > 0) applyDepth-- }
    }

    private fun onKeyChanged(key: String) {
        if (applyDepth > 0) return                      // echo of a cloud→Kotlin apply
        val category = PREF_KEY_TO_CATEGORY[key] ?: run {
            // Standing Rule #2 (no silent drops): a watched pref that is neither
            // classified NOR in SYNC_EXEMPT changed and went nowhere. SYNC_EXEMPT
            // keys are deliberately local (no marker); anything else is a real gap
            // (the device_friendly_name class — M5). check F′(b) should keep this
            // from firing, but a new unclassified key would surface here loudly
            // instead of silently losing the sync.
            if (key !in SYNC_EXEMPT) {
                Log.w(TAG, "DROP: watched pref '$key' changed but is neither in " +
                    "PREF_KEY_TO_CATEGORY nor SYNC_EXEMPT — its change syncs nowhere. " +
                    "Classify it or add it to SYNC_EXEMPT with a reason.")
            }
            return
        }

        tripwire?.onClassifiedChange(key, category)
        synchronized(pendingCategories) { pendingCategories.add(category) }
        // Debounce: coalesce a burst of key writes (a multi-key dialog save) into
        // one flush that drains all pending categories. Re-arm on each change.
        // onKeyChanged always runs on the main thread (SharedPreferences notifies
        // there), so these Handler ops are already on the right looper.
        mainHandler.removeCallbacks(flushRunnable)
        mainHandler.postDelayed(flushRunnable, DEBOUNCE_MS)
    }

    private val flushRunnable = Runnable {
        val toDispatch: List<String>
        synchronized(pendingCategories) {
            toDispatch = pendingCategories.toList()
            pendingCategories.clear()
        }
        for (category in toDispatch) {
            Log.i(TAG, "Auto-sync dispatch: $category")
            try {
                dispatch(category)
                tripwire?.onDispatched(category)  // only on success — a throw means the sync didn't happen
            } catch (e: Exception) {
                Log.w(TAG, "dispatch($category) failed: ${e.message}")
            }
        }
    }
}

package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * Connection-related preferences for Dashie Kiosk.
 *
 * Manages:
 * - Home Assistant URL and connection settings
 * - Addon mode (none, homeassistant, dev)
 * - API server settings (Fully Kiosk compatible)
 * - SSDP discovery settings
 * - URL Builder settings (base URL, dashboard name, display options)
 *
 * Uses the same SharedPreferences file as HalitePreferences for backward compatibility.
 */
class ConnectionPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_lite_prefs"

        /**
         * Prepend `http://` when an origin carries no scheme — the single guard behind [haUrl].
         *
         * `internal` so its behaviour can be pinned by a unit test: this exists because of a
         * boot crash loop, and "it looked right" is how the same shape reached production three
         * times. Empty stays empty (blank is a legitimate not-configured state that callers
         * already check for, and inventing `http://` for it would turn "unset" into a real
         * request to nowhere).
         */
        @JvmStatic
        internal fun ensureScheme(url: String): String = when {
            url.isEmpty() -> url
            url.startsWith("http://", ignoreCase = true) -> url
            url.startsWith("https://", ignoreCase = true) -> url
            else -> "http://$url"
        }

        /**
         * Announce a scheme repair — **at the read site, never inside [ensureScheme]**.
         *
         * 🔴 The obvious place for this line is the `else` branch above, and that is wrong:
         * [ensureScheme] is a PURE function pinned by `HaUrlSchemeTest`, and putting
         * `android.util.Log` in it fails those tests outright (`isReturnDefaultValues` is not set,
         * so `Log` is unmocked on the plain-JVM path). Tried it; the test caught it. Keeping the
         * repair rule pure and the announcement separate is the shape that survives both.
         *
         * Why it is worth announcing at all (O, 2026-08-04): the repair is otherwise SILENT and is
         * permanent in EFFECT but not in STORAGE. Someone who wrote `192.168.1.5:8123` via a
         * bridge or the `:2323` API gets a working device and never learns the stored value is
         * malformed — so the next consumer that reads that pref without this guard hits the
         * original boot-crash bug again, with nothing in the log to explain it.
         *
         * Not a `DROP:` — T is right that a repair is not a drop. Nothing was dispatched or
         * discarded; a bad input was accepted and corrected.
         */
        private fun announceIfRepaired(raw: String, repaired: String) {
            if (raw.isNotEmpty() && raw != repaired) {
                android.util.Log.i(
                    "ConnectionPreferences",
                    "REPAIR: stored origin \"$raw\" has no scheme — reading it as \"$repaired\". " +
                        "The STORED value is unchanged and still malformed.",
                )
            }
        }

        // HA URL settings
        private const val KEY_HA_URL = "ha_url"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_HA_ENABLED = "ha_enabled"
        private const val KEY_ADVANCED_TABLET_CONTROLS_ENABLED = "advanced_tablet_controls_enabled"
        private const val KEY_ALLOW_OFFLINE_MODE = "allow_offline_mode"
        private const val KEY_HAS_CONNECTED_BEFORE = "has_connected_before"

        // Addon settings
        private const val KEY_ADDON_URL = "addon_url"
        private const val KEY_ADDON_MODE = "addon_mode"
        private const val KEY_DEV_SERVER_URL = "dev_server_url"

        // API settings
        private const val KEY_API_ENABLED = "api_enabled"
        private const val KEY_API_PASSWORD = "api_password"
        private const val KEY_API_PORT = "api_port"

        // Discovery (mDNS)
        private const val KEY_SSDP_ENABLED = "ssdp_enabled"  // Legacy key name, controls mDNS now
        private const val KEY_DEVICE_UUID = "device_uuid"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_FRIENDLY_NAME = "device_friendly_name"

        // Custom (non-HA) URL display
        private const val KEY_USE_CUSTOM_URL = "use_custom_url"
        private const val KEY_CUSTOM_URL = "custom_url"

        // URL Builder preferences
        private const val KEY_AUTO_BUILD_URL = "auto_build_url"
        private const val KEY_HA_BASE_URL = "ha_base_url"
        private const val KEY_DASHBOARD_NAME = "dashboard_name"
        private const val KEY_HIDE_SIDEBAR = "hide_sidebar"
        private const val KEY_HIDE_TABS = "hide_tabs"
        private const val KEY_HIDE_SEARCH = "hide_search"
        private const val KEY_HIDE_ASSISTANT = "hide_assistant"
        private const val KEY_SHOW_FLOATING_BACK_BUTTON = "show_floating_back_button"

        // Addon mode constants
        const val ADDON_MODE_NONE = "none"
        const val ADDON_MODE_HOMEASSISTANT = "homeassistant"
        const val ADDON_MODE_DEV = "dev"

        // Pending HA login state (survives activity recreation for low-RAM devices)
        private const val KEY_PENDING_HA_LOGIN_SUCCESS = "pending_ha_login_success"
        private const val KEY_PENDING_HA_LOGIN_URL = "pending_ha_login_url"

        // Pending intent: user clicked "Connect to Home Assistant" on the
        // Dashie login page. Onboarding reads + clears this on next launch
        // and jumps straight to HA config (skipping scan + welcome).
        private const val KEY_PENDING_HA_SETUP = "pending_ha_setup"
        private const val KEY_PENDING_HA_LOGIN_REQUEST = "pending_ha_login_request"

        // HA Login credentials (for native login on Fire tablets)
        private const val KEY_HA_USERNAME = "ha_username"
        private const val KEY_HA_PASSWORD = "ha_password"
        private const val KEY_USE_NATIVE_LOGIN = "use_native_login"
        private const val KEY_KEEP_LOGGED_IN = "keep_logged_in"

        // HA Access Token (cached from WebView localStorage for background services)
        private const val KEY_HA_ACCESS_TOKEN = "ha_access_token"
        private const val KEY_HA_REFRESH_TOKEN = "ha_refresh_token"
        private const val KEY_HA_TOKEN_EXPIRY = "ha_token_expiry"

        // Supabase JWT (cached from WebView localStorage for authenticated edge function calls)
        private const val KEY_SUPABASE_JWT = "supabase_jwt"
        private const val KEY_SUPABASE_JWT_EXPIRY = "supabase_jwt_expiry"
        // Last time the server CONFIRMED this device still holds a valid session (D5 liveness).
        // Distinct from expiry: a token can be far from expiring and already revoked.
        private const val KEY_SESSION_CHECKED_AT = "supabase_session_checked_at"
        private const val KEY_SUPABASE_USER_ID = "supabase_user_id"

        // Music Player
        private const val KEY_MUSIC_PLAYER_ENABLED = "music_player_enabled"
        private const val KEY_MUSIC_PLAYER_ENTITY_ID = "music_player_entity_id"
        private const val KEY_MUSIC_PLAYER_WAS_SHOWN = "music_player_was_shown"
        private const val KEY_MUSIC_PLAYER_NORMAL_POS = "music_player_normal_pos"
        private const val KEY_MUSIC_PLAYER_MINI_POS = "music_player_mini_pos"
        private const val KEY_MUSIC_PLAYER_LAST_TRACK = "music_player_last_track"
        private const val KEY_MUSIC_PLAYER_LAST_ARTIST = "music_player_last_artist"
        private const val KEY_MUSIC_PLAYER_LAST_ALBUM_ART = "music_player_last_album_art"
        private const val KEY_MUSIC_PLAYER_FULLSCREEN_ON_PLAY = "music_player_fullscreen_on_play"
        private const val KEY_RECENT_MUSIC_PLAYER_ENTITY_IDS = "recent_music_player_entity_ids"
        private const val KEY_MUSIC_PLAYER_DEFAULT_ENTITY_ID = "music_player_default_entity_id"
        private const val KEY_MUSIC_PLAYER_DISPLAY_NAME = "music_player_display_name"
        private const val KEY_MUSIC_USE_HA_INTEGRATION = "music_use_ha_integration"
        private const val KEY_MA_SERVER_URL = "ma_server_url"

        // Speaker-only mode (Sendspin receiver without MA login)
        private const val KEY_MUSIC_SPEAKER_ONLY = "music_speaker_only"
        // User-confirmed MA Sendspin URL for speaker-only mode. Populated by the
        // connection dialog when user enables speaker-only. Empty means fall back
        // to mDNS discovery at connect time.
        private const val KEY_SPEAKER_ONLY_MA_URL = "speaker_only_ma_url"

        // Music Assistant API (direct REST API with JWT auth)
        private const val KEY_MA_API_TOKEN = "ma_api_token"
        private const val KEY_MA_API_URL = "ma_api_url"
        private const val KEY_MA_TOKEN_EXPIRED = "ma_token_expired"
        private const val KEY_MA_TOKEN_LONG_LIVED = "ma_token_long_lived"
        // Active music profile (family member ID for per-person music scoping)
        private const val KEY_ACTIVE_MUSIC_PROFILE_ID = "active_music_profile_id"

        // Default values
        const val DEFAULT_ADDON_PORT = 8099
        const val DEFAULT_DEV_SERVER_URL = "http://192.168.1.100:8099"
        const val DEFAULT_HA_URL = "http://192.168.1.x:8123"
        const val DEFAULT_API_PASSWORD = "" // No password by default (user should set one)
        const val DEFAULT_API_PORT = 2323 // Standard Fully Kiosk Browser port
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ========== Home Assistant URL ==========

    /**
     * Get the configured Home Assistant URL
     */
    // Trimmed on both get and set: a stray trailing space (e.g. "…:8123 ")
    // makes java.net.URL throw MalformedURLException ("For input string: 8123 ")
    // in the ping loop and corrupts the appended dashboard path on load.
    // Trimming on get repairs already-stored bad values without the user
    // re-entering the URL.
    //
    // 🔴 A MISSING SCHEME is repaired the same way, and it is not cosmetic: OkHttp's
    // `Request.Builder.url()` THROWS on a scheme-less origin, and `HaAssistClient.connect()`
    // makes that call on the MAIN thread during `initializeVoicePipeline` — so a stored
    // "192.168.1.5:8123" was a BOOT CRASH LOOP, ~10 s per cycle (T, 2026-08-02). Two silent
    // variants of the same shape preceded it in HaEventSubscriber.
    //
    // Why HERE and not in the settings screen that already normalizes: `SettingsHaUrlFragment`
    // was the ONLY writer that normalized, while three others set this value raw — the JS
    // bridge (JsBridgeHaDelegate, JsBridgeHaliteDelegate) and the :2323 HTTP API
    // (DashieApiCallbacksImpl). The invariant was enforced at a SCREEN instead of at the VALUE,
    // so every non-UI path could write the crashing state. One holder, per the seam rule.
    //
    // ⚠️ Repairing on GET is the load-bearing half, exactly as for trim: a device already in
    // the crash loop cannot realistically be walked through a settings screen, and this heals
    // it on the next read with no user action and no migration.
    //
    // Deliberately ONLY the scheme — not the fragment's full reparse. A stored haUrl may
    // legitimately carry a dashboard path and query ("…:8123/lovelace-home?kiosk"), and
    // round-tripping it through java.net.URL here would risk rewriting values that work today.
    // Fixing the crash must not become a URL rewrite.
    var haUrl: String
        get() {
            val raw = (prefs.getString(KEY_HA_URL, DEFAULT_HA_URL) ?: DEFAULT_HA_URL).trim()
            return ensureScheme(raw).also { announceIfRepaired(raw, it) }
        }
        set(value) { prefs.edit().putString(KEY_HA_URL, ensureScheme(value.trim())).commit() }

    /**
     * Check if initial setup has been completed
     */
    var isSetupComplete: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETE, false)
        set(value) { prefs.edit().putBoolean(KEY_SETUP_COMPLETE, value).commit() }

    // Per-device flag — read by Kotlin schema conditions to gate HA/advanced UI.
    // Default true preserves behavior on upgrade; non-HA onboarding (future)
    // flips it false for users who skip HA setup.
    var haEnabled: Boolean
        get() = prefs.getBoolean(KEY_HA_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_HA_ENABLED, value).commit() }

    // User chose "Use Dashie without Internet" on the WiFi setup screen.
    // Set true when the device's network reaches Dashie but fails Android's
    // captive-portal validation (NET_CAPABILITY_VALIDATED) — e.g. a network
    // that whitelists dashieapp.com but blocks Google's connectivity-check
    // endpoints. Once set, the boot-time no-internet redirect is skipped so
    // the user isn't trapped on the WiFi gate every reboot. Re-openable from
    // Settings → Change WiFi Network.
    var allowOfflineMode: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_OFFLINE_MODE, false)
        set(value) { prefs.edit().putBoolean(KEY_ALLOW_OFFLINE_MODE, value).commit() }

    // True once the device has reached validated internet at least once (set at
    // boot in MainStartupInitializer). Used by WifiSetupActivity to offer a silent
    // "reconnecting" grace period on a no-internet boot instead of immediately
    // showing the Connect-to-WiFi prompt — covers the common case where the app
    // (set as the launcher / home app) boots faster than WiFi can re-associate to
    // a saved network. A fresh device that has never connected keeps the original
    // immediate-prompt behavior so first-time setup isn't delayed.
    var hasConnectedBefore: Boolean
        get() = prefs.getBoolean(KEY_HAS_CONNECTED_BEFORE, false)
        set(value) { prefs.edit().putBoolean(KEY_HAS_CONNECTED_BEFORE, value).commit() }

    // Non-HA users opt into advanced tablet-control sections (layout,
    // screensaver, sleep options, screen locking, etc.) via a toggle on
    // the Advanced page. HA users always see those sections regardless
    // of this flag.
    var advancedTabletControlsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADVANCED_TABLET_CONTROLS_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ADVANCED_TABLET_CONTROLS_ENABLED, value).commit() }

    // ========== Addon Settings ==========

    /**
     * Optional Dashie Addon URL for timer overlay features.
     * If empty/blank, loads HA URL directly (original behavior).
     * If set, loads dashie-lite.html from addon with HA in iframe.
     * Example: "http://192.168.1.100:8099" or "http://macbook.local:8099"
     *
     * @deprecated Use addonMode and getAddonServerUrl() instead
     */
    var addonUrl: String
        get() = prefs.getString(KEY_ADDON_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_ADDON_URL, value).commit() }

    /**
     * Check if addon/overlay is available. Always true now that overlay is bundled in APK.
     */
    val hasAddonUrl: Boolean
        get() = true

    /**
     * Addon mode: controls how/if the Dashie addon server is used
     * - "none": Load HA directly (no addon features like timers/voice)
     * - "homeassistant": Use HA addon at homeassistant.local:8099
     * - "dev": Use custom development server URL
     */
    var addonMode: String
        get() {
            // Check for migration from old addonUrl preference
            val stored = prefs.getString(KEY_ADDON_MODE, null)
            if (stored != null) return stored

            // Migrate from old addonUrl if set
            val oldAddonUrl = prefs.getString(KEY_ADDON_URL, "") ?: ""
            if (oldAddonUrl.isNotBlank()) {
                // Had a custom addon URL - migrate to dev mode
                prefs.edit()
                    .putString(KEY_ADDON_MODE, ADDON_MODE_DEV)
                    .putString(KEY_DEV_SERVER_URL, oldAddonUrl)
                    .commit()
                return ADDON_MODE_DEV
            }

            return ADDON_MODE_NONE
        }
        set(value) { prefs.edit().putString(KEY_ADDON_MODE, value).commit() }

    /**
     * Development server URL (used when addonMode == "dev")
     * Example: "http://192.168.1.100:8099" or "http://macbook.local:8099"
     */
    var devServerUrl: String
        get() = prefs.getString(KEY_DEV_SERVER_URL, DEFAULT_DEV_SERVER_URL) ?: DEFAULT_DEV_SERVER_URL
        set(value) { prefs.edit().putString(KEY_DEV_SERVER_URL, value).commit() }

    /**
     * Get the overlay server URL. Always returns a URL since the overlay webapp
     * is bundled in APK assets and served via shouldInterceptRequest.
     * The URL is a virtual address used for matching in serveOverlayFromAssets().
     */
    fun getAddonServerUrl(): String {
        return "http://dashie-kiosk-overlay.local:$DEFAULT_ADDON_PORT"
    }

    /**
     * Get the HA origin (protocol + host + port) for same-origin shell page.
     * Returns null if no HA URL is configured yet (pre-onboarding).
     */
    fun getHaOrigin(): String? {
        val baseUrl = haBaseUrl.trimEnd('/')
        if (baseUrl.isNotEmpty()) return baseUrl
        // Try to extract origin from manual haUrl
        if (haUrl.isNotEmpty() && haUrl != DEFAULT_HA_URL) {
            try {
                val parsed = java.net.URL(haUrl)
                return if (parsed.port == -1 || parsed.port == parsed.defaultPort) {
                    "${parsed.protocol}://${parsed.host}"
                } else {
                    "${parsed.protocol}://${parsed.host}:${parsed.port}"
                }
            } catch (_: Exception) { /* fall through */ }
        }
        return null
    }

    /**
     * The ONE canonical origin for HA AUTHENTICATION.
     *
     * OAuth client_id + redirect_uri, hassTokens injection, and token refresh must
     * ALL key off this single value, or they drift and break. HA's frontend derives
     * its OAuth client_id from window.location.origin — and the HA iframe loads from
     * this origin (the proxy haBaseUrl when set, else the local haUrl) — so a token
     * MUST be minted for, and refreshed against, exactly this origin. A mismatch is
     * the "logged out over the proxy / refresh 400 invalid_request" bug: previously
     * some paths minted against the local haUrl while the iframe ran at the proxy.
     *
     * IMPORTANT: this is auth-only. Direct native LAN API calls keep using haUrl.
     * For local-only setups (no haBaseUrl) this returns the local origin unchanged,
     * so their behaviour does not change.
     */
    fun getAuthOrigin(): String? = getHaOrigin()

    /**
     * Build the canonical `hassTokens` JSON blob that HA reads from localStorage.
     * Single source of truth so hassUrl/clientId can't diverge across the many
     * login paths. Defaults read from prefs; callers that have fresher tokens
     * (e.g. right after an OAuth exchange) can pass them explicitly.
     *
     * Returns null if there's no auth origin yet (pre-onboarding).
     */
    fun buildHassTokensJson(
        origin: String? = getAuthOrigin(),
        accessToken: String = haAccessToken,
        refreshToken: String = haRefreshToken,
        expiry: Long = haTokenExpiry
    ): String? {
        val o = origin?.trimEnd('/') ?: return null
        val expiresIn = ((expiry - System.currentTimeMillis()) / 1000).coerceAtLeast(60L)
        return org.json.JSONObject().apply {
            put("hassUrl", o)
            put("clientId", "$o/")
            put("access_token", accessToken)
            put("refresh_token", refreshToken)
            put("expires_in", expiresIn)
            put("expires", expiry)
        }.toString()
    }

    /**
     * Get ALL possible HA origins (proxy + local).
     * When haBaseUrl is a proxy (e.g. https://ha.dashieapp.com) and haUrl is local
     * (e.g. http://192.168.x.x:8123), both origins need to be recognized for
     * shell page matching, asset serving, and URL interception.
     */
    fun getAllHaOrigins(): List<String> {
        val origins = mutableListOf<String>()
        val baseUrl = haBaseUrl.trimEnd('/')
        if (baseUrl.isNotEmpty()) origins.add(baseUrl)
        if (haUrl.isNotEmpty() && haUrl != DEFAULT_HA_URL) {
            try {
                val parsed = java.net.URL(haUrl)
                val origin = if (parsed.port == -1 || parsed.port == parsed.defaultPort) {
                    "${parsed.protocol}://${parsed.host}"
                } else {
                    "${parsed.protocol}://${parsed.host}:${parsed.port}"
                }
                if (origin !in origins) origins.add(origin)
            } catch (_: Exception) { /* skip */ }
        }
        return origins
    }

    /**
     * Get the shell page URL. Uses same-origin URL (at HA base) when HA is configured,
     * falls back to virtual overlay URL for pre-onboarding/unconfigured state.
     *
     * Same-origin shell eliminates HttpURLConnection proxy for HA HTML pages,
     * fixing Fail2Ban triggers, ERR_BLOCKED_BY_RESPONSE, and SameSite cookie issues.
     */
    fun getShellPageUrl(): String {
        // Return HA-origin URL when HA is configured, so the shell and HA iframe
        // are same-origin. This fixes ingress cookie/WebSocket issues.
        // The shell HTML is loaded via loadDataWithBaseURL (bypasses shouldInterceptRequest),
        // and <base href> in kiosk-shell.html ensures CSS/JS load from the virtual URL.
        // Falls back to virtual URL for pre-onboarding (no HA configured yet).
        val haOrigin = getHaOrigin()
        return if (haOrigin != null) {
            "$haOrigin/kiosk-shell.html"
        } else {
            "${getAddonServerUrl()}/kiosk-shell.html"
        }
    }

    /**
     * Check if addon is enabled. Always true now that overlay is bundled in APK.
     */
    val isAddonEnabled: Boolean
        get() = true

    // ========== API Settings (Fully Kiosk Compatible) ==========

    /**
     * Home Assistant API enabled (Fully Kiosk compatible REST API on port 2323)
     * Default: false (user must opt-in to enable API)
     */
    var apiEnabled: Boolean
        get() = prefs.getBoolean(KEY_API_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_API_ENABLED, value).commit() }

    /**
     * Home Assistant API password (for authentication)
     * Used with query parameter: ?password=[this value]
     */
    var apiPassword: String
        get() = prefs.getString(KEY_API_PASSWORD, DEFAULT_API_PASSWORD) ?: DEFAULT_API_PASSWORD
        set(value) { prefs.edit().putString(KEY_API_PASSWORD, value).commit() }

    /**
     * Home Assistant API port (Fully Kiosk compatible REST API)
     * Default: 2323 (standard Fully Kiosk Browser port)
     * Users can customize this to avoid conflicts or use alongside Fully Kiosk Browser
     */
    var apiPort: Int
        get() = prefs.getInt(KEY_API_PORT, DEFAULT_API_PORT)
        set(value) { prefs.edit().putInt(KEY_API_PORT, value).commit() }

    // ========== Discovery (mDNS) ==========

    /**
     * Discovery enabled (allows Home Assistant to auto-discover this device via mDNS)
     * Default: true when API is enabled
     */
    var discoveryEnabled: Boolean
        get() = prefs.getBoolean(KEY_SSDP_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_SSDP_ENABLED, value).commit() }

    /**
     * Unique device UUID for mDNS discovery.
     * Generated once and persisted.
     */
    val deviceUuid: String
        get() {
            var uuid = prefs.getString(KEY_DEVICE_UUID, null)
            if (uuid == null) {
                uuid = java.util.UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_UUID, uuid).commit()
            }
            return uuid
        }

    /**
     * User-friendly device name for mDNS discovery.
     * Defaults to device model name, but can be customized.
     */
    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, null) ?: android.os.Build.MODEL
        set(value) { prefs.edit().putString(KEY_DEVICE_NAME, value).commit() }

    /**
     * Human-friendly device name (e.g., "Mio 15\" Dashie") for display and Sendspin registration.
     * Set during onboarding from HA device registry, or edited in Settings > System.
     * Empty string means not yet configured — caller should fall back to other sources.
     */
    var deviceFriendlyName: String
        get() = prefs.getString(KEY_DEVICE_FRIENDLY_NAME, null) ?: ""
        set(value) { prefs.edit().putString(KEY_DEVICE_FRIENDLY_NAME, value).apply() }

    // ========== URL Builder Preferences ==========

    /**
     * Auto-build URL mode enabled
     * When true, the URL is constructed from components (base URL, dashboard name, parameters)
     */
    var autoBuildUrl: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BUILD_URL, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_BUILD_URL, value).commit() }

    /**
     * Home Assistant base URL (e.g., "http://192.168.1.50:8123")
     * Used when auto-build URL is enabled
     */
    // Trimmed on get/set — see [haUrl] for the trailing-space rationale.
    var haBaseUrl: String
        get() = (prefs.getString(KEY_HA_BASE_URL, "") ?: "").trim()
        set(value) { prefs.edit().putString(KEY_HA_BASE_URL, value.trim()).commit() }

    /**
     * Dashboard name/path (e.g., "lerch-dashboard")
     * Used when auto-build URL is enabled
     */
    var dashboardName: String
        get() = prefs.getString(KEY_DASHBOARD_NAME, "") ?: ""
        set(value) { prefs.edit().putString(KEY_DASHBOARD_NAME, value).commit() }

    /**
     * Hide sidebar via native HA URL parameter + CSS injection reinforcement.
     * URL parameter ensures HA calculates layout correctly from the start.
     * CSS injection provides fallback/reinforcement for complete hiding.
     */
    var hideSidebar: Boolean
        get() = prefs.getBoolean(KEY_HIDE_SIDEBAR, true)
        set(value) { prefs.edit().putBoolean(KEY_HIDE_SIDEBAR, value).commit() }

    /**
     * Hide tabs/header via native HA URL parameter + CSS injection reinforcement.
     * Default: false (show tabs by default so users can navigate between views)
     */
    var hideTabs: Boolean
        get() = prefs.getBoolean(KEY_HIDE_TABS, false)
        set(value) { prefs.edit().putBoolean(KEY_HIDE_TABS, value).commit() }

    /**
     * Hide search button in HA header via CSS injection.
     * Default: true (hidden) — most kiosk/dashboard setups don't need search.
     */
    var hideSearch: Boolean
        get() = prefs.getBoolean(KEY_HIDE_SEARCH, true)
        set(value) { prefs.edit().putBoolean(KEY_HIDE_SEARCH, value).commit() }

    /**
     * Hide assistant button in HA header via CSS injection.
     * Default: true (hidden) — Dashie provides its own voice assistant.
     */
    var hideAssistant: Boolean
        get() = prefs.getBoolean(KEY_HIDE_ASSISTANT, true)
        set(value) { prefs.edit().putBoolean(KEY_HIDE_ASSISTANT, value).commit() }

    /**
     * Show a floating back-to-home button in the top-right of HA pages
     * when the user has navigated away from their home dashboard
     * (e.g. tap_action: navigate /energy, /config). Rescues users whose
     * cards open non-Lovelace HA pages where Dashie's chrome hiding
     * leaves no visible way back. Default: false (off).
     */
    var showFloatingBackButton: Boolean
        get() = prefs.getBoolean(KEY_SHOW_FLOATING_BACK_BUTTON, false)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_FLOATING_BACK_BUTTON, value).commit() }

    /**
     * Build the full URL from components
     * Returns the constructed URL if auto-build is enabled and components are set,
     * otherwise returns the manual haUrl
     */
    fun buildFullUrl(): String {
        if (!autoBuildUrl || haBaseUrl.isEmpty()) {
            return haUrl
        }

        val baseUrl = haBaseUrl.trimEnd('/')
        val dashboard = dashboardName.trim().trimStart('/')

        // Build URL with optional dashboard path
        return if (dashboard.isNotEmpty()) {
            "$baseUrl/$dashboard"
        } else {
            baseUrl
        }
    }

    /**
     * Parse an existing URL into components.
     * Used to populate the URL builder fields from a manually entered URL.
     *
     * Note: Display options (hide sidebar, tabs, etc.) are NOT parsed from URL params.
     * They are controlled via CSS injection from the sidebar Settings > UI section.
     */
    fun parseUrl(url: String) {
        try {
            // Strip any query parameters - we don't use them anymore
            val cleanUrl = url.substringBefore("?")
            val parsed = java.net.URL(cleanUrl)

            // Extract base URL (protocol + host + port)
            haBaseUrl = if (parsed.port == -1 || parsed.port == parsed.defaultPort) {
                "${parsed.protocol}://${parsed.host}"
            } else {
                "${parsed.protocol}://${parsed.host}:${parsed.port}"
            }

            // Extract dashboard name from path (remove leading slash)
            dashboardName = parsed.path.trimStart('/').takeIf { it.isNotEmpty() } ?: ""

            // Note: URL parameters for hide_sidebar, hide_header, etc. are ignored.
            // Display options are controlled via CSS injection (Settings > UI section).

        } catch (e: Exception) {
            // If parsing fails, just use the URL as-is
            haBaseUrl = url.substringBefore("?")
            dashboardName = ""
        }
    }

    // ========== Custom (non-HA) URL Display ==========

    /**
     * When true, display a custom URL instead of the HA dashboard.
     * The HA iframe is hidden and a separate iframe shows the custom URL.
     * HA remains connected as a backend for voice, entities, etc.
     */
    var useCustomUrl: Boolean
        get() = prefs.getBoolean(KEY_USE_CUSTOM_URL, false)
        set(value) { prefs.edit().putBoolean(KEY_USE_CUSTOM_URL, value).commit() }

    /**
     * The custom URL to display when useCustomUrl is true.
     * Can be any web URL (e.g., a self-hosted dashboard, web app, etc.)
     */
    var customUrl: String
        get() = prefs.getString(KEY_CUSTOM_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_CUSTOM_URL, value).commit() }

    /**
     * A configured "host your own dashboard" device: kiosk-style, but pointed at a
     * non-HA website instead of Home Assistant. haEnabled=false by design, yet it is
     * every bit as wall-mounted as an HA kiosk — so the non-HA sidebar force-pin and
     * the hamburger's advanced-items gate must treat it as a kiosk, not as a
     * "family tablet without HA". Single source for that classification: the popout
     * gate (HamburgerPopout) and both force-pin sites (NativeSidebarController,
     * JsBridgeSystemDelegate.setHaEnabled) all read this.
     */
    val isCustomUrlKiosk: Boolean
        get() = useCustomUrl && customUrl.isNotEmpty()

    // ========== HA Login Credentials (Fire Tablet) ==========

    /**
     * HA username for native login (Fire tablet workaround)
     * Stored to allow auto-login after app restart
     */
    var haUsername: String
        get() = prefs.getString(KEY_HA_USERNAME, "") ?: ""
        set(value) { prefs.edit().putString(KEY_HA_USERNAME, value).commit() }

    /**
     * HA password for native login (Fire tablet workaround)
     * Note: Stored in plain text in SharedPreferences (private mode)
     */
    var haPassword: String
        get() = prefs.getString(KEY_HA_PASSWORD, "") ?: ""
        set(value) { prefs.edit().putString(KEY_HA_PASSWORD, value).commit() }

    /**
     * Use native login screen instead of WebView login
     * Auto-enabled on Fire tablets, can be toggled in settings
     */
    var useNativeLogin: Boolean
        get() = prefs.getBoolean(KEY_USE_NATIVE_LOGIN,
            android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true))
        set(value) { prefs.edit().putBoolean(KEY_USE_NATIVE_LOGIN, value).commit() }

    /**
     * Keep user logged in (auto-login with stored credentials)
     * When true, the app will automatically attempt login on startup
     */
    var keepLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_LOGGED_IN, true)
        set(value) { prefs.edit().putBoolean(KEY_KEEP_LOGGED_IN, value).commit() }

    fun hasStoredCredentials(): Boolean {
        return haUsername.isNotEmpty() && haPassword.isNotEmpty()
    }

    fun shouldAutoLogin(): Boolean {
        return keepLoggedIn && hasStoredCredentials()
    }

    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_HA_USERNAME)
            .remove(KEY_HA_PASSWORD)
            .commit()
    }

    // ========== Pending HA Login (survives activity recreation) ==========

    /**
     * Flag indicating HA login succeeded but the JS callback may not have fired
     * (e.g., activity was recreated due to memory pressure on low-RAM devices).
     * The onboarding JS checks this on init to skip past the login step.
     */
    var pendingHaLoginSuccess: Boolean
        get() = prefs.getBoolean(KEY_PENDING_HA_LOGIN_SUCCESS, false)
        set(value) { prefs.edit().putBoolean(KEY_PENDING_HA_LOGIN_SUCCESS, value).commit() }

    /**
     * The HA URL that was being used during the pending login.
     * Saved so onboarding can restore it after activity recreation.
     */
    var pendingHaLoginUrl: String
        get() = prefs.getString(KEY_PENDING_HA_LOGIN_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PENDING_HA_LOGIN_URL, value).commit() }

    fun clearPendingHaLogin() {
        prefs.edit()
            .remove(KEY_PENDING_HA_LOGIN_SUCCESS)
            .remove(KEY_PENDING_HA_LOGIN_URL)
            .commit()
    }

    /**
     * One-shot flag set by the login page's "Connect to Home Assistant"
     * link to tell onboarding (after sign-out + reload) to skip the
     * network scan and jump straight to the HA config screen.
     * Onboarding should clear this immediately after reading.
     */
    var pendingHaSetup: Boolean
        get() = prefs.getBoolean(KEY_PENDING_HA_SETUP, false)
        set(value) { prefs.edit().putBoolean(KEY_PENDING_HA_SETUP, value).commit() }

    /**
     * One-shot flag set by the "Sign in to Home Assistant" action in the
     * native HA settings page. SettingsActivity finishes immediately;
     * MainActivity.onResume reads + clears this flag and launches the
     * HA login flow. Avoids the WebView.post + finish() race that loses
     * evaluateJavascript calls when the host activity is tearing down.
     */
    var pendingHaLoginRequest: Boolean
        get() = prefs.getBoolean(KEY_PENDING_HA_LOGIN_REQUEST, false)
        set(value) { prefs.edit().putBoolean(KEY_PENDING_HA_LOGIN_REQUEST, value).commit() }

    // ========== HA Access Token (for background services) ==========

    /**
     * Cached HA access token.
     * Extracted from WebView localStorage for background services
     * that don't have WebView access (like DreamService/screensaver).
     */
    var haAccessToken: String
        get() = prefs.getString(KEY_HA_ACCESS_TOKEN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_HA_ACCESS_TOKEN, value).commit() }

    var haRefreshToken: String
        get() = prefs.getString(KEY_HA_REFRESH_TOKEN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_HA_REFRESH_TOKEN, value).commit() }

    var haTokenExpiry: Long
        get() = prefs.getLong(KEY_HA_TOKEN_EXPIRY, 0L)
        set(value) { prefs.edit().putLong(KEY_HA_TOKEN_EXPIRY, value).commit() }

    val hasHaAccessToken: Boolean
        get() = haAccessToken.isNotEmpty()

    val isHaTokenExpired: Boolean
        get() = haTokenExpiry > 0 && System.currentTimeMillis() > haTokenExpiry

    fun updateHaTokens(accessToken: String, refreshToken: String?, expiresIn: Long = 1800) {
        haAccessToken = accessToken
        if (refreshToken != null) {
            haRefreshToken = refreshToken
        }
        haTokenExpiry = System.currentTimeMillis() + (expiresIn * 1000)
    }

    fun clearHaTokens() {
        prefs.edit()
            .remove(KEY_HA_ACCESS_TOKEN)
            .remove(KEY_HA_REFRESH_TOKEN)
            .remove(KEY_HA_TOKEN_EXPIRY)
            .commit()
    }

    // ========== Supabase JWT (for authenticated edge function calls from Kotlin) ==========

    var supabaseJwt: String
        get() = prefs.getString(KEY_SUPABASE_JWT, "") ?: ""
        set(value) { prefs.edit().putString(KEY_SUPABASE_JWT, value).commit() }

    /**
     * When the server last CONFIRMED this device's session is still valid (D5 liveness check).
     *
     * NOT the same as expiry. `refresh_jwt` is the only endpoint that verifies the device is still
     * on the account; every other edge function accepts a signature-valid token. So a revoked
     * device holds a token that is nowhere near expiring and yet completely dead — it just doesn't
     * know it. KioskJwtRefresher uses this to re-verify on a cadence rather than only near expiry.
     */
    var sessionCheckedAtMs: Long
        get() = prefs.getLong(KEY_SESSION_CHECKED_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_SESSION_CHECKED_AT, value).commit() }

    var supabaseJwtExpiry: Long
        get() = prefs.getLong(KEY_SUPABASE_JWT_EXPIRY, 0L)
        set(value) { prefs.edit().putLong(KEY_SUPABASE_JWT_EXPIRY, value).commit() }

    var supabaseUserId: String
        get() = prefs.getString(KEY_SUPABASE_USER_ID, "") ?: ""
        set(value) { prefs.edit().putString(KEY_SUPABASE_USER_ID, value).commit() }

    val hasSupabaseJwt: Boolean
        get() = supabaseJwt.isNotEmpty()

    val isSupabaseJwtExpired: Boolean
        get() = supabaseJwtExpiry > 0 && System.currentTimeMillis() > (supabaseJwtExpiry - 5 * 60 * 1000)

    fun clearSupabaseJwt() {
        prefs.edit()
            .remove(KEY_SUPABASE_JWT)
            .remove(KEY_SUPABASE_JWT_EXPIRY)
            .remove(KEY_SUPABASE_USER_ID)
            .commit()
    }

    // ========== Music Player ==========

    var musicPlayerEnabled: Boolean
        get() = prefs.getBoolean(KEY_MUSIC_PLAYER_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_MUSIC_PLAYER_ENABLED, value).commit() }

    /** Speaker-only mode: device acts as a Sendspin receiver without MA login or player UI. */
    var musicSpeakerOnly: Boolean
        get() = prefs.getBoolean(KEY_MUSIC_SPEAKER_ONLY, false)
        set(value) { prefs.edit().putBoolean(KEY_MUSIC_SPEAKER_ONLY, value).commit() }

    /**
     * User-confirmed MA Sendspin URL for speaker-only mode (format "http://host:port/path"
     * or just "host:port"). Populated by the connection dialog when the user enables
     * speaker-only. Empty means fall back to mDNS discovery at connect time.
     */
    var speakerOnlyMaUrl: String
        get() = prefs.getString(KEY_SPEAKER_ONLY_MA_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_SPEAKER_ONLY_MA_URL, value).commit() }

    var musicPlayerEntityId: String
        get() = prefs.getString(KEY_MUSIC_PLAYER_ENTITY_ID, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MUSIC_PLAYER_ENTITY_ID, value).commit() }

    /** Whether the music player was shown when the app last closed (for restore on restart). */
    var musicPlayerWasShown: Boolean
        get() = prefs.getBoolean(KEY_MUSIC_PLAYER_WAS_SHOWN, false)
        set(value) { prefs.edit().putBoolean(KEY_MUSIC_PLAYER_WAS_SHOWN, value).commit() }

    /** Saved normal-mode position as "x,y" or empty string. */
    var musicPlayerNormalPos: String
        get() = prefs.getString(KEY_MUSIC_PLAYER_NORMAL_POS, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MUSIC_PLAYER_NORMAL_POS, value).commit() }

    /** Saved minimized-mode position as "x,y" or empty string. */
    var musicPlayerMiniPos: String
        get() = prefs.getString(KEY_MUSIC_PLAYER_MINI_POS, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MUSIC_PLAYER_MINI_POS, value).commit() }

    /** Last known track name (for showing cached data on restart before MA connects). */
    var musicPlayerLastTrack: String
        get() = prefs.getString(KEY_MUSIC_PLAYER_LAST_TRACK, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MUSIC_PLAYER_LAST_TRACK, value).commit() }

    /** Last known artist name. */
    var musicPlayerLastArtist: String
        get() = prefs.getString(KEY_MUSIC_PLAYER_LAST_ARTIST, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MUSIC_PLAYER_LAST_ARTIST, value).commit() }

    /** Last known album art URL. */
    var musicPlayerLastAlbumArt: String
        get() = prefs.getString(KEY_MUSIC_PLAYER_LAST_ALBUM_ART, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MUSIC_PLAYER_LAST_ALBUM_ART, value).commit() }

    /** Whether to open music player in full screen mode when playback starts. */
    var musicPlayerFullScreenOnPlay: Boolean
        get() = prefs.getBoolean(KEY_MUSIC_PLAYER_FULLSCREEN_ON_PLAY, false)
        set(value) { prefs.edit().putBoolean(KEY_MUSIC_PLAYER_FULLSCREEN_ON_PLAY, value).commit() }

    /** Whether to show the music player on the screensaver overlay panel. */
    var musicPlayerShowWithScreensaver: Boolean
        get() = prefs.getBoolean("music_player_show_with_screensaver", true)
        set(value) { prefs.edit().putBoolean("music_player_show_with_screensaver", value).commit() }

    /** The explicitly chosen default speaker (persisted across restarts). */
    var musicPlayerDefaultEntityId: String
        get() = prefs.getString(KEY_MUSIC_PLAYER_DEFAULT_ENTITY_ID, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MUSIC_PLAYER_DEFAULT_ENTITY_ID, value).commit() }

    /** The friendly display name of the selected default speaker. */
    var musicPlayerDisplayName: String
        get() = prefs.getString(KEY_MUSIC_PLAYER_DISPLAY_NAME, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MUSIC_PLAYER_DISPLAY_NAME, value).commit() }

    /** Atomically update both the active entity ID and its display name. */
    fun setMusicPlayerEntityAndName(entityId: String, displayName: String) {
        musicPlayerEntityId = entityId
        musicPlayerDisplayName = displayName
        addRecentMusicPlayer(entityId)
    }

    /** Recently used music player entity IDs (comma-separated, most recent first, max 5). */
    var recentMusicPlayerEntityIds: String
        get() = prefs.getString(KEY_RECENT_MUSIC_PLAYER_ENTITY_IDS, "") ?: ""
        set(value) { prefs.edit().putString(KEY_RECENT_MUSIC_PLAYER_ENTITY_IDS, value).commit() }

    /** Add an entity to the recently used list (dedupes, keeps max 5). */
    fun addRecentMusicPlayer(entityId: String) {
        if (entityId.isBlank()) return
        val current = getRecentMusicPlayers().toMutableList()
        current.remove(entityId)
        current.add(0, entityId)
        recentMusicPlayerEntityIds = current.take(5).joinToString(",")
    }

    /** Get recently used music player entity IDs. */
    fun getRecentMusicPlayers(): List<String> {
        val raw = recentMusicPlayerEntityIds
        return if (raw.isBlank()) emptyList() else raw.split(",").filter { it.isNotBlank() }
    }

    /** When true, route all music commands through HA JS bridge (standard media_player).
     *  When false, use direct MA streams server for music control. */
    var musicUseHaIntegration: Boolean
        get() = prefs.getBoolean(KEY_MUSIC_USE_HA_INTEGRATION, true)
        set(value) {
            val changed = value != musicUseHaIntegration
            prefs.edit().putBoolean(KEY_MUSIC_USE_HA_INTEGRATION, value).commit()
            if (changed) {
                // Entity IDs differ between HA and MA modes — clear stale values
                musicPlayerEntityId = ""
                musicPlayerDefaultEntityId = ""
                recentMusicPlayerEntityIds = ""
            }
        }

    /** Persisted MA streams server URL so the speaker picker works even without active playback. */
    var maServerUrl: String
        get() = prefs.getString(KEY_MA_SERVER_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MA_SERVER_URL, value).commit() }

    /** Set the default music player entity. */
    fun setDefaultMusicPlayer(entityId: String) {
        musicPlayerEntityId = entityId
    }

    /**
     * Get the effective music player entity ID.
     * Returns the base player ID (e.g. media_player.mio_15_dashie_speaker).
     * This is used by both the MA API (which uses base IDs) and HA JS subscription.
     */
    fun getEffectiveMusicPlayerEntityId(): String {
        return musicPlayerDefaultEntityId.ifEmpty { musicPlayerEntityId }
    }

    // ========== Music Assistant API (Direct REST with JWT) ==========

    /** JWT token from MA login WebView flow. */
    var maApiToken: String
        get() = prefs.getString(KEY_MA_API_TOKEN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MA_API_TOKEN, value).commit() }

    /** MA server base URL for direct API calls (e.g. "http://192.168.1.50:8095"). */
    var maApiUrl: String
        get() = prefs.getString(KEY_MA_API_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MA_API_URL, value).commit() }

    /** Whether a valid MA API token is stored. */
    val hasMaApiToken: Boolean
        get() = maApiToken.isNotEmpty()

    /**
     * True after a 401 marked the MA token expired; cleared on a fresh login.
     * Lets the control center surface a "session expired — sign in" state that
     * the in-memory auth flag alone can't reach from ControlCenterStateProvider.
     */
    var maTokenExpired: Boolean
        get() = prefs.getBoolean(KEY_MA_TOKEN_EXPIRED, false)
        set(value) { prefs.edit().putBoolean(KEY_MA_TOKEN_EXPIRED, value).commit() }

    /**
     * False when the last MA login could only obtain a short-lived (non-admin)
     * token that will expire — the long-lived upgrade in MaLoginActivity failed.
     * Default true so existing installs aren't flagged.
     */
    var maTokenLongLived: Boolean
        get() = prefs.getBoolean(KEY_MA_TOKEN_LONG_LIVED, true)
        set(value) { prefs.edit().putBoolean(KEY_MA_TOKEN_LONG_LIVED, value).commit() }

    /** Active music profile: Dashie family member ID for per-person music scoping. */
    var activeMusicProfileId: String
        get() = prefs.getString(KEY_ACTIVE_MUSIC_PROFILE_ID, "") ?: ""
        set(value) { prefs.edit().putString(KEY_ACTIVE_MUSIC_PROFILE_ID, value).commit() }

    /** Get a per-MA-user long-lived token (created lazily on first profile switch). */
    fun getMaUserToken(maUserId: String): String =
        prefs.getString("ma_user_token_$maUserId", "") ?: ""

    /** Store a per-MA-user long-lived token. */
    fun setMaUserToken(maUserId: String, token: String) {
        prefs.edit().putString("ma_user_token_$maUserId", token).commit()
    }

    /**
     * Derive the MA API URL from the existing maServerUrl (streams proxy on port 8097)
     * by switching to port 8095, or return the explicitly configured maApiUrl.
     */
    fun getEffectiveMaApiUrl(): String {
        // Prefer explicitly configured API URL
        val explicit = maApiUrl
        if (explicit.isNotEmpty()) return explicit
        // Derive from MA streams server URL (port 8097 → 8095)
        val streamsUrl = maServerUrl
        if (streamsUrl.isNotEmpty()) {
            return try {
                val parsed = java.net.URL(streamsUrl)
                "${parsed.protocol}://${parsed.host}:8095"
            } catch (_: Exception) { "" }
        }
        // Last resort: derive from HA URL (same host, MA default port 8095)
        val haUrlRaw = haBaseUrl.takeIf { it.isNotEmpty() } ?: haUrl
        if (haUrlRaw.isNotEmpty()) {
            return try {
                val parsed = java.net.URL(haUrlRaw)
                "http://${parsed.host}:8095"
            } catch (_: Exception) { "" }
        }
        return ""
    }

    /** Clear MA API credentials (token + URL) and reset session state flags. */
    fun clearMaApiToken() {
        prefs.edit()
            .remove(KEY_MA_API_TOKEN)
            .remove(KEY_MA_API_URL)
            .remove(KEY_MA_TOKEN_EXPIRED)
            .remove(KEY_MA_TOKEN_LONG_LIVED)
            .commit()
    }
}

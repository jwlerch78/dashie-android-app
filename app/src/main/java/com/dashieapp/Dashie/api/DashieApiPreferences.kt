package com.dashieapp.Dashie.api

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Preferences for standard Dashie's API integration.
 *
 * This is a simpler version of HalitePreferences that only includes
 * the settings needed for Home Assistant API integration in the
 * standard Dashie app (not Dashie Lite).
 *
 * Features supported:
 * - API enabled/disabled toggle
 * - API password for authentication
 * - Dark mode setting
 * - Discoverability (SSDP/mDNS)
 */
class DashieApiPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_api_prefs"

        // API settings
        private const val KEY_API_ENABLED = "api_enabled"
        private const val KEY_API_PASSWORD = "api_password"

        // Home Assistant token settings
        private const val KEY_HA_BASE_URL = "ha_base_url"
        private const val KEY_HA_ACCESS_TOKEN = "ha_access_token"
        private const val KEY_HA_REFRESH_TOKEN = "ha_refresh_token"
        private const val KEY_HA_TOKEN_TIMESTAMP = "ha_token_timestamp"

        // Home Assistant UI display settings (for CSS injection)
        private const val KEY_HA_HIDE_SIDEBAR = "ha_hide_sidebar"
        private const val KEY_HA_HIDE_TABS = "ha_hide_tabs"
        private const val KEY_HA_HIDE_SEARCH = "ha_hide_search"
        private const val KEY_HA_HIDE_ASSISTANT = "ha_hide_assistant"

        // Device identification for SSDP discovery
        private const val KEY_DEVICE_UUID = "device_uuid"
        private const val KEY_DEVICE_NAME = "device_name"

        // Default password is empty (no auth required by default for ease of setup)
        // Users can set a password through the web app or API
        private const val DEFAULT_PASSWORD = ""
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Whether the API server is enabled.
     * Default: true (enabled for HA integration)
     */
    var apiEnabled: Boolean
        get() = prefs.getBoolean(KEY_API_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_API_ENABLED, value) }

    /**
     * Password for API authentication.
     * Empty string means no authentication required.
     */
    var apiPassword: String
        get() = prefs.getString(KEY_API_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
        set(value) = prefs.edit { putString(KEY_API_PASSWORD, value) }

    // ============================================
    // Home Assistant Token Storage
    // ============================================

    /**
     * Home Assistant base URL (e.g., "http://192.168.1.100:8123")
     */
    var haBaseUrl: String
        get() = prefs.getString(KEY_HA_BASE_URL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_HA_BASE_URL, value) }

    /**
     * Home Assistant access token (short-lived, ~30 min)
     */
    var haAccessToken: String
        get() = prefs.getString(KEY_HA_ACCESS_TOKEN, "") ?: ""
        set(value) = prefs.edit { putString(KEY_HA_ACCESS_TOKEN, value) }

    /**
     * Home Assistant refresh token (long-lived, for getting new access tokens)
     */
    var haRefreshToken: String
        get() = prefs.getString(KEY_HA_REFRESH_TOKEN, "") ?: ""
        set(value) = prefs.edit { putString(KEY_HA_REFRESH_TOKEN, value) }

    /**
     * Timestamp when the access token was obtained (epoch millis)
     */
    var haTokenTimestamp: Long
        get() = prefs.getLong(KEY_HA_TOKEN_TIMESTAMP, 0L)
        set(value) = prefs.edit { putLong(KEY_HA_TOKEN_TIMESTAMP, value) }

    /**
     * Check if we have a valid HA token stored
     */
    val hasHaToken: Boolean
        get() = haAccessToken.isNotEmpty()

    /**
     * Update HA tokens (called after successful login)
     */
    fun updateHaTokens(accessToken: String, refreshToken: String?, baseUrl: String) {
        haAccessToken = accessToken
        if (!refreshToken.isNullOrEmpty()) {
            haRefreshToken = refreshToken
        }
        haBaseUrl = baseUrl
        haTokenTimestamp = System.currentTimeMillis()
    }

    /**
     * Clear all HA tokens (logout)
     */
    fun clearHaTokens() {
        prefs.edit {
            remove(KEY_HA_ACCESS_TOKEN)
            remove(KEY_HA_REFRESH_TOKEN)
            remove(KEY_HA_TOKEN_TIMESTAMP)
        }
    }

    // ============================================
    // Home Assistant UI Display Settings
    // These control CSS injection for kiosk mode
    // ============================================

    /**
     * Hide the HA sidebar via CSS injection.
     * Default: true (sidebar hidden for cleaner kiosk display)
     */
    var hideSidebar: Boolean
        get() = prefs.getBoolean(KEY_HA_HIDE_SIDEBAR, true)
        set(value) = prefs.edit { putBoolean(KEY_HA_HIDE_SIDEBAR, value) }

    /**
     * Hide the HA header tabs via CSS injection.
     * Default: true (tabs hidden for cleaner kiosk display)
     */
    var hideTabs: Boolean
        get() = prefs.getBoolean(KEY_HA_HIDE_TABS, true)
        set(value) = prefs.edit { putBoolean(KEY_HA_HIDE_TABS, value) }

    /**
     * Hide the HA search button via CSS injection.
     * Default: true (search hidden for cleaner kiosk display)
     */
    var hideSearch: Boolean
        get() = prefs.getBoolean(KEY_HA_HIDE_SEARCH, true)
        set(value) = prefs.edit { putBoolean(KEY_HA_HIDE_SEARCH, value) }

    /**
     * Hide the HA assistant button via CSS injection.
     * Default: true (assistant hidden — Dashie provides its own)
     */
    var hideAssistant: Boolean
        get() = prefs.getBoolean(KEY_HA_HIDE_ASSISTANT, true)
        set(value) = prefs.edit { putBoolean(KEY_HA_HIDE_ASSISTANT, value) }

    // ============================================
    // Device Identification for SSDP Discovery
    // ============================================

    /**
     * Unique device UUID for SSDP discovery.
     * Generated once and persisted. Used as the USN in SSDP announcements.
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
     * User-friendly device name for SSDP discovery.
     * Defaults to device model name, but can be customized.
     */
    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, null) ?: android.os.Build.MODEL
        set(value) = prefs.edit { putString(KEY_DEVICE_NAME, value) }
}

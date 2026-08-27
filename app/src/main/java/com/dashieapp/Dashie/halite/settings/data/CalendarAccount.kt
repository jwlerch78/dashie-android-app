package com.dashieapp.Dashie.halite.settings.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data model for a calendar account with its calendars, parsed from JS bridge JSON.
 *
 * Loaded via calendarService.getCalendars() + tokenStore.getProviderAccounts()
 * through the WebView bridge.
 */
data class CalendarAccount(
    val accountType: String,     // primary, account2, etc.
    val provider: String,        // google, microsoft
    val email: String,
    val calendars: List<CalendarInfo>,
    // True when the JS edge function has marked this account's refresh
    // token as terminally revoked (invalid_grant). Drives the inline
    // "Sign-in Required" section in CalendarPageSchema.
    val authInvalid: Boolean = false
) {
    companion object {
        fun fromJsonArray(json: String): List<CalendarAccount> {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun fromJson(json: JSONObject): CalendarAccount {
            val calendars = mutableListOf<CalendarInfo>()
            val calArr = json.optJSONArray("calendars")
            if (calArr != null) {
                for (i in 0 until calArr.length()) {
                    calendars.add(CalendarInfo.fromJson(calArr.getJSONObject(i)))
                }
            }
            return CalendarAccount(
                accountType = json.optString("accountType", "primary"),
                provider = json.optString("provider", "google"),
                email = json.optString("email", ""),
                calendars = calendars,
                authInvalid = json.optBoolean("authInvalid", false)
            )
        }
    }
}

/**
 * Data model for a single calendar within an account.
 */
data class CalendarInfo(
    val prefixedId: String,      // accountType-calendarId
    val rawId: String,
    val summary: String,         // display name
    val backgroundColor: String, // hex color
    val isActive: Boolean,
    /** "Allow adding events" — user override else provider access-role default
     *  (computed JS-side via calendarService.isCalendarEditable; design 20260713 §2.7). */
    val editable: Boolean = true,
    /** Provider reports read-only (Google reader/freeBusyReader) — render the
     *  editable row disabled; a write would fail at the provider anyway. */
    val providerReadOnly: Boolean = false
) {
    companion object {
        fun fromJson(json: JSONObject): CalendarInfo {
            return CalendarInfo(
                prefixedId = json.optString("prefixedId", ""),
                rawId = json.optString("rawId", json.optString("id", "")),
                summary = json.optString("summary", json.optString("name", "Unnamed")),
                backgroundColor = json.optString("backgroundColor", json.optString("color", "#4285F4")),
                isActive = json.optBoolean("isActive", true),
                editable = json.optBoolean("editable", true),
                providerReadOnly = json.optBoolean("providerReadOnly", false)
            )
        }
    }
}

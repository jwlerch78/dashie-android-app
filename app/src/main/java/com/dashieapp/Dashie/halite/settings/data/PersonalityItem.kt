package com.dashieapp.Dashie.halite.settings.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data model for a personality template, parsed from JS bridge JSON.
 *
 * Loaded via PersonalityService.getPersonalitiesForNative() through the WebView bridge.
 * Used by PersonalityPickerFragment to display selectable personality list.
 */
data class PersonalityItem(
    val id: String,
    val key: String,
    val name: String,
    val description: String,
    val voiceMode: String,   // "fixed" or "preferred"
    val voiceKey: String,    // e.g. "BUTLER", "COWBOY"
    val voiceName: String,   // Display name e.g. "Butler", "Cowboy"
    val isCustom: Boolean    // true for user-created personalities
) {
    /** Whether this personality locks the voice selection */
    val isVoiceFixed: Boolean get() = voiceMode == "fixed"

    /** Subtitle for display: shows personality description */
    val subtitle: String get() = description

    companion object {
        fun fromJson(json: JSONObject): PersonalityItem {
            return PersonalityItem(
                id = json.optString("id", json.optString("key", "")),
                key = json.optString("key", ""),
                name = json.optString("name", ""),
                description = json.optString("description", ""),
                voiceMode = json.optString("voiceMode", "preferred"),
                voiceKey = json.optString("voiceKey", ""),
                voiceName = json.optString("voiceName", ""),
                isCustom = json.optBoolean("isCustom", false)
            )
        }

        fun fromJsonArray(json: String): List<PersonalityItem> {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

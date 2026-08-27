package com.dashieapp.Dashie.halite.music

import org.json.JSONArray
import org.json.JSONObject

/**
 * A single recently played item from Music Assistant library.
 */
data class RecentlyPlayedItem(
    val name: String,
    val uri: String,           // e.g. "library://album/15" or "library://artist/42"
    val mediaType: String,     // "album" or "artist"
    val imageUrl: String?,     // Full URL to artwork, null if unavailable
    val artist: String?        // Artist name (for albums: first artist; for artists: null)
) {
    companion object {
        fun fromJson(json: JSONObject): RecentlyPlayedItem {
            return RecentlyPlayedItem(
                name = json.optString("name", ""),
                uri = json.optString("uri", ""),
                mediaType = json.optString("mediaType", "album"),
                imageUrl = json.optString("imageUrl", null),
                artist = json.optString("artist", null)
            )
        }
    }
}

/**
 * A named section of recently played items (e.g., "Songs", "Albums").
 */
data class RecentlyPlayedSection(
    val title: String,
    val mediaType: String,     // "track", "album", "artist", "playlist"
    val items: List<RecentlyPlayedItem>
)

/**
 * List of recently played items with optional auth info for image loading.
 * JSON format: { "authToken": "...", "haBaseUrl": "...", "items": [...] }
 * Falls back to bare array for backwards compat.
 *
 * When [sections] is populated, the UI renders categorized horizontal rows
 * instead of a flat grid. [items] remains populated as a flat list for
 * backwards compatibility with code that reads it (cache, etc.).
 */
data class RecentlyPlayedData(
    val items: List<RecentlyPlayedItem>,
    val authToken: String? = null,
    val haBaseUrl: String? = null,
    val maServerUrl: String? = null,  // MA streams server URL for image proxy
    val maApiToken: String? = null,   // MA JWT token for thumb proxy auth
    val maApiUrl: String? = null,     // MA API base URL for thumb proxy
    val sections: List<RecentlyPlayedSection> = emptyList()
) {
    companion object {
        fun fromJson(jsonString: String): RecentlyPlayedData {
            val trimmed = jsonString.trim()
            // New format: { authToken, haBaseUrl, items: [...] }
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                val authToken = obj.optString("authToken", null)
                val haBaseUrl = obj.optString("haBaseUrl", null)
                val arr = obj.optJSONArray("items") ?: JSONArray()
                val items = (0 until arr.length()).map { i ->
                    RecentlyPlayedItem.fromJson(arr.getJSONObject(i))
                }
                return RecentlyPlayedData(items, authToken, haBaseUrl)
            }
            // Legacy: bare array
            val arr = JSONArray(trimmed)
            val items = (0 until arr.length()).map { i ->
                RecentlyPlayedItem.fromJson(arr.getJSONObject(i))
            }
            return RecentlyPlayedData(items)
        }
    }
}

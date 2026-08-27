package com.dashieapp.Dashie.halite.music

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resolves and plays a bare genre utterance ("play jazz", "play some blues").
 *
 * ## Why this doesn't use the library's genres
 *
 * Verified against a live MA server on 2026-07-18: MA's genre *taxonomy*
 * (`music/genres/library_items`, 59 entries like "ambient", "jazz",
 * "afrobeats (West African urban/pop music)") is a STATIC vocabulary with no
 * content linked to it. Every route from a genre to playable items is a dead
 * end on this data:
 *
 *  - `music/tracks/library_items` with `genre` or `genre_id` — the filter is
 *    SILENTLY IGNORED. "jazz" and "blues" both returned the identical first N
 *    library tracks ordered by name, which is why every genre request played
 *    "Accidentally In Love" by Counting Crows.
 *  - `play_media` on `library://genre/N` — HTTP 500.
 *  - `music/genres/genre_tracks` — HTTP 400, no such command.
 *  - `music/browse` on the genre path — returns only the parent folder.
 *  - Track metadata carries no genres at all; only *albums* do, sparsely.
 *
 * So a genre is resolved the way a listener actually means it: as a request for
 * a STATION or PLAYLIST of that music, searched across the connected providers.
 * The library genre list is kept only as a cheap "is this word a genre?" oracle.
 */
class MaGenrePlayer(private val client: MaApiClient) {

    companion object {
        private const val TAG = "MaGenrePlayer"
        private const val SEARCH_LIMIT = 5

        /** Library genres change rarely; re-fetching per utterance is wasteful. */
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        /** Prefer a curated playlist; fall back to a station. */
        private val PLAYABLE_GROUPS = listOf("playlists", "radio")
    }

    @Volatile private var cache: List<String>? = null
    @Volatile private var cachedAtMs: Long = 0

    /**
     * If the utterance names a genre, play a station/playlist for it.
     *
     * @return true if it played; null to mean "not a genre (or nothing found) —
     *   fall through to the normal search". Never returns false, so a miss
     *   degrades into the ordinary search rather than failing the request.
     */
    fun playIfMatched(queueId: String, query: String, mediaTypeHint: String?): Boolean? {
        val genre = MaVoiceSearchResolver.matchGenre(query, cachedGenreNames(), mediaTypeHint)
            ?: return null

        val grouped = searchGenre(genre) ?: run {
            Log.w(TAG, "🔍 genre '$genre' search failed — falling through")
            return null
        }

        for (group in PLAYABLE_GROUPS) {
            val arr = grouped.optJSONArray(group) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val uri = item.optString("uri", "").takeIf { it.isNotEmpty() } ?: continue
                val name = item.optString("name", "").trim()

                // A station/playlist is self-sustaining, so continuation stays OFF —
                // enabling it would let MA's history-seeded radio take the queue over.
                val radioMode = MaVoiceSearchResolver.continuationForGroup(
                    MaVoiceSearchResolver.GROUP_GENRE
                )
                Log.i(TAG, "🔍 genre '$genre' → $group '$name' uri=$uri dstm=$radioMode")
                if (client.playMedia(queueId, uri, radioMode)) return true
                Log.w(TAG, "🔍 genre '$genre': '$name' failed to play — trying next")
            }
        }

        Log.w(TAG, "🔍 genre '$genre': no playable station or playlist — falling through")
        return null
    }

    /** Search the providers for stations/playlists matching a genre. */
    private fun searchGenre(genre: String): JSONObject? {
        val args = JSONObject().apply {
            put("search_query", genre)
            put("media_types", JSONArray().apply { put("playlist"); put("radio") })
            put("limit", SEARCH_LIMIT)
        }
        val result = client.sendRequest("music/search", args)?.opt("result")
        return result as? JSONObject
    }

    /** Library genre names, cached for [CACHE_TTL_MS]. */
    private fun cachedGenreNames(): List<String> {
        val now = System.currentTimeMillis()
        val existing = cache
        if (existing != null && now - cachedAtMs < CACHE_TTL_MS) return existing

        val arr = client.getGenres() ?: return existing ?: emptyList()
        val names = buildList {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("name", "")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add(it) }
            }
        }
        cache = names
        cachedAtMs = now
        Log.d(TAG, "🔍 genre cache refreshed: ${names.size} genres")
        return names
    }
}

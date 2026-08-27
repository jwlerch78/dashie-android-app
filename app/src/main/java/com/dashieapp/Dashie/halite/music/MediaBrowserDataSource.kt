package com.dashieapp.Dashie.halite.music

import android.util.Log
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches library data from MA API per category and converts to RecentlyPlayedItem lists.
 * All fetch methods are synchronous — call from a background thread.
 */
class MediaBrowserDataSource(
    private var apiClient: MaApiClient,
    private val maApiUrl: String
) {
    companion object {
        private const val TAG = "MediaBrowserDataSource"
    }

    enum class Category(val apiPath: String, val displayName: String, val iconType: String) {
        RECENTS("", "Recents", "recents"),
        // FAVORITES is a cross-media-type filtered view (favorite=true on each
        // type's library_items). Rendered as sections in the grid, like Recents.
        FAVORITES("", "Favorites", "favorite"),
        ALBUMS("albums", "Albums", "album"),
        ARTISTS("artists", "Artists", "artist"),
        PLAYLISTS("playlists", "Playlists", "playlist"),
        // MA's REST endpoint is plural: music/radios/library_items. Previously
        // used the singular "radio" which silently returned empty for users
        // whose MA server returned 404 on the wrong path. Items returned still
        // carry media_type="radio" (singular), which fetchLibraryItems derives
        // by removing the trailing "s".
        RADIO("radios", "Radio", "radio"),
        GENRES("genres", "Genres", "genre")
    }

    private val cache = ConcurrentHashMap<Category, List<RecentlyPlayedItem>>()

    /**
     * Swap the API client (e.g., when switching music profiles).
     * Clears all cached data so next fetch uses the new user's library.
     */
    fun swapApiClient(newClient: MaApiClient) {
        apiClient = newClient
        cache.clear()
        Log.i(TAG, "Swapped API client and cleared cache")
    }

    /** Fetch items for a category. Synchronous — call from background thread. */
    fun fetchCategory(category: Category, limit: Int = 50): List<RecentlyPlayedItem> {
        // Check cache first
        cache[category]?.let { return it }

        val items = when (category) {
            Category.RECENTS -> fetchRecents(limit)
            Category.GENRES -> fetchGenres(limit)
            // FAVORITES is normally rendered sectioned by the UI via
            // fetchFavoritesCategorized(); flatten here so callers using the
            // generic fetchCategory() path still get something sensible.
            Category.FAVORITES -> fetchFavoritesCategorized().flatMap { it.items }
            else -> fetchLibraryItems(category, limit)
        }

        Log.i(TAG, "Fetched ${items.size} items for ${category.displayName}")
        // Diagnostic: zero items for a non-Recents category usually means either
        // (a) the user truly has no items, or (b) the MA endpoint shape changed.
        // Logging at INFO so a tester report shows which categories were empty.
        if (items.isEmpty() && category != Category.RECENTS) {
            PersistentLog.info(
                "MA-BROWSE",
                "${category.displayName} fetch returned 0 items (path='${category.apiPath}')"
            )
        } else if (category == Category.RADIO) {
            PersistentLog.info("MA-BROWSE", "Radio fetch returned ${items.size} items")
        }
        cache[category] = items
        return items
    }

    /** Search across all media types. Synchronous — call from background thread. */
    fun search(query: String, limit: Int = 50): List<RecentlyPlayedItem> {
        val arr = apiClient.searchLibrary(query, limit) ?: return emptyList()
        return parseItems(arr)
    }

    /** Fetch tracks within an album. Synchronous — call from background thread. */
    fun fetchAlbumContents(albumUri: String, limit: Int = 100): List<RecentlyPlayedItem> {
        Log.d(TAG, "Fetching album tracks for '$albumUri'...")
        val arr = apiClient.getAlbumTracks(albumUri, limit)
        if (arr != null && arr.length() > 0) {
            Log.d(TAG, "Album '$albumUri': ${arr.length()} tracks")
            return parseItems(arr, defaultMediaType = "track")
        }
        Log.d(TAG, "Album '$albumUri': no tracks found")
        return emptyList()
    }

    /** Fetch tracks within a playlist. Synchronous — call from background thread. */
    fun fetchPlaylistContents(playlistUri: String, limit: Int = 100): List<RecentlyPlayedItem> {
        Log.d(TAG, "Fetching playlist tracks for '$playlistUri'...")
        val arr = apiClient.getPlaylistTracks(playlistUri, limit)
        if (arr != null && arr.length() > 0) {
            Log.d(TAG, "Playlist '$playlistUri': ${arr.length()} tracks")
            return parseItems(arr, defaultMediaType = "track")
        }
        Log.d(TAG, "Playlist '$playlistUri': no tracks found")
        return emptyList()
    }

    /**
     * Fetch favorites across all media types, grouped into sections. Mirror of
     * fetchRecentsCategorized but uses each type's library_items endpoint with
     * favorite=true. Synchronous — call from background thread.
     */
    fun fetchFavoritesCategorized(): List<RecentlyPlayedSection> {
        // Order: tracks first, then albums, artists, playlists, radio. Mirrors
        // fetchRecentsCategorized for visual consistency.
        data class FavoriteType(val mediaType: String, val itemTypeForParser: String, val sectionTitle: String)
        val types = listOf(
            FavoriteType("tracks", "track", "Songs"),
            FavoriteType("albums", "album", "Albums"),
            FavoriteType("artists", "artist", "Artists"),
            FavoriteType("playlists", "playlist", "Playlists"),
            FavoriteType("radios", "radio", "Radio")
        )
        val sections = mutableListOf<RecentlyPlayedSection>()
        for (t in types) {
            val arr = apiClient.getFavoriteItems(t.mediaType, limit = 50) ?: continue
            val items = parseItems(arr, defaultMediaType = t.itemTypeForParser)
            if (items.isNotEmpty()) {
                sections.add(RecentlyPlayedSection(title = t.sectionTitle, mediaType = t.itemTypeForParser, items = items))
            }
        }
        Log.d(TAG, "fetchFavoritesCategorized: ${sections.size} sections, ${sections.sumOf { it.items.size }} total items")
        return sections
    }

    /** Fetch tracks/albums within a genre. Synchronous — call from background thread. */
    fun fetchGenreContents(genreName: String, limit: Int = 50): List<RecentlyPlayedItem> {
        Log.d(TAG, "Fetching genre contents for '$genreName'...")
        val arr = apiClient.getTracksByGenre(genreName, limit)
        if (arr != null && arr.length() > 0) {
            Log.d(TAG, "Genre '$genreName': ${arr.length()} tracks")
            return parseItems(arr, defaultMediaType = "track")
        }
        Log.d(TAG, "Genre '$genreName': no tracks found")
        return emptyList()
    }

    /**
     * Fetch recently played items categorized by type (songs, albums, artists, playlists).
     * Synchronous — call from background thread.
     */
    fun fetchRecentsCategorized(): List<RecentlyPlayedSection> {
        val categorized = apiClient.getRecentlyPlayedCategorized()
        val sections = mutableListOf<RecentlyPlayedSection>()
        val order = listOf("track" to "Songs", "album" to "Albums", "artist" to "Artists", "playlist" to "Playlists")
        for ((type, title) in order) {
            val arr = categorized[type] ?: continue
            val items = parseItems(arr, defaultMediaType = type)
            if (items.isNotEmpty()) {
                sections.add(RecentlyPlayedSection(title = title, mediaType = type, items = items))
            }
        }
        Log.d(TAG, "fetchRecentsCategorized: ${sections.size} sections, ${sections.sumOf { it.items.size }} total items")
        return sections
    }

    /** Set cached recents from externally-fetched RecentlyPlayedData (avoids redundant API call). */
    fun setCachedRecents(items: List<RecentlyPlayedItem>) {
        cache[Category.RECENTS] = items
    }

    fun clearCache() { cache.clear() }
    fun clearCache(category: Category) { cache.remove(category) }

    private fun fetchRecents(limit: Int): List<RecentlyPlayedItem> {
        val arr = apiClient.getRecentlyPlayed(limit) ?: return emptyList()
        return parseItems(arr)
    }

    private fun fetchLibraryItems(category: Category, limit: Int): List<RecentlyPlayedItem> {
        val orderBy = when (category) {
            Category.ALBUMS, Category.ARTISTS -> "name"
            Category.PLAYLISTS -> "name"
            Category.RADIO -> "name"
            else -> "name"
        }
        Log.d(TAG, "getLibraryItems: path=${category.apiPath}, limit=$limit, orderBy=$orderBy")
        val arr = apiClient.getLibraryItems(category.apiPath, limit, orderBy)
        Log.d(TAG, "getLibraryItems result: ${arr?.length() ?: "null"} items")
        if (arr == null) return emptyList()
        return parseItems(arr, defaultMediaType = category.apiPath.removeSuffix("s"))
    }

    private fun fetchGenres(limit: Int): List<RecentlyPlayedItem> {
        // Use the MA genres API endpoint for a complete genre list
        Log.d(TAG, "Fetching genres via music/genres/library_items...")
        val arr = apiClient.getGenres(limit)
        if (arr != null && arr.length() > 0) {
            Log.d(TAG, "Got ${arr.length()} genres from API")
            return parseItems(arr, defaultMediaType = "genre")
        }
        // Fallback: extract genres from album metadata (older MA versions)
        Log.d(TAG, "Genres API returned empty, falling back to album metadata extraction...")
        val albumArr = apiClient.getLibraryItems("albums", 500, "name") ?: return emptyList()
        val genreImages = mutableMapOf<String, String?>()
        for (i in 0 until albumArr.length()) {
            val obj = albumArr.getJSONObject(i)
            val metadata = obj.optJSONObject("metadata")
            val genres = metadata?.optJSONArray("genres")
            if (genres != null) {
                val imageUrl = resolveImageUrl(obj)
                for (j in 0 until genres.length()) {
                    val genre = genres.optString(j, "").trim()
                    if (genre.isNotEmpty() && genre !in genreImages) {
                        genreImages[genre] = imageUrl
                    }
                }
            }
        }
        Log.d(TAG, "Found ${genreImages.size} unique genres from album metadata")
        return genreImages.keys.sorted().map { genre ->
            RecentlyPlayedItem(
                name = genre,
                uri = "genre://$genre",
                mediaType = "genre",
                imageUrl = genreImages[genre],
                artist = null
            )
        }
    }

    /**
     * Parse a MA API JSONArray into RecentlyPlayedItem list.
     * Handles both recently_played_items format and library_items format.
     */
    private fun parseItems(arr: JSONArray, defaultMediaType: String = "album"): List<RecentlyPlayedItem> {
        val items = mutableListOf<RecentlyPlayedItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val name = obj.optString("name", "")
            val uri = obj.optString("uri", "")
            if (name.isEmpty() || uri.isEmpty()) continue

            val mediaType = obj.optString("media_type", defaultMediaType)
            val artist = obj.optJSONArray("artists")
                ?.let { if (it.length() > 0) it.getJSONObject(0).optString("name", "") else "" }
                ?: ""
            val imageUrl = resolveImageUrl(obj)

            items.add(RecentlyPlayedItem(
                name = name,
                uri = uri,
                mediaType = mediaType,
                imageUrl = imageUrl,
                artist = artist.takeIf { it.isNotEmpty() }
            ))
        }
        return items
    }

    /** Resolve the best image URL from an MA API item, using the same logic as HaliteComponentWiring. */
    private fun resolveImageUrl(obj: JSONObject): String? {
        // Top-level image field
        val image = obj.opt("image")
        if (image is String && image.isNotEmpty()) {
            return if (image.startsWith("http")) image else "$maApiUrl$image"
        }
        if (image is JSONObject) {
            val path = image.optString("path", "")
            val remotely = image.optBoolean("remotely_accessible", false)
            if (path.isNotEmpty()) {
                return if (remotely || path.startsWith("http")) path
                else "$maApiUrl/api/thumb?path=${java.net.URLEncoder.encode(path, "UTF-8")}&size=256"
            }
        }
        // image_url fallback
        val imageUrl = obj.optString("image_url", "")
        if (imageUrl.isNotEmpty()) {
            return if (imageUrl.startsWith("http")) imageUrl else "$maApiUrl$imageUrl"
        }
        // metadata.images array
        val metadata = obj.optJSONObject("metadata")
        val images = metadata?.optJSONArray("images")
        if (images != null && images.length() > 0) {
            for (j in 0 until images.length()) {
                val img = images.getJSONObject(j)
                if (img.optString("type") == "thumb") {
                    val path = img.optString("path", "")
                    val remotely = img.optBoolean("remotely_accessible", false)
                    if (path.isNotEmpty()) {
                        return if (remotely || path.startsWith("http")) path
                        else "$maApiUrl/api/thumb?path=${java.net.URLEncoder.encode(path, "UTF-8")}&size=256"
                    }
                }
            }
        }
        return null
    }
}

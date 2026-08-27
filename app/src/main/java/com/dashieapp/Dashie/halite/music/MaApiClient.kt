package com.dashieapp.Dashie.halite.music

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Simple HTTP client for the Music Assistant REST API.
 *
 * All methods are synchronous — the caller is responsible for running
 * them on a background thread.
 *
 * API format: POST http://{host}:{port}/api
 * Auth: Authorization: Bearer {jwt_token}
 * Body: {"message_id": "N", "command": "...", "args": {...}}
 */
class MaApiClient(
    private val baseUrl: String,
    @Volatile private var token: String
) {
    companion object {
        private const val TAG = "MaApiClient"
        private const val CONNECT_TIMEOUT = 5000
        private const val READ_TIMEOUT = 5000


        // ── Circuit breaker (shared across all instances) ──────────────
        // After CIRCUIT_THRESHOLD consecutive connection failures, all requests
        // are short-circuited for CIRCUIT_BACKOFF_MS to prevent thread buildup
        // when the MA server is unreachable.
        private const val CIRCUIT_THRESHOLD = 3
        private const val CIRCUIT_BACKOFF_MS = 30_000L

        private val consecutiveFailures = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile private var circuitOpenUntil: Long = 0

        /** True when the circuit breaker is open (MA assumed unreachable). */
        fun isCircuitOpen(): Boolean {
            if (circuitOpenUntil == 0L) return false
            if (System.currentTimeMillis() >= circuitOpenUntil) {
                // Backoff expired — allow a probe request
                circuitOpenUntil = 0
                consecutiveFailures.set(0)
                Log.i(TAG, "⚡ Circuit breaker reset — allowing probe request")
                return false
            }
            return true
        }

        private fun recordSuccess() {
            if (consecutiveFailures.getAndSet(0) > 0) {
                circuitOpenUntil = 0
                Log.i(TAG, "⚡ Circuit breaker: connection restored")
            }
        }

        private fun recordConnectionFailure() {
            val count = consecutiveFailures.incrementAndGet()
            if (count >= CIRCUIT_THRESHOLD && circuitOpenUntil == 0L) {
                circuitOpenUntil = System.currentTimeMillis() + CIRCUIT_BACKOFF_MS
                Log.w(TAG, "⚡ Circuit breaker OPEN — $count consecutive failures, backing off ${CIRCUIT_BACKOFF_MS / 1000}s")
            }
        }
    }

    private val messageCounter = AtomicInteger(1)

    /** Called when any request gets a 401 (token expired/invalid). Set to true silently. */
    var onAuthFailure: (() -> Unit)? = null

    /** True after any 401 response. Checked by UI code to decide whether to show login. */
    @Volatile var authFailed = false

    /** Error text from the last failed request. Used for user-facing error messages.
     *  RESET at the start of every request — it used to be written only in the failure
     *  branch, so it held the previous error indefinitely and an empty-body failure
     *  (exactly MA's 500) reported the PREVIOUS one. Stale-but-plausible reads as fresh
     *  and sends the reader after the wrong bug. */
    @Volatile var lastErrorText: String = ""

    /** HTTP status of the last failed request; 0 = no response received (transport failure).
     *  The discriminator the user-facing message needs: a 5xx means MA ANSWERED and failed
     *  server-side, so nothing is offline — see the "server is reachable (even if 4xx/5xx)"
     *  note below. Without this, an empty 500 body was reported as "speaker may be offline". */
    @Volatile var lastErrorStatus: Int = 0

    /** Replace the token (e.g., after fetching a fresh one from central store). Clears auth failure state. */
    fun updateToken(newToken: String) {
        token = newToken
        authFailed = false
    }

    /**
     * Send a command to the MA API and return the parsed response.
     * Returns null on network/parse errors.
     */
    fun sendRequest(command: String, args: JSONObject = JSONObject()): JSONObject? {
        if (isCircuitOpen()) {
            Log.v(TAG, "⚡ Circuit open — skipping $command")
            return null
        }
        lastErrorText = ""
        lastErrorStatus = 0
        return try {
            val msgId = messageCounter.getAndIncrement().toString()
            // Send args both top-level AND nested under "args" for compatibility.
            // Some MA API versions/commands expect one format or the other.
            val body = JSONObject().apply {
                put("message_id", msgId)
                put("command", command)
                // Top-level args (newer MA API format)
                val keys = args.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, args.get(key))
                }
                // Also nested (legacy format used by some commands)
                if (args.length() > 0) {
                    put("args", args)
                }
            }

            Log.d(TAG, "→ $command: ${body.toString().take(200)}")
            val url = URL("$baseUrl/api")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }

            val code = conn.responseCode
            // Got a response — server is reachable (even if 4xx/5xx)
            recordSuccess()
            val responseText = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                val errorText = try {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                } catch (_: Exception) { "" }
                lastErrorText = errorText
                lastErrorStatus = code
                Log.w(TAG, "HTTP $code for $command: $errorText")
                if (code == 401) {
                    authFailed = true
                    onAuthFailure?.invoke()
                }
                conn.disconnect()
                return null
            }
            conn.disconnect()

            // MA API may return:
            // 1. JSONArray directly (e.g. players/all) → wrap in {"result": [...]}
            // 2. JSONObject with "result" key (standard JSON-RPC) → return as-is
            // 3. JSONObject without "result" key (e.g. players/get) → wrap in {"result": {...}}
            val trimmed = responseText.trim()
            if (trimmed.startsWith("[")) {
                JSONObject().put("result", JSONArray(trimmed))
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                if (obj.has("result") || obj.has("error_code")) {
                    obj  // Already wrapped or is an error response
                } else {
                    JSONObject().put("result", obj)  // Direct object — wrap it
                }
            } else {
                // Raw string response (e.g. JWT token from auth/token/create)
                // Strip surrounding quotes if present
                val unquoted = trimmed.removeSurrounding("\"")
                JSONObject().put("result", unquoted)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed for $command: ${e.message}")
            recordConnectionFailure()
            null
        }
    }

    /**
     * Get the current authenticated user's info (username, role, etc.).
     * Works with any token role.
     */
    fun getCurrentUser(): JSONObject? {
        val response = sendRequest("auth/me") ?: return null
        return response.optJSONObject("result")
    }

    /**
     * Create a long-lived token for a specific MA user (requires admin token).
     * Returns the JWT string or null on failure.
     */
    fun createTokenForUser(userId: String, tokenName: String = "Dashie"): String? {
        val args = JSONObject().apply {
            put("name", tokenName)
            put("user_id", userId)
        }
        val response = sendRequest("auth/token/create", args) ?: return null
        // Response is the token string directly in "result"
        return response.optString("result", "").takeIf { it.isNotEmpty() && it.contains(".") }
    }

    /**
     * Get all MA users (requires admin token).
     * Returns list of user objects with user_id, username, display_name, role, etc.
     */
    fun getUsers(): org.json.JSONArray? {
        val response = sendRequest("auth/users") ?: return null
        return response.optJSONArray("result")
    }

    /** Get all available players. Returns the "result" JSONArray or null. */
    fun getPlayers(): org.json.JSONArray? {
        val response = sendRequest("players/all") ?: return null
        return response.optJSONArray("result")
    }

    /**
     * Resolve a Sendspin player ID (up*) to a media_player.* entity from the same device.
     * Queries players/all and finds a media_player.* player with a matching display name.
     * Returns the Sendspin ID unchanged if no match is found.
     */
    /**
     * Resolve a Sendspin player ID (up*) to a media_player.* entity that can queue media.
     * Queries players/all and checks group_childs to find which media_player.* contains
     * this Sendspin player, or falls back to finding any available media_player.*_2 entity.
     */
    fun resolvePlayableEntity(sendspinId: String): String {
        if (!sendspinId.startsWith("up")) return sendspinId
        val players = getPlayers() ?: return sendspinId
        val allPlayers = (0 until players.length()).map { players.getJSONObject(it) }

        // Log all players for debugging
        for (p in allPlayers) {
            val id = p.optString("player_id", "")
            val name = p.optString("display_name", p.optString("name", ""))
            val childs = p.optJSONArray("group_childs")
            Log.d(TAG, "  player: $id ($name) childs=${childs ?: "none"}")
        }

        // Strategy 1: Find a media_player.* whose group_childs contains this Sendspin ID
        val groupParent = allPlayers.find { p ->
            val id = p.optString("player_id", "")
            if (!id.startsWith("media_player.")) return@find false
            val childs = p.optJSONArray("group_childs") ?: return@find false
            (0 until childs.length()).any { childs.getString(it) == sendspinId }
        }
        if (groupParent != null) {
            val resolved = groupParent.optString("player_id")
            Log.d(TAG, "resolvePlayableEntity: $sendspinId → $resolved (via group_childs)")
            return resolved
        }

        // Strategy 2: Find the first available media_player.* entity (not a group/sync)
        // Prefer _2 suffix (actual MA-managed speaker per filterMusicAssistantPlayers convention)
        val fallback = allPlayers
            .filter { p ->
                val id = p.optString("player_id", "")
                id.startsWith("media_player.") && !id.startsWith("media_player.mass_") &&
                    !id.contains("syncgroup") && p.optBoolean("available", true)
            }
            .sortedByDescending { it.optString("player_id", "").endsWith("_2") }
            .firstOrNull()
        if (fallback != null) {
            val resolved = fallback.optString("player_id")
            Log.d(TAG, "resolvePlayableEntity: $sendspinId → $resolved (fallback media_player)")
            return resolved
        }

        Log.w(TAG, "resolvePlayableEntity: $sendspinId → no match found")
        return sendspinId
    }

    /** Get a specific player's state. Returns the "result" JSONObject or null. */
    fun getPlayerState(playerId: String): JSONObject? {
        val args = JSONObject().put("player_id", playerId)
        val response = sendRequest("players/get", args) ?: return null
        return response.optJSONObject("result")
    }

    /** Get a player queue's state (includes elapsed_time, state, current_item).
     *  Queue ID typically matches the player ID. */
    fun getQueueState(queueId: String): JSONObject? {
        val args = JSONObject().put("queue_id", queueId)
        val response = sendRequest("player_queues/get", args) ?: return null
        return response.optJSONObject("result")
    }

    /**
     * Send a player command (play, pause, next, previous, stop).
     * Returns true if the command was accepted.
     */
    fun sendPlayerCommand(playerId: String, command: String): Boolean {
        val args = JSONObject().put("player_id", playerId)
        val response = sendRequest("players/cmd/$command", args)
        return response != null
    }

    /** Set volume level (0-100). */
    fun setVolume(playerId: String, volumeLevel: Int): Boolean {
        val args = JSONObject().apply {
            put("player_id", playerId)
            put("volume_level", volumeLevel)
        }
        val response = sendRequest("players/cmd/volume_set", args)
        return response != null
    }

    /** Transfer queue from one player to another. Returns true if HTTP 200 (MA returns null body). */
    fun transferQueue(sourceQueueId: String, targetQueueId: String): Boolean {
        val args = JSONObject().apply {
            put("source_queue_id", sourceQueueId)
            put("target_queue_id", targetQueueId)
            put("auto_play", true)
        }
        // MA returns null body for transfer — sendRequest will fail to parse.
        // Use sendRequestRaw to check HTTP status only.
        return sendRequestRaw("player_queues/transfer", args)
    }

    /**
     * Send a command and return true if HTTP 200, regardless of response body.
     * Used for commands where MA returns null (transfer, play_media).
     */
    private fun sendRequestRaw(command: String, args: JSONObject = JSONObject()): Boolean {
        if (isCircuitOpen()) {
            Log.v(TAG, "⚡ Circuit open — skipping $command")
            return false
        }
        lastErrorText = ""
        lastErrorStatus = 0
        return try {
            val msgId = messageCounter.getAndIncrement().toString()
            val body = JSONObject().apply {
                put("message_id", msgId)
                put("command", command)
                val keys = args.keys()
                while (keys.hasNext()) { val key = keys.next(); put(key, args.get(key)) }
                if (args.length() > 0) put("args", args)
            }
            Log.d(TAG, "→ $command: ${body.toString().take(200)}")
            val url = URL("$baseUrl/api")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()); it.flush() }
            val code = conn.responseCode
            recordSuccess()
            if (code !in 200..299) {
                val errorText = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                // Record here too — this path (transfer_queue) previously captured the body
                // locally and threw it away, so its callers could not report anything at all.
                lastErrorText = errorText
                lastErrorStatus = code
                Log.w(TAG, "← $command: HTTP $code: $errorText")
            } else {
                Log.d(TAG, "← $command: HTTP $code")
            }
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "Request failed for $command: ${e.message}")
            recordConnectionFailure()
            false
        }
    }

    /**
     * Play a media URI on a player queue.
     * Always explicitly sets DSTM to radioMode so stale state from a prior
     * play can't leak onto the queue (e.g. a single-track play leaving DSTM
     * on, then an album play skipping into radio-generated tracks).
     */
    fun playMedia(queueId: String, mediaUri: String, radioMode: Boolean = false): Boolean {
        val args = JSONObject().apply {
            put("queue_id", queueId)
            put("media", mediaUri)
            put("option", "play")
        }
        val response = sendRequest("player_queues/play_media", args)
        if (response == null) return false

        val dstmArgs = JSONObject().apply {
            put("queue_id", queueId)
            put("dont_stop_the_music_enabled", radioMode)
        }
        sendRequest("player_queues/dont_stop_the_music", dstmArgs)
        return true
    }

    // Library genre names, lazily fetched and cached — see cachedGenreNames().
    /** Genre resolution for bare-genre voice requests ("play jazz"). */
    private val genrePlayer = MaGenrePlayer(this)

    /** Get recently played items. Merges recently_played_items with library albums. */
    fun getRecentlyPlayed(limit: Int = 10): org.json.JSONArray? {
        val merged = JSONArray()
        val seenUris = mutableSetOf<String>()

        // 1. Recently played items (tracks/albums actually played)
        val args = JSONObject().apply {
            put("limit", limit)
            put("fully_played_only", false)
        }
        val response = sendRequest("music/recently_played_items", args)
        if (response != null) {
            val result = response.optJSONArray("result")
            if (result != null) {
                Log.d(TAG, "recently_played_items: ${result.length()} items")
                for (i in 0 until result.length()) {
                    val item = result.getJSONObject(i)
                    val uri = item.optString("uri", "")
                    if (uri.isNotEmpty()) seenUris.add(uri)
                    merged.put(item)
                }
            }
        }

        // 2. Supplement with library albums + artists sorted by last played (interleaved)
        if (merged.length() < limit) {
            val fetchCount = limit + 5 // extra to account for duplicates
            val albumArgs = JSONObject().apply {
                put("order_by", "last_played_desc")
                put("limit", fetchCount)
            }
            val artistArgs = JSONObject().apply {
                put("order_by", "last_played_desc")
                put("limit", fetchCount)
            }
            val albums = sendRequest("music/albums/library_items", albumArgs)
                ?.optJSONArray("result")
            val artists = sendRequest("music/artists/library_items", artistArgs)
                ?.optJSONArray("result")

            Log.d(TAG, "library supplement: ${albums?.length() ?: 0} albums, ${artists?.length() ?: 0} artists")

            // Interleave: album, artist, album, artist...
            var ai = 0; var bi = 0
            while (merged.length() < limit && (ai < (albums?.length() ?: 0) || bi < (artists?.length() ?: 0))) {
                if (ai < (albums?.length() ?: 0)) {
                    val item = albums!!.getJSONObject(ai); ai++
                    val uri = item.optString("uri", "")
                    if (uri.isEmpty() || uri in seenUris) continue
                    seenUris.add(uri)
                    // Tag with media_type for the parser
                    if (!item.has("media_type")) item.put("media_type", "album")
                    merged.put(item)
                }
                if (merged.length() >= limit) break
                if (bi < (artists?.length() ?: 0)) {
                    val item = artists!!.getJSONObject(bi); bi++
                    val uri = item.optString("uri", "")
                    if (uri.isEmpty() || uri in seenUris) continue
                    seenUris.add(uri)
                    if (!item.has("media_type")) item.put("media_type", "artist")
                    merged.put(item)
                }
            }
        }

        Log.d(TAG, "getRecentlyPlayed: ${merged.length()} total items")
        return if (merged.length() > 0) merged else null
    }

    /** Join a player to a group. MA returns null body on success. */
    fun groupPlayer(playerId: String, targetPlayer: String): Boolean {
        val args = JSONObject().apply {
            put("player_id", playerId)
            put("target_player", targetPlayer)
        }
        return sendRequestRaw("players/cmd/group", args)
    }

    /** Remove a player from its group. MA returns null body on success. */
    fun ungroupPlayer(playerId: String): Boolean {
        val args = JSONObject().apply { put("player_id", playerId) }
        return sendRequestRaw("players/cmd/ungroup", args)
    }

    /** Set group volume (adjusts all members proportionally). MA may return null body. */
    fun setGroupVolume(playerId: String, volumeLevel: Int): Boolean {
        val args = JSONObject().apply {
            put("player_id", playerId)
            put("volume_level", volumeLevel)
        }
        return sendRequestRaw("players/cmd/group_volume", args)
    }

    /** Mute/unmute a player. MA may return null body. */
    fun mutePlayer(playerId: String, muted: Boolean): Boolean {
        val args = JSONObject().apply {
            put("player_id", playerId)
            put("muted", muted)
        }
        return sendRequestRaw("players/cmd/volume_mute", args)
    }

    /** Mute/unmute all members of a group. MA may return null body. */
    fun muteGroup(playerId: String, muted: Boolean): Boolean {
        val args = JSONObject().apply {
            put("player_id", playerId)
            put("muted", muted)
        }
        return sendRequestRaw("players/cmd/group_volume_mute", args)
    }

    /** Set shuffle mode on a player queue. */
    fun setShuffle(queueId: String, enabled: Boolean): Boolean {
        val args = JSONObject().apply {
            put("queue_id", queueId)
            put("shuffle_enabled", enabled)
        }
        return sendRequest("player_queues/shuffle", args) != null
    }

    /** Set repeat mode on a player queue. Mode: "off", "one", "all". */
    fun setRepeat(queueId: String, mode: String): Boolean {
        val args = JSONObject().apply {
            put("queue_id", queueId)
            put("repeat_mode", mode)
        }
        return sendRequest("player_queues/repeat", args) != null
    }

    /** Seek to an absolute position (in seconds) on a player queue. */
    fun seek(queueId: String, positionSec: Int): Boolean {
        val args = JSONObject().apply {
            put("queue_id", queueId)
            put("position", positionSec)
        }
        return sendRequest("player_queues/seek", args) != null
    }

    /** Get tracks for a specific genre. Returns the "result" JSONArray or null. */
    fun getTracksByGenre(genre: String, limit: Int = 50): JSONArray? {
        val args = JSONObject().apply {
            put("limit", limit)
            put("order_by", "name")
            put("genre", genre)
        }
        val response = sendRequest("music/tracks/library_items", args) ?: return null
        return response.optJSONArray("result")
    }

    /**
     * Get recently played items categorized by media type.
     * Returns a map of mediaType → JSONArray (e.g., "track" → [...], "album" → [...]).
     */
    fun getRecentlyPlayedCategorized(
        trackLimit: Int = 20,
        albumLimit: Int = 10,
        artistLimit: Int = 10,
        playlistLimit: Int = 10
    ): Map<String, JSONArray> {
        val result = mutableMapOf<String, JSONArray>()

        // 1. Recently played items (mixed types from actual play history)
        val recentArgs = JSONObject().apply {
            put("limit", trackLimit + albumLimit + artistLimit + playlistLimit)
            put("fully_played_only", false)
        }
        val recentResponse = sendRequest("music/recently_played_items", recentArgs)
        val recentItems = recentResponse?.optJSONArray("result")

        // Buckets for categorized items
        val tracks = JSONArray()
        val albums = JSONArray()
        val artists = JSONArray()
        val playlists = JSONArray()
        val seenUris = mutableSetOf<String>()

        // Categorize recently played items
        if (recentItems != null) {
            for (i in 0 until recentItems.length()) {
                val item = recentItems.getJSONObject(i)
                val uri = item.optString("uri", "")
                if (uri.isEmpty() || uri in seenUris) continue
                seenUris.add(uri)
                val mediaType = item.optString("media_type", "").lowercase()
                when (mediaType) {
                    "track" -> if (tracks.length() < trackLimit) tracks.put(item)
                    "album" -> if (albums.length() < albumLimit) albums.put(item)
                    "artist" -> if (artists.length() < artistLimit) artists.put(item)
                    "playlist" -> if (playlists.length() < playlistLimit) playlists.put(item)
                    else -> if (tracks.length() < trackLimit) {
                        item.put("media_type", "track")
                        tracks.put(item)
                    }
                }
            }
        }

        // 2. Supplement each category from library if under limit
        if (albums.length() < albumLimit) {
            val supplementArgs = JSONObject().apply {
                put("order_by", "last_played_desc")
                put("limit", albumLimit + 5)
            }
            val libAlbums = sendRequest("music/albums/library_items", supplementArgs)?.optJSONArray("result")
            if (libAlbums != null) {
                for (i in 0 until libAlbums.length()) {
                    if (albums.length() >= albumLimit) break
                    val item = libAlbums.getJSONObject(i)
                    val uri = item.optString("uri", "")
                    if (uri.isEmpty() || uri in seenUris) continue
                    seenUris.add(uri)
                    if (!item.has("media_type")) item.put("media_type", "album")
                    albums.put(item)
                }
            }
        }

        if (artists.length() < artistLimit) {
            val supplementArgs = JSONObject().apply {
                put("order_by", "last_played_desc")
                put("limit", artistLimit + 5)
            }
            val libArtists = sendRequest("music/artists/library_items", supplementArgs)?.optJSONArray("result")
            if (libArtists != null) {
                for (i in 0 until libArtists.length()) {
                    if (artists.length() >= artistLimit) break
                    val item = libArtists.getJSONObject(i)
                    val uri = item.optString("uri", "")
                    if (uri.isEmpty() || uri in seenUris) continue
                    seenUris.add(uri)
                    if (!item.has("media_type")) item.put("media_type", "artist")
                    artists.put(item)
                }
            }
        }

        Log.d(TAG, "getRecentlyPlayedCategorized: ${tracks.length()} tracks, ${albums.length()} albums, " +
            "${artists.length()} artists, ${playlists.length()} playlists")

        if (tracks.length() > 0) result["track"] = tracks
        if (albums.length() > 0) result["album"] = albums
        if (artists.length() > 0) result["artist"] = artists
        if (playlists.length() > 0) result["playlist"] = playlists

        return result
    }

    /** Get genres from the MA library. Returns the "result" JSONArray or null. */
    fun getGenres(limit: Int = 100): JSONArray? {
        val args = JSONObject().apply {
            put("limit", limit)
            put("order_by", "name")
        }
        val response = sendRequest("music/genres/library_items", args) ?: return null
        return response.optJSONArray("result")
    }

    // ── Library Browsing ────────────────────────────────────────────

    /**
     * Parse a Music Assistant item URI into (provider_instance_or_domain, item_id).
     *
     * URI shape: `<provider>://<media_type>/<id>` — e.g. "library://playlist/42",
     * "spotify://playlist/37i9dQ...", "builtin://radio/somafm-groovesalad".
     *
     * Returns null if the URI doesn't match the expected shape. Callers must
     * handle the null and degrade gracefully (e.g., fall back to "play whole
     * item" instead of "browse contents").
     */
    private fun parseMaUri(uri: String): Pair<String, String>? {
        val schemeEnd = uri.indexOf("://")
        if (schemeEnd <= 0) return null
        val provider = uri.substring(0, schemeEnd)
        val rest = uri.substring(schemeEnd + 3)
        // rest is "<media_type>/<id>" — id is everything after the LAST slash
        // (some provider IDs contain slashes, e.g. Spotify URIs)
        val lastSlash = rest.lastIndexOf('/')
        if (lastSlash < 0) return null
        val itemId = rest.substring(lastSlash + 1)
        if (itemId.isEmpty()) return null
        return provider to itemId
    }

    /**
     * Get tracks for a playlist. Returns the "result" JSONArray or null on error.
     *
     * MA REST endpoint: `music/playlists/playlist_tracks` with `item_id` and
     * `provider_instance_or_domain` (derived from the playlist URI).
     */
    fun getPlaylistTracks(playlistUri: String, limit: Int = 100): JSONArray? {
        val (provider, itemId) = parseMaUri(playlistUri) ?: run {
            Log.w(TAG, "getPlaylistTracks: could not parse URI: $playlistUri")
            return null
        }
        val args = JSONObject().apply {
            put("item_id", itemId)
            // MA expects "provider_instance_id_or_domain" (with id_). Using
            // "library" routes through MA's library lookup, which auto-resolves
            // to the underlying provider mapping (Spotify, builtin, etc.) — so
            // we don't need to crack open the library item ourselves.
            put("provider_instance_id_or_domain", provider)
        }
        Log.d(TAG, "getPlaylistTracks: music/playlists/playlist_tracks args=$args")
        val response = sendRequest("music/playlists/playlist_tracks", args)
        if (response == null) {
            Log.w(TAG, "getPlaylistTracks: null response for $playlistUri")
            return null
        }
        val result = response.optJSONArray("result")
        Log.d(TAG, "getPlaylistTracks: ${result?.length() ?: "null"} tracks for $playlistUri")
        return result
    }

    /**
     * Get tracks for an album. Returns the "result" JSONArray or null on error.
     *
     * MA REST endpoint: `music/albums/album_tracks` with `item_id` and
     * `provider_instance_id_or_domain` (derived from the album URI). Using
     * "library" routes through MA's library lookup like playlists.
     */
    fun getAlbumTracks(albumUri: String, limit: Int = 100): JSONArray? {
        val (provider, itemId) = parseMaUri(albumUri) ?: run {
            Log.w(TAG, "getAlbumTracks: could not parse URI: $albumUri")
            return null
        }
        val args = JSONObject().apply {
            put("item_id", itemId)
            put("provider_instance_id_or_domain", provider)
        }
        Log.d(TAG, "getAlbumTracks: music/albums/album_tracks args=$args")
        val response = sendRequest("music/albums/album_tracks", args)
        if (response == null) {
            Log.w(TAG, "getAlbumTracks: null response for $albumUri")
            return null
        }
        val result = response.optJSONArray("result")
        Log.d(TAG, "getAlbumTracks: ${result?.length() ?: "null"} tracks for $albumUri")
        return result
    }

    /**
     * Get favorited library items for a media type. Mirrors getLibraryItems but
     * adds `favorite=true` filter. Used by MediaBrowserDataSource to assemble
     * a cross-type Favorites view.
     *
     * Note: the `favorite` filter is referenced in MA community snippets but not
     * formally in the published REST API docs. If it ever stops working, the
     * diagnostic log line in MediaBrowserDataSource ("Favorites <type> returned 0")
     * will tell us so a tester report surfaces the issue.
     */
    fun getFavoriteItems(mediaType: String, limit: Int = 50): JSONArray? {
        val args = JSONObject().apply {
            put("order_by", "name")
            put("limit", limit)
            put("favorite", true)
        }
        val response = sendRequest("music/$mediaType/library_items", args) ?: return null
        return response.optJSONArray("result")
    }

    /**
     * Get library items by media type (albums, artists, playlists, radio, tracks).
     * Returns the "result" JSONArray or null on error.
     */
    fun getLibraryItems(mediaType: String, limit: Int = 50, orderBy: String = "name"): JSONArray? {
        val args = JSONObject().apply {
            put("order_by", orderBy)
            put("limit", limit)
        }
        Log.d(TAG, "getLibraryItems: music/$mediaType/library_items args=$args")
        val response = sendRequest("music/$mediaType/library_items", args)
        if (response == null) {
            Log.w(TAG, "getLibraryItems: null response for $mediaType")
            return null
        }
        Log.d(TAG, "getLibraryItems response keys: ${response.keys().asSequence().toList()}")
        val result = response.optJSONArray("result")
        Log.d(TAG, "getLibraryItems: ${result?.length() ?: "null"} items for $mediaType")
        return result
    }

    /**
     * Search the MA library across all media types.
     * Returns the "result" JSONArray or null on error.
     */
    /**
     * Search for media and immediately play the best result on the given queue.
     * Combines music/search + player_queues/play_media in one call.
     *
     * Resolution order:
     * - a bare genre utterance ("play jazz") → that genre's library tracks
     * - "songs by X" / "music by X" → artist
     * - "play X album" → album
     * - "play X playlist" → playlist
     * - otherwise → tracks > albums > artists > playlists
     *
     * Continuation ("don't stop the music") is DERIVED from what we resolved —
     * see [MaVoiceSearchResolver.continuationForGroup]. Callers do not choose it,
     * because hardcoding it on was what made an explicit artist request drift into
     * an unrelated artist's radio (field bug 2026-07-18).
     *
     * @param queueId The player queue to play on
     * @param query The search string
     * @param mediaTypeHint The classifier's media type ("artist"/"album"/"playlist"),
     *   when it extracted one. Overrides phrasing — see MaVoiceSearchResolver.priorityOrder.
     * @return true if search found results and play was initiated
     */
    fun searchAndPlay(queueId: String, query: String, mediaTypeHint: String? = null): Boolean {
        Log.i(TAG, "🔍 searchAndPlay: query='$query' hint=$mediaTypeHint on queue=$queueId")

        val cleanQuery = MaVoiceSearchResolver.cleanQuery(query)

        // Genre first: "play jazz" must not match a rap track TITLED "Jazz" (which
        // then seeds radio off that artist). Deliberately narrow — see matchGenre.
        genrePlayer.playIfMatched(queueId, query, mediaTypeHint)?.let { return it }

        val rawResponse = sendRequest("music/search", JSONObject().apply {
            put("search_query", cleanQuery)
            put("media_types", JSONArray().apply {
                put("track"); put("album"); put("artist"); put("playlist")
            })
            put("limit", 5)
        }) ?: return false

        val result = rawResponse.opt("result") ?: return false

        // MA returns grouped results: { artists: [...], albums: [...], tracks: [...], playlists: [...] }
        val grouped: JSONObject = when (result) {
            is JSONObject -> result
            else -> return false
        }

        val priorityOrder = MaVoiceSearchResolver.priorityOrder(query, mediaTypeHint)
        Log.d(TAG, "🔍 searchAndPlay: clean='$cleanQuery' priority=$priorityOrder")

        // Find first non-empty group in priority order
        for (groupKey in priorityOrder) {
            val arr = grouped.optJSONArray(groupKey) ?: continue
            if (arr.length() == 0) continue
            val item = arr.getJSONObject(0)
            val uri = item.optString("uri", "").takeIf { it.isNotEmpty() } ?: continue
            val name = item.optString("name", "")
            val radioMode = MaVoiceSearchResolver.continuationForGroup(groupKey)
            Log.i(TAG, "🔍 searchAndPlay: playing '$name' from $groupKey uri=$uri dstm=$radioMode")
            return playMedia(queueId, uri, radioMode)
        }

        Log.w(TAG, "🔍 searchAndPlay: no playable URI in any group")
        return false
    }

    fun searchLibrary(query: String, limit: Int = 50): JSONArray? {
        val args = JSONObject().apply {
            put("search_query", query)
            put("media_types", JSONArray().apply {
                put("track"); put("album"); put("artist"); put("playlist")
            })
            put("limit", limit)
        }
        val response = sendRequest("music/search", args) ?: return null
        // MA search may return grouped results (object with media type keys) or a flat array
        val result = response.opt("result")
        Log.d("MaApiClient", "Search '$query': result type=${result?.javaClass?.simpleName}, keys=${if (result is JSONObject) (result as JSONObject).keys().asSequence().toList() else "N/A"}")
        if (result is JSONArray) return result
        if (result is JSONObject) {
            // Grouped by media type — flatten into a single array
            val merged = JSONArray()
            for (key in (result as JSONObject).keys()) {
                val arr = result.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) merged.put(arr.getJSONObject(i))
            }
            Log.d("MaApiClient", "Search flattened: ${merged.length()} items from keys ${result.keys().asSequence().toList()}")
            return merged
        }
        return null
    }
}

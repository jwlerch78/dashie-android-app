package com.dashieapp.Dashie.halite.music

import android.util.Log
import org.json.JSONArray

/**
 * Fuzzy-matches spoken speaker names against the MA players/all list.
 * Used by voice commands like "play jazz on the kitchen speaker" or "mute bedroom".
 *
 * Caches the player list for 30 seconds to avoid repeated API calls during
 * a voice interaction session.
 */
class SpeakerNameMatcher(
    private val playersProvider: () -> JSONArray?
) {
    private val TAG = "SpeakerNameMatcher"

    data class PlayerInfo(
        val playerId: String,
        val displayName: String,
        val available: Boolean
    )

    private var cachedPlayers: List<PlayerInfo>? = null
    private var cacheTimestamp: Long = 0
    private val CACHE_TTL_MS = 30_000L

    /**
     * Find the best matching player for a spoken name.
     * Returns the player_id or null if no reasonable match found.
     */
    fun resolve(spokenName: String): String? {
        val players = getPlayers() ?: return null
        if (players.isEmpty()) return null

        val spoken = normalize(spokenName)
        if (spoken.isBlank()) return null

        var bestMatch: PlayerInfo? = null
        var bestScore = 0

        for (player in players) {
            val name = normalize(player.displayName)
            val score = score(spoken, name)
            if (score > bestScore) {
                bestScore = score
                bestMatch = player
            }
        }

        // Require minimum score to avoid false matches
        if (bestScore < 50) {
            Log.d(TAG, "No match for '$spokenName' (best: ${bestMatch?.displayName} = $bestScore)")
            return null
        }

        Log.i(TAG, "Matched '$spokenName' -> '${bestMatch?.displayName}' (${bestMatch?.playerId}, score=$bestScore)")
        return bestMatch?.playerId
    }

    /**
     * Resolve and return both player_id and display name for TTS feedback.
     */
    fun resolveWithName(spokenName: String): Pair<String, String>? {
        val players = getPlayers() ?: return null
        if (players.isEmpty()) return null

        val spoken = normalize(spokenName)
        if (spoken.isBlank()) return null

        var bestMatch: PlayerInfo? = null
        var bestScore = 0

        for (player in players) {
            val name = normalize(player.displayName)
            val score = score(spoken, name)
            if (score > bestScore) {
                bestScore = score
                bestMatch = player
            }
        }

        if (bestScore < 50 || bestMatch == null) {
            Log.d(TAG, "No match for '$spokenName' (best: ${bestMatch?.displayName} = $bestScore)")
            return null
        }

        Log.i(TAG, "Matched '$spokenName' -> '${bestMatch.displayName}' (${bestMatch.playerId}, score=$bestScore)")
        return bestMatch.playerId to bestMatch.displayName
    }

    fun invalidateCache() {
        cachedPlayers = null
        cacheTimestamp = 0
    }

    private fun getPlayers(): List<PlayerInfo>? {
        val now = System.currentTimeMillis()
        if (cachedPlayers != null && now - cacheTimestamp < CACHE_TTL_MS) {
            return cachedPlayers
        }

        val json = playersProvider() ?: return cachedPlayers // return stale if fetch fails
        val list = mutableListOf<PlayerInfo>()
        for (i in 0 until json.length()) {
            val obj = json.optJSONObject(i) ?: continue
            val playerId = obj.optString("player_id", "").takeIf { it.isNotEmpty() } ?: continue
            val displayName = obj.optString("display_name", "")
                .takeIf { it.isNotEmpty() }
                ?: obj.optString("name", playerId)
            val available = obj.optBoolean("available", true)
            list.add(PlayerInfo(playerId, displayName, available))
        }

        cachedPlayers = list
        cacheTimestamp = now
        Log.d(TAG, "Cached ${list.size} players: ${list.map { it.displayName }}")
        return list
    }

    private fun normalize(text: String): String {
        return text.lowercase()
            .replace(Regex("""[_\-.]"""), " ")
            .replace(Regex("""\bspeaker\b"""), "")
            .replace(Regex("""\bthe\b"""), "")
            .replace(Regex("""\bmy\b"""), "")
            .replace(Regex("""\bmedia player\b"""), "")
            .replace(Regex("""\bdashie\b"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun score(spoken: String, candidate: String): Int {
        // Exact match
        if (spoken == candidate) return 100

        // Spoken is contained within candidate or vice versa
        if (candidate.contains(spoken)) return 90
        if (spoken.contains(candidate) && candidate.length >= 3) return 85

        // Word overlap scoring
        val spokenWords = spoken.split(" ").filter { it.isNotBlank() }
        val candidateWords = candidate.split(" ").filter { it.isNotBlank() }
        val matchedWords = spokenWords.count { sw ->
            candidateWords.any { cw -> cw.contains(sw) || sw.contains(cw) }
        }

        if (matchedWords > 0 && spokenWords.isNotEmpty()) {
            val ratio = matchedWords.toFloat() / spokenWords.size
            return (60 + (ratio * 30)).toInt()
        }

        return 0
    }
}

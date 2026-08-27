package com.dashieapp.Dashie.halite.music

import android.util.Log
import org.json.JSONObject

/**
 * The single "play what the voice classifier resolved" entry point.
 *
 * Both voice wiring paths (the JS-bridge one in MusicComponentWiring and the
 * HA-Assist/Kotlin-intercept one in VoiceComponentWiring) used to carry their own
 * copy of this logic, and they drifted: the HA-Assist copy handed a bare search
 * string straight to `playMedia` (which expects a URI) and logged "OK"
 * unconditionally, while its twin correctly branched to `searchAndPlay`. Keeping
 * one implementation is the fix that stops that class of bug recurring.
 */
object MaVoicePlay {

    private const val TAG = "MaVoicePlay"

    /**
     * Resolve and play a `play_media` voice command.
     *
     * @param params the classifier's params — `mediaId` (required), optional
     *   `artist` and `mediaType`.
     * @return true if playback was initiated.
     */
    fun playFromVoiceParams(client: MaApiClient, entity: String, params: JSONObject): Boolean {
        val mediaId = params.optString("mediaId", "").takeIf { it.isNotEmpty() } ?: run {
            Log.w(TAG, "🎵 play_media with no mediaId — ignoring")
            return false
        }
        val artist = params.optString("artist", "").takeIf { it.isNotEmpty() }
        val mediaTypeHint = params.optString("mediaType", "").takeIf { it.isNotEmpty() }

        val query = if (artist != null) "$mediaId $artist" else mediaId

        // A bare name is a SEARCH string, not a URI, and must be resolved before play.
        val success = if (query.contains("://")) {
            client.playMedia(entity, query, MaVoiceSearchResolver.continuationForUri(query))
        } else {
            client.searchAndPlay(entity, query, mediaTypeHint)
        }

        Log.i(TAG, "🎵 play_media '$query' hint=$mediaTypeHint on $entity → ${if (success) "OK" else "FAILED"}")
        return success
    }
}

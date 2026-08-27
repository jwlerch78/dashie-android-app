package com.dashieapp.Dashie.halite.music

/**
 * Data class representing the current music player state.
 * Populated from Home Assistant media_player entity attributes.
 */
data class MusicPlayerData(
    val trackName: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val albumArtUrl: String? = null,
    val isPlaying: Boolean = false,
    val positionSeconds: Int = 0,
    val durationSeconds: Int = 0,
    val volumeLevel: Float = 0f,
    val isVolumeMuted: Boolean = false,
    val isVolumeUnavailable: Boolean = false,
    val isMinimized: Boolean = false,
    val isMaximized: Boolean = false,
    val isStrip: Boolean = false,
    val isReconnecting: Boolean = false,
    /**
     * True when the MA session token is expired (401) — the player can't
     * reconnect until the user signs in again. Distinct from [isReconnecting]
     * (a transient "waiting for MA" state polling can resolve on its own): an
     * expired session NEVER resolves by polling, so the overlay shows a
     * "sign in" prompt instead of a perpetual "Reconnecting…" spinner.
     */
    val isSessionExpired: Boolean = false,
    /**
     * True when the player is stopped/idle (MA reports neither playing nor
     * paused) — even if a stale track name lingers so [hasMedia] is still true.
     * Distinguishes a genuinely-displayed player (playing/paused) from an
     * idle/stopped placeholder, so the screensaver panel doesn't reserve space
     * for a player that isn't really shown on the dashboard.
     */
    val isStopped: Boolean = false,
    val entityId: String = "",
    val friendlyName: String = "",
    val shuffleEnabled: Boolean = false,
    /** "off" | "all" | "one" — Music Assistant repeat modes. */
    val repeatMode: String = "off"
) {
    /**
     * Check if there's actual media to display
     */
    val hasMedia: Boolean
        get() = trackName.isNotBlank()

    /**
     * Format position as MM:SS
     */
    val formattedPosition: String
        get() = formatTime(positionSeconds)

    /**
     * Format duration as MM:SS
     */
    val formattedDuration: String
        get() = formatTime(durationSeconds)

    /**
     * Progress as percentage (0.0 to 1.0)
     */
    val progress: Float
        get() = if (durationSeconds > 0) positionSeconds.toFloat() / durationSeconds else 0f

    private fun formatTime(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return "%d:%02d".format(mins, secs)
    }

    companion object {
        /**
         * Create from JSON (used by JS bridge)
         */
        fun fromJson(json: org.json.JSONObject): MusicPlayerData {
            return MusicPlayerData(
                trackName = json.optString("trackName", ""),
                artistName = json.optString("artistName", ""),
                albumName = json.optString("albumName", ""),
                albumArtUrl = json.optString("albumArtUrl", null),
                isPlaying = json.optBoolean("isPlaying", false),
                positionSeconds = json.optInt("positionSeconds", 0),
                durationSeconds = json.optInt("durationSeconds", 0),
                volumeLevel = json.optDouble("volumeLevel", 0.0).toFloat(),
                isVolumeMuted = json.optBoolean("isVolumeMuted", false),
                isVolumeUnavailable = json.optBoolean("isVolumeUnavailable", false),
                isMinimized = json.optBoolean("isMinimized", false),
                isMaximized = json.optBoolean("isMaximized", false),
                isStrip = json.optBoolean("isStrip", false),
                isReconnecting = json.optBoolean("isReconnecting", false),
                isStopped = json.optBoolean("isStopped", false),
                entityId = json.optString("entityId", ""),
                friendlyName = json.optString("friendlyName", ""),
                shuffleEnabled = json.optBoolean("shuffleEnabled", false),
                repeatMode = json.optString("repeatMode", "off")
            )
        }
    }
}

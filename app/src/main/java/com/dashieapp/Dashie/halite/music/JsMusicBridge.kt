package com.dashieapp.Dashie.halite.music

import android.util.Log

/**
 * Legacy JS↔Kotlin adapter for non-Sendspin mode.
 *
 * Handles inbound state from the WebView's HA media_player subscription
 * and outbound recently played data. When Sendspin is connected, JS updates
 * are suppressed — the coordinator handles all state via the poller.
 *
 * This adapter will be removed when all devices use Sendspin.
 */
class JsMusicBridge(
    private val musicPlayer: MusicPlayerOverlayManager,
    private val isRealtimeSourceConnected: () -> Boolean,
    private val isPollerActive: () -> Boolean,
    private val isPollerStale: () -> Boolean,
    private val isLocallyPaused: () -> Boolean,
    private val isNativeRecentlyPlayedLoaded: () -> Boolean,
    private val addRecentPlayer: (entityId: String) -> Unit
) {
    companion object {
        private const val TAG = "JsMusicBridge"
    }

    // Push metadata from MA provider's setMediaInfo — survives JS re-injection resets.
    @Volatile var pushTrackName: String = ""
    @Volatile var pushArtistName: String = ""
    @Volatile var pushAlbumName: String = ""
    @Volatile var pushAlbumArtUrl: String = ""
    @Volatile var pushDurationSeconds: Int = 0
    @Volatile var pushIsPlaying: Boolean = false

    // Optimistic volume state
    @Volatile var userSetVolume: Float = -1f
    @Volatile var lastVolumeChangeAt: Long = 0

    /**
     * Handle inbound music player state from JS/HA subscription.
     * Applies push field overrides, locallyPaused corrections, and optimistic volume.
     */
    fun onMusicPlayerUpdate(musicJson: String) {
        try {
            val json = org.json.JSONObject(musicJson)
            val data = MusicPlayerData.fromJson(json)

            // Suppress when Sendspin connected — coordinator handles all state
            if (isRealtimeSourceConnected()) return

            // Suppress when MA API poller is active and not stale
            if (isPollerActive() && !isPollerStale()) {
                Log.d(TAG, "🎵 JS update suppressed — poller active (track='${data.trackName}')")
                return
            }

            Log.d(TAG, "🎵 JS update: playing=${data.isPlaying} entity='${data.entityId}' track='${data.trackName}'")

            // Override isPlaying when user has locally paused ExoPlayer
            var correctedData = if (isLocallyPaused()) {
                val frozenPos = musicPlayer.getCurrentData().positionSeconds
                data.copy(isPlaying = false, positionSeconds = frozenPos)
            } else {
                data
            }

            // Push field override: when JS re-injection resets state and sends
            // "Unknown Track", use the metadata from MA provider's setMediaInfo.
            val jsTrackIsEmpty = correctedData.trackName.isEmpty()
                || correctedData.trackName == "Unknown Track"
            if (jsTrackIsEmpty && pushTrackName.isNotEmpty()) {
                Log.i(TAG, "🎵 JS sent empty/unknown track — using push fields: '$pushTrackName' by '$pushArtistName'")
                correctedData = correctedData.copy(
                    trackName = pushTrackName,
                    artistName = pushArtistName,
                    albumName = pushAlbumName,
                    albumArtUrl = pushAlbumArtUrl.takeIf { it.isNotEmpty() } ?: correctedData.albumArtUrl,
                    durationSeconds = if (pushDurationSeconds > 0) pushDurationSeconds else correctedData.durationSeconds,
                    isPlaying = if (!isLocallyPaused()) pushIsPlaying else false
                )
            }

            // Optimistic volume
            val now = System.currentTimeMillis()
            val volDirty = userSetVolume >= 0f && lastVolumeChangeAt > 0
            val finalData = if (volDirty) {
                val volAge = now - lastVolumeChangeAt
                val serverClose = Math.abs(correctedData.volumeLevel - userSetVolume) < 0.06f
                if ((serverClose && volAge > 8_000) || volAge > 30_000) {
                    userSetVolume = -1f
                    correctedData
                } else {
                    correctedData.copy(volumeLevel = userSetVolume)
                }
            } else {
                correctedData
            }

            Log.d(TAG, "🎵 → UI: playing=${finalData.isPlaying} locallyPaused=${isLocallyPaused()} vol=${(finalData.volumeLevel * 100).toInt()}")
            musicPlayer.updateState(finalData)
        } catch (e: Exception) {
            Log.e(TAG, "🎵 Failed to parse music JSON: $musicJson", e)
        }
    }

    fun onMusicPlayerHide() {
        Log.d(TAG, "🎵 Hiding music player")
        musicPlayer.hidePlayer()
    }

    fun onMusicPlayerEntityNotFound(entityId: String) {
        Log.w(TAG, "🎵 Default entity not in HA states: $entityId — subscription continues, will auto-detect")
    }

    fun onEntitySwitched(entityId: String) {
        Log.i(TAG, "🎵 Entity switched to: $entityId — adding to recents")
        addRecentPlayer(entityId)
    }

    fun onRecentlyPlayedUpdate(json: String) {
        if (isNativeRecentlyPlayedLoaded()) {
            Log.d(TAG, "🎵 Recently played: skipping JS data — native API already loaded")
            return
        }
        try {
            val data = RecentlyPlayedData.fromJson(json)
            Log.d(TAG, "🎵 Recently played: ${data.items.size} items received (JS)")
            musicPlayer.updateRecentlyPlayed(data)
        } catch (e: Exception) {
            Log.e(TAG, "🎵 Failed to parse recently played JSON", e)
        }
    }
}

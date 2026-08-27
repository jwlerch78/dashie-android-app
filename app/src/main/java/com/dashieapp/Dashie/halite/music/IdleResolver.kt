package com.dashieapp.Dashie.halite.music

import android.util.Log

/**
 * Resolves the idle state for this device from the list of MA groups and speakers.
 *
 * Given the parsed player data from `getPlayers()`, finds the best group/speaker
 * for this device and pushes the result to [MusicSourceCallbacks].
 *
 * Priority:
 * 1. Playing group containing this device → set as target, coordinator handles playing state
 * 1.5. Ad-hoc sync: this device has syncedTo pointing to a playing lead → set lead as target
 * 2. Idle group containing this device with a track → push idle resolution
 * 3. This device's own Sendspin player → set as target
 * 4. Fallback to configured default entity
 */
class IdleResolver(
    private val callbacks: MusicSourceCallbacks,
    private val imageUrlResolver: (String) -> String?
) {
    companion object {
        private const val TAG = "IdleResolver"
    }

    /**
     * Result of idle resolution — the target entity ID to use for the speaker drawer.
     */
    data class Result(
        val targetEntityId: String,
        val playingGroupId: String? = null
    )

    /**
     * Resolve the idle state for this device.
     *
     * @param groups All groups from getPlayers()
     * @param speakers All speakers from getPlayers()
     * @param thisDeviceId This device's Sendspin player ID (e.g., "upXXX")
     * @param currentEntityId The currently configured entity ID
     * @return The target entity ID for the speaker drawer
     */
    fun resolve(
        groups: List<SpeakerGroupDrawer.GroupInfo>,
        speakers: List<SpeakerGroupDrawer.SpeakerInfo>,
        thisDeviceId: String,
        currentEntityId: String
    ): Result {
        // Priority 1: this device is in an actively PLAYING group
        val playingGroup = groups.find { g ->
            val isGroupPlaying = g.state == "playing"
            val hostInGroup = g.memberIds.contains(currentEntityId) ||
                g.memberIds.contains(thisDeviceId) ||
                g.memberIds.any { mid -> thisDeviceId.isNotEmpty() && mid.contains(thisDeviceId) }
            isGroupPlaying && hostInGroup
        }

        if (playingGroup != null) {
            callbacks.onTargetEntityChanged(playingGroup.groupId, isPlaying = true)
            return Result(targetEntityId = playingGroup.groupId, playingGroupId = playingGroup.groupId)
        }

        // Priority 1.5: this device is ad-hoc synced to a playing lead speaker
        val thisDeviceSpeaker = speakers.find {
            thisDeviceId.isNotEmpty() && it.playerId.contains(thisDeviceId)
        }
        if (thisDeviceSpeaker != null && thisDeviceSpeaker.syncedTo.isNotEmpty()) {
            val leadSpeaker = speakers.find { it.playerId == thisDeviceSpeaker.syncedTo }
            if (leadSpeaker != null && leadSpeaker.state == "playing") {
                Log.d(TAG, "🎵 [IDLE] Ad-hoc sync: this device synced to lead '${leadSpeaker.displayName}' (${leadSpeaker.playerId})")
                callbacks.onTargetEntityChanged(leadSpeaker.playerId, isPlaying = true)
                return Result(targetEntityId = leadSpeaker.playerId)
            }
        }

        // Priority 2: idle group containing this device with a track
        val idleGroup = groups.find { g ->
            g.currentTrack.isNotEmpty() &&
                (g.memberIds.contains(thisDeviceId) ||
                 g.memberIds.any { mid -> thisDeviceId.isNotEmpty() && mid.contains(thisDeviceId) })
        }

        if (idleGroup != null) {
            Log.d(TAG, "🎵 [IDLE] Found idle group: '${idleGroup.displayName}' track='${idleGroup.currentTrack}' artist='${idleGroup.currentArtist}' dur=${idleGroup.currentDuration}")
            val imgUrl = idleGroup.currentImageUrl.let { url ->
                if (url.isEmpty()) null
                else if (url.startsWith("http")) url
                else imageUrlResolver(url)
            }
            callbacks.onIdleResolution(
                track = idleGroup.currentTrack,
                artist = idleGroup.currentArtist,
                artworkUrl = imgUrl,
                durationSec = idleGroup.currentDuration,
                entityId = idleGroup.groupId,
                displayName = idleGroup.displayName
            )
            callbacks.onTargetEntityChanged(idleGroup.groupId)
            return Result(targetEntityId = idleGroup.groupId)
        }

        // Priority 3: this device's own Sendspin player (may have an idle track)
        // (reuses thisDeviceSpeaker from Priority 1.5 above)
        val targetId = thisDeviceSpeaker?.playerId ?: currentEntityId
        if (targetId.isNotEmpty()) {
            val displayName = thisDeviceSpeaker?.displayName ?: ""
            val speakerTrack = thisDeviceSpeaker?.currentTrack ?: ""
            val speakerArtist = thisDeviceSpeaker?.currentArtist ?: ""
            val speakerImageUrl = thisDeviceSpeaker?.currentImageUrl ?: ""
            val speakerDuration = thisDeviceSpeaker?.currentDuration ?: 0
            Log.d(TAG, "🎵 [IDLE] Priority 3: own speaker '$displayName' ($targetId) track='${speakerTrack.take(25)}' artist='${speakerArtist.take(20)}' dur=$speakerDuration")
            callbacks.onTargetEntityChanged(targetId)
            val imgUrl = if (speakerImageUrl.isEmpty()) null
                else if (speakerImageUrl.startsWith("http")) speakerImageUrl
                else imageUrlResolver(speakerImageUrl)
            callbacks.onIdleResolution(
                track = speakerTrack, artist = speakerArtist, artworkUrl = imgUrl,
                durationSec = speakerDuration, entityId = targetId, displayName = displayName
            )
        }
        return Result(targetEntityId = targetId)
    }
}

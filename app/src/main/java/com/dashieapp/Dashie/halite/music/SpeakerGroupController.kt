package com.dashieapp.Dashie.halite.music

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.halite.music.sendspin.SendspinSettings

/**
 * Handles all speaker drawer user interactions.
 *
 * Manages speaker switching (with confirmation dialog), join/unjoin,
 * per-speaker volume/mute, group volume, and queue clearing.
 * Coordinates with [MusicStateCoordinator] for target entity management.
 */
class SpeakerGroupController(
    private val context: Context,
    private val musicPlayer: MusicPlayerOverlayManager,
    private val coordinator: MusicStateCoordinator,
    private val poller: MaPollerService,
    private val apiClientProvider: () -> MaApiClient?,
    private val sendMaCommand: (entityId: String, command: String, extraArgs: Map<String, String>) -> Unit,
    private val refreshSpeakers: () -> Unit,
    private val setDefaultPlayer: (entityId: String) -> Unit,
    private val setCurrentEntityId: (entityId: String) -> Unit,
    private val showDialogs: () -> com.dashieapp.Dashie.halite.sidebar.dialogs.MusicPlayerDialogs
) {
    companion object {
        private const val TAG = "SpeakerGroupCtrl"
    }

    private val thisDeviceId: String
        get() = "up${SendspinSettings.getPlayerId()}"

    // Saved volume before host device mute (for unmute restore)
    private var preMuteVolume = -1

    // ── Speaker switch / transfer ──

    /**
     * Resolve the queue entity for transfer. If the coordinator's target is an
     * individual speaker that's playing as part of a sync group, use the sync
     * group's ID (that's where the queue lives).
     */
    private fun resolveQueueEntity(): String {
        val target = coordinator.targetEntity
        if (target.isEmpty()) return ""
        // If already a sync group, use it directly
        if (target.startsWith("syncgroup_")) return target
        // Check if this speaker is part of a playing group
        val currentData = musicPlayer.getCurrentData()
        if (currentData.entityId.startsWith("syncgroup_")) return currentData.entityId
        return target
    }

    fun onTransferQueue(targetPlayerId: String, targetName: String) {
        Log.i(TAG, "🎵 Switch target to: $targetPlayerId ($targetName)")
        val currentData = musicPlayer.getCurrentData()
        val wasPlaying = currentData.isPlaying || currentData.hasMedia
        val currentGroupName = currentData.friendlyName.ifEmpty {
            coordinator.targetEntity
        }
        val currentEntityId = resolveQueueEntity()

        if (wasPlaying) {
            val displayTarget = targetName.ifEmpty { targetPlayerId }
            val displayCurrent = currentGroupName.ifEmpty { "current speaker" }
            val dialogs = showDialogs()
            dialogs.showSpeakerSwitchConfirmation(
                activity = context as android.app.Activity,
                currentGroupName = displayCurrent,
                targetGroupName = displayTarget
            ) { action ->
                when (action) {
                    "transfer" -> doTransfer(currentEntityId, targetPlayerId, targetName)
                    "switch" -> doSwitchControl(currentEntityId, targetPlayerId, targetName)
                }
            }
        } else {
            // Nothing playing — just switch control
            applySwitch(targetPlayerId, targetName)
        }
    }

    private fun doTransfer(currentEntityId: String, targetPlayerId: String, targetName: String) {
        Log.i(TAG, "🎵 Transfer: source=$currentEntityId → target=$targetPlayerId (coordinator.target=${coordinator.targetEntity})")
        // Apply switch immediately for responsive UI, but defer refreshSpeakers
        // until after the API calls complete (otherwise IdleResolver sees the
        // old group still playing and overrides the user's selection)
        val isGroup = coordinator.isGroupActive
        applySwitch(targetPlayerId, targetName, deferRefresh = true)
        val apiClient = apiClientProvider()
        if (apiClient != null) {
            Thread {
                // If target is in the current group (formal syncgroup or ad-hoc sync),
                // ungroup it first — MA can't transfer a queue to a speaker that already
                // shares the source's queue.
                if (isGroup && currentEntityId != targetPlayerId) {
                    Log.i(TAG, "🎵 Ungrouping target $targetPlayerId before transfer (source is group)")
                    apiClient.ungroupPlayer(targetPlayerId)
                    Thread.sleep(500)  // Let MA process the ungroup
                }
                // MA's transfer_queue captures track+position, stops the source,
                // copies the queue, and auto-plays on the target — all in one call.
                // Do NOT stop/clear the source ourselves — that resets the position.
                val ok = apiClient.transferQueue(currentEntityId, targetPlayerId)
                Log.i(TAG, "🎵 Transfer result: $currentEntityId → $targetPlayerId = $ok")
                if (ok) {
                    // MA sometimes pauses after target group dissolution/reformation.
                    // Check twice with delays to catch late pauses.
                    for (attempt in 1..2) {
                        Thread.sleep(3000)
                        val state = apiClient.getPlayerState(targetPlayerId)
                        val playbackState = state?.optString("playback_state", "idle") ?: "idle"
                        if (playbackState != "playing") {
                            Log.i(TAG, "🎵 Target not playing after transfer attempt $attempt ($playbackState) — sending play")
                            apiClient.sendPlayerCommand(targetPlayerId, "play")
                        } else {
                            Log.d(TAG, "🎵 Target playing after transfer (check $attempt)")
                            break
                        }
                    }
                }
                refreshSpeakers()
            }.start()
        }
    }

    private fun doSwitchControl(currentEntityId: String, targetPlayerId: String, targetName: String) {
        Log.i(TAG, "🎵 Switch control: $currentEntityId → $targetPlayerId")
        // Apply switch immediately, defer refresh until API calls complete
        applySwitch(targetPlayerId, targetName, deferRefresh = true)
        val apiClient = apiClientProvider()
        if (apiClient != null && currentEntityId.isNotEmpty()) {
            Thread {
                apiClient.sendPlayerCommand(currentEntityId, "stop")
                Log.i(TAG, "🎵 Stopped current group: $currentEntityId")
                val args = org.json.JSONObject().put("queue_id", currentEntityId)
                apiClient.sendRequest("player_queues/clear", args)
                Log.i(TAG, "🎵 Cleared queue on: $currentEntityId")
                refreshSpeakers()
            }.start()
        }
    }

    private fun applySwitch(targetPlayerId: String, targetName: String, deferRefresh: Boolean = false) {
        setDefaultPlayer(targetPlayerId)
        setCurrentEntityId(targetPlayerId)
        coordinator.setUserSelectedTarget(targetPlayerId, targetName)
        poller.forceRefresh = true
        musicPlayer.updateSpeakerDrawerCurrentTarget(targetPlayerId)
        if (!deferRefresh) refreshSpeakers()
    }

    // ── Join / unjoin ──

    fun onSpeakerJoin(playerId: String, join: Boolean) {
        // Target the current group entity (sync group or individual speaker)
        val targetEntity = coordinator.targetEntity.ifEmpty { thisDeviceId }
        Log.i(TAG, "🎵 Speaker ${if (join) "join" else "unjoin"}: $playerId → target=$targetEntity")
        val apiClient = apiClientProvider()
        if (apiClient != null) {
            Thread {
                val ok = if (join) {
                    // Add speaker to the current group — MA handles starting playback
                    apiClient.groupPlayer(playerId, targetEntity)
                } else {
                    // Remove dynamically added speaker (static members are locked in UI).
                    // Don't send stop — MA handles detaching the speaker from the group.
                    // Sending stop right after ungroup can pause the entire group.
                    apiClient.ungroupPlayer(playerId)
                }
                Log.i(TAG, "🎵 Speaker ${if (join) "join" else "unjoin"} result: $playerId = $ok")
                if (ok) {
                    // Brief delay to let MA process the group change before we poll
                    Thread.sleep(500)
                    refreshSpeakers()
                }
            }.start()
        }
    }

    // ── Per-speaker volume ──

    fun onSpeakerVolumeChange(playerId: String, percent: Int) {
        Log.i(TAG, "🎵 Speaker volume: $playerId → $percent%")
        val apiClient = apiClientProvider()
        if (apiClient != null) {
            Thread {
                val ok = apiClient.setVolume(playerId, percent)
                Log.i(TAG, "🎵 Speaker volume result: $playerId → $percent% = $ok")
            }.start()
        } else {
            Log.w(TAG, "🎵 Speaker volume: no MA API client")
        }
    }

    // ── Per-speaker mute ──

    fun onSpeakerMuteToggle(playerId: String, muted: Boolean) {
        Log.i(TAG, "🎵 Speaker mute: $playerId → $muted")
        if (playerId.contains(thisDeviceId)) {
            // Host device: control local Android audio directly
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                if (muted) {
                    val currentVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    if (currentVol > 0) preMuteVolume = currentVol
                    if (preMuteVolume <= 0) preMuteVolume = (maxVol * 0.6f).toInt()
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
                    musicPlayer.updateSpeakerVolume(playerId, 0)
                    Log.i(TAG, "🎵 Local audio muted (saved volume: $preMuteVolume/$maxVol)")
                } else {
                    val restoreVol = if (preMuteVolume > 0) preMuteVolume else (maxVol * 0.6f).toInt()
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, restoreVol, 0)
                    val pct = (restoreVol * 100 / maxVol).coerceIn(0, 100)
                    musicPlayer.updateSpeakerVolume(playerId, pct)
                    Log.i(TAG, "🎵 Local audio unmuted (restored to: $restoreVol/$maxVol, $pct%)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "🎵 Failed to mute/unmute local audio: ${e.message}")
            }
        } else {
            // Remote speaker: use MA API mute
            val apiClient = apiClientProvider()
            if (apiClient != null) {
                Thread {
                    val ok = apiClient.mutePlayer(playerId, muted)
                    Log.i(TAG, "🎵 Speaker mute result: $playerId → $muted = $ok")
                }.start()
            } else {
                Log.w(TAG, "🎵 Speaker mute: no MA API client")
            }
        }
    }

    // ── Group volume ──

    fun onGroupVolumeChange(percent: Int) {
        val targetEntity = coordinator.targetEntity.ifEmpty { thisDeviceId }
        Log.d(TAG, "🎵 Group volume: $percent% → $targetEntity")
        val apiClient = apiClientProvider()
        if (apiClient != null) Thread { apiClient.setGroupVolume(targetEntity, percent) }.start()
    }

    fun onGroupMuteToggle(muted: Boolean) {
        val targetEntity = coordinator.targetEntity.ifEmpty { thisDeviceId }
        Log.d(TAG, "🎵 Group mute: $muted → $targetEntity")
        val apiClient = apiClientProvider()
        if (apiClient != null) Thread {
            apiClient.muteGroup(targetEntity, muted)
        }.start()
    }

    // ── Clear queue ──

    fun onClearQueue(targetPlayerId: String) {
        Log.i(TAG, "🎵 Clear queue: $targetPlayerId")
        val apiClient = apiClientProvider()
        if (apiClient != null) {
            Thread {
                val args = org.json.JSONObject().put("queue_id", targetPlayerId)
                val ok = apiClient.sendRequest("player_queues/clear", args)
                Log.i(TAG, "🎵 Clear queue: $targetPlayerId → ${if (ok != null) "OK" else "FAILED"}")
                refreshSpeakers()
            }.start()
        }
    }
}

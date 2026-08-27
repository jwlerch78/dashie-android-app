package com.dashieapp.Dashie.halite.music

import android.util.Log
import com.dashieapp.Dashie.halite.music.sendspin.SendspinPlayerService
import com.dashieapp.Dashie.halite.music.sendspin.SendspinSettings

/**
 * Music Assistant + Sendspin implementation of [MusicSource].
 *
 * Outbound commands go through the MA REST API (group-aware).
 * Entity resolution uses the group entity from fetchAndUpdateSpeakers
 * for commands, and the individual Sendspin player queue for the poller.
 */
class MaSendspinSource(
    private val apiClientProvider: () -> MaApiClient?,
    private val sendMaCommand: (entityId: String, command: String, extraArgs: Map<String, String>) -> Unit,
    private val commandEntityProvider: () -> String?,
    private val pollerEntityProvider: () -> String?
) : MusicSource {

    companion object {
        private const val TAG = "MaSendspinSrc"
    }

    override val sourceId: String = "ma_sendspin"

    override val isConnected: Boolean
        get() {
            val client = SendspinPlayerService.activeClient
            return client?.isConnected == true
        }

    override val isPlaying: Boolean
        get() = isConnected && SendspinPlayerService.activeAudioPlayer != null

    // ── Outbound commands (via MA REST API) ──

    override fun playPause(entityId: String) {
        sendMaCommand(entityId, "play_pause", emptyMap())
    }

    override fun next(entityId: String) {
        sendMaCommand(entityId, "next", emptyMap())
    }

    override fun previous(entityId: String) {
        sendMaCommand(entityId, "previous", emptyMap())
    }

    override fun stop(entityId: String) {
        sendMaCommand(entityId, "stop", emptyMap())
    }

    override fun setVolume(entityId: String, level: Int) {
        sendMaCommand(entityId, "volume_set", mapOf("volume_level" to level.toString()))
    }

    override fun playMedia(entityId: String, uri: String) {
        sendMaCommand(entityId, "play_media", mapOf("uri" to uri))
    }

    // ── Entity resolution ──

    /** Command entity — the sync group or configured player (group-aware). */
    override fun getCommandEntity(): String? {
        return commandEntityProvider()
    }

    /**
     * Poller entity — uses the same entity as commands (group-aware).
     * The group entity has correct metadata for both playing and idle states.
     * Individual player queues (up{sendspinPlayerId}) can have stale/different
     * tracks that don't match the group.
     */
    override fun getPollerEntity(): String? {
        return pollerEntityProvider()
    }
}

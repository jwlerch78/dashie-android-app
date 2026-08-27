package com.dashieapp.Dashie.halite.music

import android.util.Log
import com.dashieapp.Dashie.halite.music.sendspin.SendspinPlayerService

/**
 * Routes outbound music commands to the active [MusicSource].
 *
 * Handles optimistic UI updates (instant state toggle on button press)
 * and instant local audio feedback (AudioTrack pause/resume) while the
 * command propagates through the server.
 */
class MusicCommandRouter(
    private val coordinator: MusicStateCoordinator,
    private val musicPlayer: MusicPlayerOverlayManager,
    private val sourceProvider: () -> MusicSource?,
    private val sendMaCommand: (entityId: String, command: String, extraArgs: Map<String, String>) -> Unit,
    private val apiClientProvider: () -> MaApiClient? = { null },
    private val jsBridgeFallback: JsBridgeFallback? = null
) {
    companion object {
        private const val TAG = "MusicCmdRouter"
    }

    /** Minimal JS bridge callbacks for fallback when no MA source is available. */
    interface JsBridgeFallback {
        fun sendPlayPause()
        fun sendNext()
        fun sendPrevious()
        fun sendStop()
        fun sendVolumeSet(level: Float)
    }

    fun playPause() {
        val current = musicPlayer.getCurrentData()
        val source = sourceProvider()
        val entity = coordinator.getCommandEntity()

        if (source != null && entity != null) {
            Log.d(TAG, "🎵 [CMD] Play/Pause: ${source.sourceId} for $entity | track='${current.trackName}' playing=${current.isPlaying}")
            SendspinPlayerService.notifyUserCommand()

            // Instant local audio feedback
            if (current.isPlaying) {
                SendspinPlayerService.activeAudioPlayer?.pause()
            } else {
                SendspinPlayerService.activeAudioPlayer?.resume()
            }

            source.playPause(entity)

            // Optimistic UI
            val optimistic = current.copy(isPlaying = !current.isPlaying)
            coordinator.applyOptimisticUpdate(optimistic)
        } else {
            Log.d(TAG, "🎵 [CMD] Play/Pause: JS fallback")
            jsBridgeFallback?.sendPlayPause()
        }
    }

    fun next() {
        val source = sourceProvider()
        val entity = coordinator.getCommandEntity()
        SendspinPlayerService.notifyUserCommand()

        if (source != null && entity != null) {
            Log.d(TAG, "🎵 [CMD] Next: ${source.sourceId} for $entity")
            source.next(entity)
        } else {
            jsBridgeFallback?.sendNext()
        }
    }

    fun previous() {
        val source = sourceProvider()
        val entity = coordinator.getCommandEntity()
        SendspinPlayerService.notifyUserCommand()

        if (source != null && entity != null) {
            Log.d(TAG, "🎵 [CMD] Previous: ${source.sourceId} for $entity")
            source.previous(entity)
        } else {
            jsBridgeFallback?.sendPrevious()
        }
    }

    fun stop() {
        val source = sourceProvider()
        val entity = coordinator.getCommandEntity()
        SendspinPlayerService.notifyUserCommand()

        if (source != null && entity != null) {
            Log.d(TAG, "🎵 [CMD] Stop: ${source.sourceId} for $entity")
            source.stop(entity)
        } else {
            jsBridgeFallback?.sendStop()
        }
    }

    fun setVolume(level: Float) {
        val pct = (level * 100).toInt()
        val source = sourceProvider()
        val entity = coordinator.getCommandEntity()

        Log.d(TAG, "🎵 [CMD] Volume: $pct% entity=$entity")

        if (source != null && entity != null) {
            source.setVolume(entity, pct)
        } else {
            jsBridgeFallback?.sendVolumeSet(level)
        }
    }

    /** Toggle shuffle on the current queue. Pins optimistic state for instant UI flip. */
    fun toggleShuffle() {
        val entity = coordinator.getCommandEntity() ?: return
        val client = apiClientProvider() ?: run {
            Log.w(TAG, "🎵 [CMD] Shuffle: no MA api client")
            return
        }
        val newState = !coordinator.getShuffleEnabled()
        Log.d(TAG, "🎵 [CMD] Shuffle → $newState (entity=$entity)")
        coordinator.pinShuffleState(newState)
        // Repaint immediately so the icon flips before the network round-trip.
        coordinator.applyOptimisticUpdate(
            musicPlayer.getCurrentData().copy(shuffleEnabled = newState)
        )
        Thread { client.setShuffle(entity, newState) }.start()
    }

    /** Cycle repeat mode: off → all → one → off. */
    fun cycleRepeat() {
        val entity = coordinator.getCommandEntity() ?: return
        val client = apiClientProvider() ?: run {
            Log.w(TAG, "🎵 [CMD] Repeat: no MA api client")
            return
        }
        val next = when (coordinator.getRepeatMode()) {
            "off" -> "all"
            "all" -> "one"
            else -> "off"
        }
        Log.d(TAG, "🎵 [CMD] Repeat → $next (entity=$entity)")
        coordinator.pinRepeatMode(next)
        coordinator.applyOptimisticUpdate(
            musicPlayer.getCurrentData().copy(repeatMode = next)
        )
        Thread { client.setRepeat(entity, next) }.start()
    }

    /** Seek to an absolute position in milliseconds. */
    fun seek(positionMs: Long) {
        val entity = coordinator.getCommandEntity() ?: return
        val client = apiClientProvider() ?: run {
            Log.w(TAG, "🎵 [CMD] Seek: no MA api client")
            return
        }
        val positionSec = (positionMs / 1000).toInt().coerceAtLeast(0)
        Log.d(TAG, "🎵 [CMD] Seek → ${positionSec}s (entity=$entity)")
        // Pin the interpolation baseline so the bar stays at the seeked-to
        // position; poll updates carrying MA's pre-seek elapsed_time are
        // suppressed for the pin window.
        coordinator.pinPosition(positionSec)
        coordinator.applyOptimisticUpdate(
            musicPlayer.getCurrentData().copy(positionSeconds = positionSec)
        )
        Thread { client.seek(entity, positionSec) }.start()
    }

    fun playMedia(uri: String) {
        val source = sourceProvider()
        val entity = coordinator.getCommandEntity()

        if (source != null && entity != null) {
            Log.d(TAG, "🎵 [CMD] Play media: ${source.sourceId} for $entity uri='${uri.take(40)}'")
            SendspinPlayerService.notifyUserCommand()
            source.playMedia(entity, uri)
        } else {
            Log.w(TAG, "🎵 [CMD] Play media: no source/entity")
        }
    }
}

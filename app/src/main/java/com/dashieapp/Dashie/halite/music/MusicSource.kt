package com.dashieapp.Dashie.halite.music

/**
 * Generalized interface for a music service backend.
 *
 * Implementations handle outbound commands and entity resolution for a
 * specific music provider (e.g., Music Assistant + Sendspin, Spotify).
 * Inbound state is pushed to [MusicSourceCallbacks] which the
 * [MusicStateCoordinator] implements.
 */
interface MusicSource {
    /** Unique identifier for this source (e.g., "ma_sendspin", "spotify"). */
    val sourceId: String

    /** Whether this source is currently connected and able to receive/send. */
    val isConnected: Boolean

    /** Whether audio is currently playing through this source. */
    val isPlaying: Boolean

    // ── Outbound commands ──

    fun playPause(entityId: String)
    fun next(entityId: String)
    fun previous(entityId: String)
    fun stop(entityId: String)
    fun setVolume(entityId: String, level: Int)
    fun playMedia(entityId: String, uri: String)

    // ── Entity resolution ──

    /** The entity ID to use for outbound commands (group-aware). */
    fun getCommandEntity(): String?

    /** The entity ID the poller should query for position/duration. */
    fun getPollerEntity(): String?
}

/**
 * Callbacks for inbound state from a music source.
 *
 * Implemented by [MusicStateCoordinator] to receive updates from any
 * music source. Methods are source-agnostic — the coordinator merges
 * data from whatever source is active.
 */
interface MusicSourceCallbacks {
    /** Play/pause state changed (real-time, e.g., from Sendspin WebSocket). */
    fun onPlaybackStateChanged(isPlaying: Boolean)

    /** Volume changed on the local device. */
    fun onVolumeChanged(volume: Int, muted: Boolean)

    /** Poller received updated track metadata from the server. */
    fun onPollerTrackUpdate(
        track: String, artist: String, album: String, artworkUrl: String?,
        positionSec: Double, durationSec: Int, isPlaying: Boolean,
        entityId: String, friendlyName: String,
        shuffleEnabled: Boolean = false,
        repeatMode: String = "off",
        // True when MA reports the player stopped/idle (neither playing nor
        // paused) even though a stale track lingers. Lets the UI tell a real
        // paused player from an idle placeholder.
        isStopped: Boolean = false
    )

    /** Poller returned idle state (no track playing). */
    fun onPollerIdle(entityId: String, friendlyName: String, volume: Float)

    /** Idle resolution found a group with track metadata for this device. */
    fun onIdleResolution(
        track: String, artist: String, artworkUrl: String?,
        durationSec: Int, entityId: String, displayName: String
    )

    /**
     * The target entity for commands changed (e.g., group detected by fetchAndUpdateSpeakers).
     * @param entityId The detected entity
     * @param isPlaying True when this is a playing group containing this device.
     *                  Playing groups override userSelectedTarget.
     */
    fun onTargetEntityChanged(entityId: String, isPlaying: Boolean = false)
}

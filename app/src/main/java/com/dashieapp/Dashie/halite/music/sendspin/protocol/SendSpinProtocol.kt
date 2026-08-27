package com.dashieapp.Dashie.halite.music.sendspin.protocol

/**
 * SendSpin Protocol constants and data classes.
 *
 * Protocol spec: https://www.sendspin-audio.com/spec/
 */
object SendSpinProtocol {
    const val VERSION = 1
    const val ENDPOINT_PATH = "/sendspin"

    /**
     * Binary message header: 1 byte type + 8 bytes big-endian int64 timestamp.
     */
    const val BINARY_HEADER_SIZE_BYTES = 9

    /**
     * Binary message type identifiers.
     */
    object BinaryType {
        const val AUDIO = 4
        const val ARTWORK_BASE = 8  // 8-11 for channels 0-3
        const val VISUALIZER = 16
    }

    /**
     * Audio format constants.
     */
    object AudioFormat {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val CHANNELS_MONO = 1
        const val BIT_DEPTH = 16
        const val DEFAULT_CODEC = "pcm"
    }

    /**
     * Artwork request constants for client/hello handshake.
     */
    object Artwork {
        const val REQUEST_SIZE = 500  // Requested artwork width/height in pixels
    }

    /**
     * Time synchronization constants.
     *
     * Uses NTP-style best-of-N: send N packets, pick the one with lowest RTT.
     * This filters out network jitter by selecting the measurement with least congestion.
     */
    object TimeSync {
        const val INTERVAL_MS = 250L          // Send time sync 4x per second
        const val BURST_COUNT = 10            // Send 10 packets per burst
        const val BURST_DELAY_MS = 50L        // 50ms between burst packets
    }

    /**
     * Buffer duration targets (seconds).
     *
     * The server's BufferTracker paces delivery by wire bytes; we calculate
     * the byte cap from these durations using the highest-bitrate PCM format
     * we advertise. This keeps decoded-PCM memory bounded regardless of codec:
     * - PCM: ~DURATION seconds in memory
     * - FLAC (~50% compression): ~2x DURATION seconds, still reasonable
     */
    object Buffer {
        const val DURATION_NORMAL_SEC = 35    // 30s target + 5s sync headroom
        const val DURATION_LOW_MEM_SEC = 10
    }

    /**
     * Protocol message type identifiers.
     */
    object MessageType {
        const val CLIENT_HELLO = "client/hello"
        const val SERVER_HELLO = "server/hello"
        const val CLIENT_TIME = "client/time"
        const val SERVER_TIME = "server/time"
        const val CLIENT_STATE = "client/state"
        const val SERVER_STATE = "server/state"
        const val CLIENT_COMMAND = "client/command"
        const val SERVER_COMMAND = "server/command"
        const val CLIENT_GOODBYE = "client/goodbye"
        const val GROUP_UPDATE = "group/update"
        const val STREAM_START = "stream/start"
        const val STREAM_END = "stream/end"
        const val STREAM_CLEAR = "stream/clear"
        const val CLIENT_SYNC_OFFSET = "client/sync_offset"
    }

    /**
     * Supported client roles.
     */
    object Roles {
        const val PLAYER = "player@v1"
        const val CONTROLLER = "controller@v1"
        const val METADATA = "metadata@v1"
        const val ARTWORK = "artwork@v1"
    }
}

/**
 * A time sync measurement from NTP-style exchange.
 */
data class TimeMeasurement(
    val offset: Long,
    val rtt: Long,
    val clientReceived: Long
)

/**
 * Progress information from server/state metadata.
 * Per spec: nested progress object with track_progress, track_duration, playback_speed.
 *
 * @param trackProgress Current position in milliseconds
 * @param trackDuration Total track duration in milliseconds
 * @param playbackSpeed Speed multiplier (1000 = 1.0x normal speed)
 */
data class TrackProgress(
    val trackProgress: Long,
    val trackDuration: Long,
    val playbackSpeed: Int = 1000  // Default to normal speed
)

/**
 * Track metadata from server/state messages.
 * Per spec: includes timestamp, nested progress, and optional fields.
 *
 * @param timestamp Server timestamp when metadata was captured (microseconds)
 * @param title Track title
 * @param artist Track artist
 * @param albumArtist Album artist (may differ from track artist for compilations)
 * @param album Album name
 * @param artworkUrl URL to album artwork
 * @param year Release year
 * @param track Track number (1-indexed)
 * @param progress Progress information (position, duration, speed)
 */
data class TrackMetadata(
    val timestamp: Long,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val artworkUrl: String,
    val year: Int,
    val track: Int,
    val progress: TrackProgress
) {
    // Convenience properties for backwards compatibility
    val durationMs: Long get() = progress.trackDuration
    val positionMs: Long get() = progress.trackProgress
}

/**
 * Audio stream configuration from stream/start messages.
 */
data class StreamConfig(
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    val codecHeader: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StreamConfig) return false

        if (codec != other.codec) return false
        if (sampleRate != other.sampleRate) return false
        if (channels != other.channels) return false
        if (bitDepth != other.bitDepth) return false
        if (codecHeader != null) {
            if (other.codecHeader == null) return false
            if (!codecHeader.contentEquals(other.codecHeader)) return false
        } else if (other.codecHeader != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = codec.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channels
        result = 31 * result + bitDepth
        result = 31 * result + (codecHeader?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Group information from group/update messages.
 */
data class GroupInfo(
    val groupId: String,
    val groupName: String,
    val playbackState: String
)

/**
 * Result from parsing server/hello message.
 */
data class ServerHelloResult(
    val serverName: String,
    val serverId: String,
    val activeRoles: List<String>,
    val connectionReason: String
)

/**
 * Result from parsing server/command message.
 */
sealed class ServerCommandResult {
    data class Volume(val volume: Int) : ServerCommandResult()
    data class Mute(val muted: Boolean) : ServerCommandResult()
    data class Unknown(val command: String) : ServerCommandResult()
}

/**
 * Result from parsing client/sync_offset message.
 */
data class SyncOffsetResult(
    val playerId: String,
    val offsetMs: Double,
    val source: String
)

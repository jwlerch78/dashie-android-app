package com.dashieapp.Dashie.halite.music.sendspin.protocol

import android.util.Log
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.halite.music.sendspin.SendspinTimeFilter
import com.dashieapp.Dashie.halite.music.sendspin.protocol.message.BinaryMessageParser
import com.dashieapp.Dashie.halite.music.sendspin.protocol.message.MessageBuilder
import com.dashieapp.Dashie.halite.music.sendspin.protocol.message.MessageParser
import com.dashieapp.Dashie.halite.music.sendspin.protocol.message.parse
import com.dashieapp.Dashie.halite.music.sendspin.protocol.timesync.TimeSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer

/**
 * Abstract base class for SendSpin protocol handling.
 *
 * Contains shared protocol logic for both SendSpinClient and SendSpinServer:
 * - Message building and sending
 * - Message parsing and dispatching
 * - Time synchronization
 * - Binary message handling
 *
 * Subclasses implement transport-specific behavior (OkHttp vs Java-WebSocket)
 * and connection state management.
 *
 * @param tag Log tag for debugging
 */
abstract class SendSpinProtocolHandler(
    protected val tag: String
) {
    // Protocol state
    protected var handshakeComplete = false
    protected var currentVolume: Int = 100
    protected var currentMuted: Boolean = false
    protected var currentSyncState: String = "synchronized"  // "synchronized" or "error"

    // Stream active tracking (mirrors CLI _stream_active)
    private var _streamActive = false
    private var _currentStreamConfig: StreamConfig? = null

    // Last received values for change detection (avoids unnecessary UI recomposition)
    private var lastMetadata: TrackMetadata? = null
    private var lastPlaybackState: String? = null
    private var lastGroupInfo: GroupInfo? = null

    // Time sync manager (lazy initialized by subclass)
    protected var timeSyncManager: TimeSyncManager? = null

    // ========== Abstract Transport Methods ==========

    /**
     * Send a text message over the WebSocket.
     */
    protected abstract fun sendTextMessage(text: String)

    /**
     * Get the coroutine scope for async operations.
     */
    protected abstract fun getCoroutineScope(): CoroutineScope

    /**
     * Get the time filter for this connection.
     */
    abstract fun getTimeFilter(): SendspinTimeFilter

    /**
     * Whether the device is in low-memory mode (smaller buffer target).
     */
    protected abstract fun isLowMemoryMode(): Boolean

    /**
     * Get the client ID for this connection.
     */
    protected abstract fun getClientId(): String

    /**
     * Get the device name for this connection.
     */
    protected abstract fun getDeviceName(): String

    // ========== Abstract Event Callbacks ==========

    /**
     * Called when handshake completes with server.
     */
    protected abstract fun onHandshakeComplete(serverName: String, serverId: String)

    /**
     * Called when track metadata is updated.
     */
    protected abstract fun onMetadataUpdate(metadata: TrackMetadata)

    /**
     * Called when playback state changes.
     */
    protected abstract fun onPlaybackStateChanged(state: String)

    /**
     * Called when server sends a volume command.
     */
    protected abstract fun onVolumeCommand(volume: Int)

    /**
     * Called when server sends a mute command.
     */
    protected abstract fun onMuteCommand(muted: Boolean)

    /**
     * Called when group info is updated.
     */
    protected abstract fun onGroupUpdate(info: GroupInfo)

    /**
     * Called when audio stream starts.
     */
    protected abstract fun onStreamStart(config: StreamConfig)

    /**
     * Called when stream clear is requested.
     */
    protected abstract fun onStreamClear()

    /**
     * Called when stream ends (server terminates playback).
     */
    protected abstract fun onStreamEnd()

    /**
     * Called when audio chunk is received.
     */
    protected abstract fun onAudioChunk(timestampMicros: Long, audioData: ByteArray)

    /**
     * Called when artwork is received.
     */
    protected abstract fun onArtwork(channel: Int, payload: ByteArray)

    /**
     * Called when sync offset is received from GroupSync.
     */
    protected abstract fun onSyncOffsetApplied(offsetMs: Double, source: String)

    // ========== Protocol Message Sending ==========

    /**
     * Get the manufacturer name for device identification.
     */
    protected abstract fun getManufacturer(): String

    /**
     * Get the supported audio formats for the client/hello handshake.
     */
    protected abstract fun getSupportedFormats(): List<MessageBuilder.FormatEntry>

    /**
     * Send client/hello message to start handshake.
     *
     * Buffer capacity is computed from the format list and target duration
     * so the wire-byte cap scales with the highest PCM bitrate we advertise.
     */
    protected fun sendClientHello() {
        val formats = getSupportedFormats()
        val bufferDuration = if (isLowMemoryMode()) {
            SendSpinProtocol.Buffer.DURATION_LOW_MEM_SEC
        } else {
            SendSpinProtocol.Buffer.DURATION_NORMAL_SEC
        }
        val bufferCapacity = MessageBuilder.calculateBufferCapacity(formats, bufferDuration)
        val text = MessageBuilder.buildClientHello(
            clientId = getClientId(),
            deviceName = getDeviceName(),
            bufferCapacity = bufferCapacity,
            manufacturer = getManufacturer(),
            supportedFormats = formats
        )
        sendTextMessage(text)
        Log.d(tag, "Sent client/hello: ${text.take(500)}")
    }

    /**
     * Send client/time message for clock synchronization.
     */
    protected fun sendClientTime() {
        val clientTransmitted = System.nanoTime() / 1000 // Convert to microseconds
        sendTextMessage(MessageBuilder.buildClientTime(clientTransmitted))
    }

    /**
     * Send goodbye message before disconnecting.
     */
    protected fun sendGoodbye(reason: String) {
        if (!handshakeComplete) return
        sendTextMessage(MessageBuilder.buildGoodbye(reason))
    }

    /**
     * Send player state update (volume/muted/sync state).
     */
    protected fun sendPlayerStateUpdate() {
        val delayMs = getTimeFilter().staticDelayMs
        sendTextMessage(MessageBuilder.buildPlayerState(currentVolume, currentMuted, currentSyncState, delayMs))
    }

    /**
     * Set sync state and notify server.
     *
     * Per spec: report "synchronized" when locked to server timeline,
     * report "error" when unable to maintain sync (buffer underrun, clock issues).
     *
     * @param syncState Either "synchronized" or "error"
     */
    fun setSyncState(syncState: String) {
        if (syncState != "synchronized" && syncState != "error") {
            Log.w(tag, "Invalid sync state: $syncState (must be 'synchronized' or 'error')")
            return
        }
        if (currentSyncState != syncState) {
            currentSyncState = syncState
            Log.d(tag, "Sync state changed to: $syncState")
            if (handshakeComplete) {
                sendPlayerStateUpdate()
            }
        }
    }

    /**
     * Send a media command (play, pause, next, previous, switch).
     */
    fun sendCommand(command: String) {
        sendTextMessage(MessageBuilder.buildCommand(command))
    }

    // ========== Player State Methods ==========

    /**
     * Set volume and notify server.
     *
     * @param volume Volume level from 0.0 to 1.0
     */
    fun setVolume(volume: Double) {
        val volumePercent = (volume * 100).toInt().coerceIn(0, 100)
        currentVolume = volumePercent
        Log.d(tag, "setVolume: $volumePercent%")
        sendPlayerStateUpdate()
    }

    /**
     * Set muted state and notify server.
     */
    fun setMuted(muted: Boolean) {
        currentMuted = muted
        Log.d(tag, "setMuted: $muted")
        sendPlayerStateUpdate()
    }

    /**
     * Set initial volume before handshake.
     *
     * @param volume Volume level from 0 to 100
     * @param muted Whether audio is muted
     */
    fun setInitialVolume(volume: Int, muted: Boolean = false) {
        currentVolume = volume.coerceIn(0, 100)
        currentMuted = muted
        Log.d(tag, "Initial volume set: $currentVolume, muted=$currentMuted")
    }

    // ========== Time Sync ==========

    /**
     * Start time synchronization.
     */
    protected fun startTimeSync() {
        val manager = timeSyncManager
        if (manager != null && !manager.isRunning) {
            manager.start(getCoroutineScope())
        }
    }

    /**
     * Stop time synchronization.
     */
    protected fun stopTimeSync() {
        timeSyncManager?.stop()
    }

    /**
     * Initialize time sync manager.
     */
    protected fun initTimeSyncManager(timeFilter: SendspinTimeFilter) {
        timeSyncManager = TimeSyncManager(
            timeFilter = timeFilter,
            sendClientTime = { sendClientTime() },
            tag = tag
        )
    }

    // ========== Message Handling ==========

    /**
     * Handle incoming text (JSON) message.
     * Dispatches to appropriate handler based on message type.
     */
    protected fun handleTextMessage(text: String) {
        Log.d(tag, "Received: ${text.take(500)}")

        try {
            val json = Json.parseToJsonElement(text).jsonObject
            val type = json["type"]?.jsonPrimitive?.contentOrNull ?: return
            val payload = json["payload"]?.jsonObject

            when (type) {
                SendSpinProtocol.MessageType.SERVER_HELLO -> handleServerHello(payload)
                SendSpinProtocol.MessageType.SERVER_TIME -> handleServerTime(payload)
                SendSpinProtocol.MessageType.SERVER_STATE -> handleServerState(payload)
                SendSpinProtocol.MessageType.SERVER_COMMAND -> handleServerCommand(payload)
                SendSpinProtocol.MessageType.GROUP_UPDATE -> handleGroupUpdate(payload)
                SendSpinProtocol.MessageType.STREAM_START -> handleStreamStart(payload)
                SendSpinProtocol.MessageType.STREAM_END -> handleStreamEnd(payload)
                SendSpinProtocol.MessageType.STREAM_CLEAR -> handleStreamClear()
                SendSpinProtocol.MessageType.CLIENT_SYNC_OFFSET -> handleClientSyncOffset(payload)
                else -> Log.d(tag, "Unhandled message type: $type")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse message: ${text.take(100)}", e)
        }
    }

    protected open fun handleServerHello(payload: JsonObject?) {
        val result = MessageParser.parseServerHello(payload, "Unknown")
        if (result == null) {
            Log.e(tag, "Failed to parse server/hello")
            return
        }

        Log.i(tag, "server/hello: name=${result.serverName}, id=${result.serverId}, reason=${result.connectionReason}")
        Log.d(tag, "Active roles: ${result.activeRoles}")

        handshakeComplete = true

        // Clear cached values so the first post-handshake messages always propagate
        _streamActive = false
        _currentStreamConfig = null
        lastMetadata = null
        lastPlaybackState = null
        lastGroupInfo = null

        onHandshakeComplete(result.serverName, result.serverId)

        sendPlayerStateUpdate()
        startTimeSync()
    }

    protected fun handleServerTime(payload: JsonObject?) {
        val clientReceived = System.nanoTime() / 1000
        val measurement = MessageParser.parseServerTime(payload, clientReceived)

        if (measurement != null) {
            timeSyncManager?.onServerTime(measurement)
        }
    }

    protected fun handleServerState(payload: JsonObject?) {
        val (metadata, state) = MessageParser.parseServerState(payload)

        if (metadata != null) {
            lastMetadata = metadata
            onMetadataUpdate(metadata)
        }

        if (state != null && state != lastPlaybackState) {
            lastPlaybackState = state
            onPlaybackStateChanged(state)
        }
    }

    protected fun handleServerCommand(payload: JsonObject?) {
        when (val result = MessageParser.parseServerCommand(payload)) {
            is ServerCommandResult.Volume -> {
                Log.d(tag, "Server command: set volume to ${result.volume}%")
                currentVolume = result.volume
                onVolumeCommand(result.volume)
                sendPlayerStateUpdate()
            }
            is ServerCommandResult.Mute -> {
                Log.d(tag, "Server command: set mute to ${result.muted}")
                currentMuted = result.muted
                onMuteCommand(result.muted)
                sendPlayerStateUpdate()
            }
            is ServerCommandResult.Unknown -> {
                Log.d(tag, "Unknown player command: ${result.command}")
            }
            null -> { /* No player command in payload */ }
        }
    }

    protected fun handleGroupUpdate(payload: JsonObject?) {
        val info = MessageParser.parseGroupUpdate(payload)
        if (info != null) {
            lastGroupInfo = info
            Log.v(tag, "group/update: id=${info.groupId}, name=${info.groupName}, state=${info.playbackState}")
            onGroupUpdate(info)
        }
    }

    protected fun handleStreamStart(payload: JsonObject?) {
        val config = MessageParser.parseStreamStart(payload)
        if (config == null) return

        val formatChanged = _streamActive && config != _currentStreamConfig
        if (_streamActive) {
            if (formatChanged) {
                Log.i(tag, "Stream format changed: codec=${config.codec}, rate=${config.sampleRate}, ch=${config.channels}, bits=${config.bitDepth} - reconfiguring pipeline")
            } else {
                Log.d(tag, "Stream restart (same format): codec=${config.codec}, rate=${config.sampleRate}")
            }
        } else {
            Log.i(tag, "Stream started: codec=${config.codec}, rate=${config.sampleRate}, ch=${config.channels}, bits=${config.bitDepth}, header=${config.codecHeader?.size ?: 0} bytes")
        }

        _streamActive = true
        _currentStreamConfig = config
        onStreamStart(config)
    }

    protected fun handleStreamClear() {
        Log.v(tag, "Stream clear - flushing audio buffers")
        onStreamClear()
    }

    protected fun handleStreamEnd(payload: JsonObject?) {
        val rolesArray = payload?.get("roles")?.jsonArray
        val roles = rolesArray?.map { it.jsonPrimitive.content }

        if (roles != null && SendSpinProtocol.Roles.PLAYER !in roles) {
            // DROP: this discards a STOP signal, so it must never be quiet (standing rule 2).
            // Roles.PLAYER is the version-pinned literal "player@v1"; a server that labels the
            // same role differently (say "player@v2") has its stream/end silently thrown away
            // here, and the audio then drains for seconds — indistinguishable, in a diagnostics
            // report, from a server that never sent one. Locally MA 2.8.8 sends stream/end with
            // an EMPTY payload, so roles is null and this branch is never taken; that is exactly
            // why a mismatch would go unnoticed until a user with a different server complains.
            // PersistentLog, not Log.d: the whole point is that it survives into a bug report.
            Log.w(tag, "DROP: stream/end ignored — roles=$roles lack ${SendSpinProtocol.Roles.PLAYER}")
            PersistentLog.warn(
                "SENDSPIN",
                "DROP: stream/end IGNORED — roles=$roles lack '${SendSpinProtocol.Roles.PLAYER}'; audio will drain"
            )
            return
        }

        Log.i(tag, "Stream end - server terminated playback (roles=${roles ?: "all"})")
        _streamActive = false
        _currentStreamConfig = null
        onStreamEnd()
    }

    protected fun handleClientSyncOffset(payload: JsonObject?) {
        val result = MessageParser.parseSyncOffset(payload)
        if (result == null) {
            Log.w(tag, "client/sync_offset: missing or invalid payload")
            return
        }

        Log.i(tag, "client/sync_offset: offset=${result.offsetMs}ms from ${result.source}")

        val clampedOffset = result.offsetMs.coerceIn(-5000.0, 5000.0)
        if (clampedOffset != result.offsetMs) {
            Log.w(tag, "client/sync_offset: clamped from ${result.offsetMs}ms to ${clampedOffset}ms")
        }

        getTimeFilter().staticDelayMs = clampedOffset
        Log.d(tag, "client/sync_offset: static delay set to ${clampedOffset}ms")

        onSyncOffsetApplied(clampedOffset, result.source)
    }

    // ========== Binary Message Handling ==========

    /**
     * Handle binary message from ByteArray (SendSpinClient via Ktor/WebRTC).
     */
    protected fun handleBinaryMessage(bytes: ByteArray) {
        val message = BinaryMessageParser.parse(bytes)
        if (message == null) {
            Log.w(tag, "Failed to parse binary (${bytes.size} bytes, type=${if (bytes.isNotEmpty()) bytes[0].toInt() and 0xFF else -1})")
        } else {
            dispatchBinaryMessage(message)
        }
    }

    /**
     * Handle binary message from Java-WebSocket ByteBuffer (SendSpinServer).
     */
    protected fun handleBinaryMessage(bytes: ByteBuffer) {
        val message = BinaryMessageParser.parse(bytes)
        if (message != null) {
            dispatchBinaryMessage(message)
        }
    }

    /**
     * Dispatch parsed binary message to appropriate handler.
     */
    private fun dispatchBinaryMessage(message: BinaryMessageParser.BinaryMessage) {
        when (message) {
            is BinaryMessageParser.BinaryMessage.Audio -> {
                onAudioChunk(message.timestampMicros, message.payload)
            }
            is BinaryMessageParser.BinaryMessage.Artwork -> {
                Log.v(tag, "Received artwork channel ${message.channel}: ${message.payload.size} bytes")
                onArtwork(message.channel, message.payload)
            }
            is BinaryMessageParser.BinaryMessage.Visualizer -> {
                // Visualization data - currently not used, no logging needed
            }
            is BinaryMessageParser.BinaryMessage.Unknown -> {
                Log.v(tag, "Unknown binary message type: ${message.type}")
            }
        }
    }
}

package com.dashieapp.Dashie.halite.music.sendspin.protocol.message

import com.dashieapp.Dashie.halite.music.sendspin.protocol.GroupInfo
import com.dashieapp.Dashie.halite.music.sendspin.protocol.SendSpinProtocol
import com.dashieapp.Dashie.halite.music.sendspin.protocol.ServerCommandResult
import com.dashieapp.Dashie.halite.music.sendspin.protocol.ServerHelloResult
import com.dashieapp.Dashie.halite.music.sendspin.protocol.StreamConfig
import com.dashieapp.Dashie.halite.music.sendspin.protocol.SyncOffsetResult
import com.dashieapp.Dashie.halite.music.sendspin.protocol.TimeMeasurement
import com.dashieapp.Dashie.halite.music.sendspin.protocol.TrackMetadata
import com.dashieapp.Dashie.halite.music.sendspin.protocol.TrackProgress
import android.util.Log
import com.dashieapp.Dashie.halite.music.sendspin.Platform
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

object MessageParser {
    private const val TAG = "MessageParser"

    fun parseServerHello(payload: JsonObject?, defaultName: String): ServerHelloResult? {
        if (payload == null) {
            Log.e(TAG, "server/hello missing payload")
            return null
        }

        val serverName = payload.stringOrDefault("name", defaultName)
        val serverId = payload.stringOrDefault("server_id", "")
        val connectionReason = payload.stringOrDefault("connection_reason", "discovery")

        val activeRoles = payload["active_roles"]?.jsonArray?.map {
            it.jsonPrimitive.content
        } ?: emptyList()

        return ServerHelloResult(
            serverName = serverName,
            serverId = serverId,
            activeRoles = activeRoles,
            connectionReason = connectionReason
        )
    }

    fun parseServerTime(payload: JsonObject?, clientReceivedMicros: Long): TimeMeasurement? {
        if (payload == null) return null

        val clientTransmitted = payload.longOrDefault("client_transmitted", 0)
        val serverReceived = payload.longOrDefault("server_received", 0)
        val serverTransmitted = payload.longOrDefault("server_transmitted", 0)

        if (clientTransmitted == 0L || serverReceived == 0L || serverTransmitted == 0L) {
            Log.w(TAG, "Invalid server/time payload")
            return null
        }

        val offset = ((serverReceived - clientTransmitted) + (serverTransmitted - clientReceivedMicros)) / 2
        val rtt = (clientReceivedMicros - clientTransmitted) - (serverTransmitted - serverReceived)

        return TimeMeasurement(offset, rtt, clientReceivedMicros)
    }

    fun parseServerState(payload: JsonObject?): Pair<TrackMetadata?, String?> {
        if (payload == null) return Pair(null, null)

        // Note: JsonElement?.jsonObject THROWS IllegalArgumentException when the element
        // is JsonNull (which is different from the key being absent — absent returns
        // a plain Kotlin null). MA's server/state frames can include `"progress": null`
        // and similar, so we use `as? JsonObject` instead, which safely returns null
        // for both absent and explicit-null cases.
        val metadata = (payload["metadata"] as? JsonObject)?.let { metadataObj ->
            fun optStringClean(key: String) =
                metadataObj[key]?.jsonPrimitive?.contentOrNull?.takeUnless { it == "null" } ?: ""

            val timestamp = metadataObj.longOrDefault("timestamp", 0)
            val title = optStringClean("title")
            val artist = optStringClean("artist")
            val albumArtist = optStringClean("album_artist")
            val album = optStringClean("album")
            val artworkUrl = optStringClean("artwork_url")
            val year = metadataObj.intOrDefault("year", 0)
            val track = metadataObj.intOrDefault("track", 0)

            val progress = (metadataObj["progress"] as? JsonObject)?.let { progressObj ->
                TrackProgress(
                    trackProgress = progressObj.longOrDefault("track_progress", 0),
                    trackDuration = progressObj.longOrDefault("track_duration", 0),
                    playbackSpeed = progressObj.intOrDefault("playback_speed", 1000)
                )
            } ?: run {
                TrackProgress(
                    trackProgress = metadataObj.longOrDefault("position_ms", 0),
                    trackDuration = metadataObj.longOrDefault("duration_ms", 0),
                    playbackSpeed = 1000
                )
            }

            TrackMetadata(
                timestamp = timestamp,
                title = title,
                artist = artist,
                albumArtist = albumArtist,
                album = album,
                artworkUrl = artworkUrl,
                year = year,
                track = track,
                progress = progress
            )
        }

        val state = payload.stringOrDefault("state", "").takeIf { it.isNotEmpty() }

        return Pair(metadata, state)
    }

    fun parseServerCommand(payload: JsonObject?): ServerCommandResult? {
        if (payload == null) return null

        val player = (payload["player"] as? JsonObject) ?: return null
        val command = player.stringOrDefault("command", "")

        return when (command) {
            "volume" -> {
                val volume = player.intOrDefault("volume", -1)
                if (volume in 0..100) {
                    ServerCommandResult.Volume(volume)
                } else {
                    null
                }
            }
            "mute" -> {
                val muted = player.booleanOrDefault("mute", false)
                ServerCommandResult.Mute(muted)
            }
            else -> {
                if (command.isNotEmpty()) {
                    ServerCommandResult.Unknown(command)
                } else {
                    null
                }
            }
        }
    }

    fun parseGroupUpdate(payload: JsonObject?): GroupInfo? {
        if (payload == null) return null

        val groupId = payload.stringOrDefault("group_id", "")
        val groupName = payload.stringOrDefault("group_name", "")
        val playbackState = payload.stringOrDefault("playback_state", "")

        return GroupInfo(groupId, groupName, playbackState)
    }

    fun parseStreamStart(payload: JsonObject?): StreamConfig? {
        if (payload == null) return null

        val player = (payload["player"] as? JsonObject) ?: return null

        val codec = player.stringOrDefault("codec", SendSpinProtocol.AudioFormat.DEFAULT_CODEC)
        val sampleRate = player.intOrDefault("sample_rate", SendSpinProtocol.AudioFormat.SAMPLE_RATE)
        val channels = player.intOrDefault("channels", SendSpinProtocol.AudioFormat.CHANNELS)
        val bitDepth = player.intOrDefault("bit_depth", SendSpinProtocol.AudioFormat.BIT_DEPTH)

        val codecHeader = player["codec_header"]?.jsonPrimitive?.contentOrNull?.let { base64 ->
            try {
                Platform.base64Decode(base64)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode codec_header")
                null
            }
        }

        return StreamConfig(codec, sampleRate, channels, bitDepth, codecHeader)
    }

    fun parseSyncOffset(payload: JsonObject?): SyncOffsetResult? {
        if (payload == null) return null

        val playerId = payload.stringOrDefault("player_id", "")
        val offsetMs = payload.doubleOrDefault("offset_ms", 0.0)
        val source = payload.stringOrDefault("source", "unknown")

        return SyncOffsetResult(playerId, offsetMs, source)
    }

    // Helper extensions for safe JSON access with defaults

    private fun JsonObject.stringOrDefault(key: String, default: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: default

    private fun JsonObject.longOrDefault(key: String, default: Long): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: default

    private fun JsonObject.intOrDefault(key: String, default: Int): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: default

    private fun JsonObject.doubleOrDefault(key: String, default: Double): Double =
        this[key]?.jsonPrimitive?.doubleOrNull ?: default

    private fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean =
        this[key]?.jsonPrimitive?.booleanOrNull ?: default
}

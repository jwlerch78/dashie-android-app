package com.dashieapp.Dashie.halite.music.sendspin.protocol.message

import com.dashieapp.Dashie.halite.music.sendspin.protocol.SendSpinProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object MessageBuilder {

    data class FormatEntry(
        val codec: String,
        val sampleRate: Int,
        val channels: Int,
        val bitDepth: Int
    )

    fun buildClientHello(
        clientId: String,
        deviceName: String,
        bufferCapacity: Int,
        manufacturer: String,
        supportedFormats: List<FormatEntry>,
        lowMemoryMode: Boolean = false
    ): String {
        val message = buildJsonObject {
            put("type", SendSpinProtocol.MessageType.CLIENT_HELLO)
            put("payload", buildJsonObject {
                put("client_id", clientId)
                put("name", deviceName)
                put("version", SendSpinProtocol.VERSION)
                put("supported_roles", buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(SendSpinProtocol.Roles.PLAYER))
                    add(kotlinx.serialization.json.JsonPrimitive(SendSpinProtocol.Roles.CONTROLLER))
                    add(kotlinx.serialization.json.JsonPrimitive(SendSpinProtocol.Roles.METADATA))
                    if (!lowMemoryMode) {
                        add(kotlinx.serialization.json.JsonPrimitive(SendSpinProtocol.Roles.ARTWORK))
                    }
                })
                put("device_info", buildJsonObject {
                    put("product_name", "SendSpinDroid")
                    put("manufacturer", manufacturer)
                    put("software_version", "1.0.0")
                })
                put("player@v1_support", buildJsonObject {
                    put("supported_formats", buildJsonArray {
                        for (fmt in supportedFormats) {
                            add(buildJsonObject {
                                put("codec", fmt.codec)
                                put("sample_rate", fmt.sampleRate)
                                put("channels", fmt.channels)
                                put("bit_depth", fmt.bitDepth)
                            })
                        }
                    })
                    put("buffer_capacity", bufferCapacity)
                    put("supported_commands", buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("volume"))
                        add(kotlinx.serialization.json.JsonPrimitive("mute"))
                    })
                })
                if (!lowMemoryMode) {
                    put("artwork@v1_support", buildJsonObject {
                        put("channels", buildJsonArray {
                            add(buildJsonObject {
                                put("source", "album")
                                put("format", "jpeg")
                                put("media_width", SendSpinProtocol.Artwork.REQUEST_SIZE)
                                put("media_height", SendSpinProtocol.Artwork.REQUEST_SIZE)
                            })
                        })
                    })
                }
            })
        }
        return message.toString()
    }

    fun buildClientTime(clientTransmittedMicros: Long): String {
        val message = buildJsonObject {
            put("type", SendSpinProtocol.MessageType.CLIENT_TIME)
            put("payload", buildJsonObject {
                put("client_transmitted", clientTransmittedMicros)
            })
        }
        return message.toString()
    }

    fun buildGoodbye(reason: String): String {
        val message = buildJsonObject {
            put("type", SendSpinProtocol.MessageType.CLIENT_GOODBYE)
            put("payload", buildJsonObject {
                put("reason", reason)
            })
        }
        return message.toString()
    }

    fun buildPlayerState(volume: Int, muted: Boolean, syncState: String = "synchronized", staticDelayMs: Double = 0.0): String {
        val message = buildJsonObject {
            put("type", SendSpinProtocol.MessageType.CLIENT_STATE)
            put("payload", buildJsonObject {
                put("player", buildJsonObject {
                    put("state", syncState)
                    put("volume", volume)
                    put("muted", muted)
                    put("static_delay_ms", staticDelayMs)
                })
            })
        }
        return message.toString()
    }

    fun buildCommand(command: String): String {
        val message = buildJsonObject {
            put("type", SendSpinProtocol.MessageType.CLIENT_COMMAND)
            put("payload", buildJsonObject {
                put("controller", buildJsonObject {
                    put("command", command)
                })
            })
        }
        return message.toString()
    }

    /**
     * Calculate buffer_capacity (wire bytes) from target duration and format list.
     *
     * Uses the highest-bitrate PCM entry we advertise as the basis, so the cap
     * is tight for PCM and gives compressed codecs proportionally more seconds
     * of look-ahead (but bounded decoded memory).
     */
    fun calculateBufferCapacity(formats: List<FormatEntry>, durationSec: Int): Int {
        val maxPcmBytesPerSec = formats
            .filter { it.codec == "pcm" }
            .maxOfOrNull { it.sampleRate * it.channels * (it.bitDepth / 8) }
            ?: (SendSpinProtocol.AudioFormat.SAMPLE_RATE
                    * SendSpinProtocol.AudioFormat.CHANNELS
                    * (SendSpinProtocol.AudioFormat.BIT_DEPTH / 8))
        return durationSec * maxPcmBytesPerSec
    }

    fun buildSupportedFormats(
        preferredCodec: String,
        isCodecSupported: (String) -> Boolean,
        supportedBitDepths: List<Int> = listOf(SendSpinProtocol.AudioFormat.BIT_DEPTH)
    ): List<FormatEntry> {
        val codecOrder = mutableListOf<String>()

        if (preferredCodec != "pcm" && isCodecSupported(preferredCodec)) {
            codecOrder.add(preferredCodec)
        }

        for (codec in listOf("flac", "opus")) {
            if (codec != preferredCodec && isCodecSupported(codec)) {
                codecOrder.add(codec)
            }
        }

        if (isCodecSupported("pcm")) {
            codecOrder.add("pcm")
        }

        return buildList {
            for (codec in codecOrder) {
                // Higher bit depths only apply to PCM; compressed codecs
                // (FLAC, Opus) decode to 16-bit PCM regardless of source depth.
                val depths = if (codec == "pcm") {
                    supportedBitDepths.sortedDescending()
                } else {
                    listOf(SendSpinProtocol.AudioFormat.BIT_DEPTH)
                }
                for (bitDepth in depths) {
                    // Stereo
                    add(FormatEntry(
                        codec = codec,
                        sampleRate = SendSpinProtocol.AudioFormat.SAMPLE_RATE,
                        channels = SendSpinProtocol.AudioFormat.CHANNELS,
                        bitDepth = bitDepth
                    ))
                    // Mono
                    add(FormatEntry(
                        codec = codec,
                        sampleRate = SendSpinProtocol.AudioFormat.SAMPLE_RATE,
                        channels = SendSpinProtocol.AudioFormat.CHANNELS_MONO,
                        bitDepth = bitDepth
                    ))
                }
            }
        }
    }
}

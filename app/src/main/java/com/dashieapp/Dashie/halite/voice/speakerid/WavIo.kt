package com.dashieapp.Dashie.halite.voice.speakerid

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal 16 kHz mono PCM16 WAV writer for retained enrollment recordings.
 * (Same RIFF layout as LiveSampleCollector.createWavFile — duplicated here
 * so speakerid stays decoupled from the wakeword package; extract a shared
 * util if a third copy ever appears.)
 */
object WavIo {

    fun toWavBytes(pcm: ShortArray, sampleRate: Int = KaldiFbank.SAMPLE_RATE): ByteArray {
        val dataSize = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)                    // PCM fmt chunk size
        buf.putShort(1)                   // PCM
        buf.putShort(1)                   // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)        // byte rate
        buf.putShort(2)                   // block align
        buf.putShort(16)                  // bits per sample
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        for (s in pcm) buf.putShort(s)
        return buf.array()
    }

    fun writeWav(file: File, pcm: ShortArray, sampleRate: Int = KaldiFbank.SAMPLE_RATE) {
        file.parentFile?.mkdirs()
        file.writeBytes(toWavBytes(pcm, sampleRate))
    }
}

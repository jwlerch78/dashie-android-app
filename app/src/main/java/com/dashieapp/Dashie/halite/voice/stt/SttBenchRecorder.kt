package com.dashieapp.Dashie.halite.voice.stt

import android.util.Log
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import java.io.File
import kotlin.math.sqrt

/**
 * Captures what the device's microphone ACTUALLY hears, so every file-fed engine
 * can be scored on identical real-room audio.
 *
 * Why this exists: the loudspeaker rig ([SttBenchRunner] micMode) only works for
 * ANDROID_NATIVE, because that is the one engine with a microphone of its own.
 * Everything else — sherpa, Deepgram, HA — is a [BufferedPostSttProvider] fed
 * exclusively through `streamAudio`, so in mic mode it receives ZERO bytes and
 * honestly reports "no speech detected". Playing a clip aloud can never reach
 * them directly.
 *
 * The fix is to invert it: record the room once, then feed that recording to
 * every engine off-device and at leisure. That also produces something better
 * than the simulated far-field corpus — REAL speaker, REAL room, REAL Samsung
 * mic and its AGC, instead of an RIR convolution approximating all three.
 *
 * ⚠️ Reads the EXISTING [SharedAudioBuffer] rather than opening a second
 * AudioRecord. The wake-word capture already owns the mic (`dumpsys audio` →
 * `pack:com.dashieapp.Dashie.* src client=MIC`), and a competing recorder just
 * loses that race — the same trap that sank the private-SpeechRecognizer probe.
 * Tapping the shared buffer also means the audio is bit-identical to what a
 * buffered provider would have been fed in production.
 *
 * ⚠️ NOT bit-identical to what ANDROID_NATIVE hears: SpeechRecognizer captures
 * through Google's own stack. Both are honestly "what the device heard", but a
 * report comparing them must say so rather than implying a controlled input.
 */
object SttBenchRecorder {
    private const val TAG = "SttBench"
    private const val SAMPLE_RATE = 16000

    data class Result(
        val file: String,
        val requestedMs: Long,
        val capturedMs: Long,
        val rms: Float,          // level sanity — near-zero means the room was silent
        val peak: Float,
        val error: String? = null,
    )

    /**
     * Mark the buffer, wait [ms], then write everything captured in between.
     *
     * Blocking — call off the main thread.
     */
    fun record(buffer: SharedAudioBuffer?, dest: File, ms: Long): Result {
        if (buffer == null) {
            return Result(dest.name, ms, 0, 0f, 0f,
                "DROP: no SharedAudioBuffer — voice capture not running")
        }
        val start = buffer.markPosition()
        Thread.sleep(ms)
        val want = (ms * SAMPLE_RATE / 1000).toInt()
        val samples = buffer.readFromAsShorts(start, want)
        if (samples.isEmpty()) {
            return Result(dest.name, ms, 0, 0f, 0f,
                "DROP: captured 0 samples — is the mic capture active?")
        }

        var sumSq = 0.0
        var peak = 0f
        for (s in samples) {
            val f = s / 32768f
            sumSq += (f * f).toDouble()
            if (kotlin.math.abs(f) > peak) peak = kotlin.math.abs(f)
        }
        val rms = sqrt(sumSq / samples.size).toFloat()

        try {
            dest.parentFile?.mkdirs()
            writeWav(dest, samples)
        } catch (e: Exception) {
            return Result(dest.name, ms, 0, rms, peak, "write failed: ${e.message}")
        }

        val capturedMs = samples.size * 1000L / SAMPLE_RATE
        Log.i(TAG, "recorded ${dest.name}: ${capturedMs}ms rms=%.4f peak=%.3f"
            .format(rms, peak))
        return Result(dest.name, ms, capturedMs, rms, peak)
    }

    /** Minimal 16-bit mono PCM WAV — the shape every engine adapter already reads. */
    private fun writeWav(dest: File, samples: ShortArray) {
        val dataBytes = samples.size * 2
        val out = java.io.BufferedOutputStream(java.io.FileOutputStream(dest))
        fun le32(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        out.use {
            it.write("RIFF".toByteArray()); it.write(le32(36 + dataBytes))
            it.write("WAVE".toByteArray())
            it.write("fmt ".toByteArray()); it.write(le32(16))
            it.write(le16(1))                       // PCM
            it.write(le16(1))                       // mono
            it.write(le32(SAMPLE_RATE))
            it.write(le32(SAMPLE_RATE * 2))         // byte rate
            it.write(le16(2)); it.write(le16(16))
            it.write("data".toByteArray()); it.write(le32(dataBytes))
            val buf = ByteArray(dataBytes)
            for (i in samples.indices) {
                buf[i * 2] = (samples[i].toInt() and 0xFF).toByte()
                buf[i * 2 + 1] = ((samples[i].toInt() shr 8) and 0xFF).toByte()
            }
            it.write(buf)
        }
    }
}

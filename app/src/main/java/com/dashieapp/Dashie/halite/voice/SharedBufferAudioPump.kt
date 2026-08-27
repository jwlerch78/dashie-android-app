package com.dashieapp.Dashie.halite.voice

import android.os.Handler
import android.util.Log
import com.dashieapp.Dashie.audio.SharedAudioBuffer

/**
 * Pumps captured audio out of the shared circular buffer in 100 ms chunks and hands each one to a
 * sink as 16-bit little-endian PCM.
 *
 * Extracted so the two consumers in HA Voice Assist mode — streaming to HA's Assist pipeline, and
 * feeding an on-device STT provider — share ONE read loop instead of hand-mirroring it. The read
 * position, cadence, float→PCM16 conversion and max-duration guard are all easy to get subtly
 * wrong (the position must start at the WAKE WORD's position, not "now", or speech uttered during
 * the connect delay is lost), which is exactly why it should exist once.
 *
 * Not the endpointer: callers decide when speech ENDED (their own VAD, or the STT provider's), then
 * call [stop]. This class only moves bytes.
 */
class SharedBufferAudioPump(
    private val handler: Handler,
    private val tag: String = "AudioPump",
) {

    companion object {
        /** 100 ms at 16 kHz. */
        private const val CHUNK_SAMPLES = 1600
        private const val CADENCE_MS = 100L

        /** Convert float samples to 16-bit little-endian PCM bytes. */
        fun floatToPcm16(samples: FloatArray): ByteArray {
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val sample = (samples[i] * 32767).toInt().coerceIn(-32768, 32767)
                bytes[i * 2] = (sample and 0xFF).toByte()
                bytes[i * 2 + 1] = (sample shr 8).toByte()
            }
            return bytes
        }
    }

    private var runnable: Runnable? = null
    private var readPosition: Long = 0
    private var startedAtMs: Long = 0

    val isRunning: Boolean get() = runnable != null

    /**
     * @param fromPosition buffer position to read from — pass the WAKE WORD's position (plus any
     *   tail skip) so audio spoken during connection setup is included.
     * @param maxDurationMs hard cap on one utterance; [onMaxDuration] fires and the pump stops.
     * @param keepRunning polled before every chunk — return false to halt (mirrors the original
     *   loop's state guard, so a state change can't leave a pump running).
     */
    fun start(
        buffer: SharedAudioBuffer,
        fromPosition: Long,
        maxDurationMs: Long,
        keepRunning: () -> Boolean,
        onMaxDuration: () -> Unit,
        onChunk: (ByteArray) -> Unit,
    ) {
        stop()
        readPosition = fromPosition
        startedAtMs = System.currentTimeMillis()

        // ⚠️ THIS IS START LAG, NOT RETAINED WAKE AUDIO — the two are easy to confuse and the
        // confusion has already cost a session. It measures how far BEHIND the live head this
        // pump begins, i.e. scheduling latency between the wake mark and here. It says nothing
        // about how much of "Hey Dashie" reaches STT: that is fixed by [fromPosition], which the
        // caller has already advanced by SttProviderFactory.wakeTailSkipSamples. Two turns with
        // different numbers here fed the decoder audio from the SAME offset.
        //
        // 📌 And the lag is permanent for the session: the loop reads CHUNK_SAMPLES (100ms of
        // audio) every CADENCE_MS (100ms of wall time), so it drains at exactly real time and
        // never catches up on this backlog. Posting immediately below saves one tick, not the
        // backlog. Consequences, both latency rather than loss: every chunk reaches STT this
        // much later, and [maxDurationMs] is wall-clock, so a capped utterance carries this much
        // less audio than the cap implies.
        val startLagMs = ((buffer.getCurrentPosition() - fromPosition) * 1000) / 16000
        Log.d(tag, "Audio pump starting ${startLagMs}ms behind live " +
            "(start lag, NOT retained wake audio — read position is fromPosition=$fromPosition)")

        runnable = object : Runnable {
            override fun run() {
                if (runnable !== this) return   // a stop()/restart raced us
                if (!keepRunning()) {
                    Log.d(tag, "Audio pump halted by caller guard")
                    stop()
                    return
                }
                if (System.currentTimeMillis() - startedAtMs > maxDurationMs) {
                    Log.d(tag, "Audio pump hit max duration (${maxDurationMs}ms)")
                    stop()
                    onMaxDuration()
                    return
                }

                val chunk = buffer.readFrom(readPosition, CHUNK_SAMPLES)
                if (chunk.isNotEmpty()) {
                    readPosition += chunk.size
                    onChunk(floatToPcm16(chunk))
                }
                handler.postDelayed(this, CADENCE_MS)
            }
        }
        // Immediately, not after a delay — there is already buffered audio to catch up on.
        handler.post(runnable!!)
    }

    fun stop() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
    }
}

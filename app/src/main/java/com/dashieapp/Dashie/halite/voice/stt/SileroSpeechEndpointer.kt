package com.dashieapp.Dashie.halite.voice.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.Vad

/**
 * Neural end-of-speech detector backed by the sherpa-onnx silero [Vad] — the snappier,
 * cut-off-resistant alternative to the energy-RMS [EnergyEndpointer]. The energy VAD had to
 * wait 1200ms+ of silence to avoid clipping mid-sentence breath-pauses (a low-energy pause
 * looks like silence); silero classifies speech-vs-not per 32ms frame, so it closes on a true
 * stop after only ~500ms (config in [SherpaEngineLoader.sileroVad]).
 *
 * Contract with [BufferedPostSttProvider]: this only signals END OF SPEECH — the base still
 * buffers and transcribes the WHOLE captured utterance. Silero pushes a completed SpeechSegment
 * to its queue after minSilenceDuration of trailing silence; the first such segment IS the
 * endpoint. maxSpeechDuration is set high so a long command never gets force-cut mid-word.
 *
 * The [Vad] is resident (loaded once in SherpaEngineLoader); [reset] clears its state each turn.
 */
class SileroSpeechEndpointer(
    private val vad: Vad,
    private val windowSize: Int = 512,   // silero's required frame (32ms @ 16 kHz)
) : SpeechEndpointer {

    override var onEndOfSpeech: (() -> Unit)? = null

    // Leftover PCM floats that didn't fill a full window last accept() call.
    private var carry = FloatArray(0)
    private var fired = false

    override fun accept(pcm16le: ByteArray) {
        if (fired) return
        // 16-bit LE PCM → normalized float, appended to any carry-over.
        val incoming = FloatArray(pcm16le.size / 2)
        for (i in incoming.indices) {
            val lo = pcm16le[2 * i].toInt() and 0xff
            val hi = pcm16le[2 * i + 1].toInt()
            incoming[i] = ((hi shl 8) or lo) / 32768.0f
        }
        var buf = if (carry.isEmpty()) incoming else carry + incoming
        var off = 0
        while (off + windowSize <= buf.size) {
            vad.acceptWaveform(buf.copyOfRange(off, off + windowSize))
            off += windowSize
            // A completed speech segment in the queue = speech has ended (minSilenceDuration
            // of trailing silence elapsed). First one wins; drain so the queue doesn't grow.
            if (!vad.empty()) {
                while (!vad.empty()) vad.pop()
                if (!fired) {
                    fired = true
                    Log.i("SherpaStt", "silero endpoint — speech ended")
                    onEndOfSpeech?.invoke()
                }
                break
            }
        }
        carry = if (off < buf.size) buf.copyOfRange(off, buf.size) else FloatArray(0)
    }

    override fun reset() {
        vad.reset()
        vad.clear()
        carry = FloatArray(0)
        fired = false
    }

    // The Vad is resident in SherpaEngineLoader — don't release it here (reset covers reuse).
    override fun release() {}
}

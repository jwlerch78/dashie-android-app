package com.dashieapp.Dashie.halite.voice.stt

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.halite.voice.VoiceActivityDetector
import java.io.ByteArrayOutputStream

/**
 * Pluggable end-of-speech detector for buffered providers. Default is the energy-RMS
 * [VoiceActivityDetector]; the on-device sherpa provider overrides [BufferedPostSttProvider.
 * createEndpointer] to swap in a neural silero VAD for snappier, cut-off-resistant endpointing
 * (energy VAD had to sit at 1200ms+ to avoid clipping mid-sentence pauses — silero distinguishes
 * a breath-pause from a true stop, so it can close on ~500ms). Accepts raw 16 kHz mono 16-bit PCM.
 */
interface SpeechEndpointer {
    var onEndOfSpeech: (() -> Unit)?
    fun accept(pcm16le: ByteArray)
    fun reset()
    fun release() {}
}

/** Default endpointer: the existing energy-RMS VAD, wrapped to the [SpeechEndpointer] seam. */
private class EnergyEndpointer(silenceTimeoutMs: Long) : SpeechEndpointer {
    private val vad = VoiceActivityDetector().apply {
        setSilenceTimeout(silenceTimeoutMs)
        setMinSpeechDuration(300L)
    }
    override var onEndOfSpeech: (() -> Unit)?
        get() = vad.onEndOfSpeech
        set(v) { vad.onEndOfSpeech = v }
    override fun accept(pcm16le: ByteArray) { vad.processAudio(pcm16le) }
    override fun reset() = vad.reset()
}

/**
 * Base for STT providers that CAPTURE the whole utterance (VAD-endpointed) and
 * then POST it in one shot to an HTTP endpoint — as opposed to streaming over a
 * socket like [HaAssistSttProvider]. Owns the shared machinery: 16 kHz PCM
 * buffering, VAD end-of-speech, session lifecycle, a transcribe watchdog, and a
 * PCM→WAV helper. Subclasses supply only the HTTP call via [transcribe].
 *
 * Reuses the same [VoiceActivityDetector] the HA-Assist provider uses, so capture
 * + endpointing behavior is identical (build plan 20260708 §4.2). The captured
 * bytes are raw 16-bit mono PCM @16 kHz — HA engine-direct POSTs them as-is;
 * the OpenAI-compatible provider wraps them with [pcmToWav].
 */
abstract class BufferedPostSttProvider : SttProvider {
    companion object {
        private const val TAG = "BufferedPostStt"
        const val SAMPLE_RATE = 16000
        private const val PROCESSING_TIMEOUT_MS = 15000L

        /** Wrap raw 16-bit mono PCM in a minimal 44-byte WAV header. */
        fun pcmToWav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
            val channels = 1; val bits = 16
            val byteRate = sampleRate * channels * bits / 8
            val out = ByteArrayOutputStream(44 + pcm.size)
            fun le32(v: Int) { out.write(v and 0xff); out.write((v ushr 8) and 0xff); out.write((v ushr 16) and 0xff); out.write((v ushr 24) and 0xff) }
            fun le16(v: Int) { out.write(v and 0xff); out.write((v ushr 8) and 0xff) }
            out.write("RIFF".toByteArray()); le32(36 + pcm.size); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); le32(16); le16(1); le16(channels); le32(sampleRate); le32(byteRate); le16(channels * bits / 8); le16(bits)
            out.write("data".toByteArray()); le32(pcm.size); out.write(pcm)
            return out.toByteArray()
        }
    }

    protected val handler = Handler(Looper.getMainLooper())
    private var endpointer: SpeechEndpointer? = null
    private val pcm = ByteArrayOutputStream()
    private var listener: SttListener? = null
    private var active = false
    private var streaming = false
    private var watchdog: Runnable? = null

    /** Subclass validates + stores its own config (url/engine/token). Return [isAvailable]. */
    protected abstract fun onInitialize(config: SttConfig): Boolean

    /** POST the captured raw PCM; call onText with the transcript (null/blank = no speech)
     *  or onError. Both may be invoked off the main thread — the base re-posts to main. */
    protected abstract fun transcribe(pcm: ByteArray, onText: (String?) -> Unit, onError: (String) -> Unit)

    /** The end-of-speech detector. Default = energy RMS; override to swap (e.g. silero). */
    protected open fun createEndpointer(config: SttConfig): SpeechEndpointer =
        EnergyEndpointer(config.endOfSpeechTimeoutMs)

    override suspend fun initialize(config: SttConfig): Boolean {
        endpointer = createEndpointer(config).apply {
            onEndOfSpeech = { if (active && streaming) endAudioStream() }
        }
        return onInitialize(config)
    }

    override fun startSession(listener: SttListener) {
        if (!isAvailable()) { listener.onError("$providerId not configured", isRecoverable = false); return }
        if (active) cancelSession()
        this.listener = listener
        active = true; streaming = true
        pcm.reset(); endpointer?.reset()
        Log.i(TAG, "[$providerId] session started")
        handler.post { listener.onSessionStarted() }
    }

    override fun streamAudio(audioData: ByteArray) {
        if (!active || !streaming) return
        pcm.write(audioData)
        endpointer?.accept(audioData)
    }

    override fun endAudioStream() {
        if (!active || !streaming) return
        streaming = false
        val bytes = pcm.toByteArray()
        if (bytes.isEmpty()) { finishNoSpeech(); return }
        startWatchdog()
        // Batch STT emits NO interim transcript — it captures, then POSTs, then waits
        // ~seconds for the engine. A streaming-oriented coordinator (VoicePipelineCoordinator
        // FB19) arms a short "no transcript" timeout that would fire mid-POST and cancel our
        // session. Signal "processing started" (an empty interim) so it trusts that a result
        // is coming and holds off until onFinalResult / onNoSpeechDetected (our 15s watchdog
        // is the real bound). Build plan 20260708 §4.2 (batch vs streaming STT).
        handler.post { listener?.onInterimResult("") }
        Log.i(TAG, "[$providerId] transcribing ${bytes.size} PCM bytes")
        val transcribeStartMs = System.currentTimeMillis()   // Wave 2: audio-end → final anchor
        transcribe(
            bytes,
            onText = { text ->
                handler.post {
                    // the RESULT drop. A transcript arrived and is thrown away
                    // because the session already ended — the caller sees no final, no
                    // no-speech, no error, i.e. exactly the "dead session under a live window"
                    // shape that hung for 12 minutes with nothing in the log to grep for.
                    if (!active) {
                        Log.w(TAG, "DROP: [$providerId] transcript discarded — session no longer " +
                            "active (len=${text?.length ?: 0})")
                        return@post
                    }
                    cancelWatchdog()
                    _lastTranscribeMs = System.currentTimeMillis() - transcribeStartMs   // POST/transcribe latency
                    if (text.isNullOrBlank()) listener?.onNoSpeechDetected() else listener?.onFinalResult(text)
                    endSession()
                }
            },
            onError = { e -> notifyError(e) }
        )
    }

    // Wave-2 timing: precise end-of-audio → final-transcript for this buffered engine
    // (set in endAudioStream's onText; read by the coordinator's reportSttStage).
    @Volatile private var _lastTranscribeMs: Long? = null
    override val lastTranscribeMs: Long? get() = _lastTranscribeMs

    override fun cancelSession() { if (active) endSession() }

    override fun release() { cancelSession(); endpointer?.release(); endpointer = null }

    private fun finishNoSpeech() { handler.post { listener?.onNoSpeechDetected(); endSession() } }

    private fun endSession() {
        active = false; streaming = false
        cancelWatchdog()
        handler.post { listener?.onSessionEnded(); listener = null }
    }

    private fun notifyError(error: String) {
        Log.w(TAG, "[$providerId] $error")
        handler.post {
            cancelWatchdog()
            if (!active) return@post
            listener?.onError(error, isRecoverable = true)
            endSession()
        }
    }

    private fun startWatchdog() {
        cancelWatchdog()
        watchdog = Runnable {
            if (active) {
                Log.w(TAG, "[$providerId] transcribe watchdog timeout")
                listener?.onNoSpeechDetected()
                endSession()
            }
        }
        handler.postDelayed(watchdog!!, PROCESSING_TIMEOUT_MS)
    }

    private fun cancelWatchdog() { watchdog?.let { handler.removeCallbacks(it); watchdog = null } }
}

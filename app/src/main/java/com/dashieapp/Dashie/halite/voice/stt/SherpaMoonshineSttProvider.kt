package com.dashieapp.Dashie.halite.voice.stt

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * On-device STT via sherpa-onnx Moonshine (tiny or base) — the bundled-engine
 * half of the two-pronged STT plan (`SpeechRecognizer` on Google devices,
 * sherpa where there are none: Amazon Fire/Echo, Mio). No cloud, no HA hop,
 * no mic of its own — consumes the shared-capture PCM like every other
 * buffered provider, so it inherits none of the SpeechRecognizer mic-handoff
 * bug class and plays no OEM listening beep.
 *
 * Utterance-mode by design: [BufferedPostSttProvider] captures until VAD
 * end-of-speech, then [transcribe] runs local inference instead of an HTTP
 * POST. Bench: RTF 0.11 (tiny) / 0.17 (base) on the Fire — text lands well
 * under a second after end of speech (20260727_SHERPA_ONNX_STT_SPIKE.md).
 *
 * ⚠️ The v2-merged quantized Moonshine exports return EMPTY text for audio
 * over ~8–10 s (onnxruntime broadcast bug in the merged decoder, both models,
 * verified on Mio + Fire). We hard-cap the decoded audio at [MAX_UTTERANCE_MS]
 * and WARN loudly — never hand the decoder an input we know it silently fails
 * on. Commands are almost always < 8 s; the cap truncates, not drops.
 */
class SherpaMoonshineSttProvider(
    private val context: Context,
    private val modelId: String,               // SherpaEngineLoader.MODEL_MOONSHINE_*
    override val providerId: String,           // e.g. VoicePreferences.STT_SHERPA_MOONSHINE_TINY
    override val displayName: String,
) : BufferedPostSttProvider() {

    companion object {
        private const val TAG = "SherpaStt"
        // v2-merged export length ceiling (8 s verified good, ≥10 s verified empty).
        private const val MAX_UTTERANCE_MS = 8_000L
        private const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000   // 16 kHz mono 16-bit
        /** Below this much detected speech the buffer is treated as non-speech and never decoded.
         *  Matches the >=300 ms the old empty-transcript comment merely assumed. */
        private const val MIN_SPEECH_MS = 300
        private const val VAD_WINDOW = 512   // silero's required frame (32 ms @ 16 kHz)
    }

    // Inference is 0.7–1.2 s of CPU — never on main. Single lane per provider:
    // sessions are serial (the base class enforces one active session).
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sherpa-stt-$modelId").apply { isDaemon = true }
    }

    private var available = false

    override fun isAvailable(): Boolean = available

    override fun onInitialize(config: SttConfig): Boolean {
        // 🔴 modelUSABLE, not modelAVAILABLE. `modelAvailable` probes APK assets only; since
        // 2026-08-04 a model can also arrive by download and live in filesDir, which the
        // AssetManager cannot see. Using the asset probe here would have let a user download a
        // model, watch the settings row switch to "on this device", and still get an STT lane
        // that refused to run it — the download reaching the disk but never the pipeline.
        //
        // Caught on the Fire: the providers register at boot and log their availability, which is
        // what made the gap visible before it shipped.
        available = SherpaEngineLoader.engineAvailable() &&
            SherpaEngineLoader.modelUsable(context, modelId)
        Log.i(TAG, "[$providerId] available=$available")
        return available
    }

    /**
     * Load the recognizer + decode-gate VAD NOW instead of on the first decode.
     *
     * The recognizer costs 2.4–4.8 s to load and was the only piece of this subsystem that loaded
     * lazily — [createEndpointer] already loads the silero endpointer eagerly from
     * `BufferedPostSttProvider.initialize`. Measured on the Fire (2026-08-21): first command
     * after app start 5.5 s vs 1.1 s warm, decode rtf identical (0.15–0.17) on both turns.
     *
     * Runs on the same single-lane decode executor, so it cannot race a decode: if a turn arrives
     * mid-warm it simply queues behind it and finds the model resident — never worse than the lazy
     * path it replaces. Idempotent: [SherpaEngineLoader] returns the resident instances.
     */
    override fun prewarm() {
        if (!available) return
        executor.execute {
            val t0 = System.currentTimeMillis()
            val rec = SherpaEngineLoader.offlineRecognizer(context, modelId)
            // The gate VAD is the OTHER lazy load on this executor (measureSpeechMs), and it is a
            // second instance on purpose — see SherpaEngineLoader.sileroGateVad. Warm it here too
            // so no part of the first turn pays a load.
            SherpaEngineLoader.sileroGateVad(context)
            if (rec == null) {
                Log.w(TAG, "DROP: [$providerId] prewarm could not load model=$modelId — " +
                    "first decode will retry and pay the load")
            } else {
                Log.i(TAG, "[$providerId] prewarm complete in ${System.currentTimeMillis() - t0}ms " +
                    "(model=$modelId resident before first use)")
            }
        }
    }

    /** Neural silero endpointing (snappy, cut-off-resistant) when the VAD is bundled; else the
     *  base's energy-RMS VAD at the configured silence timeout. */
    override fun createEndpointer(config: SttConfig): SpeechEndpointer {
        val vad = SherpaEngineLoader.sileroVad(context)
        return if (vad != null) SileroSpeechEndpointer(vad)
        else super.createEndpointer(config)
    }

    override fun transcribe(pcm: ByteArray, onText: (String?) -> Unit, onError: (String) -> Unit) {
        executor.execute {
            val recognizer = SherpaEngineLoader.offlineRecognizer(context, modelId)
            if (recognizer == null) {
                onError("sherpa engine/model $modelId not available")
                return@execute
            }
            val utteranceMs = pcm.size / BYTES_PER_MS
            val capBytes = (MAX_UTTERANCE_MS * BYTES_PER_MS).toInt()
            val use = if (pcm.size > capBytes) {
                Log.w(TAG, "DROP: [$providerId] utterance ${utteranceMs}ms > ${MAX_UTTERANCE_MS}ms cap — " +
                    "truncating (v2-merged decoder returns empty above ~8s; see 20260728 plan)")
                pcm.copyOf(capBytes)
            } else pcm
            try {
                val floats = FloatArray(use.size / 2)
                for (i in floats.indices) {
                    val lo = use[2 * i].toInt() and 0xff
                    val hi = use[2 * i + 1].toInt()
                    floats[i] = ((hi shl 8) or lo) / 32768.0f
                }
                // ── Silence gate ──────────────────────────────────────────────
                // Whisper-family decoders INVENT text from non-speech: on the Mio both empty
                // follow-up windows produced fabricated sentences ("♪♪ ♪♪", a 4×-looped line) and
                // both were billed to the brain as if a human had spoken them. Nothing downstream
                // distinguishes an invented sentence from a real one, and the same lane executes
                // device commands. The upstream VAD cannot prevent it: the coordinator's is an RMS
                // ENERGY threshold, so room tone opens a window, end-of-speech is ignored for lack
                // of a transcript, and MAX_RECORDING hands us 7–8.5s of silence.
                // 📌 The empty-transcript DROP below rests on "VAD guarantees ≥300ms of detected
                // speech" — that assumption is exactly what fails here, so gate it for real.
                val speechMs = measureSpeechMs(floats)
                if (speechMs != null && speechMs < MIN_SPEECH_MS) {
                    Log.w(TAG, "DROP: [$providerId] silence gate — ${speechMs}ms speech in a " +
                        "${utteranceMs}ms buffer (<${MIN_SPEECH_MS}ms), not decoding")
                    onText("")
                    return@execute
                }
                if (speechMs == null && !hasSpeechBandEnergy(floats)) {
                    // Fallback only when silero isn't bundled — the same silero-else-energy
                    // escalation createEndpointer above makes. Intra-class parallel only; this
                    // provider is Kotlin-only and has no counterpart across the bridge.
                    Log.w(TAG, "DROP: [$providerId] silence gate (energy fallback) — no speech-band " +
                        "content in ${utteranceMs}ms buffer, not decoding")
                    onText("")
                    return@execute
                }
                val t0 = System.currentTimeMillis()
                val stream = recognizer.createStream()
                val text: String
                try {
                    stream.acceptWaveform(floats, SAMPLE_RATE)
                    recognizer.decode(stream)
                    text = recognizer.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "[$providerId] model=$modelId decoded ${utteranceMs}ms audio in ${ms}ms " +
                    "(rtf=${"%.2f".format(ms / utteranceMs.coerceAtLeast(1).toFloat())}) → \"$text\"")
                if (text.isEmpty() && utteranceMs > 500) {
                    // The silence gate above now makes this assumption TRUE (it used to be
                    // asserted and false): anything reaching the decoder carried real speech,
                    // so empty text here is the silent-failure shape the postmortem bans hiding.
                    Log.w(TAG, "DROP: [$providerId] empty transcript for ${utteranceMs}ms utterance")
                }
                // Signature belt: audio can still fool any input gate, and the two canonical
                // Whisper-family hallucination shapes are cheap to recognise on the OUTPUT.
                if (text.isNotEmpty() && isHallucinationSignature(text)) {
                    Log.w(TAG, "DROP: [$providerId] hallucination signature — discarding \"$text\"")
                    onText("")
                    return@execute
                }
                onText(text)
            } catch (e: Throwable) {
                Log.w(TAG, "[$providerId] decode failed", e)
                onError("sherpa decode failed: ${e.message}")
            }
        }
    }

    /**
     * Speech duration in the buffer per the silero VAD, or `null` when the VAD isn't bundled
     * (caller falls back to [hasSpeechBandEnergy]). Uses the dedicated gate instance — see
     * [SherpaEngineLoader.sileroGateVad] for why it must not be the endpointer's.
     */
    private fun measureSpeechMs(floats: FloatArray): Int? {
        val vad = SherpaEngineLoader.sileroGateVad(context) ?: return null
        return try {
            vad.reset(); vad.clear()
            var samples = 0L
            var off = 0
            while (off + VAD_WINDOW <= floats.size) {
                vad.acceptWaveform(floats.copyOfRange(off, off + VAD_WINDOW))
                off += VAD_WINDOW
                while (!vad.empty()) { samples += vad.front().samples.size; vad.pop() }
            }
            vad.flush()   // emit any segment still open at the end of the buffer
            while (!vad.empty()) { samples += vad.front().samples.size; vad.pop() }
            (samples * 1000 / SAMPLE_RATE).toInt()
        } catch (e: Throwable) {
            // Never fail a real utterance because the gate broke — fall through to the decoder.
            Log.w(TAG, "[$providerId] silence gate errored, allowing decode", e)
            null
        }
    }

    override fun release() {
        super.release()
        // Recognizer stays resident in SherpaEngineLoader by design (2.4–3.6 s reload).
    }
}

/**
 * Coarse speech-band proxy for devices where silero isn't bundled. Pre-emphasis
 * (`y[n] = x[n] - 0.97*x[n-1]`) is a one-pole high-pass that suppresses the low-frequency
 * room rumble an RMS check mistakes for content, so what survives is roughly speech-band
 * energy. Deliberately coarse — it is the fallback, not the gate.
 */
internal fun hasSpeechBandEnergy(floats: FloatArray, floor: Float = 0.006f): Boolean {
    if (floats.size < 2) return false
    var sumSq = 0.0
    for (i in 1 until floats.size) {
        val e = floats[i] - 0.97f * floats[i - 1]
        sumSq += (e * e).toDouble()
    }
    return Math.sqrt(sumSq / (floats.size - 1)) > floor
}

/**
 * The two canonical Whisper-family hallucination shapes, both observed on the Mio
 *: a symbol-only transcript (`"♪♪ ♪♪"`) and one sentence repeated >=3x
 * (`"I'm going to go to the hospital."` x4). File-level + pure so it is unit-testable
 * without a decoder, a Context, or the native engine.
 */
internal fun isHallucinationSignature(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty()) return false
    // (1) No letters or digits at all -> punctuation/symbol soup, never a real utterance.
    if (t.none { it.isLetterOrDigit() }) return true
    // (2) A sentence repeated >=3x. Split on terminators; compare normalized, since the
    // decoder re-punctuates each repetition slightly differently.
    val sentences = t.split(Regex("[.!?]+"))
        .map { it.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() }
    if (sentences.size >= 3) {
        val counts = sentences.groupingBy { it }.eachCount()
        if (counts.any { it.value >= 3 }) return true
    }
    return false
}

package com.dashieapp.Dashie.voice.realtime

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.microfrontend.MicroFrontend
import com.dashieapp.Dashie.wakeword.microwakeword.MicroWakeWordClassifier
import com.dashieapp.Dashie.wakeword.microwakeword.MicroWakeWordConfig

/**
 * Wake-word barge-in for realtime conversation mode (build plan
 * 20260703_BARGEIN_ESCALATION_HANDOFF, option #3 — wire-up-now variant).
 *
 * Runs the existing microWakeWord model (`hey_dashie`) on the AEC3-cleaned mic while
 * Dashie is speaking, so the user can interrupt by saying the wake word. This is the
 * VOLUME-ROBUST barge-in path: the energy/enr detector fails at high volume because a
 * small speaker distorts (non-linear) and AEC3 — a linear canceller — leaves a residual
 * that looks exactly like near-end speech (enr≈0, dn=1). A wake-word spotter recognizes
 * the word *through* that residual instead of trying to threshold it.
 *
 * PUSH-driven, unlike [com.dashieapp.Dashie.wakeword.microwakeword.MicroWakeWordDetector]
 * which pulls from the SharedAudioBuffer — that buffer's AudioRecord is released during a
 * conversation (the GeminiLiveEngine owns the mic), so we drive the same MicroFrontend +
 * classifier pipeline directly from [RealtimeAudioIo]'s capture thread with the cleaned PCM.
 *
 * The current model is single-channel (mic MFE only) and NOT reference-conditioned. AEC3
 * already removes the *linear* echo, so the wake word rides on the residual — the legacy
 * cascade path runs this same model during TTS with NO echo suppression and works, so the
 * cleaned mic should do at least as well. A reference-conditioned retrain (feeding the
 * loudspeaker signal as a 2nd channel) is the later accuracy upgrade, not a prerequisite.
 *
 * Never throws into the audio path: a failed init leaves it inert ([feed] always false).
 */
class RealtimeWakeWordBargeIn(private val context: Context) {

    companion object {
        private const val TAG = "RtWakeBarge"
        // Fallback only — init() resolves the ACTIVE wake word's MWW asset via
        // WakeWordModelManager so barge-in listens for the same word as the main
        // detector (found 2026-07-27: chickadee barge-in missed because this was
        // hardcoded to hey_dashie).
        private const val FALLBACK_MODEL = "models/mww/hey_dashie.tflite"
        // Warm up before allowing a detection (frontend AGC settle + streaming state fill),
        // matching MicroWakeWordDetector.MIN_INFERENCES_BEFORE_DETECTION (~3 s at 30 ms/inf).
        private const val WARMUP_INFERENCES = 100
        private const val COOLDOWN_MS = 1500L
        // Barge-in cutoff + sliding-window size. Kept local (not the shared mutable
        // MicroWakeWordConfig, which the cascade DualEngineDetector rewrites) and tuned for a
        // SHORT interrupt rather than a warm continuous stream. On device 2026-07-03 a real
        // "hey dashie" peaked 98–100% but the cascade's 5-frame average only reached 55%
        // (window [0,7,70,98,100]) — the ramp-up zeros dragged it under the cutoff and it missed.
        // A 3-frame window tracks the sustained peak (~89% on the same utterance); the lower
        // cutoff catches marginal ones. Raise the cutoff if Dashie's own speech self-triggers.
        // hey_dashie value — its MWW scores real utterances at 98-100%. Other models
        // calibrate differently (chickadee v0.1 real fires: 32-46% on the 5-frame avg),
        // so init() derives a per-model cutoff: probabilityCutoff + 0.15 margin, floor 0.45.
        private const val BARGE_CUTOFF = 0.75f
        private const val WINDOW_SIZE = 3
        private const val CHUNK = MicroWakeWordConfig.SAMPLES_PER_CHUNK   // 160 (10 ms @ 16k)
        private const val STRIDE = MicroWakeWordConfig.STRIDE_FRAMES      // 3 frames per inference
    }

    private var frontend: MicroFrontend? = null
    private var classifier: MicroWakeWordClassifier? = null

    private val featureFrames = mutableListOf<FloatArray>()
    private val window = ArrayDeque<Float>()
    private var carry = ShortArray(0)        // sub-160-sample remainder between feeds
    private var inferenceCount = 0
    private var lastDetectMs = 0L
    private var bargeCutoff = BARGE_CUTOFF   // per-model; resolved in init()

    val isReady: Boolean get() = classifier?.isReady() == true

    /** Stand up the frontend + classifier. Returns false (inert) if the model won't load. */
    fun init(): Boolean {
        return try {
            // Barge-in must listen for the SAME word as the main detector.
            val activeModel = try {
                com.dashieapp.Dashie.wakeword.models.WakeWordModelManager(context).getActiveModel()
            } catch (e: Exception) { null }
            val model = activeModel?.assetPath?.takeIf { it.startsWith("models/mww/") }
                ?: FALLBACK_MODEL
            bargeCutoff = if (model == FALLBACK_MODEL) BARGE_CUTOFF
                else maxOf(0.45f, (activeModel?.probabilityCutoff ?: 0.30f) + 0.15f)
            val cls = MicroWakeWordClassifier(context = context, assetModelPath = model)
            if (!cls.initialize()) { Log.w(TAG, "classifier init failed — barge-in wake word inert"); return false }
            cls.reset()
            val fe = MicroFrontend(
                stepSizeMs = MicroWakeWordConfig.FEATURE_STEP_SIZE_MS,
                sampleRate = MicroWakeWordConfig.SAMPLE_RATE,
            )
            fe.reset()
            classifier = cls; frontend = fe
            featureFrames.clear(); window.clear(); carry = ShortArray(0)
            inferenceCount = 0; lastDetectMs = 0L
            Log.i(TAG, "wake-word barge-in ready (model=$model cutoff=$bargeCutoff)")
            true
        } catch (t: Throwable) { Log.w(TAG, "init failed", t); false }
    }

    /**
     * Feed one ~100 ms cleaned mic chunk (16 kHz mono PCM16 LE). Runs the streaming
     * pipeline and returns true exactly on the inference where "hey dashie" is detected
     * (warmup + cooldown enforced). Returns false when inert.
     */
    fun feed(pcm: ByteArray): Boolean {
        val fe = frontend ?: return false
        val cls = classifier ?: return false
        val buf = concat(carry, bytesToShorts(pcm))
        var off = 0
        var detected = false
        val frame = ShortArray(CHUNK)
        while (off + CHUNK <= buf.size) {
            System.arraycopy(buf, off, frame, 0, CHUNK)
            off += CHUNK
            val frames = try { fe.processSamples(frame) } catch (_: Throwable) { continue }
            for (f in frames) {
                featureFrames.add(f)
                if (featureFrames.size >= STRIDE) {
                    val p = try { cls.classify(featureFrames) } catch (_: Throwable) { 0f }
                    featureFrames.clear()
                    inferenceCount++
                    window.addLast(p)
                    while (window.size > WINDOW_SIZE) window.removeFirst()
                    // Near-miss visibility for threshold tuning: log any notable frame (single +
                    // window avg), so a missed "hey dashie" shows how close it got to the cutoff.
                    if (p >= 0.30f) Log.i(TAG, "rt-wake p=%.0f%% avg=%.0f%% win=%s"
                        .format(p * 100, windowAvg() * 100, window.map { "%.0f".format(it * 100) }))
                    if (checkDetection()) detected = true
                }
            }
        }
        carry = if (off < buf.size) buf.copyOfRange(off, buf.size) else ShortArray(0)
        return detected
    }

    private fun windowAvg(): Float = if (window.isEmpty()) 0f else window.sum() / window.size

    private fun checkDetection(): Boolean {
        if (window.size < WINDOW_SIZE) return false
        if (inferenceCount < WARMUP_INFERENCES) return false
        val avg = windowAvg()
        if (avg < bargeCutoff) return false
        val now = System.currentTimeMillis()
        if (now - lastDetectMs < COOLDOWN_MS) return false
        lastDetectMs = now
        window.clear()   // prevent immediate re-trigger
        Log.i(TAG, "WAKE WORD barge-in (avg=${"%.0f".format(avg * 100)}%)")
        return true
    }

    fun close() {
        try { classifier?.close() } catch (_: Throwable) {}
        try { frontend?.close() } catch (_: Throwable) {}   // releases the native frontend handle
        classifier = null; frontend = null
        featureFrames.clear(); window.clear(); carry = ShortArray(0)
    }

    // ── PCM16 LE helpers ─────────────────────────────────────────────────
    private fun bytesToShorts(b: ByteArray): ShortArray {
        val out = ShortArray(b.size / 2)
        var j = 0
        for (i in out.indices) { out[i] = ((b[j].toInt() and 0xFF) or (b[j + 1].toInt() shl 8)).toShort(); j += 2 }
        return out
    }

    private fun concat(a: ShortArray, b: ShortArray): ShortArray {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = ShortArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        return out
    }
}

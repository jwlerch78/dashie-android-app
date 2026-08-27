package com.dashieapp.Dashie.halite.voice.stt

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Owns the sherpa-onnx on-device STT engine lifecycle: native-lib load, model
 * asset detection, and lazily-created resident recognizers.
 *
 * The engine (libsherpa-onnx-jni.so) and models are flavor-gated assets that
 * exist only in local/staging dev builds (fetch-stt-models.sh populates
 * src/sttEngine/ — see build-plan 20260728_SHERPA_STT_INTEGRATION_PLAN.md in
 * dashieapp_staging). Everything here feature-detects: on an APK without the
 * lib or models, [engineAvailable]/[modelAvailable] are false, the providers
 * report unavailable, and the STT priority chain falls through — no crash.
 *
 * Recognizers stay RESIDENT once created (moonshine-tiny ≈ 135 MB RSS): model
 * load costs 2.4–3.6 s on the Fire, far more than a decode (~0.7 s), so
 * per-utterance loading would dominate turn latency. Spike numbers:
 * 20260727_SHERPA_ONNX_STT_SPIKE.md.
 */
object SherpaEngineLoader {
    private const val TAG = "SherpaStt"
    private const val ASSET_ROOT = "models/stt"

    /** Model ids double as asset dir names under [ASSET_ROOT]. */
    const val MODEL_MOONSHINE_TINY = "moonshine-tiny"
    const val MODEL_MOONSHINE_BASE = "moonshine-base"

    const val SILERO_VAD_ASSET = "$ASSET_ROOT/silero_vad.onnx"

    @Volatile private var libLoaded: Boolean? = null
    private val recognizers = mutableMapOf<String, OfflineRecognizer>()
    private var vad: Vad? = null
    private var gateVad: Vad? = null   // see [sileroGateVad] — separate ON PURPOSE, not an oversight

    /** True when libsherpa-onnx-jni.so is present and loads (dev flavors only). */
    fun engineAvailable(): Boolean {
        libLoaded?.let { return it }
        synchronized(this) {
            libLoaded?.let { return it }
            val ok = try {
                System.loadLibrary("sherpa-onnx-jni")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.i(TAG, "sherpa engine not bundled in this APK (${e.message})")
                false
            }
            libLoaded = ok
            return ok
        }
    }

    /** True when the model's asset dir is bundled (cheap list() probe). */
    fun modelAvailable(context: Context, modelId: String): Boolean = try {
        context.assets.list("$ASSET_ROOT/$modelId")?.isNotEmpty() == true
    } catch (e: Exception) {
        false
    }

    /**
     * True when the model is usable — **bundled in the APK OR downloaded and verified.**
     *
     * 🔴 This is the predicate the STT lane must gate on, not [modelAvailable]. Since 2026-08-04 a
     * model can arrive by download (John's on-demand ruling), and a downloaded family lives in
     * `filesDir`, which `assets.list()` cannot see. Gating on the asset probe alone would offer
     * the engine only to dev flavors — the bug this feature exists to remove.
     *
     * The downloaded side asks [SttModelRegistry.isInstalled], which checks every member file
     * rather than just the directory: "verified" is the standard, not "present". A family only
     * reaches its real path via the installer's atomic rename, so this cannot see a partial one.
     */
    fun modelUsable(context: Context, modelId: String): Boolean {
        if (modelAvailable(context, modelId)) return true
        val family = SttModelRegistry.byId(modelId) ?: return false
        return SttModelRegistry.isInstalled(context, family)
    }

    /* on-device TTS (sherpa Piper) removed 2026-07-28 — Piper amy quality was only "ok" on the
     * Fire floor device + a slow first-load stall; Samsung's native TTS covers capable devices and
     * Kokoro (better) is deferred to faster hardware. STT stays. Restore from git (commit 23f922b7)
     * when revisiting on-device TTS. */

    /**
     * `(encoder, mergedDecoder)` filenames for [modelId], or null when neither can be found.
     *
     * Downloaded models answer from [SttModelRegistry] — the same two fields that define what the
     * installer extracted and what `isInstalled` verified, so the recognizer cannot open a
     * different file from the one that was checked.
     *
     * Bundled models answer from the asset listing instead, because the APK carries whatever the
     * build script fetched at the time it was built. A dev APK cut before the `.onnx` move still
     * has `.ort` assets, and the registry would name files that are not in it.
     */
    private fun moonshineFileNames(
        context: Context,
        modelId: String,
        bundled: Boolean,
    ): Pair<String, String>? {
        if (!bundled) {
            val family = SttModelRegistry.byId(modelId) ?: return null
            return family.encoder to family.decoder
        }
        val assets = try {
            context.assets.list("$ASSET_ROOT/$modelId").orEmpty()
        } catch (e: Exception) {
            emptyArray<String>()
        }
        val encoder = assets.firstOrNull { it.startsWith("encoder_model.") }
        val decoder = assets.firstOrNull { it.startsWith("decoder_model_merged.") }
        if (encoder == null || decoder == null) return null
        return encoder to decoder
    }

    /**
     * Lazily create (then keep resident) the offline recognizer for a moonshine
     * model. Returns null when the engine/model isn't bundled or creation fails.
     */
    @Synchronized
    fun offlineRecognizer(context: Context, modelId: String): OfflineRecognizer? {
        recognizers[modelId]?.let { return it }
        if (!engineAvailable()) return null

        // 🔴 TWO LOAD MODES, and they are not interchangeable. sherpa resolves the paths below
        // through the AssetManager when one is supplied, and as plain filesystem paths when it is
        // not. A DOWNLOADED model lives in filesDir, which the AssetManager cannot see — so
        // passing `assetManager` with a filesDir path silently fails to load. Bundled ⇒ asset
        // mode + relative paths; downloaded ⇒ path mode + absolute paths.
        val bundled = modelAvailable(context, modelId)
        val downloadedDir = SttModelRegistry.byId(modelId)
            ?.takeIf { SttModelRegistry.isInstalled(context, it) }
            ?.let { SttModelRegistry.dir(context, it) }

        if (!bundled && downloadedDir == null) return null
        val dir = if (bundled) "$ASSET_ROOT/$modelId" else downloadedDir!!.absolutePath

        // 🔴 THE MODEL FILENAMES ARE NOT A CONSTANT ANY MORE. Until 2026-08-23 both families
        // shipped `encoder_model.ort` / `decoder_model_merged.ort` and these were hardcoded here —
        // a hand-mirror of SttModelRegistry.members that agreed only because the two families
        // happened to match. moonshine-base now ships `.onnx` (the un-fused export, ~52 MB less
        // duplicated weight) while tiny stays `.ort`, so a literal here would load the wrong path
        // for one of them.
        //
        // ⚠️ And the two load modes need DIFFERENT answers, which is why this is not simply
        // "read the registry":
        //  - DOWNLOADED → the registry IS authoritative. isInstalled() has already proved those
        //    exact member files exist in that directory.
        //  - BUNDLED → whatever `fetch-stt-models.sh` put in the APK, which for a dev build made
        //    before this change is still `.ort`. Probing the asset listing keeps such an APK
        //    working instead of failing to load with a path that looks correct.
        val names = moonshineFileNames(context, modelId, bundled)
            ?: run {
                Log.w(TAG, "DROP: model=$modelId — no encoder/decoder found in " +
                    "${if (bundled) "assets" else "downloaded"} dir $dir; not loading")
                return null
            }

        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    encoder = "$dir/${names.first}",
                    mergedDecoder = "$dir/${names.second}",
                ),
                tokens = "$dir/tokens.txt",
                // 4 threads matched real-time headroom on both the RK3576 Mio and
                // the Fire (RTF 0.11–0.17) in the 2026-07-27 spike.
                numThreads = 4,
            ),
        )
        return try {
            val t0 = System.currentTimeMillis()
            val rec = if (bundled) {
                OfflineRecognizer(assetManager = context.assets, config = config)
            } else {
                OfflineRecognizer(config = config)   // absolute paths — see the note above
            }
            Log.i(TAG, "model=$modelId loaded in ${System.currentTimeMillis() - t0}ms " +
                "(resident, ${if (bundled) "bundled" else "downloaded"})")
            recognizers[modelId] = rec
            rec
        } catch (e: Throwable) {
            Log.w(TAG, "DROP: model=$modelId failed to load — sherpa STT unavailable", e)
            null
        }
    }

    /**
     * Lazily create (then keep resident) the silero neural VAD used for snappy, cut-off-resistant
     * end-of-speech on the on-device providers. Reused across sessions (the endpointer resets it
     * each turn). Returns null when the engine/model isn't bundled or creation fails — the caller
     * falls back to the energy-RMS endpointer.
     *
     * @param minSilenceMs silence after speech to close a segment (the perceived end-of-speech
     *   latency). Neural detection makes ~500ms robust where the energy VAD needed 1200ms+.
     */
    /**
     * Every STT model DIRECTORY bundled under [ASSET_ROOT], whatever they are.
     *
     * Exists so callers never spell the asset root themselves. The `?cmd=voiceEngines` probe
     * first wrote `assets.list("models/stt")` directly — a hand-mirror of a private constant,
     * and a silent one: change [ASSET_ROOT] and the probe enumerates nothing, reporting every
     * model as absent while the models are fine. `lint:discovery` flagged it; deleting the
     * duplicate is cheaper than registering it.
     *
     * Directories only — loose files under the root (silero_vad.onnx) are not models; see
     * [sileroVadAvailable].
     */
    fun bundledModels(context: Context): List<String> = try {
        context.assets.list(ASSET_ROOT).orEmpty().filter { entry ->
            context.assets.list("$ASSET_ROOT/$entry")?.isNotEmpty() == true
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not enumerate $ASSET_ROOT: ${e.message}")
        emptyList()
    }

    /**
     * True when the silero VAD asset is bundled. Extracted so the `?cmd=voiceEngines` probe
     * reports the SAME fact [sileroVad] gates on — the probe first hand-rolled it as
     * `modelAvailable("silero_vad")`, which lists a *directory* that never exists, so it
     * reported `false` on devices where the VAD is present and working. A detection probe
     * that disagrees with the loader is worse than no probe.
     *
     * Note it is a FILE (`silero_vad.onnx`) directly under [ASSET_ROOT], not a per-model
     * directory like the moonshine models — which is exactly what made the wrong check look
     * plausible.
     */
    fun sileroVadAvailable(context: Context): Boolean = try {
        context.assets.list(ASSET_ROOT)?.contains("silero_vad.onnx") == true
    } catch (e: Exception) {
        false
    }

    @Synchronized
    fun sileroVad(context: Context, minSilenceMs: Long = 500L): Vad? {
        vad?.let { return it }
        if (!engineAvailable()) return null
        if (!sileroVadAvailable(context)) return null
        return try {
            buildSileroVad(context, minSilenceMs)
                .also { vad = it; Log.i(TAG, "silero VAD loaded (minSilence=${minSilenceMs}ms, resident)") }
        } catch (e: Throwable) {
            Log.w(TAG, "silero VAD failed to load — falling back to energy VAD", e)
            null
        }
    }

    /**
     * A SECOND resident silero instance, used ONLY as the pre-decode speech gate.
     *
     * 🔴 Deliberately not [sileroVad]. That instance is handed to `SileroSpeechEndpointer` and fed
     * from the AUDIO thread, while the gate runs on the STT provider's decode executor. sherpa's
     * [Vad] is a thin wrapper over a native handle with mutable stream state and no locking, so
     * sharing it across those two threads is a data race — and its symptom (a wrong gate verdict,
     * or an endpoint that fires early/late) would read as a model or tuning bug, which is the
     * expensive kind to chase. A second instance is ~2 MB and removes the question.
     */
    @Synchronized
    fun sileroGateVad(context: Context): Vad? {
        gateVad?.let { return it }
        if (!engineAvailable()) return null
        if (!sileroVadAvailable(context)) return null
        return try {
            buildSileroVad(context, 500L)
                .also { gateVad = it; Log.i(TAG, "silero GATE Vad loaded (decode gate, resident)") }
        } catch (e: Throwable) {
            Log.w(TAG, "silero gate VAD failed to load — decode gate falls back to energy", e)
            null
        }
    }

    private fun buildSileroVad(context: Context, minSilenceMs: Long) = Vad(
        assetManager = context.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = SILERO_VAD_ASSET,
                threshold = 0.5f,
                minSilenceDuration = minSilenceMs / 1000f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                // High so only true silence ends a turn — never a mid-utterance forced cut.
                // The coordinator's MAX_RECORDING cap + our 8s decode cap bound length.
                maxSpeechDuration = 20f,
            ),
            sampleRate = 16000, numThreads = 1, provider = "cpu",
        ),
    )
}

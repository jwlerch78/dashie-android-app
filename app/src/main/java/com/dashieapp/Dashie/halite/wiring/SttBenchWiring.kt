package com.dashieapp.Dashie.halite.wiring

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import com.dashieapp.Dashie.halite.voice.stt.SttBenchNativeRecognizer
import com.dashieapp.Dashie.halite.voice.stt.SttBenchRecorder
import com.dashieapp.Dashie.halite.voice.stt.SttBenchRunner
import com.dashieapp.Dashie.halite.voice.stt.SttProviderManager
import org.json.JSONObject
import java.io.File

/**
 * Dispatch for the STT benchmark endpoint (`?cmd=sttBench`, tools/stt-bench).
 *
 * Lives here rather than inline in MediaComponentWiring because that file is at
 * its 800-line budget and this is a self-contained feature — the wiring file
 * should hold the hook-up, not the logic.
 *
 * Two engine shapes, because SpeechRecognizer is not like the others:
 *  - everything implementing [com.dashieapp.Dashie.halite.voice.stt.SttProvider]
 *    is driven by [SttBenchRunner], which paces PCM in at 1x real time;
 *  - ANDROID_NATIVE owns its own microphone and ignores `streamAudio`, so it
 *    goes through [SttBenchNativeRecognizer] and the EXTRA_AUDIO_SOURCE file
 *    feed instead. It needs no SttProviderManager, so it is matched BEFORE the
 *    manager null-check — otherwise it would be unreachable whenever the voice
 *    pipeline hasn't initialized, which is the common case on a cold app.
 */
object SttBenchWiring {

    private const val TAG = "SttBench"

    /**
     * Register BOTH bench endpoints on the API service.
     *
     * The hook-ups live here rather than in `MediaComponentWiring` for the reason this file's
     * KDoc already gives — that file holds the hook-up, not the logic, and it is at its size
     * budget. Adding the record endpoint pushed it one line over, which is the gate working:
     * the split it forced is the one that was already described here.
     *
     * Both run off the main thread. The runner paces audio at 1x real time and then blocks on
     * the engine; the recorder blocks for the requested capture duration. Either on the UI
     * thread is an ANR.
     */
    fun install(
        service: com.dashieapp.Dashie.api.DashieServiceManager,
        context: Context,
        sttManager: () -> SttProviderManager?,
        buffer: () -> SharedAudioBuffer?,
    ) {
        service.onRunSttBench = { file, providerName, timeoutMs, reply ->
            Thread {
                // leadMs rides in on the provider name (…_MIC@1500) so the harness owns the
                // value and there is no second copy to drift.
                val lead = providerName.substringAfter('@', "0").toLongOrNull() ?: 0L
                reply(run(context, sttManager, file, providerName.substringBefore('@'),
                    timeoutMs, lead))
            }.start()
        }
        service.onRecordSttBench = { name, ms, reply ->
            Thread {
                reply(record(buffer,
                    com.dashieapp.Dashie.api.handlers.BenchClip.dir(context), name, ms))
            }.start()
        }
    }

    /** `?cmd=sttBenchRecord&clip=<name>&ms=<n>` — capture the room to a WAV. */
    fun record(bufferProvider: () -> SharedAudioBuffer?, dir: File,
               name: String, ms: Long): JSONObject {
        val json = JSONObject()
        return try {
            val r = SttBenchRecorder.record(bufferProvider(), File(dir, name), ms)
            json.put("file", r.file).put("requestedMs", r.requestedMs)
                .put("capturedMs", r.capturedMs)
                .put("rms", r.rms.toDouble()).put("peak", r.peak.toDouble())
            r.error?.let { json.put("error", it) }
            json
        } catch (e: Exception) {
            Log.w(TAG, "DROP: sttBenchRecord failed", e)
            json.put("error", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun run(
        context: Context,
        managerProvider: () -> SttProviderManager?,
        file: String,
        providerName: String,
        timeoutMs: Long,
        leadMs: Long = 0L,
    ): JSONObject {
        val json = JSONObject()
        try {
            // "ANDROID_NATIVE_CLOUD" selects createSpeechRecognizer over
            // createOnDeviceSpeechRecognizer — different products with different
            // EXTRA_AUDIO_SOURCE support, and which one honors a fed file is
            // exactly what's being probed.
            val onDeviceRecognizer = !providerName.contains("_CLOUD")
            // "_MIC" = loudspeaker rig: no EXTRA_AUDIO_SOURCE, the recognizer opens
            // the microphone and the harness plays the clip aloud beside the device.
            val fedAudio = !providerName.contains("_MIC")
            val type = runCatching {
                SttProviderManager.ProviderType.valueOf(
                    providerName.replace("_CLOUD", "").replace("_MIC", ""))
            }.getOrNull()

            when {
                type == null ->
                    json.put("error", "DROP: unknown provider '$providerName'")

                // Private-recognizer probe. Retained only for the fed-file
                // experiment, which is DEVICE-VERIFIED NOT TO WORK — see the
                // SttBenchNativeRecognizer KDoc. The loudspeaker rig uses "_MIC",
                // which goes through the registered provider below so it inherits
                // the mic handoff.
                type == SttProviderManager.ProviderType.ANDROID_NATIVE && fedAudio -> {
                    val r = SttBenchNativeRecognizer.run(
                        context, File(file), timeoutMs, onDeviceRecognizer, true)
                    json.put("provider", "ANDROID_NATIVE")
                        .put("transcript", r.transcript)
                        .put("ttftMs", r.ttftMs)
                        .put("ttftSource", "recognizer:${r.recognizer}")
                        .put("audioMs", 0).put("streamedMs", 0)
                        .put("pssBeforeKb", 0).put("pssAfterKb", 0).put("pssPeakKb", 0)
                    r.error?.let { json.put("error", it) }
                }

                else -> {
                    val manager = managerProvider()
                        ?: return json.put("error", "DROP: STT pipeline not initialized")
                    val pcm = SttBenchRunner.readWavPcm(File(file))
                    val r = SttBenchRunner.run(
                        manager, type, pcm, timeoutMs, micMode = !fedAudio, leadMs = leadMs)
                    json.put("provider", r.provider)
                        .put("transcript", r.transcript)
                        .put("ttftMs", r.ttftMs)
                        .put("ttftSource", r.ttftSource)
                        .put("audioMs", r.audioMs)
                        // Pacing evidence — the harness DROPS the row when this
                        // drifts from audioMs, so it must always be present.
                        .put("streamedMs", r.streamedMs)
                        .put("pssBeforeKb", r.pssBeforeKb)
                        .put("pssAfterKb", r.pssAfterKb)
                        .put("pssPeakKb", r.pssPeakKb)
                    r.error?.let { json.put("error", it) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DROP: sttBench failed", e)
            json.put("error", "${e.javaClass.simpleName}: ${e.message}")
        }
        return json
    }
}

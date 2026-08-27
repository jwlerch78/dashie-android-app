package com.dashieapp.Dashie.halite.wiring

import android.util.Log
import com.dashieapp.Dashie.audio.AudioCaptureService
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import com.dashieapp.Dashie.halite.voice.HaAssistBenchRunner
import com.dashieapp.Dashie.halite.voice.HaVoiceService
import com.dashieapp.Dashie.halite.voice.stt.SttBenchRunner
import org.json.JSONObject
import java.io.File

/**
 * Dispatch for the HA-Assist turn benchmark (`?cmd=haAssistBench`).
 *
 * Lives here rather than inline in MediaComponentWiring for the same reason
 * [SttBenchWiring] does: that file sits at its 800-line budget, and this is a self-contained
 * feature — the wiring file should hold the hook-up, not the logic.
 *
 * Every dependency is fetched through a provider lambda rather than captured: the voice stack
 * is torn down and rebuilt on WebView recreation and on every voice re-init, so a captured
 * [HaVoiceService] or [SharedAudioBuffer] would go stale and this would bench a dead object
 * (`reference_stale_webview_refs`).
 */
object HaAssistBenchWiring {

    private const val TAG = "HaAssistBench"

    fun run(
        serviceProvider: () -> HaVoiceService?,
        bufferProvider: () -> SharedAudioBuffer?,
        captureProvider: () -> AudioCaptureService?,
        file: String,
        timeoutMs: Long,
        allowBusy: Boolean = false,
    ): JSONObject {
        val json = JSONObject()
        try {
            // Named separately so a null tells you WHICH half of the stack is missing. "not
            // initialized" on its own sends you reading the wrong file.
            val service = serviceProvider()
                ?: return json.put("error",
                    "DROP: HaVoiceService not initialized — is the device in HA Voice Assist " +
                    "mode? (voice_control_method=voice_assistant). In cascade mode this " +
                    "service does not exist and there is nothing here to bench.")
            val buffer = bufferProvider()
                ?: return json.put("error", "DROP: shared audio buffer not initialized")

            val pcm = SttBenchRunner.readWavPcm(File(file))
            return HaAssistBenchRunner.run(
                service = service,
                buffer = buffer,
                capture = captureProvider(),
                pcm = pcm,
                timeoutMs = timeoutMs,
                allowBusy = allowBusy,
            )
        } catch (e: Exception) {
            Log.w(TAG, "DROP: haAssistBench failed", e)
            return json.put("error", "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

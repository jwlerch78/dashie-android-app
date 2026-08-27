package com.dashieapp.Dashie.api.handlers

import android.util.Log
import com.dashieapp.Dashie.api.DashieApiCallbacks
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * STT benchmark endpoint — measures **time-to-final-transcript from
 * end-of-speech** for one engine over one clip, on this device, through the
 * real [com.dashieapp.Dashie.halite.voice.stt.SttProvider] path.
 *
 * Why on-device at all, when the accuracy lane scores every engine off-device:
 * accuracy is device-independent (identical weights → identical transcripts,
 * and the cloud engines don't care who calls them) but SPEED is not. Local
 * decode is bound by this SoC, and the network engines' latency depends on this
 * tablet's radio and path — timing them from a laptop measures the laptop.
 *
 * Corpus clips are pushed to the app's external files dir first (adb push →
 * Android/data/<pkg>/files/stt-bench/), so this takes a clip NAME, not an
 * upload; the harness drives one clip per request.
 *
 *   GET /?cmd=sttBench&clip=1089-134686-0000.wav
 *                     &provider=SHERPA_MOONSHINE_TINY[&timeoutMs=60000]
 *
 * Response mirrors SttBenchRunner.Result, including PSS around the decode so
 * RAM cost is captured on the device where it matters rather than inferred from
 * host RSS.
 */
class SttBenchHandler(
    private val context: android.content.Context,
    private val callbacks: DashieApiCallbacks
) {
    companion object {
        private const val TAG = "SttBench"
    }

    /**
     * `GET /?cmd=sttBenchRecord&clip=<name>[&ms=<n>]` — capture the room to a WAV in the corpus
     * dir, so a clip can be made ON the device under test instead of pushed to it.
     *
     * 🔴 **This endpoint existed and could not be reached.** `SttBenchWiring.record` was written,
     * documented with this exact query string, and called by the harness — but nothing ever added
     * the dispatcher branch, so every request fell through to "unknown command" (, N5). The
     * body was correct in its file and never executed by the runtime that mattered; standing rule
     * 3, and the reason the branch and the handler now land in the same change.
     */
    fun handleRecord(params: Map<String, String>): NanoHTTPD.Response {
        val name = params["clip"] ?: params["file"]
            ?: return errorResponse("Missing 'clip' parameter")
        val ms = params["ms"]?.toLongOrNull() ?: 5_000L
        if (ms !in 250..120_000) {
            return errorResponse("'ms' must be between 250 and 120000 (got $ms)")
        }
        // Basename only — the recorder writes into the corpus dir and must not be steerable out
        // of it, same stance as BenchClip.resolve on the read side.
        val safeName = java.io.File(name).name

        val latch = java.util.concurrent.CountDownLatch(1)
        var payload: JSONObject? = null
        callbacks.recordSttBench(safeName, ms) { json ->
            payload = json
            latch.countDown()
        }
        // Capture is real time, so the wait must clear the requested duration plus slack.
        if (!latch.await(ms + 30_000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "DROP: sttBenchRecord timed out capturing $safeName")
            return errorResponse("record did not return", NanoHTTPD.Response.Status.INTERNAL_ERROR)
        }
        val body = payload ?: return errorResponse("record produced no result",
            NanoHTTPD.Response.Status.INTERNAL_ERROR)
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, "application/json", body.toString()
        )
    }

    fun handle(params: Map<String, String>): NanoHTTPD.Response {
        // `clip` is the supported form; `file` stays accepted so a basename still
        // works if an older harness sends one.
        val requested = params["clip"] ?: params["file"]
            ?: return errorResponse("Missing 'clip' parameter")
        val provider = params["provider"] ?: return errorResponse("Missing 'provider' parameter")
        val timeoutMs = params["timeoutMs"]?.toLongOrNull() ?: 60_000L

        // Resolution is shared with HaAssistBenchHandler — see BenchClip for why this takes a
        // basename rather than a path.
        val canonical = when (val r = BenchClip.resolve(context, requested)) {
            is BenchClip.Result.Err -> {
                if (r.logIt) Log.w(TAG, "DROP: sttBench ${r.message}")
                return errorResponse(r.message)
            }
            is BenchClip.Result.Ok -> r.canonicalPath
        }

        val latch = java.util.concurrent.CountDownLatch(1)
        var payload: JSONObject? = null

        callbacks.runSttBench(canonical, provider, timeoutMs) { json ->
            payload = json
            latch.countDown()
        }

        // The runner paces audio at 1x real time, so a 30 s clip takes 30 s
        // before decoding even starts — the HTTP wait must clear audio + engine.
        if (!latch.await(timeoutMs + 120_000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "DROP: sttBench harness timeout for $provider on $canonical")
            return errorResponse("bench did not return", NanoHTTPD.Response.Status.INTERNAL_ERROR)
        }

        val body = payload ?: return errorResponse("bench produced no result",
            NanoHTTPD.Response.Status.INTERNAL_ERROR)
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, "application/json", body.toString()
        )
    }
}

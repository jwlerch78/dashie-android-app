package com.dashieapp.Dashie.api.handlers

import android.util.Log
import com.dashieapp.Dashie.api.DashieApiCallbacks
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Headless **HA Voice Assist** turn driver — `?cmd=haAssistBench&clip=<name>[&timeoutMs=]`.
 *
 * The R5/R6 counterpart to [SttBenchHandler]. Where `sttBench` pins one engine and measures it,
 * this drives the whole HA-Assist pipeline from staged audio and lets the real selection logic
 * ([com.dashieapp.Dashie.halite.voice.HaAssistStagePlanner],
 * `SttProviderFactory.resolvePriority`) decide what runs — which is the only way the matrix's
 * "local STT must never fall back to the cloud" assertion can fail. See
 * [com.dashieapp.Dashie.halite.voice.HaAssistBenchRunner] for why this takes audio and not text.
 *
 * Clip resolution is SHARED with [SttBenchHandler] via [BenchClip] rather than copied — see that
 * object for why the endpoint takes a basename and not a path.
 */
class HaAssistBenchHandler(
    private val context: android.content.Context,
    private val callbacks: DashieApiCallbacks
) {
    companion object {
        private const val TAG = "HaAssistBench"
    }

    fun handle(params: Map<String, String>): NanoHTTPD.Response {
        val requested = params["clip"] ?: params["file"]
            ?: return errorResponse("Missing 'clip' parameter")
        val timeoutMs = params["timeoutMs"]?.toLongOrNull() ?: 60_000L
        // #57: let a bench turn fire into a BUSY pipeline. Default false keeps the refusal.
        // With it, mechanism (c) — a wake honoured mid-SPEAKING — becomes headlessly
        // regression-testable; without it the only instrument is a person in a room.
        val allowBusy = params["allowBusy"] == "1" || params["allowBusy"] == "true"

        val canonical = when (val r = BenchClip.resolve(context, requested)) {
            is BenchClip.Result.Err -> {
                if (r.logIt) Log.w(TAG, "DROP: haAssistBench ${r.message}")
                return errorResponse(r.message)
            }
            is BenchClip.Result.Ok -> r.canonicalPath
        }

        val latch = java.util.concurrent.CountDownLatch(1)
        var payload: JSONObject? = null

        callbacks.runHaAssistBench(canonical, timeoutMs, allowBusy) { json ->
            payload = json
            latch.countDown()
        }

        // The runner paces audio at 1x and then waits out the turn (connect + STT + intent +
        // TTS), so the HTTP wait must clear all of it with room to spare.
        if (!latch.await(timeoutMs + 120_000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "DROP: haAssistBench harness timeout on $canonical")
            return errorResponse("bench did not return", NanoHTTPD.Response.Status.INTERNAL_ERROR)
        }

        val body = payload ?: return errorResponse("bench produced no result",
            NanoHTTPD.Response.Status.INTERNAL_ERROR)
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, "application/json", body.toString()
        )
    }
}

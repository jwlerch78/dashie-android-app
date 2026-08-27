package com.dashieapp.Dashie.voice.realtime

// BUNDLE-EXEMPT: dashieCalendarTool — FULL-mode-only by design (honest handshake 2026-07-19): kiosk doesn't claim 'calendar', so this bridge is unreachable there (brain drops the offering; Live drops the declaration). DECIDED (2026-07-19, calendar option C): this JS bridge IS calendar's end-state executor on Android full mode — the merged multi-provider calendar lives in the WebView; kiosk stays honest-decline. Revisit only if headless/server calendar fulfillment gets built (option A).
// BUNDLE-EXEMPT: dashieCalendarContext — full-mode-only, same handshake gating as dashieCalendarTool (see above).

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridges the realtime engine's `get_calendar_events` tool to the on-device JS
 * calendar tool (`window.dashieCalendarTool.query`) — the SAME merged, multi-provider
 * tool the cascade uses (calendar-tool.js → calendar-info-service). Device-fulfilled
 * by design: the merged calendar truth (Google + Outlook + HA + CalDAV) lives in the
 * WebView, so we evaluate JS and read the async result back.
 *
 * Self-contained: NO new @JavascriptInterface, NO MainActivity/bootstrap wiring. The
 * dispatcher already runs off the main thread (the `home_assistant` tool blocks on
 * HTTP there), so blocking on the JS promise is consistent with the existing pattern.
 * evaluateJavascript must run on the UI thread, so each round-trip is posted to `main`
 * and the calling thread waits on a latch.
 *
 * Build plan: 20260623_VOICE_TIER1_STRUCTURED_TOOLS.md §3 (realtime fulfiller).
 */
class RealtimeCalendarBridge(private val webViewProvider: () -> WebView?) {

    private val main = Handler(Looper.getMainLooper())
    private val seq = AtomicLong(0)

    /**
     * Run `window.dashieCalendarTool.query(args)` and return its `{ result, card }`
     * JSON. Blocks the calling (background) thread until the JS promise resolves or
     * the timeout elapses. Returns `{ result: { found:false }, error }` on failure.
     */
    fun query(args: JSONObject, timeoutMs: Long = 6000L): JSONObject {
        val argsJson = args.toString()
        val wv = webViewProvider()
        if (wv == null) { Log.w(TAG, "query: NO WEBVIEW (args=$argsJson)"); return err("no webview") }
        Log.i(TAG, "query: $argsJson")
        val id = seq.incrementAndGet()

        // Kick off the async query; stash the stringified result under a per-call id so
        // a poll can pick it up once the promise resolves. We DYNAMICALLY IMPORT the
        // tool module first (idempotent) — in conversation mode the cascade router never
        // runs, so `window.dashieCalendarTool` would otherwise be unregistered. The
        // import side-effect registers it; then we query.
        // ABSOLUTE url: injected (evaluateJavascript) code has base URL about:blank, so a
        // root-relative specifier won't resolve. window.location.origin gives the page's
        // real origin → same-origin import, nothing to resolve against a base.
        val kickoff = "(function(){window.__dashieCal=window.__dashieCal||{};" +
            "try{import(window.location.origin+'/js/core/voice/calendar-tool.js')" +
            ".then(function(){return window.dashieCalendarTool.query($argsJson);})" +
            ".then(function(r){window.__dashieCal[$id]=JSON.stringify(r);})" +
            ".catch(function(e){window.__dashieCal[$id]=JSON.stringify({error:String((e&&e.message)||e)});});}" +
            "catch(e){window.__dashieCal[$id]=JSON.stringify({error:String((e&&e.message)||e)});}})()"
        main.post { runCatching { wv.evaluateJavascript(kickoff, null) } }

        // Poll for the stashed result (and delete it once read).
        val poll = "(function(){var m=window.__dashieCal||{};var v=m[$id];if(v){delete m[$id];}return v||'';})()"
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val unwrapped = unwrap(evalOnce(wv, poll))
            if (unwrapped != null) {
                val json = runCatching { JSONObject(unwrapped) }.getOrElse { return err("bad JSON from tool") }
                // Don't log event contents (PII) — just the outcome.
                val found = json.optJSONObject("result")?.optBoolean("found")
                val errMsg = json.optString("error", "")
                Log.i(TAG, if (errMsg.isNotEmpty()) "tool error: $errMsg" else "result: found=$found")
                return json
            }
            Thread.sleep(60)
        }
        Log.w(TAG, "calendar bridge: timed out after ${timeoutMs}ms (import/query never resolved)")
        return err("calendar lookup timed out")
    }

    /**
     * Run `window.dashieCalendarTool.write(args)` — the calendar WRITE tool (create;
     * confirm-first, device-owned pending draft — 20260713_VOICE_CALENDAR_CRUD_DESIGN.md).
     * Same import+poll pattern as [query]; the same module import registers the write
     * half (calendar-tool.js lazy-attaches it). `result.needs_followup` = the turn is a
     * question (which calendar / confirm) and the caller should keep listening.
     * Longer default timeout: a create's confirm leg does a provider POST round-trip.
     */
    fun write(args: JSONObject, timeoutMs: Long = 10000L): JSONObject {
        val argsJson = args.toString()
        val wv = webViewProvider()
        if (wv == null) { Log.w(TAG, "write: NO WEBVIEW"); return err("no webview") }
        // Don't log args (event titles are PII) — just the action.
        Log.i(TAG, "write: action=${args.optString("action")}")
        val id = seq.incrementAndGet()
        val kickoff = "(function(){window.__dashieCal=window.__dashieCal||{};" +
            "try{import(window.location.origin+'/js/core/voice/calendar-tool.js')" +
            ".then(function(){return window.dashieCalendarTool.write($argsJson);})" +
            ".then(function(r){window.__dashieCal[$id]=JSON.stringify(r);})" +
            ".catch(function(e){window.__dashieCal[$id]=JSON.stringify({error:String((e&&e.message)||e)});});}" +
            "catch(e){window.__dashieCal[$id]=JSON.stringify({error:String((e&&e.message)||e)});}})()"
        main.post { runCatching { wv.evaluateJavascript(kickoff, null) } }

        val poll = "(function(){var m=window.__dashieCal||{};var v=m[$id];if(v){delete m[$id];}return v||'';})()"
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val unwrapped = unwrap(evalOnce(wv, poll))
            if (unwrapped != null) {
                val json = runCatching { JSONObject(unwrapped) }.getOrElse { return err("bad JSON from tool") }
                val r = json.optJSONObject("result")
                val errMsg = json.optString("error", "")
                Log.i(TAG, if (errMsg.isNotEmpty()) "write error: $errMsg"
                    else "write: ok=${r?.optBoolean("ok")} followup=${r?.optBoolean("needs_followup")}")
                return json
            }
            Thread.sleep(60)
        }
        Log.w(TAG, "calendar bridge: write timed out after ${timeoutMs}ms")
        return err("calendar write timed out")
    }

    /**
     * Calendar-color (20260711, Phase 3): run the cascade PRE-FLIGHT — the deterministic
     * calendar sniff + window fetch — via `window.dashieCalendarContext.build(text)`.
     * Returns `{provided, card}` (attach `provided` as provided_context.calendar; HOLD
     * `card` and render it when the brain replies with metadata.calendar_context_used),
     * or null: not calendar-shaped, empty window, or timeout — all of which mean
     * "proceed without, the tool path answers as before". Same import+poll pattern as
     * [query]; the sniff itself lives ONCE in JS (no Kotlin regex mirror — contracts
     * tier: eliminate). MUST be called off the main thread (blocks on the WebView).
     * The JS side is internally bounded at 1500ms, so 2500 here covers import overhead.
     */
    fun buildContext(text: String, timeoutMs: Long = 2500L): JSONObject? {
        val wv = webViewProvider()
        if (wv == null) { Log.w(TAG, "buildContext: no webview"); return null }
        val id = seq.incrementAndGet()
        val textJson = JSONObject.quote(text)
        val kickoff = "(function(){window.__dashieCal=window.__dashieCal||{};" +
            "try{import(window.location.origin+'/js/core/voice/calendar-context.js')" +
            ".then(function(){return window.dashieCalendarContext.build($textJson);})" +
            ".then(function(r){window.__dashieCal[$id]=JSON.stringify(r||null);})" +
            ".catch(function(e){window.__dashieCal[$id]='null';});}" +
            "catch(e){window.__dashieCal[$id]='null';}})()"
        main.post { runCatching { wv.evaluateJavascript(kickoff, null) } }

        val poll = "(function(){var m=window.__dashieCal||{};var v=m[$id];if(v){delete m[$id];}return v||'';})()"
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val unwrapped = unwrap(evalOnce(wv, poll))
            if (unwrapped != null) {
                if (unwrapped == "null") return null   // sniff miss / empty window — normal
                val json = runCatching { JSONObject(unwrapped) }.getOrNull() ?: return null
                // Don't log event contents (PII) — just the window + count.
                val p = json.optJSONObject("provided")
                Log.i(TAG, "context: window=${p?.optString("time_range")} events=${p?.optJSONArray("events")?.length() ?: 0}")
                return json
            }
            Thread.sleep(60)
        }
        Log.i(TAG, "context: pre-flight timed out (${timeoutMs}ms) — proceeding without")
        return null
    }

    /** One evaluateJavascript round-trip, blocking briefly for its callback value. */
    private fun evalOnce(wv: WebView, script: String): String? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        main.post {
            runCatching { wv.evaluateJavascript(script) { v -> holder[0] = v; latch.countDown() } }
                .onFailure { latch.countDown() }
        }
        latch.await(500, TimeUnit.MILLISECONDS)
        return holder[0]
    }

    /** evaluateJavascript returns the value JSON-ENCODED; '', null, "null" all mean
     *  not-ready-yet. Otherwise unwrap the outer encoding to the inner JSON string. */
    private fun unwrap(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null" || raw == "\"\"") return null
        return runCatching { JSONTokener(raw).nextValue() as? String }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun err(msg: String): JSONObject =
        JSONObject().put("result", JSONObject().put("found", false)).put("error", msg)

    companion object { private const val TAG = "RtCalendarBridge" }
}

package com.dashieapp.Dashie.voice.realtime

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
 * Bridges the native conversation/dialog brain turn to the on-device JS HA voice context
 * (`window.dashieHaVoiceContext.build`) — the SAME voice-controllable entity set the JS fast path
 * builds (getVoiceEntityIds honoring the account's entitySource + entitiesForBrain + device area).
 *
 * Why this exists (20260717): converseBrain sent the brain only calendar context, never the HA
 * entity list, so a device command ("turn on the string lights") that fell through the on-device
 * fast path reached the LLM with nothing to act on → "Sorry, I couldn't get an answer right now".
 * We fetch the list here and attach it as provided_context.ha_entities (+ device_area).
 *
 * Self-contained (mirrors [RealtimeCalendarBridge]): NO new @JavascriptInterface, NO MainActivity
 * wiring. Called OFF the main thread (runBrainTurn's pre-flight executor blocks on the WebView
 * round-trips); evaluateJavascript itself is posted to `main` and the caller waits on a latch.
 */
class RealtimeHaEntitiesBridge(private val webViewProvider: () -> WebView?) {

    private val main = Handler(Looper.getMainLooper())
    private val seq = AtomicLong(0)

    /**
     * Run `window.dashieHaVoiceContext.build()` and return its `{ ha_entities, device_area }`
     * JSON, or null (no webview / empty / timeout / failure) — null means "proceed without HA
     * context", exactly like the calendar pre-flight. Blocks the calling (background) thread.
     */
    fun build(timeoutMs: Long = 3000L): JSONObject? {
        val wv = webViewProvider()
        if (wv == null) { Log.w(TAG, "build: no webview"); return null }
        val id = seq.incrementAndGet()

        // Three frames, three paths — this evaluateJavascript runs in the MAIN WebView frame:
        //  1. WEBAPP with the global already imported → call it directly.
        //  2. KIOSK: window.dashieHaVoiceContext lives in the `dashie-overlay` SERVICES IFRAME, NOT
        //     this frame (a raw '/js/core/voice/…' import would 404 — HA serves only the bundle). So
        //     relay via postMessage exactly like VoiceOverlayBridge.processVoiceCommand does
        //     (dashie-call → overlay → response back), stashing the reply by id for the poll below.
        //  3. WEBAPP first turn (no global, no overlay) → dynamically import the module, then build.
        val kickoff = "(function(){window.__dashieHaCtx=window.__dashieHaCtx||{};" +
            "if(window.dashieHaVoiceContext){try{window.dashieHaVoiceContext.build()" +
            ".then(function(r){window.__dashieHaCtx[$id]=JSON.stringify(r||null);})" +
            ".catch(function(e){window.__dashieHaCtx[$id]='null';});}catch(e){window.__dashieHaCtx[$id]='null';}return;}" +
            "var ov=document.getElementById('dashie-overlay');" +
            "if(ov&&ov.contentWindow){" +
            "var h=function(e){var d=e&&e.data;if(d&&d.source==='dashie-overlay'&&d.type==='ha-voice-context-response'&&d.requestId==='$id'){" +
            "window.removeEventListener('message',h);window.__dashieHaCtx[$id]=JSON.stringify(d.result||null);}};" +
            "window.addEventListener('message',h);" +
            "ov.contentWindow.postMessage({source:'dashie-parent',type:'dashie-call',method:'buildHaVoiceContext',args:[],callbackId:'$id'},'*');return;}" +
            "try{import(window.location.origin+'/js/core/voice/ha-voice-context.js')" +
            ".then(function(){return window.dashieHaVoiceContext.build();})" +
            ".then(function(r){window.__dashieHaCtx[$id]=JSON.stringify(r||null);})" +
            ".catch(function(e){window.__dashieHaCtx[$id]='null';});}" +
            "catch(e){window.__dashieHaCtx[$id]='null';}})()"
        main.post { runCatching { wv.evaluateJavascript(kickoff, null) } }

        val poll = "(function(){var m=window.__dashieHaCtx||{};var v=m[$id];if(v){delete m[$id];}return v||'';})()"
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val unwrapped = unwrap(evalOnce(wv, poll))
            if (unwrapped != null) {
                if (unwrapped == "null") return null   // failure / no context — proceed without
                val json = runCatching { JSONObject(unwrapped) }.getOrNull() ?: return null
                // Don't log entity ids (could reveal the home's layout) — just the count.
                val n = json.optJSONArray("ha_entities")?.length() ?: 0
                Log.i(TAG, "context: ha_entities=$n area=${json.optString("device_area")}")
                return json
            }
            Thread.sleep(60)
        }
        Log.i(TAG, "ha context: build timed out (${timeoutMs}ms) — proceeding without")
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

    companion object { private const val TAG = "RtHaEntitiesBridge" }
}

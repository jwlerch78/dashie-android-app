package com.dashieapp.Dashie.halite.voice

import android.util.Log

/**
 * Wave-2 voice stage timing — reports REAL native STT/TTS measurements to the
 * webapp's `window.dashieVoiceTiming` bridge (js/core/voice/native-stage-timing.js),
 * which folds them into the per-turn `voice_turn_timing` row:
 *
 *   reportStt(ms, engine) — audio-end → final transcript, called at transcript delivery
 *   reportTts(ms, engine) — synth-request → first audio actually playing
 *
 * Why: the JS row historically carried stt_ms=null (STT is Kotlin-owned) and a ~3ms
 * tts_ms (VOICE_SPEECH_START fires when DashieNative.speak is CALLED, not at first
 * audio). The JS side is grace-window based — an old webapp that never installed
 * dashieVoiceTiming just ignores these evals (typeof guard), so this is compat-safe
 * in both directions.
 *
 * The eval targets BOTH JS contexts (the overlay iframe hosts the voice-command-router
 * on the dashboard; standalone mode runs it on the top window) — same dual-path shape
 * as VoiceOverlayBridge.processVoiceCommand.
 *
 * evalSink is installed by VoiceOverlayBridge (webView-provider based, so WebView
 * recreation after memory pressure is safe). Reports fired before the sink exists
 * (or on kiosk surfaces with no webapp) are dropped — timing is best-effort analytics
 * and must never affect the turn.
 */
object VoiceStageTiming {
    private const val TAG = "VoiceStageTiming"
    private const val MAX_SANE_MS = 60_000L

    @Volatile
    var evalSink: ((String) -> Unit)? = null

    // ── per-turn accumulator (NATIVE-owned turns) ────────────────────────────
    // The cascade brain loop (VoicePipelineCoordinator.runBrainTurn) never passes
    // through the JS voice-command-router, so the router's _writeTurnTiming never
    // fires and the reportStt/reportTts relays land in the JS bridge's slots with
    // nothing to consume them → no voice_turn_timing row. We stash each stage's
    // ms+engine here (alongside the relay, which stays for the JS-router path) and
    // submitNativeTurn() emits the whole row via dashieVoiceTiming.logNativeTurn.
    // Dialog is serial (one turn at a time), so a single mutable slot is safe;
    // timestamps gate out a prior turn's stale measurement.
    private var accSttMs: Long? = null
    private var accSttEngine: String? = null
    private var accSttAt: Long = 0L
    private var accTtsMs: Long? = null
    private var accTtsEngine: String? = null
    private var accTtsAt: Long = 0L

    fun reportStt(ms: Long, engine: String?) {
        if (ms in 0..MAX_SANE_MS) { accSttMs = ms; accSttEngine = engine; accSttAt = System.currentTimeMillis() }
        report("reportStt", ms, engine)
    }
    fun reportTts(ms: Long, engine: String?) {
        if (ms in 0..MAX_SANE_MS) { accTtsMs = ms; accTtsEngine = engine; accTtsAt = System.currentTimeMillis() }
        report("reportTts", ms, engine)
    }

    /**
     * Emit a whole `voice_turn_timing` row for a NATIVE-owned turn (the VPC brain
     * loop). Attaches the STT and TTS(ttfa) stages only when they were reported
     * at/after [turnStartMs] — so a prior turn's stale measurement (e.g. a "miss"
     * turn that never spoke) can't bleed into this one — then clears them. tts_ms
     * and ttfa_ms both carry the synth-request→first-audio measurement (the JS
     * bridge computes e2e = stt + ttfa). Best-effort analytics: a missing sink or
     * a blank session_id drops silently and never affects the turn.
     */
    fun submitNativeTurn(
        sessionId: String?, turnStartMs: Long, brainMs: Long?,
        path: String, model: String?, route: String?, success: Boolean,
    ) {
        val sink = evalSink
        if (sessionId.isNullOrBlank() || sink == null) { clearTurn(); return }
        val sttMs = accSttMs?.takeIf { accSttAt >= turnStartMs }
        val sttEng = accSttEngine?.takeIf { accSttAt >= turnStartMs }
        val ttfaMs = accTtsMs?.takeIf { accTtsAt >= turnStartMs }
        val ttsEng = accTtsEngine?.takeIf { accTtsAt >= turnStartMs }
        clearTurn()
        val obj = org.json.JSONObject().apply {
            put("session_id", sessionId)
            put("path", path)
            if (model != null) put("model", model)
            if (route != null) put("route", route)
            if (sttMs != null) put("stt_ms", sttMs)
            if (sttEng != null) put("stt_engine", sttEng)
            if (brainMs != null && brainMs in 0..MAX_SANE_MS) put("brain_ms", brainMs)
            if (ttfaMs != null) { put("tts_ms", ttfaMs); put("ttfa_ms", ttfaMs) }
            if (ttsEng != null) put("tts_engine", ttsEng)
            put("success", success)
        }
        // BUNDLE-EXEMPT: dashieVoiceTiming — timing telemetry only; guarded, nothing functional depends on it
        val js = """
            (function(){
              var p = ${obj};
              try {
                var o = document.getElementById('dashie-overlay');
                if (o && o.contentWindow && o.contentWindow.dashieVoiceTiming) { o.contentWindow.dashieVoiceTiming.logNativeTurn(p); return true; }
              } catch(e) {}
              try { if (window.dashieVoiceTiming) { window.dashieVoiceTiming.logNativeTurn(p); return true; } } catch(e) {}
              return false;
            })();
        """.trimIndent()
        try {
            sink(js)
            Log.d(TAG, "logNativeTurn session=$sessionId stt=$sttMs/$sttEng brain=$brainMs tts=$ttfaMs/$ttsEng")
        } catch (e: Exception) {
            Log.w(TAG, "native-turn timing eval failed: ${e.message}")
        }
    }

    private fun clearTurn() { accSttMs = null; accTtsMs = null }

    private fun report(method: String, ms: Long, engine: String?) {
        if (ms < 0 || ms > MAX_SANE_MS) return
        val sink = evalSink ?: return
        // engine ids are our own constants/pref ids — strip anything that could break the literal
        val eng = (engine ?: "").replace(Regex("[^A-Za-z0-9_.:-]"), "")
        val payload = """{"ms":$ms,"engine":"$eng"}"""
        val js = """
            (function(){
              var p = $payload;
              try {
                var o = document.getElementById('dashie-overlay');
                if (o && o.contentWindow && o.contentWindow.dashieVoiceTiming) { o.contentWindow.dashieVoiceTiming.$method(p); return true; }
              } catch(e) {}
              try { if (window.dashieVoiceTiming) { window.dashieVoiceTiming.$method(p); return true; } } catch(e) {}
              return false;
            })();
        """.trimIndent()
        try {
            sink(js)
            Log.d(TAG, "$method ms=$ms engine=$eng")
        } catch (e: Exception) {
            Log.w(TAG, "stage-timing eval failed: ${e.message}")
        }
    }
}

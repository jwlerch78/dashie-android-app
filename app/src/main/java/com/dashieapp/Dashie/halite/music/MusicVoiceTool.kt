package com.dashieapp.Dashie.halite.music

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The device executor for the AI `music` tool (Kind B — device-fulfilled, model-in-loop).
 *
 * Serves the REASONING cases: "what song is this?", find-and-disambiguate, "play the
 * acoustic version of X on the kitchen speaker". Latency-critical one-shots ("pause",
 * "next", "volume up", simple "play X") stay on the fast-path classifier and NEVER
 * come through here — hold that boundary (design: 20260711_AI_MUSIC_TOOL_DESIGN.md §3).
 *
 * Two callers, one executor:
 *   • Realtime: RealtimeToolDispatcher `"music"` arm (Gemini Live function call).
 *   • Cascade:  DashieJSBridge.musicToolQuery → the JS lane's music-tool.js, which adds
 *     the deterministic spoken line (the cascade has no second model pass on this path).
 *
 * Everything is reused plumbing: now-playing from [MusicStateCoordinator] (the existing
 * poller subscription — no second connection), search/play via [MaApiClient]
 * (`music/search`, `searchAndPlay`, `playMedia`), speakers via [SpeakerNameMatcher].
 * Blocking MA REST calls are fine here: both callers already run off the main thread.
 *
 * Registered by MusicComponentWiring (NOT MainActivity) via [instance]; null when music
 * isn't configured on this device — callers degrade to a found:false / not-available result.
 */
class MusicVoiceTool(
    private val coordinator: MusicStateCoordinator,
    private val apiClientProvider: () -> MaApiClient?,
    private val entityResolver: () -> String?,
    private val speakerMatcher: SpeakerNameMatcher,
) {

    companion object {
        private const val TAG = "MusicVoiceTool"

        /** The wired instance (set by MusicComponentWiring, cleared on WebView-recreation
         *  teardown). Null ⇒ music not configured — dispatch/bridge return not-available. */
        @Volatile
        var instance: MusicVoiceTool? = null

        private const val SEARCH_LIMIT = 12
        private const val MAX_RESULTS = 8

        /**
         * Does this music action START audible playback? The voice pipeline must NOT re-arm the
         * mic after one: the speaker is now producing sound the STT hears as the next utterance
         * (field 2026-07-18 — "turn off the lights and play the beatles" re-listened and answered
         * the Beatles). Transport/read actions (pause, stop, volume, now_playing) stay silent, so
         * re-arming after those is legitimate and still honors "keep dialog open".
         *
         * Covers BOTH vocabularies: the brain's tool actions (play/resume/next/previous/…) and the
         * local classifier's commands (play/play_search/next/previous/…) — see
         * js/core/intent-classifier/classifiers/music-intents.js.
         */
        fun startsAudiblePlayback(action: String?): Boolean = when (action?.trim()?.lowercase()) {
            "play", "play_search", "play_media", "resume", "next", "previous" -> true
            else -> false
        }
    }

    /** Run one tool call. [args]: { action, query?, uri?, speaker? }.
     *  Returns the model-facing result object (data reads ⇒ found:true/false; actions ⇒
     *  ok/error status per the tool contract).
     *
     *  Transport actions exist here because conversation-always mode hands EVERY wake-word
     *  utterance to the model as a Live initial command — the fast-path classifier never
     *  runs, so "stop the music" / "turn it up" MUST be model-callable (field report
     *  2026-07-12: the model searched the library for the word "stop"). One-shot paths
     *  that do hit the classifier still never call this. */
    fun execute(args: JSONObject): JSONObject = try {
        when (val action = args.optString("action")) {
            "now_playing" -> nowPlaying()
            "search" -> search(args.optString("query"))
            "play" -> play(
                uri = args.optString("uri").takeIf { it.isNotEmpty() },
                query = args.optString("query").takeIf { it.isNotEmpty() },
                speaker = args.optString("speaker").takeIf { it.isNotEmpty() },
            )
            "pause", "stop", "next", "previous" -> transport(action)
            "resume" -> transport("play")
            "volume_up" -> volumeStep(up = true)
            "volume_down" -> volumeStep(up = false)
            else -> JSONObject().put("error", "unknown action '$action'")
        }
    } catch (e: Exception) {
        Log.e(TAG, "🎵 tool call failed: ${e.message}", e)
        JSONObject().put("error", e.message ?: "music tool failed")
    }

    // ── transport (actions) ──────────────────────────────────────────────────

    private fun transport(cmd: String): JSONObject {
        val client = apiClientProvider() ?: return JSONObject().put("error", "music not available")
        val entity = entityResolver() ?: return JSONObject().put("error", "no music player selected")
        val ok = client.sendPlayerCommand(entity, cmd)
        Log.i(TAG, "🎵 transport $cmd on $entity → ${if (ok) "OK" else "FAILED"}")
        return if (ok) JSONObject().put("ok", true).put("action", cmd)
        else JSONObject().put("error", "couldn't $cmd")
    }

    private fun volumeStep(up: Boolean): JSONObject {
        val client = apiClientProvider() ?: return JSONObject().put("error", "music not available")
        val entity = entityResolver() ?: return JSONObject().put("error", "no music player selected")
        val current = coordinator.getDisplayVolumePercent().coerceIn(0, 100)
        val next = (if (up) current + 15 else current - 15).coerceIn(0, 100)
        val ok = client.setVolume(entity, next)
        Log.i(TAG, "🎵 volume ${if (up) "up" else "down"}: $current → $next on $entity → ${if (ok) "OK" else "FAILED"}")
        return if (ok) JSONObject().put("ok", true).put("volume_percent", next)
        else JSONObject().put("error", "couldn't change the volume")
    }

    // ── now_playing (read) ───────────────────────────────────────────────────

    private fun nowPlaying(): JSONObject {
        val snap = coordinator.getNowPlayingSnapshot()
        Log.i(TAG, "🎵 now_playing: found=${snap.optBoolean("found")} track='${snap.optString("track").take(40)}'")
        return snap
    }

    // ── search (read) ────────────────────────────────────────────────────────

    private fun search(query: String): JSONObject {
        if (query.isBlank()) return JSONObject().put("found", false).put("error", "empty query")
        val client = apiClientProvider()
            ?: return JSONObject().put("found", false).put("error", "music library not available")
        val arr = client.searchLibrary(query, SEARCH_LIMIT)
            ?: return JSONObject().put("found", false)
        val results = JSONArray()
        for (i in 0 until minOf(arr.length(), MAX_RESULTS)) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name", "")
            val uri = obj.optString("uri", "")
            if (name.isEmpty() || uri.isEmpty()) continue
            val item = JSONObject()
                .put("name", name)
                .put("type", obj.optString("media_type", "track"))
                .put("uri", uri)
            obj.optJSONArray("artists")
                ?.optJSONObject(0)?.optString("name", "")
                ?.takeIf { it.isNotEmpty() }
                ?.let { item.put("artist", it) }
            results.put(item)
        }
        Log.i(TAG, "🎵 search '$query' → ${results.length()} result(s)")
        if (results.length() == 0) return JSONObject().put("found", false)
        return JSONObject().put("found", true).put("results", results)
    }

    // ── play (action) ────────────────────────────────────────────────────────

    private fun play(uri: String?, query: String?, speaker: String?): JSONObject {
        if (uri == null && query == null) {
            return JSONObject().put("error", "play needs a uri (from a search result) or a query")
        }
        val client = apiClientProvider()
            ?: return JSONObject().put("error", "music playback not available")

        // Target: a named speaker wins (resolved fuzzily against MA's player list);
        // otherwise the active/configured entity — the same chain voice commands use.
        var speakerName: String? = null
        val target = if (speaker != null) {
            val resolved = speakerMatcher.resolveWithName(speaker)
                ?: return JSONObject().put("error", "no speaker matching '$speaker'")
            speakerName = resolved.second
            resolved.first
        } else {
            entityResolver() ?: return JSONObject().put("error", "no music player selected on this device")
        }

        // Continuation is derived from what we play, never hardcoded — an explicit
        // artist/album request must not drift into history-seeded radio.
        val ok = if (uri != null) {
            client.playMedia(target, uri, MaVoiceSearchResolver.continuationForUri(uri))
        } else {
            client.searchAndPlay(target, query!!)
        }
        Log.i(TAG, "🎵 play uri=${uri?.take(40)} query='${query ?: ""}' on $target → ${if (ok) "OK" else "FAILED"}")

        if (!ok) return JSONObject().put("error", "couldn't play that")
        return JSONObject().apply {
            put("ok", true)
            put("playing", uri ?: query)
            speakerName?.let { put("speaker", it) }
        }
    }
}

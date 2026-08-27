package com.dashieapp.Dashie.voice.realtime

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.halite.HaTokenExtractor
import com.dashieapp.Dashie.halite.HalitePreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes the realtime model's function calls on-device (build plan §3.4).
 * Phase 1 keeps tool execution client-side (the model emits `toolCall`, we run
 * it and send `toolResponse` back through the relay); server-side tools are a
 * Phase 2 move.
 *
 *  • home_assistant — PASS-THROUGH to HA's own Assist (conversation API), so HA
 *    does entity resolution + execution. Real device control, validated on-device.
 *  • get_calendar_events — bridges to the on-device JS calendar tool
 *    (window.dashieCalendarTool) via [RealtimeCalendarBridge] — the SAME merged
 *    multi-provider tool the cascade uses. Device-fulfilled (the merged calendar
 *    lives in the WebView). Needs a webViewProvider; without one it returns empty.
 *
 * All calls run on the engine's WS/background thread (so blocking on HTTP / the JS
 * promise is fine).
 */
class RealtimeToolDispatcher(
    private val ctx: Context,
    webViewProvider: () -> WebView? = { null },
) {

    companion object {
        private const val TAG = "RealtimeTools"

        /** Function name the model calls to end the conversation (§3.7). The engine
         *  watches for this and closes the session after replying. */
        const val END_CONVERSATION = "end_conversation"

        /** Tool declarations for the Gemini Live `setup`, GENERATED from the canonical tool
         *  schema (dashieapp_staging/js/ai/tools/tool-schemas.js -> GeneratedToolDeclarations).
         *  Regen: node scripts/generate-realtime-declarations.mjs ; drift gate: npm run lint:tools.
         *  Server tools (sports/image) are declared by the relay via the registry, not here. */
        fun functionDeclarations(): JSONArray = JSONArray(GeneratedToolDeclarations.JSON)

        /** Declarations for THIS device: the generated set minus every capability-gated tool
         *  this device doesn't claim ([clientTools] = DeviceToolCapabilities — the ONE honest
         *  handshake). Gemini is otherwise told a capability exists, calls it, and the turn is
         *  spent apologising (or, pre-2026-07-19, silently 404ing into a kiosk JS bridge).
         *  Only home_assistant/end_conversation are unconditional (always native). */
        fun functionDeclarationsFor(clientTools: List<String>?): JSONArray {
            val all = JSONArray(GeneratedToolDeclarations.JSON)
            if (clientTools == null) return all   // no capability info → declare everything (old behavior)
            val kept = JSONArray()
            for (i in 0 until all.length()) {
                val decl = all.optJSONObject(i) ?: continue
                val cap = CAPABILITY_GATED[decl.optString("name")]
                if (cap != null && cap !in clientTools) continue
                kept.put(decl)
            }
            return kept
        }

        /** Declaration name → DeviceToolCapabilities capability name, for every gated tool.
         *  Tools absent here (home_assistant, end_conversation) are always declared. Keep in
         *  step with the brain's offering drop-list (prompt.ts). */
        private val CAPABILITY_GATED = mapOf(
            "music" to "music",
            "video_feeds" to "video_feeds",
            "open_app" to "open_app",
            "get_calendar_events" to "calendar",
            "manage_calendar_event" to "calendar_write",
            "get_weather" to "weather",
        )
    }

    private val http = OkHttpClient()
    private val calendarBridge = RealtimeCalendarBridge(webViewProvider)

    /** A tool's output: [model] is sent back to the model (the `result` it speaks from);
     *  [card] is an optional UI payload the engine forwards to the screen (onCard). */
    /** [endAfterReply] = this tool call started audible media (music play) or put a feed on
     *  screen — close the Live conversation after the model's confirmation instead of holding
     *  the socket open (parity with cascade's `cascadeEndAfterTts`, John 2026-07-21). Live's
     *  AEC only cancels the model's own TTS, so an open mic would self-hear music on another
     *  speaker; and a shown feed IS the answer, like a cascade screen-answer. Wake word reopens. */
    data class ToolResult(val model: JSONObject, val card: JSONObject? = null, val endAfterReply: Boolean = false)

    fun dispatch(name: String, args: JSONObject): ToolResult = when (name) {
        "home_assistant" -> ToolResult(callHomeAssistant(args.optString("command")))
        // Bridge to the on-device JS tool: hand the model the { found, voice, text }
        // result to speak, and surface the calendar `card` for on-screen rendering.
        "get_calendar_events" -> {
            val out = calendarBridge.query(args)
            ToolResult(out.optJSONObject("result") ?: JSONObject().put("found", false), out.optJSONObject("card"))
        }
        // Calendar WRITE (create; confirm-first — the JS tool owns the pending draft and
        // the which-calendar/confirm questions; the model relays them and calls again
        // with action create/confirm/cancel). result carries {ok, voice, needs_followup}.
        "manage_calendar_event" -> {
            val out = calendarBridge.write(args)
            ToolResult(out.optJSONObject("result") ?: JSONObject().put("ok", false), out.optJSONObject("card"))
        }
        // Device-fulfilled music (reasoning cases only — transport one-shots stay on the
        // fast-path classifier). Fully native: MusicVoiceTool reads the coordinator's
        // now-playing state and calls MA REST for search/play — no WebView hop.
        // Device-fulfilled cameras (conversational asks only — direct "show me the pool
        // camera" is caught by the local classifier and never reaches a model). Fully native
        // since 2026-07-19: VideoFeedVoiceTool — matching lives once in Kotlin, time parsing
        // is pinned to the JS by the timeReferenceOffset golden vectors (no drift risk).
        "video_feeds" -> ToolResult(
            com.dashieapp.Dashie.halite.videofeed.VideoFeedVoiceTool.instance?.execute(args)
                ?: JSONObject().put("found", false).put("voice", "No video feeds are configured"),
            // show / show_all / playback put a feed on screen (the answer) → end after reply;
            // hide / hide_all just clear it → keep talking.
            endAfterReply = args.optString("action").ifEmpty { "show" } in setOf("show", "show_all", "playback")
        )
        "music" -> ToolResult(
            com.dashieapp.Dashie.halite.music.MusicVoiceTool.instance?.execute(args)
                ?: JSONObject().put("found", false).put("error", "music not available on this device"),
            // play/resume/next/… start audible playback → end after reply (shared cascade signal);
            // now_playing / pause / volume are silent → keep the conversation open.
            endAfterReply = com.dashieapp.Dashie.halite.music.MusicVoiceTool.startsAudiblePlayback(args.optString("action"))
        )
        // Device-fulfilled app launcher — "open Netflix / YouTube TV / Spotify". The launch
        // must run on the main thread (AppLaunchController) and is fire-and-forget, so we
        // return an ack the model speaks from rather than a launch result.
        "open_app" -> {
            val app = args.optString("app").trim()
            val controller = com.dashieapp.Dashie.halite.apps.AppLaunchController.instance
            if (controller == null || app.isEmpty()) {
                ToolResult(JSONObject().put("status", "error").put("error", "app launch not available"))
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).post { controller.openApp(app) }
                ToolResult(JSONObject().put("status", "ok").put("opened", app))
            }
        }
        // Device-fulfilled weather — fully native since 2026-07-19: WeatherVoiceTool
        // (VoiceWeatherSnapshot reading, HA↔Open-Meteo per the dashboard toggle, then the
        // vector-pinned WeatherTemplate synthesis). No WebView hop.
        "get_weather" -> {
            val out = com.dashieapp.Dashie.halite.voice.WeatherVoiceTool.query(ctx, args)
            ToolResult(out.optJSONObject("result") ?: JSONObject().put("found", false), out.optJSONObject("card"))
        }
        END_CONVERSATION -> ToolResult(JSONObject().put("status", "ok"))   // engine closes after replying
        else -> {
            Log.w(TAG, "DROP: no client handler for realtime tool '$name' — returning unknown_tool (declaration/dispatch drift?)")
            ToolResult(JSONObject().put("status", "unknown_tool"))
        }
    }

    /** Forward the natural-language command to HA's built-in Assist. HA resolves
     *  the entity + executes and returns a speech reply we hand back to the model. */
    private fun callHomeAssistant(command: String): JSONObject {
        if (command.isBlank()) return JSONObject().put("status", "error").put("message", "empty command")
        val creds = try {
            HaTokenExtractor.getValidCredentialsSync(HalitePreferences(ctx))
        } catch (e: Exception) {
            Log.w(TAG, "HA creds error: ${e.message}"); null
        } ?: return JSONObject().put("status", "ha_not_configured")
            .put("message", "Home Assistant isn't set up on this device")
        val (baseUrl, token) = creds
        return try {
            val body = JSONObject().put("text", command).put("language", "en").toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$baseUrl/api/conversation/process")
                .addHeader("Authorization", "Bearer $token")
                .post(body).build()
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string() ?: ""
                Log.i(TAG, "HA ${resp.code}: ${txt.take(140)}")
                if (!resp.isSuccessful) return JSONObject().put("status", "error").put("code", resp.code)
                val speech = JSONObject(txt).optJSONObject("response")
                    ?.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech")
                JSONObject().put("status", "ok").put("ha_reply", speech ?: "Done")
            }
        } catch (e: Exception) {
            Log.w(TAG, "HA call failed: ${e.message}")
            JSONObject().put("status", "error").put("message", e.message ?: "unknown")
        }
    }
}

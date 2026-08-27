package com.dashieapp.Dashie.halite.voice

import android.util.Log
import com.dashieapp.Dashie.voice.realtime.RealtimeCalendarBridge
import org.json.JSONObject

/** A resolved brain turn: the spoken line + an optional device-fulfilled card. */
/** [failed] = we never got a real answer and [voice] is a graceful apology, not a reply.
 *  Callers that need to react to a failure (a fired scheduled action → error card, not the
 *  success card) must not have to string-match the apology to find out. */
data class BrainSpeak(
    val voice: String,
    val card: JSONObject? = null,
    val failed: Boolean = false,
    /** The spoken line is a QUESTION (calendar-write slot-fill / confirm) — keep
     *  listening for the answer. Dialog re-listens anyway; single promotes into the
     *  follow-up-only dialog (§7-2b, 20260713_VOICE_CALENDAR_CRUD_DESIGN.md). */
    val needsFollowup: Boolean = false,
    /** The ANSWER is on SCREEN (a camera feed / clip), not in the words. Speak the short
     *  confirmation, but don't hold the voice UI open over the thing the user asked to
     *  look at: close as soon as the line finishes (never mid-word — cutting TTS to hide
     *  the overlay would swallow the confirmation). */
    val screenAnswer: Boolean = false,
    /** This turn STARTED AUDIBLE PLAYBACK (music). The mic must not re-arm: the speaker is now
     *  producing sound the STT hears as the next utterance. Unconditional — unlike "keep dialog
     *  open" (which legitimately chains SILENT commands), there is no setting under which
     *  re-listening into the track we just started is correct. Field 2026-07-18. See [ReArmPolicy]. */
    val startedMedia: Boolean = false,
)

/**
 * Runs the brain's device-fulfilled tools (`client_tool`) and turns a brain result into the line
 * to speak (+ any card).
 *
 * Extracted from VoicePipelineCoordinator, built to the same shape as [MultiTurnDispatcher]:
 * injected bridges, no pipeline state, so both are testable and neither needs the coordinator.
 * The single-turn resolver here and the multi-step dispatcher there are the two halves of
 * "the brain asked the device to do something".
 *
 * ⚠️ MUST be called OFF the main thread — it blocks on the WebView bridges. The brain-converse
 * callback runs on OkHttp's thread, so calling it from there is safe.
 *
 * Both engines that own a turn (single `handleBrainConverse` + dialog `cascadeDialogTurn`) go
 * through this, so calendar/weather/music behave identically in both. The JS voice-command-router
 * only runs in single/web mode, NOT dialog, which is what makes this native resolution
 * load-bearing: without it, dialog leaks the raw info_request JSON → "didn't catch that".
 */
class BrainToolResolver(
    /** Native weather executor (WeatherVoiceTool via the coordinator's context) — the
     *  convergence twin of MusicVoiceTool/VideoFeedVoiceTool. */
    private val weatherQuery: (org.json.JSONObject) -> org.json.JSONObject,
    private val calendarBridge: RealtimeCalendarBridge,
    /** Live read — the user can switch brains at runtime; a snapshot would misreport whose
     *  endpoint failed (see the degradation rule below). */
    private val useLocalBrain: () -> Boolean,
) {

    companion object {
        private const val TAG = "VoicePipeline"
        private const val FALLBACK = "Sorry, I couldn't get an answer right now."

        /**
         * No device tool ran — speak the brain's own line, with two guards.
         * In the companion (and instance-state-free) so it is testable without the WebView bridges.
         */
        internal fun plainVoice(v: String?): BrainSpeak {
            // HARD RULE: never speak a raw tool-request / JSON payload (belt-and-suspenders for a
            // brain-side normalizer miss) — ask the user to repeat instead of reading JSON aloud.
            if (!v.isNullOrBlank() && (v.trimStart().startsWith("{") || v.trimStart().startsWith("["))) {
                Log.w(TAG, "Brain voice looked like raw JSON — clarifying instead: ${v.take(60)}")
                return BrainSpeak("Sorry, I didn't quite catch that — could you say it again?")
            }
            // No usable voice → the apology. Flag it: a brain outage degrades to a graceful line and
            // the turn still reports success, so a fired scheduled action would otherwise render this
            // shrug in the normal "answer" card and look like a real reply.
            if (v.isNullOrBlank()) {
                // No silent drops: this is the ONLY place the generic apology is minted for a brain
                // turn — without a marker, a server-side error class is invisible on-device
                // (field 2026-07-28). BrainConverseClient logs the turn's metadata.error just above.
                Log.w(TAG, "DROP: brain turn had no usable voice — speaking generic fallback")
                return BrainSpeak(FALLBACK, failed = true)
            }
            return BrainSpeak(v)
        }
    }

    fun resolve(result: BrainConverseClient.Result?): BrainSpeak {
        // Degradation rule (WS-I.8): when the turn ran on the USER'S OWN brain (BYO key /
        // own model / Hermes) and failed, say whose endpoint failed and why — never a
        // generic shrug that reads as a Dashie outage (and never a silent fallback to the
        // metered cloud brain). Cloud-brain errors keep the generic fallback below.
        val err = result?.errorMessage
        if (!err.isNullOrBlank() && useLocalBrain()) {
            Log.w(TAG, "BYO brain error surfaced to user: ${err.take(200)}")
            return BrainSpeak("There's a problem with your A.I. setup: ${err.take(160)}", failed = true)
        }
        val ct = result?.clientTool
        when (ct?.optString("tool")) {
            "weather" -> {
                // Fully native since 2026-07-19: WeatherVoiceTool (VoiceWeatherSnapshot +
                // vector-pinned WeatherTemplate) — voice matches the dashboard's source.
                val out = weatherQuery(ct.optJSONObject("query") ?: JSONObject())
                val v = out.optJSONObject("result")?.optString("voice")
                if (!v.isNullOrEmpty()) return BrainSpeak(v)
                Log.w(TAG, "weather client_tool returned no voice — using brain voice")
            }
            "calendar" -> {
                val out = calendarBridge.query(ct.optJSONObject("query") ?: JSONObject())
                val v = out.optJSONObject("result")?.optString("voice")?.takeIf { it.isNotEmpty() }
                    ?: "Sorry, I couldn't check the calendar right now."
                return BrainSpeak(v, out.optJSONObject("card"))
            }
            // Cameras (conversational asks; direct commands never reach the brain). The JS
            // tool shows the feed and returns the line to speak; a miss ("no camera called
            // garage") comes back as `voice` too, so the user always hears WHY.
            "video_feeds" -> {
                // Fully native since 2026-07-19: VideoFeedVoiceTool (shared with Live + the
                // local intercept) — the JS glue's dynamic import could never load on kiosk.
                val out = com.dashieapp.Dashie.halite.videofeed.VideoFeedVoiceTool.instance
                    ?.execute(ct.optJSONObject("query") ?: JSONObject()) ?: JSONObject()
                val v = out.optString("voice").takeIf { it.isNotEmpty() }
                    ?: "Sorry, I couldn't get to the cameras right now."
                return BrainSpeak(v, screenAnswer = true)
            }
            // Music: the brain hands back client_tool:'music' ({action, query?, uri?, speaker?}).
            // Executed by the SAME native MusicVoiceTool the local fast-path + Gemini Live use — the
            // JS glue (music-tool.js) can't run here (its dynamic import 404s on the kiosk HA origin,
            // like the entity bridge did), so we call the executor directly and voice the result.
            // Without this the cascade spoke "Playing …" but never played (the bug 2026-07-17).
            "music" -> {
                val tool = com.dashieapp.Dashie.halite.music.MusicVoiceTool.instance
                    ?: return BrainSpeak("Music isn't available on this device.")
                val q = ct.optJSONObject("query") ?: JSONObject()
                val action = q.optString("action", "now_playing")
                val r = runCatching { tool.execute(q) }.getOrNull()
                // Flag a turn that actually STARTED a track so the dialog closes instead of
                // re-listening into it (only when playback really succeeded — a failed play
                // leaves the room silent, so re-arming is still fine there).
                val playing = com.dashieapp.Dashie.halite.music.MusicVoiceTool.startsAudiblePlayback(action) &&
                    r != null && (r.optBoolean("ok") || r.optBoolean("found"))
                return BrainSpeak(com.dashieapp.Dashie.halite.music.MusicVoiceLines.lineFor(action, r), startedMedia = playing)
            }
            // screenAnswer: the ack is one short line and the bell creation card is the visual
            // — speak it and bring the voice UI DOWN instead of holding the overlay open over
            // an "instant" request (2026-07-18).
            "schedule_action" -> return BrainSpeak(
                com.dashieapp.Dashie.halite.schedule.ScheduleActionDirective.handle(
                    ct.optJSONObject("query") ?: JSONObject()),
                screenAnswer = true)
            // Calendar WRITE (create; confirm-first). The JS tool owns the pending draft
            // and every question; needs_followup=true means "this is a question — keep
            // the mic path open for the answer". The brain only emits this client_tool
            // to callers declaring it in client_fulfilled_tools (BrainConverseClient).
            "calendar_write" -> {
                val out = calendarBridge.write(ct.optJSONObject("query") ?: JSONObject())
                val r = out.optJSONObject("result")
                val v = r?.optString("voice")?.takeIf { it.isNotEmpty() }
                    ?: "Sorry, I couldn't make that calendar change."
                return BrainSpeak(v, out.optJSONObject("card"),
                    needsFollowup = r?.optBoolean("needs_followup") == true)
            }
            null, "" -> {}  // no client_tool on this turn — plain voice answer
            else -> Log.w(TAG, "DROP: unhandled brain client_tool '${ct?.optString("tool")}' — device tool NOT executed, falling back to brain voice")
        }
        return plainVoice(result?.voice)
    }
}

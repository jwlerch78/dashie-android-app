package com.dashieapp.Dashie.halite.voice

import android.util.Log
import com.dashieapp.Dashie.halite.music.MusicVoiceTool
import com.dashieapp.Dashie.voice.realtime.HaAssistConverse
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fulfills a `type:"multi"` brain Turn — a compound "do A and B" turn spanning DIFFERENT tools.
 *
 * The brain (voice-conversation) already resolved each leg: an `action` (Home Assistant
 * execute_commands) or a `client_tool` (music / video_feeds), carried on `Turn.steps`. This runs
 * each step on-device with the SAME executors the single-tool path uses (HaAssistConverse,
 * MusicVoiceTool, VideoFeedVoiceTool), preserving order. The coordinator then speaks the ONE
 * covering `voice` — steps carry no per-step voice.
 *
 * Capability-gated upstream: the brain only emits `multi` (and only ever with step tools this
 * device declared in `client_fulfilled_tools`) when the caller advertised `multi`
 * (DeviceToolCapabilities). So a step's tool is always something this device can run — an
 * unfulfillable step arrives as `unsupported_tool` (e.g. an HA step the brain couldn't resolve
 * because we sent no entities), which we log and skip.
 *
 * MUST be called OFF the main thread — every executor blocks (HTTP / WebView bridge). The
 * brain-converse callback runs on OkHttp's thread, so calling it there is safe.
 *
 * The brain commits the covering `voice` BEFORE the steps run, so a step that fails or arrives
 * `unsupported_tool` would make the spoken line over-claim ("…and playing jazz" with no jazz).
 * [reconcileVoice] repairs that: the coordinator passes the brain's line through it with the
 * Summary before speaking. Rare in practice — well-formed compounds resolve every step (see
 * 20260717 build plan).
 */
class MultiTurnDispatcher(
    private val haAssistConverse: HaAssistConverse,
) {
    /** @param executed steps that ran successfully. @param unfulfilled tool names that couldn't run
     *  (unsupported step, or an executor that failed/was unavailable).
     *  @param startedMedia a step STARTED AUDIBLE PLAYBACK — the caller must not re-arm the mic
     *  (it would hear the track). See MusicVoiceTool.startsAudiblePlayback. */
    data class Summary(val executed: Int, val unfulfilled: List<String>, val startedMedia: Boolean = false)

    fun run(steps: JSONArray): Summary {
        var executed = 0
        var startedMedia = false
        val unfulfilled = mutableListOf<String>()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            val tool = step.optString("tool").takeIf { it.isNotBlank() } ?: "?"
            // The brain marks a leg it couldn't resolve (e.g. HA with no entities) — skip; the
            // caller's native fallback handles a single such command, but in a compound we just
            // drop it (running only what resolved is byte-identical to today's per-tool behavior).
            if (!step.isNull("unsupported_tool")) {
                Log.i(TAG, "step $i ($tool) unsupported — skipping")
                unfulfilled += tool
                continue
            }
            val ok = when {
                // HA leg: a resolved execute_commands action — run every command (already
                // multi-device within the one step). Same executor as the single-tool HA path.
                // An ON-direction media_player service ("…and turn on the TV") starts room audio
                // just like a music leg — same mic veto.
                step.optJSONObject("action") != null -> {
                    val done = runHa(step.optJSONObject("action"))
                    if (done?.mediaTargeted == true) startedMedia = true
                    done?.actionDone == true
                }
                // Device-fulfilled leg: music / video_feeds, run by their native executors.
                step.optJSONObject("client_tool") != null -> {
                    val ct = step.optJSONObject("client_tool")
                    val ran = runClientTool(ct)
                    // Track a leg that started a track — the caller closes the dialog instead of
                    // re-listening into it (the multi turn's own music was answering itself).
                    if (ran && ct?.optString("tool") == "music" &&
                        MusicVoiceTool.startsAudiblePlayback(ct.optJSONObject("query")?.optString("action"))) {
                        startedMedia = true
                    }
                    ran
                }
                else -> {
                    Log.w(TAG, "step $i ($tool) has neither action nor client_tool — skipping")
                    false
                }
            }
            if (ok) executed++ else unfulfilled += tool
        }
        Log.i(TAG, "multi: $executed/${steps.length()} steps executed" +
            if (unfulfilled.isEmpty()) "" else " (unfulfilled: ${unfulfilled.joinToString()})" +
            if (startedMedia) " [started playback — mic stays closed]" else "")
        return Summary(executed, unfulfilled, startedMedia)
    }

    private fun runHa(action: JSONObject?): HaAssistConverse.Result? {
        val commands = action?.optJSONObject("parameters")?.optJSONArray("commands")
            ?: action?.optJSONArray("commands")
        if (commands == null || commands.length() == 0) return null
        return runCatching { haAssistConverse.executeCommands(commands) }
            .onFailure { Log.w(TAG, "HA step failed", it) }.getOrNull()
    }

    private fun runClientTool(ct: JSONObject?): Boolean {
        val query = ct?.optJSONObject("query") ?: JSONObject()
        return when (ct?.optString("tool")) {
            "music" -> {
                val tool = MusicVoiceTool.instance ?: run { Log.w(TAG, "music step but no MusicVoiceTool"); return false }
                val r = runCatching { tool.execute(query) }.onFailure { Log.w(TAG, "music step failed", it) }.getOrNull()
                // ok OR found ⇒ the tool acted (play/transport ⇒ ok, reads ⇒ found).
                r != null && (r.optBoolean("ok") || r.optBoolean("found"))
            }
            "video_feeds" -> {
                val tool = com.dashieapp.Dashie.halite.videofeed.VideoFeedVoiceTool.instance
                    ?: run { Log.w(TAG, "video step but no VideoFeedVoiceTool"); return false }
                val r = runCatching { tool.execute(query) }.onFailure { Log.w(TAG, "video step failed", it) }.getOrNull()
                r != null && r.optBoolean("found")
            }
            else -> {
                Log.w(TAG, "client_tool step with unhandled tool '${ct?.optString("tool")}'")
                false
            }
        }
    }

    companion object {
        private const val TAG = "MultiTurnDispatcher"

        /** Spoken names for step tools in the reconciled line. */
        private val TOOL_SPOKEN = mapOf(
            "home_assistant" to "home-control",
            "music" to "music",
            "video_feeds" to "camera",
        )

        /**
         * Repair the brain's pre-committed covering line against what ACTUALLY ran, so the spoken
         * confirmation never claims a step that failed (the per-step over-claim gap, 20260718).
         * All steps ran → the line as-is; some failed → append an honest qualifier; none ran →
         * replace the claim entirely.
         */
        fun reconcileVoice(brainVoice: String?, summary: Summary): String? {
            if (summary.unfulfilled.isEmpty()) return brainVoice
            if (summary.executed == 0) return "Sorry — I couldn't get that done."
            val parts = summary.unfulfilled.map { TOOL_SPOKEN[it] ?: it.replace('_', ' ') }.distinct()
            val qualifier = "Though the ${parts.joinToString(" and ")} " +
                (if (parts.size == 1) "part" else "parts") + " didn't go through."
            val base = brainVoice?.trim()?.takeIf { it.isNotBlank() } ?: "Done."
            return "$base $qualifier"
        }
    }
}

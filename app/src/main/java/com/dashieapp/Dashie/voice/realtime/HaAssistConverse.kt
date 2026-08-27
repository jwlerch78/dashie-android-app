package com.dashieapp.Dashie.voice.realtime

import android.util.Log
import com.dashieapp.Dashie.halite.HaTokenExtractor
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Runs a transcript through HA's own Assist (`POST /api/conversation/process`) — the SAME engine
 * HA's voice assistant uses, so it owns entity matching, areas, aliases and verb→service resolution.
 * The on-device fast-path (20260717_HA_ENTITY_EXPOSURE_CONTRACT §4) calls this AFTER
 * [com.dashieapp.Dashie.halite.voice.HaEntityMatcher] gates the turn as a device command, so the
 * brain (LLM) is skipped entirely — no latency, no cost, no reliance on the brain's HA provisioning.
 *
 * NATIVE REST, not the WebView. An earlier version ran `window.hass.callWS({conversation/process})`
 * via evaluateJavascript, but on a KIOSK the HA frontend's `window.hass` lives in the HA iframe, not
 * the MAIN frame the bridge evaluates — so it returned instantly with no hass and the switch never
 * flipped (2026-07-17: "HA fast-path declined (Assist no-action)"). Calling the REST endpoint
 * directly with the device's HA token (same token + refresh path the brain call and
 * PowerManagementWatchdog use) sidesteps all frame/timing issues. Proven live: "turn the string
 * lights on" → response_type "action_done", switch.string_lights flipped.
 *
 * Called OFF the main thread (the brain pre-flight executor); the HTTP call blocks that thread.
 */
class HaAssistConverse(private val halitePrefs: () -> HalitePreferences) {

    /** @param actionDone HA matched an intent AND executed it (response_type == "action_done").
     *  @param speech HA's spoken confirmation ("Turned on the switch") — what we voice on success.
     *  @param mediaTargeted this result plausibly touched audible playback on a `media_player`, as
     *  far as THIS lane can tell: [process] → a media_player entity is in the success targets
     *  (direction unknown — combine with ReArmPolicy.haCommandStartsMedia's transcript guard);
     *  [executeCommands] → an ON/PLAY-direction media_player service succeeded. Feeds the
     *  startedMedia re-arm veto ("turn on the TV" must not re-open the mic onto the TV). */
    data class Result(val actionDone: Boolean, val speech: String?, val mediaTargeted: Boolean = false)

    // Trusts local/self-signed HA hosts (kiosk HA is often http://<lan-ip>:8123); short timeouts so a
    // dead HA falls back to the brain fast rather than stalling the turn.
    private val httpClient = LocalHostsTrustingHttpClient.builder()
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * Send [transcript] to HA Assist and return its outcome, or null (no HA creds / HTTP error /
     * unparseable). null and a non-actionDone Result both mean "the brain should handle it". Blocks
     * the calling (background) thread.
     */
    fun process(transcript: String): Result? {
        // Valid token + base URL (refreshes a stale token off-main), same as the brain/service calls.
        val creds = HaTokenExtractor.getValidCredentialsSync(halitePrefs())
            ?: run { Log.w(TAG, "process: no HA credentials"); return null }
        val (baseUrl, token) = creds
        val url = "${baseUrl.trimEnd('/')}/api/conversation/process"
        val body = JSONObject().put("text", transcript).put("language", "en").toString()
        return try {
            val req = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON))
                .addHeader("Authorization", "Bearer $token")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "assist: HTTP ${resp.code}"); return null }
                parse(resp.body?.string() ?: return null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "assist: request failed — ${e.message}")
            null
        }
    }

    /** Dispatch the brain's RESOLVED `execute_commands` straight to HA services — this handles
     *  COMPOUND / multi-device commands ("turn on X and dim Y") that HA Assist's single-intent
     *  conversation/process cannot (it returns `error`). Each command is
     *  `{domain, service, data:{entity_id, …}}` → `POST /api/services/{domain}/{service}` with `data`.
     *  Returns actionDone=true if AT LEAST ONE command succeeded. Blocks the calling (bg) thread. */
    fun executeCommands(commands: org.json.JSONArray): Result? {
        if (commands.length() == 0) return null
        val creds = HaTokenExtractor.getValidCredentialsSync(halitePrefs())
            ?: run { Log.w(TAG, "executeCommands: no HA credentials"); return null }
        val (baseUrl, token) = creds
        var ok = 0
        var mediaStarted = false
        for (i in 0 until commands.length()) {
            val c = commands.optJSONObject(i) ?: continue
            val domain = c.optString("domain").takeIf { it.isNotBlank() } ?: continue
            val service = c.optString("service").takeIf { it.isNotBlank() } ?: continue
            val data = c.optJSONObject("data") ?: JSONObject()
            val url = "${baseUrl.trimEnd('/')}/api/services/$domain/$service"
            try {
                val req = Request.Builder().url(url)
                    .post(data.toString().toRequestBody(JSON))
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        ok++
                        if (domain == "media_player" && service in MEDIA_START_SERVICES) mediaStarted = true
                    } else Log.w(TAG, "cmd $domain.$service → HTTP ${resp.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "cmd $domain.$service failed: ${e.message}")
            }
        }
        Log.i(TAG, "executeCommands: $ok/${commands.length()} succeeded" +
            if (mediaStarted) " [media_player started]" else "")
        return if (ok > 0) Result(actionDone = true, speech = null, mediaTargeted = mediaStarted) else null
    }

    /** Parse the conversation/process response: response.response_type + response.speech.plain.speech.
     *  Only "action_done" counts as handled; "error" (no_intent_match) and "query_answer" fall back to
     *  the brain so a state/knowledge question still gets the full LLM answer + card. */
    private fun parse(json: String): Result? {
        val resp = runCatching { JSONObject(json).optJSONObject("response") }.getOrNull() ?: return null
        val type = resp.optString("response_type")
        val speech = resp.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech")
            ?.takeIf { it.isNotBlank() }
        val done = type == "action_done"
        // Assist reports the entities it acted on in data.success — a media_player among them means
        // this command may have started room audio (direction is the caller's transcript guard).
        var media = false
        resp.optJSONObject("data")?.optJSONArray("success")?.let { succ ->
            for (i in 0 until succ.length()) {
                if (succ.optJSONObject(i)?.optString("id")?.startsWith("media_player.") == true) media = true
            }
        }
        Log.i(TAG, "assist: response_type=$type done=$done" + if (media) " media_player targeted" else "")
        return Result(actionDone = done, speech = speech, mediaTargeted = media)
    }

    companion object {
        private const val TAG = "HaAssistConverse"
        private val JSON = "application/json".toMediaType()

        /** media_player services that START audible playback; off/pause/mute/volume are silent. */
        private val MEDIA_START_SERVICES = setOf("turn_on", "media_play", "play_media", "media_play_pause")
    }
}

package com.dashieapp.Dashie.halite.voice

import com.dashieapp.Dashie.edition.ApiPaths

import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Calls the Dashie integration's voice gateway — `POST {haUrl}/api/dashie/voice/converse` —
 * which routes the transcript to the cloud voice-conversation "brain" on the account's behalf
 * and returns a turn.
 *
 * Native (OkHttp), deliberately NOT a WebView fetch: a cross-origin fetch from the kiosk overlay
 * to HA would hit CORS, and native lets us use the HA URL/token already in native preferences.
 *
 * v1: general / web-search queries → spoken answer. HA control through the brain
 * (provided_context.ha_entities + execute_commands dispatch) is a follow-up; clear HA commands
 * stay in the local lane per the routing policy.
 */
/**
 * @param clientToolsProvider what this device can fulfill RIGHT NOW (DeviceToolCapabilities).
 *   A provider, not a snapshot: the user can configure a camera or Music Assistant mid-session,
 *   and a value captured at construction would advertise stale capabilities for the rest of it.
 *   Defaults to the always-safe set (server-fulfillable reads only) so a caller that doesn't
 *   supply one can never over-claim.
 */
class BrainConverseClient(
    private val clientToolsProvider: () -> List<String> = { listOf("calendar", "weather") },
) {

    data class Result(
        val voice: String?,
        val text: String?,
        val action: JSONObject?,
        val card: JSONObject? = null,
        // Device-fulfilled tool the brain handed back (e.g. {tool:'weather', query:{…}}).
        // The caller runs the on-device tool and speaks ITS result instead of `voice`.
        val clientTool: JSONObject? = null,
        // The brain resolved an HA command it couldn't execute (no ha_entities) — e.g. a
        // referential follow-up ("them" → string lights). unsupportedTool='home_assistant' +
        // commandHint = the resolved command → the caller forwards it to its native HA path.
        val unsupportedTool: String? = null,
        val commandHint: String? = null,
        // Conversation id the brain assigns/echoes; send it back on the next turn (with history)
        // so follow-ups keep context. Null on a stateless one-shot.
        val conversationId: String? = null,
        // Resolved TTS voice id for this turn (D3: voice follows personality — the brain maps
        // the device personality → tts_voices.provider_voice_id). Pass straight to
        // ElevenLabsTtsClient.speak(voiceId=…). Null → the client's default voice.
        val voiceId: String? = null,
        // Vendor that owns [voiceId] (the resolved voice's provider): "elevenlabs" | "inworld".
        // Native routes cloud TTS to this vendor; null → the client's default vendor.
        val voiceProvider: String? = null,
        // CR2/CR4 (credit boundary): 'insufficient_credits' on a declined terminal turn;
        // null on a normal turn. The caller shows the prompt-to-choose instead of speaking.
        val degraded: String? = null,
        // metadata.credit snapshot — refreshes CreditStateHolder as a side effect of every
        // turn (CR-b: cached client, authoritative server). All null on old brains.
        val creditSpendable: Boolean? = null,
        val creditLow: Boolean? = null,
        val creditBalance: Double? = null,
        // Unified dialog policy (20260707_DIALOG_POLICY_UNIFICATION): the shared brain DECIDES;
        // the coordinator only RENDERS. `miss` = a no-intent "didn't catch that" turn → show the
        // subtle silent notice, do NOT speak it. `endConversation` = close the dialog (an
        // end-intent like "shut up", or a miss before any successful turn = likely false trigger).
        // Both false on old brains → unchanged behavior. metadata.{miss,end_conversation}.
        val miss: Boolean = false,
        val endConversation: Boolean = false,
        // Calendar-color (20260711): the brain answered DIRECTLY with the pre-fetched
        // calendar window in context → the caller renders the card it already holds.
        // False on old brains / tool-fallback turns → unchanged behavior.
        val calendarContextUsed: Boolean = false,
        // The brain's error detail (metadata.error on a type='error' turn, or the gateway's
        // {message} on an HTTP error like byok_key_missing). Set on BYO-brain failures so the
        // caller can surface WHOSE endpoint failed (degradation rule WS-I.8 — an explicit
        // notice, never a silent fallback). Null on a normal turn.
        val errorMessage: String? = null,
        // Turn type ('response'|'action'|'info_request'|'multi'|'error'). Surfaced so the caller
        // can branch on 'multi' (a compound turn). Null on old brains → single-tool paths unchanged.
        val type: String? = null,
        // Multi-tool turn (type=='multi'): the RESOLVED legs to run on-device, each
        // {tool, action?, client_tool?, unsupported_tool?}. Fulfilled by MultiTurnDispatcher; the
        // single top-level `voice` is the one spoken confirmation. Null on old brains / non-multi
        // turns → additive, ignored by callers that don't consume it (JS↔Kotlin contract #33).
        val steps: org.json.JSONArray? = null,
    )

    private val client: OkHttpClient = LocalHostsTrustingHttpClient.builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    // Local/BYO brain is on the LAN and should answer fast; the 28s read budget above exists for a
    // cloud web-search two-pass. A shorter read timeout for the local route means an unreachable or
    // wedged box FAILS FAST — so the coordinator can fall back to HA's Assist instead of the turn
    // hanging out the whole cloud budget (found on-device 2026-07-20: a GPU box that wasn't
    // connected stalled the turn ~28s → a shrug). Shares the connection pool via newBuilder().
    private val localBrainClient: OkHttpClient = client.newBuilder()
        .readTimeout(LOCAL_BRAIN_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Fire the converse request. [onResult] is invoked on an OkHttp background thread with the
     * parsed turn, or null on any failure (caller should speak a graceful fallback).
     */
    fun converse(
        transcript: String,
        haUrl: String,
        haToken: String,
        endpointId: String,
        useLocalBrain: Boolean = false,
        cloudJwt: String? = null,
        // Multi-turn context (cascade Dialog): prior turns [{role,text}] + the brain's
        // conversation_id, matching the JS brain-client contract. Omitted → stateless one-shot.
        conversationId: String? = null,
        history: org.json.JSONArray? = null,
        // Calendar-color (20260711): the whole provided_context object (today:
        // {calendar:{time_range,events,members}} from the pre-flight). An old brain
        // ignores unknown keys — compat-safe. Omitted → no key in the payload.
        providedContext: JSONObject? = null,
        // True when this turn is a SCHEDULED ACTION FIRING (the device replaying a stored
        // prompt at fire time), not a person speaking. The brain uses it to NOT offer
        // schedule_action on this turn, so a fired action can't re-schedule itself and
        // compound on every fire. An old brain ignores the key — compat-safe.
        announcement: Boolean = false,
        // Live brain progress ("Searching the web…", "Finalizing…") streamed as NDJSON
        // stage events before the final turn (thinking → tool status → finalizing). Fires
        // on an OkHttp background thread — the caller posts to the UI/handler thread. Null →
        // no progress wanted. Silently absent when the response isn't streamed (old
        // integration that doesn't proxy the stream → one JSON turn, no stages).
        onProgress: ((String) -> Unit)? = null,
        onResult: (Result?) -> Unit,
    ) {
        // A LOGGED-IN device (account JWT cached natively, even in kiosk display mode)
        // should call the cloud brain DIRECTLY with its own account — same as full mode's
        // callBrain — not the integration's HA-token gateway, which requires the
        // integration+add-on and is meant for ANONYMOUS kiosk. `useLocalBrain` (the
        // on-prem LAN model) explicitly overrides back to the integration path.
        val useDirectCloud = !cloudJwt.isNullOrBlank() && !useLocalBrain

        val url: String
        val builder = Request.Builder()
        if (useDirectCloud) {
            if (BuildConfig.SUPABASE_URL.isBlank()) {
                Log.w(TAG, "No SUPABASE_URL — cannot reach cloud brain")
                onResult(null)
                return
            }
            url = BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/voice-conversation"
            builder.addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            builder.addHeader("Authorization", "Bearer $cloudJwt")
        } else {
            if (haUrl.isBlank() || haToken.isBlank()) {
                Log.w(TAG, "Missing HA url/token — cannot reach voice gateway")
                onResult(null)
                return
            }
            url = haUrl.trimEnd('/') + "${ApiPaths.HA}/voice/converse"
            builder.addHeader("Authorization", "Bearer $haToken")
        }

        val payload = JSONObject().apply {
            put("text", transcript)
            put("endpoint_id", endpointId)
            // Ask for the NDJSON progress stream (thinking → tool status → finalizing). The
            // brain streams stage events + a final turn; a non-streaming responder (old
            // integration that doesn't proxy) just returns one JSON turn — parseStreamedTurn
            // handles both, so this is always safe to request.
            put("stream", true)
            // Device IANA zone so the brain formats "today" in local time. The edge
            // function runs UTC otherwise, rolling "today" to tomorrow late evening in
            // western zones (10pm Eastern = 2am UTC) — the Mio "wrong day" bug.
            put("timezone", java.util.TimeZone.getDefault().id)
            // Multi-turn context so follow-ups aren't answered cold (the "it didn't know what I
            // was asking about" + tool-search-JSON-on-a-cold-query bug). Matches JS brain-client.
            if (!conversationId.isNullOrBlank()) put("conversation_id", conversationId)
            if (history != null && history.length() > 0) put("history", history)
            if (providedContext != null && providedContext.length() > 0) put("provided_context", providedContext)
            // Fire-time replay → the brain drops schedule_action from the offered tool list
            // (anti-recursion by construction). Only sent when true; old brains ignore it.
            if (announcement) put("announcement", true)
            // Explicit device-tool capability list (WIRE tool names). Presence is the
            // OPT-IN for write-capable tools: the brain returns client_tool:'calendar_write'
            // ONLY to callers that declare it — a client without the native write handler
            // (old APK) never sends the list and gets a spoken decline instead of an
            // unhandled client_tool (the FB13 raw-JSON-leak class). Read tools keep
            // legacy absent-means-fulfilled semantics, so this list must name everything
            // resolveBrainVoice fulfills. Old brains ignore the key.
            // 20260713_VOICE_CALENDAR_CRUD_DESIGN.md §2.4.
            put("client_fulfilled_tools", org.json.JSONArray(clientToolsProvider()))
            // On-prem brain: signal the integration to run the local model on the HA box
            // (add-on /api/voice/converse-local) instead of the cloud edge fn. The brain
            // ignores unknown options, so this is a no-op against an old integration.
            if (useLocalBrain) {
                put("options", JSONObject().put("route", "local"))
            }
        }.toString().toRequestBody(JSON)

        Log.i(TAG, "converse → ${if (useDirectCloud) "cloud(direct account JWT)" else "integration(HA token)"}")
        val request = builder
            .url(url)
            .post(payload)
            .build()

        // Local/BYO route → the short-read-timeout client so an unreachable box fails fast (the
        // coordinator then falls back to HA Assist). Cloud keeps the 28s web-search budget.
        val httpClient = if (useLocalBrain) localBrainClient else client
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "converse failed: ${e.message}")
                // Local-brain unreachable (connect/read/timeout) — record it so a device
                // stranded on a stale brain_route='local' is VISIBLE (heartbeat + diagnostics)
                // instead of failing every turn silently. Cloud failures are NOT recorded here
                // (they're a Dashie-side outage, a different signal). See [BrainRouteHealth].
                if (useLocalBrain) noteLocalBrainFailure("ioexception")
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    // Keystone: the gateway stamps the account's AUTHORITATIVE route on EVERY
                    // converse response (X-Dashie-Brain-Route). Re-cache it here — on the header,
                    // available for both the buffered and NDJSON-stream paths, and on error
                    // responses too — so a stale brain_route can't strand this device past one
                    // turn (no wake word, no TTL, no failure needed). Absent header (old gateway)
                    // → no-op. See BrainRouteApplier / VoicePreferences.applyBrainRoute.
                    BrainRouteApplier.apply(it.header("X-Dashie-Brain-Route"))
                    if (!it.isSuccessful) {
                        val errBody = it.body?.string()
                        Log.w(TAG, "converse HTTP ${it.code}: ${errBody?.take(200)}")
                        // Structured on-prem error (local_brain_unconfigured, byok_key_missing, …)
                        // on the local route = the local brain is present but can't serve → same
                        // stale-route symptom class, same visibility.
                        if (useLocalBrain) noteLocalBrainFailure("http_${it.code}")
                        // A structured gateway/add-on error (e.g. byok_key_missing 503,
                        // local_brain_unconfigured) carries a human `message` — hand it to
                        // the caller for an explicit notice instead of the generic fallback.
                        val msg = try {
                            errBody?.let { b -> JSONObject(b).optString("message", "").takeIf { m -> m.isNotEmpty() } }
                        } catch (_: Exception) { null }
                        onResult(if (msg != null) Result(voice = null, text = null, action = null, errorMessage = msg) else null)
                        return
                    }
                    val source = it.body?.source()
                    if (source == null) {
                        Log.w(TAG, "converse: empty response body")
                        onResult(null)
                        return
                    }
                    // NDJSON stream: one JSON object per line. {kind:'stage',status,…} → live
                    // progress; {kind:'final',turn} → the answer. A non-streaming responder
                    // returns a single bare turn object (no `kind`) — treated as the final turn.
                    var finalTurn: JSONObject? = null
                    try {
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            val obj = try { JSONObject(line) } catch (e: Exception) {
                                Log.w(TAG, "converse: skipping non-JSON stream line"); continue
                            }
                            when (obj.optString("kind", "")) {
                                "stage" -> {
                                    val status = obj.optString("status", "")
                                    if (status.isNotEmpty()) {
                                        try { onProgress?.invoke(status) } catch (_: Exception) { /* UI post race */ }
                                    }
                                }
                                "final" -> finalTurn = obj.optJSONObject("turn")
                                // Bare turn (non-streaming responder) — the whole body is the turn.
                                else -> if (finalTurn == null) finalTurn = obj
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "converse stream read failed: ${e.message}")
                    }
                    if (finalTurn == null) {
                        Log.w(TAG, "converse: no final turn in response")
                        onResult(null)
                        return
                    }
                    // No silent drops: the brain's ERROR turn (type:'error', voice:'') carries the
                    // actual failure in metadata.error / stages[].error — it used to be swallowed
                    // here and spoken as the generic fallback with NOTHING in the log (field
                    // 2026-07-28: hours of forensics to recover a message the device had received).
                    if (finalTurn.optString("type") == "error" || finalTurn.isNull("voice") ||
                        finalTurn.optString("voice", "").isEmpty()) {
                        val metaErr = finalTurn.optJSONObject("metadata")?.optString("error").orEmpty()
                        val stages = finalTurn.optJSONArray("stages")
                        val stageErrs = if (stages != null) (0 until stages.length()).mapNotNull { i ->
                            val st = stages.optJSONObject(i) ?: return@mapNotNull null
                            st.optString("error", "").takeIf { e -> e.isNotEmpty() }?.let { e -> "${st.optString("name")}: $e" }
                        }.joinToString("; ") else ""
                        Log.w(TAG, "DROP: brain returned blank/error turn (type=${finalTurn.optString("type")} " +
                            "route=${finalTurn.optString("route")}) metadata.error='$metaErr' stages=[$stageErrs]")
                    }
                    try {
                        onResult(parseTurn(finalTurn))
                    } catch (e: Exception) {
                        Log.w(TAG, "converse parse failed: ${e.message}")
                        onResult(null)
                    }
                }
            }
        })
    }

    /** Build a [Result] from the brain's terminal turn JSON (streamed `final` or a bare
     *  non-streaming turn). Pure — no I/O — so both response shapes share one parser. */
    private fun parseTurn(turn: JSONObject): Result {
        // org.json coerces a JSON null to the STRING "null" via optString — guard with isNull.
        val vVoice = if (turn.isNull("voice")) null else turn.optString("voice", "").takeIf { v -> v.isNotEmpty() }
        // The brain couldn't execute a resolved HA command (no ha_entities) → forward it.
        // command_hint lives in the raw info_request content (which is the voice field here).
        val vUnsupported = if (turn.isNull("unsupported_tool")) null else turn.optString("unsupported_tool", "").takeIf { it.isNotEmpty() }
        val vHint = if (vUnsupported == "home_assistant" && vVoice != null) {
            try {
                // voice = raw LLM content (may be ```json-fenced) — grab the {…} substring.
                val s = vVoice.indexOf('{'); val e = vVoice.lastIndexOf('}')
                if (s in 0 until e) JSONObject(vVoice.substring(s, e + 1)).optJSONObject("query")?.optString("command_hint")?.takeIf { it.isNotEmpty() } else null
            } catch (_: Exception) { null }
        } else null
        return Result(
            voice = vVoice,
            text = if (turn.isNull("text")) null else turn.optString("text", "").takeIf { v -> v.isNotEmpty() },
            action = turn.optJSONObject("action"),
            // structured_data card (sports/calendar/image) → cascade Dialog renders
            // it inline in the conversation overlay (Engine B). Build plan §2.
            card = turn.optJSONObject("structured_data"),
            // Device-fulfilled tool (weather; calendar handled in JS lane). The
            // caller fetches it on-device + speaks that. Build plan §0.
            clientTool = turn.optJSONObject("client_tool"),
            conversationId = turn.optString("conversation_id", "").takeIf { c -> c.isNotEmpty() },
            // D3: resolved personality voice id → native TTS uses it instead of Rachel.
            voiceId = turn.optString("voice_id", "").takeIf { v -> v.isNotEmpty() },
            // Vendor that owns voiceId → CloudTtsRouter picks the matching client.
            voiceProvider = turn.optString("voice_provider", "").takeIf { v -> v.isNotEmpty() },
            // CR2/CR4 credit signals (nullable/ignorable — §13.16 compat).
            degraded = turn.optJSONObject("metadata")
                ?.optString("degraded", "")?.takeIf { d -> d.isNotEmpty() },
            creditSpendable = turn.optJSONObject("metadata")?.optJSONObject("credit")
                ?.let { c -> if (c.has("spendable")) c.optBoolean("spendable") else null },
            creditLow = turn.optJSONObject("metadata")?.optJSONObject("credit")
                ?.let { c -> if (c.has("low")) c.optBoolean("low") else null },
            creditBalance = turn.optJSONObject("metadata")?.optJSONObject("credit")
                ?.let { c -> if (c.isNull("balance")) null else c.optDouble("balance") },
            unsupportedTool = vUnsupported,
            commandHint = vHint,
            // Unified dialog policy (20260707) — the brain owns these decisions.
            miss = turn.optJSONObject("metadata")?.optBoolean("miss", false) ?: false,
            endConversation = turn.optJSONObject("metadata")?.optBoolean("end_conversation", false) ?: false,
            calendarContextUsed = turn.optJSONObject("metadata")?.optBoolean("calendar_context_used", false) ?: false,
            // Error detail from a failed turn (e.g. the BYO provider's own message,
            // 'Gemini: API key not valid…') — the errorTurn shape carries it in metadata.
            errorMessage = if (turn.optString("type") == "error")
                turn.optJSONObject("metadata")?.optString("error", "")?.takeIf { e -> e.isNotEmpty() } else null,
            // Multi-tool turn: type + the resolved steps to fan out (MultiTurnDispatcher). Both
            // absent on old brains → null → single-tool paths unchanged.
            type = turn.optString("type", "").takeIf { it.isNotEmpty() },
            steps = turn.optJSONArray("steps"),
        )
    }

    /**
     * Fire-and-forget keep-warm ping to the cloud brain so the first converse after an idle
     * gap doesn't stall on a cold Deno-isolate boot. Cheap: the edge fn short-circuits on
     * `?warmup` before auth/LLM. No-op for the local brain (nothing to warm) or with no cloud
     * JWT. The response is discarded — the point is booting the isolate, not the reply.
     */
    private var lastWarmupMs = 0L
    fun warmup(cloudJwt: String?, useLocalBrain: Boolean) {
        if (useLocalBrain || cloudJwt.isNullOrBlank() || BuildConfig.SUPABASE_URL.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastWarmupMs < WARMUP_THROTTLE_MS) return   // rapid wakes / barge-ins don't spam it
        lastWarmupMs = now
        val request = Request.Builder()
            .url(BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/voice-conversation?warmup=1")
            .get()
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer $cloudJwt")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { /* best-effort warm — ignore */ }
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    /**
     * Report the DEVICE-spoken reply of a device-fulfilled turn (calendar/calendar_write/
     * weather/music) onto its ai_interactions row, so the console transcript shows the
     * answer instead of a blank (2026-07-13). Fire-and-forget; server-side consent
     * gate (only rows already carrying prompt_text accept the fill). Cloud-account path
     * only — the HA-token gateway has no database-operations access.
     */
    fun reportDeviceTurnResponse(cloudJwt: String?, sessionId: String?, voice: String?) {
        if (cloudJwt.isNullOrBlank() || sessionId.isNullOrBlank() || voice.isNullOrBlank()) return
        if (BuildConfig.SUPABASE_URL.isBlank()) return
        val payload = JSONObject()
            .put("operation", "report_device_turn_response")
            .put("data", JSONObject().put("session_id", sessionId).put("response_text", voice.take(2000)))
            .toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url(BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/database-operations")
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer $cloudJwt")
            .post(payload)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { Log.d(TAG, "device-turn report failed: ${e.message}") }
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    /** Record a local-brain-unreachable event to the in-memory health sink + the durable
     *  diagnostics log. Both are static (no Context), so this stays callable from the OkHttp
     *  callback without threading prefs through the call site. */
    private fun noteLocalBrainFailure(reason: String) {
        BrainRouteHealth.recordLocalFailure(reason, System.currentTimeMillis())
        com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer.warn(
            "BRAIN", "local brain unreachable ($reason) — cached route may be stale; verify add-on brain_route",
        )
    }

    companion object {
        private const val TAG = "BrainConverseClient"
        private const val CONNECT_TIMEOUT_MS = 5_000L
        // Just under the native voice timeout (VoiceOverlayBridge.RESPONSE_TIMEOUT_MS = 30s)
        // so a genuine no-response surfaces THIS client's graceful fallback rather than
        // the generic native catch-all. One-pass grounded web search is well under this.
        private const val READ_TIMEOUT_MS = 28_000L
        // Local/BYO brain on the LAN — well under the cloud budget so an unreachable box surfaces
        // the HA fallback quickly instead of hanging. Roomy enough for a slow local first token.
        private const val LOCAL_BRAIN_READ_TIMEOUT_MS = 12_000L
        private const val WRITE_TIMEOUT_MS = 10_000L
        private const val WARMUP_THROTTLE_MS = 60_000L
        private val JSON = "application/json".toMediaType()
    }
}

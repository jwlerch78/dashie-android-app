package com.dashieapp.Dashie.halite.voice

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Voice Overlay Bridge
 *
 * Handles communication between Kotlin voice services and the overlay webapp
 * for voice command processing and response handling.
 *
 * Flow:
 * 1. STT provider produces transcript
 * 2. Bridge sends transcript to overlay via postMessage
 * 3. Overlay processes command (local timer or LLM)
 * 4. Overlay sends response back via JS bridge callback
 * 5. Bridge triggers TTS with the response
 *
 * The overlay's `processVoiceCommand()` returns:
 * - success: boolean
 * - voice: string (text to speak)
 * - text: string (optional additional text to display)
 * - action: object (optional action to perform)
 */
class VoiceOverlayBridge(
    private val webViewProvider: () -> WebView
) {
    // Get the current WebView (may be recreated after memory pressure)
    private val webView: WebView
        get() = webViewProvider()

    init {
        // Wave 2: give the stage-timing reporter its JS eval sink (the webapp owns
        // window.dashieVoiceTiming). Provider-based → safe across WebView recreation.
        VoiceStageTiming.evalSink = { js ->
            runCatching {
                val wv = webViewProvider()
                wv.post { wv.evaluateJavascript(js, null) }
            }
        }
    }
    companion object {
        private const val TAG = "VoiceOverlayBridge"
        private const val RESPONSE_TIMEOUT_MS = 30000L  // 30 second timeout for LLM response

        /** Parse a sports score card from a `type=sports` structured payload — shared by
         *  the cascade (structured_data) and conversation mode (relay tool_card), so both
         *  render identical cards. Returns null if absent/malformed. */
        fun parseSportsCard(sd: JSONObject?): SportsCard? {
            if (sd == null || sd.optString("type") != "sports") return null
            return try {
                fun teamOf(o: JSONObject): SportsTeam {
                    val sc = o.opt("score")
                    return SportsTeam(
                        name = o.optString("name", ""),
                        score = if (sc == null || sc == JSONObject.NULL) null else sc.toString(),
                        record = o.optString("record", "").takeIf { it.isNotEmpty() },
                        logo = o.optString("logo", "").takeIf { it.isNotEmpty() },
                        short = o.optString("short", "").takeIf { it.isNotEmpty() },
                        abbr = o.optString("abbr", "").takeIf { it.isNotEmpty() }
                    )
                }
                fun team(side: String): SportsTeam = teamOf(sd.getJSONObject(side))

                // SLATE: a games[] array → tier into inline cards / agenda popup downstream.
                val games = sd.optJSONArray("games")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        try {
                            val g = arr.getJSONObject(i)
                            SportsSlateEntry(
                                state = g.optString("state", ""),
                                detail = g.optString("detail", "").takeIf { it.isNotEmpty() },
                                startTime = g.optString("startTime", "").takeIf { it.isNotEmpty() },
                                home = teamOf(g.getJSONObject("home")),
                                away = teamOf(g.getJSONObject("away")),
                                winner = g.optString("winner", "").takeIf { it.isNotEmpty() && it != "null" }
                            )
                        } catch (e: Exception) { Log.w(TAG, "bad slate game $i", e); null }
                    }
                }?.takeIf { it.isNotEmpty() }
                val scorers = sd.optJSONArray("scorers")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val s = arr.getJSONObject(i)
                        val clocks = s.optJSONArray("clocks")?.let { c ->
                            (0 until c.length()).joinToString(", ") { c.getString(it) }
                        } ?: ""
                        SportsScorer(
                            player = s.optString("player"),
                            side = s.optString("side").takeIf { it.isNotEmpty() },
                            clocks = clocks
                        )
                    }
                }?.takeIf { it.isNotEmpty() }
                val lines = sd.optJSONArray("lines")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val l = arr.getJSONObject(i)
                        SportsStatLine(l.optString("label"), l.optString("home"), l.optString("away"))
                    }
                }?.takeIf { it.isNotEmpty() }
                val highlights = sd.optJSONArray("highlights")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val h = arr.getJSONObject(i)
                        SportsHighlight(h.optString("label"), h.optString("detail"))
                    }.filter { it.detail.isNotEmpty() }
                }?.takeIf { it.isNotEmpty() }
                // A slate card has no top-level home/away (they live in games[]); fall back
                // to an empty team so the single-card fields stay non-null.
                val empty = SportsTeam("", null, null, null)
                SportsCard(
                    state = sd.optString("state", ""),
                    statusLine = sd.optString("detail", "").takeIf { it.isNotEmpty() },
                    venue = sd.optString("venue", "").takeIf { it.isNotEmpty() },
                    home = if (sd.has("home")) team("home") else empty,
                    away = if (sd.has("away")) team("away") else empty,
                    lines = lines,
                    highlights = highlights,
                    scorers = scorers,
                    league = sd.optString("league", "").takeIf { it.isNotEmpty() },
                    games = games
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse sports card", e); null
            }
        }

        /** Parse an image card from a `type=image` structured payload — shared by the
         *  cascade (structured_data) and conversation mode (relay tool_card), so both
         *  render identical cards. Returns null if absent/malformed. */
        fun parseImageCard(sd: JSONObject?): ImageCard? {
            if (sd == null || sd.optString("type") != "image") return null
            return try {
                val url = sd.optString("url", "")
                if (url.isEmpty()) return null
                ImageCard(
                    url = url,
                    thumbnail = sd.optString("thumbnail", "").takeIf { it.isNotEmpty() },
                    description = sd.optString("description", "").takeIf { it.isNotEmpty() },
                    source = sd.optString("source", "").takeIf { it.isNotEmpty() },
                    attribution = sd.optJSONObject("attribution")
                        ?.optString("photographer", "")?.takeIf { it.isNotEmpty() }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse image card", e); null
            }
        }

        /** Parse a device calendar card ({type:'calendar', op:'read', events:[…]}) into
         *  ALL its events. The native side picks the presentation by count (1–3 inline
         *  cards, >3 a central agenda popup). Event shape mirrors the cascade's
         *  calendarEvents (start:{dateTime|date}); `color` is the calendar dot color. */
        fun parseCalendarEvents(card: JSONObject?): List<CalendarEvent> {
            if (card == null || card.optString("type") != "calendar") return emptyList()
            val arr = card.optJSONArray("events") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                try {
                    val ev = arr.getJSONObject(i)
                    val start = ev.optJSONObject("start")
                    CalendarEvent(
                        title = ev.optString("title", "").ifEmpty { ev.optString("summary", "Event") },
                        startDateTime = start?.optString("dateTime")?.takeIf { it.isNotEmpty() },
                        startDate = start?.optString("date")?.takeIf { it.isNotEmpty() },
                        endDateTime = ev.optJSONObject("end")?.optString("dateTime")?.takeIf { it.isNotEmpty() },
                        endDate = ev.optJSONObject("end")?.optString("date")?.takeIf { it.isNotEmpty() },
                        location = ev.optString("location", "").takeIf { it.isNotEmpty() },
                        isAllDay = start?.has("date") == true && !start.has("dateTime"),
                        color = ev.optString("color", "").takeIf { it.isNotEmpty() }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse calendar event $i", e); null
                }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    // Pending callbacks waiting for overlay response
    private val pendingCallbacks = ConcurrentHashMap<String, VoiceResponseCallback>()

    // Request ID counter
    private var requestIdCounter = 0L

    // Callbacks
    var onTtsRequest: ((text: String) -> Unit)? = null

    /**
     * Forward a brain ACTION into the WebView JS so the JS action registry executes it.
     *
     * Why this exists (2026-07-18): in `dialog`/`live` agentMode the NATIVE pipeline owns the
     * turn and dispatches only homeassistant / multi / client_tools. Every OTHER action the
     * brain returned was spoken but never executed, and follow-up turns inside a dialog bypass
     * the JS router entirely. Rather than add a native branch per category (the HA block is
     * already one), hand the action to the single registry that JS, web and desktop all share.
     *
     * Fire-and-forget: native needs the side effect, not a result. `window.dashie` lives in the
     * page in full mode; the kiosk overlay reaches JS via the postMessage `dashie-call` bridge,
     * which whitelists methods and does NOT yet carry this one — kiosk personality/theme
     * switching stays on the JS lane until that branch is added (kiosk-services.js:646).
     */
    fun executeVoiceAction(action: JSONObject) {
        // Owns the forwardable decision so the (size-capped) coordinator stays a one-liner.
        // homeassistant is excluded: converseBrain already executed it natively on-device,
        // and forwarding would run the same commands a second time.
        val category = action.optString("category")
        if (category.isBlank() || category == "homeassistant") return
        Log.i(TAG, "🎬 Brain→JS action: $category/${action.optString("command")}")

        val escaped = action.toString()
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        val wv = webView
        wv.post {
            wv.evaluateJavascript(
                """
                (function() {
                    if (window.dashie && window.dashie.executeVoiceAction) {
                        return window.dashie.executeVoiceAction('$escaped') ? "true" : "false";
                    }
                    return "unavailable";
                })();
                """.trimIndent()
            ) { result ->
                // "unavailable" = older webapp JS without the bridge. Expected during rollout;
                // the action simply isn't executed, exactly as before this change.
                if (result != "\"true\"" && result != "true") {
                    Log.w(TAG, "executeVoiceAction not handled by JS (result=$result)")
                }
            }
        }
    }

    var onActionRequest: ((action: JSONObject) -> Unit)? = null
    var onError: ((error: String) -> Unit)? = null

    /**
     * Callback for voice command response
     */
    interface VoiceResponseCallback {
        fun onResponse(response: VoiceResponse)
        fun onError(error: String)
        fun onTimeout()
    }

    /**
     * Response from overlay's processVoiceCommand
     */
    data class VoiceResponse(
        val success: Boolean,
        val voice: String?,           // Text to speak
        val text: String?,            // Additional display text
        val action: JSONObject?,      // Optional action to perform
        val calendarEvents: List<CalendarEvent>?,  // Calendar events to display as cards
        val sportsCard: SportsCard?,  // Sports score card (structured_data type=sports)
        val imageCard: ImageCard?,    // Image result card (structured_data type=image)
        val cardJson: JSONObject?,    // Raw unified card JSON (type=sports|image|calendar) for
                                      // the conversation surface's conversationCard() dispatch
        val silent: Boolean,          // True for local commands that don't need overlay
        val aiLane: Boolean,          // True = JS handed off the AI lane to the native brain path
        val endDialog: Boolean,       // True = close the voice UI NOW, don't hold the dialog open
                                      //        (camera/clip commands: the overlay would cover the
                                      //         very thing the user asked to look at)
        val localHandler: String?,    // Non-null = the JS lane answered locally (e.g. "time"/"date")
                                      //   and did NOT speak via ElevenLabs. In cloud mode native TTS
                                      //   is skipped assuming JS spoke — so a local answer needs the
                                      //   native side to voice it (else it's silent). See coordinator.
        val error: String?
    )

    /** A sports score card parsed from the brain's structured_data (type=sports). */
    data class SportsCard(
        val state: String,            // "pre" | "in" | "post"
        val statusLine: String?,      // detail/time ("Final · Sat, Jun 27", "Top 7th", "Sun 1:10 PM")
        val venue: String?,           // stadium name ("Gillette Stadium"), shown above the status
        val home: SportsTeam,
        val away: SportsTeam,
        // Generic, sport-agnostic stats (renderer shows what's present):
        val lines: List<SportsStatLine>?,    // paired team numbers (baseball R/H/E, …)
        val highlights: List<SportsHighlight>?,  // labeled contributors (soccer goals, baseball HR/W/L/S)
        val scorers: List<SportsScorer>?,   // legacy soccer scorers — fallback if highlights absent
        // SLATE (multi-game) payload — present only for a list:true answer. When set, the
        // controller tiers by count (1–3 inline compact cards, >3 an agenda popup) instead
        // of showing the single rich card above. Null/absent = the single-game card.
        val league: String? = null,
        val games: List<SportsSlateEntry>? = null
    )

    /** One compact game in a multi-game slate (agenda-style). Lighter than the rich card —
     *  just the matchup, score-or-time, and state. */
    data class SportsSlateEntry(
        val state: String,            // "pre" | "in" | "post"
        val detail: String?,          // "Today, 5:45 PM" · live clock · "Final · Sat, Jun 27"
        val startTime: String?,       // ISO — day-group/sort key
        val home: SportsTeam,
        val away: SportsTeam,
        val winner: String?           // "home" | "away" | null
    )

    data class SportsTeam(
        val name: String,
        val score: String?,           // null/"" for scheduled
        val record: String?,
        val logo: String?,            // crest/flag URL
        val short: String? = null,    // compact display name ("Diamondbacks") — slate rows
        val abbr: String? = null      // ticker code ("ARI") — future compact views
    )

    /** Paired team stat, e.g. R/H/E: label="R", home="4", away="1". */
    data class SportsStatLine(val label: String, val home: String, val away: String)

    /** Labeled contributor line, e.g. label="HR", detail="Judge (2), Soto"; or for soccer
     *  label=team name, detail="Messi (12')". */
    data class SportsHighlight(val label: String, val detail: String)

    data class SportsScorer(
        val player: String,
        val side: String?,            // "home" | "away" | null
        val clocks: String            // "12', 34'" (empty if none)
    )

    /** An image result card parsed from a structured_data / tool_card (type=image). */
    data class ImageCard(
        val url: String,              // full-res (hotlinked) image; renderer loads this
        val thumbnail: String?,       // reliable Google-CDN fallback
        val description: String?,     // caption / title
        val source: String?,          // 'serper'
        val attribution: String?      // display string (photographer/site), or null
    )

    /**
     * Parsed calendar event from AI response for sidebar card display.
     */
    data class CalendarEvent(
        val title: String,
        val startDateTime: String?,   // ISO8601 for timed events
        val startDate: String?,       // YYYY-MM-DD for all-day events
        val endDateTime: String?,
        val endDate: String?,
        val location: String?,
        val isAllDay: Boolean,
        val color: String? = null     // calendar backgroundColor for the agenda dot
    )

    /**
     * Process a voice command through the overlay.
     * Sends the transcript to the overlay's processVoiceCommand and handles the response.
     *
     * @param transcript The STT result to process
     * @param callback Callback for the response
     * @param forceHandler Optional JS handler override (e.g. "homeassistant") — the brain
     *        already routed this command, so the JS router must NOT re-classify it.
     *        Question-phrased HA queries ("is the garage door open") re-classified as
     *        information-query → AI lane → loop guard ate the answer (2026-07-13).
     *        Old JS ignores the extra arg (compat-safe).
     */
    fun processVoiceCommand(transcript: String, callback: VoiceResponseCallback, forceHandler: String? = null) {
        val requestId = "voice_${++requestIdCounter}"

        Log.i(TAG, "Processing voice command: '$transcript' (requestId=$requestId, force=${forceHandler ?: "none"})")

        // Store callback
        pendingCallbacks[requestId] = callback

        // Set timeout
        handler.postDelayed({
            pendingCallbacks.remove(requestId)?.let {
                Log.w(TAG, "Voice command timed out: $requestId")
                it.onTimeout()
            }
        }, RESPONSE_TIMEOUT_MS)

        // Send to overlay via postMessage
        val escapedTranscript = transcript
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        // Handler override rides as a second arg — old JS ignores it (compat-safe).
        val optionsJs = forceHandler?.let { ", {forceHandler: '${it.replace("'", "")}'}" } ?: ""

        webView.post {
            webView.evaluateJavascript("""
                (function() {
                    // Skip inline listener setup - use the single listener from injectVoiceResponseListener()
                    // This avoids double callbacks from duplicate listeners

                    var overlay = document.getElementById('dashie-overlay');
                    if (overlay && overlay.contentWindow) {
                        overlay.contentWindow.postMessage({
                            source: 'dashie-parent',
                            type: 'dashie-call',
                            method: 'processVoiceCommand',
                            args: ['$escapedTranscript'$optionsJs],
                            callbackId: '$requestId'
                        }, '*');
                        return true;
                    } else if (window.dashie && window.dashie.processVoiceCommand) {
                        // Fallback: direct call (standalone mode)
                        window.dashie.processVoiceCommand('$escapedTranscript'$optionsJs).then(function(result) {
                            if (window.DashieNative && window.DashieNative.onVoiceResponse) {
                                window.DashieNative.onVoiceResponse('$requestId', JSON.stringify(result));
                            }
                        }).catch(function(error) {
                            if (window.DashieNative && window.DashieNative.onVoiceError) {
                                window.DashieNative.onVoiceError('$requestId', error.message || 'Unknown error');
                            }
                        });
                        return true;
                    }
                    return false;
                })();
            """.trimIndent()) { result ->
                if (result == "false" || result == "null") {
                    Log.e(TAG, "Failed to send voice command - overlay not ready")
                    pendingCallbacks.remove(requestId)?.onError("Overlay not ready")
                }
            }
        }
    }

    /**
     * Process a voice command and handle TTS automatically.
     * Convenience method that handles the full voice flow.
     *
     * @param transcript The STT result to process
     * @param onComplete Called when processing is complete (after TTS if any)
     */
    fun processVoiceCommandWithTts(
        transcript: String,
        onComplete: ((success: Boolean, response: VoiceResponse?) -> Unit)? = null
    ) {
        // Re-assert the voice-response listener before EVERY command. It's injected at
        // pipeline init, but a page reload (HA-iframe recovery, navigation, kiosk shell
        // reload) silently wipes it — the overlay then answers into the void and every
        // command times out ("Sorry, I couldn't process that"; observed on-device
        // 2026-07-05: iframe logged 'AI lane → native handler' but native never heard).
        // Idempotent via window._dashieVoiceListenerInstalled → no-op when intact.
        injectVoiceResponseListener()

        processVoiceCommand(transcript, object : VoiceResponseCallback {
            override fun onResponse(response: VoiceResponse) {
                if (response.success && !response.voice.isNullOrEmpty()) {
                    // Trigger TTS
                    Log.i(TAG, "Voice response: '${response.voice}'")
                    onTtsRequest?.invoke(response.voice)
                }

                // Handle action if present
                response.action?.let { action ->
                    Log.i(TAG, "Voice action: ${action.optString("category", "unknown")}")
                    onActionRequest?.invoke(action)
                }

                onComplete?.invoke(response.success, response)
            }

            override fun onError(error: String) {
                Log.e(TAG, "Voice command error: $error")
                onError?.invoke(error)
                onComplete?.invoke(false, null)
            }

            override fun onTimeout() {
                Log.w(TAG, "Voice command timeout")
                // Speak timeout message
                onTtsRequest?.invoke("Sorry, I couldn't process that request")
                onComplete?.invoke(false, null)
            }
        })
    }

    // ==================== JavaScript Interface ====================
    // Called from overlay via window.DashieNative

    /**
     * Called from JavaScript when overlay has processed the voice command.
     * This is registered as a @JavascriptInterface on DashieNative.
     *
     * @param requestId The request ID from the original command
     * @param jsonResponse JSON string with the response
     */
    @JavascriptInterface
    fun onVoiceResponse(requestId: String, jsonResponse: String) {
        Log.d(TAG, "🎤 onVoiceResponse called: requestId=$requestId")
        Log.d(TAG, "🎤 jsonResponse: $jsonResponse")

        handler.post {
            Log.d(TAG, "🎤 Handler.post executing for $requestId")

            val callback = pendingCallbacks.remove(requestId)
            if (callback == null) {
                Log.w(TAG, "🎤 No callback found for requestId: $requestId")
                return@post
            }
            Log.d(TAG, "🎤 Callback found for $requestId")

            try {
                Log.d(TAG, "🎤 Parsing JSON...")
                val json = JSONObject(jsonResponse)
                Log.d(TAG, "🎤 JSON parsed successfully")

                Log.d(TAG, "🎤 Creating VoiceResponse...")
                // Parse calendar events if present
                val calendarEvents = json.optJSONArray("calendarEvents")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        try {
                            val ev = arr.getJSONObject(i)
                            val start = ev.optJSONObject("start")
                            CalendarEvent(
                                title = ev.optString("title", "").ifEmpty { ev.optString("summary", "Event") },
                                startDateTime = start?.optString("dateTime")?.takeIf { it.isNotEmpty() },
                                startDate = start?.optString("date")?.takeIf { it.isNotEmpty() },
                                endDateTime = ev.optJSONObject("end")?.optString("dateTime")?.takeIf { it.isNotEmpty() },
                                endDate = ev.optJSONObject("end")?.optString("date")?.takeIf { it.isNotEmpty() },
                                location = ev.optString("location", "").takeIf { it.isNotEmpty() },
                                isAllDay = start?.has("date") == true && !start.has("dateTime")
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse calendar event at index $i", e)
                            null
                        }
                    }
                }

                // Parse a tool card (structured_data) by type — sports or image, if present.
                val structuredData = json.optJSONObject("structured_data")
                val sportsCard = parseSportsCard(structuredData)
                val imageCard = parseImageCard(structuredData)

                // Unified card JSON for the conversation surface (Phase 2, build plan 20260720):
                // structured_data already carries a `type` (sports/image); calendar arrives as a
                // top-level array, wrapped to the {type,events} shape parseCalendarEvents expects.
                // This is what conversationCard(json) dispatches on — the ONE native card renderer.
                val cardJson: JSONObject? = structuredData
                    ?: json.optJSONArray("calendarEvents")
                        ?.let { JSONObject().put("type", "calendar").put("events", it) }

                val response = VoiceResponse(
                    success = json.optBoolean("success", false),
                    voice = json.optString("voice", "").takeIf { it.isNotEmpty() },
                    text = json.optString("text", "").takeIf { it.isNotEmpty() },
                    action = json.optJSONObject("action"),
                    calendarEvents = calendarEvents?.takeIf { it.isNotEmpty() },
                    sportsCard = sportsCard,
                    imageCard = imageCard,
                    cardJson = cardJson,
                    silent = json.optBoolean("silent", false),
                    aiLane = json.optBoolean("ai_lane", false),
                    endDialog = json.optBoolean("end_dialog", false),
                    localHandler = json.optString("localHandler", "").takeIf { it.isNotEmpty() },
                    error = json.optString("error", "").takeIf { it.isNotEmpty() }
                )
                Log.d(TAG, "🎤 VoiceResponse created: success=${response.success}, voice=${response.voice}, calendarEvents=${response.calendarEvents?.size ?: 0}, sportsCard=${response.sportsCard != null}")

                Log.d(TAG, "🎤 Calling callback.onResponse...")
                callback.onResponse(response)
                Log.d(TAG, "🎤 callback.onResponse completed")
            } catch (e: Exception) {
                Log.e(TAG, "🎤 Exception type: ${e.javaClass.simpleName}")
                Log.e(TAG, "🎤 Exception message: ${e.message}")
                Log.e(TAG, "🎤 Exception cause: ${e.cause}")
                e.printStackTrace()
                callback.onError("Failed to parse response: ${e.message}")
            }
        }
    }

    /**
     * Called from JavaScript when voice command processing fails.
     *
     * @param requestId The request ID from the original command
     * @param error Error message
     */
    @JavascriptInterface
    fun onVoiceError(requestId: String, error: String) {
        Log.e(TAG, "Voice error for $requestId: $error")

        handler.post {
            val callback = pendingCallbacks.remove(requestId) ?: run {
                Log.w(TAG, "No callback found for error requestId: $requestId")
                return@post
            }

            callback.onError(error)
        }
    }

    /**
     * Cancel a pending voice command
     *
     * @param requestId The request ID to cancel
     */
    fun cancelPendingCommand(requestId: String) {
        pendingCallbacks.remove(requestId)
    }

    /**
     * Cancel all pending commands
     */
    fun cancelAllPendingCommands() {
        pendingCallbacks.keys.forEach { requestId ->
            pendingCallbacks.remove(requestId)
        }
    }

    /**
     * Clean up resources
     */
    fun release() {
        cancelAllPendingCommands()
        onTtsRequest = null
        onActionRequest = null
        onError = null
    }

    /**
     * Inject the voice response listener into the parent page.
     * This listener catches 'voice-response' postMessages from the overlay iframe
     * and forwards them to DashieNative.
     *
     * Should be called from onPageFinished after the overlay is loaded.
     */
    fun injectVoiceResponseListener() {
        val script = """
            (function() {
                if (window._dashieVoiceListenerInstalled) return;
                window._dashieVoiceListenerInstalled = true;

                window.addEventListener('message', function(event) {
                    if (event.data && event.data.source === 'dashie-overlay') {
                        if (event.data.type === 'voice-response') {
                            console.log('[DashieBridge] Voice response received:', event.data.requestId, JSON.stringify(event.data.result));
                            if (window.DashieNative && window.DashieNative.onVoiceResponse) {
                                window.DashieNative.onVoiceResponse(
                                    event.data.requestId || '',
                                    JSON.stringify(event.data.result || {})
                                );
                            }
                        } else if (event.data.type === 'voice-error') {
                            console.log('[DashieBridge] Voice error received:', event.data.requestId, event.data.error);
                            if (window.DashieNative && window.DashieNative.onVoiceError) {
                                window.DashieNative.onVoiceError(
                                    event.data.requestId || '',
                                    event.data.error || 'Unknown error'
                                );
                            }
                        }
                    }
                });

                console.log('[DashieBridge] Voice response listener installed');
            })();
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(script, null)
        }

        Log.i(TAG, "Injected voice response listener")
    }
}


package com.dashieapp.Dashie.voice.realtime

import android.content.Context
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.HalitePreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Resolved configuration for one realtime conversation session. A pure data
 * holder — the caller resolves the JWT (cached or freshly extracted) and picks
 * the model, then hands a ready [RealtimeConfig] to [ConversationEngine.start].
 *
 * Transport is the conversation-relay edge fn (build plan §3.3): the key stays
 * server-side, the relay meters usage (#6), and the device authenticates with
 * the user's Supabase JWT — never a Gemini key.
 */
data class RealtimeConfig(
    val relayUrl: String,        // wss://<project>.supabase.co/functions/v1/conversation-relay
    val anonKey: String,         // SUPABASE_ANON_KEY — gateway routing
    val jwt: String,             // user Supabase JWT — gateway verify + usage attribution
    val model: String,           // Gemini Live model id
    val voiceName: String = DEFAULT_VOICE,  // Gemini prebuilt voice → speechConfig.prebuiltVoiceConfig
    val sessionId: String,       // groups this conversation's metered turns
    val systemContext: String,   // system-instruction text
    val idleMs: Long = DEFAULT_IDLE_MS,            // silence after a reply → close (§3.7)
    val maxSessionMs: Long = DEFAULT_MAX_SESSION_MS, // hard safety cap
    val initialText: String? = null,               // first turn to send on connect (pre-captured command)
    val timezone: String? = null,                  // IANA tz → relay passes to tools (tz-correct times)
    val location: String? = null,                  // "City, ST"/zip → relay passes to tools
    val retrievePictures: Boolean = true,          // ai.retrievePicturesEnabled — false → relay omits the image_search tool
    // What this device can actually fulfill (DeviceToolCapabilities) → device-only tools it
    // lacks (music with no Music Assistant, video_feeds with no cameras) are NOT declared to
    // the model. null → no gating (declare everything), preserving old behavior for any
    // caller that doesn't supply it.
    val clientTools: List<String>? = null,
    // BYOK-for-Live: a short-lived, Live-only Gemini ephemeral token brokered from the box
    // (minted when the box has a Gemini key). When set, the engine sends it to the relay as the x-dashie-live-token
    // header → the relay opens the BYOK upstream and skips the AI credit debit. null → the relay
    // uses Dashie's key as today (the safe default; old APKs never send it). Minted per session
    // open by VoicePipelineCoordinator; the in-engine 1008 model-retry reuses the same token
    // (Google mints it with uses:2, covering that immediate reconnect within the start window).
    val liveToken: String? = null,
) {
    companion object {
        /** Half-cascade live (native audio in, fast TTS out): cheapest + cleanest.
         *  Real selection comes from voice.conversationModel once plumbed (#4). */
        const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"

        /** Pinned Gemini prebuilt voice when the user hasn't chosen one
         *  (voice.liveVoiceName blank). Must be a member of CONVERSATION_VOICE_IDS
         *  (js/data/settings/voice-ai-value-ids.js) — asserted by lint:voice-options. */
        const val DEFAULT_VOICE = "Aoede"

        /** Screen-less lifecycle defaults (§3.7). After Dashie finishes a reply,
         *  this much continued silence ends the conversation; a hard cap bounds it.
         *  Short by design — a voice exchange should drop quickly if there's no
         *  follow-up (the user can just re-wake). Measured from when Dashie
         *  finishes *talking* (see GeminiLiveEngine.armIdle). */
        const val DEFAULT_IDLE_MS = 8_000L
        const val DEFAULT_MAX_SESSION_MS = 5 * 60_000L

        /** Relay WS URL derived from the flavor's SUPABASE_URL (no separate
         *  buildConfig field needed). Empty for flavors without a backend. */
        fun relayUrl(): String {
            val base = BuildConfig.SUPABASE_URL
            if (base.isBlank()) return ""
            val wss = base.replace("https://", "wss://").replace("http://", "ws://")
            return "$wss/functions/v1/conversation-relay"
        }

        /** Build a config from the JWT cached in ConnectionPreferences (populated
         *  by the WebView earlier). Returns null if no valid token is cached —
         *  the caller should then refresh via SupabaseTokenExtractor (needs the
         *  WebView) or fall back to a minted session token (anon kiosk, below). */
        fun fromCachedJwt(
            ctx: Context,
            model: String = DEFAULT_MODEL,
            sessionId: String = "rt-${System.currentTimeMillis()}",
            systemContext: String? = null,
        ): RealtimeConfig? {
            val prefs = HalitePreferences(ctx).connection
            if (!prefs.hasSupabaseJwt || prefs.isSupabaseJwtExpired) return null
            return fromBearer(ctx, prefs.supabaseJwt, model, sessionId, systemContext)
        }

        /** Build a config from an explicitly provided Bearer credential — the
         *  Live-on-kiosk path (2026-07-09): an anonymous kiosk has no account JWT,
         *  but holds a short-lived `scope:'voice'` session token minted via the
         *  integration's `/api/dashie/voice/session` (SttCredentialProvider caches
         *  + refreshes it). The relay verifies it with the same secret and
         *  attributes usage/credits to the household account (`sub`). Returns
         *  null only when the flavor has no relay URL or the bearer is blank. */
        fun fromBearer(
            ctx: Context,
            bearer: String,
            model: String = DEFAULT_MODEL,
            sessionId: String = "rt-${System.currentTimeMillis()}",
            systemContext: String? = null,
        ): RealtimeConfig? {
            if (bearer.isBlank()) return null
            val url = relayUrl()
            if (url.isBlank()) return null
            val loc = locationLabel(ctx)
            val tz = configuredTimezone(ctx)
            return RealtimeConfig(
                relayUrl = url,
                anonKey = BuildConfig.SUPABASE_ANON_KEY,
                jwt = bearer,
                model = model,
                // Console → user_settings.voice.liveVoiceName → setVoiceSettings → this pref.
                // Blank = the pinned default. Read here so both the warm and cold open paths
                // (VoicePipelineCoordinator) get it without threading a param through.
                voiceName = HalitePreferences(ctx).voice.conversationVoice.ifBlank { DEFAULT_VOICE },
                sessionId = sessionId,
                systemContext = systemContext
                    ?: defaultSystemContext(loc, tz, com.dashieapp.Dashie.halite.voice.DeviceToolCapabilities
                        .clientFulfilledTools(HalitePreferences(ctx))),
                timezone = tz,   // relay → tools (tz-correct times); configured zone preferred
                location = loc,
                // Logged-in → the account-pushed AiPreferences value; anonymous kiosk →
                // the household value cached by the capability probe (VoiceSessionAccess).
                retrievePictures = com.dashieapp.Dashie.halite.voice.VoiceSessionAccess.effectiveRetrievePictures(ctx),
                // Same capability set the cascade sends as client_fulfilled_tools — so Live and
                // cascade agree on what this device can do, and the model is never handed a tool
                // (music with no Music Assistant, cameras with no feeds) it can only fail at.
                clientTools = com.dashieapp.Dashie.halite.voice.DeviceToolCapabilities
                    .clientFulfilledTools(HalitePreferences(ctx)),
            )
        }

        /** User location string ("City, ST" or zip) from prefs, for the prompt. */
        private fun locationLabel(ctx: Context): String? = try {
            val g = com.dashieapp.Dashie.halite.preferences.GeneralPreferences(ctx)
            g.cachedLocationKey.ifBlank { g.zipCode }.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }

        /** Prefer the IANA timezone the webapp resolved from the configured
         *  location (family.timezone, pushed to native via setResolvedTimezone)
         *  over the device default — so a device whose default tz is wrong still
         *  answers times in the user's configured zone. Falls back to the device
         *  default when unset. Note: JWT clock-skew auth still relies on the
         *  device clock; this only affects date/time answers. */
        private fun configuredTimezone(ctx: Context): String = try {
            com.dashieapp.Dashie.halite.preferences.GeneralPreferences(ctx)
                .resolvedTimezone.ifBlank { java.util.TimeZone.getDefault().id }
        } catch (_: Exception) { java.util.TimeZone.getDefault().id }

        /** System prompt with the device's real local time, timezone, and location
         *  so the model answers schedules/times in the user's zone (not a default
         *  like PST). Richer per-family/entity context is a later refinement. */
        fun defaultSystemContext(
            locationLabel: String? = null,
            timezoneId: String = java.util.TimeZone.getDefault().id,
            // The SAME capability handshake the declarations use (DeviceToolCapabilities).
            // The prose capability bullets below MUST follow it: teaching "CALL
            // get_calendar_events" while the function isn't DECLARED makes Gemini call an
            // undeclared tool and the Live API closes the session with 1008 "Requested
            // entity was not found" — a silent death, found live 2026-07-20 (kiosk calendar
            // ask in live mode). Null → all bullets (old callers/tests unchanged).
            clientTools: List<String>? = null,
        ): String {
            fun has(cap: String) = clientTools == null || cap in clientTools
            val tz = java.util.TimeZone.getTimeZone(timezoneId)
            // No 'z' (tz abbreviation) — times are already local; "EDT" just reads
            // awkwardly aloud. The zone is still given to the model via $whereLine.
            val now = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault())
                .apply { timeZone = tz }.format(Date())
            val whereLine = locationLabel
                ?.let { "The user is located in $it (timezone ${tz.id})." }
                ?: "The user's timezone is ${tz.id}."
            // Capability-gated prose (must track the DECLARATIONS — see clientTools KDoc).
            val calendarBullet = if (has("calendar")) {
"""
                - To answer about the schedule, CALL get_calendar_events. The result lists the
                  day's events, each with its time, title, and an `assigned_to` (the family
                  member(s) whose calendar it's on). To decide WHOSE event it is — "mom's
                  appointment", "dad's meeting" — use `assigned_to`, NOT the title: an event
                  titled "Dwight Physical Therapy" on Mom's calendar IS Mom's PT. For a SPECIFIC
                  question ("what time is mom's PT", "when's the dentist"), find the matching
                  event (match by meaning — PT = physical therapy) and answer with the time. For
                  a GENERAL "what's on" question, the events are shown on a card/agenda on
                  screen, so give a brief one-line summary — do NOT read them all aloud."""
            } else {
                """                - This device cannot check the family calendar. If asked about the calendar
                  or someone's schedule, say briefly that you can't check the calendar on this
                  device — do NOT call any other tool for it (no schedule/reminder tools, no
                  Google Search for their personal calendar)."""
            }
            val familyVsProBullet = if (has("calendar")) {
"""
                - Family vs pro games: a bare "the game / the match / the soccer game /
                  baseball game" with NO professional team or league named is usually a FAMILY
                  event (a kid's game, practice, or meet) — CALL get_calendar_events FIRST. If
                  it returns a matching event, answer from the calendar (you may add a short
                  "…want pro scores instead?"). Only if the calendar has no such event, fall
                  back to get_sports_scores. A NAMED pro team/league ("did Arsenal win",
                  "Yankees score") or an explicit result/score question goes straight to
                  get_sports_scores."""
            } else {
                """                - A bare "the game / the match" with no pro team named may be a family event
                  you cannot see (no calendar here) — say so briefly; use get_sports_scores only
                  when a pro team, league, or score is the subject."""
            }
            return """
                You are Dashie, a warm, concise home voice assistant. Keep replies short and natural.
                Dashie — your product — is a smart home dashboard for families: a customizable
                widget dashboard (calendar, photos, weather, clock, chores and rewards), a photo
                screensaver, timers, "Hey Dashie" voice, and Home Assistant smart-home control,
                on wall-mounted tablets, TVs, and web browsers. If asked who or what you are, or
                about Dashie in general ("tell me about yourself", "what is Dashie", "what can
                you do"), answer from this description in one or two friendly sentences. You are
                Dashie: NEVER say you are a large language model and NEVER name an underlying AI
                model or provider (no "Google", "Gemini", etc.).
                Answer the user's request directly, then STOP. Do NOT ask a follow-up question
                or try to keep the conversation going — only ask something back if you genuinely
                need to clarify what the user asked. Don't end replies with "anything else?" etc.
                Answer each request EXACTLY ONCE: if you call a tool, wait for its result and then
                give a single short reply. Do NOT narrate that you're looking something up, do NOT
                answer before the tool returns, and never repeat the same answer twice.
                Right now it is $now. $whereLine
                Give dates and times in the user's local time, but do NOT state or append the
                timezone name — no "Eastern", "ET", "EDT", "Pacific", etc. Just say the time,
                e.g. "1 PM" (not "1 PM Eastern"). Convert any source times to the user's zone
                silently.
                Capabilities:
                - To control or query the smart home, CALL home_assistant with the user's
                  natural-language request (don't just say you did it).
$calendarBullet
                - For weather, CALL get_weather. Pass a `timeframe` that matches what they
                  asked ("right now" → current, "this weekend" → weekend, "will it rain
                  today" → today, a named day → that weekday). The result gives current
                  conditions plus a daily forecast; answer briefly in a natural sentence.
$familyVsProBullet
                - Sports: whenever a NAMED pro team/league or a score/result is the subject — a
                  LIVE score, a finished result, an upcoming/today's game, OR even a detail
                  about a game (who's in goal, the starting lineup, an injury) — CALL
                  get_sports_scores for one of the teams. Calling it IS how you put the scorecard on screen, so call it even if
                  you also need Google Search for a detail it doesn't carry. If the user names a
                  matchup ("first game today is Brazil vs Japan"), call it for one of those teams
                  so that game's card shows. Set `when` from intent: "recent" ONLY for an
                  explicitly finished game (did they win / final score / yesterday / last game);
                  "live" for a game in progress now; "upcoming" for today's game, the next game,
                  lineups, or "who's playing / who's starting". If the tense is unclear, prefer
                  "upcoming" — do NOT use "recent" just because no tense is stated. After it
                  returns: if found:true, give the score/result from the card (don't re-answer
                  that from memory) and use Google Search ONLY for an extra detail the card
                  lacks; if found:false, SILENTLY use Google Search. Only skip the tool for a
                  game more than about a week out. Answer exactly once.
                - Multiple games (a SLATE): whenever the user asks for MULTIPLE games — a
                  league's day ("what games are on today/tonight", "today's World Cup games"),
                  a FUTURE set ("the NEXT games", "what games are on this week", "upcoming World
                  Cup games"), OR one team's set ("list Brazil's games", "all the Yankees
                  games") — call get_sports_scores with list:true (omit `team` for the whole
                  league, pass `team` for the one-team case) and set `when`: "upcoming" for
                  next/this-week/future asks, "recent" for past results. Pass `date` (YYYY-MM-DD)
                  only for a specific day. ALWAYS call the tool for ANY multi-game ask — a future
                  schedule is NOT an exception. NEVER list games from memory or from Google
                  Search: calling the tool is the ONLY way the slate card appears on screen, and
                  even a future World Cup schedule you think you know MUST come from the tool.
                  Keep the spoken reply brief but informative: the count PLUS one or two of the
                  games — NEVER the count alone. For one team's slate, also name the team's next
                  opponent and its day/time. Every opponent, day, and score you speak MUST come
                  from the tool result you just received — read them from it; never from memory.
                  If a game is LIVE/in progress, lead with it and its score.
                - For DETAILED questions about Dashie's settings, how-to steps, or
                  troubleshooting ("how do I add a calendar", "where do I change the theme",
                  "why is my screen black") — CALL dashie_help and answer only from what it
                  returns. Who/what-are-you and general "about Dashie" questions need NO tool —
                  answer from your identity above. NEVER use Google Search for Dashie product
                  questions and never guess about settings locations or prices; if dashie_help
                  returns found:false, say you're not sure and suggest emailing
                  support@dashieapp.com (that exact address).
                - Music: CALL music for anything about music. "What song is this / who sings
                  this" → action "now_playing". Finding music ("play the acoustic version of
                  X", "play some jazz in the kitchen") → action "search" with a query (if
                  several fit, ask the user which one BY VOICE), then action "play" with the
                  chosen uri (or a query) and an optional speaker name. TRANSPORT: "stop the
                  music" → action "stop"; "pause" → "pause"; "turn it up / louder" →
                  "volume_up"; "turn it down / quieter" → "volume_down"; "next / skip this
                  song" → "next"; "play / resume" with music paused and no song named →
                  "resume". NEVER use "search" for a transport phrase, and confirm transport
                  in a word or two ("Done.") — no narration.
                - To open a whole app on the screen ("open Netflix", "put on YouTube TV",
                  "launch Spotify", "go to Prime Video"), CALL open_app with the app name the
                  user said. Confirm in a word or two ("Opening Netflix"); the app appears on
                  screen, so don't describe it. Use open_app ONLY for launching an app — not for
                  playing a specific song (use music) or showing cameras (use video_feeds).
                - For other current/factual info (news, weather, prices), use Google Search.
                - To show a photo, CALL show_image with concise search terms — BOTH when the
                  user asks to see something ("show me…") AND proactively when a picture would
                  enrich your answer (a place, landmark, animal, team, food, artwork). Speak a
                  short caption; the photo appears on screen. Skip it for non-visual topics.
                  Do NOT re-call show_image for a subject you already pictured this conversation
                  — the photo is on screen, and each call runs a fresh search and stacks a
                  duplicate. Call again only for a NEW subject or when asked to show it again.
                - When the user is clearly done ("that's all", "thanks, goodbye", "never mind"),
                  CALL end_conversation to close — don't keep the line open.
                Never guess at live information or pretend to take an action — call the tool.
            """.trimIndent()
        }
    }
}

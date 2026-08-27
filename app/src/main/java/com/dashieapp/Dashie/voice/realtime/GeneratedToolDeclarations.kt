// AUTO-GENERATED — DO NOT EDIT BY HAND.
// Source of truth: dashieapp_staging/js/ai/tools/tool-schemas.js
// Regenerate:  node scripts/generate-realtime-declarations.mjs   (from dashieapp_staging)
// Drift gate:  npm run lint:tools   (fails if this file is stale)
package com.dashieapp.Dashie.voice.realtime

/**
 * Gemini-Live function declarations for the ON-DEVICE tools, rendered from the canonical voice
 * tool schema. [RealtimeToolDispatcher.functionDeclarations] parses [JSON]. Server tools
 * (sports/image) are declared by the relay via the registry, not here.
 */
object GeneratedToolDeclarations {
    const val JSON = """
[
  {
    "name": "get_calendar_events",
    "description": "Get the family's REAL calendar events. Use for any question about what's on the calendar, schedules, or appointments. Also covers family sporting events (a kid's game, practice, meet). Use this for \"the game\" / \"the soccer game\" when no professional team is named.",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "time_range": {
          "type": "STRING",
          "description": "today | tomorrow | this_week | next_week | weekend | next_weekend | next_30_days | next_60_days | next_12_months | date_range, OR a weekday name (monday, tuesday, … sunday) for that specific upcoming day. Use 'weekend' for 'this weekend', 'next_weekend' for 'next weekend', and the weekday for 'what's on Wednesday'. For a NAMED month or an explicit period (\"in December\", \"the first week of March\") use 'date_range' and set start_date + end_date. For \"when is X\" with NO period named, use 'next_12_months' with query (+ mode 'next') so the search covers the whole year."
        },
        "start_date": {
          "type": "STRING",
          "description": "YYYY-MM-DD start of an explicit period — REQUIRED with time_range 'date_range'. A named month means its NEXT occurrence."
        },
        "end_date": {
          "type": "STRING",
          "description": "YYYY-MM-DD end of the explicit period — REQUIRED with time_range 'date_range'."
        },
        "member_name": {
          "type": "STRING",
          "description": "a specific family member's name, if the question is about one person"
        },
        "query": {
          "type": "STRING",
          "description": "a keyword to find ONE specific event by name, e.g. \"physical therapy\", \"dentist\", \"soccer\". Use this for \"what time is X\" / \"when is X\" questions."
        },
        "mode": {
          "type": "STRING",
          "description": "\"next\" = a SINGLE upcoming event — set this whenever the user says \"next\" (even with a named event, e.g. \"the next concert\"); combine with query to pick the soonest matching one. \"list\" (default) = an overview."
        }
      },
      "required": [
        "time_range"
      ]
    }
  },
  {
    "name": "manage_calendar_event",
    "description": "Add, change, or remove events on the family's REAL calendar (\"add/schedule X\", \"move/reschedule/rename X\", \"cancel/delete X\" — cancelling an appointment is a delete). Call IMMEDIATELY with whatever the user said — every field optional; the device walks them through anything missing and always asks them to confirm before writing. Relay its questions, then call again: re-send the SAME action with newly answered fields — but after the CONFIRMATION question, any yes ('yes', 'do it', 'delete it') = action 'confirm' and no = 'cancel'; NEVER re-send create/update/delete there unless the user changed a detail. Not for reading the calendar or for reminders/timers.",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "action": {
          "type": "STRING",
          "description": "create | update | delete | confirm | cancel — 'create' adds (or carries an answered field into the pending event); 'update' changes an existing event; 'delete' removes one; 'confirm' when the user approves the confirmation question; 'cancel' when they decline."
        },
        "title": {
          "type": "STRING",
          "description": "the event title (create), or the NEW title (update/rename)"
        },
        "date": {
          "type": "STRING",
          "description": "YYYY-MM-DD — ABSOLUTE date resolved from the current date (never guess). On update: the NEW day."
        },
        "start_time": {
          "type": "STRING",
          "description": "HH:MM 24-hour start time (new value on update)"
        },
        "end_time": {
          "type": "STRING",
          "description": "HH:MM 24-hour end time — omit unless stated (defaults to one hour / the event's current length). For a SPORTING EVENT (game/match/fixture) set it to TWO hours after start_time — games run long."
        },
        "all_day": {
          "type": "BOOLEAN",
          "description": "true for an all-day event"
        },
        "location": {
          "type": "STRING",
          "description": "a place, if given"
        },
        "description": {
          "type": "STRING",
          "description": "extra details, if given"
        },
        "calendar_name": {
          "type": "STRING",
          "description": "which calendar — when the user names one or answers the which-calendar question"
        },
        "calendar_names": {
          "type": "ARRAY",
          "description": "when the user names MORE THAN ONE calendar (\"add it to Dad and Mom's calendars\")",
          "items": {
            "type": "STRING"
          }
        },
        "match_query": {
          "type": "STRING",
          "description": "update/delete: words identifying the EXISTING event, e.g. \"dentist\""
        },
        "match_date": {
          "type": "STRING",
          "description": "update/delete: YYYY-MM-DD the existing event is on, if the user named its day"
        },
        "scope": {
          "type": "STRING",
          "description": "\"all\" ONLY when the user says the whole series / all of them; omit otherwise (just that one occurrence)"
        }
      },
      "required": [
        "action"
      ]
    }
  },
  {
    "name": "get_weather",
    "description": "Get current conditions or the forecast for the family's location. Use for any weather question.",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "timeframe": {
          "type": "STRING",
          "description": "current | today | tonight | tomorrow | weekend | this_week | precip_timing, OR a weekday name (monday … sunday). Use what the user asked — 'right now' → current, 'this weekend' → weekend, 'will it rain today' → today, 'tomorrow' → tomorrow, 'when will the rain stop'/'when does it start raining' → precip_timing."
        },
        "location": {
          "type": "STRING",
          "description": "a city or place, ONLY if the user names one; omit for the family's home location"
        }
      },
      "required": []
    }
  },
  {
    "name": "home_assistant",
    "description": "Control or query the Home Assistant smart home. Pass the user's natural-language request, e.g. 'turn on the kitchen lights' or 'is the garage door open?'.",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "command": {
          "type": "STRING",
          "description": "the natural-language smart-home request"
        }
      },
      "required": [
        "command"
      ]
    }
  },
  {
    "name": "music",
    "description": "All music control and questions. \"what song is this\" / \"who sings this\" → action now_playing; find music → action search; play a result → action play; and TRANSPORT: \"stop the music\" → stop, \"pause\" → pause, \"turn it up / louder\" → volume_up, \"turn it down\" → volume_down, \"next / skip\" → next, \"play / resume\" (with music paused, no song named) → resume. NEVER use search for a transport phrase. Confirm transport in a word or two.",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "action": {
          "type": "STRING",
          "description": "now_playing | search | play | pause | resume | stop | next | previous | volume_up | volume_down — now_playing reads the current track; search finds matches for a query; play starts a uri (from a search result) or a query; the rest are transport (\"stop the music\" → stop, \"turn it up\" → volume_up)."
        },
        "query": {
          "type": "STRING",
          "description": "song/artist/album/playlist search text, e.g. \"acoustic version of Hallelujah\" or \"songs by Bob Marley\". Used by search and play."
        },
        "uri": {
          "type": "STRING",
          "description": "the exact uri of a chosen search result (e.g. \"library://track/42\"). Prefer this with play after a search — it plays exactly what the user picked."
        },
        "speaker": {
          "type": "STRING",
          "description": "a speaker/room name, ONLY if the user names one (\"in the kitchen\") — omit otherwise to use the current player."
        }
      },
      "required": [
        "action"
      ]
    }
  },
  {
    "name": "video_feeds",
    "description": "Show, hide, and play back the home's cameras. \"show me the pool camera\" → action show; \"hide it\" → hide; \"show all the cameras\" → show_all. For anything about a PAST moment — \"what happened at the front door around 3pm\", \"show me the pool camera from ten minutes ago\" — use action playback with the time the user said. Confirm in a word or two; the feed appears on screen, so don't describe it.",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "action": {
          "type": "STRING",
          "description": "show | hide | show_all | hide_all | playback — show/hide one camera (live), show_all/hide_all for every camera, playback for recorded footage at a past time."
        },
        "camera": {
          "type": "STRING",
          "description": "the camera name as the user said it (\"pool\", \"front door\", \"family room\"). The device fuzzy-matches it against the configured cameras. Omit for show_all/hide_all."
        },
        "time": {
          "type": "STRING",
          "description": "playback ONLY — the user's own words for when (\"10 minutes ago\", \"at 10:30pm\", \"last night\", \"this morning\"). Pass the phrase through; do NOT convert it to a timestamp — the device resolves it in its own timezone, which is the reliable one."
        }
      },
      "required": [
        "action"
      ]
    }
  },
  {
    "name": "open_app",
    "description": "Open/launch a whole app on the screen by name — \"open Netflix\", \"put on YouTube TV\", \"launch Spotify\". The device matches the spoken name against installed apps and switches to it. Confirm in a word or two (\"Opening Netflix\"); don't describe the app. Use only for launching an app, not for playing a specific song (use music) or cameras (use video_feeds).",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "app": {
          "type": "STRING",
          "description": "the app name as the user said it (\"Netflix\", \"YouTube TV\", \"Prime Video\", \"Spotify\", \"Disney Plus\", \"Hulu\"). The device fuzzy-matches it against installed apps."
        }
      },
      "required": [
        "app"
      ]
    }
  },
  {
    "name": "end_conversation",
    "description": "End the conversation when the user is finished (e.g. 'that's all', 'goodbye', 'never mind').",
    "parameters": {
      "type": "OBJECT",
      "properties": {},
      "required": []
    }
  }
]
"""
}

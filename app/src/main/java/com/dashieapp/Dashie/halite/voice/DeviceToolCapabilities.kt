package com.dashieapp.Dashie.halite.voice

import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.music.MusicVoiceTool

/**
 * What device tools THIS device can actually fulfill right now → `client_fulfilled_tools`.
 *
 * The brain drops device-only tools (music, video_feeds) from the prompt when we don't claim
 * them, so the model is never told about a capability this device lacks. That matters: an
 * ungated tool gets CALLED — the model is told music exists, calls it, MusicVoiceTool.instance
 * is null, and the turn (plus the credits) is spent apologising. Same for cameras on a device
 * with no feeds configured.
 *
 * HONESTY RULE (2026-07-19, the KNOWN-GAP fix): claim a tool ONLY when the executor on THIS
 * device in THIS mode can actually run. calendar/weather/calendar_write execute via the JS-lane
 * bridges, which load only in FULL mode (webapp origin) — on kiosk their dynamic import 404s,
 * so claiming them made the brain hand back client_tools the device could only drop silently.
 * Unclaimed weather → the brain self-fulfills server-side (kiosk weather WORKS via the server);
 * unclaimed calendar → the brain doesn't offer the tool, so the model declines in-language.
 * `calendar_write` / `schedule_action` stay explicit opt-ins for write + native-directive tools
 * (a caller without the handler must never receive the client_tool — the FB13 raw-JSON-leak
 * class). Every channel derives from THIS list (cascade client_fulfilled_tools, Live
 * declarations via RealtimeToolDispatcher.functionDeclarationsFor, the brain's offering
 * drop-list) — one handshake, no per-channel gates.
 */
object DeviceToolCapabilities {

    fun clientFulfilledTools(prefs: HalitePreferences): List<String> {
        // Native executors — run in BOTH modes (ScheduledActionExecutor).
        val tools = mutableListOf("schedule_action")

        // Weather: fully NATIVE since 2026-07-19 (WeatherVoiceTool) — mode-blind, and voice
        // matches the dashboard's HA↔Open-Meteo source in both modes.
        tools += "weather"

        // Calendar: the executor is the JS-lane bridge, FULL mode only (decided end-state —
        // calendar option C, 2026-07-19: the merged multi-provider calendar lives in the
        // WebView; kiosk gets the brain's honest in-language decline). On kiosk the import
        // would 404, so the claim would be a lie.
        val fullMode = prefs.account.showsDashboard
        if (fullMode) {
            tools += listOf("calendar", "calendar_write")
        }

        // Music: the feature has to be on AND the executor wired (MA configured). Either
        // missing ⇒ MusicVoiceTool would return not-available, so don't advertise it.
        // (Native executor — both modes.)
        if (prefs.connection.musicPlayerEnabled && MusicVoiceTool.instance != null) {
            tools += "music"
        }

        // Video feeds: feature on AND ≥1 camera. MODE-BLIND again since 2026-07-19 — the
        // executor is the native VideoFeedVoiceTool (shared by cascade + Live + the local
        // intercept), so the claim is honest on kiosk too. First tool through the
        // native-executor convergence; weather/calendar follow.
        if (prefs.videoFeed.enabled && prefs.videoFeed.getEnabledRules().isNotEmpty()) {
            tools += "video_feeds"
        }

        // App launcher: any Android device can launch an installed app once the
        // controller is wired (web/HTTP callers have no controller, so they don't claim it).
        if (com.dashieapp.Dashie.halite.apps.AppLaunchController.instance != null) {
            tools += "open_app"
        }

        // Multi-tool turns: this build can fulfill a compound {type:'multi', steps:[…]} turn
        // (MultiTurnDispatcher runs each step). Declared unconditionally — the brain only emits
        // multi STEPS for tools already in this list (music/video_feeds are gated above; HA is
        // always fulfillable), so claiming `multi` never yields a step we can't run. Old APKs
        // don't send it → the brain never emits multi → unchanged. JS↔Kotlin contract #33.
        tools += "multi"

        return tools
    }
}

package com.dashieapp.Dashie.halite.music

import org.json.JSONObject

/**
 * Spoken lines for a music turn, rendered from a [MusicVoiceTool] result.
 *
 * Presentation only — the tool already did the playback; this just says what happened. Lives in
 * the music package (not the voice coordinator) because it's music-domain wording with no
 * pipeline state: pure (action, result) → String, so it's unit-testable and reusable by any lane.
 *
 * ⚠️ Mirrors js/core/voice/music-tool.js `synthesize()` — keep the two in sync. The kiosk can't
 * reach that JS (its dynamic import 404s on the HA origin), which is why this Kotlin copy exists.
 */
object MusicVoiceLines {

    fun lineFor(action: String, r: JSONObject?): String {
        if (r == null) return "Music control isn't available on this device."
        val ok = r.optBoolean("ok") || r.optBoolean("found")
        return when (action) {
            "now_playing" -> if (r.optBoolean("found")) {
                val artist = r.optString("artist").takeIf { it.isNotBlank() }
                "That's ${r.optString("track")}${if (artist != null) " by $artist" else ""}."
            } else "Nothing is playing right now."
            "play" -> if (ok) {
                val what = r.optString("playing").takeIf { it.isNotBlank() && !it.contains("://") }
                "Playing${if (what != null) " $what" else ""}."
            } else "Sorry — I couldn't play that."
            "search" -> {
                val results = r.optJSONArray("results")
                if (r.optBoolean("found") && results != null && results.length() > 0)
                    "I found ${results.optJSONObject(0)?.optString("name") ?: "something"}. Want me to play it?"
                else "I couldn't find that in your music library."
            }
            "pause", "stop", "next", "previous", "resume" ->
                if (ok) "Done." else "Sorry — I couldn't control the music."
            "volume_up", "volume_down" -> if (ok) {
                (r.opt("volume_percent") as? Int)?.let { "Volume's at $it percent." } ?: "Done."
            } else "Sorry — I couldn't change the volume."
            else -> if (ok) "Done." else "I didn't understand that music request."
        }
    }
}

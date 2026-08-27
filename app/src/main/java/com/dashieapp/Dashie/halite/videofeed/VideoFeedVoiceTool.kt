package com.dashieapp.Dashie.halite.videofeed

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.halite.voice.KotlinIntentPatterns
import com.dashieapp.Dashie.halite.voice.TimeReference
import org.json.JSONObject

/**
 * The ONE native executor for the `video_feeds` voice tool — cascade (BrainToolResolver),
 * Gemini Live (RealtimeToolDispatcher), and the local-intercept lane (VoiceComponentWiring)
 * all run cameras through here (native-executor convergence 2026-07-19; the music pattern —
 * MusicVoiceTool — applied to cameras). Replaces RealtimeVideoFeedBridge's JS dynamic import,
 * which could never load on the kiosk HA origin.
 *
 * Query (the brain/Live tool schema): {action: show|hide|show_all|hide_all|playback,
 * camera?, time?}. Returns {found: Boolean, voice: String} — the spoken confirmation is
 * DETERMINISTIC (no second model pass on the brain lane), mirroring the JS lane's
 * VideoFeedActionHandler wording so web and Android answer alike (registered wording pair —
 * see JS_KOTLIN_CONTRACTS; the golden `timeReferenceOffset` vectors pin the time parsing).
 *
 * Presentation mirrors the JS lane's split: a voice "show X" opens the large centered FOCAL
 * card (drawer); "show all" uses PiP cards (N focal cards would stack); playback opens the
 * Frigate playback UI seeked to the parsed instant. Overlay work is posted to main; the
 * spoken line returns synchronously (same fire-and-forget contract as the JS handler).
 */
class VideoFeedVoiceTool(
    /** Lazy: the registry populates the manager after wiring runs. Null at call time ⇒ no
     *  camera surface on this device (execute fails with the configured-feeds line). */
    private val managerProvider: () -> VideoFeedOverlayManager?,
    private val rulesProvider: () -> List<JSONObject>,
) {
    companion object {
        private const val TAG = "VideoFeedVoiceTool"

        /** Wired by VoiceComponentWiring when the feed manager exists; null ⇒ this device
         *  has no camera surface (the capability handshake then never claims video_feeds). */
        @Volatile
        var instance: VideoFeedVoiceTool? = null

        /**
         * Fuzzy-match a spoken feed name against configured rules: exact, substring both ways,
         * then token overlap (score ≥ 0.3). "pool" matches "Pool Camera". No name → first feed.
         * (Moved from VoiceComponentWiring — the local lane calls THIS copy now.)
         */
        fun findFeedByName(spokenName: String?, rules: List<JSONObject>): JSONObject? {
            if (spokenName.isNullOrBlank()) return rules.firstOrNull()
            val spoken = spokenName.lowercase()
            val spokenTokens = spoken.split(Regex("""\s+"""))

            var bestMatch: JSONObject? = null
            var bestScore = 0.0
            for (rule in rules) {
                val feedName = (rule.optString("name", "").takeIf { it.isNotEmpty() }
                    ?: rule.optString("cameraName", "")).lowercase()
                if (feedName.isEmpty()) continue
                if (feedName == spoken) return rule
                if (feedName.contains(spoken)) {
                    val score = spoken.length.toDouble() / feedName.length + 0.5
                    if (score > bestScore) { bestScore = score; bestMatch = rule }
                    continue
                }
                if (spoken.contains(feedName)) {
                    val score = feedName.length.toDouble() / spoken.length + 0.4
                    if (score > bestScore) { bestScore = score; bestMatch = rule }
                    continue
                }
                val feedTokens = feedName.split(Regex("""\s+"""))
                val overlap = spokenTokens.count { it in feedTokens }
                if (overlap > 0) {
                    val score = overlap.toDouble() / maxOf(spokenTokens.size, feedTokens.size)
                    if (score > bestScore) { bestScore = score; bestMatch = rule }
                }
            }
            return if (bestScore >= 0.3) bestMatch else null
        }
    }

    /** Execute a brain/Live video_feeds query. Safe from any thread. */
    fun execute(query: JSONObject): JSONObject {
        val m = managerProvider() ?: return fail("No video feeds are configured")
        val action = query.optString("action").ifEmpty { "show" }
        val camera = query.optString("camera").takeIf { it.isNotEmpty() }
        val rules = rulesProvider()

        return when (action) {
            "show" -> show(m, camera, rules)
            "hide" -> hide(m, camera, rules)
            "show_all" -> showAll(m, rules)
            "hide_all" -> hideAll(m)
            "playback" -> playback(m, camera, query.optString("time"), rules)
            else -> {
                Log.w(TAG, "DROP: unknown video_feeds action '$action'")
                fail("I'm not sure what to do with the cameras.")
            }
        }
    }

    private fun show(m: VideoFeedOverlayManager, camera: String?, rules: List<JSONObject>): JSONObject {
        if (rules.isEmpty()) return fail("No video feeds are configured")
        // No name → first feed (JS-lane parity); a named miss reads back the available list.
        val match = findFeedByName(camera, rules) ?: return fail(notFound(camera, rules))
        val name = displayName(match)
        // The strip-tap experience: open the video strip and focus this feed in the
        // focal drawer — exactly what the user gets by opening the strip and selecting
        // a feed (2026-07-20: NOT the PiP alert card, NOT the old lightweight
        // drawer, which was choppy MJPEG-only and orphaned its card on a second open).
        onMain { m.showStripWithFocalFeed(match.optString("id")) }
        Log.i(TAG, "📹 show '$name' (strip focal)")
        return ok("Showing $name")
    }

    private fun hide(m: VideoFeedOverlayManager, camera: String?, rules: List<JSONObject>): JSONObject {
        if (camera == null) return hideAll(m)   // "hide the camera" with no name → close them all
        val match = findFeedByName(camera, rules) ?: return fail("I couldn't find a camera called \"$camera\"")
        val name = displayName(match)
        onMain { m.dismissFeedByVoice(match.optString("id")) }
        Log.i(TAG, "📹 hide '$name'")
        return ok("Hiding $name")
    }

    private fun showAll(m: VideoFeedOverlayManager, rules: List<JSONObject>): JSONObject {
        if (rules.isEmpty()) return fail("No video feeds are configured")
        onMain { for (r in rules) m.showFeedByRuleId(r.optString("id")) }   // PiP, not N focal cards
        Log.i(TAG, "📹 show_all (${rules.size})")
        return ok("Showing ${rules.size} camera${if (rules.size > 1) "s" else ""}")
    }

    private fun hideAll(m: VideoFeedOverlayManager): JSONObject {
        onMain { m.dismissAll(); m.hideStrip(); m.releaseDpad() }
        Log.i(TAG, "📹 hide_all")
        return ok("Hiding all cameras")
    }

    private fun playback(m: VideoFeedOverlayManager, camera: String?, timePhrase: String?, rules: List<JSONObject>): JSONObject {
        if (rules.isEmpty()) return fail("No video feeds are configured")
        // Resolve the phrase HERE, on the device: its clock and zone are the reliable ones
        // (a model-emitted timestamp comes back UTC-shifted). Too vague → ASK — guessing
        // shows footage of the wrong moment, and falling back to live answers "what
        // happened earlier?" with a picture of right now.
        val phrase = timePhrase?.trim()?.lowercase().orEmpty()
        val ref = phrase.takeIf { it.isNotEmpty() }
            ?.let { TimeReference.parse(KotlinIntentPatterns.normalizeWordNumbers(it)) }
            ?: return fail("About when? You can say something like ten minutes ago, or around 9.")
        // Playback requires a NAMED camera (JS-lane parity — no first-feed guess for recordings).
        if (camera == null) return fail(notFound(null, rules))
        val match = findFeedByName(camera, rules) ?: return fail(notFound(camera, rules))
        val name = displayName(match)
        if (!match.optBoolean("isFrigateCamera", false)) {
            return fail("$name doesn't have recordings — only Frigate cameras do")
        }
        onMain { m.showFeedPlaybackAt(match.optString("id"), ref.timestampSec) }
        Log.i(TAG, "📹 playback '$name' @ ${ref.timestampSec} (\"${ref.matched}\")")
        return ok("Showing $name from $phrase")
    }

    private fun displayName(rule: JSONObject): String =
        rule.optString("name", "").takeIf { it.isNotEmpty() }
            ?: rule.optString("cameraName", "").takeIf { it.isNotEmpty() } ?: "camera"

    private fun notFound(camera: String?, rules: List<JSONObject>): String {
        val available = rules.mapNotNull { displayName(it).takeIf { n -> n != "camera" } }.joinToString(", ")
        return "I couldn't find a camera called \"$camera\". Available: $available"
    }

    private fun ok(voice: String) = JSONObject().put("found", true).put("voice", voice)
    private fun fail(voice: String): JSONObject {
        Log.w(TAG, "📹 fail: $voice")
        return JSONObject().put("found", false).put("voice", voice)
    }

    private fun onMain(block: () -> Unit) = Handler(Looper.getMainLooper()).post { block() }
}

package com.dashieapp.Dashie.halite.voice

import com.dashieapp.Dashie.halite.apps.AppLaunchRegistry

/**
 * Native-only intent classifier for "open <app>" voice commands.
 *
 * Kept separate from [KotlinIntentPatterns] because — unlike the timer/media/
 * video-feed classifiers there — this is NOT mirrored from the shared JS
 * intent-classifier library. Android can launch external apps by voice; the
 * webapp/HA pipeline can't, so there's no cross-boundary contract to keep.
 *
 * Curated app names + resolution live in [AppLaunchRegistry].
 */
object OpenAppClassifier {

    // Gerunds included because STT often transcribes "opening a youtube tv" /
    // "launching netflix". "start" stays effectively last in the HA-Assist chain
    // (KotlinIntentPatterns runs timer/media earlier); in the cloud pre-intercept
    // this classifier is the only native check, and non-app targets return null
    // so timers/etc. still reach the brain.
    private val APP_LAUNCH_VERBS = listOf(
        "open", "opening", "launch", "launching",
        "pull up", "pulling up", "go to", "start", "starting"
    )
    private val LEADING_ARTICLES = listOf("the ", "a ", "an ", "my ", "some ")

    /**
     * Returns an "app"/"launch" [KotlinIntentPatterns.InterceptResult] when the
     * transcript names an app to open, else null (falls through to video-feed /
     * brain). [normalized] should be lowercased + trimmed of trailing
     * punctuation by the caller.
     */
    fun classify(normalized: String): KotlinIntentPatterns.InterceptResult? {
        val verb = APP_LAUNCH_VERBS.firstOrNull { normalized == it || normalized.startsWith("$it ") }
            ?: return null
        var target = normalized.removePrefix(verb).trim()
        LEADING_ARTICLES.firstOrNull { target.startsWith(it) }?.let { target = target.removePrefix(it).trim() }
        val wantsApp = target == "app" || target.endsWith(" app")
        target = target.removeSuffix(" app").trim()
        if (target.isBlank()) return null

        AppLaunchRegistry.matchKnownApp(target)?.let { known ->
            return KotlinIntentPatterns.InterceptResult(
                "app", "launch", "Opening ${known.label}.",
                mapOf("query" to target, "knownPackage" to known.packageName, "knownLabel" to known.label)
            )
        }

        // Unknown name: only treat it as an app launch when the user said "app"
        // explicitly ("open the plex app") — otherwise fall through so camera /
        // other intents aren't swallowed. The controller resolves dynamically.
        if (wantsApp) {
            return KotlinIntentPatterns.InterceptResult(
                "app", "launch", "Opening $target.",
                mapOf("query" to target, "knownPackage" to "", "knownLabel" to "")
            )
        }
        return null
    }
}

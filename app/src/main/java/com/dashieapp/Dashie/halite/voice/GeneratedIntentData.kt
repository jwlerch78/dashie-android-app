// GENERATED FILE — DO NOT EDIT BY HAND.
// Source of truth: dashieapp_staging/js/vendor/dashie-shared/intent-classifier/src/duration-data.js
// Regenerate:      node scripts/gen-android-intent-data.mjs  (then rebuild the APK)
package com.dashieapp.Dashie.halite.voice

/**
 * Number-word values + duration-parse literals, generated from the shared JS
 * intent-classifier canonical. Consumed by KotlinIntentPatterns (which keeps its
 * own hand-written classify()/parseDuration() LOGIC). Contract #6.
 */
object GeneratedIntentData {
    /** Word → integer (ones/teens then tens), for normalizeWordNumbers. */
    val WORD_NUMBERS: Map<String, Int> = mapOf(
        "zero" to 0,
        "one" to 1,
        "two" to 2,
        "three" to 3,
        "four" to 4,
        "five" to 5,
        "six" to 6,
        "seven" to 7,
        "eight" to 8,
        "nine" to 9,
        "ten" to 10,
        "eleven" to 11,
        "twelve" to 12,
        "thirteen" to 13,
        "fourteen" to 14,
        "fifteen" to 15,
        "sixteen" to 16,
        "seventeen" to 17,
        "eighteen" to 18,
        "nineteen" to 19,
        "twenty" to 20,
        "thirty" to 30,
        "forty" to 40,
        "fifty" to 50,
        "sixty" to 60,
        "seventy" to 70,
        "eighty" to 80,
        "ninety" to 90
    )

    // ── Duration-parse data (parseDuration) ──────────────────────────────
    val HOUR_UNITS: List<String> = listOf("hour", "hr")
    val MINUTE_UNITS: List<String> = listOf("minute", "min")
    val SECOND_UNITS: List<String> = listOf("second", "sec")

    /** Checked BEFORE the whole-hour phrases (both contain "hour"). */
    val HALF_HOUR_PHRASES: List<String> = listOf("half an hour", "half hour")
    val WHOLE_HOUR_PHRASES: List<String> = listOf("an hour", "one hour")
    const val MINUTE_AND_HALF_PHRASE: String = "a minute and a half"

    const val HALF_HOUR_MINUTES: Int = 30
    const val MINUTE_AND_HALF_MINUTES: Int = 1
    const val MINUTE_AND_HALF_SECONDS: Int = 30

    // ── Media / transport keyword lists (patterns.js MEDIA_*) ────────────
    // NEXT/PREVIOUS are matched at COMMAND POSITION only (matchesTransportCommand);
    // PLAY/PAUSE use contains — the consuming logic in KotlinIntentPatterns is
    // hand-written, only the lists are generated.
    val MEDIA_PLAY_KEYWORDS: List<String> = listOf("play music", "play the music", "play spotify", "play on spotify", "resume music", "resume spotify", "resume playback", "resume playing", "continue music", "continue playing", "unpause music", "unpause spotify", "start music", "start playing", "start spotify")
    val MEDIA_PAUSE_KEYWORDS: List<String> = listOf("pause music", "pause the music", "pause spotify", "pause playback", "stop music", "stop the music", "stop spotify", "stop playing", "pause the song", "pause song", "stop the song", "stop song")
    val MEDIA_NEXT_KEYWORDS: List<String> = listOf("next song", "next track", "skip song", "skip track", "skip this", "next one", "skip this song", "play next")
    val MEDIA_PREVIOUS_KEYWORDS: List<String> = listOf("previous song", "previous track", "last song", "last track", "go back", "play previous", "previous one", "rewind")

    // Flexible transport words — only matched while music is playing, and never
    // when an exclude word is present (they're ambiguous without that context).
    val MEDIA_FLEXIBLE_NEXT: List<String> = listOf("next", "skip")
    val MEDIA_FLEXIBLE_STOP: List<String> = listOf("stop", "pause")
    val MEDIA_FLEXIBLE_PREVIOUS: List<String> = listOf("last", "back", "previous")
    val MEDIA_FLEXIBLE_EXCLUDE_WORDS: List<String> = listOf("timer", "alarm", "light", "lights", "door", "lock")

    // ── Volume keyword lists ─────────────────────────────────────────────
    // Emitted as the same TWO lists the JS keeps (system vs music volume).
    // Kotlin's single volume classifier unions them — a documented consumption
    // difference in hand-written logic, not a data divergence.
    val VOLUME_UP_KEYWORDS: List<String> = listOf("volume up", "turn up", "turn it up", "louder", "raise volume", "raise the volume", "bump it up")
    val VOLUME_DOWN_KEYWORDS: List<String> = listOf("volume down", "turn down", "turn it down", "quieter", "softer", "lower volume", "lower the volume")
    val MEDIA_VOLUME_UP_KEYWORDS: List<String> = listOf("turn up the music", "turn music up", "music louder", "louder", "turn it up", "crank it up", "bump it up", "music up", "turn up spotify", "spotify louder")
    val MEDIA_VOLUME_DOWN_KEYWORDS: List<String> = listOf("turn down the music", "turn music down", "music quieter", "quieter", "turn it down", "lower the music", "music down", "softer", "turn down spotify", "spotify quieter")

    // ── HA fast-path guard regex SOURCES (contracts #30 / #34) ───────────
    // Compiled by HaEntityMatcher with IGNORE_CASE. Emitted verbatim from the JS
    // canonical — the pattern syntax subset used is identical in JS and Kotlin.
    const val SCHEDULING_TIME_QUALIFIER: String = "\\bat\\s+\\d{1,2}(:\\d{2})?\\s*(a\\.?m\\.?|p\\.?m\\.?)?\\b|\\b(tonight|tomorrow|this evening|later)\\b"
    const val SECOND_TOOL_VERB: String = "\\bplay(?![a-z])(?! room)|\\bshuffle\\b|\\bput on\\b|\\bqueue\\b|\\bskip\\b|\\bnext (song|track)\\b|\\b(pause|resume|stop) the music\\b|\\bturn (up|down) the (music|volume)\\b|\\bshow me\\b.*\\b(camera|cam|feed)\\b|\\bpull up\\b.*\\b(camera|cam|feed)\\b"

    // ── Time-reference data (time-reference.js — camera playback instants) ──
    // Consumed by TimeReference.kt (hand-written logic; behavior pinned by the
    // timeReferenceOffset golden vectors). Triple = (phrase, hourOfDay, daysAgo);
    // iteration order matters — longest/most-specific phrase first.
    val TIME_NAMED_WINDOWS: List<Triple<String, Int, Int>> = listOf(
        Triple("earlier tonight", 21, 0),
        Triple("this morning", 8, 0),
        Triple("this afternoon", 14, 0),
        Triple("this evening", 19, 0),
        Triple("last night", 21, 1),
        Triple("tonight", 21, 0),
        Triple("yesterday", 12, 1)
    )
    val TIME_REFERENCE_HINTS: List<String> = listOf("ago", "last night", "yesterday", "this morning", "this afternoon", "this evening", "tonight", "earlier", "at ", "o'clock", "am", "pm")
    const val JUST_NOW_SEC: Int = 120
}

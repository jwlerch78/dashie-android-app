package com.dashieapp.Dashie.halite.voice

import java.util.Calendar
import java.util.Date

/**
 * Past time-reference parsing for voice commands — the Kotlin twin of the canonical
 * `js/vendor/dashie-shared/intent-classifier/src/time-reference.js` ("show me the pool camera
 * from ten minutes ago" / "at 3pm" / "last night" → an absolute epoch-seconds instant for
 * Frigate playback).
 *
 * DATA (named windows, hints, just-now offset) comes from [GeneratedIntentData] (codegen'd from
 * the JS canonical — contract #6 family). LOGIC is hand-written per platform and pinned by the
 * `timeReferenceOffset` golden vectors (intent-vectors.json; IntentVectorsTest runs this side).
 *
 * Only PAST instants are meaningful (you can't play back the future): an ambiguous clock time
 * resolves to the most recent one that has already happened — at 9am, "at 3 o'clock" means 3pm
 * YESTERDAY. "o'clock" is NOT a meridiem (the 12:15am bug: treating it as one resolved
 * "at 9 o'clock" to 9am, 15h back, instead of 9pm, 3h back).
 */
object TimeReference {

    data class Ref(val timestampSec: Long, val matched: String)

    private val DAYS_AGO = Regex("""\b(\d+|a|one)\s+days?\s+ago\b""")
    private val AGO = Regex("""\b((?:\d+|a|an|half|couple|few)[\w\s]*?)\s+ago\b""")
    private val VAGUE = Regex("""^(?:a\s+)?(couple|few)\s+(?:of\s+)?(\w+)""")
    private val JUST_NOW = Regex("""\bjust now\b""")
    private val CLOCK = Regex("""\b(?:at|around|about)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm|o'clock)?\b""")

    /** Parse a past time reference from a lowercased, word-number-digitized utterance.
     *  [now] is injectable for the vector tests. */
    fun parse(normalized: String, now: Date = Date()): Ref? {
        if (normalized.isBlank()) return null
        val nowSec = now.time / 1000

        // 1a. Days: "3 days ago" / "a day ago" — parseDuration is the TIMER parser (h/m/s
        // only), so days are handled here or they'd silently degrade to the live view.
        DAYS_AGO.find(normalized)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 1
            return Ref(nowSec - n * 86400L, m.value)
        }

        // 1b. Relative: "<duration> ago" — "10 minutes ago", "an hour ago", "half an hour ago".
        // Vague quantifiers ("a couple/few minutes ago") are digitized before parseDuration.
        AGO.find(normalized)?.let { m ->
            val phrase = m.groupValues[1].trim()
            val vague = VAGUE.find(phrase)
            val duration = if (vague != null) {
                val n = if (vague.groupValues[1] == "couple") 2 else 5
                KotlinIntentPatterns.parseDuration("$n ${vague.groupValues[2]}")
            } else {
                KotlinIntentPatterns.parseDuration(phrase)
            }
            if (duration != null && duration.totalSeconds > 0) {
                return Ref(nowSec - duration.totalSeconds, m.value)
            }
        }

        // 2. Named windows: "last night", "this morning", "yesterday" (generated data;
        // order matters — longest phrase first). A window that hasn't happened yet today
        // ("this evening" said at 8am) rolls back a day.
        for ((phrase, hour, daysAgo) in GeneratedIntentData.TIME_NAMED_WINDOWS) {
            if (normalized.contains(phrase)) {
                var ts = atLocalHour(now, daysAgo, hour, 0)
                if (ts > nowSec) ts -= 24 * 3600
                return Ref(ts, phrase)
            }
        }

        // 3. "just now" — recent enough to matter, far enough back to have footage.
        if (JUST_NOW.containsMatchIn(normalized)) {
            return Ref(nowSec - GeneratedIntentData.JUST_NOW_SEC, "just now")
        }

        // 4. Clock time: "at 3pm", "at 3:30", "around 9", "at 9 o'clock", "at 15:00".
        // Candidate instants, most-recent-past wins: without a meridiem, "9" could be
        // 09:00 or 21:00 on either of two days — enumerate and take the latest past one.
        CLOCK.find(normalized)?.let { m ->
            val rawHour = m.groupValues[1].toIntOrNull() ?: return@let
            val minute = m.groupValues[2].toIntOrNull() ?: 0
            val meridiem = m.groupValues[3].takeIf { it == "am" || it == "pm" }
            if (rawHour > 23 || minute > 59) return null

            val hours = when {
                meridiem == "pm" && rawHour < 12 -> listOf(rawHour + 12)
                meridiem == "am" && rawHour == 12 -> listOf(0)
                meridiem != null -> listOf(rawHour)
                rawHour < 12 -> listOf(rawHour, rawHour + 12)
                else -> listOf(rawHour)
            }
            var best: Long? = null
            for (daysAgo in 0..1) {
                for (hour in hours) {
                    val ts = atLocalHour(now, daysAgo, hour, minute)
                    if (ts <= nowSec && (best == null || ts > best!!)) best = ts
                }
            }
            best?.let { return Ref(it, m.value) }
        }

        return null
    }

    /** True when the utterance looks like it references a past time at all. */
    fun hasTimeReference(normalized: String): Boolean =
        normalized.isNotBlank() && GeneratedIntentData.TIME_REFERENCE_HINTS.any { normalized.contains(it) }

    /** Epoch seconds of ([daysAgo] days before [now]'s date) at local [hour]:[minute]. */
    private fun atLocalHour(now: Date, daysAgo: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            time = now
            add(Calendar.DAY_OF_MONTH, -daysAgo)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis / 1000
    }
}

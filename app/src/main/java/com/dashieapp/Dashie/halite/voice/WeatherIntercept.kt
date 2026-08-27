package com.dashieapp.Dashie.halite.voice

import org.json.JSONObject
import java.util.Calendar

/**
 * Native "what's the weather" pre-intercept for the cascade voice path (punch #4).
 *
 * Home Assistant Assist cannot answer weather ("no weather is exposed"), and a $0
 * degraded turn has no cloud brain — so in those modes a weather question dies. The
 * coordinator gates this to exactly those modes (aiModel = home_assistant, or degraded)
 * and, on a match, fulfills the turn on-device via [WeatherVoiceTool] — Dashie's
 * dashboard weather (HA ↔ Open-Meteo per the general.useHaForWeather toggle), the SAME
 * executor the cloud brain (BrainToolResolver) and Gemini Live already call. In cloud/AI
 * mode the brain still owns weather (it invokes WeatherVoiceTool via the LLM, so indirect
 * phrasings this regex can't catch — "do I need a coat?" — keep working there).
 *
 * Detection here is a deliberately conservative NATIVE-ONLY heuristic: there is no JS
 * twin to mirror because the cloud path lets the LLM decide it's a weather question, not
 * a regex. Fulfillment reuses the vector-pinned WeatherVoiceTool / WeatherTemplate
 * contract (#40); [classify] only ever emits timeframe tokens templateWeather accepts —
 * current / today / tonight / weekend / this_week / <weekday>.
 *
 * Pure + dependency-free (Calendar only, for "tomorrow"→weekday), so it's unit-testable
 * and safe to call from any thread.
 */
object WeatherIntercept {

    private val I = RegexOption.IGNORE_CASE

    // Weather-specific triggers. Deliberately conservative — bare "rain"/"snow"/"sunny"/
    // "outside" show up in media and device turns (which the JS classifier / HA own), so
    // each trigger is bound to a phrasing that is unambiguously a weather QUESTION.
    private val TRIGGERS = listOf(
        Regex("""\bweather\b""", I),
        Regex("""\bforecast\b""", I),
        // "temperature" ONLY in a weather sense. A named HA sensor / thermostat is NOT weather and
        // must fall through to the entity lane (John's "pool thermometer", 2026-07-22). Two ways it
        // gets named: BEFORE ("the POOL temperature", "room temperature") — excluded because "the
        // temperature" isn't consecutive; and AFTER ("the temperature OF/IN the pool", "…in the
        // bedroom") — excluded by the negative lookahead. So "what's the temperature" / "the
        // temperature outside" stay weather, but "the temperature of the pool" does not.
        Regex("""\bthe\s+temperature\b(?!\s+(of|in|for|inside|on|near|by)\b)""", I),
        Regex("""\btemperature\s+(outside|out there|today|tonight|tomorrow|this\s+week(end)?)\b""", I),
        Regex("""\bhow\s+(hot|cold|warm)\s+(is|will)\s+it\b""", I),
        Regex("""\b(will|is)\s+it\s+(going\s+to\s+)?(rain|snow|pour|sleet|hail)\b""", I),
        Regex("""\bis\s+it\s+(raining|snowing|sunny|cloudy)\b""", I),
        Regex("""\bchance\s+of\s+(rain|snow|showers|thunderstorms?)\b""", I),
        Regex("""\b(need|bring|want)\s+(an?\s+)?(umbrella|jacket|coat)\b""", I),
        Regex("""\b(how'?s?\s+it|what'?s?\s+it\s+(like|doing))\s+outside\b""", I),
    )

    private val DAYS = listOf(
        "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday",
    )

    /** Today's weekday name (lowercase), from the device clock. Injectable for tests. */
    private fun currentDayName(): String =
        DAYS[(Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]

    private fun nextDay(name: String): String {
        val i = DAYS.indexOf(name)
        return if (i < 0) name else DAYS[(i + 1) % 7]
    }

    /**
     * Is [transcript] a weather question? Returns the WeatherVoiceTool args
     * ({ "timeframe": … }) on a match, or null to fall through to the normal lane.
     * [todayName] is injectable so "tomorrow" resolves deterministically in tests.
     */
    fun classify(transcript: String, todayName: String = currentDayName()): JSONObject? {
        val t = transcript.lowercase().trim()
        if (t.isEmpty()) return null
        if (TRIGGERS.none { it.containsMatchIn(t) }) return null
        return JSONObject().put("timeframe", extractTimeframe(t, todayName))
    }

    /** Map a weather phrase to a timeframe token templateWeather understands. Order
     *  matters: the more specific windows win before the generic "current" fallback. */
    private fun extractTimeframe(t: String, todayName: String): String = when {
        Regex("""\btonight\b""").containsMatchIn(t) -> "tonight"
        Regex("""\b(this\s+)?weekend\b""").containsMatchIn(t) -> "weekend"
        Regex("""\btomorrow\b""").containsMatchIn(t) -> nextDay(todayName)
        DAYS.firstOrNull { Regex("""\b$it\b""").containsMatchIn(t) } != null ->
            DAYS.first { Regex("""\b$it\b""").containsMatchIn(t) }
        Regex("""\btoday\b""").containsMatchIn(t) -> "today"
        Regex("""\bthis\s+week\b""").containsMatchIn(t) -> "this_week"
        else -> "current"
    }
}

package com.dashieapp.Dashie.halite.voice

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Deterministic weather synthesis (NO LLM) — the Kotlin twin of the device-lane
 * `js/core/voice/weather-template.js` (which also has a brain-side TS sibling,
 * weather-synth.ts — contract #40). Given the normalized reading (VoiceWeatherSnapshot:
 * HA↔Open-Meteo per the dashboard toggle) plus the extracted query, phrases a short
 * spoken line per timeframe:
 *   current → "It's 72 degrees and partly cloudy."   today → "Today: partly cloudy, high 78, low 55."
 *   tonight → "Tonight: clear, low 55."              weekend → "This weekend: Saturday sunny, high 78; …"
 *   <weekday> → "Saturday: sunny, high 78, low 60."
 *
 * Pure + dependency-free; behavior pinned to the JS by the `templateWeather` golden
 * vectors (intent-vectors.json — same mechanism as timeReferenceOffset).
 */
object WeatherTemplate {

    /** HA-style condition token → spoken adjective + precip noun. Tokens match
     *  WeatherDataProvider.wmoCodeToCondition (native) and wmoToCondition (web). */
    private val CONDITION = mapOf(
        "sunny" to ("sunny" to "rain"),
        "clear" to ("clear" to "rain"),
        "clear-night" to ("clear" to "rain"),
        "partlycloudy" to ("partly cloudy" to "rain"),
        "cloudy" to ("cloudy" to "rain"),
        "fog" to ("foggy" to "rain"),
        "rainy" to ("rainy" to "rain"),
        "pouring" to ("heavy rain" to "rain"),
        "snowy-rainy" to ("a wintry mix" to "wintry mix"),
        "snowy" to ("snowy" to "snow"),
        "lightning-rainy" to ("thunderstorms" to "storms"),
        "hail" to ("thunderstorms with hail" to "storms"),
    )
    private val WEEKEND = setOf("saturday", "sunday")

    private fun cond(token: String?): Pair<String, String> =
        CONDITION[token?.lowercase() ?: ""] ?: ("mixed conditions" to "rain")

    /** "40% chance of rain" — only when worth saying (≥20%). */
    private fun precipPhrase(day: JSONObject?): String {
        val p = (day?.optDouble("precipProbability", 0.0) ?: 0.0).roundToInt()
        if (p < 20) return ""
        return "$p% chance of ${cond(day?.optString("condition")).second}"
    }

    /** One day → "sunny, high 78, low 55[, 40% chance of rain]". */
    private fun dayLine(day: JSONObject?, withLow: Boolean = true): String {
        val bits = mutableListOf(cond(day?.optString("condition")).first)
        day?.optDouble("high")?.takeIf { it.isFinite() }?.let { bits += "high ${it.roundToInt()}" }
        if (withLow) day?.optDouble("low")?.takeIf { it.isFinite() }?.let { bits += "low ${it.roundToInt()}" }
        val precip = precipPhrase(day)
        val line = bits.joinToString(", ")
        return if (precip.isNotEmpty()) "$line, $precip" else line
    }

    private fun daily(data: JSONObject): List<JSONObject> {
        val arr: JSONArray = data.optJSONArray("daily") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun findDay(daily: List<JSONObject>, name: String): JSONObject? =
        daily.find { it.optString("dayName").lowercase() == name.lowercase() }

    private fun weekendDays(daily: List<JSONObject>): List<JSONObject> =
        daily.filter { it.optString("dayName").lowercase() in WEEKEND }.take(2)

    // ── precip timing ("when will the rain stop/start") ─────────────────────────
    // Twin of weather-template.js's block of the same name. Kept structurally identical
    // (same helper split, same order of checks) so a future change to either is a visible
    // one-to-one diff rather than a re-derivation.
    private val WET_CONDITIONS =
        setOf("rainy", "pouring", "snowy", "snowy-rainy", "lightning-rainy", "hail")

    /** An hour counts as "wet" at ≥50% precip chance OR an outright precip condition code. */
    private fun hourIsWet(h: JSONObject?): Boolean {
        val prob = h?.optDouble("precipProb", 0.0) ?: 0.0
        val p = if (prob.isFinite()) prob else 0.0
        return p >= 50 || (h?.optString("condition")?.lowercase() ?: "") in WET_CONDITIONS
    }

    /** Spoken hour label from a "3 PM"/"3PM" label OR a "…T15:00" ISO — else "". */
    private fun hourLabel(t: String?): String {
        val s = (t ?: "").trim()
        Regex("^(\\d{1,2})\\s*(AM|PM)$", RegexOption.IGNORE_CASE).find(s)?.let {
            return "${it.groupValues[1].toInt()} ${it.groupValues[2].uppercase()}"
        }
        Regex("T(\\d{2})").find(s)?.let {
            var h = it.groupValues[1].toInt()
            val ap = if (h < 12) "AM" else "PM"
            h %= 12; if (h == 0) h = 12
            return "$h $ap"
        }
        return ""
    }

    /** "rain" or "snow" from the first wet hour's condition (defaults to rain). */
    private fun precipWord(hours: List<JSONObject>): String =
        if (hours.firstOrNull { hourIsWet(it) }?.optString("condition")?.lowercase()
                ?.startsWith("snow") == true) "snow" else "rain"

    /** "When will it stop/start" from the hourly window. Falls back to current conditions
     *  when no hourly data is present (e.g. the native path before it emits hourly). */
    private fun precipTimingLine(data: JSONObject): String {
        val arr = data.optJSONArray("hourly")
        val hrs = if (arr == null) emptyList()
                  else (0 until minOf(arr.length(), 18)).mapNotNull { arr.optJSONObject(it) }
        if (hrs.isEmpty()) return currentLine(data)
        val word = precipWord(hrs)
        if (hourIsWet(hrs[0])) {
            // JS: hrs.findIndex((h, i) => i > 0 && !hourIsWet(h)) — the i>0 skip matters (hour 0
            // is wet by definition in this branch). Index-based, not an identity check: the same
            // JSONObject can legitimately recur in the array.
            val stop = (1 until hrs.size).firstOrNull { !hourIsWet(hrs[it]) } ?: -1
            if (stop == -1) return "The $word should stick around for the next several hours."
            val when_ = hourLabel(hrs[stop].optString("time"))
            return if (when_.isNotEmpty()) "The $word should let up around $when_."
                   else "The $word should let up soon."
        }
        val start = hrs.indexOfFirst { hourIsWet(it) }
        if (start == -1) return "No $word in the forecast for the next several hours."
        val when_ = hourLabel(hrs[start].optString("time"))
        val cap = word.replaceFirstChar { it.uppercase() }
        return if (when_.isNotEmpty()) "$cap looks likely around $when_." else "$cap is possible later."
    }

    private fun currentLine(data: JSONObject): String {
        val c = data.optJSONObject("current") ?: JSONObject()
        val city = data.optJSONObject("location")?.optString("city").orEmpty()
        val place = if (city.isNotEmpty()) " in $city" else ""
        val t = c.optDouble("temperature")
        val temp = if (t.isFinite()) "${t.roundToInt()} degrees" else "out"
        val head = "It's $temp and ${cond(c.optString("condition")).first}$place."
        val today = daily(data).firstOrNull()
        val precip = today?.let { precipPhrase(it) }.orEmpty()
        return if (precip.isNotEmpty()) "$head ${precip.replaceFirstChar { it.uppercase() }} today." else head
    }

    /** Synthesize the spoken answer. Mirrors the JS templateWeather exactly (vector-pinned). */
    fun templateWeather(data: JSONObject?, query: JSONObject = JSONObject()): String {
        if (data == null || (data.has("found") && !data.optBoolean("found", true))) {
            return "I couldn't get the weather right now."
        }
        val daily = daily(data)
        val tf = query.optString("timeframe").lowercase().trim()

        return when {
            tf == "weekend" -> {
                val wknd = weekendDays(daily)
                if (wknd.isEmpty()) currentLine(data)
                else "This weekend: " + wknd.joinToString("; ") {
                    "${it.optString("dayName")} ${dayLine(it, withLow = false)}"
                } + "."
            }
            tf == "tonight" -> {
                val today = daily.firstOrNull()
                val low = today?.optDouble("low")
                if (today != null && low != null && low.isFinite())
                    "Tonight: ${cond(today.optString("condition")).first}, low ${low.roundToInt()}."
                else currentLine(data)
            }
            tf == "today" -> daily.firstOrNull()?.let { "Today: ${dayLine(it)}." } ?: currentLine(data)
            tf == "tomorrow" -> {
                // daily[0] is today, so tomorrow is daily[1]. Without this branch "tomorrow" fell
                // through to the weekday matcher (findDay, which only matches Monday…Sunday),
                // missed, and defaulted to currentLine → "…today" (the 2026-07 field bug:
                // "weather tomorrow" answered with today's conditions).
                daily.getOrNull(1)?.let { "Tomorrow: ${dayLine(it)}." } ?: currentLine(data)
            }
            tf == "precip_timing" -> {
                // "When will the rain stop/start" — scan the hourly window (2026-07 field bug:
                // this answered with a generic current snapshot because no hourly data was read).
                precipTimingLine(data)
            }
            tf.isNotEmpty() && tf != "current" && tf != "this_week" ->
                findDay(daily, tf)?.let { "${it.optString("dayName")}: ${dayLine(it)}." } ?: currentLine(data)
            else -> currentLine(data)
        }
    }
}

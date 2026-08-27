package com.dashieapp.Dashie.halite.voice

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * The ONE native executor for the `weather` voice tool on Android — cascade
 * (BrainToolResolver `client_tool:'weather'`) and Gemini Live (`get_weather`) both run
 * through here (native-executor convergence 2026-07-19; replaced RealtimeWeatherBridge's
 * WebView round-trip, which could never load on the kiosk HA origin).
 *
 * Reading: [VoiceWeatherSnapshot] — the SAME source the JS lane's DashieNative
 * .getVoiceWeather serves (native WeatherDataProvider, honoring the HA↔Open-Meteo
 * dashboard toggle), so a voice answer matches the dashboard by construction.
 * Synthesis: [WeatherTemplate] — the vector-pinned Kotlin twin of weather-template.js.
 * Returns the bridge-shaped envelope `{ result: { found, voice, text }, card }` so the
 * call sites swapped without change.
 */
object WeatherVoiceTool {

    private const val TAG = "WeatherVoiceTool"

    private val SHORT_TO_LONG = mapOf(
        "Sun" to "Sunday", "Mon" to "Monday", "Tue" to "Tuesday", "Wed" to "Wednesday",
        "Thu" to "Thursday", "Fri" to "Friday", "Sat" to "Saturday",
    )

    fun query(context: Context, args: JSONObject): JSONObject {
        val prefs = HalitePreferences(context)
        val useHa = prefs.general.useHaForWeather
        val data = try {
            // onDemand: a fresh boot with no weather surface run yet must still answer —
            // triggers one synchronous shared fetch (429-safe; caller is off-main).
            JSONObject(VoiceWeatherSnapshot.build(useHa, onDemand = prefs to context))
        } catch (e: Exception) {
            Log.w(TAG, "🌤️ snapshot failed: ${e.message}")
            JSONObject().put("found", false)
        }
        // Native dayNames are short ("Mon") — normalize to long so the template's weekday
        // matching works (same mapping the JS tool applies to this same snapshot).
        data.optJSONArray("daily")?.let { daily ->
            val out = JSONArray()
            for (i in 0 until daily.length()) {
                val d = daily.optJSONObject(i) ?: continue
                SHORT_TO_LONG[d.optString("dayName")]?.let { d.put("dayName", it) }
                out.put(d)
            }
            data.put("daily", out)
        }
        val found = !data.has("found") || data.optBoolean("found", true)
        val voice = WeatherTemplate.templateWeather(data, args)
        Log.i(TAG, "🌤️ weather (native): tf=${args.optString("timeframe").ifEmpty { "current" }} source=${data.optString("source", "none")} found=$found")
        return JSONObject()
            .put("result", JSONObject().put("found", found).put("voice", voice).put("text", JSONObject.NULL))
            .put("card", JSONObject.NULL)
    }
}

package com.dashieapp.Dashie.halite.voice

import com.dashieapp.Dashie.halite.screensaver.WeatherDataProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the normalized JSON the JS voice weather tool (`weather-tool.js`) consumes from
 * the process-wide [WeatherDataProvider] snapshot — the same reading the dashboard shows,
 * already honoring the HA↔Open-Meteo toggle. Reading the shared snapshot (rather than
 * starting a new poll) is what keeps a voice answer in sync with the dashboard's source.
 *
 * Returns {"found":false} when nothing has been fetched yet; the JS tool then falls back
 * to its own Open-Meteo path. Kept out of the JS bridge delegate to keep that file thin.
 */
object VoiceWeatherSnapshot {

    /** @param useHa the user's HA-weather toggle — used only to label the `source`.
     *  @param onDemand when non-null (prefs+context), an empty shared snapshot triggers a
     *  synchronous fetch (voice must work on a fresh boot before any surface has polled;
     *  callers are on background threads). Null keeps the old passive read (JS bridge). */
    fun build(
        useHa: Boolean,
        onDemand: Pair<com.dashieapp.Dashie.halite.HalitePreferences, android.content.Context?>? = null,
    ): String {
        val data = (if (onDemand != null)
            WeatherDataProvider.sharedSnapshotOrFetch(onDemand.first, onDemand.second)
        else WeatherDataProvider.sharedSnapshot())
            ?: return JSONObject().put("found", false).toString()
        val daily = JSONArray()
        for (d in data.forecast) {
            daily.put(
                JSONObject()
                    .put("dayName", d.dayName)
                    .put("high", d.tempHigh)
                    .put("low", d.tempLow)
                    .put("condition", d.condition)
                    .put("precipProbability", d.precipProbability)
            )
        }
        return JSONObject()
            .put("found", true)
            .put("source", if (useHa) "ha" else "open-meteo")
            .put("location", JSONObject().put("city", "").put("state", ""))
            .put(
                "current",
                JSONObject()
                    .put("temperature", data.temperature)
                    .put("condition", data.condition)
                    .put("windSpeed", data.windSpeed ?: 0.0)
            )
            .put("daily", daily)
            .toString()
    }
}

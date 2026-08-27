package com.dashieapp.Dashie.halite.videofeed

import com.dashieapp.Dashie.edition.ApiPaths

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data classes for Frigate API responses (events, recording summaries).
 * Parsed from JSON returned by the HA integration's Frigate proxy endpoints.
 */

data class FrigateEvent(
    val id: String,
    val label: String,        // "person", "car", "dog", etc.
    val score: Float,         // Detection confidence 0-1
    val startTime: Double,    // Unix timestamp (seconds, fractional)
    val endTime: Double?,     // Null if event is still in progress
    val camera: String,
    val thumbnailUrl: String, // Relative: ${ApiPaths.HA}/frigate/event/{id}/thumbnail.jpg
    val clipUrl: String,      // Relative: ${ApiPaths.HA}/frigate/event/{id}/clip.mp4
    val hasClip: Boolean      // Frigate saved a per-event clip. NOTE: not the same
                              // as "playable" — playback uses the timeline/recordings
                              // endpoint, so snapshot-only events (has_clip=false) on a
                              // continuous-recording camera still play. See
                              // FrigatePlaybackOverlay.hasRecordingFor().
) {
    companion object {
        fun fromJson(json: JSONObject): FrigateEvent = FrigateEvent(
            id = json.optString("id", ""),
            label = json.optString("label", "object"),
            score = json.optDouble("top_score", json.optDouble("score", 0.0)).toFloat(),
            startTime = json.optDouble("start_time", 0.0),
            endTime = json.optDouble("end_time", Double.NaN).takeIf { !it.isNaN() },
            camera = json.optString("camera", ""),
            thumbnailUrl = "${ApiPaths.HA}/frigate/event/${json.optString("id")}/thumbnail.jpg",
            clipUrl = "${ApiPaths.HA}/frigate/event/${json.optString("id")}/clip.mp4",
            hasClip = json.optBoolean("has_clip", false)
        )

        fun fromJsonArray(array: JSONArray): List<FrigateEvent> =
            (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
    }
}

data class RecordingHour(
    val hour: Long,      // Unix timestamp of the hour start
    val duration: Int,    // Seconds of recording in this hour
    val events: Int       // Number of events in this hour
) {
    companion object {
        /**
         * Parse Frigate's recording summary response.
         * Format: [{day: "2026-04-13", hours: [{hour: "20", events: 3, duration: 80}, ...]}]
         * Returns flat list of RecordingHour with hour converted to unix timestamp.
         */
        fun fromFrigateSummary(array: JSONArray): List<RecordingHour> {
            val result = mutableListOf<RecordingHour>()
            // Frigate recording summary reports day + hour in UTC.
            // Use a UTC calendar so timestamps are correct regardless of device timezone.
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))

            for (dayIdx in 0 until array.length()) {
                val dayObj = array.getJSONObject(dayIdx)
                val dayStr = dayObj.optString("day", "")
                val parts = dayStr.split("-")
                if (parts.size != 3) continue
                val year = parts[0].toIntOrNull() ?: continue
                val month = (parts[1].toIntOrNull() ?: continue) - 1 // Calendar months are 0-based
                val day = parts[2].toIntOrNull() ?: continue

                val hours = dayObj.optJSONArray("hours") ?: continue
                for (hourIdx in 0 until hours.length()) {
                    val hourObj = hours.getJSONObject(hourIdx)
                    val hourNum = hourObj.optString("hour", "0").toIntOrNull() ?: 0

                    cal.set(year, month, day, hourNum, 0, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    val timestampSec = cal.timeInMillis / 1000

                    result.add(RecordingHour(
                        hour = timestampSec,
                        duration = hourObj.optInt("duration", 0),
                        events = hourObj.optInt("events", 0)
                    ))
                }
            }
            return result
        }
    }
}

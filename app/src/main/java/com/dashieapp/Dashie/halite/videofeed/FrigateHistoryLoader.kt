package com.dashieapp.Dashie.halite.videofeed

import com.dashieapp.Dashie.edition.ApiPaths

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Fetches Frigate recording summary + events for a time range and delivers both
 * on the main thread. Used for the initial 3-day load and for lazy-loading older
 * days when the user snaps the day bar past the loaded window.
 */
class FrigateHistoryLoader(private val context: Context) {
    companion object {
        private const val TAG = "FrigateHistory"
        private const val EVENTS_LIMIT = 100
    }

    private val httpClient = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())

    /** Fetch [afterSec, beforeSec] for [camera]; onResult runs on the main thread. */
    fun fetch(
        camera: String, afterSec: Long, beforeSec: Long,
        onResult: (hours: List<RecordingHour>, events: List<FrigateEvent>) -> Unit
    ) {
        Thread {
            val creds = getCredentials()
            if (creds == null) {
                handler.post { onResult(emptyList(), emptyList()) }
                return@Thread
            }
            val (baseUrl, token) = creds
            val base = baseUrl.trimEnd('/')
            val hours = getJson(
                "$base${ApiPaths.HA}/frigate/recordings/$camera/summary?after=$afterSec&before=$beforeSec", token
            )?.let { runCatching { RecordingHour.fromFrigateSummary(JSONArray(it)) }.getOrNull() } ?: emptyList()
            val events = getJson(
                "$base${ApiPaths.HA}/frigate/events?camera=$camera&after=$afterSec&before=$beforeSec&limit=$EVENTS_LIMIT", token
            )?.let { runCatching { FrigateEvent.fromJsonArray(JSONArray(it)) }.getOrNull() } ?: emptyList()
            Log.d(TAG, "Loaded ${hours.size} hours, ${events.size} events for [$afterSec,$beforeSec]")
            handler.post { onResult(hours, events) }
        }.start()
    }

    /** Lightweight: fetch only the recording summary (for day availability). */
    fun fetchSummary(camera: String, afterSec: Long, beforeSec: Long, onResult: (List<RecordingHour>) -> Unit) {
        Thread {
            val creds = getCredentials()
            if (creds == null) { handler.post { onResult(emptyList()) }; return@Thread }
            val (baseUrl, token) = creds
            val hours = getJson(
                "${baseUrl.trimEnd('/')}${ApiPaths.HA}/frigate/recordings/$camera/summary?after=$afterSec&before=$beforeSec", token
            )?.let { runCatching { RecordingHour.fromFrigateSummary(JSONArray(it)) }.getOrNull() } ?: emptyList()
            handler.post { onResult(hours) }
        }.start()
    }

    /**
     * Is there recorded footage covering [targetSec]? Frigate retention, a camera that was
     * offline, or a clock skew all leave holes — a voice seek into one otherwise yields a
     * black card with no explanation, so callers probe first and can say "no recording from
     * then". Frigate's summary is hour-bucketed, so this asks about the containing hour.
     */
    fun hasFootageAt(camera: String, targetSec: Long, onResult: (Boolean) -> Unit) {
        val hourStart = targetSec - (targetSec % 3600)
        fetchSummary(camera, hourStart, targetSec + 3600) { hours ->
            onResult(hours.any { it.hour == hourStart && it.duration > 0 })
        }
    }

    private fun getCredentials(): Pair<String, String>? {
        val prefs = com.dashieapp.Dashie.halite.HalitePreferences(context)
        val creds = com.dashieapp.Dashie.halite.HaTokenExtractor.getValidCredentialsSync(prefs)
        return if (creds != null) Pair(creds.first, creds.second) else null
    }

    private fun getJson(url: String, token: String): String? = try {
        val request = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()
        httpClient.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string()
            else { Log.e(TAG, "API ${resp.code} for $url"); null }
        }
    } catch (e: Exception) {
        Log.e(TAG, "API failed: ${e.message}"); null
    }
}

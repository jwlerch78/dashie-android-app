package com.dashieapp.Dashie.halite.music

import com.dashieapp.Dashie.edition.ApiPaths

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Persists hidden speaker IDs at two levels:
 * - This tablet: SharedPreferences only (no sync)
 * - All Dashie tablets: SharedPreferences + HA integration central store
 *
 * The "all tablets" list syncs to HA via /api/dashie/music/hidden_speakers
 * (same pattern as the MA token store and camera feed registry).
 */
class SpeakerVisibilityStore(private val context: Context) {

    companion object {
        private const val TAG = "SpeakerVisibility"
        private const val PREFS_NAME = "dashie_speaker_visibility"
        private const val KEY_LOCAL_HIDDEN = "hidden_local"
        private const val KEY_GLOBAL_HIDDEN = "hidden_global"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // HA connection info (set externally before sync calls)
    var haBaseUrl: String = ""
    var haAuthToken: String = ""

    /** Get speaker IDs hidden on this tablet only. */
    fun getLocalHidden(): Set<String> {
        val raw = prefs.getString(KEY_LOCAL_HIDDEN, "") ?: ""
        return raw.split(",").filter { it.isNotEmpty() }.toSet()
    }

    /** Get speaker IDs hidden on all Dashie tablets. */
    fun getGlobalHidden(): Set<String> {
        val raw = prefs.getString(KEY_GLOBAL_HIDDEN, "") ?: ""
        return raw.split(",").filter { it.isNotEmpty() }.toSet()
    }

    /** All hidden speaker IDs (union of local + global). */
    fun getAllHidden(): Set<String> = getLocalHidden() + getGlobalHidden()

    /** Hide a speaker on this tablet only. */
    fun hideLocal(playerId: String) {
        val current = getLocalHidden().toMutableSet()
        current.add(playerId)
        prefs.edit().putString(KEY_LOCAL_HIDDEN, current.joinToString(",")).apply()
        Log.i(TAG, "Hidden locally: $playerId")
    }

    /** Hide a speaker on all Dashie tablets. Syncs to HA central store. */
    fun hideGlobal(playerId: String) {
        val current = getGlobalHidden().toMutableSet()
        current.add(playerId)
        saveGlobalHidden(current)
        syncGlobalToHa(current.toList())
    }

    /** Unhide a speaker from this tablet. */
    fun showLocal(playerId: String) {
        val current = getLocalHidden().toMutableSet()
        current.remove(playerId)
        prefs.edit().putString(KEY_LOCAL_HIDDEN, current.joinToString(",")).apply()
        Log.i(TAG, "Shown locally: $playerId")
    }

    /** Unhide a speaker from all Dashie tablets. Syncs to HA central store. */
    fun showGlobal(playerId: String) {
        val current = getGlobalHidden().toMutableSet()
        current.remove(playerId)
        saveGlobalHidden(current)
        syncGlobalToHa(current.toList())
    }

    /** Unhide a globally-hidden speaker on this tablet only (no HA sync).
     *  Removes from local global-hidden cache so it reappears here,
     *  but other tablets still see it as hidden until they re-fetch. */
    fun showGlobalLocalOnly(playerId: String) {
        val current = getGlobalHidden().toMutableSet()
        current.remove(playerId)
        saveGlobalHidden(current)
        Log.i(TAG, "Shown globally (local only, no HA sync): $playerId")
    }

    /** Check if a speaker is hidden (either scope). */
    fun isHidden(playerId: String): Boolean = playerId in getAllHidden()

    /** Fetch global hidden list from HA and merge into local cache. */
    fun fetchGlobalFromHa() {
        if (haBaseUrl.isEmpty() || haAuthToken.isEmpty()) return
        Thread {
            try {
                val url = URL("$haBaseUrl${ApiPaths.HA}/music/hidden_speakers")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Authorization", "Bearer $haAuthToken")
                val code = conn.responseCode
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val json = JSONObject(body)
                    val arr = json.optJSONArray("hidden") ?: JSONArray()
                    val ids = (0 until arr.length()).map { arr.getString(it) }.toSet()
                    saveGlobalHidden(ids)
                    Log.i(TAG, "Fetched ${ids.size} globally hidden speakers from HA")
                } else {
                    conn.disconnect()
                    Log.w(TAG, "Failed to fetch hidden speakers: HTTP $code")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch hidden speakers: ${e.message}")
            }
        }.start()
    }

    // ── Internal ──

    private fun saveGlobalHidden(ids: Set<String>) {
        prefs.edit().putString(KEY_GLOBAL_HIDDEN, ids.joinToString(",")).apply()
        Log.i(TAG, "Saved ${ids.size} globally hidden speakers locally")
    }

    private fun syncGlobalToHa(hidden: List<String>) {
        if (haBaseUrl.isEmpty() || haAuthToken.isEmpty()) {
            Log.w(TAG, "Cannot sync to HA — no base URL or auth token")
            return
        }
        Thread {
            try {
                val url = URL("$haBaseUrl${ApiPaths.HA}/music/hidden_speakers")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $haAuthToken")
                conn.doOutput = true
                val body = JSONObject().apply {
                    put("hidden", JSONArray(hidden))
                }
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()); it.flush() }
                val code = conn.responseCode
                conn.disconnect()
                Log.i(TAG, "Synced ${hidden.size} hidden speakers to HA: HTTP $code")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync hidden speakers to HA: ${e.message}")
            }
        }.start()
    }
}

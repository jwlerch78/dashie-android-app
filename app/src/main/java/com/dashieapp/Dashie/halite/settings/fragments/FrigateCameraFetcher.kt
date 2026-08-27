package com.dashieapp.Dashie.halite.settings.fragments

import com.dashieapp.Dashie.edition.ApiPaths

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaTokenExtractor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One-shot fetch of Frigate camera names via the HA proxy.
 *
 * Calls /api/dashie/frigate/cameras on the configured HA instance.
 * Returns a list of camera names on success, or null if Frigate is not
 * reachable (HA returns 502) or any other error occurs. Callers should
 * treat null as "no Frigate cameras available — hide the override row".
 */
object FrigateCameraFetcher {

    private const val TAG = "FrigateCameraFetcher"

    private val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    fun fetch(context: Context, onResult: (List<String>?) -> Unit) {
        Thread {
            val result = try {
                val halitePrefs = HalitePreferences(context)
                val creds = HaTokenExtractor.getValidCredentialsSync(halitePrefs)
                if (creds == null) {
                    Log.w(TAG, "No HA credentials available")
                    null
                } else {
                    val (baseUrl, token) = creds
                    val url = "${baseUrl.trimEnd('/')}${ApiPaths.HA}/frigate/cameras"
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            Log.i(TAG, "Frigate cameras fetch returned ${resp.code}")
                            null
                        } else {
                            val body = resp.body?.string().orEmpty()
                            val json = JSONObject(body)
                            val arr = json.optJSONArray("cameras") ?: return@use emptyList<String>()
                            (0 until arr.length()).map { arr.getString(it) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Frigate cameras fetch failed: ${e.message}")
                null
            }
            mainHandler.post { onResult(result) }
        }.start()
    }
}

package com.dashieapp.Dashie.halite.install

import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the device-checkin edge function.
 * Sends monthly heartbeat and receives account entitlements.
 */
object CheckinClient {
    private const val TAG = "CheckinClient"

    private val CHECKIN_URL = "${BuildConfig.SUPABASE_URL}/functions/v1/device-checkin"
    private val ANON_KEY = BuildConfig.SUPABASE_ANON_KEY

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    data class CheckinResult(
        val success: Boolean,
        val voiceEntitled: Boolean = false,
        val subscriptionStatus: String? = null,
        val tier: String? = null
    )

    fun checkin(data: CheckinData): CheckinResult {
        return try {
            val requestBody = JSONObject().apply {
                put("install_token", data.installToken)
                put("app_version", data.appVersion)
                put("is_logged_in", data.isLoggedIn)
                data.subscriptionStatus?.let { put("subscription_status", it) }
                data.networkId?.let { put("network_id", it) }
                data.uptimeHours?.let { put("uptime_hours", it) }
                data.installSource?.let { put("install_source", it) }
            }

            val request = Request.Builder()
                .url(CHECKIN_URL)
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $ANON_KEY")
                .header("apikey", ANON_KEY)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: "{}"
            val json = JSONObject(body)

            if (response.isSuccessful && json.optBoolean("success", false)) {
                val entitlements = json.optJSONObject("entitlements")
                Log.i(TAG, "✅ Check-in successful (entitlements=${entitlements != null})")
                CheckinResult(
                    success = true,
                    voiceEntitled = entitlements?.optBoolean("voice_entitled", false) ?: false,
                    subscriptionStatus = entitlements?.optString("subscription_status"),
                    tier = entitlements?.optString("tier")
                )
            } else {
                Log.w(TAG, "❌ Check-in failed: ${json.optString("error")}")
                CheckinResult(success = false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "❌ Check-in network error", e)
            CheckinResult(success = false)
        }
    }
}

data class CheckinData(
    val installToken: String,
    val appVersion: String,
    val isLoggedIn: Boolean,
    val subscriptionStatus: String?,
    val networkId: String?,
    val uptimeHours: Int?,
    val installSource: String?
)

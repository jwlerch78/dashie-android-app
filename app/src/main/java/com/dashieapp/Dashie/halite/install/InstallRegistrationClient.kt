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
 * HTTP client for the register-install edge function.
 * Returns an install_token on success, null on failure.
 */
object InstallRegistrationClient {
    private const val TAG = "InstallRegClient"

    private val REGISTER_URL = "${BuildConfig.SUPABASE_URL}/functions/v1/register-install"
    private val ANON_KEY = BuildConfig.SUPABASE_ANON_KEY

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    data class RegisterResult(
        val success: Boolean,
        val installToken: String? = null,
        val isNew: Boolean = false
    )

    /**
     * Register this device install with the server.
     * Called on a background thread.
     */
    fun register(data: InstallData): RegisterResult {
        return try {
            val requestBody = JSONObject().apply {
                put("android_id", data.androidId)
                data.stableDeviceId?.takeIf { it.isNotEmpty() }?.let { put("stable_device_id", it) }
                put("device_model", data.deviceModel)
                put("device_brand", data.deviceBrand)
                put("device_product", data.deviceProduct)
                put("screen_width_dp", data.screenWidthDp)
                put("screen_height_dp", data.screenHeightDp)
                put("android_version", data.androidVersion)
                put("app_version", data.appVersion)
                put("build_flavor", data.buildFlavor)
                data.networkId?.let { put("network_id", it) }
                data.countryCode?.let { put("country_code", it) }
                data.timezone?.let { put("timezone", it) }
                data.installSource?.let { put("install_source", it) }
            }

            val request = Request.Builder()
                .url(REGISTER_URL)
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $ANON_KEY")
                .header("apikey", ANON_KEY)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: "{}"
            val json = JSONObject(body)

            if (response.isSuccessful && json.optBoolean("success", false)) {
                val token = json.optString("install_token", "")
                val isNew = json.optBoolean("is_new", false)
                Log.i(TAG, "✅ Install registered (new=$isNew)")
                RegisterResult(success = true, installToken = token, isNew = isNew)
            } else {
                val error = json.optString("error", "Unknown error")
                Log.w(TAG, "❌ Registration failed: $error")
                RegisterResult(success = false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "❌ Registration network error", e)
            RegisterResult(success = false)
        }
    }
}

data class InstallData(
    val androidId: String,
    val stableDeviceId: String?,
    val deviceModel: String,
    val deviceBrand: String,
    val deviceProduct: String,
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val androidVersion: Int,
    val appVersion: String,
    val buildFlavor: String,
    val networkId: String?,
    val countryCode: String?,
    val timezone: String?,
    val installSource: String?
)

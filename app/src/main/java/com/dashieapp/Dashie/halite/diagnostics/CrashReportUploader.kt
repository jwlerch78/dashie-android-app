package com.dashieapp.Dashie.halite.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.telemetryDelegates.TelemetryDelegate
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads a crash report to the `dashboard-telemetry` edge function.
 *
 * Extracted from [com.dashieapp.Dashie.MainCrashReportHandler] so both the
 * user-facing crash dialog and the silent [AbnormalExitReporter] share one
 * upload path. This object does no UI — callers own their own success/failure
 * feedback. The payload shape is unchanged from the original dialog flow, so
 * the edge function needs no changes.
 */
object CrashReportUploader {
    private const val TAG = "CrashReportUploader"
    private const val TIMEOUT_MS = 10_000

    /**
     * POST [crashReport] to `dashboard-telemetry`. Blocking network call —
     * must be invoked off the main thread. Returns true on a 2xx response.
     */
    fun upload(context: Context, crashReport: String): Boolean {
        return try {
            val deviceId = com.dashieapp.Dashie.util.StableDeviceId.read(context).ifEmpty { "unknown" }

            // Include the last persisted HA dashboard config when available — it
            // gives crash triage the widget/camera layout that was on screen.
            val lovelaceConfigJson: JSONObject? = try {
                val configFile = File(context.filesDir, TelemetryDelegate.LOVELACE_CONFIG_FILE)
                if (configFile.exists()) JSONObject(configFile.readText()) else null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read lovelace_config for crash report: ${e.message}")
                null
            }

            // Strip control bytes (NUL especially) before upload — Postgres
            // `text` columns reject them, and crash text can carry stray bytes
            // from interrupted log writes or binary exit-trace data. Tab, LF
            // and CR are kept; everything else below 0x20 is dropped.
            val safeReport = crashReport.filter {
                it == '\n' || it == '\t' || it == '\r' || it.code >= 0x20
            }

            val payload = JSONObject().apply {
                put("type", "crash_report")
                put("device_id", deviceId)
                put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("android_version", Build.VERSION.RELEASE)
                put("api_level", Build.VERSION.SDK_INT)
                put("app_version", BuildConfig.VERSION_NAME)
                put("build_type", BuildConfig.BUILD_TYPE)
                put("environment", BuildConfig.ENVIRONMENT)
                put("crash_report", safeReport)
                put("lovelace_config", lovelaceConfigJson ?: JSONObject.NULL)
                put("timestamp", System.currentTimeMillis())
            }

            val endpoint = BuildConfig.SUPABASE_URL + "/functions/v1/dashboard-telemetry"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
            }
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                Log.i(TAG, "Crash report uploaded: HTTP $responseCode")
                true
            } else {
                // Surface the server's error body — the edge function returns
                // {"success":false,"error":"..."} on a 400, which pinpoints
                // payload/DB issues without needing to pull files off-device.
                val errBody = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    null
                }
                Log.e(TAG, "Crash report upload rejected: HTTP $responseCode ${errBody.orEmpty()}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload crash report: ${e.message}")
            false
        }
    }
}

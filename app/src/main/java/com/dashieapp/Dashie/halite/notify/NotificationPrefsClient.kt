package com.dashieapp.Dashie.halite.notify

import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.HalitePreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reads/writes a family member's per-member notification preferences
 * (member_notification_preferences) from the native family-settings editor.
 *
 * Today it only handles `notify_on_reminder` — whether that member's phone gets a
 * push when a timer/reminder fires (the native mirror of the console + mobile web
 * "Timer & reminder alerts" toggle). Writes are orthogonal: the edge handler only
 * touches the field we send, so this never clobbers arrive/depart prefs.
 *
 * Same database-operations call shape + account-JWT auth as [FamilyAlertNotifier]
 * / ScheduleCloudMirror — the proven native caller. Fire-and-forget on a daemon
 * thread; no-op without an account JWT.
 */
object NotificationPrefsClient {

    @Volatile private var prefs: HalitePreferences? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val exec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "notification-prefs-client").apply { isDaemon = true }
    }

    fun wire(prefs: HalitePreferences) {
        this.prefs = prefs
    }

    /**
     * Fetch whether this member's phone gets timer/reminder pushes. The result
     * callback runs on a background thread — marshal to the UI thread yourself.
     * Defaults to true (server default) on any error.
     */
    fun getReminderPref(familyMemberId: String, onResult: (Boolean) -> Unit) {
        if (familyMemberId.isBlank() || accountJwt() == null) {
            onResult(true)
            return
        }
        exec.execute {
            val enabled = try {
                val res = call("get_notification_preferences",
                    JSONObject().put("family_member_id", familyMemberId))
                val data = res.optJSONObject("data")
                // Absent field / no row → default on.
                data?.optBoolean("notify_on_reminder", true) ?: true
            } catch (e: Exception) {
                Log.w(TAG, "getReminderPref failed: ${e.message}")
                true
            }
            onResult(enabled)
        }
    }

    /** Persist the member's timer/reminder-push preference. Fire-and-forget. */
    fun setReminderPref(familyMemberId: String, enabled: Boolean) {
        if (familyMemberId.isBlank() || accountJwt() == null) {
            Log.d(TAG, "setReminderPref skipped — no member id / account JWT")
            return
        }
        exec.execute {
            try {
                call("set_notification_preferences", JSONObject()
                    .put("family_member_id", familyMemberId)
                    .put("notify_on_reminder", enabled))
                Log.i(TAG, "setReminderPref ok ($familyMemberId → $enabled)")
            } catch (e: Exception) {
                Log.w(TAG, "setReminderPref failed: ${e.message}")
            }
        }
    }

    // ---- edge-function call (same shape as FamilyAlertNotifier.call) ----

    private fun call(operation: String, data: JSONObject): JSONObject {
        val jwt = accountJwt() ?: throw IOException("no account JWT")
        if (BuildConfig.SUPABASE_URL.isBlank()) throw IOException("no SUPABASE_URL in this flavor")
        val body = JSONObject().put("operation", operation).put("data", data)
            .toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url(BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/database-operations")
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer $jwt")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw IOException("$operation HTTP ${resp.code}: ${text.take(200)}")
            val parsed = JSONObject(text)
            if (!parsed.optBoolean("success")) throw IOException(parsed.optString("error", "$operation failed"))
            return parsed
        }
    }

    private fun accountJwt(): String? {
        val conn = prefs?.connection ?: return null
        return if (conn.hasSupabaseJwt && !conn.isSupabaseJwtExpired) conn.supabaseJwt else null
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val TAG = "NotificationPrefsClient"
}

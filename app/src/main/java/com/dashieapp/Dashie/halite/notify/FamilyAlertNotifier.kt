package com.dashieapp.Dashie.halite.notify

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.util.StableDeviceId
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Sends a mobile push to the family's phones when a timer or a scheduled
 * reminder/alarm FIRES on this device.
 *
 * This is the device half of the reuse of the location-arrival alert pathway:
 * we call the `notify_family_alert` op on the `database-operations` edge fn with
 * this device's account JWT; the server enumerates the household's members who
 * have the mobile app (apns_token) and have not opted out, then fans out to the
 * internal gps-push-location function (APNs). iOS-only today.
 *
 * Called from the actual FIRE paths so exactly the device that rang notifies:
 *   - reminders/alarms → ScheduledActionManager.onFire (the TYPE_NOTIFY branch)
 *   - timers           → TimerOverlayManager.updateTimer (RUNNING→COMPLETED, deduped)
 *
 * Independent of ScheduleCloudMirror ownership on purpose: the mirror only runs
 * on kiosk devices, but a reminder can fire on a full dashboard device too, and
 * that device must still notify the phones.
 *
 * Fire-and-forget on a daemon thread. An anonymous kiosk (no account JWT) is a
 * no-op — there is no household to fan out to.
 *
 * Payload contract with the edge op — see .reference/JS_KOTLIN_CONTRACTS.md.
 * The `ref_id` is the stable per-fire handle (reminder local_id / timer id) that
 * a future "dismiss from the app" ack will target; keep it populated.
 */
object FamilyAlertNotifier {

    const val SOURCE_TIMER = "timer"
    const val SOURCE_REMINDER = "reminder"
    const val SOURCE_ALARM = "alarm"

    @Volatile private var appContext: Context? = null
    @Volatile private var prefs: HalitePreferences? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val exec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "family-alert-notifier").apply { isDaemon = true }
    }

    fun wire(context: Context, prefs: HalitePreferences) {
        this.appContext = context.applicationContext
        this.prefs = prefs
    }

    /**
     * Fan out a "timer/reminder fired" push to the family. Fire-and-forget;
     * failures are logged, never thrown (the local alarm/card already fired).
     *
     * @param source one of SOURCE_TIMER / SOURCE_REMINDER / SOURCE_ALARM
     * @param refId  stable per-fire id (reminder local_id / timer id); may be blank
     * @param title  notification title shown on the phone
     * @param body   notification body shown on the phone
     */
    fun notifyFamily(source: String, refId: String?, title: String, body: String) {
        if (title.isBlank() || body.isBlank()) {
            Log.w(TAG, "notifyFamily skipped — blank title/body (source=$source)")
            return
        }
        if (accountJwt() == null) {
            Log.d(TAG, "notifyFamily $source skipped — no account JWT (anonymous kiosk / not linked)")
            return
        }
        exec.execute {
            try {
                val data = JSONObject()
                    .put("source", source)
                    .put("ref_id", refId ?: JSONObject.NULL)
                    .put("device_id", deviceId())
                    .put("alert_title", title)
                    .put("alert_body", body)
                val res = call("notify_family_alert", data)
                Log.i(TAG, "notifyFamily $source ok (ref=$refId, pushed=${res.optInt("pushed", 0)})")
            } catch (e: Exception) {
                // Non-critical: the local device already alerted; the phone push is best-effort.
                Log.w(TAG, "notifyFamily $source failed: ${e.message}")
            }
        }
    }

    // ---- edge-function call (account JWT; same header shape + success envelope as
    //      ScheduleCloudMirror.call() — the proven native database-operations caller) ----

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

    private fun deviceId(): String = appContext?.let { StableDeviceId.read(it) } ?: ""

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val TAG = "FamilyAlertNotifier"
}

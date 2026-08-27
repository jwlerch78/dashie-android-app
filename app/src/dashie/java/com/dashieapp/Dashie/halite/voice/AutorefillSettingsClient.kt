package com.dashieapp.Dashie.halite.voice

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.HalitePreferences
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Writes the account's auto-replenish on/off state to `database-operations.set_autorefill`
 * (WS-L.3 P2 — the native kill switch required on every flavor, Amazon included).
 *
 * ⚠️ This is the FIRST native settings item that writes to an edge function — every other
 * one persists to SharedPreferences via HaliteSettingsValueProvider. Auto-refill is
 * deliberately NOT mirrored into a local pref: the server row is the only source of truth
 * (the console writes it too), and a local copy would be one more thing to drift. The
 * settings toggle therefore renders from the CreditStateHolder read-cache and writes
 * through here, with the schema's async toggle interceptor snapping the switch back on
 * failure.
 *
 * Header shape + success-envelope checking mirror ScheduleCloudMirror.call() — the proven
 * native database-operations caller.
 */
object AutorefillSettingsClient {
    private const val TAG = "AutorefillSettings"
    private val JSON = "application/json".toMediaType()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Turn auto-replenish [enabled] on/off for the signed-in account.
     *
     * [onResult] fires on a background thread with `(ok, errorMessage)`. On success the
     * cached state is updated immediately so the settings row re-renders without waiting
     * for the next balance read.
     *
     * Enabling can legitimately FAIL server-side ("Buy a credit pack once to save a card
     * before enabling auto-replenish") — that error text is passed through verbatim so the
     * UI can show the real reason rather than a generic failure.
     */
    fun setEnabled(context: Context, enabled: Boolean, onResult: (ok: Boolean, error: String?) -> Unit) {
        val jwt = accountJwt(context)
        if (jwt == null) {
            Log.w(TAG, "DROP: set_autorefill skipped — no valid account JWT")
            onResult(false, "You're not signed in on this device.")
            return
        }
        if (BuildConfig.SUPABASE_URL.isBlank()) {
            Log.w(TAG, "DROP: set_autorefill skipped — no SUPABASE_URL in this flavor")
            onResult(false, "Not available in this build.")
            return
        }

        val body = JSONObject()
            .put("operation", "set_autorefill")
            .put("data", JSONObject().put("enabled", enabled))
            .toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url(BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/database-operations")
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer $jwt")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "set_autorefill(enabled=$enabled) failed: ${e.message}")
                onResult(false, "Couldn't reach Dashie. Check the connection and try again.")
            }

            override fun onResponse(call: Call, response: Response) {
                val text = try { response.body?.string() ?: "{}" } catch (_: Exception) { "{}" }
                val code = response.code
                runCatching { response.close() }
                val parsed = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
                // db-ops wraps every handler in { success, ...result }; a thrown handler error
                // surfaces as success=false + a human-readable `error` (e.g. the no-card case).
                if (code !in 200..299 || !parsed.optBoolean("success")) {
                    val err = parsed.optString("error", "").ifBlank { "Couldn't update auto-refill (HTTP $code)." }
                    Log.w(TAG, "set_autorefill(enabled=$enabled) rejected: $err")
                    onResult(false, err)
                    return
                }
                // Trust the server's echoed state over our request when present.
                val applied = if (parsed.has("enabled")) parsed.optBoolean("enabled") else enabled
                CreditStateHolder.setAutorefillEnabledLocal(applied)
                Log.i(TAG, "💳 auto-refill ${if (applied) "ENABLED" else "DISABLED"} from device settings")
                onResult(true, null)
            }
        })
    }

    private fun accountJwt(context: Context): String? {
        val conn = try { HalitePreferences(context).connection } catch (_: Exception) { return null }
        return if (conn.hasSupabaseJwt && !conn.isSupabaseJwtExpired) conn.supabaseJwt else null
    }
}

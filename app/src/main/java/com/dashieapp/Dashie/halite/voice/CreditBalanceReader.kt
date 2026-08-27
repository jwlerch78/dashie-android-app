package com.dashieapp.Dashie.halite.voice

import android.util.Log
import com.dashieapp.Dashie.BuildConfig
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
 * On-demand `get_credit_balance` read into [CreditStateHolder] (FB25.5).
 *
 * The holder's cache is normally refreshed as a side effect of brain turns
 * (metadata.credit, CR-b) — which means it's EMPTY until the first voice turn of
 * the session. The control center's balance line needs the number at open time,
 * so this does the same read the Console's CreditsService does, against
 * database-operations under the cached account JWT. Fire-and-forget + throttled:
 * a failure just leaves the cache as-is (the line doesn't render), and repeat
 * opens inside the TTL reuse the cached value.
 *
 * Only the BALANCE is written back — spendable/low stay owned by the server's
 * turn metadata (the floor/low thresholds are server-side config; this read
 * doesn't know them).
 */
object CreditBalanceReader {
    private const val TAG = "CreditBalanceReader"
    private const val MIN_REFRESH_INTERVAL_MS = 30_000L
    // Client-side UX thresholds mirroring the server runtime_config (voice_credit_floor / _low).
    // Floor $0.02 = "under two cents is empty" (matches the "$0"/"No Credits" display cutoff).
    // Single-sourced from CreditStateHolder (like CREDIT_LOW below) so the reader's floor,
    // CloudActivationState's hasCredits check and StarterGrantClient can't drift apart.
    private const val CREDIT_FLOOR = CreditStateHolder.FLOOR_USD   // at/below → out of credits
    // Single-sourced from CreditStateHolder so the reader's threshold and the derived
    // CreditStateHolder.low can't drift apart.
    private const val CREDIT_LOW = CreditStateHolder.LOW_USD   // at/below → low-balance heads-up

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile private var lastFetchMs = 0L

    /** Kick a background balance read under [jwt] (the cached account JWT).
     *  No-ops without a JWT (anonymous kiosk) or inside the throttle window. */
    fun refreshAsync(jwt: String, force: Boolean = false) {
        if (jwt.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastFetchMs < MIN_REFRESH_INTERVAL_MS) return
        lastFetchMs = now

        val body = JSONObject()
            .put("operation", "get_credit_balance")
            .put("data", JSONObject())
        val request = Request.Builder()
            .url("${BuildConfig.SUPABASE_URL}/functions/v1/database-operations")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $jwt")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "balance read failed (cache unchanged): ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        Log.d(TAG, "balance read HTTP ${it.code} (cache unchanged)")
                        return
                    }
                    try {
                        val json = JSONObject(it.body?.string() ?: "{}")
                        val balance = json.optDouble("balance", Double.NaN)
                        val lifetime = json.optDouble("lifetime_granted", 0.0)
                        // "When a credit balance exists": an account that never had
                        // credits reads 0/0 — leave the cache null so no line renders.
                        if (!balance.isNaN() && (balance > 0.0 || lifetime > 0.0)) {
                            // FB27: infer low/spendable from the balance so the low-credit UX
                            // (Account dot, credits-low badge, out-of-credits red) shows the
                            // moment the balance loads — WITHOUT waiting for a completed brain
                            // turn (which the FB20 connect hiccup can prevent). Client-side
                            // thresholds mirror the server defaults (floor $0, low $1); the
                            // server gate stays authoritative, this only drives UX.
                            CreditStateHolder.update(
                                spendable = balance > CREDIT_FLOOR,
                                low = balance <= CREDIT_LOW,
                                balance = balance,
                            )
                            Log.i(TAG, "💳 balance refreshed: $balance (low=${balance <= CREDIT_LOW}, spendable=${balance > CREDIT_FLOOR})")
                        }
                        // WS-L.3: auto-refill failure state rides this same read (no extra
                        // endpoint/poll), so a declined card surfaces on an idle tablet.
                        // Read UNCONDITIONALLY — outside the balance>0 guard above, because a
                        // declined card is exactly the case where the balance may be 0/absent.
                        CreditStateHolder.updateAutorefill(
                            failed = json.optBoolean("autorefill_failed", false),
                            error = json.optString("autorefill_error", "").ifBlank { null },
                            errorAt = json.optString("autorefill_error_at", "").ifBlank { null },
                            enabled = json.optBoolean("autorefill_enabled", false),
                            threshold = json.optDouble("autorefill_threshold", Double.NaN)
                                .takeIf { !it.isNaN() },
                            topup = json.optDouble("autorefill_topup", Double.NaN)
                                .takeIf { !it.isNaN() },
                        )
                    } catch (e: Exception) {
                        Log.d(TAG, "balance parse failed: ${e.message}")
                    }
                }
            }
        })
    }
}

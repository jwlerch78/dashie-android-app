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
 * Claims the one-time STARTER GRANT — the credits that let a user's FIRST cloud/hybrid
 * Voice & AI activation succeed instead of hitting the "you need credits" wall.
 *
 * Decided 2026-07-29 (dashieapp_staging `.reference/build-plans/
 * 20260726_CHICKADEE_TO_DASHIE_TRANSITION.md` §"Starter credit grant"). Called from the
 * blocked-preset tap in SettingsCallbackWiring, ahead of the add-credits dialog.
 *
 * ## Two properties worth not breaking
 *
 * **The AMOUNT comes from the server, never from here.** It lives in
 * `runtime_config.starter_grant` and is rendered from [Result.amountUsd]. A figure
 * compiled into the APK would outlive every later change on any tablet whose owner
 * hasn't updated — the same old-build/new-server skew the compat rules exist to prevent.
 * There is deliberately no default amount constant in this file.
 *
 * **Retry is safe and needs no client bookkeeping.** The server keys the grant to the
 * account (`credit_grants.source_ref`, unique index), so a replay returns
 * `already_claimed` rather than a second grant. This client therefore keeps no
 * "already claimed" flag — a local flag would be wrong across a factory reset, a second
 * tablet on the same household, or cleared app data, and would be one more thing to drift.
 */
object StarterGrantClient {

    private const val TAG = "StarterGrant"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * @param granted   true only when THIS call minted the credits (first claim).
     * @param amountUsd what the server granted, for display. 0 unless [granted].
     * @param balanceUsd the account's balance after the call — authoritative, and set on
     *   every non-error outcome so a caller can decide what to do without a second read.
     * @param usable    convenience: the balance is enough to actually run a cloud turn.
     *   Covers the `already_claimed`-but-still-funded case, where the user is fine and
     *   should NOT be shown an add-credits prompt.
     */
    data class Result(
        val granted: Boolean,
        val amountUsd: Double,
        val balanceUsd: Double,
        val reason: String,
    ) {
        val usable: Boolean get() = balanceUsd >= CreditStateHolder.FLOOR_USD
    }

    /**
     * Claim (or discover we already claimed) the starter grant. [onResult] gets null when
     * the call could not be completed — network, HTTP error, or an unparseable body.
     *
     * Null means "don't know", NOT "no grant": the caller must fall back to its existing
     * behaviour (the add-credits dialog) rather than telling the user anything about
     * credits it can't substantiate. Callback fires on an OkHttp thread — marshal to the
     * UI thread before touching views.
     */
    fun claim(jwt: String, onResult: (Result?) -> Unit) {
        if (jwt.isEmpty()) { onResult(null); return }

        val body = JSONObject()
            .put("operation", "claim_starter_grant")
            .put("data", JSONObject())
        val request = Request.Builder()
            .url("${BuildConfig.SUPABASE_URL}/functions/v1/database-operations")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $jwt")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "DROP: starter-grant claim failed (${e.message}) — falling back to add-credits")
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body?.string()
                    if (!it.isSuccessful) {
                        Log.w(TAG, "DROP: starter-grant claim HTTP ${it.code}: ${text?.take(160)}")
                        onResult(null); return
                    }
                    val json = try { text?.let(::JSONObject) } catch (e: Exception) { null }
                    if (json == null) {
                        Log.w(TAG, "DROP: starter-grant claim returned unparseable body")
                        onResult(null); return
                    }
                    val granted = json.optBoolean("granted", false)
                    val result = Result(
                        granted = granted,
                        amountUsd = json.optDouble("amount", 0.0).takeIf { a -> !a.isNaN() } ?: 0.0,
                        balanceUsd = json.optDouble("balance", 0.0).takeIf { b -> !b.isNaN() } ?: 0.0,
                        reason = json.optString("reason", ""),
                    )
                    Log.i(TAG, "starter grant: granted=$granted reason=${result.reason} " +
                        "amount=${result.amountUsd} balance=${result.balanceUsd}")

                    // Keep the shared cache honest: the balance just moved (or was just
                    // confirmed), and every credit surface — the Account page section, the
                    // preset gate, the low-credit pill — reads CreditStateHolder, not us.
                    // Without this the grant would land server-side while the tablet still
                    // believed it had nothing.
                    CreditStateHolder.update(
                        spendable = result.usable,
                        low = result.balanceUsd <= CreditStateHolder.LOW_USD,
                        balance = result.balanceUsd,
                    )
                    onResult(result)
                }
            }
        })
    }
}

package com.dashieapp.Dashie.halite.auth

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
 * Renews this device's Supabase JWT **natively**, with no WebView involved.
 *
 * ## Why this has to exist
 * Today the ONLY thing that ever writes `connection.supabaseJwt` is
 * [com.dashieapp.Dashie.halite.SupabaseTokenExtractor], which scrapes it out of the **dashboard
 * WebView's** localStorage — the dashboard JS owns the refresh loop (`edge-client.js`). That works
 * for a full-app device and is useless for any device whose WebView isn't hosting the dashboard:
 *
 *  - a **kiosk** (WebView shows Home Assistant), and
 *  - an **`ha_only` account's device** (forced into kiosk mode before the URL is even computed).
 *
 * Such a device therefore has **no refresh path at all**: whatever JWT it holds simply expires at
 * 72h and it silently falls back to anonymous. Phase 0 confirmed the mechanism in code and found
 * zero affected devices in the field *yet* — which is exactly why this ships alongside the thing
 * that creates the first ones. An appliance must not depend on its browser to stay signed in.
 *
 * ## Semantics
 * - **Proactive:** refresh when the token is within [REFRESH_WINDOW_MS] of expiry, not after it
 *   dies. A wall tablet may go days without anyone looking at it.
 * - **`device_revoked` / `account_deleted` are TERMINAL** — the server is telling us this session
 *   is over (the device was removed in the Console, or the account is gone). Clear the credential
 *   and go anonymous; a kiosk will then silently re-provision on its next boot if it's still
 *   allowed to. Retrying would be a retry-storm against a definitive "no".
 * - **Everything else is transient** (network, 5xx): keep the existing token and try again later.
 *   Never sign a device out over one bad minute — that's the fail-open rule the server side uses
 *   for the same reason.
 * - The renewed token **preserves the `device_id` claim**, so D5 revocation keeps applying across
 *   refreshes (the server does this; we just must not throw the identity away).
 */
object KioskJwtRefresher {

    private const val TAG = "KioskJwtRefresh"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Refresh when the token has less than this left. Generous: a kiosk may be idle for days. */
    private const val REFRESH_WINDOW_MS = 12L * 60 * 60 * 1000   // 12h

    /**
     * SAFETY NET ONLY — how often to re-verify the session absent any push.
     *
     * Why a liveness check exists at all: `refresh_jwt` is the ONLY endpoint that checks device
     * liveness (D5 — is this `device_id` still on the account?). Every other edge function accepts
     * a token on signature + `sub` alone. So a REVOKED kiosk (device removed, or D6's sharing-off
     * sweep) keeps working with full account access until it happens to refresh. With refresh
     * gated purely on expiry, that was **up to 72 hours** of access after the user was told the
     * tablets "will be signed out" — found on the first live D6 test (2026-07-13): the tablet said
     * NOT_DUE and sailed on with a dead token.
     *
     * Why 24h and not 1h: the normal path is a PUSH, not a poll. The add-on already fires
     * `dashie.refresh_voice_config` at every sharing toggle (settings.js), which the integration
     * relays to each kiosk's :2323 API — so a revoked kiosk finds out in SECONDS via
     * [verifySessionNow], with zero recurring cost. This interval only covers a kiosk that was
     * asleep/offline when the push went out. One cheap call per day per kiosk is a rounding error;
     * hourly polling would have been 24× that for no added safety.
     */
    private const val LIVENESS_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000   // 24h — safety net only

    /** A fresh token is good for 72h (jwt-auth `generateSupabaseJWT`). */
    private const val ASSUMED_TTL_MS = 72L * 60 * 60 * 1000

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    enum class Outcome { REFRESHED, NOT_DUE, NO_SESSION, REVOKED, TRANSIENT_FAILURE }

    /**
     * End the session completely when the server disowns it (D5 device removal, D6 sharing-off
     * sweep, or account deletion).
     *
     * Clearing the JWT alone is NOT enough — and that gap would have shipped. The credential lives
     * in ConnectionPreferences, but the native Account page renders off
     * `AccountPreferences.isLinked`/`email`. Clear only the credential and the tablet keeps
     * announcing "Signed in via Home Assistant — someone@example.com" while actually being signed
     * out. That is the same class of lie D6 exists to remove, so the identity goes too.
     *
     * `haOnlyDisplay` deliberately STAYS: it is a property of the DEVICE (this tablet shows Home
     * Assistant), not of the session. Keeping it means the display never flickers to a dashboard
     * on the way out, and a later re-provision already has D2 set.
     */
    private fun endSession(prefs: HalitePreferences) {
        prefs.connection.clearSupabaseJwt()
        val account = prefs.account
        account.isLinked = false
        account.email = ""
        account.authUserId = ""
        // This was a direct voice-only account no longer (see AccountPageSchema).
        account.haOnlyVoiceSignup = false

        // The credit balance + cloud pipeline belonged to the now-revoked account.
        // Without wiping them the tablet keeps showing the ex-account's credits and
        // keeps running cloud STT/TTS/AI (field report 2026-07-22: "disconnected
        // sharing but it kept the credits and cloud access going"). Reset the cache
        // and revert providers to HA Voice Assist. The caller reinits the running
        // voice controller on Outcome.REVOKED.
        com.dashieapp.Dashie.halite.voice.CreditStateHolder.reset()
        com.dashieapp.Dashie.halite.settings.schema.wiring.VoicePresetSeeder.revertOffCloud(prefs.voice)

        // ...and the THIRD place the session lives, since Phase 2: the shell WebView. The JS
        // settings stack holds its own EdgeClient with the token in memory and in localStorage.
        // Clearing only the Kotlin prefs would leave it reading calendars and spending credits
        // until the page happened to reload — §10 bug 4 (a revoked kiosk keeping full account
        // access) reintroduced one layer up. Revocation that signs out half the device is worse
        // than one that signs out none: it looks like it worked.
        KioskSessionInjector.clear()
    }

    /**
     * A device with NO credential must not go on announcing an account.
     *
     * ## Why nothing was catching this
     * `isLinked`/`email` are plain stored values, independent of whether a credential exists,
     * and the Account page renders off them. [endSession] clears both together — but several
     * OTHER paths set `isLinked = true` without one (`onDashieAuthComplete`, and the
     * voice-only sign-in branch that literally logs "linked without credential"). Once that
     * happens the tablet says "Signed in — someone@example.com" while holding nothing.
     *
     * The reason no TTL rescued it: every liveness path — the sharing-toggle push, the ~30 min
     * HA-token-rotation trigger, the 24h safety net — funnels into [refreshIfStale], which
     * returns NO_SESSION the moment `supabaseJwt` is empty. **The checks are gated on having
     * the very thing whose absence is the problem**, so the inconsistent state was structurally
     * unreachable by them. Observed 2026-07-31 on the Samsung: revoked cleanly, credential
     * gone, zero spend — and still displaying the account hours later.
     *
     * So the reconcile goes HERE, on the empty-JWT branch: the one place that runs on every
     * existing cadence and previously did nothing at all.
     *
     * Clearing is always the right answer. There is no benign "linked but no credential yet"
     * window to protect: [KioskSessionProvisioner] writes the credential BEFORE the identity,
     * and the voice-only branch only links credential-less after token extraction has already
     * failed. Both would be re-established by the next successful provision.
     */
    private fun reconcileStaleIdentity(prefs: HalitePreferences) {
        val account = prefs.account
        if (!account.isLinked && account.email.isEmpty()) return   // already coherent

        Log.w(
            TAG,
            "DROP: stale identity — no credential but account still linked as " +
            "'${account.email}'. Clearing; the device was announcing an account it cannot " +
            "authenticate as. (Something set isLinked without a credential — see the " +
            "IDENTITY-SET markers.)"
        )
        account.isLinked = false
        account.email = ""
        account.authUserId = ""
    }

    /**
     * Verify the session RIGHT NOW, ignoring both the expiry window and the safety-net interval.
     *
     * This is the push-driven path, and it's the one that actually matters. The add-on fires
     * `dashie.refresh_voice_config` whenever household sharing is toggled; the integration relays
     * it to each kiosk's :2323 API (`refreshVoiceConfig`). That command already exists and already
     * arrives — so a kiosk that was just revoked by D6's sweep discovers it in SECONDS, and we pay
     * NOTHING for it: no polling, no new endpoint, no extra periodic edge call. We're simply
     * asking a question we were already being told the answer to.
     *
     * Safe to call on a device with no session (no-ops).
     */
    fun verifySessionNow(prefs: HalitePreferences, context: Context? = null, onDone: (Outcome) -> Unit = {}) {
        // Zeroing the stamp makes the liveness gate in refreshIfStale fire unconditionally.
        if (prefs.connection.supabaseJwt.isNotEmpty()) prefs.connection.sessionCheckedAtMs = 0L
        refreshIfStale(prefs, context, onDone)
    }

    /**
     * Refresh the stored JWT when it's near expiry, AND verify the session is still alive when the
     * safety-net interval has elapsed. Best-effort, async, never throws. Safe to call often.
     *
     * The liveness half is not optional: `refresh_jwt` is the ONLY endpoint that checks whether
     * this device is still on the account (D5). Refreshing only near expiry meant a revoked kiosk
     * kept full account access for up to 72h — see [LIVENESS_CHECK_INTERVAL_MS]. The fast path is
     * [verifySessionNow], driven by the existing sharing-toggle push.
     */
    fun refreshIfStale(prefs: HalitePreferences, context: Context? = null, onDone: (Outcome) -> Unit = {}) {
        val conn = prefs.connection
        val jwt = conn.supabaseJwt
        if (jwt.isEmpty()) {
            reconcileStaleIdentity(prefs)
            onDone(Outcome.NO_SESSION); return
        }

        val now = System.currentTimeMillis()
        val expiry: Long = conn.supabaseJwtExpiry
        val msLeft: Long = expiry - now

        val nearExpiry = expiry <= 0L || msLeft <= REFRESH_WINDOW_MS
        val livenessDue = (now - conn.sessionCheckedAtMs) >= LIVENESS_CHECK_INTERVAL_MS

        if (!nearExpiry && !livenessDue) {
            onDone(Outcome.NOT_DUE)
            return
        }

        Log.i(TAG, "Refreshing device JWT natively (${msLeft / 3_600_000}h left)")

        // Offer our StableDeviceId so the server can HEAL a legacy throwaway `device_id`
        // claim into a real identity (20260731_SHARING_REVOCATION_LEAK.md §3a Phase 1).
        //
        // A claim is set at MINT and then preserved verbatim by every refresh, so a session
        // minted with `firetv-<ts>-<rand>` — which matches no user_devices row — keeps that
        // unresolvable identity across unlimited 72h renewals and can never be revoked by
        // anything. Refresh is the one call every live device already makes on a bounded
        // cadence, which makes it the migration vehicle: no forced re-auth, no user action.
        //
        // The server ignores this unless the CURRENT claim is throwaway-shaped, so it is
        // inert for kiosks (already stable, via KioskSessionProvisioner) and for any session
        // already healed. Sending it costs one field on a call we were making anyway.
        // `context` is optional so this stays a pure ADD for existing callers — the refresh
        // itself never depended on one, and making it required would have rippled a
        // behaviour change into call sites that have nothing to do with revocation.
        val stableId = context?.let { com.dashieapp.Dashie.util.StableDeviceId.read(it) }.orEmpty()
        if (context == null) {
            // Not silent: without a Context we cannot offer an identity, so a throwaway
            // session refreshing through this path stays unrevocable for another cycle.
            Log.w(TAG, "DROP: refresh without a Context — cannot offer StableDeviceId, heal skipped")
        }
        val body = JSONObject().apply {
            put("operation", "refresh_jwt")
            if (stableId.isNotEmpty()) {
                put("data", JSONObject().apply { put("device_id", stableId) })
            }
        }.toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url("${BuildConfig.SUPABASE_URL}/functions/v1/jwt-auth")
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer $jwt")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Transient — keep the token we have. It may still be valid for hours.
                Log.w(TAG, "JWT refresh failed (keeping current token): ${e.message}")
                onDone(Outcome.TRANSIENT_FAILURE)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body?.string()
                    val json = try { text?.let(::JSONObject) } catch (e: Exception) { null }
                    val error = json?.optString("error").orEmpty()

                    // TERMINAL: the server is definitively ending this session.
                    //   device_revoked  — removed in the Console (D5), or household sharing was
                    //                     turned off and the kiosk rows were swept (D6).
                    //   account_deleted — the account no longer exists.
                    // Clearing is the CORRECT response: a kiosk then re-provisions silently on
                    // its next boot IF it's still permitted, and stays anonymous if it isn't.
                    // (This is also the fix for the "out of voice credits" ghost-account bug:
                    // never keep using a credential the server has disowned.)
                    //   sharing_revoked — D's sharing-liveness check (2026-08-02): the household
                    //                     withdrew the grant this session was provisioned under.
                    //                     Kept DISTINCT from device_revoked rather than folded
                    //                     into it: collapsing them would re-lose the "which of
                    //                     the two ended it" signal that made the 2026-07-13
                    //                     incident debuggable. Same terminal ACTION, different
                    //                     cause, and the log says which.
                    if (it.code == 401 &&
                        (error == "device_revoked" || error == "account_deleted" ||
                            error == "sharing_revoked")
                    ) {
                        Log.w(TAG, "Session ended by server ($error) — signing out, going anonymous")
                        endSession(prefs)
                        onDone(Outcome.REVOKED)
                        return
                    }

                    // 🔴 Any OTHER 401. Before this existed, an unrecognised 401 code fell through
                    // to the generic !isSuccessful branch below and was reported as
                    // TRANSIENT_FAILURE — so the device KEPT the credential and retried a session
                    // the server had definitively ended. Not silent (that branch logs), but
                    // misclassified, which is worse: a terminal refusal read as retryable never
                    // resolves, and there was no grep-able marker to find it by.
                    //
                    // Fails toward TERMINAL deliberately. A 401 the server did not have to send is
                    // a definite refusal; treating an unknown one as transient is what let
                    // sharing_revoked have been mishandled for a whole enforcement window. Going
                    // anonymous is recoverable — a kiosk re-provisions on its next boot if it is
                    // still permitted — whereas retrying a dead credential is not.
                    if (it.code == 401) {
                        Log.w(TAG, "DROP: unrecognised 401 error code '$error' from the refresh " +
                            "endpoint — treating as TERMINAL and ending the session. If this is a " +
                            "new server-side code, add it to the terminal list above (and to " +
                            "whatever gate covers the code vocabulary) rather than leaving it here.")
                        endSession(prefs)
                        onDone(Outcome.REVOKED)
                        return
                    }

                    // A scoped token can't be refreshed — we should never be sending one here.
                    if (it.code == 403 && error == "scoped_token") {
                        Log.e(TAG, "BUG: tried to refresh a SCOPED token — clearing it")
                        endSession(prefs)
                        onDone(Outcome.REVOKED)
                        return
                    }

                    val newJwt = json?.optString("jwtToken").orEmpty()
                    if (!it.isSuccessful || newJwt.isEmpty()) {
                        Log.w(TAG, "JWT refresh HTTP ${it.code} (keeping current token): ${text?.take(160)}")
                        onDone(Outcome.TRANSIENT_FAILURE)
                        return
                    }

                    conn.supabaseJwt = newJwt
                    conn.supabaseJwtExpiry = System.currentTimeMillis() + ASSUMED_TTL_MS
                    // The server just CONFIRMED this device is still on the account (it would have
                    // returned device_revoked otherwise). Stamp it so the next liveness check is
                    // an hour out rather than on every HA-token cycle.
                    conn.sessionCheckedAtMs = System.currentTimeMillis()
                    json?.optJSONObject("user")?.optString("id")?.takeIf { id -> id.isNotEmpty() }
                        ?.let { id -> conn.supabaseUserId = id }

                    // Hand the renewed token to the shell's EdgeClient (Phase 2). It runs with the
                    // JS refresh timer OFF (`externallyManaged`) precisely because WE are the
                    // refresher — so if we don't push, its in-memory token quietly ages out.
                    KioskSessionInjector.push(prefs)

                    Log.i(TAG, "✅ Session verified + JWT refreshed natively (no WebView)")
                    onDone(Outcome.REFRESHED)
                }
            }
        })
    }
}

package com.dashieapp.Dashie.halite.auth

import com.dashieapp.Dashie.edition.ApiPaths

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.AccountPreferences
import com.dashieapp.Dashie.util.StableDeviceId
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
 * Gives an ANONYMOUS Home-Assistant kiosk tablet a REAL Dashie account session — with no human
 * touching the tablet (Kiosk Real Login, Phase 1).
 *
 * ## Why
 * Until now a kiosk could never be logged in: [com.dashieapp.Dashie.halite.SupabaseTokenExtractor]
 * is the ONLY writer of `connection.supabaseJwt` and it scrapes the JWT out of the **WebView's**
 * localStorage — but a kiosk's WebView shows Home Assistant, not the Dashie dashboard. So every
 * account-scoped feature had to be hand-mirrored across a 5-hop chain (the voice-config mirror),
 * which is O(features) and leaked bugs at every hop. A logged-in device is O(1): calendars,
 * chores, photos, settings and credits all work through the ordinary account paths.
 *
 * ## The flow (all proven server-side before this class existed)
 * ```
 *   1. create_device_code (device_type='ha_kiosk')        → Dashie cloud, unauthenticated
 *   2. POST {ha}/api/dashie/account/authorize {user_code} → HA-token authed; the ADD-ON, which
 *                                                            holds the household account JWT,
 *                                                            claims the code for its account
 *   3. poll_device_code_status (+ our StableDeviceId)     → our OWN per-device JWT
 *   4. store it → the tablet IS logged in
 * ```
 * The account credential never passes through the tablet, and our session token never passes
 * through the add-on — each hop only ever holds its own.
 *
 * ## Notes that are load-bearing
 * - **We send our [StableDeviceId]** at poll time. The server stamps it into the JWT's `device_id`
 *   claim AND writes the matching `user_devices` row **atomically with the mint** — that pairing is
 *   what makes "remove this device in the Console" actually kill the session (D5). Do not stop
 *   sending it: without it the server falls back to a throwaway id and the device becomes
 *   unrevocable.
 * - **No `register_device` call here.** The server already created the row. The normal registration
 *   path may still enrich it later (name/metadata) — it looks devices up by `device_id` first, so
 *   it finds ours rather than conflicting.
 * - **Sharing-gated.** If household sharing is off, step 2 returns 403 and we stay anonymous —
 *   that's the off-switch working, not an error to retry around.
 * - Sibling of [com.dashieapp.Dashie.halite.voice.stt.SttSessionTokenClient] /
 *   [com.dashieapp.Dashie.halite.voice.DashieCloudCapabilityClient]: native OkHttp, because we
 *   hold the HA origin/token in native prefs and there is no dashboard WebView to borrow.
 */
object KioskSessionProvisioner {

    private const val TAG = "KioskProvisioner"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** A JWT is good for 72h; treat it as ours for that long minus the server's own skew. */
    private const val ASSUMED_TTL_MS = 72L * 60 * 60 * 1000

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Why a provisioning attempt ended — callers log/telemeter, they do not branch on it. */
    enum class Outcome { PROVISIONED, ALREADY_LOGGED_IN, SHARING_OFF, NOT_ELIGIBLE, FAILED }

    /**
     * Provision this kiosk into the household account, if it isn't already logged in.
     *
     * Best-effort and fully async — never blocks a caller, never throws. Safe to call on every
     * boot: it no-ops when the device already holds a session.
     *
     * @param onDone invoked on an OkHttp background thread with the outcome.
     */
    fun provisionIfNeeded(
        context: Context,
        prefs: HalitePreferences,
        onDone: (Outcome) -> Unit = {}
    ) {
        val conn = prefs.connection

        // Already logged in (this includes a full-app device) → nothing to do. A kiosk that has a
        // session keeps it; refreshing is KioskJwtRefresher's job, not ours.
        if (conn.hasSupabaseJwt && !conn.isSupabaseJwtExpired) {
            onDone(Outcome.ALREADY_LOGGED_IN)
            return
        }

        val haOrigin = conn.getHaOrigin()?.trimEnd('/').orEmpty()
        val haToken = conn.haAccessToken
        if (haOrigin.isBlank() || haToken.isBlank()) {
            // No HA box configured → this isn't a kiosk we can provision. Not an error.
            onDone(Outcome.NOT_ELIGIBLE)
            return
        }

        val deviceId = StableDeviceId.read(context)
        if (deviceId.isBlank()) {
            Log.w(TAG, "No StableDeviceId — cannot provision (the session would be unrevocable)")
            onDone(Outcome.FAILED)
            return
        }

        Log.i(TAG, "Provisioning kiosk session (device=${deviceId.take(8)}…)")
        createDeviceCode { codes ->
            if (codes == null) { onDone(Outcome.FAILED); return@createDeviceCode }
            val (deviceCode, userCode) = codes

            askHaToAuthorize(haOrigin, haToken, userCode) { authOutcome ->
                if (authOutcome != Outcome.PROVISIONED) { onDone(authOutcome); return@askHaToAuthorize }

                pollForSession(deviceCode, deviceId) { session ->
                    if (session == null) { onDone(Outcome.FAILED); return@pollForSession }

                    // 1. The CREDENTIAL — what edge-function calls authenticate with.
                    conn.supabaseJwt = session.jwt
                    conn.supabaseUserId = session.userId
                    conn.supabaseJwtExpiry = System.currentTimeMillis() + ASSUMED_TTL_MS

                    // 2. D2 FIRST, then the identity. Order matters and is not cosmetic.
                    //
                    // `isLinked` drives BOTH "show the account in Settings" AND "load the Dashie
                    // dashboard instead of Home Assistant" (AccountPreferences.showsDashboard, read
                    // by MainUrlHandler + 8 others). Setting isLinked without haOnlyDisplay would
                    // silently convert this wall tablet from an HA display into a Dashie dashboard
                    // on its next boot — the exact thing D2 exists to prevent
                    // ("logged in ≠ shows the dashboard").
                    //
                    // haOnlyDisplay is device-scoped precisely so the account's subscription tier
                    // can't flip it: forceKioskMode is cleared when the household subscribes, which
                    // would otherwise turn every kiosk into a dashboard that day.
                    val account = AccountPreferences(context)
                    account.haOnlyDisplay = true
                    // This is a household-SHARED kiosk (device_type='ha_kiosk'), not a
                    // direct voice-only login: it re-provisions on boot, so a local Sign
                    // Out wouldn't stick — the Account page must steer to the console. Clear
                    // the voice-only marker in case this device previously held one.
                    account.haOnlyVoiceSignup = false
                    // SESSION origin, distinct from the sticky display flag above. The Account
                    // page gates on this so a later ordinary login on the same tablet is not
                    // still described as "Signed in via Home Assistant" (Thread M, 2026-08-01).
                    account.kioskProvisionedSession = true

                    // 3. The IDENTITY — what the native Account page displays, and what Phase 2's
                    //    settings-sync client resolves its own user_settings/user_devices row from.
                    //    D1: a silent provision must not be an INVISIBLE one — the tablet should be
                    //    able to tell you whose account it joined.
                    account.email = session.email
                    account.authUserId = session.userId
                    account.isLinked = true

                    // 4. Hand the session to the SHELL (Phase 2). Native owns the credential, but
                    //    the settings stack lives in the WebView — without this push it would sit
                    //    inert until the next page load, which on a wall tablet can be days.
                    //    (This is the ON half of the toggle; §10 bug 8 was exactly the case of
                    //    wiring only the OFF half of a push that fires for both.)
                    KioskSessionInjector.push(prefs)

                    Log.i(TAG, "✅ Kiosk provisioned into account ${session.email} — logged in, still displaying Home Assistant")
                    onDone(Outcome.PROVISIONED)
                }
            }
        }
    }

    // ── Step 1: create a device code ────────────────────────────────────────────────────────

    private fun createDeviceCode(onResult: (Pair<String, String>?) -> Unit) {
        val body = JSONObject().apply {
            put("operation", "create_device_code")
            put("data", JSONObject().apply { put("device_type", DEVICE_TYPE) })
        }
        postToJwtAuth(body, bearer = BuildConfig.SUPABASE_ANON_KEY) { json ->
            val deviceCode = json?.optString("device_code").orEmpty()
            val userCode = json?.optString("user_code").orEmpty()
            if (deviceCode.isEmpty() || userCode.isEmpty()) {
                Log.w(TAG, "create_device_code returned no code")
                onResult(null)
            } else {
                onResult(deviceCode to userCode)
            }
        }
    }

    // ── Step 2: ask THIS HA box to authorize the code into its household account ─────────────

    private fun askHaToAuthorize(
        haOrigin: String,
        haToken: String,
        userCode: String,
        onResult: (Outcome) -> Unit
    ) {
        val payload = JSONObject().apply { put("user_code", userCode) }.toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url("$haOrigin${ApiPaths.HA}/account/authorize")
            .addHeader("Authorization", "Bearer $haToken")
            .post(payload)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "HA authorize failed: ${e.message}")
                onResult(Outcome.FAILED)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body?.string()
                    if (it.code == 403) {
                        // Household sharing is off (or the account revoked it). This is the
                        // intended off-switch — stay anonymous, don't retry-storm.
                        Log.i(TAG, "HA declined: household sharing is off — staying anonymous")
                        onResult(Outcome.SHARING_OFF)
                        return
                    }
                    if (!it.isSuccessful) {
                        Log.w(TAG, "HA authorize HTTP ${it.code}: ${text?.take(180)}")
                        onResult(Outcome.FAILED)
                        return
                    }
                    onResult(Outcome.PROVISIONED)
                }
            }
        })
    }

    // ── Step 3: poll for OUR session ────────────────────────────────────────────────────────

    private data class Session(val jwt: String, val userId: String, val email: String)

    private fun pollForSession(deviceCode: String, deviceId: String, onResult: (Session?) -> Unit) {
        val body = JSONObject().apply {
            put("operation", "poll_device_code_status")
            put("data", JSONObject().apply {
                put("device_code", deviceCode)
                // The stable id the server stamps into the JWT + the user_devices row (D5).
                put("device_id", deviceId)
            })
        }
        postToJwtAuth(body, bearer = BuildConfig.SUPABASE_ANON_KEY) { json ->
            if (json == null) { onResult(null); return@postToJwtAuth }

            val status = json.optString("status")
            if (status == "device_registration_failed") {
                // The server refused to create our user_devices row (e.g. device limit). It
                // deliberately does NOT hand back a credential it knows can't refresh, so this
                // is a clean failure rather than a 72h time-bomb.
                Log.w(TAG, "Provision refused: ${json.optString("message")}")
                onResult(null)
                return@postToJwtAuth
            }

            val jwt = json.optString("jwtToken")
            if (jwt.isEmpty()) {
                // Step 2 already authorized the code, so a pending status here means the
                // authorization didn't stick — surface it rather than spinning.
                Log.w(TAG, "poll returned no JWT (status=$status)")
                onResult(null)
                return@postToJwtAuth
            }
            val user = json.optJSONObject("user")
            onResult(
                Session(
                    jwt = jwt,
                    userId = user?.optString("id").orEmpty(),
                    email = user?.optString("email").orEmpty()
                )
            )
        }
    }

    // ── shared transport ────────────────────────────────────────────────────────────────────

    /** POST to the flavor's jwt-auth edge function. [onResult] gets the parsed body, or null. */
    private fun postToJwtAuth(body: JSONObject, bearer: String, onResult: (JSONObject?) -> Unit) {
        val request = Request.Builder()
            .url("${BuildConfig.SUPABASE_URL}/functions/v1/jwt-auth")
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer $bearer")
            .post(body.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "jwt-auth call failed: ${e.message}")
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body?.string()
                    if (text.isNullOrEmpty()) { onResult(null); return }
                    try {
                        onResult(JSONObject(text))
                    } catch (e: Exception) {
                        Log.w(TAG, "jwt-auth parse failed: ${e.message}")
                        onResult(null)
                    }
                }
            }
        })
    }

    /**
     * Distinct from `ha_app` (which marks an HA-VOICE-ONLY signup and drives `ha_only` account
     * gating). A kiosk is a DEVICE, not an account tier — using `ha_app` here would wrongly trip
     * that signup logic. The server also restricts account-JWT authorization to exactly this type.
     */
    private const val DEVICE_TYPE = "ha_kiosk"
}

package com.dashieapp.Dashie.halite.voice.stt

import com.dashieapp.Dashie.edition.ApiPaths

import android.util.Log
import com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient
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
 * Fetches a short-lived, STT-scoped credential from the Dashie integration —
 * `POST {haUrl}/api/dashie/voice/session` — so an ANONYMOUS kiosk tablet (no Dashie login)
 * can stream `deepgram-stt-stream` directly without holding the account's long-lived JWT.
 *
 * The integration (HA-token authed) gets the account credential from the add-on and mints the
 * token via the `issue-stt-token` edge fn. Sibling of [BrainConverseClient] /
 * [DashieCloudCapabilityClient]: native OkHttp (not a WebView fetch — cross-origin CORS + we
 * already hold the HA URL/token in native prefs). Build plan §3.2 (WS2) / §2.6 topology A.
 *
 * A logged-in device does NOT need this — it uses its own cached account JWT for STT
 * (see [SttCredentialProvider]).
 */
class SttSessionTokenClient {

    /** @param expiresInMs how long the token is valid FROM NOW (already relative). */
    data class Result(val sttToken: String, val expiresInMs: Long)

    private val client: OkHttpClient = LocalHostsTrustingHttpClient.builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Fetch a fresh STT token. [onResult] is invoked on an OkHttp background thread with the
     * parsed token, or null on any failure (unreachable / sharing off (403) / mint rejected).
     * A null result means the caller falls back to the legacy anon-key path (which only streams
     * while the proxy's `stt_auth_enforce` flag is OFF).
     */
    fun fetch(haOrigin: String, haToken: String, endpointId: String, onResult: (Result?) -> Unit) {
        if (haOrigin.isBlank() || haToken.isBlank()) {
            Log.w(TAG, "Missing HA origin/token — cannot fetch STT session token")
            onResult(null)
            return
        }
        val url = haOrigin.trimEnd('/') + "${ApiPaths.HA}/voice/session"
        val payload = JSONObject().apply { put("endpoint_id", endpointId) }
            .toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $haToken")
            .post(payload)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "STT session fetch failed: ${e.message}")
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyText = it.body?.string()
                    if (!it.isSuccessful || bodyText.isNullOrEmpty()) {
                        // 403 = household sharing off; 5xx = add-on/integration unreachable.
                        Log.w(TAG, "STT session HTTP ${it.code}: ${bodyText?.take(200)}")
                        onResult(null)
                        return
                    }
                    try {
                        val json = JSONObject(bodyText)
                        // `token` first, `stt_token` as the fallback (Thread D metering-client
                        // contract, 2026-08-01). `stt_token` is a MISNOMER kept for compat: the
                        // scope widened stt→voice on 2026-07-09 and the field name never
                        // followed (issue-stt-token/index.ts:162). The server dual-emits at v1,
                        // so reading both now is what keeps the rename window open — a client
                        // that only knows the old name pins the field forever.
                        val token = json.optString("token", "")
                            .ifEmpty { json.optString("stt_token", "") }
                        if (token.isEmpty()) {
                            Log.w(TAG, "DROP: STT session response carried neither `token` nor `stt_token`")
                            onResult(null)
                            return
                        }
                        // Prefer expires_in (seconds from now); fall back to a conservative default.
                        val expiresInSec = json.optLong("expires_in", DEFAULT_TTL_SEC)
                        Log.i(TAG, "STT session token fetched (ttl ${expiresInSec}s)")
                        onResult(Result(sttToken = token, expiresInMs = expiresInSec * 1000L))
                    } catch (e: Exception) {
                        Log.w(TAG, "STT session parse failed: ${e.message}")
                        onResult(null)
                    }
                }
            }
        })
    }

    companion object {
        private const val TAG = "SttSessionToken"
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val READ_TIMEOUT_MS = 8_000L
        private const val WRITE_TIMEOUT_MS = 5_000L
        private const val DEFAULT_TTL_SEC = 7_200L // matches issue-stt-token's 2h default
        private val JSON = "application/json".toMediaType()
    }
}

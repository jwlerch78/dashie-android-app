package com.dashieapp.Dashie.voice.realtime

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
 * Mints a Live-only Gemini ephemeral token for a BYOK Live session — BYOK-for-Live
 * (build plan 20260723_BYOK_LIVE_EPHEMERAL_TOKENS.md).
 *
 * `POST {haUrl}/api/dashie/voice/live-token` (HA-token authed). The Dashie integration forwards
 * to the add-on's `/api/keys/live-token`, which reads the household's stored Gemini key, calls
 * Google authTokens, and returns ONLY the ephemeral token — the RAW KEY NEVER LEAVES THE BOX.
 * The device brokers through the integration because the add-on is ingress-only (no LAN port).
 *
 * The device then sends `token` to the conversation-relay as the `x-dashie-live-token` HEADER on
 * the WS upgrade; the relay opens the BYOK upstream and skips the AI credit debit. On ANY failure
 * (box unreachable / no key / mint rejected → null result) the caller falls back to the Dashie-key
 * path — old APKs never send the header, so the relay defaults to Dashie's key.
 *
 * Sibling of [com.dashieapp.Dashie.halite.voice.stt.SttSessionTokenClient] — native OkHttp (not a
 * WebView fetch: cross-origin CORS + we already hold the HA URL/token in native prefs).
 */
class LiveTokenClient {

    /**
     * @param token the ephemeral token (`auth_tokens/…`) for the `x-dashie-live-token` header.
     * @param newSessionExpireAtMs epoch ms after which this token can no longer START a session
     *   (Google `newSessionExpireTime`, ~2 min). Past it, a reconnect must re-mint. 0 = unknown.
     */
    data class Result(val token: String, val newSessionExpireAtMs: Long)

    private val client: OkHttpClient = LocalHostsTrustingHttpClient.builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Fetch a fresh Live token. [onResult] runs on an OkHttp background thread with the parsed
     * token, or null on any failure (missing HA creds / unreachable / no_gemini_key 503 / mint 502).
     * A null result means the caller uses the Dashie-key path.
     */
    fun fetch(haOrigin: String?, haToken: String, model: String?, onResult: (Result?) -> Unit) {
        if (haOrigin.isNullOrBlank() || haToken.isBlank()) {
            Log.d(TAG, "Missing HA origin/token — cannot mint Live token (Dashie-key fallback)")
            onResult(null)
            return
        }
        val url = haOrigin.trimEnd('/') + "${ApiPaths.HA}/voice/live-token"
        val payload = JSONObject().apply { if (!model.isNullOrBlank()) put("model", model) }
            .toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $haToken")
            .post(payload)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Live token mint failed: ${e.message}")
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyText = it.body?.string()
                    if (!it.isSuccessful || bodyText.isNullOrEmpty()) {
                        // 503 no_gemini_key; 502 mint_failed; 5xx integration/add-on unreachable.
                        Log.w(TAG, "Live token HTTP ${it.code}: ${bodyText?.take(200)}")
                        onResult(null)
                        return
                    }
                    try {
                        val json = JSONObject(bodyText)
                        val token = json.optString("token", "")
                        if (token.isEmpty()) {
                            Log.w(TAG, "Live token response missing token")
                            onResult(null)
                            return
                        }
                        val startAt = parseIso8601Ms(json.optString("newSessionExpireTime", ""))
                        Log.i(TAG, "Live token minted (start window ends ${if (startAt > 0) "${(startAt - System.currentTimeMillis()) / 1000}s" else "?"})")
                        onResult(Result(token = token, newSessionExpireAtMs = startAt))
                    } catch (e: Exception) {
                        Log.w(TAG, "Live token parse failed: ${e.message}")
                        onResult(null)
                    }
                }
            }
        })
    }

    /** Parse an ISO-8601 instant (Google returns e.g. "2026-07-23T18:04:05.123Z") to epoch ms,
     *  or 0 on any parse failure (→ treated as "unknown", caller re-mints per open anyway). */
    private fun parseIso8601Ms(iso: String): Long {
        if (iso.isBlank()) return 0L
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    companion object {
        private const val TAG = "LiveTokenClient"
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val READ_TIMEOUT_MS = 8_000L
        private const val WRITE_TIMEOUT_MS = 5_000L
        private val JSON = "application/json".toMediaType()
    }
}

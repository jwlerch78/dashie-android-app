package com.dashieapp.Dashie.halite.auth

import android.util.Log
import com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Single canonical client for Home Assistant's OAuth token endpoint
 * (`POST <base>/auth/token`).
 *
 * Replaces three previously duplicated implementations (MainHaLoginHandler,
 * HaLoginActivity, HaTokenExtractor ×2) that had drifted in cert handling and
 * error reporting. Consolidated per the 2026-06-12 codebase review (item 0.3).
 *
 * Stateless and **blocking** — call from a background thread/dispatcher.
 * Callers own token storage and all post-exchange behavior (navigation,
 * iframe injection, prefs). `baseUrl` must be the HA base URL with any
 * dashboard path already stripped — HA 405s `/auth/token` under a dashboard
 * path (see MainHaLoginHandler.stripDashboardPath).
 */
object HaOAuthClient {
    private const val TAG = "HaOAuthClient"

    // The user's HA URL may be local-network HTTPS with a self-signed cert —
    // accept those (same client policy as HaTokenExtractor).
    private val httpClient = LocalHostsTrustingHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    sealed class Result {
        data class Success(
            val accessToken: String,
            val refreshToken: String,
            val expiresIn: Long,
        ) : Result()

        /** The server answered with a non-2xx (or a 2xx missing access_token) —
         *  an auth-level rejection (bad/expired code or refresh token). */
        data class HttpError(val code: Int, val body: String?) : Result() {
            /** Human-readable reason for the common HA auth failure codes. */
            val reason: String
                get() = when (code) {
                    400 -> "Bad request (invalid code/refresh token format?)"
                    401 -> "Token expired or revoked"
                    403 -> "Forbidden (token doesn't have permission)"
                    404 -> "HA auth endpoint not found (wrong URL?)"
                    else -> "HTTP $code"
                }
        }

        /** The server couldn't be reached (offline, DNS, timeout) — retryable. */
        data class NetworkError(val message: String) : Result()
    }

    /** `grant_type=authorization_code` — completes a login flow. */
    fun exchangeAuthorizationCode(
        baseUrl: String,
        authCode: String,
        clientId: String = "${baseUrl.trimEnd('/')}/",
    ): Result = post(
        baseUrl,
        FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", authCode)
            .add("client_id", clientId)
            .build(),
        // HA always returns a refresh token on code exchange; "" preserves the
        // legacy callers' optString default if it ever doesn't.
        fallbackRefreshToken = "",
    )

    /** `grant_type=refresh_token` — mints a fresh access token. */
    fun refreshAccessToken(
        baseUrl: String,
        refreshToken: String,
        clientId: String = "${baseUrl.trimEnd('/')}/",
    ): Result = post(
        baseUrl,
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build(),
        // HA omits refresh_token on refresh responses — keep using the old one.
        fallbackRefreshToken = refreshToken,
    )

    private fun post(baseUrl: String, body: FormBody, fallbackRefreshToken: String): Result {
        val url = "${baseUrl.trimEnd('/')}/auth/token"
        return try {
            val request = Request.Builder().url(url).post(body).build()
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "Token request failed: HTTP ${response.code} - ${responseBody.take(200)}")
                    return Result.HttpError(response.code, responseBody.take(500))
                }
                val json = JSONObject(responseBody)
                val accessToken = json.optString("access_token", "")
                if (accessToken.isEmpty()) {
                    Log.e(TAG, "Token response missing access_token")
                    return Result.HttpError(response.code, "missing access_token in response")
                }
                Result.Success(
                    accessToken = accessToken,
                    refreshToken = json.optString("refresh_token", fallbackRefreshToken),
                    expiresIn = json.optLong("expires_in", 1800L),
                )
            }
        } catch (e: Exception) {
            // ConnectException / SocketTimeoutException / SSL / JSON — all
            // surface as NetworkError; callers treat these as retryable.
            Log.e(TAG, "Token request error: ${e.javaClass.simpleName}: ${e.message}")
            Result.NetworkError("${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

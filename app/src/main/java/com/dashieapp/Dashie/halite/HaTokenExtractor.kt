package com.dashieapp.Dashie.halite

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.halite.auth.KioskJwtRefresher
import com.dashieapp.Dashie.halite.auth.KioskSessionProvisioner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Utility for extracting and refreshing Home Assistant authentication tokens.
 *
 * This centralizes token logic so it can be used by:
 * - Voice commands (HaVoiceService)
 * - Photo source settings (DialogPickers)
 * - Screensaver (ScreenDimmer)
 *
 * Usage:
 * ```
 * HaTokenExtractor.ensureToken(webView, halitePrefs) { success ->
 *     if (success) {
 *         // Token is now cached in halitePrefs
 *         val token = halitePrefs.haAccessToken
 *     }
 * }
 * ```
 *
 * For coroutine-based code:
 * ```
 * val success = HaTokenExtractor.ensureTokenAsync(webView, halitePrefs)
 * ```
 */
object HaTokenExtractor {
    private const val TAG = "HaTokenExtractor"

    // HTTP client for token refresh. Targets the user's HA URL, which may
    // be a local-network HTTPS with a self-signed cert — accept those.
    private val httpClient = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Weak reference to the most-recently-used WebView. Populated whenever
    // anything calls extractAndCache/extract, so getValidCredentialsSync can
    // fall back to a fresh extraction from HA's localStorage when the cached
    // refresh token is rejected (HA can rotate refresh tokens — the WebView
    // sees the new one in localStorage, the native cache doesn't until
    // someone re-extracts). Lets background callers self-heal silently.
    private var webViewRef: java.lang.ref.WeakReference<WebView>? = null

    /**
     * Register the active WebView so background callers (e.g.
     * getValidCredentialsSync) can re-extract HA tokens from localStorage
     * when their cached refresh token gets rejected. Should be called from
     * the WebView bootstrap path on initial creation and after WebView
     * recreation. Idempotent.
     */
    fun setWebView(webView: WebView?) {
        if (webView != null) webViewRef = java.lang.ref.WeakReference(webView)
    }

    // Backwards-compat alias kept for the internal call inside extract().
    private fun rememberWebView(webView: WebView?) = setWebView(webView)

    /**
     * Result of token extraction
     */
    data class TokenResult(
        val accessToken: String,
        val refreshToken: String?,
        val hassUrl: String?,
        val expiresAt: Long? = null  // Unix timestamp (ms) when token expires
    )

    /**
     * Result of a token refresh operation
     */
    data class RefreshResult(
        val success: Boolean,
        val accessToken: String = "",
        val refreshToken: String = ""
    )

    /**
     * Extract HA tokens from WebView localStorage and cache them in HalitePreferences.
     *
     * @param webView The WebView containing the HA frontend
     * @param halitePrefs Preferences to cache the tokens in
     * @param callback Called with true if token was extracted and cached, false otherwise
     */
    fun extractAndCache(
        webView: WebView?,
        halitePrefs: HalitePreferences,
        callback: (Boolean) -> Unit
    ) {
        extract(webView) { result ->
            if (result != null) {
                // Calculate expiresIn from the extracted expiresAt timestamp
                // If expiresAt is available, use it; otherwise default to 30 minutes
                val expiresIn = if (result.expiresAt != null && result.expiresAt > System.currentTimeMillis()) {
                    (result.expiresAt - System.currentTimeMillis()) / 1000
                } else {
                    1800L  // Default 30 minutes if no expiry info
                }
                halitePrefs.connection.updateHaTokens(result.accessToken, result.refreshToken, expiresIn)
                if (!result.hassUrl.isNullOrEmpty() && halitePrefs.connection.haBaseUrl.isEmpty()) {
                    halitePrefs.connection.haBaseUrl = result.hassUrl
                }
                Log.i(TAG, "Token extracted and cached successfully (expiresIn=${expiresIn}s)")

                // ── Kiosk Real Login, Phase 1 ──
                // This is the moment a KIOSK actually has HA credentials — and it is the only
                // reliable one. The full-app hook (onHaLoginCompleted) rides on the DASHBOARD
                // WebView loading a non-auth page, which a kiosk shell never does, so a kiosk
                // would never provision from there (verified on the Fire tablet: the callback
                // never fired).
                //
                // With an HA origin + token in hand, the tablet can ask THIS box — which is
                // signed into the household account via the add-on — to authorize it into that
                // account, then poll Dashie for its own per-device JWT. No human touches the
                // tablet. No-ops when already logged in, or when household sharing is off.
                val appContext = webView?.context?.applicationContext
                if (appContext != null) {
                    KioskSessionProvisioner.provisionIfNeeded(appContext, halitePrefs) { outcome ->
                        Log.i(TAG, "Kiosk session provisioning: $outcome")
                    }
                }
                // Renew a near-expiry device JWT NATIVELY. The dashboard's refresh loop
                // (edge-client.js) cannot run on a kiosk, so without this any session we
                // provision simply dies at 72h.
                KioskJwtRefresher.refreshIfStale(halitePrefs, appContext) { outcome ->
                    if (outcome != KioskJwtRefresher.Outcome.NOT_DUE &&
                        outcome != KioskJwtRefresher.Outcome.NO_SESSION) {
                        Log.i(TAG, "Native JWT refresh: $outcome")
                    }
                }

                callback(true)
            } else {
                callback(false)
            }
        }
    }

    /**
     * Extract HA tokens from WebView localStorage without caching.
     *
     * @param webView The WebView containing the HA frontend
     * @param callback Called with TokenResult if successful, null otherwise
     */
    fun extract(webView: WebView?, callback: (TokenResult?) -> Unit) {
        if (webView == null) {
            Log.w(TAG, "WebView is null, cannot extract token")
            callback(null)
            return
        }

        // Cache the WebView so background callers (getValidCredentialsSync)
        // can re-extract from HA's localStorage if their cached refresh
        // token is rejected.
        rememberWebView(webView)

        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript("""
                (function() {
                    try {
                        var tokens = localStorage.getItem('hassTokens');
                        if (tokens) {
                            var parsed = JSON.parse(tokens);
                            return JSON.stringify({
                                access_token: parsed.access_token || null,
                                refresh_token: parsed.refresh_token || null,
                                hassUrl: parsed.hassUrl || null,
                                expires: parsed.expires || null
                            });
                        }
                        return null;
                    } catch (e) { return null; }
                })();
            """.trimIndent()) { result ->
                try {
                    if (result != null && result != "null") {
                        val cleanResult = result
                            .trim('"')
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")

                        val json = JSONObject(cleanResult)
                        val accessToken = json.optString("access_token", "")
                        val refreshToken = json.optString("refresh_token", "")
                        val hassUrl = json.optString("hassUrl", "")
                        val expiresAt = if (json.has("expires") && !json.isNull("expires")) {
                            json.optLong("expires", 0L).takeIf { it > 0 }
                        } else null

                        if (accessToken.isNotEmpty()) {
                            Log.d(TAG, "Successfully extracted token from WebView (expires=${expiresAt ?: "unknown"})")
                            callback(TokenResult(
                                accessToken = accessToken,
                                refreshToken = refreshToken.ifEmpty { null },
                                hassUrl = hassUrl.ifEmpty { null },
                                expiresAt = expiresAt
                            ))
                            return@evaluateJavascript
                        }
                    }
                    Log.w(TAG, "No valid token found in WebView localStorage")
                    callback(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing token from WebView: ${e.message}")
                    callback(null)
                }
            }
        }
    }

    /**
     * Check if we have a valid cached token, and if not, try to refresh or extract.
     * This is a convenience method that:
     * 1. Returns true if cached token is valid
     * 2. Tries to refresh using refresh token if expired
     * 3. Falls back to extracting from WebView
     *
     * @param webView The WebView containing the HA frontend
     * @param halitePrefs Preferences containing cached tokens
     * @param callback Called with true if we have a valid token
     */
    fun ensureToken(
        webView: WebView?,
        halitePrefs: HalitePreferences,
        callback: (Boolean) -> Unit
    ) {
        if (halitePrefs.connection.hasHaAccessToken && !halitePrefs.connection.isHaTokenExpired) {
            Log.d(TAG, "Using cached token (not expired)")
            callback(true)
            return
        }

        // Token is expired or missing - try to refresh first
        if (halitePrefs.connection.isHaTokenExpired && halitePrefs.connection.haRefreshToken.isNotEmpty()) {
            Log.d(TAG, "Token expired, attempting refresh...")
            refreshTokenAsync(halitePrefs) { refreshed ->
                if (refreshed) {
                    Log.i(TAG, "Token refreshed successfully")
                    callback(true)
                } else {
                    Log.w(TAG, "Token refresh failed, trying WebView extraction...")
                    extractAndCache(webView, halitePrefs, callback)
                }
            }
        } else {
            Log.d(TAG, "No valid cached token, extracting from WebView...")
            extractAndCache(webView, halitePrefs, callback)
        }
    }

    /**
     * Get valid HA credentials (base URL + access token) for making API calls.
     * If the token is expired, attempts refresh. Returns null if no valid
     * credentials are available — callers should skip the API call entirely.
     *
     * Must be called from a background thread.
     *
     * Usage:
     * ```
     * val (baseUrl, token) = HaTokenExtractor.getValidCredentialsSync(halitePrefs) ?: return
     * // Use baseUrl and token for API call
     * ```
     */
    fun getValidCredentialsSync(halitePrefs: HalitePreferences): Pair<String, String>? {
        val conn = halitePrefs.connection
        val baseUrl = conn.haBaseUrl.ifEmpty {
            conn.haUrl.substringBefore("?").trimEnd('/')
        }
        if (baseUrl.isEmpty()) return null

        var token = conn.haAccessToken
        if (token.isEmpty()) return null

        if (conn.isHaTokenExpired) {
            try {
                val result = refreshTokenSync(halitePrefs)
                if (result.success && result.accessToken.isNotEmpty()) {
                    token = result.accessToken
                } else {
                    // Refresh failed — most commonly because HA rotated the
                    // refresh token and our cached copy is stale. The WebView
                    // sees the rotation immediately (hassTokens in localStorage),
                    // so re-extracting from it usually self-heals. Layer 1
                    // recovery: try a synchronous WebView extraction before
                    // giving up.
                    val recovered = extractFromWebViewSync(halitePrefs)
                    if (recovered) {
                        token = halitePrefs.connection.haAccessToken
                        if (token.isNotEmpty()) {
                            Log.i(TAG, "🔁 Recovered HA token via WebView extraction after refresh failure")
                            return Pair(baseUrl, token)
                        }
                    }
                    Log.w(TAG, "Token expired and refresh+extract both failed, skipping API call")
                    return null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh threw exception, skipping API call", e)
                return null
            }
        }

        return Pair(baseUrl, token)
    }

    /**
     * Synchronously re-extract HA tokens from the most-recently-known
     * WebView and write them into halitePrefs. Used as a fallback when
     * refresh fails because the cached refresh token is stale relative
     * to what HA actually has (which the WebView's localStorage knows).
     *
     * Returns true if a fresh access token was written into prefs.
     */
    private fun extractFromWebViewSync(halitePrefs: HalitePreferences): Boolean {
        val webView = webViewRef?.get() ?: run {
            Log.w(TAG, "No cached WebView ref — can't fall back to extraction")
            return false
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false
        Handler(Looper.getMainLooper()).post {
            extractAndCache(webView, halitePrefs) { ok ->
                success = ok
                latch.countDown()
            }
        }
        return try {
            // 5s cap — WebView extraction is normally <100ms; this guards
            // against an unresponsive WebView blocking the background thread.
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS) && success
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * Refresh the HA access token using the refresh token.
     * Runs asynchronously on a background thread.
     *
     * @param halitePrefs Preferences containing refresh token and base URL
     * @param callback Called with true if refresh succeeded
     */
    fun refreshTokenAsync(
        halitePrefs: HalitePreferences,
        callback: (Boolean) -> Unit
    ) {
        Thread {
            val result = refreshTokenSync(halitePrefs)
            Handler(Looper.getMainLooper()).post {
                callback(result.success)
            }
        }.start()
    }

    /**
     * Refresh the HA access token using the refresh token.
     * Runs asynchronously on a background thread.
     * Returns the full RefreshResult with new tokens.
     *
     * @param halitePrefs Preferences containing refresh token and base URL
     * @param webView Optional WebView to update localStorage after refresh
     * @param callback Called with RefreshResult
     */
    fun refreshTokenWithResult(
        halitePrefs: HalitePreferences,
        webView: WebView? = null,
        callback: (RefreshResult) -> Unit
    ) {
        Thread {
            val result = refreshTokenSync(halitePrefs)
            Handler(Looper.getMainLooper()).post {
                if (result.success && webView != null) {
                    updateWebViewTokens(webView, result.accessToken, result.refreshToken, halitePrefs.connection.haTokenExpiry)
                }
                callback(result)
            }
        }.start()
    }

    /**
     * Refresh the HA access token synchronously.
     * Must be called from a background thread.
     *
     * If refresh token fails and stored credentials exist, automatically
     * attempts re-authentication using username/password.
     *
     * @param halitePrefs Preferences containing refresh token and base URL
     * @return RefreshResult with new tokens if successful
     */
    fun refreshTokenSync(halitePrefs: HalitePreferences): RefreshResult {
        val refreshToken = halitePrefs.connection.haRefreshToken
        // Refresh MUST use the same canonical auth origin the token was minted for,
        // or HA rejects the refresh with 400 invalid_request (client_id mismatch).
        val baseUrl = halitePrefs.connection.getAuthOrigin()
            ?: halitePrefs.connection.haUrl.substringBefore("?").trimEnd('/')

        // Log diagnostic info
        Log.i(TAG, "🔐 Token recovery attempt:")
        Log.i(TAG, "   - baseUrl: ${if (baseUrl.isNotEmpty()) baseUrl else "(empty)"}")
        Log.i(TAG, "   - refreshToken: ${if (refreshToken.isNotEmpty()) "${refreshToken.take(20)}..." else "(empty)"}")
        Log.i(TAG, "   - hasStoredCredentials: ${halitePrefs.connection.hasStoredCredentials()}")
        Log.i(TAG, "   - keepLoggedIn: ${halitePrefs.connection.keepLoggedIn}")
        Log.i(TAG, "   - tokenExpiry: ${halitePrefs.connection.haTokenExpiry} (expired: ${halitePrefs.connection.isHaTokenExpired})")

        if (baseUrl.isEmpty()) {
            Log.e(TAG, "❌ No HA base URL available - cannot recover")
            return RefreshResult(false)
        }

        // Try refresh token first if available
        if (refreshToken.isNotEmpty()) {
            Log.i(TAG, "🔄 Attempting token refresh...")
            val refreshResult = attemptTokenRefresh(baseUrl, refreshToken, halitePrefs)
            if (refreshResult.success) {
                return refreshResult
            }
            Log.w(TAG, "⚠️ Token refresh failed, checking for stored credentials...")
        } else {
            Log.w(TAG, "⚠️ No refresh token available, checking for stored credentials...")
        }

        // Fallback: Try re-authentication with stored credentials
        if (halitePrefs.connection.shouldAutoLogin()) {
            Log.i(TAG, "🔑 Attempting re-authentication with stored credentials (user: ${halitePrefs.connection.haUsername})...")
            val reAuthResult = reAuthWithCredentials(baseUrl, halitePrefs)
            if (reAuthResult.success) {
                Log.i(TAG, "✅ Re-authentication successful!")
                return reAuthResult
            }
            Log.e(TAG, "❌ Re-authentication with stored credentials failed")
        } else {
            val reason = when {
                !halitePrefs.connection.keepLoggedIn -> "keepLoggedIn is false"
                !halitePrefs.connection.hasStoredCredentials() -> "no stored credentials"
                else -> "unknown"
            }
            Log.w(TAG, "⚠️ Cannot attempt credential re-auth: $reason")
        }

        return RefreshResult(false)
    }

    /**
     * Attempt to refresh using the refresh token.
     */
    private fun attemptTokenRefresh(
        baseUrl: String,
        refreshToken: String,
        halitePrefs: HalitePreferences
    ): RefreshResult {
        return when (val result = com.dashieapp.Dashie.halite.auth.HaOAuthClient
            .refreshAccessToken(baseUrl, refreshToken)) {
            is com.dashieapp.Dashie.halite.auth.HaOAuthClient.Result.Success -> {
                Log.i(TAG, "✅ Token refresh successful! New token: ${result.accessToken.take(20)}... (expires in ${result.expiresIn}s)")
                halitePrefs.connection.updateHaTokens(result.accessToken, result.refreshToken, result.expiresIn)
                RefreshResult(true, result.accessToken, result.refreshToken)
            }
            is com.dashieapp.Dashie.halite.auth.HaOAuthClient.Result.HttpError -> {
                Log.e(TAG, "❌ Token refresh failed: ${result.reason}")
                Log.e(TAG, "   Response: ${result.body}")
                RefreshResult(false)
            }
            is com.dashieapp.Dashie.halite.auth.HaOAuthClient.Result.NetworkError -> {
                Log.e(TAG, "❌ Token refresh failed: ${result.message}")
                RefreshResult(false)
            }
        }
    }

    /**
     * Re-authenticate using stored username/password credentials.
     * This performs the full HA OAuth login flow:
     * 1. Initialize login flow
     * 2. Submit credentials
     * 3. Exchange auth code for tokens
     *
     * Must be called from a background thread.
     */
    private fun reAuthWithCredentials(baseUrl: String, halitePrefs: HalitePreferences): RefreshResult {
        val username = halitePrefs.connection.haUsername
        val password = halitePrefs.connection.haPassword

        if (username.isEmpty() || password.isEmpty()) {
            Log.e(TAG, "Missing stored credentials")
            return RefreshResult(false)
        }

        val clientId = "$baseUrl/"
        val redirectUri = "$baseUrl/?auth_callback=1"

        try {
            // Step 1: Initialize login flow
            val flowId = initializeLoginFlow(baseUrl, clientId, redirectUri)
            if (flowId == null) {
                Log.e(TAG, "Failed to initialize login flow")
                return RefreshResult(false)
            }
            Log.d(TAG, "Got flow_id: $flowId")

            // Step 2: Submit credentials
            val authCode = submitCredentials(baseUrl, flowId, username, password, clientId)
            if (authCode == null) {
                Log.e(TAG, "Failed to submit credentials (wrong password?)")
                return RefreshResult(false)
            }
            Log.d(TAG, "Got auth code: ${authCode.take(20)}...")

            // Step 3: Exchange auth code for tokens
            val tokenResult = exchangeCodeForToken(baseUrl, authCode, clientId)
            if (tokenResult != null) {
                halitePrefs.connection.updateHaTokens(tokenResult.first, tokenResult.second, tokenResult.third)
                return RefreshResult(true, tokenResult.first, tokenResult.second)
            }

            Log.e(TAG, "Failed to exchange auth code for token")
        } catch (e: Exception) {
            Log.e(TAG, "Re-authentication error: ${e.message}", e)
        }

        return RefreshResult(false)
    }

    /**
     * Initialize HA login flow - returns flow_id.
     */
    private fun initializeLoginFlow(baseUrl: String, clientId: String, redirectUri: String): String? {
        val url = "$baseUrl/auth/login_flow"

        try {
            val jsonBody = JSONObject().apply {
                put("client_id", clientId)
                put("handler", org.json.JSONArray().apply {
                    put("homeassistant")
                    put(JSONObject.NULL)
                })
                put("redirect_uri", redirectUri)
            }

            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    val json = JSONObject(responseBody)
                    return json.optString("flow_id", null)
                } else {
                    Log.e(TAG, "login_flow error: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "initializeLoginFlow error: ${e.message}", e)
        }

        return null
    }

    /**
     * Submit credentials to login flow - returns auth code.
     */
    private fun submitCredentials(
        baseUrl: String,
        flowId: String,
        username: String,
        password: String,
        clientId: String
    ): String? {
        val url = "$baseUrl/auth/login_flow/$flowId"

        try {
            val jsonBody = JSONObject().apply {
                put("username", username)
                put("password", password)
                put("client_id", clientId)
            }

            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    val json = JSONObject(responseBody)
                    if (json.has("result")) {
                        return json.getString("result")
                    } else if (json.has("errors")) {
                        Log.w(TAG, "Credentials rejected: ${json.optJSONObject("errors")}")
                    }
                } else {
                    Log.e(TAG, "submitCredentials error: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "submitCredentials error: ${e.message}", e)
        }

        return null
    }

    /**
     * Exchange auth code for tokens - returns (accessToken, refreshToken, expiresIn).
     */
    private fun exchangeCodeForToken(
        baseUrl: String,
        authCode: String,
        clientId: String
    ): Triple<String, String, Long>? {
        return when (val result = com.dashieapp.Dashie.halite.auth.HaOAuthClient
            .exchangeAuthorizationCode(baseUrl, authCode, clientId)) {
            is com.dashieapp.Dashie.halite.auth.HaOAuthClient.Result.Success -> {
                Log.i(TAG, "Token exchange successful - expires_in: ${result.expiresIn}s")
                Triple(result.accessToken, result.refreshToken, result.expiresIn)
            }
            is com.dashieapp.Dashie.halite.auth.HaOAuthClient.Result.HttpError -> {
                Log.e(TAG, "exchangeCodeForToken error: ${result.code}")
                null
            }
            is com.dashieapp.Dashie.halite.auth.HaOAuthClient.Result.NetworkError -> {
                Log.e(TAG, "exchangeCodeForToken error: ${result.message}")
                null
            }
        }
    }

    /**
     * Update the tokens stored in WebView localStorage.
     * Call this after a successful refresh to keep WebView in sync.
     *
     * @param webView The WebView containing HA frontend
     * @param accessToken New access token
     * @param refreshToken New refresh token
     */
    fun updateWebViewTokens(webView: WebView?, accessToken: String, refreshToken: String, expiresAt: Long = 0) {
        if (webView == null) {
            Log.w(TAG, "WebView is null, cannot update localStorage tokens")
            return
        }

        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript("""
                (function() {
                    try {
                        var tokens = localStorage.getItem('hassTokens');
                        if (tokens) {
                            var parsed = JSON.parse(tokens);
                            parsed.access_token = '$accessToken';
                            parsed.refresh_token = '$refreshToken';
                            if ($expiresAt > 0) {
                                parsed.expires = $expiresAt;
                            }
                            localStorage.setItem('hassTokens', JSON.stringify(parsed));
                            console.log('[DashieLite] Updated hassTokens (expires=' + (parsed.expires || 'unchanged') + ')');
                            return true;
                        }
                        return false;
                    } catch (e) {
                        console.error('Error updating tokens:', e);
                        return false;
                    }
                })();
            """.trimIndent()) { result ->
                Log.d(TAG, "Token update in localStorage: $result")
            }
        }
    }

    /**
     * Suspend function version of ensureToken for coroutine-based code.
     *
     * @param webView The WebView containing the HA frontend (can be null)
     * @param halitePrefs Preferences containing cached tokens
     * @return true if we have a valid token
     */
    suspend fun ensureTokenAsync(
        webView: WebView?,
        halitePrefs: HalitePreferences
    ): Boolean = withContext(Dispatchers.Main) {
        // Check if cached token is valid
        if (halitePrefs.connection.hasHaAccessToken && !halitePrefs.connection.isHaTokenExpired) {
            Log.d(TAG, "Using cached token (not expired)")
            return@withContext true
        }

        // Try to refresh if we have a refresh token
        if (halitePrefs.connection.isHaTokenExpired && halitePrefs.connection.haRefreshToken.isNotEmpty()) {
            Log.d(TAG, "Token expired, attempting refresh...")
            val refreshResult = withContext(Dispatchers.IO) {
                refreshTokenSync(halitePrefs)
            }
            if (refreshResult.success) {
                Log.i(TAG, "Token refreshed successfully")
                // Update WebView if available
                if (webView != null) {
                    updateWebViewTokens(webView, refreshResult.accessToken, refreshResult.refreshToken)
                }
                return@withContext true
            }
            Log.w(TAG, "Token refresh failed, trying WebView extraction...")
        }

        // Fall back to WebView extraction
        if (webView == null) {
            Log.w(TAG, "WebView is null, cannot extract token")
            return@withContext false
        }

        // Use suspendCoroutine to bridge callback to suspend
        return@withContext kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            extractAndCache(webView, halitePrefs) { success ->
                cont.resume(success) {}
            }
        }
    }
}

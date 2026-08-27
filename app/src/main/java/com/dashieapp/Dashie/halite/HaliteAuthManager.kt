package com.dashieapp.Dashie.halite

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.webkit.WebView
import androidx.activity.result.ActivityResult
import com.dashieapp.Dashie.loadShellFromAssets
import androidx.activity.result.ActivityResultLauncher

/**
 * Manages native HA login flow and token injection for Fire tablets.
 *
 * Responsibilities:
 * - Native HA login activity launch (Fire tablet keyboard workaround)
 * - Token injection into WebView localStorage
 * - Auth callback URL construction
 * - Login state tracking (cancelled, completed)
 *
 * Extracted from MainActivity to reduce its size and improve maintainability.
 */
class HaliteAuthManager(
    private val activity: Activity,
    private val webViewProvider: () -> WebView,
    private val halitePrefs: HalitePreferences,
    private val haLoginLauncher: ActivityResultLauncher<Intent>
) {
    // Get the current WebView (may be recreated after memory pressure)
    private val webView: WebView
        get() = webViewProvider()
    companion object {
        private const val TAG = "HaliteAuthManager"
    }

    // Login state flags
    var nativeLoginCancelled = false
        private set
    var haLoginCompleted = false
        private set

    // Circuit breaker for login loop prevention
    private var loginAttemptCount = 0
    private var lastAttemptTime = 0L
    private val MAX_AUTO_RETRY = 3
    private val RETRY_RESET_TIME_MS = 5 * 60 * 1000L // 5 minutes

    // State for token injection (used by WebViewClient)
    private var pendingTokenJson: String? = null
    private var pendingDashboardUrl: String? = null
    private var tokenInjectionPending = false
    private var nativeLoginInProgress = false

    // Iframe token injection — tokens are injected directly into HA's HTML
    // via shouldInterceptRequest (not postMessage) to avoid storage partitioning issues.
    var iframeTokenInjectionPending = false
        private set
    var pendingIframeTokenJson: String? = null
        private set

    fun setIframeTokenInjection(tokenJson: String) {
        pendingIframeTokenJson = tokenJson
        iframeTokenInjectionPending = true
    }

    fun clearIframeTokenInjection() {
        iframeTokenInjectionPending = false
        pendingIframeTokenJson = null
    }

    /**
     * Callbacks to notify MainActivity of auth events
     */
    interface AuthCallback {
        fun onHaLoginCompleted()
        fun onReconfigureRequested()
        fun onMaxLoginAttemptsReached()
    }

    var callback: AuthCallback? = null

    /**
     * Launch native HA login activity.
     * This avoids the WebView login page which has keyboard input issues.
     * @param currentUrl Optional URL to extract HA base URL from (e.g., from current auth page).
     *                   If not provided or extraction fails, falls back to halitePrefs.haBaseUrl/haUrl.
     * @return true if native login was launched, false if it couldn't be launched (no HA URL configured)
     */
    fun launchNativeLogin(currentUrl: String? = null): Boolean {
        // Guard against launching multiple login activities
        if (nativeLoginInProgress) {
            Log.w(TAG, "🔧 Native login already in progress, ignoring duplicate launch request")
            return true  // Return true to prevent WebView from handling it
        }

        // The native login activity was built specifically for Fire tablet,
        // where the WebView's HA login page has quirks with the soft
        // keyboard. Fire TV (and other non-Fire-tablet Android devices)
        // handle the WebView login page fine — letting the WebView render
        // the standard HA auth form is more reliable than maintaining a
        // parallel native form. Skip native launch on those platforms.
        val isFireTablet = com.dashieapp.Dashie.util.DeviceInfoHelper.isFireTablet()
        if (!isFireTablet) {
            Log.i(TAG, "🔧 Not Fire tablet — skipping native HA login activity, letting WebView handle it")
            return false
        }

        // Circuit breaker: Reset counter if enough time has passed since last attempt
        if (System.currentTimeMillis() - lastAttemptTime > RETRY_RESET_TIME_MS) {
            Log.i(TAG, "🔧 Resetting login attempt counter after timeout")
            loginAttemptCount = 0
        }

        // Circuit breaker: Check if we've exceeded max retry attempts
        if (loginAttemptCount >= MAX_AUTO_RETRY) {
            Log.w(TAG, "🔧 Max login attempts reached ($MAX_AUTO_RETRY), stopping auto-launch")
            Log.w(TAG, "🔧 Please use sidebar to reconfigure or wait 5 minutes for reset")
            nativeLoginCancelled = true
            callback?.onMaxLoginAttemptsReached()
            return false
        }

        // Increment attempt counter
        loginAttemptCount++
        lastAttemptTime = System.currentTimeMillis()
        Log.i(TAG, "🔧 Login attempt $loginAttemptCount of $MAX_AUTO_RETRY")

        // Try to extract base URL from the current page URL (useful for proxy URLs)
        val extractedBaseUrl = currentUrl?.let { url ->
            try {
                val uri = android.net.Uri.parse(url)
                val scheme = uri.scheme ?: "https"
                val host = uri.host
                val port = uri.port
                if (host != null) {
                    if (port > 0) "${scheme}://${host}:${port}" else "${scheme}://${host}"
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "🔧 Failed to parse URL: $url")
                null
            }
        }

        val haBaseUrl = extractedBaseUrl
            ?: halitePrefs.connection.haBaseUrl?.trimEnd('/')
            ?: halitePrefs.connection.haUrl.substringBefore("?").trimEnd('/')

        if (haBaseUrl.isEmpty()) {
            Log.w(TAG, "🔧 Cannot launch native login - no HA URL configured (currentUrl=$currentUrl)")
            return false
        }

        // Check if we should auto-login with saved credentials
        // After 2 failed attempts, disable auto-login to let user manually correct credentials
        val shouldAutoLogin = halitePrefs.connection.shouldAutoLogin() && loginAttemptCount < 2

        Log.i(TAG, "🔧 Fire tablet: Launching native login for $haBaseUrl (autoLogin=$shouldAutoLogin, attempt=$loginAttemptCount)")

        nativeLoginInProgress = true
        val intent = Intent(activity, HaLoginActivity::class.java).apply {
            putExtra(HaLoginActivity.EXTRA_HA_URL, haBaseUrl)
            putExtra(HaLoginActivity.EXTRA_AUTO_LOGIN, shouldAutoLogin)
        }
        haLoginLauncher.launch(intent)
        return true
    }

    /**
     * Handle the result from native login activity.
     * Call this from the ActivityResultLauncher callback.
     */
    fun handleLoginResult(result: ActivityResult) {
        nativeLoginInProgress = false  // Reset flag regardless of result

        if (result.resultCode == Activity.RESULT_OK) {
            val accessToken = result.data?.getStringExtra(HaLoginActivity.RESULT_ACCESS_TOKEN)
            val refreshToken = result.data?.getStringExtra(HaLoginActivity.RESULT_REFRESH_TOKEN)
            val tokenExpiry = result.data?.getLongExtra(HaLoginActivity.RESULT_TOKEN_EXPIRY, 1800L) ?: 1800L

            if (accessToken != null) {
                Log.i(TAG, "🔧 Fire tablet: Native login successful, injecting tokens into localStorage")
                nativeLoginCancelled = false  // Reset flag on successful login
                haLoginCompleted = true  // Mark HA login as complete - enables camera permission request

                // Reset circuit breaker on successful login
                loginAttemptCount = 0
                lastAttemptTime = 0L
                Log.i(TAG, "🔧 Login successful - reset retry counter")

                // Cache tokens to HalitePreferences for background services (screensaver, etc.)
                halitePrefs.connection.updateHaTokens(accessToken, refreshToken, tokenExpiry)
                Log.d(TAG, "🔧 Cached HA tokens to preferences for background services")

                injectTokensAndLoad(accessToken, refreshToken ?: "", tokenExpiry)
                callback?.onHaLoginCompleted()
            } else {
                Log.w(TAG, "🔧 Fire tablet: No access token received")
            }
        } else {
            Log.w(TAG, "🔧 Fire tablet: Native login cancelled")

            // Check if user wants to reconfigure dashboard settings
            val wantsReconfigure = result.data?.getBooleanExtra(HaLoginActivity.RESULT_RECONFIGURE, false) ?: false

            if (wantsReconfigure) {
                Log.i(TAG, "🔧 Fire tablet: User requested reconfigure")
                callback?.onReconfigureRequested()
            } else {
                // User cancelled - set flag to prevent auto-relaunching login
                nativeLoginCancelled = true
            }
        }
    }

    /**
     * Check if we should intercept an auth page and launch native login.
     * @param url The URL being loaded
     * @return true if native login should be launched, false to let WebView handle it
     */
    fun shouldInterceptAuthPage(url: String?): Boolean {
        if (url == null) return false
        if (nativeLoginCancelled) return false

        // Don't intercept if we're in the middle of injecting tokens —
        // the auth page redirect is expected before injection completes
        if (tokenInjectionPending) return false

        val isAuthPage = url.contains("/auth/authorize") ||
                        url.contains("/auth/login") ||
                        (url.contains("/auth/") && !url.contains("auth_callback"))

        // If we're being redirected to an auth page even though login was "complete",
        // it means tokens expired - reset state and allow native login to kick in
        if (isAuthPage && haLoginCompleted) {
            Log.i(TAG, "🔧 Auth redirect detected after login complete - tokens likely expired, resetting state")
            haLoginCompleted = false
        }

        return isAuthPage
    }

    /**
     * Reset the cancelled flag (e.g., when URL changes).
     * Also resets the circuit breaker counter to allow fresh login attempts.
     */
    fun resetCancelledFlag() {
        nativeLoginCancelled = false
        loginAttemptCount = 0
        lastAttemptTime = 0L
        Log.i(TAG, "🔧 Reset cancelled flag and circuit breaker counter")
    }

    /**
     * Mark login as complete (called when dashboard loads without auth page).
     */
    fun markLoginComplete() {
        Log.i(TAG, "📢 markLoginComplete() called - haLoginCompleted=$haLoginCompleted, callback=${if (callback != null) "SET" else "NULL"}")
        if (!haLoginCompleted) {
            haLoginCompleted = true
            Log.i(TAG, "🔧 HA login complete (dashboard loaded)")
            Log.i(TAG, "📢 Invoking callback?.onHaLoginCompleted()")
            callback?.onHaLoginCompleted()
            Log.i(TAG, "📢 Callback invocation complete")
        } else {
            Log.i(TAG, "📢 Skipping callback - already completed")
        }
    }

    /**
     * Inject tokens saved in halitePrefs and load the dashboard.
     * Used by the onboarding flow: tokens were stored during handleStandardHaLoginResult,
     * and we now need to inject them into HA's localStorage and navigate to the shell.
     */
    fun injectSavedTokensAndLoad() {
        val accessToken = halitePrefs.connection.haAccessToken
        val refreshToken = halitePrefs.connection.haRefreshToken
        val expiresIn = 1800L

        if (accessToken.isEmpty()) {
            Log.w(TAG, "🔧 No saved tokens to inject — skipping")
            return
        }

        // Mark login as complete to prevent HaliteAuthManager from re-prompting
        haLoginCompleted = true
        nativeLoginCancelled = false

        Log.i(TAG, "🔧 Onboarding: Injecting saved tokens and loading HA")

        // Also prepare tokens for iframe injection (modern WebViews use storage
        // partitioning — tokens in main frame localStorage aren't visible to the iframe).
        // Single source of truth: clientId/hassUrl from the canonical auth origin.
        val expiryTimestamp = System.currentTimeMillis() + (expiresIn * 1000)
        val iframeJson = halitePrefs.connection.buildHassTokensJson(
            accessToken = accessToken, refreshToken = refreshToken, expiry = expiryTimestamp
        )
        if (iframeJson != null) {
            pendingIframeTokenJson = iframeJson
            iframeTokenInjectionPending = true
            Log.i(TAG, "🔧 Iframe token injection queued for shell page load")
        }

        injectTokensAndLoad(accessToken, refreshToken, expiresIn)
    }

    /**
     * Inject HA tokens into localStorage and load the dashboard.
     * This is the proper way to authenticate after native login - we inject the tokens
     * directly into localStorage in the same format HA's frontend expects.
     */
    private fun injectTokensAndLoad(accessToken: String, refreshToken: String, expiresIn: Long) {
        val dashboardUrl = halitePrefs.connection.buildFullUrl()
        val haBaseUrl = halitePrefs.connection.getAuthOrigin()
            ?: halitePrefs.connection.haUrl.substringBefore("?").trimEnd('/')

        // Calculate token expiry timestamp (current time + expires_in seconds)
        val expiryTimestamp = System.currentTimeMillis() + (expiresIn * 1000)

        // Build the hassTokens object HA's frontend expects — single source of truth
        // (clientId/hassUrl from the canonical auth origin, matching mint + refresh).
        val tokenJson = halitePrefs.connection.buildHassTokensJson(
            accessToken = accessToken, refreshToken = refreshToken, expiry = expiryTimestamp
        ) ?: run {
            Log.w(TAG, "🔧 Fire tablet: no auth origin configured — cannot inject tokens")
            return
        }

        Log.i(TAG, "🔧 Fire tablet: Injecting tokens into localStorage")

        // Store tokens for injection and track injection state
        pendingTokenJson = tokenJson
        pendingDashboardUrl = dashboardUrl
        tokenInjectionPending = true

        // Also queue iframe token injection — after main-frame tokens are injected and
        // the shell page reloads, the HA iframe needs tokens too. Without this, the iframe
        // loads at a different origin and can't see main-frame localStorage (storage partitioning),
        // causing a second login prompt. The tokens are injected into the HA HTML by
        // shouldInterceptRequest → interceptHaHtmlForKioskCss().
        pendingIframeTokenJson = tokenJson
        iframeTokenInjectionPending = true

        // Load the HA base URL - the existing WebViewClient's onPageFinished will handle injection
        webView.loadUrl(haBaseUrl)
    }

    /**
     * Called from WebViewClient's onPageFinished to inject tokens if pending.
     * @return true if tokens were injected, false otherwise
     */
    fun injectPendingTokensIfNeeded(view: WebView?, url: String?): Boolean {
        if (!tokenInjectionPending || pendingTokenJson == null || pendingDashboardUrl == null) {
            return false
        }

        val haBaseUrl = halitePrefs.connection.haBaseUrl.trimEnd('/').ifEmpty {
            halitePrefs.connection.haUrl.substringBefore("?").trimEnd('/')
        }

        // Only inject on HA pages
        if (url?.startsWith(haBaseUrl) != true) {
            return false
        }

        tokenInjectionPending = false
        val tokenJson = pendingTokenJson ?: return false
        val dashboardUrl = pendingDashboardUrl ?: return false
        pendingTokenJson = null
        pendingDashboardUrl = null

        // Shell page is always used in halite builds (HA is in iframe),
        // so always redirect to shell page after token injection.
        // HA will find the tokens already in localStorage (same-origin).
        val redirectUrl = if (halitePrefs.connection.hasAddonUrl) {
            halitePrefs.connection.getShellPageUrl()
        } else {
            dashboardUrl
        }

        // Escape the JSON for JavaScript string
        val escapedJson = tokenJson.replace("\\", "\\\\").replace("'", "\\'")

        // Inject tokens into localStorage, then navigate to target page via loadUrl
        val js = """
            (function() {
                try {
                    localStorage.setItem('hassTokens', '$escapedJson');
                    console.log('Dashie: HA tokens injected into localStorage');
                } catch (e) {
                    console.error('Dashie: Failed to inject tokens:', e);
                }
            })();
        """.trimIndent()

        val targetView = view ?: return false
        targetView.evaluateJavascript(js) { _ ->
            Log.i(TAG, "🔧 Fire tablet: Token injection complete, loading $redirectUrl")
            targetView.post {
                if (redirectUrl.contains("/kiosk-shell.html")) {
                    targetView.loadShellFromAssets(redirectUrl)
                } else {
                    targetView.loadUrl(redirectUrl)
                }
            }
        }

        return true
    }

    /**
     * Complete HA authentication with the code from native login.
     * Constructs the auth callback URL and loads it in the WebView.
     * Note: This method is currently unused but kept for potential OAuth code flow.
     */
    @Suppress("unused")
    fun completeAuthWithCode(authCode: String) {
        // Canonical auth origin (proxy when configured), consistent with mint/inject.
        val haBaseUrl = halitePrefs.connection.getAuthOrigin()
            ?: halitePrefs.connection.haUrl.substringBefore("?").trimEnd('/')
        val dashboardPath = halitePrefs.connection.dashboardName.trim().let { if (it.isNotEmpty()) "/$it" else "" }

        // Build the URL that HA expects after successful auth
        // Format: {base_url}/{dashboard}?auth_callback=1&code={authCode}&state={state}&hide_params
        // The state contains the hassUrl and clientId as base64 JSON

        // Create the state object that HA expects
        val stateJson = org.json.JSONObject().apply {
            put("hassUrl", haBaseUrl)
            put("clientId", "$haBaseUrl/")
        }
        val stateBase64 = android.util.Base64.encodeToString(
            stateJson.toString().toByteArray(),
            android.util.Base64.NO_WRAP
        )

        // Build the final URL with auth callback (no hide params - CSS injection handles UI hiding)
        val authCallbackUrl = "$haBaseUrl$dashboardPath?auth_callback=1&code=$authCode&state=$stateBase64"

        Log.i(TAG, "🔧 Fire tablet: Loading auth callback URL (UI hiding via CSS injection)")
        webView.loadUrl(authCallbackUrl)
    }
}

package com.dashieapp.Dashie

import com.dashieapp.Dashie.edition.ApiPaths

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.lifecycleScope
import com.dashieapp.Dashie.halite.HaLoginActivity
import com.dashieapp.Dashie.halite.HaWebLoginActivity
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.auth.HaOAuthClient
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Handles launching and processing results of HA login activities.
 * Extracted from MainActivity to reduce its size.
 *
 * Two login modes:
 * - Fire tablets: Launch HaLoginActivity (native Kotlin UI, avoids keyboard issues)
 * - Other devices: Inline WebView auth (load HA auth page in main WebView,
 *   intercept callback, exchange code for tokens — avoids creating a second
 *   WebView which causes activity kills on low-RAM devices)
 */
class MainHaLoginHandler(
    // ComponentActivity (not plain Activity) so coroutines can use lifecycleScope —
    // token-exchange jobs are cancelled automatically when the activity is destroyed.
    private val activity: ComponentActivity,
    private val webViewProvider: () -> WebView,
    private val halitePrefsProvider: () -> HalitePreferences?,
    private val dashieApiPrefsProvider: () -> com.dashieapp.Dashie.api.DashieApiPreferences?,
    private val setDashieApiPrefs: (com.dashieapp.Dashie.api.DashieApiPreferences) -> Unit,
    private val standardHaLoginLauncher: ActivityResultLauncher<Intent>,
    /** Provides HaliteAuthManager so we can queue iframe token injection
     *  (the mechanism that makes HA's frontend recognize our tokens via
     *  shouldInterceptRequest). Only used by the cloud-mode inline-auth
     *  path — kiosk-mode goes through HaliteAuthManager directly. */
    private val haliteAuthManagerProvider: () -> com.dashieapp.Dashie.halite.HaliteAuthManager? = { null },
    /** Toggles the native floating back-button overlay shown during inline
     *  HA auth (D.29). No-op default keeps existing callers compiling. */
    private val setInlineAuthBackVisible: (Boolean) -> Unit = {}
) {
    companion object {
        private const val TAG = "DashieAuth"
    }

    private var pendingHaLoginUrl: String? = null

    /** True when we're waiting for an inline auth callback in the main WebView. */
    var inlineAuthPending = false
        private set

    /** The HA base URL for the current inline auth flow. */
    private var inlineAuthHaUrl: String? = null

    /**
     * URL the main WebView was on before launching inline auth, captured
     * only for cloud-mode users (Dashie account linked) so we can restore
     * it after the OAuth callback completes. Kiosk-mode users intentionally
     * land on the HA dashboard after login, so this stays null for them.
     */
    private var preInlineAuthRestoreUrl: String? = null

    /**
     * Launch the Home Assistant login flow.
     * Called from JavaScript via DashieNative.openHaLogin(url).
     *
     * Fire tablets: launches HaLoginActivity (separate activity, native Kotlin UI)
     * Other devices: loads HA auth page directly in the main WebView (inline auth)
     */
    /**
     * Launch the HA OAuth login flow.
     *
     * @param haUrl  base URL of the user's Home Assistant instance.
     * @param forceActivityFlow when true, always use [HaLoginActivity]
     *   (separate Activity over MainActivity, dashboard WebView preserved)
     *   regardless of manufacturer. Used by the cloud-mode reauth flow
     *   where we don't want the inline-auth path replacing the
     *   dashboard URL in the main WebView. Default false preserves
     *   existing behavior for the kiosk/HA-first onboarding flow.
     */
    fun launchHaLogin(haUrl: String, forceActivityFlow: Boolean = false) {
        Log.i(TAG, "🏠 Launching HA login for URL: $haUrl (forceActivityFlow=$forceActivityFlow)")
        pendingHaLoginUrl = haUrl

        PersistentLog.info("AUTH", "openHaLogin: url=$haUrl, manufacturer=${Build.MANUFACTURER}, model=${Build.MODEL}, sdk=${Build.VERSION.SDK_INT}, forceActivity=$forceActivityFlow")
        // Native HaLoginActivity is for Fire tablet (where the WebView's HA
        // login page has soft-keyboard quirks). Fire TV uses an on-screen
        // remote keyboard that the WebView's standard auth form handles
        // fine — sending Fire TV through the native form just creates new
        // failure modes (IME not appearing on CENTER). Gate on the
        // explicit isFireTablet check rather than a manufacturer match.
        val isFireTablet = com.dashieapp.Dashie.util.DeviceInfoHelper.isFireTablet()
        if (isFireTablet || forceActivityFlow) {
            Log.i(TAG, "🏠 Using native login activity (fireTablet=$isFireTablet, forceActivity=$forceActivityFlow)")
            val intent = Intent(activity, HaLoginActivity::class.java).apply {
                putExtra(HaLoginActivity.EXTRA_HA_URL, haUrl)
            }
            standardHaLoginLauncher.launch(intent)
        } else {
            // Inline auth: load HA's OAuth page directly in the main WebView.
            // This avoids creating a second WebView (which on low-RAM Android 9 devices
            // causes the system to kill MainActivity, losing the JS callback context).
            Log.i(TAG, "🏠 Non-Fire device — using inline WebView auth")
            launchInlineAuth(haUrl)
        }
    }

    /**
     * Load HA's OAuth authorize page in the main WebView.
     * DashieWebViewClient.onPageFinished will detect the auth_callback redirect
     * and call handleInlineAuthCallback() to exchange the code for tokens.
     */
    private fun launchInlineAuth(haUrl: String) {
        val cleanUrl = haUrl.trimEnd('/')
        // Keep inlineAuthHaUrl as the FULL URL with dashboard path so we
        // store it correctly post-login. OAuth endpoints
        // (/auth/authorize, /auth/token) must use the canonical AUTH ORIGIN —
        // the origin the HA iframe actually loads from (proxy when configured),
        // not the raw haUrl — so the token's client_id matches where HA runs.
        // Falls back to the stripped local URL for pre-config / local-only setups.
        val baseUrl = halitePrefsProvider()?.connection?.getAuthOrigin() ?: stripDashboardPath(cleanUrl)
        inlineAuthHaUrl = cleanUrl
        inlineAuthPending = true
        setInlineAuthBackVisible(true)

        // Cloud-mode: capture the current dashboard URL so we can navigate
        // back to it after the auth callback completes (otherwise the
        // WebView is stuck on HA's OAuth callback page after sign-in).
        // Kiosk-mode: leave null — those users land on HA intentionally.
        val isCloudMode = halitePrefsProvider()?.account?.isLinked == true
        val currentUrl = webViewProvider().url
        Log.i(TAG, "🏠 launchInlineAuth: haUrl=$cleanUrl baseUrl=$baseUrl isCloudMode=$isCloudMode currentUrl=$currentUrl")
        PersistentLog.info("AUTH",
            "launchInlineAuth: baseUrl=$baseUrl cloudMode=$isCloudMode currentUrl=${currentUrl?.take(80)}")
        preInlineAuthRestoreUrl = if (isCloudMode) currentUrl else null

        val clientId = "$baseUrl/"
        val redirectUri = "$baseUrl/?auth_callback=1"

        val authUrl = "$baseUrl/auth/authorize" +
                "?client_id=${URLEncoder.encode(clientId, "UTF-8")}" +
                "&redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}" +
                "&response_type=code"

        Log.i(TAG, "🏠 Loading inline auth URL: $authUrl")
        PersistentLog.info("AUTH", "Inline auth: loading $authUrl")

        // Save the URL to prefs so it survives activity recreation
        halitePrefsProvider()?.connection?.let { conn ->
            conn.pendingHaLoginUrl = cleanUrl
        }

        // Show a loading page while the HA auth page loads (can take 10+ seconds
        // on remote connections). The page auto-redirects to the auth URL.
        val escapedAuthUrl = authUrl.replace("'", "\\'")
        val loadingHtml = """
            <!DOCTYPE html>
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              body { margin:0; background:#1a1a2e; display:flex; align-items:center;
                     justify-content:center; height:100vh; font-family:-apple-system,sans-serif; }
              .container { text-align:center; color:#e0e0e0; }
              .spinner { width:48px; height:48px; border:4px solid rgba(255,255,255,0.15);
                         border-top-color:#4fc3f7; border-radius:50%; margin:0 auto 24px;
                         animation:spin 0.8s linear infinite; }
              @keyframes spin { to { transform:rotate(360deg); } }
              h2 { font-size:20px; font-weight:500; margin:0 0 8px; }
              p { font-size:14px; color:#888; margin:0; }
            </style></head><body>
            <div class="container">
              <div class="spinner"></div>
              <h2>Connecting to Home Assistant</h2>
              <p>${cleanUrl}</p>
            </div>
            <script>setTimeout(function(){location.href='$escapedAuthUrl';},400);</script>
            </body></html>
        """.trimIndent()

        webViewProvider().loadDataWithBaseURL(
            cleanUrl, loadingHtml, "text/html", "UTF-8", null
        )
    }

    /**
     * Called from DashieWebViewClient when an auth_callback URL is detected
     * during inline auth. Extracts the auth code and exchanges it for tokens.
     *
     * @param url The full callback URL containing code= parameter
     * @return true if this was handled as an inline auth callback
     */
    fun handleInlineAuthCallback(url: String): Boolean {
        if (!inlineAuthPending) return false

        val haUrl = inlineAuthHaUrl ?: return false

        // Extract auth code from URL
        val uri = android.net.Uri.parse(url)
        val authCode = uri.getQueryParameter("code")

        if (authCode == null) {
            Log.e(TAG, "🏠 Inline auth callback but no code parameter")
            PersistentLog.error("AUTH", "Inline auth callback but no code param: ${url.take(120)}")
            inlineAuthPending = false
            setInlineAuthBackVisible(false)
            notifyHaLoginFailed(fromInlineAuth = true)
            return true
        }

        Log.i(TAG, "🏠 Inline auth callback — exchanging code for tokens")
        PersistentLog.info("AUTH", "Inline auth callback — exchanging code (url=${url.take(80)})")
        inlineAuthPending = false
        setInlineAuthBackVisible(false)

        exchangeCodeForToken(haUrl, authCode)
        return true
    }

    /**
     * Cancel an in-progress inline HA login (the Fire TV / non-Fire-tablet
     * path) and return the user to safety.
     *
     * Inline auth loads HA's own OAuth page into the main WebView. That page
     * has no window.dashieHandleBack(), so MainActivity's Back handler — which
     * forwards Back to dashieHandleBack() — is a silent no-op there, trapping
     * the user on the HA login screen with no way out (D.5). MainActivity
     * calls this from its Back handler so Back cancels the login instead.
     *
     * @return true if an inline auth was pending and has now been cancelled.
     */
    fun cancelInlineAuth(): Boolean {
        if (!inlineAuthPending) return false
        Log.i(TAG, "🏠 Inline HA auth cancelled by user (Back)")
        PersistentLog.info("AUTH", "Inline auth cancelled by Back press")
        inlineAuthPending = false
        inlineAuthHaUrl = null
        setInlineAuthBackVisible(false)
        val restoreUrl = preInlineAuthRestoreUrl
        preInlineAuthRestoreUrl = null
        val webView = webViewProvider()
        if (restoreUrl != null) {
            // Cloud mode — return to the dashboard the user came from.
            Log.i(TAG, "🏠 Restoring pre-auth dashboard URL")
            webView.loadUrl(restoreUrl)
        } else {
            // Kiosk mode — return to the kiosk shell. Reloading the shell
            // recreates the onboarding controller fresh, which would restart
            // at the welcome screen. Set pendingHaSetup so onboarding-controller
            // .initialize() jumps straight back to the HA config screen
            // (getPendingHaSetup()) — the step the user came from.
            halitePrefsProvider()?.connection?.pendingHaSetup = true
            val shellUrl = halitePrefsProvider()?.connection?.getShellPageUrl()
            if (shellUrl != null) {
                if (shellUrl.contains("/kiosk-shell.html")) {
                    webView.loadShellFromAssets(shellUrl)
                } else {
                    webView.loadUrl(shellUrl)
                }
            }
        }
        return true
    }

    /**
     * After restoring the dashboard URL post-HA-login, trigger the
     * existing settingsStore sync path so the JS dashboard sees the
     * newly-stored HA tokens. The path:
     *
     *   DashieNative.getHaConnectionSettings()  // read Kotlin prefs
     *     → settingsStore.set('home_assistant.access_token', ...)
     *     → haService.configure({ access_token, ... })
     *
     * is exposed by Settings.applyPendingKioskHaSync. Existing
     * device-registration code calls it on cloud restore — we invoke
     * the same path here after a fresh login. Polls for readiness so
     * we're tolerant of slow page loads (typical 1–3 s).
     */
    /**
     * Reduce a full HA URL (e.g. http://192.168.1.50:8123/ha-dashie)
     * down to its base (scheme://host:port). HA's OAuth endpoints live
     * at the base — using the dashboard path causes /auth/token to
     * return HTTP 405. Falls back to a string-only strip if URL parsing
     * fails (shouldn't happen for HA URLs but defensive).
     */
    private fun stripDashboardPath(haUrl: String): String {
        return try {
            val parsed = URL(haUrl)
            val portPart = if (parsed.port == -1 || parsed.port == parsed.defaultPort) {
                ""
            } else {
                ":${parsed.port}"
            }
            "${parsed.protocol}://${parsed.host}$portPart"
        } catch (e: Exception) {
            // Best-effort fallback: drop everything after the third slash
            // ("scheme://host[:port]/...").
            val firstSlashAfterScheme = haUrl.indexOf("//").takeIf { it >= 0 }?.plus(2) ?: 0
            val pathStart = haUrl.indexOf('/', firstSlashAfterScheme)
            if (pathStart > 0) haUrl.substring(0, pathStart) else haUrl.trimEnd('/')
        }
    }

    private fun schedulePostRestoreHaSync(webView: WebView) {
        Log.i(TAG, "🏠 schedulePostRestoreHaSync: queued (1s delay before first poll)")
        PersistentLog.info("AUTH", "schedulePostRestoreHaSync queued")
        // Reports back to Kotlin so PersistentLog captures the outcome
        // even when remote-debug isn't attached.
        // BUNDLE-EXEMPT: Settings — post-restore HA sync into the webapp; 50x retry then loud logAuthEvent timeout
        val syncJs = """
            (function syncHaWithRetry(attempts) {
                var report = function(stage, detail) {
                    try {
                        if (window.DashieNative && window.DashieNative.logAuthEvent) {
                            window.DashieNative.logAuthEvent('haSync:' + stage,
                                attempts + (detail ? ' ' + detail : ''));
                        }
                    } catch (e) {}
                    console.log('[HaLogin] haSync:' + stage + ' attempts=' + attempts +
                                (detail ? ' ' + detail : ''));
                };
                if (attempts > 50) {
                    report('timeout', 'settingsStore not ready after 10s');
                    return;
                }
                if (!window.settingsStore || !window.Settings || !window.DashieNative ||
                    typeof window.Settings.applyPendingKioskHaSync !== 'function' ||
                    typeof window.DashieNative.getHaConnectionSettings !== 'function') {
                    if (attempts === 0) report('waiting', 'awaiting settingsStore + Settings');
                    setTimeout(function() { syncHaWithRetry(attempts + 1); }, 200);
                    return;
                }
                try {
                    var haRaw = window.DashieNative.getHaConnectionSettings();
                    if (!haRaw) {
                        report('empty', 'getHaConnectionSettings returned empty');
                        return;
                    }
                    var ha = JSON.parse(haRaw);
                    var hasToken = !!ha.access_token;
                    var url = ha.url || '';
                    window._pendingKioskHaSync = ha;
                    window.Settings.applyPendingKioskHaSync(ha);
                    report('success', 'hasToken=' + hasToken + ' url=' + url);

                    // D.79 final fix — DO NOT force-reload the HA iframe here.
                    // The queue set by onLoginSuccess + the webView.loadUrl
                    // sequence means the natural HA-iframe load consumes
                    // the token via shouldInterceptRequest (account-linked
                    // path). Our previous force-reload destroyed that
                    // working state by triggering a SECOND HA load after
                    // the queue had been consumed — HA's frontend then
                    // redirected to /auth/authorize on the empty-token
                    // load. applyPendingKioskHaSync above still pushes the
                    // HA URL to settingsStore so the HA widget picks it up
                    // and loads HA naturally; that natural load is the one
                    // that consumes the queue successfully.
                    report('iframe-reload-skipped', 'natural load handles it');
                } catch (e) {
                    report('error', e && e.message ? e.message : String(e));
                }
            })(0);
        """.trimIndent()
        // Wait 1s for the page to start loading before kicking off the poll —
        // attempts run while the page bootstraps Settings + settingsStore.
        webView.postDelayed({
            Log.i(TAG, "🏠 schedulePostRestoreHaSync: firing poll JS now")
            webView.evaluateJavascript(syncJs, null)
        }, 1000)
    }

    /**
     * Exchange an OAuth authorization code for access/refresh tokens.
     *
     * @param haUrl  full HA URL (may include a dashboard path like
     *   `/ha-dashie`). Stored verbatim on success.
     *
     * client_id MUST match what was sent in /auth/authorize. Since
     * launchInlineAuth uses the base URL there, we use the base here
     * too — using the full URL with dashboard path leads HA to reject
     * the code as `invalid_request: Invalid code`.
     */
    private fun exchangeCodeForToken(haUrl: String, authCode: String) {
        // Must match the client_id sent in launchInlineAuth's /auth/authorize —
        // both now use the canonical auth origin (proxy when configured).
        val baseUrl = halitePrefsProvider()?.connection?.getAuthOrigin() ?: stripDashboardPath(haUrl)
        val clientId = "$baseUrl/"
        // OAuth /auth/token endpoint lives at the base URL only, NOT under
        // any dashboard path. POSTing to <base>/<dashboard>/auth/token
        // gets HTTP 405 from HA.
        val tokenUrl = "$baseUrl/auth/token"

        activity.lifecycleScope.launch(Dispatchers.IO) {
            when (val result = HaOAuthClient.exchangeAuthorizationCode(baseUrl, authCode, clientId)) {
                is HaOAuthClient.Result.Success -> {
                    Log.i(TAG, "🏠 Inline token exchange successful (access=${result.accessToken.length} chars)")
                    PersistentLog.info("AUTH", "Token exchange SUCCESS (${result.accessToken.length} chars)")
                    withContext(Dispatchers.Main) {
                        onLoginSuccess(result.accessToken, result.refreshToken, haUrl, result.expiresIn, fromInlineAuth = true)
                    }
                }
                is HaOAuthClient.Result.HttpError -> {
                    Log.e(TAG, "🏠 Inline token exchange failed: ${result.code} - ${result.body}")
                    PersistentLog.error("AUTH", "Token exchange FAILED: HTTP ${result.code} - ${result.body?.take(200)}")
                    withContext(Dispatchers.Main) { notifyHaLoginFailed(fromInlineAuth = true) }
                }
                is HaOAuthClient.Result.NetworkError -> {
                    Log.e(TAG, "🏠 Inline token exchange error: ${result.message}")
                    PersistentLog.error("AUTH", "Token exchange ERROR: ${result.message}")
                    withContext(Dispatchers.Main) { notifyHaLoginFailed(fromInlineAuth = true) }
                }
            }
        }
    }

    /**
     * Handle the result from HaLoginActivity (Fire tablet native login).
     * Stores the token and notifies the web app.
     */
    fun handleStandardHaLoginResult(result: ActivityResult) {
        val data = result.data
        val haUrl = pendingHaLoginUrl ?: ""

        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val accessToken = data.getStringExtra(HaLoginActivity.RESULT_ACCESS_TOKEN) ?: ""
            val refreshToken = data.getStringExtra(HaLoginActivity.RESULT_REFRESH_TOKEN) ?: ""

            if (accessToken.isNotEmpty()) {
                onLoginSuccess(accessToken, refreshToken, haUrl, 1800L)
            } else {
                Log.w(TAG, "🏠 HA login returned OK but no token")
                notifyHaLoginFailed()
            }
        } else {
            val reconfigure = data?.getBooleanExtra(HaWebLoginActivity.RESULT_RECONFIGURE, false) == true
                    || data?.getBooleanExtra(HaLoginActivity.RESULT_RECONFIGURE, false) == true
            Log.i(TAG, "🏠 HA login cancelled or failed (reconfigure=$reconfigure)")
            notifyHaLoginFailed()
        }

        pendingHaLoginUrl = null
    }

    /**
     * Common success handler for both inline and activity-based login flows.
     * Stores tokens and notifies JS. Also persists a flag to SharedPreferences
     * so the onboarding can recover if the activity was recreated.
     *
     * @param fromInlineAuth true if this came from inline WebView auth (need to navigate back to shell)
     */
    private fun onLoginSuccess(accessToken: String, refreshToken: String, haUrl: String, expiresIn: Long, fromInlineAuth: Boolean = false) {
        Log.i(TAG, "🏠 HA login successful - storing token (${accessToken.length} chars, inline=$fromInlineAuth)")
        PersistentLog.info("AUTH", "Login SUCCESS: inline=$fromInlineAuth, url=$haUrl")

        // Initialize DashieApiPreferences if needed
        var apiPrefs = dashieApiPrefsProvider()
        if (apiPrefs == null) {
            apiPrefs = com.dashieapp.Dashie.api.DashieApiPreferences(activity)
            setDashieApiPrefs(apiPrefs)
        }

        // Store the tokens (web app flow)
        apiPrefs.updateHaTokens(accessToken, refreshToken, haUrl)

        // Also store in halitePrefs for kiosk token injection (onboarding flow)
        halitePrefsProvider()?.connection?.updateHaTokens(accessToken, refreshToken, expiresIn)

        // Persist pending login success to SharedPreferences so onboarding can
        // recover if the activity was recreated (low-RAM devices / Android 9).
        // For inline auth, this is the PRIMARY mechanism — the JS callback can't fire
        // because we navigated away from the kiosk shell to the HA auth page.
        halitePrefsProvider()?.connection?.let { conn ->
            conn.pendingHaLoginSuccess = true
            conn.pendingHaLoginUrl = haUrl
            Log.i(TAG, "🏠 Persisted pending login success for activity recreation recovery")
        }

        // Fetch device friendly name from HA device registry (background, best-effort).
        // Matches by Build.MODEL so the tablet gets its HA-configured name (e.g., "Mio 15\" Dashie")
        // instead of the raw model string (e.g., "rk3576_u").
        fetchDeviceFriendlyName(haUrl, accessToken)

        if (fromInlineAuth) {
            // Cloud-mode users: restore the dashboard URL we were on before
            // launching inline auth, so they land back on dev.dashieapp.com
            // (or wherever) instead of being stuck on the HA OAuth callback
            // page. The captured URL is only set in cloud-mode (see
            // launchInlineAuth) — null for kiosk-mode users who intentionally
            // land on HA after login.
            val capturedUrl = preInlineAuthRestoreUrl
            preInlineAuthRestoreUrl = null
            // If the captured URL is the Dashie web app's HA-login wrapper
            // (/login/ on dev.dashieapp.com), restoring there re-triggers
                // the same login wrapper and the iframe goes back to
            // /auth/authorize, ignoring our injected hassTokens. Override
            // with the configured Dashie root so the dashboard renders
            // and its HA widget iframe loads at HA root (where hassTokens
            // injection actually takes effect).
            val isLoginWrapper = capturedUrl != null && (
                capturedUrl.contains("/login/") || capturedUrl.endsWith("/login")
            )
            val dashieRoot = halitePrefsProvider()?.account?.dashieUrl?.trimEnd('/')
            val restoreUrl = if (isLoginWrapper && !dashieRoot.isNullOrEmpty()) {
                dashieRoot
            } else {
                capturedUrl
            }
            Log.i(TAG, "🏠 onLoginSuccess[inline]: capturedUrl=$capturedUrl restoreUrl=$restoreUrl tokenLen=${accessToken.length}")
            PersistentLog.info("AUTH",
                "onLoginSuccess[inline]: captured=${capturedUrl?.take(80)} restore=${restoreUrl?.take(80)} tokenLen=${accessToken.length}")
            if (restoreUrl != null && !restoreUrl.contains("/auth/")) {
                Log.i(TAG, "🏠 Inline auth complete (cloud-mode) — restoring dashboard URL: $restoreUrl")
                // Queue iframe token injection BEFORE the navigation so the
                // shouldInterceptRequest hook injects the hassTokens blob the
                // moment any HA-URL iframe loads (via the dashboard's HA
                // widget). Without this, HA's frontend doesn't see our
                // OAuth tokens and the widget keeps showing its login page.
                queueIframeTokenInjection(haUrl, accessToken, refreshToken, expiresIn)

                val webView = webViewProvider()
                webView.loadUrl(restoreUrl)
                // After the dashboard finishes loading, trigger the existing
                // settingsStore + haService sync path AND force a reload of
                // any HA-URL iframes so the queued injection takes effect.
                schedulePostRestoreHaSync(webView)
            } else {
                // Kiosk-mode (or no captured URL): navigate to the kiosk
                // shell so onboarding JS can check pendingHaLoginSuccess
                // and proceed to the post-login step.
                val shellUrl = halitePrefsProvider()?.connection?.getShellPageUrl()
                if (shellUrl != null) {
                    Log.i(TAG, "🏠 Inline auth complete — navigating back to kiosk shell: $shellUrl")
                    val webView = webViewProvider()
                    if (shellUrl.contains("/kiosk-shell.html")) {
                        webView.loadShellFromAssets(shellUrl)
                    } else {
                        webView.loadUrl(shellUrl)
                    }
                }
            }
        } else {
            // Activity-based auth: the kiosk shell is still loaded, try the JS callback
            val webView = webViewProvider()
            webView.evaluateJavascript(
                // WEBAPP-EXEMPT: dashieOnHaLoginComplete — kiosk-lane HA-login result; webapp lane gets onHaLoginResult from the same flow
                "if (window.dashieOnHaLoginComplete) { window.dashieOnHaLoginComplete({success:true}); }" +
                // BUNDLE-EXEMPT: onHaLoginResult — webapp-lane HA-login result; the kiosk lane gets dashieOnHaLoginComplete from the same flow
                " else if (window.onHaLoginResult) { window.onHaLoginResult(true, '${accessToken.replace("'", "\\'")}'); }",
                null
            )

            // D.79 — For cloud-mode users who enabled HA via Settings →
            // Advanced (HaLoginActivity flow), the dashboard's HA widget
            // iframe needs the same hassTokens injection the inline-auth
            // path queues. Without this, the iframe keeps showing HA's
            // login page until a cold restart (when MainHaliteSetup picks
            // up the stored tokens). Queue here too + force iframe reload.
            val isCloudMode = halitePrefsProvider()?.account?.isLinked == true
            if (isCloudMode) {
                queueIframeTokenInjection(haUrl, accessToken, refreshToken, expiresIn)
                schedulePostRestoreHaSync(webView)
            }
        }
    }

    /**
     * Build the hassTokens JSON for a fresh HA login and queue it on
     * HaliteAuthManager so the next iframe HA-URL load gets the token
     * injected via DashieWebViewClient.shouldInterceptRequest. Called
     * from both the inline-auth and activity-auth branches of
     * onLoginSuccess so the cloud-mode HA widget iframe authenticates
     * consistently regardless of which login path the user took.
     */
    private fun queueIframeTokenInjection(
        haUrl: String,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long
    ) {
        val conn = halitePrefsProvider()?.connection
        val expiryTs = System.currentTimeMillis() + (expiresIn * 1000)
        // Single source of truth: clientId/hassUrl come from the canonical auth
        // origin (proxy when configured) so injection matches mint + refresh.
        val iframeTokenJson = conn?.buildHassTokensJson(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiry = expiryTs
        )
        if (iframeTokenJson == null) {
            Log.w(TAG, "🏠 Cannot queue iframe token injection — no auth origin configured")
            return
        }
        haliteAuthManagerProvider()?.setIframeTokenInjection(iframeTokenJson)
        val origin = conn.getAuthOrigin()
        Log.i(TAG, "🏠 Queued iframe token injection (hassUrl=$origin)")
        PersistentLog.info(
            "AUTH",
            "iframe token injection queued (origin=$origin, tokenLen=${accessToken.length})"
        )
    }

    /**
     * Query /api/dashie/device/names to find this device's friendly name.
     * Best-effort: if anything fails, we silently continue with Build.MODEL.
     */
    private fun fetchDeviceFriendlyName(haUrl: String, accessToken: String) {
        val conn = halitePrefsProvider()?.connection ?: return
        if (conn.deviceFriendlyName.isNotEmpty()) {
            Log.d(TAG, "🏠 Device friendly name already set: '${conn.deviceFriendlyName}', skipping fetch")
            return
        }

        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("${haUrl.trimEnd('/')}${ApiPaths.HA}/device/names")
                val httpConn = url.openConnection() as HttpURLConnection
                httpConn.setRequestProperty("Authorization", "Bearer $accessToken")
                httpConn.connectTimeout = 10000
                httpConn.readTimeout = 10000

                if (httpConn.responseCode == 200) {
                    val body = httpConn.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)
                    val devices = json.getJSONArray("devices")
                    // Match against this device's HA-side identifier. After the
                    // 1.4.7 integration migration, that's the hardware-backed
                    // stableDeviceID. Pre-migration HA installs still have the
                    // legacy ANDROID_ID stored, so we accept either.
                    val stableId = com.dashieapp.Dashie.util.StableDeviceId.read(activity)
                    val androidId = android.provider.Settings.Secure.getString(
                        activity.contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID
                    ) ?: ""

                    for (i in 0 until devices.length()) {
                        val device = devices.getJSONObject(i)
                        val deviceAndroidId = device.optString("android_id", "")
                        val matches = (stableId.isNotEmpty() && deviceAndroidId == stableId) ||
                            (androidId.isNotEmpty() && deviceAndroidId == androidId)
                        if (matches) {
                            val name = device.optString("name", "")
                            if (name.isNotEmpty()) {
                                conn.deviceFriendlyName = name
                                Log.i(TAG, "🏠 Device friendly name from HA: '$name' (matched $deviceAndroidId)")
                            }
                            break
                        }
                    }
                } else {
                    Log.w(TAG, "🏠 Device name fetch failed: HTTP ${httpConn.responseCode}")
                }
                httpConn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "🏠 Device name fetch error: ${e.message}")
            }
        }
    }

    private fun notifyHaLoginFailed(fromInlineAuth: Boolean = false) {
        if (fromInlineAuth) {
            // Inline auth failed — navigate back to kiosk shell. Set
            // pendingHaSetup so the recreated onboarding jumps straight to the
            // HA config screen (getPendingHaSetup()) rather than restarting at
            // the welcome screen.
            Log.i(TAG, "🏠 Inline auth failed — navigating back to kiosk shell")
            PersistentLog.warn("AUTH", "Inline auth FAILED — navigating back to shell")
            halitePrefsProvider()?.connection?.pendingHaSetup = true
            val shellUrl = halitePrefsProvider()?.connection?.getShellPageUrl()
            if (shellUrl != null) {
                val webView = webViewProvider()
                if (shellUrl.contains("/kiosk-shell.html")) {
                    webView.loadShellFromAssets(shellUrl)
                } else {
                    webView.loadUrl(shellUrl)
                }
            }
        } else {
            webViewProvider().evaluateJavascript(
                "if (window.dashieOnHaLoginComplete) { window.dashieOnHaLoginComplete({success:false}); }" +
                " else if (window.onHaLoginResult) { window.onHaLoginResult(false, null); }",
                null
            )
        }
    }
}

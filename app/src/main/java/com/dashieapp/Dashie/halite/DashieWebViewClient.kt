package com.dashieapp.Dashie.halite

import com.dashieapp.Dashie.edition.ApiPaths

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.diagnostics.CrashHandler
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.halite.diagnostics.ProvenanceReporter
import com.dashieapp.Dashie.util.DeviceInfoHelper
import com.dashieapp.Dashie.webview.WebViewJsInjector
import com.dashieapp.Dashie.webview.injectors.DeviceOptimizationInjector
import com.dashieapp.Dashie.webview.injectors.HaUiHidingInjector
import com.dashieapp.Dashie.webview.injectors.MusicPlayerJsInjector
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer

/**
 * WebView client for Dashie/Halite that orchestrates:
 * - URL interception and external link routing (shouldOverrideUrlLoading)
 * - Request interception delegating to KioskCssInjector and HaMediaInterceptor
 * - Page lifecycle (onPageStarted/Finished) with retry via WebViewRetryManager
 * - Overlay injection via OverlayInjector
 * - Crash recovery via onRenderProcessGone
 *
 * Domain logic is extracted into focused helpers:
 * - KioskCssInjector: HA HTML interception, kiosk CSS/JS injection, asset serving
 * - HaMediaInterceptor: HA media API proxy with auth + HEIC conversion
 * - OverlayInjector: Dashie Lite overlay iframe injection + postMessage communication
 * - WebViewRetryManager: Retry scheduling, page load timeouts, splash screen
 */
class DashieWebViewClient(
    private val halitePrefs: HalitePreferences,
    private val splashOverlay: View
) : WebViewClient() {

    companion object {
        private const val TAG = "DashieWebViewClient"
    }

    // Extracted domain helpers
    private val kioskCssInjector = KioskCssInjector(
        halitePrefs,
        splashOverlay.context,
        { telemetryBridgeProvider?.invoke() }
    )
    private val mediaInterceptor = HaMediaInterceptor(halitePrefs)
    private val overlayInjector = OverlayInjector(halitePrefs)
    val retryManager = WebViewRetryManager(
        splashOverlay,
        { haConnectionMonitorProvider?.invoke() }
    )

    // Flag to show onboarding tips after the shell page reloads post-onboarding
    @Volatile
    var pendingOnboardingTips = false

    // Track if overlay has captured keyboard focus (settings modal open, etc.)
    @Volatile
    var overlayHasKeyboardFocus = false
        private set

    // Track if the Activity is being destroyed (used to filter out normal renderer termination)
    @Volatile
    private var isActivityDestroyed = false

    /**
     * Call from MainActivity.onDestroy() to indicate the Activity is finishing.
     * This prevents logging renderer termination as an error when it's just
     * the normal cleanup that happens when the app closes.
     */
    fun notifyActivityDestroyed() {
        isActivityDestroyed = true
    }

    // Providers for components that may be initialized later
    var authManagerProvider: (() -> HaliteAuthManager?)? = null
    /** Handles inline auth callbacks (non-Fire devices). Returns true if the URL was an auth callback. */
    var inlineAuthCallbackHandler: ((String) -> Boolean)? = null
    var telemetryBridgeProvider: (() -> DashboardTelemetryBridge?)? = null

    /** Provides the native focus ring manager so we can hide the ring on
     *  page load — JS state resets on reload but the Kotlin view stays. */
    var focusRingProvider: (() -> com.dashieapp.Dashie.halite.widgets.FocusRingManager?)? = null
    var dialogHostProvider: (() -> NativeDialogHost?)? = null
    var haConnectionMonitorProvider: (() -> HaConnectionMonitor?)? = null
    /** Provides the dashboard health coordinator so shell-ready / iframe-loaded
     *  events can arm/cancel the post-load liveness verify (Phase 1a). */
    var healthCoordinatorProvider: (() -> DashboardHealthCoordinator?)? = null

    // Callback for page load completion (used to apply zoom settings)
    var onPageLoadComplete: (() -> Unit)? = null

    // Callback when a top-level page load starts (used to hide native widgets during reload)
    var onPageLoadStarted: (() -> Unit)? = null

    // Callback when navigating to kiosk shell (hide dashboard-only widgets)
    var onKioskShellLoaded: (() -> Unit)? = null

    // Callback for WebView crash recovery - returns true if recovery was handled
    var onRendererCrashRecovery: ((WebView?) -> Boolean)? = null

    // Callback for stealth reload completion (GPU memory recovery)
    // Called when page finishes loading and a stealth reload was pending
    // Should return the URL to restore, or null if no stealth reload is pending
    var stealthReloadUrlProvider: (() -> String?)? = null
    var onStealthReloadComplete: (() -> Unit)? = null

    // Callback for memory reload path restoration
    // Called after page finishes loading to restore the HA view/path via JavaScript
    var onMemoryReloadComplete: (() -> Unit)? = null

    // Returns true if there's a pending HA URL restore (so we skip the default base-URL load)
    var hasPendingHaRestore: (() -> Boolean)? = null

    // Fire tablet: Delayed login-complete to avoid premature trigger before auth redirect
    private val loginCompleteHandler = Handler(Looper.getMainLooper())
    private var pendingLoginCompleteRunnable: Runnable? = null

    private fun cancelPendingLoginComplete() {
        pendingLoginCompleteRunnable?.let {
            loginCompleteHandler.removeCallbacks(it)
            Log.i(TAG, "📢 Cancelled pending markLoginComplete (auth redirect detected)")
        }
        pendingLoginCompleteRunnable = null
    }

    /**
     * Wait for shell JS module scripts to finish executing before calling back.
     * Module scripts (type="module") are deferred and may not have executed by onPageFinished.
     * Polls every 100ms up to 5s for window.dashieSetHaUrl to be defined.
     */
    private fun waitForShellReady(view: WebView?, onReady: () -> Unit) {
        if (view == null) return
        val handler = Handler(Looper.getMainLooper())
        var attempts = 0
        val maxAttempts = 50 // 5 seconds max
        val poll = object : Runnable {
            override fun run() {
                // WEBAPP-EXEMPT: dashieSetHaUrl — kiosk-overlay shell API
                view.evaluateJavascript("typeof window.dashieSetHaUrl === 'function'") { result ->
                    if (result == "true") {
                        Log.i(TAG, "🏠 Shell JS ready after ${attempts * 100}ms")
                        onReady()
                    } else if (++attempts < maxAttempts) {
                        handler.postDelayed(this, 100)
                    } else {
                        Log.w(TAG, "🏠 Shell JS not ready after 5s — calling anyway")
                        onReady()
                    }
                }
            }
        }
        handler.postDelayed(poll, 200) // Initial delay to give module scripts a head start
    }

    /**
     * Proxy any ingress request (HTML, JS, CSS, images, etc.) with the cached session cookie.
     * Returns the response as a WebResourceResponse, stripping security headers.
     * Unlike proxyAndStripSecurityHeaders, this handles any content type, not just HTML.
     */
    private fun proxyIngressRequest(
        url: String,
        request: WebResourceRequest,
        session: String
    ): WebResourceResponse? {
        try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = request.method ?: "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = true

            // Copy original request headers
            request.requestHeaders?.forEach { (key, value) ->
                if (!key.equals("Accept-Encoding", ignoreCase = true)) {
                    connection.setRequestProperty(key, value)
                }
            }

            // Add the ingress session cookie (the whole reason we're proxying)
            val existingCookies = connection.getRequestProperty("Cookie")
            val sessionCookie = "ingress_session=$session"
            connection.setRequestProperty("Cookie",
                if (existingCookies.isNullOrEmpty()) sessionCookie
                else "$existingCookies; $sessionCookie"
            )

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "🔌 Ingress proxy failed: $responseCode for ${url.takeLast(60)}")
                connection.disconnect()
                return null
            }

            val contentType = connection.contentType ?: "application/octet-stream"
            // Parse mime type (strip charset parameter)
            val mimeType = contentType.substringBefore(";").trim()
            val charset = if (contentType.contains("charset="))
                contentType.substringAfter("charset=").trim()
            else "UTF-8"

            val data = connection.inputStream.readBytes()
            connection.disconnect()

            // For HTML responses, inject a script that sets the ingress_session cookie
            // directly in the page's context via document.cookie. This makes it a
            // first-party cookie that the WebView will send with WebSocket connections
            // (which can't be intercepted by shouldInterceptRequest).
            if (mimeType.contains("text/html")) {
                val html = String(data, Charsets.UTF_8)
                val cookiePath = com.dashieapp.Dashie.webview.delegates.JsBridgeHaDelegate.cachedIngressPath ?: "/api/hassio_ingress/"
                val cookieScript = """<script>
document.cookie='ingress_session=$session;path=$cookiePath';
console.log('[DashieIngress] Cookie set. document.cookie=' + document.cookie);
console.log('[DashieIngress] location=' + location.href);
(function(){var O=window.WebSocket;window.WebSocket=function(u,p){console.log('[DashieIngress] WebSocket connecting to: '+u);console.log('[DashieIngress] document.cookie at WS time: '+document.cookie);var w=p?new O(u,p):new O(u);w.addEventListener('error',function(e){console.error('[DashieIngress] WS error for '+u);});w.addEventListener('close',function(e){console.log('[DashieIngress] WS closed: code='+e.code+' reason='+e.reason+' wasClean='+e.wasClean);});return w;};window.WebSocket.prototype=O.prototype;window.WebSocket.CONNECTING=O.CONNECTING;window.WebSocket.OPEN=O.OPEN;window.WebSocket.CLOSING=O.CLOSING;window.WebSocket.CLOSED=O.CLOSED;})();
</script>"""
                val modified = if (html.contains("<head>", ignoreCase = true)) {
                    html.replaceFirst("<head>", "<head>$cookieScript", ignoreCase = true)
                } else {
                    "$cookieScript$html"
                }
                Log.i(TAG, "🔌 Injected ingress_session cookie script into HTML (${data.size} → ${modified.length} bytes)")
                return WebResourceResponse(mimeType, charset,
                    java.io.ByteArrayInputStream(modified.toByteArray(Charsets.UTF_8)))
            }

            return WebResourceResponse(mimeType, charset, java.io.ByteArrayInputStream(data))
        } catch (e: Exception) {
            Log.e(TAG, "🔌 Ingress proxy error: ${e.message}")
            return null
        }
    }

    /**
     * Ensure an ingress session cookie exists before the WebView navigates to an ingress URL.
     *
     * HA's frontend JS normally creates this session via POST /api/hassio/ingress/session
     * and sets the cookie via document.cookie. But in our kiosk shell architecture (HA in a
     * cross-origin iframe), newer WebView/Chromium versions partition cookies set by JS in
     * cross-origin iframes, so the cookie isn't sent with the ingress navigation.
     *
     * This method creates the session from Kotlin using the HA access token and sets the
     * cookie via CookieManager.setCookie() which operates at the app level (not partitioned).
     *
     * Called from shouldInterceptRequest (background thread) — blocking HTTP call is OK.
     */
    /**
     * Create an ingress session via the HA API and cache it.
     * Returns the session token string, or null if creation failed.
     * Called from shouldInterceptRequest (background thread) — blocking HTTP call is OK.
     */
    private fun ensureIngressSession(ingressUrl: String, haBaseUrl: String): String? {
        val token = halitePrefs.connection.haAccessToken
        if (token.isEmpty()) {
            Log.w(TAG, "🔌 No HA access token — cannot create ingress session")
            return null
        }

        // Extract the base HA URL for the API call
        val baseUrl = if (haBaseUrl.isNotEmpty()) haBaseUrl else {
            val uri = android.net.Uri.parse(ingressUrl)
            "${uri.scheme}://${uri.authority}"
        }

        try {
            val sessionUrl = "$baseUrl/api/hassio/ingress/session"
            Log.i(TAG, "🔌 Creating ingress session: POST $sessionUrl")

            val connection = java.net.URL(sessionUrl).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.write("{}".toByteArray())

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                // Parse session token from response: {"result":"ok","data":{"session":"xxx"}}
                val json = org.json.JSONObject(body)
                val session = json.optJSONObject("data")?.optString("session")
                if (!session.isNullOrEmpty()) {
                    // Set cookie via CookieManager (app-level, not partitioned by iframe origin)
                    val cookieManager = android.webkit.CookieManager.getInstance()

                    // Extract the ingress path for the cookie scope
                    val uri = android.net.Uri.parse(ingressUrl)
                    val pathSegments = uri.pathSegments  // ["api", "hassio_ingress", "<slug>", ...]
                    val ingressPath = if (pathSegments.size >= 3) {
                        "/${pathSegments[0]}/${pathSegments[1]}/${pathSegments[2]}/"
                    } else {
                        uri.path ?: "/"
                    }

                    val cookieValue = "ingress_session=$session; path=$ingressPath; HttpOnly; SameSite=Lax"
                    val cookieUrl = "$baseUrl$ingressPath"
                    cookieManager.setCookie(cookieUrl, cookieValue)
                    cookieManager.flush()

                    // Cache for future ingress requests (avoids re-creating session each time)
                    com.dashieapp.Dashie.webview.delegates.JsBridgeHaDelegate.cachedIngressSession = session
                    com.dashieapp.Dashie.webview.delegates.JsBridgeHaDelegate.cachedIngressPath = ingressPath

                    Log.i(TAG, "🔌 ✓ Ingress session created and cached (path=$ingressPath)")
                    return session
                } else {
                    Log.w(TAG, "🔌 Ingress session response missing session token: ${body.take(200)}")
                }
            } else {
                val errorBody = try { connection.errorStream?.bufferedReader()?.use { it.readText().take(200) } } catch (_: Exception) { null }
                Log.w(TAG, "🔌 Ingress session API failed: $responseCode — $errorBody")
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "🔌 Ingress session creation error: ${e.message}")
        }
        return null
    }

    // Callback for URL changes (including SPA navigation via History API)
    // Used to update the Dashie API's currentPage field
    var onUrlChanged: ((String) -> Unit)? = null

    // State tracking for crash diagnostics
    private var currentUrl: String? = null
    private var bootProvenanceStamped = false
    private var currentTitle: String? = null
    private val startTimeMs = System.currentTimeMillis()

    /**
     * Get the current WebView state for crash reporting.
     */
    fun getWebViewCrashState(context: Context): CrashHandler.WebViewCrashState {
        return CrashHandler.WebViewCrashState(
            url = currentUrl,
            title = currentTitle,
            webViewVersion = CrashHandler.getWebViewVersion(context),
            recentJsErrors = DashieWebChromeClient.getRecentJsErrors(),
            uptimeMinutes = (System.currentTimeMillis() - startTimeMs) / 60000
        )
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        val uri = request?.url ?: return false

        // ===== DIAGNOSTIC LOGGING FOR AUTH FLOW DEBUGGING =====
        val isRedirect = request?.isRedirect == true
        val hasGesture = request?.hasGesture() == true

        DiagnosticBuffer.info("NAV", "shouldOverrideUrlLoading: url=$url isRedirect=$isRedirect hasGesture=$hasGesture")
        Log.i(TAG, "🔗 shouldOverrideUrlLoading: $url (redirect=$isRedirect, gesture=$hasGesture)")

        // Comprehensive check for auth-related URLs - must NEVER open in external browser
        val isAuthRelated = url.contains("/auth/") ||
                           url.contains("/authorize") ||
                           url.contains("/login") ||
                           url.contains("auth_callback") ||
                           url.contains("oauth") ||
                           url.contains("/signin") ||
                           url.contains("/sign-in") ||
                           url.contains("nabucasa.com") ||
                           url.contains("accounts.google.com")

        if (isAuthRelated) {
            DiagnosticBuffer.info("AUTH", "Auth URL detected (staying in WebView): $url")
            Log.i(TAG, "🔐 Auth URL detected - MUST stay in WebView")
            // Cancel any pending markLoginComplete — user is on an auth page
            if (!url.contains("auth_callback")) {
                cancelPendingLoginComplete()
            }
        }

        // Inline auth (non-Fire devices): Intercept auth_callback with code BEFORE the
        // page loads. This prevents shouldInterceptRequest from proxying the callback URL,
        // which can return 404 on some HA setups (reverse proxies, Nabu Casa, etc.)
        // where the callback URL is handled by HA's SPA router, not the server.
        if (url.contains("auth_callback=1") && url.contains("code=")) {
            val handled = inlineAuthCallbackHandler?.invoke(url) ?: false
            if (handled) {
                Log.i(TAG, "🔐 Inline auth callback intercepted in shouldOverrideUrlLoading — blocking page load")
                DiagnosticBuffer.info("AUTH", "Inline auth callback intercepted early: ${url.take(80)}")
                return true  // Block the page load entirely
            }
        }

        // Fire tablet: Intercept auth pages and launch native login
        val isAmazon = android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        if (BuildConfig.ALLOW_URL_CONFIG &&
            isAmazon &&
            halitePrefs.connection.useNativeLogin &&
            authManagerProvider?.invoke()?.shouldInterceptAuthPage(url) == true) {

            Log.i(TAG, "🔧 Fire tablet: Intercepting HA auth, launching native login")
            DiagnosticBuffer.info("AUTH", "Fire tablet: Launching native login for $url")
            val launched = authManagerProvider?.invoke()?.launchNativeLogin(url) ?: false
            if (launched) {
                cancelPendingLoginComplete()
                DiagnosticBuffer.info("AUTH", "Native login launched - not loading in WebView")
                return true
            }
            Log.i(TAG, "🔧 Native login not available, letting WebView handle OAuth flow")
            DiagnosticBuffer.warn("AUTH", "Native login failed to launch, falling back to WebView")
        }

        // Handle external URLs (from HA button tap_action: url)
        val haBaseUrl = halitePrefs.connection.haBaseUrl?.trimEnd('/')
            ?: halitePrefs.connection.haUrl.substringBefore("?").trimEnd('/')
        val startsWithBase = haBaseUrl.isNotEmpty() && url.startsWith(haBaseUrl)

        // Keep dashieapp.com URLs in WebView (Dashie account linked mode)
        val isDashieUrl = url.contains("dashieapp.com")

        DiagnosticBuffer.info("NAV", "Decision vars: haBaseUrl='$haBaseUrl' startsWithBase=$startsWithBase isAuth=$isAuthRelated isDashie=$isDashieUrl isRedirect=$isRedirect hasGesture=$hasGesture")
        Log.d(TAG, "🔗 Decision: haBaseUrl=$haBaseUrl, startsWithBase=$startsWithBase, isAuthRelated=$isAuthRelated, isDashieUrl=$isDashieUrl, isRedirect=$isRedirect, hasGesture=$hasGesture")

        val isExternalUrl = haBaseUrl.isNotEmpty() &&
                !startsWithBase &&
                (uri.scheme == "http" || uri.scheme == "https") &&
                !isAuthRelated &&
                !isDashieUrl &&
                !isRedirect &&
                hasGesture

        DiagnosticBuffer.info("NAV", "isExternalUrl=$isExternalUrl (will ${if (isExternalUrl) "OPEN BROWSER" else "stay in WebView"})")

        if (isExternalUrl) {
            Log.i(TAG, "🔗 External link clicked - opening in browser: $url")
            DiagnosticBuffer.info("NAV", "Opening external browser for: $url")
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                view?.context?.startActivity(intent)
                DiagnosticBuffer.info("NAV", "✓ External browser launched successfully")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open external URL: ${e.message}")
                DiagnosticBuffer.error("NAV", "Failed to launch browser: ${e.message}")
            }
        } else {
            val reasons = mutableListOf<String>()
            if (haBaseUrl.isEmpty()) reasons.add("haBaseUrl empty")
            if (startsWithBase) reasons.add("matches haBaseUrl")
            if (isAuthRelated) reasons.add("auth URL")
            if (isDashieUrl) reasons.add("dashieapp.com URL")
            if (isRedirect) reasons.add("is redirect")
            if (!hasGesture) reasons.add("no gesture (programmatic)")
            DiagnosticBuffer.debug("NAV", "Staying in WebView: ${reasons.joinToString(", ")}")
        }

        return false
    }

    // Intercept HTML responses to inject WebSocket proxy and kiosk CSS BEFORE any page JS runs
    // Also intercepts HA media API requests to add auth token (bypasses CORS)
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (!BuildConfig.ALLOW_URL_CONFIG) {
            return null
        }

        val url = request?.url?.toString() ?: return null
        val uri = request.url ?: return null

        // Log all /api/ requests to diagnostic buffer if logging is enabled
        if (halitePrefs.performance.logWebViewApiRequests && url.contains("/api/")) {
            val method = request.method ?: "GET"
            val userAgent = request.requestHeaders?.get("User-Agent") ?: "unknown"
            val authHeader = if (request.requestHeaders?.containsKey("Authorization") == true) "present" else "missing"
            DiagnosticBuffer.info("WebView-API", "$method $url | Auth: $authHeader | UA: ${userAgent.take(50)}")
        }

        val acceptHeader = request.requestHeaders?.get("Accept") ?: ""
        val isMainFrame = request.isForMainFrame
        Log.d(TAG, "🔍 shouldInterceptRequest: url=$url, accept=$acceptHeader, mainFrame=$isMainFrame")

        // Serve overlay webapp files from APK assets
        val assetResponse = kioskCssInjector.serveOverlayFromAssets(url)
        if (assetResponse != null) {
            return assetResponse
        }

        // Proxy virtual HA API requests (JS fetches from dashie-ha-api.local to bypass CORS)
        if (mediaInterceptor.isVirtualHaApiRequest(url)) {
            return mediaInterceptor.interceptVirtualHaApiRequest(url)
        }

        // Intercept HA media API requests (photos for screensaver).
        //
        // ⚠️ This is a MATCHER, not a builder — it inspects URLs this code did not create, so
        // it must accept BOTH brand prefixes regardless of edition. Bundled webapp JS
        // (assets/webapp/**) still emits `/api/dashie/…`, so narrowing this to ApiPaths.HA
        // would silently stop intercepting screensaver photos on a Chickadee build: no error,
        // the request would just sail past unauthenticated and 401.
        // Being liberal in what we accept is correct here, and stays correct after the JS is
        // parameterised — at which point this can narrow, deliberately and with a device check.
        if (url.contains("/api/dashie/media/image/") || url.contains("/api/chickadee/media/image/")) {
            return mediaInterceptor.interceptHaMediaRequest(url)
        }

        // Proxy ALL ingress requests (HTML, JS, CSS, images) with the cached session cookie.
        // Must be BEFORE the isHtmlRequest check because ingress sub-resources also need auth.
        // The ingress_session cookie can't be sent by WebView due to cross-origin iframe
        // cookie partitioning, so we proxy every ingress request with the cookie added.
        val isIngressUrl = uri.path?.startsWith("/api/hassio/ingress/") == true ||
            uri.path?.startsWith("/api/hassio_ingress/") == true
        if (isIngressUrl) {
            var session = com.dashieapp.Dashie.webview.delegates.JsBridgeHaDelegate.cachedIngressSession
            if (session == null) {
                // No cached session yet (race condition on first load).
                // Create one on the spot via the HA API — we're already on a background
                // thread (shouldInterceptRequest), so the blocking HTTP call is fine.
                val haBaseUrl = halitePrefs.connection.haBaseUrl?.trimEnd('/') ?: ""
                Log.i(TAG, "🔌 No cached ingress session — creating one for: ${url.takeLast(60)}")
                session = ensureIngressSession(url, haBaseUrl)
            }
            if (session != null) {
                return proxyIngressRequest(url, request, session)
            }
            // Still no session (no HA token, API failed) — fall through to native handling
            Log.w(TAG, "🔌 Could not obtain ingress session — falling through for: ${url.takeLast(60)}")
        }

        // Only intercept main HTML document requests (not scripts, images, etc.)
        val isHtmlRequest = acceptHeader.contains("text/html") || isMainFrame
        if (!isHtmlRequest) {
            return null
        }

        Log.d(TAG, "🔍 HTML request detected: url=$url")

        // Check if this is an HA URL (for kiosk CSS injection)
        val haBaseUrl = halitePrefs.connection.haBaseUrl?.trimEnd('/') ?: halitePrefs.connection.haUrl.substringBefore("?").trimEnd('/')
        val isHaUrl = uri.port == 8123 ||
            uri.path?.startsWith("/lovelace") == true ||
            uri.path?.startsWith("/ha-dashie") == true ||
            uri.path?.contains("-dashboard") == true ||
            (haBaseUrl.isNotEmpty() && url.startsWith(haBaseUrl))

        Log.d(TAG, "🔍 URL check: port=${uri.port}, path=${uri.path}, haBaseUrl=$haBaseUrl, isHaUrl=$isHaUrl")

        // When Dashie account is linked, skip interception for dashieapp.com pages
        // (WS monitor's stuck-init detection would reload the page), but still
        // intercept HA URLs for widget iframe CSS injection (hide sidebar/header).
        val isAccountLinked = halitePrefs.account.showsDashboard
        if (isAccountLinked) {
            if (isHaUrl) {
                // D.79 — pending iframe token injection MUST flow through this
                // path too, otherwise the post-HA-login iframe reload only
                // gets the kiosk CSS (no hassTokens) and HA's frontend
                // redirects back to /auth/authorize. The later cross-origin
                // branch at line ~638 handles this for non-Dashie modes;
                // we need the same here for the Dashie-cloud + HA-widget
                // scenario. Only consume for iframe requests, not the
                // (unlikely-but-possible) main-frame HA load.
                val authMgr = authManagerProvider?.invoke()
                val tokenJson = if (!isMainFrame && authMgr?.iframeTokenInjectionPending == true) {
                    val json = authMgr.pendingIframeTokenJson
                    authMgr.clearIframeTokenInjection()
                    Log.i(TAG, "🔧 Injecting HA tokens into iframe HTML (account-linked path)")
                    json
                } else null
                Log.i(TAG, "🔗 Account linked: intercepting HA widget iframe for kiosk CSS: $url (token=${tokenJson != null})")
                return kioskCssInjector.interceptHaHtmlForKioskCss(url, request.requestHeaders, tokenJson)
            }
            Log.d(TAG, "🔍 Skipping HTML interception (Dashie account linked): $url")
            return null
        }

        // Skip HTML interception for OAuth entry points (may involve redirects
        // that break if we fetch+modify the HTML). Allow auth_callback and other
        // post-login pages — kiosk CSS injection on those pages ensures the sidebar
        // is hidden after login via HA's SPA navigation to the dashboard.
        val isOAuthEntryPoint = url.contains("/auth/authorize") || url.contains("/login_flow")
        val isAuthRelated = url.contains("/auth/") || url.contains("/authorize") ||
            url.contains("/login") || url.contains("auth_callback")

        if (isAuthRelated) {
            // Fire tablet: detect auth page loading in HA iframe (shell page mode).
            // shouldOverrideUrlLoading only fires for main frame, so in shell page mode
            // the auth page in the iframe is invisible to it. Detect it here instead.
            if (!isMainFrame && BuildConfig.ALLOW_URL_CONFIG) {
                val isAmazon = android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
                if (isAmazon && halitePrefs.connection.useNativeLogin &&
                    authManagerProvider?.invoke()?.shouldInterceptAuthPage(url) == true) {
                    Log.i(TAG, "🔧 Fire tablet: Auth page detected in iframe, launching native login")
                    DiagnosticBuffer.info("AUTH", "Fire tablet: Auth in iframe detected: $url")
                    Handler(Looper.getMainLooper()).post {
                        authManagerProvider?.invoke()?.launchNativeLogin(url)
                    }
                }
            }

            if (isOAuthEntryPoint) {
                // Proxy auth pages through our interceptor to strip security headers
                // (COOP, COEP, X-Frame-Options) that cause ERR_BLOCKED_BY_RESPONSE
                // when HA is loaded inside the kiosk shell iframe.
                // Falls back to null (native WebView handling) if proxy fails.
                Log.i(TAG, "🔐 Proxying OAuth entry point (strip security headers): $url")
                val proxied = kioskCssInjector.proxyAndStripSecurityHeaders(url, request.requestHeaders)
                if (proxied != null) return proxied
                Log.d(TAG, "🔐 Auth proxy returned null, falling back to native handling")
                return null
            }
            // Non-entry auth pages (auth_callback, etc.) — allow CSS injection below
            Log.d(TAG, "🔍 Auth-related page, allowing CSS injection: $url")
        }

        // Skip kiosk injection for /local/ files
        val isLocalFile = uri.path?.startsWith("/local/") == true
        if (isHaUrl && isLocalFile) {
            Log.d(TAG, "🏠 Skipping kiosk injection for /local/ file: $url")
            val telemetryEnabled = halitePrefs.performance.dashboardTelemetryEnabled && halitePrefs.performance.dashboardTelemetryConsent
            val pingEnabled = halitePrefs.performance.websocketPingEnabled
            val autoReloadEnabled = halitePrefs.performance.autoReloadStaleMinutes > 0
            return if (telemetryEnabled || pingEnabled || autoReloadEnabled) {
                telemetryBridgeProvider?.invoke()?.wsProxyDelegate?.interceptHtmlForWsProxy(url, request.requestHeaders)
            } else {
                null
            }
        }

        // Check if any feature needs WebSocket monitoring
        val telemetryEnabled = halitePrefs.performance.dashboardTelemetryEnabled && halitePrefs.performance.dashboardTelemetryConsent
        val pingEnabled = halitePrefs.performance.websocketPingEnabled
        val autoReloadEnabled = halitePrefs.performance.autoReloadStaleMinutes > 0
        val needsWsProxy = telemetryEnabled || pingEnabled || autoReloadEnabled

        // If this is an HA URL, inject kiosk CSS (+ tokens if pending)
        if (isHaUrl) {
            // Same-origin shell: CSS/script injection is handled via iframe.contentDocument
            // in shell JS. Token injection via loadHaIntoShellPageWithTokens() in onPageFinished.
            // But we still need WS proxy injection into the HTML for connection health monitoring —
            // without it, the WS monitor (injected later via evalInHaIframe) misses HA's initial
            // WebSocket creation and falsely triggers a "stuck initializing" reload after 30s.
            if (kioskCssInjector.isSameOriginShell()) {
                if (needsWsProxy && !kioskCssInjector.isProxyCircuitOpen()) {
                    Log.d(TAG, "🏠 Same-origin shell — injecting WS proxy only (no CSS) for: ${url.take(80)}")
                    return telemetryBridgeProvider?.invoke()?.wsProxyDelegate?.interceptHtmlForWsProxy(url, request.requestHeaders)
                }
                Log.d(TAG, "🏠 Same-origin shell — skipping HTML proxy for: ${url.take(80)}")
                return null
            }

            // Cross-origin path: fetch HTML via HttpURLConnection and inject CSS/scripts
            // Check for pending iframe token injection (post-onboarding / post-native-login).
            // Inject tokens directly into the HTML so they're in localStorage
            // before HA's frontend checks — avoids the auth redirect entirely.
            // IMPORTANT: Only consume for iframe requests. During native login, the main
            // frame loads haBaseUrl first (for main-frame token injection), and we must
            // NOT consume the iframe tokens on that main-frame load.
            val authMgr = authManagerProvider?.invoke()
            val tokenJson = if (!isMainFrame && authMgr?.iframeTokenInjectionPending == true) {
                val json = authMgr.pendingIframeTokenJson
                authMgr.clearIframeTokenInjection()
                Log.i(TAG, "🔧 Injecting HA tokens into iframe HTML (iframe token injection)")
                json
            } else null

            Log.i(TAG, "🏠 Intercepting HA HTML for kiosk CSS injection: $url")
            return kioskCssInjector.interceptHaHtmlForKioskCss(url, request.requestHeaders, tokenJson)
        }

        // Otherwise, just inject WS proxy if needed.
        // Login pages don't open HA WebSocket connections — skip the
        // intercept HTML fetch (its 10s connect + 10s read timeouts can stack
        // to ~20s of pure overhead when navigating to the login page on a
        // slow network).
        val isLoginUrl = uri.path?.startsWith("/login") == true
        if (needsWsProxy && !kioskCssInjector.isProxyCircuitOpen() && !isLoginUrl) {
            return telemetryBridgeProvider?.invoke()?.wsProxyDelegate?.interceptHtmlForWsProxy(url, request.requestHeaders)
        }

        return null
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)

        currentUrl = url
        ProvenanceReporter.lastMainUrl = url   // runtime-provenance: which JS this device loads
        // Boot provenance stamp — once, on the FIRST page start, when jsSource is actually
        // known (a pre-pageload stamp would just say "unknown").
        if (!bootProvenanceStamped) {
            bootProvenanceStamped = true
            view?.context?.let { ProvenanceReporter.stamp(it, "boot") }
        }
        PersistentLog.info("PAGELOAD", "started url=$url")
        retryManager.onPageLoadStarting(view)
        onPageLoadStarted?.invoke()

        // Hide any stale native focus ring — JS state is about to reset on
        // this page load, but the Kotlin ring view persists across reloads.
        focusRingProvider?.invoke()?.hide()

        // Kiosk-shell pages are served from local APK assets — hide splash quickly
        // instead of waiting for the full 15s failsafe timeout
        if (overlayInjector.isShellPageUrl(url)) {
            retryManager.hideSplashForLocalContent()
        }

        // CRITICAL FIX: shouldInterceptRequest is NOT called for the initial loadUrl() navigation
        // in Android WebView. So we must inject the WS proxy here as a fallback for the first page.
        // Skip when Dashie account is linked (WS monitor is for HA, not dashieapp.com).
        // Allow when force-kiosk override is active (loading HA content despite being linked).
        if (BuildConfig.ALLOW_URL_CONFIG && view != null && url != null && (!halitePrefs.account.isLinked || halitePrefs.account.forceKioskMode)) {
            val telemetryEnabled = halitePrefs.performance.dashboardTelemetryEnabled && halitePrefs.performance.dashboardTelemetryConsent
            val pingEnabled = halitePrefs.performance.websocketPingEnabled
            val autoReloadEnabled = halitePrefs.performance.autoReloadStaleMinutes > 0

            if (telemetryEnabled || pingEnabled || autoReloadEnabled) {
                Log.i(TAG, "🔌 onPageStarted: Injecting WS proxy (fallback for initial loadUrl)")
                val bridge = telemetryBridgeProvider?.invoke()
                val wsProxyScript = bridge?.wsProxyDelegate?.buildWsProxyScript()
                if (wsProxyScript != null) {
                    view.evaluateJavascript(wsProxyScript, null)
                    PersistentLog.info("HA_CONN", "WS proxy (HA monitor JS) injected — bridge=present")
                } else {
                    // bridge==null after a WebView recreation = the connection
                    // monitor never gets armed → HA disconnects go undetected.
                    PersistentLog.warn("HA_CONN",
                        "WS proxy NOT injected — bridge=${if (bridge == null) "null" else "present"} script=null")
                }
            }
        }
    }

    /**
     * Called when history is updated, including SPA navigation via History API (pushState/replaceState).
     */
    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)

        if (url != null) {
            currentUrl = url
            if (url.startsWith("http://") || url.startsWith("https://")) {
                onUrlChanged?.invoke(url)
                Log.d(TAG, "📍 URL changed (SPA nav): $url (reload=$isReload)")
            } else {
                Log.d(TAG, "📍 Ignoring non-HTTP URL: $url")
            }
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        currentUrl = url
        currentTitle = view?.title

        // Restore normal HTTP caching after a forced-fresh load. The Clear Cache
        // flow (both the Kotlin restart and the JS reload paths) sets
        // LOAD_NO_CACHE so that one load can't be served stale JS; once it has
        // finished, switch back to LOAD_DEFAULT so later reloads stay fast.
        view?.settings?.let { s ->
            if (s.cacheMode == android.webkit.WebSettings.LOAD_NO_CACHE) {
                s.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                Log.i(TAG, "🧹 Restored LOAD_DEFAULT after forced-fresh load")
            }
        }

        DiagnosticBuffer.info("NAV", "onPageFinished: $url")
        Log.i(TAG, "📄 onPageFinished: $url")
        PersistentLog.info("PAGELOAD", "finished url=$url hadMainFrameError=${retryManager.hadMainFrameError}")

        retryManager.onPageLoadFinished()

        // Inject the blank-screen paint-liveness probe (idempotent) on real pages.
        // Injected natively rather than in page source so it covers foreign HA
        // kiosk pages we don't ship. Skipped on auth/login pages.
        if (BuildConfig.ALLOW_URL_CONFIG && url != null &&
            !url.contains("/auth/") && !url.contains("/login")) {
            view?.evaluateJavascript(
                com.dashieapp.Dashie.halite.diagnostics.BlankScreenWatchdog.PAINT_PROBE_JS, null)
        }

        // Fire tablet: Check if we landed on auth page and need to launch native login
        val isAmazon = android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        if (BuildConfig.ALLOW_URL_CONFIG &&
            isAmazon &&
            halitePrefs.connection.useNativeLogin &&
            authManagerProvider?.invoke()?.shouldInterceptAuthPage(url) == true) {

            Log.i(TAG, "🔧 Fire tablet: Auth page loaded in WebView, launching native login instead")
            DiagnosticBuffer.info("AUTH", "Fire tablet: Auth page loaded, launching native login")
            cancelPendingLoginComplete()
            authManagerProvider?.invoke()?.launchNativeLogin()
            return
        }

        // Fire tablet: Inject pending tokens if native login just completed.
        // If tokens were injected, the page will redirect (to shell page or dashboard),
        // so skip all subsequent post-load injections on this temporary page.
        if (authManagerProvider?.invoke()?.injectPendingTokensIfNeeded(view, url) == true) {
            return
        }

        // Inline auth (non-Fire devices): Detect auth_callback with code parameter.
        // The main WebView loaded HA's OAuth page directly (no separate activity).
        // When HA redirects to ?auth_callback=1&code=..., we intercept here,
        // exchange the code for tokens, and navigate back to the kiosk shell.
        if (url != null && url.contains("auth_callback=1") && url.contains("code=")) {
            val handled = inlineAuthCallbackHandler?.invoke(url) ?: false
            if (handled) {
                Log.i(TAG, "🔐 Inline auth callback handled — exchanging code for tokens")
                DiagnosticBuffer.info("AUTH", "Inline auth callback intercepted: ${url.take(80)}")
                // Stop the WebView from rendering the callback page (HA would try to use the code)
                view?.stopLoading()
                return
            }
        }

        // Check for stealth reload completion (GPU memory recovery)
        val savedUrl = stealthReloadUrlProvider?.invoke()
        if (savedUrl != null && view != null) {
            Log.i(TAG, "🔄 Stealth reload page finished - restoring URL: ${savedUrl.take(80)}")
            PersistentLog.info("MEMORY", "Stealth reload page finished, restoring URL")
            onStealthReloadComplete?.invoke()
            view.loadUrl(savedUrl)
            return
        }

        // Capture pending-restore flag BEFORE onMemoryReloadComplete clears it.
        // restoreHaPathIfPending() sets pendingHaPathRestore = null after initiating
        // the JS restore, so checking hasPendingHaRestore afterward would always be false.
        val hadPendingHaRestore = hasPendingHaRestore?.invoke() == true

        // For shell pages, defer path restoration until AFTER injection scripts are set
        // (dashieSetHaUrl checks _dashieInjectionScripts to decide whether to inject kiosk CSS).
        // For non-shell pages, restore immediately.
        val isShellPage = BuildConfig.ALLOW_URL_CONFIG && overlayInjector.isShellPageUrl(url)
        if (!isShellPage) {
            onMemoryReloadComplete?.invoke()
        }

        // Hide splash screen
        retryManager.hideSplashScreen("onPageFinished")

        // ============================================================
        // KIOSK SHELL PAGE: If the shell page finished loading,
        // tell it to load HA in its iframe. Skip HA-specific injections
        // since those are handled by shouldInterceptRequest (CSS injection)
        // and the shell page JS (overlay bridge, sidebar).
        // ============================================================
        if (BuildConfig.ALLOW_URL_CONFIG && overlayInjector.isShellPageUrl(url)) {
            // Kiosk shell loaded from local APK assets — disable retry manager's
            // full-page reload so iframe failures don't destroy the sidebar
            retryManager.onKioskShellReady()

            // Phase 1a: arm the post-load liveness verify — but only when an HA iframe
            // is actually expected (setup complete). During onboarding the shell has no
            // HA iframe, so arming would fire a false CONTENT_ERROR. The iframe's load
            // event (injectPostHaLoadScripts → onIframeLoaded) cancels it on success.
            if (halitePrefs.connection.isSetupComplete) {
                healthCoordinatorProvider?.invoke()?.onShellReady()
            }

            // Hide dashboard-only native widgets (photo widget)
            onKioskShellLoaded?.invoke()

            // Hand this device's account session to the shell's settings stack (Kiosk Real Login,
            // Phase 2). This fires on EVERY shell load — including the one after the nightly
            // recreateWebView() — which is what makes the JS stack survive recreation without any
            // separate re-init bookkeeping. No-ops on an anonymous kiosk.
            com.dashieapp.Dashie.halite.auth.KioskSessionInjector.push(halitePrefs)

            val dashMenuEnabled = halitePrefs.display.dashMenuEnabled
            val dashMenuPinned = halitePrefs.display.dashMenuPinned
            Log.i(TAG, "🏠 Kiosk shell page loaded — loading HA into iframe (dashMenu: $dashMenuEnabled, pinned: $dashMenuPinned)")

            // Send sidebar config to JS shell — but keep hidden during onboarding
            val sidebarEnabled = if (halitePrefs.connection.isSetupComplete) dashMenuEnabled else false
            view?.evaluateJavascript(
                // WEBAPP-EXEMPT: dashieSetSidebarConfig — kiosk-overlay JS sidebar config
                "if(window.dashieSetSidebarConfig) dashieSetSidebarConfig({enabled:$sidebarEnabled,pinned:$dashMenuPinned});" +
                "else if(window.dashieToggleSidebar) dashieToggleSidebar($sidebarEnabled);",
                null
            )

            // Same-origin shell: pass injection scripts to shell JS BEFORE loading HA.
            // The shell JS injects these into iframe.contentDocument after HA loads.
            // Must run for ALL shell page loads including memory reload restores,
            // so it's outside the hadPendingHaRestore check below.
            if (kioskCssInjector.isSameOriginShell()) {
                val kioskScript = kioskCssInjector.buildKioskInjectionScript(
                    halitePrefs.connection.hideSidebar, halitePrefs.connection.hideTabs,
                    halitePrefs.connection.hideSearch, halitePrefs.connection.hideAssistant, halitePrefs.display.dashboardZoom
                )
                val parentBridge = kioskCssInjector.buildParentBridgeScript()
                val telemetryEnabled = halitePrefs.performance.dashboardTelemetryEnabled && halitePrefs.performance.dashboardTelemetryConsent
                val pingEnabled = halitePrefs.performance.websocketPingEnabled
                val autoReloadEnabled = halitePrefs.performance.autoReloadStaleMinutes > 0
                val wsProxy = if (telemetryEnabled || pingEnabled || autoReloadEnabled) {
                    telemetryBridgeProvider?.invoke()?.wsProxyDelegate?.buildWsProxyScript()
                } else null

                val escapedKiosk = kioskScript.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
                val escapedBridge = parentBridge.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
                val escapedWs = wsProxy?.replace("\\", "\\\\")?.replace("`", "\\`")?.replace("$", "\\$")

                val wsLiteral = if (escapedWs != null) "`$escapedWs`" else "null"
                view?.evaluateJavascript(
                    "window._dashieInjectionScripts={kioskCss:`$escapedKiosk`,parentBridge:`$escapedBridge`,wsProxy:$wsLiteral};",
                    null
                )
                Log.i(TAG, "🏠 Same-origin: passed injection scripts to shell JS")
            }

            // Now that injection scripts are set, trigger deferred path restoration.
            // dashieSetHaUrl() will see _dashieInjectionScripts and inject kiosk CSS.
            if (hadPendingHaRestore) {
                onMemoryReloadComplete?.invoke()
            }

            // Load HA into the shell's content iframe (skip during onboarding — JS handles it)
            // If there was a pending HA URL restore (from memory reload), restoreHaPathIfPending
            // already called dashieSetHaUrl with the saved URL — skip the default base-URL load.
            // Uses hadPendingHaRestore captured BEFORE onMemoryReloadComplete cleared the flag.
            if (halitePrefs.connection.isSetupComplete && !hadPendingHaRestore) {
                // Check for crash recovery URL (persisted before activity.recreate())
                val crashUrl = halitePrefs.performance.crashRestoreUrl
                val haUrl = if (!crashUrl.isNullOrEmpty()) {
                    Log.i(TAG, "🔄 Using crash restore URL: ${crashUrl.take(80)}")
                    PersistentLog.info("WEBVIEW", "Crash recovery: restoring ${crashUrl.take(80)}")
                    halitePrefs.performance.crashRestoreUrl = null  // one-time use
                    crashUrl
                } else {
                    halitePrefs.connection.buildFullUrl()
                }

                // Check for pending token injection (post-onboarding / post-native-login).
                // In same-origin mode, consume tokens here and inject via direct localStorage
                // access in dashieLoadHaWithTokens().
                // In cross-origin mode, DON'T consume here — leave iframeTokenInjectionPending
                // for shouldInterceptRequest to inject tokens into the HA HTML via the proxy.
                // The HTML proxy path is more reliable than postMessage for cross-origin.
                val authMgr = authManagerProvider?.invoke()
                val pendingTokens = if (kioskCssInjector.isSameOriginShell() &&
                    authMgr?.iframeTokenInjectionPending == true) {
                    val json = authMgr.pendingIframeTokenJson
                    authMgr.clearIframeTokenInjection()
                    Log.i(TAG, "🔧 Same-origin: consuming tokens for loadHaWithTokens path")
                    json
                } else {
                    if (authMgr?.iframeTokenInjectionPending == true) {
                        Log.i(TAG, "🔧 Cross-origin: leaving iframe tokens for shouldInterceptRequest")
                    }
                    null
                }

                // Module scripts are deferred — dashieSetHaUrl may not be defined yet.
                // Poll until the shell JS has finished executing.
                waitForShellReady(view) {
                    if (pendingTokens != null) {
                        overlayInjector.loadHaIntoShellPageWithTokens(view, haUrl, pendingTokens)
                    } else {
                        overlayInjector.loadHaIntoShellPage(view, haUrl)
                    }
                }

                // Show onboarding tips if pending (first load after onboarding completes)
                if (pendingOnboardingTips) {
                    pendingOnboardingTips = false
                    Log.i(TAG, "🏠 Showing post-onboarding tips")
                    view?.postDelayed({
                        view.evaluateJavascript(
                            // WEBAPP-EXEMPT: dashieShowOnboardingTips — kiosk-overlay onboarding tips
                            "if(window.dashieShowOnboardingTips) window.dashieShowOnboardingTips();",
                            null
                        )
                    }, 2000) // Delay for sidebar to initialize
                }
            } else {
                Log.i(TAG, "🏠 Setup not complete — skipping HA load, onboarding JS will handle")
            }

            // Post-HA-load scripts (WS monitor, telemetry, music subscription)
            // are injected event-driven via injectPostHaLoadScripts(), called
            // from DashieJSBridge.onHaIframeLoaded() when the kiosk shell
            // reports the HA iframe actually finished loading. This replaced a
            // blind postDelayed(8000) timer that raced the iframe load — if HA
            // was slow, the monitor scripts forwarded into a not-ready iframe
            // and disconnect detection silently never armed (Jules' overnight
            // non-recovery). The event fires on every HA iframe (re)load, so
            // the monitor re-arms after every recovery too.

            // Notify listener that page has loaded
            onPageLoadComplete?.invoke()
            return
        }

        // ============================================================
        // LEGACY PATH: HA loaded directly as top-level page
        // (when dash menu is disabled or using older architecture)
        // ============================================================

        // Extract Supabase JWT for authenticated Kotlin edge function calls (e.g. Google Drive photos)
        // Must run before the early-return below — fires on every dashboard page load
        if (BuildConfig.ALLOW_URL_CONFIG && url != null && !url.contains("/auth/") && !url.contains("/login")) {
            view?.let { wv ->
                SupabaseTokenExtractor.extractAndCache(wv, halitePrefs.connection) { success ->
                    if (success) Log.i(TAG, "📢 Supabase JWT extracted successfully")
                }
            }
        }

        // Same-origin shell: onPageFinished fires for same-origin iframe navigations too.
        // Skip the legacy path entirely when we're using the shell architecture —
        // kiosk CSS is already injected by KioskCssInjector in shouldInterceptRequest,
        // and HaUiHidingInjector would incorrectly add a second round of sidebar hiding.
        if (BuildConfig.ALLOW_URL_CONFIG && halitePrefs.connection.hasAddonUrl && !overlayInjector.isShellPageUrl(url)) {
            Log.d(TAG, "📄 Skipping legacy onPageFinished for iframe sub-navigation: $url")
            onPageLoadComplete?.invoke()
            return
        }

        // Fire tablet fix: Disable heavy CSS animations that interfere with keyboard input
        if (BuildConfig.ALLOW_URL_CONFIG && android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) {
            DeviceOptimizationInjector.injectAnimationPauseFix(view)
        }

        // Halite: Inject D-pad navigation support ONLY on TV devices
        val context = view?.context
        val isTvDevice = context != null &&
                (DeviceInfoHelper.isFireTV() || DeviceInfoHelper.isTVDevice(context))
        if (BuildConfig.ALLOW_URL_CONFIG && url != null && isTvDevice) {
            Log.d(TAG, "🎮 Page finished: $url - injecting D-pad support (TV device)")
            view?.postDelayed({
                WebViewJsInjector.injectDpadNavigation(view)
            }, 500)
        } else if (BuildConfig.ALLOW_URL_CONFIG && url != null) {
            Log.d(TAG, "🎮 Page finished: $url - skipping D-pad injection (not TV device)")
        }

        // Halite: Hide keyboard after page load to catch any auto-focus from HA
        if (BuildConfig.ALLOW_URL_CONFIG && context != null) {
            view?.postDelayed({
                val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(view.windowToken, 0)
                Log.d(TAG, "🔧 Page finished: hiding keyboard after page load")
            }, 1000)
        }

        // ============================================================
        // DASHIE ACCOUNT LINKED: Skip all HA-specific injections.
        // The WS monitor, telemetry, kiosk CSS, overlay, and login
        // detection are for HA — they don't apply to dashieapp.com
        // and the WS monitor's stuck-init detection would reload the page.
        // Allow when force-kiosk override is active (loading HA content).
        // ============================================================
        if (halitePrefs.account.showsDashboard) {
            // Music player: inject in full/linked mode too (HA is in a widget iframe).
            // Mirrors the kiosk shell injection (line 564-572) using the same method.
            // evalInHaIframe() is defined by core-initializer.js after widgets load.
            // Longer delay (15s vs 8s) for full mode init chain:
            //   dev.dashieapp.com → JS init → dashboard activate → widget create → HA iframe load
            if (halitePrefs.connection.isSetupComplete) {
                val musicEnabled = halitePrefs.connection.musicPlayerEnabled
                val musicEntity = halitePrefs.connection.getEffectiveMusicPlayerEntityId()
                if (musicEnabled && musicEntity.isNotEmpty()) {
                    view?.postDelayed({
                        Log.i(TAG, "🎵 Injecting music player subscription via evalInHaIframe (full mode) for: $musicEntity")
                        MusicPlayerJsInjector.injectMusicPlayerSubscriptionViaShell(view, musicEntity)
                    }, 15000)
                }
            }

            Log.i(TAG, "🔗 Dashie account linked — skipping HA injections for: $url")
            onPageLoadComplete?.invoke()
            return
        }

        // Halite: Inject CSS to hide HA UI elements based on user preferences
        if (BuildConfig.ALLOW_URL_CONFIG && url != null) {
            val hideSidebar = halitePrefs.connection.hideSidebar
            val hideTabs = halitePrefs.connection.hideTabs
            val hideSearch = halitePrefs.connection.hideSearch
            val hideAssistant = halitePrefs.connection.hideAssistant
            val zoomPercent = halitePrefs.display.dashboardZoom
            Log.i(TAG, "🔧 UI hiding prefs: sidebar=$hideSidebar, tabs=$hideTabs, search=$hideSearch, assistant=$hideAssistant, zoom=$zoomPercent%")
            Log.i(TAG, "🔧 Scheduling CSS injection in 1000ms...")
            view?.postDelayed({
                Log.i(TAG, "🔧 Executing CSS injection now (zoom=$zoomPercent%)")
                HaUiHidingInjector.injectHaUiHidingCss(view, hideSidebar, hideTabs, hideSearch, hideAssistant, zoomPercent)
            }, 1000)

            // Capture viewport diagnostics
            view?.postDelayed({
                WebViewJsInjector.injectViewportDiagnostics(view) { diagnostics ->
                    dialogHostProvider?.invoke()?.setViewportDiagnostics(diagnostics)
                }
            }, 5000)
        }

        // Halite: Inject CSS to hide video controls if low bandwidth mode enabled
        if (BuildConfig.ALLOW_URL_CONFIG && url != null && halitePrefs.display.lowBandwidthMode) {
            Log.i(TAG, "🎬 Low bandwidth mode: scheduling video controls hiding CSS injection")
            view?.postDelayed({
                DeviceOptimizationInjector.injectVideoControlsHiding(view, true)
            }, 1500)
        }

        // Halite: Detect HA login completion
        if (BuildConfig.ALLOW_URL_CONFIG && url != null) {
            val isAuthPage = url.contains("/auth/") ||
                            url.contains("/authorize") ||
                            url.contains("/login")
            val isAuthCallback = url.contains("auth_callback")
            Log.i(TAG, "📢 onPageFinished: url=$url, isAuthPage=$isAuthPage, isAuthCallback=$isAuthCallback, haLoginCompleted=${authManagerProvider?.invoke()?.haLoginCompleted}")
            if (isAuthPage && !isAuthCallback) {
                // User is on an auth/login page — cancel any pending markLoginComplete
                cancelPendingLoginComplete()
            }
            if (!isAuthPage || isAuthCallback) {
                val wasAlreadyComplete = authManagerProvider?.invoke()?.haLoginCompleted == true
                if (!wasAlreadyComplete) {
                    cancelPendingLoginComplete()
                    // Dashboard page loaded and it's not an auth page — user is logged in.
                    // Works for both token-based auth and trusted networks (which skip hassTokens).
                    pendingLoginCompleteRunnable = Runnable {
                        pendingLoginCompleteRunnable = null
                        if (authManagerProvider?.invoke()?.haLoginCompleted != true) {
                            Log.i(TAG, "📢 Auth confirmed — dashboard loaded without auth redirect")
                            authManagerProvider?.invoke()?.markLoginComplete()
                            dialogHostProvider?.invoke()?.showWelcomeToast()
                        }
                    }
                    loginCompleteHandler.postDelayed(pendingLoginCompleteRunnable!!, 5000)
                    Log.i(TAG, "📢 Scheduling login complete check in 5s")
                }

                // Inject full WebSocket monitor script
                telemetryBridgeProvider?.invoke()?.wsProxyDelegate?.injectWsMonitorFullScript()

                // Inject telemetry script
                view?.postDelayed({
                    telemetryBridgeProvider?.invoke()?.wsProxyDelegate?.injectTelemetryScript()
                }, 3000)

                // Inject music player subscription
                view?.postDelayed({
                    val enabled = halitePrefs.connection.musicPlayerEnabled
                    val explicitEntity = halitePrefs.connection.musicPlayerEntityId
                    val deviceName = halitePrefs.connection.deviceName
                    val musicPlayerEntity = halitePrefs.connection.getEffectiveMusicPlayerEntityId()
                    Log.i(TAG, "🎵 Music player check: enabled=$enabled, explicit='$explicitEntity', deviceName='$deviceName', effective='$musicPlayerEntity'")

                    if (!enabled) {
                        Log.i(TAG, "🎵 Music player disabled - skipping subscription")
                        return@postDelayed
                    }
                    if (musicPlayerEntity.isNotEmpty()) {
                        Log.i(TAG, "🎵 Injecting music player subscription for: $musicPlayerEntity")
                        MusicPlayerJsInjector.injectMusicPlayerSubscription(view, musicPlayerEntity)
                    } else {
                        Log.w(TAG, "🎵 No music player entity configured - skipping subscription")
                    }
                }, 4000)

                // Inject Dashie Lite overlay iframe (legacy path)
                overlayInjector.injectDashieLiteOverlay(view, url)

                // Inject Dash Menu sidebar (legacy path)
                overlayInjector.injectDashMenu(view, url)

                // Notify listener that page has loaded
                onPageLoadComplete?.invoke()
            }
        }
    }

    /**
     * Inject post-HA-load scripts (WS monitor, dashboard telemetry, music
     * subscription) into the HA iframe. Called event-driven from
     * DashieJSBridge.onHaIframeLoaded() when the kiosk shell reports the HA
     * iframe finished loading — replacing the old blind postDelayed(8000)
     * timer. Fires on every HA iframe (re)load, so disconnect detection
     * re-arms after every haOfflineOverlay recovery, not just first load.
     */
    fun injectPostHaLoadScripts(view: WebView) {
        // Phase 1a: the HA iframe fired its load event — proves it navigated, so cancel
        // the post-load liveness verify. Unconditional (before the setup gate) so a
        // genuine iframe load always clears a pending verify.
        healthCoordinatorProvider?.invoke()?.onIframeLoaded()

        if (!halitePrefs.connection.isSetupComplete) {
            Log.i(TAG, "🏠 injectPostHaLoadScripts: setup not complete — skipping")
            return
        }
        Log.i(TAG, "🏠 Injecting post-HA-load scripts via evalInHaIframe (HA iframe loaded)")

        // WS monitor — the kiosk-shell path for arming HA disconnect detection,
        // distinct from the direct-HA injectWsMonitorFullScript() path.
        val wsProxy = telemetryBridgeProvider?.invoke()?.wsProxyDelegate
        if (wsProxy == null) {
            PersistentLog.warn("HA_CONN",
                "WS monitor NOT forwarded — telemetry bridge null (kiosk-shell path)")
        } else if (!wsProxy.needsWsProxy()) {
            PersistentLog.warn("HA_CONN",
                "WS monitor NOT forwarded — needsWsProxy=false (kiosk-shell path)")
        } else {
            try {
                val wsScript = view.context.assets.open("js/websocket-monitor.js")
                    .bufferedReader().use { it.readText() }
                val escaped = wsScript.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
                view.evaluateJavascript("evalInHaIframe(`$escaped`);", null)
                Log.i(TAG, "🏠 WS monitor script forwarded to HA iframe")
                PersistentLog.info("HA_CONN",
                    "WS monitor forwarded to HA iframe (kiosk-shell path, on iframe-loaded event)")
            } catch (e: Exception) {
                Log.e(TAG, "🏠 Failed to load WS monitor: ${e.message}")
                PersistentLog.error("HA_CONN",
                    "WS monitor forward FAILED (kiosk-shell path): ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // Dashboard telemetry script (OOM crash-report snapshots) — always
        // injected; the snapshot is persisted locally regardless of consent.
        try {
            val telemetryScript = view.context.assets.open("js/dashboard-telemetry.js")
                .bufferedReader().use { it.readText() }
            val escapedTelemetry = telemetryScript.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
            view.evaluateJavascript("evalInHaIframe(`$escapedTelemetry`);", null)
            Log.i(TAG, "🏠 Telemetry script forwarded to HA iframe")
        } catch (e: Exception) {
            Log.e(TAG, "🏠 Failed to load telemetry script: ${e.message}")
        }

        // Music player subscription
        val musicEnabled = halitePrefs.connection.musicPlayerEnabled
        val musicEntity = halitePrefs.connection.getEffectiveMusicPlayerEntityId()
        if (musicEnabled && musicEntity.isNotEmpty()) {
            Log.i(TAG, "🎵 Injecting music player subscription via evalInHaIframe for: $musicEntity")
            com.dashieapp.Dashie.webview.injectors.MusicPlayerJsInjector.injectMusicPlayerSubscriptionViaShell(view, musicEntity)
        } else {
            Log.i(TAG, "🎵 Music player disabled or no entity configured — skipping")
        }
    }

    /**
     * Accept self-signed / invalid certs for hosts on the local network so
     * a typical home setup (HA at https://192.168.x.x:8123 with HA's
     * self-signed cert, or NPM with a private CA) works out of the box.
     * Public hostnames still get strict validation — the typical attack
     * surface there is hostile WiFi, where cert validation matters.
     */
    override fun onReceivedSslError(
        view: WebView?,
        handler: android.webkit.SslErrorHandler?,
        error: android.net.http.SslError?
    ) {
        val url = error?.url
        val host = try { android.net.Uri.parse(url).host } catch (_: Exception) { null }
        if (com.dashieapp.Dashie.util.LocalNetworkHostnames.isTrustedHost(host)) {
            Log.i(TAG, "🔓 Accepting self-signed cert for trusted host: $host (url=$url)")
            DiagnosticBuffer.info("SSL", "Accepted self-signed cert for trusted host: $host")
            handler?.proceed()
        } else {
            Log.w(TAG, "❌ Rejecting bad cert for untrusted host: $host (error=${error?.primaryError}, url=$url)")
            DiagnosticBuffer.warn("SSL", "Rejected bad cert for untrusted host: $host (primaryError=${error?.primaryError})")
            handler?.cancel()
        }
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        val isMainFrame = request?.isForMainFrame == true
        val errorCode = error?.errorCode ?: 0
        val errorDesc = error?.description?.toString() ?: "Unknown error"
        val url = request?.url?.toString() ?: "unknown"

        Log.w(TAG, "⚠️ onReceivedError: mainFrame=$isMainFrame, code=$errorCode, desc=$errorDesc, url=$url")
        DiagnosticBuffer.warn("NET", "WebView error: code=$errorCode, mainFrame=$isMainFrame, url=$url")

        // Log iframe auth errors with full detail for ERR_BLOCKED_BY_RESPONSE debugging
        if (!isMainFrame && (url.contains("/auth/") || url.contains("/authorize") || url.contains("/login"))) {
            DiagnosticBuffer.error("AUTH-ERROR", "Iframe auth error: code=$errorCode, desc=$errorDesc, url=$url")
        }

        if (isMainFrame && view != null) {
            retryManager.onMainFrameError(view, errorCode, errorDesc)
        }
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
        val isMainFrame = request?.isForMainFrame == true
        val statusCode = errorResponse?.statusCode ?: 0
        val url = request?.url?.toString() ?: "unknown"

        // host+path only (drop query/fragment) so the diagnostic buffer can
        // identify a recurring 404 culprit without leaking signed-URL tokens.
        val resource = request?.url?.let { u ->
            (u.host ?: "") + (u.path ?: "")
        }?.takeIf { it.isNotBlank() } ?: "unknown"

        Log.w(TAG, "⚠️ onReceivedHttpError: mainFrame=$isMainFrame, status=$statusCode, url=$url")
        DiagnosticBuffer.warn("NET", "HTTP error: status=$statusCode, mainFrame=$isMainFrame, resource=$resource")

        if (isMainFrame && view != null) {
            // Persist main-frame HTTP errors — this is Jules' overnight "404
            // not found" signature (HA web server up, frontend not ready yet).
            PersistentLog.warn("PAGELOAD", "HTTP $statusCode on main frame — url=$url")
            retryManager.onMainFrameHttpError(view, statusCode)
        }
    }

    /**
     * Handle WebView renderer process crash or termination.
     */
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        val didCrash = detail?.didCrash() == true
        val rendererPriority = detail?.rendererPriorityAtExit()

        // If the Activity is being destroyed, this is expected cleanup
        if (isActivityDestroyed) {
            Log.i(TAG, "onRenderProcessGone during activity destroy (expected cleanup)")
            DiagnosticBuffer.info("WEBVIEW", "Renderer terminated during activity destroy (normal)")
            return true
        }

        if (didCrash) {
            Log.e(TAG, "💥 onRenderProcessGone: CRASHED (priority=$rendererPriority)")
            DiagnosticBuffer.error("WEBVIEW", "Renderer process CRASHED: priority=$rendererPriority")
            PersistentLog.error("WEBVIEW", "Renderer process CRASHED - priority=$rendererPriority")
        } else {
            Log.w(TAG, "⚠️ onRenderProcessGone: killed by system (priority=$rendererPriority)")
            DiagnosticBuffer.warn("WEBVIEW", "Renderer killed by system: priority=$rendererPriority")
            PersistentLog.warn("WEBVIEW", "Renderer killed by system - priority=$rendererPriority (normal on Android 12+)")
        }

        // Log memory state at time of renderer termination
        com.dashieapp.Dashie.halite.diagnostics.MemoryMonitor.logMemoryStats()

        val context = view?.context ?: splashOverlay.context

        if (didCrash) {
            val webViewState = getWebViewCrashState(context)
            Log.e(TAG, "💥 WebView state at crash: url=${webViewState.url}, uptime=${webViewState.uptimeMinutes}min, webview=${webViewState.webViewVersion}")
            PersistentLog.error("WEBVIEW", "URL at crash: ${webViewState.url}, uptime: ${webViewState.uptimeMinutes}min")

            try {
                CrashHandler.saveRendererCrashReport(context, didCrash, rendererPriority, webViewState)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save renderer crash report: ${e.message}")
            }
        }

        // Attempt recovery if a handler is registered
        val recoveryHandler = onRendererCrashRecovery
        if (recoveryHandler != null) {
            val actionVerb = if (didCrash) "crash" else "system kill"
            Log.i(TAG, "🔄 Attempting WebView recovery from $actionVerb...")
            PersistentLog.info("WEBVIEW", "Attempting recovery from $actionVerb - recreating WebView")
            try {
                val handled = recoveryHandler(view)
                if (handled) {
                    Log.i(TAG, "✅ WebView recovery successful")
                    PersistentLog.info("WEBVIEW", "Recovery successful")
                    return true
                } else {
                    Log.w(TAG, "⚠️ WebView recovery handler returned false")
                    PersistentLog.warn("WEBVIEW", "Recovery handler declined")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ WebView recovery failed: ${e.message}")
                PersistentLog.error("WEBVIEW", "Recovery failed: ${e.message}")
            }
        }

        return false
    }
}

package com.dashieapp.Dashie.halite

import android.content.Context
import android.util.Log
import android.webkit.WebResourceResponse
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import java.io.ByteArrayInputStream

/**
 * Handles kiosk CSS injection into Home Assistant HTML pages and serving overlay
 * files from APK assets. Extracted from DashieWebViewClient.
 *
 * Responsibilities:
 * - Intercept HA HTML to inject kiosk CSS (hide sidebar, tabs, search, assistant)
 * - Build the JS injection script with MutationObserver for shadow DOM traversal
 * - Serve overlay webapp files from bundled APK assets
 */
class KioskCssInjector(
    private val halitePrefs: HalitePreferences,
    private val assetContext: Context,
    private val telemetryBridgeProvider: () -> DashboardTelemetryBridge?
) {
    companion object {
        private const val TAG = "KioskCssInjector"
        // Circuit breaker: stop proxying after auth failures to prevent Fail2Ban triggers
        private const val AUTH_COOLDOWN_MS = 5 * 60 * 1000L  // 5 minutes
    }

    // Track auth failures to implement circuit breaker
    @Volatile private var lastAuthFailureTime = 0L
    @Volatile private var authFailureCount = 0

    /**
     * Check if proxying is currently blocked due to recent auth failures.
     * Returns true if we should skip proxying and let WebView handle natively.
     */
    fun isProxyCircuitOpen(): Boolean {
        if (authFailureCount == 0) return false
        val elapsed = System.currentTimeMillis() - lastAuthFailureTime
        if (elapsed > AUTH_COOLDOWN_MS) {
            // Cooldown expired, reset and allow proxying
            authFailureCount = 0
            lastAuthFailureTime = 0
            Log.i(TAG, "🔌 Auth circuit breaker reset — resuming proxy")
            DiagnosticBuffer.info("AUTH-CIRCUIT", "Circuit breaker reset after ${elapsed / 1000}s cooldown")
            return false
        }
        return true
    }

    /**
     * Record an auth failure (401/403) from a proxy request.
     */
    private fun recordAuthFailure(url: String, responseCode: Int) {
        authFailureCount++
        lastAuthFailureTime = System.currentTimeMillis()
        Log.w(TAG, "🔌 Auth failure #$authFailureCount ($responseCode) for $url — circuit breaker active for ${AUTH_COOLDOWN_MS / 1000}s")
        DiagnosticBuffer.warn("AUTH-CIRCUIT", "Auth failure #$authFailureCount: HTTP $responseCode for ${url.take(80)} — proxy paused ${AUTH_COOLDOWN_MS / 1000}s")
    }

    /**
     * Check if the same-origin shell architecture is active.
     * True when HA is configured AND we're in kiosk mode (Dashie account
     * NOT linked). In same-origin mode the shell page is served at
     * {haBaseUrl}/kiosk-shell.html and CSS injection uses
     * iframe.contentDocument instead of HttpURLConnection proxy.
     *
     * Cloud-mode users (account.isLinked) host the dashboard at
     * dev.dashieapp.com — that's cross-origin to HA, and the HA widget's
     * iframe needs the HttpURLConnection proxy path so token injection
     * (and CSS injection) can happen. Without the !isLinked guard, the
     * shouldInterceptRequest HA branch falls into the same-origin path
     * for cloud-mode users too and returns null without injecting tokens
     * — HA's frontend then doesn't see our OAuth tokens and the widget
     * shows its login page.
     */
    fun isSameOriginShell(): Boolean {
        if (halitePrefs.connection.getHaOrigin() == null) return false
        if (halitePrefs.account.isLinked) return false
        return true
    }

    /**
     * Serve overlay webapp files from APK assets instead of the addon server.
     * Matches requests to the configured addon server URL and returns local asset content.
     * This bundles the webapp into the APK, eliminating the need for the Mac/addon server.
     */
    fun serveOverlayFromAssets(url: String): WebResourceResponse? {
        // Serve kiosk-shell.html from APK assets when requested at any HA origin.
        // Not gated by isSameOriginShell() because HA servers never serve this file —
        // the request would always 404 without interception. Must match both proxy URL
        // (haBaseUrl, e.g. https://ha.dashieapp.com) and local URL
        // (haUrl, e.g. http://192.168.x.x:8123) since reloads/redirects may use either.
        if (url.contains("/kiosk-shell.html")) {
            val matched = halitePrefs.connection.getAllHaOrigins().any { origin ->
                url.startsWith("$origin/kiosk-shell")
            }
            if (matched) {
                return try {
                    var html = assetContext.assets.open("webapp/kiosk-shell.html")
                        .bufferedReader().use { it.readText() }
                    // Replace <base href> with the HA origin to avoid mixed content.
                    // When the shell is loaded at an HTTPS proxy URL (e.g. https://ha.dashieapp.com),
                    // the HTTP virtual URL base href causes module script loads to be blocked.
                    // Using the HA origin ensures sub-resources load over the same protocol.
                    val haOrigin = halitePrefs.connection.getHaOrigin()
                    if (haOrigin != null) {
                        val virtualUrl = halitePrefs.connection.getAddonServerUrl().trimEnd('/') + "/"
                        html = html.replace(virtualUrl, "${haOrigin.trimEnd('/')}/_dashie/")
                        Log.i(TAG, "📦 Serving shell with HA-origin base href: $haOrigin/_dashie/")
                    }
                    Log.i(TAG, "📦 Serving shell from assets at HA origin: ${url.take(60)}")
                    WebResourceResponse("text/html", "UTF-8", html.byteInputStream()).apply {
                        responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "📦 Same-origin shell asset not found: ${e.message}")
                    null
                }
            }
        }

        val addonServerUrl = halitePrefs.connection.getAddonServerUrl().trimEnd('/')

        // Check if this URL is for the addon server OR HA-origin asset path.
        // When the shell uses an HA-origin base href (/_dashie/ prefix) to avoid
        // mixed content on HTTPS proxies, sub-resources are requested at the HA origin
        // instead of the virtual overlay URL.
        val haOriginAssetPrefix = halitePrefs.connection.getAllHaOrigins()
            .firstOrNull { url.startsWith("$it/_dashie/") }
            ?.let { "$it/_dashie" }

        val matchedPrefix = when {
            url.startsWith(addonServerUrl) -> addonServerUrl
            haOriginAssetPrefix != null -> haOriginAssetPrefix
            else -> return null
        }

        // Extract the path relative to the matched prefix
        var path = url.removePrefix(matchedPrefix).trimStart('/')

        // Strip _dashie/ prefix — this is added when the shell is served at an HA origin
        // but sub-resources still need to resolve to webapp/ assets
        if (path.startsWith("_dashie/")) path = path.removePrefix("_dashie/")

        // Strip query parameters (e.g., ?retry=1)
        path = path.substringBefore('?')

        // Default to index.html for root
        if (path.isEmpty()) path = "index.html"

        val assetPath = "webapp/$path"

        return try {
            val inputStream = assetContext.assets.open(assetPath)
            val mimeType = when {
                path.endsWith(".html") -> "text/html"
                path.endsWith(".js") -> "application/javascript"
                path.endsWith(".css") -> "text/css"
                path.endsWith(".json") -> "application/json"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                path.endsWith(".svg") -> "image/svg+xml"
                path.endsWith(".md") -> "text/markdown"
                else -> "application/octet-stream"
            }

            Log.d(TAG, "📦 Serving from assets: $assetPath ($mimeType)")

            WebResourceResponse(mimeType, "UTF-8", inputStream).apply {
                // CORS headers needed when shell page is served at HA origin
                // but sub-resources load from virtual overlay URL via <base> tag
                responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
            }
        } catch (e: java.io.FileNotFoundException) {
            Log.d(TAG, "📦 Asset not found: $assetPath - falling through to network")
            null  // Fall through to network request
        } catch (e: Exception) {
            Log.w(TAG, "📦 Error serving asset: $assetPath - ${e.message}")
            null
        }
    }

    /**
     * Intercept HA HTML to inject kiosk CSS that hides sidebar, tabs, search, and assistant.
     * This is needed for iframe-based HA dashboards where evaluateJavascript won't work.
     */
    fun interceptHaHtmlForKioskCss(url: String, headers: Map<String, String>?, tokenJson: String? = null): WebResourceResponse? {
        // Circuit breaker: skip proxying if recent auth failures to avoid Fail2Ban
        if (isProxyCircuitOpen()) {
            Log.d(TAG, "🔌 Circuit breaker open — skipping kiosk CSS proxy for: ${url.take(80)}")
            return null
        }

        val hideSidebar = halitePrefs.connection.hideSidebar
        val hideTabs = halitePrefs.connection.hideTabs
        val hideSearch = halitePrefs.connection.hideSearch
        val hideAssistant = halitePrefs.connection.hideAssistant
        val zoomPercent = halitePrefs.display.dashboardZoom

        // If nothing to hide AND no tokens to inject, still inject WS proxy if needed.
        // Cloud-mode users typically have all four hide flags false; we must NOT bail
        // here when tokenJson is non-null, because the caller already cleared it from
        // authMgr — bailing would drop the tokens and the HA iframe would never get
        // them, causing it to show the HA login page.
        if (!hideSidebar && !hideTabs && !hideSearch && !hideAssistant && tokenJson.isNullOrEmpty()) {
            return telemetryBridgeProvider()?.wsProxyDelegate?.interceptHtmlForWsProxy(url, headers)
        }

        Log.i(TAG, "🏠 Kiosk settings: sidebar=$hideSidebar, tabs=$hideTabs, search=$hideSearch, assistant=$hideAssistant")

        try {
            // Fetch the original HTML
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            // Copy headers from original request (for auth cookies, etc.)
            headers?.forEach { (key, value) ->
                if (!key.equals("Accept-Encoding", ignoreCase = true)) {
                    connection.setRequestProperty(key, value)
                }
            }

            // Add cookies from WebView's CookieManager (critical for proxy auth)
            // The WebView stores auth cookies that the manual HTTP fetch needs
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url)
            if (!cookies.isNullOrEmpty()) {
                connection.setRequestProperty("Cookie", cookies)
                Log.d(TAG, "🍪 Added ${cookies.split(";").size} cookies from CookieManager")
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "🏠 HA HTML fetch failed with code $responseCode for URL: $url")
                // Track auth failures to trigger circuit breaker
                if (responseCode == 401 || responseCode == 403) {
                    recordAuthFailure(url, responseCode)
                }
                // Log error response for debugging
                try {
                    val errorStream = connection.errorStream
                    if (errorStream != null) {
                        val errorText = errorStream.bufferedReader().use { it.readText().take(500) }
                        Log.w(TAG, "🏠 Error response: $errorText")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "🏠 Could not read error response: ${e.message}")
                }
                connection.disconnect()
                return null
            }

            val contentType = connection.contentType ?: "text/html"
            if (!contentType.contains("text/html")) {
                Log.d(TAG, "🏠 Not HTML content: $contentType")
                connection.disconnect()
                return null
            }

            // Read the original HTML
            val inputStream = connection.inputStream
            val html = inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            // Build the kiosk injection script
            // Zoom is now applied on the kiosk shell container (not inside the HA iframe),
            // so pass 100 to avoid incorrect header width compensation inside the iframe.
            val kioskScript = buildKioskInjectionScript(hideSidebar, hideTabs, hideSearch, hideAssistant, 100)

            // If tokens are pending, inject them FIRST so they're in localStorage
            // before HA's frontend JS checks for them. This avoids the auth redirect.
            val tokenScript = if (!tokenJson.isNullOrEmpty()) {
                val escaped = tokenJson.replace("\\", "\\\\").replace("'", "\\'")
                "try{localStorage.setItem('hassTokens','$escaped');console.log('Dashie: tokens injected via HTML');}catch(e){}"
            } else ""

            // Inject script at the start of <head> so it runs early
            var modifiedHtml = if (html.contains("<head>", ignoreCase = true)) {
                val scripts = if (tokenScript.isNotEmpty()) "<script>$tokenScript</script><script>$kioskScript</script>" else "<script>$kioskScript</script>"
                html.replaceFirst("<head>", "<head>$scripts", ignoreCase = true)
            } else if (html.contains("<html>", ignoreCase = true)) {
                val scripts = if (tokenScript.isNotEmpty()) "<script>$tokenScript</script><script>$kioskScript</script>" else "<script>$kioskScript</script>"
                html.replaceFirst("<html>", "<html><head>$scripts</head>", ignoreCase = true)
            } else {
                val prefix = if (tokenScript.isNotEmpty()) "<script>$tokenScript</script><script>$kioskScript</script>" else "<script>$kioskScript</script>"
                "$prefix$html"
            }

            // Also inject WS proxy if needed
            val telemetryEnabled = halitePrefs.performance.dashboardTelemetryEnabled && halitePrefs.performance.dashboardTelemetryConsent
            val pingEnabled = halitePrefs.performance.websocketPingEnabled
            val autoReloadEnabled = halitePrefs.performance.autoReloadStaleMinutes > 0
            if (telemetryEnabled || pingEnabled || autoReloadEnabled) {
                val wsProxyScript = telemetryBridgeProvider()?.wsProxyDelegate?.buildWsProxyScript()
                if (wsProxyScript != null) {
                    modifiedHtml = if (modifiedHtml.contains("<head>", ignoreCase = true)) {
                        modifiedHtml.replaceFirst("<head>", "<head><script>$wsProxyScript</script>", ignoreCase = true)
                    } else {
                        "<script>$wsProxyScript</script>$modifiedHtml"
                    }
                }
            }

            // Inject postMessage bridge handler for shell page communication.
            // When HA is in an iframe inside the kiosk shell page, the shell page
            // forwards evaluateJavascript calls and music-player events via postMessage.
            val parentBridgeScript = buildParentBridgeScript()
            modifiedHtml = if (modifiedHtml.contains("<head>", ignoreCase = true)) {
                modifiedHtml.replaceFirst("<head>", "<head><script>$parentBridgeScript</script>", ignoreCase = true)
            } else {
                "<script>$parentBridgeScript</script>$modifiedHtml"
            }

            // Block HA theme persistence to prevent cross-device theme sync.
            // syncHaIframeTheme() dispatches 'settheme' which HA persists to
            // frontend/set_user_data via WebSocket. All devices sharing the same HA
            // user pick up the change. This intercepts the WS send and strips
            // selectedTheme so the theme applies locally but doesn't propagate.
            val themeBlockerScript = """(function(){var o=WebSocket.prototype.send;WebSocket.prototype.send=function(d){try{if(typeof d==='string'&&d.indexOf('set_user_data')!==-1){var m=JSON.parse(d);if(m.type==='frontend/set_user_data'&&m.key==='theme'){console.log('[Dashie] Blocked HA theme persistence');return;}}}catch(e){}return o.call(this,d);};})();"""
            modifiedHtml = if (modifiedHtml.contains("<head>", ignoreCase = true)) {
                modifiedHtml.replaceFirst("<head>", "<head><script>$themeBlockerScript</script>", ignoreCase = true)
            } else {
                "<script>$themeBlockerScript</script>$modifiedHtml"
            }

            Log.i(TAG, "🏠 Injected kiosk CSS into HA HTML (${html.length} -> ${modifiedHtml.length} bytes)")

            return WebResourceResponse(
                "text/html",
                "UTF-8",
                ByteArrayInputStream(modifiedHtml.toByteArray(Charsets.UTF_8))
            )
        } catch (e: Exception) {
            Log.e(TAG, "🏠 Failed to intercept HA HTML: ${e.message}")
            return null
        }
    }

    /**
     * Proxy an HA HTML page, stripping security headers (COOP, COEP, X-Frame-Options, CSP)
     * that would cause ERR_BLOCKED_BY_RESPONSE in an iframe.
     *
     * Used for OAuth entry points (/auth/authorize, /login_flow) which are normally
     * NOT intercepted. Some HA setups (reverse proxies, trusted_networks) add security
     * headers that block these pages from loading inside the kiosk shell iframe.
     *
     * Unlike interceptHaHtmlForKioskCss, this does NOT inject kiosk CSS — auth pages
     * need their full UI. It only strips the response headers that block iframe loading.
     */
    fun proxyAndStripSecurityHeaders(url: String, headers: Map<String, String>?, addBearerToken: Boolean = false): WebResourceResponse? {
        // Circuit breaker: skip proxying if recent auth failures
        if (isProxyCircuitOpen()) {
            Log.d(TAG, "🔌 Circuit breaker open — skipping auth proxy for: ${url.take(80)}")
            return null
        }

        try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false  // Handle redirects explicitly

            // Copy headers from original request (for auth cookies, etc.)
            // Log all request headers for debugging ingress auth issues
            Log.i(TAG, "🔍 INGRESS-DEBUG: Proxying URL: $url")
            Log.i(TAG, "🔍 INGRESS-DEBUG: Request headers from WebView (${headers?.size ?: 0}):")
            headers?.forEach { (key, value) ->
                if (!key.equals("Accept-Encoding", ignoreCase = true)) {
                    connection.setRequestProperty(key, value)
                }
                // Mask sensitive values but show cookie names and auth type
                val safeValue = when {
                    key.equals("Cookie", ignoreCase = true) -> value.split(";").joinToString("; ") {
                        val parts = it.trim().split("=", limit = 2)
                        if (parts.size == 2) "${parts[0]}=<${parts[1].length}chars>" else it
                    }
                    key.equals("Authorization", ignoreCase = true) -> value.take(15) + "..."
                    else -> value.take(80)
                }
                Log.i(TAG, "🔍 INGRESS-DEBUG:   $key: $safeValue")
            }

            // Merge cookies from WebView's CookieManager with any already set from
            // request headers. The request headers may contain ingress_session or other
            // cookies that CookieManager doesn't have yet (race with recent Set-Cookie).
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cmCookies = cookieManager.getCookie(url)
            Log.i(TAG, "🔍 INGRESS-DEBUG: CookieManager cookies for URL: ${
                cmCookies?.split(";")?.joinToString("; ") {
                    val parts = it.trim().split("=", limit = 2)
                    if (parts.size == 2) "${parts[0]}=<${parts[1].length}chars>" else it
                } ?: "null"
            }")
            if (!cmCookies.isNullOrEmpty()) {
                val existingCookies = connection.getRequestProperty("Cookie")
                if (existingCookies.isNullOrEmpty()) {
                    connection.setRequestProperty("Cookie", cmCookies)
                    Log.i(TAG, "🔍 INGRESS-DEBUG: Using CookieManager cookies (no request cookies)")
                } else {
                    // Merge: add any CookieManager cookies not already in request headers
                    val existingNames = existingCookies.split(";").mapNotNull {
                        it.trim().split("=", limit = 2).firstOrNull()?.trim()
                    }.toSet()
                    val newCookies = cmCookies.split(";").filter { cookie ->
                        val name = cookie.trim().split("=", limit = 2).firstOrNull()?.trim()
                        name != null && name !in existingNames
                    }.joinToString("; ") { it.trim() }
                    if (newCookies.isNotEmpty()) {
                        connection.setRequestProperty("Cookie", "$existingCookies; $newCookies")
                    }
                    Log.i(TAG, "🔍 INGRESS-DEBUG: Merged cookies: ${existingNames.size} from request, added ${newCookies.split(";").size} from CM")
                }
            }

            // For ingress pages, add HA Bearer token as fallback auth.
            // Ingress normally authenticates via ingress_session cookie, but the
            // Supervisor also accepts Bearer tokens as an alternative.
            if (addBearerToken) {
                val token = halitePrefs.connection.haAccessToken
                Log.i(TAG, "🔍 INGRESS-DEBUG: Bearer token available=${token.isNotEmpty()} (${token.length} chars), existing Auth header=${connection.getRequestProperty("Authorization")?.take(15) ?: "none"}")
                if (token.isNotEmpty() && connection.getRequestProperty("Authorization").isNullOrEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    Log.i(TAG, "🔍 INGRESS-DEBUG: Added Bearer token")
                }
            }

            // Log final Cookie header being sent
            val finalCookies = connection.getRequestProperty("Cookie")
            Log.i(TAG, "🔍 INGRESS-DEBUG: Final Cookie header: ${
                finalCookies?.split(";")?.joinToString("; ") {
                    val parts = it.trim().split("=", limit = 2)
                    if (parts.size == 2) "${parts[0]}=<${parts[1].length}chars>" else it
                } ?: "none"
            }")

            val responseCode = connection.responseCode
            Log.i(TAG, "🔍 INGRESS-DEBUG: Response code: $responseCode")

            // Log ALL response headers to diagnostics
            logResponseHeaders(connection, url, responseCode)

            // Log ALL response headers for debugging
            Log.i(TAG, "🔍 INGRESS-DEBUG: Response headers:")
            connection.headerFields?.forEach { (name, values) ->
                if (name != null) {
                    Log.i(TAG, "🔍 INGRESS-DEBUG:   $name: ${values.joinToString(", ").take(120)}")
                }
            }

            // For redirects, we need to return the redirect as HTML with a meta refresh
            // because WebResourceResponse doesn't support 302 responses
            if (responseCode in 301..303 || responseCode == 307 || responseCode == 308) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location != null) {
                    Log.i(TAG, "🔐 Auth redirect: $responseCode → ${location.take(80)}")
                    // Sync any Set-Cookie headers from the redirect response
                    syncResponseCookies(connection, url)
                    val redirectHtml = "<html><head><meta http-equiv=\"refresh\" content=\"0;url=$location\"></head><body></body></html>"
                    return WebResourceResponse(
                        "text/html", "UTF-8",
                        ByteArrayInputStream(redirectHtml.toByteArray(Charsets.UTF_8))
                    )
                }
                return null
            }

            if (responseCode != 200) {
                Log.w(TAG, "🔐 Auth proxy failed: $responseCode for $url")
                // Read error body for debugging
                try {
                    val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText().take(500) }
                    Log.w(TAG, "🔍 INGRESS-DEBUG: Error body: $errorBody")
                } catch (e: Exception) { /* */ }
                if (responseCode == 401 || responseCode == 403) {
                    // Don't trigger circuit breaker for ingress auth failures —
                    // they're a different auth mechanism than HA login
                    if (!addBearerToken) {
                        recordAuthFailure(url, responseCode)
                    } else {
                        Log.w(TAG, "🔍 INGRESS-DEBUG: Ingress auth failed (not triggering circuit breaker)")
                    }
                }
                connection.disconnect()
                return null
            }

            // Sync Set-Cookie headers back to WebView's CookieManager
            syncResponseCookies(connection, url)

            // Read the HTML body
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            Log.i(TAG, "🔐 Proxied auth page (${html.length} bytes), security headers stripped: $url")

            return WebResourceResponse(
                "text/html", "UTF-8",
                ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
            )
        } catch (e: Exception) {
            Log.e(TAG, "🔐 Auth proxy error: ${e.message}")
            DiagnosticBuffer.error("AUTH-PROXY", "Proxy failed for $url: ${e.message}")
            return null
        }
    }

    /**
     * Log all response headers from the HA server to DiagnosticBuffer.
     * This is the key diagnostic data for ERR_BLOCKED_BY_RESPONSE issues —
     * captures the exact security headers that the user's reverse proxy adds.
     */
    private fun logResponseHeaders(connection: java.net.HttpURLConnection, url: String, responseCode: Int) {
        try {
            val securityHeaders = listOf(
                "Cross-Origin-Opener-Policy", "Cross-Origin-Embedder-Policy",
                "Cross-Origin-Resource-Policy", "X-Frame-Options",
                "Content-Security-Policy", "Content-Security-Policy-Report-Only"
            )
            val headerLog = StringBuilder()
            headerLog.append("status=$responseCode")

            // Log security-relevant headers specifically
            for (name in securityHeaders) {
                val value = connection.getHeaderField(name)
                if (value != null) {
                    headerLog.append(", $name=$value")
                }
            }

            // Also log server header to identify reverse proxy
            val server = connection.getHeaderField("Server")
            if (server != null) headerLog.append(", Server=$server")

            DiagnosticBuffer.info("AUTH-HEADERS", "$url → $headerLog")

            // If any blocking headers are present, log as a warning
            val hasBlockingHeaders = securityHeaders.any { connection.getHeaderField(it) != null }
            if (hasBlockingHeaders) {
                DiagnosticBuffer.warn("AUTH-HEADERS", "Security headers detected — these may cause ERR_BLOCKED_BY_RESPONSE in iframe")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log response headers: ${e.message}")
        }
    }

    /**
     * Sync Set-Cookie headers from an HttpURLConnection response back to WebView's CookieManager.
     * This is critical for auth flows where HA sets session cookies on the response.
     */
    private fun syncResponseCookies(connection: java.net.HttpURLConnection, url: String) {
        try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookieHeaders = connection.headerFields["Set-Cookie"] ?: return
            for (cookie in cookieHeaders) {
                cookieManager.setCookie(url, cookie)
                Log.d(TAG, "🍪 Synced response cookie to WebView")
            }
            cookieManager.flush()
        } catch (e: Exception) {
            Log.w(TAG, "🍪 Failed to sync cookies: ${e.message}")
        }
    }

    /**
     * Build a small postMessage handler that allows the parent (shell page) to:
     * - Forward CustomEvents (music-player-play, etc.) via dispatch-event messages
     * - Execute scripts in the HA context via eval messages
     * This is needed because evaluateJavascript() only targets the top-level frame.
     * Also used by same-origin shell to inject via contentDocument.
     */
    internal fun buildParentBridgeScript(): String {
        // Parent bridge: handles postMessage from shell (eval, dispatch-event)
        // URL tracker: reports HA iframe URL changes to parent via postMessage
        // so dashieGetHaUrl() can return the current URL despite cross-origin.
        // Token tracker: intercepts writes to localStorage.hassTokens and
        // relays them to parent so native callers can refresh their cache
        // when HA rotates the refresh token. Also posts the current value
        // on load so existing tokens are captured.
        return """(function(){
window.addEventListener('message',function(e){if(!e.data||e.data.source!=='dashie-parent')return;if(e.data.type==='dispatch-event'){var evt=e.data.detail?new CustomEvent(e.data.event,{detail:e.data.detail}):new CustomEvent(e.data.event);window.dispatchEvent(evt);}else if(e.data.type==='eval'){try{new Function(e.data.script)();}catch(ex){console.error('[DashieLite] eval error:',ex);}}});
function reportUrl(){try{parent.postMessage({type:'ha-url-changed',url:location.href},'*');}catch(e){}}
var origPush=history.pushState;history.pushState=function(){origPush.apply(this,arguments);reportUrl();};
var origReplace=history.replaceState;history.replaceState=function(){origReplace.apply(this,arguments);reportUrl();};
window.addEventListener('popstate',function(){reportUrl();});
window.addEventListener('location-changed',function(){reportUrl();});
reportUrl();
var _origCookieDesc=Object.getOwnPropertyDescriptor(Document.prototype,'cookie');
if(_origCookieDesc){Object.defineProperty(document,'cookie',{get:function(){return _origCookieDesc.get.call(this);},set:function(v){_origCookieDesc.set.call(this,v);if(v&&v.indexOf('ingress_session=')===0){try{parent.postMessage({type:'ingress-cookie',cookie:v,origin:location.origin},'*');console.log('[DashieLite] Relayed ingress_session cookie to parent');}catch(ex){}}},configurable:true});}
var _lastTokenSig='';function relayHaTokens(raw,src){if(!raw){if(src==='poll')return;console.log('[DashieLite] hassTokens empty ('+src+')');return;}try{var p=JSON.parse(raw);if(!p||!p.access_token){console.log('[DashieLite] hassTokens has no access_token ('+src+')');return;}var sig=(p.access_token||'').slice(-12)+'|'+(p.expires||0);if(sig===_lastTokenSig)return;_lastTokenSig=sig;console.log('[DashieLite] Relaying hassTokens to parent ('+src+', expires='+p.expires+')');parent.postMessage({source:'dashie-ha',type:'ha-tokens',tokens:raw},'*');}catch(ex){console.error('[DashieLite] relayHaTokens parse error ('+src+'):',ex);}}
try{var _origSetItem=Storage.prototype.setItem;Storage.prototype.setItem=function(k,v){_origSetItem.apply(this,arguments);if(this===window.localStorage&&k==='hassTokens'){relayHaTokens(v,'setItem');}};}catch(ex){console.error('[DashieLite] hassTokens patch failed:',ex);}
try{relayHaTokens(window.localStorage.getItem('hassTokens'),'init');}catch(ex){}
setInterval(function(){try{relayHaTokens(window.localStorage.getItem('hassTokens'),'poll');}catch(ex){}},5000);
console.log('[DashieLite] Parent bridge handler installed');
})();"""
    }

    /**
     * Build the kiosk CSS injection script for HA.
     * Uses MutationObserver to traverse HA's shadow DOM and inject CSS at each level.
     * Also used by same-origin shell to inject via contentDocument.
     */
    /**
     * Extract the path portion of the configured home dashboard URL.
     * Used by the injected script to decide when to show the "back to home" button.
     * Returns "/" if no dashboard is configured or the URL can't be parsed.
     */
    internal fun getHomeDashboardPath(): String {
        return try {
            val fullUrl = halitePrefs.connection.buildFullUrl()
            val path = java.net.URL(fullUrl).path
            if (path.isEmpty()) "/" else path.trimEnd('/').ifEmpty { "/" }
        } catch (e: Exception) {
            "/"
        }
    }

    internal fun buildKioskInjectionScript(
        hideSidebar: Boolean,
        hideTabs: Boolean,
        hideSearch: Boolean,
        hideAssistant: Boolean,
        zoomPercent: Int = 100,
        homeDashboardPath: String = getHomeDashboardPath(),
        // TV / d-pad-only devices can't reach the floating back button
        // (it's not focusable for d-pad). Skip rendering it there.
        isTvDevice: Boolean = com.dashieapp.Dashie.util.DeviceInfoHelper.isFireTV() ||
            com.dashieapp.Dashie.util.DeviceInfoHelper.isTVDevice(assetContext),
        showFloatingBackButton: Boolean = halitePrefs.connection.showFloatingBackButton
    ): String {
        // Calculate header width needed to fill visible area when zoom < 100%
        val headerWidth = if (zoomPercent != 100 && zoomPercent > 0) {
            100.0 / (zoomPercent / 100.0)
        } else {
            100.0
        }
        return """
            (function() {
                console.log('[DashieLite] Kiosk injection script starting (HTML inject)...');
                console.log('[DashieLite] Settings: sidebar=$hideSidebar, tabs=$hideTabs, search=$hideSearch, assistant=$hideAssistant, zoom=$zoomPercent%, headerWidth=${headerWidth}vw');

                if (window._dashieKioskApplied) {
                    console.log('[DashieLite] Kiosk already applied, skipping');
                    return;
                }
                window._dashieKioskApplied = true;

                const HIDE_SIDEBAR = $hideSidebar;
                const HIDE_TABS = $hideTabs;
                const HIDE_SEARCH = $hideSearch;
                const HIDE_ASSISTANT = $hideAssistant;
                const HOME_PATH = '$homeDashboardPath';
                // TV / d-pad-only devices: don't render the floating back
                // button — it's mouse/touch only and unreachable on a remote.
                const IS_TV_DEVICE = $isTvDevice;
                // User opt-in for the floating back-to-home button.
                // Off by default; enabled via Settings → Home Assistant →
                // Return to Home → "Floating Back Button".
                const SHOW_FLOATING_BACK_BUTTON = $showFloatingBackButton;

                // Track if we've already logged success (avoid spam from MutationObserver)
                let hasLoggedSuccess = false;

                // CSS for sidebar (injected into ha-drawer's shadow root)
                // We set --mdc-drawer-width to 0 because Bubble Card uses this variable to calculate
                // popup centering: left: calc(var(--mdc-drawer-width, 0px) / 2 + 50% - ...)
                // Without setting it to 0, popups are off-center when sidebar is hidden.
                const SIDEBAR_CSS = ':host{--mdc-drawer-width:0px !important;width:100% !important;}' +
                    '#drawer{display:none !important;width:0 !important;visibility:hidden !important;position:absolute !important;}' +
                    '.mdc-drawer{display:none !important;width:0 !important;position:absolute !important;}' +
                    'aside{display:none !important;position:absolute !important;}' +
                    // Move content to fill the space where sidebar was
                    '.mdc-drawer-app-content{margin-left:0 !important;width:100% !important;}';

                // CSS to set --mdc-drawer-width globally (for Bubble Card and other popups)
                // Bubble Card reads this variable to calculate popup centering.
                // We target multiple levels to ensure custom cards can read it.
                // Also override Bubble Card's popup positioning directly to fix centering.
                const GLOBAL_SIDEBAR_CSS = '*{--mdc-drawer-width:0px !important;--app-drawer-width:0px !important;}' +
                    ':root, html, body, home-assistant{--mdc-drawer-width:0px !important;--app-drawer-width:0px !important;}' +
                    // Fix Bubble Card popup centering when sidebar is hidden
                    '.bubble-pop-up, .bubble-pop-up-container, .bubble-backdrop, [class*="bubble"]{--mdc-drawer-width:0px !important;--app-drawer-width:0px !important;}' +
                    // Direct override for Bubble Card popup positioning - force center
                    '.bubble-pop-up{left:50% !important;transform:translateX(-50%) !important;}';

                // CSS for menu button AND fix header layout when sidebar hidden (injected into hui-root's shadow root)
                // The --mdc-top-app-bar-width fix prevents the header from leaving white space on the left
                // When zoom < 100%, header needs width > 100vw to fill the visible area
                const MENUBUTTON_CSS = 'ha-menu-button{display:none !important;}' +
                    'ha-button-menu{display:none !important;}' +
                    ':host{--mdc-top-app-bar-width:${headerWidth}vw !important;}' +
                    '.mdc-top-app-bar{width:${headerWidth}vw !important;left:0 !important;}' +
                    'app-header{width:${headerWidth}vw !important;left:0 !important;}' +
                    '.toolbar{width:100% !important;margin-left:0 !important;padding-left:16px !important;}';

                // CSS for tabs (hides entire header including tabs)
                const TABS_CSS = '#view{padding-top:0 !important;--header-height:0 !important;}' +
                    'app-header{display:none !important;height:0 !important;visibility:hidden !important;}' +
                    '.header{display:none !important;}' +
                    'app-toolbar{display:none !important;}' +
                    'paper-tabs{display:none !important;}' +
                    'ha-tabs{display:none !important;}';

                // CSS to force the HA view to fill the viewport height.
                // Without this, short dashboards leave a gap at the bottom where the
                // iframe background shows through (grey bar). In Fully Kiosk the
                // dashboard runs directly in the WebView, so this isn't an issue there.
                // We always inject this regardless of sidebar/tab settings.
                const VIEW_FULLSCREEN_CSS = '#view{min-height:100vh !important;}';

                // CSS for search - hide search button in toolbar.
                // Match by label/aria-label/title rather than position. The
                // position-based :first-of-type only works when the search
                // button is the first ha-icon-button sibling under .toolbar,
                // which is true for the standard tabs header but not for
                // subviews / auto-generated dashboards (different wrapping).
                const HIDE_BUTTON_RULES = 'display:none !important;visibility:hidden !important;width:0 !important;height:0 !important;overflow:hidden !important;opacity:0 !important;pointer-events:none !important;';
                const SEARCH_CSS =
                    'ha-icon-button[slot="actionItems"]:first-of-type{' + HIDE_BUTTON_RULES + '}' +
                    'ha-icon-button[label*="Search" i],ha-icon-button[aria-label*="Search" i],ha-icon-button[title*="Search" i]{' + HIDE_BUTTON_RULES + '}' +
                    'ha-icon-button-search,search-icon-button{' + HIDE_BUTTON_RULES + '}';

                // CSS for assistant - hide assistant button in toolbar.
                // Same defensive multi-selector approach as search.
                const ASSISTANT_CSS =
                    'ha-icon-button[slot="actionItems"]:nth-of-type(2){' + HIDE_BUTTON_RULES + '}' +
                    'ha-icon-button[label*="Assist" i],ha-icon-button[aria-label*="Assist" i],ha-icon-button[title*="Assist" i]{' + HIDE_BUTTON_RULES + '}' +
                    'ha-assist-chip,assist-chip{' + HIDE_BUTTON_RULES + '}';

                function injectStyle(name, css, root) {
                    if (!root) return false;
                    const existing = root.querySelector('style[data-dashie="' + name + '"]');
                    if (existing) {
                        return true;  // Already injected, no need to log
                    }
                    const style = document.createElement('style');
                    style.setAttribute('data-dashie', name);
                    style.textContent = css;
                    root.appendChild(style);
                    console.log('[DashieLite] Injected ' + name + ' CSS');
                    return true;
                }

                function removeStyle(name, root) {
                    if (!root) return false;
                    const existing = root.querySelector('style[data-dashie="' + name + '"]');
                    if (existing) {
                        existing.remove();
                        return true;
                    }
                    return false;
                }

                function isSubview(huiRootShadow) {
                    if (!huiRootShadow) return false;
                    return !!huiRootShadow.querySelector('ha-icon-button-arrow-prev');
                }

                function tryInject() {
                    // Inject global CSS into document head first (for Bubble Card popup centering)
                    // This must be in the main document, not a shadow root, for custom cards to read it
                    if (HIDE_SIDEBAR) {
                        injectStyle('global-sidebar', GLOBAL_SIDEBAR_CSS, document.head);
                    }

                    // Get home-assistant element
                    const ha = document.querySelector('home-assistant');
                    if (!ha || !ha.shadowRoot) {
                        console.log('[DashieLite] Waiting: home-assistant not found');
                        return false;
                    }

                    // Get home-assistant-main
                    const haMain = ha.shadowRoot.querySelector('home-assistant-main');
                    if (!haMain || !haMain.shadowRoot) {
                        console.log('[DashieLite] Waiting: home-assistant-main not found');
                        return false;
                    }

                    // Get ha-drawer (contains sidebar) - inject sidebar CSS here
                    const drawer = haMain.shadowRoot.querySelector('ha-drawer');
                    if (HIDE_SIDEBAR) {
                        if (drawer && drawer.shadowRoot) {
                            injectStyle('sidebar', SIDEBAR_CSS, drawer.shadowRoot);
                        } else {
                            console.log('[DashieLite] Waiting: ha-drawer not ready');
                        }
                    }

                    // Get partial-panel-resolver -> ha-panel-lovelace -> hui-root
                    const partialPanel = haMain.shadowRoot.querySelector('partial-panel-resolver');
                    if (!partialPanel) {
                        console.log('[DashieLite] Waiting: partial-panel-resolver not found');
                        return false;
                    }

                    const haPanel = partialPanel.querySelector('ha-panel-lovelace');
                    if (!haPanel || !haPanel.shadowRoot) {
                        console.log('[DashieLite] Waiting: ha-panel-lovelace not found');
                        return false;
                    }

                    const huiRoot = haPanel.shadowRoot.querySelector('hui-root');
                    if (!huiRoot || !huiRoot.shadowRoot) {
                        console.log('[DashieLite] Waiting: hui-root not found');
                        return false;
                    }

                    const huiRootShadow = huiRoot.shadowRoot;

                    // Only log once to avoid console spam from MutationObserver
                    if (!hasLoggedSuccess) {
                        console.log('[DashieLite] All shadow DOM elements found, injecting CSS...');
                        // DEBUG: dump toolbar action items so we can see what selector to use.
                        // The selectors below assume ha-icon-button[slot="actionItems"], but HA
                        // may have changed the element type or wrapper.
                        try {
                            const allButtons = Array.from(huiRootShadow.querySelectorAll('*'))
                                .filter(el => {
                                    const tag = el.tagName.toLowerCase();
                                    return tag.includes('icon-button') || tag.includes('search') || tag.includes('assist');
                                })
                                .map(el => ({
                                    tag: el.tagName.toLowerCase(),
                                    slot: el.getAttribute('slot') || '',
                                    label: el.getAttribute('label') || el.getAttribute('aria-label') || '',
                                    cls: el.className || ''
                                }));
                            console.log('[DashieLite] DEBUG toolbar buttons: ' + JSON.stringify(allButtons));
                        } catch (e) { console.log('[DashieLite] DEBUG enum failed: ' + e); }
                    }

                    // Inject menu button CSS (hides button and fixes header width when sidebar hidden)
                    if (HIDE_SIDEBAR) {
                        injectStyle('menubutton', MENUBUTTON_CSS, huiRootShadow);
                    }

                    // Always inject view fullscreen CSS to prevent grey bar at bottom
                    injectStyle('view-fullscreen', VIEW_FULLSCREEN_CSS, huiRootShadow);

                    // Inject or remove tabs/header CSS based on subview state
                    if (HIDE_TABS) {
                        if (isSubview(huiRootShadow)) {
                            removeStyle('tabs', huiRootShadow);
                        } else {
                            injectStyle('tabs', TABS_CSS, huiRootShadow);
                        }
                    }

                    // Inject search CSS - hide search button in toolbar
                    if (HIDE_SEARCH) {
                        injectStyle('search', SEARCH_CSS, huiRootShadow);
                    }

                    // Inject assistant CSS - hide assistant button in toolbar
                    if (HIDE_ASSISTANT) {
                        injectStyle('assistant', ASSISTANT_CSS, huiRootShadow);
                    }

                    // Only log success once
                    if (!hasLoggedSuccess) {
                        console.log('[DashieLite] ✓ Kiosk styles applied successfully');
                        hasLoggedSuccess = true;
                    }
                    return true;
                }

                let retries = 0;
                const maxRetries = 60;
                const interval = setInterval(function() {
                    retries++;
                    try {
                        const success = tryInject();
                        if (success || retries >= maxRetries) {
                            clearInterval(interval);
                            console.log('[DashieLite] Kiosk injection complete after ' + retries + ' attempts, success=' + success);
                        }
                    } catch(e) {
                        console.log('[DashieLite] Kiosk style injection error: ' + e.message);
                    }
                }, 500);

                // Helper to get hui-root shadow root (traverses the full chain)
                function getHuiRootShadow() {
                    try {
                        const ha = document.querySelector('home-assistant');
                        if (!ha || !ha.shadowRoot) return null;
                        const haMain = ha.shadowRoot.querySelector('home-assistant-main');
                        if (!haMain || !haMain.shadowRoot) return null;
                        const pp = haMain.shadowRoot.querySelector('partial-panel-resolver');
                        if (!pp) return null;
                        const lv = pp.querySelector('ha-panel-lovelace');
                        if (!lv || !lv.shadowRoot) return null;
                        const hr = lv.shadowRoot.querySelector('hui-root');
                        return hr && hr.shadowRoot ? hr.shadowRoot : null;
                    } catch(e) { return null; }
                }

                // Update header visibility based on subview state
                function updateHeaderForSubview() {
                    if (!HIDE_TABS) return;
                    const huiRootShadow = getHuiRootShadow();
                    if (!huiRootShadow) return;
                    if (isSubview(huiRootShadow)) {
                        removeStyle('tabs', huiRootShadow);
                    } else {
                        injectStyle('tabs', TABS_CSS, huiRootShadow);
                    }
                }

                // ── Floating "back to home dashboard" button ───────────────────
                // Shown when the user has navigated away from the configured home
                // dashboard (e.g. tap_action: url /energy or /config). Styled to
                // match HaWebLoginActivity's back button: 40px circle, dark bg,
                // white chevron, top-left, alpha 0.7 idle / 1.0 active.
                //
                // On HA-native lovelace subviews, HA renders its own back arrow
                // (ha-icon-button-arrow-prev). We suppress ours there to avoid
                // showing two back buttons.
                function ensureBackButton() {
                    if (document.getElementById('dashie-dashboard-back')) return;
                    if (!document.body) return;
                    var btn = document.createElement('div');
                    btn.id = 'dashie-dashboard-back';
                    btn.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"></polyline></svg>';
                    btn.style.cssText = 'position:fixed;top:8px;right:8px;width:40px;height:40px;' +
                        'border-radius:50%;background:rgba(0,0,0,0.35);' +
                        'display:none;align-items:center;justify-content:center;' +
                        'cursor:pointer;z-index:2147483647;opacity:0.4;' +
                        'transition:opacity 0.15s, background 0.15s;';
                    btn.addEventListener('mouseenter', function() {
                        btn.style.opacity = '0.95';
                        btn.style.background = 'rgba(0,0,0,0.65)';
                    });
                    btn.addEventListener('mouseleave', function() {
                        btn.style.opacity = '0.4';
                        btn.style.background = 'rgba(0,0,0,0.35)';
                    });
                    btn.addEventListener('click', function(e) {
                        e.preventDefault();
                        e.stopPropagation();
                        try {
                            // Prefer history.back() so the user returns to the tab they
                            // were on (e.g. /ha-dashie/cameras), not the dashboard root.
                            // Fall back to HOME_PATH if there's no prior entry.
                            if (history.length > 1) {
                                history.back();
                            } else {
                                history.pushState(null, '', HOME_PATH);
                                window.dispatchEvent(new Event('location-changed'));
                            }
                        } catch (err) {
                            console.warn('[DashieLite] back navigation failed:', err && err.message);
                        }
                    });
                    document.body.appendChild(btn);
                }

                function shouldShowDashieBack() {
                    // User opt-out (default): never show.
                    if (!SHOW_FLOATING_BACK_BUTTON) return false;
                    // TV: never show — d-pad can't focus this floating div.
                    if (IS_TV_DEVICE) return false;
                    var cur = location.pathname.replace(/\/+${'$'}/, '') || '/';
                    var home = HOME_PATH.replace(/\/+${'$'}/, '') || '/';
                    // Home dashboard (or any view within it — tabs, subviews, popups) → hide.
                    // HA handles intra-dashboard navigation natively (tabs at top, subview back arrow).
                    if (cur === home) return false;
                    // When the user marks their dashboard as HA's default, HOME_PATH
                    // is "/" (no explicit dashboard path in the URL), but HA
                    // actually serves the dashboard under /lovelace or similar.
                    // Without this guard, "/" + "/" = "//" never prefix-matches
                    // any real path, so the back button shows on every page.
                    if (home === '/') return false;
                    if (cur.indexOf(home + '/') === 0) return false;
                    // Outside the home dashboard (e.g. /energy, /config, a different lovelace).
                    return true;
                }

                function updateBackButton() {
                    ensureBackButton();
                    var btn = document.getElementById('dashie-dashboard-back');
                    if (!btn) return;
                    var show = shouldShowDashieBack();
                    btn.style.display = show ? 'flex' : 'none';
                    if (show) {
                        // Reset to idle style every time we reveal the button.
                        // On touch devices mouseenter fires on tap but mouseleave
                        // often doesn't fire before the element hides on nav, so
                        // the next show would inherit the hover style without this.
                        btn.style.opacity = '0.4';
                        btn.style.background = 'rgba(0,0,0,0.35)';
                    }
                }

                function setupBackButton() {
                    if (!document.body) {
                        setTimeout(setupBackButton, 100);
                        return;
                    }
                    updateBackButton();
                }
                setupBackButton();

                // Navigation listeners — delay to let HA update the DOM before checking subview state
                window.addEventListener('location-changed', function() {
                    setTimeout(function() { tryInject(); updateHeaderForSubview(); updateBackButton(); }, 150);
                });
                window.addEventListener('popstate', function() {
                    setTimeout(function() { tryInject(); updateHeaderForSubview(); updateBackButton(); }, 150);
                });
                window.addEventListener('hashchange', function() {
                    setTimeout(updateBackButton, 50);
                });

                // MutationObserver on hui-root.shadowRoot to detect subview toolbar changes
                // (The home-assistant.shadowRoot observer can't see across shadow DOM boundaries)
                if (HIDE_TABS) {
                    function startSubviewObserver() {
                        const huiRootShadow = getHuiRootShadow();
                        if (!huiRootShadow) {
                            setTimeout(startSubviewObserver, 1000);
                            return;
                        }
                        const subviewObserver = new MutationObserver(function() {
                            updateHeaderForSubview();
                            updateBackButton();
                        });
                        subviewObserver.observe(huiRootShadow, { childList: true, subtree: true });
                        console.log('[DashieLite] Started subview observer on hui-root shadowRoot');
                    }
                    setTimeout(startSubviewObserver, 3000);
                }
            })();
        """.trimIndent()
    }
}

package com.dashieapp.Dashie.webview.delegates

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.api.DashieApiPreferences
import com.dashieapp.Dashie.halite.HalitePreferences

/**
 * Delegate handling Home Assistant-related JavaScript bridge methods.
 * Includes: HA API proxy, HA login/authentication, HA tokens,
 * and HA UI display preferences (hide sidebar, hide tabs).
 */
class JsBridgeHaDelegate(
    private val context: Context,
    private val webView: WebView,
    private val halitePrefs: () -> HalitePreferences?
) {
    companion object {
        private const val TAG = "JsBridgeHa"

        // Cached ingress session token, set by cookie relay from HA iframe.
        // Used by DashieWebViewClient to manually add the cookie when proxying
        // ingress requests, because SameSite cookie enforcement prevents WebView
        // from sending it in cross-origin iframes. Static so DashieWebViewClient
        // can access without wiring.
        @Volatile @JvmStatic var cachedIngressSession: String? = null
            internal set
        @Volatile @JvmStatic var cachedIngressPath: String? = null
            internal set
    }

    // Lazy-initialized DashieApiPreferences for standard Dashie HA token storage
    private val dashieApiPrefs by lazy {
        DashieApiPreferences(context)
    }

    // Callbacks
    var onOpenHaLogin: ((String) -> Unit)? = null

    // Provider for the native dashboard-health coordinator (iframe-content
    // recovery). A provider (not a direct ref) so it survives WebView recreation.
    var dashboardHealthCoordinatorProvider:
        (() -> com.dashieapp.Dashie.halite.DashboardHealthCoordinator?)? = null

    // ============================================
    // Ingress Cookie Relay
    // ============================================

    /**
     * Set an ingress_session cookie via CookieManager at the app level.
     * Called from kiosk-shell.js which receives the cookie from the HA iframe
     * via postMessage. This bypasses cross-origin iframe cookie partitioning
     * in newer Chromium/WebView versions.
     *
     * @param origin The HA origin (e.g., "http://192.168.1.50:8123")
     * @param cookie The raw cookie string (e.g., "ingress_session=abc; path=/api/hassio_ingress/xyz/")
     */

    fun setIngressCookie(origin: String, cookie: String) {
        if (!cookie.startsWith("ingress_session=") || origin.isEmpty()) {
            Log.w(TAG, "🍪 Ignoring invalid ingress cookie: origin=$origin, cookie=${cookie.take(30)}")
            return
        }

        try {
            // Extract session token value
            val sessionValue = cookie.substringAfter("ingress_session=").substringBefore(";").trim()

            // Extract path from cookie string (e.g., "path=/api/hassio_ingress/xyz/")
            val pathMatch = Regex("""path=([^;]+)""", RegexOption.IGNORE_CASE).find(cookie)
            val path = pathMatch?.groupValues?.get(1)?.trim() ?: "/"

            // Cache for use by shouldInterceptRequest proxy
            cachedIngressSession = sessionValue
            cachedIngressPath = path
            Log.i(TAG, "🍪 Cached ingress session (${sessionValue.length} chars) for path=$path")

            // Also set in CookieManager (belt and suspenders)
            val cookieUrl = "$origin$path"
            val cookieManager = android.webkit.CookieManager.getInstance()
            // Strip SameSite and set as None-like (no SameSite attribute = browser default)
            val cleanCookie = cookie.replace(Regex(""";?\s*SameSite=\w+""", RegexOption.IGNORE_CASE), "")
            cookieManager.setCookie(cookieUrl, cleanCookie)
            cookieManager.flush()
            Log.i(TAG, "🍪 Ingress cookie set via CookieManager: url=$cookieUrl")
        } catch (e: Exception) {
            Log.e(TAG, "🍪 Failed to set ingress cookie: ${e.message}")
        }
    }

    // ============================================
    // HA iframe health (kiosk-shell.js → DashboardHealthCoordinator)
    // ============================================

    /**
     * JS reports the HA dashboard iframe is on an error page (404/5xx/blank/
     * unreachable). Forwarded to the native DashboardHealthCoordinator, which owns
     * recovery while the screen is off — JS timers freeze then, so the JS offline
     * overlay's poll can't recover overnight. Marshaled to the main thread because
     * @JavascriptInterface methods arrive on a background thread.
     */
    fun reportHaIframeError(url: String) {
        webView.post { dashboardHealthCoordinatorProvider?.invoke()?.onIframeError(url) }
    }

    /** JS reports the HA dashboard iframe loaded a healthy dashboard. */
    fun reportHaIframeHealthy() {
        webView.post { dashboardHealthCoordinatorProvider?.invoke()?.onIframeHealthy() }
    }

    // ============================================
    // Home Assistant API Proxy
    // Makes HTTP requests to local HA instance from native Android
    // (bypasses browser CORS restrictions)
    // ============================================

    /**
     * Check if HA proxy is available (always true on Android)
     */
    fun isHAProxyAvailable(): Boolean = true

    /**
     * Make a proxied request to Home Assistant API.
     * This runs on a background thread and calls back to JavaScript with the result.
     *
     * @param requestId Unique ID to match the callback
     * @param url Full HA URL (e.g., "http://192.168.1.100:8123/api/")
     * @param token Long-lived access token
     * @param method HTTP method (GET or POST)
     * @param body JSON body for POST requests (optional)
     */
    fun callHAProxy(requestId: String, url: String, token: String, method: String, body: String?) {
        Log.i(TAG, "callHAProxy: $method $url (requestId: $requestId)")

        // Run HTTP request on background thread with retry logic
        Thread {
            // Ensure we have a valid (non-expired) token before making the request.
            // The JS side passes whatever token it has, but it may be stale.
            // Use HaTokenExtractor to refresh if expired (same as voice pipeline).
            val validToken: String = run {
                val hp = halitePrefs()
                if (hp != null && hp.connection.isHaTokenExpired && hp.connection.haRefreshToken.isNotEmpty()) {
                    Log.i(TAG, "HA token expired, refreshing before proxy request...")
                    val result = com.dashieapp.Dashie.halite.HaTokenExtractor.refreshTokenSync(hp)
                    if (result.success && result.accessToken.isNotEmpty()) {
                        Log.i(TAG, "Token refreshed successfully for proxy request")
                        result.accessToken
                    } else {
                        Log.w(TAG, "Token refresh failed, using original token")
                        token
                    }
                } else if (hp != null && hp.connection.hasHaAccessToken) {
                    // Use the stored token from prefs (may be fresher than what JS passed)
                    hp.connection.haAccessToken
                } else {
                    token
                }
            }

            var lastException: Exception? = null
            var attempt = 0
            val maxAttempts = 3

            while (attempt < maxAttempts) {
                attempt++
                try {
                    Log.i(TAG, "HA proxy attempt $attempt/$maxAttempts")

                    val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = method
                    connection.setRequestProperty("Authorization", "Bearer $validToken")
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.connectTimeout = 20000  // Increased from 10s to 20s for slow networks
                    connection.readTimeout = 20000     // Increased from 10s to 20s for slow HA instances

                    // Send body for POST requests
                    if (method == "POST" && body != null) {
                        connection.doOutput = true
                        connection.outputStream.bufferedWriter().use { it.write(body) }
                    }

                    val responseCode = connection.responseCode
                    val responseBody = try {
                        if (responseCode in 200..299) {
                            connection.inputStream.bufferedReader().use { it.readText() }
                        } else {
                            connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading response: ${e.message}")
                        ""
                    }

                    Log.i(TAG, "HA response: $responseCode (attempt $attempt)")

                    // Call back to JavaScript with result
                    val result = org.json.JSONObject(mapOf(
                        "requestId" to requestId,
                        "success" to (responseCode in 200..299),
                        "status" to responseCode,
                        "data" to responseBody
                    )).toString()

                    webView.post {
                        webView.evaluateJavascript(
                            // BUNDLE-EXEMPT: _haProxyCallback — response callback — only evaluated in reply to a webapp haService call
                            "if (window._haProxyCallback) window._haProxyCallback($result);",
                            null
                        )
                    }

                    // Success - break retry loop
                    return@Thread

                } catch (e: Exception) {
                    lastException = e
                    Log.e(TAG, "HA proxy error (attempt $attempt/$maxAttempts): ${e.message}")

                    // If not last attempt, wait before retrying (exponential backoff)
                    if (attempt < maxAttempts) {
                        val delayMs = (1000 * attempt).toLong() // 1s, 2s delays
                        Log.i(TAG, "Retrying in ${delayMs}ms...")
                        Thread.sleep(delayMs)
                    }
                }
            }

            // All retries failed - send error to JavaScript
            val result = org.json.JSONObject(mapOf(
                "requestId" to requestId,
                "success" to false,
                "status" to 0,
                "error" to (lastException?.message ?: "Unknown error after $maxAttempts attempts")
            )).toString()

            webView.post {
                webView.evaluateJavascript(
                    "if (window._haProxyCallback) window._haProxyCallback($result);",
                    null
                )
            }
        }.start()
    }

    // ============================================
    // Home Assistant Login (for standard Dashie)
    // Opens native login activity to get HA token
    // ============================================

    /**
     * Open the Home Assistant login activity to authenticate.
     * After successful login, the token is stored and can be retrieved via getHaToken().
     * The web app will be notified via window.onHaLoginResult(success, token) callback.
     *
     * @param haUrl The Home Assistant base URL (e.g., "http://192.168.1.100:8123")
     */
    fun openHaLogin(haUrl: String) {
        Log.i(TAG, "openHaLogin called with URL: $haUrl")
        webView.post {
            onOpenHaLogin?.invoke(haUrl)
        }
    }

    /**
     * Get the stored Home Assistant access token.
     * Returns empty string if no token is stored.
     */
    fun getHaToken(): String {
        // Halite connection store FIRST: it holds the actively-refreshed rotating token
        // (~30-min lifetime; the JS layer + HaTokenExtractor keep it fresh).
        // dashie_api_prefs is only written at manual HA login — preferring it served a
        // weeks-dead token to JS consumers (video-feed HA API, HA settings page)
        // whenever both stores were populated (found on the Samsung, 2026-07-13:
        // api-prefs token from June 18 vs a valid rotating halite token).
        val haliteToken = halitePrefs()?.connection?.haAccessToken ?: ""
        if (haliteToken.isNotEmpty()) {
            Log.d(TAG, "Returning HA token from HalitePreferences (length: ${haliteToken.length})")
            return haliteToken
        }

        val token = dashieApiPrefs.haAccessToken
        if (token.isNotEmpty()) {
            Log.d(TAG, "Returning HA token from DashieApiPreferences (length: ${token.length})")
            return token
        }

        Log.d(TAG, "No HA token stored")
        return ""
    }

    /**
     * Get the stored Home Assistant base URL.
     * Returns empty string if not stored.
     */
    fun getHaBaseUrl(): String {
        val url = dashieApiPrefs.haBaseUrl
        if (url.isNotEmpty()) return url
        return halitePrefs()?.connection?.haBaseUrl ?: ""
    }

    /**
     * Check if we have a stored HA token.
     */
    fun hasHaToken(): Boolean {
        return dashieApiPrefs.hasHaToken || (halitePrefs()?.connection?.hasHaAccessToken ?: false)
    }

    // ============================================
    // Home Assistant UI Display Settings
    // These control CSS injection for kiosk mode
    // ============================================

    /**
     * Get HA hide sidebar preference.
     */
    fun getHaHideSidebar(): Boolean {
        // Halite builds use HalitePreferences (read by DashieWebViewClient CSS injector)
        return halitePrefs()?.connection?.hideSidebar ?: dashieApiPrefs.hideSidebar
    }

    /**
     * Set HA hide sidebar preference.
     */
    fun setHaHideSidebar(hide: Boolean) {
        Log.i(TAG, "Setting hide sidebar: $hide")
        // Write to HalitePreferences (read by DashieWebViewClient CSS injector)
        halitePrefs()?.let { it.connection.hideSidebar = hide } ?: run { dashieApiPrefs.hideSidebar = hide }
        fireHaDisplayChanged()
    }

    /**
     * Get HA hide tabs preference.
     */
    fun getHaHideTabs(): Boolean {
        // Halite builds use HalitePreferences (read by DashieWebViewClient CSS injector)
        return halitePrefs()?.connection?.hideTabs ?: dashieApiPrefs.hideTabs
    }

    /**
     * Set HA hide tabs preference.
     */
    fun setHaHideTabs(hide: Boolean) {
        Log.i(TAG, "Setting hide tabs: $hide")
        // Write to HalitePreferences (read by DashieWebViewClient CSS injector)
        halitePrefs()?.let { it.connection.hideTabs = hide } ?: run { dashieApiPrefs.hideTabs = hide }
        fireHaDisplayChanged()
    }

    /**
     * Get HA hide search preference.
     */
    fun getHaHideSearch(): Boolean {
        return halitePrefs()?.connection?.hideSearch ?: dashieApiPrefs.hideSearch
    }

    /**
     * Set HA hide search preference.
     */
    fun setHaHideSearch(hide: Boolean) {
        Log.i(TAG, "Setting hide search: $hide")
        halitePrefs()?.let { it.connection.hideSearch = hide } ?: run { dashieApiPrefs.hideSearch = hide }
        fireHaDisplayChanged()
    }

    /**
     * Get HA hide assistant preference.
     */
    fun getHaHideAssistant(): Boolean {
        return halitePrefs()?.connection?.hideAssistant ?: dashieApiPrefs.hideAssistant
    }

    /**
     * Set HA hide assistant preference.
     */
    fun setHaHideAssistant(hide: Boolean) {
        Log.i(TAG, "Setting hide assistant: $hide")
        halitePrefs()?.let { it.connection.hideAssistant = hide } ?: run { dashieApiPrefs.hideAssistant = hide }
        fireHaDisplayChanged()
    }

    /**
     * Broadcast ACTION_HA_DISPLAY_CHANGED so a currently-visible HA page reloads
     * to apply the new hide flags (baked into the page by the kiosk CSS
     * interceptor at load time). These JS-bridge setters are the web/desktop
     * settings entry point; like the Fully-Kiosk API path they previously wrote
     * the pref only. The native Settings schema already fires this via onChanged.
     * MainBroadcastManager reloads only when the WebView is on the HA host.
     */
    private fun fireHaDisplayChanged() {
        context.sendBroadcast(
            android.content.Intent("com.dashieapp.Dashie.ACTION_HA_DISPLAY_CHANGED").apply {
                setPackage(context.packageName)
            }
        )
    }

    /**
     * Set HA base URL in ConnectionPreferences (bidirectional sync from webapp).
     */
    fun setHaBaseUrl(url: String) {
        val old = halitePrefs()?.connection?.haBaseUrl ?: ""
        Log.i(TAG, "Setting HA base URL: $url (was: $old)")
        halitePrefs()?.connection?.haBaseUrl = url
        if (old.isNotEmpty() && url != old) {
            Log.i(TAG, "🔄 HA base URL changed — reloading")
            com.dashieapp.Dashie.halite.diagnostics.PersistentLog.info(
                "REFRESH", "WebView reload: HA base URL changed"
            )
            webView.post { webView.reload() }
        }
    }

    /**
     * Set HA dashboard path in ConnectionPreferences (bidirectional sync from webapp).
     */
    fun setHaDashboard(dashboard: String) {
        val old = halitePrefs()?.connection?.dashboardName ?: ""
        Log.i(TAG, "Setting HA dashboard: $dashboard (was: $old)")
        halitePrefs()?.connection?.dashboardName = dashboard
        if (dashboard != old) {
            Log.i(TAG, "🔄 HA dashboard changed — reloading")
            com.dashieapp.Dashie.halite.diagnostics.PersistentLog.info(
                "REFRESH", "WebView reload: HA dashboard changed"
            )
            webView.post { webView.reload() }
        }
    }

    /**
     * Set HA auto-build URL flag in ConnectionPreferences (bidirectional sync from webapp).
     */
    fun setHaAutoBuild(autoBuild: Boolean) {
        Log.i(TAG, "Setting HA auto-build: $autoBuild")
        halitePrefs()?.connection?.autoBuildUrl = autoBuild
    }

    /**
     * Get all HA display preferences as JSON.
     */
    fun getHaDisplayPrefs(): String {
        return org.json.JSONObject(mapOf(
            "hideSidebar" to getHaHideSidebar(),
            "hideTabs" to getHaHideTabs(),
            "hideSearch" to getHaHideSearch(),
            "hideAssistant" to getHaHideAssistant()
        )).toString()
    }

    // ============================================
    // Cloud-restorable HA settings blob (Phase 2 sync)
    //
    // getHomeAssistantSettings() / setHomeAssistantSettings(json) are the
    // round-trip pair used by the JS unified push helper to mirror Kotlin
    // SharedPrefs through user_devices.settings.home_assistant. Tokens,
    // passwords, session state, and per-device hardware identifiers are
    // intentionally excluded — see the bucket comments below.
    //
    // Excluded by design:
    //  - haAccessToken / haRefreshToken / haTokenExpiry — short-lived OAuth
    //  - haUsername / haPassword / apiPassword — credentials
    //  - rtspAuthPassword — RTSP credential
    //  - supabaseJwt / supabaseUserId / pendingHa* — session/transient
    //  - deviceUuid / deviceName — per-device hardware identity
    //  - addonUrl / addonMode / devServerUrl — deprecated dev-only fields
    // ============================================

    /**
     * One row per section: its wire name, how to build it, how to apply it.
     *
     * 🔴 ONE table because there were about to be THREE lists of the same section names — the
     * builder chain, the apply chain, and (2026-08-04) a third for validating unknown keys. Three
     * hand-mirrors of one fact, and the drift they permit is silent in the worst direction: a
     * section added to the GET side but forgotten on the APPLY side round-trips as
     * "saved successfully" and restores nothing. Adding a section is now one row, or it does not
     * exist on either side.
     */
    private fun haSections(p: com.dashieapp.Dashie.halite.HalitePreferences) = listOf(
        HaSection("core", { buildHaCoreJson(p) }, { o -> applyHaCore(p, o) }),
        HaSection("api", { buildHaApiJson(p) }, { o -> applyHaApi(p, o) }),
        HaSection("camera", { buildHaCameraJson(p) }, { o -> applyHaCamera(p, o) }),
        HaSection("videoFeed", { buildHaVideoFeedJson(p) }, { o -> applyHaVideoFeed(p, o) }),
        HaSection("power", { buildHaPowerJson(p) }, { o -> applyHaPower(p, o) }),
        HaSection("alert", { buildHaAlertJson(p) }, { o -> applyHaAlert(p, o) }),
    )

    private class HaSection(
        val name: String,
        val build: () -> org.json.JSONObject,
        val apply: (org.json.JSONObject) -> Unit,
    )

    fun getHomeAssistantSettings(): String {
        val prefs = halitePrefs() ?: return "{}"
        return org.json.JSONObject().apply {
            haSections(prefs).forEach { put(it.name, it.build()) }
        }.toString()
    }

    /**
     * Apply a settings blob shaped like [getHomeAssistantSettings]'s output.
     *
     * ⚠️ **This dispatches by SECTION, not by setting.** The payload must be
     * `{"core":{…},"api":{…},…}`; a FLAT payload of setting names matches no section and applies
     * nothing.
     *
     * 🔴 That used to be indistinguishable from success (Thread M, 2026-08-04, found it the hard
     * way: a flat payload "applied" and not one setting persisted — only a device round-trip
     * exposed it). Standing rule 2: *"understood nothing" and "applied everything" must not look
     * the same.* Unrecognised top-level keys now emit a loud `DROP:`, and a payload that matched
     * NOTHING says so explicitly, because that is the shape a caller gets wrong.
     */
    fun setHomeAssistantSettings(json: String) {
        Log.i(TAG, "🏠 setHomeAssistantSettings")
        val prefs = halitePrefs() ?: return
        try {
            val obj = org.json.JSONObject(json)
            val sections = haSections(prefs)
            var matched = 0
            sections.forEach { s ->
                if (obj.has(s.name)) { s.apply(obj.getJSONObject(s.name)); matched++ }
            }
            warnUnknownHaSections(obj, sections.map { it.name }, matched)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse HA settings JSON", e)
        }
    }

    /**
     * Name every top-level key that matched no section — and shout when NONE matched.
     *
     * Reported after the applies rather than before, so a partially-correct payload still persists
     * what it can: the point is visibility, not rejection. An unknown key is almost always one of
     * two mistakes and the message names both — a FLAT payload (setting names at the top level),
     * or a section that exists on the get side and was never added to the apply side.
     */
    private fun warnUnknownHaSections(
        obj: org.json.JSONObject,
        known: List<String>,
        matched: Int,
    ) {
        val unknown = obj.keys().asSequence().filterNot { it in known }.toList()
        if (unknown.isEmpty()) return
        if (matched == 0) {
            Log.w(TAG, "DROP: [unexpected] setHomeAssistantSettings applied NOTHING — none of " +
                "${unknown.joinToString()} is a section. This call dispatches by SECTION " +
                "(${known.joinToString("|")}); a flat payload of setting names silently matches " +
                "nothing. Wrap the settings in their section object.")
        } else {
            Log.w(TAG, "DROP: [unexpected] setHomeAssistantSettings ignored unknown top-level " +
                "key(s): ${unknown.joinToString()}. Known sections: ${known.joinToString("|")}. " +
                "$matched section(s) DID apply, so this is a partial apply, not a no-op.")
        }
    }

    private fun buildHaCoreJson(p: com.dashieapp.Dashie.halite.HalitePreferences): org.json.JSONObject {
        val c = p.connection
        return org.json.JSONObject().apply {
            put("haEnabled", c.haEnabled)
            put("isSetupComplete", c.isSetupComplete)
            put("haUrl", c.haUrl)
            put("haBaseUrl", c.haBaseUrl)
            put("dashboardName", c.dashboardName)
            put("autoBuildUrl", c.autoBuildUrl)
            put("useCustomUrl", c.useCustomUrl)
            put("customUrl", c.customUrl)
            put("hideSidebar", c.hideSidebar)
            put("hideTabs", c.hideTabs)
            put("hideSearch", c.hideSearch)
            put("hideAssistant", c.hideAssistant)
            put("advancedTabletControlsEnabled", c.advancedTabletControlsEnabled)
            put("useNativeLogin", c.useNativeLogin)
            put("keepLoggedIn", c.keepLoggedIn)
            // deviceFriendlyName removed from the HA blob (#8/M5, 20260722_DEVICE_NAME_
            // CONVERGENCE_PLAN.md): the device name's SSOT is user_devices.device_name,
            // mirrored to deviceFriendlyName by setDeviceName — it no longer travels via
            // the HA whole-blob (which the #6/M8 apply guard drops on configured devices),
            // so naming is decoupled from the deferred HA-category redesign.
        }
    }

    private fun applyHaCore(p: com.dashieapp.Dashie.halite.HalitePreferences, o: org.json.JSONObject) {
        val c = p.connection
        if (o.has("haEnabled")) c.haEnabled = o.getBoolean("haEnabled")
        if (o.has("isSetupComplete")) c.isSetupComplete = o.getBoolean("isSetupComplete")
        if (o.has("haUrl")) c.haUrl = o.getString("haUrl")
        if (o.has("haBaseUrl")) c.haBaseUrl = o.getString("haBaseUrl")
        if (o.has("dashboardName")) c.dashboardName = o.getString("dashboardName")
        if (o.has("autoBuildUrl")) c.autoBuildUrl = o.getBoolean("autoBuildUrl")
        if (o.has("useCustomUrl")) c.useCustomUrl = o.getBoolean("useCustomUrl")
        if (o.has("customUrl")) c.customUrl = o.getString("customUrl")
        if (o.has("hideSidebar")) c.hideSidebar = o.getBoolean("hideSidebar")
        if (o.has("hideTabs")) c.hideTabs = o.getBoolean("hideTabs")
        if (o.has("hideSearch")) c.hideSearch = o.getBoolean("hideSearch")
        if (o.has("hideAssistant")) c.hideAssistant = o.getBoolean("hideAssistant")
        if (o.has("advancedTabletControlsEnabled")) c.advancedTabletControlsEnabled = o.getBoolean("advancedTabletControlsEnabled")
        if (o.has("useNativeLogin")) c.useNativeLogin = o.getBoolean("useNativeLogin")
        if (o.has("keepLoggedIn")) c.keepLoggedIn = o.getBoolean("keepLoggedIn")
        // deviceFriendlyName no longer applied from the HA blob (#8/M5): only
        // setDeviceName (from the device_name SSOT) sets it, so a stale HA blob can't
        // clobber the friendly name. Old cloud rows may still carry the field — ignored.
    }

    private fun buildHaApiJson(p: com.dashieapp.Dashie.halite.HalitePreferences): org.json.JSONObject {
        val c = p.connection
        return org.json.JSONObject().apply {
            put("apiEnabled", c.apiEnabled)
            put("apiPort", c.apiPort)
            put("discoveryEnabled", c.discoveryEnabled)
            // apiPassword intentionally excluded
        }
    }

    private fun applyHaApi(p: com.dashieapp.Dashie.halite.HalitePreferences, o: org.json.JSONObject) {
        val c = p.connection
        if (o.has("apiEnabled")) c.apiEnabled = o.getBoolean("apiEnabled")
        if (o.has("apiPort")) c.apiPort = o.getInt("apiPort")
        if (o.has("discoveryEnabled")) c.discoveryEnabled = o.getBoolean("discoveryEnabled")
    }

    private fun buildHaCameraJson(p: com.dashieapp.Dashie.halite.HalitePreferences): org.json.JSONObject {
        val cam = p.camera
        return org.json.JSONObject().apply {
            put("cameraMotionEnabled", cam.cameraMotionEnabled)
            put("rtspEnabled", cam.rtspEnabled)
            put("rtspPort", cam.rtspPort)
            put("rtspResolution", cam.rtspResolution)
            put("rtspCustomWidth", cam.rtspCustomWidth)
            put("rtspCustomHeight", cam.rtspCustomHeight)
            put("rtspFps", cam.rtspFps)
            put("rtspBitrate", cam.rtspBitrate)
            put("rtspAuthEnabled", cam.rtspAuthEnabled)
            put("rtspAuthUser", cam.rtspAuthUser)
            // rtspAuthPassword intentionally excluded
            put("rtspMotionDetection", cam.rtspMotionDetection)
            put("stopStreamWhenNoClients", cam.stopStreamWhenNoClients)
            put("rtspForceSoftwareEncoding", cam.rtspForceSoftwareEncoding)
            put("rtspDisableMirrorCorrection", cam.rtspDisableMirrorCorrection)
            put("rtspPortraitStream", cam.rtspPortraitStream)
            put("haSensorEnabled", cam.haSensorEnabled)
            put("haSensorMotionEnabled", cam.haSensorMotionEnabled)
            put("haSensorFaceEnabled", cam.haSensorFaceEnabled)
        }
    }

    private fun applyHaCamera(p: com.dashieapp.Dashie.halite.HalitePreferences, o: org.json.JSONObject) {
        val cam = p.camera
        if (o.has("cameraMotionEnabled")) cam.cameraMotionEnabled = o.getBoolean("cameraMotionEnabled")
        if (o.has("rtspEnabled")) cam.rtspEnabled = o.getBoolean("rtspEnabled")
        if (o.has("rtspPort")) cam.rtspPort = o.getInt("rtspPort")
        if (o.has("rtspResolution")) cam.rtspResolution = o.getString("rtspResolution")
        if (o.has("rtspCustomWidth")) cam.rtspCustomWidth = o.getInt("rtspCustomWidth")
        if (o.has("rtspCustomHeight")) cam.rtspCustomHeight = o.getInt("rtspCustomHeight")
        if (o.has("rtspFps")) cam.rtspFps = o.getInt("rtspFps")
        if (o.has("rtspBitrate")) cam.rtspBitrate = o.getInt("rtspBitrate")
        if (o.has("rtspAuthEnabled")) cam.rtspAuthEnabled = o.getBoolean("rtspAuthEnabled")
        if (o.has("rtspAuthUser")) cam.rtspAuthUser = o.getString("rtspAuthUser")
        if (o.has("rtspMotionDetection")) cam.rtspMotionDetection = o.getBoolean("rtspMotionDetection")
        if (o.has("stopStreamWhenNoClients")) cam.stopStreamWhenNoClients = o.getBoolean("stopStreamWhenNoClients")
        if (o.has("rtspForceSoftwareEncoding")) cam.rtspForceSoftwareEncoding = o.getBoolean("rtspForceSoftwareEncoding")
        if (o.has("rtspDisableMirrorCorrection")) cam.rtspDisableMirrorCorrection = o.getBoolean("rtspDisableMirrorCorrection")
        if (o.has("rtspPortraitStream")) cam.rtspPortraitStream = o.getBoolean("rtspPortraitStream")
        if (o.has("haSensorEnabled")) cam.haSensorEnabled = o.getBoolean("haSensorEnabled")
        if (o.has("haSensorMotionEnabled")) cam.haSensorMotionEnabled = o.getBoolean("haSensorMotionEnabled")
        if (o.has("haSensorFaceEnabled")) cam.haSensorFaceEnabled = o.getBoolean("haSensorFaceEnabled")
    }

    private fun buildHaVideoFeedJson(p: com.dashieapp.Dashie.halite.HalitePreferences): org.json.JSONObject {
        val vf = p.videoFeed
        val rules = org.json.JSONArray()
        for (rule in vf.getRules()) rules.put(rule)
        return org.json.JSONObject().apply {
            put("enabled", vf.enabled)
            put("feedLocation", vf.getFeedLocation())
            put("feedLayout", vf.getFeedLayout())
            put("feedSize", vf.getFeedSize())
            put("popupsPaused", vf.getPopupsPaused())
            put("alertsMuted", vf.getAlertsMuted())
            put("cooldownSeconds", vf.getCooldownSeconds())
            put("streamResolution", vf.getStreamResolution())
            put("streamFps", vf.getStreamFps())
            put("autoDismissSeconds", vf.getAutoDismissSeconds())
            put("continueWhileActive", vf.getContinueWhileActive())
            put("alertsEnabled", vf.getAlertsEnabled())
            put("rules", rules)
        }
    }

    private fun applyHaVideoFeed(p: com.dashieapp.Dashie.halite.HalitePreferences, o: org.json.JSONObject) {
        val vf = p.videoFeed
        if (o.has("enabled")) vf.enabled = o.getBoolean("enabled")
        if (o.has("feedLocation")) vf.setFeedLocation(o.getString("feedLocation"))
        if (o.has("feedLayout")) vf.setFeedLayout(o.getString("feedLayout"))
        if (o.has("feedSize")) vf.setFeedSize(o.getString("feedSize"))
        if (o.has("popupsPaused")) vf.setPopupsPaused(o.getBoolean("popupsPaused"))
        if (o.has("alertsMuted")) vf.setAlertsMuted(o.getBoolean("alertsMuted"))
        if (o.has("cooldownSeconds")) vf.setCooldownSeconds(o.getInt("cooldownSeconds"))
        if (o.has("streamResolution")) vf.setStreamResolution(o.getInt("streamResolution"))
        if (o.has("streamFps")) vf.setStreamFps(o.getInt("streamFps"))
        if (o.has("autoDismissSeconds")) vf.setAutoDismissSeconds(o.getInt("autoDismissSeconds"))
        if (o.has("continueWhileActive")) vf.setContinueWhileActive(o.getBoolean("continueWhileActive"))
        if (o.has("alertsEnabled")) vf.setAlertsEnabled(o.getBoolean("alertsEnabled"))
        // Rules live inside configJson — mutate that blob directly. Full replace
        // if provided; caller is expected to send the canonical list.
        if (o.has("rules")) {
            val cfg = try { org.json.JSONObject(vf.configJson) } catch (_: Exception) { org.json.JSONObject() }
            cfg.put("rules", o.getJSONArray("rules"))
            vf.configJson = cfg.toString()
        }
    }

    private fun buildHaPowerJson(p: com.dashieapp.Dashie.halite.HalitePreferences): org.json.JSONObject {
        // PowerPreferences is not exposed via HalitePreferences — instantiate
        // directly with context. Single-process, single SharedPreferences file
        // means values are coherent with anywhere else that does the same.
        val pw = com.dashieapp.Dashie.halite.preferences.PowerPreferences(context)
        return org.json.JSONObject().apply {
            put("enabled", pw.enabled)
            put("preferNight", pw.preferNight)
            put("entityId", pw.entityId)
            put("nightStart", pw.nightStart)
            put("nightEnd", pw.nightEnd)
            put("minThreshold", pw.minThreshold)
            put("maxThreshold", pw.maxThreshold)
            put("emergencyThreshold", pw.emergencyThreshold)
        }
    }

    private fun applyHaPower(p: com.dashieapp.Dashie.halite.HalitePreferences, o: org.json.JSONObject) {
        val pw = com.dashieapp.Dashie.halite.preferences.PowerPreferences(context)
        if (o.has("enabled")) pw.enabled = o.getBoolean("enabled")
        if (o.has("preferNight")) pw.preferNight = o.getBoolean("preferNight")
        if (o.has("entityId")) pw.entityId = o.getString("entityId")
        if (o.has("nightStart")) pw.nightStart = o.getString("nightStart")
        if (o.has("nightEnd")) pw.nightEnd = o.getString("nightEnd")
        if (o.has("minThreshold")) pw.minThreshold = o.getInt("minThreshold")
        if (o.has("maxThreshold")) pw.maxThreshold = o.getInt("maxThreshold")
        if (o.has("emergencyThreshold")) pw.emergencyThreshold = o.getInt("emergencyThreshold")
    }

    private fun buildHaAlertJson(p: com.dashieapp.Dashie.halite.HalitePreferences): org.json.JSONObject {
        val a = p.alert
        return org.json.JSONObject().apply {
            put("alertVolume", a.alertVolume.toDouble())
        }
    }

    private fun applyHaAlert(p: com.dashieapp.Dashie.halite.HalitePreferences, o: org.json.JSONObject) {
        val a = p.alert
        if (o.has("alertVolume")) a.alertVolume = o.getDouble("alertVolume").toFloat()
    }

    /**
     * Get all HA connection settings as JSON for syncing to webapp settingsStore.
     * Returns URL, display prefs, and token info — everything the webapp needs.
     */
    fun getHaConnectionSettings(): String {
        val prefs = halitePrefs()
        val conn = prefs?.connection

        val url = conn?.buildFullUrl() ?: ""
        // Treat the placeholder default as "no URL configured". Substring match
        // (rather than exact match against "http://192.168.1.x:8123") so we
        // also catch derived URLs like "http://192.168.1.x:8123/lovelace" that
        // buildFullUrl() can return when dashboardName has been populated but
        // haBaseUrl wasn't. Without this, ha.enabled flowed through as true to
        // JS even when the user never finished HA setup — post-logout routing
        // would then try to land them in an HA-login screen pointed at .1.x.
        val hasUrl = url.isNotEmpty() && !url.contains("192.168.1.x")
        // The per-device haEnabled toggle (Settings → Advanced → Home
        // Assistant) gates whether HA features should be exposed in the
        // UI even when a URL is configured. JS consumers (photo source
        // pickers, sidebar layout filtering, etc.) treat ha.enabled as
        // "HA is usable on this device", so it should reflect BOTH that
        // a URL is configured AND that the toggle is on. Previously the
        // toggle was ignored — disabling HA in Settings left ha.enabled
        // returning true and downstream UI kept showing HA-only options.
        val haToggleOn = conn?.haEnabled ?: true

        return org.json.JSONObject().apply {
            put("enabled", hasUrl && haToggleOn)
            put("url", if (hasUrl) url else "")
            put("auto_build", conn?.autoBuildUrl ?: true)
            put("base_url", conn?.haBaseUrl ?: getHaBaseUrl())
            put("dashboard", conn?.dashboardName ?: "")
            put("hide_sidebar", getHaHideSidebar())
            put("hide_tabs", getHaHideTabs())
            put("hide_search", getHaHideSearch())
            put("hide_assistant", getHaHideAssistant())
            put("access_token", getHaToken())
            put("has_token", hasHaToken())
            // Device API settings (for cross-build sync)
            put("api_enabled", conn?.apiEnabled ?: false)
            put("api_port", conn?.apiPort ?: 2323)
            put("api_password", conn?.apiPassword ?: "")
        }.toString()
    }
}

package com.dashieapp.Dashie.webview.delegates

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.api.DashieApiPreferences
import com.dashieapp.Dashie.halite.HaDiscovery
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.rtsp.RtspPlayerManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Delegate handling Halite/Kiosk-specific JavaScript bridge methods.
 * Includes: URL management, HA login, HA API proxy, HA display settings,
 * native RTSP playback, addon server settings, API settings, lock/unlock.
 */
class JsBridgeHaliteDelegate(
    private val context: Context,
    private val webView: WebView,
    private val halitePrefs: () -> HalitePreferences?,
    private val rtspPlayerManager: () -> RtspPlayerManager?
) {
    companion object {
        private const val TAG = "JsBridgeHalite"
    }

    // Lazy-initialized DashieApiPreferences for standard Dashie HA token storage
    private val dashieApiPrefs by lazy {
        DashieApiPreferences(context)
    }

    // Callbacks
    var onOpenHaLogin: ((String) -> Unit)? = null
    var onShowLockDialog: (() -> Unit)? = null
    var onShowPinRecoveryDialog: (() -> Unit)? = null
    var onCloseSidebar: (() -> Unit)? = null
    var onInjectTouch: ((Float, Float) -> Unit)? = null
    var onApiEnabledChanged: ((Boolean) -> Unit)? = null
    var onShowPinDialog: (() -> Unit)? = null
    var onOpenSystemDetails: (() -> Unit)? = null
    var onSendDiagnosticsHeadless: (() -> Unit)? = null
    var onSendPendingCrashReportHeadless: (() -> Unit)? = null
    var onOnboardingComplete: (() -> Unit)? = null
    var onRequestPermissionByType: ((String) -> Unit)? = null
    var onRequestAllOnboardingPermissions: (() -> Unit)? = null

    // ============================================
    // Halite URL Management
    // ============================================

    /** Save HA URL to prefs without navigating the WebView (for onboarding flow).
     *  Also parses URL into components (haBaseUrl + dashboardName) so that
     *  token injection prefix matching and buildFullUrl() work correctly. */
    fun saveHaUrl(url: String) {
        if (!BuildConfig.ALLOW_URL_CONFIG || halitePrefs() == null) {
            Log.w(TAG, "URL config not allowed")
            return
        }
        Log.i(TAG, "🔧 Saving HA URL (no navigation): $url")
        val prefs = halitePrefs()!!
        prefs.connection.haUrl = url
        // Parse into components so haBaseUrl is set for token injection prefix matching
        prefs.connection.parseUrl(url)
        prefs.connection.autoBuildUrl = true
        Log.i(TAG, "🔧 Parsed URL → base=${prefs.connection.haBaseUrl}, dashboard=${prefs.connection.dashboardName}")
    }

    // ============================================
    // Lock/Unlock
    // ============================================

    fun isAppLocked(): Boolean {
        return halitePrefs()?.lock?.isLocked ?: false
    }

    fun showLockDialog() {
        Log.i(TAG, "🔒 showLockDialog()")
        webView.post { onShowLockDialog?.invoke() }
    }

    fun showPinRecoveryDialog() {
        Log.i(TAG, "🔒 showPinRecoveryDialog()")
        webView.post { onShowPinRecoveryDialog?.invoke() }
    }

    fun closeSidebar() {
        Log.i(TAG, "📱 closeSidebar()")
        webView.post { onCloseSidebar?.invoke() }
    }

    fun injectTouch(x: Float, y: Float) {
        Log.d(TAG, "👆 injectTouch($x, $y)")
        webView.post { onInjectTouch?.invoke(x, y) }
    }

    // ============================================
    // Account Settings
    // ============================================

    fun showPinDialog() {
        webView.post { onShowPinDialog?.invoke() }
    }

    // ============================================
    // System Settings
    // ============================================

    fun openSystemDetails() {
        webView.post { onOpenSystemDetails?.invoke() }
    }

    /** Remote-triggered headless diagnostic upload (no UI dialog).
     *  Console → realtime broadcast → webapp listener → this method. */
    fun sendDiagnosticsHeadless() {
        webView.post { onSendDiagnosticsHeadless?.invoke() }
    }

    /** Remote-triggered headless pending crash-report upload.
     *  No-op when there's no pending crash report on the device. */
    fun sendPendingCrashReportHeadless() {
        webView.post { onSendPendingCrashReportHeadless?.invoke() }
    }

    // ============================================
    // API Settings (Fully Kiosk compatible REST API)
    // ============================================

    fun getApiSettings(): String {
        val prefs = halitePrefs()
        return org.json.JSONObject().apply {
            put("enabled", prefs?.connection?.apiEnabled ?: false)
            put("port", prefs?.connection?.apiPort ?: 2323)
            put("password", prefs?.connection?.apiPassword ?: "")
        }.toString()
    }

    fun setApiEnabled(enabled: Boolean) {
        Log.i(TAG, "🔧 setApiEnabled($enabled)")
        halitePrefs()?.connection?.apiEnabled = enabled
        webView.post { onApiEnabledChanged?.invoke(enabled) }
    }

    fun setApiPort(port: Int) {
        if (port in 1024..65535) {
            halitePrefs()?.connection?.apiPort = port
        }
    }

    fun setApiPassword(password: String) {
        halitePrefs()?.connection?.apiPassword = password
    }

    // ============================================
    // Home Assistant API Proxy
    // ============================================

    fun isHAProxyAvailable(): Boolean = true

    fun callHAProxy(requestId: String, url: String, token: String, method: String, body: String?) {
        Log.i(TAG, "🏠 callHAProxy: $method $url")

        Thread {
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = method
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

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
                } catch (e: Exception) { "" }

                val result = org.json.JSONObject(mapOf(
                    "requestId" to requestId,
                    "success" to (responseCode in 200..299),
                    "status" to responseCode,
                    "data" to responseBody
                )).toString()

                webView.post {
                    webView.evaluateJavascript(
                        "if (window._haProxyCallback) window._haProxyCallback($result);",
                        null
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "🏠 HA proxy error: ${e.message}")
                val result = org.json.JSONObject(mapOf(
                    "requestId" to requestId,
                    "success" to false,
                    "status" to 0,
                    "error" to (e.message ?: "Unknown error")
                )).toString()
                webView.post {
                    webView.evaluateJavascript(
                        "if (window._haProxyCallback) window._haProxyCallback($result);",
                        null
                    )
                }
            }
        }.start()
    }

    // ============================================
    // Home Assistant Login
    // ============================================

    fun openHaLogin(haUrl: String) {
        Log.i(TAG, "🏠 openHaLogin: $haUrl")
        webView.post { onOpenHaLogin?.invoke(haUrl) }
    }

    fun getHaToken(): String {
        // Halite connection store FIRST: it holds the actively-refreshed rotating token
        // (~30-min lifetime). dashie_api_prefs is only written at manual HA login, so
        // preferring it served a weeks-dead token to JS consumers (video-feed HA API,
        // HA settings page) whenever both stores were populated (2026-07-13).
        val haliteToken = halitePrefs()?.connection?.haAccessToken ?: ""
        if (haliteToken.isNotEmpty()) return haliteToken
        return dashieApiPrefs.haAccessToken
    }

    fun getHaBaseUrl(): String {
        val url = dashieApiPrefs.haBaseUrl
        if (url.isNotEmpty()) return url
        return halitePrefs()?.connection?.haBaseUrl ?: ""
    }

    fun hasHaToken(): Boolean {
        return dashieApiPrefs.hasHaToken || (halitePrefs()?.connection?.hasHaAccessToken ?: false)
    }

    // ============================================
    // Home Assistant UI Display Settings
    // ============================================

    fun setHaHideSidebar(hide: Boolean) {
        Log.i(TAG, "🏠 Setting hide sidebar: $hide")
        halitePrefs()?.let { it.connection.hideSidebar = hide } ?: run { dashieApiPrefs.hideSidebar = hide }
    }

    fun setHaHideTabs(hide: Boolean) {
        Log.i(TAG, "🏠 Setting hide tabs: $hide")
        halitePrefs()?.let { it.connection.hideTabs = hide } ?: run { dashieApiPrefs.hideTabs = hide }
    }

    fun setHaHideSearch(hide: Boolean) {
        Log.i(TAG, "🏠 Setting hide search: $hide")
        halitePrefs()?.let { it.connection.hideSearch = hide } ?: run { dashieApiPrefs.hideSearch = hide }
    }

    fun setHaHideAssistant(hide: Boolean) {
        Log.i(TAG, "🏠 Setting hide assistant: $hide")
        halitePrefs()?.let { it.connection.hideAssistant = hide } ?: run { dashieApiPrefs.hideAssistant = hide }
    }

    fun getHaDisplayPrefs(): String {
        return org.json.JSONObject(mapOf(
            "hideSidebar" to dashieApiPrefs.hideSidebar,
            "hideTabs" to dashieApiPrefs.hideTabs,
            "hideSearch" to dashieApiPrefs.hideSearch,
            "hideAssistant" to dashieApiPrefs.hideAssistant
        )).toString()
    }

    // ============================================
    // Native RTSP Camera Playback
    // ============================================

    fun isNativeRtspSupported(): Boolean {
        return rtspPlayerManager() != null
    }

    fun startRtspStream(id: String, rtspUrl: String, x: Int, y: Int, width: Int, height: Int) {
        Log.i(TAG, "📹 startRtspStream: id=$id")
        val manager = rtspPlayerManager() ?: return

        val density = context.resources.displayMetrics.density
        manager.startStream(id, rtspUrl,
            (x * density).toInt(),
            (y * density).toInt(),
            (width * density).toInt(),
            (height * density).toInt()
        )
    }

    fun stopRtspStream(id: String) {
        Log.i(TAG, "📹 stopRtspStream: id=$id")
        rtspPlayerManager()?.stopStream(id)
    }

    fun updateRtspStreamPosition(id: String, x: Int, y: Int, width: Int, height: Int) {
        val manager = rtspPlayerManager() ?: return
        val density = context.resources.displayMetrics.density
        manager.updateStreamPosition(id,
            (x * density).toInt(),
            (y * density).toInt(),
            (width * density).toInt(),
            (height * density).toInt()
        )
    }

    // ============================================
    // Overlay Keyboard Focus
    // ============================================

    @Volatile
    var overlayHasKeyboardFocus = false
        private set

    fun setOverlayKeyboardFocus(captured: Boolean) {
        overlayHasKeyboardFocus = captured
        Log.d(TAG, "⌨️ Overlay keyboard focus: ${if (captured) "CAPTURED" else "RELEASED"}")
    }

    // ============================================
    // Tab Navigation (Dashie Kiosk)
    // ============================================

    /**
     * Get dashboard views/tabs by querying the Home Assistant frontend JavaScript.
     *
     * Home Assistant doesn't expose dashboard config via REST API - only WebSocket.
     * Instead, we query the frontend's internal state via JavaScript since the dashboard
     * is already loaded in the WebView.
     *
     * @return JSON array string of views: [{"path":"kitchen","title":"Kitchen","icon":"mdi:pot"},...]
     */
    fun getDashboardViews(): String? {
        return try {
            Log.d(TAG, "🗂️ getDashboardViews: Querying frontend JavaScript")

            // Use CountDownLatch to wait for JavaScript result
            val latch = java.util.concurrent.CountDownLatch(1)
            var viewsJson: String? = null

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                webView.evaluateJavascript("""
                    (function() {
                        try {
                            console.log('[TabNav] === Looking for dashboard views/tabs ===');

                            // Get hass object
                            var hass = window.hass || document.querySelector('home-assistant')?.hass;
                            if (!hass) {
                                console.error('[TabNav] No hass object found');
                                return null;
                            }

                            // Determine current panel from URL
                            var currentPath = window.location.pathname;
                            console.log('[TabNav] Current path:', currentPath);
                            console.log('[TabNav] hass.panelUrl:', hass.panelUrl);

                            // Extract panel name from path (e.g., "/ha-dashie/0" -> "ha-dashie")
                            var panelMatch = currentPath.match(/^\/([^\/]+)/);
                            var currentPanel = panelMatch ? panelMatch[1] : 'lovelace';
                            console.log('[TabNav] Current panel:', currentPanel);

                            // Check if this panel exists
                            if (!hass.panels[currentPanel]) {
                                console.error('[TabNav] Panel not found:', currentPanel);
                                console.log('[TabNav] Available panels:', Object.keys(hass.panels));
                                return null;
                            }

                            console.log('[TabNav] Traversing shadow DOM chain...');

                            // Traverse shadow DOM chain to find hui-root
                            // Based on: https://github.com/home-assistant/frontend/blob/dev/src/panels/lovelace/hui-root.ts
                            var homeAssistant = document.querySelector('home-assistant');
                            if (!homeAssistant) {
                                console.error('[TabNav] home-assistant element not found');
                                return null;
                            }
                            console.log('[TabNav] ✓ Found home-assistant');

                            if (!homeAssistant.shadowRoot) {
                                console.error('[TabNav] home-assistant has no shadowRoot');
                                return null;
                            }

                            var homeAssistantMain = homeAssistant.shadowRoot.querySelector('home-assistant-main');
                            if (!homeAssistantMain) {
                                console.error('[TabNav] home-assistant-main not found');
                                return null;
                            }
                            console.log('[TabNav] ✓ Found home-assistant-main');

                            if (!homeAssistantMain.shadowRoot) {
                                console.error('[TabNav] home-assistant-main has no shadowRoot');
                                return null;
                            }

                            var partialPanelResolver = homeAssistantMain.shadowRoot.querySelector('partial-panel-resolver');
                            if (!partialPanelResolver) {
                                console.error('[TabNav] partial-panel-resolver not found');
                                return null;
                            }
                            console.log('[TabNav] ✓ Found partial-panel-resolver');

                            // Try shadowRoot first, if it doesn't exist, query directly
                            var haPanelLovelace = null;
                            if (partialPanelResolver.shadowRoot) {
                                console.log('[TabNav] partial-panel-resolver has shadowRoot');
                                haPanelLovelace = partialPanelResolver.shadowRoot.querySelector('ha-panel-lovelace');
                            } else {
                                console.log('[TabNav] partial-panel-resolver has no shadowRoot, querying directly');
                                haPanelLovelace = partialPanelResolver.querySelector('ha-panel-lovelace');
                            }
                            if (!haPanelLovelace) {
                                console.error('[TabNav] ha-panel-lovelace not found in partial-panel-resolver shadow');
                                return null;
                            }
                            console.log('[TabNav] ✓ Found ha-panel-lovelace');
                            console.log('[TabNav] ha-panel-lovelace keys:', Object.keys(haPanelLovelace).slice(0, 30));

                            // Check for lovelace property on ha-panel-lovelace
                            if (haPanelLovelace.lovelace) {
                                console.log('[TabNav] ha-panel-lovelace has lovelace property');
                                console.log('[TabNav] lovelace keys:', Object.keys(haPanelLovelace.lovelace));

                                if (haPanelLovelace.lovelace.config && haPanelLovelace.lovelace.config.views) {
                                    console.log('[TabNav] ✅ Found views in ha-panel-lovelace.lovelace!');
                                    return JSON.stringify(haPanelLovelace.lovelace.config.views);
                                }
                            }

                            // Also check shadowRoot for hui-root
                            if (haPanelLovelace.shadowRoot) {
                                var huiRoot = haPanelLovelace.shadowRoot.querySelector('hui-root');
                                if (huiRoot) {
                                    console.log('[TabNav] ✓ Found hui-root');
                                    console.log('[TabNav] hui-root keys:', Object.keys(huiRoot).slice(0, 30));

                                    if (huiRoot.lovelace && huiRoot.lovelace.config && huiRoot.lovelace.config.views) {
                                        console.log('[TabNav] ✅ Found views in hui-root!');
                                        return JSON.stringify(huiRoot.lovelace.config.views);
                                    }
                                }
                            }

                            console.error('[TabNav] Could not find views in shadow DOM chain');
                            return null;
                        } catch (e) {
                            console.error('[TabNav] Error:', e);
                            return null;
                        }
                    })();
                """.trimIndent()) { result ->
                    if (result != null && result != "null") {
                        viewsJson = result.trim('"').replace("\\\"", "\"").replace("\\\\", "\\")
                        Log.d(TAG, "🗂️ Got views from JavaScript: ${viewsJson?.take(100)}...")

                        // Parse and log all tab titles
                        try {
                            val jsonArray = org.json.JSONArray(viewsJson)
                            val titles = mutableListOf<String>()
                            for (i in 0 until jsonArray.length()) {
                                val view = jsonArray.getJSONObject(i)
                                val title = view.optString("title", "View $i")
                                titles.add(title)
                            }
                            Log.i(TAG, "🗂️ Found ${titles.size} tabs: ${titles.joinToString(", ")}")
                        } catch (e: Exception) {
                            Log.e(TAG, "🗂️ Error parsing view titles", e)
                        }
                    } else {
                        Log.w(TAG, "🗂️ JavaScript returned null for views")
                    }
                    latch.countDown()
                }
            }

            // Wait up to 5 seconds for JavaScript result
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                Log.w(TAG, "🗂️ Timeout waiting for JavaScript views")
                return null
            }

            if (viewsJson == null || viewsJson == "null") {
                Log.w(TAG, "🗂️ No views found in frontend JavaScript")
                return null
            }

            Log.i(TAG, "🗂️ Successfully retrieved views from frontend")
            viewsJson
        } catch (e: Exception) {
            Log.e(TAG, "🗂️ Error fetching dashboard views", e)
            null
        }
    }

    /**
     * Navigate WebView to a specific dashboard view/tab.
     * Uses JavaScript client-side routing to avoid full page reload.
     *
     * @param viewPath The view path (e.g., "kitchen", "living-room", "0", "1")
     */
    fun navigateToView(viewPath: String) {
        // Determine current dashboard panel from URL path
        val currentUrl = webView.url ?: ""
        val dashboardName = if (currentUrl.isNotEmpty()) {
            try {
                // Extract just the path from the URL, then get the first segment
                // e.g., "http://192.168.1.50:8123/ha-dashie/0" -> "/ha-dashie/0" -> "ha-dashie"
                val uri = android.net.Uri.parse(currentUrl)
                val path = uri.path ?: ""
                val pathSegments = path.split("/").filter { it.isNotEmpty() }
                val extracted = pathSegments.firstOrNull() ?: "lovelace"
                Log.d(TAG, "🗂️ Extracted dashboard name '$extracted' from path '$path'")
                extracted
            } catch (e: Exception) {
                Log.w(TAG, "🗂️ Failed to parse URL, defaulting to lovelace", e)
                "lovelace"
            }
        } else {
            Log.d(TAG, "🗂️ No current URL, defaulting to lovelace")
            "lovelace"
        }

        // Build path for client-side navigation
        val dashboardPath = if (viewPath.startsWith("/")) {
            viewPath
        } else {
            "/$dashboardName/$viewPath"
        }

        Log.i(TAG, "🗂️ Navigating to view: $dashboardPath (dashboard: $dashboardName, viewPath: $viewPath)")

        // Use JavaScript to navigate without page reload
        // Home Assistant uses client-side routing, so we just update the URL and dispatch an event
        webView.post {
            webView.evaluateJavascript("""
                (function() {
                    try {
                        console.log('[TabNav] Navigating to: $dashboardPath');

                        // Use history API to change URL without reload
                        history.pushState(null, null, '$dashboardPath');

                        // Dispatch location-changed event to trigger HA router
                        window.dispatchEvent(new CustomEvent('location-changed', {
                            detail: { replace: false }
                        }));

                        console.log('[TabNav] ✅ Navigation dispatched');
                        return true;
                    } catch (e) {
                        console.error('[TabNav] Navigation error:', e);
                        return false;
                    }
                })();
            """.trimIndent()) { result ->
                Log.d(TAG, "🗂️ Navigation JavaScript result: $result")
            }
        }
    }

    // ============================================
    // Onboarding
    // ============================================

    fun isSetupComplete(): Boolean {
        return halitePrefs()?.connection?.isSetupComplete ?: true
    }

    /**
     * Check if there's a pending HA login success that wasn't consumed by the
     * onboarding JS (e.g., activity was recreated due to memory pressure).
     */
    fun hasPendingLoginSuccess(): Boolean {
        return halitePrefs()?.connection?.pendingHaLoginSuccess ?: false
    }

    /**
     * Clear the pending login success flag after the onboarding JS has consumed it.
     */
    fun clearPendingLoginSuccess() {
        halitePrefs()?.connection?.clearPendingHaLogin()
    }

    /**
     * True when a previous HA login persisted durable credentials (HA URL +
     * refresh token). The onboarding JS checks this on init so a process kill
     * mid-onboarding (e.g., Android force-restarts the app when the
     * REQUEST_INSTALL_PACKAGES grant changes during the permissions step)
     * resumes at the post-login step instead of restarting from Welcome.
     * Unlike pendingHaLoginSuccess, this is derived from stored state, so it
     * survives any number of restarts and the one-shot-flag consumption race.
     */
    fun hasStoredHaCredentials(): Boolean {
        val conn = halitePrefs()?.connection ?: return false
        return conn.haUrl.isNotEmpty() && conn.haRefreshToken.isNotEmpty()
    }

    fun startNetworkScan() {
        val tStart = System.currentTimeMillis()
        Log.i(TAG, "🔍 [t=0ms] startNetworkScan() called from JS — dispatching to background")
        // CRITICAL: HaDiscovery.discoverInstances blocks (CountDownLatch.await)
        // until scan completes. @JavascriptInterface methods run synchronously
        // from JS's perspective, so calling discoverInstances directly here
        // freezes the JS thread for the entire scan — the onboarding loading
        // card never paints (rAF can't fire) and the user sees a white screen
        // for 5-15s. Run the scan on a background thread so the bridge call
        // returns immediately; results come back via the webView.evaluateJavascript
        // callback below.
        Thread {
        val discovery = HaDiscovery(context)
        // 5s cap — HaDiscovery returns early when an HA instance is found, so
        // this only affects the no-HA-on-network case (was 15s default,
        // making the non-HA onboarding flow feel broken-slow).
        discovery.discoverInstances(object : HaDiscovery.DiscoveryCallback {
            override fun onInstanceFound(instance: HaDiscovery.DiscoveredInstance) {
                Log.i(TAG, "🔍 [t=${System.currentTimeMillis() - tStart}ms] Found HA instance: ${instance.url}")
            }

            override fun onDiscoveryComplete(instances: List<HaDiscovery.DiscoveredInstance>) {
                Log.i(TAG, "🔍 [t=${System.currentTimeMillis() - tStart}ms] Network scan complete: ${instances.size} instance(s) found")
                val jsonArray = JSONArray()
                for (instance in instances) {
                    jsonArray.put(JSONObject().apply {
                        put("ip", instance.ip)
                        put("port", instance.port)
                        put("name", instance.name ?: "")
                        put("url", instance.url)
                    })
                }
                val result = JSONObject().apply {
                    put("ha", jsonArray)
                }.toString()
                webView.post {
                    webView.evaluateJavascript(
                        "if(window.dashieOnScanComplete) dashieOnScanComplete($result);",
                        null
                    )
                }
            }

            override fun onProgress(scannedCount: Int, totalCount: Int) {
                // Progress updates not needed for onboarding
            }

            override fun onError(message: String) {
                Log.e(TAG, "🔍 Network scan error: $message")
                val result = JSONObject().apply {
                    put("ha", JSONArray())
                    put("error", message)
                }.toString()
                webView.post {
                    webView.evaluateJavascript(
                        "if(window.dashieOnScanComplete) dashieOnScanComplete($result);",
                        null
                    )
                }
            }
        }, timeoutMs = 5000)
        }.start()
    }

    fun onOnboardingComplete() {
        Log.i(TAG, "✅ Onboarding complete — setting up for normal operation")
        halitePrefs()?.let { prefs ->
            prefs.connection.isSetupComplete = true
            // HA setup just completed — flip the master enable on so the HA
            // dashboard widget, advanced options, and Control Center HA
            // section all surface. Without this the user goes through HA
            // login and lands on a screen with no HA UI visible because
            // home_assistant.enabled was at its post-clearAccountDataLocal
            // / fresh-install state.
            //
            // 🔴 EXCEPT on the custom-URL ("host your own dashboard") path, which
            // ends here too. That path deliberately sets haEnabled=false a moment
            // earlier (kiosk-overlay _applyCustomUrlConfig, whose own comment
            // warns "onOnboardingComplete() would force this TRUE"), and this
            // line was duly forcing it back. The device then loaded the HA shell
            // with no haUrl, fell back to the 192.168.1.x placeholder, and sat on
            // "Home Assistant Unavailable — Reconnecting…" forever while the
            // user's actual dashboard URL was never shown (Fire tablet,
            // 2026-08-18). Only assert HA when this was really an HA setup.
            if (!prefs.connection.useCustomUrl) {
                prefs.connection.haEnabled = true
            } else {
                Log.i(TAG, "🌐 Custom-URL onboarding — leaving home_assistant.enabled false")
            }
            // Kiosk-mode default screensaver: 5 min dim. Only set if still
            // at the fresh-install zero (don't clobber a user who already
            // tweaked it). Logged-in Dashie account users use Sleep instead
            // of Screensaver — `account.isLinked` users skip this branch
            // so their timeout stays at 0 (disabled).
            if (!prefs.account.isLinked && prefs.screensaver.screensaverTimeout == 0) {
                prefs.screensaver.screensaverTimeout = 300
                Log.i(TAG, "🖥️ Kiosk default screensaver: 5min dim")
            }
            // Mark welcome dialogs as shown so SidebarWelcomeDialogs doesn't re-prompt
            prefs.display.betaWelcomeShown = true
            // Skip swipe tip since onboarding already showed the swipe hint
            prefs.display.swipeTipShown = true
            // Mark consent given for data sharing (user saw opt-out checkboxes during onboarding)
            prefs.voice.sampleCollectionConsent = true
            prefs.performance.dashboardTelemetryConsent = true
        }
        webView.post { onOnboardingComplete?.invoke() }
    }

    fun requestAllOnboardingPermissions() {
        Log.i(TAG, "🔐 Requesting all onboarding permissions sequentially")
        webView.post { onRequestAllOnboardingPermissions?.invoke() }
    }

    /**
     * True when the onboarding permission sweep would launch no system screens —
     * everything it requests is already granted/satisfied. Synchronous query
     * (pure permission-state reads) used with [hasStoredHaCredentials] by the
     * onboarding JS to complete onboarding directly after a mid-sweep process
     * kill instead of replaying the wizard.
     */
    fun areOnboardingPermissionsGranted(): Boolean {
        return com.dashieapp.Dashie.MainPermissionDelegate
            .areOnboardingPermissionsSatisfied(context)
    }
}

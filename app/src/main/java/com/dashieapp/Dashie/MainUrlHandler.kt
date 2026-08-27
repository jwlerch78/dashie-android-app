package com.dashieapp.Dashie

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.dashieapp.Dashie.halite.DashboardTelemetryBridge
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaliteScreenController
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.edition.brandName

/**
 * Load the kiosk shell page at the HA origin for same-origin iframe access.
 * Reads the HTML from APK assets and loads via loadDataWithBaseURL so the
 * shell content always comes from the APK — shouldInterceptRequest is NOT
 * reliably called for the initial loadUrl() on all devices/restarts.
 * Sub-resources (CSS, JS bundles) still use shouldInterceptRequest via
 * KioskCssInjector, which works reliably for sub-resource requests.
 */
fun WebView.loadShellFromAssets(shellBaseUrl: String) {
    Log.i("MainUrlHandler", "📦 Loading shell from assets at HA origin: $shellBaseUrl")
    try {
        var html = context.assets.open("webapp/kiosk-shell.html")
            .bufferedReader().use { it.readText() }
        // Replace the HTTP virtual URL base href with the HA origin to avoid
        // mixed content blocking when the shell is loaded at an HTTPS proxy URL.
        // Uses /_dashie/ prefix so shouldInterceptRequest can distinguish overlay
        // asset requests from real HA paths.
        val shellOrigin = shellBaseUrl.substringBeforeLast("/kiosk-shell.html")
        html = html.replace(
            "http://dashie-kiosk-overlay.local:8099/",
            "${shellOrigin.trimEnd('/')}/_dashie/"
        )
        loadDataWithBaseURL(shellBaseUrl, html, "text/html", "UTF-8", shellBaseUrl)
    } catch (e: Exception) {
        Log.e("MainUrlHandler", "📦 Failed to load shell from assets, falling back to loadUrl: ${e.message}")
        loadUrl(shellBaseUrl)
    }
}

/**
 * Handles all URL loading and navigation for MainActivity.
 * Extracted from MainActivity to reduce its size and improve maintainability.
 *
 * Responsibilities:
 * - Determine initial URL to load (Halite vs Standard Dashie)
 * - Load URLs with WebSocket proxy injection when needed
 * - Handle incoming URL intents (ACTION_VIEW, custom dashie:// scheme)
 * - Handle onNewIntent for warm starts
 */
class MainUrlHandler(
    private val activity: ComponentActivity,
    private val webView: WebView,
    private val halitePrefs: () -> HalitePreferences?,
    private val callbacks: Callbacks
) {
    companion object {
        private const val TAG = "MainUrlHandler"
    }

    interface Callbacks {
        fun getTelemetryBridge(): DashboardTelemetryBridge?
        fun getScreenController(): HaliteScreenController?
        fun isHaliteBuild(): Boolean
    }

    /**
     * Flag to prevent onResume from reloading back to default URL
     * when handling an incoming URL intent.
     */
    var handlingUrlIntent: Boolean = false
        private set

    /**
     * Determine the initial URL to load based on build type and preferences.
     *
     * For Halite builds with addon server:
     * - Always loads the kiosk shell page (sidebar + HA iframe)
     * - Sidebar visibility is toggled dynamically via JS (dashieToggleSidebar)
     *
     * For Standard Dashie: Use BuildConfig.BASE_URL
     *
     * @param preferKiosk When true, skip the linked-Dashie-account branch and
     *   route straight to the HA / kiosk shell URL. Used by the "Close Sign-in"
     *   path: a device can have a linked account whose web session has expired
     *   AND a working HA setup — closing the login screen must drop the user
     *   back onto their HA dashboard, not bounce them to dashieapp.com (which
     *   would just redirect to /login/ again, an inescapable loop).
     */
    fun determineInitialUrl(preferKiosk: Boolean = false): String {
        val prefs = halitePrefs()

        return if (BuildConfig.ALLOW_URL_CONFIG && prefs != null) {
            // Check if user has linked a Dashie account — load dashieapp.com instead of kiosk shell
            // (unless force-kiosk debug toggle is enabled, or the caller explicitly wants kiosk)
            Log.i(TAG, "🔄 determineInitialUrl: isLinked=${prefs.account.isLinked}, forceKioskMode=${prefs.account.forceKioskMode}, preferKiosk=$preferKiosk")
            if (prefs.account.showsDashboard && !preferKiosk) {
                val dashieUrl = prefs.account.dashieUrl
                Log.i(TAG, "🔐 Dashie account linked — loading $dashieUrl")
                dashieUrl
            } else {
                // Check if custom URL display is enabled — load it directly in the WebView
                // (bypasses shell entirely; Kotlin overlays still work for sidebar/voice/timers)
                val customUrl = prefs.connection.customUrl
                if (prefs.connection.useCustomUrl && customUrl.isNotEmpty()) {
                    Log.i(TAG, "🌐 Custom URL mode — loading directly: $customUrl")
                    customUrl
                } else {
                    val haUrl = prefs.connection.haUrl
                    val addonServerUrl = prefs.connection.getAddonServerUrl()

                    if (addonServerUrl != null) {
                        // Always load the kiosk shell page so the sidebar can be
                        // toggled dynamically without a full page reload.
                        // The shell hides the sidebar strip on init if dashMenuEnabled is false.
                        // Uses getShellPageUrl() which returns HA-origin URL for same-origin iframe access
                        val shellUrl = prefs.connection.getShellPageUrl()
                        Log.i(TAG, "🏠 Kiosk shell mode: loading $shellUrl (HA: $haUrl, dashMenu: ${prefs.display.dashMenuEnabled})")
                        shellUrl
                    } else {
                        Log.i(TAG, "📱 Loading HA directly (no addon)")
                        haUrl
                    }
                }
            }
        } else {
            // Standard Dashie: use BuildConfig URL
            BuildConfig.BASE_URL
        }
    }

    /**
     * Load the initial URL, handling launch intents if present.
     *
     * @param urlToLoad The default URL to load
     * @param launchIntent The intent that launched the activity (may contain a URL)
     */
    fun loadInitialUrl(urlToLoad: String, launchIntent: Intent?) {
        // Clear WebView cache before loading to ensure fresh JS/CSS files during development
        webView.clearCache(true)

        // Check if we were launched with a URL intent (cold start)
        val hasUrlIntent = launchIntent?.action == Intent.ACTION_VIEW && launchIntent.data != null

        if (hasUrlIntent) {
            Log.i(TAG, "🌐 Launched with URL intent - deferring to handleIncomingUrlIntent")
            // Load default URL first, then navigate to intent URL after WebView is ready
            loadUrlWithWsProxyIfNeeded(urlToLoad)
            // Handle the URL intent after a short delay to let WebView initialize
            Handler(Looper.getMainLooper()).postDelayed({
                handleIncomingUrlIntent(launchIntent)
            }, 500)
        } else {
            Log.i(TAG, "🌐 Loading URL: $urlToLoad (${BuildConfig.ENVIRONMENT})")
            loadUrlWithWsProxyIfNeeded(urlToLoad)
        }
    }

    /**
     * Load a URL with WebSocket proxy injection if needed.
     *
     * For Halite builds with ping/telemetry/auto-reload enabled, we pre-fetch the HTML
     * and inject the WS proxy script before loading. This is necessary because Android's
     * shouldInterceptRequest is NOT called for the initial loadUrl() navigation.
     *
     * For standard Dashie or when WS proxy isn't needed, uses regular loadUrl.
     */
    fun loadUrlWithWsProxyIfNeeded(url: String) {
        // Log initial URL load for auth flow debugging
        DiagnosticBuffer.info("NAV", "loadUrlWithWsProxyIfNeeded: $url")
        Log.i(TAG, "🌐 loadUrlWithWsProxyIfNeeded: $url")

        // Clear WebView cache before loading to prevent stale cached pages
        // This fixes the issue where the app gets stuck after WiFi blips:
        // Without this, WebView may load a cached page even when network is down,
        // resulting in JavaScript running but API calls failing.
        if (BuildConfig.ALLOW_URL_CONFIG) {
            Log.i(TAG, "🧹 Clearing WebView cache before loading")
            webView.clearCache(true)
        }

        val telemetryBridge = callbacks.getTelemetryBridge()

        // Shell URLs are loaded from APK assets via loadDataWithBaseURL — not fetchable externally.
        val isKioskShellUrl = url.contains("/kiosk-shell.html")

        // Login / sign-up pages don't open HA WebSocket connections — skip the
        // WS proxy pre-fetch + injection (was costing up to ~26s on slow
        // networks: 6s pre-fetch fail + 20s shouldInterceptRequest fallback).
        val isLoginUrl = url.contains("/login")

        if (isKioskShellUrl) {
            // Load shell HTML from assets at the HA origin for same-origin iframe access
            Log.i(TAG, "⚡ Kiosk shell URL — loading from assets via loadDataWithBaseURL")
            DiagnosticBuffer.info("NAV", "Loading shell from assets at: $url")
            webView.loadShellFromAssets(url)
        } else if (isLoginUrl) {
            Log.i(TAG, "🔐 Login URL — skipping WS proxy pre-fetch (not needed)")
            DiagnosticBuffer.info("NAV", "Direct loadUrl for login URL (no WS proxy)")
            webView.loadUrl(url)
        } else if (BuildConfig.ALLOW_URL_CONFIG && telemetryBridge?.wsProxyDelegate?.needsWsProxy() == true) {
            Log.i(TAG, "🔌 Using loadUrlWithWsProxy for initial navigation")
            DiagnosticBuffer.info("NAV", "Using WS proxy pre-fetch for initial load")
            telemetryBridge.wsProxyDelegate.loadUrlWithWsProxy(url) {
                // Fallback if pre-fetch fails
                Log.w(TAG, "🔌 WS proxy pre-fetch failed, using regular loadUrl")
                DiagnosticBuffer.warn("NAV", "WS proxy pre-fetch failed, using webView.loadUrl directly")
                webView.loadUrl(url)
            }
        } else {
            DiagnosticBuffer.info("NAV", "Using direct webView.loadUrl (no WS proxy)")
            webView.loadUrl(url)
        }
    }

    /**
     * Handle new intents when activity is brought to foreground.
     * This is called when the activity already exists and is being brought back
     * (e.g., via toForeground API or when returning from external app).
     *
     * @return true if the intent was handled
     */
    fun handleNewIntent(intent: Intent?): Boolean {
        val fromToForeground = intent?.getBooleanExtra("from_to_foreground", false) ?: false
        Log.i(TAG, "handleNewIntent called (fromToForeground=$fromToForeground)")

        // If coming from toForeground API and external app was active, handle the wake
        if (callbacks.isHaliteBuild() && fromToForeground) {
            val screenController = callbacks.getScreenController()
            if (screenController?.isExternalAppActive() == true) {
                Log.i(TAG, "🖼️ toForeground API - returning from external app screensaver")
                screenController.onReturnFromExternalApp()
            }
        }

        // Handle URL intents (from tap_action: url buttons or external apps)
        handleIncomingUrlIntent(intent)

        return true
    }

    /**
     * Handle incoming URL intents from external apps or HA tap_action buttons.
     * Supports:
     * - dashie://open?url=<encoded_url> - Custom scheme for explicit Dashie navigation
     * - dashie://navigate?path=/lovelace/view - Navigate to HA path
     * - http(s)://... - Standard web URLs (user chose Dashie from browser picker)
     *
     * For security, only navigates to URLs that match the configured HA server,
     * or shows a confirmation for external URLs.
     */
    fun handleIncomingUrlIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return

        Log.i(TAG, "🔗 Incoming URL intent: $uri")

        val prefs = halitePrefs()

        val targetUrl: String? = when (uri.scheme) {
            "dashie" -> {
                // Custom scheme: dashie://open?url=<encoded_url>
                // or dashie://navigate?path=/lovelace/view
                when (uri.host) {
                    "open" -> uri.getQueryParameter("url")
                    "navigate" -> {
                        // Build URL from configured HA base + path
                        val path = uri.getQueryParameter("path") ?: uri.path
                        if (path != null) {
                            val baseUrl = prefs?.connection?.haBaseUrl ?: prefs?.connection?.haUrl?.substringBefore("?")
                            baseUrl?.trimEnd('/') + path
                        } else null
                    }
                    else -> null
                }
            }
            "http", "https" -> uri.toString()
            else -> null
        }

        if (targetUrl == null) {
            Log.w(TAG, "🔗 Could not extract target URL from intent")
            return
        }

        // Check if this URL matches the configured HA server
        val haBaseUrl = prefs?.connection?.haBaseUrl?.trimEnd('/')
            ?: prefs?.connection?.haUrl?.substringBefore("?")?.trimEnd('/')

        val isHaUrl = haBaseUrl?.isNotEmpty() == true && targetUrl.startsWith(haBaseUrl)

        // Set flag to prevent onResume from reloading back to default URL
        handlingUrlIntent = true

        val screenController = callbacks.getScreenController()

        if (isHaUrl) {
            // Safe - navigate directly to HA URL
            Log.i(TAG, "🔗 Navigating WebView to HA URL: $targetUrl")
            webView.loadUrl(targetUrl)

            // Wake screen if dimmed
            screenController?.screenDimmer?.resetTimer()
        } else {
            // External URL - show toast and navigate anyway (user explicitly chose Dashie)
            Log.i(TAG, "🔗 Navigating WebView to external URL: $targetUrl")
            Toast.makeText(activity, "Opening in ${activity.brandName()}", Toast.LENGTH_SHORT).show()
            webView.loadUrl(targetUrl)

            // Wake screen if dimmed
            screenController?.screenDimmer?.resetTimer()
        }

        // Clear the flag after a delay to allow onResume to complete
        Handler(Looper.getMainLooper()).postDelayed({
            handlingUrlIntent = false
        }, 1000)
    }

    /**
     * Check if currently handling a URL intent.
     * Used by onResume to avoid reloading when a URL intent is being processed.
     */
    fun isHandlingUrlIntent(): Boolean = handlingUrlIntent
}

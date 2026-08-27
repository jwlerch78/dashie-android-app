package com.dashieapp.Dashie.halite.telemetryDelegates

import android.content.Context
import android.util.Log
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Delegate handling WebSocket proxy injection and script loading.
 * Manages HTML interception to inject WS proxy before page JS runs,
 * and injects telemetry/monitor scripts after page load.
 */
class WsProxyDelegate(
    private val context: Context,
    private val webView: WebView,
    private val halitePrefs: HalitePreferences,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "WsProxyDelegate"
    }

    // WebSocket proxy script for HTML injection (with <script> tags)
    private val wsProxyScript = """<script>(function(){if(window._dwp)return;window._dwp=1;window._dws=[];var O=window.WebSocket;window._dwo=O;window.WebSocket=function(u,p){var w=p?new O(u,p):new O(u);if(u&&u.indexOf('/api/websocket')!==-1){window._dws.push({ws:w,url:u});console.log('[DashieLite] WS proxy: intercepted');}return w;};window.WebSocket.prototype=O.prototype;window.WebSocket.CONNECTING=O.CONNECTING;window.WebSocket.OPEN=O.OPEN;window.WebSocket.CLOSING=O.CLOSING;window.WebSocket.CLOSED=O.CLOSED;console.log('[DashieLite] WS proxy installed');})();</script>"""

    /**
     * Get the WS proxy script content (without <script> tags) for manual injection.
     */
    fun buildWsProxyScript(): String {
        return """(function(){if(window._dwp)return;window._dwp=1;window._dws=[];var O=window.WebSocket;window._dwo=O;window.WebSocket=function(u,p){var w=p?new O(u,p):new O(u);if(u&&u.indexOf('/api/websocket')!==-1){window._dws.push({ws:w,url:u});console.log('[DashieLite] WS proxy: intercepted');}return w;};window.WebSocket.prototype=O.prototype;window.WebSocket.CONNECTING=O.CONNECTING;window.WebSocket.OPEN=O.OPEN;window.WebSocket.CLOSING=O.CLOSING;window.WebSocket.CLOSED=O.CLOSED;console.log('[DashieLite] WS proxy installed');})();"""
    }

    /**
     * Load a URL with WS proxy pre-injected into the HTML.
     * Manually fetches HTML, injects proxy, and uses loadDataWithBaseURL.
     */
    fun loadUrlWithWsProxy(url: String, onFallback: () -> Unit) {
        scope.launch {
            try {
                Log.i(TAG, "Pre-fetching HTML for WS proxy injection: $url")

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")

                val cookieManager = android.webkit.CookieManager.getInstance()
                val cookies = cookieManager.getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    connection.setRequestProperty("Cookie", cookies)
                }

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    Log.w(TAG, "Pre-fetch failed ($responseCode), falling back to loadUrl")
                    connection.disconnect()
                    withContext(Dispatchers.Main) { onFallback() }
                    return@launch
                }

                val html = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val wsScript = "<script>${buildWsProxyScript()}</script>"
                val modifiedHtml = if (html.contains("<head>", ignoreCase = true)) {
                    html.replaceFirst(Regex("<head>", RegexOption.IGNORE_CASE), "<head>$wsScript")
                } else if (html.contains("<html", ignoreCase = true)) {
                    val htmlTagRegex = Regex("<html[^>]*>", RegexOption.IGNORE_CASE)
                    val match = htmlTagRegex.find(html)
                    if (match != null) {
                        html.replaceFirst(htmlTagRegex, "${match.value}<head>$wsScript</head>")
                    } else {
                        "$wsScript$html"
                    }
                } else {
                    "$wsScript$html"
                }

                Log.i(TAG, "Injected WS proxy, loading via loadDataWithBaseURL (${html.length} -> ${modifiedHtml.length} bytes)")

                withContext(Dispatchers.Main) {
                    webView.loadDataWithBaseURL(
                        url,
                        modifiedHtml,
                        "text/html",
                        "UTF-8",
                        url
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Pre-fetch failed: ${e.message}, falling back to loadUrl")
                withContext(Dispatchers.Main) { onFallback() }
            }
        }
    }

    /**
     * Check if WS proxy injection is needed for any enabled feature.
     */
    fun needsWsProxy(): Boolean {
        val telemetryEnabled = halitePrefs.performance.dashboardTelemetryEnabled && halitePrefs.performance.dashboardTelemetryConsent
        val pingEnabled = halitePrefs.performance.websocketPingEnabled
        val autoReloadEnabled = halitePrefs.performance.autoReloadStaleMinutes > 0
        return telemetryEnabled || pingEnabled || autoReloadEnabled
    }

    /**
     * Intercept HTML response and inject WebSocket proxy script at the start.
     * Called from shouldInterceptRequest in WebViewClient.
     */
    fun interceptHtmlForWsProxy(url: String, headers: Map<String, String>?): WebResourceResponse? {
        try {
            Log.i(TAG, "Intercepting HTML for WS proxy injection: $url")

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            headers?.forEach { (key, value) ->
                if (!key.equals("Accept-Encoding", ignoreCase = true)) {
                    connection.setRequestProperty(key, value)
                }
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "HTML fetch failed with code $responseCode")
                if (responseCode == 401 || responseCode == 403) {
                    DiagnosticBuffer.warn("AUTH-CIRCUIT", "WS proxy auth failure: HTTP $responseCode for ${url.take(80)}")
                }
                connection.disconnect()
                return null
            }

            val contentType = connection.contentType ?: "text/html"
            if (!contentType.contains("text/html")) {
                Log.d(TAG, "Not HTML content: $contentType")
                connection.disconnect()
                return null
            }

            val html = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val modifiedHtml = if (html.contains("<head>", ignoreCase = true)) {
                html.replaceFirst(
                    Regex("<head>", RegexOption.IGNORE_CASE),
                    "<head>$wsProxyScript"
                )
            } else if (html.contains("<html", ignoreCase = true)) {
                val htmlTagRegex = Regex("<html[^>]*>", RegexOption.IGNORE_CASE)
                val match = htmlTagRegex.find(html)
                if (match != null) {
                    html.replaceFirst(htmlTagRegex, "${match.value}$wsProxyScript")
                } else {
                    "$wsProxyScript$html"
                }
            } else {
                "$wsProxyScript$html"
            }

            Log.i(TAG, "Injected WS proxy into HTML (${html.length} -> ${modifiedHtml.length} bytes)")

            return WebResourceResponse(
                "text/html",
                "UTF-8",
                ByteArrayInputStream(modifiedHtml.toByteArray(Charsets.UTF_8))
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to intercept HTML: ${e.message}")
            return null
        }
    }

    /**
     * Check if WebSocket ping keep-alive is enabled.
     */
    fun isWebsocketPingEnabled(): Boolean {
        return halitePrefs.performance.websocketPingEnabled
    }

    /**
     * Get the WebSocket ping interval in milliseconds.
     */
    fun getWebsocketPingIntervalMs(): Int {
        return 10000  // 10 seconds
    }

    /**
     * Legacy method - now handled via shouldInterceptRequest HTML injection.
     */
    fun injectWsMonitorScript() {
        Log.d(TAG, "injectWsMonitorScript called (now handled via HTML interception)")
    }

    /**
     * Inject the full WebSocket monitor script (phase 2).
     * Attaches to WebSockets intercepted by the minimal proxy.
     * Called from onPageFinished.
     */
    fun injectWsMonitorFullScript() {
        val telemetryEnabled = halitePrefs.performance.dashboardTelemetryEnabled && halitePrefs.performance.dashboardTelemetryConsent
        val pingEnabled = halitePrefs.performance.websocketPingEnabled
        val autoReloadEnabled = halitePrefs.performance.autoReloadStaleMinutes > 0

        if (!telemetryEnabled && !pingEnabled && !autoReloadEnabled) {
            Log.d(TAG, "WebSocket monitor not needed - telemetry, ping, and auto-reload all disabled")
            // This is the disconnect-detection arming point. If all three are
            // off, the HA-disconnect JS signal never reaches Kotlin — overnight
            // outages go undetected and the dashboard can't auto-recover.
            PersistentLog.warn("HA_CONN",
                "WS monitor NOT injected — telemetry/ping/autoReload all disabled (no disconnect detection)")
            return
        }

        Log.i(TAG, "Injecting full WebSocket monitor (phase 2) - telemetry=$telemetryEnabled, ping=$pingEnabled, autoReload=$autoReloadEnabled")

        try {
            val script = context.assets.open("js/websocket-monitor.js")
                .bufferedReader()
                .use { it.readText() }

            webView.evaluateJavascript(script, null)

            val autoReloadMinutes = halitePrefs.performance.autoReloadStaleMinutes
            webView.evaluateJavascript(
                "if (window.setAutoReloadStaleMinutes) window.setAutoReloadStaleMinutes($autoReloadMinutes);",
                null
            )
            Log.i(TAG, "Auto-reload configured: ${if (autoReloadMinutes == 0) "disabled" else "$autoReloadMinutes minutes"}")
            PersistentLog.info("HA_CONN",
                "WS monitor injected — telemetry=$telemetryEnabled ping=$pingEnabled autoReload=$autoReloadEnabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject WS monitor script: ${e.message}")
            PersistentLog.error("HA_CONN", "WS monitor injection FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Inject the telemetry script into the WebView.
     */
    fun injectTelemetryScript() {
        Log.i(TAG, "Injecting telemetry script")

        try {
            val script = context.assets.open("js/dashboard-telemetry.js")
                .bufferedReader()
                .use { it.readText() }

            webView.post {
                webView.evaluateJavascript(script, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject telemetry script: ${e.message}")
        }
    }
}

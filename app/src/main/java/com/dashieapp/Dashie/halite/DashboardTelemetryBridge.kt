package com.dashieapp.Dashie.halite

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.dashieapp.Dashie.halite.telemetryDelegates.AuthDelegate
import com.dashieapp.Dashie.halite.telemetryDelegates.ConnectionDelegate
import com.dashieapp.Dashie.halite.telemetryDelegates.TelemetryDelegate
import com.dashieapp.Dashie.halite.telemetryDelegates.WsProxyDelegate

/**
 * JavaScript bridge for dashboard telemetry collection.
 * Registered as "DashieBridge" in the WebView.
 *
 * Thin router that delegates to domain-specific delegates:
 * - TelemetryDelegate: telemetry events, WS metrics, health alerts, device metrics
 * - AuthDelegate: auth events, proactive token refresh
 * - ConnectionDelegate: HA connection lifecycle (disconnect/reconnect/degraded/failed)
 * - WsProxyDelegate: WebSocket proxy injection, script loading
 */
class DashboardTelemetryBridge(
    context: Context,
    private val webView: WebView,
    halitePrefs: HalitePreferences
) {
    companion object {
        private const val TAG = "DashboardTelemetry"
    }

    // Public delegates for direct access from Kotlin callers
    val telemetryDelegate = TelemetryDelegate(context, webView, halitePrefs)
    val authDelegate = AuthDelegate(webView, halitePrefs)
    val connectionDelegate = ConnectionDelegate()
    val wsProxyDelegate = WsProxyDelegate(context, webView, halitePrefs, telemetryDelegate.scope)

    // ==================== Telemetry (JS -> Kotlin) ====================

    @JavascriptInterface
    fun onTelemetry(jsonData: String) = telemetryDelegate.onTelemetry(jsonData)

    @JavascriptInterface
    fun onLovelaceConfig(jsonData: String) = telemetryDelegate.onLovelaceConfig(jsonData)

    @JavascriptInterface
    fun onWsMetrics(jsonData: String) = telemetryDelegate.onWsMetrics(jsonData)

    @JavascriptInterface
    fun onWsHealthAlert(jsonData: String) = telemetryDelegate.onWsHealthAlert(jsonData)

    @JavascriptInterface
    fun getFeatureState(): String = telemetryDelegate.getFeatureState()

    @JavascriptInterface
    fun isInteractionPriorityEnabled(): Boolean = telemetryDelegate.isInteractionPriorityEnabled()

    // ==================== Auth (JS -> Kotlin) ====================

    @JavascriptInterface
    fun onAuthEvent(jsonData: String) = authDelegate.onAuthEvent(jsonData)

    @JavascriptInterface
    fun onAuthInvalid(jsonData: String) = authDelegate.onAuthInvalid(jsonData)

    // ==================== HA Connection (JS -> Kotlin) ====================

    @JavascriptInterface
    fun onHaDisconnect(jsonData: String) = connectionDelegate.onHaDisconnect(jsonData)

    @JavascriptInterface
    fun onHaConnected(jsonData: String) = connectionDelegate.onHaConnected(jsonData)

    @JavascriptInterface
    fun onConnectionDegraded(jsonData: String) = connectionDelegate.onConnectionDegraded(jsonData)

    @JavascriptInterface
    fun onConnectionFailed(jsonData: String) = connectionDelegate.onConnectionFailed(jsonData)

    @JavascriptInterface
    fun setSettingsOpen(open: Boolean) = connectionDelegate.setSettingsOpen(open)

    // ==================== WS Proxy (JS -> Kotlin) ====================

    @JavascriptInterface
    fun isWebsocketPingEnabled(): Boolean = wsProxyDelegate.isWebsocketPingEnabled()

    @JavascriptInterface
    fun getWebsocketPingIntervalMs(): Int = wsProxyDelegate.getWebsocketPingIntervalMs()

    // ==================== Utility (JS -> Kotlin) ====================

    /**
     * Get the current WebView URL. Called from overlay iframe's
     * getCurrentDashboardUrl() when cross-origin blocks parent.location access.
     */
    @JavascriptInterface
    fun getCurrentUrl(): String {
        return try {
            var url = ""
            val latch = java.util.concurrent.CountDownLatch(1)

            webView.post {
                webView.evaluateJavascript("window.location.href") { result ->
                    url = result?.trim('"') ?: ""
                    if (url.isEmpty() || url == "null") {
                        url = webView.url ?: ""
                    }
                    latch.countDown()
                }
            }

            latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            url
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get current URL: ${e.message}")
            ""
        }
    }

    // ==================== Lifecycle ====================

    fun destroy() {
        authDelegate.destroy()
        telemetryDelegate.destroy()
    }
}

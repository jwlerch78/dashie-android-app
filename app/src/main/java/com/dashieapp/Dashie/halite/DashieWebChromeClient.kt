package com.dashieapp.Dashie.halite

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer

/**
 * WebChromeClient that forwards JavaScript console messages to logcat for debugging,
 * grants permissions for DRM/protected media (Widevine) required for Spotify playback,
 * and handles window.open() calls for HA tap_action: url functionality.
 */
class DashieWebChromeClient(private val context: Context) : WebChromeClient() {

    companion object {
        private const val TAG = "DashieWebChromeClient"
        private const val MAX_JS_ERRORS = 20

        // Thread-safe circular buffer for recent JS errors
        private val recentJsErrors = java.util.concurrent.ConcurrentLinkedDeque<String>()

        /**
         * Get the most recent JS errors for crash diagnostics.
         */
        fun getRecentJsErrors(): List<String> {
            return recentJsErrors.toList()
        }

        /**
         * Clear the JS error buffer (call after crash report is generated).
         */
        fun clearJsErrors() {
            recentJsErrors.clear()
        }
    }

    /**
     * Grant permissions requested by the WebView, including RESOURCE_PROTECTED_MEDIA_ID
     * which is required for Widevine DRM (used by Spotify Web Playback SDK).
     */
    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.let {
            Log.i(TAG, "Permission request: ${it.resources.joinToString()}")
            // Grant all requested permissions (includes DRM/protected media)
            it.grant(it.resources)
        }
    }

    // Reference to parent WebView for loading auth URLs in same window
    private var parentWebViewRef: java.lang.ref.WeakReference<WebView>? = null

    /**
     * Set the parent WebView reference (called from MainActivity setup).
     * Needed to load auth URLs from window.open() in the same WebView.
     */
    fun setParentWebView(webView: WebView) {
        parentWebViewRef = java.lang.ref.WeakReference(webView)
    }

    /**
     * Handle window.open() calls from JavaScript.
     * Home Assistant uses window.open() for tap_action: url buttons.
     * We intercept these and:
     * - Auth URLs: Load in the same WebView (must not open in external browser!)
     * - Other URLs: Open in external browser
     */
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        Log.d(TAG, "onCreateWindow called: isDialog=$isDialog, isUserGesture=$isUserGesture")
        // Log to diagnostic buffer for debugging auth issues (window.open may be used for OAuth)
        DiagnosticBuffer.info("WINDOW", "onCreateWindow: isDialog=$isDialog, isUserGesture=$isUserGesture")

        // Create a temporary WebView to capture the URL from window.open()
        val tempWebView = WebView(context)
        tempWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString()
                Log.i(TAG, "🔗 window.open() URL captured: $url")

                // Check if this is an auth-related URL - these MUST stay in WebView
                val isAuthRelated = url?.let {
                    it.contains("/auth/") || it.contains("/authorize") ||
                    it.contains("/login") || it.contains("auth_callback") ||
                    it.contains("oauth") || it.contains("openid")
                } ?: false
                DiagnosticBuffer.info("WINDOW", "window.open URL: $url (isAuth=$isAuthRelated)")

                if (url != null) {
                    if (isAuthRelated) {
                        // Auth URLs MUST stay in WebView - load in parent WebView
                        Log.i(TAG, "🔗 Auth URL from window.open() - loading in WebView: $url")
                        DiagnosticBuffer.info("WINDOW", "Auth URL staying in WebView: $url")
                        parentWebViewRef?.get()?.loadUrl(url)
                    } else if (isSameOriginAsHa(url)) {
                        // Same-origin HA URL (e.g. tap_action: url /energy, #popup) —
                        // route into the HA iframe via hash change or pushState so HA's
                        // SPA router handles it instead of launching an external browser.
                        Log.i(TAG, "🔗 Same-origin HA URL from window.open() - routing to iframe: $url")
                        DiagnosticBuffer.info("WINDOW", "Same-origin URL routed to HA iframe: $url")
                        routeSameOriginUrlToHaIframe(url)
                    } else {
                        // Non-auth, cross-origin URLs → external browser
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            Log.i(TAG, "🔗 Opened URL in external browser: $url")
                            DiagnosticBuffer.info("WINDOW", "Opened in external browser via window.open: $url")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to open URL: ${e.message}")
                            DiagnosticBuffer.error("WINDOW", "Failed to open window.open URL: ${e.message}")
                        }
                    }
                }

                // Don't actually load in the temp WebView
                return true
            }
        }

        // Transport the URL to the temp WebView
        val transport = resultMsg?.obj as? WebView.WebViewTransport
        transport?.webView = tempWebView
        resultMsg?.sendToTarget()

        return true
    }

    /**
     * True if [url]'s origin matches the configured HA base URL's origin.
     * haBaseUrl is stored origin-only (e.g. "http://192.168.1.50:8123"), so a
     * startsWith check against the origin portion of [url] is sufficient.
     */
    private fun isSameOriginAsHa(url: String): Boolean {
        val haBaseUrl = try {
            HalitePreferences(context).connection.haBaseUrl.trimEnd('/')
        } catch (e: Exception) {
            ""
        }
        if (haBaseUrl.isEmpty()) return false
        return try {
            val parsed = Uri.parse(url)
            val origin = if (parsed.port == -1) {
                "${parsed.scheme}://${parsed.host}"
            } else {
                "${parsed.scheme}://${parsed.host}:${parsed.port}"
            }
            haBaseUrl.startsWith(origin)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Route a same-origin URL into the HA iframe without a full document reload.
     * Hash-only differences become [location.hash] assignments (same-document, no reload).
     * Path differences use [history.pushState] + fire a "location-changed" event so HA's
     * SPA router updates the view in place.
     */
    private fun routeSameOriginUrlToHaIframe(url: String) {
        val escaped = url.replace("\\", "\\\\").replace("'", "\\'")
        val js = """
            (function() {
                try {
                    var iframe = document.getElementById('ha-content');
                    var iw = iframe && iframe.contentWindow;
                    if (!iw) {
                        console.warn('[Dashie] routeSameOriginUrlToHaIframe: no ha-content iframe');
                        return;
                    }
                    var cur = iw.location;
                    var target = new URL('$escaped', cur.href);
                    if (cur.origin !== target.origin) {
                        console.warn('[Dashie] routeSameOriginUrlToHaIframe: origin mismatch', cur.origin, target.origin);
                        return;
                    }
                    if (cur.pathname === target.pathname && cur.search === target.search) {
                        iw.location.hash = target.hash || '';
                    } else {
                        iw.history.pushState(null, '', target.href);
                        iw.dispatchEvent(new Event('location-changed'));
                    }
                } catch (e) {
                    console.warn('[Dashie] routeSameOriginUrlToHaIframe failed:', e && e.message);
                }
            })();
        """.trimIndent()
        parentWebViewRef?.get()?.post {
            parentWebViewRef?.get()?.evaluateJavascript(js, null)
        }
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        consoleMessage?.let {
            val level = when (it.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> "E"
                ConsoleMessage.MessageLevel.WARNING -> "W"
                else -> "I"
            }
            Log.println(
                when (level) { "E" -> Log.ERROR; "W" -> Log.WARN; else -> Log.INFO },
                "WebViewConsole",
                "[${it.sourceId()}:${it.lineNumber()}] ${it.message()}"
            )

            // Track JS errors for crash diagnostics
            if (it.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                val errorEntry = "[${it.sourceId()}:${it.lineNumber()}] ${it.message()}"
                recentJsErrors.addLast(errorEntry)
                // Keep buffer at max size
                while (recentJsErrors.size > MAX_JS_ERRORS) {
                    recentJsErrors.pollFirst()
                }
            }
        }
        return true
    }
}

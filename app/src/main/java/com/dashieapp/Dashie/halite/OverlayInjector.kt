package com.dashieapp.Dashie.halite

import android.util.Log
import android.webkit.WebView

/**
 * Manages the relationship between the kiosk shell page and its content.
 *
 * The kiosk shell page (kiosk-shell.html) is the top-level WebView page.
 * It contains:
 * - The sidebar (native DOM, rendered by kiosk-shell.bundle.js)
 * - The headless services iframe (dashie-overlay, loads kiosk-services.html for timers/voice/NLP)
 * - The HA content iframe (loaded via evaluateJavascript after shell is ready)
 *
 * All overlay communication (screensaver proxy, drawer proxy, keyboard routing,
 * timer events) is handled by kiosk-overlay-bridge.js in the shell page.
 *
 * Legacy methods (injectDashieLiteOverlay, injectDashMenu) are retained for
 * backwards compatibility but are no longer called in the shell architecture.
 */
class OverlayInjector(
    private val halitePrefs: HalitePreferences
) {
    companion object {
        private const val TAG = "OverlayInjector"
    }

    /**
     * Get the URL for the kiosk shell page.
     * When HA is configured, serves from HA origin for same-origin iframe access.
     * Falls back to virtual overlay URL during onboarding/unconfigured state.
     * Served from APK assets via KioskCssInjector.serveOverlayFromAssets().
     */
    fun getShellPageUrl(): String {
        return halitePrefs.connection.getShellPageUrl()
    }

    /**
     * Tell the shell page to load HA into its content iframe.
     * Called after the shell page finishes loading (onPageFinished).
     */
    fun loadHaIntoShellPage(view: WebView?, haUrl: String) {
        if (view == null || haUrl.isBlank()) {
            Log.w(TAG, "Cannot load HA: view=$view, url=$haUrl")
            return
        }

        Log.i(TAG, "Loading HA into shell iframe: $haUrl")

        // Escape single quotes in the URL for JS string literal
        val escapedUrl = haUrl.replace("'", "\\'")
        view.evaluateJavascript("dashieSetHaUrl('$escapedUrl')") { result ->
            Log.d(TAG, "HA URL set result: $result")
        }
    }

    /**
     * Tell the shell page to load HA with token injection.
     * Used after onboarding native login — injects tokens directly into the iframe
     * via postMessage to avoid storage partitioning issues.
     */
    fun loadHaIntoShellPageWithTokens(view: WebView?, haUrl: String, tokenJson: String) {
        if (view == null || haUrl.isBlank()) {
            Log.w(TAG, "Cannot load HA with tokens: view=$view, url=$haUrl")
            return
        }

        Log.i(TAG, "Loading HA into shell iframe WITH token injection: $haUrl")

        val escapedUrl = haUrl.replace("'", "\\'")
        val escapedJson = tokenJson.replace("\\", "\\\\").replace("'", "\\'")
        view.evaluateJavascript(
            // WEBAPP-EXEMPT: dashieLoadHaWithTokens — kiosk-overlay shell API
            "if(window.dashieLoadHaWithTokens) { dashieLoadHaWithTokens('$escapedUrl', '$escapedJson'); } else { dashieSetHaUrl('$escapedUrl'); }"
        ) { result ->
            Log.d(TAG, "HA URL set with tokens result: $result")
        }
    }

    /**
     * Tell the shell page to load a custom URL into the custom-content iframe.
     * Hides the HA iframe and shows the custom one instead.
     */
    fun loadCustomUrlIntoShellPage(view: WebView?, customUrl: String) {
        if (view == null || customUrl.isBlank()) {
            Log.w(TAG, "Cannot load custom URL: view=$view, url=$customUrl")
            return
        }

        Log.i(TAG, "Loading custom URL into shell iframe: $customUrl")

        val escapedUrl = customUrl.replace("'", "\\'")
        view.evaluateJavascript("dashieSetCustomUrl('$escapedUrl')") { result ->
            Log.d(TAG, "Custom URL set result: $result")
        }
    }

    /**
     * Check if a URL is the kiosk shell page.
     * Matches both same-origin URL ({haBaseUrl}/kiosk-shell.html) and
     * legacy virtual URL (dashie-kiosk-overlay.local:8099/kiosk-shell.html).
     */
    fun isShellPageUrl(url: String?): Boolean {
        if (url == null) return false
        // Match same-origin shell at any HA origin (proxy or local)
        for (origin in halitePrefs.connection.getAllHaOrigins()) {
            if (url.startsWith("$origin/kiosk-shell")) return true
        }
        // Match legacy virtual URL shell
        val addonServerUrl = halitePrefs.connection.getAddonServerUrl().trimEnd('/')
        return url.startsWith("$addonServerUrl/kiosk-shell")
    }

    // ========================================================================
    // Legacy methods — retained for backwards compatibility.
    // Not called when using the shell page architecture.
    // ========================================================================

    /**
     * [LEGACY] Inject Dashie Lite overlay iframe on top of HA.
     * In the shell architecture, the overlay is included in kiosk-shell.html.
     */
    fun injectDashieLiteOverlay(view: WebView?, url: String?) {
        val addonServerUrl = halitePrefs.connection.getAddonServerUrl()

        if (url?.contains("/auth/") == true ||
            url?.contains("/authorize") == true ||
            url?.contains("/login") == true) {
            return
        }

        val overlayUrl = "$addonServerUrl/kiosk-services.html"
        Log.i(TAG, "[Legacy] Injecting overlay iframe: $overlayUrl")

        val script = """
            (function() {
                if (window._dashieOverlayInitStarted) return;
                window._dashieOverlayInitStarted = true;
                if (document.getElementById('dashie-overlay')) return;

                var iframe = document.createElement('iframe');
                iframe.id = 'dashie-overlay';
                iframe.src = '$overlayUrl';
                iframe.style.cssText = 'display: none';
                iframe.setAttribute('tabindex', '-1');
                document.body.appendChild(iframe);

                window.addEventListener('message', function(event) {
                    if (!iframe || event.source !== iframe.contentWindow) return;
                    var data = event.data;
                    if (data && data.source === 'dashie-overlay' && data.type === 'ready') {
                        window.dashieOverlayReady = true;
                    }
                });
            })();
        """.trimIndent()

        view?.evaluateJavascript(script, null)
    }

    /**
     * [LEGACY] Inject the Dash Menu sidebar iframe on top of HA.
     * In the shell architecture, the sidebar is native DOM in the shell page.
     */
    fun injectDashMenu(view: WebView?, url: String?) {
        if (!halitePrefs.display.dashMenuEnabled) return
        if (halitePrefs.account.showsDashboard) return

        val addonServerUrl = halitePrefs.connection.getAddonServerUrl()

        if (url?.contains("/auth/") == true ||
            url?.contains("/authorize") == true ||
            url?.contains("/login") == true) {
            return
        }

        val menuUrl = "$addonServerUrl/dash-menu.html"
        Log.i(TAG, "[Legacy] Injecting Dash Menu iframe: $menuUrl")

        val script = """
            (function() {
                if (window._dashMenuInitStarted) return;
                window._dashMenuInitStarted = true;
                if (document.getElementById('dashie-dash-menu')) return;

                var STRIP_WIDTH = 60;
                var iframe = document.createElement('iframe');
                iframe.id = 'dashie-dash-menu';
                iframe.src = '$menuUrl';
                iframe.style.cssText = 'position:fixed;left:0;top:0;width:' + STRIP_WIDTH + 'px;height:100vh;z-index:9990;border:none;background:transparent;pointer-events:auto';
                document.body.appendChild(iframe);

                var vw = document.documentElement.clientWidth || window.innerWidth;
                var zoom = (vw - STRIP_WIDTH) / vw;
                document.body.style.zoom = zoom;
                document.body.style.marginLeft = Math.ceil(STRIP_WIDTH / zoom) + 'px';
                document.body.style.overflowX = 'hidden';
                var htmlZoom = parseFloat(document.documentElement.style.zoom) || 1;
                iframe.style.zoom = (1 / (htmlZoom * zoom));

                window.addEventListener('message', function(event) {
                    if (!iframe || event.source !== iframe.contentWindow) return;
                    var data = event.data;
                    if (data && data.source === 'dash-menu' && data.type === 'resize') {
                        iframe.style.width = data.width + 'px';
                    }
                });
            })();
        """.trimIndent()

        view?.evaluateJavascript(script, null)
    }
}

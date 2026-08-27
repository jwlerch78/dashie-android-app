package com.dashieapp.Dashie

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.dashieapp.Dashie.api.DashieApiPreferences
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer

/**
 * Intercepts HA HTML responses to inject kiosk CSS for standard Dashie.
 * Extracted from MainActivity to reduce file size.
 *
 * Since HA is loaded in a cross-origin iframe, we can't inject CSS via JavaScript
 * from the parent page. Instead, we intercept HTTP requests to the HA server and
 * inject our kiosk script directly into the HTML response.
 *
 * This approach works because Android's WebView lets us intercept ALL requests,
 * including those from iframes, and modify the response before it's rendered.
 */
class MainHaKioskCssInjector(
    private val context: Context,
    private val prefsProvider: () -> DashieApiPreferences?
) {
    companion object {
        private const val TAG = "HaKioskCssInjector"
    }

    /**
     * Intercept HA HTML responses to inject kiosk CSS.
     *
     * @return WebResourceResponse with injected CSS, or null to use default behavior
     */
    fun interceptHaHtmlForKioskCss(request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null
        val uri = request.url ?: return null

        val prefs = prefsProvider() ?: return null

        // Only the main-frame HA document load matters for hide-UI; gating the diagnostic
        // logging on it keeps the captured KIOSK_UI category to one line per navigation
        // (not every sub-resource). These lines let us diagnose "hiding doesn't work on one
        // tablet" from the tester's Send/Download Diagnostics instead of needing adb logcat.
        val isMainFrame = request.isForMainFrame

        // Get the HA base URL from preferences
        var haBaseUrl = prefs.haBaseUrl.trimEnd('/')

        // Fallback: detect Home Assistant by URL patterns if haBaseUrl is not set
        // HA typically runs on port 8123 or has known paths like /lovelace, /ha-dashie
        val isLikelyHaUrl = haBaseUrl.isEmpty() && (
            uri.port == 8123 ||
            uri.path?.startsWith("/lovelace") == true ||
            uri.path?.startsWith("/ha-dashie") == true ||
            uri.path?.startsWith("/local/") == true
        )

        if (isLikelyHaUrl) {
            // Auto-detect and store HA base URL
            haBaseUrl = "${uri.scheme}://${uri.host}${if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""}"
            Log.i(TAG, "Auto-detected HA base URL: $haBaseUrl")
            // Store it for future use
            prefs.haBaseUrl = haBaseUrl
        }

        if (haBaseUrl.isEmpty()) return null

        // Only intercept requests to the HA server
        if (!url.startsWith(haBaseUrl)) {
            if (isMainFrame) DiagnosticBuffer.info("KIOSK_UI", "skip: main-frame URL not under HA base — url=$url haBase=$haBaseUrl")
            return null
        }

        // Only intercept HTML document requests (not scripts, images, etc.).
        // A newer WebView (e.g. Android 16 Chromium) may not populate the Accept header
        // the same way in shouldInterceptRequest — if it lacks text/html we silently skip
        // interception and nothing gets injected. Log it so we can see this on the device.
        val acceptHeader = request.requestHeaders?.get("Accept") ?: ""
        if (!acceptHeader.contains("text/html")) {
            if (isMainFrame) DiagnosticBuffer.warn("KIOSK_UI", "skip: main-frame Accept lacks text/html — accept='$acceptHeader' url=$url")
            return null
        }

        // Don't intercept auth pages
        if (url.contains("/auth/") || url.contains("/authorize") ||
            url.contains("/login") || url.contains("auth_callback")) {
            return null
        }

        val hideSidebar = prefs.hideSidebar
        val hideTabs = prefs.hideTabs
        val hideSearch = prefs.hideSearch
        val hideAssistant = prefs.hideAssistant

        // If nothing to hide, skip interception
        if (!hideSidebar && !hideTabs && !hideSearch && !hideAssistant) {
            if (isMainFrame) DiagnosticBuffer.info("KIOSK_UI", "skip: no hide flags enabled (main-frame HA doc) — settings may not have persisted; url=$url")
            return null
        }

        Log.i(TAG, "Intercepting HA HTML for kiosk CSS: $url")
        Log.i(TAG, "Settings: sidebar=$hideSidebar, tabs=$hideTabs, search=$hideSearch, assistant=$hideAssistant")
        DiagnosticBuffer.info("KIOSK_UI", "intercepting HA HTML (mainFrame=$isMainFrame) flags sidebar=$hideSidebar header=$hideTabs search=$hideSearch assistant=$hideAssistant url=$url")

        try {
            // Fetch the original HTML
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            // Copy headers from original request (for auth cookies, etc.)
            request.requestHeaders?.forEach { (key, value) ->
                if (!key.equals("Accept-Encoding", ignoreCase = true)) { // Don't copy compression
                    connection.setRequestProperty(key, value)
                }
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "HA HTML fetch failed with code $responseCode")
                DiagnosticBuffer.warn("KIOSK_UI", "HA HTML re-fetch returned HTTP $responseCode — injection skipped, default load used")
                connection.disconnect()
                return null
            }

            val contentType = connection.contentType ?: "text/html"
            if (!contentType.contains("text/html")) {
                Log.d(TAG, "Not HTML content: $contentType")
                connection.disconnect()
                return null
            }

            // Read the original HTML
            val inputStream = connection.inputStream
            val html = inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            // Build injection scripts
            val parentBridgeScript = buildParentBridgeScript()
            val kioskScript = buildKioskInjectionScript(hideSidebar, hideTabs, hideSearch, hideAssistant)

            // Block HA theme persistence to prevent cross-device theme sync.
            // See KioskCssInjector.kt for detailed explanation.
            val themeBlockerScript = """(function(){var o=WebSocket.prototype.send;WebSocket.prototype.send=function(d){try{if(typeof d==='string'&&d.indexOf('set_user_data')!==-1){var m=JSON.parse(d);if(m.type==='frontend/set_user_data'&&m.key==='theme'){console.log('[Dashie] Blocked HA theme persistence');return;}}}catch(e){}return o.call(this,d);};})();"""

            // Inject scripts at the start of <head>:
            // 1. Theme blocker (prevents HA from persisting theme to user data)
            // 2. Parent bridge (enables evalInHaIframe for theme sync via postMessage)
            // 3. Kiosk CSS (hides sidebar/tabs/search/assistant)
            val scripts = "<script>$themeBlockerScript</script><script>$parentBridgeScript</script><script>$kioskScript</script>"
            val modifiedHtml = if (html.contains("<head>", ignoreCase = true)) {
                html.replaceFirst("<head>", "<head>$scripts", ignoreCase = true)
            } else if (html.contains("<html>", ignoreCase = true)) {
                html.replaceFirst("<html>", "<html><head>$scripts</head>", ignoreCase = true)
            } else {
                "$scripts$html"
            }

            Log.i(TAG, "Injected parent bridge + kiosk CSS into HA HTML (${html.length} -> ${modifiedHtml.length} bytes)")
            DiagnosticBuffer.info("KIOSK_UI", "injected scripts into HA HTML (${html.length}->${modifiedHtml.length} bytes) — apply outcome will follow from js:")

            return WebResourceResponse(
                "text/html",
                "UTF-8",
                java.io.ByteArrayInputStream(modifiedHtml.toByteArray(Charsets.UTF_8))
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to intercept HA HTML: ${e.message}")
            DiagnosticBuffer.warn("KIOSK_UI", "intercept exception: ${e.message} — default load used")
            return null
        }
    }

    /**
     * Build the postMessage bridge script injected into HA HTML.
     * Enables evalInHaIframe() in full/linked mode: the JS parent posts
     * {source:'dashie-parent', type:'eval', script:...} messages which this
     * handler executes inside the HA iframe context. Used for theme sync
     * (settheme CustomEvent), token extraction, and other HA interactions.
     */
    private fun buildParentBridgeScript(): String {
        return """(function(){
window.addEventListener('message',function(e){if(!e.data||e.data.source!=='dashie-parent')return;if(e.data.type==='dispatch-event'){var evt=e.data.detail?new CustomEvent(e.data.event,{detail:e.data.detail}):new CustomEvent(e.data.event);window.dispatchEvent(evt);}else if(e.data.type==='eval'){try{new Function(e.data.script)();}catch(ex){console.error('[Dashie] eval error:',ex);}}});
function reportUrl(){try{parent.postMessage({type:'ha-url-changed',url:location.href},'*');}catch(e){}}
var origPush=history.pushState;history.pushState=function(){origPush.apply(this,arguments);reportUrl();};
var origReplace=history.replaceState;history.replaceState=function(){origReplace.apply(this,arguments);reportUrl();};
window.addEventListener('popstate',function(){reportUrl();});
window.addEventListener('location-changed',function(){reportUrl();});
reportUrl();
console.log('[Dashie] Parent bridge handler installed');
})();"""
    }

    /**
     * Build the kiosk CSS injection script for HA.
     * This script traverses HA's shadow DOM to inject CSS that hides UI elements.
     */
    private fun buildKioskInjectionScript(
        hideSidebar: Boolean,
        hideTabs: Boolean,
        hideSearch: Boolean,
        hideAssistant: Boolean
    ): String {
        return """
            (function() {
                console.log('[Dashie] Kiosk injection script starting...');
                console.log('[Dashie] Settings: sidebar=$hideSidebar, tabs=$hideTabs, search=$hideSearch, assistant=$hideAssistant');
                // Report into the captured native diagnostic (DiagnosticBuffer "KIOSK_UI") so the
                // apply outcome is visible in Send/Download Diagnostics, not just the WebView console.
                // window.DashieNative is present when HA is the main frame (HA-only kiosk mode);
                // in full mode HA is an iframe and this no-ops, which is fine.
                function dashieReport(msg) {
                    try { if (window.DashieNative && window.DashieNative.reportKioskUi) window.DashieNative.reportKioskUi(msg); } catch (e) {}
                }
                dashieReport('script started; flags sidebar=$hideSidebar header=$hideTabs search=$hideSearch assistant=$hideAssistant; bridge=' + !!(window.DashieNative && window.DashieNative.reportKioskUi));

                if (window._dashieKioskApplied) {
                    console.log('[Dashie] Kiosk already applied, skipping');
                    return;
                }
                window._dashieKioskApplied = true;

                const HIDE_SIDEBAR = $hideSidebar;
                const HIDE_TABS = $hideTabs;
                const HIDE_SEARCH = $hideSearch;
                const HIDE_ASSISTANT = $hideAssistant;

                const SIDEBAR_CSS = ':host{--app-drawer-width:0 !important;--mdc-drawer-width:0 !important;width:100% !important;}' +
                    '#drawer{display:none !important;width:0 !important;visibility:hidden !important;}' +
                    '.mdc-drawer{display:none !important;width:0 !important;}' +
                    'aside{display:none !important;}' +
                    '.mdc-drawer-app-content{margin-left:0 !important;width:100% !important;}';

                const HEADER_CSS = '#view{min-height:100vh !important;padding-top:0 !important;--header-height:0 !important;}' +
                    'app-header{display:none !important;height:0 !important;visibility:hidden !important;}' +
                    '.header{display:none !important;}' +
                    'app-toolbar{display:none !important;}';

                // Uses --dashie-header-width CSS variable (defaults to 100vw) so zoom can override it
                const MENU_BUTTON_CSS = 'ha-menu-button{display:none !important;}' +
                    ':host{--mdc-top-app-bar-width:var(--dashie-header-width, 100vw) !important;}' +
                    '.mdc-top-app-bar{width:var(--dashie-header-width, 100vw) !important;left:0 !important;}' +
                    'app-header{width:var(--dashie-header-width, 100vw) !important;left:0 !important;}' +
                    '.toolbar{width:100% !important;margin-left:0 !important;padding-left:16px !important;}';

                const SEARCH_CSS = 'ha-icon-button[slot="actionItems"]:first-of-type{display:none !important;visibility:hidden !important;width:0 !important;height:0 !important;overflow:hidden !important;opacity:0 !important;pointer-events:none !important;}';

                const ASSISTANT_CSS = 'ha-icon-button[slot="actionItems"]:nth-of-type(2){display:none !important;visibility:hidden !important;width:0 !important;height:0 !important;overflow:hidden !important;opacity:0 !important;pointer-events:none !important;}';

                // Returns: 'injected' (new), 'exists' (already there), or false (failed)
                function injectStyle(elem, css, id) {
                    if (!elem || !css) return false;
                    const styleId = 'dashie-kiosk-' + id;
                    if (elem.querySelector('#' + styleId)) {
                        return 'exists'; // Already injected, skip silently
                    }

                    const style = document.createElement('style');
                    style.id = styleId;
                    style.textContent = css;
                    elem.appendChild(style);
                    console.log('[Dashie] ✓ Injected ' + id + ' CSS');
                    return 'injected';
                }

                function removeStyle(elem, id) {
                    if (!elem) return false;
                    const styleId = 'dashie-kiosk-' + id;
                    const existing = elem.querySelector('#' + styleId);
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

                function applyKioskStyles() {
                    try {
                        const ha = document.querySelector('home-assistant');
                        if (!ha || !ha.shadowRoot) return false;

                        const main = ha.shadowRoot.querySelector('home-assistant-main');
                        if (!main || !main.shadowRoot) return false;

                        const drawer = main.shadowRoot.querySelector('ha-drawer');
                        if (drawer && drawer.shadowRoot && HIDE_SIDEBAR) {
                            injectStyle(drawer.shadowRoot, SIDEBAR_CSS, 'sidebar');
                        }

                        const partialPanel = main.shadowRoot.querySelector('partial-panel-resolver');
                        if (!partialPanel) return false;

                        const lovelace = partialPanel.querySelector('ha-panel-lovelace');
                        if (!lovelace || !lovelace.shadowRoot) return false;

                        const huiRoot = lovelace.shadowRoot.querySelector('hui-root');
                        if (!huiRoot || !huiRoot.shadowRoot) return false;

                        // All shadow DOM elements found - inject CSS
                        let anyNewInjections = false;

                        if (HIDE_TABS) {
                            if (isSubview(huiRoot.shadowRoot)) {
                                removeStyle(huiRoot.shadowRoot, 'header');
                            } else {
                                if (injectStyle(huiRoot.shadowRoot, HEADER_CSS, 'header') === 'injected') anyNewInjections = true;
                            }
                        }
                        if (HIDE_SIDEBAR) {
                            if (injectStyle(huiRoot.shadowRoot, MENU_BUTTON_CSS, 'menubutton') === 'injected') anyNewInjections = true;

                            // Also apply zoom-based header width fix if zoom is not 100%
                            var currentZoom = parseFloat(document.documentElement.style.zoom) || 1;
                            if (currentZoom !== 1 && currentZoom > 0) {
                                var headerWidth = 100 / currentZoom;
                                var zoomFixStyle = huiRoot.shadowRoot.querySelector('#dashie-zoom-fix');
                                if (!zoomFixStyle) {
                                    zoomFixStyle = document.createElement('style');
                                    zoomFixStyle.id = 'dashie-zoom-fix';
                                    huiRoot.shadowRoot.appendChild(zoomFixStyle);
                                    console.log('[Dashie] Zoom-based header width: ' + headerWidth + 'vw');
                                }
                                zoomFixStyle.textContent = ':host{--dashie-header-width:' + headerWidth + 'vw !important;--mdc-top-app-bar-width:' + headerWidth + 'vw !important;}' +
                                    '.mdc-top-app-bar{width:' + headerWidth + 'vw !important;}' +
                                    'app-header{width:' + headerWidth + 'vw !important;}';
                            }
                        }
                        if (HIDE_SEARCH) {
                            if (injectStyle(huiRoot.shadowRoot, SEARCH_CSS, 'search') === 'injected') anyNewInjections = true;
                        }
                        if (HIDE_ASSISTANT) {
                            if (injectStyle(huiRoot.shadowRoot, ASSISTANT_CSS, 'assistant') === 'injected') anyNewInjections = true;
                        }

                        // Only log success when new styles were actually injected
                        if (anyNewInjections) {
                            console.log('[Dashie] ✓ Kiosk styles applied');
                        }
                        return true;
                    } catch (e) {
                        console.log('[Dashie] Kiosk style injection error: ' + e.message);
                        return false;
                    }
                }

                let success = applyKioskStyles();
                let reportedSuccess = success;
                if (success) dashieReport('apply succeeded immediately');
                let retries = 0;
                const maxRetries = 60;
                const interval = setInterval(function() {
                    if (applyKioskStyles()) {
                        success = true;
                        if (!reportedSuccess) { reportedSuccess = true; dashieReport('apply succeeded at attempt ' + retries); }
                    }
                    retries++;
                    if (retries >= maxRetries) {
                        clearInterval(interval);
                        console.log('[Dashie] Kiosk injection complete after ' + retries + ' attempts, success=' + success);
                        dashieReport('apply loop done attempts=' + retries + ' success=' + success);
                    }
                }, 500);

                function startObserving() {
                    const ha = document.querySelector('home-assistant');
                    if (ha && ha.shadowRoot) {
                        const observer = new MutationObserver(function() { applyKioskStyles(); });
                        observer.observe(ha.shadowRoot, { childList: true, subtree: true });
                        console.log('[Dashie] Started MutationObserver on home-assistant shadowRoot');
                    } else {
                        setTimeout(startObserving, 500);
                    }
                }
                setTimeout(startObserving, 2000);

                // Watch for navigation changes — delay to let HA update DOM before subview check
                window.addEventListener('location-changed', function() {
                    window._dashieKioskApplied = false;
                    applyKioskStyles();
                    window._dashieKioskApplied = true;
                    setTimeout(function() { applyKioskStyles(); }, 150);
                });
                window.addEventListener('popstate', function() {
                    applyKioskStyles();
                    setTimeout(function() { applyKioskStyles(); }, 150);
                });

                // MutationObserver on hui-root.shadowRoot to detect subview toolbar changes
                if (HIDE_TABS) {
                    function startSubviewObserver() {
                        try {
                            const ha = document.querySelector('home-assistant');
                            if (!ha || !ha.shadowRoot) { setTimeout(startSubviewObserver, 1000); return; }
                            const main = ha.shadowRoot.querySelector('home-assistant-main');
                            if (!main || !main.shadowRoot) { setTimeout(startSubviewObserver, 1000); return; }
                            const pp = main.shadowRoot.querySelector('partial-panel-resolver');
                            if (!pp) { setTimeout(startSubviewObserver, 1000); return; }
                            const lv = pp.querySelector('ha-panel-lovelace');
                            if (!lv || !lv.shadowRoot) { setTimeout(startSubviewObserver, 1000); return; }
                            const hr = lv.shadowRoot.querySelector('hui-root');
                            if (!hr || !hr.shadowRoot) { setTimeout(startSubviewObserver, 1000); return; }

                            const subviewObserver = new MutationObserver(function() {
                                applyKioskStyles();
                            });
                            subviewObserver.observe(hr.shadowRoot, { childList: true, subtree: true });
                            console.log('[Dashie] Started subview observer on hui-root shadowRoot');
                        } catch(e) {
                            setTimeout(startSubviewObserver, 1000);
                        }
                    }
                    setTimeout(startSubviewObserver, 3000);
                }
            })();
        """.trimIndent()
    }
}

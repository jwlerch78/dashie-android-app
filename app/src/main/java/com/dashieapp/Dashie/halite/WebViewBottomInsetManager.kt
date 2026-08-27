package com.dashieapp.Dashie.halite

import android.content.Context
import android.util.Log
import android.webkit.WebView

/**
 * Manages bottom padding on the WebView so native overlay bars
 * (music player mini bar, future notification bars, etc.) don't permanently
 * obscure dashboard content. Each overlay registers a named inset in dp;
 * the manager injects extra scroll padding into the HA dashboard via
 * the kiosk shell's evalInHaIframe bridge, letting the user scroll past
 * the covered area without shrinking the visible viewport.
 *
 * Usage:
 *   insetManager.setInset("music_player", 56)   // mini player visible
 *   insetManager.removeInset("music_player")     // player hidden
 */
class WebViewBottomInsetManager(
    private val context: Context,
    private val webViewProvider: () -> WebView
) {
    companion object {
        private const val TAG = "WebViewBottomInset"
    }

    private val webView get() = webViewProvider()

    /** Active insets: name → height in dp */
    private val insets = mutableMapOf<String, Int>()

    /** Current total applied to the WebView (px), to avoid redundant JS calls */
    private var appliedPx = 0

    /**
     * Register or update a named bottom inset.
     * @param name  Unique key (e.g. "music_player")
     * @param dp    Height in dp that this overlay occupies at the bottom
     */
    fun setInset(name: String, dp: Int) {
        if (dp <= 0) {
            removeInset(name)
            return
        }
        val prev = insets[name]
        if (prev == dp) return
        insets[name] = dp
        Log.i(TAG, "setInset($name, ${dp}dp) — active: $insets")
        applyTotal()
    }

    /**
     * Remove a named bottom inset (overlay hidden).
     */
    fun removeInset(name: String) {
        if (insets.remove(name) == null) return
        Log.i(TAG, "removeInset($name) — active: $insets")
        applyTotal()
    }

    /** Remove all insets (e.g. on WebView recreation). */
    fun clear() {
        insets.clear()
        appliedPx = 0
        applyTotal()
    }

    private fun applyTotal() {
        val totalDp = insets.values.sum()
        if (totalDp == appliedPx) return  // reusing field to track applied value
        appliedPx = totalDp

        // Pass dp directly as CSS px — the WebView/iframe CSS pixels are already
        // device-independent, so dp ≈ CSS px. Converting to native px would overshoot.
        Log.i(TAG, "Applying bottom inset: ${totalDp}dp (as ${totalDp}css-px)")
        webView.post {
            webView.evaluateJavascript(buildInsetJs(totalDp), null)
        }
    }

    /**
     * Build JS that injects scroll padding into the HA dashboard.
     *
     * For kiosk shell pages: uses `evalInHaIframe()` to inject a style element
     * into the HA shadow DOM (hui-root > shadowRoot > view containers), adding
     * padding-bottom so the user can scroll past the overlay bar.
     *
     * For direct HA pages or Dashie webapp: injects directly into shadow DOM / body.
     */
    private fun buildInsetJs(px: Int): String {
        // The script that runs INSIDE the HA iframe (via evalInHaIframe or directly)
        val haInjectScript = buildHaInjectScript(px)
        // Escape for embedding inside a JS string
        val escapedScript = haInjectScript
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")

        return """
        (function() {
            var TAG = '[DashieBottomInset]';
            var px = $px;
            console.log(TAG + ' applying ' + px + 'px bottom inset');

            // Strategy 1: Kiosk shell — inject into HA iframe via evalInHaIframe bridge
            if (typeof window.evalInHaIframe === 'function') {
                console.log(TAG + ' using evalInHaIframe bridge');
                window.evalInHaIframe('$escapedScript');
                return;
            }

            // Strategy 2: Direct HA page — inject directly
            var ha = document.querySelector('home-assistant');
            if (ha) {
                console.log(TAG + ' direct HA page detected');
                eval('$escapedScript');
                return;
            }

            // Strategy 3: Dashie webapp — body padding
            document.body.style.paddingBottom = px > 0 ? px + 'px' : '';
            console.log(TAG + ' body padding-bottom=' + (px > 0 ? px + 'px' : 'reset'));
        })();
        """.trimIndent()
    }

    /**
     * Script that runs inside the HA context (either directly or via iframe bridge).
     * Traverses shadow DOM to find scrollable view containers and adds padding-bottom.
     */
    private fun buildHaInjectScript(px: Int): String {
        return """
        (function() {
            var TAG = '[DashieBottomInset-HA]';
            var px = $px;
            var styleId = 'dashie-bottom-inset';

            function injectStyle(root, label) {
                if (!root) return;
                var existing = root.querySelector('#' + styleId);
                if (px > 0) {
                    if (!existing) {
                        existing = document.createElement('style');
                        existing.id = styleId;
                        root.appendChild(existing);
                    }
                    existing.textContent = ':host { padding-bottom: ' + px + 'px !important; }';
                    console.log(TAG + ' ' + label + ': padding=' + px + 'px');
                } else if (existing) {
                    existing.remove();
                    console.log(TAG + ' ' + label + ': removed');
                }
            }

            try {
                var ha = document.querySelector('home-assistant');
                if (!ha || !ha.shadowRoot) { console.log(TAG + ' home-assistant not ready'); return; }
                var main = ha.shadowRoot.querySelector('home-assistant-main');
                if (!main || !main.shadowRoot) { console.log(TAG + ' main not ready'); return; }
                var pp = main.shadowRoot.querySelector('partial-panel-resolver');
                if (!pp) { console.log(TAG + ' partial-panel-resolver not found'); return; }
                var lv = pp.querySelector('ha-panel-lovelace');
                if (!lv || !lv.shadowRoot) { console.log(TAG + ' lovelace not found'); return; }
                var hr = lv.shadowRoot.querySelector('hui-root');
                if (!hr || !hr.shadowRoot) { console.log(TAG + ' hui-root not found'); return; }

                // Inject into view containers (the scrollable elements)
                var views = hr.shadowRoot.querySelectorAll(
                    'hui-view, hui-panel-view, hui-masonry-view, hui-sections-view');
                console.log(TAG + ' found ' + views.length + ' view containers');
                views.forEach(function(v, i) {
                    if (v.shadowRoot) {
                        injectStyle(v.shadowRoot, 'view[' + i + '].shadowRoot');
                    }
                    // Also apply inline padding on the view element itself
                    v.style.paddingBottom = px > 0 ? px + 'px' : '';
                });

                // Also inject into hui-root's shadow root as fallback
                injectStyle(hr.shadowRoot, 'hui-root.shadowRoot');
                console.log(TAG + ' done');
            } catch(e) {
                console.error(TAG + ' error:', e);
            }
        })();
        """.trimIndent()
    }

    @Suppress("unused")
    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}

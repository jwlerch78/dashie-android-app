package com.dashieapp.Dashie.webview

import android.util.Log
import android.webkit.WebView

/**
 * WebView JavaScript Injection Utilities
 *
 * General-purpose JS injection methods for WebView pages.
 * Used primarily by Halite (Dashie Lite) for Fire TV/tablet compatibility.
 *
 * Responsibilities:
 * - D-pad navigation JS injection for HA login pages
 * - Viewport diagnostics for debugging layout issues
 *
 * Domain-specific injectors are in the `injectors/` package:
 * - MusicPlayerJsInjector: Music player state subscription, playback, entity query
 * - HaUiHidingInjector: Shadow DOM CSS for sidebar/header/search hiding
 * - DeviceOptimizationInjector: Fire tablet animation fix, video controls hiding
 *
 * See: .reference/20260107_Android_Refactor.md
 */
object WebViewJsInjector {

    private const val TAG = "WebViewJsInjector"

    /**
     * Inject D-pad navigation support for WebView pages (like HA login).
     * This enables arrow key navigation between form fields and buttons.
     * Called when the WebView is showing a page that needs D-pad form navigation.
     *
     * Features:
     * - Finds all focusable elements (inputs, buttons, links, etc.)
     * - Adds orange focus ring styling (Dashie brand color)
     * - Arrow keys navigate between elements
     * - Enter key clicks buttons/links
     * - Auto-focuses first element after injection
     *
     * @param webView The WebView to inject the navigation support into
     */
    fun injectDpadNavigation(webView: WebView?) {
        webView ?: return

        val js = """
            (function() {
                if (window._dashieDpadNavApplied) return;
                window._dashieDpadNavApplied = true;

                // Find all focusable elements
                function getFocusableElements() {
                    return Array.from(document.querySelectorAll(
                        'input:not([type="hidden"]):not([disabled]), ' +
                        'textarea:not([disabled]), ' +
                        'select:not([disabled]), ' +
                        'button:not([disabled]), ' +
                        'a[href], ' +
                        '[tabindex]:not([tabindex="-1"])'
                    )).filter(el => {
                        const style = window.getComputedStyle(el);
                        return style.display !== 'none' &&
                               style.visibility !== 'hidden' &&
                               el.offsetParent !== null;
                    });
                }

                // Style for focused elements
                const style = document.createElement('style');
                style.textContent = `
                    .dashie-dpad-focus {
                        outline: 3px solid #FF9500 !important;
                        outline-offset: 2px !important;
                        box-shadow: 0 0 8px rgba(255, 149, 0, 0.6) !important;
                    }
                `;
                document.head.appendChild(style);

                // Current focus index
                let currentIndex = -1;

                // Update visual focus
                function updateFocus(elements, index) {
                    elements.forEach(el => el.classList.remove('dashie-dpad-focus'));
                    if (index >= 0 && index < elements.length) {
                        const el = elements[index];
                        el.classList.add('dashie-dpad-focus');
                        el.focus();
                        el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    }
                }

                // Handle keydown for D-pad navigation
                document.addEventListener('keydown', function(e) {
                    const elements = getFocusableElements();
                    if (elements.length === 0) return;

                    // Find current focused element index
                    const activeEl = document.activeElement;
                    currentIndex = elements.indexOf(activeEl);
                    if (currentIndex === -1) currentIndex = 0;

                    let handled = false;

                    switch(e.key) {
                        case 'ArrowDown':
                        case 'ArrowRight':
                            currentIndex = (currentIndex + 1) % elements.length;
                            handled = true;
                            break;
                        case 'ArrowUp':
                        case 'ArrowLeft':
                            currentIndex = currentIndex <= 0 ? elements.length - 1 : currentIndex - 1;
                            handled = true;
                            break;
                        case 'Enter':
                            // For buttons and links, click them
                            if (activeEl && (activeEl.tagName === 'BUTTON' || activeEl.tagName === 'A')) {
                                activeEl.click();
                                handled = true;
                            }
                            break;
                    }

                    if (handled) {
                        e.preventDefault();
                        e.stopPropagation();
                        updateFocus(elements, currentIndex);
                    }
                }, true);

                // Initialize focus on first element after a short delay
                // On TV devices, we need focus for D-pad navigation to work
                // The keyboard hiding in DashieWebViewClient.onPageFinished will hide any
                // keyboard that appears from this auto-focus
                setTimeout(function() {
                    const elements = getFocusableElements();
                    if (elements.length > 0) {
                        currentIndex = 0;
                        updateFocus(elements, currentIndex);
                    }
                }, 500);

                console.log('[DashieLite] D-pad navigation support injected');
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
        Log.i(TAG, "Halite: Injected D-pad navigation support for login page")
    }

    /**
     * Inject diagnostic script to log viewport and layout information.
     * Useful for debugging column layout issues on different devices.
     * Logs to both console (visible in WebView debug) and returns via callback.
     *
     * @param webView The WebView to inject the diagnostic into
     * @param callback Optional callback to receive the diagnostic JSON string
     */
    fun injectViewportDiagnostics(webView: WebView?, callback: ((String) -> Unit)? = null) {
        webView ?: return

        val js = """
            (function() {
                var info = {
                    // Screen info
                    screenWidth: screen.width,
                    screenHeight: screen.height,
                    screenAvailWidth: screen.availWidth,
                    screenAvailHeight: screen.availHeight,

                    // Window/viewport info
                    innerWidth: window.innerWidth,
                    innerHeight: window.innerHeight,
                    outerWidth: window.outerWidth,
                    outerHeight: window.outerHeight,

                    // Document info
                    documentWidth: document.documentElement.clientWidth,
                    documentHeight: document.documentElement.clientHeight,
                    bodyWidth: document.body ? document.body.clientWidth : 'N/A',
                    bodyHeight: document.body ? document.body.clientHeight : 'N/A',

                    // Device pixel ratio
                    devicePixelRatio: window.devicePixelRatio,

                    // Computed styles on html element
                    htmlZoom: getComputedStyle(document.documentElement).zoom || '1',
                    htmlTransform: getComputedStyle(document.documentElement).transform,

                    // Check some common breakpoints
                    matchesMax600: window.matchMedia('(max-width: 600px)').matches,
                    matchesMax768: window.matchMedia('(max-width: 768px)').matches,
                    matchesMax1024: window.matchMedia('(max-width: 1024px)').matches,
                    matchesMax1280: window.matchMedia('(max-width: 1280px)').matches,

                    // HA-specific: check for sidebar width variable
                    mdcDrawerWidth: getComputedStyle(document.documentElement).getPropertyValue('--mdc-drawer-width') || 'not set',
                    appDrawerWidth: getComputedStyle(document.documentElement).getPropertyValue('--app-drawer-width') || 'not set',

                    // HA container widths (traverse shadow DOM)
                    haContainerInfo: (function() {
                        try {
                            var ha = document.querySelector('home-assistant');
                            if (!ha || !ha.shadowRoot) return 'home-assistant not found';

                            var main = ha.shadowRoot.querySelector('home-assistant-main');
                            if (!main || !main.shadowRoot) return 'home-assistant-main not found';

                            var panel = main.shadowRoot.querySelector('ha-panel-lovelace');
                            if (!panel || !panel.shadowRoot) return 'ha-panel-lovelace not found';

                            var huiRoot = panel.shadowRoot.querySelector('hui-root');
                            if (!huiRoot || !huiRoot.shadowRoot) return 'hui-root not found';

                            var view = huiRoot.shadowRoot.querySelector('hui-view');
                            if (!view || !view.shadowRoot) {
                                // Try sections view
                                view = huiRoot.shadowRoot.querySelector('hui-sections-view');
                            }
                            if (!view) return 'hui-view/hui-sections-view not found';

                            var viewType = view.tagName.toLowerCase();
                            var viewWidth = view.clientWidth || view.offsetWidth;

                            // Check for sections container
                            var sectionsContainer = view.shadowRoot ? view.shadowRoot.querySelector('.container') : null;
                            var containerWidth = sectionsContainer ? sectionsContainer.clientWidth : 'N/A';

                            // Count actual columns rendered
                            var sections = view.shadowRoot ? view.shadowRoot.querySelectorAll('hui-section, .section') : [];

                            return {
                                viewType: viewType,
                                viewWidth: viewWidth,
                                containerWidth: containerWidth,
                                sectionCount: sections.length
                            };
                        } catch(e) {
                            return 'Error: ' + e.message;
                        }
                    })(),

                    // User agent
                    userAgent: navigator.userAgent
                };

                var json = JSON.stringify(info, null, 2);
                console.log('[DashieLite] Viewport Diagnostics:\n' + json);
                return json;
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            // Result comes back as a JSON string wrapped in quotes, need to unescape
            val unescaped = result?.trim('"')?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: "null"
            Log.i(TAG, "Viewport Diagnostics: $unescaped")
            callback?.invoke(unescaped)
        }
    }
}

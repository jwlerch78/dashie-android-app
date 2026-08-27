package com.dashieapp.Dashie.webview.injectors

import android.util.Log
import android.webkit.WebView

/**
 * Injects CSS to hide Home Assistant UI elements (sidebar, header, search, assistant).
 *
 * This is an alternative to using URL parameters (?hide_sidebar, ?hide_header) which require
 * HACS and the kiosk-mode addon. This approach injects CSS directly into the page and
 * shadow DOM elements to hide them without requiring any HA add-ons.
 *
 * Home Assistant uses Shadow DOM extensively, so we need to:
 * 1. Inject CSS into the main document
 * 2. Find and inject CSS into nested shadow roots (ha-panel-lovelace, hui-root, etc.)
 *
 * The injection runs periodically to catch dynamically loaded components.
 *
 * Note: Search and Assistant buttons are ALWAYS hidden since they're not useful
 * on remote dashboards and would just take up header space.
 *
 * Extracted from WebViewJsInjector.
 */
object HaUiHidingInjector {

    private const val TAG = "HaUiHidingInjector"

    /**
     * Inject CSS to hide Home Assistant UI elements (sidebar, header, search, assistant).
     *
     * @param webView The WebView to inject the CSS into
     * @param hideSidebar If true, hide the sidebar
     * @param hideTabs If true, hide the header/tabs
     * @param zoomPercent Dashboard zoom percentage (50-200). Used to calculate header width fix.
     *                    When zoom < 100%, header needs width > 100vw to fill visible area.
     */
    fun injectHaUiHidingCss(
        webView: WebView?,
        hideSidebar: Boolean,
        hideTabs: Boolean,
        hideSearch: Boolean = true,
        hideAssistant: Boolean = true,
        zoomPercent: Int = 100
    ) {
        webView ?: return
        if (!hideSidebar && !hideTabs && !hideSearch && !hideAssistant) return

        // JavaScript to inject CSS into shadow DOM - inspired by kiosk-mode approach
        val js = """
            (function() {
                console.log('[DashieLite] Kiosk injection script starting...');
                console.log('[DashieLite] Settings: sidebar=$hideSidebar, tabs=$hideTabs, search=$hideSearch, assistant=$hideAssistant');

                if (window._dashieKioskApplied) {
                    console.log('[DashieLite] Kiosk already applied, skipping');
                    return;
                }
                window._dashieKioskApplied = true;

                const HIDE_SIDEBAR = $hideSidebar;
                const HIDE_TABS = $hideTabs;
                const HIDE_SEARCH = $hideSearch;
                const HIDE_ASSISTANT = $hideAssistant;

                // CSS for sidebar (injected into ha-drawer's shadow root)
                // Use multiple selectors and high specificity to override any existing styles
                // We set --mdc-drawer-width to 0 because Bubble Card uses this variable to calculate
                // popup centering: left: calc(var(--mdc-drawer-width, 0px) / 2 + 50% - ...)
                // Without setting it to 0, popups are off-center when sidebar is hidden.
                const SIDEBAR_CSS = ':host{--app-drawer-width:0 !important;--mdc-drawer-width:0 !important;}' +
                    '#drawer{display:none !important;width:0 !important;visibility:hidden !important;position:absolute !important;}' +
                    '.mdc-drawer{display:none !important;width:0 !important;position:absolute !important;}' +
                    'aside{display:none !important;position:absolute !important;}' +
                    '.mdc-drawer-app-content{margin-left:0 !important;}';

                // CSS to set --mdc-drawer-width globally (for Bubble Card and other popups)
                // Bubble Card reads this variable to calculate popup centering.
                // Use * selector to ensure the variable is available everywhere, including
                // dynamically created elements and shadow DOM boundaries.
                // Also directly override Bubble Card's left positioning to force centering.
                const GLOBAL_SIDEBAR_CSS = '*{--mdc-drawer-width:0px !important;--app-drawer-width:0px !important;}' +
                    ':root, html, body, home-assistant{--mdc-drawer-width:0px !important;--app-drawer-width:0px !important;}' +
                    // Fix Bubble Card popup centering when sidebar is hidden
                    // Also target specific Bubble Card classes
                    '.bubble-pop-up, .bubble-pop-up-container, .bubble-backdrop, [class*="bubble"]{--mdc-drawer-width:0px !important;--app-drawer-width:0px !important;}' +
                    // Direct override for Bubble Card popup positioning - force center
                    // Bubble Card calculates: left: calc(var(--mdc-drawer-width) / 2 + 50% - width/2)
                    // With sidebar hidden, we want: left: 50% with transform: translateX(-50%)
                    '.bubble-pop-up{left:50% !important;transform:translateX(-50%) !important;}';

                // CSS for header/tabs (injected into hui-root's shadow root)
                const HEADER_CSS = '#view{min-height:100vh !important;padding-top:0 !important;--header-height:0 !important;}' +
                    'app-header{display:none !important;height:0 !important;visibility:hidden !important;}' +
                    '.header{display:none !important;}' +
                    'app-toolbar{display:none !important;}';

                // CSS for menu button AND fix header layout when sidebar hidden (injected into hui-root's shadow root)
                // The --mdc-top-app-bar-width fix prevents the header from leaving white space on the left
                // Uses --dashie-header-width CSS variable (defaults to 100vw) so zoom can override it
                // When CSS zoom < 100%, header needs width > 100vw to fill visible area
                const MENU_BUTTON_CSS = 'ha-menu-button{display:none !important;}' +
                    ':host{--mdc-top-app-bar-width:var(--dashie-header-width, 100vw) !important;}' +
                    '.mdc-top-app-bar{width:var(--dashie-header-width, 100vw) !important;left:0 !important;}' +
                    'app-header{width:var(--dashie-header-width, 100vw) !important;left:0 !important;}' +
                    '.toolbar{width:100% !important;margin-left:0 !important;padding-left:16px !important;}';

                // CSS for search - hide search button in toolbar
                // The buttons are in .toolbar > DIV, have slot="actionItems", and search is typically first
                // Target by slot and position since aria-label is null
                // Use multiple selectors and high specificity to ensure they work
                const SEARCH_CSS = 'ha-icon-button[slot="actionItems"]:first-of-type{display:none !important;visibility:hidden !important;width:0 !important;height:0 !important;overflow:hidden !important;opacity:0 !important;pointer-events:none !important;}';

                // CSS for assistant - hide assistant button in toolbar
                // Assistant is typically the second button with slot="actionItems"
                const ASSISTANT_CSS = 'ha-icon-button[slot="actionItems"]:nth-of-type(2){display:none !important;visibility:hidden !important;width:0 !important;height:0 !important;overflow:hidden !important;opacity:0 !important;pointer-events:none !important;}';

                // Helper to inject style into an element (works with shadow roots)
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
                    console.log('[DashieLite] ✓ Injected ' + id + ' CSS');
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

                // Track which "waiting" messages we've already logged (avoid spam)
                const _waitLogged = {};
                function logWaitOnce(msg) {
                    if (!_waitLogged[msg]) {
                        _waitLogged[msg] = true;
                        console.log('[DashieLite] Waiting: ' + msg);
                    }
                }

                // Main injection function - traverses HA's shadow DOM
                function applyKioskStyles() {
                    try {
                        // Inject global CSS into document head first (for Bubble Card popup centering)
                        // This must be in the main document, not a shadow root, for custom cards to read it
                        if (HIDE_SIDEBAR) {
                            injectStyle(document.head, GLOBAL_SIDEBAR_CSS, 'global-sidebar');
                        }

                        // Get home-assistant element
                        const ha = document.querySelector('home-assistant');
                        if (!ha) {
                            logWaitOnce('home-assistant not found');
                            return false;
                        }
                        if (!ha.shadowRoot) {
                            logWaitOnce('home-assistant shadowRoot not ready');
                            return false;
                        }

                        // Get home-assistant-main
                        const main = ha.shadowRoot.querySelector('home-assistant-main');
                        if (!main) {
                            logWaitOnce('home-assistant-main not found');
                            return false;
                        }
                        if (!main.shadowRoot) {
                            logWaitOnce('home-assistant-main shadowRoot not ready');
                            return false;
                        }

                        // Get ha-drawer (contains sidebar)
                        const drawer = main.shadowRoot.querySelector('ha-drawer');
                        if (drawer && drawer.shadowRoot && HIDE_SIDEBAR) {
                            injectStyle(drawer.shadowRoot, SIDEBAR_CSS, 'sidebar');
                        } else if (HIDE_SIDEBAR && !drawer) {
                            logWaitOnce('ha-drawer not found');
                        }

                        // Get partial-panel-resolver
                        const partialPanel = main.shadowRoot.querySelector('partial-panel-resolver');
                        if (!partialPanel) {
                            logWaitOnce('partial-panel-resolver not found');
                            return false;
                        }

                        // Get ha-panel-lovelace (the dashboard)
                        const lovelace = partialPanel.querySelector('ha-panel-lovelace');
                        if (!lovelace) {
                            logWaitOnce('ha-panel-lovelace not found (may not be on dashboard)');
                            return false;
                        }
                        if (!lovelace.shadowRoot) {
                            logWaitOnce('ha-panel-lovelace shadowRoot not ready');
                            return false;
                        }

                        // Get hui-root (main lovelace container)
                        const huiRoot = lovelace.shadowRoot.querySelector('hui-root');
                        if (!huiRoot) {
                            logWaitOnce('hui-root not found');
                            return false;
                        }
                        if (!huiRoot.shadowRoot) {
                            logWaitOnce('hui-root shadowRoot not ready');
                            return false;
                        }

                        // All shadow DOM elements found - inject CSS
                        let anyNewInjections = false;

                        // Inject or remove header CSS based on subview state
                        if (HIDE_TABS) {
                            if (isSubview(huiRoot.shadowRoot)) {
                                removeStyle(huiRoot.shadowRoot, 'header');
                            } else {
                                if (injectStyle(huiRoot.shadowRoot, HEADER_CSS, 'header') === 'injected') anyNewInjections = true;
                            }
                        }

                        // Inject menu button CSS (hide when sidebar is hidden)
                        if (HIDE_SIDEBAR) {
                            if (injectStyle(huiRoot.shadowRoot, MENU_BUTTON_CSS, 'menubutton') === 'injected') anyNewInjections = true;

                            // Apply zoom-based header width fix if zoom is not 100%
                            // Use the zoom value passed from native preferences, not document.style.zoom,
                            // because this CSS injection may run before zoom is applied to the document
                            var zoomPercent = $zoomPercent;
                            if (zoomPercent !== 100 && zoomPercent > 0) {
                                var zoomFactor = zoomPercent / 100;
                                var headerWidth = 100 / zoomFactor;
                                var zoomFixStyle = huiRoot.shadowRoot.querySelector('#dashie-zoom-fix');
                                if (!zoomFixStyle) {
                                    zoomFixStyle = document.createElement('style');
                                    zoomFixStyle.id = 'dashie-zoom-fix';
                                    huiRoot.shadowRoot.appendChild(zoomFixStyle);
                                }
                                zoomFixStyle.textContent = ':host{--dashie-header-width:' + headerWidth + 'vw !important;--mdc-top-app-bar-width:' + headerWidth + 'vw !important;}' +
                                    '.mdc-top-app-bar{width:' + headerWidth + 'vw !important;}' +
                                    'app-header{width:' + headerWidth + 'vw !important;}';
                                console.log('[DashieLite] Zoom-based header width applied: ' + headerWidth + 'vw (zoomPercent=' + zoomPercent + ')');
                            }
                        }

                        // Inject search CSS - hide search button in toolbar
                        if (HIDE_SEARCH) {
                            if (injectStyle(huiRoot.shadowRoot, SEARCH_CSS, 'search') === 'injected') anyNewInjections = true;
                        }

                        // Inject assistant CSS - hide assistant button in toolbar
                        if (HIDE_ASSISTANT) {
                            if (injectStyle(huiRoot.shadowRoot, ASSISTANT_CSS, 'assistant') === 'injected') anyNewInjections = true;
                        }

                        // Find and fix Bubble Card popups - they read --mdc-drawer-width from their own shadow root
                        // We need to inject our CSS variable override into every shadow root that might contain a popup
                        if (HIDE_SIDEBAR) {
                            // Inject into hui-root shadowRoot (where card views are)
                            if (injectStyle(huiRoot.shadowRoot, GLOBAL_SIDEBAR_CSS, 'global-sidebar-huiroot') === 'injected') anyNewInjections = true;

                            // Find all card containers and inject into their shadow roots
                            const cardContainers = huiRoot.shadowRoot.querySelectorAll('hui-view, hui-panel-view, hui-masonry-view, hui-sections-view');
                            cardContainers.forEach(function(container, i) {
                                if (container.shadowRoot) {
                                    if (injectStyle(container.shadowRoot, GLOBAL_SIDEBAR_CSS, 'global-sidebar-view' + i) === 'injected') anyNewInjections = true;
                                }
                            });

                            // Find bubble-card elements and inject into their shadow roots
                            function findAndFixBubbleCards(root) {
                                if (!root) return;
                                const bubbleCards = root.querySelectorAll('bubble-card, bubble-pop-up, [class*="bubble"]');
                                bubbleCards.forEach(function(card, i) {
                                    if (card.shadowRoot) {
                                        if (injectStyle(card.shadowRoot, GLOBAL_SIDEBAR_CSS, 'global-sidebar-bubble' + i) === 'injected') anyNewInjections = true;
                                    }
                                });
                            }

                            findAndFixBubbleCards(huiRoot.shadowRoot);
                            cardContainers.forEach(function(container) {
                                if (container.shadowRoot) {
                                    findAndFixBubbleCards(container.shadowRoot);
                                }
                            });
                        }

                        // Only log success when new styles were actually injected
                        if (anyNewInjections) {
                            console.log('[DashieLite] ✓ Kiosk styles applied');
                        }
                        return true;
                    } catch (e) {
                        console.log('[DashieLite] Kiosk style injection error: ' + e.message);
                        return false;
                    }
                }

                // Run immediately and then periodically to catch dynamic loading
                let success = applyKioskStyles();

                // Retry a few times as HA loads components dynamically
                // Use a longer interval and more retries to ensure we run after kiosk-mode HACS addon
                let retries = 0;
                const maxRetries = 60;  // Run for 30 seconds (60 * 500ms)
                const interval = setInterval(function() {
                    if (applyKioskStyles()) {
                        success = true;
                    }
                    retries++;
                    if (retries >= maxRetries) {
                        clearInterval(interval);
                        console.log('[DashieLite] Kiosk injection complete after ' + retries + ' attempts, success=' + success);
                    }
                }, 500);

                // Also use MutationObserver to re-apply when DOM changes (catches kiosk-mode HACS changes)
                const observer = new MutationObserver(function(mutations) {
                    applyKioskStyles();
                });

                // Start observing once we can find home-assistant
                function startObserving() {
                    const ha = document.querySelector('home-assistant');
                    if (ha && ha.shadowRoot) {
                        observer.observe(ha.shadowRoot, { childList: true, subtree: true });
                        console.log('[DashieLite] Started MutationObserver on home-assistant shadowRoot');
                    } else {
                        setTimeout(startObserving, 500);
                    }
                }
                setTimeout(startObserving, 2000);  // Delay to let HA initialize

                // Watch for navigation changes — delay to let HA update DOM before subview check
                window.addEventListener('location-changed', function() {
                    window._dashieKioskApplied = false;
                    applyKioskStyles();
                    window._dashieKioskApplied = true;
                    // Delayed re-check for subview state (DOM may not have updated yet)
                    setTimeout(function() { applyKioskStyles(); }, 150);
                });
                window.addEventListener('popstate', function() {
                    applyKioskStyles();
                    setTimeout(function() { applyKioskStyles(); }, 150);
                });

                // MutationObserver on hui-root.shadowRoot to detect subview toolbar changes
                // (The home-assistant.shadowRoot observer can't see across shadow DOM boundaries)
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
                            console.log('[DashieLite] Started subview observer on hui-root shadowRoot');
                        } catch(e) {
                            setTimeout(startSubviewObserver, 1000);
                        }
                    }
                    setTimeout(startSubviewObserver, 3000);
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
        Log.i(TAG, "Halite: Injected HA kiosk CSS (sidebar=$hideSidebar, tabs=$hideTabs, search=$hideSearch, assistant=$hideAssistant)")
    }
}

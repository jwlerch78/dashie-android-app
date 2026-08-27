package com.dashieapp.Dashie.webview.injectors

import android.util.Log
import android.webkit.WebView

/**
 * Device-specific optimization injections for WebView.
 *
 * Handles CSS/JS injections that fix device-specific issues:
 * - Animation pause fix for Fire tablets (tsParticles keyboard interference)
 * - Video controls hiding for low-bandwidth mode (WebRTC/camera cards)
 *
 * Extracted from WebViewJsInjector.
 */
object DeviceOptimizationInjector {

    private const val TAG = "DeviceOptInjector"

    /**
     * Inject CSS to pause/hide heavy animations that interfere with keyboard input on Fire tablets.
     *
     * Home Assistant's login page uses tsParticles (particles.js) for an animated background.
     * On Fire tablets, this continuous animation causes InputConnection issues, making the
     * keyboard freeze or stop responding. This injects CSS to hide those animations.
     *
     * Targets:
     * - #particles: The tsParticles container on HA login page
     * - canvas: Canvas elements used for particle rendering
     * - Elements with heavy CSS animations
     *
     * See: https://community.home-assistant.io/t/disable-log-in-screen-animation/682651
     *
     * @param webView The WebView to inject the CSS into
     */
    fun injectAnimationPauseFix(webView: WebView?) {
        webView ?: return

        // Simple CSS-only approach - just hide #particles
        // Wait for document.head to be available before injecting
        val js = "(function() {" +
            "if (window._dashieFireTabletFixApplied) return;" +
            "window._dashieFireTabletFixApplied = true;" +
            "function inject() {" +
            "  if (!document.head) { setTimeout(inject, 50); return; }" +
            "  var style = document.createElement('style');" +
            "  style.id = 'dashie-fire-tablet-fix';" +
            "  style.textContent = '#particles, #particles canvas, .tsparticles-canvas-el { display: none !important; visibility: hidden !important; }';" +
            "  document.head.appendChild(style);" +
            "  console.log('[DashieLite] Fire tablet: Animation fix applied');" +
            "}" +
            "inject();" +
            "})();"

        webView.evaluateJavascript(js, null)
        Log.i(TAG, "Fire tablet: Injected animation pause fix")
    }

    /**
     * Inject CSS to hide native video controls on WebRTC/camera cards.
     *
     * On low-powered devices (like Fire tablets), the video decoder can't keep up with
     * high-resolution WebRTC streams. When frames are dropped, the browser shows:
     * - Loading/buffering spinner
     * - Video scrub bar/progress controls
     *
     * This hides those native controls so the video just shows the last decoded frame
     * instead of flashing controls repeatedly. The video will still play, just without
     * the distracting UI elements.
     *
     * Targets:
     * - video elements inside WebRTC cards (webrtc-camera, frigate-card, etc.)
     * - Native video controls (scrub bar, play button, etc.)
     * - Loading spinners on video elements
     *
     * @param webView The WebView to inject the CSS into
     * @param enabled If true, hide video controls; if false, show them
     */
    fun injectVideoControlsHiding(webView: WebView?, enabled: Boolean) {
        webView ?: return
        if (!enabled) return

        val js = """
            (function() {
                if (window._dashieVideoControlsHidden) return;
                window._dashieVideoControlsHidden = true;

                console.log('[DashieLite] Injecting video controls hiding CSS...');

                function injectStyle(elem, css, id) {
                    if (!elem || !css) return false;
                    const styleId = 'dashie-video-' + id;
                    if (elem.querySelector('#' + styleId)) return true;

                    const style = document.createElement('style');
                    style.id = styleId;
                    style.textContent = css;
                    elem.appendChild(style);
                    console.log('[DashieLite] ✓ Injected ' + id + ' video CSS');
                    return true;
                }

                // CSS to hide native video controls, loading spinners, and progress bars
                // Also hide any overlays that appear during buffering
                const VIDEO_CONTROLS_CSS = `
                    /* Hide native video controls */
                    video::-webkit-media-controls,
                    video::-webkit-media-controls-enclosure,
                    video::-webkit-media-controls-panel,
                    video::-webkit-media-controls-overlay-enclosure,
                    video::-webkit-media-controls-start-playback-button,
                    video::-webkit-media-controls-play-button,
                    video::-webkit-media-controls-timeline,
                    video::-webkit-media-controls-current-time-display,
                    video::-webkit-media-controls-time-remaining-display,
                    video::-webkit-media-controls-mute-button,
                    video::-webkit-media-controls-volume-slider,
                    video::-webkit-media-controls-fullscreen-button {
                        display: none !important;
                        visibility: hidden !important;
                        opacity: 0 !important;
                        pointer-events: none !important;
                    }

                    /* Force controls attribute off */
                    video {
                        -webkit-media-controls-panel-display: none !important;
                    }

                    /* Hide loading spinner overlays commonly used in HA cards */
                    .loading-container,
                    .spinner,
                    .loading-overlay,
                    ha-circular-progress,
                    mwc-circular-progress,
                    paper-spinner,
                    .buffering-overlay,
                    [class*="loading"],
                    [class*="spinner"],
                    [class*="buffering"] {
                        display: none !important;
                        visibility: hidden !important;
                    }

                    /* WebRTC card specific - hide any controls overlay */
                    webrtc-camera .controls,
                    webrtc-camera .overlay,
                    webrtc-camera [class*="control"],
                    frigate-card .controls,
                    frigate-card .overlay,
                    frigate-card [class*="control"],
                    hui-image .controls,
                    ha-camera-stream .controls {
                        display: none !important;
                        visibility: hidden !important;
                    }
                `;

                // Inject into main document
                injectStyle(document.head, VIDEO_CONTROLS_CSS, 'controls-main');

                // Function to inject into shadow roots
                function injectIntoShadowRoots(root) {
                    if (!root) return;

                    // Find all elements that might have shadow roots
                    const elements = root.querySelectorAll('*');
                    elements.forEach(function(el) {
                        if (el.shadowRoot) {
                            injectStyle(el.shadowRoot, VIDEO_CONTROLS_CSS, 'controls-shadow-' + el.tagName.toLowerCase());
                            // Recurse into nested shadow roots
                            injectIntoShadowRoots(el.shadowRoot);
                        }
                    });

                    // Also remove controls attribute from any video elements
                    const videos = root.querySelectorAll('video');
                    videos.forEach(function(video) {
                        video.removeAttribute('controls');
                        video.controls = false;
                    });
                }

                // Apply to main document
                injectIntoShadowRoots(document);

                // Watch for new video elements and shadow roots
                const observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        mutation.addedNodes.forEach(function(node) {
                            if (node.nodeType === 1) {  // Element node
                                // Remove controls from video elements
                                if (node.tagName === 'VIDEO') {
                                    node.removeAttribute('controls');
                                    node.controls = false;
                                }
                                // Check for shadow root
                                if (node.shadowRoot) {
                                    injectStyle(node.shadowRoot, VIDEO_CONTROLS_CSS, 'controls-shadow-' + node.tagName.toLowerCase());
                                    injectIntoShadowRoots(node.shadowRoot);
                                }
                                // Check children
                                if (node.querySelectorAll) {
                                    const videos = node.querySelectorAll('video');
                                    videos.forEach(function(v) {
                                        v.removeAttribute('controls');
                                        v.controls = false;
                                    });
                                    injectIntoShadowRoots(node);
                                }
                            }
                        });
                    });
                });

                observer.observe(document.body, { childList: true, subtree: true });

                // Also traverse HA shadow DOM specifically
                function traverseHaShadowDom() {
                    const ha = document.querySelector('home-assistant');
                    if (ha && ha.shadowRoot) {
                        injectStyle(ha.shadowRoot, VIDEO_CONTROLS_CSS, 'controls-ha');
                        injectIntoShadowRoots(ha.shadowRoot);

                        // Observe HA shadow root too
                        observer.observe(ha.shadowRoot, { childList: true, subtree: true });
                    }
                }

                // Run after HA loads
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', traverseHaShadowDom);
                } else {
                    setTimeout(traverseHaShadowDom, 1000);
                }

                // Re-run periodically to catch dynamically loaded cards
                setInterval(function() {
                    injectIntoShadowRoots(document);
                    traverseHaShadowDom();
                }, 5000);

                console.log('[DashieLite] ✓ Video controls hiding enabled');
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
        Log.i(TAG, "Halite: Injected video controls hiding CSS")
    }
}

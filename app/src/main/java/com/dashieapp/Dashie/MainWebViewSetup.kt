package com.dashieapp.Dashie

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.core.view.doOnLayout
import com.dashieapp.Dashie.util.DeviceInfoHelper
import com.dashieapp.Dashie.webview.DashieWebView

/**
 * Handles all WebView configuration and settings.
 * Extracted from MainActivity to reduce its size and improve maintainability.
 *
 * Responsibilities:
 * - WebView settings (JavaScript, DOM storage, cookies, etc.)
 * - User agent configuration
 * - Viewport and scaling settings (device-specific)
 * - Gesture exclusion zones for sidebar swipe
 * - Fire tablet keyboard scroll handling
 */
class MainWebViewSetup(
    private val context: Context
) {
    companion object {
        private const val TAG = "WebViewSetup"

        /**
         * Set true to make the NEXT WebView configuration use LOAD_NO_CACHE for
         * its first load — guaranteeing fresh JS regardless of whether the async
         * clearCache(true) eviction has finished (the eviction races the reload,
         * which is why a plain in-app Clear Cache wasn't reliable). Consumed
         * (reset to false) on read; DashieWebViewClient.onPageFinished restores
         * LOAD_DEFAULT after the load so later navigation is cached. Survives the
         * Clear Cache activity restart because that's same-process.
         */
        @Volatile var forceFreshLoadOnce = false
    }

    /**
     * Configure all WebView settings.
     * Call this after finding the WebView in onCreate.
     *
     * @param webView The WebView to configure
     * @param isHaliteBuild Whether this is a Halite (ALLOW_URL_CONFIG) build
     */
    fun configure(webView: WebView, isHaliteBuild: Boolean) {
        Log.i(TAG, "🔧 Configuring WebView settings")

        configureBasicSettings(webView)
        configureSecuritySettings(webView)
        configureUserAgent(webView)
        configureCookies(webView)
        configureViewport(webView, isHaliteBuild)

        if (isHaliteBuild) {
            configureGestureExclusion(webView)
            configureFireTabletKeyboardHandler(webView)
        }

        // Protect renderer from being killed by low memory killer on cheap tablets
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_IMPORTANT,
                false  // Don't waive even when not visible - kiosk is always foreground
            )
        }

        // Enable WebView debugging for console.log visibility
        WebView.setWebContentsDebuggingEnabled(true)

        Log.i(TAG, "✓ WebView configuration complete")
    }

    /**
     * Configure basic WebView settings (JavaScript, DOM storage, etc.)
     */
    private fun configureBasicSettings(webView: WebView) {
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        @Suppress("DEPRECATION")
        settings.databaseEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Use LOAD_DEFAULT for normal HTTP caching - HA's hashed JS chunks are immutable
        // and safe to cache. This dramatically improves reload times (7s → ~1-2s).
        // For development, temporarily change to LOAD_NO_CACHE if needed.
        // After a Clear Cache, forceFreshLoadOnce makes JUST this first load bypass
        // the cache (so stale JS can't be served if the eviction lost the race);
        // onPageFinished restores LOAD_DEFAULT.
        settings.cacheMode = if (forceFreshLoadOnce) {
            forceFreshLoadOnce = false
            Log.i(TAG, "🧹 Forcing LOAD_NO_CACHE for first load after Clear Cache")
            WebSettings.LOAD_NO_CACHE
        } else {
            WebSettings.LOAD_DEFAULT
        }
        settings.setGeolocationEnabled(false)
        @Suppress("DEPRECATION")
        settings.setSaveFormData(false)
        @Suppress("DEPRECATION")
        settings.setSavePassword(false)

        // setSaveFormData/setSavePassword are no-ops on API 26+. The actual
        // "Save password?" prompt comes from the Android Autofill Framework
        // (Samsung Pass / Google Autofill), which those deprecated settings
        // don't touch. On a kiosk device the prompt interrupts the HA login
        // flow, so opt the WebView (and all descendant form fields, incl.
        // HA's username/password) out of autofill entirely.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }

        // Enable initial focus on TV devices for D-pad navigation
        // Disabled on touch devices to prevent keyboard from auto-showing
        val isTvDevice = DeviceInfoHelper.isFireTV() || DeviceInfoHelper.isTVDevice(context)
        settings.setNeedInitialFocus(isTvDevice)

        @Suppress("DEPRECATION")
        settings.setRenderPriority(WebSettings.RenderPriority.HIGH)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = false
        }

        try {
            settings.mediaPlaybackRequiresUserGesture = false
        } catch (e: Exception) {
            Log.w(TAG, "Media playback setting not available: ${e.message}")
        }

        // Optional: force hardware-backed compositing for the WebView.
        // Default is OFF — Chromium picks LAYER_TYPE_NONE / its own
        // strategy. With Dashie's stack of always-present transparent
        // overlays above the WebView (widgetContainer, overlayContainer,
        // sidebar strip), Chromium can mark the WebView region as
        // occluded and stop pushing frames to <video> compositing layers
        // — streams keep arriving but the GPU texture goes stale until
        // a reflow invalidates the layer.
        //
        // Symptom: Advanced Camera Card / WebRTC / picture-glance cards
        // go blank a few seconds after page load and only repaint when
        // HA's own sidebar (or any other layout reflow) is triggered.
        // First reported by Matt Philips 2026-04-29.
        //
        // The fix risks breaking other devices: some Fire TVs and older
        // GPUs fail HARDWARE layer allocation and render the dashboard
        // entirely blank. Surfaced as an opt-in toggle in
        // Advanced > Camera Compatibility instead of forcing it on.
        try {
            val prefs = com.dashieapp.Dashie.halite.HalitePreferences(context)
            if (prefs.display.gpuHardwareLayerEnabled) {
                webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
                Log.i(TAG, "WebView layer type → HARDWARE (user opt-in)")
            } else {
                Log.i(TAG, "WebView layer type → default (HARDWARE opt-in disabled)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read GPU layer pref, leaving WebView layer at default: ${e.message}")
        }
    }

    /**
     * Configure security-related settings.
     */
    private fun configureSecuritySettings(webView: WebView) {
        val settings = webView.settings

        // Limit file access for security on Fire TV
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
    }

    /**
     * Configure user agent to include app version.
     */
    private fun configureUserAgent(webView: WebView) {
        val settings = webView.settings
        val originalUserAgent = settings.userAgentString
        val modifiedUserAgent = originalUserAgent.replace("; wv", "").replace(";wv", "")
        val appVersion = BuildConfig.VERSION_NAME
        settings.userAgentString = "$modifiedUserAgent DashieApp/$appVersion"

        Log.d(TAG, "User agent set: DashieApp/$appVersion")
    }

    /**
     * Configure cookie settings.
     */
    private fun configureCookies(webView: WebView) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptThirdPartyCookies(webView, true)
    }

    /**
     * Configure viewport based on build type and device.
     *
     * - Halite: Use wide viewport for HA dashboards to render at native resolution
     * - Standard Dashie on tablets: Don't use wide viewport so web app's viewport meta scaling works
     * - Standard Dashie on TVs: Use wide viewport + custom scaling for high-DPI displays
     */
    private fun configureViewport(webView: WebView, isHaliteBuild: Boolean) {
        val settings = webView.settings
        val isTablet = DeviceInfoHelper.isTablet(context)
        val isTv = DeviceInfoHelper.isFireTV() || DeviceInfoHelper.isTVDevice(context)

        when {
            isHaliteBuild -> {
                // Halite: Enable wide viewport for HA dashboards
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                Log.i(TAG, "📱 Halite mode - wide viewport enabled")
            }
            isTv -> {
                // Standard Dashie on TV: Enable wide viewport with custom scaling
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                // TV devices report high density (320dpi) - scale to achieve 1:1
                val displayMetrics = context.resources.displayMetrics
                val scale = (160.0 / displayMetrics.densityDpi * 100).toInt()
                webView.setInitialScale(scale)
                Log.i(TAG, "📺 TV device - WebView scale set to $scale% (density: ${displayMetrics.densityDpi}dpi)")
            }
            isTablet -> {
                // Standard Dashie on tablet: Let web app control viewport via meta tag
                settings.useWideViewPort = false
                settings.loadWithOverviewMode = false
                Log.i(TAG, "📱 Standard Dashie on tablet - web app controls viewport scaling")
            }
            else -> {
                // Standard Dashie on phone/other: Default settings
                settings.useWideViewPort = false
                settings.loadWithOverviewMode = false
                Log.i(TAG, "📱 Standard Dashie - default viewport settings")
            }
        }
    }

    /**
     * Configure gesture exclusion zone for sidebar swipe (Android 10+).
     * Without this, Android's back gesture intercepts left-edge swipes.
     */
    private fun configureGestureExclusion(webView: WebView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            webView.doOnLayout {
                val exclusionWidth = (40 * context.resources.displayMetrics.density).toInt() // 40dp
                val exclusionRect = Rect(0, 0, exclusionWidth, webView.height)
                webView.systemGestureExclusionRects = listOf(exclusionRect)
                Log.i(TAG, "📱 Left edge gesture exclusion set: ${exclusionWidth}px wide")
            }
        }
    }

    /**
     * Configure Fire tablet keyboard visibility handler.
     * When keyboard shows, ensures focused input is scrolled into view.
     */
    private fun configureFireTabletKeyboardHandler(webView: WebView) {
        if (!Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) return

        (webView as? DashieWebView)?.onKeyboardVisibilityChanged = { isKeyboardVisible ->
            if (isKeyboardVisible) {
                Log.i(TAG, "🔧 Fire tablet: Keyboard shown - ensuring focused input is visible")
                // Use a small delay to let adjustPan work first, then scroll if needed
                webView.postDelayed({
                    webView.evaluateJavascript("""
                        (function() {
                            var focused = document.activeElement;
                            if (focused && (focused.tagName === 'INPUT' || focused.tagName === 'TEXTAREA')) {
                                focused.scrollIntoView({ behavior: 'smooth', block: 'center' });
                            }
                        })();
                    """.trimIndent(), null)
                }, 300)
            } else {
                Log.i(TAG, "🔧 Fire tablet: Keyboard hidden")
            }
        }
    }

    /**
     * Clear WebView focus to prevent keyboard popup.
     * Call this on activity start/recreate.
     */
    fun clearFocusAndHideKeyboard(webView: WebView) {
        webView.clearFocus()
    }
}

package com.dashieapp.Dashie.halite.screensaver

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * URL/WebView screensaver mode — loads an arbitrary URL into a dedicated
 * WebView that overlays the dim layer. The WebView is kept around between
 * activations to allow faster re-activation; only [destroy] tears it down
 * fully.
 *
 * Touches inside the screensaver WebView need to wake Dashie, so the
 * controller forwards every MotionEvent to [onTouchForwarded] and consumes
 * the event itself (screensaver content is view-only — no widget should be
 * receiving touch).
 */
class UrlScreensaverController(
    private val context: Context,
    private val photoContainer: FrameLayout,
    private val prefs: ScreensaverPreferences,
    private val onTouchForwarded: (MotionEvent) -> Unit
) {
    companion object {
        private const val TAG = "UrlScreensaverController"
    }

    private var screensaverWebView: WebView? = null

    /** True once the WebView has been created (whether currently loaded or "about:blank"). */
    fun isActive(): Boolean = screensaverWebView != null

    /**
     * Activate URL mode — create the WebView (if needed) and load
     * [ScreensaverPreferences.screensaverUrl]. Caller is responsible for
     * setting [photoContainer] visibility before/after.
     */
    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    fun setup() {
        val url = prefs.screensaverUrl
        Log.i(TAG, "Setting up URL screensaver: $url")

        if (screensaverWebView == null) {
            screensaverWebView = WebView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    // Allow mixed content for weather APIs that might use HTTP
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }

                setBackgroundColor(Color.BLACK)

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.i(TAG, "URL screensaver page loaded: $url")
                    }

                    @Suppress("DEPRECATION")
                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        Log.e(TAG, "WebView error ($errorCode): $description for $failingUrl")
                    }
                }

                webChromeClient = WebChromeClient()

                // Forward touch events to the host so the dim overlay's
                // touch listener can fire (otherwise the WebView consumes
                // every touch and the user can't wake the device).
                setOnTouchListener { _, event ->
                    onTouchForwarded(event)
                    true // consume — screensaver content is view-only
                }
            }
            photoContainer.addView(screensaverWebView)
        }

        // The WebView shares photoContainer with PhotoSlideshowView. Both
        // are children at independent z-orders; whichever was added last
        // sits on top. If the user previously had photos active and then
        // switched to URL, the WebView was added on top of slideshowView
        // and stays there even after deactivate() loads about:blank — the
        // black WebView covers any photo content shown in a later photo
        // mode. Always re-show + bring-to-front on activation, and hide
        // on deactivation so the other modes underneath show through.
        screensaverWebView?.let { wv ->
            wv.visibility = View.VISIBLE
            wv.bringToFront()
        }
        photoContainer.visibility = View.VISIBLE
        screensaverWebView?.loadUrl(url)
    }

    /**
     * Deactivate URL mode without destroying the WebView. Stops the page +
     * loads `about:blank` so any running scripts halt; keeps the WebView
     * around for faster re-activation. Caller decides whether to hide
     * [photoContainer] (depends on whether other modes — e.g. photos — are
     * also using it).
     */
    fun deactivate() {
        screensaverWebView?.let { wv ->
            wv.stopLoading()
            wv.loadUrl("about:blank")
            // Hide so the WebView (still a child of photoContainer) doesn't
            // cover photos / clock / weather rendered by other modes.
            wv.visibility = View.GONE
        }
    }

    /** Fully destroy the WebView. Called from ScreenDimmer.destroy(). */
    fun destroy() {
        screensaverWebView?.let { wv ->
            wv.stopLoading()
            wv.destroy()
        }
        screensaverWebView = null
    }
}

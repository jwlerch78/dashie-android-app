package com.dashieapp.Dashie.halite

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebView
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog

/**
 * Manages WebView page load retry logic, timeouts, and splash screen display.
 * Extracted from DashieWebViewClient.
 *
 * Responsibilities:
 * - Retry scheduling after network errors (with exponential backoff)
 * - Page load timeout detection (force retry if WebView hangs)
 * - Splash screen failsafe timeout (ensure sidebar access even if HA is down)
 * - Splash screen hide animation
 */
class WebViewRetryManager(
    private val splashOverlay: View,
    private val haConnectionMonitorProvider: () -> HaConnectionMonitor?
) {
    companion object {
        private const val TAG = "WebViewRetryManager"
    }

    // Retry logic for network errors
    private val retryHandler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null
    var retryCount = 0
        private set
    var hasMainFrameLoaded = false
        private set
    var hadMainFrameError = false
        private set
    private val maxRetries = 720  // Max retries (5 seconds each = 1 hour) - effectively unlimited
    private val retryDelayMs = 5000L
    private val pageLoadTimeoutMs = 30000L

    // Kiosk shell mode: once the local shell page loads, stop retrying
    // (iframe navigations within the shell should NOT trigger full-page reloads)
    var kioskShellReady = false
        private set

    // Splash screen failsafe timeout
    private var splashTimeoutRunnable: Runnable? = null
    var splashHidden = false
        private set
    private val splashTimeoutMs = 15000L

    /**
     * Called when the kiosk shell page (local APK asset) has loaded successfully.
     * Permanently disables retry/timeout logic — the shell works fine,
     * only the HA iframe inside it may be unreachable.
     */
    fun onKioskShellReady() {
        kioskShellReady = true
        hasMainFrameLoaded = true
        cancelRetry()
        cancelPageLoadTimeout()
        Log.i(TAG, "🏠 Kiosk shell ready — retry manager disabled (shell is local content)")
        PersistentLog.info("PAGELOAD", "kiosk shell ready — retry manager disabled")
    }

    /**
     * Called when a new page load starts (from onPageStarted).
     * Resets error flags and starts timeouts.
     * Skipped when kiosk shell is already loaded (iframe navigations within the shell).
     */
    fun onPageLoadStarting(view: WebView?) {
        if (kioskShellReady) return
        hasMainFrameLoaded = false
        hadMainFrameError = false
        startPageLoadTimeout(view)
        startSplashTimeout()
    }

    /**
     * Hide splash with a short delay for local asset pages (kiosk-shell).
     * Local assets load near-instantly — no need for the full 15s failsafe timeout.
     * Uses a brief delay (500ms) so the WebView has time to render first frame.
     */
    fun hideSplashForLocalContent() {
        if (splashHidden) return
        cancelSplashTimeout()
        retryHandler.postDelayed({
            hideSplashScreen("local content ready")
        }, 500)
        Log.i(TAG, "⚡ Local content — splash will hide in 500ms")
    }

    /**
     * Called when page finishes loading (from onPageFinished).
     * Cancels retries if no error occurred, or just cancels the timeout if there was an error.
     */
    fun onPageLoadFinished() {
        if (hadMainFrameError) {
            Log.i(TAG, "📄 Page finished but had main frame error - NOT cancelling retry")
            cancelPageLoadTimeout()
        } else {
            val wasRetrying = retryCount > 0
            cancelRetry()
            cancelPageLoadTimeout()
            hasMainFrameLoaded = true
            retryCount = 0

            // If we were retrying (e.g., after 404 during HA restart) and page now loaded,
            // clear the offline indicator
            if (wasRetrying) {
                Log.i(TAG, "✓ Page loaded after retries — clearing offline indicator")
                haConnectionMonitorProvider()?.onHaOnline?.invoke()
            }
        }
    }

    /**
     * Called on main frame network error (from onReceivedError).
     * Delegates to HaConnectionMonitor for ping-based recovery, or uses fallback retry.
     */
    fun onMainFrameError(view: WebView, errorCode: Int, errorDesc: String) {
        hadMainFrameError = true

        // Common network errors that warrant recovery
        val shouldRecover = errorCode in listOf(-2, -6, -7, -8, -1, -11)

        if (shouldRecover) {
            val monitor = haConnectionMonitorProvider()
            if (monitor != null) {
                Log.i(TAG, "🔄 Delegating recovery to HaConnectionMonitor (indefinite ping loop)")
                monitor.onPageLoadFailed(errorCode, errorDesc)
            } else {
                Log.i(TAG, "🔄 HaConnectionMonitor not available, using fallback retry")
                if (retryCount < maxRetries) {
                    scheduleRetry(view)
                }
            }
        }
    }

    /**
     * Called on main frame HTTP error (from onReceivedHttpError).
     * Retries on server errors (5xx) and 404 (HA restarting / not ready yet).
     *
     * When HA restarts, the web server often comes back before HA's frontend is ready,
     * returning 404. Without retry, the user sees a permanent white "404 Not Found" screen.
     * The TCP ping loop in HaConnectionMonitor won't help here because the server IS
     * reachable (TCP succeeds), it's just not serving the HA frontend yet.
     */
    fun onMainFrameHttpError(view: WebView, statusCode: Int) {
        // 404 = HA not ready yet (frontend not served), 502/503/504 = reverse proxy can't reach HA
        val isRecoverable = statusCode == 404 || statusCode >= 500

        if (isRecoverable && retryCount < maxRetries) {
            hadMainFrameError = true
            // Defeat a callback-order race. On some WebViews (older Echo Show / LineageOS
            // builds) onPageFinished fires BEFORE onReceivedHttpError for a 404, so
            // onPageLoadFinished() — seeing hadMainFrameError still false — latches
            // hasMainFrameLoaded=true. scheduleRetry()'s `if (!hasMainFrameLoaded)` guard then
            // skips the reload and the 404 page sticks forever (Jules' overnight 404 that the
            // retry "didn't fix"). A known main-frame HTTP error means the frame did NOT really
            // load, so clear the flag to guarantee the retry actually executes.
            hasMainFrameLoaded = false
            Log.i(TAG, "🔄 HTTP $statusCode on main frame — scheduling retry (HA likely restarting)")
            DiagnosticBuffer.info("NET", "HTTP $statusCode — scheduling retry #${retryCount + 1}")

            // Show offline indicator via HaConnectionMonitor
            val monitor = haConnectionMonitorProvider()
            if (monitor != null && statusCode == 404) {
                // For 404, HaConnectionMonitor's TCP ping won't help (server is reachable),
                // so just trigger the offline UI without starting its ping loop
                monitor.onHaOffline?.invoke()
            }

            scheduleRetry(view)
        }
    }

    /**
     * Hide the splash screen with fade animation.
     * Called either when page loads successfully (onPageFinished) or on failsafe timeout.
     */
    fun hideSplashScreen(reason: String) {
        if (splashHidden) {
            Log.d(TAG, "🖼️ Splash already hidden, skipping ($reason)")
            return
        }

        splashHidden = true
        cancelSplashTimeout()

        Log.i(TAG, "🖼️ Hiding splash screen ($reason)")

        splashOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                splashOverlay.visibility = View.GONE
            }
            .start()
    }

    // ---- Internal retry/timeout methods ----

    private fun scheduleRetry(view: WebView) {
        cancelRetry()

        retryCount++
        Log.i(TAG, "🔄 Scheduling retry #$retryCount in ${retryDelayMs}ms...")
        DiagnosticBuffer.info("NET", "Retry #$retryCount scheduled")

        retryRunnable = Runnable {
            if (!hasMainFrameLoaded && retryCount <= maxRetries) {
                Log.i(TAG, "🔄 Executing retry #$retryCount - reloading page...")
                DiagnosticBuffer.info("NET", "Executing retry #$retryCount")
                PersistentLog.info("REFRESH", "WebView reload: network error retry #$retryCount")
                view.reload()
            }
        }
        retryHandler.postDelayed(retryRunnable!!, retryDelayMs)
    }

    private fun cancelRetry() {
        retryRunnable?.let {
            retryHandler.removeCallbacks(it)
            if (retryCount > 0) {
                Log.i(TAG, "✓ Cancelled pending retry (page loaded successfully after $retryCount retries)")
                DiagnosticBuffer.info("NET", "Retry cancelled - page loaded after $retryCount retries")
            }
        }
        retryRunnable = null
    }

    private fun startPageLoadTimeout(view: WebView?) {
        cancelPageLoadTimeout()

        if (view == null) return

        timeoutRunnable = Runnable {
            if (!hasMainFrameLoaded && retryCount < maxRetries) {
                Log.w(TAG, "⏱️ Page load timeout after ${pageLoadTimeoutMs/1000}s - forcing retry")
                DiagnosticBuffer.warn("NET", "Page load timeout - forcing retry #${retryCount + 1}")
                scheduleRetry(view)
            }
        }
        retryHandler.postDelayed(timeoutRunnable!!, pageLoadTimeoutMs)
        Log.d(TAG, "⏱️ Page load timeout started (${pageLoadTimeoutMs/1000}s)")
    }

    private fun cancelPageLoadTimeout() {
        timeoutRunnable?.let {
            retryHandler.removeCallbacks(it)
        }
        timeoutRunnable = null
    }

    private fun startSplashTimeout() {
        cancelSplashTimeout()

        if (splashHidden) return

        splashTimeoutRunnable = Runnable {
            if (!splashHidden) {
                Log.w(TAG, "⏱️ Splash screen timeout after ${splashTimeoutMs/1000}s - hiding splash anyway")
                DiagnosticBuffer.warn("NAV", "Splash timeout - forcing hide so user can access sidebar")
                hideSplashScreen("failsafe timeout")
            }
        }
        retryHandler.postDelayed(splashTimeoutRunnable!!, splashTimeoutMs)
        Log.d(TAG, "⏱️ Splash screen timeout started (${splashTimeoutMs/1000}s)")
    }

    private fun cancelSplashTimeout() {
        splashTimeoutRunnable?.let {
            retryHandler.removeCallbacks(it)
        }
        splashTimeoutRunnable = null
    }
}

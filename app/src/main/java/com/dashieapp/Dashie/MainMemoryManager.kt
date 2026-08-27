package com.dashieapp.Dashie

import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebStorage
import android.webkit.WebView
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.diagnostics.CrashDiagnosticsCollector
import com.dashieapp.Dashie.halite.diagnostics.MemoryMonitor
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog

/**
 * Manages memory pressure handling, WebView reloads, and overlay refresh scheduling.
 * Extracted from MainActivity to reduce its size and improve separation of concerns.
 *
 * Responsibilities:
 * - Handle memory pressure events (from MemoryMonitor and onTrimMemory)
 * - Perform WebView reloads for memory recovery
 * - Schedule proactive overlay refreshes
 * - Track screensaver state for deferred memory recovery
 * - Restore HA path after memory reload
 */
class MainMemoryManager(
    private val webViewProvider: () -> WebView?,
    private val halitePrefsProvider: () -> HalitePreferences?,
    private val callbacks: Callbacks
) {
    companion object {
        private const val TAG = "MainMemoryManager"

        // Throttle interval for WebView reloads (60 seconds)
        private const val RELOAD_THROTTLE_MS = 60_000L

        // Minimum screensaver duration before WARNING-level reload (15 seconds)
        private const val SCREENSAVER_MIN_DURATION_MS = 15_000L

        // Delay for about:blank → HA URL restoration (1000ms for GPU texture release)
        private const val ABOUT_BLANK_RESTORE_DELAY_MS = 1000L

        // Retry delay for blocked overlay refresh (30 seconds)
        private const val OVERLAY_REFRESH_RETRY_DELAY_MS = 30_000L

        // Throttle interval for overlay refresh from memory pressure (60 seconds)
        // Prevents feedback loop where refresh triggers more memory warnings
        private const val OVERLAY_REFRESH_THROTTLE_MS = 60_000L

        // Throttle interval for RTSP restart from memory pressure (60 seconds)
        // Prevents death spiral where rapid RTSP stop/start triggers Samsung "Camera disabled
        // by policy" and competing restart attempts between memory recovery and auto-restart
        private const val RTSP_RESTART_THROTTLE_MS = 60_000L
    }

    /**
     * Callbacks interface for actions that require MainActivity context.
     */
    interface Callbacks {
        fun isAllowUrlConfig(): Boolean
        fun runOnUiThread(action: Runnable)
        fun getScreenController(): com.dashieapp.Dashie.halite.HaliteScreenController?
        fun getRtspPlayerManager(): com.dashieapp.Dashie.halite.rtsp.RtspPlayerManager?
        fun requestGc()
        fun requestTrimMemory()
        /**
         * Destroy and recreate the WebView to release native memory.
         * This is the ONLY way to truly release WebView's native memory (Chromium renderer,
         * GPU textures, internal buffers). clearCache() and loadUrl("about:blank") only
         * release page content, not the WebView's own allocations.
         *
         * @param urlToRestore The URL to load after recreation
         * @param pathToRestore The HA path to restore via JavaScript after page loads
         * @param onComplete Called when recreation is complete and URL is loading
         */
        fun recreateWebView(urlToRestore: String, pathToRestore: String?, onComplete: () -> Unit)

        /**
         * Restart the RTSP server to release OpenGL textures and MediaCodec resources.
         * This is called during memory pressure events when restartRtspOnMemoryPressure is enabled.
         * The RTSP server has its own GL context separate from WebView, so WebView recreation
         * doesn't release RTSP's OpenGL textures or encoder memory.
         *
         * @param onComplete Called when restart is complete (or if RTSP was not running)
         */
        fun restartRtspServer(onComplete: () -> Unit)
    }

    // Overlay Memory Management - proactive refresh scheduling
    private var overlayRefreshHandler: Handler? = null
    private var overlayRefreshRunnable: Runnable? = null
    private var pendingRefreshRetryRunnable: Runnable? = null

    // Memory recovery tracking
    private var lastReloadTime: Long = 0L
    private var lastOverlayRefreshTime: Long = 0L  // Throttle overlay refresh from memory pressure
    private var lastRtspRestartTime: Long = 0L  // Throttle RTSP restart from memory pressure
    private var pendingWarningReload: Boolean = false
    private var screensaverStartTime: Long = 0L
    private var isScreensaverCurrentlyActive: Boolean = false

    // Stores the HA URL to restore after memory reload (now a full URL when using iframe)
    private var pendingHaPathRestore: String? = null

    /** True if there's a pending HA URL/path to restore after reload. */
    val hasPendingRestore: Boolean
        get() = pendingHaPathRestore != null

    private val webView: WebView?
        get() = webViewProvider()

    private val halitePrefs: HalitePreferences?
        get() = halitePrefsProvider()

    /**
     * Handle system memory pressure from onTrimMemory.
     * Maps Android trim levels to appropriate actions.
     *
     * @param level The trim memory level from ComponentCallbacks2
     */
    fun handleTrimMemory(level: Int) {
        if (!callbacks.isAllowUrlConfig()) return

        val levelName = when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "LEVEL_$level"
        }

        // Log at appropriate severity based on level
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                // Critical - app may be killed soon
                Log.w(TAG, "⚠️ onTrimMemory: $levelName - CRITICAL memory pressure!")
                PersistentLog.warn("MEMORY", "onTrimMemory: $levelName - CRITICAL! App may be killed")
                com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer.warn("MEMORY", "onTrimMemory: $levelName - CRITICAL")
                webView?.context?.applicationContext?.let { ctx ->
                    com.dashieapp.Dashie.halite.diagnostics.CrashDiagnosticsCollector
                        .persistLifecycleEvent(ctx, "trim_critical", levelName)
                }
                // Log current memory state
                MemoryMonitor.logMemoryStats()
                // Take action: clear caches to free memory
                clearMemoryForPressure(levelName, aggressive = true)
            }
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                // Warning level
                Log.w(TAG, "⚠️ onTrimMemory: $levelName - low memory warning")
                PersistentLog.warn("MEMORY", "onTrimMemory: $levelName - low memory")
                com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer.warn("MEMORY", "onTrimMemory: $levelName")
                // Take action: clear some caches
                clearMemoryForPressure(levelName, aggressive = false)
            }
            else -> {
                // Info level (UI_HIDDEN, BACKGROUND, RUNNING_MODERATE)
                Log.i(TAG, "📉 onTrimMemory: $levelName")
                PersistentLog.info("MEMORY", "onTrimMemory: $levelName")
            }
        }
    }

    /**
     * Handle memory-related reload triggers from multiple sources.
     *
     * Called by:
     * - MemoryMonitor: When PSS exceeds thresholds (BEFORE Android's onTrimMemory fires)
     * - HaliteScreenController: For daily/test scheduled refreshes
     *
     * Levels:
     * - "high": Just log, monitor (25% of headroom consumed)
     * - "warning": WebView reload when screensaver active 15s+ (35% of headroom)
     * - "critical": Immediate WebView reload via about:blank (45% of headroom)
     * - "scheduled": Daily/test refresh - simple reload preserving HA view (no memory pressure)
     *
     * All reload levels use JavaScript path capture/restore to preserve current HA dashboard tab.
     *
     * @param level The trigger level: "high", "warning", "critical", or "scheduled"
     * @param pssMb Current PSS memory usage in MB (0 for scheduled refreshes)
     */
    fun handleMemoryPressure(level: String, pssMb: Int) {
        if (!callbacks.isAllowUrlConfig()) return

        Log.w(TAG, "🚨 Memory pressure detected: level=$level, PSS=${pssMb}MB")
        PersistentLog.warn("MEMORY", "Proactive memory pressure: $level at ${pssMb}MB")

        when (level) {
            "high" -> {
                // HIGH: Just log and monitor - no action needed
                Log.i(TAG, "📊 Memory HIGH at ${pssMb}MB - monitoring")
            }
            "warning" -> {
                // WARNING: WebView reload when screensaver has been active for 15+ seconds
                // This allows memory recovery without disrupting user interaction

                // Check if threshold-based refresh is enabled
                val recoveryEnabled = halitePrefsProvider()?.performance?.emergencyRecoveryEnabled ?: true
                if (!recoveryEnabled) {
                    Log.w(TAG, "⚠️ Memory WARNING at ${pssMb}MB but threshold-based refresh is DISABLED")
                    return
                }

                if (isScreensaverCurrentlyActive) {
                    val screensaverDuration = System.currentTimeMillis() - screensaverStartTime
                    if (screensaverDuration >= SCREENSAVER_MIN_DURATION_MS) {
                        // Screensaver active for 15+ seconds - reload now using critical path
                        // to properly release renderer memory via about:blank navigation
                        Log.w(TAG, "⚠️ Memory WARNING at ${pssMb}MB - screensaver active ${screensaverDuration/1000}s, reloading WebView")
                        performWebViewReload(pssMb, "warning_screensaver", isCritical = true)
                    } else {
                        // Screensaver active but hasn't been on long enough - schedule delayed check
                        val remainingMs = SCREENSAVER_MIN_DURATION_MS - screensaverDuration
                        Log.w(TAG, "⚠️ Memory WARNING at ${pssMb}MB - screensaver active ${screensaverDuration/1000}s, scheduling check in ${remainingMs/1000}s")
                        pendingWarningReload = true
                        PersistentLog.warn("MEMORY", "Warning: scheduling reload check in ${remainingMs/1000}s")

                        // Schedule a check after the remaining time
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (pendingWarningReload && isScreensaverCurrentlyActive) {
                                val duration = System.currentTimeMillis() - screensaverStartTime
                                if (duration >= SCREENSAVER_MIN_DURATION_MS) {
                                    Log.i(TAG, "🔄 Pending memory recovery - screensaver active ${duration/1000}s")
                                    performWebViewReload(pssMb, "warning_deferred", isCritical = true)
                                }
                            }
                        }, remainingMs.coerceAtLeast(1000L))
                    }
                } else {
                    // Screensaver not active - set pending flag for when it activates
                    Log.w(TAG, "⚠️ Memory WARNING at ${pssMb}MB - screensaver not active, deferring reload")
                    pendingWarningReload = true
                    PersistentLog.warn("MEMORY", "Warning: pending reload when screensaver activates")
                }
            }
            "critical" -> {
                // CRITICAL: Immediate WebView reload regardless of screensaver state
                // Uses about:blank navigation to safely release GPU textures first

                // Check if threshold-based refresh is enabled
                val recoveryEnabled = halitePrefsProvider()?.performance?.emergencyRecoveryEnabled ?: true
                if (!recoveryEnabled) {
                    Log.e(TAG, "🚨 Memory CRITICAL at ${pssMb}MB but threshold-based refresh is DISABLED")
                    return
                }

                Log.e(TAG, "🚨 Memory CRITICAL at ${pssMb}MB - immediate WebView reload via about:blank")
                performWebViewReload(pssMb, "critical_immediate", isCritical = true)
            }
            "scheduled" -> {
                // SCHEDULED: Daily/test/manual refresh - use critical path to ensure renderer memory is freed
                Log.i(TAG, "📅 Scheduled refresh - reloading WebView (preserving current view)")
                performWebViewReload(0, "scheduled_refresh", isCritical = true)
            }
        }
    }

    /**
     * Perform WebView reload for memory recovery.
     * Includes throttling to prevent rapid reloads.
     * Preserves the current HA dashboard page/view after reload.
     *
     * For CRITICAL level (isCritical=true), DESTROYS and RECREATES the WebView to
     * truly release native memory (Chromium renderer, GPU textures, internal buffers).
     * The previous approach of using about:blank navigation only released page content,
     * not the WebView's own ~900MB+ native allocations.
     */
    private fun performWebViewReload(pssMb: Int, reason: String, isCritical: Boolean = false) {
        // Clear pending flag since we're reloading now
        pendingWarningReload = false

        // Throttle: only reload if we haven't reloaded in the last 60 seconds
        val now = System.currentTimeMillis()
        if (now - lastReloadTime > RELOAD_THROTTLE_MS) {
            lastReloadTime = now

            // Persist that a WebView refresh is happening (for crash reports)
            webView?.context?.applicationContext?.let { ctx ->
                val type = if (reason.contains("stealth")) "stealth" else "daily"
                CrashDiagnosticsCollector.persistRefreshEvent(ctx, type)
            }

            // CRITICAL FIX: Capture the URL IMMEDIATELY before any WebView manipulation.
            val capturedUrl = webView?.url
            if (capturedUrl.isNullOrEmpty() || capturedUrl == "about:blank") {
                Log.w(TAG, "🔄 Cannot perform reload - no valid URL to restore (current: $capturedUrl)")
                PersistentLog.warn("MEMORY", "Reload aborted: no valid URL (was: $capturedUrl)")
                return
            }

            Log.i(TAG, "🔄 Captured URL for restore: $capturedUrl")
            PersistentLog.info("MEMORY", "Captured URL for restore: ${capturedUrl.take(60)}")

            // Capture the HA iframe's current URL for restoration after reload.
            // dashieGetHaUrl() is defined in kiosk-shell.js and reads the iframe's contentWindow.location.
            webView?.evaluateJavascript(
                "(function() { return typeof dashieGetHaUrl === 'function' ? dashieGetHaUrl() : (window.location.pathname + window.location.hash); })()"
            ) { result ->
                val haUrl = result?.trim('"')?.replace("\\", "") ?: ""
                val pathToRestore = if (haUrl.isNotEmpty() && haUrl != "null" && haUrl != "/kiosk-shell.html") {
                    Log.i(TAG, "🔄 Captured HA URL for restore: $haUrl")
                    haUrl
                } else {
                    null
                }

                if (isCritical) {
                    // CRITICAL: Destroy and recreate WebView to truly release native memory
                    // This is the ONLY way to release the ~900MB+ native allocations that
                    // accumulate over time (Chromium renderer, GPU textures, internal buffers).
                    // The previous about:blank approach only released page content.

                    Log.i(TAG, "🔄 CRITICAL memory reload - DESTROYING and RECREATING WebView")
                    PersistentLog.info("MEMORY", "CRITICAL reload (recreate): $reason at ${pssMb}MB")

                    callbacks.recreateWebView(capturedUrl, pathToRestore) {
                        Log.i(TAG, "🔄 WebView recreation complete")
                        // Reset MemoryMonitor flags so WARNING can fire again in next cycle
                        MemoryMonitor.resetActionFlags()
                    }
                } else {
                    // Normal reload for WARNING level - just clear cache and reload
                    // (lighter weight, doesn't destroy WebView)
                    pendingHaPathRestore = pathToRestore
                    webView?.clearCache(true)
                    webView?.reload()
                    PersistentLog.info("MEMORY", "WebView reloaded: $reason at ${pssMb}MB (restore path: $pathToRestore)")
                    Log.i(TAG, "🔄 WebView reload initiated ($reason, critical=$isCritical) - will restore path: $pathToRestore")
                    // Reset MemoryMonitor flags so WARNING can fire again in next cycle
                    MemoryMonitor.resetActionFlags()
                }
            }
        } else {
            val elapsed = (now - lastReloadTime) / 1000
            Log.i(TAG, "🔄 Skipping WebView reload (throttled - last reload was ${elapsed}s ago)")
            PersistentLog.info("MEMORY", "WebView reload throttled ($reason)")
        }

        // Also refresh overlay (still useful for overlay's own memory)
        attemptOverlayRefresh(reason = "${reason}_pss_$pssMb", forceRefresh = true)

        // Request GC
        callbacks.requestGc()
    }

    /**
     * Set the pending HA path to restore after WebView recreation.
     * Called by MainActivity during recreateWebViewForMemoryRecovery.
     */
    fun setPendingHaPathRestore(path: String) {
        pendingHaPathRestore = path
        Log.i(TAG, "🔄 Set pending HA path for restoration: $path")
    }

    /**
     * Public method to trigger WebView refresh via API.
     * Uses the critical reload path (about:blank navigation) to release memory
     * while preserving the current dashboard page.
     *
     * @param callback Called with true if refresh was triggered, false if throttled
     */
    fun triggerApiWebViewRefresh(callback: (Boolean) -> Unit) {
        // All WebView and timing checks must happen on the main thread
        callbacks.runOnUiThread(Runnable {
            val now = System.currentTimeMillis()
            // Check throttle
            if (now - lastReloadTime <= RELOAD_THROTTLE_MS) {
                val elapsed = (now - lastReloadTime) / 1000
                Log.i(TAG, "🔄 API WebView refresh throttled (last reload was ${elapsed}s ago)")
                callback(false)
            } else {
                val currentPss = MemoryMonitor.getTotalPssMb()
                performWebViewReload(currentPss, "api_triggered", isCritical = true)
                callback(true)
            }
        })
    }

    /**
     * Called after HA page finishes loading to restore the previous view.
     * Should be called from onPageFinished in DashieWebViewClient.
     */
    fun restoreHaPathIfPending() {
        val savedUrl = pendingHaPathRestore ?: return

        // Skip restoration if we're on about:blank (CRITICAL reload uses about:blank as intermediate step)
        val currentUrl = webView?.url ?: ""
        if (currentUrl == "about:blank" || currentUrl.isEmpty()) {
            Log.d(TAG, "🔄 Skipping path restore on about:blank - will restore when HA loads")
            return  // Don't clear pendingHaPathRestore - we'll restore when the real page loads
        }

        // Clear pending path now that we're restoring on the actual page
        pendingHaPathRestore = null

        // Escape single quotes in the saved URL for JS string literal
        val escapedUrl = savedUrl.replace("'", "\\'")

        // If dashieSetHaUrl exists (kiosk shell with iframe), use it to load HA at the saved URL.
        // Otherwise fall back to history.pushState for legacy direct-HA mode.
        val js = """
            (function() {
                var targetUrl = '$escapedUrl';
                if (typeof dashieSetHaUrl === 'function') {
                    console.log('[Dashie] Restoring HA iframe URL after reload:', targetUrl);
                    dashieSetHaUrl(targetUrl);
                } else {
                    // Legacy: HA loaded directly in WebView
                    var targetPath = targetUrl;
                    if (window.location.pathname + window.location.hash !== targetPath) {
                        console.log('[Dashie] Restoring HA path after memory reload:', targetPath);
                        window.history.pushState({}, '', targetPath);
                        window.dispatchEvent(new PopStateEvent('popstate', { state: {} }));
                    }
                }
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js) { _ ->
            Log.i(TAG, "🔄 Restored HA URL after memory reload: $savedUrl")
        }
    }

    /**
     * Called when screensaver state changes.
     * Checks for pending memory recovery actions.
     */
    fun onScreensaverStateChanged(isActive: Boolean) {
        isScreensaverCurrentlyActive = isActive

        if (isActive) {
            screensaverStartTime = System.currentTimeMillis()
            Log.d(TAG, "🌙 Screensaver activated - tracking for memory recovery")

            // If there's a pending warning reload, schedule a check after 15 seconds
            if (pendingWarningReload) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (pendingWarningReload && isScreensaverCurrentlyActive) {
                        val duration = System.currentTimeMillis() - screensaverStartTime
                        if (duration >= SCREENSAVER_MIN_DURATION_MS) {
                            Log.i(TAG, "🔄 Pending memory recovery - screensaver active ${duration/1000}s")
                            performWebViewReload(0, "warning_deferred", isCritical = true)
                        }
                    }
                }, SCREENSAVER_MIN_DURATION_MS)
            }
        } else {
            screensaverStartTime = 0L
            Log.d(TAG, "☀️ Screensaver deactivated")
        }
    }

    /**
     * Clear caches and free memory in response to system memory pressure.
     * @param levelName The trim memory level name for logging
     * @param aggressive If true, clear more aggressively (for CRITICAL levels)
     */
    private fun clearMemoryForPressure(levelName: String, aggressive: Boolean) {
        try {
            Log.i(TAG, "🧹 Clearing memory for $levelName (aggressive=$aggressive)")
            PersistentLog.info("MEMORY", "Clearing caches for $levelName")

            // Clear WebView caches (safe to call while WebView is running)
            webView?.let { wv ->
                // clearCache(false) clears RAM cache only (fast, doesn't touch disk)
                // clearCache(true) clears disk cache too (slower but more thorough)
                wv.clearCache(aggressive)
                Log.i(TAG, "🧹 Cleared WebView cache (includeDiskFiles=$aggressive)")
            }

            // Clear photo cache (screensaver photos)
            callbacks.getScreenController()?.let { screenController ->
                // The screen controller has access to photo cache
                Log.i(TAG, "🧹 Requesting photo cache trim")
            }

            // Restart RTSP server if enabled to release GL textures and MediaCodec resources
            // The RTSP server has its own GL context that WebView recreation doesn't touch
            val restartRtsp = halitePrefs?.performance?.restartRtspOnMemoryPressure ?: false
            if (restartRtsp && aggressive) {
                val now = System.currentTimeMillis()
                if (now - lastRtspRestartTime > RTSP_RESTART_THROTTLE_MS) {
                    lastRtspRestartTime = now
                    Log.i(TAG, "🧹 Restarting RTSP server to release GL textures and encoder memory")
                    PersistentLog.info("MEMORY", "Restarting RTSP server for memory recovery")
                    callbacks.restartRtspServer {
                        Log.i(TAG, "🧹 RTSP server restart complete")
                    }
                } else {
                    val elapsed = (now - lastRtspRestartTime) / 1000
                    Log.i(TAG, "🧹 RTSP restart throttled (last restart was ${elapsed}s ago)")
                }
            } else {
                // RTSP player may have frame buffers but we're not restarting
                callbacks.getRtspPlayerManager()?.let { rtsp ->
                    Log.i(TAG, "🧹 RTSP player active - skipping restart (restartRtsp=$restartRtsp, aggressive=$aggressive)")
                }
            }

            // Force garbage collection hint (system may or may not honor this)
            if (aggressive) {
                callbacks.requestGc()
                Log.i(TAG, "🧹 Requested GC")
            }

            // Log memory after cleanup
            MemoryMonitor.logMemoryStats()

            // Attempt overlay refresh if memory recovery is enabled AND we haven't refreshed recently
            // Throttling prevents feedback loop where refresh triggers more memory warnings
            if (halitePrefs?.performance?.memoryRecoveryEnabled == true) {
                val now = System.currentTimeMillis()
                if (now - lastOverlayRefreshTime > OVERLAY_REFRESH_THROTTLE_MS) {
                    lastOverlayRefreshTime = now
                    Log.i(TAG, "🔄 Attempting overlay refresh due to memory pressure")
                    attemptOverlayRefresh(reason = "memory_pressure_$levelName")
                } else {
                    val elapsed = (now - lastOverlayRefreshTime) / 1000
                    Log.i(TAG, "🔄 Skipping overlay refresh (throttled - last refresh was ${elapsed}s ago)")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing memory: ${e.message}")
        }
    }

    // ============================================
    // Overlay Memory Management
    // ============================================

    /**
     * Attempt to refresh the overlay iframe for memory recovery.
     * Checks if it's safe to refresh (no active user interaction) before proceeding.
     * If not safe, schedules a retry.
     *
     * @param reason Reason for refresh (for logging)
     * @param forceRefresh If true, skip the safety check and refresh immediately
     */
    fun attemptOverlayRefresh(reason: String, forceRefresh: Boolean = false) {
        if (!callbacks.isAllowUrlConfig()) return

        Log.i(TAG, "🔄 attemptOverlayRefresh: reason=$reason, force=$forceRefresh")
        PersistentLog.info("MEMORY", "Attempting overlay refresh: $reason")

        // Cancel any pending retry
        pendingRefreshRetryRunnable?.let {
            overlayRefreshHandler?.removeCallbacks(it)
        }

        webView?.evaluateJavascript("""
            (function() {
                try {
                    var iframe = document.getElementById('dashie-overlay');
                    if (!iframe || !iframe.contentWindow) {
                        return JSON.stringify({ error: 'no_iframe' });
                    }

                    // Check if safe to refresh (unless forced)
                    var canRefresh = ${forceRefresh} || true;
                    var reasons = [];

                    if (!${forceRefresh} && iframe.contentWindow.dashie && iframe.contentWindow.dashie.canSafelyRefresh) {
                        var result = iframe.contentWindow.dashie.canSafelyRefresh();
                        canRefresh = result.canRefresh;
                        reasons = result.reasons || [];
                    }

                    if (!canRefresh) {
                        return JSON.stringify({ blocked: true, reasons: reasons });
                    }

                    // Log memory snapshot before refresh
                    if (iframe.contentWindow.dashie && iframe.contentWindow.dashie.logMemorySnapshot) {
                        iframe.contentWindow.dashie.logMemorySnapshot('pre_refresh_$reason');
                    }

                    // Perform the refresh by reloading iframe src
                    var currentSrc = iframe.src.split('?')[0];
                    iframe.src = currentSrc + '?refresh=' + Date.now();

                    console.log('[Dashie] Overlay iframe refreshed for: $reason');
                    return JSON.stringify({ success: true });
                } catch (e) {
                    return JSON.stringify({ error: e.message });
                }
            })();
        """.trimIndent()) { result ->
            try {
                val cleanResult = result?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "{}"
                Log.i(TAG, "🔄 Overlay refresh result: $cleanResult")

                when {
                    cleanResult.contains("\"blocked\":true") -> {
                        // Not safe to refresh, schedule retry in 30 seconds
                        Log.i(TAG, "🔄 Overlay refresh blocked, scheduling retry in 30s")
                        PersistentLog.info("MEMORY", "Overlay refresh blocked, retrying in 30s")
                        scheduleOverlayRefreshRetry(reason)
                    }
                    cleanResult.contains("\"success\":true") -> {
                        Log.i(TAG, "✅ Overlay refresh completed for: $reason")
                        PersistentLog.info("MEMORY", "Overlay refresh completed: $reason")
                    }
                    cleanResult.contains("\"error\"") -> {
                        Log.w(TAG, "⚠️ Overlay refresh error: $cleanResult")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error parsing refresh result: ${e.message}")
            }
        }
    }

    /**
     * Schedule a retry for overlay refresh after a delay.
     * Used when refresh was blocked due to active user interaction.
     */
    private fun scheduleOverlayRefreshRetry(reason: String) {
        if (overlayRefreshHandler == null) {
            overlayRefreshHandler = Handler(Looper.getMainLooper())
        }

        pendingRefreshRetryRunnable = Runnable {
            Log.i(TAG, "🔄 Retrying overlay refresh: $reason")
            attemptOverlayRefresh(reason = "${reason}_retry", forceRefresh = false)
        }

        overlayRefreshHandler?.postDelayed(pendingRefreshRetryRunnable!!, OVERLAY_REFRESH_RETRY_DELAY_MS)
    }

    /**
     * Start proactive overlay refresh scheduler based on user preference.
     * Should be called after preferences are loaded.
     */
    fun startProactiveRefreshScheduler() {
        if (!callbacks.isAllowUrlConfig()) return

        val refreshHours = halitePrefs?.performance?.proactiveRefreshHours ?: 0
        if (refreshHours <= 0) {
            Log.i(TAG, "📅 Proactive overlay refresh disabled")
            return
        }

        if (overlayRefreshHandler == null) {
            overlayRefreshHandler = Handler(Looper.getMainLooper())
        }

        // Cancel existing scheduler
        overlayRefreshRunnable?.let {
            overlayRefreshHandler?.removeCallbacks(it)
        }

        val intervalMs = refreshHours * 60 * 60 * 1000L
        Log.i(TAG, "📅 Starting proactive overlay refresh: every ${refreshHours}h")
        PersistentLog.info("MEMORY", "Proactive refresh scheduled: every ${refreshHours}h")

        overlayRefreshRunnable = object : Runnable {
            override fun run() {
                Log.i(TAG, "📅 Proactive overlay refresh triggered")
                attemptOverlayRefresh(reason = "proactive_${refreshHours}h")
                // Reschedule for next interval
                overlayRefreshHandler?.postDelayed(this, intervalMs)
            }
        }

        // Schedule first refresh
        overlayRefreshHandler?.postDelayed(overlayRefreshRunnable!!, intervalMs)
    }

    /**
     * Stop proactive overlay refresh scheduler.
     * Called during cleanup/destroy.
     */
    fun stopProactiveRefreshScheduler() {
        overlayRefreshRunnable?.let {
            overlayRefreshHandler?.removeCallbacks(it)
        }
        pendingRefreshRetryRunnable?.let {
            overlayRefreshHandler?.removeCallbacks(it)
        }
        overlayRefreshRunnable = null
        pendingRefreshRetryRunnable = null
        Log.i(TAG, "📅 Proactive overlay refresh scheduler stopped")
    }

    /**
     * Cleanup resources. Should be called from Activity.onDestroy().
     */
    fun destroy() {
        stopProactiveRefreshScheduler()
    }
}

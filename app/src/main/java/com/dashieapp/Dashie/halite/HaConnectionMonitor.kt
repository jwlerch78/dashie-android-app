package com.dashieapp.Dashie.halite

import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog

// Note: TCP ping (pingHa) is only used for recovery detection after WebSocket disconnect.
// We rely on WebSocket ping (10s in JavaScript) for primary connectivity monitoring.

/**
 * Monitors Home Assistant connectivity and manages WebView pause/resume
 * to prevent reconnection storms that cause IP bans.
 *
 * When HA goes down (detected via WebSocket close from JavaScript):
 * 1. Wait for grace period (20s) to allow brief hiccups to recover
 * 2. Pause WebView (stops all JavaScript reconnection attempts)
 * 3. Start a slow TCP ping loop (10 seconds) to detect when HA is back
 * 4. When HA responds, resume WebView and reload the page
 *
 * This prevents the aggressive 1-second reconnection attempts from HA's
 * frontend JavaScript, which can trigger rate limits or IP bans when
 * going through reverse proxies.
 */
class HaConnectionMonitor(
    private val webView: WebView,
    private val prefs: HalitePreferences
) {
    companion object {
        private const val TAG = "HaConnectionMonitor"
        private const val DEFAULT_PING_INTERVAL_MS = 10_000L  // 10 seconds
        private const val PING_TIMEOUT_MS = 5_000  // 5 second timeout for ping
        private const val GRACE_PERIOD_MS = 20_000L  // 20 seconds grace period before pausing WebView
        private const val RTSP_GRACE_PERIOD_MS = 5_000L  // 5 seconds grace period before pausing RTSP
        private const val QUICK_RECOVERY_MS = 20_000L  // If HA back within 20s, don't reload
        private const val RESUME_THROTTLE_MS = 30_000L  // Minimum 30s between reload attempts
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State tracking
    private var isPaused = false
    private var isRtspPaused = false
    private var pingJob: Job? = null
    private var gracePeriodJob: Job? = null  // Job for delayed WebView pause
    private var rtspGracePeriodJob: Job? = null  // Job for delayed RTSP pause (shorter)
    private var disconnectTime = 0L  // When disconnect happened (for quick recovery check)

    // UI interaction state - when true, we use lighter pause (no onPause()) to keep UI responsive
    // This prevents the settings modal from freezing when HA goes offline
    private var isUiInteractionActive = false
    private var isFullyPaused = false  // Track if onPause() was called

    // Screen sleep state - when true, disconnects are likely caused by WiFi sleep (not HA outage)
    // and we should resume WITHOUT reload to avoid the visible "page refresh" on wake
    private var isScreenSleeping = false
    private var disconnectedDuringScreenSleep = false

    // Throttle tracking to prevent rapid reload loops
    private var lastResumeTime = 0L

    // Callbacks for UI updates (show/hide offline overlay)
    var onHaOffline: (() -> Unit)? = null
    var onHaOnline: (() -> Unit)? = null

    // Forwards screen sleep/wake to a listener (the DashboardHealthCoordinator).
    // Reuses this one reliable screen-sleep source rather than threading a second
    // signal through the wiring.
    var onScreenSleepListener: ((Boolean) -> Unit)? = null

    // Returns true if the WebView is currently on a main-frame error (e.g. a 404
    // from HA serving before its frontend is ready after a restart). When true,
    // recovery must RELOAD rather than resume-without-reload onto a broken page.
    var pageErrorProvider: () -> Boolean = { false }

    // Callbacks for RTSP server pause/resume (prevents OOM from buffered frames)
    var onPauseRtsp: (() -> Unit)? = null
    var onResumeRtsp: (() -> Unit)? = null

    // Whether smart reconnect is enabled (now always enabled)
    val isEnabled: Boolean
        get() = prefs.performance.smartReconnectEnabled

    init {
        // Log configuration at startup for debugging
        val localUrl = prefs.performance.localHaUrl.takeIf { it.isNotBlank() }
        val mainUrl = prefs.connection.haBaseUrl.takeIf { it.isNotBlank() } ?: prefs.connection.haUrl.takeIf { it.isNotBlank() }
        Log.i(TAG, "━━━ SMART RECONNECT CONFIG ━━━")
        Log.i(TAG, "  enabled: $isEnabled")
        Log.i(TAG, "  localUrl: ${localUrl ?: "(not configured)"}")
        Log.i(TAG, "  mainUrl: ${mainUrl ?: "(not configured)"}")
        Log.i(TAG, "  pingUrl: ${pingUrl ?: "(none - will use mainUrl)"}")
    }

    // URL for ping checks during outages
    // Prefer local URL if configured (avoids HTTPS proxy during outages)
    // Falls back to main URL - smart reconnect pings less frequently than native WS reconnect
    private val pingUrl: String?
        get() {
            // First try user-configured local URL (preferred for HTTPS sites)
            val localUrl = prefs.performance.localHaUrl.takeIf { it.isNotBlank() }
            if (localUrl != null) return localUrl

            // Fall back to main HA URL
            // Smart reconnect pings every few seconds vs native WS reconnect hammering constantly
            val mainUrl = prefs.connection.haBaseUrl.takeIf { it.isNotBlank() }
                ?: prefs.connection.haUrl.takeIf { it.isNotBlank() }

            return mainUrl
        }

    /**
     * Called from JavaScript when HA WebSocket disconnects.
     * @param code WebSocket close code
     * @param reason Close reason
     */
    fun onHaDisconnect(code: Int, reason: String) {
        Log.w(TAG, "━━━ HA DISCONNECT ━━━ code=$code, reason='$reason'")
        DiagnosticBuffer.warn("HA_CONN", "Disconnect: code=$code")
        PersistentLog.warn("HA_CONN", "Disconnect: code=$code reason='$reason' " +
            "isPaused=$isPaused screenSleeping=$isScreenSleeping")

        Log.i(TAG, "  isEnabled=$isEnabled, isPaused=$isPaused")

        if (!isEnabled) {
            Log.i(TAG, "  → Smart reconnect DISABLED - letting HA handle reconnection normally")
            return
        }

        val targetUrl = pingUrl
        if (targetUrl == null) {
            Log.i(TAG, "  → No URL configured for ping - letting HA handle reconnection normally")
            return
        }

        Log.i(TAG, "  → Smart reconnect ENABLED, pingUrl=$targetUrl")

        // Record disconnect time (for quick recovery detection)
        if (disconnectTime == 0L) {
            disconnectTime = System.currentTimeMillis()
            // Track if this disconnect happened while screen was sleeping
            if (isScreenSleeping) {
                disconnectedDuringScreenSleep = true
                Log.i(TAG, "  → Disconnect during screen sleep (likely WiFi power management)")
            }
        }

        // If already paused or grace period started, nothing to do
        if (isPaused || gracePeriodJob?.isActive == true) {
            Log.i(TAG, "  → Already paused or grace period active, ignoring")
            return
        }

        // Start RTSP grace period (5s) - shorter than WebView grace period
        // This allows brief network hiccups without disrupting the camera feed
        if (!isRtspPaused && rtspGracePeriodJob?.isActive != true) {
            Log.i(TAG, "  → Starting ${RTSP_GRACE_PERIOD_MS/1000}s RTSP grace period")
            rtspGracePeriodJob = scope.launch {
                delay(RTSP_GRACE_PERIOD_MS)
                if (isActive && !isRtspPaused && disconnectTime > 0) {
                    Log.w(TAG, "  → RTSP grace period expired - pausing RTSP server")
                    DiagnosticBuffer.warn("HA_CONN", "RTSP paused after ${RTSP_GRACE_PERIOD_MS/1000}s grace")
                    PersistentLog.warn("HA_CONN", "RTSP paused after ${RTSP_GRACE_PERIOD_MS/1000}s grace")
                    isRtspPaused = true
                    onPauseRtsp?.invoke()
                }
            }
        }

        // Start WebView grace period (30s) - give HA's native reconnection time to work
        Log.i(TAG, "  → Starting ${GRACE_PERIOD_MS/1000}s WebView grace period (letting HA try to reconnect)")
        DiagnosticBuffer.info("HA_CONN", "Grace period started - waiting ${GRACE_PERIOD_MS/1000}s")
        PersistentLog.info("HA_CONN", "Grace period started (${GRACE_PERIOD_MS/1000}s)")

        gracePeriodJob = scope.launch {
            delay(GRACE_PERIOD_MS)

            // After grace period, if still not connected, pause WebView and start ping loop
            if (isActive && !isPaused && disconnectTime > 0) {
                Log.w(TAG, "  → Grace period expired, HA still down - PAUSING WebView now")
                DiagnosticBuffer.warn("HA_CONN", "Grace period expired, pausing WebView")
                PersistentLog.warn("HA_CONN", "Grace period expired → pausing WebView, starting ping loop")
                withContext(Dispatchers.Main) {
                    pauseAndStartPingLoop(targetUrl)
                }
            } else {
                // The grace period coroutine is alive but conditions changed —
                // tells us HA reconnected during grace period (cancellation
                // path goes through onHaConnected). Log only if we entered
                // the post-delay block at all.
                if (isActive) {
                    PersistentLog.info("HA_CONN",
                        "Grace period ended without pause (isPaused=$isPaused disconnectTime=$disconnectTime)")
                }
            }
        }
    }

    /**
     * Called from JavaScript when connection is degraded (pings failing).
     * This is an early warning - start RTSP grace period immediately.
     * The full disconnect signal may come later (or not at all if WS stays "open").
     */
    fun onConnectionDegraded() {
        Log.w(TAG, "━━━ CONNECTION DEGRADED ━━━ (pings failing)")
        DiagnosticBuffer.warn("HA_CONN", "Degraded: pings failing")
        PersistentLog.warn("HA_CONN", "Degraded: JS pings failing")

        if (!isEnabled) {
            Log.i(TAG, "  → Smart reconnect DISABLED - ignoring")
            return
        }

        // Record disconnect time if not already set
        if (disconnectTime == 0L) {
            disconnectTime = System.currentTimeMillis()
        }

        // Start RTSP grace period immediately (if not already started)
        // This is the key benefit of early detection - stop buffering sooner
        if (!isRtspPaused && rtspGracePeriodJob?.isActive != true) {
            Log.i(TAG, "  → Starting ${RTSP_GRACE_PERIOD_MS/1000}s RTSP grace period (early detection)")
            DiagnosticBuffer.info("HA_CONN", "RTSP grace period started (early)")
            rtspGracePeriodJob = scope.launch {
                delay(RTSP_GRACE_PERIOD_MS)
                if (isActive && !isRtspPaused && disconnectTime > 0) {
                    Log.w(TAG, "  → RTSP grace period expired - pausing RTSP server")
                    DiagnosticBuffer.warn("HA_CONN", "RTSP paused (early detection)")
                    isRtspPaused = true
                    onPauseRtsp?.invoke()
                }
            }
        } else {
            Log.i(TAG, "  → RTSP grace period already active or RTSP already paused")
        }

        // Don't start WebView grace period here - wait for actual WS disconnect
        // The connection might recover without the WS closing
    }

    /**
     * Called from WebViewClient when the HA page fails to load (network error).
     * This is different from WebSocket disconnect - the page never loaded at all.
     * Skips grace period and starts ping loop immediately.
     */
    fun onPageLoadFailed(errorCode: Int, errorDesc: String) {
        Log.w(TAG, "━━━ PAGE LOAD FAILED ━━━ code=$errorCode, desc='$errorDesc'")
        DiagnosticBuffer.warn("HA_CONN", "Page load failed: code=$errorCode")
        PersistentLog.warn("HA_CONN", "Page load failed: code=$errorCode desc='$errorDesc'")

        if (!isEnabled) {
            Log.i(TAG, "  → Smart reconnect DISABLED - not starting ping loop")
            return
        }

        val targetUrl = pingUrl
        if (targetUrl == null) {
            Log.i(TAG, "  → No URL configured for ping")
            return
        }

        // If already handling a disconnect, don't start another ping loop
        if (isPaused) {
            Log.i(TAG, "  → Already paused and pinging, ignoring")
            return
        }

        // Record disconnect time
        if (disconnectTime == 0L) {
            disconnectTime = System.currentTimeMillis()
        }

        Log.i(TAG, "  → Starting ping loop immediately (no grace period for page load failures)")
        DiagnosticBuffer.info("HA_CONN", "Starting ping loop for page load failure")

        // Pause RTSP immediately
        if (!isRtspPaused) {
            Log.i(TAG, "  → Pausing RTSP server")
            isRtspPaused = true
            onPauseRtsp?.invoke()
        }

        // Start ping loop immediately (skip grace period since page already failed)
        isPaused = true
        onHaOffline?.invoke()
        startPingLoop(targetUrl)
    }

    /**
     * Called from JavaScript when HA WebSocket connects.
     */
    fun onHaConnected() {
        Log.i(TAG, "━━━ HA CONNECTED ━━━")
        DiagnosticBuffer.info("HA_CONN", "Connected")
        PersistentLog.info("HA_CONN", "Connected (isPaused=$isPaused " +
            "screenSleeping=$isScreenSleeping " +
            "disconnectedDuringScreenSleep=$disconnectedDuringScreenSleep " +
            "disconnectTime=$disconnectTime)")

        val wasDisconnectedFor = if (disconnectTime > 0) {
            System.currentTimeMillis() - disconnectTime
        } else 0L

        Log.i(TAG, "  isPaused=$isPaused, isRtspPaused=$isRtspPaused, wasDisconnectedFor=${wasDisconnectedFor}ms")

        // Cancel RTSP grace period if active (HA reconnected before RTSP was paused)
        if (rtspGracePeriodJob?.isActive == true) {
            Log.i(TAG, "  → Cancelling RTSP grace period (HA reconnected in time)")
            rtspGracePeriodJob?.cancel()
            rtspGracePeriodJob = null
        }

        // Resume RTSP if it was paused (will get live frames on reconnect due to cache clear)
        if (isRtspPaused) {
            Log.i(TAG, "  → Resuming RTSP server")
            isRtspPaused = false
            onResumeRtsp?.invoke()
        }

        // Cancel WebView grace period if active (HA reconnected before we paused WebView)
        if (gracePeriodJob?.isActive == true) {
            Log.i(TAG, "  → HA reconnected during grace period! Cancelling grace period.")
            DiagnosticBuffer.info("HA_CONN", "Reconnected during grace period (${wasDisconnectedFor}ms)")
            PersistentLog.info("HA_CONN", "Recovered during grace period (${wasDisconnectedFor}ms) — no pause needed")
            gracePeriodJob?.cancel()
            gracePeriodJob = null
            disconnectTime = 0L
            return
        }

        // Reset disconnect tracking
        disconnectTime = 0L

        if (isPaused) {
            // Check if this was a quick recovery
            val isQuickRecovery = wasDisconnectedFor in 1..QUICK_RECOVERY_MS
            val wasSleepDisconnect = disconnectedDuringScreenSleep
            // If the WebView is sitting on a 404 / main-frame error, the no-reload
            // branches below would resume onto a broken page. This is the nightly
            // failure: HA was mid-reboot (e.g. a 4 AM Proxmox VM reboot) while the
            // screen slept, so the disconnect is flagged sleep/quick but the page is
            // a 404 — it MUST reload regardless of sleep/quick state.
            val pageErrored = pageErrorProvider()

            // Clear the flag now that we've consumed it
            disconnectedDuringScreenSleep = false

            if (pageErrored) {
                Log.i(TAG, "  → Page on main-frame error (${wasDisconnectedFor}ms) - resuming WITH reload")
                DiagnosticBuffer.info("HA_CONN", "Page errored on recovery - reloading (was sleep=$wasSleepDisconnect)")
                PersistentLog.info("HA_CONN", "Branch=ERROR_PAGE_RELOAD after ${wasDisconnectedFor}ms (sleep=$wasSleepDisconnect)")
                resumeWebView()
            } else if (wasSleepDisconnect) {
                // Disconnect happened during screen sleep — almost certainly WiFi power
                // management, not a real HA outage. Skip reload to avoid the jarring
                // "page refresh" the user sees on every wake.
                Log.i(TAG, "  → Screen-sleep disconnect (${wasDisconnectedFor}ms) - resuming WITHOUT reload")
                DiagnosticBuffer.info("HA_CONN", "Screen-sleep recovery - no reload (WiFi power mgmt)")
                PersistentLog.info("HA_CONN", "Branch=SLEEP_RECOVERY (no reload) after ${wasDisconnectedFor}ms")
                resumeWebViewWithoutReload()
            } else if (isQuickRecovery) {
                Log.i(TAG, "  → Quick recovery (${wasDisconnectedFor}ms) - resuming WITHOUT reload")
                DiagnosticBuffer.info("HA_CONN", "Quick recovery - no reload needed")
                PersistentLog.info("HA_CONN", "Branch=QUICK_RECOVERY (no reload) after ${wasDisconnectedFor}ms")
                resumeWebViewWithoutReload()
            } else {
                Log.i(TAG, "  → Long outage (${wasDisconnectedFor}ms) - resuming WITH reload")
                DiagnosticBuffer.info("HA_CONN", "Long outage - reloading page")
                PersistentLog.info("HA_CONN", "Branch=LONG_OUTAGE (with reload) after ${wasDisconnectedFor}ms")
                resumeWebView()
            }
        } else {
            Log.i(TAG, "  → Not paused, nothing to do")
        }
    }

    /**
     * Called from JavaScript when auto-reload has given up after max retries.
     * WebSocket never connected despite multiple reloads with exponential backoff.
     * HA is likely down or unreachable.
     *
     * @param reason Why connection failed (e.g., "stuck_initializing_max_retries")
     * @param attempts Number of reload attempts made
     */
    fun onConnectionFailed(reason: String, attempts: Int) {
        Log.e(TAG, "━━━ CONNECTION FAILED ━━━ reason=$reason, attempts=$attempts")
        DiagnosticBuffer.error("HA_CONN", "Connection failed: $reason after $attempts attempts")
        PersistentLog.error("HA_CONN", "JS gave up: reason=$reason after $attempts attempts")

        // The JS side has given up on auto-reload.
        // Our ping loop (if running) will still detect when HA comes back.
        // If we're not already pinging, start now - HA might just be slow to respond.
        if (pingJob?.isActive != true && isEnabled) {
            val url = pingUrl
            if (url != null) {
                Log.i(TAG, "  → JS gave up but starting/continuing ping loop to detect HA recovery")
                DiagnosticBuffer.info("HA_CONN", "Starting ping loop for recovery detection")

                // Don't pause WebView (user may want to see error state)
                // Just start ping loop to detect when HA is back
                startPingLoop(url)
            } else {
                Log.w(TAG, "  → No ping URL configured - cannot monitor for HA recovery")
            }
        } else if (pingJob?.isActive == true) {
            Log.i(TAG, "  → Ping loop already active, will reload when HA responds")
        }

        // Invoke offline callback to show user-facing error
        onHaOffline?.invoke()
    }

    /**
     * Pause the WebView and start pinging HA to detect when it's back.
     */
    private fun pauseAndStartPingLoop(localUrl: String) {
        Log.i(TAG, "pauseAndStartPingLoop() called")
        PersistentLog.warn("HA_CONN", "Pausing WebView and starting ping loop (uiInteraction=$isUiInteractionActive)")
        isPaused = true

        // Log memory state when entering offline mode (for OOM debugging)
        com.dashieapp.Dashie.halite.diagnostics.MemoryMonitor.logMemoryStats()

        // Pause WebView on main thread
        // We call both pauseTimers() AND onPause() to fully stop WebView:
        // - pauseTimers() stops JavaScript timers (HA reconnect spam)
        // - onPause() stops rendering and releases GPU resources
        // However, if UI interaction is active (settings modal open), we skip onPause()
        // to keep the settings modal responsive. onPause() will be called when settings closes.
        webView.post {
            Log.i(TAG, "  → Calling webView.pauseTimers()")
            webView.pauseTimers()

            if (!isUiInteractionActive) {
                Log.i(TAG, "  → Calling webView.onPause()")
                webView.onPause()
                isFullyPaused = true
            } else {
                Log.i(TAG, "  → Skipping webView.onPause() - UI interaction active (settings open)")
                isFullyPaused = false
            }
        }

        // Notify UI to show offline overlay
        Log.i(TAG, "  → Notifying onHaOffline callback")
        onHaOffline?.invoke()

        // RTSP should already be paused by its own grace period (5s < 30s WebView grace)
        // But ensure it's paused if somehow it wasn't
        if (!isRtspPaused) {
            Log.i(TAG, "  → Pausing RTSP server (should already be paused)")
            isRtspPaused = true
            onPauseRtsp?.invoke()
        }

        // Start ping loop
        startPingLoop(localUrl)
    }

    /**
     * Start a coroutine that pings HA every 10 seconds.
     * If a ping loop is already running, this is a no-op to prevent restart spam.
     */
    private fun startPingLoop(localUrl: String) {
        // Don't restart if already running - prevents rapid restart cycles
        if (pingJob?.isActive == true) {
            Log.i(TAG, "Ping loop already running, not restarting")
            return
        }

        pingJob = scope.launch {
            var pingCount = 0
            Log.i(TAG, "━━━ PING LOOP STARTED ━━━ target=$localUrl, interval=${DEFAULT_PING_INTERVAL_MS}ms")
            DiagnosticBuffer.info("HA_CONN", "Starting ping loop to $localUrl")
            PersistentLog.info("HA_CONN", "Ping loop started target=$localUrl interval=${DEFAULT_PING_INTERVAL_MS}ms")

            while (isActive && isPaused) {
                pingCount++
                Log.i(TAG, "Ping #$pingCount starting...")
                val pingStart = System.currentTimeMillis()
                val pingResult = pingHaWithReason(localUrl)
                val pingElapsed = System.currentTimeMillis() - pingStart

                if (pingResult.success) {
                    Log.i(TAG, "━━━ PING SUCCESS ━━━ HA reachable after $pingCount pings!")
                    DiagnosticBuffer.info("HA_CONN", "HA reachable after $pingCount pings")
                    PersistentLog.info("HA_CONN", "Ping #$pingCount SUCCESS in ${pingElapsed}ms — HA reachable")

                    // Resume on main thread. Don't `break` — resumeWebView may be throttled
                    // (RESUME_THROTTLE_MS), in which case isPaused stays true and the while
                    // condition keeps the loop pinging until the throttle clears. On success,
                    // resumeWebView cancels pingJob, which lets the loop exit cleanly.
                    withContext(Dispatchers.Main) {
                        resumeWebView()
                    }
                } else {
                    Log.i(TAG, "Ping #$pingCount FAILED - HA still down, waiting ${DEFAULT_PING_INTERVAL_MS}ms...")
                    // Persist every 6th failure (~once per minute) to avoid log
                    // bloat during multi-hour outages, but always persist the
                    // first failure so we know the outage started.
                    if (pingCount == 1 || pingCount % 6 == 0) {
                        PersistentLog.warn("HA_CONN",
                            "Ping #$pingCount FAILED in ${pingElapsed}ms: ${pingResult.errorClass}: ${pingResult.errorMessage}")
                    }
                }

                delay(DEFAULT_PING_INTERVAL_MS)
            }
            Log.i(TAG, "Ping loop ended (isActive=$isActive, isPaused=$isPaused)")
            PersistentLog.info("HA_CONN", "Ping loop ended after $pingCount pings (isActive=$isActive isPaused=$isPaused)")
        }
    }

    /**
     * Ping HA to check if it's reachable.
     * Uses a simple TCP socket connection to check if the port is open.
     * This avoids HTTP requests that trigger HA's "invalid authentication" warnings.
     */
    private suspend fun pingHa(localUrl: String): Boolean =
        pingHaWithReason(localUrl).success

    /**
     * Ping result with failure attribution. The error class + message
     * help distinguish DNS-resolution failure from connection-refused
     * vs timeout, which all look the same in a boolean return.
     */
    private data class PingResult(
        val success: Boolean,
        val errorClass: String = "",
        val errorMessage: String = ""
    )

    private suspend fun pingHaWithReason(localUrl: String): PingResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(localUrl)
            val host = url.host
            val port = if (url.port != -1) url.port else if (url.protocol == "https") 443 else 80
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), PING_TIMEOUT_MS)
                Log.d(TAG, "Ping successful: $host:$port is reachable")
                PingResult(success = true)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Ping failed: ${e.message}")
            PingResult(
                success = false,
                errorClass = e.javaClass.simpleName,
                errorMessage = e.message ?: "(no message)"
            )
        }
    }

    /**
     * Resume the WebView and reload the page (for long outages).
     */
    private fun resumeWebView() {
        Log.i(TAG, "━━━ RESUME WEBVIEW (with reload) ━━━")
        if (!isPaused) {
            Log.i(TAG, "  → Already not paused, returning early")
            return
        }

        // Don't reload while the user is on HA's login / auth page — a reload
        // wipes whatever they've typed into the username/password fields. During
        // login the HA WebSocket isn't authenticated yet, so the monitor sees
        // the connection flap and would otherwise "recover" with a reload
        // mid-typing (reported: HA login reloads after ~5-6s, losing input).
        // Resume timers without reloading; HA's own WS connects after login.
        val currentUrl = webView.url ?: ""
        if (currentUrl.contains("/auth/authorize") ||
            currentUrl.contains("/auth/") ||
            currentUrl.contains("auth_callback")) {
            Log.i(TAG, "  → On HA auth/login page — resuming WITHOUT reload to preserve the login form")
            resumeWebViewWithoutReload()
            return
        }

        // Throttle: prevent rapid reload loops when page load fails trigger new ping loops
        // This breaks the cycle: onPageLoadFailed → ping → resumeWebView → reload → fail → repeat
        val now = System.currentTimeMillis()
        if (now - lastResumeTime < RESUME_THROTTLE_MS) {
            val elapsed = (now - lastResumeTime) / 1000
            Log.w(TAG, "  → Throttled! Last resume was ${elapsed}s ago (min ${RESUME_THROTTLE_MS/1000}s)")
            DiagnosticBuffer.warn("HA_CONN", "Resume throttled (${elapsed}s since last)")
            PersistentLog.warn("HA_CONN", "Resume throttled (${elapsed}s since last) — staying paused, ping loop will retry")
            return
        }
        lastResumeTime = now

        isPaused = false
        isFullyPaused = false
        pingJob?.cancel()
        pingJob = null
        gracePeriodJob?.cancel()
        gracePeriodJob = null
        rtspGracePeriodJob?.cancel()
        rtspGracePeriodJob = null

        webView.post {
            try {
                Log.i(TAG, "  → Calling webView.onResume()")
                webView.onResume()
                Log.i(TAG, "  → Calling webView.resumeTimers()")
                webView.resumeTimers()
                Log.i(TAG, "  → Calling webView.reload()")
                PersistentLog.info("REFRESH", "WebView reload: HA connection recovered")
                webView.reload()
            } catch (e: Exception) {
                PersistentLog.error("HA_CONN", "WebView resume/reload threw ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // Notify UI to hide offline overlay
        Log.i(TAG, "  → Notifying onHaOnline callback")
        onHaOnline?.invoke()

        // Resume RTSP server if it was paused
        if (isRtspPaused) {
            Log.i(TAG, "  → Resuming RTSP server")
            isRtspPaused = false
            onResumeRtsp?.invoke()
        }
    }

    /**
     * Resume the WebView WITHOUT reload (for quick recoveries).
     * Just resume timers and let HA's native WebSocket reconnect.
     */
    private fun resumeWebViewWithoutReload() {
        Log.i(TAG, "━━━ RESUME WEBVIEW (no reload) ━━━")
        if (!isPaused) {
            Log.i(TAG, "  → Already not paused, returning early")
            return
        }

        isPaused = false
        isFullyPaused = false
        pingJob?.cancel()
        pingJob = null
        gracePeriodJob?.cancel()
        gracePeriodJob = null
        rtspGracePeriodJob?.cancel()
        rtspGracePeriodJob = null

        webView.post {
            try {
                Log.i(TAG, "  → Calling webView.onResume()")
                webView.onResume()
                Log.i(TAG, "  → Calling webView.resumeTimers()")
                webView.resumeTimers()
                PersistentLog.info("HA_CONN", "WebView resumed without reload (native WS reconnect)")
                // No reload - let HA's native WebSocket reconnect gracefully
            } catch (e: Exception) {
                PersistentLog.error("HA_CONN", "WebView resume threw ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // Notify UI to hide offline overlay
        Log.i(TAG, "  → Notifying onHaOnline callback")
        onHaOnline?.invoke()

        // Resume RTSP server if it was paused
        if (isRtspPaused) {
            Log.i(TAG, "  → Resuming RTSP server")
            isRtspPaused = false
            onResumeRtsp?.invoke()
        }
    }

    /**
     * Set screen sleep state. When the screen is off (screensaver or hardware sleep),
     * any HA disconnect is likely caused by WiFi power management, not a real HA outage.
     * On recovery, we skip the full page reload and let HA's WebSocket reconnect natively.
     *
     * Called from HaliteScreenController when screen sleeps/wakes.
     */
    fun setScreenSleeping(sleeping: Boolean) {
        Log.i(TAG, "Screen sleeping: $sleeping")
        PersistentLog.info("HA_CONN", "Screen sleeping=$sleeping " +
            "(isPaused=$isPaused disconnectTime=$disconnectTime " +
            "disconnectedDuringScreenSleep=$disconnectedDuringScreenSleep)")
        isScreenSleeping = sleeping
        if (sleeping) {
            // If we're already disconnected or disconnect while sleeping, mark it
            if (isPaused || disconnectTime > 0) {
                disconnectedDuringScreenSleep = true
                PersistentLog.info("HA_CONN", "Marked disconnectedDuringScreenSleep=true on sleep")
            }
        } else {
            // Screen woke up — flag will be consumed in onHaConnected()
        }
        onScreenSleepListener?.invoke(sleeping)
    }

    /**
     * Set UI interaction state. When true, we skip onPause() during HA outages
     * so the settings modal can still function.
     * Called from DashboardTelemetryBridge when settings opens/closes.
     */
    fun setUiInteractionActive(active: Boolean) {
        Log.i(TAG, "UI interaction active: $active")
        isUiInteractionActive = active

        // If settings closed while HA is offline and we're paused but not fully paused,
        // now it's safe to call onPause() for memory savings
        if (!active && isPaused && !isFullyPaused) {
            Log.i(TAG, "  → Settings closed while offline, now calling onPause()")
            webView.post {
                webView.onPause()
                isFullyPaused = true
            }
        }
    }

    /**
     * Force resume (e.g., user manually requested reload).
     */
    fun forceResume() {
        if (isPaused) {
            Log.i(TAG, "Force resume requested")
            DiagnosticBuffer.info("HA_CONN", "Force resume")
            PersistentLog.info("HA_CONN", "Force resume (manual)")
            resumeWebView()
        }
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        rtspGracePeriodJob?.cancel()
        gracePeriodJob?.cancel()
        pingJob?.cancel()
        scope.cancel()
    }

    /**
     * Get current status for diagnostics.
     */
    fun getStatus(): Map<String, Any> = mapOf(
        "enabled" to isEnabled,
        "isPaused" to isPaused,
        "isRtspPaused" to isRtspPaused,
        "gracePeriodActive" to (gracePeriodJob?.isActive == true),
        "rtspGracePeriodActive" to (rtspGracePeriodJob?.isActive == true),
        "pingUrl" to (pingUrl ?: "not configured"),
        "gracePeriodMs" to GRACE_PERIOD_MS,
        "pingIntervalMs" to DEFAULT_PING_INTERVAL_MS
    )

    /**
     * Get a simple status string for memory logging.
     */
    fun getMemoryStatus(): String {
        val uptimeSecs = if (disconnectTime > 0) {
            (System.currentTimeMillis() - disconnectTime) / 1000
        } else 0
        return when {
            !isEnabled -> "disabled"
            isPaused -> "OFFLINE(${uptimeSecs}s) rtspPaused=$isRtspPaused"
            else -> "online"
        }
    }
}

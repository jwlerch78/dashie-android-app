package com.dashieapp.Dashie

import android.content.Intent
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.dashieapp.Dashie.api.DashieServiceManager
import com.dashieapp.Dashie.halite.DashboardTelemetryBridge
import com.dashieapp.Dashie.halite.DashieWebViewClient
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaliteAuthManager
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.HaliteScreenController
import com.dashieapp.Dashie.halite.NativeDialogHost
import com.dashieapp.Dashie.halite.registry.initializeNetworkMonitor
import com.dashieapp.Dashie.halite.registry.initializeWifiLock
import com.dashieapp.Dashie.halite.SupabaseTokenExtractor
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.ui.ImmersiveModeController
import com.dashieapp.Dashie.webview.DashieJSBridge

/**
 * Initializes the Halite-mode runtime: screen controller callbacks,
 * screensaver panel wiring, the HaliteAuthManager (with cloud-mode startup
 * token injection), keep-alive service, network monitors, return-home
 * navigation, and JS bridge → screen-controller / performance-overlay
 * callbacks.
 *
 * Extracted from MainActivity.onCreate() so the activity owns the
 * orchestration but not the per-callback bodies.
 *
 * Returns the constructed [HaliteAuthManager], which the activity
 * stores as a field for later use (sidebar, login flow, etc.).
 */
object MainHaliteSetup {

    private const val TAG = "MainActivity"

    fun setup(
        activity: MainActivity,
        webViewProvider: () -> WebView,
        halitePrefs: HalitePreferences,
        haliteRegistry: HaliteComponentRegistry,
        haliteScreenControllerProvider: () -> HaliteScreenController?,
        dashieWebViewClientProvider: () -> DashieWebViewClient?,
        dashboardTelemetryBridgeProvider: () -> DashboardTelemetryBridge?,
        dialogHostFn: () -> NativeDialogHost?,
        immersiveModeController: ImmersiveModeController,
        dashieServiceManagerProvider: () -> DashieServiceManager?,
        memoryManagerProvider: () -> MainMemoryManager?,
        haLoginLauncher: ActivityResultLauncher<Intent>
    ): HaliteAuthManager {
        // Initialize screen controller via registry (manages dimmer, motion wake, kiosk settings)
        // Registry was already created by coordinator.initialize()
        haliteRegistry.initializeScreenController()

        // Self-grant Device-Owner power exemptions (no-op unless we are the
        // Device Owner). Keeps the camera/motion foreground service alive under
        // aggressive OEM power management (e.g. Honor MagicOS) while the screen
        // is off. See DeviceAdminHelper.applyDeviceOwnerPowerExemptions().
        com.dashieapp.Dashie.halite.DeviceAdminHelper.applyDeviceOwnerPowerExemptions(activity)

        // Give the session injector a way to reach the shell's WebView (Kiosk Real Login,
        // Phase 2). A PROVIDER, not the instance: recreateWebView() replaces it nightly, and a
        // captured reference would go stale and silently inject into a destroyed view.
        com.dashieapp.Dashie.halite.auth.KioskSessionInjector.attach(webViewProvider)

        val haliteScreenController = haliteScreenControllerProvider()

        // Pass WebView reference for HA Media photo source (token extraction)
        haliteScreenController?.setWebView(webViewProvider())

        // Set up window flag change callback to re-apply immersive mode
        // This prevents the nav bar appearing bug on Fire tablets
        haliteScreenController?.onWindowFlagsChanged = {
            immersiveModeController.reapplyAfterWindowChange()
        }

        // Set up screen dimmed callback to re-arm RTSP motion detection
        // This gives the camera time to stabilize after screen dims
        haliteScreenController?.onScreenDimmedCallback = {
            dashieServiceManagerProvider()?.rearmRtspMotionDetection()
        }

        // Wire motion-wake camera usage to the API service's foreground-service
        // type. Mirrors RTSP's onCameraActiveChanged flow — when motion wake is
        // in camera/face mode, promote the FGS to include `camera` so OEM
        // policies don't revoke camera access after lockNow() backgrounds the
        // app. See DashieServiceManager.setMotionWakeCameraActive() for the
        // union logic with RTSP.
        haliteScreenController?.onMotionWakeCameraStateChanged = { cameraActive ->
            dashieServiceManagerProvider()?.setMotionWakeCameraActive(cameraActive)
        }
        // Re-publish now that the callback is wired. setupMotionWake() may have already
        // run and fired its notify into a null callback (startup ordering), so without
        // this the service never learns the camera is active for motion/face wake and
        // the OS revokes the camera ~6s after lockNow() backgrounds the app.
        haliteScreenController?.publishMotionWakeCameraState()

        // Set up screensaver state change callback for memory recovery timing
        // This allows WARNING-level memory pressure to defer reload until screensaver is active
        haliteScreenController?.onScreensaverStateChanged = { isActive ->
            memoryManagerProvider()?.onScreensaverStateChanged(isActive)

            // Notify screensaver panel coordinator of state change
            // This handles PiP feeds, music player, timers on the screensaver overlay panel
            haliteRegistry.screensaverPanelCoordinator?.onScreensaverStateChanged(isActive)
            if (isActive) {
                // Don't dock music/video/timer cards onto a blank screensaver
                // (screen off/asleep or a black overlay) — there's nothing
                // visible to overlay them on, so a docked card just shows a
                // stray strip over a black screen.
                if (haliteScreenController?.isBlankScreensaver() == true) {
                    Log.i(TAG, "🌙 Screensaver is blank (black/off/sleep) — not docking music/video/timer cards")
                } else {
                    haliteRegistry.videoFeedManager?.onScreensaverActivated()
                    haliteRegistry.musicPlayerManager?.onScreensaverActivated()
                    haliteRegistry.timerOverlayManager?.onScreensaverActivated()
                }
            } else {
                haliteRegistry.videoFeedManager?.onScreensaverDeactivated()
                haliteRegistry.musicPlayerManager?.onScreensaverDeactivated()
                haliteRegistry.timerOverlayManager?.onScreensaverDeactivated()
            }

            // Notify JS sleep timer service when native screensaver deactivates
            // This allows resleep timer to start when user wakes during sleep hours
            if (!isActive && halitePrefs.connection.hasAddonUrl) {
                Log.i(TAG, "🌙 Screensaver deactivated - notifying JS sleep timer via postMessage")
                // Use postMessage to reach the overlay iframe (same pattern as other overlay comms)
                webViewProvider().evaluateJavascript(
                    """
                    (function() {
                        var iframe = document.getElementById('dashie-overlay');
                        if (iframe && iframe.contentWindow) {
                            iframe.contentWindow.postMessage({
                                source: 'dashie-parent',
                                type: 'dashie-call',
                                method: 'onNativeScreensaverDeactivated',
                                args: []
                            }, '*');
                        }
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }

        // Wire screensaver panel coordinator → ScreenDimmer resize callback
        haliteRegistry.screensaverPanelCoordinator?.onResizeScreensaver = { active, feedAreaWidth ->
            haliteScreenControllerProvider()?.screenDimmer?.setVideoFeedMode(active, feedAreaWidth)
        }
        // Narrow-portrait: shrink foreground photos to fit above the music/camera dock
        haliteRegistry.screensaverPanelCoordinator?.onScreensaverBottomInset = { bottomPanelHeightPx ->
            haliteScreenControllerProvider()?.screenDimmer?.setBottomPanelInset(bottomPanelHeightPx)
        }
        // Narrow-portrait photo-alone: fit-with-bars so the whole image shows centered.
        haliteRegistry.screensaverPanelCoordinator?.onScreensaverPhotoAlone = { alone ->
            haliteScreenControllerProvider()?.screenDimmer?.setScreensaverPhotoAlone(alone)
        }

        // MIGRATION: Disabled HTML screensaver - now using native Kotlin PhotoSlideshowView for performance
        // WebView overlay still used for settings modal and service logic (timers, sleep timer, AI)
        // TODO: Build Kotlin TimerOverlayView to replace HTML timer rendering
        Log.i(TAG, "📱 Using native Kotlin screensaver (HTML screensaver disabled for performance)")

        // Start proactive overlay refresh scheduler (if enabled and addon configured)
        // This helps prevent memory leaks in the WebView service layer
        if (halitePrefs.connection.hasAddonUrl) {
            memoryManagerProvider()?.startProactiveRefreshScheduler()
        }

        // Initialize auth manager (manages native HA login for Fire tablets)
        val haliteAuthManager = HaliteAuthManager(
            activity = activity,
            webViewProvider = webViewProvider,
            halitePrefs = halitePrefs,
            haLoginLauncher = haLoginLauncher
        )

        // Cloud-mode startup: if the user already has saved HA tokens
        // (e.g. they logged in previously, then closed/reopened the app),
        // queue iframe token injection now so the dashboard's HA widget
        // gets authenticated when its iframe loads. Without this, the
        // widget shows HA's login page on every cold start until the
        // user re-runs the OAuth flow. Kiosk-mode has its own paths
        // (injectSavedTokensAndLoad in onboarding) so we skip that case.
        run {
            val isCloudMode = halitePrefs.account.isLinked
            val haUrl = halitePrefs.connection.haUrl
            val accessToken = halitePrefs.connection.haAccessToken
            val refreshToken = halitePrefs.connection.haRefreshToken
            val urlValid = haUrl.isNotEmpty() && haUrl != HalitePreferences.DEFAULT_HA_URL
            if (isCloudMode && urlValid && accessToken.isNotEmpty()) {
                // Single source of truth — clientId/hassUrl from the canonical auth
                // origin (proxy when configured), matching mint + refresh.
                val tokenJson = halitePrefs.connection.buildHassTokensJson()
                if (tokenJson != null) {
                    haliteAuthManager.setIframeTokenInjection(tokenJson)
                    val origin = halitePrefs.connection.getAuthOrigin()
                    Log.i(TAG, "🏠 Cloud-mode startup: iframe token injection queued from saved tokens (origin=$origin)")
                    PersistentLog.info(
                        "AUTH",
                        "startup iframe injection queued (cloudMode, origin=$origin, tokenLen=${accessToken.length})"
                    )
                    // Arm the proactive token-refresh timer on the SILENT saved-token
                    // cold-start path too. Previously startProactiveTokenRefresh() only
                    // ran after an interactive OAuth login (auth_ok / onHaLoginCompleted),
                    // so a device that reopens with saved tokens never refreshed: its HA
                    // access token expired at ~30min and the iframe's HA frontend then
                    // 401'd every cycle (HA "invalid authentication" ban-log spam, with
                    // zero proactive-refresh entries in PersistentLog). Re-runs on every
                    // WebView recreate, since MainHaliteSetup.setup() does.
                    dashboardTelemetryBridgeProvider()?.authDelegate?.startProactiveTokenRefresh()
                    PersistentLog.info("AUTH", "proactive refresh armed on silent cold-start")
                }
            }
        }

        haliteAuthManager.callback = object : HaliteAuthManager.AuthCallback {
            override fun onHaLoginCompleted() {
                Log.i(TAG, "📢 onHaLoginCompleted callback")
                haliteScreenControllerProvider()?.onHaLoginCompleted()
                // Only show old welcome/permission dialogs if NOT in onboarding
                if (halitePrefs.connection.isSetupComplete) {
                    dialogHostFn()?.showWelcomeToast(confirmedLogin = true)
                }
                // Extract Supabase JWT for authenticated Kotlin edge function calls (works with or without HA)
                SupabaseTokenExtractor.extractAndCache(webViewProvider(), halitePrefs.connection) { success ->
                    Log.i(TAG, "Supabase JWT extraction: $success")
                }
                // NOTE (Kiosk Real Login): the kiosk's session is NOT provisioned here. This
                // callback rides on the DASHBOARD WebView loading a non-auth page, which a kiosk
                // shell never does — verified on-device: it never fires for a kiosk. Provisioning
                // + native JWT refresh hang off HaTokenExtractor instead, which is the moment a
                // kiosk actually gains HA credentials.
                // Start proactive token refresh timer (refreshes token before expiry to prevent auth_invalid)
                dashboardTelemetryBridgeProvider()?.authDelegate?.startProactiveTokenRefresh()
            }
            override fun onReconfigureRequested() {
                // D.83 — Route to the SettingsActivity HA-URL fragment instead
                // of the deleted HaUrlSetupActivity (legacy kiosk-only setup
                // shell). This is the same surface Settings → Home Assistant →
                // Dashboard URL navigates to.
                val intent = Intent(
                    activity,
                    com.dashieapp.Dashie.halite.settings.SettingsActivity::class.java
                ).putExtra("navigate_to", "home_assistant_url")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                activity.startActivity(intent)
                activity.finish()
            }
            override fun onMaxLoginAttemptsReached() {
                Log.w(TAG, "⚠️ Max login attempts reached - showing error message")
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        "Unable to login after 3 attempts.\nPlease check your connection and use the sidebar menu to reconfigure.\n(Swipe from right edge or press Menu button)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // Wire up WebView client providers (auth manager and telemetry needed for URL interception)
        dashieWebViewClientProvider()?.apply {
            authManagerProvider = { haliteAuthManager }
            telemetryBridgeProvider = dashboardTelemetryBridgeProvider
            dialogHostProvider = dialogHostFn
            focusRingProvider = { haliteRegistry.focusRingManager }
        }

        // Start keep-alive foreground service (prevents MIUI/EMUI from killing the process).
        // DEFERRED off the synchronous cold-start path: onStartCommand() — which calls
        // startForeground() — runs on the main thread, so starting the FGS here queues it
        // behind the rest of the heavy cold-start work. On slow devices / debug builds that
        // can exceed Android's 5s startForeground deadline and crash the process with
        // ForegroundServiceDidNotStartInTimeException (which crash-loops when Dashie is the
        // home app). Posting with a short delay lets cold start finish so the service starts
        // cleanly. A few seconds' delay is harmless — the service only matters once the
        // screen goes off.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            com.dashieapp.Dashie.halite.DashieKeepAliveService.start(activity.applicationContext)
        }, 4000)

        // Initialize WiFi Lock Manager early via registry (needed for keeping connections alive)
        haliteRegistry.initializeWifiLock()

        // Initialize Network Monitor to restart RTSP when WiFi reconnects
        haliteRegistry.initializeNetworkMonitor()

        // Setup screen dimmer early (needed for screen control callbacks)
        haliteScreenController?.setup()

        // Wire return-to-home timer: navigates WebView to configured dashboard URL
        haliteScreenController?.onReturnHome = {
            activity.runOnUiThread {
                // Respect account-linked state: return to dashieapp.com when linked,
                // otherwise return to the configured HA dashboard
                val homeUrl = if (halitePrefs.account.showsDashboard) {
                    halitePrefs.account.dashieUrl
                } else {
                    halitePrefs.connection.buildFullUrl()
                }
                val currentUrl = webViewProvider().url ?: ""
                // Skip navigation if already on the home dashboard (or a sub-view of it)
                // Also skip if on the kiosk shell page (HA is in the shell's iframe)
                val isKioskShell = currentUrl.contains("/kiosk-shell.html")
                val isOnDashie = currentUrl.contains("dashieapp.com")
                if (currentUrl.startsWith(homeUrl) || isKioskShell || (halitePrefs.account.isLinked && isOnDashie)) {
                    Log.d(TAG, "🏠 Return-to-home: already on home dashboard, skipping reload")
                } else {
                    Log.i(TAG, "🏠 Return-to-home: navigating to $homeUrl (was: $currentUrl)")
                    webViewProvider().loadUrl(homeUrl)
                }
            }
        }
        haliteScreenController?.startReturnHomeTimer()

        // Wire ha_page screensaver: SPA-navigate within HA (no reload)
        haliteScreenController?.onHaPageNavigate = { pagePath ->
            activity.runOnUiThread {
                val navPath = if (pagePath != null) {
                    // Activation: navigate to HA page path
                    "/${pagePath.trimStart('/')}"
                } else {
                    // Deactivation: navigate back to home dashboard path
                    val dashboard = halitePrefs.connection.dashboardName.trim().trimStart('/')
                    if (dashboard.isNotEmpty()) "/$dashboard" else "/"
                }
                Log.i(TAG, "🏠 HA Page screensaver: SPA-navigating to $navPath")

                // Use HA's internal router: pushState + location-changed event (no reload)
                val navJs = "history.pushState(null,null,'$navPath');" +
                    "window.dispatchEvent(new CustomEvent('location-changed'));"
                val escapedNavJs = navJs.replace("\\", "\\\\").replace("'", "\\'")

                // In kiosk shell, HA is in an iframe — use evalInHaIframe bridge.
                // In direct mode (logged-in), execute on the WebView directly.
                val js = "if(typeof evalInHaIframe==='function'){evalInHaIframe('$escapedNavJs')}else{$navJs}"
                webViewProvider().evaluateJavascript(js, null)
            }
        }

        // NOTE: jsBridge-level callbacks (perf overlay, thresholds, JWT-saved,
        // return-to-home) are wired in MainActivity.applyJsBridgeCallbacks() /
        // createJsBridgeCallbacks() — NOT here. Assignments made here are one-shot
        // and silently die when the nightly memory-recovery WebView recreation
        // rebuilds the bridge.

        return haliteAuthManager
    }

    /**
     * (Re)apply the halite-owned jsBridge-level callbacks. Invoked from
     * MainActivity.applyJsBridgeCallbacks() — i.e. at initial wiring AND after
     * every WebView memory-recovery recreation. These used to be one-shot
     * assignments at the end of setup(), which only re-runs on Activity
     * recreate, so the nightly recreation silently killed them (perf-overlay
     * toggle no-oped; the JS "JWT saved" ping stopped triggering token
     * extraction / photo-widget reconfigure).
     */
    fun applyBridgeCallbacks(
        bridge: DashieJSBridge,
        activity: MainActivity,
        halitePrefs: HalitePreferences?,
        haliteRegistry: HaliteComponentRegistry?,
        webViewProvider: () -> WebView
    ) {
        // Performance overlay toggle (Kotlin-based overlay)
        bridge.onPerformanceOverlayChanged = { enabled ->
            Log.i(TAG, "📊 Performance overlay toggle: $enabled")
            activity.runOnUiThread {
                if (enabled) haliteRegistry?.performanceOverlayManager?.show()
                else haliteRegistry?.performanceOverlayManager?.hide()
            }
        }

        bridge.onThresholdsChanged = {
            activity.runOnUiThread {
                haliteRegistry?.performanceOverlayManager?.refreshThresholds()
            }
        }

        // JS-driven JWT-saved notification: when EdgeClient persists a fresh
        // Supabase JWT to localStorage it pings us so we can pull it into
        // ConnectionPreferences right away — the other extraction triggers
        // (dashboard onPageFinished, HA login completion) can fire before the
        // token exists, or not at all for non-HA users. Once the JWT lands,
        // also reconfigure the Dashie Cloud photo widget if it's the active
        // source: it reads hasSupabaseJwt=false early in startup, falls into
        // the empty state, and never re-evaluates on its own.
        bridge.authStateDelegate?.onSupabaseJwtSaved = {
            val connectionPrefs = halitePrefs?.connection
            if (connectionPrefs != null) {
                activity.runOnUiThread {
                    SupabaseTokenExtractor.extractAndCache(webViewProvider(), connectionPrefs) { success ->
                        Log.i(TAG, "Supabase JWT extraction (JS-triggered): $success")
                        if (success) {
                            val controller = haliteRegistry?.photoWidgetController
                            if (controller?.currentSourceType() == "supabase") {
                                Log.i(TAG, "Reconfiguring photo widget after JWT extraction (source=supabase)")
                                controller.reconfigure()
                            }
                        }
                    }
                }
            }
        }
    }
}

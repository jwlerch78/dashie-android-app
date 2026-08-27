package com.dashieapp.Dashie

import android.app.Activity
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dashieapp.Dashie.devicecontrols.DeviceControlsCoordinator
import com.dashieapp.Dashie.api.DashieServiceManager
import com.dashieapp.Dashie.halite.DashboardHealthCoordinator
import com.dashieapp.Dashie.halite.DashboardTelemetryBridge
import com.dashieapp.Dashie.halite.DashieWebChromeClient
import com.dashieapp.Dashie.halite.DashieWebViewClient
import com.dashieapp.Dashie.halite.DeviceMetricsInterface
import com.dashieapp.Dashie.halite.HaConnectionMonitor
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.rtsp.RtspPlayerManager
import com.dashieapp.Dashie.sidebar.NativeSidebarController
import com.dashieapp.Dashie.voice.VoiceAssistantCoordinator
import com.dashieapp.Dashie.webview.DashieJSBridge
import com.dashieapp.Dashie.webview.FullscreenModeManager

/**
 * Bootstraps the WebView's runtime: client, chrome client, dark mode,
 * JS bridge, telemetry bridges, and HA connection monitor. Extracted
 * from MainActivity.onCreate() to keep that function focused on
 * orchestration rather than per-component setup.
 *
 * Returns the constructed components in [Result]; MainActivity assigns
 * them to its fields. Components that depend on `BuildConfig.ALLOW_URL_CONFIG`
 * being on (everything except the JS bridge and chrome client) are null
 * in the standard-Dashie path.
 */
object MainWebViewBootstrap {

    private const val TAG = "MainActivity"

    data class Result(
        val darkModeHandler: MainDarkModeHandler?,
        val dashieWebViewClient: DashieWebViewClient?,
        val jsBridge: DashieJSBridge,
        val dashboardTelemetryBridge: DashboardTelemetryBridge?,
        val deviceMetricsInterface: DeviceMetricsInterface?,
        val haConnectionMonitor: HaConnectionMonitor?,
        val haDisconnectedIndicator: MainHaDisconnectedIndicator?,
        val dashboardHealthCoordinator: DashboardHealthCoordinator?
    )

    fun setup(
        activity: Activity,
        webView: WebView,
        splashOverlay: View,
        halitePrefs: HalitePreferences?,
        allowUrlConfig: Boolean,
        haliteRegistryProvider: () -> HaliteComponentRegistry?,
        timerManagerProvider: () -> com.dashieapp.Dashie.halite.timer.TimerOverlayManager?,
        fullscreenModeManager: FullscreenModeManager,
        nativeSidebarControllerProvider: () -> NativeSidebarController?,
        deviceControlsCoordinator: DeviceControlsCoordinator,
        rtspPlayerManagerProvider: () -> RtspPlayerManager?,
        voiceAssistantProvider: () -> VoiceAssistantCoordinator?,
        webViewRecreationProvider: () -> MainWebViewRecreation?,
        memoryManagerProvider: () -> MainMemoryManager?,
        dashieServiceManagerProvider: () -> DashieServiceManager?,
        haLoginHandler: MainHaLoginHandler,
        haKioskCssInjector: MainHaKioskCssInjector,
        serviceManagerProvider: () -> MainServiceManager?,
        lifecycleHandlerProvider: () -> MainLifecycleHandler?,
        kioskControllerProvider: () -> MainKioskController?,
        showTrialConfirmationDialog: (Int) -> Unit,
        showAccountAlreadyExistsDialog: () -> Unit,
        callbacksFactory: () -> DashieJSBridge.Callbacks
    ): Result {
        // Configure WebView settings (extracted to MainWebViewSetup)
        val webViewSetup = MainWebViewSetup(activity)
        webViewSetup.configure(webView, allowUrlConfig)

        // Clear WebView focus to prevent keyboard popup on activity start/recreate
        if (allowUrlConfig) {
            webViewSetup.clearFocusAndHideKeyboard(webView)
            activity.window.decorView.post { lifecycleHandlerProvider()?.hideKeyboard() }
        }

        // Halite: Enable WebView to follow system dark/light mode automatically
        val darkModeHandler = if (allowUrlConfig) {
            MainDarkModeHandler(activity, webView, deviceControlsCoordinator, activity.packageName).also {
                it.enableSystemDarkModeSupport(activity.resources.configuration)
            }
        } else null

        // Setup WebView client (extracted to DashieWebViewClient)
        val dashieWebViewClient: DashieWebViewClient? = if (allowUrlConfig && halitePrefs != null) {
            DashieWebViewClient(halitePrefs, splashOverlay).apply {
                onPageLoadStarted = {
                    // Top-level WebView navigation — drop gate to OFF so all
                    // registered surfaces hide for the duration of the page load.
                    // The next setUiMode call (from JS bootstrap or Kotlin's
                    // onKioskShellLoaded) restores the correct mode. We anchor
                    // here rather than the JS-driven notifyPageUnloading because
                    // main-frame onPageStarted fires exactly once per top-level
                    // navigation — JS-driven signals fire multiple times per
                    // bootstrap and would cause flicker.
                    haliteRegistryProvider()?.nativeWidgetVisibilityGate?.onTopLevelNavigationStarted()
                    // Cancel all in-flight timers + alarm. Without this, an
                    // alarm that started before the reload keeps playing
                    // (in-process AudioTrack survives) while the timer cards
                    // get gate-hidden and never re-appear — user has no way
                    // to silence it short of voice command. See
                    // _TECHNICAL_DEBT.md "Timer Persistence Across App Restart".
                    timerManagerProvider()?.cancelAllTimers()
                    // Drop any held fullscreen tokens — JS that entered fullscreen
                    // can't possibly call exit across a page reload, so this is the
                    // safety net that prevents stuck-hidden overlays.
                    // (Previously also called setBackdropNeutral(true) here to
                    // avoid a brand-color flash next to the blank reloading
                    // WebView, paired with a restore on onDashboardReady. That
                    // pair stranded the strip in neutral-black after stealth
                    // reloads where JS bootstrap didn't re-run — see the
                    // sidebar panel for the symmetric pattern: themed color
                    // set once and never cleared on reload.)
                    fullscreenModeManager.clearAll()
                }
                onPageLoadComplete = {
                    // Apply zoom settings after page loads
                    kioskControllerProvider()?.applyInitialDashboardZoom()
                    // Push Android's stored dark mode to the webapp so the theme matches
                    // the native sidebar/controls on startup. Only needed in kiosk mode where
                    // Kotlin is the source of truth for dark mode. In widgets mode (dashieapp.com),
                    // the webapp's Supabase-synced theme preference is the source of truth —
                    // pushing here would override the user's saved light/dark choice on every
                    // page load (including iframe sub-navigations that re-trigger onPageFinished).
                    val isDashieMode = halitePrefs.account.showsDashboard
                    if (!isDashieMode) {
                        val isDark = deviceControlsCoordinator.isSystemDarkMode()
                        val darkBool = if (isDark) "true" else "false"
                        Log.i(TAG, "🌓 onPageLoadComplete: pushing dark mode isDark=$isDark to webapp (kiosk mode)")
                        webView.post {
                            webView.evaluateJavascript(
                                "if(window.onColorSchemeChanged) window.onColorSchemeChanged($darkBool);",
                                null
                            )
                        }
                    } else {
                        Log.i(TAG, "🌓 onPageLoadComplete: skipping dark mode push (widgets mode — webapp theme is source of truth)")
                    }
                    // Re-sync native sidebar state after page reload to prevent
                    // ghost sidebar (e.g., after navigating to camera URL and
                    // back to dashboard). Skip in widgets mode — the visibility
                    // gate handles show/hide timing, and the JS layout canvas
                    // reads the pinned state directly via DashieNative.
                    if (!isDashieMode) {
                        nativeSidebarControllerProvider()?.applySidebarVisibility()
                    }
                }
                // Set up crash recovery handler - allows WebView to recover without killing the app
                onRendererCrashRecovery = { crashedWebView ->
                    webViewRecreationProvider()?.handleWebViewCrashRecovery(crashedWebView) ?: false
                }
                // Track URL changes for the Dashie API (includes SPA navigation via History API)
                onUrlChanged = { url ->
                    dashieServiceManagerProvider()?.setCurrentUrl(url)
                }
                // Restore HA view/path after memory reload completes
                onMemoryReloadComplete = {
                    memoryManagerProvider()?.restoreHaPathIfPending()
                }
                hasPendingHaRestore = {
                    memoryManagerProvider()?.hasPendingRestore == true
                }
                // Inline auth callback handler (non-Fire devices): intercepts auth_callback
                // URL in the main WebView and exchanges the code for tokens
                inlineAuthCallbackHandler = { url ->
                    haLoginHandler.handleInlineAuthCallback(url)
                }
            }.also { webView.webViewClient = it }
        } else {
            // Non-halite: use WebViewClient that hides splash and injects HA kiosk CSS
            // Uses shouldInterceptRequest to inject CSS into HA HTML responses (iframe content)
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    splashOverlay.animate()
                        .alpha(0f)
                        .setDuration(300)
                        .withEndAction { splashOverlay.visibility = View.GONE }
                        .start()

                    // Start SSDP discovery service for standard Dashie
                    // (allows Home Assistant to auto-discover this device)
                    serviceManagerProvider()?.setupSsdpService()
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    // Intercept HA HTML responses to inject kiosk CSS (delegated to extracted class)
                    return haKioskCssInjector.interceptHaHtmlForKioskCss(request)
                }
            }
            null
        }

        // Forward JavaScript console messages to logcat for debugging
        // Also handles window.open() - auth URLs stay in WebView, others open in browser
        val chromeClient = DashieWebChromeClient(activity)
        chromeClient.setParentWebView(webView)  // For loading auth URLs from window.open()
        webView.webChromeClient = chromeClient

        // Create JavaScript bridge with callbacks (shared with WebView recreation)
        val jsBridge = DashieJSBridge(
            context = activity,
            webView = webView,
            voiceAssistant = voiceAssistantProvider,
            deviceControls = deviceControlsCoordinator,
            halitePrefs = { halitePrefs },
            rtspPlayerManager = rtspPlayerManagerProvider,
            callbacks = callbacksFactory()
        )
        webView.addJavascriptInterface(jsBridge, "DashieNative")

        // Register the WebView with HaTokenExtractor so background callers
        // (FrigatePlaybackController, sensor publisher, etc.) can fall back to
        // re-extracting HA tokens from localStorage when their cached refresh
        // token is rejected by HA. Done here so the ref is available from app
        // startup — not only after the first ensureToken/extract call.
        com.dashieapp.Dashie.halite.HaTokenExtractor.setWebView(webView)

        // Hide native UI synchronously the moment any Kotlin caller
        // invokes webView.reload(). Catches every Kotlin-driven reload
        // site without instrumenting each one (HaConnectionMonitor,
        // AuthDelegate, SidebarCacheManager, etc.). Pagehide listener
        // in JS handles JS-driven reloads as a parallel path. Both
        // resolve to the same underlying notifyPageUnloading actions.
        if (webView is com.dashieapp.Dashie.webview.DashieWebView) {
            webView.onReloadStarting = {
                fullscreenModeManager.notifyPageUnloading()
            }
        }

// Wire subscription trial confirmation callback
        jsBridge.subscriptionDelegate.onShowTrialConfirmation = { days ->
            activity.runOnUiThread { showTrialConfirmationDialog(days) }
        }

        // Wire "account already exists" notice — fires from
        // subscription-sync.js when the user clicks Sign Up but their
        // email is already a Dashie account.
        jsBridge.subscriptionDelegate.onShowAccountAlreadyExistsNotice = {
            activity.runOnUiThread { showAccountAlreadyExistsDialog() }
        }

        // Halite: Setup dashboard telemetry bridge (opt-in performance data)
        if (!allowUrlConfig || halitePrefs == null) {
            return Result(
                darkModeHandler = darkModeHandler,
                dashieWebViewClient = dashieWebViewClient,
                jsBridge = jsBridge,
                dashboardTelemetryBridge = null,
                deviceMetricsInterface = null,
                haConnectionMonitor = null,
                haDisconnectedIndicator = null,
                dashboardHealthCoordinator = null
            )
        }

        val dashboardTelemetryBridge = DashboardTelemetryBridge(activity, webView, halitePrefs)
        webView.addJavascriptInterface(dashboardTelemetryBridge, "DashieBridge")
        Log.i(TAG, "📊 Dashboard telemetry bridge initialized")

        // Setup Device Metrics Interface (Camera Card adaptive streaming)
        val deviceMetricsInterface = DeviceMetricsInterface(activity, webView).apply {
            rtspPlayerManager = rtspPlayerManagerProvider()  // Wire up native RTSP player
        }
        webView.addJavascriptInterface(deviceMetricsInterface, "dashieDevice")
        Log.i(TAG, "📷 Device metrics interface initialized (window.dashieDevice, RTSP=${rtspPlayerManagerProvider() != null})")

        // Setup HA Connection Monitor (smart reconnection to prevent IP bans)
        // Skip when Dashie account is linked (unless force-kiosk override is active)
        if (halitePrefs.account.showsDashboard) {
            Log.i(TAG, "🔐 Dashie account linked — skipping HA connection monitor")
            return Result(
                darkModeHandler = darkModeHandler,
                dashieWebViewClient = dashieWebViewClient,
                jsBridge = jsBridge,
                dashboardTelemetryBridge = dashboardTelemetryBridge,
                deviceMetricsInterface = deviceMetricsInterface,
                haConnectionMonitor = null,
                haDisconnectedIndicator = null,
                dashboardHealthCoordinator = null
            )
        }

        val haConnectionMonitor = HaConnectionMonitor(webView, halitePrefs)
        dashboardTelemetryBridge.connectionDelegate.haConnectionMonitor = haConnectionMonitor
        dashieWebViewClient?.haConnectionMonitorProvider = { haConnectionMonitor }

        // Dashboard health coordinator — native owner of HA iframe-content recovery
        // (the screen-off window where JS timers freeze). Reuses smartReconnect as
        // its kill-switch. See .reference/_TECHNICAL_DEBT.md → "WebView / HA Recovery".
        val dashboardHealthCoordinator = DashboardHealthCoordinator(
            reloadIframe = {
                webView.evaluateJavascript(
                    // WEBAPP-EXEMPT: dashieReloadHaIframe — kiosk-overlay HA-iframe control; no HA iframe in full mode
                    "window.dashieReloadHaIframe && window.dashieReloadHaIframe()", null
                )
            },
            // Escalation: full page reload rebuilds the kiosk shell + HA iframe fresh,
            // recovering states an in-place iframe reload can't (the field outage).
            reloadPage = { webView.reload() },
            isEnabled = { halitePrefs.performance.smartReconnectEnabled },
            // Debug builds recover fast so the test rig runs in seconds. Prod timing
            // (15s/60s) is verified by DashboardHealthCoordinatorTest.
            firstBackoffMs = if (com.dashieapp.Dashie.BuildConfig.DEBUG) 3_000L
                else DashboardHealthCoordinator.DEFAULT_FIRST_BACKOFF_MS,
            maxBackoffMs = if (com.dashieapp.Dashie.BuildConfig.DEBUG) 6_000L
                else DashboardHealthCoordinator.DEFAULT_MAX_BACKOFF_MS
        )
        jsBridge.dashboardHealthCoordinatorProvider = { dashboardHealthCoordinator }
        // Same coordinator, reachable from non-JS-bridge wiring — the WS keepalive
        // needs it to escalate a sustained no-HA-context streak (a broken shell
        // document) into recovery instead of just stopping. Provider, so a WebView
        // recreation swaps the instance underneath rather than leaving a stale one.
        haliteRegistryProvider()?.dashboardHealthCoordinatorProvider = { dashboardHealthCoordinator }
        // Phase 1a: the WebViewClient arms/cancels the post-load liveness verify via this.
        dashieWebViewClient?.healthCoordinatorProvider = { dashboardHealthCoordinator }
        haConnectionMonitor.onScreenSleepListener = { sleeping ->
            dashboardHealthCoordinator.onScreenSleepChanged(sleeping)
        }
        // Iframe content errors now also drive the resume-with-reload-on-wake path.
        haConnectionMonitor.pageErrorProvider = {
            dashieWebViewClient?.retryManager?.hadMainFrameError == true ||
                dashboardHealthCoordinator.isContentErrored()
        }
        Log.i(TAG, "🔌 HA Connection Monitor initialized (enabled=${haConnectionMonitor.isEnabled})")

        // Setup HA Disconnected Indicator (extracted to MainHaDisconnectedIndicator)
        val haDisconnectedIndicator = MainHaDisconnectedIndicator(
            activity.resources,
            { id -> activity.findViewById(id) },
            haliteRegistryProvider()?.nativeWidgetVisibilityGate
        ).apply { initialize() }
        haConnectionMonitor.onHaOffline = {
            activity.runOnUiThread { haDisconnectedIndicator.show() }
        }
        haConnectionMonitor.onHaOnline = {
            activity.runOnUiThread { haDisconnectedIndicator.hide() }
        }
        Log.i(TAG, "🏠 HA Disconnected Indicator initialized (WebSocket-based detection)")

        return Result(
            darkModeHandler = darkModeHandler,
            dashieWebViewClient = dashieWebViewClient,
            jsBridge = jsBridge,
            dashboardTelemetryBridge = dashboardTelemetryBridge,
            deviceMetricsInterface = deviceMetricsInterface,
            haConnectionMonitor = haConnectionMonitor,
            haDisconnectedIndicator = haDisconnectedIndicator,
            dashboardHealthCoordinator = dashboardHealthCoordinator
        )
    }
}

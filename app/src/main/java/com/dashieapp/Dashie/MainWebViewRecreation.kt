package com.dashieapp.Dashie

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebView
import androidx.activity.ComponentActivity
import com.dashieapp.Dashie.halite.DashboardTelemetryBridge
import com.dashieapp.Dashie.halite.HaliteAuthManager
import com.dashieapp.Dashie.halite.DashieWebViewClient
import com.dashieapp.Dashie.halite.HaConnectionMonitor
import com.dashieapp.Dashie.halite.DeviceMetricsInterface
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.webview.DashieJSBridge
import com.dashieapp.Dashie.halite.DashieWebChromeClient

/**
 * Handles WebView recreation for memory recovery and crash recovery.
 *
 * WebView recreation is the ONLY way to truly release Chromium's native memory
 * (renderer, GPU textures, internal buffers). This class manages the complex
 * process of destroying the old WebView, creating a new one, and rewiring
 * all components.
 *
 * @param activity The activity context
 * @param coordinator The coordinator holding component references
 * @param callbacks Callbacks for accessing and updating components
 */
class MainWebViewRecreation(
    private val activity: ComponentActivity,
    private val coordinator: MainActivityCoordinator,
    private val callbacks: Callbacks
) {
    companion object {
        private const val TAG = "DashieAuth"
    }

    interface Callbacks {
        // Read-only accessors for components not on coordinator
        fun getMemoryManager(): MainMemoryManager?
        fun getKioskController(): MainKioskController?
        fun getVoiceSetup(): MainVoiceSetup?
        fun getLifecycleHandler(): MainLifecycleHandler?

        // Mutable components (recreated during WebView recreation)
        fun getDarkModeHandler(): MainDarkModeHandler?
        fun setDarkModeHandler(handler: MainDarkModeHandler?)
        fun setDashieWebViewClient(client: DashieWebViewClient?)
        fun setJsBridge(bridge: DashieJSBridge?)
        fun setDashboardTelemetryBridge(bridge: DashboardTelemetryBridge?)
        fun setDeviceMetricsInterface(dmi: DeviceMetricsInterface?)
        fun setHaConnectionMonitor(monitor: HaConnectionMonitor?)
        fun getDashboardHealthCoordinator(): com.dashieapp.Dashie.halite.DashboardHealthCoordinator?
        fun setDashboardHealthCoordinator(coordinator: com.dashieapp.Dashie.halite.DashboardHealthCoordinator?)

        // Live getters for the recreated WebViewClient's providers. These MUST
        // read the same fields the setters above write (MainActivity-scoped).
        // The recreated client previously read coordinator.* instead, which
        // always returned null because those coordinator fields are never
        // assigned — leaving the recreated client with a null telemetry
        // bridge / HA monitor / auth manager after every WebView recreation.
        fun getTelemetryBridge(): DashboardTelemetryBridge?
        fun getHaConnectionMonitor(): HaConnectionMonitor?
        fun getAuthManager(): HaliteAuthManager?
        fun setInputHandler(handler: MainInputHandler)
        fun setUrlHandler(handler: MainUrlHandler)
        fun setWebView(webView: WebView)

        // Factories - create callback objects using MainActivity-scoped references
        fun createJsBridgeCallbacks(): DashieJSBridge.Callbacks
        fun createInputHandlerCallbacks(): MainInputHandler.Callbacks
        fun createUrlHandlerCallbacks(): MainUrlHandler.Callbacks
    }

    // Convenience accessors
    private val halitePrefs get() = coordinator.halitePrefs
    private val haliteScreenController get() = coordinator.haliteScreenController
    private val dialogHost get() = coordinator.haliteRegistry?.dialogHost
    private val dashieServiceManager get() = coordinator.dashieServiceManager

    // ============================================================
    // Crash Recovery
    // ============================================================

    /**
     * Handle WebView renderer crash by restarting the Activity.
     *
     * We chose Activity restart over in-place WebView recreation because:
     * 1. Recreating WebView requires re-wiring many components
     * 2. Some state may be corrupted after renderer crash
     * 3. Activity restart provides a guaranteed clean state
     *
     * @param crashedWebView The WebView whose renderer crashed
     * @return true if recovery was initiated, false to allow default termination
     */
    fun handleWebViewCrashRecovery(crashedWebView: WebView?): Boolean {
        if (!BuildConfig.ALLOW_URL_CONFIG) {
            return false
        }

        return try {
            Log.i(TAG, "🔄 Starting WebView crash recovery via Activity restart...")
            PersistentLog.info("WEBVIEW", "Initiating crash recovery - restarting Activity")
            // Lifecycle audit: record the crash recovery event so future crash reports
            // show (a) whether renderer recovery fired, (b) how soon after the crash the
            // Activity restart completed, and (c) which URL we tried to restore to.
            val crashUrl = (crashedWebView?.url ?: halitePrefs?.performance?.lastHaIframeUrl ?: "")
                .take(60)
            com.dashieapp.Dashie.halite.diagnostics.CrashDiagnosticsCollector.persistLifecycleEvent(
                activity.applicationContext,
                "webview_renderer_crashed",
                "url=$crashUrl"
            )

            // Stop RTSP server BEFORE restart to prevent resource contention
            try {
                Log.i(TAG, "📹 Stopping RTSP server before crash recovery...")
                dashieServiceManager?.stopRtspServer()
                DiagnosticBuffer.info("RTSP", "Stopped for crash recovery")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop RTSP server: ${e.message}")
            }

            DashieWebChromeClient.clearJsErrors()

            // Save the last known HA iframe URL for restoration after restart.
            // We can't query the WebView (renderer is dead), but the kiosk shell
            // continuously reports URL changes via DashieNative.onHaUrlChanged().
            val lastUrl = halitePrefs?.performance?.lastHaIframeUrl
            if (!lastUrl.isNullOrEmpty()) {
                halitePrefs?.performance?.crashRestoreUrl = lastUrl
                Log.i(TAG, "🔄 Saved crash restore URL: ${lastUrl.take(80)}")
                PersistentLog.info("WEBVIEW", "Crash restore URL saved: ${lastUrl.take(80)}")
            }

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    Log.i(TAG, "🔄 Restarting Activity for crash recovery...")
                    halitePrefs?.performance?.rendererRecoveryPending = true
                    activity.recreate()
                    Log.i(TAG, "✅ Activity recreate() called for crash recovery")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to restart Activity: ${e.message}")
                    halitePrefs?.performance?.rendererRecoveryPending = false
                    try {
                        halitePrefs?.performance?.rendererRecoveryPending = true
                        val intent = activity.intent
                        activity.finish()
                        activity.startActivity(intent)
                    } catch (e2: Exception) {
                        Log.e(TAG, "❌ Failed to relaunch Activity: ${e2.message}")
                        halitePrefs?.performance?.rendererRecoveryPending = false
                    }
                }
            }, 100)

            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ WebView crash recovery failed: ${e.message}", e)
            PersistentLog.error("WEBVIEW", "Crash recovery failed: ${e.message}")
            false
        }
    }

    // ============================================================
    // WebView Recreation for Memory Recovery
    // ============================================================

    /**
     * Recreate the WebView to release native memory.
     *
     * Process:
     * 1. Get parent ViewGroup and position of current WebView
     * 2. Remove JavaScript interfaces and destroy old WebView
     * 3. Create new WebView programmatically
     * 4. Reconfigure settings, clients, and JS interfaces
     * 5. Update all component references
     * 6. Load the saved URL
     *
     * @param urlToRestore The URL to load after recreation
     * @param pathToRestore The HA path to restore via JavaScript after page loads
     * @param onComplete Called when recreation is complete
     */
    fun recreateWebViewForMemoryRecovery(urlToRestore: String, pathToRestore: String?, onComplete: () -> Unit) {
        if (!BuildConfig.ALLOW_URL_CONFIG) {
            Log.w(TAG, "🔄 recreateWebView: Not a Halite build, skipping")
            onComplete()
            return
        }

        val webView = coordinator.webView
        Log.i(TAG, "🔄 RECREATING WEBVIEW for memory recovery - will restore: ${urlToRestore.take(60)}")
        PersistentLog.info("MEMORY", "Recreating WebView - URL: ${urlToRestore.take(60)}")

        try {
            if (pathToRestore != null) {
                callbacks.getMemoryManager()?.setPendingHaPathRestore(pathToRestore)
            }

            // 1. Get parent ViewGroup and position
            val parent = webView.parent as? android.view.ViewGroup
            if (parent == null) {
                Log.e(TAG, "🔄 recreateWebView: WebView has no parent!")
                PersistentLog.error("MEMORY", "WebView recreation failed - no parent")
                onComplete()
                return
            }
            val index = parent.indexOfChild(webView)
            val layoutParams = webView.layoutParams

            // 2. Remove JavaScript interfaces
            try {
                webView.removeJavascriptInterface("DashieNative")
                webView.removeJavascriptInterface("DashieBridge")
                webView.removeJavascriptInterface("dashieDevice")
            } catch (e: Exception) {
                Log.w(TAG, "🔄 Error removing JS interfaces: ${e.message}")
            }

            // 3. Stop and destroy old WebView
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.onPause()
            webView.clearHistory()
            webView.clearCache(true)
            webView.clearFormData()
            parent.removeView(webView)
            webView.destroy()
            Log.i(TAG, "🔄 Old WebView destroyed")

            // 4. Request GC
            System.gc()

            // 5. Create new WebView
            val newWebView = com.dashieapp.Dashie.webview.DashieWebView(activity)
            newWebView.id = R.id.dashboardWebView
            newWebView.layoutParams = layoutParams

            // 6. Add to parent at same position
            parent.addView(newWebView, index)

            // 7. Update references BEFORE configuring
            callbacks.setWebView(newWebView)
            coordinator.updateWebView(newWebView)

            // 8. Configure WebView settings
            val webViewSetup = MainWebViewSetup(activity)
            webViewSetup.configure(newWebView, BuildConfig.ALLOW_URL_CONFIG)
            webViewSetup.clearFocusAndHideKeyboard(newWebView)

            // 9. Enable dark mode support
            val darkModeHandler = MainDarkModeHandler(activity, newWebView, coordinator.deviceControls, activity.packageName)
            darkModeHandler.enableSystemDarkModeSupport(activity.resources.configuration)
            callbacks.setDarkModeHandler(darkModeHandler)

            // 10. Setup WebView client
            val splashOverlay = activity.findViewById<View>(R.id.splashOverlay)
            val webViewClient = DashieWebViewClient(halitePrefs!!, splashOverlay)
            webViewClient.onPageLoadComplete = {
                callbacks.getKioskController()?.applyInitialDashboardZoom()
            }
            webViewClient.onRendererCrashRecovery = { crashedWebView ->
                handleWebViewCrashRecovery(crashedWebView)
            }
            webViewClient.onUrlChanged = { url ->
                dashieServiceManager?.setCurrentUrl(url)
            }
            webViewClient.onMemoryReloadComplete = {
                callbacks.getMemoryManager()?.restoreHaPathIfPending()
            }
            webViewClient.hasPendingHaRestore = {
                callbacks.getMemoryManager()?.hasPendingRestore == true
            }
            webViewClient.dialogHostProvider = { dialogHost }
            // Read the live MainActivity-scoped components via callbacks —
            // NOT coordinator.*, which is never populated (see Callbacks docs).
            webViewClient.haConnectionMonitorProvider = { callbacks.getHaConnectionMonitor() }
            webViewClient.authManagerProvider = { callbacks.getAuthManager() }
            webViewClient.telemetryBridgeProvider = { callbacks.getTelemetryBridge() }
            webViewClient.focusRingProvider = { coordinator.haliteRegistry?.focusRingManager }
            webViewClient.stealthReloadUrlProvider = {
                haliteScreenController?.getSavedUrlBeforeReload()
            }
            webViewClient.onStealthReloadComplete = {
                haliteScreenController?.completeStealthReload(newWebView)
            }
            newWebView.webViewClient = webViewClient
            callbacks.setDashieWebViewClient(webViewClient)

            // 11. Setup WebChromeClient
            val chromeClient = DashieWebChromeClient(activity)
            chromeClient.setParentWebView(newWebView)
            newWebView.webChromeClient = chromeClient

            // 12. Recreate JavaScript bridge
            val jsBridge = DashieJSBridge(
                context = activity,
                webView = newWebView,
                voiceAssistant = { callbacks.getVoiceSetup()?.getVoiceAssistant() },
                deviceControls = coordinator.deviceControls,
                halitePrefs = { coordinator.halitePrefs },
                rtspPlayerManager = { coordinator.rtspPlayerManager },
                callbacks = callbacks.createJsBridgeCallbacks()
            )
            newWebView.addJavascriptInterface(jsBridge, "DashieNative")
            callbacks.setJsBridge(jsBridge)

            // Re-register the new WebView with HaTokenExtractor; the old
            // ref points at a destroyed WebView.
            com.dashieapp.Dashie.halite.HaTokenExtractor.setWebView(newWebView)

            // 13. Tear down the previous component generation BEFORE creating
            // replacements and re-wiring. The old HA monitor's ping loop and
            // the old telemetry bridge's token-refresh loop were previously
            // only destroyed in onDestroy(), so every nightly recreation leaked
            // one generation of loops driving the destroyed WebView — partially
            // defeating the memory recovery itself. The music wiring re-run in
            // wireAll() below also re-creates its poller/coordinator loops, so
            // stop the old ones first (same orphaned-loop bug as the health
            // coordinator, fixed earlier).
            callbacks.getDashboardHealthCoordinator()?.stop()
            callbacks.getTelemetryBridge()?.destroy()
            callbacks.getHaConnectionMonitor()?.destroy()
            com.dashieapp.Dashie.halite.HaliteComponentWiring.teardownForRecreation()

            // 14. Recreate telemetry bridge + device metrics interface
            val telemetryBridge = DashboardTelemetryBridge(activity, newWebView, halitePrefs!!)
            newWebView.addJavascriptInterface(telemetryBridge, "DashieBridge")
            callbacks.setDashboardTelemetryBridge(telemetryBridge)

            val dmi = DeviceMetricsInterface(activity, newWebView)
            dmi.rtspPlayerManager = coordinator.rtspPlayerManager
            newWebView.addJavascriptInterface(dmi, "dashieDevice")
            callbacks.setDeviceMetricsInterface(dmi)

            // 15. Recreate HA connection monitor + dashboard health coordinator.
            val healthCoordinator = com.dashieapp.Dashie.halite.DashboardHealthCoordinator(
                reloadIframe = {
                    newWebView.evaluateJavascript(
                        "window.dashieReloadHaIframe && window.dashieReloadHaIframe()", null
                    )
                },
                reloadPage = { newWebView.reload() },
                isEnabled = { coordinator.halitePrefs?.performance?.smartReconnectEnabled ?: true },
                firstBackoffMs = if (com.dashieapp.Dashie.BuildConfig.DEBUG) 3_000L
                    else com.dashieapp.Dashie.halite.DashboardHealthCoordinator.DEFAULT_FIRST_BACKOFF_MS,
                maxBackoffMs = if (com.dashieapp.Dashie.BuildConfig.DEBUG) 6_000L
                    else com.dashieapp.Dashie.halite.DashboardHealthCoordinator.DEFAULT_MAX_BACKOFF_MS
            )
            callbacks.setDashboardHealthCoordinator(healthCoordinator)
            jsBridge.dashboardHealthCoordinatorProvider = { healthCoordinator }
            // Re-point the registry provider at the NEW coordinator too. Without this
            // the WS keepalive would keep escalating into the dead pre-recreation
            // instance and its recovery would silently go nowhere.
            coordinator.haliteRegistry?.dashboardHealthCoordinatorProvider = { healthCoordinator }
            // Phase 1a: wire the liveness-verify provider on the recreated client.
            webViewClient.healthCoordinatorProvider = { healthCoordinator }
            // A fresh coordinator defaults to screenSleeping=false. If this recreation
            // happened while the screen is off (the overnight stealth-reload case), the
            // asleep recovery loop would never start. Re-sync the true state from the
            // surviving screen controller so recovery engages during sleep, not only on
            // the next wake.
            haliteScreenController?.let { healthCoordinator.syncScreenSleeping(it.isScreenOff()) }

            val connectionMonitor = HaConnectionMonitor(newWebView, halitePrefs!!)
            telemetryBridge.connectionDelegate.haConnectionMonitor = connectionMonitor
            connectionMonitor.onScreenSleepListener = { sleeping ->
                healthCoordinator.onScreenSleepChanged(sleeping)
            }
            connectionMonitor.pageErrorProvider = {
                webViewClient.retryManager.hadMainFrameError || healthCoordinator.isContentErrored()
            }
            callbacks.setHaConnectionMonitor(connectionMonitor)

            // 16. Update the registry's component references, THEN re-run all
            // bridge-callback wiring against the new instances. Two past bugs
            // live here:
            // (a) Without registry.jsBridge + wireAll, JS calls reach the new
            //     bridge's @JavascriptInterface methods but its delegate
            //     callbacks are null → every hideKotlinWidget/etc is a silent
            //     no-op (weather rotator freeze after recreation).
            // (b) wireAll's wireHaConnectionCallbacks / wireTelemetryCallbacks /
            //     WS-keepalive capture registry.haConnectionMonitor and
            //     registry.telemetryBridge at wire time. These used to be
            //     created AFTER wireAll and never written back to the registry,
            //     so all of that wiring silently bound the OLD (destroyed-
            //     WebView) instances: RTSP never paused on HA disconnect, the
            //     health coordinator lost screen-sleep awareness, telemetry
            //     lost its feature-state provider.
            coordinator.haliteRegistry?.let { registry ->
                registry.jsBridge = jsBridge
                registry.telemetryBridge = telemetryBridge
                registry.haConnectionMonitor = connectionMonitor
                Log.i(TAG, "🔄 Re-wiring component callbacks to new JS bridge")
                com.dashieapp.Dashie.halite.HaliteComponentWiring.wireAll(
                    registry, activity, { newWebView }
                )
            }

            // Refresh SettingsActivity's static refs — the strong jsBridgeRef
            // otherwise retains the destroyed WebView (via the old bridge) until
            // settings is next opened, and an already-open Settings screen would
            // keep evaluating JS against the dead instance.
            com.dashieapp.Dashie.halite.settings.SettingsActivity.jsBridgeRef = jsBridge
            com.dashieapp.Dashie.halite.settings.SettingsActivity.webViewRef =
                java.lang.ref.WeakReference(newWebView)

            // Confirms the new WebView's telemetry bridge + connection monitor
            // are wired. The page-load WS-proxy injection (HA_CONN log) still
            // has to fire afterwards to actually arm disconnect detection.
            PersistentLog.info("WEBVIEW", "recreation wiring complete — telemetry bridge + HA monitor re-armed")

            // 17. Update screen controller
            haliteScreenController?.setWebView(newWebView)

            // 18. Update input handler and URL handler
            callbacks.setInputHandler(MainInputHandler(
                context = activity,
                webView = newWebView,
                halitePrefs = { coordinator.halitePrefs },
                callbacks = callbacks.createInputHandlerCallbacks()
            ))

            callbacks.setUrlHandler(MainUrlHandler(
                activity = activity,
                webView = newWebView,
                halitePrefs = { coordinator.halitePrefs },
                callbacks = callbacks.createUrlHandlerCallbacks()
            ))

            Log.i(TAG, "🔄 New WebView configured, loading URL: ${urlToRestore.take(60)}")
            PersistentLog.info("MEMORY", "WebView recreated, loading: ${urlToRestore.take(60)}")

            // 19. Load the restored URL
            newWebView.loadUrl(urlToRestore)

            // 20. Explicitly resume WebView JS timers.
            // If recreation happened while the Activity is paused/stopped (e.g. during sleep),
            // the new WebView may be in a throttled state. Calling onResume() + resumeTimers()
            // ensures the power engine's setInterval starts immediately regardless of Activity state.
            newWebView.onResume()
            newWebView.resumeTimers()
            Log.i(TAG, "🔄 WebView.onResume() called post-recreation (ensures JS engine runs during sleep)")

            // Request GC after recreation
            Handler(Looper.getMainLooper()).postDelayed({
                System.gc()
                Log.i(TAG, "🔄 Post-recreation GC requested")
            }, 1000)

            // Fix D2: nudge the freshly-loaded page to re-push theme + widget
            // data to native, in case its early one-shot push raced the
            // recreate (native surfaces left on a stale palette). Delayed so
            // the page has loaded + defined the hook; the re-push is no-op-safe
            // (setThemeColors skips an unchanged palette) so this can't flicker.
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    newWebView.evaluateJavascript(
                        // BUNDLE-EXEMPT: onWebViewRecreated — no-op-safe theme/widget re-push nudge; try/catch, harmless when absent
                        "window.onWebViewRecreated && window.onWebViewRecreated();", null
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "🔄 onWebViewRecreated nudge failed: ${e.message}")
                }
            }, 2500)

            // Runtime provenance: the loaded URL / JS source can change across a recreation —
            // re-stamp so logcat always carries the current lane picture.
            com.dashieapp.Dashie.halite.diagnostics.ProvenanceReporter.stamp(activity, "recreation")

            onComplete()

        } catch (e: Exception) {
            Log.e(TAG, "🔄 WebView recreation FAILED: ${e.message}", e)
            PersistentLog.error("MEMORY", "WebView recreation failed: ${e.message}")
            try {
                coordinator.webView.loadUrl(urlToRestore)
            } catch (e2: Exception) {
                Log.e(TAG, "🔄 Fallback URL load also failed: ${e2.message}")
            }
            onComplete()
        }
    }
}

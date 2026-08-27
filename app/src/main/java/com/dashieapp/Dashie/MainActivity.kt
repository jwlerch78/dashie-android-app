package com.dashieapp.Dashie

import com.dashieapp.Dashie.halite.registry.*
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.media.AudioManager
// TextToSpeech is managed by TtsManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.doOnLayout
import android.graphics.Rect


// Voice components (now handled by MainVoiceSetup)

// Utilities
import com.dashieapp.Dashie.util.DiagnosticToastController
import com.dashieapp.Dashie.util.DeviceInfoHelper

// Halite (Dashie Lite) support
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.SupabaseTokenExtractor
import com.dashieapp.Dashie.halite.NativeDialogHost
import com.dashieapp.Dashie.halite.HaliteScreenController
import com.dashieapp.Dashie.halite.HaliteAuthManager
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.LightSensorBrightnessController
import com.dashieapp.Dashie.halite.DashieWebViewClient
import com.dashieapp.Dashie.halite.DashieWebChromeClient
import com.dashieapp.Dashie.api.DashieServiceManager

// Halite Voice Control (refactored)
import com.dashieapp.Dashie.halite.voice.HaliteVoiceController
import com.dashieapp.Dashie.halite.voice.HaVoiceService
import com.dashieapp.Dashie.halite.TtsManager

// Halite Dashboard Telemetry
import com.dashieapp.Dashie.halite.DashboardTelemetryBridge
import com.dashieapp.Dashie.halite.HaConnectionMonitor
import com.dashieapp.Dashie.halite.DeviceMetricsInterface

// Halite Diagnostics (crash handling, persistent logging)
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.MemoryMonitor

// Halite RTSP Player (native camera playback overlay)
import com.dashieapp.Dashie.halite.rtsp.RtspPlayerManager

// Halite Overlay (for timer UI)
// DashieOverlayWebView and TimerOverlayController removed - timer UI is in main WebView via dashie-lite.html
// Screensaver and timers use CSS z-index layering within the single WebView

// Device Controls (refactored)
import com.dashieapp.Dashie.devicecontrols.DeviceControlsCoordinator

// WebView JavaScript Bridge (refactored)
import com.dashieapp.Dashie.webview.DashieJSBridge
import com.dashieapp.Dashie.webview.DashieWebView
import com.dashieapp.Dashie.webview.WebViewJsInjector

// UI Controllers
import com.dashieapp.Dashie.ui.ImmersiveModeController

// Coroutines
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.dashieapp.Dashie.edition.brandName

class MainActivity : ComponentActivity(), com.dashieapp.Dashie.controlcenter.ControlCenterHost {

    companion object {
        /** Count of live MainActivity instances (between onCreate and onDestroy).
         *  A counter (not a boolean) so the overlap during recreation — new
         *  instance's onCreate runs before the old instance's onDestroy —
         *  doesn't falsely read as "no activity". */
        @Volatile
        private var liveInstanceCount = 0

        /**
         * True when a MainActivity instance currently exists. Used by
         * DashieApiService's OOM-recovery relaunch to skip the CLEAR_TASK
         * relaunch when the activity already came back on its own (e.g. the
         * system auto-restores the task after an app-op change kill like
         * REQUEST_INSTALL_PACKAGES — relaunching on top of that destroys a
         * healthy instance and double-boots the app).
         */
        val isInstanceAlive: Boolean
            get() = liveInstanceCount > 0
    }

    private lateinit var webView: WebView

    // Main Coordinator - orchestrates all component initialization and lifecycle
    // TODO: Progressively move logic from MainActivity to coordinator
    private lateinit var coordinator: MainActivityCoordinator

    // Permission Delegate (extracted from MainActivity)
    private lateinit var permissionDelegate: MainPermissionDelegate

    private lateinit var haLoginLauncher: ActivityResultLauncher<Intent>
    private lateinit var standardHaLoginLauncher: ActivityResultLauncher<Intent>  // For standard Dashie HA login
    private lateinit var haLoginHandler: MainHaLoginHandler
    // URL Handler (extracted from MainActivity)
    private lateinit var urlHandler: MainUrlHandler
    private val TAG = "DashieAuth"

    // Device Controls Coordinator - Manages volume, brightness, settings
    private lateinit var deviceControlsCoordinator: DeviceControlsCoordinator

    // Halite (Dashie Lite) - only used when ALLOW_URL_CONFIG is true
    private var halitePrefs: HalitePreferences? = null

    /** Rate-limit for the brain-route-absent DROP (see BrainRouteApplier.onAbsent below). */
    private var lastBrainRouteAbsentLogMs: Long = 0L
    private var haliteAuthManager: HaliteAuthManager? = null

    // Halite Component Registry - now owned by coordinator
    // MainActivity delegates to coordinator's registry for backwards compatibility
    private val haliteRegistry: HaliteComponentRegistry?
        get() = if (::coordinator.isInitialized) coordinator.haliteRegistry else null

    // Halite components - delegate to coordinator's registry for centralized management
    private val dialogHost: NativeDialogHost?
        get() = haliteRegistry?.dialogHost
    private val haliteScreenController: HaliteScreenController?
        get() = haliteRegistry?.screenController
    private val dashieServiceManager: DashieServiceManager?
        get() = haliteRegistry?.dashieServiceManager
    private val ttsManager: TtsManager?
        get() = haliteRegistry?.ttsManager

    // Standard Dashie API - only used when ALLOW_URL_CONFIG is false
    private var dashieApiPrefs: com.dashieapp.Dashie.api.DashieApiPreferences? = null
    private var dashieApiManager: com.dashieapp.Dashie.api.DashieApiServiceManager? = null
    private var dashieTtsManager: TtsManager? = null  // TTS for standard Dashie API

    // Halite Voice Control - delegate to registry
    private val haliteVoiceController: HaliteVoiceController?
        get() = haliteRegistry?.voiceController

    // JavaScript bridge for WebView communication
    private var jsBridge: DashieJSBridge? = null
    // Held so it can be RE-wired to a freshly recreated jsBridge (WebView memory
    // recovery makes a new DashieJSBridge + voice delegate; without re-applying, the
    // delegate's voiceOverlayBridge is null and onVoiceResponse is dropped → the voice
    // bridge times out 30s → "Sorry, I couldn't process that").
    private var currentVoiceOverlayBridge: com.dashieapp.Dashie.halite.voice.VoiceOverlayBridge? = null

    // Halite Dashboard Telemetry (opt-in performance data collection)
    private var dashboardTelemetryBridge: DashboardTelemetryBridge? = null

    // Halite Device Metrics (Camera Card adaptive streaming support)
    private var deviceMetricsInterface: DeviceMetricsInterface? = null

    // Halite HA Connection Monitor (smart reconnection to prevent IP bans)
    private var haConnectionMonitor: HaConnectionMonitor? = null
    private var dashboardHealthCoordinator: com.dashieapp.Dashie.halite.DashboardHealthCoordinator? = null

    // HA Disconnected Indicator (extracted from MainActivity)
    private var haDisconnectedIndicator: MainHaDisconnectedIndicator? = null

    // NOTE: TimerOverlayController removed - timers now in main WebView via dashie-lite.html
    // The HTML screensaver and timers use CSS z-index for layering within the same WebView

    // WebView client (extracted from inline code)
    private var dashieWebViewClient: DashieWebViewClient? = null

    // Halite Light Sensor - delegate to registry
    private val lightSensorBrightnessController: LightSensorBrightnessController?
        get() = haliteRegistry?.lightSensorController

    // Halite WiFi Lock - delegate to registry
    private val wifiLockManager: com.dashieapp.Dashie.halite.WifiLockManager?
        get() = haliteRegistry?.wifiLockManager

    // Halite RTSP Player Manager (native camera overlays)
    private var rtspPlayerManager: RtspPlayerManager? = null

    // Native Control Center overlay (replaces JS control-center-overlay)
    private var controlCenterOverlay: com.dashieapp.Dashie.controlcenter.ControlCenterOverlay? = null
    /** Trial-expired overlay + countdown chip, via the edition seam. Chickadee has no trial,
     *  so its implementation is an absent surface rather than a hidden one. */
    private var paywallUi: com.dashieapp.Dashie.edition.PaywallUi? = null
    private var controlCenterStateProvider: com.dashieapp.Dashie.controlcenter.ControlCenterStateProvider? = null
    private lateinit var settingsActivityLauncher: ActivityResultLauncher<Intent>

    // Native Sidebar strip + popout menus (replaces JS dash-menu sidebar)
    private var nativeSidebarController: com.dashieapp.Dashie.sidebar.NativeSidebarController? = null

    // Portrait widgets-mode bottom bar + the orientation controller that
    // drives the sidebar ↔ bottom-bar swap. Initialized after the sidebar
    // so the bottom bar can mirror its visibility/active state.
    private var orientationController: com.dashieapp.Dashie.halite.orientation.OrientationController? = null
    private var bottomBarController: com.dashieapp.Dashie.halite.orientation.BottomBarController? = null
    private var portraitLayoutManager: com.dashieapp.Dashie.halite.orientation.PortraitLayoutManager? = null

    // Coordinates JS-driven fullscreen modes (auth QR, voice overlay,
    // photo widget fullscreen, modals). Hides + restores the sidebar +
    // control center on a refcounted basis. Auto-clears on WebView page
    // load so a JS reload mid-fullscreen doesn't leave overlays hidden.
    private val fullscreenModeManager: com.dashieapp.Dashie.webview.FullscreenModeManager by lazy {
        com.dashieapp.Dashie.webview.FullscreenModeManager(
            sidebarProvider = { nativeSidebarController },
            controlCenterProvider = { controlCenterOverlay },
            webViewProvider = { webView },
            widgetGateResetProvider = { haliteRegistry?.nativeWidgetVisibilityGate?.reset() },
            widgetGateFullscreenSuppressProvider = { suppressed ->
                haliteRegistry?.nativeWidgetVisibilityGate?.setSuppressedForFullscreen(suppressed)
            },
            runOnUiThread = ::runOnUiThread
        )
    }

    // Standard Dashie SSDP Service (for non-Halite builds)
    private var dashieSsdpService: com.dashieapp.Dashie.api.DashieSsdpService? = null

    // UI Controllers
    private lateinit var immersiveModeController: ImmersiveModeController

    // Input Handler (extracted from MainActivity)
    private lateinit var inputHandler: MainInputHandler

    // HA Kiosk CSS Injector (extracted from MainActivity)
    private lateinit var haKioskCssInjector: MainHaKioskCssInjector

    // Crash Report Handler (extracted from MainActivity)
    private var crashReportHandler: MainCrashReportHandler? = null

    // Play In-App Updates controller — owns the update banner + install flow
    private var updateController: com.dashieapp.Dashie.halite.update.DashieUpdateController? = null

    // Debug-only receiver for previewing the update banner via adb broadcast
    private var updateBannerTestReceiver: android.content.BroadcastReceiver? = null

    // WebView Bridge (extracted from MainActivity)
    private lateinit var webViewBridge: MainWebViewBridge

    // Dark Mode Handler (extracted from MainActivity)
    private var darkModeHandler: MainDarkModeHandler? = null

    // Kiosk Controller (extracted from MainActivity)
    private var kioskController: MainKioskController? = null

    // Memory Manager (extracted from MainActivity)
    private var memoryManager: MainMemoryManager? = null

    // Service Manager (extracted from MainActivity) - SSDP, API, RTSP, Device Admin
    private var serviceManager: MainServiceManager? = null

    // Lifecycle Handler (extracted from MainActivity) - onResume, onPause, app restart, etc.
    private var lifecycleHandler: MainLifecycleHandler? = null

    // WebView Recreation (extracted from MainActivity) - crash recovery, memory recovery
    private var webViewRecreation: MainWebViewRecreation? = null

    // Voice Setup (extracted from MainActivity)
    private var voiceSetup: MainVoiceSetup? = null

    // Zoom broadcast receiver (for native settings to apply zoom immediately)
    private var broadcastManager: MainBroadcastManager? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "GestureBackNavigation")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Boot perf T0 — record as early as possible so all subsequent marks
        // are anchored to a consistent zero. installSplashScreen() and
        // super.onCreate() will appear as small early offsets.
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.start()

        // Install splash screen (for Android 12+ compatibility)
        installSplashScreen()
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("onCreate:after-splash")

        super.onCreate(savedInstanceState)
        liveInstanceCount++
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("onCreate:after-super")

        // Install the LAN-tolerant SSL policy as the process-wide default
        // for HttpsURLConnection so every code path (KioskCssInjector,
        // proxyIngressRequest, JsBridgeHa.haProxy, HaWebLoginActivity,
        // MaApiClient, etc.) handles local self-signed certs without
        // per-site changes. Public hosts still get full cert validation.
        com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.installAsDefault()

        // Perform early initialization (crash handler, OOM detection, preferences, redirects)
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("early-init:start")
        val startupInitializer = MainStartupInitializer(this) { memoryManager }
        val startupResult = startupInitializer.performEarlyInit()
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("early-init:end")
        halitePrefs = startupResult.halitePrefs
        halitePrefs?.let { p ->
            // Trust self-signed certs for the user's configured HA host(s), not just
            // LAN-pattern hosts — covers custom domains / VPN dashboards. Read live so
            // config changes are picked up. Used by the WebView SSL handler and the
            // LocalHostsTrustingHttpClient (OAuth token exchange, sensors, etc.).
            com.dashieapp.Dashie.util.LocalNetworkHostnames.configuredHostProvider = {
                com.dashieapp.Dashie.util.LocalNetworkHostnames.hostsFromUrls(
                    p.connection.haBaseUrl, p.connection.haUrl, p.performance.localHaUrl
                )
            }
        }
        crashReportHandler = startupResult.crashReportHandler
        if (!startupResult.shouldContinue) return

        // Restore user's dark mode preference before setContentView
        // so the DayNight theme applies correctly on first render
        com.dashieapp.Dashie.devicecontrols.DarkModeManager.applyStoredPreference(this)
        android.util.Log.w("DarkModeTrace", ">>> AFTER applyStoredPreference, uiMode=${resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK}")

        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("setContentView:start")
        setContentView(R.layout.activity_main)
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("setContentView:end")
        // setContentView resets resources.configuration to system default — re-apply stored pref
        com.dashieapp.Dashie.devicecontrols.DarkModeManager.applyStoredPreference(this)
        android.util.Log.w("DarkModeTrace", ">>> AFTER setContentView + re-apply, uiMode=${resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK}")

        // Re-apply the ha_only kiosk lockdown if it was lost (sign-out
        // then sign back in, app reinstall, device change). Without this,
        // an ha_only user lands on the full dashboard with no Dashie
        // features accessible — see TrialExpiredOverlayManager
        // .convertToHaOnly for the initial application path; same flags
        // so the result is indistinguishable from a fresh opt-in.
        // Runs BEFORE the layoutMode read below so the initial URL
        // routes to the kiosk shell without a flash of dashboard chrome.
        maybeApplyHaOnlyKiosk(broadcastReload = false)

        // Sync forceKioskMode from layoutMode so they're always consistent
        // (forceKioskMode is the legacy flag that determineInitialUrl() checks)
        val layoutMode = halitePrefs?.display?.layoutMode ?: "widgets"
        halitePrefs?.account?.forceKioskMode = (layoutMode == "kiosk")

        // Set root layout background for layout canvas modes
        if (layoutMode != "legacy" && layoutMode != "kiosk") {
            findViewById<android.view.View>(R.id.rootLayout)
                ?.setBackgroundColor(android.graphics.Color.parseColor("#1a1a2e"))
        }

        // Create coordinator early - it will be initialized with components later
        coordinator = MainActivityCoordinator(
            activity = this,
            window = window
        )

        // Initialize UI controllers AFTER setContentView (DecorView must exist first)
        immersiveModeController = ImmersiveModeController(window)

        // Initialize kiosk controller (extracted from MainActivity)
        kioskController = MainKioskController(
            activity = this,
            webView = { webView },
            halitePrefs = { halitePrefs },
            immersiveModeController = immersiveModeController,
            callbacks = object : MainKioskController.Callbacks {
                override fun isAllowUrlConfig() = BuildConfig.ALLOW_URL_CONFIG
                override fun isTabletDevice() = this@MainActivity.isTabletDevice()
                override fun getIntent() = intent
            }
        )

        // Register broadcast receivers for settings-driven actions
        if (BuildConfig.ALLOW_URL_CONFIG) {
            broadcastManager = MainBroadcastManager(
                context = this,
                halitePrefsProvider = { halitePrefs },
                registryProvider = { haliteRegistry },
                screenControllerProvider = { haliteScreenController },
                voiceControllerProvider = { haliteVoiceController },
                kioskControllerProvider = { kioskController },
                sidebarControllerProvider = { nativeSidebarController },
                voiceSetupProvider = { voiceSetup },
                urlHandlerProvider = { urlHandler },
                permissionDelegateProvider = { permissionDelegate },
                webViewProvider = { webView },
                setDashieViewport = ::setDashieViewport,
                setHaliteViewport = ::setHaliteViewport,
                controlCenterProvider = { controlCenterOverlay },
                updateControllerProvider = { updateController },
                orientationControllerProvider = { orientationController },
                portraitLayoutManagerProvider = { portraitLayoutManager }
            )
            broadcastManager?.register()
        }

        // Setup callback for when nav bar is blocked (show toast to user)
        immersiveModeController.onNavBarBlocked = {
            runOnUiThread {
                Toast.makeText(
                    this,
                    "App is locked → unlock in side menu",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Apply immersive mode for Halite/kiosk mode
        // Fire tablets need triple-application to reliably hide status bar on first load
        // kioskMode = true always for Halite (enables BEHAVIOR_DEFAULT + re-hide listener)
        // fastHide = true (250ms) only when Lock Nav Bar is enabled, otherwise slow hide (3s)
        if (BuildConfig.ALLOW_URL_CONFIG) {
            val useFastHide = halitePrefs?.lock?.lockToApp == true
            Log.i(TAG, "📱 onCreate immersive setup: lockToApp=${halitePrefs?.lock?.lockToApp}, useFastHide=$useFastHide")
            // Immediate first application
            immersiveModeController.enable(kioskMode = true, fastHide = useFastHide)
            // Second application after layout pass
            window.decorView.post {
                immersiveModeController.enable(kioskMode = true, fastHide = useFastHide)
            }
            // Third application with slight delay for stubborn devices
            window.decorView.postDelayed({
                immersiveModeController.enable(kioskMode = true, fastHide = useFastHide)
            }, 100)
        }

        // Log app version on startup
        logAppVersion()

        // Register device install (first launch only, fire-and-forget)
        com.dashieapp.Dashie.halite.install.InstallTracker(this).registerIfNeeded()

        // Monthly device check-in (sends if 30+ days elapsed, fire-and-forget).
        // Routed through the edition seam: the published Chickadee edition has no account
        // and reports to nothing, so its implementation is an absent heartbeat rather than
        // a suppressed one. See edition/EditionTelemetry.kt.
        com.dashieapp.Dashie.edition.EditionSeams.telemetry(this).checkinIfDue()

        // Initialize diagnostic toast controller (toasts disabled by default)
        DiagnosticToastController.initialize(applicationContext)

        // Initialize device controls coordinator (volume, brightness, settings)
        deviceControlsCoordinator = DeviceControlsCoordinator(this)

        // Notify native overlay cards (timer, music player) when dark mode changes.
        // DarkModeManager.setDarkMode() already calls forceResourcesNightMode() which
        // updates resources.configuration, so we just forward to overlay managers here.
        // Avoiding a redundant resources.updateConfiguration() prevents cascading config
        // change loops that crash low-end TV devices (ONN stick).
        deviceControlsCoordinator.onDarkModeChanged = { isDark ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                // Resources already updated by DarkModeManager — just read current config
                val config = android.content.res.Configuration(resources.configuration)
                haliteRegistry?.timerOverlayManager?.onConfigurationChanged(config)
                haliteRegistry?.musicPlayerManager?.onConfigurationChanged(config)
                // Refresh native sidebar theme (needed when dark mode changes via API)
                nativeSidebarController?.refreshTheme(isDark)
                // Mirror the dark-mode flip onto the portrait bottom bar
                // (icon tint, panel bg, active indicator). Without this its
                // baked-#000 vector icons render black-on-black in dark mode.
                bottomBarController?.onDarkModeChanged(isDark)
                // Refresh native widget themes (photo border, weather cards) so they
                // also flip when dark mode is set via API/JS bridge, not just sidebar.
                haliteRegistry?.photoWidgetController?.applyTheme(isDark)
                haliteRegistry?.weatherDailyWidgetController?.applyTheme(isDark)
                haliteRegistry?.weatherHourlyWidgetController?.applyTheme(isDark)
            }
        }

        // Set up brightness change callback to re-apply immersive mode
        // This prevents the nav bar appearing bug on Fire tablets when brightness is adjusted
        // See ImmersiveModeController class comment for full details on this bug
        if (BuildConfig.ALLOW_URL_CONFIG) {
            deviceControlsCoordinator.brightnessManager.onWindowAttributesChanged = {
                // Use actual lockToApp preference for fastHide, not the cached state
                val useFastHide = halitePrefs?.lock?.lockToApp ?: false
                window.decorView.post {
                    immersiveModeController.enable(kioskMode = true, fastHide = useFastHide)
                }
            }
        }

        // Halite: Apply kiosk mode settings (fullscreen, keep screen on)
        if (BuildConfig.ALLOW_URL_CONFIG && halitePrefs != null) {
            kioskController?.applyHaliteKioskSettings()

            // Initialize RTSP Player Manager for native camera overlays
            // This enables hardware-accelerated RTSP playback on top of WebView
            com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("rtsp-player-manager:start")
            val rootLayout = findViewById<FrameLayout>(R.id.rootLayout)
            rtspPlayerManager = RtspPlayerManager(this, rootLayout)
            com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("rtsp-player-manager:end")
            Log.i(TAG, "📹 RTSP Player Manager initialized")

            // Set soft input mode to prevent keyboard from auto-showing
            // SOFT_INPUT_STATE_ALWAYS_HIDDEN: Keyboard won't show automatically on focus
            // SOFT_INPUT_ADJUST_PAN: When keyboard shows (user taps input), pan instead of resize
            // Combined, this prevents unwanted keyboard popup while still working when user wants it
            val softInputFlags = android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            Log.i(TAG, "🔧 Setting soft input mode: ALWAYS_HIDDEN | ADJUST_PAN")
            window.setSoftInputMode(softInputFlags)
        } else {
            // Standard Dashie: Keep screen on while dashboard is active
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // Enable immersive mode for tablets (kiosk-style display)
            if (isTabletDevice()) {
                Log.i(TAG, "📱 Tablet detected - enabling immersive kiosk mode")
                kioskController?.enableImmersiveMode()
            }
        }

        // Initialize permission delegate (handles all permission launchers)
        permissionDelegate = MainPermissionDelegate(this, object : MainPermissionDelegate.Callbacks {
            override fun isAllowUrlConfig() = BuildConfig.ALLOW_URL_CONFIG
            override fun initializeVoiceAssistant() = voiceSetup?.initializeVoiceAssistant() ?: Unit
            override fun notifyWebView(event: String, data: String) = this@MainActivity.notifyWebView(event, data)
            override fun getHaliteScreenController() = haliteScreenController
            override fun getHaliteVoiceController() = haliteVoiceController
            override fun getDialogHost() = haliteRegistry?.dialogHost
            override fun getHalitePrefs() = halitePrefs
            override fun startRtspServerWithCameraRelease() { serviceManager?.startRtspServerWithCameraRelease() }
            override fun getDashieServiceManager() = dashieServiceManager
            override fun refreshMotionWake() { haliteScreenController?.refreshMotionWake() }
            override fun disableRtspMotionMode() { haliteScreenController?.disableRtspMotionMode() }
            override fun isDeviceAdminEnabled(): Boolean =
                com.dashieapp.Dashie.halite.DeviceAdminHelper.isActive(this@MainActivity)
        })
        permissionDelegate.registerLaunchers()

        // Initialize HA login activity launcher (Fire tablet keyboard workaround)
        // The result is handled by HaliteAuthManager
        haLoginLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            haliteAuthManager?.handleLoginResult(result)
        }

        // Initialize standard Dashie HA login launcher (for web app token extraction)
        standardHaLoginLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            haLoginHandler.handleStandardHaLoginResult(result)
        }

        // Initialize HA login handler (extracted from MainActivity)
        haLoginHandler = MainHaLoginHandler(
            activity = this,
            webViewProvider = { webView },
            halitePrefsProvider = { halitePrefs },
            dashieApiPrefsProvider = { dashieApiPrefs },
            setDashieApiPrefs = { dashieApiPrefs = it },
            standardHaLoginLauncher = standardHaLoginLauncher,
            haliteAuthManagerProvider = { haliteAuthManager },
            setInlineAuthBackVisible = { visible ->
                runOnUiThread {
                    // Touch devices only — Fire TV / Android TV escape inline
                    // HA login via the remote Back button (D.5), so the visual
                    // overlay button is hidden there.
                    val show = visible &&
                        !com.dashieapp.Dashie.util.DeviceInfoHelper.isTVDevice(this)
                    findViewById<android.widget.ImageView>(R.id.inlineHaLoginBackButton)
                        ?.visibility = if (show) View.VISIBLE else View.GONE
                }
            }
        )
        findViewById<android.widget.ImageView>(R.id.inlineHaLoginBackButton)
            ?.setOnClickListener {
                Log.i(TAG, "🔙 Inline HA login back button tapped")
                haLoginHandler.cancelInlineAuth()
            }

        // Settings activity launcher — CC stays visible behind native settings.
        // On return, resume CC polling/timers (or re-show if somehow hidden).
        settingsActivityLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            Log.d("MainActivity", "Settings result: ${result.resultCode}, CC visible: ${controlCenterOverlay?.isVisible}")
            runOnUiThread {
                // Re-evaluate power watchdog immediately so CC picks up any threshold changes
                haliteScreenController?.powerWatchdog?.evaluateNow()

                when (result.resultCode) {
                    com.dashieapp.Dashie.halite.settings.SettingsActivity.RESULT_REOPEN_CC -> {
                        Log.d("MainActivity", "Re-opening CC after settings")
                        val cc = controlCenterOverlay
                        if (cc != null && cc.isVisible) {
                            cc.resumeAfterNativeSettings()
                        } else {
                            cc?.show()
                        }
                    }
                    com.dashieapp.Dashie.halite.settings.SettingsActivity.RESULT_OPEN_BRIGHTNESS_SETTINGS -> {
                        val cc = controlCenterOverlay
                        if (cc != null && cc.isVisible) {
                            cc.resumeAfterNativeSettings()
                        } else {
                            cc?.show()
                        }
                        dialogHost?.showAutoBrightnessSettingsFromBridge()
                    }
                    com.dashieapp.Dashie.halite.settings.SettingsActivity.RESULT_CLOSE_NO_CC -> {
                        // Settings closed for navigation (sign-in, sign-out) — hide CC
                        Log.d("MainActivity", "Settings closed without CC reopen")
                        controlCenterOverlay?.hide()
                    }
                    else -> {
                        // Unknown result (e.g. RESULT_CANCELED from system back) — still re-show CC
                        Log.d("MainActivity", "Settings returned unknown result ${result.resultCode}, re-showing CC")
                        val cc = controlCenterOverlay
                        if (cc != null && cc.isVisible) {
                            cc.resumeAfterNativeSettings()
                        } else {
                            cc?.show()
                        }
                    }
                }
            }
        }

        webView = findViewById(R.id.dashboardWebView)
        val splashOverlay = findViewById<View>(R.id.splashOverlay)

        // Initialize WebView bridge (extracted from MainActivity)
        webViewBridge = MainWebViewBridge { webView }

        // Initialize voice setup (extracted from MainActivity)
        // IMPORTANT: Must be initialized BEFORE checkMicrophonePermission()
        // because if permission is already granted, the callback fires immediately
        voiceSetup = MainVoiceSetup(
            context = this,
            webView = { webView },
            webViewBridge = webViewBridge,
            callbacks = object : MainVoiceSetup.Callbacks {
                override fun getDashieServiceManager() = dashieServiceManager
            }
        )

        // Check and request microphone permission if needed (Standard Dashie only)
        // Halite voice permission check is done AFTER webView is initialized
        if (!BuildConfig.ALLOW_URL_CONFIG) {
            // Standard Dashie: Request permission automatically
            permissionDelegate.checkMicrophonePermission()
        }

        // Initialize input handler (extracted from MainActivity)
        inputHandler = MainInputHandler(
            context = this,
            webView = webView,
            halitePrefs = { halitePrefs },
            callbacks = object : MainInputHandler.Callbacks {
                override fun getDialogHost() = haliteRegistry?.dialogHost
                override fun getHaliteScreenController() = haliteScreenController
                override fun isAllowUrlConfig() = BuildConfig.ALLOW_URL_CONFIG
                override fun notifyVolumeChange(level: Int) = webViewBridge.notifyVolumeChange(level)
                override fun forwardKeyToJs(keyCode: Int) = webViewBridge.forwardKeyToJs(keyCode)
                override fun forwardKeyToJsLongPress(keyCode: Int) = webViewBridge.forwardKeyToJsLongPress(keyCode)
                override fun getCurrentFocus() = currentFocus
                override fun isKeyboardShowing() = lifecycleHandler?.isKeyboardShowing() ?: false
                override fun overlayHasKeyboardFocus() = jsBridge?.overlayHasKeyboardFocus ?: false
                override fun isNativeOverlayShowing() = crashReportHandler?.isBannerShowing() ?: false
                override fun handleNativeWidgetDpadKey(keyCode: Int): Boolean {
                    val mgr = haliteRegistry?.nativeWidgetFocusManager ?: return false
                    if (!mgr.hasActiveWidget()) return false
                    return mgr.handleDpadKey(keyCode)
                }
                override fun isInlineAuthPending() = haLoginHandler.inlineAuthPending
                override fun launchSystemSettings() {
                    com.dashieapp.Dashie.devicecontrols.SettingsLauncher(this@MainActivity).openSystemSettings()
                }
                override fun performBackPressed() {
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        )

        // Initialize URL handler (extracted from MainActivity)
        urlHandler = MainUrlHandler(
            activity = this,
            webView = webView,
            halitePrefs = { halitePrefs },
            callbacks = object : MainUrlHandler.Callbacks {
                override fun getTelemetryBridge() = dashboardTelemetryBridge
                override fun getScreenController() = haliteScreenController
                override fun isHaliteBuild() = BuildConfig.ALLOW_URL_CONFIG
            }
        )

        // Initialize HA Kiosk CSS Injector (for standard Dashie non-Halite builds)
        haKioskCssInjector = MainHaKioskCssInjector(
            context = this,
            prefsProvider = { dashieApiPrefs }
        )

        // Initialize coordinator with all the components
        // The coordinator will manage Halite-specific initialization
        val rootLayout = findViewById<FrameLayout>(R.id.rootLayout)
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("coordinator-init:start")
        coordinator.initialize(
            webView = webView,
            rootLayout = rootLayout,
            splashOverlay = splashOverlay,
            deviceControls = deviceControlsCoordinator,
            immersiveMode = immersiveModeController,
            permissionDelegate = permissionDelegate,
            inputHandler = inputHandler,
            webViewBridge = webViewBridge,
            haKioskCssInjector = haKioskCssInjector,
            halitePrefs = halitePrefs
        )
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("coordinator-init:end")

        // Initialize memory manager for Halite (handles WebView reloads, memory pressure)
        if (BuildConfig.ALLOW_URL_CONFIG) {
            memoryManager = MainMemoryManager(
                webViewProvider = { webView },
                halitePrefsProvider = { halitePrefs },
                callbacks = object : MainMemoryManager.Callbacks {
                    override fun isAllowUrlConfig() = BuildConfig.ALLOW_URL_CONFIG
                    override fun runOnUiThread(action: Runnable) = this@MainActivity.runOnUiThread(action)
                    override fun getScreenController() = coordinator.getScreenController()
                    override fun getRtspPlayerManager() = rtspPlayerManager
                    override fun requestGc() = System.gc()
                    override fun requestTrimMemory() {
                        // Request aggressive memory trim from the system
                        application.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
                        Log.i(TAG, "🔄 Requested TRIM_MEMORY_COMPLETE from system")
                    }
                    override fun recreateWebView(urlToRestore: String, pathToRestore: String?, onComplete: () -> Unit) {
                        webViewRecreation?.recreateWebViewForMemoryRecovery(urlToRestore, pathToRestore, onComplete) ?: onComplete()
                    }
                    override fun restartRtspServer(onComplete: () -> Unit) {
                        serviceManager?.restartRtspServerForMemoryRecovery(onComplete) ?: onComplete()
                    }
                }
            )
        }

        // Initialize service manager (SSDP, API, RTSP, Device Admin)
        serviceManager = MainServiceManager(
            activity = this,
            halitePrefsProvider = { halitePrefs },
            callbacks = object : MainServiceManager.Callbacks {
                override fun getDashieServiceManager() = dashieServiceManager
                override fun getHaliteScreenController() = haliteScreenController
                override fun getHaliteRegistry() = haliteRegistry
                override fun getPermissionDelegate() = permissionDelegate
                override fun getWebView() = webView
                override fun getDeviceControlsCoordinator() = deviceControlsCoordinator
                override fun getDashieApiPrefs() = dashieApiPrefs
                override fun setDashieApiPrefs(prefs: com.dashieapp.Dashie.api.DashieApiPreferences) { dashieApiPrefs = prefs }
                override fun getDashieApiManager() = dashieApiManager
                override fun setDashieApiManager(manager: com.dashieapp.Dashie.api.DashieApiServiceManager) { dashieApiManager = manager }
                override fun getDashieSsdpService() = dashieSsdpService
                override fun setDashieSsdpService(service: com.dashieapp.Dashie.api.DashieSsdpService) { dashieSsdpService = service }
                override fun getUrlHandler() = urlHandler
            }
        )

        // Initialize lifecycle handler (onResume, onPause, app restart, etc.)
        lifecycleHandler = MainLifecycleHandler(
            activity = this,
            coordinator = coordinator,
            callbacks = object : MainLifecycleHandler.Callbacks {
                override fun getKioskController() = kioskController
                override fun getVoiceSetup() = voiceSetup
                override fun getMemoryManager() = memoryManager
                override fun getServiceManager() = serviceManager
                override fun getUrlHandler() = urlHandler
                override fun getDarkModeHandler() = darkModeHandler
                override fun isTabletDevice() = this@MainActivity.isTabletDevice()
            }
        )

        // Initialize WebView recreation handler (crash recovery, memory recovery)
        webViewRecreation = MainWebViewRecreation(
            activity = this,
            coordinator = coordinator,
            callbacks = object : MainWebViewRecreation.Callbacks {
                override fun getMemoryManager() = memoryManager
                override fun getKioskController() = kioskController
                override fun getVoiceSetup() = voiceSetup
                override fun getLifecycleHandler() = lifecycleHandler
                override fun getDarkModeHandler() = darkModeHandler
                override fun setDarkModeHandler(handler: MainDarkModeHandler?) { darkModeHandler = handler }
                override fun setDashieWebViewClient(client: DashieWebViewClient?) { dashieWebViewClient = client }
                override fun setJsBridge(bridge: DashieJSBridge?) {
                    jsBridge = bridge
                    // Re-wire all jsBridge-level callbacks/providers to the new bridge so a
                    // recreated WebView's delegates can still route JS→native calls
                    // (onVoiceResponse, TTS-end, voice-progress, music, brightness, sidebar,
                    // subscription) — otherwise they're null and silently dropped.
                    applyJsBridgeCallbacks(bridge)
                }
                override fun setDashboardTelemetryBridge(bridge: DashboardTelemetryBridge?) { dashboardTelemetryBridge = bridge }
                override fun setDeviceMetricsInterface(dmi: DeviceMetricsInterface?) { deviceMetricsInterface = dmi }
                override fun setHaConnectionMonitor(monitor: HaConnectionMonitor?) { haConnectionMonitor = monitor }
                override fun getDashboardHealthCoordinator() = dashboardHealthCoordinator
                override fun setDashboardHealthCoordinator(coordinator: com.dashieapp.Dashie.halite.DashboardHealthCoordinator?) { dashboardHealthCoordinator = coordinator }
                override fun getTelemetryBridge() = dashboardTelemetryBridge
                override fun getHaConnectionMonitor() = haConnectionMonitor
                override fun getAuthManager() = haliteAuthManager
                override fun setInputHandler(handler: MainInputHandler) { inputHandler = handler }
                override fun setUrlHandler(handler: MainUrlHandler) { urlHandler = handler }
                override fun setWebView(webView: WebView) { this@MainActivity.webView = webView }
                override fun createJsBridgeCallbacks() = this@MainActivity.createJsBridgeCallbacks()
                override fun createInputHandlerCallbacks() = object : MainInputHandler.Callbacks {
                    override fun getDialogHost() = haliteRegistry?.dialogHost
                    override fun getHaliteScreenController() = haliteScreenController
                    override fun isAllowUrlConfig() = BuildConfig.ALLOW_URL_CONFIG
                    override fun notifyVolumeChange(level: Int) = webViewBridge.notifyVolumeChange(level)
                    override fun forwardKeyToJs(keyCode: Int) = webViewBridge.forwardKeyToJs(keyCode)
                    override fun forwardKeyToJsLongPress(keyCode: Int) = webViewBridge.forwardKeyToJsLongPress(keyCode)
                    override fun getCurrentFocus() = currentFocus
                    override fun isKeyboardShowing() = lifecycleHandler?.isKeyboardShowing() ?: false
                    override fun overlayHasKeyboardFocus() = jsBridge?.overlayHasKeyboardFocus ?: false
                override fun isNativeOverlayShowing() = crashReportHandler?.isBannerShowing() ?: false
                    override fun handleNativeWidgetDpadKey(keyCode: Int): Boolean {
                        val mgr = haliteRegistry?.nativeWidgetFocusManager ?: return false
                        if (!mgr.hasActiveWidget()) return false
                        return mgr.handleDpadKey(keyCode)
                    }
                    override fun isInlineAuthPending() = haLoginHandler.inlineAuthPending
                    override fun launchSystemSettings() {
                        com.dashieapp.Dashie.devicecontrols.SettingsLauncher(this@MainActivity).openSystemSettings()
                    }
                    override fun performBackPressed() {
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
                override fun createUrlHandlerCallbacks() = object : MainUrlHandler.Callbacks {
                    override fun getTelemetryBridge() = dashboardTelemetryBridge
                    override fun getScreenController() = haliteScreenController
                    override fun isHaliteBuild() = BuildConfig.ALLOW_URL_CONFIG
                }
            }
        )

        // Configure WebView + WebViewClient + chrome client + JS bridge + telemetry + HA connection monitor.
        // Returns the constructed components; we just assign them to fields.
        val webViewBootstrapResult = MainWebViewBootstrap.setup(
            activity = this,
            webView = webView,
            splashOverlay = splashOverlay,
            halitePrefs = halitePrefs,
            allowUrlConfig = BuildConfig.ALLOW_URL_CONFIG,
            haliteRegistryProvider = { haliteRegistry },
            timerManagerProvider = { haliteRegistry?.timerOverlayManager },
            fullscreenModeManager = fullscreenModeManager,
            nativeSidebarControllerProvider = { nativeSidebarController },
            deviceControlsCoordinator = deviceControlsCoordinator,
            rtspPlayerManagerProvider = { rtspPlayerManager },
            voiceAssistantProvider = { voiceSetup?.getVoiceAssistant() },
            webViewRecreationProvider = { webViewRecreation },
            memoryManagerProvider = { memoryManager },
            dashieServiceManagerProvider = { dashieServiceManager },
            haLoginHandler = haLoginHandler,
            haKioskCssInjector = haKioskCssInjector,
            serviceManagerProvider = { serviceManager },
            lifecycleHandlerProvider = { lifecycleHandler },
            kioskControllerProvider = { kioskController },
            showTrialConfirmationDialog = ::showTrialConfirmationDialog,
            showAccountAlreadyExistsDialog = ::showAccountAlreadyExistsDialog,
            callbacksFactory = ::createJsBridgeCallbacks
        )
        darkModeHandler = webViewBootstrapResult.darkModeHandler
        dashieWebViewClient = webViewBootstrapResult.dashieWebViewClient
        jsBridge = webViewBootstrapResult.jsBridge
        dashboardTelemetryBridge = webViewBootstrapResult.dashboardTelemetryBridge
        deviceMetricsInterface = webViewBootstrapResult.deviceMetricsInterface
        haConnectionMonitor = webViewBootstrapResult.haConnectionMonitor
        haDisconnectedIndicator = webViewBootstrapResult.haDisconnectedIndicator
        dashboardHealthCoordinator = webViewBootstrapResult.dashboardHealthCoordinator

        // DEBUG-only: recovery test-rig event injector (no-op in release). Lets the
        // adb rig drive coordinator + environment events deterministically.
        if (BuildConfig.DEBUG) {
            com.dashieapp.Dashie.halite.diagnostics.DebugTestInjector.register(this) { event, injectIntent ->
                runOnUiThread {
                    com.dashieapp.Dashie.halite.diagnostics.DebugInjectHandler.handle(
                        event = event,
                        intent = injectIntent,
                        webView = webView,
                        halitePrefs = halitePrefs,
                        coordinator = dashboardHealthCoordinator,
                        onRecreate = { memoryManager?.handleMemoryPressure("critical", 0) },
                        screensaver = { active ->
                            if (active) haliteScreenController?.screenOff() else haliteScreenController?.screenOn()
                        },
                        paintCheck = { label -> haliteScreenController?.runBlankCheckNow(label) },
                        // Real scheduled_refresh recreate path (same as the stealth alarm)
                        onStealthReload = { memoryManager?.handleMemoryPressure("scheduled", 0) }
                    )
                }
            }
        }

        // Halite: Setup screen controller, callbacks, and auth manager
        // Note: Registry is now created by coordinator.initialize()
        // Note: Screen dimmer is setup later after HA login completes (to avoid duplicate initializations)
        if (BuildConfig.ALLOW_URL_CONFIG && halitePrefs != null && haliteRegistry != null) {
            haliteAuthManager = MainHaliteSetup.setup(
                activity = this,
                // Live lambda, not the raw field: setup()'s closures (return-to-home,
                // ha_page navigate, JWT extraction) outlive WebView memory-recovery
                // recreation, which replaces this@MainActivity.webView.
                webViewProvider = { webView },
                halitePrefs = halitePrefs!!,
                haliteRegistry = haliteRegistry!!,
                haliteScreenControllerProvider = { haliteScreenController },
                dashieWebViewClientProvider = { dashieWebViewClient },
                dashboardTelemetryBridgeProvider = { dashboardTelemetryBridge },
                dialogHostFn = { dialogHost },
                immersiveModeController = immersiveModeController,
                dashieServiceManagerProvider = { dashieServiceManager },
                memoryManagerProvider = { memoryManager },
                haLoginLauncher = haLoginLauncher
            )
        }

        // Load initial URL using the URL handler (extracted from MainActivity)
        // On OOM recovery, restore the last WebView URL instead of the default
        val urlToLoad = if (BuildConfig.ALLOW_URL_CONFIG) {
            // Check intent extras first (set by DashieApiService auto-relaunch)
            val intentRecoveryUrl = if (intent?.getBooleanExtra("from_oom_recovery", false) == true) {
                intent.getStringExtra("oom_recovery_url")
            } else null
            val isOomRecovery = startupResult.oomKillDetected || !intentRecoveryUrl.isNullOrEmpty()

            // Get the best available saved URL (intent > prefs > empty)
            val savedUrl = intentRecoveryUrl?.takeIf { it.isNotEmpty() }
                ?: halitePrefs?.performance?.lastWebViewUrl?.takeIf { it.isNotEmpty() }
                ?: ""

            val isDashieMode = halitePrefs?.account?.isLinked == true && halitePrefs?.account?.forceKioskMode != true

            if (isDashieMode) {
                // Dashie mode (normal start or OOM recovery): always load the dashboard.
                // JS handles session restore. Don't try to restore HA-iframe navigation —
                // the dashboard is always the right landing place for logged-in users.
                if (isOomRecovery) {
                    Log.i(TAG, "OOM recovery (Dashie mode): loading dashboard (ignoring saved: ${savedUrl.take(60)})")
                    PersistentLog.info("OOM", "Dashie mode: loading dashboard after OOM (ignoring saved: ${savedUrl.take(60)})")
                }
                urlHandler.determineInitialUrl()
            } else if (isOomRecovery) {
                // Kiosk/shell mode OOM recovery: always use shell URL.
                // Restoring a raw HA URL would bypass the shell and break sidebar/overlay.
                Log.i(TAG, "OOM recovery (shell mode): using shell URL (ignoring saved HA URL)")
                urlHandler.determineInitialUrl()
            } else {
                // Kiosk/shell mode normal start
                urlHandler.determineInitialUrl()
            }
        } else {
            urlHandler.determineInitialUrl()
        }
        // Set viewport based on URL being loaded, not just account link status.
        // HA URLs need wide viewport; dashieapp.com uses its own viewport meta tag.
        val isLoadingHaUrl = halitePrefs?.let { prefs ->
            val haBase = prefs.connection.haBaseUrl.trimEnd('/').takeIf { it.isNotEmpty() }
                ?: prefs.connection.haUrl.substringBefore("?").trimEnd('/').takeIf { it.isNotEmpty() }
            haBase != null && urlToLoad.startsWith(haBase)
        } ?: false

        if (isLoadingHaUrl) {
            setHaliteViewport()
        } else if (halitePrefs?.account?.isLinked == true) {
            setDashieViewport()
        }

        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("loadInitialUrl", urlToLoad)
        urlHandler.loadInitialUrl(urlToLoad, intent)

        // PERFORMANCE OPTIMIZATION: Initialize heavy components AFTER URL load starts
        // This allows WebView to begin loading in parallel with sidebar/SSDP/voice init
        // Saves ~1.5s on cold start by parallelizing network fetch and UI construction
        if (BuildConfig.ALLOW_URL_CONFIG && halitePrefs != null) {
            Handler(Looper.getMainLooper()).post {
                com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("deferred-handler-post:fired")
                // Set external references on registry before initialization
                haliteRegistry?.authManager = haliteAuthManager
                haliteRegistry?.telemetryBridge = dashboardTelemetryBridge
                haliteRegistry?.haConnectionMonitor = haConnectionMonitor
                haliteRegistry?.jsBridge = jsBridge

                // Initialize all deferred components via registry
                com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry-deferred-init:start")
                haliteRegistry?.initializeDeferredComponents()
                com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("registry-deferred-init:end")

                // Initialize native Control Center overlay
                com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("control-center-init:start")
                initializeControlCenter()
                com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("control-center-init:end")

                // Initialize trial-expired overlay manager + hook into the
                // JS-bridge subscription delegate so it auto-shows/hides
                // when the periodic check-subscription sync writes a new
                // status. Calling refresh() once here also covers the case
                // where the JS hasn't synced yet on this boot but prefs
                // already have a stale-active state.
                paywallUi = com.dashieapp.Dashie.edition.EditionSeams.paywall(this@MainActivity).also {
                    it.attachTrialSurfaces(
                        halitePrefsProvider = { halitePrefs },
                        visibilityGate = haliteRegistry?.nativeWidgetVisibilityGate,
                    )
                }
                // Single onSubscriptionUpdated callback fans out to both
                // trial overlays. Triggered every time the JS layer writes
                // a new subscription state via syncSubscriptionState.
                //
                // Also handles the subscribe-from-ha_only restore path:
                // when a user who opted into HA-only later subscribes,
                // the Stripe webhook flips subscription_status to active
                // server-side; the next sync writes that here. If we set
                // kiosk via the ha_only opt-in (kioskFromHaOnly=true)
                // and the new status grants Dashie access, drop the
                // kiosk lockdown and reload into full Dashie. Gated on
                // kioskFromHaOnly so we don't undo an explicit kiosk
                // toggle the user made via Settings.
                jsBridge?.subscriptionDelegate?.onSubscriptionUpdated = {
                    // ha_only status now triggers the trial-expired modal
                    // (see TrialExpiredOverlayManager.refresh) so the user
                    // gets explicit UI when they sign in to an ha_only
                    // account, instead of a silent flash-of-dashboard-
                    // then-kiosk. Cold-start with cached ha_only state is
                    // handled separately by maybeApplyHaOnlyKiosk() in
                    // onCreate, which sets kiosk flags BEFORE the initial
                    // URL is determined — so the dashboard never loads
                    // and the modal doesn't need to fire.
                    paywallUi?.refreshTrialSurfaces()
                    maybeRestoreFromHaOnly()
                }
                paywallUi?.refreshTrialSurfaces()

                // Initialize native sidebar strip
                com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("sidebar-init:start")
                initializeSidebar()
                com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("sidebar-init:end")

                // Wire registry callbacks to MainActivity actions (zoom, lock to app, etc.)
                wireHaliteRegistryCallbacks()

                // Initialize Play In-App Updates — checks Play for an update and
                // shows the update banner. The daily refresh window drives the
                // periodic re-check + "tonight"-deferred install. Only functions
                // on Play-installed builds (no-op for sideloaded/debug builds).
                if (BuildConfig.ALLOW_URL_CONFIG) {
                    updateController = com.dashieapp.Dashie.halite.update.DashieUpdateController(
                        this@MainActivity,
                        // "Setup complete" gates the update banner so it doesn't
                        // interrupt onboarding. halitePrefs.isSetupComplete is
                        // flipped true by either:
                        //   - the HA-onboarding callback (JsBridgeHaliteDelegate
                        //     .onOnboardingComplete) for the HA setup path
                        //   - the JS Welcome Wizard exitFullscreen('welcome-wizard')
                        //     signal handled below for the Cloud signin path
                        // Both paths converge on the same flag so this gate
                        // stays simple.
                        isSetupComplete = { halitePrefs?.connection?.isSetupComplete == true }
                    )
                    updateController?.checkForUpdateAutomatic()
                    haliteScreenController?.refreshScheduler?.onDailyRefresh = {
                        updateController?.checkForUpdateAutomatic()
                        // installIfPending stays ungated: it completes an install the
                        // user already consented to via "Update Tonight". The toggle
                        // governs LOOKING for updates, not honouring a prior choice.
                        updateController?.installIfPending()
                    }
                    registerUpdateBannerTestReceiver()

                    // JS-side Welcome Wizard completion → mark setup complete.
                    // The wizard calls DashieNative.exitFullscreen('welcome-wizard')
                    // when completeWizard() runs (see welcome-wizard-controller.js
                    // completeWizard); we use that as the "Cloud onboarding done"
                    // signal so a deferred update banner can finally fire.
                    fullscreenModeManager.onTokenExited = { token ->
                        if (token == "welcome-wizard") {
                            halitePrefs?.connection?.isSetupComplete = true
                            Log.i(TAG, "Welcome wizard exited — marked setup complete, releasing deferred update banner")
                            updateController?.onSetupCompleted()
                        }
                    }
                }

                // Wire WebView client providers after components are created
                dashieWebViewClient?.dialogHostProvider = { dialogHost }
                dashieWebViewClient?.haConnectionMonitorProvider = { haConnectionMonitor }

                // Start RTSP if enabled and check voice
                haliteRegistry?.startRtspIfEnabled()
                haliteRegistry?.checkVoiceOnResume()

                // Register memory monitor providers
                registerMemoryMonitorProviders()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Halite: Handle back specially
                if (BuildConfig.ALLOW_URL_CONFIG) {
                    // Close Control Center if it's visible
                    if (controlCenterOverlay?.isVisible == true) {
                        controlCenterOverlay?.hide()
                        return
                    }

                    // Escape an in-progress inline HA login (Fire TV path):
                    // HA's OAuth page is loaded in the main WebView and has no
                    // window.dashieHandleBack(), so forwarding Back to JS below
                    // would be a no-op and trap the user (D.5).
                    if (haLoginHandler.cancelInlineAuth()) {
                        Log.i(TAG, "🔙 Back pressed: cancelled inline HA login")
                        return
                    }

                    // Amazon Fire TV controller guideline: back on the main
                    // screen must surface an "Exit?" confirmation, not open a
                    // menu (which Amazon's reviewer cites as "infinite loop
                    // of closing and opening the menu"). Query JS for idle
                    // state; if idle on home → show exit dialog. Otherwise
                    // fall through to the existing dashieHandleBack path so
                    // widget focus, open modals, sidebar, settings nav, etc.
                    // all keep their current back behavior intact.
                    if (BuildConfig.FLAVOR == "amazon") {
                        webView.evaluateJavascript(
                            // BUNDLE-EXEMPT: dashieShouldShowExitConfirm — try/catch defaults to false -> normal back handling on kiosk
                            "(function(){try{return !!(window.dashieShouldShowExitConfirm && window.dashieShouldShowExitConfirm())}catch(e){return false}})()"
                        ) { result ->
                            if (result == "true") {
                                Log.i(TAG, "🔙 Back pressed (amazon): home idle → exit confirm")
                                runOnUiThread {
                                    // Use the canonical Exit dialog (same one
                                    // the hamburger menu → Exit triggers).
                                    // For signed-in users it offers Exit /
                                    // Logout / Cancel; for unsigned-in it's
                                    // Exit / Cancel. Falls back to the local
                                    // simple dialog if dialogHost isn't ready
                                    // (early cold-start race).
                                    val host = dialogHost
                                    if (host != null) {
                                        host.showExitConfirmationFromBridge()
                                    } else {
                                        showExitConfirmDialog()
                                    }
                                }
                            } else {
                                // Non-idle fallback: route to handleRemoteInput
                                // (NOT dashieHandleBack) because MainInputHandler's
                                // pre-existing Fire TV BACK path used handleRemoteInput,
                                // and the JS subscribers (widget focus clear, sidebar
                                // close, modal dismiss, settings nav, etc.) are wired
                                // to the 'escape' action it produces. Switching to
                                // dashieHandleBack here would change established
                                // behavior in cases where the dashboard isn't idle.
                                Log.d(TAG, "🔙 Back pressed (amazon): non-idle → handleRemoteInput")
                                webView.evaluateJavascript(
                                    "if(window.handleRemoteInput) window.handleRemoteInput(${android.view.KeyEvent.KEYCODE_BACK});",
                                    null
                                )
                            }
                        }
                        return
                    }

                    Log.d(TAG, "🔙 Back pressed: forwarding to JS (dashieHandleBack)")

                    // Forward back to JS — onboarding, settings modal, and dash bar
                    // all handle back in JavaScript.
                    webView.evaluateJavascript(
                        "if(window.dashieHandleBack) window.dashieHandleBack();",
                        null
                    )
                    return
                }

                // Default (non-Halite): send to JavaScript handler
                webView.evaluateJavascript("handleRemoteInput(${android.view.KeyEvent.KEYCODE_BACK});", null)
            }
        })

        // Check for daily refresh intent from alarm receiver (handles app killed/crashed case)
        // This must be at the end of onCreate because onNewIntent is NOT called when starting fresh.
        // The delay ensures memoryManager and haliteScreenController are initialized.
        if (BuildConfig.ALLOW_URL_CONFIG) {
            lifecycleHandler?.handleDailyRefreshIntent(intent)
        }
    }

    // ControlCenterHost interface — provides state provider to adapter for voice trial styling
    override fun getStateProvider(): com.dashieapp.Dashie.controlcenter.ControlCenterStateProvider? {
        return controlCenterStateProvider
    }

    /**
     * Initialize the native Control Center overlay.
     * Delegates to MainControlCenterFactory which holds the CC-page-id →
     * settings-page-id mapping and the count-fetch JS template.
     */
    private fun initializeControlCenter() {
        val (overlay, sp) = MainControlCenterFactory.create(
            activity = this,
            halitePrefsProvider = { halitePrefs },
            haliteRegistryProvider = { haliteRegistry },
            haliteScreenControllerProvider = { haliteScreenController },
            dashieServiceManagerProvider = { dashieServiceManager },
            dialogHostProvider = { dialogHost },
            webViewProvider = { webView },
            jsBridgeProvider = { jsBridge },
            settingsActivityLauncher = settingsActivityLauncher,
            updateControllerProvider = { updateController }
        ) ?: return
        controlCenterStateProvider = sp
        controlCenterOverlay = overlay
    }

    /**
     * Debug-only: register an adb-triggerable receiver that previews the
     * update banner without a real Play update. Lets us eyeball the banner
     * and the Control Center "Software Update" card on-device:
     *
     *   adb shell am broadcast -a com.dashieapp.Dashie.TEST_UPDATE_BANNER
     *
     * The buttons won't run a real download (no cached update info) — this is
     * a UI preview of the banner + the dismiss → Control Center card path.
     * Guarded by BuildConfig.DEBUG, so it never registers in the Play AAB.
     */
    private fun registerUpdateBannerTestReceiver() {
        if (!BuildConfig.DEBUG) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.i(TAG, "🧪 TEST_UPDATE_BANNER received — showing update banner")
                runOnUiThread { updateController?.showBannerForTest() }
            }
        }
        ContextCompat.registerReceiver(
            this, receiver,
            android.content.IntentFilter("com.dashieapp.Dashie.TEST_UPDATE_BANNER"),
            ContextCompat.RECEIVER_EXPORTED
        )
        updateBannerTestReceiver = receiver
        Log.i(TAG, "🧪 Update-banner test receiver registered (debug only)")
    }

    /**
     * Initialize the native sidebar strip and popout menus.
     * Replaces the JS dash-menu sidebar with native Kotlin views.
     */
    private fun initializeSidebar() {
        if (halitePrefs == null) return

        val sidebarDeviceControlCallbacks = com.dashieapp.Dashie.sidebar.callbacks.SidebarDeviceControlCallbacks(
            activity = this,
            deviceControlsProvider = { deviceControlsCoordinator },
            halitePrefsProvider = { halitePrefs },
            screenControllerProvider = { haliteScreenController },
            dialogHostProvider = { dialogHost },
            voiceControllerProvider = { haliteVoiceController },
            controlCenterShow = { controlCenterOverlay?.show() },
            sidebarRefreshTheme = { isDark -> nativeSidebarController?.refreshTheme(isDark) },
            webViewProvider = { webView },
            urlHandlerProvider = { urlHandler },
            notifyReloadStarting = { fullscreenModeManager.notifyPageUnloading() },
            runOnUiThread = ::runOnUiThread
        ).apply {
            onMusicVolumeChanged = { level -> com.dashieapp.Dashie.halite.HaliteComponentWiring.onSidebarVolumeChanged(level) }
        }

        val sidebar = com.dashieapp.Dashie.sidebar.NativeSidebarController(
            activity = this,
            callbacks = object : com.dashieapp.Dashie.sidebar.NativeSidebarController.Callbacks,
                com.dashieapp.Dashie.sidebar.NativeSidebarController.LockCallbacks by
                    com.dashieapp.Dashie.sidebar.callbacks.SidebarLockCallbacks(
                        halitePrefsProvider = { halitePrefs },
                        dialogHostProvider = { dialogHost }
                    ),
                com.dashieapp.Dashie.sidebar.NativeSidebarController.StateCallbacks by
                    com.dashieapp.Dashie.sidebar.callbacks.SidebarStateCallbacks(
                        halitePrefsProvider = { halitePrefs },
                        screenControllerProvider = { haliteScreenController },
                        webViewProvider = { webView }
                    ),
                com.dashieapp.Dashie.sidebar.NativeSidebarController.DeviceControlCallbacks by
                    sidebarDeviceControlCallbacks,
                com.dashieapp.Dashie.sidebar.NativeSidebarController.VideoFeedControlCallbacks by
                    com.dashieapp.Dashie.sidebar.callbacks.SidebarVideoFeedCallbacks(
                        registryProvider = { haliteRegistry },
                        runOnUiThread = ::runOnUiThread
                    ),
                com.dashieapp.Dashie.sidebar.NativeSidebarController.MusicNavigationCallbacks by
                    com.dashieapp.Dashie.sidebar.callbacks.SidebarMusicNavigationCallbacks(
                        registryProvider = { haliteRegistry },
                        webViewProvider = { webView },
                        runOnUiThread = ::runOnUiThread,
                        activityProvider = { this@MainActivity },
                        // Same launcher pattern CC + JS bridge use — proper
                        // ActivityResult lifecycle, back nav, transitions.
                        openSettingsPage = { pageId ->
                            val intent = android.content.Intent(
                                this@MainActivity,
                                com.dashieapp.Dashie.halite.settings.SettingsActivity::class.java
                            ).putExtra("navigate_to", pageId)
                            settingsActivityLauncher.launch(intent)
                            overridePendingTransition(0, 0)
                        }
                    )
            {}
        )

        sidebar.initialize()
        nativeSidebarController = sidebar

        // Keep the portrait bottom bar's active-view indicator in sync
        // with the sidebar's. Both surfaces mirror the same activeViewId
        // but only one is visible per orientation; without this the
        // bottom bar's blue under-line never moved when the user tapped
        // a different widget icon.
        sidebar.onActiveViewChanged = { viewId ->
            bottomBarController?.setActiveView(viewId)
        }

        // Orientation system — must come AFTER sidebar.initialize() so the
        // bottom bar can mirror the sidebar's button-visibility state.
        // applyLock() runs first to honor the persisted lock at startup
        // (e.g. user rebooted with portrait lock active).
        halitePrefs?.let { prefs ->
            val oc = com.dashieapp.Dashie.halite.orientation.OrientationController(
                activity = this,
                displayPrefs = prefs.display
            )
            val bottomBar = com.dashieapp.Dashie.halite.orientation.BottomBarController(
                activity = this,
                isDarkModeProvider = {
                    com.dashieapp.Dashie.devicecontrols.DarkModeManager
                        .getStoredPreference(this) ?: false
                }
            )
            bottomBar.initialize()
            val gate = haliteRegistry?.nativeWidgetVisibilityGate
            val portraitManager = com.dashieapp.Dashie.halite.orientation.PortraitLayoutManager(
                activity = this,
                orientationController = oc,
                displayPrefs = prefs.display,
                bottomBar = bottomBar,
                onSidebarRestoreNeeded = { nativeSidebarController?.show() },
                onPortraitWidgetsActiveChanged = { active ->
                    nativeSidebarController?.portraitWidgetsActive = active
                },
                gateAllowsChrome = { gate?.shouldShowKioskOrFull() ?: true }
            )
            orientationController = oc
            bottomBarController = bottomBar
            portraitLayoutManager = portraitManager
            // Register the bottom bar with the visibility gate so it
            // participates in the same loading/reloading/dim lifecycle
            // as the sidebar. The bar root is registered for visibility
            // (KIOSK_OR_FULL — same as sidebar); the inner 69dp panel is
            // registered for dim so the JS-modal scrim covers it.
            // PortraitLayoutManager runs AFTER the gate via the
            // onVisibilityChanged hook below and applies the final
            // orientation-aware override.
            gate?.let { g ->
                findViewById<android.view.View>(R.id.bottomBarRoot)?.let { root ->
                    g.register(
                        root,
                        com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.WidgetKind.KIOSK_OR_FULL,
                        dimmable = false
                    )
                }
                findViewById<android.view.View>(R.id.bottomBarPanel)?.let { panel ->
                    g.registerForDim(panel)
                }
            }
            // Apply persisted lock first; portraitManager.initialize() then
            // syncs the visible bar to the (possibly just-applied) state.
            oc.applyLock()
            portraitManager.initialize()

            // Tell JS to regenerate its layout on every orientation change
            // — including physical-rotation flips in Auto mode that don't
            // touch the lock pref (so no ACTION_ORIENTATION_LOCK_CHANGED
            // broadcast fires). The JS layoutService re-reads
            // DashieNative.getCurrentOrientation() inside handleDisplayChanged
            // and swaps to the portrait/landscape preset. Fires once
            // immediately on subscribe (with the current orientation) —
            // handleOrientationChange short-circuits when nothing changed.
            oc.addListener {
                try {
                    webView.evaluateJavascript(
                        "window.dispatchEvent(new CustomEvent('native-settings-changed', { detail: { category: 'display' } }));",
                        null
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "WebView not ready for orientation JS dispatch")
                }
            }
        }

        // Register sidebar's strip with the gate as KIOSK_OR_FULL — gate
        // handles strip visibility (VISIBLE/INVISIBLE) automatically. The
        // sidebar's setGateSuppressed callback covers the secondary effects
        // (WebView leftMargin, popout dismissal) that need to fire whenever
        // the gate decides "hide."
        //
        // dimmable=false: stripRoot is a match_parent × match_parent
        // FrameLayout — its visible content is the 69dp stripPanel inside.
        // Applying the JS-modal dim scrim to stripRoot would put a 75%
        // black foreground over the entire screen, covering the WebView
        // including any JS modal on top. The inner stripPanel is dimmed
        // via gate.registerForDim() further down so the sidebar's actual
        // visible strip still gets the dim treatment.
        haliteRegistry?.nativeWidgetVisibilityGate?.let { gate ->
            sidebar.getStripRoot()?.let {
                gate.register(
                    it,
                    com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.WidgetKind.KIOSK_OR_FULL,
                    dimmable = false
                )
            }
            // Set initial mode from halitePrefs so kiosk users get the sidebar
            // without depending on JS bootstrapping. The kiosk JS bundle
            // (loaded from APK assets in offline mode) doesn't include the
            // session-manager that calls setUiMode, so without this Kotlin-
            // side fallback the gate would stay at OFF forever in kiosk
            // mode and the user would have no way back to settings.
            //
            // JS overrides this once it bootstraps:
            //   - dashboard JS (online auth): setUiMode('full')
            //   - login screen JS: setUiMode('off')
            // Kiosk JS doesn't override — Kotlin's initial decision sticks.
            val accountPrefs = halitePrefs?.account
            val connectionPrefs = halitePrefs?.connection
            val initialMode = when {
                accountPrefs?.isLinked == true && accountPrefs.forceKioskMode != true ->
                    // Linked Dashie account in non-kiosk mode — JS will load
                    // the dashboard and call setUiMode('full'). Start at OFF
                    // to avoid showing native UI before JS asserts.
                    com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.UiMode.OFF
                connectionPrefs?.isSetupComplete == true ->
                    // HA setup complete (kiosk path) — show kiosk surfaces
                    // immediately. The kiosk JS bundle doesn't talk to the
                    // gate, so Kotlin owns this decision.
                    com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.UiMode.KIOSK
                else ->
                    // Fresh install / login screen — hide everything until
                    // JS confirms via setUiMode.
                    com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.UiMode.OFF
            }
            gate.setUiMode(initialMode)

            // Initial sync + ongoing — sidebar handles secondary effects
            // (leftMargin, popout dismissal) that the gate can't.
            sidebar.setGateSuppressed(!gate.shouldShowKioskOrFull())

            // Register the 69dp sidebarStripPanel (the actual visible
            // sidebar) for DIM-ONLY treatment so the JS modal scrim
            // covers it. NOT sidebarStripRoot — that's a match_parent ×
            // match_parent FrameLayout, and putting a 75% black
            // foreground on it would paint over the entire WebView
            // (including the JS modal sitting on top of it). The
            // sidebar manages its own visibility via setGateSuppressed
            // above; registerForDim only touches the foreground, not
            // visibility.
            findViewById<android.view.View>(R.id.sidebarStripPanel)?.let { stripPanel ->
                gate.registerForDim(stripPanel)
            }
            // Also dim the sidebar backdrop strip — a thin View painted with
            // the dashboard's theme color (blue/black) that sits in the gap
            // BEHIND the sidebar panel when the sidebar is pinned. Without
            // this, the JS modal dims everything except this strip, leaving
            // a bright vertical bar next to the sidebar.
            // dim-only (not register()) because NativeSidebarController owns
            // its visibility (GONE / VISIBLE based on pinned state).
            findViewById<android.view.View>(R.id.sidebarBackgroundStrip)?.let { backdrop ->
                gate.registerForDim(backdrop)
            }
            // FULL mode → Dashie JS canvas owns the sidebar offset; KIOSK mode
            // (HA shell) → Kotlin applies the WebView leftMargin. Drives which
            // path shifts the dashboard out from behind the pinned sidebar.
            sidebar.setJsLayoutOwnsOffset(
                gate.getMode() == com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.UiMode.FULL
            )
            gate.onVisibilityChanged = {
                nativeSidebarController?.setGateSuppressed(!gate.shouldShowKioskOrFull())
                nativeSidebarController?.setJsLayoutOwnsOffset(
                    gate.getMode() == com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.UiMode.FULL
                )
                // Re-evaluate trial UI on every gate transition so a flip
                // back to FULL after a reload re-shows the chip/overlay
                // (gate's OVERLAY_FULL_ONLY auto-hides but doesn't auto-show).
                paywallUi?.refreshTrialSurfaces()
                // Run the portrait-mode swap after the gate has applied
                // its base visibility so the bottom bar is hidden during
                // loading/edit-mode and shown in portrait once the gate
                // re-enables chrome.
                portraitLayoutManager?.reapply()
            }
        }

        // Route edge swipe gesture to native sidebar instead of JS dash menu
        dialogHost?.onRevealNativeSidebar = {
            runOnUiThread { sidebar.revealSidebar() }
        }

        Log.i(TAG, "📌 Native sidebar controller initialized")
    }

    /**
     * Wire up all HaliteComponentRegistry callbacks to MainActivity actions.
     * This connects the registry's action callbacks to methods in MainActivity.
     */
    /**
     * (Re)apply every jsBridge-level callback/provider. Called at initial wiring AND from
     * setJsBridge() after a WebView memory-recovery recreation — a fresh DashieJSBridge +
     * delegates start with these null, so without re-applying they silently drop JS→native
     * calls (the onVoiceResponse class of bug). One source of truth for all of them.
     */
    private fun applyJsBridgeCallbacks(bridge: DashieJSBridge?) {
        if (bridge == null) return
        currentVoiceOverlayBridge?.let { bridge.voiceOverlayBridge = it }
        bridge.onTtsSpeechEndCallback = { runOnUiThread { haliteVoiceController?.onTtsSpeechEnd() } }
        bridge.onVoiceProgressCallback = { status -> runOnUiThread { haliteVoiceController?.updateProcessingStatus(status) } }
        // Cascade conversation overlay (Dialog mode) — JS loop drives Live's overlay (§2).
        bridge.onStartConversationOverlayCallback = { runOnUiThread { haliteVoiceController?.startConversationOverlay() } }
        bridge.onSetConversationListeningCallback = { listening -> runOnUiThread { haliteVoiceController?.setConversationOverlayListening(listening) } }
        bridge.onConversationTranscriptCallback = { speaker, text -> runOnUiThread { haliteVoiceController?.conversationOverlayTranscript(speaker, text) } }
        bridge.onConversationCardCallback = { json -> runOnUiThread { haliteVoiceController?.conversationOverlayCard(json) } }
        bridge.onEndConversationOverlayCallback = { leaveUp -> runOnUiThread { haliteVoiceController?.endConversationOverlay(leaveUp) } }
        bridge.musicHaConnectionProvider = provider@{
            val prefs = haliteRegistry?.prefs ?: return@provider null
            val haUrl = prefs.connection.haUrl.takeIf { it.isNotEmpty() } ?: return@provider null
            val haToken = prefs.connection.haAccessToken.takeIf { it.isNotEmpty() } ?: return@provider null
            val entityId = prefs.connection.getEffectiveMusicPlayerEntityId()
            com.dashieapp.Dashie.webview.delegates.JsBridgeMusicDelegate.HaConnectionInfo(haUrl, haToken, entityId)
        }
        // Stealth (scheduled) refresh live-update: JS-page / cloud→native writes to
        // setStealthRefreshEnabled/IntervalMinutes fire this so the running scheduler
        // re-reads prefs and reschedules/cancels the alarm without a reload. Wired here
        // (not in setup()) so it survives the nightly memory-recovery WebView recreation.
        bridge.onStealthRefreshSettingsChanged = { runOnUiThread { haliteScreenController?.refreshStealthReloadSettings() } }
        bridge.lightSensorProvider = { haliteRegistry?.lightSensorController }
        bridge.nativeSidebarProvider = { nativeSidebarController }
        bridge.settingsSyncNotifierProvider = { haliteRegistry?.settingsSyncNotifier }
        // Subscription sync (trial overlays + ha_only restore). Also wired initially in
        // the registry-init block; re-applied here so it survives recreation.
        bridge.subscriptionDelegate?.onSubscriptionUpdated = {
            paywallUi?.refreshTrialSurfaces()
            maybeRestoreFromHaOnly()
        }
        // Subscription modals (also wired initially in MainWebViewBootstrap). The nightly
        // memory-recovery recreation rebuilds the delegate, so re-apply here too.
        bridge.subscriptionDelegate?.onShowTrialConfirmation = { days -> runOnUiThread { showTrialConfirmationDialog(days) } }
        bridge.subscriptionDelegate?.onShowAccountAlreadyExistsNotice = { runOnUiThread { showAccountAlreadyExistsDialog() } }
        // Halite-owned bridge callbacks (perf overlay, thresholds, JWT-saved) — these
        // lived in MainHaliteSetup.setup() as one-shot assignments and silently died
        // on the nightly memory-recovery recreation; re-applied here every time.
        MainHaliteSetup.applyBridgeCallbacks(bridge, this, halitePrefs, haliteRegistry) { webView }
    }

    private fun wireHaliteRegistryCallbacks() {
        val registry = haliteRegistry ?: return

        // Action callbacks
        registry.onStartLockToApp = { kioskController?.startLockToApp() }
        registry.onStopLockToApp = { kioskController?.stopLockToApp() }
        registry.onApplyKeepScreenOn = { keepOn -> kioskController?.applyKeepScreenOnSetting(keepOn) }
        registry.onApiEnabledChanged = { enabled -> serviceManager?.handleApiEnabledChange(enabled) }
        registry.onApiPermissionsCheck = { permissionDelegate.checkAndRequestApiPermissions(force = true) }
        registry.onExitApp = { kioskController?.exitAppSafely() }
        registry.onLogout = {
            Log.w("MainActivity", "🔐🔐🔐 registry.onLogout — sending sign-out broadcast")
            // Clear WebView cookies to prevent Google auto-login
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            // Trigger sign-out broadcast
            val intent = android.content.Intent("com.dashieapp.Dashie.ACTION_DASHIE_SIGN_OUT").apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }
        registry.onRestartApp = { lifecycleHandler?.performRestartApp() }
        registry.onRebootDevice = { lifecycleHandler?.performRebootDevice() }
        registry.onRtspEnabledChanged = { enabled -> permissionDelegate.handleRtspEnabledChange(enabled) }
        registry.onRtspMotionDetectionChanged = { enabled -> serviceManager?.handleRtspMotionDetectionChange(enabled) }
        registry.onRestartRtspServerIfNeeded = { serviceManager?.handleRestartRtspServerIfNeeded() }
        registry.onApplyDashboardZoom = { zoomPercent -> kioskController?.applyDashboardZoom(zoomPercent) }
        registry.onMusicPlayerEnabledChanged = { enabled -> serviceManager?.handleMusicPlayerEnabledChange(enabled) }

        // Dismiss overlays (control center, sidebar popouts) on screen off.
        // Also hide the native focus ring — it's painted above the WebView and
        // above ScreenDimmer's overlay, so without an explicit hide it stays
        // visible on top of a "screen off" dim/black overlay.
        registry.screenController?.onDismissOverlays = {
            controlCenterOverlay?.hide()
            nativeSidebarController?.hide()
            haliteRegistry?.focusRingManager?.hide()
        }
        // Re-apply native sidebar visibility after screen on (hidden on screen
        // off via onDismissOverlays; without this it never reappears when the
        // wake comes from the API/integration rather than user input).
        registry.screenController?.onRestoreOverlays = {
            nativeSidebarController?.applySidebarVisibility()
        }

        // Permission callbacks
        registry.hasCameraPermission = { haliteScreenController?.hasCameraPermission() ?: false }
        registry.requestCameraPermission = { onResult ->
            haliteScreenController?.requestCameraPermission(onResult) ?: onResult(false)
        }
        registry.hasMicrophonePermission = { permissionDelegate.hasMicrophonePermission() }

        // VoiceOverlayBridge callback - wire to DashieJSBridge when bridge is ready
        registry.onVoiceOverlayBridgeReady = { bridge ->
            Log.i(TAG, "🎤 VoiceOverlayBridge ready, wiring to DashieJSBridge")
            currentVoiceOverlayBridge = bridge   // remembered so setJsBridge() re-wires after WebView recreation
            jsBridge?.voiceOverlayBridge = bridge
        }

        // Voice/music/sensor/sidebar/subscription jsBridge callbacks — applied through a
        // single helper so they survive WebView recreation (see applyJsBridgeCallbacks).
        applyJsBridgeCallbacks(jsBridge)

        // Wire DashieWebViewClient callbacks for stealth reload URL restoration
        dashieWebViewClient?.stealthReloadUrlProvider = {
            registry.screenController?.getSavedUrlBeforeReload()
        }
        dashieWebViewClient?.onStealthReloadComplete = {
            // Clear the saved URL so it's not restored again
            registry.screenController?.let { controller ->
                Log.i(TAG, "🔄 Stealth reload URL restoration complete")
            }
        }

        // When navigating to kiosk shell, remove the photo widget (no layout
        // canvas in kiosk) and force the visibility gate to KIOSK mode.
        // The kiosk JS bundle doesn't include session-manager so it never
        // calls setUiMode — Kotlin owns the FULL -> KIOSK transition.
        dashieWebViewClient?.onKioskShellLoaded = {
            registry.photoWidgetController?.removeWidget()
            // Only assert KIOSK (which shows the native sidebar) once setup is
            // complete. The shell also loads DURING HA onboarding/login — if we
            // forced KIOSK there, the sidebar popped open over HA's login page
            // on every navigation. While setup is incomplete, keep the gate OFF
            // so the sidebar stays hidden. onOnboardingComplete sets
            // isSetupComplete=true and THEN reloads the shell, so this fires
            // again with the flag true and the sidebar appears post-setup.
            val mode = if (halitePrefs?.connection?.isSetupComplete == true) {
                com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.UiMode.KIOSK
            } else {
                com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.UiMode.OFF
            }
            registry.nativeWidgetVisibilityGate.setUiMode(mode)
        }

        // Wire dark mode handler to update native widgets on theme change
        darkModeHandler?.onThemeApplied = { isDark ->
            registry.photoWidgetController?.applyTheme(isDark)
            registry.weatherDailyWidgetController?.applyTheme(isDark)
            registry.weatherHourlyWidgetController?.applyTheme(isDark)
        }

        Log.i(TAG, "🔌 HaliteRegistry callbacks wired to MainActivity")
    }

    /**
     * Register component status providers with MemoryMonitor for detailed diagnostics.
     */
    private fun registerMemoryMonitorProviders() {
        val monitor = com.dashieapp.Dashie.halite.diagnostics.MemoryMonitor

        // RTSP cache status
        monitor.rtspCacheStatusProvider = {
            dashieServiceManager?.let { mgr ->
                // Access the rtspCameraServer2 via reflection or public method
                // For now, just report running state
                val running = mgr.isRtspServerRunning()
                val clients = mgr.getRtspClientCount()
                val watchdog = mgr.getRtspWatchdogStatus()
                "running=$running clients=$clients watchdog=$watchdog"
            } ?: "not_initialized"
        }

        // Photo cache status
        monitor.photoCacheStatusProvider = {
            com.dashieapp.Dashie.halite.screensaver.PhotoCache.getMemoryStatus()
        }

        // HA connection status
        monitor.haConnectionStatusProvider = {
            haConnectionMonitor?.getMemoryStatus() ?: "not_initialized"
        }

        // Wake word detector status
        monitor.wakeWordStatusProvider = {
            haliteVoiceController?.getWakeWordMemoryStatus() ?: voiceSetup?.getVoiceAssistant()?.getWakeWordMemoryStatus() ?: "not_initialized"
        }

        // Screensaver status (slideshow state, mode, photo count)
        monitor.screensaverStatusProvider = {
            haliteScreenController?.getMemoryStatus() ?: "not_initialized"
        }

        // Keystone: apply the authoritative route the gateway stamps on every converse response
        // (X-Dashie-Brain-Route). BrainConverseClient (stateless, no prefs) calls the applier;
        // this installs the Context-holding sink so a stale brain_route self-corrects next turn.
        // Lambda reads halitePrefs live (survives WebView/pref recreation).
        com.dashieapp.Dashie.halite.voice.BrainRouteApplier.applyFn = { route ->
            halitePrefs?.voice?.applyBrainRoute(route)
        }

        // 🔴 The header was ABSENT. Benign on a direct-to-edge-function turn (only the GATEWAY
        // stamps it), so this must not shout on every healthy cloud turn — but when this device
        // is cached on `local`, absence means the keystone cannot self-correct and the turn
        // dead-ends with no user-facing error. That silent case is T's V3 red row.
        //
        // Rate-limited to one line per 5 minutes so a stranded device stays greppable through a
        // long session without flooding the log.
        com.dashieapp.Dashie.halite.voice.BrainRouteApplier.onAbsent = {
            val vp = halitePrefs?.voice
            val governs = halitePrefs?.householdAnswersGovern ?: true
            if (vp != null && vp.effectiveUseLocalBrain(governs)) {
                val now = System.currentTimeMillis()
                if (now - lastBrainRouteAbsentLogMs > 5 * 60_000L) {
                    lastBrainRouteAbsentLogMs = now
                    android.util.Log.w(
                        "MainActivity",
                        "DROP: converse response carried NO X-Dashie-Brain-Route while this " +
                            "device is cached route=local — the keystone self-correction cannot " +
                            "fire, so a stale local route will strand and every turn dead-ends " +
                            "silently. Either the turn did not go via the gateway, or the " +
                            "gateway is not stamping the header this device reads (a branded " +
                            "integration emitting a different header name would look exactly " +
                            "like this). Recover now: :2323/?cmd=refreshVoiceConfig"
                    )
                }
            }
        }

        // Brain route status — the add-on-reported route, its staleness, and any recent
        // local-brain failure. Makes the "stale brain_route='local'" strand visible in the
        // heartbeat (e.g. "route=local (effective) checkedAt=9m lastFail=ioexception 2m ago").
        monitor.brainRouteStatusProvider = {
            halitePrefs?.voice?.let { vp ->
                val now = System.currentTimeMillis()
                val route = vp.brainRoute.ifEmpty { "(unreported)" }
                val governs = halitePrefs?.householdAnswersGovern ?: true
                val effective = if (vp.effectiveUseLocalBrain(governs)) "local" else "cloud"
                val checkedAt = vp.brainRouteCheckedAtMs
                val ageStr = if (checkedAt <= 0L) "never" else "${(now - checkedAt) / 60_000L}m"
                "route=$route effective=$effective checkedAt=$ageStr " +
                    com.dashieapp.Dashie.halite.voice.BrainRouteHealth.heartbeatStatus(now)
            } ?: "not_initialized"
        }

        // Persist current WebView URL for OOM recovery (captures SPA navigation)
        monitor.persistCurrentUrlCallback = {
            webView.evaluateJavascript(
                "(function() { return window.location.href; })()"
            ) { result ->
                val url = result?.trim('"')?.replace("\\", "") ?: ""
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    val isAuth = url.contains("/auth/") || url.contains("/authorize") ||
                        url.contains("/login") || url.contains("auth_callback")
                    if (!isAuth) {
                        halitePrefs?.performance?.lastWebViewUrl = url
                    }
                }
            }
        }

        // Diagnostic state provider for crash reports (persisted every 30s, survives OOM kills)
        monitor.diagnosticStateProvider = {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val dimmer = haliteScreenController?.screenDimmer
            com.dashieapp.Dashie.halite.diagnostics.MemoryMonitor.DiagnosticState(
                wifiLockHeld = wifiLockManager?.isHeld ?: false,
                serviceBound = dashieServiceManager?.isBound ?: false,
                screensaverActive = dimmer?.isDimmed() ?: false,
                screensaverMode = halitePrefs?.screensaver?.screensaverMode ?: "unknown",
                screenInteractive = pm.isInteractive
            )
        }

        Log.i(TAG, "📊 Memory monitor providers registered")
    }

    /**
     * Check if device is a tablet (delegates to DeviceInfoHelper)
     */
    private fun isTabletDevice(): Boolean = DeviceInfoHelper.isTablet(this)

    // WebView bridge delegate methods
    private fun notifyWebView(event: String, data: String) = webViewBridge.notifyWebView(event, data)
    private fun sendAudioToWebView(audioData: ByteArray) = webViewBridge.sendAudioToWebView(audioData)
    private fun sendAudioLevelToWebView(amplitude: Float) = webViewBridge.sendAudioLevelToWebView(amplitude)
    private fun sendHeartbeatToWebView(confidence: Float, volume: Float) = webViewBridge.sendHeartbeatToWebView(confidence, volume)
    private fun sendLiveSampleToWebView(wavData: ByteArray, metadata: String) = webViewBridge.sendLiveSampleToWebView(wavData, metadata)

    private fun logAppVersion() {
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = pInfo.versionName ?: "unknown"
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            val displayUrl = if (BuildConfig.ALLOW_URL_CONFIG && halitePrefs != null) {
                halitePrefs!!.connection.haUrl
            } else {
                BuildConfig.BASE_URL
            }
            Log.e(TAG, "========================================")
            Log.e(TAG, "🚀 DASHIE APP STARTING")
            Log.e(TAG, "📱 Version: $versionName (code: $versionCode)")
            // 🔴 THE build discriminator. Since 2026-08-02 versionName is a hand-assigned letter
            // suffix and versionCode is frozen until a store release, so the line above no longer
            // tells two builds apart — T found a Mio and a Samsung both reporting 1.0.16/176 ten
            // hours and a whole feature apart. This line is what answers "which build is this".
            Log.e(TAG, "🔖 Build: ${BuildConfig.GIT_SHA}")
            Log.e(TAG, "🌐 Environment: ${BuildConfig.ENVIRONMENT}")
            Log.e(TAG, "🔗 URL: $displayUrl")
            Log.e(TAG, "📦 Package: $packageName")
            Log.e(TAG, "🎯 Wake Word Threshold: ${com.dashieapp.Dashie.wakeword.edgeimpulse.EdgeImpulseConfig.DETECTION_THRESHOLD}")
            Log.e(TAG, "========================================")
        } catch (e: Exception) {
            Log.e(TAG, "Error logging app version: ${e.message}")
        }
    }

    /**
     * Handle wake word sample recording request
     */
    private fun forwardKeyToJs(keyCode: Int) = webViewBridge.forwardKeyToJs(keyCode)

    /**
     * Halite: Handle key events at dispatch level to ensure screen wake works for ALL keys.
     * Delegated to MainInputHandler.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // D.45 — wake on any key down (Fire TV is d-pad only; touch wake
        // never fires there). Sleep state goes through HaliteScreenController
        // (it wraps dimmer.screenOn + restarts inactivity timer + restores
        // overlays). Pure dim state goes through dimmer.onKeyEvent. Both
        // consume the key when they actually wake (first key just wakes).
        if (event.action == KeyEvent.ACTION_DOWN) {
            val screenCtrl = haliteScreenController
            val sleeping = screenCtrl?.isScreenOff() == true
            val dimmed = screenCtrl?.screenDimmer?.isDimmed() == true
            Log.d(TAG, "🔑 dispatchKeyEvent: keyCode=${event.keyCode} sleeping=$sleeping dimmed=$dimmed")
            if (sleeping) {
                Log.i(TAG, "🔑 Key while sleeping — calling screenController.screenOn()")
                screenCtrl?.screenOn()
                return true
            }
            if (screenCtrl?.screenDimmer?.onKeyEvent(true) == true) {
                return true
            }
        }
        // Control Center overlay intercepts all D-pad + BACK keys when visible
        if (event.action == KeyEvent.ACTION_DOWN && controlCenterOverlay?.isVisible == true) {
            if (controlCenterOverlay?.handleDpadKey(event.keyCode) == true) {
                return true
            }
        }
        // Video feed overlay (strip + Frigate playback) — a native overlay that
        // owns the remote on TV while it's capturing. Must run ABOVE the sidebar
        // block below: the strip is opened from the sidebar's video button, so
        // the sidebar still has focus and would otherwise consume the d-pad.
        // Unlike the control center, this needs both key edges (center
        // short/long-press, tiered scrub) so we don't gate on ACTION_DOWN here.
        haliteRegistry?.videoFeedManager?.let { vfm ->
            if (vfm.isCapturingDpad()) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE ->
                        if (event.action == KeyEvent.ACTION_DOWN && vfm.handleDpadBack()) return true
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_CENTER ->
                        if (vfm.handleDpadKey(event)) return true
                }
            } else if (event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) &&
                (DeviceInfoHelper.isFireTV() || DeviceInfoHelper.isTVDevice(this))) {
                // A triggered camera alert pop-up has no remote-reachable dismiss
                // (the dismiss pill is touch-only). BACK clears it on TV.
                if (vfm.dismissTriggeredAlerts()) return true
            }
        }
        // Update banner intercepts D-pad while it's showing — the kiosk routes
        // d-pad to JS, so the native banner claims the keys itself. It yields
        // to a JS overlay that has keyboard focus (e.g. the post-onboarding
        // tips card), then takes d-pad once that clears.
        if (event.action == KeyEvent.ACTION_DOWN &&
            jsBridge?.overlayHasKeyboardFocus != true &&
            updateController?.handleBannerDpadKey(event.keyCode) == true) {
            return true
        }
        // Bottom status banner (download / install in progress) — its
        // single Dismiss pill claims d-pad center/enter and swallows
        // directional keys so they don't leak to the dashboard.
        if (event.action == KeyEvent.ACTION_DOWN &&
            jsBridge?.overlayHasKeyboardFocus != true &&
            updateController?.handleStatusBannerDpadKey(event.keyCode) == true) {
            return true
        }
        // Native sidebar D-pad navigation
        if (event.action == KeyEvent.ACTION_DOWN) {
            val sidebar = nativeSidebarController
            // When a JS overlay has claimed keyboard focus (e.g. the kiosk
            // onboarding tip cards — JS opens the hamburger popout for visual
            // context, then overlays a tip card with its own buttons), let
            // d-pad fall through to MainInputHandler, which forwards to JS
            // where window.dashieOnboardingDpad routes within the overlay.
            // Skipping the entire sidebar block is necessary because both the
            // popout-routing branch below AND sidebar.handleDpadKey() (when
            // popout is visible) would otherwise consume the key.
            val overlayFocused = jsBridge?.overlayHasKeyboardFocus ?: false
            if (sidebar != null && !overlayFocused) {
                // When a popout is showing, route d-pad to the sidebar's popout
                // handler which manually navigates focusable views. Android's
                // spatial focus search can't reliably find popout views since
                // they're added to the decorView with margin-based positioning.
                if (sidebar.isPopoutShowing) {
                    if (sidebar.handleDpadKey(event.keyCode)) {
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }

                // Portrait bottom bar has focus → route d-pad to it. Comes after
                // the popout check so popouts opened from the bottom bar (they
                // reuse the sidebar's popout) still route to the sidebar's popout
                // handler above.
                val bottomBar = bottomBarController
                if (bottomBar != null && bottomBar.hasFocus &&
                    bottomBar.handleDpadKey(event.keyCode)) {
                    return true
                }

                // If sidebar has focus (a button is focused), route d-pad to sidebar
                if (sidebar.isVisible && sidebar.handleDpadKey(event.keyCode) == true) {
                    return true
                }
                // DPAD_LEFT or BACK when sidebar is not focused: focus/reveal the sidebar.
                // Skip during onboarding, login, and when overlay has keyboard focus
                // (those modes need these keys for their own navigation).
                //
                // In Dashie dashboard mode (isLinked && !forceKiosk), LEFT and BACK
                // are both reserved for JS widget navigation — the JS
                // LayoutCanvasInputHandler decides when to yield to the sidebar
                // (calls DashieNative.focusSidebar()) vs. consuming for widget
                // defocus/menu-close. In kiosk mode there's no JS grid, so LEFT
                // and BACK still auto-focus the sidebar.
                val accountLinked = halitePrefs?.account?.isLinked == true
                val forceKiosk = halitePrefs?.account?.forceKioskMode == true
                val isDashieMode = accountLinked && !forceKiosk
                val isSidebarTrigger = !isDashieMode && (
                        event.keyCode == KeyEvent.KEYCODE_BACK ||
                        event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
                if (isSidebarTrigger && !sidebar.hasFocus) {
                    val isOnboarding = halitePrefs?.connection?.isSetupComplete != true
                    val currentUrl = webView.url ?: ""
                    // A WebView URL containing "/login" is ambiguous: on the Dashie
                    // webapp the dashboard is served from /login/ (vercel.json rewrite
                    // of / → /login/index.html). Only treat it as the login SCREEN
                    // when the user hasn't completed Dashie auth yet.
                    val isOnAuthPath = currentUrl.contains("/auth/") || currentUrl.contains("/login")
                    val isLoginScreen = isOnAuthPath && !accountLinked
                    val overlayFocused = jsBridge?.overlayHasKeyboardFocus ?: false
                    if (!isOnboarding && !isLoginScreen && !overlayFocused) {
                        sidebar.focusSidebar()
                        return true
                    }
                }
            }
        }
        if (inputHandler.handleDispatchKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Handle touch events at Activity level for Halite sidebar edge swipe detection.
     * Delegated to MainInputHandler.
     */
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        // Let the WebView and other views handle the touch first
        val handled = super.dispatchTouchEvent(event)
        // Then let input handler process for sidebar gestures
        inputHandler.handleDispatchTouchEvent(event)
        return handled
    }

    /**
     * Handle key down events for navigation, sidebar, and volume.
     * Delegated to MainInputHandler.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val result = inputHandler.handleOnKeyDown(keyCode, event)
        return when {
            result.handled -> true
            result.callSuper -> super.onKeyDown(keyCode, event)
            else -> false
        }
    }

    private fun notifyVolumeChange(level: Int) = webViewBridge.notifyVolumeChange(level)

    override fun onResume() {
        android.util.Log.w("DarkModeTrace", ">>> onResume BEFORE super, uiMode=${resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK}")
        super.onResume()
        android.util.Log.w("DarkModeTrace", ">>> onResume AFTER super, uiMode=${resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK}")
        // Re-apply dark mode preference — the system may have reset resources
        // between onCreate and onResume (e.g., config change during splash screen)
        com.dashieapp.Dashie.devicecontrols.DarkModeManager.applyStoredPreference(this)
        android.util.Log.w("DarkModeTrace", ">>> onResume AFTER applyStoredPreference, uiMode=${resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK}")
        lifecycleHandler?.onResume()
        broadcastManager?.onResume()
        // Resume a sideload update install that was blocked on the
        // "install unknown apps" grant — the user may have just granted it.
        updateController?.onActivityResumed()
        maybeHandlePendingHaLoginRequest()

        // Re-assert sidebar visibility against pinned/enabled/gate state. If
        // anything during the sleep cycle put the sidebar's stripRoot into a
        // hidden state (transient hide() call, dim-overlay teardown ordering,
        // a fullscreen-modal token that didn't release before the activity
        // paused), applySidebarVisibility re-evaluates from prefs and either
        // pins it visible or hides it cleanly. Idempotent — no effect if the
        // sidebar is already in the right state.
        nativeSidebarController?.applySidebarVisibility()
    }

    /**
     * One-shot follow-up to the "Sign in to Home Assistant" row in the
     * native HA settings page. SettingsActivity sets the flag and
     * finishes; we read + clear it here and launch HA login. Routed
     * through onResume rather than evaluateJavascript so we don't race
     * the WebView with the activity teardown.
     */
    private fun maybeHandlePendingHaLoginRequest() {
        val prefs = halitePrefs ?: return
        val conn = prefs.connection
        if (!conn.pendingHaLoginRequest) return
        conn.pendingHaLoginRequest = false
        val haUrl = conn.haUrl
        if (haUrl.isEmpty() || haUrl == com.dashieapp.Dashie.halite.HalitePreferences.DEFAULT_HA_URL) {
            Log.w(TAG, "🏠 pendingHaLoginRequest set but haUrl is empty/default — ignoring")
            return
        }
        Log.i(TAG, "🏠 pendingHaLoginRequest — launching HA login for $haUrl")
        // Defer slightly so the settings-finish animation completes before
        // the HA login activity / WebView nav slides in.
        window.decorView.post {
            haLoginHandler.launchHaLogin(haUrl)
        }
    }

    /**
     * Handle new intents when activity is brought to foreground.
     * Delegates to urlHandler for all URL intent handling.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)  // Update the stored intent
        urlHandler.handleNewIntent(intent)

        // Handle daily refresh intent from alarm receiver
        lifecycleHandler?.handleDailyRefreshIntent(intent)
    }

    /**
     * Update response handling mode for voice assistant.
     * Called when user changes response handling setting in dialogs.
     * Updates both TTS (read) and display behavior based on the mode.
     */
    fun updateResponseHandling(mode: String) {
        val showResponses = (mode == HalitePreferences.RESPONSE_HANDLING_READ_AND_DISPLAY ||
                           mode == HalitePreferences.RESPONSE_HANDLING_DISPLAY_ONLY)
        runOnUiThread {
            haliteVoiceController?.setShowResponses(showResponses)
            Log.i(TAG, "🔧 Response handling updated: mode=$mode, showResponses=$showResponses")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        lifecycleHandler?.onWindowFocusChanged(hasFocus)
    }

    /** Receives Play in-app update consent-dialog results (and any other
     *  startActivityForResult callers we might add later). The Play
     *  AppUpdateManager flow uses the legacy onActivityResult mechanism;
     *  if the user cancels the consent dialog, RESULT_CANCELED arrives
     *  here BEFORE any install-state listener fires, so this is the
     *  only path that catches consent-dialog cancellation cleanly. */
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        updateController?.handleActivityResult(requestCode, resultCode)
    }

    override fun onPause() {
        super.onPause()
        lifecycleHandler?.onPause()
    }

    override fun onStop() {
        super.onStop()
        lifecycleHandler?.onStop()
    }

    /**
     * Switch WebView viewport for dashieapp.com.
     * Uses wide viewport (same as kiosk mode) for consistent rendering.
     */
    private fun setDashieViewport() {
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        Log.i(TAG, "🔐 Viewport set for dashieapp.com (wide viewport, matches kiosk)")
    }

    /**
     * Switch WebView viewport back for HA dashboards / kiosk shell.
     * Enables wide viewport for native HA dashboard resolution.
     */
    private fun setHaliteViewport() {
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        Log.i(TAG, "🏠 Viewport set for HA (wide viewport enabled)")
    }

    override fun onDestroy() {
        liveInstanceCount--
        // Log lifecycle event with enhanced diagnostics for debugging unexpected app closures
        if (BuildConfig.ALLOW_URL_CONFIG) {
            PersistentLog.info("LIFECYCLE", "onDestroy()")
            // Log if this was a "finishing" destroy vs config change
            PersistentLog.info("LIFECYCLE", "onDestroy: isFinishing=${isFinishing}, isChangingConfigurations=${isChangingConfigurations}")
            com.dashieapp.Dashie.halite.diagnostics.CrashDiagnosticsCollector.persistLifecycleEvent(
                applicationContext, "onDestroy",
                "isFinishing=$isFinishing,isChangingConfigurations=$isChangingConfigurations"
            )
            com.dashieapp.Dashie.halite.diagnostics.MemoryMonitor.stop()
            // Mark clean shutdown (so we can detect OOM kills on next startup)
            halitePrefs?.performance?.appWasRunning = false
            // Unregister broadcast receivers
            broadcastManager?.unregister()
            broadcastManager = null
        }

        // Destroy Control Center overlay (cancel coroutines)
        controlCenterOverlay?.destroy()

        // Release the Play update install-state listener
        updateController?.cleanup()
        updateBannerTestReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            updateBannerTestReceiver = null
        }

        // Destroy native sidebar controller
        nativeSidebarController?.destroy()

        // Notify WebViewClient that we're being destroyed - prevents logging normal
        // renderer termination as an error (renderer terminates when Activity finishes)
        dashieWebViewClient?.notifyActivityDestroyed()

        // Stop Sendspin speaker connection (stops audio on remote speakers)
        com.dashieapp.Dashie.halite.HaliteComponentWiring.stopSendspin()

        super.onDestroy()
        memoryManager?.destroy()  // Cleanup memory manager (proactive refresh scheduler)
        voiceSetup?.shutdown()
        haliteVoiceController?.shutdown()  // Shutdown Halite voice control
        haliteScreenController?.destroy()  // Cleanup screen dimmer and motion wake
        dashboardTelemetryBridge?.destroy()  // Cleanup telemetry coroutine scope
        haConnectionMonitor?.destroy()  // Cleanup HA connection monitor ping loop
        dashboardHealthCoordinator?.stop()  // Cancel iframe-recovery backoff loop
        if (BuildConfig.DEBUG) com.dashieapp.Dashie.halite.diagnostics.DebugTestInjector.unregister(this)
        lightSensorBrightnessController?.destroy()  // Cleanup light sensor
        wifiLockManager?.release()  // Release WiFi lock
        com.dashieapp.Dashie.halite.DashieKeepAliveService.stop(this)  // Stop keep-alive service
        dashieSsdpService?.stop()  // Stop SSDP discovery (Standard Dashie)
        dashieServiceManager?.stopService()
        ttsManager?.shutdown()
        rtspPlayerManager?.release()  // Release RTSP player overlays
        haliteRegistry?.timerOverlayManager?.destroy()  // Cleanup Kotlin timer overlays
    }

    /**
     * Handle system memory pressure notifications.
     * This is called BEFORE the system kills the app for memory, giving us a chance to:
     * 1. Log the memory pressure level for diagnostics
     * 2. Release caches and non-critical resources
     *
     * If we see these logs followed by a silent app restart (no onDestroy),
     * it indicates the system killed us for memory.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        memoryManager?.handleTrimMemory(level)
    }

    // NOTE: handleWebViewCrashRecovery() and recreateWebViewForMemoryRecovery() moved to MainWebViewRecreation

    /**
     * Create DashieJSBridge callbacks using Kotlin `by` delegation.
     * Each sub-interface is implemented by a focused class in webview/callbacks/.
     * All providers use lambdas so they automatically pick up new references during WebView recreation.
     */

    /**
     * Re-apply the ha_only kiosk lockdown when the server says ha_only
     * but the local kiosk flags aren't set. Recovers from the state
     * where convert_to_ha_only ran successfully on the server (and got
     * cached in SubscriptionPreferences) but the local kiosk flags
     * (forceKioskMode / kioskFromHaOnly / layoutMode=kiosk) are missing —
     * typically because the user signed out + signed back in, which
     * clears device prefs but doesn't reset the server-side ha_only
     * status.
     *
     * Without this, an ha_only user lands on the full Dashie dashboard
     * with no Dashie features (since the Dashie Features section is
     * suppressed for ha_only) and no path back — a broken limbo.
     *
     * @param broadcastReload true to fire SWITCH_TO_KIOSK after applying
     *   flags (use when the dashboard has already loaded — sub-sync
     *   callback). false to just set the flags (use during onCreate
     *   before determineInitialUrl() — no reload needed).
     */
    private fun maybeApplyHaOnlyKiosk(broadcastReload: Boolean) {
        val prefs = halitePrefs ?: return
        // Kiosk-mode RECOVERY, not a paywall — the seam answers this honestly per edition.
        // Chickadee answers true (it is always an HA-display device), which is what makes the
        // lockdown re-apply correctly there rather than being skipped as a "paid" concern.
        if (!com.dashieapp.Dashie.edition.EditionSeams.subscription(this).isHaOnlyPlan()) return
        // Already in ha_only kiosk mode — nothing to do.
        if (prefs.account.kioskFromHaOnly && prefs.account.forceKioskMode) return

        Log.i(TAG, "ha_only status detected but local kiosk flags missing — re-applying lockdown (broadcastReload=$broadcastReload)")
        prefs.connection.haEnabled = true
        prefs.display.layoutMode = "kiosk"
        prefs.account.forceKioskMode = true
        prefs.account.kioskFromHaOnly = true

        if (broadcastReload) {
            sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_SWITCH_TO_KIOSK").apply {
                    setPackage(packageName)
                }
            )
        }
    }

    /**
     * Subscribe-from-ha_only restore path. When a user who previously
     * opted into HA-only via the trial-expired modal subscribes, the
     * Stripe webhook flips subscription_status to active server-side;
     * the next subscription-sync writes that into local
     * SubscriptionPreferences. Detect the (kioskFromHaOnly=true AND
     * status now grants Dashie access) combination and reverse the
     * lockdown: clear forceKioskMode, layoutMode = 'widgets', clear the
     * kioskFromHaOnly marker, then reload into the full Dashie
     * dashboard. Gated on kioskFromHaOnly so a user who manually
     * toggled kiosk via Settings keeps their choice.
     */
    private fun maybeRestoreFromHaOnly() {
        val prefs = halitePrefs ?: return
        if (!prefs.account.kioskFromHaOnly) return
        if (!prefs.account.forceKioskMode) {
            // Stale flag — clear so we don't keep re-evaluating.
            prefs.account.kioskFromHaOnly = false
            return
        }

        // Same set as the inline ACTIVE|TRIALING|COMPLIMENTARY test this replaces —
        // SubscriptionPreferences.hasActiveAccess is defined as exactly those three, verified
        // before substituting, so this is behaviour-identical on Dashie.
        if (!com.dashieapp.Dashie.edition.EditionSeams.subscription(this).hasActiveAccess) return

        Log.i(TAG, "🎉 Subscribe-from-ha_only detected — restoring full Dashie layout")

        prefs.account.forceKioskMode = false
        prefs.account.kioskFromHaOnly = false
        prefs.display.layoutMode = "widgets"

        // ACTION_SWITCH_TO_FULL is the existing reverse of the
        // ACTION_SWITCH_TO_KIOSK broadcast the ha_only opt-in path
        // uses — MainBroadcastManager loads the Dashie URL and resets
        // the viewport to Dashie chrome. Restore Toast so the user
        // sees feedback that we're switching them back to full Dashie.
        Toast.makeText(
            this,
            "Welcome to ${brandName()}! Loading your dashboard…",
            Toast.LENGTH_SHORT
        ).show()
        sendBroadcast(
            android.content.Intent("com.dashieapp.Dashie.ACTION_SWITCH_TO_FULL").apply {
                setPackage(packageName)
            }
        )
    }

    private fun showTrialConfirmationDialog(days: Int) {
        // 🟡 BRAND-TRIAGED 2026-08-05 (Chickadee residue sweep). Says "Dashie" and
        // stays that way ON PURPOSE: the sentence is ACCOUNT-semantic, and Chickadee has no
        // accounts and no trial, so substituting the brand would produce a true-looking FALSE statement
        // ("welcome to Chickadee" for a trial that does not exist) instead of removing a leak. Unreachable in
        // that edition — the trigger is a subscriptionDelegate callback fired by the Dashie
        // webapp, and the Chickadee shell ships with no account/session bridge at all.
        // The durable fix is to MOVE this to src/dashie/, which is the Phase-2/3 source
        // question live with John (decision #24) — deliberately not started here.
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle).text = "Welcome to Dashie!"
        dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage).text =
            "Your $days-day free trial is now active.\n\n" +
            // Beta-scope copy. Restore the line below once AI + Locations
            // ship publicly so the welcome message reflects full feature set.
            // "You have full access to Calendar, Chores & Rewards, AI, Locations, and all Dashie features. " +
            // D.74 — Chores & Rewards moved to alpha rollout; drop from
            // the beta-scope message. Restore once chores+rewards flip
            // back to rollout_status='beta' in feature_access.
            "Dashie is in beta release and you have full access to Calendar, Photos, Weather, and more! " +
            "No credit card required."

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            // Dim the dashboard behind so the welcome modal reads as a
            // distinct overlay, not a translucent panel sitting on top.
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.6f)
        }

        // Trial is already active by the time this dialog shows — purely an
        // acknowledgment, so hide Cancel and present a single OK button.
        dialogView.findViewById<android.widget.Button>(R.id.buttonNegative).visibility = android.view.View.GONE
        dialogView.findViewById<android.widget.Button>(R.id.buttonPositive).apply {
            text = "OK"
            setOnClickListener { dialog.dismiss() }
        }

        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.setDefaultFocusOnConfirm(dialogView)
    }

    /**
     * Amazon Fire TV exit-confirmation dialog. Fired by the back-press
     * callback ONLY on the amazon build flavor and ONLY when the JS
     * side reports the dashboard is idle on the home screen (no modal,
     * sidebar, settings overlay, screensaver, voice overlay, etc.).
     *
     * Required by Amazon's Fire TV controller-behavior guideline — the
     * previous behavior of opening a menu on back-press from the main
     * screen was flagged as an "infinite loop of closing and opening
     * the menu." Showing this confirmation dialog instead is the
     * pattern Amazon expects. "Exit" calls finishAndRemoveTask so the
     * app is truly gone from recents (clean exit, not a backgrounded
     * task that re-launches when the user comes back).
     *
     * Idempotent: a second back-press while the dialog is already
     * showing is a no-op, so we don't stack dialogs if the d-pad
     * double-taps.
     */
    private var exitConfirmDialog: android.app.AlertDialog? = null

    private fun showExitConfirmDialog() {
        if (exitConfirmDialog?.isShowing == true) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)
        val brand = getString(R.string.brand_name)
        dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle).text =
            getString(R.string.exit_confirm_title, brand)
        dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage).text =
            getString(R.string.exit_confirm_body, brand)

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.6f)
        }

        dialogView.findViewById<android.widget.Button>(R.id.buttonNegative).apply {
            text = "Cancel"
            setOnClickListener { dialog.dismiss() }
        }
        dialogView.findViewById<android.widget.Button>(R.id.buttonPositive).apply {
            text = "Exit"
            setOnClickListener {
                dialog.dismiss()
                Log.i(TAG, "🚪 Exit Dashie confirmed — finishAndRemoveTask")
                finishAndRemoveTask()
            }
        }

        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.setDefaultFocusOnConfirm(dialogView)
        exitConfirmDialog = dialog
    }

    /**
     * Native dialog shown when the user clicks Sign Up but the email
     * already had a Dashie account. Mirrors showTrialConfirmationDialog
     * (same chrome) so the two confirmations feel consistent. Native
     * AlertDialog because the JS-side toast was getting hidden by the
     * sidebar / control center overlay on Samsung.
     */
    private fun showAccountAlreadyExistsDialog() {
        // 🟡 BRAND-TRIAGED 2026-08-05 (Chickadee residue sweep). Says "Dashie" and
        // stays that way ON PURPOSE: the sentence is ACCOUNT-semantic, and Chickadee has no
        // accounts, so substituting the brand would produce a true-looking FALSE statement
        // ("you already have a Chickadee account") instead of removing a leak. Unreachable in
        // that edition — the trigger is a subscriptionDelegate callback fired by the Dashie
        // webapp, and the Chickadee shell ships with no account/session bridge at all.
        // The durable fix is to MOVE this to src/dashie/, which is the Phase-2/3 source
        // question live with John (decision #24) — deliberately not started here.
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle).text = "Welcome back!"
        dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage).text =
            "You already have a Dashie account.\n\n" +
            "Signing you in to your existing account."

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.6f)
        }

        dialogView.findViewById<android.widget.Button>(R.id.buttonNegative).visibility = android.view.View.GONE
        dialogView.findViewById<android.widget.Button>(R.id.buttonPositive).apply {
            text = "OK"
            setOnClickListener { dialog.dismiss() }
        }

        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.setDefaultFocusOnConfirm(dialogView)
    }

    private fun createJsBridgeCallbacks(): DashieJSBridge.Callbacks {
        return object : DashieJSBridge.Callbacks,
            DashieJSBridge.CoreCallbacks by com.dashieapp.Dashie.webview.callbacks.JsBridgeCoreCallbacks(
                context = this,
                kioskControllerProvider = { kioskController },
                lifecycleHandlerProvider = { lifecycleHandler },
                voiceSetupProvider = { voiceSetup },
                darkModeHandlerProvider = { darkModeHandler },
                sidebarProvider = { nativeSidebarController },
                bottomBarProvider = { bottomBarController },
                registryProvider = { haliteRegistry },
                halitePrefsProvider = { halitePrefs },
                webViewProvider = { webView },
                runOnUiThread = ::runOnUiThread
            ),
            DashieJSBridge.DialogCallbacks by com.dashieapp.Dashie.webview.callbacks.JsBridgeDialogCallbacks(
                dialogHostProvider = { dialogHost },
                halitePrefsProvider = { halitePrefs },
                screenControllerProvider = { haliteScreenController },
                dismissSystemOverlaysProvider = {
                    controlCenterOverlay?.hide()
                    nativeSidebarController?.hide()
                },
                runOnUiThread = ::runOnUiThread,
                onCloseSignInProvider = {
                    // Item 23: "Close Sign-in" navigates the WebView back to
                    // the HA dashboard. Pass preferKiosk=true so a device with
                    // a linked Dashie account whose web session has expired is
                    // routed to the HA shell — NOT back to dashieapp.com, which
                    // would redirect to /login/ again (inescapable loop).
                    //
                    // PERSIST the choice (2026-07-14 resilience audit). The loop was only
                    // escaped for THIS navigation: on the next boot determineInitialUrl()
                    // reads forceKioskMode and the device landed right back on the dead
                    // login screen. The only persistent escape a user could find was
                    // "Sign out" — which used to destroy their paid voice license
                    // (see JsBridgeSystemDelegate.clearMainPrefsKeepingHa).
                    //
                    // ⚠️ Set layoutMode, NOT just forceKioskMode: onCreate re-derives
                    // `forceKioskMode = (layoutMode == "kiosk")` on every launch, so the
                    // flag alone would be silently wiped on reboot. layoutMode is the
                    // durable source of truth. Reversible from Settings.
                    //
                    // Only when HA is actually configured — forcing the kiosk shell on a
                    // device with no HA would strand it worse than the login screen does.
                    halitePrefs?.let { p ->
                        if (p.connection.haUrl.isNotBlank()) {
                            p.display.layoutMode = "kiosk"
                            p.account.forceKioskMode = true
                            Log.i(TAG, "Close Sign-in — persisting kiosk mode so the next boot doesn't return to the sign-in loop")
                        }
                    }
                    val target = urlHandler.determineInitialUrl(preferKiosk = true)
                    setHaliteViewport()
                    urlHandler.loadUrlWithWsProxyIfNeeded(target)
                }
            ),
            DashieJSBridge.SleepWakeCallbacks by com.dashieapp.Dashie.webview.callbacks.JsBridgeSleepWakeCallbacks(
                screenControllerProvider = { haliteScreenController }
            ),
            // FullscreenModeManager itself implements FullscreenCallbacks —
            // the manager owns the refcount + overlay-restore logic so no
            // separate delegate class is needed.
            DashieJSBridge.FullscreenCallbacks by fullscreenModeManager,
            DashieJSBridge.VoiceCallbacks by com.dashieapp.Dashie.webview.callbacks.JsBridgeVoiceCallbacks(
                voiceControllerProvider = { haliteVoiceController }
            ),
            DashieJSBridge.TimerCallbacks by com.dashieapp.Dashie.webview.callbacks.JsBridgeTimerCallbacks(
                timerManagerProvider = { haliteRegistry?.timerOverlayManager },
                runOnUiThread = ::runOnUiThread
            ),
            DashieJSBridge.MusicCallbacks by com.dashieapp.Dashie.webview.callbacks.JsBridgeMusicCallbacks(
                registryProvider = { haliteRegistry },
                runOnUiThread = ::runOnUiThread
            ),
            DashieJSBridge.VideoFeedCallbacks by com.dashieapp.Dashie.webview.callbacks.JsBridgeVideoFeedCallbacks(
                videoFeedManagerProvider = { haliteRegistry?.videoFeedManager },
                videoFeedPrefsProvider = { haliteRegistry?.prefs?.videoFeed },
                runOnUiThread = ::runOnUiThread,
                voiceControllerProvider = { haliteRegistry?.voiceController }
            ),
            DashieJSBridge.SidebarSyncCallbacks by com.dashieapp.Dashie.webview.callbacks.JsBridgeSidebarSyncCallbacks(
                sidebarProvider = { nativeSidebarController },
                runOnUiThread = ::runOnUiThread
            ),
            DashieJSBridge.NavigationCallbacks by com.dashieapp.Dashie.webview.callbacks.JsBridgeNavigationCallbacks(
                // Default routing: Fire tablet → HaLoginActivity (custom UI
                // built around their broken keyboard handling on HA's OAuth
                // page); other devices → inline-auth in main WebView (HA's
                // native OAuth page works fine there). Forcing the activity
                // path universally causes keyboard regressions on
                // non-Fire devices, so leave it alone.
                haLoginLauncher = { haUrl -> haLoginHandler.launchHaLogin(haUrl) },
                controlCenterShow = { controlCenterOverlay?.show() },
                webViewProvider = { webView },
                serviceManagerProvider = { serviceManager },
                dashieServiceManagerProvider = { dashieServiceManager },
                permissionDelegateProvider = { permissionDelegate },
                urlHandlerProvider = { urlHandler },
                nativeSettingsPageLauncher = { pageId ->
                    val intent = android.content.Intent(
                        this,
                        com.dashieapp.Dashie.halite.settings.SettingsActivity::class.java
                    ).putExtra("navigate_to", pageId)
                    settingsActivityLauncher.launch(intent)
                    overridePendingTransition(0, 0)
                },
                webViewClientProvider = { dashieWebViewClient },
                runOnUiThread = ::runOnUiThread,
                activityProvider = { this },
                lockDialogsProvider = { dialogHost?.lockDialogs }
            ),
            DashieJSBridge.OnboardingCallbacks by com.dashieapp.Dashie.webview.callbacks.JsBridgeOnboardingCallbacks(
                haliteAuthManagerProvider = { haliteAuthManager },
                halitePrefsProvider = { halitePrefs },
                webViewProvider = { webView },
                dashieWebViewClientProvider = { dashieWebViewClient },
                urlHandlerProvider = { urlHandler },
                permissionDelegateProvider = { permissionDelegate },
                updateControllerProvider = { updateController },
                assertKioskUiMode = {
                    if (halitePrefs?.connection?.isSetupComplete == true) {
                        haliteRegistry?.nativeWidgetVisibilityGate?.setUiMode(
                            com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.UiMode.KIOSK
                        )
                    }
                },
                runOnUiThread = ::runOnUiThread
            )
        {
            // Return-to-home live-apply. This used to be "wired" via a bridge-level
            // var that nothing read (the settings delegate routes to THIS interface
            // method, whose default is an empty no-op) — so setReturnHomeTimeout
            // from JS never reached HaliteScreenController. Symptom: disabling
            // return-to-home left the already-scheduled runnable queued, yanking
            // the user home one more time despite the setting being off.
            override fun onReturnHomeTimeoutChanged(seconds: Int) {
                runOnUiThread { haliteScreenController?.updateReturnHomeTimeout(seconds) }
            }
        }
    }

    /**
     * Public method to trigger WebView refresh via API.
     * Delegates to MainMemoryManager.
     *
     * @param callback Called with true if refresh was triggered, false if throttled
     */
    fun triggerApiWebViewRefresh(callback: (Boolean) -> Unit) {
        memoryManager?.triggerApiWebViewRefresh(callback) ?: callback(false)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        val incomingNight = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        android.util.Log.w("DarkModeTrace", ">>> onConfigurationChanged INCOMING uiMode night=$incomingNight, resources uiMode night=${resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK}", Throwable("config change source"))

        // Override the night mode bits in newConfig BEFORE super processes it.
        val storedDark = com.dashieapp.Dashie.devicecontrols.DarkModeManager.getStoredPreference(this)
        if (storedDark != null) {
            newConfig.uiMode = (newConfig.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (storedDark) android.content.res.Configuration.UI_MODE_NIGHT_YES
                 else android.content.res.Configuration.UI_MODE_NIGHT_NO)
            android.util.Log.w("DarkModeTrace", ">>> onConfigChanged: overrode newConfig to storedDark=$storedDark")
        }

        super.onConfigurationChanged(newConfig)
        android.util.Log.w("DarkModeTrace", ">>> onConfigChanged AFTER super, uiMode=${resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK}")

        // Also force resources.updateConfiguration in case super didn't propagate fully
        if (storedDark != null) {
            com.dashieapp.Dashie.devicecontrols.DarkModeManager.forceResourcesNightMode(this, storedDark)
            android.util.Log.w("DarkModeTrace", ">>> onConfigChanged AFTER force, uiMode=${resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK}")
        }

        lifecycleHandler?.onConfigurationChanged(newConfig)
        // Forward to native overlay cards so they re-render with updated theme
        haliteRegistry?.timerOverlayManager?.onConfigurationChanged(newConfig)
        haliteRegistry?.musicPlayerManager?.onConfigurationChanged(newConfig)
        // Notify orientation controller so it can fire listeners that
        // swap sidebar ↔ bottom bar in widgets mode.
        orientationController?.handleConfigurationChanged(newConfig)
    }
}
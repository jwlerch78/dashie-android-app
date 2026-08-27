package com.dashieapp.Dashie

import android.content.Intent
import android.util.Log
import android.view.View
import android.view.Window
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher

// Halite imports
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.HaliteAuthManager
import com.dashieapp.Dashie.halite.DashboardTelemetryBridge
import com.dashieapp.Dashie.halite.HaConnectionMonitor
import com.dashieapp.Dashie.halite.DashieWebViewClient
import com.dashieapp.Dashie.halite.rtsp.RtspPlayerManager

// Other imports
import com.dashieapp.Dashie.devicecontrols.DeviceControlsCoordinator
import com.dashieapp.Dashie.ui.ImmersiveModeController
import com.dashieapp.Dashie.voice.VoiceAssistantCoordinator

/**
 * Central coordinator for MainActivity that holds component references
 * and manages Halite component registry creation.
 *
 * Architecture:
 * - MainActivity: Android entry point, lifecycle callbacks, activity result registration
 * - MainActivityCoordinator: Component ownership, registry creation, reference management
 *
 * Note: Lifecycle handling and callback wiring remain in MainActivity and its delegates.
 * The coordinator serves as a centralized component registry, not an orchestrator.
 */
class MainActivityCoordinator(
    private val activity: ComponentActivity,
    private val window: Window
) {
    companion object {
        private const val TAG = "MainCoordinator"
    }

    // ============================================================
    // Core Components (owned by coordinator)
    // ============================================================

    /** WebView for dashboard display (updated on WebView recreation) */
    lateinit var webView: WebView

    /** Update the WebView reference after recreation due to memory pressure */
    fun updateWebView(newWebView: WebView) {
        webView = newWebView
    }

    /** Device controls (volume, brightness, settings) - set by MainActivity */
    lateinit var deviceControls: DeviceControlsCoordinator

    /** Immersive mode controller for kiosk display - set by MainActivity */
    lateinit var immersiveMode: ImmersiveModeController

    /** Permission delegate for all permission handling - set by MainActivity */
    lateinit var permissionDelegate: MainPermissionDelegate

    /** Input handler for key and touch events - set by MainActivity */
    lateinit var inputHandler: MainInputHandler

    /** WebView bridge for JavaScript communication - set by MainActivity */
    lateinit var webViewBridge: MainWebViewBridge

    /** HA Kiosk CSS injector - set by MainActivity */
    lateinit var haKioskCssInjector: MainHaKioskCssInjector

    // ============================================================
    // Halite Components (only for ALLOW_URL_CONFIG builds)
    // ============================================================

    /** Halite preferences */
    var halitePrefs: HalitePreferences? = null
        private set

    /** Halite component registry - central owner of all Halite components */
    var haliteRegistry: HaliteComponentRegistry? = null
        private set

    /** Halite auth manager */
    var haliteAuthManager: HaliteAuthManager? = null
        private set

    /** Dashboard telemetry bridge */
    var dashboardTelemetryBridge: DashboardTelemetryBridge? = null
        private set

    /** HA connection monitor */
    var haConnectionMonitor: HaConnectionMonitor? = null
        private set

    /** RTSP player manager */
    var rtspPlayerManager: RtspPlayerManager? = null
        private set

    /** Crash report handler */
    var crashReportHandler: MainCrashReportHandler? = null
        private set

    /** WebView client */
    var dashieWebViewClient: DashieWebViewClient? = null
        private set

    // ============================================================
    // Standard Dashie Components (non-Halite)
    // ============================================================

    /** Voice assistant coordinator (standard Dashie) */
    var voiceAssistant: VoiceAssistantCoordinator? = null
        private set

    /** Standard Dashie API preferences */
    var dashieApiPrefs: com.dashieapp.Dashie.api.DashieApiPreferences? = null
        private set

    /** Standard Dashie API manager */
    var dashieApiManager: com.dashieapp.Dashie.api.DashieApiServiceManager? = null
        private set

    /** Standard Dashie SSDP service */
    var dashieSsdpService: com.dashieapp.Dashie.api.DashieSsdpService? = null
        private set

    // ============================================================
    // Activity Result Launchers (must be set by MainActivity)
    // ============================================================

    /** HA login launcher for Halite */
    var haLoginLauncher: ActivityResultLauncher<Intent>? = null

    /** Standard HA login launcher */
    var standardHaLoginLauncher: ActivityResultLauncher<Intent>? = null

    // ============================================================
    // State
    // ============================================================

    private var isInitialized = false

    // ============================================================
    // Delegate Accessors (for components that need them)
    // ============================================================

    /** Get dialog host from registry */
    val dialogHost get() = haliteRegistry?.dialogHost

    /** Get screen controller from registry */
    val haliteScreenController get() = haliteRegistry?.screenController

    /** Get voice controller from registry */
    val haliteVoiceController get() = haliteRegistry?.voiceController

    /** Get fully kiosk manager from registry */
    val dashieServiceManager get() = haliteRegistry?.dashieServiceManager

    /** Get light sensor controller from registry */
    val lightSensorController get() = haliteRegistry?.lightSensorController

    // ============================================================
    // Initialization
    // ============================================================

    /**
     * Initialize coordinator with components created by MainActivity.
     * Creates the HaliteComponentRegistry and sets up permission launchers.
     * Callback wiring is done separately by MainActivity.wireHaliteRegistryCallbacks().
     *
     * @param webView The WebView from the layout
     * @param rootLayout The root FrameLayout for overlays
     * @param splashOverlay The splash overlay view
     * @param deviceControls Device controls coordinator
     * @param immersiveMode Immersive mode controller
     * @param permissionDelegate Permission delegate
     * @param inputHandler Input handler
     * @param webViewBridge WebView bridge
     * @param haKioskCssInjector HA Kiosk CSS injector
     * @param halitePrefs Halite preferences (null for non-Halite builds)
     */
    fun initialize(
        webView: WebView,
        rootLayout: FrameLayout,
        splashOverlay: View,
        deviceControls: DeviceControlsCoordinator,
        immersiveMode: ImmersiveModeController,
        permissionDelegate: MainPermissionDelegate,
        inputHandler: MainInputHandler,
        webViewBridge: MainWebViewBridge,
        haKioskCssInjector: MainHaKioskCssInjector,
        halitePrefs: HalitePreferences?
    ) {
        if (isInitialized) {
            Log.w(TAG, "Already initialized, skipping")
            return
        }

        Log.i(TAG, "🚀 Starting coordinator initialization")
        val startTime = System.currentTimeMillis()

        // Store references to MainActivity-created components
        this.webView = webView
        this.deviceControls = deviceControls
        this.immersiveMode = immersiveMode
        this.permissionDelegate = permissionDelegate
        this.inputHandler = inputHandler
        this.webViewBridge = webViewBridge
        this.haKioskCssInjector = haKioskCssInjector
        this.halitePrefs = halitePrefs

        // Initialize Halite or Standard Dashie components
        if (BuildConfig.ALLOW_URL_CONFIG) {
            initializeHaliteComponents(rootLayout)
        } else {
            initializeStandardDashieComponents()
        }

        isInitialized = true
        Log.i(TAG, "🚀 Coordinator initialization complete in ${System.currentTimeMillis() - startTime}ms")
    }

    /**
     * Initialize Halite-specific components.
     * Creates the registry and sets up permission launchers.
     * Note: Registry callback wiring is done by MainActivity.wireHaliteRegistryCallbacks()
     * after deferred components are initialized.
     */
    private fun initializeHaliteComponents(rootLayout: FrameLayout) {
        val prefs = halitePrefs ?: run {
            Log.e(TAG, "❌ halitePrefs is null in Halite build!")
            return
        }

        // Initialize RTSP player manager
        rtspPlayerManager = RtspPlayerManager(activity, rootLayout)

        // Create registry
        haliteRegistry = HaliteComponentRegistry(
            activity = activity,
            prefs = prefs,
            webViewProvider = { webView },
            deviceControls = deviceControls,
            immersiveMode = immersiveMode,
            window = window
        )

        // Set permission launchers from the delegate
        haliteRegistry?.cameraPermissionLauncher = permissionDelegate.cameraPermissionLauncher
        haliteRegistry?.storagePermissionLauncher = permissionDelegate.storagePermissionLauncher
        haliteRegistry?.folderPickerLauncher = permissionDelegate.folderPickerLauncher
        haliteRegistry?.haliteVoicePermissionLauncher = permissionDelegate.haliteVoicePermissionLauncher

        Log.i(TAG, "✓ Halite components initialized")
    }

    /**
     * Initialize Standard Dashie components (non-Halite).
     */
    private fun initializeStandardDashieComponents() {
        Log.i(TAG, "✓ Standard Dashie components initialized")
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    /**
     * Called from MainActivity.onDestroy()
     */
    fun onDestroy() {
        Log.i(TAG, "🧹 Destroying coordinator")
        haliteRegistry?.onDestroy()
        voiceAssistant?.shutdown()
    }

    // ============================================================
    // Component Access
    // ============================================================

    /**
     * Get the screen controller (for photo cache management).
     */
    fun getScreenController() = haliteRegistry?.screenController
}

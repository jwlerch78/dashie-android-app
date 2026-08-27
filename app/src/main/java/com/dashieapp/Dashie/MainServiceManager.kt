package com.dashieapp.Dashie

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaliteScreenController
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.registry.*
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.api.DashieServiceManager
import com.dashieapp.Dashie.devicecontrols.DeviceControlsCoordinator

/**
 * Manages external services: SSDP discovery, Dashie API, RTSP streaming,
 * Device Admin, and music player state.
 *
 * Extracted from MainActivity to consolidate service management in one place.
 *
 * @param activity The activity context
 * @param halitePrefsProvider Lazy provider for HalitePreferences
 * @param callbacks Callbacks for accessing components owned by MainActivity
 */
class MainServiceManager(
    private val activity: ComponentActivity,
    private val halitePrefsProvider: () -> HalitePreferences?,
    private val callbacks: Callbacks
) {
    companion object {
        private const val TAG = "DashieAuth"
    }

    private val halitePrefs get() = halitePrefsProvider()

    interface Callbacks {
        fun getDashieServiceManager(): DashieServiceManager?
        fun getHaliteScreenController(): HaliteScreenController?
        fun getHaliteRegistry(): HaliteComponentRegistry?
        fun getPermissionDelegate(): MainPermissionDelegate
        fun getWebView(): WebView
        fun getDeviceControlsCoordinator(): DeviceControlsCoordinator

        // Standard Dashie state (mutable)
        fun getDashieApiPrefs(): com.dashieapp.Dashie.api.DashieApiPreferences?
        fun setDashieApiPrefs(prefs: com.dashieapp.Dashie.api.DashieApiPreferences)
        fun getDashieApiManager(): com.dashieapp.Dashie.api.DashieApiServiceManager?
        fun setDashieApiManager(manager: com.dashieapp.Dashie.api.DashieApiServiceManager)
        fun getDashieSsdpService(): com.dashieapp.Dashie.api.DashieSsdpService?
        fun setDashieSsdpService(service: com.dashieapp.Dashie.api.DashieSsdpService)
        fun getUrlHandler(): MainUrlHandler?
    }

    // ============================================================
    // SSDP / API Service Management
    // ============================================================

    /**
     * Setup SSDP discovery service for Home Assistant auto-discovery.
     * For Halite: Only starts if API is enabled and SSDP is enabled in preferences.
     * For standard Dashie: Always starts if API is enabled.
     */
    fun setupSsdpService() {
        if (BuildConfig.ALLOW_URL_CONFIG) {
            // Halite mode - SSDP is now handled by HaliteComponentRegistry
            Log.i(TAG, "setupSsdpService: Halite mode - handled by registry")
            return
        } else {
            // Standard Dashie mode - use DashieApiPreferences
            Log.i(TAG, "setupSsdpService: Standard Dashie mode")

            // Guard against multiple initializations (onPageFinished can be called multiple times)
            if (callbacks.getDashieSsdpService() != null) {
                Log.i(TAG, "setupSsdpService: Already initialized, skipping")
                return
            }

            var prefs = callbacks.getDashieApiPrefs()
            if (prefs == null) {
                prefs = com.dashieapp.Dashie.api.DashieApiPreferences(activity)
                callbacks.setDashieApiPrefs(prefs)
            }

            Log.i(TAG, "setupSsdpService: apiEnabled=${prefs.apiEnabled}")

            val ssdpService = com.dashieapp.Dashie.api.DashieSsdpService(activity, prefs)
            callbacks.setDashieSsdpService(ssdpService)

            // Start SSDP if API is enabled (always enabled for HA integration)
            if (prefs.apiEnabled) {
                ssdpService.start()
                Log.i(TAG, "SSDP discovery service started (Standard Dashie)")

                // Also start the API server for HA polling (deviceInfo, etc.)
                if (callbacks.getDashieApiManager() == null) {
                    val apiManager = com.dashieapp.Dashie.api.DashieApiServiceManager(
                        context = activity,
                        webView = callbacks.getWebView(),
                        deviceControls = callbacks.getDeviceControlsCoordinator(),
                        prefs = prefs,
                        lifecycleOwner = activity
                    )
                    // Wire loadDashboardUrl to navigate home instead of reload
                    val urlHandler = callbacks.getUrlHandler()
                    if (urlHandler != null) {
                        apiManager.loadDashboardUrl = {
                            val dashboardUrl = urlHandler.determineInitialUrl()
                            urlHandler.loadUrlWithWsProxyIfNeeded(dashboardUrl)
                        }
                    }
                    callbacks.setDashieApiManager(apiManager)
                    apiManager.startServiceIfEnabled()
                    Log.i(TAG, "API server started (Standard Dashie)")
                }
            } else {
                Log.i(TAG, "setupSsdpService: API not enabled, skipping SSDP")
            }
        }
    }

    /**
     * Handle API enabled toggle change from sidebar.
     */
    fun handleApiEnabledChange(enabled: Boolean) {
        Log.i(TAG, "🔧 handleApiEnabledChange called with enabled=$enabled")
        if (enabled) {
            // Stop first to ensure service restarts with new port/settings
            Log.i(TAG, "🔧 Stopping API service before restart")
            callbacks.getDashieServiceManager()?.stopService()
            // Brief delay to allow service to fully stop before restarting
            Handler(Looper.getMainLooper()).postDelayed({
                Log.i(TAG, "🔧 Restarting API service after 300ms delay")
                callbacks.getDashieServiceManager()?.startServiceIfNeeded()
            }, 300)
        } else {
            Log.i(TAG, "🔧 Stopping API service (disabled)")
            callbacks.getDashieServiceManager()?.stopService()
            // The service may still be needed by other consumers (RTSP camera,
            // motion-wake camera) even after the user disables the REST API.
            // Re-start it after the stop completes — startServiceIfNeeded
            // no-ops if no consumer remains.
            Handler(Looper.getMainLooper()).postDelayed({
                callbacks.getDashieServiceManager()?.startServiceIfNeeded()
            }, 300)
        }
    }

    // ============================================================
    // Device Admin
    // ============================================================

    /**
     * Request Device Admin permission for hardware screen off.
     * Shows an explanation dialog first, then opens system Device Admin enrollment.
     */
    fun handleRequestDeviceAdmin() {
        Log.i(TAG, "🔧 handleRequestDeviceAdmin called")

        com.dashieapp.Dashie.halite.sidebar.dialogs.StyledConfirmDialog.show(
            activity,
            title = "Enable Screen Off",
            message = "Screen Off mode requires Device Admin permission — without it, the screen shows a black overlay instead of actually turning off.\n\n" +
                "Enable Device Admin now?",
            positiveText = "Enable",
            negativeText = "Later",
            linkText = "Don't ask again",
            onLink = {
                halitePrefs?.display?.permissionPromptDeclined = true
                Log.i(TAG, "🔧 Device Admin prompt permanently declined (Don't ask again)")
            },
            onPositive = {
                activity.startActivity(
                    com.dashieapp.Dashie.halite.DeviceAdminHelper.buildActivationIntent(
                        activity,
                        com.dashieapp.Dashie.halite.DeviceAdminHelper.screenOffExplanation(activity)
                    )
                )
            }
        )
    }

    // ============================================================
    // RTSP Server Management
    // ============================================================

    /**
     * Start RTSP server after releasing camera from motion detection.
     */
    fun startRtspServerWithCameraRelease() {
        // First, stop the camera motion detector to release the camera
        callbacks.getHaliteScreenController()?.enableRtspMotionMode()
        // Add delay to allow camera to fully release before RTSP acquires it
        // Increased to 1500ms for devices with slow camera HAL (Samsung Tab A8, etc.)
        Handler(Looper.getMainLooper()).postDelayed({
            Log.i(TAG, "startRtspServerWithCameraRelease: Starting RTSP server after delay")
            callbacks.getDashieServiceManager()?.startRtspServer()
            // Wire face frame callback so RTSP frames reach the face detector
            callbacks.getHaliteRegistry()?.wireRtspFaceFrameCallback()
            // Re-apply HA sensor config now that RTSP is running so motion callbacks
            // are wired to the RTSP path (not the standalone camera path)
            callbacks.getHaliteRegistry()?.let {
                com.dashieapp.Dashie.halite.HaliteComponentWiring.applyHaSensorConfig(it)
            }
        }, 1500)
    }

    /**
     * Handle RTSP motion detection toggle change from sidebar.
     */
    fun handleRtspMotionDetectionChange(enabled: Boolean) {
        callbacks.getDashieServiceManager()?.setRtspMotionDetection(enabled)
    }

    /**
     * Restart RTSP server if it should be running but was stopped by camera conflict.
     * Called when closing dialogs that may have used the camera (e.g., motion wake preview).
     */
    fun handleRestartRtspServerIfNeeded() {
        val rtspEnabled = halitePrefs?.camera?.rtspEnabled ?: false

        Log.i(TAG, "handleRestartRtspServerIfNeeded: rtspEnabled=$rtspEnabled")

        if (rtspEnabled) {
            // Always stop first to ensure socket is released
            Log.i(TAG, "📹 Stopping RTSP server to release resources...")
            callbacks.getDashieServiceManager()?.stopRtspServer()

            // Delay restart to allow camera and socket to fully release
            Handler(Looper.getMainLooper()).postDelayed({
                val success = callbacks.getDashieServiceManager()?.startRtspServer()
                Log.i(TAG, "📹 RTSP camera server restart result: $success")
                // Wire face frame callback so RTSP frames reach the face detector
                callbacks.getHaliteRegistry()?.wireRtspFaceFrameCallback()
                // Re-apply HA sensor config now that RTSP is running
                callbacks.getHaliteRegistry()?.let {
                    com.dashieapp.Dashie.halite.HaliteComponentWiring.applyHaSensorConfig(it)
                }
            }, 1000)
        } else {
            Log.i(TAG, "RTSP restart not needed (disabled)")
        }
    }

    /**
     * Restart RTSP server for memory recovery.
     * Stops the RTSP server to release OpenGL textures and MediaCodec resources,
     * waits for cleanup, then restarts.
     *
     * @param onComplete Called when restart is complete (or if RTSP was not running)
     */
    fun restartRtspServerForMemoryRecovery(onComplete: () -> Unit) {
        val rtspEnabled = halitePrefs?.camera?.rtspEnabled ?: false

        if (!rtspEnabled) {
            Log.i(TAG, "📹 RTSP memory restart: server not enabled, skipping")
            onComplete()
            return
        }

        val isRunning = callbacks.getDashieServiceManager()?.isRtspServerRunning() ?: false
        if (!isRunning) {
            Log.i(TAG, "📹 RTSP memory restart: server not running, skipping")
            onComplete()
            return
        }

        // Don't restart if the camera is already in a failure/recovery state.
        // Stopping and restarting during recovery triggers more camera open/close cycles,
        // which makes Samsung "Camera disabled by policy" worse and creates a death spiral.
        val inRecovery = callbacks.getDashieServiceManager()?.isRtspInCameraRecovery() ?: false
        if (inRecovery) {
            Log.i(TAG, "📹 RTSP memory restart: camera in recovery mode, skipping to avoid restart storm")
            PersistentLog.info("RTSP_RESTART", "Skipped - camera in recovery mode")
            onComplete()
            return
        }

        Log.i(TAG, "📹 RTSP memory restart: stopping server to release GL textures and encoder...")
        PersistentLog.info("RTSP_RESTART", "Stopping for memory recovery")

        callbacks.getDashieServiceManager()?.stopRtspServer()

        Handler(Looper.getMainLooper()).postDelayed({
            Log.i(TAG, "📹 RTSP memory restart: restarting server...")
            val success = callbacks.getDashieServiceManager()?.startRtspServer()
            Log.i(TAG, "📹 RTSP memory restart complete: success=$success")
            PersistentLog.info("RTSP_RESTART", "Restart complete: success=$success")
            // Wire face frame callback so RTSP frames reach the face detector
            callbacks.getHaliteRegistry()?.wireRtspFaceFrameCallback()
            onComplete()
        }, 1500)
    }

    // ============================================================
    // Music Player
    // ============================================================

    /**
     * Handle music player enabled state change from sidebar.
     */
    fun handleMusicPlayerEnabledChange(enabled: Boolean) {
        Log.i(TAG, "🎵 Music player enabled changed to: $enabled")
        if (enabled) {
            Log.i(TAG, "🎵 Music player enabled - will show when data arrives")
        } else {
            callbacks.getHaliteRegistry()?.musicPlayerManager?.hidePlayer()
            Log.i(TAG, "🎵 Music player disabled - hiding overlay")
        }
    }

    // ============================================================
    // Permission Validation
    // ============================================================

    /**
     * Validate that feature-related permissions match enabled settings.
     * Called on app resume to detect if Fire OS (or the user) revoked permissions.
     */
    fun validateApiPermissionsOnResume() {
        if (!BuildConfig.ALLOW_URL_CONFIG || halitePrefs == null) return
        // Skip during onboarding — permissions are handled by the onboarding flow
        if (halitePrefs?.connection?.isSetupComplete != true) return
        // D.46 — TV devices have no camera, the mic is remote-only, and the
        // device-admin / overlay / exact-alarm steps no-op on TV. Onboarding
        // already short-circuits the permission sweep on TV (cefd8f8f); the
        // resume-time check was still firing on app start / wake from sleep
        // and prompting the user for permissions they can't grant on a Fire
        // TV. Skip the whole resume validation on TV — voice still requests
        // mic on demand when the user actually enables voice.
        val isTv = com.dashieapp.Dashie.util.DeviceInfoHelper.isFireTV() ||
            com.dashieapp.Dashie.util.DeviceInfoHelper.isTVDevice(activity)
        if (isTv) {
            Log.d(TAG, "validateApiPermissionsOnResume: TV device — skipping resume permission sweep")
            return
        }
        // Skip the cycle immediately after sign-out — the user is in the
        // kiosk-shell transition, not actively using a feature. The
        // ACTION_DASHIE_SIGN_OUT broadcast handler sets this flag; we
        // consume it here so the next resume validates normally.
        val signOutPrefs = activity.getSharedPreferences("dashie_signout_state", Context.MODE_PRIVATE)
        if (signOutPrefs.getBoolean("skip_next_perm_validate", false)) {
            signOutPrefs.edit().remove("skip_next_perm_validate").apply()
            Log.i(TAG, "🔐 Skipping permission validate — post-signout transition")
            return
        }

        val apiEnabled = halitePrefs?.connection?.apiEnabled ?: false
        val missingPermissions = mutableListOf<String>()

        // Check camera permission (needed for RTSP or camera-based motion/face detection)
        val motionWakeMode = halitePrefs?.screensaver?.motionWakeMode
        val needsCamera = halitePrefs?.camera?.rtspEnabled == true ||
            motionWakeMode == "camera" || motionWakeMode == "face"
        if (needsCamera) {
            if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add("Camera")
            }
        }

        // Check microphone permission (needed for RTSP audio)
        if (halitePrefs?.camera?.rtspEnabled == true) {
            if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add("Microphone (RTSP)")
            }
        }

        // Check voice/wake word permission (if voice is enabled)
        if (halitePrefs?.voice?.voiceEnabled == true) {
            if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (!missingPermissions.any { it.contains("Microphone") }) {
                    missingPermissions.add("Microphone (Voice)")
                }
            }
        }

        // Check Device Admin for hardware screen off (if API + screen off enabled)
        if (apiEnabled) {
            if (halitePrefs?.sleep?.hardwareScreenOff == true &&
                !com.dashieapp.Dashie.halite.DeviceAdminHelper.isActive(activity)) {
                missingPermissions.add("Device Admin")
            }
        }

        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "⚠️ Missing permissions for enabled features: ${missingPermissions.joinToString()}")
            Handler(Looper.getMainLooper()).postDelayed({
                Log.i(TAG, "🔐 Requesting missing permissions after app resume")
                if (apiEnabled) {
                    callbacks.getPermissionDelegate().checkAndRequestApiPermissions()
                } else {
                    // API not enabled — use the RTSP permission flow directly
                    callbacks.getPermissionDelegate().handleRtspEnabledChange(true)
                }
            }, 300)
        } else {
            Log.d(TAG, "✅ All feature permissions are granted")
        }

        // HA-enabled tablets need the same kiosk permissions HA-first
        // onboarding requests (camera / mic / device admin / exact alarm /
        // overlay). The Settings "enable HA" toggle just flips the pref and
        // never requests them — and it runs in SettingsActivity, which can't
        // drive MainActivity's permission flow. This on-resume check is what
        // makes them get requested once the user returns to the dashboard,
        // regardless of how HA was enabled (native toggle, JS, calendar flow).
        // checkAndRequestApiPermissions runs its own comprehensive missing-
        // permission check, is throttled, and no-ops when all are granted.
        val haEnabled = halitePrefs?.connection?.haEnabled ?: false
        if (haEnabled) {
            Handler(Looper.getMainLooper()).postDelayed({
                Log.i(TAG, "🔐 HA enabled — verifying kiosk permissions on resume")
                callbacks.getPermissionDelegate().checkAndRequestApiPermissions()
            }, 350)
        }
    }
}

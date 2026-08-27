package com.dashieapp.Dashie.halite

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.preferences.SleepPreferences
import com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Controller for screen dimmer, motion wake detection, and kiosk mode settings.
 *
 * Responsibilities:
 * - Screen dimmer setup and management
 * - Motion wake detection (camera + sensors)
 * - Camera permission handling
 * - Kiosk mode settings (keep screen on, show when locked, etc.)
 *
 * Extracted from MainActivity to reduce its size and improve maintainability.
 */
class HaliteScreenController(
    private val activity: Activity,
    private val halitePrefs: HalitePreferences,
    private val lifecycleOwner: LifecycleOwner,
    private val cameraPermissionLauncher: ActivityResultLauncher<String>,
    private val storagePermissionLauncher: ActivityResultLauncher<String>,
    private val folderPickerLauncher: ActivityResultLauncher<android.net.Uri?>
) {
    companion object {
        private const val TAG = "HaliteScreenController"
    }

    // Screensaver preferences (for photo slideshow)
    private val screensaverPrefs = ScreensaverPreferences(activity)

    // Screen dimmer and motion wake state
    private var _screenDimmer: ScreenDimmer? = null
    val screenDimmer: ScreenDimmer?
        get() = _screenDimmer

    /** Set by HaliteComponentRegistry after light sensor is initialized. */
    var lightSensorController: LightSensorBrightnessController? = null

    /**
     * Optional secondary motion callback, invoked on every motion detection event
     * alongside the main screen-wake handler. Used by HA sensor publisher when RTSP
     * is not running (RTSP owns the camera when active and fires its own callback).
     */
    var onExtraMotionDetected: (() -> Unit)? = null

    private var motionWakeManager: MotionWakeManager? = null
    private var haLoginCompleted = false

    // Standalone face detector for RTSP+Face mode (ML Kit fallback)
    // When RTSP is active, MotionWakeManager can't use the camera, so face detection
    // is handled separately using frames from Camera2MotionVideoSource.
    // Not created when useHwFaceDetection is true (Camera2 ISP handles it at zero CPU cost).
    private var rtspFaceDetector: FaceWakeDetector? = null

    /** Set by HaliteComponentRegistry when Camera2 HW face detection is available */
    var useHwFaceDetection = false

    // Storage permission callback (set by DialogPickers when requesting permission)
    var onStoragePermissionGranted: (() -> Unit)? = null

    // Camera permission callback (set by DialogPickers when requesting permission)
    private var onCameraPermissionResult: ((Boolean) -> Unit)? = null

    // Folder picker callback (set by DialogPickers when launching folder picker)
    var onFolderPickerResult: ((android.net.Uri) -> Unit)? = null

    // Callback when screen dims (for RTSP motion detection rearm)
    var onScreenDimmedCallback: (() -> Unit)? = null

    // Callback when screensaver state changes (for WebView notification)
    // isDimmed: true when screensaver activates, false when it deactivates
    var onScreensaverStateChanged: ((isDimmed: Boolean) -> Unit)? = null

    // WebView reference for HA Media photo source (extracting auth tokens)
    private var webViewRef: android.webkit.WebView? = null

    // Blank-screen fix + detection: re-composites the dashboard WebView on
    // screensaver reveal (fixes GPU-surface-loss gray buffer) and logs
    // BLANK_DETECTED for field measurement. Reads webViewRef lazily so it
    // survives WebView recreation. See BlankScreenWatchdog.
    private val blankScreenWatchdog =
        com.dashieapp.Dashie.halite.diagnostics.BlankScreenWatchdog(
            activity, webViewProvider = { webViewRef })

    // Screensaver state tracking
    private var nativeScreensaverActive = false  // Tracks native ScreenDimmer screensaver state

    // Handler for timers (return-to-home, etc.)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // ==================== Return-to-Home Timer ====================
    // Navigates WebView back to the configured dashboard URL after inactivity
    private var returnHomeRunnable: Runnable? = null

    // Callback when return-to-home fires (navigates WebView)
    var onReturnHome: (() -> Unit)? = null

    // Callback for ha_page screensaver: navigates WebView to HA page path on activation,
    // and back to home dashboard on deactivation (pagePath=null)
    var onHaPageNavigate: ((pagePath: String?) -> Unit)? = null

    // Callback to dismiss overlays (control center, sidebar) before screen off
    var onDismissOverlays: (() -> Unit)? = null

    // Callback to restore overlays (re-apply sidebar visibility) after screen on.
    // Mirror of onDismissOverlays — fires on screenOn() so callers that hid native
    // UI on screenOff() can put it back without each callsite knowing the rules.
    var onRestoreOverlays: (() -> Unit)? = null

    // Callback to notify HaConnectionMonitor of screen sleep state changes
    // When screen is sleeping, HA disconnects are likely WiFi power management (not HA outage)
    var onScreenSleepChanged: ((isSleeping: Boolean) -> Unit)? = null

    // Callback fired when motion-wake's camera usage changes (camera/face mode
    // activated vs. disabled/sensor fallback). Wired in MainHaliteSetup.kt to
    // DashieServiceManager.setMotionWakeCameraActive(), which ORs this with
    // RTSP's state and promotes/demotes the API service's foreground-service
    // type to include `camera`. Without that type, OEM policies (Samsung, Mio,
    // newer Fire OS) revoke camera access ~5s after lockNow() backgrounds the
    // app, killing motion wake. Mirror of what commit 3328d7a2 did for RTSP.
    var onMotionWakeCameraStateChanged: ((cameraActive: Boolean) -> Unit)? = null

    // ==================== Sub-managers ====================
    // Scheduled refresh (stealth reload + daily refresh + alarm utilities)
    val refreshScheduler = ScreenRefreshScheduler(activity, halitePrefs)

    // Kiosk window flags and hardware wake
    val kioskWindowManager = KioskWindowManager(activity, halitePrefs)

    // Sleep/wake scheduling (AlarmManager + inactivity timer)
    private val sleepPrefs = SleepPreferences(activity)
    val sleepWakeScheduler = SleepWakeScheduler(
        activity = activity,
        sleepPrefs = sleepPrefs,
        onScreenOff = { screenOff() },
        onScreenOn = { screenOn() },
        scheduleAlarm = { triggerAt, pendingIntent, name ->
            refreshScheduler.scheduleAlarmWithFallback(triggerAt, pendingIntent, name)
        },
        isScreenOff = { isScreenOff() }
    )

    // Power management watchdog (Handler-based, 60s interval, direct REST API)
    val powerWatchdog = PowerManagementWatchdog(activity, halitePrefs)

    // Coroutine scope for async operations (photo loading)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track if destroyed to prevent async callbacks from accessing destroyed state
    private var isDestroyed = false

    // Direct screen off tracking for addon/HTML mode (where native ScreenDimmer is not created)
    private var _isScreenOffDirect = false

    // HTTP client for HA Media API calls. Accepts self-signed certs on LAN.
    private val httpClient = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Token refresh synchronization (prevents race conditions when multiple photos load simultaneously)
    private val tokenRefreshLock = Object()
    @Volatile private var isRefreshingToken = false

    /**
     * Set WebView reference for HA Media photo source.
     * Must be called after setup() for HA Media photos to work.
     */
    fun setWebView(webView: android.webkit.WebView?) {
        webViewRef = webView
        _screenDimmer?.setWebView(webView, halitePrefs)
        // Keep the refresh scheduler's WebView reference in sync after recreation so
        // subsequent daily/stealth refreshes don't hold a stale destroyed-WebView reference.
        refreshScheduler.updateWebView(webView)
        // Start power watchdog early — covers kiosk mode where onHaLoginCompleted() isn't called.
        // Safe to call multiple times (start() is idempotent).
        // No WebView needed — watchdog uses direct HA REST API.
        powerWatchdog.start()
    }

    /** Regression-rig oracle: run a one-shot paint check (logs PAINTED/BLANK). DEBUG only. */
    fun runBlankCheckNow(label: String) = blankScreenWatchdog.checkNow(label)

    // ==================== Screen Off/On API ====================
    // These methods work in BOTH native and HTML screensaver modes.
    // In native mode, they delegate to ScreenDimmer.
    // In HTML/addon mode, they use DevicePolicyManager directly.

    /**
     * Turn the screen off via API command.
     * Delegates to native ScreenDimmer.
     *
     * @param methodOverride "overlay" or "hardware" to override the default preference
     *                       for this call only. null = use default preference.
     */
    fun screenOff(methodOverride: String? = null) {
        // Dismiss any open overlays (control center, sidebar) before dimming
        onDismissOverlays?.invoke()

        val dimmer = _screenDimmer
        if (dimmer != null) {
            dimmer.screenOff(methodOverride)
            // Cancel inactivity timer while screen is off (no point tracking)
            sleepWakeScheduler.cancelSleepInactivityTimer()
            // Cancel re-sleep countdown — we're going to sleep, nothing left to re-sleep
            sleepWakeScheduler.cancelResleepCountdown()
            // Ensure wake alarm is scheduled so the device wakes up at the configured time
            sleepWakeScheduler.ensureWakeAlarmScheduled()
        } else {
            Log.w(TAG, "🔧 screenOff() called but ScreenDimmer not initialized")
        }
        // Notify refresh scheduler so stealth reloads fire during sleep, same as screensaver
        refreshScheduler.onSleepActivated(source = "screenOff(method=$methodOverride)")
        // Notify connection monitor so it knows disconnects are from WiFi sleep, not HA outage
        onScreenSleepChanged?.invoke(true)
    }

    /**
     * Turn the screen on via API command.
     * Delegates to native ScreenDimmer.
     */
    fun screenOn() {
        val dimmer = _screenDimmer
        if (dimmer != null) {
            dimmer.screenOn()
            // Restart inactivity timer after waking (start counting again)
            sleepWakeScheduler.startSleepInactivityTimer()
        } else {
            Log.w(TAG, "🔧 screenOn() called but ScreenDimmer not initialized")
        }
        // Notify refresh scheduler that sleep ended
        refreshScheduler.onSleepDeactivated(source = "screenOn")
        // Notify connection monitor that screen is awake
        onScreenSleepChanged?.invoke(false)
        // Restore overlays that were hidden on screen off (e.g. native sidebar)
        onRestoreOverlays?.invoke()
    }

    /**
     * Check if screen is in "off" state.
     * Works in both native and HTML screensaver modes.
     */
    fun isScreenOff(): Boolean {
        return _screenDimmer?.isScreenOff() ?: _isScreenOffDirect
    }

    /**
     * True when the active screensaver is blank (screen off/asleep or a fully
     * black overlay), so the music/video/timer cards should not dock to the
     * shared panel. See [ScreenDimmer.isBlankScreensaver].
     */
    fun isBlankScreensaver(): Boolean = _screenDimmer?.isBlankScreensaver() ?: false

    /**
     * Turn off screen hardware directly using DevicePolicyManager.
     * Used in HTML/addon mode where native ScreenDimmer doesn't exist.
     */
    private fun turnOffScreenHardwareDirect() {
        if (!DeviceAdminHelper.isActive(activity)) {
            Log.w(TAG, "🔧 Cannot turn off screen hardware - Device Admin not enabled")
            DiagnosticBuffer.error("SCREEN", "screenOff FAILED - Device Admin not enabled (HTML mode)")
            return
        }
        HalitePreferences(activity).sleep.wasInScreenOffMode = true
        Log.i(TAG, "🔧 Turning off screen hardware via lockNow() (HTML mode)")
        if (!DeviceAdminHelper.lockNow(activity)) {
            DiagnosticBuffer.error("SCREEN", "screenOff FAILED - lockNow returned false (HTML mode)")
        }
    }

    /**
     * Set up screen dimmer based on preferences.
     * Checks for camera permission if camera motion detection is enabled.
     */
    @SuppressLint("ClickableViewAccessibility")
    // ==================== Power-button screen-off grace ====================
    //
    // When the display turns off by a means we didn't initiate — the user pressing the
    // physical power button, as opposed to our own lockNow() screensaver-off — the user is
    // almost always still right in front of the camera. Without a grace window the very next
    // face/motion detection wakes the screen straight back on, so the power button feels
    // broken. Suppress motion/face wake for POWER_OFF_WAKE_GRACE_MS after such an external
    // screen-off, giving the user time to step away.
    //
    // The grace is anchored to lastScreenOnMs — the last time the wake path itself observed
    // the screen on — NOT to ACTION_SCREEN_OFF. The camera's isInteractive check sees the
    // screen-off faster than the broadcast arrives, and our own screenOn() fires
    // ACTION_SCREEN_ON which cleared the window early, so a broadcast-based grace raced and
    // fired too late. Measuring "time since the screen was last seen on" has no such race.
    // (Touch/API wakes are unaffected — they don't pass through this check.)
    private val POWER_OFF_WAKE_GRACE_MS = 4000L
    @Volatile private var lastScreenOnMs = android.os.SystemClock.elapsedRealtime()

    /** Record that the screen is on right now (resets the power-button grace anchor). */
    private fun markScreenOn() { lastScreenOnMs = android.os.SystemClock.elapsedRealtime() }

    /**
     * True if a face/motion wake should be suppressed because the display was turned off
     * externally (power button) within the last POWER_OFF_WAKE_GRACE_MS. Our own lockNow()
     * screensaver-off (isScreenOff) is never suppressed — only a system/power-button off.
     */
    private fun shouldSuppressExternalWake(dimmer: ScreenDimmer?): Boolean {
        if (dimmer == null || dimmer.isScreenOff()) return false
        return android.os.SystemClock.elapsedRealtime() - lastScreenOnMs < POWER_OFF_WAKE_GRACE_MS
    }

    /**
     * Wake decision for an external motion signal that doesn't flow through MotionWakeManager
     * — most notably RTSP camera-mode motion (handleRtspMotionDetected). Applies the same
     * display-off detection (isDisplayOff, so a power-button-off counts), sleep gate, and
     * power-button grace as the MotionWakeManager path, instead of the old isScreenOff-only
     * check that never woke from a system/power-button screen-off.
     */
    // WAKE PATH 2/4 (RTSP motion). Shared implementation the others mirror.
    // Keep in lockstep with the other 3 — see .reference/CAMERA_WAKE_PATHS.md
    fun onExternalMotionWake() {
        val dimmer = _screenDimmer
        val screenOff = dimmer?.isDisplayOff() ?: false
        if (screenOff) {
            val isSleepOff = dimmer?.isSleepScreenOff() ?: false
            if (isSleepOff && !halitePrefs.sleep.motionWakeForSleep) {
                Log.d(TAG, "RTSP motion during sleep but motionWakeForSleep disabled - ignoring")
                return
            }
            if (shouldSuppressExternalWake(dimmer)) {
                Log.d(TAG, "🔌 RTSP motion but wake suppressed (power-button grace window)")
                return
            }
            Log.d(TAG, "RTSP motion - waking from ${if (isSleepOff) "sleep" else "screen off"}")
            dimmer?.screenOn()
            markScreenOn()
        } else {
            Log.d(TAG, "RTSP motion - resetting screensaver timer")
            dimmer?.resetTimer()
            // Continued motion during the sleep window keeps the re-sleep countdown fresh
            // (motionWakeForSleep users expect presence to hold the screen on)
            sleepWakeScheduler.onUserActivity("motion")
            markScreenOn()
        }
    }

    /**
     * WAKE PATH 5 — voice ("Hey Dashie"). Fired from the voice pipeline the instant a
     * wake word passes its guards. Unlike the camera paths (1–4), voice is an explicit
     * user action, so it ALWAYS wakes: no sleep-schedule gate and no power-button grace
     * (those exist to stop *ambient* motion from re-waking a just-slept screen; a spoken
     * wake word is deliberate). Mirrors the screenOn / resetTimer + markScreenOn sequence
     * of [onExternalMotionWake]; uses isDisplayOff() (not isScreenOff()) so it also wakes
     * a power-button-off display. Must run on the main thread (caller posts).
     */
    fun onVoiceWake() {
        val dimmer = _screenDimmer
        if (dimmer?.isDisplayOff() == true) {
            Log.i(TAG, "🎤 Voice wake — waking from ${if (dimmer.isSleepScreenOff()) "sleep" else "screen off"}")
            dimmer.screenOn()
        } else {
            dimmer?.resetTimer()
        }
        // Each voice interaction resets the re-sleep countdown so an ongoing
        // conversation during the sleep window isn't cut off mid-turn.
        // (The screenOn() branch also arms via the dim-state callback; re-arming is quiet.)
        sleepWakeScheduler.onUserActivity("voiceWake")
        markScreenOn()
    }

    fun setup() {
        DiagnosticBuffer.info("SCREEN", "setup() called")

        // Check motion wake mode from preferences
        // motionWakeMode can be: "disabled", "brightness", or "camera"
        val motionWakeMode = halitePrefs.screensaver.motionWakeMode
        val cameraMotionEnabled = motionWakeMode == "camera" || motionWakeMode == "face"

        // Check if RTSP is enabled FIRST - RTSP needs exclusive camera access
        val rtspEnabled = halitePrefs.camera.rtspEnabled
        if (rtspEnabled && cameraMotionEnabled) {
            Log.i(TAG, "🔧 RTSP enabled - motion wake handled by RTSP frames (no local camera/sensor)")
        }

        // Determine if we can use camera for motion detection
        var useCameraForMotion = false
        if (cameraMotionEnabled && !rtspEnabled) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                useCameraForMotion = true
            } else {
                if (!haLoginCompleted) {
                    Log.i(TAG, "🔧 Deferring camera permission until HA login complete")
                } else if (!halitePrefs.camera.cameraPermissionRequested) {
                    Log.i(TAG, "🔧 Showing camera permission explanation dialog")
                    halitePrefs.camera.cameraPermissionRequested = true
                    showCameraPermissionDialog()
                    return // Will call setup again after permission granted
                } else {
                    // Permission was previously requested but is now missing.
                    // Fire OS can silently revoke permissions — re-request.
                    Log.w(TAG, "🔧 Camera permission was granted but got revoked — re-requesting")
                    showCameraPermissionDialog()
                    return
                }
            }
        }

        createScreenDimmer(useCameraDetection = useCameraForMotion)

        // Schedule sleep/wake alarms early so they're active before HA login completes
        scheduleSleepWakeAlarms()
    }

    /**
     * Set up screen dimmer without camera motion detection (fallback).
     */
    fun setupWithoutCamera() {
        createScreenDimmer(useCameraDetection = false)
    }

    /**
     * Create and configure the ScreenDimmer with all required wiring.
     * Shared by setup() and setupWithoutCamera().
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createScreenDimmer(useCameraDetection: Boolean) {
        val dimOverlay = activity.findViewById<ViewGroup>(R.id.screenDimOverlay)
        if (dimOverlay == null) {
            DiagnosticBuffer.error("SCREEN", "createScreenDimmer() ABORTED - dimOverlay view not found!")
            return
        }
        val photoContainer = activity.findViewById<FrameLayout>(R.id.photoSlideshowContainer)
        if (photoContainer == null) {
            DiagnosticBuffer.error("SCREEN", "createScreenDimmer() ABORTED - photoContainer view not found!")
            return
        }
        val timeoutSeconds = halitePrefs.screensaver.screensaverTimeout
        val autoScreensaverEnabled = timeoutSeconds > 0

        if (!autoScreensaverEnabled) {
            Log.i(TAG, "🔧 Auto-screensaver DISABLED (timeout = 0), but manual control still available")
        }

        // Destroy existing screen dimmer and motion wake manager before creating new ones
        // This prevents race conditions when setup() is called multiple times
        if (_screenDimmer != null) {
            DiagnosticBuffer.info("SCREEN", "Destroying existing screenDimmer in createScreenDimmer()")
        }
        _screenDimmer?.destroy()
        _screenDimmer = null
        motionWakeManager?.destroy()
        motionWakeManager = null

        val screensaverMode = halitePrefs.screensaver.screensaverMode

        // Create screen dimmer FIRST (before motion wake manager, so the callback can reference it)
        _screenDimmer = ScreenDimmer(
            context = activity,
            dimOverlay = dimOverlay,
            photoContainer = photoContainer,
            prefs = screensaverPrefs,
            timeoutSeconds = timeoutSeconds,
            screensaverMode = screensaverMode
        ) { isDimmed ->
            Log.i(TAG, "🔧 Screen ${if (isDimmed) "dimmed" else "woken"}")
            nativeScreensaverActive = isDimmed
            if (isDimmed) {
                motionWakeManager?.onScreenDimmed()
                onScreenDimmedCallback?.invoke()
                refreshScheduler.onScreensaverActivated(source = "screenDimmer_isDimmed")
                // Screensaver running inside the sleep window with no countdown = the
                // cold-start hole (see SleepWakeScheduler.ensureResleepArmed) — make
                // sure the device finds its way back to sleep. Never resets an
                // in-flight countdown; no-op when the screen is off or out of window.
                sleepWakeScheduler.ensureResleepArmed("screensaverActive")
                // Notify connection monitor — screensaver means screen is "sleeping"
                onScreenSleepChanged?.invoke(true)
                // Dashboard is now occluded — suppress blank checks (legitimately covered)
                blankScreenWatchdog.onScreensaverActive()
                // In RTSP+face mode, immediately start a face search so someone
                // standing still looking at the screen gets detected right away
                if (rtspFaceDetector != null) {
                    rtspFaceDetector?.onMotionDetected()
                    Log.i(TAG, "🔧 RTSP face mode: auto-triggering face search on screen dim")
                }
            } else {
                motionWakeManager?.onScreenWoken()
                refreshScheduler.onScreensaverDeactivated(source = "screenDimmer_isWoken")
                // A wake during the scheduled sleep window arms the re-sleep countdown.
                // This is the choke point for every dimmer wake (touch/key on the
                // overlay, voice wake, motion wake — they all funnel through
                // ScreenDimmer.screenOn()/wakeScreen()).
                sleepWakeScheduler.onUserActivity("screenDimmer_woken")
                // Wake paths that call dimmer.screenOn() directly (voice, motion)
                // bypass screenOn(), leaving the refresh scheduler's sleep latch
                // stale — sleepActive stayed true all night on 07-19. Clear it here.
                if (refreshScheduler.isScreenOffDirect && !isScreenOff()) {
                    refreshScheduler.onSleepDeactivated(source = "screenDimmer_isWoken")
                }
                // Notify connection monitor — screen is awake
                onScreenSleepChanged?.invoke(false)
                // THE FIX: force a content-preserving re-composite so a GPU surface
                // reclaimed while occluded can't leave a gray buffer; then run blank
                // detection for field measurement.
                blankScreenWatchdog.onScreensaverRevealed()
            }
            onScreensaverStateChanged?.invoke(isDimmed)
        }

        // Wire hardware wake callback
        _screenDimmer?.onHardwareWakeRequested = {
            wakeFromHardwareSleep()
        }

        // Wire auto-brightness pause/resume during dim/sleep
        _screenDimmer?.onBrightnessPauseChanged = { paused ->
            lightSensorController?.setPaused(paused)
        }

        // Wire ha_page navigation callback (navigates main WebView)
        _screenDimmer?.onHaPageNavigate = { pagePath ->
            onHaPageNavigate?.invoke(pagePath)
        }

        // Pass WebView reference for HA Media photo source (if set before setup)
        webViewRef?.let { _screenDimmer?.setWebView(it, halitePrefs) }

        // Set up clock + date + weather views for black/dim modes
        val clockTextView = activity.findViewById<android.widget.TextView>(R.id.screenDimClock)
        _screenDimmer?.setClockView(clockTextView)
        val dateTextView = activity.findViewById<android.widget.TextView>(R.id.screenDimDate)
        _screenDimmer?.setDateView(dateTextView)
        val weatherTextView = activity.findViewById<android.widget.TextView>(R.id.screenDimWeather)
        _screenDimmer?.setWeatherView(weatherTextView)

        // Set up background view (needs to be hidden for URL/photos modes)
        val backgroundView = activity.findViewById<android.view.View>(R.id.screenDimBackground)
        _screenDimmer?.setBackgroundView(backgroundView)

        // Set initial RTSP streaming state
        if (halitePrefs.camera.rtspEnabled) {
            _screenDimmer?.setRtspStreaming(true)
        }

        // Handle touch on dim overlay to wake screen
        dimOverlay.setOnTouchListener { _, event ->
            _screenDimmer?.onTouchEvent(event) ?: false
        }

        // Setup motion wake manager if enabled (AFTER screen dimmer is created)
        val motionWakeMode = halitePrefs.screensaver.motionWakeMode
        val motionWakeEnabled = motionWakeMode != "disabled"
        if (motionWakeEnabled && autoScreensaverEnabled) {
            setupMotionWake(useCameraDetection)
        } else {
            // Skipped setup — make sure the FGS coordinator knows the camera
            // isn't being held by motion wake. setupMotionWake() above already
            // calls notifyMotionWakeCameraState() at its end, so we only need
            // this branch for the skip case.
            notifyMotionWakeCameraState()
        }

        val autoStatus = if (autoScreensaverEnabled) "auto=${timeoutSeconds}s" else "manual-only"
        Log.i(TAG, "🔧 Screen dimmer ENABLED ($autoStatus, mode=$screensaverMode, camera=$useCameraDetection)")
        DiagnosticBuffer.info("SCREEN", "screenDimmer CREATED: $autoStatus, mode=$screensaverMode")

        // Start refresh scheduler unconditionally — this is a memory management
        // feature that doesn't depend on HA auth state. Runs on all setups including
        // kiosk mode (where onHaLoginCompleted() is never called) and trusted networks.
        // Must use startStealthReloadTimer when WebView is available so that
        // stealthReloadWebView is set — otherwise performDailyRefresh() silently
        // bails out because it has no WebView reference to reload.
        webViewRef?.let { webView ->
            refreshScheduler.startStealthReloadTimer(webView)
        } ?: startDailyRefreshScheduler()
    }

    /**
     * Setup motion wake manager with specified camera detection setting.
     */
    private fun setupMotionWake(useCameraDetection: Boolean) {
        // Destroy existing motion wake manager before creating new one
        // This prevents race conditions when setup is called multiple times
        motionWakeManager?.destroy()
        motionWakeManager = null

        val isFaceMode = halitePrefs.screensaver.motionWakeMode == "face"
        val faceDistPct = com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences.faceDistanceToPercent(halitePrefs.screensaver.faceWakeDistance)
        motionWakeManager = MotionWakeManager(
            context = activity,
            lifecycleOwner = lifecycleOwner,
            useCameraDetection = useCameraDetection,
            useFaceDetection = isFaceMode && useCameraDetection,
            faceDistancePercent = faceDistPct,
            // WAKE PATH 1/4 (non-RTSP motion AND non-RTSP face converge here).
            // Keep in lockstep with the other 3 — see .reference/CAMERA_WAKE_PATHS.md
            onMotionDetected = {
                // Motion/face confirmed - wake the screen
                val dimmer = _screenDimmer
                val screenOff = dimmer?.isDisplayOff() ?: false
                val isSleepOff = dimmer?.isSleepScreenOff() ?: false
                Log.i(TAG, "🔧 MotionWake callback (screenOff=$screenOff, isSleepOff=$isSleepOff, motionWakeForSleep=${halitePrefs.sleep.motionWakeForSleep})")
                if (screenOff) {
                    // Only check motionWakeForSleep for actual sleep schedule events,
                    // not for screensaver "off" mode (which should always wake on motion)
                    if (isSleepOff && !halitePrefs.sleep.motionWakeForSleep) {
                        Log.d(TAG, "🔧 Motion detected during sleep but motionWakeForSleep is disabled - ignoring")
                        return@MotionWakeManager
                    }
                    if (shouldSuppressExternalWake(dimmer)) {
                        Log.d(TAG, "🔌 Motion/face detected but wake suppressed (power-button grace window)")
                        return@MotionWakeManager
                    }
                    Log.i(TAG, "🔧 Calling screenOn() to wake from ${if (isSleepOff) "sleep" else "screensaver off mode"}")
                    dimmer?.screenOn()
                    markScreenOn()
                } else {
                    Log.i(TAG, "🔧 Calling resetTimer() to wake from screensaver")
                    dimmer?.resetTimer()
                    markScreenOn()
                }
                // Notify HA sensor publisher (used when RTSP is not streaming)
                onExtraMotionDetected?.invoke()
            },
            onFaceKeepAlive = {
                // Presence keep-alive (face mode): a face in view resets the inactivity
                // timer so the screensaver doesn't start while the user is present —
                // even a still, motionless face. Fires for any detected face (presence,
                // not proximity). resetTimer() no-ops when the screen is fully off, so a
                // distant face never wakes a sleeping screen; only a qualifying close
                // face wakes from off via the onMotionDetected path above.
                Handler(Looper.getMainLooper()).post {
                    val dimmer = _screenDimmer
                    if (dimmer != null && !dimmer.isScreenOff()) {
                        dimmer.resetTimer()
                    }
                }
            }
        )

        // Apply camera threshold: fixed 1.5% for face mode, user preference for motion mode
        if (useCameraDetection) {
            val isFace = halitePrefs.screensaver.motionWakeMode == "face"
            val threshold = if (isFace) 1.5 else halitePrefs.screensaver.cameraWakeThresholdDouble
            motionWakeManager?.setCameraThreshold(threshold)
            Log.d(TAG, "🔧 Camera motion threshold set to $threshold%" + if (isFace) " (fixed for face mode)" else "")
        }

        // When RTSP owns the camera, motion is detected from the RTSP frames (see
        // MediaComponentWiring.onRtspMotionDetected → handleRtspMotionDetected). Put
        // this manager straight into RTSP mode instead of calling start(): start()
        // would fall back to the LIGHT sensor (useCameraDetection is always false when
        // RTSP is on, per the caller), and on every recreation (refresh / screensaver
        // re-arm) that re-registers a bogus light listener which can spuriously wake the
        // screen. Decided from prefs so it holds across recreations regardless of caller.
        // "brightness" mode genuinely wants the light sensor and is intentionally left alone.
        val cameraMotionRequested = halitePrefs.screensaver.motionWakeMode == "camera" ||
            halitePrefs.screensaver.motionWakeMode == "face"
        val rtspOwnsCamera = halitePrefs.camera.rtspEnabled && cameraMotionRequested

        if (rtspOwnsCamera) {
            motionWakeManager?.enableRtspMotionMode()
            Log.i(TAG, "🔧 Motion wake ENABLED (mode: RTSP — camera owned by RTSP, no light fallback)")
        } else if (motionWakeManager?.isAvailable() == true) {
            motionWakeManager?.start()
            val mode = motionWakeManager?.getDetectionMode()
            Log.i(TAG, "🔧 Motion wake ENABLED (mode: $mode, camera: $useCameraDetection, threshold: ${halitePrefs.screensaver.cameraWakeThresholdDouble}%)")
        } else {
            Log.w(TAG, "🔧 Motion wake not available")
            motionWakeManager = null
        }
        notifyMotionWakeCameraState()
    }

    /**
     * Fires [onMotionWakeCameraStateChanged] with whether the motion wake
     * manager is currently using the camera (CAMERA or FACE detection mode).
     * Call from every site that mutates motionWakeManager — the receiver
     * (DashieServiceManager) dedupes, so over-firing is safe.
     */
    private fun notifyMotionWakeCameraState() {
        val mode = motionWakeManager?.getDetectionMode()
        val active = mode == MotionWakeManager.DetectionMode.CAMERA ||
                     mode == MotionWakeManager.DetectionMode.FACE
        onMotionWakeCameraStateChanged?.invoke(active)
    }

    /**
     * Re-publish the current motion-wake camera state to [onMotionWakeCameraStateChanged].
     * Call this once the callback has been wired (MainHaliteSetup) so the live state
     * reaches the service even if setupMotionWake() ran — and fired its notify into a
     * null callback — before the wiring. Idempotent; the receiver dedupes.
     */
    fun publishMotionWakeCameraState() {
        notifyMotionWakeCameraState()
    }

    /**
     * Refresh motion wake manager based on current preferences.
     * Call this when motion wake mode is changed from settings UI.
     */
    fun refreshMotionWake() {
        val motionWakeMode = halitePrefs.screensaver.motionWakeMode
        val motionWakeEnabled = motionWakeMode != "disabled"

        Log.i(TAG, "🔧 Refreshing motion wake (mode: $motionWakeMode, enabled: $motionWakeEnabled)")

        if (!motionWakeEnabled) {
            // Disable motion wake
            motionWakeManager?.destroy()
            motionWakeManager = null
            notifyMotionWakeCameraState()
            Log.i(TAG, "🔧 Motion wake DISABLED")
            return
        }

        // Check if RTSP is enabled - RTSP needs exclusive camera access
        val rtspEnabled = halitePrefs.camera.rtspEnabled
        val cameraMotionEnabled = motionWakeMode == "camera" || motionWakeMode == "face"

        // User picked camera/face mode but we don't have permission — prompt
        // instead of silently falling back to sensor. Mirrors the setup()
        // behavior at boot. On grant, onCameraPermissionResult() re-runs setup()
        // which recreates the screen dimmer + motion wake with camera enabled.
        if (cameraMotionEnabled && !rtspEnabled && !hasCameraPermission()) {
            Log.i(TAG, "🔧 $motionWakeMode mode selected without camera permission — prompting")
            halitePrefs.camera.cameraPermissionRequested = true
            showCameraPermissionDialog()
            return
        }

        val useCameraDetection = cameraMotionEnabled && !rtspEnabled

        if (cameraMotionEnabled && rtspEnabled) {
            Log.i(TAG, "🔧 Motion wake: Camera mode requested but RTSP owns the camera - motion handled by RTSP frames")
        }

        setupMotionWake(useCameraDetection)
    }

    /**
     * Show camera permission explanation dialog before requesting permission.
     * Uses custom dialog layout matching Quick Tip modal style.
     */
    private fun showCameraPermissionDialog() {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_camera_permission, null)
        dialogView.findViewById<android.widget.TextView>(R.id.cameraPermissionBody)?.text =
            activity.getString(R.string.camera_permission_body, activity.getString(R.string.brand_name))

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // Make dialog background transparent for rounded corners
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.skipButton).setOnClickListener {
            dialog.dismiss()
            Log.i(TAG, "🔧 User skipped camera permission - using fallback")
            setupWithoutCamera()
        }

        dialogView.findViewById<Button>(R.id.continueButton).setOnClickListener {
            dialog.dismiss()
            Log.i(TAG, "🔧 User acknowledged camera permission - requesting")
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        dialog.show()
    }

    /**
     * Handle camera permission result.
     * @param granted true if permission was granted
     */
    fun onCameraPermissionResult(granted: Boolean) {
        if (granted) {
            setup()
        } else {
            setupWithoutCamera()
        }
    }

    /**
     * Check if storage permission is granted.
     */
    fun hasStoragePermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: Need READ_MEDIA_IMAGES
            ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            // Older Android: Need READ_EXTERNAL_STORAGE
            ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if camera permission is granted.
     * Used by sidebar to show warning icon when camera mode is selected but permission denied.
     */
    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Request camera permission for motion wake.
     * @param onResult Callback with result (true if granted, false if denied)
     */
    fun requestCameraPermission(onResult: (Boolean) -> Unit) {
        if (hasCameraPermission()) {
            onResult(true)
            return
        }

        // Save callback for when permission result comes back
        onCameraPermissionResult = onResult

        Log.i(TAG, "🔧 Requesting camera permission")
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    /**
     * Handle camera permission result.
     * Called from MainActivity when permission result comes back.
     * @param granted true if permission was granted
     */
    fun onCameraPermissionResultReceived(granted: Boolean) {
        Log.i(TAG, "📷 Camera permission ${if (granted) "granted" else "denied"}")
        onCameraPermissionResult?.invoke(granted)
        onCameraPermissionResult = null
    }

    /**
     * Request storage permission for photo screensaver.
     * @param onGranted Callback when permission is granted
     */
    fun requestStoragePermission(onGranted: () -> Unit) {
        if (hasStoragePermission()) {
            onGranted()
            return
        }

        // Save callback for when permission result comes back
        onStoragePermissionGranted = onGranted

        // Request appropriate permission based on Android version
        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        Log.i(TAG, "🔧 Requesting storage permission: $permission")
        storagePermissionLauncher.launch(permission)
    }

    /**
     * Handle storage permission result.
     * @param granted true if permission was granted
     */
    fun onStoragePermissionResult(granted: Boolean) {
        Log.i(TAG, "🔧 Storage permission ${if (granted) "granted" else "denied"}")
        if (granted) {
            onStoragePermissionGranted?.invoke()
        }
        onStoragePermissionGranted = null
    }

    /**
     * Launch native folder picker for photo source selection.
     * @param onFolderSelected Callback when a folder is selected
     */
    fun launchFolderPicker(onFolderSelected: (android.net.Uri) -> Unit) {
        onFolderPickerResult = onFolderSelected
        Log.i(TAG, "🔧 Launching folder picker")
        folderPickerLauncher.launch(null)  // null starts at root
    }

    /**
     * Handle folder picker result.
     * @param uri The selected folder URI
     */
    fun onFolderSelected(uri: android.net.Uri) {
        Log.i(TAG, "🔧 Folder selected: $uri")
        onFolderPickerResult?.invoke(uri)
        onFolderPickerResult = null
    }

    /**
     * Called when HA login is complete (dashboard loaded).
     * This enables camera permission requests that were deferred.
     */
    fun onHaLoginCompleted() {
        haLoginCompleted = true
        val motionWakeMode = halitePrefs.screensaver.motionWakeMode
        val cameraMotionEnabled = motionWakeMode == "camera" || motionWakeMode == "face"
        val hasPermission = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "🔧 HA login complete - motionWakeMode: $motionWakeMode, hasPermission: $hasPermission, alreadyRequested: ${halitePrefs.camera.cameraPermissionRequested}")

        // Re-run setup to trigger camera permission dialog if needed
        // This handles the case where setup() was called before login completed
        // and we deferred the camera permission request
        if (cameraMotionEnabled) {
            Log.i(TAG, "🔧 Re-running setup() to enable camera motion detection")
            setup()
        }

        // Start scheduled refresh timers (both interval and daily)
        // These run independently of screensaver state for memory management
        webViewRef?.let { webView ->
            refreshScheduler.startStealthReloadTimer(webView)  // This also starts daily refresh scheduler
            powerWatchdog.start()  // Ensures power management runs during Doze
            Log.i(TAG, "🔄 Scheduled refresh timers started on HA login")
        } ?: run {
            // Fallback: just start daily refresh if WebView not set yet
            refreshScheduler.startDailyRefreshScheduler()
            Log.w(TAG, "🔄 WebView not available - only daily refresh started")
        }

        // Pre-warm photo cache so first screensaver load is instant
        _screenDimmer?.prewarmPhotoCache()
    }

    // ==================== Facade methods for KioskWindowManager ====================

    var onWindowFlagsChanged: (() -> Unit)?
        get() = kioskWindowManager.onWindowFlagsChanged
        set(value) { kioskWindowManager.onWindowFlagsChanged = value }

    fun applyKioskSettings(launchedFromBoot: Boolean = false) = kioskWindowManager.applyKioskSettings(launchedFromBoot)

    /**
     * Update screensaver settings at runtime.
     * Propagates changes to native ScreenDimmer.
     */
    fun updateScreensaverSettings(timeout: Int, mode: String) {
        _screenDimmer?.setTimeoutSeconds(timeout)
        _screenDimmer?.setMode(mode)
        // Rebuild weather overlay so changes to forecastCardSize / weatherMode
        // take effect immediately during an active screensaver cycle.
        _screenDimmer?.refreshWeatherOverlayIfActive()
        Log.i(TAG, "🔧 Screensaver updated: timeout=$timeout, mode=$mode")
    }

    /** Toggle the HA time provider on the active screensaver clock. */
    fun refreshHaTimeProvider() {
        _screenDimmer?.refreshHaTimeProvider()
    }

    /**
     * Check if an external app is currently active as screensaver.
     * Used by MainActivity to detect returning from an external screensaver app.
     */
    fun isExternalAppActive(): Boolean = _screenDimmer?.isExternalAppActive() ?: false

    /**
     * Get the current motion score from camera detection for graph visualization.
     * Returns 0.0 if motion detection is not active or no score available yet.
     */
    fun getCurrentMotionScore(): Double = motionWakeManager?.getCurrentMotionScore() ?: 0.0

    /**
     * Get motion wake diagnostics for troubleshooting.
     */
    fun getMotionWakeDiagnostics(): String {
        val manager = motionWakeManager ?: return "Motion Wake: Not initialized"
        return manager.getDiagnostics()
    }

    /**
     * Enable graph mode for motion wake camera - scores motion without triggering wake events
     */
    fun enableMotionGraphMode() = motionWakeManager?.enableGraphMode()

    /**
     * Disable graph mode for motion wake camera
     */
    fun disableMotionGraphMode() = motionWakeManager?.disableGraphMode()

    /**
     * Check if any face detection pipeline is available for test mode.
     * Used by ComponentRegistry to decide whether to create a temporary pipeline.
     */
    fun hasFaceTestCapability(): Boolean {
        // Check if MotionWakeManager has a working camera detector (not gutted by RTSP mode).
        // When RTSP calls enableRtspMotionMode(), the camera/face detectors are destroyed
        // and mode is set to NONE, so motionWakeManager != null is not sufficient.
        val hasWorkingCameraMotion = motionWakeManager?.getDetectionMode()?.let {
            it == MotionWakeManager.DetectionMode.CAMERA || it == MotionWakeManager.DetectionMode.FACE
        } ?: false
        return hasWorkingCameraMotion || rtspFaceDetector != null
    }

    /**
     * Create a temporary RTSP face detector for settings dialog test preview.
     * Used when no MotionWakeManager or permanent rtspFaceDetector exists (e.g., fresh install).
     * The caller must wire RTSP face frame callback separately.
     */
    fun createTempRtspFaceDetector() {
        if (rtspFaceDetector != null) return
        val faceDistPct = com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences.faceDistanceToPercent(halitePrefs.screensaver.faceWakeDistance)
        rtspFaceDetector = FaceWakeDetector(
            onFaceDetected = { /* no-op: test mode only reports via onFaceResult */ }
        ).apply { minFaceSizePercent = faceDistPct }
        Log.i(TAG, "🔧 Created temp RTSP face detector for settings test (min=${(faceDistPct * 100).toInt()}%)")
    }

    /**
     * Destroy a temporary RTSP face detector created for settings test.
     */
    fun destroyTempRtspFaceDetector() {
        rtspFaceDetector?.destroy()
        rtspFaceDetector = null
        Log.i(TAG, "🔧 Destroyed temp RTSP face detector")
    }

    /**
     * Set face detection test callback for settings dialog.
     * When non-null, enables face test mode so frames are analyzed for face indicator.
     * When null, disables test mode and clears callback.
     * Works with both local CameraMotionDetector and RTSP camera source.
     */
    fun setFaceTestCallback(callback: ((Int) -> Unit)?) {
        // Try MotionWakeManager first (local camera mode)
        motionWakeManager?.setFaceTestCallback(callback)

        // Also try RTSP face detector (RTSP+face mode)
        val detector = rtspFaceDetector
        if (detector != null) {
            if (callback != null) {
                detector.onFaceResult = callback
                detector.enableTestMode()
            } else {
                detector.disableTestMode()
                detector.onFaceResult = null
            }
        }
    }

    /**
     * Update face distance filter on the live face detector(s).
     * Called from settings dialog when user changes distance in real-time.
     */
    fun updateFaceDistance(percent: Float) {
        motionWakeManager?.updateFaceDistance(percent)
        rtspFaceDetector?.minFaceSizePercent = percent
    }

    /**
     * Called when the activity goes to background.
     * Pauses the screensaver timer to prevent it from triggering in background.
     */
    fun onActivityPaused() {
        _screenDimmer?.onActivityPaused()
    }

    /**
     * Called when the activity returns to foreground.
     * Restarts the screensaver timer.
     */
    fun onActivityResumed() {
        _screenDimmer?.onActivityResumed()
    }

    /**
     * Called when Dashie comes back to foreground after an external app was running.
     * This wakes the screen and resets the timer.
     */
    fun onReturnFromExternalApp() {
        _screenDimmer?.onReturnFromExternalApp()
    }

    /**
     * Trigger return from external app mode via motion detection or API call.
     * This brings Dashie to foreground and cleans up the external app state.
     */
    fun triggerReturnFromExternalApp() {
        _screenDimmer?.triggerReturnFromExternalApp()
    }

    fun wakeFromHardwareSleep() = kioskWindowManager.wakeFromHardwareSleep()
    fun clearWakeFlags() = kioskWindowManager.clearWakeFlags()

    /**
     * Enable RTSP motion detection mode.
     * This stops the local camera motion detector and switches to RTSP-based detection.
     * Call this when RTSP streaming is enabled to prevent camera resource conflicts.
     */
    fun enableRtspMotionMode() {
        motionWakeManager?.enableRtspMotionMode()
        // Also notify screen dimmer so it doesn't start its own motion detector
        // during photo screensaver mode
        _screenDimmer?.setRtspStreaming(true)

        // Create standalone face detector for RTSP+Face mode
        if (halitePrefs.screensaver.motionWakeMode == "face") {
            createRtspFaceDetector()
        }

        Log.i(TAG, "🔧 RTSP motion mode enabled (camera released for RTSP)")
    }

    /**
     * Disable RTSP motion detection mode.
     * This allows the local camera motion detector to restart if camera wake is enabled.
     * Call this when RTSP streaming is disabled.
     */
    fun disableRtspMotionMode() {
        motionWakeManager?.disableRtspMotionMode()
        // Also notify screen dimmer that RTSP is no longer streaming
        _screenDimmer?.setRtspStreaming(false)
        // Clean up RTSP face detector
        destroyRtspFaceDetector()
        Log.i(TAG, "🔧 RTSP motion mode disabled (camera may restart for local detection)")
    }

    // ==================== RTSP Face Detection ====================

    /**
     * Create the standalone ML Kit face detector for RTSP+Face mode.
     * This is used when RTSP owns the camera and MotionWakeManager can't run
     * its own CameraMotionDetector. Skipped when Camera2 HW face detection
     * is available (zero-CPU path via processHwFaceResult → onHwFaceDetected).
     */
    internal fun createRtspFaceDetector() {
        if (rtspFaceDetector != null) return
        // Always create the ML Kit face detector even when HW face is preferred.
        // When HW is active, Camera2MotionVideoSource skips YUV→Bitmap conversion
        // so this detector sits idle at zero cost. If HW is detected as broken at
        // runtime, ML Kit kicks in automatically.
        val faceDistPct = com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences.faceDistanceToPercent(halitePrefs.screensaver.faceWakeDistance)
        rtspFaceDetector = FaceWakeDetector(
            // WAKE PATH 3/4 (RTSP ML-Kit face fallback).
            // Keep in lockstep with the other 3 — see .reference/CAMERA_WAKE_PATHS.md
            onFaceDetected = {
                val dimmer = _screenDimmer
                val screenOff = dimmer?.isDisplayOff() ?: false
                val isSleepOff = dimmer?.isSleepScreenOff() ?: false
                if (screenOff) {
                    // Gate only on a sleep-schedule off (matches motion path); wake from
                    // screensaver "off" mode regardless of motionWakeForSleep.
                    if (!(isSleepOff && !halitePrefs.sleep.motionWakeForSleep) && !shouldSuppressExternalWake(dimmer)) {
                        dimmer?.screenOn()
                        markScreenOn()
                    }
                } else {
                    dimmer?.resetTimer()
                    markScreenOn()
                }
            }
        )
        rtspFaceDetector?.minFaceSizePercent = faceDistPct
        rtspFaceDetector?.onFacePresent = {
            // Presence keep-alive (RTSP+face, ML Kit path): mirror the MotionWakeManager
            // path — any face in view resets the inactivity timer while the screen is on.
            Handler(Looper.getMainLooper()).post {
                val dimmer = _screenDimmer
                if (dimmer != null && !dimmer.isScreenOff()) {
                    dimmer.resetTimer()
                }
            }
        }
        Log.i(TAG, "Created RTSP face detector (distance=${halitePrefs.screensaver.faceWakeDistance})")
    }

    /**
     * Destroy the standalone RTSP face detector.
     */
    private fun destroyRtspFaceDetector() {
        rtspFaceDetector?.destroy()
        rtspFaceDetector = null
    }

    /**
     * Called from handleRtspMotionDetected when face mode is active.
     * Notifies the RTSP face detector that motion was detected, starting the face search.
     */
    fun onRtspMotionForFace() {
        rtspFaceDetector?.onMotionDetected()
    }

    /**
     * Called when Camera2 hardware (ISP) face detection detects a qualifying face.
     * This is the zero-CPU-cost path — no ML Kit involved.
     * Wakes the screen or resets the dimmer timer, same as ML Kit onFaceDetected.
     */
    // WAKE PATH 4/4 (RTSP hardware/ISP face).
    // Keep in lockstep with the other 3 — see .reference/CAMERA_WAKE_PATHS.md
    fun onHwFaceDetected() {
        // Camera2 calls this on the camera handler thread, but ScreenDimmer touches
        // WebView (stopLoading, loadUrl) which requires the main thread.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { onHwFaceDetected() }
            return
        }
        val dimmer = _screenDimmer
        val screenOff = dimmer?.isDisplayOff() ?: false
        val isSleepOff = dimmer?.isSleepScreenOff() ?: false
        if (screenOff) {
            // Only gate on motionWakeForSleep for an actual sleep-schedule off — NOT for
            // screensaver "off" mode. Matches the motion-wake path (setupMotionWake), which
            // wakes from screensaver-off regardless of motionWakeForSleep. (Proven via HW
            // face DIAG: face qualifies + screenOff=true + isSleepOff=false, motion wakes but
            // face was silently dropped.)
            if (isSleepOff && !halitePrefs.sleep.motionWakeForSleep) {
                Log.d(TAG, "HW face detected during sleep but motionWakeForSleep disabled - ignoring")
                return
            }
            if (shouldSuppressExternalWake(dimmer)) {
                Log.d(TAG, "🔌 HW face detected but wake suppressed (power-button grace window)")
                return
            }
            Log.i(TAG, "HW face detected - waking from ${if (isSleepOff) "sleep" else "screensaver off mode"}")
            dimmer?.screenOn()
            markScreenOn()
        } else {
            Log.d(TAG, "HW face detected - resetting dimmer timer")
            dimmer?.resetTimer()
            markScreenOn()
        }
    }

    /**
     * Forward a frame from RTSP camera source to the face detector.
     * Called from Camera2MotionVideoSource via the face frame callback.
     */
    fun forwardRtspFrameToFace(bitmap: android.graphics.Bitmap, rotation: Int) {
        rtspFaceDetector?.analyzeFrame(bitmap, rotation)
    }

    /**
     * Get the RTSP face detector for test mode (settings dialog indicator).
     */
    fun getRtspFaceDetector(): FaceWakeDetector? = rtspFaceDetector

    // ==================== Return-to-Home Timer ====================

    /**
     * Start the return-to-home inactivity timer.
     * When the timer fires, navigates the WebView back to the configured dashboard URL.
     * Call this after setup to initialize the timer based on preferences.
     */
    fun startReturnHomeTimer() {
        val timeoutSeconds = halitePrefs.performance.returnHomeTimeout
        if (timeoutSeconds <= 0) {
            // Timer disabled - cancel any existing timer
            cancelReturnHomeTimer()
            Log.d(TAG, "🏠 Return-to-home timer DISABLED (timeout = 0)")
            return
        }

        if (returnHomeRunnable == null) {
            returnHomeRunnable = Runnable {
                Log.i(TAG, "🏠 Return-to-home timer fired - navigating to home dashboard")
                DiagnosticBuffer.info("SCREEN", "Return-to-home timer fired")
                onReturnHome?.invoke()
            }
        }

        handler.removeCallbacks(returnHomeRunnable!!)
        handler.postDelayed(returnHomeRunnable!!, timeoutSeconds * 1000L)
        Log.d(TAG, "🏠 Return-to-home timer started: ${timeoutSeconds}s")
    }

    /**
     * Reset the return-to-home timer (on user interaction).
     * Should be called alongside screensaver timer resets.
     */
    fun resetReturnHomeTimer() {
        val timeoutSeconds = halitePrefs.performance.returnHomeTimeout
        if (timeoutSeconds <= 0) return

        if (returnHomeRunnable != null) {
            handler.removeCallbacks(returnHomeRunnable!!)
            handler.postDelayed(returnHomeRunnable!!, timeoutSeconds * 1000L)
        } else {
            // Timer not initialized yet - start it
            startReturnHomeTimer()
        }
    }

    /**
     * Cancel the return-to-home timer.
     */
    private fun cancelReturnHomeTimer() {
        returnHomeRunnable?.let { handler.removeCallbacks(it) }
    }

    /**
     * Update the return-to-home timeout (called when user changes setting).
     */
    fun updateReturnHomeTimeout(seconds: Int) {
        Log.i(TAG, "🏠 Return-to-home timeout updated to ${seconds}s")
        cancelReturnHomeTimer()
        if (seconds > 0) {
            startReturnHomeTimer()
        }
    }

    // ==================== Facade methods for ScreenRefreshScheduler ====================
    // These delegate to refreshScheduler to maintain backward compatibility
    // with callers that access methods via HaliteScreenController.

    fun startStealthReloadTimer(webView: android.webkit.WebView) {
        refreshScheduler.startStealthReloadTimer(webView)
        // Also start sleep/wake alarms (previously bundled together)
        scheduleSleepWakeAlarms()
    }
    fun cancelStealthReloadTimer() = refreshScheduler.cancelStealthReloadTimer()
    fun completeStealthReload(webView: android.webkit.WebView) = refreshScheduler.completeStealthReload(webView)
    fun hasStealthReloadPending(): Boolean = refreshScheduler.hasStealthReloadPending()
    fun getSavedUrlBeforeReload(): String? = refreshScheduler.getSavedUrlBeforeReload()
    fun setStealthReloadInterval(minutes: Int) = refreshScheduler.setStealthReloadInterval(minutes)
    fun setStealthReloadEnabled(enabled: Boolean) = refreshScheduler.setStealthReloadEnabled(enabled)
    fun refreshStealthReloadSettings() = refreshScheduler.refreshStealthReloadSettings()
    fun startDailyRefreshScheduler() = refreshScheduler.startDailyRefreshScheduler()
    fun cancelDailyRefreshScheduler() = refreshScheduler.cancelDailyRefreshScheduler()
    fun scheduleTestRefresh() = refreshScheduler.scheduleTestRefresh()
    fun scheduleCustomTestRefresh(hour: Int, minute: Int) = refreshScheduler.scheduleCustomTestRefresh(hour, minute)
    fun clearCustomTestTime() = refreshScheduler.clearCustomTestTime()
    fun openExactAlarmSettings(): Boolean = refreshScheduler.openExactAlarmSettings()
    fun needsExactAlarmPermission(): Boolean = refreshScheduler.needsExactAlarmPermission()

    // ==================== Facade methods for SleepWakeScheduler ====================

    fun scheduleSleepWakeAlarms() = sleepWakeScheduler.scheduleSleepWakeAlarms()
    fun cancelSleepWakeAlarms() = sleepWakeScheduler.cancelSleepWakeAlarms()
    fun resetSleepInactivityTimer() = sleepWakeScheduler.resetSleepInactivityTimer()
    fun onSleepSettingsChanged() = sleepWakeScheduler.onSleepSettingsChanged()

    /**
     * Clean up resources.
     */
    fun destroy() {
        DiagnosticBuffer.info("SCREEN", "HaliteScreenController.destroy() called")
        // Mark as destroyed FIRST to stop async callbacks
        isDestroyed = true

        // Cancel pending coroutines
        scope.cancel()

        // Cancel scheduled refresh timers and watchdogs
        refreshScheduler.destroy()
        sleepWakeScheduler.destroy()
        powerWatchdog.destroy()

        if (_screenDimmer != null) {
            DiagnosticBuffer.info("SCREEN", "screenDimmer DESTROYED in destroy()")
        }
        _screenDimmer?.destroy()
        _screenDimmer = null
        motionWakeManager?.destroy()
        motionWakeManager = null
        notifyMotionWakeCameraState()
        destroyRtspFaceDetector()
        // Cleanup return-to-home timer
        cancelReturnHomeTimer()
        returnHomeRunnable = null
    }

    // ==================== Memory Status ====================

    /**
     * Get a status string for memory diagnostics.
     * Shows screensaver state and mode.
     */
    fun getMemoryStatus(): String {
        val mode = halitePrefs.screensaver.screensaverMode
        val state = when {
            nativeScreensaverActive -> "active"
            else -> "idle"
        }
        return "state=$state mode=$mode"
    }
}

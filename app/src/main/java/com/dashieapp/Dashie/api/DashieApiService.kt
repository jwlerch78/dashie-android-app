package com.dashieapp.Dashie.api

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.dashieapp.Dashie.MainActivity
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.edition.brandName
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog

/**
 * Foreground service hosting the Fully Kiosk-compatible REST API server.
 *
 * This service keeps the API server running even when the app is in the background,
 * allowing Home Assistant to continuously poll for device state and send commands.
 *
 * The service runs on port 2323 and implements core Fully Kiosk Browser REST API
 * endpoints for compatibility with the HA Fully Kiosk integration.
 */
class DashieApiService : Service() {

    companion object {
        private const val TAG = "DashieApiService"
        private const val NOTIFICATION_CHANNEL_ID = "dashie_lite_api"
        private const val NOTIFICATION_ID = 2323

        // Intent actions
        const val ACTION_SCREEN_ON = "com.dashieapp.Dashie.SCREEN_ON"
        const val ACTION_SCREEN_OFF = "com.dashieapp.Dashie.SCREEN_OFF"
        const val ACTION_LOAD_URL = "com.dashieapp.Dashie.LOAD_URL"
        const val ACTION_LOAD_START_URL = "com.dashieapp.Dashie.LOAD_START_URL"
        const val ACTION_RESTART_APP = "com.dashieapp.Dashie.RESTART_APP"
        const val ACTION_REBOOT_DEVICE = "com.dashieapp.Dashie.REBOOT_DEVICE"
        const val ACTION_TO_FOREGROUND = "com.dashieapp.Dashie.TO_FOREGROUND"
        const val EXTRA_URL = "url"

        // Minimum time between OOM recovery relaunches to prevent restart loops
        private const val OOM_RELAUNCH_COOLDOWN_MS = 60_000L
        private const val PREF_LAST_OOM_RELAUNCH = "last_oom_relaunch_time"
    }

    private var apiServer: DashieApiServer? = null

    /** Whether the API server failed to bind its port (e.g., port already in use) */
    var serverBindFailed: Boolean = false
        private set

    /** The port the API server is configured to use */
    var serverPort: Int = DashieApiServer.DEFAULT_PORT
        private set

    internal var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Long-lived PARTIAL_WAKE_LOCK held whenever a camera consumer (RTSP or
     * motion-wake camera/face) is active. Without this, Android Doze can
     * suspend the camera capture loop while the activity is backgrounded
     * (screen off, lockNow), causing motion/face wake to silently stop firing.
     *
     * Acquired/released in lockstep with the camera FGS-type bit in
     * applyForegroundServiceType(). Separate from the transient `wakeLock`
     * above (which is acquired for short-duration wake operations).
     */
    private var motionWakeLock: PowerManager.WakeLock? = null
    private var screenOnReceiver: BroadcastReceiver? = null

    // Binder for MainActivity to communicate with service
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): DashieApiService = this@DashieApiService
    }

    // Callbacks from MainActivity
    var onScreenOnRequested: (() -> Unit)? = null
    var onScreenOffRequested: ((String?) -> Unit)? = null  // method: "overlay", "hardware", or null
    var onLoadUrlRequested: ((String) -> Unit)? = null
    var onLoadStartUrlRequested: (() -> Unit)? = null
    var onRestartAppRequested: (() -> Unit)? = null
    var onRebootDeviceRequested: (() -> Unit)? = null
    var onToForegroundRequested: (() -> Unit)? = null
    var getVolumeCallback: ((Int) -> Int)? = null
    var setVolumeCallback: ((Int, Int) -> Unit)? = null
    var getBrightnessCallback: (() -> Int)? = null
    var setBrightnessCallback: ((Int) -> Unit)? = null
    var getAmbientLightCallback: (() -> Int)? = null
    var isScreenOnCallback: (() -> Boolean)? = null
    var getCurrentUrlCallback: (() -> String)? = null
    var getStartUrlCallback: (() -> String)? = null
    var setStartUrlCallback: ((String) -> Unit)? = null
    var speakTextCallback: ((String) -> Unit)? = null
    var stopSpeakingCallback: (() -> Unit)? = null

    // Screensaver callbacks
    var startScreensaverCallback: (() -> Unit)? = null
    var stopScreensaverCallback: (() -> Unit)? = null
    var isInScreensaverCallback: (() -> Boolean)? = null

    // Audio playback callbacks
    var playSoundCallback: ((String) -> Unit)? = null
    var stopSoundCallback: (() -> Unit)? = null
    var pauseSoundCallback: (() -> Unit)? = null
    var resumeSoundCallback: (() -> Unit)? = null
    var seekSoundCallback: ((Int) -> Unit)? = null
    var muteAudioCallback: ((Boolean) -> Unit)? = null
    var isMutedCallback: (() -> Boolean)? = null
    var getAudioPositionCallback: (() -> Int)? = null
    var getTrackPositionCallback: (() -> Int)? = null
    var getAudioDurationCallback: (() -> Int)? = null
    var getSoundUrlPlayingCallback: (() -> String)? = null
    var isSoundPausedCallback: (() -> Boolean)? = null

    // Player identity callback (pushed by Music Assistant provider)
    var setPlayerIdCallback: ((String, String) -> Unit)? = null

    // Media metadata callbacks (pushed by Music Assistant)
    var setMediaInfoCallback: ((String, String, String, String, Int, String, String) -> Unit)? = null
    var getMediaTitleCallback: (() -> String)? = null
    var getMediaArtistCallback: (() -> String)? = null
    var getMediaAlbumCallback: (() -> String)? = null
    var getMediaImageUrlCallback: (() -> String)? = null

    // App launcher callback
    var startApplicationCallback: ((String) -> Boolean)? = null

    // UI callbacks
    var showOverlayMessageCallback: ((String, Int) -> Unit)? = null

    // Motion callback
    var triggerMotionCallback: (() -> Unit)? = null

    // WebView callbacks
    var clearCacheCallback: (() -> Unit)? = null
    var clearWebstorageCallback: (() -> Unit)? = null
    var refreshWebViewCallback: ((callback: (Boolean) -> Unit) -> Unit)? = null

    // Memory diagnostics callback
    var getAppMemoryMbCallback: (() -> Int)? = null

    // Camera callbacks
    var getCamshotCallback: ((callback: (ByteArray?) -> Unit) -> Unit)? = null

    // Screenshot callback (captures the app's current screen)
    var getScreenshotCallback: ((callback: (ByteArray?) -> Unit) -> Unit)? = null

    // Kiosk lock callbacks
    var lockKioskCallback: (() -> Unit)? = null
    var unlockKioskCallback: (() -> Unit)? = null
    var isKioskLockedCallback: (() -> Boolean)? = null

    // New lock state callbacks (more granular)
    var isLockAppExitCallback: (() -> Boolean)? = null
    var isLockSettingsCallback: (() -> Boolean)? = null

    // PIN management callbacks
    var setPinCallback: ((String) -> Boolean)? = null
    var clearPinCallback: (() -> Unit)? = null
    var hasPinSetCallback: (() -> Boolean)? = null
    var verifyPinCallback: ((String) -> Boolean)? = null
    var getStoredPinCallback: (() -> String)? = null

    // RTSP streaming callbacks
    var startRtspStreamCallback: (() -> Boolean)? = null
    var stopRtspStreamCallback: (() -> Unit)? = null
    var isRtspStreamRunningCallback: (() -> Boolean)? = null
    var getRtspStreamUrlCallback: (() -> String)? = null
    var getRtspClientCountCallback: (() -> Int)? = null
    var hasRtspFailedCallback: (() -> Boolean)? = null
    var getRtspFailureReasonCallback: (() -> String?)? = null

    // RTSP configuration callbacks
    var setRtspResolutionCallback: ((Int, Int) -> Unit)? = null
    var setRtspFpsCallback: ((Int) -> Unit)? = null
    var setRtspBitrateCallback: ((Int) -> Unit)? = null
    var getRtspConfigCallback: (() -> RtspConfig)? = null

    // Dark mode callbacks
    var isDarkModeCallback: (() -> Boolean)? = null
    var setDarkModeCallback: ((Boolean) -> Boolean)? = null
    var canControlDarkModeCallback: (() -> Boolean)? = null

    // Screen off method callbacks
    var getScreenOffMethodCallback: (() -> String)? = null
    var setScreenOffMethodCallback: ((String) -> Boolean)? = null

    // Screensaver settings callbacks
    var getScreensaverModeCallback: (() -> String)? = null
    var setScreensaverModeCallback: ((String) -> Boolean)? = null
    var getPhotoSourceCallback: (() -> String)? = null
    var setPhotoSourceCallback: ((String) -> Boolean)? = null
    var getHaMediaFolderCallback: (() -> String)? = null
    var setHaMediaFolderCallback: ((String) -> Boolean)? = null

    // Display/UI settings callbacks (getters)
    var isHideSidebarCallback: (() -> Boolean)? = null
    var isHideHeaderCallback: (() -> Boolean)? = null
    var isKeepScreenOnCallback: (() -> Boolean)? = null
    var isStartOnBootCallback: (() -> Boolean)? = null
    var isAutoBrightnessCallback: (() -> Boolean)? = null
    var getMotionWakeModeCallback: (() -> String)? = null
    var getTextScalingCallback: (() -> Int)? = null

    // RTSP settings callbacks (getters)
    var isRtspEnabledCallback: (() -> Boolean)? = null
    var isRtspSoftwareEncodingCallback: (() -> Boolean)? = null

    // Display/UI settings callbacks (setters)
    var setHideSidebarCallback: ((Boolean) -> Boolean)? = null
    var setHideHeaderCallback: ((Boolean) -> Boolean)? = null
    var setKeepScreenOnCallback: ((Boolean) -> Boolean)? = null
    var setStartOnBootCallback: ((Boolean) -> Boolean)? = null
    var setAutoBrightnessCallback: ((Boolean) -> Boolean)? = null
    var setMotionWakeModeCallback: ((String) -> Boolean)? = null
    var setTextScalingCallback: ((Int) -> Boolean)? = null
    var setRtspEnabledCallback: ((Boolean) -> Boolean)? = null
    var setRtspSoftwareEncodingCallback: ((Boolean) -> Boolean)? = null

    // Timer control callbacks
    var createTimerCallback: ((Int, String?) -> Boolean)? = null
    var cancelTimerCallback: ((String?) -> Boolean)? = null

    // Voice command injection callback (for testing - bypasses wake word + STT)
    var processVoiceCommandCallback: ((String, (Boolean, String?) -> Unit) -> Unit)? = null

    // STT benchmark callback (dev harness — tools/stt-bench)
    var sttBenchCallback: ((String, String, Long, (org.json.JSONObject) -> Unit) -> Unit)? = null
    // STT bench RECORD (dev harness) — clip name, durationMs, reply. Captures the room to a WAV
    // in the bench corpus dir so a clip can be made on the device under test.
    var sttBenchRecordCallback: ((String, Long, (org.json.JSONObject) -> Unit) -> Unit)? = null
    // HA-Assist turn bench (validation matrix R5/R6) — clip, timeoutMs, reply.
    var haAssistBenchCallback: ((String, Long, Boolean, (org.json.JSONObject) -> Unit) -> Unit)? = null

    // Device identification callback
    var getDeviceNameCallback: (() -> String)? = null

    // HA sensor detection state
    var isMotionDetectedCallback: (() -> Boolean)? = null
    var isFaceDetectedCallback: (() -> Boolean)? = null
    var isMotionDetectionEnabledCallback: (() -> Boolean)? = null
    var isFaceDetectionEnabledCallback: (() -> Boolean)? = null

    // MQTT state
    var isMqttEnabledCallback: (() -> Boolean)? = null
    var getMqttBaseTopicCallback: (() -> String)? = null

    // Video feed triggers (pushed by HA integration)
    var onVideoFeedTriggerCallback: ((Map<String, String>) -> Unit)? = null
    var getVideoFeedTriggerEntitiesCallback: (() -> List<String>)? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DashieApiService created")
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "DashieApiService onStartCommand (intent=${intent != null}, flags=$flags)")
        // Persist so we can see overnight restarts. A null-intent call means Android restarted us
        // via START_STICKY after a process kill — that's the signal we need to confirm whether
        // Fire OS is honoring our sticky restart after it force-kills the app.
        PersistentLog.info("SERVICE", "onStartCommand(intent=${intent != null}, flags=$flags, startId=$startId)")

        // MUST call startForeground() immediately — Android enforces a ~5s deadline after
        // startForegroundService(). Do this BEFORE any other work (including OOM recovery).
        // Base type is SPECIAL_USE only; RTSP promotes it to include camera/microphone on
        // demand via applyForegroundServiceType() — see that method for the full rationale.
        //
        // Restore the camera FGS type if motion-wake-camera was active before a
        // START_STICKY restart (system kill / OOM / doze reap). Without this, the
        // service would come back with includeCamera=false and the OEM would
        // revoke camera access ~5s later — killing face/motion wake until the
        // user restarts the app. DashieServiceManager.setMotionWakeCameraActive()
        // persists this flag. RTSP recovers via its own restart-on-disconnect path,
        // so we only need motion-wake here.
        val restoreCamera = try {
            HalitePreferences(this).sleep.motionWakeCameraActive
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read motionWakeCameraActive: ${e.message}")
            false
        }
        if (restoreCamera) {
            PersistentLog.info("SERVICE", "Restoring camera FGS type — motionWakeCameraActive was true")
        }
        applyForegroundServiceType(includeCamera = restoreCamera)

        // Detect post-OOM-kill restart: Android restarts START_STICKY services with null intent.
        // When this happens, relaunch MainActivity so the kiosk comes back automatically.
        if (intent == null) {
            Log.w(TAG, "Null intent — service restarted after system kill. Attempting to relaunch app.")
            PersistentLog.warn("SERVICE", "START_STICKY restart detected (null intent) — relaunching MainActivity")
            relaunchAfterOomKill()
        }

        // Start the REST API HTTP server if enabled AND not already running.
        // The service itself may be running for other reasons (RTSP streaming,
        // motion-wake-camera holding the camera FGS type) without the user
        // having enabled the API — in that case the HTTP server stays offline.
        if (apiServer == null && HalitePreferences(this).connection.apiEnabled) {
            startApiServer()
        }

        // Register for system SCREEN_ON broadcast to handle power button wake
        // from hardware screen off mode (lockNow)
        registerScreenOnReceiver()

        return START_STICKY
    }

    /**
     * (Re)apply the foreground service type.
     *
     * The base type is SPECIAL_USE — always valid, needs no runtime permission. The
     * REST API server itself touches no camera or microphone, so the service must
     * never *require* those types to start: a camera/microphone FGS type whose
     * runtime permission isn't granted makes the whole startForeground() call throw
     * (Android 14+), which would take the API server down and HA loses the device.
     *
     * When RTSP camera streaming is active, the camera (and microphone) types are
     * added — but only if the matching runtime permission is granted — so Samsung's
     * camera policy won't revoke camera access while the app is backgrounded
     * (screensaver, hardware screen-off). DashieServiceManager calls this with
     * includeCamera=true/false as the RTSP camera server starts/stops.
     */
    fun applyForegroundServiceType(includeCamera: Boolean) {
        // Manage the long-lived camera-keepalive wake lock in lockstep with the
        // camera FGS bit. Doing this before the version branch covers both
        // pre/post API 34 paths.
        if (includeCamera) acquireMotionWakeLock() else releaseMotionWakeLock()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Pre-API-34: foreground service types are not used.
            try {
                startForeground(NOTIFICATION_ID, createNotification())
            } catch (e: Exception) {
                Log.e(TAG, "startForeground (untyped) failed: ${e.message}")
            }
            return
        }
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        // Add SYSTEM_EXEMPTED only when we genuinely qualify. AOSP permits
        // FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED for specific reasons only —
        // being the Device Owner, or being on the power allowlist (battery-opt
        // exempt). Device Admin alone is NOT a valid reason: claiming the bit
        // without qualifying makes startForeground() throw, silently dropping us
        // back to SPECIAL_USE and losing background-camera rights. The bit is
        // what lets the camera FGS survive Android 14+/OEM background-camera
        // restrictions after lockNow() backgrounds the app.
        val isDeviceOwner = com.dashieapp.Dashie.halite.DeviceAdminHelper.isDeviceOwnerApp(this)
        val isBatteryExempt = com.dashieapp.Dashie.halite.BatteryOptimizationHelper.isExempt(this)
        if (isDeviceOwner || isBatteryExempt) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        }
        if (includeCamera) {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                Log.w(TAG, "RTSP active but CAMERA permission not granted — camera FGS type omitted")
                PersistentLog.warn("FGS", "camera FGS type OMITTED — CAMERA permission not granted (includeCamera=true)")
            }
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        try {
            startForeground(NOTIFICATION_ID, createNotification(), type)
            Log.i(TAG, "Foreground service type applied (includeCamera=$includeCamera, typeBitmask=$type)")
            val hasCameraType = (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) != 0
            val hasSysExempt = (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED) != 0
            PersistentLog.info(
                "FGS",
                "type applied: includeCamera=$includeCamera camera=$hasCameraType " +
                    "sysExempt=$hasSysExempt (deviceOwner=$isDeviceOwner batteryExempt=$isBatteryExempt) " +
                    "bitmask=$type"
            )
        } catch (e: Exception) {
            // Never let an FGS-type failure take down the API server. Fall back to
            // SPECIAL_USE-only (needs no runtime permission). Do NOT stopSelf() — the
            // service is also bound by DashieServiceManager and survives regardless.
            Log.e(TAG, "applyForegroundServiceType(includeCamera=$includeCamera) failed: ${e.message}")
            PersistentLog.error("FGS", "applyForegroundServiceType(includeCamera=$includeCamera) FAILED: ${e.message}")
            if (type != ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) {
                try {
                    startForeground(
                        NOTIFICATION_ID, createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } catch (e2: Exception) {
                    Log.e(TAG, "Fallback to SPECIAL_USE foreground also failed: ${e2.message}")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    /**
     * Acquire the long-lived PARTIAL_WAKE_LOCK that keeps the CPU running while
     * a camera consumer (RTSP or motion-wake) is active. Idempotent: re-acquiring
     * while held is a no-op (we use isHeld to gate). No timeout — the lock lives
     * until the matching camera consumer goes inactive (releaseMotionWakeLock)
     * or the service is destroyed.
     */
    private fun acquireMotionWakeLock() {
        val pm = powerManager ?: return
        val existing = motionWakeLock
        if (existing != null && existing.isHeld) return
        try {
            val lock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Dashie:MotionWakeCamera"
            )
            lock.setReferenceCounted(false)
            lock.acquire()
            motionWakeLock = lock
            Log.i(TAG, "Motion-wake PARTIAL_WAKE_LOCK acquired")
            PersistentLog.info("SERVICE", "Motion-wake wake lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire motion-wake wake lock: ${e.message}")
        }
    }

    private fun releaseMotionWakeLock() {
        val lock = motionWakeLock ?: return
        try {
            if (lock.isHeld) {
                lock.release()
                Log.i(TAG, "Motion-wake PARTIAL_WAKE_LOCK released")
                PersistentLog.info("SERVICE", "Motion-wake wake lock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release motion-wake wake lock: ${e.message}")
        }
        motionWakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopApiServer()
        unregisterScreenOnReceiver()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        releaseMotionWakeLock()
        Log.i(TAG, "DashieApiService destroyed")
    }

    private fun startApiServer() {
        try {
            // Get password and port from appropriate preferences based on build config
            val password: String
            val port: Int
            if (com.dashieapp.Dashie.BuildConfig.ALLOW_URL_CONFIG) {
                // Halite/Dashie Lite - use HalitePreferences
                val halitePrefs = HalitePreferences(this)
                password = halitePrefs.connection.apiPassword
                port = halitePrefs.connection.apiPort
                Log.i(TAG, "🔧 Starting API server with port from HalitePreferences: $port")
            } else {
                // Standard Dashie - use DashieApiPreferences (no port setting, use default)
                password = com.dashieapp.Dashie.api.DashieApiPreferences(this).apiPassword
                port = DashieApiServer.DEFAULT_PORT
                Log.i(TAG, "🔧 Starting API server with default port: $port")
            }

            apiServer = DashieApiServer(
                context = this,
                password = password,
                port = port,
                callbacks = DashieApiCallbacksImpl(this@DashieApiService)
            )

            serverPort = port
            apiServer?.start()
            serverBindFailed = false
            Log.i(TAG, "API server started on port $port")

        } catch (e: Exception) {
            serverBindFailed = true
            Log.e(TAG, "Failed to start API server: ${e.message}", e)
        }
    }

    private fun stopApiServer() {
        try {
            apiServer?.stop()
            apiServer = null
            serverBindFailed = false
            Log.i(TAG, "API server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping API server: ${e.message}", e)
        }
    }

    /**
     * Register a BroadcastReceiver for system SCREEN_ON events.
     * This allows us to detect when the power button is pressed to wake from hardware screen off.
     */
    private fun registerScreenOnReceiver() {
        if (screenOnReceiver != null) return  // Already registered

        screenOnReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_ON) {
                    handleSystemScreenOn()
                }
            }
        }

        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOnReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOnReceiver, filter)
        }
        Log.d(TAG, "Registered SCREEN_ON broadcast receiver")
    }

    /**
     * Unregister the SCREEN_ON BroadcastReceiver.
     */
    private fun unregisterScreenOnReceiver() {
        screenOnReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "Unregistered SCREEN_ON broadcast receiver")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering screen receiver: ${e.message}")
            }
        }
        screenOnReceiver = null
    }

    /**
     * Handle system SCREEN_ON broadcast.
     * If we were in Screen Off mode (hardware screen off via lockNow), this is our
     * signal that the user pressed the power button. We should dismiss the keyguard
     * and bring Dashie back to the foreground.
     */
    @Suppress("DEPRECATION")
    private fun handleSystemScreenOn() {
        val prefs = HalitePreferences(this)
        if (!prefs.sleep.wasInScreenOffMode) {
            Log.d(TAG, "SCREEN_ON received but not in Screen Off mode - ignoring")
            return
        }

        Log.i(TAG, "🔆 SCREEN_ON received while in Screen Off mode - waking Dashie")

        // Clear the flag so we don't keep responding
        prefs.sleep.wasInScreenOffMode = false

        // Notify MainActivity to call screenOn() on the ScreenDimmer
        onScreenOnRequested?.invoke()

        // Dismiss keyguard (lock screen) if present
        try {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

            // For older devices, use deprecated keyguard lock
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                val keyguardLock = keyguardManager.newKeyguardLock("DashieLite")
                keyguardLock.disableKeyguard()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dismiss keyguard: ${e.message}")
        }

        // Bring Dashie to foreground after a brief delay for keyguard dismissal
        Handler(Looper.getMainLooper()).postDelayed({
            bringToForeground()
        }, 200)
    }

    /**
     * Wake the screen using PowerManager wake lock
     */
    @Suppress("DEPRECATION")
    internal fun wakeScreen() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = powerManager?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "DashieLite:ScreenWake"
            )
            wakeLock?.acquire(3000) // 3 second timeout
            Log.d(TAG, "Screen wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wake screen: ${e.message}", e)
        }
    }

    /**
     * Relaunch MainActivity after an OOM kill.
     * Called when the service is restarted by Android with a null intent (START_STICKY).
     * Uses a cooldown to prevent rapid restart loops if the system keeps killing us.
     */
    private fun relaunchAfterOomKill() {
        // Guard: MainActivity already came back on its own — relaunching with
        // CLEAR_TASK would destroy the healthy instance and double-boot the app
        // (registry re-init, shell reload, token-injection loss). Happens whenever
        // the system auto-restores the task after the kill, e.g. app-op change
        // kills ("REQUEST_INSTALL_PACKAGES changed." during onboarding).
        if (MainActivity.isInstanceAlive) {
            Log.i(TAG, "OOM recovery skipped — MainActivity already alive")
            PersistentLog.info("SERVICE", "OOM recovery skipped — MainActivity already alive")
            return
        }

        // Guard: don't treat benign, self-recovering kills as crashes to recover
        // from. App-op grant changes (description "<OP> changed.") force-restart
        // the app and the system restores the task itself; app upgrades and
        // user-initiated stops aren't crashes at all. Genuine overnight reaps
        // (Fire OS "excessive cpu" / "empty" kills, REASON_LOW_MEMORY) don't
        // match these and still recover.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val exit = com.dashieapp.Dashie.halite.diagnostics.ExitReasonAnalyzer
                .analyzePreviousExit(this, 0)
            val benign = when (exit) {
                is com.dashieapp.Dashie.halite.diagnostics.ExitReasonAnalyzer.ExitAnalysis.AppUpgrade,
                is com.dashieapp.Dashie.halite.diagnostics.ExitReasonAnalyzer.ExitAnalysis.UserStopped -> true
                is com.dashieapp.Dashie.halite.diagnostics.ExitReasonAnalyzer.ExitAnalysis.Other ->
                    exit.description.endsWith(" changed.")  // app-op grant change, e.g. REQUEST_INSTALL_PACKAGES
                else -> false
            }
            if (benign) {
                Log.i(TAG, "OOM recovery skipped — benign exit (${exit.javaClass.simpleName})")
                PersistentLog.info("SERVICE", "OOM recovery skipped — benign exit: $exit")
                return
            }
        }

        // Check if user has enabled auto-reload and granted overlay permission
        val perfPrefs = com.dashieapp.Dashie.halite.preferences.PerformancePreferences(this)
        if (!perfPrefs.autoReloadOnCrash) {
            Log.i(TAG, "OOM recovery skipped — auto reload on forced shutdown is disabled")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "OOM recovery skipped — SYSTEM_ALERT_WINDOW permission not granted")
            return
        }

        val prefs = getSharedPreferences("dashie_service_prefs", Context.MODE_PRIVATE)
        val lastRelaunch = prefs.getLong(PREF_LAST_OOM_RELAUNCH, 0)
        val now = System.currentTimeMillis()

        if (now - lastRelaunch < OOM_RELAUNCH_COOLDOWN_MS) {
            Log.w(TAG, "OOM relaunch skipped — cooldown active (last relaunch ${(now - lastRelaunch) / 1000}s ago)")
            return
        }

        prefs.edit().putLong(PREF_LAST_OOM_RELAUNCH, now).commit()
        Log.i(TAG, "OOM recovery: waking screen and relaunching MainActivity...")

        // Strategy: wake the screen first (foreground services can do this), then start the
        // activity. With the screen on, the foreground service gets a background activity
        // start exemption on most Android versions.
        Handler(Looper.getMainLooper()).postDelayed({
            // Step 1: Wake the screen
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock?.let { if (it.isHeld) it.release() }
                @Suppress("DEPRECATION")
                wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "DashieLite:OomRecoveryWake"
                )
                wakeLock?.acquire(10_000)  // 10s timeout
                Log.i(TAG, "OOM recovery: screen wake lock acquired")
            } catch (e: Exception) {
                Log.e(TAG, "OOM recovery: failed to wake screen: ${e.message}")
            }

            // Step 2: Launch activity after brief delay for screen to come on
            Handler(Looper.getMainLooper()).postDelayed({
                // Re-check liveness: the system/keep-alive path may have restored
                // the activity during the wake+launch delays.
                if (MainActivity.isInstanceAlive) {
                    Log.i(TAG, "OOM recovery aborted — MainActivity came back during delay")
                    PersistentLog.info("SERVICE", "OOM recovery aborted — MainActivity came back during delay")
                    return@postDelayed
                }
                try {
                    val savedUrl = perfPrefs.lastWebViewUrl
                    val launchIntent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra("from_oom_recovery", true)
                        if (savedUrl.isNotEmpty()) {
                            putExtra("oom_recovery_url", savedUrl)
                        }
                    }
                    startActivity(launchIntent)
                    Log.i(TAG, "OOM recovery: startActivity called (savedUrl=${savedUrl.take(60)})")
                } catch (e: Exception) {
                    Log.e(TAG, "OOM recovery: startActivity failed (${e.message}), trying notification")
                    launchOomRecoveryNotification()
                }
            }, 500)
        }, 2000)
    }

    /**
     * Launch MainActivity via full-screen notification after OOM kill.
     * Uses CLEAR_TASK to create a fresh activity (the old one is dead).
     */
    private fun launchOomRecoveryNotification() {
        val perfPrefs = com.dashieapp.Dashie.halite.preferences.PerformancePreferences(this)
        val savedUrl = perfPrefs.lastWebViewUrl
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("from_oom_recovery", true)
            if (savedUrl.isNotEmpty()) {
                putExtra("oom_recovery_url", savedUrl)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "dashie_foreground_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "App Recovery",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Used to recover ${brandName()} after system kill"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        // High-priority heads-up notification. setFullScreenIntent removed when dropping
        // USE_FULL_SCREEN_INTENT permission for Play Store (restricted on Android 14+).
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle(brandName())
                .setContentText("Recovering dashboard...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(brandName())
                .setContentText("Recovering dashboard...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        val notificationId = 9998  // Different from bringToForeground's 9999
        notificationManager.notify(notificationId, notification)
        Log.i(TAG, "OOM recovery: heads-up notification posted")

        // Cancel notification after activity should have started
        Handler(Looper.getMainLooper()).postDelayed({
            notificationManager.cancel(notificationId)
        }, 5000)
    }

    /**
     * Bring the app to foreground.
     * Uses multiple strategies to work around Android's background activity start restrictions.
     *
     * Strategy order:
     * 1. moveTaskToFront() - works if we have REORDER_TASKS or the app has recent task
     * 2. Direct startActivity from main thread with foreground service context
     * 3. Full-screen notification as fallback
     */
    @Suppress("DEPRECATION")
    internal fun bringToForeground() {
        Log.i(TAG, "bringToForeground() called")

        // Run on main thread to have proper context for activity operations
        android.os.Handler(mainLooper).post {
            bringToForegroundInternal()
        }
    }

    private fun bringToForegroundInternal() {
        try {
            // First, wake the screen if it's off
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                Log.d(TAG, "Screen is off, acquiring wake lock")
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "DashieLite:ToForegroundWake"
                )
                wakeLock?.acquire(5000)
            }

            // Strategy 1: Try moveTaskToFront() - this works when we have the task ID
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val appTasks = am.appTasks
            Log.d(TAG, "bringToForeground: Found ${appTasks.size} app tasks")

            if (appTasks.isNotEmpty()) {
                try {
                    // Move task to front - no need to access taskInfo fields which have API compatibility issues
                    Log.d(TAG, "bringToForeground: Moving app task to front")
                    appTasks[0].moveToFront()
                    Log.i(TAG, "✓ bringToForeground: moveTaskToFront succeeded")
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "moveTaskToFront failed: ${e.message}")
                }
            }

            // Strategy 2: Try direct activity start with special flags
            // Since we're a foreground service on the main thread, we may have exemption
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // FLAG_ACTIVITY_BROUGHT_TO_FRONT is set by the system, not us
                putExtra("from_to_foreground", true)
            }

            try {
                Log.d(TAG, "bringToForeground: Attempting direct activity start...")
                startActivity(launchIntent)
                Log.i(TAG, "✓ bringToForeground: Direct activity start succeeded")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Direct activity start failed: ${e.message}")
            }

            // Strategy 3: Full-screen notification as fallback
            Log.d(TAG, "bringToForeground: Using full-screen notification fallback...")
            launchWithFullScreenNotification()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to bring to foreground: ${e.message}", e)
        }
    }

    private fun launchWithFullScreenNotification() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("from_to_foreground", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create a high-priority notification channel for full-screen intents
        val channelId = "dashie_foreground_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Bring to Foreground",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Used to bring ${brandName()} to foreground"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        // High-priority heads-up notification. setFullScreenIntent removed when dropping
        // USE_FULL_SCREEN_INTENT permission for Play Store (restricted on Android 14+).
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle(brandName())
                .setContentText("Returning to dashboard...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(brandName())
                .setContentText("Returning to dashboard...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        val notificationId = 9999
        notificationManager.notify(notificationId, notification)
        Log.i(TAG, "bringToForeground: Heads-up notification posted")

        // Cancel after delay
        android.os.Handler(mainLooper).postDelayed({
            notificationManager.cancel(notificationId)
        }, 2000)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "${brandName()} Kiosk API",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Home Assistant integration API running"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("${brandName()} Kiosk API")
            .setContentText("Home Assistant integration active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Check if the API server is running
     */
    fun isServerRunning(): Boolean = apiServer?.isAlive == true
}

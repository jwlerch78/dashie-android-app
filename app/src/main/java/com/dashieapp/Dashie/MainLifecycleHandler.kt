package com.dashieapp.Dashie

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.DailyRefreshReceiver
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.util.DeviceInfoHelper

/**
 * Handles lifecycle method bodies and app control operations for MainActivity.
 *
 * Extracted from MainActivity to reduce its size. MainActivity keeps thin overrides
 * that call super + delegate to this handler.
 *
 * @param activity The activity context
 * @param coordinator The coordinator holding component references
 * @param callbacks Callbacks for accessing components not on the coordinator
 */
class MainLifecycleHandler(
    private val activity: ComponentActivity,
    private val coordinator: MainActivityCoordinator,
    private val callbacks: Callbacks
) {
    companion object {
        private const val TAG = "DashieAuth"
    }

    interface Callbacks {
        fun getKioskController(): MainKioskController?
        fun getVoiceSetup(): MainVoiceSetup?
        fun getMemoryManager(): MainMemoryManager?
        fun getServiceManager(): MainServiceManager?
        fun getUrlHandler(): MainUrlHandler
        fun getDarkModeHandler(): MainDarkModeHandler?
        fun isTabletDevice(): Boolean
    }

    // Convenience accessors from coordinator
    private val halitePrefs get() = coordinator.halitePrefs
    private val webView get() = coordinator.webView
    private val haliteScreenController get() = coordinator.haliteScreenController
    private val dialogHost get() = coordinator.dialogHost
    private val haliteVoiceController get() = coordinator.haliteVoiceController
    private val permissionDelegate get() = coordinator.permissionDelegate

    // ============================================================
    // Lifecycle Methods
    // ============================================================

    /**
     * onResume body - handles URL checks, immersive mode, voice resume,
     * keyboard management, and permission validation.
     */
    fun onResume() {
        if (BuildConfig.ALLOW_URL_CONFIG) {
            // Capture keyguard state at resume so we can diagnose
            // lockscreen-on-wake reports (Matt Stein 2026-05-04). Three
            // patterns to look for in this log:
            //   keyguardLocked=true → Dashie came up over the keyguard ✓
            //   keyguardLocked=false + secure=true → Android dismissed
            //     keyguard before our resume; setShowWhenLocked not
            //     honored (OEM-specific quirk, possibly Samsung)
            //   secure=false → no PIN, no keyguard to bypass
            val keyguardState = try {
                val km = activity.getSystemService(android.content.Context.KEYGUARD_SERVICE)
                    as? android.app.KeyguardManager
                if (km == null) "(no KeyguardManager)"
                else "locked=${km.isKeyguardLocked} secure=${km.isKeyguardSecure}"
            } catch (e: Throwable) {
                "(lookup failed: ${e.javaClass.simpleName})"
            }
            PersistentLog.info("LIFECYCLE", "onResume() keyguard=$keyguardState")
            // If we just came back from a pause, log which app(s) had foreground during the gap.
            // Used to confirm Fire TV idle-management activities (e.g. Still Watching) as
            // focus-stealers during overnight idle. Returns the package name of a known
            // Fire TV idle-killer if one was detected, else null.
            val idleKiller = com.dashieapp.Dashie.halite.diagnostics.CrashDiagnosticsCollector
                .logFocusStealersForPriorPause(activity.applicationContext)
            if (idleKiller != null) {
                halitePrefs?.let { prefs ->
                    com.dashieapp.Dashie.halite.diagnostics.FireTvIdleKillDialog
                        .showIfApplicable(activity as androidx.activity.ComponentActivity, prefs, idleKiller)
                }
            }
        }

        // Hide keyboard on resume to prevent it from appearing unexpectedly
        if (BuildConfig.ALLOW_URL_CONFIG) {
            val softInputFlags = android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            activity.window.setSoftInputMode(softInputFlags)

            val isTouchDevice = !DeviceInfoHelper.isFireTV() && !DeviceInfoHelper.isTVDevice(activity)
            if (isTouchDevice) {
                webView.clearFocus()
                activity.findViewById<android.view.View>(android.R.id.content)?.requestFocus()
            }
            hideKeyboard()
        }

        // Restart screensaver timer (was paused in onPause to prevent background triggering)
        if (BuildConfig.ALLOW_URL_CONFIG) {
            haliteScreenController?.onActivityResumed()
        }

        // Re-apply lock to app / immersive mode
        if (BuildConfig.ALLOW_URL_CONFIG) {
            if (halitePrefs?.lock?.lockToApp == true) {
                callbacks.getKioskController()?.startLockToApp()
            } else {
                callbacks.getKioskController()?.enableImmersiveMode(fastHide = false)
            }
        } else if (callbacks.isTabletDevice()) {
            callbacks.getKioskController()?.enableImmersiveMode(fastHide = true)
        }

        // For Halite: Check if URL changed while we were away
        if (BuildConfig.ALLOW_URL_CONFIG && halitePrefs != null) {
            // Check if returning from hardware Screen Off mode
            val returningFromScreenOff = haliteScreenController?.screenDimmer?.isScreenOff() == true
            if (returningFromScreenOff) {
                Log.i(TAG, "🔆 onResume: Returning from hardware Screen Off - waking screen")
                haliteScreenController?.screenDimmer?.screenOn()
            }

            haliteScreenController?.clearWakeFlags()

            // Check if returning from an external app screensaver
            val returningFromExternalApp = haliteScreenController?.isExternalAppActive() == true
            if (returningFromExternalApp) {
                Log.i(TAG, "🖼️ Returning from external app screensaver - waking screen")
                haliteScreenController?.onReturnFromExternalApp()
                val isTouchDevice = !DeviceInfoHelper.isFireTV() && !DeviceInfoHelper.isTVDevice(activity)
                if (isTouchDevice) {
                    webView.clearFocus()
                    activity.findViewById<android.view.View>(android.R.id.content)?.requestFocus()
                }
                hideKeyboard()
            }

            // Only check URL changes if NOT returning from external app AND NOT handling a URL intent
            // Skip URL check entirely when Dashie account is linked (WebView is on dashieapp.com, not HA)
            if (!returningFromExternalApp && !callbacks.getUrlHandler().isHandlingUrlIntent()
                && (halitePrefs?.account?.isLinked != true || halitePrefs?.account?.forceKioskMode == true)) {
                // The configured dashboard is HA by default — but on a custom-URL kiosk
                // the dashboard IS the custom URL (determineInitialUrl() loads it directly
                // into the WebView, no shell). Comparing that page against haUrl made every
                // onResume-after-load yank the kiosk back to Home Assistant.
                val conn = halitePrefs!!.connection
                val isCustomUrlKiosk = conn.isCustomUrlKiosk
                val currentUrl = if (isCustomUrlKiosk) conn.customUrl else conn.haUrl
                val webViewUrl = webView.url ?: ""

                val prefsBaseUrl = currentUrl.substringBefore("?").trimEnd('/')
                val webViewBaseUrl = webViewUrl.substringBefore("?").trimEnd('/')

                val webViewIsSubpath = webViewBaseUrl.startsWith("$prefsBaseUrl/")
                val isDashieLitePage = webViewBaseUrl.contains("/dashie-lite.html")
                val isKioskShellPage = webViewBaseUrl.contains("/kiosk-shell.html")

                Log.d(TAG, "🔧 URL check: prefs='$prefsBaseUrl' vs webView='$webViewBaseUrl' (isSubpath=$webViewIsSubpath, isDashieLite=$isDashieLitePage, isKioskShell=$isKioskShellPage, isCustomUrlKiosk=$isCustomUrlKiosk)")

                val isOnAuthPage = webViewUrl.contains("/auth/") || webViewUrl.contains("/login")
                if (webViewBaseUrl.isNotEmpty() &&
                    prefsBaseUrl != webViewBaseUrl &&
                    !webViewIsSubpath &&
                    !isDashieLitePage &&
                    !isKioskShellPage &&
                    currentUrl.isNotEmpty() &&
                    currentUrl != HalitePreferences.DEFAULT_HA_URL &&
                    !isOnAuthPage) {
                    Log.i(TAG, "🔧 Halite: URL changed from $webViewUrl to $currentUrl, reloading")
                    webView.loadUrl(currentUrl)
                }
            }

            checkHaliteVoiceOnResume()
            dialogHost?.continuePermissionSetupIfNeeded()
            permissionDelegate.continueOnboardingPermissionsIfNeeded()
            callbacks.getServiceManager()?.validateApiPermissionsOnResume()
            permissionDelegate.checkBatteryOptimizationOnStartup()
        }
    }

    /**
     * onWindowFocusChanged body - handles immersive mode reapplication,
     * system dialog dismissal for kiosk mode, and keyboard management.
     */
    fun onWindowFocusChanged(hasFocus: Boolean) {
        // Capture keyguard state on every focus change. Unlike onResume, this
        // fires when the Android keyguard appears OVER a setShowWhenLocked
        // activity (no onPause/onResume in that case) — so it's the only
        // reliable hook for the lockscreen-on-wake reports (Jules / Matt Stein).
        // Read: hasFocus=false + keyguard locked=true → keyguard covered us
        // (the bypass didn't hold); hasFocus=true → we're back on top.
        if (BuildConfig.ALLOW_URL_CONFIG) {
            val keyguardState = try {
                val km = activity.getSystemService(android.content.Context.KEYGUARD_SERVICE)
                    as? android.app.KeyguardManager
                if (km == null) "(no KeyguardManager)"
                else "locked=${km.isKeyguardLocked} secure=${km.isKeyguardSecure}"
            } catch (e: Throwable) {
                "(lookup failed: ${e.javaClass.simpleName})"
            }
            val showWhenLocked = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                    val flag = activity.window.attributes.flags and
                        android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    "flagShowWhenLocked=${flag != 0}"
                } else "flagShowWhenLocked=n/a"
            } catch (e: Throwable) { "flagShowWhenLocked=(err)" }
            PersistentLog.info("SCREEN",
                "onWindowFocusChanged hasFocus=$hasFocus keyguard=$keyguardState $showWhenLocked")
        }

        // When Lock to App is enabled and we lose focus, dismiss system dialogs
        if (!hasFocus && BuildConfig.ALLOW_URL_CONFIG && halitePrefs?.lock?.lockToApp == true) {
            try {
                @Suppress("DEPRECATION")
                activity.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                Log.d(TAG, "🔒 Dismissed system dialog (power menu blocked)")
            } catch (e: Exception) {
                Log.w(TAG, "🔒 Failed to dismiss system dialog: ${e.message}")
            }
        }

        val isKbShowing = isKeyboardShowing()
        if (BuildConfig.ALLOW_URL_CONFIG) {
            Log.d("FocusDebug", "onWindowFocusChanged: hasFocus=$hasFocus, keyboard=$isKbShowing")
        }

        // If gaining focus and keyboard is unexpectedly showing, hide it
        if (hasFocus && isKbShowing) {
            val isTouchDevice = BuildConfig.ALLOW_URL_CONFIG &&
                !DeviceInfoHelper.isFireTV() && !DeviceInfoHelper.isTVDevice(activity)
            if (isTouchDevice) {
                webView.clearFocus()
                activity.findViewById<android.view.View>(android.R.id.content)?.requestFocus()
            }
            Handler(Looper.getMainLooper()).postDelayed({
                hideKeyboard()
            }, 100)
        }

        if (hasFocus && !isKbShowing) {
            if (BuildConfig.ALLOW_URL_CONFIG) {
                val useFastHide = halitePrefs?.lock?.lockToApp == true
                Log.d("FocusDebug", "Re-applying immersive mode (fastHide=$useFastHide)")
                callbacks.getKioskController()?.enableImmersiveMode(fastHide = useFastHide)
            } else if (callbacks.isTabletDevice()) {
                callbacks.getKioskController()?.enableImmersiveMode(fastHide = true)
            }
        }
    }

    fun onPause() {
        com.dashieapp.Dashie.halite.diagnostics.CrashDiagnosticsCollector.persistLifecycleEvent(
            activity.applicationContext, "onPause"
        )
        // Mark the start of this pause window so the next onResume can attribute it.
        com.dashieapp.Dashie.halite.diagnostics.CrashDiagnosticsCollector.recordPauseStart(
            activity.applicationContext
        )
        if (BuildConfig.ALLOW_URL_CONFIG) {
            PersistentLog.info("LIFECYCLE", "onPause()")
            val stackTrace = Thread.currentThread().stackTrace
                .drop(2)
                .take(8)
                .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
            PersistentLog.info("LIFECYCLE", "onPause stack: $stackTrace")

            // Pause screensaver timer so it doesn't trigger while app is in background
            haliteScreenController?.onActivityPaused()
        }
    }

    fun onStop() {
        if (BuildConfig.ALLOW_URL_CONFIG) {
            PersistentLog.info("LIFECYCLE", "onStop()")
            com.dashieapp.Dashie.halite.diagnostics.CrashDiagnosticsCollector.persistLifecycleEvent(
                activity.applicationContext, "onStop"
            )
        }
    }

    /**
     * onConfigurationChanged body - handles dark/light mode changes.
     */
    fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        val nightModeFlags = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isNowDark = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
        Log.i(TAG, "🌓 onConfigurationChanged: uiMode night = $isNowDark")

        // Legacy sidebar theme refresh removed
        callbacks.getDarkModeHandler()?.notifyColorSchemeChanged(isNowDark)
    }

    // ============================================================
    // App Control Operations
    // ============================================================

    /**
     * Perform full app restart (kills process).
     * Uses AlarmManager to schedule restart after process exit.
     */
    fun performRestartApp() {
        Log.i(TAG, "🔄 performRestartApp() - restarting app...")
        activity.runOnUiThread {
            try {
                callbacks.getVoiceSetup()?.shutdown()

                val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

                val pendingIntent = android.app.PendingIntent.getActivity(
                    activity,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                val triggerTime = System.currentTimeMillis() + 500

                val canUseExactAlarm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    alarmManager.canScheduleExactAlarms()
                } else {
                    true
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    if (canUseExactAlarm) {
                        alarmManager.setExactAndAllowWhileIdle(
                            android.app.AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                        Log.i(TAG, "✅ Restart scheduled (exact, Doze-compatible), exiting process...")
                    } else {
                        alarmManager.setAndAllowWhileIdle(
                            android.app.AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                        Log.i(TAG, "✅ Restart scheduled (inexact - exact alarm permission not granted), exiting process...")
                    }
                } else {
                    alarmManager.setExact(
                        android.app.AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    Log.i(TAG, "✅ Restart scheduled (exact), exiting process...")
                }
                activity.finishAffinity()
                Runtime.getRuntime().exit(0)
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting app: ${e.message}", e)
            }
        }
    }

    /**
     * Reboot the entire device.
     * Tries device owner API first (no root needed), falls back to PowerManager (needs root).
     */
    fun performRebootDevice() {
        Log.i(TAG, "🔄 performRebootDevice() - rebooting device...")
        activity.runOnUiThread {
            // Try device owner reboot first (API 24+, no root needed)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    if (com.dashieapp.Dashie.halite.DeviceAdminHelper.isDeviceOwnerApp(activity)) {
                        Log.i(TAG, "🔄 Using device owner reboot")
                        com.dashieapp.Dashie.halite.DeviceAdminHelper.dpm(activity)?.reboot(
                            com.dashieapp.Dashie.halite.DeviceAdminHelper.component(activity)
                        )
                        return@runOnUiThread
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "🔄 Device owner reboot failed: ${e.message}")
                }
            }

            // Fall back to PowerManager.reboot (requires root/system app)
            try {
                val powerManager = activity.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                powerManager.reboot(null)
                Log.i(TAG, "✅ Device reboot initiated...")
            } catch (e: SecurityException) {
                Log.e(TAG, "❌ Reboot failed: requires device owner or root", e)
                Toast.makeText(
                    activity,
                    "Reboot failed: requires device owner setup or root",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Reboot failed: ${e.message}", e)
                Toast.makeText(
                    activity,
                    "Reboot failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Perform soft restart (recreates activity without killing process).
     */
    fun performSoftRestartApp() {
        Log.i(TAG, "🔄 performSoftRestartApp() - recreating activity...")
        activity.runOnUiThread {
            try {
                callbacks.getVoiceSetup()?.shutdown()
                activity.recreate()
                Log.i(TAG, "✅ Activity recreated")
            } catch (e: Exception) {
                Log.e(TAG, "Error soft restarting app: ${e.message}", e)
            }
        }
    }

    // ============================================================
    // Helper Methods
    // ============================================================

    /**
     * Handle daily refresh intent triggered by AlarmManager.
     */
    fun handleDailyRefreshIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(DailyRefreshReceiver.EXTRA_TRIGGER_DAILY_REFRESH, false) == true) {
            Log.i(TAG, "📅 Daily refresh intent received - triggering refresh")
            PersistentLog.info("REFRESH", "Intent received - triggering refresh")

            intent.removeExtra(DailyRefreshReceiver.EXTRA_TRIGGER_DAILY_REFRESH)

            Handler(Looper.getMainLooper()).postDelayed({
                com.dashieapp.Dashie.halite.diagnostics.MemoryMonitor.onMemoryPressureCallback?.invoke("scheduled", 0)
                haliteScreenController?.startDailyRefreshScheduler()
            }, 1000)
        }
    }

    /**
     * Check if voice should be initialized on resume.
     * Handles the case where user grants microphone permission externally.
     */
    private fun checkHaliteVoiceOnResume() {
        if (halitePrefs?.voice?.voiceEnabled != true) return
        if (haliteVoiceController?.isInitialized() == true) return

        if (permissionDelegate.hasMicrophonePermission()) {
            Log.i(TAG, "🎤 Voice permission granted externally - initializing voice on resume")
            haliteVoiceController?.checkPermissionAndInit()
        } else {
            Log.d(TAG, "🎤 Voice enabled but permission not granted yet")
            if (DeviceInfoHelper.isFireTV()) {
                permissionDelegate.showMicrophonePermissionInstructions()
            }
        }
    }

    /**
     * Hide the soft keyboard.
     */
    fun hideKeyboard() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        activity.currentFocus?.let {
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
        imm.hideSoftInputFromWindow(webView.windowToken, 0)
    }

    /**
     * Check if the soft keyboard is currently showing.
     */
    fun isKeyboardShowing(): Boolean {
        val rootView = activity.window.decorView.rootView
        val rect = android.graphics.Rect()
        rootView.getWindowVisibleDisplayFrame(rect)
        val screenHeight = rootView.height
        val keypadHeight = screenHeight - rect.bottom
        return keypadHeight > screenHeight * 0.15
    }
}

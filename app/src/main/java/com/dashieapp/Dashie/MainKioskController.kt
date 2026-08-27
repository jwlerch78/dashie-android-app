package com.dashieapp.Dashie

import android.app.admin.DevicePolicyManager
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.dashieapp.Dashie.halite.DashieDeviceAdminReceiver
import com.dashieapp.Dashie.halite.DeviceAdminHelper
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.DisplayPreferences
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.ui.ImmersiveModeController

/**
 * Handles kiosk mode, lock to app, and related settings for MainActivity.
 * Extracted from MainActivity to reduce its size and improve maintainability.
 *
 * Responsibilities:
 * - Apply keep screen on setting
 * - Apply dashboard zoom
 * - Apply Halite kiosk settings (fullscreen, show when locked)
 * - Start/stop lock to app mode (screen pinning + immersive mode)
 * - Safe app exit with lock task cleanup
 */
class MainKioskController(
    private val activity: ComponentActivity,
    private val webView: () -> WebView,
    private val halitePrefs: () -> HalitePreferences?,
    private val immersiveModeController: ImmersiveModeController,
    private val callbacks: Callbacks
) {
    companion object {
        private const val TAG = "MainKioskController"
        /** Grace period before asking whether a lock task actually engaged —
         *  the state transition is asynchronous, so an immediate read races it. */
        private const val LOCK_TASK_VERIFY_DELAY_MS = 750L
    }

    interface Callbacks {
        fun isAllowUrlConfig(): Boolean
        fun isTabletDevice(): Boolean
        fun getIntent(): Intent?
    }

    /**
     * Apply keep screen on setting immediately.
     * Re-applies immersive mode after flag change to prevent window insets from resetting.
     * See ImmersiveModeController class comment for full details on this bug.
     */
    fun applyKeepScreenOnSetting(keepOn: Boolean) {
        if (keepOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Re-apply immersive mode - changing window flags can reset the insets behavior
        // on some devices (especially Fire tablets), causing system bars to appear on touch
        if (callbacks.isAllowUrlConfig()) {
            immersiveModeController.reapplyAfterWindowChange()
        }
    }

    /**
     * Apply dashboard zoom setting to the WebView via CSS variable.
     * Sets --dashboard-zoom on the shell/app page. In kiosk mode, the shell CSS
     * uses this variable for container sizing + zoom. In full mode, dashboard.css uses it.
     * Also syncs to localStorage so the web app can restore on reload — the JS
     * read path (kiosk-shell.js + core-initializer.js) reads localStorage first
     * and falls back to DashieNative.getDashboardZoom only when localStorage is
     * empty, so this write is what keeps a native-settings zoom change visible
     * after the next page reload.
     *
     * @param zoomPercent Zoom level (10-300%, 100 = default)
     */
    fun applyDashboardZoom(zoomPercent: Int) {
        if (!callbacks.isAllowUrlConfig()) {
            // Silent on firetv (the only flavor with ALLOW_URL_CONFIG=false) — but
            // a user report of "zoom does nothing" must be able to distinguish
            // "gated off" from "ran and had no effect".
            PersistentLog.info("ZOOM", "apply SKIPPED: url config not allowed (flavor gate)")
            return
        }

        val clampedZoom = zoomPercent.coerceIn(
            DisplayPreferences.MIN_DASHBOARD_ZOOM, DisplayPreferences.MAX_DASHBOARD_ZOOM
        )
        val zoomFactor = clampedZoom / 100.0
        val directLoad = isDirectLoadPage()
        Log.i(TAG, "🔍 Applying dashboard zoom: $clampedZoom% (factor: $zoomFactor, directLoad: $directLoad)")
        // On disk, not just logcat: JK's 2026-07-08 report ("I set everything to
        // 50% and nothing changed … the app crashed and has now applied the
        // scaling") could not be diagnosed because nothing in this chain reaches
        // a user-submitted diagnostics report. Every link — picker write,
        // onChanged, ACTION_APPLY_ZOOM, the flavor gate, the JS eval — verifies
        // by inspection, and the CSS-variable mechanism was verified live in
        // Chromium, so the next report needs to show whether this ran AT ALL and
        // with what value. requested-vs-clamped is recorded because a silent
        // clamp is one of the few remaining explanations.
        val pageUrl = try { webView().url ?: "null" } catch (e: Exception) { "err:${e.javaClass.simpleName}" }
        PersistentLog.info(
            "ZOOM",
            "apply requested=$zoomPercent% clamped=$clampedZoom% directLoad=$directLoad page=${pageUrl.take(80)}"
        )

        // Apply zoom via CSS variable on the shell/app page.
        // In kiosk mode, kiosk-shell.css sizes the container and applies CSS zoom via this variable.
        // In full mode, dashboard.css applies zoom to .dashboard-grid via this variable.
        // In custom-URL mode the page is loaded directly (no shell), so nothing
        // consumes the variable — zoom the document itself instead.
        val zoomModeScript = if (directLoad) {
            """
                // Direct-load (custom URL) page: no --dashboard-zoom consumer
                // exists, so apply whole-page zoom on the document root.
                document.documentElement.style.zoom = '$zoomFactor';
                document.documentElement.style.transform = '';
                document.documentElement.style.transformOrigin = '';
                document.documentElement.style.width = '';
                document.documentElement.style.height = '';
            """
        } else {
            """
                // Clear any legacy whole-page zoom to avoid double-zooming
                document.documentElement.style.zoom = '';
                document.documentElement.style.transform = '';
                document.documentElement.style.transformOrigin = '';
                document.documentElement.style.width = '';
                document.documentElement.style.height = '';
            """
        }
        webView().evaluateJavascript(
            """
            (function() {
                document.documentElement.style.setProperty('--dashboard-zoom', '$zoomFactor');
                document.documentElement.setAttribute('data-dashboard-zoom', '$clampedZoom');
                try { localStorage.setItem('dashie-dashboard-zoom', '$clampedZoom'); } catch(e) {}

                // Notify all iframe widgets so widget-zoom.js re-applies the
                // new zoom inside each iframe without requiring a reload.
                // widget-zoom.js filters by target — only the HA iframe
                // honors 'ha'; Dashie widgets ignore it (they use Widget
                // Zoom via a separate ACTION_APPLY_WIDGET_ZOOM broadcast).
                try {
                    document.querySelectorAll('iframe').forEach(function(f) {
                        try {
                            if (f.contentWindow) {
                                f.contentWindow.postMessage({ type: 'zoom-changed', target: 'ha', value: $clampedZoom }, '*');
                            }
                        } catch(e) {}
                    });
                } catch(e) {}

                $zoomModeScript
                console.log('[Dashie] Dashboard zoom applied: $clampedZoom% (directLoad: $directLoad)');
            })();
            """.trimIndent(),
            null
        )
    }

    /**
     * True when the WebView is displaying a directly-loaded page (custom URL
     * mode) rather than the kiosk shell or the full Dashie web app. Mirrors
     * the custom-URL branch in MainUrlHandler.determineInitialUrl().
     */
    private fun isDirectLoadPage(): Boolean {
        val prefs = halitePrefs() ?: return false
        // Linked account (full Dashie web app) — dashboard.css consumes the CSS var
        if (prefs.account.showsDashboard) return false
        return prefs.connection.useCustomUrl && prefs.connection.customUrl.isNotEmpty()
    }

    /**
     * Apply initial zoom setting from preferences.
     * Called after WebView loads. Uses a delay to ensure the page is fully rendered
     * before applying CSS zoom (otherwise it may not take effect on first load).
     */
    fun applyInitialDashboardZoom() {
        if (!callbacks.isAllowUrlConfig()) return
        val zoom = halitePrefs()?.display?.dashboardZoom ?: HalitePreferences.DEFAULT_DASHBOARD_ZOOM
        if (zoom != 100) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                applyDashboardZoom(zoom)
            }, 1000)
        }
    }

    /**
     * Halite: Apply kiosk mode settings based on user preferences
     * - Keep screen on (if enabled)
     * - Fullscreen/immersive mode (hide status bar)
     * - Dismiss lock screen if launched from boot
     */
    @Suppress("DEPRECATION")
    fun applyHaliteKioskSettings() {
        val prefs = halitePrefs() ?: return

        // Keep screen on based on user preference
        if (prefs.sleep.keepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.i(TAG, "🔧 Halite: Keep screen on ENABLED")
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.i(TAG, "🔧 Halite: Keep screen on DISABLED")
        }

        // Always show over the lock screen — NOT gated on startOnBoot.
        // Was previously `if (prefs.startOnBoot)`, which left users without
        // Start-on-Boot (Jules, Matt Stein) with no bypass at all — their
        // device wakes to the Android keyguard instead of Dashie. The
        // KioskWindowManager copy of this logic is currently unreachable
        // (HaliteScreenController.applyKioskSettings has no callers), so
        // this is the only live lock-screen-bypass path.
        //
        // FLAG_DISMISS_KEYGUARD is only safe when the keyguard is NOT secure
        // (no PIN/password) — otherwise it triggers an auth prompt rather
        // than dismissing. setShowWhenLocked still lets us draw over a
        // secure keyguard.
        val keyguardManager = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isKeyguardSecure = keyguardManager?.isKeyguardSecure == true
        if (isKeyguardSecure) {
            activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        } else {
            activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Also use new API on Android 8.1+
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        }

        // Request keyguard dismissal only when launched from boot.
        // KeyguardHelper checks isKeyguardSecure internally and skips if PIN is set.
        // Note: KeyguardHelper is in a separate file to avoid ClassNotFoundException on API < 26
        val launchedFromBoot = callbacks.getIntent()?.getBooleanExtra("launched_from_boot", false) ?: false
        if (launchedFromBoot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.i(TAG, "🔧 Halite: Launched from boot - requesting keyguard dismissal")
            KeyguardHelper.requestDismissKeyguard(activity)
        }
        Log.i(TAG, "🔧 Halite: Show when locked ENABLED (always)")
        PersistentLog.info(
            "SCREEN",
            "Lockscreen bypass applied: secure=$isKeyguardSecure boot=$launchedFromBoot " +
                "api27plus=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1}"
        )

        // Enable lock to app (screen pinning + immersive mode) if enabled in preferences
        if (prefs.lock.lockToApp) {
            startLockToApp()
        }
    }

    /**
     * Enable immersive fullscreen mode (delegates to ImmersiveModeController)
     *
     * For Halite (Fire tablets):
     * - kioskMode = true always (enables BEHAVIOR_DEFAULT + re-hide listener)
     * - fastHide = true (250ms) when Lock Nav Bar is enabled, false (3s) when disabled
     *
     * For standard tablets:
     * - Uses provided fastHide parameter (defaults to true)
     */
    fun enableImmersiveMode(fastHide: Boolean = true) {
        immersiveModeController.enable(kioskMode = callbacks.isAllowUrlConfig(), fastHide = fastHide)
    }

    /**
     * Disable immersive fullscreen mode (delegates to ImmersiveModeController)
     */
    fun disableImmersiveMode() {
        immersiveModeController.disable()
    }

    /**
     * Start Lock to App mode (screen pinning + immersive mode).
     * Screen pinning blocks the notification shade from being pulled down.
     *
     * Three modes:
     * - Fire tablets: skip pinning (immersive mode re-hide is sufficient)
     * - Device owner: silent lock task via setLockTaskPackages (no toast, blocks shade)
     * - Standard: screen pinning with toast (or immersive-only if pinAppWhenLocked=false)
     *
     * Whether the lock ACTUALLY engaged is decided by [verifyLockTaskEngaged],
     * not by whether the call returned — see the note there. The Fire-tablet
     * branch is a shortcut for a known device class, not the test for "can this
     * device pin".
     */
    fun startLockToApp() {
        DiagnosticBuffer.info(TAG, "Starting Lock to App mode")

        val isFireTablet = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        if (isFireTablet) {
            DiagnosticBuffer.info(TAG, "Fire tablet - using immersive mode only (no screen pinning)")
            enableImmersiveMode(fastHide = true)
            return
        }

        enableImmersiveMode(fastHide = true)

        // Check if already in lock task mode to avoid redundant calls
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
            DiagnosticBuffer.debug(TAG, "Already in lock task mode (${am.lockTaskModeState}) - skipping")
            return
        }

        // Device owner: silent lock task (no toast, fully blocks shade)
        if (DeviceAdminHelper.isDeviceOwnerApp(activity)) {
            try {
                DeviceAdminHelper.dpm(activity)?.setLockTaskPackages(
                    DeviceAdminHelper.component(activity),
                    arrayOf(activity.packageName)
                )
                activity.startLockTask()
                DiagnosticBuffer.info(TAG, "Device owner: silent lock task started (LOCK_TASK_MODE_LOCKED)")
                verifyLockTaskEngaged("device owner")
            } catch (e: Exception) {
                DiagnosticBuffer.warn(TAG, "Device owner lock task failed: ${e.message}")
            }
            return
        }

        // Standard path: use screen pinning if user hasn't disabled it
        val pinAppEnabled = halitePrefs()?.lock?.pinAppWhenLocked != false
        if (!pinAppEnabled) {
            DiagnosticBuffer.info(TAG, "Pin app disabled - using immersive mode only")
            return
        }

        // Check if screen pinning is enabled in Android Settings > Security.
        // If explicitly disabled (value=0), startLockTask() silently does nothing.
        // If the setting doesn't exist (null on Samsung, etc.), assume enabled —
        // an assumption that is right on Samsung and wrong on a ROM with no
        // pinning at all. It stays, because refusing to try would break the
        // devices where the setting is simply absent; what changed is that being
        // wrong is now DETECTED after the fact by verifyLockTaskEngaged() rather
        // than reported as success.
        val systemPinningEnabled = try {
            val value = android.provider.Settings.Secure.getString(
                activity.contentResolver, "lock_to_app_enabled"
            )
            value == null || value == "1"  // null = setting doesn't exist = no restriction
        } catch (_: Exception) { true }

        if (!systemPinningEnabled) {
            DiagnosticBuffer.warn(TAG, "Screen pinning disabled in Android Settings — startLockTask will be ignored")
            Toast.makeText(
                activity,
                "App Lock: Enable \"Screen pinning\" in Android Settings > Security for full lock.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            activity.startLockTask()
            DiagnosticBuffer.info(TAG, "Screen pinning requested (system pinning enabled)")
            verifyLockTaskEngaged("screen pinning")
        } catch (e: Exception) {
            DiagnosticBuffer.warn(TAG, "Screen pinning failed: ${e.message}")
            Toast.makeText(
                activity,
                "App Lock: Screen pinning unavailable on this device.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Did the lock actually take? Ask the system, don't trust the call.
     *
     * 🔑 `startLockTask()` does not throw on a ROM that has no screen pinning —
     * it silently does nothing. So the `try` block above SUCCEEDS, logs
     * "Screen pinning requested", and the user is told the device is locked
     * while the notification shade still pulls down. Of the three outcomes only
     * two were audible before this: the explicitly-disabled path toasts, the
     * throwing path toasts, and the quiet no-op — the one that ships a broken
     * offering — said "requested" and stopped.
     *
     * That is also the real fix for the manufacturer-keyed fallback. The Amazon
     * fast-path above is a tested shortcut for a device class we ship to, but it
     * was doing double duty as the *capability oracle*, so any other ROM without
     * pinning (a Facebook Portal, say) took the standard path straight into the
     * silent gap. Capability is now decided by the OUTCOME, on every device, and
     * `Build.MANUFACTURER` decides only whether to skip an attempt we already
     * know is pointless.
     *
     * Checked after a short delay because the transition is not synchronous —
     * an immediate read races the system and would toast "unavailable" on
     * devices where pinning is about to engage, which is a worse failure than
     * the one being fixed. Immersive mode is already enabled by the caller, so
     * the fallback is a truthful message, not a behaviour change.
     */
    private fun verifyLockTaskEngaged(what: String) {
        Handler(Looper.getMainLooper()).postDelayed({
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
                DiagnosticBuffer.warn(
                    TAG,
                    "DROP: $what did not engage — startLockTask() returned without error but " +
                        "lockTaskModeState is still NONE (ROM does not support pinning). " +
                        "Falling back to immersive-only; the shade is NOT blocked."
                )
                PersistentLog.warn(
                    "SCREEN",
                    "LOCK: $what no-op on ${Build.MANUFACTURER} ${Build.MODEL} — immersive-only"
                )
                Toast.makeText(
                    activity,
                    "App Lock: this device doesn't support screen pinning — using fullscreen only.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                DiagnosticBuffer.info(TAG, "$what engaged (lockTaskModeState=${am.lockTaskModeState})")
            }
        }, LOCK_TASK_VERIFY_DELAY_MS)
    }

    /**
     * Stop Lock to App mode (disable screen pinning, switch to slow re-hide).
     * Keeps immersive mode enabled but with a 3-second re-hide delay instead of 250ms.
     */
    fun stopLockToApp() {
        Log.i(TAG, "🔓 Stopping Lock to App mode")

        // Only stop screen pinning if it's actually active
        // (Fire tablets and devices with pin-app-when-locked disabled never start pinning)
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
            try {
                activity.stopLockTask()
                Log.i(TAG, "🔓 Screen pinning stopped successfully")
            } catch (e: Exception) {
                Log.w(TAG, "🔓 Failed to stop screen pinning: ${e.message}")
            }
        }

        // Keep immersive mode but with slower re-hide (3 seconds instead of 250ms)
        // This allows users to access system bars more easily while still auto-hiding
        enableImmersiveMode(fastHide = false)
    }

    /**
     * Safely exit the app, stopping screen pinning first if active.
     * This ensures the user can actually exit when Lock to App is enabled.
     */
    fun exitAppSafely() {
        Log.i(TAG, "🚪 Exiting app safely")

        // Stop screen pinning if active (allows the app to actually close)
        try {
            activity.stopLockTask()
            Log.i(TAG, "🚪 Screen pinning stopped for exit")
        } catch (e: Exception) {
            // Ignore - may not be pinned
        }

        // Stop the DashieApiService BEFORE killing the process. The
        // service is START_STICKY, so Android automatically restarts it
        // after killProcess() — and the service's restart handler relaunches
        // MainActivity, making "exit" feel like a no-op. Explicitly stopping
        // it first prevents that auto-restart cycle.
        try {
            val serviceIntent = Intent(activity, com.dashieapp.Dashie.api.DashieApiService::class.java)
            activity.stopService(serviceIntent)
            Log.i(TAG, "🚪 DashieApiService stopped for exit")
        } catch (e: Exception) {
            Log.w(TAG, "🚪 Failed to stop DashieApiService: ${e.message}")
        }

        // finishAffinity closes all activities and triggers onDestroy for cleanup,
        // but Android keeps the process alive as a cached process.
        // We kill the process after a short delay to let onDestroy run its cleanup first.
        activity.finishAffinity()

        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 500)
    }

    /**
     * Check if kiosk mode is currently enabled (delegates to ImmersiveModeController)
     */
    fun checkKioskModeEnabled(): Boolean {
        return immersiveModeController.isEnabled(callbacks.isAllowUrlConfig(), callbacks.isTabletDevice())
    }
}

package com.dashieapp.Dashie.halite

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import com.dashieapp.Dashie.KeyguardHelper
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog

/**
 * Manages kiosk-mode window flags and hardware wake for Dashie Kiosk.
 *
 * Responsibilities:
 * - Keep screen on / show when locked flags
 * - Boot-time keyguard dismissal
 * - Hardware sleep wake (Activity window flags approach)
 * - Clearing temporary wake flags after resume
 *
 * @param activity The activity context
 * @param halitePrefs Kiosk preferences
 */
class KioskWindowManager(
    private val activity: Activity,
    private val halitePrefs: HalitePreferences
) {
    companion object {
        private const val TAG = "HaliteScreenController"
    }

    /**
     * Callback to re-apply immersive mode after window flag changes.
     * Set this from MainActivity to use the central ImmersiveModeController.
     * See ImmersiveModeController class comment for full details on this bug.
     */
    var onWindowFlagsChanged: (() -> Unit)? = null

    /**
     * Apply kiosk mode settings based on user preferences.
     * - Keep screen on (if enabled)
     * - Fullscreen/immersive mode (hide status bar)
     * - Dismiss lock screen if launched from boot
     *
     * NOTE: This modifies window flags which can reset immersive mode on Fire tablets.
     * The onWindowFlagsChanged callback should be set to re-apply immersive mode.
     */
    @Suppress("DEPRECATION")
    fun applyKioskSettings(launchedFromBoot: Boolean = false) {
        // Keep screen on based on user preference
        if (halitePrefs.sleep.keepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.i(TAG, "🔧 Keep screen on ENABLED")
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.i(TAG, "🔧 Keep screen on DISABLED")
        }

        // Always show over the lockscreen, regardless of startOnBoot. These
        // flags are persistent activity properties — they only have an
        // effect when the keyguard is shown, and harmlessly idle the rest
        // of the time. Gating them on startOnBoot meant users who didn't
        // enable that setting saw the OS lock screen every time the device
        // woke up via Android's own paths (auto-screen-off, power button)
        // because Dashie hadn't told the OS to bring its activity over the
        // keyguard. Reported by Matt Stein 2026-05-04.
        val keyguardManager = activity.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val isKeyguardSecure = keyguardManager.isKeyguardSecure

        if (isKeyguardSecure) {
            Log.i(TAG, "🔧 Keyguard is secure - showing over lockscreen without dismissal")
            // Don't use FLAG_DISMISS_KEYGUARD when PIN is set — it triggers
            // an authentication prompt rather than dismissing.
            activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        } else {
            Log.i(TAG, "🔧 Keyguard is not secure - attempting dismissal")
            // Safe to dismiss when no PIN is set
            activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // API 27+ recommended setters for the same behavior, persistent
            // for the activity's lifetime.
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        }

        // Request keyguard dismissal only when launched from boot.
        // KeyguardHelper checks isKeyguardSecure internally and skips if PIN is set.
        if (launchedFromBoot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.i(TAG, "🔧 Launched from boot - requesting keyguard dismissal (will skip if secure)")
            KeyguardHelper.requestDismissKeyguard(activity)
        }
        Log.i(TAG, "🔧 Show when locked ENABLED (always, persistent for activity lifetime)")
        // Persist to disk so we can correlate with onResume keyguard state
        // when triaging lockscreen-on-wake reports (Matt Stein 2026-05-04).
        // Pattern to look for: this line present + onResume reports
        // keyguardLocked=false → setShowWhenLocked is being honored.
        // keyguardLocked=true at onResume → keyguard appearing through us.
        PersistentLog.info(
            "SCREEN",
            "Lockscreen bypass applied: secure=$isKeyguardSecure " +
                "boot=$launchedFromBoot api27plus=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1}"
        )

        // Re-apply immersive mode after all window flag changes
        // This is critical to prevent the nav bar appearing bug on Fire tablets
        onWindowFlagsChanged?.invoke()
    }

    /**
     * Wake the device from hardware sleep (lockNow() state).
     *
     * Uses Activity-level window flags which are more reliable than PowerManager
     * wake locks on modern Android devices, especially Samsung devices with
     * aggressive power management.
     *
     * This method:
     * 1. Applies FLAG_TURN_SCREEN_ON and FLAG_SHOW_WHEN_LOCKED to the Activity window
     * 2. Uses setTurnScreenOn(true) and setShowWhenLocked(true) on API 27+
     * 3. Requests keyguard dismissal on API 28+
     * 4. Brings the Activity to foreground
     *
     * Called from ScreenDimmer.wakeDeviceHardware() via callback.
     */
    @Suppress("DEPRECATION")
    fun wakeFromHardwareSleep() {
        Log.i(TAG, "wakeFromHardwareSleep() - applying Activity wake flags")
        DiagnosticBuffer.info("SCREEN", "wakeFromHardwareSleep() - applying Activity wake flags")
        PersistentLog.info("SCREEN", "wakeFromHardwareSleep — applying activity wake flags")

        try {
            // Pre-hide system bars BEFORE adding window flags to prevent nav bar flash.
            // addFlags() resets immersive mode on many devices (documented Fire tablet bug),
            // causing a brief nav bar appearance. By hiding bars synchronously before AND after
            // the flag changes, we minimize the visible flash.
            hideSystemBarsSynchronously()

            // Check if keyguard is secure (has PIN/password/pattern)
            val keyguardManager = activity.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            val isKeyguardSecure = keyguardManager.isKeyguardSecure

            if (isKeyguardSecure) {
                Log.i(TAG, "Keyguard is secure - showing over lockscreen without dismissal")
                // Don't use FLAG_DISMISS_KEYGUARD when PIN is set - it triggers authentication prompt
                activity.window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
            } else {
                Log.i(TAG, "Keyguard is not secure - attempting dismissal")
                // Safe to dismiss keyguard when no PIN is set
                activity.window.addFlags(
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
            }
            Log.i(TAG, "Applied legacy window flags (FLAG_TURN_SCREEN_ON, FLAG_SHOW_WHEN_LOCKED)")

            // Immediately re-hide system bars after flag changes (before next frame renders)
            hideSystemBarsSynchronously()

            // Use new API on Android 8.1+ (API 27)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                Log.i(TAG, "Applied API 27+ methods (setTurnScreenOn, setShowWhenLocked)")
            }

            // Request keyguard dismissal on Android 8.0+ (API 26)
            // KeyguardHelper will check isKeyguardSecure internally and skip if PIN is set
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                KeyguardHelper.requestDismissKeyguard(activity)
                Log.i(TAG, "Requested keyguard dismissal (will skip if secure)")
            }

            // Bring activity to foreground - critical for showing over lockscreen
            // Create an intent to relaunch the current activity
            val intent = Intent(activity, activity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            activity.startActivity(intent)
            Log.i(TAG, "Brought activity to foreground")

            // Re-apply full immersive mode after all window flag changes
            onWindowFlagsChanged?.invoke()

            DiagnosticBuffer.info("SCREEN", "wakeFromHardwareSleep() completed successfully")
            PersistentLog.info("SCREEN", "wakeFromHardwareSleep — completed (activity foregrounded)")
        } catch (e: Exception) {
            Log.e(TAG, "wakeFromHardwareSleep() failed: ${e.message}")
            DiagnosticBuffer.error("SCREEN", "wakeFromHardwareSleep() failed: ${e.message}")
            PersistentLog.warn("SCREEN", "wakeFromHardwareSleep failed: ${e.message}")
        }
    }

    /**
     * Synchronously hide system bars to prevent nav bar flash during window flag changes.
     * This is called before and after addFlags() to minimize the window where bars are visible.
     */
    @Suppress("DEPRECATION")
    private fun hideSystemBarsSynchronously() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.hide(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
        } else {
            activity.window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    /**
     * Clear wake-related window flags after the device has woken up.
     * This should be called after onResume to avoid keeping unnecessary flags.
     */
    fun clearWakeFlags() {
        try {
            // Remove the temporary wake flags but keep FLAG_KEEP_SCREEN_ON if user preference is set
            activity.window.clearFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )

            // Re-apply user preference for keep screen on
            if (halitePrefs.sleep.keepScreenOn) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            // Keep FLAG_SHOW_WHEN_LOCKED if Start on Boot is enabled (needed to show over lockscreen)
            if (halitePrefs.sleep.startOnBoot) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                Log.d(TAG, "Keeping FLAG_SHOW_WHEN_LOCKED for lockscreen bypass")
            }

            // Only clear setTurnScreenOn if we're fully awake
            // Keep setShowWhenLocked(true) if Start on Boot is enabled
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setTurnScreenOn(false)
                // Don't clear setShowWhenLocked - keep it for lockscreen bypass
            }

            Log.d(TAG, "Cleared temporary wake flags")
        } catch (e: Exception) {
            Log.e(TAG, "clearWakeFlags() failed: ${e.message}")
        }
    }
}

package com.dashieapp.Dashie.halite.screensaver

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.dashieapp.Dashie.halite.DeviceAdminHelper
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog

/**
 * Hardware screen-control concerns extracted from ScreenDimmer:
 *
 * - **Device Admin** privileges (required for hardware screen off via `lockNow`)
 * - **Hardware screen off** — actually powering down the display, not just an
 *   overlay
 * - **Hardware wake** — both Activity-window-flag based wake and
 *   PowerManager wake-lock fallback
 * - **Brightness reduce/restore** — saves window + system brightness when
 *   dimming, restores on wake
 *
 * Owns no UI. Owns its own brightness-state cache + DevicePolicyManager
 * handles. Constructor takes a Context (must be an Activity for brightness
 * reduce to work — the controller no-ops if context isn't an Activity).
 */
class ScreenHardwareController(
    private val context: Context,
    private val brightnessPauseCallbackProvider: () -> ((Boolean) -> Unit)?
) {
    companion object {
        private const val TAG = "ScreenHardwareController"
    }

    private val handler = Handler(Looper.getMainLooper())

    private var savedBrightness: Float = Float.MIN_VALUE
    private var savedSystemBrightness: Int = -1

    /** Check if Dashie has Device Admin privileges (required for hardware screen off). */
    fun isDeviceAdminEnabled(): Boolean = DeviceAdminHelper.isActive(context)

    /**
     * Request Device Admin privileges. Returns an Intent to launch the
     * system settings. Caller should start this intent with
     * startActivityForResult().
     */
    fun getDeviceAdminEnableIntent(): Intent =
        DeviceAdminHelper.buildActivationIntent(
            context,
            DeviceAdminHelper.screenOffExplanation(context) +
            " This allows the display to actually power off instead of just showing a black overlay."
        )

    /**
     * Turn off the screen hardware via [android.app.admin.DevicePolicyManager.lockNow].
     * Requires Device Admin enabled. Returns true if successful, false otherwise.
     *
     * Also sets `wasInScreenOffMode` so [com.dashieapp.Dashie.halite.BootReceiver]
     * / DashieApiService know to wake us on the next SCREEN_ON broadcast
     * (power button press).
     */
    fun turnOffScreenHardware(): Boolean {
        if (!DeviceAdminHelper.isActive(context)) {
            Log.w(TAG, "Cannot turn off screen hardware - Device Admin not enabled")
            return false
        }
        HalitePreferences(context).sleep.wasInScreenOffMode = true
        Log.i(TAG, "Turning off screen hardware via lockNow()")
        PersistentLog.info("SCREEN", "turnOffScreenHardware — lockNow() — activity will background")
        return DeviceAdminHelper.lockNow(context)
    }

    /**
     * Wake the device hardware. Primary method is the Activity-based callback
     * (FLAG_TURN_SCREEN_ON via window flags); the wake-lock acquire below is a
     * fallback for devices where the Activity approach doesn't work (older
     * Samsung kiosk firmwares notably).
     *
     * @param activityWakeCallback Optional callback that applies window flags
     *   on the host Activity. Set by HaliteScreenController; null on
     *   non-Activity contexts.
     */
    fun wakeDeviceHardware(activityWakeCallback: (() -> Unit)?) {
        Log.i(TAG, "wakeDeviceHardware() called")
        PersistentLog.info(
            "SCREEN",
            "wakeDeviceHardware called — activityCallback=${activityWakeCallback != null}"
        )

        var activityWakeOk = true
        if (activityWakeCallback != null) {
            Log.i(TAG, "Using Activity-based wake")
            try {
                activityWakeCallback.invoke()
            } catch (e: Exception) {
                activityWakeOk = false
                Log.e(TAG, "Activity-based wake failed: ${e.message}")
                PersistentLog.warn("SCREEN", "Activity-based wake failed: ${e.message}")
            }
        } else {
            activityWakeOk = false
            Log.w(TAG, "Activity wake callback not set - using fallback only")
            PersistentLog.warn("SCREEN", "Activity wake callback not set — wake-lock fallback only")
        }

        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "DashieLite:ScreenWake"
            )
            wakeLock.acquire(2000)
            Log.i(TAG, "Wake lock acquired (SCREEN_BRIGHT_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP)")
            PersistentLog.info(
                "SCREEN",
                "Wake lock acquired (activityWakeOk=$activityWakeOk)"
            )
            handler.postDelayed({
                if (wakeLock.isHeld) {
                    wakeLock.release()
                    Log.d(TAG, "Wake lock released")
                }
            }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock: ${e.message}")
            PersistentLog.warn("SCREEN", "Failed to acquire wake lock: ${e.message}")
        }
    }

    /**
     * Reduce window + system brightness to 0 (saves prior values for restore).
     * No-ops if the context isn't an Activity. Pauses any auto-brightness
     * subscriber via [brightnessPauseCallbackProvider] before saving so the
     * sensor doesn't race the save.
     */
    fun reduceBrightness() {
        val activity = context as? android.app.Activity ?: return

        brightnessPauseCallbackProvider()?.invoke(true)

        val lp = activity.window.attributes
        // Snapshot only when no save is pending. A second reduce without an
        // intervening restore (the dim → sleep transition) would capture the
        // already-reduced 0 as the "saved" value — and restoreBrightness()
        // would then faithfully restore a black screen. Re-asserting 0 below
        // stays idempotent either way.
        if (savedBrightness == Float.MIN_VALUE) {
            savedBrightness = lp.screenBrightness
            try {
                savedSystemBrightness = android.provider.Settings.System.getInt(
                    activity.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS
                )
            } catch (_: Exception) {
                savedSystemBrightness = -1
            }
        }
        lp.screenBrightness = 0f
        activity.window.attributes = lp

        Log.d(TAG, "Reduced brightness to 0 (savedWindow=$savedBrightness, savedSystem=$savedSystemBrightness)")
    }

    /**
     * Restore window + system brightness saved by [reduceBrightness]. No-ops
     * if no save is pending. Resumes auto-brightness after restore.
     */
    fun restoreBrightness() {
        if (savedBrightness == Float.MIN_VALUE) return
        val activity = context as? android.app.Activity ?: return

        val lp = activity.window.attributes
        lp.screenBrightness = savedBrightness
        activity.window.attributes = lp

        if (savedSystemBrightness >= 0) {
            try {
                android.provider.Settings.System.putInt(
                    activity.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                    savedSystemBrightness
                )
            } catch (_: Exception) { /* WRITE_SETTINGS may not be granted */ }
        }

        Log.d(TAG, "Restored brightness (window=$savedBrightness, system=$savedSystemBrightness)")
        savedBrightness = Float.MIN_VALUE
        savedSystemBrightness = -1

        brightnessPauseCallbackProvider()?.invoke(false)
    }
}

package com.dashieapp.Dashie.devicecontrols

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/**
 * Manages screen brightness control.
 * Uses 0-10 scale (mapped from Android's 0-255 hardware scale).
 * Requires WRITE_SETTINGS permission on Android M+.
 */
class BrightnessManager(private val activity: Activity) {

    companion object {
        private const val TAG = "BrightnessManager"
        private const val SCALE_MAX = 10
        private const val HARDWARE_MAX = 255
        private const val HARDWARE_MIN = 10  // Avoid black screen
    }

    private val contentResolver = activity.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private var brightnessObserver: ContentObserver? = null
    private var lastReportedBrightness: Int = -1

    /**
     * Callback for brightness changes (from API, hardware, auto-brightness, etc.)
     * Parameter is brightness on 0-10 scale.
     */
    var onBrightnessChanged: ((brightness: Int) -> Unit)? = null

    /**
     * Callback to re-apply immersive mode after window attribute changes.
     * Set this to prevent the nav bar appearing bug on Fire tablets.
     * When window.attributes is modified, it can reset the window insets behavior.
     */
    var onWindowAttributesChanged: (() -> Unit)? = null

    /**
     * Get current screen brightness on 0-10 scale
     */
    fun getBrightness(): Int {
        return try {
            val brightness = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            val level = Math.round(brightness * SCALE_MAX.toFloat() / HARDWARE_MAX).toInt().coerceIn(0, SCALE_MAX)
            Log.d(TAG, "🔆 getBrightness(): raw $brightness/$HARDWARE_MAX = level $level")
            level
        } catch (e: Exception) {
            Log.e(TAG, "🔆 getBrightness() error: ${e.message}")
            5 // Default to mid-brightness
        }
    }

    /**
     * Get current screen brightness as percentage (0-100)
     * Used for UI display where 0-100% scale is expected.
     */
    fun getBrightnessPercent(): Int {
        return try {
            val brightness = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            val percent = Math.round(brightness * 100f / HARDWARE_MAX).toInt().coerceIn(0, 100)
            Log.d(TAG, "🔆 getBrightnessPercent(): raw $brightness/$HARDWARE_MAX = $percent%")
            percent
        } catch (e: Exception) {
            Log.e(TAG, "🔆 getBrightnessPercent() error: ${e.message}")
            50 // Default to mid-brightness
        }
    }

    /**
     * Set screen brightness on 0-100 percentage scale.
     * Used by auto-brightness controller for precise min/max enforcement.
     */
    fun setBrightnessPercent(percent: Int) {
        Log.i(TAG, "🔆 setBrightnessPercent($percent%) called")

        if (!canWriteSettings()) {
            Log.w(TAG, "🔆 Cannot set brightness - WRITE_SETTINGS permission not granted")
            return
        }

        try {
            // Disable auto-brightness first
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )

            // Map 0-100% directly to 0-255 hardware scale
            val brightness = Math.round(percent.coerceIn(0, 100) * HARDWARE_MAX / 100f)
                .toInt().coerceIn(HARDWARE_MIN, HARDWARE_MAX)

            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness
            )

            val windowBrightness = brightness / HARDWARE_MAX.toFloat()

            activity.runOnUiThread {
                try {
                    val params = activity.window.attributes
                    params.screenBrightness = windowBrightness
                    activity.window.attributes = params
                    Log.d(TAG, "🔆 Window brightness set to $windowBrightness")
                    onWindowAttributesChanged?.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "🔆 Failed to set window brightness: ${e.message}")
                }
            }

            Log.i(TAG, "🔆 Brightness $percent% -> raw $brightness/$HARDWARE_MAX")
        } catch (e: Exception) {
            Log.e(TAG, "🔆 setBrightnessPercent() error: ${e.message}")
        }
    }

    /**
     * Set screen brightness on 0-10 scale
     */
    fun setBrightness(level: Int) {
        Log.i(TAG, "🔆 setBrightness($level) called")

        if (!canWriteSettings()) {
            Log.w(TAG, "🔆 Cannot set brightness - WRITE_SETTINGS permission not granted")
            return
        }

        try {
            // Disable auto-brightness first
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )

            // Map 0-10 to 0-255 (minimum 10 to avoid black screen)
            val brightness = Math.round(level.coerceIn(0, SCALE_MAX) * HARDWARE_MAX / SCALE_MAX.toFloat())
                .toInt().coerceIn(HARDWARE_MIN, HARDWARE_MAX)

            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness
            )

            val windowBrightness = brightness / HARDWARE_MAX.toFloat()

            // Update window brightness on UI thread to ensure it takes effect
            activity.runOnUiThread {
                try {
                    val params = activity.window.attributes
                    params.screenBrightness = windowBrightness
                    activity.window.attributes = params
                    Log.d(TAG, "🔆 Window brightness set to $windowBrightness")

                    // Re-apply immersive mode after window attribute change
                    // This prevents the nav bar appearing bug on Fire tablets
                    onWindowAttributesChanged?.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "🔆 Failed to set window brightness: ${e.message}")
                }
            }

            Log.i(TAG, "🔆 Brightness level $level -> raw $brightness/$HARDWARE_MAX")
        } catch (e: Exception) {
            Log.e(TAG, "🔆 setBrightness() error: ${e.message}")
        }
    }

    /**
     * Increase brightness by 1 on 0-10 scale
     * @return the new brightness level (0-10)
     */
    fun brightnessUp(): Int {
        val currentLevel = getBrightness()
        val newLevel = (currentLevel + 1).coerceIn(0, SCALE_MAX)
        setBrightness(newLevel)
        Log.i(TAG, "🔆 brightnessUp(): level $currentLevel -> $newLevel")
        return newLevel
    }

    /**
     * Decrease brightness by 1 on 0-10 scale
     * @return the new brightness level (0-10)
     */
    fun brightnessDown(): Int {
        val currentLevel = getBrightness()
        val newLevel = (currentLevel - 1).coerceIn(0, SCALE_MAX)
        setBrightness(newLevel)
        Log.i(TAG, "🔆 brightnessDown(): level $currentLevel -> $newLevel")
        return newLevel
    }

    /**
     * Check if we have permission to modify system settings
     */
    fun canWriteSettings(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(activity)
        } else {
            true // Permission not required before Android M
        }
    }

    /**
     * Request permission to modify system settings (opens Android settings page)
     * @return true if the settings activity was launched, false if not available on this device
     */
    fun requestWriteSettingsPermission(): Boolean {
        Log.i(TAG, "🔆 Requesting WRITE_SETTINGS permission")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                intent.data = Uri.parse("package:${activity.packageName}")
                activity.startActivity(intent)
                true
            } catch (e: android.content.ActivityNotFoundException) {
                Log.w(TAG, "🔆 MANAGE_WRITE_SETTINGS activity not available on this device")
                false
            }
        }
        return true // Permission not required before Android M
    }

    /**
     * Get brightness info as JSON string
     * Contains: brightness (0-10), isAuto, rawValue, maxValue
     */
    fun getBrightnessInfo(): String {
        return try {
            val brightness = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            val mode = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val isAuto = mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            val level = Math.round(brightness * SCALE_MAX.toFloat() / HARDWARE_MAX).toInt().coerceIn(0, SCALE_MAX)

            JSONObject(mapOf(
                "brightness" to level,
                "isAuto" to isAuto,
                "rawValue" to brightness,
                "maxValue" to HARDWARE_MAX
            )).toString()
        } catch (e: Exception) {
            Log.e(TAG, "🔆 getBrightnessInfo() error: ${e.message}")
            "{\"brightness\": 5, \"isAuto\": false, \"rawValue\": 128, \"maxValue\": 255}"
        }
    }

    /**
     * Enable or disable auto-brightness
     */
    fun setAutoBrightness(enabled: Boolean) {
        Log.i(TAG, "🔆 setAutoBrightness($enabled) called")

        if (!canWriteSettings()) {
            Log.w(TAG, "🔆 Cannot set auto-brightness - WRITE_SETTINGS permission not granted")
            return
        }

        try {
            val mode = if (enabled) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            } else {
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            }
            val success = Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                mode
            )
            if (success) {
                Log.i(TAG, "🔆 Auto-brightness ${if (enabled) "enabled" else "disabled"}")
            } else {
                Log.w(TAG, "🔆 Failed to set auto-brightness mode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "🔆 setAutoBrightness() error: ${e.message}")
        }
    }

    /**
     * Start observing brightness changes from any source (API, hardware buttons, auto-brightness).
     * Calls onBrightnessChanged callback when brightness changes.
     */
    fun startObservingBrightnessChanges() {
        if (brightnessObserver != null) return

        brightnessObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                val currentBrightness = getBrightness()

                // Only notify if brightness actually changed
                if (currentBrightness != lastReportedBrightness) {
                    lastReportedBrightness = currentBrightness
                    Log.d(TAG, "🔆 Brightness changed externally: $currentBrightness")
                    onBrightnessChanged?.invoke(currentBrightness)
                }
            }
        }

        lastReportedBrightness = getBrightness()
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,
            brightnessObserver!!
        )
        Log.d(TAG, "🔆 Started observing brightness changes")
    }

    /**
     * Stop observing brightness changes
     */
    fun stopObservingBrightnessChanges() {
        brightnessObserver?.let {
            contentResolver.unregisterContentObserver(it)
            brightnessObserver = null
            Log.d(TAG, "🔆 Stopped observing brightness changes")
        }
    }
}

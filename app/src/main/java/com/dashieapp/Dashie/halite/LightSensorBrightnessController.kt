package com.dashieapp.Dashie.halite

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.dashieapp.Dashie.devicecontrols.BrightnessManager

/**
 * Controls screen brightness automatically based on ambient light sensor readings.
 *
 * When enabled, maps ambient light (lux) to a brightness percentage between
 * user-configurable min and max values.
 *
 * Lux ranges (approximate):
 * - 0-50 lux: Very dark (night, dark room)
 * - 50-500 lux: Indoor lighting
 * - 500+ lux: Bright (near window, outdoors)
 */
class LightSensorBrightnessController(
    private val context: Context,
    private val brightnessManager: BrightnessManager,
    private val onBrightnessChanged: ((Int) -> Unit)? = null  // Callback when brightness % changes
) : SensorEventListener {

    companion object {
        private const val TAG = "LightSensorBrightness"

        // Lux thresholds for brightness mapping
        private const val LUX_DARK = 10f      // Below this = minimum brightness (darker threshold)
        private const val LUX_BRIGHT = 1000f  // Above this = maximum brightness (higher for flashlight testing)

        // Debounce to prevent rapid brightness changes
        private const val UPDATE_INTERVAL_MS = 1000L

        // Minimum brightness change to apply (prevents micro-adjustments)
        private const val MIN_BRIGHTNESS_CHANGE = 2
    }

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var lightSensor: Sensor? = null

    private var isEnabled = false
    private var isRunning = false

    // Brightness range (0-100 percentage)
    private var minBrightness = 10
    private var maxBrightness = 100

    // Response curve: "linear", "aggressive", "gentle"
    private var responseCurve = HalitePreferences.BRIGHTNESS_CURVE_LINEAR

    // Current state
    private var currentLux = 0f
    private var lastAppliedBrightness = -1
    private var lastUpdateTime = 0L

    /** When true, sensor readings are tracked but brightness is NOT applied. */
    private var isPaused = false

    init {
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor != null) {
            Log.i(TAG, "Light sensor available: ${lightSensor!!.name}")
        } else {
            Log.w(TAG, "No light sensor available on this device")
        }
    }

    /**
     * Start listening to light sensor (for API exposure even when auto-brightness is off)
     */
    fun start() {
        if (isRunning) return
        if (lightSensor == null) {
            Log.w(TAG, "Cannot start - no light sensor")
            return
        }

        sensorManager.registerListener(
            this,
            lightSensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        isRunning = true
        Log.i(TAG, "Light sensor started (enabled=$isEnabled)")
    }

    /**
     * Stop listening to light sensor
     */
    fun stop() {
        if (!isRunning) return

        sensorManager.unregisterListener(this)
        isRunning = false
        Log.i(TAG, "Light sensor stopped")
    }

    /**
     * Pause brightness adjustments (e.g. during screensaver/sleep).
     * Sensor continues to read lux for API, but setBrightnessPercent is skipped.
     */
    fun setPaused(paused: Boolean) {
        isPaused = paused
        if (!paused) {
            // Force re-evaluation on resume so brightness catches up to current lux
            lastAppliedBrightness = -1
        }
        Log.i(TAG, "Auto-brightness ${if (paused) "paused" else "resumed"}")
    }

    /**
     * Enable or disable automatic brightness adjustment
     * When disabled, sensor still runs to provide lux readings for API
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        lastAppliedBrightness = -1  // Reset to force update on next reading
        Log.i(TAG, "Auto-brightness ${if (enabled) "enabled" else "disabled"}")

        // If enabling and we have a reading, apply immediately
        if (enabled && currentLux > 0) {
            applyBrightness()
        }
    }

    /**
     * Set min/max brightness range (0-100 percentage)
     */
    fun setMinMax(min: Int, max: Int) {
        minBrightness = min.coerceIn(0, 100)
        maxBrightness = max.coerceIn(0, 100)

        // Ensure min <= max
        if (minBrightness > maxBrightness) {
            minBrightness = maxBrightness
        }

        Log.i(TAG, "Brightness range set: $minBrightness% - $maxBrightness%")

        // Re-apply brightness with new range
        if (isEnabled && currentLux > 0) {
            applyBrightness()
        }
    }

    /**
     * Set the brightness response curve
     * @param curve One of: BRIGHTNESS_CURVE_LINEAR, BRIGHTNESS_CURVE_AGGRESSIVE, BRIGHTNESS_CURVE_GENTLE
     */
    fun setCurve(curve: String) {
        responseCurve = curve
        Log.i(TAG, "Response curve set: $curve")

        // Re-apply brightness with new curve
        if (isEnabled && currentLux > 0) {
            lastAppliedBrightness = -1  // Force update
            applyBrightness()
        }
    }

    /**
     * Get current ambient light level in lux
     * Used for API exposure
     */
    fun getCurrentLux(): Float = currentLux

    /**
     * Get current ambient light level as integer (for API)
     */
    fun getCurrentLuxInt(): Int = currentLux.toInt()

    /**
     * Check if light sensor is available on this device
     */
    fun isAvailable(): Boolean = lightSensor != null

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LIGHT) return

        currentLux = event.values[0]

        // Always update lux for API, but only adjust brightness if enabled
        if (!isEnabled) return

        // Rate limit brightness updates
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < UPDATE_INTERVAL_MS) return
        lastUpdateTime = now

        applyBrightness()
    }

    private fun applyBrightness() {
        if (isPaused) return

        val targetBrightness = calculateBrightness(currentLux)

        // Only apply if change is significant
        if (lastAppliedBrightness >= 0 &&
            kotlin.math.abs(targetBrightness - lastAppliedBrightness) < MIN_BRIGHTNESS_CHANGE) {
            Log.d(TAG, "Skipping - change too small: $lastAppliedBrightness% -> $targetBrightness%")
            return
        }

        Log.i(TAG, "🔆 Applying: ${currentLux.toInt()}lux -> $targetBrightness% (min=$minBrightness, max=$maxBrightness, curve=$responseCurve)")
        brightnessManager.setBrightnessPercent(targetBrightness)
        lastAppliedBrightness = targetBrightness

        // Notify listener of brightness change
        onBrightnessChanged?.invoke(targetBrightness)
    }

    /**
     * Calculate brightness percentage (0-100) based on lux reading
     * Applies the selected response curve to the lux-to-brightness mapping
     *
     * Response curves:
     * - Linear: Even/default - straight line interpolation
     * - Aggressive: Uses square root curve - faster brightening at low lux (good for dark rooms)
     * - Gentle: Uses squared curve - slower brightening, stays dimmer longer
     */
    private fun calculateBrightness(lux: Float): Int {
        // Clamp lux to our range
        val clampedLux = lux.coerceIn(0f, LUX_BRIGHT)

        // Calculate position in lux range (0.0 to 1.0)
        val luxRatio = when {
            clampedLux <= LUX_DARK -> 0f
            clampedLux >= LUX_BRIGHT -> 1f
            else -> (clampedLux - LUX_DARK) / (LUX_BRIGHT - LUX_DARK)
        }

        // Apply response curve to the ratio
        val adjustedRatio = when (responseCurve) {
            HalitePreferences.BRIGHTNESS_CURVE_AGGRESSIVE -> {
                // Square root curve: faster brightening at low lux
                // At 25% lux → 50% brightness ratio (vs 25% with linear)
                kotlin.math.sqrt(luxRatio.toDouble()).toFloat()
            }
            HalitePreferences.BRIGHTNESS_CURVE_GENTLE -> {
                // Squared curve: slower brightening, stays dimmer longer
                // At 50% lux → 25% brightness ratio (vs 50% with linear)
                luxRatio * luxRatio
            }
            else -> {
                // Linear: default, straight line
                luxRatio
            }
        }

        // Map to brightness range
        val brightness = minBrightness + (adjustedRatio * (maxBrightness - minBrightness))
        return brightness.toInt().coerceIn(minBrightness, maxBrightness)
    }

    /**
     * Set brightness directly (for manual mode)
     * @param brightness 0-100 percentage
     */
    fun setManualBrightness(brightness: Int) {
        Log.d(TAG, "Manual brightness: $brightness%")
        brightnessManager.setBrightnessPercent(brightness)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for light sensor
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        stop()
        Log.i(TAG, "LightSensorBrightnessController destroyed")
    }
}

package com.dashieapp.Dashie.webview.delegates

import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.halite.HalitePreferences

/**
 * JS bridge delegate for sleep/wake timer settings and screen-off behavior.
 *
 * Extracted from JsBridgeSettingsDelegate in Phase 7 (structural split).
 */
class JsBridgeSleepDelegate(
    private val webView: WebView,
    private val halitePrefs: () -> HalitePreferences?,
    // Screen-off power_off mode requires device admin — we check via a lambda
    // so this delegate doesn't pull in DevicePolicyManager directly.
    private val isDeviceAdminActive: () -> Boolean
) {
    companion object {
        private const val TAG = "JsBridgeSleep"
    }

    // Callbacks
    var onSleepSettingsChanged: (() -> Unit)? = null
    var onSleepNow: (() -> Unit)? = null
    var onWakeNow: (() -> Unit)? = null
    var onRequestDeviceAdmin: (() -> Unit)? = null

    // ============================================
    // Screen-Off Behavior
    // ============================================

    /**
     * Get screen-off behavior as a string.
     * Maps from the internal hardwareScreenOff boolean to webapp-friendly strings.
     * @return "black_overlay" or "power_off"
     */
    fun getScreenOffBehavior(): String {
        val hardwareOff = halitePrefs()?.sleep?.hardwareScreenOff ?: false
        return if (hardwareOff) "power_off" else "black_overlay"
    }

    /**
     * Set screen-off behavior from webapp string value.
     * Maps from webapp-friendly strings to the internal hardwareScreenOff boolean.
     * @param behavior "black_overlay" or "power_off"
     */
    fun setScreenOffBehavior(behavior: String) {
        Log.i(TAG, "🔧 setScreenOffBehavior($behavior)")
        when (behavior) {
            "power_off" -> {
                if (isDeviceAdminActive()) {
                    halitePrefs()?.sleep?.hardwareScreenOff = true
                    Log.i(TAG, "Screen-off behavior set to power_off")
                } else if (halitePrefs()?.display?.permissionPromptDeclined == true) {
                    // Cloud sync pushes a stale power_off here on EVERY boot when
                    // Device Admin was never granted (native value stays
                    // black_overlay, so read-compare-skip can't converge). Honor
                    // the user's "Don't ask again" instead of re-prompting forever.
                    Log.i(TAG, "Screen-off power_off requires Device Admin — prompt suppressed (user declined)")
                } else {
                    Log.i(TAG, "Screen-off power_off requires Device Admin, requesting...")
                    webView.post { onRequestDeviceAdmin?.invoke() }
                }
            }
            else -> {
                halitePrefs()?.sleep?.hardwareScreenOff = false
                Log.i(TAG, "Screen-off behavior set to black_overlay")
            }
        }
    }

    // ============================================
    // Sleep/Wake Timer Settings
    // ============================================

    fun getSleepSettings(): String {
        val prefs = halitePrefs() ?: return "{}"
        return org.json.JSONObject().apply {
            put("enabled", prefs.sleep.sleepEnabled)
            put("method", prefs.sleep.sleepMethod)
            put("sleepTime", prefs.sleep.sleepTime)
            put("wakeTime", prefs.sleep.wakeTime)
            put("resleepTimeout", prefs.sleep.resleepTimeout)
            put("inactivityTimeout", prefs.sleep.inactivityTimeout)
            put("motionWakeForSleep", prefs.sleep.motionWakeForSleep)
            put("sleepShowClock", prefs.sleep.sleepShowClock)
            put("reduceBrightnessOnSleep", prefs.sleep.reduceBrightnessOnSleep)
        }.toString()
    }

    fun getSleepDescription(): String {
        return halitePrefs()?.getSleepDescription() ?: "None"
    }

    fun setSleepEnabled(enabled: Boolean) {
        Log.i(TAG, "🔧 setSleepEnabled($enabled)")
        halitePrefs()?.sleep?.sleepEnabled = enabled
        notifySleepSettingsChanged()
    }

    fun setSleepMethod(method: String) {
        // Normalize "scheduled" → "schedule" (webapp historically used "scheduled")
        val normalized = if (method == "scheduled") "schedule" else method
        Log.i(TAG, "🔧 setSleepMethod($normalized)")
        halitePrefs()?.sleep?.sleepMethod = normalized
        notifySleepSettingsChanged()
    }

    fun setSleepTime(time: String) {
        Log.i(TAG, "🔧 setSleepTime($time)")
        halitePrefs()?.sleep?.sleepTime = time
        notifySleepSettingsChanged()
    }

    fun setWakeTime(time: String) {
        Log.i(TAG, "🔧 setWakeTime($time)")
        halitePrefs()?.sleep?.wakeTime = time
        notifySleepSettingsChanged()
    }

    /**
     * Notify that sleep settings have changed - reschedule alarms.
     */
    private fun notifySleepSettingsChanged() {
        webView.post { onSleepSettingsChanged?.invoke() }
    }

    fun setResleepTimeout(minutes: Int) {
        Log.i(TAG, "🔧 setResleepTimeout($minutes)")
        halitePrefs()?.sleep?.resleepTimeout = minutes
    }

    fun setInactivityTimeout(seconds: Int) {
        Log.i(TAG, "🔧 setInactivityTimeout($seconds seconds)")
        halitePrefs()?.sleep?.inactivityTimeout = seconds
    }

    fun setMotionWakeForSleep(enabled: Boolean) {
        Log.i(TAG, "🔧 setMotionWakeForSleep($enabled)")
        halitePrefs()?.sleep?.motionWakeForSleep = enabled
    }

    fun getMotionWakeForSleep(): Boolean {
        return halitePrefs()?.sleep?.motionWakeForSleep ?: false
    }

    fun setSleepShowClock(enabled: Boolean) {
        Log.i(TAG, "🔧 setSleepShowClock($enabled)")
        halitePrefs()?.sleep?.sleepShowClock = enabled
    }

    fun getSleepShowClock(): Boolean {
        return halitePrefs()?.sleep?.sleepShowClock ?: false
    }

    fun setReduceBrightnessOnSleep(enabled: Boolean) {
        Log.i(TAG, "🔧 setReduceBrightnessOnSleep($enabled)")
        halitePrefs()?.sleep?.reduceBrightnessOnSleep = enabled
    }

    fun sleepNow() {
        Log.i(TAG, "🔧 sleepNow()")
        webView.post { onSleepNow?.invoke() }
    }

    fun wakeNow() {
        Log.i(TAG, "🔧 wakeNow()")
        webView.post { onWakeNow?.invoke() }
    }
}

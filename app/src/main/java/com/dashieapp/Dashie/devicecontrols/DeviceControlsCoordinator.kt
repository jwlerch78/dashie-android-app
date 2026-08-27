package com.dashieapp.Dashie.devicecontrols

import android.app.Activity
import android.util.Log

/**
 * Coordinator for all device control operations.
 * Delegates to specialized managers for volume, brightness, and settings.
 *
 * This follows the same pattern as VoiceAssistantCoordinator.
 */
class DeviceControlsCoordinator(private val activity: Activity) {

    companion object {
        private const val TAG = "DeviceControlsCoord"
    }

    private val volumeManager = VolumeManager(activity)
    val brightnessManager = BrightnessManager(activity)  // Public for LightSensorBrightnessController
    private val settingsLauncher = SettingsLauncher(activity)
    private val connectivityManager = NetworkConnectivityManager(activity)
    private val darkModeManager = DarkModeManager(activity)

    init {
        Log.i(TAG, "DeviceControlsCoordinator initialized")
    }

    // ============================================
    // Volume Control (delegates to VolumeManager)
    // ============================================

    fun getVolume(): Int = volumeManager.getVolume()

    fun setVolume(level: Int) {
        activity.runOnUiThread {
            volumeManager.setVolume(level)
        }
    }

    fun volumeUp(amount: Int = 1): Int = volumeManager.volumeUp(amount)

    fun volumeDown(amount: Int = 1): Int = volumeManager.volumeDown(amount)

    fun isMuted(): Boolean = volumeManager.isMuted()

    fun mute() {
        activity.runOnUiThread {
            volumeManager.mute()
        }
    }

    fun unmute() {
        activity.runOnUiThread {
            volumeManager.unmute()
        }
    }

    fun toggleMute(): Boolean = volumeManager.toggleMute()

    fun getVolumeInfo(): String = volumeManager.getVolumeInfo()

    // ============================================
    // Brightness Control (delegates to BrightnessManager)
    // ============================================

    fun getBrightness(): Int = brightnessManager.getBrightness()

    fun getBrightnessPercent(): Int = brightnessManager.getBrightnessPercent()

    fun setBrightness(level: Int) {
        activity.runOnUiThread {
            brightnessManager.setBrightness(level)
        }
    }

    fun brightnessUp(): Int = brightnessManager.brightnessUp()

    fun brightnessDown(): Int = brightnessManager.brightnessDown()

    fun canWriteSettings(): Boolean = brightnessManager.canWriteSettings()

    /**
     * Request permission to modify system settings.
     * @return true if the settings activity was launched, false if not available on this device
     */
    fun requestWriteSettingsPermission(): Boolean {
        // This should be called from UI thread (from dialog button click)
        // If we're already on UI thread, call directly to get the return value
        return if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            brightnessManager.requestWriteSettingsPermission()
        } else {
            // Shouldn't happen, but handle gracefully
            var result = false
            activity.runOnUiThread {
                result = brightnessManager.requestWriteSettingsPermission()
            }
            result
        }
    }

    fun getBrightnessInfo(): String = brightnessManager.getBrightnessInfo()

    fun setAutoBrightness(enabled: Boolean) {
        activity.runOnUiThread {
            brightnessManager.setAutoBrightness(enabled)
        }
    }

    /**
     * Start observing brightness changes (from API, hardware buttons, auto-brightness).
     * Use this to update UI when brightness changes externally.
     */
    fun startObservingBrightnessChanges() {
        brightnessManager.startObservingBrightnessChanges()
    }

    /**
     * Stop observing brightness changes.
     */
    fun stopObservingBrightnessChanges() {
        brightnessManager.stopObservingBrightnessChanges()
    }

    /**
     * Set callback for brightness changes.
     */
    fun setOnBrightnessChangedCallback(callback: ((Int) -> Unit)?) {
        brightnessManager.onBrightnessChanged = callback
    }

    // ============================================
    // Settings & Timezone (delegates to SettingsLauncher)
    // ============================================

    fun openSystemSettings() {
        activity.runOnUiThread {
            settingsLauncher.openSystemSettings()
        }
    }

    fun openDateTimeSettings() {
        activity.runOnUiThread {
            settingsLauncher.openDateTimeSettings()
        }
    }

    fun openLocationSettings() {
        activity.runOnUiThread {
            settingsLauncher.openLocationSettings()
        }
    }

    fun openAppSettings() {
        activity.runOnUiThread {
            settingsLauncher.openAppSettings()
        }
    }

    fun getDeviceTimezone(): String = settingsLauncher.getDeviceTimezone()

    fun isLocationEnabled(): Boolean = settingsLauncher.isLocationEnabled()

    fun hasLocationPermission(): Boolean = settingsLauncher.hasLocationPermission()

    // ============================================
    // Connectivity (delegates to NetworkConnectivityManager)
    // ============================================

    fun isConnectedToInternet(): Boolean = connectivityManager.isConnectedToInternet()

    fun getWifiStatus(): String = connectivityManager.getWifiStatus()

    fun openWifiSettings() {
        activity.runOnUiThread {
            connectivityManager.openWifiSettings()
        }
    }

    // ============================================
    // Dark Mode Control (delegates to DarkModeManager)
    // ============================================

    /** Callback fired on the calling thread after any successful dark mode change. */
    var onDarkModeChanged: ((isDark: Boolean) -> Unit)? = null

    fun isSystemDarkMode(): Boolean = darkModeManager.isDarkMode()

    fun setSystemDarkMode(enabled: Boolean): Boolean {
        val result = darkModeManager.setDarkMode(enabled)
        if (result) {
            onDarkModeChanged?.invoke(enabled)
        }
        return result
    }

    fun toggleSystemDarkMode(): Boolean {
        val newState = darkModeManager.toggleDarkMode()
        // toggleDarkMode returns the new state; fire callback
        onDarkModeChanged?.invoke(newState)
        return newState
    }

    fun canWriteSecureSettings(): Boolean = darkModeManager.canWriteSecureSettings()

    fun getSystemDarkModeInfo(): String = darkModeManager.getDarkModeInfo()
}

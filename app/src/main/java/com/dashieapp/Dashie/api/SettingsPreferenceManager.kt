package com.dashieapp.Dashie.api

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.halite.HalitePreferences

/**
 * Manages settings preferences exposed through the Dashie API.
 *
 * Handles:
 * - Screensaver settings (mode, photo source, HA media folder)
 * - Display/UI settings (sidebar, header, screen on, boot, brightness, zoom)
 * - RTSP preference toggles (enabled, software encoding)
 * - Kiosk lock/PIN management
 *
 * Extracted from DashieServiceManager to isolate preference concerns.
 */
class SettingsPreferenceManager(
    private val webViewProvider: () -> WebView,
    private val halitePrefs: HalitePreferences
) {
    companion object {
        private const val TAG = "DashieSvcMgr"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val webView: WebView get() = webViewProvider()

    // Callbacks for cross-cutting concerns
    var onScreensaverModeChanged: ((String) -> Unit)? = null
    var setAutoBrightnessCallback: ((enabled: Boolean, min: Int, max: Int, curve: String) -> Unit)? = null
    var onSettingsChangedViaApi: (() -> Unit)? = null
    var onKioskLockChanged: ((Boolean) -> Unit)? = null
    var onPinChanged: (() -> Unit)? = null

    /** Lambda to start/stop RTSP server when preference changes */
    var onRtspToggle: ((enabled: Boolean) -> Unit)? = null

    // ============================================
    // Screen Off Method
    // ============================================

    fun getScreenOffMethod(): String =
        if (halitePrefs.sleep.hardwareScreenOff) "hardware" else "overlay"

    fun setScreenOffMethod(method: String): Boolean {
        return try {
            halitePrefs.sleep.hardwareScreenOff = (method == "hardware")
            Log.i(TAG, "Screen off method set to: $method (hardwareScreenOff=${method == "hardware"})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set screen off method: ${e.message}", e)
            false
        }
    }

    // ============================================
    // Screensaver Settings
    // ============================================

    fun getScreensaverMode(): String = halitePrefs.screensaver.screensaverMode

    fun setScreensaverMode(mode: String): Boolean {
        return try {
            halitePrefs.screensaver.screensaverMode = mode
            onScreensaverModeChanged?.invoke(mode)
            Log.i(TAG, "Screensaver mode set to: $mode")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set screensaver mode: ${e.message}", e)
            false
        }
    }

    fun getPhotoSource(): String = halitePrefs.screensaver.photoSourceType

    fun setPhotoSource(source: String): Boolean {
        return try {
            halitePrefs.screensaver.photoSourceType = source
            Log.i(TAG, "Photo source set to: $source")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set photo source: ${e.message}", e)
            false
        }
    }

    fun getHaMediaFolder(): String = halitePrefs.screensaver.haMediaFolder

    fun setHaMediaFolder(folder: String): Boolean {
        return try {
            halitePrefs.screensaver.haMediaFolder = folder
            Log.i(TAG, "HA Media folder set to: $folder")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set HA Media folder: ${e.message}", e)
            false
        }
    }

    // ============================================
    // Display/UI Settings (getters)
    // ============================================

    fun isHideSidebar(): Boolean = halitePrefs.connection.hideSidebar
    fun isHideHeader(): Boolean = halitePrefs.connection.hideTabs
    fun isKeepScreenOn(): Boolean = halitePrefs.sleep.keepScreenOn
    fun isStartOnBoot(): Boolean = halitePrefs.sleep.startOnBoot
    fun isAutoBrightness(): Boolean = halitePrefs.display.autoBrightnessEnabled
    fun getMotionWakeMode(): String = halitePrefs.screensaver.motionWakeMode
    fun getTextScaling(): Int = halitePrefs.display.dashboardZoom
    fun isRtspEnabled(): Boolean = halitePrefs.camera.rtspEnabled
    fun isRtspSoftwareEncoding(): Boolean = halitePrefs.camera.rtspForceSoftwareEncoding

    // ============================================
    // Display/UI Settings (setters)
    // ============================================

    fun setHideSidebar(enabled: Boolean): Boolean {
        return try {
            halitePrefs.connection.hideSidebar = enabled
            Log.i(TAG, "hideSidebar set to: $enabled")
            fireHaDisplayChanged()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set hideSidebar: ${e.message}", e)
            false
        }
    }

    fun setHideHeader(enabled: Boolean): Boolean {
        return try {
            halitePrefs.connection.hideTabs = enabled
            Log.i(TAG, "hideHeader (hideTabs) set to: $enabled")
            fireHaDisplayChanged()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set hideHeader: ${e.message}", e)
            false
        }
    }

    /**
     * Broadcast ACTION_HA_DISPLAY_CHANGED so a currently-visible HA page reloads
     * to pick up the new hide flags (the kiosk CSS interceptor bakes them in at
     * page load). Remote writers — the Console's Hide Sidebar/Tabs switches route
     * here via the Fully-Kiosk API — previously wrote the pref only, so the change
     * didn't apply live (unlike the native Settings path, which already fires this).
     * MainBroadcastManager's receiver reloads only when the WebView is actually on
     * the HA host, so this is a no-op in widgets mode.
     */
    private fun fireHaDisplayChanged() {
        val context = webView.context
        context.sendBroadcast(
            android.content.Intent("com.dashieapp.Dashie.ACTION_HA_DISPLAY_CHANGED").apply {
                setPackage(context.packageName)
            }
        )
    }

    fun setKeepScreenOn(enabled: Boolean): Boolean {
        return try {
            halitePrefs.sleep.keepScreenOn = enabled
            Log.i(TAG, "keepScreenOn set to: $enabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set keepScreenOn: ${e.message}", e)
            false
        }
    }

    fun setStartOnBoot(enabled: Boolean): Boolean {
        return try {
            halitePrefs.sleep.startOnBoot = enabled
            Log.i(TAG, "startOnBoot set to: $enabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set startOnBoot: ${e.message}", e)
            false
        }
    }

    fun setAutoBrightness(enabled: Boolean): Boolean {
        return try {
            halitePrefs.display.autoBrightnessEnabled = enabled
            val min = halitePrefs.display.autoBrightnessMin
            val max = halitePrefs.display.autoBrightnessMax
            val curve = halitePrefs.display.autoBrightnessCurve
            setAutoBrightnessCallback?.invoke(enabled, min, max, curve)
            onSettingsChangedViaApi?.invoke()
            Log.i(TAG, "autoBrightness set to: $enabled (min=$min, max=$max, curve=$curve)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set autoBrightness: ${e.message}", e)
            false
        }
    }

    fun setMotionWakeMode(mode: String): Boolean {
        return try {
            halitePrefs.screensaver.motionWakeMode = mode
            Log.i(TAG, "motionWakeMode set to: $mode")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set motionWakeMode: ${e.message}", e)
            false
        }
    }

    /**
     * Set text scaling (WebView zoom level) via HA plugin / Fully Kiosk API.
     * Saves to SharedPreferences, then fires ACTION_APPLY_ZOOM so the apply
     * goes through MainKioskController.applyDashboardZoom — the single path
     * that knows shell vs custom-URL (direct-load) mode, writes localStorage
     * for the JS reload read path, and notifies JS for Supabase sync.
     */
    fun setTextScaling(zoom: Int): Boolean {
        return try {
            val clampedZoom = zoom.coerceIn(
                com.dashieapp.Dashie.halite.preferences.DisplayPreferences.MIN_DASHBOARD_ZOOM,
                com.dashieapp.Dashie.halite.preferences.DisplayPreferences.MAX_DASHBOARD_ZOOM
            )
            halitePrefs.display.dashboardZoom = clampedZoom
            val context = webView.context
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_APPLY_ZOOM").apply {
                    setPackage(context.packageName)
                }
            )
            Log.i(TAG, "textScaling (dashboardZoom) set to: $clampedZoom% (applied via ACTION_APPLY_ZOOM)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set textScaling: ${e.message}", e)
            false
        }
    }

    /**
     * Set RTSP enabled preference and start/stop server accordingly.
     */
    fun setRtspEnabled(enabled: Boolean): Boolean {
        return try {
            halitePrefs.camera.rtspEnabled = enabled
            Log.i(TAG, "rtspEnabled preference set to: $enabled")

            handler.post {
                try {
                    onRtspToggle?.invoke(enabled)
                    if (!enabled) {
                        onSettingsChangedViaApi?.invoke()
                    } else {
                        handler.postDelayed({
                            onSettingsChangedViaApi?.invoke()
                        }, 500)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in RTSP start/stop: ${e.message}", e)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set rtspEnabled: ${e.message}", e)
            false
        }
    }

    fun setRtspSoftwareEncoding(enabled: Boolean): Boolean {
        return try {
            halitePrefs.camera.rtspForceSoftwareEncoding = enabled
            onSettingsChangedViaApi?.invoke()
            Log.i(TAG, "rtspSoftwareEncoding set to: $enabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set rtspSoftwareEncoding: ${e.message}", e)
            false
        }
    }

    // ============================================
    // Kiosk Lock / PIN Management
    // ============================================

    fun lockKiosk() {
        handler.post {
            Log.i(TAG, "lockKiosk API called - hasPinSet=${halitePrefs.lock.hasPinSet}")
            halitePrefs.lock.lockAppExit = true
            halitePrefs.lock.lockSettings = true
            halitePrefs.lock.unlockInterface = HalitePreferences.UNLOCK_INTERFACE_VISIBLE
            if (halitePrefs.lock.hasPinSet) {
                halitePrefs.lock.unlockMechanism = HalitePreferences.UNLOCK_MECHANISM_PIN
                Log.i(TAG, "App locked via API with PIN requirement")
            } else {
                halitePrefs.lock.unlockMechanism = HalitePreferences.UNLOCK_MECHANISM_ANYONE
                Log.i(TAG, "App locked via API (no PIN set)")
            }
            onKioskLockChanged?.invoke(true)
        }
    }

    fun unlockKiosk() {
        handler.post {
            halitePrefs.lock.lockAppExit = false
            halitePrefs.lock.lockSettings = false
            onKioskLockChanged?.invoke(false)
            Log.i(TAG, "App unlocked via API")
        }
    }

    fun isKioskLocked(): Boolean = halitePrefs.lock.isLocked
    fun isLockAppExit(): Boolean = halitePrefs.lock.lockAppExit
    fun isLockSettings(): Boolean = halitePrefs.lock.lockSettings
    fun hasPinSet(): Boolean = halitePrefs.lock.hasPinSet
    fun verifyPin(pin: String): Boolean = halitePrefs.lock.lockPin == pin
    fun getStoredPin(): String = halitePrefs.lock.lockPin

    fun setPin(pin: String): Boolean {
        handler.post {
            halitePrefs.lock.lockPin = pin
            halitePrefs.lock.lockPinLength = 4
            Log.i(TAG, "PIN set via API")
            onPinChanged?.invoke()
        }
        return true
    }

    fun clearPin() {
        handler.post {
            halitePrefs.lock.lockPin = ""
            halitePrefs.lock.lockRecoveryEmail = ""
            halitePrefs.lock.unlockMechanism = HalitePreferences.UNLOCK_MECHANISM_ANYONE
            Log.i(TAG, "PIN cleared via API")
            onPinChanged?.invoke()
        }
    }

    // ============================================
    // Service Callback Registration
    // ============================================

    /**
     * Register settings-related callbacks on the API service.
     */
    fun registerCallbacks(service: DashieApiService) {
        // Screen off method
        service.getScreenOffMethodCallback = { getScreenOffMethod() }
        service.setScreenOffMethodCallback = { method -> setScreenOffMethod(method) }

        // Screensaver settings
        service.getScreensaverModeCallback = { getScreensaverMode() }
        service.setScreensaverModeCallback = { mode -> setScreensaverMode(mode) }
        service.getPhotoSourceCallback = { getPhotoSource() }
        service.setPhotoSourceCallback = { source -> setPhotoSource(source) }
        service.getHaMediaFolderCallback = { getHaMediaFolder() }
        service.setHaMediaFolderCallback = { folder -> setHaMediaFolder(folder) }

        // Display/UI settings (getters)
        service.isHideSidebarCallback = { isHideSidebar() }
        service.isHideHeaderCallback = { isHideHeader() }
        service.isKeepScreenOnCallback = { isKeepScreenOn() }
        service.isStartOnBootCallback = { isStartOnBoot() }
        service.isAutoBrightnessCallback = { isAutoBrightness() }
        service.isRtspEnabledCallback = { isRtspEnabled() }
        service.isRtspSoftwareEncodingCallback = { isRtspSoftwareEncoding() }
        service.getMotionWakeModeCallback = { getMotionWakeMode() }
        service.getTextScalingCallback = { getTextScaling() }

        // Display/UI settings (setters)
        service.setHideSidebarCallback = { enabled -> setHideSidebar(enabled) }
        service.setHideHeaderCallback = { enabled -> setHideHeader(enabled) }
        service.setKeepScreenOnCallback = { enabled -> setKeepScreenOn(enabled) }
        service.setStartOnBootCallback = { enabled -> setStartOnBoot(enabled) }
        service.setAutoBrightnessCallback = { enabled -> setAutoBrightness(enabled) }
        service.setMotionWakeModeCallback = { mode -> setMotionWakeMode(mode) }
        service.setTextScalingCallback = { zoom -> setTextScaling(zoom) }
        service.setRtspEnabledCallback = { enabled -> setRtspEnabled(enabled) }
        service.setRtspSoftwareEncodingCallback = { enabled -> setRtspSoftwareEncoding(enabled) }

        // Kiosk lock
        service.lockKioskCallback = { lockKiosk() }
        service.unlockKioskCallback = { unlockKiosk() }
        service.isKioskLockedCallback = { isKioskLocked() }
        service.isLockAppExitCallback = { isLockAppExit() }
        service.isLockSettingsCallback = { isLockSettings() }

        // PIN management
        service.setPinCallback = { pin -> setPin(pin) }
        service.clearPinCallback = { clearPin() }
        service.hasPinSetCallback = { hasPinSet() }
        service.verifyPinCallback = { pin -> verifyPin(pin) }
        service.getStoredPinCallback = { getStoredPin() }
    }
}

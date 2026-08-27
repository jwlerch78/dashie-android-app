package com.dashieapp.Dashie.webview.delegates

import android.content.Context
import android.webkit.WebView
import com.dashieapp.Dashie.halite.HalitePreferences

/**
 * Thin facade over the four split sub-delegates (display, sleep, camera, system).
 *
 * **This class used to hold all settings-related JS bridge methods (~1030 lines).**
 * In Phase 7 of the settings cleanup plan, the implementation was split across
 * four domain-specific delegates. This facade remains to keep existing callers
 * (DashieJSBridge, SensorComponentWiring, MediaComponentWiring, MainBroadcastManager)
 * working unchanged — each forwarder maps to the appropriate sub-delegate.
 *
 * Future cleanup (Phase 7b): migrate callers to reference the sub-delegates
 * directly via DashieJSBridge (`displayDelegate`, `sleepDelegate`, etc.) and
 * delete this facade entirely.
 *
 * Sub-delegate ownership:
 *   - display: dashboard zoom, screensaver, layout/dash bar, display prefs, photo readback
 *   - sleep:   sleep/wake timer, screen-off behavior
 *   - camera:  video feed PiP, alert volume, RTSP, HA sensor
 *   - system:  device admin, boot, locations/chores/calendar JSON passthrough, account
 */
class JsBridgeSettingsDelegate(
    context: Context,
    webView: WebView,
    halitePrefs: () -> HalitePreferences?
) {
    val display = JsBridgeDisplayDelegate(context, webView, halitePrefs)
    val sleep = JsBridgeSleepDelegate(webView, halitePrefs, isDeviceAdminActive = { system.isDeviceAdminActive() })
    val camera = JsBridgeCameraDelegate(context, webView, halitePrefs)
    val system = JsBridgeSystemDelegate(context, webView, halitePrefs)

    // ============================================
    // Callback property forwarders
    // ============================================

    // Display
    var onScreensaverSettingsChanged: (() -> Unit)?
        get() = display.onScreensaverSettingsChanged
        set(v) { display.onScreensaverSettingsChanged = v }
    var onShowHaMediaFolderPicker: (() -> Unit)?
        get() = display.onShowHaMediaFolderPicker
        set(v) { display.onShowHaMediaFolderPicker = v }
    var onOpenMotionWakeSettings: (() -> Unit)?
        get() = display.onOpenMotionWakeSettings
        set(v) { display.onOpenMotionWakeSettings = v }
    var onLayoutEditModeChanged: ((Boolean) -> Unit)?
        get() = display.onLayoutEditModeChanged
        set(v) { display.onLayoutEditModeChanged = v }

    // Sleep
    var onSleepSettingsChanged: (() -> Unit)?
        get() = sleep.onSleepSettingsChanged
        set(v) { sleep.onSleepSettingsChanged = v }
    var onSleepNow: (() -> Unit)?
        get() = sleep.onSleepNow
        set(v) { sleep.onSleepNow = v }
    var onWakeNow: (() -> Unit)?
        get() = sleep.onWakeNow
        set(v) { sleep.onWakeNow = v }

    // Camera
    var onRtspEnabledChanged: ((Boolean) -> Unit)?
        get() = camera.onRtspEnabledChanged
        set(v) { camera.onRtspEnabledChanged = v }
    var isRtspRunning: (() -> Boolean)?
        get() = camera.isRtspRunning
        set(v) { camera.isRtspRunning = v }
    var hasRtspFailed: (() -> Boolean)?
        get() = camera.hasRtspFailed
        set(v) { camera.hasRtspFailed = v }
    var getRtspFailureReason: (() -> String?)?
        get() = camera.getRtspFailureReason
        set(v) { camera.getRtspFailureReason = v }
    var onVideoFeedConfigChanged: ((String) -> Unit)?
        get() = camera.onVideoFeedConfigChanged
        set(v) { camera.onVideoFeedConfigChanged = v }
    var onHaSensorConfigChanged: (() -> Unit)?
        get() = camera.onHaSensorConfigChanged
        set(v) { camera.onHaSensorConfigChanged = v }
    var onPreviewVideoFeedChime: ((String) -> Unit)?
        get() = camera.onPreviewVideoFeedChime
        set(v) { camera.onPreviewVideoFeedChime = v }
    var onAlertVolumeChanged: ((Float) -> Unit)?
        get() = camera.onAlertVolumeChanged
        set(v) { camera.onAlertVolumeChanged = v }

    // System (onRequestDeviceAdmin is shared between sleep + system; keep both in sync)
    var onRequestDeviceAdmin: (() -> Unit)?
        get() = system.onRequestDeviceAdmin
        set(v) {
            system.onRequestDeviceAdmin = v
            sleep.onRequestDeviceAdmin = v
        }
    var onReturnHomeTimeoutChanged: ((Int) -> Unit)?
        get() = system.onReturnHomeTimeoutChanged
        set(v) { system.onReturnHomeTimeoutChanged = v }

    // ============================================
    // Method forwarders
    // ============================================

    // ---- Display ----
    fun getDashboardZoom() = display.getDashboardZoom()
    fun getWidgetZoom() = display.getWidgetZoom()
    fun setWidgetZoom(zoom: Int) = display.setWidgetZoom(zoom)
    fun setDashboardZoom(zoom: Int) = display.setDashboardZoom(zoom)
    fun getScreensaverDescription() = display.getScreensaverDescription()
    fun getScreensaverSettings() = display.getScreensaverSettings()
    fun setScreensaverTimeout(seconds: Int) = display.setScreensaverTimeout(seconds)
    fun setScreensaverMode(mode: String) = display.setScreensaverMode(mode)
    fun setDimBrightness(percent: Int) = display.setDimBrightness(percent)
    fun setScreensaverShowClock(enabled: Boolean) = display.setScreensaverShowClock(enabled)
    fun setScreensaverShowDate(enabled: Boolean) = display.setScreensaverShowDate(enabled)
    fun setReduceBrightnessOnBlack(enabled: Boolean) = display.setReduceBrightnessOnBlack(enabled)
    fun setClockPosition(position: String) = display.setClockPosition(position)
    fun setClockSize(size: String) = display.setClockSize(size)
    fun setClockFontSize(pt: Int) = display.setClockFontSize(pt)
    fun setSlideshowInterval(seconds: Int) = display.setSlideshowInterval(seconds)
    fun setScreensaverShowMetadata(enabled: Boolean) = display.setScreensaverShowMetadata(enabled)
    fun setPhotoSourceType(type: String) = display.setPhotoSourceType(type)
    fun setHaMediaFolder(folder: String) = display.setHaMediaFolder(folder)
    fun setUnsplashQuery(query: String) = display.setUnsplashQuery(query)
    fun setUnsplashArtistHyperlinks(enabled: Boolean) = display.setUnsplashArtistHyperlinks(enabled)
    fun setWeatherOverlayEnabled(enabled: String) = display.setWeatherOverlayEnabled(enabled)
    fun setWeatherOverlayEnabledBool(enabled: Boolean) = display.setWeatherOverlayEnabledBool(enabled)
    fun setWeatherEntityId(entityId: String) = display.setWeatherEntityId(entityId)
    fun setAnimationsEnabled(enabled: Boolean) = display.setAnimationsEnabled(enabled)
    fun setAnimationLevel(level: String) = display.setAnimationLevel(level)
    fun setShowUiTips(enabled: Boolean) = display.setShowUiTips(enabled)
    fun setThemeFamily(family: String) = display.setThemeFamily(family)
    fun setScreensaverHaPagePath(path: String) = display.setScreensaverHaPagePath(path)
    fun setSlideshowTransition(transition: String) = display.setSlideshowTransition(transition)
    fun setSlideshowShuffle(enabled: Boolean) = display.setSlideshowShuffle(enabled)
    fun setPhotoFit(mode: String) = display.setPhotoFit(mode)
    fun setScreensaverApp(packageName: String, label: String) = display.setScreensaverApp(packageName, label)
    fun getInstalledApps() = display.getInstalledApps()
    fun getMotionWakeDescription() = display.getMotionWakeDescription()
    fun getMotionWakeMode() = display.getMotionWakeMode()
    fun getSidebarIconSize() = display.getSidebarIconSize()
    fun showHaMediaFolderPicker() = display.showHaMediaFolderPicker()
    fun openMotionWakeSettings() = display.openMotionWakeSettings()
    fun getLayoutDescription() = display.getLayoutDescription()
    fun getLayoutMode() = display.getLayoutMode()
    fun setLayoutEditMode(enabled: Boolean) = display.setLayoutEditMode(enabled)
    fun isDashMenuEnabled() = display.isDashMenuEnabled()
    fun setDashMenuEnabled(enabled: Boolean) = display.setDashMenuEnabled(enabled)
    fun getUse24HourClock() = display.getUse24HourClock()
    fun setUse24HourClock(enabled: Boolean) = display.setUse24HourClock(enabled)
    fun getDateFormat() = display.getDateFormat()
    fun setDateFormat(format: String) = display.setDateFormat(format)
    fun getTemperatureUnit() = display.getTemperatureUnit()
    fun setTemperatureUnit(unit: String) = display.setTemperatureUnit(unit)
    fun setZipCode(zipCode: String) = display.setZipCode(zipCode)
    fun getZipCode() = display.getZipCode()
    fun setWeatherCoordinates(latitude: Double, longitude: Double, locationKey: String) =
        display.setWeatherCoordinates(latitude, longitude, locationKey)
    fun getLanguage() = display.getLanguage()
    fun getUseHaForWeather() = display.getUseHaForWeather()
    fun getUseHaForTime() = display.getUseHaForTime()
    fun setUseHaForWeather(enabled: Boolean) = display.setUseHaForWeather(enabled)
    fun setUseHaForTime(enabled: Boolean) = display.setUseHaForTime(enabled)
    fun setResolvedTimezone(timezone: String) = display.setResolvedTimezone(timezone)
    fun getWidgetFontSize() = display.getWidgetFontSize()
    fun getDisplaySize() = display.getDisplaySize()
    fun setWidgetFontSize(size: Int) = display.setWidgetFontSize(size)
    fun setDisplaySize(size: Int) = display.setDisplaySize(size)
    fun getWeatherEntityId() = display.getWeatherEntityId()
    fun getWeatherOverlayEnabled() = display.getWeatherOverlayEnabled()
    fun getPhotoSettings() = display.getPhotoSettings()

    // ---- Sleep ----
    fun getScreenOffBehavior() = sleep.getScreenOffBehavior()
    fun setScreenOffBehavior(behavior: String) = sleep.setScreenOffBehavior(behavior)
    fun getSleepSettings() = sleep.getSleepSettings()
    fun getSleepDescription() = sleep.getSleepDescription()
    fun setSleepEnabled(enabled: Boolean) = sleep.setSleepEnabled(enabled)
    fun setSleepMethod(method: String) = sleep.setSleepMethod(method)
    fun setSleepTime(time: String) = sleep.setSleepTime(time)
    fun setWakeTime(time: String) = sleep.setWakeTime(time)
    fun setResleepTimeout(minutes: Int) = sleep.setResleepTimeout(minutes)
    fun setInactivityTimeout(seconds: Int) = sleep.setInactivityTimeout(seconds)
    fun setMotionWakeForSleep(enabled: Boolean) = sleep.setMotionWakeForSleep(enabled)
    fun getMotionWakeForSleep() = sleep.getMotionWakeForSleep()
    fun setSleepShowClock(enabled: Boolean) = sleep.setSleepShowClock(enabled)
    fun getSleepShowClock() = sleep.getSleepShowClock()
    fun setReduceBrightnessOnSleep(enabled: Boolean) = sleep.setReduceBrightnessOnSleep(enabled)
    fun sleepNow() = sleep.sleepNow()
    fun wakeNow() = sleep.wakeNow()

    // ---- Camera ----
    fun getVideoFeedSettings() = camera.getVideoFeedSettings()
    fun saveVideoFeedConfig(configJson: String) = camera.saveVideoFeedConfig(configJson)
    fun previewVideoFeedChime(soundName: String) = camera.previewVideoFeedChime(soundName)
    fun getAlertVolume() = camera.getAlertVolume()
    fun setAlertVolume(percent: Int) = camera.setAlertVolume(percent)
    fun getRtspSettings() = camera.getRtspSettings()
    fun getEncoderCapabilities() = camera.getEncoderCapabilities()
    fun setRtspEnabled(enabled: Boolean) = camera.setRtspEnabled(enabled)
    fun setRtspResolution(resolution: String) = camera.setRtspResolution(resolution)
    fun setRtspCustomWidth(width: Int) = camera.setRtspCustomWidth(width)
    fun setRtspCustomHeight(height: Int) = camera.setRtspCustomHeight(height)
    fun setRtspFps(fps: Int) = camera.setRtspFps(fps)
    fun setRtspSoftwareEncoding(enabled: Boolean) = camera.setRtspSoftwareEncoding(enabled)
    fun setRtspDisableMirrorCorrection(enabled: Boolean) = camera.setRtspDisableMirrorCorrection(enabled)
    fun setHaSensorEnabled(enabled: Boolean) = camera.setHaSensorEnabled(enabled)
    fun setHaSensorMotionEnabled(enabled: Boolean) = camera.setHaSensorMotionEnabled(enabled)
    fun setHaSensorFaceEnabled(enabled: Boolean) = camera.setHaSensorFaceEnabled(enabled)

    // ---- System ----
    fun isDeviceAdminActive() = system.isDeviceAdminActive()
    fun requestDeviceAdmin() = system.requestDeviceAdmin()
    fun setReturnHomeTimeout(seconds: Int) = system.setReturnHomeTimeout(seconds)
    fun setMemoryRecoveryEnabled(enabled: Boolean) = system.setMemoryRecoveryEnabled(enabled)
    fun getBootSettings() = system.getBootSettings()
    fun setStartOnBoot(enabled: Boolean) = system.setStartOnBoot(enabled)
    fun setAutoReloadOnCrash(enabled: Boolean) = system.setAutoReloadOnCrash(enabled)
    fun setPinAppWhenLocked(enabled: Boolean) = system.setPinAppWhenLocked(enabled)
    fun isDeviceOwnerApp() = system.isDeviceOwnerApp()
    fun relinquishDeviceOwner() = system.relinquishDeviceOwner()
    fun showRelinquishDeviceOwnerConfirmation() = system.showRelinquishDeviceOwnerConfirmation()
    fun getLocationsSettings() = system.getLocationsSettings()
    fun setLocationsSettings(json: String) = system.setLocationsSettings(json)
    fun getChoresRewardsSettings() = system.getChoresRewardsSettings()
    fun setChoresRewardsSettings(json: String) = system.setChoresRewardsSettings(json)
    fun getCalendarSettings() = system.getCalendarSettings()
    fun setCalendarSettings(json: String) = system.setCalendarSettings(json)
    fun onDashieAuthComplete(email: String) = system.onDashieAuthComplete(email)
    fun returnToKioskAfterSignIn(email: String) = system.returnToKioskAfterSignIn(email)
    fun onDashieSignIn(mode: String = "") = system.onDashieSignIn(mode)
    fun requestHaSetup() = system.requestHaSetup()
    fun getPendingHaSetup() = system.getPendingHaSetup()
    fun clearPendingHaSetup() = system.clearPendingHaSetup()
    fun setHaEnabled(enabled: Boolean) = system.setHaEnabled(enabled)
    fun setLayoutMode(mode: String) = system.setLayoutMode(mode)
    fun setPermissionPromptDeclined(declined: Boolean) = system.setPermissionPromptDeclined(declined)
    fun clearAccountDataLocal() = system.clearAccountDataLocal()
    fun onAccountDeleted() = system.onAccountDeleted()
    fun showAccountDeleteProgress() = system.showAccountDeleteProgress()
    fun dismissAccountDeleteProgress() = system.dismissAccountDeleteProgress()
    fun showAccountDeleteResult(success: Boolean, message: String) =
        system.showAccountDeleteResult(success, message)
    fun onDashieSignOut() = system.onDashieSignOut()
    fun isDashieAccountLinked() = system.isDashieAccountLinked()
    fun getDashieAccountEmail() = system.getDashieAccountEmail()
    fun getAllDeviceSettings() = system.getAllDeviceSettings()
    fun onSettingsDump(json: String) = system.onSettingsDump(json)
}

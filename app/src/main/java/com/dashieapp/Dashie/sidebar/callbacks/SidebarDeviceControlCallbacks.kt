package com.dashieapp.Dashie.sidebar.callbacks

import android.app.Activity
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.MainUrlHandler
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.devicecontrols.DeviceControlsCoordinator
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaliteScreenController
import com.dashieapp.Dashie.halite.NativeDialogHost
import com.dashieapp.Dashie.halite.voice.HaliteVoiceController
import com.dashieapp.Dashie.sidebar.NativeSidebarController

class SidebarDeviceControlCallbacks(
    private val activity: Activity,
    private val deviceControlsProvider: () -> DeviceControlsCoordinator,
    private val halitePrefsProvider: () -> HalitePreferences?,
    private val screenControllerProvider: () -> HaliteScreenController?,
    private val dialogHostProvider: () -> NativeDialogHost?,
    private val voiceControllerProvider: () -> HaliteVoiceController?,
    private val controlCenterShow: () -> Unit,
    private val sidebarRefreshTheme: (Boolean) -> Unit,
    private val webViewProvider: () -> WebView,
    private val urlHandlerProvider: () -> MainUrlHandler?,
    // Fires synchronously before any reload triggered from the sidebar
    // hamburger so native widgets / sidebar backdrop / fullscreen tokens
    // hide instantly. Wired by MainActivity to
    // FullscreenModeManager.notifyPageUnloading().
    private val notifyReloadStarting: () -> Unit = {},
    private val runOnUiThread: (Runnable) -> Unit
) : NativeSidebarController.DeviceControlCallbacks {

    // Debounce rapid dark-mode toggle presses. Two presses within DARK_TOGGLE_DEBOUNCE_MS
    // can race: the first push's onColorSchemeChanged → applyThemeFromNative → palette
    // push hasn't finished before the second toggleDarkMode fires, leading to an
    // oscillation as Kotlin and JS each chase the other's stale dark-mode state.
    // Guard at the entry point so both call paths (TV direct + popout slider) are
    // covered.
    private var lastDarkToggleAt: Long = 0L
    private val DARK_TOGGLE_DEBOUNCE_MS = 2000L  // Theme switch is visually disruptive; give it time to settle.

    override fun sleepNow() {
        runOnUiThread { screenControllerProvider()?.screenOff() }
    }

    override fun reloadWebView() {
        Log.i("FullscreenMode", "SidebarDeviceControlCallbacks.reloadWebView() invoked from hamburger")
        runOnUiThread {
            Log.i("FullscreenMode", "reloadWebView UI-thread tick — calling notifyReloadStarting()")
            notifyReloadStarting()
            Log.i("FullscreenMode", "notifyReloadStarting() returned — kicking off URL load")
            val urlHandler = urlHandlerProvider()
            if (urlHandler != null) {
                val dashboardUrl = urlHandler.determineInitialUrl()
                Log.i("FullscreenMode", "  → loadUrlWithWsProxyIfNeeded($dashboardUrl)")
                urlHandler.loadUrlWithWsProxyIfNeeded(dashboardUrl)
            } else {
                Log.i("FullscreenMode", "  → webView.reload() (no urlHandler)")
                com.dashieapp.Dashie.halite.diagnostics.PersistentLog.info(
                    "REFRESH", "WebView reload: fullscreen-mode toggle (no urlHandler)"
                )
                webViewProvider().reload()
            }
        }
    }

    override fun exitApp() {
        runOnUiThread { dialogHostProvider()?.showExitConfirmationFromBridge() }
    }

    override fun openControlCenter() {
        runOnUiThread { controlCenterShow() }
    }

    // Volume
    /** Optional callback to sync sidebar volume changes with the music player coordinator. */
    var onMusicVolumeChanged: ((volumeScale0to10: Int) -> Unit)? = null
    override fun getVolume(): Int = deviceControlsProvider().getVolume()
    override fun setVolume(level: Int) {
        deviceControlsProvider().setVolume(level)
        onMusicVolumeChanged?.invoke(level)
    }
    override fun isMuted(): Boolean = deviceControlsProvider().isMuted()
    override fun toggleMute(): Boolean {
        val result = deviceControlsProvider().toggleMute()
        // After toggle, report new volume (0 if muted, current if unmuted)
        val newVol = if (deviceControlsProvider().isMuted()) 0 else deviceControlsProvider().getVolume()
        onMusicVolumeChanged?.invoke(newVol)
        return result
    }
    override fun isMicMuted(): Boolean = halitePrefsProvider()?.voice?.micMuted ?: false
    override fun toggleMicMute() {
        val newMuted = !(halitePrefsProvider()?.voice?.micMuted ?: false)
        voiceControllerProvider()?.setMicMuted(newMuted)
    }

    // Brightness
    override fun getBrightness(): Int = deviceControlsProvider().getBrightnessPercent()
    override fun setBrightness(level: Int) {
        deviceControlsProvider().brightnessManager.setBrightnessPercent(level)
    }
    override fun canWriteBrightnessSettings(): Boolean =
        deviceControlsProvider().brightnessManager.canWriteSettings()
    override fun requestBrightnessPermission() {
        runOnUiThread {
            deviceControlsProvider().brightnessManager.requestWriteSettingsPermission()
        }
    }

    override fun isDarkMode(): Boolean = deviceControlsProvider().isSystemDarkMode()

    override fun toggleDarkMode() {
        val now = android.os.SystemClock.elapsedRealtime()
        val sinceLast = now - lastDarkToggleAt
        if (sinceLast < DARK_TOGGLE_DEBOUNCE_MS) {
            Log.i("NativeSidebar", "🌓 toggleDarkMode: debounced (${sinceLast}ms since last toggle, threshold ${DARK_TOGGLE_DEBOUNCE_MS}ms)")
            return
        }
        lastDarkToggleAt = now

        val dc = deviceControlsProvider()
        val isDark = dc.isSystemDarkMode()
        val newDark = !isDark
        Log.i("NativeSidebar", "🌓 toggleDarkMode: $isDark → $newDark")
        dc.setSystemDarkMode(newDark)
        val darkBool = if (newDark) "true" else "false"
        try {
            val webView = webViewProvider()
            webView.post {
                Log.i("NativeSidebar", "🌓 toggleDarkMode: calling window.onColorSchemeChanged($darkBool)")
                webView.evaluateJavascript(
                    "if(window.onColorSchemeChanged) window.onColorSchemeChanged($darkBool); else console.warn('onColorSchemeChanged not defined');",
                    { result -> Log.i("NativeSidebar", "🌓 toggleDarkMode: onColorSchemeChanged result=$result") }
                )
            }
        } catch (e: Exception) {
            Log.w("NativeSidebar", "🌓 Dark mode WebView sync failed: ${e.message}")
        }
        runOnUiThread { sidebarRefreshTheme(newDark) }
    }

    override fun canWriteSecureSettings(): Boolean =
        deviceControlsProvider().canWriteSecureSettings()

    override fun showDarkModePermissionDialog() {
        runOnUiThread {
            val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)
            dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle).text = "Dark Mode Control"
            dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage).text =
                "Dark mode control requires special permission that must be granted via ADB.\n\n" +
                "Run this command via ADB:\n" +
                "adb shell pm grant ${activity.packageName} android.permission.WRITE_SECURE_SETTINGS"
            dialogView.findViewById<android.widget.Button>(R.id.buttonNegative).visibility = android.view.View.GONE
            dialogView.findViewById<android.widget.Button>(R.id.buttonPositive).text = "OK"
            val dialog = android.app.AlertDialog.Builder(activity)
                .setView(dialogView)
                .setCancelable(true)
                .create()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialogView.findViewById<android.widget.Button>(R.id.buttonPositive).setOnClickListener {
                dialog.dismiss()
            }
            dialog.show()
            com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
            com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.setDefaultFocusOnConfirm(dialogView)
        }
    }
}

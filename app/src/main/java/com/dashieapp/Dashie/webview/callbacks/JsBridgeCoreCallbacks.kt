package com.dashieapp.Dashie.webview.callbacks

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.MotionEvent
import android.webkit.WebView
import com.dashieapp.Dashie.MainDarkModeHandler
import com.dashieapp.Dashie.MainKioskController
import com.dashieapp.Dashie.MainLifecycleHandler
import com.dashieapp.Dashie.MainVoiceSetup
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.orientation.BottomBarController
import com.dashieapp.Dashie.sidebar.NativeSidebarController
import com.dashieapp.Dashie.util.DeviceInfoHelper
import com.dashieapp.Dashie.webview.DashieJSBridge

class JsBridgeCoreCallbacks(
    private val context: Context,
    private val kioskControllerProvider: () -> MainKioskController?,
    private val lifecycleHandlerProvider: () -> MainLifecycleHandler?,
    private val voiceSetupProvider: () -> MainVoiceSetup?,
    private val darkModeHandlerProvider: () -> MainDarkModeHandler?,
    private val sidebarProvider: () -> NativeSidebarController?,
    private val bottomBarProvider: () -> BottomBarController?,
    private val registryProvider: () -> HaliteComponentRegistry?,
    private val halitePrefsProvider: () -> HalitePreferences?,
    private val webViewProvider: () -> WebView,
    private val runOnUiThread: (Runnable) -> Unit
) : DashieJSBridge.CoreCallbacks {

    override fun onExitApp() { kioskControllerProvider()?.exitAppSafely() }
    override fun onRestartApp() { lifecycleHandlerProvider()?.performRestartApp() }
    override fun onSoftRestartApp() { lifecycleHandlerProvider()?.performSoftRestartApp() }
    override fun onRecordWakeWordSample() { voiceSetupProvider()?.handleRecordWakeWordSample() }
    override fun isTabletDevice(): Boolean = DeviceInfoHelper.isTablet(context)
    override fun enableImmersiveMode() { kioskControllerProvider()?.enableImmersiveMode() }
    override fun disableImmersiveMode() { kioskControllerProvider()?.disableImmersiveMode() }
    override fun isKioskModeEnabled(): Boolean = kioskControllerProvider()?.checkKioskModeEnabled() ?: false

    override fun openWifiSetup() {
        context.startActivity(
            Intent(context, com.dashieapp.Dashie.wifi.WifiSetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun setDarkMode(isDark: Boolean) {
        darkModeHandlerProvider()?.setDarkMode(isDark)
        runOnUiThread { sidebarProvider()?.refreshTheme(isDark) }
    }

    override fun onForceKioskModeChanged(enabled: Boolean) {
        Log.i("DashieJSBridge", "🔄 Force kiosk mode changed: enabled=$enabled — restarting activity")
        if (enabled) {
            // Clear view switcher icons before restart — kiosk mode has no dashboard views
            runOnUiThread { sidebarProvider()?.setEnabledViews(emptyList()) }
        }
        lifecycleHandlerProvider()?.performSoftRestartApp()
    }

    override fun focusSidebar() {
        runOnUiThread { sidebarProvider()?.focusSidebar() }
    }

    override fun focusBottomBar() {
        runOnUiThread { bottomBarProvider()?.focusBottomBar() }
    }

    override fun setFocusRingBounds(x: Int, y: Int, w: Int, h: Int, state: String) {
        registryProvider()?.focusRingManager?.setBounds(x, y, w, h, state)
    }

    override fun hideFocusRing() {
        registryProvider()?.focusRingManager?.hide()
    }

    override fun enterNativeWidget(widgetId: String) {
        val registry = registryProvider() ?: return
        val widget = when (widgetId) {
            "photos" -> registry.photoWidgetController
            else -> null
        }
        if (widget == null) {
            Log.w("DashieJSBridge", "enterNativeWidget: no native widget for id=$widgetId")
            return
        }
        runOnUiThread { registry.nativeWidgetFocusManager.activate(widgetId, widget) }
    }

    override fun releaseNativeWidget() {
        val registry = registryProvider() ?: return
        runOnUiThread { registry.nativeWidgetFocusManager.deactivate(notifyJs = true) }
    }

    override fun areUiTipsEnabled(): Boolean =
        halitePrefsProvider()?.display?.showUiTips ?: true

    override fun onInjectTouch(x: Float, y: Float) {
        Log.d("DashieJSBridge", "👆 onInjectTouch($x, $y) - injecting native touch into WebView")
        runOnUiThread {
            val webView = webViewProvider()
            val downTime = android.os.SystemClock.uptimeMillis()
            val eventTime = android.os.SystemClock.uptimeMillis()
            val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0)
            val upEvent = MotionEvent.obtain(downTime, eventTime + 50, MotionEvent.ACTION_UP, x, y, 0)
            webView.dispatchTouchEvent(downEvent)
            webView.dispatchTouchEvent(upEvent)
            downEvent.recycle()
            upEvent.recycle()
        }
    }
}

package com.dashieapp.Dashie.webview.callbacks

import android.app.Activity
import android.webkit.WebView
import com.dashieapp.Dashie.MainPermissionDelegate
import com.dashieapp.Dashie.MainServiceManager
import com.dashieapp.Dashie.MainUrlHandler
import com.dashieapp.Dashie.api.DashieServiceManager
import com.dashieapp.Dashie.halite.DashieWebViewClient
import com.dashieapp.Dashie.halite.LockGate
import com.dashieapp.Dashie.halite.sidebar.dialogs.LockDialogs
import com.dashieapp.Dashie.webview.DashieJSBridge

class JsBridgeNavigationCallbacks(
    private val haLoginLauncher: (String) -> Unit,
    private val controlCenterShow: () -> Unit,
    private val webViewProvider: () -> WebView,
    private val serviceManagerProvider: () -> MainServiceManager?,
    private val dashieServiceManagerProvider: () -> DashieServiceManager?,
    private val permissionDelegateProvider: () -> MainPermissionDelegate,
    private val urlHandlerProvider: () -> MainUrlHandler?,
    private val nativeSettingsPageLauncher: (String) -> Unit,
    private val webViewClientProvider: () -> DashieWebViewClient?,
    private val runOnUiThread: (Runnable) -> Unit,
    /** Kiosk-lock settings gate (LockGate.requirePin) on the two settings
     *  funnels below — these can route to the WebView's JS settings drawer,
     *  which the SettingsActivity on-entry guard doesn't cover. */
    private val activityProvider: () -> Activity,
    private val lockDialogsProvider: () -> LockDialogs?
) : DashieJSBridge.NavigationCallbacks {

    override fun openHaLogin(haUrl: String) {
        haLoginLauncher(haUrl)
    }

    override fun onReloadDashboard() {
        runOnUiThread {
            val urlHandler = urlHandlerProvider()
            if (urlHandler != null) {
                val dashboardUrl = urlHandler.determineInitialUrl()
                urlHandler.loadUrlWithWsProxyIfNeeded(dashboardUrl)
            } else {
                // Fallback: simple reload if urlHandler not available
                com.dashieapp.Dashie.halite.diagnostics.PersistentLog.info(
                    "REFRESH", "WebView reload: JS reloadDashboard (no urlHandler)"
                )
                webViewProvider().reload()
            }
        }
    }

    override fun onOpenSettings() {
        // Launch the native SettingsActivity at its root. The old JS route
        // (window.openSettingsToPage) died with the kiosk settings bundle (2026-03) —
        // this eval'd a global no tree defines, so "open settings" silently no-oped.
        runOnUiThread {
            LockGate.requirePin(activityProvider(), lockDialogsProvider()) {
                nativeSettingsPageLauncher("")
            }
        }
    }

    override fun onOpenSettingsPage(pageId: String) {
        // Launch native Kotlin SettingsActivity directly to the specified page
        // (uses the same "navigate_to" Intent extra that control-center routing
        // already uses). Called from JS focus-mode flows where the user picks
        // a settings entry — Settings opens above the dashboard, then on Back
        // returns control to the originating widget.
        runOnUiThread {
            LockGate.requirePin(activityProvider(), lockDialogsProvider()) {
                nativeSettingsPageLauncher(pageId)
            }
        }
    }

    override fun onOpenControlCenter() {
        runOnUiThread { controlCenterShow() }
    }

    override fun onOpenDrawer() {
        // Legacy native sidebar open removed — JS dash menu is the sidebar
    }

    override fun onRtspEnabledChanged(enabled: Boolean) {
        permissionDelegateProvider().handleRtspEnabledChange(enabled)
    }

    override fun onApiEnabledChanged(enabled: Boolean) {
        serviceManagerProvider()?.handleApiEnabledChange(enabled)
    }

    override fun onRequestDeviceAdmin() {
        serviceManagerProvider()?.handleRequestDeviceAdmin()
    }

    override fun isRtspRunning(): Boolean =
        dashieServiceManagerProvider()?.isRtspServerRunning() ?: false

    override fun hasRtspFailed(): Boolean =
        dashieServiceManagerProvider()?.hasRtspFailed() ?: false

    override fun getRtspFailureReason(): String? =
        dashieServiceManagerProvider()?.getRtspFailureReason()

    override fun onHaUrlChanged(url: String) {
        dashieServiceManagerProvider()?.setCurrentUrl(url)
    }

    override fun onHaIframeLoaded() {
        // Event-driven injection of post-HA-load scripts (WS monitor,
        // telemetry, music) — fired by kiosk-shell.js when the HA iframe
        // actually finished loading. Replaces the old blind 8s timer.
        runOnUiThread {
            webViewClientProvider()?.injectPostHaLoadScripts(webViewProvider())
        }
    }
}

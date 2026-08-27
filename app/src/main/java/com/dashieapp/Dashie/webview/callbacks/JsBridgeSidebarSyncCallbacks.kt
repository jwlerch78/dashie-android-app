package com.dashieapp.Dashie.webview.callbacks

import com.dashieapp.Dashie.sidebar.NativeSidebarController
import com.dashieapp.Dashie.webview.DashieJSBridge

class JsBridgeSidebarSyncCallbacks(
    private val sidebarProvider: () -> NativeSidebarController?,
    private val runOnUiThread: (Runnable) -> Unit
) : DashieJSBridge.SidebarSyncCallbacks {

    override fun closeSidebar() {
        // Legacy native sidebar removed — JS handles its own close
    }

    override fun dismissNativeSidebar() {
        runOnUiThread { sidebarProvider()?.dismiss() }
    }

    override fun revealNativeSidebar() {
        runOnUiThread { sidebarProvider()?.revealForOnboardingTip() }
    }

    override fun stopSidebarAutoHide() {
        runOnUiThread { sidebarProvider()?.stopAutoHide() }
    }

    override fun openHamburgerPopout() {
        runOnUiThread { sidebarProvider()?.revealWithHamburgerPopoutForTip() }
    }

    override fun getSidebarHamburgerY(): Int =
        sidebarProvider()?.getHamburgerButtonScreenY() ?: -1

    override fun getControlCenterItemBounds(): String =
        sidebarProvider()?.getControlCenterItemBounds() ?: "{}"

    override fun onDashBarPinChanged(pinned: Boolean) {
        runOnUiThread { sidebarProvider()?.onPinChanged(pinned) }
    }

    override fun onSetEnabledViews(viewIds: List<String>) {
        runOnUiThread { sidebarProvider()?.setEnabledViews(viewIds) }
    }

    override fun onSetActiveView(viewId: String) {
        runOnUiThread { sidebarProvider()?.setActiveView(viewId) }
    }

    override fun onSetSidebarAccentColor(color: String) {
        runOnUiThread { sidebarProvider()?.setThemeColors(color) }
    }
}

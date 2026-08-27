package com.dashieapp.Dashie.sidebar.callbacks

import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.NativeDialogHost
import com.dashieapp.Dashie.sidebar.NativeSidebarController

class SidebarLockCallbacks(
    private val halitePrefsProvider: () -> HalitePreferences?,
    private val dialogHostProvider: () -> NativeDialogHost?
) : NativeSidebarController.LockCallbacks {

    override fun isAppLocked(): Boolean = halitePrefsProvider()?.lock?.isLocked ?: false
    override fun showLockDialog() { dialogHostProvider()?.showLockDialogFromBridge() }
    override fun showUnlockDialog() { dialogHostProvider()?.showLockDialogFromBridge() }
    override fun showPinRecoveryDialog() { dialogHostProvider()?.showPinRecoveryDialogFromBridge() }
}

package com.dashieapp.Dashie.sidebar.callbacks

import android.webkit.WebView
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaliteScreenController
import com.dashieapp.Dashie.sidebar.NativeSidebarController

class SidebarStateCallbacks(
    private val halitePrefsProvider: () -> HalitePreferences?,
    private val screenControllerProvider: () -> HaliteScreenController?,
    private val webViewProvider: () -> WebView? = { null }
) : NativeSidebarController.StateCallbacks {

    override fun isDashBarPinned(): Boolean = halitePrefsProvider()?.display?.dashMenuPinned ?: true

    override fun setDashBarPinned(pinned: Boolean) {
        halitePrefsProvider()?.display?.dashMenuPinned = pinned
    }

    override fun isSidebarEnabled(): Boolean {
        val prefs = halitePrefsProvider() ?: return true
        // Hide the sidebar entirely when no Dashie account is linked AND HA
        // setup hasn't been completed — fresh-install / post-delete / signed-
        // out state where the WebView is showing the Dashie login screen.
        // The sidebar icons (HA controls, hamburger) have nothing to act on
        // and they overlap the login card. clearAccountDataLocal resets
        // isSetupComplete to false on delete, so this catches both first-run
        // and post-delete cases. Once the user links a Dashie account OR
        // completes HA setup, dashMenuEnabled (default true) takes over.
        if (!prefs.account.isLinked && !prefs.connection.isSetupComplete) {
            return false
        }
        return prefs.display.dashMenuEnabled
    }

    override fun onSidebarDismissed() {
        screenControllerProvider()?.screenDimmer?.resetTimer()
        screenControllerProvider()?.resetSleepInactivityTimer()
        // Notify JS so onboarding tips can auto-dismiss when the sidebar closes
        webViewProvider()?.post {
            webViewProvider()?.evaluateJavascript(
                // WEBAPP-EXEMPT: dashieOnSidebarDismissed — kiosk-overlay JS sidebar; full mode uses the native sidebar
                "if(window.dashieOnSidebarDismissed) window.dashieOnSidebarDismissed();",
                null
            )
        }
    }
}

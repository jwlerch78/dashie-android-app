package com.dashieapp.Dashie.sidebar.callbacks

import android.app.Activity
import android.webkit.WebView
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.registry.hideMusicPlayer
import com.dashieapp.Dashie.halite.registry.isMusicPlayerVisible
import com.dashieapp.Dashie.halite.registry.showMusicPlayer
import com.dashieapp.Dashie.sidebar.NativeSidebarController

class SidebarMusicNavigationCallbacks(
    private val registryProvider: () -> HaliteComponentRegistry?,
    private val webViewProvider: () -> WebView,
    private val runOnUiThread: (Runnable) -> Unit,
    /** Activity used to launch the HA settings page. Lambda so the
     *  callback works across activity recreations. */
    private val activityProvider: () -> Activity? = { null },
    /** Standard settings page launcher (same path CC card clicks use).
     *  Wired by MainActivity to settingsActivityLauncher.launch(intent). */
    private val openSettingsPage: (String) -> Unit = { _ -> }
) : NativeSidebarController.MusicNavigationCallbacks {

    override fun isMusicEnabled(): Boolean {
        val prefs = registryProvider()?.prefs ?: return false
        // Speaker-only mode: no player UI, hide sidebar music icon
        if (prefs.connection.musicSpeakerOnly) return false
        return prefs.connection.musicPlayerEnabled
    }

    override fun onToggleMusicPlayer() {
        runOnUiThread {
            if (registryProvider()?.isMusicPlayerVisible() == true) {
                registryProvider()?.hideMusicPlayer()
            } else {
                registryProvider()?.showMusicPlayer()
            }
        }
    }

    override fun switchDashboardView(viewId: String) {
        val webView = webViewProvider()
        webView.post {
            webView.evaluateJavascript(
                "if(typeof PageManager!=='undefined')PageManager.switchToView('$viewId')",
                null
            )
        }
    }

    override fun isHaNeedsLogin(): Boolean {
        val conn = registryProvider()?.prefs?.connection ?: return false
        // Show the warning whenever HA is enabled but not fully usable —
        // either no URL set / placeholder URL (needs URL setup) or URL
        // set but no access token (needs login). The user fixes either
        // from the standard HA settings page.
        if (!conn.haEnabled) return false
        val needsUrl = conn.haUrl.isEmpty()
            || conn.haUrl == com.dashieapp.Dashie.halite.HalitePreferences.DEFAULT_HA_URL
        if (needsUrl) return true
        return !conn.hasHaAccessToken
    }

    override fun onHaLoginRequested() {
        runOnUiThread {
            // Route through the standard settings launcher — same path
            // CC card clicks use. The HA settings page schema renders a
            // "Sign-in Required" section + the existing "Dashboard URL"
            // navigation, so the user can fix either issue from there.
            openSettingsPage("home_assistant")
        }
    }
}

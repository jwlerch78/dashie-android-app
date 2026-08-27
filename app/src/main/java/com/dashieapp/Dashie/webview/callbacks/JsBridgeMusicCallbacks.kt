package com.dashieapp.Dashie.webview.callbacks

import android.util.Log
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.registry.hideMusicPlayer
import com.dashieapp.Dashie.halite.registry.isMusicPlayerVisible
import com.dashieapp.Dashie.halite.registry.showMusicPlayer
import com.dashieapp.Dashie.webview.DashieJSBridge

class JsBridgeMusicCallbacks(
    private val registryProvider: () -> HaliteComponentRegistry?,
    private val runOnUiThread: (Runnable) -> Unit
) : DashieJSBridge.MusicCallbacks {

    override fun onToggleMusicPlayer() {
        runOnUiThread {
            if (registryProvider()?.isMusicPlayerVisible() == true) {
                registryProvider()?.hideMusicPlayer()
            } else {
                registryProvider()?.showMusicPlayer()
            }
        }
    }

    override fun isMusicPlayerVisible(): Boolean =
        registryProvider()?.isMusicPlayerVisible() ?: false

    override fun isMusicPlayerEnabled(): Boolean =
        registryProvider()?.prefs?.connection?.musicPlayerEnabled ?: false

    override fun onMusicPlayerEnabledChanged(enabled: Boolean) {
        Log.i("DashieJSBridge", "🎵 Music player enabled changed from settings: $enabled")
        runOnUiThread {
            if (!enabled) registryProvider()?.hideMusicPlayer()
            registryProvider()?.onMusicPlayerEnabledChanged?.invoke(enabled)
        }
    }

    override fun onMusicPlayerEntityChanged(entityId: String) {
        Log.i("DashieJSBridge", "🎵 Music player entity changed from settings: $entityId")
    }

    override fun onMusicPlayerShowWithScreensaverChanged(enabled: Boolean) {
        Log.i("DashieJSBridge", "🎵 Music player show with screensaver changed: $enabled")
        runOnUiThread { registryProvider()?.musicPlayerManager?.showWithScreensaver = enabled }
    }
}

package com.dashieapp.Dashie.halite.videofeed

import android.content.Context
import com.dashieapp.Dashie.devicecontrols.VolumeManager

/**
 * Volume + mute chrome for the Frigate playback overlay.
 *
 * Split out of FrigatePlaybackController (which was at its 800-line budget): this is a
 * self-contained lump of UI wiring with its own bit of state (the pre-mute level), and
 * the controller's job is playback orchestration, not audio plumbing.
 *
 * Mute is surfaced as SYSTEM volume zero — not player mute — so what the user sees in
 * the volume column matches whether sound actually plays. The player itself stays at
 * full volume; system volume is the single source of truth.
 */
internal fun FrigatePlaybackOverlay.wireVolumeControls(context: Context) {
    val volumeManager = VolumeManager(context)
    // Restored on unmute; 5 is a sensible level if we never saw a pre-mute value.
    var savedVolumeBeforeMute = 5

    onSetVolume = { level -> volumeManager.setVolume(level) }
    onGetVolume = { volumeManager.getVolume() }
    onGetMuted = { volumeManager.getVolume() == 0 }
    onToggleMute = {
        val cur = volumeManager.getVolume()
        if (cur > 0) {
            savedVolumeBeforeMute = cur
            volumeManager.setVolume(0)
            true   // now muted
        } else {
            volumeManager.setVolume(savedVolumeBeforeMute.coerceAtLeast(1))
            // Pop the volume controls so the user can see/adjust the restored level
            // without hunting for them.
            showVideoControls()
            false  // now unmuted
        }
    }
}

package com.dashieapp.Dashie.halite.music.sendspin

import android.util.Log

/**
 * Voice-interaction duck for the LOCAL Sendspin player.
 *
 * Why this exists: the voice pipeline ducks music by lowering the MA player volume —
 * but when the ducked player IS this device, that command round-trips back over the
 * Sendspin socket as setStreamVolume(MUSIC, ~0), silencing local TTS AND Gemini Live
 * playback, which share STREAM_MUSIC (Live must use USAGE_MEDIA for AEC3; field
 * report 2026-07-12). While a duck is active we instead:
 *   • quiet ONLY the music via a software gain on our own AudioTrack, and
 *   • suppress server volume→stream writes (SendspinPlayerService.applyDeviceVolume
 *     checks [active]). No re-sync is needed on release: the stream never moves while
 *     suppressed, so the pre-duck stream value is still correct — the duck/unduck
 *     volume commands only churn MA's stored player volume, which MA restores itself.
 *
 * Driven by VoiceComponentWiring's onDuckMusic/onUnduckMusic. No-op when this device
 * isn't an active Sendspin player.
 */
object VoiceDuckController {
    private const val TAG = "VoiceDuck"

    /** Software gain while ducked — audible-but-quiet (vs the MA duck's 5%). */
    private const val VOICE_DUCK_GAIN = 0.15f

    @Volatile
    var active = false
        private set

    fun set(activeNow: Boolean) {
        if (active == activeNow) return
        active = activeNow
        SendspinPlayerService.activeAudioPlayer?.setDuckGain(if (activeNow) VOICE_DUCK_GAIN else 1f)
        Log.i(TAG, "Voice duck ${if (activeNow) "ON (gain $VOICE_DUCK_GAIN)" else "OFF"}")
    }
}

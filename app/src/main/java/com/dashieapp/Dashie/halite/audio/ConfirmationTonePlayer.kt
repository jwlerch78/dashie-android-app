package com.dashieapp.Dashie.halite.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Plays a short confirmation chime (a res/raw OGG) on the ALARM audio stream.
 *
 * Why the alarm stream: it has its own system volume, completely independent of
 * the dashboard's music/media volume. So the confirmation is reliably audible
 * even when media is quiet or ducked — without touching the music stream at all
 * (no temporary boost/duck, nothing jarring for someone listening to quiet
 * music). The per-tone volume (0..1) is a multiplier on the alarm-stream volume.
 *
 * Used by BOTH the live preview in settings and real command acknowledgements,
 * so the audition matches what actually plays.
 */
object ConfirmationTonePlayer {
    private const val TAG = "ConfirmationTone"
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    /** Play [soundName] (a res/raw chime name) at [volume] (0..1) on the alarm stream. */
    fun play(context: Context, soundName: String, volume: Float) {
        handler.post {
            val resId = context.resources.getIdentifier(soundName, "raw", context.packageName)
            if (resId == 0) {
                Log.w(TAG, "Unknown confirmation tone: $soundName")
                return@post
            }
            release()
            try {
                val afd = context.resources.openRawResourceFd(resId) ?: return@post
                val v = volume.coerceIn(0f, 1f)
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                    setVolume(v, v)
                    setOnCompletionListener { mp ->
                        mp.release()
                        if (player === mp) player = null
                    }
                    setOnErrorListener { mp, _, _ ->
                        mp.release()
                        if (player === mp) player = null
                        true
                    }
                    prepare()
                    start()
                }
                Log.d(TAG, "Playing $soundName on ALARM stream (vol=$v)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play confirmation tone: ${e.message}")
            }
        }
    }

    /** Release any in-progress player (also called automatically on completion). */
    fun release() {
        player?.release()
        player = null
    }
}

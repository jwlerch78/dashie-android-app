package com.dashieapp.Dashie.devicecontrols

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/**
 * Manages media volume control.
 * Uses 0-10 scale (mapped from device's hardware steps) for simplicity.
 *
 * Mute state is tracked internally rather than relying solely on
 * [AudioManager.isStreamMute] which is unreliable on some devices
 * (e.g. Fire tablets). The internal flag is authoritative for
 * programmatic mute/unmute; hardware volume changes update it
 * by treating volume==0 as muted.
 */
class VolumeManager(context: Context) {

    companion object {
        private const val TAG = "VolumeManager"
        private const val SCALE_MAX = 10
    }

    private val appContext = context.applicationContext
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var volumeObserver: ContentObserver? = null
    private var lastReportedVolume: Int = -1
    private var lastReportedMuted: Boolean = false

    /** Internal mute flag — authoritative source for mute state */
    private var _muted: Boolean = checkSystemMuted()

    // Callback for volume changes
    var onVolumeChanged: ((volume: Int, isMuted: Boolean) -> Unit)? = null

    /**
     * Get current volume on 0-10 scale
     */
    fun getVolume(): Int {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = if (max > 0) Math.round(current * SCALE_MAX.toFloat() / max).toInt() else 0
        Log.d(TAG, "🔊 getVolume(): step $current/$max = level $level")
        return level
    }

    /**
     * Set volume on 0-10 scale
     */
    fun setVolume(level: Int) {
        val maxSteps = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetStep = Math.round(level.coerceIn(0, SCALE_MAX) * maxSteps / SCALE_MAX.toFloat()).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetStep, 0)
        Log.i(TAG, "🔊 setVolume($level) -> step $targetStep/$maxSteps")
    }

    /**
     * Increase volume by specified amount on 0-10 scale
     * @return the new volume level (0-10)
     */
    fun volumeUp(amount: Int = 1): Int {
        val maxSteps = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val currentLevel = if (maxSteps > 0) Math.round(currentStep * SCALE_MAX.toFloat() / maxSteps).toInt() else 0
        val newLevel = (currentLevel + amount).coerceIn(0, SCALE_MAX)
        val newStep = Math.round(newLevel * maxSteps / SCALE_MAX.toFloat()).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newStep, 0)
        Log.i(TAG, "🔊 volumeUp($amount): level $currentLevel -> $newLevel (step $currentStep -> $newStep)")
        return newLevel
    }

    /**
     * Decrease volume by specified amount on 0-10 scale
     * @return the new volume level (0-10)
     */
    fun volumeDown(amount: Int = 1): Int {
        val maxSteps = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val currentLevel = if (maxSteps > 0) Math.round(currentStep * SCALE_MAX.toFloat() / maxSteps).toInt() else 0
        val newLevel = (currentLevel - amount).coerceIn(0, SCALE_MAX)
        val newStep = Math.round(newLevel * maxSteps / SCALE_MAX.toFloat()).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newStep, 0)
        Log.i(TAG, "🔊 volumeDown($amount): level $currentLevel -> $newLevel (step $currentStep -> $newStep)")
        return newLevel
    }

    /**
     * Check if audio is currently muted.
     * Uses internal flag (set by [mute]/[unmute]) combined with volume==0 detection.
     */
    fun isMuted(): Boolean {
        Log.d(TAG, "🔇 isMuted(): $_muted")
        return _muted
    }

    /**
     * Query the system mute state. Used only for initial sync.
     * Checks both isStreamMute AND volume==0 for device compatibility.
     */
    private fun checkSystemMuted(): Boolean {
        val streamMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
        } else {
            false
        }
        val volumeZero = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        return streamMuted || volumeZero
    }

    /**
     * Mute the audio
     */
    fun mute() {
        _muted = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_MUTE,
                0
            )
        } else {
            @Suppress("DEPRECATION")
            audioManager.setStreamMute(AudioManager.STREAM_MUSIC, true)
        }
        Log.i(TAG, "🔇 Audio muted")
    }

    /**
     * Unmute the audio
     */
    fun unmute() {
        _muted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_UNMUTE,
                0
            )
        } else {
            @Suppress("DEPRECATION")
            audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false)
        }
        Log.i(TAG, "🔊 Audio unmuted")
    }

    /**
     * Toggle mute state
     * @return the new mute state (true = muted)
     */
    fun toggleMute(): Boolean {
        return if (_muted) {
            unmute()
            false
        } else {
            mute()
            true
        }
    }

    /**
     * Get volume info as JSON string
     * Contains: volume (0-10), muted, currentStep, maxSteps
     */
    fun getVolumeInfo(): String {
        val currentStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxSteps = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = if (maxSteps > 0) Math.round(currentStep * SCALE_MAX.toFloat() / maxSteps).toInt() else 0

        return JSONObject(mapOf(
            "volume" to level,
            "muted" to _muted,
            "currentStep" to currentStep,
            "maxSteps" to maxSteps
        )).toString()
    }

    /**
     * Start observing volume changes from hardware buttons.
     * For external changes, treats volume==0 as muted and volume>0 as unmuted.
     */
    fun startObservingVolumeChanges() {
        if (volumeObserver != null) return

        volumeObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                val currentVolume = getVolume()

                // Only sync _muted from volume when volume actually changed
                // (ADJUST_MUTE fires the observer but may not change volume on
                // some devices — don't override the programmatic mute flag)
                if (currentVolume != lastReportedVolume) {
                    if (currentVolume == 0 && !_muted) _muted = true
                    else if (currentVolume > 0 && _muted) _muted = false
                }

                // Notify if volume or mute state changed
                if (currentVolume != lastReportedVolume || _muted != lastReportedMuted) {
                    lastReportedVolume = currentVolume
                    lastReportedMuted = _muted
                    Log.d(TAG, "🔊 Volume changed externally: $currentVolume, muted: $_muted")
                    onVolumeChanged?.invoke(currentVolume, _muted)
                }
            }
        }

        lastReportedVolume = getVolume()
        lastReportedMuted = _muted
        appContext.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            volumeObserver!!
        )
        Log.d(TAG, "🔊 Started observing volume changes")
    }

    /**
     * Stop observing volume changes
     */
    fun stopObservingVolumeChanges() {
        volumeObserver?.let {
            appContext.contentResolver.unregisterContentObserver(it)
            volumeObserver = null
            Log.d(TAG, "🔊 Stopped observing volume changes")
        }
    }
}

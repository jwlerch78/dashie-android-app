package com.dashieapp.Dashie.halite.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.R

/**
 * Central service for all non-music audio alerts (chimes and alarms).
 *
 * Chime playback uses SoundPool with OGG files from res/raw/ for high-quality,
 * low-latency notification sounds. The timer alarm still uses AudioTrack with
 * programmatic PCM generation for indefinite looping.
 *
 * Ducking uses a reference count: music is ducked when any alert is active,
 * and restored only when all alerts finish.
 *
 * Thread safety: All operations are posted to the main thread Handler.
 */
class AlertSoundService(private val context: Context) {
    companion object {
        private const val TAG = "AlertSoundService"
        private const val UNDUCK_DELAY_MS = 100L

        /** Map of chime names to res/raw resource IDs */
        private val CHIME_RESOURCES = mapOf(
            "notify_simple_beep"    to R.raw.notify_simple_beep,
            "notify_bell_tap"       to R.raw.notify_bell_tap,
            "notify_chord_wash"     to R.raw.notify_chord_wash,
            "notify_pulse_alert"    to R.raw.notify_pulse_alert,
            "notify_soft_double"    to R.raw.notify_soft_double,
            "notify_tri_fall"       to R.raw.notify_tri_fall,
            "notify_tri_rise"       to R.raw.notify_tri_rise,
            "extra_bubble"          to R.raw.extra_bubble,
            "extra_celesta"         to R.raw.extra_celesta,
            "extra_deep_bell"       to R.raw.extra_deep_bell,
            "extra_duo_chirp"       to R.raw.extra_duo_chirp,
            "extra_wood_knock"      to R.raw.extra_wood_knock,
            "extra_xylophone_pair"  to R.raw.extra_xylophone_pair,
        )

        /** Approximate durations (ms) for unduck scheduling */
        private val CHIME_DURATIONS = mapOf(
            "notify_simple_beep"    to 150L,
            "notify_bell_tap"       to 400L,
            "notify_chord_wash"     to 350L,
            "notify_pulse_alert"    to 220L,
            "notify_soft_double"    to 400L,
            "notify_tri_fall"       to 420L,
            "notify_tri_rise"       to 420L,
            "extra_bubble"          to 350L,
            "extra_celesta"         to 350L,
            "extra_deep_bell"       to 550L,
            "extra_duo_chirp"       to 300L,
            "extra_wood_knock"      to 200L,
            "extra_xylophone_pair"  to 400L,
        )
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ── Ducking callbacks (wired in HaliteComponentWiring) ────────────

    /** Called when the first alert starts — should duck music */
    var onDuckForAlert: (() -> Unit)? = null

    /** Called when the last alert finishes — should restore music volume */
    var onUnduckForAlert: (() -> Unit)? = null

    // ── State ─────────────────────────────────────────────────────────

    /** Alert volume: 0.0..1.0, scales playback amplitude. Default 40%. */
    var alertVolume: Float = 0.40f

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Returns true if the device is effectively muted for chimes.
     *
     * Chimes play via SoundPool configured with USAGE_MEDIA + STREAM_MUSIC,
     * so the only signal that actually affects whether they're audible is
     * STREAM_MUSIC volume. We intentionally do NOT honor system ringer mode
     * here — on Android, RINGER_MODE_SILENT/VIBRATE silence STREAM_RING and
     * STREAM_NOTIFICATION but have no effect on media playback. Including
     * the ringer check suppressed alerts on Samsung tablets where ringer
     * defaults to SILENT (tablets often don't have a ringer the way phones
     * do). Users who want to mute alerts can:
     *   - Turn down media volume (STREAM_MUSIC = 0 blocks here), or
     *   - Toggle the in-app alerts-mute bell in the sidebar volume popout
     *     (VideoFeedOverlayManager.isAlertsMuted gates chime playback one
     *     level up, before this function is even called).
     */
    private fun isDeviceMuted(): Boolean {
        val musicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val muted = musicVolume == 0
        Log.d(TAG, "isDeviceMuted: musicVolume=$musicVolume/$musicMax -> ${if (muted) "muted" else "unmuted"}")
        return muted
    }

    // ── SoundPool for chime playback ──────────────────────────────────

    private val soundPool: SoundPool
    private val loadedSounds = mutableMapOf<String, Int>()  // name → soundId
    private var soundPoolReady = false

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(attrs)
            .build()

        soundPool.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) {
                val loaded = loadedSounds.size
                val total = CHIME_RESOURCES.size
                if (loaded >= total) {
                    soundPoolReady = true
                    Log.i(TAG, "All $total chime sounds loaded")
                }
            }
        }

        // Pre-load all chime OGGs
        for ((name, resId) in CHIME_RESOURCES) {
            loadedSounds[name] = soundPool.load(context, resId, 1)
        }
        Log.i(TAG, "Loading ${CHIME_RESOURCES.size} chime sounds...")
    }

    private var activeAlarmTrack: AudioTrack? = null
    private var isAlarmActive = false
    private var duckRefCount = 0

    // ── Chime Playback ────────────────────────────────────────────────

    /**
     * Play a named chime via SoundPool. Single-shot (no looping).
     * Automatically ducks music for the chime duration, then restores.
     */
    fun playChime(soundName: String) = playSound(soundName, alertVolume)

    /**
     * Play the voice confirmation tone. Routed to ConfirmationTonePlayer, which
     * uses the ALARM stream so it has its own volume independent of the media/
     * music stream (these chimes' SoundPool rides STREAM_MUSIC and would be
     * capped by — and ducked with — the music). [volume] is 0.0..1.0.
     */
    fun playConfirmationTone(soundName: String, volume: Float) =
        ConfirmationTonePlayer.play(context, soundName, volume)

    private fun playSound(soundName: String, volume: Float) {
        handler.post {
            if (isDeviceMuted()) {
                Log.d(TAG, "Device muted — skipping chime: $soundName")
                return@post
            }

            val soundId = loadedSounds[soundName]
            if (soundId == null) {
                Log.w(TAG, "Unknown chime: $soundName (available: ${loadedSounds.keys})")
                return@post
            }

            incrementDuck()
            soundPool.play(soundId, volume, volume, 1, 0, 1.0f)

            // Schedule unduck after chime completes
            val duration = CHIME_DURATIONS[soundName] ?: 300L
            handler.postDelayed({ decrementDuck() }, duration + UNDUCK_DELAY_MS)

            Log.d(TAG, "Playing chime: $soundName (vol=$volume)")
        }
    }

    /** Stop any in-progress chime immediately. */
    fun stopChime() {
        handler.post { soundPool.autoPause() }
    }

    // ── Alarm Playback ────────────────────────────────────────────────

    /**
     * Start the timer alarm beep pattern on indefinite loop.
     * Still uses AudioTrack + PCM generation for gapless looping.
     */
    fun startAlarm() {
        handler.post {
            if (isAlarmActive) return@post

            if (isDeviceMuted()) {
                Log.d(TAG, "Device muted — skipping alarm")
                return@post
            }

            try {
                val samples = AlertToneGenerator.generateAlarmCycle(alertVolume)
                val bufferSize = samples.size * 2

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(AlertToneGenerator.SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(samples, 0, samples.size)
                track.setLoopPoints(0, samples.size, -1)

                incrementDuck()
                track.play()
                activeAlarmTrack = track
                isAlarmActive = true

                Log.i(TAG, "Timer alarm started (vol=${alertVolume})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start alarm: ${e.message}")
                releaseAlarmTrack()
            }
        }
    }

    /** Stop the timer alarm and restore music volume. */
    fun stopAlarm() {
        handler.post {
            if (!isAlarmActive) return@post
            isAlarmActive = false
            releaseAlarmTrack()
            decrementDuck()
            Log.i(TAG, "Timer alarm stopped")
        }
    }

    /** Whether the alarm is currently playing (thread-safe read). */
    fun isAlarmPlaying(): Boolean = isAlarmActive

    private fun releaseAlarmTrack() {
        try {
            activeAlarmTrack?.apply { stop(); release() }
        } catch (_: Exception) { }
        activeAlarmTrack = null
    }

    // ── Ducking Coordination ──────────────────────────────────────────

    private fun incrementDuck() {
        if (duckRefCount == 0) {
            Log.d(TAG, "Ducking music for alert")
            onDuckForAlert?.invoke()
        }
        duckRefCount++
    }

    private fun decrementDuck() {
        if (duckRefCount > 0) {
            duckRefCount--
            if (duckRefCount == 0) {
                Log.d(TAG, "Unducking music after alert")
                onUnduckForAlert?.invoke()
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        soundPool.release()
        if (isAlarmActive) {
            isAlarmActive = false
            releaseAlarmTrack()
        }
        duckRefCount = 0
        Log.i(TAG, "AlertSoundService destroyed")
    }
}

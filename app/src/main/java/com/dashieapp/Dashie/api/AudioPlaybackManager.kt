package com.dashieapp.Dashie.api

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Manages audio playback for the Dashie API.
 *
 * Handles:
 * - ExoPlayer for media playback (play/pause/stop/seek/mute)
 * - TTS (Text-to-Speech) engine
 * - Volume ducking for voice interactions
 * - Fire tablet audio unmute workarounds
 * - Thread-safe cached playback state for API server thread
 *
 * Extracted from DashieServiceManager to isolate audio concerns.
 */
class AudioPlaybackManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "DashieSvcMgr"
    }

    // TTS provided by MainActivity (initialized in Activity context)
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Audio playback for playSound/stopSound (ExoPlayer for reliable streaming)
    private var exoPlayer: ExoPlayer? = null
    private var isMuted = false
    private var currentSoundUrl: String? = null  // Track currently playing URL for soundUrlPlaying
    private val handler = Handler(Looper.getMainLooper())

    // Cached ExoPlayer state for thread-safe access from API server thread
    // ExoPlayer enforces main-thread access; these are updated from Player.Listener
    @Volatile private var cachedIsPlaying = false
    @Volatile private var cachedPlaybackState = Player.STATE_IDLE
    @Volatile private var cachedPositionMs = 0L
    @Volatile private var cachedDurationMs = 0L

    // Periodic position updater — keeps cachedPositionMs fresh during playback
    // so that background threads (MA poll) get accurate elapsed time
    private val positionTicker = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    cachedPositionMs = player.currentPosition
                    handler.postDelayed(this, 500)
                }
            }
        }
    }

    // Media metadata pushed by Music Assistant via setMediaInfo REST API
    @Volatile var mediaTitle: String = ""
        private set
    @Volatile var mediaArtist: String = ""
        private set
    @Volatile var mediaAlbum: String = ""
        private set
    @Volatile var mediaImageUrl: String = ""
        private set
    @Volatile var maEntityId: String = ""
    @Volatile var maServerUrl: String = ""
        private set

    // When true, duration was set via setMediaInfo (MA provider) and should not be overwritten by ExoPlayer
    @Volatile private var hasMediaDuration = false

    // In flow mode, ExoPlayer's position is cumulative across the stream.
    // When a new track starts (setMediaInfo with different title), save the ExoPlayer position
    // as offset so getTrackPosition() returns the track-relative position.
    @Volatile private var trackStartOffsetMs: Long = 0

    // Callback fired when media metadata is updated (for music player UI)
    var onMediaInfoReceived: ((title: String, artist: String, album: String, imageUrl: String, durationMs: Int) -> Unit)? = null

    // Callback fired when the MA provider pushes this device's player ID
    var onPlayerIdReceived: ((playerId: String) -> Unit)? = null

    // Volume ducking state — ref-counted to support multiple concurrent duckers
    // (voice interaction + alert sounds can both duck independently)
    private var preDuckVolume = 1.0f
    private var duckCount = 0

    // ============================================
    // Volume Ducking
    // ============================================

    /**
     * Duck ExoPlayer volume. Ref-counted: first duck saves the original volume
     * and lowers to 0.05. Subsequent ducks increment the count without changing
     * volume. Unduck only restores when all duckers have released.
     */
    fun duckAudio() {
        handler.post {
            val player = exoPlayer
            if (player != null && cachedIsPlaying) {
                if (duckCount == 0) {
                    preDuckVolume = player.volume
                    player.volume = 0.05f
                    Log.i(TAG, "🎵 Audio ducked: $preDuckVolume → 0.05")
                }
                duckCount++
            } else {
                Log.d(TAG, "🎵 Duck skipped - ExoPlayer not playing")
            }
        }
    }

    /**
     * Release one duck reference. Volume is only restored when all duckers
     * (voice + alerts) have called unduck.
     */
    fun unduckAudio() {
        handler.post {
            if (duckCount > 0) {
                duckCount--
                if (duckCount == 0) {
                    exoPlayer?.volume = preDuckVolume
                    Log.i(TAG, "🎵 Audio unducked: restored to $preDuckVolume")
                }
            }
        }
    }

    /**
     * Check if ExoPlayer is currently playing audio.
     * Thread-safe (uses cached volatile field).
     */
    fun isMusicPlaying(): Boolean = cachedIsPlaying

    /**
     * Check if ExoPlayer has a paused stream (ready to resume).
     * True when ExoPlayer is not playing but has buffered media (STATE_READY or STATE_BUFFERING).
     * Thread-safe (uses cached volatile fields).
     */
    fun isMusicPaused(): Boolean = !cachedIsPlaying &&
        (cachedPlaybackState == Player.STATE_READY || cachedPlaybackState == Player.STATE_BUFFERING)

    /**
     * Check if ExoPlayer was paused by the user (STATE_READY) vs buffering (STATE_BUFFERING).
     * In flow mode, buffering between tracks is transient and should not show as paused.
     */
    fun isUserPaused(): Boolean = !cachedIsPlaying && cachedPlaybackState == Player.STATE_READY

    /**
     * Pause ExoPlayer music playback directly.
     * Used when MA entity is idle (handed off to ExoPlayer) and HA can't control it.
     */
    fun pauseMusic() {
        handler.post {
            exoPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                    Log.i(TAG, "🎵 Music paused via ExoPlayer")
                }
            }
        }
    }

    /**
     * Resume ExoPlayer music playback directly.
     * Used when MA entity is idle (handed off to ExoPlayer) and HA can't control it.
     */
    fun resumeMusic() {
        handler.post {
            exoPlayer?.let {
                if (!it.isPlaying && it.playbackState != Player.STATE_IDLE) {
                    ensureAudioUnmuted()
                    it.play()
                    Log.i(TAG, "🎵 Music resumed via ExoPlayer")
                }
            }
        }
    }

    /**
     * Stop ExoPlayer music playback directly.
     * Used when user explicitly closes the music player.
     */
    fun stopMusic() {
        handler.post {
            exoPlayer?.let {
                it.stop()
                currentSoundUrl = null
                cachedIsPlaying = false
                cachedPlaybackState = Player.STATE_IDLE
                Log.i(TAG, "🎵 Music stopped via ExoPlayer")
            }
        }
    }

    // ============================================
    // TTS (Text-to-Speech)
    // ============================================

    /**
     * Set the TTS engine (should be initialized in MainActivity)
     */
    fun setTts(textToSpeech: TextToSpeech) {
        tts = textToSpeech
        ttsReady = true
        Log.i(TAG, "TTS engine set from MainActivity")
    }

    /**
     * Speak text using the TTS engine
     */
    fun speakText(text: String) {
        Log.i(TAG, "speakText called: '$text', ttsReady=$ttsReady, tts=${tts != null}")

        if (!ttsReady || tts == null) {
            Log.w(TAG, "TTS not ready - was setTts() called from MainActivity?")
            return
        }

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "api_tts_${System.currentTimeMillis()}")
        Log.i(TAG, "TTS speak result: $result for text: '$text'")
    }

    /**
     * Stop TTS playback
     */
    fun stopTts() {
        tts?.stop()
        Log.d(TAG, "TTS stopped")
    }

    // ============================================
    // ExoPlayer Audio Playback
    // ============================================

    /**
     * Play audio from a URL
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun playSound(url: String) {
        handler.post {
            try {
                // Ensure audio is not muted
                ensureAudioUnmuted()

                // Request audio focus
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                        .build()
                    audioManager.requestAudioFocus(focusRequest)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                }
                Log.d(TAG, "Audio focus request result: $focusResult")

                currentSoundUrl = url

                // Extract MA server URL and player ID from flow stream URL
                // URL format: http://host:port/flow/{session_id}/{queue_id}/{item_id}/{player_id}.{fmt}
                // This is more reliable than waiting for setMediaInfo to deliver them
                if (url.contains("/flow/") || url.contains("/single/")) {
                    try {
                        val parsed = java.net.URL(url)
                        val extracted = "${parsed.protocol}://${parsed.host}${if (parsed.port != -1) ":${parsed.port}" else ""}"
                        if (extracted.isNotEmpty()) {
                            maServerUrl = extracted
                            Log.i(TAG, "🎵 MA server URL from stream: $maServerUrl")
                        }
                        // Extract player_id from last path segment (before extension)
                        val segments = parsed.path.trimStart('/').split('/')
                        if (segments.size >= 5) {
                            val playerIdWithExt = segments[4]  // player_id.mp3
                            val playerId = playerIdWithExt.substringBeforeLast('.')
                            if (playerId.isNotEmpty() && maEntityId.isEmpty()) {
                                maEntityId = playerId
                                Log.i(TAG, "🎵 MA entity ID from stream: $maEntityId")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "🎵 Failed to parse MA info from stream URL: ${e.message}")
                    }
                }

                // Create or reuse ExoPlayer
                val player = exoPlayer ?: ExoPlayer.Builder(context)
                    .setAudioAttributes(
                        androidx.media3.common.AudioAttributes.Builder()
                            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                            .build(),
                        /* handleAudioFocus= */ false  // We handle focus ourselves
                    )
                    .build().also { newPlayer ->
                        newPlayer.addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                cachedPlaybackState = playbackState
                                when (playbackState) {
                                    Player.STATE_ENDED -> {
                                        Log.d(TAG, "ExoPlayer playback completed")
                                        currentSoundUrl = null
                                        cachedIsPlaying = false
                                    }
                                    Player.STATE_BUFFERING -> {
                                        Log.d(TAG, "ExoPlayer buffering...")
                                    }
                                    Player.STATE_READY -> {
                                        Log.d(TAG, "ExoPlayer ready/playing")
                                    }
                                    Player.STATE_IDLE -> {
                                        Log.d(TAG, "ExoPlayer idle")
                                        cachedIsPlaying = false
                                    }
                                }
                                // Update cached position/duration
                                cachedPositionMs = newPlayer.currentPosition
                                if (!hasMediaDuration) {
                                    cachedDurationMs = newPlayer.duration.coerceAtLeast(0)
                                }
                            }

                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                cachedIsPlaying = isPlaying
                                cachedPositionMs = newPlayer.currentPosition
                                if (!hasMediaDuration) {
                                    cachedDurationMs = newPlayer.duration.coerceAtLeast(0)
                                }
                                // Start/stop periodic position updates for background threads
                                handler.removeCallbacks(positionTicker)
                                if (isPlaying) {
                                    handler.postDelayed(positionTicker, 500)
                                }
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                Log.e(TAG, "ExoPlayer error playing: $currentSoundUrl (${error.errorCodeName}: ${error.message})")
                                currentSoundUrl = null
                                cachedIsPlaying = false
                                cachedPlaybackState = Player.STATE_IDLE
                            }
                        })
                        exoPlayer = newPlayer
                    }

                // Set new media and play
                ensureAudioUnmuted()
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
                player.play()
                Log.i(TAG, "Playing sound (ExoPlayer): $url")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play sound: ${e.message}", e)
                currentSoundUrl = null
            }
        }
    }

    /**
     * Stop audio playback
     */
    fun stopSound() {
        handler.post {
            try {
                exoPlayer?.stop()
                currentSoundUrl = null
                clearMediaInfo()
                Log.d(TAG, "Sound stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping sound: ${e.message}", e)
            }
        }
    }

    /**
     * Pause audio playback
     */
    fun pauseSound() {
        handler.post {
            try {
                exoPlayer?.let {
                    if (it.isPlaying) {
                        it.pause()
                        Log.d(TAG, "Sound paused")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing sound: ${e.message}", e)
            }
        }
    }

    /**
     * Resume audio playback
     */
    fun resumeSound() {
        handler.post {
            try {
                ensureAudioUnmuted()
                exoPlayer?.let {
                    it.play()
                    Log.d(TAG, "Sound resumed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resuming sound: ${e.message}", e)
            }
        }
    }

    /**
     * Seek audio playback to a specific position
     */
    fun seekSound(positionMs: Int) {
        handler.post {
            try {
                exoPlayer?.let {
                    it.seekTo(positionMs.toLong())
                    Log.d(TAG, "Sound seeked to $positionMs ms")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error seeking sound: ${e.message}", e)
            }
        }
    }

    // ============================================
    // Mute/Unmute
    // ============================================

    /**
     * Mute or unmute audio
     */
    fun muteAudio(mute: Boolean) {
        handler.post {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (mute) {
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
                } else {
                    // Fire tablets have issues with ADJUST_UNMUTE, so we use multiple approaches
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_UNMUTE,
                            0
                        )
                        // Double-check: if still muted, try raising volume which implicitly unmutes
                        if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                            Log.w(TAG, "ADJUST_UNMUTE didn't work, trying volume bump")
                            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            // Bump volume up then back to force unmute
                            if (currentVol < maxVol) {
                                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                            } else {
                                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false)
                    }
                }
                isMuted = mute
                Log.d(TAG, "Audio ${if (mute) "muted" else "unmuted"}")
            } catch (e: Exception) {
                Log.e(TAG, "Error ${if (mute) "muting" else "unmuting"} audio: ${e.message}", e)
            }
        }
    }

    /**
     * Check if system audio is actually muted (not just our tracked state)
     */
    fun isSystemMuted(): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
            } else {
                isMuted // Fall back to tracked state on older devices
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking mute state: ${e.message}", e)
            isMuted
        }
    }

    /**
     * Ensure audio is unmuted before playback (fixes Fire tablet issues)
     */
    private fun ensureAudioUnmuted() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
            Log.w(TAG, "Audio was muted before playback, forcing unmute")
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            // If still muted, bump volume
            if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (currentVol < maxVol) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                } else if (currentVol > 0) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                }
            }
            isMuted = false
        }
    }

    // ============================================
    // Playback State Queries
    // ============================================

    /**
     * Get current audio playback position in milliseconds (cached, thread-safe).
     */
    fun getAudioPosition(): Int = cachedPositionMs.toInt()

    /**
     * Get live audio playback position in milliseconds.
     * Must be called on the main thread. Falls back to cached value if ExoPlayer is unavailable.
     */
    fun getLiveAudioPosition(): Int {
        return try {
            exoPlayer?.currentPosition?.toInt() ?: cachedPositionMs.toInt()
        } catch (_: Exception) {
            cachedPositionMs.toInt()
        }
    }

    /**
     * Get track-relative position in milliseconds for flow mode.
     * Subtracts the offset recorded at the last track change (setMediaInfo with new title).
     */
    fun getTrackPosition(): Int {
        val rawPos = getLiveAudioPosition().toLong()
        return (rawPos - trackStartOffsetMs).coerceAtLeast(0).toInt()
    }

    /**
     * Get audio duration in milliseconds
     */
    fun getAudioDuration(): Int = cachedDurationMs.toInt()

    /**
     * Get the currently playing/paused sound URL.
     * Returns the URL if ExoPlayer is playing or paused (stream still loaded), empty string otherwise.
     */
    fun getSoundUrlPlaying(): String {
        return if ((cachedIsPlaying || isMusicPaused()) && currentSoundUrl != null) {
            currentSoundUrl!!
        } else {
            ""
        }
    }

    // ============================================
    // Media Metadata (pushed by Music Assistant)
    // ============================================

    /**
     * Store track metadata pushed by Music Assistant's setMediaInfo command.
     * Updates cached fields and notifies the music player UI via callback.
     */
    fun setMediaInfo(title: String, artist: String, album: String, imageUrl: String, durationMs: Int, entityId: String = "", maServer: String = "") {
        // Detect track change (new song) to reset position
        val trackChanged = title.isNotEmpty() && title != mediaTitle
        mediaTitle = title
        mediaArtist = artist
        mediaAlbum = album
        mediaImageUrl = imageUrl
        if (entityId.isNotEmpty()) maEntityId = entityId
        if (maServer.isNotEmpty()) maServerUrl = maServer
        if (durationMs > 0) {
            cachedDurationMs = durationMs.toLong()
            hasMediaDuration = true
        }
        if (trackChanged) {
            // In flow mode, ExoPlayer's position is cumulative. Record current position
            // as offset so getTrackPosition() returns track-relative time.
            // Use cachedPositionMs (thread-safe) since this is called from API server thread.
            trackStartOffsetMs = cachedPositionMs.coerceAtLeast(0)
            Log.i(TAG, "Track changed → offset=${trackStartOffsetMs}ms. Duration: ${durationMs}ms")
        }
        Log.i(TAG, "Media info set: \"$title\" by $artist (album: $album, entity=$maEntityId, maServer=$maServerUrl)")
        onMediaInfoReceived?.invoke(title, artist, album, imageUrl, durationMs)
    }

    /**
     * Set this device's MA player ID. Called by the MA Dashie provider on first connect
     * so the device knows its own entity ID before any playback starts.
     */
    fun setPlayerId(playerId: String, serverUrl: String = "") {
        maEntityId = playerId
        if (serverUrl.isNotEmpty()) maServerUrl = serverUrl
        Log.i(TAG, "🎵 Player ID set by MA provider: $playerId (server=$maServerUrl)")
        onPlayerIdReceived?.invoke(playerId)
    }

    /**
     * Clear stored media metadata (called on stop).
     */
    private fun clearMediaInfo() {
        mediaTitle = ""
        mediaArtist = ""
        mediaAlbum = ""
        mediaImageUrl = ""
        maEntityId = ""
        // Keep maServerUrl — it's needed for speaker picker even when not playing
        hasMediaDuration = false
        trackStartOffsetMs = 0
    }

    // ============================================
    // Service Callback Registration
    // ============================================

    /**
     * Register audio-related callbacks on the API service.
     */
    fun registerCallbacks(service: DashieApiService) {
        // TTS
        service.speakTextCallback = { text -> speakText(text) }
        service.stopSpeakingCallback = { stopTts() }

        // Audio playback
        service.playSoundCallback = { url -> playSound(url) }
        service.stopSoundCallback = { stopSound() }
        service.pauseSoundCallback = { pauseSound() }
        service.resumeSoundCallback = { resumeSound() }
        service.seekSoundCallback = { positionMs -> seekSound(positionMs) }
        service.muteAudioCallback = { mute -> muteAudio(mute) }
        service.isMutedCallback = { isSystemMuted() }
        service.getAudioPositionCallback = { getAudioPosition() }
        service.getTrackPositionCallback = { getTrackPosition() }
        service.getAudioDurationCallback = { getAudioDuration() }
        service.getSoundUrlPlayingCallback = { getSoundUrlPlaying() }
        service.isSoundPausedCallback = { isMusicPaused() }

        // Player identity
        service.setPlayerIdCallback = { playerId, serverUrl -> setPlayerId(playerId, serverUrl) }

        // Media metadata
        service.setMediaInfoCallback = { title, artist, album, imageUrl, durationMs, entityId, maServer ->
            setMediaInfo(title, artist, album, imageUrl, durationMs, entityId, maServer)
        }
        service.getMediaTitleCallback = { mediaTitle }
        service.getMediaArtistCallback = { mediaArtist }
        service.getMediaAlbumCallback = { mediaAlbum }
        service.getMediaImageUrlCallback = { mediaImageUrl }
    }

    // ============================================
    // Cleanup
    // ============================================

    /**
     * Release all audio resources.
     * Called from stopService().
     */
    fun release() {
        // Shutdown TTS
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false

        // Stop and release ExoPlayer
        try {
            exoPlayer?.release()
            exoPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing ExoPlayer: ${e.message}")
        }
    }
}

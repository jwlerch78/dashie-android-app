package com.dashieapp.Dashie.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import com.dashieapp.Dashie.devicecontrols.DeviceControlsCoordinator
import com.dashieapp.Dashie.api.DashieApiService
import java.util.Locale

/**
 * Manager for the Dashie API Service in standard Dashie app.
 *
 * This is a simplified version of DashieServiceManager that works
 * without HalitePreferences. It provides Home Assistant integration
 * for the standard Dashie widget app.
 *
 * Features supported:
 * - Device discoverability (SSDP/mDNS)
 * - Brightness control
 * - Volume control
 * - Dark/Light mode control
 * - App restart
 * - Device info (battery, charging, IP, etc.)
 * - TTS (text-to-speech)
 * - Toast messages
 *
 * Features NOT supported (Dashie Lite only):
 * - Screensaver
 * - PIN lock
 * - Motion wake
 * - RTSP streaming
 * - URL navigation (Dashie uses fixed URL)
 */
class DashieApiServiceManager(
    private val context: Context,
    private val webView: WebView,
    private val deviceControls: DeviceControlsCoordinator,
    private val prefs: DashieApiPreferences,
    private val lifecycleOwner: LifecycleOwner
) {

    companion object {
        private const val TAG = "DashieApiMgr"
    }

    private var apiService: DashieApiService? = null
    private var isBound = false
    private var currentUrl: String = ""  // Will be set by MainActivity

    // Callback to load the dashboard start URL (instead of webView.reload())
    var loadDashboardUrl: (() -> Unit)? = null

    // TTS provided by MainActivity (initialized in Activity context)
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Audio playback for playSound/stopSound
    private var mediaPlayer: MediaPlayer? = null
    private var isMuted = false
    private val handler = Handler(Looper.getMainLooper())

    // Callbacks from MainActivity for service actions
    var onRestartAppRequested: (() -> Unit)? = null

    /**
     * Set the TTS engine (should be initialized in MainActivity)
     */
    fun setTts(textToSpeech: TextToSpeech) {
        tts = textToSpeech
        ttsReady = true
        Log.i(TAG, "TTS engine set from MainActivity")
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? DashieApiService.LocalBinder
            apiService = localBinder?.getService()
            isBound = true
            Log.i(TAG, "Bound to DashieApiService")

            // Setup callbacks
            setupServiceCallbacks()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            apiService = null
            isBound = false
            Log.i(TAG, "Disconnected from DashieApiService")
        }
    }

    /**
     * Start the API service if enabled in preferences
     */
    fun startServiceIfEnabled() {
        if (!prefs.apiEnabled) {
            Log.i(TAG, "API disabled in preferences, not starting service")
            return
        }

        Log.i(TAG, "Starting Dashie API service")

        val intent = Intent(context, DashieApiService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        // Bind to get callbacks
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Speak text using the TTS engine
     */
    private fun speakText(text: String) {
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
    private fun stopTts() {
        tts?.stop()
        Log.d(TAG, "TTS stopped")
    }

    /**
     * Stop the API service
     */
    fun stopService() {
        Log.i(TAG, "Stopping Dashie API service")

        if (isBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding service: ${e.message}")
            }
            isBound = false
        }

        context.stopService(Intent(context, DashieApiService::class.java))
        apiService = null

        // Shutdown TTS
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false

        // Stop and release MediaPlayer
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaPlayer: ${e.message}")
        }
    }

    /**
     * Restart the service (e.g., after password change)
     */
    fun restartService() {
        Log.i(TAG, "Restarting Dashie API service")
        stopService()
        startServiceIfEnabled()
    }

    /**
     * Update the current URL (called when WebView navigates)
     */
    fun setCurrentUrl(url: String) {
        currentUrl = url
    }

    /**
     * Setup all callbacks between the service and MainActivity components
     */
    private fun setupServiceCallbacks() {
        val service = apiService ?: return

        // App control
        service.onRestartAppRequested = { onRestartAppRequested?.invoke() }
        service.onToForegroundRequested = {
            // Already handled by service bringing activity to foreground
        }

        // URL loading - standard Dashie uses fixed URL, so just return current
        service.onLoadUrlRequested = { url ->
            // In standard Dashie, we don't allow arbitrary URL loading
            // The URL is determined by the Dashie web app
            Log.d(TAG, "loadUrl requested but ignored in standard Dashie: $url")
        }

        service.onLoadStartUrlRequested = {
            // Navigate back to dashboard start URL
            (context as? android.app.Activity)?.runOnUiThread {
                Log.i(TAG, "🔄 API loadStartUrl requested - loading dashboard URL")
                com.dashieapp.Dashie.halite.diagnostics.PersistentLog.info("REFRESH", "WebView loadStartUrl: API request")
                val loader = loadDashboardUrl
                if (loader != null) {
                    loader()
                } else {
                    webView.reload()
                }
            }
        }

        // Volume control (maps 0-100 to our 0-10 scale)
        service.setVolumeCallback = { level, stream ->
            // Convert 0-100 to 0-10
            val ourLevel = (level * 10 / 100).coerceIn(0, 10)
            deviceControls.setVolume(ourLevel)
        }

        service.getVolumeCallback = { stream ->
            // Convert our 0-10 to 0-100
            val ourLevel = deviceControls.getVolume()
            ourLevel * 10
        }

        // Brightness control (0-255 from API, 0-10 internally)
        service.setBrightnessCallback = { level ->
            // Convert 0-255 to 0-10 for BrightnessManager
            val ourLevel = (level * 10 / 255).coerceIn(0, 10)
            deviceControls.setBrightness(ourLevel)
        }

        service.getBrightnessCallback = {
            // Convert our 0-10 to 0-255 for API
            val ourLevel = deviceControls.getBrightness()
            ourLevel * 255 / 10
        }

        // Screen state - returns if the physical display is on
        // (Standard Dashie doesn't have screensaver/screen off feature)
        service.isScreenOnCallback = {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isInteractive ?: true
        }

        // Screen on/off - not supported in standard Dashie
        service.onScreenOnRequested = {
            Log.d(TAG, "screenOn requested - not supported in standard Dashie")
        }
        service.onScreenOffRequested = { _ ->
            Log.d(TAG, "screenOff requested - not supported in standard Dashie")
        }

        // URLs - return current and fixed start URL
        service.getCurrentUrlCallback = { currentUrl }
        service.getStartUrlCallback = { currentUrl }  // Start URL is same as current in standard Dashie

        // TTS (Text-to-Speech)
        service.speakTextCallback = { text ->
            speakText(text)
        }
        service.stopSpeakingCallback = {
            stopTts()
        }

        // Screensaver - not supported in standard Dashie
        service.startScreensaverCallback = {
            Log.d(TAG, "startScreensaver requested - not supported in standard Dashie")
        }
        service.stopScreensaverCallback = {
            Log.d(TAG, "stopScreensaver requested - not supported in standard Dashie")
        }
        service.isInScreensaverCallback = { false }  // Never in screensaver mode

        // Audio playback
        service.playSoundCallback = { url -> playSound(url) }
        service.stopSoundCallback = { stopSound() }
        service.pauseSoundCallback = { pauseSound() }
        service.resumeSoundCallback = { resumeSound() }
        service.seekSoundCallback = { positionMs -> seekSound(positionMs) }
        service.muteAudioCallback = { mute -> muteAudio(mute) }
        service.isMutedCallback = { isMuted }
        service.getAudioPositionCallback = { getAudioPosition() }
        service.getTrackPositionCallback = { getAudioPosition() }  // No flow mode in legacy player
        service.getAudioDurationCallback = { getAudioDuration() }

        // App launcher
        service.startApplicationCallback = { packageName -> startApplication(packageName) }

        // Overlay message (Toast)
        service.showOverlayMessageCallback = { text, duration -> showOverlayMessage(text, duration) }

        // Motion trigger - not applicable in standard Dashie
        service.triggerMotionCallback = {
            Log.d(TAG, "triggerMotion requested - not applicable in standard Dashie")
        }

        // WebView control
        service.clearCacheCallback = { clearCache() }
        service.clearWebstorageCallback = { clearWebstorage() }

        // Camera capture - not supported (no camera permission in standard Dashie)
        service.getCamshotCallback = { callback ->
            Log.d(TAG, "getCamshot requested - not supported in standard Dashie")
            callback(null)
        }

        // Kiosk lock - not supported in standard Dashie
        service.lockKioskCallback = {
            Log.d(TAG, "lockKiosk requested - not supported in standard Dashie")
        }
        service.unlockKioskCallback = {
            Log.d(TAG, "unlockKiosk requested - not supported in standard Dashie")
        }
        service.isKioskLockedCallback = { false }

        service.isLockAppExitCallback = { false }
        service.isLockSettingsCallback = { false }

        // PIN management - not supported
        service.setPinCallback = { pin ->
            Log.d(TAG, "setPin requested - not supported in standard Dashie")
            false
        }
        service.clearPinCallback = {
            Log.d(TAG, "clearPin requested - not supported in standard Dashie")
        }
        service.hasPinSetCallback = { false }
        service.verifyPinCallback = { pin -> false }
        service.getStoredPinCallback = { "" }

        // RTSP streaming - not supported in standard Dashie
        service.startRtspStreamCallback = {
            Log.d(TAG, "startRtspStream requested - not supported in standard Dashie")
            false
        }
        service.stopRtspStreamCallback = {
            Log.d(TAG, "stopRtspStream requested - not supported in standard Dashie")
        }
        service.isRtspStreamRunningCallback = { false }
        service.getRtspStreamUrlCallback = { "" }
        service.getRtspClientCountCallback = { 0 }

        service.setRtspResolutionCallback = { _, _ -> }
        service.setRtspFpsCallback = { _ -> }
        service.setRtspBitrateCallback = { _ -> }
        service.getRtspConfigCallback = {
            com.dashieapp.Dashie.api.RtspConfig(
                width = 0, height = 0, fps = 0, bitrate = 0, port = 0, softwareEncoding = false
            )
        }

        // Dark mode callbacks
        service.isDarkModeCallback = {
            deviceControls.isSystemDarkMode()
        }
        service.setDarkModeCallback = { enabled ->
            val success = deviceControls.setSystemDarkMode(enabled)
            if (success) {
                // Notify web app JS so it can update theme classes + HA iframe theme
                Handler(Looper.getMainLooper()).post {
                    webView.evaluateJavascript(
                        "if (window.onColorSchemeChanged) window.onColorSchemeChanged($enabled);",
                        null
                    )
                }
            }
            success
        }
        service.canControlDarkModeCallback = {
            deviceControls.canWriteSecureSettings()
        }

        // Screen off method - not supported in standard Dashie
        service.getScreenOffMethodCallback = { "overlay" }
        service.setScreenOffMethodCallback = { _ -> false }

        // Screensaver settings - not supported
        service.getScreensaverModeCallback = { "none" }
        service.setScreensaverModeCallback = { _ -> false }
        service.getPhotoSourceCallback = { "none" }
        service.setPhotoSourceCallback = { _ -> false }
        service.getHaMediaFolderCallback = { "" }
        service.setHaMediaFolderCallback = { _ -> false }

        // Ambient light sensor - not supported (no light sensor integration in standard Dashie)
        service.getAmbientLightCallback = { 0 }

        // Device name - from preferences (set by web app via JS bridge)
        service.getDeviceNameCallback = { prefs.deviceName }

        Log.i(TAG, "Service callbacks configured for standard Dashie")
    }

    // ============================================
    // Audio Playback
    // ============================================

    /**
     * Play audio from URL
     */
    private fun playSound(url: String) {
        handler.post {
            try {
                stopSound()

                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(url)
                    prepareAsync()
                    setOnPreparedListener { mp ->
                        mp.start()
                        Log.i(TAG, "Audio playback started: $url")
                    }
                    setOnCompletionListener {
                        Log.d(TAG, "Audio playback completed")
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                        true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play sound: ${e.message}", e)
            }
        }
    }

    /**
     * Stop audio playback
     */
    private fun stopSound() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            mediaPlayer = null
            Log.d(TAG, "Audio playback stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping sound: ${e.message}")
        }
    }

    /**
     * Pause audio playback
     */
    private fun pauseSound() {
        handler.post {
            try {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        it.pause()
                        Log.d(TAG, "Audio playback paused")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error pausing sound: ${e.message}")
            }
        }
    }

    /**
     * Resume audio playback
     */
    private fun resumeSound() {
        handler.post {
            try {
                mediaPlayer?.let {
                    it.start()
                    Log.d(TAG, "Audio playback resumed")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error resuming sound: ${e.message}")
            }
        }
    }

    /**
     * Seek audio playback to a specific position
     */
    private fun seekSound(positionMs: Int) {
        handler.post {
            try {
                mediaPlayer?.let {
                    it.seekTo(positionMs)
                    Log.d(TAG, "Audio seeked to $positionMs ms")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error seeking sound: ${e.message}")
            }
        }
    }

    /**
     * Mute or unmute audio
     */
    private fun muteAudio(mute: Boolean) {
        handler.post {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                        0
                    )
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.setStreamMute(AudioManager.STREAM_MUSIC, mute)
                }
                isMuted = mute
                Log.d(TAG, "Audio ${if (mute) "muted" else "unmuted"}")
            } catch (e: Exception) {
                Log.w(TAG, "Error ${if (mute) "muting" else "unmuting"} audio: ${e.message}")
            }
        }
    }

    /**
     * Get current audio playback position in milliseconds
     */
    private fun getAudioPosition(): Int {
        return try {
            mediaPlayer?.currentPosition ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "Error getting audio position: ${e.message}")
            0
        }
    }

    /**
     * Get audio duration in milliseconds
     */
    private fun getAudioDuration(): Int {
        return try {
            mediaPlayer?.duration ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "Error getting audio duration: ${e.message}")
            0
        }
    }

    // ============================================
    // App Launcher
    // ============================================

    /**
     * Launch an app by package name
     */
    private fun startApplication(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Launched application: $packageName")
                true
            } else {
                Log.w(TAG, "Package not found: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch application: ${e.message}", e)
            false
        }
    }

    // ============================================
    // UI
    // ============================================

    /**
     * Show overlay message (Toast)
     */
    private fun showOverlayMessage(text: String, durationMs: Int) {
        handler.post {
            val duration = if (durationMs > 2000) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            Toast.makeText(context, text, duration).show()
            Log.d(TAG, "Showing overlay message: $text")
        }
    }

    // ============================================
    // WebView
    // ============================================

    /**
     * Clear WebView cache
     */
    private fun clearCache() {
        handler.post {
            webView.clearCache(true)
            Log.i(TAG, "WebView cache cleared")
        }
    }

    /**
     * Clear WebView local storage
     */
    private fun clearWebstorage() {
        handler.post {
            WebStorage.getInstance().deleteAllData()
            Log.i(TAG, "WebView storage cleared")
        }
    }
}

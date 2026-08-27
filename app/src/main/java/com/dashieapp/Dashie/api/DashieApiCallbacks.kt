package com.dashieapp.Dashie.api

/**
 * Callbacks interface for the Dashie API server.
 *
 * Defines all operations the HTTP API server needs from the app.
 * Implemented by DashieApiService as an anonymous object that
 * bridges to callback properties set by DashieServiceManager.
 */
interface DashieApiCallbacks {
    // Screen control
    fun onScreenOn()
    fun onScreenOff(method: String? = null)  // method: "overlay", "hardware", or null (use default)
    fun isScreenOn(): Boolean
    fun setBrightness(level: Int)
    fun getBrightness(): Int
    fun getAmbientLight(): Int  // Current light sensor reading in lux

    // Screensaver
    fun startScreensaver()
    fun stopScreensaver()
    fun isInScreensaver(): Boolean

    // Navigation
    fun onLoadUrl(url: String)
    fun onLoadStartUrl()
    fun getCurrentUrl(): String
    fun getStartUrl(): String
    fun setStartUrl(url: String)

    // App control
    fun onRestartApp()
    fun onRebootDevice()  // Reboot entire device (requires root/system app)
    fun onToForeground()
    fun startApplication(packageName: String): Boolean

    // Audio
    fun setVolume(level: Int, stream: Int)
    fun getVolume(stream: Int): Int
    fun muteAudio(mute: Boolean)
    fun isMuted(): Boolean
    fun speakText(text: String)
    fun stopSpeaking()
    fun playSound(url: String)
    fun stopSound()
    fun pauseSound()
    fun resumeSound()
    fun seekSound(positionMs: Int)
    fun getAudioPosition(): Int  // Current playback position in milliseconds (cumulative in flow mode)
    fun getTrackPosition(): Int  // Track-relative position in milliseconds
    fun getAudioDuration(): Int  // Total duration in milliseconds
    fun getSoundUrlPlaying(): String  // Currently playing URL (empty if not playing/paused)
    fun isSoundPaused(): Boolean  // True if ExoPlayer has stream loaded but paused

    // Player identity and server URL (pushed by Music Assistant provider on first connect)
    fun setPlayerId(playerId: String, maServerUrl: String = "")

    // Media metadata (pushed by Music Assistant provider)
    fun setMediaInfo(title: String, artist: String, album: String, imageUrl: String, durationMs: Int, entityId: String = "", maServerUrl: String = "")
    fun getMediaTitle(): String
    fun getMediaArtist(): String
    fun getMediaAlbum(): String
    fun getMediaImageUrl(): String

    // UI
    fun showOverlayMessage(text: String, durationMs: Int)

    // Motion
    fun triggerMotion()

    // WebView
    fun clearCache()
    fun clearWebstorage()
    fun refreshWebView(callback: (Boolean) -> Unit)  // Trigger memory release (navigate to about:blank and back)

    // Memory diagnostics
    fun getAppMemoryMb(): Int  // Total app PSS (main + renderer)

    // Camera
    fun getCamshot(callback: (ByteArray?) -> Unit)

    // Screenshot (captures what's currently displayed on screen)
    fun getScreenshot(callback: (ByteArray?) -> Unit)

    // Kiosk lock (legacy - maps to lockSettings)
    fun lockKiosk()
    fun unlockKiosk()
    fun isKioskLocked(): Boolean  // Returns true if any lock is active

    // New lock states (more granular)
    fun isLockAppExit(): Boolean
    fun isLockSettings(): Boolean

    // PIN management
    fun setPin(pin: String): Boolean
    fun clearPin()
    fun hasPinSet(): Boolean
    fun verifyPin(pin: String): Boolean
    fun getStoredPin(): String  // Returns the stored PIN for verification

    // RTSP Camera Streaming
    fun startRtspStream(): Boolean
    fun stopRtspStream()
    fun isRtspStreamRunning(): Boolean
    fun getRtspStreamUrl(): String
    fun getRtspClientCount(): Int
    fun hasRtspFailed(): Boolean
    fun getRtspFailureReason(): String?

    // RTSP Configuration (requires stream restart to take effect)
    fun setRtspResolution(width: Int, height: Int)
    fun setRtspFps(fps: Int)
    fun setRtspBitrate(bitrate: Int)
    fun getRtspConfig(): RtspConfig

    // Device identification
    fun getDeviceName(): String  // User-configured device name from Supabase
    fun getDeviceUuid(): String  // Device UUID for SSDP/UPnP

    // Dark mode control
    fun isDarkMode(): Boolean
    fun setDarkMode(enabled: Boolean): Boolean
    fun canControlDarkMode(): Boolean

    // Screen off method (overlay vs hardware)
    fun getScreenOffMethod(): String  // "overlay" or "hardware"
    fun setScreenOffMethod(method: String): Boolean

    // Screensaver settings
    fun getScreensaverMode(): String
    fun setScreensaverMode(mode: String): Boolean
    fun getPhotoSource(): String
    fun setPhotoSource(source: String): Boolean
    fun getHaMediaFolder(): String
    fun setHaMediaFolder(folder: String): Boolean

    // Display/UI settings (for HA switch entities)
    fun isHideSidebar(): Boolean
    fun isHideHeader(): Boolean
    fun isKeepScreenOn(): Boolean
    fun isStartOnBoot(): Boolean
    fun isAutoBrightness(): Boolean
    fun getMotionWakeMode(): String

    // Setters for boolean settings
    fun setHideSidebar(enabled: Boolean): Boolean
    fun setHideHeader(enabled: Boolean): Boolean
    fun setKeepScreenOn(enabled: Boolean): Boolean
    fun setStartOnBoot(enabled: Boolean): Boolean
    fun setAutoBrightness(enabled: Boolean): Boolean
    fun setMotionWakeMode(mode: String): Boolean

    // RTSP stream control
    fun isRtspEnabled(): Boolean
    fun isRtspSoftwareEncoding(): Boolean
    fun setRtspEnabled(enabled: Boolean): Boolean
    fun setRtspSoftwareEncoding(enabled: Boolean): Boolean

    // WebView zoom/text scaling (50-200%)
    fun getTextScaling(): Int
    fun setTextScaling(zoom: Int): Boolean

    // Timer control (overlay iframe timers)
    fun createTimer(durationSeconds: Int, description: String?): Boolean
    fun cancelTimer(timerId: String?): Boolean

    // Voice command injection (for testing - bypasses wake word + STT)
    fun processVoiceCommand(transcript: String, callback: (success: Boolean, response: String?) -> Unit)

    // STT benchmark: decode ONE corpus clip through a named provider and report
    // time-to-final-from-end-of-speech + PSS. Dev harness (tools/stt-bench).
    fun runSttBench(
        filePath: String,
        providerName: String,
        timeoutMs: Long,
        callback: (org.json.JSONObject) -> Unit
    )

    // STT bench RECORD: capture the room to a WAV in the bench corpus dir, so a clip can be
    // made ON the device under test rather than pushed to it. Dev harness (tools/stt-bench).
    fun recordSttBench(
        clipName: String,
        durationMs: Long,
        callback: (org.json.JSONObject) -> Unit
    )

    // HA-Assist turn benchmark: drive a WHOLE HA Voice Assist turn from a staged clip, letting
    // the real stage planner and STT priority chain choose. Unlike runSttBench (which pins one
    // engine) this is the only instrument that can falsify "local STT never falls back to the
    // cloud" — validation matrix R5/R6.
    fun runHaAssistBench(
        filePath: String,
        timeoutMs: Long,
        allowBusy: Boolean,
        callback: (org.json.JSONObject) -> Unit
    )

    // API password (read dynamically so changes take effect immediately)
    fun getApiPassword(): String

    // HA sensor detection state (published via deviceInfo polling)
    fun isMotionDetected(): Boolean
    fun isFaceDetected(): Boolean

    // Whether the user has enabled the corresponding detection toggle on the
    // device. When false, the HA integration reports the binary_sensor as
    // unavailable (vs sending false, which would look identical to "active +
    // currently nothing detected"). Source of truth is HalitePreferences'
    // camera.haSensorMotionEnabled / camera.haSensorFaceEnabled.
    fun isMotionDetectionEnabled(): Boolean
    fun isFaceDetectionEnabled(): Boolean

    // MQTT state (for deviceInfo and listSettings responses)
    fun isMqttEnabled(): Boolean
    fun getMqttBaseTopic(): String

    // Video feed triggers (pushed by HA integration when monitored entities change state)
    // Params include entityId, state, and optionally: feedId, feedLabel, cameraEntityId,
    // mode, autoDismissSeconds, continueWhileActive, alertSound, streamSourceType, streamSourceUrl
    fun onVideoFeedTrigger(params: Map<String, String>)
    fun getVideoFeedTriggerEntities(): List<String>

    // Voice-config push (HA integration → anon kiosk): re-fetch /api/dashie/voice/status
    // and hard-apply the household account's voice pipeline. Fired when household sharing
    // is toggled (or the account's voice settings change) so the kiosk updates with no
    // voice command, settings visit, or reboot.
    fun onRefreshVoiceConfig()
}

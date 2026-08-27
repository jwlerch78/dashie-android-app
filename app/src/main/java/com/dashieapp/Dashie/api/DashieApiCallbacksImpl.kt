package com.dashieapp.Dashie.api

import android.content.Intent
import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences

/**
 * Named implementation of DashieApiCallbacks that delegates to
 * callback properties on DashieApiService.
 *
 * Extracted from the anonymous `object : DashieApiCallbacks` that was
 * previously defined inline inside `startApiServer()`.
 */
internal class DashieApiCallbacksImpl(
    private val service: DashieApiService
) : DashieApiCallbacks {

    companion object {
        private const val TAG = "DashieApiService"
    }

    override fun onScreenOn() {
        Log.d(TAG, "API: screenOn requested")
        service.wakeScreen()
        service.onScreenOnRequested?.invoke()
        // Also broadcast intent for MainActivity
        service.sendBroadcast(Intent(DashieApiService.ACTION_SCREEN_ON))
    }

    override fun onScreenOff(method: String?) {
        Log.d(TAG, "API: screenOff requested (method=$method)")
        service.onScreenOffRequested?.invoke(method)
        service.sendBroadcast(Intent(DashieApiService.ACTION_SCREEN_OFF))
    }

    override fun onLoadUrl(url: String) {
        Log.d(TAG, "API: loadUrl requested: $url")
        service.onLoadUrlRequested?.invoke(url)
        service.sendBroadcast(Intent(DashieApiService.ACTION_LOAD_URL).apply {
            putExtra(DashieApiService.EXTRA_URL, url)
        })
    }

    override fun onLoadStartUrl() {
        Log.d(TAG, "API: loadStartUrl requested")
        service.onLoadStartUrlRequested?.invoke()
        service.sendBroadcast(Intent(DashieApiService.ACTION_LOAD_START_URL))
    }

    override fun onRestartApp() {
        Log.d(TAG, "API: restartApp requested")
        service.onRestartAppRequested?.invoke()
        service.sendBroadcast(Intent(DashieApiService.ACTION_RESTART_APP))
    }

    override fun onRebootDevice() {
        Log.d(TAG, "API: rebootDevice requested")
        service.onRebootDeviceRequested?.invoke()
        service.sendBroadcast(Intent(DashieApiService.ACTION_REBOOT_DEVICE))
    }

    override fun onToForeground() {
        Log.d(TAG, "API: toForeground requested")
        service.onToForegroundRequested?.invoke()
        service.bringToForeground()
    }

    override fun setVolume(level: Int, stream: Int) {
        service.setVolumeCallback?.invoke(level, stream)
    }

    override fun getVolume(stream: Int): Int {
        return service.getVolumeCallback?.invoke(stream) ?: 50
    }

    override fun setBrightness(level: Int) {
        service.setBrightnessCallback?.invoke(level)
    }

    override fun getBrightness(): Int {
        return service.getBrightnessCallback?.invoke() ?: 128
    }

    override fun getAmbientLight(): Int {
        return service.getAmbientLightCallback?.invoke() ?: 0
    }

    override fun isScreenOn(): Boolean {
        return service.isScreenOnCallback?.invoke() ?: (service.powerManager?.isInteractive ?: true)
    }

    override fun getCurrentUrl(): String {
        return service.getCurrentUrlCallback?.invoke() ?: ""
    }

    override fun getStartUrl(): String {
        return service.getStartUrlCallback?.invoke() ?: HalitePreferences(service).connection.haUrl
    }

    override fun setStartUrl(url: String) {
        Log.d(TAG, "API: setStartUrl requested: $url")
        service.setStartUrlCallback?.invoke(url)
        // Also update preferences directly as fallback
        val prefs = HalitePreferences(service)
        prefs.connection.haUrl = url
        prefs.connection.parseUrl(url)  // Parse into base URL + dashboard name components
        // Broadcast to MainActivity to handle URL change
        service.sendBroadcast(Intent(DashieApiService.ACTION_LOAD_URL).apply {
            putExtra(DashieApiService.EXTRA_URL, url)
            putExtra("setAsStartUrl", true)
        })
    }

    override fun speakText(text: String) {
        Log.d(TAG, "API: textToSpeech requested: $text")
        service.speakTextCallback?.invoke(text)
    }

    override fun stopSpeaking() {
        Log.d(TAG, "API: stopTextToSpeech requested")
        service.stopSpeakingCallback?.invoke()
    }

    override fun startScreensaver() {
        Log.d(TAG, "API: startScreensaver requested")
        service.startScreensaverCallback?.invoke()
    }

    override fun stopScreensaver() {
        Log.d(TAG, "API: stopScreensaver requested")
        service.stopScreensaverCallback?.invoke()
    }

    override fun isInScreensaver(): Boolean {
        return service.isInScreensaverCallback?.invoke() ?: false
    }

    override fun playSound(url: String) {
        Log.d(TAG, "API: playSound requested: $url")
        service.playSoundCallback?.invoke(url)
    }

    override fun stopSound() {
        Log.d(TAG, "API: stopSound requested")
        service.stopSoundCallback?.invoke()
    }

    override fun pauseSound() {
        Log.d(TAG, "API: pauseSound requested")
        service.pauseSoundCallback?.invoke()
    }

    override fun resumeSound() {
        Log.d(TAG, "API: resumeSound requested")
        service.resumeSoundCallback?.invoke()
    }

    override fun seekSound(positionMs: Int) {
        Log.d(TAG, "API: seekSound requested: $positionMs ms")
        service.seekSoundCallback?.invoke(positionMs)
    }

    override fun muteAudio(mute: Boolean) {
        Log.d(TAG, "API: muteAudio requested: $mute")
        service.muteAudioCallback?.invoke(mute)
    }

    override fun isMuted(): Boolean {
        return service.isMutedCallback?.invoke() ?: false
    }

    override fun getAudioPosition(): Int {
        return service.getAudioPositionCallback?.invoke() ?: 0
    }

    override fun getTrackPosition(): Int {
        return service.getTrackPositionCallback?.invoke() ?: 0
    }

    override fun getAudioDuration(): Int {
        return service.getAudioDurationCallback?.invoke() ?: 0
    }

    override fun getSoundUrlPlaying(): String {
        return service.getSoundUrlPlayingCallback?.invoke() ?: ""
    }

    override fun isSoundPaused(): Boolean {
        return service.isSoundPausedCallback?.invoke() ?: false
    }

    override fun setPlayerId(playerId: String, maServerUrl: String) {
        Log.d(TAG, "API: setPlayerId: $playerId (server=$maServerUrl)")
        service.setPlayerIdCallback?.invoke(playerId, maServerUrl)
    }

    override fun setMediaInfo(title: String, artist: String, album: String, imageUrl: String, durationMs: Int, entityId: String, maServerUrl: String) {
        Log.d(TAG, "API: setMediaInfo: \"$title\" by $artist (entity=$entityId, maServer=$maServerUrl)")
        service.setMediaInfoCallback?.invoke(title, artist, album, imageUrl, durationMs, entityId, maServerUrl)
    }

    override fun getMediaTitle(): String {
        return service.getMediaTitleCallback?.invoke() ?: ""
    }

    override fun getMediaArtist(): String {
        return service.getMediaArtistCallback?.invoke() ?: ""
    }

    override fun getMediaAlbum(): String {
        return service.getMediaAlbumCallback?.invoke() ?: ""
    }

    override fun getMediaImageUrl(): String {
        return service.getMediaImageUrlCallback?.invoke() ?: ""
    }

    override fun startApplication(packageName: String): Boolean {
        Log.d(TAG, "API: startApplication requested: $packageName")
        return service.startApplicationCallback?.invoke(packageName) ?: false
    }

    override fun showOverlayMessage(text: String, durationMs: Int) {
        Log.d(TAG, "API: setOverlayMessage requested: $text ($durationMs ms)")
        service.showOverlayMessageCallback?.invoke(text, durationMs)
    }

    override fun triggerMotion() {
        Log.d(TAG, "API: triggerMotion requested")
        service.triggerMotionCallback?.invoke()
    }

    override fun clearCache() {
        Log.d(TAG, "API: clearCache requested")
        service.clearCacheCallback?.invoke()
    }

    override fun clearWebstorage() {
        Log.d(TAG, "API: clearWebstorage requested")
        service.clearWebstorageCallback?.invoke()
    }

    override fun refreshWebView(callback: (Boolean) -> Unit) {
        Log.d(TAG, "API: refreshWebView requested")
        val handler = service.refreshWebViewCallback
        if (handler != null) {
            handler.invoke(callback)
        } else {
            Log.w(TAG, "API: No refreshWebView handler registered")
            callback(false)
        }
    }

    override fun getAppMemoryMb(): Int {
        return service.getAppMemoryMbCallback?.invoke() ?: 0
    }

    override fun getCamshot(callback: (ByteArray?) -> Unit) {
        Log.d(TAG, "API: getCamshot requested")
        val captureCallback = service.getCamshotCallback
        if (captureCallback != null) {
            captureCallback(callback)
        } else {
            Log.e(TAG, "getCamshotCallback not set")
            callback(null)
        }
    }

    override fun getScreenshot(callback: (ByteArray?) -> Unit) {
        Log.d(TAG, "API: getScreenshot requested")
        val captureCallback = service.getScreenshotCallback
        if (captureCallback != null) {
            captureCallback(callback)
        } else {
            Log.e(TAG, "getScreenshotCallback not set")
            callback(null)
        }
    }

    override fun lockKiosk() {
        Log.d(TAG, "API: lockKiosk requested")
        service.lockKioskCallback?.invoke()
    }

    override fun unlockKiosk() {
        Log.d(TAG, "API: unlockKiosk requested")
        service.unlockKioskCallback?.invoke()
    }

    override fun isKioskLocked(): Boolean {
        return service.isKioskLockedCallback?.invoke() ?: false
    }

    override fun isLockAppExit(): Boolean {
        return service.isLockAppExitCallback?.invoke() ?: false
    }

    override fun isLockSettings(): Boolean {
        return service.isLockSettingsCallback?.invoke() ?: false
    }

    override fun setPin(pin: String): Boolean {
        Log.d(TAG, "API: setPin requested")
        return service.setPinCallback?.invoke(pin) ?: false
    }

    override fun clearPin() {
        Log.d(TAG, "API: clearPin requested")
        service.clearPinCallback?.invoke()
    }

    override fun hasPinSet(): Boolean {
        return service.hasPinSetCallback?.invoke() ?: false
    }

    override fun verifyPin(pin: String): Boolean {
        return service.verifyPinCallback?.invoke(pin) ?: false
    }

    override fun getStoredPin(): String {
        return service.getStoredPinCallback?.invoke() ?: ""
    }

    override fun startRtspStream(): Boolean {
        Log.d(TAG, "API: startRtspStream requested")
        return service.startRtspStreamCallback?.invoke() ?: false
    }

    override fun stopRtspStream() {
        Log.d(TAG, "API: stopRtspStream requested")
        service.stopRtspStreamCallback?.invoke()
    }

    override fun isRtspStreamRunning(): Boolean {
        return service.isRtspStreamRunningCallback?.invoke() ?: false
    }

    override fun getRtspStreamUrl(): String {
        return service.getRtspStreamUrlCallback?.invoke() ?: ""
    }

    override fun getRtspClientCount(): Int {
        return service.getRtspClientCountCallback?.invoke() ?: 0
    }

    override fun hasRtspFailed(): Boolean {
        return service.hasRtspFailedCallback?.invoke() ?: false
    }

    override fun getRtspFailureReason(): String? {
        return service.getRtspFailureReasonCallback?.invoke()
    }

    override fun setRtspResolution(width: Int, height: Int) {
        Log.d(TAG, "API: setRtspResolution requested: ${width}x${height}")
        service.setRtspResolutionCallback?.invoke(width, height)
    }

    override fun setRtspFps(fps: Int) {
        Log.d(TAG, "API: setRtspFps requested: $fps")
        service.setRtspFpsCallback?.invoke(fps)
    }

    override fun setRtspBitrate(bitrate: Int) {
        Log.d(TAG, "API: setRtspBitrate requested: $bitrate")
        service.setRtspBitrateCallback?.invoke(bitrate)
    }

    override fun getRtspConfig(): RtspConfig {
        return service.getRtspConfigCallback?.invoke()
            ?: RtspConfig(1280, 720, 15, 2_000_000, 8554, false)
    }

    override fun isDarkMode(): Boolean {
        return service.isDarkModeCallback?.invoke() ?: false
    }

    override fun setDarkMode(enabled: Boolean): Boolean {
        Log.d(TAG, "API: setDarkMode($enabled) requested")
        return service.setDarkModeCallback?.invoke(enabled) ?: false
    }

    override fun canControlDarkMode(): Boolean {
        return service.canControlDarkModeCallback?.invoke() ?: false
    }

    // Screen off method
    override fun getScreenOffMethod(): String {
        return service.getScreenOffMethodCallback?.invoke() ?: "overlay"
    }

    override fun setScreenOffMethod(method: String): Boolean {
        Log.d(TAG, "API: setScreenOffMethod($method) requested")
        return service.setScreenOffMethodCallback?.invoke(method) ?: false
    }

    // Screensaver settings
    override fun getScreensaverMode(): String {
        return service.getScreensaverModeCallback?.invoke() ?: "dim"
    }

    override fun setScreensaverMode(mode: String): Boolean {
        Log.d(TAG, "API: setScreensaverMode($mode) requested")
        return service.setScreensaverModeCallback?.invoke(mode) ?: false
    }

    override fun getPhotoSource(): String {
        return service.getPhotoSourceCallback?.invoke() ?: "local"
    }

    override fun setPhotoSource(source: String): Boolean {
        Log.d(TAG, "API: setPhotoSource($source) requested")
        return service.setPhotoSourceCallback?.invoke(source) ?: false
    }

    override fun getHaMediaFolder(): String {
        return service.getHaMediaFolderCallback?.invoke() ?: "."
    }

    override fun setHaMediaFolder(folder: String): Boolean {
        Log.d(TAG, "API: setHaMediaFolder($folder) requested")
        return service.setHaMediaFolderCallback?.invoke(folder) ?: false
    }

    // Display/UI settings
    override fun isHideSidebar(): Boolean {
        return service.isHideSidebarCallback?.invoke() ?: false
    }

    override fun isHideHeader(): Boolean {
        return service.isHideHeaderCallback?.invoke() ?: false
    }

    override fun isKeepScreenOn(): Boolean {
        return service.isKeepScreenOnCallback?.invoke() ?: true
    }

    override fun isStartOnBoot(): Boolean {
        return service.isStartOnBootCallback?.invoke() ?: true
    }

    override fun isAutoBrightness(): Boolean {
        return service.isAutoBrightnessCallback?.invoke() ?: false
    }

    override fun isRtspEnabled(): Boolean {
        return service.isRtspEnabledCallback?.invoke() ?: false
    }

    override fun isRtspSoftwareEncoding(): Boolean {
        return service.isRtspSoftwareEncodingCallback?.invoke() ?: false
    }

    override fun getMotionWakeMode(): String {
        return service.getMotionWakeModeCallback?.invoke() ?: "disabled"
    }

    override fun getTextScaling(): Int {
        return service.getTextScalingCallback?.invoke() ?: 100
    }

    // Setters for boolean/string settings
    override fun setHideSidebar(enabled: Boolean): Boolean {
        Log.d(TAG, "API: setHideSidebar($enabled) requested")
        return service.setHideSidebarCallback?.invoke(enabled) ?: false
    }

    override fun setHideHeader(enabled: Boolean): Boolean {
        Log.d(TAG, "API: setHideHeader($enabled) requested")
        return service.setHideHeaderCallback?.invoke(enabled) ?: false
    }

    override fun setKeepScreenOn(enabled: Boolean): Boolean {
        Log.d(TAG, "API: setKeepScreenOn($enabled) requested")
        return service.setKeepScreenOnCallback?.invoke(enabled) ?: false
    }

    override fun setStartOnBoot(enabled: Boolean): Boolean {
        Log.d(TAG, "API: setStartOnBoot($enabled) requested")
        return service.setStartOnBootCallback?.invoke(enabled) ?: false
    }

    override fun setAutoBrightness(enabled: Boolean): Boolean {
        Log.d(TAG, "API: setAutoBrightness($enabled) requested")
        return service.setAutoBrightnessCallback?.invoke(enabled) ?: false
    }

    override fun setMotionWakeMode(mode: String): Boolean {
        Log.d(TAG, "API: setMotionWakeMode($mode) requested")
        return service.setMotionWakeModeCallback?.invoke(mode) ?: false
    }

    override fun setRtspEnabled(enabled: Boolean): Boolean {
        Log.d(TAG, "API: setRtspEnabled($enabled) requested")
        return service.setRtspEnabledCallback?.invoke(enabled) ?: false
    }

    override fun setRtspSoftwareEncoding(enabled: Boolean): Boolean {
        Log.d(TAG, "API: setRtspSoftwareEncoding($enabled) requested")
        return service.setRtspSoftwareEncodingCallback?.invoke(enabled) ?: false
    }

    override fun setTextScaling(zoom: Int): Boolean {
        Log.d(TAG, "API: setTextScaling($zoom) requested")
        return service.setTextScalingCallback?.invoke(zoom) ?: false
    }

    override fun getApiPassword(): String {
        // Read password dynamically so changes take effect immediately
        return if (com.dashieapp.Dashie.BuildConfig.ALLOW_URL_CONFIG) {
            HalitePreferences(service).connection.apiPassword
        } else {
            com.dashieapp.Dashie.api.DashieApiPreferences(service).apiPassword
        }
    }

    override fun getDeviceName(): String {
        return service.getDeviceNameCallback?.invoke() ?: android.os.Build.MODEL
    }

    override fun getDeviceUuid(): String {
        return HalitePreferences(service).connection.deviceUuid
    }

    // Timer control
    override fun createTimer(durationSeconds: Int, description: String?): Boolean {
        Log.d(TAG, "API: createTimer requested: ${durationSeconds}s, desc=$description")
        return service.createTimerCallback?.invoke(durationSeconds, description) ?: false
    }

    override fun cancelTimer(timerId: String?): Boolean {
        Log.d(TAG, "API: cancelTimer requested: id=$timerId")
        return service.cancelTimerCallback?.invoke(timerId) ?: false
    }

    // HA sensor detection state
    override fun isMotionDetected(): Boolean {
        return service.isMotionDetectedCallback?.invoke() ?: false
    }

    override fun isFaceDetected(): Boolean {
        return service.isFaceDetectedCallback?.invoke() ?: false
    }

    override fun isMotionDetectionEnabled(): Boolean {
        return service.isMotionDetectionEnabledCallback?.invoke() ?: false
    }

    override fun isFaceDetectionEnabled(): Boolean {
        return service.isFaceDetectionEnabledCallback?.invoke() ?: false
    }

    // MQTT state
    override fun isMqttEnabled(): Boolean {
        return service.isMqttEnabledCallback?.invoke() ?: false
    }

    override fun getMqttBaseTopic(): String {
        return service.getMqttBaseTopicCallback?.invoke() ?: ""
    }

    // Video feed triggers (pushed by HA integration)
    override fun onVideoFeedTrigger(params: Map<String, String>) {
        service.onVideoFeedTriggerCallback?.invoke(params)
    }

    override fun getVideoFeedTriggerEntities(): List<String> {
        return service.getVideoFeedTriggerEntitiesCallback?.invoke() ?: emptyList()
    }

    // Voice-config push (HA integration → anon-kiosk re-fetch + hard-apply). Calls the
    // same capability refresh the pipeline runs at init — it re-probes the integration and,
    // for an anonymous kiosk, hard-applies the account pipeline + fires the reinit broadcast.
    // async (OkHttp enqueue), safe to call off the main thread.
    override fun onRefreshVoiceConfig() {
        Log.i(TAG, "API: refreshVoiceConfig — refreshing kiosk capability")
        try {
            val prefs = HalitePreferences(service)
            com.dashieapp.Dashie.halite.voice.VoiceSessionAccess.refreshKioskCapability(
                service.applicationContext,
                prefs
            )

            // ── Also re-verify our ACCOUNT SESSION (Kiosk Real Login, D5/D6) ──
            //
            // The add-on fires this exact push whenever household sharing is toggled
            // (settings.js → dashie.refresh_voice_config → the integration relays it here). That
            // makes it the perfect — and free — moment to ask "am I still on this account?".
            //
            // It matters because `refresh_jwt` is the ONLY endpoint that checks device liveness;
            // every other edge function accepts a signature-valid token. So a kiosk revoked by
            // D6's sharing-off sweep would otherwise keep FULL account access until it happened to
            // refresh — up to 72h after the user was told it was signed out. Riding this push
            // makes that seconds instead, with no polling and no new periodic edge call.
            //
            // No-ops on a device with no session.
            com.dashieapp.Dashie.halite.auth.KioskJwtRefresher.verifySessionNow(
                prefs, service.applicationContext
            ) { outcome ->
                if (outcome == com.dashieapp.Dashie.halite.auth.KioskJwtRefresher.Outcome.REVOKED) {
                    Log.w(TAG, "refreshVoiceConfig: session was revoked — signed out")
                    // endSession() already reset credits + reverted the pipeline off
                    // cloud; reinit the RUNNING voice controller so it stops using cloud
                    // STT/TTS/AI on the very next turn instead of only after a reload.
                    service.applicationContext.sendBroadcast(
                        android.content.Intent("com.dashieapp.Dashie.ACTION_VOICE_PIPELINE_REINIT")
                            .apply { setPackage(service.applicationContext.packageName) }
                    )
                }
            }

            // ── …and the SIGN-IN half (the add-on fires this push on BOTH toggle directions) ──
            //
            // Sharing back ON must re-authorize the kiosk as promptly as sharing OFF signs it out.
            // Without this the loop is asymmetric: the tablet would sit signed-out until its next
            // app boot or HA-token rotation (~30 min) — so "turn sharing back on and it
            // re-authorizes automatically", which the sign-out dialog literally promises, would
            // quietly take half an hour.
            //
            // Costs nothing: it's the same push we're already handling. No-ops when the device is
            // already logged in, when HA isn't configured, or when sharing is still off (the
            // server refuses and we stay anonymous).
            com.dashieapp.Dashie.halite.auth.KioskSessionProvisioner.provisionIfNeeded(
                service.applicationContext, prefs
            ) { outcome ->
                if (outcome == com.dashieapp.Dashie.halite.auth.KioskSessionProvisioner.Outcome.PROVISIONED) {
                    Log.i(TAG, "refreshVoiceConfig: sharing is on — kiosk re-provisioned")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshVoiceConfig failed: ${e.message}")
        }
    }

    // Voice command injection (for testing)
    override fun processVoiceCommand(transcript: String, callback: (Boolean, String?) -> Unit) {
        Log.d(TAG, "API: processVoiceCommand requested: \"$transcript\"")
        val handler = service.processVoiceCommandCallback
        if (handler != null) {
            handler.invoke(transcript, callback)
        } else {
            Log.w(TAG, "API: No voice command handler registered")
            callback(false, "Voice command handler not available")
        }
    }

    // STT benchmark (dev harness — tools/stt-bench)
    override fun runSttBench(
        filePath: String,
        providerName: String,
        timeoutMs: Long,
        callback: (org.json.JSONObject) -> Unit
    ) {
        Log.d(TAG, "API: runSttBench provider=$providerName file=$filePath")
        val handler = service.sttBenchCallback
        if (handler != null) {
            handler.invoke(filePath, providerName, timeoutMs, callback)
        } else {
            Log.w(TAG, "DROP: no STT bench handler registered (voice not initialized?)")
            callback(org.json.JSONObject().apply {
                put("error", "STT bench handler not available")
            })
        }
    }

    // STT bench RECORD (dev harness — tools/stt-bench)
    override fun recordSttBench(
        clipName: String,
        durationMs: Long,
        callback: (org.json.JSONObject) -> Unit
    ) {
        Log.d(TAG, "API: recordSttBench clip=$clipName ms=$durationMs")
        val handler = service.sttBenchRecordCallback
        if (handler != null) {
            handler.invoke(clipName, durationMs, callback)
        } else {
            Log.w(TAG, "DROP: no STT bench record handler registered (voice not initialized?)")
            callback(org.json.JSONObject().apply {
                put("error", "STT bench record handler not available")
            })
        }
    }

    // HA-Assist turn benchmark (dev harness — test-rig/voice-matrix.sh R5/R6)
    override fun runHaAssistBench(
        filePath: String,
        timeoutMs: Long,
        allowBusy: Boolean,
        callback: (org.json.JSONObject) -> Unit
    ) {
        Log.d(TAG, "API: runHaAssistBench file=$filePath allowBusy=$allowBusy")
        val handler = service.haAssistBenchCallback
        if (handler != null) {
            handler.invoke(filePath, timeoutMs, allowBusy, callback)
        } else {
            Log.w(TAG, "DROP: no HA-Assist bench handler registered (voice not initialized?)")
            callback(org.json.JSONObject().apply {
                put("error", "HA-Assist bench handler not available")
            })
        }
    }
}

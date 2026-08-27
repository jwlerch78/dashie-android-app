package com.dashieapp.Dashie.halite.sidebar

import com.dashieapp.Dashie.halite.HalitePreferences

/**
 * Callbacks interface for settings that need external handling.
 * Used by both HaliteSidebarController and HaUrlSetupActivity.
 *
 * All methods have default no-op implementations so callers only need
 * to override the ones they care about.
 */
interface SettingsCallbacks {
    // Screen & Power
    fun onKeepScreenOnChanged(enabled: Boolean) {}
    fun onMotionWakeModeChanged(mode: String) {}  // "disabled", "brightness", "camera"
    fun onStartOnBootChanged(enabled: Boolean) {}
    fun onLockToAppChanged(enabled: Boolean) {}
    fun onScreensaverSettingsChanged(timeout: Int, mode: String) {}

    // Storage permission for photo screensaver
    fun hasStoragePermission(): Boolean { return false }
    fun requestStoragePermission(onGranted: () -> Unit) {}

    // Folder picker for photo source
    fun launchFolderPicker(onFolderSelected: (uri: android.net.Uri) -> Unit) {}

    // Brightness
    fun onAutoBrightnessChanged(
        enabled: Boolean,
        min: Int,
        max: Int,
        curve: String = HalitePreferences.BRIGHTNESS_CURVE_LINEAR
    ) {}
    fun onManualBrightnessChanged(brightness: Int) {}
    fun canWriteBrightnessSettings(): Boolean { return true }
    fun requestBrightnessPermission(): Boolean { return false }
    fun getCurrentBrightness(): Int { return 50 }

    // Dark Mode (requires WRITE_SECURE_SETTINGS permission via ADB)
    fun canWriteSecureSettings(): Boolean { return false }
    fun isSystemDarkMode(): Boolean { return false }
    fun onDarkModeChanged(enabled: Boolean) {}

    // Voice
    fun onVoiceEnabledChanged(enabled: Boolean) {}
    fun onShowResponsesChanged(enabled: Boolean) {}
    fun onReadResponsesAloudChanged(enabled: Boolean) {}
    fun onMicMutedChanged(muted: Boolean) {}

    // Telemetry & Beta
    fun onSampleCollectionChanged(enabled: Boolean) {}
    fun onDashboardTelemetryChanged(enabled: Boolean) {}
    fun onInteractionPriorityChanged(enabled: Boolean) {}

    // API
    fun onApiEnabledChanged(enabled: Boolean) {}
    fun onApiPermissionsCheck() {}

    // UI
    fun onHideSidebarChanged(enabled: Boolean) {}
    fun onHideHeaderChanged(enabled: Boolean) {}
    fun onUrlChangeRequested() {}
    fun onRestartRequired(message: String) {}

    // Dialog lifecycle callbacks for immersive mode re-application
    fun onDialogShown() {}
    fun onDialogDismissed() {}

    // RTSP Camera Streaming
    fun onRtspEnabledChanged(enabled: Boolean) {}
    fun onRtspResolutionChanged(resolution: String) {}
    fun onRtspFpsChanged(fps: Int) {}
    fun onRtspMotionDetectionChanged(enabled: Boolean) {}
    fun getRtspStreamUrl(): String { return "" }
    fun isRtspServerRunning(): Boolean { return false }
    fun getRtspClientCount(): Int { return 0 }
    fun restartRtspServerIfNeeded() {}  // Restart RTSP server after camera was used by another component

    // Temporary RTSP for motion wake debugging (starts RTSP if not running, for preview/graph)
    fun startRtspForDebugging(): Boolean { return false }  // Returns true if we started it
    fun stopRtspForDebugging() {}  // Stops RTSP if we started it temporarily
    fun isRtspV2Running(): Boolean { return false }  // Check if v2 (supports preview) vs v1 (no preview)

    // Camera permission (for motion wake camera mode)
    fun hasCameraPermission(): Boolean { return false }
    fun requestCameraPermission(onResult: (granted: Boolean) -> Unit) {}

    // Camera preview (for motion wake settings debugging)
    fun getCameraPreviewFrame(): ByteArray? { return null }

    // Current motion score (for motion wake graph visualization)
    fun getCurrentMotionScore(): Double { return 0.0 }

    // Graph mode (enable camera scoring without wake triggers)
    fun enableMotionGraphMode() {}
    fun disableMotionGraphMode() {}

    // Face detection test mode (for settings dialog indicator)
    // Callback receives FaceWakeDetector.FACE_RESULT_NONE/DETECTED/TOO_FAR
    fun setFaceTestCallback(callback: ((Int) -> Unit)?) {}

    // Update face distance filter on live detector (for settings dialog real-time preview)
    fun updateFaceDistance(percent: Float) {}

    // Microphone permission (for RTSP streaming audio)
    fun hasMicrophonePermission(): Boolean { return false }

    // Music Player
    fun onMusicPlayerEnabledChanged(enabled: Boolean) {}
    fun onMusicPlayerEntityChanged(entityId: String) {}
    fun onMusicPlayerShowWithScreensaverChanged(enabled: Boolean) {}
    fun onShowMusicPlayer() {}
    fun onHideMusicPlayer() {}
    fun isMusicPlayerVisible(): Boolean { return false }
}

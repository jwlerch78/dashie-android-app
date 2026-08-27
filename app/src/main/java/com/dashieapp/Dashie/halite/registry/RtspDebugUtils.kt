package com.dashieapp.Dashie.halite.registry

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.halite.FaceWakeDetector
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.HaliteComponentWiring

private const val TAG = "HaliteRegistry"

/** Mutable state for RTSP debugging — stored outside the registry since extension properties can't have backing fields */
internal object RtspDebugState {
    var startedRtspForDebugging = false
    var tempTestFaceDetector = false
}

/** Check if voice should auto-start on resume */
internal fun HaliteComponentRegistry.checkVoiceOnResume() {
    if (prefs.voice.voiceEnabled) {
        voiceController?.checkPermissionAndInit()
    }
}

/** Start RTSP server if enabled */
internal fun HaliteComponentRegistry.startRtspIfEnabled() {
    if (prefs.camera.rtspEnabled) {
        // If voice is also enabled, defer RTSP start to onVoiceInitialized callback
        // (in HaliteComponentWiring). Voice creates SharedAudioBuffer first, then RTSP
        // starts with shared audio. Starting RTSP here would grab the mic with a dedicated
        // AudioRecord, blocking voice's AudioCaptureService on single-stream audio HALs.
        if (prefs.voice.voiceEnabled) {
            Log.i(TAG, "RTSP enabled but voice also enabled - deferring RTSP start until voice init provides SharedAudioBuffer")
            return
        }

        // Check if we have the required permissions before starting
        val hasCamera = androidx.core.content.ContextCompat.checkSelfPermission(
            activityRef, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
            activityRef, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasCamera || !hasMic) {
            Log.i(TAG, "RTSP enabled but missing permissions (camera=$hasCamera, mic=$hasMic) - requesting permissions")
            // Fire OS can silently revoke permissions. Trigger the permission request
            // flow so the user gets prompted instead of RTSP silently failing.
            onRtspEnabledChanged?.invoke(true)
            return
        }

        Log.i(TAG, "RTSP enabled - releasing camera and starting server")
        screenController?.enableRtspMotionMode()
        // Increased delay to 1500ms for devices with slow camera HAL (Samsung Tab A8, etc.)
        Handler(Looper.getMainLooper()).postDelayed({
            dashieServiceManager?.startRtspServer()
            Log.i(TAG, "RTSP camera server auto-started")
            // Wire face frame callback if face mode is active
            wireRtspFaceFrameCallback()
            // Wire HA sensor callbacks if enabled
            HaliteComponentWiring.applyHaSensorConfig(this)
        }, 1500) // Was 500ms, too short for some devices
    }
}

/** Get RTSP stream URL */
internal fun HaliteComponentRegistry.getRtspStreamUrl(): String = dashieServiceManager?.getRtspStreamUrl() ?: ""

/** Check if RTSP server is running */
internal fun HaliteComponentRegistry.isRtspServerRunning(): Boolean = dashieServiceManager?.isRtspServerRunning() ?: false

/** Get RTSP client count */
internal fun HaliteComponentRegistry.getRtspClientCount(): Int = dashieServiceManager?.getRtspClientCount() ?: 0

/** Check if RTSP v2 is running (supports camera preview frames) */
internal fun HaliteComponentRegistry.isRtspV2Running(): Boolean = dashieServiceManager?.isRtspV2Running() ?: false

/**
 * Wire face frame callback from RTSP camera to face detector.
 * Call this after RTSP server starts when face wake mode is active.
 */
internal fun HaliteComponentRegistry.wireRtspFaceFrameCallback() {
    val motionWakeMode = prefs.screensaver.motionWakeMode ?: "disabled"
    if (motionWakeMode != "face") return

    // Prefer Camera2 hardware face detection (zero CPU cost, runs on ISP)
    if (dashieServiceManager?.isHwFaceDetectSupported() == true) {
        Log.i(TAG, "Wiring HW (Camera2 ISP) face detection (face mode active)")
        screenController?.useHwFaceDetection = true
        val faceDistPct = com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences
            .faceDistanceToPercent(prefs.screensaver.faceWakeDistance)
        dashieServiceManager?.setHwFaceMinSizePercent(faceDistPct)
        dashieServiceManager?.setHwFaceDetectedCallback {
            screenController?.onHwFaceDetected()
        }
    } else {
        screenController?.useHwFaceDetection = false
        Log.i(TAG, "Wiring ML Kit face frame callback (no HW face support)")
    }
    // Always wire ML Kit bitmap callback as fallback. When HW face is active,
    // Camera2MotionVideoSource skips the YUV→Bitmap conversion (zero cost).
    // If HW face is detected as broken at runtime, the flag flips and ML Kit
    // kicks in automatically without needing to re-wire.
    dashieServiceManager?.setFaceFrameCallback { bitmap, rotation ->
        screenController?.forwardRtspFrameToFace(bitmap, rotation)
    }

    // Lower RTSP motion threshold for face mode (1.5%) so motion triggers face search
    // more easily. The user's general threshold (typically 5%) is too high for face mode
    // since we have the second-stage face confirmation to prevent false wakes.
    dashieServiceManager?.setRtspMotionThreshold(1.5)
    Log.d(TAG, "RTSP motion threshold set to 1.5% for face mode")
}

/**
 * Clear face frame callback from RTSP camera.
 */
internal fun HaliteComponentRegistry.clearRtspFaceFrameCallback() {
    dashieServiceManager?.setFaceFrameCallback(null)
    dashieServiceManager?.setHwFaceDetectedCallback(null)
    dashieServiceManager?.setHwFaceResultCallback(null)
}

/**
 * Start RTSP temporarily for motion wake debugging (preview/graph).
 * Returns true if we started it (caller should stop it later).
 */
internal fun HaliteComponentRegistry.startRtspForDebugging(): Boolean {
    if (isRtspServerRunning()) {
        // Already running, don't track as temporary
        return false
    }

    // Stop CameraMotionDetector first to release the camera
    // This prevents "camera in use" errors when starting RTSP
    android.util.Log.i("HaliteRegistry", "Stopping CameraMotionDetector before starting RTSP for debugging")
    screenController?.enableRtspMotionMode()

    // Give camera time to fully release (CameraMotionDetector uses CameraX which is async)
    Thread.sleep(500)

    // Start RTSP temporarily (forceStart=true bypasses preference check)
    val started = dashieServiceManager?.startRtspServer(forceStart = true) ?: false
    if (started) {
        RtspDebugState.startedRtspForDebugging = true
        android.util.Log.i("HaliteRegistry", "Started RTSP temporarily for motion wake debugging")
        // Wire face frame callback if face mode is active
        wireRtspFaceFrameCallback()
    } else {
        // Failed to start RTSP, re-enable CameraMotionDetector
        android.util.Log.w("HaliteRegistry", "Failed to start RTSP for debugging, re-enabling CameraMotionDetector")
        screenController?.disableRtspMotionMode()
    }
    return started
}

/**
 * Stop RTSP if we started it temporarily for debugging.
 */
internal fun HaliteComponentRegistry.stopRtspForDebugging() {
    if (RtspDebugState.startedRtspForDebugging) {
        clearRtspFaceFrameCallback()
        dashieServiceManager?.stopRtspServer()
        RtspDebugState.startedRtspForDebugging = false
        android.util.Log.i("HaliteRegistry", "Stopped temporary RTSP for motion wake debugging")

        // Re-enable CameraMotionDetector now that RTSP is stopped
        android.util.Log.i("HaliteRegistry", "Re-enabling CameraMotionDetector after RTSP debugging")
        screenController?.disableRtspMotionMode()
    }
}

/** Get camera preview frame for motion wake settings */
internal fun HaliteComponentRegistry.getCameraPreviewFrame(): ByteArray? = dashieServiceManager?.getCameraPreviewFrame()

/** Get current motion score for motion wake graph visualization */
internal fun HaliteComponentRegistry.getCurrentMotionScore(): Double {
    // First try to get score from screen controller (CameraMotionDetector)
    val screenScore = screenController?.getCurrentMotionScore() ?: 0.0
    if (screenScore > 0.0) return screenScore

    // Fallback to RTSP motion score if available
    return dashieServiceManager?.getCurrentRtspMotionScore() ?: 0.0
}

/** Enable graph mode - camera scores motion without triggering wake events */
internal fun HaliteComponentRegistry.enableMotionGraphMode() = screenController?.enableMotionGraphMode()

/** Disable graph mode */
internal fun HaliteComponentRegistry.disableMotionGraphMode() = screenController?.disableMotionGraphMode()

/** Set face detection test callback for settings dialog indicator */
internal fun HaliteComponentRegistry.setFaceTestCallback(callback: ((Int) -> Unit)?) {
    val sc = screenController ?: return

    // HW face detection path: use Camera2 ISP results directly for test indicator
    if (dashieServiceManager?.isHwFaceDetectSupported() == true) {
        if (callback != null) {
            dashieServiceManager?.setHwFaceResultCallback(callback)
            Log.i(TAG, "HW face test callback set (Camera2 ISP)")
        } else {
            dashieServiceManager?.setHwFaceResultCallback(null)
            Log.i(TAG, "HW face test callback cleared")
        }
        return
    }

    // ML Kit fallback path
    if (callback != null) {
        // If no face detection pipeline exists (fresh install, mode not yet "face"),
        // create a temporary RTSP face detector and wire frames from the RTSP camera.
        // The face frame callback is stored by RtspStreamingManager so it works
        // even if RTSP hasn't started yet (applied when RTSP starts later).
        if (!sc.hasFaceTestCapability()) {
            sc.createTempRtspFaceDetector()
            dashieServiceManager?.setFaceFrameCallback { bitmap, rotation ->
                sc.forwardRtspFrameToFace(bitmap, rotation)
            }
            RtspDebugState.tempTestFaceDetector = true
            Log.i(TAG, "Created temporary face test pipeline for settings preview")
        }
    }

    sc.setFaceTestCallback(callback)

    // Clean up temporary face detector when test mode ends
    if (callback == null && RtspDebugState.tempTestFaceDetector) {
        sc.destroyTempRtspFaceDetector()
        RtspDebugState.tempTestFaceDetector = false
        Log.i(TAG, "Destroyed temporary face test pipeline")

        // If face wake mode is active, recreate the permanent face detector
        // and re-wire face frame callback so face wake continues working
        val motionWakeMode = prefs.screensaver.motionWakeMode ?: "disabled"
        if (motionWakeMode == "face" && isRtspServerRunning()) {
            Log.i(TAG, "Recreating permanent RTSP face detector after temp cleanup")
            sc.createRtspFaceDetector()
            wireRtspFaceFrameCallback()
        } else {
            dashieServiceManager?.setFaceFrameCallback(null)
        }
    }
}

/** Update face distance filter on live detector */
internal fun HaliteComponentRegistry.updateFaceDistance(percent: Float) {
    screenController?.updateFaceDistance(percent)
    // Also update HW face detection distance filter
    dashieServiceManager?.setHwFaceMinSizePercent(percent)
}

/** Get feature state for telemetry */
internal fun HaliteComponentRegistry.getFeatureState(): org.json.JSONObject {
    return org.json.JSONObject().apply {
        put("wakeWordActive", voiceController?.isWakeWordActive() ?: false)
        put("voiceInteractionActive", voiceController?.isVoiceInteractionActive() ?: false)
        put("cameraStreamActive", false)
    }
}

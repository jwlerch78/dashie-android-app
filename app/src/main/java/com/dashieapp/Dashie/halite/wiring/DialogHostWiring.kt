package com.dashieapp.Dashie.halite.wiring

import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.registry.*

/**
 * Wires NativeDialogHost callbacks for exit, voice, screensaver, and motion wake.
 * Extracted from HaliteComponentWiring.
 */
object DialogHostWiring {
    private const val TAG = "DialogHostWiring"

    fun wireDialogHostCallbacks(registry: HaliteComponentRegistry, webViewProvider: () -> WebView) {
        val dialogHost = registry.dialogHost ?: return

        dialogHost.onExitApp = { registry.onExitApp?.invoke() }
        dialogHost.onLogout = { registry.onLogout?.invoke() }
        dialogHost.onRestartApp = { registry.onRestartApp?.invoke() }

        dialogHost.onVoiceEnabledChanged = { enabled ->
            registry.voiceController?.let { voice ->
                if (enabled) {
                    voice.checkPermissionAndInit()
                } else {
                    voice.disable()
                }
            }
        }

        dialogHost.onScreensaverSettingsChanged = { timeout, mode ->
            registry.screenController?.updateScreensaverSettings(timeout, mode)
        }

        dialogHost.onMotionWakeModeChanged = { mode ->
            Log.i(TAG, "Motion wake mode changed to: $mode")
            registry.screenController?.refreshMotionWake()
            val threshold = if (mode == "face") 1.5 else (registry.prefs.screensaver.cameraWakeThresholdDouble ?: 5.0)
            Log.i(TAG, "Updating RTSP motion threshold to: $threshold%")
            registry.dashieServiceManager?.setRtspMotionThreshold(threshold)
            if (mode == "face" && registry.isRtspServerRunning()) {
                registry.screenController?.enableRtspMotionMode()
                registry.wireRtspFaceFrameCallback()
            } else {
                registry.clearRtspFaceFrameCallback()
            }
        }

        dialogHost.onLockToAppChanged = { enabled ->
            Log.i(TAG, "🔒 Lock to app = $enabled (from NativeDialogHost)")
            if (enabled) {
                registry.onStartLockToApp?.invoke()
            } else {
                registry.onStopLockToApp?.invoke()
            }
        }

        dialogHost.onAutoBrightnessChanged = { enabled, min, max, curve ->
            registry.lightSensorController?.setEnabled(enabled)
            registry.lightSensorController?.setMinMax(min, max)
            registry.lightSensorController?.setCurve(curve)
        }

        // Wire SettingsCallbacks for motion wake preview dialog (camera preview, motion graph, face detection)
        dialogHost.setSettingsCallbacks(object : com.dashieapp.Dashie.halite.sidebar.SettingsCallbacks {
            override fun getCameraPreviewFrame(): ByteArray? = registry.getCameraPreviewFrame()
            override fun getCurrentMotionScore(): Double = registry.getCurrentMotionScore()
            override fun enableMotionGraphMode() { registry.enableMotionGraphMode() }
            override fun disableMotionGraphMode() { registry.disableMotionGraphMode() }
            override fun setFaceTestCallback(callback: ((Int) -> Unit)?) { registry.setFaceTestCallback(callback) }
            override fun updateFaceDistance(percent: Float) { registry.updateFaceDistance(percent) }
            override fun isRtspServerRunning(): Boolean = registry.isRtspServerRunning()
            override fun getRtspClientCount(): Int = registry.getRtspClientCount()
            override fun isRtspV2Running(): Boolean = registry.isRtspV2Running()
            override fun startRtspForDebugging(): Boolean = registry.startRtspForDebugging()
            override fun stopRtspForDebugging() { registry.stopRtspForDebugging() }
            override fun hasCameraPermission(): Boolean = registry.hasCameraPermission?.invoke() ?: false
            override fun requestCameraPermission(onResult: (granted: Boolean) -> Unit) {
                registry.requestCameraPermission?.invoke(onResult) ?: onResult(false)
            }
            override fun hasMicrophonePermission(): Boolean = registry.hasMicrophonePermission?.invoke() ?: false
        })
    }
}

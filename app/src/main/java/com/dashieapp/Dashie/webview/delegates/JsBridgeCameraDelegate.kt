package com.dashieapp.Dashie.webview.delegates

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.diagnostics.MediaCodecCapabilities
import com.dashieapp.Dashie.util.DeviceInfoHelper

/**
 * JS bridge delegate for camera, RTSP, video feed (PiP), alert volume, and HA
 * sensor publishing settings.
 *
 * Extracted from JsBridgeSettingsDelegate in Phase 7 (structural split).
 */
class JsBridgeCameraDelegate(
    private val context: Context,
    private val webView: WebView,
    private val halitePrefs: () -> HalitePreferences?
) {
    companion object {
        private const val TAG = "JsBridgeCamera"
    }

    // Callbacks
    var onRtspEnabledChanged: ((Boolean) -> Unit)? = null
    var isRtspRunning: (() -> Boolean)? = null
    var hasRtspFailed: (() -> Boolean)? = null
    var getRtspFailureReason: (() -> String?)? = null
    var onVideoFeedConfigChanged: ((String) -> Unit)? = null
    var onHaSensorConfigChanged: (() -> Unit)? = null
    var onPreviewVideoFeedChime: ((String) -> Unit)? = null
    var onAlertVolumeChanged: ((Float) -> Unit)? = null

    // ============================================
    // Video Feed PiP Settings
    // ============================================

    fun getVideoFeedSettings(): String {
        val prefs = halitePrefs()
        return prefs?.videoFeed?.configJson ?: """{"enabled":false,"rules":[]}"""
    }

    fun saveVideoFeedConfig(configJson: String) {
        Log.i(TAG, "saveVideoFeedConfig: ${configJson.take(100)}...")
        val videoFeedPrefs = halitePrefs()?.videoFeed ?: return

        // Preserve NATIVE-OWNED per-rule state from the existing rules — the JS side
        // doesn't manage per-device subscription state on Android (settings are
        // native-only), and it doesn't manage the HA-derived Frigate annotations
        // either (syncHaFeedsToLocal writes them from the integration's auto-match;
        // the JS mirror's cached copy predates them). A full-replace that keeps only
        // the JS fields silently strips isFrigateCamera on every overlay push, which
        // kills the playback button + voice playback until the next strip-open HA
        // sync (found live 2026-07-20: flags vanished on every app restart).
        try {
            val incoming = org.json.JSONObject(configJson)
            val incomingRules = incoming.optJSONArray("rules") ?: org.json.JSONArray()
            val existingModes = mutableMapOf<String, String>()
            val existingFrigate = mutableMapOf<String, Triple<Boolean, String, String>>()
            for (rule in videoFeedPrefs.getRules()) {
                val id = rule.optString("id", "")
                if (id.isEmpty()) continue
                val mode = rule.optString("subscriptionMode", "")
                if (mode.isNotEmpty()) existingModes[id] = mode
                if (rule.has("isFrigateCamera")) {
                    existingFrigate[id] = Triple(
                        rule.optBoolean("isFrigateCamera", false),
                        rule.optString("frigateCameraName", ""),
                        rule.optString("frigateCameraOverride", ""),
                    )
                }
            }
            for (i in 0 until incomingRules.length()) {
                val rule = incomingRules.getJSONObject(i)
                val id = rule.optString("id", "")
                existingModes[id]?.let { preserved ->
                    rule.put("subscriptionMode", preserved)
                    rule.put("enabled", preserved != "ignored")
                    if (preserved != "trigger" && preserved != "trigger_alert") {
                        rule.put("triggerEntityId", "")
                    }
                }
                // Only restore Frigate keys the incoming rule doesn't carry itself —
                // an (older) JS payload simply omits them.
                if (!rule.has("isFrigateCamera")) {
                    existingFrigate[id]?.let { (isFrigate, name, override) ->
                        rule.put("isFrigateCamera", isFrigate)
                        rule.put("frigateCameraName", name)
                        rule.put("frigateCameraOverride", override)
                    }
                }
            }
            incoming.put("rules", incomingRules)
            videoFeedPrefs.configJson = incoming.toString()
            webView.post { onVideoFeedConfigChanged?.invoke(incoming.toString()) }
        } catch (e: Exception) {
            // Incoming config is unparseable — nothing safe to save without risking
            // overwriting native subscription modes.
            Log.w(TAG, "saveVideoFeedConfig: skipping unparseable config", e)
        }
    }

    fun previewVideoFeedChime(soundName: String) {
        Log.d(TAG, "previewVideoFeedChime: $soundName")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onPreviewVideoFeedChime?.invoke(soundName)
        }
    }

    // ============================================
    // Alert Volume
    // ============================================

    /** Get alert volume as 0-100 percentage for JS slider */
    fun getAlertVolume(): Int {
        return ((halitePrefs()?.alert?.alertVolume ?: 0.40f) * 100).toInt()
    }

    /** Set alert volume from JS slider (0-100 percentage) */
    fun setAlertVolume(percent: Int) {
        Log.i(TAG, "setAlertVolume: $percent%")
        val volume = percent.coerceIn(0, 100) / 100f
        halitePrefs()?.alert?.alertVolume = volume
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onAlertVolumeChanged?.invoke(volume)
        }
    }

    // ============================================
    // Camera Streaming Settings (RTSP)
    // ============================================

    fun getRtspSettings(): String {
        val prefs = halitePrefs()
        val ipAddress = DeviceInfoHelper.getIpAddressCached(context)

        val isRunning = isRtspRunning?.invoke() ?: false
        val hasFailed = hasRtspFailed?.invoke() ?: false
        val failureReason = getRtspFailureReason?.invoke()

        return org.json.JSONObject().apply {
            put("enabled", prefs?.camera?.rtspEnabled ?: false)
            put("resolution", prefs?.camera?.rtspResolution ?: "720p")
            put("fps", prefs?.camera?.rtspFps ?: 15)
            put("softwareEncoding", prefs?.camera?.rtspForceSoftwareEncoding ?: false)
            put("disableMirrorCorrection", prefs?.camera?.rtspDisableMirrorCorrection ?: false)
            put("authEnabled", prefs?.camera?.rtspAuthEnabled ?: false)
            put("motionDetection", prefs?.camera?.rtspMotionDetection ?: true)
            put("port", prefs?.camera?.rtspPort ?: 8554)
            put("streamUrl", "rtsp://$ipAddress:${prefs?.camera?.rtspPort ?: 8554}")
            put("isRunning", isRunning)
            put("hasFailed", hasFailed)
            if (hasFailed && failureReason != null) {
                put("errorMessage", simplifyErrorMessage(failureReason))
            }
            // HA Sensor Publishing
            put("haSensorEnabled", prefs?.camera?.haSensorEnabled ?: false)
            put("haSensorMotionEnabled", prefs?.camera?.haSensorMotionEnabled ?: false)
            put("haSensorFaceEnabled", prefs?.camera?.haSensorFaceEnabled ?: false)
            put("motionThreshold", prefs?.screensaver?.cameraWakeThresholdDouble ?: 5.0)
            put("faceDistance", prefs?.screensaver?.faceWakeDistance ?: "near")
        }.toString()
    }

    private fun simplifyErrorMessage(technicalError: String): String {
        return when {
            technicalError.contains("no frames received", ignoreCase = true) ||
            technicalError.contains("no physical camera", ignoreCase = true) ->
                "Camera could not be found"
            technicalError.contains("camera in use", ignoreCase = true) ->
                "Camera is in use by another app"
            technicalError.contains("permission", ignoreCase = true) ->
                "Camera permission denied"
            technicalError.contains("encoder", ignoreCase = true) ->
                "Video encoder error"
            else -> "Camera streaming failed"
        }
    }

    fun getEncoderCapabilities(): String {
        return try {
            val encoderCaps = MediaCodecCapabilities.getH264EncoderCapabilities()
            val cameraCaps = MediaCodecCapabilities.getCameraCapabilities(context, useFrontCamera = true)
            org.json.JSONObject().apply {
                if (encoderCaps != null) {
                    put("encoderName", encoderCaps.encoderName)
                    put("isHardwareAccelerated", encoderCaps.isHardwareAccelerated)
                    put("widthAlignment", encoderCaps.widthAlignment)
                    put("heightAlignment", encoderCaps.heightAlignment)
                    put("widthRange", "${encoderCaps.widthRange.first}-${encoderCaps.widthRange.last}")
                    put("heightRange", "${encoderCaps.heightRange.first}-${encoderCaps.heightRange.last}")
                }
                if (cameraCaps != null) {
                    val ratios = org.json.JSONArray()
                    cameraCaps.nativeAspectRatios.forEach { ratios.put(it) }
                    put("cameraAspectRatios", ratios)
                }
            }.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get encoder capabilities", e)
            "{}"
        }
    }

    fun setRtspEnabled(enabled: Boolean) {
        Log.i(TAG, "📹 setRtspEnabled($enabled)")
        halitePrefs()?.camera?.rtspEnabled = enabled
        webView.post { onRtspEnabledChanged?.invoke(enabled) }
    }

    fun setRtspResolution(resolution: String) {
        halitePrefs()?.camera?.rtspResolution = resolution
    }

    fun setRtspCustomWidth(width: Int) {
        Log.i(TAG, "📹 setRtspCustomWidth($width)")
        halitePrefs()?.camera?.rtspCustomWidth = width
    }

    fun setRtspCustomHeight(height: Int) {
        Log.i(TAG, "📹 setRtspCustomHeight($height)")
        halitePrefs()?.camera?.rtspCustomHeight = height
    }

    fun setRtspFps(fps: Int) {
        halitePrefs()?.camera?.rtspFps = fps
    }

    fun setRtspSoftwareEncoding(enabled: Boolean) {
        halitePrefs()?.camera?.rtspForceSoftwareEncoding = enabled
    }

    fun setRtspDisableMirrorCorrection(enabled: Boolean) {
        halitePrefs()?.camera?.rtspDisableMirrorCorrection = enabled
    }

    // ============================================
    // HA Sensor Publishing
    // ============================================

    fun setHaSensorEnabled(enabled: Boolean) {
        Log.i(TAG, "setHaSensorEnabled($enabled)")
        halitePrefs()?.camera?.haSensorEnabled = enabled
        webView.post { onHaSensorConfigChanged?.invoke() }
    }

    fun setHaSensorMotionEnabled(enabled: Boolean) {
        Log.i(TAG, "setHaSensorMotionEnabled($enabled)")
        halitePrefs()?.camera?.haSensorMotionEnabled = enabled
        webView.post { onHaSensorConfigChanged?.invoke() }
    }

    fun setHaSensorFaceEnabled(enabled: Boolean) {
        Log.i(TAG, "setHaSensorFaceEnabled($enabled)")
        halitePrefs()?.camera?.haSensorFaceEnabled = enabled
        webView.post { onHaSensorConfigChanged?.invoke() }
    }
}

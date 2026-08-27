package com.dashieapp.Dashie.halite

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.dashieapp.Dashie.halite.diagnostics.SystemMetricsProvider
import com.dashieapp.Dashie.halite.preferences.PowerPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * JavaScript bridge exposing device metrics for the Dashie Camera Card.
 *
 * Registered as `window.dashieDevice` on the WebView. Provides:
 * - System metrics (RAM, CPU, thermal state, battery)
 * - Video codec support (H.264, H.265, VP8, VP9 decoders)
 * - Hardware decoder capabilities
 * - Platform detection
 *
 * This enables the Camera Card to:
 * 1. Detect supported video codecs and filter incompatible streams
 * 2. Select appropriate quality tier based on device resources
 * 3. Adapt streaming protocol (WebRTC vs HLS) based on platform
 *
 * @see <a href="/.reference/build-plans/20260129_DASHIE_CAMERA_CARD_CONCEPT.md">Camera Card Spec</a>
 */
class DeviceMetricsInterface(
    private val context: Context,
    private val webView: WebView
) {
    companion object {
        private const val TAG = "DeviceMetrics"

        // Video codec MIME types
        private const val MIME_H264 = "video/avc"
        private const val MIME_H265 = "video/hevc"
        private const val MIME_VP8 = "video/x-vnd.on2.vp8"
        private const val MIME_VP9 = "video/x-vnd.on2.vp9"
        private const val MIME_AV1 = "video/av01"
    }

    private val systemMetricsProvider = SystemMetricsProvider(context)
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /**
     * Get all system metrics for camera card quality selection.
     *
     * Called from JavaScript:
     * ```javascript
     * const metrics = await window.dashieDevice.getSystemMetrics();
     * const data = JSON.parse(metrics);
     * ```
     *
     * @return JSON string with device metrics
     */
    @JavascriptInterface
    fun getSystemMetrics(): String {
        return try {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)

            val metrics = JSONObject().apply {
                // RAM metrics (MB)
                put("ramAvailable", memInfo.availMem / (1024 * 1024))
                put("ramTotal", memInfo.totalMem / (1024 * 1024))
                put("isLowMemory", memInfo.lowMemory)

                // CPU usage (0-100%)
                put("cpuUsage", systemMetricsProvider.getCpuPercent())
                put("cpuCores", Runtime.getRuntime().availableProcessors())

                // Thermal state
                put("thermalState", getThermalState())
                put("thermalLevel", getThermalLevel())

                // Battery
                put("batteryPercent", systemMetricsProvider.getBatteryLevel())
                put("isCharging", systemMetricsProvider.isCharging())

                // Platform info
                put("isTablet", isTablet())
                put("platform", "android")
                put("androidVersion", Build.VERSION.SDK_INT)
                put("deviceModel", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)

                // Video codec support (for playback)
                put("videoCodecs", getVideoCodecSupport())

                // Hardware decoder info
                put("hardwareDecoderCount", getHardwareDecoderCount())
                put("hardwareDecoders", getHardwareDecoderDetails())
            }

            Log.d(TAG, "System metrics requested: RAM=${memInfo.availMem / (1024 * 1024)}MB, Thermal=${getThermalState()}")
            metrics.toString()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get system metrics: ${e.message}", e)
            JSONObject().apply {
                put("error", e.message ?: "Unknown error")
                put("platform", "android")
            }.toString()
        }
    }

    /**
     * Get supported video codecs for playback (decoders).
     *
     * Called from JavaScript:
     * ```javascript
     * const codecs = JSON.parse(window.dashieDevice.getVideoCodecSupport());
     * // Returns: ["h264", "h265", "vp8", "vp9"]
     * ```
     *
     * @return JSON array of supported codec names
     */
    @JavascriptInterface
    fun getVideoCodecSupport(): String {
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val supported = mutableListOf<String>()

            // Check each video codec decoder support
            if (hasDecoder(codecList, MIME_H264)) supported.add("h264")
            if (hasDecoder(codecList, MIME_H265)) supported.add("h265")
            if (hasDecoder(codecList, MIME_VP8)) supported.add("vp8")
            if (hasDecoder(codecList, MIME_VP9)) supported.add("vp9")
            if (hasDecoder(codecList, MIME_AV1)) supported.add("av1")

            Log.d(TAG, "Video codec support: ${supported.joinToString(", ")}")
            JSONArray(supported).toString()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get codec support: ${e.message}", e)
            // H.264 is universally supported, return as fallback
            JSONArray(listOf("h264")).toString()
        }
    }

    /**
     * Get the number of hardware video decoders available.
     *
     * Useful for determining how many concurrent video streams can be
     * hardware-decoded without falling back to CPU decoding.
     *
     * @return Number of hardware decoder instances
     */
    @JavascriptInterface
    fun getHardwareDecoderCount(): Int {
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            codecList.codecInfos.count { codecInfo ->
                !codecInfo.isEncoder && isHardwareAccelerated(codecInfo)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get hardware decoder count: ${e.message}", e)
            1 // Assume at least one
        }
    }

    /**
     * Get detailed hardware decoder information.
     *
     * @return JSON array with decoder details
     */
    @JavascriptInterface
    fun getHardwareDecoderDetails(): String {
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val decoders = JSONArray()

            for (codecInfo in codecList.codecInfos) {
                if (!codecInfo.isEncoder && isHardwareAccelerated(codecInfo)) {
                    for (type in codecInfo.supportedTypes) {
                        if (type.startsWith("video/")) {
                            decoders.put(JSONObject().apply {
                                put("name", codecInfo.name)
                                put("mimeType", type)
                                put("codecName", mimeToCodecName(type))
                                put("isHardware", true)
                            })
                        }
                    }
                }
            }

            decoders.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get decoder details: ${e.message}", e)
            JSONArray().toString()
        }
    }

    /**
     * Check if a specific codec is supported for decoding.
     *
     * @param codecName Codec name: "h264", "h265", "vp8", "vp9", "av1"
     * @return true if codec is supported
     */
    @JavascriptInterface
    fun isCodecSupported(codecName: String): Boolean {
        val mimeType = when (codecName.lowercase()) {
            "h264", "avc" -> MIME_H264
            "h265", "hevc" -> MIME_H265
            "vp8" -> MIME_VP8
            "vp9" -> MIME_VP9
            "av1" -> MIME_AV1
            else -> return false
        }

        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            hasDecoder(codecList, mimeType)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get thermal state as a string suitable for quality selection.
     *
     * @return "normal", "warning", or "critical"
     */
    private fun getThermalState(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                when (pm.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE,
                    PowerManager.THERMAL_STATUS_LIGHT -> "normal"
                    PowerManager.THERMAL_STATUS_MODERATE -> "warning"
                    PowerManager.THERMAL_STATUS_SEVERE,
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY,
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "critical"
                    else -> "normal"
                }
            } catch (e: Exception) {
                "normal"
            }
        }
        return "normal" // Pre-Android Q, assume normal
    }

    /**
     * Get thermal level as numeric value (0-6).
     */
    private fun getThermalLevel(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.currentThermalStatus
            } catch (e: Exception) {
                0
            }
        }
        return 0
    }

    /**
     * Check if this device is a tablet (vs phone).
     */
    private fun isTablet(): Boolean {
        val configuration = context.resources.configuration
        val screenLayoutSize = configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK
        return screenLayoutSize >= Configuration.SCREENLAYOUT_SIZE_LARGE
    }

    /**
     * Check if a decoder exists for the given MIME type.
     */
    private fun hasDecoder(codecList: MediaCodecList, mimeType: String): Boolean {
        return codecList.codecInfos.any { codecInfo ->
            !codecInfo.isEncoder && codecInfo.supportedTypes.contains(mimeType)
        }
    }

    /**
     * Check if a codec is hardware accelerated.
     */
    private fun isHardwareAccelerated(codecInfo: MediaCodecInfo): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                codecInfo.isHardwareAccelerated
            } else {
                // Fallback for older Android versions
                val name = codecInfo.name.lowercase()
                !name.contains("sw") && !name.contains("google") &&
                    (name.contains("omx") || name.contains("c2.") ||
                     name.contains("qcom") || name.contains("exynos") ||
                     name.contains("mtk") || name.contains("hisi"))
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Convert MIME type to friendly codec name.
     */
    private fun mimeToCodecName(mimeType: String): String {
        return when (mimeType) {
            MIME_H264 -> "h264"
            MIME_H265 -> "h265"
            MIME_VP8 -> "vp8"
            MIME_VP9 -> "vp9"
            MIME_AV1 -> "av1"
            else -> mimeType.substringAfterLast("/")
        }
    }

    // ==================== Native RTSP Player Control ====================
    // JavaScript bridge for controlling native RTSP video overlays.
    // Used by Dashie Camera Card to play RTSP streams with hardware decoding.

    /**
     * RTSP Player Manager reference. Set by MainActivity after initialization.
     */
    var rtspPlayerManager: com.dashieapp.Dashie.halite.rtsp.RtspPlayerManager? = null

    /**
     * Check if native RTSP playback is available.
     * Returns true when running in Dashie Kiosk WebView with RTSP support.
     *
     * Called from JavaScript:
     * ```javascript
     * if (window.dashieDevice?.isNativeRtspSupported()) {
     *   // Use native RTSP player
     * } else {
     *   // Fall back to WebRTC
     * }
     * ```
     */
    @JavascriptInterface
    fun isNativeRtspSupported(): Boolean {
        return rtspPlayerManager?.isNativeRtspSupported() == true
    }

    /**
     * Start a native RTSP stream at the specified screen position.
     *
     * Called from JavaScript:
     * ```javascript
     * window.dashieDevice.startRtspStream(
     *   'camera1',
     *   'rtsp://192.168.1.50:8555/pool',
     *   100, 200, 640, 360
     * );
     * ```
     *
     * @param id Unique identifier for this stream (used to update/stop it)
     * @param rtspUrl The RTSP URL to play
     * @param x Left position in pixels (WebView coordinate space)
     * @param y Top position in pixels (WebView coordinate space)
     * @param width Width in pixels
     * @param height Height in pixels
     */
    @JavascriptInterface
    fun startRtspStream(id: String, rtspUrl: String, x: Int, y: Int, width: Int, height: Int) {
        Log.i(TAG, "JS startRtspStream: id=$id, url=$rtspUrl, pos=($x,$y), size=${width}x$height")
        rtspPlayerManager?.startStream(id, rtspUrl, x, y, width, height)
            ?: Log.w(TAG, "RTSP player manager not available")
    }

    /**
     * Update the position of an existing RTSP stream overlay.
     * Call this when the card moves due to scrolling or resize.
     *
     * @param id The stream ID to update
     * @param x New left position in pixels
     * @param y New top position in pixels
     * @param width New width in pixels
     * @param height New height in pixels
     */
    @JavascriptInterface
    fun updateRtspStreamPosition(id: String, x: Int, y: Int, width: Int, height: Int) {
        rtspPlayerManager?.updateStreamPosition(id, x, y, width, height)
    }

    /**
     * Stop a specific RTSP stream and remove its overlay.
     *
     * @param id The stream ID to stop
     */
    @JavascriptInterface
    fun stopRtspStream(id: String) {
        Log.i(TAG, "JS stopRtspStream: id=$id")
        rtspPlayerManager?.stopStream(id)
    }

    /**
     * Stop all active RTSP streams.
     * Call this when navigating away from the camera view.
     */
    @JavascriptInterface
    fun stopAllRtspStreams() {
        Log.i(TAG, "JS stopAllRtspStreams")
        rtspPlayerManager?.stopAllStreams()
    }

    /**
     * Hide all RTSP overlays temporarily.
     * Call this when showing a modal or sidebar over the camera view.
     */
    @JavascriptInterface
    fun hideRtspOverlays() {
        rtspPlayerManager?.hideAllOverlays()
    }

    /**
     * Show all RTSP overlays.
     * Call this when dismissing a modal or sidebar.
     */
    @JavascriptInterface
    fun showRtspOverlays() {
        rtspPlayerManager?.showAllOverlays()
    }

    /**
     * Check if a specific stream is currently playing.
     *
     * @param id The stream ID to check
     * @return true if the stream is playing
     */
    @JavascriptInterface
    fun isRtspStreamPlaying(id: String): Boolean {
        return rtspPlayerManager?.isStreamPlaying(id) == true
    }

    /**
     * Get the number of active RTSP streams.
     */
    @JavascriptInterface
    fun getActiveRtspStreamCount(): Int {
        return rtspPlayerManager?.getActiveStreamCount() ?: 0
    }

    /**
     * Sync power management config from JS to SharedPreferences.
     * Called by the JS settings page and power engine on start.
     * The native PowerManagementWatchdog reads this config for
     * screen-off battery management.
     */
    /**
     * Sync HA auth tokens from the kiosk shell JS to native SharedPreferences.
     * In kiosk mode, hassTokens live in the HA iframe's localStorage (different origin),
     * so HaTokenExtractor can't access them directly. The kiosk shell extracts them
     * via evalInHaIframe and pushes here.
     */
    @JavascriptInterface
    fun syncHaTokens(json: String) {
        try {
            val obj = JSONObject(json)
            val accessToken = obj.optString("access_token", "")
            val refreshToken = obj.optString("refresh_token", "")
            val expires = obj.optLong("expires", 0L)

            if (accessToken.isEmpty()) {
                Log.w(TAG, "🔐 syncHaTokens: empty access token, skipping")
                return
            }

            val prefs = context.getSharedPreferences("dashie_lite_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("ha_access_token", accessToken)
                .putString("ha_refresh_token", refreshToken)
                .putLong("ha_token_expiry", if (expires > 0) expires else System.currentTimeMillis() + 1800_000)
                .apply()

            Log.i(TAG, "🔐 HA tokens synced to SharedPreferences (expires=${expires})")
        } catch (e: Exception) {
            Log.w(TAG, "🔐 Failed to sync HA tokens", e)
        }
    }

    @JavascriptInterface
    fun syncPowerConfig(json: String) {
        try {
            val prefs = PowerPreferences(context)
            val incoming = org.json.JSONObject(json)
            // Guard: don't let JS overwrite native-enabled config with defaults.
            // If battery charging is enabled natively and JS sends enabled=false,
            // skip the import — native settings are authoritative.
            if (prefs.enabled && !incoming.optBoolean("enabled", false)) {
                Log.d("DeviceMetrics", "⚡ Power config sync skipped (native enabled, JS disabled)")
                return
            }
            prefs.importFromJson(json)
            Log.d("DeviceMetrics", "⚡ Power config synced to SharedPreferences")
        } catch (e: Exception) {
            Log.w("DeviceMetrics", "⚡ Failed to sync power config", e)
        }
    }

    /** Return the native power config JSON so JS can use Android SharedPreferences as source of truth. */
    @JavascriptInterface
    fun getPowerConfig(): String {
        return try {
            PowerPreferences(context).exportToJson()
        } catch (_: Exception) { "{}" }
    }

    /** Return the native power watchdog log as a JSON array string. */
    @JavascriptInterface
    fun getPowerLog(): String {
        return try {
            context.getSharedPreferences("dashie_power_watchdog_log", Context.MODE_PRIVATE)
                .getString("log", "[]") ?: "[]"
        } catch (_: Exception) { "[]" }
    }

    /** Return the native battery history as a JSON array string. */
    @JavascriptInterface
    fun getBatteryHistory(): String {
        return try {
            context.getSharedPreferences("dashie_power_watchdog_history", Context.MODE_PRIVATE)
                .getString("history", "[]") ?: "[]"
        } catch (_: Exception) { "[]" }
    }
}

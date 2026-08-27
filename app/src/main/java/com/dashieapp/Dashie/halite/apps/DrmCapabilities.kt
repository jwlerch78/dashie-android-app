package com.dashieapp.Dashie.halite.apps

import android.media.MediaDrm
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.util.UUID

/**
 * Reads the device's Widevine DRM security level via [MediaDrm].
 *
 * L1 = hardware-backed (keys + decode in the TEE); required for Netflix/Prime
 *      HD+HDR and the protected secure-video path.
 * L3 = software-only; caps premium DRM to SD and, on cheap SoCs, often renders
 *      protected playback as a flickering/black screen (UI works, video doesn't).
 *
 * "Will Netflix HD work on this device?" ≈ "is this L1?". Budget tablets
 * (Rockchip / Unisoc / low-end MediaTek) almost always ship L3.
 */
object DrmCapabilities {
    private const val TAG = "DrmCapabilities"
    private val WIDEVINE_UUID: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

    /** "L1", "L3", or "unknown" (Widevine absent or query failed). */
    fun getWidevineSecurityLevel(): String {
        var drm: MediaDrm? = null
        return try {
            drm = MediaDrm(WIDEVINE_UUID)
            drm.getPropertyString("securityLevel")
        } catch (e: Exception) {
            Log.w(TAG, "Widevine query failed: ${e.message}")
            "unknown"
        } finally {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) drm?.close()
                else @Suppress("DEPRECATION") drm?.release()
            } catch (_: Exception) { }
        }
    }

    /** True when the device has a hardware-backed secure video path (Widevine L1). */
    fun supportsHardwareDrm(): Boolean = getWidevineSecurityLevel() == "L1"

    /** JSON for the JS bridge: {"widevine":"L3","hardwareDrm":false,"netflixHd":false}. */
    fun toJson(): String {
        val level = getWidevineSecurityLevel()
        val hw = level == "L1"
        return JSONObject().apply {
            put("widevine", level)
            put("hardwareDrm", hw)
            put("netflixHd", hw)
        }.toString()
    }

    /** One-line log line for field diagnostics. */
    fun logCapabilities() {
        val level = getWidevineSecurityLevel()
        Log.i(TAG, "Widevine=$level, hardwareDRM=${level == "L1"}, NetflixHD=${level == "L1"}")
    }
}

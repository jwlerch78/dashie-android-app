package com.dashieapp.Dashie.halite.videofeed

import org.json.JSONObject

/**
 * Data class representing a single video feed PiP overlay.
 * Populated from a configured rule when a trigger fires.
 */
data class VideoFeedData(
    val ruleId: String = "",
    val cameraEntityId: String = "",
    val cameraName: String = "",
    val triggerEntityId: String = "",
    val autoDismissSeconds: Int = 30,
    val continueWhileActive: Boolean = false,
    val streamSourceType: String = "entity",  // entity, rtsp, go2rtc
    val streamSourceUrl: String = "",
    val playSoundOnTrigger: Boolean = false,
    val triggerSound: String = "extra_wood_knock",
    val isMinimized: Boolean = false,
    val isMaximized: Boolean = false,
    val isPinned: Boolean = false,
    val isFloating: Boolean = false,
    val isAvailable: Boolean = true,
    val fps: Int = 10,
    val quality: Int = 8,
    val resolution: Int = 480,
    val rtspUrl: String = "",  // Direct RTSP URL for ExoPlayer (bypasses MJPEG proxy)
    val isFrigateCamera: Boolean = false,
    val frigateCameraName: String = ""
) {
    /** Label for the PiP overlay — adapts based on source type */
    val displayLabel: String
        get() = when {
            cameraName.isNotBlank() -> cameraName
            streamSourceType == "go2rtc" && streamSourceUrl.isNotBlank() -> streamSourceUrl
            streamSourceType == "rtsp" -> "RTSP Stream"
            cameraEntityId.isNotBlank() -> cameraEntityId
            else -> "Video Feed"
        }

    companion object {
        fun fromJson(json: JSONObject): VideoFeedData {
            return VideoFeedData(
                ruleId = json.optString("ruleId", json.optString("id", "")),
                cameraEntityId = json.optString("cameraEntityId", ""),
                cameraName = json.optString("cameraName", json.optString("name", "")),
                triggerEntityId = json.optString("triggerEntityId", ""),
                autoDismissSeconds = json.optInt("autoDismissSeconds", 30),
                continueWhileActive = json.optBoolean("continueWhileActive", false),
                streamSourceType = json.optString("streamSourceType", "entity"),
                streamSourceUrl = json.optString("streamSourceUrl", ""),
                playSoundOnTrigger = json.optBoolean("playSoundOnTrigger", false),
                triggerSound = json.optString("triggerSound", "extra_wood_knock"),
                isMinimized = json.optBoolean("isMinimized", false),
                isMaximized = json.optBoolean("isMaximized", false),
                isAvailable = json.optBoolean("available", true),
                fps = json.optInt("fps", 10),
                quality = json.optInt("quality", 8),
                resolution = json.optInt("resolution", 480),
                rtspUrl = json.optString("rtspUrl", ""),
                isFrigateCamera = json.optBoolean("is_frigate_camera", json.optBoolean("isFrigateCamera", false)),
                frigateCameraName = json.optString("frigate_camera_name", json.optString("frigateCameraName", ""))
            )
        }
    }
}

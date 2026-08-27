package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * Camera and RTSP streaming preferences for Dashie Kiosk.
 *
 * Manages:
 * - Camera motion detection enabled state
 * - Camera permission tracking
 * - RTSP server configuration (port, resolution, FPS, bitrate)
 * - RTSP authentication settings
 * - RTSP motion detection mode
 * - Software encoding fallback
 *
 * Uses the same SharedPreferences file as HalitePreferences for backward compatibility.
 */
class CameraPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_lite_prefs"

        // Camera motion detection
        private const val KEY_CAMERA_MOTION_ENABLED = "camera_motion_enabled"
        private const val KEY_CAMERA_PERMISSION_REQUESTED = "camera_permission_requested"

        // RTSP Camera Streaming
        private const val KEY_RTSP_ENABLED = "rtsp_enabled"
        private const val KEY_RTSP_PORT = "rtsp_port"
        private const val KEY_RTSP_RESOLUTION = "rtsp_resolution"
        private const val KEY_RTSP_FPS = "rtsp_fps"
        private const val KEY_RTSP_BITRATE = "rtsp_bitrate"
        private const val KEY_RTSP_AUTH_ENABLED = "rtsp_auth_enabled"
        private const val KEY_RTSP_AUTH_USER = "rtsp_auth_user"
        private const val KEY_RTSP_AUTH_PASSWORD = "rtsp_auth_password"
        private const val KEY_RTSP_MOTION_DETECTION = "rtsp_motion_detection"
        private const val KEY_STOP_STREAM_WHEN_NO_CLIENTS = "stop_stream_when_no_clients"
        private const val KEY_RTSP_CUSTOM_WIDTH = "rtsp_custom_width"
        private const val KEY_RTSP_CUSTOM_HEIGHT = "rtsp_custom_height"
        private const val KEY_RTSP_FORCE_SOFTWARE_ENCODING = "rtsp_force_software_encoding"
        private const val KEY_RTSP_DISABLE_MIRROR_CORRECTION = "rtsp_disable_mirror_correction"
        private const val KEY_RTSP_ROTATE_STREAM_180 = "rtsp_rotate_stream_180"
        private const val KEY_RTSP_PORTRAIT_STREAM = "rtsp_portrait_stream"

        // HA Sensor Publishing
        private const val KEY_HA_SENSOR_ENABLED = "ha_sensor_enabled"
        private const val KEY_HA_SENSOR_MOTION_ENABLED = "ha_sensor_motion_enabled"
        private const val KEY_HA_SENSOR_FACE_ENABLED = "ha_sensor_face_enabled"

        // RTSP Resolution presets
        const val RTSP_RESOLUTION_480P = "480p"
        const val RTSP_RESOLUTION_720P = "720p"
        const val RTSP_RESOLUTION_1080P = "1080p"
        const val RTSP_RESOLUTION_CUSTOM = "custom"

        // RTSP default values
        const val DEFAULT_RTSP_PORT = 8554
        const val DEFAULT_RTSP_FPS = 15
        const val DEFAULT_RTSP_BITRATE = 2_000_000  // 2 Mbps
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ========== Camera Motion Detection ==========

    /**
     * Camera-based motion detection enabled (for waking screen when someone approaches)
     * Uses front camera to detect movement - more reliable than sensors
     * Default: false (user must opt-in, as camera can interfere with microphone on some devices)
     */
    var cameraMotionEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_MOTION_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CAMERA_MOTION_ENABLED, value).commit()
            // Reset the permission-requested flag when camera motion is disabled
            // This allows the permission dialog to show again if user re-enables
            if (!value) {
                cameraPermissionRequested = false
            }
        }

    /**
     * Track if camera permission dialog has been shown this session.
     * Prevents duplicate permission popups during activity recreations.
     * Automatically reset when cameraMotionEnabled is disabled.
     */
    var cameraPermissionRequested: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_PERMISSION_REQUESTED, false)
        set(value) { prefs.edit().putBoolean(KEY_CAMERA_PERMISSION_REQUESTED, value).commit() }

    // ========== RTSP Camera Streaming ==========

    /**
     * RTSP streaming enabled
     * When enabled, starts RTSP server on configured port for camera streaming.
     * Default: false (user must opt-in)
     */
    var rtspEnabled: Boolean
        get() = prefs.getBoolean(KEY_RTSP_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_RTSP_ENABLED, value).commit() }

    /**
     * RTSP server port
     * Default: 8554 (standard RTSP port)
     */
    var rtspPort: Int
        get() = prefs.getInt(KEY_RTSP_PORT, DEFAULT_RTSP_PORT)
        set(value) { prefs.edit().putInt(KEY_RTSP_PORT, value.coerceIn(1024, 65535)).commit() }

    /**
     * RTSP stream resolution: "480p", "720p", "1080p"
     * Default: "720p"
     */
    var rtspResolution: String
        get() = prefs.getString(KEY_RTSP_RESOLUTION, RTSP_RESOLUTION_720P) ?: RTSP_RESOLUTION_720P
        set(value) { prefs.edit().putString(KEY_RTSP_RESOLUTION, value).commit() }

    /**
     * Get resolution dimensions based on resolution setting.
     * Uses standard camera resolutions to ensure compatibility:
     * - VGA: 640x480 (4:3) - universally supported, low CPU
     * - 720p: 1280x720 (HD) - standard HD resolution
     * - 1080p: 1920x1080 (Full HD) - standard FHD resolution
     * - Custom: User-specified dimensions for oddball cameras
     */
    val rtspResolutionWidth: Int
        get() = when (rtspResolution) {
            RTSP_RESOLUTION_480P -> 640   // VGA - universally supported
            RTSP_RESOLUTION_720P -> 1280
            RTSP_RESOLUTION_1080P -> 1920
            RTSP_RESOLUTION_CUSTOM -> rtspCustomWidth
            else -> 1280
        }

    val rtspResolutionHeight: Int
        get() = when (rtspResolution) {
            RTSP_RESOLUTION_480P -> 480   // VGA - universally supported
            RTSP_RESOLUTION_720P -> 720
            RTSP_RESOLUTION_1080P -> 1080
            RTSP_RESOLUTION_CUSTOM -> rtspCustomHeight
            else -> 720
        }

    /**
     * Custom resolution width (used when rtspResolution == "custom")
     * Default: 1280 (720p width)
     */
    var rtspCustomWidth: Int
        get() = prefs.getInt(KEY_RTSP_CUSTOM_WIDTH, 1280)
        set(value) { prefs.edit().putInt(KEY_RTSP_CUSTOM_WIDTH, value.coerceIn(320, 3840)).commit() }

    /**
     * Custom resolution height (used when rtspResolution == "custom")
     * Default: 720 (720p height)
     */
    var rtspCustomHeight: Int
        get() = prefs.getInt(KEY_RTSP_CUSTOM_HEIGHT, 720)
        set(value) { prefs.edit().putInt(KEY_RTSP_CUSTOM_HEIGHT, value.coerceIn(240, 2160)).commit() }

    /**
     * Get a display string for the current resolution setting
     */
    val rtspResolutionDisplay: String
        get() = when (rtspResolution) {
            RTSP_RESOLUTION_480P -> "480p (640x480)"
            RTSP_RESOLUTION_720P -> "720p (1280x720)"
            RTSP_RESOLUTION_1080P -> "1080p (1920x1080)"
            RTSP_RESOLUTION_CUSTOM -> "Custom (${rtspCustomWidth}x${rtspCustomHeight})"
            else -> rtspResolution.uppercase()
        }

    /**
     * RTSP stream frame rate (FPS)
     * Options: 10, 15, 24, 30
     * Default: 15
     */
    var rtspFps: Int
        get() = prefs.getInt(KEY_RTSP_FPS, DEFAULT_RTSP_FPS)
        set(value) { prefs.edit().putInt(KEY_RTSP_FPS, value.coerceIn(10, 30)).commit() }

    /**
     * RTSP stream bitrate in bits per second
     * Options: 1Mbps, 2Mbps, 4Mbps, 8Mbps
     * Default: 2Mbps
     */
    var rtspBitrate: Int
        get() = prefs.getInt(KEY_RTSP_BITRATE, DEFAULT_RTSP_BITRATE)
        set(value) { prefs.edit().putInt(KEY_RTSP_BITRATE, value.coerceIn(500_000, 10_000_000)).commit() }

    // ========== RTSP Authentication ==========

    /**
     * RTSP authentication enabled
     * When enabled, requires username/password to connect
     * Default: false
     */
    var rtspAuthEnabled: Boolean
        get() = prefs.getBoolean(KEY_RTSP_AUTH_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_RTSP_AUTH_ENABLED, value).commit() }

    /**
     * RTSP authentication username
     */
    var rtspAuthUser: String
        get() = prefs.getString(KEY_RTSP_AUTH_USER, "") ?: ""
        set(value) { prefs.edit().putString(KEY_RTSP_AUTH_USER, value).commit() }

    /**
     * RTSP authentication password
     */
    var rtspAuthPassword: String
        get() = prefs.getString(KEY_RTSP_AUTH_PASSWORD, "") ?: ""
        set(value) { prefs.edit().putString(KEY_RTSP_AUTH_PASSWORD, value).commit() }

    /**
     * Check if RTSP authentication credentials are configured
     */
    val hasRtspCredentials: Boolean
        get() = rtspAuthUser.isNotEmpty() && rtspAuthPassword.isNotEmpty()

    // ========== RTSP Motion Detection & Optimization ==========

    /**
     * Use RTSP stream for motion detection
     * When enabled, motion detection is done from RTSP frames instead of separate camera capture.
     * This is more efficient when streaming is active.
     * Default: true (unified motion detection)
     */
    var rtspMotionDetection: Boolean
        get() = prefs.getBoolean(KEY_RTSP_MOTION_DETECTION, true)
        set(value) { prefs.edit().putBoolean(KEY_RTSP_MOTION_DETECTION, value).commit() }

    /**
     * Stop streaming when no clients connected
     * Saves CPU/power when nobody is watching.
     * Default: false (keep streaming for instant reconnect)
     */
    var stopStreamWhenNoClients: Boolean
        get() = prefs.getBoolean(KEY_STOP_STREAM_WHEN_NO_CLIENTS, false)
        set(value) { prefs.edit().putBoolean(KEY_STOP_STREAM_WHEN_NO_CLIENTS, value).commit() }

    /**
     * Force software encoding for RTSP streaming.
     * Enable this on devices with buggy hardware encoders (e.g., some Samsung tablets)
     * that produce scrambled/corrupted video frames.
     * Uses more CPU but produces reliable output.
     * Default: false (use hardware encoding)
     */
    var rtspForceSoftwareEncoding: Boolean
        get() = prefs.getBoolean(KEY_RTSP_FORCE_SOFTWARE_ENCODING, false)
        set(value) { prefs.edit().putBoolean(KEY_RTSP_FORCE_SOFTWARE_ENCODING, value).commit() }

    /**
     * Disable automatic mirror correction for RTSP streaming.
     * Normally, front cameras apply a selfie-mode mirror effect which we correct by
     * flipping the image horizontally. Some devices (like ONN streaming sticks) report
     * cameras as front-facing but don't apply the mirror effect, causing the stream
     * to appear mirrored when the correction is applied.
     * Enable this setting to disable the automatic flip correction.
     * Default: false (apply mirror correction for front cameras)
     */
    var rtspDisableMirrorCorrection: Boolean
        get() = prefs.getBoolean(KEY_RTSP_DISABLE_MIRROR_CORRECTION, false)
        set(value) { prefs.edit().putBoolean(KEY_RTSP_DISABLE_MIRROR_CORRECTION, value).commit() }

    /**
     * Rotate the RTSP stream 180° via GPU pixel rotation.
     * Enable this if the stream appears upside-down in HA, Fully Kiosk, or other
     * viewers that ignore H.264 rotation metadata. This physically rotates pixels
     * before encoding, so all viewers see the stream upright.
     * Default: false (no rotation)
     */
    var rtspRotateStream180: Boolean
        get() = prefs.getBoolean(KEY_RTSP_ROTATE_STREAM_180, false)
        set(value) { prefs.edit().putBoolean(KEY_RTSP_ROTATE_STREAM_180, value).commit() }

    /**
     * Stream in portrait orientation (swap width/height).
     * Enable this when the device is mounted in portrait and the stream appears
     * squished into landscape. Swaps the encoder dimensions (e.g., 1280x720 becomes
     * 720x1280) so the stream matches the device's actual orientation.
     * Default: false (landscape)
     */
    var rtspPortraitStream: Boolean
        get() = prefs.getBoolean(KEY_RTSP_PORTRAIT_STREAM, false)
        set(value) { prefs.edit().putBoolean(KEY_RTSP_PORTRAIT_STREAM, value).commit() }

    // ========== HA Sensor Publishing ==========

    /** Publish detection data to HA as binary_sensor entities */
    var haSensorEnabled: Boolean
        get() = prefs.getBoolean(KEY_HA_SENSOR_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_HA_SENSOR_ENABLED, value).commit() }

    /** Publish motion detection as binary_sensor.dashie_{device}_motion */
    var haSensorMotionEnabled: Boolean
        get() = prefs.getBoolean(KEY_HA_SENSOR_MOTION_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_HA_SENSOR_MOTION_ENABLED, value).commit() }

    /** Publish face detection as binary_sensor.dashie_{device}_face */
    var haSensorFaceEnabled: Boolean
        get() = prefs.getBoolean(KEY_HA_SENSOR_FACE_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_HA_SENSOR_FACE_ENABLED, value).commit() }
}

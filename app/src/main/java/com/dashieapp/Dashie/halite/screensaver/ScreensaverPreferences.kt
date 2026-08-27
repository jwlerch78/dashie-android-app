package com.dashieapp.Dashie.halite.screensaver

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferences manager for screensaver and photo slideshow settings.
 * Extracted from HalitePreferences to support the upcoming photo sources feature.
 *
 * Handles:
 * - Screensaver timeout and mode (dim/black/photos)
 * - Motion wake settings (brightness sensor, camera)
 * - Photo source configuration (local folder, Immich, Google Photos)
 */
class ScreensaverPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_lite_prefs"

        // Screensaver settings
        private const val KEY_SCREENSAVER_TIMEOUT = "screensaver_timeout"
        private const val KEY_SCREENSAVER_MODE = "screensaver_mode"

        // Motion wake settings
        private const val KEY_MOTION_WAKE_MODE = "motion_wake_mode"
        private const val KEY_MOTION_WAKE_ENABLED = "motion_wake_enabled"  // Legacy key for migration
        private const val KEY_CAMERA_MOTION_ENABLED = "camera_motion_enabled"  // Legacy key for migration
        private const val KEY_CAMERA_WAKE_THRESHOLD = "camera_wake_threshold"
        private const val KEY_FACE_WAKE_DISTANCE = "face_wake_distance"

        // Photo source settings (for upcoming photo slideshow feature)
        private const val KEY_PHOTO_SOURCE_TYPE = "photo_source_type"
        private const val KEY_LOCAL_PHOTO_FOLDER = "local_photo_folder"
        private const val KEY_LOCAL_PHOTO_FOLDER_URI = "local_photo_folder_uri"
        private const val KEY_IMMICH_SERVER_URL = "immich_server_url"
        private const val KEY_IMMICH_ACCESS_TOKEN = "immich_access_token"
        private const val KEY_IMMICH_SELECTED_ALBUMS = "immich_selected_albums"
        private const val KEY_GOOGLE_PHOTOS_ALBUM_ID = "google_photos_album_id"
        private const val KEY_GOOGLE_PHOTOS_REFRESH_TOKEN = "google_photos_refresh_token"

        // Slideshow settings
        private const val KEY_SLIDESHOW_INTERVAL = "slideshow_interval"
        private const val KEY_SLIDESHOW_TRANSITION = "slideshow_transition"
        private const val KEY_SLIDESHOW_SHUFFLE = "slideshow_shuffle"
        private const val KEY_PHOTO_FIT = "photo_fit"
        private const val KEY_SHOW_METADATA = "show_metadata"
        private const val KEY_SHOW_CLOCK = "show_clock"
        private const val KEY_USE_24_HOUR_CLOCK = "use_24_hour_clock"
        private const val KEY_SHOW_PREVIEW_ON_WAKE = "show_preview_on_wake"
        private const val KEY_CLOCK_POSITION = "clock_position"
        private const val KEY_CLOCK_SIZE = "clock_size"
        private const val KEY_CLOCK_FONT_SIZE = "clock_font_size"
        private const val KEY_SHOW_DATE = "show_date"

        // Default values
        const val DEFAULT_SCREENSAVER_TIMEOUT = 0  // 0 = disabled. Avoids dimming during first-run/setup.
        const val DEFAULT_SCREENSAVER_MODE = "dim"  // "dim", "black", or "photos"
        const val DEFAULT_SLIDESHOW_INTERVAL = 15   // seconds between photos
        const val DEFAULT_SLIDESHOW_TRANSITION = "fade"  // "fade", "slide", "none"
        const val DEFAULT_PHOTO_FIT = "fit"  // "fit" (letterbox, whole photo) or "fill" (crop to fill)

        // Motion wake mode options
        const val MOTION_WAKE_DISABLED = "disabled"
        const val MOTION_WAKE_BRIGHTNESS = "brightness"
        const val MOTION_WAKE_CAMERA = "camera"
        const val MOTION_WAKE_FACE = "face"

        // Face wake distance options (min face size as % of frame width)
        const val FACE_DISTANCE_FAR = "far"             // ~8% - across the room (3m+)
        const val FACE_DISTANCE_NEAR = "near"            // ~15% - within ~1.5m
        const val FACE_DISTANCE_CLOSE = "close"          // ~21% - within ~1m
        const val FACE_DISTANCE_VERY_CLOSE = "very_close" // ~35% - right in front (<0.5m)

        /**
         * Convert face distance label to minimum face size percentage (0.0 - 1.0).
         */
        fun faceDistanceToPercent(distance: String): Float {
            return when (distance) {
                FACE_DISTANCE_FAR -> 0.08f
                FACE_DISTANCE_NEAR -> 0.15f
                FACE_DISTANCE_CLOSE -> 0.23f
                FACE_DISTANCE_VERY_CLOSE -> 0.35f
                else -> 0.15f
            }
        }

        /**
         * Get display label for face distance setting.
         */
        fun faceDistanceLabel(distance: String): String {
            return when (distance) {
                FACE_DISTANCE_FAR -> "Far"
                FACE_DISTANCE_NEAR -> "Near"
                FACE_DISTANCE_CLOSE -> "Close"
                FACE_DISTANCE_VERY_CLOSE -> "V. Close"
                else -> "Near"
            }
        }

        // Photo source type options
        const val PHOTO_SOURCE_NONE = "none"
        const val PHOTO_SOURCE_LOCAL = "local"
        const val PHOTO_SOURCE_IMMICH = "immich"
        const val PHOTO_SOURCE_GOOGLE = "google"
        const val PHOTO_SOURCE_HA_MEDIA = "ha_media"  // Home Assistant media folder
        const val PHOTO_SOURCE_UNSPLASH = "unsplash"  // Unsplash random photos
        const val PHOTO_SOURCE_GOOGLE_DRIVE = "google_drive"  // Google Drive folder via edge function
        const val PHOTO_SOURCE_SUPABASE = "supabase"  // Dashie Cloud (Supabase Storage)

        // HA Media settings
        private const val KEY_HA_MEDIA_FOLDER = "ha_media_folder"

        // Unsplash settings
        private const val KEY_UNSPLASH_QUERY = "unsplash_query"
        private const val KEY_UNSPLASH_ARTIST_HYPERLINKS = "unsplash_artist_hyperlinks"

        // Screensaver mode options
        const val SCREENSAVER_MODE_DIM = "dim"
        const val SCREENSAVER_MODE_BLACK = "black"
        const val SCREENSAVER_MODE_OFF = "off"  // Hardware screen off (requires Device Admin)
        const val SCREENSAVER_MODE_URL = "url"
        const val SCREENSAVER_MODE_PHOTOS = "photos"
        const val SCREENSAVER_MODE_APP = "app"  // Launch external app
        const val SCREENSAVER_MODE_HA_PAGE = "ha_page"  // Navigate to HA page

        // Launch App settings
        private const val KEY_LAUNCH_APP_PACKAGE = "launch_app_package"
        private const val KEY_LAUNCH_APP_LABEL = "launch_app_label"

        // HA Page screensaver settings
        private const val KEY_HA_PAGE_PATH = "screensaver_ha_page_path"

        // URL screensaver settings
        private const val KEY_SCREENSAVER_URL = "screensaver_url"

        /**
         * The URL screensaver's default page — a BRAND fact, read from resources.
         *
         * 🔴 Deliberately a function and not a `const val`. It used to be a compile-time constant
         * in `main/`, which **no source set can override**, so the Chickadee build pointed its
         * screensaver at a Dashie-hosted page — an "independent" brand depending on Dashie
         * infrastructure for a screen a household stares at all day. Resources override per
         * flavor; constants do not, and that is the whole reason for the shape.
         *
         * @see R.string.default_screensaver_url for the two values and why Chickadee's carries
         *      `?weather=1`.
         */
        @JvmStatic
        fun defaultScreensaverUrl(context: Context): String =
            context.getString(com.dashieapp.Dashie.R.string.default_screensaver_url)

        // Weather + clock overlay
        private const val KEY_WEATHER_OVERLAY_ENABLED = "weather_overlay_enabled"
        private const val KEY_WEATHER_MODE = "weather_mode"
        private const val KEY_WEATHER_ENTITY_ID = "weather_entity_id"
        private const val KEY_FORECAST_CARD_SIZE = "forecast_card_size"
        const val DEFAULT_WEATHER_ENTITY_ID = "weather.forecast_home"

        // Dim screensaver brightness
        private const val KEY_DIM_BRIGHTNESS = "dim_brightness"
        const val DEFAULT_DIM_BRIGHTNESS = 15  // 15% brightness (85% opacity)

        // Reduce hardware brightness during black overlay
        private const val KEY_REDUCE_BRIGHTNESS = "reduce_brightness_on_black"

        // Slideshow transition options
        const val TRANSITION_FADE = "fade"
        const val TRANSITION_SLIDE = "slide"
        const val TRANSITION_NONE = "none"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Held for the per-brand resource reads (see [defaultScreensaverUrl]).
     *
     * `applicationContext`, not the passed one: this object outlives the Activity that often
     * constructs it, and retaining an Activity here would be a leak for the sake of a string
     * lookup. Resources resolve identically from either.
     */
    private val appContext: Context = context.applicationContext

    // ========== Screensaver Settings ==========

    /**
     * Screensaver timeout in seconds (0 = disabled)
     */
    var screensaverTimeout: Int
        get() = prefs.getInt(KEY_SCREENSAVER_TIMEOUT, DEFAULT_SCREENSAVER_TIMEOUT)
        set(value) { prefs.edit().putInt(KEY_SCREENSAVER_TIMEOUT, value).commit() }

    /**
     * Screensaver mode: "dim", "black", "url", or "photos"
     * - dim: Screen dims to low brightness
     * - black: Screen goes completely black
     * - url: Display a webpage (default is PER-BRAND — see [defaultScreensaverUrl])
     * - photos: Display photo slideshow (requires photo source)
     */
    var screensaverMode: String
        get() = prefs.getString(KEY_SCREENSAVER_MODE, DEFAULT_SCREENSAVER_MODE) ?: DEFAULT_SCREENSAVER_MODE
        set(value) { prefs.edit().putString(KEY_SCREENSAVER_MODE, value).commit() }

    /**
     * URL to display when screensaver mode is "url".
     *
     * Default is per-brand — see [defaultScreensaverUrl]. Resolved on read rather than captured,
     * so the value tracks the running edition's resources.
     */
    var screensaverUrl: String
        get() = prefs.getString(KEY_SCREENSAVER_URL, null)
            ?: defaultScreensaverUrl(appContext)
        set(value) { prefs.edit().putString(KEY_SCREENSAVER_URL, value).commit() }

    /**
     * Brightness level for dim screensaver mode (1-75%)
     * Stored as brightness percentage (e.g., 15 = 15% screen brightness)
     * Converted to alpha opacity for overlay (brightness 15% = alpha 0.85)
     * Default: 15% brightness
     * Max: 75% (anything higher doesn't meaningfully "dim")
     */
    var dimBrightness: Int
        get() = prefs.getInt(KEY_DIM_BRIGHTNESS, DEFAULT_DIM_BRIGHTNESS).coerceIn(1, 75)
        set(value) { prefs.edit().putInt(KEY_DIM_BRIGHTNESS, value.coerceIn(1, 75)).commit() }

    /**
     * Reduce hardware display brightness to minimum during black overlay screensaver/sleep.
     * When enabled, sets window brightness to 0 on dim and restores on wake.
     * Default: true (saves power and reduces light bleed from backlight)
     */
    var reduceBrightnessOnBlack: Boolean
        get() = prefs.getBoolean(KEY_REDUCE_BRIGHTNESS, true)
        set(value) { prefs.edit().putBoolean(KEY_REDUCE_BRIGHTNESS, value).commit() }

    /**
     * Package name of the app to launch when screensaver mode is "app"
     * e.g., "com.immich.kiosk" for Immich Kiosk
     */
    var launchAppPackage: String
        get() = prefs.getString(KEY_LAUNCH_APP_PACKAGE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LAUNCH_APP_PACKAGE, value).commit() }

    /**
     * User-friendly label for the launch app (for display in settings)
     * e.g., "Immich Kiosk"
     */
    var launchAppLabel: String
        get() = prefs.getString(KEY_LAUNCH_APP_LABEL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LAUNCH_APP_LABEL, value).commit() }

    /**
     * Whether launch app is configured
     */
    val hasLaunchAppConfig: Boolean
        get() = launchAppPackage.isNotEmpty()

    /**
     * HA page path for ha_page screensaver mode.
     * The path after the HA base URL (e.g., "lovelace-kitchen/weather").
     * On screensaver activation, the WebView navigates to {baseUrl}/{haPagePath}.
     */
    var haPagePath: String
        get() = prefs.getString(KEY_HA_PAGE_PATH, "") ?: ""
        set(value) { prefs.edit().putString(KEY_HA_PAGE_PATH, value).commit() }

    // ========== Motion Wake Settings ==========

    /**
     * Motion wake mode: "disabled", "brightness", "camera", "face"
     * - disabled: Screen doesn't wake on motion (touch only)
     * - brightness: Uses brightness/light sensor to detect motion (legacy behavior)
     * - camera: Uses front camera to detect movement
     * - face: Uses camera motion + ML Kit face detection (two-stage)
     *
     * This replaces the old motionWakeEnabled and cameraMotionEnabled booleans.
     * For backwards compatibility, we migrate from old settings on first read.
     */
    var motionWakeMode: String
        get() {
            val storedMode = prefs.getString(KEY_MOTION_WAKE_MODE, null)
            if (storedMode != null) return storedMode

            // Migrate from old settings
            val oldMotionWake = prefs.getBoolean(KEY_MOTION_WAKE_ENABLED, false)
            val oldCameraMotion = prefs.getBoolean(KEY_CAMERA_MOTION_ENABLED, false)
            val migratedMode = when {
                oldCameraMotion -> MOTION_WAKE_CAMERA
                oldMotionWake -> MOTION_WAKE_BRIGHTNESS
                else -> MOTION_WAKE_DISABLED
            }
            // Store migrated value
            prefs.edit().putString(KEY_MOTION_WAKE_MODE, migratedMode).commit()
            return migratedMode
        }
        set(value) { prefs.edit().putString(KEY_MOTION_WAKE_MODE, value).commit() }

    /**
     * Camera wake threshold as integer for backwards compatibility (1-20%, default 5%)
     * For 0.5% precision, use cameraWakeThresholdTenths instead.
     * Lower values = more sensitive to motion
     */
    var cameraWakeThreshold: Int
        get() = cameraWakeThresholdTenths / 10
        set(value) { cameraWakeThresholdTenths = value * 10 }

    /**
     * Camera wake threshold in tenths of a percent (2-200, default 50 = 5.0%)
     * This allows 0.2% precision: 2 = 0.2%, 5 = 0.5%, 50 = 5.0%, etc.
     * Lower values = more sensitive to motion
     */
    var cameraWakeThresholdTenths: Int
        get() = prefs.getInt(KEY_CAMERA_WAKE_THRESHOLD, 50)  // Default 50 = 5.0%
        set(value) { prefs.edit().putInt(KEY_CAMERA_WAKE_THRESHOLD, value.coerceIn(2, 200)).commit() }

    /**
     * Camera wake threshold as a Double (0.2 - 20.0%, default 5.0%)
     * Convenience property for threshold with decimal support.
     */
    val cameraWakeThresholdDouble: Double
        get() = cameraWakeThresholdTenths / 10.0

    /**
     * Face wake distance: "far", "near", "close", "very_close"
     * Controls how close a face must be to wake the screen.
     * Default: "far" (any visible face wakes the screen)
     */
    var faceWakeDistance: String
        get() = prefs.getString(KEY_FACE_WAKE_DISTANCE, FACE_DISTANCE_NEAR) ?: FACE_DISTANCE_NEAR
        set(value) { prefs.edit().putString(KEY_FACE_WAKE_DISTANCE, value).commit() }

    /**
     * Motion wake enabled (legacy compatibility)
     * @deprecated Use motionWakeMode instead
     */
    @Deprecated("Use motionWakeMode instead", ReplaceWith("motionWakeMode"))
    var motionWakeEnabled: Boolean
        get() = motionWakeMode != MOTION_WAKE_DISABLED
        set(value) {
            if (value && motionWakeMode == MOTION_WAKE_DISABLED) {
                motionWakeMode = MOTION_WAKE_BRIGHTNESS
            } else if (!value) {
                motionWakeMode = MOTION_WAKE_DISABLED
            }
        }

    /**
     * Motion sensitivity in tenths of a percent (2-200, default 50 = 5.0%)
     * Used by the photo slideshow MotionDetector.
     * Maps to cameraWakeThresholdTenths for consistency.
     */
    val motionSensitivity: Int
        get() = cameraWakeThresholdTenths

    // ========== Photo Source Settings ==========

    /**
     * Photo source type: "ha_media" (default), "local", "immich", "google", "none"
     * - ha_media: Home Assistant /config/media folder (default for Dashie users)
     * - local: Local folder on device storage
     * - immich: Self-hosted Immich server
     * - google: Google Photos via Picker API
     * - none: No photo source configured (screensaver modes dim/black only)
     */
    var photoSourceType: String
        get() = prefs.getString(KEY_PHOTO_SOURCE_TYPE, PHOTO_SOURCE_UNSPLASH) ?: PHOTO_SOURCE_UNSPLASH
        set(value) { prefs.edit().putString(KEY_PHOTO_SOURCE_TYPE, value).commit() }

    /**
     * Whether a photo source is configured
     */
    val hasPhotoSource: Boolean
        get() = photoSourceType != PHOTO_SOURCE_NONE

    // --- Local Folder Settings ---

    /**
     * Path to local photo folder (for local source)
     * Empty string if not configured
     */
    var localPhotoFolder: String
        get() = prefs.getString(KEY_LOCAL_PHOTO_FOLDER, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LOCAL_PHOTO_FOLDER, value).commit() }

    /**
     * URI to local photo folder (for SAF-based access)
     * Used when file path cannot be determined from content URI
     */
    var localPhotoFolderUri: String
        get() = prefs.getString(KEY_LOCAL_PHOTO_FOLDER_URI, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LOCAL_PHOTO_FOLDER_URI, value).commit() }

    /**
     * Whether local folder is configured
     */
    val hasLocalPhotoFolder: Boolean
        get() = localPhotoFolder.isNotEmpty() || localPhotoFolderUri.isNotEmpty()

    // --- Immich Settings ---

    /**
     * Immich server URL (e.g., "http://192.168.1.100:2283")
     */
    var immichServerUrl: String
        get() = prefs.getString(KEY_IMMICH_SERVER_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_IMMICH_SERVER_URL, value).commit() }

    /**
     * Immich access token from POST /auth/login (~400-day session lifespan).
     * Stored centrally in HA via /api/dashie/immich/token and cached locally.
     */
    var immichAccessToken: String
        get() = prefs.getString(KEY_IMMICH_ACCESS_TOKEN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_IMMICH_ACCESS_TOKEN, value).commit() }

    /**
     * JSON array of selected Immich album IDs, or "*" for all albums.
     * Synced centrally via HA so all devices share the same selection.
     */
    var immichSelectedAlbums: String
        get() = prefs.getString(KEY_IMMICH_SELECTED_ALBUMS, "") ?: ""
        set(value) { prefs.edit().putString(KEY_IMMICH_SELECTED_ALBUMS, value).commit() }

    /**
     * Whether Immich is configured (has URL and access token)
     */
    val hasImmichConfig: Boolean
        get() = immichServerUrl.isNotEmpty() && immichAccessToken.isNotEmpty()

    // --- Google Photos Settings ---

    /**
     * Google Photos album ID (from Picker API selection)
     */
    var googlePhotosAlbumId: String
        get() = prefs.getString(KEY_GOOGLE_PHOTOS_ALBUM_ID, "") ?: ""
        set(value) { prefs.edit().putString(KEY_GOOGLE_PHOTOS_ALBUM_ID, value).commit() }

    /**
     * Google Photos refresh token for API access
     */
    var googlePhotosRefreshToken: String
        get() = prefs.getString(KEY_GOOGLE_PHOTOS_REFRESH_TOKEN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_GOOGLE_PHOTOS_REFRESH_TOKEN, value).commit() }

    /**
     * Whether Google Photos is configured
     */
    val hasGooglePhotosConfig: Boolean
        get() = googlePhotosAlbumId.isNotEmpty() && googlePhotosRefreshToken.isNotEmpty()

    // --- Home Assistant Media Settings ---

    /**
     * HA media folder to display ("*" for all, "." for root, or subfolder name)
     * Default: "*" (all photos from all folders)
     */
    var haMediaFolder: String
        get() = prefs.getString(KEY_HA_MEDIA_FOLDER, "*") ?: "*"
        set(value) { prefs.edit().putString(KEY_HA_MEDIA_FOLDER, value).commit() }

    /**
     * Whether HA Media is configured.
     * Note: This only checks if HA URL is available - actual token check is done by HaMediaPhotoSource
     */
    val hasHaMediaConfig: Boolean
        get() = true  // Always "configured" since we get credentials from WebView at runtime

    // --- Unsplash Settings ---
    // API key is no longer stored client-side. All Unsplash API calls go through
    // Supabase Edge Function proxy (unsplash-random, unsplash-download-track)
    // which keeps the key confidential as required by Unsplash guidelines.

    /**
     * Unsplash search query keywords (e.g., "nature,landscape,mountains")
     * Default: "nature" for a sensible out-of-box experience
     */
    var unsplashQuery: String
        get() = prefs.getString(KEY_UNSPLASH_QUERY, "nature") ?: "nature"
        set(value) { prefs.edit().putString(KEY_UNSPLASH_QUERY, value).commit() }

    /**
     * Enable clickable hyperlinks to Unsplash artist profiles in the photo explorer.
     * When disabled, artist credits are shown as plain text (no links).
     * Default: false (disabled) — many kiosk users don't want users leaving the app.
     */
    var unsplashArtistHyperlinks: Boolean
        get() = prefs.getBoolean(KEY_UNSPLASH_ARTIST_HYPERLINKS, false)
        set(value) { prefs.edit().putBoolean(KEY_UNSPLASH_ARTIST_HYPERLINKS, value).commit() }

    /**
     * Whether Unsplash is configured.
     * Always true — Unsplash API access is handled server-side via Edge Function proxy.
     */
    val hasUnsplashConfig: Boolean
        get() = true

    /**
     * Clear Unsplash configuration
     */
    fun clearUnsplashConfig() {
        prefs.edit()
            .remove(KEY_UNSPLASH_QUERY)
            .commit()
        if (photoSourceType == PHOTO_SOURCE_UNSPLASH) {
            photoSourceType = PHOTO_SOURCE_NONE
        }
    }

    // ========== Slideshow Settings ==========

    /**
     * Slideshow interval in seconds (how long each photo is shown)
     * Default: 15 seconds
     */
    var slideshowInterval: Int
        get() = prefs.getInt(KEY_SLIDESHOW_INTERVAL, DEFAULT_SLIDESHOW_INTERVAL)
        set(value) { prefs.edit().putInt(KEY_SLIDESHOW_INTERVAL, value.coerceIn(5, 900)).commit() }

    /**
     * Slideshow transition effect: "fade", "slide", "none"
     */
    var slideshowTransition: String
        get() = prefs.getString(KEY_SLIDESHOW_TRANSITION, DEFAULT_SLIDESHOW_TRANSITION) ?: DEFAULT_SLIDESHOW_TRANSITION
        set(value) { prefs.edit().putString(KEY_SLIDESHOW_TRANSITION, value).commit() }

    /**
     * Photo scaling mode: "fit" (letterbox — whole photo visible, default) or
     * "fill" (center-crop — photo fills the frame, edges cropped). Consumed by
     * PhotoSlideshowView.fitLandscapePhotos (FIT_CENTER vs CENTER_CROP).
     */
    var photoFit: String
        get() = prefs.getString(KEY_PHOTO_FIT, DEFAULT_PHOTO_FIT) ?: DEFAULT_PHOTO_FIT
        set(value) { prefs.edit().putString(KEY_PHOTO_FIT, value).commit() }

    /**
     * Shuffle photos in slideshow
     * When true, photos are shown in random order
     * Default: true
     */
    var slideshowShuffle: Boolean
        get() = prefs.getBoolean(KEY_SLIDESHOW_SHUFFLE, true)
        set(value) { prefs.edit().putBoolean(KEY_SLIDESHOW_SHUFFLE, value).commit() }

    /**
     * Show metadata ribbon (date/location) on photos
     * Default: true
     */
    var showMetadata: Boolean
        get() = prefs.getBoolean(KEY_SHOW_METADATA, true)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_METADATA, value).commit() }


    /**
     * Show clock overlay on screensaver
     * Default: false
     */
    var showClock: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CLOCK, false)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_CLOCK, value).commit() }

    var clockPosition: String
        get() = prefs.getString(KEY_CLOCK_POSITION, "top") ?: "top"
        set(value) { prefs.edit().putString(KEY_CLOCK_POSITION, value).commit() }

    /**
     * Show date below the clock overlay (month and day only)
     * Default: false
     */
    var showDate: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DATE, false)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_DATE, value).commit() }

    var clockSize: String
        get() = prefs.getString(KEY_CLOCK_SIZE, "medium") ?: "medium"
        set(value) { prefs.edit().putString(KEY_CLOCK_SIZE, value).commit() }

    var clockFontSize: Int
        get() = prefs.getInt(KEY_CLOCK_FONT_SIZE, 40)
        set(value) { prefs.edit().putInt(KEY_CLOCK_FONT_SIZE, value).commit() }

    /**
     * Use 24-hour clock format (e.g., 14:30 instead of 2:30 PM)
     * Default: false (12-hour format)
     */
    var use24HourClock: Boolean
        get() = prefs.getBoolean(KEY_USE_24_HOUR_CLOCK, false)
        set(value) { prefs.edit().putBoolean(KEY_USE_24_HOUR_CLOCK, value).commit() }

    // ========== Weather Overlay Settings ==========

    /**
     * Legacy boolean for weather overlay. Kept for backward compat reads.
     * New code should use [weatherMode] instead.
     */
    var weatherOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEATHER_OVERLAY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_WEATHER_OVERLAY_ENABLED, value).commit() }

    /**
     * Weather display mode on screensaver.
     * - "disabled": No weather shown
     * - "current": Current temp + weather emoji inline with clock
     * - "forecast": Full forecast card (WeatherClockOverlayView)
     *
     * Migrates from legacy [weatherOverlayEnabled] boolean on first read.
     * When weather was enabled but no explicit mode was ever chosen, the
     * default is "current" (Simple Weather) — the forecast card is the
     * heavier, opt-in presentation.
     */
    var weatherMode: String
        get() {
            val stored = prefs.getString(KEY_WEATHER_MODE, null)
            if (stored != null) return stored
            // Migrate from legacy boolean — default to Simple Weather, not
            // the forecast card, when weather is on but unconfigured.
            return if (weatherOverlayEnabled) "current" else "disabled"
        }
        set(value) { prefs.edit().putString(KEY_WEATHER_MODE, value).commit() }

    /**
     * User-controllable size for the forecast card overlay.
     * Values: "small" (0.85x), "medium" (1.0x), "large" (1.25x), "full" (~92% width, 1.6x scale).
     * Multiplies into WeatherClockOverlayView.uiScaleFactor.
     */
    var forecastCardSize: String
        get() = prefs.getString(KEY_FORECAST_CARD_SIZE, "medium") ?: "medium"
        set(value) { prefs.edit().putString(KEY_FORECAST_CARD_SIZE, value).commit() }

    /**
     * Home Assistant weather entity ID to fetch data from.
     * Default: "weather.forecast_home"
     */
    var weatherEntityId: String
        get() = prefs.getString(KEY_WEATHER_ENTITY_ID, DEFAULT_WEATHER_ENTITY_ID) ?: DEFAULT_WEATHER_ENTITY_ID
        set(value) { prefs.edit().putString(KEY_WEATHER_ENTITY_ID, value).commit() }

    /**
     * Show photo preview thumbnail in top-right corner when motion wakes the screen.
     * The thumbnail fades after 3 seconds. Tapping it opens gallery mode.
     * Default: true (enabled)
     */
    var showPreviewOnWake: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PREVIEW_ON_WAKE, true)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_PREVIEW_ON_WAKE, value).commit() }

    // ========== Utility Methods ==========

    /**
     * Check if current photo source is properly configured
     */
    val isPhotoSourceReady: Boolean
        get() = when (photoSourceType) {
            PHOTO_SOURCE_LOCAL -> hasLocalPhotoFolder
            PHOTO_SOURCE_IMMICH -> hasImmichConfig
            PHOTO_SOURCE_GOOGLE -> hasGooglePhotosConfig
            PHOTO_SOURCE_HA_MEDIA -> hasHaMediaConfig
            PHOTO_SOURCE_UNSPLASH -> hasUnsplashConfig
            else -> false
        }

    /**
     * Clear all photo source configuration
     */
    fun clearPhotoSourceConfig() {
        prefs.edit()
            .remove(KEY_PHOTO_SOURCE_TYPE)
            .remove(KEY_LOCAL_PHOTO_FOLDER)
            .remove(KEY_IMMICH_SERVER_URL)
            .remove(KEY_IMMICH_ACCESS_TOKEN)
            .remove(KEY_IMMICH_SELECTED_ALBUMS)
            .remove(KEY_GOOGLE_PHOTOS_ALBUM_ID)
            .remove(KEY_GOOGLE_PHOTOS_REFRESH_TOKEN)
            .commit()
    }

    /**
     * Clear Google Photos configuration (for sign-out)
     */
    fun clearGooglePhotosConfig() {
        prefs.edit()
            .remove(KEY_GOOGLE_PHOTOS_ALBUM_ID)
            .remove(KEY_GOOGLE_PHOTOS_REFRESH_TOKEN)
            .commit()
        if (photoSourceType == PHOTO_SOURCE_GOOGLE) {
            photoSourceType = PHOTO_SOURCE_NONE
        }
    }

    /**
     * Clear Immich configuration
     */
    fun clearImmichConfig() {
        prefs.edit()
            .remove(KEY_IMMICH_SERVER_URL)
            .remove(KEY_IMMICH_ACCESS_TOKEN)
            .remove(KEY_IMMICH_SELECTED_ALBUMS)
            .commit()
        if (photoSourceType == PHOTO_SOURCE_IMMICH) {
            photoSourceType = PHOTO_SOURCE_NONE
        }
    }

    /**
     * Clear local folder configuration
     */
    fun clearLocalFolderConfig() {
        prefs.edit()
            .remove(KEY_LOCAL_PHOTO_FOLDER)
            .commit()
        if (photoSourceType == PHOTO_SOURCE_LOCAL) {
            photoSourceType = PHOTO_SOURCE_NONE
        }
    }
}

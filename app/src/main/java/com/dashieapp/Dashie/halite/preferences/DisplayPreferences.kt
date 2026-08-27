package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * Display-related preferences for Dashie Kiosk.
 *
 * Manages:
 * - Clock format (12/24 hour)
 * - Auto-brightness settings (enabled, min, max, curve)
 * - Dashboard zoom level
 * - Low bandwidth mode (video display optimization)
 *
 * Uses the same SharedPreferences file as HalitePreferences for backward compatibility.
 */
class DisplayPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_lite_prefs"

        // Clock & date format
        private const val KEY_USE_24_HOUR_CLOCK = "use_24_hour_clock"
        private const val KEY_DATE_FORMAT = "date_format"           // "mdy" or "dmy"
        private const val KEY_TEMPERATURE_UNIT = "temperature_unit" // "F" or "C"

        // Auto-brightness settings
        private const val KEY_AUTO_BRIGHTNESS_ENABLED = "auto_brightness_enabled"
        private const val KEY_AUTO_BRIGHTNESS_MIN = "auto_brightness_min"
        private const val KEY_AUTO_BRIGHTNESS_MAX = "auto_brightness_max"
        private const val KEY_AUTO_BRIGHTNESS_CURVE = "auto_brightness_curve"

        // UI tips (hint overlays on focused widgets)
        private const val KEY_SHOW_UI_TIPS = "show_ui_tips"

        // UI state tracking
        private const val KEY_BETA_WELCOME_SHOWN = "beta_welcome_shown"
        private const val KEY_SWIPE_TIP_SHOWN = "swipe_tip_shown"
        private const val KEY_SETUP_PENDING_STEP = "setup_pending_step"
        private const val KEY_TIMER_POSITIONS = "timer_positions"

        // Brightness response curve options
        const val BRIGHTNESS_CURVE_LINEAR = "linear"       // Default, even response
        const val BRIGHTNESS_CURVE_AGGRESSIVE = "aggressive" // Faster brightening at low lux
        const val BRIGHTNESS_CURVE_GENTLE = "gentle"       // Slower brightening

        // Dashboard zoom level (HA Dashboard widget — historic name kept)
        private const val KEY_DASHBOARD_ZOOM = "dashboard_zoom"
        const val DEFAULT_DASHBOARD_ZOOM = 100
        // Floor is 10, not 0: zoom 0 renders nothing and the shell's
        // width/height compensation (100vh / zoom) divides by zero.
        const val MIN_DASHBOARD_ZOOM = 10
        const val MAX_DASHBOARD_ZOOM = 300

        // Widget zoom level — applies to Dashie core widgets (calendar,
        // chores, rewards, locations, agenda, photos, etc.) independently
        // of HA Dashboard zoom. Default 100% per user-stated intent.
        private const val KEY_WIDGET_ZOOM = "widget_zoom"
        const val DEFAULT_WIDGET_ZOOM = 100
        const val MIN_WIDGET_ZOOM = 50
        const val MAX_WIDGET_ZOOM = 300

        // Font Size — percentage (75–200, 100 = base) applied to web widget
        // TEXT via --widget-font-scale (calendar, chores, weather, photos…).
        // Independent of zoom: grows text without changing layout. Mirrors
        // interface.widgetFontSize on the web side.
        private const val KEY_WIDGET_FONT_SIZE = "widget_font_size"
        const val DEFAULT_WIDGET_FONT_SIZE = 100
        const val MIN_WIDGET_FONT_SIZE = 75
        const val MAX_WIDGET_FONT_SIZE = 200

        // Display Size — percentage (100–200, 100 = base) applied to NATIVE
        // chrome/overlays (sidebar, control center, music/video/timer/voice).
        // Independent of Font Size (web text) and Widget Zoom (web layout).
        // Read live by DisplaySizeScale.
        private const val KEY_DISPLAY_SIZE = "display_size"
        const val DEFAULT_DISPLAY_SIZE = 100
        const val MIN_DISPLAY_SIZE = 100
        const val MAX_DISPLAY_SIZE = 200

        // Low bandwidth mode
        private const val KEY_LOW_BANDWIDTH_MODE = "low_bandwidth_mode"
        private const val KEY_GPU_HARDWARE_LAYER = "gpu_hardware_layer_enabled"

        // Dash Menu sidebar
        private const val KEY_DASH_MENU_ENABLED = "dash_menu_enabled"
        private const val KEY_DASH_MENU_PINNED = "dash_menu_pinned"
        private const val KEY_SIDEBAR_ICON_SIZE = "sidebar_icon_size"

        // Layout mode
        private const val KEY_LAYOUT_MODE = "layout_mode"  // "single_panel" or "widgets"

        // Orientation lock — only meaningful in Widgets layout mode.
        // Auto follows the device sensor; the four explicit values map
        // directly to ActivityInfo.SCREEN_ORIENTATION_* flags so wall-
        // mounted devices (no usable accelerometer) can pin to a specific
        // rotation regardless of how the OS thinks the device is oriented.
        //   "landscape"         → SCREEN_ORIENTATION_LANDSCAPE   (0°)
        //   "landscape_reverse" → SCREEN_ORIENTATION_REVERSE_LANDSCAPE (180°)
        //   "portrait"          → SCREEN_ORIENTATION_PORTRAIT    (90°)
        //   "portrait_reverse"  → SCREEN_ORIENTATION_REVERSE_PORTRAIT  (270°)
        private const val KEY_ORIENTATION_LOCK = "orientation_lock"
        const val ORIENTATION_LOCK_AUTO = "auto"
        const val ORIENTATION_LOCK_LANDSCAPE = "landscape"
        const val ORIENTATION_LOCK_LANDSCAPE_REVERSE = "landscape_reverse"
        const val ORIENTATION_LOCK_PORTRAIT = "portrait"
        const val ORIENTATION_LOCK_PORTRAIT_REVERSE = "portrait_reverse"

        // Permission prompt
        private const val KEY_PERMISSION_PROMPT_DECLINED = "permission_prompt_declined"

        // Fire TV idle-kill warning dialog (Still Watching, etc.)
        private const val KEY_FIRETV_IDLE_WARNING_DISMISSED = "firetv_idle_warning_dismissed"

        // Countries that use Fahrenheit as their primary temperature unit
        private val FAHRENHEIT_COUNTRIES = setOf("US", "BS", "BZ", "KY", "PW", "MH", "FM")
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ========== Clock Format ==========

    /**
     * Use 24-hour clock format throughout the app (e.g., 14:30 instead of 2:30 PM).
     * This is a universal preference that applies to screensaver clock, time pickers, etc.
     */
    var use24HourClock: Boolean
        get() = prefs.getBoolean(KEY_USE_24_HOUR_CLOCK, false)
        set(value) { prefs.edit().putBoolean(KEY_USE_24_HOUR_CLOCK, value).commit() }

    /**
     * Date format: "mdy" = "Month Day, Year", "dmy" = "Day Month Year".
     * Defaults based on device locale (US uses mdy, most other countries use dmy).
     */
    var dateFormat: String
        get() {
            val stored = prefs.getString(KEY_DATE_FORMAT, null)
            if (stored != null) return stored
            val country = java.util.Locale.getDefault().country?.uppercase()
            return if (country == "US") "mdy" else "dmy"
        }
        set(value) { prefs.edit().putString(KEY_DATE_FORMAT, value).commit() }

    /**
     * Temperature unit: "F" = Fahrenheit, "C" = Celsius.
     * Defaults based on device locale (Fahrenheit for US and a few others, Celsius elsewhere).
     */
    var temperatureUnit: String
        get() {
            val stored = prefs.getString(KEY_TEMPERATURE_UNIT, null)
            if (stored != null) return stored
            // Auto-detect from locale: only US and a handful of countries use Fahrenheit
            val country = java.util.Locale.getDefault().country?.uppercase()
            return if (country in FAHRENHEIT_COUNTRIES) "F" else "C"
        }
        set(value) { prefs.edit().putString(KEY_TEMPERATURE_UNIT, value).commit() }

    // ========== Auto-Brightness Settings ==========

    /**
     * Auto-brightness enabled (uses light sensor to adjust brightness)
     * When enabled, brightness is automatically adjusted between min and max based on ambient light
     */
    var autoBrightnessEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BRIGHTNESS_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_BRIGHTNESS_ENABLED, value).commit() }

    /**
     * Auto-brightness minimum level (0-100%)
     * Used as brightness when room is dark
     */
    var autoBrightnessMin: Int
        get() = prefs.getInt(KEY_AUTO_BRIGHTNESS_MIN, 10)
        set(value) { prefs.edit().putInt(KEY_AUTO_BRIGHTNESS_MIN, value.coerceIn(0, 100)).commit() }

    /**
     * Auto-brightness maximum level (0-100%)
     * Used as brightness when room is bright
     */
    var autoBrightnessMax: Int
        get() = prefs.getInt(KEY_AUTO_BRIGHTNESS_MAX, 100)
        set(value) { prefs.edit().putInt(KEY_AUTO_BRIGHTNESS_MAX, value.coerceIn(0, 100)).commit() }

    /**
     * Auto-brightness response curve: "linear", "aggressive", "gentle"
     * - linear: Even response across lux range (default)
     * - aggressive: Faster brightening at lower lux levels
     * - gentle: Slower brightening, stays dimmer longer
     */
    var autoBrightnessCurve: String
        get() = prefs.getString(KEY_AUTO_BRIGHTNESS_CURVE, BRIGHTNESS_CURVE_LINEAR) ?: BRIGHTNESS_CURVE_LINEAR
        set(value) { prefs.edit().putString(KEY_AUTO_BRIGHTNESS_CURVE, value).commit() }

    // ========== Dashboard Zoom ==========

    /**
     * Dashboard zoom level (50-200%, 100 = default)
     * Allows users to zoom in or out on the dashboard for better visibility on different screen sizes.
     */
    var dashboardZoom: Int
        get() = prefs.getInt(KEY_DASHBOARD_ZOOM, DEFAULT_DASHBOARD_ZOOM)
        set(value) { prefs.edit().putInt(KEY_DASHBOARD_ZOOM, value.coerceIn(MIN_DASHBOARD_ZOOM, MAX_DASHBOARD_ZOOM)).commit() }

    /**
     * Widget zoom level (50-200%, 100 = default). Applies only to Dashie
     * core widgets (calendar, chores, rewards, locations, etc.) — the HA
     * dashboard widget uses [dashboardZoom] independently.
     */
    var widgetZoom: Int
        get() = prefs.getInt(KEY_WIDGET_ZOOM, DEFAULT_WIDGET_ZOOM)
        set(value) { prefs.edit().putInt(KEY_WIDGET_ZOOM, value.coerceIn(MIN_WIDGET_ZOOM, MAX_WIDGET_ZOOM)).commit() }

    /**
     * Font Size (75–200%, 100 = default). Scales web widget TEXT via
     * --widget-font-scale without changing layout. Mirrors the web-side
     * interface.widgetFontSize; pushed to the WebView via
     * ACTION_APPLY_WIDGET_FONT_SIZE.
     */
    var widgetFontSize: Int
        get() = prefs.getInt(KEY_WIDGET_FONT_SIZE, DEFAULT_WIDGET_FONT_SIZE)
        set(value) { prefs.edit().putInt(KEY_WIDGET_FONT_SIZE, value.coerceIn(MIN_WIDGET_FONT_SIZE, MAX_WIDGET_FONT_SIZE)).commit() }

    /**
     * Display Size (100–200%, 100 = default). Scales native chrome/overlays
     * (sidebar, control center, music/video/timer/voice) via DisplaySizeScale.
     * Native-only — does not affect the WebView.
     */
    var displaySize: Int
        get() = prefs.getInt(KEY_DISPLAY_SIZE, DEFAULT_DISPLAY_SIZE)
        set(value) { prefs.edit().putInt(KEY_DISPLAY_SIZE, value.coerceIn(MIN_DISPLAY_SIZE, MAX_DISPLAY_SIZE)).commit() }

    // ========== Low Bandwidth Mode ==========

    /**
     * Low bandwidth mode - hides video controls and loading spinners on camera/WebRTC cards.
     * Useful for low-powered tablets that can't keep up with high-resolution video streams.
     * When the decoder drops frames, native video controls (scrub bar, loading spinner) appear
     * repeatedly which is distracting. This hides those controls so the video just shows
     * the last decoded frame without the distracting UI elements.
     * Default: false (show native controls)
     */
    var lowBandwidthMode: Boolean
        get() = prefs.getBoolean(KEY_LOW_BANDWIDTH_MODE, false)
        set(value) { prefs.edit().putBoolean(KEY_LOW_BANDWIDTH_MODE, value).commit() }

    /**
     * Force the WebView's compositor to use LAYER_TYPE_HARDWARE.
     * Workaround for camera cards (Advanced Camera Card, WebRTC, etc.)
     * going blank a few seconds after page load and only repainting on
     * layout reflow. Off by default — risks rendering the dashboard
     * entirely blank on some Fire TVs / older GPUs that fail HARDWARE
     * texture allocation. Requires app restart to take effect since the
     * layer type is set during WebView init.
     */
    var gpuHardwareLayerEnabled: Boolean
        get() = prefs.getBoolean(KEY_GPU_HARDWARE_LAYER, false)
        set(value) { prefs.edit().putBoolean(KEY_GPU_HARDWARE_LAYER, value).commit() }

    // ========== Dash Menu ==========

    /**
     * Show the Dash Menu sidebar (JS-rendered icon strip on left edge).
     * Currently debug-only. Requires app restart to take effect.
     */
    var dashMenuEnabled: Boolean
        get() = prefs.getBoolean(KEY_DASH_MENU_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_DASH_MENU_ENABLED, value).commit() }

    /**
     * Whether the dash menu sidebar is pinned (always visible) or overlay (swipe to reveal).
     * Default false — unpinned (overlay) mode; user swipes to reveal.
     */
    var dashMenuPinned: Boolean
        get() = prefs.getBoolean(KEY_DASH_MENU_PINNED, false)
        set(value) { prefs.edit().putBoolean(KEY_DASH_MENU_PINNED, value).commit() }

    /**
     * Sidebar icon size multiplier: "0.75", "0.9", "1", "1.15", "1.3".
     * Applied to native sidebar strip icon dimensions (base 30dp).
     */
    var sidebarIconSize: String
        get() = prefs.getString(KEY_SIDEBAR_ICON_SIZE, "1") ?: "1"
        set(value) { prefs.edit().putString(KEY_SIDEBAR_ICON_SIZE, value).commit() }

    /**
     * Dashboard layout mode:
     * - "widgets" — standard grid layout (layout canvas, default)
     * - "single_panel" — full-screen single widget with rotation
     * - "kiosk" — HA-only kiosk mode (switches to kiosk shell)
     * - "legacy" — old Dashboard module (pre-layout-system)
     * - "canvas" — freeform drag/drop layout designer (dev)
     */
    var layoutMode: String
        get() = prefs.getString(KEY_LAYOUT_MODE, "widgets") ?: "widgets"
        set(value) { prefs.edit().putString(KEY_LAYOUT_MODE, value).commit() }

    /**
     * Orientation lock for the widgets dashboard. Only honored when
     * [layoutMode] == "widgets" — single_panel / kiosk keep the device's
     * current orientation. Values: "auto" | "landscape" | "portrait".
     */
    var orientationLock: String
        get() = prefs.getString(KEY_ORIENTATION_LOCK, ORIENTATION_LOCK_AUTO) ?: ORIENTATION_LOCK_AUTO
        set(value) { prefs.edit().putString(KEY_ORIENTATION_LOCK, value).commit() }

    // ========== UI Tips ==========

    /**
     * Show on-widget UI tip strips when a native widget is d-pad-activated
     * (e.g. "◀ ▶ next / prev · ● full screen" on the photo widget).
     * Default: true.
     */
    var showUiTips: Boolean
        get() = prefs.getBoolean(KEY_SHOW_UI_TIPS, true)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_UI_TIPS, value).commit() }

    // ========== UI State ==========

    /** Beta welcome dialog shown. Default: false */
    var betaWelcomeShown: Boolean
        get() = prefs.getBoolean(KEY_BETA_WELCOME_SHOWN, false)
        set(value) { prefs.edit().putBoolean(KEY_BETA_WELCOME_SHOWN, value).commit() }

    /** Swipe tip popup shown. Default: false */
    var swipeTipShown: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_TIP_SHOWN, false)
        set(value) { prefs.edit().putBoolean(KEY_SWIPE_TIP_SHOWN, value).commit() }

    /**
     * Tracks which step of the first-launch permission setup flow is pending.
     * 0 = idle, 1 = awaiting overlay return, 2 = awaiting exact alarm return, 3 = awaiting device admin return
     */
    var setupPendingStep: Int
        get() = prefs.getInt(KEY_SETUP_PENDING_STEP, 0)
        set(value) { prefs.edit().putInt(KEY_SETUP_PENDING_STEP, value).commit() }

    /** Timer overlay positions (JSON map of timer ID -> {x, y}). */
    var timerPositions: String
        get() = prefs.getString(KEY_TIMER_POSITIONS, "{}") ?: "{}"
        set(value) { prefs.edit().putString(KEY_TIMER_POSITIONS, value).commit() }

    /** User declined the device permissions prompt permanently. Can be re-enabled from API settings. */
    var permissionPromptDeclined: Boolean
        get() = prefs.getBoolean(KEY_PERMISSION_PROMPT_DECLINED, false)
        set(value) { prefs.edit().putBoolean(KEY_PERMISSION_PROMPT_DECLINED, value).commit() }

    /**
     * User checked "Don't show again" on the Fire TV idle-kill warning dialog
     * (e.g. "Still Watching" closed Dashie overnight). When true, the dialog is
     * suppressed even if a known idle-killer is detected at next onResume.
     */
    var firetvIdleWarningDismissed: Boolean
        get() = prefs.getBoolean(KEY_FIRETV_IDLE_WARNING_DISMISSED, false)
        set(value) { prefs.edit().putBoolean(KEY_FIRETV_IDLE_WARNING_DISMISSED, value).commit() }
}

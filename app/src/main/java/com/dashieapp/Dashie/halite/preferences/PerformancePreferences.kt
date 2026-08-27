package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferences manager for performance, memory management, and diagnostics settings.
 * Extracted from HalitePreferences to improve modularity.
 *
 * Handles:
 * - Memory recovery and threshold settings (RAM %, PSS)
 * - Stealth WebView refresh for memory stability
 * - Emergency recovery settings
 * - Performance overlay and diagnostics
 * - Connection stability (WiFi lock, auto-reload)
 * - OOM kill detection
 */
class PerformancePreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_lite_prefs"

        // Performance/Connection Stability
        private const val KEY_WIFI_LOCK_ENABLED = "wifi_lock_enabled"
        private const val KEY_AUTO_RELOAD_STALE_MINUTES = "auto_reload_stale_minutes"
        private const val KEY_BATTERY_OPTIMIZATION_PROMPTED = "battery_optimization_prompted"
        private const val KEY_WEBSOCKET_PING_ENABLED = "websocket_ping_enabled"
        private const val KEY_SMART_RECONNECT_ENABLED = "smart_reconnect_enabled"
        private const val KEY_LOCAL_HA_URL = "local_ha_url"

        // Memory Recovery
        private const val KEY_MEMORY_RECOVERY_ENABLED = "memory_recovery_enabled"
        private const val KEY_PROACTIVE_REFRESH_HOURS = "proactive_refresh_hours"

        // Stealth WebView Refresh
        private const val KEY_STEALTH_REFRESH_ENABLED = "stealth_refresh_enabled"
        private const val KEY_STEALTH_REFRESH_INTERVAL_MINUTES = "stealth_refresh_interval_minutes"
        private const val KEY_DAILY_REFRESH_HOUR = "daily_refresh_hour"
        private const val KEY_DAILY_REFRESH_HOURS = "daily_refresh_hours"  // Multi-select: comma-separated hours
        private const val KEY_CUSTOM_REFRESH_TEST_TIME = "custom_refresh_test_time"  // Millis timestamp for custom test
        private const val KEY_USE_EXACT_ALARMS = "use_exact_alarms"  // User preference for exact alarm scheduling

        // Emergency Recovery
        private const val KEY_EMERGENCY_RECOVERY_ENABLED = "emergency_recovery_enabled"
        private const val KEY_EMERGENCY_THRESHOLD_MODE = "emergency_threshold_mode"
        private const val KEY_EMERGENCY_THRESHOLD_MB = "emergency_threshold_mb"

        // Dual Threshold System (RAM % OR Heap %)
        private const val KEY_IDLE_RAM_PERCENT = "idle_ram_percent"
        private const val KEY_IDLE_HEAP_PERCENT = "idle_heap_percent"
        private const val KEY_CRITICAL_RAM_PERCENT = "critical_ram_percent"
        private const val KEY_CRITICAL_HEAP_PERCENT = "critical_heap_percent"

        // RTSP server restart on memory pressure
        private const val KEY_RESTART_RTSP_ON_MEMORY_PRESSURE = "restart_rtsp_on_memory_pressure"

        // Auto reload on crash (OOM kill recovery)
        private const val KEY_AUTO_RELOAD_ON_CRASH = "auto_reload_on_crash"
        private const val KEY_LAST_WEBVIEW_URL = "last_webview_url"

        // Enhanced Logging
        private const val KEY_ENHANCED_LOGGING_ENABLED = "enhanced_logging_enabled"

        // OOM Kill Detection
        private const val KEY_APP_WAS_RUNNING = "app_was_running"
        private const val KEY_APP_START_TIME = "app_start_time"
        private const val KEY_LAST_MEMORY_READING_MB = "last_memory_reading_mb"
        private const val KEY_LAST_RAM_PERCENT = "last_ram_percent"
        private const val KEY_HOURLY_PSS_TREND = "hourly_pss_trend"
        private const val KEY_HOURLY_RAM_TREND = "hourly_ram_trend"
        private const val KEY_HOURLY_HEAP_TREND = "hourly_heap_trend"
        private const val KEY_LAST_SYSTEM_MEMINFO = "last_system_meminfo"
        private const val KEY_LAST_APP_MEMORY_BREAKDOWN = "last_app_memory_breakdown"

        // WebView Renderer Recovery Tracking
        private const val KEY_RENDERER_RECOVERY_PENDING = "renderer_recovery_pending"
        private const val KEY_LAST_HA_IFRAME_URL = "last_ha_iframe_url"
        private const val KEY_CRASH_RESTORE_URL = "crash_restore_url"

        // Diagnostics Mode
        private const val KEY_DIAGNOSTICS_MODE_ENABLED = "diagnostics_mode_enabled"
        private const val KEY_PERFORMANCE_OVERLAY_ENABLED = "performance_overlay_enabled"
        private const val KEY_PERFORMANCE_OVERLAY_POSITION = "performance_overlay_position"

        // Other Performance
        private const val KEY_RETURN_HOME_TIMEOUT = "return_home_timeout"
        private const val KEY_INTERACTION_PRIORITY_ENABLED = "interaction_priority_enabled"

        // Dashboard Telemetry
        private const val KEY_DASHBOARD_TELEMETRY_ENABLED = "dashboard_telemetry_enabled"
        private const val KEY_DASHBOARD_TELEMETRY_CONSENT = "dashboard_telemetry_consent"
        private const val KEY_LOG_WEBVIEW_API_REQUESTS = "log_webview_api_requests"

        // App Version Tracking
        private const val KEY_LAST_APP_VERSION = "last_app_version"

        // One-time migration markers (one per migration so they're independently re-runnable
        // if we ever need to repeat). Only set to true after the migration runs successfully.
        private const val KEY_MIGRATION_RAN_STEALTH_DEFAULT_2_24_15 =
            "migration_ran_stealth_default_2_24_15"

        // Default values
        const val DEFAULT_PROACTIVE_REFRESH_HOURS = 4  // Refresh every 4 hours
        const val DEFAULT_STEALTH_REFRESH_INTERVAL_MINUTES = 240  // 4 hours

        // Emergency threshold modes
        const val THRESHOLD_MODE_AUTO = "auto"
        const val THRESHOLD_MODE_CUSTOM = "custom"
        const val DEFAULT_EMERGENCY_THRESHOLD_MB = 550

        // Dual threshold defaults
        const val DEFAULT_IDLE_RAM_PERCENT = 82  // 0 = disabled
        const val DEFAULT_IDLE_HEAP_PERCENT = 80  // 0 = disabled
        const val DEFAULT_CRITICAL_RAM_PERCENT = 90  // 0 = disabled
        const val DEFAULT_CRITICAL_HEAP_PERCENT = 88  // 0 = disabled

        // Performance overlay positions
        const val OVERLAY_POSITION_TOP_LEFT = "top-left"
        const val OVERLAY_POSITION_TOP_RIGHT = "top-right"
        const val OVERLAY_POSITION_BOTTOM_LEFT = "bottom-left"
        const val OVERLAY_POSITION_BOTTOM_RIGHT = "bottom-right"

        // Return home timeout
        const val DEFAULT_RETURN_HOME_TIMEOUT = 0
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        runMigrations()
    }

    /**
     * Re-enables Stealth Refresh for users upgrading from a version where it was
     * either default-off or who turned it off before we knew it was load-bearing
     * for camera-heavy dashboards (Michael Achermann's OOM, 2026-04-22). Runs
     * once per device — gated by [KEY_MIGRATION_RAN_STEALTH_DEFAULT_2_24_15].
     * After this migration, users who turn it off again will keep that choice.
     */
    private fun runMigrations() {
        if (!prefs.getBoolean(KEY_MIGRATION_RAN_STEALTH_DEFAULT_2_24_15, false)) {
            // Force Stealth Refresh ON at 4h regardless of prior state. We
            // accept overwriting users who explicitly disabled it in 2.24.14B
            // or earlier — the cost of leaving them off is OOM kills.
            prefs.edit()
                .putBoolean(KEY_STEALTH_REFRESH_ENABLED, true)
                .putInt(KEY_STEALTH_REFRESH_INTERVAL_MINUTES, DEFAULT_STEALTH_REFRESH_INTERVAL_MINUTES)
                .putBoolean(KEY_MIGRATION_RAN_STEALTH_DEFAULT_2_24_15, true)
                .commit()
        }
    }

    // ========== Performance/Connection Stability Settings ==========

    /**
     * WiFi lock enabled - keeps WiFi connection active when screen is off.
     * Prevents Android from dropping the WiFi connection during Doze mode.
     * Default: true (enabled by default for always-on dashboards)
     */
    var wifiLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_WIFI_LOCK_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_WIFI_LOCK_ENABLED, value).commit() }

    /**
     * Auto-reload when connection is stale (minutes).
     * If no WebSocket messages received for this duration, reload the page.
     * 0 = disabled, typical values: 2, 5, 10
     * Default: 3 minutes
     */
    var autoReloadStaleMinutes: Int
        get() = prefs.getInt(KEY_AUTO_RELOAD_STALE_MINUTES, 3)
        set(value) { prefs.edit().putInt(KEY_AUTO_RELOAD_STALE_MINUTES, value.coerceIn(0, 30)).commit() }

    /**
     * Track if we've prompted user for battery optimization exemption.
     * Prevents nagging - only prompt once unless user explicitly opens settings.
     */
    var batteryOptimizationPrompted: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_OPTIMIZATION_PROMPTED, false)
        set(value) { prefs.edit().putBoolean(KEY_BATTERY_OPTIMIZATION_PROMPTED, value).commit() }

    /**
     * WebSocket ping keep-alive enabled.
     * Now always enabled - required for connection stability and early disconnect detection.
     */
    var websocketPingEnabled: Boolean
        get() = true  // Always enabled - no longer optional
        set(value) { /* No-op, always enabled */ }

    /**
     * Smart reconnect enabled.
     * Now always enabled - required for RTSP stability and preventing OOM crashes.
     */
    var smartReconnectEnabled: Boolean
        get() = true  // Always enabled - no longer optional
        set(value) { /* No-op, always enabled */ }

    /**
     * Local HA URL for smart reconnect ping loop.
     * This should be the local/internal URL to HA (e.g., "http://192.168.1.100:8123")
     * that can be pinged without going through a reverse proxy.
     */
    // Trimmed on get/set: a trailing space makes java.net.URL(localHaUrl) throw
    // MalformedURLException in HaConnectionMonitor's ping loop. Trimming on get
    // repairs already-stored bad values without the user re-entering the URL.
    var localHaUrl: String
        get() = (prefs.getString(KEY_LOCAL_HA_URL, "") ?: "").trim()
        set(value) { prefs.edit().putString(KEY_LOCAL_HA_URL, value.trim()).commit() }

    // ========== Memory Recovery Settings ==========

    /**
     * Memory recovery enabled.
     * When enabled, the overlay iframe will be refreshed when Android signals
     * memory pressure (RUNNING_LOW, RUNNING_CRITICAL). This helps prevent
     * WebView renderer crashes on long-running devices.
     * Default: true (enabled)
     */
    var memoryRecoveryEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEMORY_RECOVERY_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_MEMORY_RECOVERY_ENABLED, value).commit() }

    /**
     * Proactive overlay refresh interval (hours).
     * When set, the overlay iframe will be refreshed periodically to prevent
     * memory accumulation from JavaScript leaks.
     * 0 = disabled (only refresh on memory pressure)
     * 4, 8, 12 = refresh every N hours
     * Default: 0 (disabled)
     */
    var proactiveRefreshHours: Int
        get() = prefs.getInt(KEY_PROACTIVE_REFRESH_HOURS, DEFAULT_PROACTIVE_REFRESH_HOURS)
        set(value) { prefs.edit().putInt(KEY_PROACTIVE_REFRESH_HOURS, value.coerceIn(0, 24)).commit() }

    // ========== Stealth WebView Refresh ==========

    /**
     * Stealth WebView refresh enabled.
     * When enabled, the entire WebView will be reloaded during screensaver or sleep mode
     * to release accumulated GPU texture memory.
     * Default: true (enabled)
     */
    var stealthRefreshEnabled: Boolean
        get() = prefs.getBoolean(KEY_STEALTH_REFRESH_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_STEALTH_REFRESH_ENABLED, value).commit() }

    /**
     * Stealth refresh interval (minutes).
     * How often to refresh the WebView during screensaver/sleep.
     * 0 = disabled (rely on Daily Scheduled only)
     * Default: 240 (4 hours)
     */
    var stealthRefreshIntervalMinutes: Int
        get() = prefs.getInt(KEY_STEALTH_REFRESH_INTERVAL_MINUTES, DEFAULT_STEALTH_REFRESH_INTERVAL_MINUTES)
        set(value) {
            // Allow 0 (disabled), or 15-480 minutes
            val clampedValue = when {
                value == 0 -> 0  // Disabled
                value < 15 -> 15  // Minimum 15 minutes if enabled
                value > 480 -> 480  // Maximum 8 hours
                else -> value
            }
            prefs.edit().putInt(KEY_STEALTH_REFRESH_INTERVAL_MINUTES, clampedValue).commit()
        }

    /**
     * Daily scheduled refresh hour (0-23).
     * @deprecated Use dailyRefreshHours for multi-select support.
     * Kept for backward compatibility - reads from new format if available.
     * 0 = disabled
     */
    var dailyRefreshHour: Int
        get() {
            // If multi-select hours exist, return the first one (for backward compat)
            val hours = dailyRefreshHours
            return if (hours.isNotEmpty()) hours.first() else 0
        }
        set(value) {
            // Setting single hour clears all and sets just this one
            dailyRefreshHours = if (value == 0) emptySet() else setOf(value)
        }

    /**
     * Daily scheduled refresh hours (multi-select).
     * Stored as comma-separated string, e.g., "6,23" for 6 AM and 11 PM.
     * Empty set = disabled
     * Default: empty (disabled)
     */
    var dailyRefreshHours: Set<Int>
        get() {
            val stored = prefs.getString(KEY_DAILY_REFRESH_HOURS, null)
            if (stored.isNullOrBlank()) {
                // Migrate from old single-hour setting if exists
                val oldHour = prefs.getInt(KEY_DAILY_REFRESH_HOUR, 0)
                // Default to 1am if no value set — pairs with stealth refresh
                // every 4h to give 1am + 5am overnight refreshes (least disruptive
                // window). Users who explicitly chose another hour are respected.
                return if (oldHour > 0) setOf(oldHour) else setOf(1)
            }
            return stored.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..24 }  // Valid hours: 1-24 (24 = midnight)
                .toSet()
        }
        set(value) {
            val filtered = value.filter { it in 1..24 }.toSet()
            val str = if (filtered.isEmpty()) "" else filtered.sorted().joinToString(",")
            prefs.edit().putString(KEY_DAILY_REFRESH_HOURS, str).commit()
        }

    /**
     * Custom one-shot test refresh time (milliseconds since epoch).
     * When set, the next scheduled refresh will be at this time instead of
     * the regular hourly schedule. Cleared after the refresh fires.
     * 0 = disabled (use normal schedule)
     */
    var customRefreshTestTime: Long
        get() = prefs.getLong(KEY_CUSTOM_REFRESH_TEST_TIME, 0)
        set(value) { prefs.edit().putLong(KEY_CUSTOM_REFRESH_TEST_TIME, value).commit() }

    /**
     * User preference for using exact alarms (setExactAndAllowWhileIdle).
     * When enabled AND permission is granted, alarms fire at exact times.
     * When disabled OR permission not granted, alarms may drift 5-15 minutes.
     * Default: true (enabled - use exact alarms for precise timing)
     */
    var useExactAlarms: Boolean
        get() = prefs.getBoolean(KEY_USE_EXACT_ALARMS, true)
        set(value) { prefs.edit().putBoolean(KEY_USE_EXACT_ALARMS, value).commit() }

    // ========== Emergency Recovery Settings ==========

    /**
     * Emergency recovery enabled.
     * When enabled, the WebView will be force-reloaded when memory pressure is critical.
     * Default: false (disabled)
     *
     * 🔴 **OFF BY DEFAULT ON PURPOSE — do not "fix" this.** ruled it 2026-08-04, after Thread T
     * proved the recovery path itself works 5/5 when switched on. The recovery is not what is
     * broken; the **trigger metric** is:
     *
     * > *"PSS has flaws here: the WebView memory isn't always included in the app's PSS, so it
     * > doesn't work reliably."*
     *
     * The WebView runs in a **separate renderer process**, so the PSS figure this gate fires on can
     * omit the very memory it is meant to police — it under-counts the process it exists to watch.
     * Enabling it by default would mean firing late, erratically, or not at all, and a force-reload
     * of the dashboard is not a cheap misfire.
     *
     * ⚠️ The trap this note exists to stop: a future reader finds a **healthy, tested recovery path**
     * next to an **off-by-default gate**, concludes someone forgot to flip it, and turns it on. The
     * gate is off because its INPUT is unreliable, not because the output is unproven. Fixing it
     * means finding a trigger metric that actually sees renderer memory — not changing this default.
     */
    var emergencyRecoveryEnabled: Boolean
        get() = prefs.getBoolean(KEY_EMERGENCY_RECOVERY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_EMERGENCY_RECOVERY_ENABLED, value).commit() }

    /**
     * Emergency threshold mode: "auto" or "custom".
     * Auto: Use dynamically calculated threshold based on device RAM.
     * Custom: Use user-specified threshold value.
     * Default: "auto"
     */
    var emergencyThresholdMode: String
        get() = prefs.getString(KEY_EMERGENCY_THRESHOLD_MODE, THRESHOLD_MODE_AUTO) ?: THRESHOLD_MODE_AUTO
        set(value) { prefs.edit().putString(KEY_EMERGENCY_THRESHOLD_MODE, value).commit() }

    /**
     * Custom emergency threshold (MB).
     * Only used when emergencyThresholdMode is "custom".
     * Default: 550 MB
     */
    var emergencyThresholdMb: Int
        get() = prefs.getInt(KEY_EMERGENCY_THRESHOLD_MB, DEFAULT_EMERGENCY_THRESHOLD_MB)
        set(value) { prefs.edit().putInt(KEY_EMERGENCY_THRESHOLD_MB, value.coerceIn(200, 1000)).commit() }

    // ========== Dual Threshold System (RAM % OR PSS) ==========

    /**
     * Idle RAM % threshold (during screensaver).
     * When RAM usage exceeds this percentage AND screensaver is active, trigger refresh.
     * 0 = disabled
     * Default: 85%
     */
    var idleRamPercent: Int
        get() = prefs.getInt(KEY_IDLE_RAM_PERCENT, DEFAULT_IDLE_RAM_PERCENT)
        set(value) { prefs.edit().putInt(KEY_IDLE_RAM_PERCENT, value.coerceIn(0, 99)).commit() }

    /**
     * Idle Heap % threshold.
     * When heap usage exceeds this AND screensaver is active, trigger refresh.
     * 0 = disabled
     * Default: 80%
     */
    var idleHeapPercent: Int
        get() = prefs.getInt(KEY_IDLE_HEAP_PERCENT, DEFAULT_IDLE_HEAP_PERCENT)
        set(value) { prefs.edit().putInt(KEY_IDLE_HEAP_PERCENT, value.coerceIn(0, 99)).commit() }

    /**
     * Critical RAM % threshold (immediate refresh).
     * When RAM usage exceeds this percentage, trigger immediate refresh.
     * 0 = disabled
     * Default: 92%
     */
    var criticalRamPercent: Int
        get() = prefs.getInt(KEY_CRITICAL_RAM_PERCENT, DEFAULT_CRITICAL_RAM_PERCENT)
        set(value) { prefs.edit().putInt(KEY_CRITICAL_RAM_PERCENT, value.coerceIn(0, 99)).commit() }

    /**
     * Critical Heap % threshold.
     * When heap usage exceeds this, trigger immediate refresh.
     * 0 = disabled
     * Default: 88%
     */
    var criticalHeapPercent: Int
        get() = prefs.getInt(KEY_CRITICAL_HEAP_PERCENT, DEFAULT_CRITICAL_HEAP_PERCENT)
        set(value) { prefs.edit().putInt(KEY_CRITICAL_HEAP_PERCENT, value.coerceIn(0, 99)).commit() }

    /**
     * Restart RTSP server on memory pressure.
     * When enabled, the RTSP server will be stopped and restarted during memory pressure events
     * to release OpenGL textures and MediaCodec resources that WebView recreation doesn't touch.
     * This is especially helpful for devices where hardware encoding fails or falls back to software.
     * Default: false (disabled - restarting RTSP during memory pressure can cause a death spiral
     * on Samsung devices where rapid camera open/close triggers "Camera disabled by policy")
     */
    var restartRtspOnMemoryPressure: Boolean
        get() = prefs.getBoolean(KEY_RESTART_RTSP_ON_MEMORY_PRESSURE, false)
        set(value) { prefs.edit().putBoolean(KEY_RESTART_RTSP_ON_MEMORY_PRESSURE, value).commit() }

    /**
     * Auto reload on forced shutdown (OOM kill).
     * When enabled, the app will automatically relaunch after being killed by the system.
     * Requires SYSTEM_ALERT_WINDOW ("Display over other apps") permission.
     * Default: true (enabled)
     */
    var autoReloadOnCrash: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RELOAD_ON_CRASH, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_RELOAD_ON_CRASH, value).commit() }

    /**
     * Last WebView URL persisted for OOM recovery.
     * Saved on every successful page load so we can restore the user's tab after a crash.
     * Empty string = no saved URL (use default haUrl).
     */
    var lastWebViewUrl: String
        get() = prefs.getString(KEY_LAST_WEBVIEW_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LAST_WEBVIEW_URL, value).commit() }

    // ========== Enhanced Logging ==========

    /**
     * Enhanced logging enabled.
     * When enabled, additional diagnostic information is logged including
     * PSS values, memory thresholds, WebView version, and component status.
     * Default: false (disabled)
     */
    var enhancedLoggingEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENHANCED_LOGGING_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENHANCED_LOGGING_ENABLED, value).commit() }

    // ========== OOM Kill Detection ==========

    /**
     * Tracks if the app was running when it was terminated.
     * Set to true on startup, cleared on clean shutdown (onDestroy).
     * If true on next startup, indicates an unclean shutdown (OOM kill).
     */
    var appWasRunning: Boolean
        get() = prefs.getBoolean(KEY_APP_WAS_RUNNING, false)
        set(value) { prefs.edit().putBoolean(KEY_APP_WAS_RUNNING, value).commit() }

    /**
     * Timestamp when app started (for calculating uptime at OOM kill).
     */
    var appStartTime: Long
        get() = prefs.getLong(KEY_APP_START_TIME, 0)
        set(value) { prefs.edit().putLong(KEY_APP_START_TIME, value).commit() }

    /**
     * Last PSS memory reading in MB (for diagnosing OOM kills).
     * Updated periodically by MemoryMonitor.
     */
    var lastMemoryReadingMb: Int
        get() = prefs.getInt(KEY_LAST_MEMORY_READING_MB, 0)
        set(value) { prefs.edit().putInt(KEY_LAST_MEMORY_READING_MB, value).commit() }

    /**
     * Last RAM % reading (for diagnosing OOM kills).
     * WebView renderer memory may not show in PSS, so this catches those cases.
     * Updated periodically by MemoryMonitor.
     */
    var lastRamPercent: Int
        get() = prefs.getInt(KEY_LAST_RAM_PERCENT, 0)
        set(value) { prefs.edit().putInt(KEY_LAST_RAM_PERCENT, value).commit() }

    /**
     * Hourly PSS trend data for OOM crash reports.
     * Comma-separated PSS values (MB), one per hour: "24,450,680,720,740,750"
     * Persisted so it survives OOM kills.
     */
    var hourlyPssTrend: String
        get() = prefs.getString(KEY_HOURLY_PSS_TREND, "") ?: ""
        set(value) { prefs.edit().putString(KEY_HOURLY_PSS_TREND, value).commit() }

    /**
     * Hourly RAM % trend data for OOM crash reports.
     * Comma-separated RAM percentages, one per hour: "42,78,84,85,86,86"
     * Persisted so it survives OOM kills.
     */
    var hourlyRamTrend: String
        get() = prefs.getString(KEY_HOURLY_RAM_TREND, "") ?: ""
        set(value) { prefs.edit().putString(KEY_HOURLY_RAM_TREND, value).commit() }

    /**
     * Hourly heap % trend data for OOM crash reports.
     * Comma-separated heap usage percentages: "12,45,78,91,95,100"
     * Persisted so it survives OOM kills.
     */
    var hourlyHeapTrend: String
        get() = prefs.getString(KEY_HOURLY_HEAP_TREND, "") ?: ""
        set(value) { prefs.edit().putString(KEY_HOURLY_HEAP_TREND, value).commit() }

    /**
     * Last system memory snapshot from /proc/meminfo (persisted for OOM reports).
     * Updated every 30s by MemoryMonitor. Survives OOM kills.
     */
    var lastSystemMeminfo: String
        get() = prefs.getString(KEY_LAST_SYSTEM_MEMINFO, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LAST_SYSTEM_MEMINFO, value).commit() }

    /**
     * Last app memory breakdown from Debug.MemoryInfo (persisted for OOM reports).
     * Categories: JavaHeap, Native, Graphics, Code, Stack, Other, System, Swap.
     * Updated every 30s by MemoryMonitor. Survives OOM kills.
     */
    var lastAppMemoryBreakdown: String
        get() = prefs.getString(KEY_LAST_APP_MEMORY_BREAKDOWN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LAST_APP_MEMORY_BREAKDOWN, value).commit() }

    /**
     * Clear hourly trend data. Called on clean app startup to reset for new session.
     */
    fun clearHourlyTrend() {
        prefs.edit()
            .putString(KEY_HOURLY_PSS_TREND, "")
            .putString(KEY_HOURLY_RAM_TREND, "")
            .putString(KEY_HOURLY_HEAP_TREND, "")
            .commit()
    }

    /**
     * Tracks if we're in the middle of a WebView renderer recovery.
     * Set to true just before calling recreate() when renderer dies.
     * Cleared on startup after logging success. If true on startup,
     * it confirms the Activity successfully restarted after renderer termination.
     */
    var rendererRecoveryPending: Boolean
        get() = prefs.getBoolean(KEY_RENDERER_RECOVERY_PENDING, false)
        set(value) { prefs.edit().putBoolean(KEY_RENDERER_RECOVERY_PENDING, value).commit() }

    /**
     * Last known HA iframe URL, continuously updated as the user navigates
     * between dashboard tabs. Used by crash recovery to restore the correct page.
     */
    var lastHaIframeUrl: String?
        get() = prefs.getString(KEY_LAST_HA_IFRAME_URL, null)
        set(value) { prefs.edit().putString(KEY_LAST_HA_IFRAME_URL, value).commit() }

    /**
     * URL to restore after crash recovery. Set by handleWebViewCrashRecovery
     * from lastHaIframeUrl just before activity.recreate(). Consumed and cleared
     * by DashieWebViewClient when the shell page loads after restart.
     */
    var crashRestoreUrl: String?
        get() = prefs.getString(KEY_CRASH_RESTORE_URL, null)
        set(value) { prefs.edit().putString(KEY_CRASH_RESTORE_URL, value).commit() }

    // ========== Diagnostics Mode Settings ==========

    /**
     * Diagnostics mode enabled (master toggle).
     * When enabled, enables performance monitoring features.
     * Default: false
     */
    var diagnosticsModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_DIAGNOSTICS_MODE_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_DIAGNOSTICS_MODE_ENABLED, value).commit() }

    /**
     * Performance overlay enabled.
     * Shows a floating overlay with real-time CPU/RAM/Heap metrics and graphs.
     * Only visible when diagnosticsModeEnabled is also true.
     * Default: false
     */
    var performanceOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_PERFORMANCE_OVERLAY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_PERFORMANCE_OVERLAY_ENABLED, value).commit() }

    /**
     * Performance overlay position.
     * Controls which corner the floating overlay appears in.
     * Default: top-right
     */
    var performanceOverlayPosition: String
        get() = prefs.getString(KEY_PERFORMANCE_OVERLAY_POSITION, OVERLAY_POSITION_TOP_RIGHT) ?: OVERLAY_POSITION_TOP_RIGHT
        set(value) { prefs.edit().putString(KEY_PERFORMANCE_OVERLAY_POSITION, value).commit() }

    // ========== Other Performance Settings ==========

    /**
     * Return to home dashboard after inactivity (seconds).
     * 0 = disabled. When enabled, navigates back to the default HA dashboard URL
     * after the specified period of no user interaction.
     */
    var returnHomeTimeout: Int
        get() = prefs.getInt(KEY_RETURN_HOME_TIMEOUT, DEFAULT_RETURN_HOME_TIMEOUT)
        set(value) { prefs.edit().putInt(KEY_RETURN_HOME_TIMEOUT, value.coerceIn(0, 3600)).commit() }

    /**
     * Interaction priority mode (experimental).
     * When enabled, reduces background processing during user interactions.
     * Default: false
     */
    var interactionPriorityEnabled: Boolean
        get() = prefs.getBoolean(KEY_INTERACTION_PRIORITY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_INTERACTION_PRIORITY_ENABLED, value).commit() }

    // ========== Dashboard Telemetry ==========

    var dashboardTelemetryEnabled: Boolean
        get() = prefs.getBoolean(KEY_DASHBOARD_TELEMETRY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_DASHBOARD_TELEMETRY_ENABLED, value).commit() }

    var dashboardTelemetryConsent: Boolean
        get() = prefs.getBoolean(KEY_DASHBOARD_TELEMETRY_CONSENT, false)
        set(value) { prefs.edit().putBoolean(KEY_DASHBOARD_TELEMETRY_CONSENT, value).commit() }

    var logWebViewApiRequests: Boolean
        get() = prefs.getBoolean(KEY_LOG_WEBVIEW_API_REQUESTS, false)
        set(value) { prefs.edit().putBoolean(KEY_LOG_WEBVIEW_API_REQUESTS, value).commit() }

    // ========== App Version Tracking ==========

    /**
     * Check if app was updated and return true if caches should be cleared.
     * Call this on app start to detect version changes.
     */
    fun checkAndUpdateAppVersion(currentVersionCode: Int): Boolean {
        val lastVersion = prefs.getInt(KEY_LAST_APP_VERSION, 0)
        val isUpdate = lastVersion != 0 && lastVersion != currentVersionCode

        if (lastVersion != currentVersionCode) {
            prefs.edit().putInt(KEY_LAST_APP_VERSION, currentVersionCode).commit()
        }

        return isUpdate
    }
}

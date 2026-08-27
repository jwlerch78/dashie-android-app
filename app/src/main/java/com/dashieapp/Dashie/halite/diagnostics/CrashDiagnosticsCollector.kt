package com.dashieapp.Dashie.halite.diagnostics

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.PerformancePreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects diagnostic data for crash/OOM reports that was previously missing.
 *
 * Gathers:
 * - Battery optimization exempt status
 * - SYSTEM_ALERT_WINDOW permission status
 * - WiFi lock / foreground service / screen state (persisted to SharedPrefs by MemoryMonitor)
 * - Auto-reload and performance settings
 * - Samsung Device Care advisory
 * - Installed non-system packages (helps diagnose RAM competition)
 */
object CrashDiagnosticsCollector {
    private const val TAG = "CrashDiagnostics"

    // Process-exit diagnostics (API 30+ getHistoricalProcessExitReasons)
    private const val EXIT_REASON_LOG_TAG = "EXIT_REASON"
    private const val EXIT_REASON_MAX = 16
    private val EXIT_REASON_TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // Focus-stealer telemetry: identifies which app took foreground when MainActivity onPause fires.
    // Used to confirm Fire TV Ambient Experience / system overlays as the cause of overnight pauses.
    private const val FOCUS_STEALER_LOG_TAG = "FOCUS_STEALER"
    private const val KEY_LAST_PAUSE_START = "diag_last_pause_start"
    private val FOCUS_STEALER_TIMESTAMP_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Fire-TV (and similar) system packages that, when they take foreground, indicate the OS
     * pushed Dashie to background as part of an idle-management policy. If we see one of these
     * during a pause window, we surface a one-time prompt to the user explaining the cause.
     *
     * Add to this set as new culprits are confirmed in the field via FOCUS_STEALER logs.
     */
    val KNOWN_FIRETV_IDLE_KILLERS = setOf(
        "com.amazon.stillwatching.activity"   // Fire TV "Still Watching?" prompt — confirmed on AFTMM
    )

    // SharedPreferences keys for runtime state persisted by MemoryMonitor every 30s
    const val PREFS_NAME = "dashie_diagnostics_state"
    const val KEY_WIFI_LOCK_HELD = "diag_wifi_lock_held"
    const val KEY_SERVICE_BOUND = "diag_service_bound"
    const val KEY_SCREENSAVER_ACTIVE = "diag_screensaver_active"
    const val KEY_SCREENSAVER_MODE = "diag_screensaver_mode"
    const val KEY_SCREEN_INTERACTIVE = "diag_screen_interactive"

    // WebView refresh tracking keys
    private const val KEY_LAST_DAILY_REFRESH_TIME = "diag_last_daily_refresh_time"
    private const val KEY_LAST_STEALTH_REFRESH_TIME = "diag_last_stealth_refresh_time"
    private const val KEY_DAILY_REFRESH_COUNT = "diag_daily_refresh_count"
    private const val KEY_STEALTH_REFRESH_COUNT = "diag_stealth_refresh_count"
    private const val KEY_DAILY_SCHEDULER_STARTED = "diag_daily_scheduler_started"
    private const val KEY_DAILY_SCHEDULER_NEXT_ALARM = "diag_daily_scheduler_next_alarm"

    // Lifecycle audit (rolling buffer) + heartbeat + session tracking.
    // Written with commit() (not apply()) on critical paths so entries survive OOM kills.
    private const val KEY_LIFECYCLE_AUDIT = "diag_lifecycle_audit"
    private const val AUDIT_MAX_ENTRIES = 30
    private const val AUDIT_ENTRY_DELIMITER = "\u0001"  // Unit Separator — safe against any user content
    private const val AUDIT_FIELD_DELIMITER = "\u0002"

    // Monitor heartbeat: updated every 30s by MemoryMonitor. Tells us exactly when the
    // MemoryMonitor loop last successfully ran — i.e., the last moment we know the process
    // was healthy. (Compared to `currentTimeMillis` on restart, this reveals whether the
    // app died immediately after the last heartbeat or lived for an opaque stretch.)
    private const val KEY_MONITOR_LAST_HEARTBEAT = "diag_monitor_last_heartbeat"
    private const val KEY_MONITOR_LAST_PSS_MB = "diag_monitor_last_pss_mb"
    private const val KEY_MONITOR_LAST_RAM_PERCENT = "diag_monitor_last_ram_pct"

    // Session tracking: current session + previous session's start and last-heartbeat.
    // These are distinct from halitePrefs.appStartTime which gets overwritten on every
    // onCreate BEFORE the prior session's values can be read for the OOM report. We
    // snapshot the prior session's values here at the moment a new session starts so
    // the OOM report can compute real "alive time" = prev_last_heartbeat - prev_start.
    private const val KEY_CURRENT_SESSION_START = "diag_current_session_start"
    private const val KEY_PREVIOUS_SESSION_START = "diag_previous_session_start"
    private const val KEY_PREVIOUS_SESSION_LAST_HEARTBEAT = "diag_previous_session_last_heartbeat"

    /**
     * Persist runtime state that won't survive an OOM kill.
     * Called by MemoryMonitor every 30s so the last snapshot is available in crash reports.
     */
    fun persistRuntimeState(
        context: Context,
        wifiLockHeld: Boolean,
        serviceBound: Boolean,
        screensaverActive: Boolean,
        screensaverMode: String,
        screenInteractive: Boolean
    ) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_WIFI_LOCK_HELD, wifiLockHeld)
                .putBoolean(KEY_SERVICE_BOUND, serviceBound)
                .putBoolean(KEY_SCREENSAVER_ACTIVE, screensaverActive)
                .putString(KEY_SCREENSAVER_MODE, screensaverMode)
                .putBoolean(KEY_SCREEN_INTERACTIVE, screenInteractive)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist runtime state: ${e.message}")
        }
    }

    /**
     * Record that a WebView refresh occurred. Call from ScreenRefreshScheduler.
     * @param type "daily" or "stealth"
     */
    fun persistRefreshEvent(context: Context, type: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            when (type) {
                "daily" -> prefs.edit()
                    .putLong(KEY_LAST_DAILY_REFRESH_TIME, now)
                    .putInt(KEY_DAILY_REFRESH_COUNT, prefs.getInt(KEY_DAILY_REFRESH_COUNT, 0) + 1)
                    .commit()
                "stealth" -> prefs.edit()
                    .putLong(KEY_LAST_STEALTH_REFRESH_TIME, now)
                    .putInt(KEY_STEALTH_REFRESH_COUNT, prefs.getInt(KEY_STEALTH_REFRESH_COUNT, 0) + 1)
                    .commit()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist refresh event: ${e.message}")
        }
    }

    /**
     * Record that the daily refresh scheduler was started and its next alarm time.
     */
    fun persistSchedulerState(context: Context, started: Boolean, nextAlarmMillis: Long = 0) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DAILY_SCHEDULER_STARTED, started)
                .putLong(KEY_DAILY_SCHEDULER_NEXT_ALARM, nextAlarmMillis)
                .commit()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist scheduler state: ${e.message}")
        }
    }

    // ==================== Lifecycle audit / heartbeat / session tracking ====================

    /**
     * Append an event to the rolling lifecycle audit buffer (last 30 entries).
     *
     * Uses commit() (synchronous) because these entries exist specifically to survive
     * crashes — if apply() is still pending on the writer thread when the kernel kills
     * us, the entry is lost.
     *
     * @param event Short event name (e.g., "onCreate", "alarm_fired", "refresh_started")
     * @param details Optional free-form detail string
     */
    /**
     * Record the moment MainActivity entered onPause so the next onResume can query
     * UsageStatsManager for what stole focus during the pause window.
     */
    fun recordPauseStart(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_PAUSE_START, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record pause start: ${e.message}")
        }
    }

    /**
     * On the next onResume after a pause, query UsageStatsManager for any package that moved to
     * foreground during our pause window. Logs results to PersistentLog under tag FOCUS_STEALER.
     *
     * Requires PACKAGE_USAGE_STATS permission, which the user must grant manually:
     *   Settings > Apps > Special access > Usage data access
     *   or via ADB: adb shell appops set <pkg> GET_USAGE_STATS allow
     *
     * No-op (with one diagnostic log line) if the permission is not granted.
     *
     * @return the package name of a known Fire TV idle-killer (see [KNOWN_FIRETV_IDLE_KILLERS])
     *   that took foreground during the pause window, or null if none of those were the cause.
     *   Caller can use this to surface a user-facing prompt explaining the platform behavior.
     */
    fun logFocusStealersForPriorPause(context: Context): String? {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pauseStart = prefs.getLong(KEY_LAST_PAUSE_START, 0L)
            // Clear so we only attribute this pause window once.
            prefs.edit().remove(KEY_LAST_PAUSE_START).apply()
            if (pauseStart <= 0L) return null  // No prior pause recorded (first resume after launch).

            val now = System.currentTimeMillis()
            val pauseDurationSec = (now - pauseStart) / 1000

            if (!hasUsageStatsPermission(context)) {
                PersistentLog.info(
                    FOCUS_STEALER_LOG_TAG,
                    "Pause window=${pauseDurationSec}s — usage-stats permission not granted, " +
                        "cannot identify focus-stealer. Grant via: " +
                        "adb shell appops set ${context.packageName} GET_USAGE_STATS allow"
                )
                return null
            }

            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm == null) {
                PersistentLog.warn(FOCUS_STEALER_LOG_TAG, "UsageStatsManager unavailable")
                return null
            }

            // Widen by 2s on each side to catch the events that bracket our onPause/onResume.
            val events = usm.queryEvents(pauseStart - 2_000L, now + 2_000L)
            val ourPkg = context.packageName
            val foregrounds = mutableListOf<String>()
            var detectedKiller: String? = null
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val isForegroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                if (!isForegroundEvent) continue
                if (event.packageName == ourPkg) continue
                val ts = FOCUS_STEALER_TIMESTAMP_FORMAT.format(Date(event.timeStamp))
                val activity = event.className?.substringAfterLast('.') ?: "?"
                foregrounds.add("$ts ${event.packageName}/$activity")
                if (detectedKiller == null && event.packageName in KNOWN_FIRETV_IDLE_KILLERS) {
                    detectedKiller = event.packageName
                }
            }

            if (foregrounds.isEmpty()) {
                PersistentLog.info(
                    FOCUS_STEALER_LOG_TAG,
                    "Pause window=${pauseDurationSec}s — no foreground events from other packages " +
                        "(focus-stealer may be a system overlay that doesn't show in usage events)"
                )
            } else {
                PersistentLog.info(
                    FOCUS_STEALER_LOG_TAG,
                    "Pause window=${pauseDurationSec}s — ${foregrounds.size} foreground event(s) during pause:"
                )
                foregrounds.take(20).forEach { PersistentLog.info(FOCUS_STEALER_LOG_TAG, "  $it") }
                if (detectedKiller != null) {
                    PersistentLog.info(
                        FOCUS_STEALER_LOG_TAG,
                        "Detected known Fire TV idle-killer: $detectedKiller"
                    )
                }
            }
            return detectedKiller
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log focus stealers: ${e.message}")
            PersistentLog.warn(FOCUS_STEALER_LOG_TAG, "error: ${e.message}")
            return null
        }
    }

    private fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    fun persistLifecycleEvent(context: Context, event: String, details: String = "") {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val sessionStart = prefs.getLong(KEY_CURRENT_SESSION_START, 0L)
            val safeEvent = event.replace(AUDIT_ENTRY_DELIMITER, " ").replace(AUDIT_FIELD_DELIMITER, " ")
            val safeDetails = details.replace(AUDIT_ENTRY_DELIMITER, " ").replace(AUDIT_FIELD_DELIMITER, " ")
            val newEntry = listOf(now.toString(), sessionStart.toString(), safeEvent, safeDetails)
                .joinToString(AUDIT_FIELD_DELIMITER)

            val existing = prefs.getString(KEY_LIFECYCLE_AUDIT, "") ?: ""
            val entries = if (existing.isEmpty()) mutableListOf() else existing.split(AUDIT_ENTRY_DELIMITER).toMutableList()
            entries.add(newEntry)
            while (entries.size > AUDIT_MAX_ENTRIES) entries.removeAt(0)

            prefs.edit()
                .putString(KEY_LIFECYCLE_AUDIT, entries.joinToString(AUDIT_ENTRY_DELIMITER))
                .commit()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist lifecycle event: ${e.message}")
        }
    }

    /**
     * Update the MemoryMonitor heartbeat timestamp. Call from MemoryMonitor on each snapshot
     * so crash reports can show exactly when the monitor loop last ran — the unambiguous
     * signal of when the process was last known healthy.
     */
    fun persistMonitorHeartbeat(context: Context, pssMb: Int, ramPercent: Int) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_MONITOR_LAST_HEARTBEAT, System.currentTimeMillis())
                .putInt(KEY_MONITOR_LAST_PSS_MB, pssMb)
                .putInt(KEY_MONITOR_LAST_RAM_PERCENT, ramPercent)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist monitor heartbeat: ${e.message}")
        }
    }

    /**
     * Rotate session tracking at the start of a new process: snapshot the prior session's
     * start-time and last-heartbeat so the OOM report can compute actual alive-time
     * (prev_last_heartbeat − prev_start) separately from wall-clock-elapsed-since-start.
     */
    fun markNewSessionStarted(context: Context, newSessionStart: Long) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val prevSessionStart = prefs.getLong(KEY_CURRENT_SESSION_START, 0L)
            val prevLastHeartbeat = prefs.getLong(KEY_MONITOR_LAST_HEARTBEAT, 0L)
            prefs.edit()
                .putLong(KEY_PREVIOUS_SESSION_START, prevSessionStart)
                .putLong(KEY_PREVIOUS_SESSION_LAST_HEARTBEAT, prevLastHeartbeat)
                .putLong(KEY_CURRENT_SESSION_START, newSessionStart)
                .commit()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to rotate session tracking: ${e.message}")
        }
    }

    /**
     * Retrieve the previous session's start time and last heartbeat for the OOM report.
     * Returns (prevStart, prevLastHeartbeat); either may be 0 if unknown.
     */
    fun getPreviousSessionTimings(context: Context): Pair<Long, Long> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            Pair(
                prefs.getLong(KEY_PREVIOUS_SESSION_START, 0L),
                prefs.getLong(KEY_PREVIOUS_SESSION_LAST_HEARTBEAT, 0L)
            )
        } catch (e: Exception) {
            Pair(0L, 0L)
        }
    }

    /**
     * Format the lifecycle audit and session timings for inclusion in crash reports.
     */
    fun collectLifecycleAuditAndSessions(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("─── SESSION TIMING ───")
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)

            val currSession = prefs.getLong(KEY_CURRENT_SESSION_START, 0L)
            val prevSession = prefs.getLong(KEY_PREVIOUS_SESSION_START, 0L)
            val prevLastHB = prefs.getLong(KEY_PREVIOUS_SESSION_LAST_HEARTBEAT, 0L)
            val currLastHB = prefs.getLong(KEY_MONITOR_LAST_HEARTBEAT, 0L)
            val currPss = prefs.getInt(KEY_MONITOR_LAST_PSS_MB, 0)
            val currRam = prefs.getInt(KEY_MONITOR_LAST_RAM_PERCENT, 0)

            if (prevSession > 0) {
                sb.appendLine("Previous session start: ${dateFormat.format(java.util.Date(prevSession))}")
                if (prevLastHB > 0) {
                    val aliveMin = (prevLastHB - prevSession) / 60_000
                    sb.appendLine("Previous last heartbeat: ${dateFormat.format(java.util.Date(prevLastHB))}")
                    sb.appendLine("Previous session alive for: ${aliveMin}min (${aliveMin / 60}h${aliveMin % 60}m)")
                    if (currSession > 0) {
                        val deadMin = (currSession - prevLastHB) / 60_000
                        sb.appendLine("Gap between prev last heartbeat and this start: ${deadMin}min (${deadMin / 60}h${deadMin % 60}m)")
                    }
                } else {
                    sb.appendLine("Previous last heartbeat: (never recorded)")
                }
            } else {
                sb.appendLine("Previous session: (no prior session tracked)")
            }

            if (currSession > 0) {
                sb.appendLine("Current session start: ${dateFormat.format(java.util.Date(currSession))}")
            }
            if (currLastHB > 0) {
                val agoMin = (System.currentTimeMillis() - currLastHB) / 60_000
                sb.appendLine("Current last heartbeat: ${dateFormat.format(java.util.Date(currLastHB))} (${agoMin}min ago) PSS=${currPss}MB RAM=${currRam}%")
            }
        } catch (e: Exception) {
            sb.appendLine("(error reading session state: ${e.message})")
        }
        sb.appendLine()

        sb.appendLine("─── LIFECYCLE AUDIT (last ${AUDIT_MAX_ENTRIES} events) ───")
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_LIFECYCLE_AUDIT, "") ?: ""
            if (raw.isEmpty()) {
                sb.appendLine("(no events recorded)")
            } else {
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                val sessionLabels = mutableMapOf<Long, String>()
                var nextLabel = 'A'
                for (entry in raw.split(AUDIT_ENTRY_DELIMITER)) {
                    val fields = entry.split(AUDIT_FIELD_DELIMITER)
                    if (fields.size < 4) continue
                    val ts = fields[0].toLongOrNull() ?: continue
                    val sessionStart = fields[1].toLongOrNull() ?: 0L
                    val event = fields[2]
                    val details = fields[3]
                    val sessionLabel = sessionLabels.getOrPut(sessionStart) {
                        val label = "sess ${nextLabel}"
                        nextLabel = (nextLabel + 1)
                        label
                    }
                    val detailsStr = if (details.isBlank()) "" else " ($details)"
                    sb.appendLine("${dateFormat.format(java.util.Date(ts))} [$sessionLabel] $event$detailsStr")
                }
            }
        } catch (e: Exception) {
            sb.appendLine("(error reading lifecycle audit: ${e.message})")
        }

        return sb.toString()
    }

    /**
     * Collect WebView refresh state for crash/OOM reports.
     */
    fun collectRefreshState(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("─── WEBVIEW REFRESH STATE ───")

        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)

            // Daily refresh scheduler
            val schedulerStarted = prefs.getBoolean(KEY_DAILY_SCHEDULER_STARTED, false)
            sb.appendLine("Daily Scheduler Started: ${if (schedulerStarted) "YES ✓" else "NO ✗"}")

            val nextAlarm = prefs.getLong(KEY_DAILY_SCHEDULER_NEXT_ALARM, 0)
            if (nextAlarm > 0) {
                val isInPast = nextAlarm < System.currentTimeMillis()
                sb.appendLine("Next Scheduled Alarm: ${dateFormat.format(java.util.Date(nextAlarm))}${if (isInPast) " (MISSED - in the past)" else ""}")
            }

            // Daily refresh config
            val perfPrefs = PerformancePreferences(context)
            val dailyHours = perfPrefs.dailyRefreshHours
            sb.appendLine("Daily Refresh Hours: ${if (dailyHours.isEmpty()) "NONE (disabled)" else dailyHours.sorted().joinToString(", ") { "${it}:00" }}")

            // Last daily refresh
            val lastDaily = prefs.getLong(KEY_LAST_DAILY_REFRESH_TIME, 0)
            val dailyCount = prefs.getInt(KEY_DAILY_REFRESH_COUNT, 0)
            if (lastDaily > 0) {
                val agoMin = (System.currentTimeMillis() - lastDaily) / 60_000
                sb.appendLine("Last Daily Refresh: ${dateFormat.format(java.util.Date(lastDaily))} (${agoMin}min ago, total=$dailyCount)")
            } else {
                sb.appendLine("Last Daily Refresh: NEVER (count=$dailyCount)")
            }

            // Last stealth refresh
            val lastStealth = prefs.getLong(KEY_LAST_STEALTH_REFRESH_TIME, 0)
            val stealthCount = prefs.getInt(KEY_STEALTH_REFRESH_COUNT, 0)
            if (lastStealth > 0) {
                val agoMin = (System.currentTimeMillis() - lastStealth) / 60_000
                sb.appendLine("Last Stealth Refresh: ${dateFormat.format(java.util.Date(lastStealth))} (${agoMin}min ago, total=$stealthCount)")
            } else {
                sb.appendLine("Last Stealth Refresh: NEVER (count=$stealthCount)")
            }
        } catch (e: Exception) {
            sb.appendLine("(error reading refresh state: ${e.message})")
        }

        return sb.toString()
    }

    /**
     * Collect system-level state: battery optimization, overlay permission, Samsung detection.
     * These are system settings that persist across app restarts, so query them directly.
     */
    fun collectSystemState(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("─── SYSTEM PERMISSIONS & STATE ───")

        // Battery optimization exempt status
        try {
            val exempt = com.dashieapp.Dashie.halite.BatteryOptimizationHelper.isExempt(context)
            sb.appendLine("Battery Optimization Exempt: ${if (exempt) "YES ✓" else "NO ✗ (app may be killed aggressively)"}")
        } catch (e: Exception) {
            sb.appendLine("Battery Optimization Exempt: (error: ${e.message})")
        }

        // SYSTEM_ALERT_WINDOW permission (needed for OOM auto-relaunch)
        try {
            val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(context)
            } else {
                true
            }
            sb.appendLine("Overlay Permission (SYSTEM_ALERT_WINDOW): ${if (canOverlay) "YES ✓" else "NO ✗ (OOM auto-relaunch won't work)"}")
        } catch (e: Exception) {
            sb.appendLine("Overlay Permission: (error: ${e.message})")
        }

        // Device Owner / Device Admin — the strongest keep-alive levers on aggressive
        // OEMs. Device Owner additionally unlocks setUserControlDisabledPackages
        // (force-stop / standby exemption) and the SYSTEM_EXEMPTED camera FGS.
        try {
            val isDO = com.dashieapp.Dashie.halite.DeviceAdminHelper.isDeviceOwnerApp(context)
            val isAdmin = com.dashieapp.Dashie.halite.DeviceAdminHelper.isActive(context)
            sb.appendLine("Device Owner: ${if (isDO) "YES ✓" else "NO (weaker keep-alive on aggressive OEMs)"}")
            sb.appendLine("Device Admin: ${if (isAdmin) "YES ✓" else "NO ✗ (no hardware screen-off)"}")
        } catch (e: Exception) {
            sb.appendLine("Device Owner/Admin: (error: ${e.message})")
        }

        // Exact alarm permission (precise stealth-reload / timer scheduling)
        try {
            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager)
                    ?.canScheduleExactAlarms() == true
            } else true
            sb.appendLine("Exact Alarm Permission: ${if (canExact) "YES ✓" else "NO ✗"}")
        } catch (e: Exception) {
            sb.appendLine("Exact Alarm Permission: (error: ${e.message})")
        }

        // WRITE_SETTINGS (brightness control)
        try {
            val canWrite = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.provider.Settings.System.canWrite(context)
            } else true
            sb.appendLine("Write Settings (brightness): ${if (canWrite) "YES ✓" else "NO ✗"}")
        } catch (e: Exception) {
            sb.appendLine("Write Settings: (error: ${e.message})")
        }

        // Camera runtime permission (required for motion/face detection + RTSP)
        try {
            val cam = context.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            sb.appendLine("Camera Permission: ${if (cam) "YES ✓" else "NO ✗ (motion/face detection + RTSP disabled)"}")
        } catch (e: Exception) {
            sb.appendLine("Camera Permission: (error: ${e.message})")
        }

        // Notification permission (API 33+) — FGS notification visibility
        try {
            val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
            sb.appendLine("Notification Permission: ${if (notif) "YES ✓" else "NO ✗"}")
        } catch (e: Exception) {
            sb.appendLine("Notification Permission: (error: ${e.message})")
        }

        // Manufacturer (Samsung, Xiaomi, and Huawei all have notably aggressive
        // background-app management that ignores standard Android keep-alive APIs)
        val mfr = Build.MANUFACTURER.lowercase()
        val isSamsung = mfr == "samsung"
        val isXiaomi = mfr == "xiaomi" || mfr == "redmi"
        val isHuawei = mfr == "huawei" || mfr == "honor"
        val flag = when {
            isSamsung -> " (Samsung — aggressive LMK)"
            isXiaomi -> " (Xiaomi/MIUI — Autostart + Background app limits must be whitelisted manually; WifiLock often ignored)"
            isHuawei -> " (Huawei/EMUI — protected apps list must be whitelisted manually)"
            else -> ""
        }
        sb.appendLine("Manufacturer: ${Build.MANUFACTURER}$flag")

        // MIUI/HyperOS version (only present on Xiaomi devices)
        if (isXiaomi) {
            try {
                val miuiVersion = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java)
                    .invoke(null, "ro.miui.ui.version.name") as? String ?: ""
                if (miuiVersion.isNotEmpty()) {
                    sb.appendLine("MIUI/HyperOS Version: $miuiVersion")
                }
            } catch (_: Exception) { /* ignore */ }
        }

        return sb.toString()
    }

    /**
     * Collect runtime state from SharedPreferences (persisted by MemoryMonitor every 30s).
     * This is the last known state before the OOM kill.
     */
    fun collectRuntimeState(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("─── RUNTIME STATE (at time of kill) ───")

        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val wifiLock = prefs.getBoolean(KEY_WIFI_LOCK_HELD, false)
            val serviceBound = prefs.getBoolean(KEY_SERVICE_BOUND, false)
            val screensaverActive = prefs.getBoolean(KEY_SCREENSAVER_ACTIVE, false)
            val screensaverMode = prefs.getString(KEY_SCREENSAVER_MODE, "unknown") ?: "unknown"
            val screenInteractive = prefs.getBoolean(KEY_SCREEN_INTERACTIVE, true)

            sb.appendLine("WiFi Lock Held: ${if (wifiLock) "YES ✓" else "NO ✗"}")
            sb.appendLine("Foreground Service Bound: ${if (serviceBound) "YES ✓" else "NO ✗ (OOM auto-restart may not work)"}")
            sb.appendLine("Screen Interactive: ${if (screenInteractive) "YES (screen on)" else "NO (screen off)"}")
            sb.appendLine("Screensaver: ${if (screensaverActive) "ACTIVE (mode=$screensaverMode)" else "inactive"}")
        } catch (e: Exception) {
            sb.appendLine("(error reading runtime state: ${e.message})")
        }

        return sb.toString()
    }

    /**
     * Collect relevant app settings for crash diagnosis.
     */
    fun collectAppSettings(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("─── APP SETTINGS (crash-relevant) ───")

        try {
            val perfPrefs = PerformancePreferences(context)
            sb.appendLine("Emergency Recovery Enabled: ${if (perfPrefs.emergencyRecoveryEnabled) "YES ✓" else "NO ✗ (thresholds won't trigger reload)"}")
            sb.appendLine("Auto Reload on Crash (OOM): ${if (perfPrefs.autoReloadOnCrash) "YES ✓" else "NO ✗"}")
            sb.appendLine("WiFi Lock Setting: ${if (perfPrefs.wifiLockEnabled) "enabled" else "disabled"}")
            sb.appendLine("Auto Reload Stale: ${perfPrefs.autoReloadStaleMinutes}min (0=disabled)")

            // Threshold settings
            val idleRam = perfPrefs.idleRamPercent
            val criticalRam = perfPrefs.criticalRamPercent
            val idleHeap = perfPrefs.idleHeapPercent
            val criticalHeap = perfPrefs.criticalHeapPercent
            sb.appendLine("Idle Thresholds: RAM=${idleRam}%, Heap=${idleHeap}%")
            sb.appendLine("Critical Thresholds: RAM=${criticalRam}%, Heap=${criticalHeap}%")

            // Stealth/proactive refresh
            val stealthEnabled = perfPrefs.stealthRefreshEnabled
            val stealthInterval = perfPrefs.stealthRefreshIntervalMinutes
            val proactiveHours = perfPrefs.proactiveRefreshHours
            sb.appendLine("Stealth Refresh: ${if (stealthEnabled) "enabled, interval=${stealthInterval}min" else "disabled"}")
            sb.appendLine("Proactive Refresh: ${if (proactiveHours > 0) "every ${proactiveHours}h" else "disabled"}")

            // RTSP settings (major memory contributor)
            val rtspEnabled = perfPrefs.restartRtspOnMemoryPressure
            sb.appendLine("Restart RTSP on Memory Pressure: ${if (rtspEnabled) "YES" else "NO"}")

            // Fully Kiosk API — when enabled, runs DashieApiService as a foreground
            // service which provides much stronger keep-alive behavior on aggressive OEMs.
            // When disabled, no foreground service runs and the app is more vulnerable
            // to being killed by MIUI / Fire OS / EMUI background management.
            try {
                val halitePrefs = HalitePreferences(context)
                val apiEnabled = halitePrefs.connection.apiEnabled
                sb.appendLine("Fully Kiosk API Enabled: ${if (apiEnabled) "YES ✓ (foreground service running)" else "NO ✗ (no foreground service — app vulnerable to OEM kill)"}")
            } catch (e: Exception) {
                sb.appendLine("Fully Kiosk API Enabled: (error: ${e.message})")
            }
        } catch (e: Exception) {
            sb.appendLine("(error reading settings: ${e.message})")
        }

        return sb.toString()
    }

    /**
     * Collect installed apps that may compete for RAM.
     * Shows all user-installed apps plus system bloatware that runs background services.
     * Filters out core Android/Google framework packages that can't be removed.
     */
    fun collectInstalledApps(context: Context): String {
        val sb = StringBuilder()

        try {
            val pm = context.packageManager
            val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            // User-installed apps (non-system)
            val userApps = allApps
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { it.packageName }
                .sorted()

            sb.appendLine("─── USER-INSTALLED APPS (${userApps.size}) ───")
            for (app in userApps) {
                sb.appendLine("  $app")
            }

            // System bloatware: show system apps that are known RAM consumers
            // or that the user could potentially disable to free resources.
            // Skip core Android framework, Google Play Services, etc.
            val systemApps = allApps
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
                .map { it.packageName }

            val bloatware = systemApps.filter { pkg ->
                isBloatwarePackage(pkg)
            }.sorted()

            if (bloatware.isNotEmpty()) {
                sb.appendLine("─── SYSTEM BLOATWARE (${bloatware.size}, may be disableable) ───")
                for (app in bloatware) {
                    sb.appendLine("  $app")
                }
            }

            sb.appendLine("Total system apps: ${systemApps.size}")
        } catch (e: Exception) {
            sb.appendLine("─── INSTALLED APPS ───")
            sb.appendLine("(error listing apps: ${e.message})")
        }

        return sb.toString()
    }

    /**
     * Returns true if a system package is likely bloatware that runs background services,
     * competes for RAM, and can potentially be disabled by the user.
     * Only includes actual apps — not framework components, overlays, or configs.
     */
    private fun isBloatwarePackage(pkg: String): Boolean {
        // Known Samsung apps with background services that can be disabled
        val samsungBloat = setOf(
            "com.samsung.android.game.gos",             // Game Optimizer (RAM hog)
            "com.samsung.android.game.gamehome",         // Game Launcher
            "com.samsung.android.app.spage",             // Samsung Free / Bixby feed
            "com.samsung.android.app.settings.bixby",    // Bixby settings
            "com.samsung.android.visionintelligence",    // Bixby Vision
            "com.samsung.android.bixby.agent",           // Bixby Voice
            "com.samsung.android.bixby.service",         // Bixby service
            "com.samsung.android.intellivoiceservice",   // Samsung voice assistant
            "com.samsung.android.scloud",                // Samsung Cloud
            "com.samsung.android.mobileservice",         // Samsung Experience Service
            "com.samsung.android.rubin.app",             // Samsung AI / context service
            "com.samsung.android.forest",                // Samsung Health plant
            "com.samsung.android.app.dressroom",         // AR Emoji / dressroom
            "com.samsung.android.stickercenter",         // Sticker Center
            "com.samsung.android.themecenter",            // Theme Center
            "com.samsung.android.smartswitchassistant",  // Smart Switch
            "com.samsung.android.video",                 // Samsung Video
            "com.samsung.android.app.sharelive",         // Live sharing
            "com.samsung.android.smartmirroring",        // Smart View / screen mirror
            "com.samsung.android.galaxycontinuity",      // Samsung Flow
            "com.samsung.android.app.parentalcare",      // Parental controls
            "com.samsung.android.app.routines",          // Bixby Routines
            "com.samsung.android.aware.service",         // Context awareness
            "com.samsung.android.calendar",              // Samsung Calendar
            "com.samsung.android.app.contacts",          // Samsung Contacts
            "com.samsung.android.dialer",                // Samsung Phone
            "com.samsung.android.fmm",                   // Find My Mobile
            "com.samsung.android.lool",                  // Samsung Members
            "com.samsung.android.kidsinstaller",         // Kids Mode
            "com.samsung.android.app.updatecenter",      // Galaxy Store updates
            "com.samsung.SMT",                           // Samsung TTS
            "com.sec.android.app.samsungapps",           // Galaxy Store
            "com.sec.android.app.sbrowser",              // Samsung Internet
            "com.sec.android.gallery3d",                 // Samsung Gallery
            "com.sec.android.daemonapp",                 // Weather widget daemon
            "com.sec.android.easyMover",                 // Smart Switch
            "com.sec.android.easyMover.Agent"            // Smart Switch agent
        )
        if (pkg in samsungBloat) return true

        // Social media / streaming / productivity preloads
        val bloatPrefixes = listOf(
            "com.facebook.", "com.instagram.", "com.twitter.", "com.tiktok.",
            "com.netflix.", "com.spotify.", "com.amazon.", "com.disney.",
            "com.microsoft.", "com.linkedin.", "com.snapchat.",
            "com.peacock.", "com.hulu.", "com.pandora.",
            "flipboard.", "com.booking.", "com.tripadvisor."
        )
        if (bloatPrefixes.any { pkg.startsWith(it) }) return true

        // Carrier bloatware
        val carrierPrefixes = listOf(
            "com.att.", "com.verizon.", "com.tmobile.", "com.sprint.",
            "com.asurion.", "com.synchronoss."
        )
        if (carrierPrefixes.any { pkg.startsWith(it) }) return true

        return false
    }

    /**
     * Collect all diagnostic sections in one call.
     * Returns a combined string for insertion into crash/OOM reports.
     */
    fun collectAll(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine(collectSystemState(context))
        sb.appendLine()
        sb.appendLine(collectRuntimeState(context))
        sb.appendLine()
        sb.appendLine(collectRefreshState(context))
        sb.appendLine()
        sb.appendLine(collectLifecycleAuditAndSessions(context))
        sb.appendLine()
        sb.appendLine(collectAppSettings(context))
        sb.appendLine()
        sb.appendLine(collectProcessExitReasons(context))
        sb.appendLine()
        sb.appendLine(collectInstalledApps(context))
        return sb.toString()
    }

    /**
     * Write a one-shot permission/setup snapshot to the PersistentLog at startup.
     * Guarantees the durable log — and therefore EVERY report (crash or manual
     * diagnostics) — contains a complete picture of keep-alive-relevant state
     * even when no crash occurs and collectAll() is never invoked. RTSP-enabled
     * and motion-wake mode aren't repeated here because they're already logged
     * durably by the FGS / MOTION tags.
     */
    fun logSetupSnapshotToPersistentLog(context: Context) {
        try {
            val isDO = com.dashieapp.Dashie.halite.DeviceAdminHelper.isDeviceOwnerApp(context)
            val isAdmin = com.dashieapp.Dashie.halite.DeviceAdminHelper.isActive(context)
            val battExempt = com.dashieapp.Dashie.halite.BatteryOptimizationHelper.isExempt(context)
            val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                android.provider.Settings.canDrawOverlays(context) else true
            val cam = context.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val exactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                (context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager)
                    ?.canScheduleExactAlarms() == true else true
            val writeSettings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                android.provider.Settings.System.canWrite(context) else true
            val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED else true
            val apiEnabled = try { HalitePreferences(context).connection.apiEnabled } catch (_: Exception) { false }
            PersistentLog.info(
                "SETUP",
                "snapshot — deviceOwner=$isDO deviceAdmin=$isAdmin batteryExempt=$battExempt " +
                    "overlay=$overlay camPerm=$cam exactAlarm=$exactAlarm writeSettings=$writeSettings " +
                    "notifPerm=$notif dashieApi=$apiEnabled mfr=${Build.MANUFACTURER}"
            )
        } catch (e: Exception) {
            PersistentLog.warn("SETUP", "snapshot failed: ${e.message}")
        }
    }

    /**
     * Query Android's historical process-exit records (API 30+) and return them as a
     * newline-delimited block for inclusion in crash reports. This is the only diagnostic
     * that reveals *why* the OS killed our process when the kill was uncooperative
     * (no onPause/onDestroy/crash) — essential on Fire TV / aggressive OEM ROMs.
     */
    fun collectProcessExitReasons(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== Process Exit Reasons ===")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            sb.appendLine("API < 30, not available")
            return sb.toString()
        }
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am == null) {
                sb.appendLine("ActivityManager unavailable")
                return sb.toString()
            }
            val exits = am.getHistoricalProcessExitReasons(null, 0, EXIT_REASON_MAX)
            if (exits.isEmpty()) {
                sb.appendLine("No historical exits recorded")
            } else {
                exits.forEachIndexed { index, info ->
                    sb.appendLine(formatExitInfo(index, info))
                }
            }
        } catch (e: Exception) {
            sb.appendLine("error: ${e.message}")
        }
        return sb.toString()
    }

    /**
     * Log historical process exits to PersistentLog on startup. Writes one entry per exit
     * under tag "EXIT_REASON" so overnight kill events are individually greppable later.
     * Safe to call unconditionally — no-op on API < 30, never throws.
     */
    fun logProcessExitReasonsToPersistentLog(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            PersistentLog.info(EXIT_REASON_LOG_TAG, "API < 30, process exit reasons unavailable")
            return
        }
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am == null) {
                PersistentLog.warn(EXIT_REASON_LOG_TAG, "ActivityManager unavailable")
                return
            }
            val exits = am.getHistoricalProcessExitReasons(null, 0, EXIT_REASON_MAX)
            if (exits.isEmpty()) {
                PersistentLog.info(EXIT_REASON_LOG_TAG, "No historical exits recorded (first run or system purged)")
                return
            }
            PersistentLog.info(EXIT_REASON_LOG_TAG, "═══ Historical process exits (${exits.size}) ═══")
            exits.forEachIndexed { index, info ->
                PersistentLog.info(EXIT_REASON_LOG_TAG, formatExitInfo(index, info))
            }
        } catch (e: Exception) {
            // Diagnostic path must never break startup.
            Log.w(TAG, "Failed to read exit reasons: ${e.message}")
            PersistentLog.warn(EXIT_REASON_LOG_TAG, "Failed to read exit reasons: ${e.message}")
        }
    }

    private fun formatExitInfo(index: Int, info: ApplicationExitInfo): String {
        val reason = exitReasonName(info.reason)
        val importance = exitImportanceName(info.importance)
        val ts = EXIT_REASON_TIMESTAMP_FORMAT.format(Date(info.timestamp))
        val pssMb = info.pss / 1024
        val rssMb = info.rss / 1024
        val description = info.description?.take(200) ?: ""
        return "[$index] $ts reason=$reason importance=$importance pss=${pssMb}MB rss=${rssMb}MB status=${info.status} desc=\"$description\""
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        13 -> "FREEZER"
        14 -> "PACKAGE_STATE_CHANGE"
        15 -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }

    private fun exitImportanceName(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND(100)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FG_SERVICE(125)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "TOP_SLEEPING(150)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE(200)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE(230)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE(300)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED(400)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE(1000)"
        else -> "IMPORTANCE_$importance"
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}

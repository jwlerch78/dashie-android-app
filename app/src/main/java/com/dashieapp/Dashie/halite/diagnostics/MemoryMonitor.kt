package com.dashieapp.Dashie.halite.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Periodic memory monitor for diagnosing OOM crashes.
 *
 * Logs memory stats every 30 seconds (debug mode) to help identify:
 * - Gradual memory leaks (memory slowly growing)
 * - Memory state before OOM crashes
 * - Correlation with specific activities (HA disconnect, screensaver, etc.)
 * - Component-specific memory usage (RTSP cache, photo cache, etc.)
 *
 * Tracks TOTAL app memory = main process PSS + WebView renderer PSS.
 * The WebView renderer runs in a separate sandboxed process, so we find it
 * and sum both processes to get accurate memory usage for threshold detection.
 *
 * Also proactively triggers memory recovery actions when total PSS
 * exceeds thresholds.
 *
 * Usage:
 * ```
 * MemoryMonitor.init(context)  // In onCreate (for PSS monitoring)
 * MemoryMonitor.start()  // In onCreate
 * MemoryMonitor.stop()   // In onDestroy
 * ```
 */
object MemoryMonitor {
    private const val TAG = "MemoryMonitor"
    private const val INTERVAL_MS = 30 * 1000L  // 30 seconds (debugging mode)
    private const val SNAPSHOT_INTERVAL_MS = 15 * 60 * 1000L  // 15 minutes (was 60min, reduced to capture spikes)

    // ==================== RAM % Thresholds (Primary) ====================
    // RAM % is more reliable than PSS across devices because it doesn't depend
    // on finding the WebView renderer process (which varies by device/Android version).
    //
    // These are system-wide RAM usage percentages:
    // - 85% = Idle threshold (reload during screensaver)
    // - 95% = Critical threshold (immediate reload)
    // - 75% = Recovery threshold (reset flags when RAM drops below this)
    private const val RAM_IDLE_THRESHOLD_PERCENT = 85
    private const val RAM_CRITICAL_THRESHOLD_PERCENT = 92
    private const val RAM_RECOVERY_THRESHOLD_PERCENT = 75

    // ==================== PSS Thresholds (Fallback/Secondary) ====================
    // PSS thresholds are calculated based on device RAM at initialization.
    // Used as secondary check when RAM % isn't triggering (e.g., other apps using memory).

    // Fallback thresholds for when device info is unavailable
    private const val FALLBACK_WARNING_MB = 300
    private const val FALLBACK_CRITICAL_MB = 450
    private const val FALLBACK_EMERGENCY_MB = 550
    private const val FALLBACK_RECOVERY_MB = 250

    // Calculated thresholds (set during init)
    private var pssWarningThresholdMb = FALLBACK_WARNING_MB
    private var pssCriticalThresholdMb = FALLBACK_CRITICAL_MB
    private var pssEmergencyThresholdMb = FALLBACK_EMERGENCY_MB
    private var pssRecoveryThresholdMb = FALLBACK_RECOVERY_MB

    // Device info for diagnostics
    private var deviceTotalRamMb = 0
    private var deviceLowMemoryThresholdMb = 0

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var startTime = 0L
    private var logCount = 0
    private var appContext: Context? = null
    private var activityManager: ActivityManager? = null
    private var myPid: Int = 0

    // Track if we've already taken action at each level (reset on app restart or after reload)
    private var warningActionTaken = false
    private var criticalActionTaken = false

    // Track critical trigger timing to prevent duplicate triggers
    private var lastCriticalTriggerTime = 0L
    private const val CRITICAL_MIN_INTERVAL_MS = 60 * 1000L  // Minimum 60s between critical triggers

    // Periodic memory snapshot tracking (every 15 minutes)
    private var lastSnapshotTime = 0L

    // Periodic memory trend (in-memory + persisted to SharedPreferences for OOM reports)
    private val hourlyPssReadings = mutableListOf<Int>()
    private val hourlyRamReadings = mutableListOf<Int>()
    private val hourlyHeapReadings = mutableListOf<Int>()  // Heap usage % (0-100)
    private const val MAX_HOURLY_READINGS = 96  // Cap at 96 readings = 24 hours at 15min intervals

    // RAM jump detection (track previous reading to detect significant changes)
    private var previousRamPercent = -1
    private const val RAM_JUMP_THRESHOLD_PERCENT = 5  // Log when RAM changes by more than 5%
    private var forceSnapshotOnNextCheck = false  // Force a trend snapshot after a significant jump

    // Process importance tracking (detect if Samsung/system downgrades our priority)
    private var lastImportance = -1
    private const val IMPORTANCE_LOG_INTERVAL_MS = 5 * 60 * 1000L  // Log importance every 5 min
    private var lastImportanceLogTime = 0L

    /**
     * Reset action flags after a successful WebView reload.
     * This allows WARNING to fire again in the next pressure cycle
     * rather than waiting for PSS to drop below recovery threshold.
     */
    fun resetActionFlags() {
        Log.i(TAG, "📊 Resetting memory pressure action flags after reload")
        warningActionTaken = false
        criticalActionTaken = false
    }

    // Component-specific status callbacks (set by each subsystem)
    var rtspCacheStatusProvider: (() -> String)? = null
    var photoCacheStatusProvider: (() -> String)? = null
    var wakeWordStatusProvider: (() -> String)? = null
    var haConnectionStatusProvider: (() -> String)? = null
    var screensaverStatusProvider: (() -> String)? = null
    // Brain route + local-brain failure health — makes a device stranded on a stale
    // brain_route='local' visible in the heartbeat instead of failing every turn silently.
    var brainRouteStatusProvider: (() -> String)? = null

    // Callback to save memory reading for OOM detection (set by MainActivity)
    // Passes both PSS (MB) and RAM % since WebView renderer memory may not show in PSS
    var saveMemoryReadingCallback: ((pssMb: Int, ramPercent: Int) -> Unit)? = null

    // Callback for memory pressure actions (set by MainActivity)
    // Level: "high" (25% headroom - just log), "warning" (35% headroom - reload during screensaver),
    //        "critical" (45% headroom - immediate reload)
    var onMemoryPressureCallback: ((level: String, pssMb: Int) -> Unit)? = null

    // Callback to persist current WebView URL for OOM recovery (called every 30s)
    var persistCurrentUrlCallback: (() -> Unit)? = null

    /**
     * Provider for runtime diagnostic state (WiFi lock, service bound, screensaver, screen).
     * Called every 30s to persist state that won't survive OOM kills.
     * Returns: (wifiLockHeld, serviceBound, screensaverActive, screensaverMode, screenInteractive)
     */
    var diagnosticStateProvider: (() -> DiagnosticState)? = null

    data class DiagnosticState(
        val wifiLockHeld: Boolean,
        val serviceBound: Boolean,
        val screensaverActive: Boolean,
        val screensaverMode: String,
        val screenInteractive: Boolean
    )

    /**
     * Initialize with context for PSS monitoring.
     * Call this in MainActivity.onCreate().
     *
     * Calculates dynamic memory thresholds based on device RAM.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        myPid = android.os.Process.myPid()

        // Calculate dynamic thresholds based on device RAM
        calculateDynamicThresholds()

        Log.i(TAG, "Initialized with PID=$myPid, RAM=${deviceTotalRamMb}MB, lowThreshold=${deviceLowMemoryThresholdMb}MB")
        Log.i(TAG, "Dynamic thresholds: warning=${pssWarningThresholdMb}MB, critical=${pssCriticalThresholdMb}MB, emergency=${pssEmergencyThresholdMb}MB")
        PersistentLog.info("MEMORY", "Dynamic thresholds: warn=$pssWarningThresholdMb, crit=$pssCriticalThresholdMb, emerg=$pssEmergencyThresholdMb (device RAM: $deviceTotalRamMb MB)")
    }

    /**
     * Calculate dynamic memory thresholds based on device characteristics.
     *
     * Uses the "headroom" approach: the space between Android's low memory threshold
     * and total RAM is divided into zones for our thresholds.
     *
     * Example for 3GB device with 500MB low threshold:
     * - Headroom = 3000 - 500 = 2500 MB
     * - Warning at PSS = 500 + (2500 * 0.50) = 1750 MB
     * - Critical at PSS = 500 + (2500 * 0.75) = 2375 MB
     * - Emergency at PSS = 500 + (2500 * 0.90) = 2750 MB
     * - Recovery when PSS < 500 + (2500 * 0.40) = 1500 MB
     */
    private fun calculateDynamicThresholds() {
        val am = activityManager ?: return

        try {
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)

            deviceTotalRamMb = (memInfo.totalMem / 1024 / 1024).toInt()
            deviceLowMemoryThresholdMb = (memInfo.threshold / 1024 / 1024).toInt()

            // Calculate headroom (available space above Android's low memory threshold)
            val headroomMb = deviceTotalRamMb - deviceLowMemoryThresholdMb

            if (headroomMb > 0) {
                // Use percentages of headroom for thresholds
                // These are PSS thresholds, not "headroom remaining"
                pssWarningThresholdMb = (headroomMb * 0.25).toInt()    // 25% of headroom
                pssCriticalThresholdMb = (headroomMb * 0.35).toInt()   // 35% of headroom
                pssEmergencyThresholdMb = (headroomMb * 0.45).toInt()  // 45% of headroom
                pssRecoveryThresholdMb = (headroomMb * 0.20).toInt()   // 20% of headroom

                // Ensure minimum thresholds for very low RAM devices
                pssWarningThresholdMb = pssWarningThresholdMb.coerceAtLeast(200)
                pssCriticalThresholdMb = pssCriticalThresholdMb.coerceAtLeast(300)
                pssEmergencyThresholdMb = pssEmergencyThresholdMb.coerceAtLeast(400)
                pssRecoveryThresholdMb = pssRecoveryThresholdMb.coerceAtLeast(150)

                // Ensure ordering is correct
                if (pssWarningThresholdMb >= pssCriticalThresholdMb) {
                    pssWarningThresholdMb = pssCriticalThresholdMb - 50
                }
                if (pssCriticalThresholdMb >= pssEmergencyThresholdMb) {
                    pssCriticalThresholdMb = pssEmergencyThresholdMb - 50
                }
            } else {
                Log.w(TAG, "Headroom calculation failed, using fallback thresholds")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate dynamic thresholds: ${e.message}")
        }
    }

    /**
     * Get current threshold information for diagnostics overlay.
     */
    fun getThresholdInfo(): ThresholdInfo {
        return ThresholdInfo(
            warningMb = pssWarningThresholdMb,
            criticalMb = pssCriticalThresholdMb,
            emergencyMb = pssEmergencyThresholdMb,
            recoveryMb = pssRecoveryThresholdMb,
            deviceTotalRamMb = deviceTotalRamMb,
            deviceLowMemoryThresholdMb = deviceLowMemoryThresholdMb
        )
    }

    data class ThresholdInfo(
        val warningMb: Int,
        val criticalMb: Int,
        val emergencyMb: Int,
        val recoveryMb: Int,
        val deviceTotalRamMb: Int,
        val deviceLowMemoryThresholdMb: Int
    )

    private val runnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            logMemoryStats()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    /**
     * Start periodic memory monitoring.
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        startTime = System.currentTimeMillis()
        logCount = 0

        // Log immediately on start
        logMemoryStats()

        // Schedule periodic logging
        handler.postDelayed(runnable, INTERVAL_MS)
        Log.i(TAG, "Memory monitoring started (interval: ${INTERVAL_MS / 1000}s)")
        DiagnosticBuffer.info("MEMORY", "Monitor started")
    }

    /**
     * Stop periodic memory monitoring.
     */
    fun stop() {
        isRunning = false
        handler.removeCallbacks(runnable)
        Log.i(TAG, "Memory monitoring stopped")
    }

    /**
     * Get system RAM usage percentage (0-100).
     */
    private fun getRamPercent(): Int {
        val am = activityManager ?: return -1
        return try {
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val used = memInfo.totalMem - memInfo.availMem
            ((used * 100) / memInfo.totalMem).toInt()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get RAM %: ${e.message}")
            -1
        }
    }

    /**
     * Log current memory statistics with component details.
     * Checks RAM % thresholds (primary) for proactive intervention.
     */
    fun logMemoryStats() {
        logCount++

        // Persist current WebView URL for OOM recovery
        persistCurrentUrlCallback?.invoke()

        val runtime = Runtime.getRuntime()

        val maxMemMb = runtime.maxMemory() / (1024 * 1024)
        val totalMemMb = runtime.totalMemory() / (1024 * 1024)
        val freeMemMb = runtime.freeMemory() / (1024 * 1024)
        val usedMemMb = totalMemMb - freeMemMb
        val heapPercent = (usedMemMb * 100) / maxMemMb

        val uptimeMin = (System.currentTimeMillis() - startTime) / 60000

        // Get system RAM % (primary threshold metric)
        val ramPercent = getRamPercent()

        // Get total app PSS (main process + WebView renderer) for logging
        val totalPssMb = getPssMb()

        // Build PSS string with breakdown if renderer is active
        val pssStr = if (totalPssMb > 0) {
            if (lastRendererPssMb > 0) {
                " PSS=${totalPssMb}MB (main=${lastMainPssMb}+renderer=${lastRendererPssMb})"
            } else {
                " PSS=${totalPssMb}MB"
            }
        } else ""

        val msg = "uptime=${uptimeMin}min RAM=${ramPercent}% heap=${usedMemMb}MB/${maxMemMb}MB (${heapPercent}%)$pssStr"

        // Log to both logcat and diagnostic buffer
        Log.i(TAG, "MEM[$logCount] $msg")
        DiagnosticBuffer.info("MEMORY", msg)
        PersistentLog.info("MEMORY", msg)

        // Detect significant RAM jumps (>5% change between 30-second readings)
        if (previousRamPercent >= 0 && ramPercent > 0) {
            val delta = ramPercent - previousRamPercent
            if (kotlin.math.abs(delta) >= RAM_JUMP_THRESHOLD_PERCENT) {
                val direction = if (delta > 0) "SPIKE" else "DROP"
                val jumpMsg = "RAM $direction: ${previousRamPercent}% -> ${ramPercent}% (${if (delta > 0) "+" else ""}${delta}%) PSS=${totalPssMb}MB"
                Log.w(TAG, "📊 $jumpMsg")
                PersistentLog.warn("MEMORY", jumpMsg)
                DiagnosticBuffer.warn("MEMORY", jumpMsg)
                // Force a trend snapshot on significant jumps so we capture the event
                forceSnapshotOnNextCheck = true
            }
        }
        previousRamPercent = ramPercent

        // Save memory reading for OOM detection (both PSS and RAM % for threshold comparison)
        // WebView renderer memory may not show in PSS, so RAM % catches those cases
        saveMemoryReadingCallback?.invoke(totalPssMb, ramPercent)

        // Persist system memory and app breakdown for OOM reports (survives OOM kills)
        persistMemoryDetails(heapPercent.toInt())

        // Persist heartbeat timestamp — the unambiguous signal of when the MemoryMonitor
        // loop last successfully ran. Crash reports compare this to process_start to
        // compute actual alive-time (vs. wall-clock time since start, which misleads when
        // the device sat dead for hours after kill).
        appContext?.let { ctx ->
            CrashDiagnosticsCollector.persistMonitorHeartbeat(ctx, totalPssMb, ramPercent)
        }

        // Monitor our process importance (detect if system/Samsung downgrades priority)
        checkProcessImportance()

        // Log component-specific status
        logComponentStatus()

        // Check RAM % and Heap % thresholds for proactive intervention
        if (ramPercent > 0) {
            checkRamThresholds(ramPercent, heapPercent.toInt())
        }

        // Warn if heap is getting low (legacy check)
        if (heapPercent >= 90) {
            val warning = "WARNING: Heap usage at ${heapPercent}%!"
            Log.w(TAG, warning)
            DiagnosticBuffer.warn("MEMORY", warning)
            PersistentLog.warn("MEMORY", warning)
        }

        // Hourly snapshot for crash/diagnostic reports
        checkAndLogHourlySnapshot(ramPercent, totalPssMb, heapPercent.toInt())
    }

    /**
     * Check if snapshot interval has passed and log a memory snapshot to PersistentLog.
     * Snapshots are taken every 15 minutes (or forced after significant RAM jumps).
     * These are included in crash reports to track memory trends.
     *
     * Also records the reading in the trend list and persists it
     * to SharedPreferences so it survives OOM kills.
     */
    private fun checkAndLogHourlySnapshot(ramPercent: Int, pssMb: Int, heapPercent: Int = 0) {
        val now = System.currentTimeMillis()

        // Snapshot on interval OR forced (after RAM jump)
        val intervalElapsed = lastSnapshotTime == 0L || (now - lastSnapshotTime) >= SNAPSHOT_INTERVAL_MS
        if (intervalElapsed || forceSnapshotOnNextCheck) {
            forceSnapshotOnNextCheck = false
            lastSnapshotTime = now

            // Log to PersistentLog (survives restarts, included in crash/diagnostic reports)
            val reason = if (!intervalElapsed) " [RAM jump]" else ""
            val snapshot = "SNAPSHOT$reason: RAM=$ramPercent%, App Memory=$pssMb MB, Heap=$heapPercent%"
            PersistentLog.info("MEMORY", snapshot)
            Log.i(TAG, "📊 $snapshot")

            // Log system memory and app breakdown on snapshots
            val sysMemory = readSystemMemory()
            val appBreakdown = getAppMemoryBreakdown()
            PersistentLog.info("MEMORY", "System: $sysMemory")
            PersistentLog.info("MEMORY", "App breakdown: $appBreakdown")

            // Also capture in diagnostic buffer for immediate diagnostics
            DiagnosticBuffer.info("MEMORY", snapshot)

            // Record in hourly trend (cap at MAX_HOURLY_READINGS)
            if (hourlyPssReadings.size < MAX_HOURLY_READINGS) {
                hourlyPssReadings.add(pssMb)
                hourlyRamReadings.add(ramPercent)
                hourlyHeapReadings.add(heapPercent)
            }

            // Persist trend to SharedPreferences (survives OOM kills)
            persistHourlyTrend()

            // Log the trend line so far
            if (hourlyPssReadings.size > 1) {
                val trendStr = formatMemoryTrend()
                PersistentLog.info("MEMORY", "TREND: $trendStr")
                Log.i(TAG, "📊 TREND: $trendStr")
            }
        }
    }

    /**
     * Persist the hourly trend to SharedPreferences via halitePrefs.
     * This ensures the trend data survives OOM kills.
     */
    private fun persistHourlyTrend() {
        val prefs = halitePrefs ?: return
        prefs.performance.hourlyPssTrend = hourlyPssReadings.joinToString(",")
        prefs.performance.hourlyRamTrend = hourlyRamReadings.joinToString(",")
        prefs.performance.hourlyHeapTrend = hourlyHeapReadings.joinToString(",")
    }

    /**
     * Format the memory trend as a compact string for logging/reports.
     * Output: "PSS: 24→450→680→720→740→750→757 | RAM: 42→78→84→85→86→86→86 (15min intervals)"
     */
    fun formatMemoryTrend(): String {
        if (hourlyPssReadings.isEmpty()) return "(no data)"
        val pss = hourlyPssReadings.joinToString("→") { "${it}" }
        val ram = hourlyRamReadings.joinToString("→") { "${it}" }
        val heap = hourlyHeapReadings.joinToString("→") { "${it}" }
        return "PSS(MB): $pss | RAM(%): $ram | Heap(%): $heap (15min intervals)"
    }

    /**
     * Get the memory trend as a formatted string for OOM crash reports.
     * Includes per-snapshot labels (15-minute intervals) and a growth summary.
     *
     * Can also read from persisted data (for use after app restart during OOM report generation).
     * Pass pssCsv/ramCsv from SharedPreferences, or null to use in-memory data.
     */
    fun formatTrendForCrashReport(pssCsv: String? = null, ramCsv: String? = null, heapCsv: String? = null): String {
        val pssValues = if (pssCsv.isNullOrBlank()) {
            hourlyPssReadings.toList()
        } else {
            pssCsv.split(",").mapNotNull { it.trim().toIntOrNull() }
        }

        val ramValues = if (ramCsv.isNullOrBlank()) {
            hourlyRamReadings.toList()
        } else {
            ramCsv.split(",").mapNotNull { it.trim().toIntOrNull() }
        }

        val heapValues = if (heapCsv.isNullOrBlank()) {
            hourlyHeapReadings.toList()
        } else {
            heapCsv.split(",").mapNotNull { it.trim().toIntOrNull() }
        }

        if (pssValues.isEmpty()) return "(no trend data recorded)"

        val sb = StringBuilder()
        sb.appendLine("Interval: 15min snapshots (+ on significant RAM jumps)")

        // PSS trend with time labels (t0=0min, t1=15min, t2=30min, etc.)
        sb.append("PSS (MB): ")
        sb.appendLine(pssValues.mapIndexed { i, v ->
            val min = i * 15
            "${min}m=$v"
        }.joinToString(" "))

        // RAM trend with time labels
        if (ramValues.isNotEmpty()) {
            sb.append("RAM (%):  ")
            sb.appendLine(ramValues.mapIndexed { i, v ->
                val min = i * 15
                "${min}m=$v"
            }.joinToString(" "))
        }

        // Heap trend with time labels
        if (heapValues.isNotEmpty()) {
            sb.append("Heap (%): ")
            sb.appendLine(heapValues.mapIndexed { i, v ->
                val min = i * 15
                "${min}m=$v"
            }.joinToString(" "))
        }

        // Growth summary
        if (pssValues.size >= 2) {
            val first = pssValues.first()
            val last = pssValues.last()
            val growth = last - first
            val totalMinutes = (pssValues.size - 1) * 15
            val sign = if (growth >= 0) "+" else ""

            sb.append("Growth:   ${sign}${growth}MB over ${totalMinutes}min")

            // Check if values plateaued (last 3+ readings within 20MB of each other)
            if (pssValues.size >= 3) {
                val tail = pssValues.takeLast(3)
                val min = tail.min()
                val max = tail.max()
                if (max - min <= 20) {
                    val plateauIdx = pssValues.size - 3
                    val plateauMin = plateauIdx * 15
                    sb.append(" (stable ~${tail.average().toInt()}MB since ${plateauMin}m)")
                }
            }
            sb.appendLine()
        }

        return sb.toString().trimEnd()
    }

    // Cache renderer PID to avoid repeated process list scans
    private var cachedRendererPid: Int = -1
    private var lastRendererPidCheck = 0L
    private const val RENDERER_PID_CACHE_MS = 60_000L  // Re-scan process list every 60s

    // Last known memory values for logging
    private var lastMainPssMb = 0
    private var lastRendererPssMb = 0

    /**
     * Get total app memory (main process + WebView renderer) in MB.
     *
     * The WebView renderer runs in a separate sandboxed process, so we need to
     * find it and sum its PSS with the main process PSS to get the true app footprint.
     *
     * This is the metric that properly reflects WebView/GPU memory leaks.
     */
    private fun getPssMb(): Int {
        return try {
            // Get main process PSS (real-time via Debug API)
            val mainMemInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(mainMemInfo)
            val mainPssMb = mainMemInfo.totalPss / 1024
            lastMainPssMb = mainPssMb

            // Get WebView renderer PSS (via ActivityManager - may be cached but OK for 30s intervals)
            val rendererPssMb = calculateRendererPssMb()
            lastRendererPssMb = rendererPssMb

            // Return combined total
            mainPssMb + rendererPssMb
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get PSS: ${e.message}")
            0
        }
    }

    /**
     * Calculate WebView renderer process PSS in MB (internal implementation).
     *
     * Finds the sandboxed WebView renderer process (if running) and returns its PSS.
     * The renderer typically has a process name like:
     * - <package>:sandboxed_process0
     * - <package>:webview
     * - or similar isolated process names
     *
     * Note: Uses ActivityManager.getProcessMemoryInfo() which is rate-limited by Android
     * (returns cached values for ~5 minutes). This is acceptable for our 30-second
     * monitoring interval.
     */
    private fun calculateRendererPssMb(): Int {
        val am = activityManager ?: return 0
        val packageName = appContext?.packageName ?: return 0

        try {
            // Check if we need to re-scan for renderer PID
            val now = System.currentTimeMillis()
            if (cachedRendererPid <= 0 || (now - lastRendererPidCheck) > RENDERER_PID_CACHE_MS) {
                cachedRendererPid = findRendererPid(am, packageName)
                lastRendererPidCheck = now

                if (cachedRendererPid > 0) {
                    Log.d(TAG, "Found WebView renderer process: PID=$cachedRendererPid")
                }
            }

            // If no renderer process found, return 0
            if (cachedRendererPid <= 0) {
                return 0
            }

            // Get renderer PSS via ActivityManager. Returns 0 on isolated-UID variants
            // (Fire OS, etc.) where the renderer runs under a UID we can't query.
            val memInfos = am.getProcessMemoryInfo(intArrayOf(cachedRendererPid))
            val amPssMb = if (memInfos.isNotEmpty()) memInfos[0].totalPss / 1024 else 0
            if (amPssMb > 0) return amPssMb

            // Fallback: read PSS directly from /proc/<pid>/smaps_rollup. Works for any PID
            // whose /proc files we can read, even when ActivityManager refuses to report
            // memory for sandboxed/isolated-UID processes.
            val procPssMb = readPssMbFromProc(cachedRendererPid)
            if (procPssMb > 0) return procPssMb
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get renderer PSS: ${e.message}")
            // Reset cached PID so we re-scan next time
            cachedRendererPid = -1
        }

        return 0
    }

    /**
     * Read proportional set size (PSS) for an arbitrary PID via the kernel's procfs.
     *
     * `/proc/<pid>/smaps_rollup` reports the same Pss number as `dumpsys meminfo` and works
     * even for sandboxed/isolated-UID processes that are invisible to ActivityManager APIs.
     * Available on Linux kernel 4.14+ (Android 8+); falls back to summing `/proc/<pid>/smaps`
     * is not implemented because Fire OS 7's kernel does have smaps_rollup.
     *
     * Last fallback: `VmRSS` from `/proc/<pid>/status`. RSS overcounts shared pages but is
     * better than reporting zero.
     *
     * @return PSS in MB, or 0 if unreadable.
     */
    private fun readPssMbFromProc(pid: Int): Int {
        if (pid <= 0) return 0
        try {
            val rollup = java.io.File("/proc/$pid/smaps_rollup")
            if (rollup.canRead()) {
                rollup.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.startsWith("Pss:")) {
                            val kb = line.substring(4).trim().split(Regex("\\s+")).first().toIntOrNull()
                            if (kb != null) return kb / 1024
                            break
                        }
                        line = reader.readLine()
                    }
                }
            }
            // Fallback to VmRSS — overcounts shared pages but better than zero.
            val status = java.io.File("/proc/$pid/status")
            if (status.canRead()) {
                status.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.startsWith("VmRSS:")) {
                            val kb = line.substring(6).trim().split(Regex("\\s+")).first().toIntOrNull()
                            if (kb != null) return kb / 1024
                            break
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (e: Exception) {
            // /proc files may briefly disappear if the renderer is restarting; ignore.
        }
        return 0
    }

    /**
     * Find the WebView renderer process PID.
     *
     * WebView on Android runs in an isolated/sandboxed process for security.
     * On older Android/WebView, the process runs under our app's package.
     * On newer versions, it runs under the WebView provider's package:
     * - com.google.android.webview (Google WebView)
     * - com.amazon.webview.chromium (Amazon Fire devices)
     * - com.android.webview (AOSP WebView)
     */
    private fun findRendererPid(am: ActivityManager, packageName: String): Int {
        try {
            val procs = am.runningAppProcesses ?: return -1

            // Note: On modern Android, WebView sandboxed processes run under isolated UIDs
            // and are NOT visible to us via runningAppProcesses (security restriction)

            // First, try to find under our package name (older Android behavior)
            for (proc in procs) {
                if (!proc.processName.startsWith(packageName)) continue
                if (proc.processName == packageName) continue

                val suffix = proc.processName.removePrefix("$packageName:")
                if (suffix.contains("sandbox", ignoreCase = true) ||
                    suffix.contains("webview", ignoreCase = true) ||
                    suffix.contains("isolated", ignoreCase = true) ||
                    suffix.startsWith("privileged_process") ||
                    suffix.matches(Regex("\\d+"))) {
                    Log.d(TAG, "Found renderer under app package: ${proc.processName} (PID=${proc.pid})")
                    return proc.pid
                }
            }

            // If not found, look under known WebView provider packages
            val webViewPackages = listOf(
                "com.google.android.webview",
                "com.amazon.webview.chromium",
                "com.android.webview"
            )

            for (proc in procs) {
                for (webViewPkg in webViewPackages) {
                    if (proc.processName.startsWith("$webViewPkg:sandboxed_process")) {
                        Log.d(TAG, "Found WebView renderer: ${proc.processName} (PID=${proc.pid})")
                        return proc.pid
                    }
                }
            }

            // Debug: log what WebView-related processes we found
            val webViewProcs = procs.filter {
                it.processName.contains("webview", ignoreCase = true) ||
                it.processName.contains("chromium", ignoreCase = true) ||
                it.processName.contains("sandbox", ignoreCase = true)
            }
            if (webViewProcs.isNotEmpty()) {
                Log.d(TAG, "WebView processes found but not matched: ${webViewProcs.map { it.processName }}")
            }

        } catch (e: Exception) {
            Log.w(TAG, "Failed to find renderer process: ${e.message}")
        }

        // Fallback for isolated-UID variants (Fire OS, etc.) where getRunningAppProcesses()
        // omits sandboxed processes spawned under our app: scan /proc directly.
        return findRendererPidViaProc()
    }

    /**
     * Scan /proc/<pid>/cmdline for any running process whose command line identifies it as
     * a WebView sandboxed renderer. Works regardless of UID isolation because /proc/<pid>/cmdline
     * is world-readable on Linux. Used as a fallback when ActivityManager.getRunningAppProcesses()
     * omits the renderer (Fire OS 7 / API 25+ isolated-UID security).
     *
     * @return PID of the first matching renderer, or -1 if none found.
     */
    private fun findRendererPidViaProc(): Int {
        try {
            val procDir = java.io.File("/proc")
            val pidDirs = procDir.list { _, name -> name.isNotEmpty() && name[0].isDigit() } ?: return -1
            val matchedPids = mutableListOf<Pair<Int, String>>()
            for (entry in pidDirs) {
                val pid = entry.toIntOrNull() ?: continue
                val cmdlineFile = java.io.File("/proc/$pid/cmdline")
                if (!cmdlineFile.canRead()) continue
                val cmdline = try {
                    cmdlineFile.readText().replace(' ', ' ').trim()
                } catch (e: Exception) {
                    continue
                }
                if (cmdline.isEmpty()) continue
                // Match common WebView renderer cmdline patterns:
                //   com.amazon.webview.chromium:sandboxed_process0
                //   com.google.android.webview:sandboxed_process<N>
                //   com.android.webview:sandboxed_process<N>
                //   <our_pkg>:sandboxed_processN  (older Android)
                if (cmdline.contains(":sandboxed_process") ||
                    cmdline.contains("webview.chromium:") ||
                    cmdline.contains("android.webview:")) {
                    matchedPids.add(pid to cmdline)
                }
            }
            if (matchedPids.isNotEmpty()) {
                // If multiple renderers exist (e.g. multi-process WebView with utility procs),
                // pick the first; our caller can extend to sum across all if needed.
                val (pid, cmd) = matchedPids.first()
                Log.d(TAG, "Found renderer via /proc: PID=$pid cmdline=$cmd (${matchedPids.size} matches)")
                return pid
            }
        } catch (e: Exception) {
            Log.w(TAG, "findRendererPidViaProc failed: ${e.message}")
        }
        return -1
    }

    /**
     * Get memory breakdown for detailed logging.
     * Returns: "main=XXmb renderer=YYmb total=ZZmb"
     */
    fun getMemoryBreakdown(): String {
        val total = lastMainPssMb + lastRendererPssMb
        return if (lastRendererPssMb > 0) {
            "main=${lastMainPssMb}MB renderer=${lastRendererPssMb}MB total=${total}MB"
        } else {
            "main=${lastMainPssMb}MB (no renderer)"
        }
    }

    /**
     * Persist system memory and app memory breakdown to SharedPreferences.
     * Called every 30s so the last reading survives OOM kills.
     */
    private fun persistMemoryDetails(heapPercent: Int) {
        val prefs = halitePrefs ?: return
        prefs.performance.lastSystemMeminfo = readSystemMemory()
        prefs.performance.lastAppMemoryBreakdown = getAppMemoryBreakdown()

        // Persist runtime diagnostic state for crash reports (survives OOM kills)
        val ctx = appContext ?: return
        diagnosticStateProvider?.invoke()?.let { state ->
            CrashDiagnosticsCollector.persistRuntimeState(
                ctx,
                wifiLockHeld = state.wifiLockHeld,
                serviceBound = state.serviceBound,
                screensaverActive = state.screensaverActive,
                screensaverMode = state.screensaverMode,
                screenInteractive = state.screenInteractive
            )
        }
    }

    /**
     * Read system-wide memory info from /proc/meminfo.
     * Returns a compact string with key metrics for OOM crash reports.
     * This is world-readable on Android, no special permissions needed.
     */
    private fun readSystemMemory(): String {
        return try {
            val lines = File("/proc/meminfo").readLines()
            val values = mutableMapOf<String, Long>()
            for (line in lines) {
                val parts = line.split(":")
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val kb = parts[1].trim().removeSuffix(" kB").trim().toLongOrNull()
                    if (kb != null) values[key] = kb
                }
            }
            val totalMb = (values["MemTotal"] ?: 0) / 1024
            val availMb = (values["MemAvailable"] ?: 0) / 1024
            val freeMb = (values["MemFree"] ?: 0) / 1024
            val buffersMb = (values["Buffers"] ?: 0) / 1024
            val cachedMb = (values["Cached"] ?: 0) / 1024
            val swapTotalMb = (values["SwapTotal"] ?: 0) / 1024
            val swapFreeMb = (values["SwapFree"] ?: 0) / 1024
            val swapUsedMb = swapTotalMb - swapFreeMb
            val usedMb = totalMb - availMb

            "Total=${totalMb}MB Used=${usedMb}MB Avail=${availMb}MB " +
                "Free=${freeMb}MB Buffers=${buffersMb}MB Cached=${cachedMb}MB " +
                "Swap=${swapUsedMb}/${swapTotalMb}MB"
        } catch (e: Exception) {
            "(error: ${e.message})"
        }
    }

    /**
     * Get detailed app memory breakdown from Debug.MemoryInfo.
     * Uses summary stats (API 23+) to categorize: java heap, native heap,
     * graphics (GPU), code, stack, and other.
     * Returns compact string for persistence and crash reports.
     */
    private fun getAppMemoryBreakdown(): String {
        return try {
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)

            // Summary stats in KB (available API 23+)
            val javaHeap = (memInfo.getMemoryStat("summary.java-heap")?.toIntOrNull() ?: 0) / 1024
            val nativeHeap = (memInfo.getMemoryStat("summary.native-heap")?.toIntOrNull() ?: 0) / 1024
            val code = (memInfo.getMemoryStat("summary.code")?.toIntOrNull() ?: 0) / 1024
            val stack = (memInfo.getMemoryStat("summary.stack")?.toIntOrNull() ?: 0) / 1024
            val graphics = (memInfo.getMemoryStat("summary.graphics")?.toIntOrNull() ?: 0) / 1024
            val privateOther = (memInfo.getMemoryStat("summary.private-other")?.toIntOrNull() ?: 0) / 1024
            val system = (memInfo.getMemoryStat("summary.system")?.toIntOrNull() ?: 0) / 1024
            val totalSwap = (memInfo.getMemoryStat("summary.total-swap")?.toIntOrNull() ?: 0) / 1024

            "JavaHeap=${javaHeap}MB Native=${nativeHeap}MB " +
                "Graphics=${graphics}MB Code=${code}MB " +
                "Stack=${stack}MB Other=${privateOther}MB " +
                "System=${system}MB Swap=${totalSwap}MB"
        } catch (e: Exception) {
            "(error: ${e.message})"
        }
    }

    /**
     * Get main process PSS in MB (from last reading).
     */
    fun getMainPssMb(): Int = lastMainPssMb

    /**
     * Get WebView renderer PSS in MB (from last reading).
     * Returns 0 if no renderer process is active.
     */
    fun getRendererPssMb(): Int = lastRendererPssMb

    /**
     * Get total app PSS in MB (main + renderer, from last reading).
     */
    fun getTotalPssMb(): Int = lastMainPssMb + lastRendererPssMb

    /**
     * Get fresh total PSS in MB (main + renderer).
     * Use this for real-time monitoring (e.g., performance overlay).
     * Calls getPssMb() which refreshes both main and renderer PSS.
     */
    fun getFreshTotalPssMb(): Int = getPssMb()

    /**
     * Check memory thresholds using OR logic: RAM % OR Heap %.
     *
     * This dual-threshold approach handles:
     * - System-wide memory pressure (RAM % catches general memory exhaustion)
     * - App-specific heap exhaustion (Heap % catches WebRTC/SurfaceTexture leaks)
     *
     * Thresholds are read from user preferences.
     */
    private fun checkRamThresholds(ramPercent: Int, heapPercent: Int) {
        val prefs = halitePrefs ?: return

        // Get user-configured thresholds (0 = disabled)
        val idleRamPct = prefs.performance.idleRamPercent
        val idleHeapPct = prefs.performance.idleHeapPercent
        val criticalRamPct = prefs.performance.criticalRamPercent
        val criticalHeapPct = prefs.performance.criticalHeapPercent

        // Debug: Log current values vs thresholds
        Log.d(TAG, "📊 Threshold check: RAM=$ramPercent% (idle=$idleRamPct%, critical=$criticalRamPct%), Heap=$heapPercent% (idle=$idleHeapPct%, critical=$criticalHeapPct%)")

        // Check CRITICAL threshold (OR logic: triggers if EITHER exceeds)
        val criticalRamTriggered = criticalRamPct > 0 && ramPercent >= criticalRamPct
        val criticalHeapTriggered = criticalHeapPct > 0 && heapPercent >= criticalHeapPct

        if (criticalRamTriggered || criticalHeapTriggered) {
            if (isEmergencyRecoveryEnabled()) {
                val now = System.currentTimeMillis()
                val timeSinceLastTrigger = now - lastCriticalTriggerTime

                // Enforce minimum 60s interval between critical triggers to match MainMemoryManager throttle
                if (lastCriticalTriggerTime > 0 && timeSinceLastTrigger < CRITICAL_MIN_INTERVAL_MS) {
                    Log.d(TAG, "📊 Critical threshold exceeded but too soon (${timeSinceLastTrigger/1000}s < 60s)")
                    return
                }

                lastCriticalTriggerTime = now

                val trigger = when {
                    criticalRamTriggered && criticalHeapTriggered -> "RAM=${ramPercent}% AND Heap=${heapPercent}%"
                    criticalRamTriggered -> "RAM=${ramPercent}% >= ${criticalRamPct}%"
                    else -> "Heap=${heapPercent}% >= ${criticalHeapPct}%"
                }
                val msg = "🚨 CRITICAL: $trigger! Triggering immediate WebView reload"
                Log.e(TAG, msg)
                PersistentLog.error("MEMORY", msg)
                DiagnosticBuffer.error("MEMORY", msg)
                onMemoryPressureCallback?.invoke("critical", ramPercent)
            } else {
                val msg = "🚨 Critical threshold exceeded but recovery is DISABLED (RAM=${ramPercent}%, Heap=${heapPercent}%)"
                Log.w(TAG, msg)
                PersistentLog.warn("MEMORY", msg)
            }
            return
        }

        // Check IDLE threshold (OR logic)
        val idleRamTriggered = idleRamPct > 0 && ramPercent >= idleRamPct
        val idleHeapTriggered = idleHeapPct > 0 && heapPercent >= idleHeapPct

        if ((idleRamTriggered || idleHeapTriggered) && !criticalActionTaken) {
            criticalActionTaken = true
            val trigger = when {
                idleRamTriggered && idleHeapTriggered -> "RAM=${ramPercent}% AND Heap=${heapPercent}%"
                idleRamTriggered -> "RAM=${ramPercent}% >= ${idleRamPct}%"
                else -> "Heap=${heapPercent}% >= ${idleHeapPct}%"
            }
            val msg = "⚠️ IDLE THRESHOLD: $trigger - will reload during screensaver"
            Log.w(TAG, msg)
            PersistentLog.warn("MEMORY", msg)
            DiagnosticBuffer.warn("MEMORY", msg)
            onMemoryPressureCallback?.invoke("warning", ramPercent)
        }
        // Note: Flags are reset by resetActionFlags() after successful reload.
        // We don't use a recovery threshold because the reload may not drop memory
        // below a fixed level, and we need to be able to trigger again if memory
        // climbs back up (e.g., 95% → 80% → 95% should trigger twice).
    }

    /**
     * Check PSS thresholds (legacy/secondary check).
     * Kept for cases where RAM % isn't triggering but app PSS is high.
     */
    @Suppress("unused")
    private fun checkPssThresholds(pssMb: Int) {
        // Get effective critical threshold (use custom if set)
        val effectiveCriticalThreshold = getEffectiveEmergencyThreshold()

        when {
            pssMb >= effectiveCriticalThreshold -> {
                // CRITICAL: Immediate WebView reload (if enabled)
                if (isEmergencyRecoveryEnabled()) {
                    val msg = "🚨 CRITICAL: PSS=${pssMb}MB exceeds ${effectiveCriticalThreshold}MB! Triggering immediate WebView reload"
                    Log.e(TAG, msg)
                    PersistentLog.error("MEMORY", msg)
                    DiagnosticBuffer.error("MEMORY", msg)
                    onMemoryPressureCallback?.invoke("critical", pssMb)
                } else {
                    val msg = "🚨 PSS=${pssMb}MB exceeds ${effectiveCriticalThreshold}MB but critical recovery is DISABLED"
                    Log.w(TAG, msg)
                    PersistentLog.warn("MEMORY", msg)
                }
            }
            pssMb >= pssCriticalThresholdMb && !criticalActionTaken -> {
                // WARNING: WebView reload during screensaver (once per pressure cycle)
                criticalActionTaken = true
                val msg = "⚠️ WARNING: PSS=${pssMb}MB exceeds ${pssCriticalThresholdMb}MB - will reload during screensaver"
                Log.w(TAG, msg)
                PersistentLog.warn("MEMORY", msg)
                DiagnosticBuffer.warn("MEMORY", msg)
                onMemoryPressureCallback?.invoke("warning", pssMb)
            }
            pssMb >= pssWarningThresholdMb && !warningActionTaken -> {
                // HIGH: Log only, monitor closely (once per pressure cycle)
                warningActionTaken = true
                val msg = "📊 HIGH: PSS=${pssMb}MB exceeds ${pssWarningThresholdMb}MB - monitoring"
                Log.i(TAG, msg)
                PersistentLog.info("MEMORY", msg)
                DiagnosticBuffer.info("MEMORY", msg)
                onMemoryPressureCallback?.invoke("high", pssMb)
            }
            pssMb < pssRecoveryThresholdMb -> {
                // Memory recovered below recovery threshold - reset flags for next cycle
                if (warningActionTaken || criticalActionTaken) {
                    Log.i(TAG, "✅ Memory recovered: PSS=${pssMb}MB < ${pssRecoveryThresholdMb}MB - flags reset")
                    PersistentLog.info("MEMORY", "Memory recovered: PSS=${pssMb}MB")
                }
                warningActionTaken = false
                criticalActionTaken = false
            }
        }
    }

    // Preferences reference for checking user settings
    private var halitePrefs: com.dashieapp.Dashie.halite.HalitePreferences? = null

    /**
     * Set preferences reference for checking user settings.
     * Should be called during init().
     *
     * Logs the user's configured threshold values to PersistentLog for diagnostics.
     */
    fun setPreferences(prefs: com.dashieapp.Dashie.halite.HalitePreferences) {
        halitePrefs = prefs

        // Log user's threshold settings for diagnostics (helps debug why thresholds didn't trigger)
        val idleRam = prefs.performance.idleRamPercent
        val idleHeap = prefs.performance.idleHeapPercent
        val criticalRam = prefs.performance.criticalRamPercent
        val criticalHeap = prefs.performance.criticalHeapPercent
        val stealthInterval = prefs.performance.stealthRefreshIntervalMinutes
        val stealthEnabled = prefs.performance.stealthRefreshEnabled

        val thresholdMsg = "User thresholds: idle=${idleRam}% RAM / ${idleHeap}% Heap, critical=${criticalRam}% RAM / ${criticalHeap}% Heap"
        val stealthMsg = "Stealth refresh: ${if (stealthEnabled) "enabled" else "disabled"}, interval=${stealthInterval}min"

        Log.i(TAG, "📊 $thresholdMsg")
        Log.i(TAG, "📊 $stealthMsg")
        PersistentLog.info("MEMORY", thresholdMsg)
        PersistentLog.info("MEMORY", stealthMsg)
    }

    /**
     * Get the effective emergency threshold based on user preference.
     * If custom mode is set, use the user-specified value; otherwise use calculated auto value.
     */
    private fun getEffectiveEmergencyThreshold(): Int {
        val prefs = halitePrefs ?: return pssEmergencyThresholdMb

        return if (prefs.performance.emergencyThresholdMode == com.dashieapp.Dashie.halite.HalitePreferences.THRESHOLD_MODE_CUSTOM) {
            prefs.performance.emergencyThresholdMb
        } else {
            pssEmergencyThresholdMb
        }
    }

    /**
     * Check if emergency recovery is enabled in user preferences.
     */
    private fun isEmergencyRecoveryEnabled(): Boolean {
        return halitePrefs?.performance?.emergencyRecoveryEnabled ?: true
    }

    /**
     * Log status from registered components.
     */
    private fun logComponentStatus() {
        // RTSP cache status
        rtspCacheStatusProvider?.let { provider ->
            try {
                val status = provider()
                Log.i(TAG, "  RTSP: $status")
                PersistentLog.info("MEMORY", "RTSP: $status")
            } catch (e: Exception) {
                Log.w(TAG, "  RTSP: error getting status")
            }
        }

        // Photo cache status
        photoCacheStatusProvider?.let { provider ->
            try {
                val status = provider()
                Log.i(TAG, "  Photo: $status")
                PersistentLog.info("MEMORY", "Photo: $status")
            } catch (e: Exception) {
                Log.w(TAG, "  Photo: error getting status")
            }
        }

        // Wake word detector status
        wakeWordStatusProvider?.let { provider ->
            try {
                val status = provider()
                Log.i(TAG, "  WakeWord: $status")
                PersistentLog.info("MEMORY", "WakeWord: $status")
            } catch (e: Exception) {
                Log.w(TAG, "  WakeWord: error getting status")
            }
        }

        // HA connection status
        haConnectionStatusProvider?.let { provider ->
            try {
                val status = provider()
                Log.i(TAG, "  HA: $status")
                PersistentLog.info("MEMORY", "HA: $status")
            } catch (e: Exception) {
                Log.w(TAG, "  HA: error getting status")
            }
        }

        // Screensaver status
        screensaverStatusProvider?.let { provider ->
            try {
                val status = provider()
                Log.i(TAG, "  Screensaver: $status")
                PersistentLog.info("MEMORY", "Screensaver: $status")
            } catch (e: Exception) {
                Log.w(TAG, "  Screensaver: error getting status")
            }
        }

        // Brain route status (add-on-reported route + last local-brain failure)
        brainRouteStatusProvider?.let { provider ->
            try {
                val status = provider()
                Log.i(TAG, "  BrainRoute: $status")
                PersistentLog.info("MEMORY", "BrainRoute: $status")
            } catch (e: Exception) {
                Log.w(TAG, "  BrainRoute: error getting status")
            }
        }
    }

    /**
     * Monitor our process importance level.
     * Android assigns an importance level that determines OOM kill order.
     * If Samsung Device Care or other memory managers downgrade our importance,
     * we want to know about it.
     *
     * Importance levels (lower = more important):
     * - 100 = FOREGROUND (visible activity)
     * - 125 = FOREGROUND_SERVICE
     * - 130 = FOREGROUND_SERVICE (while-in-use)
     * - 200 = VISIBLE
     * - 300 = SERVICE
     * - 400 = CACHED/HOME
     * - 500+ = BACKGROUND (easily killed)
     */
    private fun checkProcessImportance() {
        val am = activityManager ?: return
        try {
            val myInfo = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(myInfo)
            val importance = myInfo.importance
            val now = System.currentTimeMillis()

            // Log on change (any priority shift is noteworthy)
            if (importance != lastImportance) {
                val importanceName = importanceToName(importance)
                val prevName = if (lastImportance >= 0) importanceToName(lastImportance) else "unknown"
                val direction = when {
                    lastImportance < 0 -> "initial"
                    importance > lastImportance -> "DOWNGRADED"
                    importance < lastImportance -> "UPGRADED"
                    else -> "same"
                }
                val msg = "Process importance $direction: $prevName($lastImportance) -> $importanceName($importance)"

                if (importance > lastImportance && lastImportance >= 0) {
                    // Priority downgrade — this is dangerous, log as warning
                    Log.w(TAG, "⚠️ $msg")
                    PersistentLog.warn("MEMORY", msg)
                    DiagnosticBuffer.warn("MEMORY", msg)
                } else {
                    Log.i(TAG, "📊 $msg")
                    PersistentLog.info("MEMORY", msg)
                }
                lastImportance = importance
                lastImportanceLogTime = now
            } else if ((now - lastImportanceLogTime) >= IMPORTANCE_LOG_INTERVAL_MS) {
                // Periodic log even if unchanged (for crash report context)
                PersistentLog.info("MEMORY", "Process importance: ${importanceToName(importance)}($importance)")
                lastImportanceLogTime = now
            }
        } catch (e: Exception) {
            // Non-critical — don't spam errors
            Log.d(TAG, "Failed to check importance: ${e.message}")
        }
    }

    private fun importanceToName(importance: Int): String = when {
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FG_SERVICE"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
        else -> "BACKGROUND"
    }

    /**
     * Trigger immediate critical recovery from external callers (e.g., PerformanceOverlay).
     *
     * The performance overlay updates every 1 second and can detect threshold breaches
     * faster than MemoryMonitor's 30-second interval. This method allows it to trigger
     * immediate recovery without waiting for the next scheduled check.
     */
    fun triggerCriticalRecovery() {
        if (isEmergencyRecoveryEnabled()) {
            val pssMb = getTotalPssMb()
            val ramPct = getRamPercent()
            val msg = "🚨 CRITICAL (external trigger): RAM=$ramPct%, PSS=${pssMb}MB - triggering immediate recovery"
            Log.e(TAG, msg)
            PersistentLog.error("MEMORY", msg)
            DiagnosticBuffer.error("MEMORY", msg)
            onMemoryPressureCallback?.invoke("critical", pssMb)
        } else {
            Log.w(TAG, "🚨 Critical recovery requested but emergency recovery is DISABLED")
        }
    }

    /**
     * Trigger idle/warning recovery from external callers (e.g., PerformanceOverlay).
     *
     * This is called when the idle threshold (85% RAM or PSS idle MB) is exceeded.
     * The callback handler in MainActivity will check if screensaver is active
     * and has been active for 15+ seconds before actually reloading.
     *
     * This allows the 1-second overlay check to detect idle threshold faster than
     * the 30-second MemoryMonitor interval, while still respecting the
     * screensaver requirement.
     */
    fun triggerIdleRecovery() {
        val pssMb = getTotalPssMb()
        val ramPct = getRamPercent()
        val msg = "⚠️ IDLE (external trigger): RAM=$ramPct%, PSS=${pssMb}MB - checking screensaver status"
        Log.w(TAG, msg)
        PersistentLog.warn("MEMORY", msg)
        DiagnosticBuffer.warn("MEMORY", msg)
        onMemoryPressureCallback?.invoke("warning", pssMb)
    }

    /**
     * Log an event with memory snapshot (call on significant state changes).
     */
    fun logEvent(event: String) {
        val runtime = Runtime.getRuntime()
        val maxMemMb = runtime.maxMemory() / (1024 * 1024)
        val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val usedPercent = (usedMemMb * 100) / maxMemMb

        val msg = "EVENT: $event [${usedMemMb}MB/${maxMemMb}MB (${usedPercent}%)]"
        Log.i(TAG, msg)
        DiagnosticBuffer.info("MEMORY", msg)
        PersistentLog.info("MEMORY", msg)
    }

    /**
     * Get current memory status as a string.
     */
    fun getStatus(): String {
        val runtime = Runtime.getRuntime()
        val maxMemMb = runtime.maxMemory() / (1024 * 1024)
        val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        return "${usedMemMb}MB/${maxMemMb}MB"
    }
}

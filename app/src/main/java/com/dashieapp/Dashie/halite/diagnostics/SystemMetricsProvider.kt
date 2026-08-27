package com.dashieapp.Dashie.halite.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Provides real-time system metrics for the performance overlay.
 * Collects CPU, RAM, heap, and thermal data that can be exposed to JavaScript
 * via the DashieJSBridge.
 *
 * Note: /proc/stat is restricted on Android 8.0+ (SELinux), so we use
 * per-process CPU time from /proc/self/stat instead.
 *
 * Usage:
 * ```
 * val provider = SystemMetricsProvider(context)
 * val metricsJson = provider.getAllMetricsJson()
 * ```
 */
class SystemMetricsProvider(private val context: Context) {

    companion object {
        private const val TAG = "SystemMetrics"
    }

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val runtime = Runtime.getRuntime()

    // CPU tracking - process-level (app CPU)
    private var lastProcessCpuTime = 0L
    private var lastProcessWallTime = 0L
    private var lastAppCpuPercent = 0
    private var cpuCores = Runtime.getRuntime().availableProcessors()

    // CPU tracking - system-wide (total CPU from /proc/stat)
    private var lastSystemCpuTotal = 0L
    private var lastSystemCpuIdle = 0L
    private var lastSystemCpuPercent = 0
    private var systemCpuAvailable = true  // /proc/stat may be restricted on some devices

    /**
     * Get system-wide CPU usage percentage (0-100).
     * Reads from /proc/stat which is available on Fire OS and many Android devices.
     * Falls back to app-only CPU if /proc/stat is restricted.
     */
    fun getCpuPercent(): Int {
        if (systemCpuAvailable) {
            val systemCpu = getSystemCpuPercent()
            if (systemCpu >= 0) return systemCpu
        }
        // Fallback to app-only CPU
        return getAppCpuPercent()
    }

    /**
     * Get system-wide CPU usage from /proc/stat.
     * Returns -1 if not available.
     */
    private fun getSystemCpuPercent(): Int {
        return try {
            val stat = File("/proc/stat").readText()
            val cpuLine = stat.lineSequence().first { it.startsWith("cpu ") }
            val parts = cpuLine.trim().split("\\s+".toRegex())
            // cpu user nice system idle iowait irq softirq steal guest guest_nice
            if (parts.size < 5) return -1

            val user = parts[1].toLongOrNull() ?: 0L
            val nice = parts[2].toLongOrNull() ?: 0L
            val system = parts[3].toLongOrNull() ?: 0L
            val idle = parts[4].toLongOrNull() ?: 0L
            val iowait = if (parts.size > 5) parts[5].toLongOrNull() ?: 0L else 0L
            val irq = if (parts.size > 6) parts[6].toLongOrNull() ?: 0L else 0L
            val softirq = if (parts.size > 7) parts[7].toLongOrNull() ?: 0L else 0L
            val steal = if (parts.size > 8) parts[8].toLongOrNull() ?: 0L else 0L

            val totalCpu = user + nice + system + idle + iowait + irq + softirq + steal
            val totalIdle = idle + iowait

            if (lastSystemCpuTotal > 0) {
                val totalDelta = totalCpu - lastSystemCpuTotal
                val idleDelta = totalIdle - lastSystemCpuIdle

                if (totalDelta > 0) {
                    lastSystemCpuPercent = (((totalDelta - idleDelta) * 100) / totalDelta).toInt().coerceIn(0, 100)
                }
            }

            lastSystemCpuTotal = totalCpu
            lastSystemCpuIdle = totalIdle

            lastSystemCpuPercent
        } catch (e: Exception) {
            Log.d(TAG, "/proc/stat not available: ${e.message}")
            systemCpuAvailable = false
            -1
        }
    }

    /**
     * Get current CPU usage percentage (0-100) for our process only.
     * Uses /proc/self/stat which is always accessible to apps.
     */
    fun getAppCpuPercent(): Int {
        return try {
            val processCpuTime = getProcessCpuTime()
            val currentTime = System.nanoTime()

            if (lastProcessCpuTime > 0 && lastProcessWallTime > 0) {
                val cpuTimeDelta = processCpuTime - lastProcessCpuTime
                val systemTimeDelta = (currentTime - lastProcessWallTime) / 1_000_000L

                if (systemTimeDelta > 0) {
                    val cpuMs = cpuTimeDelta * 10 // jiffies to ms (assuming 100 Hz)
                    lastAppCpuPercent = ((cpuMs * 100) / systemTimeDelta).toInt().coerceIn(0, 100 * cpuCores) / cpuCores
                    lastAppCpuPercent = lastAppCpuPercent.coerceIn(0, 100)
                }
            }

            lastProcessCpuTime = processCpuTime
            lastProcessWallTime = currentTime

            lastAppCpuPercent
        } catch (e: Exception) {
            Log.w(TAG, "Error reading app CPU stats: ${e.message}")
            if (lastAppCpuPercent > 0) lastAppCpuPercent else -1
        }
    }

    /**
     * Read process CPU time from /proc/self/stat.
     * Returns sum of utime and stime (user + system time) in jiffies.
     */
    private fun getProcessCpuTime(): Long {
        return try {
            val stat = File("/proc/self/stat").readText()
            val parts = stat.split(" ")
            if (parts.size > 14) {
                val utime = parts[13].toLongOrNull() ?: 0L
                val stime = parts[14].toLongOrNull() ?: 0L
                utime + stime
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Get system RAM usage percentage (0-100).
     */
    fun getRamPercent(): Int {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val used = memInfo.totalMem - memInfo.availMem
        return ((used * 100) / memInfo.totalMem).toInt()
    }

    /**
     * Get detailed RAM information in MB.
     */
    fun getRamDetails(): RamDetails {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        return RamDetails(
            totalMB = (memInfo.totalMem / 1024 / 1024).toInt(),
            usedMB = ((memInfo.totalMem - memInfo.availMem) / 1024 / 1024).toInt(),
            availableMB = (memInfo.availMem / 1024 / 1024).toInt(),
            isLowMemory = memInfo.lowMemory,
            lowMemoryThresholdMB = (memInfo.threshold / 1024 / 1024).toInt()
        )
    }

    /**
     * Get JVM heap usage percentage (0-100).
     */
    fun getHeapPercent(): Int {
        val maxHeap = runtime.maxMemory()
        val usedHeap = runtime.totalMemory() - runtime.freeMemory()
        return if (maxHeap > 0) ((usedHeap * 100) / maxHeap).toInt() else 0
    }

    /**
     * Get detailed JVM heap information in MB.
     */
    fun getHeapDetails(): HeapDetails {
        val maxHeap = runtime.maxMemory() / 1024 / 1024
        val totalHeap = runtime.totalMemory() / 1024 / 1024
        val freeHeap = runtime.freeMemory() / 1024 / 1024
        val usedHeap = totalHeap - freeHeap

        return HeapDetails(
            maxMB = maxHeap.toInt(),
            usedMB = usedHeap.toInt(),
            freeMB = freeHeap.toInt(),
            percentUsed = if (maxHeap > 0) ((usedHeap * 100) / maxHeap).toInt() else 0
        )
    }

    /**
     * Get Native Heap usage in MB.
     * Native Heap is used by OkHttp, Bitmap decoding, JNI, etc.
     * This is separate from Java Heap and is where image processing memory goes.
     */
    fun getNativeHeapMB(): Int {
        return (Debug.getNativeHeapAllocatedSize() / 1024 / 1024).toInt()
    }

    /**
     * Get PSS (Proportional Set Size) in MB for main process only.
     * For total app memory including WebView renderer, use getPssBreakdown().
     *
     * Note: We use Debug.getMemoryInfo() instead of ActivityManager.getProcessMemoryInfo()
     * because the latter is rate-limited by Android (returns cached values for ~5 minutes).
     */
    fun getPssMB(): Int {
        return try {
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            memInfo.totalPss / 1024  // PSS is in KB, convert to MB
        } catch (e: Exception) {
            Log.w(TAG, "Error getting PSS: ${e.message}")
            -1
        }
    }

    /**
     * Get PSS breakdown including WebView renderer process.
     * This is the metric that properly reflects WebView/GPU memory leaks.
     *
     * Uses MemoryMonitor.getFreshTotalPssMb() which reads both main and renderer
     * PSS fresh (not from cache). Note that renderer PSS via ActivityManager
     * may be rate-limited to ~5 min cache by Android.
     */
    fun getPssBreakdown(): PssBreakdown {
        // Trigger fresh reading of both main and renderer PSS
        val totalPss = MemoryMonitor.getFreshTotalPssMb()

        // Get the individual values that were just updated
        val mainPss = MemoryMonitor.getMainPssMb()
        val rendererPss = MemoryMonitor.getRendererPssMb()

        return PssBreakdown(
            mainMb = mainPss,
            rendererMb = rendererPss,
            totalMb = totalPss
        )
    }

    data class PssBreakdown(
        val mainMb: Int,
        val rendererMb: Int,
        val totalMb: Int
    )

    /**
     * Get thermal status as a string (Android Q+).
     * Returns: "none", "light", "moderate", "severe", "critical", "emergency", "shutdown", or "unavailable"
     */
    fun getThermalStatus(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                when (pm.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> "OK"
                    PowerManager.THERMAL_STATUS_LIGHT -> "Warm"
                    PowerManager.THERMAL_STATUS_MODERATE -> "Hot"
                    PowerManager.THERMAL_STATUS_SEVERE -> "Very Hot"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                    else -> "?"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error getting thermal status: ${e.message}")
                "Error"
            }
        } else {
            "N/A"
        }
    }

    /**
     * Get thermal status as numeric level (0-6, -1 if unavailable).
     * Higher = hotter.
     */
    fun getThermalLevel(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.currentThermalStatus
            } catch (e: Exception) {
                -1
            }
        } else {
            -1
        }
    }

    /**
     * Get battery level (0-100) or -1 if unavailable.
     */
    fun getBatteryLevel(): Int {
        return try {
            val batteryIntent = context.registerReceiver(null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) (level * 100) / scale else -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Check if device is charging.
     */
    fun isCharging(): Boolean {
        return try {
            val batteryIntent = context.registerReceiver(null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val status = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get all metrics as a JSON string for the JS bridge.
     */
    fun getAllMetricsJson(emergencyRecoveryEnabled: Boolean = true): String {
        val ram = getRamDetails()
        val heap = getHeapDetails()
        val cpuPercent = getCpuPercent()

        return JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("cpu", JSONObject().apply {
                put("percent", cpuPercent)
                put("appPercent", getAppCpuPercent())
                put("scope", if (systemCpuAvailable) "system" else "app")
                put("cores", cpuCores)
            })
            put("ram", JSONObject().apply {
                put("totalMB", ram.totalMB)
                put("usedMB", ram.usedMB)
                put("availableMB", ram.availableMB)
                put("percent", if (ram.totalMB > 0) (ram.usedMB * 100) / ram.totalMB else 0)
                put("isLowMemory", ram.isLowMemory)
            })
            put("heap", JSONObject().apply {
                put("maxMB", heap.maxMB)
                put("usedMB", heap.usedMB)
                put("freeMB", heap.freeMB)
                put("percent", heap.percentUsed)
            })
            // Native Heap (OkHttp, Bitmap decoding, JNI allocations)
            put("nativeHeap", JSONObject().apply {
                put("usedMB", getNativeHeapMB())
            })
            // PSS breakdown (main process + WebView renderer)
            // Use MemoryMonitor's breakdown which tracks both processes
            val pssBreakdown = getPssBreakdown()
            put("pss", JSONObject().apply {
                put("mainMB", pssBreakdown.mainMb)
                put("rendererMB", pssBreakdown.rendererMb)
                put("totalMB", pssBreakdown.totalMb)
            })
            // Memory thresholds (dynamic, based on device RAM)
            val thresholds = MemoryMonitor.getThresholdInfo()
            put("memoryThresholds", JSONObject().apply {
                put("warningMB", thresholds.warningMb)
                put("criticalMB", thresholds.criticalMb)
                put("emergencyMB", thresholds.emergencyMb)
                put("recoveryMB", thresholds.recoveryMb)
                put("deviceTotalRamMB", thresholds.deviceTotalRamMb)
                put("deviceLowMemoryThresholdMB", thresholds.deviceLowMemoryThresholdMb)
                // Include whether threshold-based refresh is enabled (affects overlay display)
                put("enabled", emergencyRecoveryEnabled)
            })
            // GPU is not accessible via Android APIs - no public interface exists
            put("gpu", JSONObject().apply {
                put("available", false)
                put("reason", "No Android API for GPU usage")
            })
            put("thermal", getThermalStatus())
            put("thermalLevel", getThermalLevel())
            put("battery", JSONObject().apply {
                put("level", getBatteryLevel())
                put("isCharging", isCharging())
            })
        }.toString()
    }

    /**
     * Get a compact metrics summary string for logging.
     */
    fun getMetricsSummary(): String {
        val cpu = getCpuPercent()
        val ram = getRamPercent()
        val heap = getHeapPercent()
        val thermal = getThermalStatus()
        return "CPU=$cpu% RAM=$ram% Heap=$heap% Thermal=$thermal"
    }

    data class RamDetails(
        val totalMB: Int,
        val usedMB: Int,
        val availableMB: Int,
        val isLowMemory: Boolean,
        val lowMemoryThresholdMB: Int
    )

    data class HeapDetails(
        val maxMB: Int,
        val usedMB: Int,
        val freeMB: Int,
        val percentUsed: Int
    )
}

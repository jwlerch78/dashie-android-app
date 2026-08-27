package com.dashieapp.Dashie.halite.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Utility for detecting device capabilities and memory constraints.
 *
 * Used to adapt UI features (like camera preview quality) based on device resources.
 */
object DeviceCapabilities {
    private const val TAG = "DeviceCapabilities"

    // Memory thresholds (in MB)
    private const val LOW_MEMORY_THRESHOLD_MB = 1536  // 1.5 GB
    private const val LOW_MEMORY_CLASS_THRESHOLD = 192  // 192 MB per-app limit

    /**
     * Check if this is a low-memory device.
     *
     * Returns true if any of these conditions are met:
     * - Total RAM < 2 GB
     * - Android Go edition
     * - Low memory class (< 192 MB per app)
     * - System reports isLowRamDevice = true
     */
    fun isLowMemoryDevice(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        // Check 1: System-reported low RAM flag
        if (activityManager.isLowRamDevice) {
            Log.i(TAG, "Device identified as low RAM by system")
            return true
        }

        // Check 2: Android Go edition
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            if (activityManager.isLowRamDevice) {
                Log.i(TAG, "Device is Android Go edition")
                return true
            }
        }

        // Check 3: Total device RAM (increased threshold to 2GB to catch ONN stick at 1.4GB)
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalRamMB = memInfo.totalMem / (1024 * 1024)
        if (totalRamMB < 2048) {  // 2GB threshold
            Log.i(TAG, "Device has low total RAM: ${totalRamMB}MB (threshold: 2048MB)")
            return true
        }

        // Check 4: Memory class (per-app heap limit)
        val memoryClass = activityManager.memoryClass
        if (memoryClass < LOW_MEMORY_CLASS_THRESHOLD) {
            Log.i(TAG, "Device has low memory class: ${memoryClass}MB (threshold: ${LOW_MEMORY_CLASS_THRESHOLD}MB)")
            return true
        }

        Log.i(TAG, "Device capabilities: RAM=${totalRamMB}MB, memoryClass=${memoryClass}MB, lowRam=${activityManager.isLowRamDevice}")
        return false
    }

    /**
     * Check if camera preview should be disabled due to critically low available memory.
     *
     * Returns true if available RAM < 400MB (system is under memory pressure).
     */
    fun shouldDisableCameraPreview(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availableMB = memInfo.availMem / (1024 * 1024)

        if (availableMB < 400) {
            Log.i(TAG, "Camera preview disabled - critically low available RAM: ${availableMB}MB")
            return true
        }

        return false
    }

    /**
     * Get current memory info for logging/diagnostics.
     */
    fun getMemoryInfo(context: Context): MemorySnapshot {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val runtime = Runtime.getRuntime()

        return MemorySnapshot(
            totalRamMB = memInfo.totalMem / (1024 * 1024),
            availableRamMB = memInfo.availMem / (1024 * 1024),
            lowMemory = memInfo.lowMemory,
            threshold = memInfo.threshold / (1024 * 1024),
            heapUsedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
            heapMaxMB = runtime.maxMemory() / (1024 * 1024),
            memoryClass = activityManager.memoryClass,
            isLowRamDevice = activityManager.isLowRamDevice
        )
    }

    /**
     * Log current memory state to PersistentLog.
     */
    fun logMemoryState(context: Context, tag: String = "MEMORY") {
        val info = getMemoryInfo(context)
        PersistentLog.info(tag, info.toString())
    }

    data class MemorySnapshot(
        val totalRamMB: Long,
        val availableRamMB: Long,
        val lowMemory: Boolean,
        val threshold: Long,
        val heapUsedMB: Long,
        val heapMaxMB: Long,
        val memoryClass: Int,
        val isLowRamDevice: Boolean
    ) {
        override fun toString(): String {
            return "RAM: ${availableRamMB}/${totalRamMB}MB available, " +
                   "Heap: ${heapUsedMB}/${heapMaxMB}MB, " +
                   "MemClass: ${memoryClass}MB, " +
                   "LowRam: $isLowRamDevice, " +
                   "LowMemFlag: $lowMemory"
        }
    }
}

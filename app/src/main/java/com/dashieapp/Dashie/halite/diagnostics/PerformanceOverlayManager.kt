package com.dashieapp.Dashie.halite.diagnostics

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import com.dashieapp.Dashie.halite.HalitePreferences
import java.nio.ByteBuffer

/**
 * Manages the performance overlay lifecycle and metric updates.
 *
 * The overlay is rendered in the Kotlin layer as an Activity-level view,
 * rather than in the WebView iframe, which avoids click-through issues
 * with Home Assistant's Shadow DOM.
 *
 * Now uses Activity-level views (ViewGroup) instead of WindowManager overlays,
 * eliminating the need for SYSTEM_ALERT_WINDOW permission.
 */
class PerformanceOverlayManager(
    private val context: Context,
    private val overlayContainer: ViewGroup,
    private val prefs: HalitePreferences
) {
    companion object {
        private const val TAG = "PerfOverlayMgr"
        private const val UPDATE_INTERVAL_MS = 1000L // 1 Hz updates
    }

    private var overlayView: PerformanceOverlayView? = null
    private val metricsProvider = SystemMetricsProvider(context)
    private val handler = Handler(Looper.getMainLooper())

    // Memory pressure test allocations (native memory, not Java heap)
    // Uses ByteBuffer.allocateDirect() to simulate WebView/GPU memory pressure
    private val testAllocations = mutableListOf<ByteBuffer>()

    // Cooldown for threshold triggers (prevent rapid repeated reloads)
    private var lastCriticalTriggerTime = 0L
    private var lastIdleTriggerTime = 0L
    private val CRITICAL_COOLDOWN_MS = 60_000L  // 60 seconds between critical triggers
    private val IDLE_COOLDOWN_MS = 30_000L      // 30 seconds between idle triggers (allows screensaver to activate)

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateMetrics()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    /**
     * Show the performance overlay
     */
    fun show() {
        if (overlayView != null) {
            Log.w(TAG, "Performance overlay already visible")
            return
        }

        Log.i(TAG, "📊 Showing performance overlay at position: ${prefs.performance.performanceOverlayPosition}")

        overlayView = PerformanceOverlayView(
            context = context,
            overlayContainer = overlayContainer,
            onClose = ::hide,
            initialPosition = prefs.performance.performanceOverlayPosition
        )

        try {
            // Add to overlay container (no SYSTEM_ALERT_WINDOW permission needed!)
            overlayContainer.addView(overlayView, overlayView!!.cardLayoutParams)

            // Set threshold tick marks from user preferences
            refreshThresholds()

            // Set total RAM capacity in the label
            val totalRamMb = metricsProvider.getRamDetails().totalMB
            overlayView?.setTotalRamMb(totalRamMb)

            startUpdates()
            prefs.performance.performanceOverlayEnabled = true
            Log.i(TAG, "📊 Performance overlay added to overlay container")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to add performance overlay to container", e)
            overlayView = null
        }
    }

    /**
     * Update the threshold tick marks on the overlay bars.
     * Called when overlay is shown or when thresholds change.
     */
    fun refreshThresholds() {
        val view = overlayView ?: return

        // User preference thresholds (for tick marks - may be 0 if disabled)
        val ramIdlePct = prefs.performance.idleRamPercent
        val ramCriticalPct = prefs.performance.criticalRamPercent

        // Whether threshold-based refresh is enabled (controls tick mark visibility)
        val enabled = prefs.performance.emergencyRecoveryEnabled

        Log.d(TAG, "📊 Setting threshold markers: RAM idle=$ramIdlePct% critical=$ramCriticalPct%, Heap idle=${prefs.performance.idleHeapPercent}% critical=${prefs.performance.criticalHeapPercent}%, enabled=$enabled")

        view.setThresholds(ramIdlePct, ramCriticalPct, prefs.performance.idleHeapPercent, prefs.performance.criticalHeapPercent, enabled)
    }

    /**
     * Hide the performance overlay
     */
    fun hide() {
        val view = overlayView ?: run {
            Log.w(TAG, "Performance overlay not visible")
            return
        }

        Log.i(TAG, "📊 Hiding performance overlay")

        stopUpdates()

        try {
            view.destroy()
            overlayContainer.removeView(view)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to remove performance overlay from container", e)
        }

        overlayView = null
        prefs.performance.performanceOverlayEnabled = false
    }

    /**
     * Toggle the overlay visibility
     */
    fun toggle() {
        if (overlayView != null) {
            hide()
        } else {
            show()
        }
    }

    /**
     * Check if the overlay is currently visible
     */
    fun isVisible(): Boolean = overlayView != null

    /**
     * Start periodic metric updates
     */
    private fun startUpdates() {
        handler.post(updateRunnable)
    }

    /**
     * Stop periodic metric updates
     */
    private fun stopUpdates() {
        handler.removeCallbacks(updateRunnable)
    }

    /**
     * Update the overlay with current metrics.
     * Also triggers threshold check every second (not just every 30s from MemoryMonitor).
     */
    private fun updateMetrics() {
        val view = overlayView ?: return

        try {
            val cpu = metricsProvider.getCpuPercent()
            val ram = metricsProvider.getRamPercent()
            // Use total PSS (main + renderer) to properly track WebView memory leaks
            val pssBreakdown = metricsProvider.getPssBreakdown()
            val pssMB = pssBreakdown.totalMb

            // Debug: Log if renderer PSS is detected (helps diagnose Fire tablet issue)
            if (pssBreakdown.rendererMb == 0 && ram > 50) {
                Log.d(TAG, "📊 PSS: main=${pssBreakdown.mainMb}MB, renderer=0 (not detected?), RAM=$ram%")
            }

            val heapPercent = metricsProvider.getHeapPercent()
            view.updateMetrics(cpu, ram, pssMB, heapPercent)

            // Also check thresholds every second (overlay runs at 1Hz, MemoryMonitor at 30s)
            // This ensures fast response when critical thresholds are exceeded
            checkThresholdsNow(ram, heapPercent)
        } catch (e: Exception) {
            Log.w(TAG, "Error updating metrics: ${e.message}")
        }
    }

    /**
     * Check thresholds immediately (called every 1 second from overlay update).
     * This supplements the 30-second MemoryMonitor check for faster response.
     *
     * Checks both CRITICAL and IDLE thresholds:
     * - CRITICAL (92%): Immediate reload, 60-second cooldown
     * - IDLE (85%): Reload only during screensaver (handled by callback), 30-second cooldown
     */
    private fun checkThresholdsNow(ramPercent: Int, heapPercent: Int) {
        val now = System.currentTimeMillis()

        // Get threshold values from prefs
        val criticalRamPct = prefs.performance.criticalRamPercent
        val criticalHeapPct = prefs.performance.criticalHeapPercent
        val idleRamPct = prefs.performance.idleRamPercent
        val idleHeapPct = prefs.performance.idleHeapPercent

        // Check CRITICAL threshold first (takes precedence)
        val criticalRamTriggered = criticalRamPct > 0 && ramPercent >= criticalRamPct
        val criticalHeapTriggered = criticalHeapPct > 0 && heapPercent >= criticalHeapPct

        if (criticalRamTriggered || criticalHeapTriggered) {
            if (prefs.performance.emergencyRecoveryEnabled) {
                // Check cooldown to prevent rapid repeated triggers
                if (now - lastCriticalTriggerTime < CRITICAL_COOLDOWN_MS) {
                    return // Still in cooldown
                }
                lastCriticalTriggerTime = now

                val trigger = when {
                    criticalRamTriggered && criticalHeapTriggered -> "RAM=${ramPercent}% AND Heap=${heapPercent}%"
                    criticalRamTriggered -> "RAM=${ramPercent}% >= ${criticalRamPct}%"
                    else -> "Heap=${heapPercent}% >= ${criticalHeapPct}%"
                }
                Log.e(TAG, "🚨 CRITICAL (overlay check): $trigger! Triggering immediate recovery")
                MemoryMonitor.triggerCriticalRecovery()
            }
            return // Don't also trigger idle if critical
        }

        // Check IDLE threshold (only if not already critical)
        val idleRamTriggered = idleRamPct > 0 && ramPercent >= idleRamPct
        val idleHeapTriggered = idleHeapPct > 0 && heapPercent >= idleHeapPct

        if (idleRamTriggered || idleHeapTriggered) {
            // Check cooldown for idle triggers (30s to allow screensaver activation check)
            if (now - lastIdleTriggerTime < IDLE_COOLDOWN_MS) {
                return // Still in cooldown
            }
            lastIdleTriggerTime = now

            val trigger = when {
                idleRamTriggered && idleHeapTriggered -> "RAM=${ramPercent}% AND Heap=${heapPercent}%"
                idleRamTriggered -> "RAM=${ramPercent}% >= ${idleRamPct}%"
                else -> "Heap=${heapPercent}% >= ${idleHeapPct}%"
            }
            Log.w(TAG, "⚠️ IDLE (overlay check): $trigger - triggering screensaver check")
            // Delegate to MemoryMonitor - callback will check if screensaver is active
            MemoryMonitor.triggerIdleRecovery()
        }
    }

    /**
     * Initialize overlay if it was previously enabled
     */
    fun initializeFromPrefs() {
        if (prefs.performance.performanceOverlayEnabled) {
            Log.i(TAG, "📊 Restoring performance overlay from prefs")
            show()
        }
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        releaseTestMemory()
        hide()
    }

    // ========== Memory Pressure Test Controls ==========

    // Chunk size for allocations (10MB is safer for low-memory devices)
    private val CHUNK_SIZE_MB = 10
    private val CHUNK_SIZE_BYTES = CHUNK_SIZE_MB * 1024 * 1024

    /**
     * Allocate NATIVE memory in 10MB chunks to simulate WebView/GPU memory pressure.
     * Uses ByteBuffer.allocateDirect() which bypasses Java heap and allocates
     * directly from system RAM - this is how WebView GPU textures consume memory.
     * Multiple presses accumulate.
     */
    private fun allocateChunks(targetMB: Int) {
        val chunksNeeded = targetMB / CHUNK_SIZE_MB
        var allocated = 0

        for (i in 0 until chunksNeeded) {
            try {
                // allocateDirect() allocates native memory, not Java heap
                val allocation = ByteBuffer.allocateDirect(CHUNK_SIZE_BYTES)
                // Touch the memory to ensure it's actually committed by the OS
                for (j in 0 until CHUNK_SIZE_BYTES step 4096) {
                    allocation.put(j, 1)
                }
                testAllocations.add(allocation)
                allocated += CHUNK_SIZE_MB
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "📊 Test: Native OOM after allocating ${allocated}MB of ${targetMB}MB")
                break
            }
        }

        val totalMB = testAllocations.size * CHUNK_SIZE_MB
        Log.i(TAG, "📊 Test: Allocated +${allocated}MB native memory (total: ${totalMB}MB in ${testAllocations.size} chunks)")
    }

    /**
     * Allocate 50MB of memory for testing memory pressure handling
     */
    private fun allocate50MB() {
        allocateChunks(50)
    }

    /**
     * Allocate 100MB of memory for testing memory pressure handling
     */
    private fun allocate100MB() {
        allocateChunks(100)
    }

    /**
     * Release all test memory allocations.
     * Note: Direct ByteBuffers are released when GC collects them, not immediately.
     * We clear references and trigger GC to encourage release.
     */
    private fun releaseTestMemory() {
        val totalMB = testAllocations.size * CHUNK_SIZE_MB
        val count = testAllocations.size
        // Clear each buffer to help GC identify them as unused
        testAllocations.forEach { it.clear() }
        testAllocations.clear()
        // Multiple GC passes help release native memory
        System.gc()
        System.runFinalization()
        System.gc()
        Log.i(TAG, "📊 Test: Released ${totalMB}MB native memory ($count chunks), triggered GC")
    }

    /**
     * Trigger aggressive memory recovery (GC + native trim)
     */
    private fun triggerMemoryRecovery() {
        Log.i(TAG, "📊 Test: Triggering memory recovery...")

        // Release test allocations first
        releaseTestMemory()

        // Multiple GC passes for thorough collection
        repeat(3) {
            System.gc()
            System.runFinalization()
        }

        Log.i(TAG, "📊 Test: Memory recovery triggered (released + GC x3)")
    }
}

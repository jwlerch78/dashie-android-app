package com.dashieapp.Dashie.halite

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer

/**
 * Manages WiFi lock and CPU wake lock to prevent Android from dropping WiFi
 * or killing the process during Doze mode.
 *
 * The two locks are controlled independently:
 *
 * - **WiFi lock**: WIFI_MODE_FULL_LOW_LATENCY on Android 10+ (API 29), falling back to
 *   WIFI_MODE_FULL_HIGH_PERF on older versions. Honors the Performance → WiFi Lock toggle:
 *   on some budget WiFi chipsets, holding the radio out of power-save for hours can wedge
 *   the driver (WiFi drops with a bogus "wrong password" until an airplane-mode reset),
 *   so users must be able to turn this off.
 * - **CPU wake lock**: PARTIAL_WAKE_LOCK keeps the process alive during screen-off on
 *   aggressive OEMs (Fire OS, MIUI). Always held while the dashboard runs, independent
 *   of the WiFi Lock toggle.
 *
 * Usage:
 * - Call applyWifiLockPreference() at startup and whenever the toggle changes
 * - Call release() on teardown to drop both locks
 */
class WifiLockManager(private val context: Context) {

    companion object {
        private const val TAG = "WifiLockManager"
        private const val DIAG_TAG = "NETWORK"
        private const val WIFI_LOCK_TAG = "DashieLite:WifiLock"
        private const val CPU_LOCK_TAG = "DashieLite:CpuKeepAlive"
    }

    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private val wifiManager: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }
    private val powerManager: PowerManager? by lazy {
        context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    /**
     * Apply the Performance → WiFi Lock preference. The CPU keep-alive lock is always
     * acquired regardless of the preference — it guards against process-kill, not the radio.
     */
    @Synchronized
    fun applyWifiLockPreference(enabled: Boolean) {
        acquireCpuLock()
        if (enabled) acquireWifiLock() else releaseWifiLock()
    }

    /**
     * Release both WiFi lock and CPU wake lock.
     * Safe to call multiple times or when locks are not held.
     */
    @Synchronized
    fun release() {
        releaseWifiLock()
        releaseCpuLock()
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            @Suppress("DEPRECATION")
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }

            wifiLock = wifiManager?.createWifiLock(lockMode, WIFI_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
            val modeName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "LOW_LATENCY" else "HIGH_PERF"
            Log.i(TAG, "WiFi lock acquired (mode=$modeName)")
            DiagnosticBuffer.info(DIAG_TAG, "WiFi lock acquired (mode=$modeName)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WiFi lock: ${e.message}")
            DiagnosticBuffer.error(DIAG_TAG, "Failed to acquire WiFi lock: ${e.message}")
        }
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.i(TAG, "WiFi lock released")
                DiagnosticBuffer.info(DIAG_TAG, "WiFi lock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WiFi lock: ${e.message}")
        }
        wifiLock = null
    }

    private fun acquireCpuLock() {
        if (cpuWakeLock?.isHeld == true) return
        try {
            cpuWakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                CPU_LOCK_TAG
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "CPU wake lock acquired (PARTIAL_WAKE_LOCK)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire CPU wake lock: ${e.message}")
        }
    }

    private fun releaseCpuLock() {
        try {
            if (cpuWakeLock?.isHeld == true) {
                cpuWakeLock?.release()
                Log.i(TAG, "CPU wake lock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release CPU wake lock: ${e.message}")
        }
        cpuWakeLock = null
    }

    /**
     * Check if WiFi lock is currently held
     */
    val isHeld: Boolean
        get() = wifiLock?.isHeld == true

    /**
     * Check if CPU wake lock is currently held
     */
    val isCpuLockHeld: Boolean
        get() = cpuWakeLock?.isHeld == true
}

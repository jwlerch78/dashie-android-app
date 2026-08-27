package com.dashieapp.Dashie.halite.music.sendspin

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog

/**
 * Owns the WiFi + CPU wake locks for the Sendspin (Music Assistant) foreground service.
 * Extracted from SendspinPlayerService to keep it under the file-size budget.
 *
 * - **WiFi lock**: honors the shared Performance → WiFi Lock toggle
 *   (dashie_lite_prefs / wifi_lock_enabled, default true) so a Music Assistant user can turn
 *   OFF the aggressive radio lock that wedges budget WiFi chipsets — the reported symptom is
 *   WiFi dropping with a bogus "wrong password" for hours until an airplane-mode reset. Uses
 *   WIFI_MODE_FULL_LOW_LATENCY on API 29+ (gentler on the driver than the legacy HIGH_PERF),
 *   matching [com.dashieapp.Dashie.halite.WifiLockManager]. Before July 2026 this lock was
 *   always-on HIGH_PERF and ignored the toggle, so a MA user had no way to relieve the wedge.
 * - **CPU wake lock**: always held for the service lifetime (guards the reconnect coroutine +
 *   ping scheduler from Doze), independent of the toggle.
 */
class SendspinLockManager(private val context: Context) {

    companion object {
        private const val TAG = "SendspinService"
        private const val PERF_PREFS_NAME = "dashie_lite_prefs"
        private const val KEY_WIFI_LOCK_ENABLED = "wifi_lock_enabled"
    }

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Reads the shared Performance → WiFi Lock toggle (default true, matching PerformancePreferences). */
    private fun isWifiLockEnabledPref(): Boolean =
        context.getSharedPreferences(PERF_PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIFI_LOCK_ENABLED, true)

    fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        if (!isWifiLockEnabledPref()) {
            Log.i(TAG, "WiFi lock skipped — Performance WiFi Lock disabled")
            PersistentLog.info("SENDSPIN", "WiFi lock skipped — Performance WiFi Lock disabled")
            return
        }
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            val lock = wifiManager.createWifiLock(mode, "Dashie:Sendspin")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
            val modeName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "LOW_LATENCY" else "HIGH_PERF"
            Log.i(TAG, "WiFi lock acquired ($modeName)")
            PersistentLog.info("SENDSPIN", "WiFi lock acquired ($modeName)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WiFi lock: ${e.message}")
            PersistentLog.warn("SENDSPIN", "Failed to acquire WiFi lock: ${e.message}")
        }
    }

    fun releaseWifiLock() {
        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
                Log.i(TAG, "WiFi lock released")
                PersistentLog.info("SENDSPIN", "WiFi lock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WiFi lock: ${e.message}")
        }
        wifiLock = null
    }

    /** Apply a live change to the Performance WiFi Lock toggle without a service restart. */
    fun applyWifiLockPreference(enabled: Boolean) {
        if (enabled) acquireWifiLock() else releaseWifiLock()
    }

    fun acquireWakeLock() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Dashie:SendspinService")
            lock.setReferenceCounted(false)
            lock.acquire()
            wakeLock = lock
            Log.i(TAG, "Sendspin PARTIAL_WAKE_LOCK acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire Sendspin wake lock: ${e.message}")
            PersistentLog.warn("SENDSPIN", "Failed to acquire wake lock: ${e.message}")
        }
    }

    fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
                Log.i(TAG, "Sendspin wake lock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release Sendspin wake lock: ${e.message}")
        }
        wakeLock = null
    }

    /** Release both locks on service teardown. */
    fun releaseAll() {
        releaseWifiLock()
        releaseWakeLock()
    }
}

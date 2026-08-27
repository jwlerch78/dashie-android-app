package com.dashieapp.Dashie.halite.diagnostics

import android.os.SystemClock
import android.util.Log

/**
 * Lightweight cold-start performance instrumentation for the Kotlin side.
 *
 * Records timestamped marks during startup and writes them to both Logcat
 * (tag "DashieBoot", which can be filtered alongside the JS-side "BootPerf"
 * console marks) and PersistentLog (so they survive across runs and can be
 * pulled via run-as).
 *
 * Usage:
 *   BootPerf.start()                   // record T0 — call as early as possible
 *   BootPerf.mark("onCreate:enter")
 *   ...
 *   BootPerf.mark("setContentView:after")
 *
 * To capture: `adb logcat -c && launch app && adb logcat -d | grep -E "DashieBoot|BootPerf"`
 *
 * Overhead is intentionally minimal (one elapsedRealtime() call + Log.i +
 * PersistentLog.info) so it can stay enabled in beta builds.
 */
object BootPerf {
    private const val TAG = "DashieBoot"

    @Volatile
    private var t0Millis: Long = 0L

    @Volatile
    private var lastMarkMillis: Long = 0L

    /**
     * Record T0. Subsequent marks log time elapsed since this point.
     * Called from MainActivity.onCreate() before any other work.
     */
    fun start() {
        t0Millis = SystemClock.elapsedRealtime()
        lastMarkMillis = t0Millis
        Log.i(TAG, "[+0ms] (Δ0ms) boot-perf-start")
        // PersistentLog may not yet be initialized at this point — guard against
        // that. Once it is, callers can re-mark anything they care about.
        try {
            PersistentLog.info(TAG, "[+0ms] (Δ0ms) boot-perf-start")
        } catch (_: Throwable) {
            // PersistentLog not yet initialized; harmless.
        }
    }

    /**
     * Record a boot perf mark.
     * @param label short identifier (kebab-case preferred)
     */
    fun mark(label: String) {
        val now = SystemClock.elapsedRealtime()
        if (t0Millis == 0L) {
            // start() was never called — record T0 here so we still get useful data.
            t0Millis = now
            lastMarkMillis = now
        }
        val elapsed = now - t0Millis
        val delta = now - lastMarkMillis
        lastMarkMillis = now

        val line = "[+${elapsed}ms] (Δ${delta}ms) $label"
        Log.i(TAG, line)
        try {
            PersistentLog.info(TAG, line)
        } catch (_: Throwable) {
            // PersistentLog not yet initialized; the Logcat line is sufficient.
        }
    }

    /**
     * Convenience: mark with an extra detail string.
     */
    fun mark(label: String, detail: String) {
        mark("$label — $detail")
    }

    /**
     * Time a block of code and emit start/end marks around it.
     */
    inline fun <T> timed(label: String, block: () -> T): T {
        mark("$label:start")
        try {
            return block()
        } finally {
            mark("$label:end")
        }
    }

    /**
     * Elapsed milliseconds since T0. Useful for ad-hoc deltas
     * (e.g. dispatchKeyEvent processing time).
     */
    fun elapsedMs(): Long {
        if (t0Millis == 0L) return 0L
        return SystemClock.elapsedRealtime() - t0Millis
    }
}

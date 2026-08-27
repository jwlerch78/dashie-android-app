package com.dashieapp.Dashie.halite.settings

import android.util.Log

/**
 * Lightweight profiler for measuring settings UI performance on low-end devices.
 * All output goes to logcat with tag "SettingsProfiler".
 *
 * Usage:
 *   val token = SettingsProfiler.start("onCreate")
 *   // ... work ...
 *   SettingsProfiler.end(token)
 *
 * Remove this class (and all call sites) before production release.
 */
object SettingsProfiler {
    private const val TAG = "SettingsProfiler"
    private const val SLOW_THRESHOLD_MS = 16 // one frame at 60fps

    data class Token(val label: String, val startNanos: Long)

    /** Start timing a labeled section. */
    fun start(label: String): Token {
        return Token(label, System.nanoTime())
    }

    /** End timing and log the result. Warns if > 16ms (one frame). */
    fun end(token: Token) {
        val elapsedMs = (System.nanoTime() - token.startNanos) / 1_000_000.0
        val flag = if (elapsedMs > SLOW_THRESHOLD_MS) " ⚠️ SLOW" else ""
        Log.d(TAG, "%-45s %8.2f ms%s".format(token.label, elapsedMs, flag))
    }

    /** Log a one-off measurement with context. */
    fun log(message: String) {
        Log.d(TAG, message)
    }

    /** Measure a block inline and return its result. */
    inline fun <T> measure(label: String, block: () -> T): T {
        val token = start(label)
        val result = block()
        end(token)
        return result
    }
}

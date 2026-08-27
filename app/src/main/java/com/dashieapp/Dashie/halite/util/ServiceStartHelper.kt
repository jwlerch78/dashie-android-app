package com.dashieapp.Dashie.halite.util

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Helpers for starting services that promote themselves to foreground, without
 * tripping the ~5s startForegroundService() → startForeground() deadline.
 *
 * Crash data (June 2026, 1.0.8–1.0.12): every
 * "Context.startForegroundService() did not then call Service.startForeground()"
 * report paired with a main-thread stall on a low-RAM device — the service's
 * onCreate never ran before the deadline, so no fix inside the service can help.
 * When the app is already in the foreground (the kiosk's steady state), plain
 * startService() carries no deadline; the service still becomes a foreground
 * service the moment its own startForeground() call runs.
 */
object ServiceStartHelper {

    /** True when this process is currently the foreground app. */
    fun isAppInForeground(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** True when the last trim signal for this process was RUNNING_CRITICAL. */
    fun isMemoryCritical(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.lastTrimLevel == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
    }

    /**
     * Start a self-promoting service. Uses plain startService() when the app is
     * in the foreground (no 5s deadline); falls back to startForegroundService()
     * when backgrounded or when startService() is rejected because the app raced
     * into the background between the check and the call.
     */
    fun startForegroundAware(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            context.startService(intent)
            return
        }
        if (isAppInForeground()) {
            try {
                context.startService(intent)
                return
            } catch (_: IllegalStateException) {
                // Backgrounded mid-call — use the FGS path below.
            }
        }
        context.startForegroundService(intent)
    }
}

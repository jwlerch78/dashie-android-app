package com.dashieapp.Dashie.halite.settings

import android.util.Log

/**
 * Reachability tracer for settings pages, fragments, activities, and dialogs.
 * Emits a single tagged line each time a surface renders — exercise every path
 * from the UI, then diff emitted entries against the file inventory to find
 * unreached (suspected-dead) code.
 *
 * Logcat: `adb logcat -s SettingsTrace`
 *
 * See `.reference/build-plans/20260422_DEAD_CODE_SETTINGS_CLEANUP.md`.
 */
object SettingsTrace {
    private const val TAG = "SettingsTrace"

    fun shown(what: String) {
        Log.i(TAG, "SHOWN: $what")
    }

    /**
     * For dialog chokepoints (e.g. [com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog])
     * — reads the caller from the stack so the log identifies which dialog method
     * routed through the chokepoint without touching every call site.
     */
    fun shownFromCaller(kind: String) {
        val frame = Throwable().stackTrace.getOrNull(2)
        val where = frame?.let {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        } ?: "unknown"
        Log.i(TAG, "SHOWN: $kind @ $where")
    }
}

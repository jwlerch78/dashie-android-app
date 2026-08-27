package com.dashieapp.Dashie.halite.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Debug-only receiver to simulate a fatal uncaught exception via adb, so the
 * crash-recovery path (CrashHandler → CrashRecovery auto-relaunch + loop guard)
 * can be verified on a real device.
 *
 * Throws on the main thread to faithfully reproduce the 2026-05-28 crash class:
 * an exception that reaches Thread's default uncaught handler and kills the
 * process. It is NOT a WebView renderer crash (use ?cmd=triggerRendererCrash
 * for that) — this exercises the JVM process-death path.
 *
 * Usage:
 *   adb shell am broadcast -a com.dashieapp.TEST_CRASH -n com.dashieapp.Dashie/com.dashieapp.Dashie.halite.diagnostics.TestCrashReceiver
 *   # optional custom message:
 *   adb shell am broadcast -a com.dashieapp.TEST_CRASH -e message "simulated photo-sign timeout" -n com.dashieapp.Dashie/...TestCrashReceiver
 *
 * Lives in src/debug only — never compiled into release builds.
 */
class TestCrashReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra("message") ?: "Simulated fatal crash (TestCrashReceiver)"
        Log.w("TestCrashReceiver", "Throwing simulated uncaught exception on main thread: $message")

        // Post to the main looper so the throw propagates to the default
        // uncaught-exception handler exactly like a real fatal crash, rather
        // than being absorbed by the broadcast dispatch.
        Handler(Looper.getMainLooper()).post {
            throw RuntimeException(message)
        }
    }
}

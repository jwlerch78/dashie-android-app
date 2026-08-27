package com.dashieapp.Dashie.halite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog

/**
 * BroadcastReceiver for stealth reload alarms during screensaver.
 * Uses AlarmManager.setAndAllowWhileIdle() (or setExactAndAllowWhileIdle if permitted)
 * for reliable execution even during Doze mode.
 *
 * This replaces the Handler-based timer which doesn't fire during Doze,
 * fixing the issue where overnight refreshes during screensaver weren't happening.
 */
class StealthReloadReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "StealthReloadReceiver"
        const val ACTION_STEALTH_RELOAD = "com.dashieapp.Dashie.halite.ACTION_STEALTH_RELOAD"

        // Static reference to callback - set by HaliteScreenController
        var onStealthReloadTriggered: (() -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STEALTH_RELOAD) {
            Log.w(TAG, "🔄 Received unexpected action: ${intent.action}")
            return
        }

        Log.i(TAG, "🔄 Stealth reload alarm fired!")
        DiagnosticBuffer.info("MEMORY", "Stealth reload alarm fired")
        PersistentLog.info("MEMORY", "Stealth reload alarm fired")

        val callback = onStealthReloadTriggered
        if (callback != null) {
            Log.i(TAG, "🔄 Invoking stealth reload callback")
            callback.invoke()
        } else {
            Log.w(TAG, "🔄 No stealth reload callback registered - screen controller not initialized?")
            PersistentLog.warn("MEMORY", "No stealth reload callback - screen controller not initialized")
        }
    }
}

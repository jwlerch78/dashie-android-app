package com.dashieapp.Dashie.halite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog

/**
 * BroadcastReceiver for scheduled sleep/wake time alarms.
 * Uses AlarmManager.setAndAllowWhileIdle() for reliable execution
 * even during Doze mode and device sleep.
 *
 * This receiver handles both sleep and wake time alarms:
 * - SLEEP alarm: Triggers screenOff() to enter sleep mode
 * - WAKE alarm: Triggers screenOn() to exit sleep mode
 *
 * Unlike the JavaScript timer-based approach, AlarmManager alarms
 * fire reliably overnight even when the WebView is inactive.
 */
class SleepWakeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SleepWakeReceiver"
        const val ACTION_SLEEP = "com.dashieapp.Dashie.halite.ACTION_SCHEDULED_SLEEP"
        const val ACTION_WAKE = "com.dashieapp.Dashie.halite.ACTION_SCHEDULED_WAKE"

        // Static references to callbacks - set by HaliteScreenController
        var onSleepTriggered: (() -> Unit)? = null
        var onWakeTriggered: (() -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SLEEP -> {
                Log.i(TAG, "🌙 Scheduled SLEEP alarm fired!")
                DiagnosticBuffer.info("SLEEP", "Scheduled sleep alarm fired")
                PersistentLog.info("SLEEP", "Scheduled sleep alarm fired")

                val callback = onSleepTriggered
                if (callback != null) {
                    Log.i(TAG, "🌙 Invoking sleep callback directly")
                    callback.invoke()
                } else {
                    Log.w(TAG, "🌙 No sleep callback registered - screen controller not initialized?")
                    PersistentLog.warn("SLEEP", "No sleep callback - screen controller not initialized")
                }
            }
            ACTION_WAKE -> {
                Log.i(TAG, "☀️ Scheduled WAKE alarm fired!")
                DiagnosticBuffer.info("SLEEP", "Scheduled wake alarm fired")
                PersistentLog.info("SLEEP", "Scheduled wake alarm fired")

                val callback = onWakeTriggered
                if (callback != null) {
                    Log.i(TAG, "☀️ Invoking wake callback directly")
                    callback.invoke()
                } else {
                    Log.w(TAG, "☀️ No wake callback registered - screen controller not initialized?")
                    PersistentLog.warn("SLEEP", "No wake callback - screen controller not initialized")
                }
            }
            else -> {
                Log.w(TAG, "Received unexpected action: ${intent.action}")
            }
        }
    }
}

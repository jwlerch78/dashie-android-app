package com.dashieapp.Dashie.halite.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.dashieapp.Dashie.BuildConfig

/**
 * DEBUG-ONLY broadcast hook for the recovery test rig.
 *
 * Lets an adb script inject the failure/environment events that have no external
 * trigger (iframe error/healthy, screen sleep/wake, WebView recreation), so the
 * [com.dashieapp.Dashie.halite.DashboardHealthCoordinator] — and recovery in
 * general — can be exercised on real hardware in seconds, without a real HA outage
 * or an overnight wait.
 *
 *   adb shell am broadcast -a com.dashieapp.Dashie.TEST_INJECT --es event iframe_error
 *
 * No-ops entirely in release builds (never registered, so the action is inert).
 * See `test-rig/run-health-tests.sh`.
 */
object DebugTestInjector {
    const val ACTION = "com.dashieapp.Dashie.TEST_INJECT"
    private const val TAG = "DebugTestInjector"
    private var receiver: BroadcastReceiver? = null

    /** @param onEvent invoked with the "event" extra. Dispatched on the main thread. */
    fun register(context: Context, onEvent: (event: String, intent: Intent) -> Unit) {
        if (!BuildConfig.DEBUG || receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val event = intent?.getStringExtra("event") ?: return
                Log.i(TAG, "inject: $event")
                try {
                    onEvent(event, intent)
                } catch (e: Exception) {
                    Log.e(TAG, "inject '$event' failed: ${e.message}")
                }
            }
        }
        ContextCompat.registerReceiver(
            context, r, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED
        )
        receiver = r
        Log.i(TAG, "registered (DEBUG only)")
    }

    fun unregister(context: Context) {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }
}

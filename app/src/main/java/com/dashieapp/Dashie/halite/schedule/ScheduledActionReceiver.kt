package com.dashieapp.Dashie.halite.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires when an AlarmManager alarm for a scheduled action goes off.
 *
 * Mirrors the static-callback pattern of [com.dashieapp.Dashie.halite.SleepWakeReceiver]:
 * [ScheduledActionManager] sets [onFire] at init and owns the lifecycle; this receiver is
 * stateless and just forwards the action id.
 *
 * NOTE: if the app process was killed, [onFire] is null at fire time. Process-death
 * robustness (re-arm on boot / cold-start dispatch) is a later step; the Phase-1 demo
 * runs with the app alive.
 */
class ScheduledActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getStringExtra(EXTRA_ACTION_ID) ?: return
        Log.i(TAG, "⏰ Scheduled action fired: $id")
        val cb = onFire
        if (cb != null) {
            cb.invoke(id)
        } else {
            Log.w(TAG, "No onFire handler registered (process restarted?) — id=$id")
        }
    }

    companion object {
        private const val TAG = "ScheduledActionReceiver"
        const val ACTION_FIRE = "com.dashieapp.Dashie.halite.ACTION_SCHEDULED_ACTION"
        const val EXTRA_ACTION_ID = "action_id"

        /** Set by [ScheduledActionManager] at init; invoked when an alarm fires. */
        var onFire: ((String) -> Unit)? = null
    }
}

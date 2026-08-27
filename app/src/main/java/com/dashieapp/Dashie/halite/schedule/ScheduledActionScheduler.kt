package com.dashieapp.Dashie.halite.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Arms / cancels AlarmManager alarms for scheduled actions.
 *
 * Uses `setExactAndAllowWhileIdle` (fires through doze) keyed by action id, mirroring
 * [com.dashieapp.Dashie.halite.SleepWakeScheduler]. `SCHEDULE_EXACT_ALARM` is already
 * declared in the manifest; if exact alarms aren't permitted at runtime we fall back to
 * the inexact `setAndAllowWhileIdle`.
 */
class ScheduledActionScheduler(private val context: Context) {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun register(action: ScheduledAction) {
        val pi = pendingIntent(action.id)
        val exact = canExact()
        try {
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, action.fireAtEpochMs, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, action.fireAtEpochMs, pi)
            }
            Log.i(TAG, "Registered ${action.id} @ ${action.fireAtEpochMs} (exact=$exact)")
        } catch (se: SecurityException) {
            Log.e(TAG, "Exact alarm denied — falling back to inexact for ${action.id}", se)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, action.fireAtEpochMs, pi)
        }
    }

    fun unregister(id: String) {
        alarmManager.cancel(pendingIntent(id))
        Log.i(TAG, "Unregistered $id")
    }

    private fun canExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true

    private fun pendingIntent(id: String): PendingIntent {
        val intent = Intent(context, ScheduledActionReceiver::class.java).apply {
            action = ScheduledActionReceiver.ACTION_FIRE
            putExtra(ScheduledActionReceiver.EXTRA_ACTION_ID, id)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        // Stable request code per id so register/unregister address the same alarm.
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, flags)
    }

    companion object {
        private const val TAG = "ScheduledActionScheduler"
    }
}

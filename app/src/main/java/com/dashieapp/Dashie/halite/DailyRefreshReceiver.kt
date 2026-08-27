package com.dashieapp.Dashie.halite

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.dashieapp.Dashie.MainActivity
import com.dashieapp.Dashie.halite.diagnostics.CrashDiagnosticsCollector
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.MemoryMonitor
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog

/**
 * BroadcastReceiver for daily scheduled refresh alarms.
 * Uses AlarmManager.setExactAndAllowWhileIdle() for reliable execution
 * even during Doze mode and device sleep.
 *
 * This receiver is triggered by AlarmManager and either:
 * 1. Invokes the callback if the app is running and callback is registered
 * 2. Starts MainActivity with EXTRA_TRIGGER_DAILY_REFRESH if callback is null
 */
class DailyRefreshReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DailyRefreshReceiver"
        private const val PREFS_NAME = "dashie_lite_prefs"
        private const val KEY_DAILY_REFRESH_HOURS = "daily_refresh_hours"
        const val ACTION_DAILY_REFRESH = "com.dashieapp.Dashie.halite.ACTION_DAILY_REFRESH"
        const val EXTRA_TRIGGER_DAILY_REFRESH = "trigger_daily_refresh"

        // Static reference to the callback - set by HaliteScreenController
        var onRefreshTriggered: (() -> Unit)? = null

        /**
         * Reschedule the next daily refresh alarm directly from a Context.
         * Used by fallback paths when the ScreenRefreshScheduler instance isn't available
         * (e.g., app was OOM-killed and static callbacks are null).
         *
         * Without this, setExactAndAllowWhileIdle() one-shot alarms are lost forever
         * when the callback path can't reach scheduleNextDailyRefresh().
         */
        fun rescheduleNextAlarm(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val stored = prefs.getString(KEY_DAILY_REFRESH_HOURS, null)
                val hours = if (stored.isNullOrBlank()) {
                    // Match PerformancePreferences default: 3 AM
                    setOf(3)
                } else {
                    stored.split(",")
                        .mapNotNull { it.trim().toIntOrNull() }
                        .filter { it in 1..24 }
                        .toSet()
                }

                if (hours.isEmpty()) return

                // Calculate next alarm time (same logic as ScreenRefreshScheduler)
                val now = System.currentTimeMillis()
                val calendar = java.util.Calendar.getInstance()
                val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                val normalizedHours = hours.map { if (it == 24) 0 else it }.sorted()

                var targetHour = -1
                var addDays = 0
                for (hour in normalizedHours) {
                    if (hour > currentHour) {
                        targetHour = hour
                        break
                    }
                }
                if (targetHour == -1) {
                    targetHour = normalizedHours.first()
                    addDays = 1
                }

                val targetCalendar = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, targetHour)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                    if (addDays > 0) add(java.util.Calendar.DAY_OF_YEAR, addDays)
                }

                val triggerAtMillis = targetCalendar.timeInMillis
                if (triggerAtMillis <= now) {
                    // Edge case: bump to tomorrow
                    targetCalendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
                val finalTrigger = targetCalendar.timeInMillis

                // Schedule the alarm
                val intent = Intent(context, DailyRefreshReceiver::class.java).apply {
                    action = ACTION_DAILY_REFRESH
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, finalTrigger, pendingIntent
                    )
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, finalTrigger, pendingIntent)
                }

                // Persist state for diagnostics
                CrashDiagnosticsCollector.persistSchedulerState(context, started = true, nextAlarmMillis = finalTrigger)
                CrashDiagnosticsCollector.persistLifecycleEvent(
                    context, "refresh_rescheduled",
                    "via=receiver_fallback,next=${finalTrigger}"
                )

                val nextTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(finalTrigger))
                val delayMin = (finalTrigger - now) / 1000 / 60
                Log.i(TAG, "📅 Rescheduled next daily refresh for $nextTime (in ${delayMin} min) [fallback path]")
                PersistentLog.info("REFRESH", "Rescheduled for $nextTime (${delayMin} min) [fallback]")
            } catch (e: Exception) {
                Log.e(TAG, "📅 Failed to reschedule daily refresh: ${e.message}")
                PersistentLog.error("REFRESH", "Reschedule failed: ${e.message}")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DAILY_REFRESH) {
            Log.w(TAG, "📅 Received unexpected action: ${intent.action}")
            return
        }

        Log.i(TAG, "📅 Daily refresh alarm fired!")
        DiagnosticBuffer.info("REFRESH", "Daily refresh alarm fired")
        PersistentLog.info("REFRESH", "Daily refresh alarm fired")
        CrashDiagnosticsCollector.persistLifecycleEvent(context, "alarm_fired", "daily_refresh")

        // Delegate to the screen controller's callback (preferred path — no lifecycle disruption)
        val callback = onRefreshTriggered
        if (callback != null) {
            Log.i(TAG, "📅 Invoking refresh callback directly")
            CrashDiagnosticsCollector.persistLifecycleEvent(context, "alarm_path", "callback_direct")
            callback.invoke()
            return
        }

        // Fallback: scheduler callback not registered (e.g. app crashed and restarted but
        // startStealthReloadTimer() hasn't been called yet). Try the memory pressure callback
        // directly — same underlying path, avoids Activity lifecycle disruption on screen-off.
        // IMPORTANT: Always reschedule from here — the one-shot alarm won't repeat on its own.
        val memCallback = MemoryMonitor.onMemoryPressureCallback
        if (memCallback != null) {
            Log.i(TAG, "📅 Scheduler callback null — invoking memory pressure callback directly")
            DiagnosticBuffer.info("REFRESH", "Daily refresh via direct memory callback")
            PersistentLog.info("REFRESH", "Daily refresh via direct memory callback")
            CrashDiagnosticsCollector.persistLifecycleEvent(context, "alarm_path", "memory_callback_fallback")
            memCallback.invoke("scheduled", 0)
            CrashDiagnosticsCollector.persistRefreshEvent(context, "daily")
            rescheduleNextAlarm(context)
            return
        }

        // Last resort: app not running at all — start MainActivity with refresh flag.
        // This path causes Activity lifecycle changes; avoid when screen is off if possible.
        // Reschedule regardless — handleDailyRefreshIntent will also try, but belt-and-suspenders.
        Log.i(TAG, "📅 No callback — starting MainActivity with refresh flag")
        DiagnosticBuffer.info("REFRESH", "Starting MainActivity with refresh flag")
        PersistentLog.info("REFRESH", "Starting MainActivity with refresh flag")
        CrashDiagnosticsCollector.persistLifecycleEvent(context, "alarm_path", "mainactivity_launch_fallback")
        rescheduleNextAlarm(context)
        try {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_TRIGGER_DAILY_REFRESH, true)
            }
            context.startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "📅 Failed to start MainActivity: ${e.message}")
            PersistentLog.error("REFRESH", "Failed to start MainActivity: ${e.message}")
        }
    }
}

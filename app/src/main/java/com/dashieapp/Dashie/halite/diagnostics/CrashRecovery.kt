package com.dashieapp.Dashie.halite.diagnostics

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dashieapp.Dashie.MainActivity
import com.dashieapp.Dashie.halite.preferences.PerformancePreferences

/**
 * Relaunches the app after a fatal uncaught exception.
 *
 * The crash path ([CrashHandler]) saves a report and then chains to the default
 * handler, which kills the process — nothing brings the app back. The app's
 * other recovery features (autoReloadOnCrash → WebView reload, emergency
 * memory recovery, START_STICKY foreground services) all address in-process
 * problems and do NOT relaunch a dead process. On OEM-locked signage devices
 * with no overlay permission and no surviving foreground service, a hard crash
 * therefore leaves the device on the home screen until someone restarts it.
 *
 * This schedules a one-shot [AlarmManager] alarm to start [MainActivity] a
 * couple seconds out, then lets the process die. The alarm fires from outside
 * the dead process, so it works without overlay permission or a live service.
 *
 * Gated on the `autoReloadOnCrash` setting. A crash-rate limiter prevents a
 * deterministic crash from becoming an infinite relaunch loop: if the app has
 * crashed [MAX_CRASHES] or more times within [WINDOW_MS], the relaunch is
 * suppressed so the device comes to rest instead of boot-looping.
 */
object CrashRecovery {
    private const val TAG = "CrashRecovery"
    private const val PREFS_NAME = "dashie_crash_recovery"
    private const val KEY_CRASH_TIMESTAMPS = "crash_timestamps"

    private const val RELAUNCH_DELAY_MS = 2000L
    private const val WINDOW_MS = 5 * 60 * 1000L   // 5 minutes
    private const val MAX_CRASHES = 3              // suppress relaunch at the 3rd crash in the window
    private const val RELAUNCH_REQUEST_CODE = 0xC2A5

    /**
     * Record this crash and, if appropriate, schedule a relaunch. Must never
     * throw — it runs inside the uncaught-exception handler, which still needs
     * to chain to the default handler afterward.
     */
    fun maybeScheduleRelaunch(context: Context) {
        try {
            if (!PerformancePreferences(context).autoReloadOnCrash) {
                PersistentLog.info("CRASH-RECOVERY", "autoReloadOnCrash disabled — not relaunching")
                return
            }

            // System.currentTimeMillis() (not elapsedRealtime) so the window
            // survives the process death between crashes.
            val now = System.currentTimeMillis()
            val recent = recordCrash(context, now)

            if (recent.size >= MAX_CRASHES) {
                PersistentLog.warn(
                    "CRASH-RECOVERY",
                    "Crash loop detected (${recent.size} crashes in ${WINDOW_MS / 60000}min) — NOT relaunching"
                )
                return
            }

            scheduleRelaunch(context, now)
            PersistentLog.warn(
                "CRASH-RECOVERY",
                "Scheduled relaunch in ${RELAUNCH_DELAY_MS}ms (crash ${recent.size} in window)"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule crash relaunch", e)
        }
    }

    /** Append [now] to the persisted crash list, dropping entries older than the window. */
    private fun recordCrash(context: Context, now: Long): List<Long> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val recent = (prefs.getString(KEY_CRASH_TIMESTAMPS, "") ?: "")
            .split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { now - it < WINDOW_MS } + now
        // commit() (not apply) — the process is about to be killed.
        prefs.edit().putString(KEY_CRASH_TIMESTAMPS, recent.joinToString(",")).commit()
        return recent
    }

    private fun scheduleRelaunch(context: Context, now: Long) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            RELAUNCH_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Inexact alarm — no SCHEDULE_EXACT_ALARM permission needed, and a ~2s
        // relaunch on an always-on, interactive signage device fires promptly.
        alarmManager.set(AlarmManager.RTC, now + RELAUNCH_DELAY_MS, pendingIntent)
    }
}

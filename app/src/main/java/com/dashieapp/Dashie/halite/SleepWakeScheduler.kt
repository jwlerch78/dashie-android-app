package com.dashieapp.Dashie.halite

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.halite.preferences.SleepPreferences

/**
 * Manages sleep/wake scheduling using AlarmManager and Handler-based inactivity timer.
 *
 * Two modes:
 * 1. **Schedule mode** — Uses AlarmManager to sleep/wake at configured times (e.g., 22:00 sleep, 07:00 wake).
 *    Reliable during Doze mode. Reschedules for next day after each alarm fires.
 * 2. **Inactivity mode** — Uses Handler to sleep after a period of no user interaction.
 *    Runs via Handler since device is awake (no Doze concern). Resets on touch/key events.
 *
 * @param activity The activity context
 * @param sleepPrefs Sleep/wake preferences
 * @param onScreenOff Callback to turn screen off (delegates to HaliteScreenController.screenOff())
 * @param onScreenOn Callback to turn screen on (delegates to HaliteScreenController.screenOn())
 * @param scheduleAlarm Callback to schedule an alarm (delegates to ScreenRefreshScheduler.scheduleAlarmWithFallback())
 * @param isScreenOff Callback to check if screen is currently off
 */
class SleepWakeScheduler(
    private val activity: Context,
    private val sleepPrefs: SleepPreferences,
    private val onScreenOff: () -> Unit,
    private val onScreenOn: () -> Unit,
    private val scheduleAlarm: (triggerAtMillis: Long, pendingIntent: PendingIntent, alarmName: String) -> Unit,
    private val isScreenOff: () -> Boolean
) {
    companion object {
        private const val TAG = "HaliteScreenController"

        // resleepTimeout=0 is "Immediate" in the settings UI. A short grace keeps a wake
        // from being reverted before the person has even seen the screen.
        private const val RESLEEP_IMMEDIATE_GRACE_MS = 30_000L
    }

    // ==================== Sleep/Wake Timer (AlarmManager) ====================
    private var alarmManager: AlarmManager? = null
    private var sleepAlarmPendingIntent: PendingIntent? = null
    private var wakeAlarmPendingIntent: PendingIntent? = null

    // ==================== Sleep Inactivity Timer (Handler) ====================
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var sleepInactivityRunnable: Runnable? = null

    // ==================== Re-sleep Countdown (Handler) ====================
    private var resleepRunnable: Runnable? = null
    private var resleepArmed = false

    // ==================== Schedule-based Sleep/Wake ====================

    /**
     * Start the sleep/wake alarm scheduler.
     * Schedules native AlarmManager alarms for configured sleep/wake times.
     * This is more reliable than JS timers which don't fire during Doze mode.
     *
     * Should be called:
     * - After setup() when the screen controller initializes
     * - When sleep settings change (called from JS via bridge)
     */
    fun scheduleSleepWakeAlarms() {
        // Register callbacks for the receiver
        SleepWakeReceiver.onSleepTriggered = {
            Log.i(TAG, "🌙 Native sleep alarm triggered - calling screenOff()")
            PersistentLog.info("SLEEP", "Native sleep alarm triggered")
            onScreenOff()
            // Reschedule for next day
            scheduleSleepWakeAlarms()
        }
        SleepWakeReceiver.onWakeTriggered = {
            Log.i(TAG, "☀️ Native wake alarm triggered - calling screenOn()")
            PersistentLog.info("SLEEP", "Native wake alarm triggered")
            onScreenOn()
            // Reschedule for next day
            scheduleSleepWakeAlarms()
        }

        // Cancel existing alarms first
        cancelSleepWakeAlarms()

        // Check if sleep timer is enabled and using schedule method
        if (!sleepPrefs.sleepEnabled) {
            Log.d(TAG, "🌙 Sleep timer disabled - not scheduling alarms")
            return
        }

        if (sleepPrefs.sleepMethod != SleepPreferences.SLEEP_METHOD_SCHEDULE) {
            // Inactivity mode: use Handler-based timer instead of AlarmManager
            startSleepInactivityTimer()
            return
        }

        // Cancel inactivity timer if switching to schedule mode
        cancelSleepInactivityTimer()

        // Parse sleep and wake times
        val sleepTime = sleepPrefs.sleepTime  // Format: "HH:mm"
        val wakeTime = sleepPrefs.wakeTime

        val sleepParts = sleepTime.split(":").mapNotNull { it.toIntOrNull() }
        val wakeParts = wakeTime.split(":").mapNotNull { it.toIntOrNull() }

        if (sleepParts.size != 2 || wakeParts.size != 2) {
            Log.e(TAG, "🌙 Invalid sleep/wake time format: sleep=$sleepTime, wake=$wakeTime")
            return
        }

        val sleepHour = sleepParts[0]
        val sleepMinute = sleepParts[1]
        val wakeHour = wakeParts[0]
        val wakeMinute = wakeParts[1]

        // Calculate next sleep and wake times
        val now = System.currentTimeMillis()
        val calendar = java.util.Calendar.getInstance()

        // Calculate next sleep time
        calendar.timeInMillis = now
        calendar.set(java.util.Calendar.HOUR_OF_DAY, sleepHour)
        calendar.set(java.util.Calendar.MINUTE, sleepMinute)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        if (calendar.timeInMillis <= now) {
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        val nextSleepTime = calendar.timeInMillis

        // Calculate next wake time
        calendar.timeInMillis = now
        calendar.set(java.util.Calendar.HOUR_OF_DAY, wakeHour)
        calendar.set(java.util.Calendar.MINUTE, wakeMinute)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        if (calendar.timeInMillis <= now) {
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        val nextWakeTime = calendar.timeInMillis

        // Initialize AlarmManager if needed
        if (alarmManager == null) {
            alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        }

        // Create and schedule sleep alarm
        val sleepIntent = Intent(activity, SleepWakeReceiver::class.java).apply {
            action = SleepWakeReceiver.ACTION_SLEEP
        }
        val sleepFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        sleepAlarmPendingIntent = PendingIntent.getBroadcast(activity, 2, sleepIntent, sleepFlags)
        scheduleAlarm(nextSleepTime, sleepAlarmPendingIntent!!, "sleep")

        // Create and schedule wake alarm
        val wakeIntent = Intent(activity, SleepWakeReceiver::class.java).apply {
            action = SleepWakeReceiver.ACTION_WAKE
        }
        wakeAlarmPendingIntent = PendingIntent.getBroadcast(activity, 3, wakeIntent, sleepFlags)
        scheduleAlarm(nextWakeTime, wakeAlarmPendingIntent!!, "wake")

        // Log the scheduled times
        val sleepTimeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(nextSleepTime))
        val wakeTimeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(nextWakeTime))
        Log.i(TAG, "🌙 Sleep/wake alarms scheduled: sleep=$sleepTimeStr, wake=$wakeTimeStr")
        PersistentLog.info("SLEEP", "Alarms scheduled: sleep=$sleepTimeStr, wake=$wakeTimeStr")

        // Cold start / reschedule inside the sleep window: the screen is awake but no
        // wake transition will ever fire — make sure a countdown back to sleep exists.
        // (No-op when the screen is already off, e.g. the nightly 22:00 reschedule.)
        ensureResleepArmed("schedule-boot")
    }

    /**
     * Cancel any pending sleep/wake alarms.
     */
    fun cancelSleepWakeAlarms() {
        sleepAlarmPendingIntent?.let { pendingIntent ->
            alarmManager?.cancel(pendingIntent)
        }
        sleepAlarmPendingIntent = null

        wakeAlarmPendingIntent?.let { pendingIntent ->
            alarmManager?.cancel(pendingIntent)
        }
        wakeAlarmPendingIntent = null

        Log.d(TAG, "🌙 Sleep/wake alarms cancelled")
    }

    /**
     * Ensure a wake alarm is scheduled when the screen goes to sleep.
     * Called from screenOff() to handle manual sleep (sleep button) and JS-triggered sleep.
     * If sleep is in schedule mode and a wake time is configured, schedules the wake alarm.
     */
    fun ensureWakeAlarmScheduled() {
        if (!sleepPrefs.sleepEnabled) return
        if (sleepPrefs.sleepMethod != SleepPreferences.SLEEP_METHOD_SCHEDULE) return

        // Always (re)schedule wake alarm with current settings - existing alarm
        // may point to tomorrow or use stale values from before settings changed.
        val wakeTime = sleepPrefs.wakeTime
        val wakeParts = wakeTime.split(":").mapNotNull { it.toIntOrNull() }
        if (wakeParts.size != 2) return

        val now = System.currentTimeMillis()
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.set(java.util.Calendar.HOUR_OF_DAY, wakeParts[0])
        calendar.set(java.util.Calendar.MINUTE, wakeParts[1])
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        if (calendar.timeInMillis <= now) {
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        val nextWakeTime = calendar.timeInMillis

        // Register wake callback
        SleepWakeReceiver.onWakeTriggered = {
            Log.i(TAG, "☀️ Native wake alarm triggered - calling screenOn()")
            PersistentLog.info("SLEEP", "Native wake alarm triggered")
            onScreenOn()
            scheduleSleepWakeAlarms()
        }

        val wakeIntent = Intent(activity, SleepWakeReceiver::class.java).apply {
            action = SleepWakeReceiver.ACTION_WAKE
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        wakeAlarmPendingIntent = PendingIntent.getBroadcast(activity, 3, wakeIntent, flags)
        scheduleAlarm(nextWakeTime, wakeAlarmPendingIntent!!, "wake")

        val wakeTimeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(nextWakeTime))
        Log.i(TAG, "🌙 Wake alarm scheduled from screenOff: wake=$wakeTimeStr")
        PersistentLog.info("SLEEP", "Wake alarm scheduled from screenOff: $wakeTimeStr")
    }

    /**
     * Reschedule sleep/wake alarms.
     * Called when sleep timer settings change or after an alarm fires.
     */
    fun onSleepSettingsChanged() {
        Log.i(TAG, "🌙 Sleep settings changed - rescheduling alarms")
        // Cancel inactivity timer before rescheduling (scheduleSleepWakeAlarms will restart if needed)
        cancelSleepInactivityTimer()
        // Cancel any pending re-sleep — the next interaction re-arms with fresh settings
        cancelResleepCountdown()
        scheduleSleepWakeAlarms()
    }

    // ==================== Inactivity-based Sleep ====================

    /**
     * Start the sleep inactivity timer.
     * After the configured timeout of no user interaction, turns the screen off.
     * Only active when sleepMethod == "inactivity" and sleep is enabled.
     * Does not start if the screen is already off.
     */
    fun startSleepInactivityTimer() {
        if (!sleepPrefs.sleepEnabled || sleepPrefs.sleepMethod != SleepPreferences.SLEEP_METHOD_INACTIVITY) {
            return
        }

        // Don't start if screen is already off
        if (isScreenOff()) {
            Log.d(TAG, "🌙 Sleep inactivity timer: screen already off, not starting")
            return
        }

        cancelSleepInactivityTimer()

        val timeoutSeconds = sleepPrefs.inactivityTimeout
        if (timeoutSeconds <= 0) return

        if (sleepInactivityRunnable == null) {
            sleepInactivityRunnable = Runnable {
                // Double-check settings haven't changed
                if (sleepPrefs.sleepEnabled && sleepPrefs.sleepMethod == SleepPreferences.SLEEP_METHOD_INACTIVITY) {
                    val timeoutMin = sleepPrefs.inactivityTimeout / 60
                    Log.i(TAG, "🌙 Sleep inactivity timeout (${timeoutMin}m) - turning screen off")
                    PersistentLog.info("SLEEP", "Inactivity timeout (${timeoutMin}m) - screen off")
                    DiagnosticBuffer.info("SLEEP", "Inactivity timeout fired - screenOff()")
                    onScreenOff()
                }
            }
        }

        handler.removeCallbacks(sleepInactivityRunnable!!)
        handler.postDelayed(sleepInactivityRunnable!!, timeoutSeconds * 1000L)

        val timeoutMin = timeoutSeconds / 60
        val timeoutSec = timeoutSeconds % 60
        val desc = if (timeoutSec > 0) "${timeoutMin}m ${timeoutSec}s" else "${timeoutMin}m"
        Log.d(TAG, "🌙 Sleep inactivity timer started: $desc")
    }

    /**
     * Reset the sleep inactivity timer on user interaction.
     * Call this alongside screensaver and return-to-home timer resets.
     */
    fun resetSleepInactivityTimer() {
        // Schedule mode: user interaction feeds the re-sleep countdown instead
        // (onUserActivity self-guards on method/window, so this is a no-op otherwise).
        onUserActivity("input")

        if (!sleepPrefs.sleepEnabled || sleepPrefs.sleepMethod != SleepPreferences.SLEEP_METHOD_INACTIVITY) {
            return
        }

        // Don't reset if screen is off (user waking is handled by screenOn → startSleepInactivityTimer)
        if (isScreenOff()) return

        if (sleepInactivityRunnable != null) {
            val timeoutSeconds = sleepPrefs.inactivityTimeout
            if (timeoutSeconds <= 0) return
            handler.removeCallbacks(sleepInactivityRunnable!!)
            handler.postDelayed(sleepInactivityRunnable!!, timeoutSeconds * 1000L)
        } else {
            // Timer not initialized yet - start it
            startSleepInactivityTimer()
        }
    }

    /**
     * Cancel the sleep inactivity timer.
     */
    fun cancelSleepInactivityTimer() {
        sleepInactivityRunnable?.let { handler.removeCallbacks(it) }
    }

    // ==================== Re-sleep (schedule mode) ====================
    //
    // A wake during the scheduled sleep window (kid taps the screen, wake word fires,
    // motion wake) used to be a one-way door: the device fell back to the bright
    // screensaver and stayed there until the morning wake alarm. Re-sleep closes the
    // door: every wake/interaction inside the window (re)starts a countdown of
    // sleepPrefs.resleepTimeout minutes; when it expires with no further interaction,
    // the screen goes back off. Handler-based like the inactivity timer — the device
    // is awake during the countdown, so Doze is not a concern.

    /**
     * Record a wake or user interaction. Inside the sleep window this (re)starts the
     * re-sleep countdown; outside it (or in inactivity mode) it cancels any countdown.
     * Called from resetSleepInactivityTimer() (all input paths) and from the screen
     * controller's wake paths (dimmer wake, voice wake, motion wake).
     */
    fun onUserActivity(source: String = "unknown") = armResleep(source, reset = true)

    /**
     * Arm the re-sleep countdown ONLY if it isn't already running — never resets an
     * in-flight countdown (an interaction reset would extend the awake time; a passive
     * observation must not). Covers the "awake in the sleep window with no countdown"
     * states that have no wake transition: a cold app start inside the window
     * (2026-07-20 overnight: install-kill at 23:06 + daily-refresh cold start at 01:00
     * left both Mios on the screensaver until morning), and any screensaver activation
     * during the window.
     */
    fun ensureResleepArmed(source: String = "unknown") = armResleep(source, reset = false)

    private fun armResleep(source: String, reset: Boolean) {
        if (!sleepPrefs.sleepEnabled ||
            sleepPrefs.sleepMethod != SleepPreferences.SLEEP_METHOD_SCHEDULE ||
            !isInSleepWindow()
        ) {
            cancelResleepCountdown()
            return
        }
        if (isScreenOff()) return  // already asleep — nothing to re-sleep
        if (!reset && resleepArmed) return  // ensure-only: countdown already running

        val minutes = sleepPrefs.resleepTimeout
        val delayMs = if (minutes <= 0) RESLEEP_IMMEDIATE_GRACE_MS else minutes * 60_000L
        if (resleepRunnable == null) {
            resleepRunnable = Runnable { fireResleep() }
        }
        handler.removeCallbacks(resleepRunnable!!)
        handler.postDelayed(resleepRunnable!!, delayMs)

        // Log the arm transition once; interaction resets stay quiet (touch spam)
        if (!resleepArmed) {
            resleepArmed = true
            val desc = if (minutes <= 0) "immediate (${RESLEEP_IMMEDIATE_GRACE_MS / 1000}s grace)" else "${minutes}m"
            Log.i(TAG, "🌙 Re-sleep countdown armed: $desc (source=$source)")
            PersistentLog.info("SLEEP", "Re-sleep countdown armed: $desc (source=$source)")
        }
    }

    private fun fireResleep() {
        resleepArmed = false
        // Re-verify at fire time — settings may have changed, the wake alarm may have
        // fired, or the screen may already be off.
        if (!sleepPrefs.sleepEnabled || sleepPrefs.sleepMethod != SleepPreferences.SLEEP_METHOD_SCHEDULE) return
        if (!isInSleepWindow()) {
            Log.i(TAG, "🌙 Re-sleep fired outside sleep window — skipped")
            PersistentLog.info("SLEEP", "Re-sleep fired outside sleep window — skipped")
            return
        }
        if (isScreenOff()) return
        Log.i(TAG, "🌙 Re-sleep timeout (${sleepPrefs.resleepTimeout}m) — returning to sleep")
        PersistentLog.info("SLEEP", "Re-sleep timeout (${sleepPrefs.resleepTimeout}m) — screen off")
        DiagnosticBuffer.info("SLEEP", "Re-sleep timeout fired - screenOff()")
        onScreenOff()
    }

    /**
     * Cancel the re-sleep countdown. Called on screen off (nothing left to re-sleep)
     * and on sleep settings changes.
     */
    fun cancelResleepCountdown() {
        resleepRunnable?.let { handler.removeCallbacks(it) }
        if (resleepArmed) {
            resleepArmed = false
            Log.d(TAG, "🌙 Re-sleep countdown cancelled")
        }
    }

    /**
     * True when the current time falls inside the scheduled sleep window
     * (sleepTime → wakeTime), handling windows that cross midnight.
     */
    fun isInSleepWindow(): Boolean {
        val sleepParts = sleepPrefs.sleepTime.split(":").mapNotNull { it.toIntOrNull() }
        val wakeParts = sleepPrefs.wakeTime.split(":").mapNotNull { it.toIntOrNull() }
        if (sleepParts.size != 2 || wakeParts.size != 2) return false
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val sleepMin = sleepParts[0] * 60 + sleepParts[1]
        val wakeMin = wakeParts[0] * 60 + wakeParts[1]
        if (sleepMin == wakeMin) return false
        return if (sleepMin > wakeMin) {
            nowMin >= sleepMin || nowMin < wakeMin  // window crosses midnight (22:00 → 07:00)
        } else {
            nowMin >= sleepMin && nowMin < wakeMin
        }
    }

    // ==================== Cleanup ====================

    /**
     * Clean up all alarms and timers.
     */
    fun destroy() {
        cancelSleepWakeAlarms()
        cancelSleepInactivityTimer()
        sleepInactivityRunnable = null
        cancelResleepCountdown()
        resleepRunnable = null
    }
}

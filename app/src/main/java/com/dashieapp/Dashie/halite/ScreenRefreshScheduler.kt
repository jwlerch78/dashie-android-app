package com.dashieapp.Dashie.halite

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.halite.diagnostics.CrashDiagnosticsCollector
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.MemoryMonitor
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog

/**
 * Manages scheduled WebView refreshes for memory recovery and maintenance.
 *
 * Two independent refresh mechanisms:
 * 1. **Stealth reload** — Reloads WebView during screensaver to release GPU texture memory.
 *    Uses AlarmManager for reliable execution during Doze mode. Only fires while screensaver is active.
 * 2. **Daily scheduled refresh** — Refreshes WebView at user-configured hours regardless of
 *    screensaver state. Uses AlarmManager for reliable overnight execution.
 *
 * Also provides shared alarm scheduling utilities used by SleepWakeScheduler.
 *
 * @param activity The activity context
 * @param halitePrefs Halite preferences for settings
 */
class ScreenRefreshScheduler(
    private val activity: Context,
    private val halitePrefs: HalitePreferences
) {
    companion object {
        private const val TAG = "HaliteScreenController"
    }

    // ==================== Stealth WebView Reload ====================
    // Reloads WebView during screensaver to release GPU texture memory.
    // User won't notice because screensaver is covering the WebView.
    // Saves and restores the current URL so user returns to the same page.
    // Uses AlarmManager for reliable execution during Doze mode (not Handler).
    private var stealthReloadPendingIntent: PendingIntent? = null
    private var savedUrlBeforeReload: String? = null
    private val stealthReloadEnabled: Boolean
        get() = halitePrefs.performance.stealthRefreshEnabled
    private val stealthReloadIntervalMs: Long
        get() = halitePrefs.performance.stealthRefreshIntervalMinutes * 60 * 1000L
    private var lastStealthReloadTime = 0L
    private var stealthReloadWebView: WebView? = null

    // ==================== Daily Scheduled Refresh ====================
    // Refreshes WebView at user-configured times (multi-select hours).
    // Uses AlarmManager for reliable execution during Doze mode.
    // Runs regardless of screensaver state (user-scheduled maintenance).
    private var lastDailyRefreshTime = 0L
    private var alarmManager: AlarmManager? = null
    private var dailyRefreshPendingIntent: PendingIntent? = null

    // Screensaver state tracking (set by HaliteScreenController)
    var isScreensaverActive = false
        private set
    var isScreenOffDirect = false

    // Fired when the daily scheduled refresh executes — a reliable overnight
    // maintenance window. DashieUpdateController hooks this to run a Play
    // update check and install any "tonight"-deferred update.
    var onDailyRefresh: (() -> Unit)? = null

    // ==================== Stealth WebView Reload ====================

    /**
     * Start the scheduled refresh timer.
     * After the configured interval, the WebView will be reloaded to release GPU textures.
     * The current URL is saved and restored after reload.
     * Also starts the daily scheduled refresh scheduler.
     *
     * Call this when HA login completes - the timer runs independently of screensaver state.
     */
    fun startStealthReloadTimer(webView: WebView) {
        stealthReloadWebView = webView

        // Start daily scheduled refresh (independent of stealth refresh)
        startDailyRefreshScheduler()

        // Register callback for stealth reload receiver
        StealthReloadReceiver.onStealthReloadTriggered = {
            performStealthReload()
        }

        if (!stealthReloadEnabled) {
            Log.d(TAG, "🔄 Stealth refresh disabled")
            return
        }

        Log.i(TAG, "🔄 Stealth refresh initialized (interval: ${stealthReloadIntervalMs / 1000 / 60} min, requires screensaver)")
        PersistentLog.info("MEMORY", "Stealth refresh initialized (interval: ${stealthReloadIntervalMs / 1000 / 60} min)")

        // If screensaver is already active, start the timer now
        if (isScreensaverActive || isScreenOffDirect) {
            onScreensaverActivated(source = "startStealthReloadTimer_alreadyActive")
        }
    }

    /**
     * Cancel the stealth refresh alarm.
     * Note: Does NOT cancel the daily scheduled refresh scheduler.
     */
    fun cancelStealthReloadTimer() {
        cancelStealthReloadAlarm()
        StealthReloadReceiver.onStealthReloadTriggered = null
        Log.d(TAG, "🔄 Stealth refresh timer cancelled")
    }

    /**
     * Called when screensaver activates. Starts a fresh stealth alarm.
     * Alarm is cancelled if screensaver deactivates before it fires.
     *
     * @param source Human-readable origin for this activation (e.g. "screenDimmer_callback",
     *   "startup_already_active"). Logged to help diagnose unexpected re-activations that would
     *   otherwise reset the stealth countdown.
     */
    fun onScreensaverActivated(source: String = "unknown") {
        val wasActive = isScreensaverActive
        isScreensaverActive = true
        Log.i(TAG, "🔄 onScreensaverActivated(source=$source, wasActive=$wasActive, alarmPending=${stealthReloadPendingIntent != null})")
        PersistentLog.info("MEMORY", "Screensaver ACTIVATED (source=$source, wasActive=$wasActive, alarmPending=${stealthReloadPendingIntent != null})")
        logScreensaverCallerTrace(source)

        if (!stealthReloadEnabled || stealthReloadIntervalMs <= 0) return

        // Don't reschedule if an alarm is already pending. Redundant activations (e.g. motion-wake
        // → re-dim, or re-registration of the ScreenDimmer callback) would otherwise cancel the
        // pending alarm and restart the 4h countdown, causing the refresh to never fire overnight.
        if (stealthReloadPendingIntent != null) {
            Log.i(TAG, "🔄 Stealth alarm already pending — leaving existing alarm in place")
            PersistentLog.info("MEMORY", "Stealth alarm already pending — not rescheduled (source=$source)")
            return
        }

        scheduleStealthReloadAlarm()
        Log.i(TAG, "🔄 Stealth alarm scheduled: ${stealthReloadIntervalMs / 1000 / 60} min (cancelled if screensaver deactivates)")
        PersistentLog.info("MEMORY", "Stealth alarm scheduled: ${stealthReloadIntervalMs / 1000 / 60} min (source=$source)")
    }

    /**
     * Capture a short stack trace for screensaver activation. Used to diagnose unexpected
     * re-activations — we log the first few non-framework frames so we can see what called
     * into `onScreensaverActivated()` when no user was in the room.
     */
    private fun logScreensaverCallerTrace(source: String) {
        try {
            val stack = Throwable().stackTrace
            val frames = stack
                .drop(1) // drop this method
                .filter {
                    val cn = it.className
                    cn.startsWith("com.dashieapp.")
                }
                .take(6)
                .joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            if (frames.isNotEmpty()) {
                PersistentLog.info("MEMORY", "Screensaver activation trace (source=$source): $frames")
            }
        } catch (_: Exception) {
            // Best-effort diagnostic, never fail the activation path.
        }
    }

    /**
     * Called when screensaver deactivates. Cancels any pending stealth alarm.
     * User interaction means they're actively using the device - start fresh next time.
     * Does NOT cancel the alarm if sleep mode is also active — sleep and screensaver
     * are treated equivalently for refresh purposes.
     */
    fun onScreensaverDeactivated(source: String = "unknown") {
        val wasActive = isScreensaverActive
        isScreensaverActive = false
        Log.i(TAG, "🔄 onScreensaverDeactivated(source=$source, wasActive=$wasActive, sleepActive=$isScreenOffDirect, alarmPending=${stealthReloadPendingIntent != null})")
        PersistentLog.info("MEMORY", "Screensaver DEACTIVATED (source=$source, wasActive=$wasActive, sleepActive=$isScreenOffDirect)")

        if (!stealthReloadEnabled) return

        if (!isScreenOffDirect) {
            cancelStealthReloadAlarm()
            Log.i(TAG, "🔄 Stealth alarm cancelled: screensaver deactivated")
            PersistentLog.info("MEMORY", "Stealth alarm cancelled (screensaver off)")
        }
    }

    /**
     * Called when the device enters sleep mode (scheduled or inactivity-based screen off).
     * Treats sleep identically to screensaver — user isn't looking at the screen either way.
     *
     * @param source Human-readable origin for this activation (see [onScreensaverActivated]).
     */
    fun onSleepActivated(source: String = "unknown") {
        val wasActive = isScreenOffDirect
        isScreenOffDirect = true
        Log.i(TAG, "🔄 onSleepActivated(source=$source, wasActive=$wasActive, alarmPending=${stealthReloadPendingIntent != null})")
        PersistentLog.info("MEMORY", "Sleep ACTIVATED (source=$source, wasActive=$wasActive, alarmPending=${stealthReloadPendingIntent != null})")

        if (!stealthReloadEnabled || stealthReloadIntervalMs <= 0) return

        // Same guard as onScreensaverActivated — don't reset a pending 4h countdown.
        if (stealthReloadPendingIntent != null) {
            Log.i(TAG, "🔄 Stealth alarm already pending — leaving existing alarm in place")
            PersistentLog.info("MEMORY", "Stealth alarm already pending — not rescheduled (source=$source)")
            return
        }

        scheduleStealthReloadAlarm()
        Log.i(TAG, "🔄 Sleep active - stealth alarm scheduled: ${stealthReloadIntervalMs / 1000 / 60} min")
        PersistentLog.info("MEMORY", "Sleep active - stealth alarm scheduled: ${stealthReloadIntervalMs / 1000 / 60} min (source=$source)")
    }

    /**
     * Called when the device exits sleep mode (screen turns back on).
     * Cancels the sleep-mode stealth alarm unless screensaver is also still active.
     */
    fun onSleepDeactivated(source: String = "unknown") {
        val wasActive = isScreenOffDirect
        isScreenOffDirect = false
        Log.i(TAG, "🔄 onSleepDeactivated(source=$source, wasActive=$wasActive, screensaverActive=$isScreensaverActive, alarmPending=${stealthReloadPendingIntent != null})")
        PersistentLog.info("MEMORY", "Sleep DEACTIVATED (source=$source, wasActive=$wasActive, screensaverActive=$isScreensaverActive)")

        if (!isScreensaverActive) {
            cancelStealthReloadAlarm()
            Log.i(TAG, "🔄 Sleep ended - stealth alarm cancelled")
            PersistentLog.info("MEMORY", "Stealth alarm cancelled (sleep ended)")
        }
    }

    /**
     * Schedule the stealth reload alarm using AlarmManager.
     * Uses exact timing if permission granted, otherwise inexact (may drift 5-15 min).
     */
    private fun scheduleStealthReloadAlarm() {
        // Cancel existing alarm first
        cancelStealthReloadAlarm()

        // Re-register callback
        StealthReloadReceiver.onStealthReloadTriggered = {
            performStealthReload()
        }

        // Create PendingIntent
        val intent = Intent(activity, StealthReloadReceiver::class.java).apply {
            action = StealthReloadReceiver.ACTION_STEALTH_RELOAD
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        stealthReloadPendingIntent = PendingIntent.getBroadcast(activity, 1, intent, flags)  // requestCode=1 to differentiate from daily refresh

        // Schedule alarm
        val triggerAtMillis = System.currentTimeMillis() + stealthReloadIntervalMs
        scheduleAlarmWithFallback(triggerAtMillis, stealthReloadPendingIntent!!, "stealth reload")
    }

    /**
     * Cancel the stealth reload alarm.
     */
    private fun cancelStealthReloadAlarm() {
        stealthReloadPendingIntent?.let { pendingIntent ->
            alarmManager?.cancel(pendingIntent)
        }
        stealthReloadPendingIntent = null
    }

    /**
     * Perform the stealth WebView reload.
     * Called by StealthReloadReceiver when the alarm fires.
     */
    private fun performStealthReload() {
        val webView = stealthReloadWebView ?: run {
            Log.w(TAG, "🔄 Stealth reload: no WebView reference")
            return
        }

        // Verify screensaver is still active (edge case protection)
        if (!isScreensaverActive && !isScreenOffDirect) {
            Log.w(TAG, "🔄 Stealth reload: screensaver deactivated before alarm fired, skipping")
            return
        }

        // Throttle: don't reload more than once per 10 minutes
        val now = System.currentTimeMillis()
        if (now - lastStealthReloadTime < 10 * 60 * 1000L) {
            val elapsed = (now - lastStealthReloadTime) / 1000 / 60
            Log.i(TAG, "🔄 Stealth reload: throttled (last reload was ${elapsed} min ago)")
            // Schedule next alarm anyway
            scheduleStealthReloadAlarm()
            return
        }

        Log.i(TAG, "🔄 Stealth reload: screensaver active for ${stealthReloadIntervalMs / 1000 / 60} min, triggering aggressive refresh")
        PersistentLog.info("MEMORY", "Stealth reload after ${stealthReloadIntervalMs / 1000 / 60} min screensaver time")

        // Use the same aggressive memory release path as daily scheduled refresh
        MemoryMonitor.onMemoryPressureCallback?.invoke("scheduled", 0)
        CrashDiagnosticsCollector.persistRefreshEvent(activity, "stealth")

        lastStealthReloadTime = now

        // Schedule next stealth reload (screensaver is still active)
        scheduleStealthReloadAlarm()
        Log.d(TAG, "🔄 Next stealth reload scheduled in ${stealthReloadIntervalMs / 1000 / 60} min")
    }

    /**
     * Called by MainActivity after stealth reload completes.
     * This restores the previously saved URL.
     */
    fun completeStealthReload(webView: WebView) {
        val urlToRestore = savedUrlBeforeReload
        if (urlToRestore != null) {
            Log.i(TAG, "🔄 Stealth reload complete - restoring URL: ${urlToRestore.take(80)}")
            PersistentLog.info("MEMORY", "Stealth reload complete, restored URL")
            // Navigate back to saved URL
            webView.loadUrl(urlToRestore)
            savedUrlBeforeReload = null
        }
    }

    /**
     * Check if a stealth reload is pending (URL needs to be restored).
     */
    fun hasStealthReloadPending(): Boolean = savedUrlBeforeReload != null

    /**
     * Get the saved URL for restoration.
     * Returns null and clears the saved URL after returning (one-time use).
     */
    fun getSavedUrlBeforeReload(): String? {
        val url = savedUrlBeforeReload
        savedUrlBeforeReload = null  // Clear after returning (one-time use)
        return url
    }

    /**
     * Configure stealth reload interval (in minutes).
     * Saves to preferences for persistence.
     */
    fun setStealthReloadInterval(minutes: Int) {
        halitePrefs.performance.stealthRefreshIntervalMinutes = minutes
        Log.i(TAG, "🔄 Stealth reload interval set to $minutes minutes")
    }

    /**
     * Enable or disable scheduled refresh.
     * Saves to preferences for persistence.
     */
    fun setStealthReloadEnabled(enabled: Boolean) {
        halitePrefs.performance.stealthRefreshEnabled = enabled
        if (enabled) {
            // Start timer immediately when enabled
            stealthReloadWebView?.let { webView ->
                startStealthReloadTimer(webView)
            }
        } else {
            cancelStealthReloadTimer()
        }
        Log.i(TAG, "🔄 Scheduled refresh ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Called when scheduled refresh settings change via JS bridge.
     * Restarts the timer with new settings.
     */
    fun refreshStealthReloadSettings() {
        // Re-read the enabled/interval prefs and reschedule or cancel accordingly.
        // startStealthReloadTimer early-returns when disabled WITHOUT cancelling a
        // pending alarm, so a live disable must cancel explicitly here.
        if (!stealthReloadEnabled) {
            cancelStealthReloadTimer()
            Log.i(TAG, "🔄 Scheduled refresh disabled - timer cancelled")
            return
        }
        // Restart timer with new interval (runs independently of screensaver)
        stealthReloadWebView?.let { webView ->
            startStealthReloadTimer(webView)
            Log.i(TAG, "🔄 Scheduled refresh settings updated - timer restarted")
        }
    }

    // ==================== Daily Scheduled Refresh (AlarmManager) ====================

    /**
     * Start the daily refresh scheduler using AlarmManager.
     * Uses setExactAndAllowWhileIdle() for reliable execution during Doze mode.
     * Called when HA login completes, runs independently of screensaver state.
     */
    fun startDailyRefreshScheduler() {
        // Initialize AlarmManager if not already done
        if (alarmManager == null) {
            alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        }

        // Register the callback for when the alarm fires
        DailyRefreshReceiver.onRefreshTriggered = {
            performDailyRefresh()
        }

        // Check for custom test time first
        val customTestTime = halitePrefs.performance.customRefreshTestTime
        if (customTestTime > 0) {
            val delayMs = customTestTime - System.currentTimeMillis()
            if (delayMs > 0) {
                scheduleDailyRefreshAlarm(customTestTime)
                val nextTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(customTestTime))
                Log.i(TAG, "📅 Custom test refresh scheduled for $nextTime (in ${delayMs / 1000} sec)")
                PersistentLog.info("REFRESH", "Custom test scheduled for $nextTime")
                return
            } else {
                // Custom time has passed, clear it
                halitePrefs.performance.customRefreshTestTime = 0
            }
        }

        val scheduledHours = halitePrefs.performance.dailyRefreshHours
        if (scheduledHours.isEmpty()) {
            Log.d(TAG, "📅 Daily refresh disabled - no hours configured")
            return
        }

        val (triggerAtMillis, nextRefreshMs) = calculateNextDailyRefreshTime(scheduledHours)
        if (nextRefreshMs <= 0) {
            Log.w(TAG, "📅 Daily refresh: could not calculate next time")
            return
        }

        scheduleDailyRefreshAlarm(triggerAtMillis)
        CrashDiagnosticsCollector.persistSchedulerState(activity, started = true, nextAlarmMillis = triggerAtMillis)
        val nextTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(triggerAtMillis))
        Log.i(TAG, "📅 Daily refresh scheduler started - next refresh at $nextTime (in ${nextRefreshMs / 1000 / 60} min)")
        PersistentLog.info("REFRESH", "Scheduler started - next at $nextTime (${nextRefreshMs / 1000 / 60} min)")
    }

    /**
     * Cancel the daily refresh scheduler.
     * Called during cleanup (destroy) or when disabling the feature.
     *
     * Does NOT null the callback — callback management is handled by the scheduling path.
     * Clearing the callback here caused refresh #2 not to re-schedule: if scheduling threw
     * after the cancel, the callback was lost and the already-pending alarm had no target.
     */
    fun cancelDailyRefreshScheduler() {
        dailyRefreshPendingIntent?.let { pendingIntent ->
            alarmManager?.cancel(pendingIntent)
            Log.d(TAG, "📅 Daily refresh alarm cancelled")
        }
        dailyRefreshPendingIntent = null
    }

    /**
     * Update the WebView reference after WebView recreation.
     * Must be called from HaliteScreenController.setWebView() to avoid holding a stale
     * reference to a destroyed WebView across daily refresh cycles.
     */
    fun updateWebView(newWebView: WebView?) {
        if (stealthReloadWebView !== newWebView) {
            Log.i(TAG, "🔄 ScreenRefreshScheduler WebView reference updated (post-recreation)")
            stealthReloadWebView = newWebView
        }
    }

    /**
     * Schedule a test refresh for 2 minutes from now.
     * Used by performance overlay "Test Refresh" button.
     */
    fun scheduleTestRefresh() {
        val triggerAtMillis = System.currentTimeMillis() + (2 * 60 * 1000L)
        scheduleDailyRefreshAlarm(triggerAtMillis)
        val nextTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(triggerAtMillis))
        Log.i(TAG, "📅 TEST: Daily refresh scheduled for $nextTime (2 minutes from now)")
        PersistentLog.info("REFRESH", "TEST scheduled for $nextTime (2 min)")
    }

    /**
     * Schedule a custom test refresh at a specific time.
     * @param hour Hour (0-23)
     * @param minute Minute (0-59)
     */
    fun scheduleCustomTestRefresh(hour: Int, minute: Int) {
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            // If time has passed today, schedule for tomorrow
            if (timeInMillis <= System.currentTimeMillis()) {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
        }

        // Store the custom time so it survives app restarts
        halitePrefs.performance.customRefreshTestTime = calendar.timeInMillis

        scheduleDailyRefreshAlarm(calendar.timeInMillis)
        val nextTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(calendar.timeInMillis))
        val delayMin = (calendar.timeInMillis - System.currentTimeMillis()) / 1000 / 60
        Log.i(TAG, "📅 CUSTOM TEST: Refresh scheduled for $nextTime (in $delayMin min)")
        PersistentLog.info("REFRESH", "CUSTOM TEST scheduled for $nextTime ($delayMin min)")
    }

    /**
     * Clear any custom test time.
     */
    fun clearCustomTestTime() {
        halitePrefs.performance.customRefreshTestTime = 0
        Log.i(TAG, "📅 Custom test time cleared")
        // Reschedule normal daily refresh
        startDailyRefreshScheduler()
    }

    /**
     * Calculate the absolute time (millis) and delay for the next scheduled refresh.
     * @param scheduledHours Set of hours (1-24, where 24 = midnight/0:00)
     * @return Pair of (triggerAtMillis, delayMs), or (-1, -1) if no valid hours
     */
    private fun calculateNextDailyRefreshTime(scheduledHours: Set<Int>): Pair<Long, Long> {
        if (scheduledHours.isEmpty()) return Pair(-1L, -1L)

        val now = System.currentTimeMillis()
        val calendar = java.util.Calendar.getInstance()
        val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)

        // Normalize hours: 24 means midnight (0:00), convert to 0 for comparison
        val normalizedHours = scheduledHours.map { if (it == 24) 0 else it }.sorted()

        // Find the next hour that's strictly after the current hour
        // This ensures we don't reschedule for the same hour that just fired
        var targetHour = -1
        var addDays = 0

        for (hour in normalizedHours) {
            if (hour > currentHour) {
                targetHour = hour
                break
            }
        }

        if (targetHour == -1) {
            // No hours left today, use the first hour tomorrow
            targetHour = normalizedHours.first()
            addDays = 1
        }

        // Calculate the target time
        val targetCalendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, targetHour)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (addDays > 0) {
                add(java.util.Calendar.DAY_OF_YEAR, addDays)
            }
        }

        val triggerAtMillis = targetCalendar.timeInMillis
        var delayMs = triggerAtMillis - now

        // Safety check: if calculated time is somehow in the past (e.g., race condition
        // where we're right at the boundary), bump to tomorrow
        if (delayMs <= 0) {
            Log.w(TAG, "📅 calculateNextDailyRefreshTime: delayMs=$delayMs <= 0, bumping to tomorrow")
            val tomorrowCalendar = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, normalizedHours.first())
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            val tomorrowTrigger = tomorrowCalendar.timeInMillis
            delayMs = tomorrowTrigger - now
            return Pair(tomorrowTrigger, delayMs)
        }

        return Pair(triggerAtMillis, delayMs)
    }

    // ==================== Alarm Scheduling Helpers ====================

    /**
     * Check if the app can schedule exact alarms.
     * On Android 12+ (API 31+), this requires SCHEDULE_EXACT_ALARM permission
     * which the user must grant in Settings.
     *
     * @return true if exact alarms can be scheduled, false otherwise
     */
    private fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() ?: false
        } else {
            // Before Android 12, exact alarms don't require special permission
            true
        }
    }

    /**
     * Schedule an alarm with exact timing if permission granted, otherwise use inexact.
     * - Exact: Uses setExactAndAllowWhileIdle() - fires at precise time
     * - Inexact: Uses setAndAllowWhileIdle() - may drift 5-15 minutes due to battery batching
     *
     * Both methods work during Doze mode.
     *
     * @param triggerAtMillis Absolute time to trigger the alarm
     * @param pendingIntent The PendingIntent to fire
     * @param alarmName Human-readable name for logging
     */
    fun scheduleAlarmWithFallback(triggerAtMillis: Long, pendingIntent: PendingIntent, alarmName: String) {
        // Initialize AlarmManager if needed
        if (alarmManager == null) {
            alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        }

        // Check both permission AND user preference for exact alarms
        val permissionGranted = canScheduleExactAlarms()
        val userPrefersExact = halitePrefs.performance.useExactAlarms
        val useExact = permissionGranted && userPrefersExact

        val nextTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(triggerAtMillis))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (useExact) {
                // Exact alarm - fires at precise time
                alarmManager?.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.d(TAG, "📅 EXACT alarm scheduled for $alarmName at $nextTime")
                PersistentLog.info("ALARM", "EXACT $alarmName scheduled for $nextTime")
            } else {
                // Inexact alarm - may drift 5-15 minutes
                alarmManager?.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                val reason = if (!permissionGranted) "no permission" else "user preference"
                Log.d(TAG, "📅 INEXACT alarm scheduled for $alarmName at $nextTime ($reason)")
                PersistentLog.info("ALARM", "INEXACT $alarmName scheduled for $nextTime ($reason)")
            }
        } else {
            // Pre-Marshmallow fallback
            alarmManager?.set(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            Log.d(TAG, "📅 Alarm scheduled for $alarmName at $nextTime (pre-M)")
        }
    }

    /**
     * Open system settings to allow user to grant exact alarm permission.
     * Only available on Android 12+ (API 31+).
     *
     * @return true if settings were opened, false if not applicable
     */
    fun openExactAlarmSettings(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = android.net.Uri.parse("package:${activity.packageName}")
                }
                if (activity is android.app.Activity) {
                    activity.startActivity(intent)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open exact alarm settings: ${e.message}")
                false
            }
        } else {
            false
        }
    }

    /**
     * Check if exact alarm permission is needed and not yet granted.
     * Use this to determine whether to show the permission prompt to the user.
     *
     * @return true if exact alarms are supported but not permitted
     */
    fun needsExactAlarmPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExactAlarms()
    }

    // ==================== Daily Refresh Scheduling ====================

    /**
     * Schedule the daily refresh alarm using AlarmManager.
     * Uses exact timing if SCHEDULE_EXACT_ALARM permission is granted,
     * otherwise falls back to inexact timing (may drift 5-15 minutes).
     */
    private fun scheduleDailyRefreshAlarm(triggerAtMillis: Long) {
        // Set the callback FIRST (idempotent) so an in-flight alarm firing between
        // cancel() and scheduleAlarmWithFallback() still has a valid target.
        DailyRefreshReceiver.onRefreshTriggered = {
            performDailyRefresh()
        }

        // Cancel any existing alarm (not the callback — we just re-set it above)
        cancelDailyRefreshScheduler()

        // Create the PendingIntent for the alarm
        val intent = Intent(activity, DailyRefreshReceiver::class.java).apply {
            action = DailyRefreshReceiver.ACTION_DAILY_REFRESH
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        dailyRefreshPendingIntent = PendingIntent.getBroadcast(activity, 0, intent, flags)

        // Initialize AlarmManager if needed
        if (alarmManager == null) {
            alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        }

        // Schedule the alarm using exact timing if permission granted, otherwise inexact.
        // Wrap in try/catch so a SecurityException (e.g., revoked exact-alarm permission on
        // Android 12+) doesn't abort the caller before the fallback path can be attempted.
        try {
            scheduleAlarmWithFallback(triggerAtMillis, dailyRefreshPendingIntent!!, "daily refresh")
        } catch (e: SecurityException) {
            Log.e(TAG, "📅 scheduleDailyRefreshAlarm: SecurityException — falling back to inexact alarm", e)
            PersistentLog.error("ALARM", "Daily refresh scheduling denied: ${e.message}")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager?.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        dailyRefreshPendingIntent!!
                    )
                } else {
                    alarmManager?.set(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        dailyRefreshPendingIntent!!
                    )
                }
                PersistentLog.info("ALARM", "Daily refresh rescheduled via inexact fallback after SecurityException")
            } catch (e2: Throwable) {
                Log.e(TAG, "📅 Inexact fallback scheduling also failed", e2)
                PersistentLog.error("ALARM", "Inexact fallback failed: ${e2.message}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "📅 scheduleDailyRefreshAlarm: unexpected exception", e)
            PersistentLog.error("ALARM", "Daily refresh scheduling unexpected exception: ${e.message}")
        }
    }

    /**
     * Perform the daily scheduled refresh.
     * Refreshes at the scheduled time regardless of screensaver state.
     *
     * CRITICAL: The entire body is wrapped in try/finally so `scheduleNextDailyRefresh()`
     * runs even if the memory-pressure callback throws. Prior to this wrapping, any exception
     * inside invoke() (e.g., SecurityException from setExactAndAllowWhileIdle if the user
     * revoked the exact-alarm permission mid-session) would bypass the reschedule and the
     * one-shot AlarmManager alarm would be lost forever — which is the scenario that
     * explained Michael Achermann's 2.9-day OOM crash (refresh #1 fired, refresh #2 never
     * scheduled).
     */
    private fun performDailyRefresh() {
        var rescheduleHandled = false
        try {
            // Clear custom test time if it was set (one-shot)
            if (halitePrefs.performance.customRefreshTestTime > 0) {
                halitePrefs.performance.customRefreshTestTime = 0
            }

            // Run the Play update check / "tonight" install on this overnight
            // window. Done before the WebView-refresh throttle below so an
            // update check still happens even when the reload itself is skipped.
            try {
                onDailyRefresh?.invoke()
            } catch (e: Throwable) {
                Log.w(TAG, "📅 Daily refresh: onDailyRefresh callback threw", e)
            }

            val webView = stealthReloadWebView
            if (webView == null) {
                Log.w(TAG, "📅 Daily refresh: no WebView reference")
                return
            }

            // Daily refresh triggers regardless of screensaver state (user-scheduled maintenance)
            val screensaverActive = isScreensaverActive || isScreenOffDirect
            Log.i(TAG, "📅 Daily refresh triggered (screensaver: $screensaverActive)")
            PersistentLog.info("REFRESH", "Daily refresh triggered (screensaver: $screensaverActive)")

            // Throttle: don't refresh if a stealth reload happened within the last 30 minutes
            val now = System.currentTimeMillis()
            if (now - lastStealthReloadTime < 30 * 60 * 1000L) {
                Log.i(TAG, "📅 Daily refresh: skipped (stealth reload was ${(now - lastStealthReloadTime) / 1000 / 60} min ago)")
                PersistentLog.info("REFRESH", "Skipped - stealth reload was recent")
                return
            }

            // Throttle: don't refresh if last daily refresh was within 30 minutes
            if (now - lastDailyRefreshTime < 30 * 60 * 1000L) {
                Log.i(TAG, "📅 Daily refresh: throttled (last was ${(now - lastDailyRefreshTime) / 1000 / 60} min ago)")
                PersistentLog.info("REFRESH", "Throttled - last refresh was recent")
                return
            }

            Log.i(TAG, "📅 Daily scheduled refresh executing")
            PersistentLog.info("REFRESH", "Daily refresh EXECUTING")
            DiagnosticBuffer.info("REFRESH", "Daily scheduled refresh executing")
            CrashDiagnosticsCollector.persistLifecycleEvent(activity, "refresh_started", "type=daily")

            // Record refresh BEFORE invoking callback — ensures the refresh event is
            // persisted for crash diagnostics even if the callback crashes the process.
            CrashDiagnosticsCollector.persistRefreshEvent(activity, "daily")
            lastDailyRefreshTime = now
            lastStealthReloadTime = now  // Update stealth time too to prevent double-refresh

            // Schedule next refresh NOW (before invoking callback). Doing this up front
            // guarantees alarm #2 is scheduled even if the memory-pressure callback throws,
            // hangs, or kills the process during WebView recreation.
            scheduleNextDailyRefresh()
            rescheduleHandled = true

            // Trigger reload via memory manager (uses common path-capture-and-restore mechanism)
            // This preserves the current HA tab/view via JavaScript path restoration.
            try {
                MemoryMonitor.onMemoryPressureCallback?.invoke("scheduled", 0)
                CrashDiagnosticsCollector.persistLifecycleEvent(activity, "refresh_callback_returned", "type=daily")
            } catch (e: Throwable) {
                Log.e(TAG, "📅 Daily refresh: memory-pressure callback threw — alarm was already rescheduled, refresh may have partially completed", e)
                PersistentLog.error("REFRESH", "Memory callback threw: ${e.javaClass.simpleName}: ${e.message}")
                CrashDiagnosticsCollector.persistLifecycleEvent(
                    activity, "refresh_callback_threw", "${e.javaClass.simpleName}:${e.message?.take(80)}"
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "📅 Daily refresh: unexpected exception in performDailyRefresh", e)
            PersistentLog.error("REFRESH", "Unexpected exception: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            // Belt-and-suspenders: ensure next alarm is scheduled no matter what.
            if (!rescheduleHandled) {
                try {
                    scheduleNextDailyRefresh()
                } catch (e: Throwable) {
                    Log.e(TAG, "📅 Daily refresh: failed to reschedule in finally block", e)
                    PersistentLog.error("REFRESH", "Reschedule-in-finally failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Schedule the next daily refresh based on configured hours.
     */
    private fun scheduleNextDailyRefresh() {
        val scheduledHours = halitePrefs.performance.dailyRefreshHours
        if (scheduledHours.isEmpty()) {
            Log.d(TAG, "📅 Daily refresh: no hours configured, not rescheduling")
            return
        }

        val (triggerAtMillis, nextRefreshMs) = calculateNextDailyRefreshTime(scheduledHours)
        if (nextRefreshMs > 0) {
            scheduleDailyRefreshAlarm(triggerAtMillis)
            CrashDiagnosticsCollector.persistSchedulerState(activity, started = true, nextAlarmMillis = triggerAtMillis)
            CrashDiagnosticsCollector.persistLifecycleEvent(
                activity, "refresh_rescheduled", "via=post_refresh,next=${triggerAtMillis}"
            )
            val nextTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(triggerAtMillis))
            Log.i(TAG, "📅 Next daily refresh scheduled for $nextTime (in ${nextRefreshMs / 1000 / 60} min)")
            PersistentLog.info("REFRESH", "Next scheduled for $nextTime (${nextRefreshMs / 1000 / 60} min)")
        } else {
            Log.e(TAG, "📅 ERROR: Could not schedule next refresh - delayMs=$nextRefreshMs, hours=$scheduledHours")
            PersistentLog.error("REFRESH", "Failed to schedule next - delayMs=$nextRefreshMs")
            CrashDiagnosticsCollector.persistLifecycleEvent(
                activity, "refresh_reschedule_failed", "delayMs=$nextRefreshMs,hours=$scheduledHours"
            )
        }
    }

    // ==================== Cleanup ====================

    /**
     * Clean up all alarms and callbacks.
     * Only nulls the daily refresh callback here (not in cancelDailyRefreshScheduler) so
     * mid-scheduling races don't orphan a pending alarm with no target.
     */
    fun destroy() {
        cancelStealthReloadTimer()
        cancelDailyRefreshScheduler()
        DailyRefreshReceiver.onRefreshTriggered = null
    }
}

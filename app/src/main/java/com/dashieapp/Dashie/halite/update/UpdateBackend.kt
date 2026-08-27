package com.dashieapp.Dashie.halite.update

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Backend-agnostic base for Dashie's in-app update mechanism.
 *
 * Holds the shared state and the "Update Tonight" deferred-install alarm — a
 * fixed [INSTALL_HOUR] AlarmManager alarm, persisted across restarts, and
 * independent of the daily-refresh and sleep schedules.
 *
 * [DashieUpdateController] drives one concrete backend, picked at runtime:
 *  - [PlayUpdateBackend] — Google Play In-App Updates (store builds)
 *  - [SideloadUpdateBackend] — APK download + install (sideload flavor)
 *
 * Subclasses implement [checkForUpdate], [startDownload], [installAndRestart],
 * [cleanup]; they set [updateAvailable] / [downloadInProgress] and fire the
 * [onUpdateAvailable] / [onReadyToInstall] callbacks.
 */
abstract class UpdateBackend(protected val context: Context) {

    companion object {
        private const val TAG = "DashieUpdate"
        /** Hour of day (0-23) at which a "tonight"-deferred update installs. */
        const val INSTALL_HOUR = 2
        private const val INSTALL_ALARM_REQUEST_CODE = 7302
        private const val PREFS_NAME = "dashie_lite_prefs"
        private const val KEY_TONIGHT_PENDING = "update_tonight_pending"
        private const val KEY_SNOOZE_UNTIL = "update_snooze_until"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Fires (main thread) when an update first becomes available. */
    var onUpdateAvailable: (() -> Unit)? = null

    /** Fires (main thread) when a downloaded update is staged and ready to install. */
    var onReadyToInstall: (() -> Unit)? = null

    /** Fires (main thread) when a download attempt fails (HTTP error, SHA
     *  mismatch, network exception, etc.) so the host can surface a UI
     *  message instead of leaving the user wondering. Reason is a short
     *  human-readable string suitable for Toast display. */
    var onDownloadFailed: ((reason: String) -> Unit)? = null

    /** True once an available update has been detected. */
    var updateAvailable: Boolean = false
        protected set

    /** True while an update is downloading. */
    var downloadInProgress: Boolean = false
        protected set

    /** True from the moment a PackageInstaller session is committed (in
     *  the sideload path) until the OS replaces the app process with the
     *  new APK. Bridges the silent gap between download-complete and
     *  app-restart so the CC "Software Update" card can render a visible
     *  "Installing..." state instead of looking idle. */
    var installInProgress: Boolean = false
        protected set

    /** Reset [installInProgress] — called by the controller when the
     *  PackageInstaller reports the install was aborted/failed (so the
     *  status banner + CC card don't stay stuck in "Installing..."). */
    fun clearInstallInProgress() {
        installInProgress = false
    }

    /** Set when the user dismissed the banner — Control Center should show an indicator. */
    var dismissedToControlCenter: Boolean = false

    // ── Optional manifest metadata (Update Notification Redesign, 2026-05) ──

    /** versionName of the available update, e.g. "1.1.4". Null until detected. */
    var availableVersionName: String? = null
        protected set

    /** Optional release title from the manifest — shown bold in the modal. */
    var releaseTitle: String? = null
        protected set

    /** Optional plaintext release notes (\n-separated). Shown in the modal body. */
    var releaseDescription: String? = null
        protected set

    /**
     * Whether this update should pop the auto-banner. False = silent rollout
     * (the CC "Software Update" card still appears). Default true preserves
     * legacy behavior for manifests that omit the `notify` field.
     */
    var shouldNotify: Boolean = true
        protected set

    /**
     * True while a user-deferred ("Update tonight") install is waiting for the
     * [INSTALL_HOUR] alarm. Persisted to SharedPreferences so the deferred
     * install survives an app restart / device reboot.
     */
    var tonightInstallPending: Boolean
        get() = prefs.getBoolean(KEY_TONIGHT_PENDING, false)
        set(value) { prefs.edit().putBoolean(KEY_TONIGHT_PENDING, value).apply() }

    /**
     * Epoch-ms until which the update banner is snoozed — set when the user
     * taps "Later" on a backend that can't install unattended. Persisted so
     * the snooze survives a restart. 0 = not snoozed.
     */
    var snoozeUntilMillis: Long
        get() = prefs.getLong(KEY_SNOOZE_UNTIL, 0L)
        set(value) { prefs.edit().putLong(KEY_SNOOZE_UNTIL, value).apply() }

    // ── Backend-specific (implemented by Play / Sideload / Amazon) ───

    /**
     * Whether this backend can defer an install to a chosen time ("Update
     * Tonight" + the 2 AM alarm). False for store-handoff backends
     * ([AmazonUpdateBackend]) — the banner hides the "Update Tonight" button.
     */
    open val supportsDeferredInstall: Boolean = true

    /** Query for an available update. Safe to call repeatedly. */
    abstract fun checkForUpdate()

    /** Start the (consent +) background download. Requires a foreground Activity. */
    abstract fun startDownload(activity: Activity)

    /** Install a downloaded update and restart the app. No-ops if nothing is staged. */
    abstract fun installAndRestart()

    /** Release any listeners. Call from the host's onDestroy. */
    abstract fun cleanup()

    /**
     * Host activity resumed. Default no-op; [SideloadUpdateBackend] overrides
     * it to resume an install that was blocked on the "install unknown apps"
     * permission grant.
     */
    open fun onActivityResumed() {}

    /**
     * Called when the user defers an install ("Update Tonight"). Default
     * no-op; [SideloadUpdateBackend] overrides it to secure the
     * install-unknown-apps grant now, while the user is present — the
     * unattended 2 AM install can't prompt for a permission.
     */
    open fun prepareForDeferredInstall(activity: Activity) {}

    // ── Shared: "Update Tonight" deferred-install alarm ──────────────

    /**
     * Arm an alarm for the next [INSTALL_HOUR]:00 to install a "tonight"-
     * deferred update. AlarmManager-backed (Doze-safe) and independent of the
     * daily-refresh and sleep schedules. Persists [tonightInstallPending] so a
     * restart can re-arm it (AlarmManager alarms don't survive a reboot).
     */
    fun scheduleInstallAlarm() {
        tonightInstallPending = true
        val triggerAt = nextInstallTimeMillis()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, installPendingIntent())
        val t = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(triggerAt))
        Log.i(TAG, "Deferred install armed for $t")
    }

    /** Cancel the deferred-install alarm and clear the pending flag. */
    fun cancelInstallAlarm() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(installPendingIntent())
        tonightInstallPending = false
        Log.i(TAG, "Deferred install alarm cancelled")
    }

    private fun installPendingIntent(): PendingIntent {
        val intent = Intent(context, UpdateInstallReceiver::class.java).apply {
            action = UpdateInstallReceiver.ACTION_INSTALL_UPDATE
        }
        return PendingIntent.getBroadcast(
            context, INSTALL_ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun nextInstallTimeMillis(): Long {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, INSTALL_HOUR)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }

    // ── Shared: misc ─────────────────────────────────────────────────

    /** Record that the user dismissed the banner; Control Center shows an indicator. */
    fun markDismissed() {
        dismissedToControlCenter = true
        Log.i(TAG, "Update banner dismissed — surfaced to Control Center")
    }

    /**
     * Debug-only: force the "update available" state so the banner and the
     * Control Center "Software Update" card can be previewed without a real
     * update. Has no effect on the actual install flow.
     */
    fun simulateUpdateAvailableForTest() {
        updateAvailable = true
        availableVersionName = availableVersionName ?: "TEST"
        releaseTitle = releaseTitle ?: "What's new in this update"
        releaseDescription = releaseDescription
            ?: "• Simulated release notes line one\n• Simulated release notes line two\n• Final improvement"
        shouldNotify = true
        Log.i(TAG, "🧪 simulateUpdateAvailableForTest — updateAvailable forced true")
    }
}

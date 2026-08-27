package com.dashieapp.Dashie

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.diagnostics.AbnormalExitReporter
import com.dashieapp.Dashie.halite.diagnostics.CrashDiagnosticsCollector
import com.dashieapp.Dashie.halite.diagnostics.CrashHandler
import com.dashieapp.Dashie.halite.diagnostics.ExitReasonAnalyzer
import com.dashieapp.Dashie.halite.diagnostics.MemoryMonitor
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog

/**
 * Handles early initialization that happens before setContentView() in MainActivity.
 *
 * Responsibilities:
 * - CrashHandler and PersistentLog initialization
 * - MemoryMonitor setup and callback wiring
 * - OOM kill detection from previous session
 * - HalitePreferences creation and setup checks
 * - Redirect to setup/wifi activities if needed
 *
 * @param activity The activity context
 * @param memoryManagerProvider Lazy provider for MainMemoryManager (not yet created during early init)
 */
class MainStartupInitializer(
    private val activity: ComponentActivity,
    private val memoryManagerProvider: () -> MainMemoryManager?
) {
    companion object {
        private const val TAG = "DashieAuth"
    }

    /**
     * Result of early initialization.
     * @param shouldContinue true if onCreate should continue, false if activity was redirected (finish called)
     * @param halitePrefs The initialized HalitePreferences (null for non-Halite builds)
     * @param crashReportHandler The crash report handler (null for non-Halite builds)
     */
    data class Result(
        val shouldContinue: Boolean,
        val halitePrefs: HalitePreferences?,
        val crashReportHandler: MainCrashReportHandler?,
        val oomKillDetected: Boolean = false
    )

    /**
     * Perform all early initialization before setContentView().
     *
     * This includes crash handler setup, OOM detection, preferences initialization,
     * and redirect checks. Must be called after super.onCreate() and installSplashScreen().
     *
     * @return Result indicating whether to continue onCreate or if the activity was redirected
     */
    fun performEarlyInit(): Result {
        var halitePrefs: HalitePreferences? = null
        var crashReportHandler: MainCrashReportHandler? = null
        var oomKillDetected = false

        // Initialize crash handler and persistent logging early (before anything else can crash)
        if (BuildConfig.ALLOW_URL_CONFIG) {
            CrashHandler.install(activity.applicationContext)
            PersistentLog.init(activity.applicationContext)
            PersistentLog.info("STARTUP", "MainActivity.onCreate() - app starting")

            // Log why the previous process exited (if any). On Fire TV / aggressive OEM ROMs
            // the OS can force-kill us without any onPause/onDestroy callback, so this is the
            // only diagnostic that can tell us whether the kill was OOM, Amazon PowerManager,
            // permission change, freezer, etc.
            CrashDiagnosticsCollector.logProcessExitReasonsToPersistentLog(activity.applicationContext)

            // One-shot permission/setup snapshot (device owner/admin, battery exempt,
            // overlay, exact alarm, camera/notification perms, fully-kiosk API) so the
            // durable log — and every diagnostics report — always shows the full
            // keep-alive setup even when no crash occurs.
            CrashDiagnosticsCollector.logSetupSnapshotToPersistentLog(activity.applicationContext)

            // Initialize memory monitor with context (for PSS monitoring)
            MemoryMonitor.init(activity.applicationContext)
            MemoryMonitor.start()

            // Wire up memory reading callback for OOM detection
            // Saves both PSS (MB) and RAM % since WebView renderer memory may not show in PSS
            // (halitePrefs will be initialized shortly, so use lazy reference via provider)
            MemoryMonitor.saveMemoryReadingCallback = { pssMb, ramPercent ->
                halitePrefs?.performance?.lastMemoryReadingMb = pssMb
                halitePrefs?.performance?.lastRamPercent = ramPercent
            }

            // Wire up memory pressure callback for proactive intervention
            // This triggers when PSS exceeds thresholds (200MB warning, 350MB critical, 450MB emergency)
            MemoryMonitor.onMemoryPressureCallback = { level, pssMb ->
                memoryManagerProvider()?.handleMemoryPressure(level, pssMb)
            }

            // Crash report handler - will check for reports AFTER OOM detection
            crashReportHandler = MainCrashReportHandler(activity, activity.lifecycleScope)
        }

        // Replace splash theme with main theme immediately after super.onCreate
        activity.setTheme(R.style.Theme_Dashie)

        // Initialize Halite preferences and check setup state
        if (BuildConfig.ALLOW_URL_CONFIG) {
            halitePrefs = HalitePreferences(activity)

            // Set preferences on MemoryMonitor so it can check thresholds
            MemoryMonitor.setPreferences(halitePrefs)

            // Check for OOM kill from previous session
            oomKillDetected = checkForOomKill(halitePrefs)

            // Check if we just recovered from a WebView renderer termination
            if (halitePrefs.performance.rendererRecoveryPending) {
                Log.i(TAG, "✅ RENDERER RECOVERY VERIFIED: Activity successfully restarted after WebView renderer termination")
                PersistentLog.info("WEBVIEW", "✅ Renderer recovery VERIFIED - Activity restarted successfully")
                halitePrefs.performance.rendererRecoveryPending = false
            }

            // Clear hourly trend for new session (data was already included in OOM report if needed)
            halitePrefs.performance.clearHourlyTrend()

            // Mark app as running (will be cleared in onDestroy for clean shutdown)
            halitePrefs.performance.appWasRunning = true
            val newSessionStart = System.currentTimeMillis()
            halitePrefs.performance.appStartTime = newSessionStart

            // Rotate session tracking in diagnostics: snapshot the previous session's start
            // and last-heartbeat BEFORE starting the new session so the OOM report above
            // could read them (it already did), and so future reports can distinguish
            // "time the process was actually alive" from "wall-clock time since first start."
            // Must happen AFTER checkForOomKill() so that call reads the prior session's data.
            com.dashieapp.Dashie.halite.diagnostics.CrashDiagnosticsCollector.markNewSessionStarted(
                activity.applicationContext, newSessionStart
            )
            com.dashieapp.Dashie.halite.diagnostics.CrashDiagnosticsCollector.persistLifecycleEvent(
                activity.applicationContext,
                "onCreate",
                if (oomKillDetected) "after_oom_detected" else "clean_start"
            )

            // NOW check for pending crash reports (after OOM report is saved)
            crashReportHandler?.checkForPendingCrashReports()

            // Upload any pending silent abnormal-exit reports — this launch's
            // (if checkForOomKill just saved one) plus any prior failed-upload
            // retries. Runs on a background thread; no user-facing banner.
            AbnormalExitReporter.sweepAndUpload(activity.applicationContext)

            // Check for app update and clear caches if needed
            if (halitePrefs.performance.checkAndUpdateAppVersion(BuildConfig.VERSION_CODE)) {
                Log.i(TAG, "🔧 Halite: App updated - clearing wake word manifest cache")
                com.dashieapp.Dashie.wakeword.models.WakeWordModelManager(activity).clearManifestCache()
            }

            if (!halitePrefs.connection.isSetupComplete) {
                Log.i(TAG, "🔧 Halite: First run — onboarding will be handled by JS in kiosk shell")
                // Continue to MainActivity — kiosk-shell.js will detect !isSetupComplete
                // and show the JS-based onboarding overlay
            }
        }

        // Check for WiFi connectivity — redirect to WiFi setup if no internet.
        // Critical for devices where Dashie is the launcher: without this, a
        // no-WiFi boot drops the user into a broken WebView with no path
        // forward. (Previously gated on !ALLOW_URL_CONFIG to skip Halite,
        // which had its own onboarding — Halite is deprecated, so the gate
        // is gone and all flavors get the same WiFi recovery path.)
        // allowOfflineMode is set when the user previously chose "Use Dashie
        // without Internet" on the WiFi gate. It covers networks that reach
        // Dashie but fail Android's captive-portal validation, so we must not
        // trap them here on every reboot. Read directly (halitePrefs is only
        // initialized when ALLOW_URL_CONFIG is true).
        val connectionPrefs = halitePrefs?.connection
            ?: com.dashieapp.Dashie.halite.preferences.ConnectionPreferences(activity)
        val allowOfflineMode = connectionPrefs.allowOfflineMode
        if (isConnectedToInternet()) {
            // Record that this device has reached the internet at least once.
            // WifiSetupActivity reads this flag to decide whether a future
            // no-internet boot deserves a silent reconnect grace period
            // (previously-set-up device whose WiFi just hasn't associated yet)
            // vs. an immediate prompt (genuine first-time setup).
            if (!connectionPrefs.hasConnectedBefore) connectionPrefs.hasConnectedBefore = true
        } else if (!allowOfflineMode) {
            Log.i(TAG, "📶 No internet - launching WiFi setup")
            val intent = Intent(activity, com.dashieapp.Dashie.wifi.WifiSetupActivity::class.java)
            activity.startActivity(intent)
            activity.finish()
            return Result(shouldContinue = false, halitePrefs = null, crashReportHandler = null)
        }

        return Result(shouldContinue = true, halitePrefs = halitePrefs, crashReportHandler = crashReportHandler, oomKillDetected = oomKillDetected)
    }

    /**
     * Check for OOM kill from previous session.
     * If appWasRunning is still true, the app didn't shut down cleanly (OOM killed).
     */
    private fun checkForOomKill(halitePrefs: HalitePreferences): Boolean {
        if (!halitePrefs.performance.appWasRunning) return false

        val now = System.currentTimeMillis()
        val prevStart = halitePrefs.performance.appStartTime

        // Wall-clock time since the previous session started — NOT the same as how long
        // the process was actually alive. If the app was killed mid-session and nobody
        // restarted it for many hours, this number includes that dead time.
        val wallClockUptimeMs = now - prevStart
        val wallClockUptimeMinutes = wallClockUptimeMs / 1000 / 60

        // Actual alive-time: use the MemoryMonitor heartbeat (updated every 30s) as a proxy
        // for when the process was last known healthy. If we have that, aliveMs = lastHB - prevStart
        // and deadMs = now - lastHB. Gives us an unambiguous picture in crash reports.
        val prevTimings = com.dashieapp.Dashie.halite.diagnostics.CrashDiagnosticsCollector
            .getPreviousSessionTimings(activity.applicationContext)
        // Note: markNewSessionStarted hasn't run yet here (sequencing), so the "previous
        // session" in diagnostics prefs is actually the session BEFORE the one that just
        // died. We want the just-died session's values, which are still in the CURRENT
        // session slot. Pull them directly.
        val diagPrefs = activity.applicationContext.getSharedPreferences(
            com.dashieapp.Dashie.halite.diagnostics.CrashDiagnosticsCollector.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val lastHeartbeat = diagPrefs.getLong("diag_monitor_last_heartbeat", 0L)

        val actualAliveMinutes: Long
        val deadBeforeRestartMinutes: Long
        if (lastHeartbeat > 0 && lastHeartbeat >= prevStart) {
            actualAliveMinutes = (lastHeartbeat - prevStart) / 1000 / 60
            deadBeforeRestartMinutes = (now - lastHeartbeat) / 1000 / 60
        } else {
            actualAliveMinutes = -1  // unknown
            deadBeforeRestartMinutes = -1
        }

        val uptimeMinutes = if (actualAliveMinutes >= 0) actualAliveMinutes else wallClockUptimeMinutes
        val lastPssMb = halitePrefs.performance.lastMemoryReadingMb
        val lastRamPercent = halitePrefs.performance.lastRamPercent

        // Classify the OS-recorded exit reason up front (API 30+ via
        // ApplicationExitInfo; Unavailable on older devices). Doing this BEFORE
        // the OOM decision lets the OS's own verdict override Dashie's heuristic.
        val exitAnalysis = ExitReasonAnalyzer.analyzePreviousExit(
            activity.applicationContext, prevStart
        )

        // Consider it an OOM kill if ANY of:
        // - the OS itself recorded REASON_LOW_MEMORY — authoritative, and it
        //   catches OOMs the heartbeat misses (the 30s heartbeat predates the
        //   final-second memory spike, so PSS/RAM can look fine at death)
        // - PSS was high (>450MB) - direct memory pressure
        // - RAM % was high (>85%) - WebView renderer memory may not show in PSS
        val OOM_PSS_THRESHOLD_MB = 450
        val OOM_RAM_THRESHOLD_PERCENT = 85
        val osSaysLowMemory = exitAnalysis is ExitReasonAnalyzer.ExitAnalysis.LowMemory
        val isPssHigh = lastPssMb >= OOM_PSS_THRESHOLD_MB
        val isRamHigh = lastRamPercent >= OOM_RAM_THRESHOLD_PERCENT

        if (osSaysLowMemory || isPssHigh || isRamHigh) {
            val reason = when {
                osSaysLowMemory -> "OS REASON_LOW_MEMORY (heartbeat PSS=${lastPssMb}MB, RAM=${lastRamPercent}%)"
                isPssHigh && isRamHigh -> "PSS=${lastPssMb}MB, RAM=${lastRamPercent}%"
                isPssHigh -> "PSS=${lastPssMb}MB (RAM=${lastRamPercent}%)"
                else -> "RAM=${lastRamPercent}% (PSS=${lastPssMb}MB, WebView renderer likely culprit)"
            }
            Log.w(TAG, "⚠️ OOM KILL DETECTED: App was killed after ${uptimeMinutes}min - $reason")
            PersistentLog.error("OOM", "OOM kill detected! Uptime: ${uptimeMinutes}min - $reason")

            // Restore the HA iframe URL so the shell reloads to the correct page.
            // lastHaIframeUrl survives OOM kills (SharedPreferences). Copy it to crashRestoreUrl
            // which DashieWebViewClient checks when the shell page finishes loading.
            val lastHaUrl = halitePrefs.performance.lastHaIframeUrl
            if (!lastHaUrl.isNullOrEmpty()) {
                halitePrefs.performance.crashRestoreUrl = lastHaUrl
                Log.i(TAG, "OOM recovery: will restore HA URL: ${lastHaUrl.take(80)}")
                PersistentLog.info("OOM", "Saved crash restore URL: ${lastHaUrl.take(80)}")
            }

            // Save OOM crash report for the crash report dialog
            // Include hourly memory trend data (persisted to SharedPreferences, survives OOM kills)
            val hourlyPssTrend = halitePrefs.performance.hourlyPssTrend
            val hourlyRamTrend = halitePrefs.performance.hourlyRamTrend
            val hourlyHeapTrend = halitePrefs.performance.hourlyHeapTrend
            CrashHandler.saveOomKillReport(
                activity.applicationContext, uptimeMinutes, lastPssMb, lastRamPercent,
                hourlyPssTrend, hourlyRamTrend, hourlyHeapTrend,
                wallClockUptimeMinutes = wallClockUptimeMinutes,
                actualAliveMinutes = actualAliveMinutes,
                deadBeforeRestartMinutes = deadBeforeRestartMinutes,
                lastHeartbeatMillis = lastHeartbeat,
                sessionStartMillis = prevStart
            )
            com.dashieapp.Dashie.halite.diagnostics.CrashDiagnosticsCollector.persistLifecycleEvent(
                activity.applicationContext,
                "oom_detected",
                "alive=${actualAliveMinutes}m,dead=${deadBeforeRestartMinutes}m,pss=${lastPssMb}MB,ram=${lastRamPercent}%"
            )
            return true
        } else {
            // Not an OOM kill, but the app still died without a clean shutdown.
            // A native WebView/GPU crash lands here (oom=0, memory fine) — the
            // exit-reason analysis above (API 30+) tells the two apart. Only a
            // confirmed native crash or ANR is reported; plain signals and
            // benign reclaim are dropped (see ExitAnalysis.isReportableCrash).
            Log.i(TAG, "App was killed after ${uptimeMinutes}min (PSS=${lastPssMb}MB, RAM=${lastRamPercent}%) - not an OOM kill, classifying abnormal exit")
            PersistentLog.info("OOM", "App killed but memory low (PSS=${lastPssMb}MB < ${OOM_PSS_THRESHOLD_MB}MB, RAM=${lastRamPercent}% < ${OOM_RAM_THRESHOLD_PERCENT}%) - classifying abnormal exit")

            if (exitAnalysis.isReportableCrash) {
                AbnormalExitReporter.saveReport(
                    activity.applicationContext, exitAnalysis,
                    uptimeMinutes = uptimeMinutes,
                    actualAliveMinutes = actualAliveMinutes,
                    deadBeforeRestartMinutes = deadBeforeRestartMinutes,
                    lastHeartbeatMillis = lastHeartbeat,
                    sessionStartMillis = prevStart,
                    lastPssMb = lastPssMb,
                    lastRamPercent = lastRamPercent
                )
            } else {
                PersistentLog.info("OOM", "Abnormal exit classified as benign (${exitAnalysis.javaClass.simpleName}) - not reporting")
            }
            return false
        }
    }

    /**
     * Check if device has internet connectivity.
     */
    private fun isConnectedToInternet(): Boolean {
        val connectivityManager = activity.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

package com.dashieapp.Dashie.halite.diagnostics

import android.content.Context
import android.os.Build
import android.webkit.WebView
import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.preferences.PerformancePreferences
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global crash handler that captures uncaught exceptions and saves them to disk.
 *
 * Features:
 * - Captures stack traces before app terminates
 * - Saves crash reports to internal storage (survives app restart)
 * - Includes device info and diagnostic buffer contents
 * - Automatically chains to default handler after saving
 *
 * Usage:
 * ```
 * // In Application.onCreate() or MainActivity.onCreate()
 * CrashHandler.install(applicationContext)
 *
 * // On next launch, check for crash reports
 * CrashHandler.getPendingCrashReport(context)?.let { report ->
 *     // Show dialog to send crash report
 *     CrashHandler.clearPendingCrashReport(context)
 * }
 * ```
 */
object CrashHandler : Thread.UncaughtExceptionHandler {
    private const val TAG = "CrashHandler"
    private const val CRASH_FILE_NAME = "crash_report.txt"
    private const val MAX_CRASH_FILES = 1  // Keep only the most recent crash report

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: Context? = null

    // Re-entrancy guard. The default-handler chain can form a cycle: chromium's
    // JavaExceptionReporter re-installs itself as the default handler after we do
    // (on WebView/renderer init) and captures *us* as its delegate, while we still
    // hold it (or, after a double-install, ourselves) as `defaultHandler`. Without
    // this guard each loop re-runs buildCrashReport() — a heavy operation (binder
    // calls + formatting hundreds of log lines) — pegging the main thread until the
    // OS fires a 5s ANR and kills the process. Observed on rockchip rk3576s_u
    // (June 2026): the ANR tombstone showed CrashHandler.uncaughtException recursing
    // 244 deep. See CrashHandler diagnostics notes.
    private val isHandling = AtomicBoolean(false)

    /**
     * Install the crash handler. Call this early in app startup.
     *
     * Idempotent: a second call (or a call after chromium's reporter has chained
     * to us) is a no-op, and we never capture ourselves as [defaultHandler] —
     * doing so would create a self-recursion cycle.
     */
    fun install(context: Context) {
        appContext = context.applicationContext
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current === this) {
            Log.i(TAG, "Crash handler already installed — skipping re-install")
            return
        }
        defaultHandler = current
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.i(TAG, "Crash handler installed")
        DiagnosticBuffer.info("CRASH", "Crash handler installed")
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // If we're already handling an exception, the handler chain has looped
        // back into us. Do NOT rebuild the report or re-chain — just return. The
        // ART runtime terminates the process once the top-level handler returns,
        // so breaking the loop here is sufficient (and avoids the ANR).
        if (!isHandling.compareAndSet(false, true)) {
            Log.e(TAG, "uncaughtException re-entered — breaking handler cycle (report already captured)")
            return
        }
        try {
            Log.e(TAG, "FATAL: Uncaught exception on thread ${thread.name}", throwable)

            // Build the crash report
            val report = buildCrashReport(thread, throwable)

            // Save to disk
            saveCrashReport(report)

            Log.i(TAG, "Crash report saved to disk")

            // Schedule a relaunch (gated on autoReloadOnCrash + crash-loop guard)
            // before the process dies. The alarm fires from outside the dead
            // process, so it works without overlay permission or a live service.
            appContext?.let { CrashRecovery.maybeScheduleRelaunch(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash report", e)
        } finally {
            // Chain to the default handler (will terminate the app)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Build a comprehensive crash report string.
     */
    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val sb = StringBuilder()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

        sb.appendLine("═══════════════════════════════════════════════")
        sb.appendLine("     DASHIE LITE CRASH REPORT")
        sb.appendLine("═══════════════════════════════════════════════")
        sb.appendLine()
        sb.appendLine("Timestamp: $timestamp")
        sb.appendLine("Thread: ${thread.name} (id=${thread.id})")

        // Uptime
        try {
            appContext?.let { ctx ->
                val perfPrefs = PerformancePreferences(ctx)
                val startTime = perfPrefs.appStartTime
                if (startTime > 0) {
                    val uptimeMin = (System.currentTimeMillis() - startTime) / 60000
                    sb.appendLine("Uptime before crash: $uptimeMin minutes")
                }
            }
        } catch (_: Exception) { }
        sb.appendLine()

        // Device info
        sb.appendLine("─── DEVICE INFO ───")
        sb.appendLine("Manufacturer: ${Build.MANUFACTURER}")
        sb.appendLine("Model: ${Build.MODEL}")
        sb.appendLine("Device: ${Build.DEVICE}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Board: ${Build.BOARD}")
        sb.appendLine("Hardware: ${Build.HARDWARE}")
        sb.appendLine("WebView: ${getWebViewVersionInfo()}")
        sb.appendLine()

        // App info
        sb.appendLine("─── APP INFO ───")
        sb.appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Build Type: ${BuildConfig.BUILD_TYPE}")
        sb.appendLine("Environment: ${BuildConfig.ENVIRONMENT}")
        sb.appendLine("Flavor: ${BuildConfig.FLAVOR}")
        sb.appendLine()

        appContext?.let { sb.append(com.dashieapp.Dashie.api.ApiAccessObserver.reportSection(it)) }

        // Exception info
        sb.appendLine("─── EXCEPTION ───")
        sb.appendLine("Type: ${throwable.javaClass.name}")
        sb.appendLine("Message: ${throwable.message}")
        sb.appendLine()

        // Full stack trace
        sb.appendLine("─── STACK TRACE ───")
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        sb.appendLine(sw.toString())
        sb.appendLine()

        // Include diagnostic buffer contents (in-memory events)
        sb.appendLine("─── DIAGNOSTIC BUFFER (last ${DiagnosticBuffer.size()} events) ───")
        val diagnostics = DiagnosticBuffer.exportAsText()
        if (diagnostics.isNotEmpty()) {
            sb.appendLine(diagnostics)
        } else {
            sb.appendLine("(empty)")
        }
        sb.appendLine()

        // Include persistent log (disk-based, survives restarts - last 100 lines)
        sb.appendLine("─── PERSISTENT LOG (recent 100 lines) ───")
        try {
            val persistentLogs = PersistentLog.exportRecentLogs(100)
            if (persistentLogs.isNotEmpty()) {
                sb.appendLine(persistentLogs)
            } else {
                sb.appendLine("(empty)")
            }
        } catch (e: Exception) {
            sb.appendLine("(error reading: ${e.message})")
        }
        sb.appendLine()

        // Memory info
        sb.appendLine("─── MEMORY INFO ───")
        val runtime = Runtime.getRuntime()
        val maxMemMB = runtime.maxMemory() / (1024 * 1024)
        val totalMemMB = runtime.totalMemory() / (1024 * 1024)
        val freeMemMB = runtime.freeMemory() / (1024 * 1024)
        val usedMemMB = totalMemMB - freeMemMB
        sb.appendLine("Max: ${maxMemMB}MB")
        sb.appendLine("Total: ${totalMemMB}MB")
        sb.appendLine("Used: ${usedMemMB}MB")
        sb.appendLine("Free: ${freeMemMB}MB")
        sb.appendLine()

        // Enhanced diagnostics (permissions, runtime state, settings, installed apps)
        appContext?.let { ctx ->
            try {
                sb.appendLine(CrashDiagnosticsCollector.collectAll(ctx))
            } catch (e: Exception) {
                sb.appendLine("(error collecting diagnostics: ${e.message})")
            }
        }
        sb.appendLine()

        sb.appendLine("═══════════════════════════════════════════════")
        sb.appendLine("     END OF CRASH REPORT")
        sb.appendLine("═══════════════════════════════════════════════")

        return sb.toString()
    }

    /**
     * Save the crash report to internal storage.
     */
    private fun saveCrashReport(report: String) {
        val context = appContext ?: return

        try {
            // Use a timestamped filename to keep multiple reports
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "crash_${timestamp}.txt"

            val crashDir = File(context.filesDir, "crash_reports")
            if (!crashDir.exists()) {
                crashDir.mkdirs()
            }

            val crashFile = File(crashDir, filename)
            crashFile.writeText(report)

            Log.i(TAG, "Crash report saved to: ${crashFile.absolutePath}")

            // Clean up old crash reports (keep only MAX_CRASH_FILES)
            cleanupOldReports(crashDir)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash file: ${e.message}")
        }
    }

    /**
     * Keep only the most recent crash reports.
     */
    private fun cleanupOldReports(crashDir: File) {
        try {
            val files = crashDir.listFiles { file -> file.name.startsWith("crash_") && file.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            if (files.size > MAX_CRASH_FILES) {
                files.drop(MAX_CRASH_FILES).forEach { file ->
                    file.delete()
                    Log.d(TAG, "Deleted old crash report: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup old crash reports: ${e.message}")
        }
    }

    /**
     * Check if there are any pending crash reports from previous sessions.
     * @return The most recent crash report, or null if none exists.
     */
    fun getPendingCrashReport(context: Context): String? {
        try {
            val crashDir = File(context.filesDir, "crash_reports")
            if (!crashDir.exists()) return null

            val files = crashDir.listFiles { file -> file.name.startsWith("crash_") && file.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?: return null

            return files.firstOrNull()?.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read crash report: ${e.message}")
            return null
        }
    }

    /**
     * Get all pending crash reports.
     * @return List of crash reports (most recent first), or empty list if none.
     */
    fun getAllPendingCrashReports(context: Context): List<CrashReportInfo> {
        try {
            val crashDir = File(context.filesDir, "crash_reports")
            if (!crashDir.exists()) return emptyList()

            return crashDir.listFiles { file -> file.name.startsWith("crash_") && file.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?.map { file -> CrashReportInfo(file.name, file.lastModified(), file.readText()) }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read crash reports: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Clear all pending crash reports (call after user sends/dismisses them).
     */
    fun clearPendingCrashReports(context: Context) {
        try {
            val crashDir = File(context.filesDir, "crash_reports")
            if (!crashDir.exists()) return

            crashDir.listFiles()?.forEach { file ->
                file.delete()
            }
            Log.i(TAG, "Cleared all crash reports")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear crash reports: ${e.message}")
        }
    }

    /**
     * Get the count of pending crash reports.
     */
    fun getPendingCrashCount(context: Context): Int {
        return try {
            val crashDir = File(context.filesDir, "crash_reports")
            if (!crashDir.exists()) return 0

            crashDir.listFiles { file -> file.name.startsWith("crash_") && file.name.endsWith(".txt") }?.size ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Data class for crash report info.
     */
    data class CrashReportInfo(
        val filename: String,
        val timestamp: Long,
        val content: String
    )

    /**
     * Data class to capture WebView state at crash time.
     * This provides crucial debugging info for renderer crashes.
     */
    data class WebViewCrashState(
        val url: String?,
        val title: String?,
        val webViewVersion: String,
        val recentJsErrors: List<String> = emptyList(),
        val uptimeMinutes: Long = 0
    )

    /**
     * Compact WebView identifier for crash/diagnostics headers — no
     * Context needed. Returns "<provider> <version>" e.g.
     * "com.google.android.webview 134.0.6998.135", or "unknown" if the
     * platform doesn't expose the info.
     *
     * Used inside crash-report sections where Context isn't always wired
     * through. Prefer [getWebViewVersion] when Context is available — it
     * also gives a friendlier provider label.
     */
    fun getWebViewVersionInfo(): String {
        return try {
            val pkg = WebView.getCurrentWebViewPackage()
            if (pkg != null) {
                "${pkg.packageName} ${pkg.versionName}"
            } else {
                "unknown"
            }
        } catch (e: Exception) {
            "unknown (${e.javaClass.simpleName})"
        }
    }

    /**
     * Get the installed WebView version.
     * Tries Google WebView first, then falls back to AOSP WebView.
     */
    fun getWebViewVersion(context: Context): String {
        // List of known WebView providers in order of preference
        val webViewPackages = listOf(
            "com.google.android.webview" to "Google WebView",
            "com.android.webview" to "AOSP WebView",
            "com.android.chrome" to "Chrome WebView",
            "com.amazon.webview.chromium" to "Amazon WebView",
            "com.sec.android.app.sbrowser" to "Samsung WebView"
        )

        for ((packageName, label) in webViewPackages) {
            try {
                val packageInfo = context.packageManager.getPackageInfo(packageName, 0)
                return "$label ${packageInfo.versionName}"
            } catch (e: Exception) {
                // Package not found, try next
            }
        }

        // Last resort: try to get the current WebView package from WebViewCompat
        return try {
            val webViewPackage = android.webkit.WebView.getCurrentWebViewPackage()
            if (webViewPackage != null) {
                "${webViewPackage.packageName.substringAfterLast('.')} ${webViewPackage.versionName}"
            } else {
                "unknown"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Save a WebView renderer crash report.
     * Called from onRenderProcessGone() to create a report that will trigger the
     * crash report dialog on next app launch.
     *
     * @param context Application context
     * @param didCrash true if renderer crashed, false if system killed it
     * @param rendererPriority The renderer's priority at time of termination
     * @param webViewState Optional state info about the WebView at crash time
     */
    fun saveRendererCrashReport(
        context: Context,
        didCrash: Boolean,
        rendererPriority: Int?,
        webViewState: WebViewCrashState? = null
    ) {
        try {
            val sb = StringBuilder()
            sb.appendLine("=== WebView Renderer Process Terminated ===")
            sb.appendLine()

            // Timestamp
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            sb.appendLine("Time: ${dateFormat.format(Date())}")
            sb.appendLine()

            // App uptime
            try {
                val perfPrefs = PerformancePreferences(context)
                val startTime = perfPrefs.appStartTime
                if (startTime > 0) {
                    val uptimeMin = (System.currentTimeMillis() - startTime) / 60000
                    sb.appendLine("App uptime before crash: $uptimeMin minutes")
                    sb.appendLine()
                }
            } catch (_: Exception) { }

            // Crash details
            sb.appendLine("=== Renderer Crash Details ===")
            sb.appendLine("Did Crash: $didCrash")
            sb.appendLine("Renderer Priority: $rendererPriority")
            sb.appendLine()

            if (didCrash) {
                sb.appendLine("The WebView renderer process CRASHED.")
                sb.appendLine("This typically indicates a bug in WebView or problematic web content.")
            } else {
                sb.appendLine("The WebView renderer process was KILLED by the system.")
                sb.appendLine("This typically indicates memory pressure or low renderer priority.")
            }
            sb.appendLine()

            // Device info
            sb.appendLine("=== Device Info ===")
            sb.appendLine("Manufacturer: ${android.os.Build.MANUFACTURER}")
            sb.appendLine("Model: ${android.os.Build.MODEL}")
            sb.appendLine("Device: ${android.os.Build.DEVICE}")
            sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            sb.appendLine("WebView: ${getWebViewVersionInfo()}")
            sb.appendLine()

            // App info
            sb.appendLine("=== App Info ===")
            sb.appendLine("Version: ${com.dashieapp.Dashie.BuildConfig.VERSION_NAME}")
            sb.appendLine("Build Type: ${com.dashieapp.Dashie.BuildConfig.BUILD_TYPE}")
            sb.appendLine()

            // WebView info (crucial for renderer crashes!)
            sb.appendLine("=== WebView Info ===")
            val webViewVersion = webViewState?.webViewVersion ?: getWebViewVersion(context)
            sb.appendLine("WebView Version: $webViewVersion")
            if (webViewState != null) {
                sb.appendLine("URL at crash: ${webViewState.url ?: "unknown"}")
                sb.appendLine("Title: ${webViewState.title ?: "unknown"}")
                sb.appendLine("Uptime: ${webViewState.uptimeMinutes} minutes")
                if (webViewState.recentJsErrors.isNotEmpty()) {
                    sb.appendLine("Recent JS Errors (${webViewState.recentJsErrors.size}):")
                    webViewState.recentJsErrors.takeLast(5).forEach { error ->
                        sb.appendLine("  - $error")
                    }
                }
            }
            sb.appendLine()

            // Memory info
            sb.appendLine("=== Memory at Crash ===")
            val runtime = Runtime.getRuntime()
            val maxMem = runtime.maxMemory() / 1024 / 1024
            val totalMem = runtime.totalMemory() / 1024 / 1024
            val freeMem = runtime.freeMemory() / 1024 / 1024
            val usedMem = totalMem - freeMem
            sb.appendLine("Max Memory: ${maxMem}MB")
            sb.appendLine("Total Memory: ${totalMem}MB")
            sb.appendLine("Used Memory: ${usedMem}MB")
            sb.appendLine("Free Memory: ${freeMem}MB")
            sb.appendLine()

            // Diagnostic buffer (in-memory events)
            sb.appendLine("=== Recent Events (DiagnosticBuffer) ===")
            sb.appendLine(DiagnosticBuffer.exportAsText())
            sb.appendLine()

            // Persistent log (last 100 lines)
            sb.appendLine("=== Persistent Log (last 100 lines) ===")
            sb.appendLine(PersistentLog.exportRecentLogs(100))
            sb.appendLine()

            // Enhanced diagnostics (permissions, runtime state, settings, installed apps)
            try {
                sb.appendLine(CrashDiagnosticsCollector.collectAll(context))
            } catch (e: Exception) {
                sb.appendLine("(error collecting diagnostics: ${e.message})")
            }

            // Save the report
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "crash_renderer_${timestamp}.txt"

            val crashDir = File(context.filesDir, "crash_reports")
            if (!crashDir.exists()) {
                crashDir.mkdirs()
            }

            val crashFile = File(crashDir, filename)
            crashFile.writeText(sb.toString())

            Log.i(TAG, "Renderer crash report saved to: ${crashFile.absolutePath}")

            // Clean up old reports
            cleanupOldReports(crashDir)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save renderer crash report: ${e.message}")
        }
    }

    /**
     * Save an OOM kill crash report.
     * Called on app startup when we detect the app was killed by the system
     * (appWasRunning flag was still set from previous session).
     *
     * @param context Application context
     * @param uptimeMinutes How long the app was running before being killed
     * @param lastPssMb Last known PSS memory usage in MB
     * @param lastRamPercent Last known system RAM usage percentage
     */
    fun saveOomKillReport(
        context: Context,
        uptimeMinutes: Long,
        lastPssMb: Int,
        lastRamPercent: Int = 0,
        hourlyPssTrend: String = "",
        hourlyRamTrend: String = "",
        hourlyHeapTrend: String = "",
        /** Wall-clock ms between previous session's start and the restart detection, in minutes.
         *  NOT the same as actual alive time — if the device sat idle after kill, this includes that gap. */
        wallClockUptimeMinutes: Long = -1,
        /** Real alive-time derived from (lastHeartbeat - prevSessionStart). -1 when unknown. */
        actualAliveMinutes: Long = -1,
        /** Time between last heartbeat and the restart detection, i.e. how long the app was dead. -1 when unknown. */
        deadBeforeRestartMinutes: Long = -1,
        /** Raw millis for human-readable report output. */
        lastHeartbeatMillis: Long = 0L,
        sessionStartMillis: Long = 0L
    ) {
        try {
            val sb = StringBuilder()
            sb.appendLine("═══════════════════════════════════════════════")
            sb.appendLine("     DASHIE LITE OOM KILL REPORT")
            sb.appendLine("═══════════════════════════════════════════════")
            sb.appendLine()

            // Timestamp
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            sb.appendLine("Detected at: ${dateFormat.format(Date())}")
            sb.appendLine()

            // OOM Kill details
            sb.appendLine("─── OOM KILL DETAILS ───")
            sb.appendLine("The app was terminated by the Android system (lowmemorykiller).")
            sb.appendLine("This occurs when the device runs out of memory and the kernel")
            sb.appendLine("kills processes to free up RAM.")
            sb.appendLine()
            sb.appendLine("Uptime before kill: $uptimeMinutes minutes")
            // New breakdown: distinguish real alive-time (up to last heartbeat) from the
            // wall-clock gap (which includes any time the device sat idle after kill).
            if (actualAliveMinutes >= 0) {
                val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                sb.appendLine("  ├─ Actually alive: $actualAliveMinutes minutes (process_start → last_heartbeat)")
                sb.appendLine("  ├─ Dead before restart: $deadBeforeRestartMinutes minutes (last_heartbeat → restart_detection)")
                if (wallClockUptimeMinutes >= 0) {
                    sb.appendLine("  └─ Wall-clock elapsed: $wallClockUptimeMinutes minutes (prior-start → restart_detection)")
                }
                if (sessionStartMillis > 0) sb.appendLine("Session start: ${tsFormat.format(Date(sessionStartMillis))}")
                if (lastHeartbeatMillis > 0) sb.appendLine("Last heartbeat: ${tsFormat.format(Date(lastHeartbeatMillis))}")
            } else {
                sb.appendLine("  (no heartbeat data available — uptime is wall-clock between prior start and restart detection)")
            }
            sb.appendLine("Last known PSS: ${lastPssMb}MB")
            sb.appendLine("Last known RAM: ${lastRamPercent}%")
            if (lastRamPercent > 85 && lastPssMb < 450) {
                sb.appendLine("Note: High RAM with low PSS suggests WebView renderer was the culprit")
            }
            sb.appendLine()

            // Device + app + RTSP config — shared section builders
            sb.append(CrashReportSections.deviceInfo())
            sb.append(CrashReportSections.appInfo())
            sb.append(CrashReportSections.rtspConfig(context))

            // Dashboard snapshot + Lovelace analysis + current memory — shared builders
            sb.append(CrashReportSections.dashboardContent(context))
            sb.append(CrashReportSections.lovelaceConfig(context))
            sb.append(CrashReportSections.currentMemory())

            // Memory trend + system memory + app breakdown + processes — shared builders
            sb.append(CrashReportSections.memoryTrend(hourlyPssTrend, hourlyRamTrend, hourlyHeapTrend))
            sb.append(CrashReportSections.systemMemory(context, lastPssMb))
            sb.append(CrashReportSections.appMemoryBreakdown(context))
            sb.append(CrashReportSections.topProcesses(context))

            // Persistent log (survives OOM kills - last 100 lines)
            sb.appendLine("─── PERSISTENT LOG (before OOM kill) ───")
            try {
                val persistentLogs = PersistentLog.exportRecentLogs(100)
                if (persistentLogs.isNotEmpty()) {
                    sb.appendLine(persistentLogs)
                } else {
                    sb.appendLine("(empty)")
                }
            } catch (e: Exception) {
                sb.appendLine("(error reading: ${e.message})")
            }
            sb.appendLine()

            // Enhanced diagnostics (permissions, runtime state, settings, installed apps)
            try {
                sb.appendLine(CrashDiagnosticsCollector.collectAll(context))
            } catch (e: Exception) {
                sb.appendLine("(error collecting diagnostics: ${e.message})")
            }
            sb.appendLine()

            sb.appendLine("═══════════════════════════════════════════════")
            sb.appendLine("     END OF OOM KILL REPORT")
            sb.appendLine("═══════════════════════════════════════════════")

            // Save the report
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "crash_oom_${timestamp}.txt"

            val crashDir = File(context.filesDir, "crash_reports")
            if (!crashDir.exists()) {
                crashDir.mkdirs()
            }

            val crashFile = File(crashDir, filename)
            crashFile.writeText(sb.toString())

            Log.i(TAG, "OOM kill report saved to: ${crashFile.absolutePath}")

            // Clean up old reports
            cleanupOldReports(crashDir)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save OOM kill report: ${e.message}")
        }
    }
}

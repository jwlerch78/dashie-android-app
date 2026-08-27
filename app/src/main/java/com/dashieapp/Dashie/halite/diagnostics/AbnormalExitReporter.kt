package com.dashieapp.Dashie.halite.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reports *abnormal* app exits that the OOM detector deliberately ignores —
 * the app died without reaching `onDestroy`, but memory was not high, so it
 * was not an OOM kill. A WebView GPU-process SIGSEGV lands exactly here
 * (`oom=0`, memory fine). These exits were silently discarded before.
 *
 * Reports are written to a private `silent_crash_reports/` directory and
 * uploaded to `dashboard-telemetry` on the next launch with NO user-facing
 * banner — the banner flow (`crash_reports/`) is reserved for high-confidence
 * crashes (Java exceptions, OOM kills).
 *
 * On API 30+ [ExitReasonAnalyzer] supplies the real exit reason (and a
 * tombstone trace for native crashes); on older devices the report records
 * "cause undetermined" with the surrounding context (uptime, last heartbeat,
 * persistent-log tail) — still enough to correlate a crash to a release.
 */
object AbnormalExitReporter {
    private const val REPORT_DIR = "silent_crash_reports"
    private const val MAX_PENDING_FILES = 10
    private const val PERSISTENT_LOG_TAIL_LINES = 300

    /**
     * Build and persist an abnormal-exit report. Called from startup detection
     * when the previous session ended uncleanly and it was not an OOM kill.
     * Does not upload — [sweepAndUpload] handles delivery.
     *
     * Timing arguments mirror what `MainStartupInitializer.checkForOomKill`
     * already computes for the OOM path.
     */
    fun saveReport(
        context: Context,
        analysis: ExitReasonAnalyzer.ExitAnalysis,
        uptimeMinutes: Long,
        actualAliveMinutes: Long,
        deadBeforeRestartMinutes: Long,
        lastHeartbeatMillis: Long,
        sessionStartMillis: Long,
        lastPssMb: Int,
        lastRamPercent: Int
    ) {
        try {
            val report = buildReport(
                context, analysis, uptimeMinutes, actualAliveMinutes, deadBeforeRestartMinutes,
                lastHeartbeatMillis, sessionStartMillis, lastPssMb, lastRamPercent
            )
            val dir = File(context.filesDir, REPORT_DIR).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            File(dir, "abnormal_exit_$stamp.txt").writeText(report)
            PersistentLog.warn(
                "CRASH",
                "Abnormal-exit report saved ($stamp) — ${analysis.javaClass.simpleName}"
            )
        } catch (e: Exception) {
            PersistentLog.error("CRASH", "Failed to save abnormal-exit report: ${e.message}")
        }
    }

    /**
     * Upload all pending abnormal-exit reports, deleting each on success.
     * Failed uploads are kept for retry on the next launch. Fire-and-forget on
     * a background daemon thread — safe to call once per startup.
     */
    fun sweepAndUpload(context: Context) {
        Thread {
            try {
                val dir = File(context.filesDir, REPORT_DIR)
                val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
                    ?.sortedBy { it.lastModified() }
                if (files.isNullOrEmpty()) return@Thread

                // Guard against unbounded growth if the device is offline for a
                // long stretch — keep only the most recent MAX_PENDING_FILES.
                if (files.size > MAX_PENDING_FILES) {
                    files.dropLast(MAX_PENDING_FILES).forEach { it.delete() }
                }
                for (file in files.takeLast(MAX_PENDING_FILES)) {
                    val ok = CrashReportUploader.upload(context, file.readText())
                    if (ok) {
                        file.delete()
                        PersistentLog.info("CRASH", "Abnormal-exit report uploaded: ${file.name}")
                    } else {
                        PersistentLog.warn(
                            "CRASH",
                            "Abnormal-exit upload failed, will retry next launch: ${file.name}"
                        )
                    }
                }
            } catch (e: Exception) {
                PersistentLog.error("CRASH", "Abnormal-exit sweep failed: ${e.message}")
            }
        }.apply {
            isDaemon = true
            name = "abnormal-exit-sweep"
        }.start()
    }

    private fun buildReport(
        context: Context,
        analysis: ExitReasonAnalyzer.ExitAnalysis,
        uptimeMinutes: Long,
        actualAliveMinutes: Long,
        deadBeforeRestartMinutes: Long,
        lastHeartbeatMillis: Long,
        sessionStartMillis: Long,
        lastPssMb: Int,
        lastRamPercent: Int
    ): String {
        val sb = StringBuilder()
        val tsFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val tsShort = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        sb.appendLine("═══════════════════════════════════════════════")
        sb.appendLine("     DASHIE ABNORMAL EXIT REPORT")
        sb.appendLine("═══════════════════════════════════════════════")
        sb.appendLine()
        // Machine-readable marker so email triage (_HOW_TO.md §9) can distinguish
        // these from OOM kills and Java-exception crash reports.
        sb.appendLine("CRASH TYPE: ABNORMAL_EXIT")
        sb.appendLine("Detected at: ${tsFull.format(Date())}")
        sb.appendLine()

        sb.appendLine("─── WHAT HAPPENED ───")
        sb.appendLine("The app process ended without a clean shutdown (no onDestroy).")
        sb.appendLine("Dashie's memory heuristic did not see a high reading at the last")
        sb.appendLine("heartbeat — but on a fast or boot-time death the heartbeat can")
        sb.appendLine("predate the memory spike, so an OOM kill is NOT ruled out. A")
        sb.appendLine("native crash in the WebView process (GPU/EGL driver SIGSEGV)")
        sb.appendLine("also lands here. See the memory sections below.")
        sb.appendLine()

        sb.appendLine("─── SUSPECTED CAUSE ───")
        sb.appendLine(describeCause(analysis))
        sb.appendLine()

        val trace = analysis.traceOrNull()
        if (!trace.isNullOrBlank()) {
            sb.appendLine("─── OS TRACE (tombstone / ANR) ───")
            sb.appendLine(trace.trim())
            sb.appendLine()
        }

        // Device + app info — shared section builders
        sb.append(CrashReportSections.deviceInfo())
        sb.append(CrashReportSections.appInfo())

        sb.appendLine("─── SESSION TIMING ───")
        if (sessionStartMillis > 0) {
            sb.appendLine("Session start: ${tsShort.format(Date(sessionStartMillis))}")
        }
        if (lastHeartbeatMillis > 0) {
            sb.appendLine("Last heartbeat: ${tsShort.format(Date(lastHeartbeatMillis))}")
        }
        if (actualAliveMinutes >= 0) {
            sb.appendLine("Actually alive: $actualAliveMinutes min (start → last heartbeat)")
            sb.appendLine("Dead before restart: $deadBeforeRestartMinutes min")
        } else {
            sb.appendLine("Uptime (wall-clock): $uptimeMinutes min (no heartbeat data)")
        }
        sb.appendLine("Last known PSS: ${lastPssMb}MB")
        sb.appendLine("Last known RAM: ${lastRamPercent}%")
        sb.appendLine()

        // Shared sections — identical detail to the OOM-kill report so the two
        // report types never drift (CrashReportSections).
        sb.append(CrashReportSections.rtspConfig(context))
        sb.append(CrashReportSections.dashboardContent(context))
        sb.append(CrashReportSections.lovelaceConfig(context))
        sb.append(CrashReportSections.currentMemory())
        sb.append(CrashReportSections.memoryTrend(context))
        sb.append(CrashReportSections.systemMemory(context, lastPssMb))
        sb.append(CrashReportSections.appMemoryBreakdown(context))
        sb.append(CrashReportSections.topProcesses(context))
        sb.append(com.dashieapp.Dashie.api.ApiAccessObserver.reportSection(context))

        sb.appendLine("─── PERSISTENT LOG (last $PERSISTENT_LOG_TAIL_LINES lines) ───")
        sb.appendLine(PersistentLog.exportRecentLogs(PERSISTENT_LOG_TAIL_LINES))
        sb.appendLine()

        // Enhanced diagnostics (permissions, runtime state, settings, installed apps)
        try {
            sb.appendLine(CrashDiagnosticsCollector.collectAll(context))
        } catch (e: Exception) {
            sb.appendLine("(error collecting diagnostics: ${e.message})")
        }
        sb.appendLine()

        sb.appendLine("═══════════════════════════════════════════════")
        return sb.toString()
    }

    private fun describeCause(a: ExitReasonAnalyzer.ExitAnalysis): String = when (a) {
        is ExitReasonAnalyzer.ExitAnalysis.NativeCrash ->
            "Native crash — SIGSEGV/abort in native code, most likely the WebView\n" +
            "or a GPU/EGL driver. OS reason: ${a.description}"
        is ExitReasonAnalyzer.ExitAnalysis.Signal ->
            "Process killed by a signal. OS reason: ${a.description}"
        is ExitReasonAnalyzer.ExitAnalysis.LowMemory ->
            "OS low-memory kill (lmkd). Note: Dashie's own OOM heuristic did NOT\n" +
            "flag this — the OS disagreed. OS reason: ${a.description}"
        is ExitReasonAnalyzer.ExitAnalysis.Anr ->
            "Application Not Responding — the main thread stalled.\n" +
            "OS reason: ${a.description}"
        is ExitReasonAnalyzer.ExitAnalysis.Other ->
            "Other OS-reported exit reason (code ${a.reason}): ${a.description}"
        ExitReasonAnalyzer.ExitAnalysis.NotFound ->
            "Cause undetermined — no matching OS exit record was found."
        ExitReasonAnalyzer.ExitAnalysis.Unavailable ->
            "Cause undetermined — this device (API ${Build.VERSION.SDK_INT}) predates\n" +
            "ApplicationExitInfo (API 30+), so the OS exit reason is unavailable.\n" +
            "Most likely a native crash in the WebView process, or a force-stop."
        // Not reportable (filtered out by ExitAnalysis.isReportableCrash before
        // saveReport is called) — listed only to keep the `when` exhaustive.
        is ExitReasonAnalyzer.ExitAnalysis.AppCrash ->
            "Java/Kotlin uncaught exception. OS reason: ${a.description}"
        is ExitReasonAnalyzer.ExitAnalysis.UserStopped ->
            "User stopped the app. OS reason: ${a.description}"
        is ExitReasonAnalyzer.ExitAnalysis.AppUpgrade ->
            "App upgrade / package change. OS reason: ${a.description}"
    }
}

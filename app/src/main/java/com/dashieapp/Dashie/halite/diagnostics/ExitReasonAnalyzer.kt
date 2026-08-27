package com.dashieapp.Dashie.halite.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Classifies how the *previous* app process exited, using the OS-recorded
 * [ApplicationExitInfo] history (API 30+).
 *
 * Dashie's abnormal-exit detection ([com.dashieapp.Dashie.MainStartupInitializer]
 * `checkForOomKill`) knows the previous session did not reach `onDestroy`, but on
 * its own it cannot tell a native crash apart from a user force-stop or an app
 * upgrade. On API 30+ the OS records the real reason — including the tombstone
 * trace for native crashes (a SIGSEGV in the WebView GPU process, etc.), which is
 * the only way to capture that class of crash from the field.
 *
 * On API < 30 [ApplicationExitInfo] does not exist; [analyzePreviousExit]
 * returns [ExitAnalysis.Unavailable]. With no OS reason there is no way to
 * confirm a native crash, so [ExitAnalysis.isReportableCrash] is false and no
 * abnormal-exit report is filed on those devices.
 */
object ExitReasonAnalyzer {
    private const val TAG = "ExitReasonAnalyzer"

    /** Max characters of tombstone/ANR trace to attach (keeps the report small). */
    private const val MAX_TRACE_CHARS = 12_000

    /** Marker that begins the ART GC-timing histogram block in a thread dump. */
    private const val GC_DUMP_MARKER = "Dumping cumulative Gc timings"

    /** Max chars of process-header preamble to keep when budgeting around "main". */
    private const val HEADER_BUDGET = 2_000

    /** A thread-dump header line, e.g. `"main" prio=5` or `"Signal Catcher" daemon prio=10`. */
    private val THREAD_HEADER_REGEX = Regex("^\"[^\"]*\"\\s+(?:daemon\\s+)?prio=", RegexOption.MULTILINE)

    /** The main thread's header line specifically — the one that names the blocking call. */
    private val MAIN_THREAD_REGEX = Regex("^\"main\"\\s+prio=", RegexOption.MULTILINE)

    /** Classification of the previous process exit. */
    sealed class ExitAnalysis {
        /** API < 30 — the OS does not expose an exit reason on this device. */
        object Unavailable : ExitAnalysis()

        /** API 30+, but no matching exit record was found for the dead session. */
        object NotFound : ExitAnalysis()

        /** Native crash (SIGSEGV/abort). [trace] is the OS tombstone, may be null. */
        data class NativeCrash(val description: String, val trace: String?) : ExitAnalysis()

        /** Process killed by a signal, not classed as a native crash. */
        data class Signal(val description: String) : ExitAnalysis()

        /** OS low-memory kill (lmkd). */
        data class LowMemory(val description: String) : ExitAnalysis()

        /** Application Not Responding. [trace] is the ANR trace, may be null. */
        data class Anr(val description: String, val trace: String?) : ExitAnalysis()

        /** Java/Kotlin uncaught exception (already covered by CrashHandler). */
        data class AppCrash(val description: String) : ExitAnalysis()

        /** User force-stopped the app, or the OS stopped it at user request. */
        data class UserStopped(val description: String) : ExitAnalysis()

        /** Exit due to an app upgrade / package state change. */
        data class AppUpgrade(val description: String) : ExitAnalysis()

        /** Some other / unmapped OS reason. */
        data class Other(val reason: Int, val description: String) : ExitAnalysis()

        /**
         * True when this exit is worth reporting as a suspected crash.
         *
         * Scoped deliberately tight: only a confirmed native crash or ANR —
         * the specific exits this reporter was built to surface from the
         * field, both carrying an OS trace. Everything else is excluded:
         *  - [Signal] — `REASON_SIGNALED` is the OS explicitly saying "not a
         *    crash" (an external SIGKILL: background reclaim, force-stop,
         *    focus loss).
         *  - [Other], [NotFound], [Unavailable] — no crash signal at all;
         *    reporting these flooded the feed with routine process reclaim.
         *  - [LowMemory] — a real crash, but `checkForOomKill`'s OOM path
         *    owns it and never routes it here.
         *  - [AppCrash] — Java/Kotlin crashes are `CrashHandler`'s job.
         *  - [UserStopped], [AppUpgrade] — benign.
         */
        val isReportableCrash: Boolean
            get() = when (this) {
                is NativeCrash, is Anr -> true
                is Signal, is LowMemory, is Other, NotFound, Unavailable -> false
                is AppCrash, is UserStopped, is AppUpgrade -> false
            }

        /** The attached native/ANR trace, if any. */
        fun traceOrNull(): String? = when (this) {
            is NativeCrash -> trace
            is Anr -> trace
            else -> null
        }
    }

    /**
     * Classify how the previous app process exited.
     *
     * @param prevSessionStartMillis when the dead session started (used to
     *        sanity-match the OS exit record). Pass 0 if unknown.
     */
    fun analyzePreviousExit(context: Context, prevSessionStartMillis: Long): ExitAnalysis {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ExitAnalysis.Unavailable
        return try {
            analyzeApi30(context, prevSessionStartMillis)
        } catch (e: Throwable) {
            Log.w(TAG, "ApplicationExitInfo lookup failed: ${e.message}")
            ExitAnalysis.NotFound
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun analyzeApi30(context: Context, prevSessionStartMillis: Long): ExitAnalysis {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return ExitAnalysis.NotFound

        // Most-recent-first. We want the main process's most recent exit — the
        // in-process GPU crash that motivated this kills the main process, not a
        // sandboxed WebView child.
        val records = am.getHistoricalProcessExitReasons(context.packageName, 0, 0)
        val record = records.firstOrNull { it.processName == context.packageName }
            ?: return ExitAnalysis.NotFound

        // Sanity: the exit should be at/after the dead session's start (allow a
        // small clock-skew slack). If it predates that session, it's a stale record.
        if (prevSessionStartMillis > 0 && record.timestamp < prevSessionStartMillis - 60_000L) {
            return ExitAnalysis.NotFound
        }

        val desc = buildString {
            append("reason=${record.reason} (${reasonName(record.reason)})")
            append(", status=${record.status}, importance=${record.importance}")
            record.description?.let { append(", \"$it\"") }
        }

        return when (record.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE ->
                ExitAnalysis.NativeCrash(desc, readTrace(record))
            ApplicationExitInfo.REASON_ANR ->
                ExitAnalysis.Anr(desc, readTrace(record))
            ApplicationExitInfo.REASON_SIGNALED ->
                ExitAnalysis.Signal(desc)
            ApplicationExitInfo.REASON_LOW_MEMORY ->
                ExitAnalysis.LowMemory(desc)
            ApplicationExitInfo.REASON_CRASH ->
                ExitAnalysis.AppCrash(desc)
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED ->
                ExitAnalysis.UserStopped(desc)
            // 15 = PACKAGE_STATE_CHANGE, 16 = PACKAGE_UPDATED (API 33 constants —
            // referenced as literals so this stays compilable against API 30).
            15, 16 ->
                ExitAnalysis.AppUpgrade(desc)
            else ->
                ExitAnalysis.Other(record.reason, desc)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTrace(record: ApplicationExitInfo): String? {
        return try {
            record.traceInputStream?.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.isEmpty()) return null
                // The trace stream is plain text for ANRs, but a *binary
                // protobuf* Tombstone for native crashes (Android 12+).
                // Extract printable-ASCII runs so the result is human-readable
                // either way — for the protobuf this surfaces the build
                // fingerprint, signal, abort message and symbolized backtrace
                // frames. It also strips NUL/control bytes that Postgres `text`
                // columns reject (raw protobuf bytes previously 400'd the
                // crash-report upload).
                val text = extractPrintableStrings(bytes)
                if (text.isBlank()) null else prioritizeAndTruncate(text)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to read exit trace: ${e.message}")
            null
        }
    }

    /**
     * Cap the extracted trace at [MAX_TRACE_CHARS] while preserving the parts
     * that matter for diagnosis.
     *
     * An ART ANR/thread dump front-loads ~10KB of GC-timing histograms before
     * the thread stacks, so a naive first-N truncation discards the `"main"`
     * thread — the one frame that names the blocking call. This:
     *   1. drops the GC-timing histogram block (huge, never actionable), then
     *   2. if still over budget, keeps the process header + the `"main"` thread
     *      block and as many following threads as fit.
     * Native-crash tombstones (no GC block, no `"main"` header) fall through to
     * a plain head-truncation — unchanged behavior.
     */
    private fun prioritizeAndTruncate(raw: String): String {
        // 1. Strip the GC-timing histogram block: from the GC marker up to the
        //    first thread header (a line like `"main" prio=5`).
        var text = raw
        val gcStart = text.indexOf(GC_DUMP_MARKER)
        if (gcStart >= 0) {
            val firstThread = THREAD_HEADER_REGEX.find(text, gcStart)
            if (firstThread != null) {
                text = text.substring(0, gcStart) +
                    "…(GC timing histograms omitted)…\n" +
                    text.substring(firstThread.range.first)
            }
        }
        if (text.length <= MAX_TRACE_CHARS) return text

        // 2. Still over budget — center the kept window on the "main" thread so
        //    its stack survives even on a very long dump.
        val mainIdx = MAIN_THREAD_REGEX.find(text)?.range?.first
        if (mainIdx != null) {
            val firstThreadIdx = THREAD_HEADER_REGEX.find(text)?.range?.first ?: mainIdx
            val header = text.substring(0, minOf(firstThreadIdx, HEADER_BUDGET))
            val budgetForMain = (MAX_TRACE_CHARS - header.length - 64).coerceAtLeast(0)
            val end = minOf(mainIdx + budgetForMain, text.length)
            return header +
                (if (mainIdx > firstThreadIdx) "…(threads before main omitted)…\n" else "") +
                text.substring(mainIdx, end) +
                (if (end < text.length) "\n…(trace truncated)" else "")
        }

        // 3. No "main" header (e.g. native tombstone) — plain head truncation.
        return text.take(MAX_TRACE_CHARS) + "\n…(trace truncated)"
    }

    /**
     * Extract printable-ASCII runs (length >= 4) from raw bytes, one per line —
     * a `strings`-style pass. A plain-text ANR trace survives largely intact; a
     * binary protobuf tombstone yields its embedded strings (library names,
     * symbolized frames, abort message).
     */
    private fun extractPrintableStrings(bytes: ByteArray): String {
        val out = StringBuilder()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= 4) out.append(run).append('\n')
            run.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) run.append(c.toChar()) else flush()
        }
        flush()
        return out.toString()
    }

    private fun reasonName(reason: Int): String = when (reason) {
        0 -> "UNKNOWN"
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "reason_$reason"
    }
}

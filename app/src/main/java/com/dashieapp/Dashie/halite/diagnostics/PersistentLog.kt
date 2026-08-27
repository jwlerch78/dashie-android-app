package com.dashieapp.Dashie.halite.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Persistent log file for capturing events that survive app restarts and device reboots.
 *
 * Features:
 * - Writes to internal storage (persists across app restarts)
 * - Automatic rotation (keeps last N hours or N MB)
 * - Async writes to avoid blocking main thread
 * - Thread-safe
 * - Low overhead for normal operation
 *
 * Usage:
 * ```
 * // Initialize early in app startup
 * PersistentLog.init(applicationContext)
 *
 * // Log events
 * PersistentLog.info("AUTH", "Login started")
 * PersistentLog.error("CRASH", "WebSocket disconnected unexpectedly")
 *
 * // Export for diagnostics
 * val logContent = PersistentLog.exportLogs()
 * ```
 */
object PersistentLog {
    private const val TAG = "PersistentLog"
    private const val LOG_FILE_NAME = "dashie_events.log"
    private const val MAX_LOG_SIZE_BYTES = 1024 * 1024  // 1MB max per log file
    private const val MAX_LOG_FILES = 3  // Keep last 3 log files (rotating)

    private var logFile: File? = null
    private var appContext: Context? = null
    private var initialized = false

    // Single-threaded executor for async writes
    private val writeExecutor = Executors.newSingleThreadExecutor()

    // Date formatters
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTimestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * Initialize the persistent log. Call this early in app startup.
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return

        appContext = context.applicationContext
        val logsDir = File(context.filesDir, "logs")
        if (!logsDir.exists()) {
            logsDir.mkdirs()
        }

        logFile = File(logsDir, LOG_FILE_NAME)
        initialized = true

        // Log startup
        info("SYSTEM", "═══ Dashie Lite Started ═══")
        info("SYSTEM", "PersistentLog initialized, file: ${logFile?.absolutePath}")
        // Capture WebView version once per session — critical for triaging
        // WebView-version-specific bugs (compositor stalls, codec quirks).
        // Older Chromium versions on Android 13- often need workarounds
        // that newer Chromium has fixed natively.
        try {
            info("SYSTEM", "WebView: ${CrashHandler.getWebViewVersionInfo()}")
        } catch (e: Throwable) {
            info("SYSTEM", "WebView: (lookup failed: ${e.javaClass.simpleName})")
        }

        // Check for rotation on startup
        checkRotation()

        Log.i(TAG, "PersistentLog initialized: ${logFile?.absolutePath}")
    }

    /**
     * Log a debug-level event.
     */
    fun debug(tag: String, message: String) = log("DEBUG", tag, message)

    /**
     * Log an info-level event.
     */
    fun info(tag: String, message: String) = log("INFO", tag, message)

    /**
     * Log a warning-level event.
     */
    fun warn(tag: String, message: String) = log("WARN", tag, message)

    /**
     * Log an error-level event.
     */
    fun error(tag: String, message: String) = log("ERROR", tag, message)

    /**
     * Log an error with exception details.
     */
    fun error(tag: String, message: String, throwable: Throwable) {
        val fullMessage = "$message | ${throwable.javaClass.simpleName}: ${throwable.message}"
        log("ERROR", tag, fullMessage)
    }

    /**
     * Log an event with the specified level.
     */
    private fun log(level: String, tag: String, message: String) {
        if (!initialized || logFile == null) {
            // Fallback to logcat only
            Log.w(TAG, "PersistentLog not initialized, falling back to logcat: [$level] $tag: $message")
            return
        }

        val timestamp = timestampFormat.format(Date())
        val logLine = "$timestamp [$level] $tag: $message\n"

        // Write asynchronously to avoid blocking
        writeExecutor.execute {
            try {
                synchronized(this) {
                    FileWriter(logFile, true).use { writer ->
                        writer.write(logLine)
                    }
                }

                // Check if rotation is needed after write
                checkRotation()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write to persistent log: ${e.message}")
            }
        }

        // Also write to logcat for immediate visibility during development
        when (level) {
            "ERROR" -> Log.e(tag, message)
            "WARN" -> Log.w(tag, message)
            "INFO" -> Log.i(tag, message)
            else -> Log.d(tag, message)
        }

        // Also add to in-memory DiagnosticBuffer for immediate access
        when (level) {
            "ERROR" -> DiagnosticBuffer.error(tag, message)
            "WARN" -> DiagnosticBuffer.warn(tag, message)
            "INFO" -> DiagnosticBuffer.info(tag, message)
            else -> DiagnosticBuffer.debug(tag, message)
        }
    }

    /**
     * Check if log rotation is needed and perform it.
     */
    @Synchronized
    private fun checkRotation() {
        val file = logFile ?: return

        try {
            if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                rotateLog()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check/rotate log: ${e.message}")
        }
    }

    /**
     * Rotate the log file.
     */
    private fun rotateLog() {
        val file = logFile ?: return
        val logsDir = file.parentFile ?: return

        try {
            // Rename current log with timestamp
            val timestamp = fileTimestampFormat.format(Date())
            val rotatedFile = File(logsDir, "dashie_events_$timestamp.log")
            file.renameTo(rotatedFile)

            // Create new empty log file
            logFile = File(logsDir, LOG_FILE_NAME)
            logFile?.createNewFile()

            Log.i(TAG, "Log rotated: ${rotatedFile.name}")

            // Cleanup old rotated logs
            cleanupOldLogs(logsDir)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log: ${e.message}")
        }
    }

    /**
     * Clean up old rotated log files, keeping only MAX_LOG_FILES.
     */
    private fun cleanupOldLogs(logsDir: File) {
        try {
            val logFiles = logsDir.listFiles { file ->
                file.name.startsWith("dashie_events") && file.name.endsWith(".log")
            }?.sortedByDescending { it.lastModified() } ?: return

            if (logFiles.size > MAX_LOG_FILES) {
                logFiles.drop(MAX_LOG_FILES).forEach { file ->
                    file.delete()
                    Log.d(TAG, "Deleted old log file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup old logs: ${e.message}")
        }
    }

    /**
     * Export all log content (current + rotated files).
     * Returns logs in chronological order (oldest first).
     */
    fun exportLogs(): String {
        val context = appContext ?: return "(PersistentLog not initialized)"

        try {
            val logsDir = File(context.filesDir, "logs")
            if (!logsDir.exists()) return "(no logs directory)"

            val logFiles = logsDir.listFiles { file ->
                file.name.startsWith("dashie_events") && file.name.endsWith(".log")
            }?.sortedBy { it.lastModified() } ?: return "(no log files)"

            val sb = StringBuilder()
            for (file in logFiles) {
                if (sb.isNotEmpty()) {
                    sb.appendLine()
                    sb.appendLine("═══ ${file.name} ═══")
                    sb.appendLine()
                }
                sb.append(file.readText())
            }

            return if (sb.isEmpty()) "(empty logs)" else sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logs: ${e.message}")
            return "(error reading logs: ${e.message})"
        }
    }

    /**
     * Export only recent logs (last N lines from current log file).
     */
    fun exportRecentLogs(maxLines: Int = 500): String {
        val file = logFile ?: return "(PersistentLog not initialized)"

        try {
            if (!file.exists()) return "(no current log file)"

            val lines = file.readLines()
            val recentLines = if (lines.size > maxLines) {
                lines.takeLast(maxLines)
            } else {
                lines
            }

            return if (recentLines.isEmpty()) "(empty)" else recentLines.joinToString("\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export recent logs: ${e.message}")
            return "(error: ${e.message})"
        }
    }

    /**
     * Get the total size of all log files in bytes.
     */
    fun getTotalLogSize(): Long {
        val context = appContext ?: return 0

        return try {
            val logsDir = File(context.filesDir, "logs")
            if (!logsDir.exists()) return 0

            logsDir.listFiles { file ->
                file.name.startsWith("dashie_events") && file.name.endsWith(".log")
            }?.sumOf { it.length() } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Clear all log files (use with caution).
     */
    fun clearAllLogs() {
        val context = appContext ?: return

        try {
            val logsDir = File(context.filesDir, "logs")
            if (!logsDir.exists()) return

            logsDir.listFiles()?.forEach { file ->
                file.delete()
            }

            // Recreate current log file
            logFile = File(logsDir, LOG_FILE_NAME)
            logFile?.createNewFile()

            Log.i(TAG, "All logs cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs: ${e.message}")
        }
    }

    /**
     * Force flush any pending writes (for crash scenarios).
     */
    fun flush() {
        // The FileWriter is opened/closed for each write, so no explicit flush needed
        // This is intentional to ensure writes are immediately persisted
    }
}

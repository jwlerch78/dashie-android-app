package com.dashieapp.Dashie.halite.diagnostics

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-memory circular buffer for diagnostic events.
 *
 * Features:
 * - Fixed size (500 entries) to capture more history for overnight debugging
 * - Smart deduplication of rapid identical events
 * - Thread-safe for concurrent access
 * - Optional auto-persist to disk for critical events
 * - No disk I/O during normal operation (except critical events)
 *
 * Usage:
 * ```
 * DiagnosticBuffer.info("PIN", "afterTextChanged: len=4 attempts=0")
 * DiagnosticBuffer.warn("WS", "Connection lost")
 * DiagnosticBuffer.error("RTSP", "Resolution mismatch detected")
 * DiagnosticBuffer.critical("CRASH", "Memory warning received")  // Also persists to disk
 * ```
 *
 * For overnight debugging, use PersistentLog which writes directly to disk.
 */
object DiagnosticBuffer {
    private const val TAG = "DiagnosticBuffer"
    private const val MAX_ENTRIES = 500  // Increased from 200 for better overnight debugging
    private const val DEBOUNCE_MS = 1000L  // Events within 1 second are "rapid"

    private val buffer = ArrayDeque<DiagnosticEntry>(MAX_ENTRIES)

    // For deduplication of rapid identical events
    private var lastEvent: DiagnosticEntry? = null
    private var repeatCount = 0
    private var repeatStartTime = 0L

    data class DiagnosticEntry(
        val timestamp: Long,
        val tag: String,
        val level: String,  // DEBUG, INFO, WARN, ERROR
        val message: String
    )

    /**
     * Log an event with the specified level.
     * Rapid identical events are automatically deduplicated.
     */
    @Synchronized
    fun log(tag: String, level: String, message: String) {
        val now = System.currentTimeMillis()
        val last = lastEvent

        // Check if this is a rapid repeat of the same event
        if (last != null &&
            last.tag == tag &&
            last.message == message &&
            (now - last.timestamp) < DEBOUNCE_MS) {

            // First repeat - record start time
            if (repeatCount == 0) {
                repeatStartTime = last.timestamp
            }

            // Just increment counter, don't add new entry
            repeatCount++

            // Update lastEvent timestamp so debounce window slides
            lastEvent = DiagnosticEntry(now, tag, level, message)
            return
        }

        // If we had repeated events, log the summary first
        if (repeatCount > 0 && last != null) {
            val duration = now - repeatStartTime
            addEntry(DiagnosticEntry(
                timestamp = now,
                tag = last.tag,
                level = "INFO",
                message = "↑ repeated ${repeatCount}x over ${duration}ms"
            ))
            repeatCount = 0
        }

        // Log the new event
        val entry = DiagnosticEntry(now, tag, level, message)
        addEntry(entry)
        lastEvent = entry

        // Also log to logcat for local debugging
        when (level) {
            "ERROR" -> Log.e(tag, message)
            "WARN" -> Log.w(tag, message)
            "INFO" -> Log.i(tag, message)
            else -> Log.d(tag, message)
        }
    }

    private fun addEntry(entry: DiagnosticEntry) {
        if (buffer.size >= MAX_ENTRIES) {
            buffer.removeFirst()
        }
        buffer.addLast(entry)
    }

    /** Log a debug-level event */
    fun debug(tag: String, message: String) = log(tag, "DEBUG", message)

    /** Log an info-level event */
    fun info(tag: String, message: String) = log(tag, "INFO", message)

    /** Log a warning-level event */
    fun warn(tag: String, message: String) = log(tag, "WARN", message)

    /** Log an error-level event */
    fun error(tag: String, message: String) = log(tag, "ERROR", message)

    /**
     * Log a critical event that also gets persisted to disk via PersistentLog.
     * Use sparingly for events that must survive app crashes/restarts.
     */
    fun critical(tag: String, message: String) {
        log(tag, "CRITICAL", message)
        // Also persist to disk for crash survival
        try {
            PersistentLog.error(tag, "[CRITICAL] $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist critical event: ${e.message}")
        }
    }

    /**
     * Flush any pending repeat count before export.
     * Call this before exporting to ensure repeated events are captured.
     */
    @Synchronized
    fun flush() {
        if (repeatCount > 0 && lastEvent != null) {
            val now = System.currentTimeMillis()
            val duration = now - repeatStartTime
            addEntry(DiagnosticEntry(
                timestamp = now,
                tag = lastEvent!!.tag,
                level = "INFO",
                message = "↑ repeated ${repeatCount}x over ${duration}ms"
            ))
            repeatCount = 0
        }
    }

    /**
     * Export all entries as a list.
     * Flushes any pending repeated events first.
     */
    @Synchronized
    fun export(): List<DiagnosticEntry> {
        flush()
        return buffer.toList()
    }

    /**
     * Export all entries as formatted text.
     * Format: "HH:mm:ss.SSS [LEVEL] TAG: message"
     */
    @Synchronized
    fun exportAsText(): String {
        flush()
        val df = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return buffer.joinToString("\n") { entry ->
            "${df.format(Date(entry.timestamp))} [${entry.level}] ${entry.tag}: ${entry.message}"
        }
    }

    /**
     * Get the number of entries currently in the buffer.
     */
    @Synchronized
    fun size(): Int = buffer.size

    /**
     * Clear all entries from the buffer.
     */
    @Synchronized
    fun clear() {
        buffer.clear()
        lastEvent = null
        repeatCount = 0
        repeatStartTime = 0L
    }

    /**
     * Check if the buffer has any entries.
     */
    @Synchronized
    fun isEmpty(): Boolean = buffer.isEmpty()
}

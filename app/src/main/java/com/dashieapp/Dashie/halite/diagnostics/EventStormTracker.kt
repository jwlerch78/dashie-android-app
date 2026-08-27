package com.dashieapp.Dashie.halite.diagnostics

/**
 * Tracks rapid event storms (like WebSocket reconnection attempts) and logs
 * aggregate summaries instead of individual events to preserve buffer space.
 *
 * Use this for scenarios where:
 * - Events can fire 50+ times per minute
 * - Individual events aren't useful, but the pattern is
 * - You need to know when the storm started, how intense it was, and when it ended
 *
 * Usage:
 * ```
 * private val wsStormTracker = EventStormTracker("WS_STORM")
 *
 * fun onDisconnect() {
 *     wsStormTracker.recordEvent("disconnect")
 * }
 *
 * fun onConnected() {
 *     wsStormTracker.stormEnded("connection restored")
 * }
 *
 * fun onPauseWebView() {
 *     wsStormTracker.stormInterrupted("WebView paused")
 * }
 * ```
 *
 * Example output:
 * ```
 * 12:34:01.100 [WARN] WS_STORM: disconnect - monitoring for storm...
 * 12:34:11.100 [INFO] WS_STORM: Storm ongoing: 23 events in 10s (~2/sec)
 * 12:34:21.100 [INFO] WS_STORM: Storm ongoing: 47 events in 20s (~2/sec)
 * 12:34:25.000 [WARN] WS_STORM: Storm interrupted by WebView paused: 52 events over 24s
 * ```
 */
class EventStormTracker(
    private val tag: String,
    private val summaryIntervalMs: Long = 10_000L  // Log summary every 10 seconds
) {
    private var stormStartTime = 0L
    private var stormEventCount = 0
    private var lastSummaryTime = 0L
    private var isInStorm = false
    private var lastEventName = ""

    /**
     * Record an event. If events are happening rapidly, tracks as a storm.
     * @param eventName Name of the event (e.g., "reconnect_attempt", "disconnect")
     */
    @Synchronized
    fun recordEvent(eventName: String) {
        val now = System.currentTimeMillis()
        stormEventCount++
        lastEventName = eventName

        if (!isInStorm) {
            // First event - might be start of a storm
            isInStorm = true
            stormStartTime = now
            lastSummaryTime = now
            DiagnosticBuffer.warn(tag, "$eventName - monitoring for storm...")
            return
        }

        // Log periodic summary during storm
        if ((now - lastSummaryTime) >= summaryIntervalMs) {
            val durationSec = (now - stormStartTime) / 1000
            val rate = if (durationSec > 0) stormEventCount / durationSec else stormEventCount.toLong()
            DiagnosticBuffer.info(tag, "Storm ongoing: $stormEventCount events in ${durationSec}s (~$rate/sec)")
            lastSummaryTime = now
        }
    }

    /**
     * Call when the storm condition ends (e.g., connection restored).
     * @param reason Description of why the storm ended
     */
    @Synchronized
    fun stormEnded(reason: String) {
        if (!isInStorm) return

        val now = System.currentTimeMillis()
        val durationSec = (now - stormStartTime) / 1000

        DiagnosticBuffer.info(tag, "Storm ended ($reason): $stormEventCount events over ${durationSec}s")

        // Reset
        reset()
    }

    /**
     * Call when we intervene to stop the storm (e.g., pause WebView).
     * Unlike stormEnded(), this keeps tracking in case events resume.
     * @param action Description of the intervention (e.g., "WebView paused")
     */
    @Synchronized
    fun stormInterrupted(action: String) {
        if (!isInStorm) return

        val now = System.currentTimeMillis()
        val durationSec = (now - stormStartTime) / 1000

        DiagnosticBuffer.warn(tag, "Storm interrupted by $action: $stormEventCount events over ${durationSec}s")

        // Reset the summary timer but keep tracking
        lastSummaryTime = now
    }

    /**
     * Check if currently tracking a storm.
     */
    fun isActive(): Boolean = isInStorm

    /**
     * Get current storm statistics.
     * @return Stats if in a storm, null otherwise
     */
    @Synchronized
    fun getStats(): StormStats? {
        if (!isInStorm) return null
        val now = System.currentTimeMillis()
        val durationMs = now - stormStartTime
        return StormStats(
            eventCount = stormEventCount,
            durationMs = durationMs,
            eventsPerSecond = if (durationMs > 0) {
                stormEventCount * 1000.0 / durationMs
            } else 0.0,
            lastEventName = lastEventName
        )
    }

    /**
     * Reset the tracker to initial state.
     */
    @Synchronized
    fun reset() {
        isInStorm = false
        stormEventCount = 0
        stormStartTime = 0L
        lastSummaryTime = 0L
        lastEventName = ""
    }

    /**
     * Statistics about the current storm.
     */
    data class StormStats(
        val eventCount: Int,
        val durationMs: Long,
        val eventsPerSecond: Double,
        val lastEventName: String
    ) {
        val durationSeconds: Long get() = durationMs / 1000
    }
}

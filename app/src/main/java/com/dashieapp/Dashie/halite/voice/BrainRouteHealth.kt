package com.dashieapp.Dashie.halite.voice

/**
 * In-memory record of the last time a turn routed to the LOCAL brain and failed to reach it.
 *
 * Why this exists (visibility for the stale-route bug): `brain_route_cached` can go stale as
 * `local` while the add-on reports `cloud` — the device then routes every turn to a local brain
 * that isn't there and fails with a misleading "no API key" notice, writing NO telemetry (the
 * failure path was the least-instrumented surface). One stranded device looked identical to an
 * idle one. This lets the diagnostics heartbeat print the route + last-failure so a stranded
 * device announces itself.
 *
 * Deliberately a process-local singleton, NOT SharedPreferences:
 *  - the writer ([BrainConverseClient]) is a stateless client with no Context/prefs handle, so
 *    a static sink avoids threading prefs (and a hot-file edit) through the call site;
 *  - the reader (the heartbeat) runs in the same process, so in-memory is sufficient;
 *  - persistence across restart is unnecessary — a restart re-probes the route anyway, and the
 *    durable forensic trail is the `DiagnosticBuffer.warn("BRAIN", …)` line written alongside.
 *
 * Written from an OkHttp background thread, read from the heartbeat thread → fields are @Volatile.
 */
object BrainRouteHealth {
    @Volatile var lastLocalFailureAtMs: Long = 0L
        private set
    @Volatile var lastLocalFailureReason: String = ""
        private set

    /** Record a local-brain-unreachable event. [reason] is a short tag (e.g. "ioexception",
     *  "http_503"); truncated defensively. [atMs] is the event time (pass System.currentTimeMillis
     *  from the caller — this object never reads the clock so it stays trivially unit-testable). */
    fun recordLocalFailure(reason: String, atMs: Long) {
        lastLocalFailureReason = reason.take(40)
        lastLocalFailureAtMs = atMs
    }

    /** A one-line status for the diagnostics heartbeat. [nowMs] supplied by the caller.
     *  Examples: "fails=none" · "lastFail=ioexception 3m ago". */
    fun heartbeatStatus(nowMs: Long): String {
        val at = lastLocalFailureAtMs
        if (at <= 0L) return "fails=none"
        val agoMin = ((nowMs - at) / 60_000L).coerceAtLeast(0L)
        val reason = lastLocalFailureReason.ifEmpty { "unknown" }
        return "lastFail=$reason ${agoMin}m ago"
    }

    /** Test hook — reset between unit tests. */
    fun resetForTest() {
        lastLocalFailureAtMs = 0L
        lastLocalFailureReason = ""
    }
}

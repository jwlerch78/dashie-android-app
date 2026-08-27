package com.dashieapp.Dashie.halite.music.sendspin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.URL

/**
 * Manages the Sendspin client connection lifecycle for Dashie.
 *
 * Sendspin is the native protocol of Music Assistant, enabling synchronized
 * multi-room audio with Chromecast (via Sendspin Bridges), AirPlay, and
 * other Sendspin speakers.
 *
 * Lifecycle: created when music is enabled, destroyed when disabled.
 * The actual protocol handling is in [SendSpinClient] via [SendspinPlayerService].
 *
 * Auto-recovery: when the client disconnects after exhausting its internal
 * reconnect attempts (5 rapid retries), this manager schedules a fresh
 * connection attempt with exponential backoff (30s → 60s → 120s, max 5min).
 * This handles cases like MA server restarts, network changes, or stale
 * session cleanup without requiring the user to restart the app.
 */
class SendspinConnectionManager(
    private val context: Context,
    private val maServerUrl: String,   // e.g. "http://192.168.1.50:8095"
    private val deviceName: String,
    /** Sendspin WebSocket port. Default 8927 (MA's default). Override when mDNS
     *  discovery resolves a different port from the NsdServiceInfo. */
    private val sendspinPort: Int = DEFAULT_SENDSPIN_PORT,
    /** Sendspin WebSocket path. Default /sendspin (MA's default). Override when
     *  mDNS discovery resolves a custom path from the TXT record. */
    private val sendspinPath: String = DEFAULT_SENDSPIN_PATH
) {
    companion object {
        private const val TAG = "SendspinConnMgr"
        const val DEFAULT_SENDSPIN_PORT = 8927
        const val DEFAULT_SENDSPIN_PATH = "/sendspin"
        private const val INITIAL_RECOVERY_DELAY_MS = 30_000L  // 30 seconds
        private const val MAX_RECOVERY_DELAY_MS = 300_000L     // 5 minutes
        private const val MAX_RECOVERY_ATTEMPTS = 20           // ~45 min total before giving up
        private const val MEMORY_DEFER_DELAY_MS = 30_000L      // re-check interval under RUNNING_CRITICAL

        fun extractHost(url: String): String {
            return try {
                URL(url).host ?: ""
            } catch (_: Exception) { "" }
        }
    }

    enum class State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    var state: State = State.DISCONNECTED
        private set

    var onStateChanged: ((State) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var serverHost: String = ""
    private var stopped = false
    private var recoveryAttempts = 0
    private var recoveryRunnable: Runnable? = null

    /**
     * Start the Sendspin connection.
     * Extracts the MA server host and launches the foreground service.
     */
    fun start() {
        stopped = false
        recoveryAttempts = 0
        cancelRecovery()
        serverHost = extractHost(maServerUrl)
        if (serverHost.isBlank()) {
            Log.w(TAG, "Cannot determine MA server host from URL: $maServerUrl")
            setState(State.ERROR)
            return
        }
        Log.i(TAG, "Starting Sendspin connection to $serverHost:$sendspinPort$sendspinPath (device: $deviceName)")
        setState(State.CONNECTING)
        SendspinPlayerService.start(context, serverHost, sendspinPort, deviceName, serverPath = sendspinPath)
    }

    /** Stop the connection and service. */
    fun stop() {
        stopped = true
        cancelRecovery()
        SendspinPlayerService.stop(context)
        setState(State.DISCONNECTED)
        Log.i(TAG, "Stopped")
    }

    val isConnected: Boolean get() = state == State.CONNECTED

    // ========== Callbacks from SendspinPlayerService ==========

    fun onConnected() {
        handler.post {
            recoveryAttempts = 0  // Reset on successful connection
            cancelRecovery()
            setState(State.CONNECTED)
            Log.i(TAG, "Sendspin client connected")
        }
    }

    fun onDisconnected() {
        handler.post {
            if (stopped) return@post
            setState(State.ERROR)
            Log.w(TAG, "Sendspin client disconnected after reconnect exhaustion")
            scheduleRecovery()
        }
    }

    // ========== Auto-recovery ==========

    private fun scheduleRecovery() {
        if (stopped || serverHost.isBlank()) return
        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
            Log.w(TAG, "Auto-recovery exhausted ($MAX_RECOVERY_ATTEMPTS attempts). Manual restart required.")
            return
        }

        val delay = (INITIAL_RECOVERY_DELAY_MS * (1L shl recoveryAttempts.coerceAtMost(4)))
            .coerceAtMost(MAX_RECOVERY_DELAY_MS)
        recoveryAttempts++

        Log.i(TAG, "Scheduling auto-recovery #$recoveryAttempts in ${delay / 1000}s")
        val runnable = object : Runnable {
            override fun run() {
                if (stopped) return
                // Don't restart the service while the system is at RUNNING_CRITICAL —
                // crash data shows service starts under that pressure blow the FGS
                // deadline (main thread stalled) and take the app down with them.
                if (com.dashieapp.Dashie.halite.util.ServiceStartHelper.isMemoryCritical()) {
                    Log.w(TAG, "Auto-recovery #$recoveryAttempts deferred ${MEMORY_DEFER_DELAY_MS / 1000}s — memory pressure RUNNING_CRITICAL")
                    handler.postDelayed(this, MEMORY_DEFER_DELAY_MS)
                    return
                }
                Log.i(TAG, "Auto-recovery #$recoveryAttempts: restarting Sendspin service")
                setState(State.CONNECTING)
                SendspinPlayerService.start(context, serverHost, sendspinPort, deviceName, serverPath = sendspinPath)
            }
        }
        recoveryRunnable = runnable
        handler.postDelayed(runnable, delay)
    }

    private fun cancelRecovery() {
        recoveryRunnable?.let { handler.removeCallbacks(it) }
        recoveryRunnable = null
    }

    private fun setState(newState: State) {
        if (state == newState) return
        state = newState
        Log.d(TAG, "State: $newState")
        onStateChanged?.invoke(newState)
    }
}

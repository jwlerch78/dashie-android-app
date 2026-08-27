package com.dashieapp.Dashie.halite.telemetryDelegates

import android.util.Log
import com.dashieapp.Dashie.halite.HaConnectionMonitor
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import org.json.JSONObject

/**
 * Delegate handling HA WebSocket connection lifecycle signals from JavaScript.
 * Routes disconnect/reconnect/degraded/failed events to HaConnectionMonitor.
 */
class ConnectionDelegate {
    companion object {
        private const val TAG = "ConnectionDelegate"
    }

    var haConnectionMonitor: HaConnectionMonitor? = null

    /**
     * Handle HA WebSocket disconnect signal from JavaScript.
     */
    fun onHaDisconnect(jsonData: String) {
        try {
            val data = JSONObject(jsonData)
            val code = data.optInt("code", -1)
            val reason = data.optString("reason", "")

            Log.w(TAG, "HA disconnect signal from JS: code=$code")
            DiagnosticBuffer.warn("HA_CONN", "JS signal: disconnect code=$code")

            haConnectionMonitor?.onHaDisconnect(code, reason)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse HA disconnect signal: ${e.message}")
        }
    }

    /**
     * Handle HA WebSocket connect signal from JavaScript.
     */
    fun onHaConnected(jsonData: String) {
        try {
            val data = JSONObject(jsonData)
            val reconnectCount = data.optInt("reconnectCount", 0)

            Log.i(TAG, "HA connected signal from JS (reconnects: $reconnectCount)")
            DiagnosticBuffer.info("HA_CONN", "JS signal: connected (reconnects=$reconnectCount)")

            haConnectionMonitor?.onHaConnected()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse HA connected signal: ${e.message}")
        }
    }

    /**
     * Handle degraded connection signal (pings failing but WS not yet closed).
     */
    fun onConnectionDegraded(jsonData: String) {
        try {
            val data = JSONObject(jsonData)
            val consecutiveFailures = data.optInt("consecutiveFailures", 0)
            val lastPongAgo = data.optString("lastPongAgo", "unknown")

            Log.w(TAG, "Connection degraded: $consecutiveFailures pings failed, last pong: $lastPongAgo")
            DiagnosticBuffer.warn("HA_CONN", "Degraded: $consecutiveFailures pings failed")

            haConnectionMonitor?.onConnectionDegraded()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse connection degraded signal: ${e.message}")
        }
    }

    /**
     * Handle connection failed signal (auto-reload gave up after max retries).
     */
    fun onConnectionFailed(jsonData: String) {
        try {
            val data = JSONObject(jsonData)
            val reason = data.optString("reason", "unknown")
            val attempts = data.optInt("attempts", 0)

            Log.e(TAG, "Connection FAILED: $reason after $attempts attempts")
            DiagnosticBuffer.error("HA_CONN", "Connection failed: $reason ($attempts attempts)")

            haConnectionMonitor?.onConnectionFailed(reason, attempts)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse connection failed signal: ${e.message}")
        }
    }

    /**
     * Handle settings modal open/close signal for lighter pause behavior during outages.
     */
    fun setSettingsOpen(open: Boolean) {
        Log.i(TAG, "Settings modal ${if (open) "OPENED" else "CLOSED"}")
        haConnectionMonitor?.setUiInteractionActive(open)
    }
}

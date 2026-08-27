package com.dashieapp.Dashie.halite

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer

/**
 * Monitors network connectivity changes and triggers callbacks when
 * WiFi/network is lost or restored.
 *
 * This is used to restart RTSP streaming when the device reconnects
 * to WiFi after a disconnection (which closes the RTSP socket).
 */
class NetworkMonitor(
    private val context: Context
) {
    companion object {
        private const val TAG = "NetworkMonitor"
        private const val RESTART_DELAY_MS = 2000L  // Wait 2s after WiFi reconnects before restarting RTSP
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isNetworkAvailable = false
    private val handler = Handler(Looper.getMainLooper())

    // Callbacks
    var onNetworkLost: (() -> Unit)? = null
    var onNetworkAvailable: (() -> Unit)? = null

    /**
     * Start monitoring network connectivity changes
     */
    fun startMonitoring() {
        if (networkCallback != null) {
            Log.d(TAG, "Already monitoring network")
            return
        }

        // Check initial state
        isNetworkAvailable = isConnected()
        Log.i(TAG, "Starting network monitoring (initial state: ${if (isNetworkAvailable) "connected" else "disconnected"})")
        DiagnosticBuffer.info("NETWORK", "Monitor started (initial: ${if (isNetworkAvailable) "connected" else "disconnected"})")

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "━━━ NETWORK AVAILABLE ━━━")
                DiagnosticBuffer.info("NETWORK", "WiFi connected")

                // Only trigger callback if we were previously disconnected
                if (!isNetworkAvailable) {
                    isNetworkAvailable = true
                    Log.i(TAG, "Network restored - triggering callback after ${RESTART_DELAY_MS}ms delay")

                    // Delay callback to give WiFi time to fully stabilize
                    handler.postDelayed({
                        onNetworkAvailable?.invoke()
                    }, RESTART_DELAY_MS)
                } else {
                    Log.d(TAG, "Network already available, no action needed")
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "━━━ NETWORK LOST ━━━")
                DiagnosticBuffer.warn("NETWORK", "WiFi disconnected")

                isNetworkAvailable = false
                onNetworkLost?.invoke()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                Log.d(TAG, "Network capabilities changed: validated=$validated")
            }
        }

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            Log.i(TAG, "Network callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}", e)
            DiagnosticBuffer.error("NETWORK", "Failed to register monitor: ${e.message}")
            networkCallback = null
        }
    }

    /**
     * Stop monitoring network connectivity changes
     */
    fun stopMonitoring() {
        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
                Log.i(TAG, "Network callback unregistered")
                DiagnosticBuffer.info("NETWORK", "Monitor stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering network callback: ${e.message}")
            }
        }
        networkCallback = null
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Check if network is currently connected
     */
    private fun isConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Get current connection state
     */
    fun isNetworkConnected(): Boolean = isNetworkAvailable
}

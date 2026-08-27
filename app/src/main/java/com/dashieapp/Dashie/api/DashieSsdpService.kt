package com.dashieapp.Dashie.api

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * SSDP (Simple Service Discovery Protocol) service for standard Dashie.
 *
 * Enables Home Assistant auto-discovery by:
 * 1. Broadcasting NOTIFY (ssdp:alive) packets periodically
 * 2. Responding to M-SEARCH queries from HA
 *
 * Uses the service type: urn:dashie:service:Dashie:1
 * (Different from Dashie Lite which uses urn:dashie:service:DashieLite:1)
 */
class DashieSsdpService(
    private val context: Context,
    private val prefs: DashieApiPreferences
) {
    companion object {
        private const val TAG = "DashieSsdp"

        // SSDP constants
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val DASHIE_ST = "urn:dashie:service:Dashie:1"

        // Timing
        private const val ANNOUNCE_INTERVAL_MS = 30_000L  // 30 seconds
        private const val CACHE_CONTROL_MAX_AGE = 1800    // 30 minutes
        private const val SEARCH_RESPONSE_DELAY_MS = 500L // Random delay before responding
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var announceJob: Job? = null
    private var searchListenerJob: Job? = null
    private var multicastSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var isRunning = false

    /**
     * Start the SSDP service.
     * Begins periodic announcements and listens for M-SEARCH queries.
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "SSDP service already running")
            return
        }

        if (!prefs.apiEnabled) {
            Log.i(TAG, "API not enabled, skipping SSDP")
            return
        }

        isRunning = true
        Log.i(TAG, "Starting SSDP service for standard Dashie")

        // Acquire multicast lock to receive multicast packets
        // Without this, WiFi power-saving may block multicast reception
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("DashieSsdp")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "Multicast lock acquired")
            DiagnosticBuffer.info("NETWORK", "SSDP started (multicast lock held, announce every ${ANNOUNCE_INTERVAL_MS / 1000}s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire multicast lock", e)
            DiagnosticBuffer.error("NETWORK", "SSDP multicast lock failed: ${e.message}")
        }

        // Start the announcer (broadcasts ssdp:alive)
        startAnnouncer()

        // Start the M-SEARCH listener
        startSearchListener()
    }

    /**
     * Stop the SSDP service.
     * Sends ssdp:byebye and cleans up resources.
     */
    fun stop() {
        if (!isRunning) return

        Log.i(TAG, "Stopping SSDP service")
        isRunning = false

        // Send byebye notification
        scope.launch {
            try {
                sendByebye()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending byebye", e)
            }
        }

        // Cancel jobs
        announceJob?.cancel()
        searchListenerJob?.cancel()

        // Close sockets
        try {
            multicastSocket?.leaveGroup(InetAddress.getByName(SSDP_ADDRESS))
            multicastSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing multicast socket", e)
        }

        // Release multicast lock
        try {
            multicastLock?.release()
            Log.i(TAG, "Multicast lock released")
            DiagnosticBuffer.info("NETWORK", "SSDP stopped (multicast lock released)")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing multicast lock", e)
        }

        multicastSocket = null
        multicastLock = null
    }

    /**
     * Start periodic NOTIFY announcements.
     */
    private fun startAnnouncer() {
        announceJob = scope.launch {
            // Send initial announcement
            sendNotify()

            // Then announce periodically
            while (isActive && isRunning) {
                delay(ANNOUNCE_INTERVAL_MS)
                sendNotify()
            }
        }
    }

    /**
     * Start listening for M-SEARCH queries.
     */
    private fun startSearchListener() {
        searchListenerJob = scope.launch {
            try {
                // Create multicast socket
                multicastSocket = MulticastSocket(SSDP_PORT).apply {
                    reuseAddress = true
                    soTimeout = 5000 // 5 second timeout for clean shutdown
                }

                val group = InetAddress.getByName(SSDP_ADDRESS)
                multicastSocket?.joinGroup(group)

                Log.i(TAG, "Joined multicast group $SSDP_ADDRESS:$SSDP_PORT")

                val buffer = ByteArray(1024)

                while (isActive && isRunning) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        multicastSocket?.receive(packet)

                        val message = String(packet.data, 0, packet.length)
                        handleSearchRequest(message, packet.address, packet.port)
                    } catch (e: SocketTimeoutException) {
                        // Expected - just continue the loop
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e(TAG, "Error receiving SSDP packet", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up multicast listener", e)
            }
        }
    }

    /**
     * Handle an incoming M-SEARCH request.
     */
    private suspend fun handleSearchRequest(message: String, fromAddress: InetAddress, fromPort: Int) {
        // Check if this is an M-SEARCH
        if (!message.startsWith("M-SEARCH")) return

        // Parse the ST (Search Target) header
        val stLine = message.lines().find { it.startsWith("ST:", ignoreCase = true) }
        val st = stLine?.substringAfter(":")?.trim() ?: return

        // Check if they're looking for us or ssdp:all
        if (st != DASHIE_ST && st != "ssdp:all" && st != "upnp:rootdevice") {
            return
        }

        Log.d(TAG, "Received M-SEARCH for ST=$st from $fromAddress:$fromPort")

        // Random delay before responding (per SSDP spec)
        delay((Math.random() * SEARCH_RESPONSE_DELAY_MS).toLong())

        // Send response
        sendSearchResponse(fromAddress, fromPort)
    }

    /**
     * Send NOTIFY (ssdp:alive) announcement.
     */
    private suspend fun sendNotify() {
        val localIp = getLocalIpAddress() ?: return
        val apiPort = BuildConfig.API_PORT
        val haUrl = prefs.haBaseUrl.takeIf { it.isNotBlank() }

        val message = buildString {
            append("NOTIFY * HTTP/1.1\r\n")
            append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
            append("CACHE-CONTROL: max-age=$CACHE_CONTROL_MAX_AGE\r\n")
            append("LOCATION: http://$localIp:$apiPort/?cmd=deviceInfo\r\n")
            append("NT: $DASHIE_ST\r\n")
            append("NTS: ssdp:alive\r\n")
            append("SERVER: Dashie/${BuildConfig.VERSION_NAME} UPnP/1.1\r\n")
            append("USN: uuid:${prefs.deviceUuid}::$DASHIE_ST\r\n")
            append("X-DASHIE-NAME: ${prefs.deviceName}\r\n")
            append("X-DASHIE-API: http://$localIp:$apiPort/\r\n")
            // Include the HA URL this tablet is configured for (helps HA filter its own devices)
            if (haUrl != null) {
                append("X-DASHIE-HA-URL: $haUrl\r\n")
            }
            append("\r\n")
        }

        sendMulticast(message)
        Log.d(TAG, "Sent NOTIFY ssdp:alive from $localIp")
    }

    /**
     * Send ssdp:byebye notification when stopping.
     */
    private suspend fun sendByebye() {
        val message = buildString {
            append("NOTIFY * HTTP/1.1\r\n")
            append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
            append("NT: $DASHIE_ST\r\n")
            append("NTS: ssdp:byebye\r\n")
            append("USN: uuid:${prefs.deviceUuid}::$DASHIE_ST\r\n")
            append("\r\n")
        }

        sendMulticast(message)
        Log.d(TAG, "Sent NOTIFY ssdp:byebye")
    }

    /**
     * Send response to M-SEARCH query.
     */
    private suspend fun sendSearchResponse(toAddress: InetAddress, toPort: Int) {
        val localIp = getLocalIpAddress() ?: return
        val apiPort = BuildConfig.API_PORT
        val haUrl = prefs.haBaseUrl.takeIf { it.isNotBlank() }

        val message = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("CACHE-CONTROL: max-age=$CACHE_CONTROL_MAX_AGE\r\n")
            append("LOCATION: http://$localIp:$apiPort/?cmd=deviceInfo\r\n")
            append("ST: $DASHIE_ST\r\n")
            append("SERVER: Dashie/${BuildConfig.VERSION_NAME} UPnP/1.1\r\n")
            append("USN: uuid:${prefs.deviceUuid}::$DASHIE_ST\r\n")
            append("X-DASHIE-NAME: ${prefs.deviceName}\r\n")
            append("X-DASHIE-API: http://$localIp:$apiPort/\r\n")
            // Include the HA URL this tablet is configured for
            if (haUrl != null) {
                append("X-DASHIE-HA-URL: $haUrl\r\n")
            }
            append("\r\n")
        }

        withContext(Dispatchers.IO) {
            try {
                val socket = DatagramSocket()
                val data = message.toByteArray()
                val packet = DatagramPacket(data, data.size, toAddress, toPort)
                socket.send(packet)
                socket.close()
                Log.d(TAG, "Sent search response to $toAddress:$toPort")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending search response", e)
            }
        }
    }

    /**
     * Send a multicast packet to the SSDP address.
     */
    private suspend fun sendMulticast(message: String) {
        withContext(Dispatchers.IO) {
            try {
                val socket = DatagramSocket()
                val data = message.toByteArray()
                val address = InetAddress.getByName(SSDP_ADDRESS)
                val packet = DatagramPacket(data, data.size, address, SSDP_PORT)
                socket.send(packet)
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending multicast", e)
            }
        }
    }

    /**
     * Get the device's local IP address.
     */
    private fun getLocalIpAddress(): String? {
        try {
            // Try WiFi manager first (most reliable on Android)
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ipInt = wifiInfo?.ipAddress ?: 0

            if (ipInt != 0) {
                val ip = String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
                if (ip != "0.0.0.0") {
                    return ip
                }
            }

            // Fallback: iterate network interfaces
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    networkInterface.inetAddresses.toList().forEach { address ->
                        if (!address.isLoopbackAddress && address.hostAddress?.contains(".") == true) {
                            return address.hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP", e)
        }
        return null
    }
}

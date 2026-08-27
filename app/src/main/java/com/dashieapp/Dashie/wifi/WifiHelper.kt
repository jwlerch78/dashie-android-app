package com.dashieapp.Dashie.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.*

/**
 * WiFi Helper - Handles WiFi scanning, connection, and status
 *
 * Supports two approaches depending on Android version:
 * - Android 10+ (API 29+): Uses WifiNetworkSpecifier for app-specific connections
 * - Android 9 and below: Uses deprecated WifiConfiguration (still works)
 */
class WifiHelper(private val context: Context) {

    private val TAG = "WifiHelper"
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Callback for connection result
    interface ConnectionCallback {
        fun onConnected(ssid: String)
        fun onConnectionFailed(error: String)
    }

    // Callback for scan results
    interface ScanCallback {
        fun onScanResults(networks: List<WifiNetwork>)
        fun onScanFailed(error: String)
    }

    /**
     * Represents a discovered WiFi network
     */
    data class WifiNetwork(
        val ssid: String,
        val bssid: String,
        val signalStrength: Int, // 0-4 bars
        val isSecured: Boolean,
        val isConnected: Boolean = false
    )

    /**
     * Check if device has internet connectivity
     */
    fun isConnectedToInternet(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Check if WiFi is enabled
     */
    fun isWifiEnabled(): Boolean {
        return wifiManager.isWifiEnabled
    }

    /**
     * Enable WiFi (may require user interaction on newer Android versions)
     */
    fun enableWifi(): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            wifiManager.setWifiEnabled(true)
        } else {
            // Android 10+ requires user to manually enable WiFi via settings
            Log.w(TAG, "Cannot programmatically enable WiFi on Android 10+")
            false
        }
    }

    /**
     * Get currently connected WiFi SSID
     */
    fun getCurrentSsid(): String? {
        val wifiInfo = wifiManager.connectionInfo
        val ssid = wifiInfo?.ssid?.replace("\"", "") ?: return null
        return if (ssid == "<unknown ssid>") null else ssid
    }

    /**
     * Get WiFi status as JSON
     */
    fun getWifiStatus(): String {
        val status = JSONObject()
        status.put("wifiEnabled", isWifiEnabled())
        status.put("connected", isConnectedToInternet())
        status.put("ssid", getCurrentSsid() ?: JSONObject.NULL)

        // Signal strength (0-4)
        val wifiInfo = wifiManager.connectionInfo
        if (wifiInfo != null && wifiInfo.rssi != -127) {
            val level = WifiManager.calculateSignalLevel(wifiInfo.rssi, 5)
            status.put("signalStrength", level)
            status.put("rssi", wifiInfo.rssi)
        }

        return status.toString()
    }

    /**
     * Scan for available WiFi networks
     * Note: Requires ACCESS_FINE_LOCATION permission on Android 6+
     */
    @Suppress("DEPRECATION")
    fun scanNetworks(callback: ScanCallback) {
        if (!isWifiEnabled()) {
            callback.onScanFailed("WiFi is disabled")
            return
        }

        try {
            // Get scan results (may be cached if scan was recent)
            val scanResults = wifiManager.scanResults
            val networks = mutableListOf<WifiNetwork>()
            val seenSsids = mutableSetOf<String>()
            val currentSsid = getCurrentSsid()

            for (result in scanResults) {
                val ssid = result.SSID
                // Skip empty SSIDs and duplicates
                if (ssid.isNullOrBlank() || seenSsids.contains(ssid)) continue
                seenSsids.add(ssid)

                val signalLevel = WifiManager.calculateSignalLevel(result.level, 5)
                val isSecured = result.capabilities.contains("WPA") ||
                               result.capabilities.contains("WEP") ||
                               result.capabilities.contains("PSK")

                networks.add(WifiNetwork(
                    ssid = ssid,
                    bssid = result.BSSID,
                    signalStrength = signalLevel,
                    isSecured = isSecured,
                    isConnected = ssid == currentSsid
                ))
            }

            // Sort by signal strength (strongest first), but keep connected network at top
            val sorted = networks.sortedWith(compareBy({ !it.isConnected }, { -it.signalStrength }))

            Log.d(TAG, "Found ${sorted.size} WiFi networks")
            callback.onScanResults(sorted)

        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission required for WiFi scan", e)
            callback.onScanFailed("Location permission required")
        } catch (e: Exception) {
            Log.e(TAG, "WiFi scan failed", e)
            callback.onScanFailed(e.message ?: "Unknown error")
        }
    }

    /**
     * Get networks as JSON array
     */
    fun getNetworksJson(callback: (String) -> Unit) {
        scanNetworks(object : ScanCallback {
            override fun onScanResults(networks: List<WifiNetwork>) {
                val jsonArray = JSONArray()
                for (network in networks) {
                    val obj = JSONObject()
                    obj.put("ssid", network.ssid)
                    obj.put("bssid", network.bssid)
                    obj.put("signalStrength", network.signalStrength)
                    obj.put("isSecured", network.isSecured)
                    obj.put("isConnected", network.isConnected)
                    jsonArray.put(obj)
                }
                callback(jsonArray.toString())
            }

            override fun onScanFailed(error: String) {
                callback("[]")
            }
        })
    }

    /**
     * Connect to a WiFi network
     */
    fun connectToNetwork(ssid: String, password: String, callback: ConnectionCallback) {
        Log.i(TAG, "Attempting to connect to: $ssid")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ - Use WifiNetworkSpecifier
            connectWithNetworkSpecifier(ssid, password, callback)
        } else {
            // Android 9 and below - Use WifiConfiguration
            connectWithWifiConfiguration(ssid, password, callback)
        }
    }

    /**
     * Connect using WifiNetworkSpecifier (Android 10+)
     * This creates an app-specific network connection
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun connectWithNetworkSpecifier(ssid: String, password: String, callback: ConnectionCallback) {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.i(TAG, "✅ Connected to $ssid")

                // Bind this process to use the WiFi network
                connectivityManager.bindProcessToNetwork(network)

                // Unregister callback after success
                try {
                    connectivityManager.unregisterNetworkCallback(this)
                } catch (e: Exception) {
                    // Already unregistered
                }

                callback.onConnected(ssid)
            }

            override fun onUnavailable() {
                super.onUnavailable()
                Log.e(TAG, "❌ Network unavailable: $ssid")

                try {
                    connectivityManager.unregisterNetworkCallback(this)
                } catch (e: Exception) {
                    // Already unregistered
                }

                callback.onConnectionFailed("Network unavailable or wrong password")
            }
        }

        try {
            connectivityManager.requestNetwork(request, networkCallback)
            Log.d(TAG, "Network request sent for $ssid")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request network", e)
            callback.onConnectionFailed(e.message ?: "Connection failed")
        }
    }

    /**
     * Connect using WifiConfiguration (Android 9 and below)
     * This adds the network to the system's saved networks
     */
    @Suppress("DEPRECATION")
    private fun connectWithWifiConfiguration(ssid: String, password: String, callback: ConnectionCallback) {
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            preSharedKey = "\"$password\""

            // WPA/WPA2 settings
            allowedProtocols.set(WifiConfiguration.Protocol.RSN)
            allowedProtocols.set(WifiConfiguration.Protocol.WPA)
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
            allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)
        }

        try {
            // Check if network already exists
            val existingNetworks = wifiManager.configuredNetworks
            var networkId = -1

            for (existing in existingNetworks ?: emptyList()) {
                if (existing.SSID == "\"$ssid\"") {
                    networkId = existing.networkId
                    // Update password
                    config.networkId = networkId
                    wifiManager.updateNetwork(config)
                    break
                }
            }

            // Add network if not found
            if (networkId == -1) {
                networkId = wifiManager.addNetwork(config)
            }

            if (networkId == -1) {
                callback.onConnectionFailed("Failed to add network configuration")
                return
            }

            // Disconnect from current network
            wifiManager.disconnect()

            // Enable and connect to the new network
            val enabled = wifiManager.enableNetwork(networkId, true)
            val reconnected = wifiManager.reconnect()

            if (enabled && reconnected) {
                // Wait a bit for connection to establish.
                // Intentionally unscoped: bounded 3s delay, then a status callback.
                CoroutineScope(Dispatchers.Main).launch {
                    delay(3000)

                    if (getCurrentSsid() == ssid && isConnectedToInternet()) {
                        Log.i(TAG, "✅ Connected to $ssid (legacy method)")
                        callback.onConnected(ssid)
                    } else {
                        Log.e(TAG, "❌ Connection failed for $ssid")
                        callback.onConnectionFailed("Connection timed out")
                    }
                }
            } else {
                callback.onConnectionFailed("Failed to enable network")
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during WiFi connect", e)
            callback.onConnectionFailed("Permission denied")
        } catch (e: Exception) {
            Log.e(TAG, "WiFi connection failed", e)
            callback.onConnectionFailed(e.message ?: "Unknown error")
        }
    }

    /**
     * Suggest a network (Android 10+) - This adds to the user's saved networks
     * More persistent than NetworkSpecifier but requires user approval
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    fun suggestNetwork(ssid: String, password: String): Boolean {
        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .setIsAppInteractionRequired(true) // Show notification to user
            .build()

        val suggestionsList = listOf(suggestion)
        val status = wifiManager.addNetworkSuggestions(suggestionsList)

        return status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
    }

    /**
     * Open system WiFi settings
     */
    fun openWifiSettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    /**
     * Trigger WiFi reconnection - useful after adding a network
     */
    @Suppress("DEPRECATION")
    fun reconnect() {
        try {
            wifiManager.disconnect()
            wifiManager.reconnect()
            Log.d(TAG, "Triggered WiFi reconnect")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reconnect", e)
        }
    }
}

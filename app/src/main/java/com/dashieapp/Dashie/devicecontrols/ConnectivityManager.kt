package com.dashieapp.Dashie.devicecontrols

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/**
 * Manages WiFi and network connectivity status and settings.
 */
class NetworkConnectivityManager(private val context: Context) {

    companion object {
        private const val TAG = "ConnectivityManager"
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * Check if device has internet connectivity (validated)
     */
    fun isConnectedToInternet(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        val connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                       capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        Log.d(TAG, "📶 isConnectedToInternet(): $connected")
        return connected
    }

    /**
     * Get WiFi status as JSON string
     * Contains: wifiEnabled, connected, ssid, signalStrength (0-4), rssi
     */
    @Suppress("DEPRECATION")
    fun getWifiStatus(): String {
        val status = JSONObject()
        status.put("wifiEnabled", wifiManager.isWifiEnabled)

        // Check internet connectivity
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
        val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false
        status.put("connected", connected)

        // Get current SSID
        val wifiInfo = wifiManager.connectionInfo
        val ssid = wifiInfo?.ssid?.replace("\"", "")
        if (ssid != null && ssid != "<unknown ssid>") {
            status.put("ssid", ssid)

            // Signal strength (0-4)
            if (wifiInfo.rssi != -127) {
                val level = WifiManager.calculateSignalLevel(wifiInfo.rssi, 5)
                status.put("signalStrength", level)
                status.put("rssi", wifiInfo.rssi)
            }
        } else {
            status.put("ssid", JSONObject.NULL)
        }

        Log.d(TAG, "📶 getWifiStatus(): $status")
        return status.toString()
    }

    /**
     * Open system WiFi settings
     */
    fun openWifiSettings() {
        Log.i(TAG, "📶 Opening system WiFi settings")
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

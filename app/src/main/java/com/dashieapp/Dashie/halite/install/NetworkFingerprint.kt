package com.dashieapp.Dashie.halite.install

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest

/**
 * Generates a SHA-256 fingerprint of the local network for household detection.
 * All devices on the same WiFi produce the same fingerprint.
 *
 * Combines: default gateway IP + BSSID (AP MAC) + SSID
 * The hash ensures we never store actual network identifiers.
 */
object NetworkFingerprint {
    private const val TAG = "NetworkFingerprint"

    fun generate(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager

            val connectionInfo = wifiManager?.connectionInfo

            // BSSID: MAC address of the access point
            val bssid = connectionInfo?.bssid
                ?.takeIf { it != "02:00:00:00:00:00" && it != "<unknown ssid>" }

            // SSID
            @Suppress("DEPRECATION")
            val ssid = connectionInfo?.ssid
                ?.removeSurrounding("\"")
                ?.takeIf { it != "<unknown ssid>" && it.isNotEmpty() }

            // Default gateway
            val gateway = getDefaultGateway(wifiManager)

            if (bssid == null && gateway == null) {
                Log.d(TAG, "No network info available for fingerprint")
                return null
            }

            val raw = listOfNotNull(gateway, bssid, ssid).joinToString("|")
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray())
                .joinToString("") { "%02x".format(it) }

            Log.d(TAG, "Generated network fingerprint: ${hash.take(8)}...")
            hash
        } catch (e: Exception) {
            Log.w(TAG, "Failed to generate network fingerprint", e)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun getDefaultGateway(wifiManager: WifiManager?): String? {
        val dhcpInfo = wifiManager?.dhcpInfo ?: return null
        val gateway = dhcpInfo.gateway
        if (gateway == 0) return null

        return "${gateway and 0xFF}.${gateway shr 8 and 0xFF}" +
                ".${gateway shr 16 and 0xFF}.${gateway shr 24 and 0xFF}"
    }
}

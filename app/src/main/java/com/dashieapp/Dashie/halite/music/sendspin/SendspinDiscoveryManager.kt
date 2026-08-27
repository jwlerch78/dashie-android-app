package com.dashieapp.Dashie.halite.music.sendspin

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * mDNS discovery for Sendspin servers on the local network.
 *
 * Scans for `_sendspin-server._tcp.` services (Music Assistant's Sendspin
 * protocol). Used in speaker-only mode to auto-discover the MA server
 * without requiring the user to sign in or manually configure a server URL.
 *
 * Note: pre-2.24.13B versions used `_sendspin._tcp.` as the service type,
 * which does not match what MA advertises — discovery silently found
 * nothing even on working networks (cross-referenced against the reference
 * implementation at github.com/pantherale0/SendSpinlite, which uses
 * `_sendspin-server._tcp.`).
 *
 * Lifecycle: start when speaker-only mode is enabled, stop when disabled.
 */
class SendspinDiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "SendspinDiscovery"
        private const val SERVICE_TYPE = "_sendspin-server._tcp."
        private const val DEFAULT_PATH = "/sendspin"
    }

    data class DiscoveredServer(
        val name: String,
        val host: String,
        val port: Int,
        /** WebSocket path, read from the mDNS TXT "path" attribute if present. Default /sendspin. */
        val path: String
    )

    var onServerDiscovered: ((DiscoveredServer) -> Unit)? = null
    var onDiscoveryFailed: ((String) -> Unit)? = null

    private var nsdManager: NsdManager? = null
    private var isDiscovering = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.i(TAG, "Discovery started for $serviceType")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "Service found: ${serviceInfo.serviceName} (type: ${serviceInfo.serviceType})")
            // Resolve to get host/port
            nsdManager?.resolveService(serviceInfo, resolveListener)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "Service lost: ${serviceInfo.serviceName}")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(TAG, "Discovery stopped")
            isDiscovering = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery start failed: error $errorCode")
            isDiscovering = false
            onDiscoveryFailed?.invoke("mDNS discovery failed (error $errorCode)")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery stop failed: error $errorCode")
        }
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: error $errorCode")
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress ?: return
            val port = serviceInfo.port
            val name = serviceInfo.serviceName
            // TXT record "path" attribute tells us the WebSocket path. MA's default is
            // /sendspin but custom deployments can override. Leading slash enforced.
            val pathFromTxt = try {
                serviceInfo.attributes?.get("path")?.toString(Charsets.UTF_8)
            } catch (_: Exception) { null }
            val path = when {
                pathFromTxt.isNullOrEmpty() -> DEFAULT_PATH
                pathFromTxt.startsWith("/") -> pathFromTxt
                else -> "/$pathFromTxt"
            }
            Log.i(TAG, "Resolved: $name at $host:$port$path")
            onServerDiscovered?.invoke(DiscoveredServer(name, host, port, path))
        }
    }

    fun startDiscovery() {
        if (isDiscovering) return
        val mgr = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (mgr == null) {
            Log.e(TAG, "NsdManager not available")
            onDiscoveryFailed?.invoke("mDNS not available on this device")
            return
        }
        nsdManager = mgr
        isDiscovering = true
        Log.i(TAG, "Starting mDNS discovery for Sendspin servers")
        mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        if (!isDiscovering) return
        try {
            nsdManager?.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping discovery: ${e.message}")
        }
        isDiscovering = false
    }
}

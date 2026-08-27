package com.dashieapp.Dashie.halite

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Discovers Home Assistant instances on the local network
 * Uses both mDNS/NSD service discovery and network port scanning
 */
class HaDiscovery(private val context: Context) {

    private val TAG = "HaDiscovery"
    private var nsdManager: NsdManager? = null

    data class DiscoveredInstance(
        val ip: String,
        val port: Int = 8123,
        val name: String? = null,
        val url: String = "http://$ip:$port"
    )

    interface DiscoveryCallback {
        fun onInstanceFound(instance: DiscoveredInstance)
        fun onDiscoveryComplete(instances: List<DiscoveredInstance>)
        fun onProgress(scannedCount: Int, totalCount: Int)
        fun onError(message: String)
    }

    init {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    /**
     * Discover Home Assistant instances using multiple methods
     * Call from a background thread
     */
    fun discoverInstances(callback: DiscoveryCallback, timeoutMs: Long = 15000) {
        val discoveredInstances = CopyOnWriteArrayList<DiscoveredInstance>()

        // Latch for early return: counts down when ANY instance is found OR both methods finish
        val earlyReturnLatch = CountDownLatch(1)

        // Wrapper callback that triggers early return on first discovery
        val wrappedCallback = object : DiscoveryCallback {
            override fun onInstanceFound(instance: DiscoveredInstance) {
                callback.onInstanceFound(instance)
                // Signal early return — we found at least one instance
                earlyReturnLatch.countDown()
            }
            override fun onDiscoveryComplete(instances: List<DiscoveredInstance>) {
                callback.onDiscoveryComplete(instances)
            }
            override fun onProgress(scannedCount: Int, totalCount: Int) {
                callback.onProgress(scannedCount, totalCount)
            }
            override fun onError(message: String) {
                callback.onError(message)
            }
        }

        // Run both discovery methods in parallel
        val executor = Executors.newFixedThreadPool(2)
        val methodLatch = CountDownLatch(2)

        // Method 1: mDNS/NSD service discovery
        executor.submit {
            try {
                discoverViaNsd(discoveredInstances, wrappedCallback, timeoutMs / 2)
            } catch (e: Exception) {
                Log.e(TAG, "NSD discovery error: ${e.message}")
            } finally {
                methodLatch.countDown()
                // If both methods finish without finding anything, unblock early return
                if (methodLatch.count == 0L) earlyReturnLatch.countDown()
            }
        }

        // Method 2: Network port scan
        executor.submit {
            try {
                discoverViaPortScan(discoveredInstances, wrappedCallback, timeoutMs)
            } catch (e: Exception) {
                Log.e(TAG, "Port scan error: ${e.message}")
            } finally {
                methodLatch.countDown()
                // If both methods finish without finding anything, unblock early return
                if (methodLatch.count == 0L) earlyReturnLatch.countDown()
            }
        }

        // Wait for first discovery OR both methods to finish OR timeout
        try {
            val found = earlyReturnLatch.await(timeoutMs + 2000, TimeUnit.MILLISECONDS)
            if (discoveredInstances.isNotEmpty()) {
                // Give a brief window (500ms) for additional instances after first discovery
                Thread.sleep(500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discovery timeout: ${e.message}")
        }

        executor.shutdownNow()

        // Remove duplicates and notify completion
        val uniqueInstances = discoveredInstances.distinctBy { it.ip }
        Log.i(TAG, "Discovery complete: ${uniqueInstances.size} instance(s) found")
        callback.onDiscoveryComplete(uniqueInstances)
    }

    /**
     * Discover via mDNS/NSD service discovery
     */
    private fun discoverViaNsd(
        results: MutableList<DiscoveredInstance>,
        callback: DiscoveryCallback,
        timeoutMs: Long
    ) {
        if (nsdManager == null) {
            Log.w(TAG, "NSD Manager not available")
            return
        }

        val latch = CountDownLatch(1)
        var discoveryListener: NsdManager.DiscoveryListener? = null

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "NSD discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD service found: ${serviceInfo.serviceName}")

                // Resolve the service to get its IP
                nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "NSD resolve failed: $errorCode")
                    }

                    @Suppress("DEPRECATION")
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        val ip = si.host?.hostAddress
                        val port = si.port
                        if (ip != null) {
                            val instance = DiscoveredInstance(
                                ip = ip,
                                port = port,
                                name = si.serviceName
                            )
                            if (results.none { it.ip == ip }) {
                                results.add(instance)
                                Log.i(TAG, "NSD discovered HA at $ip:$port (${si.serviceName})")
                                callback.onInstanceFound(instance)
                            }
                        }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD start discovery failed: $errorCode")
                latch.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD stop discovery failed: $errorCode")
            }
        }

        try {
            nsdManager?.discoverServices("_home-assistant._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)

            // Wait for discovery
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)

            // Stop discovery
            try {
                nsdManager?.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                // Ignore - might already be stopped
            }
        } catch (e: Exception) {
            Log.e(TAG, "NSD discovery error: ${e.message}")
        }
    }

    /**
     * Discover via port scanning the local subnet
     */
    private fun discoverViaPortScan(
        results: MutableList<DiscoveredInstance>,
        callback: DiscoveryCallback,
        timeoutMs: Long
    ) {
        val localIp = getLocalIpAddress()
        if (localIp == null) {
            Log.w(TAG, "Could not determine local IP address")
            callback.onError("Could not determine local IP address")
            return
        }

        Log.i(TAG, "Local IP: $localIp, starting subnet scan")

        // Get subnet (assume /24 - most common home network)
        val subnet = localIp.substringBeforeLast(".")
        val port = 8123

        // Scan common HA IP ranges first, then rest of subnet
        val priorityIps = listOf(
            // Common router-assigned IPs
            "$subnet.100", "$subnet.101", "$subnet.102",
            "$subnet.50", "$subnet.51", "$subnet.52",
            // Common static IPs for servers
            "$subnet.10", "$subnet.11", "$subnet.20", "$subnet.21",
            // Docker/VM common IPs
            "$subnet.2", "$subnet.3", "$subnet.4", "$subnet.5"
        )

        // Build full scan list: priority IPs first, then rest of subnet
        val allIps = priorityIps.toMutableList()
        for (i in 1..254) {
            val ip = "$subnet.$i"
            if (ip !in allIps && ip != localIp) {
                allIps.add(ip)
            }
        }

        val totalCount = allIps.size
        val scannedCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(20) // Parallel scanning
        val scanLatch = CountDownLatch(totalCount)

        for (ip in allIps) {
            executor.submit {
                try {
                    if (isHomeAssistant(ip, port)) {
                        val instance = DiscoveredInstance(ip = ip, port = port)
                        if (results.none { it.ip == ip }) {
                            results.add(instance)
                            Log.i(TAG, "Port scan discovered HA at $ip:$port")
                            callback.onInstanceFound(instance)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore - IP not reachable
                } finally {
                    val count = scannedCount.incrementAndGet()
                    if (count % 25 == 0) {
                        callback.onProgress(count, totalCount)
                    }
                    scanLatch.countDown()
                }
            }
        }

        // Wait for scan to complete or timeout
        try {
            scanLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.d(TAG, "Port scan timeout/interrupted")
        }

        executor.shutdownNow()
        callback.onProgress(totalCount, totalCount)
    }

    /**
     * Check if the given IP:port is a Home Assistant instance
     * Uses /manifest.json endpoint which is publicly accessible and doesn't trigger IP bans
     */
    private fun isHomeAssistant(ip: String, port: Int): Boolean {
        return try {
            // Use /manifest.json instead of /api/ to avoid triggering HA's IP ban mechanism
            // manifest.json is publicly accessible and returns HA-specific data
            val url = URL("http://$ip:$port/manifest.json")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            val contentType = connection.contentType ?: ""

            // If we get a 200 response, check if it's actually HA's manifest
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                // HA manifest contains "name": "Home Assistant"
                val isHA = response.contains("\"name\":\"Home Assistant\"") ||
                           response.contains("\"name\": \"Home Assistant\"")
                if (isHA) {
                    Log.d(TAG, "Found Home Assistant at $ip:$port via manifest.json")
                }
                isHA
            } else {
                connection.disconnect()
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the device's local IP address
     */
    private fun getLocalIpAddress(): String? {
        try {
            // Try using ConnectivityManager first (more reliable on newer Android)
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager != null) {
                val network = connectivityManager.activeNetwork
                val linkProperties = connectivityManager.getLinkProperties(network)
                linkProperties?.linkAddresses?.forEach { linkAddress ->
                    val address = linkAddress.address
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }

            // Fallback: enumerate network interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP: ${e.message}")
        }
        return null
    }
}

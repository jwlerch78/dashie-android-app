package com.dashieapp.Dashie.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceInfoHelper {
    private const val TAG = "DeviceInfo"

    // Cache: static device info (CPU, model, screen, etc.) never changes at runtime
    private var cachedStaticInfo: JSONObject? = null

    // Cache: IP address with TTL (can change on WiFi reconnect)
    private var cachedIpAddress: String? = null
    private var ipCacheTimestamp: Long = 0
    private const val IP_CACHE_TTL_MS = 60_000L  // 60 seconds

    /**
     * Get comprehensive device information.
     * Static fields (CPU, model, screen, ABIs, device type) are cached after first call.
     * IP address is cached with a 60-second TTL.
     * Memory stats are always fresh.
     */
    fun getDeviceInfo(context: Context): String {
        val staticInfo = cachedStaticInfo ?: buildStaticInfo(context).also { cachedStaticInfo = it }

        // Clone and add dynamic fields
        val result = JSONObject(staticInfo.toString())
        result.put("localIpAddress", getIpAddressCached(context))
        result.put("memory", buildMemoryInfo())

        return result.toString()
    }

    /**
     * Get cached IP address (refreshed every 60 seconds).
     * Use this when only the IP is needed (e.g., RTSP settings).
     */
    fun getIpAddressCached(context: Context): String {
        val now = System.currentTimeMillis()
        if (cachedIpAddress != null && now - ipCacheTimestamp < IP_CACHE_TTL_MS) {
            return cachedIpAddress!!
        }
        cachedIpAddress = getLocalIpAddress(context)
        ipCacheTimestamp = now
        return cachedIpAddress!!
    }

    /**
     * Build static device info (everything that doesn't change at runtime).
     * Only called once — result is cached.
     */
    private fun buildStaticInfo(context: Context): JSONObject {
        val deviceInfo = JSONObject()

        try {
            // Device ID — hardware-tied stable ID where available (Widevine
            // MediaDrm), falling back to ANDROID_ID otherwise. Keeps the
            // "androidId" key name for backward compat with downstream
            // consumers, but the value matches what the control center and
            // server-side logs show.
            deviceInfo.put("androidId", StableDeviceId.read(context))

            // Basic Android Build information
            deviceInfo.put("manufacturer", Build.MANUFACTURER)
            deviceInfo.put("brand", Build.BRAND)
            deviceInfo.put("model", Build.MODEL)
            deviceInfo.put("device", Build.DEVICE)
            deviceInfo.put("product", Build.PRODUCT)
            deviceInfo.put("hardware", Build.HARDWARE)
            deviceInfo.put("board", Build.BOARD)

            // OS Version
            deviceInfo.put("androidVersion", Build.VERSION.RELEASE)
            deviceInfo.put("sdkInt", Build.VERSION.SDK_INT)
            deviceInfo.put("buildId", Build.ID)

            // Display information
            val displayMetrics = context.resources.displayMetrics
            deviceInfo.put("screenWidth", displayMetrics.widthPixels)
            deviceInfo.put("screenHeight", displayMetrics.heightPixels)
            deviceInfo.put("densityDpi", displayMetrics.densityDpi)
            deviceInfo.put("density", displayMetrics.density)

            // CPU/Processor information (subprocess — only runs once now)
            val cpuInfo = getCPUInfo()
            deviceInfo.put("cpu", cpuInfo)

            // Supported ABIs
            val abis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Build.SUPPORTED_ABIS.joinToString(", ")
            } else {
                @Suppress("DEPRECATION")
                "${Build.CPU_ABI}, ${Build.CPU_ABI2}"
            }
            deviceInfo.put("supportedAbis", abis)

            // Device type detection
            deviceInfo.put("isFireTV", isFireTV())
            deviceInfo.put("isFireTablet", isFireTablet())
            deviceInfo.put("isTv", isTVDevice(context))
            deviceInfo.put("isTablet", isTablet(context))

            // Capability flags
            deviceInfo.put("supportsDarkMode", !isFireTablet())

            // Package/App information
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            deviceInfo.put("appVersionName", packageInfo.versionName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                deviceInfo.put("appVersionCode", packageInfo.longVersionCode)
            } else {
                @Suppress("DEPRECATION")
                deviceInfo.put("appVersionCode", packageInfo.versionCode)
            }

            Log.d(TAG, "Static device info built and cached")

        } catch (e: Exception) {
            Log.e(TAG, "Error building static device info: ${e.message}", e)
            deviceInfo.put("error", e.message ?: "Unknown error")
        }

        return deviceInfo
    }

    /**
     * Build current memory stats (always fresh, no caching).
     */
    private fun buildMemoryInfo(): JSONObject {
        val runtime = Runtime.getRuntime()
        val memoryInfo = JSONObject()
        memoryInfo.put("maxMemoryMB", runtime.maxMemory() / (1024 * 1024))
        memoryInfo.put("totalMemoryMB", runtime.totalMemory() / (1024 * 1024))
        memoryInfo.put("freeMemoryMB", runtime.freeMemory() / (1024 * 1024))
        return memoryInfo
    }

    /**
     * Read CPU information from /proc/cpuinfo
     */
    private fun getCPUInfo(): JSONObject {
        val cpuInfo = JSONObject()

        try {
            val process = Runtime.getRuntime().exec("cat /proc/cpuinfo")
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            var processorCount = 0
            var processorName = "Unknown"
            var hardware = "Unknown"

            while (reader.readLine().also { line = it } != null) {
                line?.let {
                    when {
                        it.startsWith("processor") -> processorCount++
                        it.startsWith("model name") || it.startsWith("Processor") -> {
                            processorName = it.substringAfter(":").trim()
                        }
                        it.startsWith("Hardware") -> {
                            hardware = it.substringAfter(":").trim()
                        }
                    }
                }
            }

            reader.close()
            process.waitFor()

            cpuInfo.put("processorCount", maxOf(processorCount, Runtime.getRuntime().availableProcessors()))
            cpuInfo.put("processorName", processorName)
            cpuInfo.put("hardware", hardware)
            cpuInfo.put("availableProcessors", Runtime.getRuntime().availableProcessors())

        } catch (e: Exception) {
            Log.e(TAG, "Error reading CPU info: ${e.message}")
            cpuInfo.put("error", e.message ?: "Cannot read CPU info")
            cpuInfo.put("availableProcessors", Runtime.getRuntime().availableProcessors())
        }

        return cpuInfo
    }

    /**
     * Get the device's local IP address (WiFi/Ethernet)
     * Returns the first non-loopback IPv4 address found
     */
    private fun getLocalIpAddress(context: Context): String {
        try {
            // Method 1: Try NetworkInterface enumeration (works for both WiFi and Ethernet)
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                // Skip loopback and down interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    // Only return IPv4 addresses
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress
                        if (ip != null) {
                            Log.d(TAG, "Found local IP: $ip on ${networkInterface.displayName}")
                            return ip
                        }
                    }
                }
            }

            // Method 2: Fallback to WifiManager (WiFi only)
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
                Log.d(TAG, "Found WiFi IP: $ip")
                return ip
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP address: ${e.message}")
        }
        return "unknown"
    }

    /**
     * Detect if device is Amazon Fire TV (not Fire Tablet).
     * Fire TV models contain "AFT" (Amazon Fire TV) in the model name.
     * Fire Tablets contain "KF" (Kindle Fire) in the model name.
     */
    fun isFireTV(): Boolean {
        val model = Build.MODEL.lowercase()

        // AFT = Amazon Fire TV Stick/Cube (e.g., AFTMM, AFTN, AFTKA)
        // Fire TV string appears in some models
        // Exclude Fire Tablets which have "KF" prefix (Kindle Fire)
        return (model.contains("aft") || model.contains("fire tv")) &&
                !model.contains("kf")  // KF = Kindle Fire (tablets)
    }

    /**
     * Detect if device is Amazon Fire Tablet (not Fire TV).
     * Fire Tablets have "KF" (Kindle Fire) prefix in the model name.
     */
    fun isFireTablet(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()

        // KF = Kindle Fire (e.g., KFMAWI, KFONWI)
        return manufacturer.contains("amazon") && model.contains("kf")
    }

    /**
     * Detect if device is a TV (Android TV, Fire TV, etc.)
     */
    fun isTVDevice(context: Context): Boolean {
        return context.packageManager.hasSystemFeature("android.software.leanback") ||
                context.packageManager.hasSystemFeature("android.hardware.type.television")
    }

    /**
     * Detect if device is a tablet
     * Uses multiple detection methods:
     * 1. Known tablet model prefixes (Amazon Fire tablets, etc.)
     * 2. Screen smallest width >= 600dp
     * 3. Screen diagonal >= 7 inches
     */
    fun isTablet(context: Context): Boolean {
        // Method 1: Check for known tablet model prefixes
        val model = Build.MODEL.uppercase()
        val knownTabletPrefixes = listOf(
            "KF",        // Amazon Fire tablets (KFRAPWI, KFMAWI, etc.)
            "SM-T",      // Samsung Galaxy Tab
            "SM-X",      // Samsung Galaxy Tab S
            "MIOLCD",    // Mioio Android displays
            "PIXEL C",   // Google Pixel C
            "NEXUS 7",   // Google Nexus 7
            "NEXUS 9",   // Google Nexus 9
            "LENOVO TB", // Lenovo tablets
            "TAB",       // Generic tablet naming
            "ECHO SHOW"  // Amazon Echo Show smart displays (fixed-mount, treat as tablet for QR device-flow auth + full dashboard UI)
        )
        val isKnownTablet = knownTabletPrefixes.any { model.startsWith(it) }
        if (isKnownTablet) {
            Log.d(TAG, "📱 isTablet: model=$model matches known tablet prefix")
            return true
        }

        // Method 2: Check screen smallest width (works for most tablets)
        val displayMetrics = context.resources.displayMetrics
        val widthDp = displayMetrics.widthPixels / displayMetrics.density
        val heightDp = displayMetrics.heightPixels / displayMetrics.density
        val smallestWidth = minOf(widthDp, heightDp)
        if (smallestWidth >= 600) {
            Log.d(TAG, "📱 isTablet: smallestWidth=${smallestWidth}dp >= 600dp")
            return true
        }

        // Method 3: Check screen diagonal (7+ inches is typically a tablet)
        val widthInches = displayMetrics.widthPixels / displayMetrics.xdpi
        val heightInches = displayMetrics.heightPixels / displayMetrics.ydpi
        val diagonalInches = Math.sqrt((widthInches * widthInches + heightInches * heightInches).toDouble())
        if (diagonalInches >= 7.0) {
            Log.d(TAG, "📱 isTablet: diagonal=${String.format("%.1f", diagonalInches)}\" >= 7\"")
            return true
        }

        Log.d(TAG, "📱 isTablet: false (model=$model, smallestWidth=${smallestWidth}dp, diagonal=${String.format("%.1f", diagonalInches)}\")")
        return false
    }

    /**
     * Get a simple one-line device description
     */
    fun getDeviceDescription(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
    }

    /**
     * Check if device has specific hardware features
     */
    fun hasFeature(context: Context, feature: String): Boolean {
        return context.packageManager.hasSystemFeature(feature)
    }

    /**
     * Get all system features (useful for debugging)
     */
    fun getAllSystemFeatures(context: Context): List<String> {
        return context.packageManager.systemAvailableFeatures
            .mapNotNull { it.name }
            .sorted()
    }
}

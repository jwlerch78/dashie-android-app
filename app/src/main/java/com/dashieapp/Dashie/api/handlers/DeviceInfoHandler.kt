package com.dashieapp.Dashie.api.handlers

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.dashieapp.Dashie.api.DashieApiCallbacks
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface

/**
 * Handles device information API endpoints for the Dashie REST API.
 *
 * Endpoints:
 * - deviceInfo: Comprehensive device state (polled by HA every 30-60s)
 * - device.xml: UPnP device description for SSDP discovery
 * - listSettings: All configurable settings (required by HA integration setup)
 */
class DeviceInfoHandler(
    private val context: Context,
    private val callbacks: DashieApiCallbacks,
    private val port: Int
) {
    companion object {
        private const val TAG = "DeviceInfoHandler"
        // PREFS_NAME / KEY_STABLE_DEVICE_ID / WIDEVINE_UUID removed in Phase 2d-i.
        // They were this file's private copy of the device-identity constants — a second
        // definition of the same prefs file, key and DRM UUID. Both now live once, in
        // util/DeviceIdentity.kt. Re-declaring them here is what let the two derivations
        // drift apart in the first place.
    }

    // ============================================
    // API Endpoints
    // ============================================

    /**
     * GET /?cmd=deviceInfo&type=json
     *
     * Returns comprehensive device state information.
     * This is polled by Home Assistant every 30-60 seconds.
     */
    fun handleDeviceInfo(params: Map<String, String>): NanoHTTPD.Response {
        val type = params["type"] ?: "json"

        val battery = getBatteryInfo()
        val storage = getStorageInfo()
        val memory = getMemoryInfo()
        val network = getNetworkInfo()

        val deviceInfo = JSONObject().apply {
            // Device identification — both fields return the hardware-tied
            // stable ID. We keep the legacy `deviceID` field name for HA
            // integration backward compat and add `stableDeviceID` so the HA
            // integration's migration logic can detect when a device first
            // reports a hardware-backed ID and rewrite legacy ANDROID_ID
            // entity unique_ids to it.
            put("deviceID", getStableDeviceId())
            put("stableDeviceID", getStableDeviceId())
            put("deviceName", callbacks.getDeviceName())  // User-configured name from Supabase
            put("deviceModel", Build.MODEL)
            put("deviceManufacturer", Build.MANUFACTURER)
            put("androidVersion", Build.VERSION.RELEASE)
            put("androidSdk", Build.VERSION.SDK_INT)

            // Battery
            put("batteryLevel", battery.level)
            put("plugged", battery.isPlugged)  // HA binary_sensor expects "plugged" not "isPlugged"
            put("plugSource", battery.plugSource)

            // Screen
            put("isScreenOn", callbacks.isScreenOn())  // Legacy field name
            put("screenOn", callbacks.isScreenOn())    // HA switch uses this newer field name
            put("screenBrightness", callbacks.getBrightness())
            put("screenOrientation", 0)  // 0=portrait, 90=landscape - HA sensor expects this
            put("ambientLight", callbacks.getAmbientLight())  // Light sensor lux reading
            put("isInScreensaver", callbacks.isInScreensaver())

            // Kiosk mode
            put("kioskMode", true)
            put("kioskLocked", callbacks.isKioskLocked())  // Legacy: true if any lock active
            put("maintenanceMode", false)
            put("isDeviceAdmin", false)  // HA binary_sensor expects this
            put("isDeviceOwner", false)  // Additional field HA may check

            // New lock states (more granular)
            put("lockAppExit", callbacks.isLockAppExit())
            put("lockSettings", callbacks.isLockSettings())
            put("hasPinSet", callbacks.hasPinSet())

            // Dark mode
            put("isDarkMode", callbacks.isDarkMode())

            // Device type detection for capability flags
            val isFireTablet = Build.MANUFACTURER.lowercase().contains("amazon") &&
                               Build.MODEL.lowercase().contains("kf")
            put("isFireTablet", isFireTablet)
            // Dark mode requires WRITE_SECURE_SETTINGS permission (check actual capability)
            put("supportsDarkMode", callbacks.canControlDarkMode())

            // Screen off method (for HA select entity)
            put("screenOffMethod", callbacks.getScreenOffMethod())

            // Screensaver settings (for HA select entities)
            put("screensaverMode", callbacks.getScreensaverMode())
            put("photoSource", callbacks.getPhotoSource())
            put("haMediaFolder", callbacks.getHaMediaFolder())
            put("motionWakeMode", callbacks.getMotionWakeMode())

            // Display/UI settings (for HA switch entities)
            put("hideSidebar", callbacks.isHideSidebar())
            put("hideHeader", callbacks.isHideHeader())
            put("keepScreenOn", callbacks.isKeepScreenOn())
            put("startOnBoot", callbacks.isStartOnBoot())
            put("autoBrightness", callbacks.isAutoBrightness())
            put("rtspEnabled", callbacks.isRtspEnabled())
            put("rtspSoftwareEncoding", callbacks.isRtspSoftwareEncoding())
            put("textScaling", callbacks.getTextScaling())

            // App info for HA sensors
            put("foregroundApp", context.packageName)  // HA sensor expects this

            // Motion detection status (0=off, 1=waiting, 2=active)
            put("motionDetectorStatus", 0) // TODO: Get from MotionWakeManager

            // HA sensor detection state (binary_sensor entities). The *Enabled
            // flags reflect the user's toggles in HalitePreferences; HA's
            // integration uses them to render the binary_sensor as unavailable
            // when off (vs collapsing to "active+clear" the way always-false
            // detection state would).
            put("motionDetected", callbacks.isMotionDetected())
            put("faceDetected", callbacks.isFaceDetected())
            put("motionDetectionEnabled", callbacks.isMotionDetectionEnabled())
            put("faceDetectionEnabled", callbacks.isFaceDetectionEnabled())

            // URLs
            put("currentPage", callbacks.getCurrentUrl())
            put("startUrl", callbacks.getStartUrl())

            // Network
            put("ip4", network.ip4)
            put("Mac", network.mac)  // Capital M - Home Assistant expects this exact casing
            put("wifiSignalLevel", network.wifiSignalLevel)
            // Raw RSSI in dBm for HA's signal_strength sensor. wifiSignalLevel (0-100,
            // Fully Kiosk API shape) stays for compat; omitted when not on WiFi.
            network.wifiRssi?.let { put("wifiRssi", it) }
            put("ssid", network.ssid)

            // Storage
            put("internalStorageFreeSpace", storage.freeSpace)
            put("internalStorageTotalSpace", storage.totalSpace)

            // Memory (JVM heap)
            put("ramFreeMemory", memory.freeMemory)
            put("ramTotalMemory", memory.totalMemory)

            // System RAM usage (matches performance overlay)
            val systemRam = getSystemRamInfo()
            put("ramUsedPercent", systemRam.usedPercent)
            put("ramTotalMb", systemRam.totalMb)
            put("ramAvailableMb", systemRam.availableMb)

            // App Memory (PSS - actual physical memory used by app)
            put("appMemoryMb", callbacks.getAppMemoryMb())

            // App info
            put("appVersionName", getAppVersionName())
            put("appVersionCode", getAppVersionCode())
            put("isLicensed", true) // Always licensed (free/open source)

            // Audio
            put("currentVolume", callbacks.getVolume(3)) // STREAM_MUSIC
            put("audioVolume", callbacks.getVolume(3))  // Alias for HA integration
            put("audioMuted", callbacks.isMuted())
            put("audioPosition", callbacks.getAudioPosition())  // Current position in ms (cumulative in flow mode)
            put("trackPosition", callbacks.getTrackPosition())  // Track-relative position in ms
            put("audioDuration", callbacks.getAudioDuration())  // Total duration in ms
            // soundUrlPlaying - the URL currently being played (empty if not playing)
            // This is what Home Assistant uses to detect playback state
            val soundUrl = callbacks.getSoundUrlPlaying()
            if (soundUrl.isNotEmpty()) {
                put("soundUrlPlaying", soundUrl)
            }
            if (callbacks.isSoundPaused()) {
                put("soundPaused", true)
            }

            // Media metadata (pushed by Music Assistant via setMediaInfo)
            val mediaTitle = callbacks.getMediaTitle()
            if (mediaTitle.isNotEmpty()) put("mediaTitle", mediaTitle)
            val mediaArtist = callbacks.getMediaArtist()
            if (mediaArtist.isNotEmpty()) put("mediaArtist", mediaArtist)
            val mediaAlbum = callbacks.getMediaAlbum()
            if (mediaAlbum.isNotEmpty()) put("mediaAlbum", mediaAlbum)
            val mediaImageUrl = callbacks.getMediaImageUrl()
            if (mediaImageUrl.isNotEmpty()) put("mediaImageUrl", mediaImageUrl)

            // RTSP Camera config (for HA camera sensors)
            val rtspConfig = callbacks.getRtspConfig()
            put("rtspConfig", JSONObject().apply {
                put("width", rtspConfig.width)
                put("height", rtspConfig.height)
                put("fps", rtspConfig.fps)
                put("bitrate", rtspConfig.bitrate)
                put("port", rtspConfig.port)
                put("softwareEncoding", rtspConfig.softwareEncoding)
            })

            // Video feed trigger entities (HA integration monitors these and pushes state changes)
            val triggerEntities = callbacks.getVideoFeedTriggerEntities()
            if (triggerEntities.isNotEmpty()) {
                put("videoFeedTriggerEntities", JSONArray(triggerEntities))
            }

            // Settings object - required by HA Number and Switch entities
            // HA reads settings like: coordinator.data["settings"].get("screenBrightness")
            put("settings", JSONObject().apply {
                // motionDetection here mirrors the master toggle in the same
                // form HA's existing switch entity expects. The new
                // motionDetectionEnabled / faceDetectionEnabled fields above
                // are the per-detector signals the binary_sensor logic uses.
                put("motionDetection", callbacks.isMotionDetectionEnabled())
                put("screenBrightness", callbacks.getBrightness())  // HA number entity
                put("screensaverBrightness", 10)  // HA number entity (0-255)
                put("timeToScreensaverV2", 300)  // HA number entity (seconds)
                put("timeToScreenOffV2", 600)  // HA number entity (seconds)
                put("mqttEnabled", callbacks.isMqttEnabled())
                put("mqttEventTopic", callbacks.getMqttBaseTopic())
            })
        }

        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            deviceInfo.toString(2)
        )
    }

    /**
     * GET /device.xml
     *
     * Returns UPnP device description XML for SSDP discovery.
     * This is the standard UPnP endpoint that SSDP LOCATION headers should point to.
     * Home Assistant's SSDP integration fetches this to validate discovered devices.
     */
    fun handleDeviceXml(): NanoHTTPD.Response {
        val deviceUuid = callbacks.getDeviceUuid()
        val deviceName = callbacks.getDeviceName()
        val deviceModel = Build.MODEL
        val versionName = getAppVersionName()

        // Build UPnP device description XML per UPnP spec
        val xml = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion>
    <major>1</major>
    <minor>1</minor>
  </specVersion>
  <device>
    <deviceType>urn:dashie:device:DashieLite:1</deviceType>
    <friendlyName>$deviceName</friendlyName>
    <manufacturer>Dashie</manufacturer>
    <manufacturerURL>https://github.com/jwlerch78/dashie-ha-integration</manufacturerURL>
    <modelName>DashieLite</modelName>
    <modelNumber>$versionName</modelNumber>
    <modelDescription>Dashie Kiosk for Home Assistant</modelDescription>
    <modelURL>https://github.com/jwlerch78/dashie-ha-integration</modelURL>
    <serialNumber>$deviceModel</serialNumber>
    <UDN>uuid:$deviceUuid</UDN>
  </device>
</root>"""

        Log.d(TAG, "Serving device.xml for SSDP discovery")
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "text/xml",
            xml
        )
    }

    /**
     * GET /?cmd=listSettings&type=json
     *
     * Returns all configurable settings as a JSON object.
     * This is required by Home Assistant's Fully Kiosk integration during setup.
     * The HA integration calls getSettings() which maps to this endpoint.
     */
    fun handleListSettings(): NanoHTTPD.Response {
        Log.d(TAG, "API: listSettings requested")

        val settings = JSONObject().apply {
            // Screen settings
            put("screenBrightness", callbacks.getBrightness())
            put("screensaverBrightness", 10)
            put("timeToScreensaverV2", 300)  // seconds
            put("timeToScreenOffV2", 600)    // seconds
            put("screenOrientation", 0)      // 0=portrait, 90=landscape

            // Motion detection (master toggle — HA Fully Kiosk-style switch)
            put("motionDetection", callbacks.isMotionDetectionEnabled())
            put("motionSensitivity", 50)

            // Audio
            put("audioVolume", callbacks.getVolume(3))  // STREAM_MUSIC

            // URLs
            put("startUrl", callbacks.getStartUrl())

            // Kiosk settings
            put("kioskMode", true)
            put("kioskLocked", callbacks.isKioskLocked())
            put("lockAppExit", callbacks.isLockAppExit())
            put("lockSettings", callbacks.isLockSettings())

            // Dark mode
            put("darkMode", callbacks.isDarkMode())

            // Screen off method
            put("screenOffMethod", callbacks.getScreenOffMethod())

            // Screensaver
            put("screensaverMode", callbacks.getScreensaverMode())
            put("photoSource", callbacks.getPhotoSource())
            put("haMediaFolder", callbacks.getHaMediaFolder())

            // MQTT
            put("mqttEnabled", callbacks.isMqttEnabled())
            put("mqttEventTopic", callbacks.getMqttBaseTopic())

            // Additional settings HA may look for
            put("remoteAdminEnabled", true)
            put("remoteAdminPort", port)
            put("maintenanceMode", false)
        }

        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            settings.toString(2)
        )
    }

    // ============================================
    // Helper methods for gathering device info
    // ============================================

    private data class BatteryInfo(val level: Int, val isPlugged: Boolean, val plugSource: String)

    private fun getBatteryInfo(): BatteryInfo {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (scale > 0) (level * 100 / scale) else 0

        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val isPlugged = plugged != 0
        val plugSource = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "None"
        }

        return BatteryInfo(batteryPct, isPlugged, plugSource)
    }

    private data class StorageInfo(val freeSpace: Long, val totalSpace: Long)

    private fun getStorageInfo(): StorageInfo {
        return try {
            val path = File(context.filesDir.absolutePath)
            val stat = StatFs(path.absolutePath)
            val freeSpace = stat.availableBytes
            val totalSpace = stat.totalBytes
            StorageInfo(freeSpace, totalSpace)
        } catch (e: Exception) {
            StorageInfo(0, 0)
        }
    }

    private data class MemoryInfo(val freeMemory: Long, val totalMemory: Long)

    private fun getMemoryInfo(): MemoryInfo {
        val runtime = Runtime.getRuntime()
        val freeMemory = runtime.freeMemory()
        val totalMemory = runtime.totalMemory()
        return MemoryInfo(freeMemory, totalMemory)
    }

    private data class SystemRamInfo(val usedPercent: Int, val totalMb: Int, val availableMb: Int)

    private fun getSystemRamInfo(): SystemRamInfo {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)

            val totalMb = (memInfo.totalMem / 1024 / 1024).toInt()
            val availableMb = (memInfo.availMem / 1024 / 1024).toInt()
            val usedMem = memInfo.totalMem - memInfo.availMem
            val usedPercent = if (memInfo.totalMem > 0) {
                ((usedMem * 100) / memInfo.totalMem).toInt()
            } else 0

            SystemRamInfo(usedPercent, totalMb, availableMb)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get system RAM info: ${e.message}")
            SystemRamInfo(0, 0, 0)
        }
    }

    private data class NetworkInfo(
        val ip4: String,
        val mac: String,
        val wifiSignalLevel: Int,
        val ssid: String,
        val wifiRssi: Int?, // raw dBm; null when no WiFi connection (RSSI sentinel -127)
    )

    private fun getNetworkInfo(): NetworkInfo {
        var ip4 = "0.0.0.0"
        var mac = "00:00:00:00:00:00"
        var wifiSignalLevel = 0
        var ssid = ""
        var wifiRssi: Int? = null

        try {
            // Get IP address and MAC from active network interface
            // This works for both WiFi and Ethernet connections
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr.hostAddress?.contains(".") == true && !addr.isLoopbackAddress) {
                        ip4 = addr.hostAddress ?: "0.0.0.0"

                        // Try to get MAC address from this interface (works for Ethernet too)
                        try {
                            val hwAddr = iface.hardwareAddress
                            if (hwAddr != null && hwAddr.isNotEmpty()) {
                                val macFromInterface = hwAddr.joinToString(":") { "%02x".format(it) }
                                // Only use if it's not the default/invalid MAC
                                if (macFromInterface != "00:00:00:00:00:00" &&
                                    macFromInterface != "02:00:00:00:00:00") {
                                    mac = macFromInterface
                                    Log.d(TAG, "Got MAC from interface ${iface.name}: $mac")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not get MAC from interface ${iface.name}: ${e.message}")
                        }
                        break
                    }
                }
            }

            // Get WiFi info (signal strength, SSID)
            // Also try WiFi MAC if we didn't get one from the interface
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.connectionInfo?.let { info ->
                // Only use WiFi MAC if we don't have a valid one yet
                if (mac == "00:00:00:00:00:00") {
                    val wifiMac = info.macAddress
                    if (wifiMac != null && wifiMac != "02:00:00:00:00:00") {
                        mac = wifiMac
                    }
                }
                wifiSignalLevel = WifiManager.calculateSignalLevel(info.rssi, 100)
                if (info.rssi != -127) wifiRssi = info.rssi
                ssid = info.ssid?.removeSurrounding("\"") ?: ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting network info: ${e.message}")
        }

        return NetworkInfo(ip4, mac, wifiSignalLevel, ssid, wifiRssi)
    }

    fun getDeviceId(): String {
        // Use ANDROID_ID for a truly unique device identifier
        // Build.SERIAL is deprecated and returns "unknown" on API 26+, causing
        // duplicate deviceIDs when multiple devices have the same manufacturer/model
        return try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )?.takeIf { it.isNotEmpty() }
                ?: "${Build.MANUFACTURER}_${Build.MODEL}".replace(" ", "_")
        } catch (e: Exception) {
            "${Build.MANUFACTURER}_${Build.MODEL}".replace(" ", "_")
        }
    }

    /**
     * Hardware-tied device ID that survives app reinstall and package changes.
     *
     * Strategy:
     *   1. Return cached value from SharedPreferences if present (immutable post-first-run).
     *   2. Else, derive from Widevine MediaDrm device unique ID (hardware-backed).
     *   3. Else, fall back to ANDROID_ID for the rare device without Widevine.
     *
     * The cached value is never rewritten — even if Widevine becomes available
     * later — to keep the ID stable for HA integrations that key on first-seen value.
     */
    /**
     * Hardware-tied device ID that survives app reinstall and package changes.
     *
     * Delegates to [com.dashieapp.Dashie.util.DeviceIdentity] (Phase 2d-i). This used to be a
     * SECOND independent Widevine derivation writing the same prefs file/key as
     * VoiceLicenseManager — byte-identical on a Widevine device, but with a different terminal
     * fallback, so on a device without Widevine whichever subsystem ran first won the cache.
     */
    fun getStableDeviceId(): String =
        com.dashieapp.Dashie.util.DeviceIdentity.stableId(context)

    fun getAppVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    fun getAppVersionCode(): Long {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            0L
        }
    }
}

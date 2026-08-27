package com.dashieapp.Dashie.halite.mqtt

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.halite.preferences.MqttPreferences
import org.json.JSONObject

/**
 * Publishes device sensor data to MQTT topics.
 *
 * Reads sensor values from system APIs and existing Dashie components,
 * then publishes to per-sensor topics and a combined state topic.
 *
 * Two publishing modes:
 * 1. **Interval** — Timer-based, publishes all enabled sensors on a schedule
 * 2. **On change** — Instant publish when motion/face/screen state changes
 *
 * This class does NOT collect motion/face data itself — those are pushed
 * via [onMotionChanged] and [onFaceChanged] by HaliteComponentWiring.
 */
class MqttSensorPublisher(
    private val context: Context,
    private val mqttService: DashieMqttService,
    private val prefs: MqttPreferences
) {
    companion object {
        private const val TAG = "MqttSensorPublisher"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var publishRunnable: Runnable? = null
    private var started = false

    // Providers to read debounced state from HaSensorPublisher (authoritative source)
    var motionDetectedProvider: (() -> Boolean)? = null
    var faceDetectedProvider: (() -> Boolean)? = null
    // Providers for the user-controlled detection toggles. When these return
    // false, MQTT consumers (the HA integration) treat the binary_sensor as
    // unavailable rather than off.
    var motionDetectionEnabledProvider: (() -> Boolean)? = null
    var faceDetectionEnabledProvider: (() -> Boolean)? = null

    // Last published state for change detection
    @Volatile private var lastMotionPublished: Boolean = false
    @Volatile private var lastFacePublished: Boolean = false

    // Callbacks to read current state from other components
    var screenOnProvider: (() -> Boolean)? = null
    var brightnessProvider: (() -> Int)? = null
    var ambientLightProvider: (() -> Int)? = null
    var volumeProvider: (() -> Int)? = null
    var isMutedProvider: (() -> Boolean)? = null
    var currentUrlProvider: (() -> String)? = null
    var isInScreensaverProvider: (() -> Boolean)? = null
    var appMemoryMbProvider: (() -> Int)? = null

    // ── Public API ──────────────────────────────────────────────────

    fun start() {
        if (started) return
        started = true
        scheduleNextPublish()
        startChangeDetection()
        Log.i(TAG, "Started (interval=${prefs.publishIntervalSec}s)")
    }

    fun stop() {
        started = false
        publishRunnable?.let { handler.removeCallbacks(it) }
        publishRunnable = null
        stopChangeDetection()
        Log.i(TAG, "Stopped")
    }

    fun destroy() {
        stop()
        motionDetectedProvider = null
        faceDetectedProvider = null
        motionDetectionEnabledProvider = null
        faceDetectionEnabledProvider = null
        screenOnProvider = null
        brightnessProvider = null
        ambientLightProvider = null
        volumeProvider = null
        isMutedProvider = null
        currentUrlProvider = null
        isInScreensaverProvider = null
        appMemoryMbProvider = null
    }

    // ── Publishing ──────────────────────────────────────────────────

    /** Publish all enabled sensors (called on interval) */
    fun publishAll() {
        if (!mqttService.isConnected) return
        val base = mqttService.baseTopic

        if (prefs.sensorBattery) publishBattery()
        if (prefs.sensorScreen) publishScreen()
        if (prefs.sensorMotion) publishMotion()
        if (prefs.sensorFace) publishFace()
        if (prefs.sensorLight) publishLight()
        if (prefs.sensorAudio) publishAudio()
        if (prefs.sensorDeviceInfo) publishDeviceInfo()

        // Combined state topic
        publishState()
    }

    private fun publishBattery() {
        val battery = getBatteryInfo()
        val json = JSONObject().apply {
            put("value", battery.level)
            put("unit", "%")
            put("charging", battery.isCharging)
            put("plugSource", battery.plugSource)
        }
        mqttService.publish("${mqttService.baseTopic}/sensor/battery", json.toString())
    }

    private fun publishScreen() {
        val json = JSONObject().apply {
            put("screenOn", screenOnProvider?.invoke() ?: true)
            put("brightness", brightnessProvider?.invoke() ?: 128)
            put("isInScreensaver", isInScreensaverProvider?.invoke() ?: false)
        }
        mqttService.publish("${mqttService.baseTopic}/sensor/screen", json.toString())
    }

    private fun publishMotion() {
        val current = motionDetectedProvider?.invoke() ?: false
        lastMotionPublished = current
        val enabled = motionDetectionEnabledProvider?.invoke() ?: true
        val json = JSONObject().apply {
            put("value", current)
            put("enabled", enabled)
        }
        mqttService.publish("${mqttService.baseTopic}/sensor/motion", json.toString())
    }

    private fun publishFace() {
        val current = faceDetectedProvider?.invoke() ?: false
        lastFacePublished = current
        val enabled = faceDetectionEnabledProvider?.invoke() ?: true
        val json = JSONObject().apply {
            put("value", current)
            put("enabled", enabled)
        }
        mqttService.publish("${mqttService.baseTopic}/sensor/face", json.toString())
    }

    private fun publishLight() {
        val json = JSONObject().apply {
            put("value", ambientLightProvider?.invoke() ?: 0)
            put("unit", "lx")
        }
        mqttService.publish("${mqttService.baseTopic}/sensor/light", json.toString())
    }

    private fun publishAudio() {
        val json = JSONObject().apply {
            put("volume", volumeProvider?.invoke() ?: 0)
            put("muted", isMutedProvider?.invoke() ?: false)
        }
        mqttService.publish("${mqttService.baseTopic}/sensor/audio", json.toString())
    }

    private fun publishDeviceInfo() {
        val memory = getSystemRamInfo()
        val json = JSONObject().apply {
            put("ramUsedPercent", memory.usedPercent)
            put("ramAvailableMb", memory.availableMb)
            put("appMemoryMb", appMemoryMbProvider?.invoke() ?: 0)
        }
        mqttService.publish("${mqttService.baseTopic}/sensor/memory", json.toString())
    }

    /** Combined state topic — mirrors what DeviceInfoHandler returns */
    private fun publishState() {
        val battery = getBatteryInfo()
        val json = JSONObject().apply {
            put("batteryLevel", battery.level)
            put("charging", battery.isCharging)
            put("plugSource", battery.plugSource)
            put("screenOn", screenOnProvider?.invoke() ?: true)
            put("brightness", brightnessProvider?.invoke() ?: 128)
            put("isInScreensaver", isInScreensaverProvider?.invoke() ?: false)
            put("motionDetected", motionDetectedProvider?.invoke() ?: false)
            put("faceDetected", faceDetectedProvider?.invoke() ?: false)
            put("motionDetectionEnabled", motionDetectionEnabledProvider?.invoke() ?: false)
            put("faceDetectionEnabled", faceDetectionEnabledProvider?.invoke() ?: false)
            put("ambientLight", ambientLightProvider?.invoke() ?: 0)
            put("volume", volumeProvider?.invoke() ?: 0)
            put("muted", isMutedProvider?.invoke() ?: false)
            put("currentUrl", currentUrlProvider?.invoke() ?: "")
            put("deviceModel", Build.MODEL)
            put("deviceManufacturer", Build.MANUFACTURER)
        }
        mqttService.publish("${mqttService.baseTopic}/state", json.toString())
    }

    // ── Timer ────────────────────────────────────────────────────────

    private var changeCheckRunnable: Runnable? = null

    private fun scheduleNextPublish() {
        if (!started) return
        val runnable = Runnable {
            if (started && mqttService.isConnected) {
                try {
                    publishAll()
                } catch (e: Exception) {
                    Log.w(TAG, "Publish error: ${e.message}")
                }
            }
            scheduleNextPublish()
        }
        publishRunnable = runnable
        handler.postDelayed(runnable, prefs.publishIntervalSec * 1000L)
    }

    /**
     * Fast poll (every 1s) to detect motion/face state changes from HaSensorPublisher.
     * Reads the debounced state and publishes only when it transitions.
     */
    private fun startChangeDetection() {
        stopChangeDetection()
        val runnable = Runnable { checkAndPublishChanges() }
        changeCheckRunnable = runnable
        handler.postDelayed(runnable, 1000L)
    }

    private fun stopChangeDetection() {
        changeCheckRunnable?.let { handler.removeCallbacks(it) }
        changeCheckRunnable = null
    }

    private fun checkAndPublishChanges() {
        if (!started || !mqttService.isConnected) {
            startChangeDetection() // Re-schedule even if not connected
            return
        }

        if (prefs.publishOnChange) {
            val currentMotion = motionDetectedProvider?.invoke() ?: false
            if (currentMotion != lastMotionPublished && prefs.sensorMotion) {
                publishMotion()
            }

            val currentFace = faceDetectedProvider?.invoke() ?: false
            if (currentFace != lastFacePublished && prefs.sensorFace) {
                publishFace()
            }
        }

        // Re-schedule
        val runnable = Runnable { checkAndPublishChanges() }
        changeCheckRunnable = runnable
        handler.postDelayed(runnable, 1000L)
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private data class BatteryInfo(val level: Int, val isCharging: Boolean, val plugSource: String)

    private fun getBatteryInfo(): BatteryInfo {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (scale > 0) (level * 100 / scale) else 0

        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val plugSource = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "None"
        }

        return BatteryInfo(batteryPct, plugged != 0, plugSource)
    }

    private data class RamInfo(val usedPercent: Int, val availableMb: Int)

    private fun getSystemRamInfo(): RamInfo {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val usedMem = memInfo.totalMem - memInfo.availMem
            val usedPercent = if (memInfo.totalMem > 0) ((usedMem * 100) / memInfo.totalMem).toInt() else 0
            val availableMb = (memInfo.availMem / 1024 / 1024).toInt()
            RamInfo(usedPercent, availableMb)
        } catch (e: Exception) {
            RamInfo(0, 0)
        }
    }
}

package com.dashieapp.Dashie.halite

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.PowerPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Native-side power management engine that runs battery hysteresis logic
 * entirely in Kotlin, using a simple Handler loop (60s interval).
 *
 * The foreground service keeps the process alive during screen-off/Doze,
 * so Handler callbacks fire reliably. No AlarmManager needed.
 *
 * Battery reading, threshold logic, and HA switch toggle are fully native.
 * Switch control uses the HA REST API directly (no WebView dependency),
 * so it works reliably even when the screen is off.
 *
 * Config is synced from JS via window.dashieDevice.syncPowerConfig().
 */
class PowerManagementWatchdog(
    private val activity: Context,
    private val halitePrefs: HalitePreferences
) {
    companion object {
        private const val TAG = "PowerWatchdog"
        private const val CHECK_INTERVAL_MS = 60_000L // 60 seconds
        private const val MIN_TOGGLE_GAP_MS = 5 * 60 * 1000L // 5 min between toggles

        private const val STATE_PREFS = "dashie_power_watchdog_state"
        private const val LOG_PREFS = "dashie_power_watchdog_log"
        private const val HISTORY_PREFS = "dashie_power_watchdog_history"
        private const val LOG_MAX_ENTRIES = 500
        private const val HISTORY_MAX_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val HISTORY_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
    }

    private val powerPrefs = PowerPreferences(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var state = "IDLE"
    private var lastToggleTime = 0L
    private var lastBatteryLevel: Int? = null
    private var manualOverrideUntil = 0L  // timestamp until which watchdog skips hysteresis
    private var overrideTarget: Int? = null // null = normal, 100 = charge-to-full, 5 = discharge-to-5%
    private var lastHistorySlot = 0L
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Accepts self-signed certs for LAN HA URLs.
    private val httpClient = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val checkRunnable = object : Runnable {
        override fun run() {
            evaluate()
            if (isRunning) handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    init {
        restoreState()
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
        Log.i(TAG, "⚡ Watchdog started (${CHECK_INTERVAL_MS / 1000}s interval, state=$state)")
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false

        handler.removeCallbacks(checkRunnable)
        Log.i(TAG, "⚡ Watchdog stopped")
    }

    /** Run an immediate evaluation (e.g. after settings change). */
    fun evaluateNow() {
        Log.i(TAG, "⚡ Immediate evaluation requested")
        handler.post { evaluate() }
    }

    /**
     * Notify the watchdog that the user manually toggled the switch.
     * @param target null = normal toggle (10-min time-based override),
     *   100 = charge to full and stay, 5 = discharge to 5% then turn back on.
     */
    fun onManualToggle(turnedOn: Boolean, target: Int? = null) {
        val newState = if (turnedOn) "CHARGING" else "IDLE"
        overrideTarget = target
        if (target != null) {
            // Target-based override: hold until battery reaches target
            manualOverrideUntil = Long.MAX_VALUE
            Log.i(TAG, "⚡ Manual toggle: switch ${if (turnedOn) "ON" else "OFF"} → state=$newState (override until ${target}%)")
            persistLog("manual", "User toggled switch ${if (turnedOn) "ON" else "OFF"} → $newState (target: ${target}%)")
        } else {
            // Normal toggle: 10-minute time-based override
            manualOverrideUntil = System.currentTimeMillis() + 10 * 60 * 1000L
            Log.i(TAG, "⚡ Manual toggle: switch ${if (turnedOn) "ON" else "OFF"} → state=$newState (override 10min)")
            persistLog("manual", "User toggled switch ${if (turnedOn) "ON" else "OFF"} → $newState")
        }
        setState(newState)
    }

    fun destroy() {
        stop()
    }

    // ==================== CORE LOGIC ====================

    private fun evaluate() {
        val batteryLevel = getBatteryLevel()
        val isCharging = isCharging()

        if (batteryLevel < 0) return

        val config = loadConfig()
        if (!config.enabled || config.entityId.isEmpty()) return

        // Determine active thresholds
        val isNight = isNightWindow(config.nightStart, config.nightEnd)
        val thresholdLow = if (config.preferNight && !isNight) config.emergencyThreshold else config.minThreshold
        val thresholdHigh = config.maxThreshold

        val prevLevel = lastBatteryLevel
        val delta = if (prevLevel != null) batteryLevel - prevLevel else null
        val deltaStr = if (delta != null) " (${if (delta >= 0) "+" else ""}${delta}%)" else ""
        val pollMsg = "bat:${batteryLevel}%$deltaStr chg:$isCharging st:$state range:${thresholdLow}-${thresholdHigh}%"
        Log.d(TAG, "⚡ $pollMsg")
        lastBatteryLevel = batteryLevel

        // Record battery history every 15 minutes
        recordHistory(batteryLevel, isCharging)
        // Log every poll to persistent log
        persistLog("poll", pollMsg)

        // Manual override: user toggled the switch, skip hysteresis until override expires
        val inManualOverride = System.currentTimeMillis() < manualOverrideUntil
        if (inManualOverride) {
            val target = overrideTarget
            if (target != null) {
                // Target-based override: check if battery reached the target
                if (target >= 100 && batteryLevel >= 100) {
                    // Charged to full — clear override, stay at 100% (CHARGING state persists)
                    Log.i(TAG, "⚡ Override target reached: bat ${batteryLevel}% >= ${target}% — clearing override")
                    persistLog("action", "Override target reached: ${target}% — staying at full")
                    manualOverrideUntil = 0L
                    overrideTarget = null
                } else if (target <= 5 && batteryLevel <= target) {
                    // Discharged to 5% — turn switch back ON and clear override
                    Log.i(TAG, "⚡ Override target reached: bat ${batteryLevel}% <= ${target}% — turning switch ON")
                    persistLog("action", "Override target reached: ${target}% — turning switch back ON")
                    if (toggleSwitch(config.entityId, true, "override-target-reached")) {
                        setState("CHARGING")
                    }
                    manualOverrideUntil = 0L
                    overrideTarget = null
                } else {
                    // Target not yet reached — keep overriding, but handle disconnect detection
                    Log.d(TAG, "⚡ Override active (target: ${target}%) — skipping hysteresis")
                    if (state == "CHARGING" && !isCharging) {
                        Log.w(TAG, "⚡ DISCONNECT during override: re-sending turn_on")
                        toggleSwitch(config.entityId, true, "override-disconnect-detect")
                    }
                }
            } else {
                Log.d(TAG, "⚡ Manual override active — skipping hysteresis")
            }
            return
        }

        // Charger disconnect detection
        if (state == "CHARGING" && !isCharging) {
            val msg = "DISCONNECT: st=CHARGING but not charging → turn_on"
            Log.w(TAG, "⚡ $msg")
            persistLog("warn", msg)
            toggleSwitch(config.entityId, true, "disconnect-detect")
        }

        // Hysteresis state machine
        if (state == "IDLE" && batteryLevel <= thresholdLow) {
            val msg = "ON: bat ${batteryLevel}% <= ${thresholdLow}% → switch ON"
            Log.i(TAG, "⚡ $msg")
            persistLog("action", msg)
            if (toggleSwitch(config.entityId, true, "hysteresis-on")) {
                setState("CHARGING")
            }
        } else if (state == "IDLE" && batteryLevel >= thresholdHigh && isCharging) {
            val msg = "GUARD OFF: bat ${batteryLevel}% >= ${thresholdHigh}% while IDLE+charging → switch OFF"
            Log.i(TAG, "⚡ $msg")
            persistLog("action", msg)
            toggleSwitch(config.entityId, false, "idle-above-max")
        } else if (state == "CHARGING" && batteryLevel >= thresholdHigh) {
            val msg = "OFF: bat ${batteryLevel}% >= ${thresholdHigh}% → switch OFF"
            Log.i(TAG, "⚡ $msg")
            persistLog("action", msg)
            if (toggleSwitch(config.entityId, false, "hysteresis-off")) {
                setState("IDLE")
            }
        }
    }

    // ==================== BATTERY READING (NATIVE) ====================

    private fun getBatteryLevel(): Int {
        return try {
            val intent = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) (level * 100) / scale else -1
        } catch (e: Exception) { -1 }
    }

    private fun isCharging(): Boolean {
        return try {
            val intent = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) { false }
    }

    // ==================== HA SWITCH TOGGLE (DIRECT REST API) ====================

    private fun toggleSwitch(entityId: String, turnOn: Boolean, reason: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastToggleTime < MIN_TOGGLE_GAP_MS) {
            val waitSec = (MIN_TOGGLE_GAP_MS - (now - lastToggleTime)) / 1000
            Log.d(TAG, "⚡ BLOCKED ($reason): rapid-cycle, retry in ${waitSec}s")
            persistLog("block", "BLOCKED ($reason): rapid-cycle, retry in ${waitSec}s")
            return false
        }

        val action = if (turnOn) "turn_on" else "turn_off"

        // Get HA base URL and token
        val baseUrl = halitePrefs.connection.haBaseUrl.ifEmpty {
            halitePrefs.connection.haUrl.substringBefore("?").trimEnd('/')
        }
        if (baseUrl.isEmpty()) {
            Log.w(TAG, "⚡ FAILED ($reason): no HA base URL")
            return false
        }

        Log.i(TAG, "⚡ SEND: switch.$action → $entityId ($reason) via REST API")
        persistLog("send", "switch.$action → $entityId ($reason)")

        // All network calls (token refresh + HTTP toggle) run on background thread
        val url = "$baseUrl/api/services/switch/$action"
        val body = JSONObject().put("entity_id", entityId).toString()

        Thread {
            try {
                // Get valid token, refreshing if needed (must be off main thread)
                val credentials = HaTokenExtractor.getValidCredentialsSync(halitePrefs)
                if (credentials == null) {
                    Log.w(TAG, "⚡ FAILED ($reason): token expired and refresh failed")
                    return@Thread
                }
                val (_, token) = credentials

                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "⚡ SUCCESS: switch.$action → $entityId (HTTP ${response.code})")
                        persistLog("info", "SUCCESS: switch.$action → $entityId (HTTP ${response.code})")
                    } else {
                        val errBody = response.body?.string()?.take(200) ?: ""
                        Log.e(TAG, "⚡ HTTP ERROR: switch.$action → $entityId (HTTP ${response.code}: $errBody)")
                        persistLog("error", "HTTP ERROR: switch.$action (HTTP ${response.code})")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "⚡ NETWORK ERROR: switch.$action → $entityId", e)
                persistLog("error", "NETWORK ERROR: switch.$action (${e.message})")
            }
        }.start()

        lastToggleTime = now
        return true
    }

    // ==================== NIGHT WINDOW ====================

    private fun isNightWindow(startStr: String, endStr: String): Boolean {
        val now = Calendar.getInstance()
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val startParts = startStr.split(":")
        val endParts = endStr.split(":")
        val start = startParts[0].toIntOrNull()?.let { it * 60 + (startParts.getOrNull(1)?.toIntOrNull() ?: 0) } ?: return false
        val end = endParts[0].toIntOrNull()?.let { it * 60 + (endParts.getOrNull(1)?.toIntOrNull() ?: 0) } ?: return false

        return if (start <= end) minutes >= start && minutes < end
        else minutes >= start || minutes < end
    }

    // ==================== STATE PERSISTENCE ====================

    private fun setState(newState: String) {
        val old = state
        state = newState
        Log.i(TAG, "⚡ State: $old → $newState")
        persistLog("state", "$old → $newState")
        try {
            statePrefs().edit()
                .putString("state", newState)
                .putLong("timestamp", System.currentTimeMillis())
                .commit()
        } catch (_: Exception) {}
    }

    private fun restoreState() {
        try {
            val prefs = statePrefs()
            state = prefs.getString("state", "IDLE") ?: "IDLE"
        } catch (_: Exception) { state = "IDLE" }
    }

    private fun statePrefs(): SharedPreferences =
        activity.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    // ==================== CONFIG ====================

    data class PowerConfig(
        val enabled: Boolean = false,
        val entityId: String = "",
        val preferNight: Boolean = false,
        val minThreshold: Int = 20,
        val maxThreshold: Int = 80,
        val emergencyThreshold: Int = 10,
        val nightStart: String = "22:00",
        val nightEnd: String = "06:00"
    )

    // ==================== PERSISTENT LOG ====================

    private fun persistLog(level: String, msg: String) {
        try {
            val prefs = activity.getSharedPreferences(LOG_PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString("log", null)
            val arr = if (raw != null) JSONArray(raw) else JSONArray()

            val entry = JSONObject().apply {
                put("t", System.currentTimeMillis())
                put("time", timeFormat.format(Date()))
                put("level", level)
                put("msg", msg)
            }
            arr.put(entry)

            // Trim oldest entries
            while (arr.length() > LOG_MAX_ENTRIES) arr.remove(0)

            prefs.edit().putString("log", arr.toString()).commit()
        } catch (_: Exception) {}
    }

    /** Read the persistent log as a JSON array string. Called by DeviceMetricsInterface. */
    fun getLogJson(): String {
        return try {
            val prefs = activity.getSharedPreferences(LOG_PREFS, Context.MODE_PRIVATE)
            prefs.getString("log", "[]") ?: "[]"
        } catch (_: Exception) { "[]" }
    }

    // ==================== BATTERY HISTORY ====================

    private fun snapTo15(ts: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        cal.set(Calendar.MINUTE, (cal.get(Calendar.MINUTE) / 15) * 15)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun recordHistory(batteryLevel: Int, isCharging: Boolean) {
        try {
            val slot = snapTo15(System.currentTimeMillis())
            if (slot == lastHistorySlot) return
            lastHistorySlot = slot

            val prefs = activity.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString("history", null)
            val arr = if (raw != null) JSONArray(raw) else JSONArray()

            val entry = JSONObject().apply {
                put("t", slot)
                put("l", batteryLevel)
                put("c", isCharging)
            }
            arr.put(entry)

            // Prune entries older than 24 hours
            val cutoff = System.currentTimeMillis() - HISTORY_MAX_AGE_MS
            while (arr.length() > 0 && arr.getJSONObject(0).optLong("t", 0) < cutoff) {
                arr.remove(0)
            }

            prefs.edit().putString("history", arr.toString()).commit()
        } catch (_: Exception) {}
    }

    /** Read the battery history as a JSON array string. Called by DeviceMetricsInterface. */
    fun getHistoryJson(): String {
        return try {
            val prefs = activity.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
            prefs.getString("history", "[]") ?: "[]"
        } catch (_: Exception) { "[]" }
    }

    // ==================== CONFIG ====================

    private fun loadConfig(): PowerConfig {
        return try {
            PowerConfig(
                enabled = powerPrefs.enabled,
                entityId = powerPrefs.entityId,
                preferNight = powerPrefs.preferNight,
                minThreshold = powerPrefs.minThreshold,
                maxThreshold = powerPrefs.maxThreshold,
                emergencyThreshold = powerPrefs.emergencyThreshold,
                nightStart = powerPrefs.nightStart,
                nightEnd = powerPrefs.nightEnd
            )
        } catch (e: Exception) { PowerConfig() }
    }
}

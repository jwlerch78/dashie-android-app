package com.dashieapp.Dashie.halite.telemetryDelegates

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.EventStormTracker
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Delegate handling telemetry collection, WebSocket metrics, and health alerts.
 * Manages sending enriched telemetry to Supabase and persisting dashboard snapshots.
 */
class TelemetryDelegate(
    private val context: Context,
    private val webView: WebView,
    private val halitePrefs: HalitePreferences
) {
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Store latest WebSocket metrics to include with dashboard telemetry
    private var latestWsMetrics: JSONObject? = null

    // Store latest WebSocket health data to include with dashboard telemetry
    private var latestWsHealth: JSONObject? = null

    // Track WebSocket disconnect storms for aggregate logging
    private val wsStormTracker = EventStormTracker("WS_STORM")

    // Provider for app feature state (wake word, camera, etc.)
    var featureStateProvider: (() -> JSONObject)? = null

    /**
     * Handle telemetry data from JavaScript.
     * Always persists snapshot locally; sends to Supabase only if consented.
     */
    fun onTelemetry(jsonData: String) {
        Log.i(TAG, "Received telemetry from JavaScript")

        // Always persist dashboard snapshot locally for OOM crash reports
        persistDashboardSnapshot(jsonData)

        // Only send to Supabase if telemetry consent is granted
        if (!halitePrefs.performance.dashboardTelemetryEnabled || !halitePrefs.performance.dashboardTelemetryConsent) {
            Log.d(TAG, "Telemetry consent not granted - snapshot saved locally only")
            return
        }

        scope.launch {
            try {
                sendTelemetry(jsonData)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send telemetry: ${e.message}")
            }
        }
    }

    /**
     * Persist dashboard structural data to SharedPreferences.
     * Survives OOM kills and is included in crash reports.
     */
    private fun persistDashboardSnapshot(jsonData: String) {
        try {
            val data = JSONObject(jsonData)
            val prefs = context.getSharedPreferences("dashie_lite_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putInt("snapshot_dom_nodes", data.optInt("domNodeCount", 0))
                .putInt("snapshot_camera_count", data.optInt("cameraCount", 0))
                .putInt("snapshot_total_cards", data.optInt("totalCardCount", 0))
                .putInt("snapshot_custom_card_count", data.optInt("customCardCount", 0))
                .putInt("snapshot_entity_count", data.optInt("entityCount", 0))
                .putInt("snapshot_view_count", data.optInt("viewCount", 0))
                .putString("snapshot_cards_json", data.optJSONObject("cards")?.toString() ?: "{}")
                .putString("snapshot_custom_cards_json", data.optJSONObject("customCards")?.toString() ?: "{}")
                .putString("snapshot_media_json", data.optJSONObject("mediaElements")?.toString() ?: "{}")
                .putInt("snapshot_js_heap_mb", data.optJSONObject("fpsMetrics")?.optInt("jsHeapUsedMB", 0) ?: 0)
                .putInt("snapshot_js_heap_limit_mb", data.optJSONObject("fpsMetrics")?.optInt("jsHeapLimitMB", 0) ?: 0)
                .putLong("snapshot_time", System.currentTimeMillis())
                .apply()
            Log.d(TAG, "Dashboard snapshot persisted for crash reports")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist dashboard snapshot: ${e.message}")
        }
    }

    /**
     * Handle Lovelace dashboard config from JavaScript.
     * Persisted to a file (can be 10-50KB) for crash reports and diagnostics.
     * Called once when the dashboard first loads.
     */
    fun onLovelaceConfig(jsonData: String) {
        Log.i(TAG, "Received Lovelace config from JavaScript (${jsonData.length / 1024}KB)")
        scope.launch {
            persistLovelaceConfig(jsonData)
        }
    }

    /**
     * Save raw Lovelace config JSON to a file.
     * Too large for SharedPreferences; file persists across OOM kills.
     */
    private fun persistLovelaceConfig(jsonData: String) {
        try {
            val file = java.io.File(context.filesDir, LOVELACE_CONFIG_FILE)
            file.writeText(jsonData)

            // Also persist a timestamp in SharedPreferences so crash report knows freshness
            val prefs = context.getSharedPreferences("dashie_lite_prefs", Context.MODE_PRIVATE)
            prefs.edit().putLong("lovelace_config_time", System.currentTimeMillis()).apply()

            Log.d(TAG, "Lovelace config persisted (${jsonData.length / 1024}KB)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist Lovelace config: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "TelemetryDelegate"
        private const val TELEMETRY_ENDPOINT_PATH = "/dashboard-telemetry"
        const val LOVELACE_CONFIG_FILE = "lovelace_config.json"

        /**
         * Analyze a Lovelace config JSON and produce a human-readable summary
         * for crash reports. Extracts card types, entity domains, and card_mod usage.
         */
        fun analyzeLovelaceConfig(configJson: String): String {
            try {
                val config = JSONObject(configJson)
                val sb = StringBuilder()

                val views = config.optJSONArray("views") ?: return "(no views in config)"
                sb.appendLine("Views: ${views.length()}")

                // Walk all cards recursively
                val cardCounts = mutableMapOf<String, Int>()
                var cardModCount = 0
                var cardModCssBytes = 0
                val entityDomains = mutableMapOf<String, Int>()
                var totalEntities = 0

                fun processEntity(entityId: String) {
                    val domain = entityId.substringBefore(".")
                    if (domain.isNotEmpty() && domain != entityId) {
                        entityDomains[domain] = (entityDomains[domain] ?: 0) + 1
                        totalEntities++
                    }
                }

                fun walkObject(obj: JSONObject) {
                    // Check for card type
                    val type = obj.optString("type", "")
                    if (type.isNotEmpty()) {
                        cardCounts[type] = (cardCounts[type] ?: 0) + 1
                    }

                    // Check for card_mod
                    val cardMod = obj.optJSONObject("card_mod")
                    if (cardMod != null) {
                        cardModCount++
                        val style = cardMod.optString("style", "")
                        if (style.isNotEmpty()) cardModCssBytes += style.length
                    }

                    // Check for entities
                    obj.optString("entity", "").takeIf { it.contains(".") }?.let { processEntity(it) }

                    // Check entities array
                    val entities = obj.optJSONArray("entities")
                    if (entities != null) {
                        for (i in 0 until entities.length()) {
                            val item = entities.opt(i)
                            when (item) {
                                is String -> if (item.contains(".")) processEntity(item)
                                is JSONObject -> item.optString("entity", "").takeIf { it.contains(".") }?.let { processEntity(it) }
                            }
                        }
                    }

                    // Recurse into nested cards (vertical-stack, horizontal-stack, grid, etc.)
                    val cards = obj.optJSONArray("cards")
                    if (cards != null) {
                        for (i in 0 until cards.length()) {
                            cards.optJSONObject(i)?.let { walkObject(it) }
                        }
                    }

                    // Recurse into card (conditional card wraps a single card)
                    obj.optJSONObject("card")?.let { walkObject(it) }

                    // Check conditions for entity references
                    val conditions = obj.optJSONArray("conditions")
                    if (conditions != null) {
                        for (i in 0 until conditions.length()) {
                            conditions.optJSONObject(i)?.optString("entity", "")
                                ?.takeIf { it.contains(".") }?.let { processEntity(it) }
                        }
                    }

                    // Check chips (mushroom-chips-card)
                    val chips = obj.optJSONArray("chips")
                    if (chips != null) {
                        for (i in 0 until chips.length()) {
                            chips.optJSONObject(i)?.let { walkObject(it) }
                        }
                    }
                }

                for (i in 0 until views.length()) {
                    val view = views.optJSONObject(i) ?: continue
                    val viewCards = view.optJSONArray("cards") ?: continue
                    for (j in 0 until viewCards.length()) {
                        viewCards.optJSONObject(j)?.let { walkObject(it) }
                    }
                }

                // Format card types sorted by count
                val totalCards = cardCounts.values.sum()
                sb.appendLine("Total cards: $totalCards")
                val sortedCards = cardCounts.entries.sortedByDescending { it.value }
                for ((type, count) in sortedCards) {
                    sb.appendLine("  $type: $count")
                }

                // card_mod summary
                if (cardModCount > 0) {
                    sb.appendLine("card_mod: $cardModCount cards, ${cardModCssBytes / 1024}KB CSS")
                }

                // Entity domains sorted by count
                if (totalEntities > 0) {
                    sb.appendLine("Entities in config: $totalEntities references")
                    val sortedDomains = entityDomains.entries.sortedByDescending { it.value }
                    val domainParts = sortedDomains.map { "${it.key}(${it.value})" }
                    sb.appendLine("  ${domainParts.joinToString(", ")}")
                }

                return sb.toString().trimEnd()
            } catch (e: Exception) {
                return "(analysis error: ${e.message})"
            }
        }
    }

    /**
     * Handle WebSocket metrics from JavaScript (called periodically by websocket-monitor.js).
     */
    fun onWsMetrics(jsonData: String) {
        Log.i(TAG, "Received WebSocket metrics from JavaScript")

        if (!halitePrefs.performance.dashboardTelemetryEnabled || !halitePrefs.performance.dashboardTelemetryConsent) {
            return
        }

        try {
            latestWsMetrics = JSONObject(jsonData)
            Log.d(TAG, "WebSocket metrics: ${latestWsMetrics?.toString(2)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse WS metrics: ${e.message}")
        }
    }

    /**
     * Handle WebSocket health alerts from JavaScript.
     * Alerts include: disconnect, reconnect, message_gap, stale_connection, etc.
     */
    fun onWsHealthAlert(jsonData: String) {
        try {
            val alert = JSONObject(jsonData)
            val alertType = alert.optString("alertType", "unknown")
            val timestamp = alert.optString("timestamp", "")

            Log.w(TAG, "WebSocket health alert: $alertType at $timestamp")

            // Store the latest health snapshot
            if (alert.has("health")) {
                latestWsHealth = alert.optJSONObject("health")
            }

            // Log specific alert details with DiagnosticBuffer and storm tracking
            when (alertType) {
                "disconnect" -> {
                    val data = alert.optJSONObject("data")
                    val code = data?.optInt("code", -1) ?: -1
                    val duration = data?.optInt("connectionDurationSec", 0) ?: 0
                    Log.w(TAG, "WebSocket DISCONNECTED: code=$code, was connected for ${duration}s")
                    wsStormTracker.recordEvent("disconnect code=$code")
                    DiagnosticBuffer.warn("WS", "Disconnect: code=$code, duration=${duration}s")
                }
                "reconnect" -> {
                    val data = alert.optJSONObject("data")
                    val count = data?.optInt("reconnectCount", 0) ?: 0
                    Log.i(TAG, "WebSocket RECONNECTED (count: $count)")
                    wsStormTracker.stormEnded("reconnected (count=$count)")
                    DiagnosticBuffer.info("WS", "Reconnected (total: $count)")
                }
                "message_gap" -> {
                    val data = alert.optJSONObject("data")
                    val gap = data?.optInt("durationSec", 0) ?: 0
                    Log.w(TAG, "Message gap detected: ${gap}s without messages")
                    DiagnosticBuffer.warn("WS", "Message gap: ${gap}s")
                }
                "stale_connection" -> {
                    val data = alert.optJSONObject("data")
                    val staleTime = data?.optInt("timeSinceLastMessageSec", 0) ?: 0
                    val wsState = data?.optString("wsReadyStateStr", "unknown") ?: "unknown"
                    Log.w(TAG, "Stale connection: no messages for ${staleTime}s, WS state: $wsState")
                    DiagnosticBuffer.warn("WS", "Stale: ${staleTime}s, state=$wsState")
                }
                "network_offline" -> {
                    Log.w(TAG, "Network went OFFLINE")
                    DiagnosticBuffer.warn("WS", "Network offline")
                }
                "visibility_hidden" -> {
                    Log.d(TAG, "App visibility: HIDDEN")
                }
                "soft_reconnect_attempt" -> {
                    Log.i(TAG, "Soft reconnect attempted (closing WS to trigger HA reconnect)")
                    wsStormTracker.recordEvent("soft_reconnect")
                    DiagnosticBuffer.info("WS", "Soft reconnect attempt")
                }
                "auto_reload" -> {
                    Log.w(TAG, "Auto-reload triggered due to stale connection")
                    wsStormTracker.stormInterrupted("auto_reload")
                    DiagnosticBuffer.warn("WS", "Auto-reload triggered")
                    // Also on disk: this alert immediately precedes a full main-frame
                    // reload, and a reload storm is what kills the process. The
                    // in-memory buffer dies with the process, so a PSS-driven OOM
                    // would lose the only line attributing it.
                    com.dashieapp.Dashie.halite.diagnostics.PersistentLog.warn(
                        "REFRESH", "WebView reload: JS auto-reload (stale WS connection)"
                    )
                }
                "incident_report" -> {
                    val data = alert.optJSONObject("data")
                    val incidentType = data?.optString("incidentType", "unknown") ?: "unknown"
                    val code = data?.optInt("disconnectCode", -1) ?: -1
                    val meaning = data?.optString("disconnectCodeMeaning", "") ?: ""
                    val duration = data?.optString("connectionDurationFormatted", "?") ?: "?"
                    val pingEnabled = data?.optBoolean("pingEnabled", false) ?: false
                    val pingsSent = data?.optInt("pingsSent", 0) ?: 0
                    Log.w(TAG, "INCIDENT REPORT: $incidentType - code=$code ($meaning), " +
                            "connected for $duration, ping=${if (pingEnabled) "ON ($pingsSent sent)" else "OFF"}")
                    DiagnosticBuffer.error("WS", "INCIDENT: $incidentType code=$code ($meaning) duration=$duration")
                }
                "stale_incident" -> {
                    val data = alert.optJSONObject("data")
                    val incidentType = data?.optString("incidentType", "unknown") ?: "unknown"
                    val staleSec = data?.optInt("staleSec", 0) ?: 0
                    val wsState = data?.optString("wsReadyStateStr", "unknown") ?: "unknown"
                    val pingEnabled = data?.optBoolean("pingEnabled", false) ?: false
                    val pingsSent = data?.optInt("pingsSent", 0) ?: 0
                    val networkOnline = data?.optBoolean("networkOnline", true) ?: true
                    val documentHidden = data?.optBoolean("documentHidden", false) ?: false

                    val level = when {
                        incidentType.contains("3min") -> "ERROR"
                        else -> "WARN"
                    }

                    Log.w(TAG, "STALE INCIDENT: $incidentType - stale=${staleSec}s, ws=$wsState, " +
                            "ping=${if (pingEnabled) "ON ($pingsSent)" else "OFF"}, " +
                            "network=${if (networkOnline) "online" else "OFFLINE"}, " +
                            "hidden=$documentHidden")

                    when (level) {
                        "ERROR" -> DiagnosticBuffer.error("WS", "STALE $incidentType: ${staleSec}s, ws=$wsState, ping=$pingEnabled")
                        else -> DiagnosticBuffer.warn("WS", "STALE $incidentType: ${staleSec}s, ws=$wsState, ping=$pingEnabled")
                    }
                }
            }

            // If telemetry is enabled, send alert immediately for important events
            if (halitePrefs.performance.dashboardTelemetryEnabled && halitePrefs.performance.dashboardTelemetryConsent) {
                when (alertType) {
                    "disconnect", "stale_connection", "message_gap", "soft_reconnect_attempt", "auto_reload", "incident_report", "stale_incident" -> {
                        scope.launch {
                            try {
                                sendHealthAlert(alert)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send health alert: ${e.message}")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse WS health alert: ${e.message}")
        }
    }

    /**
     * Return JSON with current app feature state for telemetry correlation.
     */
    fun getFeatureState(): String {
        return try {
            val state = featureStateProvider?.invoke() ?: JSONObject().apply {
                put("wakeWordActive", false)
                put("voiceInteractionActive", false)
                put("cameraStreamActive", false)
            }
            state.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get feature state: ${e.message}")
            "{\"error\": \"${e.message}\"}"
        }
    }

    /**
     * Check if interaction priority mode is enabled.
     */
    fun isInteractionPriorityEnabled(): Boolean {
        return halitePrefs.performance.interactionPriorityEnabled
    }

    /**
     * Send a WebSocket health alert to the telemetry endpoint.
     */
    private suspend fun sendHealthAlert(alert: JSONObject) = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("type", "ws_health_alert")
                put("device_id", getAnonymizedDeviceId())
                put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("android_version", Build.VERSION.RELEASE)
                put("app_version", BuildConfig.VERSION_NAME)
                put("alert", alert)
            }

            val endpoint = BuildConfig.SUPABASE_URL + "/functions/v1" + TELEMETRY_ENDPOINT_PATH
            val anonKey = BuildConfig.SUPABASE_ANON_KEY

            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $anonKey")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true

            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }

            val responseCode = connection.responseCode
            Log.d(TAG, "Health alert sent: $responseCode")

        } catch (e: Exception) {
            Log.e(TAG, "Error sending health alert: ${e.message}")
        }
    }

    /**
     * Send telemetry data to Supabase edge function.
     */
    private suspend fun sendTelemetry(jsonData: String) = withContext(Dispatchers.IO) {
        try {
            val jsData = JSONObject(jsonData)

            val payload = JSONObject().apply {
                put("device_id", getAnonymizedDeviceId())
                put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("android_version", Build.VERSION.RELEASE)
                put("webview_version", getWebViewVersion())
                put("app_version", BuildConfig.VERSION_NAME)
                put("device_metrics", getDeviceMetrics())

                if (jsData.has("loadTimeMs")) put("loadTimeMs", jsData.optInt("loadTimeMs"))
                if (jsData.has("totalTimeMs")) put("totalTimeMs", jsData.optInt("totalTimeMs"))
                if (jsData.has("viewCount")) put("viewCount", jsData.optInt("viewCount"))
                if (jsData.has("entityCount")) put("entityCount", jsData.optInt("entityCount"))
                if (jsData.has("domNodeCount")) put("domNodeCount", jsData.optInt("domNodeCount"))
                if (jsData.has("totalCardCount")) put("totalCardCount", jsData.optInt("totalCardCount"))
                if (jsData.has("cameraCount")) put("cameraCount", jsData.optInt("cameraCount"))
                if (jsData.has("customCardCount")) put("customCardCount", jsData.optInt("customCardCount"))

                if (jsData.has("cards")) put("cards", jsData.optJSONObject("cards") ?: JSONObject())
                if (jsData.has("customCards")) put("customCards", jsData.optJSONObject("customCards") ?: JSONObject())
                if (jsData.has("cardFeatures")) put("cardFeatures", jsData.optJSONObject("cardFeatures") ?: JSONObject())
                if (jsData.has("fpsMetrics")) put("fpsMetrics", jsData.optJSONObject("fpsMetrics") ?: JSONObject())

                latestWsMetrics?.let { put("wsMetrics", it) }
                latestWsHealth?.let { put("wsHealth", it) }

                if (jsData.has("appFeatures")) put("appFeatures", jsData.optJSONObject("appFeatures") ?: JSONObject())
                if (jsData.has("collectedAt")) put("collectedAt", jsData.optString("collectedAt"))
            }

            Log.i(TAG, "Sending telemetry: ${payload.toString(2)}")

            val endpoint = BuildConfig.SUPABASE_URL + "/functions/v1" + TELEMETRY_ENDPOINT_PATH
            val anonKey = BuildConfig.SUPABASE_ANON_KEY

            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $anonKey")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true

            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }

            val responseCode = connection.responseCode
            val responseBody = try {
                if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }
            } catch (e: Exception) {
                ""
            }

            Log.i(TAG, "Telemetry response: $responseCode - $responseBody")

        } catch (e: Exception) {
            Log.e(TAG, "Error sending telemetry: ${e.message}")
        }
    }

    private fun getAnonymizedDeviceId(): String {
        // Hashed for k-anonymity in dashboard telemetry; source is the
        // hardware-tied stable ID so the value is consistent across
        // package reinstalls and matches what the control center shows.
        return com.dashieapp.Dashie.util.StableDeviceId.read(context).hashCode().toString(16)
    }

    private fun getWebViewVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo("com.google.android.webview", 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            try {
                val packageInfo = context.packageManager.getPackageInfo("com.android.webview", 0)
                packageInfo.versionName ?: "unknown"
            } catch (e2: Exception) {
                "unknown"
            }
        }
    }

    private fun getDeviceMetrics(): JSONObject {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val runtime = Runtime.getRuntime()

        return JSONObject().apply {
            put("total_ram_mb", memoryInfo.totalMem / (1024 * 1024))
            put("available_ram_mb", memoryInfo.availMem / (1024 * 1024))
            put("low_memory", memoryInfo.lowMemory)
            put("heap_max_mb", runtime.maxMemory() / (1024 * 1024))
            put("heap_used_mb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024))
            put("cpu_cores", runtime.availableProcessors())

            val displayMetrics = context.resources.displayMetrics
            put("screen_width", displayMetrics.widthPixels)
            put("screen_height", displayMetrics.heightPixels)
            put("screen_density", displayMetrics.density)
            put("screen_dpi", displayMetrics.densityDpi)

            put("is_low_ram_device", activityManager.isLowRamDevice)
            put("api_level", Build.VERSION.SDK_INT)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER.takeIf { it.isNotEmpty() && it != Build.UNKNOWN }?.let {
                    put("soc_manufacturer", it)
                }
                Build.SOC_MODEL.takeIf { it.isNotEmpty() && it != Build.UNKNOWN }?.let {
                    put("soc_model", it)
                }
            }
        }
    }

    fun destroy() {
        scope.cancel()
    }
}

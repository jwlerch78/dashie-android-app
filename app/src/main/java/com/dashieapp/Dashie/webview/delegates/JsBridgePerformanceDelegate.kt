package com.dashieapp.Dashie.webview.delegates

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.MemoryMonitor
import com.dashieapp.Dashie.halite.diagnostics.SystemMetricsProvider
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Delegate handling performance and diagnostics JavaScript bridge methods.
 * Includes: Performance overlay, system metrics, memory thresholds,
 * stealth refresh, emergency recovery, enhanced logging.
 */
class JsBridgePerformanceDelegate(
    private val context: Context,
    private val webView: WebView,
    private val halitePrefs: () -> HalitePreferences?
) {
    companion object {
        private const val TAG = "JsBridgePerf"
    }

    // Lazy-initialized system metrics provider
    private val systemMetricsProvider by lazy {
        SystemMetricsProvider(context)
    }

    // Callbacks
    var onPerformanceOverlayChanged: ((Boolean) -> Unit)? = null
    var onThresholdsChanged: (() -> Unit)? = null
    var onStealthRefreshSettingsChanged: (() -> Unit)? = null

    // ============================================
    // System Metrics
    // ============================================

    /**
     * Get system metrics (CPU, RAM, Heap, Thermal) as JSON.
     * Called by the performance overlay at 1Hz for real-time display.
     */
    fun getSystemMetrics(): String {
        val recoveryEnabled = halitePrefs()?.performance?.emergencyRecoveryEnabled ?: true
        return systemMetricsProvider.getAllMetricsJson(recoveryEnabled)
    }

    // ============================================
    // Diagnostics Mode (Performance Overlay)
    // ============================================

    fun setDiagnosticsModeEnabled(enabled: Boolean) {
        halitePrefs()?.performance?.diagnosticsModeEnabled = enabled
        Log.i(TAG, "📊 Diagnostics mode ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Record the HA kiosk hide-UI apply outcome into the captured DiagnosticBuffer.
     * Called from the injected kiosk script (window.DashieNative.reportKioskUi) so the
     * "Send/Download Diagnostics" report shows whether the shadow-DOM CSS actually applied
     * (and after how many attempts) — previously that only went to the WebView console and
     * was invisible in diagnostics. Pairs with the Kotlin-side KIOSK_UI interception logs in
     * MainHaKioskCssInjector. Lets us diagnose "hiding doesn't work on one tablet" from the
     * tester's diagnostic instead of logcat. (See email-triage-log: Scott Menzer.)
     */
    fun reportKioskUi(detail: String?) {
        DiagnosticBuffer.info("KIOSK_UI", "js: ${detail ?: "(null)"}")
    }

    fun setPerformanceOverlayEnabled(enabled: Boolean) {
        halitePrefs()?.performance?.performanceOverlayEnabled = enabled
        Log.i(TAG, "📊 Performance overlay ${if (enabled) "enabled" else "disabled"}")
        webView.post {
            onPerformanceOverlayChanged?.invoke(enabled)
        }
    }

    fun setPerformanceOverlayPosition(position: String) {
        halitePrefs()?.performance?.performanceOverlayPosition = position
        Log.i(TAG, "📊 Performance overlay position set to: $position")
    }

    // ============================================
    // Performance Settings (Bulk Read)
    // ============================================

    fun getPerformanceSettings(): String {
        val prefs = halitePrefs()
        val thresholdInfo = MemoryMonitor.getThresholdInfo()

        return org.json.JSONObject().apply {
            // Stealth WebView Refresh
            put("stealthRefreshEnabled", prefs?.performance?.stealthRefreshEnabled ?: true)
            put("stealthRefreshIntervalMinutes", prefs?.performance?.stealthRefreshIntervalMinutes ?: 120)
            put("dailyRefreshHour", prefs?.performance?.dailyRefreshHour ?: 0)
            // Multi-select hours as JSON array
            put("dailyRefreshHours", org.json.JSONArray(prefs?.performance?.dailyRefreshHours?.sorted() ?: emptyList<Int>()))
            // Emergency Recovery (master enable/disable)
            put("emergencyRecoveryEnabled", prefs?.performance?.emergencyRecoveryEnabled ?: true)
            // Dual threshold system: RAM % OR Heap %
            // Idle thresholds (during screensaver)
            put("idleRamPercent", prefs?.performance?.idleRamPercent ?: 82)
            put("idleHeapPercent", prefs?.performance?.idleHeapPercent ?: 80)
            put("calculatedIdleThresholdMb", thresholdInfo.warningMb)
            // Critical thresholds (immediate)
            put("criticalRamPercent", prefs?.performance?.criticalRamPercent ?: 90)
            put("criticalHeapPercent", prefs?.performance?.criticalHeapPercent ?: 88)
            put("calculatedCriticalThresholdMb", thresholdInfo.emergencyMb)
            // Legacy fields for backward compatibility
            put("emergencyThresholdMode", prefs?.performance?.emergencyThresholdMode ?: "auto")
            put("emergencyThresholdMb", prefs?.performance?.emergencyThresholdMb ?: 550)
            put("calculatedThresholdMb", thresholdInfo.emergencyMb)
            // Diagnostics
            put("performanceOverlayEnabled", prefs?.performance?.performanceOverlayEnabled ?: false)
            put("performanceOverlayPosition", prefs?.performance?.performanceOverlayPosition ?: "top-left")
            put("enhancedLoggingEnabled", prefs?.performance?.enhancedLoggingEnabled ?: false)
            put("apiLoggingEnabled", prefs?.performance?.logWebViewApiRequests ?: false)
            // Dashboard telemetry (share performance data)
            put("dashboardTelemetryEnabled", prefs?.performance?.dashboardTelemetryEnabled ?: false)
            // Auto-reload (WebSocket stale)
            put("autoReloadMinutes", prefs?.performance?.autoReloadStaleMinutes ?: 3)
            // Device info for threshold display
            put("deviceTotalRamMb", thresholdInfo.deviceTotalRamMb)
        }.toString()
    }

    // ============================================
    // Stealth Refresh Settings
    // ============================================

    fun setStealthRefreshEnabled(enabled: Boolean) {
        halitePrefs()?.performance?.stealthRefreshEnabled = enabled
        Log.i(TAG, "🔄 Stealth refresh ${if (enabled) "enabled" else "disabled"}")
        onStealthRefreshSettingsChanged?.invoke()
    }

    fun setStealthRefreshIntervalMinutes(minutes: Int) {
        halitePrefs()?.performance?.stealthRefreshIntervalMinutes = minutes
        Log.i(TAG, "🔄 Stealth refresh interval set to $minutes minutes")
        onStealthRefreshSettingsChanged?.invoke()
    }

    fun setDailyRefreshHour(hour: Int) {
        halitePrefs()?.performance?.dailyRefreshHour = hour
        Log.i(TAG, "🔄 Daily refresh hour set to $hour (0 = disabled)")
    }

    /**
     * Set multiple daily refresh hours (multi-select).
     * @param hoursJson Comma-separated hours, e.g., "6,23" for 6 AM and 11 PM. Empty = disabled.
     */
    fun setDailyRefreshHours(hoursJson: String) {
        Log.i(TAG, "📅 setDailyRefreshHours called with: '$hoursJson'")
        val hours = if (hoursJson.isBlank()) {
            emptySet()
        } else {
            hoursJson.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..24 }
                .toSet()
        }
        Log.i(TAG, "📅 Parsed hours: $hours")
        val prefs = halitePrefs()
        if (prefs == null) {
            Log.e(TAG, "📅 ERROR: halitePrefs() returned null!")
            return
        }
        prefs.performance.dailyRefreshHours = hours
        // Verify it was stored
        val readBack = prefs.performance.dailyRefreshHours
        Log.i(TAG, "📅 Read back after store: $readBack")
        Log.i(TAG, "🔄 Daily refresh hours set to: ${hours.sorted().joinToString(", ") { formatHour(it) }} (${hours.size} times)")
        // Notify that stealth/daily refresh settings changed (to reschedule if active)
        onStealthRefreshSettingsChanged?.invoke()
    }

    private fun formatHour(hour: Int): String {
        val normalized = if (hour == 24) 0 else hour
        return String.format("%02d:00", normalized)
    }

    // ============================================
    // Emergency Recovery Settings
    // ============================================

    fun setEmergencyRecoveryEnabled(enabled: Boolean) {
        halitePrefs()?.performance?.emergencyRecoveryEnabled = enabled
        Log.i(TAG, "🚨 Emergency recovery ${if (enabled) "enabled" else "disabled"}")
        webView.post { onThresholdsChanged?.invoke() }
    }

    fun setEmergencyThresholdMode(mode: String) {
        halitePrefs()?.performance?.emergencyThresholdMode = mode
        Log.i(TAG, "🚨 Emergency threshold mode set to: $mode")
    }

    fun setEmergencyThresholdMb(mb: Int) {
        halitePrefs()?.performance?.emergencyThresholdMb = mb
        Log.i(TAG, "🚨 Emergency threshold set to: $mb MB")
    }

    // ============================================
    // Dual Threshold System (RAM % OR PSS)
    // ============================================

    fun setIdleRamPercent(percent: Int) {
        halitePrefs()?.performance?.idleRamPercent = percent
        Log.i(TAG, "📊 Idle RAM threshold set to: $percent%")
        webView.post { onThresholdsChanged?.invoke() }
    }

    fun setIdleHeapPercent(percent: Int) {
        halitePrefs()?.performance?.idleHeapPercent = percent
        Log.i(TAG, "📊 Idle Heap threshold set to: $percent%")
        webView.post { onThresholdsChanged?.invoke() }
    }

    fun setCriticalRamPercent(percent: Int) {
        halitePrefs()?.performance?.criticalRamPercent = percent
        Log.i(TAG, "🚨 Critical RAM threshold set to: $percent%")
        webView.post { onThresholdsChanged?.invoke() }
    }

    fun setCriticalHeapPercent(percent: Int) {
        halitePrefs()?.performance?.criticalHeapPercent = percent
        Log.i(TAG, "🚨 Critical Heap threshold set to: $percent%")
        webView.post { onThresholdsChanged?.invoke() }
    }

    // ============================================
    // Dashboard Telemetry (Share Performance Data)
    // ============================================

    fun setDashboardTelemetryEnabled(enabled: Boolean) {
        halitePrefs()?.performance?.dashboardTelemetryEnabled = enabled
        halitePrefs()?.performance?.dashboardTelemetryConsent = enabled
        Log.i(TAG, "📊 Dashboard telemetry ${if (enabled) "enabled" else "disabled"}")
    }

    // ============================================
    // Enhanced Logging
    // ============================================

    fun setEnhancedLoggingEnabled(enabled: Boolean) {
        halitePrefs()?.performance?.enhancedLoggingEnabled = enabled
        Log.i(TAG, "📝 Enhanced logging ${if (enabled) "enabled" else "disabled"}")
    }

    // ============================================
    // Auto-Reload (WebSocket Stale)
    // ============================================

    fun setAutoReloadMinutes(minutes: Int) {
        halitePrefs()?.performance?.autoReloadStaleMinutes = minutes
        Log.i(TAG, "🔄 Auto-reload stale set to $minutes minutes")
    }

    // ============================================
    // Send Telemetry Now (One-Time)
    // ============================================

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Send a one-time performance telemetry snapshot.
     * Returns "ok" on success or an error message.
     */
    fun sendTelemetryNow(): String {
        Log.i(TAG, "📊 Sending one-time telemetry snapshot")
        try {
            val deviceId = com.dashieapp.Dashie.util.StableDeviceId.read(context).ifEmpty { "unknown" }
            val metricsJson = getSystemMetrics()
            val settingsJson = getPerformanceSettings()
            val diagnosticsText = DiagnosticBuffer.exportAsText()

            val payload = JSONObject().apply {
                put("type", "performance_snapshot")
                put("device_id", deviceId)
                put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("android_version", Build.VERSION.RELEASE)
                put("api_level", Build.VERSION.SDK_INT)
                put("app_version", BuildConfig.VERSION_NAME)
                put("build_type", BuildConfig.BUILD_TYPE)
                put("environment", BuildConfig.ENVIRONMENT)
                put("system_metrics", JSONObject(metricsJson))
                put("performance_settings", JSONObject(settingsJson))
                put("event_count", DiagnosticBuffer.size())
                put("diagnostics", diagnosticsText)
                put("timestamp", System.currentTimeMillis())
            }

            // 🔴 Gated with the credential itself (2026-08-02). Blanking the Chickadee
            // flavors' BuildConfig would make this build "https:///functions/v1/…" and throw
            // inside the coroutine — a caught, silent failure that still ATTEMPTED to phone a
            // Dashie endpoint from an account-free product on every diagnostics upload. Refusing
            // before the attempt is the honest behaviour, and it is loud.
            if (!com.dashieapp.Dashie.edition.EditionSeams.hasAccounts) {
                android.util.Log.i(TAG, "DROP: [expected] dashboard telemetry not uploaded — " +
                    "this edition is account-free and has no Dashie endpoint to report to.")
                // A distinct value, not "sending": the JS caller shows a result, and claiming
                // "sending" for something deliberately never sent is the quiet lie this whole
                // edition boundary exists to avoid. The existing error path already proves the
                // caller tolerates a non-"sending" string.
                return "unavailable: no account in this edition"
            }

            scope.launch {
                try {
                    val endpoint = BuildConfig.SUPABASE_URL + "/functions/v1/dashboard-telemetry"
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
                    Log.i(TAG, "📊 Telemetry snapshot sent, response: $responseCode")

                    withContext(Dispatchers.Main) {
                        val success = responseCode in 200..299
                        val js = if (success) {
                            "window.dispatchEvent(new CustomEvent('dashie-telemetry-sent', {detail:{success:true}}))"
                        } else {
                            "window.dispatchEvent(new CustomEvent('dashie-telemetry-sent', {detail:{success:false,error:'HTTP $responseCode'}}))"
                        }
                        webView.evaluateJavascript(js, null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "📊 Failed to send telemetry snapshot: ${e.message}")
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(
                            "window.dispatchEvent(new CustomEvent('dashie-telemetry-sent', {detail:{success:false,error:'${e.message?.replace("'", "")}'}}))",
                            null
                        )
                    }
                }
            }

            return "sending"
        } catch (e: Exception) {
            Log.e(TAG, "📊 Failed to build telemetry payload: ${e.message}")
            return "error: ${e.message}"
        }
    }

}

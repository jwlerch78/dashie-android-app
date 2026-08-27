package com.dashieapp.Dashie.halite.sidebar.dialogs

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.MediaCodecCapabilities
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.halite.diagnostics.CrashHandler
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.dashieapp.Dashie.edition.brandName

/**
 * Handles diagnostic data collection and submission dialogs for Dashie Lite.
 * Allows users to view, copy, and send diagnostic events to help troubleshoot issues.
 */
class DiagnosticsDialogs(
    private val activity: Activity,
    private val halitePrefs: HalitePreferences
) {
    // Viewport diagnostics captured from WebView (set externally)
    var lastViewportDiagnostics: String? = null
    // Motion wake diagnostics provider (set externally by whoever has access to HaliteScreenController)
    var motionWakeDiagnosticsProvider: (() -> String)? = null
    // Screensaver panel state provider (set externally — see ScreensaverPanelCoordinator.describePanelState)
    var screensaverPanelDiagnosticsProvider: (() -> String)? = null
    companion object {
        private const val TAG = "DiagnosticsDialogs"

        // Diagnostics endpoint path (appended to SUPABASE_URL from BuildConfig)
        private const val DIAGNOSTICS_ENDPOINT_PATH = "/dashboard-telemetry"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Send diagnostics headlessly — no UI dialog. Used by remote triggers
     * (Console "Send diagnostics" button broadcasts on the per-device
     * realtime channel, the webapp listener calls into this via the JS
     * bridge). Brief toast feedback so the user knows something happened,
     * but no review dialog. */
    fun sendDiagnosticsHeadless() {
        val diagnosticsText = DiagnosticBuffer.exportAsText()
        scope.launch {
            val success = sendDiagnostics(diagnosticsText)
            activity.runOnUiThread {
                if (success) {
                    Toast.makeText(activity, "Diagnostics uploaded (Console request)", Toast.LENGTH_SHORT).show()
                    DiagnosticBuffer.clear()
                } else {
                    Toast.makeText(activity, "Diagnostic upload failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Upload the most recent pending crash report (if any) headlessly.
     * Returns silently when there's no pending report — the Console
     * should normally check has_crash_report before broadcasting, but
     * we treat the "no pending" case as a no-op. */
    fun sendPendingCrashReportHeadless() {
        val crashReport = CrashHandler.getPendingCrashReport(activity)
        if (crashReport.isNullOrEmpty()) {
            activity.runOnUiThread {
                Toast.makeText(activity, "No pending crash report to send", Toast.LENGTH_SHORT).show()
            }
            return
        }
        scope.launch {
            val success = com.dashieapp.Dashie.halite.diagnostics.CrashReportUploader.upload(activity, crashReport)
            activity.runOnUiThread {
                if (success) {
                    Toast.makeText(activity, "Crash report uploaded (Console request)", Toast.LENGTH_SHORT).show()
                    CrashHandler.clearPendingCrashReports(activity)
                } else {
                    Toast.makeText(activity, "Crash upload failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Show the send diagnostics dialog
     */
    fun showSendDiagnosticsDialog() {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_send_diagnostics, null)

        dialogView.findViewById<TextView>(R.id.dialogSubtitle)?.text =
            activity.getString(R.string.diagnostics_share_subtitle, activity.getString(R.string.brand_name))

        // Get references to views
        val textEventCount = dialogView.findViewById<TextView>(R.id.textEventCount)
        val textDiagnosticsPreview = dialogView.findViewById<TextView>(R.id.textDiagnosticsPreview)
        val buttonClose = dialogView.findViewById<Button>(R.id.buttonClose)
        val buttonCopy = dialogView.findViewById<Button>(R.id.buttonCopy)
        val buttonSend = dialogView.findViewById<Button>(R.id.buttonSend)

        // Get diagnostic data
        val eventCount = DiagnosticBuffer.size()
        val diagnosticsText = DiagnosticBuffer.exportAsText()

        // Update UI
        textEventCount.text = "$eventCount events in buffer"
        textDiagnosticsPreview.text = if (diagnosticsText.isNotEmpty()) {
            diagnosticsText
        } else {
            "No diagnostic events yet.\n\nDiagnostic events are recorded when:\n- PIN entry is attempted\n- RTSP streaming starts/stops\n- WebSocket connection issues occur"
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Close button
        buttonClose.setOnClickListener {
            dialog.dismiss()
        }

        // Copy button
        buttonCopy.setOnClickListener {
            val fullReport = buildFullReport(diagnosticsText)
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("${activity.brandName()} Diagnostics", fullReport))
            Toast.makeText(activity, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        // Send button
        buttonSend.setOnClickListener {
            buttonSend.isEnabled = false
            buttonSend.text = "Sending..."

            scope.launch {
                val success = sendDiagnostics(diagnosticsText)
                activity.runOnUiThread {
                    if (success) {
                        Toast.makeText(activity, "Diagnostics sent successfully", Toast.LENGTH_SHORT).show()
                        // Clear the buffer after successful send
                        DiagnosticBuffer.clear()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(activity, "Failed to send diagnostics", Toast.LENGTH_SHORT).show()
                        buttonSend.isEnabled = true
                        buttonSend.text = "Send"
                    }
                }
            }
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
    }

    /**
     * Build a full diagnostic report including device info, diagnostic buffer, and persistent logs
     */
    private fun buildFullReport(diagnosticsText: String, fullPersistentLog: Boolean = false): String {
        val sb = StringBuilder()

        sb.appendLine("═══ DASHIE LITE DIAGNOSTIC REPORT ═══")
        sb.appendLine()

        // Device info (matches crash report detail level)
        sb.appendLine("═══ DEVICE INFO ═══")
        sb.appendLine()
        sb.appendLine("Manufacturer: ${Build.MANUFACTURER}")
        sb.appendLine("Model: ${Build.MODEL}")
        sb.appendLine("Device: ${Build.DEVICE}")
        sb.appendLine("Board: ${Build.BOARD}")
        sb.appendLine("Hardware: ${Build.HARDWARE}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine()

        // App info
        sb.appendLine("═══ APP INFO ═══")
        sb.appendLine()
        sb.appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Build Type: ${BuildConfig.BUILD_TYPE}")
        sb.appendLine("Flavor: ${BuildConfig.FLAVOR}")
        sb.appendLine("Environment: ${BuildConfig.ENVIRONMENT}")
        sb.appendLine()

        sb.append(com.dashieapp.Dashie.api.ApiAccessObserver.reportSection(activity))

        // Setup / permissions snapshot — device owner/admin, battery exemption,
        // overlay, exact alarm, camera/notification perms + keep-alive app settings.
        // collectAll() only runs on a crash, so include the key state here too.
        sb.appendLine("═══ SETUP & PERMISSIONS ═══")
        sb.appendLine()
        sb.appendLine(
            com.dashieapp.Dashie.halite.diagnostics.CrashDiagnosticsCollector
                .collectSystemState(activity)
        )
        sb.appendLine(
            com.dashieapp.Dashie.halite.diagnostics.CrashDiagnosticsCollector
                .collectAppSettings(activity)
        )
        sb.appendLine()

        // Memory info
        sb.appendLine("═══ MEMORY INFO ═══")
        sb.appendLine()
        val runtime = Runtime.getRuntime()
        val heapMaxMB = runtime.maxMemory() / (1024 * 1024)
        val heapTotalMB = runtime.totalMemory() / (1024 * 1024)
        val heapFreeMB = runtime.freeMemory() / (1024 * 1024)
        val heapUsedMB = heapTotalMB - heapFreeMB
        sb.appendLine("Heap: ${heapUsedMB}MB used / ${heapMaxMB}MB max (${heapTotalMB}MB allocated, ${heapFreeMB}MB free)")
        try {
            val activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val totalRAM = memInfo.totalMem / (1024 * 1024)
            val availRAM = memInfo.availMem / (1024 * 1024)
            val usedRAM = totalRAM - availRAM
            val ramPercent = (usedRAM * 100 / totalRAM)
            sb.appendLine("System RAM: ${usedRAM}MB / ${totalRAM}MB (${ramPercent}% used, ${availRAM}MB available)")
            sb.appendLine("Low Memory: ${memInfo.lowMemory}")
        } catch (e: Exception) {
            sb.appendLine("System RAM: (error reading)")
        }
        sb.appendLine()

        // WebView info
        sb.appendLine("═══ WEBVIEW INFO ═══")
        sb.appendLine()
        sb.appendLine("WebView: ${CrashHandler.getWebViewVersion(activity)}")
        sb.appendLine()

        // Display metrics
        val displayMetrics = activity.resources.displayMetrics
        sb.appendLine("═══ DISPLAY INFO ═══")
        sb.appendLine()
        sb.appendLine("Screen: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels} px")
        sb.appendLine("Density: ${displayMetrics.densityDpi} dpi (${displayMetrics.density}x)")
        sb.appendLine("Scaled Density: ${displayMetrics.scaledDensity}")
        sb.appendLine("Dashboard Zoom: ${halitePrefs.display.dashboardZoom}%")
        sb.appendLine()

        // Camera streaming settings and capabilities
        sb.appendLine("═══ CAMERA STREAMING ═══")
        sb.appendLine()
        sb.appendLine("Streaming Enabled: ${halitePrefs.camera.rtspEnabled}")
        sb.appendLine("Software Encoding: ${halitePrefs.camera.rtspForceSoftwareEncoding}")
        sb.appendLine("Resolution: ${halitePrefs.camera.rtspResolutionDisplay} (${halitePrefs.camera.rtspResolutionWidth}x${halitePrefs.camera.rtspResolutionHeight})")
        sb.appendLine("Frame Rate: ${halitePrefs.camera.rtspFps} fps")
        sb.appendLine("Bitrate: ${halitePrefs.camera.rtspBitrate / 1_000_000}Mbps")
        sb.appendLine("Port: ${halitePrefs.camera.rtspPort}")
        sb.appendLine()

        // Camera capabilities
        val cameraCaps = MediaCodecCapabilities.getCameraCapabilities(activity, useFrontCamera = true)
        if (cameraCaps != null) {
            sb.appendLine("Camera: ${cameraCaps.cameraId} (${cameraCaps.facing})")
            sb.appendLine("Sensor Orientation: ${cameraCaps.sensorOrientation}°")
            sb.appendLine("Native Aspect Ratios: ${cameraCaps.nativeAspectRatios.joinToString(", ")}")
            val topSizes = cameraCaps.supportedSizes.take(6).joinToString(", ") { "${it.width}x${it.height}" }
            sb.appendLine("Supported Sizes: $topSizes${if (cameraCaps.supportedSizes.size > 6) "..." else ""}")
        } else {
            sb.appendLine("Camera: Not available or no permission")
        }
        sb.appendLine()

        // Encoder capabilities
        val encoderCaps = MediaCodecCapabilities.getH264EncoderCapabilities()
        if (encoderCaps != null) {
            sb.appendLine("Encoder: ${encoderCaps.encoderName}")
            sb.appendLine("Hardware Accelerated: ${encoderCaps.isHardwareAccelerated}")
            sb.appendLine("Resolution Range: ${encoderCaps.widthRange.first}-${encoderCaps.widthRange.last} x ${encoderCaps.heightRange.first}-${encoderCaps.heightRange.last}")
            sb.appendLine("FPS Range: ${encoderCaps.supportedFrameRates.first}-${encoderCaps.supportedFrameRates.last}")
            sb.appendLine("Bitrate Range: ${encoderCaps.bitrateRange.first / 1000}k - ${encoderCaps.bitrateRange.last / 1_000_000}M")
            sb.appendLine("Alignment: ${encoderCaps.widthAlignment}x${encoderCaps.heightAlignment}")
            sb.appendLine("Profiles: ${encoderCaps.supportedProfiles.joinToString(", ")}")

            // Check if current settings are supported
            val resSupported = encoderCaps.isResolutionSupported(halitePrefs.camera.rtspResolutionWidth, halitePrefs.camera.rtspResolutionHeight)
            val fpsSupported = halitePrefs.camera.rtspFps in encoderCaps.supportedFrameRates
            if (!resSupported || !fpsSupported) {
                sb.appendLine()
                sb.appendLine("⚠️ COMPATIBILITY WARNINGS:")
                if (!resSupported) {
                    sb.appendLine("  - Resolution ${halitePrefs.camera.rtspResolutionWidth}x${halitePrefs.camera.rtspResolutionHeight} may not be supported")
                }
                if (!fpsSupported) {
                    sb.appendLine("  - FPS ${halitePrefs.camera.rtspFps} may not be supported")
                }
            }
        } else {
            sb.appendLine("Encoder: Failed to query capabilities")
        }
        sb.appendLine()

        // Motion wake diagnostics
        sb.appendLine("═══ MOTION WAKE ═══")
        sb.appendLine()
        sb.appendLine("Mode Setting: ${halitePrefs.screensaver.motionWakeMode}")
        if (halitePrefs.screensaver.motionWakeMode == "camera" || halitePrefs.screensaver.motionWakeMode == "face") {
            sb.appendLine("Threshold: ${halitePrefs.screensaver.cameraWakeThresholdDouble}%")
        }
        sb.appendLine("Hardware Screen Off: ${if (halitePrefs.sleep.hardwareScreenOff) "Enabled" else "Disabled"}")
        val motionDiag = motionWakeDiagnosticsProvider?.invoke()
        if (motionDiag != null) {
            sb.appendLine(motionDiag)
        } else {
            sb.appendLine("(runtime state not available)")
        }
        sb.appendLine()

        // Screensaver panel state — catches the "photo stranded shrunk to the
        // left, blank strip on the right" bug. If captured while the bug is on
        // screen, the ⚠️ STALE PANEL line confirms it directly.
        sb.appendLine("═══ SCREENSAVER PANEL ═══")
        sb.appendLine()
        val panelDiag = screensaverPanelDiagnosticsProvider?.invoke()
        if (panelDiag != null) {
            sb.append(panelDiag)
        } else {
            sb.appendLine("(runtime state not available)")
        }
        sb.appendLine()

        // Viewport diagnostics from WebView (if available)
        sb.appendLine("═══ VIEWPORT DIAGNOSTICS (from WebView) ═══")
        sb.appendLine()
        sb.append(lastViewportDiagnostics ?: "(not captured - reload dashboard to capture)")
        sb.appendLine()
        sb.appendLine()

        // Lovelace config analysis
        sb.appendLine("═══ LOVELACE CONFIG (dashboard analysis) ═══")
        sb.appendLine()
        try {
            val configFile = java.io.File(activity.filesDir,
                com.dashieapp.Dashie.halite.telemetryDelegates.TelemetryDelegate.LOVELACE_CONFIG_FILE)
            if (configFile.exists()) {
                val prefs = activity.getSharedPreferences("dashie_lite_prefs", Context.MODE_PRIVATE)
                val configTime = prefs.getLong("lovelace_config_time", 0L)
                if (configTime > 0) {
                    val ageMin = (System.currentTimeMillis() - configTime) / 60_000
                    sb.appendLine("Captured: ${ageMin}min ago, Size: ${configFile.length() / 1024}KB")
                }
                sb.appendLine(com.dashieapp.Dashie.halite.telemetryDelegates.TelemetryDelegate
                    .analyzeLovelaceConfig(configFile.readText()))
            } else {
                sb.appendLine("(no Lovelace config captured - reload dashboard)")
            }
        } catch (e: Exception) {
            sb.appendLine("(error: ${e.message})")
        }
        sb.appendLine()
        sb.appendLine()

        // In-memory diagnostic events
        sb.appendLine("═══ DIAGNOSTIC EVENTS (in-memory buffer) ═══")
        sb.appendLine()
        sb.append(diagnosticsText.ifEmpty { "(no events)" })
        sb.appendLine()
        sb.appendLine()

        // Persistent log (survives restarts). Uploads carry the FULL on-disk log
        // (~3MB overnight history); the on-screen/clipboard path uses a tail.
        if (fullPersistentLog) {
            sb.appendLine("═══ PERSISTENT LOG (on-disk, full, rotating ~3MB) ═══")
            sb.appendLine()
            sb.append(PersistentLog.exportLogs())
        } else {
            sb.appendLine("═══ PERSISTENT LOG (recent 200 lines) ═══")
            sb.appendLine()
            sb.append(PersistentLog.exportRecentLogs(200))
        }
        sb.appendLine()
        sb.appendLine()

        // Check for any pending crash reports
        val crashCount = CrashHandler.getPendingCrashCount(activity)
        if (crashCount > 0) {
            sb.appendLine("═══ PENDING CRASH REPORT ═══")
            sb.appendLine()
            val crashReport = CrashHandler.getPendingCrashReport(activity)
            sb.append(crashReport ?: "(error reading crash report)")
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * Send diagnostics to the server
     */
    private suspend fun sendDiagnostics(diagnosticsText: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Hardware-tied stable ID (matches control center footer + server-side logs).
            val deviceId = com.dashieapp.Dashie.util.StableDeviceId.read(activity).ifEmpty { "unknown" }

            // Get encoder info for diagnostics
            val encoderCaps = MediaCodecCapabilities.getH264EncoderCapabilities()

            val payload = JSONObject().apply {
                put("type", "diagnostics_report")
                put("device_id", deviceId)
                put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("android_version", Build.VERSION.RELEASE)
                put("api_level", Build.VERSION.SDK_INT)
                put("app_version", BuildConfig.VERSION_NAME)
                put("build_type", BuildConfig.BUILD_TYPE)
                put("environment", BuildConfig.ENVIRONMENT)
                put("event_count", DiagnosticBuffer.size())
                // WebView identifier is critical for triaging WebView-
                // version-specific bugs (camera card compositor stalls,
                // codec init differences, etc.). Persisted into the
                // diagnostics text so it survives even on edge functions
                // that don't know about webview_version yet.
                val webViewInfo = com.dashieapp.Dashie.halite.diagnostics.CrashHandler
                    .getWebViewVersionInfo()
                put("webview_version", webViewInfo)
                // Upload the SAME comprehensive report as the on-screen / Copy path
                // (buildFullReport) so remote sends carry every structured section —
                // device / app / setup+permissions / memory / display / camera / encoder
                // / motion-wake / screensaver / lovelace / events — plus the FULL on-disk
                // persistent log (~3MB overnight history). One text field so the edge
                // function and diagnostics_reports table need no schema changes.
                put("diagnostics", buildFullReport(diagnosticsText, fullPersistentLog = true))
                put("timestamp", System.currentTimeMillis())

                // Camera streaming configuration
                put("rtsp_enabled", halitePrefs.camera.rtspEnabled)
                put("rtsp_software_encoding", halitePrefs.camera.rtspForceSoftwareEncoding)
                put("rtsp_resolution", "${halitePrefs.camera.rtspResolutionWidth}x${halitePrefs.camera.rtspResolutionHeight}")
                put("rtsp_fps", halitePrefs.camera.rtspFps)
                put("rtsp_bitrate", halitePrefs.camera.rtspBitrate)
                put("encoder_name", encoderCaps?.encoderName ?: "unknown")
                put("encoder_hw", encoderCaps?.isHardwareAccelerated ?: false)

                // Include Lovelace config analysis if available
                val configFile = java.io.File(activity.filesDir,
                    com.dashieapp.Dashie.halite.telemetryDelegates.TelemetryDelegate.LOVELACE_CONFIG_FILE)
                if (configFile.exists()) {
                    try {
                        val configJson = configFile.readText()
                        put("lovelace_config", JSONObject(configJson))
                        put("lovelace_analysis", com.dashieapp.Dashie.halite.telemetryDelegates.TelemetryDelegate
                            .analyzeLovelaceConfig(configJson))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to include Lovelace config: ${e.message}")
                    }
                }
            }

            // Use BuildConfig values for endpoint and auth key
            val endpoint = BuildConfig.SUPABASE_URL + "/functions/v1" + DIAGNOSTICS_ENDPOINT_PATH
            val anonKey = BuildConfig.SUPABASE_ANON_KEY

            Log.i(TAG, "Sending diagnostics to ${BuildConfig.ENVIRONMENT}")

            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $anonKey")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true

            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }

            val responseCode = connection.responseCode
            Log.i(TAG, "Diagnostics response: $responseCode")

            responseCode in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send diagnostics: ${e.message}")
            false
        }
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        scope.cancel()
    }
}

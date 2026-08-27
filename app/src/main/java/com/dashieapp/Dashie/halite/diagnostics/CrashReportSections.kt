package com.dashieapp.Dashie.halite.diagnostics

import android.content.Context
import android.os.Build
import com.dashieapp.Dashie.BuildConfig
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared section builders for crash reports.
 *
 * Both the OOM-kill report ([CrashHandler.saveOomKillReport]) and the
 * abnormal-exit report ([AbnormalExitReporter]) build from these, so the two
 * report types always carry identical detail and never drift apart when a
 * new section is added.
 *
 * Each function returns a complete `─── SECTION ───` block ending with a
 * trailing blank line — callers just `append()` the result.
 */
object CrashReportSections {

    private const val PREFS_NAME = "dashie_lite_prefs"
    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun deviceInfo(): String = buildString {
        appendLine("─── DEVICE INFO ───")
        appendLine("Manufacturer: ${Build.MANUFACTURER}")
        appendLine("Model: ${Build.MODEL}")
        appendLine("Device: ${Build.DEVICE}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("WebView: ${webViewVersionSafe()}")
        appendLine()
    }

    fun appInfo(): String = buildString {
        appendLine("─── APP INFO ───")
        appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Build Type: ${BuildConfig.BUILD_TYPE}")
        appendLine("Environment: ${BuildConfig.ENVIRONMENT}")
        appendLine()
    }

    fun rtspConfig(context: Context): String = buildString {
        appendLine("─── RTSP CONFIG ───")
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            appendLine("Enabled: ${prefs.getBoolean("rtsp_enabled", false)}")
            appendLine("Resolution: ${prefs.getString("rtsp_resolution", "720p") ?: "720p"}")
            appendLine("FPS: ${prefs.getInt("rtsp_fps", 15)}")
            appendLine("Bitrate: ${prefs.getInt("rtsp_bitrate", 2_000_000) / 1_000_000}Mbps")
            appendLine("Software Encoding: ${prefs.getBoolean("rtsp_force_software_encoding", false)}")
        } catch (e: Exception) {
            appendLine("(error reading: ${e.message})")
        }
        appendLine()
    }

    /** Dashboard snapshot persisted by the telemetry script — survives an OOM kill. */
    fun dashboardContent(context: Context): String = buildString {
        appendLine("─── DASHBOARD CONTENT (last snapshot) ───")
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val snapshotTime = prefs.getLong("snapshot_time", 0L)
            if (snapshotTime > 0) {
                val ageMin = (System.currentTimeMillis() - snapshotTime) / 60_000
                appendLine("Snapshot taken: ${stamp().format(Date(snapshotTime))} (${ageMin}min ago)")
                appendLine("DOM Nodes: ${prefs.getInt("snapshot_dom_nodes", 0)}")
                appendLine("Entities: ${prefs.getInt("snapshot_entity_count", 0)}")
                appendLine("Views/Tabs: ${prefs.getInt("snapshot_view_count", 0)}")

                val jsHeap = prefs.getInt("snapshot_js_heap_mb", 0)
                val jsHeapLimit = prefs.getInt("snapshot_js_heap_limit_mb", 0)
                if (jsHeap > 0) appendLine("JS Heap: ${jsHeap}MB / ${jsHeapLimit}MB")

                val totalCards = prefs.getInt("snapshot_total_cards", 0)
                val cameraCount = prefs.getInt("snapshot_camera_count", 0)
                val customCardCount = prefs.getInt("snapshot_custom_card_count", 0)
                appendLine("Total Cards: $totalCards (cameras: $cameraCount, custom: $customCardCount)")

                val cardsJson = prefs.getString("snapshot_cards_json", null)
                if (!cardsJson.isNullOrBlank() && cardsJson != "{}") {
                    val cards = JSONObject(cardsJson)
                    val parts = mutableListOf<String>()
                    cards.keys().forEach { key -> parts.add("$key(${cards.optInt(key)})") }
                    appendLine("  Standard: ${parts.joinToString(", ")}")
                }

                val customJson = prefs.getString("snapshot_custom_cards_json", null)
                if (!customJson.isNullOrBlank() && customJson != "{}") {
                    val custom = JSONObject(customJson)
                    val parts = mutableListOf<String>()
                    custom.keys().forEach { key -> parts.add("$key(${custom.optInt(key)})") }
                    appendLine("  Custom: ${parts.joinToString(", ")}")
                }

                val mediaJson = prefs.getString("snapshot_media_json", null)
                if (!mediaJson.isNullOrBlank() && mediaJson != "{}") {
                    val media = JSONObject(mediaJson)
                    appendLine("Media Elements: iframes=${media.optInt("iframes", 0)}, " +
                        "videos=${media.optInt("videos", 0)}, canvases=${media.optInt("canvases", 0)}, " +
                        "images=${media.optInt("images", 0)}")
                }
            } else {
                appendLine("(no dashboard snapshot recorded)")
            }
        } catch (e: Exception) {
            appendLine("(error reading: ${e.message})")
        }
        appendLine()
    }

    /** Lovelace config analysis persisted by the telemetry script — survives an OOM kill. */
    fun lovelaceConfig(context: Context): String = buildString {
        appendLine("─── LOVELACE CONFIG (dashboard analysis) ───")
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val configTime = prefs.getLong("lovelace_config_time", 0L)
            val configFile = File(
                context.filesDir,
                com.dashieapp.Dashie.halite.telemetryDelegates.TelemetryDelegate.LOVELACE_CONFIG_FILE
            )
            if (configTime > 0 && configFile.exists()) {
                val ageMin = (System.currentTimeMillis() - configTime) / 60_000
                appendLine("Captured: ${stamp().format(Date(configTime))} (${ageMin}min ago)")
                appendLine("Config size: ${configFile.length() / 1024}KB")
                appendLine(
                    com.dashieapp.Dashie.halite.telemetryDelegates.TelemetryDelegate
                        .analyzeLovelaceConfig(configFile.readText())
                )
            } else {
                appendLine("(no Lovelace config captured)")
            }
        } catch (e: Exception) {
            appendLine("(error reading: ${e.message})")
        }
        appendLine()
    }

    fun currentMemory(): String = buildString {
        appendLine("─── CURRENT MEMORY (after restart) ───")
        val runtime = Runtime.getRuntime()
        appendLine("Max: ${runtime.maxMemory() / (1024 * 1024)}MB")
        appendLine("Total: ${runtime.totalMemory() / (1024 * 1024)}MB")
        appendLine("Free: ${runtime.freeMemory() / (1024 * 1024)}MB")
        appendLine()
    }

    /** Hourly memory trend persisted to SharedPreferences — survives an OOM kill. */
    fun memoryTrend(pssTrend: String, ramTrend: String, heapTrend: String): String = buildString {
        appendLine("─── MEMORY TREND ───")
        if (pssTrend.isNotBlank()) {
            try {
                appendLine(MemoryMonitor.formatTrendForCrashReport(pssTrend, ramTrend, heapTrend))
            } catch (e: Exception) {
                appendLine("(error formatting: ${e.message})")
            }
        } else {
            appendLine("(no trend data recorded - uptime may be < 15 minutes)")
        }
        appendLine()
    }

    /** Convenience: read the hourly trend strings from prefs, then format. */
    fun memoryTrend(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return memoryTrend(
            prefs.getString("hourly_pss_trend", "") ?: "",
            prefs.getString("hourly_ram_trend", "") ?: "",
            prefs.getString("hourly_heap_trend", "") ?: ""
        )
    }

    /** System /proc/meminfo snapshot persisted every 30s — survives an OOM kill. */
    fun systemMemory(context: Context, lastPssMb: Int): String = buildString {
        appendLine("─── SYSTEM MEMORY (at time of kill) ───")
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val sysMeminfo = prefs.getString("last_system_meminfo", "") ?: ""
            if (sysMeminfo.isNotBlank()) {
                appendLine(sysMeminfo)
                val usedMatch = Regex("Used=(\\d+)MB").find(sysMeminfo)
                if (usedMatch != null) {
                    val usedMb = usedMatch.groupValues[1].toIntOrNull() ?: 0
                    val otherMb = usedMb - lastPssMb
                    if (otherMb > 0) appendLine("Our App: ${lastPssMb}MB  Other processes: ${otherMb}MB")
                }
            } else {
                appendLine("(no system memory data recorded)")
            }
        } catch (e: Exception) {
            appendLine("(error: ${e.message})")
        }
        appendLine()
    }

    /** Debug.MemoryInfo breakdown persisted every 30s — survives an OOM kill. */
    fun appMemoryBreakdown(context: Context): String = buildString {
        appendLine("─── APP MEMORY BREAKDOWN (at time of kill) ───")
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val appBreakdown = prefs.getString("last_app_memory_breakdown", "") ?: ""
            appendLine(if (appBreakdown.isNotBlank()) appBreakdown else "(no breakdown data recorded)")
        } catch (e: Exception) {
            appendLine("(error: ${e.message})")
        }
        appendLine()
    }

    fun topProcesses(context: Context): String = buildString {
        appendLine("─── TOP PROCESSES (after restart) ───")
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val procs = am?.runningAppProcesses
            if (procs != null && procs.isNotEmpty()) {
                for (proc in procs.sortedBy { it.importance }.take(15)) {
                    val importanceName = when {
                        proc.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FG"
                        proc.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FG_SVC"
                        proc.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
                        proc.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
                        proc.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
                        else -> "BG"
                    }
                    appendLine("  [$importanceName] ${proc.processName} (pid=${proc.pid})")
                }
                appendLine("Note: Only shows own processes (Android API restriction)")
            } else {
                appendLine("(unable to read process list)")
            }
        } catch (e: Exception) {
            appendLine("(error reading: ${e.message})")
        }
        appendLine()
    }

    private fun webViewVersionSafe(): String = try {
        CrashHandler.getWebViewVersionInfo()
    } catch (e: Throwable) {
        "(lookup failed: ${e.javaClass.simpleName})"
    }
}

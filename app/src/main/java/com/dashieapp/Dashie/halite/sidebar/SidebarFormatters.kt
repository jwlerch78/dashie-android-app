package com.dashieapp.Dashie.halite.sidebar

import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences
import com.dashieapp.Dashie.util.TimeFormatter

/**
 * Utility functions for formatting sidebar settings values for display.
 * These are pure functions with no side effects.
 */
object SidebarFormatters {

    /**
     * Format timeout value for display.
     * Delegates to TimeFormatter for consistency.
     */
    fun formatTimeout(seconds: Int): String = TimeFormatter.formatTimeout(seconds)

    /**
     * Format timeout with abbreviations (30 sec, 1 min, etc.)
     */
    fun formatTimeoutAbbreviated(seconds: Int): String {
        return when {
            seconds == 0 -> "Off"
            seconds < 60 -> "$seconds sec"
            seconds == 60 -> "1 min"
            seconds < 3600 -> "${seconds / 60} min"
            seconds == 3600 -> "1 hr"
            else -> "${seconds / 3600} hr"
        }
    }

    /**
     * Format API password for display (masked or "Not set")
     */
    fun formatApiPassword(password: String): String {
        return if (password.isEmpty()) "Not set" else "••••••••"
    }

    /**
     * Format API status for sidebar display.
     * Shows: "Disabled", "Enabled (2323, pwd=none)", or "Enabled (2323, pwd=****)"
     */
    fun formatApiStatus(enabled: Boolean, port: Int, password: String): String {
        if (!enabled) return "Disabled"
        val pwdStatus = if (password.isEmpty()) "none" else "••••"
        return "Enabled ($port, pwd=$pwdStatus)"
    }

    /**
     * Shorten URL for display (remove http:// prefix)
     */
    fun shortenUrl(url: String): String {
        return url.removePrefix("http://").removePrefix("https://")
    }

    /**
     * Format motion wake mode for display.
     * For camera mode, includes the threshold percentage with decimal.
     */
    fun formatMotionWakeMode(mode: String, cameraThresholdTenths: Int, faceDistance: String = "far"): String {
        return when (mode) {
            "disabled" -> "Touch Only"
            "brightness" -> "Brightness Sensor"
            "camera" -> "Motion (Camera)"
            "face" -> "Face Detection (Camera)"
            else -> "Touch Only"
        }
    }

    /**
     * Format response handling mode for display.
     */
    fun formatResponseHandling(mode: String): String {
        return when (mode) {
            HalitePreferences.RESPONSE_HANDLING_READ_AND_DISPLAY -> "Read & Display"
            HalitePreferences.RESPONSE_HANDLING_READ_ONLY -> "Read Only"
            HalitePreferences.RESPONSE_HANDLING_DISPLAY_ONLY -> "Display Only"
            HalitePreferences.RESPONSE_HANDLING_NONE -> "None"
            else -> "Read & Display"
        }
    }

    /**
     * Format threshold value in tenths to display string (e.g., 50 -> "5.0%", 55 -> "5.5%")
     */
    fun formatThreshold(tenths: Int): String {
        val value = tenths / 10.0
        return "%.1f%%".format(value)
    }

    /**
     * Format screensaver display text showing timeout and mode.
     * Uses abbreviated format like "1 min (Dim)"
     */
    fun formatScreensaverDisplay(timeout: Int, mode: String): String {
        val timeoutStr = formatTimeoutAbbreviated(timeout)
        val modeStr = when (mode) {
            "black" -> "Black Overlay"
            "off" -> "Screen Off"
            "url" -> "URL"
            "photos" -> "Photos"
            "app" -> "App"
            "ha_page" -> "HA Page"
            else -> "Dim"
        }
        return if (timeout == 0) "Off" else "$timeoutStr ($modeStr)"
    }
}

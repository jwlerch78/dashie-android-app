package com.dashieapp.Dashie.api.handlers

import android.util.Log
import com.dashieapp.Dashie.api.DashieApiCallbacks
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Handles settings, dark mode, screensaver config, and kiosk lock/PIN API endpoints.
 *
 * Endpoints: setStringSetting, setBooleanSetting, setDarkMode, getDarkMode,
 * setScreensaverMode, setPhotoSource, setHaMediaFolder,
 * lockKiosk, unlockKiosk, setPin, clearPin, getPinStatus
 */
class SettingsApiHandler(
    private val callbacks: DashieApiCallbacks
) {
    companion object {
        private const val TAG = "SettingsApiHandler"

        // Named once, printed back on rejection. A caller that guessed a key gets told what the
        // real ones are instead of having to read this file — the whole reason the old silent
        // 200 cost people time.
        private const val STRING_KEYS = "screenBrightness, startUrl, motionWakeMode, textScaling"
        private const val BOOLEAN_KEYS = "hideSidebar, hideHeader/hideTabs, keepScreenOn, " +
                "startOnBoot, autoBrightness, rtspEnabled, rtspSoftwareEncoding"
    }

    // ============================================
    // General Settings
    // ============================================

    /**
     * GET /?cmd=setStringSetting&key=[key]&value=[val]
     *
     * Supported keys: screenBrightness, startUrl, motionWakeMode, textScaling
     */
    fun handleSetStringSetting(params: Map<String, String>): NanoHTTPD.Response {
        val key = params["key"] ?: return errorResponse("Missing 'key' parameter")
        val value = params["value"] ?: return errorResponse("Missing 'value' parameter")

        when (key) {
            "screenBrightness" -> {
                val brightness = value.toIntOrNull() ?: return errorResponse("Invalid brightness value")
                callbacks.setBrightness(brightness)
                return successResponse("Brightness set to $brightness")
            }
            "startUrl" -> {
                callbacks.setStartUrl(value)
                return successResponse("Start URL set to $value")
            }
            "motionWakeMode" -> {
                val success = callbacks.setMotionWakeMode(value)
                return if (success) {
                    successResponse("Motion wake mode set to $value")
                } else {
                    errorResponse("Failed to set motion wake mode")
                }
            }
            "textScaling" -> {
                val zoom = value.toIntOrNull() ?: return errorResponse("Invalid zoom value")
                if (zoom < 50 || zoom > 200) {
                    return errorResponse("Zoom must be between 50 and 200")
                }
                val success = callbacks.setTextScaling(zoom)
                return if (success) {
                    successResponse("Zoom set to $zoom%")
                } else {
                    errorResponse("Failed to set zoom")
                }
            }
            else -> {
                // 🔴 An UNSUPPORTED key is a client error, and it must answer like one. This
                // branch returned HTTP 200 with the words "not supported" in the message body
                // until 2026-08-25 — so `curl` reported success, every scripted caller that
                // checked the status code believed the write had landed, and the setting had
                // silently not moved. Found twice by asking the device — once in a survey of
                // constrained hardware, and again by someone trying to unmute a microphone
                // that stayed muted while the API reported OK. Standing rule 2 — a drop nobody is told about.
                Log.w(TAG, "DROP: unsupported setStringSetting key: $key")
                return errorResponse("Setting '$key' not supported. Supported: $STRING_KEYS")
            }
        }
    }

    /**
     * GET /?cmd=setBooleanSetting&key=[key]&value=[val]
     *
     * Supported keys: hideSidebar, hideHeader, keepScreenOn, startOnBoot,
     * autoBrightness, rtspEnabled, rtspSoftwareEncoding
     */
    fun handleSetBooleanSetting(params: Map<String, String>): NanoHTTPD.Response {
        val key = params["key"] ?: return errorResponse("Missing 'key' parameter")
        val value = params["value"]?.lowercase() in listOf("true", "1", "yes")

        Log.d(TAG, "setBooleanSetting: $key = $value")

        val success = when (key) {
            "hideSidebar" -> callbacks.setHideSidebar(value)
            "hideHeader", "hideTabs" -> callbacks.setHideHeader(value)
            "keepScreenOn" -> callbacks.setKeepScreenOn(value)
            "startOnBoot" -> callbacks.setStartOnBoot(value)
            "autoBrightness" -> callbacks.setAutoBrightness(value)
            "rtspEnabled" -> callbacks.setRtspEnabled(value)
            "rtspSoftwareEncoding" -> callbacks.setRtspSoftwareEncoding(value)
            else -> {
                // See handleSetStringSetting's else-branch: 200-on-rejection, same fix.
                Log.w(TAG, "DROP: unsupported setBooleanSetting key: $key")
                return errorResponse("Setting '$key' not supported. Supported: $BOOLEAN_KEYS")
            }
        }

        return if (success) {
            successResponse("Setting '$key' set to $value")
        } else {
            errorResponse("Failed to set '$key'")
        }
    }

    // ============================================
    // Dark Mode
    // ============================================

    /**
     * GET /?cmd=setDarkMode&value=[true|false]
     *
     * Requires WRITE_SECURE_SETTINGS permission.
     */
    fun handleSetDarkMode(params: Map<String, String>): NanoHTTPD.Response {
        val valueStr = params["value"] ?: return errorResponse("Missing 'value' parameter")
        val enabled = valueStr.lowercase() in listOf("true", "1", "yes")

        val success = callbacks.setDarkMode(enabled)
        return if (success) {
            Log.i(TAG, "Dark mode set to $enabled via API")
            successResponse("Dark mode ${if (enabled) "enabled" else "disabled"}")
        } else {
            Log.w(TAG, "Failed to set dark mode - permission may be missing")
            errorResponse("Failed to set dark mode - WRITE_SECURE_SETTINGS permission required")
        }
    }

    /**
     * GET /?cmd=getDarkMode
     */
    fun handleGetDarkMode(): NanoHTTPD.Response {
        val isDark = callbacks.isDarkMode()
        val json = JSONObject().apply {
            put("status", "OK")
            put("isDarkMode", isDark)
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json.toString())
    }

    // ============================================
    // Screen Off Method
    // ============================================

    /**
     * GET /?cmd=setScreenOffMethod&method=[overlay|hardware]
     *
     * Changes the default screen-off method used when `screenOff` is called
     * without an explicit `method` parameter, or when the device triggers
     * screen off via sleep schedule / inactivity timer.
     *
     * - overlay: Black overlay (app stays active, fast wake, WebSocket stays alive)
     * - hardware: Real hardware screen off via Device Admin lockNow() (lowest power)
     */
    fun handleSetScreenOffMethod(params: Map<String, String>): NanoHTTPD.Response {
        val method = params["method"]?.lowercase()
            ?: return errorResponse("Missing 'method' parameter")

        if (method !in listOf("overlay", "hardware")) {
            return errorResponse("Invalid method: $method. Valid: overlay, hardware")
        }

        val success = callbacks.setScreenOffMethod(method)
        return if (success) {
            Log.i(TAG, "Screen off method set to $method via API")
            val json = JSONObject().apply {
                put("status", "OK")
                put("message", "Screen off method set to $method")
                put("screenOffMethod", method)
            }
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json.toString())
        } else {
            errorResponse("Failed to set screen off method")
        }
    }

    /**
     * GET /?cmd=getScreenOffMethod
     *
     * Returns the current default screen-off method.
     */
    fun handleGetScreenOffMethod(): NanoHTTPD.Response {
        val method = callbacks.getScreenOffMethod()
        val json = JSONObject().apply {
            put("status", "OK")
            put("screenOffMethod", method)
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json.toString())
    }

    // ============================================
    // Screensaver Settings
    // ============================================

    /**
     * GET /?cmd=setScreensaverMode&mode=[dim|black|off|url|photos|weather|ha_page|app]
     */
    fun handleSetScreensaverMode(params: Map<String, String>): NanoHTTPD.Response {
        val mode = params["mode"] ?: return errorResponse("Missing 'mode' parameter")

        val validModes = listOf("dim", "black", "off", "url", "photos", "weather", "ha_page", "app")
        if (mode.lowercase() !in validModes) {
            return errorResponse("Invalid mode: $mode. Valid modes: ${validModes.joinToString(", ")}")
        }

        val success = callbacks.setScreensaverMode(mode.lowercase())
        return if (success) {
            Log.i(TAG, "Screensaver mode set to $mode via API")
            val json = JSONObject().apply {
                put("status", "OK")
                put("message", "Screensaver mode set to $mode")
                put("screensaverMode", mode.lowercase())
            }
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json.toString())
        } else {
            errorResponse("Failed to set screensaver mode")
        }
    }

    /**
     * GET /?cmd=setPhotoSource&source=[ha_media|local|google|immich]
     */
    fun handleSetPhotoSource(params: Map<String, String>): NanoHTTPD.Response {
        val source = params["source"] ?: return errorResponse("Missing 'source' parameter")

        val validSources = listOf("ha_media", "local", "google", "immich")
        if (source.lowercase() !in validSources) {
            return errorResponse("Invalid source: $source. Valid sources: ${validSources.joinToString(", ")}")
        }

        val success = callbacks.setPhotoSource(source.lowercase())
        return if (success) {
            Log.i(TAG, "Photo source set to $source via API")
            val json = JSONObject().apply {
                put("status", "OK")
                put("message", "Photo source set to $source")
                put("photoSource", source.lowercase())
            }
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json.toString())
        } else {
            errorResponse("Failed to set photo source")
        }
    }

    /**
     * GET /?cmd=setHaMediaFolder&folder=[folder_name]
     */
    fun handleSetHaMediaFolder(params: Map<String, String>): NanoHTTPD.Response {
        val folder = params["folder"] ?: return errorResponse("Missing 'folder' parameter")

        val success = callbacks.setHaMediaFolder(folder)
        return if (success) {
            Log.i(TAG, "HA Media folder set to $folder via API")
            val json = JSONObject().apply {
                put("status", "OK")
                put("message", "HA Media folder set to $folder")
                put("haMediaFolder", folder)
            }
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json.toString())
        } else {
            errorResponse("Failed to set HA Media folder")
        }
    }

    // ============================================
    // Kiosk Lock / PIN
    // ============================================

    /**
     * GET /?cmd=lockKiosk
     *
     * Lock kiosk mode - sets lockSettings=true.
     * API lock bypasses PIN requirement (intentional for remote management).
     */
    fun handleLockKiosk(): NanoHTTPD.Response {
        callbacks.lockKiosk()
        Log.i(TAG, "Kiosk locked via API (lockSettings=true)")
        return successResponse("Kiosk locked")
    }

    /**
     * GET /?cmd=unlockKiosk
     *
     * Unlock kiosk mode - sets lockSettings=false.
     * API unlock does NOT require PIN (API password is authentication).
     */
    @Suppress("UNUSED_PARAMETER")
    fun handleUnlockKiosk(params: Map<String, String>): NanoHTTPD.Response {
        callbacks.unlockKiosk()
        Log.i(TAG, "Kiosk unlocked via API (lockSettings=false)")
        return successResponse("Kiosk unlocked")
    }

    /**
     * GET /?cmd=setPin&pin=[4-digit PIN]
     */
    fun handleSetPin(params: Map<String, String>): NanoHTTPD.Response {
        val pin = params["pin"] ?: return errorResponse("Missing 'pin' parameter")

        if (!pin.matches(Regex("^\\d{4}$"))) {
            return errorResponse("PIN must be exactly 4 digits")
        }

        val success = callbacks.setPin(pin)
        return if (success) {
            Log.i(TAG, "PIN set via API")
            successResponse("PIN set successfully")
        } else {
            errorResponse("Failed to set PIN")
        }
    }

    /**
     * GET /?cmd=clearPin
     */
    fun handleClearPin(): NanoHTTPD.Response {
        callbacks.clearPin()
        Log.i(TAG, "PIN cleared via API")
        return successResponse("PIN cleared")
    }

    /**
     * GET /?cmd=getPinStatus
     */
    fun handleGetPinStatus(): NanoHTTPD.Response {
        val hasPinSet = callbacks.hasPinSet()
        val json = JSONObject().apply {
            put("status", "OK")
            put("hasPinSet", hasPinSet)
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json.toString())
    }
}

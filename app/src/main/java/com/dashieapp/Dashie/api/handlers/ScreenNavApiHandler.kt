package com.dashieapp.Dashie.api.handlers

import android.util.Log
import com.dashieapp.Dashie.api.DashieApiCallbacks
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Handles screen control, navigation, app/WebView management, and camera API endpoints.
 *
 * Endpoints: screenOn, screenOff, startScreensaver, stopScreensaver,
 * loadUrl, loadStartUrl, restartApp, rebootDevice, toForeground,
 * startApplication, setOverlayMessage, triggerMotion, clearCache,
 * clearWebstorage, refreshWebView, getCamshot
 */
class ScreenNavApiHandler(
    private val callbacks: DashieApiCallbacks
) {
    companion object {
        private const val TAG = "ScreenNavHandler"
    }

    // ============================================
    // Screen Control
    // ============================================

    fun handleScreenOn(): NanoHTTPD.Response {
        callbacks.onScreenOn()
        return successResponse("Screen turned on")
    }

    fun handleScreenOff(params: Map<String, String>): NanoHTTPD.Response {
        val method = params["method"]?.lowercase()  // "overlay", "hardware", or null
        if (method != null && method !in listOf("overlay", "hardware")) {
            return errorResponse("Invalid method: $method. Valid: overlay, hardware")
        }
        callbacks.onScreenOff(method)
        val suffix = if (method != null) " (method=$method)" else ""
        return successResponse("Screen turned off$suffix")
    }

    fun handleStartScreensaver(): NanoHTTPD.Response {
        callbacks.startScreensaver()
        return successResponse("Screensaver started")
    }

    fun handleStopScreensaver(): NanoHTTPD.Response {
        callbacks.stopScreensaver()
        return successResponse("Screensaver stopped")
    }

    // ============================================
    // Navigation
    // ============================================

    fun handleLoadUrl(params: Map<String, String>): NanoHTTPD.Response {
        val url = params["url"] ?: return errorResponse("Missing 'url' parameter")
        callbacks.onLoadUrl(url)
        return successResponse("Loading URL: $url")
    }

    fun handleLoadStartUrl(): NanoHTTPD.Response {
        callbacks.onLoadStartUrl()
        return successResponse("Loading start URL")
    }

    fun handleRestartApp(): NanoHTTPD.Response {
        callbacks.onRestartApp()
        return successResponse("Restarting app")
    }

    /**
     * GET /?cmd=rebootDevice
     * Requires REBOOT permission - only works on rooted devices or if installed as system app.
     */
    fun handleRebootDevice(): NanoHTTPD.Response {
        return try {
            callbacks.onRebootDevice()
            successResponse("Rebooting device")
        } catch (e: SecurityException) {
            errorResponse("Reboot failed: requires root or system app (${e.message})")
        } catch (e: Exception) {
            errorResponse("Reboot failed: ${e.message}")
        }
    }

    fun handleToForeground(): NanoHTTPD.Response {
        callbacks.onToForeground()
        return successResponse("Brought to foreground")
    }

    // ============================================
    // App / WebView Management
    // ============================================

    fun handleStartApplication(params: Map<String, String>): NanoHTTPD.Response {
        val packageName = params["package"] ?: return errorResponse("Missing 'package' parameter")
        val success = callbacks.startApplication(packageName)
        return if (success) {
            successResponse("Started application: $packageName")
        } else {
            errorResponse("Failed to start application: $packageName")
        }
    }

    fun handleSetOverlayMessage(params: Map<String, String>): NanoHTTPD.Response {
        val text = params["text"] ?: return errorResponse("Missing 'text' parameter")
        val duration = params["duration"]?.toIntOrNull() ?: 3000
        callbacks.showOverlayMessage(text, duration)
        return successResponse("Showing message: $text")
    }

    fun handleTriggerMotion(): NanoHTTPD.Response {
        callbacks.triggerMotion()
        return successResponse("Motion triggered")
    }

    fun handleClearCache(): NanoHTTPD.Response {
        callbacks.clearCache()
        return successResponse("Cache cleared")
    }

    fun handleClearWebstorage(): NanoHTTPD.Response {
        callbacks.clearWebstorage()
        return successResponse("Web storage cleared")
    }

    /**
     * GET /?cmd=refreshWebView
     *
     * Trigger WebView memory release by navigating to about:blank for 500ms
     * then back to the current page.
     */
    fun handleRefreshWebView(): NanoHTTPD.Response {
        Log.i(TAG, "API: refreshWebView requested")

        val latch = CountDownLatch(1)
        var success = false

        callbacks.refreshWebView { result ->
            success = result
            latch.countDown()
        }

        val completed = latch.await(5, TimeUnit.SECONDS)

        return if (!completed) {
            Log.w(TAG, "refreshWebView timed out")
            errorResponse("WebView refresh timed out")
        } else if (success) {
            successResponse("WebView refresh triggered (navigate away 500ms then back)")
        } else {
            errorResponse("WebView refresh throttled (max once per 60s)")
        }
    }

    // ============================================
    // Camera
    // ============================================

    /**
     * GET /?cmd=getScreenshot
     *
     * Capture a JPEG screenshot of what's currently displayed on screen.
     * Returns the raw JPEG bytes with image/jpeg content type.
     */
    fun handleGetScreenshot(): NanoHTTPD.Response {
        Log.d(TAG, "API: getScreenshot requested")

        val startMs = System.currentTimeMillis()
        val latch = CountDownLatch(1)
        var imageBytes: ByteArray? = null

        callbacks.getScreenshot { bytes ->
            imageBytes = bytes
            latch.countDown()
        }

        val success = latch.await(5, TimeUnit.SECONDS)
        val elapsedMs = System.currentTimeMillis() - startMs

        return if (success && imageBytes != null) {
            Log.i(TAG, "Returning screenshot: ${imageBytes!!.size} bytes (${elapsedMs}ms)")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "image/jpeg",
                java.io.ByteArrayInputStream(imageBytes),
                imageBytes!!.size.toLong()
            )
        } else {
            // success=false → latch timed out (capture took >5s); success=true with null
            // bytes → capture ran but failed (no WebView size, draw/compress exception).
            val reason = if (!success) "timed out after ${elapsedMs}ms" else "returned null after ${elapsedMs}ms"
            Log.e(TAG, "Screenshot capture failed: $reason")
            errorResponse("Screenshot capture failed")
        }
    }

    /**
     * GET /?cmd=getCamshot
     *
     * Capture a JPEG image from the camera.
     * Returns the raw JPEG bytes with image/jpeg content type.
     */
    fun handleGetCamshot(): NanoHTTPD.Response {
        Log.d(TAG, "API: getCamshot requested")

        val latch = CountDownLatch(1)
        var imageBytes: ByteArray? = null

        callbacks.getCamshot { bytes ->
            imageBytes = bytes
            latch.countDown()
        }

        val success = latch.await(10, TimeUnit.SECONDS)

        return if (success && imageBytes != null) {
            Log.i(TAG, "Returning camera image: ${imageBytes!!.size} bytes")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "image/jpeg",
                java.io.ByteArrayInputStream(imageBytes),
                imageBytes!!.size.toLong()
            )
        } else {
            Log.e(TAG, "Camera capture failed or timed out")
            errorResponse("Camera capture failed")
        }
    }
}

package com.dashieapp.Dashie.api.handlers

import android.util.Log
import com.dashieapp.Dashie.api.DashieApiCallbacks
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Handles RTSP camera streaming API endpoints for the Dashie REST API.
 *
 * Endpoints: startRtspStream, stopRtspStream, getRtspStatus,
 * setRtspResolution, setRtspFps, setRtspBitrate, getRtspConfig
 */
class RtspApiHandler(
    private val callbacks: DashieApiCallbacks
) {
    companion object {
        private const val TAG = "RtspApiHandler"
    }

    /**
     * GET /?cmd=startRtspStream
     *
     * Start the RTSP camera stream. Returns the stream URL on success.
     */
    fun handleStartRtspStream(): NanoHTTPD.Response {
        val success = callbacks.startRtspStream()
        return if (success) {
            val url = callbacks.getRtspStreamUrl()
            Log.i(TAG, "RTSP stream started via API: $url")
            val json = JSONObject().apply {
                put("status", "OK")
                put("message", "RTSP stream started")
                put("streamUrl", url)
            }
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json.toString())
        } else {
            errorResponse("Failed to start RTSP stream")
        }
    }

    /**
     * GET /?cmd=stopRtspStream
     */
    fun handleStopRtspStream(): NanoHTTPD.Response {
        callbacks.stopRtspStream()
        Log.i(TAG, "RTSP stream stopped via API")
        return successResponse("RTSP stream stopped")
    }

    /**
     * GET /?cmd=getRtspStatus
     *
     * Get current RTSP stream status (running state, URL, client count).
     */
    fun handleGetRtspStatus(): NanoHTTPD.Response {
        val isRunning = callbacks.isRtspStreamRunning()
        val url = callbacks.getRtspStreamUrl()
        val clientCount = callbacks.getRtspClientCount()
        val hasFailed = callbacks.hasRtspFailed()
        val failureReason = callbacks.getRtspFailureReason()

        val json = JSONObject().apply {
            put("status", "OK")
            put("isStreaming", isRunning)
            put("streamUrl", if (isRunning) url else "")
            put("clientCount", clientCount)
            put("hasFailed", hasFailed)
            if (hasFailed && failureReason != null) {
                put("failureReason", failureReason)
            }
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json.toString())
    }

    /**
     * GET /?cmd=setRtspResolution&width=[w]&height=[h]
     *
     * Changes take effect on next stream start.
     */
    fun handleSetRtspResolution(params: Map<String, String>): NanoHTTPD.Response {
        val width = params["width"]?.toIntOrNull()
            ?: return errorResponse("Missing or invalid 'width' parameter")
        val height = params["height"]?.toIntOrNull()
            ?: return errorResponse("Missing or invalid 'height' parameter")

        if (width < 320 || width > 3840 || height < 240 || height > 2160) {
            return errorResponse("Invalid resolution: ${width}x${height}. Valid range: 320-3840 x 240-2160")
        }

        callbacks.setRtspResolution(width, height)
        Log.i(TAG, "RTSP resolution set via API: ${width}x${height}")

        val json = JSONObject().apply {
            put("status", "OK")
            put("message", "Resolution set to ${width}x${height}. Restart stream to apply.")
            put("width", width)
            put("height", height)
            put("requiresRestart", true)
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json.toString())
    }

    /**
     * GET /?cmd=setRtspFps&fps=[fps]
     *
     * Valid range: 10-30 FPS. Changes take effect on next stream start.
     */
    fun handleSetRtspFps(params: Map<String, String>): NanoHTTPD.Response {
        val fps = params["fps"]?.toIntOrNull()
            ?: return errorResponse("Missing or invalid 'fps' parameter")

        if (fps < 10 || fps > 30) {
            return errorResponse("Invalid FPS: $fps. Valid range: 10-30")
        }

        callbacks.setRtspFps(fps)
        Log.i(TAG, "RTSP FPS set via API: $fps")

        val json = JSONObject().apply {
            put("status", "OK")
            put("message", "FPS set to $fps. Restart stream to apply.")
            put("fps", fps)
            put("requiresRestart", true)
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json.toString())
    }

    /**
     * GET /?cmd=setRtspBitrate&bitrate=[bps]
     *
     * Valid range: 500000 (500 Kbps) to 10000000 (10 Mbps). Changes take effect on next stream start.
     */
    fun handleSetRtspBitrate(params: Map<String, String>): NanoHTTPD.Response {
        val bitrate = params["bitrate"]?.toIntOrNull()
            ?: return errorResponse("Missing or invalid 'bitrate' parameter")

        if (bitrate < 500_000 || bitrate > 10_000_000) {
            return errorResponse("Invalid bitrate: $bitrate. Valid range: 500000-10000000 bps")
        }

        callbacks.setRtspBitrate(bitrate)
        Log.i(TAG, "RTSP bitrate set via API: $bitrate bps")

        val json = JSONObject().apply {
            put("status", "OK")
            put("message", "Bitrate set to ${bitrate / 1_000_000.0} Mbps. Restart stream to apply.")
            put("bitrate", bitrate)
            put("requiresRestart", true)
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json.toString())
    }

    /**
     * GET /?cmd=getRtspConfig
     *
     * Get current RTSP configuration.
     */
    fun handleGetRtspConfig(): NanoHTTPD.Response {
        val config = callbacks.getRtspConfig()

        val json = JSONObject().apply {
            put("status", "OK")
            put("width", config.width)
            put("height", config.height)
            put("fps", config.fps)
            put("bitrate", config.bitrate)
            put("port", config.port)
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json.toString())
    }
}

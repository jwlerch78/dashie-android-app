package com.dashieapp.Dashie.halite.rtsp

import android.app.Activity
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Manages multiple RTSP player overlays for camera grid display.
 *
 * This manager handles the lifecycle of native RTSP video players that overlay
 * on top of the WebView. Each camera card in the HA dashboard gets its own
 * overlay positioned at the card's screen coordinates.
 *
 * Key responsibilities:
 * - Create/destroy player overlays
 * - Position overlays to align with WebView cards
 * - Manage playback state across multiple streams
 * - Handle cleanup on navigation/page changes
 *
 * Usage from JS bridge:
 * ```kotlin
 * // Start a stream
 * rtspPlayerManager.startStream("cam1", "rtsp://...", x, y, width, height)
 *
 * // Update position on scroll
 * rtspPlayerManager.updateStreamPosition("cam1", newX, newY, width, height)
 *
 * // Stop when card removed
 * rtspPlayerManager.stopStream("cam1")
 * ```
 */
class RtspPlayerManager(
    private val activity: Activity,
    private val rootLayout: FrameLayout
) {
    companion object {
        private const val TAG = "RtspPlayerManager"
        private const val MAX_CONCURRENT_STREAMS = 9 // Support up to 3x3 grid
    }

    // Active player overlays keyed by stream ID
    private val players = mutableMapOf<String, RtspPlayerOverlay>()

    // Callback for events (optional, for debugging/telemetry)
    var onStreamStarted: ((String) -> Unit)? = null
    var onStreamStopped: ((String) -> Unit)? = null
    var onStreamError: ((String, String) -> Unit)? = null

    /**
     * Start playing an RTSP stream at the specified position.
     *
     * @param id Unique identifier for this stream (used by JS to reference it)
     * @param rtspUrl The RTSP URL to play
     * @param x Left position in pixels (from WebView coordinate space)
     * @param y Top position in pixels (from WebView coordinate space)
     * @param width Width in pixels
     * @param height Height in pixels
     */
    fun startStream(id: String, rtspUrl: String, x: Int, y: Int, width: Int, height: Int) {
        Log.i(TAG, "startStream: id=$id, url=$rtspUrl, pos=($x,$y), size=${width}x$height")

        // Check if we already have this stream
        players[id]?.let { existing ->
            Log.d(TAG, "Stream $id already exists, updating position")
            activity.runOnUiThread {
                existing.updatePosition(x, y, width, height)
            }
            return
        }

        // Check max streams limit
        if (players.size >= MAX_CONCURRENT_STREAMS) {
            Log.w(TAG, "Max concurrent streams ($MAX_CONCURRENT_STREAMS) reached, cannot add $id")
            onStreamError?.invoke(id, "Maximum streams reached")
            return
        }

        // Create overlay and add to view hierarchy on UI thread
        // (Views must be created on the UI thread in Android)
        activity.runOnUiThread {
            val overlay = RtspPlayerOverlay(activity, id).apply {
                layoutParams = FrameLayout.LayoutParams(width, height).apply {
                    leftMargin = x
                    topMargin = y
                }

                // Set up callbacks
                onConnected = {
                    Log.i(TAG, "Stream $id connected")
                    onStreamStarted?.invoke(id)
                }
                onDisconnected = {
                    Log.i(TAG, "Stream $id disconnected")
                }
                onError = { error ->
                    Log.e(TAG, "Stream $id error: $error")
                    onStreamError?.invoke(id, error)
                }
            }

            rootLayout.addView(overlay)
            players[id] = overlay

            // Start playback
            overlay.startPlayback(rtspUrl)

            Log.d(TAG, "Added stream $id, total streams: ${players.size}")
        }
    }

    /**
     * Stop a specific stream and remove its overlay.
     *
     * @param id The stream ID to stop
     */
    fun stopStream(id: String) {
        Log.i(TAG, "stopStream: id=$id")

        val overlay = players.remove(id)
        if (overlay == null) {
            Log.d(TAG, "Stream $id not found")
            return
        }

        activity.runOnUiThread {
            overlay.stopPlayback()
            rootLayout.removeView(overlay)
            overlay.release()

            onStreamStopped?.invoke(id)
            Log.d(TAG, "Removed stream $id, remaining streams: ${players.size}")
        }
    }

    /**
     * Update the position of a stream overlay.
     * Call this when the WebView scrolls or the card resizes.
     *
     * @param id The stream ID to update
     * @param x New left position in pixels
     * @param y New top position in pixels
     * @param width New width in pixels
     * @param height New height in pixels
     */
    fun updateStreamPosition(id: String, x: Int, y: Int, width: Int, height: Int) {
        val overlay = players[id]
        if (overlay == null) {
            Log.d(TAG, "updateStreamPosition: stream $id not found")
            return
        }

        activity.runOnUiThread {
            overlay.updatePosition(x, y, width, height)
        }
    }

    /**
     * Stop all active streams.
     * Call this on page navigation or when leaving camera view.
     */
    fun stopAllStreams() {
        Log.i(TAG, "stopAllStreams: stopping ${players.size} streams")

        activity.runOnUiThread {
            players.values.forEach { overlay ->
                overlay.stopPlayback()
                rootLayout.removeView(overlay)
                overlay.release()
            }
            players.clear()
        }
    }

    /**
     * Hide all overlays temporarily (e.g., when sidebar opens).
     */
    fun hideAllOverlays() {
        activity.runOnUiThread {
            players.values.forEach { it.visibility = android.view.View.GONE }
        }
    }

    /**
     * Show all overlays (e.g., when sidebar closes).
     */
    fun showAllOverlays() {
        activity.runOnUiThread {
            players.values.forEach { it.visibility = android.view.View.VISIBLE }
        }
    }

    /**
     * Check if a stream is currently playing.
     *
     * @param id The stream ID to check
     * @return true if the stream exists and is playing
     */
    fun isStreamPlaying(id: String): Boolean {
        return players[id]?.isCurrentlyPlaying() == true
    }

    /**
     * Get the number of active streams.
     */
    fun getActiveStreamCount(): Int = players.size

    /**
     * Get list of active stream IDs.
     */
    fun getActiveStreamIds(): List<String> = players.keys.toList()

    /**
     * Check if native RTSP playback is supported.
     * Always returns true since we have ExoPlayer.
     */
    fun isNativeRtspSupported(): Boolean = true

    /**
     * Release all resources. Call this when the activity is destroyed.
     */
    fun release() {
        Log.i(TAG, "Releasing RtspPlayerManager")
        stopAllStreams()
    }
}

package com.dashieapp.Dashie.halite.rtsp

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

/**
 * Native RTSP video player overlay that sits on top of the WebView.
 *
 * This view displays an RTSP stream using ExoPlayer with hardware-accelerated decoding.
 * It's positioned at specific screen coordinates to align with an HA camera card
 * rendered in the WebView beneath it.
 *
 * Key features:
 * - Hardware video decoding via MediaCodec
 * - Automatic reconnection on connection loss
 * - Minimal latency (100-300ms vs 500ms-2s for WebRTC)
 * - Efficient multi-stream support
 *
 * Usage:
 * 1. Create overlay and add to parent FrameLayout
 * 2. Call startPlayback() with RTSP URL
 * 3. Use updatePosition() to keep aligned with scrolling WebView
 * 4. Call stopPlayback() when done
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RtspPlayerOverlay(
    context: Context,
    private val streamId: String
) : FrameLayout(context) {

    companion object {
        private const val TAG = "RtspPlayerOverlay"

        // Reconnection settings
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 2000L
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var statusOverlay: TextView? = null

    private var rtspUrl: String? = null
    private var isPlaying = false
    private var reconnectAttempts = 0

    // Callback for player events
    var onError: ((String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    init {
        // Set background to black while loading
        setBackgroundColor(Color.BLACK)

        // Create PlayerView
        playerView = PlayerView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            useController = false // No playback controls - just video
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER) // Don't show spinner
            setShutterBackgroundColor(Color.BLACK)
        }
        addView(playerView)

        // Create status overlay for connection state
        statusOverlay = TextView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            setTextColor(Color.WHITE)
            textSize = 12f
            visibility = GONE
        }
        addView(statusOverlay)

        Log.d(TAG, "[$streamId] Created RtspPlayerOverlay")
    }

    /**
     * Start playing an RTSP stream.
     *
     * @param url The RTSP URL (e.g., rtsp://192.168.1.50:8554/front_door)
     */
    fun startPlayback(url: String) {
        if (isPlaying && rtspUrl == url) {
            Log.d(TAG, "[$streamId] Already playing $url")
            return
        }

        // Stop any existing playback
        stopPlayback()

        rtspUrl = url
        reconnectAttempts = 0

        Log.i(TAG, "[$streamId] Starting RTSP playback: $url")
        showStatus("Connecting...")

        try {
            // Create ExoPlayer with hardware decoding
            player = ExoPlayer.Builder(context).build().apply {
                // Set up listener for playback events
                addListener(playerListener)

                // Create RTSP media source
                val rtspSource = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true) // TCP is more reliable than UDP
                    .createMediaSource(MediaItem.fromUri(url))

                setMediaSource(rtspSource)
                prepare()
                playWhenReady = true
            }

            // Attach player to view
            playerView?.player = player

            isPlaying = true

        } catch (e: Exception) {
            Log.e(TAG, "[$streamId] Failed to start playback: ${e.message}", e)
            showStatus("Error: ${e.message}")
            onError?.invoke(e.message ?: "Unknown error")
        }
    }

    /**
     * Stop playback and release resources.
     */
    fun stopPlayback() {
        if (!isPlaying && player == null) return

        Log.d(TAG, "[$streamId] Stopping playback")

        player?.let {
            it.removeListener(playerListener)
            it.stop()
            it.release()
        }
        player = null
        playerView?.player = null

        isPlaying = false
        rtspUrl = null
        reconnectAttempts = 0

        showStatus(null)
    }

    /**
     * Update the position and size of this overlay.
     * Call this when the WebView scrolls or the card resizes.
     *
     * @param x Left position in pixels
     * @param y Top position in pixels
     * @param width Width in pixels
     * @param height Height in pixels
     */
    fun updatePosition(x: Int, y: Int, width: Int, height: Int) {
        val params = layoutParams as? ViewGroup.MarginLayoutParams
        if (params != null) {
            params.leftMargin = x
            params.topMargin = y
            params.width = width
            params.height = height
            layoutParams = params
        }
    }

    /**
     * Check if this overlay is currently playing.
     */
    fun isCurrentlyPlaying(): Boolean = isPlaying

    /**
     * Get the stream ID for this overlay.
     */
    fun getStreamId(): String = streamId

    private fun showStatus(message: String?) {
        statusOverlay?.apply {
            if (message != null) {
                text = message
                visibility = VISIBLE
            } else {
                visibility = GONE
            }
        }
    }

    private fun attemptReconnect() {
        val url = rtspUrl ?: return

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "[$streamId] Max reconnect attempts reached")
            showStatus("Connection failed")
            onError?.invoke("Max reconnection attempts reached")
            return
        }

        reconnectAttempts++
        Log.i(TAG, "[$streamId] Attempting reconnect ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")
        showStatus("Reconnecting ($reconnectAttempts)...")

        // Delay before reconnect
        postDelayed({
            if (isPlaying) {
                player?.let {
                    it.stop()
                    it.prepare()
                    it.playWhenReady = true
                }
            }
        }, RECONNECT_DELAY_MS)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    Log.i(TAG, "[$streamId] Playback ready")
                    showStatus(null) // Hide status when playing
                    reconnectAttempts = 0
                    onConnected?.invoke()
                }
                Player.STATE_BUFFERING -> {
                    Log.d(TAG, "[$streamId] Buffering...")
                    // Don't show status for brief buffering
                }
                Player.STATE_ENDED -> {
                    Log.i(TAG, "[$streamId] Playback ended")
                    onDisconnected?.invoke()
                }
                Player.STATE_IDLE -> {
                    Log.d(TAG, "[$streamId] Player idle")
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "[$streamId] Player error: ${error.message}", error)

            when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                    // Network error - try to reconnect
                    attemptReconnect()
                }
                else -> {
                    showStatus("Error: ${error.message}")
                    onError?.invoke(error.message ?: "Playback error")
                }
            }
        }
    }

    /**
     * Release all resources. Call this when removing the overlay.
     */
    fun release() {
        Log.d(TAG, "[$streamId] Releasing resources")
        stopPlayback()
        removeAllViews()
        playerView = null
        statusOverlay = null
    }
}

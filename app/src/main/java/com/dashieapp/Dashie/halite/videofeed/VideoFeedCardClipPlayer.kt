package com.dashieapp.Dashie.halite.videofeed

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Encapsulates Frigate MP4 clip playback on a VideoFeedCard.
 *
 * Extracted from VideoFeedCard.kt to narrow that file's concerns. The clip
 * player is a separate ExoPlayer instance from the live stream's; this class
 * owns its lifecycle and the loading/error overlays that belong to it.
 *
 * The host card provides:
 *  - context / handler (for animation + auto-dismiss timing)
 *  - the SurfaceView to render into (the card's live stream SurfaceView)
 *  - a way to stop/restart the live stream around clip playback
 *  - current player-mute state (so the clip starts muted/unmuted appropriately)
 *  - an onClipStateChanged relay callback set by the Frigate controller
 */
internal class VideoFeedCardClipPlayer(private val card: VideoFeedCard) {

    companion object {
        private const val TAG = "VideoFeedCardClip"
    }

    private var clipPlayer: ExoPlayer? = null
    private var isInClipPlayback = false
    private var clipLoadingOverlay: TextView? = null
    private var loadingAnimRunnable: Runnable? = null

    /** Whether we are currently playing a clip (vs. live stream). */
    fun isPlayingClip(): Boolean = isInClipPlayback

    /** Access the clip player for external time queries (e.g. FrigatePlaybackController). */
    fun player(): ExoPlayer? = clipPlayer

    fun positionMs(): Long = clipPlayer?.currentPosition ?: 0
    fun durationMs(): Long = clipPlayer?.duration ?: 0

    fun seekTo(positionMs: Long) {
        clipPlayer?.seekTo(positionMs)
    }

    /**
     * Switch from live stream to MP4 clip playback.
     * Stops the live stream, creates a new ExoPlayer for progressive MP4
     * playback, and renders to the existing SurfaceView.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun start(clipUrl: String, seekToMs: Long = 0) {
        // Fully stop and release the live RTSP/MJPEG stream
        card.stopStreamForClip()
        isInClipPlayback = true

        // Release any previous clip player
        clipPlayer?.let { it.stop(); it.release() }
        clipPlayer = null

        val sv = card.cameraSurfaceView
        Log.i(TAG, "start: cameraSurfaceView=$sv, cameraImageView=${card.cameraImageView}")
        if (sv == null) {
            Log.e(TAG, "start: no SurfaceView available, cannot play clip")
            return
        }

        showLoading(true)

        // Extract Bearer token from the URL query param and use as a header instead
        val uri = android.net.Uri.parse(clipUrl)
        val token = uri.getQueryParameter("token") ?: ""
        val cleanUrl = clipUrl.replace("?token=$token", "").replace("&token=$token", "")

        // Build ExoPlayer with auth header. Clips are transcoded to 720p by the
        // HA proxy (frigate_proxy.py) so they're within tablet HW decoder capabilities.
        // Use OkHttpDataSource backed by LocalHostsTrustingHttpClient so HA over
        // local HTTPS (self-signed cert) works — DefaultHttpDataSource uses
        // HttpURLConnection with strict cert validation and would 404 here.
        val okHttpClient = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder().build()
        val dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
        val mediaSourceFactory =
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)

        val player = ExoPlayer.Builder(card.context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        player.setVideoSurfaceView(sv)
        player.volume = if (card.isPlayerMuted()) 0f else 1f

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val name = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "$playbackState"
                }
                Log.i(TAG, "Clip player state: $name")
                when (playbackState) {
                    Player.STATE_BUFFERING -> showLoading(true)
                    Player.STATE_READY -> showLoading(false)
                    Player.STATE_ENDED -> card.onClipStateChanged?.invoke("ended")
                }
                card.onClipStateChanged?.invoke(name)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Clip player error: ${error.message}", error)
                showLoading(false)
                val cause = error.cause
                val isResolutionError = cause?.message?.contains("EXCEEDS_CAPABILITIES") == true ||
                    cause?.message?.contains("NO_EXCEEDS") == true
                if (isResolutionError) {
                    showError(
                        "Camera resolution too high for this device.\n" +
                            "Reduce camera to 1080p in the Tapo app\n" +
                            "(Camera Settings → Video Quality)"
                    )
                } else {
                    showError("Clip playback failed")
                }
                card.onClipStateChanged?.invoke("error")
            }

            override fun onRenderedFirstFrame() {
                Log.i(TAG, "Clip player rendered first frame")
                showLoading(false)
            }
        })

        val mediaItem = MediaItem.fromUri(cleanUrl)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true

        if (seekToMs > 0) {
            player.seekTo(seekToMs)
        }

        clipPlayer = player
        Log.i(TAG, "Started clip playback: $cleanUrl (token=${token.take(10)}...)")
    }

    /** Stop clip playback and return to live stream. */
    fun stop() {
        clipPlayer?.let { player ->
            player.stop()
            player.release()
        }
        clipPlayer = null
        isInClipPlayback = false

        card.startStreamAfterClip()
        Log.i(TAG, "Stopped clip playback, returned to live")
    }

    /**
     * Apply the current mute state to the clip player (if present). Called by
     * the card when the user toggles mute during playback.
     */
    fun applyMute(muted: Boolean) {
        clipPlayer?.volume = if (muted) 0f else 1f
    }

    /** Release without restarting the live stream (used by card.release()). */
    fun release() {
        clipPlayer?.let { it.stop(); it.release() }
        clipPlayer = null
        isInClipPlayback = false
        loadingAnimRunnable?.let { card.mainHandler.removeCallbacks(it) }
        loadingAnimRunnable = null
        clipLoadingOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        clipLoadingOverlay = null
    }

    // ── Overlays ────────────────────────────────────────────────────────

    fun showLoading(show: Boolean) {
        if (show && clipLoadingOverlay == null) {
            val videoContainer = videoContainerOrNull() ?: return
            val ctx = card.context
            val tv = TextView(ctx).apply {
                text = "Loading"
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val pad = VideoFeedStyles.dpToPx(ctx, 10)
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    setColor(0xCC000000.toInt())
                    cornerRadius = VideoFeedStyles.dpToPx(ctx, 8).toFloat()
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
            }
            clipLoadingOverlay = tv
            videoContainer.addView(tv)

            // Animate dots: Loading. → Loading.. → Loading...
            var dotCount = 0
            loadingAnimRunnable = object : Runnable {
                override fun run() {
                    dotCount = (dotCount + 1) % 4
                    tv.text = "Loading" + ".".repeat(dotCount)
                    card.mainHandler.postDelayed(this, 400)
                }
            }
            card.mainHandler.postDelayed(loadingAnimRunnable!!, 400)
        } else if (!show) {
            loadingAnimRunnable?.let { card.mainHandler.removeCallbacks(it) }
            loadingAnimRunnable = null
            clipLoadingOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
            clipLoadingOverlay = null
        }
    }

    fun showError(message: String) {
        showLoading(false)

        val videoContainer = videoContainerOrNull() ?: return
        val ctx = card.context

        val errorView = TextView(ctx).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val pad = VideoFeedStyles.dpToPx(ctx, 16)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(0xDD000000.toInt())
                cornerRadius = VideoFeedStyles.dpToPx(ctx, 8).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        videoContainer.addView(errorView)

        // Auto-dismiss after 8 seconds
        card.mainHandler.postDelayed({
            (errorView.parent as? ViewGroup)?.removeView(errorView)
        }, 8000)
    }

    /**
     * The video container lives at mainCol[0] → topRow[0] → videoContainer.
     * This layout is only populated in PLAYBACK mode; returns null otherwise
     * so callers can no-op rather than crash.
     */
    private fun videoContainerOrNull(): FrameLayout? = card.playbackVideoContainer()
}

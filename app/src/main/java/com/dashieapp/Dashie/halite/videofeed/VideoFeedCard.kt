package com.dashieapp.Dashie.halite.videofeed

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.media3.ui.AspectRatioFrameLayout
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.dashieapp.Dashie.R
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * Single PiP camera card view — a native FrameLayout containing:
 * - ImageView for live MJPEG camera stream (parsed natively via OkHttp)
 * - Camera name label bar with pin/unpin toggle
 * - Close/maximize buttons
 *
 * Supports 2 modes: NORMAL, MAXIMIZED.
 */
class VideoFeedCard(
    context: Context,
    private val overlayContainer: FrameLayout,
    val data: VideoFeedData,
    private val size: String = "medium",
    private val displayMethod: String = "sidebar",
    private val streamUrl: String,
    // Full URL to the HA Dashie snapshot endpoint for this camera, or empty if
    // snapshots aren't available (pre-integration HA, or a source without an
    // entity_id). When present, the card paints a disk-cached or HA-fetched
    // JPEG as a placeholder until the first live frame renders.
    private val snapshotUrl: String = "",
    private val targetFps: Int = 10,
    private val authTokenProvider: () -> String,
    private val onTokenRefreshNeeded: (() -> Boolean)? = null,
    private val onMaximize: () -> Unit,
    private val onNormalize: () -> Unit,
    private val onDismiss: () -> Unit,
    private val onPinChanged: ((Boolean) -> Unit)? = null,
    initialShowOverlayControls: Boolean = true
) : FrameLayout(context) {

    companion object {
        private const val TAG = "VideoFeedCard"
        private const val RECONNECT_DELAY_MS = 3000L
        // Upper bound on a single MJPEG frame read by marker-scanning (the
        // no-Content-Length path). Guards against a malformed stream growing
        // the buffer without end. 8 MB comfortably exceeds any real JPEG frame.
        private const val MAX_MJPEG_FRAME_BYTES = 8 * 1024 * 1024
        private const val RTSP_RECONNECT_DELAY_MS = 2000L
        private const val MAX_RTSP_RECONNECTS = 5
        private const val FREEZE_THRESHOLD_MS = 5000L
        private const val MAX_FREEZE_RECOVERIES = 2
        // RTSP state-flap watchdog — see the field comment on bufferingTimestamps
        private const val FLAP_WINDOW_MS = 5_000L
        private const val FLAP_THRESHOLD = 5
        // Bumped from 2 → 5 after observing cameras that take more than 2
        // fresh-session attempts before the RTSP handshake stabilizes.
        private const val MAX_FLAP_RECOVERIES = 5
        // State-flap restart toggle. After diagnostic tests confirmed the
        // stream can stay broken indefinitely without intervention (frame
        // rendering stalls while player thinks it's healthy), left enabled
        // as a belt-and-suspenders alongside the frame-stall watchdog below.
        private const val WATCHDOG_RESTART_ENABLED = true

        private val httpClient = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)  // No read timeout for continuous stream
            .build()
    }

    enum class Mode { NORMAL, MAXIMIZED, PLAYBACK }
    private enum class RenderMode { RTSP, MJPEG }

    /** Public getter for coordinator/tests; still private setter. */
    var mode: Mode = Mode.NORMAL
        private set
    private var renderMode: RenderMode = if (data.rtspUrl.isNotBlank() && data.rtspUrl != "null") RenderMode.RTSP else RenderMode.MJPEG
    // Internal visibility: VideoFeedCardClipPlayer reads these to render the clip into the SurfaceView.
    internal var cameraImageView: ImageView? = null     // MJPEG mode
    internal var cameraSurfaceView: SurfaceView? = null  // RTSP: Maximized / Playback modes
    /** RTSP output for Mode.NORMAL — TextureView avoids SurfaceView's punch-through
     *  issue inside the scaled container. Exactly one of cameraSurfaceView/cameraTextureView
     *  is non-null when renderMode == RTSP. */
    internal var cameraTextureView: android.view.TextureView? = null
    private var aspectRatioFrame: AspectRatioFrameLayout? = null  // center-crop wrapper
    private var zoomWrapper: FrameLayout? = null  // pinch-to-zoom wrapper
    // Placeholder ImageView painted above the stream surface. Disk-cached if
    // we've seen this camera before (instant), otherwise a fresh snapshot
    // fetched from the HA Dashie integration. Faded out on first live frame.
    private var thumbnailImageView: ImageView? = null
    // Expand/compress ImageView in the overlay controls. Reference kept so the
    // tapToMaximize setter can toggle visibility without rebuilding the view.
    // Nulled out by each build{Normal,Maximized,Playback}View rebuild.
    private var expandButton: ImageView? = null
    // Small indeterminate spinner shown next to the camera name in the label
    // bar while the live stream is still establishing (thumbnail is up).
    // Hidden in onFirstLiveFrame. Nulled on each view rebuild.
    private var loadingSpinner: android.widget.ProgressBar? = null
    // True once we've painted the first live frame (RTSP onRenderedFirstFrame
    // or MJPEG first bitmap decode) — prevents a late async snapshot fetch
    // from replacing an already-streaming view.
    private var firstLiveFrameRendered: Boolean = false
    // RTSP frozen-first-frame watchdog: on some reconnects, ExoPlayer reports
    // onRenderedFirstFrame (the codec-config frame) but position never
    // advances. The watchdog checks a couple of seconds later and forces a
    // re-prepare if the stream is stuck. Bounded retries so we don't thrash.
    private var frozenFrameWatchdog: Runnable? = null
    private var frozenFrameRecoveryAttempts: Int = 0
    private var positionAtFirstFrame: Long = 0L

    // RTSP state-flap watchdog: some cameras (notably pedroSG94-sourced Dashie
    // tablets) open with unstable handshake output — ExoPlayer cycles through
    // BUFFERING/READY many times in a few seconds while the stream "plays"
    // but the visible frame never updates. The user's fix is to close and
    // reopen the feed, which gives a fresh ExoPlayer + RTSP session. Detect
    // this pattern by counting BUFFERING entries in a sliding window; when
    // the threshold trips, release + recreate the player.
    private val bufferingTimestamps = ArrayDeque<Long>()
    private var flapRecoveryAttempts: Int = 0
    // Set true after we've logged the "budget exhausted" message once, so the
    // log isn't spammed on every subsequent BUFFERING. Reset on view rebuild.
    private var flapBudgetExhaustedLogged: Boolean = false
    private var exoPlayer: ExoPlayer? = null
    private var rtspReconnectAttempts = 0
    private var labelText: TextView? = null
    private var pinIcon: ImageView? = null
    private var playbackIcon: ImageView? = null

    // Frigate playback callback — only wired when data.isFrigateCamera is true
    var onPlaybackRequested: (() -> Unit)? = null

    // Scale container: content rendered at base (medium) size, scale transform fills actual card
    private var scaleContainer: FrameLayout? = null

    // D-pad chrome for playback (zone focus rings + fullscreen). View refs are
    // captured in buildPlaybackView.
    private val playbackChrome = VideoFeedCardPlaybackChrome(context)
    private val baseWidthPx = VideoFeedStyles.cardWidthPx(context, "medium")
    private val baseHeightPx = VideoFeedStyles.cardHeightPx(context, "medium")

    var isPinned: Boolean = data.isPinned
        private set

    // Touch interaction
    private enum class TouchMode { NONE, DRAGGING, RESIZING, DRAG_TO_UNPIN, DRAG_UP_TO_FLOAT }
    var isDraggable: Boolean = false
    var isResizable: Boolean = true
    /** Callback for the restore (compress) button in playback mode — returns card to floating/maximized. */
    var onRestoreFromPlayback: (() -> Unit)? = null

    /** Overrides the close (X) button's onDismiss — used by Frigate playback controller
     *  so it can clean up playback state before fully dismissing. */
    var onDismissOverride: (() -> Unit)? = null

    /**
     * When true, tapping the card body (not buttons) maximizes the feed AND
     * the expand button in the overlay controls is visible.
     *
     * Reactive: the setter updates [expandButton] visibility live so callers
     * that flip this flag AFTER the view was built in init{} (which is the
     * common case — CardPlacement.DrawerFocal / Floating set it via
     * VideoFeedCoordinator.applyInteractivity from outside) still see the
     * button appear. Before this setter existed, the button was only ever
     * added/skipped at construction time and later flag flips had no visible
     * effect on the overlay button.
     */
    var tapToMaximize: Boolean = false
        set(value) {
            val changed = field != value
            field = value
            if (changed) refreshExpandButtonVisibility()
        }
    /** When true, horizontal drag detaches the card from the sidebar panel */
    var canDragToUnpin: Boolean = false
    /** Called when a drag-to-unpin gesture completes (card dragged past threshold) */
    var onDragToUnpin: (() -> Unit)? = null
    /** When true, vertical drag up detaches the card from the strip to floating */
    var canDragUpToFloat: Boolean = false
    /** Called when a drag-up-to-float gesture completes */
    var onDragUpToFloat: (() -> Unit)? = null
    /** Called when drag-up gesture starts (so caller can create a drag ghost) */
    var onDragUpStarted: (() -> Unit)? = null
    /** Visual proxy in overlayContainer that follows the finger during drag-up.
     *  Set by the overlay manager in onDragUpStarted; moved via translation here. */
    var dragGhost: View? = null
    /** Screen position where the ghost landed when drag completed (set before ghost removal). */
    var dragReleaseScreenX: Int = 0; private set
    var dragReleaseScreenY: Int = 0; private set
    /** Called when card is tapped in strip mode (no maximize, just select) */
    var onStripTap: (() -> Unit)? = null
    /** When false, hides overlay buttons (close, maximize, resize) */
    var showOverlayControls: Boolean = initialShowOverlayControls

    /** Tag used to find the label bar from outside the card. */
    private val LABEL_BAR_TAG = "videofeed_label_bar"

    /** When true, the bottom label bar is rendered at half the default height.
     *  Used by screensaver narrow-portrait mode to keep the label visually
     *  proportional to the card (default 36dp label looks oversized in
     *  narrow-portrait cards). Setter walks the view tree and adjusts the
     *  existing label bar + the container padding that reserves space for it. */
    var compactLabelBar: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyCompactLabelBar(this, value)
        }

    private fun applyCompactLabelBar(root: android.view.View, compact: Boolean) {
        val s = VideoFeedStyles
        val targetDp = if (compact) s.LABEL_HEIGHT_DP / 2 else s.LABEL_HEIGHT_DP
        val targetPx = s.dpToPx(context, targetDp)
        val textSp = if (compact) 10f else 13f
        val playIconPx = s.dpToPx(context, if (compact) 14 else 28)
        val pinIconPx = s.dpToPx(context, if (compact) 12 else 20)
        walkViews(root) { v ->
            when (v.tag) {
                LABEL_BAR_TAG -> {
                    v.layoutParams = v.layoutParams?.apply { height = targetPx } ?: v.layoutParams
                    v.minimumHeight = targetPx
                }
                "videofeed_camera_container" -> {
                    (v as? LinearLayout)?.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, targetPx)
                }
                "videofeed_label_text" -> {
                    (v as? android.widget.TextView)?.setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_SP, textSp)
                }
                "videofeed_play_icon" -> {
                    v.layoutParams = v.layoutParams?.apply {
                        width = playIconPx; height = playIconPx
                    } ?: v.layoutParams
                }
                "videofeed_pin_icon" -> {
                    v.layoutParams = v.layoutParams?.apply {
                        width = pinIconPx; height = pinIconPx
                    } ?: v.layoutParams
                }
            }
        }
    }

    private fun walkViews(v: android.view.View, action: (android.view.View) -> Unit) {
        action(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walkViews(v.getChildAt(i), action)
        }
    }
    private var touchMode = TouchMode.NONE
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartLeft = 0
    private var touchStartTop = 0
    private var touchStartWidth = 0
    private var touchStartedInResizeZone = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val resizeZonePx = VideoFeedStyles.dpToPx(context, VideoFeedStyles.RESIZE_HANDLE_DP + 8)
    private val dragUnpinThresholdPx = VideoFeedStyles.dpToPx(context, 80)

    // Pinch-to-zoom: View property transforms for SurfaceView, Matrix for MJPEG ImageView
    private val zoomMatrix = android.graphics.Matrix()
    private val savedMatrix = android.graphics.Matrix()
    private var isZooming = false
    private var zoomScale = 1f
    private var zoomTranslateX = 0f
    private var zoomTranslateY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isZooming = true
            if (renderMode == RenderMode.RTSP) {
                if (zoomWrapper == null) return false
            } else {
                val img = cameraImageView ?: return false
                if (img.scaleType != ImageView.ScaleType.MATRIX) {
                    zoomMatrix.set(img.imageMatrix)
                    img.scaleType = ImageView.ScaleType.MATRIX
                }
                savedMatrix.set(zoomMatrix)
            }
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val sf = detector.scaleFactor
            zoomScale *= sf
            if (zoomScale < 1f) { zoomScale = 1f; resetZoom(); return true }
            if (zoomScale > 6f) { zoomScale = 6f; return true }

            if (renderMode == RenderMode.RTSP) {
                // SurfaceView: use View property transforms on the zoom wrapper
                val wrapper = zoomWrapper ?: return true
                zoomTranslateX += detector.focusX - lastFocusX
                zoomTranslateY += detector.focusY - lastFocusY
                wrapper.scaleX = zoomScale
                wrapper.scaleY = zoomScale
                wrapper.translationX = zoomTranslateX
                wrapper.translationY = zoomTranslateY
            } else {
                // MJPEG: Matrix transforms on ImageView
                val targetView: View = cameraImageView ?: return true
                val viewLoc = IntArray(2); targetView.getLocationOnScreen(viewLoc)
                val cardLoc = IntArray(2); this@VideoFeedCard.getLocationOnScreen(cardLoc)
                val fx = detector.focusX + (cardLoc[0] - viewLoc[0])
                val fy = detector.focusY + (cardLoc[1] - viewLoc[1])
                zoomMatrix.postScale(sf, sf, fx, fy)
                zoomMatrix.postTranslate(detector.focusX - lastFocusX, detector.focusY - lastFocusY)
                cameraImageView?.imageMatrix = zoomMatrix
            }
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (zoomScale < 1.05f) resetZoom()
        }
    })

    // Saved video dimensions for restoring center-crop after zoom reset
    private var lastVideoWidth = 0
    private var lastVideoHeight = 0

    private fun resetZoom() {
        zoomScale = 1f
        zoomTranslateX = 0f
        zoomTranslateY = 0f
        isZooming = false
        if (renderMode == RenderMode.RTSP) {
            zoomWrapper?.let { w ->
                w.scaleX = 1f; w.scaleY = 1f
                w.translationX = 0f; w.translationY = 0f
            }
        } else {
            // Restore the mode-appropriate fit: strip/normal cards fill via
            // CENTER_CROP; maximized/playback show the whole frame via FIT.
            cameraImageView?.scaleType =
                if (mode == Mode.NORMAL) ImageView.ScaleType.CENTER_CROP
                else ImageView.ScaleType.FIT_CENTER
        }
        zoomMatrix.reset()
    }

    // Track parent clip state so we can restore after drag
    private data class ClipState(val vg: ViewGroup, val clipChildren: Boolean, val clipToPadding: Boolean)
    private var savedClipStates: List<ClipState>? = null
    private var savedElevation = 0f

    /**
     * Disable clipChildren and clipToPadding on ALL ancestors (up to the DecorView)
     * so this card is visible above the strip bounds during drag-up-to-float.
     */
    private fun disableParentClipping() {
        val states = mutableListOf<ClipState>()
        var v: android.view.ViewParent? = parent
        while (v != null) {
            if (v is ViewGroup) {
                states.add(ClipState(v, v.clipChildren, v.clipToPadding))
                v.clipChildren = false
                v.clipToPadding = false
            }
            v = v.parent
        }
        savedClipStates = states
        savedElevation = elevation
        elevation = 100f
        // Also ensure our own wrapper doesn't clip
        (parent as? ViewGroup)?.clipChildren = false
        (parent as? ViewGroup)?.clipToPadding = false
    }

    private fun restoreParentClipping() {
        savedClipStates?.forEach { state ->
            state.vg.clipChildren = state.clipChildren
            state.vg.clipToPadding = state.clipToPadding
        }
        savedClipStates = null
        elevation = savedElevation
    }

    /** Remove the drag ghost from its parent and clear the reference. */
    private fun removeDragGhost() {
        dragGhost?.let { ghost ->
            (ghost.parent as? ViewGroup)?.removeView(ghost)
        }
        dragGhost = null
    }

    // Offline overlay — shown initially when camera is known-unavailable, hidden on first frame
    private var offlineOverlay: TextView? = null
    private var showingOffline = !data.isAvailable

    // MJPEG stream
    private val executor = Executors.newSingleThreadExecutor()
    /** Main-thread Handler. Exposed `internal` so same-package helpers (e.g.
     *  VideoFeedCardClipPlayer) can post UI work. */
    internal val mainHandler = Handler(Looper.getMainLooper())
    private val streaming = AtomicBoolean(false)
    private val pendingFrame = AtomicBoolean(false)

    // Layout params for the overlay container
    val cardLayoutParams: FrameLayout.LayoutParams
        get() {
            val s = VideoFeedStyles
            return when (mode) {
                Mode.NORMAL -> FrameLayout.LayoutParams(
                    s.cardWidthPx(context, size),
                    s.cardHeightPx(context, size)
                ).apply {
                    gravity = Gravity.NO_GRAVITY
                    setMargins(s.dpToPx(context, s.MARGIN_DP), s.dpToPx(context, s.MARGIN_DP),
                        s.dpToPx(context, s.MARGIN_DP), s.dpToPx(context, s.MARGIN_DP))
                }
                Mode.MAXIMIZED -> FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                Mode.PLAYBACK -> FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
        }

    init {
        buildNormalView()
        startStream()
    }

    // ── Mode Transitions ─────────────────────────────────────────────

    fun setMaximized() {
        mode = Mode.MAXIMIZED
        stopStream()
        rebuildView()
        startStream()
        // Only set FrameLayout.LayoutParams when parent is a FrameLayout.
        // If card is in a LinearLayout (scroll container), the OverlayManager
        // handles container migration before/after this call.
        if (parent !is LinearLayout) {
            layoutParams = cardLayoutParams
        }
    }

    fun setNormal() {
        mode = Mode.NORMAL
        stopStream()
        rebuildView()
        startStream()
        if (parent !is LinearLayout) {
            layoutParams = cardLayoutParams
        }
    }

    /** Restart the live stream (e.g., after reparenting the view). */
    fun restartStream() {
        stopStream()
        startStream()
    }

    fun release() {
        stopStream()
        releaseExoPlayer()
        clipPlayback.release()
        // Drop any queued main-thread posts (fallbackToMjpeg, reconnects, etc.)
        // so they can't fire startMjpegStream() after we've shut the executor down.
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    private var liveEdgeChaserRunnable: Runnable? = null
    /** Highest ExoPlayer position (ms) we've observed advancing — the
     *  frame-stall watchdog's progress signal. Position advancing means
     *  samples are flowing; a stuck position while READY means a silent stall.
     *  We deliberately do NOT use onSurfaceTextureUpdated here: ExoPlayer's
     *  setVideoTextureView() installs its own SurfaceTextureListener and
     *  clobbers any app-set listener, so that callback never fires once
     *  playback attaches. Watching position instead is the same reliable
     *  signal the frozen-frame watchdog uses. */
    private var lastProgressPositionMs = 0L
    /** Wall-clock time of the last observed position advance. */
    private var lastProgressAdvanceTimeMs = 0L
    private var freezeRecoveryCount = 0

    private var rtspStreamStartTimeMs = 0L

    private var isChasingLiveEdge = false

    private fun startLiveEdgeChaser() {
        stopLiveEdgeChaser()
        rtspStreamStartTimeMs = System.currentTimeMillis()
        // Seed the progress watchdog from the start of this session: position 0,
        // clock = now. The first advance bumps lastProgressAdvanceTimeMs; if
        // position never advances within FREEZE_THRESHOLD_MS the watchdog trips.
        lastProgressPositionMs = 0L
        lastProgressAdvanceTimeMs = System.currentTimeMillis()
        isChasingLiveEdge = false
        // Don't reset freezeRecoveryCount here — it must persist across
        // recovery attempts so MAX_FREEZE_RECOVERIES triggers MJPEG fallback
        val runnable = object : Runnable {
            override fun run() {
                val player = exoPlayer ?: return
                val bufferedMs = player.totalBufferedDuration
                val sinceStart = System.currentTimeMillis() - rtspStreamStartTimeMs

                if (sinceStart > 5_000 && player.playbackState == Player.STATE_READY) {
                    if (bufferedMs > 1500 && !isChasingLiveEdge) {
                        // Drifting — speed up to catch the live edge
                        isChasingLiveEdge = true
                        player.setPlaybackSpeed(1.5f)
                        Log.i(TAG, "Live edge chase: speeding up (buffer=${bufferedMs}ms) for ${data.cameraEntityId}")
                    } else if (isChasingLiveEdge && bufferedMs < 800) {
                        // Caught up — return to normal speed
                        isChasingLiveEdge = false
                        player.setPlaybackSpeed(1.0f)
                        Log.i(TAG, "Live edge chase: caught up (buffer=${bufferedMs}ms) for ${data.cameraEntityId}")
                    }

                    // Frame-render stall watchdog (NORMAL mode / TextureView only):
                    // a healthy live stream advances ExoPlayer's position every
                    // tick. If position stops advancing for >FREEZE_THRESHOLD_MS
                    // while the player still reports READY, the RTSP session has
                    // silently stalled (no state change, no error, no BUFFERING
                    // flap — the watchdogs that watch state transitions can't
                    // detect this). Force a fresh player + RTSP session.
                    //
                    // We watch position, NOT onSurfaceTextureUpdated: ExoPlayer's
                    // setVideoTextureView() installs its own SurfaceTextureListener
                    // and clobbers ours, so that callback never fired once playback
                    // attached — which made the old timestamp permanently stale and
                    // tore down every healthy stream at the 5s mark (a teardown
                    // loop). Only applies to TextureView (NORMAL mode); Maximized
                    // and Playback use SurfaceView and skip the cameraTextureView
                    // guard below.
                    val now = System.currentTimeMillis()
                    if (cameraTextureView != null) {
                        val pos = player.currentPosition
                        if (pos > lastProgressPositionMs + 100L) {
                            lastProgressPositionMs = pos
                            lastProgressAdvanceTimeMs = now
                        }
                    }
                    val msSinceAdvance = now - lastProgressAdvanceTimeMs
                    if (msSinceAdvance > FREEZE_THRESHOLD_MS
                        && cameraTextureView != null) {
                        if (freezeRecoveryCount < MAX_FREEZE_RECOVERIES) {
                            freezeRecoveryCount++
                            Log.w(
                                TAG,
                                "Frame-stall watchdog: position stuck ${msSinceAdvance}ms for " +
                                    "${data.cameraEntityId} — forcing full restart " +
                                    "(attempt $freezeRecoveryCount/$MAX_FREEZE_RECOVERIES)",
                            )
                            val uri = lastRtspUri
                            val tv = cameraTextureView
                            if (uri != null && tv != null && tv.isAvailable) {
                                hasRenderedFirstFrame = false
                                // createExoPlayerForTexture → startLiveEdgeChaser()
                                // re-seeds the progress fields AND schedules a fresh
                                // tick, so return here to avoid double-scheduling.
                                createExoPlayerForTexture(tv, uri)
                                return
                            }
                        } else {
                            Log.w(
                                TAG,
                                "Frame-stall watchdog: budget exhausted for ${data.cameraEntityId} " +
                                    "— falling back to MJPEG",
                            )
                            fallbackToMjpeg()
                            return  // don't schedule another tick, stream mode is changing
                        }
                    }
                }
                mainHandler.postDelayed(this, 1000)
            }
        }
        liveEdgeChaserRunnable = runnable
        mainHandler.postDelayed(runnable, 3000)
    }

    private fun stopLiveEdgeChaser() {
        liveEdgeChaserRunnable?.let { mainHandler.removeCallbacks(it) }
        liveEdgeChaserRunnable = null
        if (isChasingLiveEdge) {
            isChasingLiveEdge = false
            exoPlayer?.setPlaybackSpeed(1.0f)
        }
    }

    private fun releaseExoPlayer() {
        stopLiveEdgeChaser()
        exoPlayer?.let { player ->
            player.removeListener(exoPlayerListener)
            player.stop()
            player.release()
        }
        exoPlayer = null
    }

    // ── Frigate Clip Playback ────────────────────────────────────────
    //
    // Delegated to VideoFeedCardClipPlayer for clarity. The public API below
    // preserves the existing surface so FrigatePlaybackController etc. don't
    // need to change.

    private val clipPlayback = VideoFeedCardClipPlayer(this)

    // Callback for clip state changes (buffering, playing, ended)
    var onClipStateChanged: ((state: String) -> Unit)? = null

    /** SurfaceView injected into MJPEG-mode playback so clip ExoPlayer has a render target. */
    private var injectedClipSurfaceView: SurfaceView? = null

    /** Internal hook the clip player uses to tear down the live stream before attach. */
    internal fun stopStreamForClip() {
        stopStream()
        releaseExoPlayer()
        // In MJPEG playback, no SurfaceView exists. Inject one over the ImageView so
        // ExoPlayer can render the clip. Hide the ImageView so the last MJPEG frame
        // doesn't cover the video.
        if (mode == Mode.PLAYBACK && cameraSurfaceView == null) {
            val videoContainer = playbackVideoContainer() ?: return
            cameraImageView?.visibility = View.GONE
            val sv = SurfaceView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            videoContainer.addView(sv)
            cameraSurfaceView = sv
            injectedClipSurfaceView = sv
        }
    }

    /** Internal hook the clip player uses to restart the live stream after stop. */
    internal fun startStreamAfterClip() {
        // RTSP live (including an MJPEG card upgraded to RTSP in playback) keeps
        // its SurfaceView as the permanent live surface — reuse it. Tearing it
        // down here and restoring the MJPEG ImageView is what made a remote-opened
        // feed drop to MJPEG after scrubbing back to live (the upgrade tags its
        // surface as injectedClipSurfaceView). Just restart the live stream.
        if (renderMode == RenderMode.RTSP) {
            injectedClipSurfaceView = null  // it's the live surface now, not a clip surface
            startStream()
            return
        }
        // MJPEG: tear down the per-clip injected SurfaceView and restore the ImageView.
        injectedClipSurfaceView?.let { sv ->
            (sv.parent as? ViewGroup)?.removeView(sv)
            if (cameraSurfaceView === sv) cameraSurfaceView = null
        }
        injectedClipSurfaceView = null
        cameraImageView?.visibility = View.VISIBLE
        startStream()
    }

    /** Locate the video container in PLAYBACK mode: mainColumn[0] → topRow[0] → videoContainer. */
    /** The playback video container — uses the stored ref (robust to layout
     *  order, e.g. the title bar now sitting at index 0). */
    internal fun playbackVideoContainer(): FrameLayout? = playbackChrome.videoContainer

    fun startClipPlayback(clipUrl: String, seekToMs: Long = 0) = clipPlayback.start(clipUrl, seekToMs)

    fun stopClipPlayback() = clipPlayback.stop()

    fun seekClipTo(positionMs: Long) = clipPlayback.seekTo(positionMs)

    fun getClipPositionMs(): Long = clipPlayback.positionMs()

    fun getClipDurationMs(): Long = clipPlayback.durationMs()

    fun getClipPlayer(): ExoPlayer? = clipPlayback.player()


    // ── Touch: Drag + Resize ──────────────────────────────────────────

    /** Check if local touch coords are in the bottom-left resize zone */
    private fun isInResizeZone(localX: Float, localY: Float): Boolean {
        val labelTop = height - VideoFeedStyles.dpToPx(context, VideoFeedStyles.LABEL_HEIGHT_DP)
        return localX < resizeZonePx && localY > (labelTop - resizeZonePx)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (mode == Mode.MAXIMIZED) return false
        // In playback mode, only intercept touches on the video area (top-left quadrant)
        // Let the event strip ScrollView and timeline HorizontalScrollView handle their own touches
        if (mode == Mode.PLAYBACK) {
            // Get the video container bounds — only intercept drag in the video region
            val videoContainer = playbackVideoContainer() ?: return false
            // Map touch to local coords of videoContainer
            val loc = IntArray(2)
            videoContainer.getLocationOnScreen(loc)
            val inVideo = ev.rawX >= loc[0] && ev.rawX <= loc[0] + videoContainer.width &&
                    ev.rawY >= loc[1] && ev.rawY <= loc[1] + videoContainer.height
            if (!inVideo) return false
        }
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = ev.rawX
                touchStartY = ev.rawY
                val lp = layoutParams as? LayoutParams
                if (lp != null) {
                    touchStartLeft = lp.leftMargin
                    touchStartTop = lp.topMargin
                    touchStartWidth = lp.width
                } else if (!canDragToUnpin && !tapToMaximize) {
                    // No FrameLayout.LayoutParams and no gesture that works without it
                    return false
                }
                touchStartedInResizeZone = if (lp != null) isInResizeZone(ev.x, ev.y) else false
                touchMode = TouchMode.NONE
                // Preemptively prevent ScrollView from stealing touch when drag-to-unpin
                // or drag-up-to-float is possible.
                if (canDragToUnpin || canDragUpToFloat) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - touchStartX
                val dy = ev.rawY - touchStartY
                if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                    touchMode = when {
                        touchStartedInResizeZone && isResizable && mode != Mode.PLAYBACK -> TouchMode.RESIZING
                        isDraggable && mode != Mode.PLAYBACK -> TouchMode.DRAGGING
                        canDragToUnpin && abs(dx) > abs(dy) -> TouchMode.DRAG_TO_UNPIN
                        canDragUpToFloat && dy < 0 && abs(dy) > abs(dx) -> TouchMode.DRAG_UP_TO_FLOAT
                        else -> {
                            // Gesture doesn't match — let parent handle it
                            parent?.requestDisallowInterceptTouchEvent(false)
                            return false
                        }
                    }
                    if (touchMode == TouchMode.DRAG_UP_TO_FLOAT) {
                        disableParentClipping()
                        onDragUpStarted?.invoke()
                    }
                    return true  // Intercept: handle in onTouchEvent
                }
            }
        }
        return false
    }

    // Zoom pan tracking: separate start (for tap detection) from last (for pan delta)
    private var zoomGestureStartX = 0f
    private var zoomGestureStartY = 0f
    private var zoomLastX = 0f
    private var zoomLastY = 0f
    private var zoomDidPan = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Feed all touch events to scale detector when zooming is possible
        if (event.pointerCount >= 2 || isZooming) {
            scaleDetector.onTouchEvent(event)
        }
        // While zoomed with two fingers, consume all events (scale detector handles everything)
        if (isZooming && event.pointerCount >= 2) {
            // Mark that a gesture happened (not a simple tap)
            zoomDidPan = true
            return true
        }
        // When zoomed in with one finger: allow pan, reset only on a deliberate single tap
        if (isZooming && zoomScale > 1.05f) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    zoomGestureStartX = event.rawX
                    zoomGestureStartY = event.rawY
                    zoomLastX = event.rawX
                    zoomLastY = event.rawY
                    zoomDidPan = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    // One-finger pan while zoomed
                    val dx = event.rawX - zoomLastX
                    val dy = event.rawY - zoomLastY
                    if (renderMode == RenderMode.RTSP) {
                        zoomTranslateX += dx
                        zoomTranslateY += dy
                        zoomWrapper?.translationX = zoomTranslateX
                        zoomWrapper?.translationY = zoomTranslateY
                    } else {
                        zoomMatrix.postTranslate(dx, dy)
                        cameraImageView?.imageMatrix = zoomMatrix
                    }
                    zoomLastX = event.rawX
                    zoomLastY = event.rawY
                    if (abs(event.rawX - zoomGestureStartX) > touchSlop ||
                        abs(event.rawY - zoomGestureStartY) > touchSlop) {
                        zoomDidPan = true
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    // Only reset zoom on a clean tap — no panning, no pinching
                    if (!zoomDidPan) {
                        val dx = abs(event.rawX - zoomGestureStartX)
                        val dy = abs(event.rawY - zoomGestureStartY)
                        if (dx < touchSlop && dy < touchSlop) {
                            resetZoom()
                        }
                    }
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /**
     * A card should swallow all un-handled touches when it occupies full screen
     * (MAXIMIZED or PLAYBACK mode) to prevent taps falling through to the WebView
     * beneath. Strip thumbnails also use MATCH_PARENT layout but must NOT swallow
     * — they need onStripTap to fire — so we gate on mode, not layout params.
     */
    private fun shouldSwallowUnhandledTouches(): Boolean {
        return mode == Mode.MAXIMIZED || mode == Mode.PLAYBACK
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Full-screen cards swallow all un-handled touches so they don't fall
        // through to the WebView/web UI beneath. Children (close X, playback
        // controls, mute, timeline) receive taps first because they're clickable.
        if (shouldSwallowUnhandledTouches()) return true

        if (mode == Mode.MAXIMIZED) return super.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.rawX
                touchStartY = event.rawY
                val lp = layoutParams as? LayoutParams
                if (lp != null) {
                    touchStartLeft = lp.leftMargin
                    touchStartTop = lp.topMargin
                    touchStartWidth = lp.width
                } else if (!canDragToUnpin && !tapToMaximize) {
                    return super.onTouchEvent(event)
                }
                touchStartedInResizeZone = if (lp != null) isInResizeZone(event.x, event.y) else false
                touchMode = TouchMode.NONE
                // Preemptively claim touch to prevent ScrollView from stealing it
                if (canDragToUnpin || canDragUpToFloat) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                // Consume DOWN if we might drag, resize, tap-to-maximize, drag-to-unpin, drag-up, or strip-tap
                return (isDraggable && mode != Mode.PLAYBACK) || (touchStartedInResizeZone && isResizable && mode != Mode.PLAYBACK) || tapToMaximize || canDragToUnpin || canDragUpToFloat || onStripTap != null
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchMode == TouchMode.NONE) {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        touchMode = when {
                            touchStartedInResizeZone && isResizable && mode != Mode.PLAYBACK -> TouchMode.RESIZING
                            isDraggable && mode != Mode.PLAYBACK -> TouchMode.DRAGGING
                            canDragToUnpin && abs(dx) > abs(dy) -> TouchMode.DRAG_TO_UNPIN
                            canDragUpToFloat && dy < 0 && abs(dy) > abs(dx) -> TouchMode.DRAG_UP_TO_FLOAT
                            else -> {
                                // Gesture doesn't match — let parent handle it
                                parent?.requestDisallowInterceptTouchEvent(false)
                                return true
                            }
                        }
                        if (touchMode == TouchMode.DRAG_UP_TO_FLOAT) {
                            disableParentClipping()
                            onDragUpStarted?.invoke()
                        }
                    }
                }
                when (touchMode) {
                    TouchMode.DRAGGING -> {
                        val lp = layoutParams as? LayoutParams ?: return true
                        lp.gravity = Gravity.NO_GRAVITY
                        lp.leftMargin = (touchStartLeft + (event.rawX - touchStartX)).toInt()
                        lp.topMargin = (touchStartTop + (event.rawY - touchStartY)).toInt()
                        lp.rightMargin = 0
                        lp.bottomMargin = 0
                        layoutParams = lp
                    }
                    TouchMode.RESIZING -> {
                        val dx = event.rawX - touchStartX
                        val dy = event.rawY - touchStartY
                        val s = VideoFeedStyles
                        val screenWidth = context.resources.displayMetrics.widthPixels
                        val minWidth = (screenWidth * s.MIN_SIZE_PERCENT).toInt()
                        val maxWidth = (screenWidth * s.MAX_SIZE_PERCENT).toInt()
                        // Bottom-left: drag left/down = bigger
                        val sizeDelta = max(-dx, dy)
                        val newWidth = (touchStartWidth + sizeDelta).toInt().coerceIn(minWidth, maxWidth)
                        val newHeight = (newWidth / s.ASPECT_RATIO).toInt() + s.dpToPx(context, s.LABEL_HEIGHT_DP)
                        val widthDiff = newWidth - touchStartWidth
                        val lp = layoutParams as? LayoutParams ?: return true
                        lp.gravity = Gravity.NO_GRAVITY
                        lp.width = newWidth
                        lp.height = newHeight
                        lp.leftMargin = touchStartLeft - widthDiff
                        lp.topMargin = touchStartTop
                        lp.rightMargin = 0
                        lp.bottomMargin = 0
                        layoutParams = lp
                    }
                    TouchMode.DRAG_TO_UNPIN -> {
                        translationX = event.rawX - touchStartX
                        translationY = event.rawY - touchStartY
                    }
                    TouchMode.DRAG_UP_TO_FLOAT -> {
                        val dx = event.rawX - touchStartX
                        val dy = event.rawY - touchStartY
                        // Move the ghost (visible, in overlayContainer) instead of this card (clipped in strip)
                        val ghost = dragGhost
                        if (ghost != null) {
                            ghost.translationX = dx
                            ghost.translationY = dy
                        }
                    }
                    TouchMode.NONE -> {}
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                when (touchMode) {
                    TouchMode.DRAG_TO_UNPIN -> {
                        if (translationX < -dragUnpinThresholdPx) {
                            // Dragged far enough left — detach from panel
                            onDragToUnpin?.invoke()
                        } else {
                            // Snap back
                            animate().translationX(0f).translationY(0f).setDuration(150).start()
                        }
                    }
                    TouchMode.DRAG_UP_TO_FLOAT -> {
                        val dy = event.rawY - touchStartY
                        // Save ghost's final screen position before removing it
                        // getLocationOnScreen includes translationX/Y
                        dragGhost?.let { ghost ->
                            val ghostLoc = IntArray(2)
                            ghost.getLocationOnScreen(ghostLoc)
                            dragReleaseScreenX = ghostLoc[0]
                            dragReleaseScreenY = ghostLoc[1]
                        }
                        removeDragGhost()
                        if (dy < -dragUnpinThresholdPx) {
                            // Dragged far enough up — create floating copy
                            restoreParentClipping()
                            onDragUpToFloat?.invoke()
                        } else {
                            // Snap back
                            restoreParentClipping()
                        }
                    }
                    TouchMode.NONE -> {
                        val dx = abs(event.rawX - touchStartX)
                        val dy = abs(event.rawY - touchStartY)
                        if (dx < touchSlop && dy < touchSlop) {
                            // Tap — route to appropriate handler
                            if (onStripTap != null) {
                                onStripTap?.invoke()
                            } else if (tapToMaximize) {
                                onMaximize()
                            }
                        }
                    }
                    else -> {}
                }
                touchMode = TouchMode.NONE
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (touchMode == TouchMode.DRAG_UP_TO_FLOAT) {
                    removeDragGhost()
                    restoreParentClipping()
                } else if (touchMode == TouchMode.DRAG_TO_UNPIN) {
                    animate().translationX(0f).translationY(0f).setDuration(150).start()
                    restoreParentClipping()
                }
                touchMode = TouchMode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ── View Building ────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    /**
     * Show/hide the TV d-pad focus ring. Drawn as a foreground on the scaled
     * content container ([scaleContainer]) so it tracks the card's rounded
     * corners exactly — those corners scale with the card, so a fixed ring on
     * the outer wrapper would not line up. clipToOutline trims the ring to the
     * rounded edge, so a wide stroke reads as a clean inner border.
     */
    fun setDpadHighlight(on: Boolean) {
        val sc = scaleContainer ?: return
        sc.foreground = if (on) {
            android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(
                    VideoFeedStyles.dpToPx(context, 6),
                    VideoFeedStyles.ACCENT_COLOR
                )
                cornerRadius = VideoFeedStyles.dpToPx(
                    context, VideoFeedStyles.CORNER_RADIUS_DP
                ).toFloat()
            }
        } else null
    }

    private fun buildNormalView() {
        removeAllViews()
        scaleContainer = null
        firstLiveFrameRendered = false  // thumbnail should repaint on rebuild
        frozenFrameRecoveryAttempts = 0
        cancelFrozenFrameWatchdog()
        flapRecoveryAttempts = 0
        bufferingTimestamps.clear()
        flapBudgetExhaustedLogged = false
        freezeRecoveryCount = 0  // fresh budget for frame-stall watchdog
        expandButton = null  // will be re-created in addOverlayButtons
        loadingSpinner = null  // will be re-created by createLabelBar
        val s = VideoFeedStyles

        // Scale container at base (medium) dimensions — scaled to fill actual card
        val sc = FrameLayout(context).apply {
            layoutParams = LayoutParams(baseWidthPx, baseHeightPx)
            pivotX = 0f
            pivotY = 0f
            background = s.createRoundedBackground(context)
            clipToOutline = true
        }

        // Main vertical layout — holds only the camera view now (label bar is added
        // directly to sc as a sibling, see below). Bottom padding reserves space
        // for the label bar so the camera view isn't under it visually.
        val labelBarHeightPx = s.dpToPx(context,
            if (compactLabelBar) s.LABEL_HEIGHT_DP / 2 else s.LABEL_HEIGHT_DP)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding(0, 0, 0, labelBarHeightPx)
            clipToPadding = true
            tag = "videofeed_camera_container"
        }

        // Camera view: TextureView for RTSP (ExoPlayer), ImageView for MJPEG.
        //
        // We use TextureView here (NOT SurfaceView) because this card lives inside
        // a scale-transformed parent (sc). SurfaceView's surface is rendered via a
        // separate window layer that doesn't reliably follow parent View transforms
        // on all devices — even with setZOrderOnTop(false), the surface can obscure
        // sibling views like the label bar on Samsung/Fire Tablet hardware.
        // TextureView renders through the Canvas path, follows transforms, and lets
        // sibling views render normally. Minor GPU cost is negligible for
        // strip/drawer/floating card sizes.
        //
        // Maximized and Playback modes still use SurfaceView (no scale transform
        // there — see buildMaximizedView / buildPlaybackView).
        if (renderMode == RenderMode.RTSP) {
            cameraImageView = null
            cameraSurfaceView = null
            // blackWrap: fills the camera slot (above label bar padding) with a
            // solid black background. arFrame shrinks to the video's aspect in
            // FIT mode, leaving the blackWrap's black showing through as the
            // letterbox bars. Keeping the bg on this wrapper (not container) means
            // the label bar still sits over sc's rounded grey background, not black.
            val blackWrap = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
                setBackgroundColor(Color.BLACK)
            }
            val arFrame = AspectRatioFrameLayout(context).apply {
                // gravity=CENTER so the aspect-shrunk view sits centered in
                // blackWrap (equal letterbox bars on both sides) instead of
                // anchoring top-left.
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply { gravity = Gravity.CENTER }
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            val zoomWrap = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val tv = android.view.TextureView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            zoomWrap.addView(tv)
            arFrame.addView(zoomWrap)
            blackWrap.addView(arFrame)
            // Thumbnail placeholder painted ABOVE the TextureView. It fills the
            // whole slot (not just the aspect-sized inner frame) so the viewer
            // sees something reasonable even before arFrame's aspect is set.
            // CENTER_CROP avoids letterbox bars on the placeholder itself.
            val thumb = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            blackWrap.addView(thumb)
            container.addView(blackWrap)
            cameraTextureView = tv
            aspectRatioFrame = arFrame
            zoomWrapper = zoomWrap
            thumbnailImageView = thumb
        } else {
            cameraSurfaceView = null
            cameraTextureView = null
            aspectRatioFrame = null
            zoomWrapper = null
            // Wrap the MJPEG ImageView in a FrameLayout so we can stack a
            // thumbnail placeholder on top until the first bitmap decodes.
            val mjpegWrap = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
                setBackgroundColor(Color.BLACK)
            }
            cameraImageView = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                // CENTER_CROP fills the strip card and crops overflow — no
                // pillarbox bars. (Maximized/playback keep FIT to show the
                // whole frame.) resetZoom restores this same mode-aware type.
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            mjpegWrap.addView(cameraImageView)
            val thumb = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP  // match live view
                visibility = View.GONE
            }
            mjpegWrap.addView(thumb)
            container.addView(mjpegWrap)
            thumbnailImageView = thumb
        }

        sc.addView(container)

        // Offline overlay — centered over the image area, hidden once a frame arrives
        offlineOverlay = buildOfflineOverlay()
        sc.addView(offlineOverlay)

        // Overlay buttons (close, maximize, resize) — hidden in strip/drawer mode
        if (showOverlayControls) {
            addOverlayButtons(sc, isMaximized = false)
        }

        // Label bar — added LAST as a direct child of sc so it draws on top of
        // container/TextureView/etc. (same Z level as the resize grip, which stays
        // visible on Samsung/Fire Tablet while deeper-hierarchy views get obscured
        // by aspect-ratio-triggered redraws). Scales with sc's transform because
        // it's inside sc. Overlays the bottom of the camera view with its
        // semi-transparent black background as the original design intended.
        val labelBar = createLabelBar(data.displayLabel)
        sc.addView(labelBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            labelBarHeightPx,
        ).apply { gravity = Gravity.BOTTOM })
        labelBar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            Log.i(TAG, "🎥 LabelBar layout: ${data.displayLabel} size=${v.width}x${v.height} visibility=${v.visibility} alpha=${v.alpha}")
        }

        // Card itself is transparent; content is in the scale container
        background = null
        clipChildren = false
        clipToPadding = false
        addView(sc)
        scaleContainer = sc

        // Paint the thumbnail placeholder (if we can) so the user sees a
        // reasonable image while the stream establishes. Called from each view
        // builder since the target ImageView is rebuilt on mode change.
        primeThumbnail()
    }

    /**
     * Paint the thumbnail placeholder ImageView. Strategy:
     *   1. Kick off async HA snapshot fetch immediately (preferred — the
     *      freshest available frame, typically ~100ms warm / 500ms cold).
     *   2. Schedule a disk-cache fallback to show at +500ms. If HA has
     *      already responded by then, the scheduled fallback is no-op'd.
     *      This way a fast HA response skips the stale-disk-image flash;
     *      a slow HA response still gives the user something to look at.
     *   3. Disk is written whenever HA responds, so future visits benefit.
     */
    private fun primeThumbnail() {
        val thumb = thumbnailImageView ?: return
        if (mode == Mode.PLAYBACK) return  // Clip playback paints its own content.
        val entityId = data.cameraEntityId
        if (entityId.isBlank()) return

        var haResponded = false

        // Async HA fetch — starts immediately.
        if (snapshotUrl.isNotBlank()) {
            val token = authTokenProvider()
            if (token.isNotBlank()) {
                VideoFeedThumbnailCache.fetchAsync(context, entityId, snapshotUrl, token) { bmp ->
                    mainHandler.post {
                        haResponded = true
                        if (firstLiveFrameRendered) return@post
                        val current = thumbnailImageView ?: return@post
                        current.setImageBitmap(bmp)
                        current.alpha = 1f
                        current.visibility = View.VISIBLE
                    }
                }
            }
        }

        // Disk-cache fallback — scheduled for +500ms. Skipped if HA already
        // responded (haResponded flag) or the stream's own first frame landed.
        mainHandler.postDelayed({
            if (haResponded || firstLiveFrameRendered) return@postDelayed
            val current = thumbnailImageView ?: return@postDelayed
            if (current.visibility == View.VISIBLE) return@postDelayed
            // Load disk cache off the main thread to avoid a stutter on slow
            // tablet filesystems. Post the bitmap back on completion.
            Thread {
                val cached = VideoFeedThumbnailCache.loadFromDisk(context, entityId) ?: return@Thread
                mainHandler.post {
                    if (haResponded || firstLiveFrameRendered) return@post
                    val tv = thumbnailImageView ?: return@post
                    if (tv.visibility == View.VISIBLE) return@post
                    tv.setImageBitmap(cached)
                    tv.alpha = 1f
                    tv.visibility = View.VISIBLE
                }
            }.start()
        }, 500L)
    }

    /**
     * Called from RTSP [onRenderedFirstFrame] and the MJPEG first-bitmap
     * decode. Marks the stream as live and fades the thumbnail placeholder
     * out so the viewer sees the real stream. Idempotent.
     */
    private fun onFirstLiveFrame() {
        if (firstLiveFrameRendered) return
        firstLiveFrameRendered = true
        fadeThumbnailOut()
        loadingSpinner?.visibility = View.GONE
    }

    private fun fadeThumbnailOut() {
        val thumb = thumbnailImageView ?: return
        if (thumb.visibility != View.VISIBLE) return
        thumb.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                thumb.visibility = View.GONE
                thumb.setImageDrawable(null)  // release bitmap; we won't need it again
            }
            .start()
    }

    /**
     * RTSP frozen-first-frame watchdog. ExoPlayer occasionally fires
     * onRenderedFirstFrame on the codec-config frame (signalling "decoder
     * is alive") but then position never advances — the user sees a single
     * frozen frame indefinitely until they close and reopen the feed.
     *
     * Snapshot the position when first frame renders; 2 seconds later, if
     * position hasn't moved, issue a stop()+prepare() on the player. Bounded
     * to 2 attempts per card mount so we don't thrash endlessly if the
     * stream source is genuinely broken — at that point the existing
     * `attemptRtspReconnect()` path takes over.
     */
    private fun armFrozenFrameWatchdog() {
        cancelFrozenFrameWatchdog()
        val player = exoPlayer ?: return
        positionAtFirstFrame = player.currentPosition
        val runnable = Runnable {
            val p = exoPlayer ?: return@Runnable
            val advanced = p.currentPosition > positionAtFirstFrame + 100L
            if (advanced) return@Runnable
            if (frozenFrameRecoveryAttempts >= 2) {
                Log.w(TAG, "Frozen-frame watchdog: recovery budget exhausted for ${data.cameraEntityId}")
                return@Runnable
            }
            frozenFrameRecoveryAttempts++
            Log.w(
                TAG,
                "Frozen-frame watchdog: position=${p.currentPosition} stuck at ${positionAtFirstFrame} " +
                    "for ${data.cameraEntityId} — forcing re-prepare (attempt $frozenFrameRecoveryAttempts/2)",
            )
            try {
                p.stop()
                p.prepare()
            } catch (e: Exception) {
                Log.w(TAG, "Frozen-frame recovery re-prepare failed", e)
            }
        }
        frozenFrameWatchdog = runnable
        mainHandler.postDelayed(runnable, 2000L)
    }

    private fun cancelFrozenFrameWatchdog() {
        frozenFrameWatchdog?.let { mainHandler.removeCallbacks(it) }
        frozenFrameWatchdog = null
    }

    /**
     * Record a BUFFERING transition for the state-flap watchdog and trigger
     * a full player restart if we've seen too many within the sliding window.
     *
     * Rationale (from field logs + user reproduction):
     *   - pedroSG94-sourced RTSP streams (Dashie tablet cameras) sometimes
     *     hand ExoPlayer a bitstream that plays but flaps state constantly
     *     on the first session — visible frames never update.
     *   - User's workaround: close + reopen the card. That tears down the
     *     ExoPlayer and starts a fresh RTSP session, which succeeds.
     *   - Frigate playback mode uses a transcoded clip source, no flap.
     *   - Once the live stream stabilizes, it stays stable for the session.
     *
     * So the signal is: rapid, repeated BUFFERING on startup = unstable
     * handshake. The recovery is a full session restart via the existing
     * release+createExoPlayer path (same as attemptRtspReconnect but without
     * inflating the natural-reconnect counter). Bounded to 2 recoveries per
     * view rebuild so we don't thrash indefinitely on a genuinely broken
     * source — after that, existing paths take over (eventually MJPEG fallback).
     */
    private fun recordBufferingAndMaybeRecover() {
        val now = System.currentTimeMillis()
        bufferingTimestamps.addLast(now)
        // Drop timestamps outside the sliding window.
        while (bufferingTimestamps.isNotEmpty() && (now - bufferingTimestamps.first()) > FLAP_WINDOW_MS) {
            bufferingTimestamps.removeFirst()
        }
        if (bufferingTimestamps.size < FLAP_THRESHOLD) return
        if (flapRecoveryAttempts >= MAX_FLAP_RECOVERIES) {
            if (!flapBudgetExhaustedLogged) {
                Log.w(TAG, "State-flap watchdog: budget exhausted for ${data.cameraEntityId}")
                flapBudgetExhaustedLogged = true
            }
            return
        }
        flapRecoveryAttempts++
        val action = if (WATCHDOG_RESTART_ENABLED) "forcing full restart" else "observed (restart disabled for diagnostic)"
        Log.w(
            TAG,
            "State-flap watchdog: ${bufferingTimestamps.size} BUFFERING in ${FLAP_WINDOW_MS}ms " +
                "for ${data.cameraEntityId} — $action " +
                "(attempt $flapRecoveryAttempts/$MAX_FLAP_RECOVERIES)",
        )
        bufferingTimestamps.clear()  // don't retrigger until a fresh window worth of flaps
        if (!WATCHDOG_RESTART_ENABLED) return
        // Full restart: fresh ExoPlayer + fresh RTSP session. Same surface +
        // URI as the current session so we don't disturb the layout.
        val uri = lastRtspUri ?: return
        val tv = cameraTextureView
        val sv = cameraSurfaceView
        hasRenderedFirstFrame = false
        when {
            tv != null && tv.isAvailable -> createExoPlayerForTexture(tv, uri)
            sv != null && sv.holder.surface.isValid -> createExoPlayer(sv, uri)
        }
    }

    private fun buildOfflineOverlay(): TextView {
        val padH = VideoFeedStyles.dpToPx(context, 8)
        val padV = VideoFeedStyles.dpToPx(context, 4)
        return TextView(context).apply {
            text = "Offline"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(padH, padV, padH, padV)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xCC000000.toInt())
                cornerRadius = VideoFeedStyles.dpToPx(context, 4).toFloat()
            }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            visibility = if (showingOffline) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    // Playback overlay reference — set by FrigatePlaybackController
    var playbackOverlay: FrigatePlaybackOverlay? = null

    /** TV/d-pad playback: drop the touch chrome (close, mute, restore buttons). */
    var playbackTvMode = false

    fun setPlaybackMode() {
        mode = Mode.PLAYBACK
        stopStream()
        rebuildView()
        // Delay stream start to let the new SurfaceView get laid out and surface created
        post { startStream() }
    }

    fun exitPlaybackMode() {
        playbackOverlay = null
        playbackChrome.reset()
        mode = Mode.NORMAL
        stopStream()
        rebuildView()
        startStream()
    }

    /** TV d-pad zone focus ring: "video" | "scrubber" | "clips" | "none". */
    fun setPlaybackZoneFocus(zone: String) = playbackChrome.setZoneFocus(zone)

    /** Fullscreen playback (hide clips + label bar; timeline overlays the video). */
    fun setPlaybackFullscreen(on: Boolean) = playbackChrome.setFullscreen(on)

    /** Briefly reveal the timeline overlay during fullscreen scrubbing. */
    fun showFullscreenScrubBar() = playbackChrome.showScrubBar()

    private fun rebuildView() {
        when (mode) {
            Mode.NORMAL -> buildNormalView()
            Mode.MAXIMIZED -> buildMaximizedView()
            Mode.PLAYBACK -> buildPlaybackView()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildMaximizedView() {
        removeAllViews()
        scaleContainer = null
        firstLiveFrameRendered = false
        frozenFrameRecoveryAttempts = 0
        cancelFrozenFrameWatchdog()
        flapRecoveryAttempts = 0
        bufferingTimestamps.clear()
        flapBudgetExhaustedLogged = false
        freezeRecoveryCount = 0
        expandButton = null  // will be re-created in addOverlayButtons
        loadingSpinner = null  // will be re-created by createLabelBar
        val s = VideoFeedStyles

        background = s.createRoundedBackground(context, opaque = true)

        // Full-screen camera view
        if (renderMode == RenderMode.RTSP) {
            cameraImageView = null
            cameraTextureView = null  // Maximized uses SurfaceView; clear stale NORMAL-mode ref.
            val arFrame = AspectRatioFrameLayout(context).apply {
                // gravity=CENTER so the aspect-shrunk view is centered in the
                // card (equal letterbox bars on both sides) instead of
                // anchoring top-left and leaving a wide gap on one side.
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.CENTER
                }
                // FIT (letterbox) to match Normal + Playback modes. Previously
                // this was ZOOM (crop-to-fill) which over-zoomed 4:3 / non-16:9
                // cameras in maximized view. The MAXIMIZED card itself has a
                // black opaque rounded background so the letterbox bars render
                // as black, matching the NORMAL-mode blackWrap look.
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            val zoomWrap = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val sv = SurfaceView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            zoomWrap.addView(sv)
            arFrame.addView(zoomWrap)
            addView(arFrame)
            // Thumbnail above the SurfaceView area — SurfaceView punches
            // through the view hierarchy, so a sibling ImageView added after
            // it draws on top of the punch-through area until faded out.
            val thumb = ImageView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            addView(thumb)
            cameraSurfaceView = sv
            aspectRatioFrame = arFrame
            zoomWrapper = zoomWrap
            thumbnailImageView = thumb
        } else {
            cameraSurfaceView = null
            aspectRatioFrame = null
            zoomWrapper = null
            cameraImageView = ImageView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
            }
            addView(cameraImageView)
            val thumb = ImageView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            addView(thumb)
            thumbnailImageView = thumb
        }

        // Offline overlay
        offlineOverlay = buildOfflineOverlay()
        addView(offlineOverlay)

        // Overlay buttons — maximized style (no scale container)
        addOverlayButtons(this, isMaximized = true)

        setOnTouchListener(null) // No drag in maximized mode

        primeThumbnail()
    }

    /**
     * Playback mode: video + events side-by-side, timeline below, label bar at bottom.
     * Layout:
     *   [Video area (weight=1)] [Event strip (fixed width)]   ← top row
     *   [Play/Pause] [Timeline scrubber]                      ← timeline row
     *   [Label bar with play button + camera name + pin]      ← bottom
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildPlaybackView() {
        removeAllViews()
        scaleContainer = null
        expandButton = null  // playback mode doesn't use the expand button (its own restore)
        val s = VideoFeedStyles
        val overlay = playbackOverlay ?: return

        val cornerRadiusPx = s.dpToPx(context, 12).toFloat()
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF111111.toInt())
            cornerRadius = cornerRadiusPx
        }
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
        clipToOutline = true

        // Layout:
        // mainColumn (VERTICAL)
        //   topRow (HORIZONTAL, weight=1): [videoContainer weight=1] [eventStrip fixed]
        //   timelineRow (full width)
        //   labelBar (full width)
        val mainColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        playbackChrome.mainColumn = mainColumn

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        // Video container (holds camera view + overlay buttons + LIVE button)
        val videoContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setBackgroundColor(Color.BLACK)
        }

        if (renderMode == RenderMode.RTSP) {
            cameraImageView = null
            cameraTextureView = null  // Playback uses SurfaceView; clear stale NORMAL-mode ref.
            val arFrame = AspectRatioFrameLayout(context).apply {
                // AspectRatioFrameLayout resizes itself to the video aspect in FIT
                // mode, so its MATCH_PARENT params get shrunk on one axis. Use
                // gravity=CENTER on the FrameLayout params so the shrunk view is
                // centered in videoContainer (equal letterbox bars on both sides /
                // top-bottom) instead of anchored top-left.
                layoutParams = FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
                ).apply { gravity = Gravity.CENTER }
                // FIT mode: show the full video with letterbox for aspect mismatch.
                // Prevents 4:3 feed (480p Samsung/Fire) from having its bottom cropped
                // when displayed in the 16:9-ish video area. Both live RTSP and clip
                // playback render through this AspectRatioFrameLayout, so the clip
                // player inherits this mode automatically.
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            val sv = SurfaceView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            arFrame.addView(sv)
            videoContainer.addView(arFrame)
            cameraSurfaceView = sv
            aspectRatioFrame = arFrame
            zoomWrapper = null  // no zoom in playback mode
        } else {
            cameraSurfaceView = null
            cameraTextureView = null
            aspectRatioFrame = null
            zoomWrapper = null
            cameraImageView = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
            }
            videoContainer.addView(cameraImageView)
        }

        // Offline overlay
        offlineOverlay = buildOfflineOverlay()
        videoContainer.addView(offlineOverlay)

        // Video playback controls (play/pause, skip) — centered overlay
        val videoControls = overlay.buildVideoControlsOverlay()
        videoContainer.addView(videoControls)

        // Tap video to show/hide playback controls
        videoContainer.isClickable = true
        videoContainer.setOnClickListener { overlay.toggleVideoControls() }

        // Overlay buttons (close) on the video
        addOverlayButtons(videoContainer, isMaximized = false)

        topRow.addView(videoContainer)
        playbackChrome.videoContainer = videoContainer

        // Event strip (right side, only video height). Wrapped in a non-scrolling
        // FrameLayout so the d-pad zone focus ring covers the whole strip box —
        // a ring on the ScrollView itself only spans the laid-out content and
        // shifts as the list scrolls.
        val eventStrip = overlay.buildEventStrip()
        val stripWidth = (eventStrip.layoutParams as? LinearLayout.LayoutParams)?.width
            ?: LinearLayout.LayoutParams.WRAP_CONTENT
        val clipsWrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(stripWidth, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        clipsWrapper.addView(
            eventStrip,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        topRow.addView(clipsWrapper)
        playbackChrome.eventStrip = clipsWrapper

        mainColumn.addView(topRow)

        // Timeline row: same column split as topRow
        // Left: [play/pause] [timeline]  Right: [clip timer] [Go to Live]
        val timelineRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val timelineSection = overlay.buildTimelineSection()
        timelineRow.addView(timelineSection, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        timelineRow.addView(overlay.buildClipInfoSection())
        mainColumn.addView(timelineRow)
        playbackChrome.timelineRow = timelineRow
        playbackChrome.timelineSection = timelineSection

        // Day bar (history navigation) below the scrubber, aligned under it
        // (left section matches the scrubber; right spacer aligns to the clips).
        val dayRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val daySection = overlay.buildDayBar()
        dayRow.addView(daySection, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        dayRow.addView(overlay.buildClipInfoSection())
        mainColumn.addView(dayRow)
        playbackChrome.dayRow = dayRow
        playbackChrome.dayBarSection = daySection

        // Label bar (camera title) — pinned to the TOP in playback to balance the
        // day bar at the bottom. Inserted at index 0 above the video row.
        val labelBar = createLabelBar(data.displayLabel)
        mainColumn.addView(labelBar, 0)
        playbackChrome.labelBar = labelBar

        addView(mainColumn)
        // No resize handle in playback mode — the card is full-screen.
    }

    // ── Stream Start/Stop ─────────────────────────────────────────────

    private fun startStream() {
        if (renderMode == RenderMode.RTSP) {
            startRtspStream()
        } else {
            startMjpegStream()
        }
    }

    private fun stopStream() {
        streaming.set(false)
        releaseExoPlayer()
    }

    /** Public: pause the live stream (freezes the last frame). */
    fun pauseLiveStream() {
        exoPlayer?.pause()
        // For MJPEG, stopping the stream freezes on the last fetched image.
        if (renderMode != RenderMode.RTSP) stopStream()
    }

    /** Public: resume the live stream. */
    fun resumeLiveStream() {
        if (exoPlayer != null) {
            exoPlayer?.play()
        } else {
            startStream()
        }
    }

    /** Whether the video player's audio is muted (player-level, not system). */
    private var playerMuted: Boolean = true  // Default: muted on open

    /** Mute or unmute the video player's audio (live + clip players). Does NOT touch system volume. */
    fun setPlayerMuted(muted: Boolean) {
        playerMuted = muted
        val v = if (muted) 0f else 1f
        exoPlayer?.volume = v
        clipPlayback.applyMute(muted)
    }

    fun isPlayerMuted(): Boolean = playerMuted

    // ── ExoPlayer RTSP ────────────────────────────────────────────────

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun startRtspStream() {
        val effectiveRtspUrl = upgradedRtspUrl ?: data.rtspUrl
        if (effectiveRtspUrl.isBlank() || effectiveRtspUrl == "null") {
            Log.w(TAG, "No RTSP URL, falling back to MJPEG: ${data.cameraEntityId}")
            fallbackToMjpeg()
            return
        }

        // Build the final ExoPlayer Uri once
        val rtspUri = buildExoPlayerUri(effectiveRtspUrl)
        Log.i(TAG, "RTSP prepared for ${data.cameraEntityId}: $rtspUri (host=${rtspUri.host})")

        if (rtspUri.host.isNullOrBlank()) {
            Log.w(TAG, "Failed to build RTSP URI (no host), falling back to MJPEG")
            fallbackToMjpeg()
            return
        }

        val uri = rtspUri

        // Route to TextureView (Mode.NORMAL) or SurfaceView (Maximized / Playback).
        val tv = cameraTextureView
        if (tv != null) {
            if (tv.isAvailable) {
                createExoPlayerForTexture(tv, uri)
            } else {
                tv.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        if (exoPlayer == null && tv === cameraTextureView) createExoPlayerForTexture(tv, uri)
                    }
                    override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true
                    // No-op: once ExoPlayer attaches via setVideoTextureView it
                    // owns this listener anyway. The frame-stall watchdog tracks
                    // ExoPlayer position instead (see startLiveEdgeChaser).
                    override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
                }
            }
            return
        }

        val sv = cameraSurfaceView ?: run {
            Log.w(TAG, "No SurfaceView/TextureView available, falling back to MJPEG")
            fallbackToMjpeg()
            return
        }

        // Use SurfaceHolder.Callback to wait for surface readiness.
        // Player release is handled explicitly by stopStream() / release() only.
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (exoPlayer == null && sv === cameraSurfaceView) createExoPlayer(sv, uri)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // ExoPlayer handles surface loss internally.
                // Player release is handled by stopStream() / release().
            }
        })
        if (sv.holder.surface.isValid) {
            createExoPlayer(sv, uri)
        }
    }

    /** Build an ExoPlayer-compatible Uri from a raw RTSP URL.
     *  Returns a Uri object (not string) to avoid re-parsing issues.
     *  For URLs without credentials, Uri.parse() works fine.
     *  For URLs with credentials containing '@', we build the Uri
     *  manually with encoded authority so getHost() works correctly. */
    private fun buildExoPlayerUri(rawUrl: String): android.net.Uri {
        val (strippedUrl, user, pass) = parseRtspUrl(rawUrl)
        if (user.isNullOrBlank()) return android.net.Uri.parse(strippedUrl)

        // Parse host:port/path from the stripped (credential-free) URL
        val hostAndPath = strippedUrl.removePrefix("rtsp://")
        val slashIdx = hostAndPath.indexOf('/')
        val authority = if (slashIdx >= 0) hostAndPath.substring(0, slashIdx) else hostAndPath
        val path = if (slashIdx >= 0) hostAndPath.substring(slashIdx) else ""
        val colonIdx = authority.lastIndexOf(':')
        val host = if (colonIdx >= 0) authority.substring(0, colonIdx) else authority
        val port = if (colonIdx >= 0) authority.substring(colonIdx + 1).toIntOrNull() ?: -1 else -1

        return android.net.Uri.Builder()
            .scheme("rtsp")
            .encodedAuthority(
                "${android.net.Uri.encode(user, "")}:${android.net.Uri.encode(pass ?: "", "")}@$host${if (port > 0) ":$port" else ""}"
            )
            .encodedPath(path)
            .build()
    }

    // Cache the URI for reconnect attempts
    private var lastRtspUri: android.net.Uri? = null


    /** Variant for TextureView output (Mode.NORMAL). See createExoPlayer for the
     *  SurfaceView variant used by Maximized / Playback modes. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun createExoPlayerForTexture(textureView: android.view.TextureView, uri: android.net.Uri) {
        createExoPlayerCommon(uri) { player -> player.setVideoTextureView(textureView) }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun createExoPlayer(surfaceView: SurfaceView, uri: android.net.Uri) {
        createExoPlayerCommon(uri) { player -> player.setVideoSurfaceView(surfaceView) }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun createExoPlayerCommon(uri: android.net.Uri, attachSink: (ExoPlayer) -> Unit) {
        releaseExoPlayer()
        lastRtspUri = uri
        hasRenderedFirstFrame = false
        Log.i(TAG, "Starting ExoPlayer RTSP: ${data.cameraEntityId} → $uri (host=${uri.host}, port=${uri.port}, userInfo=${uri.userInfo != null})")

        // Low-latency buffer for live RTSP.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                500,   // minBufferMs — absorbs ~200ms inter-frame gaps
                2000,  // maxBufferMs — cap to limit latency
                0,     // bufferForPlaybackMs — play immediately
                0      // bufferForPlaybackAfterRebufferMs — resume instantly
            )
            .build()

        // Focal (large) cards and PLAYBACK mode (full-screen Frigate playback)
        // enable audio. Strip (small) cards disable the audio track to avoid
        // audio sync pacing that causes latency drift on small previews.
        val isFocal = size == "large" || mode == Mode.PLAYBACK

        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        val player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()
        attachSink(player)
        player.addListener(exoPlayerListener)

        if (!isFocal) {
            // Strip cards: disable audio track to prevent latency drift
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true)
                .build()
        }
        // Focal cards: full player volume; system volume (VolumeManager) gates actual output.
        // In playback mode, the user can mute via the always-visible mute button.
        // Playback mode respects playerMuted (mute button); other modes stay silent.
        player.volume = when {
            mode == Mode.PLAYBACK && !playerMuted -> 1f
            else -> 0f
        }

        val factory = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setDebugLoggingEnabled(true)
        val rtspSource = factory.createMediaSource(MediaItem.fromUri(uri))
        player.setMediaSource(rtspSource)
        player.prepare()
        player.playWhenReady = true
        exoPlayer = player

        // Periodically check buffer health — if we drift >2s behind,
        // seek to end of buffer to snap back to live edge
        startLiveEdgeChaser()
        // Don't reset rtspReconnectAttempts here — createExoPlayer is called by
        // attemptRtspReconnect(), so resetting here prevents the fallback from ever
        // triggering. Reset happens in STATE_READY callback on successful connect.
    }

    private var hasRenderedFirstFrame = false

    private val exoPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            Log.i(TAG, "ExoPlayer state: $stateName for ${data.cameraEntityId}")
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    // State-flap detection: a healthy stream has at most a
                    // couple of BUFFERING entries on startup. Repeated rapid
                    // transitions within a short window mean the RTSP session
                    // is unstable from the source side. Force a full player
                    // restart (fresh handshake) instead of waiting for natural
                    // recovery that may never come.
                    recordBufferingAndMaybeRecover()
                }
                Player.STATE_READY -> {
                    rtspReconnectAttempts = 0
                    // Quick-retry: if READY but no frame rendered within 2s,
                    // the SurfaceView surface isn't connected — restart immediately
                    if (!hasRenderedFirstFrame) {
                        mainHandler.postDelayed({
                            if (!hasRenderedFirstFrame && exoPlayer?.playbackState == Player.STATE_READY) {
                                Log.w(TAG, "READY but no frame for ${data.cameraEntityId} — quick retry")
                                val uri = lastRtspUri ?: return@postDelayed
                                val tv = cameraTextureView
                                val sv = cameraSurfaceView
                                hasRenderedFirstFrame = false
                                when {
                                    tv != null && tv.isAvailable -> createExoPlayerForTexture(tv, uri)
                                    sv != null && sv.holder.surface.isValid -> createExoPlayer(sv, uri)
                                }
                            }
                        }, 2000)
                    }
                    if (showingOffline) {
                        showingOffline = false
                        mainHandler.post { offlineOverlay?.visibility = View.GONE }
                    }
                }
                Player.STATE_ENDED -> {
                    Log.w(TAG, "ExoPlayer stream ended: ${data.cameraEntityId}")
                    attemptRtspReconnect()
                }
                else -> {}
            }
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            Log.i(TAG, "ExoPlayer video size: ${videoSize.width}x${videoSize.height} for ${data.cameraEntityId}")
            if (videoSize.width > 0 && videoSize.height > 0) {
                mainHandler.post { applyCenterCropTransform(videoSize.width, videoSize.height) }
            }
        }

        override fun onRenderedFirstFrame() {
            hasRenderedFirstFrame = true
            Log.i(TAG, "ExoPlayer rendered first frame for ${data.cameraEntityId}")
            mainHandler.post {
                onFirstLiveFrame()
                armFrozenFrameWatchdog()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val cause = error.cause?.cause ?: error.cause
            Log.w(TAG, "ExoPlayer error for ${data.cameraEntityId}: ${error.message} | cause: ${cause?.javaClass?.simpleName}: ${cause?.message}")
            attemptRtspReconnect()
        }
    }

    /** Set the aspect ratio on the AspectRatioFrameLayout for center-crop. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun applyCenterCropTransform(videoWidth: Int, videoHeight: Int) {
        lastVideoWidth = videoWidth
        lastVideoHeight = videoHeight
        val arFrame = aspectRatioFrame ?: return
        if (videoHeight > 0) {
            arFrame.setAspectRatio(videoWidth.toFloat() / videoHeight)
        }
        Log.d(TAG, "Center-crop: video=${videoWidth}x${videoHeight} aspect=${videoWidth.toFloat() / videoHeight}")
    }

    private fun attemptRtspReconnect() {
        rtspReconnectAttempts++
        if (rtspReconnectAttempts > MAX_RTSP_RECONNECTS) {
            Log.w(TAG, "ExoPlayer RTSP failed after $MAX_RTSP_RECONNECTS attempts, falling back to MJPEG")
            fallbackToMjpeg()
            return
        }
        Log.i(TAG, "ExoPlayer reconnect attempt $rtspReconnectAttempts/$MAX_RTSP_RECONNECTS")
        mainHandler.postDelayed({
            val uri = lastRtspUri ?: return@postDelayed
            val tv = cameraTextureView
            val sv = cameraSurfaceView
            when {
                tv != null && tv.isAvailable -> createExoPlayerForTexture(tv, uri)
                sv != null && sv.holder.surface.isValid -> createExoPlayer(sv, uri)
            }
        }, RTSP_RECONNECT_DELAY_MS)
    }

    private fun fallbackToMjpeg() {
        Log.i(TAG, "Falling back to MJPEG for ${data.cameraEntityId}")
        releaseExoPlayer()
        renderMode = RenderMode.MJPEG
        mainHandler.post {
            rebuildView()
            reapplyScale()
            startMjpegStream()
        }
    }

    /** Upgrade a live MJPEG card to RTSP ExoPlayer. Call from UI thread. */
    fun upgradeToRtsp(rtspUrl: String) {
        if (renderMode == RenderMode.RTSP) return // already RTSP
        if (rtspUrl.isBlank()) return
        Log.i(TAG, "Upgrading to RTSP for ${data.cameraEntityId}: $rtspUrl")
        upgradedRtspUrl = rtspUrl
        stopStream()
        renderMode = RenderMode.RTSP

        // In PLAYBACK mode, avoid rebuildView — it would wipe the LIVE button
        // the FrigatePlaybackController installed. Instead inject a SurfaceView
        // over the existing MJPEG ImageView (mirroring the clip-playback
        // injection path) and start RTSP on it.
        if (mode == Mode.PLAYBACK) {
            val videoContainer = playbackVideoContainer()
            if (videoContainer != null && cameraSurfaceView == null) {
                cameraImageView?.visibility = View.GONE
                val sv = SurfaceView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                // Insert at index 0 so the LIVE button + overlay controls stay on top.
                videoContainer.addView(sv, 0)
                cameraSurfaceView = sv
                injectedClipSurfaceView = sv  // Reuse existing teardown path on exit.
            }
            startStream()
            return
        }

        rebuildView()
        // Re-apply scale — onSizeChanged won't fire since card size didn't change
        reapplyScale()
        startStream()
    }

    /** Re-apply scale container transform after a view rebuild. */
    private fun reapplyScale() {
        val sc = scaleContainer ?: return
        val w = width
        val h = height
        if (w > 0 && h > 0 && baseWidthPx > 0 && baseHeightPx > 0) {
            val scale = minOf(w.toFloat() / baseWidthPx, h.toFloat() / baseHeightPx)
            sc.scaleX = scale
            sc.scaleY = scale
        }
    }

    /** RTSP URL provided via upgradeToRtsp() — used instead of data.rtspUrl */
    private var upgradedRtspUrl: String? = null

    // ── MJPEG Stream ──────────────────────────────────────────────────

    private fun startMjpegStream() {
        // Bail early if release() has already shut the executor down. This
        // guards against main-thread Runnables (fallbackToMjpeg, etc.) that
        // were queued before release() ran and fire after the executor is
        // terminated — which would otherwise crash with RejectedExecutionException.
        if (executor.isShutdown) return
        if (streaming.getAndSet(true) || streamUrl.isBlank()) return
        Log.i(TAG, "Starting MJPEG stream: ${data.cameraEntityId}")
        try {
            executor.execute { readMjpegStream() }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Tiny race between isShutdown check and submit — keep state consistent.
            streaming.set(false)
            Log.w(TAG, "MJPEG submit rejected — executor was shut down", e)
        }
    }

    /**
     * Reads an MJPEG stream (multipart/x-mixed-replace) from the HA camera proxy.
     * Parses the multipart boundary, extracts each JPEG frame, decodes to Bitmap,
     * and posts to the main thread to update the ImageView.
     * Auto-reconnects on error.
     */
    private fun readMjpegStream() {
        var backoffMs = RECONNECT_DELAY_MS
        while (streaming.get()) {
            var connected = false
            try {
                val request = Request.Builder()
                    .url(streamUrl)
                    .header("Authorization", "Bearer ${authTokenProvider()}")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 401) {
                        Log.w(TAG, "MJPEG stream 401 — attempting token refresh")
                        val refreshed = onTokenRefreshNeeded?.invoke() ?: false
                        if (refreshed) {
                            Log.i(TAG, "Token refreshed, will retry stream")
                        } else {
                            Log.e(TAG, "Token refresh failed or unavailable")
                        }
                        return@use
                    }
                    if (!response.isSuccessful) {
                        Log.w(TAG, "MJPEG stream HTTP ${response.code}")
                        return@use
                    }

                    connected = true
                    backoffMs = RECONNECT_DELAY_MS  // Reset backoff on successful connect

                    val contentType = response.header("Content-Type") ?: ""
                    val boundary = extractBoundary(contentType)
                    if (boundary == null) {
                        Log.w(TAG, "No multipart boundary in Content-Type: $contentType")
                        return@use
                    }

                    Log.i(TAG, "MJPEG stream connected, boundary='$boundary'")
                    val stream = BufferedInputStream(response.body?.byteStream() ?: return@use, 131072)
                    readFrames(stream, boundary)
                }
            } catch (e: Exception) {
                if (streaming.get()) {
                    Log.w(TAG, "MJPEG stream error: ${e.message}")
                }
            }

            // Reconnect with backoff if still active
            if (streaming.get()) {
                val delay = if (connected) RECONNECT_DELAY_MS else backoffMs
                try { Thread.sleep(delay) } catch (_: InterruptedException) { return }
                if (!connected) {
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)  // Max 30s backoff
                }
            }
        }
    }

    /**
     * Read MJPEG frames from the stream. Each frame is delimited by a boundary
     * and has Content-Length headers telling us the exact JPEG size.
     */
    private fun readFrames(stream: BufferedInputStream, boundary: String) {
        var lastFrameT = System.currentTimeMillis()
        var frameCount = 0
        while (streaming.get()) {
            // Skip to next boundary
            if (!skipToBoundary(stream, boundary)) break

            // Read headers
            var contentLength = -1
            while (streaming.get()) {
                val line = readLine(stream) ?: return
                if (line.isEmpty()) break  // End of headers
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
                }
            }

            if (!streaming.get()) continue

            // Two valid framings in multipart/x-mixed-replace:
            //   - Content-Length present (our ffmpeg proxy path): read exactly
            //     that many bytes.
            //   - Content-Length absent (Frigate's native /api/<camera> MJPEG,
            //     and many other sources — it's optional per the spec): read the
            //     JPEG by its SOI(FFD8)…EOI(FFD9) markers. Without this, every
            //     frame from a Content-Length-less source was skipped and the
            //     card spun forever (Phase A Frigate auto-routing regression).
            val frameStartT = System.currentTimeMillis()
            val frameData: ByteArray? = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength && streaming.get()) {
                    val n = stream.read(buf, read, contentLength - read)
                    if (n < 0) return
                    read += n
                }
                if (read == contentLength) buf else null
            } else {
                readJpegByMarkers(stream)
            }

            if (frameData == null || !streaming.get()) continue

            // Skip decode if a previous frame is still waiting to display
            if (pendingFrame.get()) {
                frameCount++
                continue
            }
            val readDoneT = System.currentTimeMillis()
            val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size)
            val decodeDoneT = System.currentTimeMillis()
            if (bitmap != null) {
                val readMs = readDoneT - frameStartT
                val decodeMs = decodeDoneT - readDoneT
                val sinceLastMs = frameStartT - lastFrameT
                lastFrameT = frameStartT
                frameCount++
                val fc = frameCount
                val cl = frameData.size
                val postTimeMs = System.currentTimeMillis()
                pendingFrame.set(true)
                mainHandler.postAtFrontOfQueue {
                    val displayMs = System.currentTimeMillis() - postTimeMs
                    cameraImageView?.setImageBitmap(bitmap)
                    pendingFrame.set(false)
                    if (showingOffline) {
                        showingOffline = false
                        offlineOverlay?.visibility = android.view.View.GONE
                    }
                    if (!firstLiveFrameRendered) onFirstLiveFrame()
                    Log.d(TAG, "F#$fc: ${cl}B r=${readMs}ms d=${decodeMs}ms g=${sinceLastMs}ms disp=${displayMs}ms")
                }
            }
        }
    }

    /**
     * Read one JPEG from an MJPEG part that has no Content-Length header
     * (e.g. Frigate's native /api/<camera> MJPEG). Scans for the JPEG
     * Start-Of-Image marker (FF D8) and reads through the End-Of-Image
     * marker (FF D9), returning the complete JPEG inclusive of both.
     * FF D9 only appears as the real EOI in a valid JPEG stream (FF bytes
     * in entropy-coded data are byte-stuffed as FF 00), so marker scanning
     * is safe. Returns null on EOF or if the frame exceeds the size guard.
     */
    private fun readJpegByMarkers(stream: BufferedInputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream(64 * 1024)
        var started = false
        var prev = -1
        while (streaming.get()) {
            val b = stream.read()
            if (b < 0) return null
            if (!started) {
                if (prev == 0xFF && b == 0xD8) {
                    out.write(0xFF)
                    out.write(0xD8)
                    started = true
                    prev = 0xD8
                } else {
                    prev = b
                }
            } else {
                out.write(b)
                if (prev == 0xFF && b == 0xD9) return out.toByteArray()  // EOI
                prev = b
                if (out.size() > MAX_MJPEG_FRAME_BYTES) return null  // malformed
            }
        }
        return null
    }

    /** Skip bytes until we find the boundary marker line. */
    private fun skipToBoundary(stream: BufferedInputStream, boundary: String): Boolean {
        while (streaming.get()) {
            val line = readLine(stream) ?: return false
            if (line.contains(boundary)) return true
        }
        return false
    }

    /** Read a single line (terminated by \r\n or \n) from the input stream. */
    private fun readLine(stream: BufferedInputStream): String? {
        val sb = StringBuilder(128)
        while (streaming.get()) {
            val b = stream.read()
            if (b < 0) return null
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 4096) return sb.toString() // Safety limit
        }
        return null
    }

    /**
     * Parse an RTSP URL into (stripped URL without credentials, username, password).
     * ExoPlayer's RtspClient can't handle '@' in usernames (e.g., email addresses).
     * We strip credentials from the URL and pass them separately via setUserInfo().
     *
     * Input:  rtsp://user%40gmail.com:pass%21@192.168.1.1:554/stream
     * Output: Triple("rtsp://192.168.1.1:554/stream", "user@gmail.com", "pass!")
     */
    private fun parseRtspUrl(url: String): Triple<String, String?, String?> {
        if (!url.startsWith("rtsp://")) return Triple(url, null, null)
        val afterScheme = url.substring(7)
        val atIdx = afterScheme.lastIndexOf('@')
        if (atIdx < 0) return Triple(url, null, null) // No credentials
        val encodedCreds = afterScheme.substring(0, atIdx)
        val hostAndPath = afterScheme.substring(atIdx + 1)
        val strippedUrl = "rtsp://$hostAndPath"
        val colonIdx = encodedCreds.indexOf(':')
        val (rawUser, rawPass) = if (colonIdx >= 0) {
            encodedCreds.substring(0, colonIdx) to encodedCreds.substring(colonIdx + 1)
        } else {
            encodedCreds to ""
        }
        val user = try { java.net.URLDecoder.decode(rawUser, "UTF-8") } catch (_: Exception) { rawUser }
        val pass = try { java.net.URLDecoder.decode(rawPass, "UTF-8") } catch (_: Exception) { rawPass }
        return Triple(strippedUrl, user, pass)
    }

    /** Extract multipart boundary from Content-Type header. */
    private fun extractBoundary(contentType: String): String? {
        val idx = contentType.indexOf("boundary=", ignoreCase = true)
        if (idx < 0) return null
        return contentType.substring(idx + 9).trim()
    }

    // ── Overlay Buttons ──────────────────────────────────────────────

    /**
     * Button layout:
     * - Close (X) in top-LEFT
     * - Maximize/Restore (expand/compress) in top-RIGHT
     */
    private fun addOverlayButtons(parent: FrameLayout, isMaximized: Boolean) {
        val s = VideoFeedStyles
        val isFullScreen = isMaximized || mode == Mode.PLAYBACK
        val btnSize = if (mode == Mode.PLAYBACK) s.dpToPx(context, 48)
            else if (isMaximized) s.dpToPx(context, 36)
            else s.dpToPx(context, 28)
        val margin = if (isFullScreen) s.dpToPx(context, 12) else s.dpToPx(context, 6)
        val isDark = s.isDarkMode(context)
        val btnBg = if (isFullScreen) 0x80808080.toInt()
            else if (isDark) VideoFeedStyles.CLOSE_BG_DARK else VideoFeedStyles.CLOSE_BG_LIGHT
        val iconColor = if (isFullScreen || isDark) 0xFFFFFFFF.toInt() else 0xFF333333.toInt()

        // Close button — top LEFT (elevated above video controls overlay)
        val closeBtn = createIconButton(R.drawable.ic_close, btnSize, btnBg, iconColor).apply {
            layoutParams = LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(margin, margin, 0, 0)
            }
            elevation = 20f
            setOnClickListener {
                Log.i(TAG, "closeBtn clicked (mode=$mode, hasOverride=${onDismissOverride != null})")
                onDismissOverride?.invoke() ?: onDismiss()
            }
        }
        // TV playback uses BACK to exit — no on-screen close button.
        val tvPlayback = mode == Mode.PLAYBACK && playbackTvMode
        if (!tvPlayback) parent.addView(closeBtn)

        // Always-visible mute button + restore — top RIGHT (touch chrome only).
        if (mode == Mode.PLAYBACK && !playbackTvMode) {
            playbackOverlay?.buildMuteButton(btnBg, iconColor, btnSize)?.let { muteBtn ->
                muteBtn.layoutParams = LayoutParams(btnSize, btnSize).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(0, margin, margin, 0)
                }
                muteBtn.elevation = 20f
                parent.addView(muteBtn)
            }

            // Restore (compress) button to the LEFT of the mute icon
            val restoreBtn = createIconButton(R.drawable.ic_compress, btnSize, btnBg, iconColor).apply {
                layoutParams = LayoutParams(btnSize, btnSize).apply {
                    gravity = Gravity.TOP or Gravity.END
                    // Leave room for the mute button to the right (btnSize + margin)
                    setMargins(0, margin, margin + btnSize + margin, 0)
                }
                elevation = 20f
                setOnClickListener {
                    Log.i(TAG, "restoreBtn clicked (mode=$mode)")
                    onRestoreFromPlayback?.invoke()
                }
            }
            parent.addView(restoreBtn)
        }

        // Maximize (expand arrows) or Restore (compress arrows) — top RIGHT.
        //
        // Always create the button in non-Playback modes and store the reference.
        // Visibility is driven by refreshExpandButtonVisibility() which reads
        // the current [tapToMaximize] flag. This lets callers that flip the flag
        // AFTER the view was built (the common case — CardPlacement /
        // VideoFeedCoordinator.applyInteractivity sets interactivity post-
        // construction) still see the button appear or disappear live.
        //
        // Rules enforced in refreshExpandButtonVisibility:
        //   - Maximized mode: always show (compress button, always usable)
        //   - Normal mode + tapToMaximize: show (expand button)
        //   - Normal mode + !tapToMaximize: hide (strip, screensaver, drawer
        //     pre-open, notification-style)
        if (mode != Mode.PLAYBACK) {
            val expandIcon = if (isMaximized) R.drawable.ic_compress else R.drawable.ic_expand
            val expandBtn = createIconButton(expandIcon, btnSize, btnBg, iconColor).apply {
                layoutParams = LayoutParams(btnSize, btnSize).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(0, margin, margin, 0)
                }
                setOnClickListener {
                    if (isMaximized) onNormalize() else onMaximize()
                }
            }
            parent.addView(expandBtn)
            expandButton = expandBtn
            refreshExpandButtonVisibility()
        }


        // Resize handle — bottom LEFT (visual only, touch handled by card)
        // Skip in playback mode — playback adds its own resize handle at the card level
        if (!isMaximized && mode != Mode.PLAYBACK) {
            val handleSize = s.dpToPx(context, s.RESIZE_HANDLE_DP)
            val resizeHandle = ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_resize_handle))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = LayoutParams(handleSize, handleSize).apply {
                    gravity = Gravity.BOTTOM or Gravity.START
                }
                elevation = 10f
            }
            parent.addView(resizeHandle)
        }
    }

    /**
     * Toggle visibility of the expand/compress button based on the current
     * [mode] and [tapToMaximize] flag. Safe to call before the button has
     * been created (no-op if [expandButton] is null).
     *
     * Rule: show the button whenever the card is maximized OR when the card
     * is in normal mode with tapToMaximize enabled (which now includes
     * notification-style floating alerts — the user wants to be able to
     * maximize an active alert).
     */
    private fun refreshExpandButtonVisibility() {
        val btn = expandButton ?: return
        val showForMaximized = mode == Mode.MAXIMIZED
        val showForNormalMaximizable = mode == Mode.NORMAL && tapToMaximize
        btn.visibility = if (showForMaximized || showForNormalMaximizable) View.VISIBLE else View.GONE
    }

    private fun createIconButton(iconRes: Int, sizePx: Int, bgColor: Int, tintColor: Int): ImageView {
        val s = VideoFeedStyles
        val padding = s.dpToPx(context, 6)
        return ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, iconRes))
            setColorFilter(tintColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(padding, padding, padding, padding)
            background = s.createCircleBackground(bgColor)
            elevation = 10f
            isClickable = true
            isFocusable = true
        }
    }

    // ── Scale ─────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val sc = scaleContainer ?: return
        if (baseWidthPx > 0 && baseHeightPx > 0) {
            val scale = minOf(w.toFloat() / baseWidthPx, h.toFloat() / baseHeightPx)
            sc.scaleX = scale
            sc.scaleY = scale
        }
    }

    // ── Pin Toggle ───────────────────────────────────────────────────

    /** Enable overlay controls and rebuild the view (for transitioning from strip to floating). */
    fun enableOverlayControls() {
        if (showOverlayControls) return
        showOverlayControls = true
        rebuildView()
    }

    /** Update pin state externally (e.g., after drag-to-float) */
    fun updatePinned(pinned: Boolean) {
        isPinned = pinned
        updatePinIcon()
    }

    private fun togglePin() {
        isPinned = !isPinned
        updatePinIcon()
        onPinChanged?.invoke(isPinned)
    }

    private fun updatePinIcon() {
        val icon = if (isPinned) R.drawable.ic_pin else R.drawable.ic_unpin
        pinIcon?.setImageDrawable(ContextCompat.getDrawable(context, icon))
        pinIcon?.alpha = if (isPinned) 1.0f else 0.5f
    }

    // ── UI Components ────────────────────────────────────────────────

    private fun createLabelBar(name: String): LinearLayout {
        val s = VideoFeedStyles
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.VISIBLE  // Defensive: ensure not accidentally hidden
            tag = LABEL_BAR_TAG
            val labelPx = s.dpToPx(context, if (compactLabelBar) s.LABEL_HEIGHT_DP / 2 else s.LABEL_HEIGHT_DP)
            minimumHeight = labelPx
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, labelPx
            )
            val leftPad = if (showOverlayControls) s.dpToPx(context, s.RESIZE_HANDLE_DP + 4) else s.dpToPx(context, 8)
            setPadding(leftPad, 0, s.dpToPx(context, 8), 0)
            // In playback mode the bar sits on a dark card bg (not over video),
            // so always use a visible solid gray. In normal mode, semi-transparent over video.
            if (mode == Mode.PLAYBACK) {
                setBackgroundColor(0xFF444444.toInt())
            } else {
                setBackgroundColor(0x80000000.toInt())
            }

            // Center group: [Play button (optional)] [Camera name]
            // Wrapped in a weight=1 container so pin icon stays right-aligned
            val centerGroup = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
                )
            }

            // Playback button — left of label (only for Frigate cameras, not in playback mode)
            if (data.isFrigateCamera && showOverlayControls && mode != Mode.PLAYBACK) {
                val iconSize = s.dpToPx(context, if (compactLabelBar) 14 else 28)
                val playBtn = ImageView(context).apply {
                    tag = "videofeed_play_icon"
                    setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_playback))
                    setColorFilter(0xFFFFFFFF.toInt())
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        marginEnd = s.dpToPx(context, 6)
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    setOnClickListener {
                        Log.i(TAG, "🎬 Play button clicked for ${data.displayLabel}, callback=${onPlaybackRequested != null}")
                        onPlaybackRequested?.invoke()
                    }
                }
                playbackIcon = playBtn
                centerGroup.addView(playBtn)
            }

            // Camera name label
            val label = TextView(context).apply {
                tag = "videofeed_label_text"
                text = name.ifEmpty { data.cameraEntityId }
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, VideoFeedStyles.scaledSp(context, if (compactLabelBar) 10f else 13f))
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                isSingleLine = true
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { togglePin() }
            }
            labelText = label
            centerGroup.addView(label)

            // Loading spinner — shown while the live stream is still
            // establishing (we still have the thumbnail placeholder up).
            // Hidden in onFirstLiveFrame(). Skipped in PLAYBACK mode (clip
            // has its own progress UI) and on compact label bars where
            // there's no room.
            if (mode != Mode.PLAYBACK && !compactLabelBar) {
                val spinnerSize = s.dpToPx(context, 14)
                val spinner = android.widget.ProgressBar(
                    context, null, android.R.attr.progressBarStyleSmall,
                ).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    layoutParams = LinearLayout.LayoutParams(spinnerSize, spinnerSize).apply {
                        marginStart = s.dpToPx(context, 6)
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    visibility = if (firstLiveFrameRendered) View.GONE else View.VISIBLE
                }
                loadingSpinner = spinner
                centerGroup.addView(spinner)
            }

            addView(centerGroup)

            Log.i(TAG, "🎥 LabelBar: ${data.displayLabel} isFrigateCamera=${data.isFrigateCamera} frigateName=${data.frigateCameraName} showOverlay=$showOverlayControls sourceType=${data.streamSourceType} entity=${data.cameraEntityId}")

            // Pin icon — right of label (hidden when overlay controls are off or in playback)
            if (showOverlayControls && mode != Mode.PLAYBACK) {
                pinIcon = ImageView(context).apply {
                    tag = "videofeed_pin_icon"
                    val iconSize = s.dpToPx(context, if (compactLabelBar) 12 else 20)
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        marginStart = s.dpToPx(context, 6)
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setColorFilter(0xFFFFFFFF.toInt())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { togglePin() }
                }
                addView(pinIcon)
                updatePinIcon()
            } else {
                pinIcon = null
            }
        }
    }
}

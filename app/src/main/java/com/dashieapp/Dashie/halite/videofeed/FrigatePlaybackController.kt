package com.dashieapp.Dashie.halite.videofeed

import com.dashieapp.Dashie.edition.ApiPaths

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Orchestrates Frigate recording playback within a VideoFeedCard.
 *
 * When opened:
 * 1. Auto-pins the feed so sidebar close doesn't dismiss it
 * 2. Switches the card to PLAYBACK mode (integrated layout)
 * 3. Adds a LIVE button overlay on the video area
 * 4. Fetches recording summary + events from Frigate via HA proxy
 */
class FrigatePlaybackController(
    private val context: Context,
    private val card: VideoFeedCard,
    private val data: VideoFeedData,
    private val haBaseUrl: String,
    private val authTokenProvider: () -> String,
    /** TV/d-pad open: strip the touch chrome (zoom, Go-to-Live pill, close,
     *  mute, restore, volume) and show a hold-select hint instead. */
    private val tvMode: Boolean = false
) {
    companion object {
        private const val TAG = "FrigatePlayback"
        private const val DEFAULT_TIMELINE_HOURS = 72  // initial window; older days lazy-load
        // D-pad scrub: move the cursor on every press but defer the actual clip
        // load until the user settles, so holding/repeated presses don't reload
        // on every step.
        private const val DPAD_SCRUB_COMMIT_MS = 400L
        // Anything this close to now IS live — same threshold seekToTimestamp uses.
        private const val LIVE_EDGE_GUARD_SEC = 10L
    }

    enum class State { LIVE, LOADING, PLAYBACK }

    private val handler = Handler(Looper.getMainLooper())
    private val historyLoader = FrigateHistoryLoader(context)
    /** Earliest second currently loaded into the timeline (extended by lazy load). */
    private var loadedStartSec = 0L
    private var state = State.LIVE
    private var overlay: FrigatePlaybackOverlay? = null
    private val liveBtn = FrigateLiveButton(context, card, passive = tvMode) { returnToLive() }
    private var events: List<FrigateEvent> = emptyList()
    private var recordingHours: List<RecordingHour> = emptyList()
    private var currentClipStartSec: Long = 0
    private var currentClipDurationMs: Long = 0
    private var positionUpdateRunnable: Runnable? = null

    // Callbacks
    var onCloseStrip: (() -> Unit)? = null
    var onReopenStrip: (() -> Unit)? = null
    var onAutoPin: (() -> Unit)? = null

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Rule id the controller is actively serving playback for. Used by the
     * manager to suppress duplicate trigger alerts for the same camera while
     * the user is already watching it in playback focal mode.
     */
    val activeRuleId: String get() = data.ruleId

    /** Camera entity id the controller is serving. */
    val activeCameraEntityId: String get() = data.cameraEntityId

    fun open() {
        if (state != State.LIVE) return
        state = State.LOADING
        Log.i(TAG, "Opening playback for camera: ${data.frigateCameraName}")

        // Auto-pin so sidebar close doesn't dismiss the feed
        onAutoPin?.invoke()

        // Close the video strip to make room
        onCloseStrip?.invoke()

        // Create the overlay (provides timeline + event strip components)
        overlay = FrigatePlaybackOverlay(context).apply {
            onScrubPositionChanged = { timestampSec -> seekToTimestamp(timestampSec) }
            onEventSelected = { event -> playEvent(event) }
            onPlayPauseToggle = { togglePlayPause() }
            onGoToLive = { returnToLive() }
            onDaySelected = { index -> snapToDay(index) }
            onActiveTimeChanged = { ts -> updateActiveDay(ts) }
            onSkipBack = { skipSeconds(-30) }
            onSkipForward = { skipSeconds(30) }
            wireVolumeControls(context)   // volume + mute → FrigatePlaybackVolumeChrome.kt
        }
        // Live RTSP audio reaches the speaker only when the player itself is
        // not muted; we surface mute as system-volume zero, so unmute the
        // player here once and let system volume govern.
        card.setPlayerMuted(false)

        // When a clip ends, auto-continue with the next segment (or return to live
        // if we've reached the current time). On error, stop and show nothing.
        card.onClipStateChanged = { clipState ->
            if (clipState == "ended") {
                val nextStart = currentClipStartSec + (currentClipDurationMs / 1000)
                    .coerceAtLeast(300)  // Fallback to 300s forward if duration unknown
                val nowSec = System.currentTimeMillis() / 1000
                if (nextStart >= nowSec - 10) {
                    // We've caught up to live
                    returnToLive()
                } else {
                    playClip(data.frigateCameraName, nextStart, nextStart + 300)
                }
            } else if (clipState == "error") {
                overlay?.hideClipProgress()
            }
        }

        // Wire the restore button to exit playback mode while keeping the card alive
        card.onRestoreFromPlayback = { restoreToFloating() }

        // Wire the close (X) button's dismiss handler to our close() flow.
        // The card's original onDismiss was captured at construction; if the card is
        // reused across multiple playback opens, the original callback may be stale.
        card.onDismissOverride = { close() }

        // Switch card to playback mode — rebuilds layout with integrated timeline + events
        card.playbackOverlay = overlay
        overlay?.tvMode = tvMode
        card.playbackTvMode = tvMode
        card.setPlaybackMode()

        // Wire up scroll listeners immediately so they work during LIVE mode too
        wireTimelineScrollListeners()

        // Show pause icon — live stream is playing
        overlay?.setPlaying(true)

        // Add the LIVE badge on the video. TV mode uses the passive variant
        // (shows "● LIVE" while live, hidden off-live); go-to-live is the
        // timeline's hold-select hint.
        liveBtn.attach()

        // Fetch the initial recording window (3 days); older days lazy-load.
        val now = System.currentTimeMillis() / 1000
        val after = now - (DEFAULT_TIMELINE_HOURS * 3600)
        loadedStartSec = after
        historyLoader.fetch(data.frigateCameraName, after, now) { hours, evts ->
            recordingHours = hours
            events = evts
            // A voice openAt() may already be playing a past-timestamp clip when this
            // async load lands — scroll the freshly-laid-out timeline to the CLIP, not
            // to now (scrolling to now fired the debounced scrub → seek(now) → live-edge
            // → returnToLive, killing the clip ~1s in; the 90s-of-clip-start guard then
            // absorbs the clip-position scroll's own scrub echo).
            val scrollTarget = if (currentClipStartSec > 0) currentClipStartSec else null
            overlay?.setRecordingData(hours, loadedStartSec, now, scrollTarget)
            overlay?.setEvents(evts)
            if (state == State.LOADING) state = State.LIVE
        }
        // Mark which of the last 7 days have footage (dim/disable the empty ones)
        // — cheap summary-only probe so day selection doesn't fail to load.
        val weekStart = now - 7 * 24 * 3600
        historyLoader.fetchSummary(data.frigateCameraName, weekStart, now) { weekHours ->
            overlay?.dayBar?.setAvailabilityFromSummary(weekHours)
        }

        // Start live time updates (keeps timeline at "now" while in live mode)
        startLiveTimeUpdates()
    }

    /**
     * Open playback already positioned at a wall-clock timestamp — the voice path
     * ("show me the pool camera from ten minutes ago"). `open()` always starts LIVE and its
     * scrub path is deliberately gated on user-gesture flags, so a programmatic jump drives
     * `playClip` directly rather than faking a scroll.
     *
     * Probes for footage first (retention gaps, camera offline): seeking into a hole would
     * otherwise show a black card with no explanation. `onNoFootage` lets the caller say so.
     */
    fun openAt(timestampSec: Long, onNoFootage: (() -> Unit)? = null) {
        open()

        val nowSec = System.currentTimeMillis() / 1000
        if (timestampSec >= nowSec - LIVE_EDGE_GUARD_SEC) {
            Log.i(TAG, "openAt: target is at the live edge — staying live")
            return
        }

        historyLoader.hasFootageAt(data.frigateCameraName, timestampSec) { hasFootage ->
            if (!hasFootage) {
                Log.w(TAG, "openAt: no footage for ${data.frigateCameraName} at $timestampSec")
                onNoFootage?.invoke()
                return@hasFootageAt
            }
            Log.i(TAG, "openAt: seeking ${data.frigateCameraName} to $timestampSec")
            // snapTimelineTo does the whole seek: stops the live ticker, scrolls the
            // timeline (with the auto-scroll guard so the listener doesn't fire a spurious
            // seek back), AND plays the clip at that instant. Calling playClip here too
            // fetched the same stitched MP4 twice.
            snapTimelineTo(timestampSec)
            updateActiveDay(timestampSec)
        }
    }

    // Callback to dismiss the card from overlay after closing playback
    var onDismissCard: (() -> Unit)? = null

    // Callback to restore the card to floating/maximized view (keeps card alive)
    var onRestoreToFloating: (() -> Unit)? = null

    fun close() {
        stopAllUpdates()

        if (state == State.PLAYBACK) {
            card.stopClipPlayback()
        }

        // Remove LIVE button
        liveBtn.remove()

        // Clear callbacks on the card so a future controller doesn't inherit stale refs
        card.onRestoreFromPlayback = null
        card.onDismissOverride = null

        // Remove card from overlay container and release it
        card.exitPlaybackMode()
        (card.parent as? ViewGroup)?.removeView(card)
        card.release()
        overlay = null

        // Dismiss and reopen the strip
        onDismissCard?.invoke()
        onReopenStrip?.invoke()

        state = State.LIVE
        Log.i(TAG, "Closed playback for camera: ${data.frigateCameraName}")
    }

    /**
     * Minimal teardown: exit playback mode on the card, stop all playback-specific
     * runnables, and release Frigate UI refs — WITHOUT firing any manager-side
     * callbacks (onRestoreToFloating / onReopenStrip / onDismissCard). The caller
     * keeps ownership of where the card goes next.
     *
     * Used when the manager is migrating the card somewhere else (e.g., screensaver
     * activation) and just needs the playback UI gone.
     */
    fun teardownInPlace() {
        stopAllUpdates()
        if (state == State.PLAYBACK) {
            card.stopClipPlayback()
        }
        liveBtn.remove()
        card.onRestoreFromPlayback = null
        card.onDismissOverride = null
        card.onClipStateChanged = null
        card.exitPlaybackMode()
        overlay = null
        state = State.LIVE
        Log.i(TAG, "Teardown-in-place for camera: ${data.frigateCameraName}")
    }

    /**
     * Exit Frigate playback but keep the card alive.
     *
     * Phase 2: the manager-side callback (onRestoreToFloating) now routes through
     * VideoFeedCoordinator.transitionFromPlayback, which handles drawer restoration
     * (including showing the strip if needed) atomically. No more ordering hack
     * here — we just tear down playback-specific state and fire the callback.
     */
    fun restoreToFloating() {
        stopAllUpdates()
        if (state == State.PLAYBACK) {
            card.stopClipPlayback()
        }
        liveBtn.remove()
        card.onRestoreFromPlayback = null  // Clear to avoid dangling ref
        card.onDismissOverride = null
        card.exitPlaybackMode()
        overlay = null
        state = State.LIVE
        onRestoreToFloating?.invoke()
        Log.i(TAG, "Restored playback to floating for camera: ${data.frigateCameraName}")
    }

    // ── D-pad control surface ───────────────────────────────────────
    // Public entry points for VideoFeedDpadController. Thin wrappers over the
    // private playback verbs (which already handle LIVE-vs-PLAYBACK state),
    // plus event-list navigation that owns its own selection index since the
    // events + overlay both live here.

    private var dpadScrubTargetSec = 0L
    private var dpadScrubbing = false
    private var dpadScrubForward = false
    private val dpadScrubCommitRunnable = Runnable { dpadCommitScrub() }

    /**
     * Tiered d-pad seek (positive = forward, negative = rewind, seconds). Moves
     * the timeline cursor immediately but debounces the clip load — a held/rapid
     * scrub only loads once, on settle.
     */
    fun dpadScrub(seconds: Int) {
        val nowSec = System.currentTimeMillis() / 1000
        val minSec = if (loadedStartSec > 0) loadedStartSec else nowSec - DEFAULT_TIMELINE_HOURS * 3600
        val base = if (dpadScrubbing) dpadScrubTargetSec else dpadCurrentTimeSec()
        dpadScrubTargetSec = (base + seconds).coerceIn(minSec, nowSec)
        dpadScrubForward = seconds > 0
        dpadScrubbing = true
        // Freeze auto-follow so it doesn't fight the manual cursor, then just
        // move the cursor + label (cheap — no media load).
        stopAllUpdates()
        isAutoScrolling = true
        overlay?.timelineBar?.scrollToTime(dpadScrubTargetSec)
        overlay?.timelineBar?.setTimeLabel(dpadScrubTargetSec)
        handler.removeCallbacks(dpadScrubCommitRunnable)
        handler.postDelayed(dpadScrubCommitRunnable, DPAD_SCRUB_COMMIT_MS)
    }

    private fun dpadCurrentTimeSec(): Long = if (state == State.PLAYBACK) {
        val player = card.getClipPlayer()
        if (player != null) currentClipStartSec + (player.currentPosition / 1000)
        else currentClipStartSec
    } else System.currentTimeMillis() / 1000

    private fun dpadCommitScrub() {
        if (!dpadScrubbing) return
        dpadScrubbing = false
        isAutoScrolling = false
        val target = dpadScrubTargetSec
        val nowSec = System.currentTimeMillis() / 1000
        // Snap to live only when catching up to the edge by scrubbing FORWARD —
        // not when stepping a few seconds back from live (which is within the
        // live-edge window but should still load a clip).
        if (dpadScrubForward && target >= nowSec - 10) {
            returnToLive()
            return
        }
        if (state == State.PLAYBACK) {
            val player = card.getClipPlayer()
            val offsetMs = (target - currentClipStartSec) * 1000
            val clipDur = player?.duration ?: 0L
            if (player != null && offsetMs in 0..clipDur) {
                player.seekTo(offsetMs)
                startPositionUpdates()
            } else {
                playClip(data.frigateCameraName, target, target + 300)
            }
        } else {
            stopLiveTimeUpdates()
            playClip(data.frigateCameraName, target, target + 300)
        }
    }

    /** Briefly reveal the timeline overlay during fullscreen scrubbing. */
    fun dpadShowFullscreenScrub() = card.showFullscreenScrubBar()

    fun dpadTogglePlayPause() = togglePlayPause()

    fun dpadReturnToLive() = returnToLive()

    // Zone focus rings: scrubber / main video display / clip strip.
    fun dpadFocusScrubber() = card.setPlaybackZoneFocus("scrubber")
    fun dpadFocusVideo() = card.setPlaybackZoneFocus("video")
    fun dpadFocusClips() {
        card.setPlaybackZoneFocus("clips")
        dpadEnterClips()
    }

    // Fullscreen video (hides timeline + clips + label bar).
    private var dpadFullscreen = false
    fun dpadEnterFullscreen() {
        dpadFullscreen = true
        card.setPlaybackFullscreen(true)
    }
    /** Exit fullscreen if active; returns true if it was fullscreen. */
    fun dpadExitFullscreen(): Boolean {
        if (!dpadFullscreen) return false
        dpadFullscreen = false
        card.setPlaybackFullscreen(false)
        card.setPlaybackZoneFocus("video")
        return true
    }
    fun dpadIsFullscreen(): Boolean = dpadFullscreen

    private var dpadEventIndex = -1

    /** Enter the clip list: highlight + scroll to the current (or first) event.
     *  Returns false if there are no events to navigate. */
    fun dpadEnterClips(): Boolean {
        val ids = overlay?.renderedEventIds() ?: return false
        if (ids.isEmpty()) return false
        if (dpadEventIndex !in ids.indices) dpadEventIndex = 0
        highlightClipAt(dpadEventIndex)
        return true
    }

    fun dpadClipNext() = moveClip(+1)
    fun dpadClipPrev() = moveClip(-1)

    /** Play the currently selected clip (no-op if none). */
    fun dpadClipSelect() {
        val id = overlay?.renderedEventIds()?.getOrNull(dpadEventIndex) ?: return
        events.find { it.id == id }?.let { playEvent(it) }
    }

    private fun moveClip(delta: Int) {
        val ids = overlay?.renderedEventIds() ?: return
        if (ids.isEmpty()) return
        dpadEventIndex = (dpadEventIndex + delta).coerceIn(0, ids.size - 1)
        highlightClipAt(dpadEventIndex)
    }

    private fun highlightClipAt(index: Int) {
        val id = overlay?.renderedEventIds()?.getOrNull(index) ?: return
        overlay?.highlightEvent(id)
        overlay?.scrollEventIntoView(id)
    }


    // ── Playback Controls ───────────────────────────────────────────

    private fun playClip(camera: String, startSec: Long, endSec: Long) {
        Log.i(TAG, "playClip REQUEST: camera=$camera, startSec=$startSec, endSec=$endSec")
        Thread {
            val credentials = getCredentials() ?: return@Thread
            val (baseUrl, token) = credentials
            val clipUrl = "${baseUrl.trimEnd('/')}${ApiPaths.HA}/frigate/$camera/$startSec/$endSec/clip.mp4"
            val authedUrl = "$clipUrl?token=$token"

            handler.post {
                currentClipStartSec = startSec
                state = State.PLAYBACK
                liveBtn.setLive(false)  // Show "Go to Live"
                card.startClipPlayback(authedUrl)
                overlay?.setPlaying(true)
                startPositionUpdates()
                Log.i(TAG, "Playing clip: $camera $startSec-$endSec")
            }
        }.start()
    }

    private fun playEvent(event: FrigateEvent) {
        Thread {
            val credentials = getCredentials() ?: return@Thread
            val (baseUrl, token) = credentials
            // Use the timeline (camera/start/end) endpoint rather than the per-event
            // (event/{id}) endpoint. Frigate's per-event clip endpoint sometimes
            // returns truncated/malformed MP4s ("Loading finished before preparation
            // is complete", contentIsMalformed=true) when the event's clip file
            // hasn't been finalized yet or was never stitched. The timeline endpoint
            // stitches from recordings on demand and is reliably well-formed.
            val startSec = event.startTime.toLong()
            val endSec = event.endTime?.toLong() ?: (System.currentTimeMillis() / 1000)
            val clipUrl = "${baseUrl.trimEnd('/')}${ApiPaths.HA}/frigate/${event.camera}/$startSec/$endSec/clip.mp4"
            val authedUrl = "$clipUrl?token=$token"

            handler.post {
                currentClipStartSec = startSec
                val durationMs = (endSec - startSec) * 1000
                state = State.PLAYBACK
                liveBtn.setLive(false)
                // Show clip timer immediately from event metadata (no wait for ExoPlayer)
                overlay?.updateClipTimer(0, durationMs)
                card.startClipPlayback(authedUrl)
                overlay?.setPlaying(true)
                overlay?.highlightEvent(event.id)
                currentClipDurationMs = durationMs
                startPositionUpdates()
                Log.i(TAG, "Playing event clip: ${event.id} (${event.label}) via timeline $startSec-$endSec duration=${durationMs}ms")
            }
        }.start()
    }

    private fun seekToTimestamp(timestampSec: Long) {
        Log.d(TAG, "seekToTimestamp: ts=$timestampSec, state=$state, isAutoScrolling=$isAutoScrolling, userDidScroll=$userDidScroll, userIsScrolling=$userIsScrolling, currentClipStart=$currentClipStartSec")
        if (isAutoScrolling) {
            Log.d(TAG, "  → ignored (auto-scroll)")
            return
        }
        // Defense against pixel-rounding feedback loop: if we're in PLAYBACK and the
        // incoming timestamp is within 90 seconds of the currently playing clip's start,
        // treat it as an auto-scroll artifact (not user-initiated) and ignore.
        // Pixel rounding at low zoom can lose up to ~60s per iteration, which accumulates.
        if (state == State.PLAYBACK && currentClipStartSec > 0 &&
            kotlin.math.abs(timestampSec - currentClipStartSec) < 90) {
            Log.d(TAG, "  → ignored (within 90s of current clip start — likely rounding loop)")
            return
        }
        val nowSec = System.currentTimeMillis() / 1000
        // Scrubbing within 10s of "now" → go live (don't load a clip that'd just buffer up to now)
        val isAtLiveEdge = timestampSec >= nowSec - 10

        if (state == State.LIVE || state == State.LOADING) {
            if (!userDidScroll) {
                Log.d(TAG, "  → ignored (not user-initiated, state=$state)")
                return
            }
            userDidScroll = false
            if (isAtLiveEdge) return  // Already live
            stopLiveTimeUpdates()
            Log.i(TAG, "  → starting clip playback from LIVE at $timestampSec")
            playClip(data.frigateCameraName, timestampSec, timestampSec + 300)
        } else if (state == State.PLAYBACK) {
            if (isAtLiveEdge) {
                Log.i(TAG, "  → scrubbed to live edge, returning to live")
                userDidScroll = false
                returnToLive()
                return
            }
            val offsetMs = (timestampSec - currentClipStartSec) * 1000
            if (offsetMs >= 0 && offsetMs < card.getClipDurationMs()) {
                card.seekClipTo(offsetMs)
            } else {
                playClip(data.frigateCameraName, timestampSec, timestampSec + 300)
            }
        }
    }

    private fun returnToLive() {
        stopAllUpdates()
        if (state == State.PLAYBACK) {
            card.stopClipPlayback()  // Stops clip and restarts live stream
        }
        state = State.LIVE
        liveStreamPaused = false
        liveBtn.setLive(true)  // Show "● LIVE"
        overlay?.setPlaying(true)  // Live stream is always playing → show pause icon
        overlay?.updateClipTimer(0, 0)
        // Scroll timeline to current time
        val nowSec = System.currentTimeMillis() / 1000
        overlay?.timelineBar?.scrollToTime(nowSec)
        // Start live time update (keeps the center indicator at "now")
        startLiveTimeUpdates()
        Log.i(TAG, "Returned to live (staying in playback mode)")
    }

    private fun skipSeconds(seconds: Int) {
        // Compute the target wall-clock timestamp based on current state.
        val currentTs: Long = when (state) {
            State.PLAYBACK -> {
                val player = card.getClipPlayer()
                if (player != null) currentClipStartSec + (player.currentPosition / 1000)
                else currentClipStartSec
            }
            else -> System.currentTimeMillis() / 1000
        }
        val nowSec = System.currentTimeMillis() / 1000
        val targetTs = (currentTs + seconds).coerceAtMost(nowSec)
        if (targetTs == currentTs) return  // At the live edge skipping forward — no-op

        // Always update the timeline cursor so the UI reflects the skip.
        isAutoScrolling = true
        overlay?.timelineBar?.scrollToTime(targetTs)
        handler.postDelayed({ isAutoScrolling = false }, 700)

        // If skipping forward lands within 10s of now, just return to live.
        if (targetTs >= nowSec - 10) {
            if (state != State.LIVE) returnToLive()
            return
        }

        if (state == State.PLAYBACK) {
            val player = card.getClipPlayer()
            val offsetMs = (targetTs - currentClipStartSec) * 1000
            val clipDur = player?.duration ?: 0L
            if (player != null && offsetMs in 0..clipDur) {
                player.seekTo(offsetMs)
            } else {
                playClip(data.frigateCameraName, targetTs, targetTs + 300)
            }
        } else {
            // From LIVE/LOADING: start a new clip at the target time
            stopLiveTimeUpdates()
            playClip(data.frigateCameraName, targetTs, targetTs + 300)
        }
        Log.i(TAG, "Skip $seconds: ${currentTs}→${targetTs}, state=$state")
    }

    private var liveStreamPaused = false

    private fun togglePlayPause() {
        if (state == State.LIVE || state == State.LOADING) {
            if (liveStreamPaused) {
                card.resumeLiveStream()
                overlay?.setPlaying(true)
            } else {
                card.pauseLiveStream()
                overlay?.setPlaying(false)
            }
            liveStreamPaused = !liveStreamPaused
            return
        }
        if (state != State.PLAYBACK) return
        val player = card.getClipPlayer() ?: return
        if (player.isPlaying) {
            player.pause()
            overlay?.setPlaying(false)
            stopAllUpdates()
        } else {
            player.play()
            overlay?.setPlaying(true)
            startPositionUpdates()
        }
    }

    // ── Position Updates ────────────────────────────────────────────

    private var userIsScrolling = false
    private var userDidScroll = false    // Stays true from touch-down until debounced callback processes it
    private var isAutoScrolling = false  // Prevents onTimeSelected from firing during auto-scroll

    private fun wireTimelineScrollListeners() {
        overlay?.timelineBar?.onUserScrollStart = {
            Log.d(TAG, "onUserScrollStart: stopping live updates")
            userIsScrolling = true
            userDidScroll = true
            stopLiveTimeUpdates()
        }
        overlay?.timelineBar?.onUserScrollEnd = {
            Log.d(TAG, "onUserScrollEnd: userDidScroll=$userDidScroll")
            userIsScrolling = false
        }
    }

    private fun startPositionUpdates() {
        stopAllUpdates()
        wireTimelineScrollListeners()

        positionUpdateRunnable = object : Runnable {
            override fun run() {
                if (state == State.PLAYBACK) {
                    val posMs = card.getClipPositionMs()
                    val exoDur = card.getClipDurationMs()
                    val durMs = if (exoDur > 0) exoDur else currentClipDurationMs
                    val currentTimeSec = currentClipStartSec + (posMs / 1000)
                    overlay?.updateClipTimer(posMs, durMs)
                    if (!userIsScrolling && posMs > 0) {
                        isAutoScrolling = true
                        overlay?.timelineBar?.scrollToTime(currentTimeSec)
                        // Must outlast the 500ms scroll-debounce in FrigateTimelineBar
                        // so auto-scroll doesn't fire a spurious seekToTimestamp.
                        handler.postDelayed({ isAutoScrolling = false }, 700)
                    } else if (!userIsScrolling && currentClipStartSec > 0) {
                        // Clip is loading — show its start time instead of wall-clock time
                        // so the label doesn't flicker to "now" before settling.
                        // Guard with isAutoScrolling so the scroll doesn't trigger a
                        // spurious seekToTimestamp loop (scroll → debounce → new clip →
                        // loops with pixel-rounding error, going backwards a minute each time).
                        isAutoScrolling = true
                        overlay?.timelineBar?.setTimeLabel(currentClipStartSec)
                        overlay?.timelineBar?.scrollToTime(currentClipStartSec)
                        handler.postDelayed({ isAutoScrolling = false }, 700)
                        Log.d(TAG, "posTick loading: scrolled to clipStart=$currentClipStartSec")
                    }
                }
                if (positionUpdateRunnable != null) {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(positionUpdateRunnable!!)
    }

    private var liveTimeRunnable: Runnable? = null

    private fun startLiveTimeUpdates() {
        stopAllUpdates()
        Log.i(TAG, "startLiveTimeUpdates() called")
        liveTimeRunnable = object : Runnable {
            override fun run() {
                val tb = overlay?.timelineBar
                // Tick the time label on the timeline so it visibly advances with real time.
                tb?.updateLiveTimeLabel()
                // Make sure the progress strip is hidden in live mode.
                overlay?.hideClipProgress()

                // Scroll to "now" only when actually live and not being interacted with.
                if ((state == State.LIVE || state == State.LOADING) && !userIsScrolling && !userDidScroll) {
                    val nowSec = System.currentTimeMillis() / 1000
                    isAutoScrolling = true
                    overlay?.timelineBar?.extendRangeToNow()
                    overlay?.timelineBar?.scrollToTime(nowSec)
                    handler.postDelayed({ isAutoScrolling = false }, 100)
                }
                // Reschedule unless someone called stopLiveTimeUpdates() (which nulls this)
                if (liveTimeRunnable != null) {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(liveTimeRunnable!!, 1000)
    }

    private fun stopLiveTimeUpdates() {
        liveTimeRunnable?.let { handler.removeCallbacks(it) }
        liveTimeRunnable = null
    }

    private fun stopAllUpdates() {
        positionUpdateRunnable?.let { handler.removeCallbacks(it) }
        positionUpdateRunnable = null
        stopLiveTimeUpdates()
    }

    // ── Day bar (history navigation) ─────────────────────────────────

    private var dpadDayIndex = 0
    private var activeDayIndex = 0

    /** Active day (under the cursor) changed — orange it + scroll the clip list
     *  there. Fires on any cursor move, so manual scrubbing updates it too. */
    private fun updateActiveDay(ts: Long) {
        val bar = overlay?.dayBar ?: return
        val index = bar.dayIndexForTime(ts)
        if (index == activeDayIndex) return
        activeDayIndex = index
        bar.setActiveDay(index)
        val dayStart = bar.dayStartSec(index)
        overlay?.scrollClipsToDay(dayStart, dayStart + 24 * 3600)
    }

    /** Snap the timeline to the start of day [index] (0 = today → live edge),
     *  lazy-loading older days that fall before the loaded window. */
    fun snapToDay(index: Int) {
        if (index <= 0) {
            returnToLive()
            return
        }
        val dayStart = overlay?.dayBar?.dayStartSec(index) ?: return
        if (dayStart < loadedStartSec) {
            historyLoader.fetch(data.frigateCameraName, dayStart, loadedStartSec) { hours, evts ->
                recordingHours = hours + recordingHours
                events = (evts + events).distinctBy { it.id }
                loadedStartSec = dayStart
                overlay?.timelineBar?.extendRangeToStart(dayStart)
                overlay?.timelineBar?.setRecordingData(recordingHours)
                overlay?.timelineBar?.setEvents(events)
                overlay?.setEvents(events)
                snapTimelineTo(snapTargetForDay(dayStart))
            }
        } else {
            snapTimelineTo(snapTargetForDay(dayStart))
        }
    }

    /** Where to land when a day is selected: noon if that hour has a recording,
     *  otherwise the earliest recorded hour of the day (noon if nothing recorded). */
    private fun snapTargetForDay(dayStart: Long): Long {
        val noon = dayStart + 12 * 3600
        val dayEnd = dayStart + 24 * 3600
        val nowSec = System.currentTimeMillis() / 1000
        val dayHours = recordingHours.filter { it.hour in dayStart until dayEnd && it.duration >= 60 }
        if (dayHours.isEmpty()) return noon.coerceAtMost(nowSec)
        val noonBucket = (noon / 3600) * 3600
        val target = if (dayHours.any { it.hour == noonBucket }) noon else dayHours.minOf { it.hour }
        return target.coerceAtMost(nowSec)
    }

    private fun snapTimelineTo(ts: Long) {
        stopAllUpdates()
        isAutoScrolling = true
        overlay?.timelineBar?.scrollToTime(ts)
        overlay?.timelineBar?.setTimeLabel(ts)
        handler.postDelayed({ isAutoScrolling = false }, 700)
        // Play footage from the snapped time so the video follows the day jump.
        val nowSec = System.currentTimeMillis() / 1000
        if (ts < nowSec - 10) playClip(data.frigateCameraName, ts, ts + 300)
    }

    // D-pad day-bar focus + selection.
    fun dpadFocusDays() {
        dpadDayIndex = activeDayIndex  // start the cursor on the day in view
        card.setPlaybackZoneFocus("days"); overlay?.dayBar?.highlightDay(dpadDayIndex)
    }
    fun dpadClearDayFocus() = overlay?.dayBar?.highlightDay(null)
    fun dpadDayNext() = moveDay(+1)
    fun dpadDayPrev() = moveDay(-1)
    fun dpadDaySelect() { if (overlay?.dayBar?.isAvailable(dpadDayIndex) == true) snapToDay(dpadDayIndex) }
    private fun moveDay(delta: Int) {
        val bar = overlay?.dayBar ?: return
        // Skip over days with no footage so the cursor only lands on selectable ones.
        var next = dpadDayIndex + delta
        while (next in 0 until bar.count) {
            if (bar.isAvailable(next)) {
                dpadDayIndex = next
                bar.highlightDay(next)
                return
            }
            next += delta
        }
    }

    // ── Auth ─────────────────────────────────────────────────────────

    private fun getCredentials(): Pair<String, String>? {
        val halitePrefs = com.dashieapp.Dashie.halite.HalitePreferences(context)
        val creds = com.dashieapp.Dashie.halite.HaTokenExtractor.getValidCredentialsSync(halitePrefs)
        return if (creds != null) Pair(creds.first, creds.second) else null
    }
}

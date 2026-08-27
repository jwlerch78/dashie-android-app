package com.dashieapp.Dashie.halite.videofeed

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dashieapp.Dashie.R

/**
 * Provides the UI components for Frigate recording playback.
 * Not a View itself — exposes buildTimelineRow() and buildEventStrip()
 * which the controller places into the layout.
 */
class FrigatePlaybackOverlay(val context: Context) {

    companion object {
        private const val TAG = "FrigateOverlay"
        private const val TIMELINE_HEIGHT_DP = 72
        private const val CLIP_STRIP_WIDTH_DP = 240
        private const val THUMBNAIL_SIZE_DP = 100
        private const val PLAY_BUTTON_SIZE_DP = 40
    }

    /** TV/d-pad mode: hide touch chrome (zoom, volume) and show a hold-select hint. */
    var tvMode = false

    // Callbacks
    var onScrubPositionChanged: ((timestampSec: Long) -> Unit)? = null
    var onEventSelected: ((FrigateEvent) -> Unit)? = null
    var onPlayPauseToggle: (() -> Unit)? = null

    // UI components (created lazily)
    var timelineBar: FrigateTimelineBar? = null
        private set
    private var playPauseButton: ImageView? = null
    private var clipStrip: LinearLayout? = null
    private var isPlaying = false

    // ── Public API ──────────────────────────────────────────────────

    // Recording summary + last-shown events. Playback uses the timeline
    // (recordings) endpoint, so whether a clip will play depends on these
    // recordings — not on the event's per-event has_clip flag. We keep both
    // so playability (click gate + no-recording icon) reflects recordings,
    // and we re-render the strip whichever of the two async loads lands last.
    private var recordingHours: List<RecordingHour> = emptyList()
    private var lastEvents: List<FrigateEvent> = emptyList()

    fun setRecordingData(hours: List<RecordingHour>, afterSec: Long, beforeSec: Long, initialScrollSec: Long? = null) {
        recordingHours = hours
        timelineBar?.setTimeRange(afterSec, beforeSec, initialScrollSec)
        timelineBar?.setRecordingData(hours)
        // Recording data may arrive after events; refresh the strip so the
        // no-recording icons reflect actual recording coverage.
        if (lastEvents.isNotEmpty()) populateClipStrip(lastEvents)
    }

    fun setEvents(events: List<FrigateEvent>) {
        lastEvents = events
        timelineBar?.setEvents(events)
        populateClipStrip(events)
    }

    /**
     * Whether Frigate has a recording covering this event's time. Playback
     * stitches from continuous recordings via the timeline endpoint, so this
     * — not the event's has_clip flag — is what decides whether a clip will
     * actually play. Continuous-recording cameras emit snapshot-only events
     * (has_clip=false) that are nonetheless fully playable from recordings.
     */
    private fun hasRecordingFor(event: FrigateEvent): Boolean {
        if (event.hasClip) return true  // per-event clip definitely exists
        if (recordingHours.isEmpty()) return false
        val hourBucket = (event.startTime.toLong() / 3600L) * 3600L
        return recordingHours.any { it.hour == hourBucket && it.duration > 0 }
    }

    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        playPauseButton?.setImageDrawable(
            ContextCompat.getDrawable(context,
                if (playing) R.drawable.ic_pause else R.drawable.ic_play
            )
        )
    }

    fun updatePlaybackPosition(timestampSec: Long) {
        timelineBar?.scrollToTime(timestampSec)
    }

    private var highlightedEventId: String? = null
    private val eventRowMap = mutableMapOf<String, View>()
    private var eventScrollView: ScrollView? = null

    /** Rendered event ids in display order — the navigable set for d-pad clip selection. */
    fun renderedEventIds(): List<String> = eventRowMap.keys.toList()

    /** Keep the selected row visible without re-anchoring to the top: scroll only
     *  when it crosses the viewport's top/bottom edge. */
    fun scrollEventIntoView(eventId: String) {
        val row = eventRowMap[eventId] ?: return
        val sv = eventScrollView ?: return
        sv.post {
            val pad = dpToPx(8)
            val viewTop = sv.scrollY
            val viewBottom = viewTop + sv.height
            when {
                row.top < viewTop + pad -> sv.smoothScrollTo(0, (row.top - pad).coerceAtLeast(0))
                row.bottom > viewBottom - pad -> sv.smoothScrollTo(0, row.bottom - sv.height + pad)
            }
        }
    }

    fun highlightEvent(eventId: String) {
        // Remove highlight from previous
        highlightedEventId?.let { prevId ->
            eventRowMap[prevId]?.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadius = dpToPx(4).toFloat()
            }
        }
        // Blue highlight for the selected clip (distinct from the orange zone ring).
        highlightedEventId = eventId
        eventRowMap[eventId]?.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF153A5C.toInt())               // blue-tinted fill
            setStroke(dpToPx(3), 0xFF2196F3.toInt())   // blue border
            cornerRadius = dpToPx(4).toFloat()
        }
    }

    // ── Build Components ────────────────────────────────────────────

    // Skip callbacks
    var onSkipBack: (() -> Unit)? = null
    var onSkipForward: (() -> Unit)? = null

    // Volume callbacks (0–10 system volume scale via VolumeManager)
    var onSetVolume: ((Int) -> Unit)? = null
    var onGetVolume: (() -> Int)? = null
    var onToggleMute: (() -> Boolean)? = null  // Returns new mute state
    var onGetMuted: (() -> Boolean)? = null   // Returns current player-mute state

    private var videoControlsOverlay: FrameLayout? = null
    private var controlsAutoHideRunnable: Runnable? = null
    private val autoHideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val CONTROLS_AUTO_HIDE_MS = 5000L

    /**
     * Semi-transparent overlay on the video with centered play/pause and skip controls.
     * Tap video to show, tap outside controls or wait 3s to hide.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun buildVideoControlsOverlay(): FrameLayout {
        val overlay = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x66000000.toInt()) // Semi-transparent dark
            visibility = View.GONE  // Hidden by default
            isClickable = true
            // Tap on the dimmed area hides the controls
            setOnClickListener { hideVideoControls() }
        }

        // Center row: [Skip Back] [Play/Pause] [Skip Forward]
        val controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }

        val playSize = dpToPx(120)  // Large center play button
        val skipSize = dpToPx(72)   // Slightly smaller skip buttons
        val skipMargin = dpToPx(40)

        // Skip back 30s
        controlsRow.addView(ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_skip_back_30))
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(skipSize, skipSize).apply {
                marginEnd = skipMargin
                gravity = Gravity.CENTER_VERTICAL
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onSkipBack?.invoke()
                scheduleAutoHide()
            }
        })

        // Play/Pause button (large, center)
        playPauseButton = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(playSize, playSize).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_play))
            setColorFilter(Color.WHITE)
            val pad = dpToPx(16)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x88000000.toInt())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onPlayPauseToggle?.invoke()
                scheduleAutoHide()
            }
        }
        controlsRow.addView(playPauseButton)

        // Skip forward 30s
        controlsRow.addView(ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_skip_forward_30))
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(skipSize, skipSize).apply {
                marginStart = skipMargin
                gravity = Gravity.CENTER_VERTICAL
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onSkipForward?.invoke()
                scheduleAutoHide()
            }
        })

        overlay.addView(controlsRow)

        // ── Volume +/- column (skipped in TV mode — use the remote's volume) ──
        if (!tvMode) {
        val volBtnSize = dpToPx(54)
        val volBtnPad = dpToPx(9)

        val volumeColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                // Leave room for the always-visible mute button above (~48dp + margin)
                topMargin = dpToPx(68)
                marginEnd = dpToPx(12)
            }
            isClickable = true  // Don't fall through to dismiss
        }

        // + button
        volumeColumn.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_add)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(volBtnSize, volBtnSize)
            setPadding(volBtnPad, volBtnPad, volBtnPad, volBtnPad)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val cur = onGetVolume?.invoke() ?: 5
                val newLevel = (cur + 1).coerceAtMost(10)
                onSetVolume?.invoke(newLevel)
                updateVolumeDisplay(newLevel)
                scheduleAutoHide()
            }
        })

        // Volume level number (0–10)
        volumeLevelLabel = TextView(context).apply {
            text = "10"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                volBtnSize, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(4)
                bottomMargin = dpToPx(4)
            }
        }
        volumeColumn.addView(volumeLevelLabel)

        // - button
        volumeColumn.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_remove)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(volBtnSize, volBtnSize)
            setPadding(volBtnPad, volBtnPad, volBtnPad, volBtnPad)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val cur = onGetVolume?.invoke() ?: 5
                val newLevel = (cur - 1).coerceAtLeast(0)
                onSetVolume?.invoke(newLevel)
                updateVolumeDisplay(newLevel)
                scheduleAutoHide()
            }
        })

        updateVolumeDisplay(onGetVolume?.invoke() ?: 5)

        overlay.addView(volumeColumn)
        }  // end if (!tvMode)

        // ── Progress strip (bottom of video) ────────────────────
        overlay.addView(buildProgressStrip())

        videoControlsOverlay = overlay
        return overlay
    }

    private var progressStrip: LinearLayout? = null
    private var progressBar: android.widget.ProgressBar? = null
    private var progressPositionLabel: TextView? = null
    private var progressDurationLabel: TextView? = null

    /** Horizontal progress strip at the bottom of the video overlay. */
    private fun buildProgressStrip(): LinearLayout {
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                val m = dpToPx(12)
                setMargins(m, m, m, m)
            }
            setBackgroundColor(0x99000000.toInt())
            val pad = dpToPx(8)
            setPadding(pad, pad, pad, pad)
            isClickable = true  // Don't fall through to dismiss
            visibility = View.GONE  // Shown once clip duration is known
        }

        progressPositionLabel = TextView(context).apply {
            text = "0s"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dpToPx(8) }
        }
        strip.addView(progressPositionLabel)

        progressBar = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(8), 1f)
        }
        strip.addView(progressBar)

        progressDurationLabel = TextView(context).apply {
            text = "0s"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dpToPx(8) }
        }
        strip.addView(progressDurationLabel)

        progressStrip = strip
        return strip
    }

    /** Build a standalone circular mute toggle to live outside the video overlay (always visible). */
    fun buildMuteButton(circleBg: Int, iconTint: Int, sizePx: Int): ImageView {
        val btn = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_volume_up))
            setColorFilter(iconTint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = sizePx / 5
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(circleBg)
            }
            elevation = 10f
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onToggleMute?.invoke()
                updateMuteIcon()
            }
        }
        volumeMuteButton = btn
        updateMuteIcon()
        updateVolumeDisplay(onGetVolume?.invoke() ?: 5)
        return btn
    }

    private var volumeLevelLabel: TextView? = null
    private var volumeMuteButton: ImageView? = null

    /** Update the volume number label (system volume 0–10). */
    private fun updateVolumeDisplay(level: Int) {
        volumeLevelLabel?.text = "$level"
    }

    /** Update the mute icon based on the video player's mute state (not system volume). */
    private fun updateMuteIcon() {
        val muted = onGetMuted?.invoke() ?: false
        volumeMuteButton?.setImageDrawable(
            ContextCompat.getDrawable(context,
                if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
            )
        )
    }

    fun showVideoControls() {
        videoControlsOverlay?.visibility = View.VISIBLE
        scheduleAutoHide()
    }

    fun hideVideoControls() {
        videoControlsOverlay?.visibility = View.GONE
        controlsAutoHideRunnable?.let { autoHideHandler.removeCallbacks(it) }
    }

    fun toggleVideoControls() {
        if (videoControlsOverlay?.visibility == View.VISIBLE) {
            hideVideoControls()
        } else {
            showVideoControls()
        }
    }

    private fun scheduleAutoHide() {
        controlsAutoHideRunnable?.let { autoHideHandler.removeCallbacks(it) }
        controlsAutoHideRunnable = Runnable { hideVideoControls() }
        autoHideHandler.postDelayed(controlsAutoHideRunnable!!, CONTROLS_AUTO_HIDE_MS)
    }

    /** Left section: [Timeline] [Zoom controls] — play/skip moved to video overlay */
    fun buildTimelineSection(): LinearLayout {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1A1A1A.toInt())
            val pad = dpToPx(4)
            setPadding(pad, pad, pad, pad)
        }

        // Timeline bar (takes remaining width)
        timelineBar = FrigateTimelineBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(TIMELINE_HEIGHT_DP), 1f)
            onTimeSelected = { ts -> onScrubPositionChanged?.invoke(ts) }
            // Fires continuously as the cursor moves (scrub, snap, live-follow) —
            // drives the active-day indicator + clip-list day scroll.
            onTimeChanged = { ts -> onActiveTimeChanged?.invoke(ts) }
        }
        section.addView(timelineBar)

        // TV mode: replace the touch zoom controls with a "hold select → Go to
        // Live" hint (modeled on the calendar's TV hold hint).
        if (tvMode) {
            section.addView(FrigatePlaybackHints.holdToLive(context))
            return section
        }

        // Zoom controls (side by side: zoom out, zoom in)
        val zoomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT
            )
            val pad = dpToPx(6)
            setPadding(pad, 0, pad, 0)
        }

        val zoomBtnSize = dpToPx(40)

        // Zoom out (magnifying glass -)
        zoomRow.addView(ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_zoom_out))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(zoomBtnSize, zoomBtnSize).apply {
                marginEnd = dpToPx(8)
                gravity = Gravity.CENTER_VERTICAL
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { timelineBar?.zoomOut() }
        })

        // Zoom in (magnifying glass +)
        zoomRow.addView(ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_zoom_in))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(zoomBtnSize, zoomBtnSize).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { timelineBar?.zoomIn() }
        })

        section.addView(zoomRow)

        return section
    }

    /** Right section of timeline row — now empty placeholder matching event strip width. */
    fun buildClipInfoSection(): LinearLayout {
        return LinearLayout(context).apply {
            setBackgroundColor(0xFF151515.toInt())
            layoutParams = LinearLayout.LayoutParams(dpToPx(CLIP_STRIP_WIDTH_DP), LinearLayout.LayoutParams.MATCH_PARENT)
        }
    }

    /** Vertical scrolling event clip strip with thumbnails + details. */
    fun buildEventStrip(): ScrollView {
        val scroll = object : ScrollView(context) {
            @android.annotation.SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
                when (ev.action) {
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_MOVE ->
                        parent?.requestDisallowInterceptTouchEvent(true)
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        parent?.requestDisallowInterceptTouchEvent(false)
                }
                return super.onTouchEvent(ev)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(0xFF151515.toInt())
            layoutParams = LinearLayout.LayoutParams(dpToPx(CLIP_STRIP_WIDTH_DP), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        clipStrip = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(4)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(clipStrip)
        eventScrollView = scroll
        return scroll
    }

    // Callbacks for clip info row
    var onGoToLive: (() -> Unit)? = null

    // Day bar (history navigation below the scrubber).
    var dayBar: FrigateDayBar? = null
        private set
    var onDaySelected: ((Int) -> Unit)? = null
    /** Timeline cursor moved (any cause) — used to track the active day. */
    var onActiveTimeChanged: ((Long) -> Unit)? = null

    /** Scroll the clip list to the first event within the given day. */
    fun scrollClipsToDay(dayStartSec: Long, dayEndSec: Long) {
        val ev = lastEvents.firstOrNull { it.startTime.toLong() in dayStartSec until dayEndSec } ?: return
        val row = eventRowMap[ev.id] ?: return
        val sv = eventScrollView ?: return
        sv.post { sv.smoothScrollTo(0, (row.top - dpToPx(8)).coerceAtLeast(0)) }
    }

    /** Build the day-button row (Today / Yesterday / M/D…). */
    fun buildDayBar(): View {
        val bar = FrigateDayBar(context).apply {
            onDaySelected = { idx -> this@FrigatePlaybackOverlay.onDaySelected?.invoke(idx) }
        }
        dayBar = bar
        return bar.build()
    }

    fun updateClipTimer(positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) {
            // No known duration — hide the progress strip
            progressStrip?.visibility = View.GONE
            return
        }
        // Cap position at duration so the label doesn't overrun the clip length
        val pos = positionMs.coerceIn(0L, durationMs)
        val posSec = (pos / 1000).toInt()
        val durSec = (durationMs / 1000).toInt()
        progressPositionLabel?.text = formatDuration(posSec)
        progressDurationLabel?.text = formatDuration(durSec)
        progressBar?.progress = ((pos * 1000) / durationMs).toInt()
        progressStrip?.visibility = View.VISIBLE
    }

    /** Hide the progress strip (e.g. when the clip has ended). */
    fun hideClipProgress() {
        progressStrip?.visibility = View.GONE
    }

private fun formatDuration(totalSec: Int): String {
        val min = totalSec / 60
        val sec = totalSec % 60
        return when {
            min >= 10 -> {
                val rounded = if (sec >= 30) min + 1 else min
                "${rounded}m"
            }
            min >= 1 -> "${min}m ${sec}s"
            else -> "${sec}s"
        }
    }

    // ── Event Clip Strip ────────────────────────────────────────────

    private fun populateClipStrip(events: List<FrigateEvent>) {
        clipStrip?.removeAllViews()
        if (events.isEmpty()) {
            clipStrip?.addView(TextView(context).apply {
                text = "No events"
                setTextColor(0xFF666666.toInt())
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dpToPx(8), dpToPx(16), dpToPx(8), dpToPx(16))
            })
            return
        }
        eventRowMap.clear()
        for (event in events.take(30)) {
            val row = createClipRow(event)
            eventRowMap[event.id] = row
            clipStrip?.addView(row)
        }
    }

    private fun createClipRow(event: FrigateEvent): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(4) }
            background = GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadius = dpToPx(4).toFloat()
            }
            val pad = dpToPx(3)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (hasRecordingFor(event)) {
                    onEventSelected?.invoke(event)
                } else {
                    // No recording covers this event's time (e.g. Frigate
                    // `record.enabled: false` on this camera). Give clear
                    // feedback instead of a silent failure / 401.
                    android.widget.Toast.makeText(
                        context, "Clip not recorded on Frigate", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // Thumbnail with timestamp overlay — takes 80% of row width
        val thumbContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(THUMBNAIL_SIZE_DP), 4f).apply {
                marginEnd = dpToPx(4)
            }
        }

        val thumbnailView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(0xFF2A2A2A.toInt())
                cornerRadius = dpToPx(4).toFloat()
            }
        }
        thumbContainer.addView(thumbnailView)
        FrigateClipThumbnail.load(context, event, thumbnailView)

        // Timestamp overlaid on thumbnail (bottom center, semi-transparent bg)
        val timeFormat = java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.getDefault())
        val timeOverlay = TextView(context).apply {
            text = timeFormat.format(java.util.Date(event.startTime.toLong() * 1000))
            setTextColor(Color.WHITE)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val padH = dpToPx(4)
            val padV = dpToPx(1)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(0xAA000000.toInt())
                cornerRadius = dpToPx(2).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(2)
            }
        }
        thumbContainer.addView(timeOverlay)
        row.addView(thumbContainer)

        // Right side: person icon + duration stacked (20% of width)
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        // Detection type icon (person, car, etc.)
        val iconRes = when (event.label) {
            "person" -> R.drawable.ic_person_detection
            else -> R.drawable.ic_person_detection // TODO: add car, dog icons
        }
        details.addView(ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, iconRes))
            setColorFilter(0xFFFF9800.toInt()) // Orange for person
            val iconSize = dpToPx(20)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        })

        // Duration
        val endSec = event.endTime?.toLong() ?: (System.currentTimeMillis() / 1000)
        val durationSec = endSec - event.startTime.toLong()
        details.addView(TextView(context).apply {
            text = formatDuration(durationSec.toInt())
            setTextColor(0xFF888888.toInt())
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
        })

        // No-recording indicator: grey video-camera icon with diagonal
        // strikethrough, shown when Frigate has the event (detection fired) but
        // no recording covers its time (record disabled on this camera). Based
        // on recording coverage, NOT has_clip — continuous-recording cameras
        // emit snapshot-only events that are still playable. Mirrors the
        // strikethrough pattern in VideoFeedStripRenderer.buildToggleRow.
        if (!hasRecordingFor(event)) {
            val iconSize = dpToPx(18)
            val iconFrame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dpToPx(6)
                }
            }
            iconFrame.addView(ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setImageResource(R.drawable.ic_videocam)
                setColorFilter(0xFF888888.toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
            iconFrame.addView(View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dpToPx(2), iconSize).apply {
                    gravity = Gravity.CENTER
                }
                setBackgroundColor(0xFF888888.toInt())
                rotation = -45f
            })
            details.addView(iconFrame)
        }

        row.addView(details)
        return row
    }

    private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()
}

package com.dashieapp.Dashie.halite.videofeed

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scrollable timeline bar with a fixed center time indicator.
 *
 * The timeline is wider than the view — scrolling left/right moves through time.
 * A fixed vertical line + time label sits at the center of the viewport.
 * Recording availability is shown as colored blocks, event markers as dots.
 *
 * Tapo-style: the current time indicator stays centered, the timeline scrolls beneath it.
 */
class FrigateTimelineBar(
    context: Context
) : FrameLayout(context) {

    companion object {
        private const val TAG = "FrigateTimeline"

        // Colors
        private const val BG_COLOR = 0xFF1A1A1A.toInt()
        private const val RECORDING_COLOR = 0xFF2196F3.toInt()       // Blue
        private const val MOTION_COLOR = 0xFFFFA726.toInt()          // Orange (motion/events)
        private const val NO_RECORDING_COLOR = 0xFF333333.toInt()    // Visible dark gray
        private const val INDICATOR_COLOR = 0xFFFFFFFF.toInt()       // White center line
        private const val INDICATOR_BG_COLOR = 0xDD000000.toInt()    // Time label bg
        private const val TICK_COLOR = 0xFF555555.toInt()
        private const val TICK_LABEL_COLOR = 0xFFAAAAAA.toInt()

        // Event label colors
        private val EVENT_COLORS = mapOf(
            "person" to 0xFFFF9800.toInt(),
            "car" to 0xFF2196F3.toInt(),
            "dog" to 0xFF4CAF50.toInt(),
            "cat" to 0xFF9C27B0.toInt(),
        )
        private const val EVENT_DEFAULT_COLOR = 0xFFFF5722.toInt()

        // Layout
        private const val PIXELS_PER_HOUR = 60f    // ~8 hours visible in viewport
        private const val BAR_HEIGHT_DP = 24f
        private const val EVENT_DOT_RADIUS_DP = 4f
        private const val TICK_HEIGHT_DP = 8f
    }

    // Callback: fires when scrolling stops (debounced, ACTION_UP equivalent)
    var onTimeSelected: ((timestampSec: Long) -> Unit)? = null
    var onTimeChanged: ((timestampSec: Long) -> Unit)? = null
    var onUserScrollStart: (() -> Unit)? = null
    var onUserScrollEnd: (() -> Unit)? = null

    // Data
    private var recordingHours: List<RecordingHour> = emptyList()
    private var events: List<FrigateEvent> = emptyList()
    private var timeRangeStartSec: Long = 0
    private var timeRangeEndSec: Long = 0
    private var pixelsPerSecond: Float = 0f

    // Components
    private val scrollView: HorizontalScrollView
    private val timelineCanvas: TimelineCanvasView
    private val centerIndicator: View
    private val timeLabel: android.widget.TextView
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
    private var scrollDebounceRunnable: Runnable? = null
    private val SCROLL_DEBOUNCE_MS = 500L

    init {
        setBackgroundColor(BG_COLOR)

        // Scrollable timeline content
        val canvasHeightPx = dpToPx(56)  // Fixed height matching TIMELINE_HEIGHT_DP
        timelineCanvas = TimelineCanvasView(context).apply {
            setWillNotDraw(false)
            // Wide canvas for scrolling, explicit height
            // Must use FrameLayout.LayoutParams (MarginLayoutParams) — HorizontalScrollView requires it
            layoutParams = FrameLayout.LayoutParams(
                context.resources.displayMetrics.widthPixels * 2,
                canvasHeightPx
            )
        }
        scrollView = object : HorizontalScrollView(context) {
            @SuppressLint("ClickableViewAccessibility")
            override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
                if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                    var p = parent
                    while (p != null) {
                        p.requestDisallowInterceptTouchEvent(true)
                        p = if (p is android.view.View) (p as android.view.View).parent else null
                    }
                    onUserScrollStart?.invoke()
                }
                return super.onInterceptTouchEvent(ev)
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
                when (ev.action) {
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        var p = parent
                        while (p != null) {
                            p.requestDisallowInterceptTouchEvent(false)
                            p = if (p is android.view.View) (p as android.view.View).parent else null
                        }
                        onUserScrollEnd?.invoke()
                    }
                }
                return super.onTouchEvent(ev)
            }
        }.apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false  // Canvas must be wider than viewport for scrolling
            addView(timelineCanvas)
            // Track scroll position — update time label immediately, debounce playback trigger
            setOnScrollChangeListener { _, scrollX, _, _, _ ->
                updateCenterTime(scrollX)
                // Debounce the "time selected" callback (for playback trigger)
                scrollDebounceRunnable?.let { handler.removeCallbacks(it) }
                scrollDebounceRunnable = Runnable {
                    val timeSec = getCenterTimeSec()
                    onTimeSelected?.invoke(timeSec)
                }
                handler.postDelayed(scrollDebounceRunnable!!, SCROLL_DEBOUNCE_MS)
            }
        }
        addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Fixed center indicator line
        centerIndicator = View(context).apply {
            setBackgroundColor(INDICATOR_COLOR)
        }
        addView(centerIndicator, LayoutParams(dpToPx(2), LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // Time label floating above center indicator
        timeLabel = android.widget.TextView(context).apply {
            text = ""
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val padH = dpToPx(8)
            val padV = dpToPx(3)
            setPadding(padH, padV, padH, padV)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(INDICATOR_BG_COLOR)
                cornerRadius = dpToPx(4).toFloat()
            }
        }
        addView(timeLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            bottomMargin = dpToPx(2)
        })
    }

    // ── Public API ──────────────────────────────────────────────────

    fun setTimeRange(startSec: Long, endSec: Long, initialScrollSec: Long? = null) {
        timeRangeStartSec = startSec
        timeRangeEndSec = endSec
        val totalSeconds = (endSec - startSec).coerceAtLeast(1)
        val density = context.resources.displayMetrics.density
        pixelsPerSecond = (PIXELS_PER_HOUR * density) / 3600f
        val totalWidthPx = (totalSeconds * pixelsPerSecond).toInt()

        // Padding = half of our own width so edges can reach center.
        // Use scrollView's measured width if available, else estimate conservatively.
        val viewWidth = scrollView.width.takeIf { it > 0 } ?: (context.resources.displayMetrics.widthPixels / 4)
        val halfWidth = viewWidth / 2
        val paddedWidth = totalWidthPx + halfWidth * 2

        Log.i(TAG, "setTimeRange: ${totalSeconds}s, pxPerSec=$pixelsPerSecond, totalW=$totalWidthPx, padded=$paddedWidth, halfW=$halfWidth")

        val canvasHeight = dpToPx(56)
        Log.i(TAG, "Canvas layout: ${paddedWidth}x${canvasHeight}, halfW=$halfWidth")
        timelineCanvas.timelinePadding = halfWidth
        timelineCanvas.layoutParams = FrameLayout.LayoutParams(paddedWidth, canvasHeight)
        // Force re-measure and redraw
        scrollView.requestLayout()
        timelineCanvas.requestLayout()
        post { timelineCanvas.invalidate() }

        // Scroll after the canvas has been laid out with new dimensions — to the
        // caller-supplied position (an active clip's playhead) or to "now".
        // Hardcoding "now" here yanked a voice-opened past-timestamp playback
        // ("show the pool camera from 20 minutes ago") back to the live edge as
        // soon as the async history load landed: the scroll fired the debounced
        // scrub → seekToTimestamp(now) → "returning to live" (2026-07-20).
        timelineCanvas.addOnLayoutChangeListener(object : OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                        ol: Int, ot: Int, or2: Int, ob: Int) {
                v.removeOnLayoutChangeListener(this)
                val targetSec = initialScrollSec ?: (System.currentTimeMillis() / 1000)
                scrollToTime(targetSec)
                val canvasW = timelineCanvas.width
                val viewW = scrollView.width
                Log.i(TAG, "Layout done → scrolled to ${if (initialScrollSec != null) "clip playhead" else "now"}: scrollX=${scrollView.scrollX}, canvasW=$canvasW, viewW=$viewW, maxScroll=${canvasW - viewW}")
            }
        })
    }

    fun setRecordingData(hours: List<RecordingHour>) {
        recordingHours = hours
        timelineCanvas.invalidate()
    }

    /**
     * Extend timeRangeEndSec to the current time so the live indicator keeps advancing.
     * Grows the canvas as time passes without rebuilding everything.
     */
    fun extendRangeToNow() {
        val nowSec = System.currentTimeMillis() / 1000
        if (nowSec <= timeRangeEndSec) return
        if (timeRangeStartSec <= 0 || pixelsPerSecond <= 0) return  // Not initialized yet
        timeRangeEndSec = nowSec
        val totalSeconds = (timeRangeEndSec - timeRangeStartSec).coerceAtLeast(1)
        val totalWidthPx = (totalSeconds * pixelsPerSecond).toInt()
        val paddedWidth = totalWidthPx + timelineCanvas.timelinePadding * 2
        val canvasHeight = dpToPx(56)
        timelineCanvas.layoutParams = FrameLayout.LayoutParams(paddedWidth, canvasHeight)
        timelineCanvas.requestLayout()
        timelineCanvas.invalidate()
    }

    /**
     * Extend the range BACKWARD to an earlier start (lazy-loaded older history).
     * Grows the canvas without auto-scrolling; the caller scrolls to the target.
     */
    fun extendRangeToStart(newStartSec: Long) {
        if (newStartSec >= timeRangeStartSec || pixelsPerSecond <= 0) return
        timeRangeStartSec = newStartSec
        val totalSeconds = (timeRangeEndSec - timeRangeStartSec).coerceAtLeast(1)
        val totalWidthPx = (totalSeconds * pixelsPerSecond).toInt()
        val paddedWidth = totalWidthPx + timelineCanvas.timelinePadding * 2
        val canvasHeight = dpToPx(56)
        timelineCanvas.layoutParams = FrameLayout.LayoutParams(paddedWidth, canvasHeight)
        timelineCanvas.requestLayout()
        timelineCanvas.invalidate()
    }

    /**
     * Update the centered time label directly to the current time, bypassing
     * the scroll-position-derived label. Use in live mode to keep the timestamp
     * ticking even if scrolling is clamped or out of sync with the real time.
     */
    fun updateLiveTimeLabel() {
        val nowSec = System.currentTimeMillis() / 1000
        timeLabel.text = timeFormat.format(Date(nowSec * 1000))
    }

    /** Set the black-box label to a specific timestamp (e.g. the clip's position time). */
    fun setTimeLabel(timestampSec: Long) {
        timeLabel.text = timeFormat.format(Date(timestampSec * 1000))
    }

    fun setEvents(eventList: List<FrigateEvent>) {
        events = eventList
        timelineCanvas.invalidate()
    }

    fun scrollToTime(timestampSec: Long) {
        val clamped = timestampSec.coerceIn(timeRangeStartSec, timeRangeEndSec)
        val targetX = ((clamped - timeRangeStartSec) * pixelsPerSecond).toInt()
        scrollView.scrollTo(targetX, 0)
        // Set the label directly from the requested timestamp. Do NOT derive it
        // from the scroll position (which loses seconds-level precision at low zoom
        // because targetX is rounded to whole pixels).
        timeLabel.text = timeFormat.format(Date(clamped * 1000))
        onTimeChanged?.invoke(clamped)
    }

    fun getCenterTimeSec(): Long {
        val scrollX = scrollView.scrollX
        val ts = timeRangeStartSec + (scrollX / pixelsPerSecond).toLong()
        return ts.coerceIn(timeRangeStartSec, timeRangeEndSec)
    }

    private var currentZoomLevel = 1.0f  // 1.0 = default (PIXELS_PER_HOUR)

    fun zoomIn() {
        if (currentZoomLevel >= 4.0f) return
        val centerTs = getCenterTimeSec()
        currentZoomLevel *= 1.5f
        rebuildWithZoom()
        // Defer scrollToTime until after layout so pixelsPerSecond + scroll range are ready
        post { scrollToTime(centerTs) }
    }

    fun zoomOut() {
        if (currentZoomLevel <= 0.25f) return
        val centerTs = getCenterTimeSec()
        currentZoomLevel /= 1.5f
        rebuildWithZoom()
        post { scrollToTime(centerTs) }
    }

    private fun rebuildWithZoom() {
        val totalSeconds = (timeRangeEndSec - timeRangeStartSec).coerceAtLeast(1)
        val density = context.resources.displayMetrics.density
        pixelsPerSecond = (PIXELS_PER_HOUR * currentZoomLevel * density) / 3600f
        val totalWidthPx = (totalSeconds * pixelsPerSecond).toInt()

        val viewWidth = scrollView.width.takeIf { it > 0 } ?: (context.resources.displayMetrics.widthPixels / 4)
        val halfWidth = viewWidth / 2
        val paddedWidth = totalWidthPx + halfWidth * 2
        val canvasHeight = dpToPx(56)

        timelineCanvas.timelinePadding = halfWidth
        timelineCanvas.layoutParams = FrameLayout.LayoutParams(paddedWidth, canvasHeight)
        scrollView.requestLayout()
        timelineCanvas.requestLayout()
        post { timelineCanvas.invalidate() }
        Log.i(TAG, "Zoom: ${currentZoomLevel}x, pxPerSec=$pixelsPerSecond, canvasW=$paddedWidth")
    }

    // ── Internal ────────────────────────────────────────────────────

    private fun updateCenterTime(scrollX: Int) {
        if (pixelsPerSecond <= 0 || timeRangeStartSec <= 0) return  // Not initialized
        val timeSec = timeRangeStartSec + (scrollX / pixelsPerSecond).toLong()
        val clamped = timeSec.coerceIn(timeRangeStartSec, timeRangeEndSec)

        timeLabel.text = timeFormat.format(Date(clamped * 1000))

        // Clamp scroll if past the end (don't show future)
        val maxScrollX = ((timeRangeEndSec - timeRangeStartSec) * pixelsPerSecond).toInt()
        if (scrollX > maxScrollX) {
            scrollView.scrollTo(maxScrollX, 0)
        }

        onTimeChanged?.invoke(clamped)
    }

    private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

    // ── Inner Canvas View ───────────────────────────────────────────

    @SuppressLint("ViewConstructor")
    inner class TimelineCanvasView(context: Context) : View(context) {
        var timelinePadding: Int = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Report our layoutParams dimensions as our measured size.
            // HorizontalScrollView with isFillViewport=false measures us at WRAP_CONTENT,
            // but we need to be wider than the viewport for scrolling to work.
            val lp = layoutParams
            val w = if (lp != null && lp.width > 0) lp.width else MeasureSpec.getSize(widthMeasureSpec)
            val h = if (lp != null && lp.height > 0) lp.height else MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
        }

        private val recordingPaint = Paint().apply { color = RECORDING_COLOR; style = Paint.Style.FILL }
        private val motionPaint = Paint().apply { color = MOTION_COLOR; style = Paint.Style.FILL }
        private val noRecordingPaint = Paint().apply { color = NO_RECORDING_COLOR; style = Paint.Style.FILL }
        private val tickPaint = Paint().apply { color = TICK_COLOR; strokeWidth = dpToPx(1).toFloat() }
        private val tickLabelPaint = Paint().apply {
            color = 0xFFDDDDDD.toInt(); textSize = dpToPx(9).toFloat(); isAntiAlias = true
        }
        private val eventPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        private val rect = RectF()
        private val density = context.resources.displayMetrics.density

        private val dotPaint = Paint().apply { color = 0xFF444444.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
        private val hourLinePaint = Paint().apply { color = 0xFF666666.toInt(); strokeWidth = dpToPx(1).toFloat() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (pixelsPerSecond <= 0 || timeRangeEndSec <= timeRangeStartSec) {
                Log.d(TAG, "onDraw skipped: pxPerSec=$pixelsPerSecond, range=${timeRangeEndSec - timeRangeStartSec}")
                return
            }

            val h = height.toFloat()
            if (h <= 0) return
            val scrollX = (parent as? HorizontalScrollView)?.scrollX ?: 0
            Log.d(TAG, "onDraw: w=$width h=${h.toInt()} scrollX=$scrollX pad=$timelinePadding rec=${recordingHours.size}")
            val tickAreaTop = h * 0.05f
            val barTop = h * 0.35f
            val barH = BAR_HEIGHT_DP * density
            val barBottom = barTop + barH

            // ── Hour lines + labels + 15-min dots ────────────────
            val startHour = ((timeRangeStartSec / 3600) + 1) * 3600
            var ts = startHour
            while (ts < timeRangeEndSec) {
                val x = timeToX(ts)

                // Hour line (full height, subtle)
                canvas.drawLine(x, tickAreaTop, x, barBottom, hourLinePaint)

                // Hour label above the bar
                val label = SimpleDateFormat("h a", Locale.getDefault()).format(Date(ts * 1000))
                val labelW = tickLabelPaint.measureText(label)
                canvas.drawText(label, x - labelW / 2, tickAreaTop + tickLabelPaint.textSize, tickLabelPaint)

                // 15-minute dots between this hour and next
                for (quarter in 1..3) {
                    val dotTs = ts + (quarter * 900L)
                    if (dotTs >= timeRangeEndSec) break
                    val dotX = timeToX(dotTs)
                    val dotSize = if (quarter == 2) 2.5f else 1.5f // Larger dot at 30 min
                    canvas.drawCircle(dotX, tickAreaTop + tickLabelPaint.textSize / 2, dotSize * density, dotPaint)
                }

                ts += 3600
            }

            // ── Recording availability bar ───────────────────────
            // Clamp visible area to current time — don't draw into the future
            val nowSec = System.currentTimeMillis() / 1000
            val nowX = timeToX(nowSec.coerceAtMost(timeRangeEndSec))

            // Background (no recording) — only up to "now"
            rect.set(timelinePadding.toFloat(), barTop, nowX, barBottom)
            canvas.drawRect(rect, noRecordingPaint)

            // Recording segments (blue) — width scaled by actual recording coverage.
            // hour.duration is seconds recorded in that hour (0–3600). If Frigate was
            // down for part of the hour, duration < 3600 and we draw a narrower bar.
            for (hour in recordingHours) {
                if (hour.duration < 60) continue  // Skip hours with <1 min of recording
                val coverage = (hour.duration / 3600f).coerceAtMost(1f)
                val x1 = timeToX(hour.hour)
                val x2 = timeToX(hour.hour + (coverage * 3600).toLong()).coerceAtMost(nowX)
                if (x2 <= x1) continue
                rect.set(x1, barTop, x2, barBottom)
                canvas.drawRect(rect, recordingPaint)
            }

            // ── Event markers (centered vertically on the recording bar) ───
            val dotRadius = EVENT_DOT_RADIUS_DP * density
            val barCenterY = barTop + barH / 2
            for (event in events) {
                val x = timeToX(event.startTime.toLong())
                eventPaint.color = EVENT_COLORS[event.label] ?: EVENT_DEFAULT_COLOR
                canvas.drawCircle(x, barCenterY, dotRadius, eventPaint)
            }
        }

        private fun timeToX(timestampSec: Long): Float {
            return timelinePadding + (timestampSec - timeRangeStartSec) * pixelsPerSecond
        }
    }
}

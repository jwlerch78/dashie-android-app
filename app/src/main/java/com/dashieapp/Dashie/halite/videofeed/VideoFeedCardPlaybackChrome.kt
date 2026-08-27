package com.dashieapp.Dashie.halite.videofeed

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * D-pad chrome for Frigate playback mode: the three zone focus rings
 * (scrubber / video / clips) and fullscreen — which re-parents the timeline
 * onto the video as a temporary, auto-hiding scrub overlay. Extracted from
 * VideoFeedCard to keep that file within its size budget.
 */
class VideoFeedCardPlaybackChrome(private val context: Context) {
    var videoContainer: FrameLayout? = null
    var mainColumn: LinearLayout? = null
    var timelineRow: View? = null
    /** The scrubber+hint section (left part of timelineRow). The scrubber ring
     *  rings this, not the full row, so it stops at "Go to Live" instead of
     *  extending under the clip strip. */
    var timelineSection: View? = null
    var eventStrip: View? = null
    var labelBar: View? = null
    /** Full day-bar row (hidden in fullscreen). */
    var dayRow: View? = null
    /** Day-bar section under the scrubber (zone ring target). */
    var dayBarSection: View? = null
    var fullscreen = false
        private set

    fun reset() {
        timelineRow?.removeCallbacks(scrubHideRunnable)
        videoContainer = null; mainColumn = null; timelineRow = null
        timelineSection = null; eventStrip = null; labelBar = null
        dayRow = null; dayBarSection = null; fullscreen = false
    }

    /** Accent ring around one zone: "scrubber" | "video" | "clips" | "days" | "none". */
    fun setZoneFocus(zone: String) {
        videoContainer?.foreground = if (zone == "video") ring() else null
        timelineSection?.foreground = if (zone == "scrubber") ring() else null
        eventStrip?.foreground = if (zone == "clips") ring() else null
        dayBarSection?.foreground = if (zone == "days") ring() else null
    }

    private fun ring() = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setStroke(dp(4), VideoFeedStyles.ACCENT_COLOR)
        cornerRadius = dp(8).toFloat()
    }

    /**
     * Fullscreen: hide the clip strip + label bar, and re-parent the timeline
     * onto the video as a hidden bottom overlay (revealed on scrub via
     * [showScrubBar]).
     */
    fun setFullscreen(on: Boolean) {
        fullscreen = on
        eventStrip?.visibility = if (on) View.GONE else View.VISIBLE
        labelBar?.visibility = if (on) View.GONE else View.VISIBLE
        dayRow?.visibility = if (on) View.GONE else View.VISIBLE
        if (on) {
            videoContainer?.foreground = null
            timelineToOverlay()
        } else {
            timelineToColumn()
        }
    }

    /** Briefly reveal the timeline overlay during fullscreen scrubbing. */
    fun showScrubBar() {
        if (!fullscreen) return
        val tl = timelineRow ?: return
        tl.visibility = View.VISIBLE
        tl.removeCallbacks(scrubHideRunnable)
        tl.postDelayed(scrubHideRunnable, 2500)
    }

    private val scrubHideRunnable = Runnable {
        if (fullscreen) timelineRow?.visibility = View.GONE
    }

    private fun timelineToOverlay() {
        val tl = timelineRow ?: return
        val vc = videoContainer ?: return
        (tl.parent as? ViewGroup)?.removeView(tl)
        tl.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM }
        tl.elevation = 16f
        tl.visibility = View.GONE
        vc.addView(tl)
    }

    private fun timelineToColumn() {
        val tl = timelineRow ?: return
        val col = mainColumn ?: return
        tl.removeCallbacks(scrubHideRunnable)
        (tl.parent as? ViewGroup)?.removeView(tl)
        tl.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tl.elevation = 0f
        tl.visibility = View.VISIBLE
        // Re-insert above the day bar (the title bar now sits at the top).
        val anchorIdx = col.indexOfChild(dayRow)
        if (anchorIdx >= 0) col.addView(tl, anchorIdx) else col.addView(tl)
    }

    private fun dp(v: Int) = VideoFeedStyles.dpToPx(context, v)
}

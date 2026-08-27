package com.dashieapp.Dashie.halite.videofeed

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The bottom-right "● LIVE" / "Go to Live" pill on the Frigate playback video.
 * Extracted from FrigatePlaybackController to keep that file within budget.
 * Not used in TV mode (the timeline shows a hold-select hint instead).
 */
class FrigateLiveButton(
    private val context: Context,
    private val card: VideoFeedCard,
    /** TV mode: a passive "● LIVE" status badge — not clickable, and hidden
     *  entirely while off-live (the timeline's hold-select hint covers go-live). */
    private val passive: Boolean,
    private val onGoToLive: () -> Unit
) {
    private var button: View? = null
    private var dot: View? = null
    private var text: TextView? = null

    fun attach() {
        val btn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val padH = VideoFeedStyles.dpToPx(context, 10)
            val padV = VideoFeedStyles.dpToPx(context, 5)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(0xDD000000.toInt())
                cornerRadius = VideoFeedStyles.dpToPx(context, 14).toFloat()
            }
            isClickable = !passive
            isFocusable = !passive
            elevation = 12f
            if (!passive) setOnClickListener { onGoToLive() }
        }

        // Red dot (only shown when actually live)
        dot = View(context).apply {
            val size = VideoFeedStyles.dpToPx(context, 7)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = VideoFeedStyles.dpToPx(context, 5)
                gravity = Gravity.CENTER_VERTICAL
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFF0000.toInt())
            }
        }
        btn.addView(dot)

        text = TextView(context).apply {
            text = "LIVE"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11f
        }
        btn.addView(text)

        val videoContainer = card.playbackVideoContainer() ?: return

        val btnMargin = VideoFeedStyles.dpToPx(context, 12)
        videoContainer.addView(btn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = btnMargin
            marginEnd = btnMargin
        })
        button = btn
        setLive(true)
    }

    /** Interactive: toggle between "● LIVE" and "Go to Live". Passive (TV): show
     *  "● LIVE" only while live, hidden off-live. */
    fun setLive(isLive: Boolean) {
        if (passive) {
            button?.visibility = if (isLive) View.VISIBLE else View.GONE
            dot?.visibility = View.VISIBLE
            text?.text = "LIVE"
            return
        }
        dot?.visibility = if (isLive) View.VISIBLE else View.GONE
        text?.text = if (isLive) "LIVE" else "Go to Live"
        button?.isClickable = !isLive
    }

    fun remove() {
        button?.let { (it.parent as? ViewGroup)?.removeView(it) }
        button = null; dot = null; text = null
    }
}

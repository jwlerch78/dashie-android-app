package com.dashieapp.Dashie.halite.widgets

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.dashieapp.Dashie.R

/**
 * Small Kotlin-rendered badge shown in the bottom-right corner of a
 * paused rotator slot. Lives above the WebView so it's visible regardless
 * of which widget (native or iframe) is the active rotation.
 *
 * JS drives via window.DashieNative.showRotatorPauseIndicator(slotId, x, y, w, h)
 * (slot bounds in physical pixels; the indicator computes its own corner
 * placement from those) and hideRotatorPauseIndicator(slotId).
 */
class RotatorPauseIndicator(
    private val activity: Activity,
    private val rootContainer: ViewGroup
) {
    companion object {
        private const val BADGE_DP = 24f
        private const val INSET_DP = 8f
    }

    private var view: ImageView? = null

    fun show(slotX: Int, slotY: Int, slotW: Int, slotH: Int) {
        activity.runOnUiThread {
            ensureView()
            val v = view ?: return@runOnUiThread
            val badgePx = dp(BADGE_DP)
            val insetPx = dp(INSET_DP)
            val lp = (v.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(badgePx, badgePx)
            lp.width = badgePx
            lp.height = badgePx
            lp.leftMargin = slotX + slotW - badgePx - insetPx
            lp.topMargin = slotY + slotH - badgePx - insetPx
            lp.gravity = Gravity.TOP or Gravity.START
            v.layoutParams = lp
            v.visibility = View.VISIBLE
            v.bringToFront()
        }
    }

    fun hide() {
        activity.runOnUiThread { view?.visibility = View.GONE }
    }

    fun destroy() {
        activity.runOnUiThread {
            view?.let { (it.parent as? ViewGroup)?.removeView(it) }
            view = null
        }
    }

    private fun ensureView() {
        if (view != null) return
        val badgePx = dp(BADGE_DP)
        val v = ImageView(activity).apply {
            setImageResource(R.drawable.ic_pause)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC14161C"))
                setStroke(dp(1f), Color.parseColor("#33FFFFFF"))
            }
            // Elevation 0 — addView order; see activity_main.xml ladder.
            val pad = dp(5f)
            setPadding(pad, pad, pad, pad)
        }
        rootContainer.addView(v, FrameLayout.LayoutParams(badgePx, badgePx))
        view = v
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, activity.resources.displayMetrics
    ).toInt()
}

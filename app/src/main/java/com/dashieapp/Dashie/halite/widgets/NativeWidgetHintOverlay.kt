package com.dashieapp.Dashie.halite.widgets

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.dashieapp.Dashie.R

/**
 * A thin, semi-transparent "cheatsheet" strip painted across the bottom of
 * a native Kotlin widget while that widget is d-pad-activated.
 *
 * Matches the calendar's JS TV focus hint (thin strip, centered text with
 * key glyphs + labels). Stays visible for the entire duration of focus —
 * the caller is responsible for calling hide() when focus ends. Intended
 * to be reused by any native widget that implements NativeDpadWidget.
 *
 * Usage:
 *   val hint = NativeWidgetHintOverlay(activity, rootFrameLayout)
 *   hint.show(widgetBoundsPx, "◀ ▶  next / prev photo   ·   ●  full screen")
 *   ...
 *   hint.hide()
 *
 * Coordinates are in physical pixels (rootLayout space) — the caller is
 * responsible for supplying the widget's current screen bounds.
 */
class NativeWidgetHintOverlay(
    private val activity: Activity,
    private val rootContainer: ViewGroup,
    /** Visibility gate — registered OVERLAY_FULL_ONLY (event-triggered,
     *  dashboard-only). Gate force-hides on page reload. */
    private val visibilityGate: NativeWidgetVisibilityGate? = null
) {
    companion object {
        private const val TAG = "NativeWidgetHint"
        private const val STRIP_HEIGHT_DP = 30f
        // Inset from the widget's outer edges so the strip sits entirely
        // inside the focus ring (avoids overlap with the activated 8dp ring
        // drawn by FocusRingManager).
        private const val RING_INSET_DP = 8f
    }

    private var view: TextView? = null

    /**
     * Show the hint strip at the bottom of the given widget bounds. Stays
     * visible at full opacity until hide() is called — no auto-fade.
     */
    fun show(widgetX: Int, widgetY: Int, widgetW: Int, widgetH: Int, hintText: CharSequence) {
        hide()
        activity.runOnUiThread {
            val density = activity.resources.displayMetrics.density
            val stripHeightPx = (STRIP_HEIGHT_DP * density).toInt()

            val tv = TextView(activity).apply {
                text = hintText
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#EBEBEB"))
                background = ColorDrawable(Color.parseColor("#CC14161C"))  // 80% dark
                // Minimal horizontal padding; no vertical padding since the
                // strip height is fixed.
                setPadding((density * 6).toInt(), 0, (density * 6).toInt(), 0)
                alpha = 1f
                // Sit ABOVE the rotator pill icons (which use elevation=4dp)
                // so the cheatsheet text isn't occluded on TV. Without this
                // the hint draws under the bottom-right media-controls pill
                // and the bottom-left settings pill, hiding the key glyphs.
                elevation = 12f * density
            }

            val ringInsetPx = (RING_INSET_DP * density).toInt()
            val lp = FrameLayout.LayoutParams(
                widgetW - 2 * ringInsetPx,
                stripHeightPx
            ).apply {
                leftMargin = widgetX + ringInsetPx
                topMargin = widgetY + widgetH - stripHeightPx - ringInsetPx
                gravity = Gravity.TOP or Gravity.START
            }
            rootContainer.addView(tv, lp)
            visibilityGate?.register(tv, NativeWidgetVisibilityGate.WidgetKind.OVERLAY_FULL_ONLY)
            view = tv
            Log.d(TAG, "Shown: bounds=[$widgetX,$widgetY,$widgetW,$widgetH]")
        }
    }

    fun hide() {
        activity.runOnUiThread {
            view?.let { v ->
                (v.parent as? ViewGroup)?.removeView(v)
            }
            view = null
        }
    }
}

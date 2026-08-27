package com.dashieapp.Dashie.halite.widgets

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dashieapp.Dashie.R

/**
 * Touch-fade "Sign in required" pill anchored at the bottom-left corner
 * of a calendar (or agenda) widget. Shown only when one or more
 * connected calendar accounts has a revoked refresh token (auth_invalid).
 *
 * Visual: amber warning triangle + "Sign in required" label. Click is
 * routed through [onClickAction] — the overlay manager opens an
 * AlertDialog with Fix / Dismiss options.
 */
class CalendarReauthPill @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        // Match the dark-glass look of the rotator pills so the two are
        // visually consistent when they appear in the same widget area.
        // Sizing is ~25% larger than the rotator pills so the amber
        // warning reads as deliberately attention-grabbing.
        private const val PILL_PAD_H_DP = 12.5f
        private const val PILL_PAD_V_DP = 5f
        private const val ICON_DP = 22.5f
        private const val GAP_DP = 7.5f
        private const val LABEL_SP = 15f
        private val AMBER = Color.parseColor("#FFA800")
    }

    var onClickAction: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#8014161C"))
            cornerRadius = dp(20f).toFloat()
            setStroke(dp(1f), Color.parseColor("#66FFA800"))
        }
        val padH = dp(PILL_PAD_H_DP); val padV = dp(PILL_PAD_V_DP)
        setPadding(padH, padV, padH, padV)
        isClickable = true
        isFocusable = false // d-pad nav not designed to reach pill overlays
        setOnClickListener { onClickAction?.invoke() }

        val iconSize = dp(ICON_DP)
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_weather_warning)
                setColorFilter(AMBER)
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LayoutParams(iconSize, iconSize)
        )

        addView(spacer(dp(GAP_DP)))

        addView(
            TextView(context).apply {
                text = "Sign in required"
                setTextColor(AMBER)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SP)
                includeFontPadding = false
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )
    }

    private fun spacer(widthPx: Int) = android.view.View(context).apply {
        layoutParams = LayoutParams(widthPx, 1)
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
    ).toInt()
}

package com.dashieapp.Dashie.halite.widgets

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import com.dashieapp.Dashie.R

/**
 * Touch-fade "settings" pill anchored at the bottom-left corner of a
 * rotator slot. Single icon, hidden by default; fades in on touch and
 * auto-hides after a controller-managed timeout.
 *
 * Click emits "settings" via [onAction], routed back to JS by
 * RotatorPillsController.
 */
class RotatorSettingsPill @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        const val ACTION_SETTINGS = "settings"
        // Pill height matched to RotatorMediaPill (~34dp). Cog sized to
        // ICON_DP + 2*PILL_PAD_V matches media's 32 + 2*1 = 34dp.
        private const val ICON_DP = 32f
        private const val PILL_PAD_DP = 1f
    }

    var onAction: ((String) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            // ~50% black — match RotatorMediaPill.
            setColor(Color.parseColor("#8014161C"))
            cornerRadius = dp(20f).toFloat()
            setStroke(dp(1f), Color.parseColor("#33FFFFFF"))
        }
        val pad = dp(PILL_PAD_DP)
        setPadding(pad, pad, pad, pad)

        val iconSizePx = dp(ICON_DP)
        addView(
            ImageButton(context).apply {
                setImageResource(R.drawable.ic_settings)
                setColorFilter(Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setOnClickListener { onAction?.invoke(ACTION_SETTINGS) }
            },
            LinearLayout.LayoutParams(iconSizePx, iconSizePx)
        )
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
    ).toInt()
}

package com.dashieapp.Dashie.halite.videofeed

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * TV-mode playback hints, modeled on the calendar widget's TV hold hint
 * (a circular OK glyph with "(hold)" stacked beneath it, next to a label).
 */
object FrigatePlaybackHints {

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    /**
     * "Hold select → Go to Live" hint shown in the timeline row in place of the
     * touch zoom controls.
     */
    fun holdToLive(context: Context): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val padH = dp(context, 10)
            setPadding(padH, 0, padH, 0)
        }

        // Vertical keystack: ● glyph badge + "(hold)" caption
        val keystack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(context, 8) }
        }
        val glyphSize = dp(context, 26)
        keystack.addView(TextView(context).apply {
            text = "●"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(glyphSize, glyphSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x2EFFFFFF)  // white @ 0.18
            }
        })
        keystack.addView(TextView(context).apply {
            text = "(hold)"
            setTextColor(0xB3FFFFFF.toInt())  // white @ ~0.7
            textSize = 9f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 2) }
        })
        row.addView(keystack)

        // Label
        row.addView(TextView(context).apply {
            text = "Go to Live"
            setTextColor(0xD1FFFFFF.toInt())  // white @ ~0.82
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        })

        return row
    }
}

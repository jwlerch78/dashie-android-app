package com.dashieapp.Dashie.halite.settings.schema

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.style.ReplacementSpan
import kotlin.math.roundToInt

/**
 * Draws its text as a filled pill — white on [bgColor] — the way the console's LOCAL/CLOUD tags
 * look.
 *
 * A ReplacementSpan rather than Background/ForegroundColorSpan because those paint a bare
 * rectangle flush against the glyphs: no padding, no rounded corners, and the fill inherits the
 * row's text size. Here the chip owns its own metrics, so it can be smaller than the value text
 * it sits beside and still be legible.
 */
class BadgeChipSpan(
    private val bgColor: Int,
    private val textSizePx: Float,
    private val horizontalPadPx: Float,
    private val verticalPadPx: Float,
    private val cornerRadiusPx: Float
) : ReplacementSpan() {

    private fun chipPaint(src: Paint) = Paint(src).apply {
        textSize = textSizePx
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        val p = chipPaint(paint)
        val textWidth = p.measureText(text ?: "", start, end)
        // Leave the line's own metrics alone: the chip is shorter than the row text, so reporting
        // its (smaller) metrics here would let the line collapse around it and clip the descenders
        // of the value text beside it.
        return (textWidth + horizontalPadPx * 2).roundToInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val p = chipPaint(paint)
        val textWidth = p.measureText(text ?: "", start, end)
        val fm = p.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val chipHeight = textHeight + verticalPadPx * 2
        val chipWidth = textWidth + horizontalPadPx * 2

        // Center the pill on the baseline's text body rather than the full line box, so it sits
        // optically level with the value text instead of riding high next to tall glyphs.
        val centerY = y + (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        val chipTop = centerY - chipHeight / 2f

        val rect = RectF(x, chipTop, x + chipWidth, chipTop + chipHeight)
        val fill = Paint(p).apply { color = bgColor; style = Paint.Style.FILL }
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, fill)

        p.color = android.graphics.Color.WHITE
        canvas.drawText(
            text ?: "", start, end,
            x + horizontalPadPx,
            chipTop + verticalPadPx - fm.ascent,
            p
        )
    }
}

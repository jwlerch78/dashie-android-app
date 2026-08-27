package com.dashieapp.Dashie.halite.music

import android.content.Context
import android.content.res.Configuration
import com.dashieapp.Dashie.halite.preferences.DisplaySizeScale
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity

/**
 * Shared styling constants, theme utilities, and drawable factories for the music player.
 */
object MusicPlayerStyles {

    // Dimensions (dp)
    const val NORMAL_WIDTH_DP = 360
    const val NORMAL_HEIGHT_DP = 195
    const val NORMAL_HEIGHT_COLLAPSED_DP = 194  // 150 (player) + 44 (always-visible speaker bar)
    const val MINIMIZED_WIDTH_DP = 200
    const val MINIMIZED_HEIGHT_DP = 56
    const val ART_SIZE_NORMAL_DP = 108
    const val ART_SIZE_MINIMIZED_DP = 36
    const val ART_SIZE_MAXIMIZED_DP = 300
    const val CORNER_RADIUS_DP = 12
    const val PADDING_DP = 12
    const val MARGIN_DP = 16
    const val MINIMIZED_BOTTOM_MARGIN_DP = 80
    const val RECENTLY_PLAYED_STRIP_HEIGHT_DP = 120
    const val SPEAKER_ROW_HEIGHT_DP = 44
    const val STRIP_HEIGHT_DP = 91
    const val STRIP_SIDE_PADDING_DP = 69
    const val STRIP_MAX_WIDTH_DP = 1100
    /** Screen width (dp) at/above which the full-layout strip stays compact and
     *  centered. Below it, the strip stretches to fill the width beside the
     *  sidebar (shrunk title + right-aligned selector buttons) so small screens
     *  like the Echo Show 5 (~788dp) don't overlap the sidebar or show an
     *  under-filled bar. Wider screens (Samsung SM-X200 landscape ~1280dp, Fire
     *  TV ~960dp) keep the controls compact. Referenced by both MusicPlayerCard
     *  (card layout) and MusicPlayerStripRenderer (content layout) — keep in sync. */
    const val STRIP_COMPACT_MIN_WIDTH_DP = 900
    const val STRIP_ART_SIZE_DP = 76
    const val STRIP_BUTTON_SIZE_DP = 36
    const val STRIP_ICON_SIZE_DP = 22
    const val HIDDEN_BUBBLE_SIZE_DP = 48
    const val HIDDEN_BUBBLE_MARGIN_DP = 16
    const val RESIZE_HANDLE_DP = 24
    const val MIN_WIDTH_DP = 280
    const val MAX_WIDTH_DP = 800
    const val MIN_HEIGHT_DP = 160
    const val MAX_HEIGHT_DP = 800

    // Colors - Dark mode
    const val BG_COLOR_DARK = 0xE0000000.toInt()
    const val BG_COLOR_OPAQUE = 0xFF000000.toInt()
    const val HEADER_BG_DARK = 0xFF2A2A2A.toInt()  // Lighter gray for header bars in dark mode
    const val GLOW_COLOR_DARK = 0x30FFFFFF.toInt()  // Subtle white glow border for dark mode depth
    const val TEXT_PRIMARY_DARK = 0xFFFFFFFF.toInt()
    const val TEXT_SECONDARY_DARK = 0xB3FFFFFF.toInt()
    const val BORDER_COLOR_DARK = 0x26FFFFFF.toInt()

    // Colors - Light mode
    const val BG_COLOR_LIGHT = 0xF0FFFFFF.toInt()
    const val TEXT_PRIMARY_LIGHT = 0xFF000000.toInt()
    const val TEXT_SECONDARY_LIGHT = 0xB3000000.toInt()
    const val BORDER_COLOR_LIGHT = 0x26000000.toInt()

    // Shared colors
    const val ACCENT_COLOR = 0xFFFF9500.toInt()

    // Close button colors
    const val CLOSE_BG_DARK = 0xFF2A2A2A.toInt()
    const val CLOSE_BG_LIGHT = 0xFFE0E0E0.toInt()
    const val CLOSE_ICON_DARK = 0xFFFFFFFF.toInt()
    const val CLOSE_ICON_LIGHT = 0xFF333333.toInt()
    const val PROGRESS_BG_DARK = 0x33FFFFFF.toInt()
    const val PROGRESS_BG_LIGHT = 0x33000000.toInt()

    /** When true, isDarkMode() always returns true (e.g., on the black screensaver panel). */
    var forceDarkMode: Boolean = false

    fun isDarkMode(context: Context): Boolean {
        if (forceDarkMode) return true
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun textPrimary(context: Context): Int =
        if (isDarkMode(context)) TEXT_PRIMARY_DARK else TEXT_PRIMARY_LIGHT

    fun textSecondary(context: Context): Int =
        if (isDarkMode(context)) TEXT_SECONDARY_DARK else TEXT_SECONDARY_LIGHT

    fun progressBg(context: Context): Int =
        if (isDarkMode(context)) PROGRESS_BG_DARK else PROGRESS_BG_LIGHT

    // Native overlays honor the same "widget zoom" knob as the iframe widgets.
    // At 100% zoom DisplaySizeScale.scale() == 1.0f, so these are unchanged for
    // every existing user; they only grow once zoom is raised. Routing ALL music
    // dimensions through dpToPx() means scaling one chokepoint scales the whole
    // strip/card uniformly (art, buttons, padding, height). Text is set in sp in
    // the renderers — wrap those with scaledSp() so type grows in step.
    fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density * DisplaySizeScale.scale(context)).toInt()

    /** sp value scaled by the widget-zoom factor, for setTextSize(COMPLEX_UNIT_SP, …). */
    fun scaledSp(context: Context, sp: Float): Float =
        sp * DisplaySizeScale.scale(context)

    fun createRoundedBackground(context: Context, opaque: Boolean = false): GradientDrawable {
        val isDark = isDarkMode(context)
        return GradientDrawable().apply {
            val bgColor = when {
                opaque -> BG_COLOR_OPAQUE
                isDark -> BG_COLOR_DARK
                else -> BG_COLOR_LIGHT
            }
            setColor(bgColor)
            if (opaque) {
                // Full-screen maximized mode: no corners, no border
                cornerRadius = 0f
            } else {
                cornerRadius = dpToPx(context, CORNER_RADIUS_DP).toFloat()
                setStroke(dpToPx(context, 1), if (isDark) BORDER_COLOR_DARK else BORDER_COLOR_LIGHT)
            }
        }
    }

    fun createCircleBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    fun createProgressDrawable(context: Context, forceDarkMode: Boolean = false): LayerDrawable {
        val bgColor = if (forceDarkMode) PROGRESS_BG_DARK else progressBg(context)
        val background = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = dpToPx(context, 2).toFloat()
        }
        val progress = GradientDrawable().apply {
            setColor(ACCENT_COLOR)
            cornerRadius = dpToPx(context, 2).toFloat()
        }
        val clip = ClipDrawable(progress, Gravity.START, ClipDrawable.HORIZONTAL)
        return LayerDrawable(arrayOf(background, clip)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    /**
     * Create a slow-spinning arc view for the reconnecting state.
     * A thin 270-degree arc rotates once every 3 seconds — calm enough for 30-60s waits.
     */
    fun createReconnectingSpinner(context: Context, sizePx: Int, color: Int = ACCENT_COLOR): android.view.View {
        val strokeWidth = dpToPx(context, 2).toFloat()
        val arcDrawable = object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
                this.color = color
                strokeCap = Paint.Cap.ROUND
            }

            override fun draw(canvas: Canvas) {
                val inset = strokeWidth / 2
                val rect = android.graphics.RectF(inset, inset,
                    bounds.width() - inset, bounds.height() - inset)
                canvas.drawArc(rect, 0f, 270f, false, paint)
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
            @Deprecated("Deprecated")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }

        return android.widget.ImageView(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(sizePx, sizePx)
            setImageDrawable(arcDrawable)
            visibility = android.view.View.GONE
            // Slow rotation: 3 seconds per revolution
            android.animation.ObjectAnimator.ofFloat(this, "rotation", 0f, 360f).apply {
                duration = 3000
                repeatCount = android.animation.ObjectAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                start()
            }
        }
    }

    fun grayedOutColor(context: Context): Int =
        if (isDarkMode(context)) 0x99FFFFFF.toInt() else 0x99000000.toInt()

    /** Solid orange round thumb for the SeekBar scrubber. */
    fun createSeekThumb(context: Context, sizeDp: Int = 14): Drawable {
        val sizePx = dpToPx(context, sizeDp)
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ACCENT_COLOR)
            setSize(sizePx, sizePx)
        }
    }

    /**
     * Apply shuffle/repeat button visual state.
     * Off → secondary text color (faded). On → ACCENT_COLOR (orange).
     * Caller picks the icon resource (ic_repeat vs ic_repeat_one for repeat).
     */
    fun applyToggleButtonState(button: android.widget.ImageButton, active: Boolean, forceDarkMode: Boolean = false) {
        val color = if (active) ACCENT_COLOR
            else if (forceDarkMode) TEXT_SECONDARY_DARK
            else textSecondary(button.context)
        button.setColorFilter(color)
    }

    fun createChevronDrawable(context: Context, pointingRight: Boolean, color: Int): Drawable {
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = dpToPx(context, 2).toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val path = Path()
                if (pointingRight) {
                    path.moveTo(w * 0.35f, h * 0.2f)
                    path.lineTo(w * 0.65f, h * 0.5f)
                    path.lineTo(w * 0.35f, h * 0.8f)
                } else {
                    path.moveTo(w * 0.65f, h * 0.2f)
                    path.lineTo(w * 0.35f, h * 0.5f)
                    path.lineTo(w * 0.65f, h * 0.8f)
                }
                canvas.drawPath(path, paint)
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
            @Deprecated("Deprecated")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
    }

    fun createTriangleDrawable(context: Context, pointingUp: Boolean, color: Int): Drawable {
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val path = Path()
                if (pointingUp) {
                    path.moveTo(w / 2, 0f)
                    path.lineTo(0f, h)
                    path.lineTo(w, h)
                } else {
                    path.moveTo(0f, 0f)
                    path.lineTo(w, 0f)
                    path.lineTo(w / 2, h)
                }
                path.close()
                canvas.drawPath(path, paint)
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
            @Deprecated("Deprecated")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
            override fun getIntrinsicWidth() = dpToPx(context, 10)
            override fun getIntrinsicHeight() = dpToPx(context, 6)
        }
    }
}

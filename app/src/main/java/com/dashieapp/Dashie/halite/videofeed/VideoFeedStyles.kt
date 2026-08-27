package com.dashieapp.Dashie.halite.videofeed

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import com.dashieapp.Dashie.halite.preferences.DisplaySizeScale

/**
 * Shared styling constants for Video Feed PiP overlays.
 * Mirrors MusicPlayerStyles pattern for consistency.
 */
object VideoFeedStyles {

    // Size presets (percentage of screen width → dp approximations for 10" tablet)
    // Actual dp is calculated at runtime from screen dimensions
    const val SIZE_SMALL_PERCENT = 0.25f
    const val SIZE_STRIP_PERCENT = 0.19f   // Strip cards are 25% smaller than "small"
    const val SIZE_MEDIUM_PERCENT = 0.33f
    const val SIZE_LARGE_PERCENT = 0.50f
    const val SIZE_XL_PERCENT = 0.75f      // Matches legacy notification-mode default

    // Aspect ratio for camera feeds (16:9)
    const val ASPECT_RATIO = 16f / 9f

    // Layout
    const val CORNER_RADIUS_DP = 12
    const val MARGIN_DP = 16
    const val STACKING_GAP_DP = 8
    const val BUTTON_SIZE_DP = 32
    const val LABEL_HEIGHT_DP = 36
    const val SIDEBAR_OFFSET_DP = 64  // Extra left margin to clear the HA sidebar
    const val RESIZE_HANDLE_DP = 28
    const val MIN_SIZE_PERCENT = 0.15f
    const val MAX_SIZE_PERCENT = 0.95f
    // Dim overlay
    const val DIM_OVERLAY_ALPHA = 0.4f         // Dashboard dim (lighter) — sidebar mode
    const val NOTIFICATION_DIM_ALPHA = 0.7f    // Notification alert: modal-ish, less transparent
    const val SIDEBAR_PANEL_ALPHA = 0.85f      // Sidebar panel bg (much more opaque)
    const val DIM_OVERLAY_ANIMATE_MS = 300L

    // Sidebar display mode — matches ScreensaverPanelCoordinator
    const val SIDEBAR_PANEL_MARGIN_DP = 8      // Matches screensaver panel margin
    const val SIDEBAR_CARD_GAP_DP = 12         // Matches screensaver panel gap
    const val SIDEBAR_DISMISS_HEIGHT_DP = 44   // Dismiss All button height

    // Notification display mode
    const val NOTIFICATION_BOTTOM_MARGIN_DP = 40
    const val NOTIFICATION_HORIZONTAL_GAP_DP = 12

    // Strip display mode (bottom bar, like music player strip)
    const val STRIP_TOP_BAR_HEIGHT_DP = 38
    const val STRIP_CARD_GAP_DP = 10
    const val STRIP_CONTROLS_WIDTH_DP = 80
    const val STRIP_PADDING_DP = 12
    const val STRIP_SIDE_PADDING_DP = 69   // Sidebar strip width — don't overlap

    // Colors - Dark mode
    const val BG_COLOR_DARK = 0xE0000000.toInt()
    const val TEXT_PRIMARY_DARK = 0xFFFFFFFF.toInt()
    const val BORDER_COLOR_DARK = 0x26FFFFFF.toInt()

    // Colors - Light mode
    const val BG_COLOR_LIGHT = 0xF0FFFFFF.toInt()
    const val TEXT_PRIMARY_LIGHT = 0xFF000000.toInt()
    const val BORDER_COLOR_LIGHT = 0x26000000.toInt()

    // Shared
    const val ACCENT_COLOR = 0xFFFF9500.toInt()
    const val CLOSE_BG_DARK = 0xFF2A2A2A.toInt()
    const val CLOSE_BG_LIGHT = 0xFFE0E0E0.toInt()

    fun isDarkMode(context: Context): Boolean {
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun textPrimary(context: Context): Int =
        if (isDarkMode(context)) TEXT_PRIMARY_DARK else TEXT_PRIMARY_LIGHT

    // Native overlays honor the same "widget zoom" knob as the iframe widgets.
    // At 100% zoom DisplaySizeScale.scale() == 1.0f, so these are unchanged for
    // every existing user; cards/controls only grow once zoom is raised.
    fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density * DisplaySizeScale.scale(context)).toInt()

    fun dpToPxF(context: Context, dp: Int): Float =
        dp * context.resources.displayMetrics.density * DisplaySizeScale.scale(context)

    /** sp value scaled by the widget-zoom factor, for setTextSize(COMPLEX_UNIT_SP, …). */
    fun scaledSp(context: Context, sp: Float): Float =
        sp * DisplaySizeScale.scale(context)

    /**
     * Calculate PiP card width in pixels based on size preset.
     */
    fun cardWidthPx(context: Context, size: String): Int {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val percent = when (size) {
            "strip" -> SIZE_STRIP_PERCENT
            "small" -> SIZE_SMALL_PERCENT
            "large" -> SIZE_LARGE_PERCENT
            "xl" -> SIZE_XL_PERCENT
            else -> SIZE_MEDIUM_PERCENT
        }
        return (screenWidth * percent * DisplaySizeScale.scale(context)).toInt()
    }

    /**
     * Calculate PiP card height from width using 16:9 aspect ratio + label bar.
     */
    fun cardHeightPx(context: Context, size: String): Int {
        val width = cardWidthPx(context, size)
        val videoHeight = (width / ASPECT_RATIO).toInt()
        return videoHeight + dpToPx(context, LABEL_HEIGHT_DP)
    }

    fun createRoundedBackground(context: Context, opaque: Boolean = false): GradientDrawable {
        val isDark = isDarkMode(context)
        return GradientDrawable().apply {
            setColor(if (opaque) 0xFF000000.toInt() else if (isDark) BG_COLOR_DARK else BG_COLOR_LIGHT)
            if (opaque) {
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
}

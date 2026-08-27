package com.dashieapp.Dashie.halite.videofeed

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Manages the overlay UI for video feed display.
 *
 * Sidebar mode:
 * - Full-screen dim layer (alpha 0.4, tappable to dismiss all)
 * - Semi-opaque right-1/3 panel background (alpha 0.85)
 * - "Dismiss Cameras" button at bottom of panel
 *
 * Notification mode:
 * - Full-screen dim layer only (tappable to dismiss all)
 *
 * Also supports showing the dismiss button during persistent mode.
 */
class VideoFeedDimOverlay(
    private val context: Context,
    private val container: FrameLayout,
    /** Visibility gate — each created sub-view (dim, panel, dismiss button)
     *  is registered OVERLAY_KIOSK_OR_FULL so they force-hide on transition
     *  to OFF mode (login screen / logout). */
    private val visibilityGate: com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate? = null
) {
    private var dimView: View? = null
    private var panelView: View? = null
    private var dismissButton: TextView? = null
    var isShowing = false
        private set

    var onOverlayTapped: (() -> Unit)? = null
    var onDismissAll: (() -> Unit)? = null
    /** Provider for dynamic panel width (from coordinator). Falls back to screenWidth/3. */
    var panelWidthProvider: (() -> Int)? = null
    private fun getPanelWidth(): Int =
        panelWidthProvider?.invoke() ?: (context.resources.displayMetrics.widthPixels / 3)

    /** Current dismiss button style, driven by FeedDisplayState. */
    var dismissStyle: DismissStyle = DismissStyle.NONE

    fun showSidebar() {
        if (isShowing) return
        isShowing = true
        ensureDimView(clickable = true)
        ensurePanelView()
        ensureDismissButton()
        animateIn(dimView, VideoFeedStyles.DIM_OVERLAY_ALPHA)
        animateIn(panelView, VideoFeedStyles.SIDEBAR_PANEL_ALPHA)
        animateIn(dismissButton, 1f)
    }

    fun showNotification() {
        if (isShowing) return
        isShowing = true
        ensureDimView(clickable = true)
        panelView?.visibility = View.GONE
        dismissButton?.visibility = View.GONE
        // Notification alerts use a darker dim than sidebar mode — the card is
        // a modal-ish affordance centered on the dashboard, so the rest of the
        // UI should visually recede more than it does for the sidebar case.
        animateIn(dimView, VideoFeedStyles.NOTIFICATION_DIM_ALPHA)
    }

    /**
     * Show only the dim overlay and dismiss button (no panel background).
     * Used when the ScreensaverPanelCoordinator handles the panel background.
     */
    fun showDimWithDismiss() {
        if (isShowing) return
        isShowing = true
        ensureDimView(clickable = true)
        panelView?.visibility = View.GONE
        ensureDismissButton()
        animateIn(dimView, VideoFeedStyles.DIM_OVERLAY_ALPHA)
        animateIn(dismissButton, 1f)
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        animateOut(dimView)
        animateOut(panelView)
        animateOut(dismissButton)
    }

    /** Show the dismiss button in a specific container */
    fun showDismissButtonIn(targetContainer: FrameLayout) {
        ensureDismissButton()
        val btn = dismissButton ?: return
        val currentParent = btn.parent as? ViewGroup
        if (currentParent != targetContainer) {
            currentParent?.removeView(btn)
            targetContainer.addView(btn, createDismissLayoutParams())
        }
        // Always update layout params to match current state
        val lp = createDismissLayoutParams()
        btn.layoutParams = lp
        btn.bringToFront()
        animateIn(btn, 1f)
    }

    /** Show only the dismiss button in the original container */
    fun showDismissButtonOnly() {
        showDismissButtonIn(container)
    }

    /** Hide only the dismiss button */
    fun hideDismissButton() {
        animateOut(dismissButton)
    }

    /** Hide the dismiss button and move back to original container */
    fun hideDismissButtonAndReset() {
        val btn = dismissButton ?: return
        btn.animate().cancel()
        btn.visibility = View.GONE
        btn.alpha = 0f
        val currentParent = btn.parent as? ViewGroup
        if (currentParent != null && currentParent != container) {
            currentParent.removeView(btn)
            container.addView(btn, createDismissLayoutParams())
        }
    }

    // ── Animation ────────────────────────────────────────────────────

    private fun animateIn(view: View?, targetAlpha: Float) {
        view ?: return
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(targetAlpha)
            .setDuration(VideoFeedStyles.DIM_OVERLAY_ANIMATE_MS)
            .setListener(null)
            .start()
    }

    private fun animateOut(view: View?) {
        view ?: return
        if (view.visibility == View.GONE) return
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(VideoFeedStyles.DIM_OVERLAY_ANIMATE_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.visibility = View.GONE
                }
            })
            .start()
    }

    // ── View Creation ────────────────────────────────────────────────

    private fun ensureDimView(clickable: Boolean) {
        if (dimView == null) {
            dimView = View(context).apply {
                setBackgroundColor(Color.BLACK)
                alpha = 0f
                visibility = View.GONE
            }
            container.addView(dimView, 0,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            dimView?.let {
                visibilityGate?.register(
                    it,
                    com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.WidgetKind.OVERLAY_KIOSK_OR_FULL
                )
            }
        }
        dimView?.apply {
            isClickable = clickable
            isFocusable = clickable
            setOnClickListener(if (clickable) View.OnClickListener { onOverlayTapped?.invoke() } else null)
        }
    }

    private fun ensurePanelView() {
        if (panelView != null) return
        val panelWidth = getPanelWidth()
        panelView = View(context).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }
        val lp = FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.END
        }
        val insertIndex = (container.indexOfChild(dimView) + 1).coerceAtMost(container.childCount)
        container.addView(panelView, insertIndex, lp)
        panelView?.let {
            visibilityGate?.register(
                it,
                com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.WidgetKind.OVERLAY_KIOSK_OR_FULL
            )
        }
    }

    private fun ensureDismissButton() {
        if (dismissButton != null) return
        val btnHeight = VideoFeedStyles.dpToPx(context, VideoFeedStyles.SIDEBAR_DISMISS_HEIGHT_DP)
        val cornerRadius = VideoFeedStyles.dpToPx(context, DISMISS_CORNER_RADIUS_DP).toFloat()

        dismissButton = TextView(context).apply {
            text = "Dismiss Cameras"
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            alpha = 0f
            visibility = View.GONE
            elevation = 10f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#333333"))
                this.cornerRadius = cornerRadius
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onDismissAll?.invoke() }
        }
        val lp = createDismissLayoutParams()
        val insertIndex = if (panelView != null) {
            (container.indexOfChild(panelView) + 1).coerceAtMost(container.childCount)
        } else {
            container.childCount
        }
        container.addView(dismissButton, insertIndex, lp)
        dismissButton?.let {
            visibilityGate?.register(
                it,
                com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.WidgetKind.OVERLAY_KIOSK_OR_FULL
            )
        }
    }

    /** Create layout params for dismiss button based on current dismissStyle. */
    private fun createDismissLayoutParams(): FrameLayout.LayoutParams {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val btnHeight = VideoFeedStyles.dpToPx(context, VideoFeedStyles.SIDEBAR_DISMISS_HEIGHT_DP)
        val bottomMargin = VideoFeedStyles.dpToPx(context, DISMISS_BOTTOM_MARGIN_DP)

        return when (dismissStyle) {
            DismissStyle.SIDEBAR_WIDTH -> {
                val panelWidth = getPanelWidth()
                FrameLayout.LayoutParams(panelWidth, btnHeight).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    this.bottomMargin = 0
                }
            }
            DismissStyle.BOTTOM_CENTER -> {
                // Fixed-width pill centered at the bottom of the screen.
                // Used for notification-style alerts where the camera card is
                // centered on the dashboard; the dismiss pill sits below the
                // card rather than matching the (nonexistent) sidebar panel.
                // Larger and higher off the bottom than the sidebar style so
                // it reads as a "confirmation" affordance on a modal overlay.
                val pillWidth = VideoFeedStyles.dpToPx(context, BOTTOM_CENTER_PILL_WIDTH_DP)
                val pillHeight = VideoFeedStyles.dpToPx(context, BOTTOM_CENTER_PILL_HEIGHT_DP)
                val pillBottomMargin = VideoFeedStyles.dpToPx(context, BOTTOM_CENTER_BOTTOM_MARGIN_DP)
                FrameLayout.LayoutParams(pillWidth, pillHeight).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    this.bottomMargin = pillBottomMargin
                }
            }
            DismissStyle.NONE -> {
                FrameLayout.LayoutParams(0, 0)
            }
        }
    }

    fun destroy() {
        listOfNotNull(dimView, panelView, dismissButton).forEach {
            it.animate().cancel()
            (it.parent as? ViewGroup)?.removeView(it)
        }
        dimView = null
        panelView = null
        dismissButton = null
        isShowing = false
    }

    companion object {
        private const val DISMISS_CORNER_RADIUS_DP = 12
        private const val DISMISS_BOTTOM_MARGIN_DP = 8
        // Notification alert BOTTOM_CENTER pill: bigger tap target and lifted
        // enough off the screen bottom to read as a modal-confirmation
        // affordance without overlapping the 2x2 notification grid cards.
        private const val BOTTOM_CENTER_PILL_WIDTH_DP = 320
        private const val BOTTOM_CENTER_PILL_HEIGHT_DP = 60
        private const val BOTTOM_CENTER_BOTTOM_MARGIN_DP = 24
    }
}

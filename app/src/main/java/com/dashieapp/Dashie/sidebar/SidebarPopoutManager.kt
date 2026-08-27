package com.dashieapp.Dashie.sidebar

import android.app.Activity
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Creates and positions popout views for sidebar popouts.
 *
 * Popouts are added directly to the Activity's decor view (not a PopupWindow)
 * so they share the Activity's full-screen touch region. PopupWindows have a
 * separate window that can't receive touches in the nav bar area, even with
 * setDecorFitsSystemWindows(false). Using the Activity's view hierarchy avoids
 * this limitation.
 *
 * Each popout appears to the right of the sidebar strip, vertically
 * centered on the anchor button or bottom-aligned.
 */
class SidebarPopoutManager(
    private val activity: Activity
) {
    // Support callers that pass Context (it must be an Activity)
    constructor(context: android.content.Context) : this(context as Activity)

    companion object {
        private const val TAG = "PopoutManager"
        private const val GAP_DP = 8
    }

    private var popoutView: View? = null
    private var backdropView: View? = null
    private var popoutId: String? = null
    private var stripPanelRef: View? = null
    private var anchorButtonRef: View? = null
    private var alignmentMode: Alignment = Alignment.CENTER_ON_ANCHOR
    private var dismissCallback: (() -> Unit)? = null

    val isPopoutVisible: Boolean get() = popoutView?.parent != null
    val currentPopoutId: String? get() = popoutId

    /** Get the active popout's content view (for re-theming after dark mode toggle). */
    val activeContentView: View? get() = popoutView

    enum class Alignment {
        /** Bottom of popout aligns with bottom of strip panel.
         *  Used by the landscape sidebar's hamburger popout. */
        BOTTOM,
        /** Popout is vertically centered on the anchor button.
         *  Used by the landscape sidebar's volume + brightness popouts. */
        CENTER_ON_ANCHOR,
        /** Popout sits ABOVE the strip panel, left edge aligned with
         *  the anchor button's left edge. Used by the portrait bottom
         *  bar's hamburger popout — pins to the start (left) edge so
         *  the popout extends rightward from the leftmost button. */
        ABOVE_ANCHOR_START,
        /** Popout sits ABOVE the strip panel, horizontally centered on
         *  the anchor button. Used by the portrait bottom bar's
         *  volume + brightness popouts. */
        ABOVE_ANCHOR_CENTER
    }

    private fun isAboveAlignment(a: Alignment): Boolean =
        a == Alignment.ABOVE_ANCHOR_START || a == Alignment.ABOVE_ANCHOR_CENTER

    /** The decor view where we add popout views */
    private val decorView: FrameLayout
        get() = activity.window.decorView as FrameLayout

    /**
     * Show a popout anchored to the given button, or dismiss if same popout is already showing.
     * Returns the inflated content view, or null if dismissed.
     */
    fun togglePopout(
        layoutResId: Int,
        anchorButton: View,
        stripPanel: View,
        popoutId: String,
        alignment: Alignment = Alignment.CENTER_ON_ANCHOR,
        showBackdrop: Boolean = true,
        onDismiss: () -> Unit = {}
    ): View? {
        // Toggle off if same popout
        if (this.popoutId == popoutId && popoutView != null) {
            dismissPopout()
            return null
        }
        dismissPopout()

        val inflater = LayoutInflater.from(activity)
        val tempParent = FrameLayout(activity)
        val contentView = inflater.inflate(layoutResId, tempParent, false)

        // Calculate position using getLocationInWindow (we're in the same window now)
        val density = activity.resources.displayMetrics.density
        val gap = (GAP_DP * density).toInt()

        val stripLoc = IntArray(2)
        stripPanel.getLocationInWindow(stripLoc)
        val anchorLoc = IntArray(2)
        anchorButton.getLocationInWindow(anchorLoc)
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val safeMargin = (16 * density).toInt()

        // X position depends on alignment family:
        //   BOTTOM / CENTER_ON_ANCHOR: popout sits to the RIGHT of the
        //     strip (landscape sidebar at left edge of screen).
        //   ABOVE_ANCHOR_*: popout sits ABOVE the strip (portrait bottom
        //     bar); X comes from the anchor button's position with the
        //     final adjustment applied AFTER popoutWidth is known.
        val xRightOfStrip = stripLoc[0] + stripPanel.width + gap
        val isAbove = isAboveAlignment(alignment)
        // For above-alignment we don't know xPos yet — pick a provisional
        // value (anchor start). Final xPos is recomputed after we know
        // popoutWidth.
        val xPos = if (isAbove) anchorLoc[0] else xRightOfStrip

        // Cap popout width to the space available next to the anchor.
        // On narrow-portrait devices (Lenovo Smart Clock 2 class) the popout's intrinsic
        // width (e.g. 400dp volume popout) can exceed the visible area, causing the right
        // edge to be clipped behind the screen. With a capped width, the HorizontalScrollView
        // inside takes over and the user can swipe to reach the clipped controls.
        // For above-alignment the popout floats above the bar so it can use almost the
        // full screen width — only the safe-margin on each side is reserved.
        val maxPopupWidth = if (isAbove) {
            (screenWidth - 2 * safeMargin).coerceAtLeast((120 * density).toInt())
        } else {
            (screenWidth - xRightOfStrip - safeMargin).coerceAtLeast((120 * density).toInt())
        }

        // Use the root view's specified width (e.g. 286dp), fall back to wrap_content, then
        // clamp to available space. If the layout declares wrap_content, measure against
        // the max to discover content's natural width and cap at our max.
        val lp = contentView.layoutParams
        val declaredWidth = lp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT
        val popupWidth: Int = when {
            declaredWidth > 0 && declaredWidth > maxPopupWidth -> maxPopupWidth
            declaredWidth > 0 -> declaredWidth
            else -> {
                // Wrap_content root — measure to get natural width, then cap
                contentView.measure(
                    View.MeasureSpec.makeMeasureSpec(maxPopupWidth, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val natural = contentView.measuredWidth
                if (natural > maxPopupWidth || natural == 0) maxPopupWidth else natural
            }
        }

        // Re-measure height with the final width so positioning math uses real height
        val widthSpec = View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY)
        contentView.measure(
            widthSpec,
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupHeight = contentView.measuredHeight

        val topMargin = (8 * density).toInt()
        val stripBottom = stripLoc[1] + stripPanel.height
        val stripTop = stripLoc[1]

        var yPos: Int
        var finalHeight = popupHeight
        // xPos is recomputed for above-alignment now that popupWidth is known.
        var finalX = xPos

        when (alignment) {
            Alignment.BOTTOM -> {
                yPos = stripBottom - popupHeight
            }
            Alignment.ABOVE_ANCHOR_START -> {
                // Popout floats above the (horizontal) bar, left-aligned with
                // anchor. Anchored to anchor.left so the leftmost button's
                // popout pins to the bar's start edge.
                yPos = stripTop - popupHeight - gap
                finalX = anchorLoc[0]
            }
            Alignment.ABOVE_ANCHOR_CENTER -> {
                yPos = stripTop - popupHeight - gap
                val anchorCenterX = anchorLoc[0] + anchorButton.width / 2
                finalX = anchorCenterX - popupWidth / 2
            }
            else -> {
                // CENTER_ON_ANCHOR — landscape sidebar's volume/brightness.
                val anchorCenter = anchorLoc[1] + anchorButton.height / 2
                yPos = anchorCenter - popupHeight / 2
            }
        }

        if (isAboveAlignment(alignment)) {
            // Clamp X within screen bounds with safe-margin on both sides,
            // then clamp Y so the popout doesn't run off the top edge.
            val maxLeft = (screenWidth - popupWidth - safeMargin).coerceAtLeast(safeMargin)
            finalX = finalX.coerceIn(safeMargin, maxLeft)
            yPos = yPos.coerceAtLeast(topMargin)
        } else {
            // Vertical-strip alignments: cap height if it exceeds available space
            val maxHeight = stripBottom - topMargin
            if (popupHeight > maxHeight) {
                finalHeight = maxHeight
                yPos = topMargin
            } else {
                val maxTop = stripBottom - popupHeight
                yPos = yPos.coerceIn(topMargin, maxTop.coerceAtLeast(topMargin))
            }
        }

        Log.i(TAG, "togglePopout($popoutId) popupH=${popupHeight}px stripBot=$stripBottom " +
            "yPos=$yPos popBot=${yPos + finalHeight}")

        // Add a semi-transparent dim backdrop to intercept outside touches and dismiss.
        // When showBackdrop=false (e.g. onboarding tips), skip the backdrop entirely
        // so touches pass through to the WebView (tip card buttons need to be tappable).
        val backdrop = if (showBackdrop) {
            View(activity).apply {
                setBackgroundColor(0x66000000.toInt()) // 40% black dim
                setOnClickListener { dismissPopout() }
                isFocusable = false
            }
        } else null
        backdrop?.let {
            decorView.addView(it, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }

        // Add the content view with WRAP_CONTENT height initially.
        // The caller's bind() may change section visibility (e.g. locked vs unlocked),
        // so we let the view size itself, then repositionActivePopout() adjusts position.
        val contentLp = FrameLayout.LayoutParams(popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = finalX
            this.topMargin = yPos
        }
        decorView.addView(contentView, contentLp)

        popoutView = contentView
        backdropView = backdrop
        this.popoutId = popoutId
        stripPanelRef = stripPanel
        anchorButtonRef = anchorButton
        alignmentMode = alignment
        dismissCallback = onDismiss

        // Reposition after layout with actual rendered height
        contentView.post {
            repositionActivePopout()
        }
        return contentView
    }

    /**
     * Reposition the active popout using its actual rendered height.
     * Called automatically after show and can be called after dynamic content changes.
     */
    fun repositionActivePopout() {
        val contentView = popoutView ?: return
        val stripPanel = stripPanelRef ?: return

        val popupHeight = contentView.height
        val popupWidth = contentView.width
        if (popupHeight <= 0) return

        val density = activity.resources.displayMetrics.density
        val gap = (GAP_DP * density).toInt()
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val safeMargin = (16 * density).toInt()

        val stripLoc = IntArray(2)
        stripPanel.getLocationInWindow(stripLoc)
        val stripBottom = stripLoc[1] + stripPanel.height
        val stripTop = stripLoc[1]

        val topMargin = (8 * density).toInt()
        val maxHeight = stripBottom - topMargin

        var xPos: Int
        var yPos: Int
        var constrainedHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT

        if (isAboveAlignment(alignmentMode)) {
            val anchor = anchorButtonRef
            val anchorLoc = IntArray(2)
            anchor?.getLocationInWindow(anchorLoc)
            yPos = (stripTop - popupHeight - gap).coerceAtLeast(topMargin)
            xPos = if (anchor != null && alignmentMode == Alignment.ABOVE_ANCHOR_CENTER) {
                anchorLoc[0] + anchor.width / 2 - popupWidth / 2
            } else if (anchor != null) {
                anchorLoc[0]
            } else {
                safeMargin
            }
            val maxLeft = (screenWidth - popupWidth - safeMargin).coerceAtLeast(safeMargin)
            xPos = xPos.coerceIn(safeMargin, maxLeft)
        } else {
            xPos = stripLoc[0] + stripPanel.width + gap
            if (popupHeight > maxHeight) {
                yPos = topMargin
                constrainedHeight = maxHeight
            } else if (alignmentMode == Alignment.BOTTOM) {
                yPos = stripBottom - popupHeight
            } else {
                // CENTER_ON_ANCHOR: center popout vertically on the anchor button
                val anchor = anchorButtonRef
                if (anchor != null) {
                    val anchorLoc = IntArray(2)
                    anchor.getLocationInWindow(anchorLoc)
                    val anchorCenter = anchorLoc[1] + anchor.height / 2
                    yPos = anchorCenter - popupHeight / 2
                    // Clamp within strip bounds
                    val maxTop = stripBottom - popupHeight
                    yPos = yPos.coerceIn(topMargin, maxTop.coerceAtLeast(topMargin))
                } else {
                    val maxTop = stripBottom - popupHeight
                    yPos = maxTop.coerceAtLeast(topMargin)
                }
            }
        }

        Log.i(TAG, "reposition popupH=$popupHeight stripBot=$stripBottom yPos=$yPos xPos=$xPos " +
            "popBot=${yPos + popupHeight} align=$alignmentMode")

        val lp = contentView.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.leftMargin = xPos
        lp.topMargin = yPos
        lp.height = constrainedHeight
        contentView.layoutParams = lp
    }

    fun dismissPopout() {
        val content = popoutView
        val backdrop = backdropView
        val callback = dismissCallback

        popoutView = null
        backdropView = null
        popoutId = null
        stripPanelRef = null
        anchorButtonRef = null
        dismissCallback = null

        if (content?.parent != null) {
            (content.parent as? ViewGroup)?.removeView(content)
        }
        if (backdrop?.parent != null) {
            (backdrop.parent as? ViewGroup)?.removeView(backdrop)
        }
        callback?.invoke()
    }
}

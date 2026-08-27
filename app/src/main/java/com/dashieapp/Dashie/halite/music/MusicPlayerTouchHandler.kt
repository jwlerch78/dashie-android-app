package com.dashieapp.Dashie.halite.music

import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton

/**
 * Handles drag-to-reposition, free-form resize, and button click detection for the music player card.
 * In maximized mode, touch events are delegated to Android's default handling.
 */
class MusicPlayerTouchHandler(
    private val card: View,
    private val overlayContainer: View,
    private val onPositionChanged: ((Int, Int) -> Unit)?,
    private val onMinimizedTap: () -> Unit
) {

    companion object {
        private const val TAG = "MusicPlayerTouch"
    }

    private enum class TouchMode { NONE, DRAG, RESIZE }

    var currentX = 0
    var currentY = 0
    var hasCustomPosition = false

    // Saved positions per mode (survives show/hide and mode switches)
    private var savedNormalPosition: Pair<Int, Int>? = null
    private var savedMinimizedPosition: Pair<Int, Int>? = null

    /** Provides the list of clickable buttons for the current render mode. */
    var buttonProvider: (() -> List<ImageButton>)? = null

    /** Provides the volume section bounds for touch passthrough (normal mode only). */
    var volumeBoundsProvider: (() -> Rect?)? = null

    /** Provides the recently played strip bounds for touch passthrough (normal mode only). */
    var recentlyPlayedBoundsProvider: (() -> Rect?)? = null

    /** Provides the speaker row bounds for touch passthrough (normal mode only). */
    var speakerRowBoundsProvider: (() -> Rect?)? = null

    /** Called when free-form resize changes dimensions. Args: newWidth, newHeight */
    var onSizeChanged: ((Int, Int) -> Unit)? = null

    /** Minimum height in px for the current content state (prevents clipping bottom sections). */
    var minContentHeightPx: Int = 0

    /** Current uniform scale applied to content container (for scaled button hit detection). */
    var contentScale: Float = 1f

    /**
     * Called during resize MOVE to clamp proposed dimensions so the card never grows beyond
     * what the content (album art) can use. Returns constrained (width, height).
     * If null, only static min/max limits apply.
     */
    var resizeConstraintProvider: ((proposedWidth: Int, proposedHeight: Int) -> Pair<Int, Int>)? = null

    // Touch/drag/resize state
    private var touchMode = TouchMode.NONE
    private var isDragging = false
    private val dragOffset = PointF(0f, 0f)
    private var dragStartTime = 0L
    private var touchStartedOnButton = false
    private var touchedButton: ImageButton? = null

    // Resize state
    private var touchStartedInResizeZone = false
    private var resizeStartRawX = 0f
    private var resizeStartRawY = 0f
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0
    private var resizeStartLeft = 0
    private val resizeZonePx = MusicPlayerStyles.dpToPx(card.context, MusicPlayerStyles.RESIZE_HANDLE_DP + 8)
    private val minWidthPx = MusicPlayerStyles.dpToPx(card.context, MusicPlayerStyles.MIN_WIDTH_DP)
    private val maxWidthPx = MusicPlayerStyles.dpToPx(card.context, MusicPlayerStyles.MAX_WIDTH_DP)
    private val minHeightPx = MusicPlayerStyles.dpToPx(card.context, MusicPlayerStyles.MIN_HEIGHT_DP)
    private val maxHeightPx = MusicPlayerStyles.dpToPx(card.context, MusicPlayerStyles.MAX_HEIGHT_DP)

    /** Check if local touch coords are in the bottom-left resize zone (speaker bar area) */
    private fun isInResizeZone(localX: Float, localY: Float): Boolean {
        return localX < resizeZonePx && localY > (card.height - resizeZonePx)
    }

    /**
     * Handle a touch event on the card.
     * @return true if consumed, null if the card should call super.dispatchTouchEvent()
     */
    fun handleTouchEvent(
        event: MotionEvent,
        isMaximized: Boolean,
        isMinimized: Boolean,
        cardLayoutParams: FrameLayout.LayoutParams
    ): Boolean? {
        // In maximized mode, let Android handle touch normally
        if (isMaximized) return null

        // Don't interrupt an active drag/resize gesture with passthrough checks.
        // Once a gesture starts, all MOVE/UP events must reach the gesture handler.
        val gestureActive = touchStartedInResizeZone || touchMode != TouchMode.NONE

        if (!gestureActive) {
            // If touch is within the volume section, let Android handle it
            volumeBoundsProvider?.invoke()?.let { bounds ->
                if (event.y >= bounds.top && event.y <= bounds.bottom) {
                    return null
                }
            }

            // If touch is within the recently played strip, let Android handle it
            recentlyPlayedBoundsProvider?.invoke()?.let { bounds ->
                if (event.y >= bounds.top && event.y <= bounds.bottom) {
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        Log.d(TAG, "Touch in recently played bounds — passing to Android")
                    }
                    return null
                }
            }

            // Speaker row: pass through unless touch is in the resize zone
            if (!isMinimized) {
                speakerRowBoundsProvider?.invoke()?.let { bounds ->
                    if (event.y >= bounds.top && event.y <= bounds.bottom) {
                        if (!isInResizeZone(event.x, event.y)) return null
                    }
                }
            }
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchedButton = findButtonAtPoint(event)

                if (touchedButton != null) {
                    touchStartedOnButton = true
                    touchedButton?.isPressed = true
                    return true
                }

                // Check if touch is in the resize zone (bottom-left corner)
                touchStartedInResizeZone = !isMinimized && isInResizeZone(event.x, event.y)

                touchStartedOnButton = false
                touchMode = TouchMode.NONE
                isDragging = false
                dragStartTime = System.currentTimeMillis()

                // Switch from gravity-based to margin-based positioning if needed
                if (!hasCustomPosition) {
                    hasCustomPosition = true
                    currentX = card.left
                    currentY = card.top
                    cardLayoutParams.gravity = Gravity.NO_GRAVITY
                    cardLayoutParams.leftMargin = currentX
                    cardLayoutParams.topMargin = currentY
                    cardLayoutParams.rightMargin = 0
                    cardLayoutParams.bottomMargin = 0
                    card.layoutParams = cardLayoutParams
                }

                if (touchStartedInResizeZone) {
                    resizeStartRawX = event.rawX
                    resizeStartRawY = event.rawY
                    resizeStartWidth = card.width
                    resizeStartHeight = card.height
                    resizeStartLeft = currentX
                }

                dragOffset.set(event.rawX - currentX, event.rawY - currentY)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchStartedOnButton && touchedButton != null) {
                    val currentButton = findButtonAtPoint(event)
                    touchedButton?.isPressed = (currentButton == touchedButton)
                    return true
                }

                val deltaX = Math.abs(event.rawX - (currentX + dragOffset.x))
                val deltaY = Math.abs(event.rawY - (currentY + dragOffset.y))

                if (touchMode == TouchMode.NONE && (deltaX > 10 || deltaY > 10)) {
                    touchMode = if (touchStartedInResizeZone) TouchMode.RESIZE else TouchMode.DRAG
                    isDragging = touchMode == TouchMode.DRAG
                }

                when (touchMode) {
                    TouchMode.DRAG -> {
                        var newX = (event.rawX - dragOffset.x).toInt()
                        var newY = (event.rawY - dragOffset.y).toInt()

                        val parentWidth = overlayContainer.width
                        val parentHeight = overlayContainer.height
                        val cardWidth = card.width
                        val cardHeight = card.height
                        val minMargin = MusicPlayerStyles.dpToPx(card.context, 8)

                        // Guard against narrow/unmeasured containers where the
                        // card is wider than its parent — coerceIn throws if
                        // max < min (seen on StarView when parent is 0-width).
                        val maxX = (parentWidth - cardWidth - minMargin).coerceAtLeast(minMargin)
                        val minY = -cardHeight / 2
                        val maxY = (parentHeight - cardHeight - minMargin).coerceAtLeast(minY)
                        newX = newX.coerceIn(minMargin, maxX)
                        newY = newY.coerceIn(minY, maxY)

                        currentX = newX
                        currentY = newY
                        cardLayoutParams.leftMargin = currentX
                        cardLayoutParams.topMargin = currentY
                        card.layoutParams = cardLayoutParams
                        card.requestLayout()
                    }
                    TouchMode.RESIZE -> {
                        // Resize from bottom-left: dragging left increases width, dragging down increases height
                        val dx = resizeStartRawX - event.rawX  // inverted: drag left = bigger
                        val dy = event.rawY - resizeStartRawY  // drag down = taller

                        val effectiveMinHeight = maxOf(minHeightPx, minContentHeightPx)
                        // Guard same as drag path — max must be ≥ min.
                        val maxW = maxWidthPx.coerceAtLeast(minWidthPx)
                        val maxH = maxHeightPx.coerceAtLeast(effectiveMinHeight)
                        var newWidth = (resizeStartWidth + dx).toInt().coerceIn(minWidthPx, maxW)
                        var newHeight = (resizeStartHeight + dy).toInt().coerceIn(effectiveMinHeight, maxH)

                        // Apply content-aware constraints (prevents white space when art is maxed)
                        resizeConstraintProvider?.invoke(newWidth, newHeight)?.let { (cw, ch) ->
                            newWidth = cw.coerceIn(minWidthPx, maxW)
                            newHeight = ch.coerceIn(effectiveMinHeight, maxH)
                        }

                        // Adjust left margin so the right edge stays anchored
                        val widthDelta = newWidth - resizeStartWidth
                        val newX = resizeStartLeft - widthDelta

                        cardLayoutParams.width = newWidth
                        cardLayoutParams.height = newHeight
                        cardLayoutParams.leftMargin = newX
                        currentX = newX
                        card.layoutParams = cardLayoutParams
                        card.requestLayout()
                    }
                    TouchMode.NONE -> { /* waiting for threshold */ }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (touchStartedOnButton && touchedButton != null) {
                    val currentButton = findButtonAtPoint(event)
                    touchedButton?.isPressed = false
                    if (currentButton == touchedButton) {
                        touchedButton?.performClick()
                    }
                    touchedButton = null
                    touchStartedOnButton = false
                    return true
                }

                when (touchMode) {
                    TouchMode.DRAG -> {
                        onPositionChanged?.invoke(currentX, currentY)
                        Log.i(TAG, "Drag complete - position ($currentX, $currentY)")
                    }
                    TouchMode.RESIZE -> {
                        val lp = card.layoutParams as? FrameLayout.LayoutParams
                        if (lp != null) {
                            onSizeChanged?.invoke(lp.width, lp.height)
                            onPositionChanged?.invoke(currentX, currentY)
                            Log.i(TAG, "Resize complete - size (${lp.width}x${lp.height}), pos ($currentX, $currentY)")
                        }
                    }
                    TouchMode.NONE -> {
                        if (isMinimized) onMinimizedTap()
                    }
                }
                touchMode = TouchMode.NONE
                isDragging = false
                touchStartedOnButton = false
                touchStartedInResizeZone = false
                touchedButton = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touchedButton?.isPressed = false
                touchedButton = null
                touchStartedOnButton = false
                touchStartedInResizeZone = false
                touchMode = TouchMode.NONE
                isDragging = false
                return true
            }
        }
        return null
    }

    fun setPosition(x: Int, y: Int, layoutParams: FrameLayout.LayoutParams) {
        hasCustomPosition = true
        currentX = x
        currentY = y
        layoutParams.gravity = Gravity.NO_GRAVITY
        layoutParams.leftMargin = x
        layoutParams.topMargin = y
        layoutParams.rightMargin = 0
        layoutParams.bottomMargin = 0
        card.layoutParams = layoutParams
        card.requestLayout()
    }

    /**
     * Save the current drag position for the given mode before switching.
     */
    fun savePositionForMode(isMinimized: Boolean) {
        if (hasCustomPosition) {
            if (isMinimized) {
                savedMinimizedPosition = Pair(currentX, currentY)
                Log.d(TAG, "🎵 Saved minimized position ($currentX, $currentY)")
            } else {
                savedNormalPosition = Pair(currentX, currentY)
                Log.d(TAG, "🎵 Saved normal position ($currentX, $currentY)")
            }
        }
    }

    /**
     * Check if a saved position exists for the given mode.
     */
    fun hasSavedPosition(isMinimized: Boolean): Boolean {
        return if (isMinimized) savedMinimizedPosition != null else savedNormalPosition != null
    }

    /**
     * Restore a previously saved position for the given mode.
     */
    fun restoreSavedPosition(isMinimized: Boolean, layoutParams: FrameLayout.LayoutParams) {
        val pos = if (isMinimized) savedMinimizedPosition else savedNormalPosition
        if (pos != null) {
            Log.d(TAG, "🎵 Restoring ${if (isMinimized) "minimized" else "normal"} position (${pos.first}, ${pos.second})")
            setPosition(pos.first, pos.second, layoutParams)
        }
    }

    /**
     * Pre-load a saved position (from SharedPreferences) without applying it.
     * Will be applied on the next restoreSavedPosition() call.
     */
    fun setSavedPosition(isMinimized: Boolean, x: Int, y: Int) {
        if (isMinimized) {
            savedMinimizedPosition = Pair(x, y)
        } else {
            savedNormalPosition = Pair(x, y)
        }
        Log.d(TAG, "🎵 Pre-loaded ${if (isMinimized) "minimized" else "normal"} position ($x, $y)")
    }

    fun resetPosition() {
        hasCustomPosition = false
        isDragging = false
        touchMode = TouchMode.NONE
        touchStartedOnButton = false
        touchStartedInResizeZone = false
    }

    private fun findButtonAtPoint(event: MotionEvent): ImageButton? {
        val buttons = buttonProvider?.invoke() ?: return null
        val x = event.x.toInt()
        val y = event.y.toInt()

        val thisLocation = IntArray(2)
        card.getLocationInWindow(thisLocation)

        for (button in buttons) {
            if (button.visibility == View.VISIBLE && button.width > 0 && button.height > 0) {
                val location = IntArray(2)
                button.getLocationInWindow(location)
                val relativeX = location[0] - thisLocation[0]
                val relativeY = location[1] - thisLocation[1]
                val effectiveW = (button.width * contentScale).toInt()
                val effectiveH = (button.height * contentScale).toInt()
                if (x >= relativeX && x <= relativeX + effectiveW &&
                    y >= relativeY && y <= relativeY + effectiveH) {
                    return button
                }
            }
        }
        return null
    }
}

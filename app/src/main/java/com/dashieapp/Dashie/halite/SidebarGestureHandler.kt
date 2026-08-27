package com.dashieapp.Dashie.halite

import android.app.Activity
import android.util.Log
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper

/**
 * Handles edge swipe gesture detection for the sidebar.
 *
 * Responsibilities:
 * - Detects swipes from left/right edges to open sidebar
 * - Detects long-press on right arrow (2s) for tab navigation mode
 * - Tracks touch events without interfering with WebView
 *
 * Supports:
 * - Right swipe from LEFT edge (traditional)
 * - Left swipe from RIGHT edge (alternative for devices where left edge triggers back)
 */
class SidebarGestureHandler(
    private val activity: Activity,
    private val callback: SidebarGestureCallback
) {
    companion object {
        private const val TAG = "SidebarGesture"

        // Edge swipe detection thresholds
        private const val EDGE_THRESHOLD = 50f      // pixels from edge to start tracking
        private const val SWIPE_MIN_DISTANCE = 150f // minimum swipe distance
        private const val SWIPE_MAX_OFF_PATH = 250f // maximum vertical deviation

        // Long-press detection
        private const val LONG_PRESS_DURATION_MS = 2000L // 2 seconds
    }

    // Touch tracking state
    private var touchStartX: Float = -1f
    private var touchStartY: Float = -1f
    private var isRightEdgeSwipe: Boolean = false
    private var longPressHandler: Handler? = null
    private var longPressRunnable: Runnable? = null

    /**
     * Handle touch events for edge swipe and long-press detection.
     * Called from Activity's dispatchTouchEvent.
     *
     * @return true if touch was consumed, false to let others handle it
     */
    fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                return handleTouchDown(event)
            }
            MotionEvent.ACTION_MOVE -> {
                // Cancel long-press if user moves significantly
                if (touchStartX >= 0) {
                    val deltaX = Math.abs(event.rawX - touchStartX)
                    val deltaY = Math.abs(event.rawY - touchStartY)
                    if (deltaX > 20f || deltaY > 20f) {
                        cancelLongPress()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                return handleTouchUp(event)
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                touchStartX = -1f
            }
        }
        return false
    }

    private fun handleTouchDown(event: MotionEvent): Boolean {
        val x = event.rawX
        val screenWidth = activity.resources.displayMetrics.widthPixels.toFloat()
        val rightEdgeStart = screenWidth - EDGE_THRESHOLD

        // Track if touch is near left edge OR right edge
        when {
            x < EDGE_THRESHOLD -> {
                // Left edge - swipe right to open
                touchStartX = x
                touchStartY = event.rawY
                isRightEdgeSwipe = false
                Log.d(TAG, "Left edge touch at x=$x - tracking for swipe")
            }
            x > rightEdgeStart -> {
                // Right edge - swipe left to open
                touchStartX = x
                touchStartY = event.rawY
                isRightEdgeSwipe = true
                Log.d(TAG, "Right edge touch at x=$x - tracking for swipe")

                // Start long-press timer for tab navigation
                startLongPressTimer()
            }
            else -> {
                // Not an edge touch
                touchStartX = -1f
            }
        }
        return false
    }

    private fun handleTouchUp(event: MotionEvent): Boolean {
        // Cancel long-press timer
        cancelLongPress()

        // Only process if we were tracking an edge swipe
        if (touchStartX >= 0) {
            val deltaX = event.rawX - touchStartX
            val deltaY = Math.abs(event.rawY - touchStartY)

            Log.d(TAG, "Edge touch UP - deltaX=$deltaX, deltaY=$deltaY, rightEdge=$isRightEdgeSwipe")

            val isValidSwipe = if (isRightEdgeSwipe) {
                // Right edge: swipe LEFT (negative deltaX)
                deltaX < -SWIPE_MIN_DISTANCE && deltaY < SWIPE_MAX_OFF_PATH
            } else {
                // Left edge: swipe RIGHT (positive deltaX)
                deltaX > SWIPE_MIN_DISTANCE && deltaY < SWIPE_MAX_OFF_PATH
            }

            // Reset tracking
            touchStartX = -1f

            if (isValidSwipe) {
                val edgeName = if (isRightEdgeSwipe) "right" else "left"
                Log.i(TAG, "Edge swipe from $edgeName detected")
                callback.onSwipeToOpen()
                return true
            }
        }

        return false
    }

    /**
     * Start long-press timer for tab navigation mode.
     * If user holds for 2 seconds without moving, trigger tab nav mode.
     */
    private fun startLongPressTimer() {
        if (longPressHandler == null) {
            longPressHandler = Handler(Looper.getMainLooper())
        }

        longPressRunnable = Runnable {
            Log.i(TAG, "Long-press detected on right edge - triggering tab navigation")
            callback.onLongPressRight()
            touchStartX = -1f // Reset tracking
        }

        longPressHandler?.postDelayed(longPressRunnable!!, LONG_PRESS_DURATION_MS)
    }

    /**
     * Cancel long-press timer if user moves or lifts finger.
     */
    private fun cancelLongPress() {
        longPressRunnable?.let {
            longPressHandler?.removeCallbacks(it)
            longPressRunnable = null
        }
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        cancelLongPress()
        longPressHandler = null
    }
}

/**
 * Callback interface for gesture events.
 */
interface SidebarGestureCallback {
    /**
     * Called when user swipes from edge to open sidebar.
     */
    fun onSwipeToOpen()

    /**
     * Called when user long-presses (2s) on right edge while sidebar is open.
     * Used to enter tab navigation mode.
     */
    fun onLongPressRight()
}

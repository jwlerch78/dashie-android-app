package com.dashieapp.Dashie.halite.screensaver

import android.app.ActivityManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.dashieapp.Dashie.R

/**
 * Floating "return to Dashie" button that appears when an external app
 * is launched as a screensaver.
 *
 * Uses SYSTEM_ALERT_WINDOW permission to draw over other apps.
 * The button stays in the corner and when tapped, brings Dashie back.
 */
class FloatingReturnButton(private val context: Context) {

    companion object {
        private const val TAG = "FloatingReturnButton"
        private const val BUTTON_SIZE_DP = 48
        private const val MARGIN_DP = 16
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isShowing = false

    // Callback when button is clicked
    var onReturnClicked: (() -> Unit)? = null

    /**
     * Show the floating return button in the top-right corner.
     */
    fun show() {
        if (isShowing) {
            Log.d(TAG, "Already showing")
            return
        }

        // Check if we have overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "No overlay permission - cannot show floating button")
            return
        }

        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // Create the floating view
            floatingView = createFloatingView()

            // Set up window parameters
            val params = createLayoutParams()

            // Add the view to the window
            windowManager?.addView(floatingView, params)
            isShowing = true
            Log.i(TAG, "Floating return button shown")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating button: ${e.message}", e)
            isShowing = false
        }
    }

    /**
     * Hide and remove the floating button.
     */
    fun hide() {
        if (!isShowing || floatingView == null) {
            return
        }

        try {
            windowManager?.removeView(floatingView)
            Log.i(TAG, "Floating return button hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing floating button: ${e.message}", e)
        } finally {
            floatingView = null
            isShowing = false
        }
    }

    /**
     * Check if the button is currently showing.
     */
    fun isShowing(): Boolean = isShowing

    private fun createFloatingView(): View {
        // Create a simple circular button with an X or home icon
        val density = context.resources.displayMetrics.density
        val buttonSizePx = (BUTTON_SIZE_DP * density).toInt()

        val container = FrameLayout(context).apply {
            // Semi-transparent dark background
            setBackgroundResource(R.drawable.floating_button_background)
        }

        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_dashie_logo_white)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val padding = (12 * density).toInt()  // More padding to make logo ~30% smaller
            setPadding(padding, padding, padding, padding)
        }

        container.addView(icon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Handle click
        container.setOnClickListener {
            Log.i(TAG, "Return button clicked")
            // First bring Dashie to foreground using moveTaskToFront (no reload)
            bringDashieToForeground()
            // Then invoke callback to clean up screensaver state
            onReturnClicked?.invoke()
            hide()
        }

        // Make it slightly transparent until touched
        container.alpha = 0.6f
        container.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 1.0f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 0.6f
                }
            }
            false // Don't consume, let click handler work
        }

        return container
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        val buttonSizePx = (BUTTON_SIZE_DP * density).toInt()
        val marginPx = (MARGIN_DP * density).toInt()

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            buttonSizePx,
            buttonSizePx,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = marginPx
            y = marginPx
        }
    }

    /**
     * Programmatically trigger the return action (same as clicking the button).
     * This brings Dashie to foreground, invokes the callback, and hides the button.
     * Used by motion detection to return from external app.
     */
    fun triggerReturn() {
        Log.i(TAG, "Return triggered programmatically")
        bringDashieToForeground()
        onReturnClicked?.invoke()
        hide()
    }

    /**
     * Bring Dashie to foreground using moveTaskToFront.
     * This avoids recreating the activity and just brings the existing task forward.
     */
    private fun bringDashieToForeground() {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val appTasks = am.appTasks

            if (appTasks.isNotEmpty()) {
                // Move our app's task to the front - this doesn't recreate the activity
                appTasks[0].moveToFront()
                Log.i(TAG, "✓ Moved Dashie task to front")
            } else {
                Log.w(TAG, "No app tasks found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bring Dashie to foreground: ${e.message}", e)
        }
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        hide()
        windowManager = null
        onReturnClicked = null
    }
}

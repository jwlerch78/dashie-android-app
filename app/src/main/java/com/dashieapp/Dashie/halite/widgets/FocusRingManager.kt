package com.dashieapp.Dashie.halite.widgets

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.dashieapp.Dashie.R

/**
 * Manages a native Kotlin focus ring drawn above the WebView (and above native
 * widgets like photos), but below the native sidebar. Lets JS-side
 * LayoutCanvasInputHandler paint a focus indicator that can't be occluded
 * by Kotlin overlays.
 *
 * JS drives this via DashieNative.setFocusRingBounds(x, y, w, h, state) and
 * DashieNative.hideFocusRing(). Coordinates are in CSS pixels × devicePixelRatio
 * (physical pixels) so they match Kotlin's coordinate space directly.
 *
 * Two visual states:
 *   "focused"   — navigating grid: silver gradient band, 6dp thick
 *   "activated" — widget is controlled: orange gradient band, 8dp thick
 *
 * The ring is painted OUTSIDE the widget edge (3dp expansion per side) so it
 * sits in the inter-widget gap rather than overlapping widget content. Leaves
 * a 1dp safety margin before the next widget's 4dp inset territory.
 */
class FocusRingManager(
    private val activity: Activity,
    /** Visibility gate — registered OVERLAY_FULL_ONLY because the ring is
     *  event-triggered (JS setFocusRingBounds) within FULL mode, not
     *  always-on. Gate force-hides on non-FULL transitions but doesn't
     *  force visible on FULL — JS owns the show timing. Without this
     *  asymmetry, the ring would auto-restore after a reload even
     *  though JS hadn't requested it, and could overlay things like
     *  the Control Center modal. */
    private val visibilityGate: NativeWidgetVisibilityGate? = null
) {
    companion object {
        private const val TAG = "FocusRingManager"

        // Ring thickness for each state.
        private const val BORDER_DP_FOCUSED = 6f
        private const val BORDER_DP_ACTIVATED = 8f

        // Expansion outward from the widget's visible edge. Consumes 75% of the
        // slot's 4dp padding, leaving 1dp gap from slot boundary so adjacent
        // widgets never overlap the focused one.
        private const val EXPANSION_DP = 3f

        // Corner radius: widget clip is 8dp. Ring is offset 3dp outward so to
        // keep the curve looking natural, bump to 8 + 3 = 11dp.
        private const val CORNER_DP = 11f

        // Gradient bands — two-color stroke creates a subtle gradient from
        // a brighter outer edge to a darker inner edge (or vice versa).
        // Used by both focused and activated rings; width differs by state.
        private val ACTIVATED_OUTER = Color.parseColor("#FFB347")  // light orange
        private val ACTIVATED_INNER = Color.parseColor("#E07A00")  // dark orange
    }

    private var ringView: View? = null
    private var currentState: String? = null

    private fun ensureView(): View {
        val existing = ringView
        if (existing != null && existing.parent != null) return existing

        // Root FrameLayout is the parent. We insert the ring view at the index
        // RIGHT AFTER widgetContainer so it sits above Kotlin native widgets
        // (photos, weather) but below splashOverlay and sidebar_strip.
        val root = activity.findViewById<FrameLayout>(R.id.rootLayout)
        val widgetContainer = activity.findViewById<View>(R.id.widgetContainer)
        val insertAt = if (widgetContainer != null) root.indexOfChild(widgetContainer) + 1 else 1

        val view = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(0, 0)
            visibility = View.GONE
            background = buildDrawable("focused")
            isClickable = false
            isFocusable = false
        }
        root.addView(view, insertAt)
        visibilityGate?.register(view, NativeWidgetVisibilityGate.WidgetKind.OVERLAY_FULL_ONLY)
        ringView = view
        Log.i(TAG, "Focus ring view added at index $insertAt in rootLayout")
        return view
    }

    /**
     * Build a ring drawable whose stroke color cycles dark→light→dark→light
     * around the perimeter via a SweepGradient, slowly rotating to match
     * the Fire TV menu shimmer style.
     *
     * Both ring states use the brand dashie orange gradient regardless of
     * theme — themed rings (e.g. blue on Blue theme) didn't visually pop
     * as the "this widget is selected" indicator. The two states still
     * differ in width (BORDER_DP_FOCUSED vs BORDER_DP_ACTIVATED) so the
     * user can distinguish navigation focus from activation.
     */
    private fun buildDrawable(state: String): Drawable {
        val density = activity.resources.displayMetrics.density
        val borderDp = if (state == "activated") BORDER_DP_ACTIVATED else BORDER_DP_FOCUSED
        val lightColor = ACTIVATED_OUTER
        val darkColor = ACTIVATED_INNER
        // 5 stops so the sweep wraps seamlessly: dark→light→dark→light→dark
        // (last must match first for a continuous loop). Two bright spots +
        // two dark spots spaced 90° apart, so as the whole pattern rotates
        // you see a shimmer effect traveling around the frame.
        val colors = intArrayOf(darkColor, lightColor, darkColor, lightColor, darkColor)
        return PerimeterSweepRingDrawable(
            strokeWidthPx = borderDp * density,
            cornerRadiusPx = CORNER_DP * density,
            colors = colors,
            durationMs = 10_000L  // slow revolution — Fire TV-like shimmer
        )
    }


    /**
     * Show or move the focus ring.
     *
     * Coordinates are PHYSICAL pixels in the WebView's INTERNAL coordinate
     * space (CSS px × devicePixelRatio), as JS computes them from
     * getBoundingClientRect(). When the native sidebar is pinned the WebView
     * is horizontally transformed (scaleX < 1, pivotX = webView.width) to
     * make room for the sidebar — we apply the same transform here so the
     * ring lines up with the rendered widget in rootLayout space.
     *
     * @param state "focused" or "activated"
     */
    fun setBounds(physicalX: Int, physicalY: Int, physicalW: Int, physicalH: Int, state: String) {
        activity.runOnUiThread {
            val view = ensureView()
            if (state != currentState) {
                view.background = buildDrawable(state)
                currentState = state
            }

            // Apply WebView's current transform (scaleX + pivotX) so the ring
            // lines up visually with the scaled/shifted WebView content.
            // WebView uses scaleY=1 and pivotY=0 so Y passes through unchanged.
            val webView = activity.findViewById<android.view.View>(R.id.dashboardWebView)
            val scaleX = webView?.scaleX ?: 1f
            val pivotX = webView?.pivotX ?: 0f
            val transformedX = scaleX * physicalX + (1f - scaleX) * pivotX
            val transformedW = (scaleX * physicalW).coerceAtLeast(1f)

            // Expand the ring outward so it sits in the inter-widget gap
            // (not overlapping widget content). 3dp per side = 6dp growth,
            // using 75% of the slot's 4dp padding. Leaves 1dp gap to the
            // slot boundary so adjacent widgets never overlap.
            val density = activity.resources.displayMetrics.density
            val expansionPx = EXPANSION_DP * density

            val lp = (view.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(0, 0)
            lp.width = (transformedW + 2f * expansionPx).toInt()
            lp.height = (physicalH + 2f * expansionPx).toInt()
            lp.leftMargin = (transformedX - expansionPx).toInt()
            lp.topMargin = (physicalY - expansionPx).toInt()
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            view.layoutParams = lp
            view.visibility = View.VISIBLE
            view.bringToFront()  // stay above photos/weather on re-layout
        }
    }

    fun hide() {
        activity.runOnUiThread {
            ringView?.visibility = View.GONE
            // Stop the shimmer animation to save power while hidden
            (ringView?.background as? PerimeterSweepRingDrawable)?.stopAnimating()
        }
    }

    fun destroy() {
        activity.runOnUiThread {
            (ringView?.background as? PerimeterSweepRingDrawable)?.stopAnimating()
            ringView?.let {
                (it.parent as? FrameLayout)?.removeView(it)
            }
            ringView = null
            currentState = null
        }
    }

    /**
     * Ring drawable with a rotating SweepGradient stroke — dark→light→dark→
     * light cycling around the perimeter with a slow revolution, matching
     * the Fire TV menu shimmer.
     *
     * Standard GradientDrawable can't do this — its stroke is a solid color.
     * SweepGradient is an angular gradient around a center point; each angle
     * maps to a color. As we rotate the shader's local matrix every frame,
     * the color bands slide around the frame.
     */
    private class PerimeterSweepRingDrawable(
        private val strokeWidthPx: Float,
        private val cornerRadiusPx: Float,
        private val colors: IntArray,
        private val durationMs: Long = 10_000L
    ) : Drawable() {
        companion object {
            // 50ms = ~20fps. At 10s/revolution that's 1.8°/frame — smooth.
            private const val FRAME_INTERVAL_MS = 50L
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val rect = RectF()
        private val matrix = Matrix()
        private var shader: SweepGradient? = null
        private var currentAngle: Float = 0f

        // Time-based animation throttled to ~20fps (50ms intervals). At 10s/
        // revolution that's 1.8° per frame — still visibly smooth but ⅓ the
        // CPU/GPU work of a full 60fps redraw. The angle is computed from
        // elapsed time modulo duration so there's no "reset" boundary like
        // ValueAnimator's 360→0 restart — wrap happens continuously.
        //
        // Why throttle: the focus ring sits above the WebView with a
        // transparent background, so every redraw forces the WebView
        // underneath to recomposite. Cutting the rate to ⅓ proportionally
        // reduces that compositing cost.
        private val handler = Handler(Looper.getMainLooper())
        private var startTimeMs: Long = 0L
        private var animating: Boolean = false
        private val frameRunnable = Runnable { tick() }

        private fun tick() {
            if (!animating) return
            if (startTimeMs == 0L) startTimeMs = SystemClock.uptimeMillis()
            val elapsedMs = SystemClock.uptimeMillis() - startTimeMs
            val phase = (elapsedMs % durationMs).toFloat() / durationMs.toFloat()
            currentAngle = phase * 360f
            invalidateSelf()
            handler.postDelayed(frameRunnable, FRAME_INTERVAL_MS)
        }

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            super.onBoundsChange(bounds)
            val cx = bounds.exactCenterX().toFloat()
            val cy = bounds.exactCenterY().toFloat()
            shader = SweepGradient(cx, cy, colors, null)
            startAnimating()
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val half = strokeWidthPx / 2f
            rect.set(
                b.left + half,
                b.top + half,
                b.right - half,
                b.bottom - half
            )

            val s = shader ?: run {
                shader = SweepGradient(
                    b.exactCenterX().toFloat(),
                    b.exactCenterY().toFloat(),
                    colors, null
                )
                shader!!
            }
            matrix.reset()
            matrix.postRotate(currentAngle, b.exactCenterX().toFloat(), b.exactCenterY().toFloat())
            s.setLocalMatrix(matrix)
            paint.shader = s

            val innerCorner = (cornerRadiusPx - half).coerceAtLeast(0f)
            canvas.drawRoundRect(rect, innerCorner, innerCorner, paint)
        }

        fun startAnimating() {
            if (animating) return
            animating = true
            startTimeMs = 0L
            handler.post(frameRunnable)
        }

        fun stopAnimating() {
            animating = false
            handler.removeCallbacks(frameRunnable)
        }

        override fun setAlpha(alpha: Int) {
            if (paint.alpha != alpha) {
                paint.alpha = alpha
                invalidateSelf()
            }
        }

        override fun setColorFilter(cf: ColorFilter?) {
            paint.colorFilter = cf
            invalidateSelf()
        }

        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

package com.dashieapp.Dashie.halite.diagnostics

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.dashieapp.Dashie.R

/**
 * Performance overlay view that displays real-time system metrics.
 * Rendered in the Kotlin layer (not WebView) to avoid click-through issues.
 *
 * Now uses Activity-level views (FrameLayout.LayoutParams) instead of
 * WindowManager overlays, eliminating the need for SYSTEM_ALERT_WINDOW permission.
 *
 * Displays:
 * - CPU usage percentage
 * - RAM usage percentage
 * - PSS (app memory) in MB
 *
 * Features:
 * - Drag to reposition (drag by header)
 */
class PerformanceOverlayView(
    context: Context,
    private val overlayContainer: ViewGroup,
    private val onClose: () -> Unit,
    private val initialPosition: String = POSITION_TOP_RIGHT
) : FrameLayout(context) {

    companion object {
        private const val TAG = "PerfOverlay"

        // Fixed PSS scale (based on default critical threshold 550 + 50 buffer)
        // This stays constant regardless of whether PSS thresholds are enabled
        private const val DEFAULT_PSS_SCALE_MB = 600

        // Color thresholds
        private const val WARNING_THRESHOLD = 70
        private const val CRITICAL_THRESHOLD = 85

        // Colors matching iOS-style design
        private const val COLOR_NORMAL = "#34C759"   // Green
        private const val COLOR_WARNING = "#FF9500"  // Orange
        private const val COLOR_CRITICAL = "#FF3B30" // Red

        // Position constants (match PerformancePreferences)
        const val POSITION_TOP_LEFT = "top-left"
        const val POSITION_TOP_RIGHT = "top-right"
        const val POSITION_BOTTOM_LEFT = "bottom-left"
        const val POSITION_BOTTOM_RIGHT = "bottom-right"
    }

    val cardLayoutParams: FrameLayout.LayoutParams

    // Views - main metrics
    private var cpuValue: TextView? = null
    private var cpuBar: ProgressBar? = null
    private var ramLabel: TextView? = null
    private var ramValue: TextView? = null
    private var ramBar: ProgressBar? = null
    private var ramBarContainer: FrameLayout? = null
    private var ramIdleTick: View? = null
    private var ramCriticalTick: View? = null
    private var pssValue: TextView? = null
    private var pssBar: ProgressBar? = null
    private var pssBarContainer: FrameLayout? = null
    private var pssIdleTick: View? = null
    private var pssCriticalTick: View? = null
    private var heapLabel: TextView? = null
    private var heapValue: TextView? = null
    private var heapBar: ProgressBar? = null
    private var heapBarContainer: FrameLayout? = null
    private var heapIdleTick: View? = null
    private var heapCriticalTick: View? = null

    // Views - controls
    private var closeButton: ImageButton? = null
    private var header: LinearLayout? = null

    // Drag state
    private var isDragging = false
    private val dragOffset = PointF(0f, 0f)

    // Current position (for drag handling)
    // Which margins we use depends on gravity (anchor point)
    private var currentHMargin = 0  // Horizontal margin (left or right depending on gravity)
    private var currentVMargin = 0  // Vertical margin (top or bottom depending on gravity)

    // Track which side the gravity anchors to
    private var anchorRight = true   // true = Gravity.END (use rightMargin), false = Gravity.START (use leftMargin)
    private var anchorBottom = false // true = Gravity.BOTTOM (use bottomMargin), false = Gravity.TOP (use topMargin)

    // Threshold values for bar scaling and coloring
    // PSS scale is fixed at DEFAULT_PSS_SCALE_MB so the graph doesn't jump when changing settings
    private var pssMaxMb = DEFAULT_PSS_SCALE_MB
    private var ramIdleThreshold = 85
    private var ramCriticalThreshold = 92
    private var heapIdleThreshold = 80
    private var heapCriticalThreshold = 88

    init {
        val displayMetrics = context.resources.displayMetrics
        val margin = (16 * displayMetrics.density).toInt()

        // Calculate gravity and anchor flags based on position preference
        val positionGravity = when (initialPosition) {
            POSITION_TOP_LEFT -> {
                anchorRight = false
                anchorBottom = false
                Gravity.TOP or Gravity.START
            }
            POSITION_TOP_RIGHT -> {
                anchorRight = true
                anchorBottom = false
                Gravity.TOP or Gravity.END
            }
            POSITION_BOTTOM_LEFT -> {
                anchorRight = false
                anchorBottom = true
                Gravity.BOTTOM or Gravity.START
            }
            POSITION_BOTTOM_RIGHT -> {
                anchorRight = true
                anchorBottom = true
                Gravity.BOTTOM or Gravity.END
            }
            else -> {
                anchorRight = true
                anchorBottom = false
                Gravity.TOP or Gravity.END  // Default to top-right
            }
        }

        // Use FrameLayout.LayoutParams instead of WindowManager.LayoutParams
        // No SYSTEM_ALERT_WINDOW permission needed!
        cardLayoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = positionGravity
            // Set appropriate margins based on gravity
            when (initialPosition) {
                POSITION_TOP_LEFT -> {
                    leftMargin = margin
                    topMargin = margin
                }
                POSITION_TOP_RIGHT -> {
                    rightMargin = margin
                    topMargin = margin
                }
                POSITION_BOTTOM_LEFT -> {
                    leftMargin = margin
                    bottomMargin = margin
                }
                else -> { // POSITION_BOTTOM_RIGHT or default
                    rightMargin = margin
                    bottomMargin = margin
                }
            }
        }

        currentHMargin = margin
        currentVMargin = margin

        render()
    }

    private fun render() {
        removeAllViews()

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.performance_overlay, this, true)

        // Find main metric views
        cpuValue = view.findViewById(R.id.perf_cpu_value)
        cpuBar = view.findViewById(R.id.perf_cpu_bar)
        ramLabel = view.findViewById(R.id.perf_ram_label)
        ramValue = view.findViewById(R.id.perf_ram_value)
        ramBar = view.findViewById(R.id.perf_ram_bar)
        ramBarContainer = view.findViewById(R.id.perf_ram_bar_container)
        ramIdleTick = view.findViewById(R.id.perf_ram_idle_tick)
        ramCriticalTick = view.findViewById(R.id.perf_ram_critical_tick)
        pssValue = view.findViewById(R.id.perf_pss_value)
        pssBar = view.findViewById(R.id.perf_pss_bar)
        pssBarContainer = view.findViewById(R.id.perf_pss_bar_container)
        pssIdleTick = view.findViewById(R.id.perf_pss_idle_tick)
        pssCriticalTick = view.findViewById(R.id.perf_pss_critical_tick)
        heapLabel = view.findViewById(R.id.perf_heap_label)
        heapValue = view.findViewById(R.id.perf_heap_value)
        heapBar = view.findViewById(R.id.perf_heap_bar)
        heapBarContainer = view.findViewById(R.id.perf_heap_bar_container)
        heapIdleTick = view.findViewById(R.id.perf_heap_idle_tick)
        heapCriticalTick = view.findViewById(R.id.perf_heap_critical_tick)

        // Find control views
        closeButton = view.findViewById(R.id.perf_close)
        header = view.findViewById(R.id.perf_header)

        // Wire close button
        closeButton?.setOnClickListener {
            Log.d(TAG, "Close button clicked")
            onClose()
        }

        // Wire drag on header
        header?.setOnTouchListener { _, event -> handleDrag(event) }
    }

    /**
     * Handle drag events on header.
     * The margins that matter depend on the gravity anchor:
     * - Gravity.END uses rightMargin (drag right = decrease margin)
     * - Gravity.START uses leftMargin (drag right = increase margin)
     * - Gravity.BOTTOM uses bottomMargin (drag down = decrease margin)
     * - Gravity.TOP uses topMargin (drag down = increase margin)
     */
    private fun handleDrag(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                // Store initial position for delta calculation
                // For anchored-to-end margins (right/bottom), we add the margin
                // For anchored-to-start margins (left/top), we subtract the margin
                dragOffset.x = if (anchorRight) event.rawX + currentHMargin else event.rawX - currentHMargin
                dragOffset.y = if (anchorBottom) event.rawY + currentVMargin else event.rawY - currentVMargin
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    // Calculate new margins based on anchor side
                    if (anchorRight) {
                        // Dragging right decreases rightMargin (moves away from right edge)
                        currentHMargin = (dragOffset.x - event.rawX).toInt().coerceAtLeast(0)
                        cardLayoutParams.rightMargin = currentHMargin
                    } else {
                        // Dragging right increases leftMargin (moves away from left edge)
                        currentHMargin = (event.rawX - dragOffset.x).toInt().coerceAtLeast(0)
                        cardLayoutParams.leftMargin = currentHMargin
                    }

                    if (anchorBottom) {
                        // Dragging down decreases bottomMargin (moves away from bottom edge)
                        currentVMargin = (dragOffset.y - event.rawY).toInt().coerceAtLeast(0)
                        cardLayoutParams.bottomMargin = currentVMargin
                    } else {
                        // Dragging down increases topMargin (moves away from top edge)
                        currentVMargin = (event.rawY - dragOffset.y).toInt().coerceAtLeast(0)
                        cardLayoutParams.topMargin = currentVMargin
                    }

                    layoutParams = cardLayoutParams
                    requestLayout()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return false
    }

    /**
     * Update the displayed metrics
     */
    fun updateMetrics(
        cpu: Int,
        ram: Int,
        pssMB: Int,
        heapPercent: Int = 0
    ) {
        // Update CPU
        cpuValue?.text = "$cpu%"
        cpuBar?.progress = cpu
        setColorForValue(cpuValue, cpu)

        // Update RAM - color based on actual user thresholds
        ramValue?.text = "$ram%"
        ramBar?.progress = ram
        setColorForThreshold(ramValue, ram, ramIdleThreshold, ramCriticalThreshold)

        // Update PSS (app memory) - color based on percentage of scale
        pssValue?.text = "$pssMB MB"
        val pssPercent = if (pssMaxMb > 0) (pssMB * 100 / pssMaxMb).coerceIn(0, 100) else 0
        pssBar?.progress = pssPercent
        setColorForValue(pssValue, pssPercent)

        // Update Heap % - color based on actual user thresholds
        heapValue?.text = "$heapPercent%"
        heapBar?.progress = heapPercent
        setColorForThreshold(heapValue, heapPercent, heapIdleThreshold, heapCriticalThreshold)
    }

    /**
     * Set the threshold values for RAM and Heap bar tick marks.
     *
     * @param ramIdlePercent RAM idle threshold (0 = disabled)
     * @param ramCriticalPercent RAM critical threshold (0 = disabled)
     * @param heapIdlePercent Heap idle threshold (0 = disabled)
     * @param heapCriticalPercent Heap critical threshold (0 = disabled)
     * @param enabled Whether threshold-based refresh is enabled (hides all ticks when false)
     */
    fun setThresholds(
        ramIdlePercent: Int,
        ramCriticalPercent: Int,
        heapIdlePercent: Int = 0,
        heapCriticalPercent: Int = 0,
        enabled: Boolean = true
    ) {
        // Store thresholds for coloring
        ramIdleThreshold = if (ramIdlePercent > 0) ramIdlePercent else 82
        ramCriticalThreshold = if (ramCriticalPercent > 0) ramCriticalPercent else 90
        heapIdleThreshold = if (heapIdlePercent > 0) heapIdlePercent else 80
        heapCriticalThreshold = if (heapCriticalPercent > 0) heapCriticalPercent else 88

        // Position tick marks after layout is complete
        post {
            if (!enabled) {
                ramIdleTick?.visibility = View.GONE
                ramCriticalTick?.visibility = View.GONE
                pssIdleTick?.visibility = View.GONE
                pssCriticalTick?.visibility = View.GONE
                heapIdleTick?.visibility = View.GONE
                heapCriticalTick?.visibility = View.GONE
            } else {
                // RAM tick marks (0-100% scale)
                positionTickMark(ramIdleTick, ramBarContainer, ramIdlePercent, 100)
                positionTickMark(ramCriticalTick, ramBarContainer, ramCriticalPercent, 100)

                // PSS ticks hidden (no PSS thresholds)
                pssIdleTick?.visibility = View.GONE
                pssCriticalTick?.visibility = View.GONE

                // Heap tick marks (0-100% scale)
                positionTickMark(heapIdleTick, heapBarContainer, heapIdlePercent, 100)
                positionTickMark(heapCriticalTick, heapBarContainer, heapCriticalPercent, 100)
            }
        }
    }

    /**
     * Position a tick mark view at the specified value within its container.
     */
    private fun positionTickMark(tick: View?, container: FrameLayout?, value: Int, max: Int) {
        if (tick == null || container == null || value <= 0 || max <= 0) {
            tick?.visibility = View.GONE
            return
        }

        tick.visibility = View.VISIBLE
        val containerWidth = container.width
        if (containerWidth <= 0) {
            // Container not yet laid out, try again later
            tick.visibility = View.GONE
            return
        }

        // Calculate position as percentage of container width
        val position = (value.toFloat() / max.toFloat() * containerWidth).toInt()
        val lp = tick.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(
            (2 * resources.displayMetrics.density).toInt(),
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        lp.marginStart = position.coerceIn(0, containerWidth - lp.width)
        tick.layoutParams = lp
    }

    private fun setColorForValue(textView: TextView?, value: Int) {
        val color = when {
            value >= CRITICAL_THRESHOLD -> Color.parseColor(COLOR_CRITICAL)
            value >= WARNING_THRESHOLD -> Color.parseColor(COLOR_WARNING)
            else -> Color.parseColor(COLOR_NORMAL)
        }
        textView?.setTextColor(color)
    }

    /**
     * Set color based on actual user-configured thresholds.
     * Red = at or above critical, Orange = at or above idle, Green = below idle.
     */
    private fun setColorForThreshold(textView: TextView?, value: Int, idleThreshold: Int, criticalThreshold: Int) {
        val color = when {
            criticalThreshold > 0 && value >= criticalThreshold -> Color.parseColor(COLOR_CRITICAL)
            idleThreshold > 0 && value >= idleThreshold -> Color.parseColor(COLOR_WARNING)
            else -> Color.parseColor(COLOR_NORMAL)
        }
        textView?.setTextColor(color)
    }

    /**
     * Set position of the overlay using margin values.
     * The margins are applied based on the anchor side (gravity).
     *
     * @param hMargin Horizontal margin (applied to left or right based on gravity)
     * @param vMargin Vertical margin (applied to top or bottom based on gravity)
     */
    fun setPosition(hMargin: Int, vMargin: Int) {
        currentHMargin = hMargin
        currentVMargin = vMargin

        if (anchorRight) {
            cardLayoutParams.rightMargin = hMargin
        } else {
            cardLayoutParams.leftMargin = hMargin
        }

        if (anchorBottom) {
            cardLayoutParams.bottomMargin = vMargin
        } else {
            cardLayoutParams.topMargin = vMargin
        }

        layoutParams = cardLayoutParams
        requestLayout()
    }

    /**
     * Set the total device RAM to display in the RAM label.
     * Displays as "RAM (2,048 MB)" with comma formatting.
     */
    fun setTotalRamMb(totalMb: Int) {
        val formatted = String.format("%,d", totalMb)
        ramLabel?.text = "RAM ($formatted MB)"
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        Log.d(TAG, "Destroying performance overlay view")
    }
}

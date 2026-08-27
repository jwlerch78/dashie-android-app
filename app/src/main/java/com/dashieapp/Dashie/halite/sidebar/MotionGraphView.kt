package com.dashieapp.Dashie.halite.sidebar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Custom view that displays a real-time motion score graph.
 * Shows the last 5 seconds of motion data with:
 * - X axis: time in seconds (0-5)
 * - Y axis: motion score percentage (0-20%)
 * - Dotted horizontal line showing current threshold
 * - Filled area under the motion curve
 */
class MotionGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val GRAPH_DURATION_MS = 5000L  // Show last 5 seconds
        private const val MAX_DATA_POINTS = 100     // Max points to store
        private const val Y_AXIS_MAX = 20.0         // Max Y value (20%)
    }

    // Data storage: timestamp to motion score
    private val dataPoints = mutableListOf<Pair<Long, Double>>()

    // Current threshold (in percentage, e.g., 5.0 for 5%)
    private var thresholdPercent: Double = 5.0

    // Paints
    private val linePaint = Paint().apply {
        color = Color.parseColor("#FF9500")  // Dashie orange
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#40FF9500")  // Dashie orange with 25% alpha
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val thresholdPaint = Paint().apply {
        color = Color.parseColor("#4CAF50")  // Green
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)  // Dotted line
    }

    private val axisPaint = Paint().apply {
        color = Color.parseColor("#666666")  // Gray
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.parseColor("#999999")  // Light gray
        textSize = 14f  // Smaller font
        isAntiAlias = true
    }

    private val thresholdLabelPaint = Paint().apply {
        color = Color.parseColor("#4CAF50")  // Green
        textSize = 12f  // Smaller font
        isAntiAlias = true
    }

    private val linePath = Path()
    private val fillPath = Path()

    /**
     * Add a new motion score data point.
     * @param score Motion score percentage (0-100)
     */
    fun addDataPoint(score: Double) {
        val now = System.currentTimeMillis()
        dataPoints.add(Pair(now, score))

        // Remove old data points (older than 5 seconds)
        val cutoff = now - GRAPH_DURATION_MS
        dataPoints.removeAll { it.first < cutoff }

        // Limit total points for performance
        while (dataPoints.size > MAX_DATA_POINTS) {
            dataPoints.removeAt(0)
        }

        invalidate()
    }

    /**
     * Set the threshold line position.
     * @param thresholdTenths Threshold in tenths (e.g., 50 for 5.0%)
     */
    fun setThreshold(thresholdTenths: Int) {
        thresholdPercent = thresholdTenths / 10.0
        invalidate()
    }

    /**
     * Clear all data points.
     */
    fun clear() {
        dataPoints.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val paddingLeft = 8f   // Minimal left padding
        val paddingRight = 8f  // Minimal right padding
        val paddingTop = 12f
        val paddingBottom = 20f  // Space for X axis label
        val yLabelWidth = 18f  // Space for Y axis labels
        val graphLeft = paddingLeft + yLabelWidth
        val graphRight = w - paddingRight
        val graphTop = paddingTop
        val graphBottom = h - paddingBottom
        val graphWidth = graphRight - graphLeft
        val graphHeight = graphBottom - graphTop

        // Draw Y axis
        canvas.drawLine(graphLeft, graphTop, graphLeft, graphBottom, axisPaint)

        // Draw X axis
        canvas.drawLine(graphLeft, graphBottom, graphRight, graphBottom, axisPaint)

        // Draw Y axis labels (0%, 10%, 20%) - smaller, closer to axis
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("0", graphLeft - 3f, graphBottom + 4f, labelPaint)
        canvas.drawText("10", graphLeft - 3f, graphTop + graphHeight / 2 + 4f, labelPaint)
        canvas.drawText("20", graphLeft - 3f, graphTop + 4f, labelPaint)

        // Draw X axis label - only "5s" on the right (newer data)
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("5s", graphRight, graphBottom + 14f, labelPaint)

        // Draw threshold line
        val thresholdY = graphBottom - (thresholdPercent / Y_AXIS_MAX * graphHeight).toFloat()
        if (thresholdY >= graphTop && thresholdY <= graphBottom) {
            canvas.drawLine(graphLeft, thresholdY, graphRight, thresholdY, thresholdPaint)
            // Draw threshold label inside graph area, on the right
            thresholdLabelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("${String.format("%.1f", thresholdPercent)}%", graphRight - 3f, thresholdY - 3f, thresholdLabelPaint)
        }

        // Draw motion data
        if (dataPoints.size >= 2) {
            val now = System.currentTimeMillis()
            val startTime = now - GRAPH_DURATION_MS

            linePath.reset()
            fillPath.reset()
            var firstPoint = true

            for ((timestamp, score) in dataPoints) {
                // Calculate X position (time)
                val timeOffset = timestamp - startTime
                val xPercent = timeOffset.toFloat() / GRAPH_DURATION_MS
                val x = graphLeft + (1 - xPercent) * graphWidth  // Invert so newer is on right

                // Calculate Y position (score, capped at Y_AXIS_MAX)
                val cappedScore = score.coerceAtMost(Y_AXIS_MAX)
                val y = graphBottom - (cappedScore / Y_AXIS_MAX * graphHeight).toFloat()

                if (firstPoint) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, graphBottom)
                    fillPath.lineTo(x, y)
                    firstPoint = false
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }

            // Close fill path
            if (dataPoints.isNotEmpty()) {
                val lastPoint = dataPoints.last()
                val lastTimeOffset = lastPoint.first - startTime
                val lastXPercent = lastTimeOffset.toFloat() / GRAPH_DURATION_MS
                val lastX = graphLeft + (1 - lastXPercent) * graphWidth
                fillPath.lineTo(lastX, graphBottom)
                fillPath.close()
            }

            // Draw fill first, then line on top
            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(linePath, linePaint)
        }
    }
}

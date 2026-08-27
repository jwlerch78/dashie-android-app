package com.dashieapp.Dashie.halite.videofeed

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Row of day buttons below the scrubber. Rendered oldest → newest (left → right)
 * to match the timeline's flow, so Today sits on the right. Tapping (touch) or
 * selecting (d-pad) snaps the timeline to that day; days with no footage are
 * dimmed and not selectable.
 *
 * Two visual states, independent:
 *  - ACTIVE day (the day the timeline is currently showing): solid orange.
 *  - d-pad FOCUS (the hovered day in the DAYS zone): blue border.
 *
 * Index semantics: 0 = today, 1 = yesterday, … (independent of render order).
 */
class FrigateDayBar(private val context: Context) {
    companion object {
        private const val DAY_COUNT = 7
        private const val ACTIVE_COLOR = 0xFFFF9500.toInt()   // solid orange
        private const val IDLE_COLOR = 0xFF2A2A2A.toInt()
        private const val FOCUS_COLOR = 0xFF2196F3.toInt()    // blue border
    }

    /** Fired on tap (touch) or d-pad select with the day index (0 = today). */
    var onDaySelected: ((index: Int) -> Unit)? = null

    private val buttons = arrayOfNulls<TextView>(DAY_COUNT)
    private val available = BooleanArray(DAY_COUNT) { true }
    private var activeIndex = 0
    private var focusIndex = -1

    val count: Int get() = DAY_COUNT

    /** Build the button row view (oldest on the left, Today on the right). */
    fun build(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1A1A1A.toInt())
            val pad = dp(4)
            setPadding(pad, pad, pad, pad)
        }
        for (visual in 0 until DAY_COUNT) {
            val index = DAY_COUNT - 1 - visual  // leftmost = oldest, rightmost = today
            val btn = makeButton(index)
            buttons[index] = btn
            row.addView(btn)
        }
        refresh()
        return row
    }

    private fun makeButton(index: Int): TextView = TextView(context).apply {
        text = labelFor(index)
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 12f
        maxLines = 1
        val pv = dp(6)
        setPadding(dp(4), pv, dp(4), pv)
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { val m = dp(3); setMargins(m, 0, m, 0) }
        setOnClickListener { if (available[index]) onDaySelected?.invoke(index) }
    }

    /** d-pad focus (blue border), or clear when null. */
    fun highlightDay(index: Int?) { focusIndex = index ?: -1; refresh() }

    /** The day the timeline is currently showing (solid orange). */
    fun setActiveDay(index: Int) { activeIndex = index; refresh() }

    /** Dim + disable days with no footage (Today always available), from a 7-day summary. */
    fun setAvailabilityFromSummary(weekHours: List<RecordingHour>) {
        for (i in 0 until DAY_COUNT) {
            val a = if (i == 0) true else {
                val start = dayStartSec(i)
                weekHours.any { it.hour in start until (start + 24 * 3600) && it.duration >= 60 }
            }
            available[i] = a
            buttons[i]?.apply { alpha = if (a) 1f else 0.35f; isClickable = a; isFocusable = a }
        }
    }

    fun isAvailable(index: Int): Boolean = available.getOrElse(index) { false }

    /** Which day index a timestamp falls in (0 = today; clamps to oldest shown). */
    fun dayIndexForTime(ts: Long): Int {
        for (i in 0 until DAY_COUNT) if (ts >= dayStartSec(i)) return i
        return DAY_COUNT - 1
    }

    /** Local midnight (start of day) for day [index] back from today, in seconds. */
    fun dayStartSec(index: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -index)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis / 1000
    }

    private fun refresh() {
        for (i in 0 until DAY_COUNT) buttons[i]?.background = chipBg(i == activeIndex, i == focusIndex)
    }

    private fun labelFor(index: Int): String = when (index) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -index)
            SimpleDateFormat("M/d", Locale.getDefault()).format(cal.time)
        }
    }

    private fun chipBg(active: Boolean, focused: Boolean) = GradientDrawable().apply {
        setColor(if (active) ACTIVE_COLOR else IDLE_COLOR)
        if (focused) setStroke(dp(2), FOCUS_COLOR)
        cornerRadius = dp(6).toFloat()
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}

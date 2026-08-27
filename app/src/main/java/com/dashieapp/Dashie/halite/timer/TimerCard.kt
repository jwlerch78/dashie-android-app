package com.dashieapp.Dashie.halite.timer

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.PointF
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.preferences.DisplaySizeScale

/**
 * Timer card view that displays a timer in expanded or minimized mode.
 * Supports drag-to-reposition.
 *
 * Now uses Activity-level views (FrameLayout.LayoutParams) instead of
 * WindowManager overlays, eliminating the need for SYSTEM_ALERT_WINDOW permission.
 */
class TimerCard(
    context: Context,
    private val overlayContainer: ViewGroup,
    initialData: TimerData,
    private val onPositionChanged: (String, Int, Int) -> Unit,
    private val onPauseResume: (String) -> Unit,
    private val onCancel: (String) -> Unit,
    private val onMinimizeExpand: (String) -> Unit
) : FrameLayout(context) {

    companion object {
        private const val TAG = "TimerCard"
    }

    private var timerData: TimerData = initialData
    val cardLayoutParams: FrameLayout.LayoutParams

    // Views (expanded mode)
    private var timeTextExpanded: TextView? = null
    private var labelTextExpanded: TextView? = null
    private var descriptionTextExpanded: TextView? = null
    private var pauseResumeButton: ImageButton? = null
    private var cancelButton: ImageButton? = null
    private var minimizeButton: ImageButton? = null

    // Views (minimized mode)
    private var timeTextMinimized: TextView? = null
    private var slotBadgeMinimized: TextView? = null
    private var expandButtonMinimized: ImageButton? = null

    // Drag state
    private var isDragging = false
    private val dragOffset = PointF(0f, 0f)
    private var dragStartTime = 0L
    private var touchStartedOnButton = false

    // Pulsing animation for paused state
    private var pulsingAnimator: ValueAnimator? = null

    // Flashing animation for completed state
    private var flashingAnimator: ValueAnimator? = null

    // Current position (for drag handling)
    private var currentX = 0
    private var currentY = 0

    init {
        // Set explicit size to include badge overhang space (360dp card + 14dp badge padding)
        val displayMetrics = context.resources.displayMetrics
        val cardWidth = (360 * displayMetrics.density).toInt()
        val badgePadding = (14 * displayMetrics.density).toInt()

        // Use FrameLayout.LayoutParams instead of WindowManager.LayoutParams
        // No SYSTEM_ALERT_WINDOW permission needed!
        cardLayoutParams = FrameLayout.LayoutParams(
            cardWidth + badgePadding,  // Extra width for badge
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        // Honor the widget_zoom knob (no-op at 100%). The card's content is
        // XML-inflated, so scale the whole view as a render transform — uniform
        // (text + buttons + badge) without touching the layout's sp values.
        // Pivot top-left matches the TOP|START anchor so the card grows down-right
        // from its positioned corner rather than drifting off-screen.
        val zoomScale = DisplaySizeScale.scale(context)
        if (zoomScale != 1f) {
            pivotX = 0f
            pivotY = 0f
            scaleX = zoomScale
            scaleY = zoomScale
        }

        render()
    }

    /**
     * Render or re-render the timer card (expanded or minimized)
     */
    private fun render() {
        removeAllViews()

        if (timerData.isMinimized) {
            renderMinimized()
        } else {
            renderExpanded()
        }

        updateVisualState()
    }

    /**
     * Render expanded card layout
     */
    private fun renderExpanded() {
        val view = LayoutInflater.from(context).inflate(R.layout.timer_card_expanded, this, true)

        timeTextExpanded = view.findViewById(R.id.timer_time)
        labelTextExpanded = view.findViewById(R.id.timer_label)
        val slotBadge = view.findViewById<TextView>(R.id.timer_slot_badge)
        val header = view.findViewById<LinearLayout>(R.id.timer_header)
        val body = view.findViewById<LinearLayout>(R.id.timer_body)
        val cardContent = view.findViewById<LinearLayout>(R.id.timer_card_content)
        pauseResumeButton = view.findViewById(R.id.timer_pause_resume)
        cancelButton = view.findViewById(R.id.timer_cancel)
        minimizeButton = view.findViewById(R.id.timer_minimize)

        // Set label: use description if exists (named timer), otherwise use "Timer 1" format
        // Append duration suffix from JS label in smaller text (e.g., "(5 min)")
        val durationSuffix = timerData.label.let { label ->
            val parenIdx = label.indexOf(" (")
            if (parenIdx >= 0) " ${label.substring(parenIdx + 1)}" else ""
        }
        val namePart = if (!timerData.description.isNullOrEmpty() && timerData.description != "null") {
            timerData.description!!.uppercase()
        } else {
            val labelText = timerData.label.takeIf { it.isNotEmpty() && it != "null" }
                ?: "Timer ${timerData.slot}"
            labelText.substringBefore(" (").uppercase()
        }
        val suffixPart = durationSuffix.uppercase()
        val spannable = SpannableString("$namePart$suffixPart")
        if (suffixPart.isNotEmpty()) {
            spannable.setSpan(
                RelativeSizeSpan(0.7f),
                namePart.length,
                spannable.length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        labelTextExpanded?.text = spannable

        // Set slot badge
        slotBadge?.text = timerData.slot.toString()

        // Set time
        timeTextExpanded?.text = timerData.formatTime()

        // Apply theme colors (dark vs light mode)
        applyThemeColors(cardContent, header, body, labelTextExpanded, timeTextExpanded, minimizeButton, cancelButton)

        // Wire up button handlers
        pauseResumeButton?.setOnClickListener {
            Log.i(TAG, "⏱️ Pause/Resume button clicked for timer ${timerData.id}")
            onPauseResume(timerData.id)
        }
        cancelButton?.setOnClickListener {
            Log.i(TAG, "⏱️ Cancel button clicked for timer ${timerData.id}")
            onCancel(timerData.id)
        }
        minimizeButton?.setOnClickListener {
            Log.i(TAG, "⏱️ Minimize button clicked for timer ${timerData.id}")
            onMinimizeExpand(timerData.id)
        }

        updatePauseResumeButton()
    }

    /**
     * Apply theme colors based on system dark/light mode
     */
    private fun applyThemeColors(
        cardContent: LinearLayout?,
        header: LinearLayout?,
        body: LinearLayout?,
        label: TextView?,
        time: TextView?,
        minimizeBtn: ImageButton?,
        cancelBtn: ImageButton?
    ) {
        val isDarkMode = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

        if (isDarkMode) {
            // Dark mode - use drawables with rounded corners
            cardContent?.setBackgroundResource(R.drawable.timer_card_background_dark)
            header?.setBackgroundResource(R.drawable.timer_header_background_dark)
            label?.setTextColor(0xFFB0B0B0.toInt())
            time?.setTextColor(0xFFFFFFFF.toInt())
            minimizeBtn?.setColorFilter(0xFFFFFFFF.toInt())
            cancelBtn?.setBackgroundResource(R.drawable.rounded_background_dark_gray)
            cancelBtn?.setColorFilter(0xFFFFFFFF.toInt())
        } else {
            // Light mode - use drawables with rounded corners
            cardContent?.setBackgroundResource(R.drawable.timer_card_background_light)
            header?.setBackgroundResource(R.drawable.timer_header_background_light)
            label?.setTextColor(0xFF404040.toInt())
            time?.setTextColor(0xFF000000.toInt())
            minimizeBtn?.setColorFilter(0xFF404040.toInt())
            cancelBtn?.setBackgroundResource(R.drawable.rounded_background_light_gray)
            cancelBtn?.setColorFilter(0xFF333333.toInt())
        }
    }

    /**
     * Render minimized pill layout
     */
    private fun renderMinimized() {
        // Inflate and add to this FrameLayout
        LayoutInflater.from(context).inflate(R.layout.timer_card_minimized, this, true)

        // Get the inflated LinearLayout (first child after inflation)
        val container = getChildAt(0) as? LinearLayout

        timeTextMinimized = findViewById(R.id.timer_time_minimized)
        slotBadgeMinimized = findViewById(R.id.timer_slot_badge_minimized)
        expandButtonMinimized = findViewById(R.id.timer_expand)

        timeTextMinimized?.text = timerData.formatTime()
        slotBadgeMinimized?.text = timerData.slot.toString()

        // Apply theme colors to minimized view
        container?.let { applyMinimizedThemeColors(it) }

        expandButtonMinimized?.setOnClickListener {
            Log.i(TAG, "⏱️ Expand button clicked for timer ${timerData.id}")
            onMinimizeExpand(timerData.id)
        }
    }

    /**
     * Apply theme colors to minimized view based on system dark/light mode
     */
    private fun applyMinimizedThemeColors(container: android.widget.LinearLayout) {
        val isDarkMode = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

        if (isDarkMode) {
            container.setBackgroundResource(R.drawable.timer_minimized_background_dark)
            timeTextMinimized?.setTextColor(0xFFFFFFFF.toInt())
        } else {
            container.setBackgroundResource(R.drawable.timer_minimized_background_light)
            timeTextMinimized?.setTextColor(0xFF000000.toInt())
        }

        // Style expand button with gray circle (like album art maximize overlay)
        // Size is 32dp in XML to match badge
        expandButtonMinimized?.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x80808080.toInt())  // 50% gray
            }
            setColorFilter(0xFFFFFFFF.toInt())
            val pad = (6 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
    }

    /**
     * Update timer data and refresh display
     */
    fun updateTimer(newData: TimerData) {
        val wasMinimized = timerData.isMinimized
        timerData = newData

        // If minimized state changed, need to re-render
        if (wasMinimized != timerData.isMinimized) {
            render()
        } else {
            // Just update text
            timeTextExpanded?.text = timerData.formatTime()
            timeTextMinimized?.text = timerData.formatTime()
            updatePauseResumeButton()
            updateVisualState()
        }
    }

    /**
     * Update pause/resume button icon based on state
     */
    private fun updatePauseResumeButton() {
        val iconRes = when (timerData.state) {
            TimerState.PAUSED -> R.drawable.ic_play
            else -> R.drawable.ic_pause
        }
        pauseResumeButton?.setImageResource(iconRes)
    }

    /**
     * Update visual state (opacity, animations) based on timer state
     */
    private fun updateVisualState() {
        when (timerData.state) {
            TimerState.PAUSED -> {
                stopCompletionFlashing()
                startPulsingAnimation()
            }
            TimerState.COMPLETED -> {
                stopPulsingAnimation()
                alpha = 1.0f
                startCompletionFlashing()
            }
            else -> {
                stopPulsingAnimation()
                stopCompletionFlashing()
                alpha = 1.0f
            }
        }
    }

    /**
     * Start pulsing animation for paused state — only pulses the time text opacity.
     */
    private fun startPulsingAnimation() {
        if (pulsingAnimator?.isRunning == true) return

        pulsingAnimator = ValueAnimator.ofFloat(0.4f, 1.0f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animation ->
                val a = animation.animatedValue as Float
                timeTextExpanded?.alpha = a
                timeTextMinimized?.alpha = a
            }
            start()
        }
    }

    /**
     * Stop pulsing animation and restore full opacity on time text
     */
    private fun stopPulsingAnimation() {
        pulsingAnimator?.cancel()
        pulsingAnimator = null
        timeTextExpanded?.alpha = 1.0f
        timeTextMinimized?.alpha = 1.0f
    }

    /**
     * Start flashing animation for completed state.
     * Alternates the time text color between normal and orange every 500ms.
     */
    private fun startCompletionFlashing() {
        if (flashingAnimator?.isRunning == true) return

        val isDarkMode = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val normalColor = if (isDarkMode) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val alarmColor = 0xFFFF6B00.toInt() // Orange

        flashingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val color = if (fraction < 0.5f) normalColor else alarmColor
                timeTextExpanded?.setTextColor(color)
                timeTextMinimized?.setTextColor(color)
            }
            start()
        }
    }

    /**
     * Stop completion flashing animation and restore normal text color
     */
    private fun stopCompletionFlashing() {
        flashingAnimator?.cancel()
        flashingAnimator = null

        // Restore normal text color
        val isDarkMode = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val normalColor = if (isDarkMode) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        timeTextExpanded?.setTextColor(normalColor)
        timeTextMinimized?.setTextColor(normalColor)
    }

    /**
     * Set position of the timer card using margins
     */
    fun setPosition(x: Int, y: Int) {
        currentX = x
        currentY = y
        cardLayoutParams.leftMargin = x
        cardLayoutParams.topMargin = y
        layoutParams = cardLayoutParams
        requestLayout()
    }

    /**
     * Check if a touch event is on any button
     */
    private fun isTouchOnButton(event: MotionEvent): Boolean {
        val buttons = listOfNotNull(pauseResumeButton, cancelButton, minimizeButton, expandButtonMinimized)
        // Hit-test in SCREEN coordinates so the widget_zoom render-transform
        // (scaleX/scaleY on this card) is handled correctly. getLocationOnScreen()
        // already reflects the scaled visual position, and a button's visual size
        // is its layout size times the card scale. The old code mixed event.x/y
        // (delivered in the card's UNSCALED local space) with scaled window
        // offsets, so once scaleX != 1 every button hit-rect was misplaced and
        // taps fell through to drag handling. At scale 1.0 this is equivalent.
        val s = scaleX.takeIf { it > 0f } ?: 1f
        val rawX = event.rawX
        val rawY = event.rawY

        for (button in buttons) {
            if (button.visibility == VISIBLE) {
                val location = IntArray(2)
                button.getLocationOnScreen(location)
                val bw = button.width * s
                val bh = button.height * s

                if (rawX >= location[0] && rawX <= location[0] + bw &&
                    rawY >= location[1] && rawY <= location[1] + bh) {
                    Log.i(TAG, "⏱️ Touch detected on button: ${button.contentDescription}")
                    return true
                }
            }
        }
        return false
    }

    /**
     * Dispatch touch events - allows us to control whether children get events
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartedOnButton = isTouchOnButton(event)

                if (touchStartedOnButton) {
                    Log.i(TAG, "⏱️ Touch started on button - dispatching to children")
                    // Let the child button handle this touch sequence
                    return super.dispatchTouchEvent(event)
                }

                // Not on a button - handle for dragging
                Log.i(TAG, "⏱️ Touch started on card (not button) - preparing for drag")
                isDragging = false
                dragStartTime = System.currentTimeMillis()
                dragOffset.set(event.rawX - currentX, event.rawY - currentY)
                // Consume the event for dragging
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchStartedOnButton) {
                    // Pass to button
                    return super.dispatchTouchEvent(event)
                }

                val deltaX = Math.abs(event.rawX - (currentX + dragOffset.x))
                val deltaY = Math.abs(event.rawY - (currentY + dragOffset.y))

                // Start dragging if moved >10px
                if (!isDragging && (deltaX > 10 || deltaY > 10)) {
                    isDragging = true
                    Log.i(TAG, "⏱️ Started dragging (moved ${maxOf(deltaX, deltaY).toInt()}px)")
                }

                if (isDragging) {
                    val parentW = (parent as? ViewGroup)?.width ?: overlayContainer.width
                    val parentH = (parent as? ViewGroup)?.height ?: overlayContainer.height
                    // Use the SCALED footprint (Display Size renders the card via
                    // scaleX/scaleY from pivot 0,0) so the visible card — not its
                    // unscaled layout box — is what's kept on-screen.
                    val s = scaleX.takeIf { it > 0f } ?: 1f
                    val cardW = (width * s).toInt().coerceAtLeast(1)
                    val cardH = (height * s).toInt().coerceAtLeast(1)
                    // Keep at least 25% of the card visible on screen
                    val minVisible = (cardW * 0.25f).toInt()
                    currentX = (event.rawX - dragOffset.x).toInt()
                        .coerceIn(-cardW + minVisible, parentW - minVisible)
                    currentY = (event.rawY - dragOffset.y).toInt()
                        .coerceIn(0, parentH - minVisible)
                    cardLayoutParams.leftMargin = currentX
                    cardLayoutParams.topMargin = currentY
                    layoutParams = cardLayoutParams
                    requestLayout()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touchStartedOnButton) {
                    Log.i(TAG, "⏱️ Touch up on button - dispatching to children")
                    // Pass to button
                    val result = super.dispatchTouchEvent(event)
                    touchStartedOnButton = false
                    return result
                }

                if (isDragging) {
                    onPositionChanged(timerData.id, currentX, currentY)
                    Log.i(TAG, "⏱️ Drag complete - timer ${timerData.id} at ($currentX, $currentY)")
                } else {
                    Log.i(TAG, "⏱️ Touch up without drag (tap on card)")
                }
                isDragging = false
                touchStartedOnButton = false
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /**
     * Called when configuration changes (e.g., light/dark mode toggle)
     */
    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "Configuration changed - re-rendering for theme update")
        render()
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        stopPulsingAnimation()
        stopCompletionFlashing()
    }
}

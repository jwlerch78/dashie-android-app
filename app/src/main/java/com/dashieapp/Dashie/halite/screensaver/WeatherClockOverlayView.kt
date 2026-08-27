package com.dashieapp.Dashie.halite.screensaver

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.graphics.Color
import android.graphics.PorterDuff
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Calendar

/**
 * Native weather forecast card overlay for the screensaver.
 * Displays a card with:
 * - Top row: weather icon + condition | temperature + high/low
 * - Horizontally scrollable forecast area: up to 10-day or 5-hour
 * - Tab bar at bottom: "5-Day" | "Hourly" with 15s auto-rotate
 *
 * No clock — the screensaver already has one.
 */
class WeatherClockOverlayView(
    context: Context,
    private val userSizeMultiplier: Float = 1f
) : FrameLayout(context) {

    companion object {
        private const val TAG = "WeatherOverlay"

        // Light mode colors (default)
        private const val CARD_BG_LIGHT = 0xDDE8E8F0.toInt()   // Light blue-gray
        private const val TEXT_COLOR_LIGHT = 0xFF1A1A1A.toInt()  // Near-black
        private const val TEXT_DIM_LIGHT = 0xFF666666.toInt()    // Gray
        private const val DIVIDER_LIGHT = 0x20000000

        // Dark mode colors (matches music strip dark mode)
        private const val CARD_BG_DARK = 0xE0000000.toInt()     // Near-black, 88% alpha
        private const val TEXT_COLOR_DARK = 0xFFFFFFFF.toInt()   // White
        // Full white (was 0xB3 / 70%) — user asked for all weather fonts white
        // in dark mode for readability. Dim text now matches primary in dark;
        // light mode keeps its gray (TEXT_DIM_LIGHT) for hierarchy.
        private const val TEXT_DIM_DARK = 0xFFFFFFFF.toInt()     // White
        private const val DIVIDER_DARK = 0x30FFFFFF
        private const val GLOW_DARK = 0x30FFFFFF.toInt()         // Subtle white glow border

        private const val PRECIP_COLOR = 0xFF2196F3.toInt()     // Blue for precip %
        private const val TAB_ROTATE_MS = 15_000L               // 15 seconds
    }

    private var isDarkMode = false
    private var isWidgetMode = false
    // null = legacy screensaver behavior (tab bar + auto-rotate between
    // daily/hourly). "daily" or "hourly" = locked single-mode for use as a
    // layout widget where rotation is driven externally.
    private var lockedMode: String? = null
    // Active colors (updated by setDarkMode)
    private var cardBgColor = CARD_BG_LIGHT
    private var textColor = TEXT_COLOR_LIGHT
    private var textColorDim = TEXT_DIM_LIGHT
    private var dividerColor = DIVIDER_LIGHT

    // Current weather
    private val weatherIcon: TextView
    private val loadingSpinner: ProgressBar
    private val tempText: TextView
    private val conditionText: TextView
    private val highLowText: TextView

    // Forecast area
    private val forecastScrollView: HorizontalScrollView
    private val forecastContainer: LinearLayout
    private val scrollIndicatorLeft: TextView
    private val scrollIndicator: TextView

    // Tab bar
    private val tab5Day: TextView
    private val tabHourly: TextView
    private val tabSeparator: TextView

    // References for theming
    private val cardView: LinearLayout
    private val dividers = mutableListOf<View>()

    // No-location empty state — friendlier handoff than showError("No
    // location") for users who skipped the zip-code step in onboarding and
    // don't have HA configured to fall back on. Mirrors the photo widget's
    // "Click to add photos" pattern: weather icon + caption pointing at
    // Preferences in the Control Center. Built lazily on first use so the
    // common path (data flowing) doesn't pay for views that may never show.
    private var noLocationContainer: LinearLayout? = null
    private var noLocationText: TextView? = null
    private var isShowingNoLocation = false

    private val handler = Handler(Looper.getMainLooper())
    private var rotateRunnable: Runnable? = null
    private var showingHourly = false
    private var lastData: WeatherDataProvider.WeatherData? = null

    /**
     * Tap signal for the rotator control bar. Set by WeatherWidgetController
     * to forward a "user touched the widget" event to the JS layer, which
     * tells the Kotlin RotatorControlBarController to fade the bar in.
     * Fires on ACTION_DOWN — no swipe / long-press detection here; touch
     * gestures are handled by the control bar's own buttons.
     */
    var onTouchSignal: (() -> Unit)? = null

    // Device-class UI scaling — matches PhotoSlideshowView.uiScaleFactor
    // pattern but with a ~+56% content bump (1.25 × 1.25) for the weather
    // widget so TV / small-tablet cards show dense content (4 daily / 4
    // hourly columns with highs, lows, precip) legibly.
    //
    // PhotoSlideshowView baseline references × 1.5625:
    //   Fire TV 4K Max → 0.5 × 1.5625 = 0.78
    //   Fire Tablet → 0.56 × 1.5625 = 0.88
    //   Samsung SM-X200 → 0.74 × 1.5625 = 1.16
    //   Mio 15" → 1.0 × 1.5625 = 1.56 (clamped to 1.8)
    private val uiScaleFactor: Float by lazy {
        val dm = resources.displayMetrics
        val shortSidePx = minOf(dm.widthPixels, dm.heightPixels)
        val normalizedShortSide = shortSidePx / dm.density
        val baseNormalizedSize = 1080f
        val deviceScale = (normalizedShortSide / baseNormalizedSize) * 1.5625f
        val combined = (deviceScale * userSizeMultiplier).coerceIn(0.5f, 3.0f)
        Log.d(TAG, "UI scale: $combined (device=$deviceScale × user=$userSizeMultiplier, " +
                "short=$shortSidePx density=${dm.density} normalized=$normalizedShortSide)")
        combined
    }

    // Per-section readability tuning (June 2026 user request), applied on top
    // of the device-class uiScaleFactor:
    //   • iconScale — weather condition glyphs ~10% larger
    //   • forecastTextScale — forecast-column text ~20% larger
    // The current-conditions header (big temp + condition label) is left at
    // baseline; only the forecast row and the weather glyphs are bumped.
    private val iconScale = 1.1f
    private val forecastTextScale = 1.2f

    init {
        // Main card container
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Tighter horizontal padding so the 4th forecast column (e.g. "12PM")
            // isn't clipped, and a smaller top inset to shift content up.
            val padH = dp(12)
            val padTop = dp(8)
            val padV = dp(14)
            setPadding(padH, padTop, padH, padV)
            background = GradientDrawable().apply {
                setColor(cardBgColor)
                cornerRadius = dp(16).toFloat()
            }
        }
        cardView = card

        // ── Top row: icon + condition | temp + high/low ──
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Left: icon + condition
        val leftCol = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Initial state has no weather data — show a small spinning ring
        // (matches the calendar widget's loading affordance) instead of a
        // thermometer emoji + error text. weatherIcon stays empty until
        // updateWeather() supplies a real condition glyph.
        weatherIcon = TextView(context).apply {
            textSize = 34f * uiScaleFactor * iconScale
            text = ""
            visibility = View.GONE  // shown when data arrives
        }
        leftCol.addView(weatherIcon)

        loadingSpinner = ProgressBar(context).apply {
            isIndeterminate = true
            // Tint with Dashie orange to match the calendar spinner.
            indeterminateDrawable?.setColorFilter(
                Color.parseColor("#FF9500"),
                PorterDuff.Mode.SRC_IN
            )
            val sz = (32f * uiScaleFactor).toInt().let { dp(it) }
            val lp = LinearLayout.LayoutParams(sz, sz)
            layoutParams = lp
        }
        leftCol.addView(loadingSpinner)

        conditionText = TextView(context).apply {
            setTextColor(textColor)
            textSize = 16f * uiScaleFactor
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = dp(8)
            layoutParams = lp
            // D.66 — show "Loading weather..." next to the spinner instead of
            // empty text so the first paint conveys the in-flight state rather
            // than a bare spinner. Replaced by the real condition label in
            // updateWeather, or kept (instead of an error flash) by showError
            // when lastData is still null.
            text = "Loading weather..."
        }
        leftCol.addView(conditionText)

        topRow.addView(leftCol)

        // Right: temp + high/low
        val rightCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        tempText = TextView(context).apply {
            setTextColor(textColor)
            textSize = 30f * uiScaleFactor
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.END
            text = "--°"
        }
        rightCol.addView(tempText)

        highLowText = TextView(context).apply {
            setTextColor(textColorDim)
            textSize = 13f * uiScaleFactor
            gravity = Gravity.END
            text = ""
        }
        rightCol.addView(highLowText)

        topRow.addView(rightCol)
        card.addView(topRow)

        // ── Divider ──
        card.addView(buildDivider().also { dividers.add(it) })

        // ── Horizontally scrollable forecast area with scroll indicator ──
        val forecastRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(4)
            lp.bottomMargin = dp(4)
            layoutParams = lp
        }

        forecastScrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        forecastContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        forecastScrollView.addView(forecastContainer)

        // Left scroll indicator (hidden until user scrolls right)
        scrollIndicatorLeft = TextView(context).apply {
            text = "‹"
            setTextColor(textColorDim)
            textSize = 20f * uiScaleFactor
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(dp(16), LinearLayout.LayoutParams.MATCH_PARENT)
            layoutParams = lp
            visibility = View.GONE
        }
        forecastRow.addView(scrollIndicatorLeft)
        forecastRow.addView(forecastScrollView)

        // Right scroll indicator (hidden when fully scrolled)
        scrollIndicator = TextView(context).apply {
            text = "›"
            setTextColor(textColorDim)
            textSize = 20f * uiScaleFactor
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(dp(16), LinearLayout.LayoutParams.MATCH_PARENT)
            layoutParams = lp
            visibility = View.GONE
        }
        forecastRow.addView(scrollIndicator)

        // Show/hide indicator based on scroll position
        forecastScrollView.viewTreeObserver.addOnScrollChangedListener {
            updateScrollIndicator()
        }

        card.addView(forecastRow)

        // ── Bottom divider ──
        card.addView(buildDivider().also { dividers.add(it) })

        // ── Tab bar ──
        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10)
            layoutParams = lp
        }

        tab5Day = buildTabLabel("Daily", true).apply {
            setOnClickListener { switchTab(hourly = false) }
        }
        tabRow.addView(tab5Day)

        // Separator pipe
        tabSeparator = TextView(context).apply {
            text = "|"
            setTextColor(textColorDim)
            textSize = 14f * uiScaleFactor
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = dp(16)
            lp.marginEnd = dp(16)
            layoutParams = lp
        }
        tabRow.addView(tabSeparator)

        tabHourly = buildTabLabel("Hourly", false).apply {
            setOnClickListener { switchTab(hourly = true) }
        }
        tabRow.addView(tabHourly)

        card.addView(tabRow)

        // Add card to this FrameLayout
        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setUse24Hour(value: Boolean) {
        // No clock in this view — no-op
    }

    /**
     * Lock the view to a single forecast mode and hide the tab bar.
     * Called by WeatherWidgetController when this view is used as a layout
     * widget (where rotation is driven by the JS rotation engine, not the
     * view's own auto-rotate). Pass "daily" or "hourly".
     *
     * Uses View.GONE rather than removeView so the LinearLayout's child
     * ordering and layoutParams (forecast row weight=1, etc.) stay intact.
     */
    fun setLockedMode(mode: String) {
        lockedMode = mode
        showingHourly = (mode == "hourly")
        // Hide the tab bar row (last child) and the divider above it
        // (second-to-last child). cardView children:
        //   [topRow, divider, forecastRow, divider, tabRow]
        val tabRow = cardView.getChildAt(cardView.childCount - 1)
        val tabDivider = cardView.getChildAt(cardView.childCount - 2)
        tabRow?.visibility = View.GONE
        tabDivider?.visibility = View.GONE
        Log.i(TAG, "setLockedMode: $mode (showingHourly=$showingHourly)")
        if (lastData != null) renderForecast()
    }

    /**
     * Configure for widget mode: transparent background, fill available height.
     * Call after construction, before setDarkMode.
     */
    /**
     * Strip the card background entirely and center the contents within
     * the host FrameLayout. Used by "Weather & Time" full-screen mode
     * where a card border looks busy and the host is told to fill height
     * so the card sits visually centered. Call after construction.
     */
    fun setBorderless() {
        cardView.background = null
        cardView.setPadding(dp(20), dp(8), dp(20), dp(8))
        // Re-anchor the inner card layout so it's vertically centered
        // within the (full-height) outer FrameLayout host.
        cardView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_VERTICAL }
    }

    fun setWidgetMode() {
        isWidgetMode = true

        // Theme-aware card background for widget. Prefer the webapp palette
        // (NativeThemeManager.bgPrimary) so the weather widget interior matches
        // the JS widgets next to it; fall back to black/white when no theme
        // has been pushed yet (cold boot).
        // Corner radius uses raw dp (not uiScaleFactor-scaled dp() helper) so
        // the card's inner corners match the photo widget's 4dp inner radius
        // (outer container 8dp − 4dp border inset = 4dp). Previously the
        // scaled dp(12) produced ~19dp corners that pinched in at the edges
        // and made the card read as slightly narrower than the photo slot.
        val widgetBg = com.dashieapp.Dashie.halite.theming.NativeThemeManager
            .getBgPrimaryOr(if (isDarkMode) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        val density = resources.displayMetrics.density
        val innerCornerRadiusPx = 4f * density
        (cardView.background as? GradientDrawable)?.apply {
            setColor(widgetBg)
            cornerRadius = innerCornerRadiusPx
        }

        // Fill available space
        cardView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        // Let the forecast row expand to fill vertical space
        val forecastRow = forecastScrollView.parent as? LinearLayout
        forecastRow?.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }

        // Let the scroll view + column row fill that height so each column's
        // weighted spacers can distribute its rows evenly (equal spacing
        // between divider, label, emoji/precip, temp, and bottom icons).
        forecastScrollView.layoutParams =
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        forecastContainer.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )
    }

    /**
     * Force-refresh the widget-mode card background from NativeThemeManager.
     * Lets the WeatherWidgetController re-apply themed bgPrimary on every
     * palette push without depending on whether isDarkMode also changed
     * (setDarkMode early-returns when dark state is unchanged, which made
     * theme-family-only switches no-op the inner card bg).
     */
    fun refreshThemedBg() {
        if (!isWidgetMode) return
        val widgetBg = com.dashieapp.Dashie.halite.theming.NativeThemeManager
            .getBgPrimaryOr(if (isDarkMode) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        (cardView.background as? GradientDrawable)?.setColor(widgetBg)
    }

    /**
     * Switch to dark mode styling (black bg, white/gray text, subtle glow border).
     * Matches the music strip dark mode for consistency on dim/black screensaver.
     */
    fun setDarkMode(dark: Boolean) {
        if (isDarkMode == dark) return
        isDarkMode = dark

        cardBgColor = if (dark) CARD_BG_DARK else CARD_BG_LIGHT
        textColor = if (dark) TEXT_COLOR_DARK else TEXT_COLOR_LIGHT
        textColorDim = if (dark) TEXT_DIM_DARK else TEXT_DIM_LIGHT
        dividerColor = if (dark) DIVIDER_DARK else DIVIDER_LIGHT

        // Card background
        if (isWidgetMode) {
            // Widget mode: tracks webapp --bg-primary so we match the JS
            // widgets next to us (falls back to black/white at cold boot).
            val widgetBg = com.dashieapp.Dashie.halite.theming.NativeThemeManager
                .getBgPrimaryOr(if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            (cardView.background as? GradientDrawable)?.setColor(widgetBg)
        } else {
            (cardView.background as? GradientDrawable)?.apply {
                setColor(cardBgColor)
                if (dark) {
                    setStroke(1, GLOW_DARK)
                } else {
                    setStroke(0, 0)
                }
            }
        }

        // Top row text
        conditionText.setTextColor(textColor)
        tempText.setTextColor(textColor)
        highLowText.setTextColor(textColorDim)

        // Scroll indicators
        scrollIndicatorLeft.setTextColor(textColorDim)
        scrollIndicator.setTextColor(textColorDim)

        // Dividers
        for (d in dividers) d.setBackgroundColor(dividerColor)

        // Tabs
        tabSeparator.setTextColor(textColorDim)
        updateTabStyles()

        // Re-render forecast with new colors
        renderForecast()
    }

    fun start() {
        if (lockedMode == null) startAutoRotate()
        Log.i(TAG, "Overlay started (lockedMode=$lockedMode)")
    }

    fun stop() {
        stopAutoRotate()
    }

    fun updateWeather(data: WeatherDataProvider.WeatherData) {
        // Real data — clear the no-location overlay if it's up so the
        // card reappears with the new forecast.
        hideNoLocationState()

        lastData = data
        val isNight = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it < 6 || it >= 20 }
        // Replace the loading spinner with the resolved condition glyph.
        loadingSpinner.visibility = View.GONE
        weatherIcon.visibility = View.VISIBLE
        weatherIcon.text = WeatherIcons.getEmoji(data.condition, isNight)
        tempText.text = "${Math.round(data.temperature)}${data.tempUnit}"
        conditionText.text = WeatherIcons.getLabel(data.condition, isNight)
        // Restore normal text color in case showError() was previously
        // called and left the condition row in red.
        conditionText.setTextColor(textColor)

        // High/low from first forecast day
        if (data.forecast.isNotEmpty()) {
            val today = data.forecast.first()
            highLowText.text = "H:${Math.round(today.tempHigh)}° L:${Math.round(today.tempLow)}°"
        }

        renderForecast()
    }

    fun showError(message: String) {
        hideNoLocationState()

        // D.66 — if we've never shown real weather data yet, keep the
        // loading state visible instead of flashing the error message.
        // The provider will retry on the next poll; a brief failure
        // window during cold-start (HA token not yet injected, or
        // Open-Meteo geocode racing onboarding) shouldn't pop "Could
        // not fetch weather data" on the user's very first paint.
        if (lastData == null) {
            loadingSpinner.visibility = View.VISIBLE
            weatherIcon.visibility = View.GONE
            conditionText.text = "Loading weather..."
            conditionText.setTextColor(textColor)
            return
        }

        // Post-data error: surface the message in muted text. No thermometer
        // glyph (was inconsistent with the rest of the UI) and no red —
        // error here is non-blocking; rotation widget is expected to recover
        // on the next poll.
        loadingSpinner.visibility = View.GONE
        weatherIcon.visibility = View.GONE
        conditionText.text = message
        conditionText.setTextColor(textColorDim)
    }

    /**
     * Show the no-location empty state — weather icon + "Update location in
     * preferences to show weather". Used when the user skipped the zip-code
     * step in onboarding and there's no HA fallback, so the regular fetch
     * loop has nothing to geocode and would otherwise leave the card stuck
     * on the loading spinner or surface a vague "Could not fetch weather
     * data" error. Hides the regular card and overlays a centered icon +
     * caption inside this FrameLayout.
     */
    fun showNoLocationState() {
        ensureNoLocationContainer()
        cardView.visibility = View.GONE
        noLocationContainer?.visibility = View.VISIBLE
        // Theme-aware caption color: dark on light theme, light on dark —
        // matches the photo widget's "Click to add photos" empty state and
        // the rest of the dashboard's body-text contrast.
        noLocationText?.setTextColor(
            if (isDarkMode) Color.WHITE else Color.parseColor("#222222")
        )
        isShowingNoLocation = true
    }

    private fun hideNoLocationState() {
        if (!isShowingNoLocation) return
        noLocationContainer?.visibility = View.GONE
        cardView.visibility = View.VISIBLE
        isShowingNoLocation = false
    }

    private fun ensureNoLocationContainer() {
        if (noLocationContainer != null) return
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER }
        }
        val icon = ImageView(context).apply {
            val sizePx = (64 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (12 * resources.displayMetrics.density).toInt()
            }
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_weather_partly_cloudy)
            // Tint orange to match the photo widget's empty-state icon —
            // the source vector is white, which would be invisible on the
            // light-theme outer container.
            setColorFilter(Color.parseColor("#EE9828"))
        }
        container.addView(icon)
        val text = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            textSize = 16f * uiScaleFactor
            text = "Update location in preferences to show weather"
            gravity = Gravity.CENTER
            val padH = (16 * resources.displayMetrics.density).toInt()
            setPadding(padH, 0, padH, 0)
        }
        container.addView(text)
        addView(container)
        noLocationContainer = container
        noLocationText = text
    }

    // ── Tab switching ──

    private fun switchTab(hourly: Boolean) {
        showingHourly = hourly
        updateTabStyles()
        renderForecast()
        startAutoRotate()
    }

    private fun startAutoRotate() {
        stopAutoRotate()
        rotateRunnable = object : Runnable {
            override fun run() {
                showingHourly = !showingHourly
                updateTabStyles()
                renderForecast()
                handler.postDelayed(this, TAB_ROTATE_MS)
            }
        }
        rotateRunnable?.let { handler.postDelayed(it, TAB_ROTATE_MS) }
    }

    private fun stopAutoRotate() {
        rotateRunnable?.let { handler.removeCallbacks(it) }
        rotateRunnable = null
    }

    private fun updateTabStyles() {
        styleTab(tab5Day, active = !showingHourly)
        styleTab(tabHourly, active = showingHourly)
    }

    private fun renderForecast() {
        forecastContainer.removeAllViews()
        forecastScrollView.scrollTo(0, 0)
        val data = lastData ?: return

        // If the view hasn't been measured yet, defer rendering. This
        // happens for the inactive widget in a JS-driven rotation: the
        // view is created hidden (visibility GONE), so onSizeChanged
        // hasn't fired and cardContentWidth is 0. Rendering with 0 width
        // produces negative column widths and a collapsed forecast area.
        // onSizeChanged will call back into renderForecast once measured.
        if (cardContentWidth <= 0) {
            Log.i(TAG, "renderForecast: deferring (not measured yet) lockedMode=$lockedMode")
            return
        }

        Log.i(TAG, "renderForecast: lockedMode=$lockedMode showingHourly=$showingHourly " +
                "daily=${data.forecast.size} hourly=${data.hourly.size} " +
                "cardContentWidth=$cardContentWidth height=$height")

        if (showingHourly && data.hourly.isNotEmpty()) {
            renderHourly(data)
        } else {
            renderDaily(data)
        }

        // Update scroll indicator after layout
        forecastContainer.post { updateScrollIndicator() }
    }

    private fun updateScrollIndicator() {
        val child = forecastScrollView.getChildAt(0) ?: return
        val scrollRange = child.width - forecastScrollView.width
        val canScroll = scrollRange > dp(10)
        val atStart = forecastScrollView.scrollX <= dp(5)
        val atEnd = forecastScrollView.scrollX >= scrollRange - dp(5)
        scrollIndicatorLeft.visibility = if (canScroll && !atStart) View.VISIBLE else View.GONE
        scrollIndicator.visibility = if (canScroll && !atEnd) View.VISIBLE else View.GONE
    }

    private var cardContentWidth = 0

    /**
     * Measure the available width for forecast columns.
     * Called before rendering to size columns correctly.
     */
    private fun getColumnWidth(count: Int): Int {
        // Use measured card width minus padding (padH=dp(12) * 2)
        val availableWidth = if (cardContentWidth > 0) cardContentWidth else {
            // Fallback: use parent width (the FrameLayout constraint from ScreenDimmer)
            val parentWidth = (parent as? android.view.View)?.width ?: (resources.displayMetrics.widthPixels * 0.40).toInt()
            parentWidth - dp(24) // subtract card padding (2 × padH=dp(12))
        }
        val visibleCols = minOf(count, 5)
        return if (visibleCols > 0) availableWidth / visibleCols else dp(56)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            onTouchSignal?.invoke()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val prevContentWidth = cardContentWidth
        cardContentWidth = w - dp(24) // card padding (2 × padH=dp(12))
        Log.i(TAG, "onSizeChanged: w=$w h=$h cardContentWidth=$cardContentWidth lockedMode=$lockedMode")
        if (cardContentWidth <= 0 || lastData == null) return
        // Re-render the forecast when EITHER:
        //  • we have data but never rendered (first measure — cardContentWidth
        //    was 0 when data first arrived), or
        //  • the available width changed after a prior render. Column widths are
        //    derived from cardContentWidth (see getColumnWidth), so a slot resize
        //    — e.g. the native sidebar settling and narrowing the canvas AFTER
        //    the widget's first layout/render — must recompute them. Without
        //    this, columns keep their original (too-wide) width and the last
        //    visible column is clipped on first load (only width matters; a
        //    height-only change leaves cardContentWidth unchanged and is a no-op).
        val neverRendered = forecastContainer.childCount == 0
        val widthChanged = prevContentWidth > 0 && cardContentWidth != prevContentWidth
        if (neverRendered) {
            // First measure: the initial layout pass still lays out the
            // columns we add here, so render synchronously (original behavior).
            renderForecast()
        } else if (widthChanged) {
            // Re-render AFTER this layout pass. renderForecast() rebuilds the
            // column views (removeAllViews + addView); doing that synchronously
            // inside onSizeChanged — i.e. mid-layout — leaves the new columns
            // unmeasured and the forecast renders blank. post() runs it on the
            // next frame so the rebuilt columns get a proper layout pass.
            post { if (lastData != null && cardContentWidth > 0) renderForecast() }
        }
    }

    // ── Daily forecast renderer ──

    private fun renderDaily(data: WeatherDataProvider.WeatherData) {
        // Widget mode: column width sized so 4 fit visibly; remaining days
        // scroll horizontally. Screensaver: column width fits all visible.
        val colWidth = if (isWidgetMode) {
            getColumnWidth(4)
        } else {
            getColumnWidth(data.forecast.size)
        }
        for (fc in data.forecast) {
            forecastContainer.addView(buildDailyColumn(fc, colWidth))
        }
    }

    private fun buildDailyColumn(fc: WeatherDataProvider.ForecastDay, colWidth: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                colWidth,
                if (isWidgetMode) LinearLayout.LayoutParams.MATCH_PARENT
                else LinearLayout.LayoutParams.WRAP_CONTENT
            )

            if (isWidgetMode) addView(weightSpacer())  // top gap: divider → label

            // Row 1: Day name
            addView(TextView(context).apply {
                text = fc.dayName
                setTextColor(textColorDim)
                textSize = 13f * uiScaleFactor * forecastTextScale
                gravity = Gravity.CENTER
            })

            addView(if (isWidgetMode) weightSpacer() else buildSpacer(12))

            // Row 2: Icon + precip (fixed height, vertically centered)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(58)
                )

                addView(TextView(context).apply {
                    text = WeatherIcons.getEmoji(fc.condition)
                    textSize = 26f * uiScaleFactor * iconScale
                    gravity = Gravity.CENTER
                })

                if (fc.precipProbability >= 5) {
                    addView(TextView(context).apply {
                        text = "${fc.precipProbability}%"
                        setTextColor(PRECIP_COLOR)
                        textSize = 11f * uiScaleFactor * forecastTextScale
                        gravity = Gravity.CENTER
                    })
                }
            })

            addView(if (isWidgetMode) weightSpacer() else buildSpacer(12))

            // Row 3: High / Low temps
            if (isWidgetMode) {
                // Stacked: high on top, low below in smaller gray font
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(TextView(context).apply {
                        text = "${Math.round(fc.tempHigh)}°"
                        setTextColor(textColor)
                        textSize = 14f * uiScaleFactor * forecastTextScale
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                    })
                    addView(TextView(context).apply {
                        text = "${Math.round(fc.tempLow)}°"
                        setTextColor(textColorDim)
                        textSize = 11f * uiScaleFactor * forecastTextScale
                        gravity = Gravity.CENTER
                    })
                })
            } else {
                // Inline: High | Low
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    addView(TextView(context).apply {
                        text = "${Math.round(fc.tempHigh)}°"
                        setTextColor(textColor)
                        textSize = 13f * uiScaleFactor * forecastTextScale
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    addView(TextView(context).apply {
                        text = " | "
                        setTextColor(textColorDim)
                        textSize = 13f * uiScaleFactor * forecastTextScale
                    })
                    addView(TextView(context).apply {
                        text = "${Math.round(fc.tempLow)}°"
                        setTextColor(textColorDim)
                        textSize = 13f * uiScaleFactor * forecastTextScale
                    })
                })
            }

            // Heavier bottom gap: the rotator pills overlay sits at the card's
            // bottom edge, so the temp needs extra space below it for the
            // temp→pill gap to read the same as the gaps above.
            if (isWidgetMode) addView(weightSpacer(2f))  // bottom gap: temp → rotator icons
        }
    }

    // ── Hourly forecast renderer ──

    private fun renderHourly(data: WeatherDataProvider.WeatherData) {
        // Widget mode: column width sized so 4 fit visibly; remaining hours
        // scroll horizontally. Screensaver: 10 narrow columns visible.
        val hours = data.hourly.take(10)
        val colWidth = if (isWidgetMode) {
            getColumnWidth(4)
        } else {
            getColumnWidth(hours.size)
        }
        for (hf in hours) {
            forecastContainer.addView(buildHourlyColumn(hf, colWidth))
        }
    }

    private fun buildHourlyColumn(hf: WeatherDataProvider.HourlyForecast, colWidth: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                colWidth,
                if (isWidgetMode) LinearLayout.LayoutParams.MATCH_PARENT
                else LinearLayout.LayoutParams.WRAP_CONTENT
            )

            if (isWidgetMode) addView(weightSpacer())  // top gap: divider → label

            // Row 1: Time label
            addView(TextView(context).apply {
                text = hf.time
                setTextColor(if (hf.time == "Now") textColor else textColorDim)
                textSize = 13f * uiScaleFactor * forecastTextScale
                typeface = if (hf.time == "Now") Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = Gravity.CENTER
            })

            addView(if (isWidgetMode) weightSpacer() else buildSpacer(12))

            // Row 2: Icon + precip (fixed height, vertically centered)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(58)
                )

                addView(TextView(context).apply {
                    val isNight = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it < 6 || it >= 20 }
                    text = WeatherIcons.getEmoji(hf.condition, isNight)
                    textSize = 26f * uiScaleFactor * iconScale
                    gravity = Gravity.CENTER
                })

                if (hf.precipProbability >= 5) {
                    addView(TextView(context).apply {
                        text = "${hf.precipProbability}%"
                        setTextColor(PRECIP_COLOR)
                        textSize = 11f * uiScaleFactor * forecastTextScale
                        gravity = Gravity.CENTER
                    })
                }
            })

            addView(if (isWidgetMode) weightSpacer() else buildSpacer(12))

            // Row 3: Temperature (bold)
            addView(TextView(context).apply {
                text = "${Math.round(hf.temperature)}°"
                setTextColor(textColor)
                textSize = 15f * uiScaleFactor * forecastTextScale
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })

            // Heavier bottom gap: the rotator pills overlay sits at the card's
            // bottom edge, so the temp needs extra space below it for the
            // temp→pill gap to read the same as the gaps above.
            if (isWidgetMode) addView(weightSpacer(2f))  // bottom gap: temp → rotator icons
        }
    }

    // ── View builders ──

    private fun buildSpacer(dpHeight: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(dpHeight))
        }
    }

    // Weighted spacer for widget mode — placed above/between/below the forecast
    // rows so the column fills the height and the four gaps (divider → label →
    // emoji/precip → temp → bottom rotator icons) come out equal. Scales with
    // widget height automatically.
    private fun weightSpacer(weight: Float = 1f): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, 0, weight)
    }

    private fun buildDivider(): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8)
            }
            setBackgroundColor(dividerColor)
        }
    }

    private fun buildTabLabel(label: String, active: Boolean): TextView {
        return TextView(context).apply {
            text = label
            textSize = 14f * uiScaleFactor
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            styleTab(this, active)
        }
    }

    private fun styleTab(tab: TextView, active: Boolean) {
        tab.background = null
        tab.setTextColor(if (active) textColor else textColorDim)
        tab.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun dp(value: Int): Int {
        // Scale dp values by the device-class factor so spacing shrinks on
        // TVs / small tablets in proportion with text.
        val scaled = value.toFloat() * uiScaleFactor
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, scaled, resources.displayMetrics
        ).toInt()
    }
}

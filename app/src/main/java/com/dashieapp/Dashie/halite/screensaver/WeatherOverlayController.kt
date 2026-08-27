package com.dashieapp.Dashie.halite.screensaver

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.dashieapp.Dashie.halite.HalitePreferences

/**
 * Weather overlay extracted from ScreenDimmer:
 *
 * - **Forecast card** rendering (the [WeatherClockOverlayView] that shows
 *   a multi-day forecast strip in photos / dim / black / weather modes)
 * - **Inline current weather** (the small "☀️ 72°F" tag below the clock
 *   in dim/black/photos modes)
 * - **Position** of the forecast card relative to the clock side
 *   (applies anti-burn-in flip — see [applyPosition])
 * - **Provider lifecycle** — [WeatherDataProvider] is kept alive across
 *   dim cycles so cached data is shown immediately on next screensaver
 *
 * The flip timer that drives anti-burn-in side swaps stays in
 * ScreenDimmer for now; it calls [applyPosition] when it ticks. A later
 * refactor step will move the flip timer into ClockOverlayController and
 * wire weather to subscribe.
 */
class WeatherOverlayController(
    private val context: Context,
    private val dimOverlay: ViewGroup,
    private val prefs: ScreensaverPreferences,
    private val halitePrefsProvider: () -> HalitePreferences?,
    private val screensaverModeProvider: () -> String,
    private val weatherTextViewProvider: () -> TextView?,
    private val slideshowViewProvider: () -> PhotoSlideshowView?,
    private val clockTextViewProvider: () -> TextView?,
    private val clockOnRightProvider: () -> Boolean,
    private val uiScaleFactorProvider: () -> Float,
    private val effectiveClockFontSizeProvider: () -> Int,
    private val applyClockPositionCallback: () -> Unit
) {
    companion object {
        private const val TAG = "WeatherOverlayController"
    }

    private var weatherOverlay: WeatherClockOverlayView? = null
    private var weatherProvider: WeatherDataProvider? = null

    /** True once the forecast card view exists (still attached or detached). */
    fun isOverlayActive(): Boolean = weatherOverlay != null

    /** Set dark-mode background on the forecast card. No-op if not active. */
    fun setDarkMode(dark: Boolean) {
        weatherOverlay?.setDarkMode(dark)
    }

    /**
     * Build the forecast card overlay (sizes itself per [ScreensaverPreferences.forecastCardSize]
     * unless the screensaver mode is "weather", in which case it goes
     * fullscreen + borderless), positions it on the opposite side from
     * the clock, starts the data provider, and shows any cached data
     * immediately.
     */
    fun setupOverlay() {
        val hp = halitePrefsProvider() ?: run {
            Log.w(TAG, "Cannot setup weather overlay - no halitePrefs")
            return
        }

        if (weatherOverlay == null) {
            val dm = context.resources.displayMetrics
            val density = dm.density
            val isWeatherTimeMode = screensaverModeProvider() == "weather"
            val sizeOption = if (isWeatherTimeMode) "fullscreen" else prefs.forecastCardSize
            val widthFraction = when (sizeOption) {
                "vsmall" -> 0.30
                "small" -> 0.35
                "large" -> 0.50
                "xlarge" -> 0.60
                "fullscreen" -> 0.92
                else -> 0.40 // medium
            }
            val sizeMultiplier = when (sizeOption) {
                "vsmall" -> 0.7f
                "small" -> 0.85f
                "large" -> 1.25f
                "xlarge" -> 1.5f
                "fullscreen" -> 1.3f  // gives clock + bottom edge breathing room
                else -> 1.0f // medium
            }
            val cardWidth = (dm.widthPixels * widthFraction).toInt()
            val offsetDp = 48 // match clock margin for consistent spacing
            val offsetPx = (offsetDp * density).toInt()

            weatherOverlay = WeatherClockOverlayView(context, sizeMultiplier).apply {
                layoutParams = FrameLayout.LayoutParams(
                    cardWidth,
                    if (isWeatherTimeMode) {
                        // Fill the screen height so setBorderless()'s
                        // CENTER_VERTICAL inner gravity actually centers
                        // the card on screen rather than wrapping content.
                        FrameLayout.LayoutParams.MATCH_PARENT
                    } else {
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    }
                ).apply {
                    if (isWeatherTimeMode) {
                        gravity = android.view.Gravity.CENTER_HORIZONTAL
                    } else {
                        topMargin = offsetPx
                    }
                }
                if (isWeatherTimeMode) {
                    setBorderless()
                }
            }
            // Add to dimOverlay so it appears on top of photos/black
            dimOverlay.addView(weatherOverlay)
        }

        applyPosition()

        weatherOverlay?.setUse24Hour(hp.display.use24HourClock)
        weatherOverlay?.start()

        val entityId = prefs.weatherEntityId
        if (weatherProvider == null || weatherProvider?.getLastData() == null) {
            weatherProvider = WeatherDataProvider(hp, entityId, context)
        }

        weatherProvider?.onWeatherUpdated = { data ->
            weatherOverlay?.updateWeather(data)
        }
        weatherProvider?.onError = { error ->
            weatherOverlay?.showError(error)
        }

        val cached = weatherProvider?.getLastData()
        if (cached != null) {
            weatherOverlay?.updateWeather(cached)
            Log.i(TAG, "🌤️ Forecast card showing cached data immediately (${cached.forecast.size} daily)")
        } else {
            Log.i(TAG, "🌤️ Forecast card has no cached data — spinner until fetch completes")
        }

        weatherProvider?.start()

        // Notify slideshow so it can avoid positioning the clock on top of
        // the forecast card; reposition clock if it's currently visible.
        slideshowViewProvider()?.setForecastCardVisible(true)
        if (clockTextViewProvider()?.visibility == View.VISIBLE) {
            applyClockPositionCallback()
        }

        Log.i(TAG, "Weather overlay setup complete (entity=$entityId)")
    }

    /**
     * Position the forecast card on the opposite side from the clock.
     * Clock-on-right ⇒ weather on left; clock-on-left ⇒ weather on right.
     * Called on initial setup and on every flip-timer tick.
     */
    fun applyPosition() {
        val params = weatherOverlay?.layoutParams as? FrameLayout.LayoutParams ?: return
        val dm = context.resources.displayMetrics
        val offsetPx = (48 * dm.density).toInt() // match clock margin

        params.marginStart = 0
        params.marginEnd = 0

        // Weather goes on opposite side from clock's logical position.
        // When sidebar is active, clock is forced left — but weather should stay
        // on the logical opposite side (which is also left when clockOnRight=true).
        // In that case, weather stays left and clock moves to bottom-left
        // (handled in applyClockPosition).
        val weatherOnLeft = clockOnRightProvider()
        if (weatherOnLeft) {
            params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            params.marginStart = offsetPx
        } else {
            params.gravity = android.view.Gravity.TOP or android.view.Gravity.END
            params.marginEnd = offsetPx
        }

        weatherOverlay?.layoutParams = params
    }

    /**
     * Adjust the forecast-card top/bottom margin so it sits in the half of
     * the screen opposite the clock anchor. Used in "weather" mode when
     * the clock cycles through 6 anchors and the card needs to vacate the
     * row containing the clock.
     */
    fun applyCardForClockAnchor(anchor: String, clockZonePx: Int) {
        val weatherView = weatherOverlay ?: return
        val params = weatherView.layoutParams as? FrameLayout.LayoutParams ?: return
        params.topMargin = if (anchor.startsWith("T")) clockZonePx else 0
        params.bottomMargin = if (anchor.startsWith("B")) clockZonePx else 0
        weatherView.layoutParams = params
    }

    /**
     * Tear down the forecast card view. Keeps [WeatherDataProvider] alive
     * so cached data is available on the next dim cycle. The provider is
     * only stopped via [destroyProvider].
     */
    fun cleanup() {
        weatherOverlay?.stop()
        weatherProvider?.stop()
        weatherOverlay?.let { dimOverlay.removeView(it) }
        weatherOverlay = null
        weatherTextViewProvider()?.visibility = View.GONE
        slideshowViewProvider()?.setForecastCardVisible(false)
        // Note: weatherProvider stays alive across dim cycles for cache reuse
    }

    /**
     * If the screensaver is currently dimmed and the forecast overlay is
     * up, tear it down and rebuild. Used when forecastCardSize / weatherMode
     * change at runtime so the user sees the new settings without waiting
     * for the next dim cycle.
     *
     * @param onSetupForFlipMode Invoked after setup if the new mode needs
     *   a flip-timer restart (delegated back to ScreenDimmer for now).
     */
    fun refreshIfActive(onSetupForFlipMode: () -> Unit) {
        if (dimOverlay.visibility != View.VISIBLE) return
        if (weatherOverlay == null) return
        cleanup()
        val mode = screensaverModeProvider()
        if (mode == "weather") {
            setupOverlay()
            weatherOverlay?.setDarkMode(true)
        } else if (prefs.weatherMode == "forecast" && mode in listOf("photos", "dim", "black")) {
            setupOverlay()
            if (mode in listOf("dim", "black")) {
                weatherOverlay?.setDarkMode(true)
            }
            onSetupForFlipMode()
        }
    }

    /**
     * Setup inline "current weather" display below the clock/date.
     * Shows: "☀️ 72°F" in white text matching the clock style. Reuses the
     * shared [WeatherDataProvider] used by the forecast card.
     */
    fun setupInlineCurrent() {
        val hp = halitePrefsProvider() ?: return
        val isPhotosMode = screensaverModeProvider() == "photos"

        // For dim/black modes, use the XML weather text view.
        // For photos mode, the slideshow has its own weather text view.
        if (!isPhotosMode) {
            val wv = weatherTextViewProvider() ?: return
            val effectivePt = effectiveClockFontSizeProvider()
            val scaledSize = (effectivePt * 0.65f) * uiScaleFactorProvider()
            wv.textSize = scaledSize
            wv.setTextColor(0xCCFFFFFF.toInt())  // Match date text opacity
            wv.setShadowLayer(
                5f * uiScaleFactorProvider(),
                3f * uiScaleFactorProvider(),
                3f * uiScaleFactorProvider(),
                android.graphics.Color.BLACK
            )
            wv.typeface = android.graphics.Typeface.DEFAULT
            wv.visibility = View.VISIBLE
        }

        val entityId = prefs.weatherEntityId
        if (weatherProvider == null) {
            weatherProvider = WeatherDataProvider(hp, entityId, context)
        }

        weatherProvider?.onWeatherUpdated = { data ->
            renderInline(data, isPhotosMode)
        }
        weatherProvider?.onError = { _ ->
            if (!isPhotosMode) weatherTextViewProvider()?.visibility = View.GONE
        }

        // Show cached data immediately if available
        weatherProvider?.getLastData()?.let { data ->
            renderInline(data, isPhotosMode)
        }

        weatherProvider?.start()
        Log.i(TAG, "Current weather inline setup complete (photosMode=$isPhotosMode)")
    }

    private fun renderInline(data: WeatherDataProvider.WeatherData, isPhotosMode: Boolean) {
        val isNight = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY).let { it < 6 || it >= 20 }
        val temp = "${Math.round(data.temperature)}${data.tempUnit}"
        val targetView =
            if (isPhotosMode) slideshowViewProvider()?.getWeatherTextView() else weatherTextViewProvider()
        val textSizePx = targetView?.textSize ?: 40f
        val span = WeatherIcons.buildWeatherSpan(context, data.condition, temp, isNight, textSizePx)
        if (isPhotosMode) {
            slideshowViewProvider()?.updateCurrentWeather(span)
        } else {
            weatherTextViewProvider()?.text = span
        }
    }
}

package com.dashieapp.Dashie.halite.screensaver

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.dashieapp.Dashie.halite.HalitePreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clock + date overlay extracted from ScreenDimmer:
 *
 * - **Clock + date textviews** lifecycle (font sizing, shadow, visibility,
 *   1-second update tick)
 * - **Anti-burn-in flip timer** that swaps clock side / cycles random
 *   anchors / cycles weather-mode anchors every 15 minutes. The timer
 *   drives both clock and weather positioning; weather subscribes via
 *   the [onFlipApplyWeather] callback.
 * - **Anchor logic** for "random" + "weather" modes (8-anchor grid,
 *   6-anchor weather variant, position selection that avoids the forecast
 *   card and video-feed sidebar)
 * - **HA time provider** lifecycle (used when the user opts in to "Use HA
 *   for time" — clock falls back to device time if provider hasn't synced)
 *
 * Public API mirrors what ScreenDimmer used to expose: [setClockView],
 * [setDateView], [setWeatherView] for view registration, and
 * [refreshHaTimeProvider] for the runtime "Use HA for time" toggle.
 */
class ClockOverlayController(
    private val context: Context,
    private val dimOverlay: ViewGroup,
    private val prefs: ScreensaverPreferences,
    private val halitePrefsProvider: () -> HalitePreferences?,
    private val screensaverModeProvider: () -> String,
    private val isVideoFeedModeProvider: () -> Boolean,
    private val uiScaleFactorProvider: () -> Float,
    private val slideshowViewProvider: () -> PhotoSlideshowView?,
    private val weatherOverlayActive: () -> Boolean,
    private val applyWeatherCardForClockAnchor: (String) -> Unit,
    private val applyWeatherPosition: () -> Unit
) {
    companion object {
        private const val TAG = "ClockOverlayController"
        private const val FLIP_INTERVAL_MS = 15 * 60 * 1000L  // 15 minutes
        // 8-anchor random anti-burn-in grid (3×3 minus center).
        private val RANDOM_CLOCK_ANCHORS = listOf("TL", "TC", "TR", "ML", "MR", "BL", "BC", "BR")
        // 6-anchor cycle for the "weather" screensaver mode — top+bottom rows
        // only so the centered weather card always has clear vertical space.
        private val WEATHER_CLOCK_ANCHORS = listOf("TL", "TC", "TR", "BL", "BC", "BR")
    }

    private val handler = Handler(Looper.getMainLooper())

    private var clockTextView: TextView? = null
    private var dateTextView: TextView? = null
    private var weatherTextView: TextView? = null
    private var clockRunnable: Runnable? = null

    // Flip-timer state — drives both clock anti-burn-in and weather card
    // side swaps. Clock starts on the right; weather starts on the left.
    var clockOnRight = true
        private set
    private var flipRunnable: Runnable? = null

    private var randomClockAnchor: String? = null
    private var weatherClockAnchor: String? = null

    private var haTimeProvider: HaTimeProvider? = null

    fun getClockTextView(): TextView? = clockTextView
    fun getDateTextView(): TextView? = dateTextView
    fun getWeatherTextView(): TextView? = weatherTextView

    fun setClockView(textView: TextView?) { clockTextView = textView }
    fun setDateView(textView: TextView?) { dateTextView = textView }
    fun setWeatherView(textView: TextView?) { weatherTextView = textView }

    /** Apply the screensaver mode's clock font size. Public so weather inline can match. */
    fun getEffectiveClockFontSize(): Int {
        return when (prefs.clockSize) {
            "vsmall" -> 40
            "small" -> 60
            "medium" -> 80
            "large" -> 100
            "xlarge" -> 120
            "custom" -> prefs.clockFontSize
            else -> 80
        }
    }

    /**
     * Setup the clock overlay (clock + date + textview gravities + 1s tick).
     * Honors `prefs.showClock` unless [forceShow] is true (used by the
     * "weather" screensaver mode + the sleep-show-clock pref).
     */
    fun setupClockOverlay(forceShow: Boolean = false) {
        if (clockTextView == null) return
        if (!forceShow && !prefs.showClock) return

        Log.i(TAG, "Setting up clock overlay for ${screensaverModeProvider()} mode (forceShow=$forceShow)")

        startHaTimeProviderIfEnabled()

        val effectivePt = getEffectiveClockFontSize()
        val scaledClockSize = effectivePt.toFloat() * uiScaleFactorProvider()
        clockTextView?.textSize = scaledClockSize
        clockTextView?.setShadowLayer(
            5f * uiScaleFactorProvider(),
            3f * uiScaleFactorProvider(),
            3f * uiScaleFactorProvider(),
            android.graphics.Color.BLACK
        )
        clockTextView?.typeface = android.graphics.Typeface.DEFAULT

        if (prefs.showDate) {
            val scaledDateSize = (effectivePt * 0.60f) * uiScaleFactorProvider()
            dateTextView?.textSize = scaledDateSize
            dateTextView?.setShadowLayer(
                5f * uiScaleFactorProvider(),
                3f * uiScaleFactorProvider(),
                3f * uiScaleFactorProvider(),
                android.graphics.Color.BLACK
            )
            dateTextView?.visibility = View.VISIBLE
        } else {
            dateTextView?.visibility = View.GONE
        }

        applyClockPosition()

        // Remove the previous tick before overwriting the field. setupClockOverlay
        // runs again on the dim→sleep transition (screenOff with sleepShowClock),
        // and the runnable re-posts itself unconditionally — an orphaned previous
        // instance would tick every second forever (one leaked loop per night).
        clockRunnable?.let { handler.removeCallbacks(it) }
        clockRunnable = object : Runnable {
            override fun run() {
                updateClock()
                handler.postDelayed(this, 1000)
            }
        }
        updateClock()
        clockTextView?.visibility = View.VISIBLE
        (clockTextView?.parent as? View)?.visibility = View.VISIBLE
        clockRunnable?.let { handler.post(it) }
    }

    /**
     * Reposition the clock + date container based on prefs and screensaver
     * mode. Three branches:
     *  - "weather" mode: 6-anchor cycle, weather card recenters opposite
     *  - random mode: 8-anchor grid, avoids forecast card + video sidebar
     *  - default: 4-corner placement honoring clockOnRight + clockPosition
     */
    fun applyClockPosition() {
        val clockContainer = clockTextView?.parent as? android.view.View ?: clockTextView ?: return
        val params = clockContainer.layoutParams as? FrameLayout.LayoutParams ?: return
        val margin = (48 * dimOverlay.resources.displayMetrics.density).toInt()

        params.setMargins(0, 0, 0, 0)
        params.marginStart = 0
        params.marginEnd = 0

        if (screensaverModeProvider() == "weather") {
            val anchor = weatherClockAnchor
                ?: pickWeatherClockAnchor().also { weatherClockAnchor = it }
            applyRandomAnchorGravity(params, anchor, margin)
            clockContainer.layoutParams = params
            val textGravity = when (anchor.last()) {
                'L' -> android.view.Gravity.START
                'R' -> android.view.Gravity.END
                else -> android.view.Gravity.CENTER_HORIZONTAL
            }
            clockTextView?.gravity = textGravity
            dateTextView?.gravity = textGravity
            weatherTextView?.gravity = textGravity
            // Push the weather card into the half opposite the clock.
            applyWeatherCardForClockAnchor(anchor)
            return
        }

        if (prefs.clockPosition == "random") {
            // Re-pick if the cached anchor is now invalid for the current
            // environment. setupClockOverlay runs BEFORE setupWeatherOverlay
            // in dim/black/photos modes, so the first anchor is picked
            // without knowing the forecast card will appear. When the
            // weather overlay is then built, it calls applyClockPosition
            // again — at which point we need to re-evaluate whether the
            // current anchor still passes the (now stricter) constraints.
            val current = randomClockAnchor
            val weatherUp = weatherOverlayActive()
            val videoFeed = isVideoFeedModeProvider()
            val anchor = if (current == null || !isRandomAnchorValid(current)) {
                val picked = pickRandomClockAnchor()
                randomClockAnchor = picked
                Log.i(TAG, "📐 applyClockPosition[random]: weather=$weatherUp videoFeed=$videoFeed currentInvalid=${current != null} → PICK $picked")
                picked
            } else {
                Log.i(TAG, "📐 applyClockPosition[random]: weather=$weatherUp videoFeed=$videoFeed currentValid → KEEP $current")
                current
            }
            applyRandomAnchorGravity(params, anchor, margin)
            clockContainer.layoutParams = params
            val textGravity = when (anchor.last()) {
                'L' -> android.view.Gravity.START
                'R' -> android.view.Gravity.END
                else -> android.view.Gravity.CENTER_HORIZONTAL
            }
            clockTextView?.gravity = textGravity
            dateTextView?.gravity = textGravity
            weatherTextView?.gravity = textGravity
            return
        }

        var isTop = prefs.clockPosition == "top"
        // When screensaver sidebar (video feeds/music) is active, force clock to left side
        val isRight = if (isVideoFeedModeProvider()) false else clockOnRight

        // Avoid overlap with forecast card: if clock and weather would be on the same
        // horizontal side (both left), move clock to bottom so they don't stack on top.
        if (weatherOverlayActive()) {
            val weatherOnLeft = clockOnRight  // weather uses logical clockOnRight, not effective
            val clockOnLeft = !isRight
            if (clockOnLeft && weatherOnLeft && isTop) {
                isTop = false
            } else if (!clockOnLeft && !weatherOnLeft && isTop) {
                isTop = false
            }
        }

        params.gravity = when {
            isTop && isRight -> {
                params.topMargin = margin; params.marginEnd = margin
                android.view.Gravity.TOP or android.view.Gravity.END
            }
            isTop && !isRight -> {
                params.topMargin = margin; params.marginStart = margin
                android.view.Gravity.TOP or android.view.Gravity.START
            }
            !isTop && isRight -> {
                params.bottomMargin = margin; params.marginEnd = margin
                android.view.Gravity.BOTTOM or android.view.Gravity.END
            }
            else -> {
                params.bottomMargin = margin; params.marginStart = margin
                android.view.Gravity.BOTTOM or android.view.Gravity.START
            }
        }

        clockContainer.layoutParams = params

        val textGravity = if (isRight) android.view.Gravity.END else android.view.Gravity.START
        clockTextView?.gravity = textGravity
        dateTextView?.gravity = textGravity
        weatherTextView?.gravity = textGravity
    }

    /** Read the current weather-mode clock anchor (null until first applyClockPosition). */
    fun getWeatherClockAnchor(): String? = weatherClockAnchor

    /** Pick a weather-mode clock anchor different from the current one. */
    private fun pickWeatherClockAnchor(): String {
        val current = weatherClockAnchor
        val candidates = WEATHER_CLOCK_ANCHORS.filter { it != current }
        return candidates.random()
    }

    /** Approximate vertical space the clock + date + margin occupies. */
    fun computeClockZonePx(): Int {
        val effectivePt = getEffectiveClockFontSize()
        val density = dimOverlay.resources.displayMetrics.density
        // Clock textSize is set in pt; Android renders 1pt ≈ 1.33dp visually.
        // Add date (60% of clock) and edge margin (48dp) + breathing room (24dp).
        val clockHeightDp = effectivePt * 1.4f * uiScaleFactorProvider()
        val dateHeightDp = effectivePt * 0.60f * 1.4f * uiScaleFactorProvider()
        val totalDp = clockHeightDp + dateHeightDp + 48f + 24f
        return (totalDp * density).toInt()
    }

    /**
     * True if [anchor] satisfies the same constraints [pickRandomClockAnchor]
     * applies. Used at apply-time to re-validate the cached anchor when
     * environment changes (e.g. forecast card appearing after first
     * applyClockPosition).
     */
    private fun isRandomAnchorValid(anchor: String): Boolean {
        if (isVideoFeedModeProvider() && anchor.endsWith("R")) return false
        if (weatherOverlayActive() && anchor.startsWith("T")) return false
        // In photos mode with metadata visible, the slideshow renders the
        // photo caption / location strip at the bottom of the screen.
        // Letting the clock anchor in the bottom row overlaps that strip.
        if (screensaverModeProvider() == "photos" && prefs.showMetadata && anchor.startsWith("B")) return false
        return true
    }

    /**
     * Pick a random clock anchor different from the current one. Excludes
     * the right column when video-feed sidebar is active and the forecast
     * card's top corner when weather is visible.
     */
    private fun pickRandomClockAnchor(): String {
        val current = randomClockAnchor
        var pool = RANDOM_CLOCK_ANCHORS
        if (isVideoFeedModeProvider()) {
            pool = pool.filter { !it.endsWith("R") }
        }
        if (weatherOverlayActive()) {
            // Forecast card occupies a top corner. Excluding only that one
            // corner wasn't enough — TC (top-center) and the opposite top
            // corner can both still visually collide with the wide forecast
            // strip. Drop the whole top row so the clock only ever lands in
            // middle or bottom anchors when the card is up.
            pool = pool.filter { !it.startsWith("T") }
        }
        // Photos mode with metadata visible: the slideshow renders the
        // caption / location strip at the bottom — keep the clock out of
        // the bottom row so it doesn't sit on top of the metadata.
        if (screensaverModeProvider() == "photos" && prefs.showMetadata) {
            pool = pool.filter { !it.startsWith("B") }
        }
        val candidates = pool.filter { it != current }.ifEmpty { pool }
        val picked = candidates.random()
        Log.i(TAG, "📐 pickRandomClockAnchor: pool=$pool current=$current → $picked")
        return picked
    }

    /** Convert a 2-letter anchor code (e.g. "TL", "MR", "BC") into Gravity + margins. */
    private fun applyRandomAnchorGravity(
        params: FrameLayout.LayoutParams,
        anchor: String,
        margin: Int
    ) {
        val v = anchor[0]  // T / M / B
        val h = anchor[1]  // L / C / R
        val verticalGravity = when (v) {
            'T' -> android.view.Gravity.TOP
            'B' -> android.view.Gravity.BOTTOM
            else -> android.view.Gravity.CENTER_VERTICAL
        }
        val horizontalGravity = when (h) {
            'L' -> android.view.Gravity.START
            'R' -> android.view.Gravity.END
            else -> android.view.Gravity.CENTER_HORIZONTAL
        }
        params.gravity = verticalGravity or horizontalGravity
        if (v == 'T') params.topMargin = margin
        if (v == 'B') params.bottomMargin = margin
        if (h == 'L') params.marginStart = margin
        if (h == 'R') params.marginEnd = margin
    }

    /**
     * Update the clock display text. Falls back to device time until the
     * HA time provider has synced (if "Use HA for time" is enabled).
     */
    private fun updateClock() {
        val nowMs = haTimeProvider?.takeIf { it.hasSynced() }?.nowMs() ?: System.currentTimeMillis()
        val now = Date(nowMs)
        val use24Hour = halitePrefsProvider()?.display?.use24HourClock ?: false
        if (use24Hour) {
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            clockTextView?.text = timeFormat.format(now)
        } else {
            val timeFormat = SimpleDateFormat("h:mm", Locale.getDefault())
            val amPmFormat = SimpleDateFormat("a", Locale.getDefault())
            val time = timeFormat.format(now)
            val amPm = amPmFormat.format(now).lowercase()
            clockTextView?.text = "$time $amPm"
        }

        if (dateTextView?.visibility == View.VISIBLE) {
            val datePattern = if (halitePrefsProvider()?.display?.dateFormat == "dmy") "EEE d MMM" else "EEE MMM d"
            val dateFormat = SimpleDateFormat(datePattern, Locale.getDefault())
            dateTextView?.text = dateFormat.format(now)
        }
    }

    /** Stop the clock overlay (clock tick + flip + HA time provider). */
    fun stopClockOverlay() {
        clockRunnable?.let { handler.removeCallbacks(it) }
        clockRunnable = null
        clockTextView?.visibility = View.GONE
        dateTextView?.visibility = View.GONE
        weatherTextView?.visibility = View.GONE
        (clockTextView?.parent as? View)?.visibility = View.GONE
        stopFlipTimer()
        stopHaTimeProvider()
        randomClockAnchor = null  // re-randomize on next session
        weatherClockAnchor = null
    }

    /**
     * Start the 15-minute anti-burn-in flip timer. Three behaviors:
     *  - "weather" mode: cycle weatherClockAnchor through 6 positions
     *  - random mode: cycle randomClockAnchor through 8 positions
     *  - default: flip clockOnRight (which also triggers weather card move
     *    + slideshow side swap)
     */
    fun startFlipTimer() {
        stopFlipTimer()
        flipRunnable = object : Runnable {
            override fun run() {
                when {
                    screensaverModeProvider() == "weather" -> {
                        weatherClockAnchor = pickWeatherClockAnchor()
                        Log.i(TAG, "Anti-burn-in weather: clock anchor → $weatherClockAnchor")
                        applyClockPosition()
                    }
                    prefs.clockPosition == "random" -> {
                        val newAnchor = pickRandomClockAnchor()
                        randomClockAnchor = newAnchor
                        // Derive logical clock side from the anchor's horizontal half
                        // ('R' → right, 'L'/'C' → left). This drives the forecast card
                        // (positioned on the opposite side via applyWeatherPosition)
                        // and the photo slideshow's own clock (which only knows L/R).
                        clockOnRight = newAnchor.last() == 'R'
                        Log.i(TAG, "Anti-burn-in random: clock anchor → $newAnchor (clockOnRight=$clockOnRight)")
                        applyClockPosition()
                        applyWeatherPosition()
                        // setClockSide explicitly (NOT flipClockSide) — random mode
                        // doesn't strictly toggle, so flipping the slideshow's own
                        // clockOnRight state would drift it out of sync with the
                        // controller's. Idempotent setClockSide keeps both in lockstep.
                        slideshowViewProvider()?.setClockSide(clockOnRight)
                    }
                    else -> {
                        clockOnRight = !clockOnRight
                        Log.i(TAG, "Anti-burn-in flip: clock now on ${if (clockOnRight) "right" else "left"}")
                        applyClockPosition()
                        applyWeatherPosition()
                        slideshowViewProvider()?.setClockSide(clockOnRight)
                    }
                }
                handler.postDelayed(this, FLIP_INTERVAL_MS)
            }
        }
        flipRunnable?.let { handler.postDelayed(it, FLIP_INTERVAL_MS) }
    }

    fun stopFlipTimer() {
        flipRunnable?.let { handler.removeCallbacks(it) }
        flipRunnable = null
    }

    private fun startHaTimeProviderIfEnabled() {
        val hp = halitePrefsProvider() ?: return
        if (!hp.general.useHaForTime) {
            stopHaTimeProvider()
            return
        }
        if (haTimeProvider != null) return
        haTimeProvider = HaTimeProvider(hp).apply {
            onSync = { _ -> updateClock() }
            start()
        }
    }

    private fun stopHaTimeProvider() {
        haTimeProvider?.stop()
        haTimeProvider = null
    }

    /**
     * Restart the HA time provider lifecycle (used when the user toggles
     * "Use HA for time" while the screensaver is active). When the toggle
     * goes off, the next clock tick falls back to device time. When it
     * goes on, the next clock tick uses HA time once the first sync completes.
     */
    fun refreshHaTimeProvider() {
        stopHaTimeProvider()
        if (clockTextView?.visibility == View.VISIBLE) {
            startHaTimeProviderIfEnabled()
            updateClock()
        }
    }
}

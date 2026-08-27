package com.dashieapp.Dashie.halite.widgets

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.WebView
import android.widget.FrameLayout
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences
import com.dashieapp.Dashie.halite.screensaver.WeatherClockOverlayView
import com.dashieapp.Dashie.halite.screensaver.WeatherDataProvider

/**
 * Controller for the native Kotlin weather widget in the layout system.
 *
 * Displays the WeatherClockOverlayView at the slot position/size.
 * No maximize/minimize gestures — a separate full-screen design will come later.
 *
 * Reuses WeatherClockOverlayView for rendering and WeatherDataProvider for data.
 */
class WeatherWidgetController(
    private val context: Context,
    private val rootContainer: ViewGroup,
    private val screensaverPrefs: ScreensaverPreferences,
    private val halitePrefs: HalitePreferences?,
    private val webViewProvider: () -> WebView?,
    private val visibilityGate: NativeWidgetVisibilityGate? = null,
    private val mode: String = "daily"  // "daily" or "hourly"
) {
    companion object {
        private const val TAG = "WeatherWidgetCtrl"
        // Theming notes for this controller (border color + inner card bg
        // both track NativeThemeManager.bgPrimary) live in
        // dashieapp_staging/.reference/THEMING_ARCHITECTURE.md §6, §7
        // (sync race), and §8.5 (setDarkMode early-return).
    }

    private var widgetContainer: FrameLayout? = null
    private var weatherView: WeatherClockOverlayView? = null
    private var cardBorderDrawable: GradientDrawable? = null
    private var dataProvider: WeatherDataProvider? = null
    private var isDestroyed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Re-paint on every palette push to fix the sync race between
    // setSidebarThemeColors (palette update) and setDarkMode (which triggers
    // onDarkModeChanged → applyTheme) — those arrive from JS as separate
    // bridge calls on different binder threads, so onDarkModeChanged often
    // fires before the manager has the new bgPrimary. Without this listener,
    // applyTheme reads stale bgPrimary and the card stays on the previous
    // theme's color. applyTheme only paints; no config-change trigger here.
    private val themeListener: () -> Unit = {
        val isDark = com.dashieapp.Dashie.devicecontrols.DarkModeManager.getStoredPreference(context) ?: false
        applyTheme(isDark)
    }

    init {
        com.dashieapp.Dashie.halite.theming.NativeThemeManager.addListener(themeListener)
    }

    // Current slot bounds (pixels when gridCols==0, grid units otherwise)
    private var slotX = 0
    private var slotY = 0
    private var slotW = 0
    private var slotH = 0
    private var gridCols = 48
    private var gridRows = 32

    /**
     * Called from JS bridge when the layout canvas finds a Kotlin weather slot.
     */
    fun setWidgetBounds(x: Int, y: Int, w: Int, h: Int, gridColumns: Int, gridRowsTotal: Int) {
        Log.i(TAG, "setWidgetBounds: x=$x y=$y w=$w h=$h grid=${gridColumns}x${gridRowsTotal}")

        slotX = x
        slotY = y
        slotW = w
        slotH = h
        gridCols = gridColumns
        gridRows = gridRowsTotal

        mainHandler.post {
            if (isDestroyed) return@post
            if (widgetContainer == null) {
                createWidget()
            } else {
                repositionWidget()
            }
        }
    }

    private fun createWidget() {
        if (isDestroyed) return
        Log.i(TAG, "Creating weather widget")

        val density = context.resources.displayMetrics.density
        val radiusPx = 8f * density
        val borderPx = (4f * density).toInt()

        // Themed border container with rounded corners — matches
        // PhotoWidgetController so weather and photos render with the same
        // visible border inset. Prefer the active webapp palette so the card
        // matches web widgets next to it; fall back to the original hardcoded
        // values until the webapp pushes a theme.
        val isDark = com.dashieapp.Dashie.devicecontrols.DarkModeManager.getStoredPreference(context) ?: false
        val borderColor = com.dashieapp.Dashie.halite.theming.NativeThemeManager
            .getBgPrimaryOr(if (isDark) Color.parseColor("#2A2A3E") else Color.WHITE)
        val borderDrawable = GradientDrawable().apply {
            setColor(borderColor)
            cornerRadius = radiusPx
        }
        cardBorderDrawable = borderDrawable
        val cardContainer = FrameLayout(context).apply {
            // Start INVISIBLE so the visibility gate evaluates state before
            // first layout pass — avoids a 1-frame flash on reload between
            // addView() and register().
            visibility = View.INVISIBLE
            background = borderDrawable
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
                }
            }
            clipToOutline = true
            // Consume touches so they don't pass through to the WebView
            // underneath — without this, taps on the visible weather widget
            // were reaching the family_cards iframe (a sibling JS widget in
            // the same rotator slot, hidden via display but still in the DOM)
            // and triggering its open-rewards/chores click handler.
            isClickable = true
            setOnClickListener { /* consume only — no behavior */ }
        }

        // Weather view — locked to a single forecast mode (daily or hourly).
        // Rotation between modes is driven externally by the JS rotation
        // engine, not by the view's internal tab bar / auto-rotate.
        val view = WeatherClockOverlayView(context)
        weatherView = view
        view.setWidgetMode()
        view.setLockedMode(mode)
        view.setDarkMode(isDark)

        // Forward ACTION_DOWN to JS so the rotator control bar can fade in.
        // The native weather view sits on top of the WebView and would
        // otherwise eat all touches before they reach the slot's DOM.
        // JS resolves widget type → slot id and calls signalRotatorTouch.
        val widgetType = "weather_$mode"
        view.onTouchSignal = {
            // BUNDLE-EXEMPT: dashieRotatorTouch — rotator touch drives full-mode webapp widgets
            val js = "window.dashieRotatorTouch && window.dashieRotatorTouch('$widgetType')"
            mainHandler.post { webViewProvider()?.evaluateJavascript(js, null) }
        }

        val innerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            setMargins(borderPx, borderPx, borderPx, borderPx)
        }
        cardContainer.addView(view, innerParams)

        // Position at slot bounds
        val params = calculateLayoutParams()
        rootContainer.addView(cardContainer, params)
        widgetContainer = cardContainer
        visibilityGate?.register(cardContainer)

        // Start weather data provider
        val entityId = screensaverPrefs.weatherEntityId
        val provider = WeatherDataProvider(halitePrefs ?: return, entityId, context)
        dataProvider = provider

        provider.onWeatherUpdated = { data ->
            mainHandler.post {
                if (!isDestroyed) {
                    view.updateWeather(data)
                }
            }
        }
        provider.onError = { message ->
            Log.w(TAG, "Weather data error: $message")
            mainHandler.post {
                if (!isDestroyed) {
                    view.showError(message)
                }
            }
        }
        provider.onNoLocation = {
            Log.i(TAG, "Weather has no location configured — showing empty state")
            mainHandler.post {
                if (!isDestroyed) {
                    view.showNoLocationState()
                }
            }
        }

        provider.start()
        view.start()
        Log.i(TAG, "Weather widget created and data provider started (entity=$entityId)")
    }

    private fun repositionWidget() {
        val container = widgetContainer ?: return
        val params = calculateLayoutParams()
        container.layoutParams = params
        visibilityGate?.register(container)
        Log.d(TAG, "Repositioned weather widget")
    }

    /**
     * Calculate pixel-based FrameLayout.LayoutParams.
     * When gridCols == 0, values are physical pixels from JS getBoundingClientRect.
     */
    private fun calculateLayoutParams(): FrameLayout.LayoutParams {
        if (gridCols == 0) {
            // slotX/slotY/slotW/slotH are physical px from JS
            // getBoundingClientRect(). JS layout canvas is already
            // offset to the right of the native sidebar via CSS var
            // (see js/core/native-sidebar-layout.js), so these are
            // real screen coordinates — no scaleX/pivot math needed.
            Log.d(TAG, "Layout (pixel mode): content($slotX,$slotY ${slotW}x$slotH)")
            return FrameLayout.LayoutParams(slotW, slotH).apply {
                leftMargin = slotX
                topMargin = slotY
            }
        }

        // Grid mode fallback
        val containerW = rootContainer.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val containerH = rootContainer.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        val canvasInset = 4; val clipInset = 4
        val areaW = containerW - canvasInset * 2; val areaH = containerH - canvasInset * 2

        val pixelX = canvasInset + clipInset + (slotX.toFloat() / gridCols * areaW).toInt()
        val pixelY = canvasInset + clipInset + (slotY.toFloat() / gridRows * areaH).toInt()
        val pixelW = (slotW.toFloat() / gridCols * areaW).toInt() - clipInset * 2
        val pixelH = (slotH.toFloat() / gridRows * areaH).toInt() - clipInset * 2

        return FrameLayout.LayoutParams(pixelW, pixelH).apply {
            leftMargin = pixelX; topMargin = pixelY
        }
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    fun pause() {
        weatherView?.stop()
        dataProvider?.stop()
    }

    fun resume() {
        weatherView?.start()
        dataProvider?.start()
    }

    fun setVisible(visible: Boolean) {
        Log.i(TAG, "[$mode] setVisible($visible) ENTRY — queuing main-thread apply")
        mainHandler.post {
            // Use INVISIBLE (not GONE) so the view still gets measured while
            // hidden. This is critical for rotation: when this widget is the
            // inactive one in a JS-driven rotator, it still needs onSizeChanged
            // to fire so cardContentWidth is set before data arrives. With
            // GONE, the view skips measure entirely and renderForecast runs
            // with cardContentWidth=0, producing a collapsed forecast section.
            //
            // Honor the gate's full visibility rules — fullscreen-suppression,
            // dashboard-ready, AND auth state. A rotation tick that fires
            // while a JS modal is up, dashboard is reloading, or the user
            // isn't authenticated MUST NOT pop the widget on top. Use
            // shouldShowFull() rather than partial flag checks to keep this
            // in sync with the gate's authoritative rule (FULL_ONLY kind).
            val gate = visibilityGate
            val container = widgetContainer
            val attached = container?.isAttachedToWindow ?: false
            if (!visible) {
                // D.50 — JS layout doesn't include this widget. Unregister
                // from the gate so a later setUiMode(FULL) → applyVisibility
                // pass doesn't override our hide back to VISIBLE. JS layout
                // is authoritative for which widgets belong in the current
                // layout; the gate only owns the gross "any FULL_ONLY visible
                // now?" axis (auth/reload). Re-register on next visible=true.
                container?.let { gate?.unregister(it) }
                container?.visibility = View.INVISIBLE
                Log.i(TAG, "[$mode] setVisible(false) applied + unregistered from gate")
                return@post
            }
            // Showing: re-register so subsequent mode flips apply.
            container?.let { gate?.register(it) }
            if (gate != null && !gate.shouldShowFull()) {
                container?.visibility = View.INVISIBLE
                Log.i(TAG, "[$mode] setVisible(true) SUPPRESSED by gate (suppressed=${gate.isFullscreenSuppressed()}, mode=${gate.getMode()}, attached=$attached, containerNull=${container == null})")
                return@post
            }
            container?.visibility = View.VISIBLE
            Log.i(TAG, "[$mode] setVisible(true) applied — viewVisibility=${container?.visibility}, attached=$attached, containerNull=${container == null}")
        }
    }

    fun applyTheme(isDark: Boolean) {
        mainHandler.post {
            weatherView?.setDarkMode(isDark)
            // setDarkMode early-returns when isDark hasn't actually changed, so
            // a palette-only push (theme family change without dark toggle)
            // wouldn't repaint the inner card. Force-refresh from the manager
            // here so the inner card always tracks bgPrimary.
            weatherView?.refreshThemedBg()
            // Outer card border — always repaint to follow webapp palette.
            cardBorderDrawable?.setColor(
                com.dashieapp.Dashie.halite.theming.NativeThemeManager
                    .getBgPrimaryOr(if (isDark) Color.parseColor("#2A2A3E") else Color.WHITE)
            )
            Log.d(TAG, "Applied theme: isDark=$isDark")
        }
    }

    /**
     * Force an immediate weather re-fetch (e.g. after the user changes
     * location or toggles the "Use HA for weather" setting).
     */
    fun refresh() {
        dataProvider?.refresh()
    }

    fun removeWidget() {
        Log.i(TAG, "Removing weather widget")
        weatherView?.stop()
        dataProvider?.stop()
        dataProvider = null
        widgetContainer?.let { rootContainer.removeView(it) }
        widgetContainer = null
        weatherView = null
    }

    fun destroy() {
        Log.i(TAG, "Destroying weather widget controller")
        isDestroyed = true
        removeWidget()
    }
}

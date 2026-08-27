package com.dashieapp.Dashie.halite.widgets

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import com.dashieapp.Dashie.sidebar.SidebarScaling

/**
 * One controller per rotating slot. Owns:
 *   - RotatorViewStrip    — always visible, bottom-right (one icon per
 *                           widget; tap to switch, long-press to freeze)
 *   - RotatorSettingsPill — touch-fade, bottom-left (settings cog)
 *
 * Bridge surface (called from JS via SensorComponentWiring):
 *   setBounds(x, y, w, h)
 *   setViews(viewsJson, activeWidgetType)
 *   setActiveView(activeWidgetType)
 *   setFrozenView(frozenWidgetType)        — null = nothing frozen
 *   signalTouch()                          — fades in settings pill
 *   remove()
 */
class RotatorPillsController(
    private val context: Context,
    private val rootContainer: ViewGroup,
    private val webViewProvider: () -> WebView?,
    private val visibilityGate: NativeWidgetVisibilityGate? = null,
    val slotId: String
) {
    companion object {
        private const val TAG = "RotatorPills"
        private const val SETTINGS_AUTO_HIDE_MS = 7_000L
        private const val FADE_DURATION_MS = 250L
        // Inset pill / strip inside the slot's rounded-corner border.
        private const val INSET_DP = 8f
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var viewStrip: RotatorViewStrip? = null
    private var settingsPill: RotatorSettingsPill? = null
    private var settingsHideRunnable: Runnable? = null
    private var isDestroyed = false

    private var slotX = 0
    private var slotY = 0
    private var slotW = 0
    private var slotH = 0

    /** Cached view list so we can apply state changes (active / frozen)
     *  without rebuilding the strip rows. */
    private var pendingViews: List<Pair<String, String>> = emptyList()
    private var pendingActive: String? = null
    private var pendingFrozen: String? = null

    fun setBounds(x: Int, y: Int, w: Int, h: Int) {
        slotX = x; slotY = y; slotW = w; slotH = h
        mainHandler.post {
            if (isDestroyed) return@post
            ensurePills()
            applyPendingState()
            repositionPills()
        }
    }

    /** Replace the strip's view list. `views` is widgetType pairs — first
     *  is the type id ("weather_hourly", "family_cards"), second is the
     *  display label (currently unused for icon rendering but kept for
     *  future tooltips / accessibility). */
    fun setViews(views: List<Pair<String, String>>, activeWidgetType: String?) {
        pendingViews = views
        pendingActive = activeWidgetType
        mainHandler.post {
            if (isDestroyed) return@post
            Log.d(TAG, "setViews slot=$slotId types=${views.map { it.first }} active=$activeWidgetType")
            ensurePills()
            viewStrip?.setViews(views, activeWidgetType, pendingFrozen)
            // Re-measure so the strip's intrinsic width reflects the new
            // icon count before we reposition.
            repositionPills()
        }
    }

    fun setActiveView(activeWidgetType: String?) {
        pendingActive = activeWidgetType
        mainHandler.post {
            viewStrip?.setActive(activeWidgetType)
        }
    }

    fun setFrozenView(frozenWidgetType: String?) {
        pendingFrozen = frozenWidgetType
        mainHandler.post {
            viewStrip?.setFrozen(frozenWidgetType)
            // Strip collapses to [pauseButton, frozenIcon] in frozen mode
            // and back to one icon per widget on resume — width changes,
            // so re-anchor it to the slot's bottom-right.
            repositionPills()
        }
    }

    /** Compat path for the legacy setRotatorPaused bridge call. Maps the
     *  boolean to the freeze marker on the currently-active widget. */
    fun setPaused(paused: Boolean) {
        setFrozenView(if (paused) pendingActive else null)
    }

    /** Reveal the settings pill on touch; auto-hides after 7s. The view
     *  strip is always visible and unaffected. */
    fun signalTouch() {
        mainHandler.post {
            if (isDestroyed) return@post
            val pill = settingsPill ?: run {
                Log.w(TAG, "signalTouch: settings pill not yet created for slot=$slotId")
                return@post
            }
            Log.d(TAG, "signalTouch slot=$slotId — fading in settings pill")
            showSettingsPillWithAutoHide(pill)
        }
    }

    fun remove() {
        mainHandler.post {
            isDestroyed = true
            cancelSettingsHide()
            viewStrip?.let { rootContainer.removeView(it) }
            settingsPill?.let { rootContainer.removeView(it) }
            viewStrip = null
            settingsPill = null
        }
    }

    // ===========================================================================
    // PRIVATE
    // ===========================================================================

    private fun ensurePills() {
        if (viewStrip == null) {
            Log.i(TAG, "ensurePills: creating view strip for slot=$slotId")
            val density = context.resources.displayMetrics.density
            val v = RotatorViewStrip(context).apply {
                visibility = View.INVISIBLE  // gate-suppressed until ready
                // 4dp — sits above rotator widgets (re-stacked by the
                // rotation engine via addView) but well below the
                // screensaver (200dp, see activity_main.xml ladder).
                elevation = 4f * density
                onAction = { action, widgetType ->
                    Log.i(TAG, "view-strip action=$action type=$widgetType slot=$slotId")
                    // BUNDLE-EXEMPT: dashieRotatorAction — rotator pills drive full-mode webapp widgets
                    val js = "window.dashieRotatorAction && window.dashieRotatorAction('$slotId','$action','$widgetType')"
                    webViewProvider()?.evaluateJavascript(js, null)
                    // Any interaction also reveals the settings pill.
                    settingsPill?.let { showSettingsPillWithAutoHide(it) }
                }
            }
            rootContainer.addView(v, FrameLayout.LayoutParams(0, 0))
            viewStrip = v
            visibilityGate?.register(v)
        }
        if (settingsPill == null) {
            Log.i(TAG, "ensurePills: creating settings pill for slot=$slotId")
            val density = context.resources.displayMetrics.density
            val v = RotatorSettingsPill(context).apply {
                alpha = 0f
                visibility = View.INVISIBLE
                // 4dp — sits above rotator widgets (re-stacked by the
                // rotation engine via addView) but well below the
                // screensaver (200dp, see activity_main.xml ladder).
                elevation = 4f * density
                onAction = { action ->
                    Log.i(TAG, "settings action=$action slot=$slotId")
                    val js = "window.dashieRotatorAction && window.dashieRotatorAction('$slotId','$action')"
                    webViewProvider()?.evaluateJavascript(js, null)
                    showSettingsPillWithAutoHide(this)
                }
            }
            rootContainer.addView(v, FrameLayout.LayoutParams(0, 0))
            settingsPill = v
            visibilityGate?.register(v)
        }
    }

    private fun applyPendingState() {
        if (pendingViews.isNotEmpty()) {
            viewStrip?.setViews(pendingViews, pendingActive, pendingFrozen)
        }
    }

    private fun repositionPills() {
        val strip = viewStrip ?: return
        val settings = settingsPill ?: return
        val insetPx = dp(INSET_DP)

        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        strip.measure(unspec, unspec)
        settings.measure(unspec, unspec)
        val stripW = strip.measuredWidth
        val stripH = strip.measuredHeight
        val sw = settings.measuredWidth
        val sh = settings.measuredHeight

        // View strip: bottom-right corner. Cap its width so it never
        // overlaps the bottom-left settings pill — once it would, the
        // HorizontalScrollView will just scroll instead.
        val maxStripW = (slotW - sw - insetPx * 3).coerceAtLeast(stripW)
        val finalStripW = stripW.coerceAtMost(maxStripW)
        strip.layoutParams = FrameLayout.LayoutParams(finalStripW, stripH).apply {
            leftMargin = slotX + slotW - finalStripW - insetPx
            topMargin = slotY + slotH - stripH - insetPx
        }
        // Settings: bottom-left corner.
        settings.layoutParams = FrameLayout.LayoutParams(sw, sh).apply {
            leftMargin = slotX + insetPx
            topMargin = slotY + slotH - sh - insetPx
        }
    }

    private fun showSettingsPillWithAutoHide(v: RotatorSettingsPill) {
        cancelSettingsHide()
        v.visibility = View.VISIBLE
        v.bringToFront()
        v.animate().cancel()
        v.animate().alpha(1f).setDuration(FADE_DURATION_MS).start()
        settingsHideRunnable = Runnable { fadeSettingsOut(v) }
        mainHandler.postDelayed(settingsHideRunnable!!, SETTINGS_AUTO_HIDE_MS)
    }

    private fun fadeSettingsOut(v: RotatorSettingsPill) {
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(FADE_DURATION_MS).withEndAction {
            v.visibility = View.INVISIBLE
        }.start()
    }

    private fun cancelSettingsHide() {
        settingsHideRunnable?.let { mainHandler.removeCallbacks(it) }
        settingsHideRunnable = null
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
    ).toInt()
}

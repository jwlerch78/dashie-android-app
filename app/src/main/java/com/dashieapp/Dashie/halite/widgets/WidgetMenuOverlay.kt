package com.dashieapp.Dashie.halite.widgets

// BRIDGE-DYNAMIC: window.$coord — per-widget JS d-pad coordinator protocol (full-mode layout canvas)

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Generic Kotlin-rendered "more options" bar menu shared across widgets
 * (rotator, photos, and any future widget that wants a focused-state bar).
 * Floats above the WebView so it's visible regardless of which widget is
 * the active rendering.
 *
 * JS owns the state machine + d-pad routing + tab item list. Bridge surface:
 *
 *   show(widgetId, jsCoordinator, x, y, w, h, items, selectedIdx)
 *     items is List<MenuItem>(id, label, iconRes)
 *     selectedIdx = -1 → no tab painted as selected (touch mode)
 *   setSelectedIndex(idx)
 *   setItem(idx, label, iconRes)   // dynamic swap (e.g. rotator pause↔play)
 *   hide()
 *
 * Touch tab taps round-trip back to JS via:
 *   window.<jsCoordinator>.activateBarTabByIndex(widgetId, index)
 *
 * For the JSON-from-JS path used by the bridge, see WidgetOverlayWiring
 * — it converts the JSON items array (with string icon keys) into the
 * MenuItem list this class expects.
 */
class WidgetMenuOverlay(
    private val activity: Activity,
    private val rootContainer: ViewGroup,
    private val webViewProvider: () -> WebView? = { null },
    /** Visibility gate — registered OVERLAY_FULL_ONLY so page reload
     *  force-hides any visible bar (kills the ghost overlay bug). */
    private val visibilityGate: NativeWidgetVisibilityGate? = null
) {
    companion object { private const val TAG = "WidgetMenuOverlay" }

    data class MenuItem(val id: String, val label: String, val iconRes: Int)

    private var view: LinearLayout? = null
    private var tabViews: MutableList<View> = mutableListOf()
    private var items: List<MenuItem> = emptyList()
    private var selectedIdx: Int = -1
    private var currentWidgetId: String? = null
    private var currentJsCoordinator: String = "rotatorMenu"

    fun show(
        widgetId: String,
        jsCoordinator: String,
        x: Int, y: Int, w: Int, h: Int,
        items: List<MenuItem>,
        selectedIdx: Int
    ) {
        Log.i(TAG, "[$jsCoordinator] show: widgetId=$widgetId tabs=${items.size} selectedIdx=$selectedIdx bounds=[$x,$y,${w}x$h]")
        activity.runOnUiThread {
            this.currentWidgetId = widgetId
            this.currentJsCoordinator = jsCoordinator
            this.items = items
            this.selectedIdx = selectedIdx
            ensureView(items)
            applySelection(selectedIdx)
            val v = view ?: return@runOnUiThread
            val lp = (v.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(0, 0)
            lp.width = w
            lp.height = h
            lp.leftMargin = x
            lp.topMargin = y
            lp.gravity = Gravity.TOP or Gravity.START
            v.layoutParams = lp
            v.visibility = View.VISIBLE
            v.bringToFront()
        }
    }

    fun setSelectedIndex(idx: Int) {
        activity.runOnUiThread {
            selectedIdx = idx
            applySelection(idx)
        }
    }

    /**
     * Dynamically swap an item's label + icon. Used for stateful tabs
     * (e.g. rotator's Pause↔Play swap on play-state change). `iconRes`
     * of 0 leaves the icon unchanged.
     */
    fun setItem(index: Int, label: String, iconRes: Int) {
        activity.runOnUiThread {
            val tab = tabViews.getOrNull(index) as? LinearLayout ?: return@runOnUiThread
            (tab.getChildAt(0) as? ImageView)?.let { iv ->
                if (iconRes != 0) iv.setImageResource(iconRes)
            }
            (tab.getChildAt(1) as? TextView)?.text = label
        }
    }

    fun hide() {
        activity.runOnUiThread { view?.visibility = View.GONE }
    }

    fun destroy() {
        activity.runOnUiThread {
            view?.let { (it.parent as? ViewGroup)?.removeView(it) }
            view = null
            tabViews.clear()
            items = emptyList()
            currentWidgetId = null
        }
    }

    // ===========================================================================
    // PRIVATE
    // ===========================================================================

    private fun ensureView(items: List<MenuItem>) {
        if (view != null && tabViews.size == items.size) {
            // Tab count matches — just rebind labels/icons.
            tabViews.forEachIndexed { i, tab ->
                val item = items[i]
                tab.tag = item.id
                (tab as? LinearLayout)?.let { lin ->
                    (lin.getChildAt(0) as? ImageView)?.setImageResource(item.iconRes)
                    (lin.getChildAt(1) as? TextView)?.text = item.label
                }
            }
            return
        }
        // Tab count changed (or first show) — rebuild from scratch.
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            val bottomR = dp(4f).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EB14161C"))
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, bottomR, bottomR, bottomR, bottomR)
                setStroke(dp(1f), Color.parseColor("#1AFFFFFF"))
            }
            // 4dp — sits above rotator/native widgets which are added to
            // widgetContainer at elevation 0 and re-stacked dynamically
            // by the rotation engine. Well below the screensaver (200dp,
            // see activity_main.xml).
            elevation = dp(4f).toFloat()
            val pad = dp(6f)
            setPadding(pad, pad, pad, pad)
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        tabViews.clear()
        items.forEachIndexed { idx, item ->
            val tab = buildTab(item, idx)
            container.addView(tab, tabLp(idx))
            tabViews.add(tab)
        }

        rootContainer.addView(container, FrameLayout.LayoutParams(0, 0))
        visibilityGate?.register(container, NativeWidgetVisibilityGate.WidgetKind.OVERLAY_FULL_ONLY)
        view = container
    }

    private fun buildTab(item: MenuItem, index: Int): View {
        val tab = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = buildTabBackground(selected = false)
            foreground = null
            isFocusable = false
            isFocusableInTouchMode = false
            tag = item.id
            val padH = dp(10f)
            val padV = dp(8f)
            setPadding(padH, padV, padH, padV)
            setOnClickListener {
                val widgetId = currentWidgetId ?: return@setOnClickListener
                val coord = currentJsCoordinator
                val js = "window.$coord && window.$coord.activateBarTabByIndex && window.$coord.activateBarTabByIndex('$widgetId', $index)"
                webViewProvider()?.evaluateJavascript(js, null)
            }
        }

        val icon = ImageView(activity).apply {
            setImageResource(item.iconRes)
            setColorFilter(Color.parseColor("#B3FFFFFF"))
            val size = dp(22f)
            layoutParams = LinearLayout.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        tab.addView(icon)

        val label = TextView(activity).apply {
            text = item.label
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(4f)
            layoutParams = lp
            isAllCaps = true
            letterSpacing = 0.06f
        }
        tab.addView(label)

        return tab
    }

    private fun tabLp(idx: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            if (idx > 0) marginStart = dp(8f)
        }
    }

    private fun buildTabBackground(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            setColor(if (selected) Color.parseColor("#33339AF0") else Color.parseColor("#0DFFFFFF"))
            cornerRadius = dp(8f).toFloat()
            if (selected) setStroke(dp(2f), Color.parseColor("#FF339AF0"))
            else setStroke(dp(2f), Color.TRANSPARENT)
        }
    }

    private fun applySelection(idx: Int) {
        tabViews.forEachIndexed { i, tab ->
            val isSel = (i == idx)
            tab.background = buildTabBackground(selected = isSel)
            val tint = if (isSel) Color.WHITE else Color.parseColor("#B3FFFFFF")
            (tab as? LinearLayout)?.let { lin ->
                (lin.getChildAt(0) as? ImageView)?.setColorFilter(tint)
                (lin.getChildAt(1) as? TextView)?.setTextColor(tint)
            }
        }
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, activity.resources.displayMetrics
    ).toInt()
}

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
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generic Kotlin-rendered settings overlay shared across widgets (rotator,
 * photos, and any future widget that wants a settings sub-page). Floats
 * above the WebView and any native widget overlays.
 *
 * JS owns the state machine + d-pad routing. Bridge surface:
 *
 *   show(widgetId, jsCoordinator, title, x, y, w, h, itemsJson, selectedIndex)
 *     itemsJson is an array of:
 *       { type: 'header', label }
 *       { type: 'option', label, sublabel?, checked }   // radio-style
 *       { type: 'toggle', label, checked }              // toggle-style
 *       { type: 'nav',    label, sublabel? }            // navigates a sub-page
 *       { type: 'close',  label }                       // Done / Back
 *   setSelection(index)            // d-pad highlight by row index
 *   setItemChecked(index, checked) // update visual after a toggle/option
 *   hide()
 *
 * Touch row taps round-trip back to JS via:
 *   window.<jsCoordinator>.activateSettingsItemByIndex(widgetId, index)
 */
class WidgetSettingsOverlay(
    private val activity: Activity,
    private val rootContainer: ViewGroup,
    private val webViewProvider: () -> WebView? = { null },
    /** Visibility gate — registered as OVERLAY_FULL_ONLY so page reload
     *  force-hides any visible overlay (kills the ghost overlay bug). */
    private val visibilityGate: NativeWidgetVisibilityGate? = null
) {
    companion object { private const val TAG = "WidgetSettingsOverlay" }

    private var view: LinearLayout? = null
    private var titleView: TextView? = null
    private var listContainer: LinearLayout? = null
    private var rowViews: MutableList<View> = mutableListOf()
    private var rowMeta: MutableList<JSONObject> = mutableListOf()
    /** -1 = no row selected (touch mode); otherwise index of the
     *  d-pad-highlighted row. */
    private var selectedIdx: Int = -1
    /** Widget id this overlay was last shown for. */
    private var currentWidgetId: String? = null
    /** JS coordinator name this overlay round-trips touch taps to. */
    private var currentJsCoordinator: String = "rotatorMenu"

    fun show(
        widgetId: String,
        jsCoordinator: String,
        title: String,
        x: Int, y: Int, w: Int, h: Int,
        itemsJson: String,
        selectedIndex: Int
    ) {
        Log.i(TAG, "[$jsCoordinator] show: widgetId=$widgetId selectedIndex=$selectedIndex bounds=[$x,$y,${w}x$h]")
        activity.runOnUiThread {
            currentWidgetId = widgetId
            currentJsCoordinator = jsCoordinator
            ensureContainer()
            titleView?.text = title
            buildRows(itemsJson, selectedIndex)
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

    fun setSelection(index: Int) {
        activity.runOnUiThread {
            selectedIdx = index
            applySelectionStyles()
            ensureSelectedVisible()
        }
    }

    fun setItemChecked(index: Int, checked: Boolean) {
        activity.runOnUiThread {
            if (index < 0 || index >= rowMeta.size) return@runOnUiThread
            rowMeta[index].put("checked", checked)
            val row = rowViews.getOrNull(index) as? LinearLayout ?: return@runOnUiThread
            val checkmark = row.findViewWithTag<TextView>("checkmark") ?: return@runOnUiThread
            checkmark.text = if (checked) "✓" else ""
        }
    }

    fun hide() {
        activity.runOnUiThread { view?.visibility = View.GONE }
    }

    fun destroy() {
        activity.runOnUiThread {
            view?.let { (it.parent as? ViewGroup)?.removeView(it) }
            view = null
            titleView = null
            listContainer = null
            rowViews.clear()
            rowMeta.clear()
        }
    }

    // ===========================================================================
    // PRIVATE
    // ===========================================================================

    private fun ensureContainer() {
        if (view != null) return

        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F214161C"))
                cornerRadius = dp(8f).toFloat()
                setStroke(dp(1f), Color.parseColor("#22FFFFFF"))
            }
            // 6dp — sits above the bar (4dp) when settings opens; both
            // sit above rotator/native widgets (re-stacked via addView
            // by the rotation engine). Well below the screensaver
            // (200dp, see activity_main.xml).
            elevation = dp(6f).toFloat()
            // Block any descendant from grabbing system focus — JS owns the
            // d-pad selection visual via setSelection(), and touch users
            // shouldn't see a stuck system focus border.
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        // Title bar — centered title + top-right X close button.
        val titleBar = FrameLayout(activity)
        val title = TextView(activity).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isAllCaps = true
            letterSpacing = 0.06f
            val padH = dp(11f); val padV = dp(7f)
            setPadding(padH, padV, padH, padV)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        titleBar.addView(title)
        titleView = title

        val closeX = TextView(activity).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FFFF9500"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            val padH = dp(12f); val padV = dp(4f)
            setPadding(padH, padV, padH, padV)
            isClickable = true
            foreground = null
            isFocusable = false
            isFocusableInTouchMode = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
            setOnClickListener {
                val widgetId = currentWidgetId ?: return@setOnClickListener
                val coord = currentJsCoordinator
                // Find the close item index in the current page so JS runs
                // its close handler (pops the stack / closes the overlay).
                val closeIdx = rowMeta.indexOfFirst { it.optString("type") == "close" }
                val js = if (closeIdx >= 0) {
                    "window.$coord && window.$coord.activateSettingsItemByIndex && window.$coord.activateSettingsItemByIndex('$widgetId', $closeIdx)"
                } else {
                    "window.$coord && window.$coord.deactivate && window.$coord.deactivate()"
                }
                webViewProvider()?.evaluateJavascript(js, null)
            }
        }
        titleBar.addView(closeX)

        outer.addView(titleBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val divider = View(activity).apply { setBackgroundColor(Color.parseColor("#22FFFFFF")) }
        outer.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)))

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            scrollBarStyle = ScrollView.SCROLLBARS_OUTSIDE_OVERLAY
        }
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(6f)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        outer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        rootContainer.addView(outer, FrameLayout.LayoutParams(0, 0))
        visibilityGate?.register(outer, NativeWidgetVisibilityGate.WidgetKind.OVERLAY_FULL_ONLY)
        view = outer
        listContainer = list
    }

    private fun buildRows(itemsJson: String, selectedIndex: Int) {
        val list = listContainer ?: return
        list.removeAllViews()
        rowViews.clear()
        rowMeta.clear()

        val arr = try { JSONArray(itemsJson) } catch (e: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            rowMeta.add(item)
            val row = buildRow(item, i)
            list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            rowViews.add(row)
        }
        // -1 means "no row selected" (touch entry); only clamp when the
        // caller actually requested a real row.
        selectedIdx = if (selectedIndex < 0) -1
                      else selectedIndex.coerceIn(0, (rowViews.size - 1).coerceAtLeast(0))
        applySelectionStyles()
    }

    private fun buildRow(item: JSONObject, index: Int): View {
        val type = item.optString("type", "option")
        val label = item.optString("label", "")
        val checked = item.optBoolean("checked", false)
        val sublabel = item.optString("sublabel", "")
        val iconKey = item.optString("icon", "")
        val iconRes = WidgetIconRegistry.resolve(iconKey)

        if (type == "header") {
            return TextView(activity).apply {
                text = label
                setTextColor(Color.parseColor("#88FFFFFF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                isAllCaps = true
                letterSpacing = 0.08f
                val padH = dp(8f); val padTop = dp(8f); val padBottom = dp(3f)
                setPadding(padH, padTop, padH, padBottom)
                isClickable = false
                isFocusable = false
            }
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = buildRowBackground(false)
            // Kill any inherited selectableItemBackground / ripple foreground.
            foreground = null
            val padH = dp(10f); val padV = dp(7f)
            setPadding(padH, padV, padH, padV)
            isClickable = true
            // JS owns d-pad selection state via setSelection(); the row
            // doesn't need system focusability and the system focus border
            // confuses touch users (looks like a stuck selection ring).
            isFocusable = false
            isFocusableInTouchMode = false
            isSelected = false
            isHovered = false
            setOnClickListener {
                val widgetId = currentWidgetId ?: return@setOnClickListener
                val coord = currentJsCoordinator
                val js = "window.$coord && window.$coord.activateSettingsItemByIndex && window.$coord.activateSettingsItemByIndex('$widgetId', $index)"
                webViewProvider()?.evaluateJavascript(js, null)
            }
        }

        // Optional leading icon (resolved via WidgetIconRegistry from
        // item.icon string key — e.g. "weather_hourly", "family_cards"
        // for the rotator widget chooser, or any other registered key).
        if (iconRes != 0) {
            val iconSize = dp(20f)
            val iconView = ImageView(activity).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(iconView, LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = dp(10f)
            })
        }

        val text = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // "close" rows (Done / Back) get a larger, bold, orange treatment
        // so they read as the primary affordance to dismiss the page.
        val isClose = (type == "close")
        val labelColor = if (isClose) Color.parseColor("#FFFF9500") else Color.WHITE
        val labelSize = if (isClose) 16f else 13f
        text.addView(TextView(activity).apply {
            this.text = label
            setTextColor(labelColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSize)
            if (isClose) {
                typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            }
        })
        if (sublabel.isNotEmpty()) {
            text.addView(TextView(activity).apply {
                this.text = sublabel
                setTextColor(Color.parseColor("#99FFFFFF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            })
        }
        row.addView(text)

        // Right-side accessory: chevron for nav rows, checkmark for option/toggle.
        val markText = when {
            type == "nav"  -> "›"
            checked        -> "✓"
            else           -> ""
        }
        val mark = TextView(activity).apply {
            tag = "checkmark"
            this.text = markText
            setTextColor(
                if (type == "nav") Color.parseColor("#99FFFFFF")
                else Color.parseColor("#FF339AF0")
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val w = dp(20f)
            layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
        }
        row.addView(mark)

        return row
    }

    private fun applySelectionStyles() {
        rowViews.forEachIndexed { i, row ->
            if (row !is LinearLayout) return@forEachIndexed
            row.background = buildRowBackground(i == selectedIdx)
        }
    }

    private fun buildRowBackground(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            setColor(if (selected) Color.parseColor("#33339AF0") else Color.TRANSPARENT)
            cornerRadius = dp(6f).toFloat()
            if (selected) setStroke(dp(2f), Color.parseColor("#FF339AF0"))
            else setStroke(dp(2f), Color.TRANSPARENT)
        }
    }

    private fun ensureSelectedVisible() {
        val row = rowViews.getOrNull(selectedIdx) ?: return
        row.post { row.requestRectangleOnScreen(android.graphics.Rect(0, 0, row.width, row.height)) }
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, activity.resources.displayMetrics
    ).toInt()
}

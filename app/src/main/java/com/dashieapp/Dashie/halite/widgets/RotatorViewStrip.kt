package com.dashieapp.Dashie.halite.widgets

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.sidebar.SidebarScaling

/**
 * Always-visible "view selector" strip anchored at the bottom-right corner
 * of a rotator slot. One icon per widget in the rotation; tap to switch,
 * tap on the active icon (or long-press any icon) to toggle the freeze
 * (rotation pause). The active icon renders in orange; inactive icons in
 * white-ish gray. The frozen icon gets an orange border.
 *
 * Wrapped in a HorizontalScrollView so future scenarios with many widgets
 * scroll horizontally — for now the strip is short enough to fit.
 *
 * View list comes from JS via setRotatorViews(slotId, viewsJson, activeIndex):
 *   viewsJson is an array of { type: 'weather_hourly' | ... , label?: string }
 */
class RotatorViewStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "RotatorViewStrip"
        const val ACTION_GOTO        = "goto"          // tap inactive icon
        const val ACTION_TOGGLE_FREEZE = "toggle-freeze" // tap active icon OR long-press
        // Visual sizing — base values, multiplied by SidebarScaling so the
        // rotator strip tracks the same device boost as the sidebar (TV 0.75×,
        // Fire tablet 0.85×, Samsung/Mio 1.2×, etc).
        private const val ICON_DP = 23f
        private const val ICON_PAD_DP = 5f      // padding inside each icon button
        private const val STRIP_PAD_H_DP = 6f
        private const val STRIP_PAD_V_DP = 4f
        private const val ICON_GAP_DP = 4f
        // Active-icon orange (matches Dashie brand orange).
        private val ACTIVE_TINT = Color.parseColor("#FF9500")
        // Inactive: medium gray — readable on the white widget background.
        private val INACTIVE_TINT = Color.parseColor("#FF999999")
        private val FROZEN_BORDER_COLOR = Color.parseColor("#FF9500")
    }

    /** Per-device boost shared with the sidebar so all chrome scales together. */
    private val scale: Float = SidebarScaling.effectiveMultiplier(context)

    /** Emitted on tap-inactive (action=goto, type=widgetType) and on
     *  tap-active OR long-press (action=toggle-freeze, type=widgetType). */
    var onAction: ((action: String, widgetType: String) -> Unit)? = null

    private val row: LinearLayout
    private var activeWidgetType: String? = null
    private var frozenWidgetType: String? = null
    /** Per-widget-type cache of icon button views, in display order. */
    private val iconViews = LinkedHashMap<String, ImageView>()

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER

        row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // No pill chrome — icons sit directly on the widget's white
            // background (the slot's clip wrapper renders the bg). Saves
            // visual weight; gray inactive icons read fine on white.
            val padH = dp(STRIP_PAD_H_DP * scale); val padV = dp(STRIP_PAD_V_DP * scale)
            setPadding(padH, padV, padH, padV)
        }
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    /** Cached list of widgets so we can re-render the row when the
     *  frozen state changes (frozen mode hides non-frozen icons). */
    private var allViews: List<Pair<String, String>> = emptyList()

    /**
     * Rebuild the strip from a list of widgets. Each entry is
     * (widgetType, displayLabel). Active widget renders orange.
     */
    fun setViews(views: List<Pair<String, String>>, activeWidgetType: String?, frozenWidgetType: String?) {
        allViews = views
        this.activeWidgetType = activeWidgetType
        this.frozenWidgetType = frozenWidgetType
        rebuildRow()
    }

    /** Update which widget is currently active (orange tint). */
    fun setActive(widgetType: String?) {
        activeWidgetType = widgetType
        applyTintsAndBorder()
    }

    /**
     * Update which widget is frozen. When non-null, the strip collapses
     * to [pauseButton, frozenIcon] — non-frozen icons are removed so the
     * UI clearly reads as "we're parked here, click to resume". Tapping
     * either the pause button or the icon toggles the freeze off.
     */
    fun setFrozen(widgetType: String?) {
        if (frozenWidgetType == widgetType) return
        frozenWidgetType = widgetType
        rebuildRow()
    }

    /** Rebuild the row contents from current state (allViews + frozen). */
    private fun rebuildRow() {
        Log.d(TAG, "rebuildRow: views=${allViews.size} active=$activeWidgetType frozen=$frozenWidgetType scale=$scale")
        row.removeAllViews()
        iconViews.clear()

        val gapPx = dp(ICON_GAP_DP * scale)
        val iconSizePx = dp(ICON_DP * scale) + dp(ICON_PAD_DP * scale) * 2

        val frozen = frozenWidgetType
        if (frozen != null && allViews.any { it.first == frozen }) {
            // Frozen mode — only the frozen icon (right) and a pause
            // button (left) are shown. Tapping either resumes rotation.
            // Pause button is ~30% smaller than the rest of the icons.
            val pauseSizePx = (iconSizePx * 0.7f).toInt()
            row.addView(makePauseButton(), LinearLayout.LayoutParams(pauseSizePx, pauseSizePx))
            row.addView(spacer(gapPx))
            val btn = makeIconButton(frozen)
            row.addView(btn, LinearLayout.LayoutParams(iconSizePx, iconSizePx))
            iconViews[frozen] = btn
        } else {
            // Normal mode — one icon per widget in the rotation.
            allViews.forEachIndexed { index, (type, _label) ->
                if (index > 0) row.addView(spacer(gapPx))
                val btn = makeIconButton(type)
                row.addView(btn, LinearLayout.LayoutParams(iconSizePx, iconSizePx))
                iconViews[type] = btn
            }
        }
        applyTintsAndBorder()
    }

    private fun applyTintsAndBorder() {
        for ((type, view) in iconViews) {
            val isActive = type == activeWidgetType
            val isFrozen = type == frozenWidgetType
            view.setColorFilter(if (isActive || isFrozen) ACTIVE_TINT else INACTIVE_TINT)
            view.background = if (isFrozen) frozenBorderDrawable() else null
        }
    }

    /** Small circular pause button that appears in frozen mode. Tapping
     *  it toggles freeze off (same as tapping the frozen icon). Solid
     *  orange fill with a white pause glyph. */
    private fun makePauseButton(): ImageView {
        val padPx = dp(3f * scale)
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_pause)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(padPx, padPx, padPx, padPx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ACTIVE_TINT)
            }
            isClickable = true
            setOnClickListener {
                onAction?.invoke(ACTION_TOGGLE_FREEZE, frozenWidgetType ?: "")
            }
        }
    }

    private fun frozenBorderDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(8f * scale).toFloat()
            setStroke(dp(2f * scale), FROZEN_BORDER_COLOR)
        }
    }

    private fun makeIconButton(widgetType: String): ImageView {
        val iconRes = iconResForType(widgetType)
        val padPx = dp(ICON_PAD_DP * scale)
        val view = ImageView(context).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(padPx, padPx, padPx, padPx)
            isClickable = true
            isLongClickable = true
        }

        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                Log.d(TAG, "onSingleTapUp type=$widgetType active=$activeWidgetType frozen=$frozenWidgetType")
                if (widgetType == activeWidgetType) {
                    // Tap on the active icon = toggle freeze.
                    onAction?.invoke(ACTION_TOGGLE_FREEZE, widgetType)
                } else {
                    onAction?.invoke(ACTION_GOTO, widgetType)
                }
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                Log.d(TAG, "onLongPress type=$widgetType")
                onAction?.invoke(ACTION_TOGGLE_FREEZE, widgetType)
            }
        })
        view.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                Log.d(TAG, "ACTION_DOWN type=$widgetType x=${ev.x.toInt()} y=${ev.y.toInt()} attached=${view.isAttachedToWindow} alpha=${view.alpha}")
            }
            gestureDetector.onTouchEvent(ev)
        }
        return view
    }

    /** Map a widget type string to its drawable resource. Unknown types
     *  fall back to the family-cards silhouette so the strip never has a
     *  blank slot. */
    private fun iconResForType(type: String): Int = when (type) {
        "weather_hourly"   -> R.drawable.ic_view_weather_hourly
        "weather_daily"    -> R.drawable.ic_view_weather_daily
        "family_cards"     -> R.drawable.ic_view_family_cards
        // Single-purpose family card variants reuse the sidebar glyphs
        // the user already associates with chores / locations. Kept in
        // sync with WidgetIconRegistry (used by the settings chooser).
        "family_chores"    -> R.drawable.ic_sidebar_tasks
        "family_locations" -> R.drawable.ic_sidebar_location
        else -> R.drawable.ic_view_family_cards
    }

    private fun spacer(widthPx: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(widthPx, 1)
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
    ).toInt()
}

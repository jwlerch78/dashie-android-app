package com.dashieapp.Dashie.sidebar

import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.dashieapp.Dashie.R

/**
 * Manages the sidebar strip UI — a 69dp vertical bar with icon buttons.
 *
 * Buttons (bottom-to-top): Music, Camera, Volume, Brightness, Hamburger.
 * Above those (when 2+ views enabled): divider + view switcher buttons (HA, Calendar, Chores, Rewards, Location).
 * Music and Camera buttons are conditionally visible based on feature state.
 * View switcher buttons are conditionally visible based on enabled views.
 */
class SidebarStripView(
    private val root: FrameLayout
) {
    // ── Views ────────────────────────────────────────────────────────

    val stripRoot: FrameLayout = root.findViewById(R.id.sidebarStripRoot)
    val backdrop: View = root.findViewById(R.id.sidebarBackdrop)
    val panel: View = root.findViewById(R.id.sidebarStripPanel)

    val btnMusic: FrameLayout = root.findViewById(R.id.sidebarBtnMusic)
    val btnCamera: FrameLayout = root.findViewById(R.id.sidebarBtnCamera)
    val btnVolume: FrameLayout = root.findViewById(R.id.sidebarBtnVolume)
    val btnBrightness: FrameLayout = root.findViewById(R.id.sidebarBtnBrightness)
    val btnHamburger: FrameLayout = root.findViewById(R.id.sidebarBtnHamburger)

    val iconVolume: ImageView = root.findViewById(R.id.sidebarIconVolume)

    // ── View switcher buttons ────────────────────────────────────────

    val btnViewHomeAssistant: FrameLayout = root.findViewById(R.id.sidebarBtnViewHomeAssistant)
    val btnViewCalendar: FrameLayout = root.findViewById(R.id.sidebarBtnViewCalendar)
    val btnViewChores: FrameLayout = root.findViewById(R.id.sidebarBtnViewChores)
    val btnViewRewards: FrameLayout = root.findViewById(R.id.sidebarBtnViewRewards)
    val btnViewLocation: FrameLayout = root.findViewById(R.id.sidebarBtnViewLocation)
    private val viewSwitcherDivider: View = root.findViewById(R.id.sidebarViewSwitcherDivider)
    /** Sign-in required warning badge on the HA icon. Visibility is driven
     *  by [setHaNeedsLogin] from NativeSidebarController. */
    private val badgeHaWarning: ImageView = root.findViewById(R.id.sidebarBadgeHaWarning)

    /**
     * Show or hide the amber warning triangle on the Home Assistant icon.
     * Returns the new visibility state for callers that want to use it
     * elsewhere (e.g. to override the click handler).
     */
    fun setHaNeedsLogin(needsLogin: Boolean) {
        badgeHaWarning.visibility = if (needsLogin) View.VISIBLE else View.GONE
    }

    /** Whether the HA warning badge is currently visible. Read by the
     *  click handler in NativeSidebarController to decide between the
     *  default view-switch and the HA login prompt. */
    val haNeedsLogin: Boolean
        get() = badgeHaWarning.visibility == View.VISIBLE

    private val viewButtonMap: Map<String, FrameLayout> = mapOf(
        "home" to btnViewHomeAssistant,
        "calendar" to btnViewCalendar,
        "chores" to btnViewChores,
        "rewards" to btnViewRewards,
        "location" to btnViewLocation
    )

    private val viewIconMap: Map<String, ImageView> = mapOf(
        "home" to root.findViewById(R.id.sidebarIconViewHomeAssistant),
        "calendar" to root.findViewById(R.id.sidebarIconViewCalendar),
        "chores" to root.findViewById(R.id.sidebarIconViewChores),
        "rewards" to root.findViewById(R.id.sidebarIconViewRewards),
        "location" to root.findViewById(R.id.sidebarIconViewLocation)
    )

    private val viewActiveIndicatorMap: Map<String, View> = mapOf(
        "home" to root.findViewById(R.id.sidebarActiveIndicatorViewHomeAssistant),
        "calendar" to root.findViewById(R.id.sidebarActiveIndicatorViewCalendar),
        "chores" to root.findViewById(R.id.sidebarActiveIndicatorViewChores),
        "rewards" to root.findViewById(R.id.sidebarActiveIndicatorViewRewards),
        "location" to root.findViewById(R.id.sidebarActiveIndicatorViewLocation)
    )

    private val allIcons: List<ImageView> = listOf(
        root.findViewById(R.id.sidebarIconMusic),
        root.findViewById(R.id.sidebarIconCamera),
        root.findViewById(R.id.sidebarIconVolume),
        root.findViewById(R.id.sidebarIconBrightness),
        root.findViewById(R.id.sidebarIconHamburger),
        root.findViewById(R.id.sidebarIconViewHomeAssistant),
        root.findViewById(R.id.sidebarIconViewCalendar),
        root.findViewById(R.id.sidebarIconViewChores),
        root.findViewById(R.id.sidebarIconViewRewards),
        root.findViewById(R.id.sidebarIconViewLocation)
    )

    // ── Theme colors (from webapp via DashieNative.setSidebarThemeColors) ──────

    /** Themed strip backdrop (parsed from --bg-primary). When set, [setPinnedBackground]
     *  uses it for the pinned state instead of the static drawables. Null until JS
     *  has pushed a theme color. */
    private var themedBackdropColor: Int? = null

    /** Accent color (--accent-secondary equivalent, reserved for future use) */
    fun setAccentColor(colorHex: String) {
        // Reserved for future use
    }

    /** Primary color (--accent-primary equivalent) — used for the active view indicator bar */
    fun setPrimaryColor(colorHex: String) {
        try {
            val color = Color.parseColor(colorHex)
            viewActiveIndicatorMap.values.forEach { it.setBackgroundColor(color) }
        } catch (e: IllegalArgumentException) {
            android.util.Log.w("SidebarStrip", "Invalid primary color: $colorHex")
        }
    }

    /** Backdrop color (--bg-primary equivalent). Painted on the pinned panel so the strip
     *  tracks per-theme widget background instead of the static near-black/near-white
     *  drawables. Overlay (unpinned) mode keeps its translucent drawable so floating
     *  sidebar doesn't block content underneath.
     *
     *  Uses a GradientDrawable with 10dp corner radius to preserve the rounded-corner
     *  shape from bg_sidebar_strip.xml (a flat setBackgroundColor would lose the
     *  rounding). */
    fun setBackdropColor(color: Int) {
        themedBackdropColor = color
        val density = root.resources.displayMetrics.density
        panel.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            setColor(color)
        }
    }

    /** Read the themed backdrop color (null when JS hasn't pushed one). Used by
     *  NativeSidebarController.currentBackdropColor() to keep the separate
     *  sidebarBackgroundStrip view in sync with the panel. */
    fun getThemedBackdropColor(): Int? = themedBackdropColor

    /** Drop any pushed theme color so the strip reverts to its neutral static
     *  drawables. Called on sign-out — a kiosk HA user with no Dashie account
     *  shouldn't keep the previously-logged-in account's theme on the strip. */
    fun clearThemedColors(pinned: Boolean, isDarkMode: Boolean) {
        themedBackdropColor = null
        setPinnedBackground(pinned, isDarkMode)
    }

    /** Theme the focused-state highlight on every sidebar icon button. The static
     *  bg_sidebar_button drawable hardcodes #FF9500 orange for focus fill +
     *  stroke; replace each button's background with a themed RippleDrawable.
     *
     *  Iterates known button references directly rather than walking the panel
     *  tree by constantState — RippleDrawable's wrapping confuses constantState
     *  equality and the walker silently no-ops. Direct refs are exhaustive
     *  (every button in sidebar_strip.xml has a field on this class) and
     *  cheaper. */
    fun applyButtonHighlightTheme(accent: Int) {
        val buttons = listOf(
            btnMusic, btnCamera, btnVolume, btnBrightness, btnHamburger,
            btnViewCalendar, btnViewChores, btnViewRewards, btnViewLocation, btnViewHomeAssistant
        )
        // New drawable instance per button — sharing a single RippleDrawable
        // would make focus state on one button highlight all of them.
        buttons.forEach { it.background = makeThemedSidebarButtonBg(accent) }
    }

    private fun makeThemedSidebarButtonBg(accent: Int): android.graphics.drawable.RippleDrawable {
        val density = root.resources.displayMetrics.density
        val focusedFill = (accent and 0x00FFFFFF) or (0x33 shl 24)  // ~20% alpha
        val rippleAlpha = (accent and 0x00FFFFFF) or (0x30 shl 24)
        val selector = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 6f * density
                setColor(focusedFill)
                setStroke((2f * density).toInt(), accent)
            })
            addState(intArrayOf(), android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 6f * density
                setColor(android.graphics.Color.TRANSPARENT)
            })
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(rippleAlpha),
            selector,
            null
        )
    }

    // ── Strip width ─────────────────────────────────────────────────

    /**
     * Apply a size multiplier to the strip panel width.
     * Base width is 69dp (XML default); multiplier scales proportionally so the
     * strip stays visually balanced with the icon multiplier.
     */
    fun applyStripWidth(multiplier: Float) {
        val baseDp = 69f
        val widthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, baseDp * multiplier, root.resources.displayMetrics
        ).toInt()
        panel.layoutParams = panel.layoutParams?.apply { width = widthPx }
        panel.requestLayout()
    }

    // ── Vertical spacing ────────────────────────────────────────────

    /** Multiplier applied to button height and inter-button margin. Read by setEnabledViewButtons. */
    private var spacingMultiplier: Float = 1f

    /**
     * Apply a size multiplier to button height (base 50dp) and vertical gap (base 8dp)
     * between buttons. Keeps icons packed tightly on devices with smaller strips (TV,
     * Fire tablet-class). First visible button retains 0 top margin (handled in
     * setEnabledViewButtons and explicit no-margin in XML for the top of each section).
     */
    fun applyButtonSpacing(multiplier: Float) {
        spacingMultiplier = multiplier
        val buttonHeightPx = dp(50f * multiplier)
        val marginPx = dp(8f * multiplier)
        val dividerMarginPx = dp(10f * multiplier)

        // All buttons (view switcher + control strip)
        val allButtons = listOf(
            btnViewCalendar, btnViewChores, btnViewRewards, btnViewLocation, btnViewHomeAssistant,
            btnMusic, btnCamera, btnVolume, btnBrightness, btnHamburger
        )
        allButtons.forEach { btn ->
            val lp = btn.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return@forEach
            lp.height = buttonHeightPx
            // Preserve existing top margin behavior: 0 for first-in-section (set by XML
            // or setEnabledViewButtons), scaled-8dp for others. We detect "non-zero" to
            // avoid stomping intentional zero margins.
            if (lp.topMargin > 0) lp.topMargin = marginPx
            btn.layoutParams = lp
        }

        // Divider below view switcher
        (viewSwitcherDivider.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.bottomMargin = dividerMarginPx
            viewSwitcherDivider.layoutParams = lp
        }
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, root.resources.displayMetrics
    ).toInt()

    // ── Icon sizing ─────────────────────────────────────────────────

    /**
     * Apply a size multiplier to control icons (music, camera, volume, brightness, hamburger).
     * View switcher icons get a 10% boost on top of this for visual prominence.
     * Base size is 30dp; multiplier scales proportionally.
     */
    fun applyIconSize(multiplier: Float) {
        val baseDp = 30f
        val controlIcons = listOf(
            root.findViewById<ImageView>(R.id.sidebarIconMusic),
            root.findViewById<ImageView>(R.id.sidebarIconCamera),
            root.findViewById<ImageView>(R.id.sidebarIconVolume),
            root.findViewById<ImageView>(R.id.sidebarIconBrightness),
            root.findViewById<ImageView>(R.id.sidebarIconHamburger)
        )
        val viewIcons = viewIconMap.values.toList()

        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, baseDp * multiplier, root.resources.displayMetrics
        ).toInt()
        val viewSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, baseDp * multiplier * 1.2f, root.resources.displayMetrics
        ).toInt()

        controlIcons.forEach { icon ->
            icon?.layoutParams = icon?.layoutParams?.apply { width = sizePx; height = sizePx }
            icon?.requestLayout()
        }
        viewIcons.forEach { icon ->
            icon.layoutParams = icon.layoutParams.apply { width = viewSizePx; height = viewSizePx }
            icon.requestLayout()
        }
    }

    // ── Button visibility ────────────────────────────────────────────

    fun setMusicVisible(visible: Boolean) {
        btnMusic.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setCameraVisible(visible: Boolean) {
        btnCamera.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ── Volume icon state ────────────────────────────────────────────

    fun updateVolumeIcon(isMuted: Boolean) {
        iconVolume.setImageResource(
            if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
    }

    // ── Theme / Dark mode support ────────────────────────────────────

    fun applyTheme(isDarkMode: Boolean) {
        val iconTint = if (isDarkMode) 0xFFFFFFFF.toInt() else 0xFF212121.toInt()
        val filter: ColorFilter = PorterDuffColorFilter(iconTint, PorterDuff.Mode.SRC_IN)
        allIcons.forEach { it.colorFilter = filter }
    }

    fun setPinnedBackground(pinned: Boolean, isDarkMode: Boolean) {
        // Pinned + themed color present: paint themed color with the SAME 10dp
        // rounded corners as bg_sidebar_strip.xml (a flat setBackgroundColor
        // would drop the rounding).
        // Overlay always uses the translucent drawable so floating sidebar
        // doesn't block content underneath.
        val themed = themedBackdropColor
        if (pinned && themed != null) {
            val density = root.resources.displayMetrics.density
            panel.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(themed)
            }
            return
        }
        panel.setBackgroundResource(
            if (pinned) {
                if (isDarkMode) R.drawable.bg_sidebar_strip else R.drawable.bg_sidebar_strip_light
            } else {
                if (isDarkMode) R.drawable.bg_sidebar_strip_overlay else R.drawable.bg_sidebar_strip_overlay_light
            }
        )
    }

    // ── Backdrop for overlay (unpinned) mode ─────────────────────────

    fun showBackdrop() {
        backdrop.visibility = View.VISIBLE
    }

    fun hideBackdrop() {
        backdrop.visibility = View.GONE
    }

    // ── View switcher visibility / active state ───────────────────────

    /**
     * Show or hide the view switcher section (divider + buttons).
     * Called by NativeSidebarController when enabled views are updated.
     * @param enabledViewIds List of view IDs to show (e.g. ["calendar", "chores"]).
     *                       If fewer than 2, the entire section is hidden.
     */
    fun setEnabledViewButtons(enabledViewIds: List<String>) {
        val showSwitcher = enabledViewIds.size >= 2
        val scaledMarginPx = dp(8f * spacingMultiplier)
        viewButtonMap.forEach { (viewId, btn) ->
            // Clear top margin on first visible button so centering is symmetric
            if (showSwitcher && viewId in enabledViewIds) {
                val isFirstVisible = enabledViewIds.firstOrNull() == viewId
                val params = btn.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                params?.topMargin = if (isFirstVisible) 0 else scaledMarginPx
                btn.layoutParams = params
                btn.visibility = View.VISIBLE
            } else {
                btn.visibility = View.GONE
            }
        }
        viewSwitcherDivider.visibility = if (showSwitcher) View.VISIBLE else View.GONE
    }

    /**
     * Highlight the active view button (current view).
     * Active button shows the indicator bar; all icons remain fully opaque.
     */
    fun setActiveViewButton(activeViewId: String?) {
        viewButtonMap.forEach { (viewId, btn) ->
            if (btn.visibility != View.VISIBLE) return@forEach
            val indicator = viewActiveIndicatorMap[viewId]
            indicator?.visibility = if (viewId == activeViewId) View.VISIBLE else View.GONE
        }
    }

    // ── Clickable buttons list (for D-pad navigation) ────────────────

    fun getVisibleButtons(): List<View> {
        // Order MUST match the visual XML layout order (top-to-bottom) so d-pad
        // up/down navigation follows what the user sees. Home Assistant is the
        // last view button in the layout, not the first.
        val viewBtns = listOf(
            btnViewCalendar, btnViewChores, btnViewRewards, btnViewLocation, btnViewHomeAssistant
        ).filter { it.visibility == View.VISIBLE }
        val controlBtns = listOf(btnMusic, btnCamera, btnVolume, btnBrightness, btnHamburger)
            .filter { it.visibility == View.VISIBLE }
        return viewBtns + controlBtns
    }
}

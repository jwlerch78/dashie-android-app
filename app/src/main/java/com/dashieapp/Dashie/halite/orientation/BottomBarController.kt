package com.dashieapp.Dashie.halite.orientation

import android.app.Activity
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.theming.NativeThemeManager

/**
 * Thin view controller for the portrait bottom bar (`bottom_bar.xml`).
 *
 * Phase 1 scope: the bottom bar acts as a *view mirror* of the existing
 * landscape sidebar.
 *  - Click handlers forward to the corresponding `sidebarBtn*` views via
 *    [View.performClick] — this reuses every click handler
 *    `NativeSidebarController.wireButtonClicks` already installed
 *    (volume popout, hamburger popout, view switch, etc.) without
 *    duplicating that wiring graph.
 *  - Button visibility, active-view indicator, and the HA sign-in badge
 *    are synced from the sidebar in [syncFromSidebar], which the
 *    portrait layout manager invokes whenever the bar becomes visible.
 *
 * Note: `performClick()` invokes the click handler regardless of the
 * source view's visibility, so it works even though the sidebar is GONE
 * in portrait mode. This is intentional — the underlying sidebar logic
 * (NativeSidebarController) keeps its in-memory state, we just route
 * clicks to it.
 *
 * A future refactor could pull the sidebar's controller surface behind
 * an interface and wire the bottom bar directly. For Phase 1 the
 * delegate-via-performClick approach keeps the diff small and avoids
 * touching the 1545-line NativeSidebarController.
 */
class BottomBarController(
    private val activity: Activity,
    private val isDarkModeProvider: () -> Boolean = { false }
) {

    private val barRoot: FrameLayout? = activity.findViewById(R.id.bottomBarRoot)
    private val barPanel: View? = activity.findViewById(R.id.bottomBarPanel)

    private val btnMusic: FrameLayout? = activity.findViewById(R.id.bottomBarBtnMusic)
    private val btnCamera: FrameLayout? = activity.findViewById(R.id.bottomBarBtnCamera)
    private val btnVolume: FrameLayout? = activity.findViewById(R.id.bottomBarBtnVolume)
    private val btnBrightness: FrameLayout? = activity.findViewById(R.id.bottomBarBtnBrightness)
    private val btnHamburger: FrameLayout? = activity.findViewById(R.id.bottomBarBtnHamburger)

    private val btnViewHomeAssistant: FrameLayout? =
        activity.findViewById(R.id.bottomBarBtnViewHomeAssistant)
    private val btnViewCalendar: FrameLayout? =
        activity.findViewById(R.id.bottomBarBtnViewCalendar)
    private val btnViewChores: FrameLayout? =
        activity.findViewById(R.id.bottomBarBtnViewChores)
    private val btnViewRewards: FrameLayout? =
        activity.findViewById(R.id.bottomBarBtnViewRewards)
    private val btnViewLocation: FrameLayout? =
        activity.findViewById(R.id.bottomBarBtnViewLocation)

    private val iconVolume: ImageView? = activity.findViewById(R.id.bottomBarIconVolume)
    private val badgeHaWarning: ImageView? = activity.findViewById(R.id.bottomBarBadgeHaWarning)

    /** Map of view-switcher id → (this bar's button, this bar's active indicator). */
    private val viewButtons: Map<String, Pair<FrameLayout?, View?>> = mapOf(
        "home" to (btnViewHomeAssistant to
            activity.findViewById<View>(R.id.bottomBarActiveIndicatorViewHomeAssistant)),
        "calendar" to (btnViewCalendar to
            activity.findViewById<View>(R.id.bottomBarActiveIndicatorViewCalendar)),
        "chores" to (btnViewChores to
            activity.findViewById<View>(R.id.bottomBarActiveIndicatorViewChores)),
        "rewards" to (btnViewRewards to
            activity.findViewById<View>(R.id.bottomBarActiveIndicatorViewRewards)),
        "location" to (btnViewLocation to
            activity.findViewById<View>(R.id.bottomBarActiveIndicatorViewLocation))
    )

    /** Every ImageView in the bar — used for tint application. */
    private val allIcons: List<ImageView> = listOfNotNull(
        activity.findViewById(R.id.bottomBarIconHamburger),
        activity.findViewById(R.id.bottomBarIconBrightness),
        activity.findViewById(R.id.bottomBarIconVolume),
        activity.findViewById(R.id.bottomBarIconCamera),
        activity.findViewById(R.id.bottomBarIconMusic),
        activity.findViewById(R.id.bottomBarIconViewHomeAssistant),
        activity.findViewById(R.id.bottomBarIconViewCalendar),
        activity.findViewById(R.id.bottomBarIconViewChores),
        activity.findViewById(R.id.bottomBarIconViewRewards),
        activity.findViewById(R.id.bottomBarIconViewLocation)
    )

    /** Every active-indicator strip on the view-switcher buttons. */
    private val activeIndicators: List<View> = listOfNotNull(
        activity.findViewById(R.id.bottomBarActiveIndicatorViewHomeAssistant),
        activity.findViewById(R.id.bottomBarActiveIndicatorViewCalendar),
        activity.findViewById(R.id.bottomBarActiveIndicatorViewChores),
        activity.findViewById(R.id.bottomBarActiveIndicatorViewRewards),
        activity.findViewById(R.id.bottomBarActiveIndicatorViewLocation)
    )

    private val themeListener: () -> Unit = { applyThemeFromPalette() }

    fun initialize() {
        wireClickDelegations()
        // Apply current dark-mode tint immediately so icons (especially
        // ic_brightness, which has fillColor=#000 baked in) don't render
        // black-on-black before the first theme push.
        applyDarkModeTint(isDarkModeProvider())
        // Also try the palette right away in case it's already populated
        // from a prior session.
        applyThemeFromPalette()
        // Subscribe to live theme updates from the central palette.
        NativeThemeManager.addListener(themeListener)
    }

    fun destroy() {
        NativeThemeManager.removeListener(themeListener)
    }

    /** Toggle the bar's root visibility. */
    fun setVisible(visible: Boolean) {
        barRoot?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Re-apply icon tint when the dark-mode flag flips. Mirrors
     * SidebarStripView.applyTheme — both sources of tint are the same
     * dark-mode pref so the bottom bar matches the sidebar exactly.
     */
    fun onDarkModeChanged(isDarkMode: Boolean) {
        applyDarkModeTint(isDarkMode)
        // Panel background may also be theme-dependent — re-pull palette.
        applyThemeFromPalette()
    }

    private fun applyDarkModeTint(isDarkMode: Boolean) {
        val iconTint = if (isDarkMode) 0xFFFFFFFF.toInt() else 0xFF212121.toInt()
        val filter: ColorFilter = PorterDuffColorFilter(iconTint, PorterDuff.Mode.SRC_IN)
        allIcons.forEach { it.colorFilter = filter }
    }

    /**
     * Apply the central palette to the panel background + active
     * indicators. Mirrors NativeSidebarController.setThemeColors's
     * sidebar-side updates so the two bars look identical.
     *
     * Called from [initialize] for the first paint and from the
     * [NativeThemeManager] listener for live updates.
     */
    private fun applyThemeFromPalette() {
        val tm = NativeThemeManager
        // Panel background — same source the sidebar's setBackdropColor uses
        // (--bg-primary). When null (no theme pushed yet) we leave the XML
        // bg_sidebar_strip drawable in place.
        tm.getBgPrimary()?.let { color ->
            val density = activity.resources.displayMetrics.density
            barPanel?.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(color)
            }
        }
        // Bar root backdrop — paint the area BEHIND the panel (the 8dp
        // side margins + the area between the panel's rounded corners
        // and the screen edges) with the grid-gap color. Mirrors the
        // sidebar's sidebarBackgroundStrip pattern so the colored
        // "land" extends behind the bar instead of showing the
        // WebView's content underneath.
        val backdrop = tm.getGridGap() ?: tm.getBgPrimary()
        backdrop?.let { color ->
            barRoot?.setBackgroundColor(color)
        }
        // Active indicator color — same source the sidebar uses
        // (--accent-primary).
        tm.getAccentPrimary()?.let { color ->
            activeIndicators.forEach { it.setBackgroundColor(color) }
        }
    }

    /**
     * Set the active-view indicator strip directly. Mirrors the sidebar's
     * setActiveViewButton — used by the active-view callback hook so the
     * bottom bar's indicator updates in real time when the user changes
     * widgets (rather than waiting for the next syncFromSidebar pass).
     */
    fun setActiveView(activeViewId: String?) {
        viewButtons.forEach { (viewId, pair) ->
            val (_, indicator) = pair
            indicator?.visibility =
                if (viewId == activeViewId) View.VISIBLE else View.GONE
        }
    }

    /**
     * Sync visibility / active state from the sidebar. Called by the
     * portrait layout manager when the bottom bar becomes visible and
     * whenever the sidebar's state may have changed.
     */
    fun syncFromSidebar() {
        // Mirror visibility for system controls (music / camera are
        // conditionally enabled; volume / brightness / hamburger are
        // always visible in the sidebar).
        copyVisibility(R.id.sidebarBtnMusic, btnMusic)
        copyVisibility(R.id.sidebarBtnCamera, btnCamera)

        // Mirror view-switcher visibility + active indicator.
        viewButtons.forEach { (viewId, pair) ->
            val (btn, indicator) = pair
            val sidebarBtnId = sidebarViewBtnId(viewId)
            val sidebarBtn = activity.findViewById<View>(sidebarBtnId)
            btn?.visibility = sidebarBtn?.visibility ?: View.GONE

            val sidebarIndicatorId = sidebarViewIndicatorId(viewId)
            val sidebarIndicator = activity.findViewById<View>(sidebarIndicatorId)
            indicator?.visibility = sidebarIndicator?.visibility ?: View.GONE
        }

        // Mirror HA sign-in badge.
        val sidebarBadge = activity.findViewById<View>(R.id.sidebarBadgeHaWarning)
        badgeHaWarning?.visibility = sidebarBadge?.visibility ?: View.GONE

        // Mirror volume icon (muted ↔ unmuted).
        val sidebarVolumeIcon = activity.findViewById<ImageView>(R.id.sidebarIconVolume)
        sidebarVolumeIcon?.drawable?.let { iconVolume?.setImageDrawable(it.constantState?.newDrawable()) }
    }

    private fun wireClickDelegations() {
        // System controls — performClick() fires the sidebar's installed
        // click handler regardless of the sidebar's current visibility.
        btnMusic?.setOnClickListener { delegateClick(R.id.sidebarBtnMusic) }
        btnCamera?.setOnClickListener { delegateClick(R.id.sidebarBtnCamera) }
        btnVolume?.setOnClickListener { delegateClick(R.id.sidebarBtnVolume) }
        btnBrightness?.setOnClickListener { delegateClick(R.id.sidebarBtnBrightness) }
        btnHamburger?.setOnClickListener { delegateClick(R.id.sidebarBtnHamburger) }

        // View switcher — delegate to matching sidebar button.
        viewButtons.forEach { (viewId, pair) ->
            val (btn, _) = pair
            btn?.setOnClickListener { delegateClick(sidebarViewBtnId(viewId)) }
        }
    }

    private fun delegateClick(sidebarBtnId: Int) {
        val target = activity.findViewById<View>(sidebarBtnId)
        if (target == null) {
            Log.w(TAG, "Sidebar button $sidebarBtnId not found — click dropped")
            return
        }
        target.performClick()
    }

    private fun sidebarViewBtnId(viewId: String): Int = when (viewId) {
        "home" -> R.id.sidebarBtnViewHomeAssistant
        "calendar" -> R.id.sidebarBtnViewCalendar
        "chores" -> R.id.sidebarBtnViewChores
        "rewards" -> R.id.sidebarBtnViewRewards
        "location" -> R.id.sidebarBtnViewLocation
        else -> View.NO_ID
    }

    private fun sidebarViewIndicatorId(viewId: String): Int = when (viewId) {
        "home" -> R.id.sidebarActiveIndicatorViewHomeAssistant
        "calendar" -> R.id.sidebarActiveIndicatorViewCalendar
        "chores" -> R.id.sidebarActiveIndicatorViewChores
        "rewards" -> R.id.sidebarActiveIndicatorViewRewards
        "location" -> R.id.sidebarActiveIndicatorViewLocation
        else -> View.NO_ID
    }

    private fun copyVisibility(sourceId: Int, target: View?) {
        val source = activity.findViewById<View>(sourceId) ?: return
        target?.visibility = source.visibility
    }

    // ── D-pad navigation (TV / portrait) ─────────────────────────────
    // The bottom bar is the portrait equivalent of the sidebar. JS focuses it
    // (DashieNative.focusBottomBar) when the user presses DOWN at the bottom
    // widget row; ◄/► move between buttons; ▲ / BACK return to the dashboard;
    // CENTER activates (reusing the sidebar click delegation, so popouts open
    // and are then d-pad-navigated by NativeSidebarController's popout handler).
    // Mirrors the sidebar's idle-highlight timeout so the focus ring fades.
    private val dpadHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var highlightTimeoutRunnable: Runnable? = null

    /** Visible, focusable buttons in left-to-right visual order. */
    private fun visibleButtons(): List<FrameLayout> =
        listOfNotNull(
            btnHamburger, btnBrightness, btnVolume, btnCamera, btnMusic,
            btnViewCalendar, btnViewChores, btnViewRewards, btnViewLocation, btnViewHomeAssistant
        ).filter { it.visibility == View.VISIBLE }

    /** True when a bottom-bar button currently holds focus. */
    val hasFocus: Boolean
        get() = visibleButtons().any { it.isFocused }

    /** Entry point from DashieNative.focusBottomBar() — focus the first button. */
    fun focusBottomBar() {
        val buttons = visibleButtons()
        if (buttons.isEmpty()) return
        buttons.first().requestFocus()
        startHighlightTimeout()
        Log.d(TAG, "Bottom bar focused via d-pad")
    }

    /** Returns true if the key was consumed. Called when the bar has focus. */
    fun handleDpadKey(keyCode: Int): Boolean {
        val buttons = visibleButtons()
        if (buttons.isEmpty()) return false
        val current = buttons.indexOfFirst { it.isFocused }
        if (current < 0) return false
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                val next = if (current <= 0) buttons.size - 1 else current - 1
                buttons[next].requestFocus(); startHighlightTimeout(); true
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val next = if (current >= buttons.size - 1) 0 else current + 1
                buttons[next].requestFocus(); startHighlightTimeout(); true
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP,
            android.view.KeyEvent.KEYCODE_BACK -> { returnFocusToDashboard(); true }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> true  // nothing below the bar
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER -> {
                buttons[current].performClick(); startHighlightTimeout(); true
            }
            else -> false
        }
    }

    /** Clear bar focus and hand it back to the JS dashboard (last focused slot). */
    private fun returnFocusToDashboard() {
        cancelHighlightTimeout()
        visibleButtons().forEach { it.clearFocus() }
        val webView = activity.findViewById<android.webkit.WebView>(R.id.dashboardWebView)
        webView?.requestFocus()
        webView?.evaluateJavascript(
            // BUNDLE-EXEMPT: LayoutCanvasInputHandler_restoreFocusFromBottomBar — layout canvas is the full-mode dashboard; kiosk shows HA
            "if (typeof window.LayoutCanvasInputHandler_restoreFocusFromBottomBar === 'function') " +
                "window.LayoutCanvasInputHandler_restoreFocusFromBottomBar();",
            null
        )
        Log.d(TAG, "Bottom bar yielded focus back to dashboard")
    }

    private fun startHighlightTimeout() {
        cancelHighlightTimeout()
        val r = Runnable { if (hasFocus) returnFocusToDashboard() }
        highlightTimeoutRunnable = r
        dpadHandler.postDelayed(r, HIGHLIGHT_TIMEOUT_MS)
    }

    private fun cancelHighlightTimeout() {
        highlightTimeoutRunnable?.let { dpadHandler.removeCallbacks(it) }
        highlightTimeoutRunnable = null
    }

    companion object {
        private const val TAG = "BottomBarController"
        // Mirror SELECTION_TIMEOUT / the sidebar highlight timeout (20s).
        private const val HIGHLIGHT_TIMEOUT_MS = 20_000L
    }
}

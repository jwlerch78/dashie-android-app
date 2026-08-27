package com.dashieapp.Dashie.halite.orientation

import android.app.Activity
import android.util.Log
import android.view.View
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.preferences.DisplayPreferences

/**
 * Coordinates the sidebar ↔ bottom-bar swap on orientation change.
 *
 * Observes [OrientationController] and, in widgets layout mode:
 *  - PORTRAIT → hide `sidebar_strip`, show `bottom_bar`
 *  - LANDSCAPE → restore `sidebar_strip`, hide `bottom_bar`
 *
 * In single_panel / kiosk layouts the sidebar visibility is driven by
 * other code paths (kiosk shell hides it entirely, single_panel keeps it
 * for view switching) — this manager leaves them alone and only ever
 * hides the bottom bar in those modes.
 *
 * Note: the sidebar's own `NativeSidebarController` continues to drive
 * the sidebar's content state (pinned/overlay, button visibility, etc.)
 * — this manager only flips the strip's root visibility. Restoring the
 * strip in landscape is delegated to the sidebar controller (via the
 * callback in [onSidebarRestoreNeeded]) so its pinned/overlay logic stays
 * authoritative.
 */
class PortraitLayoutManager(
    private val activity: Activity,
    private val orientationController: OrientationController,
    private val displayPrefs: DisplayPreferences,
    private val bottomBar: BottomBarController,
    /**
     * Invoked when the sidebar should be made visible again after a
     * portrait → landscape switch. Implementations should re-run their
     * own visibility logic (typically `applySidebarVisibility()`).
     */
    private val onSidebarRestoreNeeded: () -> Unit,
    /**
     * Invoked with the authoritative "portrait widgets dashboard is active"
     * state (from OrientationController, not Configuration.orientation — a TV
     * ignores the portrait request and stays physically landscape). The
     * sidebar uses this to stay hidden and to route d-pad focus to the bottom
     * bar instead of itself.
     */
    private val onPortraitWidgetsActiveChanged: (Boolean) -> Unit = {},
    /**
     * Returns whether the visibility gate currently allows chrome
     * (sidebar / bottom bar) to be shown. When false (kiosk loading,
     * full-mode loading, edit mode, etc.), both bars stay hidden no
     * matter what the orientation says.
     */
    private val gateAllowsChrome: () -> Boolean = { true }
) {

    private val sidebarStripRoot: View? =
        activity.findViewById(R.id.sidebarStripRoot)

    // The colored "land" View painted by NativeSidebarController behind
    // the sidebar strip in landscape pinned mode. Setting the sidebar
    // root to GONE doesn't hide this — it's a sibling View. In portrait
    // we hide it explicitly; in landscape the sidebar controller's
    // updateRootBackground re-shows it when the sidebar shows.
    private val sidebarBackdropStrip: View? =
        activity.findViewById(R.id.sidebarBackgroundStrip)

    private val listener = OrientationController.Listener { orientation ->
        apply(orientation)
    }

    fun initialize() {
        // addListener fires immediately with the current orientation,
        // so the initial state lands correctly without an extra call.
        orientationController.addListener(listener)
    }

    fun destroy() {
        orientationController.removeListener(listener)
    }

    /**
     * Re-evaluate without an orientation change — call this when the
     * layout mode toggles (widgets ↔ single_panel/kiosk), since the
     * portrait swap only applies in widgets mode.
     */
    fun reapply() {
        apply(orientationController.current())
    }

    private fun apply(orientation: OrientationController.Orientation) {
        val mode = displayPrefs.layoutMode
        // The widgets/single-panel dashboard only loads when the user is
        // signed into Dashie. A pure-kiosk (HA-only) user has
        // isLinked=false, forceKioskMode=false, AND the default
        // layoutMode="widgets" (no layout picker access) — so isLinked is
        // the only reliable signal that excludes them (device logs
        // 2026-05-29). forceKioskMode kept as a guard for signed-in
        // users who toggled into kiosk.
        val account = com.dashieapp.Dashie.halite.preferences.AccountPreferences(activity)
        val isWidgetsDashboard = account.showsDashboard && mode == "widgets"
        val gateOk = gateAllowsChrome()
        // Authoritative "bottom bar is the active chrome" signal for the sidebar
        // controller (so it stays hidden + yields d-pad to the bottom bar).
        onPortraitWidgetsActiveChanged(
            gateOk && isWidgetsDashboard &&
                orientation == OrientationController.Orientation.PORTRAIT
        )
        if (!gateOk) {
            // Loading / edit mode / kiosk-off — gate has already set both
            // surfaces to INVISIBLE/GONE. Don't override; just keep the
            // bottom bar hidden so we don't fight the gate.
            Log.d(TAG, "🧭 gate hides chrome — keeping bottom bar hidden")
            bottomBar.setVisible(false)
            return
        }
        if (!isWidgetsDashboard) {
            // Kiosk (HA shell), single-panel, or not-signed-in — the
            // sidebar is owned by other paths. Hide the bottom bar and
            // restore the side sidebar (a prior portrait-widgets pass
            // may have hidden it, or the user toggled into kiosk while
            // portrait, so we can't assume it's already visible).
            Log.d(TAG, "🧭 reapply mode=$mode linked=${account.isLinked} kiosk=${account.forceKioskMode} — bottom bar off, restoring sidebar")
            bottomBar.setVisible(false)
            onSidebarRestoreNeeded()
            return
        }
        when (orientation) {
            OrientationController.Orientation.PORTRAIT -> {
                Log.i(TAG, "🧭 PORTRAIT → bottom bar, sidebar hidden")
                sidebarStripRoot?.visibility = View.GONE
                // Also hide the colored "land" backdrop View painted
                // behind the landscape sidebar — it's a sibling View
                // whose visibility is driven by the sidebar's pinned
                // state, not by the strip root, so it would otherwise
                // keep painting a colored strip on the left edge of
                // the screen in portrait.
                sidebarBackdropStrip?.visibility = View.GONE
                bottomBar.setVisible(true)
                bottomBar.syncFromSidebar()
            }
            OrientationController.Orientation.LANDSCAPE -> {
                Log.i(TAG, "🧭 LANDSCAPE → sidebar, bottom bar hidden")
                bottomBar.setVisible(false)
                // Defer to the sidebar controller's own visibility logic
                // (it may decide GONE if account isn't linked, etc.).
                // updateRootBackground re-shows the backdrop strip if
                // the sidebar is pinned, so no explicit unhide needed.
                onSidebarRestoreNeeded()
            }
        }
    }

    companion object {
        private const val TAG = "PortraitLayoutManager"
    }
}

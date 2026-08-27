package com.dashieapp.Dashie.halite.widgets

// BRIDGE-DYNAMIC: window.$jsCoordinatorName — per-widget JS d-pad coordinator protocol (full-mode layout canvas); global name is widget-supplied

import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * Base class for native Kotlin widgets (`NativeDpadWidget`) that defer
 * all menu-related d-pad input to a parent-level JS coordinator
 * (e.g. `window.photosMenu`, `window.rotatorMenu`).
 *
 * Subclasses provide:
 *   - `widgetId` — the dashboard widget id JS uses to identify this widget
 *   - `jsCoordinatorName` — the global JS object name (e.g. "photosMenu")
 *   - `widgetContainer` — the View whose bounds we pass to JS for overlay
 *     positioning. Subclass owns its lifecycle.
 *   - `webViewProvider` — for `evaluateJavascript` calls back to JS.
 *
 * d-pad lifecycle:
 *   onActivate()  → window.<coord>.activateAt(widgetId, x, y, w, h)
 *                   (JS shows the hint via the generic widget overlay bridge)
 *   onNavKey(k)   → window.<coord>.handleDirection|handleSelect|handleEscape(...)
 *                   Always consumes — JS owns the state machine. Returns true.
 *                   Subclass can override `onNativeNavKey` to handle
 *                   widget-internal keys before JS dispatch (e.g. fullscreen
 *                   explorer keys when the explorer is open).
 *   onDeactivate() → window.<coord>.deactivate()
 *
 * This is the "native widget glue" half of the unified TV menu pattern;
 * the matching JS half lives in coordinator files like photos-menu.js
 * and rotator-menu.js.
 */
abstract class JsCoordinatedDpadWidget(
    protected val webViewProvider: () -> WebView?
) : NativeDpadWidget {
    companion object {
        private const val TAG = "JsCoordDpadWidget"
    }

    /** Widget id for the JS coordinator (e.g. "photos"). */
    protected abstract val widgetId: String

    /** Global JS coordinator object name (e.g. "photosMenu"). */
    protected abstract val jsCoordinatorName: String

    /** Container whose screen bounds we pass to JS for overlay positioning. */
    protected abstract val widgetContainer: FrameLayout?

    /**
     * Hook for widget-internal keys that should fire before forwarding to
     * the JS coordinator (e.g. dismissing a fullscreen explorer overlay
     * that the widget itself owns).
     *
     * Return non-null to short-circuit: true = event consumed, false =
     * yield to NativeWidgetFocusManager (deactivate). Return null to
     * forward to JS (default).
     */
    protected open fun onNativeNavKey(action: String): Boolean? = null

    final override fun onActivate() {
        val container = widgetContainer
        if (container == null) {
            Log.w(TAG, "[$jsCoordinatorName] onActivate: container is null")
            return
        }
        val webView = webViewProvider()
        if (webView == null) {
            Log.w(TAG, "[$jsCoordinatorName] onActivate: webView is null")
            return
        }
        val loc = IntArray(2)
        container.getLocationInWindow(loc)
        val x = loc[0]; val y = loc[1]
        val w = container.width; val h = container.height
        val js = "window.$jsCoordinatorName && window.$jsCoordinatorName.activateAt && " +
            "window.$jsCoordinatorName.activateAt('$widgetId', $x, $y, $w, $h)"
        Log.i(TAG, "[$jsCoordinatorName] onActivate: dispatching to JS, bounds=[$x,$y,${w}x$h]")
        webView.evaluateJavascript(js, null)
    }

    final override fun onDeactivate() {
        val webView = webViewProvider()
        if (webView != null) {
            val js = "window.$jsCoordinatorName && window.$jsCoordinatorName.deactivate && " +
                "window.$jsCoordinatorName.deactivate()"
            webView.evaluateJavascript(js, null)
        }
        Log.i(TAG, "[$jsCoordinatorName] onDeactivate")
    }

    final override fun onNavKey(action: String): Boolean {
        // Subclass may handle widget-internal keys first (e.g. fullscreen).
        val handled = onNativeNavKey(action)
        if (handled != null) return handled

        val webView = webViewProvider() ?: return false
        val js = when (action) {
            "left", "right", "up", "down" ->
                "window.$jsCoordinatorName && window.$jsCoordinatorName.handleDirection && " +
                    "window.$jsCoordinatorName.handleDirection('$action')"
            "enter" ->
                "window.$jsCoordinatorName && window.$jsCoordinatorName.handleSelect && " +
                    "window.$jsCoordinatorName.handleSelect()"
            "escape" ->
                "window.$jsCoordinatorName && window.$jsCoordinatorName.handleEscape && " +
                    "window.$jsCoordinatorName.handleEscape()"
            else -> return true
        }
        webView.evaluateJavascript(js, null)
        // Always consume — JS owns state. Edge case: when JS is already
        // idle and gets escape, it returns false synchronously but we
        // can't observe that across the bridge. In practice JS always
        // exits to idle then a second escape would normally yield, but
        // we'd need a callback to release focus. Punt for now; revisit
        // if it becomes a UX issue.
        return true
    }
}

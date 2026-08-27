package com.dashieapp.Dashie.halite.widgets

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.webkit.WebView

/**
 * A native Kotlin widget that supports d-pad activation (PhotoWidget is the
 * first; WeatherWidget etc. can implement later).
 *
 * Lifecycle:
 *   - onActivate() — user pressed Enter on the widget's layout-canvas slot.
 *     Widget should show its hint overlay + prepare for d-pad input.
 *   - onNavKey(action) — a d-pad key pressed while widget is active. Return
 *     true to stay active (key consumed); return false to yield (manager
 *     deactivates and focus returns to the JS layout canvas). action is one
 *     of "up"|"down"|"left"|"right"|"enter"|"escape".
 *   - onDeactivate() — manager is ending the activation (either yield or
 *     external stop). Widget should hide hint and clean up any widget-only
 *     state.
 */
interface NativeDpadWidget {
    fun onActivate()
    fun onDeactivate()
    fun onNavKey(action: String): Boolean
    /** Optional: direction ('up'|'down'|'left'|'right') to move JS focus
     *  after yield, or null to stay on the same slot. */
    fun exitDirection(): String? = null
}

/**
 * Singleton gate that owns "which native widget (if any) is currently
 * activated". When active, MainInputHandler routes d-pad keys through
 * this manager instead of forwarding them to JS.
 *
 * JS (LayoutCanvasInputHandler) activates a native widget by calling the
 * DashieNative.enterNativeWidget(widgetId) bridge. When activation ends,
 * the manager notifies JS via window.LayoutCanvasInputHandler_onNativeWidgetExited
 * so the canvas can restore grid navigation.
 */
class NativeWidgetFocusManager(
    private val activity: Activity,
    private val webViewProvider: () -> WebView?
) {
    companion object {
        private const val TAG = "NativeWidgetFocus"
        /** Auto-deactivate the active widget after this much idle time
         *  with no d-pad input. Mirrors the JS layout-canvas idle timeout
         *  so a user who walks away doesn't leave the dashboard stuck on
         *  a focused widget. */
        private const val IDLE_TIMEOUT_MS = 45_000L
    }

    private var activeWidget: NativeDpadWidget? = null
    private var activeWidgetId: String? = null

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleTimeoutRunnable = Runnable {
        Log.i(TAG, "Idle timeout (${IDLE_TIMEOUT_MS / 1000}s) — auto-deactivating $activeWidgetId")
        deactivate(notifyJs = true)
    }

    fun activate(widgetId: String, widget: NativeDpadWidget) {
        if (activeWidget === widget) {
            // Already active — just kick the idle timer.
            resetIdleTimer()
            return
        }
        if (activeWidget != null) deactivate(notifyJs = false)
        activeWidget = widget
        activeWidgetId = widgetId
        try { widget.onActivate() } catch (e: Exception) { Log.e(TAG, "onActivate threw", e) }
        Log.i(TAG, "Activated: $widgetId")
        resetIdleTimer()
    }

    /** Restart the idle timeout — called on every d-pad key. */
    private fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleTimeoutRunnable)
        idleHandler.postDelayed(idleTimeoutRunnable, IDLE_TIMEOUT_MS)
        Log.i(TAG, "Idle timer (re)scheduled for ${IDLE_TIMEOUT_MS / 1000}s")
    }

    private fun cancelIdleTimer() {
        idleHandler.removeCallbacks(idleTimeoutRunnable)
        Log.i(TAG, "Idle timer cancelled")
    }

    fun hasActiveWidget(): Boolean = activeWidget != null

    /**
     * Called from MainInputHandler.dispatchKeyEvent when a native widget is
     * active. Returns true if the manager handled the key (MainActivity
     * should NOT forward to JS); false to pass through normally.
     */
    fun handleDpadKey(keyCode: Int): Boolean {
        val widget = activeWidget ?: return false
        val action = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "enter"
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> "escape"
            else -> return false  // non-nav key — let it pass
        }
        // Any input resets the idle timer.
        resetIdleTimer()
        val consumed = try {
            widget.onNavKey(action)
        } catch (e: Exception) {
            Log.e(TAG, "onNavKey threw for action=$action", e)
            false
        }
        if (!consumed) {
            // Widget yields. Grab exit direction before deactivation clears state.
            val direction = try { widget.exitDirection() } catch (e: Exception) { null }
            deactivate(notifyJs = true, direction = direction)
        }
        return true
    }

    /**
     * Deactivate the currently active widget (if any).
     * @param notifyJs if true, invokes the JS callback so LayoutCanvasInputHandler
     *                 can restore grid navigation. Set to false when activating
     *                 a new widget directly (avoids a redundant round trip).
     * @param direction optional direction to move JS focus to after yield.
     */
    fun deactivate(notifyJs: Boolean = true, direction: String? = null) {
        val widget = activeWidget ?: return
        val widgetId = activeWidgetId
        cancelIdleTimer()
        try { widget.onDeactivate() } catch (e: Exception) { Log.e(TAG, "onDeactivate threw", e) }
        activeWidget = null
        activeWidgetId = null
        Log.i(TAG, "Deactivated: $widgetId, notifyJs=$notifyJs, direction=$direction")

        if (notifyJs) {
            val dirArg = direction?.let { "'${it.replace("'", "")}'" } ?: "null"
            webViewProvider()?.post {
                webViewProvider()?.evaluateJavascript(
                    // BUNDLE-EXEMPT: LayoutCanvasInputHandler_onNativeWidgetExited — layout canvas is the full-mode dashboard; kiosk shows HA
                    "if (typeof window.LayoutCanvasInputHandler_onNativeWidgetExited === 'function') " +
                            "window.LayoutCanvasInputHandler_onNativeWidgetExited($dirArg);",
                    null
                )
            }
        }
    }
}

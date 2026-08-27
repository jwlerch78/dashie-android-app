package com.dashieapp.Dashie.webview

import android.util.Log
import android.view.View
import android.webkit.WebView
import com.dashieapp.Dashie.controlcenter.ControlCenterOverlay
import com.dashieapp.Dashie.sidebar.NativeSidebarController

/**
 * Refcounted "go fullscreen in the WebView" coordinator.
 *
 * JS callers (auth flows, voice overlay, photo widget fullscreen, modals)
 * call [enter] with a string token before showing a UI that needs the
 * native sidebar / control-center out of the way, and [exit] when the UI
 * dismisses. The first [enter] hides; the last [exit] restores. Held
 * tokens are tracked by name so two callers asking for fullscreen at the
 * same time don't step on each other.
 *
 * Critically, [clearAll] is invoked by the WebView's onPageStarted hook —
 * if JS reloads (background refresh, navigation, crash) without ever
 * calling [exit], this is the safety net that stops the sidebar from
 * staying hidden forever. That was the failure mode behind the
 * post-MS-auth modal being uninteractable.
 *
 * The screensaver is intentionally NOT a participant: it sits above the
 * sidebar via z-order, so its visibility is independent. Only this
 * manager's tokens (sidebar/control-center hide state) are reset on
 * page reload.
 */
class FullscreenModeManager(
    private val sidebarProvider: () -> NativeSidebarController?,
    private val controlCenterProvider: () -> ControlCenterOverlay?,
    private val webViewProvider: () -> WebView?,
    // Hides native widgets (photos, weather, etc.) on a page-unload
    // signal. Pulled out as a provider lambda because the visibility
    // gate lives on haliteRegistry, which isn't available at
    // MainActivity field-init time when this manager is constructed.
    private val widgetGateResetProvider: () -> Unit,
    // Toggles a separate "suppressed for fullscreen" flag on the
    // visibility gate. Used on enter/exit so JS modals (auth QR,
    // voice overlay) hide widgets without flipping the dashboardReady
    // state — exiting restores immediately, no need to wait for a
    // fresh onDashboardReady signal.
    private val widgetGateFullscreenSuppressProvider: (Boolean) -> Unit,
    private val runOnUiThread: (Runnable) -> Unit
) : DashieJSBridge.FullscreenCallbacks {
    companion object { private const val TAG = "FullscreenMode" }

    // Plain set is fine — the same token entering twice is a no-op (idempotent
    // by design; protects against double-call from JS retry logic).
    private val tokens = mutableSetOf<String>()

    /** Fires (on UI thread) when a specific fullscreen token is exited.
     *  Lets the host listen for e.g. "welcome-wizard" exit to mark
     *  onboarding complete. Pass null to remove. */
    var onTokenExited: ((token: String) -> Unit)? = null

    override fun enterFullscreen(token: String) = enter(token)
    override fun exitFullscreen(token: String) = exit(token)

    /**
     * JS-driven page-unload signal. Fires from a `pagehide` listener,
     * earlier than WebViewClient.onPageStarted, so native UI responds
     * instantly to reloads:
     *   - Reset the native widget visibility gate (hide photos / weather
     *     etc. before they linger on top of the loading screen).
     *   - Drop any held fullscreen tokens (same safety net as clearAll).
     *
     * Backdrop strip is deliberately NOT neutralized here — same reasoning
     * as the Kotlin onPageStarted path: stealth reloads where the page
     * never re-runs JS bootstrap (memory recovery, same-origin nav after
     * sleep/wake) leave the strip stranded in neutral. Strip keeps its
     * themed color across reloads, parallel to the sidebar panel.
     *
     * Kept idempotent — Kotlin's onPageStarted hook still fires the same
     * actions as a fallback for cases JS can't notify (renderer crash,
     * native-triggered webView.reload(), etc.).
     */
    override fun notifyPageUnloading() {
        runOnUiThread {
            val sidebar = sidebarProvider()
            Log.i(TAG, "notifyPageUnloading() — sidebarPresent=${sidebar != null} tokensHeld=${tokens.size}")
            try {
                widgetGateResetProvider()
                Log.i(TAG, "  → widgetGateResetProvider() called")
            } catch (e: Throwable) {
                Log.e(TAG, "  ✗ widgetGateResetProvider() threw", e)
            }
            if (tokens.isNotEmpty()) {
                tokens.clear()
                applyRestore()
            }
        }
    }

    fun enter(token: String) {
        runOnUiThread {
            val wasEmpty = tokens.isEmpty()
            if (tokens.add(token)) {
                Log.i(TAG, "enter('$token') — held=${tokens.joinToString()}")
            }
            if (wasEmpty && tokens.isNotEmpty()) {
                applyHide()
            }
        }
    }

    fun exit(token: String) {
        runOnUiThread {
            val removed = tokens.remove(token)
            if (removed) {
                Log.i(TAG, "exit('$token') — held=${tokens.joinToString()}")
                onTokenExited?.invoke(token)
            }
            if (removed && tokens.isEmpty()) {
                applyRestore()
            }
        }
    }

    /**
     * Drop every held token and restore overlays. Called from
     * WebViewClient.onPageStarted so a JS reload never leaves the sidebar
     * hidden forever. Idempotent — no-op if nothing was held.
     */
    fun clearAll() {
        runOnUiThread {
            if (tokens.isEmpty()) return@runOnUiThread
            Log.i(TAG, "clearAll() — releasing ${tokens.size} held token(s): ${tokens.joinToString()}")
            tokens.clear()
            applyRestore()
        }
    }

    private fun applyHide() {
        // Fully hide the sidebar (visibility=GONE + notify JS to drop the
        // layout offset + reset WebView leftMargin in kiosk modes). The
        // earlier setVisuallySuppressed-only path left WebView margin in
        // place, which let the welcome wizard render shifted right with
        // an invisible-but-still-tappable sidebar gap on the left.
        sidebarProvider()?.hide(false)
        // Also flip the suppressed flag so the sidebar's auto-show paths
        // (page-reload re-init) don't pop it back during the wizard.
        sidebarProvider()?.setVisuallySuppressed(true)
        // Native widgets (photos, weather, etc) — hide via the gate's
        // separate fullscreen-suppression flag so exit can restore them
        // without needing a fresh onDashboardReady.
        widgetGateFullscreenSuppressProvider(true)
        // Control center is user-triggered (no auto-restore needed) — just
        // dismiss it if it happens to be open behind the modal.
        controlCenterProvider()?.hide()
    }

    private fun applyRestore() {
        sidebarProvider()?.setVisuallySuppressed(false)
        sidebarProvider()?.show()
        widgetGateFullscreenSuppressProvider(false)
        // Re-grant focus to the WebView so JS modals can claim d-pad input.
        // This was the second half of the post-auth-modal bug: even with the
        // sidebar hidden, the modal couldn't receive Enter presses because
        // focus had landed elsewhere during the dismiss.
        webViewProvider()?.let { wv ->
            wv.requestFocus(View.FOCUS_DOWN)
        }
    }
}

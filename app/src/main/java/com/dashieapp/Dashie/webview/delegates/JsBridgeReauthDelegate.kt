package com.dashieapp.Dashie.webview.delegates

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * JS bridge delegate for the calendar account reauthentication overlay.
 *
 * The JS layer (widget-data-manager + layout-canvas) reports two pieces
 * of state we need on the Kotlin side:
 *  - `setReauthPillBounds(slotId, widgetType, x, y, w, h)` — bounds of a
 *    visible calendar/agenda widget. Called whenever the layout iterates
 *    or re-positions; called once per visible calendar/agenda slot.
 *  - `removeReauthPill(slotId)` — slot no longer hosts a calendar/agenda
 *    widget (e.g. rotator advanced to a different widget type). Drop the
 *    pill so it doesn't linger over the wrong content.
 *  - `setReauthPillState(count, soleEmail)` — global count of accounts
 *    whose refresh tokens have been terminally revoked (auth_invalid in
 *    Supabase). soleEmail is non-empty only when count===1, so the
 *    Kotlin AlertDialog body can name the affected account.
 *
 * All callbacks fire on whichever thread the WebView dispatches the JS
 * bridge call (typically a worker). Listeners must marshal to the UI
 * thread before touching views — see CalendarReauthOverlayManager.
 */
class JsBridgeReauthDelegate {

    companion object {
        private const val TAG = "JsBridgeReauth"
    }

    var onReauthPillBounds: ((slotId: String, widgetType: String, x: Int, y: Int, w: Int, h: Int) -> Unit)? = null
    var onReauthPillRemoved: ((slotId: String) -> Unit)? = null

    /** Primary state listener — owned by the overlay manager wiring. */
    var onReauthPillState: ((count: Int, soleEmail: String) -> Unit)? = null

    // Multiple consumers may want the auth_invalid count: the overlay
    // manager (for the pill), the Control Center state provider (for the
    // amber calendar card), etc. The primary `onReauthPillState` slot
    // covers one subscriber; additional listeners register here.
    private val additionalStateListeners = mutableListOf<(Int, String) -> Unit>()
    fun addStateListener(listener: (Int, String) -> Unit) {
        additionalStateListeners.add(listener)
    }

    @JavascriptInterface
    fun setReauthPillBounds(slotId: String, widgetType: String, x: Int, y: Int, w: Int, h: Int) {
        Log.d(TAG, "setReauthPillBounds slot=$slotId type=$widgetType bounds=${x},${y},${w},${h}")
        onReauthPillBounds?.invoke(slotId, widgetType, x, y, w, h)
    }

    @JavascriptInterface
    fun removeReauthPill(slotId: String) {
        Log.d(TAG, "removeReauthPill slot=$slotId")
        onReauthPillRemoved?.invoke(slotId)
    }

    @JavascriptInterface
    fun setReauthPillState(count: Int, soleEmail: String) {
        Log.d(TAG, "setReauthPillState count=$count soleEmail=${if (soleEmail.isEmpty()) "<none>" else soleEmail}")
        onReauthPillState?.invoke(count, soleEmail)
        additionalStateListeners.forEach { it(count, soleEmail) }
    }
}

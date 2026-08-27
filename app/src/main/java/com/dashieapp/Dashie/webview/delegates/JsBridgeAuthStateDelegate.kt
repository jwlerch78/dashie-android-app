package com.dashieapp.Dashie.webview.delegates

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * JS bridge delegate for native UI mode signaling.
 *
 * JS reports the current UI mode via [setUiMode] so the visibility gate
 * can show / hide all native surfaces in lockstep:
 *   - "off"   — login / welcome / sign-up screens (everything hidden)
 *   - "kiosk" — HA-only dashboard (kiosk-relevant surfaces visible)
 *   - "full"  — authenticated Dashie dashboard (everything visible)
 *
 * Account-removal overlays (rotator settings menu tied to a calendar
 * account, future "account disabled" badges) are dismissed via the
 * separate [dismissContextualOverlays] one-shot.
 *
 * Auth-flow suppression for OAuth modals stays as separate calls so
 * the visibility gate's modal-suppression token system handles them
 * orthogonally to mode.
 */
class JsBridgeAuthStateDelegate {

    companion object {
        private const val TAG = "JsBridgeUiMode"
    }

    /** Receives the raw mode string ("off"/"kiosk"/"full"). */
    var onUiModeChanged: ((String) -> Unit)? = null
    var onDismissContextualOverlays: ((provider: String, email: String) -> Unit)? = null
    var onAuthFlowStarted: (() -> Unit)? = null
    var onAuthFlowEnded: (() -> Unit)? = null
    /** JS modal shown / dismissed — Kotlin dims registered native widgets
     *  (photos, rotator, etc) to match the modal backdrop. Ref-counted
     *  inside NativeWidgetVisibilityGate so stacked modals don't undim
     *  too early. Called from DashieModal.show()/hide() and any other JS
     *  modal that wants its backdrop to "cover" native widgets above the
     *  WebView in z-order. */
    var onSetNativeWidgetsDimmed: ((Boolean) -> Unit)? = null
    /** Fired when JS has just persisted a fresh Supabase JWT to localStorage.
     *  Wiring re-runs SupabaseTokenExtractor.extractAndCache so background
     *  Kotlin services (photo widget, edge calls) see the new token without
     *  waiting for the next page-load extraction race to resolve. */
    var onSupabaseJwtSaved: (() -> Unit)? = null

    @JavascriptInterface
    fun setUiMode(mode: String) {
        Log.i(TAG, "setUiMode: $mode")
        com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark("js->kotlin setUiMode($mode)")
        onUiModeChanged?.invoke(mode)
    }

    /** Called when a calendar account is removed. Kotlin dismisses any open
     *  contextual overlay tied to that account (rotator settings menu, etc).
     *  Provider + email allow per-account filtering for future overlays;
     *  current implementation is conservative (dismiss any open contextual
     *  overlay regardless of binding). */
    @JavascriptInterface
    fun dismissContextualOverlays(provider: String, email: String) {
        Log.i(TAG, "dismissContextualOverlays: provider=$provider email=$email")
        onDismissContextualOverlays?.invoke(provider, email)
    }

    @JavascriptInterface
    fun notifyAuthFlowStarted() {
        Log.i(TAG, "notifyAuthFlowStarted")
        onAuthFlowStarted?.invoke()
    }

    @JavascriptInterface
    fun notifyAuthFlowEnded() {
        Log.i(TAG, "notifyAuthFlowEnded")
        onAuthFlowEnded?.invoke()
    }

    /** Called from DashieModal.show()/hide() (and any other JS modal that
     *  paints a backdrop) so Kotlin can apply matching alpha to native
     *  widgets that sit above the WebView in z-order. Without this the
     *  WebView's CSS backdrop dims the dashboard but leaves photos /
     *  rotator widgets at full brightness, breaking the modal focus cue. */
    @JavascriptInterface
    fun setNativeWidgetsDimmed(dimmed: Boolean) {
        Log.i(TAG, "setNativeWidgetsDimmed($dimmed)")
        onSetNativeWidgetsDimmed?.invoke(dimmed)
    }

    /** JS calls this from EdgeClient._saveJWTToStorage right after writing
     *  the Supabase JWT to localStorage. Without this, background Kotlin
     *  services that need the JWT (e.g. SupabasePhotoSource) only see it
     *  on the next dashboard onPageFinished — which often fires BEFORE
     *  edge-client persists the token, so the first session shows
     *  "Not signed in" until the next reload. */
    @JavascriptInterface
    fun notifySupabaseJwtSaved() {
        Log.i(TAG, "notifySupabaseJwtSaved")
        onSupabaseJwtSaved?.invoke()
    }
}

package com.dashieapp.Dashie.halite.widgets

import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View

/**
 * Central gate for native Kotlin UI visibility.
 *
 * One state field: [UiMode]. JS owns it and reports the current mode via
 * [setUiMode]:
 *   - OFF    — login / welcome / sign-up screens. All native UI hidden.
 *   - KIOSK  — HA-only dashboard, no Dashie auth. Show kiosk-relevant
 *              surfaces (sidebar, music, video feeds, voice, etc.).
 *   - FULL   — authenticated Dashie dashboard. Show everything.
 *
 * One modifier: [fullscreenSuppressed], driven by FullscreenModeManager for
 * orthogonal modal flows (auth QR, voice overlay). Hides everything
 * regardless of mode.
 *
 * Each widget registers with a [WidgetKind] declaring the modes it appears
 * in. Visibility is recomputed whenever mode or fullscreenSuppressed change.
 *
 * Default mode is OFF — nothing shows until JS explicitly sets a mode.
 * That's intentional: better to start hidden and have JS opt in than to
 * flicker visible-then-hidden during bootstrap.
 */
class NativeWidgetVisibilityGate {

    enum class UiMode { OFF, KIOSK, FULL }

    enum class WidgetKind {
        /** Visible only in FULL mode. Dashie widgets (rotator, weather,
         *  photos), settings overlays, focus rings — anything that
         *  requires the authenticated Dashie experience. Gate flips
         *  the view's visibility based on mode. */
        FULL_ONLY,

        /** Visible in FULL or KIOSK mode. Sidebar, music player, video
         *  feeds, voice indicator — surfaces that operate in HA-only
         *  kiosk mode as well as the authenticated dashboard. Gate flips
         *  visibility based on mode. */
        KIOSK_OR_FULL,

        /** Event-triggered overlays only valid in FULL mode (rotator
         *  settings menu, photos menu, etc.). Gate force-hides them with
         *  GONE on non-FULL transitions but does NOT force them visible
         *  on FULL — JS show() / hide() controls the visible state.
         *  Without this, on page reload the gate would either leave them
         *  ghosting (no registration) or force them visible after the
         *  reload completes (FULL_ONLY semantics). */
        OVERLAY_FULL_ONLY,

        /** Event-triggered overlays valid in FULL or KIOSK mode (HA
         *  disconnected indicator, music/video/voice cards, etc.). Gate
         *  force-hides them with GONE on transitions to OFF but doesn't
         *  force them visible on FULL/KIOSK — the surface's own state
         *  (HA disconnected, music playing, etc.) controls visibility. */
        OVERLAY_KIOSK_OR_FULL
    }

    companion object {
        private const val TAG = "WidgetVisGate"

        /** Dim scrim — a black overlay drawn on top of each registered
         *  widget when isDimmed=true. Alpha 0xC0 = 192/255 ≈ 0.75 black,
         *  matching the CSS `.dashie-modal-overlay { background:
         *  rgba(0,0,0,0.75) }` backdrop opacity. Using View.foreground
         *  (rather than view.alpha) makes the widget read as DARKENED
         *  rather than TRANSLUCENT. */
        private const val DIM_SCRIM_COLOR = 0xC0000000.toInt() // ARGB 0xC0=192/255≈0.75 black

        // Note: an earlier iteration applied a RenderEffect blur on each
        // dimmed widget to match the CSS `backdrop-filter: blur(4px)`.
        // Removed because the blur created two visible artifacts:
        //   (1) light-grey 1px divider lines inside the weather widget
        //       read as solid black after blur+scrim, looking like a theme
        //       switch to dark mode
        //   (2) certain DashieModal shows appeared dim because the blurred
        //       hardware-layer compositing interacted badly with the WebView's
        //       own compositor when a backdrop-filter was active on .dashie-
        //       modal-overlay (Chromium 110+ behavior)
        // The CSS backdrop already blurs the dashboard content behind the
        // modal — blurring the Kotlin widgets too is double-up that doesn't
        // add visual fidelity. Scrim-only matches the visible state of a
        // CSS-dimmed area closely enough.

        fun parseMode(s: String?): UiMode = when (s?.lowercase()) {
            "full" -> UiMode.FULL
            "kiosk" -> UiMode.KIOSK
            else -> UiMode.OFF
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val widgets = mutableMapOf<View, WidgetKind>()
    // Views that opted OUT of the JS-modal dim scrim. Used by full-screen
    // container Views (e.g. sidebar stripRoot — registered for visibility
    // management but its full-screen bounds would cause the dim scrim to
    // cover the entire WebView including any JS modal sitting on top).
    private val dimOptOutViews = mutableSetOf<View>()

    private var mode: UiMode = UiMode.OFF
    private var fullscreenSuppressed = false

    // Dim state — orthogonal to mode/suppression. When set, all visible
    // registered widgets render at [DIM_ALPHA] so a JS modal backdrop reads
    // as "covering everything" even though the backdrop is just a WebView
    // overlay that can't physically dim native Kotlin widgets above it
    // (photos widget, rotator widget) in the z-order. Use case: DashieModal
    // calls dim(true) on show, dim(false) on hide. Ref-counted so stacked
    // modals don't undim too early.
    private var dimRefCount = 0
    private val isDimmed: Boolean
        get() = dimRefCount > 0

    /** Single callback fired when mode or fullscreenSuppressed changes.
     *  Used by surfaces that have side-effects beyond simple View.visibility
     *  (e.g. sidebar's WebView leftMargin, popout dismissal). The gate
     *  handles registered Views' visibility automatically; this callback
     *  is for everything else. */
    var onVisibilityChanged: (() -> Unit)? = null


    /** Register a native UI surface. Sets initial visibility per current
     *  state. MUST be called from the main thread.
     *
     *  @param dimmable when false, the JS-modal dim scrim is NOT applied to
     *      this view. Use for full-screen container Views (e.g. sidebar
     *      stripRoot) where applying foreground to a match_parent ×
     *      match_parent View would cover the entire WebView and dim any
     *      JS modal on top of it. The actual visible content inside such
     *      a container should call registerForDim() separately if it
     *      needs the scrim. */
    fun register(container: View, kind: WidgetKind = WidgetKind.FULL_ONLY, dimmable: Boolean = true) {
        widgets[container] = kind
        if (!dimmable) dimOptOutViews.add(container) else dimOptOutViews.remove(container)
        applyVisibilityFor(container, kind)
        Log.d(TAG, "register kind=$kind dimmable=$dimmable mode=$mode suppressed=$fullscreenSuppressed total=${widgets.size}")
    }

    /** Unregister a surface (e.g. when an overlay is destroyed). */
    fun unregister(container: View) {
        widgets.remove(container)
        dimOptOutViews.remove(container)
        Log.d(TAG, "unregister total=${widgets.size}")
    }

    /** Called from JS bridge thread — posts to main. */
    fun setUiMode(newMode: UiMode) {
        mainHandler.post {
            if (mode == newMode) return@post
            val prev = mode
            mode = newMode
            Log.i(TAG, "UI mode $prev -> $newMode")
            applyVisibility()
        }
    }

    /** Called when a top-level WebView navigation is starting (main-frame
     *  onPageStarted). Drops mode to OFF so all registered surfaces hide
     *  for the duration of the page load. The next setUiMode call (from JS
     *  bootstrap or Kotlin's onKioskShellLoaded) restores the correct mode.
     *  ⚠️ EXCEPT in KIOSK, which is preserved — nothing re-asserts it, so the
     *  reset would be permanent. See the comment in the body.
     *
     *  We anchor hide-on-reload to WebView main-frame onPageStarted rather
     *  than the JS-driven notifyPageUnloading because main-frame
     *  onPageStarted fires exactly once per top-level navigation, while
     *  notifyPageUnloading can fire multiple times per session (iframe
     *  events, dashboard bootstrap signals) and would cause flicker. */
    fun onTopLevelNavigationStarted() {
        mainHandler.post {
            // Also clear fullscreenSuppressed — a top-level navigation is a
            // hard reset; any modal that was up before the nav can't survive
            // it, so stale suppression flags must be cleared. Without this,
            // flows that set fullscreenSuppressed via direct
            // setSuppressedForFullscreen (notifyAuthFlowStarted, etc) and
            // then redirect to an external OAuth provider before
            // notifyAuthFlowEnded can fire would leave the gate stuck
            // suppressed across the redirect-back. shouldShowFull() returns
            // false even when mode flips to FULL after bootstrap → all
            // native UI stays hidden post-reauth.
            // 🔴 KIOSK is the one mode that nothing will restore, so it must NOT
            // be reset here. Dropping to OFF is safe only because the next
            // setUiMode re-asserts the right mode — true for the Dashie web app
            // and the login screen, whose JS calls setUiMode on bootstrap. Kiosk
            // is deliberately different: Kotlin owns the decision because "the
            // kiosk JS bundle doesn't talk to the gate" (MainActivity's initial
            // -mode comment), and in custom-URL mode the page is arbitrary
            // third-party HTML that will never call setUiMode at all.
            //
            // Resetting there strands the gate at OFF permanently: the sidebar
            // stays gateSuppressed, so the user has NO route to the Control
            // Center and cannot undo it from the UI — only adb or clearing app
            // data recovers it. Found on a Fire tablet set up as a non-HA
            // dashboard (2026-08-18): "Custom URL mode — loading directly" →
            // "mode KIOSK -> OFF" → "showOverlay ignored — gateSuppressed".
            //
            // The transient flags below ARE genuinely per-navigation and are
            // still cleared in every mode.
            val keepMode = mode == UiMode.KIOSK
            val needsApply = mode != UiMode.OFF || fullscreenSuppressed || isDimmed
            val prev = mode
            if (!keepMode) mode = UiMode.OFF
            fullscreenSuppressed = false
            // Top-level nav is a hard reset for the dim ref count too — any
            // JS modal up before the nav can't survive it, so its pending
            // dim(false) won't fire and we'd be stuck dimmed across the
            // reload. Clear here.
            clearDimState()
            if (needsApply) {
                Log.i(TAG, if (keepMode)
                    "Top-level navigation — mode KEPT at $prev (kiosk: no JS will re-assert), fullscreenSuppressed cleared, dim cleared"
                else
                    "Top-level navigation — mode $prev -> OFF, fullscreenSuppressed cleared, dim cleared (hide pending next setUiMode)")
                applyVisibility()
            }
        }
    }

    /** Legacy path called from FullscreenModeManager / JS resetNativeWidgetGate.
     *  No-op for visibility now — hide-on-reload is anchored to
     *  [onTopLevelNavigationStarted] instead. Kept so existing callers compile. */
    fun reset() {
        // Intentionally empty — see onTopLevelNavigationStarted.
    }

    /** Hide / restore native UI for fullscreen modals (auth QR, voice etc).
     *  Orthogonal to mode — exiting a modal restores per the current mode. */
    fun setSuppressedForFullscreen(suppressed: Boolean) {
        mainHandler.post {
            if (fullscreenSuppressed == suppressed) return@post
            fullscreenSuppressed = suppressed
            Log.i(TAG, "setSuppressedForFullscreen($suppressed)")
            applyVisibility()
        }
    }

    /** Dim / un-dim native widgets to read as "behind a modal backdrop."
     *  Ref-counted so stacked JS modals don't undim until ALL have closed.
     *  Callers must pair every `setDimmed(true)` with a `setDimmed(false)`.
     *
     *  Why ref-counting: modal frameworks (DashieModal, etc) can stack —
     *  e.g. a confirm() opens, user picks an option that opens an error
     *  modal. Without ref-counting the inner modal's hide() would undim
     *  the widgets even though the outer modal is still up. */
    fun setDimmed(dim: Boolean) {
        mainHandler.post {
            val previouslyDimmed = isDimmed
            if (dim) {
                dimRefCount++
            } else if (dimRefCount > 0) {
                dimRefCount--
            }
            val nowDimmed = isDimmed
            if (previouslyDimmed == nowDimmed) {
                Log.d(TAG, "setDimmed($dim) refCount=$dimRefCount (no transition)")
                return@post
            }
            Log.i(TAG, "setDimmed($dim) → $nowDimmed (refCount=$dimRefCount)")
            applyVisibility()
        }
    }

    /** Force-clear the dim ref count. Used on top-level navigation so a
     *  stale dim from before a reload doesn't keep widgets dim post-nav. */
    private fun clearDimState() {
        if (dimRefCount > 0) {
            Log.i(TAG, "clearDimState — clearing refCount=$dimRefCount")
            dimRefCount = 0
        }
    }

    fun isFullscreenSuppressed(): Boolean = fullscreenSuppressed
    fun getMode(): UiMode = mode

    /** Predicate for callers driving their own visibility (e.g. rotation
     *  engine swapping widgets on a tick). Same rule as FULL_ONLY kind. */
    fun shouldShowFull(): Boolean =
        !fullscreenSuppressed && mode == UiMode.FULL

    /** Predicate matching KIOSK_OR_FULL — used by sidebar's leftMargin /
     *  layout calculations and the JS bridge layout-offset getters. */
    fun shouldShowKioskOrFull(): Boolean =
        !fullscreenSuppressed && (mode == UiMode.FULL || mode == UiMode.KIOSK)

    private fun applyVisibility() {
        for ((widget, kind) in widgets) {
            applyVisibilityFor(widget, kind)
        }
        // Dim-only views — sidebar etc. — get the scrim foreground updated
        // without touching their visibility (which they manage themselves).
        for (view in dimOnlyViews) {
            applyDimEffectFor(view)
        }
        onVisibilityChanged?.invoke()
    }

    private fun applyVisibilityFor(widget: View, kind: WidgetKind) {
        when (kind) {
            WidgetKind.FULL_ONLY -> {
                widget.visibility = if (shouldShowFull()) View.VISIBLE else View.INVISIBLE
            }
            WidgetKind.KIOSK_OR_FULL -> {
                widget.visibility = if (shouldShowKioskOrFull()) View.VISIBLE else View.INVISIBLE
            }
            WidgetKind.OVERLAY_FULL_ONLY -> {
                // Asymmetric: force GONE on non-FULL (kills ghosts on reload).
                // Don't touch visibility on FULL — surface's own logic owns show.
                if (!shouldShowFull()) widget.visibility = View.GONE
            }
            WidgetKind.OVERLAY_KIOSK_OR_FULL -> {
                // Asymmetric: force GONE on OFF (or modal-suppressed). Don't
                // touch visibility in FULL/KIOSK — surface's own logic owns show.
                if (!shouldShowKioskOrFull()) widget.visibility = View.GONE
            }
        }

        // Dim pass: apply a black scrim foreground + (API 31+) blur to
        // visible widgets when isDimmed=true. View.foreground draws ON TOP
        // of the widget content rather than blending it with the layer
        // behind, so the result reads as DARKENED rather than TRANSLUCENT
        // (the alpha-based first attempt left widgets bluish/ghostly).
        applyDimEffectFor(widget)
    }

    private fun applyDimEffectFor(widget: View) {
        if (widget in dimOptOutViews) {
            // Explicit opt-out — usually a full-screen container View that
            // would cover the WebView modal if we applied a foreground here.
            widget.foreground = null
            return
        }
        val shouldDim = isDimmed && widget.visibility == View.VISIBLE
        widget.foreground = if (shouldDim) ColorDrawable(DIM_SCRIM_COLOR) else null
    }

    // ── Dim-only registration ────────────────────────────────────────
    // Views that manage their own visibility (notably the native sidebar
    // strip — has gate-driven setGateSuppressed + own VISIBLE/INVISIBLE
    // logic) shouldn't go through the regular `register(view, kind)`
    // path because that would double-manage visibility. They can opt
    // into dim-only treatment here so the JS modal scrim covers them.

    private val dimOnlyViews = mutableSetOf<View>()

    fun registerForDim(view: View) {
        if (dimOnlyViews.add(view)) {
            applyDimEffectFor(view)
            Log.d(TAG, "registerForDim total=${dimOnlyViews.size}")
        }
    }

    fun unregisterForDim(view: View) {
        if (dimOnlyViews.remove(view)) {
            view.foreground = null
            Log.d(TAG, "unregisterForDim total=${dimOnlyViews.size}")
        }
    }
}

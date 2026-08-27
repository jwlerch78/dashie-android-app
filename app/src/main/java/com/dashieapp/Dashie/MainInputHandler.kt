package com.dashieapp.Dashie

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.Toast
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaliteScreenController
import com.dashieapp.Dashie.halite.NativeDialogHost
import com.dashieapp.Dashie.util.DeviceInfoHelper

/**
 * Handles all input events for MainActivity.
 * Extracted to reduce MainActivity size and improve maintainability.
 *
 * Responsibilities:
 * - Key event handling (dispatchKeyEvent, onKeyDown)
 * - Touch event handling (dispatchTouchEvent)
 * - Volume button handling with 0-10 level mapping
 * - Screen wake on input when dimmed
 */
class MainInputHandler(
    private val context: Context,
    private val webView: WebView,
    private val halitePrefs: () -> HalitePreferences?,
    private val callbacks: Callbacks
) {
    companion object {
        private const val TAG = "MainInputHandler"

        // BootPerf instrumentation: log d-pad dispatch duration when it
        // exceeds a frame budget. A spike here means the main thread was
        // busy when the user pressed a key — correlate with concurrent JS
        // or Kotlin init work in the BootPerf timeline.
        private const val DPAD_LAG_THRESHOLD_MS = 16L
        // Also log the first N d-pad events unconditionally so we can see
        // when input first arrives during boot.
        private const val DPAD_FIRST_N_LOG_ALL = 5

        // Hold threshold for d-pad center long-press (Navigate Days, etc.).
        // Matches widget-touch-controls' LongPressDetector default so touch
        // and d-pad feel the same.
        private const val DPAD_CENTER_HOLD_MS = 500L

        // Escape-chord hold threshold for BACK. Used when Dashie is the device
        // launcher and a user needs to bail out to system Settings even though
        // the normal "HOME → launcher → Settings" path is gone.
        private const val ESCAPE_HOLD_MS = 2000L
    }

    private var dpadEventCount: Int = 0

    // ── Escape chord state (long-press BACK) ─────────────────────────────
    // The Onn / Google TV remote auto-repeats KEYCODE_BACK at ~100ms while
    // held, so we can use event.repeatCount + (eventTime - downTime) to
    // detect a sustained hold. We do this in dispatchKeyEvent (before
    // anything else can consume the event) so the chord works even when the
    // sidebar / native widgets are intercepting normal BACK.
    private var backFirstDownTime: Long = 0L
    private var backChordFired: Boolean = false
    // True between a deferred BACK DOWN and its resolution (UP or chord fire).
    private var backPending: Boolean = false
    private val backHoldHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val backHoldRunnable = Runnable {
        if (backPending) {
            backPending = false
            backChordFired = true
            Log.i(TAG, "🆘 Long-press BACK chord fired (>= ${ESCAPE_HOLD_MS}ms) — launching system Settings")
            callbacks.launchSystemSettings()
        }
    }

    // ── DPAD_CENTER long-press state ─────────────────────────────────────
    // Short-press is deferred to ACTION_UP so we can distinguish hold from
    // tap. Only DPAD_CENTER is deferred — all other keys remain instant.
    private enum class CenterPressState { IDLE, PENDING, FIRED }
    private var centerPressState: CenterPressState = CenterPressState.IDLE
    private val centerHoldHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val centerHoldRunnable = Runnable {
        if (centerPressState == CenterPressState.PENDING) {
            centerPressState = CenterPressState.FIRED
            Log.d("KeyDebug", "DPAD_CENTER long-press fired (>= ${DPAD_CENTER_HOLD_MS}ms)")
            callbacks.forwardKeyToJsLongPress(KeyEvent.KEYCODE_DPAD_CENTER)
        }
    }

    private fun startCenterHoldTimer() {
        cancelCenterHoldTimer()
        centerPressState = CenterPressState.PENDING
        centerHoldHandler.postDelayed(centerHoldRunnable, DPAD_CENTER_HOLD_MS)
    }

    private fun cancelCenterHoldTimer() {
        centerHoldHandler.removeCallbacks(centerHoldRunnable)
    }

    /**
     * Wraps the JS forward call for d-pad keys. For DPAD_CENTER, defers the
     * short-press forward until ACTION_UP so we can distinguish from a hold.
     * For all other keys (and DPAD_CENTER auto-repeats), forwards immediately.
     */
    private fun forwardOrDeferCenter(event: KeyEvent) {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount > 0) {
                // OS key auto-repeat — keep any in-flight timer running but
                // don't restart it or re-forward.
                return
            }
            startCenterHoldTimer()
        } else {
            callbacks.forwardKeyToJs(event.keyCode)
        }
    }

    /**
     * Called from handleDispatchKeyEvent when DPAD_CENTER ACTION_UP arrives.
     * Delivers the short-press if the hold timer hasn't already fired.
     *
     * Returns `false` (NOT consumed) so the UP event continues through to
     * the WebView. WebView consumption of DPAD_CENTER UP is required for
     * HTML input IME activation on Fire TV — the WebView wires the IME's
     * InputConnection to the focused <input> when it sees the UP event.
     * Consuming UP here (the original 98c13e84 behavior) silently broke
     * keyboard entry on Fire TV onboarding URL field.
     */
    private fun handleCenterActionUp(): Boolean {
        cancelCenterHoldTimer()
        when (centerPressState) {
            CenterPressState.PENDING -> {
                Log.d("KeyDebug", "DPAD_CENTER short-press (released before ${DPAD_CENTER_HOLD_MS}ms)")
                callbacks.forwardKeyToJs(KeyEvent.KEYCODE_DPAD_CENTER)
            }
            CenterPressState.FIRED -> {
                // Hold already dispatched on the long-press timer.
            }
            CenterPressState.IDLE -> {
                // No DOWN was deferred (e.g. native widget consumed it,
                // screensaver wake, etc.). Nothing to forward.
            }
        }
        centerPressState = CenterPressState.IDLE
        return false
    }

    interface Callbacks {
        fun getDialogHost(): NativeDialogHost?
        fun getHaliteScreenController(): HaliteScreenController?
        fun isAllowUrlConfig(): Boolean
        fun notifyVolumeChange(level: Int)
        fun forwardKeyToJs(keyCode: Int)
        /** Forward a long-press of [keyCode] (currently DPAD_CENTER only).
         *  Bridges to JS window.handleRemoteInputLongPress so focused widgets
         *  can opt into a "select-hold" affordance. */
        fun forwardKeyToJsLongPress(keyCode: Int)
        fun getCurrentFocus(): android.view.View?
        fun isKeyboardShowing(): Boolean
        fun overlayHasKeyboardFocus(): Boolean
        /** True when a native Kotlin overlay (e.g. the crash-report banner) is showing in
         *  the Activity window. When true, d-pad keys are yielded to the native focus system
         *  instead of being forwarded to the JS dashboard, so the overlay's buttons are
         *  reachable by remote on TV devices. The crash AlertDialog doesn't need this — it's
         *  a separate window and gets d-pad natively. */
        fun isNativeOverlayShowing(): Boolean = false
        /** If a native Kotlin widget is d-pad-activated, route the key through
         *  NativeWidgetFocusManager. Returns true if the manager consumed it. */
        fun handleNativeWidgetDpadKey(keyCode: Int): Boolean = false
        /** True when MainHaLoginHandler.launchInlineAuth replaced the WebView
         *  with HA's OAuth page. The only state where BACK on a TV device
         *  must fall through to OnBackPressedCallback (so cancelInlineAuth
         *  can dismiss). See the BACK handler in handleOnKeyDown for why a
         *  URL-substring check ("/login", "/auth/") doesn't work as a
         *  stand-in for "in an HA auth flow." */
        fun isInlineAuthPending(): Boolean = false

        /** Escape hatch: open the system Settings activity. Triggered by the
         *  long-press-BACK chord (held 2s) or KEYCODE_SETTINGS (remote gear
         *  button). Important when Dashie is the device's launcher and a
         *  beta tester needs to reach system Settings without going through
         *  Dashie's own Advanced Settings UI (which may be unreachable if
         *  Dashie is stuck on a non-responsive screen). */
        fun launchSystemSettings() {}

        /** Re-dispatch a short-press BACK through the activity's normal back
         *  pipeline (OnBackPressedDispatcher → cancelInlineAuth / control-center
         *  close / dashieHandleBack). Used only when a deferred BACK turns out
         *  to be a tap during an inline-auth flow. */
        fun performBackPressed() {}
    }

    // Reusable toast for volume feedback
    private var volumeToast: Toast? = null

    /**
     * Handle key events at dispatch level to ensure screen wake works for ALL keys.
     * dispatchKeyEvent runs before onKeyDown, so we can intercept here first.
     *
     * IMPORTANT: D-pad keys MUST be handled here (not in onKeyDown) because WebView
     * consumes them for scrolling in its dispatchKeyEvent(), preventing onKeyDown from being called.
     *
     * @return true if the event was consumed (screen was woken or forwarded to JS), false to continue normal handling
     */
    fun handleDispatchKeyEvent(event: KeyEvent): Boolean {
        // ── Escape chord: long-press BACK (timer-based, defer-on-down) ───
        // Runs FIRST, no mode gating. Holding BACK >= ESCAPE_HOLD_MS opens
        // system Settings — the universal escape hatch when Dashie is the
        // launcher. We arm a TIMER on the first DOWN instead of relying on key
        // auto-repeat (some remotes / screens don't emit it), and CONSUME the
        // press so it can't pop a focus-stealing native dialog (e.g. the
        // sign-in exit confirmation) mid-hold — which previously broke the
        // chord. A short press is re-delivered through the normal handlers on
        // ACTION_UP, so tap-BACK behaves exactly as before.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            val screen = callbacks.getHaliteScreenController()
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    // Screen asleep → BACK just wakes it; don't arm the chord.
                    if (screen?.screenDimmer?.isDimmed() == true) {
                        screen.screenDimmer?.resetTimer()
                        screen.resetSleepInactivityTimer()
                        return true
                    }
                    backChordFired = false
                    backPending = true
                    backFirstDownTime = event.eventTime
                    backHoldHandler.removeCallbacks(backHoldRunnable)
                    backHoldHandler.postDelayed(backHoldRunnable, ESCAPE_HOLD_MS)
                }
                // Consume initial + any auto-repeat DOWNs while we decide.
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                backHoldHandler.removeCallbacks(backHoldRunnable)
                if (backChordFired) {
                    // Long-press already opened Settings — swallow the release.
                    backChordFired = false
                    backPending = false
                    return true
                }
                if (backPending) {
                    backPending = false
                    // Short press — deliver BACK now through the same handlers
                    // the initial DOWN used to hit (native widget → inline-auth
                    // cancel → JS dashboard).
                    screen?.screenDimmer?.resetTimer()
                    screen?.resetSleepInactivityTimer()
                    if (!callbacks.handleNativeWidgetDpadKey(KeyEvent.KEYCODE_BACK)) {
                        if (callbacks.isInlineAuthPending()) {
                            callbacks.performBackPressed()
                        } else if (BuildConfig.FLAVOR == "amazon") {
                            // Amazon Fire TV: route through onBackPressedDispatcher
                            // (performBackPressed) so MainActivity's amazon-flavor
                            // back-handler runs — it queries dashieShouldShowExitConfirm
                            // and shows the native "Exit Dashie?" dialog when the
                            // dashboard is idle on home (Amazon controller guideline).
                            // Without this, BACK forwards straight to JS handleRemoteInput
                            // → 'escape' action → sidebar focus, which is the loop
                            // Amazon's reviewer flagged. The non-idle fallback inside
                            // onBackPressed will call handleRemoteInput itself so
                            // widget focus, modal close, sidebar dismiss, etc. all
                            // keep their existing behavior.
                            callbacks.performBackPressed()
                        } else {
                            callbacks.forwardKeyToJs(KeyEvent.KEYCODE_BACK)
                        }
                    }
                }
                return true
            }
        }

        if (!callbacks.isAllowUrlConfig()) return false

        // DPAD_CENTER long-press lifecycle: ACTION_UP delivers either the
        // short-press (deferred from DOWN) or nothing (if hold already fired).
        // handleCenterActionUp() returns false so the UP event also passes
        // through to the WebView — required for IME activation on focused
        // HTML inputs (Fire TV). Must run before the general
        // "skip non-DOWN" filter below.
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_UP) {
            return handleCenterActionUp()
        }

        if (event.action != KeyEvent.ACTION_DOWN) return false

        // Media keys (FF/RW/Play-Pause) need to be claimed at dispatch
        // BEFORE Android's MediaSession framework intercepts them. On
        // Fire TV the OZ-DCS receiver and Amazon's media controller
        // both consume these keys system-wide if we don't claim them
        // here first. Forward to JS and consume.
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            Log.d("KeyDebug", "dispatchKeyEvent: Media key ${KeyEvent.keyCodeToString(event.keyCode)} → forwarding to JS")
            callbacks.forwardKeyToJs(event.keyCode)
            return true
        }

        // Channel rocker → calendar (main widget) period nav. These TV keys are
        // otherwise unused, so always forward to JS (which maps 166→next,
        // 167→prev on the main widget) and consume — same always-on treatment as
        // the FF/RW media keys above. repeatCount==0 → one period per physical
        // press (no auto-fly when held).
        if ((event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP ||
             event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) &&
            event.repeatCount == 0) {
            Log.i(TAG, "dispatchKeyEvent: Channel key ${KeyEvent.keyCodeToString(event.keyCode)} → forwarding to JS (calendar nav)")
            callbacks.forwardKeyToJs(event.keyCode)
            return true
        }

        val isDpadOrCenter = event.keyCode in listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER
        )
        val dispatchStartMs = if (isDpadOrCenter) android.os.SystemClock.elapsedRealtime() else 0L
        val result = handleDispatchKeyEventInner(event)
        if (isDpadOrCenter) {
            val durationMs = android.os.SystemClock.elapsedRealtime() - dispatchStartMs
            dpadEventCount += 1
            if (dpadEventCount <= DPAD_FIRST_N_LOG_ALL || durationMs >= DPAD_LAG_THRESHOLD_MS) {
                com.dashieapp.Dashie.halite.diagnostics.BootPerf.mark(
                    "dpad-event#$dpadEventCount keyCode=${event.keyCode} took=${durationMs}ms"
                )
            }
        }
        return result
    }

    private fun handleDispatchKeyEventInner(event: KeyEvent): Boolean {

        val screenController = callbacks.getHaliteScreenController()

        // Check if screensaver is active - wake on any key
        if (screenController?.screenDimmer?.isDimmed() == true) {
            Log.d("KeyDebug", "dispatchKeyEvent: Screen dimmed, waking on key: ${event.keyCode}")
            screenController.screenDimmer?.resetTimer()
            screenController.resetSleepInactivityTimer()
            return true  // Consume the key, just wake screen
        }

        // Handle d-pad keys here to prevent WebView from consuming them for scrolling
        // WebView.dispatchKeyEvent() consumes d-pad keys before onKeyDown() is called
        val isDpadKey = event.keyCode in listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER
        )

        // If a native Kotlin widget is d-pad-activated, it owns all d-pad input.
        // Check for nav keys including BACK/ESCAPE so the widget can yield cleanly.
        val isNavKey = isDpadKey ||
                event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
                event.keyCode == KeyEvent.KEYCODE_ENTER
        if (isNavKey && callbacks.handleNativeWidgetDpadKey(event.keyCode)) {
            Log.d("KeyDebug", "dispatchKeyEvent: Native widget consumed key: ${event.keyCode}")
            screenController?.screenDimmer?.resetTimer()
            screenController?.resetReturnHomeTimer()
            screenController?.resetSleepInactivityTimer()
            return true
        }

        if (isDpadKey) {
            // A native Kotlin overlay (crash-report banner) is showing in the Activity
            // window — yield d-pad/center to the native focus system so its buttons
            // (View/Dismiss) are reachable by remote. Returning false lets
            // super.dispatchKeyEvent route the key to the focused native Button instead
            // of forwarding it to the JS dashboard (which would consume it). Without this,
            // a crash report can't be viewed or dismissed by remote on TV. (DPAD_CENTER UP
            // is handled earlier in handleDispatchKeyEvent and also passes through.)
            if (callbacks.isNativeOverlayShowing()) {
                Log.d("KeyDebug", "dispatchKeyEvent: native overlay showing — yielding ${KeyEvent.keyCodeToString(event.keyCode)} to native focus")
                return false
            }

            // Login page: forward d-pad to JS (handleRemoteInput) which routes to
            // the auth page D-pad handler. This handler traverses HA's shadow DOM
            // to find and focus input fields. We can't let WebView handle natively
            // because HA uses shadow DOM web components that WebView's D-pad focus
            // traversal can't reach. Dashie's own /login page installs a lightweight
            // handleRemoteInput inline (see login/index.html) for the same purpose.
            val currentUrl = webView.url ?: ""
            val isLoginUrl = currentUrl.contains("/auth/authorize") || currentUrl.contains("/auth/login") || currentUrl.contains("/login")
            val accountLinked = halitePrefs()?.account?.isLinked == true
            if (isLoginUrl && !accountLinked) {
                Log.d("KeyDebug", "dispatchKeyEvent: On login page, forwarding d-pad to JS auth handler: ${event.keyCode}")
                forwardOrDeferCenter(event)
                return true
            }

            // During onboarding — forward d-pad to JS for custom focus management
            // (prevents WebView from auto-focusing inputs)
            val isOnboarding = halitePrefs()?.connection?.isSetupComplete != true
            if (isOnboarding) {
                Log.d("KeyDebug", "dispatchKeyEvent: Onboarding active, forwarding d-pad to JS: ${event.keyCode}")
                forwardOrDeferCenter(event)
                return true
            }

            // If overlay has keyboard focus (settings modal open), forward ALL d-pad to JS
            if (callbacks.overlayHasKeyboardFocus()) {
                Log.d("KeyDebug", "dispatchKeyEvent: Overlay has focus, forwarding d-pad to JS: ${event.keyCode}")
                forwardOrDeferCenter(event)

                // Reset timers on d-pad navigation
                screenController?.screenDimmer?.resetTimer()
                screenController?.resetReturnHomeTimer()
                screenController?.resetSleepInactivityTimer()

                return true  // Consume the key to prevent WebView from scrolling
            }

            // Forward ALL d-pad keys to JS for unified dashboard navigation.
            Log.d("KeyDebug", "dispatchKeyEvent: D-pad ${KeyEvent.keyCodeToString(event.keyCode)}, forwarding to JS")
            forwardOrDeferCenter(event)

            // Reset timers on d-pad navigation
            screenController?.screenDimmer?.resetTimer()
            screenController?.resetReturnHomeTimer()
            screenController?.resetSleepInactivityTimer()

            return true
        }

        return false
    }

    /**
     * Handle touch events for edge swipe detection and screensaver wake.
     * Process touches AFTER normal WebView handling (non-blocking).
     *
     * @return true if touch was handled
     */
    fun handleDispatchTouchEvent(event: MotionEvent): Boolean {
        if (!callbacks.isAllowUrlConfig()) return false

        // DEBUG: Log keyboard state and focus info on touch
        if (event.action == MotionEvent.ACTION_DOWN) {
            val focusedView = callbacks.getCurrentFocus()
            val isKbShowing = callbacks.isKeyboardShowing()
            Log.d("TouchDebug", "Touch DOWN - keyboard=$isKbShowing, focus=${focusedView?.javaClass?.simpleName ?: "null"}, webView.hasFocus=${webView.hasFocus()}")

            val screenController = callbacks.getHaliteScreenController()

            // When the screensaver is active (dimmed), don't wake on touch here.
            // PiP card interactions (close, play/pause, drag, etc.) should not exit
            // the screensaver. The dimOverlay's own touch listener handles wake for
            // taps on empty areas (which sets isDimmed=false before we get here).
            if (screenController?.screenDimmer?.isDimmed() != true) {
                // Reset screensaver timer on touch (prevents screensaver during active use)
                screenController?.screenDimmer?.resetTimer()

                // Reset return-to-home timer on touch
                screenController?.resetReturnHomeTimer()

                // Reset sleep inactivity timer on touch
                screenController?.resetSleepInactivityTimer()
            }
        }

        // Handle edge swipe via NativeDialogHost (which manages the gesture handler)
        callbacks.getDialogHost()?.handleTouchEvent(event)

        return false  // Always let parent also process
    }

    /**
     * Handle key down events for navigation and volume.
     *
     * @return Pair of (handled, shouldCallSuper) - handled=true means we consumed the event,
     *         shouldCallSuper indicates if super.onKeyDown should be called
     */
    fun handleOnKeyDown(keyCode: Int, event: KeyEvent?): KeyHandleResult {
        // Escape chord: dedicated SETTINGS button on remotes that emit
        // KEYCODE_SETTINGS at the app layer. Note: on Onn / Google TV the
        // OS consumes this keycode at framework level before delivery, so
        // it's a no-op there. Kept for remotes / hardware that do deliver
        // it. The universal escape chord is long-press BACK, handled in
        // handleDispatchKeyEvent.
        if (keyCode == KeyEvent.KEYCODE_SETTINGS) {
            Log.i(TAG, "⚙️ KEYCODE_SETTINGS pressed — launching system Settings (escape chord)")
            callbacks.launchSystemSettings()
            return KeyHandleResult(handled = true, callSuper = false)
        }

        // DEBUG: Log ALL key events
        if (callbacks.isAllowUrlConfig()) {
            Log.d("KeyDebug", "onKeyDown: keyCode=$keyCode (${KeyEvent.keyCodeToString(keyCode)}), source=${event?.source}, deviceId=${event?.deviceId}")
        }

        val screenController = callbacks.getHaliteScreenController()
        val prefs = halitePrefs()

        // Halite: Any key press wakes the screen if dimmed
        if (callbacks.isAllowUrlConfig() && screenController?.screenDimmer?.isDimmed() == true) {
            Log.d("KeyDebug", "Screen dimmed, waking on key press: $keyCode")
            screenController.screenDimmer?.resetTimer()
            screenController.resetSleepInactivityTimer()
            return KeyHandleResult(handled = true, callSuper = false)
        }

        // Halite: Reset screen dimmer timer on navigation key press
        if (callbacks.isAllowUrlConfig()) {
            val isNavigationKey = keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE
            )
            if (isNavigationKey) {
                screenController?.screenDimmer?.resetTimer()
                screenController?.resetReturnHomeTimer()
                screenController?.resetSleepInactivityTimer()
            }
        }

        // For Halite: Special handling for d-pad
        if (callbacks.isAllowUrlConfig()) {
            val isDpadKey = keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER
            )
            if (isDpadKey) {
                // Login page: already handled in dispatchKeyEvent (forwarded to JS auth handler).
                // Consume here to prevent duplicate handling.
                val currentUrl = webView.url ?: ""
                val isLoginUrl = currentUrl.contains("/auth/authorize") || currentUrl.contains("/auth/login") || currentUrl.contains("/login")
                val accountLinked = halitePrefs()?.account?.isLinked == true
                if (isLoginUrl && !accountLinked) {
                    Log.d("KeyDebug", "D-pad: On login page, already handled in dispatchKeyEvent")
                    return KeyHandleResult(handled = true, callSuper = false)
                }

                // Otherwise, forward to overlay (for settings modal or HA dashboard navigation)
            }
        }

        // Halite: D-pad LEFT — always forward to JS (dash bar handles navigation)
        if (callbacks.isAllowUrlConfig() && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            Log.d("KeyDebug", "D-pad LEFT: Forwarding to JS")
            callbacks.forwardKeyToJs(keyCode)
            return KeyHandleResult(handled = true, callSuper = false)
        }

        // Halite: BACK/ESCAPE handling
        // Forward to JS for onboarding, settings overlay, and dash bar toggle.
        if (callbacks.isAllowUrlConfig() && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
            // If overlay has keyboard focus, forward to overlay (closes settings, etc.)
            if (callbacks.overlayHasKeyboardFocus()) {
                Log.d("KeyDebug", "BACK/ESCAPE: Overlay has focus, forwarding to JS: $keyCode")
                callbacks.forwardKeyToJs(keyCode)
                return KeyHandleResult(handled = true, callSuper = false)
            }

            val isTvDevice = DeviceInfoHelper.isFireTV() || DeviceInfoHelper.isTVDevice(context)

            if (isTvDevice) {
                // D.84 (revised 2026-05-22): only intercept BACK when inline
                // HA auth is actively pending — that's the one state where
                // forwarding to JS is a silent no-op (HA's OAuth page has
                // no window.dashieHandleBack) and we instead need
                // MainActivity.onBackPressedCallback → cancelInlineAuth().
                //
                // Earlier D.84 fix gated on `currentUrl.contains("/login")
                // || currentUrl.contains("/auth/")`. But /index.html
                // redirects to `/login/` as Dashie's SPA entry, so
                // signed-in users on the dashboard ALSO match the URL
                // substring. That made BACK on dashboard widgets fall
                // through to dashieHandleBack — undefined in the full
                // Dashie context — breaking widget defocus + sidebar
                // refocus on Fire TV (regression observed 2026-05-22).
                if (!callbacks.isInlineAuthPending()) {
                    Log.d("KeyDebug", "BACK/ESCAPE: TV device, forwarding to JS: $keyCode")
                    callbacks.forwardKeyToJs(keyCode)
                    return KeyHandleResult(handled = true, callSuper = false)
                }
                // Otherwise (inline auth pending) fall through so
                // OnBackPressedCallback → cancelInlineAuth() runs.
            } else {
                // Touch devices: let BACK propagate to OnBackPressedCallback
                Log.d("KeyDebug", "BACK: Touch device, letting OnBackPressedCallback handle")
                return KeyHandleResult(handled = false, callSuper = true)
            }
        }

        // Handle specific key codes
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                callbacks.forwardKeyToJs(keyCode)
                KeyHandleResult(handled = true, callSuper = false)
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                handleVolumeKey(isUp = true, lockToApp = prefs?.lock?.lockToApp == true)
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                handleVolumeKey(isUp = false, lockToApp = prefs?.lock?.lockToApp == true)
            }
            else -> KeyHandleResult(handled = false, callSuper = true)
        }
    }

    /**
     * Handle volume key press.
     * When Lock to App is enabled, don't call super (blocks system volume UI).
     */
    private fun handleVolumeKey(isUp: Boolean, lockToApp: Boolean): KeyHandleResult {
        if (callbacks.isAllowUrlConfig() && lockToApp) {
            handleVolumeButtonPress(isUp = isUp, showToast = true)
            return KeyHandleResult(handled = true, callSuper = false)
        } else {
            handleVolumeButtonPress(isUp = isUp, showToast = false)
            return KeyHandleResult(handled = false, callSuper = true)
        }
    }

    /**
     * Handle volume button press - read current level, apply 0-10 increment, overwrite.
     * @param isUp True for volume up, false for volume down
     * @param showToast True to show a toast with the new level (used when system UI is blocked)
     */
    private fun handleVolumeButtonPress(isUp: Boolean, showToast: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxSteps = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        // Convert current step to 0-10 level
        val currentLevel = if (maxSteps > 0) Math.round(currentStep * 10.0f / maxSteps).toInt() else 0

        // Apply our increment/decrement
        val newLevel = if (isUp) {
            (currentLevel + 1).coerceIn(0, 10)
        } else {
            (currentLevel - 1).coerceIn(0, 10)
        }

        // Convert back to device steps and set
        val newStep = Math.round(newLevel * maxSteps / 10.0f).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newStep, 0)

        Log.d(TAG, "Volume button ${if (isUp) "UP" else "DOWN"}: level $currentLevel -> $newLevel (step $currentStep -> $newStep)")

        // Show toast when system volume UI is blocked (Lock Nav Bar mode)
        if (showToast) {
            volumeToast?.cancel()
            volumeToast = Toast.makeText(context, "Volume: $newLevel", Toast.LENGTH_SHORT)
            volumeToast?.show()
        }

        // Notify web app
        callbacks.notifyVolumeChange(newLevel)
    }

    /**
     * Result of key handling.
     * @param handled True if the event was fully consumed
     * @param callSuper True if super.onKeyDown should still be called
     */
    data class KeyHandleResult(val handled: Boolean, val callSuper: Boolean)
}
